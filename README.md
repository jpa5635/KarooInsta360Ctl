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

**Fixed (2026-08-29) — automatic triggers permanently stopped working after a single
Control Center start or stop.** A regression from the 2026-08-28 recording-ownership
entry above, not a new independent bug: `startAllCameras()`/`stopAllCameras()` (the
shared function behind Control Center, the ride-page tile, and the BonusAction) were
still calling the *indefinite* `pauseAutomation()` added back on 2026-08-27, on the
assumption they still needed the same protection `CameraConfigActivity` gives itself.
Ownership tracking made that assumption false — a `RecordingOwner.MANUAL` recording can
no longer be auto-stopped regardless of pause state — but nothing was ever calling
`resumeAutomation()` for these three surfaces (unlike `CameraConfigActivity`, they have
no "screen closes" moment to resume from), so the pause set on the very first Control
Center tap for a camera just... stayed set. Forever. Silently disabling that camera's
heart-rate/power/speed/radar triggers until someone happened to open and close its
Configure screen — which most people never think to do after using Control Center,
since nothing tells them to.

**Fixed** by removing the pause from `startAllCameras()` entirely (ownership tracking
already fully covers it — there was nothing left for the pause to protect) and replacing
`stopAllCameras()`'s indefinite pause with a new `pauseAutomationBriefly()`: still needed
there, since a manual Stop clears ownership back to `NONE` and an independently-true
trigger condition would otherwise cause an immediate automatic restart on the monitor's
very next ~1s tick — but now self-clears 3 seconds later instead of requiring a Configure
visit. Updated the in-app Control Center description text and the affected doc comments
to match.

**Fixed (2026-08-29) — two things stopped working specifically during a ride (not on
the home screen), reported after the first real on-bike test of a sideloaded build.**

1. **Control Center's Start/Stop entry would disappear partway through a ride.** It was
   only ever (re-)dispatched reactively — on connect, on a settings/camera-list change,
   or on an actual recording-state change — which covers everything while testing on the
   home screen, but not whatever the Karoo does to Control Center's contents around
   ride-state transitions (starting/pausing/resuming/ending a ride). karoo-ext documents
   no guarantee against this and gives no way to detect it directly, so rather than chase
   an exact root cause with no device logs to look at, `Insta360Extension` now has two
   independent nets: it re-dispatches the notification on every `RideState` change (the
   most likely trigger), and unconditionally every 30 seconds regardless of why it
   vanished — see `updateControlCenterNotification()`'s doc comment.

2. **The "camera started/stopped recording" status-bar notification wasn't showing up
   during a ride either — a separate, unrelated bug**, not the same one as #1. That
   notification's channel was `IMPORTANCE_LOW`/`PRIORITY_LOW`, which on Android means
   "sits silently in the shade, no heads-up banner" — invisible by design behind
   whatever fullscreen ride page is on screen, since there's no shade to pull down
   mid-ride. Bumped to `IMPORTANCE_HIGH`/`PRIORITY_HIGH` so it banners on top instead.
   Because a `NotificationChannel`'s importance is fixed forever at first creation on a
   given device (Android silently ignores importance on every later
   `createNotificationChannel()` call for the same ID), the channel ID itself had to
   change too (`recording_state` → `recording_state_hi`) — changing only the importance
   value in code would have done nothing for anyone who'd already run an earlier build.
   See `ensureNotificationChannel()`'s doc comment.

**Fixed (2026-08-29) — three more issues from the first real on-bike test of build 0.2.**

1. **Speed trigger seemed to never fire.** The Configure screen's speed fields were
   labeled "(m/s)" and the comparison in `runCameraMonitor` compared directly against
   the Karoo's raw m/s speed reading — technically correct, but a value entered assuming
   mph (the unit riders actually think in) would be off by a factor of ~2.2x. Someone
   entering "20" meaning 20 mph was actually setting a 20 m/s (44.7 mph) threshold,
   which is why it looked broken — it was just never reachable at any normal riding
   speed. Changed the fields to mph and added a conversion
   (`Insta360Extension.METERS_PER_SECOND_PER_MPH`) before comparing against the raw
   m/s reading. Existing saved thresholds are unaffected in storage — the same stored
   number is just now interpreted as mph instead of m/s, which for anyone who'd already
   entered a value assuming mph makes it start working with no changes needed on their
   end.

