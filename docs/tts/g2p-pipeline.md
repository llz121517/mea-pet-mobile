# TTS G2P / 词表生成管线

> 代码与完整说明在 [`tools/tts/`](../../tools/tts/README.md)。本文只记录**命令与产物清单**，
> 供集成/发布文档引用。

## 1. 一条命令的复现路径

Windows / PowerShell，Python 3.11（`.tools/tts-venv` 或自建 venv）：

```powershell
.\.tools\tts-venv\Scripts\python.exe -m pip install -r tools/tts/requirements.txt

python tools/tts/download_data.py
python tools/tts/build_tokens.py     --out tools/tts/out
python tools/tts/build_lexicon_zh.py --out tools/tts/out
python tools/tts/build_lexicon_en.py --out tools/tts/out
python tools/tts/build_lexicon_ja.py --out tools/tts/out
python tools/tts/build_bloom.py      --lexicon tools/tts/out/lexicon.txt --out tools/tts/out/en_words.bloom
python tools/tts/verify_assets.py    --assets tools/tts/out
python tools/tts/build_package.py    --assets tools/tts/out --model <model.onnx> --out tools/tts/dist
```

需要联网下载约 25 MB（JMdict / open_jtalk 词典 / 3 个 ruleFsts），URL 与 sha256 见
`tools/tts/data-sources.json`。`model.onnx` 需自备（见 `tools/tts/build_model.md`）。

## 2. 产物清单

| 产物 | 大小 | sha256（已发布） | 断言 |
|---|---|---|---|
| `tokens.txt` | 439 B | `a1511ff2233d31364008ac483ee02d7d60f89c19b507224ef10604938bd68120` | bit-identical ✅ |
| `lexicon.txt` | 8,916,530 B | 发布 `97e2b4fb7da877f399df0fac0aafec82d2b3992a2c988172e95999859f37dfae` / 生成 `261ee0a73af04230b15a4ea0b0fd3564ae7be8787428d219f667b3fde5e73e12` | 内容一致、单字行序不同 ⚠️ |
| `ja_reading_map.json` | 514,556 B | `5d92d21265804b88ffc922abbb1659ea0b3635935ebd045b17ac491febd029b2` | bit-identical ✅ |
| `en_words.bloom` | 524,288 B | `5057e2869e40dc87808db3b60e5ef685f71edc65effc249c524f12c754b34523` | bit-identical ✅ |
| `fsts/phone.fst` | 88,630 B | `1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7` | bit-identical ✅ |
| `fsts/date.fst` | 59,154 B | `eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718` | bit-identical ✅ |
| `fsts/number.fst` | 64,482 B | `743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd` | bit-identical ✅ |
| `model.onnx` | 74,864,089 B | `2d4f52c6b6fdabf62a8b027bdfd53183342c0ddf8b8867bc45edbc6171b026f5` | record-only（不生成） |
| `tts-model-v1.zip` | ≈71 MB | 随打包变化 | 不参与断言 |
| `manifest.json` | 859 B | 随打包变化 | 不参与断言 |

分段条数（lexicon）：zh 单字 41,923 + zh 词组 47,111 + ja 24,616 + en 126,048 + extras 41
= **239,739 行**。

## 3. lexicon 行序差异

发布包的 41,923 条单字由旧脚本用 `set()` 遍历生成，行序是 CPython str hash 随机化的结果，
无法复现；新生成器按 `pypinyin.pinyin_dict` 插入顺序输出。两者行数、字节数、
`sha256(sorted lines)` 完全相同，且 `zh_phrases` / `ja` / `en` / `extras` 四段逐字节一致
（`verify_assets.py --reference <已发布 lexicon>` 可复现该结论）。查找语义与发音不受影响：
同一进程内用两套 lexicon 合成同一句，采样帧数逐一相同。

## 4. 生成器结构

- `build_tokens.py`：`vendor/vits_text/symbols.py` → tokens（`^`/`$`→0、空格写 `␣`）。
- `build_lexicon_zh.py`：pypinyin 单字 + 词组 → BOPOMOFO → IPA（训练同源规则）。
- `build_lexicon_en.py`：specials + CMUdict → IPA（coda-l→`ɫ`）+ 41 条手工 OOV 补条。
- `build_lexicon_ja.py`：JMdict → 假名 → pyopenjtalk IPA2；同时产 `ja_reading_map.json`
  （word / char_full / char_stem 三表，App 侧日语片段改写用）。
- `build_bloom.py`：lexicon 里的小写英文词 → Bloom bitset（与 App `EnglishBloomHash` 同算法）。
- `build_package.py`：6 文件 + zip（保留 `fsts/`）+ manifest。
- `build_samples.py`：13 条综合试听；`--text` 可跑验收句并检查 `Ignore OOV`。
- `verify_assets.py`：按 `tools/tts/artifacts.json` 做三级断言（bit / content / record-only）。

详见 [`tools/tts/README.md`](../../tools/tts/README.md)。
