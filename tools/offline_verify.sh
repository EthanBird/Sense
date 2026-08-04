#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
GRADLE_DIST=${SENSE_GRADLE_HOME:-${GRADLE_HOME:-}}
BUILD_TOOLS_VERSION=${SENSE_BUILD_TOOLS_VERSION:-36.0.0}

if [[ -z "$SDK" || ! -d "$SDK/platforms/android-36" ]]; then
    echo "Set ANDROID_SDK_ROOT to an SDK containing Android API 36." >&2
    exit 2
fi
if [[ -z "$GRADLE_DIST" || ! -d "$GRADLE_DIST/lib" ]]; then
    echo "Set SENSE_GRADLE_HOME to an unpacked Gradle 8.13 distribution." >&2
    exit 2
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
    JAR_TOOL="$JAVA_HOME/bin/jar"
else
    JAR_TOOL=$(command -v jar || true)
fi
if [[ -z "$JAR_TOOL" ]]; then
    echo "A JDK jar tool is required for packaged boundary verification." >&2
    exit 2
fi

BUILD_TOOLS="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
KOTLIN_LIB="$GRADLE_DIST/lib"
APP_BUILD_FILE="$ROOT/app/build.gradle.kts"
VERSION_NAME=$(sed -n -E 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)"[[:space:]]*$/\1/p' "$APP_BUILD_FILE")
VERSION_CODE=$(sed -n -E 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' "$APP_BUILD_FILE")
if [[ -z "$VERSION_NAME" || "$VERSION_NAME" == *$'\n'* ]]; then
    echo "app/build.gradle.kts must contain exactly one literal versionName." >&2
    exit 2
fi
if [[ -z "$VERSION_CODE" || "$VERSION_CODE" == *$'\n'* ]]; then
    echo "app/build.gradle.kts must contain exactly one literal versionCode." >&2
    exit 2
fi
OUT="$ROOT/build/offline-$VERSION_NAME"
MEMORY_PROTOCOL_JAR="$OUT/memory-protocol-main.jar"
EVENT_JOURNAL_JAR="$OUT/event-journal-main.jar"
APK_DIR="$ROOT/app/build/outputs/apk/offline"
APK="$APK_DIR/Sense-v$VERSION_NAME-offline-debug.apk"
LEXICON_ASSET="$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin"
LEXICON_SHA256="71258c3d1b4cade8693a13564ead0217a7e92068bbe554ecc806ae0f3a08e800"
LEXICON_MANIFEST="$ROOT/ime-service/src/main/lexicon/sources.json"
LEXICON_STATS="$ROOT/ime-service/src/main/lexicon/pinyin_lexicon.stats.json"
SYLLABLES_ASSET="$ROOT/ime-service/src/main/assets/pinyin_syllables.txt"
SYLLABLES_SHA256="3033c80d4bd20fdf4bf8378a6e89b51edbc9c68ab6737b3e9a8ec962f3546bf3"
BIGRAM_ASSET="$ROOT/ime-service/src/main/assets/pinyin_bigrams.bin"
BIGRAM_SHA256="9f37c162783e1ea1cfb59a321cc310d32d693ef8d88b332ca28b29933760fe5d"
ENGLISH_ASSET="$ROOT/ime-service/src/main/assets/english_lexicon.txt"
ENGLISH_SHA256="1a182354bc9c944dc28a384c21dbb9a2338e93bd963c4ee33f40b033a8f55624"
ENGLISH_WORD_COUNT="20000"
WUBI_ASSET="$ROOT/ime-service/src/main/assets/wubi86_lexicon.bin"
WUBI_SHA256="e2d47d43ab702862c349cd7f9ad36b2d4cbd72963c95cdb6f7911bf849937207"
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$OUT/android-user-home}

find "$OUT" -mindepth 1 -delete 2>/dev/null || true
mkdir -p \
    "$OUT/protocol-main" "$OUT/protocol-test" \
    "$OUT/memory-protocol-main" "$OUT/memory-protocol-test" \
    "$OUT/event-journal-main" "$OUT/event-journal-test" \
    "$OUT/brain-api-main" "$OUT/brain-api-test" \
    "$OUT/brain-main" "$OUT/brain-test" \
    "$OUT/runtime-main" "$OUT/runtime-test" \
    "$OUT/core-main" "$OUT/core-test" \
    "$OUT/config-main" "$OUT/config-test" \
    "$OUT/ui-main" "$OUT/ui-test" \
    "$OUT/service-main" "$OUT/service-test" \
    "$OUT/settings-main" "$OUT/settings-test" \
    "$OUT/generated" "$OUT/app-classes" "$OUT/app-test" "$OUT/dex" \
    "$ANDROID_USER_HOME" "$APK_DIR"

python3 "$ROOT/tools/test_release_plan.py" 2>&1 | tee "$OUT/release-plan-tests.txt"
python3 "$ROOT/tools/test_check_x02_boundaries.py" 2>&1 |
    tee "$OUT/x02-boundary-checker-tests.txt"
python3 "$ROOT/tools/check_x02_boundaries.py" 2>&1 |
    tee "$OUT/x02-boundaries.txt"
python3 "$ROOT/tools/test_verify_manifest_permissions.py" 2>&1 |
    tee "$OUT/manifest-permission-tests.txt"
python3 "$ROOT/tools/test_verify_aapt2_manifest_protection.py" 2>&1 |
    tee "$OUT/aapt2-manifest-protection-tests.txt"
