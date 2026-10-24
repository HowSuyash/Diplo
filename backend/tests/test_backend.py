import json
import os

os.environ["MOCK_TINYFISH"] = "1"
os.environ["LLM_BASE_URL"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.intent import match_rules  # noqa: E402
from app.main import app  # noqa: E402

client = TestClient(app)


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


def test_web_intent_always_confirms():
    body = client.post("/intent", json={"text": "scholarship status 20261234"}).json()
    assert body["needs_confirmation"] is True


def test_unknown_text_without_llm():
    assert client.post("/intent", json={"text": "kuch bhi"}).json()["kind"] == "unknown"


def test_mock_web_run_streams_progress_then_result():
    with client.stream(
        "POST", "/web/run", json={"flow": "scholarship_status", "params": {"application_id": "1"}}
    ) as resp:
        events = [json.loads(line[6:]) for line in resp.iter_lines() if line.startswith("data: ")]
    assert [e["type"] for e in events] == ["progress", "progress", "progress", "result"]
    assert "Approved" in events[-1]["speak"]


def test_web_run_rejects_missing_params():
    assert client.post("/web/run", json={"flow": "pnr_status"}).status_code == 422
