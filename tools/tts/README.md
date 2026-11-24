# tools/tts · TTS G2P / 词表生成管线

把 Mea TTS（sherpa-onnx + 自有 VITS 模型）在「模型之外」用到的全部资产，从一次性 spike
脚本收编成**可在新 clone 上重跑**的管线：

> 新 clone + 联网下载 + 下面几条命令 = 可重新生成全部 G2P 资产（tokens / lexicon /
> ja_reading_map / en_words.bloom / fsts / dist 包 / 13 条试听）。

`model.onnx` **不在**本管线内（需要外部训练 checkpoint），见 [build_model.md](build_model.md)。

## 1. 范围

| | 内容 |
|---|---|
| **本管线生成** | `tokens.txt`、`lexicon.txt`（中/英/日）、`ja_reading_map.json`、`en_words.bloom`、`dist/`（6 文件 + `tts-model-v1.zip` + `manifest.json`）、13 条综合试听 wav |
| **本管线下载+校验** | `fsts/{phone,date,number}.fst`、JMdict 常用词、open_jtalk 1.11 词典 |
| **不在本管线** | `model.onnx`（外部 checkpoint `G_latest.pth` + 4 模块拼接，见 build_model.md） |

## 2. 新 clone 最短复现（Windows / PowerShell）

> **需要联网**（约 25 MB：JMdict zip 1.4 MB + open_jtalk 词典 tar.gz 22.6 MB + 3 个 fst 205 KB）。
> 数据源 URL / sha256 全部 pin 在 [data-sources.json](data-sources.json)。

```powershell
# 0) Python 3.11 虚拟环境（只需一次；也可以复用已有 venv）
python -m venv .tools/tts-venv
.\.tools\tts-venv\Scripts\python.exe -m pip install -r tools/tts/requirements.txt

# 1) 下载并校验外部数据（已有且 sha 正确会自动跳过）
python tools/tts/download_data.py

# 2) 按顺序生成资产
python tools/tts/build_tokens.py     --out tools/tts/out
python tools/tts/build_lexicon_zh.py --out tools/tts/out
python tools/tts/build_lexicon_en.py --out tools/tts/out
python tools/tts/build_lexicon_ja.py --out tools/tts/out   # 同时产 ja_reading_map.json
python tools/tts/build_bloom.py      --lexicon tools/tts/out/lexicon.txt --out tools/tts/out/en_words.bloom

# 3) 与已发布包对比（artifacts.json 是基准）
python tools/tts/verify_assets.py --assets tools/tts/out

# 4) 打包（model.onnx 需自备，见 build_model.md）
python tools/tts/build_package.py --assets tools/tts/out --model <path/to/model.onnx> --out tools/tts/dist

# 5) 试听（可选，13 条；模型同上）
python tools/tts/build_samples.py --model <path/to/model.onnx> --out tools/tts/samples
```

**本机实测耗时**（Windows 11 / CPython 3.11.9 / 冷 `out/`，不含 `pip install`）：

| 步骤 | 耗时 |
|---|---|
| `download_data.py`（冷启动、走 GitHub + HuggingFace） | **7.6 s** |
| `build_tokens.py` | 0.1 s |
| `build_lexicon_zh.py`（41,923 单字 + 47,111 词组） | 12.1 s |
| `build_lexicon_en.py`（specials + 126,037 CMUdict 词 + 41 条补条） | 5.0 s |
| `build_lexicon_ja.py`（22,645 JMdict 词 → pyopenjtalk） | 4.6 s |
| `build_bloom.py` | 1.1 s |
| **生成合计（tokens/zh/en/ja/bloom 5 步）** | **≈ 23 s** |
| `verify_assets.py` | 2 s |
| `build_package.py`（80 MB 文件 + 68 MB zip） | 5 s |
| `build_samples.py`（13 条 + 1 条验收） | 40 s |

## 3. 目录结构

