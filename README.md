# EVABRPUploader

<p align="center"><img src="docs/logo.svg" width="440" alt="EVABRPUploader"></p>

[![Tests](https://github.com/malys/EVABRPUploader/actions/workflows/tests.yml/badge.svg)](https://github.com/malys/EVABRPUploader/actions/workflows/tests.yml)
[![Security](https://github.com/malys/EVABRPUploader/actions/workflows/security.yml/badge.svg)](https://github.com/malys/EVABRPUploader/actions/workflows/security.yml)
[![Unstable](https://github.com/malys/EVABRPUploader/actions/workflows/unstable.yml/badge.svg)](https://github.com/malys/EVABRPUploader/actions/workflows/unstable.yml)
[![Release](https://img.shields.io/github/v/release/malys/EVABRPUploader?include_prereleases&sort=semver)](https://github.com/malys/EVABRPUploader/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Part of EVSuite](https://img.shields.io/badge/part%20of-EVSuite-2f81f7)](https://malys.github.io/EVSuite/)

> ⚠️ **This software runs on a vehicle, with no warranty and no liability.** Read
> [DISCLAIMER.md](DISCLAIMER.md) before installing. It is provided "as is"; installing it is
> your decision and your risk. Not affiliated with Iternio.
> MG and MG4 are third-party marks used only to identify compatibility; this independent
> project is not affiliated with or approved by SAIC Motor or MG Motor.

Sends live telemetry from an **MG4 (SAIC eh32)** to
[A Better Route Planner](https://abetterrouteplanner.com) — battery level, speed, range,
temperature, charging state and position — straight from the car's own APIs.

No OBD dongle. No Home Assistant. No phone in the loop. The app runs on the car's head
unit and talks to ABRP directly.

EVABRPUploader is **independent**. It reads the vehicle through the shared
[EVHardware](https://github.com/malys/EVHardware) layer and needs no other EVSuite app.
It is a substantially reworked fork of Leon Kernan's `ABRP_Uploader` — see [Credits](#credits).

## Part of EVSuite

EVABRPUploader is one app of [**EVSuite**](https://malys.github.io/EVSuite/), a family of independent,
offline-first apps for the MG4 head unit (Android Automotive OS 9). Each app installs on its
own — pick only what you need. User guides and install instructions:
<https://malys.github.io/EVSuite/>.

Discover the rest of the suite:

[![EVProfile](https://img.shields.io/badge/EVProfile-settings%20%26%20drive%20profiles-2f81f7?logo=github)](https://github.com/malys/EVProfile)
[![EVTasker](https://img.shields.io/badge/EVTasker-rule%20automation-2f81f7?logo=github)](https://github.com/malys/EVTasker)
[![EVChargePilot](https://img.shields.io/badge/EVChargePilot-energy%20%26%20trips-2f81f7?logo=github)](https://github.com/malys/EVChargePilot)
[![EVLauncher](https://img.shields.io/badge/EVLauncher-home%20launcher-2f81f7?logo=github)](https://github.com/malys/EVLauncher)
[![EVSwipe](https://img.shields.io/badge/EVSwipe-swipe%20shortcuts-2f81f7?logo=github)](https://github.com/malys/EVSwipe)
[![EVHardware](https://img.shields.io/badge/EVHardware-shared%20vehicle%20library-2f81f7?logo=github)](https://github.com/malys/EVHardware)

---

## Contents

- [Screenshots](#screenshots)
- [How it works](#how-it-works)
- [Configuration](#configuration)
- [Install](#install)
- [Building](#building)
- [Project documents](#project-documents)
- [Security](#security)
- [Contributing](#contributing)
- [Legal](#legal)
- [Credits](#credits)

## Screenshots
<p align="center">
  <img src="screenshots/evabrp1.png" width="280" alt="EVABRPUploader screenshot 1">
  <img src="screenshots/evabrp2.png" width="280" alt="EVABRPUploader screenshot 2">
</p>

---

## How it works
| Signal | Source | Firmwares |
|---|---|---|
| State of charge | `EVHardware` vendor charging service, with a SWI68 property fallback | all supported (fallback SWI68 only) |
| Range | `EVHardware` vendor charging service, standard AAOS property, then SWI68 fallback | all supported |
| Speed, parked state | `EVHardware` shared energy snapshot | all supported |
| Outside temperature | The head unit's weather broadcast, falling back to its map-service query; the vehicle sensor first where one exists | all supported (the vehicle sensor is absent on MG4) |
| Charging, DC fast charging | Charge port state + charge rate heuristic | where the VHAL implements them |
| Charged energy this session | `EVHardware` charging-session integration | where the charge rate is readable |
| State of energy, pack capacity, pack temperature | `EVHardware` typed standard-AAOS reads | where the VHAL implements them |
| Odometer, tyre pressures | Standard AAOS properties (privileged) | platform-signed installs only |
| Cabin temperature | `EVHardware` typed HVAC read | where the VHAL implements it |
| Climate setpoint | `EVHardware` shared energy snapshot | all supported |
| Position, elevation, heading | GPS | — |

All vehicle telemetry goes through **EVHardware**, which detects the generation
(SWI68/69/131/132/133/165), owns property ids and units, and applies the shared nullable
fallback order. `CarPropertyAdapter` remains only as transport for the debug-only VHAL probe
and is never instantiated by the production service. See [`FIRMWARE.md`](FIRMWARE.md).

Odometer and tyre pressures sit behind privileged AAOS permissions: a platform-signed
install sends them, any other install simply omits them.

A property the car will not give up is **omitted** from the payload, never sent as zero —
so ABRP is never told the battery is empty because a read failed. Position is held to the
same rule in time as well as in value: a GPS fix older than `max(5 min, 3 x your upload
interval)` is dropped rather than resent, so a stale fix cannot report the car back at the
place it set off from.

**The app never writes to the car.** It only reads. Any write path would be a bug; see
[`SECURITY.md`](SECURITY.md).

## Configuration
| Setting | Default | Notes |
|---|---|---|
| Upload frequency | 60 s | 15 / 30 / 60 / 120 / 300 s. Applies while driving |
| More often at low battery | On | Tightens to 15 s below the threshold |
| Low battery threshold | 20 % | Editable, 1–99 |
| Service on/off | Off | Also the "stop uploading" control |

Parked and unplugged, the app drops to **one upload every 15 minutes** on its own,
whatever the setting. A state change — parked, plugged in, unplugged — always uploads
immediately.

### Config file

To avoid typing on the head unit, put your settings in a plain text file and tap **Import file**
on the **ABRP** tab. The app fills in whatever the file contains. Nothing is sent — press
**Test**, then **Save** as usual.

The head unit has no file picker, so the file is not chosen by hand: copy it from a PC into

```
Android/data/com.evsuite.abrp/files/
```

on the USB stick (or on the head unit's internal storage), then tap **Import file**. The app
scans that folder on every mounted volume and takes the first file that parses — the name does
not matter. If nothing is found it shows the exact path to copy to. On a phone or tablet, where
a document picker exists, the button falls back to letting you pick any file.

The file is a `key = value` list, one per line. Blank lines and lines starting with `#` are
ignored, keys are case-insensitive, and any key you leave out keeps its current value:

```
# ABRP Uploader config
api_key = your-abrp-api-key
token   = your-abrp-user-token

# optional — the cadence controls, same values as the UI
interval_sec    = 60      # snapped to 15 / 30 / 60 / 120 / 300
boost_low_soc   = true
low_soc_percent = 20      # clamped to 1–99
```

Only `api_key` and `token` are usually needed. The file is read once on import and not kept —
your credentials live only in the app's encrypted store afterwards.

### Power

The app holds **no wake lock** and schedules **no alarms**, so it cannot wake a sleeping
head unit; it costs nothing while the car is off. While the car is on, both the upload tick
and the GPS subscription cost power, and the GPS interval follows your configured
frequency.

At defaults that is roughly **59 uploads/hour driving** and **3/hour parked**.

## Install
The MG4 head unit has no visible way to open Settings or install an APK. The known route
in (via the on-screen keyboard) is:

1. Open any app with a text field and tap it to raise the on-screen keyboard — e.g. the
   Amazon Music app's email/login field.
2. **Long-press** the comma `,` (or the `@`) key on the keyboard.
3. Tap **"Language settings"**.
4. Tap the **search** icon in the top bar and type **`backup`**. It opens an empty page —
   now press the **back** arrow, and you land in Android's Settings panel.
5. Enable **Developer options**, and turn on **"Install unknown apps"** (unknown sources).
6. In Settings, search **`storage`** — you now have access to internal storage and the USB
   key. Navigate to the APK and tap it to install.

> ⚠️ You are enabling developer options and sideloading on a car. Do this **parked**, and
> only with an APK you trust. See [DISCLAIMER.md](DISCLAIMER.md).

### Channels

Two channels. Pick one — they install side by side.

| Channel | Auto-update | Use it if |
|---|---|---|
| **Stable** | No. Contains no updater at all. | You want the car to run what you put on it |
| **Unstable** | Yes, from GitHub pre-releases | You are testing and want fixes as they land |

Grab the APK from [Releases](https://github.com/malys/EVABRPUploader/releases). Stable builds are the tagged ones; unstable builds
are marked pre-release.

### Getting the APK onto the car

The MG4 has no visible file manager. To reach it:

1. Open **Bluetooth Settings** and select the car name.
2. Long-press the **comma** key until the keyboard settings appear.
3. **Android Keyboard Settings → Languages**.
4. Search for `file` in the search box at the top.
5. That gives you the Files app.

From there, open your USB stick and tap the APK.

### First run

1. Open the app.
2. By default, the app prefills the open-source telemetry API key published by the
   [SAIC Python MQTT Gateway](https://github.com/SAIC-iSmart-API/saic-python-mqtt-gateway#abrp-api-integration).
   You can replace it with your own key. Get a token from your ABRP account.
3. Paste the token, press **Test** (read-only — it does not send telemetry), then **Save**.
4. Turn the switch on. The service restarts with the car from then on.

Typing a long API key and token on the car's on-screen keyboard is painful. Instead you can
put them in a text file and tap **Import file** — see [Config file](#config-file) below.

## Building
Requires JDK 17 and the Android SDK. With [mise](https://mise.jdev):

```bash
mise install          # JDK
mise run bootstrap    # Android SDK + local.properties
mise run check        # permission gate + lint + tests
mise run build        # debug APK
```

Without mise: set `JAVA_HOME` to a JDK 17, write `sdk.dir` into `local.properties`, then
`./gradlew assembleStableDebug`.

Release builds are signed from environment variables. These sign the **APK** and have
nothing to do with your ABRP API key, which you type into the app itself:

| Variable | What it is |
|---|---|
| `SIGNING_KEYSTORE` | Path to the keystore file (CI decodes `SIGNING_KEYSTORE_BASE64` into one) |
| `SIGNING_STORE_PASSWORD` | Opens the keystore container |
| `SIGNING_KEY_ALIAS` | Which key inside it to sign with (default `platform`) |
| `SIGNING_KEY_PASSWORD` | Opens that particular key — a keystore protects each key separately |

**Never commit a keystore or a password.** Put them in `mise.local.toml`, which is
gitignored, or in GitHub Actions secrets.

### Layout
```
app/src/main      upload service, ABRP payload and UI
EVHardware        shared vehicle reads, energy snapshot and integration
app/src/stable    no-op update hook — the stable channel cannot self-update
app/src/unstable  audited updater policy; runtime trigger currently suspended
app/src/debug     VHAL probe tools, absent from every release build
app/src/test      JVM unit tests
```

## Project documents
| Document | What it covers |
|---|---|
| [DESIGN.md](DESIGN.md) | The EVSuite design system — colour, type, touch targets, icons |
| [AGENTS.md](AGENTS.md) | Context for AI agents working in this repository |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to build, test and submit a change |
| [FIRMWARE.md](FIRMWARE.md) | Supported firmware generations and confirmed behaviour |
| [SECURITY.md](SECURITY.md) | Threat model and vulnerability disclosure |
| [DISCLAIMER.md](DISCLAIMER.md) | Vehicle-safety disclaimer — read before installing |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [LICENSE.md](LICENSE.md) | Licence and attribution notes |

## Security
See [SECURITY.md](SECURITY.md) for the threat model and how to report a vulnerability
privately.

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md). Short version: this code runs on a moving
vehicle, so changes need tests and a clear account of what you verified on a car and what
you did not.

## Legal
Released under the MIT License. See [LICENSE](LICENSE) and [LICENSE.md](LICENSE.md); the
copyright notice naming Leon Kernan must travel with any copy. No warranty, no liability —
see [DISCLAIMER.md](DISCLAIMER.md).

## Credits
- Original app: **Leon Kernan** (`ABRP_Uploader`, MIT-licensed here) — the car-API approach
  and the first working uploader.
- ABRP telemetry API: [Iternio](https://documenter.getpostman.com/view/7396339/SWTK5a8w).
- Sibling project: **EVProfile**, whose security and CI patterns this repo reuses.
