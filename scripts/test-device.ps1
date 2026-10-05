#requires -Version 7.0
param(
    [string]$Serial = 'emulator-5556',
    [string]$Classes = 'dev.personal.aihotreader.EdgeToEdgeTest',
    [string]$BookmarkPhase = '',
    [switch]$Build,
    [switch]$InstallTarget
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tc = [IO.Path]::GetFullPath((Join-Path $root '..\android-toolchain'))
$config = Get-Content (Join-Path $tc 'toolchain.json') -Raw | ConvertFrom-Json
$env:JAVA_HOME = $config.javaHome
$env:ANDROID_HOME = $config.androidHome
$env:ANDROID_USER_HOME = Join-Path $tc 'android-user'
$env:GRADLE_USER_HOME = Join-Path $tc 'gradle-cache'
$env:TEMP = Join-Path $tc 'tmp'
$env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $env:TEMP + '" -Duser.home="' + (Join-Path $tc 'java-user') + '"'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
Push-Location $root
try {
    if ($Build) {
        $env:AIHOT_SIGNING_PROPERTIES = [IO.Path]::GetFullPath((Join-Path $root '..\aihot-private\signing.properties'))
        $buildTasks = @('assembleReleaseAndroidTest')
        if ($InstallTarget) { $buildTasks = @('assembleRelease') + $buildTasks }
        & .\gradlew.bat --no-daemon '-PdeviceTestBuildType=release' @buildTasks
        if ($LASTEXITCODE -ne 0) { throw 'Instrumentation build failed.' }
        & $adb -s $Serial install -r 'app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
        if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }
    }
    if ($InstallTarget) {
        & $adb -s $Serial install -r 'app\build\outputs\apk\release\app-release.apk'
        if ($LASTEXITCODE -ne 0) { throw 'Release APK install failed.' }
    }
    $arguments = @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$Classes)
    if ($BookmarkPhase) { $arguments += @('-e','bookmarkPhase',$BookmarkPhase) }
    $arguments += 'dev.personal.aihotreader.test/androidx.test.runner.AndroidJUnitRunner'
    & $adb @arguments | Tee-Object -Variable result
    if ($LASTEXITCODE -ne 0 -or (($result -join "`n") -notmatch 'OK \([1-9]\d* tests?\)')) {
        throw 'Device instrumentation did not report a passing result. Inspect the captured output.'
    }
} finally {
    Pop-Location
    Remove-Item Env:AIHOT_SIGNING_PROPERTIES -ErrorAction SilentlyContinue
}
