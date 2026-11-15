# TTS 真机验收清单（1.8.0 · sherpa-onnx）

> **状态：执行中（自动化部分见 §A，2026-11-15）；试听 / 交互项由维护者逐条勾选，未勾完即 v1.8.0 发布阻塞。** 执行完请回填 §3 并附机型/SoC/Android 版本/`ro.product.cpu.abilist`。
> 上游：发布收尾项见 [`../TODO.md`](../TODO.md) 待办 0；迁移报告见 [`migration-report-1.8.0.md`](migration-report-1.8.0.md)。

> 本版（1.8.0 / versionCode 14）语音合成已迁移到 sherpa-onnx，模型包、采样率、播放链路全部更换。
> 构建期校验（单测 / R8 / 16KB 对齐 / so 清单）已在桌面完成，**本清单是需要真机才能确认的部分**，请在 Android 设备上逐条执行并勾选。
> 记录结论时请附：机型、SoC、Android 版本、`ro.product.cpu.abilist`，以及异常条目的 `logcat` 片段。

## A. 自动化执行记录（2026-11-15）

在 PC + 真机上**不依赖人耳听音**的部分已执行，结果如下；带 ⚠️ 的项需要解锁屏幕后人工/半自动完成。

设备：Xiaomi 22041216UC（Xagapro MT6895），Android 14（SDK 34），`ro.product.cpu.abilist=arm64-v8a,armeabi-v7a`，`PAGESIZE=4096`（**非** 16KB 设备），已 root（Magisk），执行时设备锁屏。

| 项目 | 结果 |
|---|---|
| debug APK 构建 | ✅ `BUILD SUCCESSFUL`；`app\build\outputs\apk\debug\app-debug.apk` 84,691,804 B |
| APK 元数据 | ✅ `versionName=1.8.0`、`versionCode=14`、minSdk 26 / targetSdk 36、ABI `arm64-v8a` + `armeabi-v7a`（两套 sherpa/ORT `.so` 都在） |
| 16KB 对齐 | ✅ `zipalign -c -P 16 -v 4` → `Verification successful` |
| APK 内 assets | ✅ `assets/tts/ja_reading_map.json` 514,556 B sha256 `5d92d212…`、`assets/tts/en_words.bloom` 524,288 B sha256 `5057e286…`，与 `tools/tts/artifacts.json` **bit-identical** |
| §0.1 安装 | ✅ `adb install -r` Success（覆盖安装，数据保留）；`dumpsys package` = 1.8.0 / 14、`DEBUGGABLE` |
| §0.2 模型文件 | ✅ 设备 `files/tts_model` 恰好 6 个文件、大小与 §0.2 表格完全一致；**sha256 与 `artifacts.json` 发布值逐字节一致**：`model.onnx`=2d4f52c6…、`lexicon.txt`=**97e2b4fb…**（发布型行序，非本地 dist 的 261ee0a7）、`tokens.txt`=a1511ff2…、`fsts/{phone,date,number}.fst` 全部一致 |
| 冷启动 | ✅ `am start -W` WaitTime=3046ms，进程存活（例：pid 8020）；`logcat -b crash` 为空（无 `Fatal signal`） |
| §2.3 内存基线 | ✅ 空闲（模型文件就位、未触发合成）：TOTAL PSS **165,638 KB**（Java 20,852 / Native 27,420），TOTAL RSS 266,444 KB |

> ⚠️ **待解锁后继续**：§1 全部 21 条试听（需人耳判定读法）、§2.1 首次合成 `load=` 与二次合成、§2.2 连续 10 条 SIGILL/崩溃、§2.3 合成后 PSS、§2.4 打断、§2.5 悬浮窗、§2.6 删除重导入、§2.7 1.7.x 升级迁移——这些需要解锁屏幕且（试听类）需要人耳。
>
> 设备最后更新 App 于 2026-11-15 10:47（本次 `adb install -r` 之前已有 1.8.0 debug 包），因此 §0.1「冷启动显示未下载」两项已无新鲜安装状态可验（存量模型就绪），由人工按需重装后确认。


---

## 0. 前置：安装与模型

### 0.1 安装 APK

```powershell
# 仓库根目录执行
adb install -r app\build\outputs\apk\debug\app-debug.apk
# 或 release 构建（未签名时先自行签名）
# adb install -r app\build\outputs\apk\release\app-release-unsigned.apk
```

