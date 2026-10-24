import asyncio
import json
import os

os.environ["MOCK_TINYFISH"] = "1"
os.environ["LLM_BASE_URL"] = ""
os.environ["TINYFISH_API_KEY"] = ""  # keep tests offline even when .env has a real key

import httpx  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import ask, main  # noqa: E402
from app.intent import _with_history, apply_policy, match_rules  # noqa: E402
from app.main import app  # noqa: E402
from app.schemas import Intent, Turn, WebRunRequest  # noqa: E402

client = TestClient(app)


def _events(flow: str, params: dict[str, str]) -> list[dict]:
    with client.stream("POST", "/web/run", json={"flow": flow, "params": params}) as resp:
        return [json.loads(line[6:]) for line in resp.iter_lines() if line.startswith("data: ")]


def test_scholarship_without_id_asks_for_it():
    intent = match_rules("Meri scholarship ka status batao")
    assert intent.kind == "clarify"
    assert intent.params["flow"] == "scholarship_status"


def test_scholarship_with_spoken_id_is_web_task():
    intent = match_rules("मेरी स्कॉलरशिप का स्टेटस बताओ 2026 ४५६७")
    assert intent.kind == "web"
    assert intent.params == {"flow": "scholarship_status", "application_id": "20264567"}


def test_pnr_needs_ten_digits():
    assert match_rules("PNR status 12345").kind == "clarify"
    assert match_rules("PNR 123 456 7890 ka status").params["pnr"] == "1234567890"


def test_train_ticket_means_pnr():
    assert match_rules("मेरी ट्रेन टिकट का स्टेटस बताओ").params["flow"] == "pnr_status"
    assert match_rules("train ticket status 1234567890").params["pnr"] == "1234567890"


def test_web_intent_always_confirms():
    body = client.post("/intent", json={"text": "scholarship status 20261234"}).json()
    assert body["needs_confirmation"] is True


def test_unknown_text_without_llm():
    assert client.post("/intent", json={"text": "kuch bhi"}).json()["kind"] == "unknown"


def test_invented_phone_actions_are_refused():
    # llama3.2:3b answered "एक जोक सुनाओ" with a phone action "speak" the app can't run.
    intent = apply_policy(Intent(kind="phone", action="speak", speak="क्या तुम्हें यह जोक पसंद आया?"))
    assert intent.kind == "unknown"
    assert apply_policy(Intent(kind="phone", action="set_alarm", params={"hour": "6", "minute": "0"})).kind == "phone"


def test_history_reaches_the_model_as_context():
    history = [Turn(user="अपना इंट्रोडक्शन लिखो", diplo="मैं डिप्लो हूँ।")]
    prompt = _with_history("यह मैसेज सुयश को भेजो", history)
    assert prompt.startswith("Earlier in this conversation:\nUser: अपना इंट्रोडक्शन लिखो\nDiplo: मैं डिप्लो हूँ।")
    assert prompt.endswith("The user now says: यह मैसेज सुयश को भेजो")
    assert _with_history("नमस्ते", []) == "नमस्ते"
    body = {"text": "kuch bhi", "history": [{"user": "a", "diplo": "b"}]}
    assert client.post("/intent", json=body).status_code == 200


def test_contact_edits_always_ask_first():
    save = apply_policy(Intent(kind="phone", action="save_contact", params={"name": "राहुल", "number": "9876543210"}))
    assert save.needs_confirmation is True
    assert save.speak == "राहुल, नंबर 9876543210, कॉन्टैक्ट में सेव करूं?"
    delete = apply_policy(Intent(kind="phone", action="delete_contact", params={"contact": "राहुल"}))
    assert delete.needs_confirmation is True
    assert "वापस नहीं आएगा" in delete.speak


def test_chat_replies_carry_no_action():
    intent = apply_policy(Intent(kind="chat", action="joke", params={"x": "y"}, speak="एक मज़ेदार चुटकुला।"))
    assert intent == Intent(kind="chat", speak="एक मज़ेदार चुटकुला।")
    assert apply_policy(Intent(kind="chat")).kind == "unknown"


