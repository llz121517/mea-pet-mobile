# sherpa-onnx TTS Android 集成实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 App 现有「4 个 ONNX + 自写 G2P + 固定 22050Hz 播放器」的 VITS 推理链，替换为「sherpa-onnx AAR + 单图 model.onnx + 统一 lexicon + App 侧一层日文上下文改写 + ruleFsts」，保留 Mea 音色与 `TtsManager` 对外 API。

**Architecture:** 文本 → `TtsTextNormalizer`（拉丁小写 + 日语汉字连段改写假名）→ `SherpaTtsEngine.synthesize()`（sherpa C++ 内部：句切分 → phone/date/number FST → CharacterLexicon 最长匹配 → tokens → VITS 单图推理）→ `GeneratedAudio`（22050Hz，采样率取自引擎）→ `TtsAudioPlayer.play(samples, sampleRate)`。模型包（model.onnx + tokens.txt + lexicon.txt + fsts/*.fst，共 ~84MB）仍走运行时下载 / 本地 zip 导入；`ja_reading_map.json`（~515KB）随 APK 资产分发。

**Tech Stack:** Kotlin 2.2.10 / AGP 9.3 / minSdk 26 / targetSdk 36；sherpa-onnx AAR `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`（JitPack，含 libsherpa-onnx-jni.so + libonnxruntime.so，4 ABI）；JUnit4 + mockito-kotlin 5.4（JVM 单测）。

**Spec:** `.tools/tts-spike/G2P-ARCHITECTURE.md`（本地 spike 目录，gitignored；Task 2 会把它复制成仓库内 `docs/tts/architecture.md`）+ `.tools/tts-spike/scripts/test_final_design.py`（App 侧改写规则的**权威参考实现**，Kotlin 移植必须逐行对齐）。执行者若不熟悉背景，先读这两份。

## Global Constraints

- **不按语言切引擎**：一个 `OfflineTts` 实例、一个统一 lexicon；语言判定只通过上下文改写规则体现（紧挨假名的汉字连段才按日语读）。
- **模型包只放设备文件系统**：`OfflineTts(config = config)` 不能传非空 `AssetManager`（传了就是 `newFromAsset` 资产模式，下载到 `filesDir` 的绝对路径会失效）。JNI 符号已确认：`newFromFile` / `newFromAsset`。
- **ruleFsts 顺序固定**：`fsts/phone.fst → fsts/date.fst → fsts/number.fst`（顺序错电话会被读成中文数词）。
- **语速换算**：`speed = 1.0f / lengthScale`，`lengthScale` 来自设置项 `TTS_LENGTH_SCALE`（0.5~2.0，1.0 原速）。
- **采样率不写死**：一律用 `GeneratedAudio.sampleRate`（当前模型 22050）。
- 合成参数：`noiseScale = 0.55f`、`noiseScaleW = 0.7f`、`maxNumSentences = 1`、`numThreads = 2`、`provider = "cpu"`、`debug = false`。
- 单次朗读文本上限 200 字（保留现有 `MAX_SYNTH_CHARS`，防 OOM）。
- **`TtsManager` 对外 API 不变**：`speak(ChatMessage, Source)` / `stop()` / `release()` / `releaseEngine()` / `isPlaying` / `sessionMuted` / `toggleSessionMute()` / `Source.MAIN|OVERLAY` / 静态 `onPlaybackStart`。ChatViewModel、FloatingLive2dService、OverlayMenu、Live2dDelegate 均不改。
- 旧依赖 `com.microsoft.onnxruntime:onnxruntime-android` 必须移除（sherpa AAR 已内嵌 `libonnxruntime.so`，双份会冲突）。
- R8 必须保留 `com.k2fsa.sherpa.onnx.**`（JNI 按类名/方法名注册，AAR 自带 proguard.txt 为空）。
- 16KB page size：已核对 AAR 内 `arm64-v8a/libsherpa-onnx-jni.so`、`libonnxruntime.so` 的 PT_LOAD `p_align = 0x4000`，满足。
- 不新增分词/语言检测依赖；不引入 Robolectric；JVM 单测只覆盖纯逻辑，原生推理靠真机验证（Task 10）。

## 已确认的外部事实（不要重复调研）

- JitPack 坐标 `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`：AAR 49,441,341 B，`classes.jar` 含 `com.k2fsa.sherpa.onnx.OfflineTts` / `OfflineTtsConfig` / `OfflineTtsModelConfig` / `OfflineTtsVitsModelConfig` / `GenerationConfig` / `GeneratedAudio`，`jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/` 各含 4 个 .so（app 的 `abiFilters` 会自动裁掉 x86 两份）。
- 关键 API（javap 实测，Kotlin 命名参数可直接用）：
  - `OfflineTts(assetManager: AssetManager? = null, config: OfflineTtsConfig)`；`sampleRate(): Int`；`generateWithConfig(text: String, config: GenerationConfig): GeneratedAudio`；`release()`。
  - `OfflineTtsConfig(model, ruleFsts, ruleFars, maxNumSentences, silenceScale)`。
  - `OfflineTtsModelConfig(vits, matcha, kokoro, ..., numThreads, debug, provider)`。
  - `OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir, dictDir, noiseScale, noiseScaleW, lengthScale)`。
  - `GenerationConfig(silenceScale, speed, sid, referenceAudio, referenceSampleRate, referenceText, numSteps, extra, phonemeCodepoints, tokens)`。
  - `GeneratedAudio(samples: FloatArray, sampleRate: Int)`。
- 模型包体积（当前 spike 产物实测字节数，Task 2 脚本会再核对一次）：

| 相对路径 | 大小（B） | sha256 在 Task 2 生成 |
|---|---|---|
| `model.onnx` | 74,864,089 | — |
| `lexicon.txt`（源 `lexicon_final3.txt`） | 8,915,316 | — |
| `tokens.txt` | 439 | — |
| `fsts/phone.fst` | 88,630 | — |
| `fsts/date.fst` | 59,154 | — |
| `fsts/number.fst` | 64,482 | — |
| 合计 | 83,992,110（≈84.0MB） | — |

---

## File Structure

**新增**

| 路径 | 职责 |
|---|---|
| `app/src/main/assets/tts/ja_reading_map.json` | 日文词形→假名映射（515KB，Task 2 从 spike 复制） |
| `app/src/main/java/com/meapet/mobile/tts/text/JaReadingMap.kt` | 映射数据结构 + DTO + 资产加载器 |
| `app/src/main/java/com/meapet/mobile/tts/text/TtsTextNormalizer.kt` | 拉丁小写 + 日文上下文改写（`test_final_design.py::rewrite` 的 Kotlin 移植） |
| `app/src/main/java/com/meapet/mobile/tts/engine/TtsEngine.kt` | 合成引擎接口（便于单测替换） |
| `app/src/main/java/com/meapet/mobile/tts/engine/SherpaTtsEngine.kt` | sherpa 配置构造、懒加载、合成、释放 |
| `app/src/test/java/com/meapet/mobile/tts/engine/SherpaDependencyTest.kt` | 依赖接入+API 签名验证（Task 1） |
| `app/src/test/java/com/meapet/mobile/tts/engine/SherpaTtsEngineConfigTest.kt` | `buildSherpaTtsConfig()` 纯函数验证（Task 5） |
| `app/src/test/java/com/meapet/mobile/tts/text/TtsTextNormalizerTest.kt` | 改写规则单测（Task 3） |
| `app/src/test/java/com/meapet/mobile/tts/model/TtsModelPackageTest.kt` | 包清单/zip 条目匹配纯函数单测（Task 4） |
| `app/src/test/java/com/meapet/mobile/tts/audio/TtsAudioPlayerTest.kt` | float→PCM16 转换单测（Task 6） |
| `app/src/test/java/com/meapet/mobile/tts/TtsManagerTest.kt` | 语速换算/采样率/空白跳过（Task 7） |
| `docs/tts/architecture.md` | 最终 G2P 架构（Task 2 复制自 spike 并修正） |
| `docs/tts/model-package.md` | 模型包清单、上传步骤、sha256（Task 2） |
| `.tools/tts-spike/scripts/build_app_model_package.py` | 打包 dist/ + 同步资产 + 生成 manifest（gitignored） |

**修改**

| 路径 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 加 `sherpa-onnx`，删 `onnxruntime-android` |
| `settings.gradle.kts` | `dependencyResolutionManagement.repositories` 加 JitPack（限定 group） |
| `app/build.gradle.kts` | 依赖替换；native 库注释更新 |
| `app/proguard-rules.pro` | 加 `com.k2fsa.sherpa.onnx.**` keep |
| `app/src/main/java/com/meapet/mobile/tts/model/TtsModelManager.kt` | 新文件清单/size 严格校验/旧包迁移/zip 嵌套导入/去掉 `ensureNativeLoaded` |
| `app/src/main/java/com/meapet/mobile/tts/audio/TtsAudioPlayer.kt` | `play(audio, sampleRate)`；去掉对 `TtsSynthesizer` 的依赖 |
| `app/src/main/java/com/meapet/mobile/tts/TtsManager.kt` | 归一化 + 引擎调用 + 互斥串行 + 动态采样率播放 + 试听入口 |
| `app/src/main/java/com/meapet/mobile/app/AppContainer.kt` | 装配 `SherpaTtsEngine` / `TtsTextNormalizer` |
| `app/src/main/java/com/meapet/mobile/ui/screen/settings/VoiceSettings.kt` | 文案（约 84MB）、debug 试听对话框 |
| `docs/TODO.md` | 勾掉 TTS 集成相关条目（若存在） |

**删除**

`tts/VitsOnnxEngine.kt`、`tts/TtsSynthesizer.kt`、`tts/DurationExpander.kt`、`tts/g2p/`（整目录 5 文件）、`test/.../tts/ChineseG2pTest.kt`、`test/.../tts/G2pProcessorTest.kt`、`app/src/main/assets/pinyin/zh_pinyin.txt`、`app/src/main/assets/pinyin/zh_phrases.txt`。

---

### Task 1: 接入 sherpa-onnx AAR 并移除 ONNX Runtime 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`（dependencies 段）
- Modify: `app/proguard-rules.pro`
- Test: `app/src/test/java/com/meapet/mobile/tts/engine/SherpaDependencyTest.kt`（新建）

**Interfaces:**
- Consumes: 无
- Produces: 编译期可用的 `com.k2fsa.sherpa.onnx.*` 类；libs alias `sherpa.onnx`。后续 Task 5 直接 `implementation(libs.sherpa.onnx)` 已就位。

- [ ] **Step 1: 先写失败的验证测试**

```kotlin
package com.meapet.mobile.tts.engine

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** 证明 sherpa-onnx AAR 已进入单测 classpath，且关键数据类签名与计划一致。 */
class SherpaDependencyTest {

