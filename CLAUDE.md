# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo is a fork of [marq24/ha-fordpass](https://github.com/marq24/ha-fordpass), a Home Assistant custom integration for Ford and Lincoln vehicles. It is kept as a **reference implementation** for a Hubitat Elevation port developed elsewhere — the Python code in `custom_components/fordpass/` is the source of truth for Ford/Autonomic API behavior, data structures, and auth flow, but is not being actively developed here.

## Hubitat port has moved

The Hubitat Elevation port (Groovy app + driver) that used to live in `hubitat/` on this repo's `hubitat` branch has moved to **[dacmanj/hubitat](https://github.com/dacmanj/hubitat)**, under `FordPass/` — that's where it's published and distributed via Hubitat Package Manager, alongside this author's other Hubitat packages. Do new Hubitat development there, not here.

When working in `dacmanj/hubitat/FordPass/` and you need to understand how the Ford API works, how metrics are structured, or what a command should do — read the Python in this repo (`custom_components/fordpass/`) first, then implement the equivalent in Groovy. This repo's `hubitat` branch still holds the pre-migration history if you need to trace how a particular piece of the port evolved.

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
- To debug the HA reference: set `logger: custom_components.fordpass: debug` in `configuration.yaml`
