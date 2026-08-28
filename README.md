# karoo-insta360 (standalone test app)

A minimal Android app to test BLE start/stop recording control of an Insta360
Ace Pro, before wiring the same logic into a proper Karoo (karoo-ext)
extension.

## Why standalone first

This is deliberately **not** a karoo-ext extension yet. The Karoo runs stock
Android and accepts sideloaded APKs, so this app can be built, sideloaded,
and tested against real Ace Pro hardware immediately — proving out the BLE
protocol layer (the actual unknown) before adding karoo-ext's extension
lifecycle and in-ride UI on top.

Once `Insta360BleClient` is confirmed working against your camera, the same
class drops into a `KarooExtension` service with minimal changes — the BLE
logic doesn't change, only what triggers `startCapture()`/`stopCapture()`
(a UI button here vs. a Karoo in-ride action/data field later).

## Project structure

```
app/
  build.gradle.kts
  src/main/
    AndroidManifest.xml
    kotlin/com/example/karooinsta360/
      MainActivity.kt        # scan / connect / start / stop UI
      Insta360BleClient.kt   # BLE protocol client (Architecture B, Header16)
    res/
      layout/activity_main.xml
      values/strings.xml
build.gradle.kts
settings.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.properties
```

## Build

1. Open this folder in Android Studio (it will generate the Gradle wrapper
   JAR automatically on first sync), **or** run `gradle wrapper` once if you
   have Gradle installed locally, then use `./gradlew`.
