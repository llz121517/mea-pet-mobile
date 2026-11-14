# Mea TTS · G2P 详细架构（最终方案）

> 目标：保留 Mea 音色，支持 **中 / 日 / 英** 混排，数字/日期/电话正确读出。
> 原则：**不按语言切引擎**；一个模型 + 一个统一 lexicon + App 侧一层上下文改写 + sherpa ruleFsts。

## 1. 总体数据流

```
用户文本（可能中/日/英/数字混排）
   │
   ▼  App 侧（Kotlin，轻量，无词典/无分词库）
① 拉丁转小写
② 英文 OOV 兜底：词表外的多字母拉丁词按字母拆开（kotlin → k o t l i n）
③ 上下文改写：日语片段（连续汉字且紧挨假名）→ 用映射表改写为假名
   │
   ▼  sherpa C++ 内部
④ 句切分（。！？,.!?…）→ 按句合成（maxNumSentences=1，句子级回调）
⑤ ruleFsts：phone.fst → date.fst → number.fst（顺序重要！）
⑥ CharacterLexicon 最长匹配（统一 lexicon.txt）
⑦ 符号 → ID（tokens.txt）+ add_blank
   ▼
VITS 单图推理 → 22050Hz 音频
```

## 2. 数据文件（模型包内）

| 文件 | 内容 | 大小 |
|---|---|---|
| model.onnx | 混合精度单图（prior encoder / duration predictor 为 fp32，flow / decoder 为 fp16），metadata：sample_rate=22050, add_blank=1, blank_id=0, n_speakers=1, comment=icefall, jieba=1 | 74.9MB |
| tokens.txt | 实际 69 行、67 个唯一 ID（`_`/`^`/`$` 共用 ID 0，ID 58 缺号） | 439 B |
| lexicon.txt（源文件 lexicon_final4.txt） | 中文词/字 89k + 英文词 126k（含 41 条 OOV 技术词/品牌词补条）+ 假名/假名词 ~24k；**不含日文汉字词条** | 8.9MB |
| fsts/phone.fst, date.fst, number.fst | 数字/日期/电话归一化（phone 必须最先） | ~200KB |

> `ja_reading_map.json`（日文词形→假名 16,976 + 单字 1,071 + 动词词干 1,077，0.5MB）**不在模型包内**：它随 APK `assets/tts/` 分发（见 `docs/tts/model-package.md`），模型包只含上表 6 个文件。`assets/tts/en_words.bloom`（512KB，1<<22 bits，k=3）是英文 OOV 判定用的词表 Bloom 索引，同样随 APK 分发。

## 3. 上下文改写规则（App 侧核心逻辑）

```
扫描文本，找「连续汉字段」；若该段紧挨假名（前或后）→ 判定为日语片段：
  段内最长匹配 ja_reading_map.word（≤8 字）→ 替换为假名读音
  未命中的单字：
    · 若其后紧跟假名且有词干读音 → 用词干（知らない→し、行こう→い）
    · 否则用整字读音（私は→わたし、何も→なに）
  仍无映射 → 保留原字
非日语片段（纯中文/数字/英文）：完全不动 → 中文永不被日文词条抢占
```

两条已知边界：

- **标点与句末也算「非假名」。** 判定只看相邻字符是不是假名，因此「天気。」里的「気」后面紧跟的是「。」而不是假名，不会被当前字前后假名判定为日语 —— 需要它后面还有假名（如「天気が」），或整词命中 `ja_reading_map.word`（如「天気」），才会被改写。
- **词条最长匹配上限 8 字。** 超过 8 字的复合词不会整条命中，会退化为更短的词条或单字/词干兜底。

### 英文 OOV 兜底：逐字母拼读

sherpa 的 CharacterLexicon 对词表外英文词**整词丢弃**（日志 `char...: Ignore OOV 'kotlin'`），不会自动退化为逐字母。因此 App 在拉丁转小写之后（日文改写之前）对每个拉丁 run 做检查：