python3 "$ROOT/tools/test_lexicon_sources.py" 2>&1 | tee "$OUT/lexicon-source-tests.txt"
python3 "$ROOT/tools/test_build_pinyin_lexicon.py" 2>&1 | tee "$OUT/lexicon-builder-tests.txt"
python3 "$ROOT/tools/test_build_bigram_model.py" 2>&1 | tee "$OUT/bigram-builder-tests.txt"
python3 "$ROOT/tools/test_build_wubi86_lexicon.py" 2>&1 | tee "$OUT/wubi86-builder-tests.txt"
python3 "$ROOT/tools/test_verify_wubi86_assets.py" 2>&1 | tee "$OUT/wubi86-verifier-tests.txt"
python3 "$ROOT/tools/test_m4_core_assets.py" 2>&1 | tee "$OUT/m4-core-assets-tests.txt"
python3 "$ROOT/tools/test_m5_mixed_assets.py" 2>&1 | tee "$OUT/m5-mixed-assets-tests.txt"
python3 "$ROOT/tools/build_pinyin_lexicon.py" \
    "$LEXICON_MANIFEST" \
    "$OUT/pinyin_lexicon.bin" \
    --manifest \
    --canonical-output "$OUT/pinyin_lexicon.canonical.tsv" \
    --syllables-output "$OUT/pinyin_syllables.txt" \
    --stats-output "$OUT/pinyin_lexicon.stats.json"
cmp "$LEXICON_ASSET" "$OUT/pinyin_lexicon.bin"
cmp "$SYLLABLES_ASSET" "$OUT/pinyin_syllables.txt"
cmp "$LEXICON_STATS" "$OUT/pinyin_lexicon.stats.json"
printf '%s  %s\n' "$LEXICON_SHA256" "$LEXICON_ASSET" | sha256sum -c -
printf '%s  %s\n' "$SYLLABLES_SHA256" "$SYLLABLES_ASSET" | sha256sum -c -
python3 "$ROOT/tools/build_bigram_model.py" \
    "$LEXICON_ASSET" \
    "$OUT/pinyin_bigrams.bin" \
    --max-pairs 65536
cmp "$BIGRAM_ASSET" "$OUT/pinyin_bigrams.bin"
printf '%s  %s\n' "$BIGRAM_SHA256" "$BIGRAM_ASSET" | sha256sum -c -
printf '%s  %s\n' "$ENGLISH_SHA256" "$ENGLISH_ASSET" | sha256sum -c -
awk '!/^#/ && NF { count++ } END { print count + 0 }' "$ENGLISH_ASSET" | grep -Fx "$ENGLISH_WORD_COUNT"
python3 "$ROOT/tools/verify_wubi86_assets.py" 2>&1 | tee "$OUT/wubi86-assets.txt"
printf '%s  %s\n' "$WUBI_SHA256" "$WUBI_ASSET" | sha256sum -c -

cmp "$ROOT/NOTICE" "$ROOT/ime-service/src/main/assets/NOTICE.txt"
cmp "$ROOT/LICENSE" "$ROOT/ime-service/src/main/assets/LICENSE.txt"
cmp "$ROOT/licenses/rime-frost-GPL-3.0.txt" "$ROOT/ime-service/src/main/assets/RIME-FROST-GPL-3.0.txt"
cmp "$ROOT/licenses/RIME-FROST-NOTICE.md" "$ROOT/ime-service/src/main/assets/RIME-FROST-NOTICE.txt"
cmp "$ROOT/licenses/popular-english-words-ISC.txt" "$ROOT/ime-service/src/main/assets/POPULAR-ENGLISH-WORDS-ISC.txt"
cmp "$ROOT/licenses/rime-wubi-LGPL-3.0.txt" "$ROOT/ime-service/src/main/assets/RIME-WUBI-LGPL-3.0.txt"
cmp "$ROOT/licenses/RIME-WUBI-NOTICE.md" "$ROOT/ime-service/src/main/assets/RIME-WUBI-NOTICE.txt"

COMPILER_CP=$(find "$KOTLIN_LIB" -maxdepth 1 -name '*.jar' -print | paste -sd: -)
STDLIB="$KOTLIN_LIB/kotlin-stdlib-2.0.21.jar"
JUNIT="$KOTLIN_LIB/junit-4.13.2.jar"
HAMCREST="$KOTLIN_LIB/hamcrest-core-1.3.jar"

# Standalone compilation does not consult a dependency cache. Keep the Sogou profile contract in
# the pure runtime gate, while the independently packaged compatibility APK reports the optional
# OkHttp/Concentus transport as unavailable. The production Gradle build compiles the real pinned
# transport and its complete tests.
cat > "$OUT/generated/SogouProtocolOfflineCompat.kt" <<'KOTLIN'
package io.github.ethanbird.senseime.speech

internal object SogouAsrProtocol {
    const val ENDPOINT_URL =
        "wss://srss.speech.sogou.com/srss/v1/speech/streaming_recognize"
}
KOTLIN
cat > "$OUT/generated/SogouTransportOfflineCompat.kt" <<'KOTLIN'
@file:Suppress("UNUSED_PARAMETER")

package io.github.ethanbird.senseime.speech

import java.util.concurrent.Executor

internal interface SogouAsrCallback {
    fun onPartialResult(transcript: CloudSpeechTranscript)

    fun onResult(result: CloudSpeechHttpResult)
}

internal interface SogouAsrLiveCall : CloudSpeechCall {
    fun sendPcm(pcm: ByteArray): Boolean

