[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [switch] $SkipTests,
    [switch] $Publish
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Publish -and ($SkipBuild -or $SkipTests)) {
    throw "-Publish requires a fresh local build and the complete local test gate."
}

$ReleaseTag = "v0.4.11"
$ReleaseApkName = "Sense-v0.4.11.apk"
$ReleaseTitle = "Sense v0.4.11 - Candidate memory and local association"
$ReleaseCertificateSha256 = "76db888ff42b04d52d4d19a573fe8f8df2fa3af0ab36bd6a08c6f70a8aace984"
$ExpectedVersionName = "0.4.11"
$ExpectedVersionCode = 36

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$AppBuildFile = Join-Path $RepoRoot "app\build.gradle.kts"
$GradleWrapper = Join-Path $RepoRoot "gradlew.bat"
$ReleaseNotes = Join-Path $RepoRoot "docs\releases\v0.4.11.md"
$BuiltApk = Join-Path $RepoRoot "app/build/outputs/apk/release/app-release.apk"
$ReleaseDirectory = Join-Path $RepoRoot "build\releases\$ReleaseTag"
$ReleaseApk = Join-Path $ReleaseDirectory $ReleaseApkName
$ChecksumsFile = Join-Path $ReleaseDirectory "SHA256SUMS.txt"

function Write-Step {
    param([Parameter(Mandatory = $true)][string] $Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [string[]] $ArgumentList = @(),
        [switch] $Quiet
    )

    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Quiet) {
            $output = @(& $FilePath @ArgumentList 2>&1)
        }
        else {
            $output = @(
                & $FilePath @ArgumentList 2>&1 |
                    ForEach-Object {
                        Write-Host $_
                        $_
                    }
            )
        }
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    if ($exitCode -ne 0) {
        $rendered = $output -join [Environment]::NewLine
        throw "Command failed ($exitCode): $FilePath $($ArgumentList -join ' ')`n$rendered"
    }
    return $output
}

function Get-OneGradleLiteral {
    param(
        [Parameter(Mandatory = $true)][string] $Text,
        [Parameter(Mandatory = $true)][string] $Pattern,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $matches = [regex]::Matches(
        $Text,
        $Pattern,
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if ($matches.Count -ne 1) {
        throw "$AppBuildFile must contain exactly one literal $Name; found $($matches.Count)."
    }
    return $matches[0].Groups[1].Value
}

function Convert-LocalPropertiesPath {
    param([Parameter(Mandatory = $true)][string] $Value)
    return $Value.Trim().Replace("\:", ":").Replace("\\", "\")
}

function Resolve-AndroidSdk {
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $candidates.Add($value)
        }
    }

    $localProperties = Join-Path $RepoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match "^\s*sdk\.dir\s*=" } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $rawValue = ($sdkLine -split "=", 2)[1]
            $candidates.Add((Convert-LocalPropertiesPath $rawValue))
        }
    }

    $candidates.Add("F:\Android\Sdk")
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $resolved = [System.IO.Path]::GetFullPath($candidate)
        if (Test-Path -LiteralPath (Join-Path $resolved "build-tools")) {
            return $resolved
        }
    }
    throw "Android SDK was not found in ANDROID_SDK_ROOT, ANDROID_HOME, local.properties, or F:\Android\Sdk."
}

function Resolve-BuildTools {
    param([Parameter(Mandatory = $true)][string] $AndroidSdk)

    $directories = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "build-tools") -Directory |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($directory in $directories) {
        $apksigner = Join-Path $directory.FullName "apksigner.bat"
        $zipalign = Join-Path $directory.FullName "zipalign.exe"
        $aapt2 = Join-Path $directory.FullName "aapt2.exe"
        if (
            (Test-Path -LiteralPath $apksigner) -and
            (Test-Path -LiteralPath $zipalign) -and
            (Test-Path -LiteralPath $aapt2)
        ) {
            return @{
                Directory = $directory.FullName
                Apksigner = $apksigner
                Zipalign = $zipalign
                Aapt2 = $aapt2
            }
        }
    }
    throw "No complete Android build-tools installation was found under $AndroidSdk."
}