- `run.length > 1 && !knownEnglishWord(run)` → 按字母拆成**单个空格**分隔的小写字母串：`kotlin` → `k o t l i n`；非字母字符丢弃，`don't` → `d o n t`；词边界不含句点，`you.` 按 `you` 查表、句号原样保留；
- 单字母与词表命中的词保持原样；
- 词表判定走 `assets/tts/en_words.bloom`（`EnglishBloomIndex`）：1<<22 bits / k=3 / FNV-1a 双种子，误判率约 0.06%，误判只会导致少拆一个词（sherpa 整词丢弃），不会拆错已知词；
- Bloom 资产缺失或长度不符时 `EMPTY.mightContain` 恒 true → 退化为「不拆分」，宁可保持原词也不把已知词读成字母。

例：`今天用 kotlin 写了个 app` → 归一化后 `今天用 k o t l i n 写了个 app`，sherpa 日志不再出现 `Ignore OOV 'kotlin'`。

## 4. 各语言/符号来源

- 中文：pypinyin BOPOMOFO → bopomofo_to_ipa（训练同源），词组表 47k + 单字 41,923；多音字靠词条（行长 háng zhǎng、重庆 chóng qìng）。
- 英文：CMUdict ARPAbet → IPA（重音 ˈ/ˌ），词表 126k + 41 条手工 OOV 补条；运行时文本转小写，词表外多字母词由 App 逐字母兜底。
- 日文：JMdict 常用词 → 假名读音 → pyopenjtalk IPA2；lexicon 里只放**假名**，汉字由 App 改写。
- 数字：ruleFsts（phone→date→number）；电话逐位（1 读幺），年龄/时间按数词（十八岁、八点三十分）。
- 未知内容兜底：中文单字条目、假名单字、英文字母（App 侧把词表外的多字母英文词拆成字母拼读）、日文单字/词干。

## 5. tokens.txt 约定

实际 **69 行、67 个唯一 ID**：`_`=0（blank，`^`/`$` 也映射 0，三行共用 ID 0）、标点 1-7、`N Q` 8-9、23 个拉丁字母 10-32（缺 c/q/r）、IPA 33-54、`⁼ ʰ` 55-56、反引号（U+0060）=57、**ID 58 缺号**、`#`=59 `*`=60 `=`=61、`ˈ`=62 `ˌ`=63、`→`=64 `↓`=65 `↑`=66、`␣`=67。
add_blank=1 → 运行时自动生成 `[0,s0,0,s1,…,sN,0]`。

## 6. 已验证结论

- pth → 单图 ONNX：ORT vs PyTorch 最大误差 3e-5。
- 混合精度可拼接（74.9MB），sherpa 正常加载；预分词路径勿用（piper 双插 blank 导致语速翻倍）。
- 中文：多音字、词边界、数字/日期/电话正常；在 13 条综合样本上 OOV=0（lexicon 覆盖范围外仍会逐字/字母兜底）。
- 日文：今日→きょう、一緒→いっしょ、天気→てんき、私は→わたし、知らない→しらない。
- 英文：126k 词覆盖 + 词表外逐字母兜底；中英混排正常。
- 综合样本：samples/comprehensive/（13 条）。

## 7. 集成后剩余（真机验证等）

1.8.0 已完成 Android 侧集成（sherpa AAR、`SherpaTtsEngine`、动态采样率播放器、模型包管理、第 3 节日文改写规则），以下仅剩真机确认项，逐条见 `docs/tts/device-test-checklist.md`：

1. 真机验证 lexicon 8.9MB + 模型 75MB 的加载时间与内存占用；
2. 真机验证 16KB page size / SIGILL（sherpa AAR 内嵌 ORT 1.28.2）、打断抢占、悬浮窗朗读、删除后重新导入；
3. 旧版 1.7.x 四模块模型覆盖安装后的自动清理（升级后需重新下载 / 导入模型）。
