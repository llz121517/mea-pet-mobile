#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""下载并校验 G2P 管线所需的外部数据（JMdict / open_jtalk 词典 / ruleFsts）。

用法::

    python tools/tts/download_data.py              # 联网下载缺失项（已有且 sha 正确则跳过）
    python tools/tts/download_data.py --offline    # 不联网：校验 data/，缺失项从开发机 .tools 缓存复制
    python tools/tts/download_data.py --check      # 只校验，不下载也不复制
    python tools/tts/download_data.py --force      # 忽略已有文件，强制重新下载

数据源与 sha256 见 ``tools/tts/data-sources.json``；落盘目录默认 ``tools/tts/data/``
（gitignored）。每个文件都会校验 sha256，校验失败即视为下载失败并尝试下一个镜像。
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

from lib import common

USER_AGENT = "meapet-tts/1.0"
CHUNK = 1 << 20


# --------------------------------------------------------------------------- #
# 校验
# --------------------------------------------------------------------------- #
def verify_file(path: Path, size: int | None, sha256: str | None) -> bool:
    if not path.is_file():
        return False
    if size is not None and common.file_size(path) != size:
        return False
    if sha256 is not None and common.sha256_file(path) != sha256:
        return False
    return True


def verify_source(data_dir: Path, source: dict) -> bool:
    dest = data_dir / source["dest"]
    verify_files = source.get("verify_files")
    if verify_files:
        if not dest.is_dir():
            return False
        for name, expect in verify_files.items():
            if not verify_file(dest / name, expect.get("size"), expect.get("sha256")):
                return False
        return True
    return verify_file(dest, source.get("size"), source.get("sha256"))


def describe_source(source: dict) -> str:
    dest = source["dest"]
    if source.get("verify_files"):
        return "%s（目录，%d 个校验文件）" % (dest, len(source["verify_files"]))
    return "%s（%s）" % (dest, common.human_size(source["size"] or 0))


# --------------------------------------------------------------------------- #
# 下载 / 解包
# --------------------------------------------------------------------------- #
def download(url: str, dst: Path, timeout: float) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    common.log("    下载 %s" % url)
    started = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp, open(dst, "wb") as f:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        last_report = 0
        while True:
            chunk = resp.read(CHUNK)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total and done - last_report >= max(total // 5, CHUNK):
                last_report = done
                common.log("      %5.1f%%  %s / %s"
                           % (100.0 * done / total, common.human_size(done), common.human_size(total)))
    common.log("    完成 %.1fs  %s" % (time.time() - started, common.human_size(common.file_size(dst))))


def extract_zip(archive: Path, source: dict, dest: Path) -> None:
    member = source["archive"]["member"]
    with zipfile.ZipFile(archive) as zf:
        try:
            data = zf.read(member)
        except KeyError:
            raise SystemExit("压缩包内找不到 %r：%s" % (member, [i.filename for i in zf.infolist()]))
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)


def extract_tar_gz(archive: Path, source: dict, dest: Path) -> None:
    strip = int(source["archive"].get("strip_components", 0))
    dest.mkdir(parents=True, exist_ok=True)
    root = dest.resolve()
    with tarfile.open(archive, "r:gz") as tf:
        for member in tf.getmembers():
            parts = [p for p in Path(member.name).parts if p not in ("", ".")]
            if len(parts) <= strip:
                continue
            rel = Path(*parts[strip:])
            target = (dest / rel).resolve()
            if root not in target.parents and target != root:
                raise SystemExit("压缩包内路径越界，拒绝解包：%r" % member.name)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                src = tf.extractfile(member)
                if src is None:
                    continue
                with src, open(target, "wb") as f:
                    shutil.copyfileobj(src, f, CHUNK)


def install(source: dict, data_dir: Path, archive: Path) -> None:
    dest = data_dir / source["dest"]
    archive_info = source.get("archive")
    if archive_info:
        fmt = archive_info["format"]
        if fmt == "zip":
            extract_zip(archive, source, dest)
        elif fmt in ("tar.gz", "tgz"):
            extract_tar_gz(archive, source, dest)
        else:
            raise SystemExit("未知压缩格式：%s" % fmt)
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(archive, dest)


# --------------------------------------------------------------------------- #
# 开发机缓存（--offline）
# --------------------------------------------------------------------------- #
def legacy_clone(source: dict) -> list[Path]:
    """开发机上可能已存在的旧位置（仅 `.tools/tts-spike`，不保证存在）。"""
    spike = common.REPO_ROOT / ".tools" / "tts-spike"
    candidates: list[Path] = []
    sid = source["id"]
    if sid == "jmdict":
        candidates.append(spike / "pretrained" / "jmdict-eng-common-3.6.2.json")
    elif sid.endswith("_fst"):
        candidates.append(spike / "model" / "fsts" / Path(source["dest"]).name)
    elif sid == "open_jtalk_dic":
        site = Path(sys.executable).parent.parent / "Lib" / "site-packages"
        candidates.append(site / "pyopenjtalk" / "open_jtalk_dic_utf_8-1.11")
    return candidates


