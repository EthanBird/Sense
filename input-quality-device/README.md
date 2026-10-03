# System IME / external editor acceptance

This is a separate, unprivileged Android editor fixture application. The production app has no
dependency on it and no test-specific control endpoint. Tests select the installed Sense **debug**
IME and inject real touchscreen events into its actual window. Assertions observe the fixture's
ordinary EditText/InputConnection, not a direct call into the decoder or keyboard listener.

On a dedicated booted emulator with default 26-key full pinyin:

```powershell
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
$env:ANDROID_HOME = 'F:\Android\Sdk'
./tools/test_external_editor.ps1 -Serial emulator-5580 -RunName d1-first
```

The default runner builds/installs three debug APKs, records their SHA-256, runs ten tests, retrieves
screenshots/XML/timings and checks the instrumentation transcript (shell exit code alone is not
a passing test). Evidence directories are unique and never overwritten. Settings changed by the
fixture (default IME and show-soft-keyboard-with-hardware) are restored in teardown.

The IME's existing Canvas does not expose virtual per-key accessibility nodes. The fixture uses
the current window bounds and known QWERTY geometry to touch keys, then checks actual editor text.
This validates routing and layout stability, not TalkBack accessibility or every screen configuration.

Coverage: candidate click, rapid typing + immediate Space, raw-English Enter, pending Backspace,
field switch, external selection move, hide/reopen, password literal input and Chinese-mode restore.
Test strings in screenshots are synthetic fixtures, not user input.

Readiness is the **current service dump**, including production generation and model READY state;
historical model-loaded logs are not enough. All default tests intentionally wait for the production
runtime. Cold-start confirmation before that runtime is ready is an explicit separate regression,
not covered or fixed by waiting here. Timing files report actual injected key cadence and a single
Space-to-editor observation, not decoder p95 or phone latency. Add a dedicated distribution test
before setting Android performance thresholds.

## Event-source calibration (API 37 dedicated emulator)

`-InjectionCadence` compares two event sources against the same installed debug APK. It builds a
standalone shell DEX with `tools/prepare_touch_injector.ps1`, verifies its local/device digest,
and checks each raw prefix and the final external text. No helper is linked into the shipping APK.
The device must be the `sense-input-quality` AVD; the helper targets only the installed Sense UID.
Do not interpret sender cadence as keyboard rendering latency or compare different versions using
this calibration alone. See the E24 report for failures retained and actual measurements.

## Fixed-APK fast-input comparison

`tools/measure_fast_input_latency.py PROTOCOL_LOCK NEW_EVIDENCE_DIRECTORY` installs the independent
fixture and executes `ExternalEditorFastLatencyTest` in the locked ABBA order. It checks the frozen
product APKs, production sources and preinstalled E24 shell helper before running. Build this
module's `assembleDebug` and `assembleDebugAndroidTest` tasks first. Only the dedicated emulator's
Sense DEBUG profile is cleared. The development APK and original IME are restored afterward.
Each block checks 16 confirmations and two immediate-followup sequences; every confirmation's raw
prefixes, sender events and external editor observations are retained. The summary rejects partial
blocks rather than selecting successful inputs. See E25 for the frozen rule and measured results.
