[CmdletBinding()]
param(
    [ValidateSet('ClientOnly', 'DevelopmentTest', 'MicrosoftSigned')]
    [string]$PackageFlavor = 'ClientOnly',
    [string]$DriverPackage,
    [ValidateSet('Either', 'Attestation', 'WHQL')]
    [string]$MicrosoftSigningPolicy = 'WHQL',
    [string]$OutputRoot,
    [string]$ClientExecutable,
    [string]$BundleReadmePath,
    [string]$LicensePath,
    [string]$NoticePath,
    [string]$ClientOnlyNoticePath,
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$SourceCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot 'dist' }
$bundleName = switch ($PackageFlavor) {
    'ClientOnly' { 'SenseMicClient-windows-x64-client-only' }
    'DevelopmentTest' { 'SenseMicClient-windows-x64-development-test' }
    'MicrosoftSigned' { 'SenseMicClient-windows-x64' }
}
$bundle = Join-Path $OutputRoot $bundleName
$archive = Join-Path $OutputRoot "$bundleName.zip"
$resolvedOutput = [IO.Path]::GetFullPath($OutputRoot)
$resolvedBundle = [IO.Path]::GetFullPath($bundle)
$resolvedArchive = [IO.Path]::GetFullPath($archive)
$outputPrefix = $resolvedOutput.TrimEnd('\') + '\'
foreach ($target in @($resolvedBundle, $resolvedArchive, "$resolvedArchive.sha256")) {
    if (-not $target.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Bundle output path is outside $resolvedOutput`: $target"
    }
}
# Invalidate every previous product before inputs/build/signature validation. A caller that fails to
# inspect a non-zero exit can therefore never mistake an older ZIP or checksum for this invocation.
if (Test-Path -LiteralPath $resolvedBundle -PathType Container) {
    Remove-Item -LiteralPath $resolvedBundle -Recurse -Force
}
foreach ($oldArtifact in @($resolvedArchive, "$resolvedArchive.sha256")) {
    if (Test-Path -LiteralPath $oldArtifact -PathType Leaf) {
        Remove-Item -LiteralPath $oldArtifact -Force
    }
}
$repoRoot = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $PSScriptRoot 'Cargo.toml'
$driverBuild = Join-Path $PSScriptRoot 'driver\windows\build-driver.ps1'
$driverValidator = Join-Path $PSScriptRoot 'scripts\Assert-WindowsDriverPackage.ps1'
$builtDriver = Join-Path $PSScriptRoot 'driver\windows\x64'

if (($PackageFlavor -eq 'ClientOnly') -and $DriverPackage) {
    throw 'DriverPackage was supplied for a ClientOnly artifact. Select MicrosoftSigned or DevelopmentTest explicitly.'
}
if (($PackageFlavor -eq 'MicrosoftSigned') -and (-not $DriverPackage)) {
    throw 'MicrosoftSigned packaging requires a Partner Center returned driver directory via -DriverPackage.'
}
if (($PackageFlavor -eq 'MicrosoftSigned') -and ($MicrosoftSigningPolicy -ne 'WHQL')) {
    throw 'Public MicrosoftSigned packaging requires an HLK/WHQL catalog; attestation packages remain test-only.'
}

if ($ClientExecutable) {
    $clientSource = [IO.Path]::GetFullPath($ClientExecutable)
    if (-not (Test-Path -LiteralPath $clientSource -PathType Leaf)) {
        throw "Client executable was not found: $clientSource"
    }
} else {
    & cargo build --locked --release --manifest-path $manifest
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed with exit code $LASTEXITCODE" }
    $clientSource = Join-Path $PSScriptRoot 'target\release\sense-mic.exe'
}

$bundleReadmeSource = if ($BundleReadmePath) { [IO.Path]::GetFullPath($BundleReadmePath) } else { Join-Path $PSScriptRoot 'README.md' }
$licenseSource = if ($LicensePath) { [IO.Path]::GetFullPath($LicensePath) } else { Join-Path $repoRoot 'LICENSE' }
$noticeSource = if ($NoticePath) { [IO.Path]::GetFullPath($NoticePath) } else { Join-Path $repoRoot 'NOTICE' }
$clientOnlyNoticeSource = if ($ClientOnlyNoticePath) {
    [IO.Path]::GetFullPath($ClientOnlyNoticePath)
} else {
    Join-Path $PSScriptRoot 'CLIENT-ONLY-NOTICE.txt'
}
foreach ($contentPath in @($bundleReadmeSource, $licenseSource, $noticeSource)) {
    if (-not (Test-Path -LiteralPath $contentPath -PathType Leaf)) {
        throw "Windows bundle content file was not found: $contentPath"
    }
}
if (($PackageFlavor -eq 'ClientOnly') -and (-not (Test-Path -LiteralPath $clientOnlyNoticeSource -PathType Leaf))) {
    throw "Client-only notice file was not found: $clientOnlyNoticeSource"
}

$driverSource = $null
$driverValidationPath = $null
$driverSigningKind = $null
if ($PackageFlavor -eq 'DevelopmentTest') {
    if ($DriverPackage) {
        $driverSource = [IO.Path]::GetFullPath($DriverPackage)
    } else {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $driverBuild -SignMode Test
        if ($LASTEXITCODE -ne 0) { throw "driver build failed with exit code $LASTEXITCODE" }
        $driverSource = $builtDriver
    }
} elseif ($PackageFlavor -eq 'MicrosoftSigned') {
    $driverSource = [IO.Path]::GetFullPath($DriverPackage)
}

New-Item -ItemType Directory -Path $resolvedBundle -Force | Out-Null

Copy-Item -LiteralPath $clientSource -Destination (Join-Path $resolvedBundle 'sense-mic.exe')
Copy-Item -LiteralPath $bundleReadmeSource -Destination (Join-Path $resolvedBundle 'README.md')
Copy-Item -LiteralPath $licenseSource -Destination (Join-Path $resolvedBundle 'LICENSE')
Copy-Item -LiteralPath $noticeSource -Destination (Join-Path $resolvedBundle 'NOTICE')
if ($PackageFlavor -eq 'ClientOnly') {
    Copy-Item -LiteralPath $clientOnlyNoticeSource -Destination (Join-Path $resolvedBundle 'CLIENT-ONLY-NOTICE.txt')
}

if ($driverSource) {
    $driverDestination = Join-Path $resolvedBundle 'driver\windows\x64'
    New-Item -ItemType Directory -Path $driverDestination -Force | Out-Null
    $driverValidationPath = Join-Path $driverDestination 'driver-validation.json'
    $driverFiles = @('SenseMicVAD.inf', 'SenseMicVAD.sys', 'sensemicvad.cat')
    if ($PackageFlavor -eq 'DevelopmentTest') {
        $driverFiles += @('package.cer', 'build-manifest.json')
    }
    foreach ($name in $driverFiles) {
        $sourcePath = Join-Path $driverSource $name
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Driver package is missing $name below $driverSource"
        }
        Copy-Item -LiteralPath $sourcePath -Destination $driverDestination -Force
    }

    # Validate the private staged copy rather than the caller-owned source directory. The exact
    # INF/SYS/CAT bytes accepted by SignTool and the catalog membership checks are therefore the
    # bytes that enter the archive, even if a sync process rewrites the source concurrently.
    if ($PackageFlavor -eq 'DevelopmentTest') {
        & $driverValidator -PackagePath $driverDestination -ExpectedClass DevelopmentTest `
            -OutputManifestPath $driverValidationPath
    } else {
        & $driverValidator -PackagePath $driverDestination -ExpectedClass MicrosoftSigned `
            -MicrosoftSigningPolicy $MicrosoftSigningPolicy -OutputManifestPath $driverValidationPath
    }
    $driverValidation = Get-Content -LiteralPath $driverValidationPath -Raw -Encoding utf8 | ConvertFrom-Json
    $driverSigningKind = [string]$driverValidation.signingKind
}

$packageManifest = [ordered]@{
    schema = 1
    builtAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    artifactClass = switch ($PackageFlavor) {
        'ClientOnly' { 'windows-client-only' }
        'DevelopmentTest' { 'windows-client-with-development-test-driver' }
        'MicrosoftSigned' { 'windows-client-with-microsoft-signed-driver' }
    }
    releaseEligible = (
        ($PackageFlavor -eq 'ClientOnly') -or
        (($PackageFlavor -eq 'MicrosoftSigned') -and ($driverSigningKind -eq 'microsoft-whql-or-hlk'))
    )
    sourceCommit = if ($SourceCommit) { $SourceCommit.ToLowerInvariant() } else { $null }
    driverIncluded = [bool]$driverSource
    driverTrust = $driverSigningKind
    driverInstallBoundary = if ($driverSource) {
        'sense-mic invokes pnputil; Windows validates the catalog and administrator elevation is required'
    } else {
        'client-only artifact; install a separately supplied Microsoft-signed driver package before using the virtual endpoint'
    }
}
$packageManifest | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $resolvedBundle 'PACKAGE-MANIFEST.json') -Encoding utf8

if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -Path (Join-Path $resolvedBundle '*') -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$archive.sha256" -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ascii
Write-Host "Windows bundle: $archive"
Write-Host "Artifact class: $($packageManifest.artifactClass)"
Write-Host "SHA-256: $hash"
