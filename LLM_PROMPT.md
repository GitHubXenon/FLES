# FLES — LLM Prompt for Generating Example Sentences

Use one of the following prompts. Replace the bracketed placeholders and provide your vocabulary/grammar list, learning notes, and confusing-word groups together with the prompt.

---

## 中文版 Prompt

我总结了现阶段 **[外语]** 学习中的生词、生语法以及尚未掌握的原因。我的母语是 **[母语]**。我还将一些容易混淆的词语特意放在了一起，例如读音相近或意思容易混淆的词。

请根据我提供的词汇和语法清单、备注、未掌握原因及易混淆词分组，生成适合我背诵的例句，并严格遵守以下要求：

1. **输出格式：** 优先输出可正常打开的标准 Excel `.xlsx` 文件。如果确实无法生成 `.xlsx`，可以暂时输出 CSV；但请注意，CSV 不能直接导入 FLES，必须之后转换成 `.xlsx`。
2. **字段及顺序：** 必须严格输出以下 8 列，不得缺少、增加或调换字段：
   - A：序号
   - B：板块
   - C：外语例句
   - D：母语意思
   - E：覆盖的词汇和语法
   - F：提示和讲解
   - G：全句注音
   - H：发音音频（缺省，留空即可）
3. **提高知识密度：** 尽可能将多个尚未掌握的单词和语法自然地组合在同一个句子中，但不要为了堆砌知识点而牺牲自然度。
4. **针对薄弱点：** 仔细分析我的备注、未掌握原因以及易混淆词分组，推断我真正容易混淆或理解错误的地方，并据此设计有针对性的例句和提示。
5. **句子难度：** 不要只输出过于简单的词汇碎片，例如“三百、六百、八百、三千、一億”或 “cat, dog, monkey”。每条例句都必须是自然、完整、有语法结构的句子。
6. **句子结构：** 每行原则上只写一个完整句子，不要拆成两个独立句子或连续写两个句号。尽量使用连接词、转折、因果、条件等结构，使句子包含清晰的逻辑关系。
7. **语境多样性：** 例句应覆盖不同的日常生活、工作、学习及其他合理语境，帮助我理解同一词汇或语法在不同情境下的用法。
8. **补充词汇：** 可以使用生词表中没有出现、但难度低于或接近当前学习水平的常用词，以保证句子自然、完整。
9. **适度重复：** 同一个生词或语法点可以出现在多个例句中；必要时通过不同语境展示其不同用法。
10. **注音规则：**
    - 日语：在 G 列完整重写整句，并为句中所有汉字标注假名读音；不要只标注部分生词。
    - 英语：在 G 列提供关键生词的准确 IPA 音标；不需要给整句生成 IPA。
    - 汉语：在 G 列提供关键生词的准确拼音，并标注声调。
    - 其他语言：提供适合该语言的常用读音标注方式。
11. **内容质量：** 母语意思应准确、自然；E 列指出实际覆盖的词汇和语法；F 列解释关键用法、易混点和记忆提示。不要填写与句子无关的项目。
12. **Excel 结构：** 第一行是上述 8 列的表头，数据从第二行开始；不要合并单元格，不要添加额外工作表或额外列；H 列保持空白。每行一条例句，序号连续。

请优先保证例句自然、语法正确、翻译准确，并真正针对我提供的薄弱点。完成后请提供 `.xlsx` 文件，并简要说明生成了多少条例句、覆盖了哪些主要知识点。不要只在聊天中展示表格而不生成文件。

---

## English Prompt

I have compiled a list of vocabulary and grammar items that I am currently learning in **[target language]**, together with the reasons why I have not mastered them. My native language is **[native language]**. I have also deliberately grouped words that are easy to confuse, for example, words with similar pronunciations or easily confused meanings.

Based on the vocabulary and grammar list, my notes, the reasons I have not mastered each item, and the groups of confusing words that I provide, generate example sentences suitable for memorization. Follow these requirements strictly:

1. **Output format:** Prefer a valid, standard Excel `.xlsx` workbook that can be opened normally. If you genuinely cannot generate `.xlsx`, you may provide CSV as a temporary fallback. Note that CSV cannot be imported directly into FLES; it must subsequently be converted to `.xlsx`.
2. **Exact fields and order:** Output exactly these eight columns. Do not omit, add, or reorder any column:
   - A: No.
   - B: Topic
   - C: Example Sentence (Target Language)
   - D: Meaning in Native Language
   - E: Covered Vocabulary and Grammar
   - F: Hints and Explanations
   - G: Full-Sentence Reading
   - H: Pronunciation Audio (optional; leave blank)