```
tools/tts/
├── README.md                 # 本文
├── requirements.txt          # pin 版本（Python 3.11）
├── data-sources.json         # 外部数据源 URL/镜像/sha256/落盘位置
├── artifacts.json            # 已发布包的产物指纹与断言级别
├── lib/common.py             # 路径/CLI/日志/lexicon 读写/分段组装/产物比对
├── download_data.py          # 下载 + 解包 + sha256 校验（--offline / --check）
├── build_tokens.py           # 符号表 → tokens.txt（bit-identical 校验）
├── build_lexicon_zh.py       # pypinyin 单字 + 词组 → BOPOMOFO → IPA
├── build_lexicon_en.py       # CMUdict → IPA（coda-l→ɫ）+ 手工 OOV 补条 → extras
├── build_lexicon_ja.py       # JMdict → 假名 → pyopenjtalk IPA2 + ja_reading_map.json
├── build_bloom.py            # en_words.bloom（与 App EnglishBloomHash 同算法）
├── build_package.py          # dist/：6 文件 + zip + manifest.json
├── build_samples.py          # 13 条试听 + --text 验收（OOV 检查）
├── verify_assets.py          # 对 artifacts.json 做三级断言，打印差异报告
├── build_model.md            # model.onnx 来源（不在本管线）
├── vendor/vits_text/         # 训练同源 G2P 代码副本（4 个 .py + SOURCE.md）
├── data/                     # ↓ 以下全部 gitignored
├── out/                      # 生成产物
├── dist/                     # 打包产物
└── samples/                  # 试听 wav
```

`data/`、`out/`、`dist/`、`samples/` 由 [.gitignore](.gitignore) 忽略（约 200 MB），
不入库；本管线只提交脚本、配置与文档。

## 4. 脚本职责

| 脚本 | 输入 | 输出 | 关键规则 |
|---|---|---|---|
| `download_data.py` | `data-sources.json` | `data/{jmdict,open_jtalk_dic,fsts}` | 逐镜像尝试，压缩包 + 解包后都校验 sha256 |
| `build_tokens.py` | `vendor/vits_text/symbols.py` | `out/tokens.txt` | `_`=0、`^`/`$`→0、空格写 `␣`、CRLF；与 artifacts 不一致即报错 |
| `build_lexicon_zh.py` | `pypinyin.pinyin_dict` / `phrases_dict` | `out/lexicon_zh.txt` | BOPOMOFO→IPA（训练同源）+ i/u 介音 + 卷舌修正；单字不加 `␣`、词组加 `␣` |
| `build_lexicon_en.py` | `cmudict` + `vendor english` | `out/lexicon_en.txt`、`out/lexicon_extras.txt` | specials → CMUdict（只收 `^[a-z][a-z'\-.]*$`），coda-l→`ɫ`，重音 `ˈ/ˌ`；手工补条 token 白名单校验 |
| `build_lexicon_ja.py` | JMdict + open_jtalk 词典 | `out/lexicon_ja.txt`、`out/ja_reading_map.json` | 只收假名/假名词；word/char_full/char_stem 三表；`OPEN_JTALK_DICT_DIR` 指向 `data/` |
| `build_bloom.py` | `out/lexicon.txt` | `out/en_words.bloom` | m=1<<22、k=3、FNV-1a 双种子、小端 bit（与 App 同算法） |
| `build_package.py` | `out/*` + `--model` | `dist/`（6 文件 + zip + manifest） | zip 内保留 `fsts/` 结构；固定 zip 时间戳 → 相同输入得到相同 zip |
| `build_samples.py` | `out/*` + `--model` | `samples/*.wav` | 13 条综合样本 + `--text` 验收（截获 sherpa stderr 查 `Ignore OOV`） |
| `verify_assets.py` | `out/`（+ `data/`、`dist/`） | 报告 / JSON | `bit-identical` 严格比对；`content-identical` 用内容指纹；`record-only` 仅记录 |

## 5. 数据源（pin）

全部记录在 [data-sources.json](data-sources.json)（含镜像）。摘要：

| id | 来源 | sha256 | 落盘 |
|---|---|---|---|
| `jmdict` | jmdict-simplified `3.6.2+20261012204142`（dictDate 2026-10-12, commonOnly）zip 1,444,290 B | 解包 JSON `a80b7b66…6dd8d47b`（16,525,308 B） | `data/jmdict-eng-common-3.6.2.json` |
| `open_jtalk_dic` | open_jtalk `v1.11.1` 词典 tar.gz | `fe6ba0e4…902f382a`（23,646,843 B；`sys.dic` 另校验） | `data/open_jtalk_dic_utf_8-1.11/` |
| `phone_fst` | `hf.co/csukuangfj/vits-zh-hf-fanchen-C` | `1ac2b6fa…3562f8d7`（88,630 B） | `data/fsts/phone.fst` |
| `date_fst` | 同上 | `eb8aa079…6980e718`（59,154 B） | `data/fsts/date.fst` |
| `number_fst` | 同上 | `743f4021…7a8bd5bd`（64,482 B） | `data/fsts/number.fst` |

