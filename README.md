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
backend/       Python FastAPI: /intent (rules + LLM fallback), /web/run (TinyFish SSE), safety policy
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
   `saarthi.backendUrl=http://127.0.0.1:8000`, then run `adb reverse tcp:8000 tcp:8000` each time
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

## How it works

```
speak ─► SpeechRecognizer (hi-IN) ─► OfflineParser ──miss──► backend /intent (rules → LLM)
                                         │                         │
                                         ▼                         ▼
                              confirm by voice ("haan") ◄── needs_confirmation
                                         │
                     ┌───────────────────┴───────────────────┐
              PhoneExecutor                          backend /web/run ─► TinyFish Agent ─► portal
       (call, WhatsApp, alarm, read screen)                │       └─► TinyFish Search + Fetch (questions)
                                                           progress + result (SSE)
                     └───────────────────┬───────────────────┘
                                         ▼
                                Hindi TTS reads the result
```
