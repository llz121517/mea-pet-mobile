#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把生成的资产 + 外部模型拼成可分发模型包 ``dist/``。

产物（6 个文件 + zip + manifest）：

```
dist/
├── model.onnx            # 由 --model 指定（本管线不生成，见 build_model.md）
├── lexicon.txt           # <assets>/lexicon.txt
├── tokens.txt            # <assets>/tokens.txt
├── fsts/{phone,date,number}.fst
├── tts-model-v1.zip      # 保留 fsts/ 子目录结构（App「从本地导入」用）
└── manifest.json         # [{"path","size","sha256"}, ...]
```

注：``ja_reading_map.json`` 与 ``en_words.bloom`` **不进模型包**（随 APK assets 分发），
本脚本不写 app/ 目录，需要时手工复制。

用法::

    python tools/tts/build_package.py --assets tools/tts/out \
        --model <path/to/model.onnx> --out tools/tts/dist
"""
from __future__ import annotations

import argparse
import json
import shutil
import zipfile
from pathlib import Path

from lib import common

#: (assets 内相对路径, dist 内相对路径, artifacts.json 的 key)
FILES: list[tuple[str, str, str | None]] = [
    ("model.onnx", "model.onnx", "model.onnx"),
    ("lexicon.txt", "lexicon.txt", "lexicon.txt"),
    ("tokens.txt", "tokens.txt", "tokens.txt"),
    ("fsts/phone.fst", "fsts/phone.fst", "fsts/phone.fst"),
    ("fsts/date.fst", "fsts/date.fst", "fsts/date.fst"),
    ("fsts/number.fst", "fsts/number.fst", "fsts/number.fst"),
]

ZIP_NAME = "tts-model-v1.zip"
#: 固定 zip 内时间戳 → 相同输入得到相同 zip（发布包带真实时间戳，不参与一致性断言）
ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)


def build_zip(dist: Path, files: list[dict]) -> Path:
    zip_path = dist / ZIP_NAME
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for item in files:
            info = zipfile.ZipInfo(item["path"], date_time=ZIP_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(dist / item["path"], "rb") as src:
                zf.writestr(info, src.read())
    return zip_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="组装 dist/（6 文件 + zip + manifest）")
    parser.add_argument("--assets", default=str(common.OUT_DIR),
                        help="生成资产目录（默认 tools/tts/out）")
    parser.add_argument("--model", default=None,
                        help="model.onnx 路径（默认 <assets>/model.onnx；本管线不生成模型）")
    parser.add_argument("--out", default=str(common.DIST_DIR), help="输出目录（默认 tools/tts/dist）")
    parser.add_argument("--artifacts", default=str(common.ARTIFACTS_JSON),
                        help="artifacts.json 路径")
    parser.add_argument("--strict", action="store_true",
                        help="任一文件与 artifacts.json 不一致就退出码 2（默认只告警）")
    args = parser.parse_args(argv)

    assets = Path(args.assets)
    dist = Path(args.out)
    if dist.exists():
        shutil.rmtree(dist)
    dist.mkdir(parents=True)

    model = Path(args.model) if args.model else assets / "model.onnx"
    if not model.is_file():
        common.die("缺少 model.onnx：%s\n"
                   "  本管线不生成模型；请见 tools/tts/build_model.md，"
                   "或用 --model <path/to/model.onnx> 指定已有文件。" % model)

    artifacts, _ = common.load_artifacts(args.artifacts)
    manifest: list[dict] = []
    mismatched: list[str] = []
    total = 0
    common.log("打包到 %s" % dist)
    for src_rel, dst_rel, artifact_key in FILES:
        if src_rel == "model.onnx":
            src = model
        else:
            src = assets / src_rel
            if not src.is_file() and src_rel.startswith("fsts/"):
                # fsts 由 download_data.py 落在 data/fsts
                src = common.DATA_DIR / src_rel
        if not src.is_file():
            common.die("缺少输入：%s（先运行对应的 build_*.py / download_data.py）" % src)
        if common.file_size(src) == 0:
            common.die("输入是空文件：%s" % src)
        dst = dist / dst_rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        size = common.file_size(dst)
        sha = common.sha256_file(dst)
        total += size
        manifest.append({"path": dst_rel, "size": size, "sha256": sha})
        expected = artifacts.get(artifact_key or "")
        if expected is None:
            status = "未登记"
        elif expected["size"] == size and expected["sha256"] == sha:
            status = "OK"
        else:
            status = "DIFF"
            mismatched.append(dst_rel)
        common.log("  %-16s %11d B  %s  %s" % (dst_rel, size, sha[:16] + "…", status))

    zip_path = build_zip(dist, manifest)
    manifest_json = json.dumps(manifest, ensure_ascii=False, indent=2)
    (dist / "manifest.json").write_text(manifest_json, encoding="utf-8")

    common.log("")
    common.log("dist：%d 个文件 / %s；zip %s / %s；manifest.json %d B"
               % (len(manifest), common.human_size(total), ZIP_NAME,
                  common.human_size(common.file_size(zip_path)),
                  common.file_size(dist / "manifest.json")))
    common.log("  zip 内结构：%s" % ", ".join(item["path"] for item in manifest))
    if mismatched:
        common.warn("以下文件与 artifacts.json 不一致：%s" % ", ".join(mismatched))
        if args.strict:
            return 2
    common.log("提示：ja_reading_map.json / en_words.bloom 不进模型包，"
               "分别来自 build_lexicon_ja.py 与 build_bloom.py，随 APK assets 分发。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
