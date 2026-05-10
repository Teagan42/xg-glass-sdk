# xg.glass — Bare-Metal Home Assistant Integration

A first-class Home Assistant custom integration that makes your Rokid glasses
a native HA device — no cloud, no external AI, no middleware.  All intelligence
lives inside Home Assistant.

```
┌──────────────────────┐  HA WebSocket / REST API  ┌────────────────────────┐
│  Rokid Glasses       │ ◄────────────────────────► │  Home Assistant        │
│                      │                            │                        │
│  XgGlassHaBridgeEntry│  assist_pipeline/run  ───► │  Assist pipeline       │
│  (glasses-companion) │  subscribe_events     ───► │  openwakeword          │
│                      │  /api/states          ───► │  STT / TTS             │
│  Mic → HA pipeline   │  /api/services        ───► │  Intent recognition    │
│  Speaker ← TTS URL   │  todo.get_items       ───► │  To-do entities        │
│  Display ← events    │  todo.add_item        ───► │  Notify / automations  │
└──────────────────────┘                            └────────────────────────┘
```

## Features

| Feature | Description |
|---------|-------------|
| **Wake-Word Satellite** | Streams mic audio to HA's openwakeword. On detection: STT → Assist pipeline → TTS playback on glasses speaker. Loops automatically. |
| **Quick Assist** | Tap once to speak. Full STT → intent → TTS round trip. |
| **Device Dashboard** | Fetch and display states of pinned entities (lights, switches, climate, locks, …). |
| **Voice Toggle** | Dictate any control phrase ("Turn on kitchen lights"). HA's intent engine handles it. |
| **View To-Dos** | Lists all pending items from a configured HA `todo` entity. |
| **Add To-Do** | Dictate an item; STT transcript is added via `todo.add_item`. |
| **Push Display** | While the Satellite is running, any HA automation can push text to the glasses display using `xg_glass.display` or `notify.send_message`. |

## Architecture

```
custom_components/xg_glass/   ← installed in Home Assistant
  manifest.json               ← integration metadata (requires HA ≥ 2024.9)
  __init__.py                 ← platform setup + xg_glass.display service
  config_flow.py              ← UI: Settings → Integrations → Add → xg.glass
  notify.py                   ← NotifyEntity: fires xg_glass_display events
  const.py
  strings.json / translations/

glasses-companion/
  XgGlassHaBridgeEntry.kt     ← run with: xg-glass run XgGlassHaBridgeEntry.kt
```

### Protocol

The glasses companion connects to HA's **standard** WebSocket API
(`ws://ha:8123/api/websocket`).  Nothing custom on the HA side is required
for the core voice functionality.  The custom component adds:

- A device entry in HA's device registry (groups entities under one device card)
- A `notify` entity so automations can push display text
- An `xg_glass.display` service for direct automation use

## Prerequisites

### Home Assistant

- HA Core **≥ 2024.9**
- **Assist pipeline** configured (Settings → Voice Assistants)
- For wake-word detection: **openwakeword** add-on installed and a wake word
  selected in the pipeline settings
- A **long-lived access token** (HA → Profile → Security → Long-lived access tokens)

### Glasses / Host Machine

- xg.glass SDK installed (`pip install -e .` in the repo root)
- `adb` on PATH (for Rokid glasses)
- JDK 17 or 21

## Installation

### 1 — Install the HA custom component

Copy (or symlink) the `custom_components/xg_glass` directory into your HA
configuration directory:

```bash
cp -r custom_components/xg_glass /config/custom_components/
```

Or with HACS: add this repository as a custom repository, then install
"xg.glass Smart Glasses".

Restart Home Assistant.

### 2 — Add the integration

Go to **Settings → Integrations → Add Integration**, search for **xg.glass**,
and enter a device name (e.g. "Rokid Glasses").

This creates:
- A device entry under your given name
- A `notify.xg_glass_<name>_display` entity

### 3 — Run the glasses companion app

```bash
# Copy the companion file somewhere accessible
cp glasses-companion/XgGlassHaBridgeEntry.kt /tmp/

# Run on real glasses
xg-glass run /tmp/XgGlassHaBridgeEntry.kt

# Run on simulator (for development)
xg-glass run --sim /tmp/XgGlassHaBridgeEntry.kt
```

### 4 — Configure settings in the host app

The host UI will show input fields for:

| Setting | Description |
|---------|-------------|
| **Home Assistant URL** | `http://homeassistant.local:8123` or IP address |
| **Long-Lived Access Token** | Generated in HA Profile → Security |
| **Assist Pipeline ID** | Leave blank to use the HA default pipeline |
| **To-Do Entity ID** | e.g. `todo.shopping_list` |
| **Pinned Entities** | Comma-separated entity IDs shown in the dashboard |

## Usage

Once the app is running on your glasses, tap the command you want:

- **Wake-Word Satellite** — wear the glasses and speak your configured wake
  word (e.g. "Hey Jarvis", "Okay Nabu", whatever you set in the HA pipeline).
  The glasses will listen, transcribe, get an intent response, and read the
  answer aloud.  This repeats automatically.
- **Quick Assist** — tap once, speak your question or command, get a response.
- **Device Dashboard** — see the current states of all your pinned entities.
- **Voice Toggle** — tap, say "Turn off the bedroom fan", done.
- **View To-Dos** — see all pending items on your to-do list.
- **Add To-Do** — tap, say the item, it's added.

## Pushing text from HA automations

While the **Wake-Word Satellite** is running, the glasses subscribe to
`xg_glass_display` events.  You can push text from any HA automation:

### Via the `notify` entity

```yaml
service: notify.send_message
target:
  entity_id: notify.xg_glass_rokid_glasses_display
data:
  message: "Doorbell rang!"
  title: "Front Door"
```

### Via the `xg_glass.display` service

```yaml
service: xg_glass.display
data:
  message: "Laundry is done."
```

### Example automation — doorbell alert

```yaml
automation:
  - alias: Doorbell to glasses
    trigger:
      - platform: state
        entity_id: binary_sensor.front_door_doorbell
        to: "on"
    action:
      - service: notify.send_message
        target:
          entity_id: notify.xg_glass_rokid_glasses_display
        data:
          message: "Someone at the front door!"
```

## To-Do list

```yaml
# Add an item programmatically
service: todo.add_item
target:
  entity_id: todo.shopping_list
data:
  item: "Milk"
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| "HA authentication failed" | Regenerate the long-lived token; make sure it has no leading/trailing spaces |
| Wake word never triggers | Check that openwakeword is running and a wake word is selected in the pipeline |
| No TTS audio | Verify the pipeline has a TTS engine configured; check `canPlayAudioBytes` in device capabilities |
| Entities show "not found" | Double-check entity IDs in settings (copy from HA Developer Tools → States) |
| `todo.get_items` fails | Requires HA ≥ 2023.11 with `return_response=true` support |

## Audio format

HA's Assist pipeline expects **PCM signed-16-bit little-endian, 16 kHz, mono**.
This matches the xg.glass `MicrophoneOptions` defaults used by the companion app.
No re-encoding is required on the glasses side.
