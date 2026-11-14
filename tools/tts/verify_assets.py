#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 ``artifacts.json`` 校验当前生成/下载的产物，打印差异报告。

断言分级（见 artifacts.json 的 ``level``）：

- ``bit-identical``：``tokens.txt`` / ``ja_reading_map.json`` / ``en_words.bloom`` / ``fsts/*``
  —— 字节必须完全一致，不一致即失败；
- ``content-identical``：``lexicon.txt`` —— 先比字节；字节不同时再比「内容指纹」
  （行数 + 全部词条排序后的 sha256）与分段条数，内容一致判为 ``CONTENT-OK``
  （已知的行序差异，见 README），内容不一致才是失败；
- ``record-only``：``model.onnx`` —— 本管线不生成，找到就顺带校验 sha。

用法::

    python tools/tts/verify_assets.py --assets tools/tts/out
    python tools/tts/verify_assets.py --assets tools/tts/out --report-only   # 只报告，永远 0
    python tools/tts/verify_assets.py --assets tools/tts/out --strict        # 行序差异也算失败
    python tools/tts/verify_assets.py --assets tools/tts/out \
        --reference <已发布 lexicon.txt>                                     # 打印逐行 diff

退出码：0 = 通过（或仅已知行序差异）；1 = 有产物不一致；2 = --strict 下的字节差异。
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from lib import common

PASS = "PASS"
PASS_CONTENT = "CONTENT-OK"
SKIP = "SKIP"
FAIL = "FAIL"


def lookup(assets: Path, rel: str, *, fst_like: bool) -> Path | None:
    """在 assets / data / dist 里找文件（fsts 与 model.onnx 由别的脚本产出）。"""
    candidates = [assets / rel]
    if fst_like or rel == "model.onnx":
        candidates += [
            assets.parent / "data" / rel,
            assets.parent / "dist" / rel,
            # --assets 指向仓库外时，回退到本管线的 data/ 与 dist/
            common.DATA_DIR / rel,
            common.DIST_DIR / rel,
        ]
    for path in candidates:
        if path.is_file():
            return path
    return None


def lexicon_content_sha256(lines: list[str]) -> str:
    """内容指纹：所有词条行排序后按 LF 连接、末尾加 LF，再取 sha256。"""
    return hashlib.sha256(("\n".join(sorted(lines)) + "\n").encode("utf-8")).hexdigest()


def section_counts(assets: Path, expected_sections: dict | None = None) -> dict[str, int]:
    """按 build_* 的分段文件统计条数；zh 段按期望的单字数量拆成 chars/phrases。"""
    counts: dict[str, int] = {}
    zh_path = common.section_path(assets, "zh")
    if zh_path.is_file() and zh_path.stat().st_size:
        zh_lines = common.read_lines(zh_path)
        split = (expected_sections or {}).get("zh_chars")
        if split and len(zh_lines) > split:
            counts["zh_chars"] = split
            counts["zh_phrases"] = len(zh_lines) - split
        else:
            counts["zh"] = len(zh_lines)
    for key in ("ja", "en", "extras"):
        path = common.section_path(assets, key)
        if path.is_file() and path.stat().st_size:
            counts[key] = len(common.read_lines(path))
    return counts


def section_order(expected_sections: dict) -> list[str]:
    return [key for key in ("zh_chars", "zh_phrases", "ja", "en", "extras")
            if key in expected_sections]


def compare_sections_with_reference(assets: Path, reference: Path, expected: dict) -> list[str]:
    """把生成产物按分段与已发布 lexicon 对齐比较（--reference 时给出更强的结论）。"""
    spec = expected.get("sections") or {}
    if not spec:
        return []
    reference_lines = common.read_lines(reference)
    total = sum(spec.values())
    if len(reference_lines) != total:
        return ["  参考文件行数 %d != artifacts.json 分段合计 %d，跳过分段对比"
                % (len(reference_lines), total)]

    bounds: list[tuple[str, int, int]] = []
    offset = 0
    for key in section_order(spec):
        bounds.append((key, offset, offset + spec[key]))
        offset += spec[key]

    ours: dict[str, list[str]] = {}
    zh_path = common.section_path(assets, "zh")
    if zh_path.is_file() and zh_path.stat().st_size:
        zh_lines = common.read_lines(zh_path)
        split = spec.get("zh_chars", len(zh_lines))
        if len(zh_lines) > split:
            ours["zh_chars"] = zh_lines[:split]
            ours["zh_phrases"] = zh_lines[split:]
        else:
            ours["zh"] = zh_lines
    for key in ("ja", "en", "extras"):
        path = common.section_path(assets, key)
        if path.is_file() and path.stat().st_size:
            ours[key] = common.read_lines(path)

    lines: list[str] = []
    for key, start, end in bounds:
        got = ours.get(key)
        if got is None:
            lines.append("  %-10s 生成产物缺少该段" % key)
            continue
        want = reference_lines[start:end]
        if got == want:
            lines.append("  %-10s 与发布产物逐字节一致（%d 行）" % (key, len(got)))
        elif sorted(got) == sorted(want) and len(got) == len(want):
            lines.append("  %-10s 行序不同、内容一致（%d 行）" % (key, len(got)))
        else:
            lines.append("  %-10s 内容不同（生成 %d 行 / 发布 %d 行）" % (key, len(got), len(want)))
    return lines


def check_lexicon(path: Path, expected: dict, assets: Path, reference: Path | None,
                  strict: bool) -> tuple[str, list[str]]:
    detail: list[str] = []
    size = common.file_size(path)
    sha = common.sha256_file(path)
    if size == expected["size"] and sha == expected["sha256"]:
        return PASS, ["字节完全一致：%d B  %s" % (size, sha)]

    detail.append("字节不同（期望 bit-identical）：实际 %d B / %s" % (size, sha))
    detail.append("  期望 %d B / %s" % (expected["size"], expected["sha256"]))
    lines = common.read_lines(path)
    detail.append("  实际行数 %d / 期望行数 %s"
                  % (len(lines), expected.get("lines", "?")))
    detail.append("  实际内容指纹 sha256(sorted lines) = %s" % lexicon_content_sha256(lines))
    detail.append("  期望内容指纹 sha256(sorted lines) = %s" % expected.get("content_sha256", "?"))

    counts = section_counts(assets, expected.get("sections"))
    if counts:
        detail.append("  实际分段：" + "  ".join("%s=%d" % (key, counts[key]) for key in counts))
        wanted = expected.get("sections")
        if wanted:
            detail.append("  期望分段：" + "  ".join("%s=%d" % (key, wanted[key]) for key in wanted))

    same_content = (
        expected.get("lines") == len(lines)
        and expected.get("content_sha256") == lexicon_content_sha256(lines)
    )
    if same_content:
        detail.append("  结论：内容完全一致（相同词条 + 相同 token），差异只来自词条**行序**。")
        detail.append("        旧脚本用 Python set() 遍历字表（str hash 随机化）导致行序不可复现；")
        detail.append("        新生成器按 pypinyin.pinyin_dict 插入顺序输出，因此单字段顺序不同。")
        if reference and reference.is_file():
            section_report = compare_sections_with_reference(assets, reference, expected)
            if section_report:
                detail.append("  分段对比（vs 已发布 lexicon）：")
                detail.extend(section_report)
            ref_lines = common.read_lines(reference)
            shown = 0
            for index, (got, want) in enumerate(zip(lines, ref_lines)):
                if got != want:
                    detail.append("  抽样 diff（第 %d 行）：生成 %r / 发布 %r"
                                  % (index, got, want))
                    shown += 1
                    if shown >= 5:
                        break
        else:
            detail.append("  抽样：%s" % " | ".join(lines[:3]))
        if strict:
            detail.append("  --strict：行序差异按失败处理。")
            return FAIL, detail
        return PASS_CONTENT, detail

    detail.append("  结论：内容不一致（词条集合或 token 有差异，不只是行序）。")
    if reference and reference.is_file():
        reference_lines = common.read_lines(reference)
        ours = set(lines)
        theirs = set(reference_lines)
        only_ours = [line for line in lines if line not in theirs][:5]
        only_theirs = [line for line in reference_lines if line not in ours][:5]
        if only_ours:
            detail.append("  仅在生成产物里的行：%s" % " | ".join(only_ours))
        if only_theirs:
            detail.append("  仅在发布产物里的行：%s" % " | ".join(only_theirs))
    return FAIL, detail


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="用 artifacts.json 校验生成/下载产物")
    parser.add_argument("--assets", default=str(common.OUT_DIR),
                        help="产物目录（默认 tools/tts/out）")
    parser.add_argument("--artifacts", default=str(common.ARTIFACTS_JSON),
                        help="artifacts.json 路径")
    parser.add_argument("--reference", default=None,
                        help="可选的已发布 lexicon.txt 副本，用于打印逐行 diff")
    parser.add_argument("--report-only", action="store_true",
                        help="只打印报告，不返回非零退出码")
    parser.add_argument("--strict", action="store_true",
                        help="lexicon 必须 bit-identical（行序差异也算失败）")
    parser.add_argument("--json", dest="json_path", default=None,
                        help="可选的 JSON 报告输出路径")
    args = parser.parse_args(argv)

    assets = Path(args.assets)
    artifacts, raw = common.load_artifacts(args.artifacts)
    reference = Path(args.reference) if args.reference else None

    common.log("校验目录：%s" % assets.resolve())
    common.log("基准文件：%s（package=%s）" % (args.artifacts, raw.get("package", "?")))
    common.log("")

    results: list[dict] = []
    failures = 0
    for item in raw["artifacts"]:
        rel = item["path"]
        level = item.get("level", "bit-identical")
        fst_like = rel.startswith("fsts/")
        path = lookup(assets, rel, fst_like=fst_like)
        common.log("[%s] level=%s" % (rel, level))
        if path is None:
            if level == "record-only":
                status, detail = SKIP, ["未找到（本管线不生成，可选：放在 data/ 后顺带校验）"]
            else:
                status, detail = FAIL, ["文件不存在（查找范围：%s、data/、dist/）" % assets]
                failures += 1
        elif level == "record-only":
            size, sha = common.file_size(path), common.sha256_file(path)
            ok = size == item["size"] and sha == item["sha256"]
            status = PASS if ok else FAIL
            detail = ["%s  %d B  %s" % (path, size, sha),
                      "期望 %d B  %s" % (item["size"], item["sha256"])]
            if not ok:
                failures += 1
        elif level == "content-identical":
            status, detail = check_lexicon(path, item, assets, reference, args.strict)
            if status == FAIL:
                failures += 1
        else:
            size, sha = common.file_size(path), common.sha256_file(path)
            ok = size == item["size"] and sha == item["sha256"]
            status = PASS if ok else FAIL
            detail = ["%s  %d B  %s" % (path.name, size, sha),
                      "期望 %d B  %s" % (item["size"], item["sha256"])]
            if not ok:
                failures += 1
        common.log("  %s" % status)
        for line in detail:
            common.log("    %s" % line)
        common.log("")
        results.append({"path": rel, "level": level, "status": status,
                        "file": str(path) if path else None, "detail": detail})

    total = len(results)
    passed = sum(1 for r in results if r["status"] in (PASS, PASS_CONTENT, SKIP))
    common.log("汇总：%d/%d 通过" % (passed, total))
    for result in results:
        if result["status"] == FAIL:
            common.log("  失败：%s（%s）" % (result["path"], result["level"]))
        elif result["status"] == PASS_CONTENT:
            common.log("  已知差异：%s（内容一致、行序不同；见 README）" % result["path"])

    if args.json_path:
        payload = {
            "assets": str(assets.resolve()),
            "artifacts": str(Path(args.artifacts).resolve()),
            "reference": str(reference) if reference else None,
            "strict": args.strict,
            "results": results,
            "failures": failures,
        }
        Path(args.json_path).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        common.log("JSON 报告：%s" % args.json_path)

    if args.report_only:
        common.log("--report-only：无论结果如何都返回 0")
        return 0
    if failures:
        common.log("结果：失败（%d 项不一致）" % failures)
        return 2 if args.strict else 1
    common.log("结果：通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
