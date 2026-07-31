[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",

    [ValidateSet("all", "x64", "x86", "arm64")]
    [string]$Architecture = "all",

    [switch]$Clean,
    [switch]$SkipTests,
    [switch]$SkipHost,
    [switch]$NoArchive
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"

$WindowsRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$RepositoryRoot = (Resolve-Path (Join-Path $WindowsRoot "..")).Path
$OutRoot = Join-Path $WindowsRoot "out"
$BundleRoot = Join-Path $OutRoot "bundle"

function Assert-UnderOutRoot {
    param([Parameter(Mandatory)][string]$Path)
    $full = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetFullPath($OutRoot).TrimEnd('\') + '\'
    if (-not $full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Output path is outside windows/out: $full"
    }
}

function Find-CMake {
    $command = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path -LiteralPath $vswhere)) {
        throw "cmake.exe and Visual Studio Installer were not found."
    }
    $installation = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $installation) {
        throw "Visual Studio with the C++ toolchain was not found."
    }
    $candidate = Join-Path $installation "Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    if (-not (Test-Path -LiteralPath $candidate)) {
        throw "Visual Studio CMake was not found: $candidate"
    }
    return $candidate
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$CommandArguments
    )
    & $FilePath @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($CommandArguments -join ' ')"
    }
}

function Build-Native {
    param(
        [Parameter(Mandatory)][string]$Platform,
        [Parameter(Mandatory)][string]$CMake
    )

    $generatorPlatform = switch ($Platform) {
        "x64" { "x64" }
        "x86" { "Win32" }
        "arm64" { "ARM64" }
    }
    $buildDirectory = Join-Path $OutRoot "native-$Platform"
    Assert-UnderOutRoot $buildDirectory
    if ($Clean -and (Test-Path -LiteralPath $buildDirectory)) {
        Remove-Item -LiteralPath $buildDirectory -Recurse -Force
    }

    Invoke-Checked -FilePath $CMake -CommandArguments @(
        "-S", $WindowsRoot,
        "-B", $buildDirectory,
        "-G", "Visual Studio 17 2022",
        "-A", $generatorPlatform,
        "-DSENSE_BUILD_TESTS=ON"
    )
    Invoke-Checked -FilePath $CMake -CommandArguments @(
        "--build", $buildDirectory,
        "--config", $Configuration,
        "--parallel"
    )

    if (-not $SkipTests) {
        $CTest = Join-Path (Split-Path $CMake) "ctest.exe"
        Invoke-Checked -FilePath $CTest -CommandArguments @(
            "--test-dir", $buildDirectory,
            "-C", $Configuration,
            "--output-on-failure"
        )
    }

    $nativeBundle = Join-Path $BundleRoot "native\$Platform"
    New-Item -ItemType Directory -Path $nativeBundle -Force | Out-Null
    $source = Join-Path $buildDirectory "native\tsf\$Configuration"
    Copy-Item -LiteralPath (Join-Path $source "SenseTsf.dll") -Destination $nativeBundle -Force
    Copy-Item -LiteralPath (Join-Path $source "data") -Destination $nativeBundle -Recurse -Force
}

$CMake = Find-CMake
New-Item -ItemType Directory -Path $OutRoot -Force | Out-Null
Assert-UnderOutRoot $BundleRoot
if (Test-Path -LiteralPath $BundleRoot) {
    Remove-Item -LiteralPath $BundleRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $BundleRoot -Force | Out-Null

$platforms = if ($Architecture -eq "all") { @("x64", "x86") } else { @($Architecture) }
foreach ($platform in $platforms) {
    Build-Native -Platform $platform -CMake $CMake
}

if (-not $SkipHost) {
    $settingsProject = Join-Path $WindowsRoot "host\Sense.Settings\Sense.Settings.csproj"
    $agentProject = Join-Path $WindowsRoot "host\Sense.AgentHost\Sense.AgentHost.csproj"
    $settingsOutput = Join-Path $OutRoot "publish-settings"
    $agentOutput = Join-Path $OutRoot "publish-agent"
    foreach ($path in @($settingsOutput, $agentOutput)) {
        Assert-UnderOutRoot $path
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }

    Invoke-Checked -FilePath "dotnet" -CommandArguments @(
        "publish", $settingsProject,
        "-c", $Configuration,
        "-r", "win-x64",
        "--self-contained", "false",
        "-o", $settingsOutput
    )
    Invoke-Checked -FilePath "dotnet" -CommandArguments @(
        "publish", $agentProject,
        "-c", $Configuration,
        "-r", "win-x64",
        "--self-contained", "false",
        "-o", $agentOutput
    )
    Copy-Item -Path (Join-Path $settingsOutput "*") -Destination $BundleRoot -Recurse -Force
    Copy-Item -Path (Join-Path $agentOutput "*") -Destination $BundleRoot -Recurse -Force
    Invoke-Checked `
        -FilePath (Join-Path $agentOutput "Sense.AgentHost.exe") `
        -CommandArguments @("--self-test")
}

Copy-Item -LiteralPath (Join-Path $WindowsRoot "tools\install.ps1") -Destination $BundleRoot -Force
Copy-Item -LiteralPath (Join-Path $WindowsRoot "tools\uninstall.ps1") -Destination $BundleRoot -Force
Copy-Item -LiteralPath (Join-Path $RepositoryRoot "LICENSE") -Destination $BundleRoot -Force
Copy-Item -LiteralPath (Join-Path $RepositoryRoot "NOTICE") -Destination $BundleRoot -Force
Copy-Item -LiteralPath (Join-Path $WindowsRoot "README.md") -Destination (Join-Path $BundleRoot "README-Windows.md") -Force

if (-not $NoArchive) {
    $archive = Join-Path $OutRoot "Sense-Windows-$Configuration.zip"
    Assert-UnderOutRoot $archive
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
    Compress-Archive -Path (Join-Path $BundleRoot "*") -DestinationPath $archive -CompressionLevel Optimal
    Write-Host "Archive: $archive" -ForegroundColor Cyan
}

Write-Host "Bundle: $BundleRoot" -ForegroundColor Green
