#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 GitHub Release 所需的版本信息 / 标题 / 说明（由 .github/workflows/release.yml 调用）。

与 tools/build-release.ps1 · tools/build-release.sh 的口径完全一致：

* 版本号从 app/build.gradle.kts 的 versionName / versionCode 解析；
* 产物名 MeaPet-v<versionName>-<abi>.APK（abi ∈ v7a / v8a / both，由 build-release 脚本产出）；
* Release 标题形如 ``v1.7.2 / 2026.9.11``（tag / tag 日期）。

发布日期直接取 tag 所指提交的日期（Asia/Shanghai 时区）。

用法::

    # CI：校验 tag（在 main 上、与 versionName 一致）并把 tag / 标题 / prerelease 等写入 $GITHUB_OUTPUT
    python3 tools/release_meta.py --tag v1.7.2 --require-main --github-output "$GITHUB_OUTPUT"

    # CI：构建完成后生成 Release 正文（含三档 APK 大小与提交列表）
    python3 tools/release_meta.py --tag v1.7.2 --notes-file release-notes.md

    # 本地预览 Release 正文（打印到 stdout）
    python3 tools/release_meta.py --tag v1.7.2 --notes-file -

只依赖 Python 3.8+ 标准库与 git，不引入第三方依赖。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, NamedTuple, Optional, Sequence, Tuple

ROOT = Path(__file__).resolve().parent.parent  # 仓库根目录

# Release 正文最多列出的提交条数（完整列表走对比链接）
MAX_COMMITS = 40

# tag 日期的时区：用中国时区避免跨日
DATE_TZ = "Asia/Shanghai"

# 三档 ABI 产物的展示顺序与说明（与 tools/build-release.* 的 -Abis 默认值一致）
ABI_ROWS: Sequence[Tuple[str, str]] = (
    ("both", "双架构（不确定选这个）"),
    ("v8a", "arm64-v8a（绝大多数设备）"),
    ("v7a", "armeabi-v7a（较老设备）"),
)


# ────────────────────────────── 基础工具 ──────────────────────────────


def die(msg: str) -> "None":
    """输出 GitHub Actions 错误注解并退出（非 Actions 环境只是普通 stderr 文本）。"""
    print(f"::error::{msg}", file=sys.stderr)
    sys.exit(1)


class GitResult(NamedTuple):
    returncode: int
    stdout: str
    stderr: str


def run_git(*args: str) -> GitResult:
    """执行 git 并读回 stdout / stderr。

    刻意不用 subprocess 的管道捕获：在受限沙箱（如本地 DSH 环境）里创建匿名管道会被
    拒绝，重定向到临时文件则各平台（CI Linux / 本地 Windows）都稳定。
    """
    with tempfile.TemporaryDirectory(prefix="release-meta-") as tmp:
        out_path = Path(tmp) / "stdout"
        err_path = Path(tmp) / "stderr"
        with out_path.open("wb") as out, err_path.open("wb") as err:
            proc = subprocess.run(
                ["git", *args],
                cwd=ROOT,
                stdout=out,
                stderr=err,
                env={**os.environ, "TZ": DATE_TZ},
            )
        return GitResult(
            proc.returncode,
            out_path.read_text(encoding="utf-8", errors="replace").strip(),
            err_path.read_text(encoding="utf-8", errors="replace").strip(),
        )


def git(*args: str) -> str:
    """执行 git 命令并返回 stdout，失败即中止。"""
    proc = run_git(*args)
    if proc.returncode != 0:
        die(f"git {' '.join(args)} 失败：{(proc.stderr or proc.stdout).strip()}")
    return proc.stdout


def git_try(*args: str) -> str:
    """执行 git 命令，失败时返回空串（用于可选查询）。"""
    proc = run_git(*args)
    return proc.stdout if proc.returncode == 0 else ""


def git_ok(*args: str) -> bool:
    return run_git(*args).returncode == 0


# ────────────────────────────── 版本 / 日期 ──────────────────────────────


def read_version(gradle_file: Path) -> Tuple[str, int]:
    """从 app/build.gradle.kts 解析 versionName / versionCode。"""
    if not gradle_file.is_file():
        die(f"找不到 {gradle_file}")
    text = gradle_file.read_text(encoding="utf-8")
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code_match = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name_match:
        die(f"未能从 {gradle_file.name} 解析 versionName")
    if not code_match:
        die(f"未能从 {gradle_file.name} 解析 versionCode")
    return name_match.group(1).strip(), int(code_match.group(1))


