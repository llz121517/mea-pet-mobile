# Third-Party Notices

This project includes third-party components distributed under
their respective licenses. These components are NOT covered by
the MIT license of this project.

---

## Live2D Cubism Framework (Java)

- **Location in this repo:** `app/src/main/java/com/live2d/`
- **Copyright:** Copyright(c) Live2D Inc. All rights reserved.
- **License:** Live2D Open Software License Agreement
- **License text:** `app/src/main/java/com/live2d/LICENSE.md`
- **URL:** https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html
- **Distribution:** bundled in the APK (framework classes are compiled into
  the app; the shader assets are packaged under `assets/`).
- **Notes:** this is the Cubism Java Framework source that ships with the
  Cubism SDK for Java. The SDK's own `LICENSE.md` (copied here) also lists
  the Free Material License sample models (Haru / Hiyori / Mao / Mark /
  Natori / Ren / Rice / Wanko); those sample models are **not** included in
  this repository or in the APK. The SDK release is **Cubism SDK for
  Java 5-r.5**, whose Core reports **6.0.1** at runtime — see item 6 of
  "Previously open questions".

---

## Live2D Cubism Framework Shader Resources

- **Location in this repo:** `app/src/main/assets/com/live2d/`
  (GLSL shader sources under `sdk/cubism/framework/shaders/`)
- **Copyright:** Copyright(c) Live2D Inc. All rights reserved.
- **License:** Live2D Open Software License Agreement
- **License text:** `app/src/main/assets/com/live2d/LICENSE.md`
- **URL:** https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html
- **Notes:** These shader assets ship with the Cubism Java Framework,
  which is part of Live2D Cubism Components and is available under the
  Open Software License (same as the Framework code above). They are
  **not** the sample character models listed under Live2D's Free
  Material License. Only **Cubism Core** is under the Proprietary
  Software License — see the next section.
- **Distribution:** bundled in the APK
  (`assets/com/live2d/sdk/cubism/framework/shaders/standardES/*`).

---

## Live2D Cubism Core (NOT included)

Live2D Cubism Core is **not distributed** in this repository.
It must be downloaded separately from the official Live2D website:

  https://www.live2d.com/download/cubism-sdk/download-java/

Cubism Core is subject to the **Live2D Proprietary Software License
Agreement**, which you must accept directly with Live2D Inc.
when downloading.

  https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html

- **Distribution in the APK:** the Core AAR is required at build time.
  `app/libs/Live2DCubismCore.aar` is git-ignored (`app/libs/.gitignore`),
  so it is not part of this repository, but when present it is packaged
  into the released APK as `lib/<abi>/libLive2DCubismCoreJNI.so` (the
  published APKs (v1.8.0 and v1.9.0) contain that file for `arm64-v8a`).
- **Version:** **Cubism SDK for Java `5-r.5`** (recorded by the
  maintainer), which ships **Cubism Core 6.0.1** — confirmed on a device
  (2026-11-24): `logcat -s Live2d` reports
  `Live2D Cubism Core version: 06.00.0001 (100663297)` and
  `Live2D Cubism SDK Core Version 6.0.1`. Note these are two separate
  version series: `5-r.5` identifies the SDK release, `6.0.1` the Core
  binary inside it. The SDK-5-era features in use (`mea.motionsync3.json`,
  the vendored `effect/` package) are consistent with this release.
- **Notes:** the Core AAR used by this project ships prebuilt JNI
  libraries for `arm64-v8a`, `x86` and `x86_64` only (no `armeabi-v7a`
  binary).

---

## Mea (梅尔) Live2D Character Model

The character Live2D model shipped / used by this app is a community work
and is **not** licensed under this project's MIT license.

- **Source:** Bilibili — [Live2D模型分享 - 梅娅]
- **URL:** https://www.bilibili.com/video/BV1AoX7BXEaN
- **Notes:** Copyright remains with the original author(s). Follow the
  terms stated in the original release when redistributing or reusing
  the model assets.
- **Version:** not recorded (no version string in `mea.model3.json`).
- **License:** no explicit license text is published with the model
  release. Recorded as a **fan-made derivative (二创)** of the character:
  all rights belong to **Cabbage Soft** and this project grants no licence
  for these assets — see "Fan-work statement".
- **Files bundled in the APK:** `app/src/main/assets/live2d/mea_live2d/` —
  `mea.model3.json`, `mea.moc3`, `mea.cdi3.json`, `mea.physics3.json`,
  `mea.motionsync3.json`, `idle_motion.motion3.json`,
  `angry_motion.motion3.json`, `textures/texture_00.png`,
  `textures/texture_01.png`, and `橙色猫猫.can3` (a Cubism Editor
  animation-workspace project file, `.can3` / CAFF archive). Note:
  `mea.model3.json` only references `idle_motion.motion3.json` and
  `angry_motion.motion3.json`; the `.can3` file was not referenced at
  runtime and was a leftover project file, so it has been **removed**
  (see item 2 of "Previously open questions").
- **Distribution:** bundled in the APK.

---

## Material Design Icons (Pictogrammers)

Interface icons converted to `VectorDrawable`.

- **Location in this repo:** `app/src/main/res/drawable/ic_*.xml`
- **Copyright:** Copyright (c) Pictogrammers
- **License:** Apache License 2.0
- **URL:** https://pictogrammers.com/library/mdi/
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Notes:** 35 icons covering the settings screens, main / overlay
  menus and the input bars, converted from Iconify SVG via
  `tools/svg2vd.py` (see `tools/icons.txt`). Apache 2.0 does not require
  attribution, but credit is given on the in-app About page as good
  practice.
- **Distribution:** bundled in the APK as `VectorDrawable` resources.
- **Resolved 2026-11-24:** `ic_menu_compact.xml` (context-compaction menu,
  commit `9a302ba`) was the one icon missing from `tools/icons.txt`; it
  matched `mdi/arrow-collapse` byte-for-byte against the Iconify API and
  has been registered there.

---

## sherpa-onnx / ONNX Runtime (NOT included)