镜像：GitHub 走 `gh-proxy.com` / `ghfast.top`，HuggingFace 走 `hf-mirror.com`（均为第三方代理，
内容由 sha256 兜底）。`download_data.py --offline` 不联网，只校验本地；缺失项会尝试从开发机
`.tools/tts-spike` 缓存复制（仅开发机存在，新 clone 必须联网）。

## 6. 产物与三级断言结论

`artifacts.json` 记录已发布 `tts-model-v1` 的指纹与断言级别。本轮**实测**（clean `out/`）：

| 产物 | 大小 | sha256 | 级别 | 结论 |
|---|---|---|---|---|
| `tokens.txt` | 439 B | `a1511ff2…8bd68120` | bit-identical | **✅ 完全一致** |
| `ja_reading_map.json` | 514,556 B | `5d92d212…ebd029b2` | bit-identical | **✅ 完全一致**（word 16,976 / char_full 1,071 / char_stem 1,077） |
| `en_words.bloom` | 524,288 B | `5057e286…54b34523` | bit-identical | **✅ 完全一致**（126,077 词，误判率 0.064%） |
| `fsts/phone.fst` | 88,630 B | `1ac2b6fa…3562f8d7` | bit-identical | **✅ 下载后 sha 一致** |
| `fsts/date.fst` | 59,154 B | `eb8aa079…6980e718` | bit-identical | **✅ 下载后 sha 一致** |
| `fsts/number.fst` | 64,482 B | `743f4021…7a8bd5bd` | bit-identical | **✅ 下载后 sha 一致** |
| `lexicon.txt` | 8,916,530 B | 生成 `261ee0a7…e5e73e12` / 发布 `97e2b4fb…9f37dfae` | content-identical | **⚠️ 内容一致、字节不同（仅单字行序）** |
| `model.onnx` | 74,864,089 B | `2d4f52c6…71b026f5` | record-only | 本管线不生成（`dist/` 里有就顺带校验） |

### 6.1 已知差异：`lexicon.txt` 的单字行序

- 两者**行数相同**（239,739）、**字节数相同**（8,916,530）、**内容指纹相同**
  （`sha256(sorted lines)` = `2951401971…d91d337e`）——即「相同词条 + 相同 token」。
- 用 `--reference` 对已发布 lexicon 做分段对比：

  ```
  zh_chars     行序不同、内容一致（41,923 行）
  zh_phrases   与发布产物逐字节一致（47,111 行）
  ja           与发布产物逐字节一致（24,616 行）
  en           与发布产物逐字节一致（126,048 行）
  extras       与发布产物逐字节一致（41 行）
  ```

- 原因：旧脚本 `rebuild_zh_lexicon.py` 用 `set()` 遍历字符表，CPython 的 str hash 随机化
  让 41,923 条单字的写入顺序**每次运行都不同**（发布包就是某一次随机顺序的产物，无法复现）。
  新生成器改为按 `pypinyin.pinyin_dict` 的插入顺序（= 旧 `assets/pinyin/zh_pinyin.txt` 的顺序，
  也是码点顺序）输出，**顺序确定、可重复**。
- 影响：lexicon 的**查找语义与发音完全不变**（CharacterLexicon 按最长匹配查表，entry 顺序
  只在重复词上影响谁生效；重复词全部集中在 `en` 段，而 `en` 段逐字节一致）。
  实测同进程内用两套 lexicon 合成 4 句 × 6 次，时长**完全相同**（见 §7）。
- 因此 `verify_assets.py` 把它判为 `CONTENT-OK` 并以 0 退出；加 `--strict` 才按字节差异失败。

> 任务书里写的 section 顺序是 `zh → en → ja → extras`，但已发布 `lexicon_final4.txt` 的实际
> 分段是 `zh → ja → en → extras`（kana 段在 89,034–113,649 行，en 段从 113,650 行开始）。
> 本管线按**发布产物的实际顺序**组织，这样除单字段外每个字节都对得上。

