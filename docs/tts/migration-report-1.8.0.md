# MeaPet 1.8.0 · TTS 迁移 sherpa-onnx 详细报告

> 版本：`1.8.0`（versionCode 14）｜分支 `dev`｜范围 `9cda4ca..fc70902`（17 commits）
> 状态：代码与构建全部完成并通过单测；**真机语音验证待执行**（当前无 adb 设备，见 §9）；整分支 final review 进行中。

---

## 1. 一句话结论

把「4 个 ONNX 模块 + 自写拼音 G2P + 固定 22050Hz 播放器」的 TTS 推理链，整体替换为「sherpa-onnx AAR + 单个 75MB VITS2 图 + 统一 lexicon + App 侧一层日文上下文改写 + ruleFsts」，**保留 Mea 音色与 `TtsManager` 对外 API**：

- 支持 **中 / 日 / 英混排**，数字、日期、电话号码按规则朗读；
- 代码净变化：`app/src` 内 **30 个文件，+1,247 / −1,268 行**（含删除 12 个旧文件、旧资产 1.65MB）；
- 全量单测 **191 个全绿**；debug APK 80.47MB、release APK 61.64MB（较 1.7.2 +7.31MB）；16KB page 对齐校验通过；R8 无 missing-class。

---

## 2. 架构对比

### 旧链路（1.7.x）

```
文本 → ChineseG2p（自写拼音表 + 多音字词表 + 68 符号映射）
     → VitsOnnxEngine × 4 session（enc_p / dp / flow / dec）
     → DurationExpander（attention 展开 / 先验采样，Kotlin 手写）
     → TtsAudioPlayer（硬编码 22050Hz）
```
模型：4 个 ONNX 共 ~73MB，运行时下载；额外依赖 `onnxruntime-android`。

### 新链路（1.8.0）

```
文本
  │  App 侧（Kotlin，无词典/无分词库）
  ├─ ① 拉丁字母串转小写
  └─ ② 日语上下文改写：连续汉字段若紧挨假名 → 用 ja_reading_map 改写为假名
  │  sherpa C++ 内部
  ├─ ③ 按句切分（maxNumSentences=1）
  ├─ ④ ruleFsts：phone.fst → date.fst → number.fst（顺序固定）
  ├─ ⑤ CharacterLexicon 最长匹配（统一 lexicon.txt，jieba=1 触发）
  ├─ ⑥ 符号 → ID（tokens.txt，add_blank=1）
  └─ ⑦ VITS2 单图推理（prior encoder / duration predictor 为 fp32，flow / decoder 为 fp16，混合精度拼接）
  │
  └→ GeneratedAudio（22050Hz，采样率由引擎给出）→ TtsAudioPlayer.play(samples, sampleRate)
```

**关键点**

| 项 | 决策 | 原因 |
|---|---|---|
| 模型打包 | 单图 `model.onnx` 74,864,089 B | 无子模型胶水代码；混合精度拼接数值误差 ~0.0127（桌面验证） |
| 文本前端 | 走 sherpa 文本前端（不预分词） | 预分词会进 piper 路径双插 blank，语速翻倍 |
| 语言处理 | 一个引擎、一个 lexicon，**不按语言切** | 用户明确要求；中文永不进日文分支（需紧挨假名） |
| 数字/日期/电话 | ruleFsts，电话最先 | 顺序反了电话会被读成中文数词（"一百三十八亿…"） |
| 语速 | `speed = 1 / lengthScale`（设置 0.5~2.0） | 与 VITS `length_scale` 语义一致 |
| 采样率 | 跟随 `GeneratedAudio.sampleRate` | 播放器不再写死 22050 |
| ONNX Runtime | 由 sherpa AAR 内嵌 **1.28.2** | 移除 `onnxruntime-android` 依赖，避免两份 `libonnxruntime.so` |

---

## 3. 设计决策（实施中做出的 ruling）