def version_code_at(ref: str) -> Optional[int]:
    """读取历史提交（通常是上个 tag）里的 versionCode，用于单调递增检查。"""
    text = git_try("show", f"{ref}:app/build.gradle.kts")
    if not text:
        return None
    match = re.search(r"versionCode\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def tag_date(sha: str) -> str:
    """tag 日期 = tag 所指提交的日期（Asia/Shanghai，YYYY-MM-DD）。"""
    out = git("log", "-1", "--date=format:%Y-%m-%d", "--format=%cd", sha)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", out or ""):
        die(f"无法解析 tag 所指向提交 {sha[:7]} 的日期：{out!r}")
    return out


def display_date(iso_date: str) -> str:
    """YYYY-MM-DD → 2026.9.11（不补前导零，与 Release 标题约定一致）。"""
    year, month, day = iso_date.split("-")
    return f"{int(year)}.{int(month)}.{int(day)}"


# ────────────────────────────── git 查询 ──────────────────────────────


def ensure_on_main(sha: str) -> None:
    """tag 指向的提交必须在 main 上（CI 检出时 main 为 origin/main）。"""
    for ref in ("origin/main", "refs/remotes/origin/main", "main"):
        if not git_ok("rev-parse", "--verify", "--quiet", ref):
            continue
        if not git_ok("merge-base", "--is-ancestor", sha, ref):
            die(
                f"tag 指向的提交 {sha[:7]} 不在 {ref} 上："
                "本 workflow 只发布 main 分支上的 tag"
            )
        return
    die("仓库缺少 main / origin/main 引用，无法校验 tag 是否在 main 上（请先 git fetch origin main）")


def previous_tag(sha: str) -> Optional[str]:
    """tag 提交之前最近的一个正式 tag（v1.7.1 这种），没有则返回 None。"""
    out = git_try(
        "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", f"{sha}^"
    )
    return out or None


def commit_list(range_spec: str) -> Tuple[int, List[Tuple[str, str, str]]]:
    """返回 (非 merge 提交总数, 最多 MAX_COMMITS 条 (短 sha, 完整 sha, 标题))。"""
    count_out = git_try("rev-list", "--no-merges", "--count", range_spec)
    try:
        total = int(count_out)
    except ValueError:
        total = 0
    log_out = git_try(
        "log",
        "--no-merges",
        f"--max-count={MAX_COMMITS}",
        "--format=%h%x09%H%x09%s",
        range_spec,
    )
    rows: List[Tuple[str, str, str]] = []
    for line in log_out.splitlines():
        parts = line.split("\t", 2)
        if len(parts) == 3:
            rows.append((parts[0], parts[1], parts[2]))
    return total, rows


def default_repo() -> str:
    """从 git remote origin 推断 owner/repo（如 llz121517/mea-pet-mobile）。"""
    url = git_try("remote", "get-url", "origin")
    match = re.search(r"github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?$", url)
    if not match:
        die("无法从 git remote origin 推断仓库，请用 --repo owner/repo 指定")
    return f"{match.group(1)}/{match.group(2)}"


# ────────────────────────────── Release 正文 ──────────────────────────────


def render_notes(
    *,
    tag: str,
    repo: str,
    version_name: str,
    version_code: int,
    date_text: str,
    sha: str,
    short_sha: str,
    prev_tag: Optional[str],
    prerelease: bool,
    dist_dir: Path,
) -> str:
    base = f"https://github.com/{repo}"
    lines: List[str] = []

    if prerelease:
        lines += [
            "> **预发布版本（pre-release）**：不会作为「检测更新」的最新正式版推送给用户。",
            "",
        ]

    lines += [
        f"**Tag** [`{tag}`]({base}/releases/tag/{tag}) · "
        f"**提交** [`{short_sha}`]({base}/commit/{sha}) · "
        f"**日期** {date_text} · **versionCode** {version_code}",
        "",
        "## 下载",
        "",
        "| APK | 架构 | 大小 |",
        "| --- | --- | --- |",
    ]

    for abi, desc in ABI_ROWS:
        name = f"MeaPet-v{version_name}-{abi}.APK"
        apk = dist_dir / name
        size = f"{apk.stat().st_size / (1024 * 1024):.1f} MB" if apk.is_file() else "—"
        lines.append(f"| `{name}` | {desc} | {size} |")

    lines += [
        "",
        "> 覆盖安装可保留聊天记录与设置；需要 Android 8.0（API 26）及以上。"
        "APK 由 GitHub Actions 使用 `keystore` 环境中的证书自动签名。",
        "",
    ]

    range_spec = f"{prev_tag}..{tag}" if prev_tag else tag
    total, rows = commit_list(range_spec)
    if prev_tag:
        lines.append(f"## 本次提交（{prev_tag} → {tag}，共 {total} 个）")
    else:
        lines.append(f"## 本次提交（首次发布，共 {total} 个）")
    lines.append("")
    if rows:
        for row_short, row_full, subject in rows:
            lines.append(f"- [`{row_short}`]({base}/commit/{row_full}) {subject}")
        if total > len(rows):
            lines += ["", f"> 仅列出最近 {len(rows)} 条，完整列表见下方对比链接。"]
    else:
        lines.append("- （无可列出的提交）")
    lines.append("")

    if prev_tag:
        lines += [
            f"**完整对比**：[`{prev_tag}...{tag}`]({base}/compare/{prev_tag}...{tag})",
            "",
        ]
    lines.append(f"**变更说明**：[CHANGELOG.md]({base}/blob/{tag}/CHANGELOG.md)")

    return "\n".join(lines).rstrip() + "\n"


# ────────────────────────────── 入口 ──────────────────────────────


def parse_args(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="生成 GitHub Release 的版本信息与正文（与 tools/build-release.* 同口径）",
    )
    parser.add_argument("--tag", required=True, help="要发布的 tag，例如 v1.7.2")
    parser.add_argument("--repo", default=None, help="owner/repo，缺省从 git remote origin 推断")
    parser.add_argument("--gradle", default="app/build.gradle.kts", help="版本号来源文件")
    parser.add_argument("--dist", default="dist", help="APK 产物目录（构建后才有内容）")
    parser.add_argument(
        "--require-main",
        action="store_true",
        help="要求 tag 指向的提交在 main 上（CI 用）",
    )
    parser.add_argument("--github-output", default=None, help="写入 $GITHUB_OUTPUT 的文件路径")
    parser.add_argument(
        "--notes-file",
        default=None,
        help="写入 Release 正文的文件路径；'-' 表示打印到 stdout",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)

    tag = args.tag.strip()
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.]+)?", tag):
        die(f"tag 格式应为 v1.7.2（或预发布 v1.7.2-beta1），实际为 {tag!r}")
    tag_version = tag[1:]
    # 预发布 tag 形如 v1.6.0-beta1：基础版本号仍是 1.6.0；照常发布，但标记 pre-release，
    # 这样 GitHub 的 "Latest" 与 App 的「检测更新」（/releases/latest）都不会取到它。
    base_version = tag_version.split("-", 1)[0]
    prerelease = tag_version != base_version

    if not git_ok("rev-parse", "--verify", "--quiet", f"refs/tags/{tag}"):
        die(f"本地找不到 tag {tag}（请先 git fetch --tags）")
    sha = git("rev-parse", f"{tag}^{{commit}}")
    # --short=7 在短 sha 冲突时会自动加长，直接用返回结果
    short_sha = git("rev-parse", "--short=7", sha)

    if args.require_main:
        ensure_on_main(sha)

    version_name, version_code = read_version(ROOT / args.gradle)
    if base_version != version_name:
        die(
            f"tag {tag} 与 {args.gradle} 的 versionName {version_name} 不一致："
            f"请先把 versionName 改成 {base_version} 再打 tag"
        )

    prev_tag = previous_tag(sha)
    if prev_tag:
        prev_code = version_code_at(prev_tag)
        if prev_code is not None and version_code <= prev_code:
            print(
                f"::warning::versionCode {version_code} 未大于 {prev_tag} 的 "
                f"versionCode {prev_code}，设备可能无法覆盖安装",
            )

    date_iso = tag_date(sha)
    date_text = display_date(date_iso)
    title = f"{tag} / {date_text}"
    repo = args.repo or default_repo()

    outputs = {
        "tag": tag,
        "version_name": version_name,
        "version_code": str(version_code),
        "date": date_text,
        "title": title,
        "sha": sha,
        "short_sha": short_sha,
        "prev_tag": prev_tag or "",
        "prerelease": "true" if prerelease else "false",
    }
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as handle:
            for key, value in outputs.items():
                handle.write(f"{key}={value}\n")

    if args.notes_file:
        notes = render_notes(
            tag=tag,
            repo=repo,
            version_name=version_name,
            version_code=version_code,
            date_text=date_text,
            sha=sha,
            short_sha=short_sha,
            prev_tag=prev_tag,
            prerelease=prerelease,
            dist_dir=ROOT / args.dist,
        )
        if args.notes_file == "-":
            print(notes, end="")
        else:
            Path(args.notes_file).write_text(notes, encoding="utf-8")
            print(f"已写入 Release 正文：{args.notes_file}")

    print(
        f"tag={tag} 标题={title} 版本={version_name}({version_code}) "
        f"提交={short_sha} 上个tag={prev_tag or '无'} 预发布={prerelease}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
