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
  built and demoed without spending credits. Set it to `0` for real portal runs.
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

1. Open the `android/` folder in Android Studio and let Gradle sync.
2. For a real phone, create `android/local.properties` with `saarthi.backendUrl=http://<your-laptop-LAN-IP>:8000`. Keep the phone on the same Wi-Fi or hotspot. The emulator uses `http://10.0.2.2:8000` by default.
3. Run on the phone and grant mic, phone and contacts permissions.
4. To read the screen and tap Send, enable **Settings → Accessibility → Diplo**. On Android 13+, first go to App info → ⋮ → *Allow restricted settings*.
5. For offline Hindi speech, download the Hindi offline speech pack in the phone's Google voice settings.

> The Android code was written without an Android SDK and hasn't been compiled yet. Expect a few fixes on the first sync (task A0 in the plan).

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
