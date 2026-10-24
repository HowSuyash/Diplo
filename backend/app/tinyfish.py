"""Thin async client for the TinyFish APIs we use: Agent (SSE), Search and Fetch.

Docs: https://docs.tinyfish.ai  (auth is the X-API-Key header on every call)
"""

import json
from collections.abc import AsyncIterator
from typing import Any

import httpx

AGENT_BASE = "https://agent.tinyfish.ai/v1"
SEARCH_URL = "https://api.search.tinyfish.ai/"
FETCH_URL = "https://api.fetch.tinyfish.ai/"


class TinyFishError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(f"TinyFish HTTP {status}: {detail}")
        self.status = status
        self.detail = detail


class TinyFish:
    def __init__(self, api_key: str):
        self._headers = {"X-API-Key": api_key, "Content-Type": "application/json"}

    async def stream_run(
        self,
        url: str,
        goal: str,
        *,
        output_schema: dict[str, Any] | None = None,
        browser_profile: str = "lite",
        **extra: Any,
    ) -> AsyncIterator[dict[str, Any]]:
        """Start an agent run and yield its SSE events.

        Event types: STARTED, STREAMING_URL, PROGRESS, TF_API_RESULT, HEARTBEAT, COMPLETE.
        `extra` passes through other run options, e.g. use_vault, profile_id, proxy_config.
        """
        body: dict[str, Any] = {"url": url, "goal": goal, "browser_profile": browser_profile, **extra}
        if output_schema:
            body["output_schema"] = output_schema
        timeout = httpx.Timeout(15.0, read=None)  # runs can take minutes; heartbeats keep it alive
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream(
                "POST", f"{AGENT_BASE}/automation/run-sse", json=body, headers=self._headers
            ) as resp:
                if resp.status_code != 200:
                    detail = (await resp.aread()).decode(errors="replace")
                    raise TinyFishError(resp.status_code, detail)
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    payload = line[len("data:") :].strip()
                    if payload:
                        yield json.loads(payload)

    async def cancel(self, run_id: str) -> None:
        async with httpx.AsyncClient(timeout=15.0) as client:
            await client.post(f"{AGENT_BASE}/runs/{run_id}/cancel", headers=self._headers)

    async def search(self, query: str, *, location: str = "IN", **params: Any) -> dict[str, Any]:
        """Ranked web results for a spoken question (or to find the right portal)."""
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.get(
                SEARCH_URL, params={"query": query, "location": location, **params}, headers=self._headers
            )
        if resp.status_code != 200:
            raise TinyFishError(resp.status_code, resp.text)
        return resp.json()

    async def fetch(self, urls: list[str], *, purpose: str | None = None, fmt: str = "markdown") -> dict[str, Any]:
        """Free read of up to 10 pages (scheme details, notices) without a full agent run."""
        body: dict[str, Any] = {"urls": urls, "format": fmt}
        if purpose:
            body["purpose"] = purpose
        # Fetch allows 110 s per URL behind a 120 s CDN limit; wait longer to get its timeout error.
        async with httpx.AsyncClient(timeout=150.0) as client:
            resp = await client.post(FETCH_URL, json=body, headers=self._headers)
        if resp.status_code != 200:
            raise TinyFishError(resp.status_code, resp.text)
        return resp.json()
