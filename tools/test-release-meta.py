#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/release_meta.py 的回归检查（不需要 GitHub、不发版、不联网）。

在临时 git 仓库里造出 tag / main / versionName，覆盖发布前的关键判断：

1. tag 不在 main 上 → 拒绝发布；
2. tag 在 main 上 → 标题「tag / tag 日期」（日期不补前导零）；
3. tag 与 app/build.gradle.kts 的 versionName 不一致 → 拒绝发布；
4. 带后缀的 tag（v1.0.0-beta1）→ prerelease=true，照常发布；
5. Release 正文含下载表 / 预发布提示 / 提交列表，且上个 tag 能正确识别。

用法：python tools/test-release-meta.py
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, List

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools" / "release_meta.py"

FAILURES: List[str] = []

# 统一用 UTF-8 输出，避免 Windows 控制台按 GBK 编码时中文/特殊字符报错
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        try:
            _stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001 - 老版本 Python 或特殊终端下忽略
            pass


# ────────────────────────────── 进程 / 断言 ──────────────────────────────


def capture(cmd: List[str], cwd: Path, extra_env: "Dict[str, str] | None" = None) -> "tuple[int, str, str]":
    """执行命令并读回标准输出 / 错误。

    不用 subprocess 的管道捕获：受限沙箱（本地 DSH 环境）禁止匿名管道，
    重定向到临时文件在 CI 与本地都可用。
    """
    env = {
        **os.environ,
        "TZ": "Asia/Shanghai",
        # 子进程（release_meta）输出统一 UTF-8，避免各平台默认编码不一致
        "PYTHONIOENCODING": "utf-8",
        **(extra_env or {}),
    }
    with tempfile.TemporaryDirectory(prefix="test-release-meta-") as tmp:
        out_path = Path(tmp) / "stdout"
        err_path = Path(tmp) / "stderr"
        with out_path.open("wb") as out, err_path.open("wb") as err:
            proc = subprocess.run(
                cmd,
                cwd=str(cwd),
                stdout=out,
                stderr=err,
                env=env,
            )
        return (
            proc.returncode,
            out_path.read_text(encoding="utf-8", errors="replace"),
            err_path.read_text(encoding="utf-8", errors="replace"),
        )


def git(cwd: Path, *args: str, extra_env: "Dict[str, str] | None" = None) -> str:
    code, out, err = capture(["git", *args], cwd, extra_env)
    if code != 0:
        raise SystemExit(f"fixture git {' '.join(args)} 失败：{err.strip() or out.strip()}")
    return out.strip()


def commit(cwd: Path, message: str, iso_date: str) -> None:
    """用固定时间提交，让「tag 日期」断言可复现。"""
    when = f"{iso_date}T12:00:00+08:00"
    git(
        cwd,
        "commit",
        "-q",
        "-m",
        message,
        extra_env={"GIT_AUTHOR_DATE": when, "GIT_COMMITTER_DATE": when},
    )


def meta(cwd: Path, *args: str) -> "tuple[int, str, str]":
    """跑测试仓库里那份 release_meta.py（ROOT 由脚本位置推导，才落在 fixture 上）。"""
    return capture([sys.executable, str(cwd / "tools" / "release_meta.py"), *args], cwd)


def check(label: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"PASS: {label}")
    else:
        print(f"FAIL: {label}{(' — ' + detail) if detail else ''}")
        FAILURES.append(label)


# ────────────────────────────── 测试仓库 ──────────────────────────────


def build_fixture(root: Path) -> Dict[str, Path]:
    fx = root / "repo"
    (fx / "tools").mkdir(parents=True)
    (fx / "app").mkdir()
    shutil.copy2(SCRIPT, fx / "tools" / "release_meta.py")
    (fx / "app" / "build.gradle.kts").write_text(
        'versionCode =  7\nversionName = "1.0.0"\n', encoding="utf-8"
    )

    git(fx, "init", "-q", "-b", "main")
    git(fx, "config", "user.email", "release-test@example.com")
    git(fx, "config", "user.name", "MeaPet Release Test")
    git(fx, "config", "commit.gpgsign", "false")
    # 仅用于推断 owner/repo，不会联网
    git(fx, "remote", "add", "origin", "https://github.com/llz121517/mea-pet-mobile.git")
    git(fx, "add", "-A")
    commit(fx, "chore: init 1.0.0", "2026-01-02")
    main_sha = git(fx, "rev-parse", "HEAD")
    git(fx, "update-ref", "refs/remotes/origin/main", main_sha)
    return {"dir": fx, "main_sha": main_sha}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def outputs_of(path: Path) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for line in read(path).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    return result