- [y] 安装成功，`adb shell dumpsys package com.meapet.mobile | Select-String versionName` 显示 `1.8.0`
- [y] 冷启动后进入「设置 → 语音」，模型卡显示「语音模型未下载」/「未配置模型下载地址，可从本地 zip 导入」（旧版残留已清理）

### 0.2 方式 A：推送 6 个文件到应用沙盒

模型包共 6 个文件（约 84MB），`fsts/` 是**必须**的子目录：

| 目标相对路径 | 大小（B） |
|---|---|
| `model.onnx` | 74,864,089 |
| `tokens.txt` | 439 |
| `lexicon.txt` | 8,916,530 |
| `fsts/phone.fst` | 88,630 |
| `fsts/date.fst` | 59,154 |
| `fsts/number.fst` | 64,482 |

```powershell
$PKG   = "com.meapet.mobile"
$MODEL = "E:\Documents\Projects\Android\MeaPet\tools\tts\dist"   # 含 model.onnx / lexicon.txt / tokens.txt / fsts\（本地 spike 存档 .tools\tts-spike\dist 同结构，可互换）

adb shell am force-stop $PKG

# 1) 先推到 /data/local/tmp（shell 可写，run-as 可读）
adb shell mkdir -p /data/local/tmp/tts_model/fsts
adb push $MODEL\model.onnx      /data/local/tmp/tts_model/model.onnx
adb push $MODEL\lexicon.txt     /data/local/tmp/tts_model/lexicon.txt
adb push $MODEL\tokens.txt      /data/local/tmp/tts_model/tokens.txt
adb push $MODEL\fsts\phone.fst  /data/local/tmp/tts_model/fsts/phone.fst
adb push $MODEL\fsts\date.fst   /data/local/tmp/tts_model/fsts/date.fst
adb push $MODEL\fsts\number.fst /data/local/tmp/tts_model/fsts/number.fst

# 2) 再搬进应用私有目录 files/tts_model/（注意 fsts/ 子目录，6 条 cp 缺一不可）
adb shell run-as $PKG mkdir -p files/tts_model/fsts
adb shell "run-as $PKG cp /data/local/tmp/tts_model/model.onnx      files/tts_model/model.onnx"
adb shell "run-as $PKG cp /data/local/tmp/tts_model/lexicon.txt     files/tts_model/lexicon.txt"
adb shell "run-as $PKG cp /data/local/tmp/tts_model/tokens.txt      files/tts_model/tokens.txt"
adb shell "run-as $PKG cp /data/local/tmp/tts_model/fsts/phone.fst  files/tts_model/fsts/phone.fst"
adb shell "run-as $PKG cp /data/local/tmp/tts_model/fsts/date.fst   files/tts_model/fsts/date.fst"
adb shell "run-as $PKG cp /data/local/tmp/tts_model/fsts/number.fst files/tts_model/fsts/number.fst"

# 3) 校验：应看到 6 个文件，且 fsts 下 3 个 .fst，大小与上表一致
adb shell run-as $PKG ls -lR files/tts_model
```

- [y] `ls -lR files/tts_model` 恰为 6 个文件，`fsts/` 下 3 个 `.fst`（2026-11-15 真机实测，sha256 亦与发布值一致，见 §A）
- [y] 重新启动 App → 设置页显示「语音模型已就绪」，发声开关可点（不再置灰）

> App 启动时（`TtsModelManager.init`）按文件清单 + 精确大小判定就绪，手工推送后**必须 force-stop 再启动**才会重新扫描；推送过程中 App 在跑会读到半套模型。

### 0.3 方式 B：本地 zip 导入

- [y] 把 `dist/tts-model-v1.zip`（约 71MB，见 `docs/tts/model-package.md`）拷到设备
- [y] 设置 → 语音 →「从本地导入」选中该 zip → 卡片显示「正在导入语音模型…」→ 完成后「语音模型已就绪」
- [y] 导入后 `adb shell run-as com.meapet.mobile ls -lR files/tts_model` 同样为 6 文件（`build_package.py` 产出的 zip 为根级平铺；若 zip 带 `tts-model-v1/` 外层目录，App 也会自动剥离，两种都支持）

---

## 1. 逐条试听用例

入口：Debug 构建「设置 → 语音 → 试听文本（Debug）」，或直接在主界面/悬浮窗发一条让模型复述该文本的消息。
预期读法按 [`architecture.md`](architecture.md) 的规则给出（lexicon 命中 → ruleFsts → 字符兜底）。**每条都要能完整念完、不吞句、不卡死。**

