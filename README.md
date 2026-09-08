# karoo-insta360

A [Hammerhead Karoo](https://www.hammerhead.io) extension that controls Insta360 cameras
over BLE. It starts and stops recording automatically from live ride data, gives you
manual control from the head unit, and shows on your ride pages whether a camera is
actually rolling.

Built on [karoo-ext](https://github.com/hammerheadnav/karoo-ext). BLE protocol
reverse-engineering courtesy of
[xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl).

---

## Installing

Download the APK from
[Releases](https://github.com/jpa5635/KarooInsta360Ctl/releases) and sideload it:

```
adb install -r karoo-insta360-<version>-debug.apk
```

Open the app on the Karoo once and grant Bluetooth permissions when prompted.

---

## Cameras

Put the camera in Bluetooth pairing mode, then tap **Scan for Cameras**. The list shows
every nearby Bluetooth device rather than filtering to Insta360 ones, so pick yours by
name and tap **Add**. If it never appears, **Add by Address** takes a MAC directly.

Tap a saved camera to open its Configure screen, where you can rename it, remove it, and
use **Start** / **Stop** to confirm control works. Do that before configuring triggers —
it separates "my thresholds are wrong" from "the camera isn't connected".

### Multiple cameras

Everything is per camera. Add as many as you like, and each gets its own trigger settings
within a profile — a bar-mounted camera on radar only, a rear-facing one on speed and
radar, and so on. Manual controls (data field, bonus button) always act on **all** saved
cameras at once: start every connected idle camera, or stop every recording one.

---

## Profiles

Triggers do not live on cameras. They live on **profiles**, and a profile also chooses
which of your saved cameras it applies to at all.

Exactly one profile is active at a time, and it alone drives automatic recording. That is
the point: a Road profile and a Gravel Race profile become a one-tap switch instead of
re-entering every threshold, and each can use a different set of cameras with different
thresholds per camera.

Tap **Create New Profile**, name it, then open it to select its cameras and configure each
one's triggers. Back on the main screen, **Apply** it. The active profile is named at the
top — if that says "none", nothing automatic will happen.

---

## Starting and stopping recording

### Manual: the data field

Add **Insta360 Recording Control** to any ride page. It's tappable: tap to start every
connected camera, tap again to stop every recording one. It fills red while any camera is
recording, so it doubles as an indicator.

### Manual: a bonus button

Bind the **Toggle Camera Recording** action to a controller button in the Karoo's own
button settings, for override without looking at the screen.

If you use SRAM AXS controls, note that assigning a Karoo Action to a button removes its
native AXS shift mapping entirely, and only Karoo Actions support separate short and long
press. Dedicating one bonus button to this is the usual compromise.

### Manual: on the camera itself

Pressing the shutter on the camera works normally, and the extension notices and updates
its fields to match.

Recordings started by any manual method are **never** stopped by the automatic triggers.
Only a recording the triggers themselves started gets auto-stopped, and that protection
lasts as long as the recording does. Stopping manually also briefly pauses that camera's
triggers, so they can't immediately restart it while a trigger condition is still true.

### Automatic: triggers

Configured per camera inside a profile. Each trigger has its own start and stop values
plus its own sustain durations.

**Heart rate** and **power** form a single *effort* latch — enable either or both, and
either can start a recording.

**Speed** and **radar** are each independent latches.

A camera records while **any** latch wants it recording, and stops only once none do.

#### Heart rate, power, speed

| Setting | Meaning |
|---|---|
| Start threshold | Start once the value rises to or above this |
| Stop threshold | Stop once the value falls below this |
| Start seconds | How long it must stay at/above the start value first |
| Stop seconds | How long it must stay below the stop value first |

Start and stop are separate values on purpose. Set both to 300 W and you get a recording
that starts and stops repeatedly as your power wobbles across the line; setting stop lower
than start gives you hysteresis. The sustain durations are usually asymmetric too — short
to start so you don't miss the beginning of an effort, long to stop so a brief soft-pedal
doesn't cut the clip in half.

Speed thresholds are entered in mph or km/h, chosen per camera.

#### Radar

Radar works differently, and it's worth understanding before you set it.

| Setting | Meaning |
|---|---|
| Start distance | Start once the nearest tracked vehicle is within this (ft or m) |
| Start seconds | How long a vehicle must be within that distance first |
| Stop seconds | How long radar must track **no vehicle at all** before stopping |

The stop side is not a distance. Once radar has started a recording, any vehicle still on
radar at any distance keeps it going — the recording ends only after radar reports nothing
tracked for the stop duration. That's deliberate: a car that has passed you is still worth
recording until it's gone.

### Data source loss

If a trigger's data source stops updating entirely — a dropped HR strap, a dead sensor,
lost radar — that trigger can't tell whether effort or speed actually fell, and would hold
a recording open indefinitely. **Data Source Loss** sets how long to tolerate that before
treating the source as lost and releasing the trigger. Any other still-live trigger on the
same camera is unaffected and can keep recording on its own. 0 waits forever.

---

## Tracking recording state

Three data fields, all addable to any ride page.

**Insta360 Recording Control** — tappable, fills red while any camera is recording.

**Distance** — ride distance with a red dot flashing beside it while recording. Use it in
place of your usual Distance field and the recording indicator costs you no page space at
all.

**Insta360 Recording** — a plain numeric field, 1 while any camera is recording and 0 when
none are. Useful if you'd rather build your own layout around it.

All of them render in light or dark. Karoo doesn't tell an extension whether your ride
pages are light or dark, so set it yourself under **Ride Page Fields**. Full-width and
half-width layouts are handled automatically from whichever cell you drop the field into.

---

## Notifications

**In-ride alerts** appear on every start and stop, and always state the reason — "Speed
trigger", "Radar trigger", "Manually from Karoo field", "Manually from Karoo button", "On
the camera". So when a camera starts itself mid-descent, you know which trigger did it
rather than guessing.

**Status bar notifications** for the same events are optional, under **Notifications**.
They cover every start and stop from any source and apply to all saved cameras. On Android
13+ this prompts for notification permission the first time you enable it.

---

## Building

karoo-ext is published to GitHub Packages, so a GitHub personal access token with
`read:packages` scope is required. Put it in `~/.gradle/gradle.properties`:

```
gpr.user=your-github-username
gpr.key=ghp_yourtoken
```

Then:

```
gradle assembleDebug
```

Output lands in `app/build/outputs/apk/debug/`.

`.github/workflows/build.yml` builds on every push to `main` and uploads the APK as a run
artifact; pushing a tag like `v0.1.27` also attaches it to a release. It needs the same
token as a repository secret named `KAROO_EXT_TOKEN`.

Builds are signed with the committed `app/shared-debug.keystore` so every build, local or
CI, installs over the previous one instead of requiring an uninstall. It uses Android's
standard debug credentials, which are public by design — don't reuse it for anything
published.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before tagging; nothing
derives them automatically.

## Project structure

```
app/src/main/
  kotlin/com/example/karooinsta360/
    Insta360BleClient.kt              # BLE protocol: framing, MTU, write queue, notifications
    connection/
      Insta360ConnectionManager.kt    # per-camera connection and recording state, alerts
    extension/
      Insta360Extension.kt            # karoo-ext service: trigger evaluation, bonus action
      RecordingControlDataType.kt     # tappable recording tile
      RecordingDistanceDataType.kt    # distance with recording dot
      RecordingStateDataType.kt       # numeric 1/0 field
    camera/                           # camera store and per-camera config screens
    profile/                          # profiles: trigger settings and camera selection
    RecordingReason.kt                # why a recording started or stopped
  res/xml/extension_info.xml          # data types and bonus actions declared to Karoo
```
