#requires -Version 7.0
param(
    [string]$Toolchain = (Join-Path $PSScriptRoot '..\..\android-toolchain'),
    [string]$SigningProperties = (Join-Path $PSScriptRoot '..\..\aihot-private\signing.properties'),
    [int]$VersionCode = 4
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolchainRoot = [IO.Path]::GetFullPath($Toolchain)
$configurationPath = Join-Path $toolchainRoot 'toolchain.json'
if (-not (Test-Path -LiteralPath $configurationPath)) {
    throw 'Missing android-toolchain/toolchain.json. See BUILDING.md to configure the local toolchain.'
}
$config = Get-Content -LiteralPath $configurationPath -Raw | ConvertFrom-Json
$env:JAVA_HOME = $config.javaHome
$env:ANDROID_HOME = $config.androidHome
$env:ANDROID_USER_HOME = Join-Path $toolchainRoot 'android-user'
$env:GRADLE_USER_HOME = Join-Path $toolchainRoot 'gradle-cache'
$temporaryDirectory = Join-Path $toolchainRoot 'tmp'
$javaUserDirectory = Join-Path $toolchainRoot 'java-user'
New-Item -ItemType Directory -Path $temporaryDirectory,$javaUserDirectory -Force | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $temporaryDirectory + '" -Duser.home="' + $javaUserDirectory + '"'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
if (-not (Test-Path -LiteralPath $SigningProperties)) {
    throw 'Missing release signing configuration. Keep signing keys outside the source tree.'
}
$env:AIHOT_SIGNING_PROPERTIES = [IO.Path]::GetFullPath($SigningProperties)
Push-Location $projectRoot
try {
    & .\gradlew.bat --no-daemon testDebugUnitTest lintRelease assembleRelease "-PappVersionCode=$VersionCode"
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    $apkPath = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
    $apksigner = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat'
    & $apksigner verify --verbose --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
}
finally {
    Pop-Location
    Remove-Item Env:AIHOT_SIGNING_PROPERTIES -ErrorAction SilentlyContinue
}