### 1.1 中文

| # | 文本 | 预期读法 | 结果    |
|---|---|---|-------|
| 1 | 他去银行了 | yín háng（词条命中，不读 xíng） | - [y] |
| 2 | 他是银行行长 | yín háng háng zhǎng（多音字靠词条，不读 cháng） | - [y] |
| 3 | 我来自重庆 | chóng qìng（不读 zhòng） | - [y] |
| 4 | 我不喜欢音乐 | yīn yuè（不读 lè） | - [y] |
| 5 | 我今年 18 岁 | shí bā suì（数词，不逐位） | - [y] |
| 6 | 今天是 2026 年 11 月 1 日 | èr líng èr liù nián shí yī yuè yī rì（date.fst） | - [y] |
| 7 | 我的电话是 13800138000 | 幺 三 八 零 零 幺 三 八 零 零 零（phone.fst 逐位，首/中位 1 读幺） | - [y] |
| 8 | 现在 8 点 30 分 | bā diǎn sān shí fēn（时间按数词，不逐位） | - [y] |
| 9 | 请把 API 文档发给张三 | A-P-I 逐字母拼读，其余中文连续 | - [y] |
| 10 | 今天用 Kotlin 写了个 App | Kotlin / App 按英文单词读（拉丁转小写后走 lexicon），前后中文无缝 | - [y] |

### 1.2 日文（汉字连段改写为假名）

| # | 文本 | 预期读法 | 结果    |
|---|---|---|-------|
| 11 | 今日はいい天気ですね | きょうはいいてんきですね（今日 / 天気 词条命中） | - [y] |
| 12 | 一緒に行こう | いっしょにいこう（一緒 词条；行 用词干 い） | - [y] |
| 13 | 知らない | しらない（知 用词干 し） | - [y] |
| 14 | 私は猫が好きです | わたしはねこがすきです（私 整字 わたし） | - [y] |
| 15 | 天気がいいから散歩しよう | てんきがいいからさんぽしよう（散歩 词条；词条缺失时逐字兜底） | - [y] |

> 已知边界（**不算失败**）：纯汉字片段若不紧挨假名（如句末「天気。」后无假名）不会走日文改写，会按中文读；`ja_reading_map` 词条最长匹配上限 8 字，超长复合词会退化为更短词条或单字兜底；**日文句子里的阿拉伯数字目前按中文数词读**（ruleFsts 是中文数字前端），#19「15 時」、#21「9 月 1 日」的数字部分预期为中文读音，假名数词（じゅうごじ / くがつついたち）列为 1.9.0 目标（见 `TODO.md` 待办 D）。

### 1.3 英文

| # | 文本 | 预期读法 | 结果    |
|---|---|---|-------|
| 16 | Hello, nice to meet you. | 自然英文连读，重音正确，句末不漏 | - [y] |
| 17 | The quick brown fox jumps over the lazy dog, gathering all the letters of the alphabet. | 长句不吞词、不断流、不重复；整句时长明显长于第 16 条 | - [y] |
| 18 | I have 3 apples and 12 oranges. | 数字按英文数词（three / twelve），不逐位 | - [y] |

> 2026-11-15 修复：英文句中的整数由 App 归一化层预转英文数词（`3`→three、`12`→twelve，见 `architecture.md` §3），不再被中文 ruleFsts 读成 sān/shí èr。`3.5`、`10:30`、电话号码等复合/超长数字仍走 ruleFsts（不受影响）。

### 1.4 混排

| # | 文本 | 预期读法 | 结果 |
|---|---|---|---|
| 19 | 今日の meeting は 15 時からです | きょうの / meeting 英文 / じゅうごじ / からです，三种语言同一音色无缝 | - [ ] |
| 20 | 我用 Python 写了 3 个脚本，明天再 debug | 中文 + Python/debug 英文 + 数字 sān，切换处无爆音/空拍 | - [ ] |
| 21 | 明日は 9 月 1 日、Tokyo は晴れ | あした（词条）＋ くがつついたち（date.fst）＋ Tokyo 英文 + はれ | - [ ] |

### 1.5 其他

