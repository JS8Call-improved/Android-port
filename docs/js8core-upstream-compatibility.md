# JS8Core upstream compatibility

## Reviewed revision

- Repository: https://github.com/JS8Call-improved/JS8Core
- Previous pin: `b826cee2a9da99ea9529b78d5be9f731b76c4aa9`
- Updated pin: `feaec6fc757796b58ba3cc26fc6fab573014e2e8`
- Reported library version: `0.1.0`

The version alone does not identify these changes: it remains 0.1.0. Use the
parent repository's `core/` gitlink for exact provenance. Kotlin hosts can call
`JS8Engine.coreVersion()` to identify the linked library's release version.
Rebuild the AAR and native consumers together; upstream's appended configuration
and event fields are source-compatible, not a promise of binary compatibility.

## Android receive policy

Upstream now defaults to frequency/timing tracking and guarded trajectory-aware
interference cancellation. Android deliberately retains the historical decoder
until target-device validation:

- `symbolTracking = JS8Engine.SymbolTracking.DISABLED`
- `trackedSic = false`
- `optionalDecodeBudgetMs = 250`

Existing source calls to `JS8Engine.create()` keep working. The wrapper's native
creation signature has changed, so do not mix an old wrapper with a new `.so`.
The experimental paths can be selected at engine creation:

```kotlin
val engine = JS8Engine.create(
    callbackHandler = callbacks,
    symbolTracking = JS8Engine.SymbolTracking.BOTH,
    trackedSic = true,
    optionalDecodeBudgetMs = 250
)
```

Tracking modes are `DISABLED`, `FREQUENCY`, `TIMING`, and `BOTH`. Cancellation
requires `BOTH`. Settings require engine recreation, not a stop/start. The
optional budget includes queue wait, mandatory decoding, and callback time;
zero disables optional work without disabling mandatory decoding. The wrapper
accepts 0..10000 ms. This is cooperative admission, not a hard real-time bound.

For diagnostics, implement `JS8Engine.ReceiveDiagnosticsHandler` instead of just
`CallbackHandler`. Its default/no-op diagnostic methods run immediately before
the corresponding existing callback on the same native thread:

- `onDecodedDiagnostics(trackingUsed, afterTrackedSic)`
- `onDecodeFinishedDiagnostics(count, trackedCancellations, optionalWorkSkipped, canceled)`

Existing callback implementations do not need these methods. Keep callbacks
nonblocking. The core suppresses stale-context events and may suppress their
completion as well; `canceled` is not a guarantee that a stopped RX context will
deliver a final notification.

## Compatibility checks

`tools/jsc_codeword_parity_test.cpp` now reads dictionary entries through
`jsc_word(index)` rather than the removed internal `JSC_MAP`. It still checks
both separator values for all 262,144 codewords and verifies every decoded
dictionary entry. Upstream's own dictionary digest test additionally checks
content and index ordering.

`scripts/test-core.sh` explicitly builds every executable needed by upstream
CTest, including the optional Python-driven WAV test's executable. Use a fresh
directory when validating an upgrade:

```sh
./scripts/test-core.sh /path/to/new/build-directory
```

Android CI explicitly watches the `core` gitlink as well as `core/**`, and builds
the instrumentation APK so wrapper/test compilation regressions are caught even
when no device is available in CI.

From `android/`, with Java 17 and the Android SDK configured:

```sh
./gradlew :js8core-lib:assembleDebug :js8core-lib:assembleRelease \
  :js8core-lib:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease test
./gradlew :js8core-lib:connectedDebugAndroidTest
```

The library builds both `arm64-v8a` and `armeabi-v7a`.
`JS8EngineReceiveTrackingTest` covers the linked version, invalid budgets,
mandatory reference-frame preservation, zero-budget denial, diagnostic delivery,
and positive tracked/cancellation recovery from independently synthesized
impaired Ultra audio. It uses a seed pool because Gaussian implementations and
numeric behavior differ across platforms. A positive test requires actual
CRC-valid frames and a cancellation follow-up, not just enabled settings.
Existing tests continue exercising hosts that implement only `CallbackHandler`.

The positive recovery tests use a 10000-ms optional budget to separate correctness
from target-device performance. They are not measurements of the production
250-ms budget.

## Validation status and release gates

Initial host validation on macOS/AppleClang:

- Fresh Release build: **35/35 CTest tests passed**.
- Exhaustive parity: **524,288/524,288 bit comparisons**, no mismatches.
- Dictionary text: **262,144/262,144 matches**.
- Debug/Release AARs and app APKs built for both ARM ABIs; instrumentation APK
  compiled using Java 17 and the installed NDK **25.1.8937393**. CI's recommended
  NDK 26.1 configuration still needs its CI run. Host app unit tests:
  **46/46 in Debug and 46/46 in Release**.
- All **23 JNI registration descriptors** matched the compiled Kotlin methods;
diagnostic callback descriptors were checked too.
- The Kotlin impairment generator's eight clips were also submitted to the host
  streaming engine with the same timing geometry: tracked strong frames were
  recovered for all eight seeds, and cancellation recovered the second payload
  for three seeds. This validates test stimulus on the host, not Android execution.

Device execution remains required before changing Android's defaults:

1. Run the full instrumentation suite on both ARM ABIs where hardware is available.
2. Check lifecycle, reference recordings, loopback, Turbo, and TX timing/PTT.
3. Measure sustained MULTI reception at the production budget on slower 32-bit
   hardware as well as 64-bit hardware: CPU, thermal behavior, decode latency,
   audio continuity, and UI responsiveness.
4. Exercise timestamp gaps and stop/restart under load; verify stale work does
   not leak into a fresh session.
5. Collect held-out recorded-HF/overlap and physical RF evidence before claiming
   sensitivity improvements or enabling tracking/cancellation for releases.

Do not treat native synthetic successes or an APK build as device/RF validation.
Upstream documents its own limits in `core/docs/symbol-tracking-validation.md`.

## Attribution

The updated upstream includes additional copyright/provenance notices and a BSD
3-Clause CRC++ notice. The library's `coreNotices` Gradle task packages upstream
`LICENSE`, `NOTICE`, and the CRC++ header containing its notice under
`assets/licenses/js8core/` in the AAR and consuming APK. This preserves those
notices; it does not replace the application's existing licensing obligations
or constitute a complete third-party licensing audit.
