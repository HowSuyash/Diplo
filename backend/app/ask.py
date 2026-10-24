"""General questions answered from the live web with TinyFish Search and Fetch.

"PM Kisan yojana kya hai" -> Search ranks pages -> Fetch reads the page behind the best snippet ->
Saarthi speaks that snippet's sentences in full, quoted from the page. Nothing is generated, so
nothing is made up. With ANSWER_WITH_LLM=1 an LLM writes the answer from the top pages instead.
Search and Fetch are free, so this runs without asking the user first.
"""

import logging
import re
from collections.abc import AsyncIterator
from datetime import date
from typing import Any
from urllib.parse import urlsplit

import httpx

from . import config, llm
from .tinyfish import TinyFish, TinyFishError

log = logging.getLogger(__name__)

FLOW_ID = "web_search"
TITLE = "Answer a question from the web"
REQUIRED = ("query",)

_SOURCES = 3  # search results shown to the user
_LLM_PAGES = 2  # pages the LLM reads: a CPU-only local model is slow on long input
_LLM_PAGE_CHARS = 1500
_MAX_SPOKEN = 350

# A voice answer needs text, so skip video and social results.
_SKIP_DOMAINS = "youtube.com,youtu.be,facebook.com,instagram.com,x.com"

_DEVANAGARI = re.compile(r"[ऀ-ॿ]")
# Hinglish questions are asked by Hindi speakers, so search Hindi pages for them.
_HINGLISH = re.compile(
    r"\b(kya|hai|hain|ka|ki|ke|ko|kaise|kab|kahan|kaun|kitna|kitni|kitne|mein|batao|aaj|kal|yojana|mausam)\b"
)
_SENTENCE = re.compile(r"(?<=[.!?।])\s+")
# Search engines join snippet fragments with these.
_FRAGMENT_BREAK = re.compile(r"\s*(?:\.\.\.|…|·|・)\s*")

_ANSWER_PROMPT = """You answer a spoken question for an Indian voice assistant, using only the web pages given.
Reply in one or two short sentences that sound natural read aloud: no markdown, lists, URLs or source numbers.
If the question is in Hindi or Hinglish, reply in simple Hindi written in Devanagari. Otherwise reply in English.
If the pages do not answer the question, say that you could not find it."""

_NOT_FOUND = "वेब पर इसका जवाब नहीं मिला।"


def _progress(message: str) -> dict[str, Any]:
    return {"type": "progress", "message": message}


def _language(query: str) -> str:
    return "hi" if _DEVANAGARI.search(query) or _HINGLISH.search(query.lower()) else "en"


def _site(hit: dict[str, Any]) -> str:
    """Readable host name: "xn--m1bet4hqd2b..." becomes "पीएमकिसान..."."""
    host = hit.get("site_name") or urlsplit(hit["url"]).hostname or ""
    try:
        return host.encode("ascii").decode("idna")
    except UnicodeError:
        return host


def _source(hit: dict[str, Any]) -> dict[str, str]:
    return {"title": hit.get("title", ""), "url": hit["url"], "site": _site(hit)}


