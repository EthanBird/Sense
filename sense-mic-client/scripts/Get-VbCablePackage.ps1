[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $env:TEMP 'sense-vbcable-pack45'),
    [string]$ArchivePath,
    [string]$SignToolPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$url = 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip'
$expectedSha256 = 'b950e39f01af1d04ea623c8f6d8eb9b6ea5c477c637295fabf20631c85116bfb'
$root = [IO.Path]::GetFullPath($OutputDirectory)
$download = if ($ArchivePath) {
    (Resolve-Path -LiteralPath $ArchivePath -ErrorAction Stop).Path
} else {
    Join-Path ([IO.Path]::GetTempPath()) 'VBCABLE_Driver_Pack45.zip'
}

if (-not $ArchivePath) {
    Invoke-WebRequest -Uri $url -OutFile $download
}
$actualSha256 = (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $expectedSha256) {
    throw "VB-CABLE archive SHA-256 mismatch: $actualSha256"
}

if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
New-Item -ItemType Directory -Path $root -Force | Out-Null
Expand-Archive -LiteralPath $download -DestinationPath $root

$validator = Join-Path $PSScriptRoot 'Assert-VbCablePackage.ps1'
$result = & $validator -PackagePath $root -SignToolPath $SignToolPath -PassThru
$result | ConvertTo-Json -Depth 7 |
    Set-Content -LiteralPath (Join-Path (Split-Path $root -Parent) 'VB-CABLE-MANIFEST.json') -Encoding utf8

[pscustomobject]@{
    PackagePath = $root
    SourceUrl = $url
    SourceArchiveSha256 = $actualSha256
}
