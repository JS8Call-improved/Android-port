# JS8Android

JS8Android is the Android port of JS8Call.

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=flat&logo=buy-me-a-coffee&logoColor=black)](https://www.buymeacoffee.com/punk.kaos)

## Beta status

This port is beta.. Currently RX and TX work, as do basic auto-reply responses.
Network rig control via rigctld works, plus USB rig control via Hamlib. The GUI *will* change. Not
responsible for this code doing unexpected things, draining all of your batteries, or
kidnapping your pets. User beware.

Please open bug reports when you find things wrong, bug reports help me build this app quicker! 

## Android Port Summary

- Extracted a platform-agnostic core engine under `core/` with public headers in
  `core/include` and protocol/DSP implementations in `core/src`.
- Added an Android adapter layer in `adapters/android/` plus a JNI bridge in
  `adapters/android/jni` (native `js8_engine_jni.cpp` with Kotlin/Java wrappers).
- Introduced a Gradle/NDK build in `android/` with the `js8core-lib` AAR module and
  `app` example, including FFTW3 build scripting in `android/build-fftw3.sh`.
- Implemented a foreground decoding service (`JS8EngineService`) and AudioRecord
  capture via `JS8AudioHelper`, with UI updates delivered through local broadcasts.
- Built a Material 3 Android UI (Monitor, Decodes, Transmit, Settings) with
  ViewModel-backed decode buffering for multipart messages.

See `android/README.md` for build and usage details, and
`android/ARCHITECTURE.md` for how the app layer is put together.

## License

JS8Call is licensed under the GPLv3. See [LICENSE](LICENSE) for details.

## History - JS8Call

JS8Call is an experiment in combining the robustness of FT8 (a weak-signal mode by
K1JT) with a messaging and network protocol layer for weak signal communication.
The open source software is designed for connecting amateur radio operators who are
operating under weak signal conditions and offers real-time keyboard-to-keyboard
messaging, store-and-forward messaging, and automatic station announcements.

* July 6, 2017 - The initial idea of using a modification to the FT8 protocol to
  support long-form QSOs was developed by Jordan, KN4CRD, and submitted to the WSJT-X
  mailing list: https://sourceforge.net/p/wsjt/mailman/message/35931540/
* August 31, 2017 - Jordan, KN4CRD, did a little development and modified WSJT-X to
  support long-form QSOs using the existing FT8 protocol:
  https://sourceforge.net/p/wsjt/mailman/message/36020051/ He sent a video example to
  the WSJT-X group: https://widefido.wistia.com/medias/7bb1uq62ga
* January 8, 2018 - Jordan, KN4CRD, started working on the design of a long-form QSO
  application built on top of FT8 with a redesigned interface.
* February 9, 2018 - Jordan, KN4CRD, submitted question to the WSJT-X group to see if
  there was any interest in pursuing the idea:
  https://sourceforge.net/p/wsjt/mailman/message/36221549/
* February 10, 2018 - Jordan KN4CRD, Julian OH8STN, John N0JDS, and the Portable
  Digital QRP group did an experiment using FSQ. The idea of JS8Call, combining FT8,
  long-form QSOs, and FSQCall like features was born.
* February 11, 2018 - Jordan, KN4CRD, inquired about the idea of integrating
  long-form messages into WSJT-X:
  https://sourceforge.net/p/wsjt/mailman/message/36223372/
* February 12, 2018 - Joe Taylor, K1JT, wrote back:
  https://sourceforge.net/p/wsjt/mailman/message/36224507/ saying that "Please don't
  let my comment discourage you from proceeding as you wish, toward something new."
* March 4, 2018 - Jordan, KN4CRD, published a design document for JS8Call:
  https://github.com/jsherer/js8call
* July 6, 2018 - Version 0.0.1 of JS8Call released to the development group
* July 15, 2018 - Version 0.1 released - a dozen testers
* July 21, 2018 - Version 0.2 released - 75 testers
* July 27, 2018 - Version 0.3 released - 150 testers
* August 12, 2018 - Version 0.4 released - ("leaked" on QRZ) - 500 testers
* September 2, 2018 - Version 0.5 released - 3000 testers
* September 14, 2018 - Version 0.6 released - 5000 testers
* October 8, 2018 - Version 0.7 released - 6000 testers, name changed to JS8 & JS8Call
* October 31, 2018 - Version 0.8 released - ~7000 testers
* November 15, 2018 - Version 0.9 released - ~7500 testers
* November 30, 2018 - Version 0.10 released - ~7800 testers
* December 18, 2018 - Version 0.11 released - ~8200 testers
* January 1, 2019 - Version 0.12 released - ~9000 testers
* January 23, 2019 - Version 0.13 released - ~9250 testers
* February 7, 2019 - Version 0.14 released - ~9600 testers
* February 21, 2019 - Version 1.0.0-RC1 released - ~10000 testers
* March 11, 2019 - Version 1.0.0-RC2 released - >10000 testers
* March 26, 2019 - Version 1.0.0-RC3 released - >11000 testers
* April 1, 2019 - Version 1.0.0 general availability - Public Release
* June 6, 2019 - Version 1.1.0 general availability
* November 29, 2019 - Version 2.0.0 general availability - Fast and Turbo speeds introduced
* December 22, 2019 - Version 2.1.0 general availability - Slow speed introduced

## Notice

JS8Call is a derivative of the WSJT-X application, restructured and redesigned for
message passing using a custom FSK modulation called JS8. It is not supported by nor
endorsed by the WSJT-X development group. While the WSJT-X group maintains copyright
over the original work and code, JS8Call is a derivative work licensed under and in
accordance with the terms of the GPLv3 license. The source code modifications are
public and can be found in this repository: https://github.com/js8call/js8call .
