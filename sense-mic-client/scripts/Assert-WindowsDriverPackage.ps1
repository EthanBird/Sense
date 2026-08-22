[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PackagePath,

    [Parameter(Mandatory = $true)]
    [ValidateSet('DevelopmentTest', 'UnsignedSubmission', 'MicrosoftSigned')]
    [string]$ExpectedClass,

    [ValidateSet('Either', 'Attestation', 'WHQL')]
    [string]$MicrosoftSigningPolicy = 'Either',

    [string]$SignToolPath,
    [string]$OutputManifestPath,
    [switch]$PassThru
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'WindowsDriverSigningPolicy.ps1')

function Resolve-SignTool([string]$RequestedPath) {
    if ($RequestedPath) {
        $resolved = [IO.Path]::GetFullPath($RequestedPath)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "SignTool was not found at $resolved"
        }
        return $resolved
    }

    $command = Get-Command 'signtool.exe' -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $kitsRoot = Join-Path ([Environment]::GetFolderPath('ProgramFilesX86')) 'Windows Kits\10\bin'
    if (Test-Path -LiteralPath $kitsRoot -PathType Container) {
        $candidate = Get-ChildItem -LiteralPath $kitsRoot -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
            Sort-Object { [Version]$_.Name } -Descending |
            ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }

    throw 'SignTool is required to validate a Microsoft-signed driver package.'
}

function Get-EnhancedKeyUsageOids($Certificate) {
    $result = @()
    if (-not $Certificate) { return $result }
    foreach ($extension in $Certificate.Extensions) {
        if ($extension -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]) {
            foreach ($usage in $extension.EnhancedKeyUsages) {
                $result += [string]$usage.Value
            }
        }
    }
    return @($result | Sort-Object -Unique)
}

function Assert-CatalogMembership(
    [string]$ResolvedSignTool,
    [string]$CatalogPath,
    [string]$MemberPath,
    [switch]$AllowUntrustedRoot
) {
    # /c selects this package's catalog directly, so no catalog registration or
    # certificate-store mutation is needed.  Development certificates are
    # intentionally untrusted on a clean CI host: SignTool reports membership
    # before returning the expected trust-chain error.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $ResolvedSignTool verify /v /c $CatalogPath $MemberPath 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output = $lines -join "`n"
    if ($exitCode -eq 0) { return }

    $reportsMembership = $output -match '(?im)^\s*File is signed in catalog:'
    $reportsUntrustedRoot = $output -match '(?is)certificate chain processed.*root.*not trusted'
    $reportsMissingMember = $output -match '(?i)not found in the specified catalog'
    if ($AllowUntrustedRoot -and $reportsMembership -and $reportsUntrustedRoot -and (-not $reportsMissingMember)) {
        return
    }

    throw "Catalog membership verification failed for $([IO.Path]::GetFileName($MemberPath)) (SignTool exit $exitCode): $output"
}

function Assert-ManifestHashes([string]$Root, $Manifest) {
    if (-not $Manifest.files) { throw 'Driver build manifest has no files map.' }
    foreach ($property in $Manifest.files.PSObject.Properties) {
        $name = [string]$property.Name
        if ([IO.Path]::GetFileName($name) -ne $name) {
            throw "Driver build manifest contains an invalid file name: $name"
        }
        $path = Join-Path $Root $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Driver build manifest references a missing file: $name"
        }
        $expectedBytes = [Int64]$property.Value.bytes
        $actualBytes = (Get-Item -LiteralPath $path).Length
        if ($expectedBytes -ne $actualBytes) {
            throw "Driver build manifest byte count mismatch for $name"
        }
        $expectedHash = ([string]$property.Value.sha256).ToLowerInvariant()
        $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($expectedHash -ne $actualHash) {
            throw "Driver build manifest SHA-256 mismatch for $name"
        }
    }
}

function Assert-BuildManifestContract($Manifest, [string[]]$ExpectedFileNames) {
    if ([int]$Manifest.schema -ne 2) { throw 'Driver build manifest schema must be 2.' }
    if ([string]$Manifest.architecture -ne 'x64') { throw 'Driver build manifest architecture must be x64.' }
    if ([string]$Manifest.configuration -ne 'Release') { throw 'Driver build manifest configuration must be Release.' }
    if (($Manifest.releaseEligible -isnot [bool]) -or [bool]$Manifest.releaseEligible) {
        throw 'Development/submission driver build manifest must declare releaseEligible false.'
    }
    if (-not [string]$Manifest.intendedUse) { throw 'Driver build manifest intendedUse is required.' }
    if (-not [string]$Manifest.wdk) { throw 'Driver build manifest WDK version is required.' }
    if (-not $Manifest.files) { throw 'Driver build manifest has no files map.' }
    $actualNames = @($Manifest.files.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @($ExpectedFileNames | Sort-Object)
    if (@(Compare-Object -ReferenceObject $expectedNames -DifferenceObject $actualNames).Count -ne 0) {
        throw "Driver build manifest file set mismatch: expected $($expectedNames -join ', '); observed $($actualNames -join ', ')"
    }
}

$root = [IO.Path]::GetFullPath($PackagePath)
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw "Driver package directory was not found: $root"
}

