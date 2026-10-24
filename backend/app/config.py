import os

from dotenv import load_dotenv

load_dotenv()

TINYFISH_API_KEY = os.getenv("TINYFISH_API_KEY", "")
# Mocks the billed Agent runs (the portal flows).
MOCK_TINYFISH = os.getenv("MOCK_TINYFISH", "1" if not TINYFISH_API_KEY else "0") == "1"
# Search and Fetch are free, so they only fall back to canned answers when there is no key.
MOCK_SEARCH = not TINYFISH_API_KEY

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "").rstrip("/")
LLM_MODEL = os.getenv("LLM_MODEL", "llama3.2:3b")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
# Let the LLM write web answers from the fetched pages. Off by default: in testing a small local
# model (llama3.2:3b) invented facts, e.g. the wrong temperature. Turn on with a strong model.
ANSWER_WITH_LLM = os.getenv("ANSWER_WITH_LLM", "0") == "1"

SCHOLARSHIP_PORTAL_URL = os.getenv("SCHOLARSHIP_PORTAL_URL", "https://scholarships.gov.in/")
PNR_PORTAL_URL = os.getenv(
    "PNR_PORTAL_URL", "https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html"
)
