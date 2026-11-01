package edu.osaka_u.fles;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Records mono 16-bit PCM audio and writes a standard WAV file.
 *
 * Call start() from a worker thread. Call requestStop() when the user releases
 * the record button, then call awaitResult() from a worker thread.
 *
 * The temporary file ends with .wav.part. It is renamed to .wav only after
 * the WAV header has been finalized successfully.
 */
public final class VoiceRecorder {

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_COUNT = 1;
    public static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final long STOP_TIMEOUT_SECONDS = 10L;

    /** 按下后立刻松开、一点音频都没采到：不是故障，调用方应当当作“录音太短”处理。 */
    public static final class EmptyRecordingException extends IOException {
        public EmptyRecordingException() {
            super("No audio data was captured");
        }
    }

    public static final class Result {
        public final File file;
        public final long startedAt;
        public final long endedAt;
        public final long durationMs;
        public final long pcmBytes;
        public final long fileSizeBytes;
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;

        Result(File file, long startedAt, long endedAt, long durationMs,
               long pcmBytes, long fileSizeBytes) {
            this.file = file;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.durationMs = durationMs;
            this.pcmBytes = pcmBytes;
            this.fileSizeBytes = fileSizeBytes;
            this.sampleRate = SAMPLE_RATE;
            this.channels = CHANNEL_COUNT;
            this.bitsPerSample = BITS_PER_SAMPLE;
        }
    }

    private final File outputFile;
    private final File temporaryFile;
    private final long startedAt;
    private final CountDownLatch finishedLatch = new CountDownLatch(1);

    private volatile boolean stopRequested;
    private volatile Result result;
    private volatile IOException failure;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean started;

    public VoiceRecorder(File outputFile, long startedAt) {
        if (outputFile == null) throw new IllegalArgumentException("outputFile is null");
        this.outputFile = outputFile;
        this.temporaryFile = new File(outputFile.getParentFile(), outputFile.getName() + ".part");
        this.startedAt = startedAt;
    }

    /**
     * Creates the AudioRecord instance and starts the background PCM writer.
     *
     * The caller (MainActivity) asks the user for the microphone permission, but the
     * permission is verified again here, right before the microphone is touched: the user
     * can revoke it at any moment, and this method runs later on a worker thread.
     *
     * @throws SecurityException if RECORD_AUDIO has not been granted
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked explicitly just below
    public synchronized void start(Context context) throws IOException {
        if (started) throw new IllegalStateException("Recorder has already been started");
        if (context == null) throw new IllegalArgumentException("context is null");
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RECORD_AUDIO permission has not been granted");
        }

        File parent = outputFile.getParentFile();
        if (parent == null) throw new IOException("Output directory is missing");
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create recording directory: " + parent.getAbsolutePath());
        }
        if (outputFile.exists() || temporaryFile.exists()) {
            throw new IOException("Recording file already exists");
        }

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING);
        if (minBuffer <= 0) {
            throw new IOException("AudioRecord.getMinBufferSize failed: " + minBuffer);
        }
        int bufferSize = Math.max(minBuffer * 2, 4096);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_ENCODING,
                bufferSize
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            throw new IOException("AudioRecord initialization failed");
        }

        try {
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("AudioRecord did not enter recording state");
            }
            started = true;
            recordingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    recordLoop(bufferSize);
                }
            }, "FLES-AudioRecord");
            recordingThread.start();
        } catch (RuntimeException | IOException e) {
            safeStopAndRelease();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Unable to start audio recording", e);
        }
    }

    public File getOutputFile() {
        return outputFile;
    }

    /** Non-blocking; safe to call from the UI thread on ACTION_UP/ACTION_CANCEL. */
    public void requestStop() {
        stopRequested = true;
    }

