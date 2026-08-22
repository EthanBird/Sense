[CmdletBinding()]
param(
    [string]$Version = '0.4.13',
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'dist'),
    [string]$DriverStage,
    [string]$SourceCommit,
    [string]$IsccPath,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-Iscc {
    param([string]$ExplicitPath)
    if ($ExplicitPath) {
        $resolved = Resolve-Path -LiteralPath $ExplicitPath -ErrorAction Stop
        return $resolved.Path
    }
    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in @(
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
    )) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return $candidate
        }
    }
    throw 'ISCC.exe was not found. Install Inno Setup 6 or pass -IsccPath.'
}

if (-not $SourceCommit) {
    $SourceCommit = (& git -C (Split-Path $PSScriptRoot -Parent) rev-parse HEAD).Trim()
}
if ($SourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
    throw "SourceCommit must be a full Git commit: $SourceCommit"
}

$manifest = Join-Path $PSScriptRoot 'Cargo.toml'
if (-not $SkipBuild) {
    & cargo build --locked --release --manifest-path $manifest --bin sense-mic --bin sense-mic-gui
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed with exit code $LASTEXITCODE" }
}

$targetRoot = if ([string]::IsNullOrWhiteSpace($env:CARGO_TARGET_DIR)) {
    Join-Path $PSScriptRoot 'target'
} else {
    [IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)
}
$target = Join-Path $targetRoot 'release'
$stage = Join-Path $OutputRoot 'stage'
$setupOut = Join-Path $OutputRoot 'setup'
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage, $setupOut -Force | Out-Null

foreach ($file in @('sense-mic.exe', 'sense-mic-gui.exe')) {
    $source = Join-Path $target $file
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing release binary: $source" }
    Copy-Item -LiteralPath $source -Destination $stage
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination $stage
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'CLIENT-ONLY-NOTICE.txt') -Destination $stage
$repoRoot = Split-Path $PSScriptRoot -Parent
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $stage
Copy-Item -LiteralPath (Join-Path $repoRoot 'NOTICE') -Destination $stage
@(
    "Sense Mic $Version",
    "Source commit: $SourceCommit",
    "Built UTC: $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))",
    "Windows architecture: x64"
) | Set-Content -LiteralPath (Join-Path $stage 'BUILD-INFO.txt') -Encoding UTF8

$driverDefine = $null
if ($DriverStage) {
    $driverPath = (Resolve-Path -LiteralPath $DriverStage -ErrorAction Stop).Path
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'scripts\Assert-WindowsDriverPackage.ps1') `
        -PackagePath $driverPath -ExpectedClass MicrosoftSigned -MicrosoftSigningPolicy WHQL
    if ($LASTEXITCODE -ne 0) { throw 'Windows driver release policy rejected the supplied package.' }
    $driverDefine = "/DDriverStage=$driverPath"
}

$iscc = Resolve-Iscc $IsccPath
$iss = Join-Path $PSScriptRoot 'installer\SenseMic.iss'
$arguments = @(
    "/DAppVersion=$Version",
    "/DStageDir=$stage",
    "/DOutputDir=$setupOut",
    "/DSourceCommit=$SourceCommit"
)
if ($driverDefine) { $arguments += $driverDefine }
$arguments += $iss
& $iscc @arguments
if ($LASTEXITCODE -ne 0) { throw "ISCC failed with exit code $LASTEXITCODE" }

$setup = Join-Path $setupOut "SenseMicSetup-v$Version-windows-x64.exe"
if (-not (Test-Path -LiteralPath $setup -PathType Leaf)) { throw "Missing setup output: $setup" }
$hash = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash.ToLowerInvariant()
@("$hash  $(Split-Path $setup -Leaf)") | Set-Content -LiteralPath (Join-Path $setupOut 'SHA256SUMS-windows.txt') -Encoding ascii
[pscustomobject]@{
    Setup = $setup
    Sha256 = $hash
    DriverBundled = [bool]$DriverStage
    SourceCommit = $SourceCommit
}
