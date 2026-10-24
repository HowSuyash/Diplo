# Diplo: finale implementation plan

HackIIITD 2026 finale · **Sat 24 Oct** · IIIT Delhi · Team DietCode

This plan builds what the Round 1 deck promised the judges (slides 3, 5, 6, 8, 9). If we change scope, we change it here first.

## 1. What we must demo (the "done" bar)

From slide 8, *"Feasible in 24 hours: Android app, 3 phone commands, 2 portal flows via TinyFish"*:

| # | Command (spoken) | Path | Works offline? |
|---|---|---|---|
| P1 | "Mummy ko call karo" / "Mom ko WhatsApp pe bolo main 7 baje aaunga" | phone: Intent + accessibility tap on Send | parsing yes, sending needs network |
| P2 | "Subah 6 baje ka alarm lagao" | phone: `AlarmClock` intent | **yes** (demo in airplane mode) |
| P3 | "Screen pe kya likha hai padho" | phone: AccessibilityService → TTS | **yes** |
| W1 | "Meri scholarship ka status batao" → asks ID → "haan" → result in Hindi | web: TinyFish Agent | no |
| W2 | "PNR 1234567890 ka status batao" | web: TinyFish Agent | no |
| — | "Ruko" at any point | kill switch | yes |

Everything else (wake word, on-device LLM, more portals, OTP) is **stretch** and only goes in if it's stable by H16.

## 2. Architecture as we will actually build it

```
 Android app (Kotlin, Compose)                         Backend (Python, FastAPI)            TinyFish
 ─────────────────────────────                         ─────────────────────────            ────────
 Mic button ─► SpeechRecognizer (hi-IN, offline pack)
              │
              ▼
   OfflineParser (rules, Devanagari + Hinglish) ──miss──► POST /intent
              │ hit                                         rules for web flows
              ▼                                             └► LLM fallback (OpenAI-compatible:
   Router: phone | web | clarify                                Ollama locally / Gemini)
              │                                             safety policy sets needs_confirmation
   Confirm: TTS asks, listen for "haan"/"nahi" + buttons
              │
   ┌──────────┴──────────┐
 PhoneExecutor        POST /web/run (SSE) ─────────────► Flow template ─► Agent /run-sse ──► portal
 Intents + a11y taps    progress / watch / result ◄───── translate events ◄── PROGRESS/COMPLETE
              │
   Speaker (TTS hi-IN) speaks the result
```

What's already in the repo (scaffold, see README): all the boxes above exist with a first
implementation. The backend runs and is tested; the Android app is written but **not compiled yet**
(no Android SDK on the machine it was written on). Opening it in Android Studio is task A0.

### Decisions (and why)

| Area | Deck says | MVP we build | Stretch |
|---|---|---|---|
| Wake word | openWakeWord | Push-to-talk button + accessibility button | Porcupine / openWakeWord |
| STT | Whisper.cpp / Vosk | Android `SpeechRecognizer` hi-IN with offline Hindi pack. Zero setup, handles Hinglish | Vosk `small-hi` |
| Intent | Gemma / Qwen 1–2B on device | **Rules on device** for the 3 phone commands (truly offline) + **LLM fallback on backend** (deck slide 8: "cloud fallback") | Gemma 3 1B via MediaPipe LLM Inference |
| Web | TinyFish Agent/Search/Fetch/Vault | Agent `run-sse` with goal templates + `output_schema` | Search to pick a portal, Fetch for scheme info, Vault for logins |
| TTS | Android TTS | Android TTS, Hindi voice, Devanagari text | — |

**Why rules before an on-device LLM:** a 1B model on a mid-range phone adds 1–3 s per command and
is the riskiest piece to get working in 24 h. Rules make the offline claim true on stage. If track
B gets Gemma working by H16, it replaces the rules behind the same `OfflineParser.parse()` call.

## 3. Team tracks

Write your name next to a track. Each track owns its folders and merges small PRs into `main` often.

| Track | Owner | Owns | Done when |
|---|---|---|---|
| **A: Voice & UI** | _____ | `android/.../voice`, `ui`, `MainActivity` | speak → transcript on screen → spoken reply; Confirm screen with Haan/Nahi; Stop works; start listening from any app via the accessibility button |
| **B: Phone executor** | _____ | `android/.../intent`, `exec` | P1–P3 work from voice on a real phone; contact matching handles "mummy/मम्मी/mom"; WhatsApp Send tapped after "haan" |
| **C: Backend & brain** | _____ | `backend/app/main.py`, `intent.py`, `schemas.py` | `/intent` handles all demo phrasings; LLM fallback tuned; backend reachable from the phone at the venue (hotspot or tunnel) |
| **D: Web flows & demo** | _____ | `backend/app/flows.py`, `tinyfish.py`, `mock-portal/`, pitch | W1 and W2 return correct JSON from TinyFish; Hindi summaries; demo script rehearsed; backup video recorded |

## 4. Before the event (Wed 21 – Fri 23 Oct)

> **Check the HackIIITD rules on pre-built code first.** Many hackathons allow setup, boilerplate
> and research beforehand but want features built during the event, and judges can read commit
> timestamps. The scaffold here is boilerplate; if the rules are strict, do only the setup and
> testing below and write feature code at the venue.

Everyone:
- [ ] Accept the GitHub invite, clone, read README + this plan + API_CONTRACT
- [ ] Install Android Studio (SDK 35), Python 3.11+, Git
- [ ] Phone: developer options + USB debugging on; Hindi **offline speech** pack downloaded
      (Settings → Google → Voice / Speech → Offline speech recognition → हिन्दी); Hindi TTS voice installed

