#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从 ``vendor/vits_text/symbols.py`` 的符号表重建 ``tokens.txt``。

规则（与已发布 tokens.txt 逐字节一致）：

1. ``symbols = [_pad] + list(_punctuation) + list(_letters)``，按枚举顺序编号；
2. 空格符号写成 ``␣``（U+2423），编号不变；
3. ``^`` 映射到 blank id ``0``（``^`` 原本是第 58 个符号，发布包把它与 ``_`` 合并）；
4. 末尾追加 ``$ 0``（piper/sherpa 前端要求的句末符号）。

用法::

    python tools/tts/build_tokens.py --out tools/tts/out

输出与 ``artifacts.json`` 不一致时报错并说明差异（``--allow-mismatch`` 可放行，
仅用于升级模型/符号表的场景）。``--reference`` 可指向旧 ``tokens.txt`` 得到逐行 diff。
"""
from __future__ import annotations

import argparse
from pathlib import Path

from lib import common
from vendor.vits_text import symbols as symbols_mod


def build_tokens() -> list[tuple[str, int]]:
    """→ [(token, id), ...]；``^``/``$`` 的 id 均为 0。"""
    rows: list[tuple[str, int]] = []
    seen: dict[str, int] = {}
    for index, symbol in enumerate(symbols_mod.symbols):
        token = "␣" if symbol == " " else symbol
        token_id = 0 if token == "^" else index
        if token in seen:
            raise SystemExit("符号表里有重复符号：%r（第 %d 与第 %d 个）" % (token, seen[token], index))
        seen[token] = index
        rows.append((token, token_id))
    rows.append(("$", 0))
    return rows


def render(rows: list[tuple[str, int]]) -> str:
    return common.NEWLINE.join("%s %d" % (token, token_id) for token, token_id in rows) + common.NEWLINE


def explain_diff(actual: list[str], expected: list[str]) -> list[str]:
    """给出「第一次出现差异」的可读说明。"""
    lines = ["  行数：实际 %d / 参考 %d" % (len(actual), len(expected))]
    if len(actual) != len(expected):
        only_actual = [line for line in actual if line not in set(expected)]
        only_expected = [line for line in expected if line not in set(actual)]
        if only_actual:
            lines.append("  多出的行：%s" % ", ".join(repr(x) for x in only_actual[:8]))
        if only_expected:
            lines.append("  缺少的行：%s" % ", ".join(repr(x) for x in only_expected[:8]))
        return lines
    for index, (got, want) in enumerate(zip(actual, expected)):
        if got != want:
            lines.append("  第 %d 行（0 起）：实际 %r / 参考 %r" % (index, got, want))
            for j in range(index + 1, min(index + 3, len(actual))):
                lines.append("    后续第 %d 行：实际 %r / 参考 %r" % (j, actual[j], expected[j]))
            break
    return lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="重建 tokens.txt（来自 vendor/vits_text/symbols.py）")
    common.add_common_args(parser)
    parser.add_argument("--reference", default=None,
                        help="可选的参考 tokens.txt（例如 .tools/tts-spike/model/tokens.txt），用于逐行 diff")
    args = parser.parse_args(argv)

    out_dir = Path(args.out)
    rows = build_tokens()
    text = render(rows)
    path = common.write_text(out_dir / "tokens.txt", text)
    unique_ids = len({token_id for _, token_id in rows})
    common.log("tokens.txt: %d 行 / %d 个唯一 id / %d B -> %s"
               % (len(rows), unique_ids, common.file_size(path), path))
    common.log("  符号表：vendor/vits_text/symbols.py 的 %d 个符号；blank=0（_ ^ $）、标点 1-7、"
               "IPA 33-54、␣=67" % len(symbols_mod.symbols))

    artifacts, _ = common.load_artifacts(args.artifacts)
    expected = artifacts.get("tokens.txt")
    if expected is None:
        common.warn("artifacts.json 未登记 tokens.txt，跳过一致性校验")
        return 0

    actual_size = common.file_size(path)
    actual_sha = common.sha256_file(path)
    if actual_size == expected["size"] and actual_sha == expected["sha256"]:
        common.log("与 artifacts.json 一致：%d B / sha256 %s" % (actual_size, actual_sha))
        return 0

    common.log("")
    common.log("tokens.txt 与 artifacts.json 不一致：")
    common.log("  期望 %d B / sha256 %s" % (expected["size"], expected["sha256"]))
    common.log("  实际 %d B / sha256 %s（%d 行 / %d 个唯一 id）"
               % (actual_size, actual_sha, len(rows), unique_ids))
    if args.reference:
        reference = common.read_lines(args.reference)
        actual_lines = [line.rstrip("\r") for line in text.split(common.NEWLINE) if line]
        for line in explain_diff(actual_lines, reference):
            common.log(line)
    else:
        common.log("  提示：用 --reference <旧 tokens.txt> 可以得到逐行 diff。")
    common.log("  常见原因：改动了 vendor/vits_text/symbols.py、行尾不是 CRLF、或模型换了符号表。")
    if args.allow_mismatch:
        common.warn("--allow-mismatch：忽略该差异")
        return 0
    common.log("  如属预期（升级模型），请更新 artifacts.json，或加 --allow-mismatch。")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
