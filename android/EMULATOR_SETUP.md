# Android emulator setup on this Mac

Reference for the emulator install done on 2026-08-20 (Apple M5, macOS, Homebrew SDK). The emulator gives a fast loop for UI work. Final checks still belong on the Fire HD 10, because the tablet runs 32-bit Fire OS at a different density.

## What is installed

- SDK root: `/opt/homebrew/share/android-commandlinetools`
- Emulator: build 16079175 (`emulator-darwin_aarch64`), in `<SDK>/emulator`
- System image: API 34, `google_apis`, `arm64-v8a`, revision 14, in `<SDK>/system-images/android-34/google_apis/arm64-v8a`
- AVD: `firehd10`, config in `~/.android/avd/firehd10.avd/`

The AVD uses the "10.1in WXGA (Tablet)" profile with these overrides in `config.ini`:

```
hw.lcd.width = 1920
hw.lcd.height = 1200
hw.lcd.density = 240
hw.keyboard = yes
hw.ramSize = 2048
```

This approximates the Fire HD 10 screen (10.1 inch, 1920x1200).

## Why the install was manual

`sdkmanager` corrupts large zip downloads on this machine. It fails with "Error reading Zip content from a SeekableByteChannel". The NDK install hit the same bug earlier. The fix is the same: download the zips from `dl.google.com` directly and unzip them into the SDK.

1. Find the current file names in the repository manifests:
   - Emulator: `https://dl.google.com/android/repository/repository2-3.xml`
   - System images: `https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml`
2. Download and unzip:

   ```sh
   SDK=/opt/homebrew/share/android-commandlinetools
   curl -sSfO https://dl.google.com/android/repository/emulator-darwin_aarch64-16079175.zip
   curl -sSfO https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-34_r14.zip
   unzip -q emulator-darwin_aarch64-16079175.zip -d $SDK
   mkdir -p $SDK/system-images/android-34/google_apis
   unzip -q arm64-v8a-34_r14.zip -d $SDK/system-images/android-34/google_apis
   ```

3. `avdmanager` refuses a manually unzipped emulator with the error `"emulator" package must be installed!`. It wants a `package.xml` next to the binary. This file was written by hand at `<SDK>/emulator/package.xml`:

   ```xml
   <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
   <ns5:repository xmlns:ns5="http://schemas.android.com/repository/android/generic/02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
     <localPackage path="emulator" obsolete="false">
       <type-details xsi:type="ns5:genericDetailsType"/>
       <revision><major>37</major><minor>2</minor><micro>5</micro></revision>
       <display-name>Android Emulator</display-name>
     </localPackage>
   </ns5:repository>
   ```

   Match the revision to `Pkg.Revision` in `<SDK>/emulator/source.properties`. The system image zip already contains its own `package.xml`.

## AVD creation (already done, repeat only if deleted)

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
SDK=/opt/homebrew/share/android-commandlinetools
echo no | $SDK/cmdline-tools/latest/bin/avdmanager create avd -n firehd10 \
  -k "system-images;android-34;google_apis;arm64-v8a" -d "10.1in WXGA (Tablet)"
```

Then apply the `config.ini` overrides listed above. Warnings about `devices.xml` are noise.

## Daily use

Start the emulator:

```sh
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
  /opt/homebrew/share/android-commandlinetools/emulator/emulator -avd firehd10
```

Build and install the app (`-e` targets the emulator when the tablet is also connected):

```sh
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
```

Take a screenshot:

```sh
adb -e exec-out screencap -p > screen.png
```

## Limits

- No audio decode and no radio hardware. The emulator is for layout and interaction work only.
- The image is 64-bit Android 14. The tablet is 32-bit Fire OS. Test release candidates on the tablet.
- The app and its native engine start without problems on the emulator.