$requiredNames = @('SenseMicVAD.inf', 'SenseMicVAD.sys', 'sensemicvad.cat')
foreach ($name in $requiredNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $name) -PathType Leaf)) {
        throw "Driver package is missing $name below $root"
    }
}

$inf = Join-Path $root 'SenseMicVAD.inf'
$sys = Join-Path $root 'SenseMicVAD.sys'
$cat = Join-Path $root 'sensemicvad.cat'
$infSource = Get-Content -LiteralPath $inf -Raw -Encoding unicode
if ($infSource -notmatch '(?im)^\s*CatalogFile\s*=\s*SenseMicVAD\.cat\s*$') {
    # WDK currently emits UTF-16 INF files, but accept UTF-8 Partner Center payloads too.
    $infSource = Get-Content -LiteralPath $inf -Raw -Encoding utf8
}
foreach ($identityPattern in @(
    '(?im)^\s*Class\s*=\s*MEDIA\s*$',
    '(?im)^\s*CatalogFile\s*=\s*SenseMicVAD\.cat\s*$',
    '(?im)^\s*%SENSEMICVAD_SA\.DeviceDesc%\s*=\s*SENSEMICVAD_SA\s*,\s*ROOT\\SenseMicVAD\s*$',
    '(?im)^\s*ServiceBinary\s*=\s*%13%\\SenseMicVAD\.sys\s*$'
)) {
    if ($infSource -notmatch $identityPattern) {
        throw "Driver INF identity check failed for pattern: $identityPattern"
    }
}
$versionInfo = (Get-Item -LiteralPath $sys).VersionInfo
if ([string]$versionInfo.OriginalFilename -ne 'SenseMicVAD.sys') {
    throw "Driver binary identity mismatch: OriginalFilename is '$($versionInfo.OriginalFilename)'"
}
if ([string]$versionInfo.FileDescription -notmatch '^Sense Mic ') {
    throw "Driver binary identity mismatch: FileDescription is '$($versionInfo.FileDescription)'"
}
$buildManifestPath = Join-Path $root 'build-manifest.json'
$buildManifest = $null
if (Test-Path -LiteralPath $buildManifestPath -PathType Leaf) {
    $buildManifest = Get-Content -LiteralPath $buildManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
}

$signature = Get-AuthenticodeSignature -LiteralPath $cat
$binarySignature = Get-AuthenticodeSignature -LiteralPath $sys
$certificate = $signature.SignerCertificate
$ekuOids = @(Get-EnhancedKeyUsageOids $certificate)
$signerSubject = if ($certificate) { [string]$certificate.Subject } else { $null }
$signerIssuer = if ($certificate) { [string]$certificate.Issuer } else { $null }
$signerThumbprint = if ($certificate) { [string]$certificate.Thumbprint } else { $null }
$signingKind = $null

