# JS8Android app architecture

How the Kotlin application layer is put together: the screens, the database, the
foreground service, and the seam between them and the native engine.

This covers `android/app/` only. The C++ side is documented elsewhere:
`docs/backend-refactor-plan.md` for why `libjs8core` was extracted from the Qt
desktop, and `adapters/android/README.md` for the platform adapters that back it.
Build steps are in `android/README.md`.

## The layers

```
core/                     platform-agnostic DSP and protocol, no Qt
  └── adapters/android/   Oboe audio, storage, logging, networking
        └── jni/          js8_engine_jni.cpp, and JS8Engine.kt beside it
              └── android/js8core-lib/   AAR wrapping the .so and the Kotlin API
                    └── android/app/     this document
```

`js8core-lib` has no Kotlin sources of its own. Its Gradle file pulls
`adapters/android/jni/kotlin` and `.../java` into its main source set, so the
JNI wrapper lives next to the C++ it wraps and ships as part of the AAR.

## The three long-lived pieces

**`JS8EngineService`** (`service/`, ~4700 lines) is a foreground service and the
only owner of the native engine. It captures audio, feeds the decoder, receives
decode callbacks, interprets the JS8 protocol, writes to the database, and drives
transmission. It runs whether or not any screen is showing.

**`MainActivity`** (~540 lines) hosts the navigation graph and acts as a broadcast
hub. It listens for what the service emits, forwards it into ViewModels, and
pumps the transmit queue.

**Fragments and ViewModels** (`ui/`) render. ViewModels are scoped to the
activity, not the fragment, so a thread and the list behind it read the same
instance.

The service and the UI never call each other directly. They talk over
`LocalBroadcastManager` with about twenty-six actions declared as constants on
`JS8EngineService`. The service→UI direction carries decodes, spectrum frames,
engine state, TX state and progress, rig status, and time drift. The UI→service
direction carries start, stop, transmit, set frequency, set TX offset, and audio
device switches.

That seam is what lets the service keep decoding with no UI attached. It also
costs something, described under **Transmission** below.

## Data

Room, currently at **version 6**, in `data/`. Five tables:

| Table | What it holds |
|---|---|
| `messages` | Two-party and group threads, keyed by `conversationId` |
| `contacts` | Every station heard, plus the operator's own name, star, and notes |
| `mailbox_messages` | Store-and-forward mail held for **other** stations |
| `mailbox_group_delivery` | Which callsigns have collected which group message |
| `conversation_settings` | Per-thread settings; currently the relay path |

Held mail deliberately does not live in `messages`. A held message has an
originator and a destination and neither one is us, so putting it there would
manufacture phantom rows in the conversation query.

**Migrations are hand-written and destructive fallback is off.** Schemas are
exported to `android/app/schemas/`, and `MigrationTest` (instrumented) runs the
3→4, 4→5 and 5→6 steps against a seeded database. A version gap now crashes on
upgrade rather than silently wiping, which matters once the database holds traffic
we promised a third party we would forward. Migrations exist from version 2
onward; a version 1 database predates the export and would fail.

Repositories (`MessageRepository`, `ContactRepository`, `MailboxRepository`) wrap
the DAOs and move work to `Dispatchers.IO`. The service holds its own repository
instances; ViewModels hold theirs.

One join is done in Kotlin rather than SQL: threads come from `messages` and
names from `contacts`, and `MessagesFragment` hands the adapter a callsign-to-name
map instead of joining the two. `MessageDao.getConversations()` matches
`timestamp = MAX(timestamp)` per conversation and already produces a duplicate row
when two messages share a millisecond, so it was left alone.

## Receiving

```
AudioRecord ──▶ JS8AudioHelper ──▶ engine.submitAudio()
                                        │  (native decode cycle)
                                        ▼
                              CallbackHandler.onDecoded(utc, snr, dt, freq,
                                                        text, type, quality,
                                                        mode, driftMs)
                                        │
                                        ▼
                                 JS8EngineService
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
             broadcastDecode      handleRelayFrame   maybeHandleIncomingMessage
              (waterfall,                             maybeHandleAutoReply
               decode list)                                 │
                                                            ▼
                                            ACTION_MESSAGE_RECEIVED ──▶ MainActivity
                                                            │              │
                                                            ▼              ▼
                                                     notification      Room insert
```

Every decode runs through all three handlers. `type` carries the frame-type bits
the protocol uses: `0b1` first frame, `0b10` last frame, `0b100` data frame. Those
bits are load-bearing — a buffered command's payload arrives across continuation
frames, and telling a first frame from a continuation is what keeps the JNI's
callsign-prefix heuristic from rewriting message bodies.

**Multi-frame reassembly** happens in the service, in two separate buffer maps
keyed by audio offset: `msgBuffers` for `MSG` and `MSG TO:`, `relayBuffers` for
relay (`>`) traffic. Buffers expire on a timeout scaled to the submode's frame
period, because a Slow-mode transmission spaces frames thirty seconds apart and a
flat timeout truncated them.