    @Test
    fun `sherpa 配置类可构造且字段可读`() {
        val vits = OfflineTtsVitsModelConfig(
            model = "/tmp/model.onnx",
            lexicon = "/tmp/lexicon.txt",
            tokens = "/tmp/tokens.txt",
            noiseScale = 0.55f,
            noiseScaleW = 0.7f,
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"),
            ruleFsts = "/tmp/phone.fst,/tmp/date.fst,/tmp/number.fst",
            maxNumSentences = 1,
        )

        assertEquals("/tmp/model.onnx", config.model.vits.model)
        assertEquals(0.55f, config.model.vits.noiseScale, 1e-6f)
        assertEquals("/tmp/phone.fst,/tmp/date.fst,/tmp/number.fst", config.ruleFsts)
        assertEquals(1, config.maxNumSentences)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（编译期找不到包）**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.engine.SherpaDependencyTest"`
Expected: FAIL —— `Unresolved reference: k2fsa`。

- [ ] **Step 3: 加仓库与依赖**

`settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 改为：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx Android AAR（含 JNI + libonnxruntime.so），仅该 group 走 JitPack，避免拖慢其它依赖解析
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}
```

`gradle/libs.versions.toml`：

```toml
[versions]
# 删掉 onnxruntime = "1.24.3"，新增：
sherpaOnnx = "v2.0.1"

[libraries]
# 删掉 onnxruntime-android = {...}，新增：
sherpa-onnx = { module = "com.github.k2-fsa.sherpa-onnx:sherpa-onnx", version.ref = "sherpaOnnx" }
```

`app/build.gradle.kts` dependencies 段把「本地 VITS TTS：ONNX Runtime」那三行替换为：

```kotlin
    // 本地 VITS TTS：sherpa-onnx AAR（内含 JNI + libonnxruntime.so，仅 v8a/v7a 会被 abiFilters 打包进 APK；
    // 模型仍走运行时下载，见 TtsModelManager）。不要再引入 onnxruntime-android，否则 so 重复。
    implementation(libs.sherpa.onnx)
```

同时更新 `aaptOptions` 上方注释里「ONNX Runtime 原生库（libonnxruntime.so）随 AAR 打包进 APK」为「sherpa-onnx AAR 内嵌 libsherpa-onnx-jni.so + libonnxruntime.so」。

`app/proguard-rules.pro`：把 「ONNX Runtime」段的规则替换为：

```
# ── sherpa-onnx（JNI 按注册名回调 Java/Kotlin 类，AAR 自带 proguard.txt 为空） ──
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.engine.SherpaDependencyTest"`
Expected: PASS（`BUILD SUCCESSFUL`，1 test passed）。
若报 `Module was compiled with an incompatible version of Kotlin`，停下上报（需要换 AAR 版本），不要用 `-Xskip-metadata-version-check` 掩盖。

- [ ] **Step 5: 确认依赖树里没有两份 ORT**

Run: `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath | Select-String -Pattern "sherpa|onnxruntime"`
Expected: 只出现 `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`，**没有** `com.microsoft.onnxruntime:onnxruntime-android`。

- [ ] **Step 6: 提交**

```bash
git add gradle/libs.versions.toml settings.gradle.kts app/build.gradle.kts app/proguard-rules.pro app/src/test/java/com/meapet/mobile/tts/engine/SherpaDependencyTest.kt
git commit -m "build(tts): 接入 sherpa-onnx AAR 并移除 onnxruntime-android 依赖"
```

---

### Task 2: 生成 App 模型包与资产（脚本 + 文档）

**Files:**
- Create: `.tools/tts-spike/scripts/build_app_model_package.py`（gitignored）
- Create: `app/src/main/assets/tts/ja_reading_map.json`（脚本复制）
- Create: `docs/tts/architecture.md`（复制 spike 架构文档并修正过时内容）
- Create: `docs/tts/model-package.md`（清单 + 上传步骤 + sha256）

**Interfaces:**
- Consumes: `.tools/tts-spike/model/{model.onnx,lexicon_final3.txt,tokens.txt,fsts/*.fst,ja_reading_map.json}`
- Produces: `dist/` 下 6 个模型文件（相对路径见上表）+ `dist/tts-model-v1.zip` + `dist/manifest.json`；App 资产 `assets/tts/ja_reading_map.json`。Task 4 的 `EXPECTED_SIZES` 与 Task 10 的推送路径都以此为准。

- [ ] **Step 1: 写打包脚本**

```python
# -*- coding: utf-8 -*-
"""把 spike 产物打成 App 可分发模型包（dist/），并同步 App 资产 ja_reading_map.json。

输入：.tools/tts-spike/model/ 下的 spike 产物
输出：.tools/tts-spike/dist/{model.onnx,tokens.txt,lexicon.txt,fsts/*.fst}
      .tools/tts-spike/dist/tts-model-v1.zip      # 供 App「从本地导入」
      .tools/tts-spike/dist/manifest.json         # 大小 + sha256，写文档用
      app/src/main/assets/tts/ja_reading_map.json # 随 APK 分发
"""
import hashlib
import json
import os
import shutil
import zipfile

SPIKE = r'E:\Documents\Projects\Android\MeaPet\.tools\tts-spike'
APP = r'E:\Documents\Projects\Android\MeaPet\app'
SRC = os.path.join(SPIKE, 'model')
DIST = os.path.join(SPIKE, 'dist')
ASSET_DIR = os.path.join(APP, 'src', 'main', 'assets', 'tts')

# (源文件相对 model/，目标相对 dist/)
FILES = [
    ('model.onnx', 'model.onnx'),
    ('lexicon_final3.txt', 'lexicon.txt'),
    ('tokens.txt', 'tokens.txt'),
    ('fsts/phone.fst', 'fsts/phone.fst'),
    ('fsts/date.fst', 'fsts/date.fst'),
    ('fsts/number.fst', 'fsts/number.fst'),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    if os.path.isdir(DIST):
        shutil.rmtree(DIST)
    os.makedirs(DIST)

    total = 0
    manifest = []
    for src_rel, dst_rel in FILES:
        src = os.path.join(SRC, src_rel)
        if not os.path.isfile(src) or os.path.getsize(src) == 0:
            raise SystemExit('缺少输入：%s' % src)
        dst = os.path.join(DIST, dst_rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        size = os.path.getsize(dst)
        total += size
        manifest.append({'path': dst_rel, 'size': size, 'sha256': sha256(dst)})

    os.makedirs(ASSET_DIR, exist_ok=True)
    shutil.copy2(os.path.join(SRC, 'ja_reading_map.json'),
                 os.path.join(ASSET_DIR, 'ja_reading_map.json'))

    zip_path = os.path.join(DIST, 'tts-model-v1.zip')
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for item in manifest:
            z.write(os.path.join(DIST, item['path']), item['path'])

    json.dump(manifest, open(os.path.join(DIST, 'manifest.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=2)

    print('dist: %d files %.2fMB | zip %.2fMB' %
          (len(manifest), total / 1e6, os.path.getsize(zip_path) / 1e6))
    for item in manifest:
        print('%-16s %10d  %s' % (item['path'], item['size'], item['sha256']))
    print('asset:', os.path.getsize(os.path.join(ASSET_DIR, 'ja_reading_map.json')), 'bytes')


if __name__ == '__main__':
    main()
```

- [ ] **Step 2: 运行脚本**

Run: `.\.tools\tts-venv\Scripts\python.exe .tools\tts-spike\scripts\build_app_model_package.py`
Expected: 输出 6 行清单 + 总计 `83.99MB` 左右；`asset: 514556 bytes`。

- [ ] **Step 3: 校验打包结果与计划表一致**

Run:

```powershell
Get-ChildItem .tools\tts-spike\dist -Recurse -File | Where-Object { $_.Name -ne 'tts-model-v1.zip' -and $_.Name -ne 'manifest.json' } |
    Sort-Object FullName | ForEach-Object { "{0,10}  {1}" -f $_.Length, $_.FullName.Replace((Get-Location).Path + '\','') }
```

Expected（必须逐字节匹配；`Sort-Object FullName` 下顺序为 fsts/date、fsts/number、fsts/phone、lexicon、model、tokens）：

```
     59154  .tools\tts-spike\dist\fsts\date.fst
     64482  .tools\tts-spike\dist\fsts\number.fst
     88630  .tools\tts-spike\dist\fsts\phone.fst
   8915316  .tools\tts-spike\dist\lexicon.txt
  74864089  .tools\tts-spike\dist\model.onnx
       439  .tools\tts-spike\dist\tokens.txt
```

若与上表不同，**以脚本实测值为准**并同步更新本计划与 Task 4 的 `EXPECTED_SIZES`（不要把旧数字硬写进代码）。

- [ ] **Step 4: 写两份仓库文档**

`docs/tts/architecture.md`：复制 `.tools/tts-spike/G2P-ARCHITECTURE.md` 全文，然后修正这三处已知不准确内容：

1. tokens.txt：实际 69 行、67 个唯一 ID（`_`/`^`/`$` 共用 ID 0，ID 58 缺号），不是「68 符号 + ^/$」的简单描述；
2. 「OOV=0」改为「在 13 条综合样本上 OOV=0（lexicon 覆盖范围外仍会逐字/字母兜底）」；
3. 改写规则补两条边界：标点与句末也算「非假名」（因此「天気。」里的「気」不会被当前字前后假名判定为日语，需要后面的假名或词条命中）、词条最长匹配上限 8 字。

`docs/tts/model-package.md`：模板如下（把 `<...>` 换成脚本输出）：

```markdown
# TTS 模型包（sherpa-onnx）

## 文件清单

| 文件 | 大小（B） | sha256 |
|---|---|---|
| model.onnx | `<...>` | `<...>` |
| lexicon.txt | `<...>` | `<...>` |
| tokens.txt | `<...>` | `<...>` |
| fsts/phone.fst | `<...>` | `<...>` |
| fsts/date.fst | `<...>` | `<...>` |
| fsts/number.fst | `<...>` | `<...>` |

合计约 84MB（zip 约 80MB）。`ja_reading_map.json`（515KB）不进模型包，随 APK assets 分发。

## 分发方式

App 支持两种：

1. **在线下载**：把 6 个文件按「上传名」平铺上传到 `app.ttsModelBaseUrl` 指向的静态目录 / GitHub Release：
   `model.onnx`、`lexicon.txt`、`tokens.txt`、`phone.fst`、`date.fst`、`number.fst`
   （`fsts/` 只保留 App 本地目录结构，远端是平铺名，见 TtsModelManager.buildDownloadFiles）。
2. **本地 zip 导入**：直接用 `dist/tts-model-v1.zip`，设置页「从本地导入」。

## 重新生成

```powershell
.\.tools\tts-venv\Scripts\python.exe .tools\tts-spike\scripts\build_app_model_package.py
```

源数据（lexicon/model）由 `.tools/tts-spike/scripts/{build_lexicon_v3,build_lexicon_en,build_lexicon_ja,rebuild_zh_lexicon,test_final_design}.py` 生成，链路见 `docs/tts/architecture.md`。
```

- [ ] **Step 5: 提交（只提交资产与文档，84MB 模型包不入库）**

```bash
git add app/src/main/assets/tts/ja_reading_map.json docs/tts/architecture.md docs/tts/model-package.md
git commit -m "chore(tts): 打 sherpa 模型包并同步日语改写映射资产与文档"
```

Run: `git status --porcelain` → Expected: 不出现 `.tools/`（已被 `/.tools/` 忽略）。

---

### Task 3: 文本归一化（拉丁小写 + 日语上下文改写）

**Files:**
- Create: `app/src/main/java/com/meapet/mobile/tts/text/JaReadingMap.kt`
- Create: `app/src/main/java/com/meapet/mobile/tts/text/TtsTextNormalizer.kt`
- Test: `app/src/test/java/com/meapet/mobile/tts/text/TtsTextNormalizerTest.kt`

**Interfaces:**
- Consumes: `assets/tts/ja_reading_map.json`（Task 2）
- Produces:
  - `data class JaReadingMap(val word: Map<String,String>, val charFull: Map<String,String>, val charStem: Map<String,String>)`，`JaReadingMap.EMPTY`
  - `class TtsTextNormalizer(private val mapProvider: () -> JaReadingMap)`，`fun normalize(text: String): String`（Task 7 使用）
  - `object JaReadingMapLoader { fun load(context: Context): JaReadingMap }`（Task 7 的 provider 用）

- [ ] **Step 1: 先写失败的单测（用例直接抄 spike 已验证输出）**

```kotlin
package com.meapet.mobile.tts.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextNormalizerTest {

    // 小型映射：只保留测试用条目，避免单测依赖 515KB 资产
    private val map = JaReadingMap(
        word = mapOf("今日" to "きょう", "一緒" to "いっしょ", "天気" to "てんき"),
        charFull = mapOf("私" to "わたし", "何" to "なに"),
        charStem = mapOf("知" to "し"),
    )

    private fun normalizer() = TtsTextNormalizer { map }

    @Test
    fun `中文不被动`() {
        val text = "重庆的银行行长说，重要的会议要重新安排。"
        assertEquals(text, normalizer().normalize(text))
    }

    @Test
    fun `日语单字按整字或词干改写`() {
        assertEquals("わたしはなにもしらない。", normalizer().normalize("私は何も知らない。"))
    }

    @Test
    fun `日语词条优先最长匹配`() {
        assertEquals("こんにちは、きょうはいいてんきですね。", normalizer().normalize("こんにちは、今日はいい天気ですね。"))
        assertEquals("いっしょにいこう", normalizer().normalize("一緒にいこう"))
    }

    @Test
    fun `中英混排只改日语片段且拉丁转小写`() {
        assertEquals(
            "今天天气不错，いっしょにいこう，let's go！",
            normalizer().normalize("今天天气不错，一緒にいこう，LET'S GO！"),
        )
    }

    @Test
    fun `紧挨假名的汉字才进入日语分支`() {
        // 「今天」后面是中文标点：整段保持中文
        assertEquals("今天天气真好。", normalizer().normalize("今天天气真好。"))
    }

    @Test
    fun `映射缺失时保留原字`() {
        assertEquals("麒麟です", normalizer().normalize("麒麟です"))
    }

    @Test
    fun `空白输入返回空串`() {
        assertEquals("", normalizer().normalize("   "))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.text.TtsTextNormalizerTest"`
Expected: FAIL（`Unresolved reference: JaReadingMap`）。

- [ ] **Step 3: 实现 `JaReadingMap.kt`**

```kotlin
package com.meapet.mobile.tts.text

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 日语汉字改写映射（由 JMdict 常用词生成，见 docs/tts/architecture.md）。
 *
 * - [word] 词形 → 假名读音（≥2 字，最长匹配用，上限 8 字）
 * - [charFull] 单字 → 整字读音（私は→わたし、何も→なに）
 * - [charStem] 单字 → 动词/形容词词干读音（知らない→し、行こう→い）
 */
data class JaReadingMap(
    val word: Map<String, String>,
    val charFull: Map<String, String>,
    val charStem: Map<String, String>,
) {
    companion object {
        val EMPTY = JaReadingMap(emptyMap(), emptyMap(), emptyMap())
    }
}

@Serializable
internal data class JaReadingMapDto(
    val word: Map<String, String> = emptyMap(),
    @SerialName("char_full") val charFull: Map<String, String> = emptyMap(),
    @SerialName("char_stem") val charStem: Map<String, String> = emptyMap(),
)

internal fun JaReadingMapDto.toMap() = JaReadingMap(word, charFull, charStem)

/** 从 APK 资产读取日语映射（首次调用约几十~几百毫秒，调用方必须在 IO 线程）。 */
object JaReadingMapLoader {
    private const val ASSET = "tts/ja_reading_map.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): JaReadingMap =
        context.assets.open(ASSET).bufferedReader().use { reader ->
            json.decodeFromString(JaReadingMapDto.serializer(), reader.readText()).toMap()
        }
}
```

- [ ] **Step 4: 实现 `TtsTextNormalizer.kt`（逐行对齐 `test_final_design.py::rewrite`）**

```kotlin
package com.meapet.mobile.tts.text

/**
 * 合成前文本归一化：拉丁转小写 + 日语汉字连段改写为假名。
 *
 * 规则（与 `.tools/tts-spike/scripts/test_final_design.py::rewrite` 完全一致，改逻辑必须两边同步）：
 * 1. 扫描「连续汉字段」；仅当该段紧挨假名（前或后）才视为日语片段；
 * 2. 片段内先用 [JaReadingMap.word] 做最长匹配（≤[MAX_WORD_LEN] 字）；
 * 3. 未命中的单字：后跟假名且有词干读音→词干；否则整字读音；再否则保留原字；
 * 4. 非日语片段完全不动 → 中文永不被日文词条抢占。
 */
class TtsTextNormalizer(private val mapProvider: () -> JaReadingMap) {

    @Volatile
    private var cachedMap: JaReadingMap? = null

    private fun map(): JaReadingMap =
        cachedMap ?: synchronized(this) { cachedMap ?: mapProvider().also { cachedMap = it } }

    /** 文本 → 归一化文本；空白输入返回空串。 */
    fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return rewriteJapaneseContext(lowercaseLatin(trimmed))
    }

    internal fun lowercaseLatin(text: String): String =
        LATIN_RUN.replace(text) { it.value.lowercase() }

    internal fun rewriteJapaneseContext(text: String): String {
        val ja = map()
        val sb = StringBuilder(text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            if (!isKanji(text[i])) {
                sb.append(text[i])
                i++
                continue
            }
            var j = i
            while (j < n && isKanji(text[j])) j++
            val run = text.substring(i, j)
            val prevKana = i > 0 && isKana(text[i - 1])
            val nextKana = j < n && isKana(text[j])

            if (!prevKana && !nextKana) {
                sb.append(run)   // 纯中文片段：不动
                i = j
                continue
            }

            var k = 0
            while (k < run.length) {
                var hitLen = 0
                var hitReading: String? = null
                for (len in minOf(MAX_WORD_LEN, run.length - k) downTo 2) {
                    val reading = ja.word[run.substring(k, k + len)]
                    if (reading != null) {
                        hitLen = len
                        hitReading = reading
                        break
                    }
                }
                if (hitReading != null) {
                    sb.append(hitReading)
                    k += hitLen
                    continue
                }
                val ch = run[k]
                val followKana = (k + 1 < run.length && isHira(run[k + 1])) ||
                    (k + 1 == run.length && nextKana)
                val reading = when {
                    followKana && ja.charStem.containsKey(ch) -> ja.charStem[ch]
                    ja.charFull.containsKey(ch) -> ja.charFull[ch]
                    ja.charStem.containsKey(ch) -> ja.charStem[ch]
                    else -> null
                }
                sb.append(reading ?: ch.toString())
                k++
            }
            i = j
        }
        return sb.toString()
    }

    private companion object {
        /** 与 Python `re.sub(r"[A-Za-z][A-Za-z'\-\.]*", lower)` 等价。 */
        val LATIN_RUN = Regex("[A-Za-z][A-Za-z'\\-.]*")
        const val MAX_WORD_LEN = 8

        fun isKanji(c: Char) = c in '\u4e00'..'\u9fff'
        fun isKana(c: Char) = c in '\u3040'..'\u30ff'
        fun isHira(c: Char) = c in '\u3040'..'\u309f'
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.text.TtsTextNormalizerTest"`
Expected: PASS（7 tests）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/meapet/mobile/tts/text app/src/test/java/com/meapet/mobile/tts/text
git commit -m "feat(tts): 新增文本归一化与日语上下文改写"
```

---

### Task 4: `TtsModelManager` 改造（新包清单 / 严格校验 / 旧包迁移 / zip 嵌套导入）

**Files:**
- Modify: `app/src/main/java/com/meapet/mobile/tts/model/TtsModelManager.kt`
- Test: `app/src/test/java/com/meapet/mobile/tts/model/TtsModelPackageTest.kt`

**Interfaces:**
- Consumes: 模型包布局（Task 2）；`TtsModelState`
- Produces（Task 5/7/8 依赖）:
  - `companion object { val REQUIRED_MODEL_FILES: List<String>; val EXPECTED_SIZES: Map<String, Long> }`
  - `fun modelDir(): File`（新增，供给 `buildSherpaTtsConfig`）
  - `fun isModelReady(): Boolean`（严格按大小校验）
  - `fun buildDownloadFiles(baseUrl: String): List<ModelFile>`（远端平铺名 → 本地 `fsts/` 路径）
  - `suspend fun importFromZip(uri: Uri): Result<String>`（支持 `fsts/` 子目录与 zip 内嵌一级目录）
  - `internal fun resolveModelEntries(entries: List<String>): Map<String, String>?`（纯函数，可单测）
  - `ensureNativeLoaded()` 改为 `@Deprecated` 空实现（旧引擎还在调用，Task 9 随旧代码一起删）

- [ ] **Step 1: 先写失败的单测**

```kotlin
package com.meapet.mobile.tts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsModelPackageTest {

    @Test
    fun `必需文件清单与大小表一一对应`() {
        val files = TtsModelManager.REQUIRED_MODEL_FILES
        assertEquals(6, files.size)
        assertEquals(
            listOf("model.onnx", "tokens.txt", "lexicon.txt", "fsts/phone.fst", "fsts/date.fst", "fsts/number.fst"),
            files,
        )
        assertTrue(files.all { TtsModelManager.EXPECTED_SIZES[it]?.let { size -> size > 0 } == true })
    }

    @Test
    fun `根级平铺 zip 可解析`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES
        val mapping = resolveModelEntries(entries)!!
        assertEquals("model.onnx", mapping["model.onnx"])
        assertEquals("fsts/phone.fst", mapping["fsts/phone.fst"])
    }

    @Test
    fun `zip 内嵌一级目录可解析`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES.map { "tts-model-v1/$it" } + "readme.txt"
        val mapping = resolveModelEntries(entries)!!
        assertEquals("tts-model-v1/model.onnx", mapping["model.onnx"])
        assertEquals("tts-model-v1/fsts/number.fst", mapping["fsts/number.fst"])
    }

