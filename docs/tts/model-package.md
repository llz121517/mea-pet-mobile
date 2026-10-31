# TTS 模型包（sherpa-onnx）

## 文件清单

| 文件 | 大小（B） | sha256 |
|---|---|---|
| model.onnx | `74864089` | `2d4f52c6b6fdabf62a8b027bdfd53183342c0ddf8b8867bc45edbc6171b026f5` |
| lexicon.txt | `8915316` | `3f46c6ed7218ebdde5e188484053c448a0803f315b5f3d6f7891efe48ba582fa` |
| tokens.txt | `439` | `a1511ff2233d31364008ac483ee02d7d60f89c19b507224ef10604938bd68120` |
| fsts/phone.fst | `88630` | `1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7` |
| fsts/date.fst | `59154` | `eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718` |
| fsts/number.fst | `64482` | `743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd` |

合计 83,992,110 B（约 84MB）；`tts-model-v1.zip` 实测 71,137,348 B（约 71MB）。`ja_reading_map.json`（515KB）不进模型包，随 APK assets 分发。

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