def copy_legacy(source: dict, data_dir: Path) -> bool:
    dest = data_dir / source["dest"]
    for candidate in legacy_clone(source):
        if not candidate.exists():
            continue
        if candidate.is_dir():
            shutil.copytree(candidate, dest, dirs_exist_ok=True)
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(candidate, dest)
        if verify_source(data_dir, source):
            common.log("    已从开发机缓存复制：%s" % candidate)
            return True
        common.warn("缓存 %s 校验失败，继续尝试其他来源" % candidate)
    return False


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="下载并校验 G2P 管线外部数据")
    parser.add_argument("--data", default=str(common.DATA_DIR), help="数据目录（默认 tools/tts/data）")
    parser.add_argument("--sources", default=str(common.DATA_SOURCES_JSON), help="data-sources.json 路径")
    parser.add_argument("--offline", action="store_true",
                        help="不联网：只校验本地 data/；缺失项尝试从 .tools/tts-spike 缓存复制")
    parser.add_argument("--check", action="store_true", help="只校验，不下载/不复制")
    parser.add_argument("--force", action="store_true", help="忽略已有文件，强制重新下载")
    parser.add_argument("--timeout", type=float, default=120.0, help="单次网络超时秒数（默认 120）")
    args = parser.parse_args(argv)

    data_dir = Path(args.data)
    data_dir.mkdir(parents=True, exist_ok=True)
    manifest = common.read_json(args.sources)
    sources: list[dict] = manifest["sources"]
    common.log("数据目录：%s" % data_dir.resolve())
    common.log("数据源清单：%s（%d 项）" % (args.sources, len(sources)))

    failures: list[str] = []
    for source in sources:
        sid = source["id"]
        common.log("")
        common.log("[%s] %s" % (sid, describe_source(source)))
        if source.get("description"):
            common.log("    %s" % source["description"])

        if not args.force and verify_source(data_dir, source):
            common.log("    已存在且 sha256 正确，跳过")
            continue
        if args.check:
            failures.append("%s：本地数据缺失或 sha256 不符（--check 不下载）" % sid)
            continue
        if args.offline:
            if copy_legacy(source, data_dir):
                continue
            failures.append("%s：--offline 下本地缺失且开发机缓存不可用（参见 data-sources.json 的 urls）" % sid)
            continue

        # 联网：按 urls 顺序尝试，任何一步校验失败都换下一个镜像
        installed = False
        errors: list[str] = []
        for url in source["urls"]:
            tmp_dir = Path(tempfile.mkdtemp(prefix="meapet-tts-dl-"))
            archive = tmp_dir / (Path(source["dest"]).name + ".download")
            try:
                download(url, archive, args.timeout)
                archive_info = source.get("archive") or {}
                if archive_info.get("sha256") or archive_info.get("size"):
                    ok = verify_file(archive, archive_info.get("size"), archive_info.get("sha256"))
                    if not ok:
                        actual = "%d B %s" % (common.file_size(archive), common.sha256_file(archive))
                        raise ValueError("压缩包校验失败：期望 %s B %s，实际 %s"
                                         % (archive_info.get("size"), archive_info.get("sha256"), actual))
                    common.log("    压缩包 sha256 正确：%s" % archive_info["sha256"])
                install(source, data_dir, archive)
                if not verify_source(data_dir, source):
                    raise ValueError("解包后校验失败：%s" % (data_dir / source["dest"]))
                common.log("    校验通过：%s" % describe_source(source))
                installed = True
                break
            except Exception as exc:  # noqa: BLE001 - 逐个镜像容错
                errors.append("%s -> %s: %s" % (url, type(exc).__name__, exc))
                common.warn("该来源失败：%s" % exc)
            finally:
                shutil.rmtree(tmp_dir, ignore_errors=True)

        if not installed:
            for err in errors:
                common.log("    失败来源：%s" % err)
            failures.append("%s：所有下载来源均失败（详见上方日志）" % sid)

    common.log("")
    if failures:
        common.log("结果：%d 项失败" % len(failures))
        for item in failures:
            common.log("  - %s" % item)
        return 1
    common.log("结果：全部 %d 项数据齐全且 sha256 校验通过" % len(sources))
    common.log("提示：build_lexicon_ja.py 会把 OPEN_JTALK_DICT_DIR 指向 %s"
               % (data_dir / "open_jtalk_dic_utf_8-1.11"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
