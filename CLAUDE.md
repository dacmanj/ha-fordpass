# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo is a fork of [marq24/ha-fordpass](https://github.com/marq24/ha-fordpass), a Home Assistant custom integration for Ford and Lincoln vehicles. The **active development target is a Hubitat Elevation port** in `hubitat/`. The HA Python code in `custom_components/fordpass/` is retained as a **reference implementation** — it is the source of truth for API behavior, data structures, and auth flow, but is not being actively developed here.

When understanding how the Ford API works, how metrics are structured, or what a command should do — read the Python first, then implement the equivalent in Groovy.

## Hubitat port (`hubitat/`) — primary work target

| File | Role |
|------|------|
| `hubitat/apps/FordPassConnect.groovy` | Hubitat app: OAuth PKCE flow, dual-token management (Ford + Autonomic), API polling, child device creation |
| `hubitat/drivers/FordPassVehicle.groovy` | Child device driver: parses vehicle data from `parseVehicleData(Map)`, exposes Hubitat capabilities and custom attributes, relays commands via `parent.*` calls |

### Driver architecture

The app (`FordPassConnect`) polls the Ford/Autonomic APIs and calls `childDevice.parseVehicleData(rawData)` each cycle. The driver is entirely passive — it only reads data pushed from the app and sends commands back up via `parent.sendVehicleCommand()`.

```
FordPass/Autonomic API
        ↓  (app polls REST + manages OAuth)
FordPassConnect.groovy  (Hubitat app)
        ↓  parseVehicleData(rawData)
FordPassVehicle.groovy  (child device driver)
        ↓  sendEvent(name, value, unit)
Hubitat device attributes / capabilities
```

`rawData` shape mirrors the HA coordinator data:
```groovy
rawData.metrics  // Map<String, {value, updateTime}> for scalars; List<{value, vehicleDoor/vehicleWheel/...}> for arrays
// GPS is nested: rawData.metrics.position.value.location → {lat, lon, alt}
// No "vehiclestatus" wrapper — metrics is at the root
```

### Hubitat-specific conventions

- **Unit conversion**: use `location.temperatureScale` ("C"/"F") for temperatures; driver preferences for pressure (PSI/kPa/BAR) and distance (km/miles). Ford API always delivers Celsius and kilometres.
- **Geofence presence**: `location.latitude` / `location.longitude` give the hub's configured coordinates. Haversine distance vs. configurable radius drives the `PresenceSensor` `present`/`not present` value.
- **Hub location guard**: if `location.latitude` is null, log a `warn` and skip presence — don't silently fail.
- **Debug logging**: gated on `settings.enableDebugLog`; unmatched/unexpected API values should log at debug, not warn.
- **`safeVal` helper**: standard pattern for extracting scalar metrics — `metrics[key]?.value`, swallows exceptions at debug level.

### Ford API field notes (discovered from real data)

- `doorStatus` `vehicleDoor` values vary by vehicle: Mach-E uses `UNSPECIFIED_FRONT`/`INNER_TAILGATE`; F150 uses `TAILGATE`, `INNER_TAILGATE`, `FRUNK`. `vehicleSide` can be `"LH"`/`"RH"` (traditional) or `"DRIVER"`/`"PASSENGER"` (Mach-E). Always handle both.
- `doorLockStatus` on Mach-E only includes `UNSPECIFIED_FRONT` (driver side) + `ALL_DOORS` — no separate passenger lock entry.
- Tire pressure values are in kPa regardless of region.
- Temperatures are always Celsius.
- Distances are always kilometres.

## Reference implementation (`custom_components/fordpass/`)

Use this to understand the API, not as code to maintain. Key files for cross-referencing:

| File | What to look up |
|------|----------------|
| `fordpass_bridge.py` | OAuth PKCE flow, token refresh, WebSocket connection, exact API endpoints and headers |
| `fordpass_handler.py` | Raw JSON → parsed values; contains commented sample API payloads for multiple vehicle types (Mach-E, F150, etc.) |
| `const.py` | OAuth IDs, region app IDs, region → login URL mapping |
| `const_tags.py` | Every entity the HA integration exposes, with the metric key name it reads from |

The commented payload samples in `fordpass_handler.py` are particularly useful when diagnosing why a metric isn't parsing correctly.

## Development notes

- **No automated tests** — all testing requires a real connected vehicle and live Ford API credentials
- **Ford's API is undocumented and changes without warning** — be defensive in all JSON parsing, use safe navigation (`?.`), and log unmatched values at debug level so they can be diagnosed
- To debug the Hubitat driver: enable **"Enable debug logging"** in driver preferences; unmatched door/window/tire entries will appear in Hubitat logs identifying the exact `vehicleDoor`/`vehicleWheel` value sent by the API
- To debug the HA reference: set `logger: custom_components.fordpass: debug` in `configuration.yaml`