def test_questions_become_web_searches_without_confirmation():
    for text in ("PM Kisan yojana kya hai", "आज दिल्ली का मौसम कैसा है", "Ayushman card kaise banaye"):
        intent = apply_policy(match_rules(text))
        assert intent.action == "web_search", text
        assert intent.params == {"flow": "web_search", "query": text}
        assert intent.needs_confirmation is False


def test_phone_commands_are_not_web_searches():
    for text in (
        "Mummy ko call karo",
        "Mom ko WhatsApp pe bolo main 7 baje aaunga",
        "Screen pe kya likha hai padho",
        "Papa ko batao main late hoon",
        "मम्मी को बताओ मैं आ रहा हूं",
    ):
        assert match_rules(text) is None, text


def test_flow_keywords_win_over_search():
    assert match_rules("PNR 1234567890 ka status kya hai").params["flow"] == "pnr_status"


def test_mock_web_run_streams_progress_then_result():
    events = _events("scholarship_status", {"application_id": "1"})
    assert [e["type"] for e in events] == ["progress", "progress", "progress", "result"]
    assert "Approved" in events[-1]["speak"]


def test_web_search_streams_an_answer_with_sources():
    events = _events("web_search", {"query": "PM Kisan yojana kya hai"})
    assert [e["type"] for e in events] == ["progress", "result"]
    assert events[-1]["speak"]
    assert events[-1]["data"]["sources"][0]["url"].startswith("https://")


def test_snippet_is_completed_from_its_page():
    snippet = "Under the scheme an income support of 6,000/- per year in three equal installments will be ..."
    page = (
        "# PM-Kisan\n\n[Home](/) | About\n\nPM-KISAN is a Central Sector scheme. Under the scheme an"
        " income support of 6,000/- per year in three **equal installments** will be provided to all"
        " land holding farmer families. Definition of family for the scheme is husband, wife and minor"
        " children. States identify the eligible families."
    )
    assert ask._complete(snippet, page) == (
        "Under the scheme an income support of 6,000/- per year in three equal installments will be"
        " provided to all land holding farmer families."
    )


def test_snippet_not_on_page_falls_back_to_whole_sentences():
    snippet = "दिल्ली में आज तापमान 21 डिग्री दर्ज किया गया। अधिकतम ..."
    assert ask._complete(snippet, "something else entirely") == ""
    assert ask._trim_snippet(snippet) == "दिल्ली में आज तापमान 21 डिग्री दर्ज किया गया।"


def test_hinglish_questions_search_hindi_pages():
    assert ask._language("Ayushman card kaise banaye") == "hi"
    assert ask._language("आज का मौसम") == "hi"
    assert ask._language("What is PM Kisan") == "en"


def test_punycode_site_names_are_readable():
    assert ask._site({"url": "https://xn--m1bet4hqd2b.xn--11b7cb3a6a.xn--h2brj9c/"}) == "एनआईसी.सरकार.भारत"


def test_web_run_rejects_missing_params():
    assert client.post("/web/run", json={"flow": "pnr_status"}).status_code == 422
    assert client.post("/web/run", json={"flow": "web_search", "params": {"query": " "}}).status_code == 422


def test_flows_list_includes_web_search():
    ids = [f["id"] for f in client.get("/flows").json()]
    assert ids == ["scholarship_status", "pnr_status", "web_search"]


def test_web_client_is_served():
    resp = client.get("/")
    assert resp.status_code == 200
    assert "<title>Diplo</title>" in resp.text


class _FakeTinyFish:
    """A TinyFish run that only ends when cancelled."""

    cancelled: list[str] = []

    def __init__(self, api_key: str):
        pass

    async def stream_run(self, url, goal, **kwargs):
        yield {"type": "STARTED", "run_id": "run-1"}
        yield {"type": "PROGRESS", "run_id": "run-1", "purpose": "Opening the portal"}
        await asyncio.Event().wait()

    async def cancel(self, run_id: str) -> None:
        self.cancelled.append(run_id)


