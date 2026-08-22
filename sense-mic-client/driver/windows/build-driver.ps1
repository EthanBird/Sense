[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Test', 'Submission')]
    [string]$SignMode,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$wdkVersion = '10.0.26100.6584'
$kitVersion = '10.0.26100.0'
$wdkVsixVersion = '10.0.26100.10'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'x64' }
$solutionRoot = Join-Path $PSScriptRoot 'SenseMicVAD'
$solution = Join-Path $solutionRoot 'VirtualAudioDriver.sln'
$packagesRoot = Join-Path $solutionRoot 'packages'
$cacheRoot = Join-Path $solutionRoot '.cache'

$packages = @(
    @{
        Id = 'Microsoft.Windows.WDK.x64'
        Sha256 = 'C393D03DFB640B5C92F546B32F6770EF68CD3AAF691956E7D66D8E2C28A1B55E'
    },
    @{
        Id = 'Microsoft.Windows.SDK.CPP.x64'
        Sha256 = 'C29CE7A4641CB37EE32EBB8078CC65CFBABC7025076BCFBA869039204B1E960D'
    },
    @{
        Id = 'Microsoft.Windows.SDK.CPP'
        Sha256 = '5D31B38205BDD9AC761B4CB39FBBC6B7209B01C11194324AFC674D7D119483A0'
    }
)

$wdkVsix = @{
    Url = 'https://download.visualstudio.microsoft.com/download/pr/a347fec5-0e47-410f-adae-6fbb5e01232a/349e99f0385d8f4c6e7c9a4f223d449a4503a5d595091ad2a031fe80ed1517af/WDK.vsix'
    Sha256 = '349E99F0385D8F4C6E7C9A4F223D449A4503A5D595091AD2A031FE80ED1517AF'
}

function Assert-Hash([string]$Path, [string]$Expected) {
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $Expected) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual."
    }
}

function Reset-ChildDirectory([string]$Path, [string]$Parent) {
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedPath.StartsWith($resolvedParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to reset path outside $resolvedParent"
    }
    if (Test-Path -LiteralPath $resolvedPath) {
        $item = Get-Item -LiteralPath $resolvedPath -Force
        if ($item.Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
            Remove-Item -LiteralPath $resolvedPath -Force
        } else {
            Remove-Item -LiteralPath $resolvedPath -Recurse -Force
        }
    }
    New-Item -ItemType Directory -Path $resolvedPath -Force | Out-Null
}

function Ensure-Download([string]$Url, [string]$Path, [string]$Sha256) {
    if (Test-Path -LiteralPath $Path) {
        try {
            Assert-Hash $Path $Sha256
            return
        } catch {
            Remove-Item -LiteralPath $Path -Force
        }
    }
    Write-Host "Downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
    Assert-Hash $Path $Sha256
}

function Ensure-NuGetPackage([hashtable]$Package) {
    $id = [string]$Package.Id
    $lowerId = $id.ToLowerInvariant()
    $archive = Join-Path $cacheRoot "$lowerId.$wdkVersion.nupkg"
    $destination = Join-Path $packagesRoot "$id.$wdkVersion"
    $props = Join-Path $destination 'build\native'
    if ((Test-Path -LiteralPath $props) -and (Get-ChildItem -LiteralPath $props -Filter '*.props')) {
        return
    }
    $url = "https://api.nuget.org/v3-flatcontainer/$lowerId/$wdkVersion/$lowerId.$wdkVersion.nupkg"
    Ensure-Download $url $archive ([string]$Package.Sha256)
    Reset-ChildDirectory $destination $packagesRoot
    & tar.exe -xf $archive -C $destination
    if ($LASTEXITCODE -ne 0) {
        throw "tar.exe failed while extracting $archive"
    }
}

New-Item -ItemType Directory -Path $packagesRoot, $cacheRoot, $OutputDirectory -Force | Out-Null
foreach ($package in $packages) {
    Ensure-NuGetPackage $package
}