**Protocol handling** lives in the service too. Directed commands are parsed by
`util/Js8Commands`, which matches longest-name-first so `QUERY MSGS` does not
arrive as `QUERY` and `MSG TO:` does not arrive as `MSG`. The service implements
the store-and-forward mailbox (`MSG TO:` deposits, `QUERY MSGS`, `QUERY MSG {id}`),
relay forwarding and delivery, auto-replies to the query commands, heartbeats, and
ACK handling.

## Transmitting

There are **two** transmit paths, and the difference matters.

**Direct.** `sendAutoReply`, `sendRelayMessage`, and `sendHeartbeat` call
`engine.transmitMessage` from inside the service. These work with no UI attached.

**Queued.** Everything else goes:

```
fragment ──▶ TransmitViewModel.queueMessage()
                     │
                     ▼
            MainActivity.processNextTxIfIdle()
                     │  ACTION_TRANSMIT_MESSAGE
                     ▼
              JS8EngineService ──▶ engine.transmitMessage()
                     │  ACTION_TX_STATE / ACTION_TX_PROGRESS
                     ▼
              MainActivity pumps the next one
```

The service can also *ask* for a transmission with `broadcastQueueTx`, which sends
`ACTION_QUEUE_TX` to the activity and comes back around through the same pump.
Sixteen call sites use it, including every mailbox reply, because delivery is
marked when the transmission finishes and the completion hook lives on the queue.

**Known limitation:** `MainActivity` registers its receivers in `onStart` and
unregisters them in `onStop`. While the app is backgrounded the pump is not
running, so anything routed through `broadcastQueueTx` waits until the app is
foregrounded again. Decoding continues; queued replies do not go out. The queue is
also in memory only, so process death loses it. Both are worth fixing by moving
the pump into the service and the queue into the database.

## Where Kotlin mirrors native tables

Two lookup tables are duplicated from `core/src/protocol/varicode.cpp` into
Kotlin, because crossing JNI for them on the decode hot path was not worth it:

- `util/Js8Commands` mirrors `kDirectedCmds`
- `util/Js8Groups` mirrors `kBaseCalls`

Both have **drift guards**: unit tests that read `varicode.cpp` off disk, extract
the native table, and fail if the Kotlin copy has diverged. A change to the native
vocabulary breaks the build rather than silently producing frames the other end
cannot parse.

## Screens

Four tabs, with six further destinations reached from them. Wide screens in
landscape get a `NavigationRailView` instead of the bottom bar; the code addresses
either through `NavigationBarView`, the shared superclass.

| Destination | Purpose |
|---|---|
| **Monitor** | Waterfall, status strip, and the decode list, which was merged in from its own tab |
| **Messages** | Thread list, plus a pinned All activity row |
| ├ Conversation | One thread; compose bar, relay-path strip, mailbox actions |
| ├ Everything | All band activity as a chat thread |
| ├ Held messages | Mail this station is holding for others |
| ├ Other groups | Group threads the operator has not joined |
| ├ Relay path | Ordered hops for one thread |
| **Contacts** | Every station heard, searchable |
| └ Contact detail | Identity, editable name and notes, relay path, favourite, delete |
| **Settings** | Callsign, grid, audio, rig control, autoreply, mailbox, groups |

The waterfall is a custom `View` with its own renderer (`WaterfallView`,
`WaterfallRenderer`) fed by `ACTION_SPECTRUM`.

## Audio and rig control

Capture is `AudioRecord` through `JS8AudioHelper` at 12 kHz. Transmit audio is
generated natively and played through Oboe in the adapter layer.

Rig control has four backends, all driven from the service: `HamlibRigControl`
(native, USB), `RigCtlClient` (network `rigctld`), `TruSdxDirectSerial`, and
`BluetoothSerialBridge`. `UsbPermissionHelper` handles the Android USB permission
dance.

`PskReporterClient` spots decodes to PSKReporter when enabled.

## Tests

**Unit** (`app/src/test/`, JVM): the protocol vocabulary and its drift guards,
multi-frame assembly, relay path composition, callsign validation, TX message
classification, contact search, display names, avatar colours, PSKReporter
encoding.

**Instrumented** (`app/src/androidTest/`): `MigrationTest` covers every schema
step against a seeded database.

**Engine-level** (`android/js8core-lib/src/androidTest/`): lifecycle, audio
submission, and TX timing against the real native engine.

There is also a **debug-only decode injection path**. A broadcast to
`DebugDecodeReceiver` (debug source set only) feeds synthetic decode text through
the same handler chain a real decode takes, which makes multi-frame commands,
malformed frames, and bad checksums testable on one emulator with no audio. The
receiver does not exist in release builds.

## Known weak points

Recorded here so they are not rediscovered:

- The TX pump depends on `MainActivity` being started, and the queue is in memory.
  See **Transmitting**.
- `MessageDao.getConversations()` emits a duplicate row when two messages in one
  conversation share a millisecond.
- `TransmitViewModel` re-sorts by priority on every add while popping index 0 on
  completion, so a high-priority insert during an airborne send can attribute the
  result to the wrong `dbId`.
- `JS8EngineService` is ~4700 lines and carries audio, rig control, protocol,
  mailbox, and notifications together. The protocol handling is the obvious first
  thing to lift out.
