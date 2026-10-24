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
  "flows": ["scholarship_status", "pnr_status", "web_search"]
}
```

`mock_tinyfish`: every portal flow (TinyFish Agent, billed per step) returns canned results.
`mock_flows`: the portal flows that are canned right now; the others run on the real site.
`mock_search`: `web_search` returns canned answers (only when there is no TinyFish API key).

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
  "history": [{ "user": "अपना इंट्रोडक्शन लिखो", "saarthi": "मैं सारथी हूँ, ..." }] }
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
| `action` | `call`, `send_message`, `set_alarm`, `read_screen`, `web_task`, `web_search`, `none` |
| `params` | always strings. `call`: `contact`. `send_message`: `contact`, `message`, `channel` (`whatsapp`/`sms`). `set_alarm`: `hour` (0–23), `minute`, `label`. `web_task`: `flow` plus that flow's fields. `web_search`: `flow` (`web_search`) and `query` |
| `needs_confirmation` | set by the backend safety policy, not by the model. Always `true` for `call`, `send_message`, `web_task`. `false` for `web_search`: it only reads public pages |
| `speak` | what the app says next, in Hindi (Devanagari reads best with the hi-IN TTS voice) |

**Clarify loop:** when `kind` is `clarify`, the app speaks `speak`, listens again, and sends
`"<previous text> <answer>"` as the next `text`.

**Web questions:** general questions ("PM Kisan yojana kya hai", "आज दिल्ली का मौसम कैसा है")
come back as `web_search` with `kind: "web"`, so the app runs them through `/web/run` like any
other flow, without asking first.

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

`web_search` result `data`: `{ "answer": "...", "sources": [{ "title": "...", "url": "...", "site": "..." }] }`.
`answer` (also in `speak`) is quoted from the best source page, not generated, in Hindi when
the question was Hindi or Hinglish.

## `GET /flows`

`[{ "id": "scholarship_status", "title": "...", "required": ["application_id"] }, ..., { "id": "web_search", ... }]`
