#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""英文 lexicon：CMUdict ARPAbet → IPA（+ coda-l → ɫ）+ 手工 OOV 技术词补条。

内容 = 已发布 lexicon 的 ``en`` 段（specials + CMUdict），随后把手工补条里**未被前面
section 覆盖**的词写成 ``extras`` 段（发布包里是 41 条）。

关键规则（必须与已发布产物一致）：

- ARPAbet → IPA 用 ``build_lexicon_en.py`` 的旧表，重音 1→``ˈ``、2→``ˌ``，多字符音素拆成
  单字符 token；``ɑʊ/ɑɪ/eɪ/oʊ/ɔɪ`` 只在有重音时插重音符；
- **coda-l 规则**：``l`` 在音节末（下一个音素不是元音或后面没有音素）→ ``ɫ``，否则 ``l``；
- CMUdict 只收 ``^[a-z][a-z'\\-.]*$`` 的词（丢掉 ``'bout``/``'twas`` 这类撇号开头条目，
  与旧脚本 lexicon_final3 的过滤一致）；每个词的第一个读音，词尾加 ``␣``；
- specials 用 vendor 的 ``english.english_to_ipa2`` 生成（``usb``/``cpu`` 等缩写会得到
  ``usb*`` 这类 eng_to_ipa 结果，发布包就是这样，别"顺手修正"）；
- 手工补条（``HANDCRAFTED``）是 OOV 修复清单：kotlin / github / 各类技术缩写与品牌词。

用法::

    python tools/tts/build_lexicon_en.py --out tools/tts/out
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

import cmudict

from lib import common
from vendor.vits_text import english

#: ARPAbet → 模型 IPA（多字符音素后续会拆成单字符 token）
ARPA = {
    "AA": "ɑ", "AE": "æ", "AH": "ə", "AO": "ɔ", "AW": "aʊ", "AY": "aɪ",
    "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "EH": "ɛ", "ER": "əɹ",
    "EY": "eɪ", "F": "f", "G": "g", "HH": "h", "IH": "ɪ", "IY": "i",
    "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "ŋ",
    "OW": "oʊ", "OY": "ɔɪ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ",
    "T": "t", "TH": "θ", "UH": "ʊ", "UW": "u", "V": "v", "W": "w",
    "Y": "j", "Z": "z", "ZH": "ʒ",
}
DIPHTHONGS = ("aʊ", "aɪ", "eɪ", "oʊ", "ɔɪ")
VOWELS = set("ɑæəɔaɪeɪiɪoʊʊuɚ")

#: 与旧脚本 lexicon_final3 的英文行过滤保持一致（排除撇号开头条目与带数字/大写的键）。
WORD_RE = re.compile(r"^[a-z][a-z'\-.]*$")

#: 额外口语/缩写词：旧脚本用 english_to_ipa2 生成。顺序即产物顺序。
SPECIALS = ["usb", "wifi", "ok", "type", "c", "live", "coffee", "ai", "cpu", "gpu"]

#: 手工 IPA 补条（OOV 修复清单）。token 全部来自 tokens.txt 单字符 token；
#: 注意 tokens.txt 没有合流塞擦音 token ``tʃ``/``dʒ``，这里一律写成 ``t ʃ`` / ``d ʒ``。
HANDCRAFTED: list[tuple[str, str]] = [
    # 开发工具 / 平台
    ("kotlin", "k ˈ ɑ t l ɪ n"),
    ("github", "g ˈ ɪ t h ə b"),
    ("macos", "m ˈ æ k o ʊ ɛ s"),
    ("openai", "o ʊ p ə n ˌ e ɪ ˌ a ɪ"),
    ("chatgpt", "t ʃ ˈ æ t ˌ d ʒ i p i t i"),
    ("gpt", "ˌ d ʒ i p i t i"),
    ("llm", "ˌ ɛ l ˌ ɛ l ˌ ɛ m"),
    ("sdk", "ˌ ɛ s ˌ d i k e ɪ"),
    ("json", "ˌ d ʒ e ɪ s ə n"),
    ("css", "ˌ s i ˌ ɛ s ˌ ɛ s"),
    ("js", "ˌ d ʒ e ɪ ˌ ɛ s"),
    ("cpp", "ˌ s i p i p i"),
    ("www", "ˌ d ə b ə l j u ˌ d ə b ə l j u ˌ d ə b ə l j u"),
    ("https", "ˌ e ɪ t ʃ t i t i p i ˌ ɛ s"),
    ("tcp", "ˌ t i s i p i"),
    ("udp", "ˌ j u d i p i"),
    ("vpn", "ˌ v i p i ɛ n"),
    ("gpu", "ˌ d ʒ i p i j u"),
    ("ssd", "ˌ ɛ s ɛ s d i"),
    ("hdmi", "ˌ e ɪ t ʃ d i ɛ m a ɪ"),
    ("ux", "ˌ j u ɛ k s"),
    ("npm", "ˌ ɛ n p i ɛ m"),
    ("kubernetes", "k u b ə ɹ n ɛ t i z"),
    ("x86", "ɛ k s ˈ e ɪ t i s ɪ k s"),
    # AI / 音频
    ("ml", "ɛ m ɛ l"),
    ("nlp", "ɛ n ɛ l p i"),
    ("asr", "e ɪ ɛ s ɑ ɹ"),
    ("tts", "t i t i ɛ s"),
    ("ocr", "o ʊ s i ɑ ɹ"),
    ("vlog", "v l ɔ g"),
    ("sherpa", "ʃ ˈ ɛ ɹ p ə"),
    ("onnx", "ˈ ɔ n ɪ k s"),
    ("live2d", "l a ɪ v t u d i"),
    ("meapet", "m i p ɛ t"),
    ("deepseek", "d i p s i k"),
    ("qwen", "k w ɛ n"),
    ("claude", "k l ɔ d"),
    ("gemini", "d ʒ ɛ m ɪ n a ɪ"),
    ("llama", "l ɑ m ə"),
    # 框架 / 发行版 / 平台
    ("pytorch", "p a ɪ t ɔ ɹ t ʃ"),
    ("tensorflow", "t ɛ n s ə ɹ f l o ʊ"),
    ("numpy", "n ə m p a ɪ"),
    ("ubuntu", "u b u n t u"),
    ("debian", "d ɛ b i ə n"),
    ("docker", "d ɑ k ə ɹ"),
    ("linux", "l ɪ n ə k s"),
    ("bilibili", "b ɪ l ɪ b ɪ l ɪ"),
    ("tiktok", "t ɪ k t ɔ k"),
    ("youtube", "j u t j u b"),
    ("google", "g u g ə l"),
    ("emoji", "ɪ m o ʊ d ʒ i"),
    ("wechat", "w i t ʃ æ t"),
]


def arpa_to_tokens(phones: list[str], valid: set[str]) -> list[str]:
    """ARPAbet（带重音数字）→ token 序列，含 coda-l→ɫ 规则。"""
    out: list[str] = []
    for index, phone in enumerate(phones):
        stress = 0
        if phone and phone[-1].isdigit():
            stress = int(phone[-1])
            phone = phone[:-1]
        base = ARPA.get(phone)
        if not base:
            continue
        if base == "l":
            nxt = phones[index + 1] if index + 1 < len(phones) else None
            coda = nxt is None or (nxt.rstrip("012") not in VOWELS)
            base = "ɫ" if coda else "l"
        if base in DIPHTHONGS or phone in ("AA", "AE", "AH", "AO", "EH", "ER",
                                           "IH", "IY", "OW", "UH", "UW"):
            if stress == 1:
                out.append("ˈ")
            elif stress == 2:
                out.append("ˌ")
        out.extend(ch for ch in base if ch in valid)
    return out


def build_specials(valid: set[str]) -> list[tuple[str, list[str]]]:
    entries: list[tuple[str, list[str]]] = [("live", ["l", "a", "ɪ", "v", "␣"])]
    for word in SPECIALS:
        try:
            ipa = english.english_to_ipa2(word)
        except Exception as exc:  # noqa: BLE001 - eng_to_ipa 对个别缩写会抛错
            common.warn("specials %r 生成失败（%s），跳过" % (word, exc))
            continue
        tokens = [ch for ch in ipa if ch in valid]
        if tokens:
            entries.append((word, tokens + ["␣"]))
    return entries


def build_cmudict(valid: set[str]) -> tuple[list[tuple[str, list[str]]], int]:
    entries: list[tuple[str, list[str]]] = []
    skipped = 0
    for word, prons in cmudict.dict().items():
        if not WORD_RE.match(word):
            skipped += 1
            continue
        tokens = arpa_to_tokens(prons[0], valid)
        if tokens:
            entries.append((word, tokens + ["␣"]))
    return entries, skipped


def validate_handcrafted(valid: set[str]) -> None:
    bad: dict[str, list[str]] = {}
    for word, ipa in HANDCRAFTED:
        for token in ipa.split():
            if token not in valid:
                bad.setdefault(token, []).append(word)
    if bad:
        for token, words in sorted(bad.items()):
            common.log("非法 token %r（出现于：%s）" % (token, ", ".join(words)))
        common.die("手工补条里有 %d 个 tokens.txt 之外的 token；必须修词条本身，不能静默丢弃" % len(bad))
    total = sum(len(ipa.split()) for _, ipa in HANDCRAFTED)
    common.log("手工补条 token 校验通过：%d 条 / %d 个 token 全部在 tokens.txt 内"
               % (len(HANDCRAFTED), total))


def existing_words(out_dir: Path, exclude: tuple[str, ...] = ("extras",)) -> set[str]:
    words: set[str] = set()
    for key in common.SECTION_ORDER:
        if key in exclude:
            continue
        path = common.section_path(out_dir, key)
        if path.is_file() and path.stat().st_size:
            words.update(word for word, _ in common.read_lexicon(path))
    return words


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成英文 lexicon 段（CMUdict → IPA + 手工补条）")
    common.add_common_args(parser)
    parser.add_argument("--tokens", default=None, help="tokens.txt 路径（默认 <out>/tokens.txt）")
    args = parser.parse_args(argv)
    out_dir = Path(args.out)
    tokens_path = Path(args.tokens) if args.tokens else out_dir / "tokens.txt"
    if not tokens_path.is_file():
        common.die("缺少 %s；请先运行 build_tokens.py" % tokens_path)
    valid = common.valid_token_set(tokens_path)

    validate_handcrafted(valid)
    entries = build_specials(valid)
    specials_count = len(entries)
    cmudict_entries, skipped = build_cmudict(valid)
    entries.extend(cmudict_entries)
    path = common.write_lexicon(common.section_path(out_dir, "en"), entries)
    common.log("en 段：specials %d + CMUdict %d（过滤 %d 个非小写拉丁键） = %d 条 / %d B"
               % (specials_count, len(cmudict_entries), skipped, len(entries), common.file_size(path)))

    # extras：手工补条里未被 zh/ja/en 覆盖的词（发布包里 41 条）
    known = existing_words(out_dir, exclude=("extras",))
    extras = [(word, ipa.split() + ["␣"]) for word, ipa in HANDCRAFTED if word not in known]
    extra_path = common.write_lexicon(common.section_path(out_dir, "extras"), extras)
    skipped_words = [word for word, _ in HANDCRAFTED if word in known]
    common.log("extras 段：%d 条 / %d B（已被前面 section 覆盖、跳过 %d 条：%s）"
               % (len(extras), common.file_size(extra_path), len(skipped_words),
                  ", ".join(skipped_words) or "-"))

    common.log("组装 lexicon.txt：")
    common.assemble_lexicon(out_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