3. **Increase learning density:** Naturally combine multiple vocabulary and grammar items that I have not mastered in the same sentence whenever possible. Do not make a sentence unnatural merely to pack in more items.
4. **Target my weak points:** Carefully interpret my notes, the reasons I selected for not knowing an item, and the deliberately grouped confusing words. Infer the specific distinctions or usage patterns I struggle with, then create targeted sentences and explanations.
5. **Sentence difficulty:** Do not provide trivial fragments such as “three hundred, six hundred, eight hundred, three thousand, one hundred million” or “cat, dog, monkey.” Every example must be a natural, complete sentence with proper grammatical structure.
6. **Sentence structure:** In principle, each row must contain one complete sentence, not two separate sentences or two consecutive sentence-ending periods. Prefer clauses involving conjunctions, contrast, cause and effect, or conditions so that the sentence expresses a clear logical relationship.
7. **Context variety:** Use a variety of realistic contexts, including daily life, work, study, and other appropriate situations. Help me understand how the same vocabulary or grammar works in different contexts.
8. **Supporting vocabulary:** You may use common words that are not in my list, provided their difficulty is lower than or close to my current learning level and they help make the sentence natural and complete.
9. **Useful repetition:** The same vocabulary or grammar item may appear in multiple sentences. Use different contexts where appropriate to demonstrate different meanings or usage patterns.
10. **Pronunciation/reading requirements:**
    - Japanese: In column G, rewrite the entire sentence and provide kana readings for every kanji in the sentence. Do not annotate only selected difficult words.
    - English: In column G, provide accurate IPA transcriptions for key vocabulary items. Do not generate IPA for the entire sentence.
    - Chinese: In column G, provide pinyin with tone marks for key vocabulary items.
    - Other languages: Use a conventional pronunciation or reading notation appropriate for that language.
11. **Content quality:** The native-language meaning must be accurate and natural. Column E must identify the vocabulary and grammar actually used. Column F must explain important usage, confusing distinctions, and memory cues. Do not include irrelevant items.
12. **Workbook structure:** Put the eight headers above in the first row and start the data in row 2. Do not merge cells, add worksheets, or add columns. Leave column H blank. Use one example sentence per row and consecutive row numbers.

Prioritize natural sentences, correct grammar, accurate translations, and examples that genuinely address my weak points. When finished, provide the `.xlsx` file and briefly state how many sentences were generated and which major learning points they cover. Do not merely display a table in chat; generate the actual file.

---

## 日本語版 Prompt

私は現在学習している **[学習対象言語]** の未知語彙・文法と、それらをまだ習得できていない理由をまとめました。私の母語は **[母語]** です。また、発音が似ている語や意味を混同しやすい語など、間違えやすい単語を意図的にグループ化しています。

私が提供する語彙・文法リスト、メモ、未習得の理由、混同しやすい語のグループをもとに、暗記・復習に適した例文を作成してください。次の条件を厳守してください。

1. **出力形式：** 正常に開ける標準的な Excel `.xlsx` ファイルを優先してください。どうしても `.xlsx` を生成できない場合は、一時的な代替として CSV を出力してもかまいません。ただし、CSV は FLES に直接インポートできないため、後で `.xlsx` に変換する必要があります。
2. **フィールドと列順：** 必ず次の 8 列をこの順番で出力してください。列の省略、追加、並べ替えは禁止です。
   - A：番号
   - B：トピック
   - C：外国語の例文
   - D：母語での意味
   - E：対象語彙・文法
   - F：ヒント・解説
   - G：文全体の読み方
   - H：発音音声（任意。空欄にする）
3. **学習密度を高める：** 可能な限り、まだ習得できていない複数の単語や文法を一つの例文に自然に組み合わせてください。ただし、学習項目を詰め込むために不自然な文にしないでください。
4. **弱点に合わせる：** 私のメモ、未習得の理由、意図的にまとめた混同しやすい語を注意深く分析してください。私が実際に混同している違いや使い方を推測し、それに対応する例文と解説を作成してください。
5. **文の難易度：** 「三百、六百、八百、三千、一億」や「cat, dog, monkey」のような単純な語句の羅列は避けてください。すべての例文を、自然で完全な文法構造を持つ文にしてください。
6. **文の構造：** 原則として 1 行につき一つの完全な文にしてください。二つの独立した文に分けたり、文末の句点を二つ続けたりしないでください。接続表現、逆接、因果関係、条件などを使い、明確な論理関係を持つ文にしてください。
7. **場面の多様性：** 日常生活、仕事、学習など、さまざまな自然な場面を取り入れてください。同じ語彙や文法が異なる状況でどのように使われるか理解できるようにしてください。
8. **補助語彙：** 文を自然で完全なものにするため、私のリストにない単語を使用してもかまいません。ただし、現在の学習レベルより難易度が低い、または同程度の一般的な語彙を優先してください。
9. **適切な反復：** 同じ単語や文法項目を複数の例文で使用してもかまいません。必要に応じて異なる場面を使い、意味や用法の違いを示してください。
10. **読み方・発音の要件：**
    - 日本語：G 列には文全体を書き直し、文中のすべての漢字にふりがなを付けてください。一部の難しい単語だけに読み方を付けるのではなく、全文を対象にしてください。
    - 英語：G 列には重要な語彙の正確な IPA 発音記号を記載してください。文全体の IPA は不要です。
    - 中国語：G 列には重要な語彙の声調付きピンインを記載してください。
    - その他の言語：その言語で一般的に使われる発音・読み方の表記を使用してください。
11. **内容の品質：** 母語訳は正確で自然な表現にしてください。E 列には実際に例文で使用した語彙・文法を記載し、F 列には重要な用法、混同しやすい点、記憶のヒントを説明してください。例文と関係のない内容は記載しないでください。
12. **Excel の構造：** 1 行目を上記 8 列のヘッダーとし、2 行目からデータを入力してください。セル結合、追加のワークシート、追加列は禁止です。H 列は空欄にしてください。1 行につき例文を一つ記載し、番号は連番にしてください。

例文の自然さ、文法の正確さ、翻訳の正確さ、そして私が提供した弱点への対応を最優先してください。完成したら `.xlsx` ファイルを提供し、生成した例文数と主な学習項目を簡潔に説明してください。チャット上に表を表示するだけではなく、実際のファイルを生成してください。
