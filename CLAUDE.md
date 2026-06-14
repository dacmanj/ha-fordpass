# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**ha-fordpass** is a Home Assistant custom integration for Ford and Lincoln vehicles via the FordPass/Lincoln Way API. It is a **cloud push integration** — data arrives over WebSocket in real time; polling is the fallback only. The API is reverse-engineered from the official mobile apps and Ford can break it without warning.

The `hubitat/` directory is a **separate Groovy port** of the same integration for the Hubitat Elevation hub. It is functionally parallel to the Python HA integration and shares the same API constants and auth flow, but is independent code.

## Architecture (HA integration — `custom_components/fordpass/`)

### Data flow

```
FordPass API (WebSocket + REST)
        ↓
fordpass_bridge.py      — OAuth token lifecycle, WebSocket connection, all API calls
        ↓
FordPassDataUpdateCoordinator (in __init__.py)
        ↓  coordinator.data = {metrics, states, events, vehicles, messages, rcc, ...}
fordpass_handler.py     — parses raw Ford JSON into HA-friendly values
        ↓
const_tags.py           — Tag enum wires every entity to handler functions
        ↓
sensor.py / switch.py / lock.py / button.py / …  — thin HA platform files
```

### Key files

| File | Role |
|------|------|
| `fordpass_bridge.py` | WebSocket manager, OAuth PKCE flow, every Ford API call |
| `fordpass_handler.py` | All data extraction: metrics → entity states/attributes |
| `const_tags.py` | `Tag` enum — one entry per entity, with state/attr/command callbacks |
| `__init__.py` | Integration setup, coordinator lifecycle, HA service registration |
| `config_flow.py` | OAuth setup UI and config schema |
| `const.py` | Hard constants: version, region app IDs, OAuth IDs |
| `const_shared.py` | Shared constants (pressure units, manufacturers, coordinator key) |

### Entity pattern

Every entity is a `Tag` in `const_tags.py`:

```python
Tag.BATTERY_SOC = ApiKey(
    key="batterySOCActual",
    state_fn=lambda data, prev: FordpassDataHandler.get_battery_soc(data),
    attrs_fn=FordpassDataHandler.get_battery_attrs,
)
```

- `state_fn(data, prev_state)` — reads from `coordinator.data`, returns the entity state
- `attrs_fn(data, units)` — returns extra attributes dict
- `on_off_fn` / `select_fn` / `press_fn` — async callbacks for write operations

Platform files loop over their tag list and create entities automatically; no platform file changes are needed when adding a new sensor.

### Adding a new sensor

1. Add a `Tag` entry in `const_tags.py` with `state_fn` pointing to a `FordpassDataHandler` method
2. Add an `ExtSensorEntityDescription` to the `SENSORS` list in `const_tags.py`
3. Add the data extraction method to `fordpass_handler.py`

### Adding a new command (button/switch)

```python
# const_tags.py
MY_CMD = ApiKey(key="myCmd", press_fn=FordpassDataHandler.my_command_handler)

# fordpass_handler.py
@staticmethod
async def my_command_handler(coordinator, vehicle):
    return await vehicle.send_command("api_endpoint", {"param": "value"})
```

### Vehicle capability detection

Use `coordinator.tag_not_supported_by_vehicle(tag)` before creating entities. Tags in `EV_ONLY_TAGS`, `FUEL_OR_PEV_ONLY_TAGS`, and `RCC_TAGS` (defined in `const_tags.py`) are filtered at setup time based on detected engine type.

### Authentication & tokens

- OAuth 2.0 PKCE flow; initial token extracted from browser network tab (see `doc/OBTAINING_TOKEN.md`)
- Tokens stored outside the component directory at `$HA_CONFIG/.storage/fordpass_tokens.json` (not tracked by git)
- Access token expires every ~5 minutes; `fordpass_bridge.py` auto-refreshes before every API call
- 401 responses trigger `_check_for_reauth()` in the coordinator

### WebSocket watchdog

A watchdog timer fires every 64 seconds (`WEBSOCKET_WATCHDOG_INTERVAL`). If the WebSocket is unhealthy it reconnects. If the WS dies between watchdog ticks, data staleness is bounded by 64 s.

### Error handling convention

- `UNSUPPORTED` (string constant) — this metric does not exist for this vehicle
- `None` — data not yet received
- Log at `DEBUG` for expected API failures; `WARNING`/`ERROR` only for unexpected states
- Always use `.get()` with a default when parsing Ford JSON — responses vary by vehicle and region

### Config versioning

`CONFIG_VERSION` and `CONFIG_MINOR_VERSION` live in `const.py`. Bump them and add a migration branch in `async_migrate_entry()` (`__init__.py`) for any breaking config change.

## Hubitat port (`hubitat/`)

| File | Role |
|------|------|
| `apps/FordPassConnect.groovy` | Hubitat app: OAuth PKCE flow, token management, API polling, child device creation |
| `drivers/FordPassVehicle.groovy` | Child device driver: parses vehicle data, exposes Lock/Switch/PresenceSensor capabilities and custom attributes |

The Groovy code mirrors the Python logic. API constants (`OAUTH_ID`, `CLIENT_ID`, endpoint URLs, region map) are kept in sync with `const.py` and `fordpass_bridge.py`.

## Development notes

- **No automated tests** — testing requires real Ford API credentials and a connected vehicle
- To debug: set `logger: custom_components.fordpass: debug` in HA `configuration.yaml`
- Use **Developer Tools → Services** in HA to call `fordpass.refresh_status`, `fordpass.clear_tokens`, or `fordpass.poll_api`
- Ford's API is undocumented and changes without notice; be defensive in all parsing
- Regional endpoints differ; never hardcode a region-specific URL outside `const.py`
