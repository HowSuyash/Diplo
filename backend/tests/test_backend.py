import asyncio
import json
import os

os.environ["MOCK_TINYFISH"] = "1"
os.environ["LLM_BASE_URL"] = ""
os.environ["TINYFISH_API_KEY"] = ""  # keep tests offline even when .env has a real key
os.environ["ARTEMIS_URL"] = ""  # and never drive a real phone
os.environ["MOCK_ARTEMIS"] = "0"

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


def _phone_agent_on(monkeypatch, *, mock: bool):
    monkeypatch.setattr(main.config, "MOCK_ARTEMIS", mock)
    monkeypatch.setattr(main.config, "ARTEMIS_URL", "" if mock else "http://artemis.test")


def _model_says(monkeypatch, reply: dict, *, offered: bool):
    async def chat(system, user, **kwargs):
        assert ("phone_task" in system) is offered
        return json.dumps(reply)

    monkeypatch.setattr(main.intent.llm, "chat", chat)


def test_phone_agent_is_off_by_default():
    assert client.get("/health").json()["phone_agent"] is False
    assert client.post("/web/run", json={"flow": "phone_task", "params": {"goal": "x"}}).status_code == 503


def test_other_phone_tasks_go_to_artemis_after_haan(monkeypatch):
    _phone_agent_on(monkeypatch, mock=True)
    goal = "Open Settings and turn on Dark theme"
    _model_says(monkeypatch, {"kind": "phone", "action": "phone_task", "params": {"goal": goal}}, offered=True)
    assert client.post("/intent", json={"text": "dark mode on karo"}).json() == {
        "kind": "web",
        "action": "web_task",
        # The user's own words ride along, so the agent can catch a goal that got the direction wrong.
        "params": {"flow": "phone_task", "goal": goal + ' (The user said: "dark mode on karo")'},
        "needs_confirmation": True,
        "speak": "फ़ोन पर यह करूं: dark mode on karo?",
    }


def test_rides_use_the_cab_app_the_user_named(monkeypatch):
    # For "रैपीडो से …" gpt-oss wrote a Google Maps goal and read "से" as a place ("from Rapid").
    _phone_agent_on(monkeypatch, mock=True)
    maps_goal = "Open Google Maps, set destination to Abes Engineering College from Rapid"
    _model_says(monkeypatch, {"kind": "phone", "action": "phone_task", "params": {"goal": maps_goal}}, offered=True)
    goal = client.post("/intent", json={"text": "रैपीडो से एब्स इंजीनियरिंग कॉलेज के लिए बाइक बुक करो"}).json()["params"]["goal"]
    assert goal.startswith("Use the Rapido app, not Google Maps")
    # "ola" inside "bola" is not Ola, and a goal that already names the app is left alone.
    assert main.intent._named_app("mummy ko bola main aa raha hoon") is None
    assert main.intent._named_app("uber se cp jana hai") == "Uber"
    assert main.intent.phone_task_intent("Open Uber and book a ride", "uber book karo").params["goal"].startswith("Open Uber")


def test_payments_stop_on_the_payment_screen():
    # UPI Lite pays small amounts without a PIN, so the agent must never tap the final Pay itself.
    assert "Never complete a payment" in main.phone.LIMITS
    pay = main.intent.phone_task_intent("Open Paytm and send ₹2 to Suyash", "पेटीएम से सुयश को ₹2 भेजो")
    assert pay.speak.endswith("पेमेंट का आखिरी बटन आप खुद दबाइएगा।")
    ride = main.intent.phone_task_intent("Open Uber and book a ride to Connaught Place", "uber book karo")
    assert "पेमेंट" not in ride.speak


def test_phone_task_without_a_goal_gets_the_users_words(monkeypatch):
    # gpt-oss answered "brightness kam karo" with phone_task and empty params.
    _phone_agent_on(monkeypatch, mock=True)
    _model_says(monkeypatch, {"kind": "phone", "action": "phone_task", "params": {}}, offered=True)
    body = client.post("/intent", json={"text": "brightness kam karo"}).json()
    assert body["params"]["goal"].startswith("Do this on the phone: brightness kam karo")
    assert body["needs_confirmation"] is True


def test_phone_tasks_are_refused_while_artemis_is_off(monkeypatch):
    _model_says(monkeypatch, {"kind": "phone", "action": "phone_task", "params": {"goal": "Open Settings"}}, offered=False)
    assert client.post("/intent", json={"text": "dark mode on karo"}).json()["kind"] == "unknown"