| # | 决策 | 依据 | 若错的代价 |
|---|---|---|---|
| 1 | 不建 git worktree，直接在 `dev` 分支原工作区实施 | 本计划依赖 gitignored 的 `.tools/`（84MB 模型产物）与 `local.properties`（SDK 路径/模型地址），worktree 里都没有 | 改动直接落 `dev`，靠逐任务 commit 回退 |
| 2 | ORT 在 T1~T8 期间降为 `compileOnly` 过渡 | 旧 `VitsOnnxEngine` 到 T9 才删，直接删依赖会编译断；保留 `implementation` 又会因两份同名 `.so` 让 `mergeDebugNativeLibs` 失败 | 中间态旧 TTS 运行时会 `NoClassDefFoundError`（本已不可用，不发布） |
| 3 | 旧版兼容逻辑独立成 `TtsLegacyMigration.kt` | 用户要求便于维护、后续整文件删除 | 多一个文件与两行 `init` 调用 |
| 4 | 不做 ORT 降级方案 | ORT 1.23.2 的 KleidiAI SME SIGILL（历史 commit `db097864`）已在 1.24.3 修复，AAR 用 1.28.2 | 真出现 SIGILL 只能换 AAR/改编译，需另行决策 |
| 5 | T6（播放器签名）与 T7（TtsManager）合并提交 | T6 改签名后旧调用点使中间态无法编译 | 单次提交 diff 稍大 |
| 6 | `releaseEngine()` 改为 `suspend` 并在同一 `synthMutex` 内 release | `generateWithConfig` 阻塞且不可取消，release 与在途 synthesize 并发访问已释放 native 对象 = use-after-free | 调用点已都在协程内，无额外改动 |
| 7 | 子代理无 model 参数，统一继承会话模型 | 本 harness 的 `subagent` 工具不支持 model/provider 覆盖 | 成本/时延略高，质量不受影响 |

---

## 4. 模型包（运行时分发，不打进 APK）

合计 **83,992,110 B ≈ 84.0MB**；本地 zip **71,137,348 B**。

| 文件 | 大小（B） | sha256 |
|---|---|---|
| model.onnx | 74,864,089 | `2d4f52c6…b026f5` |
| lexicon.txt | 8,915,316 | `3f46c6ed…a582fa` |
| tokens.txt | 439 | `a1511ff2…68120` |
| fsts/phone.fst | 88,630 | `1ac2b6fa…62f8d7` |
| fsts/date.fst | 59,154 | `eb8aa079…0e718` |
| fsts/number.fst | 64,482 | `743f4021…8bd5bd` |

（完整 sha256 与生成脚本见 `docs/tts/model-package.md`；打包脚本 `.tools/tts-spike/scripts/build_app_model_package.py`。）

**两种分发方式**

1. **在线下载**（设置页「下载模型」）：6 个文件以**平铺文件名**上传到 `app.ttsModelBaseUrl`（当前为 GitHub Release `tts-resource-pack`）；App 侧 `buildDownloadFiles()` 把远端 basename 映射到本地 `filesDir/tts_model/`（保留 `fsts/` 子目录），按精确字节数校验。
2. **本地导入**（设置页「从本地导入」）：`dist/tts-model-v1.zip`，支持平铺或单层顶层目录，导入前清空旧目录。

> 上传步骤与自检命令见 `docs/tts/model-package.md`；**旧 release 里的 4 个旧 onnx 不要删**（旧版 App 仍在使用）。

---

## 5. 语言 / 数字处理细节

### 日语上下文改写（`TtsTextNormalizer`）

与桌面参考实现 `.tools/tts-spike/scripts/test_final_design.py::rewrite` **逐行为对齐**：

1. 扫描连续汉字段；仅当该段**紧挨假名**（前或后）才视为日语片段；
2. 片段内用 `ja_reading_map.word` 最长匹配（≤8 字）；
3. 未命中单字：后跟平假名且有词干 → 词干；否则整字读音；否则保留原字；
4. 非日语片段完全不动 → 中文永不被日文词条抢占（如「重庆的银行行长」保持中文）。

映射资产：`assets/tts/ja_reading_map.json`（514,556 B；词 16,976 / 单字 1,071 / 词干 1,077）。

### 数字 / 日期 / 电话

`ruleFsts = phone.fst, date.fst, number.fst`（顺序固定）：

- 电话逐位朗读（1 → 幺）：`13800138000` → 幺三八零零一三八零零零；
- 年龄/时间按数词：18 岁 → 十八岁；8:30 → 八点三十分；
- 日期：`2026年10月31日` → 二零二六年十月三十一日。

### 中文声调（已知上限）

Mea 模型是**日语语料微调**，中文声调符号 `→↓↑` 在训练里对应日语音调，因此个别字（如「今」）声调不准。这属于模型能力上限，词表/G2P 无法修正；与旧版听感一致，需换中文预训练模型（会换音色）才能改善。

---

## 6. 代码结构变化

**新增（5 个生产文件 + 8 个测试类 + 3 份文档 + 1 份资产）**

