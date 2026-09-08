# karoo-insta360

A [Hammerhead Karoo](https://www.hammerhead.io) extension that controls one or more
Insta360 cameras over BLE — starting and stopping recording automatically from your ride
data, or manually from the head unit, and showing on your ride pages whether a camera is
actually rolling.

Built on [karoo-ext](https://github.com/hammerheadnav/karoo-ext). BLE protocol
reverse-engineering courtesy of
[xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl).

## What it does

**Records the parts of a ride worth keeping.** Action cameras have two failure modes:
record the whole ride and spend an evening scrubbing through four hours of nothing, or
record nothing and miss the moment. This extension starts recording when something is
happening — you're above threshold, descending fast, or a car is closing on you — and
stops when it isn't.

**Triggers** are evaluated continuously against live Karoo ride data:

| Trigger | Fires on |
|---|---|
| Heart rate | bpm at or above a start value, sustained |
| Power | watts at or above a start value, sustained |
| Speed | speed at or above a start value, sustained |
| Radar | a vehicle approaching within a set distance |

Each has separate start and stop thresholds and separate sustain durations, so a brief
spike doesn't start a recording and a momentary dip doesn't end one. Heart rate and power
are combined into a single *effort* latch; speed and radar are independent. A camera
records while any latch wants it recording.

**Manual control** is available in-ride two ways: a tappable data field on any ride page,
and a "Toggle Camera Recording" action you can bind to a controller button. A recording
you start manually is never stopped by the automatic triggers — only recordings the
triggers themselves started get auto-stopped.

**Ride page fields**, all available in light or dark to match your pages:

- **Insta360 Recording Control** — tappable; fills red while any camera is recording
- **Distance** — ride distance with a flashing red dot while recording, so the indicator
  costs you no extra page space
- **Insta360 Recording** — a plain numeric field, 1 while any camera is recording

**Multiple cameras** are supported throughout. Everything is keyed per camera, and
triggers are configured per camera within a profile.

## How it's organised

Two concepts, and the split matters:

**Cameras** are the physical devices you've paired. A camera holds its address and name,
and nothing about when to record.

**Profiles** hold all the trigger settings, and choose which of your saved cameras they
apply to. Exactly one profile is active at a time, and it drives all automatic recording.
This is what makes a Road profile and a Gravel Race profile a one-tap switch rather than a
re-entry of every threshold — with different cameras and different thresholds per camera
if you want.

## Setup walkthrough

A worked configuration: one Ace Pro on the bars, recording hard efforts and close passes.

### 1. Install

Grab the APK from [Releases](https://github.com/jpa5635/KarooInsta360Ctl/releases) and
sideload it:

```
adb install -r karoo-insta360-0.1.25-debug.apk
```

Open the app once on the Karoo and grant Bluetooth permissions when prompted.

### 2. Add the camera

Put the camera in Bluetooth pairing mode, then in the app tap **Scan for Cameras**. The
scan lists every nearby Bluetooth device rather than filtering to Insta360 ones — filtering
proved unreliable across models — so pick yours by name and tap **Add**. If it doesn't
appear, **Add by Address** takes a MAC directly.

Tap the camera to open its Configure screen and use **Start** / **Stop** to confirm control
works before going further. The camera should respond within a second or so. Give it a name
here too if you have more than one.

### 3. Create a profile

Back on the main screen, tap **Create New Profile** and name it — "Road", say. Open it, and
tick the camera you just added so this profile considers it.

### 4. Set triggers

Open the camera's entry within the profile. For the worked example:

**Effort trigger — heart rate.** Enable it. Start at 155 bpm, stop at 140 bpm, sustain 10
seconds to start and 45 seconds to stop.

The gap between start and stop values is deliberate hysteresis: with both at 155 you'd get
a recording that starts and stops repeatedly as your heart rate wobbles across the line.
The asymmetric sustain matters too — 10 seconds to start means you don't miss the beginning
of an effort, while 45 seconds to stop means a brief soft-pedal mid-climb doesn't cut the
clip in half.

**Radar trigger.** Enable it, start distance 100 metres. Every car that comes past gets
recorded, which is the footage you'll want if anything ever goes wrong.

**Speed trigger.** Enable it, start at 45 km/h, stop at 35 km/h, 3 seconds to start and 20
to stop. Fast descents, without recording every flat mile.

Leave power off unless you want it — heart rate and power share the effort latch, so
enabling both means either can start a recording.

### 5. Apply the profile

Back out to the main screen and **Apply** the profile. The active profile is named at the
top; if it says "none", nothing automatic will happen.

### 6. Add the fields

On the Karoo, edit a ride page and add **Insta360 Recording Control** — a half-width cell
is plenty. Replace your existing Distance field with this extension's **Distance** to get
the recording dot without spending a cell on it.

Then in the app's **Ride Page Fields** section, set the dark theme toggle to match your
ride pages. Karoo doesn't tell an extension whether pages are light or dark, so this can't
be detected.

### 7. Optional: a controller button

In the Karoo's own button settings, bind **Toggle Camera Recording** to a controller button
for manual override without looking at the screen.

Note if you use SRAM AXS controls: assigning a Karoo Action to a button removes its native
AXS shift mapping entirely, and only Karoo Actions support separate short and long press.
Dedicating one bonus button to this is the usual compromise.

### 8. Data source loss

In **Data Source Loss**, set a timeout in minutes. If a trigger's data source stops
updating entirely — a dropped HR strap, a dead radar — that trigger can't tell that effort
or speed actually fell, and would otherwise hold a recording open indefinitely. This bounds
it. Ten minutes is reasonable; 0 waits forever.

## Notifications

Every start and stop raises an in-ride alert stating the reason — "Speed trigger",
"Manually from Karoo field", "On the camera" — so you always know why a camera changed
state. Status bar notifications for the same events are optional, under **Notifications**.

## Building

Requires a GitHub personal access token with `read:packages` scope, since karoo-ext is
published to GitHub Packages. Put it in `~/.gradle/gradle.properties`:

```
gpr.user=your-github-username
gpr.key=ghp_yourtoken
```

Then:

```
gradle assembleDebug
```

Output lands in `app/build/outputs/apk/debug/`.

### CI

`.github/workflows/build.yml` builds on every push to `main` and uploads the APK as a run
artifact; pushing a tag like `v0.1.26` also attaches it to a release. It needs the same
token as a repository secret named `KAROO_EXT_TOKEN` (Settings → Secrets and variables →
Actions).

Builds are signed with the committed `app/shared-debug.keystore` so that every build, local
or CI, installs over the previous one instead of requiring an uninstall. It uses Android's
standard debug credentials, which are public by design — do not reuse it for anything
published.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before tagging; nothing
derives them automatically, and Android refuses to install over an equal or higher version.

## Project structure

```
app/src/main/
  kotlin/com/example/karooinsta360/
    Insta360BleClient.kt              # BLE protocol: framing, MTU, write queue, notifications
    connection/
      Insta360ConnectionManager.kt    # per-camera connection state, recording state, alerts
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

## Known limitations

- Camera-side recording detection depends on a capture-status payload whose schema isn't
  fully confirmed for the Ace Pro 2. See the development log.
- The Distance field draws its own value rather than using Karoo's numeric treatment, so it
  is close to but not pixel-identical with stock fields.
- Field light/dark is an app-wide setting, not per-placement, since data fields have no
  per-instance configuration.

## Development log

Protocol reverse-engineering, dead ends, and the reasoning behind non-obvious decisions:
[docs/DEVELOPMENT-LOG.md](docs/DEVELOPMENT-LOG.md).