2. **Root cause found for Control Center's Start/Stop control being hidden during a
   ride: it's Hammerhead's own documented, intentional behavior, not a bug.** Their
   "Karoo OS - System Notifications" support article states outright: "System
   Notifications will be hidden while riding so you can keep your focus on the road or
   trail." `SystemNotification` (what Control Center's entry uses) *is* a System
   Notification in Hammerhead's terms, so this control is invisible for the entire
   duration of any ride by platform policy — no re-dispatch timing or frequency can
   override that, which is also why the two independent re-dispatch mechanisms added the
   day before had no effect on this specific symptom. They're left in place since
   they're harmless and still useful for the (different, still-hypothetical) case of
   this control getting cleared for some unrelated reason while *not* riding, but the
   practical fix here is: don't rely on this control while actually riding. The
   ride-page "Insta360 Recording Control" tile and the "Toggle Camera Recording"
   BonusAction (a controller/shifter button) are unaffected by this Control-Center-
   specific policy and are the surfaces that actually work mid-ride. Updated the in-app
   description text and `updateControlCenterNotification()`'s doc comment to say this
   plainly instead of speculating.

3. **The status-bar "recording started/stopped" notification: seen as a heads-up
   banner, but never found afterward in any drawer.** Given (2) above, and that
   Hammerhead's own docs describe exactly two notification surfaces on Karoo — "System
   Notifications" (Control Center, karoo-ext's own mechanism) and "Phone Notifications"
   (forwarded from a paired phone via the Companion App, unrelated to a sideloaded app's
   own notifications) — there's reason to believe a locally-posted Android
   `NotificationCompat` notification may not have any persistent, user-accessible
   "drawer" on Karoo at all, heads-up banner aside. Rather than continue chasing Android
   notification channel settings against an OS behavior neither of us can fully see,
   added a second, independent signal: `Insta360Extension` now also raises a native
   karoo-ext `InRideAlert` ("critical messaging related to the current ride," per its
   own doc comment) on every genuine start/stop, alongside — not instead of — the
   existing status-bar notification. `InRideAlert` is guaranteed to actually render on
   Karoo's screen regardless of what's true about the Android notification drawer or
   Control Center's ride-hiding policy. Required adding
   `Insta360ConnectionManager.Listener.onRecordingChanged()` (fired from the same
   `startCapture()`/`stopCapture()` call sites as the existing notification, factored
   through a new shared `onGenuineRecordingAction()`) since only the extension — not
   `Insta360ConnectionManager` — holds a `KarooSystemService` to dispatch through. New
   colors in `colors.xml` (`recording_started_bg`/`recording_stopped_bg`/
   `recording_alert_text`) give the alert a green/blue-gray tint so start vs. stop reads
   at a glance.

**Versioning (2026-08-29):** `versionName` now follows `0.1.<build number>` (this build
is `0.1.3`) rather than `0.<build number>`, per request — `versionCode` keeps
incrementing by 1 per build regardless, since it doesn't need to encode the same scheme.

**Added (2026-08-29, build 0.1.4) — manual "Reconnect" button, for when Karoo/camera
power-on timing don't line up.** Reported symptom: sometimes the Karoo and camera don't
connect at all after both power on, apparently because of a timing mismatch between when
each becomes ready. Root cause: `Insta360ConnectionManager.connectToSaved()` guards
against duplicate connection attempts with `if (isConnected(address) ||
clients.containsKey(address)) return`. If a BLE connect attempt is made while the camera
isn't actually ready to accept it, the resulting `Insta360BleClient` can get stuck in
`clients` without ever calling back `onConnected()` or `onDisconnected()` — not
connected (so the UI correctly shows "Disconnected"), but still occupying the map entry
that blocks every future automatic retry from `retryLater()`. Previously the only fix was
force-closing the app so process death cleared `clients` from scratch.

Added `Insta360ConnectionManager.reconnect(context, address)` — tears down whatever's in
`clients` for that address (stuck, genuinely connected, or nothing) via the existing
`disconnect()`, then calls `connectToSaved()` again to force a genuinely fresh attempt.
Originally wired to a Reconnect button on `CameraConfigActivity`; moved the same day (see
below) to each camera's row on the main camera list instead, so it's reachable without
opening Configure first.

