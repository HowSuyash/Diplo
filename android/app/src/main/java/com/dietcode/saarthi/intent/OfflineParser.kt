package com.dietcode.saarthi.intent

/**
 * On-device parser for the phone commands, so they work with no network.
 * The hi-IN recognizer returns Devanagari ("मम्मी को कॉल करो"), so every rule
 * matches both scripts. Anything not matched here goes to the backend.
 *
 * TODO(track B): send_message ("Mummy ko WhatsApp pe bolo ..."), more phrasings,
 * and swap in the on-device model (Gemma via MediaPipe) if it is ready in time.
 */
object OfflineParser {

    private val devanagariDigits = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
    )

    private val callWords = listOf("call", "कॉल", "फोन", "फ़ोन", "phone")
    private val alarmWords = listOf("alarm", "अलार्म")
    private val readWords = listOf("padho", "पढ़ो", "पढो", "read", "सुनाओ", "sunao")
    private val screenWords = listOf("screen", "स्क्रीन")
    private val pmWords = listOf("shaam", "शाम", "raat", "रात", "dopahar", "दोपहर", "pm")

    // "<name> को call करो" / "<name> ko call karo" / "call <name>"
    private val callTarget = Regex("""^(.+?)\s+(?:को|ko)\s""")
    private val callEnglish = Regex("""^call\s+(.+)$""")
    private val time = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*(?:बजे|baje|बज|o'?clock|am|pm)""")

    fun parse(raw: String): AgentIntent? {
        val text = raw.trim().lowercase().map { devanagariDigits[it] ?: it }.joinToString("")

        if (readWords.any { it in text } && screenWords.any { it in text }) {
            return AgentIntent(AgentIntent.Kind.PHONE, "read_screen", speak = "स्क्रीन पढ़ रहा हूं।")
        }

        if (alarmWords.any { it in text }) {
            val match = time.find(text) ?: return null
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
            if (pmWords.any { it in text } && hour < 12) hour += 12
            return AgentIntent(
                AgentIntent.Kind.PHONE, "set_alarm",
                params = mapOf("hour" to "$hour", "minute" to "$minute"),
                speak = "$hour बजकर $minute मिनट का अलार्म लगा रहा हूं।",
            )
        }

        if (callWords.any { it in text }) {
            val name = (callTarget.find(text) ?: callEnglish.find(text))?.groupValues?.get(1)?.trim()
                ?: return null
            return AgentIntent(
                AgentIntent.Kind.PHONE, "call",
                params = mapOf("contact" to name),
                needsConfirmation = true,
                speak = "$name को कॉल करूं?",
            )
        }
        return null
    }

    private val yesWords = listOf("haan", "han", "हाँ", "हां", "हा", "yes", "ok", "okay", "kar do", "कर दो", "ji", "जी")
    private val noWords = listOf("nahi", "nahin", "नहीं", "नही", "no", "mat", "मत", "cancel", "ruko", "रुको")

    /** true = yes, false = no, null = unclear (ask again). Checks "no" first: "nahi haan" is a no. */
    fun confirmation(raw: String): Boolean? {
        val text = raw.trim().lowercase()
        return when {
            noWords.any { it in text } -> false
            yesWords.any { it in text } -> true
            else -> null
        }
    }

    // Not "बस": it also means "bus".
    private val stopWords = listOf("stop", "ruko", "रुको", "band karo", "बंद करो")

    fun isStop(raw: String): Boolean = stopWords.any { it in raw.trim().lowercase() }
}
