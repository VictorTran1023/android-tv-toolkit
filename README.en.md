<div align="center">

# Android TV Toolkit

Make an Android TV usable — without root, without flashing, with a way back

Works with **TCL / FFALCON, Xiaomi / Redmi, Sony, Hisense / VIDDA, Skyworth / Coocaa, OPPO, Changhong, Konka, XGIMI, Dangbei and Android TV boxes**

![Platform](https://img.shields.io/badge/Android%20TV-9%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![HUD](https://img.shields.io/badge/HUD-Android%2010%2B-3DDC84?style=flat-square)
![Scope](https://img.shields.io/badge/Scope-Android%20TV%20%2F%20Google%20TV-1F6FEB?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB?logo=python&logoColor=white&style=flat-square)
![License](https://img.shields.io/badge/License-MIT-8E8E93?style=flat-square)
![CI](https://github.com/VictorTran1023/android-tv-toolkit/actions/workflows/ci.yml/badge.svg)

[中文](README.md) · [English](README.en.md) · [Usage guide](docs/usage.md) · [Brands & models](docs/brands.md) · [Full handbook](docs/handbook.md) · [Discussions](https://github.com/VictorTran1023/android-tv-toolkit/discussions)

</div>

---

The four walls of an Android TV — **can't connect, can't install, can't play, can't switch off** — are rarely hardware limits. Firmware changes behaviour in four places: ADB key format, a customised PackageManager, missing Google services, and preloaded daemons.

This repository is a **brand-agnostic workflow**: identify the device with `getprop` and `resolve-activity`, enable ADB and pick an install channel from the per-brand table, then keep every change reversible with snapshot → one change at a time → reboot → regression. No root, no flashing, no system partition edits.

> **New here?** Read the [usage guide](docs/usage.md) and the [brand table](docs/brands.md) first.
> They cover what to press on the TV, what to type on the PC, and which install channel to use when `adb install` is blocked.

> The repository was renamed from `tcl-tv-toolkit` to **`android-tv-toolkit`**; GitHub redirects the old URL. **The content is no longer TCL-only**; TCL is the only model fully verified by this repo, and every other brand is explicitly marked *verified / reference / unverified*.

## What you get

| Wall | Approach | Result |
| --- | --- | --- |
| `adb unauthorized`, no prompt on TV | Re-handshake with the **legacy binary key structure** the vendor adbd accepts | Authorise once, then plain `adb` works |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | Try `adb install` first; fall back to an **installer with a UI** | Verified on TCL; a channel table for other brands |
| No GMS, YouTube won't open | SmartTube + FlClash with deep-link subscription import | Stable 1080p |
| Ads, heat, background daemons | Tiered `pm disable-user` with a **gated workflow** (snapshot → change → reboot → regress) | TCL case: CPU 48% → 9%, −10 °C |
| System status without replacing the launcher | A 41 KB native HUD: VPN / CPU / RAM / **real °C** / **local weather** | Overlay strip + weather clock screensaver |

## Install (about 15 minutes)

### 1. Enable ADB on the TV

| Brand | Entry point |
| --- | --- |
| **TCL / FFALCON** | Settings → About → **Device info** → press **Up Down Left Right** → **More** → **ADB** |
| **Xiaomi / Redmi** | Settings → About → tap **Product model** repeatedly → Developer mode; then Settings → Account & security → **ADB debugging** + **Install unknown apps** |
| **Sony BRAVIA** | Settings → Device Preferences → About → tap **Build** 7 times → Developer options → **USB debugging** |
| **OPPO** | Settings → System → About → **Device model** → **Up Up Right Right** → confirm |
| **Hisense / VIDDA, Skyworth / Coocaa** | Try Settings → About → tap Build repeatedly; some models hide ADB in a factory/developer menu |
| **Other Android TV / Google TV** | Settings → Device Preferences → About → tap **Build** 7 times → Developer options → **USB debugging** |
| **Huawei / Honor (HarmonyOS), LG webOS, Samsung Tizen** | **Not supported** — not Android TV |

> Models vary a lot. See the full table, verification status and troubleshooting in [**Brands & models**](docs/brands.md).

The TV IP is under Settings → Network. The PC and TV must be on the same LAN. On first connect the TV asks to allow USB debugging — tick **Always allow**.

### 2. Connect and authorise

```powershell
adb connect <TV_IP>:5555
adb devices -l          # should say "device"

# If it stays unauthorized and the TV shows no prompt (some vendor adbd
# only parses the legacy public-key format):
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555
```

### 3. Snapshot before changing anything

```powershell
$env:TV_IP = "<TV_IP>"
.\scripts\preflight.ps1
```

The snapshot stores packages, disabled packages, all three settings tables, system properties and the current launcher / screensaver / animation values. Roll back with:

```powershell
.\scripts\restore-all.ps1
```

### 4. Install the HUD (optional)

The HUD is a standalone small APK; the scripts work without it. Build instructions are in [docs/hud-app.md](docs/hud-app.md). To install a built APK:

```bash
adb install -r tvhud.apk
```

If that is blocked:

| Brand | Channel |
| --- | --- |
| **TCL / FFALCON** | `adb install` is blocked; use **Dangbei Market → APK manager**, or TCL's multi-screen push |
| Xiaomi / Sony / most boxes | `adb install` normally works |
| Hisense / VIDDA, Skyworth / Coocaa | Try `adb install`; otherwise a USB stick + the built-in file manager |
| Others | See [Brands & models](docs/brands.md) |

Grant the optional permissions afterwards (overlay, usage stats, autostart, screensaver). `appops` support differs per brand — see [Brands & models](docs/brands.md).

### 5. Verify

```powershell
.\scripts\regress.ps1
```

The suite auto-detects the current launcher and settings package (no hard-coded `com.tcl.cyberui`), then checks Home, D-pad, Settings, app launch, Wi-Fi, outbound network, critical system packages, crashes and ANR. 0 FAIL means pass.

To delegate it to an agent, use the prompt templates in the [Usage guide](docs/usage.md).

## One verified case: TCL T7K

| Metric | Before | After | How |
| --- | --- | --- | --- |
| Far-field voice `com.tcl.walleve` | **53% of one core**, held an `AudioIn` wakelock 24 h | 0 (process gone) | `pm disable-user` |
| Whole-device CPU | 48% | **9%** | same |
| Temperature while playing | **87 °C** | 71–77 °C | same |
| Available RAM | 85 MB | 639 MB | same |
| Promo / boot-ad / OTA packages | 8 resident | all disabled, **still disabled after reboot** | `pm disable-user` |

These numbers are a sample, not a promise for every TV. The commands, evidence, traps and rollback are in [docs/t7k-optimization.md](docs/t7k-optimization.md). Other brands have different package names and different gains; the method is the same.

One detail that matters: after `pm disable-user`, **wait 60 seconds before rebooting**. PackageManager has not persisted the restriction yet, and a fast reboot silently restores everything.

## Why this does not brick the TV

- `pm disable-user` only changes the per-user package state in `/data`; it does not touch the system partition or the bootloader.
- `settings put` only changes the settings database.
- The preflight rules keep hands off the launcher, settings, systemui, remote/pairing services, Wi-Fi holders and system-critical packages.
- No root, no flashing, no `build.prop` edits, no launcher replacement.

The worst case is a missing feature: `pm enable`, or a factory reset (which loses data). A real brick needs a bootloader or system-image write — out of scope.

## The HUD (`hud/`)

![HUD strip](docs/images/hud-strip-1.5.png)

A ~41 KB native app: two rows, fades out after 10 seconds, never takes remote focus.

| Reading | Source | Note |
| --- | --- | --- |
| Time / CPU / RAM / throughput | `/proc`, `TrafficStats` | 1 Hz, no network polling |
| VPN state | `ConnectivityManager` | read-only |
| **CPU temperature °C** | embedded ADB → `dumpsys thermalservice` | amber ≥60 °C, red ≥72 °C |
| Clock screensaver | `DreamService` | replaces the vendor art screensaver, one command back |
| **Local weather** | domestic IP lookup → Open-Meteo (fallback wttr.in) | shown on the screensaver; 30 min cache; manual city override |

Not obtainable: GPU utilisation (`mali0/gpuinfo` denies access; no GED/devfreq node). The model is known: **ARM Mali-G52** on the T7K.

Build (no Gradle), per-brand install channels, and what an upgrade resets: [docs/hud-app.md](docs/hud-app.md).

## Repository layout

```
.
├── docs/                 Handbook and deep dives
│   ├── usage.md          Usage guide (TV side / PC side / delegating to an agent)
│   ├── brands.md         Per-brand ADB, install channels, autostart caveats
│   ├── handbook.md       Full manual (connect → authorise → sideload → proxy → tune)
│   ├── t7k-optimization.md   TCL T7K case study (data, evidence, rollback)
│   ├── adb-inside-tv.md  ADB from inside the app: how, and its limits
│   ├── hud-app.md        Build, install, upgrade cost
│   ├── hud-design.md     Strip / screensaver design spec
│   ├── regression.md     Regression checks
│   └── ui-study.md       Launcher UI design archive (shelved)
├── hud/                  Native HUD sources (Java, no third-party deps)
├── scripts/              Python tools and PowerShell gate/regression scripts
└── .github/workflows/    CI: build APK, self-check scripts
```

## Documentation

| Document | Contents |
| --- | --- |
| [usage.md](docs/usage.md) | **Usage guide**: TV presses, PC commands, agent prompts |
| [brands.md](docs/brands.md) | **Brand table**: ADB entry, install channel, autostart limits, verified models |
| [handbook.md](docs/handbook.md) | Environment, discovery, authorisation, sideload, proxy, YouTube, tuning, failure table |
| [t7k-optimization.md](docs/t7k-optimization.md) | TCL T7K case study: baseline, gates, data, traps |
| [adb-inside-tv.md](docs/adb-inside-tv.md) | How an app runs ADB against its own device, and why that is safe here |
| [hud-app.md](docs/hud-app.md) | Building and installing the HUD (per-brand channels and upgrade cost) |
| [hud-design.md](docs/hud-design.md) | Strip and screensaver design spec |
| [regression.md](docs/regression.md) | Checks and pass criteria |
| [ui-study.md](docs/ui-study.md) | Launcher UI design archive (shelved) |

## Quick start

```bash
git clone https://github.com/VictorTran1023/android-tv-toolkit.git
cd android-tv-toolkit

adb connect <TV_IP>:5555
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555   # only if unauthorized
```

```powershell
$env:TV_IP = "<TV_IP>"
.\scripts\preflight.ps1      # snapshot
# …change one thing at a time…
.\scripts\regress.ps1        # regression
.\scripts\restore-all.ps1    # roll back when needed
```

## Rollback

Everything is `pm disable-user` / `settings put`, reversible with one command:

```bash
adb shell pm enable <package>                  # restore one package
powershell -File scripts/restore-all.ps1       # restore all settings from snapshot
```

## Disclaimer

For debugging and learning on devices you own. Sideloading and proxy tooling are your own responsibility, and local law applies.
No nodes, accounts or subscriptions are provided here.

## License

[MIT](LICENSE)
