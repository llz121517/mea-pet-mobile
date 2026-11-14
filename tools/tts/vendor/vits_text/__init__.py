# -*- coding: utf-8 -*-
"""vits_core.text 的只读副本（G2P 规则，**未改逻辑**）。

来源：``E:\\Documents\\Projects\\Python\\mea-pet-public\\vits_core\\text``（用户自有仓库），
拷贝文件：``symbols.py`` / ``mandarin.py`` / ``japanese.py`` / ``english.py``。
逐文件 sha256 与拷贝日期见同目录 ``SOURCE.md``。

本 ``__init__.py`` 刻意**不 import** 任何子模块：``japanese`` 依赖 pyopenjtalk 的词典
（需要 ``OPEN_JTALK_DICT_DIR`` 环境变量），``english`` 依赖 eng_to_ipa/inflect，按需
import 才能让各脚本只加载自己需要的依赖。
"""
