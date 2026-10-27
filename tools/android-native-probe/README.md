# Android native snapshot probe

This fixture checks the handoff from the public `ControlSession` API through the production Android bridge snapshot and native snapshot parser. It runs i5 and i7 sessions over a controlled transport with fixed literal RX replies, calls public `readSession`, serializes the production `Bundle` through Android `Parcel` bytes, and passes the received `Bundle` to `NativeHeadsetSnapshot.fromBundle`.

The fixture preserves the boundary exercised by the earlier scratch run: i5 `CASE_BATTERY=ff` stays not-provided, known and unknown readings remain distinct, model-specific noise choices and writable capability survive, ANC and transparency level choices retain separate visibility modes, i7 BLE and BR addresses remain separate, and old session references are rejected after disconnect and reconnect. It also verifies that the v3 native parser rejects legacy v1 and v2 snapshots.

## Prerequisites

- Run `./gradlew :core:jvmJar` first so `core/build/libs/core-jvm.jar` exists. The probe build script does not invoke Gradle.
- Use Python 3.11 or newer; the build script reads the version catalog with the standard-library `tomllib` module.
- Set `JAVA_HOME` to JDK 21 or newer. The compiler and signer use that JDK, and the script checks the version before building.
- Set `ANDROID_SDK_ROOT` or `ANDROID_HOME` to an installed Android SDK with a platform and build-tools containing `android.jar`, `d8`, `aapt2`, `zipalign`, and `apksigner`. The script selects the latest installed platform and build-tools; `ANDROID_COMPILE_PLATFORM_DIR` and `ANDROID_BUILD_TOOLS_VERSION` can select specific installed versions.
- Set `GRADLE_USER_HOME` if the Kotlin compiler, Kotlin standard library, coroutines, and compiler dependencies are cached outside `$HOME/.gradle`.
- The versions for Kotlin and coroutines come from `gradle/libs.versions.toml`; the script fails with a direct message when a required cached artifact is missing.

## Build

```bash
JAVA_HOME=/path/to/jdk \
ANDROID_SDK_ROOT=/path/to/android-sdk \
GRADLE_USER_HOME=/path/to/gradle-cache \
tools/android-native-probe/build.sh
```

The build creates its classes, DEX, APK, signing key, and build log under `.scratch/android-native-probe/`. It only builds the APK. It does not inspect, select, install to, or start any device. The manifest declares no permissions and does not use an OEM/system package.

## Optional manual run

Use an API 26 emulator to check the Parcel runtime. Supply both an ADB server port and a device serial explicitly for every command; the commands below never rely on ADB's default server or implicit device selection.

```bash
: "${ADB_SERVER_PORT:?Set the ADB server port explicitly}"
: "${ANDROID_SERIAL:?Set the device serial explicitly}"

adb -P "$ADB_SERVER_PORT" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -P "$ADB_SERVER_PORT" -s "$ANDROID_SERIAL" install -r .scratch/android-native-probe/native-probe.apk
adb -P "$ADB_SERVER_PORT" -s "$ANDROID_SERIAL" shell am start -n io.github.dohex.openrose.nativeprobe/.NativeSnapshotProbeActivity
```

To read only the fixture process log after launch:

```bash
: "${ADB_SERVER_PORT:?Set the ADB server port explicitly}"
: "${ANDROID_SERIAL:?Set the device serial explicitly}"
probe_pid=$(adb -P "$ADB_SERVER_PORT" -s "$ANDROID_SERIAL" shell pidof io.github.dohex.openrose.nativeprobe | tr -d '\r')
adb -P "$ADB_SERVER_PORT" -s "$ANDROID_SERIAL" logcat -d --pid="$probe_pid" -s OpenRoseNativeProbe:I AndroidRuntime:E '*:S'
```

Uninstall the fixture with the same explicit port and serial after the run:

```bash
adb -P "$ADB_SERVER_PORT" -s "$ANDROID_SERIAL" uninstall io.github.dohex.openrose.nativeprobe
```

## Evidence boundary

The controlled transport and literal replies validate the public session → production `Bundle` → real Android `Parcel` → native consumer boundary. They do not exercise a physical Bluetooth controller, a headset, an OEM service, HyperOS/SystemUI, Xposed hooks, or OEM presentation. Passing this probe is not an OEM compatibility result.

An earlier one-off emulator run recorded 50 passing assertions in `.scratch/native-probe/asserted-result.txt`. That record belongs to the prior scratch fixture. This checked-in script is a reusable build entry point; its new APK output under `.scratch/android-native-probe/` is build-only and has not been run on an emulator as part of adding these files.
