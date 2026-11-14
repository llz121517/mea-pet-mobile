#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成 13 条综合试听样本（中 / 英 / 日 / 数字 / 混排），可选跑验收句。

流程与已发布的 ``gen_comprehensive_v2.py`` 一致：

1. 拉丁 run 转小写（App 侧另有英文 OOV 逐字母兜底，Python 参考实现不含该层）；
2. 日语片段改写：连续汉字段紧挨假名才改写，段内最长匹配 ``ja_reading_map.word``（≤8 字），
   未命中的单字按「词干 / 整字 / 原字」兜底；
3. sherpa-onnx 合成（``rule_fsts=phone,date,number``），句尾补 150ms 静音。

模型不在仓库里：用 ``--model`` 指向自备的 ``model.onnx``（见 build_model.md）。

用法::

    python tools/tts/build_samples.py --model <path/to/model.onnx> --out tools/tts/samples

验收模式（额外合成任意句子并检查 sherpa 日志里的 OOV）::

    python tools/tts/build_samples.py --model <path/to/model.onnx> \
        --text "今天用 kotlin 写了个 app" --fail-on-oov
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path

from lib import common

TAIL_SILENCE = 0.15

#: 综合试听用例（与 gen_comprehensive_v2.py 完全一致）
CASES: list[tuple[str, str]] = [
    ("01_zh_short", "你好喵，今天天气真好。"),
    ("02_zh_long", "今天天气真不错，我们一起去公园散步吧，顺便买点好吃的回来，晚上再一起看电影。"),
    ("03_zh_poly", "重庆的银行行长说，重要的会议要重新安排，他想再去一次音乐厅。"),
    ("04_zh_numbers", "我今年18岁，晚上8点30分见面，电话是13800138000。"),
    ("05_zh_date", "今天是2026年10月31日，下午3点15分出发。"),
    ("06_zh_en_mixed", "今天我们去喝coffee，然后看live直播，OK吗？"),
    ("07_zh_letters", "这个USB接口是Type-C的，支持WiFi和蓝牙5.0。"),
    ("08_ja_short", "こんにちは、今日はいい天気ですね。"),
    ("09_ja_long", "私はメアだよ。一緒に遊ぼうね、今日は何をする？"),
    ("10_ja_mixed", "今日は一緒にゲームをしよう、let's go！"),
    ("11_en_short", "Hello, nice to meet you."),
    ("12_en_long", "Today is a beautiful day, let's go for a walk together."),
    ("13_mixed3", "今天天气不错，いっしょに行こう，let's go！"),
]

LATIN_LOWER_RE = re.compile(r"[A-Za-z][A-Za-z'\-.]*")
KANJI_RE = re.compile(r"[\u4e00-\u9fff]")
KANA_RE = re.compile(r"[\u3040-\u30ff]")
HIRA_RE = re.compile(r"[\u3040-\u309f]")
MAX_WORD_LEN = 8


def load_reading_map(path: Path) -> tuple[dict, dict, dict]:
    data = common.read_json(path)
    return data["word"], data["char_full"], data["char_stem"]


def make_rewriter(word: dict, char_full: dict, char_stem: dict):
    """日语片段改写（与 App 的 TtsTextNormalizer.rewriteJapaneseContext 等价）。"""

    def rewrite(text: str) -> str:
        out: list[str] = []
        index = 0
        length = len(text)
        while index < length:
            char = text[index]
            if not KANJI_RE.match(char):
                out.append(char)
                index += 1
                continue
            end = index
            while end < length and KANJI_RE.match(text[end]):
                end += 1
            run = text[index:end]
            prev_kana = index > 0 and KANA_RE.match(text[index - 1])
            next_kana = end < length and KANA_RE.match(text[end])
            if not (prev_kana or next_kana):
                out.append(run)          # 纯中文片段：不动
                index = end
                continue
            offset = 0
            while offset < len(run):
                hit_len = 0
                hit_reading = None
                for size in range(min(MAX_WORD_LEN, len(run) - offset), 1, -1):
                    reading = word.get(run[offset:offset + size])
                    if reading is not None:
                        hit_len, hit_reading = size, reading
                        break
                if hit_reading is not None:
                    out.append(hit_reading)
                    offset += hit_len
                    continue
                char = run[offset]
                follow_kana = (offset + 1 < len(run) and HIRA_RE.match(run[offset + 1])) or \
                              (offset + 1 == len(run) and next_kana)
                if follow_kana and char in char_stem:
                    reading = char_stem[char]
                elif char in char_full:
                    reading = char_full[char]
                elif char in char_stem:
                    reading = char_stem[char]
                else:
                    reading = char
                out.append(reading)
                offset += 1
            index = end
        return "".join(out)

    return rewrite


def normalize(text: str, rewrite) -> str:
    lower = LATIN_LOWER_RE.sub(lambda m: m.group(0).lower(), text)
    return rewrite(lower)


def build_tts(model: Path, tokens: Path, lexicon: Path, fsts: list[Path]):
    try:
        import sherpa_onnx
    except ImportError:
        common.die("缺少 sherpa-onnx；请按 tools/tts/requirements.txt 安装")

    vits = sherpa_onnx.OfflineTtsVitsModelConfig()
    vits.model = str(model)
    vits.tokens = str(tokens)
    vits.lexicon = str(lexicon)
    vits.noise_scale = 0.55
    vits.noise_scale_w = 0.7
    model_config = sherpa_onnx.OfflineTtsModelConfig()
    model_config.vits = vits
    model_config.num_threads = 2
    config = sherpa_onnx.OfflineTtsConfig()
    config.model = model_config
    config.max_num_sentences = 1
    existing = [str(path) for path in fsts if path.is_file()]
    if existing:
        config.rule_fsts = ",".join(existing)
    return sherpa_onnx.OfflineTts(config)