    fun finishInput(): Boolean
}

internal class SogouAsrWebSocketClient(
    callbackExecutor: Executor,
) : AutoCloseable {
    fun transcribe(
        profile: SpeechProviderProfile,
        audio: Pcm16WavAudio,
        callback: SogouAsrCallback,
    ): Result<CloudSpeechCall> = Result.failure(transportUnavailable())

    fun startStreaming(
        profile: SpeechProviderProfile,
        callback: SogouAsrCallback,
    ): Result<SogouAsrLiveCall> = Result.failure(transportUnavailable())

    override fun close() = Unit

    private fun transportUnavailable(): IllegalStateException =
        IllegalStateException("Sogou transport is provided by the production Gradle build")
}
KOTLIN

mapfile -t PROTOCOL_SOURCES < <(find "$ROOT/ai-protocol/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t PROTOCOL_TEST_SOURCES < <(find "$ROOT/ai-protocol/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t MEMORY_PROTOCOL_SOURCES < <(
    find "$ROOT/memory-protocol/src/main/kotlin" -name '*.kt' -print | sort
)
mapfile -t MEMORY_PROTOCOL_TEST_SOURCES < <(
    find "$ROOT/memory-protocol/src/test/kotlin" -name '*.kt' -print | sort
)
mapfile -t EVENT_JOURNAL_SOURCES < <(
    find "$ROOT/event-journal/src/main/kotlin" -name '*.kt' -print | sort
)
mapfile -t EVENT_JOURNAL_TEST_SOURCES < <(
    find "$ROOT/event-journal/src/test/kotlin" -name '*.kt' -print | sort
)
mapfile -t BRAIN_API_SOURCES < <(find "$ROOT/brain-api/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_API_TEST_SOURCES < <(find "$ROOT/brain-api/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_SOURCES < <(find "$ROOT/ai-brain/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_TEST_SOURCES < <(find "$ROOT/ai-brain/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t RUNTIME_PURE_SOURCES < <(printf '%s\n' \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/AgentRunRecorder.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/AgentSkillRunAdmission.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/AgentSkillRepository.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/AgentToolSettings.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainIpcEventBatcher.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainIpcSerialDeliveryQueue.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainIpcTextChunker.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainAdmissionSerialLane.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainRequestEnvelopePolicy.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainRetentionFailureGate.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainRunTickerSlot.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainPreviewReplaceWirePolicy.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/DefaultAgentToolExecutor.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/HttpUrlConnectionProviderTransport.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/ProviderConnectionTest.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/CloudSpeechProtocol.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/CloudSpeechResponseDecoder.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/CloudSpeechSessionGate.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/FloatWaveformRingBuffer.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/Pcm16Audio.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/SpeechProviderCredentialPolicy.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/SpeechProviderProfile.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/SpeechRecognitionState.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/speech/SystemSpeechFallbackPolicy.kt")
mapfile -t RUNTIME_TEST_SOURCES < <(
    find "$ROOT/ai-runtime/src/test/kotlin" -name '*.kt' \
        ! -name 'SogouAsrLiveProbeTest.kt' \
        ! -name 'SogouAsrProtocolTest.kt' \
        -print | sort
)
RUNTIME_PURE_SOURCES+=("$OUT/generated/SogouProtocolOfflineCompat.kt")
mapfile -t CORE_SOURCES < <(find "$ROOT/core-input/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t TEST_SOURCES < <(find "$ROOT/core-input/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t CONFIG_SOURCES < <(find "$ROOT/ime-config/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t CONFIG_TEST_SOURCES < <(find "$ROOT/ime-config/src/test/kotlin" -name '*.kt' -print | sort)
# Compile the complete Android UI source set with the older offline compiler. JVM execution below
# omits the two tests that instantiate platform android.jar stubs; Gradle's authoritative unit gate
# executes those against AGP's mockable Android runtime.
mapfile -t UI_LAYOUT_SOURCES < <(find "$ROOT/ime-ui/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t UI_TEST_SOURCES < <(
    find "$ROOT/ime-ui/src/test/kotlin" -name '*.kt' \
        ! -name 'KeyboardArchitectureTest.kt' \
        ! -name 'KeyboardInteractionControllerTest.kt' \
        -print | sort
)
mapfile -t SERVICE_PURE_SOURCES < <(
    {
        find "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service" \
            -maxdepth 1 -name '*.kt' \
            ! -name 'AgentSkillDirectoryWatcher.kt' \
            ! -name 'SenseInputMethodService.kt' \
            -print
        printf '%s\n' \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ai/AgentSkillRunSnapshot.kt" \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ai/AgentStreamPresentation.kt"
        find "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ai/editor" \
            -name '*.kt' -print
    } | sort -u
)
mapfile -t SERVICE_TEST_SOURCES < <(find "$ROOT/ime-service/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t SETTINGS_PURE_SOURCES < <(printf '%s\n' \
    "$ROOT/app/src/main/kotlin/io/github/ethanbird/senseime/SettingsNavigation.kt" \
    "$ROOT/app/src/main/kotlin/io/github/ethanbird/senseime/SettingsAsyncLane.kt" \
    "$ROOT/app/src/main/kotlin/io/github/ethanbird/senseime/SkillDraftCaptureCoordinator.kt" \
    "$ROOT/app/src/main/kotlin/io/github/ethanbird/senseime/SkillDraftRecoveryStore.kt" \
    "$ROOT/app/src/main/kotlin/io/github/ethanbird/senseime/SkillSettingsIoSession.kt" \
    "$ROOT/app/src/main/kotlin/io/github/ethanbird/senseime/SkillSettingsModel.kt")
mapfile -t SETTINGS_TEST_SOURCES < <(printf '%s\n' \
    "$ROOT/app/src/test/kotlin/io/github/ethanbird/senseime/SettingsAsyncLaneTest.kt" \
    "$ROOT/app/src/test/kotlin/io/github/ethanbird/senseime/SettingsNavigationStateTest.kt" \
    "$ROOT/app/src/test/kotlin/io/github/ethanbird/senseime/SkillDraftCaptureCoordinatorTest.kt" \
    "$ROOT/app/src/test/kotlin/io/github/ethanbird/senseime/SkillDraftRecoveryStoreTest.kt" \
    "$ROOT/app/src/test/kotlin/io/github/ethanbird/senseime/SkillSettingsIoSessionTest.kt" \
    "$ROOT/app/src/test/kotlin/io/github/ethanbird/senseime/SkillSettingsModelTest.kt")
mapfile -t APP_TEST_SOURCES < <(find "$ROOT/app/src/test/kotlin" -name '*.kt' -print | sort)

if command -v rg >/dev/null 2>&1; then
    if rg -n 'java\.net\.|javax\.net\.|okhttp|retrofit' \
        "$ROOT/ime-service" "$ROOT/ime-ui"; then
        echo "Release gate failed: network transport leaked into the IME or UI module." >&2
        exit 1
    fi
elif grep -R -n -E \
    --include='*.kt' \
    'java\.net\.|javax\.net\.|okhttp|retrofit' \
    "$ROOT/ime-service" "$ROOT/ime-ui"; then
    echo "Release gate failed: network transport leaked into the IME or UI module." >&2
    exit 1
fi

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT/protocol-main" "${PROTOCOL_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main" \
    -d "$OUT/protocol-test" "${PROTOCOL_TEST_SOURCES[@]}"
PROTOCOL_TEST_CLASSES=()
for source in "${PROTOCOL_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    PROTOCOL_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/protocol-test" \
    org.junit.runner.JUnitCore "${PROTOCOL_TEST_CLASSES[@]}" | tee "$OUT/protocol-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT/memory-protocol-main" "${MEMORY_PROTOCOL_SOURCES[@]}"
"$JAR_TOOL" --create \
    --file "$MEMORY_PROTOCOL_JAR" \
    -C "$OUT/memory-protocol-main" .
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/memory-protocol-main" \
    -Xfriend-paths="$OUT/memory-protocol-main" \
    -d "$OUT/memory-protocol-test" "${MEMORY_PROTOCOL_TEST_SOURCES[@]}"
MEMORY_PROTOCOL_TEST_CLASSES=()
for source in "${MEMORY_PROTOCOL_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    MEMORY_PROTOCOL_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/memory-protocol-main:$OUT/memory-protocol-test" \
    org.junit.runner.JUnitCore "${MEMORY_PROTOCOL_TEST_CLASSES[@]}" |
    tee "$OUT/memory-protocol-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$OUT/memory-protocol-main" \
    -d "$OUT/event-journal-main" "${EVENT_JOURNAL_SOURCES[@]}"
"$JAR_TOOL" --create \
    --file "$EVENT_JOURNAL_JAR" \
    -C "$OUT/event-journal-main" .
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath \
        "$STDLIB:$JUNIT:$HAMCREST:$OUT/memory-protocol-main:$OUT/event-journal-main" \
    -Xfriend-paths="$OUT/event-journal-main" \
    -d "$OUT/event-journal-test" "${EVENT_JOURNAL_TEST_SOURCES[@]}"
EVENT_JOURNAL_TEST_CLASSES=()
for source in "${EVENT_JOURNAL_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    EVENT_JOURNAL_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp \
    "$STDLIB:$JUNIT:$HAMCREST:$OUT/memory-protocol-main:$OUT/event-journal-main:$OUT/event-journal-test" \
    org.junit.runner.JUnitCore "${EVENT_JOURNAL_TEST_CLASSES[@]}" |
    tee "$OUT/event-journal-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$ANDROID_JAR" \
    -d "$OUT/config-main" "${CONFIG_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/config-main" \
    -Xfriend-paths="$OUT/config-main" \
    -d "$OUT/config-test" "${CONFIG_TEST_SOURCES[@]}"
CONFIG_TEST_CLASSES=()
for source in "${CONFIG_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    CONFIG_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/config-main:$OUT/config-test" \
    org.junit.runner.JUnitCore "${CONFIG_TEST_CLASSES[@]}" |
    tee "$OUT/config-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$OUT/protocol-main" \
    -d "$OUT/brain-api-main" "${BRAIN_API_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main" \
    -d "$OUT/brain-api-test" "${BRAIN_API_TEST_SOURCES[@]}"
BRAIN_API_TEST_CLASSES=()
for source in "${BRAIN_API_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    BRAIN_API_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/brain-api-test" \
    org.junit.runner.JUnitCore "${BRAIN_API_TEST_CLASSES[@]}" | tee "$OUT/brain-api-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$ANDROID_JAR:$OUT/protocol-main:$OUT/brain-api-main" \
    -d "$OUT/settings-main" "${SETTINGS_PURE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath \
        "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/settings-main" \
    -Xfriend-paths="$OUT/settings-main" \
    -d "$OUT/settings-test" "${SETTINGS_TEST_SOURCES[@]}"
SETTINGS_TEST_CLASSES=()
for source in "${SETTINGS_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    SETTINGS_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp \
    "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/settings-main:$OUT/settings-test" \
    org.junit.runner.JUnitCore "${SETTINGS_TEST_CLASSES[@]}" |
    tee "$OUT/settings-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$OUT/protocol-main:$OUT/brain-api-main" \
    -d "$OUT/brain-main" "${BRAIN_SOURCES[@]}"
if [[ -d "$ROOT/ai-brain/src/main/resources" ]]; then
    cp -R "$ROOT/ai-brain/src/main/resources/." "$OUT/brain-main/"
fi
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/brain-main" \
    -Xfriend-paths="$OUT/brain-main" \
    -d "$OUT/brain-test" "${BRAIN_TEST_SOURCES[@]}"
BRAIN_TEST_CLASSES=()
for source in "${BRAIN_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    BRAIN_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/brain-main:$OUT/brain-test" \
    org.junit.runner.JUnitCore "${BRAIN_TEST_CLASSES[@]}" | tee "$OUT/brain-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$OUT/protocol-main:$OUT/brain-api-main:$OUT/brain-main" \
    -d "$OUT/runtime-main" "${RUNTIME_PURE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath \
        "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/brain-main:$OUT/runtime-main" \
    -Xfriend-paths="$OUT/runtime-main" \
    -d "$OUT/runtime-test" "${RUNTIME_TEST_SOURCES[@]}"
RUNTIME_TEST_CLASSES=()
for source in "${RUNTIME_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    RUNTIME_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp \
    "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/brain-main:$OUT/runtime-main:$OUT/runtime-test" \
    org.junit.runner.JUnitCore "${RUNTIME_TEST_CLASSES[@]}" | tee "$OUT/runtime-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT/core-main" "${CORE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/core-main" \
    -d "$OUT/core-test" "${TEST_SOURCES[@]}"

CORE_TEST_CLASSES=()
for source in "${TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    CORE_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
(
    cd "$ROOT/core-input"
    java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/core-main:$OUT/core-test" \
        org.junit.runner.JUnitCore "${CORE_TEST_CLASSES[@]}"
) | tee "$OUT/unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB:$ANDROID_JAR" \
    -d "$OUT/ui-main" "${UI_LAYOUT_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/ui-main" \
    -Xfriend-paths="$OUT/ui-main" \
    -d "$OUT/ui-test" "${UI_TEST_SOURCES[@]}"
UI_TEST_CLASSES=()
for source in "${UI_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    UI_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/ui-main:$OUT/ui-test" \
    org.junit.runner.JUnitCore "${UI_TEST_CLASSES[@]}" | tee "$OUT/ui-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath \
        "$STDLIB:$ANDROID_JAR:$OUT/protocol-main:$OUT/brain-api-main:$OUT/core-main:$OUT/config-main" \
    -d "$OUT/service-main" "${SERVICE_PURE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath \
        "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/core-main:$OUT/config-main:$OUT/service-main" \
    -Xfriend-paths="$OUT/service-main" \
    -d "$OUT/service-test" "${SERVICE_TEST_SOURCES[@]}"
SERVICE_TEST_CLASSES=()
for source in "${SERVICE_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    SERVICE_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
(
    cd "$ROOT/ime-service"
    java -cp \
        "$STDLIB:$ANDROID_JAR:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/brain-api-main:$OUT/core-main:$OUT/config-main:$OUT/service-main:$OUT/service-test" \
        org.junit.runner.JUnitCore "${SERVICE_TEST_CLASSES[@]}"
) | tee "$OUT/service-unit-tests.txt"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M0HostBenchmark \
    "$ROOT/benchmarks/results/m0-host.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M1PinyinBenchmark \
    "$LEXICON_ASSET" \
    "$ROOT/benchmarks/results/m1-pinyin.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M2AdaptiveBenchmark \
    "$LEXICON_ASSET" \
    "$ROOT/ime-service/src/main/assets/pinyin_syllables.txt" \
    "$ROOT/benchmarks/results/m2-adaptive.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M3SentenceBenchmark \
    "$LEXICON_ASSET" \
    "$BIGRAM_ASSET" \
    "$ROOT/benchmarks/replay/m3-sentences.tsv" \
    "$ROOT/benchmarks/results/m3-sentence.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M4CoreBenchmark \
    "$LEXICON_ASSET" \
    "$BIGRAM_ASSET" \
    "$ROOT/ime-service/src/main/assets/pinyin_syllables.txt" \
    "$ROOT/benchmarks/replay/m4-core.tsv" \
    "$ROOT/benchmarks/results/m4-core.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M5MixedInputBenchmark \
    "$LEXICON_ASSET" \
    "$BIGRAM_ASSET" \
    "$ROOT/ime-service/src/main/assets/pinyin_syllables.txt" \
    "$ENGLISH_ASSET" \
    "$ROOT/benchmarks/results/m5-mixed-input.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M6InputPolishBenchmark \
    "$ENGLISH_ASSET" \
    "$ROOT/benchmarks/results/m6-input-polish.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M7ChineseSchemeBenchmark \
    "$ROOT/ime-service/src/main/assets/wubi86_lexicon.bin" \
    "$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin" \
    "$ROOT/ime-service/src/main/assets/pinyin_bigrams.bin" \
    "$ROOT/ime-service/src/main/assets/pinyin_syllables.txt" \
    "$ROOT/benchmarks/results/m7-chinese-schemes.json"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/app-res.zip"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/ime-service/src/main/res" -o "$OUT/ime-service-res.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/tools/offline/AndroidManifest.xml" \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay \
    --output-text-symbols "$OUT/R.txt" \
    -A "$ROOT/ime-service/src/main/assets" \
    -R "$OUT/app-res.zip" \
    -R "$OUT/ime-service-res.zip" \
    -o "$OUT/resources.apk"

awk '
BEGIN { print "package io.github.ethanbird.senseime\n\nobject R {"; type = "" }
$1 == "int" {
    if ($2 != type) {
        if (type != "") print "    }"
        type = $2
        print "    object " type " {"
    }
    print "        const val " $3 ": Int = " $4
}
END { if (type != "") print "    }"; print "}" }
' "$OUT/R.txt" > "$OUT/generated/R.kt"
awk '
BEGIN { print "package io.github.ethanbird.senseime.service\n\nobject R {"; type = "" }
$1 == "int" {
    if ($2 != type) {
        if (type != "") print "    }"
        type = $2
        print "    object " type " {"
    }
    print "        const val " $3 ": Int = " $4
}
END { if (type != "") print "    }"; print "}" }
' "$OUT/R.txt" > "$OUT/generated/ServiceR.kt"

# The offline APK remains independent of Maven/Gradle caches. The production build links the
# pinned AndroidX Activity artifact; this small source-compatible dispatcher covers the only
# AndroidX surface used by the settings Activity in the standalone compiler path.
cat > "$OUT/generated/AndroidxActivityCompat.kt" <<'KOTLIN'
package androidx.activity

import android.app.Activity

abstract class OnBackPressedCallback(
    var isEnabled: Boolean,
) {
    abstract fun handleOnBackPressed()
}

class OnBackPressedDispatcher(
    private val fallbackOnBackPressed: () -> Unit,
) {
    private val callbacks = mutableListOf<OnBackPressedCallback>()

    @Suppress("UNUSED_PARAMETER")
    fun addCallback(owner: ComponentActivity, callback: OnBackPressedCallback) {
        callbacks += callback
    }

    fun onBackPressed() {
        callbacks.asReversed().firstOrNull { it.isEnabled }
            ?.handleOnBackPressed()
            ?: fallbackOnBackPressed()
    }
}

open class ComponentActivity : Activity() {
    val onBackPressedDispatcher: OnBackPressedDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        OnBackPressedDispatcher(::dispatchPlatformBack)
    }

    @Suppress("DEPRECATION")
    private fun dispatchPlatformBack() {
        super.onBackPressed()
    }
}
KOTLIN

mapfile -t APP_SOURCES < <(
    find \
        "$ROOT/ai-protocol/src/main/kotlin" \
        "$ROOT/brain-api/src/main/kotlin" \
        "$ROOT/ai-brain/src/main/kotlin" \
        "$ROOT/ai-runtime/src/main/kotlin" \
        "$ROOT/core-input/src/main/kotlin" \
        "$ROOT/ime-config/src/main/kotlin" \
        "$ROOT/ime-ui/src/main/kotlin" \
        "$ROOT/ime-service/src/main/kotlin" \
        "$ROOT/app/src/main/kotlin" \
        -name '*.kt' \
        ! -name 'SogouAsrProtocol.kt' \
        ! -name 'SogouAsrWebSocketClient.kt' \
        -print | sort
)
APP_SOURCES+=(
    "$OUT/generated/R.kt"
    "$OUT/generated/ServiceR.kt"
    "$OUT/generated/AndroidxActivityCompat.kt"
    "$OUT/generated/SogouProtocolOfflineCompat.kt"
    "$OUT/generated/SogouTransportOfflineCompat.kt"
)

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$ANDROID_JAR:$STDLIB" \
    -d "$OUT/app-classes" "${APP_SOURCES[@]}"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$ANDROID_JAR:$STDLIB:$JUNIT:$HAMCREST:$OUT/app-classes" \
    -Xfriend-paths="$OUT/app-classes" \
    -d "$OUT/app-test" "${APP_TEST_SOURCES[@]}"
APP_TEST_CLASSES=()
for source in "${APP_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    package_name=$(
        sed -n -E \
            's/^[[:space:]]*package[[:space:]]+([^[:space:]]+).*/\1/p' \
            "$source"
    )
    [[ -n "$package_name" ]]
    APP_TEST_CLASSES+=("$package_name.${file_name%.kt}")
done
java -cp \
    "$ANDROID_JAR:$STDLIB:$JUNIT:$HAMCREST:$OUT/app-classes:$OUT/app-test" \
    org.junit.runner.JUnitCore "${APP_TEST_CLASSES[@]}" |
    tee "$OUT/app-unit-tests.txt"

(cd "$OUT/app-classes" && zip -q -r "$OUT/app-classes.jar" .)
"$BUILD_TOOLS/d8" \
    --min-api 29 \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    "$OUT/app-classes.jar" \
    "$STDLIB"

cp "$OUT/resources.apk" "$OUT/unsigned-unaligned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned-unaligned.apk" classes*.dex)
(cd "$ROOT/ai-brain/src/main/resources" && zip -q -r "$OUT/unsigned-unaligned.apk" .)
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned-unaligned.apk" "$OUT/unsigned-aligned.apk"

keytool -genkeypair \
    -keystore "$OUT/debug.keystore" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -dname "CN=Android Debug,O=Sense,C=CN" \
    -keyalg RSA \
    -validity 10000 \
    -noprompt >/dev/null 2>&1

"$BUILD_TOOLS/apksigner" sign \
    --ks "$OUT/debug.keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$APK" \
    "$OUT/unsigned-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK" | tee "$OUT/apksigner.txt"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$APK"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | tee "$OUT/apk-badging.txt"
"$BUILD_TOOLS/aapt2" dump permissions "$APK" | tee "$OUT/apk-permissions.txt"
"$BUILD_TOOLS/aapt2" dump xmltree "$APK" --file AndroidManifest.xml |
    tee "$OUT/apk-manifest.xmltree"
python3 "$ROOT/tools/verify_aapt2_manifest_protection.py" \
    --permissions "$OUT/apk-permissions.txt" \
    "$OUT/apk-manifest.xmltree"
APK_ANALYZER=$(
    find "$SDK/cmdline-tools" -type f -name apkanalyzer -print |
        sort -V |
        tail -n 1
)
if [[ ! -x "$APK_ANALYZER" ]]; then
    echo "Android apkanalyzer is required to verify the packaged manifest." >&2
    exit 2
fi
"$APK_ANALYZER" manifest print "$APK" > "$OUT/apk-manifest.xml"
python3 "$ROOT/tools/verify_manifest_permissions.py" \
    --packaged "$OUT/apk-manifest.xml"
python3 - "$OUT/apk-manifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path = sys.argv[1]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(manifest_path).getroot()
application = root.find("application")
if application is None:
    raise SystemExit(f"{manifest_path}: missing application")
services = application.findall("service")
activities = application.findall("activity")


def exactly_one(name: str):
    matches = [
        service
        for service in services
        if service.get(android + "name") == name
    ]
    if len(matches) != 1:
        raise SystemExit(
            f"{manifest_path}: expected one {name}, found {len(matches)}"
        )
    return matches[0]


def exactly_one_activity(name: str):
    matches = [
        activity
        for activity in activities
        if activity.get(android + "name") == name
    ]
    if len(matches) != 1:
        raise SystemExit(
            f"{manifest_path}: expected one {name}, found {len(matches)}"
        )
    return matches[0]


brain = exactly_one(
    "io.github.ethanbird.senseime.brain.runtime.SenseAiBrainService"
)
if brain.get(android + "exported") != "false":
    raise SystemExit(f"{manifest_path}: Brain service must be exported=false")
if brain.get(android + "process") != ":brain":
    raise SystemExit(f"{manifest_path}: Brain service must run in :brain")
if brain.get(android + "foregroundServiceType") != "specialUse":
    raise SystemExit(f"{manifest_path}: Brain service must use specialUse")
subtypes = [
    prop
    for prop in brain.findall("property")
    if prop.get(android + "name")
    == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
]
if (
    len(subtypes) != 1
    or subtypes[0].get(android + "value")
    != "user_initiated_agent_task"
):
    raise SystemExit(f"{manifest_path}: Brain special-use subtype drifted")

agent_hub = exactly_one_activity(
    "io.github.ethanbird.senseime.AgentHubActivity"
)
if agent_hub.get(android + "exported") != "false":
    raise SystemExit(f"{manifest_path}: Agent Hub must be exported=false")
if agent_hub.get(android + "process") != ":brain":
    raise SystemExit(f"{manifest_path}: Agent Hub must run in :brain")
if agent_hub.get(android + "windowSoftInputMode") != "adjustResize":
    raise SystemExit(f"{manifest_path}: Agent Hub must use adjustResize")

ime = exactly_one(
    "io.github.ethanbird.senseime.service.SenseInputMethodService"
)
if ime.get(android + "permission") != "android.permission.BIND_INPUT_METHOD":
    raise SystemExit(f"{manifest_path}: IME service must require BIND_INPUT_METHOD")
actions = {
    action.get(android + "name")
    for action in ime.findall("./intent-filter/action")
}
if "android.view.InputMethod" not in actions:
    raise SystemExit(f"{manifest_path}: IME service is missing InputMethod action")
PY
grep -F "package: name='io.github.ethanbird.senseime' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" "$OUT/apk-badging.txt"
grep -Fx "minSdkVersion:'29'" "$OUT/apk-badging.txt"
grep -Fx "targetSdkVersion:'36'" "$OUT/apk-badging.txt"
DECLARED_PERMISSIONS=$(
    sed -n -E \
        "s/^uses-permission(-sdk-[0-9]+)?: name='([^']+)'.*/\2/p" \
        "$OUT/apk-permissions.txt" |
        sort -u
)
if ! grep -Fxq "android.permission.INTERNET" <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: AI build is missing android.permission.INTERNET." >&2
    exit 1
fi
if ! grep -Fxq "android.permission.RECORD_AUDIO" <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: speech input is missing android.permission.RECORD_AUDIO." >&2
    exit 1
fi
if ! grep -Fxq "android.permission.FOREGROUND_SERVICE" <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: Agent runtime is missing android.permission.FOREGROUND_SERVICE." >&2
    exit 1
fi
if ! grep -Fxq "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: Agent runtime is missing android.permission.FOREGROUND_SERVICE_SPECIAL_USE." >&2
    exit 1
fi
if ! grep -Fxq "android.permission.POST_NOTIFICATIONS" <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: Agent runtime is missing android.permission.POST_NOTIFICATIONS." >&2
    exit 1
fi
if ! grep -Fxq "android.permission.WAKE_LOCK" <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: Agent runtime is missing android.permission.WAKE_LOCK." >&2
    exit 1
fi
if ! grep -Fxq \
    "io.github.ethanbird.senseime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" \
    <<<"$DECLARED_PERMISSIONS"; then
    echo "Release gate failed: AndroidX signature receiver permission is missing." >&2
    exit 1
fi
UNEXPECTED_PERMISSIONS=$(
    grep -Fvx \
        -e "android.permission.INTERNET" \
        -e "android.permission.RECORD_AUDIO" \
        -e "android.permission.FOREGROUND_SERVICE" \
        -e "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" \
        -e "android.permission.POST_NOTIFICATIONS" \
        -e "android.permission.WAKE_LOCK" \
        -e "io.github.ethanbird.senseime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" \
        <<<"$DECLARED_PERMISSIONS" || true
)
if [[ -n "$UNEXPECTED_PERMISSIONS" ]]; then
    printf 'Release gate failed: unexpected APK permissions:\n%s\n' \
        "$UNEXPECTED_PERMISSIONS" >&2
    exit 1
fi
unzip -p "$APK" assets/NOTICE.txt | cmp - "$ROOT/NOTICE"
unzip -p "$APK" assets/LICENSE.txt | cmp - "$ROOT/LICENSE"
unzip -p "$APK" assets/RIME-FROST-GPL-3.0.txt | cmp - "$ROOT/licenses/rime-frost-GPL-3.0.txt"
unzip -p "$APK" assets/RIME-FROST-NOTICE.txt | cmp - "$ROOT/licenses/RIME-FROST-NOTICE.md"
unzip -p "$APK" assets/POPULAR-ENGLISH-WORDS-ISC.txt | cmp - "$ROOT/licenses/popular-english-words-ISC.txt"
unzip -p "$APK" assets/RIME-WUBI-LGPL-3.0.txt | cmp - "$ROOT/licenses/rime-wubi-LGPL-3.0.txt"
unzip -p "$APK" assets/RIME-WUBI-NOTICE.txt | cmp - "$ROOT/licenses/RIME-WUBI-NOTICE.md"
unzip -p "$APK" sense/soul.md | cmp - "$ROOT/ai-brain/src/main/resources/sense/soul.md"
unzip -p "$APK" assets/pinyin_lexicon.bin | sha256sum | awk '{print $1}' | grep -Fx "$LEXICON_SHA256"
unzip -p "$APK" assets/pinyin_bigrams.bin | sha256sum | awk '{print $1}' | grep -Fx "$BIGRAM_SHA256"
unzip -p "$APK" assets/english_lexicon.txt | sha256sum | awk '{print $1}' | grep -Fx "$ENGLISH_SHA256"
unzip -p "$APK" assets/wubi86_lexicon.bin | sha256sum | awk '{print $1}' | grep -Fx "$WUBI_SHA256"
unzip -p "$APK" assets/english_lexicon.txt |
    awk '!/^#/ && NF { count++ } END { print count + 0 }' |
    grep -Fx "$ENGLISH_WORD_COUNT"
unzip -l "$APK" \
    assets/NOTICE.txt \
    assets/LICENSE.txt \
    assets/RIME-FROST-GPL-3.0.txt \
    assets/RIME-FROST-NOTICE.txt \
    assets/POPULAR-ENGLISH-WORDS-ISC.txt \
    assets/RIME-WUBI-LGPL-3.0.txt \
    assets/RIME-WUBI-NOTICE.txt \
    sense/soul.md \
    assets/pinyin_lexicon.bin \
    assets/pinyin_bigrams.bin \
    assets/english_lexicon.txt \
    assets/wubi86_lexicon.bin | tee "$OUT/apk-attributed-assets.txt"
python3 "$ROOT/tools/verify_wubi86_assets.py" --apk "$APK" 2>&1 |
    tee "$OUT/apk-wubi86-assets.txt"
sha256sum "$APK" | tee "$APK.sha256"
python3 "$ROOT/tools/check_x02_boundaries.py" \
    --check-artifacts \
    --memory-jar "$MEMORY_PROTOCOL_JAR" \
    --event-journal-jar "$EVENT_JOURNAL_JAR" \
    --app-apk "$APK"

HOME="$ANDROID_USER_HOME" "$SDK/cmdline-tools/latest/bin/lint" \
    --exitcode \
    --sdk-home "$SDK" \
    --compile-sdk-version 36 \
    --kotlin-language-level 2.0 \
    --resources "$ROOT/app/src/main/res" \
    --resources "$ROOT/ime-service/src/main/res" \
    --sources "$ROOT/app/src/main/kotlin" \
    --sources "$ROOT/ime-service/src/main/kotlin" \
    --sources "$ROOT/ime-config/src/main/kotlin" \
    --sources "$ROOT/ime-ui/src/main/kotlin" \
    --sources "$ROOT/core-input/src/main/kotlin" \
    --sources "$ROOT/ai-protocol/src/main/kotlin" \
    --sources "$ROOT/brain-api/src/main/kotlin" \
    --sources "$ROOT/ai-brain/src/main/kotlin" \
    --sources "$ROOT/ai-runtime/src/main/kotlin" \
    --classpath "$OUT/app-classes" \
    --libraries "$ANDROID_JAR" \
    --text "$OUT/lint.txt" \
    "$ROOT/tools/offline"

echo "v$VERSION_NAME offline compatibility verification complete: $APK"
