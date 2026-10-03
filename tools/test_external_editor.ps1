param(
    [string]$Serial = "emulator-5580",
    [string]$RunName = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$ColdStart,
    # Clears only the disposable AVD's Sense debug profile before the learning scenarios.
    [switch]$Learning,
    [switch]$Correction,
    [switch]$Association,
    [switch]$Boundary,
    [switch]$Mixed,
    [switch]$Completion,
    [switch]$BoundedCompletion,
    [switch]$InjectionCadence,
    [switch]$Context
)
$ErrorActionPreference = "Stop"
# Dedicated emulator acceptance; never silently select a user's phone.
if ($Serial -notmatch '^emulator-[0-9]+$') { throw "Use a dedicated emulator serial for this fixture." }
if ($RunName -notmatch '^[a-zA-Z0-9_-]+$') { throw "RunName must be a simple artifact directory name." }
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$out = Join-Path $root ".artifacts\input-quality\external-editor-$RunName"
if (Test-Path -LiteralPath $out) { throw "Evidence directory already exists: $out" }
New-Item -ItemType Directory -Path $out | Out-Null
function Invoke-Adb([string[]]$Arguments) {
    $value = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $value" }
    return $value
}
if ((@($ColdStart, $Learning, $Correction, $Association, $Boundary, $Mixed, $Completion, $BoundedCompletion, $InjectionCadence, $Context) | Where-Object { $_ }).Count -gt 1) { throw "Select only one specialized scenario group." }
if ($ColdStart -or $Learning -or $Correction -or $Association -or $Boundary -or $Mixed -or $Completion -or $BoundedCompletion -or $InjectionCadence -or $Context) {
    $avd = ((Invoke-Adb @("emu", "avd", "name"))[0]).Trim()
    if ($avd -ne "sense-input-quality") { throw "Lifecycle/learning fixtures target only the dedicated sense-input-quality AVD's debug IME." }
}
Push-Location $root
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat --offline --no-parallel --console=plain :app:assembleDebug :input-quality-device:assembleDebug :input-quality-device:assembleDebugAndroidTest *> (Join-Path $out "build.log")
        if ($LASTEXITCODE -ne 0) { throw "Build failed; see $out\build.log" }
    }
    $apks = @(
        "app/build/outputs/apk/debug/app-debug.apk",
        "input-quality-device/build/outputs/apk/debug/input-quality-device-debug.apk",
        "input-quality-device/build/outputs/apk/androidTest/debug/input-quality-device-debug-androidTest.apk"
    )
    $hashes = @{}
    $packages = @("io.github.ethanbird.senseime.debug", "io.github.ethanbird.senseime.inputqualityfixture", "io.github.ethanbird.senseime.inputqualityfixture.test")
    $apkIndex = 0
    foreach ($apk in $apks) {
        $hashes[$apk] = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($SkipInstall) {
            # Keep the process/cache alive across lifecycle rounds, but never test an unknown APK.
            $paths = @(Invoke-Adb @("shell", "pm", "path", $packages[$apkIndex]))
            if ($paths.Count -ne 1 -or $paths[0] -notmatch '^package:(.+)$') { throw "Expected one installed APK for $($packages[$apkIndex])" }
            $remoteHash = ((Invoke-Adb @("shell", "sha256sum", $Matches[1])) -join "").Split(' ')[0]
            if ($remoteHash -ne $hashes[$apk]) { throw "Installed APK does not match $apk; run without SkipInstall." }
        } else {
            Invoke-Adb @("install", "-r", $apk) | Out-Host
        }
        $apkIndex += 1
    }
    if ($Learning) {
        $cleared = (Invoke-Adb @("shell", "pm", "clear", "io.github.ethanbird.senseime.debug")) -join ""
        if ($cleared.Trim() -ne "Success") { throw "Disposable debug profile was not reset." }
    }
    $metadata = @{
        serial = $Serial
        sdk = ((Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")) -join "").Trim()
        scope = $(if ($Learning) { "Real candidate selection, reuse, privacy and process-restart persistence; clean disposable debug profile; not phone certification" } elseif ($ColdStart) { "Real system IME touch -> external EditText; force-stopped debug app, loading runtime asserted; not phone certification" } else { "Real system IME touch -> external EditText; warm production runtime; not cold-start or phone certification" })
        debugProfileReset = [bool]$Learning
        apks = $hashes
        installedDuringRun = -not $SkipInstall
        imePidBefore = ((Invoke-Adb @("shell", 'pidof io.github.ethanbird.senseime.debug:ime || true')) -join "").Trim()
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $out "environment.json")
    $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorInputTest"
    $testArgs = @("shell", "am", "instrument", "-w", "-r", "-e", "evidenceRun", $RunName)
    $expectedTests = 10
    if ($Learning) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorLearningTest"
        $expectedTests = 4
    }
    if ($Correction) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorCorrectionTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 8
    }
    if ($Association) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorAssociationTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 9
    }
    if ($Boundary) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorBoundaryTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 6
    }
    if ($Mixed) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorMixedRecallTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 3
    }
    if ($Completion) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorCompletionTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 10
    }
    if ($BoundedCompletion) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorBoundedCompletionTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 4
    }
    if ($Context) {
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorContextTest"
        $testArgs += @("-e", "noLearning", "true")
        $expectedTests = 8
    }
    if ($InjectionCadence) {
        & (Join-Path $PSScriptRoot 'prepare_touch_injector.ps1') -Serial $Serial | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Shell touch fixture preparation failed.' }
        $helperSha = (Get-FileHash (Join-Path $root 'build/input-quality-touch/classes.dex') -Algorithm SHA256).Hash.ToLowerInvariant()
        $metadata.touchInjectorSha256 = $helperSha
        $metadata.touchInjectorSourceSha256 = (Get-FileHash (Join-Path $PSScriptRoot 'android-fixture/TouchBurst.java') -Algorithm SHA256).Hash.ToLowerInvariant()
        $class = "io.github.ethanbird.senseime.inputqualityfixture.ExternalEditorInjectionCadenceTest"
        $testArgs += @("-e", "noLearning", "true", "-e", "touchInjectorSha256", $helperSha)
        $expectedTests = 1
    }
    if ($ColdStart) {
        $testArgs += @("-e", "coldStart", "true", "-e", "noLearning", "true")
        $methods = @("rapidTypingAndImmediateSpaceCommitTheLatestSentenceOnce", "confirmationReplaysFollowingInputInOrder",
            "switchingEditorsDoesNotCommitAnOldCandidateIntoTheNewField", "enterCommitsRawEnglishInChineseModeWithoutANewline")
        $class = ($methods | ForEach-Object { "$class#$_" }) -join ","
        $expectedTests = $methods.Count
    }
    $testArgs += @("-e", "class", $class, "io.github.ethanbird.senseime.inputqualityfixture.test/androidx.test.runner.AndroidJUnitRunner")
    $result = Invoke-Adb $testArgs
    $metadata.imePidAfter = ((Invoke-Adb @("shell", 'pidof io.github.ethanbird.senseime.debug:ime || true')) -join "").Trim()
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $out "environment.json")
    $result | Set-Content -Encoding utf8 (Join-Path $out "instrumentation.log")
    Invoke-Adb @("pull", "/sdcard/Android/data/io.github.ethanbird.senseime.inputqualityfixture/files/input-quality/$RunName", (Join-Path $out "device")) | Out-Host
    # am instrument commonly returns shell exit 0 even when assertions fail.
    $transcript = $result -join "`n"
    if ($transcript -notmatch "OK \($expectedTests tests?\)" -or $transcript -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "External-editor acceptance failed; see $out\instrumentation.log"
    }
    Write-Host "External-editor acceptance passed: $out"
} finally {
    Pop-Location
}
