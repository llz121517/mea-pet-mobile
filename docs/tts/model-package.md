# TTS 模型包（sherpa-onnx）

> **仅覆盖 1.8.0+ 的单图模型包。** `tts-resource-pack` release 里还并存着 1.7.x 的旧版
> 4 模块模型与旧 native 库，它们不在本文范围内；两者区别见下方「release 里的其它文件」。

## 文件清单

| 文件 | 大小（B） | sha256 | 来源 |
|---|---|---|---|
| model.onnx | `74864089` | `2d4f52c6b6fdabf62a8b027bdfd53183342c0ddf8b8867bc45edbc6171b026f5` | **外部 checkpoint** 导出，非本仓库管线生成 |
| lexicon.txt | `8916530` | `97e2b4fb7da877f399df0fac0aafec82d2b3992a2c988172e95999859f37dfae` | `build_lexicon_{zh,en,ja}.py` 生成 |
| tokens.txt | `439` | `a1511ff2233d31364008ac483ee02d7d60f89c19b507224ef10604938bd68120` | `build_tokens.py` 生成 |
| fsts/phone.fst | `88630` | `1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7` | 从 sherpa-onnx / icefall 模型目录**下载并校验**，非本项目编写 |
| fsts/date.fst | `59154` | `eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718` | 同上 |
| fsts/number.fst | `64482` | `743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd` | 同上 |

合计 83,993,324 B（约 84MB）；`tts-model-v1.zip` 实测 71,137,797 B（约 71MB）。`ja_reading_map.json`（515KB）与 `en_words.bloom`（512KB，英文 OOV 判定）同样由 `tools/tts/` 生成，但**不进模型包**，随 APK assets 分发。

## release 里的其它文件（不属于 1.8.0+ 模型包）

`tts-resource-pack` release 是随版本演进累积的，同一个 release 里放着两个世代的产物：

| 文件 | 世代 | 用途 / 来源 |
|---|---|---|
| `enc_p.onnx` / `dp.onnx` / `flow.onnx` / `dec.onnx` | **1.7.x 旧版** | 同一 checkpoint 的 4 模块拆分形态（约 73MB 合计）。1.8.0 起废弃，仅留作旧版本兼容与排障 |
| `TTS-Resource-Pack.zip`（约 82MB） | **1.7.x 旧版** | 4 模块模型的打包产物 |
| `libonnxruntime-arm64-v8a.so` / `libonnxruntime-armeabi-v7a.so` | **1.7.x 旧版** | 旧版自带 ORT。1.8.0+ 用 sherpa-onnx AAR 内置的 ONNX Runtime，不需要它们 |
| `FAN-WORK-NOTICE.md` | 声明 | 二创与使用限制声明，见 [`docs/FAN-WORK-NOTICE.md`](../FAN-WORK-NOTICE.md) |

**各版本该下哪个**：1.8.0+（含 1.9.0）用 `tts-model-v1.zip`，或 `model.onnx` + `lexicon.txt` + `tokens.txt` + 3 个 `.fst` 裸文件；1.7.x 及更早用 `TTS-Resource-Pack.zip`。从 1.7.x 升级到 1.8.0+ 时 App 会自动清理旧版 4 模块模型并关闭两个语音开关（见 [`legacy-compat.md`](../legacy-compat.md)）。

## 分发方式

App 支持两种：

1. **在线下载**：把 6 个文件按「上传名」平铺上传到 `app.ttsModelBaseUrl` 指向的静态目录 / GitHub Release：
   `model.onnx`、`lexicon.txt`、`tokens.txt`、`phone.fst`、`date.fst`、`number.fst`
   （`fsts/` 只保留 App 本地目录结构，远端是平铺名，见 TtsModelManager.buildDownloadFiles）。
2. **本地 zip 导入**：直接用 `tools/tts/dist/tts-model-v1.zip`（`build_package.py --out tools/tts/dist` 的产物），设置页「从本地导入」。

## 重新生成

```powershell
python tools/tts/download_data.py
python tools/tts/build_tokens.py     --out tools/tts/out
python tools/tts/build_lexicon_zh.py --out tools/tts/out
python tools/tts/build_lexicon_en.py --out tools/tts/out
python tools/tts/build_lexicon_ja.py --out tools/tts/out
python tools/tts/build_bloom.py      --lexicon tools/tts/out/lexicon.txt --out tools/tts/out/en_words.bloom
python tools/tts/verify_assets.py    --assets tools/tts/out
python tools/tts/build_package.py    --assets tools/tts/out --model <path/to/model.onnx> --out tools/tts/dist
```

G2P / 词表生成管线已从 `.tools/tts-spike/scripts/` 收编到 `tools/tts/`（新 clone + 联网下载
即可复现）：命令与产物清单见 [`g2p-pipeline.md`](g2p-pipeline.md)，细节与已知差异见
[`tools/tts/README.md`](../../tools/tts/README.md)。`model.onnx` 不在该管线内（需要外部训练
checkpoint），来源说明见 [`tools/tts/build_model.md`](../../tools/tts/build_model.md)。
