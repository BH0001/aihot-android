#requires -Version 7.0
param([string]$Serial='emulator-5556')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tc=[IO.Path]::GetFullPath((Join-Path $root '..\android-toolchain'))
$env:TEMP=Join-Path $tc 'tmp'
$env:TMP=$env:TEMP
$adb=Join-Path $tc 'sdk\platform-tools\adb.exe'
$metadata=Get-Content -LiteralPath (Join-Path $root 'app\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
$releaseOutput=@($metadata.elements | Where-Object {$_.outputFile -eq 'app-release.apk'})
if ($metadata.applicationId -ne 'dev.personal.aihotreader' -or $metadata.variantName -ne 'release' -or $releaseOutput.Count -ne 1) {
    throw 'Build the expected release APK before running display mode tests.'
}
$versionName=[string]$releaseOutput[0].versionName
if ($versionName -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') { throw 'Unsupported release versionName for validation filenames.' }
$recordPrefix='v'+($versionName -replace '[^0-9A-Za-z]','')

function Invoke-CheckedAdb {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    $output=@(& $adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($output -join "`n")" }
    $output
}

function Test-DisplayState {
    param([string]$WindowState,[string[]]$Overlays,[string]$NavigationMode,[int]$Rotation,[string]$Navigation,[string]$Cutout)
    $size=[regex]::Match($WindowState,'(?m)^\s+init=\d+x\d+\s[^\r\n]*?\bcur=(\d+)x(\d+)\b')
    $actualRotation=[regex]::Match($WindowState,'(?m)^\s+mRotation=(\d)\s')
    $cutoutInsets=[regex]::Match($WindowState,'mDisplayCutout=DisplayCutout\{insets=Rect\((\d+), (\d+) - (\d+), (\d+)\)')
    if (-not $size.Success -or -not $actualRotation.Success -or -not $cutoutInsets.Success) { return $false }
    $width=[int]$size.Groups[1].Value; $height=[int]$size.Groups[2].Value
    $correctShape=if ($Rotation -eq 1) {$width -gt $height} else {$height -gt $width}
    $hasCutout=(@(1..4 | ForEach-Object {[int]$cutoutInsets.Groups[$_].Value}) | Measure-Object -Sum).Sum -gt 0
    $enabled=@($Overlays | Where-Object {$_ -match '^\s*\[x\]\s+'} | ForEach-Object {($_ -replace '^\s*\[x\]\s+','').Trim()})
    $expectedNavigationMode=if ($Navigation -eq 'gestural') {'2'} else {'0'}
    return $correctShape -and $hasCutout -and ([int]$actualRotation.Groups[1].Value -eq $Rotation) -and
        ($NavigationMode.Trim() -eq $expectedNavigationMode) -and
        ($enabled -contains "com.android.internal.systemui.navbar.$Navigation") -and
        ($enabled -contains "com.android.internal.display.cutout.emulation.$Cutout")
}

function Assert-DisplayMode {
    param([int]$Rotation,[string]$Navigation,[string]$Cutout,[string]$Label)
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $overlays=@(Invoke-CheckedAdb shell cmd overlay list)
        $windowState=(Invoke-CheckedAdb shell dumpsys window displays) -join "`n"
        $navigationMode=(Invoke-CheckedAdb shell cmd overlay lookup android android:integer/config_navBarInteractionMode) -join "`n"
        $modeMatches=Test-DisplayState -WindowState $windowState -Overlays $overlays -NavigationMode $navigationMode -Rotation $Rotation -Navigation $Navigation -Cutout $Cutout
        if ($modeMatches) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $overlays | Select-String 'cutout|navbar' | Out-File (Join-Path $root "validation\$recordPrefix-$Label-modes.txt")
    $windowState | Out-File (Join-Path $root "validation\$recordPrefix-$Label-window.txt")
    if (-not $modeMatches) { throw "Display mode did not take effect: rotation=$Rotation, navigation=$Navigation, cutout=$Cutout. See recorded window/overlay state." }
}

function Assert-PngOrientation {
    param([string]$Path,[bool]$Landscape)
    $bytes=[IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 24 -or [Convert]::ToHexString($bytes[0..7]) -ne '89504E470D0A1A0A') { throw "Not a PNG screenshot: $Path" }
    $width=[uint32]$bytes[16]*16777216+[uint32]$bytes[17]*65536+[uint32]$bytes[18]*256+$bytes[19]
    $height=[uint32]$bytes[20]*16777216+[uint32]$bytes[21]*65536+[uint32]$bytes[22]*256+$bytes[23]
    if (($Landscape -and $width -le $height) -or (-not $Landscape -and $height -le $width)) {
        throw "Screenshot orientation is incorrect: $Path ($width x $height)."
    }
}

if (((Invoke-CheckedAdb shell getprop ro.kernel.qemu) -join '').Trim() -ne '1') { throw 'Display mode tests are emulator-only.' }
New-Item -ItemType Directory -Path (Join-Path $root 'validation') -Force | Out-Null
$original=@{}
foreach($key in @('font_scale','accelerometer_rotation','user_rotation')) {
    $original[$key]=((Invoke-CheckedAdb shell settings get system $key) -join '').Trim()
}
$night=((Invoke-CheckedAdb shell cmd uimode night) -join '') -replace '^Night mode:\s*',''
$originalRotation=((Invoke-CheckedAdb shell wm user-rotation) -join '').Trim()
$originalFixed=((Invoke-CheckedAdb shell wm fixed-to-user-rotation) -join '').Trim()
$overlayOutput=Invoke-CheckedAdb shell cmd overlay list
$originalOverlays=@($overlayOutput | Where-Object {$_ -match '^\[x\].*(navbar|display.cutout)'} | ForEach-Object {($_ -replace '^\[x\]\s*','').Trim()})
Push-Location $root
try {
    Invoke-CheckedAdb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
    Invoke-CheckedAdb shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.hole
    Invoke-CheckedAdb shell settings put system font_scale 1.3
    Invoke-CheckedAdb shell cmd uimode night yes
    Invoke-CheckedAdb shell settings put system accelerometer_rotation 0
    Invoke-CheckedAdb shell settings put system user_rotation 0
    Invoke-CheckedAdb shell wm fixed-to-user-rotation enabled
    Invoke-CheckedAdb shell wm user-rotation lock 0
    Assert-DisplayMode -Rotation 0 -Navigation threebutton -Cutout hole -Label portrait
    .\scripts\test-device.ps1 -Serial $Serial -Classes 'dev.personal.aihotreader.EdgeToEdgeTest,dev.personal.aihotreader.LiveAppearanceTest' | Tee-Object "validation\$recordPrefix-threebutton-hole-large-dark.txt"
    $portraitDirectory="validation\$recordPrefix-threebutton-screens"
    New-Item -ItemType Directory -Path $portraitDirectory -Force | Out-Null
    foreach($name in @('home-light','home-dark','article-light','article-dark')) {
        $screenshot=Join-Path $portraitDirectory "$name.png"
        Invoke-CheckedAdb pull "/sdcard/Android/data/dev.personal.aihotreader/files/validation/v101/$name.png" $screenshot
        Assert-PngOrientation -Path $screenshot -Landscape $false
    }
    Assert-DisplayMode -Rotation 0 -Navigation threebutton -Cutout hole -Label portrait

    Invoke-CheckedAdb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
    Invoke-CheckedAdb shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.corner
    Invoke-CheckedAdb shell settings put system font_scale 1.0
    Invoke-CheckedAdb shell cmd uimode night no
    Invoke-CheckedAdb shell settings put system user_rotation 1
    Invoke-CheckedAdb shell wm user-rotation lock 1
    Assert-DisplayMode -Rotation 1 -Navigation gestural -Cutout corner -Label landscape
    .\scripts\test-device.ps1 -Serial $Serial -Classes 'dev.personal.aihotreader.EdgeToEdgeTest#nativeSafeAreaIsAppliedOnce,dev.personal.aihotreader.LiveSiteTest' | Tee-Object "validation\$recordPrefix-landscape-corner.txt"
    foreach($name in @('home','article')) {
        $deviceName=if($name -eq 'home') {'homepage'} else {$name}
        $screenshot="validation\$recordPrefix-landscape-$name.png"
        Invoke-CheckedAdb pull "/sdcard/Android/data/dev.personal.aihotreader/files/validation/$deviceName.png" $screenshot
        Assert-PngOrientation -Path $screenshot -Landscape $true
    }
    Assert-DisplayMode -Rotation 1 -Navigation gestural -Cutout corner -Label landscape
} finally {
    $restoreErrors=[Collections.Generic.List[string]]::new()
    function Restore-DeviceSetting {
        param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
        try { Invoke-CheckedAdb @Arguments }
        catch { $restoreErrors.Add($_.Exception.Message) }
    }
    foreach($id in @('com.android.internal.display.cutout.emulation.hole','com.android.internal.display.cutout.emulation.corner')) {
        Restore-DeviceSetting shell cmd overlay disable $id
    }
    foreach($id in $originalOverlays) { Restore-DeviceSetting shell cmd overlay enable-exclusive --category $id }
    foreach($key in $original.Keys) {
        if($original[$key] -eq 'null') { Restore-DeviceSetting shell settings delete system $key }
        else { Restore-DeviceSetting shell settings put system $key $original[$key] }
    }
    Restore-DeviceSetting shell cmd uimode night $night.Trim()
    if ($originalRotation -match '^lock\s+(\d)') {
        Restore-DeviceSetting shell wm user-rotation lock $Matches[1]
    } else { Restore-DeviceSetting shell wm user-rotation free }
    Restore-DeviceSetting shell wm fixed-to-user-rotation $originalFixed
    Pop-Location
    if ($restoreErrors.Count) { throw "Some original display settings could not be restored:`n$($restoreErrors -join "`n")" }
}
