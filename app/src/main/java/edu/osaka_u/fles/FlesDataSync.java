package edu.osaka_u.fles;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * SAF + XLSX 题库同步。
 * 注意：checkAndSync() 是同步函数，调用者必须从后台线程执行。
 */
public class FlesDataSync {

    private static final String PREFS_NAME = "fles_sync_preferences";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LAST_HASH = "last_successful_xlsx_sha256";
    private static final String KEY_LAST_SYNC_AT = "last_sync_at";
    private static final String FILE_NAME = "sentences.xlsx";
    private static final int MAX_EXCEL_BYTES = 50 * 1024 * 1024; // 50 MB 防止意外读取超大文件

    private final Context context;
    private final FlesDatabase database;
    private final SharedPreferences preferences;

    private ContentObserver contentObserver;
    private Uri observedTreeUri;

    public FlesDataSync(Context context, FlesDatabase database) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 返回结果
    // ------------------------------------------------------------------

    public static class SyncResult {
        public boolean success;
        public boolean changed;
        public boolean languageMismatch;
        public int insertedCount;
        public String message = "";
        public long syncedAt;
    }

    // ------------------------------------------------------------------
    // SAF 授权目录保存
    // ------------------------------------------------------------------

    public void saveTreeUri(Uri treeUri) {
        if (treeUri == null) return;
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply();
    }

    public Uri getTreeUri() {
        String value = preferences.getString(KEY_TREE_URI, null);
        if (value == null || value.length() == 0) return null;
        try {
            return Uri.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    public String getLastSuccessfulHash() {
        return preferences.getString(KEY_LAST_HASH, "");
    }

    public long getLastSyncAt() {
        return preferences.getLong(KEY_LAST_SYNC_AT, 0L);
    }

    public void clearSavedFolder() {
        preferences.edit()
                .remove(KEY_TREE_URI)
                .remove(KEY_LAST_HASH)
                .remove(KEY_LAST_SYNC_AT)
                .apply();
    }

    // ------------------------------------------------------------------
    // 查找文件并读取字节
    // ------------------------------------------------------------------

    /** 在所选 SAF 目录的直接子项目中查找 sentences.xlsx。 */
    public Uri findXlsxDocument(Uri treeUri) throws IOException {
        if (treeUri == null) throw new IOException("尚未选择题库文件夹");

        String treeDocumentId;
        try {
            treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            throw new IOException("无法识别所选文件夹 URI", e);
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, treeDocumentId);

        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };

        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, projection, null, null, null);
            if (cursor == null) throw new IOException("文件提供者未返回目录内容");

            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (idColumn < 0 || nameColumn < 0) {
                throw new IOException("文件提供者缺少必要的文件信息列");
            }

            while (cursor.moveToNext()) {
                String documentId = cursor.getString(idColumn);
                String displayName = cursor.getString(nameColumn);
                if (FILE_NAME.equalsIgnoreCase(displayName)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                }
            }
        } catch (SecurityException e) {
            throw new IOException("FLES 已无法访问该文件夹，请重新选择文件夹", e);
        } finally {
            if (cursor != null) cursor.close();
        }

        throw new IOException("所选文件夹中没有找到 " + FILE_NAME);
    }

    public byte[] readDocument(Uri documentUri) throws IOException {
        if (documentUri == null) throw new IOException("Excel 文件 URI 为空");
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            input = context.getContentResolver().openInputStream(documentUri);
            if (input == null) throw new IOException("无法打开 Excel 文件");

            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_EXCEL_BYTES) {
                    throw new IOException("Excel 文件超过 50 MB，已停止读取");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (SecurityException e) {
            throw new IOException("没有读取该文件的权限，请重新选择题库文件夹", e);
        } finally {
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            try { output.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // 文件哈希：只用来判断 sentences.xlsx 是否变化，不是句子 ID
    // ------------------------------------------------------------------

    public String calculateFileSha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前系统不支持 SHA-256", e);
        }
    }

    // ------------------------------------------------------------------
    // 一次完整同步
    // ------------------------------------------------------------------

    /**
     * 从授权目录读取 sentences.xlsx 并同步。
     * 本方法执行文件 I/O、XLSX 解压解析和数据库事务，必须由后台线程调用。
     * 语言设置保存在 APP 内，不要求 Excel 自带语言元数据。
     */
    public SyncResult checkAndSync() {
        SyncResult result = new SyncResult();
        Uri treeUri = getTreeUri();
        if (treeUri == null) {
            result.success = false;
            result.message = "尚未选择题库文件夹";
            return result;
        }

        try {
            Uri excelUri = findXlsxDocument(treeUri);
            byte[] bytes = readDocument(excelUri);
            String newHash = calculateFileSha256(bytes);
            String oldHash = getLastSuccessfulHash();

            // 若哈希相同且数据库中仍有活动题目，就不必重复解析 XLSX。
            if (newHash.equals(oldHash) && database.countActiveSentences() > 0) {
                result.success = true;
                result.changed = false;
                result.message = "Excel 题库没有变化";
                result.syncedAt = getLastSyncAt();
                return result;
            }

            // 先完整解析并校验 Excel；解析失败时不会触碰数据库。
            List<FlesDatabase.Sentence> imported = XlsxReader.read(bytes);
            if (imported == null || imported.isEmpty()) {
                throw new IOException("Excel 中没有有效例句；为保护旧题库，本次导入已取消");
            }

            int inserted = database.syncImportedSentences(imported);

            // 只有数据库事务成功后才记录新哈希。失败时下次前台检查可重试。
            long now = System.currentTimeMillis();
            preferences.edit()
                    .putString(KEY_LAST_HASH, newHash)
                    .putLong(KEY_LAST_SYNC_AT, now)
                    .apply();

            result.success = true;
            result.changed = true;
            result.insertedCount = inserted;
            result.syncedAt = now;
            result.message = "Excel 同步完成：新增 " + inserted + " 条，识别到 " +
                    imported.size() + " 条有效例句";
            return result;

        } catch (Exception e) {
            result.success = false;
            result.changed = false;
            result.message = e.getMessage() == null ? "Excel 同步失败" : e.getMessage();
            return result;
        }
    }

    // ------------------------------------------------------------------
    // ContentObserver：尽力监听文件夹变化
    // ------------------------------------------------------------------

    /**
     * 一些 DocumentsProvider 会发送文件变化通知，一些 USB/MTP 文件提供者不会。
     * 因此此监听只作为快速刷新手段；MainActivity.onResume() 仍必须执行 checkAndSync。
     */
    public void registerContentObserver(Uri treeUri, Handler handler, final Runnable onChanged) {
        unregisterContentObserver();
        if (treeUri == null || onChanged == null) return;

        observedTreeUri = treeUri;
        contentObserver = new ContentObserver(handler == null ? new Handler(Looper.getMainLooper()) : handler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                onChanged.run();
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                onChanged.run();
            }
        };

        try {
            // 文件提供者通常向“子项目 URI”发变化通知，而不是向树 URI 本身发，
            // 所以监听 children URI，并包含后代。
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            context.getContentResolver().registerContentObserver(
                    childrenUri, true, contentObserver);
        } catch (Exception ignored) {
            // 不影响 onResume 时的强制文件检查。
        }
    }

    public void unregisterContentObserver() {
        if (contentObserver != null) {
            try {
                context.getContentResolver().unregisterContentObserver(contentObserver);
            } catch (Exception ignored) { }
            contentObserver = null;
            observedTreeUri = null;
        }
    }
}