# ────────────────────────────── 用例 ──────────────────────────────


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="test-release-meta-") as tmp:
        fixture = build_fixture(Path(tmp))
        fx = fixture["dir"]

        # 1) tag 指向 dev 上的提交 → 拒绝发布
        git(fx, "checkout", "-q", "-b", "dev")
        (fx / "dev.txt").write_text("dev only\n", encoding="utf-8")
        git(fx, "add", "-A")
        commit(fx, "feat: dev only", "2026-01-05")
        git(fx, "tag", "v1.0.0")
        code, _, err = meta(fx, "--tag", "v1.0.0", "--require-main")
        check("tag 不在 main 上 → 拒绝", code != 0 and "不在" in err, f"code={code} err={err.strip()}")

        # 2) tag 移到 main 上 → 正常出标题（日期取 tag 提交日期，不补前导零）
        git(fx, "tag", "-d", "v1.0.0")
        git(fx, "checkout", "-q", "main")
        git(fx, "tag", "v1.0.0")
        out_file = fx / "gh-output.txt"
        notes_file = fx / "notes.md"
        code, out, err = meta(
            fx,
            "--tag",
            "v1.0.0",
            "--require-main",
            "--github-output",
            str(out_file),
            "--notes-file",
            str(notes_file),
        )
        check("tag 在 main 上 → 发布信息生成成功", code == 0, f"code={code} err={err.strip()}")
        outputs = outputs_of(out_file)
        check(
            "标题为「tag / tag 日期」（2026.1.2 不补零）",
            outputs.get("title") == "v1.0.0 / 2026.1.2",
            f"title={outputs.get('title')!r}",
        )
        check("识别为正式版", outputs.get("prerelease") == "false", f"prerelease={outputs.get('prerelease')!r}")
        check("无更早 tag → prev_tag 为空", outputs.get("prev_tag", None) == "", f"prev_tag={outputs.get('prev_tag')!r}")
        notes = read(notes_file)
        check("正文含下载表", "## 下载" in notes and "MeaPet-v1.0.0-both.APK" in notes)
        check("正文含首次发布标记", "首次发布" in notes)

        # 3) tag 与 versionName 不一致 → 拒绝发布
        git(fx, "tag", "v1.0.1")
        code, _, err = meta(fx, "--tag", "v1.0.1")
        check("tag 与 versionName 不一致 → 拒绝", code != 0 and "不一致" in err, f"code={code} err={err.strip()}")
        git(fx, "tag", "-d", "v1.0.1")

        # 4) 带后缀的 tag → 照常发布，但标为 prerelease
        git(fx, "tag", "v1.0.0-beta1")
        beta_out = fx / "beta-output.txt"
        beta_notes = fx / "beta-notes.md"
        code, _, err = meta(
            fx,
            "--tag",
            "v1.0.0-beta1",
            "--github-output",
            str(beta_out),
            "--notes-file",
            str(beta_notes),
        )
        beta = outputs_of(beta_out)
        check("后缀 tag 仍发布（exit 0）", code == 0, f"code={code} err={err.strip()}")
        check("后缀 tag → prerelease=true", beta.get("prerelease") == "true", f"prerelease={beta.get('prerelease')!r}")
        check(
            "后缀 tag 标题为「tag / tag 日期」",
            beta.get("title") == "v1.0.0-beta1 / 2026.1.2",
            f"title={beta.get('title')!r}",
        )
        check("后缀 tag 正文标注 pre-release", "pre-release" in read(beta_notes))
        git(fx, "tag", "-d", "v1.0.0-beta1")

        # 5) 版本号 +1 后再打 tag → 能识别上个 tag 与提交范围
        (fx / "app" / "build.gradle.kts").write_text(
            'versionCode =  8\nversionName = "1.0.1"\n', encoding="utf-8"
        )
        git(fx, "add", "-A")
        commit(fx, "chore: 版本号升至 1.0.1", "2026-02-03")
        git(fx, "tag", "v1.0.1")
        next_out = fx / "next-output.txt"
        next_notes = fx / "next-notes.md"
        code, _, err = meta(
            fx,
            "--tag",
            "v1.0.1",
            "--github-output",
            str(next_out),
            "--notes-file",
            str(next_notes),
        )
        nxt = outputs_of(next_out)
        check("新版本发布成功", code == 0, f"code={code} err={err.strip()}")
        check("识别上个 tag", nxt.get("prev_tag") == "v1.0.0", f"prev_tag={nxt.get('prev_tag')!r}")
        check(
            "标题日期取 tag 提交日期（2026.2.3 不补零）",
            nxt.get("title") == "v1.0.1 / 2026.2.3",
            f"title={nxt.get('title')!r}",
        )
        next_notes_text = read(next_notes)
        check("提交列表按上个 tag 起算", "v1.0.0 → v1.0.1" in next_notes_text, next_notes_text.splitlines()[0] if next_notes_text else "")
        check("含完整对比链接", "compare/v1.0.0...v1.0.1" in next_notes_text)

    print()
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} 项 — {'; '.join(FAILURES)}")
        return 1
    print("PASS: release_meta 全部检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