    @Test
    fun `缺任一文件返回 null`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES - "fsts/date.fst"
        assertNull(resolveModelEntries(entries))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.model.TtsModelPackageTest"`
Expected: FAIL（`Unresolved reference: resolveModelEntries` / `EXPECTED_SIZES` 大小不符）。

- [ ] **Step 3: 改常量与就绪判定**

`TtsModelManager.kt` 顶部 companion object 替换（保留 `TAG`、`MODEL_DIR`）：

```kotlin
    companion object {
        private const val TAG = "TtsModelManager"
        private const val MODEL_DIR = "tts_model"

        /** 必需文件（相对 modelDir）。fsts/ 子目录由 App 维护，远端上传名是平铺的 basename。 */
        val REQUIRED_MODEL_FILES = listOf(
            "model.onnx",
            "tokens.txt",
            "lexicon.txt",
            "fsts/phone.fst",
            "fsts/date.fst",
            "fsts/number.fst",
        )

        /** 各文件精确字节数（来源：.tools/tts-spike/dist/manifest.json，见 docs/tts/model-package.md）。 */
        val EXPECTED_SIZES = mapOf(
            "model.onnx" to 74_864_089L,
            "tokens.txt" to 439L,
            "lexicon.txt" to 8_915_316L,
            "fsts/phone.fst" to 88_630L,
            "fsts/date.fst" to 59_154L,
            "fsts/number.fst" to 64_482L,
        )

        /** 旧版 4 模块模型文件名：升级时清理，避免 73MB 垃圾常驻。 */
        private val LEGACY_MODEL_FILES = listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx")
    }
```

`init` 改为：

```kotlin
    init {
        migrateLegacyModel()
        refreshState()
        // 一次性迁移：旧版本把 libonnxruntime.so 下载到 filesDir/tts_model/lib/<abi>/，
        // 现改为随 AAR 打包，异步清理老用户升级后的残留目录（~20MB，不阻塞启动）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { File(modelDir, "lib").deleteRecursively() }
        }
    }

    /** 旧版 4 模块 ONNX（enc_p/dp/flow/dec）不再使用：整目录清掉后按未下载态引导重新下载。 */
    private fun migrateLegacyModel() {
        if (LEGACY_MODEL_FILES.any { File(modelDir, it).exists() }) {
            Log.i(TAG, "检测到旧版 4 模块模型，清理后需重新下载 sherpa 模型包")
            runCatching { modelDir.deleteRecursively() }
        }
    }
```

`isModelReady` 改为严格大小校验，并新增 `modelDir()`：

```kotlin
    /** 模型 6 件是否齐全且大小精确匹配（下载/导入中断的半文件不会被误判就绪）。 */
    fun isModelReady(): Boolean = REQUIRED_MODEL_FILES.all { rel ->
        val file = File(modelDir, rel)
        val expected = EXPECTED_SIZES[rel] ?: 0L
        file.isFile && (expected <= 0L || file.length() == expected)
    }

    /** 模型目录绝对路径（sherpa 配置构造用）。 */
    fun modelDir(): File = modelDir
```

删掉 `nativeLoaded` 字段与真正的 `System.loadLibrary` 逻辑（sherpa AAR 自带 `System.loadLibrary("sherpa-onnx-jni")`），但**暂时保留一个空实现**，因为旧 `VitsOnnxEngine.kt` 还在调用它（Task 9 才删旧引擎）：

```kotlin
    /**
     * 兼容旧引擎的加载入口：sherpa AAR 自带 JNI 库加载，这里不再需要显式 load。
     * @deprecated 仅旧四模块引擎调用，Task 9 删除旧引擎后一并移除。
     */
    @Deprecated("sherpa AAR 自动加载原生库，无需显式调用")
    fun ensureNativeLoaded() {
        // no-op
    }
```

Task 9 删除旧引擎时，把这个 stub 也删掉。

- [ ] **Step 4: 改下载清单与 zip 导入**

`buildDownloadFiles`：

```kotlin
    /**
     * 由 baseUrl 生成下载清单。远端是**平铺文件名**（GitHub Release / 静态目录不能带子路径），
     * 本地落盘仍按 [REQUIRED_MODEL_FILES] 的相对路径（fsts/ 子目录）。
     */
    fun buildDownloadFiles(baseUrl: String): List<ModelFile> {
        if (baseUrl.isBlank()) return emptyList()
        val base = baseUrl.trimEnd('/')
        return REQUIRED_MODEL_FILES.map { rel ->
            ModelFile(
                url = "$base/${rel.substringAfterLast('/')}",
                relativePath = rel,
                expectedSize = EXPECTED_SIZES[rel] ?: 0L,
            )
        }
    }
```

`importFromZip` 内两遍扫描改为按 `resolveModelEntries` 匹配（替换原有的 `resolveBaseDir` / `resolve` 逻辑与 `seenModels`）：

```kotlin
            val entries = ZipInputStream(stream).use { zis ->
                buildList {
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) add(entry.name)
                        zis.closeEntry()
                    }
                }
            }
            if (entries.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("zip 为空或无法解析"))
            }
            val mapping = resolveModelEntries(entries)
                ?: return@withContext Result.failure(
                    IllegalStateException("资源包缺少模型文件（需要 ${REQUIRED_MODEL_FILES.joinToString("、")}）")
                )
            val byEntry = mapping.entries.associate { (rel, entryName) -> entryName to rel }

            // 先整体清空再解压：避免残留半套模型 / 旧 fsts 混入
            modelDir.deleteRecursively()
            modelDir.mkdirs()

            val maxEntrySize = 256L * 1024 * 1024   // 单条目上限（zip 炸弹粗防御）
            resolver.openInputStream(uri).use { input ->
                val zis = ZipInputStream(input)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val rel = byEntry[entry.name]
                    if (rel != null) writeEntry(zis, File(modelDir, rel), maxEntrySize, buf)
                    zis.closeEntry()
                }
            }
            refreshState()
            if (_state.value == TtsModelState.Ready) {
                Log.i(TAG, "zip 导入完成：${byEntry.size} 个文件")
                Result.success("导入成功")
            } else {
                val msg = "导入失败：文件不完整或大小不符"
                _state.value = TtsModelState.Error(msg)
                Result.failure(IllegalStateException(msg))
            }
```

删除 `resolveBaseDir` 整个函数；文件末尾（类外）新增纯函数：

```kotlin
/**
 * 在 zip 条目里定位模型包：允许条目平铺或包一层目录（取 model.onnx 所在目录作为基准）。
 * @return 本地相对路径 → zip 条目名；缺任一必需文件返回 null。
 */
internal fun resolveModelEntries(entries: List<String>): Map<String, String>? {
    val modelEntry = entries.firstOrNull { it == "model.onnx" || it.endsWith("/model.onnx") } ?: return null
    val prefix = modelEntry.removeSuffix("model.onnx")
    val mapping = TtsModelManager.REQUIRED_MODEL_FILES.associateWith { rel -> "$prefix$rel" }
    return mapping.takeIf { m -> m.values.all { it in entries } }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.model.TtsModelPackageTest"`
Expected: PASS（4 tests）。

- [ ] **Step 6: 全量单测仍绿（此时旧引擎还在、SettingsViewModel 用的是 mock，不应受影响）**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/meapet/mobile/tts/model/TtsModelManager.kt app/src/test/java/com/meapet/mobile/tts/model/TtsModelPackageTest.kt
git commit -m "feat(tts): 模型管理切换到 sherpa 模型包（严格校验/旧包迁移/zip 嵌套导入）"
```

---

### Task 5: `TtsEngine` 接口与 `SherpaTtsEngine`

**Files:**
- Create: `app/src/main/java/com/meapet/mobile/tts/engine/TtsEngine.kt`
- Create: `app/src/main/java/com/meapet/mobile/tts/engine/SherpaTtsEngine.kt`
- Test: `app/src/test/java/com/meapet/mobile/tts/engine/SherpaTtsEngineConfigTest.kt`

**Interfaces:**
- Consumes: `TtsModelManager.modelDir()` / `isModelReady()`（Task 4）；sherpa API（Task 1）
- Produces（Task 7 依赖）:
  - `interface TtsEngine { fun synthesize(text: String, speed: Float): GeneratedAudio; fun release() }`
  - `class SherpaTtsEngine(modelManager: TtsModelManager) : TtsEngine`
  - `internal fun buildSherpaTtsConfig(modelDir: File): OfflineTtsConfig`
  - `internal const val TTS_NOISE_SCALE = 0.55f`、`TTS_NOISE_SCALE_W = 0.7f`、`TTS_NUM_THREADS = 2`

- [ ] **Step 1: 先写失败的配置单测**

```kotlin
package com.meapet.mobile.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SherpaTtsEngineConfigTest {

    @Test
    fun `配置路径为绝对路径且采样率无关`() {
        val dir = File("C:/tmp/tts_model")
        val config = buildSherpaTtsConfig(dir)
        val vits = config.model.vits

        assertEquals(File(dir, "model.onnx").absolutePath, vits.model)
        assertEquals(File(dir, "lexicon.txt").absolutePath, vits.lexicon)
        assertEquals(File(dir, "tokens.txt").absolutePath, vits.tokens)
        assertEquals(0.55f, vits.noiseScale, 1e-6f)
        assertEquals(0.7f, vits.noiseScaleW, 1e-6f)
        assertEquals(1, config.maxNumSentences)
        assertEquals(2, config.model.numThreads)
        assertEquals("cpu", config.model.provider)
    }

    @Test
    fun `ruleFsts 顺序固定 phone date number`() {
        val dir = File("C:/tmp/tts_model")
        val paths = buildSherpaTtsConfig(dir).ruleFsts.split(",")
        assertEquals(3, paths.size)
        assertTrue(paths[0].endsWith("fsts\\phone.fst") || paths[0].endsWith("fsts/phone.fst"))
        assertTrue(paths[1].endsWith("date.fst"))
        assertTrue(paths[2].endsWith("number.fst"))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.engine.SherpaTtsEngineConfigTest"`
Expected: FAIL（`Unresolved reference: buildSherpaTtsConfig`）。

- [ ] **Step 3: 写 `TtsEngine.kt`**

```kotlin
package com.meapet.mobile.tts.engine

import com.k2fsa.sherpa.onnx.GeneratedAudio

/**
 * TTS 合成引擎抽象：一段文本 → 一段波形。
 *
 * 单独抽接口是为了让 [com.meapet.mobile.tts.TtsManager] 能在 JVM 单测里替换掉原生实现。
 * 实现必须保证：合成失败抛异常（由上层兜底），返回的 [GeneratedAudio.samples] 允许为空。
 */
interface TtsEngine {
    /**
     * @param text 已完成归一化的文本（拉丁已小写、日语片段已改写假名）
     * @param speed 语速 = 1 / lengthScale，>1 更快
     */
    fun synthesize(text: String, speed: Float): GeneratedAudio

    /** 释放原生资源（删除模型前调用；之后可再次懒加载）。 */
    fun release()
}
```

- [ ] **Step 4: 写 `SherpaTtsEngine.kt`**

```kotlin
package com.meapet.mobile.tts.engine

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.meapet.mobile.tts.model.TtsModelManager
import java.io.File

/** 音高随机性 / 时长随机性：最终方案定稿值（桌面 spike 试听确认）。 */
internal const val TTS_NOISE_SCALE = 0.55f
internal const val TTS_NOISE_SCALE_W = 0.7f
internal const val TTS_NUM_THREADS = 2

/** 数字归一化 FST 顺序固定：phone 必须最先（否则电话号码会被读成中文数词）。 */
internal val RULE_FST_FILES = listOf("fsts/phone.fst", "fsts/date.fst", "fsts/number.fst")

/**
 * 构造 sherpa 配置。关键点：
 * - 不传 AssetManager（file 模式），路径必须是设备上的绝对路径；
 * - 走文本前端（不预分词），metadata 里 jieba=1 会让 CharacterLexicon 做最长匹配；
 * - ruleFsts 用逗号分隔的绝对路径。
 */
internal fun buildSherpaTtsConfig(modelDir: File): OfflineTtsConfig {
    val vits = OfflineTtsVitsModelConfig(
        model = File(modelDir, "model.onnx").absolutePath,
        lexicon = File(modelDir, "lexicon.txt").absolutePath,
        tokens = File(modelDir, "tokens.txt").absolutePath,
        noiseScale = TTS_NOISE_SCALE,
        noiseScaleW = TTS_NOISE_SCALE_W,
        lengthScale = 1.0f,
    )
    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = vits,
            numThreads = TTS_NUM_THREADS,
            debug = false,
            provider = "cpu",
        ),
        ruleFsts = RULE_FST_FILES.joinToString(",") { File(modelDir, it).absolutePath },
        maxNumSentences = 1,
    )
}

/**
 * sherpa-onnx 离线 TTS 引擎。
 *
 * - 首次 [synthesize] 才创建 [OfflineTts]（加载 75MB 模型 + 8.9MB lexicon，耗时数百 ms~数秒，务必在 IO 线程）；
 * - [OfflineTts.generateWithConfig] 是阻塞原生调用，**非线程安全**：并发合成由上层
 *   [com.meapet.mobile.tts.TtsManager] 的 Mutex 串行化；
 * - [release] 幂等，删除模型前由设置页调用。
 */
class SherpaTtsEngine(private val modelManager: TtsModelManager) : TtsEngine {

    companion object {
        private const val TAG = "SherpaTtsEngine"
    }

    @Volatile
    private var engine: OfflineTts? = null

    @Synchronized
    private fun requireEngine(): OfflineTts {
        engine?.let { return it }
        val startedAt = SystemClock.elapsedRealtime()
        val config = buildSherpaTtsConfig(modelManager.modelDir())
        Log.i(TAG, "创建 OfflineTts：model=${config.model.vits.model}")
        return OfflineTts(config = config).also {
            engine = it
            Log.i(
                TAG,
                "OfflineTts 就绪：sampleRate=${it.sampleRate()} speakers=${it.numSpeakers()} " +
                    "load=${SystemClock.elapsedRealtime() - startedAt}ms",
            )
        }
    }

    override fun synthesize(text: String, speed: Float): GeneratedAudio {
        val tts = requireEngine()
        val audio = tts.generateWithConfig(text, GenerationConfig(speed = speed, sid = 0))
        Log.d(TAG, "合成完成：文字=${text.length} speed=$speed 样本=${audio.samples.size} sr=${audio.sampleRate}")
        return audio
    }

    @Synchronized
    override fun release() {
        val current = engine ?: return
        engine = null
        runCatching { current.release() }.onFailure { Log.w(TAG, "释放 OfflineTts 异常", it) }
        Log.i(TAG, "OfflineTts 已释放")
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.engine.*"`
Expected: PASS（含 Task 1 的依赖测试）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/meapet/mobile/tts/engine app/src/test/java/com/meapet/mobile/tts/engine
git commit -m "feat(tts): 新增 sherpa 合成引擎与配置构造"
```

---

### Task 6: 播放器支持动态采样率

**Files:**
- Modify: `app/src/main/java/com/meapet/mobile/tts/audio/TtsAudioPlayer.kt`
- Test: `app/src/test/java/com/meapet/mobile/tts/audio/TtsAudioPlayerTest.kt`

**Interfaces:**
- Consumes: 无
- Produces（Task 7 依赖）:
  - `fun play(audio: FloatArray, sampleRate: Int)`（旧 `play(audio)` 删除）
  - `internal fun floatToPcm16(audio: FloatArray): ShortArray`
  - 移除 `import com.meapet.mobile.tts.TtsSynthesizer` 与 `SAMPLE_RATE` 常量

- [ ] **Step 1: 先写失败的转换单测**

```kotlin
package com.meapet.mobile.tts.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TtsAudioPlayerTest {

    @Test
    fun `浮点波形转 PCM16 并裁剪越界值`() {
        // 0.5 * 32767 = 16383.5，Kotlin toInt() 截断 → 16383；±2 先裁剪到 ±1
        val pcm = floatToPcm16(floatArrayOf(0f, 0.5f, -0.5f, 2f, -2f))
        assertArrayEquals(shortArrayOf(0, 16383, -16383, 32767, -32767), pcm)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.audio.TtsAudioPlayerTest"`
Expected: FAIL（`Unresolved reference: floatToPcm16`）。

- [ ] **Step 3: 改造播放器**

删除文件顶部 `import com.meapet.mobile.tts.TtsSynthesizer` 与 companion 里的 `const val SAMPLE_RATE = TtsSynthesizer.SAMPLE_RATE`，companion 只留 `JOIN_TIMEOUT_MS`；在文件末尾（类外）新增：

```kotlin
/**
 * fp32 波形（[-1,1]）→ PCM16。越界值裁剪到 [-1,1]，与参考实现 `audio * 32767` 一致；
 * 抽成顶层函数便于 JVM 单测（不触发 AudioTrack）。
 */
internal fun floatToPcm16(audio: FloatArray): ShortArray =
    ShortArray(audio.size) { i -> (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
```

`play` 改为：

```kotlin
    /**
     * fp32 波形 → int16 并播放。采样率由合成结果给出（当前模型 22050），不再写死。
     * 新段到达会先停止旧段。
     */
    fun play(audio: FloatArray, sampleRate: Int) {
        Log.d(
            TAG,
            "play: audio=${audio.size} 样本 sr=$sampleRate (~${audio.size / (sampleRate / 1000f) / 1000f}s)，" +
                "thread=${Thread.currentThread().name}",
        )
        // PCM 转换放锁外：与段调度无关，长段转换不应阻塞 stop（触摸语音互斥路径）
        val pcm = floatToPcm16(audio)
        synchronized(playLock) {
            stopInternal()
            val gen = synchronized(generationLock) { ++generation }
            isPlaying = true
            playThread = Thread({ writePcm(pcm, gen, sampleRate) }, "TtsAudioPlayer").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "play: 已提交写线程 gen=$gen sr=$sampleRate")
        }
    }
```

`writePcm(pcm: ShortArray, gen: Int, sampleRate: Int)`：签名加 sampleRate，内部 `obtainTrack(pcm.size, sampleRate)`，日志里带上 sampleRate。

`obtainTrack(minSamples: Int, sampleRate: Int)`：`getMinBufferSize(sampleRate, ...)` 与 `AudioFormat.Builder().setSampleRate(sampleRate)` 都用参数，去掉对 `SAMPLE_RATE` 常量的引用。

- [ ] **Step 4: 运行单测与编译**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.audio.TtsAudioPlayerTest"`
Expected: PASS。

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: FAIL —— `TtsManager.kt` 仍在调用旧的 `player.play(audio)`。这是预期的，Task 7 修好；本任务先不提交构建（Task 7 完成后一起绿）。

- [ ] **Step 5: 不单独提交（本任务会让 `TtsManager` 暂时编译失败）**

改动保留在工作区，与 Task 7 一起提交；Task 7 Step 6 的 `git add` 已覆盖本文件与单测。

---

### Task 7: `TtsManager` 改造 + `AppContainer` 装配

**Files:**
- Modify: `app/src/main/java/com/meapet/mobile/tts/TtsManager.kt`（整体重写）
- Modify: `app/src/main/java/com/meapet/mobile/app/AppContainer.kt:160-201`
- Test: `app/src/test/java/com/meapet/mobile/tts/TtsManagerTest.kt`（新建）

**Interfaces:**
- Consumes: `TtsEngine`/`SherpaTtsEngine`（Task 5）、`TtsTextNormalizer`/`JaReadingMapLoader`（Task 3）、`TtsAudioPlayer.play(audio, sampleRate)`（Task 6）、`TtsModelManager.state`（原有）
- Produces: `TtsManager(settingsManager, modelManager, engine: TtsEngine, normalizer: TtsTextNormalizer, player: TtsAudioPlayer, scope)`；新增 `fun speakPreview(text: String)`（设置页试听用，走 MAIN 开关之外的直通路径：仅要求模型就绪与会话未静音）

- [ ] **Step 1: 先写失败的单测**

```kotlin
package com.meapet.mobile.tts

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.engine.TtsEngine
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import com.meapet.mobile.tts.text.JaReadingMap
import com.meapet.mobile.tts.text.TtsTextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicReference

class TtsManagerTest {

    private val settings = mock<SettingsManager>()
    private val modelManager = mock<TtsModelManager>()
    private val player = mock<TtsAudioPlayer>()
    private val normalizer = TtsTextNormalizer { JaReadingMap.EMPTY }

    private fun manager(engine: TtsEngine) = TtsManager(
        settingsManager = settings,
        modelManager = modelManager,
        engine = engine,
        normalizer = normalizer,
        player = player,
        scope = CoroutineScope(SupervisorJob()),
    )

    @Test
    fun `播放取合成结果采样率并按语速换算`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(2.0)
        whenever(modelManager.state).thenReturn(MutableStateFlow<TtsModelState>(TtsModelState.Ready))

        val speedRef = AtomicReference<Float>(0f)
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                speedRef.set(speed)
                return GeneratedAudio(FloatArray(100) { 0.1f }, 24000)
            }
            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "今天天气真好。"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).play(any(), eq(24000))
        assertEquals(0.5f, speedRef.get(), 1e-6f)
    }

    @Test
    fun `空白内容不进入合成`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        whenever(modelManager.state).thenReturn(MutableStateFlow<TtsModelState>(TtsModelState.Ready))
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }
            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "   "), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertTrue(!called)
        // 注意：TtsManager.init 会给 player.onPlaybackComplete 赋值（算一次交互），
        // 所以只能断言 play() 从未被调用，不能用 verifyNoInteractions。
        verify(player, never()).play(any(), any())
    }

    @Test
    fun `模型未就绪不合成`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(modelManager.state).thenReturn(MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded))
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }
            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "你好"), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertTrue(!called)
        verify(player, never()).play(any(), any())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.TtsManagerTest"`
Expected: FAIL（`TtsManager` 构造参数不匹配 / `player.play(audio)` 签名不符）。

- [ ] **Step 3: 重写 `TtsManager.kt`**

```kotlin
package com.meapet.mobile.tts

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.engine.TtsEngine
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import com.meapet.mobile.tts.text.TtsTextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS 门面：开关判断 + 整段合成播放。
 *
 * 主界面与悬浮窗两路 LLM 回复都汇聚到这里：[speak] 先查对应开关与模型就绪，
 * 满足则「归一化 → sherpa 合成 → 播放」。新回复到达时取消上一段（旧话不念完）。
 *
 * ## 并发
 * - [speakSeq]：native 合成无法被 `Job.cancel()` 打断，合成完成后要校验自己仍是最新，
 *   否则旧消息的协程会在合成完成后盖掉新消息的播放（表现为"最新回复无声"）。
 * - [synthMutex]：sherpa 的 `generateWithConfig` 非线程安全，且取消挡不住在途 native 调用，
 *   用互斥锁把在途合成串行化；拿到锁后先复查序号，过期直接放弃，不浪费一次推理。
 */
class TtsManager(
    private val settingsManager: SettingsManager,
    private val modelManager: TtsModelManager,
    private val engine: TtsEngine,
    private val normalizer: TtsTextNormalizer,
    private val player: TtsAudioPlayer,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "TtsManager"

        /** 单次合成的最大字符数：超长回复合成占用内存/时间线性增长，超出后截断朗读（不影响文字显示）。 */
        private const val MAX_SYNTH_CHARS = 200

        /** 设置里的 lengthScale 合法范围（与设置页滑杆一致）。 */
        private const val LENGTH_SCALE_MIN = 0.5f
        private const val LENGTH_SCALE_MAX = 2.0f

        /**
         * TTS 开始播放时的回调（互斥：停掉未完的触摸语音）。
         * 由 app 容器注入，避免 tts 包反向依赖 live2d。
         */
        @Volatile
        var onPlaybackStart: (() -> Unit)? = null
    }

    /** 发声来源。 */
    enum class Source { MAIN, OVERLAY }

    /** 是否正在播放（驱动喇叭图标变色）。合成阶段为 false，真正出声才置 true。 */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 本次会话（内存）语音是否被临时关闭；不持久化，重启 App 即恢复。 */
    private val _sessionMuted = MutableStateFlow(false)
    val sessionMuted: StateFlow<Boolean> = _sessionMuted.asStateFlow()

    init {
        // 真实播放完成（硬件播放头到末尾帧）→ 解除播放中标记
        player.onPlaybackComplete = { _isPlaying.value = false }
    }

    /** 切换本次会话的语音开关（快捷静音按钮用）。关闭时同时停止当前朗读。 */
    fun toggleSessionMute() {
        val muted = !_sessionMuted.value
        _sessionMuted.value = muted
        if (muted) stop()
    }

    @Volatile
    private var currentJob: Job? = null

    /** 在途合成互斥（sherpa generate 非线程安全，且协作式取消挡不住 native 调用）。 */
    private val synthMutex = Mutex()

    /** speak 序号（原子递增）：合成完成后、播放前校验自己仍是最新。 */
    private val speakSeq = AtomicInteger(0)

    /**
     * 朗读一条助手回复。开关关闭 / 会话静音 / 模型未就绪时静默跳过（不影响文字显示）。
     */
    fun speak(message: ChatMessage, source: Source) {
        speakInternal(text = message.content.trim(), checkEnabled = { isEnabledFor(source) }, tag = "speak($source)")
    }

    /**
     * 设置页「试听」用：跳过来源开关（该入口本身就是用户主动操作），仍尊重会话静音与模型就绪。
     */
    fun speakPreview(text: String) {
        speakInternal(text = text.trim(), checkEnabled = { true }, tag = "speakPreview")
    }

    private fun speakInternal(text: String, checkEnabled: () -> Boolean, tag: String) {
        Log.d(TAG, "$tag 进入，content='${text.take(30)}'")
        if (!checkEnabled()) {
            Log.d(TAG, "$tag: 开关关闭，跳过朗读")
            return
        }
        if (_sessionMuted.value) {
            Log.d(TAG, "本次会话已静音，跳过朗读")
            return
        }
        if (modelManager.state.value != TtsModelState.Ready) {
            Log.d(TAG, "模型未就绪（state=${modelManager.state.value}），跳过朗读")
            return
        }
        if (text.isEmpty()) {
            Log.d(TAG, "$tag: content 空白，跳过")
            return
        }
        val clipped = if (text.length > MAX_SYNTH_CHARS) {
            Log.w(TAG, "文本超长（${text.length} 字），截断为前 $MAX_SYNTH_CHARS 字朗读")
            text.substring(0, MAX_SYNTH_CHARS)
        } else {
            text
        }

        val lengthScale = settingsManager.getTtsLengthScale().toFloat()
            .coerceIn(LENGTH_SCALE_MIN, LENGTH_SCALE_MAX)
        val speed = 1.0f / lengthScale

        // 抢占最新发言权（见类注释 speakSeq）
        val mySeq = speakSeq.incrementAndGet()

        currentJob?.cancel()
        player.stop()
        _isPlaying.value = false

        currentJob = scope.launch(Dispatchers.IO) {
            try {
                val normalized = normalizer.normalize(clipped)
                if (normalized.isEmpty()) {
                    Log.w(TAG, "$tag: 归一化后为空，静默跳过")
                    if (mySeq == speakSeq.get()) _isPlaying.value = false
                    return@launch
                }
                Log.d(TAG, "$tag: 开始合成 '${normalized.take(20)}' (${normalized.length} 字, speed=$speed)")

                // 串行化在途合成；拿到锁后复查序号，过期不再浪费一次推理
                val audio = synthMutex.withLock {
                    if (mySeq != speakSeq.get()) return@withLock null
                    engine.synthesize(normalized, speed)
                } ?: run {
                    Log.d(TAG, "$tag: 合成前已被更新的 speak/stop 取代（seq=$mySeq），放弃")
                    return@launch
                }

                if (mySeq != speakSeq.get()) {
                    Log.w(TAG, "$tag: 合成完成但已有更新的 speak/stop（seq=$mySeq → ${speakSeq.get()}），丢弃本段")
                    return@launch
                }
                if (audio.samples.isEmpty()) {
                    Log.w(TAG, "$tag: 合成为空数组，静默跳过（无声！）")
                    _isPlaying.value = false
                    return@launch
                }
                // 真正开始播放前才亮（合成 / 排队阶段喇叭保持熄灭）
                _isPlaying.value = true
                onPlaybackStart?.invoke()   // 互斥：停掉未完的触摸语音
                player.play(audio.samples, audio.sampleRate)   // 播完经 onPlaybackComplete 解除 isPlaying
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "$tag: 合成被取消（上一段被打断）")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "合成失败: $clipped", e)
                if (mySeq == speakSeq.get()) _isPlaying.value = false
            }
        }
    }

    /** 停止当前朗读并取消待合成任务。 */
    fun stop() {
        Log.d(TAG, "stop() 调用（取消合成 + 停播放）")
        speakSeq.incrementAndGet()   // 让在途合成完成后自行丢弃（cancel 挡不住 native 推理）
        currentJob?.cancel()
        currentJob = null
        player.stop()
        _isPlaying.value = false
    }

    /** 释放播放器与推理资源。 */
    fun release() {
        stop()
        player.release()
    }

    /**
     * 删除模型前调用：停止播放/合成并释放底层 OfflineTts（75MB 模型 + 8.9MB lexicon 的 native 内存）。
     * 之后若重新下载模型，引擎会在下次合成时按新模型懒加载重建。
     */
    fun releaseEngine() {
        stop()
        engine.release()
    }

    /** 对应来源的语音开关是否开启。 */
    private fun isEnabledFor(source: Source): Boolean = when (source) {
        Source.MAIN -> settingsManager.isTtsMainEnabled()
        Source.OVERLAY -> settingsManager.isTtsOverlayEnabled()
    }
}
```

- [ ] **Step 4: 改 `AppContainer` 装配**

替换 160~201 行（`// ── TTS 语音 ──` 到 `wireVoiceMutex()` 结束）中的：

```kotlin
    /** TTS 模型包（model.onnx + lexicon + tokens + fsts）下载与状态管理。 */
    val ttsModelManager: TtsModelManager by lazy {
        TtsModelManager(context)
    }

    /** sherpa-onnx 合成引擎（懒加载 75MB 模型 + 8.9MB lexicon）。 */
    private val sherpaTtsEngine: TtsEngine by lazy {
        SherpaTtsEngine(ttsModelManager)
    }

    /** 文本归一化：拉丁小写 + 日语上下文改写（首次合成时加载 ja_reading_map.json）。 */
    private val ttsTextNormalizer: TtsTextNormalizer by lazy {
        TtsTextNormalizer { JaReadingMapLoader.load(context) }
    }

    /** TTS 门面：开关判断、归一化、整段合成播放。 */
    val ttsManager: TtsManager by lazy {
        TtsManager(
            settingsManager = settingsManager,
            modelManager = ttsModelManager,
            engine = sherpaTtsEngine,
            normalizer = ttsTextNormalizer,
            player = TtsAudioPlayer(),
            scope = applicationScope,
        ).also { wireVoiceMutex() }
    }
```

imports 相应替换：删 `tts.TtsSynthesizer`、`tts.VitsOnnxEngine`、`tts.g2p.ChineseG2p`、`tts.g2p.G2pProcessor`；加 `tts.engine.SherpaTtsEngine`、`tts.engine.TtsEngine`、`tts.text.JaReadingMapLoader`、`tts.text.TtsTextNormalizer`。

- [ ] **Step 5: 运行单测与编译**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.tts.*" ; .\gradlew.bat :app:compileDebugKotlin`
Expected: PASS + 编译成功（旧 `VitsOnnxEngine/TtsSynthesizer` 仍在但已无调用方，Task 9 删除）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/meapet/mobile/tts/TtsManager.kt \
        app/src/main/java/com/meapet/mobile/tts/audio/TtsAudioPlayer.kt \
        app/src/main/java/com/meapet/mobile/app/AppContainer.kt \
        app/src/test/java/com/meapet/mobile/tts/TtsManagerTest.kt \
        app/src/test/java/com/meapet/mobile/tts/audio/TtsAudioPlayerTest.kt
git commit -m "feat(tts): TtsManager 切换到 sherpa 引擎（串行合成/动态采样率/试听入口）"
```

---

### Task 8: 设置页文案与 debug 试听入口

**Files:**
- Modify: `app/src/main/java/com/meapet/mobile/ui/screen/settings/VoiceSettings.kt`
- Modify: `app/src/main/java/com/meapet/mobile/viewmodel/SettingsViewModel.kt`（仅新增试听转发函数）

**Interfaces:**
- Consumes: `container.ttsManager.speakPreview(text)`（Task 7）
- Produces: 设置页 debug 下「试听文本…」入口；文案「约 84MB」

- [ ] **Step 1: 新增 ViewModel 转发函数**

`SettingsViewModel` 的 TTS 区（`updateTtsLengthScale` 附近）加：

```kotlin
    /** 设置页试听：直接把给定文本交给 TTS（跳过来源开关，仅要求模型就绪）。 */
    fun previewTts(text: String) {
        container.ttsManager.speakPreview(text)
    }
```

- [ ] **Step 2: 设置页加 debug 试听入口**

`TtsSection` 内、语速滑杆之后加（`BuildConfig.DEBUG` 门控，release 不出现在 UI）：

```kotlin
        // Debug 专用：任意文本试听，方便真机验证数字/日期/日语/中英混排（release 不显示）
        if (com.meapet.mobile.BuildConfig.DEBUG) {
            var previewText by remember { mutableStateOf("你好喵，今天天气真好。我今年18岁，电话是13800138000。") }
            var showDialog by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showDialog = true },
                enabled = modelReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("试听文本（Debug）") }
            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("输入试听文本") },
                    text = {
                        OutlinedTextField(
                            value = previewText,
                            onValueChange = { previewText = it },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDialog = false
                            viewModel.previewTts(previewText)
                        }) { Text("朗读") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) { Text("取消") }
                    },
                )
            }
        }