Per track:
- [ ] **A0 (A or B):** open `android/` in Android Studio, sync, fix compile errors, run on a real phone, push
- [ ] **B:** on the demo phone, enable the accessibility service once (Android 13+: App info → ⋮ → *Allow restricted settings* first). Note the exact steps for the demo
- [ ] **C:** run the backend on the laptop, hit it from the phone over the same Wi-Fi/hotspot (`http://<laptop-ip>:8000/health`). Set up a `cloudflared` tunnel as a backup for the venue network
- [ ] **D:** get a TinyFish API key (agent.tinyfish.ai/api-keys); check credits; ask organisers if TinyFish credits are provided
- [ ] **D:** in the TinyFish playground, try the scholarship and PNR portals. Write down: does it reach the result? captcha? OTP? how long? Pick the two flows that work
- [ ] **D:** if a real portal blocks the agent, plan a small **mock portal** (static HTML form, clearly labelled as a demo, hosted on GitHub Pages) so W1 always works on stage
- [ ] **Lead:** fill in owners above; confirm hackathon duration and judging slot

## 5. Event timeline (24 h)

Integration checkpoints are fixed; everything else is flexible. At each checkpoint `main` must run.

| Hours | A: Voice & UI | B: Phone | C: Backend | D: Web & demo |
|---|---|---|---|---|
| **0–3** | STT + TTS on real phone, show transcript | P2 alarm + P3 read screen | backend on venue network, phone reaches `/health` | W1 working via `curl /web/run` with real TinyFish |
| **✓ H3** | **Walking skeleton: speak → `/intent` → spoken reply (backend in mock mode)** | | | |
| **3–8** | Confirm screen, haan/nahi by voice, Stop | P1 call + WhatsApp send, contact aliases | clarify loop, LLM prompt for all demo phrasings | W2 PNR flow, Hindi summaries |
| **✓ H8** | **P1–P3 work offline from voice; W1 works from curl** | | | |
| **8–12** | Working screen with live steps | SMS fallback, edge cases ("kal subah", "shaam 7") | `needs_user_input` → ask user, re-run | OTP pattern (below) on mock portal |
| **✓ H12** | **W1 end to end from voice on the phone, with confirmation** | | | |
| **12–16** | accessibility-button trigger from any app | stretch: Gemma via MediaPipe | stretch: TinyFish Search to pick the portal | live browser view (`watch` url) on the projector |
| **✓ H16** | **Feature freeze.** Only bug fixes from here | | | |
| **16–20** | polish, big fonts, Hindi strings | bug bash on the demo phone | logging, timeouts, mock fallbacks | update pitch deck, demo script |
| **✓ H20** | **Full rehearsal ×3. Record the backup demo video** | | | |
| **20–24** | buffer | buffer | buffer | final pitch, README screenshots, submit |

Sleep in pairs (A+C, then B+D) after H12 so the backend and Android always have someone awake.

### OTP pattern (W1 stretch)

The deck shows "user speaks the OTP". A TinyFish run can't pause for input, so:
1. Run 1 fills the ID, requests the OTP and stops with `needs_user_input: "otp"`.
2. The app asks "OTP बोलिए", the user speaks it.
3. Run 2 continues with the OTP, using a TinyFish **Browser Context Profile** so cookies/session
   carry over between runs.

This depends on the portal keeping state in cookies, so prove it on the mock portal first.
**Unverified.** If it doesn't work by H12, demo W1 without OTP.

## 6. Demo script (≈3 min)

1. **Problem (20 s):** "Scholarship status ke liye cyber café jaana padta hai."
2. **Offline (40 s):** phone in airplane mode → "Subah 6 baje ka alarm lagao" → alarm set. "Screen padho" on a notice.
3. **Phone (30 s):** airplane mode off → "Mummy ko WhatsApp pe bolo main 7 baje aaunga" → reads back → "haan" → sent.
4. **Web (60 s):** "Meri scholarship ka status batao" → asks ID → speaks ID → "haan" → live TinyFish browser on the projector → result spoken in Hindi.
5. **Safety (15 s):** start a task, say "ruko" → stops instantly.
6. **Close (15 s):** "Your phone and the web, operated by your voice."

## 7. Risks and fallbacks

| Risk | Fallback |
|---|---|
| Venue Wi-Fi blocks ports or is slow | own phone hotspot for laptop + phone; `cloudflared` tunnel URL in `local.properties` |
| Government portal down, captcha or slow on the day | mock portal for W1; pre-recorded video of the real-portal run |
| TinyFish run takes 30–90 s | narrate "पोर्टल खोल रहा हूं"; show live steps + live browser view; Fetch for read-only pages |
| STT mishears Hinglish | transcript shown on screen; rules match Devanagari and Latin; LLM fallback |
| Accessibility service disabled after reinstall | re-enable before the demo (steps from B's notes); `read_screen` tells the user how |
| WhatsApp UI changes the Send button | click by view id, then by text "Send" |
| Laptop or phone dies | second phone with the APK installed; chargers; backup video |

## 8. Safety rules (judges will ask)

- `call`, `send_message`, every web flow: always confirmed by voice or button. Enforced in `backend/app/intent.py` (`apply_policy`) and in the app.
- Password fields are never read (`readScreen` skips `isPassword`). No passwords/OTPs in TinyFish goals; logins go through TinyFish **Vault** (credentials injected at the browser layer, the model never sees them).
- Payments: hand off to the user's own UPI app, never automated (deck slide 8).
- "Ruko" or the Stop button cancels speech, listening and the web run immediately.
