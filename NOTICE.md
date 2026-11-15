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

---

## Live2D Cubism Core (NOT included)

Live2D Cubism Core is **not distributed** in this repository.
It must be downloaded separately from the official Live2D website:

  https://www.live2d.com/download/cubism-sdk/download-java/

Cubism Core is subject to the **Live2D Proprietary Software License
Agreement**, which you must accept directly with Live2D Inc.
when downloading.

  https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html

---

## Mea (梅尔) Live2D Character Model

The character Live2D model shipped / used by this app is a community work
and is **not** licensed under this project's MIT license.

- **Source:** Bilibili — [Live2D模型分享 - 梅娅]
- **URL:** https://www.bilibili.com/video/BV1AoX7BXEaN
- **Notes:** Copyright remains with the original author(s). Follow the
  terms stated in the original release when redistributing or reusing
  the model assets.

---

## Material Design Icons (Pictogrammers)

Interface icons converted to `VectorDrawable`.

- **Location in this repo:** `app/src/main/res/drawable/ic_*.xml`
- **Copyright:** Copyright (c) Pictogrammers
- **License:** Apache License 2.0
- **URL:** https://pictogrammers.com/library/mdi/
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Notes:** 33 icons covering the settings screens, main / overlay
  menus and the input bars, converted from Iconify SVG via
  `tools/svg2vd.py` (see `tools/icons.txt`). Apache 2.0 does not require
  attribution, but credit is given on the in-app About page as good
  practice.

---

## sherpa-onnx / ONNX Runtime (NOT included)

The on-device text-to-speech pipeline uses the **sherpa-onnx** Android AAR
(`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`, see
`gradle/libs.versions.toml`). It is fetched from JitPack at build time and is
**not distributed** in this repository.

- **Project:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache License 2.0
- **Notes:** the AAR bundles prebuilt native libraries, including **ONNX Runtime**
  (MIT License, https://github.com/microsoft/onnxruntime). The repo therefore
  no longer declares an `onnxruntime-android` dependency of its own.

---

## TTS Model Assets & G2P Data (NOT included)

- **Mea VITS2 model weights** — `model.onnx` (~75 MB), together with
  `lexicon.txt` / `tokens.txt` / `fsts/{phone,date,number}.fst`, is packaged as
  `tts-model-v1.zip` (6 files, ~84 MB) and distributed as a separate GitHub
  Release asset. It is **not** in this repository; copyright and licensing of the
  model and voice data remain with the original author(s) — see the release notes.
- **Build-time G2P data** (downloaded by `tools/tts/`, datasets themselves not
  committed):
  - **JMdict** (jmdict-simplified) — © EDRDG, CC BY-SA 4.0
  - **open_jtalk 1.11 dictionary** — © 名古屋工業大学 / NAIST (see `COPYING` in the dictionary package)
  - **CMUdict** (via the `cmudict` package) — BSD-2-Clause
  - **pypinyin** word tables — MIT
  - **`fsts/*.fst`** (rule FSTs for phone/date/number normalization) — Apache-2.0 (sherpa-onnx / icefall model directory)
- **Vendored VITS front-end code** — `tools/tts/vendor/vits_text/` is a copy of MIT-licensed
  VITS / vits-zh implementations; sources and sha256 are recorded in
  `tools/tts/vendor/vits_text/SOURCE.md`.
- **Derived assets shipped inside the APK** — `app/src/main/assets/tts/ja_reading_map.json`
  (derived from JMdict + the open_jtalk dictionary) and
  `app/src/main/assets/tts/en_words.bloom` (derived from the English entries of
  `lexicon.txt`). Redistributing these derived files should respect the data
  licenses listed above.
- Full license breakdown: `tools/tts/README.md` §12.

---

## Umeng+ (友盟+) U-APP Statistics SDK (NOT included)

The Umeng+ analytics SDK is integrated as a Gradle dependency
(`com.umeng.umsdk:common` + `com.umeng.umsdk:asms`, see
`gradle/libs.versions.toml`); it is **not distributed** in this
repository and is fetched at build time.

- **Provider:** 友盟同欣（北京）科技有限公司 / Umeng (an Alibaba company)
- **License:** Proprietary — subject to the Umeng service terms and
  privacy policy, which the app developer accepts when integrating.
- **URL:** https://www.umeng.com/
- **Privacy policy:** https://www.umeng.com/page/policy
- **Notes:** Used only for de-identified usage statistics, and only after
  the user grants consent (first-launch dialog; revocable in Settings →
  About → 隐私与数据). The AppKey is injected via `local.properties`
  (`umeng.appKey`), not hard-coded; forks should supply their own key or
  remove the dependency.
