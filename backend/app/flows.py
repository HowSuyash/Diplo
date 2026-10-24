"""Web flows: one goal template per portal task. A new portal is a new Flow, not a new scraper."""

import re
from dataclasses import dataclass, field
from typing import Any, Callable

from . import config


@dataclass(frozen=True)
class Flow:
    id: str
    title: str
    url: str
    goal: str  # str.format template over params
    required: tuple[str, ...]
    # Hindi question to ask when a required param is missing.
    ask: dict[str, str]
    # Read back before running, e.g. "आवेदन संख्या {application_id} डालूं?"
    confirm: str
    summarize: Callable[[dict[str, Any]], str]
    output_schema: dict[str, Any] = field(default_factory=dict)
    # What a mock run returns (MOCK_TINYFISH=1), so the demo reads right for each flow.
    mock_result: dict[str, str] = field(default_factory=dict)

    def missing(self, params: dict[str, str]) -> list[str]:
        return [p for p in self.required if not params.get(p)]

    def render_goal(self, params: dict[str, str]) -> str:
        return self.goal.format(**params)


# Shared instructions so every flow fails safely instead of guessing.
_SAFETY = (
    " Never invent values. If the site asks for an OTP, captcha you cannot solve, password or"
    " payment, stop and set needs_user_input to what is needed. Return only the JSON."
)


def _status_schema(*extra: str) -> dict[str, Any]:
    props = {name: {"type": "string"} for name in ("status", "details", "needs_user_input", *extra)}
    return {"type": "object", "properties": props, "required": ["status"]}


def _say_status(prefix: str, *, not_found: str = "") -> Callable[[dict[str, Any]], str]:
    def summarize(result: dict[str, Any]) -> str:
        if result.get("needs_user_input"):
            return f"पोर्टल को और जानकारी चाहिए: {result['needs_user_input']}"
        if not_found and _NOT_FOUND.search(result.get("status", "")):
            return not_found
        details = f" {result['details']}" if result.get("details") else ""
        return f"{prefix} {result.get('status', 'पता नहीं चला')}.{details}"

    return summarize


# How portals say the number doesn't exist (ConfirmTkt: "Flushed PNR / PNR not yet generated").
_NOT_FOUND = re.compile(r"invalid|flushed|not found|not yet generated|no record", re.IGNORECASE)


FLOWS: dict[str, Flow] = {
    f.id: f
    for f in [
        Flow(
            id="scholarship_status",
            title="Scholarship application status",
            url=config.SCHOLARSHIP_PORTAL_URL,
            goal=(
                "Open the scholarship application status check. Enter application ID"
                " {application_id} and submit. Return JSON with status (e.g. Approved, Pending,"
                " Rejected), details (payment or remarks, one sentence) and needs_user_input." + _SAFETY
            ),
            required=("application_id",),
            ask={"application_id": "अपना एप्लीकेशन आईडी बोलिए।"},
            confirm="एप्लीकेशन आईडी {application_id} डालूं?",
            summarize=_say_status("आपकी स्कॉलरशिप का स्टेटस है:"),
            output_schema=_status_schema(),
            mock_result={"status": "Approved", "details": "Payment in process", "needs_user_input": ""},
        ),
        Flow(
            id="pnr_status",
            title="Train PNR status",
            url=config.PNR_PORTAL_URL,
            goal=(
                "Check the PNR status for PNR number {pnr} on this page.\n"
                "1. Close any pop-up, app-install banner or cookie banner first.\n"
                "2. Type {pnr} into the PNR number box and press the button that checks the status.\n"
                "3. Read the result. Return JSON with status (booking status of each passenger, e.g."
                " CNF B2 34 or WL 12; if the site says the PNR is invalid, flushed or not found, put"
                " that message here), details (at most 12 words, e.g. '12951 Mumbai Rajdhani, 24 Oct,"
                " chart not prepared'; empty if the PNR was not found) and needs_user_input.\n"
                "Do not log in, sign up, install the app or book anything." + _SAFETY
            ),
            required=("pnr",),
            ask={"pnr": "अपना दस अंकों का पीएनआर नंबर बोलिए।"},
            confirm="पीएनआर {pnr} का स्टेटस देखूं?",
            summarize=_say_status(
                "आपकी टिकट का स्टेटस है:",
                not_found="यह पीएनआर नंबर नहीं मिला। टिकट पर लिखा दस अंकों का नंबर जांच कर फिर बोलिए।",
            ),
            output_schema=_status_schema(),
            mock_result={
                "status": "CNF B2 34",
                "details": "12951 Mumbai Rajdhani, 24 Oct, chart not prepared",
                "needs_user_input": "",
            },
        ),
    ]
}