## 7. sherpa 验收（`今天用 kotlin 写了个 app`）

```powershell
python tools/tts/build_samples.py --model <model.onnx> `
    --text "今天用 kotlin 写了个 app" --fail-on-oov
```

实测日志（2026-11-15，用本管线 `out/lexicon.txt` + 发布 `model.onnx`）：

```
[text]  2.24s  今天用 kotlin 写了个 app
  OOV=0
EXIT=0
```

- **无 `Ignore OOV 'kotlin'`**：手工补条（`build_lexicon_en.py::HANDCRAFTED`）生效。
- 启动时 sherpa 会打印若干 `Duplicated word: live/ai/c/coffee/cpu/ok/type/usb/wifi`——
  这是发布 lexicon 本来就有的重复条目（specials 与 CMUdict 撞词），两边完全一致，可忽略。
- **时长与发布包一致**：单次运行的时长受 VITS 随机时长预测器影响（每个进程随机种子，
  同进程内每次 run 也会变，约 ±3%），所以不能用单次数字对比。改为「同一进程内、分别用
  生成 lexicon 与发布 lexicon 各合成 4 句 × 6 次」：

  | 句子 | 生成 lexicon 均值 | 发布 lexicon 均值 | 差异 |
  |---|---|---|---|
  | `你好喵，今天天气真好。` | 2.409 s (σ 0.066) | 2.409 s (σ 0.066) | 0.00% |
  | `今天用 kotlin 写了个 app` | 2.008 s (σ 0.054) | 2.008 s (σ 0.054) | 0.00% |
  | `我今年18岁，晚上8点30分见面，电话是13800138000。` | 6.863 s (σ 0.103) | 6.863 s (σ 0.103) | 0.00% |
  | `私はメアだよ。一緒に遊ぼうね、今日は何をする？` | 3.657 s (σ 0.044) | 3.657 s (σ 0.044) | 0.00% |

  两套 lexicon 的 24 次测量**逐一相同**（同进程同随机种子 ⇒ 同一 phoneme 序列 ⇒ 同一
  采样帧数），证明文本前端产出的 token 序列与发布包一致，差异只在单字段行序。

## 8. 输出格式约定（改这些等于改产物字节）

- 文本产物：UTF-8 + **CRLF**（发布包即 CRLF），末尾带行尾；
- lexicon 行：`word tok tok ... ␣`；**多字词条末尾加 `␣`**，单字（含单字母）**不加**；
- section 顺序固定为 `zh` → `ja` → `en` → `extras`（`lib/common.py::SECTION_ORDER`）；
- `tokens.txt`：`_`=0、`^`/`$` 也映射 0、空格写 `␣`（U+2423）、共 69 行 / 67 个唯一 id；
- `ja_reading_map.json`：`json.dumps(..., ensure_ascii=False)` 紧凑格式、**无末尾换行**；
- `en_words.bloom`：裸 bitset 524,288 B、无文件头、`byte = b >> 3`、`mask = 1 << (b & 7)`；
- `dist/tts-model-v1.zip`：条目顺序 `model.onnx, lexicon.txt, tokens.txt, fsts/phone.fst,
  fsts/date.fst, fsts/number.fst`，保留 `fsts/` 子目录。

## 9. 常用参数 / 离线

```powershell
# 数据：只校验 / 强制重下 / 开发机离线（从 .tools/tts-spike 复制）
python tools/tts/download_data.py --check
python tools/tts/download_data.py --force
python tools/tts/download_data.py --offline --data <dir>

# 校验：只报告 / 严格字节 / 逐行 diff / JSON
python tools/tts/verify_assets.py --assets tools/tts/out --report-only
python tools/tts/verify_assets.py --assets tools/tts/out --strict
python tools/tts/verify_assets.py --assets tools/tts/out --reference <已发布 lexicon.txt>
python tools/tts/verify_assets.py --assets tools/tts/out --json verify.json

