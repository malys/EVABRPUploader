# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.4] - 2026-08-25

### Fixed

- **The car was reported where it used to be, not where it is.** A GPS fix had no expiry, so
  `lastLocation` kept whatever it last held. Two paths turned that into a wrong position:
  start-up seeded it from `getLastKnownLocation()` unconditionally — with a cold receiver that
  is the *previous* drive's fix, uploaded stamped with the current time for the first minutes
  of every trip — and once the receiver stopped delivering (garage, tunnel) the same fix was
  resent forever. A fix is now dropped past `max(5 min, 3 x upload interval)`, measured on the
  monotonic clock rather than the wall clock, which the head unit adjusts from GPS itself.
  Position is then omitted and ABRP keeps its own last point instead of being moved backwards.
- **`ext_temp` could be sent as a freezing day the car never measured.** It was the only
  temperature without a plausibility guard, while `cabin_temp`, `batt_temp` and `hvac_setpoint`
  all had one. A VHAL that does not implement `ENV_OUTSIDE_TEMPERATURE` returns `0.0` rather
  than throwing, so the read looked successful and `ext_temp: 0` went to ABRP's consumption
  model. Now guarded like the others (-50..80 degC, exactly 0.0 excluded). A genuine 0 degC is
  lost with it — the cheaper of the two errors, and the same trade-off already made three times.

### Added

- **A one-line summary of every upload in the log.** `TelemetryPayload.summarize()` renders
  what actually went on the wire, derived from the JSON itself so it cannot drift from the
  payload it describes, and names the fields that were left out. That is what separates a read
  that failed from a read that returned something wrong — the question this release started from.
  Dropped stale fixes are logged with their age.

## [2.1.3] - 2026-08-25

### Removed

- `android.car.permission.CAR_POWERTRAIN` — declared since the first release and never used.
  The app reads no powertrain property: park state comes from the vendor gear services through
  EVHardware, and nothing here touches the standard AAOS gear or ignition properties the
  permission guards. Dropped from the manifest and from the security allowlist.

## [2.1.2] - 2026-08-25

### Fixed

- **The uploader starts on a car that has not granted position yet.** The service declares the
  `location` foreground type, and since Android 14 every declared type is checked against the
  permissions held at the moment it enters the foreground — so a fresh install, or a boot
  before the app had ever been opened, killed the service in its own `onCreate` before the
  first tick. The type is now claimed only while the permission behind it is held, and the
  activity asks for the permission *before* starting the service rather than after.
- **Outside and cabin temperature reach ABRP again.** Both were declared and read since 2.1.0
  but never requested at runtime, so both properties answered as unreadable and were dropped
  from every payload.
- **The service notification is visible.** `POST_NOTIFICATIONS` was declared and never asked
  for; the only status the service has was being dropped silently on Android 13 and later.
- A permission granted while the service is already running now takes effect at once instead
  of at the next boot.

## [2.1.1] - 2026-08-23

### Fixed

- Restored stable release builds by approving the read-only odometer, tyre-pressure and
  battery-capacity permissions in the security allowlist.

## [2.1.0] - 2026-08-23

### Added

- The telemetry payload now carries every ABRP field this vehicle can actually answer:
  pack temperature, usable capacity, state of energy, odometer, climate setpoint, the four
  tyre pressures, and `kwh_charged` for the running charge session. `kwh_charged` has no
  counter in the VHAL, so it is integrated from the instantaneous charge rate on every
  sample tick — including the ticks the cadence policy throttles away — and resets when a
  new session starts.
- Range falls back to the standard AAOS `RANGE_REMAINING` property when the SWI68 vendor
  cluster answers nothing, so range now reaches ABRP on other firmware generations too.

### Fixed

- Three vehicle property IDs were off by one and had silently disabled their fields: the
  charge-port read was the flap (`EV_CHARGE_PORT_OPEN`) rather than the cable
  (`EV_CHARGE_PORT_CONNECTED`), and the cabin-temperature ID was not a VehiclePropertyIds
  value at all, so `cabin_temp` had never been sent.

## [2.0.1] - 2026-08-22

### Fixed

- Telemetry no longer stops while the car is charging. Charge detection relied solely on
  the standard AAOS charge-port property, which this VHAL need not implement: a plugged-in
  car reading "unplugged" was treated as parked and idle and throttled to one sample every
  15 minutes, so ABRP never saw the SOC curve it needs to size a charging stop. The charge
  rate and, failing that, a rising SOC on a parked car are now used as fallbacks.
- `power` is now signed ABRP's way — positive leaving the battery, negative charging. The
  vehicle's charge rate uses the opposite convention and was being forwarded unchanged, so
  ABRP saw a fast-charging car as discharging.

## [2.0.0] - 2026-08-15

### ⚠️ Breaking — existing users must install once more

- Renamed from MG4ABRPUploader to **EVABRPUploader**, and the application id changed from
  `com.mg4.abrptelemetry` to **`com.evsuite.abrp`**. Android treats this as a different app,
  so it does not update an existing install: it is added next to it and starts with no ABRP
  credentials and no permissions. Re-enter your ABRP token here, confirm telemetry reaches
  your account, and only then uninstall the old app.

## [1.1.0] - 2026-08-10

### Changed

- Reworked the main screen as three swipeable ABRP, Service and Log pages with a fixed,
  high-contrast top navigation bar and consistent selected-state accessibility.
- Updated the shared EVHardware dependency to the current catalogue and diagnostics layer.

## [1.0.3] - 2026-08-09

### Fixed

- Corrected stable release packaging and metadata.

## [1.0.2] - 2026-08-04

### Added

- Add an in-app About surface with project information and a repository QR code.

### Security

- Keep unstable OTA downloads in private cache, validate every redirect, verify the APK
  signing certificate, and require an explicit successful `pm install` result.
- Remove the public-download update path and its storage permission exposure.

## [1.0.1] - 2026-08-03

### Changed

- Prefill the ABRP API-key input with the public open-source telemetry key while
  preserving any custom key already saved by the user.

## [1.0.0] - 2026-08-02

### Added

- API key and token inputs are masked by default, with a password visibility
  toggle sized for in-car touch targets.
- CONTRIBUTING.md, DESIGN.md and this CHANGELOG.
- Security issue template.

### Changed

- Adopted the EVSuite design system. Colour, spacing, type and component styles now
  come from shared tokens (`values/colors.xml`, `values/dimens.xml`,
  `values/styles_ev.xml`), generated by `tools/sync-tokens.mjs` and specified in
  [DESIGN.md](DESIGN.md).
- Theme is now `Theme.Material3.DayNight.NoActionBar`: the app follows the vehicle's
  day/night setting, and the light palette is held to the same 7:1 contrast floor as
  the dark one.
- `ev_outline` raised from `#4A525B` to `#7A8492`. The old value was 2.25:1 against
  `ev_surface`, below the 3:1 floor for non-text UI, so the card border it was meant
  to provide effectively was not there.
- Launcher icon replaced with the EVSuite adaptive icon: charcoal tile, white glyph
  with a single red accent, product caption. Vector only — the five legacy density
  bitmap buckets are gone.
- README restructured to the shared EVSuite skeleton; table of contents is now
  generated by `tools/sync-docs.mjs`.
- Repaired bug/feature issue templates (duplicate YAML key, field ordering).