The on-device text-to-speech pipeline uses the **sherpa-onnx** Android AAR
(`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`, see
`gradle/libs.versions.toml`). It is fetched from JitPack at build time and is
**not distributed** in this repository.

- **Project:** https://github.com/k2-fsa/sherpa-onnx
- **Version:** `v2.0.1` (`sherpa-onnx-v2.0.1.aar`).
- **License:** Apache License 2.0
- **Notes:** the AAR bundles prebuilt native libraries, including **ONNX Runtime**
  (MIT License, https://github.com/microsoft/onnxruntime). The repo therefore
  no longer declares an `onnxruntime-android` dependency of its own.
- **Distribution in the APK:** although the AAR is not committed, it is
  downloaded from JitPack at build time and its native libraries are
  bundled into the release APK: `lib/<abi>/libsherpa-onnx-jni.so` and
  `lib/<abi>/libonnxruntime.so`. The bundled ONNX Runtime version is
  **1.28.2** (identified by the `VERS_1.28.2` version node in the
  prebuilt `libonnxruntime.so`). The AAR's `libsherpa-onnx-c-api.so` and
  `libsherpa-onnx-cxx-api.so` are excluded by
  `packaging.jniLibs.excludes` in `app/build.gradle.kts`; the model files
  themselves are still downloaded at runtime (see below).

---

## TTS Model Assets & G2P Data (NOT included)

> **Architecture correction (2026-11-24).** `model.onnx` is a **VITS**
> model, **not VITS2** as earlier revisions of this file and
> `tools/tts/build_model.md` stated. Evidence read straight out of the
> shipped weights: the ONNX metadata says `model_type=vits`, `version=1`,
> `comment=icefall`, and the flow contains **no transformer blocks** —
> the transformer-flow is VITS2's defining change and is absent here.

**The `tts-resource-pack` release mixes two app generations and three
independent origins.** The table below is the authoritative breakdown; do
not assume a file in that release came from this repository.

| Asset in the release | Origin | Version |
|---|---|---|
| `model.onnx` (~75 MB) | **External checkpoint** — not produced by `tools/tts/`. Exported from a trained `G_latest.pth` by slicing into 4 sub-graphs and re-concatenating (see `tools/tts/build_model.md`) | 1.8.0+ |
| `enc_p.onnx` / `dp.onnx` / `flow.onnx` / `dec.onnx` (~73 MB total) | **Legacy 4-module model** of the same external checkpoint, used by 1.7.x and earlier. **Obsolete since 1.8.0**; kept only for old-version compatibility and troubleshooting | 1.7.x |
| `tokens.txt` | **Generated by this repo** — `tools/tts/build_tokens.py`, from the symbol table in `vendor/vits_text/symbols.py` | 1.8.0+ |
| `lexicon.txt` | **Generated by this repo** — `build_lexicon_{zh,en,ja}.py`, from pypinyin word tables + CMUdict (126,037 words, plus 41 manual additions) + JMdict (22,645 words via pyopenjtalk) | 1.8.0+ |
| `ja_reading_map.json`, `en_words.bloom` | **Generated by this repo** — `build_lexicon_ja.py` / `build_bloom.py`; these two are also bundled in the APK | 1.8.0+ |
| `phone.fst` / `date.fst` / `number.fst` | **Downloaded and checksum-verified** by this repo's tooling from the sherpa-onnx / icefall model directory; redistributed unmodified | 1.8.0+ |
| `tts-model-v1.zip` (~71 MB) | **Packaged by this repo** — `tools/tts/build_package.py`, bundling the 6 files the app expects (`model.onnx` + `tokens.txt` + `lexicon.txt` + 3 `.fst`). **This is the file users need for 1.8.0+** | 1.8.0+ |
| `TTS-Resource-Pack.zip` (~82 MB) | **Legacy package** — the same idea for the 4-module model, i.e. 1.7.x layout | 1.7.x |
| `libonnxruntime-arm64-v8a.so`, `libonnxruntime-armeabi-v7a.so` | **Legacy native libraries** for 1.7.x only. 1.8.0+ uses the ONNX Runtime bundled inside the sherpa-onnx AAR and does not need these | 1.7.x |

So: the model weights are external, the G2P assets (`tokens.txt`,
`lexicon.txt`, the derived JSON/bloom files) are generated by this
repository's own scripts, the `.fst` rule files are downloaded rather than
authored here, and the two `.zip` files are packaging artifacts of the two
different app generations. Copyright and licensing of the **model and voice
data** remain with the original author(s); see "Fan-work statement" and the
release notes.
- **Build-time G2P data** (downloaded by `tools/tts/`, datasets themselves not
  committed; see `tools/tts/data-sources.json`):
  - **JMdict** (jmdict-simplified 3.6.2 snapshot, `dictDate=2026-10-12`)
    — © Electronic Dictionary Research and Development Group (EDRDG),
    CC BY-SA 4.0; the jmdict-simplified packaging script is MIT.
  - **open_jtalk 1.11.1 dictionary** — © 名古屋工業大学 / NAIST, distributed
    under a BSD-3-Clause-style license; the bundled `COPYING` also carries
    the UniDic Consortium copyright.
  - **CMUdict** — the pronouncing-dictionary data is BSD-2-Clause
    (© Carnegie Mellon University); the PyPI wrapper package `cmudict`
    (1.1.3) used to read it is **GPL-3.0-or-later** and is only used at
    build time (its code is not copied into any shipped asset).
  - **pypinyin** (0.55.0) word tables — MIT.
  - **`fsts/*.fst`** (rule FSTs for phone/date/number normalization) — Apache-2.0 (sherpa-onnx / icefall model directory)
### Licensing of the TTS model: two separate layers

The model weights are **not** simply "MIT" — the architecture and the
character-voice weights carry different terms. Both must be honoured when
redistributing.

| Layer | What | License | Notes |
|---|---|---|---|
| 1. Architecture / upstream implementation | **VITS** — [jaywalnut310/vits](https://github.com/jaywalnut310/vits) | **MIT** (© 2021 Jaehyeon Kim) | Permissive; covers the model architecture and the upstream code, **not** the weights produced with it |
| 2. Fine-tuned weights and voice data | This project's `model.onnx` / `tts-model-v1.zip`, and the 21 touch-voice clips | **Not open source.** Fan-work derivative of 梅尔·艾什礼佩克 (Mare Ashley-Pecker) | All rights to the character and its voice belong to **Cabbage Soft**. No license is granted to reuse, redistribute or retrain these |

Practical consequence: **you may reuse the VITS architecture under MIT,
but you may not reuse this project's weights or voice clips.** See
"Fan-work statement" below.

- **Vendored VITS front-end code** — `tools/tts/vendor/vits_text/` is a copy of MIT-licensed
  VITS / vits-zh implementations; sources and sha256 are recorded in
  `tools/tts/vendor/vits_text/SOURCE.md`.
- **Derived assets shipped inside the APK** — `app/src/main/assets/tts/ja_reading_map.json`
  (derived from JMdict + the open_jtalk dictionary) and
  `app/src/main/assets/tts/en_words.bloom` (derived from the English entries of
  `lexicon.txt`). Redistributing these derived files should respect the data
  licenses listed above; in particular `ja_reading_map.json` contains
  material derived from JMdict and therefore carries the CC BY-SA 4.0
  attribution / share-alike obligations when redistributed.
- **Distribution:** the `model.onnx` / `lexicon.txt` / `tokens.txt` /
  `fsts/*` model package is **not** in the repository or the APK (the app
  downloads it or imports the zip manually). Only the two derived assets
  above (`ja_reading_map.json`, `en_words.bloom`) are bundled in the APK.
- Full license breakdown: `tools/tts/README.md` §12.

---

## Umeng+ (友盟+) U-APP Statistics SDK (NOT included)

The Umeng+ analytics SDK is integrated as a Gradle dependency
(`com.umeng.umsdk:common` + `com.umeng.umsdk:asms`, see
`gradle/libs.versions.toml`); it is **not distributed** in this
repository and is fetched at build time.

- **Provider:** 友盟同欣（北京）科技有限公司 / Umeng (an Alibaba company)
- **Version:** `com.umeng.umsdk:common` 9.9.2, `com.umeng.umsdk:asms` 1.8.7.2.
- **License:** Proprietary — subject to the Umeng service terms and
  privacy policy, which the app developer accepts when integrating.
- **URL:** https://www.umeng.com/
- **Privacy policy:** https://www.umeng.com/page/policy
- **Notes:** Used only for de-identified usage statistics, and only after
  the user grants consent (first-launch dialog; revocable in Settings →
  About → 隐私与数据). The AppKey is injected via `local.properties`
  (`umeng.appKey`), not hard-coded; forks should supply their own key or
  remove the dependency.
- **Distribution in the APK:** with `umeng.enabled=true` (default) both
  AARs are bundled into the APK (classes plus `lib/<abi>/libumeng-spy.so`);
  with `-Pumeng.enabled=false` they become `compileOnly` and are absent.
- **License metadata discrepancy (handled 2026-11-24):** the Maven POMs
  of both artifacts declare "Apache License 2.0", pointing at a **demo
  repository** of Umeng's own SDK integration samples — not at the SDK
  artifacts themselves. That string is metadata Umeng attached to its
  sample project, and it does not describe the binaries: neither AAR
  contains any `LICENSE`/`NOTICE`/`COPYING` file (only `R.txt` and a
  proguard config). **This project treats the SDK as proprietary** and
  relies on the Umeng service terms + privacy policy — the same terms
  every integrating app accepts when registering for an AppKey. No
  Apache-2.0 permissions are assumed or relied upon.
  *Why no further action is possible:* only Umeng can correct its own
  POM metadata; there is no authoritative open-source licence to point at.
  The practical risk is bounded because the SDK is (a) consent-gated at
  runtime, (b) removable from the build (`-Pumeng.enabled=false`), and
  (c) not modified or redistributed as source by this project.
  **For a strictly permissive-only distribution, build with
  `-Pumeng.enabled=false`.**

---

The sections below cover the remaining third-party components used by the
app. Versions were cross-checked against `gradle/libs.versions.toml`, the
Compose BOM `2026.02.01`, the local Gradle cache
(`~/.gradle/caches/modules-2/files-2.1`), the Maven POM metadata fetched
from Google Maven / Maven Central / JitPack, and the released APKs in
`dist/` (in particular `MeaPet-v1.9.0-v8a.APK`, the newest APK present
when this NOTICE was audited). "Bundled in the APK" is meant to hold for
release builds, where R8 may additionally strip unused code.

---

## JLaTeXMath-Android (ru.noties)

LaTeX rendering for AI replies (`MarkdownText.kt`).

- **Location in this repo:** not vendored — transitive dependency
  `ru.noties:jlatexmath-android:0.2.0` of `io.noties.markwon:ext-latex`
  (see `gradle/libs.versions.toml`).
- **Version:** 0.2.0
- **Author/Org:** Dimitry Ivanov (noties); JLaTeXMath © 2009-2011 Calixte
  Denizet, Kris Coolsaet, Nico Van Cleemput, Kurt Vermeulen and
  Universiteit Gent (2004-2007).
- **License:** GNU General Public License v2 **or later**, with a
  classpath-style **linking exception**. The Maven POM only declares
  "GNU General Public License, version 2"; the exception lives in the
  upstream `LICENSE` file (added by the "Change licence by adding linking
  exception" commit), whose operative paragraph reads:

  > Linking this library statically or dynamically with other modules is
  > making a combined work based on this library. Thus, the terms and
  > conditions of the GNU General Public License cover the whole
  > combination.
  >
  > As a special exception, the copyright holders of this library give
  > you permission to link this library with independent modules to
  > produce an executable, regardless of the license terms of these
  > independent modules, and to copy and distribute the resulting
  > executable under terms of your choice, provided that you also meet,
  > for each linked independent module, the terms and conditions of the
  > license of that module. An independent module is a module which is
  > not derived from or based on this library.

- **Compliance assessment (resolved 2026-11-24):** MeaPet is an
  **independent module** in the sense above — it consumes JLaTeXMath as an
  unmodified Maven artifact (`implementation(...)`, no source changes, no
  copied code), so the APK is a "combined work" whose overall distribution
  terms this project may choose. **MeaPet therefore distributes the APK
  under MIT, and satisfies this library's terms by (a) shipping this
  notice, (b) not modifying the library, and (c) making its source
  available upstream.** The GPL obligations attach to JLaTeXMath itself
  (and its own fonts), not to MeaPet's code.
  *Caveat recorded honestly:* the exception text covers "linking … to
  produce an executable", and Android bundles classes into a single
  `classes.dex` rather than linking separate executables. The upstream
  project and downstream consumers (e.g. Markwon's own `ext-latex`
  artifact, which is Apache-2.0) treat this arrangement as covered; this
  project follows that reading. It remains the one interpretation a
  maintainer/legal review may wish to confirm.
- **URL:** https://github.com/noties/jlatexmath-android
- **License text:** https://github.com/noties/jlatexmath-android/blob/android/LICENSE
- **Distribution:** bundled in the APK — classes plus the
  `org/scilab/forge/jlatexmath/**` assets (TeX symbol/font tables and
  fonts).
- **Notes:** this is the only copyleft component in the shipped app. The
  fonts it bundles are itemised in the next section; the remaining open
  question about the bundled fonts was `jlm_special.ttf`, now resolved in
  "Previously open questions".

---

## LaTeX Fonts bundled with JLaTeXMath-Android

The `jlatexmath-android` AAR ships the glyph fonts used to render
formulas; they are redistributed in the APK under
`assets/org/scilab/forge/jlatexmath/fonts/**`. Licence texts travel with
the AAR in `assets/org/scilab/forge/jlatexmath/fonts/licences/`.

- **How the mapping works:** upstream `LICENSE` lists every font by its
  **pre-rename** filename (e.g. `eufb10.ttf`), while the Android fork
  prefixes all of them with `jlm_`. The table below maps the 30 `.ttf`
  files actually present in the APK back to their upstream group.

  | Upstream group (per JLaTeXMath `LICENSE`) | Files in the APK | License |
  |---|---|---|
  | `eufb10`, `eufm10`, `msam10`, `msbm10` | `jlm_eufb10`, `jlm_eufm10`, `jlm_msam10`, `jlm_msbm10` | **SIL OFL 1.1** (© 1997, 2009 American Mathematical Society; those four are Reserved Font Names) |
  | `dsrom10` | `jlm_dsrom10` | "free" terms: use and distribute as you like; do not rename to Knuth's CM names (`fonts/licences/License_for_dsrom.txt`) |
  | `stmary10`, `rsfs10` | `jlm_stmary10`, `jlm_rsfs10` | **Public domain** |
  | `cmbsy10`, `cmbx10`, `cmbxti10`, `cmex10`, `cmmi10`, `cmr10`, `cmss10`, `cmssbx10`, `cmsy10`, `cmtt10` | `jlm_cmbsy10`, `jlm_cmbx10`, `jlm_cmbxti10`, `jlm_cmex10`, `jlm_cmmi10`, `jlm_cmr10`, `jlm_cmss10`, `jlm_cmssbx10`, `jlm_cmsy10`, `jlm_cmtt10` | **Knuth License** (copy/distribute/modify allowed; modified versions must be renamed — the `jlm_` prefix *is* that rename) |
  | Cyrillic `wnby10`, `wnss10`, `wnti10`, `wnr10`, `wnssi10`, `wnxyti10`, `wnssbx10`, `wntt10` | — (not shipped) | Knuth License |
  | Greek `fcmbipg`, `fcmripg`, `fcmrpg`, `fcsropg`, `fcmbpg`, `fcsbpg`, `fctrpg`, `fcsrpg` | — (not shipped) | GNU GPL v2 |
  | *(not in the upstream list)* | `jlm_cmmib10`, `jlm_cmssi10`, `jlm_cmti10` | **Knuth License** — these are further weights/faces of the Computer Modern family (`cmmib10` bold math-italic, `cmssi10` sans italic, `cmti10` text italic). They are not named individually in the upstream inventory, but the same CM/TFM sources and the same Knuth grant cover them, and CM faces are only modifiable if renamed — which the `jlm_` prefix does |
  | *(not in the upstream list)* | `jlm_jlmbi10`, `jlm_jlmbx10`, `jlm_jlmi10`, `jlm_jlmr10`, `jlm_jlmsb10`, `jlm_jlmsbi10`, `jlm_jlmsi10`, `jlm_jlmss10`, `jlm_jlmtt10` | **JLaTeXMath's own re-cut fonts** — their `name` tables carry **no third-party copyright** (unlike `jlm_cmr10`, which still says `Copyright (C) 1997 American Mathematical Society`), only `Created by calixte with FontForge 2.0` and dates like `jlm_jlm : 29-12-2010`. So they are the fork's own derivations, licensed with JLaTeXMath itself (GPL-2.0-or-later + classpath exception) |
  | *(not in the upstream list)* | `jlm_special` | **JLaTeXMath's own font**, same evidence as the row above: `Created by calixte,,, with FontForge 2.0`, `jlm_special : 14-8-2010`, and **no AMS or other third-party copyright string**. It contains only three glyphs (E/e/m, used for math accents) and is authored by JLaTeXMath's maintainer (Calixte Denizet), so it carries JLaTeXMath's own license rather than a separate font license |

- **URL:** https://github.com/noties/jlatexmath-android
- **Distribution:** bundled in the APK as assets (fonts are also present
  at the APK root `org/scilab/forge/jlatexmath/fonts/`, because the AAR
  ships them as Java resources too).
- **Notes:** the app does not bundle any font files of its own (there is
  no `res/font/` directory and no font file under `app/src/main/assets/`);
  every font glyph shipped in the APK comes from this JLaTeXMath AAR.
- **Resolved 2026-11-24:** `jlm_special.ttf` is no longer an open question
  — see the last row of the table. It is the project's own font, so no
  third-party font license is missing.

---

## Markwon

Markdown rendering for AI replies (code blocks, strikethrough, tables,
links, LaTeX, inline parsing).

- **Location in this repo:** declared in `app/build.gradle.kts` /
  `gradle/libs.versions.toml` (not vendored).
- **Version:** 4.6.2 — modules `io.noties.markwon:core`, `ext-latex`,
  `ext-strikethrough`, `ext-tables`, `linkify`, `inline-parser`.
- **Author/Org:** Dimitry Ivanov (noties).
- **License:** Apache License 2.0.
- **URL:** https://github.com/noties/Markwon
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Distribution:** bundled in the APK.

---

## CommonMark Java

Markdown parser used by Markwon.

- **Location in this repo:** transitive dependency of Markwon
  (`io.noties.markwon:core` / `inline-parser` / `ext-*`), not vendored.
- **Version:** 0.13.0 — `com.atlassian.commonmark:commonmark`,
  `commonmark-ext-gfm-strikethrough`, `commonmark-ext-gfm-tables`.
- **Author/Org:** Atlassian Pty Ltd (© 2015-2016).
- **License:** BSD 2-Clause (per `META-INF/LICENSE.txt` in the jar).
- **URL:** https://github.com/commonmark/commonmark-java
- **Distribution:** bundled in the APK (classes plus
  `org/commonmark/internal/util/entities.properties` at APK root).

---

## Kotlin Standard Library & JetBrains Annotations

- **Version:** `org.jetbrains.kotlin:kotlin-stdlib` 2.2.10 (Kotlin plugin
  version from `gradle/libs.versions.toml`); `org.jetbrains:annotations`
  23.0.0 (transitive).
- **Author/Org:** JetBrains s.r.o.
- **License:** Apache License 2.0.
- **URL:** https://kotlinlang.org/ ·
  https://github.com/JetBrains/java-annotations
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Distribution:** bundled in the APK (`kotlin/**` metadata and
  `DebugProbesKt.bin` markers are present in the published APK).

---

## KotlinX (coroutines / serialization / io)

- **Location in this repo:** declared in `gradle/libs.versions.toml`
  (`kotlinx-coroutines-core/-android/-test`, `kotlinx-serialization-json`).
- **Version (resolved for the current build):**
  - `kotlinx-coroutines-core` **1.10.1** (upgraded from the catalog's
    1.9.0 by Ktor), `kotlinx-coroutines-android` **1.9.0** (catalog
    version; may resolve to the same 1.10.x line as core in a given
    build), `kotlinx-coroutines-slf4j` **1.10.1** (pulled by Ktor)
  - `kotlinx-serialization-core` / `-json` / `-json-io` **1.8.0**
    (upgraded from the catalog's 1.7.3 by Ktor)
  - `kotlinx-io-core` / `kotlinx-io-bytestring` **0.6.0** (pulled by Ktor)
- **Author/Org:** JetBrains s.r.o.
- **License:** Apache License 2.0.
- **URL:** https://github.com/Kotlin/kotlinx.coroutines ·
  https://github.com/Kotlin/kotlinx.serialization ·
  https://github.com/Kotlin/kotlinx-io
- **Distribution:** bundled in the APK.
- **Notes:** versions are the Gradle resolution result; dependency
  upgrades can move them within the same major line.

---

## Ktor Client

HTTP client used by the OpenAI-compatible API client and the update
checker.

- **Location in this repo:** declared in `gradle/libs.versions.toml`
  (the JVM/Android artifacts of the Kotlin Multiplatform modules).
- **Version:** 3.1.0 — `io.ktor:ktor-client-core`, `ktor-client-cio`,
  `ktor-client-content-negotiation`, `ktor-client-logging`,
  `ktor-http`, `ktor-http-cio`, `ktor-events`, `ktor-io`, `ktor-network`,
  `ktor-network-tls`, `ktor-serialization`, `ktor-serialization-kotlinx`,
  `ktor-serialization-kotlinx-json`, `ktor-sse`, `ktor-utils`,
  `ktor-websocket-serialization`, `ktor-websockets`.
- **Author/Org:** JetBrains s.r.o.
- **License:** Apache License 2.0.
- **URL:** https://github.com/ktorio/ktor
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Distribution:** bundled in the APK.
- **Notes:** `ktor-client-core` pulls **SLF4J API** and
  `kotlinx-coroutines-slf4j` (see below).

---

## SLF4J API

Logging facade required by Ktor's JVM implementation.

- **Version:** 2.0.16 (`org.slf4j:slf4j-api`).
- **Author/Org:** QOS.ch (SLF4J authors).
- **License:** MIT.
- **URL:** https://www.slf4j.org/
- **License text:** https://www.slf4j.org/license.html
- **Distribution:** bundled in the APK (compile dependency of
  `ktor-client-core-jvm`). No SLF4J binding is bundled, so logging is a
  no-op at runtime.

---

## Okio

I/O library used by DataStore's okio backend.

- **Version:** 3.4.0 (`com.squareup.okio:okio-jvm`, pulled by
  `androidx.datastore:datastore-core-okio`).
- **Author/Org:** Square, Inc.
- **License:** Apache License 2.0.
- **URL:** https://github.com/square/okio
- **Distribution:** bundled in the APK.

---

## zip4j

AES-256 encrypted zip reading / writing used by the settings →「数据与备份」
export and import flow (`app/src/main/java/com/meapet/mobile/backup/`).

- **Location in this repo:** not vendored — declared in
  `gradle/libs.versions.toml` (not embedded source).
- **Version:** 2.11.5 (`net.lingala.zip4j:zip4j`, see
  `gradle/libs.versions.toml`).
- **Copyright:** Copyright (c) Srikanth Reddy Lingala (zip4j project
  contributors).
- **License:** Apache License 2.0.
- **URL:** https://github.com/srikanth-lingala/zip4j
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Distribution:** bundled in the APK.
- **Notes:** used only to write / read the AES-256 encrypted backup package
  (settings, chat history, memory, compaction state and wallpaper). It
  performs no network access; the app never uploads the backup package.

---

## JSpecify & Guava ListenableFuture

Small annotation / utility artifacts pulled in by AndroidX.

- **JSpecify** — `org.jspecify:jspecify:1.0.0`; JSpecify project
  contributors; Apache License 2.0; https://jspecify.org/ ; bundled in
  the APK.
- **Guava ListenableFuture** — `com.google.guava:listenablefuture:1.0`
  (the `ListenableFuture` interface only, part of Guava); © Google LLC;
  Apache License 2.0; https://github.com/google/guava ; bundled in the
  APK.

---

## AndroidX / Jetpack

Core Android support libraries used throughout the app.

- **Author/Org:** The Android Open Source Project (Google LLC).
- **License:** Apache License 2.0.
- **URL:** https://developer.android.com/jetpack
- **License text:** https://www.apache.org/licenses/LICENSE-2.0 (each AAR
  also ships its own `LICENSE.txt`, e.g.
  `META-INF/androidx.core_core/LICENSE.txt` inside the APK).
- **Distribution:** bundled in the APK.
- **Version note:** versions below are the resolution results of the
  current build (catalog + transitive upgrades; see
  `gradle/libs.versions.toml` and the local Gradle cache). A dependency
  upgrade can move them.

| Artifact | Version |
|---|---|
| `androidx.activity:activity` / `activity-ktx` / `activity-compose` | 1.8.2 |
| `androidx.annotation:annotation` (annotation-jvm) | 1.9.1 |
| `androidx.annotation:annotation-experimental` | 1.4.1 |
| `androidx.arch.core:core-common` / `core-runtime` | 2.2.0 |
| `androidx.autofill:autofill` | 1.0.0 |
| `androidx.collection:collection` / `collection-ktx` | 1.5.0 |
| `androidx.concurrent:concurrent-futures` | 1.1.0 |
| `androidx.core:core` / `core-ktx` / `core-viewtree` | 1.16.0 / 1.16.0 / 1.0.0 |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 |
| `androidx.datastore:datastore`, `-core`, `-core-okio`, `-preferences`, `-preferences-core` | 1.1.1 |
| `androidx.documentfile:documentfile` | 1.0.0 |
| `androidx.dynamicanimation:dynamicanimation` | 1.0.0 |
| `androidx.emoji2:emoji2` | 1.4.0 |
| `androidx.graphics:graphics-path` | 1.0.1 |
| `androidx.interpolator:interpolator` | 1.0.0 |
| `androidx.legacy:legacy-support-core-utils` | 1.0.0 |
| `androidx.lifecycle:*` (common, common-java8, livedata, livedata-core, process, runtime, runtime-compose, runtime-ktx, viewmodel, viewmodel-compose, viewmodel-ktx, viewmodel-savedstate) | 2.9.4 |
| `androidx.loader:loader` | 1.0.0 |
| `androidx.localbroadcastmanager:localbroadcastmanager` | 1.0.0 |
| `androidx.print:print` | 1.0.0 |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 |
| `androidx.savedstate:savedstate` / `-compose` / `-ktx` | 1.3.2 |
| `androidx.sqlite:sqlite` / `sqlite-framework` | 2.7.1 |
| `androidx.startup:startup-runtime` | 1.1.1 |
| `androidx.tracing:tracing` | 1.2.0 |
| `androidx.transition:transition` | 1.6.0 |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 |
| `androidx.window:window` / `window-core` | 1.5.0 |

---

## Jetpack Compose

- **Author/Org:** The Android Open Source Project (Google LLC).
- **License:** Apache License 2.0.
- **URL:** https://developer.android.com/jetpack/compose
- **Version:** governed by the Compose BOM
  `androidx.compose:compose-bom:2026.02.01`
  (`gradle/libs.versions.toml`).

| Artifact | Version |
|---|---|
| `androidx.compose.animation:animation` / `animation-core` | 1.10.4 |
| `androidx.compose.foundation:foundation` / `foundation-layout` | 1.10.4 |
| `androidx.compose.material:material` | 1.10.4 |
| `androidx.compose.material:material-ripple` | 1.10.4 |
| `androidx.compose.material:material-icons-core` / `material-icons-extended` | 1.7.8 |
| `androidx.compose.material3:material3` | 1.4.0 |
| `androidx.compose.runtime:runtime`, `runtime-annotation`, `runtime-retain`, `runtime-saveable` | 1.10.4 |
| `androidx.compose.ui:ui`, `ui-geometry`, `ui-graphics`, `ui-text`, `ui-unit`, `ui-util`, `ui-tooling-preview` | 1.10.4 |

- **Distribution:** bundled in the APK.
- **Notes:** `material-icons-extended` supplies the built-in Material
  icons (Apache-2.0). `androidx.compose.ui:ui-tooling` and
  `ui-tooling-data` are debug-only and `ui-test-junit4` is test-only —
  they are not packaged into release APKs.

---

## Touch Voice Assets (触摸语音)

The app plays 21 pre-recorded Japanese touch-reaction lines.

- **Location in this repo:** `app/src/main/assets/voice/{upper,lower_left,lower_right}/*.ogg`
  (21 files, 48 kHz mono Opus).
- **Source:** generated with a **GPT-SoVITS** model fine-tuned on the
  character's voice. These are **fan-made derivative works (二创)** of
  the character 梅尔·艾什礼佩克 (Mare Ashley-Pecker).
- **Author/Org:** the recordings were produced for this project; the
  underlying character and its voice are the property of
  **Cabbage Soft** (developers/publishers of
  《霞流宝石心 -壮志凌云振寰宇-》 / ジュエリー・ハーツ・アカデミア
  -We will wing wonder world-, 2022).
- **License / rights:** **All rights to the character, its likeness and
  its voice remain with Cabbage Soft.** These clips are **not** covered
  by this repository's MIT license, and no license is granted to reuse,
  redistribute or train on them. They are bundled only because this app
  is a non-commercial fan work that displays the same character; anyone
  forking the app should **remove them or obtain their own permission**
  from the rights holder.
- **Distribution:** bundled in the APK.
- **Notes:** the WAV originals were re-encoded to Opus by
  `tools/encode-voice-assets.ps1` (ffmpeg/libopus is used at build time
  only and is not shipped). A distribution notice also ships as a
  release asset — see "Fan-work statement" below.

---

## Build & test-only dependencies (not distributed)

These are not part of the APK; they are listed for completeness. Their
licenses apply only to the development process.

### Android build tooling

| Component | Version | License | URL |
|---|---|---|---|
| Gradle (incl. wrapper) | 9.5.0 | Apache-2.0 | https://gradle.org/ |
| Android Gradle Plugin (AGP) | 9.3.0 | Apache-2.0 | https://developer.android.com/build |
| Kotlin Gradle plugin / compiler | 2.2.10 | Apache-2.0 | https://kotlinlang.org/ |
| Foojay toolchains resolver | 1.0.0 | Apache-2.0 | https://github.com/gradle/foojay-toolchains |
| GitHub Actions (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4`) | — | MIT | https://github.com/actions |
| Android SDK / JDK 21 toolchain | — | Android SDK Terms / GPL-2.0+CE | — |

### JVM unit / instrumentation tests

| Component | Version | License |
|---|---|---|
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| Hamcrest (core / library / integration) | 1.3 | BSD 3-Clause |
| Mockito / Mockito-Kotlin | 5.12.0 / 5.4.0 | MIT |
| Byte Buddy / Byte Buddy Agent | 1.14.15 | Apache-2.0 |
| Objenesis | 3.3 | Apache-2.0 |
| Xerial sqlite-jdbc | 3.49.1.0 | Apache-2.0 |
| kotlin-test / kotlinx-coroutines-test | 2.2.10 / 1.9.0-1.10.1 | Apache-2.0 |
| AndroidX Test (ext-junit 1.1.5, runner 1.5.2, monitor 1.6.1, core 1.5.0, annotation 1.0.1, services-storage 1.4.2) | — | Apache-2.0 |
| Espresso (espresso-core / espresso-idling-resource) | 3.5.1 | Apache-2.0 |
| Compose UI test / tooling | 1.10.4 | Apache-2.0 |

### Python G2P pipeline (`tools/tts/`, Python 3.11)

Also not distributed; used to regenerate the TTS lexicon / token / lookup
assets.

| Package | Version | License |
|---|---|---|
| pypinyin | 0.55.0 | MIT |
| cmudict (PyPI wrapper) | 1.1.3 | GPL-3.0-or-later |
| pyopenjtalk-prebuilt | 0.3.0 | MIT (bundles HTS Engine (modified BSD) and open_jtalk (MIT)) |
| numpy | 1.26.4 | BSD-3-Clause |
| sherpa-onnx (Python) | 2.0.1 | Apache-2.0 |
| soundfile | 0.14.0 | BSD-3-Clause (bundles libsndfile, LGPL-2.1) |
| eng-to-ipa | 0.0.2 | MIT |
| inflect | 7.6.0 | MIT |
| Unidecode | 1.4.0 | GPL-2.0-or-later |
| jieba | 0.42.1 | MIT |
| cn2an | 0.5.24 | MIT |

---

## Previously open questions (已解决清单)

This section used to list items that could not be confirmed from the
repository, the build metadata, the cached POMs or the published APKs.
**As of 2026-11-24 all eight are closed** — each entry below keeps the
original question, the evidence that closed it, and what (if anything)
still depends on a third party. It is retained as an audit trail, not as
a list of open risks.

> **2026-11-24 re-audit** (`dist/MeaPet-v1.9.0-v8a.APK`, unpacked with
> `aapt list`): **all eight items are now resolved or handled.** Items 1,
> 2, 3, 6, 8 were closed earlier (provenance recorded, orphan removed,
> icon registered, SDK version recorded, model rights recorded); items 4
> (JLaTeXMath GPL + linking exception), 5 (Umeng metadata) and 7
> (`jlm_special.ttf`) were closed in this pass — 4 and 7 by retrieving the
> upstream media and reading the actual licence/font metadata, 5 by
> establishing that the Apache-2.0 POM string belongs to Umeng's demo
> project rather than to the SDK binaries.
>
> **One interpretation caveat remains on item 4** (GPL "linking" vs
> Android's single-`classes.dex` packaging); it does not block shipping and
> is recorded in the JLaTeXMath section. Item 5 cannot be fixed from this
> side at all — only Umeng can correct its metadata.

1. ~~**Touch voice assets**~~ **Resolved 2026-11-24** — 21 files
   (225 KB) bundled in the APK; codec verified as **Opus** (`OpusHead`
   magic at offset 28, `.ogg` is only the container). Provenance is now
   recorded: generated with a **GPT-SoVITS** model fine-tuned on the
   character's voice, as **fan-made derivative works (二创)**. All rights
   to the character and voice belong to **Cabbage Soft**
   (《霞流宝石心 -壮志凌云振寰宇-》). **The clips are therefore not MIT
   and are not licensed for reuse** — see the "Touch Voice Assets"
   section above and the release-asset fan-work statement.
2. ~~**Mea Live2D model assets**~~ **Resolved 2026-11-24** —
   `app/src/main/assets/live2d/mea_live2d/**` now holds **9 files** in the
   APK (moc3, model3.json, physics3, motionsync3, cdi3, 2 motions,
   2 textures). Provenance: **community work shared on Bilibili, a fan-made
   derivative (二创) of the character** — so the same rights position as
   items 1 and 8 applies: all rights to the character and its likeness
   belong to **Cabbage Soft**, are not covered by this repository's MIT
   licence, and this project grants no licence for them (see "Fan-work
   statement").
   **Orphan removed in the same pass:** `橙色猫猫.can3` (223 KB, Cubism
   Editor project file) was tracked in git and shipped in the APK while
   nothing referenced it — `mea.model3.json` does not mention it and no
   code loaded it — so it was deleted. Rebuilt and verified on a device:
   `Live2d: 模型目录预热完成：9 个文件`, model loads normally.
3. ~~**`ic_menu_compact.xml`**…~~ **Resolved 2026-11-24:** the icon is
   `mdi/arrow-collapse` (path data matched against the Iconify API) and is
   now registered in `tools/icons.txt`. It remains bundled in the APK as
   before, with its license covered by the Material Design Icons entry
   above (Apache-2.0).
4. ~~**JLaTeXMath-Android GPL-2.0+ / classpath exception**~~ **Resolved
   2026-11-24.** The upstream `LICENSE` was retrieved in full (via the
   GitHub contents API, `noties/jlatexmath-android@android`) and it **does**
   carry the linking exception, verbatim quoted in the "JLaTeXMath-Android"
   section above. MeaPet uses the library unmodified as an independent
   module (Maven `implementation`, no source copied), which is exactly what
   the exception contemplates: the combined work may be distributed under
   terms of the redistributor's choice, provided each linked module's own
   terms are met. **This project therefore ships the APK under MIT and
   discharges the GPL obligations for JLaTeXMath itself via this notice,
   the unmodified artifact, and upstream source availability.**
   One interpretation caveat is recorded in that section (Android bundles
   classes into `classes.dex` rather than linking executables; the
   upstream project and Markwon's own Apache-2.0 `ext-latex` treat that as
   covered). No further action is required to ship, though a legal review
   may confirm the reading.
5. ~~**Umeng SDK license metadata**~~ **Handled 2026-11-24.** The POMs'
   "Apache License 2.0" string points at Umeng's **demo repository** (their
   own integration samples), not at the SDK binaries, so it is metadata
   about the sample project rather than a grant covering the AARs. Neither
   AAR contains any licence file (only `R.txt` and proguard rules).
   **This project treats the SDK as proprietary**, relies on the Umeng
   service terms + privacy policy, and assumes no Apache-2.0 permissions.
   Only Umeng can fix its own metadata, so no further action is possible on
   our side; risk is bounded because the SDK is consent-gated, removable
   from the build (`-Pumeng.enabled=false`), and never modified or
   redistributed as source. **Build with `-Pumeng.enabled=false` if a
   strictly permissive-only artifact is required.**
6. ~~**Live2D Cubism SDK / Cubism Core version**~~ **Resolved
   2026-11-24:** the SDK release is **Cubism SDK for Java `5-r.5`**
   (recorded by the maintainer), which ships **Cubism Core 6.0.1** —
   verified on a device via `logcat -s Live2d`
   (`Live2D Cubism Core version: 06.00.0001 (100663297)`,
   `Live2D Cubism SDK Core Version 6.0.1`). Two distinct version series:
   `5-r.5` = SDK release, `6.0.1` = Core binary inside it. They are not
   contradictory; `app/libs/Live2DCubismCore.aar` merely carries no
   version string because the Core version is read at runtime over JNI
   (`CubismCoreVersion`). SDK-5-era features in use
   (`mea.motionsync3.json`, vendored `effect/` package) match `5-r.x`.
7. ~~**`jlm_special.ttf`**~~ **Resolved 2026-11-24.** The font is
   **JLaTeXMath's own artwork**, not a third-party typeface: its `name`
   table reads `Created by calixte,,, with FontForge 2.0`,
   `jlm_special : 14-8-2010`, `Version 001.000`, and — unlike the real
   Computer Modern files (e.g. `jlm_cmr10`, which still carries
   `Copyright (C) 1997 American Mathematical Society`) — it contains **no
   third-party copyright string**. It holds three glyphs (E/e/m, for math
   accents). It is therefore covered by **JLaTeXMath's own licence**
   (GPL-2.0-or-later with the linking exception) and needs no separate
   font licence. The same reasoning resolves the fork's own re-cut faces
   (`jlm_jlm*.ttf`); the full 30-file mapping is in the "LaTeX Fonts"
   section.
8. ~~**Mea TTS model / voice training data**~~ **Resolved 2026-11-24** —
   the 6 model files (`model.onnx`, `lexicon.txt`, `tokens.txt`,
   `fsts/*.fst`) are neither in the repo nor in the APK (only the two
   derived G2P assets ship — see "TTS Model Assets"); they are distributed
   as `tts-model-v1.zip` on the `tts-resource-pack` release. Provenance is
   now recorded: a **VITS** model trained on the character's voice — a
   **fan-made derivative work (二创)**. All rights to the character and
   voice belong to **Cabbage Soft**, so the weights are **not MIT** and no
   license is granted to reuse or retrain them. The architecture is
   **VITS** (not VITS2) — see the architecture note in "TTS Model Assets".

---

## Fan-work statement (二创声明)

MeaPet is an **unofficial fan work** based on
《霞流宝石心 -壮志凌云振寰宇-》 (ジュエリー・ハーツ・アカデミア
-We will wing wonder world-, Cabbage Soft, 2022), produced for
non-commercial personal use. It contains **no game assets** from the
original work.

**All rights to the character 梅尔·艾什礼佩克 (Mare Ashley-Pecker), its
likeness and its voice belong to Cabbage Soft.** Consequently the
following shipped/generated assets are **not** covered by this
repository's MIT license. **This project grants no license for them** —
the rights are held by Cabbage Soft, so whether they may be reused,
redistributed or trained on is for the rights holder to decide:

| Asset | Where it lives | Produced with |
|---|---|---|
| 21 touch-voice Opus clips | `app/src/main/assets/voice/**` (in the APK) | GPT-SoVITS fine-tune |
| TTS model weights | `tts-model-v1.zip` on the `tts-resource-pack` release (not in the repo or APK) | VITS |
| Live2D model assets | `app/src/main/assets/live2d/mea_live2d/**` (in the APK) | community work (Bilibili) |

The full statement — including what this project asks users not to do,
the note that it is a provenance notice rather than a license grant, and
the takedown contact — is published as a release asset and kept in the
repo at [`docs/FAN-WORK-NOTICE.md`](docs/FAN-WORK-NOTICE.md).