[CmdletBinding()]
param(
    [string]$InstallRoot = (Join-Path $env:ProgramFiles "Sense"),
    [switch]$PurgeUserData
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
        "-InstallRoot", "`"$InstallRoot`""
    )
    if ($PurgeUserData) { $arguments += "-PurgeUserData" }
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $arguments
    return
}

$resolvedInstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $env:ProgramFiles "Sense"))
if (-not $resolvedInstallRoot.Equals($expectedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The uninstall directory does not match the default Sense directory: $resolvedInstallRoot"
}

$x64 = Join-Path $InstallRoot "native\x64\SenseTsf.dll"
$x86 = Join-Path $InstallRoot "native\x86\SenseTsf.dll"
if (Test-Path -LiteralPath $x64) {
    & (Join-Path $env:WINDIR "System32\regsvr32.exe") /s /u $x64
}
$regsvr32 = Join-Path $env:WINDIR "SysWOW64\regsvr32.exe"
if ((Test-Path -LiteralPath $regsvr32) -and (Test-Path -LiteralPath $x86)) {
    & $regsvr32 /s /u $x86
}

Get-Process -Name "Sense.AgentHost","Sense.Settings" -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-ItemProperty `
    -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" `
    -Name "SenseAgentHost" `
    -ErrorAction SilentlyContinue

$startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\Sense"
if (Test-Path -LiteralPath $startMenu) {
    Remove-Item -LiteralPath $startMenu -Recurse -Force
}
if (Test-Path -LiteralPath $InstallRoot) {
    Remove-Item -LiteralPath $InstallRoot -Recurse -Force
}

if ($PurgeUserData) {
    $userData = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA "Sense"))
    $localRoot = [System.IO.Path]::GetFullPath($env:LOCALAPPDATA).TrimEnd('\') + '\'
    if ($userData.StartsWith($localRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $userData)) {
        Remove-Item -LiteralPath $userData -Recurse -Force
    }
}

Write-Host "Sense Windows uninstalled." -ForegroundColor Green
