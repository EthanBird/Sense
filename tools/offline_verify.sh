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

BUILD_TOOLS="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
KOTLIN_LIB="$GRADLE_DIST/lib"
OUT="$ROOT/build/offline-v0.3.7-m8"
APK_DIR="$ROOT/app/build/outputs/apk/offline"
APK="$APK_DIR/Sense-v0.3.7-m8-debug.apk"
LEXICON_ASSET="$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin"
LEXICON_SHA256="ef2fac5d3b62ba3d88674e63a9bfbdc907f0a814b1798fbba25f6ac3cadccce6"
BIGRAM_ASSET="$ROOT/ime-service/src/main/assets/pinyin_bigrams.bin"
BIGRAM_SHA256="db00a109dde6d1f471172a7abb53aae30509894d6064897a80a502aab690f18c"
ENGLISH_ASSET="$ROOT/ime-service/src/main/assets/english_lexicon.txt"
ENGLISH_SHA256="1a182354bc9c944dc28a384c21dbb9a2338e93bd963c4ee33f40b033a8f55624"
ENGLISH_WORD_COUNT="20000"
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$OUT/android-user-home}

find "$OUT" -mindepth 1 -delete 2>/dev/null || true
mkdir -p \
    "$OUT/protocol-main" "$OUT/protocol-test" \
    "$OUT/brain-api-main" "$OUT/brain-api-test" \
    "$OUT/brain-main" "$OUT/brain-test" \
    "$OUT/runtime-main" "$OUT/runtime-test" \
    "$OUT/core-main" "$OUT/core-test" \
    "$OUT/ui-main" "$OUT/ui-test" \
    "$OUT/service-main" "$OUT/service-test" \
    "$OUT/generated" "$OUT/app-classes" "$OUT/dex" \
    "$ANDROID_USER_HOME" "$APK_DIR"

python3 "$ROOT/tools/test_build_pinyin_lexicon.py" 2>&1 | tee "$OUT/lexicon-builder-tests.txt"
python3 "$ROOT/tools/test_build_bigram_model.py" 2>&1 | tee "$OUT/bigram-builder-tests.txt"
python3 "$ROOT/tools/test_m4_core_assets.py" 2>&1 | tee "$OUT/m4-core-assets-tests.txt"
python3 "$ROOT/tools/test_m5_mixed_assets.py" 2>&1 | tee "$OUT/m5-mixed-assets-tests.txt"
python3 "$ROOT/tools/build_pinyin_lexicon.py" \
    "$LEXICON_ASSET" \
    "$OUT/pinyin_lexicon.bin" \
    --base-binary \
    --custom "$ROOT/ime-service/src/main/lexicon/sense_idioms.dict.tsv" \
    --custom "$ROOT/ime-service/src/main/lexicon/sense_custom.dict.tsv"
cmp "$LEXICON_ASSET" "$OUT/pinyin_lexicon.bin"
printf '%s  %s\n' "$LEXICON_SHA256" "$LEXICON_ASSET" | sha256sum -c -
python3 "$ROOT/tools/build_bigram_model.py" \
    "$LEXICON_ASSET" \
    "$OUT/pinyin_bigrams.bin" \
    --max-pairs 65536
cmp "$BIGRAM_ASSET" "$OUT/pinyin_bigrams.bin"
printf '%s  %s\n' "$BIGRAM_SHA256" "$BIGRAM_ASSET" | sha256sum -c -
printf '%s  %s\n' "$ENGLISH_SHA256" "$ENGLISH_ASSET" | sha256sum -c -
awk '!/^#/ && NF { count++ } END { print count + 0 }' "$ENGLISH_ASSET" | grep -Fx "$ENGLISH_WORD_COUNT"

cmp "$ROOT/NOTICE" "$ROOT/ime-service/src/main/assets/NOTICE.txt"
cmp "$ROOT/LICENSE" "$ROOT/ime-service/src/main/assets/LICENSE.txt"
cmp "$ROOT/licenses/rime-pinyin-simp-LICENSE" "$ROOT/ime-service/src/main/assets/RIME-PINYIN-SIMP-LICENSE.txt"
cmp "$ROOT/licenses/CC-CEDICT-NOTICE.md" "$ROOT/ime-service/src/main/assets/CC-CEDICT-NOTICE.txt"
cmp "$ROOT/licenses/CC-BY-SA-4.0.txt" "$ROOT/ime-service/src/main/assets/CC-BY-SA-4.0.txt"
cmp "$ROOT/licenses/popular-english-words-ISC.txt" "$ROOT/ime-service/src/main/assets/POPULAR-ENGLISH-WORDS-ISC.txt"
cmp "$ROOT/licenses/chinese-idiom-chengyu-MIT.txt" "$ROOT/ime-service/src/main/assets/CHINESE-IDIOM-CHENGYU-MIT.txt"

