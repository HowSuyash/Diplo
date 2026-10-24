"""Wire format shared with the Android app. Keep in sync with docs/API_CONTRACT.md."""

from typing import Literal

from pydantic import BaseModel, Field

Kind = Literal["phone", "web", "clarify", "unknown"]


class IntentRequest(BaseModel):
    text: str = Field(min_length=1)
    lang: str = "hi-IN"


class Intent(BaseModel):
    kind: Kind
    # phone: call | send_message | set_alarm | read_screen
    # web:   web_task (params["flow"] names the flow)
    # clarify / unknown: none
    action: str = "none"
    params: dict[str, str] = Field(default_factory=dict)
    needs_confirmation: bool = False
    # What the app should say next, in the user's language (Devanagari reads best with hi-IN TTS).
    speak: str = ""


class WebRunRequest(BaseModel):
    flow: str
    params: dict[str, str] = Field(default_factory=dict)