2. Build a debug APK:
   ```
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

## Sideload onto the Karoo

Same process used for Karoo extensions generally (see
[DC Rainmaker's sideloading guide](https://www.dcrainmaker.com) or the
[awesome-karoo](https://github.com/timklge/awesome-karoo) README for
step-by-step instructions):

- **Karoo 3**: use Hammerhead's official sideloading procedure.
- **Karoo 2**: `adb install app-debug.apk` after enabling sideloading.

You can also just install the APK on any Android phone first if you want to
sanity-check the BLE flow before testing on the Karoo itself — the code
doesn't depend on Karoo-specific hardware.

## Test flow

1. Launch the app.
2. Tap **Scan for Camera** — grant Bluetooth permissions when prompted. The
   scan looks for BLE advertisements with a name starting `Ace Pro` and
   auto-fills the address field when found. (Make sure the camera's BLE is
   discoverable — check the Ace Pro's Bluetooth/pairing menu.)
3. Tap **Connect**. Status should change to "Connected" once BE80 service
   discovery and notification setup succeed.
4. Tap **Start Recording** / **Stop Recording** and watch the camera and the
   on-screen response/error output.

## Known unknowns going in

- `START_CAPTURE`/`STOP_CAPTURE` are sent with an **empty payload**, per
  `insta360ctl`'s protocol docs (which list the payload as "optional"). If
  the Ace Pro rejects an empty body, `onCommandResponse`/`onError` should
  surface a `500`-style failure — that's the signal a minimal protobuf
  payload is needed instead.
- The BLE advertised name prefix (`"Ace Pro"`) is inferred from the pattern
  used by other models in `insta360ctl`'s docs, not confirmed for the Ace
  Pro specifically — if scanning doesn't find it, check the actual
  advertised name (e.g. via a generic BLE scanner app) and adjust
  `NAME_PREFIX` in `MainActivity.kt`.

## Debugging log

**2026-08-26 — "Connected" but Start/Stop Recording does nothing.**

Root cause found by diffing against `xaionaro-go/insta360ctl`'s actual Go
implementation (not just its docs): `Insta360BleClient.sendCommand()` was
writing to BE81 with `WRITE_TYPE_DEFAULT` (ATT "Write Request" — write with
response). The reference implementation's `sendCommandHeader16` — the code
path it uses for X3/ONE RS/Ace Pro-family cameras — always writes with
`withResponse=false` (ATT "Write Command" — write without response), with no
exceptions anywhere in the codebase.

BE81 advertises both `Write` and `WriteNR` properties, so a Write Request
still succeeds at the Android/GATT level (`onCharacteristicWrite` fires with
`GATT_SUCCESS`) even when the camera firmware's internal command dispatcher
is only wired to Write Command events — which reproduces this symptom
exactly: BLE-level "connected", the write appears to succeed, nothing happens
on camera, no error anywhere. Fixed by switching to
`WRITE_TYPE_NO_RESPONSE`. Also added a log line dumping BE81's actual GATT
properties on connect, for future hardware debugging.

**Update — write-type fix ruled out, reverted.** Logcat from a real test
showed `writeCharacteristic()` returning `true` and `onCharacteristicWrite`
succeeding on *every* Start Recording tap (4 attempts, sequence numbers
incrementing correctly) — with zero response, zero notification, and zero
camera reaction each time. Also: `BE81 properties=0xa` = `WRITE(0x08) |
READ(0x02)` only — this camera's BE81 does **not** advertise `WriteNR`
(`0x04`) at all, so `WRITE_TYPE_NO_RESPONSE` was requesting an ATT operation
the characteristic never declared support for (Android reports local
success regardless; the camera's BLE stack likely just drops it). Reverted
to `WRITE_TYPE_DEFAULT`, which matches this device's actual properties —
but since the *original* bug report (before any write-type change) already
showed the identical "connected, write looks fine, nothing happens" symptom
under `WRITE_TYPE_DEFAULT`, write type was never the real cause either way.

Also notable: this camera identifies over BLE as **"Ace Pro 2"**, not "Ace
Pro" — a model `insta360ctl` has no specific entry for at all (its model
table only has `ModelAcePro`, matched by the `"Ace Pro "` prefix, which
`"Ace Pro 2 ..."` happens to also match — likely misdetection on its side,
not confirmation that Ace Pro 2 behaves identically).

**Current hypothesis: authorization/pairing handshake.**
`insta360ctl`'s `Device.Init()` only runs `syncHandshake()` + `authorize()`
(`CHECK_AUTHORIZATION` / cmd `0x27`, falling back to `REQUEST_AUTHORIZATION`
/ cmd `0x56` which requires a physical button press/confirmation on the
camera) for Go2BlePacket-format cameras (GO 2/GO 3) — skipped entirely for
Header16-format cameras (X3/ONE RS/Ace Pro) in that code path. But the
doc's "Key Implementation Notes" state authorization is required generally,
and every verified example in the docs is tagged "Verified: GO 3" — the
Header16/Ace Pro path looks under-tested by the reference project itself,
not confirmed auth-free, and a newer model (Ace Pro 2) is a good candidate
to actually enforce it. Total silence on every command — no error response
at all — matches exactly what the docs describe happening when a
connection isn't authorized/synced.

Added a **Check Authorization** button that sends `CHECK_AUTHORIZATION`
(protobuf `CheckAuthorization{authorization_id: <BLE MAC>, initiator_type:
APP}`, cmd `0x27`) using a small hand-rolled protobuf writer (see
`ProtoWriter` in `Insta360BleClient.kt` — field numbers taken from
`insta360ctl`'s generated `authorization.pb.go`, no protobuf runtime
dependency added). `checkAuthorization()`/`requestAuthorization()` are
implemented; `onCommandResponse`/`onNotification` logging now includes the
raw hex payload so the response (if any) can actually be inspected.

**Next test:** connect, tap **Check Authorization**, capture logcat. Three
outcomes to distinguish:
1. Still total silence → authorization isn't the blocker either; look
   elsewhere (wrong service discovered, need to bond/pair at the OS
   level first, wrong sequence-number handling, etc).
2. A response comes back (`CheckAuthorizationResp`, status field 1) →
   parse the status; if "not authorized", call `requestAuthorization()`
   next, which should trigger a prompt on the camera itself requiring
   physical confirmation.
3. An error/rejection comes back → that's actually progress — means the
   camera's command dispatcher is alive and reachable, just rejecting
   this specific command, which narrows things further.

**Root cause found — via a real BLE HCI snoop capture of the official
Insta360 app.** Captured `adb bugreport` while running the official app
through connect → browse → (likely) a shutter action on this camera, then
extracted `btsnoop_hci.log` and parsed it with `tshark`. Every one of 6
observed "Message"-format commands (`GetOptions` x4, `CheckAuthorization`,
one unidentified `cmd=225`) had the field at header offset 0 exactly equal
to `16 + payload_length`, encoded as a **4-byte little-endian uint32**:

```
cmd=8  (GetOptions)         total_size=25 = 16-byte header + 9-byte payload
cmd=8  (GetOptions)         total_size=51 = 16-byte header + 35-byte payload
cmd=8  (GetOptions)         total_size=44 = 16-byte header + 28-byte payload
cmd=39 (CheckAuthorization) total_size=56 = 16-byte header + 40-byte payload
cmd=8  (GetOptions)         total_size=22 = 16-byte header + 6-byte payload
cmd=225                     total_size=27 = 16-byte header + 11-byte payload
```

Our encoding (and the reference project's own docs, for "Header16" cameras)
instead treated offset 0 as a 2-byte **payload-length-only** field with
offset 2-3 reserved as zero. For every command we'd sent so far — all
empty-payload (`START_CAPTURE`, `STOP_CAPTURE`, `CHECK_AUTHORIZATION`) —
this meant we wrote `00 00 00 00` into that field, declaring the frame as
**zero bytes total**, instead of the correct `10 00 00 00` (16). The
camera's parser almost certainly discarded every one of these as
empty/invalid on the spot — fully explaining the total silence across every
write-type, command-code, and authorization variation tried so far,
regardless of any of those other factors. Fixed in `sendCommand()` /
`handleIncoming()` (now `writeU32LE`/`readU32LE` at offset 0).

Also observed in the capture, not yet resolved: a distinct short (7-byte)
non-16-byte-header frame format `FF <msgtype> <cmd> <size_lo> <size_hi>
[data...]` used at least once (`FF 0C 01 01 00 00 CC`), separate from the
`Message` format above — this loosely matches the docs' "Simplified FF-Frame
Packet" used for things like wake-up authorization, but byte 2 (`0x01`)
doesn't match the doc's specific wake-up-auth example (`0x02`). Not
confirmed what triggered it in the capture (could be the actual
shutter/record action, or something unrelated like a wake/heartbeat
variant). Also observed: 7-byte periodic notifications (`07 00 00 00 05
00 00`) arriving roughly once per second — a heartbeat/keepalive, now
handled as benign rather than logged as an error.

**RESOLVED (2026-08-27).** Rebuilt with the header size-field fix — Start
Recording and Stop Recording both now work against the real Ace Pro 2. The
`total_inner_size` (uint32 LE @ offset 0) bug was the entire problem; write
type, command-code framing, and authorization were all fine as originally
written (this app never actually needed `CHECK_AUTHORIZATION` — the
Header16-format/no-auth assumption from `insta360ctl`'s code held up).
`checkAuthorization()`/`requestAuthorization()` are left in place in
`Insta360BleClient.kt` since they're harmless and may be useful later (e.g.
if a firmware update changes this), but aren't required for basic
start/stop control.

BLE protocol layer is now confirmed working standalone. Ready to move on to
the karoo-ext migration below.

## karoo-ext integration (2026-08-27)

Rather than a separate `karoo-ext-template`-based project, the extension was
added directly into this app alongside the standalone test UI — same APK,
same `Insta360BleClient.kt` (unchanged), one extra Gradle dependency and a
new `extension/` package:

```
app/src/main/kotlin/com/example/karooinsta360/
  extension/
    Insta360Extension.kt      # KarooExtension service — auto-connects, handles BonusAction
    RecordingStateDataType.kt # optional data field: 1 while recording, 0 when idle
  MainActivity.kt              # unchanged — still useful standalone for debugging
  Insta360BleClient.kt         # unchanged