**Removed (2026-08-29, build 0.1.5) — the Karoo Control Center Start/Stop control.** Now
that Hammerhead's own documentation confirmed Control Center notifications are hidden for
the entire duration of any ride (see the build-0.2 entry above), that control was never
usable for the in-ride case it existed for — it only ever worked before/after a ride, which
the tappable ride-page tile and the controller-button BonusAction also cover, so it was
pure surface area with no remaining use case. Removed: the "Control Center" setting and
checkbox in `MainActivity`, `AppSettings.isControlCenterControlEnabled`/
`setControlCenterControlEnabled` (and the now-unneeded generic
`registerChangeListener`/`unregisterChangeListener` pair on `AppSettings`, which existed
only to let the extension react to that one setting), `Insta360Extension`'s
`updateControlCenterNotification()`/`startControlCenterRefresh()`/
`stopControlCenterRefresh()` and their `RideState`-collector/30s-repost jobs, the
`ControlCenterActionActivity` trampoline (deleted outright — its manifest entry, and the
`Theme.Transparent.NoDisplay` style that existed only for it), and
`RecordingActions.ACTION_START_ALL`/`ACTION_STOP_ALL` (only `ACTION_TOGGLE_ALL`, used by
the ride-page tile, remains). `Insta360ConnectionManager.startAllCameras()`/
`stopAllCameras()` are unchanged and still used internally by `toggleAllCameras()`. The
in-app "Control Center" section in `MainActivity` was replaced with an "In-Ride Manual
Control" section explaining the two surfaces that actually work mid-ride.

**Moved (2026-08-29, build 0.1.5) — the Reconnect button now lives on the main camera
list, not the Configure screen.** Each camera's row in `MainActivity` (alongside
Configure/Remove) now has its own **Reconnect** button, so recovering a camera that never
connected in the first place (see the build-0.1.4 entry above) doesn't require opening
Configure — useful since a camera stuck in that state also can't be told apart from a
normal "Disconnected" camera without already knowing to look. `CameraConfigActivity` no
longer has a Reconnect button.

**Added (2026-08-29, build 0.1.5) — Speed and Radar trigger units are now chosen per
camera instead of fixed app-wide.** Speed can be entered in mph or km/h; Radar's trigger
distance can be entered in feet or meters — each camera picks independently via a new pair
of radio buttons in `CameraConfigActivity` (Speed: `speedUnitGroup`; Radar:
`radarUnitGroup`). `CameraStore.CameraConfig` gained `speedUnit: SpeedUnit` (`MPH`/`KMH`)
and `radarUnit: DistanceUnit` (`FEET`/`METERS`), each carrying its own conversion factor to
the Karoo's raw SI units (m/s for speed, meters for radar) — `Insta360Extension`'s
per-camera monitor now multiplies by `config.speedUnit.metersPerSecondPerUnit`/
`config.radarUnit.metersPerUnit` instead of the fixed `METERS_PER_SECOND_PER_MPH`/
`METERS_PER_FOOT` constants those replace. A camera saved before this change has no stored
unit at all; loading one defaults to `MPH`/`FEET` — the units this app used exclusively
before today — so its existing numbers keep meaning exactly what they did. Switching a
camera's unit does **not** convert its already-entered threshold number; the UI says so
next to each unit picker, since silently reinterpreting "20" from 20 mph to 20 km/h (very
different speeds) would be worse than requiring a manual re-entry.

**Changed (2026-08-29, build 0.1.6) — Power's stop trigger now uses a rolling 3-second
average, with a configurable number of tolerated spikes.** Reported issue: the power stop
trigger felt too twitchy after a day of real testing. Previously it compared the Karoo's
*instantaneous* power reading straight against `stopThreshold`, sustained for
`stopSeconds` — a single low-power tick (freewheeling for a second while still working
hard overall) could restart the whole stop countdown from zero, or a single tick that
happened to be high could cancel a countdown that was otherwise legitimately about to
finish. Power's stop check now instead averages the last `POWER_STOP_AVERAGE_SAMPLES`
(3) one-second ticks — a true 3-second rolling average — before comparing to
`stopThreshold`.

