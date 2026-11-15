# -*- coding: utf-8 -*-
"""tools/tts 公共代码：路径、CLI、日志、lexicon 读写与产物校验。

与已发布 ``tts-model-v1`` 包字节兼容的约定：

- 文本产物统一 UTF-8、行尾 **CRLF**（已发布的 tokens.txt / lexicon.txt 即 CRLF）；
- lexicon 行格式 ``word tok tok ... ␣``，**entry 顺序决定最终字节**；
- section 顺序：``zh``（单字 → 词组）→ ``ja``（假名 / 假名词）→ ``en``（specials → CMUdict）
  → ``extras``（手工 OOV 技术词）。该顺序按已发布 ``lexicon_final4.txt`` 的实际分段确定，
  详见 README「与已发布包的差异」。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Iterable, Sequence

# --------------------------------------------------------------------------- #
# 路径
# --------------------------------------------------------------------------- #
TTS_DIR = Path(__file__).resolve().parents[1]      # tools/tts
REPO_ROOT = TTS_DIR.parents[1]                     # 仓库根目录
DATA_DIR = TTS_DIR / "data"                        # 下载的第三方数据（gitignored）
OUT_DIR = TTS_DIR / "out"                          # 生成产物（gitignored）
DIST_DIR = TTS_DIR / "dist"                        # 打包产物（gitignored）
SAMPLES_DIR = TTS_DIR / "samples"                  # 试听 wav（gitignored）
VENDOR_DIR = TTS_DIR / "vendor"
ARTIFACTS_JSON = TTS_DIR / "artifacts.json"
DATA_SOURCES_JSON = TTS_DIR / "data-sources.json"

#: 发布产物的行尾（读入时用 splitlines()，写出时显式使用该行尾）。
NEWLINE = "\r\n"
ENCODING = "utf-8"

#: lexicon 分段顺序（**不要轻易改动**：顺序影响发布产物字节）。
SECTION_ORDER: tuple[str, ...] = ("zh", "ja", "en", "extras")
SECTION_FILES = {
    "zh": "lexicon_zh.txt",
    "ja": "lexicon_ja.txt",
    "en": "lexicon_en.txt",
    "extras": "lexicon_extras.txt",
}

#: 英文词条（也是 Bloom 词表集合）的判定规则：小写拉丁开头，可含撇号/连字符/句点。
EN_WORD_RE = r"^[a-z][a-z'\-.]*$"

# 允许 `python tools/tts/xxx.py` 之外的方式（例如 `python -m`）也能 import lib/vendor。
if str(TTS_DIR) not in sys.path:
    sys.path.insert(0, str(TTS_DIR))


# --------------------------------------------------------------------------- #
# 控制台
# --------------------------------------------------------------------------- #
def force_utf8_stdio() -> None:
    """让 stdout/stderr 能打印 IPA 符号（Windows 控制台默认 GBK 会 UnicodeEncodeError）。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:  # pragma: no cover - 非 TextIOWrapper
            pass


force_utf8_stdio()


def log(msg: str = "") -> None:
    print(msg, flush=True)


def warn(msg: str) -> None:
    print("WARNING: %s" % msg, file=sys.stderr, flush=True)


def die(msg: str, code: int = 1) -> "NoReturn":  # noqa: F821 - 仅用于类型提示
    print("ERROR: %s" % msg, file=sys.stderr, flush=True)
    raise SystemExit(code)


# --------------------------------------------------------------------------- #
# 哈希 / 文件
# --------------------------------------------------------------------------- #
def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path | str, chunk_size: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(chunk_size), b""):
            h.update(chunk)
    return h.hexdigest()


def file_size(path: Path | str) -> int:
    return Path(path).stat().st_size


def human_size(n: int) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return "%d B" % n if unit == "B" else "%.2f %s" % (n, unit)
        n /= 1024.0
    return "%d B" % n


def read_json(path: Path | str):
    with open(path, "r", encoding=ENCODING) as f:
        return json.load(f)


def write_json(path: Path | str, obj, *, indent: int = 2) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(obj, ensure_ascii=False, indent=indent)
    with open(path, "w", encoding=ENCODING, newline="\n") as f:
        f.write(text + "\n")


# --------------------------------------------------------------------------- #
# lexicon / tokens
# --------------------------------------------------------------------------- #
def read_lines(path: Path | str) -> list[str]:
    """按行读取（兼容 CRLF/LF/CR），丢弃空行，保留行内内容。"""
    data = Path(path).read_bytes().decode(ENCODING)
    return [line for line in data.splitlines() if line.strip()]


