# Diplo

**A voice agent that runs your phone and the web, in your own language.**
Speak a goal in Hindi, Hinglish or English. Diplo does it on your Android phone or on a website,
asks before anything risky, and reads the result back to you.

Team **DietCode** · HackIIITD 2026 (Esya '26, IIIT Delhi) · Suyash Shukla, Suyash Dwivedi, Piyush Tiwari, Aabhas Viswas

> "Meri scholarship ka status batao" → asks for your application ID → confirms → checks the portal
> through TinyFish → "आपकी स्कॉलरशिप का स्टेटस है: Approved."

## Start here

1. [docs/PLAN.md](docs/PLAN.md): what we're building, who owns what, the 24-hour timeline
2. [docs/API_CONTRACT.md](docs/API_CONTRACT.md): the JSON between the app and the backend
3. [docs/pitch/](docs/pitch/): Round 1 deck and project documentation

## Repo layout

```
android/       Kotlin + Jetpack Compose app: voice I/O, offline parser, phone executor, accessibility service
backend/       Python FastAPI: /intent (rules + LLM fallback), /web/run (TinyFish and ARTEMIS SSE), safety policy
backend/web/   web client for phone and laptop browsers, served at /
docs/          plan, API contract, pitch
```

## Run the backend

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate          # macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
copy .env.example .env          # macOS/Linux: cp .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

- `http://localhost:8000` opens the web client, `http://localhost:8000/docs` the API docs.
- Put your TinyFish key (agent.tinyfish.ai/api-keys) in `.env`. Web questions then use live
  TinyFish Search + Fetch, which are free.
- `MOCK_TINYFISH=1` keeps portal runs (TinyFish Agent, billed per step) fake, so the app can be
  built without spending credits. `0` runs them all for real. `MOCK_TINYFISH=scholarship_status`
  keeps only that flow fake: PNR status then runs live on confirmtkt.com (~6 steps, ~$0.10), and
  on a laptop the live TinyFish browser shows beside the conversation.
- For the LLM fallback, install [Ollama](https://ollama.com) and run `ollama pull llama3.2:3b`, or point `LLM_BASE_URL` at any OpenAI-compatible API (see `.env.example`).
- Run the tests with `python -m pytest`.

Try it:

```bash
curl -X POST localhost:8000/intent -H "Content-Type: application/json" -d "{\"text\": \"scholarship status 20261234\"}"
curl -N -X POST localhost:8000/web/run -H "Content-Type: application/json" -d "{\"flow\": \"scholarship_status\", \"params\": {\"application_id\": \"20261234\"}}"
curl -N -X POST localhost:8000/web/run -H "Content-Type: application/json" -d "{\"flow\": \"web_search\", \"params\": {\"query\": \"PM Kisan yojana kya hai\"}}"
```

## Use it from a phone or laptop browser

The backend serves a web client at `/`: speak or type, confirm with Haan/Nahi, watch TinyFish
work step by step, hear the answer. Phone commands (call, WhatsApp, alarm) stay in the Android app.

- **Laptop:** open `http://localhost:8000` in Chrome or Edge.
- **Phone:** browsers only allow the microphone over https, so expose the backend through a tunnel:

  ```bash
  winget install Cloudflare.cloudflared               # once (macOS: brew install cloudflared)
  cloudflared tunnel --url http://localhost:8000      # prints an https://….trycloudflare.com URL
  ```

  Open that URL on the phone (Chrome on Android, Safari on iPhone). It reconnects by itself and
  keeps the same URL while it runs. `ssh -R 80:localhost:8000 nokey@localhost.run` needs no
  install, but some networks reset it every few minutes, with a new URL each time. On the same Wi-Fi you can
  also use `http://<laptop-ip>:8000`, but then only typing works. Anyone with the tunnel URL can
  use the backend while the tunnel runs, so keep `MOCK_TINYFISH=1` while it's up.

## Run the Android app

1. Open the `android/` folder in Android Studio (Rabbit or newer) and let Gradle sync. The build
   uses Gradle 9.6, AGP 9.3 and Kotlin 2.4, which run on the JDK bundled with Studio. From a
   terminal: set `JAVA_HOME` to Studio's `jbr` folder, then `gradlew assembleDebug` (unit tests:
   `gradlew :app:testDebugUnitTest`).
2. Point the app at the backend in `android/local.properties`. Easiest, and immune to venue Wi-Fi:
   `diplo.backendUrl=http://127.0.0.1:8000`, then run `adb reverse tcp:8000 tcp:8000` each time
   the phone is plugged in over USB. Over the air, use `http://<your-laptop-LAN-IP>:8000` or a
   tunnel URL instead. Without the setting the emulator default `http://10.0.2.2:8000` is used.

   **No reliable cable?** `adb reverse` works over wireless debugging too, with the same
   `127.0.0.1` build. Put the phone and laptop on one Wi-Fi, turn on Developer options →
   *Wireless debugging*, tap *Pair device with pairing code*, then:

   ```bash
   adb pair <ip>:<pairing-port> <6-digit-code>    # once per laptop
   adb connect <ip>:<port>                        # the port shown on the Wireless debugging screen
   adb -s <ip>:<port> reverse tcp:8000 tcp:8000
   ```

   Wireless debugging needs the phone to be a Wi-Fi client, not a hotspot: at a venue, turn on
   the laptop's Mobile hotspot and join it from the phone.
3. Run on the phone and grant mic, phone and contacts permissions.
4. To read the screen and tap Send, enable **Settings → Accessibility → Diplo**. On Android 13+, first go to App info → ⋮ → *Allow restricted settings*.
5. For offline Hindi speech, download the Hindi offline speech pack in the phone's Google voice
   settings. Without it Diplo falls back to online recognition, and the text box always works.

Tested on an Android 15 emulator: web answers, live PNR through TinyFish, alarm, call, and the
WhatsApp confirmation. Voice input and the actual WhatsApp send need a real phone.

The build needs Android SDK Platform 37 (RunAnywhere requires it); Studio offers to install it,
or run `sdkmanager "platforms;android-37.0"`.

## Phone agent: anything else on the phone (Google ARTEMIS)

With ARTEMIS on, the phone's work is done by [Google ARTEMIS](https://github.com/google/artemis)
on the laptop: calls, WhatsApp messages, settings, ride bookings, food orders, anything in any app
("uber se connaught place ki ride book karo", "Instagram pe meri last post ke likes batao"). The
backend's LLM writes an English goal and, after "haan", ARTEMIS taps through the phone's apps like
a person. The app shows each step, "ruko" stops it, and at the end Diplo reads the screen it left
and says the result ("Uber Go बुक हो गई, ड्राइवर 4 मिनट में"). It never pays: it stops on the screen
with the final Pay or Send money button (UPI Lite needs no PIN, so a PIN rule alone isn't enough),
and it never types a UPI PIN, password or OTP. Rides go in the cab app you name ("रैपीडो से…"), never
Google Maps. `ARTEMIS_FOR_ALL=0` keeps the app's instant commands (call,
alarm, settings…) and uses ARTEMIS only for the rest. Offline, the app's own rules and on-device
model still handle the phone commands.

```bash
git clone https://github.com/google/artemis.git      # next to this repo, once
cd artemis
pip install uv && uv sync                            # once
copy .env.example .env                               # then set GEMINI_API_KEY (aistudio.google.com)
set PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools;%PATH%
set ARTEMIS_CANCEL_GRACE_SECONDS=2                   # "ruko" stops the phone at once, not after 45 s
uv run artemis-admin --port 8765                     # our backend already uses 8000
```

Then put `ARTEMIS_URL=http://127.0.0.1:8765` in `backend/.env` and restart the backend. The phone
is the one `adb devices` shows (USB or wireless debugging, as above). Its first task installs the
small "Artemis Accessibility Helper" on it. ARTEMIS needs a Gemini key: its OpenAI-compatible
mode (Groq) still has open bugs ([#95](https://github.com/google/artemis/issues/95)). Screenshots of
the phone go to Gemini, so demo with a phone that has nothing private open. `MOCK_ARTEMIS=1` fakes
the runs.

## On-device LLM (RunAnywhere)

Commands the rules don't catch still work with no network: `LocalBrain` runs a small model on the
phone with [RunAnywhere](https://github.com/RunanywhereAI/runanywhere-sdks) (llama.cpp) and answers
in the same Intent JSON, for phone actions and small talk. The model (`qwen3-0.6b` by default, a few
hundred MB) downloads once while online; pick another catalog model with `diplo.localModel=` in
`android/local.properties`. Online, the backend's bigger model answers first.

## How it works

```
speak ─► SpeechRecognizer (hi-IN) ─► OfflineParser ──miss──► backend /intent (rules → LLM)
                                         │          offline? ─► LocalBrain (on-device LLM)
                                         ▼                         ▼
                              confirm by voice ("haan") ◄── needs_confirmation
                                         │
                     ┌───────────────────┴───────────────────┐
              PhoneExecutor                          backend /web/run ─► TinyFish Agent ─► portal
       (call, WhatsApp, alarm, settings,                   │       ├─► TinyFish Search + Fetch (questions)
        apps, contacts, read screen)                       │       └─► Google ARTEMIS ─► any app on the phone
                                                           progress + result (SSE)
                     └───────────────────┬───────────────────┘
                                         ▼
                                Hindi TTS reads the result
```
