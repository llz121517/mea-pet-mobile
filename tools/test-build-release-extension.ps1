#requires -Version 5.1
# Regression check: both release wrappers must publish uppercase .APK files in dist/.
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
    Set-Content (Join-Path $fixture 'gradlew') "#!/usr/bin/env bash`nexit 0"
    Set-Content (Join-Path $fixture 'gradlew.bat') "@echo off`r`nexit /b 0"

    foreach ($wrapper in @('sh', 'ps1')) {
        $dist = Join-Path $fixture 'dist'
        if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
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
        $expected = Join-Path $dist 'MeaPet-v1.7.2-v8a.APK'
        $actual = @(Get-ChildItem -File $dist | Select-Object -ExpandProperty Name)
        if ($actual.Count -ne 1 -or $actual[0] -cne 'MeaPet-v1.7.2-v8a.APK') {
            throw "$wrapper wrapper published unexpected filenames: $($actual -join ', ')"
        }
        if ((Get-Content $expected -Raw).Trim() -ne 'signed fixture') {
            throw "$wrapper wrapper did not copy the signed APK"
        }
        Write-Host "PASS: $wrapper wrapper publishes $($actual[0])"
    }
} finally {
    if (Test-Path $fixture) { Remove-Item -Recurse -Force $fixture }
}