```

所需 import：`androidx.compose.material3.AlertDialog`、`androidx.compose.material3.OutlinedTextField`、`androidx.compose.runtime.getValue/setValue/mutableStateOf/remember`（按文件现有 import 风格补齐；若该文件已用 `rememberSaveable` 则用同款）。

- [ ] **Step 3: 改文案**

`VoiceSettings.kt` 166 行附近：

```kotlin
            "约 84MB，下载后开放语音功能；网络受限可从本地 zip 导入",
```

`Ready` 分支的 `"模型与已下载，语音功能可用"` 改为 `"模型已下载，语音功能可用"`。

- [ ] **Step 4: 编译 + 现有单测**

Run: `.\gradlew.bat :app:compileDebugKotlin ; .\gradlew.bat :app:testDebugUnitTest --tests "com.meapet.mobile.viewmodel.SettingsViewModelTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/meapet/mobile/ui/screen/settings/VoiceSettings.kt app/src/main/java/com/meapet/mobile/viewmodel/SettingsViewModel.kt
git commit -m "feat(tts): 设置页文案更新并加 debug 试听入口"
```

---

### Task 9: 删除旧 G2P / 四模块引擎 / 旧资产

**Files:**
- Delete: `app/src/main/java/com/meapet/mobile/tts/VitsOnnxEngine.kt`
- Delete: `app/src/main/java/com/meapet/mobile/tts/TtsSynthesizer.kt`
- Delete: `app/src/main/java/com/meapet/mobile/tts/DurationExpander.kt`
- Delete: `app/src/main/java/com/meapet/mobile/tts/g2p/`（5 文件）
- Delete: `app/src/test/java/com/meapet/mobile/tts/ChineseG2pTest.kt`
- Delete: `app/src/test/java/com/meapet/mobile/tts/G2pProcessorTest.kt`
- Delete: `app/src/main/assets/pinyin/zh_pinyin.txt`、`zh_phrases.txt`
- Modify: `app/build.gradle.kts`（若 Task 1 注释仍提到旧 G2P，顺手改）

**Interfaces:**
- Consumes: 无
- Produces: 干净构建；APK 减少 ~1.65MB assets

- [ ] **Step 1: 确认没有残留引用（待删文件自身不算）**

```powershell
Get-ChildItem -Recurse -File app/src | Select-String -Pattern "VitsOnnxEngine|TtsSynthesizer|DurationExpander|ChineseG2p|G2pProcessor|PinyinDict|PinyinTable|SymbolTable|zh_pinyin|zh_phrases" |
    Where-Object { $_.Path -notmatch '\\tts\\(VitsOnnxEngine|TtsSynthesizer|DurationExpander)\.kt$' -and $_.Path -notmatch '\\tts\\g2p\\' } |
    ForEach-Object { "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" }
