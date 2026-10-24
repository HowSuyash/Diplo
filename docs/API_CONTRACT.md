# App ↔ backend contract

The Android app and the backend are built in parallel, so this file is the source of truth.
Change it in the same PR as the code, and tell the other track.

Base URL: `http://<laptop-ip>:8000` (emulator: `http://10.0.2.2:8000`). All bodies are JSON, UTF-8.

## `GET /health`

```json
{ "ok": true, "mock_tinyfish": true, "llm": true, "flows": ["scholarship_status", "pnr_status"] }
```

## `POST /intent`

Fallback parser. The phone tries `OfflineParser` first and only calls this when that returns nothing.

Request:

```json
{ "text": "मेरी स्कॉलरशिप का स्टेटस बताओ", "lang": "hi-IN" }
```

Response (`Intent`):

```json
{
  "kind": "clarify",
  "action": "none",
  "params": { "flow": "scholarship_status" },
  "needs_confirmation": false,
  "speak": "अपना एप्लीकेशन आईडी बोलिए।"
}
```

| Field | Values |
|---|---|
| `kind` | `phone`, `web`, `clarify`, `unknown` |
| `action` | `call`, `send_message`, `set_alarm`, `read_screen`, `web_task`, `none` |
| `params` | always strings. `call`: `contact`. `send_message`: `contact`, `message`, `channel` (`whatsapp`/`sms`). `set_alarm`: `hour` (0–23), `minute`, `label`. `web_task`: `flow` plus that flow's fields |
| `needs_confirmation` | set by the backend safety policy, not by the model. Always `true` for `call`, `send_message`, `web_task` |
| `speak` | what the app says next, in Hindi (Devanagari reads best with the hi-IN TTS voice) |

**Clarify loop:** when `kind` is `clarify`, the app speaks `speak`, listens again, and sends
`"<previous text> <answer>"` as the next `text`.

## `POST /web/run`

Runs a TinyFish web flow and streams progress as Server-Sent Events. Call it only after the user said "haan".

Request:

```json
{ "flow": "scholarship_status", "params": { "application_id": "20261234" } }
```

Errors before the stream starts: `404` unknown flow, `422` missing params.

Stream: one `data: <json>` line per event.

| `type` | Fields | App does |
|---|---|---|
| `progress` | `message` (English step from TinyFish) | add to the step list on screen |
| `watch` | `url` (live browser view, valid 24 h) | optional: open on the laptop/projector for the demo |
| `result` | `data` (flow JSON), `speak` (Hindi summary) | show and speak `speak`, stream ends |
| `error` | `message`, `speak` | show and speak `speak`, stream ends |

Flow result `data`: `{ "status": "...", "details": "...", "needs_user_input": "" }`.
If `needs_user_input` is non-empty the portal needs an OTP/captcha from the user (see PLAN.md, OTP).

## `GET /flows`

`[{ "id": "scholarship_status", "title": "...", "required": ["application_id"] }, ...]`