def test_with_artemis_on_it_does_every_phone_task(monkeypatch):
    _phone_agent_on(monkeypatch, mock=True)
    prompt = main.intent._system_prompt()
    assert "Open the Phone app and call Mummy" in prompt and '- call: {"contact"' not in prompt
    monkeypatch.setattr(main.config, "ARTEMIS_FOR_ALL", False)  # the app's actions first again
    assert '- call: {"contact"' in main.intent._system_prompt()

    goal = "Open the Phone app and call Mummy"
    monkeypatch.setattr(main.config, "ARTEMIS_FOR_ALL", True)
    _model_says(monkeypatch, {"kind": "phone", "action": "phone_task", "params": {"goal": goal}}, offered=True)
    body = client.post("/intent", json={"text": "mummy ko call karo"}).json()
    assert body["params"]["goal"] == goal + ' (The user said: "mummy ko call karo")'
    assert body["needs_confirmation"] is True


def test_result_is_read_from_the_screen_the_agent_left(monkeypatch):
    async def chat(system, user, **kwargs):
        assert "Use only the screen text" in system and "Uber Go ₹184" in user
        return "Uber Go बुक हो गई, ड्राइवर 4 मिनट में आएगा।"

    monkeypatch.setattr(main.phone.llm, "chat", chat)
    body = {"goal": "Open Uber and book an Uber Go", "said": "uber book karo",
            "screen": ["Uber Go ₹184", "Driver arriving in 4 min", "Ramesh · DL 1C 2345"]}
    assert client.post("/phone/answer", json=body).json() == {"speak": "Uber Go बुक हो गई, ड्राइवर 4 मिनट में आएगा।"}
    # Nothing on the screen, or no model: just say it is done.
    assert client.post("/phone/answer", json={**body, "screen": []}).json() == {"speak": main.phone.DONE}


def test_mock_phone_task_streams_steps_then_done(monkeypatch):
    _phone_agent_on(monkeypatch, mock=True)
    monkeypatch.setattr(main.phone, "_MOCK_STEP_SECONDS", 0)
    events = _events("phone_task", {"goal": "Open Settings and turn on Dark theme"})
    assert [e["type"] for e in events] == ["progress", "progress", "progress", "result"]
    assert events[-1]["speak"] == main.phone.DONE
    assert client.get("/flows").json()[-1]["id"] == "phone_task"
    assert client.post("/web/run", json={"flow": "phone_task", "params": {"goal": " "}}).status_code == 422


class _FakeArtemis:
    """An ARTEMIS task that opens an app, taps once, then ends as `final`."""

    final = "completed"
    phones = [{"serial": "abc123", "state": "device"}]
    goals: list[str] = []
    stopped: list[str] = []

    def __init__(self, base_url: str):
        self.polls = 0

    async def devices(self):
        return self.phones

    async def submit(self, goal, *, profile="flash"):
        self.goals.append(goal)
        return "task-1"

    async def steps(self, task_id):
        return [("t1", "open_app"), ("t2", "click")][: self.polls + 1]

    async def status(self, task_id):
        self.polls += 1
        return self.final if self.polls >= 2 else "running"

    async def stop(self, task_id):
        self.stopped.append(task_id)

    async def stop_all(self):
        self.stopped.append("all")


def _fake_artemis(monkeypatch, final: str) -> type[_FakeArtemis]:
    class Fake(_FakeArtemis):
        pass

    Fake.final, Fake.goals, Fake.stopped = final, [], []
    _phone_agent_on(monkeypatch, mock=False)
    monkeypatch.setattr(main.phone, "Artemis", Fake)
    monkeypatch.setattr(main.phone, "_POLL_SECONDS", 0)
    return Fake


def test_artemis_task_streams_each_action(monkeypatch):
    fake = _fake_artemis(monkeypatch, "completed")
    events = _events("phone_task", {"goal": "Open Settings and turn on Dark theme"})
    assert [e.get("message") for e in events[:-1]] == ["Starting the phone agent", "Open app", "Click"]
    assert events[-1]["type"] == "result" and events[-1]["speak"] == main.phone.DONE
    # What the user said haan to, plus the limits: no payments, PINs or unasked sends.
    assert fake.goals == ["Open Settings and turn on Dark theme" + main.phone.LIMITS]
    assert fake.stopped == []