# 升级模型/词表时可以显式放行 sha 不一致（会写入 CI 报告，慎用）
python tools/tts/build_tokens.py --out tools/tts/out --allow-mismatch
```

退出码：`build_tokens` / `build_lexicon_ja` / `build_bloom` 在产物与 `artifacts.json`
不一致时返回 `2`；`verify_assets` 通过返回 `0`、内容不一致返回 `1`、`--strict` 字节差异返回 `2`、
`--report-only` 恒为 `0`。

## 10. 依赖与版本（[requirements.txt](requirements.txt)）

Python **3.11**（Windows 实测 3.11.9）。要点：

- `numpy==1.26.4`：`pyopenjtalk-prebuilt==0.3.0`（Windows 唯一预编译包）按 numpy 1.x ABI 编译，
  numpy 2.x 下 import 报 `numpy.dtype size changed`；
- `pyopenjtalk-prebuilt==0.3.0`：只提供代码，**不含词典**；词典由 `download_data.py` 下载，
  `build_lexicon_ja.py` 通过 `OPEN_JTALK_DICT_DIR` 指到 `data/open_jtalk_dic_utf_8-1.11`；
- `pypinyin==0.55.0` / `cmudict==1.1.3`：词表数据随包，版本变了表就变了；
- `sherpa-onnx==2.0.1`：只用于 `build_samples.py`（试听/验收），生成 lexicon 不需要；
- 下载用标准库 `urllib.request`，不依赖 `requests`。

## 11. 与已发布包的关系

- `artifacts.json` 的基准 = App 1.8.0 使用的 `tts-model-v1`（发布 commit `17e0441`）。
- `ja_reading_map.json` 与 `en_words.bloom` **不进模型包**，随 APK `assets/tts/` 分发。
  本管线只生成到 `tools/tts/out/`，**不写 `app/`**；需要同步时手工复制：

  ```powershell
  Copy-Item tools/tts/out/ja_reading_map.json app/src/main/assets/tts/ja_reading_map.json
  Copy-Item tools/tts/out/en_words.bloom     app/src/main/assets/tts/en_words.bloom
  ```

- `dist/` 是「6 文件 + zip + manifest」的可分发模型包（App 支持在线下载或本地导入），
  详见 [docs/tts/model-package.md](../../docs/tts/model-package.md)。
- `dist/`、`out/`、`data/`、`samples/` 都 gitignored；提交里只有脚本/配置/文档。

## 12. 许可与出处

| 内容 | 许可 / 出处 |
|---|---|
| JMdict 常用词（jmdict-simplified） | © EDRDG, CC BY-SA 4.0；打包脚本 MIT |
| open_jtalk 1.11 词典 | © 名古屋工業大学 / NAIST，见 `data/open_jtalk_dic_utf_8-1.11/COPYING` |
| CMUdict（`cmudict` 包） | BSD-2-Clause（CMU Pronouncing Dictionary） |
| pypinyin 词表 | MIT |
| `fsts/*.fst` | Apache-2.0（sherpa-onnx / icefall 模型目录） |
| `vendor/vits_text/*` | 用户自有仓库 `mea-pet-public/vits_core/text` 副本，MIT；见 [SOURCE.md](vendor/vits_text/SOURCE.md) |

## 13. 排查

| 现象 | 原因 / 处理 |
|---|---|
| `download_data.py` 全部镜像失败 | 检查网络/代理；或手动把文件放到 `tools/tts/data/<dest>` 后跑 `--check` |
| `import pyopenjtalk` 报 `numpy.dtype size changed` | numpy ≥ 2；`pip install "numpy==1.26.4"` |
| `build_lexicon_ja.py` 报缺 `sys.dic` | 先跑 `download_data.py`（open_jtalk 词典不进 wheel） |
| `build_lexicon_zh.py` 报缺 `tokens.txt` | 先跑 `build_tokens.py`（脚本用 tokens 过滤符号） |
| `build_tokens.py` 报 DIFF | 符号表/行尾/模型换了；`--reference <旧文件>` 看逐行 diff |
| `ja_reading_map.json` 报 DIFF | pyopenjtalk / open_jtalk 词典 / JMdict 版本与 pin 不一致 |
| `verify_assets` 报 lexicon `CONTENT-OK` | 预期行为（单字行序差异），见 §6.1 |
| 试听里 `Ignore OOV 'xxx'` | 该词不在 lexicon；加进 `build_lexicon_en.py::HANDCRAFTED` 或依赖 App 逐字母兜底 |
