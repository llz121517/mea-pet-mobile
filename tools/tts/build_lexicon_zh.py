#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""中文 lexicon：纯 pypinyin（单字 + 词组）→ BOPOMOFO → IPA。

**不再依赖** `assets/pinyin/{zh_pinyin,zh_phrases}.txt`（已在 980d157 删除），改为直接读
pypinyin 自带的两张表：

- 单字：``pypinyin.pinyin_dict.pinyin_dict``（键是码点 int，按插入顺序输出，41,923 字）；
- 词组：``pypinyin.phrases_dict.phrases_dict`` 中「长度 ≥ 2 且全为 U+4E00–U+9FFF」的
  47,111 条（多音字靠词组消歧：重庆 / 银行 / 行长）。

转换链与训练同源（沿用 ``rebuild_zh_lexicon.py``）：``lazy_pinyin(BOPOMOFO)`` → 轻声补
``ˉ`` → ``mandarin._bopomofo_to_ipa`` → ``i([aoe])→j`` / ``u([aoəe])→w`` → 卷舌韵修正 →
按 ``tokens.txt`` 过滤成 token。单字词条末尾**不加** ``␣``，多字词条**加** ``␣``。

用法::

    python tools/tts/build_lexicon_zh.py --out tools/tts/out
"""
from __future__ import annotations

import argparse
import re
import time
from pathlib import Path

import pypinyin
from pypinyin.phrases_dict import phrases_dict as PHRASES
from pypinyin.pinyin_dict import pinyin_dict as PINYIN_DICT

from lib import common
from vendor.vits_text import mandarin

_BOPOMOFO_TO_IPA = mandarin._bopomofo_to_ipa  # noqa: SLF001 - 训练同源规则，直接复用
_CJK_RE = re.compile(r"^[\u4e00-\u9fff]+$")
_TONE_MARK_RE = re.compile(r"([\u3105-\u3129])$")

#: 抽查用：多音字/常见词（打印发音，便于人工确认）
SPOT_CHECK = ("今天", "重庆", "银行", "行长", "音乐", "会", "岁", "说", "𢱧")


def word_to_ipa(word: str) -> str | None:
    """汉字串 → IPA（保留空格/声调箭头；符号表外的字符会在切 token 时丢）。"""
    bopomofos = pypinyin.lazy_pinyin(word, style=pypinyin.BOPOMOFO)
    if not bopomofos or any(not b for b in bopomofos):
        return None
    # 没有声调符号的音节补轻声（与训练前端一致）
    bopomofos = [_TONE_MARK_RE.sub(r"\1ˉ", b) for b in bopomofos]
    text = "".join(bopomofos)
    for pattern, repl in _BOPOMOFO_TO_IPA:
        text = re.sub(pattern, repl, text)
    text = re.sub("i([aoe])", r"j\1", text)
    text = re.sub("u([aoəe])", r"w\1", text)
    text = re.sub(r"([sɹ]`[⁼ʰ]?)([→↓↑ ]+|$)", r"\1ɹ`\2", text).replace("ɻ", "ɹ`")
    text = re.sub(r"([s][⁼ʰ]?)([→↓↑ ]+|$)", r"\1ɹ\2", text)
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成中文 lexicon 段（pypinyin → BOPOMOFO → IPA）")
    common.add_common_args(parser)
    parser.add_argument("--tokens", default=None,
                        help="tokens.txt 路径（默认 <out>/tokens.txt）")
    args = parser.parse_args(argv)
    out_dir = Path(args.out)
    tokens_path = Path(args.tokens) if args.tokens else out_dir / "tokens.txt"
    if not tokens_path.is_file():
        common.die("缺少 %s；请先运行 build_tokens.py" % tokens_path)
    valid = common.valid_token_set(tokens_path)
    common.log("有效 token：%d 个（%s）" % (len(valid), tokens_path))

    started = time.time()
    entries: list[tuple[str, list[str]]] = []
    char_fail: list[str] = []
    char_count = 0
    for codepoint in PINYIN_DICT:
        char = chr(codepoint)
        char_count += 1
        ipa = word_to_ipa(char)
        tokens = common.ipa_to_tokens(ipa, valid) if ipa else []
        if tokens:
            entries.append((char, tokens))
        else:
            char_fail.append(char)
    word_fail = 0
    word_count = 0
    for word in PHRASES:
        if len(word) < 2 or not _CJK_RE.match(word):
            continue
        word_count += 1
        ipa = word_to_ipa(word)
        tokens = common.ipa_to_tokens(ipa, valid) if ipa else []
        if not tokens:
            word_fail += 1
            continue
        if tokens[-1] != "␣":
            tokens = tokens + ["␣"]
        entries.append((word, tokens))

    path = common.write_lexicon(common.section_path(out_dir, "zh"), entries)
    common.log("单字 %d（失败 %d） 词组 %d（无 token %d） -> %d 条 / %d B"
               % (char_count, len(char_fail), word_count, word_fail,
                  len(entries), common.file_size(path)))
    common.log("  %.1fs  %s" % (time.time() - started, path))
    if char_fail:
        common.warn("以下单字没有产出 token（前 10 个）：%s" % " ".join(char_fail[:10]))
    if word_fail:
        common.warn("%d 个词组没有产出 token" % word_fail)

    for word in SPOT_CHECK:
        hit = next((tokens for w, tokens in entries if w == word), None)
        if hit:
            common.log("  抽查 %-4s %s" % (word, " ".join(hit)))

    common.log("组装 lexicon.txt：")
    common.assemble_lexicon(out_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
