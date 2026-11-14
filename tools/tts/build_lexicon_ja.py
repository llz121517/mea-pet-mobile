#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""日文 lexicon：JMdict 常用词 → 假名读音 → pyopenjtalk IPA2，并生成 ja_reading_map.json。

产物两份：

1. ``<out>/lexicon_ja.txt``：**只放假名/假名词**（24,616 条），词尾加 ``␣``，单假名不加；
   汉字不进 lexicon —— App 侧先用 ja_reading_map.json 把日语片段改写成假名。
2. ``<out>/ja_reading_map.json``：App 侧上下文改写用的三张表

   - ``word``：多字词形 → 假名读音（16,976）
   - ``char_full``：单字词形 → 整字读音（1,071）
   - ``char_stem``：单字词干 → 去掉送假名后的读音（1,077，处理 知らない/行こう/食べた）

实现与旧脚本 ``test_final_design.py`` 等价（同样的 JMdict 遍历顺序、同样的 setdefault
去重、同样的单假名兜底），并且 ``OPEN_JTALK_DICT_DIR`` 指向 download_data.py 下载的
open_jtalk 1.11 词典，保证输出可复现（ja_reading_map.json 与发布包逐字节相同）。

用法::

    python tools/tts/download_data.py
    python tools/tts/build_lexicon_ja.py --out tools/tts/out
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
from pathlib import Path

from lib import common

#: 单假名兜底表（五十音 + 浊半浊 + 拗音 + 长音 + 促音，平假名与片假名）
KANA_SINGLE = (
    "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
    "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゃゅょっー"
    "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
    "ガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポャュョッ"
)

_KANJI_OKURI_RE = re.compile(r"^(.*?[\u4e00-\u9fff])([\u3040-\u309f]+)$")


def load_japanese_module(dict_dir: Path):
    """设置 OPEN_JTALK_DICT_DIR 之后再 import（pyopenjtalk 在 import 时读取该变量）。"""
    os.environ["OPEN_JTALK_DICT_DIR"] = str(dict_dir)
    from vendor.vits_text import japanese  # noqa: PLC0415 - 必须延迟到环境变量设置之后

    return japanese


def build_entries(words: list[dict], japanese, valid: set[str]):
    """→ (kana_entries, word_map, char_full, char_stem)。顺序 = JMdict 词条顺序。"""
    conv: dict[str, list[str]] = {}

    def ipa_tokens(text: str) -> list[str]:
        if text not in conv:
            try:
                conv[text] = common.ipa_to_tokens(japanese.japanese_to_ipa2(text), valid)
            except Exception:  # noqa: BLE001 - 与旧脚本一致：失败缓存空列表
                conv[text] = []
        return conv[text]

    kana_entries: dict[str, list[str]] = {}
    word_map: dict[str, str] = {}
    char_full: dict[str, str] = {}
    char_stem: dict[str, str] = {}
    for entry in words:
        common_kana = [k["text"] for k in entry.get("kana", []) if k.get("common")]
        if not common_kana:
            continue
        reading = common_kana[0]
        for kana in entry.get("kana", []):
            text = kana["text"]
            if text and len(text) <= 12:
                tokens = ipa_tokens(text)
                if tokens:
                    kana_entries.setdefault(text, tokens + ["␣"] if len(text) > 1 else tokens)
        for kanji in entry.get("kanji", []):
            if not kanji.get("common"):
                continue
            text = kanji["text"]
            if len(text) >= 2:
                word_map.setdefault(text, reading)
            if len(text) == 1:
                char_full.setdefault(text, reading)
            match = _KANJI_OKURI_RE.match(text)
            if match and len(match.group(1)) == 1 and reading.endswith(match.group(2)):
                char_stem.setdefault(match.group(1), reading[: -len(match.group(2))])

    # 单假名兜底（大多数已在上面的假名词里出现过）
    for char in KANA_SINGLE:
        tokens = ipa_tokens(char)
        if tokens:
            kana_entries.setdefault(char, tokens)
    return kana_entries, word_map, char_full, char_stem, conv


