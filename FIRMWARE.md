# Supported firmware notes

This document records project compatibility constraints and behaviour confirmed during
development. It must not be treated as a vendor specification.

## Identity

| | |
|---|---|
| Tested generation | `SWI68-29958-1300R69` |
| Android | 9 (Pie) — **API 28** |
| Chipset | MediaTek **MT2712** |
| Device | `mt2712_saic_eh32` (eh32 = MG4) |
| Emulator display profile | **1920×1080 @ 160 dpi** |

## Signing caveat

Certificate equality alone does not prove an APK came from this project. The nightly OTA
therefore also requires https and an exact-host GitHub allowlist. The same caveat applies
to EVProfile's OTA.

## Vendor property IDs

The vendor VHAL is `vendor/bin/hw/android.hardware.automotive.vehicle@2.0-service`
(ARM aarch64), backed by `vendor/lib64/android.hardware.automotive.YFvehicle@2.0.so`
("YF" supplier implementation). The property IDs (`0x2160F404` SOC,
`0x2140F41C` range, …) are **compiled into that binary**, not stored in a
readable config. The project therefore treats these IDs as implementation details with
firmware-specific compatibility limits.

These IDs are therefore confirmed for **SWI68 (R69) only**. MG4 ships other
generations (SWI69/131/132/133/165) and the VHAL binary lists many SAIC
platforms (`eh32`, `as33`, `ec32`, `ip42`, …); the IDs may differ there.

Only SOC and range still ride these vendor IDs. Speed, outside temperature and
park state are read through `EVHardware`, which branches per generation
internally and so covers all six firmwares — `CarPropertyAdapter` carries just
the SWI68 EV cluster plus the standard-AAOS charge / cabin-temp reads. EVHardware
has no EV-battery abstraction for any generation, which is why SOC/range cannot yet
follow the same path. See `AGENTS.md` for the full per-signal split.

## Emulator fidelity

The `mise` emulator tasks use an API 33 automotive AVD for the car service and an API 28
AVD at **1920×1080 @ 160 dpi** for the screen. Neither exposes the SAIC vendor properties,
so vehicle-specific behaviour still requires on-vehicle validation.
