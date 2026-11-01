package edu.osaka_u.fles;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FLES 主界面。
 * 一个 Activity 内通过三个布局容器切换：首页、学习卡片、熟练/收藏库。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PICK_FOLDER = 501;
    private static final String USER_PREFS = "fles_user_preferences";
    private static final String KEY_NATIVE_LANGUAGE = "native_language_tag";
    private static final String KEY_TARGET_LANGUAGE = "target_language_tag";
    private static final String KEY_LEARNING_MODE = "learning_mode";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private FlesDatabase database;
    private FlesDataSync dataSync;
    private SharedPreferences userPreferences;

    // 页面容器
    private View pageHome;
    private View pageStudy;
    private View pageLibrary;

    // 首页控件
    private TextView txtHomeTitle;
    private TextView txtHomeSubtitle;
    private TextView txtLanguagePair;
    private TextView txtLearningModeLabel;
    private TextView txtMySentenceLibraryLabel;
    private TextView txtQuestionFileLabel;
    private TextView txtActiveCount;
    private TextView txtSyncStatus;
    private Button btnStartStudy;
    private Button btnMasteryLibrary;
    private Button btnFavoriteLibrary;
    private Button btnChooseFolder;
    private Button btnLanguageSettings;
    private RadioGroup rgLearningMode;

    // 学习页控件
    private Button btnExitStudy;
    private TextView txtStudyPageTitle;
    private ScrollView scrollStudyContent;
    private TextView txtStudySection;
    private TextView txtPromptDirection;
    private TextView txtPromptText;
    private Button btnShowAnswer;
    private TextView txtAnswer;
    private Button btnMemoryHint;
    private Button btnPronunciationGuide;
    private TextView txtMemoryHint;
    private TextView txtPronunciationGuide;
    private TextView txtNoLearningSentences;
    private Button btnMarkMastered;
    private Button btnToggleFavorite;
    private Button btnNextCard;

    // 库页面控件
    private Button btnLibraryBack;
    private Button btnLibraryRefresh;
    private TextView txtLibraryTitle;
    private TextView txtLibraryEmpty;
    private ListView listSentences;

    // 录音（按住录音，松开停止）
    private Button btnVoiceRecord;
    private static final int REQUEST_RECORD_AUDIO = 702;
    /** 短于这个时长的录音当作误触，不保存也不登记。 */
    private static final long MIN_RECORDING_MS = 300L;
    private volatile boolean voiceRecordButtonHeld = false;
    private GradientDrawable voiceButtonBackground;
    private ValueAnimator voiceRadiusAnimator;
    private ObjectAnimator voicePulseAnimator;

    // 系统深色 / 浅色模式切换
    private int lastNightMode;
    private boolean pendingThemeRecreate;

    // 用户设置
    private String nativeLanguageTag;
    private String targetLanguageTag;
    // volatile：UI 线程写入，ioExecutor 线程读取。
    private volatile String learningMode = "MIXED"; // MIXED / FOREIGN_TO_NATIVE / NATIVE_TO_FOREIGN
    private String pendingNativeTag; // 语言对话框进行中暂存，选完目标语言后才一起保存

    // UI 当前卡片状态，仅主线程访问
    private FlesDatabase.Sentence currentSentence;
    private String currentPromptDirection = "FOREIGN_TO_NATIVE";
    private boolean answerVisible;
    private boolean memoryHintVisible;
    private boolean pronunciationGuideVisible;
    private boolean currentSentenceFavorited;
    private boolean transitioning;
    private boolean segmentNeedsResume;
    private boolean syncInProgress;
    private boolean syncRerunRequested;
    private final Runnable observerSyncRunnable = new Runnable() {
        @Override
        public void run() {
            checkSyncAsync();
        }
    };
    private boolean activityResumed;
    private long segmentStartElapsedRealtime = -1L;

    // 以下 worker 字段只允许 ioExecutor 中的任务访问
    private long workerSessionId = -1L;
    private long workerAttemptId = -1L;
    private String workerCurrentSentenceKey;
    // 当前正在进行的录音，只允许 ioExecutor 线程访问。
    private ActiveVoiceRecording activeVoiceRecording;
    private String workerLastSentenceKey;
    private final ArrayList<String> workerNormalBag = new ArrayList<String>();
    private final ArrayList<String> workerFavoriteBag = new ArrayList<String>();
    private List<FlesDatabase.Sentence> workerNormalPool = new ArrayList<FlesDatabase.Sentence>();
    private List<FlesDatabase.Sentence> workerFavoritePool = new ArrayList<FlesDatabase.Sentence>();
    private final Map<String, FlesDatabase.Sentence> workerSentenceMap =
            new HashMap<String, FlesDatabase.Sentence>();

    // 当前库页面数据，仅主线程访问
    private String currentLibraryType = "mastery";
    private List<FlesDatabase.Sentence> libraryItems = new ArrayList<FlesDatabase.Sentence>();
    private ArrayAdapter<String> libraryAdapter;

    // ------------------------------------------------------------------
    // Activity 生命周期
    // ------------------------------------------------------------------

    /** 一段正在录的音：记下它属于哪个学习记录、哪个卡片记录。 */
    private static class ActiveVoiceRecording {
        VoiceRecorder recorder;
        long sessionId;
        long attemptId;
        int sequenceNo;
        String sentenceKey;
        String fileName;
        String relativePath;
        long startedAt;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        lastNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

        database = new FlesDatabase(this);
        dataSync = new FlesDataSync(this, database);
        userPreferences = getSharedPreferences(USER_PREFS, MODE_PRIVATE);

        bindViews();
        setupClickListeners();
        loadUserPreferences();
        applyLocalization();
        ioExecutor.execute(() -> {
            database.closeOpenStudyRecords(System.currentTimeMillis());
            // 上次进程被杀时遗留的未完成录音（.wav.part）不是可用录音，直接清理。
            VoiceRecorder.cleanupPartialFiles(new File(getFilesDir(), "voice_recordings"));
        });
        refreshHomeCounts();

        if (nativeLanguageTag == null || targetLanguageTag == null) {
            showFirstLaunchLanguageDialog();
        }
    }

    /**
     * 系统在深色 / 浅色之间切换时：配色要重新加载，必须重建 Activity。
     * 但学习状态保存在内存里，学习中直接重建会被踢回首页，所以学习中先记下来，
     * 等用户退出学习回到首页时再重建。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int nightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode == lastNightMode) return; // 旋转屏幕等其他变化，不需要处理
        lastNightMode = nightMode;
        if (pageStudy != null && pageStudy.getVisibility() == View.VISIBLE) {
            pendingThemeRecreate = true;
        } else {
            recreate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        registerFolderObserver();
        checkSyncAsync();

        if (segmentNeedsResume && currentSentence != null && pageStudy.getVisibility() == View.VISIBLE) {
            segmentNeedsResume = false;
            resumeCurrentCardSegment();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (pageStudy != null && pageStudy.getVisibility() == View.VISIBLE && currentSentence != null && !transitioning) {
            pauseCurrentCardSegment("app_background");
        }
        mainHandler.removeCallbacks(observerSyncRunnable);
        if (dataSync != null) dataSync.unregisterContentObserver();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (dataSync != null) dataSync.unregisterContentObserver();
        // 将 close 排在之前所有数据库任务之后。
        if (database != null) {
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    database.close();
                }
            });
        }
        ioExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pageStudy != null && pageStudy.getVisibility() == View.VISIBLE) {
            exitStudy();
        } else if (pageLibrary != null && pageLibrary.getVisibility() == View.VISIBLE) {
            showHomePage();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------
    // View 绑定和监听
    // ------------------------------------------------------------------

    private void bindViews() {
        pageHome = findViewById(R.id.pageHome);
        pageStudy = findViewById(R.id.pageStudy);
        pageLibrary = findViewById(R.id.pageLibrary);

        txtHomeTitle = findViewById(R.id.txtHomeTitle);
        txtHomeSubtitle = findViewById(R.id.txtHomeSubtitle);
        txtLanguagePair = findViewById(R.id.txtLanguagePair);
        txtLearningModeLabel = findViewById(R.id.txtLearningModeLabel);
        txtMySentenceLibraryLabel = findViewById(R.id.txtMySentenceLibraryLabel);
        txtQuestionFileLabel = findViewById(R.id.txtQuestionFileLabel);
        txtActiveCount = findViewById(R.id.txtActiveCount);
        txtSyncStatus = findViewById(R.id.txtSyncStatus);
        btnStartStudy = findViewById(R.id.btnStartStudy);
        btnMasteryLibrary = findViewById(R.id.btnMasteryLibrary);
        btnFavoriteLibrary = findViewById(R.id.btnFavoriteLibrary);
        btnChooseFolder = findViewById(R.id.btnChooseFolder);
        btnLanguageSettings = findViewById(R.id.btnLanguageSettings);
        rgLearningMode = findViewById(R.id.rgLearningMode);

        btnExitStudy = findViewById(R.id.btnExitStudy);
        txtStudyPageTitle = findViewById(R.id.txtStudyPageTitle);
        scrollStudyContent = findViewById(R.id.scrollStudyContent);
        txtStudySection = findViewById(R.id.txtStudySection);
        txtPromptDirection = findViewById(R.id.txtPromptDirection);
        txtPromptText = findViewById(R.id.txtPromptText);
        btnShowAnswer = findViewById(R.id.btnShowAnswer);
        txtAnswer = findViewById(R.id.txtAnswer);
        btnMemoryHint = findViewById(R.id.btnMemoryHint);
        btnPronunciationGuide = findViewById(R.id.btnPronunciationGuide);
        txtMemoryHint = findViewById(R.id.txtMemoryHint);
        txtPronunciationGuide = findViewById(R.id.txtPronunciationGuide);
        txtNoLearningSentences = findViewById(R.id.txtNoLearningSentences);
        btnMarkMastered = findViewById(R.id.btnMarkMastered);
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite);
        btnNextCard = findViewById(R.id.btnNextCard);
        btnVoiceRecord = findViewById(R.id.btnVoiceRecord);

        btnLibraryBack = findViewById(R.id.btnLibraryBack);
        btnLibraryRefresh = findViewById(R.id.btnLibraryRefresh);
        txtLibraryTitle = findViewById(R.id.txtLibraryTitle);
        txtLibraryEmpty = findViewById(R.id.txtLibraryEmpty);
        listSentences = findViewById(R.id.listSentences);

        libraryAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listSentences.setAdapter(libraryAdapter);

        setupVoiceButtonVisual();
    }

    private void setupClickListeners() {
        btnStartStudy.setOnClickListener(v -> showStudyPage());
        btnMasteryLibrary.setOnClickListener(v -> showLibraryPage("mastery"));
        btnFavoriteLibrary.setOnClickListener(v -> showLibraryPage("favorites"));
        btnChooseFolder.setOnClickListener(v -> launchFolderPicker());
        btnLanguageSettings.setOnClickListener(v -> showLanguageSettingsDialog());

        rgLearningMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioForeignToNative) {
                learningMode = "FOREIGN_TO_NATIVE";
            } else if (checkedId == R.id.radioNativeToForeign) {
                learningMode = "NATIVE_TO_FOREIGN";
            } else {
                learningMode = "MIXED";
            }
            userPreferences.edit().putString(KEY_LEARNING_MODE, learningMode).apply();
        });

        btnExitStudy.setOnClickListener(v -> exitStudy());
        btnShowAnswer.setOnClickListener(v -> revealAnswer());
        btnMemoryHint.setOnClickListener(v -> toggleMemoryHint());
        btnPronunciationGuide.setOnClickListener(v -> togglePronunciationGuide());
        btnMarkMastered.setOnClickListener(v -> handleMasteryClick());
        btnToggleFavorite.setOnClickListener(v -> handleFavoriteClick());
        btnNextCard.setOnClickListener(v -> handleNextClick());
        btnVoiceRecord.setOnTouchListener((view, event) -> handleVoiceRecordTouch(view, event));

        btnLibraryBack.setOnClickListener(v -> showHomePage());
        btnLibraryRefresh.setOnClickListener(v -> loadLibrary(currentLibraryType));
        listSentences.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < libraryItems.size()) {
                showSentenceDetailDialog(libraryItems.get(position));
            }
        });
    }

    private void loadUserPreferences() {
        nativeLanguageTag = userPreferences.getString(KEY_NATIVE_LANGUAGE, null);
        targetLanguageTag = userPreferences.getString(KEY_TARGET_LANGUAGE, null);
        learningMode = userPreferences.getString(KEY_LEARNING_MODE, "MIXED");

        if ("FOREIGN_TO_NATIVE".equals(learningMode)) {
            rgLearningMode.check(R.id.radioForeignToNative);
        } else if ("NATIVE_TO_FOREIGN".equals(learningMode)) {
            rgLearningMode.check(R.id.radioNativeToForeign);
        } else {
            rgLearningMode.check(R.id.radioMixed);
        }
    }

    // ------------------------------------------------------------------
    // 首次语言选择 / 简单三语 UI 文案
    // ------------------------------------------------------------------

    private void showFirstLaunchLanguageDialog() {
        chooseNativeLanguage(true);
    }

    private void showLanguageSettingsDialog() {
        chooseNativeLanguage(false);
    }

    private void chooseNativeLanguage(final boolean firstLaunch) {
        final String[] labels = new String[]{"简体中文", "日本語", "English"};
        final String[] tags = new String[]{"zh-CN", "ja-JP", "en-US"};
        int selected = indexOfLanguage(nativeLanguageTag);

        new AlertDialog.Builder(this)
                .setTitle("选择母语 / 母語を選択 / Choose native language")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    // 只暂存，选完目标语言后才真正保存，避免中途取消造成设置不一致。
                    pendingNativeTag = tags[which];
                    dialog.dismiss();
                    chooseTargetLanguage(firstLaunch);
                })
                .setCancelable(!firstLaunch)
                .show();
    }

    private void chooseTargetLanguage(final boolean firstLaunch) {
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<String> tags = new ArrayList<String>();
        addLanguageOption(labels, tags, "简体中文", "zh-CN");
        addLanguageOption(labels, tags, "日本語", "ja-JP");
        addLanguageOption(labels, tags, "English", "en-US");

        int selected = tags.indexOf(targetLanguageTag);
        if (selected < 0) selected = 0;

        new AlertDialog.Builder(this)
                .setTitle("选择目标学习语言 / 学習言語を選択 / Choose target language")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected,
                        (dialog, which) -> {
                            String newTarget = tags.get(which);
                            dialog.dismiss();
                            boolean changed = !pendingNativeTag.equals(nativeLanguageTag)
                                    || !newTarget.equals(targetLanguageTag);
                            nativeLanguageTag = pendingNativeTag;
                            targetLanguageTag = newTarget;
                            saveLanguagePreferences();
                            applyLocalization();
                            refreshHomeCounts();
                            if (firstLaunch) {
                                showFolderPromptDialog();
                            } else if (changed) {
                                showLanguageChangedNote();
                            }
                            if (dataSync.getTreeUri() != null) checkSyncAsync();
                        })
                .setCancelable(!firstLaunch)
                .show();
    }

    /** 首次启动：语言选完后，引导用户选择题库文件夹。 */
    private void showFolderPromptDialog() {
        new AlertDialog.Builder(this)
                .setMessage(getLocalizedText("pickFolderPrompt"))
                .setPositiveButton(getLocalizedText("chooseFolder"), (d, w) -> launchFolderPicker())
                .setNegativeButton(getLocalizedText("later"), null)
                .show();
    }

    /** 修改语言后提醒：Excel 题库内容要和新语言设置一致（Excel 本身不带语言标签，APP 无法自动检查）。 */
    private void showLanguageChangedNote() {
        new AlertDialog.Builder(this)
                .setMessage(getLocalizedText("langChangedNote"))
                .setPositiveButton("OK", null)
                .show();
    }

    private void addLanguageOption(List<String> labels, List<String> tags,
                                   String label, String tag) {
        if (!tag.equals(pendingNativeTag)) {
            labels.add(label);
            tags.add(tag);
        }
    }

    private int indexOfLanguage(String tag) {
        if ("ja-JP".equals(tag)) return 1;
        if ("en-US".equals(tag)) return 2;
        return 0;
    }

    private void saveLanguagePreferences() {
        userPreferences.edit()
                .putString(KEY_NATIVE_LANGUAGE, nativeLanguageTag)
                .putString(KEY_TARGET_LANGUAGE, targetLanguageTag)
                .apply();
    }

    private String languageName(String tag) {
        if ("zh-CN".equals(tag)) return "中文";
        if ("ja-JP".equals(tag)) return "日本語";
        if ("en-US".equals(tag)) return "English";
        return tag == null ? "未设置" : tag;
    }

    private void updateLanguagePairText() {
        txtLanguagePair.setText(getLocalizedText("native") + "：" + languageName(nativeLanguageTag) +
                "     " + getLocalizedText("target") + "：" + languageName(targetLanguageTag));
    }

    private String getLocalizedText(String key) {
        boolean ja = "ja-JP".equals(nativeLanguageTag);
        boolean en = "en-US".equals(nativeLanguageTag);
        if (ja) {
            switch (key) {
                case "start": return "学習開始";
                case "native": return "母語";
                case "target": return "学習言語";
                case "studyMode": return "学習方向";
                case "myLibraries": return "例文ライブラリ";
                case "currentBank": return "現在の問題集";
                case "questionFile": return "問題ファイル";
                case "mixed": return "双方向ミックス（ランダム）";
                case "foreignToNative": return "外国語を見て母語を思い出す";
                case "nativeToForeign": return "母語を見て外国語を思い出す";
                case "mastery": return "習得済み";
                case "favorites": return "お気に入り";
                case "topic": return "分野";
                case "showAnswer": return "答えを表示";
                case "hideAnswer": return "答えを閉じる";
                case "hint": return "記憶ヒント";
                case "reading": return "全文の読み方";
                case "mastered": return "習得した";
                case "favorite": return "お気に入り";
                case "favorited": return "登録済み";
                case "next": return "次へ";
                case "exit": return "学習を終了";
                case "back": return "戻る";
                case "refresh": return "更新";
                case "chooseFolder": return "問題ファイルのフォルダを選択";
                case "languageSettings": return "言語設定";
                case "learning": return "学習中";
                case "empty": return "学習できる例文がありません。ホームに戻って問題集を確認してください。";
                case "pickFolderPrompt": return "問題ファイル sentences.xlsx を入れるフォルダを選択してください。";
                case "later": return "あとで";
                case "langChangedNote": return "言語設定を変更しました。sentences.xlsx の例文と意味も新しい設定に合わせてください(言語は自動チェックされません)。";
                case "noFolder": return "問題フォルダが未選択です";
                case "folderChosen": return "フォルダを選択しました。sentences.xlsx を確認中…";
                case "lastSync": return "最終同期";
                case "toastMastered3": return "3日連続で習得を確認し、習得済みに追加しました。";
                case "toastMasteredToday": return "今日の習得確認を記録しました。";
                case "toastMasteredAlready": return "この例文は今日すでに確認済みです。";
                case "voiceHold": return "長押しして録音";
                case "voiceRecording": return "録音中・離すと停止";
                case "toastMicPermission": return "録音するにはマイクの使用を許可してください。許可後、もう一度ボタンを長押ししてください。";
                case "toastMicGranted": return "マイクが許可されました。もう一度ボタンを長押ししてください。";
                case "toastMicDenied": return "マイクが許可されていないため、録音できません。";
                case "toastRecSaved": return "録音を保存しました";
                case "toastRecTooShort": return "録音が短すぎるため保存しませんでした";
                case "toastRecFailed": return "録音に失敗しました";
                case "toastNoCard": return "録音できるカードがありません";
                default: return key;
            }
        }
        if (en) {
            switch (key) {
                case "start": return "Start Learning";
                case "native": return "Native language";
                case "target": return "Target language";
                case "studyMode": return "Study direction";
                case "myLibraries": return "My Sentence Libraries";
                case "currentBank": return "Current question bank";
                case "questionFile": return "Question File";
                case "mixed": return "Mixed directions (random)";
                case "foreignToNative": return "Target language → recall native language";
                case "nativeToForeign": return "Native language → recall target language";
                case "mastery": return "Mastered";
                case "favorites": return "Favorites";
                case "topic": return "Topic";
                case "showAnswer": return "Show Answer";
                case "hideAnswer": return "Hide Answer";
                case "hint": return "Memory Hint";
                case "reading": return "Full Sentence Reading";
                case "mastered": return "Mastered";
                case "favorite": return "Favorite";
                case "favorited": return "Favorited";
                case "next": return "Next";
                case "exit": return "Exit Study";
                case "back": return "Back";
                case "refresh": return "Refresh";
                case "chooseFolder": return "Choose Question Folder";
                case "languageSettings": return "Language Settings";
                case "learning": return "Learning";
                case "empty": return "There are no sentences to study. Return home and check your question bank.";
                case "pickFolderPrompt": return "Please choose the folder where you keep your sentences.xlsx file.";
                case "later": return "Later";
                case "langChangedNote": return "Language settings changed. Make sure the sentences and meanings in sentences.xlsx match the new settings (the app cannot check the language automatically).";
                case "noFolder": return "No question folder selected yet";
                case "folderChosen": return "Folder selected. Checking sentences.xlsx…";
                case "lastSync": return "Last sync";
                case "toastMastered3": return "Confirmed 3 days in a row. Added to Mastered.";
                case "toastMasteredToday": return "Today's mastery confirmation recorded.";
                case "toastMasteredAlready": return "You already confirmed this sentence today.";
                case "voiceHold": return "Hold to record";
                case "voiceRecording": return "Recording… release to stop";
                case "toastMicPermission": return "Allow microphone access, then hold the button again.";
                case "toastMicGranted": return "Microphone allowed. Hold the button again to record.";
                case "toastMicDenied": return "Microphone access was not granted, so recording is unavailable.";
                case "toastRecSaved": return "Recording saved";
                case "toastRecTooShort": return "Too short, not saved";
                case "toastRecFailed": return "Recording failed";
                case "toastNoCard": return "No card to record for";
                default: return key;
            }
        }
        switch (key) {
            case "start": return "开始学习";
            case "native": return "母语";
            case "target": return "学习语言";
            case "studyMode": return "学习方向";
            case "myLibraries": return "我的句子库";
            case "currentBank": return "当前题库";
            case "questionFile": return "题库文件";
            case "mixed": return "双向混合（随机切换）";
            case "foreignToNative": return "显示外语，回忆母语";
            case "nativeToForeign": return "显示母语，回忆外语";
            case "mastery": return "熟练库";
            case "favorites": return "收藏库";
            case "topic": return "板块";
            case "showAnswer": return "显示答案";
            case "hideAnswer": return "隐藏答案";
            case "hint": return "记忆提示";
            case "reading": return "全句注音";
            case "mastered": return "熟悉";
            case "favorite": return "收藏";
            case "favorited": return "已收藏";
            case "next": return "下一题";
            case "exit": return "退出学习";
            case "back": return "返回";
            case "refresh": return "刷新";
            case "chooseFolder": return "选择 / 更换题库文件夹";
            case "languageSettings": return "语言设置";
            case "learning": return "学习中";
            case "empty": return "目前没有可学习的句子。请返回首页检查题库，或明天继续。";
            case "pickFolderPrompt": return "请选择存放 sentences.xlsx 的题库文件夹。";
            case "later": return "稍后";
            case "langChangedNote": return "语言设置已修改。请确保 sentences.xlsx 中的例句和意思与新设置一致（APP 无法自动检查语言）。";
            case "noFolder": return "尚未选择题库文件夹";
            case "folderChosen": return "已选择文件夹，正在检查 sentences.xlsx…";
            case "lastSync": return "上次同步";
            case "toastMastered3": return "连续三天确认熟悉，已加入熟练库。";
            case "toastMasteredToday": return "已记录今天的熟悉确认。";
            case "toastMasteredAlready": return "今天已经确认过这句话。";
            case "voiceHold": return "按住录音，松开停止";
            case "voiceRecording": return "录音中…松开停止";
            case "toastMicPermission": return "请允许 FLES 使用麦克风，然后再次按住录音按钮。";
            case "toastMicGranted": return "麦克风权限已允许，请再次按住录音按钮开始录音。";
            case "toastMicDenied": return "未获得麦克风权限，无法录音。";
            case "toastRecSaved": return "录音已保存";
            case "toastRecTooShort": return "录音太短，未保存";
            case "toastRecFailed": return "录音失败";
            case "toastNoCard": return "当前没有可以录音的卡片";
            default: return key;
        }
    }

    private void applyLocalization() {
        if (txtHomeTitle == null) return;
        txtHomeTitle.setText("FLES");
        txtHomeSubtitle.setText("Foreign Language Example Sentence");
        btnStartStudy.setText(getLocalizedText("start"));
        btnMasteryLibrary.setText(getLocalizedText("mastery") + " (0)");
        btnFavoriteLibrary.setText(getLocalizedText("favorites") + " (0)");
        btnChooseFolder.setText(getLocalizedText("chooseFolder"));
        btnLanguageSettings.setText(getLocalizedText("languageSettings"));
        txtLearningModeLabel.setText(getLocalizedText("studyMode"));
        txtMySentenceLibraryLabel.setText(getLocalizedText("myLibraries"));
        txtQuestionFileLabel.setText(getLocalizedText("questionFile"));
        btnExitStudy.setText(getLocalizedText("exit"));
        txtStudyPageTitle.setText(getLocalizedText("learning"));
        btnShowAnswer.setText(getLocalizedText("showAnswer"));
        btnMemoryHint.setText(getLocalizedText("hint"));
        btnPronunciationGuide.setText(getLocalizedText("reading"));
        btnMarkMastered.setText(getLocalizedText("mastered"));
        btnToggleFavorite.setText(getLocalizedText("favorite"));
        btnNextCard.setText(getLocalizedText("next"));
        btnVoiceRecord.setText(getLocalizedText("voiceHold"));
        btnLibraryBack.setText(getLocalizedText("back"));
        btnLibraryRefresh.setText(getLocalizedText("refresh"));
        updateLanguagePairText();

        ((android.widget.RadioButton) findViewById(R.id.radioMixed)).setText(getLocalizedText("mixed"));
        ((android.widget.RadioButton) findViewById(R.id.radioForeignToNative)).setText(getLocalizedText("foreignToNative"));
        ((android.widget.RadioButton) findViewById(R.id.radioNativeToForeign)).setText(getLocalizedText("nativeToForeign"));
        txtNoLearningSentences.setText(getLocalizedText("empty"));
        if (dataSync != null && dataSync.getTreeUri() == null) {
            txtSyncStatus.setText(getLocalizedText("noFolder"));
        }
    }

    // ------------------------------------------------------------------
    // SAF：用户选择题库文件夹
    // ------------------------------------------------------------------

    private void launchFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            // 本 APP 只读取题库，明确只申请持久读权限。
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (SecurityException ignored) {
                // 如果 provider 不支持持久授权，当前会话可能仍能访问；下次失效时提示重新选择。
            }
            dataSync.saveTreeUri(treeUri);
            txtSyncStatus.setText(getLocalizedText("folderChosen"));
            registerFolderObserver();
            checkSyncAsync();
        }
    }

    private void registerFolderObserver() {
        if (dataSync == null) return;
        Uri uri = dataSync.getTreeUri();
        if (uri == null) return;
        // 复制文件时会连续触发多次通知：防抖 1.5 秒，等复制结束再检查。
        dataSync.registerContentObserver(uri, mainHandler, () -> {
            mainHandler.removeCallbacks(observerSyncRunnable);
            mainHandler.postDelayed(observerSyncRunnable, 1500L);
        });
    }

    private void checkSyncAsync() {
        if (dataSync == null || nativeLanguageTag == null || targetLanguageTag == null) return;
        final Uri tree = dataSync.getTreeUri();
        if (tree == null) return;
        if (syncInProgress) {
            // 同步进行中又收到变化通知：结束后再检查一次，避免漏掉复制完成后的最终文件。
            syncRerunRequested = true;
            return;
        }
        syncInProgress = true;

        ioExecutor.execute(() -> {
            final FlesDataSync.SyncResult result = dataSync.checkAndSync();
            if (result.changed) {
                // 刷新 worker 内候选池，当前正在展示的卡片不被强行替换。
                refreshWorkerPools();
            }
            runOnUiThread(() -> {
                syncInProgress = false;
                String statusText = result.message;
                long shownTime = result.syncedAt > 0L ? result.syncedAt : dataSync.getLastSyncAt();
                if (shownTime > 0L) {
                    statusText += "\n" + getLocalizedText("lastSync") + "：" +
                            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(new Date(shownTime));
                }
                txtSyncStatus.setText(statusText);
                if (result.success && result.changed) {
                    showToastMessage(result.message);
                }
                refreshHomeCounts();
                if (syncRerunRequested) {
                    syncRerunRequested = false;
                    checkSyncAsync();
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 页面切换：首页 / 学习页 / 库页面
    // ------------------------------------------------------------------

    private void showOnlyPage(View page) {
        pageHome.setVisibility(View.GONE);
        pageStudy.setVisibility(View.GONE);
        pageLibrary.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
    }

    private void showHomePage() {
        showOnlyPage(pageHome);
        currentSentence = null;
        currentSegmentElapsedReset();
        refreshHomeCounts();
        if (pendingThemeRecreate) { // 学习期间系统切换了深色模式：现在回到首页，刷新配色
            pendingThemeRecreate = false;
            recreate();
        }
    }

    private void currentSegmentElapsedReset() {
        segmentStartElapsedRealtime = -1L;
        answerVisible = false;
        memoryHintVisible = false;
        pronunciationGuideVisible = false;
        transitioning = false;
    }

    private void showStudyPage() {
        if (nativeLanguageTag == null || targetLanguageTag == null) {
            showFirstLaunchLanguageDialog();
            return;
        }
        showOnlyPage(pageStudy);
        currentSentence = null;
        currentSegmentElapsedReset();
        setStudyControlsEnabled(false);
        txtNoLearningSentences.setVisibility(View.GONE);
        scrollStudyContent.setVisibility(View.VISIBLE);
        txtPromptText.setText("…");

        ioExecutor.execute(() -> {
            closeWorkerSessionIfOpen("restarted");
            workerLastSentenceKey = null;
            workerNormalBag.clear();
            workerFavoriteBag.clear();
            workerSessionId = database.createStudySession(System.currentTimeMillis());
            refreshWorkerPools();
            showNextCardOnWorker("study_start", null, 0L, null, 0L);
        });
    }

    private void showLibraryPage(String type) {
        currentLibraryType = type;
        showOnlyPage(pageLibrary);
        txtLibraryTitle.setText("mastery".equals(type)
                ? getLocalizedText("mastery") : getLocalizedText("favorites"));
        loadLibrary(type);
    }

    private void showSentenceDetailDialog(FlesDatabase.Sentence sentence) {
        StringBuilder text = new StringBuilder();
        text.append(sentence.targetSentence).append("\n\n");
        text.append(sentence.nativeMeaning);
        if (sentence.section.length() > 0) text.append("\n\n").append(getLocalizedText("topic")).append("：").append(sentence.section);
        if (sentence.memoryHint.length() > 0) text.append("\n\n").append(getLocalizedText("hint")).append("：\n").append(sentence.memoryHint);
        if (sentence.pronunciationGuide.length() > 0) text.append("\n\n").append(getLocalizedText("reading")).append("：\n").append(sentence.pronunciationGuide);

        new AlertDialog.Builder(this)
                .setTitle(getLocalizedText("topic") + " / FLES")
                .setMessage(text.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void refreshHomeCounts() {
        ioExecutor.execute(() -> {
            FlesDatabase.HomeCounts counts = database.getHomeCounts(LocalDate.now().toString());
            runOnUiThread(() -> {
                btnMasteryLibrary.setText(getLocalizedText("mastery") + " (" + counts.masteryCount + ")");
                btnFavoriteLibrary.setText(getLocalizedText("favorites") + " (" + counts.favoriteCount + ")");
                String countText;
                if ("ja-JP".equals(nativeLanguageTag)) {
                    countText = "現在の問題集：" + counts.activeCount + " 件";
                } else if ("en-US".equals(nativeLanguageTag)) {
                    countText = "Current question bank: " + counts.activeCount;
                } else {
                    countText = "当前题库：" + counts.activeCount + " 条";
                }
                txtActiveCount.setText(countText);
            });
        });
    }

    private void loadLibrary(final String type) {
        txtLibraryEmpty.setVisibility(View.GONE);
        listSentences.setVisibility(View.VISIBLE);
        ioExecutor.execute(() -> {
            final List<FlesDatabase.Sentence> items = "mastery".equals(type)
                    ? database.getMasteryLibrary() : database.getFavoriteLibrary();
            runOnUiThread(() -> {
                libraryItems = items;
                libraryAdapter.clear();
                for (FlesDatabase.Sentence s : items) {
                    String prefix = s.section.length() == 0 ? "" : "[" + s.section + "] ";
                    libraryAdapter.add(prefix + s.targetSentence + "\n" + s.nativeMeaning);
                }
                libraryAdapter.notifyDataSetChanged();
                boolean empty = items.isEmpty();
                txtLibraryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                listSentences.setVisibility(empty ? View.GONE : View.VISIBLE);
                if ("ja-JP".equals(nativeLanguageTag)) {
                    txtLibraryEmpty.setText("mastery".equals(type) ? "習得済みの例文はまだありません。" : "お気に入りの例文はまだありません。");
                } else if ("en-US".equals(nativeLanguageTag)) {
                    txtLibraryEmpty.setText("mastery".equals(type) ? "No mastered sentences yet." : "No favorite sentences yet.");
                } else {
                    txtLibraryEmpty.setText("mastery".equals(type) ? "熟练库暂时为空。" : "收藏库暂时为空。");
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 学习池和随机抽题：全部 worker 方法只从 ioExecutor 调用
    // ------------------------------------------------------------------

    private void refreshWorkerPools() {
        String today = LocalDate.now().toString();
        workerNormalPool = database.getNormalLearningSentences(today);
        workerFavoritePool = database.getFavoriteLearningSentences(today);
        workerSentenceMap.clear();
        for (FlesDatabase.Sentence s : workerNormalPool) workerSentenceMap.put(s.sentenceKey, s);
        for (FlesDatabase.Sentence s : workerFavoritePool) workerSentenceMap.put(s.sentenceKey, s);

        Set<String> normalKeys = new HashSet<String>();
        for (FlesDatabase.Sentence s : workerNormalPool) normalKeys.add(s.sentenceKey);
        Set<String> favoriteKeys = new HashSet<String>();
        for (FlesDatabase.Sentence s : workerFavoritePool) favoriteKeys.add(s.sentenceKey);
        workerNormalBag.retainAll(normalKeys);
        workerFavoriteBag.retainAll(favoriteKeys);
    }

    private String choosePoolTypeOnWorker() {
        boolean hasNormal = !workerNormalPool.isEmpty();
        boolean hasFavorite = !workerFavoritePool.isEmpty();
        if (hasNormal && hasFavorite) return random.nextDouble() < 0.10 ? "FAVORITE" : "NORMAL";
        if (hasFavorite) return "FAVORITE";
        if (hasNormal) return "NORMAL";
        return "EMPTY";
    }

    private void refillBagOnWorker(String poolType) {
        ArrayList<String> bag = "FAVORITE".equals(poolType) ? workerFavoriteBag : workerNormalBag;
        List<FlesDatabase.Sentence> pool = "FAVORITE".equals(poolType)
                ? workerFavoritePool : workerNormalPool;
        bag.clear();
        for (FlesDatabase.Sentence s : pool) bag.add(s.sentenceKey);
        Collections.shuffle(bag, random);
        if (bag.size() > 1 && workerLastSentenceKey != null &&
                workerLastSentenceKey.equals(bag.get(0))) {
            Collections.swap(bag, 0, 1);
        }
    }

    private String drawKeyFromBagOnWorker(String poolType) {
        ArrayList<String> bag = "FAVORITE".equals(poolType) ? workerFavoriteBag : workerNormalBag;
        if (bag.isEmpty()) refillBagOnWorker(poolType);
        if (bag.isEmpty()) return null;
        return bag.remove(0);
    }

    private FlesDatabase.Sentence drawNextSentenceOnWorker() {
        refreshWorkerPools();
        if (workerNormalPool.isEmpty() && workerFavoritePool.isEmpty()) return null;

        // 每次只做一次 90/10 池选择，避免“重复时重抽”改变收藏池的真实概率。
        String poolType = choosePoolTypeOnWorker();
        if ("EMPTY".equals(poolType)) return null;
        String key = drawKeyFromBagOnWorker(poolType);

        // 选中的袋子可能因同步/熟练状态变化而刚好为空，回退到另一个非空池。
        if (key == null) {
            String fallback = "FAVORITE".equals(poolType) ? "NORMAL" : "FAVORITE";
            key = drawKeyFromBagOnWorker(fallback);
        }
        if (key == null) return null;

        FlesDatabase.Sentence sentence = workerSentenceMap.get(key);
        if (sentence == null) {
            // 极端状态变化时重新读取一次候选池，不在这里无限循环。
            refreshWorkerPools();
            sentence = workerSentenceMap.get(key);
        }
        if (sentence != null) workerLastSentenceKey = key;
        return sentence;
    }

    private String choosePromptDirectionOnWorker(final String mode) {
        if ("FOREIGN_TO_NATIVE".equals(mode)) return "FOREIGN_TO_NATIVE";
        if ("NATIVE_TO_FOREIGN".equals(mode)) return "NATIVE_TO_FOREIGN";
        return random.nextBoolean() ? "FOREIGN_TO_NATIVE" : "NATIVE_TO_FOREIGN";
    }

    private FlesDatabase.MasteryResult showNextCardOnWorker(String reason, String oldKey,
                                                              long oldDurationMs,
                                                              String masteryLocalDate,
                                                              long masteryClickedAt) {
        FlesDatabase.MasteryResult masteryResult = null;
        // 先结束并保存本卡片的录音，再结束卡片记录，保证录音对应的是正确的卡片记录。
        stopVoiceRecordingOnWorker("CARD_TRANSITION");
        if (workerAttemptId >= 0 && workerCurrentSentenceKey != null) {
            long now = System.currentTimeMillis();
            String eventType = "NEXT_CLICKED";
            if ("mastery".equals(reason)) eventType = "MASTERY_CLICKED";
            if ("exit".equals(reason)) eventType = "CARD_EXITED";
            if ("app_background".equals(reason)) eventType = "CARD_EXITED";
            database.insertStudyEvent(workerAttemptId, workerSessionId,
                    workerCurrentSentenceKey, eventType, now, oldDurationMs);
            database.finishStudyAttemptWithAdditionalDuration(workerAttemptId, now, oldDurationMs, reason);
            workerAttemptId = -1L;
        }

        if ("mastery".equals(reason) && oldKey != null) {
            String date = masteryLocalDate == null ? LocalDate.now().toString() : masteryLocalDate;
            long clickedAt = masteryClickedAt > 0L ? masteryClickedAt : System.currentTimeMillis();
            masteryResult = database.markMastery(oldKey, date, clickedAt);
        }

        FlesDatabase.Sentence next = drawNextSentenceOnWorker();
        if (next == null) {
            // 学习记录只在用户点“退出学习”时结束；没有可学句子时也保持它打开。
            workerCurrentSentenceKey = null;
            runOnUiThread(() -> showNoLearningSentences());
            return masteryResult;
        }

        String direction = choosePromptDirectionOnWorker(learningMode);
        long now = System.currentTimeMillis();
        if (workerSessionId < 0) workerSessionId = database.createStudySession(now);
        workerCurrentSentenceKey = next.sentenceKey;
        workerAttemptId = database.createStudyAttempt(
                workerSessionId, next.sentenceKey, direction, now);
        database.insertStudyEvent(workerAttemptId, workerSessionId,
                next.sentenceKey, "CARD_SHOWN", now, 0L);

        final FlesDatabase.Sentence sentenceForUi = next;
        final String directionForUi = direction;
        runOnUiThread(() -> showCard(sentenceForUi, directionForUi));
        return masteryResult;
    }

    // ------------------------------------------------------------------
    // 显示卡片与答案、提示
    // ------------------------------------------------------------------

    private void showCard(FlesDatabase.Sentence sentence, String direction) {
        currentSentence = sentence;
        currentPromptDirection = direction;
        answerVisible = false;
        memoryHintVisible = false;
        pronunciationGuideVisible = false;
        currentSentenceFavorited = sentence.isFavorited;
        transitioning = false;
        // 新卡片：录音按钮一定回到初始外观（上一张卡片的录音已在切换时被结束）。
        voiceRecordButtonHeld = false;
        applyVoiceButtonVisual(false);
        if (activityResumed) {
            segmentStartElapsedRealtime = SystemClock.elapsedRealtime();
        } else {
            segmentStartElapsedRealtime = -1L;
            segmentNeedsResume = true;
        }

        scrollStudyContent.setVisibility(View.VISIBLE);
        txtNoLearningSentences.setVisibility(View.GONE);
        txtStudySection.setText(sentence.section.length() == 0
                ? "" : getLocalizedText("topic") + "：" + sentence.section);

        if ("FOREIGN_TO_NATIVE".equals(direction)) {
            txtPromptDirection.setText(promptInstruction(true));
            txtPromptText.setText(sentence.targetSentence);
            txtAnswer.setText(sentence.nativeMeaning);
        } else {
            txtPromptDirection.setText(promptInstruction(false));
            txtPromptText.setText(sentence.nativeMeaning);
            txtAnswer.setText(sentence.targetSentence);
        }

        txtAnswer.setVisibility(View.GONE);
        btnShowAnswer.setVisibility(sentence.nativeMeaning.length() == 0 || sentence.targetSentence.length() == 0
                ? View.GONE : View.VISIBLE);
        btnShowAnswer.setText(getLocalizedText("showAnswer"));

        btnMemoryHint.setVisibility(sentence.memoryHint.trim().length() == 0 ? View.GONE : View.VISIBLE);
        txtMemoryHint.setText(sentence.memoryHint);
        txtMemoryHint.setVisibility(View.GONE);

        btnPronunciationGuide.setVisibility(sentence.pronunciationGuide.trim().length() == 0
                ? View.GONE : View.VISIBLE);
        txtPronunciationGuide.setText(sentence.pronunciationGuide);
        txtPronunciationGuide.setVisibility(View.GONE);

        btnToggleFavorite.setText(currentSentenceFavorited
                ? getLocalizedText("favorited") : getLocalizedText("favorite"));
        setStudyControlsEnabled(true);
    }

    private String promptInstruction(boolean foreignShown) {
        if ("ja-JP".equals(nativeLanguageTag)) {
            return foreignShown ? "母語の意味を思い出してください" : "外国語の文を思い出してください";
        }
        if ("en-US".equals(nativeLanguageTag)) {
            return foreignShown ? "Recall the meaning in your native language" : "Recall the sentence in the target language";
        }
        return foreignShown ? "请回忆母语意思" : "请回忆外语句子";
    }

    private void revealAnswer() {
        if (currentSentence == null || transitioning || answerVisible) return;
        answerVisible = true;
        txtAnswer.setVisibility(View.VISIBLE);
        btnShowAnswer.setVisibility(View.GONE);
        logStudyEvent("ANSWER_REVEALED");
    }

    private void toggleMemoryHint() {
        if (currentSentence == null || transitioning) return;
        memoryHintVisible = !memoryHintVisible;
        txtMemoryHint.setVisibility(memoryHintVisible ? View.VISIBLE : View.GONE);
        if (memoryHintVisible) logStudyEvent("MEMORY_HINT_REVEALED");
    }

    private void togglePronunciationGuide() {
        if (currentSentence == null || transitioning) return;
        pronunciationGuideVisible = !pronunciationGuideVisible;
        txtPronunciationGuide.setVisibility(pronunciationGuideVisible ? View.VISIBLE : View.GONE);
        if (pronunciationGuideVisible) logStudyEvent("PRONUNCIATION_GUIDE_REVEALED");
    }

    // ------------------------------------------------------------------
    // 熟悉 / 收藏 / 下一题 / 退出
    // ------------------------------------------------------------------

    private void handleMasteryClick() {
        if (currentSentence == null || transitioning) return;
        transitioning = true;
        setStudyControlsEnabled(false);
        final String key = currentSentence.sentenceKey;
        final long duration = currentSegmentDuration();
        final long clickedAt = System.currentTimeMillis();
        final String clickedDate = LocalDate.now().toString();

        ioExecutor.execute(() -> {
            FlesDatabase.MasteryResult result = showNextCardOnWorker(
                    "mastery", key, duration, clickedDate, clickedAt);
            runOnUiThread(() -> {
                if (result != null && result.becamePermanentNow) {
                    showToastMessage(getLocalizedText("toastMastered3"));
                } else if (result != null && result.alreadyClickedToday) {
                    showToastMessage(getLocalizedText("toastMasteredAlready"));
                } else {
                    showToastMessage(getLocalizedText("toastMasteredToday"));
                }
                refreshHomeCounts();
            });
        });
    }

    private void handleFavoriteClick() {
        if (currentSentence == null || transitioning || !btnToggleFavorite.isEnabled()) return;
        final String key = currentSentence.sentenceKey;
        final long duration = currentSegmentDuration();
        btnToggleFavorite.setEnabled(false);

        // 收藏不会切换卡片，因此不冻结整个学习页。若此时 APP 进入后台，
        // onPause 会在本任务之后排入当前 attempt 的结束操作，时间不会算入后台。
        ioExecutor.execute(() -> {
            boolean favoriteNow = database.toggleFavorite(key);
            database.insertStudyEvent(workerAttemptId >= 0 ? workerAttemptId : null,
                    workerSessionId >= 0 ? workerSessionId : null,
                    key, favoriteNow ? "FAVORITE_ADDED" : "FAVORITE_REMOVED",
                    System.currentTimeMillis(), duration);
            refreshWorkerPools();
            runOnUiThread(() -> {
                if (currentSentence != null && currentSentence.sentenceKey.equals(key)) {
                    currentSentenceFavorited = favoriteNow;
                    currentSentence.isFavorited = favoriteNow;
                    btnToggleFavorite.setText(favoriteNow
                            ? getLocalizedText("favorited") : getLocalizedText("favorite"));
                    btnToggleFavorite.setEnabled(true);
                }
                refreshHomeCounts();
            });
        });
    }

    private void handleNextClick() {
        if (currentSentence == null || transitioning) return;
        transitioning = true;
        setStudyControlsEnabled(false);
        long duration = currentSegmentDuration();
        ioExecutor.execute(() -> showNextCardOnWorker("next", null, duration, null, 0L));
    }

    private void exitStudy() {
        if (pageStudy.getVisibility() != View.VISIBLE) {
            showHomePage();
            return;
        }
        transitioning = true;
        setStudyControlsEnabled(false);
        long duration = currentSegmentDuration();
        ioExecutor.execute(() -> {
            stopVoiceRecordingOnWorker("SESSION_EXIT");
            if (workerAttemptId >= 0 && workerCurrentSentenceKey != null) {
                long now = System.currentTimeMillis();
                database.insertStudyEvent(workerAttemptId, workerSessionId,
                        workerCurrentSentenceKey, "CARD_EXITED", now, duration);
                database.finishStudyAttemptWithAdditionalDuration(workerAttemptId, now, duration, "user_exit");
                workerAttemptId = -1L;
            }
            if (workerSessionId >= 0) {
                database.finishStudySession(workerSessionId, System.currentTimeMillis(), "user_exit");
                workerSessionId = -1L;
            }
            workerCurrentSentenceKey = null;
            runOnUiThread(() -> {
                currentSentence = null;
                segmentNeedsResume = false;
                showHomePage();
            });
        });
    }

    private void setStudyControlsEnabled(boolean enabled) {
        btnShowAnswer.setEnabled(enabled);
        btnMemoryHint.setEnabled(enabled);
        btnPronunciationGuide.setEnabled(enabled);
        btnMarkMastered.setEnabled(enabled);
        btnToggleFavorite.setEnabled(enabled);
        btnNextCard.setEnabled(enabled);
        btnExitStudy.setEnabled(enabled);
        btnVoiceRecord.setEnabled(enabled);
        btnVoiceRecord.setAlpha(enabled ? 1f : 0.45f);
    }

    // ------------------------------------------------------------------
    // attempt/session 计时：仅计算当前前台片段
    // ------------------------------------------------------------------

    private long currentSegmentDuration() {
        if (segmentStartElapsedRealtime < 0) return 0L;
        return Math.max(0L, SystemClock.elapsedRealtime() - segmentStartElapsedRealtime);
    }

    // 仅用于 worker 任务写事件的 duration 快照；这里调用时通常仍在主线程。
    private long currentSegmentDurationSafe() {
        return currentSegmentDuration();
    }

    /**
     * 切到后台：只把这一段前台时间累加到当前卡片记录上，并停止录音。
     * 不结束卡片记录，也不结束学习记录——回来后继续同一条。
     */
    private void pauseCurrentCardSegment(String reason) {
        if (currentSentence == null) return;
        final long duration = currentSegmentDuration(); // 计时没在走时为 0
        segmentStartElapsedRealtime = -1L;
        segmentNeedsResume = true;
        voiceRecordButtonHeld = false;
        applyVoiceButtonVisual(false);

        ioExecutor.execute(() -> {
            stopVoiceRecordingOnWorker("APP_PAUSED");
            if (workerAttemptId >= 0L && workerCurrentSentenceKey != null) {
                database.addStudyAttemptDuration(workerAttemptId, duration);
                database.insertStudyEvent(workerAttemptId, workerSessionId,
                        workerCurrentSentenceKey, "APP_PAUSED", System.currentTimeMillis(), duration);
            }
        });
    }

    /** 回到前台：继续同一个学习记录、同一条卡片记录，只是重新开始计时。 */
    private void resumeCurrentCardSegment() {
        if (currentSentence == null) return;
        final String key = currentSentence.sentenceKey;

        ioExecutor.execute(() -> {
            if (workerSessionId >= 0L && workerAttemptId >= 0L && key.equals(workerCurrentSentenceKey)) {
                database.insertStudyEvent(workerAttemptId, workerSessionId,
                        key, "APP_RESUMED", System.currentTimeMillis(), 0L);
            }
            runOnUiThread(() -> {
                if (pageStudy.getVisibility() == View.VISIBLE && currentSentence != null &&
                        currentSentence.sentenceKey.equals(key)) {
                    if (activityResumed) {
                        segmentStartElapsedRealtime = SystemClock.elapsedRealtime();
                        segmentNeedsResume = false;
                    } else {
                        segmentStartElapsedRealtime = -1L;
                        segmentNeedsResume = true;
                    }
                }
            });
        });
    }

    private void logStudyEvent(final String eventType) {
        if (currentSentence == null) return;
        final String key = currentSentence.sentenceKey;
        final long elapsed = currentSegmentDuration();
        ioExecutor.execute(() -> {
            database.insertStudyEvent(
                    workerAttemptId >= 0 ? workerAttemptId : null,
                    workerSessionId >= 0 ? workerSessionId : null,
                    key, eventType, System.currentTimeMillis(), elapsed);
        });
    }

    // ------------------------------------------------------------------
    // 录音：按住开始，松开停止
    //
    // 线程规则：触摸事件只在主线程更新外观并把任务交给 ioExecutor；
    // 录音的开始 / 结束和数据库写入全部在 ioExecutor 内串行执行，
    // 所以它们和 workerSessionId / workerAttemptId 的读写不会互相打架。
    // ------------------------------------------------------------------

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void setupVoiceButtonVisual() {
        voiceButtonBackground = new GradientDrawable();
        voiceButtonBackground.setShape(GradientDrawable.RECTANGLE);
        voiceButtonBackground.setColor(getColor(R.color.fles_voice_idle));
        voiceButtonBackground.setCornerRadius(dp(24));
        btnVoiceRecord.setBackground(voiceButtonBackground);
        btnVoiceRecord.setElevation(dp(4));
    }

    /**
     * 录音中：变红、圆角变成胶囊形、按钮放大并轻轻“呼吸”；
     * 松开：全部恢复原样。
     */
    private void applyVoiceButtonVisual(boolean recording) {
        if (btnVoiceRecord == null || voiceButtonBackground == null) return;

        if (voicePulseAnimator != null) {
            voicePulseAnimator.cancel();
            voicePulseAnimator = null;
        }
        if (voiceRadiusAnimator != null) voiceRadiusAnimator.cancel();
        btnVoiceRecord.animate().cancel();

        voiceButtonBackground.setColor(getColor(recording
                ? R.color.fles_voice_recording : R.color.fles_voice_idle));
        btnVoiceRecord.setText(getLocalizedText(recording ? "voiceRecording" : "voiceHold"));

        float height = Math.max(btnVoiceRecord.getHeight(), dp(108));
        float fromRadius = voiceButtonBackground.getCornerRadius();
        float toRadius = recording ? height / 2f : dp(24);
        voiceRadiusAnimator = ValueAnimator.ofFloat(fromRadius, toRadius);
        voiceRadiusAnimator.setDuration(160L);
        voiceRadiusAnimator.addUpdateListener(animation ->
                voiceButtonBackground.setCornerRadius((Float) animation.getAnimatedValue()));
        voiceRadiusAnimator.start();

        if (recording) {
            voicePulseAnimator = ObjectAnimator.ofPropertyValuesHolder(btnVoiceRecord,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1.06f, 1.14f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.06f, 1.14f));
            voicePulseAnimator.setDuration(520L);
            voicePulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            voicePulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            voicePulseAnimator.start();
        } else {
            btnVoiceRecord.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
        }
    }

    private boolean handleVoiceRecordTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                if (currentSentence == null || transitioning) return true;

                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    // 第一次授权后需要再按一次：避免权限弹窗返回时意外开始录音。
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_RECORD_AUDIO);
                    showToastMessage(getLocalizedText("toastMicPermission"));
                    return true;
                }

                voiceRecordButtonHeld = true;
                applyVoiceButtonVisual(true);
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                ioExecutor.execute(this::startVoiceRecordingOnWorker);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!voiceRecordButtonHeld) return true; // 这次按下并没有开始录音
                voiceRecordButtonHeld = false;
                applyVoiceButtonVisual(false);
                ioExecutor.execute(() -> stopVoiceRecordingOnWorker("USER_RELEASE"));
                return true;

            default:
                return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            showToastMessage(getLocalizedText(granted ? "toastMicGranted" : "toastMicDenied"));
        }
    }

    /**
     * 录音文件名：FLES_S{学习记录ID}_A{卡片记录ID}_R{该卡片第几段}_{开始时间}.wav
     * 例：FLES_S000012_A00000345_R01_20261031-143005.wav
     * 其中 A 后面的数字就是 study_attempts.id，所以文件名本身就能定位到唯一一条卡片记录；
     * R 区分同一张卡片上按了多次录音。
     */
    private static String buildRecordingFileName(long sessionId, long attemptId,
                                                 int sequenceNo, long startedAt) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startedAt));
        return String.format(Locale.US, "FLES_S%06d_A%08d_R%02d_%s.wav",
                sessionId, attemptId, sequenceNo, stamp);
    }

    private void resetVoiceButtonOnUi(final boolean force) {
        runOnUiThread(() -> {
            if (force) voiceRecordButtonHeld = false;
            if (!voiceRecordButtonHeld) applyVoiceButtonVisual(false);
        });
    }

    /** 只能在 ioExecutor 上调用。 */
    private void startVoiceRecordingOnWorker() {
        if (!voiceRecordButtonHeld) return;      // 手指已经松开：不创建录音
        if (activeVoiceRecording != null) return;

        if (workerSessionId < 0L || workerAttemptId < 0L || workerCurrentSentenceKey == null) {
            runOnUiThread(() -> {
                voiceRecordButtonHeld = false;
                applyVoiceButtonVisual(false);
                showToastMessage(getLocalizedText("toastNoCard"));
            });
            return;
        }

        final long sessionId = workerSessionId;
        final long attemptId = workerAttemptId;
        final String sentenceKey = workerCurrentSentenceKey;
        final long startedAt = System.currentTimeMillis();

        final File directory = new File(getFilesDir(), "voice_recordings");
        int sequenceNo = database.nextVoiceSequence(attemptId);
        String fileName = buildRecordingFileName(sessionId, attemptId, sequenceNo, startedAt);
        while (new File(directory, fileName).exists()) { // 极端情况：文件在但数据库没有这一行
            sequenceNo++;
            fileName = buildRecordingFileName(sessionId, attemptId, sequenceNo, startedAt);
        }

        final VoiceRecorder recorder = new VoiceRecorder(new File(directory, fileName), startedAt);
        try {
            recorder.start(getApplicationContext());

            ActiveVoiceRecording active = new ActiveVoiceRecording();
            active.recorder = recorder;
            active.sessionId = sessionId;
            active.attemptId = attemptId;
            active.sequenceNo = sequenceNo;
            active.sentenceKey = sentenceKey;
            active.fileName = fileName;
            active.relativePath = "voice_recordings/" + fileName;
            active.startedAt = startedAt;
            activeVoiceRecording = active;

            database.insertStudyEvent(attemptId, sessionId, sentenceKey,
                    "VOICE_RECORDING_STARTED", startedAt, 0L);
        } catch (Exception e) {
            activeVoiceRecording = null;
            database.insertStudyEvent(attemptId, sessionId, sentenceKey,
                    "VOICE_RECORDING_FAILED", System.currentTimeMillis(), 0L);
            final String detail = e.getMessage();
            runOnUiThread(() -> {
                voiceRecordButtonHeld = false;
                applyVoiceButtonVisual(false);
                showToastMessage(getLocalizedText("toastRecFailed") + (detail == null ? "" : "：" + detail));
            });
        }
    }

    /**
     * 只能在 ioExecutor 上调用：结束录音、等 WAV 封装完成，再登记到数据库。
     * 没有正在进行的录音时什么也不做，所以在各个“结束点”都可以放心调用。
     */
    private void stopVoiceRecordingOnWorker(String stopReason) {
        final ActiveVoiceRecording active = activeVoiceRecording;
        if (active == null) return;
        activeVoiceRecording = null;

        final boolean forceReset = !"USER_RELEASE".equals(stopReason);
        File savedFile = null;
        try {
            active.recorder.requestStop();
            VoiceRecorder.Result result = active.recorder.awaitResult();
            savedFile = result.file;

            if (result.durationMs < MIN_RECORDING_MS) {
                result.file.delete();
                database.insertStudyEvent(active.attemptId, active.sessionId, active.sentenceKey,
                        "VOICE_RECORDING_TOO_SHORT", result.endedAt, result.durationMs);
                runOnUiThread(() -> showToastMessage(getLocalizedText("toastRecTooShort")));
                return;
            }

            database.insertVoiceRecording(
                    active.sessionId, active.attemptId, active.sequenceNo, active.sentenceKey,
                    active.fileName, active.relativePath,
                    result.startedAt, result.endedAt, result.durationMs,
                    result.sampleRate, result.channels, result.bitsPerSample,
                    result.fileSizeBytes, stopReason);
            database.insertStudyEvent(active.attemptId, active.sessionId, active.sentenceKey,
                    "VOICE_RECORDING_SAVED", result.endedAt, result.durationMs);

            final String seconds = String.format(Locale.US, "%.1f s", result.durationMs / 1000f);
            runOnUiThread(() -> showToastMessage(getLocalizedText("toastRecSaved") + " (" + seconds + ")"));

        } catch (VoiceRecorder.EmptyRecordingException e) {
            // 按下后立刻松开：不是故障，当作太短。
            database.insertStudyEvent(active.attemptId, active.sessionId, active.sentenceKey,
                    "VOICE_RECORDING_TOO_SHORT", System.currentTimeMillis(), 0L);
            runOnUiThread(() -> showToastMessage(getLocalizedText("toastRecTooShort")));
        } catch (Exception e) {
            // 失败时不留下没有登记的文件：保证“文件”和“数据库行”一一对应。
            File file = savedFile != null ? savedFile : active.recorder.getOutputFile();
            if (file != null && file.exists()) file.delete();
            database.insertStudyEvent(active.attemptId, active.sessionId, active.sentenceKey,
                    "VOICE_RECORDING_FAILED", System.currentTimeMillis(), 0L);
            final String detail = e.getMessage();
            runOnUiThread(() -> showToastMessage(getLocalizedText("toastRecFailed")
                    + (detail == null ? "" : "：" + detail)));
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            resetVoiceButtonOnUi(forceReset);
        }
    }

    // ------------------------------------------------------------------
    // worker 辅助函数
    // ------------------------------------------------------------------

    private void closeWorkerSessionIfOpen(String reason) {
        stopVoiceRecordingOnWorker("SESSION_CLOSED");
        if (workerAttemptId >= 0 && workerCurrentSentenceKey != null) {
            long now = System.currentTimeMillis();
            database.finishStudyAttemptWithAdditionalDuration(workerAttemptId, now, 0L, reason);
            workerAttemptId = -1L;
        }
        if (workerSessionId >= 0) {
            database.finishStudySession(workerSessionId, System.currentTimeMillis(), reason);
            workerSessionId = -1L;
        }
        workerCurrentSentenceKey = null;
    }

    private void showNoLearningSentences() {
        currentSentence = null;
        segmentStartElapsedRealtime = -1L;
        transitioning = false;
        scrollStudyContent.setVisibility(View.GONE);
        txtNoLearningSentences.setVisibility(View.VISIBLE);
        voiceRecordButtonHeld = false;
        applyVoiceButtonVisual(false);
        setStudyControlsEnabled(false);
        // 即使没有可学句子，也必须允许用户退出学习页。
        btnExitStudy.setEnabled(true);
    }

    private void showToastMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
