# App ↔ backend contract

The Android app and the backend are built in parallel, so this file is the source of truth.
Change it in the same PR as the code, and tell the other track.

Base URL: `http://<laptop-ip>:8000` (emulator: `http://10.0.2.2:8000`). All bodies are JSON, UTF-8.

## `GET /health`

```json
{
  "ok": true,
  "mock_tinyfish": false,
  "mock_flows": ["scholarship_status"],
  "mock_search": false,
  "llm": true,
  "phone_agent": true,
  "mock_artemis": false,
  "flows": ["scholarship_status", "pnr_status", "web_search", "phone_task"]
}
```

`mock_tinyfish`: every portal flow (TinyFish Agent, billed per step) returns canned results.
`mock_flows`: the portal flows that are canned right now; the others run on the real site.
`mock_search`: `web_search` returns canned answers (only when there is no TinyFish API key).
`phone_agent`: Google ARTEMIS is set up (`ARTEMIS_URL`), so `phone_task` exists. `mock_artemis`:
its runs are canned.

## `GET /`

The web client: the same voice loop as the app, in a phone or laptop browser. Voice needs
https or `localhost`; typing works anywhere. `/?q=<text>` runs a command on load.

## `POST /intent`

Fallback parser. The phone tries `OfflineParser` first and only calls this when that returns nothing.

Request:

```json
{ "text": "मेरी स्कॉलरशिप का स्टेटस बताओ", "lang": "hi-IN" }
```

Optional `history`: the last few exchanges, oldest first (at most 6), so the LLM can resolve
follow-ups like "यह मैसेज सुरेश को भेजो" or "कौन जीता":