def synth_capture(tts, text: str):
    """合成并截获本次 generate 期间 C++ 写到 fd 2 的日志（sherpa 的 OOV 日志不走 Python stderr）。"""
    saved = os.dup(2)
    tmp = tempfile.TemporaryFile()
    os.dup2(tmp.fileno(), 2)
    try:
        audio = tts.generate(text, sid=0, speed=1.0)
    finally:
        sys.stderr.flush()
        os.dup2(saved, 2)
        os.close(saved)
    tmp.seek(0)
    log = tmp.read().decode("utf-8", "replace")
    tmp.close()
    return audio, log


def write_wav(path: Path, samples, sample_rate: int) -> float:
    import numpy as np
    import soundfile as sf

    data = np.asarray(samples, np.float32)
    data = np.concatenate([data, np.zeros(int(TAIL_SILENCE * sample_rate), np.float32)])
    sf.write(str(path), data, sample_rate)
    return len(data) / float(sample_rate)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成 13 条综合试听样本（可加 --text 做验收）")
    parser.add_argument("--model", default=str(common.OUT_DIR / "model.onnx"),
                        help="model.onnx 路径（默认 tools/tts/out/model.onnx；本管线不生成模型）")
    parser.add_argument("--lexicon", default=str(common.OUT_DIR / "lexicon.txt"),
                        help="lexicon.txt 路径（默认 tools/tts/out/lexicon.txt）")
    parser.add_argument("--tokens", default=str(common.OUT_DIR / "tokens.txt"),
                        help="tokens.txt 路径（默认 tools/tts/out/tokens.txt）")
    parser.add_argument("--map", dest="map_path", default=str(common.OUT_DIR / "ja_reading_map.json"),
                        help="ja_reading_map.json 路径（默认 tools/tts/out/ja_reading_map.json）")
    parser.add_argument("--fsts", default=None,
                        help="fsts 目录（默认 tools/tts/data/fsts，其次 tools/tts/out/fsts）")
    parser.add_argument("--out", default=str(common.SAMPLES_DIR),
                        help="wav 输出目录（默认 tools/tts/samples）")
    parser.add_argument("--text", action="append", default=[],
                        help="额外合成句子（可重复；用于验收，如 --text \"今天用 kotlin 写了个 app\"）")
    parser.add_argument("--fail-on-oov", action="store_true",
                        help="--text 合成时若日志出现 Ignore OOV 则退出码 1")
    args = parser.parse_args(argv)

    model, tokens = Path(args.model), Path(args.tokens)
    lexicon, map_path = Path(args.lexicon), Path(args.map_path)
    for path, hint in ((model, "见 tools/tts/build_model.md"),
                       (tokens, "先运行 build_tokens.py"),
                       (lexicon, "先运行 build_lexicon_*.py"),
                       (map_path, "先运行 build_lexicon_ja.py")):
        if not path.is_file():
            common.die("缺少 %s（%s）" % (path, hint))

    if args.fsts:
        fsts = [Path(args.fsts) / name for name in ("phone.fst", "date.fst", "number.fst")]
    else:
        base = common.DATA_DIR / "fsts"
        if not (base / "phone.fst").is_file():
            base = common.OUT_DIR / "fsts"
        fsts = [base / name for name in ("phone.fst", "date.fst", "number.fst")]
    missing_fsts = [str(p) for p in fsts if not p.is_file()]
    if missing_fsts:
        common.warn("缺少 ruleFsts（数字/日期/电话不会归一化）：%s" % ", ".join(missing_fsts))

    word, char_full, char_stem = load_reading_map(map_path)
    rewrite = make_rewriter(word, char_full, char_stem)
    common.log("ja_reading_map：word %d / char_full %d / char_stem %d"
               % (len(word), len(char_full), len(char_stem)))
    common.log("模型：%s（%s）" % (model, common.human_size(common.file_size(model))))
    tts = build_tts(model, tokens, lexicon, fsts)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    failures = 0
    for tag, text in CASES:
        feed = normalize(text, rewrite)
        audio, log = synth_capture(tts, feed)
        duration = write_wav(out_dir / (tag + ".wav"), audio.samples, audio.sample_rate)
        oov = [line.strip() for line in log.splitlines() if "Ignore OOV" in line]
        failures += len(oov)
        common.log("[%-12s] %5.2fs  %s" % (tag, duration, feed[:46]))
        for line in oov:
            common.warn("%s: %s" % (tag, line))

    for text in args.text:
        feed = normalize(text, rewrite)
        audio, log = synth_capture(tts, feed)
        duration = write_wav(out_dir / "acceptance.wav", audio.samples, audio.sample_rate)
        oov = [line.strip() for line in log.splitlines() if "Ignore OOV" in line]
        failures += len(oov)
        common.log("[text] %5.2fs  %s" % (duration, feed))
        common.log("  OOV=%d" % len(oov))
        for line in oov:
            common.warn("  %s" % line)

    common.log("输出目录：%s" % out_dir)
    if args.fail_on_oov and failures:
        common.log("验收失败：日志里出现 %d 条 Ignore OOV" % failures)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