```

Expected: 输出为空。若 AppContainer/TtsManager/SettingsViewModel 仍出现 `ensureNativeLoaded` 以外的旧类引用，先修掉再删文件。

- [ ] **Step 2: 删除文件**

```bash
git rm app/src/main/java/com/meapet/mobile/tts/VitsOnnxEngine.kt \
       app/src/main/java/com/meapet/mobile/tts/TtsSynthesizer.kt \
       app/src/main/java/com/meapet/mobile/tts/DurationExpander.kt
git rm -r app/src/main/java/com/meapet/mobile/tts/g2p
git rm app/src/test/java/com/meapet/mobile/tts/ChineseG2pTest.kt \
       app/src/test/java/com/meapet/mobile/tts/G2pProcessorTest.kt
git rm app/src/main/assets/pinyin/zh_pinyin.txt app/src/main/assets/pinyin/zh_phrases.txt
```

然后删掉 `TtsModelManager.kt` 里 Task 4 留的兼容 stub（`@Deprecated fun ensureNativeLoaded()` 整段），因为已无调用方。

- [ ] **Step 3: 全量单测 + 编译**

Run: `.\gradlew.bat :app:testDebugUnitTest ; .\gradlew.bat :app:assembleDebug`
Expected: 全部 PASS，`BUILD SUCCESSFUL`。

- [ ] **Step 4: 确认 APK 内不再有旧资产、也没有 x86 so**

Run:

```powershell
$apk = Get-ChildItem app\build\outputs\apk\debug\*.apk | Select-Object -First 1
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
$z.Entries | Where-Object { $_.FullName -match '\.so$|assets/pinyin|assets/tts' } | ForEach-Object { "{0,10}  {1}" -f $_.Length, $_.FullName }
$z.Dispose()
```

Expected：只有 `lib/arm64-v8a/{libonnxruntime,libsherpa-onnx-*,...}.so` 与 `lib/armeabi-v7a/...`（无 x86），`assets/tts/ja_reading_map.json` 存在，无 `assets/pinyin/*`。

- [ ] **Step 5: 提交**

```bash
git add -A app
git commit -m "refactor(tts): 移除旧四模块 ONNX 推理与自写 G2P/拼音资产"
```

---

### Task 10: 真机集成验证（数据/效果已定稿，验证加载/内存/播放）

**Files:** 无代码改动；产出记录写入 `docs/tts/device-test-<日期>.md`

**Interfaces:**
- Consumes: Task 1~9 全部产物
- Produces: 真机验收记录；不通过则回到对应 Task 修

- [ ] **Step 1: 准备设备与模型文件**

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
# 先在设备上建目录，再把 dist 推送过去（约 84MB）
adb shell mkdir -p /data/local/tmp/tts_model/fsts
adb push .tools\tts-spike\dist\model.onnx      /data/local/tmp/tts_model/
adb push .tools\tts-spike\dist\tokens.txt      /data/local/tmp/tts_model/
adb push .tools\tts-spike\dist\lexicon.txt     /data/local/tmp/tts_model/
adb push .tools\tts-spike\dist\fsts\phone.fst  /data/local/tmp/tts_model/fsts/
adb push .tools\tts-spike\dist\fsts\date.fst   /data/local/tmp/tts_model/fsts/
adb push .tools\tts-spike\dist\fsts\number.fst /data/local/tmp/tts_model/fsts/
# 校验大小后拷进 App 私有目录（debug 包可直接 run-as）
adb shell "run-as com.meapet.mobile mkdir -p files/tts_model/fsts"
adb shell "run-as com.meapet.mobile cp /data/local/tmp/tts_model/model.onnx files/tts_model/"
adb shell "run-as com.meapet.mobile cp /data/local/tmp/tts_model/tokens.txt files/tts_model/"
adb shell "run-as com.meapet.mobile cp /data/local/tmp/tts_model/lexicon.txt files/tts_model/"
adb shell "run-as com.meapet.mobile cp /data/local/tmp/tts_model/fsts/phone.fst files/tts_model/fsts/"
adb shell "run-as com.meapet.mobile cp /data/local/tmp/tts_model/fsts/date.fst files/tts_model/fsts/"
adb shell "run-as com.meapet.mobile cp /data/local/tmp/tts_model/fsts/number.fst files/tts_model/fsts/"
adb shell "run-as com.meapet.mobile ls -l files/tts_model files/tts_model/fsts"
```

Expected:`model.onnx` 74864089、`lexicon.txt` 8915316、`tokens.txt` 439、三个 fst 大小匹配（见 Task 2 表）。

- [ ] **Step 2: 启动 App、打开语音开关、点「试听文本（Debug）」逐条验证**

`adb logcat -s TtsManager SherpaTtsEngine TtsAudioPlayer` 观察首次合成日志。

验收（逐条勾）：

- [ ] 首次合成日志出现 `OfflineTts 就绪：sampleRate=22050`，`load=` 毫秒数记录到文档（参考阈值 ≤ 8000ms；超过则记录机型）
- [ ] 试听 `你好喵，今天天气真好。` 正常出声，无尾部截断
- [ ] `我今年18岁，晚上8点30分见面，电话是13800138000。` → 十八岁 / 八点三十分 / 幺三八零零一三八零零零
- [ ] `今天是2026年10月31日，下午3点15分出发。` → 二零二六年十月三十一日
- [ ] `重庆的银行行长说，重要的会议要重新安排，他想再去一次音乐厅。` → 多音字正确（chóng qìng / háng zhǎng / yuè）
- [ ] `こんにちは、今日はいい天気ですね。`（日语，走改写）
- [ ] `私はメアだよ。一緒に遊ぼうね、今日は何をする？`
- [ ] `今天我们去喝coffee，然后看live直播，ok吗？`
- [ ] `hello, nice to meet you.` / `today is a beautiful day, let's go for a walk.`
- [ ] `今天天气不错，いっしょにいこう，let's go！` 中日英混排
- [ ] 连续朗读 10 条后：`adb shell dumpsys meminfo com.meapet.mobile | Select-String TOTAL` 无持续增长、无 OOM 崩溃（记录 PSS）
- [ ] 朗读中点停止/新回复到达：立即停、只有最新一条发声
- [ ] 悬浮窗语音开关同理可读（OverlayMenu 喇叭图标随播放变色）
- [ ] 设置页「删除模型」→ `adb shell "run-as com.meapet.mobile ls files/tts_model"` 目录消失；再「从本地导入」选 `dist/tts-model-v1.zip` 恢复，重试试听成功
- [ ] 覆盖安装旧包（含 4 个 onnx）升级：启动后模型状态为未下载，`files/tts_model` 里旧文件被清空

- [ ] **Step 3: 提交验证记录**

```bash
git add docs/tts/device-test-*.md
git commit -m "docs(tts): 记录 sherpa TTS 真机集成验证结果"
```

---

### Task 11: Release 构建校验（R8 / 16KB / 体积）与版本发布

**Files:**
- Modify: `app/build.gradle.kts`（versionCode/versionName）
- Create/Modify: 版本变更记录（`docs/archive/changelog/` 现有惯例目录，或 `docs/TODO.md` 勾选）

**Interfaces:**
- Consumes: Task 1~10
- Produces: 可分发的 release APK；升级说明

- [ ] **Step 1: 构建分 ABI release**

Run: `.\gradlew.bat :app:assembleRelease -PappAbi=v8a -Pumeng.enabled=false`
Expected: `BUILD SUCCESSFUL`（无签名也能出未签名 APK）。

- [ ] **Step 2: 校验 so 清单、16KB 对齐、体积**

```powershell
$apk = Get-ChildItem app\build\outputs\apk\release\*.apk | Select-Object -First 1
"This APK: {0:N1} MB" -f ((Get-Item $apk).Length / 1MB)
$zipalign = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\zipalign.exe" | Sort-Object FullName -Descending | Select-Object -First 1
# SDK 不在默认路径时，换成 $env:ANDROID_HOME / $env:ANDROID_SDK_ROOT\build-tools\<ver>\zipalign.exe
& $zipalign.FullName -c -P 16 -v 4 $apk.FullName | Select-Object -Last 5
```

Expected：`Verification successful`；APK 对比旧版（旧版 4 ONNX 不打包，仅 so 差异）预计 +6~8MB/ABI，记录到文档。

同时确认 release 里 R8 没裁掉 sherpa 类（构建日志无 `Missing class com.k2fsa` 警告）；若不放心可 `.\gradlew.bat :app:assembleRelease --info | Select-String "com.k2fsa"` 确认无 keep 缺失警告。

- [ ] **Step 3: 版本号与升级说明**

`app/build.gradle.kts`：

```kotlin
        versionCode = 14
        versionName = "1.8.0"
```

在仓库既有变更记录位置（`docs/archive/changelog/` 或 `docs/TODO.md`）加一段：

```markdown
## 1.8.0
- 语音合成迁移到 sherpa-onnx：中日英混排、数字/日期/电话号码按规则朗读。
- 需重新下载语音模型（约 84MB，旧 4 文件模型会被自动清理）；网络受限可用本地 zip 导入。
- 中文声调为模型上限（日语语料微调），与旧版听感一致。
```

- [ ] **Step 4: 全量回归**

Run: `.\gradlew.bat :app:testDebugUnitTest ; .\gradlew.bat :app:lintDebug`
Expected：全部 PASS；lint 无新增 error（AAR 的 `uses-sdk 21` 不应报错）。

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle.kts docs
git commit -m "release: 1.8.0 TTS 迁移 sherpa-onnx"
```

---

## Self-Review 记录

- **Spec 覆盖**：统一模型/统一 lexicon（Task 1/4/5）、App 侧改写（Task 3）、ruleFsts 顺序（Task 5）、模型分发（Task 2/4）、播放器动态采样率（Task 6）、串行合成与打断（Task 7）、设置页与删除/导入（Task 8）、清理旧代码（Task 9）、真机与 release 校验（Task 10/11）。
- **已知不在本计划内**：中文声调准确性（模型语料上限，词表改不动，已与用户确认接受）；流式首句提前播放（未来可选：`generateWithConfigAndCallback` + 分块写 AudioTrack）；`今` 声调修复（需要换中文预训练模型）。
- **类型一致性**：`play(audio, sampleRate)`、`synthesize(text, speed)`、`resolveModelEntries`、`modelDir()`、`speakPreview`、`TtsManager` 构造参数在 Task 3/4/5/6/7/8 之间已对齐。
- **占位符检查**：全部步骤含可直接执行的命令或完整代码；模型包大小已在 Task 2 二次校验并要求以实测为准。
