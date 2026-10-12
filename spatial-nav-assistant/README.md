# Spatial Navigation Assistant

A single-page, voice-driven navigation assistant. MapLibre GL draws the map, the Web Speech API handles listening and speaking, and a simulated LLM agent answers questions like *"what is to my left?"* by calling tools.

## Run

```sh
npm start            # serves on http://localhost:8080 (GPS/compass/mic need localhost or HTTPS)
```

On a desktop without a compass, tick **Simulate heading**. In browsers without SpeechRecognition (for example Firefox), type requests into the text box instead.

## Layout

| File | Role |
| --- | --- |
| `js/spatial.js` | Bearing engine: forward azimuth, haversine, relative bearing, Ahead/Right/Behind/Left buckets |
| `js/telemetry.js` | `watchPosition` GPS and `deviceorientation` heading (iOS `webkitCompassHeading`, Android `360 − alpha`) |
| `js/state.js` | Global `{ lat, lon, heading, … }` state with pub/sub |
| `js/tools.js` | Tool schemas and deterministic handlers: `get_current_location`, `reroute_navigation`, `get_surrounding_pois` |
| `js/agent.js` | Mock LLM (tool_use / tool_result message contract) and the agent loop |
| `js/voice.js` | Push-to-talk recognition and speech synthesis |
| `js/map.js`, `js/main.js` | Map rendering and UI wiring |
| `js/pois.js` | Mock POIs placed at fixed bearings around the first GPS fix |

Quadrants use half-open ranges on the relative bearing: Ahead `[315, 45)`, Right `[45, 135)`, Behind `[135, 225)`, Left `[225, 315)`.

To use a real model, replace `mockLlm` in `js/agent.js` with a call that takes `(messages, TOOL_DEFINITIONS)` and returns an assistant message with `text` or `tool_use` blocks.

## Verify

```sh
npm test      # spatial engine + agent pipeline unit tests (node:test)
npm run check # syntax-check every module, including browser-only ones
npm run smoke # load the real page in headless Chrome/Edge and ask "what is to my left?"
```