app/src/main/res/xml/extension_info.xml   # declares the extension + BonusAction + DataType
```

**Update (2026-08-27) — switched from a controller button to an automatic
heart-rate/power trigger.** The original plan used a `BonusAction`
("Toggle Camera Recording") bound to a physical controller button, since
the karoo-ext SDK's graphical data fields (`DataTypeImpl.startView`,
backed by `RemoteViews`/Glance) are display-only — nothing in the SDK or
its sample app wires up a click handler on one, and `BonusAction`'s own
doc comment says it's meant to be assigned to a controller (e.g. a SRAM
AXS shifter). That still works, but the current approach replaces it
entirely: `Insta360Extension` now watches heart rate or power (your
choice) via `KarooSystemService.streamDataFlow(...)` and starts/stops
recording automatically based on sustained threshold crossings — no
button press needed at all. There's no `BonusAction`/`onBonusAction` in
the extension anymore.

**What it does:**
- On service creation, scans for a BLE device whose advertised name starts
  with `"Ace Pro"` (same match used by `MainActivity`) and connects
  automatically — no manual address entry, since there's no extension UI
  for it. Retries every 5s if Bluetooth isn't on yet or the camera isn't
  found, and again on disconnect.
- Subscribes to the Karoo's live heart-rate or power stream (configurable —
  see below) and runs a simple debounce state machine:
  - **Not recording:** once the value is `>=` the threshold, a timer
    starts; if it stays `>=` threshold continuously for the configured
    "start" duration, `startCapture()` fires.
  - **Recording:** once the value drops `<` the threshold, a timer starts;
    if it stays below continuously for the configured "stop" duration,
    `stopCapture()` fires.
  - A momentary sensor dropout (`StreamState.Idle`/`Searching`/
    `NotAvailable` — e.g. a flaky HR strap) is ignored rather than treated
    as "below threshold", so it can't accidentally cut a recording short.
  - State tracked optimistically (the camera doesn't send a parsed ack for
    these commands yet).
- Also publishes a plain numeric data field (`recording_state`: `1`/`0`)
  you can drop onto a ride page for visual confirmation, since there's no
  other in-ride feedback that a trigger actually fired.

**Configuring the trigger:** open the app (`MainActivity`) — there's now
an "Auto-Record Trigger" section at the bottom with a Heart Rate/Power
choice, a threshold field, and two duration fields (seconds above
threshold before starting, seconds below before stopping). Tap **Save
Trigger Settings**; the extension picks up the change immediately via a
`SharedPreferences` listener — no rebuild, reinstall, or Karoo reboot
needed for a settings change (only for code changes). Defaults: Heart
Rate, 140bpm, 5s to start, 30s to stop — treat these as placeholders, not
tuned values.

**Setup required before building:**
1. `karoo-ext` is published only to GitHub Packages, which requires
   authentication to download even though the repo is public. Create a
   GitHub personal access token (classic, `read:packages` scope) and add to
   `~/.gradle/gradle.properties` (your global one, **not** this project's —
   so it never gets committed):
   ```
   gpr.user=<your GitHub username>
   gpr.key=<your token>
   ```
2. `./gradlew assembleDebug` and sideload as before (see Build/Sideload
   above) — same APK now also registers as a Karoo extension.
3. Launch the app once after installing so the Bluetooth permission prompt
   fires (a bound Service can't request runtime permissions itself). The
   background scan/connect starts from then on without needing the app
   open.
4. Open the app once, go to **Auto-Record Trigger**, set your metric,
   threshold, and durations, and tap **Save Trigger Settings**. No Karoo
   controller/button binding is needed anymore — the extension triggers
   itself off the Karoo's own heart-rate/power stream.

**Fixed (2026-08-27) — manual stop from the app broke the auto-trigger.**
The extension worked once, then a manual **Stop Recording** tap in
`MainActivity` silently broke it. Root cause: `MainActivity` and
`Insta360Extension` each held their **own independent** `Insta360BleClient`
connected to the same camera address — exactly the "don't run both at
once" caveat this section used to warn about, and the user hit it on the
very first real test. Two consequences:
1. A second local BLE connection to the same peripheral address doesn't
   cleanly coexist with the first — connecting from `MainActivity` could
   disrupt the extension's own link.
2. Worse, the extension's `isRecording` flag was a **local field only it
   updated** — it had no way to learn that `MainActivity`'s manual stop had
   happened. So the extension stayed convinced it was still recording,
   which means the monitor loop kept waiting for the value to *drop below*
   threshold (to stop a "recording" that, per the camera, had already
   stopped) and never went back to watching for it to *rise above*
   threshold again. The trigger wasn't broken, it was just permanently
   stuck in the wrong half of its own state machine.

Fixed by introducing `connection/Insta360ConnectionManager.kt` — a
process-wide singleton that now owns the **one** `Insta360BleClient`
connection. Both `Insta360Extension` (auto-trigger) and `MainActivity`
(manual buttons) call into this same object instead of each creating their
own client, so there's exactly one BLE connection and one shared
`isRecording`/`isConnected` truth. The extension's monitor loop now reads
`Insta360ConnectionManager.isRecording` directly (not a local copy), so a
manual stop/start from the app is picked up immediately and the
auto-trigger resumes watching correctly. `MainActivity.onDestroy()` also no
longer disconnects the BLE link — closing the app shouldn't kill the
connection the extension depends on.

**Update (2026-08-27) — multiple cameras, each independently configured,
plus a Speed trigger.** The app now manages a *list* of cameras instead of
one:

```
app/src/main/kotlin/com/example/karooinsta360/
  camera/
    CameraStore.kt            # persisted list of cameras + each one's trigger config (JSON in SharedPreferences)
    CameraConfigActivity.kt   # per-camera screen: rename, remove, manual test, edit triggers
  connection/
    Insta360ConnectionManager.kt  # one Insta360BleClient per saved camera address, keyed by address
  extension/
    Insta360Extension.kt      # one monitor coroutine per saved camera
  MainActivity.kt              # camera list: scan/add/remove, jump into CameraConfigActivity
