[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PackagePath,

    [string]$SignToolPath,
    [switch]$PassThru
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ExpectedArchiveSha256 = 'b950e39f01af1d04ea623c8f6d8eb9b6ea5c477c637295fabf20631c85116bfb'
$ExpectedFiles = @(
    'pin_in.ico',
    'pin_out.ico',
    'readme.txt',
    'vbaudio_cable_2003.cat',
    'vbaudio_cable_2003.sys',
    'vbaudio_cable_vista.cat',
    'vbaudio_cable_vista.sys',
    'vbaudio_cable_win7.cat',
    'vbaudio_cable_win7.sys',
    'vbaudio_cable_xp.cat',
    'vbaudio_cable_xp.sys',
    'vbaudio_cable64_2003.cat',
    'vbaudio_cable64_2003.sys',
    'vbaudio_cable64_vista.cat',
    'vbaudio_cable64_vista.sys',
    'vbaudio_cable64_win10.cat',
    'vbaudio_cable64_win10.sys',
    'vbaudio_cable64_win7.cat',
    'vbaudio_cable64_win7.sys',
    'vbaudio_cable64arm_win10.sys',
    'VBCABLE_ControlPanel.exe',
    'VBCABLE_Setup.exe',
    'VBCABLE_Setup_x64.exe',
    'vbMmeCable_2003.inf',
    'vbMmeCable_vista.inf',
    'vbMmeCable_win7.inf',
    'vbMmeCable_xp.inf',
    'vbMmeCable64_2003.inf',
    'vbMmeCable64_vista.inf',
    'vbMmeCable64_win10.inf',
    'vbMmeCable64_win7.inf'
)

function Resolve-SignTool([string]$RequestedPath) {
    if ($RequestedPath) {
        $resolved = [IO.Path]::GetFullPath($RequestedPath)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "SignTool was not found at $resolved"
        }
        return $resolved
    }
    $command = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $kitsRoot = Join-Path ([Environment]::GetFolderPath('ProgramFilesX86')) 'Windows Kits\10\bin'
    $candidate = Get-ChildItem -LiteralPath $kitsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -match '^\d+\.\d+\.\d+\.\d+$' |
        Sort-Object { [Version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if ($candidate) { return $candidate }
    throw 'SignTool is required to validate the VB-CABLE catalog.'
}

$root = (Resolve-Path -LiteralPath $PackagePath -ErrorAction Stop).Path
$actualFiles = @(Get-ChildItem -LiteralPath $root -File | Select-Object -ExpandProperty Name | Sort-Object)
$expectedSorted = @($ExpectedFiles | Sort-Object)
if (($actualFiles.Count -ne $expectedSorted.Count) -or
    (Compare-Object -ReferenceObject $expectedSorted -DifferenceObject $actualFiles)) {
    throw "VB-CABLE package file set mismatch below $root"
}

$setup = Join-Path $root 'VBCABLE_Setup_x64.exe'
$catalog = Join-Path $root 'vbaudio_cable64_win10.cat'
$inf = Join-Path $root 'vbMmeCable64_win10.inf'
$sys = Join-Path $root 'vbaudio_cable64_win10.sys'

$setupSignature = Get-AuthenticodeSignature -LiteralPath $setup
if ($setupSignature.Status -ne [System.Management.Automation.SignatureStatus]::Valid -or
    $setupSignature.SignerCertificate.Subject -notmatch 'BUREL VINCENT') {
    throw "Unexpected VB-CABLE setup signature: $($setupSignature.Status) / $($setupSignature.SignerCertificate.Subject)"
}
$catalogSignature = Get-AuthenticodeSignature -LiteralPath $catalog
if ($catalogSignature.Status -ne [System.Management.Automation.SignatureStatus]::Valid -or
    $catalogSignature.SignerCertificate.Subject -notmatch 'Microsoft Windows Hardware Compatibility Publisher') {
    throw "Unexpected VB-CABLE catalog signature: $($catalogSignature.Status) / $($catalogSignature.SignerCertificate.Subject)"
}

$signTool = Resolve-SignTool $SignToolPath
foreach ($member in @($inf, $sys)) {
    & $signTool verify /kp /v /c $catalog $member | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "VB-CABLE kernel-policy catalog verification failed for $member (exit $LASTEXITCODE)"
    }
}

$result = [ordered]@{
    schema = 1
    product = 'VB-CABLE'
    sourceUrl = 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip'
    sourceArchiveSha256 = $ExpectedArchiveSha256
    catalogSigner = $catalogSignature.SignerCertificate.Subject
    setupSigner = $setupSignature.SignerCertificate.Subject
    files = [ordered]@{}
}
foreach ($name in $ExpectedFiles) {
    $path = Join-Path $root $name
    $result.files[$name] = [ordered]@{
        bytes = (Get-Item -LiteralPath $path).Length
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

Write-Host "Validated production VB-CABLE package: $root"
if ($PassThru) { [PSCustomObject]$result }