```
tts/text/JaReadingMap.kt          映射数据结构 + DTO + assets 加载器
tts/text/TtsTextNormalizer.kt     拉丁小写 + 日语上下文改写
tts/engine/TtsEngine.kt           合成引擎接口（可单测替换）
tts/engine/SherpaTtsEngine.kt     sherpa 配置/懒加载/合成/释放
tts/model/TtsLegacyMigration.kt   一次性旧版兼容清理（整文件可删）
assets/tts/ja_reading_map.json    日语改写映射（515KB）
docs/tts/{architecture,model-package,device-test-checklist}.md
```

**测试新增 8 个类**：`TtsTextNormalizerTest`、`SherpaDependencyTest`、`TtsModelPackageTest`、`TtsLegacyMigrationTest`、`TtsModelManagerTest`、`TtsManagerTest`、`TtsAudioPlayerTest`、`SherpaTtsEngineConfigTest`。

**修改**：`TtsModelManager`（新包清单/严格字节校验/平铺下载/嵌套 zip 导入）、`TtsManager`（归一化→互斥合成→动态采样率播放，新增 `speakPreview`、`releaseEngine` 改 suspend）、`TtsAudioPlayer`（采样率参数化）、`AppContainer`（装配新引擎/归一化器）、`VoiceSettings`（文案 + Debug 试听）、`SettingsViewModel`（`previewTts`）、构建三件套（JitPack 依赖 / proguard keep / catalog）、`AboutSettings`（副标题，用户单独请求）。

**删除（12 个文件 + 1.65MB 资产）**

`VitsOnnxEngine.kt`、`TtsSynthesizer.kt`、`DurationExpander.kt`、`tts/g2p/`（5 个）、`ChineseG2pTest`、`G2pProcessorTest`、`assets/pinyin/{zh_pinyin,zh_phrases}.txt`；同时清理 3 个 `TODO(T9)` 过渡锚点（compileOnly ORT、`ensureNativeLoaded`、proguard `-dontwarn ai.onnxruntime.**`）。

---

## 7. 验证记录

| 项 | 结果 |
|---|---|
| 单测 | **191 tests / 27 suites / 0 failures / 0 errors / 0 skipped**（迁移前 166） |
| 测试数演进 | 166 → 173（T3）→ 179（T4）→ 186（T4 fix）→ 188（T5）→ 201（T7）→ 191（T9 删 10 个 G2P 测试） |
| `assembleDebug` | 成功；APK **84,380,589 B（80.47MB）** |
| `assembleRelease` | 成功（R8）；unsigned APK **64,637,707 B（61.64MB）**，较 1.7.2 +7,670,029 B（+7.31MB） |
| 原生库 | 仅 `arm64-v8a` / `armeabi-v7a`；每 ABI 4 个 TTS `.so`（`libonnxruntime` + `libsherpa-onnx-{c-api,cxx-api,jni}`），无 x86 |
| 16KB page | `zipalign -c -P 16 -v 4` → **Verification successful**；AAR 内 `.so` PT_LOAD `p_align=0x4000` |
| R8 | release 日志 0 `Missing class` / 0 `ai.onnxruntime` / 0 `com.k2fsa` 警告（仅 umeng 既有 stackmap 噪声） |
| 资产 | APK 有 `assets/tts/ja_reading_map.json`，无 `assets/pinyin/*` |
| 模型包 | 6 文件逐字节与 `dist/manifest.json` 一致；commit 内资产 blob 与源文件 hash 完全相同 |
| 桌面 spike（迁移前） | pth→单图 ONNX：ORT vs PyTorch 最大误差 3e-5；sherpa 加载/合成正常；13 条综合样本试听通过 |
| AAR 依赖 | `debugRuntimeClasspath` 仅 `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`，无 onnxruntime-android |
| **真机** | **未执行**（无 adb 设备），已交付 `docs/tts/device-test-checklist.md`（21 条用例） |

---

## 8. 升级与部署

**老用户升级（1.7.x → 1.8.0）**

1. 覆盖安装后，首次进入设置页/悬浮窗触发 `TtsModelManager.init` → `TtsLegacyMigration.migrate()` 检测到旧 4 文件模型 → **整目录删除**；
2. 模型状态回到「未下载」，设置页提示 **约 84MB**；
3. 用户重新下载或本地 zip 导入（下载地址不变，`local.properties` 无需改）；
4. 旧版已开语音开关者：开关仍在但 `state != Ready` 时 `speak()` 静默跳过（不崩、不串音）。

**发布前需人工完成**