```

**Camera identity.** Every camera is stored as `{address, name}`, and the
name travels alongside the address everywhere — the camera list, the
config screen, logs. Discovered-via-scan devices default to their
advertised name (still `"Ace Pro 2 ..."` for this camera); rename it to
whatever's useful (e.g. "Chest", "Handlebar") in `CameraConfigActivity`.

**Multiple cameras.** `MainActivity` shows every saved camera with live
connected/recording status, a **Scan for Cameras** button (lists newly
found, not-yet-saved devices with an **Add** button each), and a manual
"add by address" fallback. Each camera gets its own **Remove** button.
`Insta360ConnectionManager` now keys everything (the BLE client, connected
state, recording state) by address, so it can hold independent connections
to as many cameras as you save — all of them auto-connect and auto-reconnect
the same way the single camera used to.

**Independent per-camera trigger config, plus a Speed trigger.** Tap
**Configure** on a camera to open `CameraConfigActivity`, which now has
*three* metrics instead of one — Heart Rate, Power, and Speed — each with
its own enable checkbox, threshold, and start/stop durations, **saved and
evaluated per camera**. Two cameras can have completely different
configs (e.g. one triggers off heart rate for POV shots, another off speed
alone for descents).

The three metrics don't all combine the same way:
- **Heart Rate and Power form one "effort" trigger.** Either one, on its
  own, can start recording (whichever crosses its threshold first, if
  both are enabled) and either one, on its own, can stop it. This was a
  deliberate choice over requiring both simultaneously, so a spike in
  either signal is enough to catch the moment.
- **Speed is a separate, independent trigger.** It does not factor into
  the heart-rate/power decision in either direction, and vice versa —
  each is evaluated as its own sustained-threshold state machine. The
  camera actually records whenever *either* the effort trigger or the
  speed trigger currently wants it to; a start/stop command is only sent
  to the camera when that combined "should be recording" value actually
  flips (so, e.g., speed dropping below its threshold won't stop a
  recording that effort is still holding open, and effort dropping won't
  stop one that speed is still holding open).
- Within the effort group, crossing (or dropping below) is "any": whichever
  of heart rate/power gets there first wins. There's no "all must agree"
  mode currently, and no way to require heart rate AND power together.

**Fourth trigger: Radar — "a vehicle is approaching from behind" (2026-08-27).**
Added a fourth independent per-camera trigger, using karoo-ext's `RADAR`
data type (`Field.RADAR_THREAT_LEVEL` plus up to 8 `RADAR_TARGET_n_RANGE`
fields) — this is the data a compatible radar sensor reports, e.g. a
Garmin Varia RTL515/RCT715 or similar, paired to the Karoo like any other
sensor. **It's its own on/off switch, independent of Heart Rate/Power/
Speed in both directions** — enabling or disabling car detection has no
effect on whether the other three are active, same as Speed already was
relative to effort.

Unlike the other three metrics, radar isn't a gradually-changing reading —
a car is either behind you or it isn't, and it's only there briefly — so
the trigger works differently on purpose:
- **Start:** the nearest currently-tracked target is within the configured
  distance (default 100 ft; entered in feet, converted to meters
  internally since that's the unit karoo-ext reports target range in) —
  a real, close vehicle, not merely something the radar can see somewhere
  in its full range (Varia's is roughly 140m/450ft).
- **Stop is NOT "the same threshold crossed the other way."** A car that's
  just passed and is now pulling away is still worth keeping in the
  recording, so recording keeps going as long as *any* vehicle remains on
  radar at all, regardless of distance. It only stops once the radar has
  reported zero targets for the configured grace period (default 15s) —
  long enough that a brief gap between cars in a stream of traffic doesn't
  chop one recording into several.
- Start duration defaults to 0 (immediate) rather than the 5s the other
  three default to — waiting even a couple of seconds to confirm a
  "sustained" threat could mean the car's already past by the time
  recording starts.
- `RADAR_TARGET_n_RANGE` fields are simply absent from the data point when
  nothing occupies that slot (not present-with-a-sentinel-value), which
  conveniently is exactly the "no vehicle" signal the stop condition
  needs — no separate "is anything detected at all" field to check.
- The nearest target's range is taken as the minimum across whichever of
  the 8 possible target fields are present, rather than assuming target 1
  is always the closest — more robust to different radar sensors'
  conventions for ordering multiple simultaneous targets.

**Aggregate data field.** `recording_state` (the data field you can drop
on a ride page) now reports `1` if *any* saved camera is currently
recording, `0` if none are — it's not per-camera, since karoo-ext data
types are declared statically in `extension_info.xml` and can't be
generated dynamically per however-many cameras you've saved.

**Not yet done / worth revisiting:**
- Recording state is tracked optimistically on send, not confirmed from
  the camera — if a `startCapture()`/`stopCapture()` write is silently
  dropped (e.g. camera briefly out of range), state will drift from
  reality until the next successful command. Parsing a real ack out of
  `onCommandResponse` would fix this if the camera ever sends one for
  these commands (it didn't appear to during the original debugging
  captures).
- Within the effort group there's no "require both heart rate AND power"
  mode — only "either one."
- **Unverified: the radar trigger has never run against real radar
  hardware.** It's built directly off karoo-ext's documented `RADAR` data
  type shape, but two things in particular are assumptions rather than
  confirmed behavior: that `RADAR_TARGET_n_RANGE` values arrive already in
  meters (consistent with Speed being m/s rather than a raw sensor scale
  factor, but genuinely unconfirmed), and that a target's field is simply
  absent — rather than present as some zero/sentinel value — once nothing
  occupies that slot (the stop condition depends on this: it's what makes
  "no vehicle detected" a checkable state at all). If distances read as
  wildly wrong once tested with a real sensor (e.g. a Garmin Varia), or a
  recording never stops because a target field lingers at a stale value
  instead of disappearing, that's where to look first — same
  connect-something-real-and-see-what-actually-comes-through approach
  that resolved the X4 Air issue above.
- **Fixed (2026-08-27) — scan no longer filters by name.** It used to only
  surface BLE devices starting with `"Ace Pro"`, which meant any other
  Insta360 model was invisible to the scan even though it might work fine
  once added — this is exactly what happened when trying to add an X4 Air
  (its BLE name doesn't start with `"Ace Pro"`). The scan now lists every
  nearby named BLE device; tap **Add** on whichever one is your camera.
  This trades a longer list for never silently hiding a camera again.
- **Unverified: whether `Insta360BleClient`'s protocol actually works on
  non-Ace-Pro-2 cameras once connected.** Scanning will now find an X4 Air
  (or anything else), but everything this client does — the BE80/BE81/BE82
  service and characteristic UUIDs, and especially the Header16 frame
  layout, including the offset-0 4-byte `total_inner_size` field that took
  a real BLE capture to nail down (see the debugging log above) — was only
  empirically confirmed against an actual Ace Pro 2. `xaionaro-go/insta360ctl`'s
  model table lists the X4 as also using "Direct Control" over BE80 with the
  same Header16 format (not the GO-series' different framing), which is a
  reasonable starting point, but that project's docs were also wrong about
  Ace Pro 2's header layout specifically — a different model actually
  responding to Start/Stop Recording still needs to be confirmed on real
  hardware, the same way this was: connect, try Start/Stop from
  `CameraConfigActivity`'s manual test buttons, and if nothing happens,
  the BLE HCI snoop capture + `tshark` approach from this project's
  debugging log is the way to find out what that camera actually expects.
- The old single-camera, single-metric `TriggerSettings.kt` is superseded
  by `CameraStore.kt` and now unused — left in place (emptied out) rather
  than deleted, only because file deletion isn't available in this
  session; safe to delete by hand.
- **X4 Air "connects but Start/Stop does nothing" — root-caused and
  fixed (2026-08-27); see below.** The bug report captured to debug it
  first turned out to be from the wrong device. `getprop
  ro.product.model`/`ro.product.manufacturer`
  inside that bugreport read `P30` / `Relndoo` — a phone, not the Karoo —
  and its Bluetooth HCI snoop log contained zero mentions of this app's
  package (`com.example.karooinsta360`), `karooext`, or `hammerhead`
  anywhere. All the BE80/BE81/BE82 GATT traffic to the X4 Air in that
  capture (service discovery, a repeating 4-message "GetOptions"-shaped
  burst, a `CheckAuthorization`-shaped exchange returning the camera's
  name/serial/firmware, and two identical short 7-byte writes) came from
  `com.arashivision.insta360akiko` — the **official Insta360 app** —
  which the bugreport's own log lines show running in the foreground on
  that phone (`CameraConnectService` starting) at the same timestamp the
  capture covers. So this capture only proves the official app can reach
  the X4 Air on a different device; it says nothing about why this app's
  own `startCapture()`/`stopCapture()` aren't working on the Karoo — no
  frame anywhere in any of the 8 rotated log files had this app's expected
  16-byte, empty-payload, commandCode-4-or-5 shape.
  **Resolved (2026-08-27) — this actually turned out to be two separate
  bugs, both on this app's side, neither in the camera's protocol.**
  A logcat captured correctly from the Karoo this time (`adb logcat -s
  "Insta360Ble:*"`) showed `sendCommand called: commandCode=4`, a properly
  framed 16-byte write, and `Write to 0000be81... succeeded` — the X4 Air
  *was* accepting Start/Stop the whole time. Two things were undoing it:
  1. **The automatic trigger monitor was fighting the manual test
     buttons.** `Insta360Extension`'s per-camera monitor coroutine runs
     continuously in the background (it's a separate Service, always
     alive once the extension is bound) and forces actual recording state
     to match its own heart-rate/power/speed computation every second,
     regardless of who or what last changed that state. In the log, a
     manual `startCapture()` on the main thread (tid 4260 — the Start
     button) was followed ~350ms later by an *automatic* `stopCapture()`
     from a different thread (tid 4292 — the monitor's own coroutine),
     because none of that camera's configured triggers were actually
     above threshold. So every manual test recorded for a fraction of a
     second before the monitor stopped it again — which is exactly what
     looked like "momentarily records or takes a picture." Fixed by
     `Insta360ConnectionManager.pauseAutomation()`/`resumeAutomation()`:
     `CameraConfigActivity` now pauses that camera's monitor in
     `onResume()` and resumes it in `onPause()`, so the manual Start/Stop
     buttons are only fought by the automatic trigger while this screen
     isn't the one in control.
  2. **The "Check Auth" test button actively broke Start afterward.**
     Once check-auth-then-start was tried, Start stopped doing anything
     at all for the rest of that connection. `checkAuthorization()` sends
     the camera a made-up `authorization_id` (this app's own BLE address —
     it was never anything the X4 Air had actually paired against), and
     on this camera that appears to leave the connection in some
     "awaiting an authorization decision" state that silently swallows
     Start afterward. It was already a speculative diagnostic added
     while debugging the Ace Pro 2 (see the comment in
     `Insta360BleClient.kt`) and was never actually required — Start/Stop
     work fine without ever calling it. Removed the button from
     `CameraConfigActivity`; the underlying `checkAuthorization()`
     function is left in place (now unused) in case a future camera's
     protocol turns out to need it.

**Separate start/stop thresholds, plus more descriptive config fields
(2026-08-27).** Heart Rate, Power, and Speed each now have *two* threshold
values instead of one — a `startThreshold` and a `stopThreshold` — so you
can put real distance between "start recording" and "stop recording"
instead of relying only on the sustained-duration timers for hysteresis.
For example: start recording once power reaches 300W, but don't stop again
until it's fallen under some other, separately-chosen number. Comparisons
work the same way they always did per metric, just against two different
numbers now: **start** fires once the value rises **to or above**
`startThreshold` (sustained for the start duration); **stop** fires once
the value falls **below** `stopThreshold` (sustained for the stop
duration). Nothing stops you from setting `stopThreshold` *higher* than
`startThreshold` (e.g. start at 300W, stop at 320W, from the original
feature request) — that's a valid configuration, but worth understanding
what it actually does: once power is anywhere between 300 and 320, the
stop condition (`< 320`) is already satisfied, so recording would stop
again almost immediately after starting rather than requiring power to
drop back down. The more common intent — "keep recording through brief
dips" — usually wants `stopThreshold` *lower* than `startThreshold` (e.g.
start at 300W, stop at 250W). Radar is unaffected — it never had a
meaningful second threshold to begin with, since its stop condition is
"no vehicle detected" rather than a crossed value (see above); its config
screen still only asks for one distance.

Old saved cameras (from before this change) had a single `threshold` key
in their stored JSON; loading one now migrates that value into *both*
`startThreshold` and `stopThreshold`, so an existing camera's behavior is
unchanged until you edit and re-save it with different start/stop values.

Also made every threshold/duration field in `CameraConfigActivity` more
descriptive — each `EditText` now has its own explanatory label above it
(e.g. "Start recording once power rises to or above (watts):") instead of
relying on the section heading and hint text alone to convey what a bare
number field does.

**Recording start/stop notifications (2026-08-27).** Added an optional
setting (`MainActivity`, new "Notifications" section, off by default) that
posts a status-bar/drawer notification every time any saved camera starts
or stops recording — e.g. "Recording started — Chest". Fires from genuine
`startCapture()`/`stopCapture()` calls only (both the automatic triggers
and the manual test buttons), deliberately not from connect/disconnect
handling, so a camera merely reconnecting never posts a false "stopped"
notification. Each camera gets its own notification (keyed by BLE address)
so a second camera's start/stop doesn't overwrite the first camera's still-
relevant one in the drawer. On Android 13+, turning the setting on prompts
for the `POST_NOTIFICATIONS` runtime permission; declining reverts the
checkbox, and `MainActivity.onResume()` also catches the permission being
revoked later from system Settings and turns the setting back off so it
doesn't sit "on" while silently doing nothing.

**Control Center start/stop, plus a tappable ride-page tile and a
controller-button toggle (2026-08-27).** Investigated whether karoo-ext
supports adding a custom control into the Karoo's own Control Center
panel — it doesn't; that panel's contents are fixed by the OS and aren't
an extension point. What karoo-ext *does* expose is `SystemNotification`
(a `KarooEffect`), which posts a native Karoo-style notification directly
into Control Center, can carry an action button, and can be re-dispatched
with the same `id` to update in place rather than piling up duplicates.
That, plus two other real karoo-ext mechanisms, add up to three ways to
start/stop recording without opening the app — all three act on **every
saved camera together** (`Insta360ConnectionManager.startAllCameras()` /
`stopAllCameras()` / `toggleAllCameras()`), since none of them can offer a
way to pick out one specific camera from a single tap or button press:

1. **Control Center notification.** New setting in `MainActivity`
   ("Control Center" section, off by default since — unlike the momentary
   notification above — this one sits in Control Center continuously):
   `Insta360Extension` keeps a `SystemNotification` (fixed id
   `insta360_recording_control`) showing "Camera idle — tap Start..." or
   "Recording — tap Stop...", updated in place every time the aggregate
   recording state changes. Its action button's `actionIntent` can only
   name an intent *action* to launch an activity with — no extras, no
   direct callback into the extension — so a new invisible trampoline,
   `ControlCenterActionActivity` (fully transparent/no-history theme, see
   `styles.xml`), exists purely to receive that click, call
   `startAllCameras()`/`stopAllCameras()`, and close itself before ever
   drawing a frame. No Android notification permission needed — this goes
   through karoo-ext's own system, not `NotificationManager`. **Known
   limitation:** karoo-ext has no "remove a SystemNotification" effect, so
   turning the setting back off stops it from being *updated* but won't
   retract one already showing in Control Center — dismiss it by hand.
2. **Tappable ride-page data field.** A new graphical data type,
   `RecordingControlDataType` ("Insta360 Recording Control" in the data
   field picker), renders a small `RemoteViews` tile ("Tap to Start" /
   "● REC — Tap to Stop") you can drop on any ride page. Its click is
   wired via `RemoteViews.setOnClickPendingIntent` to a
   `PendingIntent.getBroadcast` targeting a new `RecordingToggleReceiver`,
   which calls `toggleAllCameras()` — no activity launch at all, so
   tapping it never interrupts whatever's on screen. The tile refreshes
   itself by listening for `Insta360ConnectionManager` state changes;
   `ViewEmitter.updateView()` is rate-limited to ~1Hz internally, so a
   render that lands too soon after the last one is silently dropped and
   caught up by the next state change shortly after.
3. **Controller-button BonusAction.** Brought back a `BonusAction`
   ("Toggle Camera Recording", `actionId="toggle_recording"` in
   `extension_info.xml`) — assignable to a physical controller button
   (e.g. a shifter paddle) in the Karoo's own button-mapping settings,
   the same mechanism this project used for manual control before the
   automatic triggers existed (see the "switched from a controller button
   to an automatic..." entry above). `Insta360Extension.onBonusAction()`
   calls `toggleAllCameras()` the same as the other two surfaces.

**Fixed (2026-08-27) — Control Center's Start/Stop button immediately
undid itself.** Same root cause, same shape, as the very first "manual
test buttons only record for a fraction of a second" bug from earlier in
this log — just showing up in the three new surfaces above instead of
`CameraConfigActivity`. `Insta360Extension`'s per-camera monitor keeps
polling every ~1s and forcing actual recording state to match its own
heart-rate/power/speed/radar computation, regardless of who last changed
it. `CameraConfigActivity` was already immune to this (it pauses that
camera's automation for as long as its screen is open, in `onResume()`/
`onPause()`), but `startAllCameras()`/`stopAllCameras()` — the shared
function behind the Control Center notification, the ride-page tile, and
the BonusAction — never paused anything, so a manual Start got silently
reversed by the monitor's very next tick (or a manual Stop got silently
un-done the same way, if the auto-trigger condition was still true).
**Fixed** by having `startAllCameras()`/`stopAllCameras()` call
`pauseAutomation()` on every camera they actually act on. Since none of
these three surfaces has a "screen closing" moment of its own to resume
from (unlike `CameraConfigActivity`), a manual override from one of them
now *sticks* — the camera stays exactly as you left it, ignoring its
automatic triggers entirely, until you open that camera's Configure
screen once (even just to look and back out), which always hands control
back to automation on `onPause()`. Documented in-app in the Control
Center section's description text, plus doc comments on
`pauseAutomation`/`resumeAutomation` themselves.

**Added (2026-08-28) — recording ownership, so automation never stops a
recording it didn't start.** Until now, "who last touched a camera" was
only ever tracked as a *pause* (see the entry above) — and a pause on its
own only stops the automatic monitor from immediately reversing an
action within its next ~1s poll tick. It didn't stop the monitor from
later deciding, on some subsequent tick, that its own heart-rate/power/
speed/radar computation says "should be recording" and stopping a
recording someone else had started for an unrelated reason — most
concretely, closing `CameraConfigActivity` right after using its Start
button (`onPause()` clears the pause immediately, so if the automatic
condition happened to be false at that moment, the very next tick would
stop the recording the button had just started) or, less obviously,
opening and closing Configure at some later, unrelated point while a
Control-Center-started recording was still going (that also clears the
pause, since the pause map is keyed only by address, not by who set it).

Added `Insta360ConnectionManager.RecordingOwner` (`NONE` / `AUTOMATIC` /
`MANUAL`), recorded per camera address the moment `startCapture()` is
called and cleared back to `NONE` the moment recording stops for any
reason (including a disconnect). `runCameraMonitor()`'s combine step now
only calls `stopCapture()` when it *also* owns the recording
(`recordingOwner(address) == RecordingOwner.AUTOMATIC`) — a recording
owned `MANUAL` (the Configure screen's Start button, the Control Center
notification, the ride-page tile, the controller-button BonusAction —
all pass `RecordingOwner.MANUAL`) or `NONE` (the camera's own physical
shutter button, which this app never sees start) is never touched by the
monitor, for as long as it keeps running, independent of any pause
state. `pauseAutomation()`/`resumeAutomation()` still exist and are still
needed for the narrower thing they always did — stopping automation from
immediately re-*starting* a manually-*stopped* recording if the trigger
condition is still independently true — but no longer need to be treated
as "the" protection for a manual recording; that's now permanent and
doesn't depend on ever reopening Configure. Updated the in-app Control
Center description text and the relevant doc comments to match.

**Known limitation, unchanged by this fix:** a recording started via the
camera's own physical shutter button — entirely outside this app — still
can't be distinguished from "not recording" at the moment it starts, so
there's nothing to protect until this app's own polling happens to
notice the camera is now recording (at which point it's owner `NONE`,
which the fix above already protects). There's a brief window right at
shutter-button press where the extension's own recording-state read is
still stale.

## Attribution

BLE protocol reverse-engineering courtesy of
[xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl).
