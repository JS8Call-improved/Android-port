# JS8Android Build Notes

This directory contains the Android Gradle project for JS8Android.

## Prerequisites

- Android Studio or command line tools
- Android SDK + NDK (26.1.10909125 recommended)
- Java 17

## Build FFTW3 (required)

From the repo root:

```bash
cd android
./build-fftw3.sh
```

This writes prebuilt FFTW3 libraries under `android/libs/fftw3/`.

## Build Hamlib (required for USB rig control)

From the repo root:

```bash
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/26.1.10909125
./android/hamlib/build-hamlib-android.sh
```

This writes static Hamlib libraries under `android/libs/hamlib/<abi>/`.

## Build Debug APK

From the repo android folder:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/`

## Run the tests

Unit tests run on the host. From the repo android folder:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:testDebugUnitTest
```

Report: `android/app/build/reports/tests/testDebugUnitTest/`

The engine tests are instrumented and need a device or emulator attached:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=~/Library/Android/sdk ./gradlew :js8core-lib:connectedDebugAndroidTest
```

Report: `android/js8core-lib/build/reports/androidTests/connected/`

To run one class:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=~/Library/Android/sdk ./gradlew :js8core-lib:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.js8call.core.JS8EngineLoopbackTest
```

## Inject decodes

On a station with a rig, turn off Enable Autoreply and Enable Relay in Settings first. Both act on injected decodes like real ones, and a reply queued with monitoring off goes out at the next start.

Debug builds take synthetic decodes over adb, no audio needed. With the app in front, and your callsign from Settings in place of `N0CALL`:

```bash
adb shell am broadcast -n com.js8call.example/.debug.DebugDecodeReceiver --es text "'K1ABC: N0CALL SNR?'"
```

Optional extras:

- `--ei snr -12` (default -10)
- `--ef freq 1450` audio offset in Hz (default 1500)
- `--ei mode 2` submode: 0 Normal, 1 Fast, 2 Turbo, 4 Slow (default 0)
- `--ei type 1` frame flags, added together: 1 first, 2 last, 4 data (default 3, a single frame)

A multi-frame message is one broadcast per frame, back to back, at the same `freq`. The frames after a `MSG` header are data frames: 4 in the middle, 6 last.

```bash
adb shell am broadcast -n com.js8call.example/.debug.DebugDecodeReceiver --es text "'K1ABC: N0CALL MSG HELLO'" --ei type 1
adb shell am broadcast -n com.js8call.example/.debug.DebugDecodeReceiver --es text "' WORLD'" --ei type 6
```

Output: the Decodes tab, and Messages for a complete `MSG` to your callsign. Log: `adb logcat -s JS8EngineService DebugDecodeReceiver`

## Build Release APK (signed)

Create a keystore once:

```bash
keytool -genkeypair -v -keystore android/release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias js8
```

Create `android/keystore.properties`:

```properties
storeFile=release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=js8
keyPassword=YOUR_KEY_PASSWORD
```

Build:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/`

## Build Release Bundle (AAB)

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:bundleRelease
```

Output: `android/app/build/outputs/bundle/release/`
