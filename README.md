**# HelixMetaADB-Menu
Root, Boot Unlocker, Cheats, Hardware, Software, And so much more! Open source so feel free to add things yourself and make forks :)
**# Helix

**Helix** is an Android app for Meta Quest headsets that combines a wireless ADB console, movement / camera mods, hardware tweaks, and root tooling in one place.

Contact: **blaku64th** on Discord if you run into issues.

---

## Features

### Console (main tab)

- Wireless ADB **pair** (6-digit code) and **connect**
- Auto-discover pairing / connect endpoints over mDNS
- Auto-reconnect every 5s after you’ve paired once
- Tries to enable `adb_wifi_enabled` when `WRITE_SECURE_SETTINGS` is granted
- Grants `WRITE_SECURE_SETTINGS` over ADB after the first successful connect (helps next boots)
- **Terminal** runs real Linux commands:
  1. Local `su` (if rooted)
  2. Local `sh`
  3. ADB shell (if connected)
- Battery readout for headset + controllers
- Open Developer Settings shortcut

### Presets tab

Categorized controls with **collapsible headers**:

| Category   | Examples |
|-----------|----------|
| **Mods**  | Fly, long arms, wall walk, grapple, platforms, gravity, camera modes, head spin, input holds |
| **Visuals** | Guardian, overlays, graphics quality, OVR metric overlay |
| **Hardware** | Root / Magisk / bootloader, LEDs, CPU/GPU, reboot |
| **Settings** | OTA blocker, telemetry / hosts, spoof build type, ADB auth |
| **Power** | CPU/GPU status, governors, temp & usage thresholds |
| **Offline** | Brightness, timeout, volume (works without ADB) |
| **Misc** | Macros (save / run / boot) |
| **Recording** | Capture FPS, bitrate, size |

### Movement & camera mods (high level)

- **Fly** – joystick / A-button / velocity (hold B)
- **Hover, Glide, Surf, Orbit, Bunny hop, Strafe, Auto-forward**
- **Wall Walk** – grip to lock aim and pull
- **Grapple** – grip + trigger
- **Platforms** – left/right grip push
- **Up/Down** – grip to enable, RT up / LT down
- **Low / High Gravity**
- **Long Arms** – behind head, break-hands (grip), IPD scale
- **Camera** – third person, shoulder, top-down, spectate, freeze
- **Rotation** – spin / upside-down / backwards head (**hold right trigger** to apply; release resets headlock)
- **Spaz**, **PSA bypass**, **checkpoint** (save / return)

### Root & system

- IonStack / V79 / “best root” helpers
- Magisk install from GitHub assets
- Bootloader unlock helpers (Quest-specific)
- OTA blocker, hosts blocker, telemetry disable lists
- RGB LED + battery-gradient LED (root)
- CPU / GPU sysfs tools and thresholds

### Boot

- `BootReceiver` can launch Helix and try wireless ADB on boot
- **Run Macros on Boot** master toggle (Settings)
- Per-macro **Boot** switch in the Macros list
- Selected macros run after ADB is up following a boot launch

---

## Requirements

- Meta Quest 2 / Pro / 3 / 3S (or similar Horizon OS device)
- Developer Mode enabled (Meta Horizon mobile app)
- Wireless debugging available in Developer options
- Android Studio (or CLI) to build from source
- Optional: root / Magisk for full hardware & OTA features

---

## How to use

### 1. Build & install

