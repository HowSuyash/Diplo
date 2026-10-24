"""Diplo backend: intent parsing and TinyFish web flows for the Android app and the web client.

Run:  uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
"""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from contextlib import aclosing
from pathlib import Path
from typing import Any

import anyio
import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles

from . import ask, config, intent
from .flows import FLOWS, Flow
from .schemas import Intent, IntentRequest, WebRunRequest
from .tinyfish import TinyFish, TinyFishError

log = logging.getLogger(__name__)

WEB_DIR = Path(__file__).resolve().parent.parent / "web"

app = FastAPI(title="Diplo backend", version="0.2.0")


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "mock_tinyfish": config.MOCK_TINYFISH,
        "mock_flows": [f for f in FLOWS if config.mocked(f)],
        "mock_search": config.MOCK_SEARCH,
        "llm": bool(config.LLM_BASE_URL),
        "flows": [*FLOWS, ask.FLOW_ID],
    }


@app.get("/flows")
async def flows() -> list[dict[str, Any]]:
    agent_flows = [{"id": f.id, "title": f.title, "required": f.required} for f in FLOWS.values()]
    return [*agent_flows, {"id": ask.FLOW_ID, "title": ask.TITLE, "required": ask.REQUIRED}]


@app.post("/intent", response_model=Intent)
async def parse_intent(req: IntentRequest) -> Intent:
    return await intent.parse(req.text, req.history)


def _sse(event: dict[str, Any]) -> str:
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"


async def _mock_run(flow: Flow) -> AsyncIterator[dict[str, Any]]:
    """Canned TinyFish events so the app can be built before the real flows work."""
    yield {"type": "STARTED", "run_id": "mock-run"}
    for step in ("Opening the portal", "Filling in the details", "Reading the result"):
        await asyncio.sleep(1.0)
        yield {"type": "PROGRESS", "run_id": "mock-run", "purpose": step}
    yield {"type": "COMPLETE", "run_id": "mock-run", "status": "COMPLETED", "result": flow.mock_result}


_PORTAL_FAILED = "पोर्टल से जवाब नहीं मिला। थोड़ी देर बाद फिर कोशिश करें।"
# An SSE comment: keeps tunnels from closing a quiet stream, and a failed write is how we notice
# that the app hung up.
_HEARTBEAT = ": heartbeat\n\n"
_POLL_SECONDS = 3.0
_TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}


def _outcome(flow: Flow, run: dict[str, Any]) -> dict[str, Any]:
    """The app's result or error event for a finished run (a COMPLETE event or a polled run)."""
    result = run.get("result") or {}
    if run.get("status") == "COMPLETED":
        return {"type": "result", "data": result, "speak": flow.summarize(result)}
    error = run.get("error")
    message = error.get("message") if isinstance(error, dict) else error
    return {"type": "error", "message": message or run.get("status", "FAILED"), "speak": _PORTAL_FAILED}


async def _poll_run(tf: TinyFish, run_id: str, flow: Flow, steps_seen: int) -> AsyncIterator[str]:
    while True:
        run = await tf.get_run(run_id)
        for step in (run.get("steps") or [])[steps_seen:]:
            steps_seen += 1
            yield _sse({"type": "progress", "message": step.get("action", "")})
        if run.get("status") in _TERMINAL:
            yield _sse(_outcome(flow, run))
            return
        yield _HEARTBEAT
        await asyncio.sleep(_POLL_SECONDS)


async def _run_flow(req: WebRunRequest) -> AsyncIterator[str]:
    flow = FLOWS[req.flow]
    goal = flow.render_goal(req.params)
    tf = None if config.mocked(flow.id) else TinyFish(config.TINYFISH_API_KEY)
    events = _mock_run(flow) if tf is None else tf.stream_run(flow.url, goal, output_schema=flow.output_schema)

    # Translate TinyFish events into the small set the app understands (docs/API_CONTRACT.md).
    run_id, steps, finished = "", 0, False
    try:
        try:
            async with aclosing(events):
                async for ev in events:
                    kind = ev.get("type")
                    if kind == "STARTED":
                        run_id = ev.get("run_id", "")
                    elif kind == "HEARTBEAT":
                        yield _HEARTBEAT
                    elif kind == "PROGRESS":
                        steps += 1
                        yield _sse({"type": "progress", "message": ev.get("purpose", "")})
                    elif kind == "STREAMING_URL":
                        yield _sse({"type": "watch", "url": ev.get("streaming_url", "")})
                    elif kind == "COMPLETE":
                        finished = True
                        yield _sse(_outcome(flow, ev))
                        return
        except httpx.HTTPError as e:
            if not run_id:
                raise
            log.warning("TinyFish stream for run %s dropped (%r); polling the run instead", run_id, e)
        if tf is None or not run_id:
            yield _sse({"type": "error", "message": "TinyFish stream ended early", "speak": _PORTAL_FAILED})
            return
        # The stream ended before the run did (some networks reset long connections). The run
        # carries on at TinyFish, so follow it by polling, as the TinyFish docs advise.
        async for chunk in _poll_run(tf, run_id, flow, steps):
            yield chunk
        finished = True
    except TinyFishError as e:
        yield _sse({"type": "error", "message": str(e), "speak": "वेब एजेंट शुरू नहीं हो पाया।"})
    except httpx.HTTPError as e:
        yield _sse({"type": "error", "message": f"TinyFish connection: {e!r}", "speak": _PORTAL_FAILED})
    finally:
        if tf is not None and run_id and not finished:
            # The app hung up first (Stop or "ruko"). Cancel the run too, or the browser keeps
            # going and keeps billing. Shielded: this runs while the request is being torn down.
            with anyio.CancelScope(shield=True):
                try:
                    await tf.cancel(run_id)
                except httpx.HTTPError as e:
                    log.warning("Could not cancel TinyFish run %s: %r", run_id, e)


async def _run_search(query: str) -> AsyncIterator[str]:
    async for event in ask.answer(query):
        yield _sse(event)


# Keep proxies and tunnels from buffering the stream.
_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


@app.post("/web/run")
async def run_web(req: WebRunRequest) -> StreamingResponse:
    if req.flow == ask.FLOW_ID:
        if not req.params.get("query", "").strip():
            raise HTTPException(422, "missing params: ['query']")
        stream = _run_search(req.params["query"])
    else:
        flow = FLOWS.get(req.flow)
        if flow is None:
            raise HTTPException(404, f"unknown flow {req.flow!r}")
        if missing := flow.missing(req.params):
            raise HTTPException(422, f"missing params: {missing}")
        stream = _run_flow(req)
    return StreamingResponse(stream, media_type="text/event-stream", headers=_SSE_HEADERS)


# The web client (phone or laptop browser) at "/". Mounted last so the API routes win.
app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
