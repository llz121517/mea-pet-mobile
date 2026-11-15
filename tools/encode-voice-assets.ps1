#requires -Version 5.1
<#
.SYNOPSIS
  把 assets/voice/ 下的触摸语音原地重新编码为 Opus（.ogg）

.DESCRIPTION
  1.7.x 及更早版本的触摸语音是 32kHz 单声道 WAV（21 个文件共 2.7MB）。
  1.8.0 起改为 Opus 48kHz 单声道 48kbps（共约 225KB，−92%），听感无明显损失。
  原 WAV 已从仓库移除；需要重转时先从 git 历史取回，再运行本脚本：
    git checkout v1.7.2 -- app/src/main/assets/voice

  依赖带 libopus 的 ffmpeg（Windows：winget install Gyan.FFmpeg），
  默认从 PATH 查找，也可用 -Ffmpeg 指定完整路径。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\encode-voice-assets.ps1
  powershell -ExecutionPolicy Bypass -File tools\encode-voice-assets.ps1 -Ffmpeg "C:\Program Files\ffmpeg\bin\ffmpeg.exe"
#>
[CmdletBinding()]
param(
    # ffmpeg 可执行文件路径
    [string]$Ffmpeg = "ffmpeg"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$voiceRoot = Join-Path $root 'app\src\main\assets\voice'

$wavs = @(Get-ChildItem $voiceRoot -Recurse -Filter *.wav)
if ($wavs.Count -eq 0) {
    throw "未找到 wav 文件：$voiceRoot（原 WAV 已从仓库移除，见脚本说明）"
}

$inTotal = 0L
$outTotal = 0L
foreach ($wav in $wavs) {
    $out = [System.IO.Path]::ChangeExtension($wav.FullName, '.ogg')
    & $Ffmpeg -y -hide_banner -loglevel error -i $wav.FullName `
        -c:a libopus -b:a 48k -ac 1 -ar 48000 -application audio $out
    if ($LASTEXITCODE -ne 0) { throw "编码失败：$($wav.Name)" }

    $inKB = $wav.Length / 1KB
    $outSize = (Get-Item $out).Length
    $outKB = $outSize / 1KB
    $inTotal += $wav.Length
    $outTotal += $outSize
    Write-Host ("{0}  {1:N0} KB -> {2:N0} KB" -f $wav.Name, $inKB, $outKB)
    Remove-Item $wav.FullName -Force
}

Write-Host ("完成：{0} 个文件，{1:N0} KB -> {2:N0} KB（节省 {3:N2} MB）" -f `
    $wavs.Count, ($inTotal / 1KB), ($outTotal / 1KB), (($inTotal - $outTotal) / 1MB))
