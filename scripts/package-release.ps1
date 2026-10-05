#requires -Version 7.0
param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$apk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build the signed release APK first.' }
$metadataPath = Join-Path (Split-Path -Parent $apk) 'output-metadata.json'
if (-not (Test-Path -LiteralPath $metadataPath)) { throw 'Missing release APK metadata. Rebuild the signed release APK.' }
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$outputs = @($metadata.elements | Where-Object { $_.outputFile -eq [IO.Path]::GetFileName($apk) })
if ($metadata.applicationId -ne 'dev.personal.aihotreader' -or $metadata.variantName -ne 'release' -or $outputs.Count -ne 1) {
    throw 'Release APK metadata does not match the expected application and output.'
}
$versionName = [string]$outputs[0].versionName
if ($versionName -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') { throw 'Release versionName is missing or unsafe for artifact filenames.' }
$artifactName = "aihot-reader-$versionName"
$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $projectRoot "dist\$versionName"
} else { [IO.Path]::GetFullPath($OutputDirectory) }
$validationPrefix = 'v' + ($versionName -replace '\.','')
foreach ($required in @('VALIDATION.md','INSTALLATION.md','gradlew','gradlew.bat','gradle\wrapper\gradle-wrapper.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $required))) { throw "Missing deliverable: $required" }
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$destinationApk = Join-Path $outputRoot "$artifactName.apk"
Copy-Item -LiteralPath $apk -Destination $destinationApk -Force
foreach ($document in @('INSTALLATION.md','VALIDATION.md')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $document) -Destination (Join-Path $outputRoot $document) -Force
}
$rootFiles = @('.gitignore','.gitattributes','LICENSE','THIRD_PARTY_NOTICES.md','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','README.md','BUILDING.md','INSTALLATION.md','VALIDATION.md','app\build.gradle.kts')
$sourceFiles = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $projectRoot $_) })
foreach ($directory in @('app\src','gradle\wrapper','scripts','design','docs','licenses')) {
    $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -Recurse -File
}
$zipPath = Join-Path $outputRoot "$artifactName-source.zip"
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::Create)
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $sourceFiles) {
        $relative = [IO.Path]::GetRelativePath($projectRoot, $file.FullName).Replace('\','/')
        if ($relative -match '(?i)(signing\.properties|\.p12$|\.jks$|\.keystore$|(^|/)local\.properties$)') {
            throw "Private configuration must not be packaged: $relative"
        }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, 'aihot-android/' + $relative) | Out-Null
    }
}
finally { $archive.Dispose(); $stream.Dispose() }
$logoZip = Join-Path $outputRoot "$artifactName-logo.zip"
Compress-Archive -Path (Join-Path $projectRoot 'design\*') -DestinationPath $logoZip -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'design\logo-preview.png') -Destination (Join-Path $outputRoot 'logo-preview.png') -Force
foreach ($name in @('home-light','home-dark','article-light','article-dark')) {
    $screen = Join-Path $projectRoot "validation\$validationPrefix-portrait-screens\$name.png"
    if (Test-Path -LiteralPath $screen) {
        Copy-Item -LiteralPath $screen -Destination (Join-Path $outputRoot "preview-$name.png") -Force
    }
}
foreach ($name in @('home','article')) {
    $screen = Join-Path $projectRoot "validation\$validationPrefix-landscape-$name.png"
    if (Test-Path -LiteralPath $screen) {
        Copy-Item -LiteralPath $screen -Destination (Join-Path $outputRoot "preview-landscape-$name.png") -Force
    }
}
$checksums = @($destinationApk,$zipPath,$logoZip) | ForEach-Object {
    (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($_)
}
[IO.File]::WriteAllText((Join-Path $outputRoot 'SHA256SUMS.txt'), ($checksums -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
Get-ChildItem -LiteralPath $outputRoot -File | Select-Object Name,Length
