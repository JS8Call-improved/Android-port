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
