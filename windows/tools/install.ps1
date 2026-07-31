[CmdletBinding()]
param(
    [string]$BundleRoot = $PSScriptRoot,
    [string]$InstallRoot = (Join-Path $env:ProgramFiles "Sense"),
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Administrator)) {
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", "`"$PSCommandPath`"",
        "-BundleRoot", "`"$BundleRoot`"",
        "-InstallRoot", "`"$InstallRoot`""
    )
    if ($NoLaunch) { $arguments += "-NoLaunch" }
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $arguments
    return
}

$BundleRoot = (Resolve-Path -LiteralPath $BundleRoot).Path
$expectedDlls = Get-ChildItem -LiteralPath (Join-Path $BundleRoot "native") -Recurse -Filter "SenseTsf.dll" -File
if (-not $expectedDlls) {
    throw "SenseTsf.dll is missing from the bundle."
}

$resolvedInstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$programFilesRoot = [System.IO.Path]::GetFullPath($env:ProgramFiles).TrimEnd('\') + '\'
if (-not $resolvedInstallRoot.StartsWith($programFilesRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The install directory must stay under Program Files: $resolvedInstallRoot"
}

$installedX64 = Join-Path $InstallRoot "native\x64\SenseTsf.dll"
$installedX86 = Join-Path $InstallRoot "native\x86\SenseTsf.dll"
$regsvr64 = Join-Path $env:WINDIR "System32\regsvr32.exe"
$regsvr32 = Join-Path $env:WINDIR "SysWOW64\regsvr32.exe"

if (Test-Path -LiteralPath $installedX64) {
    & $regsvr64 /s /u $installedX64
}
if ((Test-Path -LiteralPath $regsvr32) -and (Test-Path -LiteralPath $installedX86)) {
    & $regsvr32 /s /u $installedX86
}

Get-Process -Name "Sense.AgentHost" -ErrorAction SilentlyContinue | Stop-Process -Force
New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
Copy-Item -Path (Join-Path $BundleRoot "*") -Destination $InstallRoot -Recurse -Force

if (Test-Path -LiteralPath $installedX64) {
    & $regsvr64 /s $installedX64
    if ($LASTEXITCODE -ne 0) { throw "x64 TSF registration failed: $LASTEXITCODE" }
}
if ((Test-Path -LiteralPath $regsvr32) -and (Test-Path -LiteralPath $installedX86)) {
    & $regsvr32 /s $installedX86
    if ($LASTEXITCODE -ne 0) { throw "x86 TSF registration failed: $LASTEXITCODE" }
}

$startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\Sense"
New-Item -ItemType Directory -Path $startMenu -Force | Out-Null
$shell = New-Object -ComObject WScript.Shell
$settingsShortcut = $shell.CreateShortcut((Join-Path $startMenu "Sense Settings.lnk"))
$settingsShortcut.TargetPath = Join-Path $InstallRoot "Sense.Settings.exe"
$settingsShortcut.WorkingDirectory = $InstallRoot
$settingsShortcut.IconLocation = (Join-Path $InstallRoot "Sense.Settings.exe") + ",0"
$settingsShortcut.Save()
$uninstallShortcut = $shell.CreateShortcut((Join-Path $startMenu "Uninstall Sense.lnk"))
$uninstallShortcut.TargetPath = "powershell.exe"
$uninstallShortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$InstallRoot\uninstall.ps1`""
$uninstallShortcut.WorkingDirectory = $InstallRoot
$uninstallShortcut.Save()

Write-Host "Sense Windows installed: $InstallRoot" -ForegroundColor Green
Write-Host "Press Win + Space and choose Sense under Chinese (Simplified)." -ForegroundColor Cyan

if (-not $NoLaunch) {
    Start-Process -FilePath (Join-Path $InstallRoot "Sense.Settings.exe")
}
