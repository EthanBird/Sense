[CmdletBinding()]
param(
    [string]$TestDriverPackage
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Throws([scriptblock]$Action, [string]$MessagePattern) {
    $caught = $null
    try {
        & $Action
    } catch {
        $caught = $_
    }
    if (-not $caught) { throw 'Expected command to fail, but it succeeded.' }
    if (($MessagePattern) -and ($caught.Exception.Message -notmatch $MessagePattern)) {
        throw "Failure did not match '$MessagePattern': $($caught.Exception.Message)"
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$clientRoot = Split-Path -Parent $scriptRoot
$repoRoot = Split-Path -Parent $clientRoot
$packageScript = Join-Path $clientRoot 'package-windows.ps1'
$validator = Join-Path $scriptRoot 'Assert-WindowsDriverPackage.ps1'
$signingPolicy = Join-Path $scriptRoot 'WindowsDriverSigningPolicy.ps1'
$releaseWorkflow = Join-Path $repoRoot '.github\workflows\release-v0.4.12.yml'
$repairWorkflow = Join-Path $repoRoot '.github\workflows\repair-v0.4.12-sense-mic-assets.yml'

. $signingPolicy

$ekuFixtures = @(
    [PSCustomObject]@{
        Name = 'attestation-only'
        Oids = @('1.3.6.1.4.1.311.10.3.5.1')
        Expected = 'microsoft-attestation'
    },
    [PSCustomObject]@{
        Name = 'whql-only'
        Oids = @('1.3.6.1.4.1.311.10.3.5')
        Expected = 'microsoft-whql-or-hlk'
    },
    [PSCustomObject]@{
        # Some chains expose both values.  The more specific attestation OID
        # wins so classification remains a single value.
        Name = 'attestation-precedence'
        Oids = @('1.3.6.1.4.1.311.10.3.5', '1.3.6.1.4.1.311.10.3.5.1')
        Expected = 'microsoft-attestation'
    }
)
foreach ($fixture in $ekuFixtures) {
    $actual = Resolve-MicrosoftDriverSigningKind -EnhancedKeyUsageOids $fixture.Oids
    Assert-True ($actual -eq $fixture.Expected) `
        "EKU fixture $($fixture.Name) resolved to $actual instead of $($fixture.Expected)."
}
Assert-Throws {
    Resolve-MicrosoftDriverSigningKind -EnhancedKeyUsageOids @(
        '1.3.6.1.4.1.311.10.3.39',
        '1.3.6.1.5.5.7.3.3'
    )
} 'neither the attestation EKU.*nor the HLK/WHQL'
Assert-True (Test-MicrosoftDriverSigningPolicy -SigningKind microsoft-attestation -Policy Either) `
    'Either policy rejected an attestation catalog.'
Assert-True (Test-MicrosoftDriverSigningPolicy -SigningKind microsoft-whql-or-hlk -Policy WHQL) `
    'WHQL policy rejected an HLK/WHQL catalog.'
Assert-True (-not (Test-MicrosoftDriverSigningPolicy -SigningKind microsoft-attestation -Policy WHQL)) `
    'WHQL policy accepted an attestation catalog.'

$packageSource = Get-Content -LiteralPath $packageScript -Raw -Encoding utf8
$validatorSource = Get-Content -LiteralPath $validator -Raw -Encoding utf8
Assert-True ($packageSource -match "\[string\]\`$PackageFlavor\s*=\s*'ClientOnly'") `
    'Windows packaging must default to ClientOnly.'
Assert-True ($packageSource -match "\[string\]\`$MicrosoftSigningPolicy\s*=\s*'WHQL'") `
    'Public MicrosoftSigned packaging must default to the WHQL policy.'
Assert-True ($packageSource -notmatch 'DriverSignMode') `
    'The legacy DriverSignMode packaging path must stay removed.'
Assert-True ($validatorSource -match '\^\\s\*%SENSEMICVAD_SA') `
    'The driver identity check must anchor ROOT\SenseMicVAD to its hardware-id model line.'

$workflowSource = Get-Content -LiteralPath $releaseWorkflow -Raw -Encoding utf8
$releaseWorkflows = Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github\workflows') -File |
    Where-Object { $_.Name -match '^release.*\.ya?ml$' }
foreach ($workflow in $releaseWorkflows) {
    $source = Get-Content -LiteralPath $workflow.FullName -Raw -Encoding utf8
    Assert-True ($source -notmatch '(?i)DriverSignMode\s+Test') `
        "Public release workflow $($workflow.Name) packages a test-signed driver."
    Assert-True ($source -notmatch '(?i)PackageFlavor\s+DevelopmentTest') `
        "Public release workflow $($workflow.Name) packages a development test driver."
}
Assert-True ($workflowSource -match 'SenseMicClient-v0\.4\.12-windows-x64-client-only\.zip') `
    'The v0.4.12 repair workflow must name the Windows artifact client-only.'
Assert-True ($workflowSource -match '(?i)PackageFlavor\s+ClientOnly') `
    'The v0.4.12 release workflow must explicitly select ClientOnly packaging.'

Assert-True (Test-Path -LiteralPath $repairWorkflow -PathType Leaf) `
    'The v0.4.12 asset correction requires a dedicated workflow_dispatch workflow.'
$repairSource = Get-Content -LiteralPath $repairWorkflow -Raw -Encoding utf8
Assert-True ($repairSource -match '(?m)^\s*workflow_dispatch:\s*$') `
    'The v0.4.12 asset correction workflow must use workflow_dispatch.'
Assert-True ($repairSource -notmatch '(?m)^\s*(push|pull_request|schedule):\s*$') `
    'The v0.4.12 asset correction workflow must not run from push, pull_request, or schedule.'
Assert-True ($repairSource -match '5da18356653779415bd80d4fcc400fdba77c43da') `
    'The v0.4.12 asset correction workflow must pin the immutable tag SHA.'
foreach ($digest in @(
    'dcb2c9664880523ea010ecc8eb532629c20b1a1772c33ecea45d4e5d4aef628e',
    'aaca4dd9c90bf7cb2b4b73c72ac6188ea6ecc465e2365ab7958855cf9f2fe8ae',
    '5c212ceb139ecd72c989f337ce3deed11f32ce8db2323beadca3d8df51fee419',
    'c4e7f1f6a3df4bca7afaf30a9e08724469588dce1f16cad52ab3ae69e0be68be'
)) {
    Assert-True ($repairSource -match $digest) `
        "The v0.4.12 repair workflow is missing approved payload digest $digest."
}
Assert-True ($repairSource -match 'preserve_existing=true') `
    'The v0.4.12 repair workflow must preserve a semantically identical corrected Windows asset.'
Assert-True ($repairSource -match 'SenseMicClient-v0\.4\.12-windows-x64-client-only\.zip') `
    'The v0.4.12 asset correction workflow must publish the client-only Windows asset.'
Assert-True ($repairSource -match 'SenseMicClient-v0\.4\.12-windows-x64\.zip') `
    'The v0.4.12 asset correction workflow must identify the legacy Windows asset.'
Assert-True ($repairSource -notmatch '(?i)(createRef|updateRef|git\s+(tag|push).*(v0\.4\.12|RELEASE_TAG))') `
    'The v0.4.12 asset correction workflow must never create, move, or push the tag.'
$uploadNewOffset = $repairSource.IndexOf('gh release upload "$RELEASE_TAG" "prepared/$WINDOWS_ASSET"')
$deleteLegacyOffset = $repairSource.IndexOf('gh release delete-asset "$RELEASE_TAG" "$LEGACY_WINDOWS_ASSET"')
$replaceChecksumOffset = $repairSource.IndexOf('gh release delete-asset "$RELEASE_TAG" SHA256SUMS.txt')
Assert-True (($uploadNewOffset -ge 0) -and ($uploadNewOffset -lt $deleteLegacyOffset)) `
    'The corrected Windows asset must be uploaded before the legacy asset is deleted.'
Assert-True (($deleteLegacyOffset -ge 0) -and ($deleteLegacyOffset -lt $replaceChecksumOffset)) `
    'The checksum manifest must be replaced only after the legacy Windows asset is deleted.'

$fixtureRoot = Join-Path $repoRoot 'build\sense-mic-package-policy-test'
$fixtureExe = Join-Path $fixtureRoot 'sense-mic.exe'
$fixtureOutput = Join-Path $fixtureRoot 'out'
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
[IO.File]::WriteAllBytes($fixtureExe, [byte[]](0x4d, 0x5a, 0x00, 0x00))

$fixtureSourceCommit = '5da18356653779415bd80d4fcc400fdba77c43da'
& $packageScript -PackageFlavor ClientOnly -ClientExecutable $fixtureExe `
    -SourceCommit $fixtureSourceCommit -OutputRoot $fixtureOutput
$archive = Join-Path $fixtureOutput 'SenseMicClient-windows-x64-client-only.zip'
Assert-True (Test-Path -LiteralPath $archive -PathType Leaf) 'Client-only archive was not produced.'
$expanded = Join-Path $fixtureRoot 'expanded'
if (Test-Path -LiteralPath $expanded) { Remove-Item -LiteralPath $expanded -Recurse -Force }
Expand-Archive -LiteralPath $archive -DestinationPath $expanded
Assert-True (-not (Test-Path -LiteralPath (Join-Path $expanded 'driver') -PathType Container)) `
    'Client-only archive unexpectedly contains a driver directory.'
Assert-True (Test-Path -LiteralPath (Join-Path $expanded 'CLIENT-ONLY-NOTICE.txt') -PathType Leaf) `
    'Client-only archive is missing its explicit driver boundary notice.'
$artifactManifest = Get-Content -LiteralPath (Join-Path $expanded 'PACKAGE-MANIFEST.json') -Raw -Encoding utf8 | ConvertFrom-Json
Assert-True ([string]$artifactManifest.artifactClass -eq 'windows-client-only') `
    'Client-only archive has the wrong artifactClass.'
Assert-True (-not [bool]$artifactManifest.driverIncluded) `
    'Client-only archive says that a driver is included.'
Assert-True ([string]$artifactManifest.sourceCommit -eq $fixtureSourceCommit) `
    'Client-only archive did not preserve its immutable source commit.'

Assert-Throws {
    & $packageScript -PackageFlavor MicrosoftSigned -ClientExecutable $fixtureExe -OutputRoot $fixtureOutput
} 'requires a Partner Center returned driver directory'
$staleSignedArchive = Join-Path $fixtureOutput 'SenseMicClient-windows-x64.zip'
$staleSignedChecksum = "$staleSignedArchive.sha256"
Set-Content -LiteralPath $staleSignedArchive -Value 'stale archive' -Encoding ascii
Set-Content -LiteralPath $staleSignedChecksum -Value 'stale checksum' -Encoding ascii
Assert-Throws {
    & $packageScript -PackageFlavor MicrosoftSigned -MicrosoftSigningPolicy Attestation `
        -DriverPackage $fixtureRoot -ClientExecutable $fixtureExe -OutputRoot $fixtureOutput
} 'requires an HLK/WHQL catalog'
Assert-True (-not (Test-Path -LiteralPath $staleSignedArchive -PathType Leaf)) `
    'A failed package invocation left a stale Windows archive behind.'
Assert-True (-not (Test-Path -LiteralPath $staleSignedChecksum -PathType Leaf)) `
    'A failed package invocation left a stale checksum behind.'

if ($TestDriverPackage) {
    & $validator -PackagePath $TestDriverPackage -ExpectedClass DevelopmentTest
    Assert-Throws {
        & $validator -PackagePath $TestDriverPackage -ExpectedClass MicrosoftSigned
    } 'signature is not valid|Catalog signer is not'

    $tamperedDriver = Join-Path $fixtureRoot 'tampered-driver'
    $resolvedTampered = [IO.Path]::GetFullPath($tamperedDriver)
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot).TrimEnd('\') + '\'
    Assert-True ($resolvedTampered.StartsWith($resolvedFixture, [StringComparison]::OrdinalIgnoreCase)) `
        'Tampered-driver fixture escaped the test root.'
    if (Test-Path -LiteralPath $resolvedTampered) {
        Remove-Item -LiteralPath $resolvedTampered -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedTampered -Force | Out-Null
    Copy-Item -Path (Join-Path ([IO.Path]::GetFullPath($TestDriverPackage)) '*') `
        -Destination $resolvedTampered -Recurse -Force
    [IO.File]::AppendAllText((Join-Path $resolvedTampered 'SenseMicVAD.inf'), "`r`n; tampered`r`n")
    Assert-Throws {
        & $validator -PackagePath $resolvedTampered -ExpectedClass DevelopmentTest
    } 'byte count mismatch|SHA-256 mismatch'

    $membershipTampered = Join-Path $fixtureRoot 'membership-tampered-driver'
    $resolvedMembershipTampered = [IO.Path]::GetFullPath($membershipTampered)
    Assert-True ($resolvedMembershipTampered.StartsWith($resolvedFixture, [StringComparison]::OrdinalIgnoreCase)) `
        'Catalog-membership fixture escaped the test root.'
    if (Test-Path -LiteralPath $resolvedMembershipTampered) {
        Remove-Item -LiteralPath $resolvedMembershipTampered -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedMembershipTampered -Force | Out-Null
    Copy-Item -Path (Join-Path ([IO.Path]::GetFullPath($TestDriverPackage)) '*') `
        -Destination $resolvedMembershipTampered -Recurse -Force
    $membershipInf = Join-Path $resolvedMembershipTampered 'SenseMicVAD.inf'
    [IO.File]::AppendAllText($membershipInf, "`r`n; membership tampered`r`n")
    $membershipManifestPath = Join-Path $resolvedMembershipTampered 'build-manifest.json'
    $membershipManifest = Get-Content -LiteralPath $membershipManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
    $membershipManifest.files.'SenseMicVAD.inf'.bytes = (Get-Item -LiteralPath $membershipInf).Length
    $membershipManifest.files.'SenseMicVAD.inf'.sha256 = `
        (Get-FileHash -LiteralPath $membershipInf -Algorithm SHA256).Hash.ToLowerInvariant()
    $membershipManifest | ConvertTo-Json -Depth 7 |
        Set-Content -LiteralPath $membershipManifestPath -Encoding utf8
    Assert-Throws {
        & $validator -PackagePath $resolvedMembershipTampered -ExpectedClass DevelopmentTest
    } '(?s)Catalog membership verification failed.*not found in the specified catalog'

    $incompleteManifestDriver = Join-Path $fixtureRoot 'incomplete-manifest-driver'
    $resolvedIncompleteManifest = [IO.Path]::GetFullPath($incompleteManifestDriver)
    Assert-True ($resolvedIncompleteManifest.StartsWith($resolvedFixture, [StringComparison]::OrdinalIgnoreCase)) `
        'Incomplete-manifest fixture escaped the test root.'
    if (Test-Path -LiteralPath $resolvedIncompleteManifest) {
        Remove-Item -LiteralPath $resolvedIncompleteManifest -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedIncompleteManifest -Force | Out-Null
    Copy-Item -Path (Join-Path ([IO.Path]::GetFullPath($TestDriverPackage)) '*') `
        -Destination $resolvedIncompleteManifest -Recurse -Force
    $incompleteManifestPath = Join-Path $resolvedIncompleteManifest 'build-manifest.json'
    $incompleteManifest = Get-Content -LiteralPath $incompleteManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
    $incompleteManifest.files.PSObject.Properties.Remove('package.cer')
    $incompleteManifest | ConvertTo-Json -Depth 7 |
        Set-Content -LiteralPath $incompleteManifestPath -Encoding utf8
    Assert-Throws {
        & $validator -PackagePath $resolvedIncompleteManifest -ExpectedClass DevelopmentTest
    } 'file set mismatch'

    $developmentOutput = Join-Path $fixtureRoot 'development-package'
    & $packageScript -PackageFlavor DevelopmentTest -DriverPackage $TestDriverPackage `
        -ClientExecutable $fixtureExe -SourceCommit $fixtureSourceCommit -OutputRoot $developmentOutput
    $developmentArchive = Join-Path $developmentOutput 'SenseMicClient-windows-x64-development-test.zip'
    Assert-True (Test-Path -LiteralPath $developmentArchive -PathType Leaf) `
        'Development-test archive was not produced.'
    $developmentExpanded = Join-Path $fixtureRoot 'development-expanded'
    $resolvedDevelopmentExpanded = [IO.Path]::GetFullPath($developmentExpanded)
    Assert-True ($resolvedDevelopmentExpanded.StartsWith($resolvedFixture, [StringComparison]::OrdinalIgnoreCase)) `
        'Expanded development package escaped the test root.'
    if (Test-Path -LiteralPath $resolvedDevelopmentExpanded) {
        Remove-Item -LiteralPath $resolvedDevelopmentExpanded -Recurse -Force
    }
    Expand-Archive -LiteralPath $developmentArchive -DestinationPath $resolvedDevelopmentExpanded
    & $validator -PackagePath (Join-Path $resolvedDevelopmentExpanded 'driver\windows\x64') `
        -ExpectedClass DevelopmentTest
}

Write-Host 'Sense Mic Windows release policy tests passed.'
