# model.onnx 从哪里来（**不在 G2P 管线内**）

`model.onnx` 是 Mea 音色的 **VITS** 模型（早期文档误写为 VITS2，2026-11-24 依模型 metadata `model_type=vits` 更正），**不是本管线生成的**，也无法从本仓库的数据重建：
它需要训练侧的 checkpoint `G_latest.pth`（约 39.7M 参数），而该文件既不在仓库里、也不适合入库
（体积 + 训练语料/授权约束）。G2P 管线只负责「模型之外」的全部资产，模型通过 `--model` 传入。

## 已发布产物的指纹

| 属性 | 值 |
|---|---|
| 文件大小 | `74,864,089 B`（约 74.9 MB） |
| sha256 | `2d4f52c6b6fdabf62a8b027bdfd53183342c0ddf8b8867bc45edbc6171b026f5` |
| 图结构 | 混合精度单图：prior encoder / duration predictor 为 fp32，flow / decoder 为 fp16 |
| metadata | `sample_rate=22050, n_speakers=1, speaker_id=0, add_blank=1, blank_id=0, language=Chinese, voice=Mea, comment=icefall, use_eos_bos=0, version=1, model_type=vits, jieba=1` |

该 sha256 记在 [`artifacts.json`](artifacts.json) 的 `model.onnx` 条目里，级别是
`record-only`：`verify_assets.py` 找到就顺带校验，找不到不视为失败。

## 生成路径（一次性离线步骤，需要自备 checkpoint）

1. **自备 checkpoint**：训练产出的 `G_latest.pth`（VITS，Mea 音色，22,050 Hz，中文语料微调）。
2. **严格加载 + 整图导出**：用训练侧的 `vits_core/models_infer.py` 严格加载参数（`strict=True`），
   按 `enc_p → dp → flow → dec` 拆成 4 个 ONNX 子图，其中 enc_p/dp 为 fp32、flow/dec 为 fp16，
   但对外声明 fp32 I/O。
3. **拼接成单图**：在子图边界直接拼接（无需精度转换），并写入上表 metadata。得到
   `model.onnx`（74.9 MB），sherpa-onnx 直接可加载。
4. **数值校验**：ORT vs PyTorch 最大误差约 `3e-5`；sherpa 端用本管线的 `build_samples.py`
   跑 13 条样本确认音色/语速正常。

> 说明：上面 2–3 步的导出/拼接脚本是 spike 期间的一次性脚本（`export_full_onnx.py` /
> `stitch_mixed.py`），**没有归档进本仓库**（它们依赖训练目录与 PyTorch 环境，且已在
> `.tools/tts-spike` 里被 gitignore）。本仓库只固化「产物的指纹 + 接口契约（tokens/lexicon/
> metadata）」，保证 G2P 侧可复现。

## 自备模型怎么用

```powershell
# 打包：把自备模型和生成资产拼成 dist/
python tools/tts/build_package.py --assets tools/tts/out --model <path/to/model.onnx> --out tools/tts/dist

# 试听：直接用自备模型合成 13 条样本
python tools/tts/build_samples.py --model <path/to/model.onnx> --out tools/tts/samples

# 验收：额外合成验收句，日志里不应出现 Ignore OOV
python tools/tts/build_samples.py --model <path/to/model.onnx> --text "今天用 kotlin 写了个 app" --fail-on-oov
```

## 契约约束（换模型时必须同时满足）

- `tokens.txt` 的符号表必须与模型训练时的 `symbols.py` 一致（本管线从
  `vendor/vits_text/symbols.py` 重建；不一致时 `build_tokens.py` 会报错）；
- `lexicon.txt` 的 token 必须都在 `tokens.txt` 里（`verify_assets.py` 不校验这一层，
  但 `build_lexicon_en.py` 会对手工补条做 token 白名单校验）；
- metadata 里的 `add_blank` / `blank_id` / `jieba` 决定 sherpa 前端行为；
- 换 checkpoint 会改变音色与时长，**已发布 lexicon 与 bloom 仍可复用**，但 `model.onnx`
  的 sha256 一定不同，需要同步更新 `artifacts.json`。
