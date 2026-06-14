# FordPass Connect — Hubitat Elevation Port

Hubitat port of [marq24/ha-fordpass](https://github.com/marq24/ha-fordpass), bringing Ford and Lincoln connected vehicle support to Hubitat Elevation.

---

## Files

| File | Role |
|------|------|
| `apps/FordPassConnect.groovy` | Hubitat app — OAuth PKCE flow, token management, API polling, child device creation |
| `drivers/FordPassVehicle.groovy` | Child device driver — parses vehicle data, exposes capabilities and attributes, relays commands |

---

## Installation

Install both files via the Hubitat Package Manager (HPM) or manually:

1. **Drivers Code** → paste `FordPassVehicle.groovy` → Save
2. **Apps Code** → paste `FordPassConnect.groovy` → Save
3. **Apps** → **Add User App** → **FordPass Connect**

---

## Setup

The app walks you through four pages:

1. **Main** — status overview; shows token state and last poll time
2. **Region** — select brand (Ford or Lincoln) and region (USA, Canada, EU, AU, …)
3. **Auth** — generates a PKCE code challenge, gives you a Ford login URL to open in a browser, and accepts the redirect URL you paste back in
4. **VIN** — lists vehicles on the account; select one to create the child device

After setup the app polls the Ford/Autonomic APIs on a configurable interval and pushes data to the child device.

---

## Supported Vehicles

Any Ford or Lincoln with an active FordPass or Lincoln Way connectivity subscription. Feature availability varies by vehicle:

| Feature | Petrol/Diesel | PHEV | Full EV |
|---------|:---:|:---:|:---:|
| Fuel level / range | ✓ | ✓ | — |
| Battery SOC / range | — | ✓ | ✓ |
| Charging status / plug | — | ✓ | ✓ |
| Remote start | ✓ | ✓ | ✓ |
| Lock / unlock | ✓ | ✓ | ✓ |
| Door / window status | ✓ | ✓ | ✓ |
| Tire pressures | ✓ | ✓ | ✓ |
| GPS / presence | ✓ | ✓ | ✓ |
| Guard mode | ✓ (select) | ✓ (select) | ✓ (select) |
| Preconditioning | — | ✓ | ✓ |

---

## Driver Capabilities & Attributes

### Capabilities

| Capability | Attribute | Values |
|-----------|-----------|--------|
| Lock | `lock` | `locked` / `unlocked` |
| Switch | `switch` | `on` (remote start active) / `off` |
| PresenceSensor | `presence` | `present` / `not present` |
| TemperatureMeasurement | `temperature` | outside temp in hub's scale |
| Refresh | — | triggers immediate poll |

### Custom Attributes

**Distance / range**
- `odometer` — km or miles (per preference)
- `fuelLevel` — %
- `fuelRange` — km or miles
- `batterySOC` — % (EV/PHEV)
- `batteryRange` — km or miles (EV/PHEV)

**Tires**
- `tirePressureFL / FR / RL / RR` — in selected unit
- `tirePressureUnit` — PSI / kPa / BAR

**Doors** (CLOSED / OPEN / AJAR)
- `doorStatusDriver / Passenger / RearLeft / RearRight / Hood / Tailgate`

**Windows** (open / closed)
- `windowStatusDriver / Passenger / RearLeft / RearRight`

**Ignition / security**
- `ignition` — Off / On / Start / Run
- `lockState` — LOCKED / PARTLY_LOCKED / UNLOCKED
- `alarmStatus`
- `deepSleepMode`

**Fluids / engine**
- `oilLife` — %
- `engineOilTemp` / `engineCoolantTemp` — in hub's temperature scale

**EV / charging**
- `chargingStatus` — NOT_READY / IN_PROGRESS / COMPLETE / PAUSED / SCHEDULED
- `plugStatus` — CONNECTED / DISCONNECTED / CHARGING
- `chargingPower` — kW
- `targetSOC` — %

**GPS**
- `latitude` / `longitude` / `speed` / `heading`
- `lastUpdated`

### Commands

| Command | Description |
|---------|-------------|
| `lock()` / `unlock()` | Lock or unlock all doors |
| `remoteStart()` / `cancelRemoteStart()` | Remote engine/climate start |
| `honkAndFlash()` | Panic cue (honk + lights) |
| `requestStatusRefresh()` | Tell vehicle to push a fresh status update (can take up to 5 min) |
| `enableGuardMode()` / `disableGuardMode()` | Guard mode (select vehicles) |
| `preconditionStart()` / `preconditionStop()` / `preconditionExtend()` | Cabin preconditioning (EV/PHEV) |
| `refresh()` | Trigger an immediate app poll |

---

## Driver Preferences

| Preference | Default | Notes |
|-----------|---------|-------|
| Tire pressure unit | PSI | PSI / kPa / BAR |
| Distance unit | miles | miles / km |
| Home geofence radius | 200 m | Radius around hub location for presence detection |
| Enable debug logging | off | Auto-disables after 2 hours |

### Geofence presence

Set the hub's location under **Settings → Location** in Hubitat. The driver computes the Haversine distance between the vehicle's GPS position and the hub's coordinates; if within the configured radius it reports `present`, otherwise `not present`.

---

## Debugging

1. Enable **"Enable debug logging"** in driver preferences — logs auto-disable after 2 hours.
2. Open **Hubitat Logs** filtered to the device name.
3. Unmatched door, window, or tire API values are logged at debug level with the raw `vehicleDoor` / `vehicleWindow` / `vehicleWheel` field values — useful for diagnosing vehicles with non-standard field names (e.g. Mach-E uses `UNSPECIFIED_FRONT` instead of `FRONT_LEFT`).

> **Ford's API is undocumented and changes without warning.** All JSON parsing is defensive — unexpected fields are logged at debug level rather than causing errors.

---

## Token Lifecycle

- **Ford Foundational token** — refreshed automatically before each API call; a background job also runs every 4 minutes as a safety net.
- **Autonomic token** — exchanged using the Ford token; refreshed on the same schedule.
- Token state is stored in the app's `state` map and survives hub reboots.

---

## Known Limitations

- No automated tests — all verification requires a real connected vehicle with live Ford API credentials.
- Guard mode uses a separate Ford MPS endpoint; availability is vehicle-dependent.
- Preconditioning uses the Ford RCC API; only available on Mach-E and RCC-capable vehicles.
- `requestStatusRefresh()` asks the vehicle to push an update — the vehicle must have cellular connectivity and may take up to 5 minutes to respond.

---

## Credits

Ported from [marq24/ha-fordpass](https://github.com/marq24/ha-fordpass). All API behavior, data structures, and auth flow are derived from that Home Assistant integration.
