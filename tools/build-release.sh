#!/usr/bin/env bash
# 构建并签名 v7a / v8a / both 三个 release APK → dist/MeaPet-v<版本>-<ABI>.APK
#
# 自动构建会把当前提交的 9 位短哈希通过 -PversionSuffix 传给 gradle，拼到 versionName
# 末尾（如 1.9.0-1a2b3c4d5），便于从安装包识别构建来源；dist/ 产物文件名仍使用
# app/build.gradle.kts 里的基础版本号（MeaPet-v1.9.0-<ABI>.APK），保持既有命名不变。
# 非 git 工作区（如从源码包构建）读不到哈希时只告警，versionName 保持原样继续构建。
#
# 用法:
#   tools/build-release.sh                 # 三档全打
#   ABIS="v8a" tools/build-release.sh      # 只打 v8a
#
# 证书来自根目录 keystore.properties（复制 keystore.properties.example 填写），
# 或预先导出环境变量覆盖：
#   KEYSTORE_STORE_FILE / KEYSTORE_STORE_PASSWORD / KEYSTORE_KEY_ALIAS / KEYSTORE_KEY_PASSWORD
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABIS="${ABIS:-v7a v8a both}"
DIST="$ROOT/dist"

die() { echo "✖ $*" >&2; exit 1; }

# ── 证书来源 ──────────────────────────────────────────────
KS="$ROOT/keystore.properties"
[[ -f "$KS" ]] || die "缺少 $KS（请从 keystore.properties.example 复制并填好签名证书）"

prop() { sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*(.*)[[:space:]]*$/\1/p" "$KS" | head -1; }
resolve_path() { case "$1" in /*) echo "$1" ;; *) echo "$ROOT/$1" ;; esac; }

if [[ -z "${KEYSTORE_STORE_FILE:-}" ]]; then sf="$(prop storeFile)"; [[ -n "$sf" ]] && export KEYSTORE_STORE_FILE="$(resolve_path "$sf")"; fi
if [[ -z "${KEYSTORE_STORE_PASSWORD:-}" ]]; then p="$(prop storePassword)";     [[ -n "$p" ]] && export KEYSTORE_STORE_PASSWORD="$p"; fi
if [[ -z "${KEYSTORE_KEY_ALIAS:-}" ]]; then p="$(prop keyAlias)";               [[ -n "$p" ]] && export KEYSTORE_KEY_ALIAS="$p"; fi
if [[ -z "${KEYSTORE_KEY_PASSWORD:-}" ]]; then p="$(prop keyPassword)";         [[ -n "$p" ]] && export KEYSTORE_KEY_PASSWORD="$p"; fi

for v in KEYSTORE_STORE_FILE KEYSTORE_STORE_PASSWORD KEYSTORE_KEY_ALIAS KEYSTORE_KEY_PASSWORD; do
  [[ -n "${!v:-}" ]] || die "缺少证书配置：$v（keystore.properties 或环境变量）"
done
[[ -f "$KEYSTORE_STORE_FILE" ]] || die "找不到 keystore 文件：$KEYSTORE_STORE_FILE"

# ── 版本号 ────────────────────────────────────────────────
# 取的是基础版本号（versionName 字面量），不含自动构建追加的 -短哈希后缀；
# dist 产物文件名沿用 MeaPet-v<基础版本>-<ABI>.APK，保持既有命名不变。
VER="$(sed -nE 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)"
[[ -n "$VER" ]] || die "未能从 app/build.gradle.kts 解析 versionName"

# ── 当前提交短哈希（9 位）────────────────────────────────
# 只负责取哈希并通过 -PversionSuffix 传给 gradle；versionName 的拼接在
# app/build.gradle.kts 里完成。git 短哈希冲突时 --short=9 会自动加长，仍视为合法。
VERSION_SUFFIX="$(git -C "$ROOT" rev-parse --short=9 HEAD 2>/dev/null || true)"
if [[ "$VERSION_SUFFIX" =~ ^[0-9a-fA-F]{7,40}$ ]]; then
  echo "当前提交短哈希：$VERSION_SUFFIX（拼到 versionName 末尾）"
else
  VERSION_SUFFIX=""
  echo "⚠ 未能从 git 读取当前提交短哈希，本次构建 versionName 不带 -<短哈希> 后缀" >&2
fi

mkdir -p "$DIST"
cd "$ROOT"

for ABI in $ABIS; do
  echo "── assembleRelease ($ABI) ──"
  # -PappAbi 过滤 ABI；-PversionSuffix 把自动构建的提交短哈希拼到 versionName 末尾
  GRADLE_ARGS=(":app:assembleRelease" "-PappAbi=$ABI" "--console=plain")
  if [[ -n "$VERSION_SUFFIX" ]]; then
    GRADLE_ARGS+=("-PversionSuffix=$VERSION_SUFFIX")
  fi
  ./gradlew "${GRADLE_ARGS[@]}"

  OUT="$ROOT/app/build/outputs/apk/release"
  SRC=""
  [[ -f "$OUT/app-release.apk" ]]          && SRC="$OUT/app-release.apk"
  [[ -z "$SRC" && -f "$OUT/app-release-unsigned.apk" ]] && SRC="$OUT/app-release-unsigned.apk"
  [[ -n "$SRC" ]] || die "未找到产物（$ABI）：$OUT"
  if [[ "$SRC" == *"-unsigned.apk" ]]; then
    die "release($ABI) 未签名——请检查 keystore.properties 是否被 gradle 读到（产物为 app-release-unsigned.apk）"
  fi

  DST="$DIST/MeaPet-v$VER-$ABI.APK"
  cp -f "$SRC" "$DST"
  SIZE_MB=$(awk "BEGIN{printf \"%.1f\", $(stat -c%s "$DST")/1048576}")
  echo "✔ $DST (${SIZE_MB} MB)"
done

echo
echo "产物已放入 $DIST ："
for f in "$DIST"/MeaPet-*.APK; do echo "  $(basename "$f")"; done