switch ($ExpectedClass) {
    'DevelopmentTest' {
        if (-not $buildManifest) { throw 'Development test driver package requires build-manifest.json.' }
        Assert-BuildManifestContract $buildManifest @(
            'SenseMicVAD.inf',
            'SenseMicVAD.sys',
            'sensemicvad.cat',
            'package.cer'
        )
        if ([string]$buildManifest.signMode -ne 'Test') {
            throw 'Development test driver package manifest must declare signMode Test.'
        }
        if ([string]$buildManifest.distributionClass -ne 'development-test-only') {
            throw 'Development test driver package manifest has the wrong distributionClass.'
        }
        Assert-ManifestHashes $root $buildManifest
        if (-not $certificate) { throw 'Development test catalog has no signer certificate.' }
        if (-not $binarySignature.SignerCertificate) { throw 'Development test driver binary has no signer certificate.' }
        if ($binarySignature.SignerCertificate.Thumbprint -ne $certificate.Thumbprint) {
            throw 'Development test catalog and driver binary use different signer certificates.'
        }
        if ($signerSubject -match 'Microsoft Windows Hardware Compatibility Publisher') {
            throw 'Development test package unexpectedly uses the Microsoft hardware publisher certificate.'
        }
        if (-not (Test-Path -LiteralPath (Join-Path $root 'package.cer') -PathType Leaf)) {
            throw 'Development test package is missing package.cer.'
        }
        $resolvedSignTool = Resolve-SignTool $SignToolPath
        foreach ($member in @($inf, $sys)) {
            Assert-CatalogMembership -ResolvedSignTool $resolvedSignTool -CatalogPath $cat `
                -MemberPath $member -AllowUntrustedRoot
        }
        $signingKind = 'test'
    }

    'UnsignedSubmission' {
        if (-not $buildManifest) { throw 'Unsigned submission driver package requires build-manifest.json.' }
        Assert-BuildManifestContract $buildManifest @(
            'SenseMicVAD.inf',
            'SenseMicVAD.sys',
            'sensemicvad.cat',
            'SenseMicVAD.pdb'
        )
        if ([string]$buildManifest.signMode -ne 'Submission') {
            throw 'Unsigned submission driver package manifest must declare signMode Submission.'
        }
        if ([string]$buildManifest.distributionClass -ne 'unsigned-partner-center-staging') {
            throw 'Unsigned submission driver package manifest has the wrong distributionClass.'
        }
        Assert-ManifestHashes $root $buildManifest
        if ($certificate) { throw 'Unsigned submission catalog unexpectedly has a signer certificate.' }
        if ($binarySignature.SignerCertificate) { throw 'Unsigned submission driver binary unexpectedly has a signer certificate.' }
        if (-not (Test-Path -LiteralPath (Join-Path $root 'SenseMicVAD.pdb') -PathType Leaf)) {
            throw 'Unsigned Partner Center staging directory is missing SenseMicVAD.pdb.'
        }
        $signingKind = 'unsigned-partner-center-staging'
    }

    'MicrosoftSigned' {
        if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Microsoft driver catalog signature is not valid: $($signature.Status) - $($signature.StatusMessage)"
        }
        if (-not $certificate) { throw 'Microsoft-signed catalog has no signer certificate.' }
        if ($signerSubject -notmatch 'Microsoft Windows Hardware Compatibility Publisher') {
            throw "Catalog signer is not the Microsoft Windows Hardware Compatibility Publisher: $signerSubject"
        }

        $signingKind = Resolve-MicrosoftDriverSigningKind -EnhancedKeyUsageOids $ekuOids
        if (-not (Test-MicrosoftDriverSigningPolicy -SigningKind $signingKind -Policy $MicrosoftSigningPolicy)) {
            $expectedSigningKind = if ($MicrosoftSigningPolicy -eq 'Attestation') {
                'microsoft-attestation'
            } else {
                'microsoft-whql-or-hlk'
            }
            throw "Expected $expectedSigningKind signing, observed $signingKind"
        }

        $resolvedSignTool = Resolve-SignTool $SignToolPath
        foreach ($member in @($inf, $sys)) {
            & $resolvedSignTool verify /kp /v /c $cat $member
            if ($LASTEXITCODE -ne 0) {
                throw "Kernel-policy catalog verification failed for $member with exit code $LASTEXITCODE"
            }
        }
    }
}

$files = [ordered]@{}
foreach ($name in $requiredNames) {
    $path = Join-Path $root $name
    $files[$name] = [ordered]@{
        bytes = (Get-Item -LiteralPath $path).Length
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$result = [ordered]@{
    schema = 1
    validatedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    expectedClass = $ExpectedClass
    releaseEligible = (
        ($ExpectedClass -eq 'MicrosoftSigned') -and
        ($signingKind -eq 'microsoft-whql-or-hlk')
    )
    signingKind = $signingKind
    microsoftSigningPolicy = if ($ExpectedClass -eq 'MicrosoftSigned') { $MicrosoftSigningPolicy } else { $null }
    signatureStatus = [string]$signature.Status
    binarySignatureStatus = [string]$binarySignature.Status
    signer = [ordered]@{
        subject = $signerSubject
        issuer = $signerIssuer
        thumbprint = $signerThumbprint
        enhancedKeyUsageOids = $ekuOids
    }
    files = $files
}

if ($OutputManifestPath) {
    $manifestOutput = [IO.Path]::GetFullPath($OutputManifestPath)
    $manifestParent = Split-Path -Parent $manifestOutput
    if ($manifestParent) { New-Item -ItemType Directory -Path $manifestParent -Force | Out-Null }
    $result | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $manifestOutput -Encoding utf8
}

Write-Host "Validated Sense Mic driver package as $ExpectedClass ($signingKind): $root"
if ($PassThru) { [PSCustomObject]$result }