- [ ] 语速滑杆 0.5x / 1.0x / 2.0x 三档都试听同一句，语速变化明显且**不变调**（采样率跟随引擎输出，1.0x 时无变速感）
- [ ] 超长回复（>200 字）：日志出现 `文本超长（N 字），截断为前 200 字朗读`，文字显示完整、朗读只念前 200 字且不崩
- [ ] 纯标点/空白回复（如「……」）不发声、不报错（日志 `归一化后为空` 或 `合成为空数组`）
- [ ] Emoji / 颜文字混入句子不崩，读作跳过或兜底，不吞掉后续整句

---

## 2. 专项验证

### 2.1 首次合成与加载耗时

```powershell
adb logcat -c
adb logcat -v time | Select-String "SherpaTtsEngine|TtsManager|TtsAudioPlayer|TtsModelManager|LegacyMigration|LegacyTts|Fatal signal|SIGILL|OutOfMemory|OOM"
```

- [ ] 首次合成日志出现 `OfflineTts 就绪：sampleRate=22050 speakers=1 load=NNNms`
- [ ] 记录 `load=` 毫秒数：______ ms（≤2000ms 视为正常；>5000ms 记录机型/Soc 与存储类型）
- [ ] 第二次合成不再出现「创建 OfflineTts」（引擎懒加载只创建一次），日志为 `合成完成：文字=N speed=… 样本=… sr=22050`
- [ ] 首次合成期间 UI 不卡死（合成在 IO 线程：主界面动画仍流畅、可返回）

### 2.2 SIGILL / 原生崩溃

1.7.1 曾因 ORT 1.23.2 KleidiAI SME MatMul 在部分骁龙 SoC 上触发 `Fatal signal 4 (SIGILL)`；sherpa AAR 内嵌 ORT 1.28.2 已含修复，仍需真机确认。

2026-11-15 还实测到一次 **CheckJNI SIGABRT**（首次带流式回调的构建）：`JNI DETECTED ERROR ... JNI NewFloatArray called with pending exception java.lang.NoSuchMethodError: no non-static method "...invoke([F)Ljava/lang/Integer;"`——Kotlin lambda 脱糖后没有 sherpa JNI 查找的那个签名。已改为 Java 适配器 `TtsStreamCallback`，`TtsStreamCallbackTest` 用反射守护签名；复验时 crash buffer 里不得再出现该 Abort message。

```powershell
adb shell getprop ro.board.platform
adb shell getprop ro.soc.model
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist64
adb shell getconf PAGESIZE          # 16384 = 16KB page 设备，重点覆盖
adb logcat -b crash -d | Select-String "Fatal|signal|DEBUG"
```

- [y] `ro.board.platform` = `mt6895`，`ro.soc.model` = `MT6895Z/TCZA`
- [y] `ro.product.cpu.abilist` = `arm64-v8a,armeabi-v7a`（与 APK 内 ABI 一致：两套 sherpa/ORT `.so` 都在）
- [y] `PAGESIZE` = `4096`（**非** 16KB 设备；16KB 专项覆盖需另找设备）
- [ ] 连续合成 10 条（含长句、数字、混排）后 `adb logcat -b crash -d` **无** `Fatal signal 4 (SIGILL)`、无 `Fatal signal 11 (SIGSEGV)`（冷启动后已确认 crash buffer 为空，合成后需复验）
- [ ] 应用未出现闪退/重启，进程存活（`adb shell pidof com.meapet.mobile` 前后一致）

### 2.3 内存

- [y] 合成前 `adb shell dumpsys meminfo com.meapet.mobile | Select-String "TOTAL PSS"` 记录：**165,638 KB**（空闲、模型文件就位、未触发合成；Java 20,852 / Native 27,420，TOTAL RSS 266,444 KB）
- [ ] 连续 10 条试听后 `logcat` 无 `OutOfMemoryError` / `OutOfMemory`，进程未被 LMK 杀死
- [ ] 模型加载后 PSS 增量在合理范围（模型 75MB + lexicon 8.5MB，增量通常 100~250MB）；记录：______
- [ ] 设置页「删除模型」后 PSS 明显回落（引擎已 release，native 内存释放）

### 2.4 打断与抢占

- [ ] 长回复播放中再发一条新消息：旧朗读**立即停止**，新回复从头读完整（日志 `合成被取消` / `丢弃本段`）
- [ ] 连点喇叭（会话静音）两次：静音时立即停声，恢复后下一条正常朗读
- [ ] 播放中进入设置页删除模型：不崩溃、不 native use-after-free（`releaseEngine` 与合成同一把 Mutex），删除后播放停止
- [ ] 主界面与悬浮窗几乎同时收到回复：两路串行合成，不出现两段声音重叠

