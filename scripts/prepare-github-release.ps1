param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $projectRoot "app\build.gradle.kts"
$gradleText = Get-Content -LiteralPath $gradleFile -Raw

$applicationIdMatch = [regex]::Match($gradleText, 'applicationId\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $applicationIdMatch.Success -or -not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "Could not read applicationId/versionCode/versionName from app/build.gradle.kts"
}

$expectedCertificate = $env:DAILYPLAN_RELEASE_CERT_SHA256
if ([string]::IsNullOrWhiteSpace($expectedCertificate) -or $expectedCertificate -notmatch '^[0-9a-fA-F]{64}$') {
    throw "Set DAILYPLAN_RELEASE_CERT_SHA256 to the confirmed release certificate fingerprint first. No signing secret is needed by this script."
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
if ([IO.Path]::GetExtension($resolvedApk) -ne ".apk") {
    throw "Input must be an APK file"
}

$sdkRoot = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { throw "ANDROID_HOME or ANDROID_SDK_ROOT is required to locate apksigner" }
$buildTools = Join-Path $sdkRoot "build-tools"
$apkSigner = Get-ChildItem -LiteralPath $buildTools -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $apkSigner) { throw "Could not find apksigner under $buildTools" }

$certificateOutput = & $apkSigner verify --print-certs $resolvedApk 2>&1
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $certificateOutput" }
$certificateText = $certificateOutput -join "`n"
if ($certificateText -match '(?i)CN=Android Debug') { throw "Debug-signed APKs cannot be prepared for GitHub releases" }
$actualCertificateMatch = [regex]::Match($certificateText, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
if (-not $actualCertificateMatch.Success -or $actualCertificateMatch.Groups[1].Value -ine $expectedCertificate) {
    throw "APK signer does not match DAILYPLAN_RELEASE_CERT_SHA256"
}

$aapt = Join-Path (Split-Path -Parent $apkSigner) "aapt.exe"
if (-not (Test-Path -LiteralPath $aapt)) { throw "Could not find aapt beside apksigner" }
$badging = & $aapt dump badging $resolvedApk 2>&1
if ($LASTEXITCODE -ne 0) { throw "Could not read APK package metadata: $badging" }
$packageMatch = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'")
if (-not $packageMatch.Success -or
    $packageMatch.Groups[1].Value -cne $applicationIdMatch.Groups[1].Value -or
    $packageMatch.Groups[2].Value -cne $versionCodeMatch.Groups[1].Value -or
    $packageMatch.Groups[3].Value -cne $versionNameMatch.Groups[1].Value) {
    throw "APK package/version does not match app/build.gradle.kts"
}

$versionName = $versionNameMatch.Groups[1].Value
if ($versionName -notmatch '^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$') { throw "versionName contains unsupported filename characters" }
$assetDirectory = Join-Path $projectRoot "release-assets"
New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null
$apkName = "DailyPlan-v$versionName.apk"
$outputApk = Join-Path $assetDirectory $apkName
Copy-Item -LiteralPath $resolvedApk -Destination $outputApk -Force
$sha256 = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()

$metadata = [ordered]@{
    applicationId = $applicationIdMatch.Groups[1].Value
    versionCode = [long]$versionCodeMatch.Groups[1].Value
    versionName = $versionName
    apkName = $apkName
    sha256 = $sha256
}
$metadataJson = $metadata | ConvertTo-Json
$metadataPath = Join-Path $assetDirectory "dailyplan-update.json"
[IO.File]::WriteAllText($metadataPath, $metadataJson, [Text.UTF8Encoding]::new($false))

Write-Output "Release assets prepared in $assetDirectory"
Write-Output "APK: $apkName"
Write-Output "SHA-256: $sha256"
Write-Output "Version: $($metadata.versionName) (versionCode $($metadata.versionCode))"