def test_failed_artemis_task_gets_a_hindi_reply(monkeypatch):
    fake = _fake_artemis(monkeypatch, "failed")
    events = _events("phone_task", {"goal": "Open Settings"})
    assert events[-1] == {"type": "error", "message": "ARTEMIS task failed", "speak": main.phone.FAILED}
    assert fake.stopped == []  # it ended on its own


def test_no_phone_attached_is_said_plainly(monkeypatch):
    fake = _fake_artemis(monkeypatch, "completed")
    fake.phones = [{"serial": "abc123", "state": "unauthorized"}]
    events = _events("phone_task", {"goal": "Open Settings"})
    assert events[-1] == {"type": "error", "message": "no phone connected to ARTEMIS", "speak": main.phone.NOT_CONNECTED}
    assert fake.goals == []  # nothing was queued


def test_task_that_never_starts_is_stopped(monkeypatch):
    # Without a model API key ARTEMIS's worker dies before it records a session, so the task
    # reads "queued" forever.
    fake = _fake_artemis(monkeypatch, "queued")
    monkeypatch.setattr(main.phone, "_QUEUED_SECONDS", 0)
    events = _events("phone_task", {"goal": "Open Settings"})
    assert events[-1] == {"type": "error", "message": "ARTEMIS task never started", "speak": main.phone.NOT_STARTED}
    assert fake.stopped == ["task-1"]


def test_task_with_no_new_action_is_stopped(monkeypatch):
    # Out of model credits, ARTEMIS keeps retrying while the task reads "running".
    fake = _fake_artemis(monkeypatch, "running")
    fake.steps = lambda self, task_id: asyncio.sleep(0, result=[])
    monkeypatch.setattr(main.phone, "_STALL_SECONDS", 0)
    events = _events("phone_task", {"goal": "Open Settings"})
    assert events[-1]["message"].startswith("ARTEMIS task stalled") and events[-1]["speak"] == main.phone.FAILED
    assert fake.stopped == ["task-1"]


def test_artemis_actions_are_read_from_its_trace_tree(monkeypatch):
    # The shape of a real ARTEMIS session tree: device actions are "action" nodes among logs.
    tree = [
        {"type": "log", "name": "artemis.sdk.agent", "trace_id": "l1", "timestamp": 1.0, "children": []},
        {"type": "agent", "name": "FlashRunner", "trace_id": "a0", "timestamp": 2.0, "children": [
            {"type": "action", "name": "manage_app", "trace_id": "s1", "timestamp": 3.0, "children": [
                {"type": "agent", "name": "hopper", "trace_id": "h1", "timestamp": 3.5, "children": []},
            ]},
            {"type": "log", "name": "artemis.agents.flash.runner", "trace_id": "l2", "timestamp": 4.0, "children": []},
            {"type": "action", "name": "swipe", "trace_id": "s2", "timestamp": 5.0, "children": []},
        ]},
    ]

    async def call(method, path, **kwargs):
        assert path == "/api/sessions/task-9/tree"
        return tree

    agent = main.phone.Artemis("http://artemis.test")
    monkeypatch.setattr(agent, "_call", call)
    assert asyncio.run(agent.steps("task-9")) == [("s1", "manage_app"), ("s2", "swipe")]


def test_relaunching_the_same_app_is_stopped(monkeypatch):
    # The agent launched Uber four times in a row; every launch reset the stall timer.
    fake = _fake_artemis(monkeypatch, "running")
    fake.steps = lambda self, task_id: asyncio.sleep(0, result=[(f"t{i}", "manage_app") for i in range(3)])
    events = _events("phone_task", {"goal": "Open Uber and book a ride"})
    assert events[-1]["speak"] == main.phone.LOOPING
    assert fake.stopped == ["task-1"]


def test_abort_stops_every_phone_task(monkeypatch):
    fake = _fake_artemis(monkeypatch, "running")
    assert client.post("/phone/stop").json() == {"stopped": True}
    assert fake.stopped == ["all"]
    _phone_agent_on(monkeypatch, mock=True)  # nothing real to stop
    assert client.post("/phone/stop").json() == {"stopped": False}


def test_hanging_up_stops_the_task_on_the_phone(monkeypatch):
    fake = _fake_artemis(monkeypatch, "running")  # never ends by itself

    async def hang_up():
        stream = main._run_phone("Open Settings")
        assert "Starting the phone agent" in await anext(stream)
        assert "Open app" in await anext(stream)
        await stream.aclose()  # "ruko": the app closes the stream

    asyncio.run(hang_up())
    assert fake.stopped == ["task-1"]
