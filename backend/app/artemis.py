"""Thin async client for Google ARTEMIS, running on this laptop next to the backend.

ARTEMIS (github.com/google/artemis, Apache 2.0) drives the phone connected over ADB: it reads the
screen and taps, types and scrolls through any app towards an English goal. These are the endpoints
its own artemis-client uses (packages/artemis-client/README.md in that repo).
"""

import uuid
from typing import Any

import httpx

TERMINAL = frozenset({"completed", "success", "failed", "cancelled", "canceled", "rejected"})
SUCCEEDED = frozenset({"completed", "success"})


class ArtemisError(Exception):
    pass


class Artemis:
    def __init__(self, base_url: str):
        self._base = base_url.rstrip("/")

    async def _call(self, method: str, path: str, **kwargs: Any) -> Any:
        """The JSON reply, or None for a 404."""
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.request(method, f"{self._base}{path}", **kwargs)
        if resp.status_code == 404:
            return None
        if resp.status_code != 200:
            raise ArtemisError(f"ARTEMIS HTTP {resp.status_code}: {resp.text}")
        return resp.json()

    async def devices(self) -> list[dict[str, Any]]:
        """Phones ADB sees. A usable one has state "device" (not "offline" or "unauthorized")."""
        reply = await self._call("GET", "/api/devices") or {}
        return list(reply.get("devices") or [])

    async def submit(self, goal: str, *, profile: str = "flash") -> str:
        """Queues a task on the phone and returns its id."""
        task_id = str(uuid.uuid4())
        body = {"goal": goal, "profile": profile, "session_id": task_id}
        reply = await self._call("POST", "/api/run", json=body) or {}
        if reply.get("status") == "rejected" or not reply.get("tasks"):
            raise ArtemisError(str(reply.get("error") or reply.get("detail") or "task was not accepted"))
        return task_id

    async def status(self, task_id: str) -> str:
        """running, completed, failed, ... A task still in the queue has no session yet, and
        neither has one that died before starting (no API key): both read as "queued"."""
        session = await self._call("GET", f"/api/sessions/{task_id}")
        return str((session or {}).get("status") or "queued").lower()

    async def steps(self, task_id: str) -> list[tuple[str, str]]:
        """(id, name) of every action so far, oldest first, e.g. ("t1", "manage_app")."""
        tree = await self._call("GET", f"/api/sessions/{task_id}/tree") or []
        found: list[tuple[float, str, str]] = []

        def walk(nodes: list[dict[str, Any]]) -> None:
            for node in nodes:
                # Device actions (manage_app, tap, swipe, input_text) are "action" nodes; "tool"
                # covers the agent's other tools. The rest are logs, spans and sub-agents.
                if node.get("type") in ("action", "tool") and node.get("name"):
                    found.append((node.get("timestamp") or 0.0, str(node["trace_id"]), node["name"]))
                walk(node.get("children") or [])

        walk(tree)
        return [(trace_id, name) for _, trace_id, name in sorted(found)]

    async def stop(self, task_id: str) -> None:
        await self._call("POST", "/api/stop", json={"session_id": task_id})
