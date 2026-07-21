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
OUT="$ROOT/build/offline-m1"
APK_DIR="$ROOT/app/build/outputs/apk/offline"
APK="$APK_DIR/Sense-v0.1.0-m1-debug.apk"
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$OUT/android-user-home}

find "$OUT" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "$OUT/core-main" "$OUT/core-test" "$OUT/generated" "$OUT/app-classes" "$OUT/dex" "$ANDROID_USER_HOME" "$APK_DIR"

COMPILER_CP=$(find "$KOTLIN_LIB" -maxdepth 1 -name '*.jar' -print | paste -sd: -)
STDLIB="$KOTLIN_LIB/kotlin-stdlib-2.0.21.jar"
JUNIT="$KOTLIN_LIB/junit-4.13.2.jar"
HAMCREST="$KOTLIN_LIB/hamcrest-core-1.3.jar"

mapfile -t CORE_SOURCES < <(find "$ROOT/core-input/src/main/kotlin" -name '*.kt' -print | sort)
mapfile -t TEST_SOURCES < <(find "$ROOT/core-input/src/test/kotlin" -name '*.kt' -print | sort)

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT/core-main" "${CORE_SOURCES[@]}"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib -no-reflect \
    -classpath "$STDLIB:$JUNIT:$HAMCREST:$OUT/core-main" \
    -d "$OUT/core-test" "${TEST_SOURCES[@]}"

java -cp "$STDLIB:$JUNIT:$HAMCREST:$OUT/core-main:$OUT/core-test" \
    org.junit.runner.JUnitCore \
    io.github.ethanbird.senseime.core.InputReducerTest \
    io.github.ethanbird.senseime.core.FakeDecoderTest \
    io.github.ethanbird.senseime.core.PinyinDecoderTest | tee "$OUT/unit-tests.txt"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M0HostBenchmark \
    "$ROOT/benchmarks/results/m0-host.json"

java -cp "$STDLIB:$OUT/core-main" \
    io.github.ethanbird.senseime.core.M1PinyinBenchmark \
    "$ROOT/ime-service/src/main/assets/pinyin_lexicon.bin" \
    "$ROOT/benchmarks/results/m1-pinyin.json"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/app-res.zip"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/ime-service/src/main/res" -o "$OUT/ime-service-res.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/tools/offline/AndroidManifest.xml" \
    --min-sdk-version 29 \
    --target-sdk-version 36 \
    --version-code 2 \
    --version-name 0.1.0-m1 \
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
"$BUILD_TOOLS/aapt2" dump badging "$APK" | tee "$OUT/apk-badging.txt"
"$BUILD_TOOLS/aapt2" dump permissions "$APK" | tee "$OUT/apk-permissions.txt"
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

echo "M1 verification complete: $APK"
