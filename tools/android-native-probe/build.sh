#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
output_dir=$repo_root/.scratch/android-native-probe
versions_file=$repo_root/gradle/libs.versions.toml

read_catalog_version() {
  python3 - "$versions_file" "$1" <<'PY'
import sys
import tomllib
from pathlib import Path

catalog = tomllib.loads(Path(sys.argv[1]).read_text())
print(catalog["versions"][sys.argv[2]])
PY
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_home=$JAVA_HOME
else
  java_path=$(command -v java) || fail 'Set JAVA_HOME or put java on PATH.'
  java_home=$(dirname -- "$(dirname -- "$(realpath -- "$java_path")")")
fi
[[ -x "$java_home/bin/java" ]] || fail "No java executable under JAVA_HOME=$java_home"
[[ -x "$java_home/bin/keytool" ]] || fail "No keytool executable under JAVA_HOME=$java_home"
java_version=$("$java_home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*".*/\1/p')
[[ "$java_version" =~ ^[0-9]+ ]] || fail "Could not determine JDK version under JAVA_HOME=$java_home"
(( java_version >= 21 )) || fail "JDK 21 or newer is required; found JDK $java_version"
export JAVA_HOME=$java_home

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME:-}/Android/Sdk}}
[[ -d "$sdk_root" ]] || fail 'Set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK.'
gradle_user_home=${GRADLE_USER_HOME:-${HOME:-}/.gradle}
cache_root=$gradle_user_home/caches/modules-2/files-2.1

kotlin_version=$(read_catalog_version kotlin)
coroutines_version=$(read_catalog_version coroutines)

latest_directory() {
  local parent=$1
  local pattern=$2
  find "$parent" -mindepth 1 -maxdepth 1 -type d -name "$pattern" -print 2>/dev/null | sort -V | tail -n 1 || true
}

build_tools_version=${ANDROID_BUILD_TOOLS_VERSION:-}
if [[ -n "$build_tools_version" ]]; then
  build_tools_dir=$sdk_root/build-tools/$build_tools_version
else
  build_tools_dir=$(latest_directory "$sdk_root/build-tools" '[0-9]*')
fi
[[ -n "$build_tools_dir" && -x "$build_tools_dir/d8" ]] || fail 'No Android build-tools with d8 found; set ANDROID_BUILD_TOOLS_VERSION if needed.'