On top of that, added a configurable **allowed spikes** setting (0-5, new
`powerStopAllowedSpikes` field, new field in `CameraConfigActivity`'s Power section): while
a stop countdown is already running, the rolling average is allowed to spike back to/above
`stopThreshold` — without cancelling the countdown — up to this many times per stop
attempt, each capped at 3 seconds (`POWER_STOP_MAX_SPIKE_MS`, matching the average window
itself). A spike that runs longer than 3 seconds, or that happens after the allowance is
used up, is treated as a genuine return to effort and cancels the countdown as before. The
default is 0 (no spikes tolerated) — matching the exact behavior every camera had before
this feature existed, aside from the underlying instantaneous-vs-3s-average change, which
applies unconditionally. All of this new state
(`powerStopSamples`/`powerSpikeActive`/`powerSpikeStartTime`/`powerSpikesUsed` in
`runCameraMonitor`) resets whenever a stop attempt actually completes or is cancelled, so
each new attempt starts with a fresh 3-second window and a fresh spike allowance. Heart
Rate's stop side is unchanged (still instantaneous) — this request was specifically about
Power.

**Investigated (2026-08-29) — reported "speed trigger fired at a lower speed than
expected."** Re-checked the mph/km-h math end to end: `SpeedUnit.MPH`'s conversion factor
(0.44704 m/s per mph) and `KMH`'s (0.277778 m/s per km/h) are both correct, and
`runCameraMonitor`'s speed latch multiplies the configured threshold by that factor before
comparing against the Karoo's raw speed reading — the logic itself checks out. The one
thing that can't be verified from here: Hammerhead's public karoo-ext documentation never
explicitly states what unit `DataType.Type.SPEED`'s raw stream is actually in. Every
available signal points to it being plain m/s regardless of the Karoo's own display-unit
setting — that's the universal ANT+/FIT convention for cycling computers, and karoo-ext's
own docs describe unit conversion as happening only in the separate view-formatting step
(`UpdateNumericConfig`/`UpdateGraphicConfig`'s `formatDataTypeId`), which would be
redundant if the raw stream were already unit-converted — but this isn't something a public
doc states outright, so it remains the one assumption in this calculation that only a real
device test can confirm or rule out. Added logging to make that test possible: both speed
threshold crossings in `runCameraMonitor` now log the raw Karoo reading in m/s alongside
the configured threshold and its converted m/s equivalent (e.g. `rawSpeed=8.1m/s,
threshold=18.0mph = 8.04672m/s`) — capturing logcat (`adb logcat -s Insta360Extension:*`)
around the moment a speed trigger fires and comparing `rawSpeed` to your actual known speed
at that instant (from a separate bike computer, GPS app, or car speedometer) will show
directly whether the Karoo's raw value is really m/s, or something else entirely (e.g.
already mph, which would make triggers fire at roughly 0.45x the intended real speed — a
plausible match for "fired lower than expected"). Not yet resolved without that data point.

**Added (2026-08-29, build 0.1.7) — logging overhaul so every start/stop, and every
suppressed non-start/non-stop, is traceable from `adb logcat -s Insta360Extension:*
Insta360ConnMgr:*` alone.** Prompted by two follow-up requests after auditing what was and
wasn't logged: (1) "the log needs to be clearer and specify which effort triggered a start
or stop," and (2) "even if the trigger is ignored because recording is already occuring."
Previously Heart Rate's and the combined "effort" latch's logs were bare (no values, and no
way to tell whether HR or Power actually caused a given crossing), and the final
start/stop decision in `runCameraMonitor` had **no logging at all** for its no-op paths —
if a trigger fired but the camera was already recording (or the recording belonged to a
manual start, or automation was paused for that camera), nothing was logged; the line
simply did nothing.

Two changes:

- **Per-metric attribution.** Heart Rate's and Power's start/stop crossings each now log
  their own value against their own threshold, the same way Speed's crossings already did
  (e.g. `heart rate (165bpm ≥ 160bpm for 5s)`, `power (3s avg 210.4W < 200W for 10s, spikes
  used 1/2)`). Since either metric can independently start or stop the shared "effort"
  latch, the combined log line now names *which one(s)* actually crossed that tick (e.g.
  `effort start: heart rate (...) and power (...)` when both cross together) instead of the
  old generic "effort threshold sustained." Radar's logs got the same treatment (now include
  the live distance reading, not just the configured threshold).
