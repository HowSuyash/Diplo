"""Text -> Intent. Fast rules first, then an optional LLM fallback, then the safety policy.

Phone commands are parsed on the device (OfflineParser.kt) so they work offline; this
endpoint is the fallback for them and the main path for web flows.
"""

import json
import logging
import re

from . import ask, llm
from .flows import FLOWS
from .schemas import Intent, Turn

log = logging.getLogger(__name__)

# Actions that always need a spoken "haan" first, whatever the parser said.
# web_search is not here: it only reads public pages through free TinyFish Search and Fetch.
SENSITIVE_ACTIONS = {"call", "send_message", "save_contact", "delete_contact", "web_task"}

# Used when the model leaves "speak" empty (small models often do).
_DEFAULT_SPEAK = {
    "call": "{contact} को कॉल करूं?",
    "send_message": "{contact} को यह मैसेज भेजूं: {message}?",
    "set_alarm": "{hour} बजकर {minute} मिनट का अलार्म लगा रहा हूं।",
    "read_screen": "स्क्रीन पढ़ रहा हूं।",
    "open_app": "{app} खोल रहा हूं।",
    "set_setting": "ठीक है।",
    "save_contact": "{name}, नंबर {number}, कॉन्टैक्ट में सेव करूं?",
    "delete_contact": "{contact} का कॉन्टैक्ट डिलीट करूं? यह वापस नहीं आएगा।",
}

_DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")

_FLOW_KEYWORDS = {
    "scholarship_status": ("scholarship", "स्कॉलरशिप", "स्कालरशिप", "छात्रवृत्ति", "chhatravritti"),
    # The recognizer often mangles "PNR", so the ticket itself works as a keyword too.
    "pnr_status": (
        "pnr", "पीएनआर", "पी एन आर", "train ticket", "ticket status", "ticket ka status",
        "ट्रेन टिकट", "टिकट का स्टेटस", "टिकट स्टेटस",
    ),
}

_NOT_UNDERSTOOD = "माफ़ कीजिए, मैं समझ नहीं पाया। फिर से बोलिए।"
_CANT_DO = "यह मैं अभी नहीं कर सकता। कॉल, मैसेज, अलार्म या कोई सवाल पूछिए।"

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


def match_flow(text: str) -> Intent | None:
    lowered = text.lower()
    for flow_id, words in _FLOW_KEYWORDS.items():
        if not any(w in lowered for w in words):
            continue
        digits = _digits(text)
        if flow_id == "pnr_status":
            return _web_intent(flow_id, {"pnr": digits} if len(digits) == 10 else {})
        return _web_intent(flow_id, {"application_id": digits} if digits else {})
    return None


def match_rules(text: str) -> Intent | None:
    """Portal flows, then question words for web search. Used as-is when there is no LLM."""
    if intent := match_flow(text):
        return intent
    return search_intent(text) if _is_question(text.lower()) else None


_SYSTEM_PROMPT = """You turn a spoken command (English, Hindi or Hinglish) into one JSON object:
{"kind": "phone"|"web"|"chat"|"clarify"|"unknown", "action": str, "params": {str: str}, "speak": str}

Phone actions and params:
- call: {"contact": name as spoken}
- send_message: {"contact": name, "message": text in the user's language, "channel": "whatsapp"
  unless the user says SMS, then "sms"}. If the user asks you to write the message ("अपना
  इंट्रोडक्शन लिखो", "birthday wish likh do"), write it yourself, ready to send.
- set_alarm: {"hour": 0-23, "minute": 0-59, "label": optional}
- read_screen: {}
- open_app: {"app": the app's name as the phone shows it, in English letters, e.g. "YouTube",
  "query": optional, what to play or search for inside it ("सिद्धू मूसे वाला के गाने")}
- set_setting: {"setting": "brightness"|"volume"|"torch"|"dnd"|"silent"|"font_size"|"wifi"|
  "bluetooth"|"mobile_data"|"location", "value": "up"|"down"|"auto"|"on"|"off"|"max"|"min"|"mute"}.
  Vague complaints map to the fix: "brightness bahut hai, kuch kar ki apne aap theek rahe" ->
  brightness auto; "aankhon mein chubh raha hai" -> brightness down; "kuch sunai nahi deta" ->
  volume up; "akshar chhote hain, padh nahi pata" -> font_size up; "meeting mein hoon" -> dnd on.
- save_contact: {"name": as the user wants it saved, "number": digits only}
- delete_contact: {"contact": name as spoken}
Web actions:
- web_task: {"flow": one of %s, plus that flow's fields}
- web_search: {"query": the question} for any other question about facts: government schemes,
  news, weather, prices, sports, people, places, or anything that could have changed. Even if
  you know the answer, use web_search so it comes from a live page.
Chat: only for greetings, jokes, small talk and questions about Saarthi itself. Use kind
"chat", action "none", and put the reply itself in "speak": one or two short, friendly
sentences, like a helpful assistant talking out loud. You are Saarthi, a voice agent that
makes calls, sends WhatsApp messages, sets alarms and checks government portals.
A chat reply never says you did or are doing something (sending, calling, opening): only an
action does that. If the user wants something done, return that action instead.
Earlier turns, when given, are context: use them to resolve "यह", "वो", "उसे", "it" ("यह मैसेज
सुरेश को भेजो" sends Saarthi's last reply; "कौन जीता" after a cricket question asks about that
match). Then write a web_search "query" as a complete question in the user's language.
If a required detail is missing use kind "clarify" and ask for it in "speak".
"speak" is short and in the user's language: Hindi written in Devanagari for Hindi or Hinglish,
English for English. The recognizer writes English in Devanagari too ("कैन यू हेल्प मी"), so
judge by the words, not the script.
Reply with JSON only."""


