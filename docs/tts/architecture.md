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
③ 英文数字预转换：紧邻拉丁词的整数 → 英文数词（3 → three，见 §3）
④ 日文数字改写：日文上下文里的整数 → 假名数词与日期/时刻/量词读法
   （15時 → じゅうごじ、9月1日 → くがつついたち，见 §3.1）
⑤ 上下文改写：日语片段（连续汉字且紧挨假名）→ 用映射表改写为假名
   │
   ▼  sherpa C++ 内部
⑥ 句切分（。！？,.!?…）→ 逐句合成（maxNumSentences=1，每句一个分块）→ 每句完成即回调 App；
   App 首块到达就开播，后续分块边合成边写 AudioTrack（generateWithConfigAndCallback）
⑦ ruleFsts：phone.fst → date.fst → number.fst（顺序重要！）
⑧ CharacterLexicon 最长匹配（统一 lexicon.txt）
⑨ 符号 → ID（tokens.txt）+ add_blank
   ▼
VITS 单图推理 → 22050Hz 音频
```

> **逐句流式已启用**（2026-11-15）：sherpa 对每句合成完即回调，App 在首块到达时新建 AudioTrack 开播，后续分块入队由播放器专用线程写入；打断时回调返回 0 让原生推理中止剩余句子（不再白算完）。桌面实测 4 句中文：首块 1.10s（整段 3.78s）；8 句：首块 ~1.1s（整段 8.41s）。
>
> **回调对象必须用 Java 适配器 `TtsStreamCallback`**：sherpa JNI 用 `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")` 取回调方法（`.so` 字符串可复核）；Kotlin 2.x 的 indy lambda 脱糖后只有擦除版 `invoke(Object)`，直接传 lambda 会在真机上 CheckJNI SIGABRT（`JNI NewFloatArray called with pending exception NoSuchMethodError`，2026-11-15 实测）。Java 类按泛型实参实现 `Function1<float[], Integer>` 时 javac 会生成该专用签名 + 擦除桥接；`TtsStreamCallbackTest` 用反射守护该签名，`proguard-rules.pro` 的 `-keep class com.meapet.mobile.tts.**` 保证 R8 不删改。
>
> **多句并行（`maxNumSentences > 1`）仍未启用**：批量并行实测只快约 8%（8 句 8.41s→7.79s），且 batch 越大句间停顿越短、听感有变化，收益不抵复杂度。已知取舍：真机合成约 1.1× 实时（RTF≈1.1），所以句间可能有轻微停顿，这是流式播放在"合成慢于实时"设备上的预期代价。
>
> **AudioTrack 的两个平台坑（Xiaomi 22041216UC / MIUI 14 实测，2026-11-15）**：①空 buffer 就 `play()` 的 track 会一帧不渲染（无声）——写线程改为先写入首批数据再 `play()`；②总帧数 < track buffer（2 秒）的片段，AudioFlinger 一直挂着不消费：`playState=PLAYING`、`underrun=0`、播放头恒 0、marker 永不到，表现为 **≤2 秒的短回复/试听整段无声**（此前只能靠轮询超时收尾，用户听到的是静音）。现在写线程在结束标记前用**静音补齐到 buffer 容量**逼其开始渲染，marker 仍打在真实音频末尾（补齐静音不可闻）。修复前后对照见 [`../archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](../archive/checklists/2026-11-15-tts-device-test-1.8.0.md) §2.8。

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

### 英文数字：预转换为英文数词

lexicon 里**没有阿拉伯数字条目**，数字由 sherpa 的 `ruleFsts`（中文数字前端）在查表前转成中文数词——这在英文句子里会把 `3` 读成 `sān`。因此 App 在 OOV 兜底之后、日文改写之前加了 `convertEnglishNumbers`：

- 整数 run 任一侧（跳过空格的最近邻）是拉丁字母 → 视为英文上下文，转英文基数词：`3`→`three`、`12`→`twelve`、`1234`→`one thousand two hundred thirty four`（lexicon 已覆盖 zero…million）；
- 相邻是 CJK/假名 → 保持原样，继续走 `ruleFsts`（中文「3 个」= sān、电话逐位、日期读法都不受影响）；
- 复合数字不转：紧邻 `.` `:` `/` `%` `-` `+` 且再外侧是数字（`3.5`、`10:30`、`3/4`、`-5`）；≥7 位（电话）或 `0` 开头的多位数也不转。

> 已知边界：**中文句子里的数字仍走中文 `ruleFsts`**（`3 个`→sān gè、电话逐位、中文日期读法都不受影响）。日文句子的数字已由 §3.1 处理。

### 3.1 日文数字：改写为假名数词

日文句子里的阿拉伯数字原先同样走中文 `ruleFsts`，`15 時` 读成 shí wǔ shí、`9 月 1 日` 读成 jiǔ yuè yī rì。`rewriteJapaneseNumbers` 在英文数字之后、日文汉字改写之前把日文上下文里的数字换成假名读法：

- **上下文判定**：数字任一侧（跳过空格的最近邻）紧邻**假名**，或数字后紧跟**日文单位**且整段文本含假名。中日文同形的 `9月1日` 靠「整段是否有假名」区分——真日文几乎必然含假名，中文没有。
- **覆盖**：日期（1日→ついたち、20日→はつか、14日→じゅうよっか）、月份（4月→しがつ、7月→しちがつ、9月→くがつ）、时刻（4時→よじ、7時→しちじ、9時→くじ、24時→にじゅうよじ）、分钟（1分→いっぷん、30分→さんじゅっぷん）、人数（1人→ひとり、2人→ふたり）、年龄（20歳→はたち），以及個/本/杯/枚/冊/匹/台/回/階/件等量词的音变；其余单位退化为「基数 + 单位基准读音」。
- **基数**：1–99999；组合里 4/7/9 用 よん/なな/きゅう（单独念时用 し/しち/く），百千有 さんびゃく/ろっぴゃく/はっぴゃく/さんぜん/はっせん 音变，万位不省「一」（10000→いちまん）。
- **降级**：`0` 开头多位、复合数字（3.5 / 10:30 / 电话）、超过 99999、以及判不出日文上下文的数字一律保持原样，交给 `ruleFsts` 兜底。

> 只改数字，汉字读音仍由 §2 的日文汉字改写负责（`15時に 会いましょう` → `じゅうごじに 会いましょう`，`会` 由映射表另行处理）。

## 4. 各语言/符号来源

- 中文：pypinyin BOPOMOFO → bopomofo_to_ipa（训练同源），词组表 47k + 单字 41,923；多音字靠词条（行长 háng zhǎng、重庆 chóng qìng）。
- 英文：CMUdict ARPAbet → IPA（重音 ˈ/ˌ），词表 126k + 41 条手工 OOV 补条；运行时文本转小写，词表外多字母词由 App 逐字母兜底。
- 日文：JMdict 常用词 → 假名读音 → pyopenjtalk IPA2；lexicon 里只放**假名**，汉字由 App 改写。
- 数字：英文上下文由 App `convertEnglishNumbers` 预转英文数词；日文上下文由 `rewriteJapaneseNumbers` 转假名数词与日期/时刻/量词读法（见 §3.1）；中文上下文走 ruleFsts（phone→date→number）：电话逐位（1 读幺），年龄/时间按数词（十八岁、八点三十分）。
- 未知内容兜底：中文单字条目、假名单字、英文字母（App 侧把词表外的多字母英文词拆成字母拼读）、日文单字/词干。

## 5. tokens.txt 约定

实际 **69 行、67 个唯一 ID**：`_`=0（blank，`^`/`$` 也映射 0，三行共用 ID 0）、标点 1-7、`N Q` 8-9、23 个拉丁字母 10-32（缺 c/q/r）、IPA 33-54、`⁼ ʰ` 55-56、反引号（U+0060）=57、**ID 58 缺号**、`#`=59 `*`=60 `=`=61、`ˈ`=62 `ˌ`=63、`→`=64 `↓`=65 `↑`=66、`␣`=67。
add_blank=1 → 运行时自动生成 `[0,s0,0,s1,…,sN,0]`。

## 6. 已验证结论

- pth → 单图 ONNX：ORT vs PyTorch 最大误差 3e-5。
- 混合精度可拼接（74.9MB），sherpa 正常加载；预分词路径勿用（piper 双插 blank 导致语速翻倍）。
- 中文：多音字、词边界、数字/日期/电话正常；在 13 条综合样本上 OOV=0（lexicon 覆盖范围外仍会逐字/字母兜底）。
- 日文：今日→きょう、一緒→いっしょ、天気→てんき、私は→わたし、知らない→しらない。
- 英文：126k 词覆盖 + 词表外逐字母兜底；中英混排正常；英文句中的整数经 `convertEnglishNumbers` 预转为英文数词（`3`→three、`12`→twelve，单测覆盖）。
- 数字：中文/日文上下文仍走 ruleFsts；日文数字读法（じゅうごじ 等）是已知边界，排 1.9.0。
- 综合样本：samples/comprehensive/（13 条）。

## 7. 真机验证（已完成，2026-11-15）

1.8.0 已完成 Android 侧集成（sherpa AAR、`SherpaTtsEngine`、动态采样率播放器、模型包管理、第 3 节日文改写规则）；以下真机确认项已于 2026-11-15 全部通过，逐条记录见归档清单 [`../archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](../archive/checklists/2026-11-15-tts-device-test-1.8.0.md)：

1. 真机验证 lexicon 8.9MB + 模型 75MB 的加载时间与内存占用；
2. 真机验证 16KB page size / SIGILL（sherpa AAR 内嵌 ORT 1.28.2）、打断抢占、悬浮窗朗读、删除后重新导入；
3. 旧版 1.7.x 四模块模型覆盖安装后的自动清理（升级后需重新下载 / 导入模型）。
