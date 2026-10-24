"""One chat call to any OpenAI-compatible endpoint (Ollama locally, Gemini, ...)."""

import logging

import httpx

from . import config

log = logging.getLogger(__name__)


def enabled() -> bool:
    return bool(config.LLM_BASE_URL)


async def chat(system: str, user: str, *, json_mode: bool = False, timeout: float = 90.0) -> str | None:
    """The model's reply, or None when the LLM is disabled or the call fails.

    The default timeout is generous: a local model's first call includes loading it into memory.
    """
    if not enabled():
        return None
    body = {
        "model": config.LLM_MODEL,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0,
    }
    if json_mode:
        body["response_format"] = {"type": "json_object"}
    headers = {"Authorization": f"Bearer {config.LLM_API_KEY}"} if config.LLM_API_KEY else {}
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(f"{config.LLM_BASE_URL}/chat/completions", json=body, headers=headers)
            resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
    except (httpx.HTTPError, KeyError, IndexError, ValueError) as e:
        log.warning("LLM call failed: %r", e)
        return None