def test_hanging_up_cancels_the_tinyfish_run(monkeypatch):
    monkeypatch.setattr(main.config, "MOCK_TINYFISH", False)
    monkeypatch.setattr(main, "TinyFish", _FakeTinyFish)
    _FakeTinyFish.cancelled = []

    async def hang_up():
        stream = main._run_flow(WebRunRequest(flow="pnr_status", params={"pnr": "1234567890"}))
        assert "Opening the portal" in await anext(stream)
        await stream.aclose()  # what happens to the stream when the app disconnects

    asyncio.run(hang_up())
    assert _FakeTinyFish.cancelled == ["run-1"]


def test_failed_run_reports_tinyfish_error_message(monkeypatch):
    class Failing(_FakeTinyFish):
        async def stream_run(self, url, goal, **kwargs):
            yield {"type": "STARTED", "run_id": "run-2"}
            yield {"type": "COMPLETE", "run_id": "run-2", "status": "FAILED",
                   "error": {"code": "SITE_BLOCKED", "message": "Site blocked the agent"}}

    monkeypatch.setattr(main.config, "MOCK_TINYFISH", False)
    monkeypatch.setattr(main, "TinyFish", Failing)
    Failing.cancelled = []
    events = _events("pnr_status", {"pnr": "1234567890"})
    assert events == [{"type": "error", "message": "Site blocked the agent", "speak": main._PORTAL_FAILED}]
    assert Failing.cancelled == []  # it finished on its own


def test_dropped_stream_is_followed_by_polling_the_run(monkeypatch):
    class Dropping(_FakeTinyFish):
        polls = 0

        async def stream_run(self, url, goal, **kwargs):
            yield {"type": "STARTED", "run_id": "run-3"}
            yield {"type": "PROGRESS", "run_id": "run-3", "purpose": "Opening the portal"}
            raise httpx.ReadError("connection reset")

        async def get_run(self, run_id):
            Dropping.polls += 1
            steps = [{"action": "Opening the portal"}, {"action": "Reading the result"}]
            if Dropping.polls == 1:
                return {"status": "RUNNING", "steps": steps}
            return {"status": "COMPLETED", "steps": steps, "result": {"status": "CNF B2 34"}}

    monkeypatch.setattr(main.config, "MOCK_TINYFISH", False)
    monkeypatch.setattr(main, "TinyFish", Dropping)
    monkeypatch.setattr(main, "_POLL_SECONDS", 0)
    Dropping.cancelled = []
    events = _events("pnr_status", {"pnr": "1234567890"})
    assert [e["message"] for e in events[:-1]] == ["Opening the portal", "Reading the result"]
    assert events[-1]["type"] == "result" and "CNF B2 34" in events[-1]["speak"]
    assert Dropping.cancelled == []


def test_unknown_pnr_gets_a_short_hindi_reply():
    summarize = main.FLOWS["pnr_status"].summarize
    assert summarize({"status": "Flushed PNR / PNR not yet generated", "details": "long English"}).startswith(
        "यह पीएनआर नंबर नहीं मिला"
    )
    assert summarize({"status": "CNF B2 34", "details": "12951 Mumbai Rajdhani"}) == (
        "आपकी टिकट का स्टेटस है: CNF B2 34. 12951 Mumbai Rajdhani"
    )


def test_flows_can_be_mocked_one_by_one(monkeypatch):
    monkeypatch.setattr(main.config, "MOCK_TINYFISH", False)
    monkeypatch.setattr(main.config, "MOCK_FLOWS", frozenset({"scholarship_status"}))
    assert main.config.mocked("scholarship_status")
    assert not main.config.mocked("pnr_status")
    assert client.get("/health").json()["mock_flows"] == ["scholarship_status"]
