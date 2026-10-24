package com.dietcode.diplo.intent

/**
 * Web tasks recognised on the phone, so in private mode only the question itself leaves it, for
 * TinyFish through the backend: portal flows by keyword, other questions by question words.
 * A port of the backend's match_rules (backend/app/intent.py); speak texts match its flows.py.
 */
object WebRules {

    private val devanagariDigits = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
    )

    private val scholarshipWords = listOf("scholarship", "स्कॉलरशिप", "स्कालरशिप", "छात्रवृत्ति", "chhatravritti")
    // The recognizer often mangles "PNR", so the ticket itself works as a keyword too.
    private val pnrWords = listOf(
        "pnr", "पीएनआर", "पी एन आर", "train ticket", "ticket status", "ticket ka status",
        "ट्रेन टिकट", "टिकट का स्टेटस", "टिकट स्टेटस",
    )

    // Latin words match whole words ("what" must not match "whatsapp"); Devanagari matches
    // substrings, because its vowel signs break \b.
    private val askLatin = Regex(
        """\b(kya hai|kya hota|kya hoti|kaun|kab|kahan|kaise|kitna|kitni|kitne|ke baare|ke bare""" +
            """|batao|bataiye|mausam|news|khabar|search|khojo|dhundho|dhoondo|google""" +
            """|what|who|when|where|how|which|why)\b""",
    )
    private val askDevanagari = listOf(
        "क्या है", "क्या होता", "क्या होती", "कौन", "कब", "कहां", "कहाँ", "कैसे", "कितना", "कितनी",
        "कितने", "के बारे", "बताओ", "बताइए", "बताइये", "मौसम", "खबर", "ख़बर", "खोजो", "ढूंढो",
    )
    // Phone commands and tasks inside an app are not web questions: "Mummy ko batao …" is a
    // message, "Instagram pe meri post ke likes batao" is for the on-device agent.
    private val notWebLatin = Regex(
        """\b(call|phone|alarm|whatsapp|message|sms|bolo|bhejo|screen|padho|ko batao|ko bata do""" +
            """|instagram|youtube|uber|ola|rapido|swiggy|zomato|amazon|flipkart|paytm|phonepe|gpay""" +
            """|settings|setting|spotify|gmail|chrome|camera|maps)\b""",
    )
    private val notWebDevanagari = listOf(
        "कॉल", "फोन", "फ़ोन", "अलार्म", "व्हाट्सएप", "मैसेज", "बोलो", "भेजो", "स्क्रीन", "पढ़ो",
        "को बताओ", "को बता दो", "इंस्टाग्राम", "यूट्यूब", "उबर", "ओला", "रैपीडो", "रैपिडो", "स्विगी",
        "ज़ोमैटो", "जोमैटो", "अमेज़न", "फ्लिपकार्ट", "पेटीएम", "फोनपे", "सेटिंग", "कैमरा",
    )

    /** A web flow or question, or null when it is not a web task. */
    fun parse(raw: String): AgentIntent? {
        val text = raw.trim()
        val lowered = text.lowercase()
        val digits = digits(text)
        if (scholarshipWords.any { it in lowered }) {
            return portal("scholarship_status", "application_id", digits.ifEmpty { null },
                ask = "अपना एप्लीकेशन आईडी बोलिए।", confirm = "एप्लीकेशन आईडी %s डालूं?")
        }
        if (pnrWords.any { it in lowered }) {
            return portal("pnr_status", "pnr", digits.takeIf { it.length == 10 },
                ask = "अपना दस अंकों का पीएनआर नंबर बोलिए।", confirm = "पीएनआर %s का स्टेटस देखूं?")
        }
        return if (isQuestion(lowered)) search(text) else null
    }

    fun search(query: String) = AgentIntent(
        AgentIntent.Kind.WEB, "web_search",
        params = mapOf("flow" to "web_search", "query" to query.trim()),
        speak = "वेब पर देख रहा हूं।",
    )

    private fun portal(flow: String, field: String, value: String?, ask: String, confirm: String): AgentIntent =
        if (value == null) {
            AgentIntent(AgentIntent.Kind.CLARIFY, params = mapOf("flow" to flow), speak = ask)
        } else {
            AgentIntent(
                AgentIntent.Kind.WEB, "web_task",
                params = mapOf("flow" to flow, field to value),
                needsConfirmation = true, // a portal run is billed, so it always asks first
                speak = confirm.format(value),
            )
        }

    private fun isQuestion(lowered: String): Boolean {
        if (notWebLatin.containsMatchIn(lowered) || notWebDevanagari.any { it in lowered }) return false
        return askLatin.containsMatchIn(lowered) || askDevanagari.any { it in lowered }
    }

    private fun digits(text: String) = text.map { devanagariDigits[it] ?: it }.filter(Char::isDigit).joinToString("")
}
