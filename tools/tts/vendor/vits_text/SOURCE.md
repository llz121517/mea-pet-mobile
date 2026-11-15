# vendor/vits_text · 来源与校验

本目录是 **代码副本**，不是本仓库原创代码。

| 文件 | 上游路径 | sha256（拷贝时） |
|---|---|---|
| symbols.py | `E:\Documents\Projects\Python\mea-pet-public\vits_core\text\symbols.py` | `f87647989b3f8ff9f4bcd6d2f313b5d302397d0425a7e757ed8dd8510ef72ee5` |
| mandarin.py | 同上 `mandarin.py` | `8d7a2f5e9e4751c6b1cad0ad8ba0ebebe1a5d6ea3c7e26818ec978a7ac96721b` |
| japanese.py | 同上 `japanese.py` | `ee2231b2b6175debf91cda3408fca49d215d93f4b1f4e456e2755814432c8164` |
| english.py | 同上 `english.py` | `f439b50c707a4a8dd383af3e992ead4ccabab43eb8c5ab515d4b2dfcdbd85f10` |

- 拷贝日期：2026-11-15。
- 逻辑**未做任何修改**（连注释、正则顺序、空格都保持原样），只用于复现训练同源的
  BOPOMOFO→IPA / ARPAbet→IPA / 假名→IPA2 规则。
- 上游 `vits_core/text/__init__.py` 依赖 `text.cleaners`，本目录**不**复制它；本目录的
  `__init__.py` 只有说明文字，不 import 子模块（`japanese.py` 需要先设置
  `OPEN_JTALK_DICT_DIR`）。
- 上游这些文件的代码出自 VITS / vits-zh 系列开源实现（MIT），见仓库 `NOTICE.md`。

**改逻辑必须两边同步**：App 侧 `TtsTextNormalizer` / `EnglishBloomHash` 与
`build_lexicon_*.py` 的 IPA 输出有隐含契约，改 vendor 代码会改变 lexicon 发音。
