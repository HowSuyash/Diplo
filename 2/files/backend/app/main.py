"""Saarthi backend: intent parsing and TinyFish web flows for the Android app.

Run:  uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
"""

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse

from . import config, intent
from .flows import FLOWS
from .schemas import Intent, IntentRequest, WebRunRequest
from .tinyfish import TinyFish, TinyFishError

app = FastAPI(title="Saarthi backend", version="0.1.0")


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "mock_tinyfish": config.MOCK_TINYFISH,
        "llm": bool(config.LLM_BASE_URL),
        "flows": list(FLOWS),
    }


@app.get("/flows")
async def flows() -> list[dict[str, Any]]:
    return [{"id": f.id, "title": f.title, "required": f.required} for f in FLOWS.values()]


@app.post("/intent", response_model=Intent)
async def parse_intent(req: IntentRequest) -> Intent:
    return await intent.parse(req.text)


def _sse(event: dict[str, Any]) -> str:
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"


async def _mock_run(goal: str) -> AsyncIterator[dict[str, Any]]:
    """Canned TinyFish events so the app can be built before the real flows work."""
    yield {"type": "STARTED", "run_id": "mock-run"}
    for step in ("Opening the portal", "Filling the application ID", "Reading the result"):
        await asyncio.sleep(1.0)
        yield {"type": "PROGRESS", "run_id": "mock-run", "purpose": step}
    yield {
        "type": "COMPLETE",
        "run_id": "mock-run",
        "status": "COMPLETED",
        "result": {"status": "Approved", "details": "Payment in process", "needs_user_input": ""},
    }


async def _run_flow(req: WebRunRequest) -> AsyncIterator[str]:
    flow = FLOWS[req.flow]
    goal = flow.render_goal(req.params)
    if config.MOCK_TINYFISH:
        events = _mock_run(goal)
    else:
        events = TinyFish(config.TINYFISH_API_KEY).stream_run(
            flow.url, goal, output_schema=flow.output_schema
        )

    # Translate TinyFish events into the small set the app understands (docs/API_CONTRACT.md).
    try:
        async for ev in events:
            kind = ev.get("type")
            if kind == "PROGRESS":
                yield _sse({"type": "progress", "message": ev.get("purpose", "")})
            elif kind == "STREAMING_URL":
                yield _sse({"type": "watch", "url": ev.get("streaming_url", "")})
            elif kind == "COMPLETE":
                result = ev.get("result") or {}
                if ev.get("status") == "COMPLETED":
                    yield _sse({"type": "result", "data": result, "speak": flow.summarize(result)})
                else:
                    yield _sse({
                        "type": "error",
                        "message": ev.get("error") or ev.get("status", "FAILED"),
                        "speak": "पोर्टल से जवाब नहीं मिला। थोड़ी देर बाद फिर कोशिश करें।",
                    })
                return
    except TinyFishError as e:
        yield _sse({"type": "error", "message": str(e), "speak": "वेब एजेंट शुरू नहीं हो पाया।"})


@app.post("/web/run")
async def run_web(req: WebRunRequest) -> StreamingResponse:
    flow = FLOWS.get(req.flow)
    if flow is None:
        raise HTTPException(404, f"unknown flow {req.flow!r}")
    if missing := flow.missing(req.params):
        raise HTTPException(422, f"missing params: {missing}")
    return StreamingResponse(_run_flow(req), media_type="text/event-stream")
