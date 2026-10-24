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
android/   Kotlin + Jetpack Compose app: voice I/O, offline parser, phone executor, accessibility service
backend/   Python FastAPI: /intent (rules + LLM fallback), /web/run (TinyFish SSE), safety policy
docs/      plan, API contract, pitch
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

- `http://localhost:8000/docs` gives you interactive API docs.
- `MOCK_TINYFISH=1` (the default without a key) streams fake web runs, so the app can be built before TinyFish is set up.
- For the LLM fallback, install [Ollama](https://ollama.com) and run `ollama pull llama3.2:3b`, or point `LLM_BASE_URL` at any OpenAI-compatible API (see `.env.example`).
- Run the tests with `python -m pytest`.

Try it:

```bash
curl -X POST localhost:8000/intent -H "Content-Type: application/json" -d "{\"text\": \"scholarship status 20261234\"}"
curl -N -X POST localhost:8000/web/run -H "Content-Type: application/json" -d "{\"flow\": \"scholarship_status\", \"params\": {\"application_id\": \"20261234\"}}"
```

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
       (call, WhatsApp, alarm, read screen)                progress + result (SSE)
                     └───────────────────┬───────────────────┘
                                         ▼
                                Hindi TTS reads the result
```