1. 按 `docs/tts/model-package.md` 把 6 个文件上传到 GitHub Release（平铺文件名，保留旧文件）；或用 `dist/tts-model-v1.zip` 作为本地导入分发；
2. 真机执行 `docs/tts/device-test-checklist.md`（重点：SIGILL、首载耗时、内存、打断、悬浮窗、删除重导入、升级迁移）；
3. 配置签名（`keystore.properties` / 环境变量）后出正式 release APK。

**回滚**：`git revert fc70902` 及此前 TTS 相关提交，或回到 `1.7.2` tag；模型侧旧 release 资源未删除，回滚后旧版 App 仍可正常下载。

---

## 9. 已知限制 / 未完成项

**模型能力（不可通过工程手段修复）**

- 中文声调准确度受日语语料限制（「今」等字声调不稳），与旧版一致；
- 中日混排边界个例（如 `ゲーム + let's go`）听感一般，可未来按标点分句合成改善；
- 纯拉丁 OOV 仍靠字母拼读兜底，生僻专有名词可能不理想。

**待真机验证（阻塞发布，不阻塞代码）**

- ARM64 低端骁龙设备 SIGILL（历史坑，1.28.2 应已修复，需实测确认）；
- 首载耗时/内存峰值、AudioTrack 播放质量、打断与悬浮窗互斥；
- `run-as cp` 推送模型包在部分 ROM 上不可用 → 用 zip 导入兜底。

**代码 polish（已记录，不阻塞合并）**

- `releaseEngine()` 的 native teardown 仍在调用线程（Main）执行，若真机卡顿可改 `withContext(IO)`；
- 播放器未校验 `sampleRate > 0`；`onPlaybackComplete` 无代际令牌（pre-existing）；异常日志打印全文；
- 若干测试为 `Thread.sleep` 式时序断言（TtsManagerTest），建议后续换 latch；
- 多音字词表仍缺「今天」类非多音词条（对听感无影响，仅词边界）。

---

## 10. 提交记录（9cda4ca..fc70902）

| 提交 | 说明 |
|---|---|
| `222d736` | T1 接入 sherpa AAR，移除 onnxruntime-android 依赖 |
| `a631f92` | T1 fix：恢复 `ai.onnxruntime` dontwarn 过渡规则 |
| `30bb5fa` | T2 打模型包 + 日语映射资产 + 文档 |
| `3cacc58` | T2 fix：统一 lexicon 文件名、校准 tokens 字母数 |
| `d1216f0` | T3 文本归一化与日语上下文改写 |
| `80803a4` | T4 模型管理切换到 sherpa 模型包 |
| `7e95d9c` | T4 fix：修复导入失败状态卡死 + 严格校验测试 |
| `da0e12d` | T5 sherpa 合成引擎与配置构造 |
| `89330d0` | T6+7 播放器动态采样率 + TtsManager/AppContainer |
| `31b66d6` | T8 设置页文案 + Debug 试听 |
| `980d157` | T9 移除旧四模块推理 / 自写 G2P / 拼音资产 |
| `fc70902` | T10+11 release 1.8.0（版本号/CHANGELOG/真机清单） |
| 另 | 计划文档、RESUME/ledger（`.superpowers/sdd/`，gitignored）、About 页诗句（`aa7513a`） |

---

## 11. 最终审查结论

- **整分支 final review（`9cda4ca..fc70902`）**：集成层面（数据流、引擎/播放器、生命周期、并发、R8/ABI/16KB、模型包校验）全部干净；提出 2 个 Important —— ①模型目录替换期间 TTS 可能重建引擎（状态序缺陷）、②README 仍描述旧 4-ONNX 包。
- **修复**：`cf37b7b`（`TtsModelManager.markMutating()` + 导入/删除/下载流程先失效 `speak` 门禁再 `releaseEngine()`，`synthMutex` 内复查 Ready；193 tests）+ `7e35e0f`（README/architecture/checklist/计划文档同步）。
- **scoped re-review**：4 项全部 **ADDRESSED**，无新破坏，**Verdict: approved**。
- **最终结论：代码 SHIP-READY**。发布前仅剩两项仓库外动作：上传 6 个模型文件（平铺名）到 `app.ttsModelBaseUrl`，以及按 `docs/tts/device-test-checklist.md` 跑真机验证（SIGILL/音频/升级迁移）。
- Deferred triage：`must-fix-before-merge = none`；历史工具的 minor 全部 acceptable。

*本报告由实施过程逐任务记录汇总。*