def read_lexicon(path: Path | str) -> list[tuple[str, list[str]]]:
    """读 lexicon → [(word, [token, ...]), ...]，保持文件顺序。"""
    entries: list[tuple[str, list[str]]] = []
    for lineno, line in enumerate(read_lines(path), 1):
        parts = line.split()
        if len(parts) < 2:
            raise ValueError("%s:%d lexicon 行格式错误：%r" % (path, lineno, line))
        entries.append((parts[0], parts[1:]))
    return entries


def render_lexicon_lines(entries: Iterable[tuple[str, Sequence[str]]]) -> list[str]:
    return ["%s %s" % (word, " ".join(tokens)) for word, tokens in entries]


def write_lexicon(path: Path | str, entries: Iterable[tuple[str, Sequence[str]]]) -> Path:
    """写 lexicon（UTF-8 + CRLF，末尾同样带行尾），字节与发布产物一致。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = NEWLINE.join(render_lexicon_lines(entries))
    data = (payload + NEWLINE).encode(ENCODING) if payload else b""
    path.write_bytes(data)
    return path


def write_text(path: Path | str, text: str) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode(ENCODING))
    return path


def read_tokens(path: Path | str) -> tuple[list[str], dict[str, int]]:
    """读 tokens.txt → (按行顺序的 token 列表, token -> id)。`^`/`$` 与 `_` 共用 id 0。"""
    tokens: list[str] = []
    ids: dict[str, int] = {}
    for line in read_lines(path):
        try:
            token, raw_id = line.rsplit(" ", 1)
            token_id = int(raw_id)
        except ValueError:
            raise ValueError("%s: tokens.txt 行格式错误：%r" % (path, line))
        tokens.append(token)
        ids[token] = token_id
    return tokens, ids


def valid_token_set(path: Path | str) -> set[str]:
    tokens, _ = read_tokens(path)
    return set(tokens)


def ipa_to_tokens(ipa: str, valid: set[str]) -> list[str]:
    """IPA 串 → 模型 token 序列；空格折叠成单个 ``␣``，不在符号表内的字符丢弃。"""
    out: list[str] = []
    for ch in ipa:
        if ch == " ":
            if out and out[-1] != "␣":
                out.append("␣")
        elif ch in valid:
            out.append(ch)
    return out


# --------------------------------------------------------------------------- #
# 分段组装
# --------------------------------------------------------------------------- #
def section_path(out_dir: Path | str, key: str) -> Path:
    return Path(out_dir) / SECTION_FILES[key]


def assemble_lexicon(out_dir: Path | str = OUT_DIR, *, require_all: bool = False) -> Path:
    """把已存在的 section 文件按 SECTION_ORDER 拼成 out/lexicon.txt。"""
    out_dir = Path(out_dir)
    entries: list[tuple[str, list[str]]] = []
    present: list[str] = []
    missing: list[str] = []
    for key in SECTION_ORDER:
        path = section_path(out_dir, key)
        if path.is_file() and path.stat().st_size > 0:
            section = read_lexicon(path)
            entries.extend(section)
            present.append(key)
            log("  %-6s %7d 条  %s" % (key, len(section), path.name))
        else:
            missing.append(key)
    if require_all and missing:
        die("缺少 section：%s（请先运行对应的 build_lexicon_*.py）" % ", ".join(missing))
    if not entries:
        die("out/ 下没有任何 lexicon section，无法组装 lexicon.txt")
    if missing:
        warn("缺少 section：%s；当前 lexicon.txt 是部分产物" % ", ".join(missing))
    path = write_lexicon(out_dir / "lexicon.txt", entries)
    log("lexicon.txt: %d 条 / %d B -> %s" % (len(entries), file_size(path), path))
    return path


# --------------------------------------------------------------------------- #
# artifacts.json
# --------------------------------------------------------------------------- #
def load_artifacts(path: Path | str | None = None) -> tuple[dict[str, dict], dict]:
    """→ (path -> artifact 记录, 原始 JSON)。"""
    raw = read_json(path or ARTIFACTS_JSON)
    return {item["path"]: item for item in raw["artifacts"]}, raw


def add_common_args(parser: argparse.ArgumentParser, *, out: bool = True) -> None:
    if out:
        parser.add_argument(
            "--out",
            default=str(OUT_DIR),
            help="产物输出目录（默认 tools/tts/out）",
        )
    parser.add_argument(
        "--artifacts",
        default=str(ARTIFACTS_JSON),
        help="artifacts.json 路径（默认 tools/tts/artifacts.json）",
    )
    parser.add_argument(
        "--allow-mismatch",
        action="store_true",
        help="产物与 artifacts.json 不一致时不报错（仅用于升级模型/词表的场景）",
    )