    /**
     * Waits for WAV finalization. Do not call this on the main/UI thread.
     */
    public Result awaitResult() throws IOException, InterruptedException {
        requestStop();
        boolean completed = finishedLatch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) throw new IOException("Timed out while finalizing WAV recording");
        if (failure != null) throw failure;
        if (result == null) throw new IOException("Recording produced no result");
        return result;
    }

    private void recordLoop(int requestedBufferSize) {
        long pcmBytes = 0L;
        IOException error = null;
        RandomAccessFile wav = null;
        try {
            wav = new RandomAccessFile(temporaryFile, "rw");
            wav.setLength(0L);
            wav.write(new byte[44]); // reserve the standard WAV header

            byte[] buffer = new byte[Math.max(requestedBufferSize, 4096)];
            while (!stopRequested) {
                int count = audioRecord.read(buffer, 0, buffer.length);
                if (count > 0) {
                    wav.write(buffer, 0, count);
                    pcmBytes += count;
                } else if (count < 0) {
                    throw new IOException("AudioRecord.read failed: " + count);
                }
            }

            if (pcmBytes <= 0L) throw new EmptyRecordingException();

            writeWavHeader(wav, pcmBytes);
            wav.getFD().sync();
        } catch (IOException e) {
            error = e;
        } catch (RuntimeException e) {
            error = new IOException("Unexpected recording error", e);
        } finally {
            if (wav != null) {
                try { wav.close(); } catch (IOException ignored) { }
            }
            safeStopAndRelease();
        }

        long endedAt = System.currentTimeMillis();
        if (error == null) {
            if (outputFile.exists() && !outputFile.delete()) {
                error = new IOException("Cannot replace existing output file");
            } else if (!temporaryFile.renameTo(outputFile)) {
                error = new IOException("Cannot finalize WAV file: rename failed");
            }
        }

        if (error == null) {
            long bytesPerSecond = (long) SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8L;
            long duration = (pcmBytes * 1000L) / bytesPerSecond;
            result = new Result(outputFile, startedAt, endedAt, duration,
                    pcmBytes, outputFile.length());
        } else {
            failure = error;
            if (temporaryFile.exists()) temporaryFile.delete();
            if (outputFile.exists() && outputFile.length() == 0L) outputFile.delete();
        }
        finishedLatch.countDown();
    }

    private static void writeWavHeader(RandomAccessFile file, long pcmBytes) throws IOException {
        long totalDataLength = pcmBytes + 36L;
        long byteRate = (long) SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8L;
        int blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8;

        file.seek(0L);
        file.writeBytes("RIFF");
        writeIntLittleEndian(file, totalDataLength);
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeIntLittleEndian(file, 16L);              // PCM fmt chunk length
        writeShortLittleEndian(file, 1);               // PCM format
        writeShortLittleEndian(file, CHANNEL_COUNT);
        writeIntLittleEndian(file, SAMPLE_RATE);
        writeIntLittleEndian(file, byteRate);
        writeShortLittleEndian(file, blockAlign);
        writeShortLittleEndian(file, BITS_PER_SAMPLE);
        file.writeBytes("data");
        writeIntLittleEndian(file, pcmBytes);
    }

    private static void writeIntLittleEndian(RandomAccessFile file, long value) throws IOException {
        file.write((int) (value & 0xff));
        file.write((int) ((value >> 8) & 0xff));
        file.write((int) ((value >> 16) & 0xff));
        file.write((int) ((value >> 24) & 0xff));
    }

    private static void writeShortLittleEndian(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >> 8) & 0xff);
    }

    private void safeStopAndRelease() {
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
            } catch (RuntimeException ignored) { }
            try { record.release(); } catch (RuntimeException ignored) { }
        }
    }

    /** Deletes unfinished .wav.part files after an app/process interruption. */
    public static void cleanupPartialFiles(File recordingDirectory) {
        if (recordingDirectory == null || !recordingDirectory.isDirectory()) return;
        File[] files = recordingDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".wav.part")) file.delete();
        }
    }
}