- **Every combine-step outcome is now logged, not just the ones that took an action.**
  `runCameraMonitor`'s final "should this camera be recording right now" step now logs all
  six possible outcomes each tick can land on: sending a start command, sending a stop
  command, a start suppressed because the camera is *already recording* (the exact case
  named in the request — logged as `... but camera is ALREADY RECORDING (owner=...) — start
  IGNORED, no action taken`), a stop suppressed because the current recording isn't owned by
  this automation (a manual start, the ride-page tile, or the camera's own shutter button),
  automation paused for that camera, and plain idle. Each log line names which latch(es)
  currently want recording (`effort`, `speed`, `radar`, or a `+`-joined combination) and
  includes the most recent per-metric detail string, so a single line answers "what wanted
  it, why, and what actually happened." To avoid re-logging the same steady-state line once
  a second for an entire multi-minute recording, this is edge-triggered — only a *change* in
  outcome from the previous tick produces a new log line — but every actual state change
  still gets one.
- `startCapture`/`stopCapture` in `Insta360ConnectionManager` now take an optional `reason`
  string, included in both the success log and the existing "ignored — not connected"
  warning, so the connection-manager layer's "what actually happened at the BLE level" can
  be matched to the extension layer's "why we tried" without cross-referencing timestamps.
  Manual callers (Configure screen's Start/Stop buttons, the ride-page tile/BonusAction's
  start-all/stop-all) now pass their own descriptive reasons too.

**Added (2026-08-29, build 0.1.8) — named configuration profiles for the whole camera
fleet.** Request: reconfiguring every camera's trigger thresholds by hand each time you
switch riding contexts (e.g. a road ride vs. a gravel race with different speed/radar
cutoffs) was tedious. New `ProfileStore` object, modeled on `CameraStore`'s own
SharedPreferences-JSON approach: a profile is a user-named snapshot of every saved camera's
*full* trigger configuration (heart rate/power/speed/radar thresholds and durations, units,
and power's spike tolerance — everything except each camera's address/name identity),
keyed by BLE address so different cameras can keep different settings within the same
profile (asked and confirmed explicitly — a profile is not one shared value forced onto
every camera).

New "Configuration Profiles" section on the main screen, directly below the camera list per
the request:

- **Save Current Settings as New Profile** — prompts for a name, then snapshots every
  currently-saved camera's settings into a brand new profile and makes it the active one.
- Each saved profile gets its own row with **Apply** (writes that profile's settings back
  into every camera it covers, via the same `CameraStore.addOrUpdateCamera` path a manual
  edit in `CameraConfigActivity` would use — so it fires the usual change listener and
  `Insta360Extension` picks up the new thresholds immediately, no restart needed),
  **Update** (overwrites the profile with every camera's *current* settings — for after
  you've tweaked something and want to save it back), **Rename**, and **Delete**. Split
  across two two-button rows rather than one four-button row — didn't fit comfortably on
  the Karoo's narrow screen the way the camera list's three buttons already do.
- A camera whose address isn't covered by a profile (added after that profile was created,
  or never part of it) is simply left untouched when that profile is applied — surfaced in
  a Toast ("Applied 'Road' to 2 camera(s); 1 camera not in this profile were left
  unchanged") rather than silently doing nothing, so a forgotten camera doesn't go
  unnoticed.
- "Active profile" (shown at the top of the section) is bookkeeping only — the most
  recently applied (or saved) profile's name, for reference. It is not continuously
  enforced: editing a camera's settings by hand afterward silently drifts it away from
  matching that profile, same as changing one value in a saved preset without re-saving it
  — use **Update** to bring the profile back in sync when that's what you want.

**Redesigned (2026-08-29, build 0.1.9) — profiles now own trigger configuration entirely;
0.1.8's design is superseded.** Direct feedback on 0.1.8: "I don't like how you've
implemented profiles. All of the triggers should be configured in the profile, not under
the camera settings," followed by "you should be able to configure what cameras are active
in each profile and then the trigger settings for each of the cameras considered in that
profile." 0.1.8 had it backwards — each camera still owned its own real trigger settings,
and a profile was just a save/restore snapshot of them (`CameraStore.CameraConfig` carried
heartRate/power/speed/radar directly, `ProfileStore` copied those values in and back out).
This build reverses that:

- **`CameraStore.CameraConfig` is now identity-only** — address and name, nothing else.
  Every trigger field that used to live there is gone.
- **`ProfileStore.Profile` is the sole owner of trigger configuration**, and now tracks two
  things explicitly: `activeCameraAddresses` — *which* saved cameras this profile
  considers at all — and `cameraSettings` — the full heart rate/power/speed/radar
  configuration for each camera it's ever included. Switching a camera off within a
  profile (unchecking it) doesn't erase its settings from `cameraSettings`, only from
  `activeCameraAddresses` — switching it back on later restores exactly what it had before,
  the same way muting a track in a DAW doesn't erase the track.
- **New `ProfileActivity`** (reached via each profile's **Configure** button on the main
  screen, replacing 0.1.8's **Update** button, which no longer means anything now that
  cameras don't hold their own settings to snapshot) lists every saved camera with a
  checkbox for whether this profile includes it, and a **Configure Triggers** button (shown
  only when checked) into...
- **New `ProfileCameraConfigActivity`** — the actual heart rate/power/speed/radar form,
  moved here verbatim from `CameraConfigActivity`, now scoped to one (profile, camera)
  pair and saved via `ProfileStore.updateCameraSettings` instead of `CameraStore`.
- **`CameraConfigActivity` is stripped down to identity only** — rename, manual Start/Stop
  test, Remove. A note in its layout points to the profile's Configure screen for actual
  trigger editing.
- **"Active profile" is now continuously enforced, not just a label.** `Insta360Extension`'s
  `resyncCameraMonitors` only starts a monitor coroutine for a camera that is both
  currently saved AND present in the *active* profile's `activeCameraAddresses` — a camera
  outside the active profile gets no monitor at all, not one running with empty/disabled
  settings. The extension now listens for `ProfileStore` changes the same way it already
  listened for `CameraStore` changes, so toggling a camera in `ProfileActivity`, editing its
  triggers in `ProfileCameraConfigActivity`, or applying a different profile all take effect
  within the next ~1s tick, no restart needed. **Apply** on the main screen is now just
  `ProfileStore.activateProfile` (an id pointer flip) rather than 0.1.8's per-camera write —
  simpler, since there's no longer anything to copy anywhere.
- **Creating a new profile** now includes every currently-saved camera switched on by
  default, seeded from the *currently-active* profile's settings where it covers the same
  camera (falling back to sane defaults otherwise) — so a new profile starts from a known
  baseline instead of every trigger reset to zero — and immediately opens `ProfileActivity`
  for it, since reviewing/adjusting per-camera settings is exactly what you'd want to do
  next with a brand new profile.
- **Migration:** anyone upgrading from 0.1.7 or earlier (before profiles existed at all)
  had real, ride-tested trigger settings sitting directly on their cameras in the old
  format. `CameraStore.migrateLegacyTriggersToProfileIfNeeded` runs once, reads that old
  per-camera JSON directly (bypassing the new, simplified parser, which no longer knows
  those fields exist), and — only if no profile exists yet at all — packages whatever had
  real enabled triggers into one new "Migrated Settings" profile, switched on and made
  active, so upgrading straight to 0.1.9 doesn't silently throw those numbers away. Anyone
  who already made real profiles in 0.1.8 keeps them untouched: their saved
  `cameraSettings` carry over as-is, and a profile from that build with no
  `activeCameraAddresses` key at all (added this version) is read as if every camera it had
  settings for was already switched on, matching 0.1.8's actual behavior.

**Added (2026-09-07) — recording indicator work: field layouts, themes, camera-side
detection, and trigger reasons in alerts.** Four changes:

1. **Recording Control field: red background, width-aware layout, light/dark.** The tile
   now fills its whole cell with red (`field_recording_bg`) while any saved camera is
   recording, so state reads from peripheral vision rather than requiring you to read
   text. Layout adapts between full-width and half-width cells — note this is one data
   type, not two: a field has no per-instance config and no declared width variants, so
   `ViewConfig.gridSize` (60-unit grid; `Pair(60, 15)` = full width, quarter height) is
   what the field branches on at render time. Light/dark, by contrast, *does* need a
   setting, because karoo-ext exposes no theme signal anywhere — not `ViewConfig`, not
   `RideProfile`, not `UserProfile` — hence `AppSettings.isFieldThemeDark`, app-wide
   rather than per-placement to avoid doubling this extension's entries in the field
   picker.

2. **Camera-side recording is now detected.** `Insta360BleClient` already separated
   unsolicited notifications from command responses (sequence 0 with the from-camera
   flag) but only logged them. Now `Insta360ConnectionManager.handleCameraNotification`
   acts on them: `0x2008`/`0x2014` (physical shutter button, paired remote) trigger a
   status query rather than a guess; `0x2009` and `0x2007`/`0x2005` (capture stopped,
   card full, shutdown) apply directly; `0x2002` (auto-split) is explicitly ignored,
   since it fires mid-recording when the camera rolls to a new file and would otherwise
   read as a stop. `CMD_GET_CURRENT_CAPTURE_STATUS` (0x0F) is also sent on every connect,
   replacing the old assumption that a freshly connected camera is idle — which was wrong
   whenever the camera was already rolling before we got there.

   **The 0x2010 payload parse is unconfirmed and fails closed.** insta360ctl parses that
   code for *storage* fields on the GO 3 despite its name being `NotifyCurrentCaptureStatus`,
   so the payload carries more than capture state and the field numbering may differ by
   model. `handleCaptureStatusPayload` therefore only reads field 1 as a varint and, if
   the payload isn't shaped that way, logs the raw hex and leaves our existing belief
   alone rather than replacing it with a wrong one. Ride once, start/stop the camera by
   hand, and logcat will show the real layout.

3. **New "Insta360 Distance" data field** (`recording_distance`) — ride distance that
   behaves exactly like Karoo's stock Distance field, with a red dot flashing beside it
   while recording. It costs no extra page space, since it replaces a Distance field you
   were going to have anyway. Crucially it does *not* reimplement distance rendering:
   `startStream` republishes the system `TYPE_DISTANCE_ID` value under this field's own
   id, and `startView` sends `UpdateGraphicConfig(formatDataTypeId = TYPE_DISTANCE_ID)`,
   which is precisely what that field exists for ("overlay graphical elements on existing
   numeric data field treatment", per its own doc comment). Units, precision, font and
   header stay native and stay correct through future Karoo restyles.

   Flash rate is 1s on / 1s off, and that is a floor rather than a preference:
   `ViewEmitter.updateView` silently drops any view emitted less than ~900ms after the
   previous one. A faster flash would need a hand-built `ViewFlipper` with
   `autoStart`/`flipInterval` so the animation runs inside the Karoo process.

4. **Start/stop alerts now say why.** The free-text `reason: String` on
   `startCapture`/`stopCapture` became `RecordingReason` (new file), which carries both a
   `logText` (unchanged detail for logcat) and a short `alertText` for the rider. So an
   `InRideAlert` now reads "Ace Pro 2 · Speed trigger" or "Ace Pro 2 · Manually from
   Karoo button" instead of just the camera name, while logcat keeps the full
   "speed stop: rawSpeed=1.8m/s < 4.0m/s (9.0mph)" detail. Data-source-loss stops are
   distinguished from ordinary threshold stops in the alert as well as the log, since a
   dropped strap is actionable mid-ride in a way a normal stop isn't.

## Building on CI (2026-09-07)

`.github/workflows/build.yml` builds the APK on GitHub's runners: every push to `main`
uploads it as a run artifact, and pushing a tag like `v0.1.11` also attaches it to a
GitHub Release. Manual runs via the Actions tab work too.

**One-time setup — the karoo-ext token.** karoo-ext is only published to GitHub Packages,
and reading from there needs authentication even though the repo is public (same reason
`~/.gradle/gradle.properties` needs `gpr.user`/`gpr.key` locally). The workflow reads the
`USERNAME`/`TOKEN` env vars that `settings.gradle.kts` already falls back to, so:

1. Create a classic personal access token with only the **`read:packages`** scope at
   <https://github.com/settings/tokens>.
2. Add it to this repo under Settings → Secrets and variables → Actions → New repository
   secret, named `KAROO_EXT_TOKEN`.

The workflow deliberately does *not* use the automatic `GITHUB_TOKEN`: it's scoped to this
repository, and reading another organisation's packages with it fails inconsistently.

**Debug, not release.** The workflow builds `assembleDebug`. A release build is unsigned
without a keystore and Android refuses to install an unsigned APK; the debug signing key
is generated during the build, which is all a sideloaded Karoo install needs. Signing a
real release build would mean committing an encrypted keystore and adding
`signingConfigs` — not worth it for a sideloaded app with one user.

**Remember `versionCode`.** It isn't derived from anything, so bump `versionCode` (and
`versionName`) in `app/build.gradle.kts` before tagging, or the new APK won't install over
the old one.

**Fixed (2026-09-07) — camera-side recording was never detected, and the Distance field
was misnamed.**

`Insta360BleClient.handleIncoming` classified an inbound frame as an unsolicited
notification only when `sequence == 0`. That is not how the protocol works: insta360ctl
identifies unsolicited frames by `seq == 255`, or by there being no pending request for
that sequence. So every notification the camera pushed with a non-zero sequence — which
is to say, the ones that mattered — went to `onCommandResponse` and was dropped, and
starting a recording on the camera body changed nothing in the extension. Classification
is now by command code instead: notification codes start at 0x2000 and command codes never
approach it, so the split is unambiguous whatever sequence numbering a given model uses.

Three supporting changes, since a feature that depends on frames you never asked for needs
to fail loudly rather than silently:

- Every frame received from a camera is logged with code, sequence and raw hex, so
  "nothing was decoded" is distinguishable from "nothing arrived".
- `handleCaptureStatusPayload` used to require the payload to begin with the exact tag
  byte for field 1 and discarded anything else unread. It now walks the whole message and
  logs every varint field it finds, so a single logcat trace identifies which field
  actually carries capture state on the Ace Pro 2.
- A 10-second per-camera status poll now runs alongside notifications. Redundant when
  notifications work — a poll that agrees with current state changes nothing and raises no
  alert — but it bounds how long the indicator can be wrong if a notification is missed or
  simply never sent by this model. Given the entire point of the indicator is to be
  trustworthy, a BLE write every ten seconds is a cheap premium.

The custom distance field is also now named just "Distance" rather than "Insta360
Distance": the extension name already appears next to it in the field picker, so the
prefix read as a stutter and sorted it away from the stock field it replaces.

**Fixed (2026-09-07, 0.1.13) — the two things still broken in 0.1.12.**

*Camera-side recording still undetected.* The 0.1.12 fix corrected how inbound frames were
classified, but that was downstream of the actual problem: `Insta360BleClient` only ever
subscribed to **BE82**, and `onCharacteristicChanged` ignored frames from anything else.
insta360ctl subscribes to five notify characteristics (BE82, AE02, and B002/B003/B004 on
the secondary service), so if this camera pushes capture status on any of the others, the
frames were never arriving to be classified. Rather than hard-code that list and hope it
matches the Ace Pro 2, service discovery now subscribes to *every* characteristic
advertising NOTIFY or INDICATE, and logs the full discovery so an absent notification is
distinguishable from an unsubscribed one. Subscriptions are issued one at a time from each
other's completion callback, since Android's GATT stack silently drops a descriptor write
issued while another is in flight. Frame classification also no longer requires the
from-camera flag, for the same reason it no longer requires `sequence == 0`: if a model
doesn't set it, requiring it drops exactly the frames we care about.

*Distance field showed no number.* It relied on
`UpdateGraphicConfig(formatDataTypeId = TYPE_DISTANCE_ID)` to have Karoo render the value
beneath our dot overlay — which is what that setting is documented for, and how karoo-ext's
own sample uses it — but on device the cell came up empty. The field now draws its own
value from a `DISTANCE` subscription made directly in `startView`. Beyond simply working,
that removes the dependency on Karoo choosing to start our `startStream` at all, which was
one of the two candidate explanations and the one that couldn't be ruled out from outside.

The costs are worth naming: the font no longer matches a stock field exactly, unit
formatting is now ours to keep right (it reads the rider's configured unit system rather
than assuming metric), and the value is centred rather than honouring the field's
alignment setting, since RemoteViews can't set gravity at runtime without risking an
`ActionException`. `startStream` is kept regardless, so the type still works as a plain
numeric field and reverting to the overlay approach stays a small change.

## Attribution

BLE protocol reverse-engineering courtesy of
[xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl).
