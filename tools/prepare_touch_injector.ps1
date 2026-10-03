param([string]$Serial = 'emulator-5580')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Dedicated emulator required.' }
$avd = & $adb -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or $avd[0].Trim() -ne 'sense-input-quality') { throw 'Unexpected emulator.' }
$out = Join-Path $root 'build/input-quality-touch'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') --release 8 -cp $androidJar -d $out (Join-Path $PSScriptRoot 'android-fixture/TouchBurst.java')
if ($LASTEXITCODE -ne 0) { throw 'Touch fixture javac failed.' }
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp (Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/lib/d8.jar') com.android.tools.r8.D8 --min-api 29 --lib $androidJar --output $out (Join-Path $out 'sense/fixture/TouchBurst.class')
if ($LASTEXITCODE -ne 0) { throw 'Touch fixture D8 failed.' }
& $adb -s $Serial shell mkdir -p /data/local/tmp/sense-input-quality-touch
if ($LASTEXITCODE -ne 0) { throw 'Touch fixture directory preparation failed.' }
& $adb -s $Serial push (Join-Path $out 'classes.dex') /data/local/tmp/sense-input-quality-touch/classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Touch fixture transfer failed.' }
$localHash = (Get-FileHash (Join-Path $out 'classes.dex') -Algorithm SHA256).Hash.ToLowerInvariant()
$remoteHash = (& $adb -s $Serial shell sha256sum /data/local/tmp/sense-input-quality-touch/classes.dex) -split ' ' | Select-Object -First 1
if ($LASTEXITCODE -ne 0 -or $localHash -ne $remoteHash) { throw 'Touch fixture digest mismatch.' }
Write-Output "Shell touch fixture SHA-256: $localHash"