COMPILER_CP=$(find "$KOTLIN_LIB" -maxdepth 1 -name '*.jar' -print | paste -sd: -)
STDLIB="$KOTLIN_LIB/kotlin-stdlib-2.0.21.jar"
JUNIT="$KOTLIN_LIB/junit-4.13.2.jar"
HAMCREST="$KOTLIN_LIB/hamcrest-core-1.3.jar"

mapfile -t PROTOCOL_SOURCES < <(find "$ROOT/ai-protocol/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t PROTOCOL_TEST_SOURCES < <(find "$ROOT/ai-protocol/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_API_SOURCES < <(find "$ROOT/brain-api/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_API_TEST_SOURCES < <(find "$ROOT/brain-api/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_SOURCES < <(find "$ROOT/ai-brain/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t BRAIN_TEST_SOURCES < <(find "$ROOT/ai-brain/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t RUNTIME_PURE_SOURCES < <(printf '%s\n' \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/BrainIpcTextChunker.kt" \
    "$ROOT/ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/ProviderConnectionTest.kt")
mapfile -t RUNTIME_TEST_SOURCES < <(find "$ROOT/ai-runtime/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t CORE_SOURCES < <(find "$ROOT/core-input/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t TEST_SOURCES < <(find "$ROOT/core-input/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t UI_LAYOUT_SOURCES < <(printf '%s\n' \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyCodes.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/CandidatePresentationPolicy.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/CanvasIconGeometry.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/CandidateStripScrollState.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/EmojiCatalog.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyboardLayoutContract.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KineticScrollPolicy.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyboardGapHitResolver.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/SymbolCatalog.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/SwipeCharacterMap.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/TouchInputReducer.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/AiHoldGestureSession.kt")
mapfile -t UI_TEST_SOURCES < <(find "$ROOT/ime-ui/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t SERVICE_PURE_SOURCES < <(
    {
        printf '%s\n' \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/CandidateDecodeSession.kt" \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/EditorCompositionSelectionPolicy.kt" \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/EditorPrivacyPolicy.kt" \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/LatestOnlyTaskRunner.kt" \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ProgressiveCandidateSnapshot.kt" \
            "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ai/AgentStreamPresentation.kt"
        find "$ROOT/ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/ai/editor" \
            -name '*.kt' -print
    } | sort -u
)
mapfile -t SERVICE_TEST_SOURCES < <(find "$ROOT/ime-service/src/test/kotlin" -name '*.kt' -print | sort)

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
    -classpath "$STDLIB:$OUT/protocol-main" \
    -d "$OUT/runtime-main" "${RUNTIME_PURE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/runtime-main" \
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
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/runtime-main:$OUT/runtime-test" \
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
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/core-main:$OUT/core-test" \
    org.junit.runner.JUnitCore "${CORE_TEST_CLASSES[@]}" | tee "$OUT/unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT/ui-main" "${UI_LAYOUT_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/ui-main" \
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
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/ui-main:$OUT/ui-test" \
    org.junit.runner.JUnitCore "${UI_TEST_CLASSES[@]}" | tee "$OUT/ui-unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$OUT/protocol-main:$OUT/core-main" \
    -d "$OUT/service-main" "${SERVICE_PURE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/core-main:$OUT/service-main" \
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
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/protocol-main:$OUT/core-main:$OUT/service-main:$OUT/service-test" \
    org.junit.runner.JUnitCore "${SERVICE_TEST_CLASSES[@]}" | tee "$OUT/service-unit-tests.txt"

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

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/app-res.zip"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/ime-service/src/main/res" -o "$OUT/ime-service-res.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/tools/offline/AndroidManifest.xml" \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --version-code 14 \
    --version-name 0.3.7-m8 \
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

mapfile -t APP_SOURCES < <(
    find \
        "$ROOT/ai-protocol/src/main/kotlin" \
        "$ROOT/brain-api/src/main/kotlin" \
        "$ROOT/ai-brain/src/main/kotlin" \
        "$ROOT/ai-runtime/src/main/kotlin" \
        "$ROOT/core-input/src/main/kotlin" \
        "$ROOT/ime-ui/src/main/kotlin" \
        "$ROOT/ime-service/src/main/kotlin" \
        "$ROOT/app/src/main/kotlin" \
        -name '*.kt' -print | sort
)
APP_SOURCES+=("$OUT/generated/R.kt")

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$ANDROID_JAR:$STDLIB" \
    -d "$OUT/app-classes" "${APP_SOURCES[@]}"

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


brain = exactly_one(
    "io.github.ethanbird.senseime.brain.runtime.SenseAiBrainService"
)
if brain.get(android + "exported") != "false":
    raise SystemExit(f"{manifest_path}: Brain service must be exported=false")
if brain.get(android + "process") != ":brain":
    raise SystemExit(f"{manifest_path}: Brain service must run in :brain")

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
grep -F "package: name='io.github.ethanbird.senseime' versionCode='14' versionName='0.3.7-m8'" "$OUT/apk-badging.txt"
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
UNEXPECTED_PERMISSIONS=$(
    grep -Fvx "android.permission.INTERNET" <<<"$DECLARED_PERMISSIONS" || true
)
if [[ -n "$UNEXPECTED_PERMISSIONS" ]]; then
    printf 'Release gate failed: unexpected APK permissions:\n%s\n' \
        "$UNEXPECTED_PERMISSIONS" >&2
    exit 1
fi
unzip -p "$APK" assets/NOTICE.txt | cmp - "$ROOT/NOTICE"
unzip -p "$APK" assets/LICENSE.txt | cmp - "$ROOT/LICENSE"
unzip -p "$APK" assets/RIME-PINYIN-SIMP-LICENSE.txt | cmp - "$ROOT/licenses/rime-pinyin-simp-LICENSE"
unzip -p "$APK" assets/CC-CEDICT-NOTICE.txt | cmp - "$ROOT/licenses/CC-CEDICT-NOTICE.md"
unzip -p "$APK" assets/CC-BY-SA-4.0.txt | cmp - "$ROOT/licenses/CC-BY-SA-4.0.txt"
unzip -p "$APK" assets/POPULAR-ENGLISH-WORDS-ISC.txt | cmp - "$ROOT/licenses/popular-english-words-ISC.txt"
unzip -p "$APK" assets/CHINESE-IDIOM-CHENGYU-MIT.txt | cmp - "$ROOT/licenses/chinese-idiom-chengyu-MIT.txt"
unzip -p "$APK" sense/soul.md | cmp - "$ROOT/ai-brain/src/main/resources/sense/soul.md"
unzip -p "$APK" assets/pinyin_lexicon.bin | sha256sum | awk '{print $1}' | grep -Fx "$LEXICON_SHA256"
unzip -p "$APK" assets/pinyin_bigrams.bin | sha256sum | awk '{print $1}' | grep -Fx "$BIGRAM_SHA256"
unzip -p "$APK" assets/english_lexicon.txt | sha256sum | awk '{print $1}' | grep -Fx "$ENGLISH_SHA256"
unzip -p "$APK" assets/english_lexicon.txt |
    awk '!/^#/ && NF { count++ } END { print count + 0 }' |
    grep -Fx "$ENGLISH_WORD_COUNT"
unzip -l "$APK" \
    assets/NOTICE.txt \
    assets/LICENSE.txt \
    assets/RIME-PINYIN-SIMP-LICENSE.txt \
    assets/CC-CEDICT-NOTICE.txt \
    assets/CC-BY-SA-4.0.txt \
    assets/POPULAR-ENGLISH-WORDS-ISC.txt \
    sense/soul.md \
    assets/pinyin_lexicon.bin \
    assets/pinyin_bigrams.bin \
    assets/english_lexicon.txt | tee "$OUT/apk-attributed-assets.txt"
sha256sum "$APK" | tee "$APK.sha256"

HOME="$ANDROID_USER_HOME" "$SDK/cmdline-tools/latest/bin/lint" \
    --exitcode \
    --sdk-home "$SDK" \
    --compile-sdk-version 36 \
    --kotlin-language-level 2.0 \
    --resources "$ROOT/app/src/main/res" \
    --resources "$ROOT/ime-service/src/main/res" \
    --sources "$ROOT/app/src/main/kotlin" \
    --sources "$ROOT/ime-service/src/main/kotlin" \
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

echo "v0.3.7-m8 verification complete: $APK"
