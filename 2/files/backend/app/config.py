import os

from dotenv import load_dotenv

load_dotenv()

TINYFISH_API_KEY = os.getenv("TINYFISH_API_KEY", "")
MOCK_TINYFISH = os.getenv("MOCK_TINYFISH", "1" if not TINYFISH_API_KEY else "0") == "1"

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "").rstrip("/")
LLM_MODEL = os.getenv("LLM_MODEL", "llama3.2:3b")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")

SCHOLARSHIP_PORTAL_URL = os.getenv("SCHOLARSHIP_PORTAL_URL", "https://scholarships.gov.in/")
PNR_PORTAL_URL = os.getenv(
    "PNR_PORTAL_URL", "https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html"
)
