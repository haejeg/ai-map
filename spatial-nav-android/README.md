# Spatial Nav (Android)

A driving navigation app with a voice AI co-pilot. It offers place search, traffic-aware turn-by-turn routes, live incidents and road conditions, automatic rerouting, and a Claude assistant you can talk to ("what's on my left?", "take me to the nearest gas station", "any accidents ahead?", "is there a faster route?").

## Setup

Install and run the app, then tap the gear button (or the demo-mode banner) to open **API keys**:

- **TomTom** (free tier at developer.tomtom.com): place search, routing, live traffic and road conditions.
- **Claude** (console.anthropic.com): the AI voice assistant.

**Save & test** checks each key with the service before saving it. TomTom gets one reverse-geocode request; Claude gets a model lookup that generates no tokens. A rejected key is never saved. If you're offline, the key is saved and marked "Not verified". The app switches between live data and demo mode as soon as a key changes, with no restart needed.

Keys are encrypted with AES-256-GCM under a key held in the Android Keystore and kept out of backups. Each key is sent only to its own service.

Developers can also put keys in the git-ignored `local.properties` (`tomtom.apiKey=…`, `anthropic.apiKey=…`). A key saved in the app takes precedence over these.

> Keys stored on a device are for your own testing. Before distributing the app, move the Claude call behind a small server that holds the key.

## What's where

| Path | Role |
| --- | --- |
| `data/tomtom/` | TomTom Search, Routing (`traffic=true`, alternatives, guidance, traffic sections), Traffic Incidents v5, flow tiles |
| `data/MockDataSource.kt` | Offline demo data |
| `navigation/NavigationEngine.kt` | Pure guidance logic: route snapping, prompts at 800/250/40 m, off-route detection, arrival, incidents ahead |
| `navigation/NavigationManager.kt` | Live trip: auto-reroute, traffic refresh every 2 min, incident announcements, faster-route switching |
| `agent/ClaudeAssistant.kt` | Claude (`claude-opus-5-5`, low effort) with tool use and conversation memory |
| `agent/NavigationTools.kt` | Assistant tools: location, place search, surrounding POIs, start/change/stop navigation, route status, traffic incidents, faster route |
| `agent/MockLlm.kt` | Keyword-based offline assistant |
| `spatial/` | Bearing engine (Ahead/Right/Behind/Left) and route geometry |
| `telemetry/` | GPS, compass (true north), and GPS course while moving |
| `settings/` | API key storage (Keystore-encrypted), precedence, and live verification |
| `ui/` | Compose screens (map, API keys), MapLibre map (traffic-coloured route, incidents, flow layer) |

## Test

```sh
./gradlew testDebugUnitTest
```

The tests cover the spatial engine, route geometry, TomTom parsing and HTTP requests (against a mock server), guidance timing, rerouting, traffic refresh, and the Claude tool loop (against a mock Messages API). No real API calls are made.