def _plain(text: str | None) -> str:
    """Markdown (or model output) as one line of speakable text."""
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", text or "")  # images
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)  # links keep their text
    text = re.sub(r"[*_#`>|]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _trim_snippet(snippet: str) -> str:
    """A snippet without its cut-off tail."""
    text = _plain(snippet)
    if not re.search(r"(\.\.\.|…)$", text):
        return text
    text = re.sub(r"\s*(\.\.\.|…)$", "", text)
    sentences = _SENTENCE.split(text)
    whole = " ".join(sentences[:-1])
    return whole if len(whole) >= 40 else text


def _complete(snippet: str, page: str) -> str:
    """The snippet's sentences in full, quoted from the page it came from ("" if not found)."""
    fragment = max(_FRAGMENT_BREAK.split(_plain(snippet)), key=len)
    anchor = fragment[:40]
    text = _plain(page)
    at = text.find(anchor)
    if len(anchor) < 15 or at < 0:
        return ""
    ends = [m.end() for m in re.finditer(r"[.!?।]\s", text[max(0, at - 300) : at])]
    start = max(0, at - 300) + ends[-1] if ends else at
    spoken = ""
    for sentence in _SENTENCE.split(text[start : start + 1000]):
        if spoken and len(spoken) + len(sentence) > _MAX_SPOKEN:
            break
        spoken = f"{spoken} {sentence}".strip()
        if len(spoken) >= len(fragment) + (at - start):
            break
    # One run-on "sentence" (a menu, a table) is not an answer; the snippet is better then.
    return spoken if len(fragment) / 2 < len(spoken) <= _MAX_SPOKEN else ""


async def _fetch(tf: TinyFish, query: str, urls: list[str]) -> dict[str, str]:
    """url -> page text. A page that fails to load just falls back to its snippet."""
    try:
        fetched = await tf.fetch(urls, purpose=f"Answer this question: {query}")
    except (TinyFishError, httpx.HTTPError) as e:
        log.warning("TinyFish fetch failed: %s", e)
        return {}
    return {p["url"]: p["text"] for p in fetched.get("results", []) if isinstance(p.get("text"), str)}


def _llm_input(query: str, hits: list[dict[str, Any]], pages: dict[str, str]) -> str:
    parts = [f"Question: {query}"]
    for n, hit in enumerate(hits, 1):
        body = pages.get(hit["url"]) or hit.get("snippet", "")
        parts.append(f"[{n}] {hit.get('title', '')} ({_site(hit)})\n{body[:_LLM_PAGE_CHARS]}")
    return "\n\n".join(parts)


async def _mock(query: str) -> AsyncIterator[dict[str, Any]]:
    """No API key: a canned answer so the app can be built without one."""
    yield _progress("Searching the web")
    speak = f"यह नमूना जवाब है। \"{query}\" का असली जवाब TinyFish API key लगाने पर मिलेगा।"
    sources = [{"title": "Mock result", "url": "https://example.com/", "site": "example.com"}]
    yield {"type": "result", "data": {"answer": speak, "sources": sources}, "speak": speak}


async def answer(query: str) -> AsyncIterator[dict[str, Any]]:
    """Yields app events (docs/API_CONTRACT.md): progress steps, then one result or error."""
    if config.MOCK_SEARCH:
        async for event in _mock(query):
            yield event
        return

    tf = TinyFish(config.TINYFISH_API_KEY)
    lang = _language(query)
    yield _progress("Searching the web")
    try:
        found = await tf.search(
            query,
            language=lang,
            exclude_domains=_SKIP_DOMAINS,
            purpose=f"Answer this spoken question: {query}",
        )
    except (TinyFishError, httpx.HTTPError) as e:
        log.warning("TinyFish search failed: %s", e)
        yield {"type": "error", "message": str(e), "speak": "वेब सर्च नहीं हो पाया। थोड़ी देर बाद फिर कोशिश करें।"}
        return
    hits = [r for r in found.get("results", []) if r.get("url")][:_SOURCES]
    with_snippet = [h for h in hits if _plain(h.get("snippet"))]
    if not with_snippet:
        yield {"type": "error", "message": "no search results", "speak": _NOT_FOUND}
        return

    spoken = ""
    if config.ANSWER_WITH_LLM and llm.enabled():
        to_read = hits[:_LLM_PAGES]
        yield _progress("Reading " + ", ".join(_site(h) for h in to_read))
        pages = await _fetch(tf, query, [h["url"] for h in to_read])
        yield _progress("Writing the answer")
        # The date, so "aaj ka match" picks today's result over an old one the pages also mention.
        prompt = f"{_ANSWER_PROMPT}\nToday is {date.today():%d %B %Y}."
        spoken = _plain(await llm.chat(prompt, _llm_input(query, hits, pages), timeout=45.0))
    if not spoken:
        # Answer in the question's language when one of the top snippets is in it.
        in_lang = [h for h in with_snippet if (lang == "hi") == bool(_DEVANAGARI.search(h["snippet"]))]
        best = (in_lang or with_snippet)[0]
        yield _progress(f"Reading {_site(best)}")
        page = (await _fetch(tf, query, [best["url"]])).get(best["url"], "")
        spoken = _complete(best["snippet"], page) or _trim_snippet(best["snippet"])
    yield {"type": "result", "data": {"answer": spoken, "sources": [_source(h) for h in hits]}, "speak": spoken}
