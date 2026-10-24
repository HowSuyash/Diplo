"""Wire format shared with the Android app. Keep in sync with docs/API_CONTRACT.md."""

from typing import Literal

from pydantic import BaseModel, Field

Kind = Literal["phone", "web", "chat", "clarify", "unknown"]


class Turn(BaseModel):
    user: str
    saarthi: str


class IntentRequest(BaseModel):
    text: str = Field(min_length=1)
    lang: str = "hi-IN"
    # The last few exchanges, oldest first, so "यह मैसेज सुरेश को भेजो" can mean the reply before.
    history: list[Turn] = Field(default_factory=list, max_length=6)


class Intent(BaseModel):
    kind: Kind
    # phone: call | send_message | set_alarm | read_screen
    # web:   web_task (params["flow"] names the flow)
    # chat / clarify / unknown: none (a chat reply is just `speak`)
    action: str = "none"
    params: dict[str, str] = Field(default_factory=dict)
    needs_confirmation: bool = False
    # What the app should say next, in the user's language (Devanagari reads best with hi-IN TTS).
    speak: str = ""


class WebRunRequest(BaseModel):
    flow: str
    params: dict[str, str] = Field(default_factory=dict)