function Resolve-ApkAnalyzer {
    param([Parameter(Mandatory = $true)][string] $AndroidSdk)

    $analyzers = @(
        Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "cmdline-tools") `
            -Recurse -File -Filter "apkanalyzer.bat" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending
    )
    if ($analyzers.Count -eq 0) {
        throw "apkanalyzer.bat was not found under $AndroidSdk\cmdline-tools."
    }
    return $analyzers[0].FullName
}

function Initialize-Java {
    if (
        -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and
        (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))
    ) {
        return
    }

    foreach ($candidate in @(
        "F:\Android\Jdk\jdk-17",
        "C:\Program Files\Android\Android Studio\jbr"
    )) {
        if (Test-Path -LiteralPath (Join-Path $candidate "bin\java.exe")) {
            $env:JAVA_HOME = $candidate
            return
        }
    }

    $java = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($null -eq $java) {
        throw "A Java 17 runtime was not found."
    }
}

function Test-PropertyFileContains {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Name
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    $escapedName = [regex]::Escape($Name)
    return $null -ne (
        Get-Content -LiteralPath $Path |
            Where-Object { $_ -match "^\s*$escapedName\s*=\s*\S" } |
            Select-Object -First 1
    )
}

function Initialize-ReleaseSigning {
    $requiredNames = @(
        "SENSE_RELEASE_STORE_FILE",
        "SENSE_RELEASE_STORE_PASSWORD",
        "SENSE_RELEASE_KEY_ALIAS",
        "SENSE_RELEASE_KEY_PASSWORD"
    )

    $allInEnvironment = $true
    foreach ($name in $requiredNames) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            $allInEnvironment = $false
        }
    }
    if ($allInEnvironment) {
        return
    }

    $propertyFiles = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        $propertyFiles.Add((Join-Path $env:GRADLE_USER_HOME "gradle.properties"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $propertyFiles.Add((Join-Path $env:USERPROFILE ".gradle\gradle.properties"))
    }
    $propertyFiles.Add("F:\Android\Gradle\gradle.properties")

    foreach ($propertyFile in $propertyFiles | Select-Object -Unique) {
        $hasEveryValue = $true
        foreach ($name in $requiredNames) {
            $fromEnvironment =
                -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))
            if (-not $fromEnvironment -and -not (Test-PropertyFileContains $propertyFile $name)) {
                $hasEveryValue = $false
            }
        }
        if ($hasEveryValue) {
            $env:GRADLE_USER_HOME = Split-Path -Parent $propertyFile
            return
        }
    }

    throw "The complete persistent SENSE_RELEASE_* signing configuration was not found."
}

function Resolve-Python {
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($name in @("python.exe", "python")) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            $candidates.Add($command.Source)
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $codexRuntimeRoot = Join-Path $env:USERPROFILE ".cache\codex-runtimes"
        if (Test-Path -LiteralPath $codexRuntimeRoot) {
            foreach ($candidate in @(
                Get-ChildItem -Path (
                    Join-Path $codexRuntimeRoot "*\dependencies\python\python.exe"
                ) -File -ErrorAction SilentlyContinue |
                    Sort-Object FullName -Descending
            )) {
                $candidates.Add($candidate.FullName)
            }
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $savedErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $candidate -c "import sys, tomllib; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" `
                *> $null
            $candidateExitCode = $LASTEXITCODE
        }
        catch {
            $candidateExitCode = 1
        }
        finally {
            $ErrorActionPreference = $savedErrorActionPreference
        }
        if ($candidateExitCode -eq 0) {
            return $candidate
        }
    }
    throw "Python 3.11+ with tomllib was not found."
}

function Invoke-Gradle {
    param([Parameter(Mandatory = $true)][string[]] $Tasks)
    $arguments = @("--console=plain") + $Tasks
    Invoke-Checked -FilePath $GradleWrapper -ArgumentList $arguments | Out-Null
}

