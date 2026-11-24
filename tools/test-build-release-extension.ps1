#requires -Version 5.1
# Regression check:
#   1. both release wrappers must publish uppercase .APK files in dist/ with the unchanged
#      MeaPet-v<基础版本>-<ABI>.APK naming;
#   2. both wrappers must pass the current commit's 9-char short hash to gradle via
#      -PversionSuffix（app/build.gradle.kts 负责把它拼到 versionName 末尾）。
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("meapet-release-test-" + [guid]::NewGuid().ToString('N'))
$bash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw 'Git Bash is required to test the shell release wrapper' }

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'tools'), (Join-Path $fixture 'app\build\outputs\apk\release') | Out-Null
    Copy-Item (Join-Path $repo 'tools\build-release.sh') (Join-Path $fixture 'tools\build-release.sh')
    Copy-Item (Join-Path $repo 'tools\build-release.ps1') (Join-Path $fixture 'tools\build-release.ps1')
    Set-Content (Join-Path $fixture 'app\build.gradle.kts') 'versionName = "1.7.2"'
    Set-Content (Join-Path $fixture 'keystore.properties') "storeFile=release.keystore`nstorePassword=test`nkeyAlias=test`nkeyPassword=test"
    Set-Content (Join-Path $fixture 'release.keystore') 'dummy'
    Set-Content (Join-Path $fixture 'app\build\outputs\apk\release\app-release.apk') 'signed fixture'
    # 假 gradlew：把收到的参数写进 gradle-args.txt，供断言 -PappAbi / -PversionSuffix
    Set-Content (Join-Path $fixture 'gradlew') "#!/usr/bin/env bash`nprintf '%s\n' `"`$@`" > gradle-args.txt`nexit 0`n"
    Set-Content (Join-Path $fixture 'gradlew.bat') "@echo off`r`n(echo %*)>gradle-args.txt`r`nexit /b 0`r`n"

    # 造一个带提交的 git 仓库：构建脚本用 git rev-parse --short=9 读取提交短哈希。
    # 注意：`& git ... | Out-Null` 会让 $LASTEXITCODE 保留管道最后一环的状态，
    # 且 PowerShell 7 下 git 的 stderr 输出（如 "Initialized empty Git repository"）
    # 会被当成 NativeCommandError。这里统一用 `2>&1 | Out-Null` 吞掉，并以
    # 用户配置 + 显式 --allow-empty 保证在任何环境都能提交成功。
    # 注意：一律用 `git -C $fixture` 并显式 `--local`，不要依赖 Push-Location。
    # 曾因裸 `git config` 在 fixture 未就绪时落到主仓库，把 .git/config 的
    # user.name/email 永久改成假的测试身份，导致之后所有提交作者错位。
    & git init -q $fixture 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'git init failed' }
    & git -C $fixture config --local user.email 'release-test@example.com' 2>&1 | Out-Null
    & git -C $fixture config --local user.name 'release test' 2>&1 | Out-Null
    & git -C $fixture config --local commit.gpgsign false 2>&1 | Out-Null
    & git -C $fixture add -A 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
    & git -C $fixture commit --allow-empty -qm 'fixture' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
    $expectedSuffix = (& git -C $fixture rev-parse --short=9 HEAD | Out-String).Trim()
    if ($expectedSuffix -notmatch '^[0-9a-fA-F]{7,40}$') {
        throw "unexpected short hash from git: $expectedSuffix"
    }

    $argsFile = Join-Path $fixture 'gradle-args.txt'
    foreach ($wrapper in @('sh', 'ps1')) {
        $dist = Join-Path $fixture 'dist'
        if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
        if (Test-Path $argsFile) { Remove-Item -Force $argsFile }
        if ($wrapper -eq 'sh') {
            $env:RELEASE_FIXTURE = $fixture
            try {
                $env:ABIS = 'v8a'
                & $bash -c 'cd "$(cygpath -u "$RELEASE_FIXTURE")" && bash tools/build-release.sh'
                if ($LASTEXITCODE -ne 0) { throw "Shell wrapper failed: $LASTEXITCODE" }
            } finally {
                Remove-Item Env:RELEASE_FIXTURE -ErrorAction SilentlyContinue
                Remove-Item Env:ABIS -ErrorAction SilentlyContinue
            }
        } else {
            & (Join-Path $fixture 'tools\build-release.ps1') -Abis v8a
            if ($LASTEXITCODE -ne 0) { throw "PowerShell wrapper failed: $LASTEXITCODE" }
        }

        # 产物命名保持不变：基础版本号来自 build.gradle.kts，不拼短哈希后缀
        $expected = Join-Path $dist 'MeaPet-v1.7.2-v8a.APK'
        $actual = @(Get-ChildItem -File $dist | Select-Object -ExpandProperty Name)
        if ($actual.Count -ne 1 -or $actual[0] -cne 'MeaPet-v1.7.2-v8a.APK') {
            throw "$wrapper wrapper published unexpected filenames: $($actual -join ', ')"
        }
        if ((Get-Content $expected -Raw).Trim() -ne 'signed fixture') {
            throw "$wrapper wrapper did not copy the signed APK"
        }

        # 自动构建必须把当前提交短哈希传给 gradle，由 build.gradle.kts 拼到 versionName
        if (-not (Test-Path $argsFile)) { throw "$wrapper wrapper did not invoke gradlew" }
        $gradleArgs = (Get-Content $argsFile -Raw)
        if ($gradleArgs -notmatch [regex]::Escape("-PappAbi=v8a")) {
            throw "$wrapper wrapper did not pass -PappAbi: $gradleArgs"
        }
        if ($gradleArgs -notmatch [regex]::Escape("-PversionSuffix=$expectedSuffix")) {
            throw "$wrapper wrapper did not pass -PversionSuffix=$expectedSuffix : $gradleArgs"
        }
        Write-Host "PASS: $wrapper wrapper publishes $($actual[0]) and passes -PversionSuffix=$expectedSuffix"
    }
} finally {
    if (Test-Path $fixture) { Remove-Item -Recurse -Force $fixture -ErrorAction SilentlyContinue }
}
