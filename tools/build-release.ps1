#requires -Version 5.1
<#
.SYNOPSIS
  构建并签名 v7a / v8a / both 三个 release APK → dist/MeaPet-v<版本>-<ABI>.APK

.DESCRIPTION
  依次用 -PappAbi 过滤 ABI 跑 assembleRelease，把签名产物复制改名进 dist/。
  签名证书来自根目录 keystore.properties（或 4 个环境变量覆盖）：
    KEYSTORE_STORE_FILE / KEYSTORE_STORE_PASSWORD / KEYSTORE_KEY_ALIAS / KEYSTORE_KEY_PASSWORD

  自动构建会把当前提交的 9 位短哈希通过 -PversionSuffix 传给 gradle，拼到 versionName
  末尾（如 1.9.0-1a2b3c4d5），便于从安装包识别构建来源；dist/ 产物文件名仍使用
  app/build.gradle.kts 里的基础版本号（MeaPet-v1.9.0-<ABI>.APK），保持既有命名不变。
  非 git 工作区（如从源码包构建）读不到哈希时只告警，versionName 保持原样继续构建。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
  powershell -ExecutionPolicy Bypass -File tools\build-release.ps1 -Abis v8a        # 只打 v8a
#>
[CmdletBinding()]
param(
    # 要构建的 ABI，逗号分隔，可用的值：v7a / v8a / both
    [string]$Abis = "v7a,v8a,both"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dist = Join-Path $root 'dist'

function Read-Props([string]$path) {
    $map = @{}
    if (-not (Test-Path $path)) { return $map }
    foreach ($line in [System.IO.File]::ReadAllLines($path)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $trimmed = $line.TrimStart()
        if ($trimmed.StartsWith('#')) { continue }
        $i = $line.IndexOf('=')
        if ($i -lt 0) { continue }
        $map[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
    }
    return $map
}

# ── 证书来源：keystore.properties（环境变量可覆盖）──────────────────────
$ksFile = Join-Path $root 'keystore.properties'
if (-not (Test-Path $ksFile)) {
    Write-Error "缺少 $ksFile（请从 keystore.properties.example 复制并填好签名证书）"
    exit 1
}
$props = Read-Props $ksFile

function Resolve-KsPath([string]$p) {
    if ([System.IO.Path]::IsPathRooted($p)) { return $p }
    return Join-Path $root $p
}

if ([string]::IsNullOrEmpty($env:KEYSTORE_STORE_FILE) -and $props.ContainsKey('storeFile')) {
    $env:KEYSTORE_STORE_FILE = Resolve-KsPath $props['storeFile']
}
if ([string]::IsNullOrEmpty($env:KEYSTORE_STORE_PASSWORD) -and $props.ContainsKey('storePassword')) {
    $env:KEYSTORE_STORE_PASSWORD = $props['storePassword']
}
if ([string]::IsNullOrEmpty($env:KEYSTORE_KEY_ALIAS) -and $props.ContainsKey('keyAlias')) {
    $env:KEYSTORE_KEY_ALIAS = $props['keyAlias']
}
if ([string]::IsNullOrEmpty($env:KEYSTORE_KEY_PASSWORD) -and $props.ContainsKey('keyPassword')) {
    $env:KEYSTORE_KEY_PASSWORD = $props['keyPassword']
}

$missing = @()
if ([string]::IsNullOrEmpty($env:KEYSTORE_STORE_FILE)) { $missing += 'storeFile' }
if ([string]::IsNullOrEmpty($env:KEYSTORE_STORE_PASSWORD)) { $missing += 'storePassword' }
if ([string]::IsNullOrEmpty($env:KEYSTORE_KEY_ALIAS)) { $missing += 'keyAlias' }
if ([string]::IsNullOrEmpty($env:KEYSTORE_KEY_PASSWORD)) { $missing += 'keyPassword' }
if ($missing.Count -gt 0) {
    Write-Error "keystore.properties 缺少: $($missing -join ', ')（或设对应环境变量）"
    exit 1
}
if (-not (Test-Path $env:KEYSTORE_STORE_FILE)) {
    Write-Error "找不到 keystore 文件: $env:KEYSTORE_STORE_FILE"
    exit 1
}

# ── 从 app/build.gradle.kts 取版本号 ─────────────────────────────────────
# 取的是基础版本号（versionName 字面量），不包含自动构建追加的 -短哈希后缀；
# dist 产物文件名沿用 MeaPet-v<基础版本>-<ABI>.APK，保持既有命名不变。
$buildFile = Join-Path $root 'app\build.gradle.kts'
$m = [regex]::Match((Get-Content $buildFile -Raw), 'versionName\s*=\s*"([^"]+)"')
if (-not $m.Success) {
    Write-Error "未能从 app\build.gradle.kts 解析 versionName"
    exit 1
}
$ver = $m.Groups[1].Value

# ── 当前提交短哈希（9 位）──────────────────────────────────────────────
# 只负责取哈希并通过 -PversionSuffix 传给 gradle；versionName 的拼接在
# app/build.gradle.kts 里完成（dist 产物文件名继续用上面的基础版本号，保持不变）。
# git 短哈希冲突时 --short=9 会自动加长，仍视为合法。
$versionSuffix = $null
try {
    $versionSuffix = (& git -C $root rev-parse --short=9 HEAD 2>$null | Out-String).Trim()
} catch {
    $versionSuffix = $null
}
if ([string]::IsNullOrWhiteSpace($versionSuffix) -or $versionSuffix -notmatch '^[0-9a-fA-F]{7,40}$') {
    Write-Warning "未能从 git 读取当前提交短哈希，本次构建 versionName 不带 -<短哈希> 后缀"
    $versionSuffix = $null
} else {
    Write-Host "当前提交短哈希：$versionSuffix（拼到 versionName 末尾）"
}

# ── 构建 ────────────────────────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$gradlew = Join-Path $root 'gradlew.bat'
Push-Location $root
try {
    foreach ($abi in ($Abis -split ',' | Where-Object { $_ })) {
        Write-Host "── assembleRelease ($abi) ──"
        # -PappAbi 过滤 ABI；-PversionSuffix 把自动构建的提交短哈希拼到 versionName 末尾
        $gradleArgs = @(':app:assembleRelease', "-PappAbi=$abi", '--console=plain')
        if ($versionSuffix) { $gradleArgs += "-PversionSuffix=$versionSuffix" }
        & $gradlew @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "assembleRelease($abi) 失败，退出码 $LASTEXITCODE" }

        $outDir = Join-Path $root 'app\build\outputs\apk\release'
        $signed   = Join-Path $outDir 'app-release.apk'
        $unsigned = Join-Path $outDir 'app-release-unsigned.apk'
        $src = $null
        if (Test-Path $signed)   { $src = $signed }
        elseif (Test-Path $unsigned) { $src = $unsigned }
        if (-not $src) { throw "未找到产物（$abi）：$outDir" }
        if ($src -eq $unsigned) {
            throw "release($abi) 未签名——请检查 keystore.properties 是否被 gradle 读到（生成的是 app-release-unsigned.apk）"
        }

        $dst = Join-Path $dist "MeaPet-v$ver-$abi.APK"
        Copy-Item -Path $src -Destination $dst -Force
        $sizeMb = [math]::Round((Get-Item $dst).Length / 1MB, 1)
        Write-Host "✔ $dst ($sizeMb MB)"
    }
} finally {
    Pop-Location
}

Write-Host "`n产物已放入 $dist :"
Get-ChildItem $dist -Filter 'MeaPet-*.APK' | ForEach-Object { Write-Host "  $($_.Name)" }