$programFilesX86 = [Environment]::GetFolderPath('ProgramFilesX86')
$vswhere = Join-Path $programFilesX86 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) {
    throw 'Visual Studio Build Tools with C++ support is required.'
}
$visualStudioRoot = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1)
if (-not $visualStudioRoot) {
    throw 'Visual Studio C++ build tools were not found.'
}
$msbuild = Join-Path $visualStudioRoot 'MSBuild\Current\Bin\MSBuild.exe'
if (-not (Test-Path -LiteralPath $msbuild)) {
    throw "MSBuild.exe was not found below $visualStudioRoot"
}
$vcRoot = Join-Path $visualStudioRoot 'MSBuild\Microsoft\VC'
$vcTargetsSource = Get-ChildItem -LiteralPath $vcRoot -Directory |
    Where-Object { $_.Name -match '^v\d+$' } |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $vcTargetsSource) {
    throw "Visual C++ MSBuild targets were not found below $vcRoot"
}

$vsixArchive = Join-Path $cacheRoot "WDK-$wdkVsixVersion.vsix"
$vsixExtracted = Join-Path $cacheRoot "WDK-$wdkVsixVersion"
Ensure-Download ([string]$wdkVsix.Url) $vsixArchive ([string]$wdkVsix.Sha256)
if (-not (Test-Path -LiteralPath (Join-Path $vsixExtracted 'extension.vsixmanifest'))) {
    Reset-ChildDirectory $vsixExtracted $cacheRoot
    & tar.exe -xf $vsixArchive -C $vsixExtracted
    if ($LASTEXITCODE -ne 0) {
        throw "tar.exe failed while extracting $vsixArchive"
    }
}

$overlay = Join-Path $cacheRoot "VCTargets-$($vcTargetsSource.Name)-WDK-$wdkVsixVersion"
$overlayStamp = Join-Path $overlay '.sense-mic-overlay'
$expectedStamp = "$($vcTargetsSource.FullName)`n$($wdkVsix.Sha256)"
$currentStamp = if (Test-Path -LiteralPath $overlayStamp) { Get-Content -LiteralPath $overlayStamp -Raw } else { '' }
if ($currentStamp.TrimEnd() -ne $expectedStamp.TrimEnd()) {
    Reset-ChildDirectory $overlay $cacheRoot
    Copy-Item -Path (Join-Path $vcTargetsSource.FullName '*') -Destination $overlay -Recurse -Force
    $wdkOverlay = Join-Path $vsixExtracted "`$MSBuild\Microsoft\VC\$($vcTargetsSource.Name)"
    if (-not (Test-Path -LiteralPath $wdkOverlay)) {
        throw "WDK VSIX does not contain the expected $($vcTargetsSource.Name) integration."
    }
    Copy-Item -Path (Join-Path $wdkOverlay '*') -Destination $overlay -Recurse -Force
    Set-Content -LiteralPath $overlayStamp -Value $expectedStamp -NoNewline
}

$wdkRoot = Join-Path $packagesRoot "Microsoft.Windows.WDK.x64.$wdkVersion\c"
$sdkRoot = Join-Path $packagesRoot "Microsoft.Windows.SDK.CPP.$wdkVersion\c"
$infToolPath = Join-Path $wdkRoot "bin\$kitVersion\x64\"
$inf2CatToolPath = Join-Path $wdkRoot "bin\$kitVersion\x86\"
$driverSignToolPath = Join-Path $sdkRoot "bin\$kitVersion\x86\"
$infVerif = Join-Path $wdkRoot "tools\$kitVersion\x64\infverif.exe"

$msbuildSignMode = switch ($SignMode) {
    'Test' { 'TestSign' }
    'Submission' { 'Off' }
}

$properties = @(
    '/m', '/t:Rebuild',
    '/p:Configuration=Release', '/p:Platform=x64',
    '/p:SpectreMitigation=false', '/p:Driver_SpectreMitigation=false',
    '/p:SkipPackageVerification=true', '/p:ApiValidator_Enable=false',
    "/p:SignMode=$msbuildSignMode",
    "/p:InfToolPath=$infToolPath", "/p:Inf2CatToolPath=$inf2CatToolPath",
    "/p:DrvCatToolPath=$infToolPath", "/p:DriverSignToolPath=$driverSignToolPath",
    "/p:VCTargetsPath=$overlay\"
)

