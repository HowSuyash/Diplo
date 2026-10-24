"""Text -> Intent. Fast rules first, then an optional LLM fallback, then the safety policy.

Phone commands are parsed on the device (OfflineParser.kt) so they work offline; this
endpoint is the fallback for them and the main path for web flows.
"""

import json
import logging
import re

from . import ask, llm
from .flows import FLOWS
from .schemas import Intent

log = logging.getLogger(__name__)

# Actions that always need a spoken "haan" first, whatever the parser said.
# web_search is not here: it only reads public pages through free TinyFish Search and Fetch.
SENSITIVE_ACTIONS = {"call", "send_message", "web_task"}

# Used when the model leaves "speak" empty (small models often do).
_DEFAULT_SPEAK = {
    "call": "{contact} को कॉल करूं?",
    "send_message": "{contact} को यह मैसेज भेजूं: {message}?",
    "set_alarm": "{hour} बजकर {minute} मिनट का अलार्म लगा रहा हूं।",
    "read_screen": "स्क्रीन पढ़ रहा हूं।",
}

_DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")

_FLOW_KEYWORDS = {
    "scholarship_status": ("scholarship", "स्कॉलरशिप", "स्कालरशिप", "छात्रवृत्ति", "chhatravritti"),
    "pnr_status": ("pnr", "पीएनआर", "पी एन आर"),
}

_NOT_UNDERSTOOD = "माफ़ कीजिए, मैं समझ नहीं पाया। फिर से बोलिए।"

# A general question goes to TinyFish Search. Latin words match whole words ("what" must not
# match "whatsapp"); Devanagari matches substrings, because its vowel signs break \b.
_ASK_LATIN = re.compile(
    r"\b(kya hai|kya hota|kya hoti|kaun|kab|kahan|kaise|kitna|kitni|kitne|ke baare|ke bare"
    r"|batao|bataiye|mausam|news|khabar|search|khojo|dhundho|dhoondo|google"
    r"|what|who|when|where|how|which|why)\b"
)
_ASK_DEVANAGARI = (
    "क्या है", "क्या होता", "क्या होती", "कौन", "कब", "कहां", "कहाँ", "कैसे", "कितना", "कितनी",
    "कितने", "के बारे", "बताओ", "बताइए", "बताइये", "मौसम", "खबर", "ख़बर", "खोजो", "ढूंढो",
)
# Phone commands are parsed on the device; never turn them into a web search.
# "Mummy ko batao ..." is a message to a person, not a question.
_PHONE_LATIN = re.compile(
    r"\b(call|phone|alarm|whatsapp|message|sms|bolo|bhejo|screen|padho|ko batao|ko bata do)\b"
)
_PHONE_DEVANAGARI = (
    "कॉल", "फोन", "फ़ोन", "अलार्म", "व्हाट्सएप", "मैसेज", "बोलो", "भेजो", "स्क्रीन", "पढ़ो",
    "को बताओ", "को बता दो",
)


def _digits(text: str) -> str:
    return "".join(re.findall(r"\d+", text.translate(_DEVANAGARI_DIGITS)))


def _web_intent(flow_id: str, params: dict[str, str]) -> Intent:
    flow = FLOWS[flow_id]
    missing = flow.missing(params)
    if missing:
        return Intent(kind="clarify", params={"flow": flow_id, **params}, speak=flow.ask[missing[0]])
    return Intent(
        kind="web",
        action="web_task",
        params={"flow": flow_id, **params},
        speak=flow.confirm.format(**params),
    )


def search_intent(query: str) -> Intent:
    return Intent(
        kind="web",
        action="web_search",
        params={"flow": ask.FLOW_ID, "query": query.strip()},
        speak="वेब पर देख रहा हूं।",
    )


def _is_question(lowered: str) -> bool:
    if _PHONE_LATIN.search(lowered) or any(w in lowered for w in _PHONE_DEVANAGARI):
        return False
    return bool(_ASK_LATIN.search(lowered)) or any(w in lowered for w in _ASK_DEVANAGARI)


def match_rules(text: str) -> Intent | None:
    lowered = text.lower()
    for flow_id, words in _FLOW_KEYWORDS.items():
        if not any(w in lowered for w in words):
            continue
        digits = _digits(text)
        if flow_id == "pnr_status":
            return _web_intent(flow_id, {"pnr": digits} if len(digits) == 10 else {})
        return _web_intent(flow_id, {"application_id": digits} if digits else {})
    if _is_question(lowered):
        return search_intent(text)
    return None


_SYSTEM_PROMPT = """You turn a spoken command (English, Hindi or Hinglish) into one JSON object:
{"kind": "phone"|"web"|"clarify"|"unknown", "action": str, "params": {str: str}, "speak": str}

Phone actions and params:
- call: {"contact": name as spoken}
- send_message: {"contact": name, "message": text in the user's language, "channel": "whatsapp"|"sms"}
- set_alarm: {"hour": 0-23, "minute": 0-59, "label": optional}
- read_screen: {}
Web actions:
- web_task: {"flow": one of %s, plus that flow's fields}
- web_search: {"query": the question} for any other question that needs facts or current
  information from the web (government schemes, news, weather, prices).
If a required detail is missing use kind "clarify" and ask for it in "speak".
"speak" is a short reply in Hindi written in Devanagari, e.g. a confirmation question.
Reply with JSON only."""


async def llm_parse(text: str) -> Intent | None:
    content = await llm.chat(_SYSTEM_PROMPT % list(FLOWS), text, json_mode=True)
    if content is None:
        return None
    try:
        raw = json.loads(content)
        raw["params"] = {k: str(v) for k, v in (raw.get("params") or {}).items()}
        intent = Intent.model_validate(raw)
    except ValueError as e:
        log.warning("LLM intent parse failed for %r: %s", text, e)
        return None
    if intent.action == "web_search":
        return search_intent(intent.params.get("query") or text)
    return intent


def apply_policy(intent: Intent) -> Intent:
    """The model never decides whether to ask first; this does."""
    if intent.action in SENSITIVE_ACTIONS:
        intent.needs_confirmation = True
    if intent.action == "web_task" and intent.params.get("flow") not in FLOWS:
        return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    if not intent.speak:
        template = _DEFAULT_SPEAK.get(intent.action)
        try:
            intent.speak = template.format(**intent.params) if template else _NOT_UNDERSTOOD
        except KeyError:
            return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    return intent


async def parse(text: str) -> Intent:
    intent = match_rules(text) or await llm_parse(text)
    if intent is None:
        return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    return apply_policy(intent)
