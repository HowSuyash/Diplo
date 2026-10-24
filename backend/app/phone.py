"""Phone tasks no fixed command covers ("dark mode on karo"), done by Google ARTEMIS.

The app's own commands (call, WhatsApp, alarm, settings, apps, contacts) stay the fast, offline
path. Anything else a person does on their phone becomes one English goal for ARTEMIS, which runs
on this laptop and taps through the phone connected over USB or wireless debugging. Like the portal
flows it runs only after the user said "haan", and hanging up stops it.
"""

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import Any

import anyio
import httpx

from . import config, llm
from .artemis import SUCCEEDED, TERMINAL, Artemis, ArtemisError

log = logging.getLogger(__name__)

FLOW_ID = "phone_task"
TITLE = "Do anything on the phone (Google ARTEMIS)"
REQUIRED = ("goal",)

# Added to every goal, so the agent does what the user said haan to and nothing riskier. Booking a
# ride or placing an order the user asked for is fine; typing a PIN, password or OTP stays theirs.
LIMITS = (
    " Do only what this task asks, and finish it. Never type a UPI PIN, password, OTP or card details,"
    " and never change security or account settings: stop on that screen so the user can do it."
    " Do not send, post, buy, book or delete anything the task does not ask for."
)

_POLL_SECONDS = 1.5
_MAX_SECONDS = 240  # a Flash step takes 3-5 s, so this is ~50 steps
# A task that dies before it starts (no model API key) never gets a session, so it looks queued forever.
_QUEUED_SECONDS = 30
# No new action for this long means it is stuck: ARTEMIS retries a failing model (rate limit, no
# credits) for ~70 s per call while the task still reads "running". On Gemini's free tier, 503
# "high demand" retries can hold one step for a minute, so leave room for that.
_STALL_SECONDS = 120
_MOCK_STEP_SECONDS = 1.0

DONE = "हो गया।"
FAILED = "फ़ोन पर यह काम नहीं हो पाया।"
NOT_CONNECTED = "फ़ोन लैपटॉप से जुड़ा नहीं है। USB या वायरलेस डिबगिंग से जोड़िए।"
NOT_STARTED = "फ़ोन एजेंट शुरू नहीं हो पाया।"


def enabled() -> bool:
    return config.MOCK_ARTEMIS or bool(config.ARTEMIS_URL)


# ARTEMIS reports only that a task ended, not what it found, so the app reads the screen it left
# behind and the model says the result from that.
_ANSWER_PROMPT = """A phone agent just did a task on the user's phone. You get the task, what the user said, and
the text now on the phone's screen, top to bottom. Tell the user the result in one or two short
sentences, as Diplo talking out loud: what the screen shows about the task (a fare and arrival
time, a count, a status, that the message was sent). Use only the screen text and never invent
numbers or names. If the screen doesn't show the result, say only that it is done. Reply in Hindi
written in Devanagari if the user spoke Hindi or Hinglish, otherwise in English. No markdown."""


async def answer_from_screen(goal: str, said: str, screen: list[str]) -> str:
    """What to say after a phone task, from the text on the screen it ended on."""
    lines = [line.strip() for line in screen if line.strip()]
    if not lines:
        return DONE
    prompt = f"Task: {goal}\nUser said: {said}\nScreen:\n" + "\n".join(lines[:80])
    reply = await llm.chat(_ANSWER_PROMPT, prompt, timeout=20.0)
    return (reply or "").strip() or DONE


def _progress(message: str) -> dict[str, Any]:
    return {"type": "progress", "message": message}


def _readable(tool: str) -> str:
    """"open_app" -> "Open app"."""
    return tool.replace("_", " ").strip().capitalize()


async def _mock(goal: str) -> AsyncIterator[dict[str, Any] | None]:
    """MOCK_ARTEMIS=1: canned steps, to build the app without ARTEMIS or a phone."""
    for step in ("Opening the app", "Tapping through the screens", "Checking the result"):
        await asyncio.sleep(_MOCK_STEP_SECONDS)
        yield _progress(step)
    yield {"type": "result", "data": {"status": "completed", "goal": goal}, "speak": DONE}


async def run(goal: str) -> AsyncIterator[dict[str, Any] | None]:
    """App events (docs/API_CONTRACT.md), and None while the task is still going (a heartbeat)."""
    if config.MOCK_ARTEMIS:
        async for event in _mock(goal):
            yield event
        return

    agent = Artemis(config.ARTEMIS_URL)
    yield _progress("Starting the phone agent")
    try:
        # ARTEMIS queues a task even with no phone attached, and it would wait there for one.
        if not any(d.get("state") == "device" for d in await agent.devices()):
            yield {"type": "error", "message": "no phone connected to ARTEMIS", "speak": NOT_CONNECTED}
            return
        task_id = await agent.submit(goal + LIMITS, profile=config.ARTEMIS_PROFILE)
    except (ArtemisError, httpx.HTTPError) as e:
        log.warning("ARTEMIS did not take the task: %r", e)
        yield {"type": "error", "message": str(e), "speak": NOT_STARTED}
        return

    finished, seen = False, set()
    now = asyncio.get_running_loop().time
    started = last_step = now()
    try:
        while True:
            for step_id, tool in await agent.steps(task_id):
                if step_id not in seen:
                    seen.add(step_id)
                    last_step = now()
                    yield _progress(_readable(tool))
            status = await agent.status(task_id)
            if status in TERMINAL:
                finished = True
                if status in SUCCEEDED:
                    yield {"type": "result", "data": {"status": status, "goal": goal}, "speak": DONE}
                else:
                    yield {"type": "error", "message": f"ARTEMIS task {status}", "speak": FAILED}
                return
            if status == "queued" and now() - started > _QUEUED_SECONDS:
                yield {"type": "error", "message": "ARTEMIS task never started", "speak": NOT_STARTED}
                return
            if now() - last_step > _STALL_SECONDS:
                yield {"type": "error", "message": "ARTEMIS task stalled (its model may be failing)", "speak": FAILED}
                return
            if now() - started > _MAX_SECONDS:
                yield {"type": "error", "message": "ARTEMIS task took too long", "speak": FAILED}
                return
            yield None
            await asyncio.sleep(_POLL_SECONDS)
    except (ArtemisError, httpx.HTTPError) as e:
        yield {"type": "error", "message": f"ARTEMIS connection: {e!r}", "speak": FAILED}
    finally:
        if not finished:
            # The app hung up ("ruko"), the task timed out or ARTEMIS stopped answering: stop the
            # phone too, or it keeps tapping. Shielded: this runs while the request is torn down.
            with anyio.CancelScope(shield=True):
                try:
                    await agent.stop(task_id)
                except (ArtemisError, httpx.HTTPError) as e:
                    log.warning("Could not stop ARTEMIS task %s: %r", task_id, e)