Write-Host "Building SenseMicVAD x64 Release ($SignMode) with WDK $wdkVersion"
& $msbuild $solution @properties
if ($LASTEXITCODE -ne 0) {
    throw "MSBuild failed with exit code $LASTEXITCODE"
}

$packageDirectory = Join-Path $solutionRoot 'x64\Release\package'
$inf = Join-Path $packageDirectory 'SenseMicVAD.inf'
$sys = Join-Path $packageDirectory 'SenseMicVAD.sys'
$cat = Join-Path $packageDirectory 'sensemicvad.cat'
foreach ($artifact in @($inf, $sys, $cat)) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
        throw "Expected driver artifact is missing: $artifact"
    }
}

Write-Host 'Running InfVerif...'
& $infVerif /v /w $inf
if ($LASTEXITCODE -ne 0) {
    throw "InfVerif failed with exit code $LASTEXITCODE"
}

foreach ($name in @('SenseMicVAD.inf', 'SenseMicVAD.sys', 'sensemicvad.cat', 'SenseMicVAD.pdb', 'package.cer', 'build-manifest.json')) {
    $candidate = Join-Path $OutputDirectory $name
    if (Test-Path -LiteralPath $candidate) { Remove-Item -LiteralPath $candidate -Force }
}
Copy-Item -LiteralPath $inf, $sys, $cat -Destination $OutputDirectory -Force
$testCertificate = Join-Path $solutionRoot 'x64\Release\package.cer'
if (($SignMode -eq 'Test') -and (Test-Path -LiteralPath $testCertificate)) {
    Copy-Item -LiteralPath $testCertificate -Destination $OutputDirectory -Force
}

if ($SignMode -eq 'Test') {
    $signature = Get-AuthenticodeSignature -LiteralPath $cat
    if (-not $signature.SignerCertificate) { throw 'The test-signed catalog has no signer certificate.' }
} else {
    $signature = Get-AuthenticodeSignature -LiteralPath $cat
    if ($signature.SignerCertificate) {
        throw 'Partner Center staging output unexpectedly contains a signed catalog.'
    }

    $pdb = Get-ChildItem -LiteralPath (Join-Path $solutionRoot 'Source\Main') -Filter 'SenseMicVAD.pdb' -File -Recurse |
        Where-Object { $_.FullName -match '[\\/]x64[\\/]Release[\\/]' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $pdb) {
        throw 'Partner Center staging output requires SenseMicVAD.pdb, but the Release build did not produce it.'
    }
    Copy-Item -LiteralPath $pdb.FullName -Destination $OutputDirectory -Force
}

$distributionClass = if ($SignMode -eq 'Test') {
    'development-test-only'
} else {
    'unsigned-partner-center-staging'
}
$intendedUse = if ($SignMode -eq 'Test') {
    'development machines with test-signing enabled'
} else {
    'staging input for CAB signing and Microsoft Partner Center; not a public release asset'
}
$manifest = [ordered]@{
    schema = 2
    builtAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    architecture = 'x64'
    configuration = 'Release'
    signMode = $SignMode
    distributionClass = $distributionClass
    releaseEligible = $false
    intendedUse = $intendedUse
    wdk = $wdkVersion
    files = @{}
}
$manifestFiles = @('SenseMicVAD.inf', 'SenseMicVAD.sys', 'sensemicvad.cat')
if ($SignMode -eq 'Submission') { $manifestFiles += 'SenseMicVAD.pdb' }
if (($SignMode -eq 'Test') -and (Test-Path -LiteralPath (Join-Path $OutputDirectory 'package.cer') -PathType Leaf)) {
    $manifestFiles += 'package.cer'
}
foreach ($name in $manifestFiles) {
    $path = Join-Path $OutputDirectory $name
    $manifest.files[$name] = [ordered]@{
        bytes = (Get-Item -LiteralPath $path).Length
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'build-manifest.json') -Encoding utf8

Write-Host "Sense Mic driver package: $([IO.Path]::GetFullPath($OutputDirectory))"