function Invoke-LocalTests {
    param([Parameter(Mandatory = $true)][string] $Python)

    Write-Step "Run local Python verification"
    # Canonical commands include:
    # python tools/test_release_plan.py
    # python tools/test_check_x02_boundaries.py
    # python tools/check_x02_boundaries.py
    Invoke-Checked -FilePath $Python -ArgumentList @(
        (Join-Path $RepoRoot "tools\check_x02_boundaries.py")
    ) | Out-Null
    $pythonTests = Get-ChildItem -LiteralPath (Join-Path $RepoRoot "tools") `
        -File -Filter "test_*.py" |
        Sort-Object Name
    foreach ($test in $pythonTests) {
        Write-Host "python tools/$($test.Name)"
        Invoke-Checked -FilePath $Python -ArgumentList @($test.FullName) | Out-Null
    }
    Write-Host "python tools/verify_wubi86_assets.py"
    Invoke-Checked -FilePath $Python -ArgumentList @(
        (Join-Path $RepoRoot "tools\verify_wubi86_assets.py")
    ) | Out-Null

    Write-Step "Run JVM/Android unit tests, lint, and test APK assembly"
    Invoke-Gradle @(
        ":ai-protocol:test",
        ":brain-api:test",
        ":ai-brain:test",
        ":ai-runtime:testDebugUnitTest",
        ":ai-runtime:assembleDebugAndroidTest",
        ":memory-protocol:test",
        ":memory-protocol:jar",
        ":event-journal:test",
        ":event-journal:jar",
        ":core-input:test",
        ":ime-config:testDebugUnitTest",
        ":ime-service:testDebugUnitTest",
        ":ime-service:assembleDebugAndroidTest",
        ":ime-ui:testDebugUnitTest",
        ":ime-ui:assembleDebugAndroidTest",
        ":app:testDebugUnitTest",
        ":app:assembleDebugAndroidTest",
        ":ai-runtime:lintDebug",
        ":ime-config:lintDebug",
        ":ime-service:lintDebug",
        ":ime-ui:lintDebug",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleBenchmark",
        ":benchmark:assembleBenchmark"
    )

    Write-Step "Verify merged runtime components and IME network isolation"
    Invoke-Checked -FilePath $Python -ArgumentList @(
        (Join-Path $RepoRoot "tools/verify_runtime_boundaries.py")
    ) | Out-Null

    Write-Step "Run M0-M7 host performance gates"
    # Run the latency-sensitive M3 and M4 gates before the sustained host benchmarks so their
    # absolute budgets are measured before CPU thermal throttling can bias the local release gate.
    $m3Passes = 0
    foreach ($attempt in 1..3) {
        Write-Host "M3 benchmark attempt $attempt/3"
        try {
            Invoke-Checked -FilePath $GradleWrapper -ArgumentList @(
                "--console=plain",
                "--no-parallel",
                ":core-input:m3SentenceBenchmark"
            ) | Out-Null
            $m3Passes++
        }
        catch {
            Write-Host "M3 attempt $attempt failed: $($_.Exception.Message)" `
                -ForegroundColor Yellow
        }
    }
    if ($m3Passes -lt 2) {
        throw "M3 benchmark passed only $m3Passes/3 attempts."
    }

    Invoke-Checked -FilePath $GradleWrapper -ArgumentList @(
        "--console=plain",
        "--no-parallel",
        ":core-input:m4CoreBenchmark"
    ) | Out-Null

    Invoke-Checked -FilePath $GradleWrapper -ArgumentList @(
        "--console=plain",
        "--no-parallel",
        ":core-input:m0HostBenchmark",
        ":core-input:m1PinyinBenchmark",
        ":core-input:m2AdaptiveBenchmark"
    ) | Out-Null

    Invoke-Checked -FilePath $GradleWrapper -ArgumentList @(
        "--console=plain",
        "--no-parallel",
        ":core-input:m5MixedInputBenchmark",
        ":core-input:m6InputPolishBenchmark",
        ":core-input:m7ChineseSchemeBenchmark"
    ) | Out-Null
}

function Assert-RegexValue {
    param(
        [Parameter(Mandatory = $true)][string] $Text,
        [Parameter(Mandatory = $true)][string] $Pattern,
        [Parameter(Mandatory = $true)][string] $Expected,
        [Parameter(Mandatory = $true)][string] $Label
    )
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        throw "APK metadata does not contain $Label."
    }
    $actual = $match.Groups[1].Value
    if ($actual -ne $Expected) {
        throw "APK $Label mismatch: expected '$Expected', got '$actual'."
    }
}