```bash
git clone <your-repo-url> Helix
cd Helix
# Open in Android Studio, or:
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install on the headset (SideQuest, MQDH, or `adb install`).

### 2. First-time wireless ADB

1. On the headset: **Settings → System → Developer → Wireless debugging → ON**
2. Open **Pair device with pairing code**
3. In Helix **Console** tab, enter the **6-digit code** → **Pair**
4. Helix discovers the connect port and connects automatically when possible

After a successful connect, Helix tries to grant itself `WRITE_SECURE_SETTINGS` so later sessions can flip wireless ADB on more easily.

### 3. Console

- Type any shell command (e.g. `id`, `getprop ro.product.model`, `ls /sdcard`) and press **Run**
- Prefer root when available; otherwise local shell or ADB

### 4. Mods

1. Connect ADB (most mods need it for `setprop` / headlock)
2. Open **Presets → Mods**
3. Expand a section (Fly, Movement, …) and toggle what you need
4. Read on-screen hints (e.g. hold RT, grip + trigger)
5. Use **Disarm Movement** to turn everything off and reset headlock

### 5. Macros

1. **Presets → Misc → Macros**
2. Name + one command per line (no `adb shell` prefix)
3. **Save** → **Run** / **Delete**
4. Optional: enable **Boot** on a macro + **Run Macros on Boot** in Settings

### 6. Root tools

Use **Hardware** for Root Setup / Magisk / status checks. These are device- and firmware-specific; read logs in the presets console area carefully.

---

## Project layout (edit guide)

Typical package: `com.helix`

```
app/src/main/java/com/helix/
├── MainActivity.kt          # Console UI, ADB pair/connect, terminal, boot hooks
├── PresetsController.kt     # Presets tab UI (headers, toggles, sliders, macros)
├── Catalog.kt               # Extra mod toggles/buttons/sliders wired into presets
├── Utils.kt                 # Buttons, toggles, custom actions, categories
├── Macros.kt                # Macro storage + boot-macro selection
├── ShellExecutor.kt         # Local su/sh + ADB command runner
├── BootReceiver.kt          # BOOT_COMPLETED → launch + wireless ADB attempt
├── AdbDiscovery.kt          # mDNS discovery for pair/connect
├── AppAdbConnectionManager.kt
│
├── Movement / headlock
│   ├── MovementMods.kt, Fly.kt, VelocityFly.kt, Hover.kt, …
│   ├── Grapple.kt, Platforms.kt, WallWalk.kt, UpDown.kt, …
│   ├── CameraMods.kt, RotationMods.kt, HeadLockLib.kt, …
│   └── IPDADB.kt, PSA.kt, Spaz.kt, …
│
├── Root / hardware
│   ├── RootLib.kt / RootHelper, IonStackRoot, MagiskUtils
│   ├── CpuUtils.kt, GpuUtils.kt, RgbLedEngine.kt
│   └── BootUnlocker, UpdateBlocker, …
│
└── UI helpers
    ├── Prefs.kt, Anim, AppContext, …
    └── res/layout/activity_main.xml, tab_*.xml
```

### Add a simple shell button

In `Utils.kt` → `Buttons`:

```kotlin
ButtonAction(
    "My Command",
    Category.SETTINGS,
    "settings put system screen_off_timeout 600000"
),
```

### Add a custom toggle (Kotlin logic)

In `Catalog.kt` → `ExtraToggles`:

```kotlin
Utils.CustomToggleAction(
    "My Mod", Utils.Category.MODS,
    onEnabled = { ctx ->
        ctx.run("setprop debug.my.mod 1")
        ctx.log("My Mod ON")
    },
    onDisabled = { ctx ->
        ctx.run("setprop debug.my.mod 0")
        ctx.log("My Mod OFF")
    }
),
```

### Add a slider

In `Catalog.kt` → `EXTRA_SLIDERS` or `Utils.Sliders`:

```kotlin
Utils.SliderAction(
    "My Level", 0, 10, 5,
    "setprop debug.my.level %VALUE%",
    Utils.Category.MODS
),
```

### Grouping under headers

`PresetsController` assigns items to collapsible sections by **label keywords** (see `renderModsGrouped`, `renderHardwareGrouped`, …).  
If a new control lands under **Other**, add a keyword to the matching `Bucket`.

### Command execution path

Presets and the console call `Utils.ActionContext.run(command)`.

In `MainActivity`, that is wired to:

```text
ShellExecutor.run(cmd) → su → sh → adb shell
```

So preset buttons automatically benefit from local root when present.

### Headlock mods pattern

Most movement mods:

1. `HeadlockHelper.armOffsetUnmanaged(ctx)` (or managed arm)
2. Loop writing translation/rotation via `writeOffset` / `writePose`
3. `disarm` / `forceDisarm` on stop

Rotation mods additionally require **right trigger held** while active (`RotationMods.requireRightTrigger`).

### Macros on boot

1. `Macros.setBootEnabled(context, true)`
2. Optionally restrict names with `Macros.setBootMacroNames`
3. `BootReceiver` starts `MainActivity` with `from_boot=true`
4. After ADB connects, `maybeRunBootMacros()` runs the selected macros

---

## Building notes

- Min SDK / target: follow the app’s `build.gradle` (Quest builds are typically API 29–32+).
- Manifest should include VR / Horizon categories as required for your distribution path.
- Permissions commonly involved:
  - `INTERNET`, nearby Wi‑Fi (discovery)
  - `RECEIVE_BOOT_COMPLETED`
  - `WRITE_SECURE_SETTINGS` (granted via `pm grant` after ADB, not normal install)
  - Optional root (no permission; `su` binary)

---

## Safety

- Movement and headlock mods alter tracking pose. Use **Disarm Movement** if something feels stuck.
- Root / Magisk / OTA blocking can brick or soft-brick if misused. Know your firmware.
- This project is for **personal / research** use on devices you own.

---

## Credits

- Inspired by patterns from community Quest tooling (including EventHorizon-style root terminal & utils).
- Maintainer contact: **blaku64th** on Discord.

---

## License

Add your preferred license here (e.g. MIT, GPL-3.0). If none is set, all rights remain with the author.
