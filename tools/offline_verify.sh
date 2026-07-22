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
OUT="$ROOT/build/offline-m3"
APK_DIR="$ROOT/app/build/outputs/apk/offline"
APK="$APK_DIR/Sense-v0.3.0-m3-debug.apk"
BIGRAM_ASSET="$ROOT/ime-service/src/main/assets/pinyin_bigrams.bin"
BIGRAM_SHA256="b45c9fcf271b67c46d7d4d34eccac2bad8805837bbc9b2ad0ce2c719ab922c2a"
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$OUT/android-user-home}

find "$OUT" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "$OUT/core-main" "$OUT/core-test" "$OUT/ui-main" "$OUT/ui-test" "$OUT/generated" "$OUT/app-classes" "$OUT/dex" "$ANDROID_USER_HOME" "$APK_DIR"

python3 "$ROOT/tools/test_build_pinyin_lexicon.py" 2>&1 | tee "$OUT/lexicon-builder-tests.txt"
python3 "$ROOT/tools/test_build_bigram_model.py" 2>&1 | tee "$OUT/bigram-builder-tests.txt"
python3 "$ROOT/tools/build_bigram_model.py" \
    "$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin" \
    "$OUT/pinyin_bigrams.bin" \
    --max-pairs 65536
cmp "$BIGRAM_ASSET" "$OUT/pinyin_bigrams.bin"
printf '%s  %s\n' "$BIGRAM_SHA256" "$BIGRAM_ASSET" | sha256sum -c -

cmp "$ROOT/NOTICE" "$ROOT/ime-service/src/main/assets/NOTICE.txt"
cmp "$ROOT/LICENSE" "$ROOT/ime-service/src/main/assets/LICENSE.txt"
cmp "$ROOT/licenses/rime-pinyin-simp-LICENSE" "$ROOT/ime-service/src/main/assets/RIME-PINYIN-SIMP-LICENSE.txt"
cmp "$ROOT/licenses/CC-CEDICT-NOTICE.md" "$ROOT/ime-service/src/main/assets/CC-CEDICT-NOTICE.txt"
cmp "$ROOT/licenses/CC-BY-SA-4.0.txt" "$ROOT/ime-service/src/main/assets/CC-BY-SA-4.0.txt"

COMPILER_CP=$(find "$KOTLIN_LIB" -maxdepth 1 -name '*.jar' -print | paste -sd: -)
STDLIB="$KOTLIN_LIB/kotlin-stdlib-2.0.21.jar"
JUNIT="$KOTLIN_LIB/junit-4.13.2.jar"
HAMCREST="$KOTLIN_LIB/hamcrest-core-1.3.jar"

mapfile -t CORE_SOURCES < <(find "$ROOT/core-input/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t TEST_SOURCES < <(find "$ROOT/core-input/src/test/kotlin" -name '*.kt' -print | sort)
mapfile -t UI_LAYOUT_SOURCES < <(printf '%s\n' \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyCodes.kt" \
    "$ROOT/ime-ui/src/main/kotlin/io/github/ethanbird/senseime/ui/KeyboardLayoutContract.kt")
mapfile -t UI_TEST_SOURCES < <(find "$ROOT/ime-ui/src/test/kotlin" -name '*.kt' -print | sort)

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
    CORE_TEST_CLASSES+=("io.github.ethanbird.senseime.core.${file_name%.kt}")
done
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/core-main:$OUT/core-test" \
    org.junit.runner.JUnitCore "${CORE_TEST_CLASSES[@]}" | tee "$OUT/unit-tests.txt"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT/ui-main" "${UI_LAYOUT_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/ui-main" \
    -d "$OUT/ui-test" "${UI_TEST_SOURCES[@]}"
UI_TEST_CLASSES=()
for source in "${UI_TEST_SOURCES[@]}"; do
    [[ "$source" == *Test.kt ]] || continue
    file_name=${source##*/}
    UI_TEST_CLASSES+=("io.github.ethanbird.senseime.ui.${file_name%.kt}")
done
java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/ui-main:$OUT/ui-test" \
    org.junit.runner.JUnitCore "${UI_TEST_CLASSES[@]}" | tee "$OUT/ui-unit-tests.txt"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M0HostBenchmark \
    "$ROOT/benchmarks/results/m0-host.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M1PinyinBenchmark \
    "$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin" \
    "$ROOT/benchmarks/results/m1-pinyin.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M2AdaptiveBenchmark \
    "$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin" \
    "$ROOT/ime-service/src/main/assets/pinyin_syllables.txt" \
    "$ROOT/benchmarks/results/m2-adaptive.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M3SentenceBenchmark \
    "$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin" \
    "$BIGRAM_ASSET" \
    "$ROOT/benchmarks/replay/m3-sentences.tsv" \
    "$ROOT/benchmarks/results/m3-sentence.json"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/app-res.zip"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/ime-service/src/main/res" -o "$OUT/ime-service-res.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/tools/offline/AndroidManifest.xml" \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --version-code 5 \
    --version-name 0.3.0-m3 \
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
grep -F "package: name='io.github.ethanbird.senseime' versionCode='5' versionName='0.3.0-m3'" "$OUT/apk-badging.txt"
grep -Fx "minSdkVersion:'29'" "$OUT/apk-badging.txt"
grep -Fx "targetSdkVersion:'36'" "$OUT/apk-badging.txt"
if grep -Fq "android.permission.INTERNET" "$OUT/apk-permissions.txt"; then
    echo "Release gate failed: APK declares android.permission.INTERNET." >&2
    exit 1
fi
unzip -p "$APK" assets/NOTICE.txt | cmp - "$ROOT/NOTICE"
unzip -p "$APK" assets/LICENSE.txt | cmp - "$ROOT/LICENSE"
unzip -p "$APK" assets/RIME-PINYIN-SIMP-LICENSE.txt | cmp - "$ROOT/licenses/rime-pinyin-simp-LICENSE"
unzip -p "$APK" assets/CC-CEDICT-NOTICE.txt | cmp - "$ROOT/licenses/CC-CEDICT-NOTICE.md"
unzip -p "$APK" assets/CC-BY-SA-4.0.txt | cmp - "$ROOT/licenses/CC-BY-SA-4.0.txt"
unzip -p "$APK" assets/pinyin_bigrams.bin | sha256sum | awk '{print $1}' | grep -Fx "$BIGRAM_SHA256"
unzip -l "$APK" \
    assets/NOTICE.txt \
    assets/LICENSE.txt \
    assets/RIME-PINYIN-SIMP-LICENSE.txt \
    assets/CC-CEDICT-NOTICE.txt \
    assets/CC-BY-SA-4.0.txt \
    assets/pinyin_bigrams.bin | tee "$OUT/apk-attributed-assets.txt"
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
    --classpath "$OUT/app-classes" \
    --libraries "$ANDROID_JAR" \
    --text "$OUT/lint.txt" \
    "$ROOT/tools/offline"

echo "M3 verification complete: $APK"