```json
{ "text": "यह मैसेज सुयश को भेजो",
  "history": [{ "user": "अपना इंट्रोडक्शन लिखो", "diplo": "मैं डिप्लो हूँ, ..." }] }
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
| `kind` | `phone`, `web`, `chat`, `clarify`, `unknown` |
| `action` | `call`, `send_message`, `set_alarm`, `read_screen`, `open_app`, `set_setting`, `save_contact`, `delete_contact`, `web_task`, `web_search`, `none` |
| `params` | always strings. `call`: `contact`. `send_message`: `contact`, `message`, `channel` (`whatsapp`/`sms`). `set_alarm`: `hour` (0–23), `minute`, `label`. `open_app`: `app` (as spoken or as the phone shows it; the app matches it to launcher labels), optional `query` to play or search inside it. `set_setting`: `setting` (`brightness`, `volume`, `torch`, `dnd`, `silent`, `font_size`, `wifi`, `bluetooth`, `mobile_data`, `location`) and `value` (`up`, `down`, `auto`, `on`, `off`, `max`, `min`, `mute`). `save_contact`: `name`, `number` (digits). `delete_contact`: `contact`. `web_task`: `flow` plus that flow's fields. `web_search`: `flow` (`web_search`) and `query` |
| `needs_confirmation` | set by the backend safety policy, not by the model. Always `true` for `call`, `send_message`, `save_contact`, `delete_contact`, `web_task` (the app also asks for the phone ones whatever the reply says). `false` for `web_search`: it only reads public pages |
| `web_task` flows | `scholarship_status` (`application_id`), `pnr_status` (`pnr`), and `phone_task` (`goal`) while the phone agent is on |
| `speak` | what the app says next, in Hindi (Devanagari reads best with the hi-IN TTS voice) |

**Clarify loop:** when `kind` is `clarify`, the app speaks `speak`, listens again, and sends
`"<previous text> <answer>"` as the next `text`.

**Web questions:** general questions ("PM Kisan yojana kya hai", "आज दिल्ली का मौसम कैसा है")
come back as `web_search` with `kind: "web"`, so the app runs them through `/web/run` like any
other flow, without asking first.

**Phone tasks done by the agent:** while the phone agent is on, phone work comes back as a
`web_task` with `flow: "phone_task"` and an English `goal`: everything except `read_screen` by
default (`ARTEMIS_FOR_ALL=1`: calls, messages, settings, bookings, any app), or only what no phone
action covers with `ARTEMIS_FOR_ALL=0`. The app runs it through `/web/run` like a portal flow,
after "haan": Google ARTEMIS on the laptop taps through the phone's apps, and the app shows its
steps. `speak` repeats the user's own words: `"फ़ोन पर यह करूं: dark mode on karo?"`. With the
agent off, tasks no phone action covers are `unknown`.

**Order in the app:** online, every command goes to `/intent` first; offline (the backend
unreachable), the phone's `OfflineParser`, then its on-device model.

**Chat:** small talk the LLM can answer itself ("एक जोक सुनाओ", "तुम क्या कर सकते हो") comes
back as `kind: "chat"`, `action: "none"`, with the reply in `speak`. The app just says it. Needs
the LLM fallback; without one these are `unknown`.

## `POST /web/run`

Runs a web flow and streams progress as Server-Sent Events. Portal flows (TinyFish Agent) run
only after the user said "haan". `web_search` (TinyFish Search + Fetch) needs no confirmation.

Request:

```json
{ "flow": "scholarship_status", "params": { "application_id": "20261234" } }
```

```json
{ "flow": "web_search", "params": { "query": "PM Kisan yojana kya hai" } }
```

Errors before the stream starts: `404` unknown flow, `422` missing params.

Stream: one `data: <json>` line per event. Lines starting with `:` are heartbeats; ignore them.
**Closing the stream stops the work:** when the app hangs up (Stop or "ruko"), the backend
cancels the TinyFish run, so it stops billing.

| `type` | Fields | App does |
|---|---|---|
| `progress` | `message` (English step from TinyFish) | add to the step list on screen |
| `watch` | `url` (live browser view, valid 24 h) | optional: open on the laptop/projector for the demo |
| `result` | `data` (flow JSON), `speak` (Hindi summary) | show and speak `speak`, stream ends |
| `error` | `message`, `speak` | show and speak `speak`, stream ends |

Portal flow result `data`: `{ "status": "...", "details": "...", "needs_user_input": "" }`.
If `needs_user_input` is non-empty the portal needs an OTP/captcha from the user (see PLAN.md, OTP).

`phone_task` request: `{ "flow": "phone_task", "params": { "goal": "Open Settings and turn on Dark theme" } }`.
Errors before the stream: `503` while the phone agent is off, `422` without a `goal`. `progress`
messages name each action ("Open app", "Click"); the result is `data: { "status": "completed", "goal": "..." }`
with `speak: "हो गया।"`. Every goal gets limits added: no payments, PINs, passwords, OTPs, security
settings, or sends and deletes the user didn't ask for. If no phone is attached, the stream is one
`error` that says so.

`web_search` result `data`: `{ "answer": "...", "sources": [{ "title": "...", "url": "...", "site": "..." }] }`.
`answer` (also in `speak`) is quoted from the best source page, not generated, in Hindi when
the question was Hindi or Hinglish.

## `POST /phone/answer`

ARTEMIS reports only that a `phone_task` ended, not what it found. So when one succeeds, the app
reads the screen it left (accessibility service, top to bottom) and asks what to say:

```json
{ "goal": "Open Uber and book an Uber Go to Connaught Place", "said": "uber book karo",
  "screen": ["Uber Go ₹184", "Driver arriving in 4 min", "..."] }
```

Reply: `{ "speak": "Uber Go बुक हो गई, ड्राइवर 4 मिनट में आएगा।" }`, in the user's language, from
the screen text only. With an empty screen or no LLM it is `"हो गया।"`.

## `GET /flows`

`[{ "id": "scholarship_status", "title": "...", "required": ["application_id"] }, ..., { "id": "web_search", ... }]`,
plus `phone_task` while the phone agent is on.