def _with_history(text: str, history: list[Turn]) -> str:
    if not history:
        return text
    earlier = "\n".join(f"User: {t.user}\nSaarthi: {t.saarthi}" for t in history)
    return f"Earlier in this conversation:\n{earlier}\n\nThe user now says: {text}"


async def llm_parse(text: str, history: list[Turn] | None = None) -> Intent | None:
    content = await llm.chat(_SYSTEM_PROMPT % list(FLOWS), _with_history(text, history), json_mode=True)
    if content is None:
        return None
    try:
        raw = json.loads(content)
        raw["action"] = raw.get("action") or "none"  # small models send null
        raw["params"] = {k: str(v) for k, v in (raw.get("params") or {}).items()}
        intent = Intent.model_validate(raw)
    except ValueError as e:
        log.warning("LLM intent parse failed for %r: %s", text, e)
        return None
    if intent.action == "web_search":
        # Alone, the user's own words search best and keep the answer in their language;
        # a follow-up needs the model's query, which folds in what "it" refers to.
        return search_intent((intent.params.get("query") if history else None) or text)
    if intent.action == "web_task" and intent.params.get("flow") in FLOWS:
        # The flow's own wording: it asks for missing fields and confirms before a billed run.
        params = {k: v for k, v in intent.params.items() if k != "flow"}
        return _web_intent(intent.params["flow"], params)
    return intent


def apply_policy(intent: Intent) -> Intent:
    """The model never decides whether to ask first; this does."""
    if intent.action in SENSITIVE_ACTIONS:
        intent.needs_confirmation = True
    if intent.kind == "phone":
        # Small models invent phone actions ("speak" for "joke sunao") that the app can't run.
        if intent.action not in _DEFAULT_SPEAK:
            return Intent(kind="unknown", speak=_CANT_DO)
        # Our wording, not the model's: it says "संदेश भेज दिया" before the user has said haan.
        # A written message ends in "।", and "...हूँ।?" reads badly in the question.
        shown = {k: v.rstrip("।.!? ") if k == "message" else v for k, v in intent.params.items()}
        try:
            intent.speak = _DEFAULT_SPEAK[intent.action].format(**shown)
        except KeyError:
            return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
        return intent
    if intent.kind == "chat":
        return Intent(kind="chat", speak=intent.speak) if intent.speak else Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    if intent.action == "web_task" and intent.params.get("flow") not in FLOWS:
        return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    if not intent.speak:
        template = _DEFAULT_SPEAK.get(intent.action)
        try:
            intent.speak = template.format(**intent.params) if template else _NOT_UNDERSTOOD
        except KeyError:
            return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    return intent


async def parse(text: str, history: list[Turn] | None = None) -> Intent:
    # With a model, it tells small talk ("नमस्ते कैसे हो") from web questions ("मौसम कैसा है");
    # the question-word rules take over when it is off or fails.
    intent = match_flow(text) or await llm_parse(text, history) or match_rules(text)
    if intent is None:
        return Intent(kind="unknown", speak=_NOT_UNDERSTOOD)
    return apply_policy(intent)
