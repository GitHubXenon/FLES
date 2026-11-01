package edu.osaka_u.fles;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FLES 本地数据库。
 * 所有学习数据只保存在 app 私有目录中的 fles.db。
 */
public class FlesDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "fles.db";
    private static final int DB_VERSION = 2;

    public FlesDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    // ---------------------------------------------------------------------
    // 内部数据类：避免额外创建 Java 文件
    // ---------------------------------------------------------------------

    public static class Sentence {
        public String sentenceKey = "";
        public int serialNo;
        public String section = "";
        public String targetSentence = "";
        public String nativeMeaning = "";
        public String coverage = "";
        public String memoryHint = "";
        public String pronunciationGuide = "";
        public String pronunciationAudioFile = ""; // 暂时只保留字段，不使用音频
        public boolean isActive;
        public boolean isFavorited;
        public boolean isPermanentlyMastered;
    }

    public static class MasteryResult {
        public int consecutiveDays;
        public boolean isPermanentlyMastered;
        public boolean alreadyClickedToday;
        public boolean becamePermanentNow;
    }

    /** 一段录音：通过 attemptId 对应唯一一条卡片记录（study_attempts.id）。 */
    public static class VoiceRecording {
        public long id;
        public long sessionId;      // 学习记录 ID（study_sessions.id）
        public long attemptId;      // 卡片记录 ID（study_attempts.id）
        public int sequenceNo;      // 这是该卡片记录里的第几段录音，从 1 开始
        public String sentenceKey = "";
        public String fileName = "";
        public String relativePath = "";
        public long startedAt;
        public long endedAt;
        public long durationMs;
        public int sampleRate;
        public int channels;
        public int bitsPerSample;
        public long fileSizeBytes;
        public String stopReason = "";
    }

    public static class HomeCounts {
        public int activeCount;
        public int masteryCount;
        public int favoriteCount;
    }

    // ---------------------------------------------------------------------
    // SQLite 生命周期
    // ---------------------------------------------------------------------

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sentences (" +
                "sentence_key TEXT PRIMARY KEY NOT NULL," +
                "serial_no INTEGER NOT NULL DEFAULT 0," +
                "section TEXT NOT NULL DEFAULT ''," +
                "target_sentence TEXT NOT NULL," +
                "native_meaning TEXT NOT NULL DEFAULT ''," +
                "coverage TEXT NOT NULL DEFAULT ''," +
                "memory_hint TEXT NOT NULL DEFAULT ''," +
                "pronunciation_guide TEXT NOT NULL DEFAULT ''," +
                "pronunciation_audio_file TEXT NOT NULL DEFAULT ''," +
                "is_active INTEGER NOT NULL DEFAULT 1," +
                "first_imported_at INTEGER NOT NULL" +
                ")");

        db.execSQL("CREATE TABLE favorites (" +
                "sentence_key TEXT PRIMARY KEY NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY(sentence_key) REFERENCES sentences(sentence_key)" +
                ")");

        db.execSQL("CREATE TABLE mastery_state (" +
                "sentence_key TEXT PRIMARY KEY NOT NULL," +
                "last_mastery_date TEXT NOT NULL," +
                "consecutive_days INTEGER NOT NULL DEFAULT 0," +
                "is_permanently_mastered INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(sentence_key) REFERENCES sentences(sentence_key)" +
                ")");

        db.execSQL("CREATE TABLE mastery_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sentence_key TEXT NOT NULL," +
                "local_date TEXT NOT NULL," +
                "clicked_at INTEGER NOT NULL," +
                "consecutive_days_after INTEGER NOT NULL," +
                "became_permanent INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(sentence_key, local_date)," +
                "FOREIGN KEY(sentence_key) REFERENCES sentences(sentence_key)" +
                ")");

        db.execSQL("CREATE TABLE study_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER," +
                "end_reason TEXT NOT NULL DEFAULT ''" +
                ")");

        db.execSQL("CREATE TABLE study_attempts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL," +
                "sentence_key TEXT NOT NULL," +
                "prompt_direction TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "end_reason TEXT NOT NULL DEFAULT ''," +
                "answer_revealed INTEGER NOT NULL DEFAULT 0," +
                "memory_hint_revealed INTEGER NOT NULL DEFAULT 0," +
                "pronunciation_guide_revealed INTEGER NOT NULL DEFAULT 0," +
                "favorite_toggled INTEGER NOT NULL DEFAULT 0," +
                "mastery_clicked INTEGER NOT NULL DEFAULT 0," +
                "recording_count INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY(session_id) REFERENCES study_sessions(id)," +
                "FOREIGN KEY(sentence_key) REFERENCES sentences(sentence_key)" +
                ")");

        db.execSQL("CREATE TABLE study_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "attempt_id INTEGER," +
                "session_id INTEGER," +
                "sentence_key TEXT NOT NULL," +
                "event_type TEXT NOT NULL," +
                "occurred_at INTEGER NOT NULL," +
                "elapsed_ms INTEGER," +
                "FOREIGN KEY(attempt_id) REFERENCES study_attempts(id)," +
                "FOREIGN KEY(session_id) REFERENCES study_sessions(id)," +
                "FOREIGN KEY(sentence_key) REFERENCES sentences(sentence_key)" +
                ")");

        db.execSQL("CREATE INDEX idx_sentences_active ON sentences(is_active)");
        db.execSQL("CREATE INDEX idx_attempts_sentence ON study_attempts(sentence_key)");
        db.execSQL("CREATE INDEX idx_attempts_session ON study_attempts(session_id)");
        db.execSQL("CREATE INDEX idx_events_sentence ON study_events(sentence_key)");
        db.execSQL("CREATE INDEX idx_events_attempt ON study_events(attempt_id)");
        db.execSQL("CREATE INDEX idx_mastery_permanent ON mastery_state(is_permanently_mastered)");

        createVoiceRecordingsTable(db);
    }

    /** 录音表。onCreate（全新安装）和 onUpgrade（1 -> 2）共用，保证两条路径得到相同结构。 */
    private void createVoiceRecordingsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS voice_recordings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL," +
                "attempt_id INTEGER NOT NULL," +
                "sequence_no INTEGER NOT NULL," +
                "sentence_key TEXT NOT NULL," +
                "file_name TEXT NOT NULL UNIQUE," +
                "relative_path TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER NOT NULL," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "sample_rate INTEGER NOT NULL," +
                "channels INTEGER NOT NULL," +
                "bits_per_sample INTEGER NOT NULL," +
                "file_size_bytes INTEGER NOT NULL DEFAULT 0," +
                "stop_reason TEXT NOT NULL DEFAULT ''," +
                "UNIQUE(attempt_id, sequence_no)," +
                "FOREIGN KEY(session_id) REFERENCES study_sessions(id)," +
                "FOREIGN KEY(attempt_id) REFERENCES study_attempts(id)," +
                "FOREIGN KEY(sentence_key) REFERENCES sentences(sentence_key)" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_voice_attempt ON voice_recordings(attempt_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_voice_session ON voice_recordings(session_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_voice_sentence ON voice_recordings(sentence_key)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 不要 DROP TABLE，也不要清空用户的学习记录。只增不删。
        if (oldVersion < 2) {
            // v2：卡片记录增加“是否点过提示”等字段，并新增录音表。
            db.execSQL("ALTER TABLE study_attempts ADD COLUMN answer_revealed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE study_attempts ADD COLUMN memory_hint_revealed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE study_attempts ADD COLUMN pronunciation_guide_revealed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE study_attempts ADD COLUMN favorite_toggled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE study_attempts ADD COLUMN mastery_clicked INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE study_attempts ADD COLUMN recording_count INTEGER NOT NULL DEFAULT 0");
            createVoiceRecordingsTable(db);
        }
    }

    // ---------------------------------------------------------------------
    // 句子身份规则
    // ---------------------------------------------------------------------

    /**
     * 唯一身份就是规范化后的外语例句本身。
     * 只做 Unicode NFC 和首尾空白处理；保留内部空格、标点和大小写。
     */
    public static String normalizeSentenceKey(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.trim(), Normalizer.Form.NFC);
    }

    // ---------------------------------------------------------------------
    // Excel 导入后的 SQLite 同步
    // ---------------------------------------------------------------------

    /**
     * 将本次 Excel 中的句子集合同步进数据库。
     * 返回本次真正新增的句子数。
     *
     * 规则：
     * 1. 当前所有句子先设为 inactive；
     * 2. 新句子插入所有字段；
     * 3. 已存在的句子只恢复 active，不覆盖第一次导入的字段；
     * 4. 不删除任何 sentences 行、收藏和学习历史。
     */
    public int syncImportedSentences(List<Sentence> imported) {
        if (imported == null) throw new IllegalArgumentException("imported list is null");

        SQLiteDatabase db = getWritableDatabase();
        int insertedCount = 0;
        long now = System.currentTimeMillis();
        Set<String> seenInThisFile = new HashSet<String>();

        db.beginTransaction();
        try {
            db.execSQL("UPDATE sentences SET is_active = 0");

            for (Sentence sentence : imported) {
                if (sentence == null) continue;
                String key = normalizeSentenceKey(sentence.targetSentence);
                if (key.length() == 0) continue;
                if (!seenInThisFile.add(key)) continue; // 同一导入文件重复句子只保留第一次

                ContentValues newRow = new ContentValues();
                newRow.put("sentence_key", key);
                newRow.put("serial_no", sentence.serialNo);
                newRow.put("section", safe(sentence.section));
                newRow.put("target_sentence", key);
                newRow.put("native_meaning", safe(sentence.nativeMeaning));
                newRow.put("coverage", safe(sentence.coverage));
                newRow.put("memory_hint", safe(sentence.memoryHint));
                newRow.put("pronunciation_guide", safe(sentence.pronunciationGuide));
                newRow.put("pronunciation_audio_file", safe(sentence.pronunciationAudioFile));
                newRow.put("is_active", 1);
                newRow.put("first_imported_at", now);

                long rowId = db.insertWithOnConflict(
                        "sentences", null, newRow, SQLiteDatabase.CONFLICT_IGNORE);
                if (rowId != -1) insertedCount++;

                // 已存在的句子只更新 active，其他字段一律不更新。
                ContentValues active = new ContentValues();
                active.put("is_active", 1);
                db.update("sentences", active, "sentence_key = ?", new String[]{key});
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return insertedCount;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------------
    // 读取句子
    // ---------------------------------------------------------------------

    /** 当前动态题库中处于 active 状态的句子数量。 */
    public int countActiveSentences() {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sentences WHERE is_active = 1", null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    /** 所有句子查询共用：一条 SQL 同时带出收藏和永久熟练状态，避免每行再额外查询。 */
    private static final String SELECT_SENTENCE_COLUMNS =
            "SELECT s.sentence_key, s.serial_no, s.section, s.target_sentence, " +
            "s.native_meaning, s.coverage, s.memory_hint, s.pronunciation_guide, " +
            "s.pronunciation_audio_file, s.is_active, " +
            "(f.sentence_key IS NOT NULL) AS is_fav, " +
            "COALESCE(m.is_permanently_mastered, 0) AS is_perm " +
            "FROM sentences s " +
            "LEFT JOIN favorites f ON f.sentence_key = s.sentence_key " +
            "LEFT JOIN mastery_state m ON m.sentence_key = s.sentence_key ";

    private Sentence cursorToSentence(Cursor c) {
        Sentence s = new Sentence();
        s.sentenceKey = c.getString(c.getColumnIndexOrThrow("sentence_key"));
        s.serialNo = c.getInt(c.getColumnIndexOrThrow("serial_no"));
        s.section = c.getString(c.getColumnIndexOrThrow("section"));
        s.targetSentence = c.getString(c.getColumnIndexOrThrow("target_sentence"));
        s.nativeMeaning = c.getString(c.getColumnIndexOrThrow("native_meaning"));
        s.coverage = c.getString(c.getColumnIndexOrThrow("coverage"));
        s.memoryHint = c.getString(c.getColumnIndexOrThrow("memory_hint"));
        s.pronunciationGuide = c.getString(c.getColumnIndexOrThrow("pronunciation_guide"));
        s.pronunciationAudioFile = c.getString(c.getColumnIndexOrThrow("pronunciation_audio_file"));
        s.isActive = c.getInt(c.getColumnIndexOrThrow("is_active")) == 1;
        s.isFavorited = c.getInt(c.getColumnIndexOrThrow("is_fav")) == 1;
        s.isPermanentlyMastered = c.getInt(c.getColumnIndexOrThrow("is_perm")) == 1;
        return s;
    }

    private List<Sentence> querySentences(String sql, String[] args) {
        List<Sentence> result = new ArrayList<Sentence>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) result.add(cursorToSentence(c));
        } finally {
            c.close();
        }
        return result;
    }

    /** 普通池：active、未收藏、非永久熟练、今天没有点熟悉。 */
    public List<Sentence> getNormalLearningSentences(String today) {
        String sql = SELECT_SENTENCE_COLUMNS +
                "WHERE s.is_active = 1 " +
                "AND f.sentence_key IS NULL " +
                "AND COALESCE(m.is_permanently_mastered, 0) = 0 " +
                "AND COALESCE(m.last_mastery_date, '') <> ? " +
                "ORDER BY s.serial_no, s.sentence_key";
        return querySentences(sql, new String[]{today});
    }

    /** 收藏池：收藏中的句子即使 is_active=0 仍保留；排除今天已熟悉和永久熟练。 */
    public List<Sentence> getFavoriteLearningSentences(String today) {
        String sql = SELECT_SENTENCE_COLUMNS +
                "WHERE f.sentence_key IS NOT NULL " +
                "AND COALESCE(m.is_permanently_mastered, 0) = 0 " +
                "AND COALESCE(m.last_mastery_date, '') <> ? " +
                "ORDER BY f.created_at DESC";
        return querySentences(sql, new String[]{today});
    }

    public List<Sentence> getMasteryLibrary() {
        String sql = SELECT_SENTENCE_COLUMNS +
                "WHERE COALESCE(m.is_permanently_mastered, 0) = 1 " +
                "ORDER BY m.updated_at DESC";
        return querySentences(sql, new String[]{});
    }

    public List<Sentence> getFavoriteLibrary() {
        String sql = SELECT_SENTENCE_COLUMNS +
                "WHERE f.sentence_key IS NOT NULL " +
                "ORDER BY f.created_at DESC";
        return querySentences(sql, new String[]{});
    }

    public Sentence getSentenceByKey(String sentenceKey) {
        Cursor c = getReadableDatabase().rawQuery(
                SELECT_SENTENCE_COLUMNS + "WHERE s.sentence_key=? LIMIT 1",
                new String[]{sentenceKey});
        try {
            return c.moveToFirst() ? cursorToSentence(c) : null;
        } finally {
            c.close();
        }
    }

    // ---------------------------------------------------------------------
    // 收藏
    // ---------------------------------------------------------------------

    public void addFavorite(String sentenceKey) {
        ContentValues v = new ContentValues();
        v.put("sentence_key", sentenceKey);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "favorites", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void removeFavorite(String sentenceKey) {
        getWritableDatabase().delete(
                "favorites", "sentence_key=?", new String[]{sentenceKey});
    }

    public boolean isFavorite(String sentenceKey) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM favorites WHERE sentence_key=? LIMIT 1",
                new String[]{sentenceKey});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public boolean toggleFavorite(String sentenceKey) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        boolean nowFavorited;
        try {
            Cursor c = db.rawQuery(
                    "SELECT 1 FROM favorites WHERE sentence_key=? LIMIT 1",
                    new String[]{sentenceKey});
            boolean exists;
            try {
                exists = c.moveToFirst();
            } finally {
                c.close();
            }

            if (exists) {
                db.delete("favorites", "sentence_key=?", new String[]{sentenceKey});
                nowFavorited = false;
            } else {
                ContentValues v = new ContentValues();
                v.put("sentence_key", sentenceKey);
                v.put("created_at", System.currentTimeMillis());
                db.insertOrThrow("favorites", null, v);
                nowFavorited = true;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return nowFavorited;
    }

    // ---------------------------------------------------------------------
    // 熟悉：每日最多一次，连续三个本地日历日后永久熟练
    // ---------------------------------------------------------------------

    public MasteryResult markMastery(String sentenceKey, String localDate, long clickedAt) {
        MasteryResult result = new MasteryResult();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previousDate = null;
            int previousCount = 0;
            boolean alreadyPermanent = false;

            Cursor c = db.rawQuery(
                    "SELECT last_mastery_date, consecutive_days, is_permanently_mastered " +
                            "FROM mastery_state WHERE sentence_key=? LIMIT 1",
                    new String[]{sentenceKey});
            try {
                if (c.moveToFirst()) {
                    previousDate = c.getString(0);
                    previousCount = c.getInt(1);
                    alreadyPermanent = c.getInt(2) == 1;
                }
            } finally {
                c.close();
            }

            result.isPermanentlyMastered = alreadyPermanent;
            if (alreadyPermanent) {
                result.consecutiveDays = previousCount;
                result.alreadyClickedToday = false;
                result.becamePermanentNow = false;
                db.setTransactionSuccessful();
                return result;
            }

            if (localDate.equals(previousDate)) {
                // 同一天重复点按不累计，也不重复插入 mastery_events。
                result.consecutiveDays = previousCount;
                result.alreadyClickedToday = true;
                result.isPermanentlyMastered = false;
                result.becamePermanentNow = false;
                db.setTransactionSuccessful();
                return result;
            }

            int nextCount;
            if (previousDate == null) {
                nextCount = 1;
            } else {
                LocalDate today = LocalDate.parse(localDate);
                LocalDate yesterday = LocalDate.parse(previousDate);
                nextCount = today.equals(yesterday.plusDays(1)) ? previousCount + 1 : 1;
            }

            boolean permanent = nextCount >= 3;
            ContentValues state = new ContentValues();
            state.put("sentence_key", sentenceKey);
            state.put("last_mastery_date", localDate);
            state.put("consecutive_days", nextCount);
            state.put("is_permanently_mastered", permanent ? 1 : 0);
            state.put("updated_at", clickedAt);
            db.insertWithOnConflict(
                    "mastery_state", null, state, SQLiteDatabase.CONFLICT_REPLACE);

            ContentValues event = new ContentValues();
            event.put("sentence_key", sentenceKey);
            event.put("local_date", localDate);
            event.put("clicked_at", clickedAt);
            event.put("consecutive_days_after", nextCount);
            event.put("became_permanent", permanent ? 1 : 0);
            db.insertOrThrow("mastery_events", null, event);

            result.consecutiveDays = nextCount;
            result.isPermanentlyMastered = permanent;
            result.alreadyClickedToday = false;
            result.becamePermanentNow = permanent;
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // 首页数量
    // ---------------------------------------------------------------------

    public HomeCounts getHomeCounts(String today) {
        HomeCounts counts = new HomeCounts();
        SQLiteDatabase db = getReadableDatabase();

        Cursor c1 = db.rawQuery("SELECT COUNT(*) FROM sentences WHERE is_active=1", null);
        try { if (c1.moveToFirst()) counts.activeCount = c1.getInt(0); }
        finally { c1.close(); }

        Cursor c2 = db.rawQuery(
                "SELECT COUNT(*) FROM mastery_state WHERE is_permanently_mastered=1", null);
        try { if (c2.moveToFirst()) counts.masteryCount = c2.getInt(0); }
        finally { c2.close(); }

        Cursor c3 = db.rawQuery("SELECT COUNT(*) FROM favorites", null);
        try { if (c3.moveToFirst()) counts.favoriteCount = c3.getInt(0); }
        finally { c3.close(); }

        return counts;
    }

    // ---------------------------------------------------------------------
    // Study Session / Attempt / Event
    // ---------------------------------------------------------------------

    public long createStudySession(long startedAt) {
        ContentValues v = new ContentValues();
        v.put("started_at", startedAt);
        v.put("end_reason", "");
        return getWritableDatabase().insertOrThrow("study_sessions", null, v);
    }

    public void finishStudySession(long id, long endedAt, String reason) {
        ContentValues v = new ContentValues();
        v.put("ended_at", endedAt);
        v.put("end_reason", safe(reason));
        getWritableDatabase().update(
                "study_sessions", v, "id=?", new String[]{String.valueOf(id)});
    }

    public long createStudyAttempt(long sessionId, String sentenceKey,
                                   String direction, long startedAt) {
        ContentValues v = new ContentValues();
        v.put("session_id", sessionId);
        v.put("sentence_key", sentenceKey);
        v.put("prompt_direction", direction);
        v.put("started_at", startedAt);
        v.put("duration_ms", 0);
        v.put("end_reason", "");
        return getWritableDatabase().insertOrThrow("study_attempts", null, v);
    }

    public void finishStudyAttempt(long attemptId, long endedAt,
                                   long durationMs, String reason) {
        ContentValues v = new ContentValues();
        v.put("ended_at", endedAt);
        v.put("duration_ms", Math.max(0, durationMs));
        v.put("end_reason", safe(reason));
        getWritableDatabase().update(
                "study_attempts", v, "id=?", new String[]{String.valueOf(attemptId)});
    }

    public void insertStudyEvent(Long attemptId, Long sessionId,
                                 String sentenceKey, String eventType,
                                 long occurredAt, Long elapsedMs) {
        ContentValues v = new ContentValues();
        if (attemptId != null && attemptId >= 0) v.put("attempt_id", attemptId);
        if (sessionId != null && sessionId >= 0) v.put("session_id", sessionId);
        v.put("sentence_key", sentenceKey);
        v.put("event_type", eventType);
        v.put("occurred_at", occurredAt);
        if (elapsedMs != null && elapsedMs >= 0) v.put("elapsed_ms", elapsedMs);
        SQLiteDatabase db = getWritableDatabase();
        db.insert("study_events", null, v);

        // 同时把结果记到卡片记录自己的字段上，查询“这次有没有点提示”不必再扫事件表。
        String flagColumn = flagColumnForEvent(eventType);
        if (flagColumn != null && attemptId != null && attemptId >= 0) {
            db.execSQL("UPDATE study_attempts SET " + flagColumn + " = 1 WHERE id = ?",
                    new Object[]{attemptId});
        }
    }

    /** 事件类型 -> 卡片记录上的标记列。列名只来自这里的固定白名单。 */
    private static String flagColumnForEvent(String eventType) {
        if ("ANSWER_REVEALED".equals(eventType)) return "answer_revealed";
        if ("MEMORY_HINT_REVEALED".equals(eventType)) return "memory_hint_revealed";
        if ("PRONUNCIATION_GUIDE_REVEALED".equals(eventType)) return "pronunciation_guide_revealed";
        if ("FAVORITE_ADDED".equals(eventType) || "FAVORITE_REMOVED".equals(eventType)) return "favorite_toggled";
        if ("MASTERY_CLICKED".equals(eventType)) return "mastery_clicked";
        return null;
    }

    // ---------------------------------------------------------------------
    // 卡片记录耗时：一张卡片可能跨多段前台时间（切到后台再回来），所以累加而不是覆盖
    // ---------------------------------------------------------------------

    /** 累加当前卡片的一段前台时长，不结束卡片记录。 */
    public void addStudyAttemptDuration(long attemptId, long additionalDurationMs) {
        if (additionalDurationMs <= 0L) return;
        getWritableDatabase().execSQL(
                "UPDATE study_attempts SET duration_ms = duration_ms + ? " +
                        "WHERE id = ? AND ended_at IS NULL",
                new Object[]{additionalDurationMs, attemptId});
    }

    /** 累加最后一段前台时长，并结束这条卡片记录。 */
    public void finishStudyAttemptWithAdditionalDuration(
            long attemptId, long endedAt, long additionalDurationMs, String reason) {
        getWritableDatabase().execSQL(
                "UPDATE study_attempts SET duration_ms = duration_ms + ?, " +
                        "ended_at = ?, end_reason = ? WHERE id = ? AND ended_at IS NULL",
                new Object[]{Math.max(0L, additionalDurationMs), endedAt, safe(reason), attemptId});
    }

    // ---------------------------------------------------------------------
    // 录音
    // ---------------------------------------------------------------------

    /** 这张卡片记录下一段录音的序号（从 1 开始）。 */
    public int nextVoiceSequence(long attemptId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM voice_recordings WHERE attempt_id = ?",
                new String[]{String.valueOf(attemptId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 1;
        } finally {
            c.close();
        }
    }

    /** 保存一段已成功写完的 WAV 的登记信息，并同步更新卡片记录上的 recording_count。 */
    public long insertVoiceRecording(
            long sessionId, long attemptId, int sequenceNo, String sentenceKey,
            String fileName, String relativePath, long startedAt, long endedAt,
            long durationMs, int sampleRate, int channels, int bitsPerSample,
            long fileSizeBytes, String stopReason) {
        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("attempt_id", attemptId);
        values.put("sequence_no", sequenceNo);
        values.put("sentence_key", safe(sentenceKey));
        values.put("file_name", safe(fileName));
        values.put("relative_path", safe(relativePath));
        values.put("started_at", startedAt);
        values.put("ended_at", endedAt);
        values.put("duration_ms", Math.max(0L, durationMs));
        values.put("sample_rate", sampleRate);
        values.put("channels", channels);
        values.put("bits_per_sample", bitsPerSample);
        values.put("file_size_bytes", Math.max(0L, fileSizeBytes));
        values.put("stop_reason", safe(stopReason));

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = db.insertOrThrow("voice_recordings", null, values);
            db.execSQL("UPDATE study_attempts SET recording_count = recording_count + 1 WHERE id = ?",
                    new Object[]{attemptId});
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public List<VoiceRecording> getVoiceRecordingsForAttempt(long attemptId) {
        List<VoiceRecording> result = new ArrayList<VoiceRecording>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, session_id, attempt_id, sequence_no, sentence_key, file_name, " +
                        "relative_path, started_at, ended_at, duration_ms, sample_rate, channels, " +
                        "bits_per_sample, file_size_bytes, stop_reason " +
                        "FROM voice_recordings WHERE attempt_id = ? ORDER BY sequence_no ASC",
                new String[]{String.valueOf(attemptId)});
        try {
            while (c.moveToNext()) {
                VoiceRecording r = new VoiceRecording();
                r.id = c.getLong(0);
                r.sessionId = c.getLong(1);
                r.attemptId = c.getLong(2);
                r.sequenceNo = c.getInt(3);
                r.sentenceKey = c.getString(4);
                r.fileName = c.getString(5);
                r.relativePath = c.getString(6);
                r.startedAt = c.getLong(7);
                r.endedAt = c.getLong(8);
                r.durationMs = c.getLong(9);
                r.sampleRate = c.getInt(10);
                r.channels = c.getInt(11);
                r.bitsPerSample = c.getInt(12);
                r.fileSizeBytes = c.getLong(13);
                r.stopReason = c.getString(14);
                result.add(r);
            }
        } finally {
            c.close();
        }
        return result;
    }

    /** 关闭 APP/异常退出后，可将未结束的记录标记为 interrupted。 */
    public void closeOpenStudyRecords(long endedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues attempt = new ContentValues();
        attempt.put("ended_at", endedAt);
        attempt.put("end_reason", "interrupted");
        db.update("study_attempts", attempt, "ended_at IS NULL", null);

        ContentValues session = new ContentValues();
        session.put("ended_at", endedAt);
        session.put("end_reason", "interrupted");
        db.update("study_sessions", session, "ended_at IS NULL", null);
    }
}