def write_map(path: Path, *, word_map, char_full, char_stem) -> str:
    """与发布包一致的紧凑 JSON（无缩进、无末尾换行、ensure_ascii=False）。"""
    payload = json.dumps(
        {"word": word_map, "char_full": char_full, "char_stem": char_stem},
        ensure_ascii=False,
    ).encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return common.sha256_bytes(payload)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成日文 lexicon 段 + ja_reading_map.json")
    common.add_common_args(parser)
    parser.add_argument("--tokens", default=None, help="tokens.txt 路径（默认 <out>/tokens.txt）")
    parser.add_argument("--data", default=str(common.DATA_DIR), help="数据目录（默认 tools/tts/data）")
    parser.add_argument("--jmdict", default=None,
                        help="JMdict JSON 路径（默认 <data>/jmdict-eng-common-3.6.2.json）")
    parser.add_argument("--openjtalk-dict", default=None,
                        help="open_jtalk 词典目录（默认 <data>/open_jtalk_dic_utf_8-1.11）")
    parser.add_argument("--map", dest="map_path", default=None,
                        help="ja_reading_map.json 输出路径（默认 <out>/ja_reading_map.json）")
    args = parser.parse_args(argv)

    out_dir = Path(args.out)
    data_dir = Path(args.data)
    jmdict_path = Path(args.jmdict) if args.jmdict else data_dir / "jmdict-eng-common-3.6.2.json"
    dict_dir = Path(args.openjtalk_dict) if args.openjtalk_dict else data_dir / "open_jtalk_dic_utf_8-1.11"
    tokens_path = Path(args.tokens) if args.tokens else out_dir / "tokens.txt"

    if not tokens_path.is_file():
        common.die("缺少 %s；请先运行 build_tokens.py" % tokens_path)
    if not jmdict_path.is_file():
        common.die("缺少 %s；请先运行 download_data.py（或 --offline 使用开发机缓存）" % jmdict_path)
    if not (dict_dir / "sys.dic").is_file():
        common.die("缺少 open_jtalk 词典：%s；请先运行 download_data.py" % dict_dir)

    valid = common.valid_token_set(tokens_path)
    started = time.time()
    data = common.read_json(jmdict_path)
    words = data["words"]
    common.log("JMdict：%s（%d 词，dictDate=%s）" % (jmdict_path.name, len(words), data.get("dictDate")))
    common.log("open_jtalk 词典：%s" % dict_dir)

    japanese = load_japanese_module(dict_dir)
    kana_entries, word_map, char_full, char_stem, conv = build_entries(words, japanese, valid)
    common.log("假名 %d / 单字 %d / 词干 %d / 转换缓存 %d（%.1fs）"
               % (len(kana_entries), len(char_full), len(char_stem), len(conv), time.time() - started))

    entries = list(kana_entries.items())
    lexicon_path = common.write_lexicon(common.section_path(out_dir, "ja"), entries)
    common.log("ja 段：%d 条 / %d B -> %s"
               % (len(entries), common.file_size(lexicon_path), lexicon_path))

    map_path = Path(args.map_path) if args.map_path else out_dir / "ja_reading_map.json"
    map_sha = write_map(map_path, word_map=word_map, char_full=char_full, char_stem=char_stem)
    common.log("ja_reading_map.json：word %d / char_full %d / char_stem %d / %d B / sha256 %s"
               % (len(word_map), len(char_full), len(char_stem), common.file_size(map_path), map_sha))

    artifacts, _ = common.load_artifacts(args.artifacts)
    expected = artifacts.get("ja_reading_map.json")
    if expected is None:
        common.warn("artifacts.json 未登记 ja_reading_map.json，跳过一致性校验")
    elif expected["sha256"] == map_sha and expected["size"] == common.file_size(map_path):
        common.log("与 artifacts.json 一致（bit-identical）")
    else:
        common.log("")
        common.log("ja_reading_map.json 与 artifacts.json 不一致：")
        common.log("  期望 %d B / %s" % (expected["size"], expected["sha256"]))
        common.log("  实际 %d B / %s" % (common.file_size(map_path), map_sha))
        common.log("  常见原因：pyopenjtalk / open_jtalk 词典版本不同，或 JMdict 快照不同。")
        if not args.allow_mismatch:
            common.log("  请按 README「依赖与版本」核对 requirements.txt，或加 --allow-mismatch。")
            return 2
        common.warn("--allow-mismatch：忽略该差异")

    common.log("组装 lexicon.txt：")
    common.assemble_lexicon(out_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