### 2.5 悬浮窗朗读

- [ ] 打开悬浮窗 → 菜单开启「悬浮窗语音」→ 悬浮窗内发一条消息，回复朗读正常（不走主界面开关）
- [ ] 播放中，主界面与悬浮窗两个入口的喇叭图标状态**始终一致**（`TtsManager.isPlaying` 是全局唯一共享流：悬浮窗朗读时主界面图标同时点亮，停止后同时熄灭；不存在「各自独立」的图标状态）
- [ ] 悬浮窗与主界面同时开关语音，各自设置互不影响

### 2.6 删除模型 → 重新导入

- [ ] 设置 → 语音 →「删除模型」→ 卡片回到「语音模型未下载」，发声开关置灰
- [ ] 删除后 `adb shell run-as com.meapet.mobile ls files/` 中无 `tts_model` 残留
- [ ] 重新「从本地导入」或下载 → 就绪 → 试听正常（引擎按新模型重建，`OfflineTts 就绪` 再次出现）
- [ ] 下载/导入中途强杀 App 再进：状态为未就绪（不因半套文件误判就绪），`.part` 临时文件不残留就绪判定

### 2.7 旧版 4 文件模型覆盖安装（升级路径）

1. 先安装 1.7.2（versionCode 13）并下载好旧版 4 模块模型（`files/tts_model/{enc_p,dp,flow,dec}.onnx` + `lib/<abi>/libonnxruntime.so`）
2. `adb install -r app\build\outputs\apk\debug\app-debug.apk` 覆盖安装 1.8.0
3. 启动后检查

- [ ] `adb shell run-as com.meapet.mobile ls -l files/tts_model` 无 `enc_p.onnx` / `dp.onnx` / `flow.onnx` / `dec.onnx`，也无 `lib/` 目录
- [ ] `logcat` 出现 `LegacyTtsModelCleanup: 检测到旧版 4 模块模型，清理后需重新下载 sherpa 模型包`，以及总入口 `LegacyMigration: 旧版 TTS 残留已清理，两个语音开关已关闭，需重新下载模型`
- [ ] **升级后两个语音开关（主界面朗读 / 悬浮窗朗读）均为关闭**（迁移命中时由 `LegacyTtsSwitchReset` 一并置 false，不再沿用旧开关值）
- [ ] 设置页语音卡片为「语音模型未下载」，不显示已就绪、不因缺模型崩溃
- [ ] 重新按 0.2/0.3 准备 6 文件模型后，功能恢复；手动重开两个语音开关后朗读正常
- [ ] 覆盖安装后原有设置（发声开关、语速、主题）保留，`versionName=1.8.0`、`versionCode=14`

---

### 2.8 逐句流式播放（首块 / 句间 / 打断）

| 检查项 | 通过标准 | 结果 |
|---|---|---|
| 首块延迟 | Debug 试听输入 3 句以上文本（如「你好喵。今天天气真好。我们出去散步吧。」），logcat 出现 `TtsManager: 首块到达（…ms），开始播放`，且明显早于 `流式合成完成` | - [ ] |
| 分块与收尾 | `SherpaTtsEngine: 流式合成…分块=N`（N≈句数）、`TtsAudioPlayer: 流式段 gen=…: marker 到达（真实播完）`，喇叭图标按时熄灭 | - [ ] |
| 句间停顿 | 句子之间无异常长停顿（RTF≈1.1 允许轻微停顿），整段可听懂 | - [ ] |
| 打断 | 播放中点静音 / 发新消息：旧话立即停，日志出现 `流式中止`（或 `未收到结束标记即退出`），新段正常播放 | - [ ] |
| 中止不白算 | 打断后旧句日志 `流式合成中止：…分块=k` 中 k < 总句数 | - [ ] |
## 3. 结论

- [ ] 全部用例通过，可发布
- [ ] 有失败项（在下方记录：条目号 / 现象 / `logcat` 片段 / 机型）

```
设备：____________  SoC：____________  Android：____________  PAGESIZE：____________
首次加载 load=______ms   连续 10 条：通过 / 失败
备注：

```

> 参考：[`architecture.md`](architecture.md)（改写规则与边界）、[`model-package.md`](model-package.md)（模型包清单与 sha256）。
