#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成 ``en_words.bloom``：英文词表 Bloom 索引（裸 bitset，App 侧判 OOV 用）。

参数与 App 的 ``EnglishBloomHash`` / ``EnglishBloomIndex``（
``app/src/main/java/com/meapet/mobile/tts/text/EnglishBloomIndex.kt``）**必须完全一致**：

- 位图：``m = 1 << 22`` bits = 524,288 B，无文件头；
- 哈希：FNV-1a 32-bit，种子 ``0x811C9DC5`` / ``0x01000193``，第 i 位 = ``(h1 + i*h2) & (m-1)``，k=3；
- 位序：小端 bit → ``byte = b >> 3``，``mask = 1 << (b & 7)``。

集合 = lexicon 里所有匹配 ``^[a-z][a-z'\\-.]*$`` 的词（与 generate 脚本一致）。
误判率约 0.06%；误判只会少拆一个词，不会拆错已知词。

用法::

    python tools/tts/build_bloom.py --lexicon tools/tts/out/lexicon.txt \
        --out tools/tts/out/en_words.bloom
"""
from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

from lib import common

BLOOM_BITS = 1 << 22
BLOOM_BYTES = BLOOM_BITS // 8
BLOOM_HASHES = 3
FNV_PRIME = 0x01000193
FNV_OFFSET_BASIS = 0x811C9DC5

WORD_RE = re.compile(common.EN_WORD_RE)


def fnv1a(data: bytes, seed: int) -> int:
    value = seed
    for byte in data:
        value ^= byte
        value = (value * FNV_PRIME) & 0xFFFFFFFF
    return value


def bits_for(word: str) -> list[int]:
    raw = word.encode("utf-8")
    h1 = fnv1a(raw, FNV_OFFSET_BASIS)
    h2 = fnv1a(raw, FNV_PRIME)
    return [(h1 + index * h2) & (BLOOM_BITS - 1) for index in range(BLOOM_HASHES)]


def build_bloom(words: set[str]) -> bytes:
    bits = bytearray(BLOOM_BYTES)
    for word in words:
        for bit in bits_for(word):
            bits[bit >> 3] |= 1 << (bit & 7)
    return bytes(bits)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成 en_words.bloom（英文词表 Bloom 索引）")
    parser.add_argument("--lexicon", default=str(common.OUT_DIR / "lexicon.txt"),
                        help="lexicon.txt 路径（默认 tools/tts/out/lexicon.txt）")
    parser.add_argument("--out", default=str(common.OUT_DIR / "en_words.bloom"),
                        help="输出文件（默认 tools/tts/out/en_words.bloom）")
    common.add_common_args(parser, out=False)
    args = parser.parse_args(argv)

    lexicon = Path(args.lexicon)
    if not lexicon.is_file():
        common.die("缺少 %s；请先运行 build_lexicon_*.py 生成 lexicon" % lexicon)
    words = {word for word, _ in common.read_lexicon(lexicon) if WORD_RE.match(word)}
    if not words:
        common.die("lexicon 里没有英文词，无法生成 Bloom 索引")

    bloom = build_bloom(words)
    if len(bloom) != BLOOM_BYTES:
        common.die("Bloom 大小异常：%d != %d" % (len(bloom), BLOOM_BYTES))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(bloom)

    false_positive = (1.0 - math.exp(-BLOOM_HASHES * len(words) / float(BLOOM_BITS))) ** BLOOM_HASHES
    sha = common.sha256_bytes(bloom)
    common.log("en_words.bloom：%d 词 / %d B（m=1<<22, k=3）/ sha256 %s"
               % (len(words), len(bloom), sha))
    common.log("  误判率估算 %.4f%%（约 1/%.0f）；位序 byte=b>>3, mask=1<<(b&7)；写入 %s"
               % (false_positive * 100.0, 1.0 / false_positive if false_positive else float("inf"), out))
    for word in ("kotlin", "zzzq"):
        common.log("  bits_for(%r) = %s" % (word, bits_for(word)))

    artifacts, _ = common.load_artifacts(args.artifacts)
    expected = artifacts.get("en_words.bloom")
    if expected is None:
        common.warn("artifacts.json 未登记 en_words.bloom，跳过一致性校验")
    elif expected["sha256"] == sha and expected["size"] == len(bloom):
        common.log("与 artifacts.json 一致（bit-identical）")
    else:
        common.log("")
        common.log("en_words.bloom 与 artifacts.json 不一致：")
        common.log("  期望 %d B / %s" % (expected["size"], expected["sha256"]))
        common.log("  实际 %d B / %s" % (len(bloom), sha))
        common.log("  常见原因：lexicon 的英文词集合变了，或 bloom 参数/哈希与 App 不同步。")
        if not args.allow_mismatch:
            common.log("  如属预期（词表升级），请更新 artifacts.json 与 App，或加 --allow-mismatch。")
            return 2
        common.warn("--allow-mismatch：忽略该差异")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
