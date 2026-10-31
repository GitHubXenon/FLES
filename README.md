# FLES — Foreign Language Example Sentence

## 中文

FLES 是一款用于外语例句背诵的 Android 应用，支持随机学习、双向回忆、收藏、熟练度记录及本地 Excel 题库导入。

### 开发环境

- Java 8（语言兼容级别）
- Android 10（API 29）及以上

### 安装与题库导入
将标准 `.xlsx` 例句库放入手机内部存储的以下目录：

```text
手机内部存储/
└── Documents/
    └── FLES/
        ├── sentences.xlsx
        └── audio/                 （预留目录，音频功能暂未实现）
```

首次使用时，在 FLES 中选择并授权 `Documents/FLES/` 文件夹。若 `sentences.xlsx` 不存在，可在该目录中新建或复制进去。应用会检查文件并导入题库。

### Excel 格式要求
必须使用标准 `.xlsx` 文件。工作表第一行是表头，数据从第二行开始；列顺序必须固定为：

| 列 | 字段 | 说明 |
|---|---|---|
| A | 序号 | 例句序号 |
| B | 板块 | 主题或知识点类别 |
| C | 外语例句 | 必填；用于识别同一句例句 |
| D | 母语意思 | 例句在用户母语中的含义 |
| E | 覆盖的词汇和语法 | 本句涉及的目标词汇、语法 |
| F | 提示和讲解 | 记忆提示、易混点和关键说明 |
| G | 全句注音 | 按目标语言提供读音标注 |
| H | 发音音频（缺省） | 可留空；音频功能暂未实现 |

不要调整列顺序，也不要增加其他列。H 列建议保留但留空；若当前导入器支持缺少 H 列，也可以省略该列。每行应对应一条例句，避免合并单元格。

### 使用大语言模型生成题库
将你的生词/语法清单、未掌握原因、易混淆词分组，以及 `LLM_PROMPT.md` 中对应语言的提示词一起提供给大语言模型。要求模型输出符合上述列顺序的标准 `.xlsx` 文件。音频列保持为空。

若模型无法生成 `.xlsx`，可以暂时要求它输出 CSV；但当前 FLES 导入器读取的是 `.xlsx`，CSV 不能直接导入，必须先转换为标准 `.xlsx` 文件。

---

## 日本語

FLES は、外国語の例文を使って学習・暗記するための Android アプリです。ランダム学習、双方向の想起練習、お気に入り、習熟記録、端末内の Excel 学習データの取り込みに対応します。

### 開発環境
- Java 8（言語互換レベル）
- Android 10（API 29）以降

### 学習データの配置と取り込み
標準形式の `.xlsx` ファイルを、端末の内部共有ストレージにある次のフォルダーへ配置してください。

```text
内部ストレージ/
└── Documents/
    └── FLES/
        ├── sentences.xlsx
        └── audio/                 （予約フォルダー。音声機能は未実装）
```

初回起動時に、FLES で `Documents/FLES/` フォルダーを選択し、アクセスを許可してください。`sentences.xlsx` が存在しない場合は、このフォルダー内に新規作成するか、コピーしてください。アプリがファイルを確認し、学習データを取り込みます。

### Excel ファイルの形式
標準の `.xlsx` 形式を使用してください。最初の行をヘッダー、2 行目以降をデータとし、列順を次のように固定してください。

| 列 | フィールド | 説明 |
|---|---|---|
| A | 番号 | 例文番号 |
| B | トピック | テーマ・学習項目 |
| C | 外国語の例文 | 必須。同一例文の識別に使用 |
| D | 母語での意味 | ユーザーの母語による意味 |
| E | 対象語彙・文法 | 例文に含まれる語彙や文法 |
| F | ヒント・解説 | 記憶のヒント、混同しやすい点、重要事項 |
| G | 文全体の読み方 | 学習対象言語に応じた読み方の表記 |
| H | 発音音声（任意） | 空欄可。音声機能は未実装 |

列の順序を変更したり、列を追加したりしないでください。H 列は空欄のまま残すことを推奨します。現在のインポーターが H 列の省略に対応している場合は、省略も可能です。セルの結合は避け、1 行につき 1 つの例文を入力してください。

### LLM による学習データの作成
覚えていない単語・文法のリスト、理解できていない理由、混同しやすい語のグループ、および `LLM_PROMPT.md` の該当言語のプロンプトを LLM に入力してください。指定した列順の標準 `.xlsx` ファイルを出力するよう指示してください。音声列は空欄にしてください。

LLM が `.xlsx` を出力できない場合は、一時的に CSV を出力させてもかまいません。ただし、現在の FLES は `.xlsx` を読み込みます。CSV は直接取り込めないため、標準 `.xlsx` に変換してから使用してください。

---

## English

FLES is an Android app for learning and memorizing foreign-language example sentences. It supports randomized practice, bidirectional recall, favorites, mastery tracking, and local Excel sentence-bank import.

### Development Environment
- Java 8 language compatibility
- Android 10 (API 29) or later

### Sentence Bank Location and Import
Place a standard `.xlsx` sentence bank in the following folder on the device's internal shared storage:

```text
Internal Storage/
└── Documents/
    └── FLES/
        ├── sentences.xlsx
        └── audio/                 (Reserved; audio features are not implemented yet)
```

On first use, select and grant FLES access to the `Documents/FLES/` folder. If `sentences.xlsx` does not exist, create it or copy it into this folder. The app checks the file and imports the sentence bank.

### Excel Format Requirements
Use a standard `.xlsx` workbook. The first row must contain headers, and data must start from row 2. The column order is fixed:

| Column | Field | Description |
|---|---|---|
| A | No. | Sentence number |
| B | Topic | Topic or learning category |
| C | Example Sentence (Target Language) | Required; used to identify a sentence |
| D | Meaning in Native Language | Meaning in the user's native language |
| E | Covered Vocabulary and Grammar | Target vocabulary and grammar in the sentence |
| F | Hints and Explanations | Memory cues, confusing points, and key notes |
| G | Full-Sentence Reading | Reading/pronunciation notation appropriate for the target language |
| H | Pronunciation Audio (Optional) | May be blank; audio features are not implemented yet |

Do not change the column order or add extra columns. It is recommended to keep column H and leave it blank. If the current importer supports a missing H column, that column may also be omitted. Avoid merged cells and use one sentence per row.

### Generating a Sentence Bank with an LLM
Provide the LLM with your vocabulary and grammar list, the reasons you have not mastered each item, any deliberately grouped confusing words, and the matching language version of the prompt in `LLM_PROMPT.md`. Ask it to produce a standard `.xlsx` workbook using the exact column order above. Leave the audio column blank.

If the LLM cannot generate `.xlsx`, you may ask for CSV as a temporary fallback. However, the current FLES importer reads `.xlsx` files, so CSV cannot be imported directly. Convert the CSV to a standard `.xlsx` workbook before importing it into FLES.
