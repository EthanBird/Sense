[CmdletBinding()]
param(
    [ValidateSet('Test', 'Unsigned', 'Production')]
    [string]$DriverSignMode = 'Test',
    [string]$DriverPackage,
    [string]$CertificatePath,
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot 'dist' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $PSScriptRoot 'Cargo.toml'
$driverBuild = Join-Path $PSScriptRoot 'driver\windows\build-driver.ps1'
$builtDriver = Join-Path $PSScriptRoot 'driver\windows\x64'
$bundle = Join-Path $OutputRoot 'SenseMicClient-windows-x64'
$archive = Join-Path $OutputRoot 'SenseMicClient-windows-x64.zip'

& cargo build --locked --release --manifest-path $manifest
if ($LASTEXITCODE -ne 0) { throw "cargo build failed with exit code $LASTEXITCODE" }

if ($DriverPackage) {
    $driverSource = [IO.Path]::GetFullPath($DriverPackage)
} else {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $driverBuild, '-SignMode', $DriverSignMode)
    if ($CertificatePath) { $arguments += @('-CertificatePath', $CertificatePath) }
    & powershell.exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "driver build failed with exit code $LASTEXITCODE" }
    $driverSource = $builtDriver
}

foreach ($name in @('SenseMicVAD.inf', 'SenseMicVAD.sys', 'sensemicvad.cat')) {
    if (-not (Test-Path -LiteralPath (Join-Path $driverSource $name) -PathType Leaf)) {
        throw "Driver package is missing $name below $driverSource"
    }
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputRoot)
$resolvedBundle = [IO.Path]::GetFullPath($bundle)
if (-not $resolvedBundle.StartsWith($resolvedOutput.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Bundle path is outside $resolvedOutput"
}
if (Test-Path -LiteralPath $resolvedBundle) {
    Remove-Item -LiteralPath $resolvedBundle -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $resolvedBundle 'driver\windows\x64') -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'target\release\sense-mic.exe') -Destination $resolvedBundle
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination $resolvedBundle
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $resolvedBundle
Copy-Item -LiteralPath (Join-Path $repoRoot 'NOTICE') -Destination $resolvedBundle
Copy-Item -Path (Join-Path $driverSource '*') -Destination (Join-Path $resolvedBundle 'driver\windows\x64') -Recurse -Force

if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -Path (Join-Path $resolvedBundle '*') -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$archive.sha256" -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ascii
Write-Host "Windows bundle: $archive"
Write-Host "SHA-256: $hash"