platform_dir=${ANDROID_COMPILE_PLATFORM_DIR:-}
if [[ -n "$platform_dir" ]]; then
  [[ "$platform_dir" = /* ]] || platform_dir=$sdk_root/platforms/$platform_dir
else
  platform_dir=$(latest_directory "$sdk_root/platforms" 'android-*')
fi
android_jar=$platform_dir/android.jar
[[ -f "$android_jar" ]] || fail 'No Android platform android.jar found; set ANDROID_COMPILE_PLATFORM_DIR if needed.'

artifact_jar() {
  local group=$1 artifact=$2 version=$3
  local artifact_dir=$cache_root/$group/$artifact/$version
  local -a jars=()
  shopt -s nullglob
  jars=("$artifact_dir"/*/"$artifact-$version.jar")
  shopt -u nullglob
  [[ ${#jars[@]} -gt 0 ]] || fail "Missing cached $group:$artifact:$version; populate the Gradle cache first."
  printf '%s\n' "${jars[0]}"
}

kotlin_stdlib=$(artifact_jar org.jetbrains.kotlin kotlin-stdlib "$kotlin_version")
coroutines_jar=$(artifact_jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm "$coroutines_version")
compiler=$(artifact_jar org.jetbrains.kotlin kotlin-compiler-embeddable "$kotlin_version")
script_runtime=$(artifact_jar org.jetbrains.kotlin kotlin-script-runtime "$kotlin_version")
daemon=$(artifact_jar org.jetbrains.kotlin kotlin-daemon-embeddable "$kotlin_version")

latest_cached_jar() {
  local artifact_dir=$1 pattern=$2
  find "$artifact_dir" -mindepth 3 -maxdepth 3 -type f -name "$pattern" ! -name '*sources.jar' -print 2>/dev/null | sort -V | tail -n 1 || true
}

reflect_jar=$(latest_cached_jar "$cache_root/org.jetbrains.kotlin/kotlin-reflect" 'kotlin-reflect-*.jar')
annotations_jar=$(latest_cached_jar "$cache_root/org.jetbrains/annotations" 'annotations-*.jar')
[[ -f "$reflect_jar" ]] || fail 'Missing cached Kotlin reflect jar required by the compiler.'
[[ -f "$annotations_jar" ]] || fail 'Missing cached JetBrains annotations jar required by the compiler.'
compiler_classpath=$compiler:$kotlin_stdlib:$script_runtime:$daemon:$reflect_jar:$annotations_jar:$coroutines_jar

core_jar=$repo_root/core/build/libs/core-jvm.jar
[[ -f "$core_jar" ]] || fail 'Missing core/build/libs/core-jvm.jar; run ./gradlew :core:jvmJar first.'

mkdir -p "$output_dir"
rm -rf "$output_dir/classes" "$output_dir/dex"
mkdir -p "$output_dir/classes" "$output_dir/dex"
build_log=$output_dir/build.log
: > "$build_log"
printf 'Kotlin %s; coroutines %s\nSDK: %s\nBuild tools: %s\n' \
  "$kotlin_version" "$coroutines_version" "$platform_dir" "$build_tools_dir" >> "$build_log"

"$java_home/bin/java" -cp "$compiler_classpath" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$android_jar:$kotlin_stdlib:$coroutines_jar:$core_jar" \
  -d "$output_dir/classes" \
  "$repo_root/androidApp/src/main/kotlin/io/github/dohex/openrose/bridge/BridgeTarget.kt" \
  "$repo_root/androidApp/src/main/kotlin/io/github/dohex/openrose/bridge/BridgeWire.kt" \
  "$repo_root/androidApp/src/main/kotlin/io/github/dohex/openrose/bridge/BridgeSnapshotCodec.kt" \
  "$repo_root/androidApp/src/main/kotlin/io/github/dohex/openrose/integration/NativeHeadsetSnapshot.kt" \
  "$script_dir/src/io/github/dohex/openrose/nativeprobe/NativeSnapshotProbeActivity.kt" \
  >> "$build_log" 2>&1

mapfile -d '' probe_classes < <(find "$output_dir/classes" -name '*.class' -print0)
"$build_tools_dir/d8" --min-api 26 --lib "$android_jar" \
  --output "$output_dir/dex" "${probe_classes[@]}" "$core_jar" "$coroutines_jar" "$kotlin_stdlib" \
  >> "$build_log" 2>&1

"$build_tools_dir/aapt2" link -I "$android_jar" \
  --manifest "$script_dir/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 36 \
  -o "$output_dir/unsigned.apk" >> "$build_log" 2>&1

python3 - "$output_dir/unsigned.apk" "$output_dir/dex/classes.dex" <<'PY'
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1], 'a') as apk:
    apk.write(sys.argv[2], 'classes.dex')
PY

keystore=$output_dir/native-probe.keystore
if [[ ! -f "$keystore" ]]; then
  "$java_home/bin/keytool" -genkeypair \
    -keystore "$keystore" \
    -storepass android -keypass android -alias native-probe \
    -dname CN=OpenRoseNativeSnapshotProbe -keyalg RSA -keysize 2048 -validity 3650 \
    >> "$build_log" 2>&1
fi
"$build_tools_dir/zipalign" -f 4 "$output_dir/unsigned.apk" "$output_dir/aligned.apk" >> "$build_log" 2>&1
"$build_tools_dir/apksigner" sign \
  --ks "$keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias native-probe \
  --out "$output_dir/native-probe.apk" "$output_dir/aligned.apk" \
  >> "$build_log" 2>&1
"$build_tools_dir/apksigner" verify "$output_dir/native-probe.apk" >> "$build_log" 2>&1

printf 'Built API 26 probe APK: %s\nBuild log: %s\n' \
  "$output_dir/native-probe.apk" "$build_log"