function Test-ReleaseApk {
    param(
        [Parameter(Mandatory = $true)][string] $Apk,
        [Parameter(Mandatory = $true)][hashtable] $BuildTools,
        [Parameter(Mandatory = $true)][string] $ApkAnalyzer,
        [Parameter(Mandatory = $true)][string] $Python,
        [Parameter(Mandatory = $true)][string] $PackageName,
        [Parameter(Mandatory = $true)][string] $VersionCode,
        [Parameter(Mandatory = $true)][string] $VersionName,
        [Parameter(Mandatory = $true)][string] $MinSdk,
        [Parameter(Mandatory = $true)][string] $TargetSdk
    )

    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "Release APK is missing: $Apk"
    }

    Write-Step "Verify APK signature, signer identity, alignment, and package metadata"
    $signatureOutput = (
        Invoke-Checked -FilePath $BuildTools.Apksigner -ArgumentList @(
            "verify", "--verbose", "--print-certs", $Apk
        ) -Quiet
    ) -join "`n"
    Write-Host $signatureOutput

    Assert-RegexValue $signatureOutput "(?m)^Number of signers:\s*(\d+)\s*$" "1" "signer count"
    $certificateDigests = [regex]::Matches(
        $signatureOutput,
        "(?im)^.*certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$"
    )
    if ($certificateDigests.Count -ne 1) {
        throw "APK signature output must contain exactly one certificate SHA-256 digest."
    }
    $certificateDigest = $certificateDigests[0].Groups[1].Value.ToLowerInvariant()
    if ($certificateDigest -ne $ReleaseCertificateSha256) {
        throw (
            "APK signer certificate SHA-256 mismatch: expected " +
            "$ReleaseCertificateSha256, got $certificateDigest."
        )
    }

    Invoke-Checked -FilePath $BuildTools.Zipalign -ArgumentList @(
        "-c", "-P", "16", "4", $Apk
    ) | Out-Null

    $badging = (
        Invoke-Checked -FilePath $BuildTools.Aapt2 -ArgumentList @(
            "dump", "badging", $Apk
        ) -Quiet
    ) -join "`n"
    Assert-RegexValue $badging "(?m)^package:\s+name='([^']+)'" $PackageName "package name"
    Assert-RegexValue $badging "(?m)^package:.*\bversionCode='([^']+)'" $VersionCode "versionCode"
    Assert-RegexValue $badging "(?m)^package:.*\bversionName='([^']+)'" $VersionName "versionName"
    Assert-RegexValue $badging "(?m)^minSdkVersion:'([^']+)'\s*$" $MinSdk "minSdk"
    Assert-RegexValue $badging "(?m)^targetSdkVersion:'([^']+)'\s*$" $TargetSdk "targetSdk"

    $validationDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
        "sense-apk-validation-" + [guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $validationDirectory | Out-Null
    try {
        $permissionsPath = Join-Path $validationDirectory "permissions.txt"
        $xmlTreePath = Join-Path $validationDirectory "manifest.xmltree.txt"
        $manifestPath = Join-Path $validationDirectory "AndroidManifest.xml"

        $permissions = Invoke-Checked -FilePath $BuildTools.Aapt2 -ArgumentList @(
            "dump", "permissions", $Apk
        ) -Quiet
        [System.IO.File]::WriteAllLines($permissionsPath, [string[]]$permissions)

        $xmlTree = Invoke-Checked -FilePath $BuildTools.Aapt2 -ArgumentList @(
            "dump", "xmltree", $Apk, "--file", "AndroidManifest.xml"
        ) -Quiet
        [System.IO.File]::WriteAllLines($xmlTreePath, [string[]]$xmlTree)

        # Equivalent to:
        # python tools/verify_aapt2_manifest_protection.py --permissions ... ...
        Invoke-Checked -FilePath $Python -ArgumentList @(
            (Join-Path $RepoRoot "tools\verify_aapt2_manifest_protection.py"),
            "--permissions", $permissionsPath, $xmlTreePath
        ) | Out-Null

        $manifest = Invoke-Checked -FilePath $ApkAnalyzer -ArgumentList @(
            "manifest", "print", $Apk
        ) -Quiet
        [System.IO.File]::WriteAllLines($manifestPath, [string[]]$manifest)

        # Equivalent to: python tools/verify_manifest_permissions.py --packaged ...
        Invoke-Checked -FilePath $Python -ArgumentList @(
            (Join-Path $RepoRoot "tools\verify_manifest_permissions.py"),
            "--packaged", $manifestPath
        ) | Out-Null

        # Rebuild the pinned source and compare the APK's SWBX/1 plus both
        # LGPL attribution assets byte-for-byte with their reviewed copies.
        Invoke-Checked -FilePath $Python -ArgumentList @(
            (Join-Path $RepoRoot "tools\verify_wubi86_assets.py"),
            "--apk", $Apk
        ) | Out-Null
    }
    finally {
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedValidationDirectory = [System.IO.Path]::GetFullPath($validationDirectory)
        if ($resolvedValidationDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedValidationDirectory -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-RemoteTagTarget {
    param(
        [Parameter(Mandatory = $true)][string] $Git,
        [Parameter(Mandatory = $true)][string] $Tag
    )
    $tagRef = "refs/tags/$Tag"
    $peeledRef = "$tagRef^{}"
    $lines = @(Invoke-Checked -FilePath $Git -ArgumentList @(
        "ls-remote", "--tags", "origin", $tagRef, $peeledRef
    ) -Quiet)
    if ($lines.Count -eq 0) {
        return "MISSING"
    }

    $baseTarget = $null
    $peeledTarget = $null
    foreach ($line in $lines) {
        if ([string]$line -notmatch "^([0-9a-f]{40,64})\s+(.+)$") {
            throw "Unexpected git ls-remote output: $line"
        }
        if ($Matches[2] -eq $peeledRef) {
            $peeledTarget = $Matches[1]
        }
        elseif ($Matches[2] -eq $tagRef) {
            $baseTarget = $Matches[1]
        }
    }
    if ($null -ne $peeledTarget) {
        return $peeledTarget
    }
    if ($null -ne $baseTarget) {
        return $baseTarget
    }
    throw "Remote tag lookup returned no usable target for $Tag."
}

function Assert-PublishSourceState {
    param(
        [Parameter(Mandatory = $true)][string] $Git,
        [Parameter(Mandatory = $true)][string] $Head
    )

    $status = @(
        Invoke-Checked -FilePath $Git -ArgumentList @(
            "status", "--porcelain=v1", "--untracked-files=all"
        ) -Quiet
    )
    if ($status.Count -ne 0) {
        throw (
            "Publishing requires a clean worktree. Pending paths:`n" +
            ($status -join [Environment]::NewLine)
        )
    }

    $mainRef = @(
        Invoke-Checked -FilePath $Git -ArgumentList @(
            "ls-remote", "--heads", "origin", "refs/heads/main"
        ) -Quiet
    )
    if (
        $mainRef.Count -ne 1 -or
        [string]$mainRef[0] -notmatch "^([0-9a-f]{40,64})\s+refs/heads/main$"
    ) {
        throw "origin/main lookup returned an unexpected result."
    }
    if ($Matches[1].ToLowerInvariant() -ne $Head) {
        throw "origin/main must point to the local release HEAD $Head."
    }
}

function Restore-PublishBenchmarkResults {
    param([Parameter(Mandatory = $true)][string] $Git)

    Invoke-Checked -FilePath $Git -ArgumentList @(
        "restore",
        "--worktree",
        "--source=HEAD",
        "--",
        "benchmarks/results/m0-host.json",
        "benchmarks/results/m1-pinyin.json",
        "benchmarks/results/m2-adaptive.json",
        "benchmarks/results/m3-sentence.json",
        "benchmarks/results/m4-core.json",
        "benchmarks/results/m5-mixed-input.json",
        "benchmarks/results/m6-input-polish.json",
        "benchmarks/results/m7-chinese-schemes.json"
    ) | Out-Null
}

function Get-OriginNameWithOwner {
    param([Parameter(Mandatory = $true)][string] $Git)

    $originUrl = @(
        Invoke-Checked -FilePath $Git -ArgumentList @(
            "remote", "get-url", "origin"
        ) -Quiet
    )[0].Trim()
    $match = [regex]::Match(
        $originUrl,
        "^(?:https://github\.com/|ssh://git@github\.com/|git@github\.com:)" +
        "([^/:\s]+)/([^/\s]+?)(?:\.git)?/?$",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    if (-not $match.Success) {
        throw "origin must be a github.com owner/repository remote."
    }
    return "$($match.Groups[1].Value)/$($match.Groups[2].Value)"
}

function Publish-Release {
    param(
        [Parameter(Mandatory = $true)][string] $Python,
        [Parameter(Mandatory = $true)][hashtable] $BuildTools,
        [Parameter(Mandatory = $true)][string] $ApkAnalyzer,
        [Parameter(Mandatory = $true)][string] $PackageName,
        [Parameter(Mandatory = $true)][string] $VersionCode,
        [Parameter(Mandatory = $true)][string] $VersionName,
        [Parameter(Mandatory = $true)][string] $MinSdk,
        [Parameter(Mandatory = $true)][string] $TargetSdk,
        [Parameter(Mandatory = $true)][string] $LocalSha256
    )

    $gitCommand = Get-Command "git.exe" -ErrorAction Stop
    $ghCommand = Get-Command "gh.exe" -ErrorAction Stop
    $git = $gitCommand.Source
    $gh = $ghCommand.Source

    # Canonical planner command: python tools/release_plan.py
    Write-Step "Plan GitHub release for the current HEAD"
    $head = @(
        Invoke-Checked -FilePath $git -ArgumentList @("rev-parse", "HEAD") -Quiet
    )[0].Trim().ToLowerInvariant()
    if ($head -notmatch "^[0-9a-f]{40,64}$") {
        throw "git rev-parse returned an invalid HEAD: $head"
    }
    Assert-PublishSourceState -Git $git -Head $head

    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $localTagProbe = @(
            & $git rev-parse --verify --quiet "refs/tags/$ReleaseTag^{commit}" 2>$null
        )
        $localTagExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    if ($localTagExitCode -eq 0) {
        $localTarget = $localTagProbe[0].Trim().ToLowerInvariant()
        if ($localTarget -ne $head) {
            throw "Local tag $ReleaseTag targets $localTarget instead of current HEAD $head."
        }
    }

    $remoteTarget = Get-RemoteTagTarget -Git $git -Tag $ReleaseTag
    if ($remoteTarget -ne "MISSING" -and $remoteTarget -ne $head) {
        throw "Remote tag $ReleaseTag targets $remoteTarget instead of current HEAD $head."
    }

    $previousBuildFile = Join-Path ([System.IO.Path]::GetTempPath()) (
        "sense-previous-build-" + [guid]::NewGuid().ToString("N") + ".gradle.kts"
    )
    try {
        $previousText = Invoke-Checked -FilePath $git -ArgumentList @(
            "show", "HEAD^:app/build.gradle.kts"
        ) -Quiet
        [System.IO.File]::WriteAllLines($previousBuildFile, [string[]]$previousText)

        $planJson = (
            Invoke-Checked -FilePath $Python -ArgumentList @(
                (Join-Path $RepoRoot "tools\release_plan.py"),
                "--previous", $previousBuildFile,
                "--current", $AppBuildFile,
                "--release-tag", $ReleaseTag,
                "--release-apk", $ReleaseApkName,
                "--current-sha", $head,
                "--tag-target", $remoteTarget
            ) -Quiet
        ) -join "`n"
        $plan = $planJson | ConvertFrom-Json
        if (-not [bool]$plan.should_release) {
            throw "Local release plan stopped publishing: $($plan.status)."
        }
        Write-Host "Release plan: $($plan.status)"
    }
    finally {
        Remove-Item -LiteralPath $previousBuildFile -Force -ErrorAction SilentlyContinue
    }

    $repo = @(
        Invoke-Checked -FilePath $gh -ArgumentList @(
            "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"
        ) -Quiet
    )[0].Trim()
    if ($repo -notmatch "^[^/]+/[^/]+$") {
        throw "gh repo view returned an invalid repository: $repo"
    }
    $originRepo = Get-OriginNameWithOwner -Git $git
    if (-not $originRepo.Equals($repo, [StringComparison]::OrdinalIgnoreCase)) {
        throw "git origin ($originRepo) and gh repository ($repo) must match."
    }

    $remoteHead = @(
        Invoke-Checked -FilePath $gh -ArgumentList @(
            "api", "repos/$repo/commits/$head", "--jq", ".sha"
        ) -Quiet
    )[0].Trim().ToLowerInvariant()
    if ($remoteHead -ne $head) {
        throw "Current HEAD $head is not available in $repo."
    }

    $notesArguments = @()
    if (Test-Path -LiteralPath $ReleaseNotes) {
        $notesArguments = @("--notes-file", $ReleaseNotes)
    }
    else {
        $notesArguments = @("--notes", "Sense $ExpectedVersionName locally verified release.")
    }

    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $gh release view $ReleaseTag --repo $repo *> $null
        $releaseExists = $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    if ($releaseExists) {
        Invoke-Checked -FilePath $gh -ArgumentList (
            @(
                "release", "edit", $ReleaseTag,
                "--repo", $repo,
                "--target", $head,
                "--draft=false",
                "--prerelease=false",
                "--title", $ReleaseTitle
            ) + $notesArguments
        ) | Out-Null
    }
    elseif ($remoteTarget -eq "MISSING") {
        Invoke-Checked -FilePath $gh -ArgumentList (
            @(
                "release", "create", $ReleaseTag,
                "--repo", $repo,
                "--target", $head,
                "--title", $ReleaseTitle
            ) + $notesArguments
        ) | Out-Null
    }
    else {
        Invoke-Checked -FilePath $gh -ArgumentList (
            @(
                "release", "create", $ReleaseTag,
                "--repo", $repo,
                "--verify-tag",
                "--title", $ReleaseTitle
            ) + $notesArguments
        ) | Out-Null
    }

    Invoke-Checked -FilePath $gh -ArgumentList @(
        "release", "upload", $ReleaseTag,
        $ReleaseApk, $ChecksumsFile,
        "--repo", $repo,
        "--clobber"
    ) | Out-Null

    $publishedTarget = Get-RemoteTagTarget -Git $git -Tag $ReleaseTag
    if ($publishedTarget -ne $head) {
        throw "Published tag target mismatch: expected $head, got $publishedTarget."
    }

    $releaseJson = (
        Invoke-Checked -FilePath $gh -ArgumentList @(
            "release", "view", $ReleaseTag,
            "--repo", $repo,
            "--json", "tagName,isPrerelease,isDraft,url"
        ) -Quiet
    ) -join "`n"
    $release = $releaseJson | ConvertFrom-Json
    if (
        $release.tagName -ne $ReleaseTag -or
        [bool]$release.isPrerelease -or
        [bool]$release.isDraft
    ) {
        throw "GitHub release metadata does not describe the expected stable release."
    }

    Write-Step "Download the published assets and verify them again"
    $downloadDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
        "sense-release-download-" + [guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $downloadDirectory | Out-Null
    try {
        Invoke-Checked -FilePath $gh -ArgumentList @(
            "release", "download", $ReleaseTag,
            "--repo", $repo,
            "--pattern", $ReleaseApkName,
            "--pattern", "SHA256SUMS.txt",
            "--dir", $downloadDirectory,
            "--clobber"
        ) | Out-Null

        $downloadedApk = Join-Path $downloadDirectory $ReleaseApkName
        $downloadedChecksums = Join-Path $downloadDirectory "SHA256SUMS.txt"
        $downloadedSha256 = (Get-FileHash -LiteralPath $downloadedApk -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($downloadedSha256 -ne $LocalSha256) {
            throw "Downloaded APK SHA-256 mismatch: expected $LocalSha256, got $downloadedSha256."
        }

        $checksumPattern = "^([0-9a-fA-F]{64})\s+\*?$([regex]::Escape($ReleaseApkName))$"
        $checksumMatch = Get-Content -LiteralPath $downloadedChecksums |
            Where-Object { $_ -match $checksumPattern } |
            Select-Object -First 1
        if ($null -eq $checksumMatch) {
            throw "Downloaded SHA256SUMS.txt has no entry for $ReleaseApkName."
        }
        [void]($checksumMatch -match $checksumPattern)
        if ($Matches[1].ToLowerInvariant() -ne $downloadedSha256) {
            throw "Downloaded SHA256SUMS.txt does not match the downloaded APK."
        }

        Test-ReleaseApk `
            -Apk $downloadedApk `
            -BuildTools $BuildTools `
            -ApkAnalyzer $ApkAnalyzer `
            -Python $Python `
            -PackageName $PackageName `
            -VersionCode $VersionCode `
            -VersionName $VersionName `
            -MinSdk $MinSdk `
            -TargetSdk $TargetSdk
    }
    finally {
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        $resolvedDownloadDirectory = [System.IO.Path]::GetFullPath($downloadDirectory)
        if ($resolvedDownloadDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedDownloadDirectory -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Assert-PublishSourceState -Git $git -Head $head
    Write-Host "GitHub release: $($release.url)" -ForegroundColor Green
}

Push-Location $RepoRoot
try {
    if (-not (Test-Path -LiteralPath $AppBuildFile -PathType Leaf)) {
        throw "Missing Android application build file: $AppBuildFile"
    }
    if (-not (Test-Path -LiteralPath $GradleWrapper -PathType Leaf)) {
        throw "Missing Gradle wrapper: $GradleWrapper"
    }

    $buildText = Get-Content -LiteralPath $AppBuildFile -Raw
    $versionName = Get-OneGradleLiteral $buildText '^\s*versionName\s*=\s*"([^"]+)"\s*$' "versionName"
    $versionCode = Get-OneGradleLiteral $buildText "^\s*versionCode\s*=\s*(\d+)\s*$" "versionCode"
    $packageName = Get-OneGradleLiteral $buildText '^\s*applicationId\s*=\s*"([^"]+)"\s*$' "applicationId"
    $minSdk = Get-OneGradleLiteral $buildText "^\s*minSdk\s*=\s*(\d+)\s*$" "minSdk"
    $targetSdk = Get-OneGradleLiteral $buildText "^\s*targetSdk\s*=\s*(\d+)\s*$" "targetSdk"

    if ($versionName -ne $ExpectedVersionName) {
        throw "Release version mismatch: expected $ExpectedVersionName, got $versionName."
    }
    if ($versionCode -ne $ExpectedVersionCode) {
        throw "Release versionCode mismatch: expected $ExpectedVersionCode, got $versionCode."
    }
    if ($ReleaseTag -ne "v$versionName" -or $ReleaseApkName -ne "Sense-v$versionName.apk") {
        throw "Release tag/APK constants do not match app/build.gradle.kts."
    }

    Initialize-Java
    Initialize-ReleaseSigning
    $androidSdk = Resolve-AndroidSdk
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
    $buildTools = Resolve-BuildTools $androidSdk
    $apkAnalyzer = Resolve-ApkAnalyzer $androidSdk
    $python = Resolve-Python
    $publishGit = $null
    $publishHead = $null

    if ($Publish) {
        $publishGit = (Get-Command "git.exe" -ErrorAction Stop).Source
        $publishHead = @(
            Invoke-Checked -FilePath $publishGit -ArgumentList @(
                "rev-parse", "HEAD"
            ) -Quiet
        )[0].Trim().ToLowerInvariant()
        Assert-PublishSourceState -Git $publishGit -Head $publishHead
    }

    Write-Host "Sense local release $ReleaseTag"
    Write-Host "Package: $packageName"
    Write-Host "Version: $versionName ($versionCode), SDK $minSdk-$targetSdk"
    Write-Host "Android SDK: $androidSdk"
    Write-Host "Build tools: $($buildTools.Directory)"

    if (-not $SkipTests) {
        Invoke-LocalTests $python
    }
    else {
        Write-Host "Local tests skipped by -SkipTests."
    }

    if (-not $SkipBuild) {
        Write-Step "Build the persistently signed release APK"
        Invoke-Gradle @(":app:assembleRelease")
    }
    else {
        Write-Host "Release build skipped by -SkipBuild; the existing APK will be verified."
    }

    if (-not (Test-Path -LiteralPath $BuiltApk -PathType Leaf)) {
        throw "Expected Gradle output is missing: $BuiltApk"
    }

    if (-not $SkipTests) {
        Write-Step "Verify locally built X-02 artifacts"
        Invoke-Checked -FilePath $python -ArgumentList @(
            (Join-Path $RepoRoot "tools\check_x02_boundaries.py"),
            "--check-artifacts"
        ) | Out-Null
    }

    if ($Publish) {
        Restore-PublishBenchmarkResults -Git $publishGit
        Assert-PublishSourceState -Git $publishGit -Head $publishHead
    }

    New-Item -ItemType Directory -Path $ReleaseDirectory -Force | Out-Null
    Copy-Item -LiteralPath $BuiltApk -Destination $ReleaseApk -Force

    Test-ReleaseApk `
        -Apk $ReleaseApk `
        -BuildTools $buildTools `
        -ApkAnalyzer $apkAnalyzer `
        -Python $python `
        -PackageName $packageName `
        -VersionCode $versionCode `
        -VersionName $versionName `
        -MinSdk $minSdk `
        -TargetSdk $targetSdk

    $sha256 = (Get-FileHash -LiteralPath $ReleaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText(
        $ChecksumsFile,
        "$sha256  $ReleaseApkName`n",
        [System.Text.Encoding]::ASCII
    )
    Write-Host "APK: $ReleaseApk" -ForegroundColor Green
    Write-Host "SHA-256: $sha256" -ForegroundColor Green
    Write-Host "Signer SHA-256: $ReleaseCertificateSha256" -ForegroundColor Green

    if ($Publish) {
        Publish-Release `
            -Python $python `
            -BuildTools $buildTools `
            -ApkAnalyzer $apkAnalyzer `
            -PackageName $packageName `
            -VersionCode $versionCode `
            -VersionName $versionName `
            -MinSdk $minSdk `
            -TargetSdk $targetSdk `
            -LocalSha256 $sha256
    }
}
finally {
    Pop-Location
}
