package com.dietcode.saarthi.intent

/**
 * On-device parser for the phone commands, so they work with no network.
 * The hi-IN recognizer returns Devanagari ("मम्मी को कॉल करो"), so every rule
 * matches both scripts. Anything not matched here goes to the backend.
 *
 * TODO(track B): more phrasings, and swap in the on-device model (Gemma via MediaPipe) if it
 * is ready in time.
 */
object OfflineParser {

    private val devanagariDigits = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
    )

    private val callWords = listOf("call", "कॉल", "फोन", "फ़ोन", "phone")
    private val alarmWords = listOf("alarm", "अलार्म")
    private val wakeWords = listOf("उठा", "जगा", "utha", "jaga")
    private val readWords = listOf("padho", "पढ़ो", "पढो", "read", "सुनाओ", "sunao")
    private val screenWords = listOf("screen", "स्क्रीन")
    private val nightWords = listOf("raat", "रात")
    private val pmWords = nightWords + listOf("shaam", "शाम", "dopahar", "दोपहर", "pm", "p.m")

    // "<name> को call करो" / "<name> ko call karo" / "call <name>"
    private val callTarget = Regex("""^(.+?)\s+(?:को|ko)\s""")
    private val callEnglish = Regex("""^call\s+(.+)$""")

    private val hourWords = mapOf(
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "छे" to 6,
        "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "chhah" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "gyarah" to 11, "barah" to 12, "baarah" to 12,
    )
    private val hourPattern = (listOf("""\d{1,2}""") + hourWords.keys.sortedByDescending { it.length }).joinToString("|")
    // साढ़े (half past), सवा (quarter past), पौने (quarter to). The ढ़ comes precomposed or with a nukta.
    private const val FRACTION = """सा(?:ढ़?|ढ़)े|sa+dhe|सवा|sawa|पौने|paune"""
    // Groups in every time pattern: 1 fraction, 2 hour, 3 minute.
    private val timePatterns = listOf(
        // "6 बजे", "साढ़े छह बजे", "6:30 baje", "6 a.m."
        Regex("""(?<![\p{L}\p{M}\d])(?:($FRACTION)\s+)?($hourPattern)(?:[:.](\d{2}))?\s*(?:बजे|बजकर|baje|बज|o'?clock|a\.?\s?m\b\.?|p\.?\s?m\b\.?)"""),
        // "6:30" on its own: the recognizer often writes times this way and drops बजे
        Regex("""()(?<!\d)(\d{1,2})[:.](\d{2})"""),
        // "सुबह 6 का अलार्म"
        Regex("""(?:subah|सुबह|shaam|शाम|raat|रात|dopahar|दोपहर)\s+(?:($FRACTION)\s+)?($hourPattern)()(?![\p{L}\p{M}\d])"""),
    )

    // "<name> ko [WhatsApp pe] bolo <message>" / "<name> को बोलो कि <message>" / "<name> ko <message> bol do"
    private val messageTarget = Regex("""^(.+?)\s+(?:को|ko)\s+(.+)$""")
    private val sayVerb = Regex(
        """\s(?:bol(?:o|na|\s+do)|bhej(?:o|\s+do)|kaho|keh\s+do|batao|bata\s+do|likho|likh\s+do|""" +
            """(?:message|msg|whatsapp|sms)\s+kar(?:o|\s+do)|""" +
            """बोलो|बोल\s+दो|भेजो|भेज\s+दो|कहो|कह\s+दो|बताओ|बता\s+दो|लिखो|लिख\s+दो|""" +
            """(?:मैसेज|व्हाट्सएप|एसएमएस)\s+कर(?:ो|\s+दो))(?=\s|$)""",
    )
    // "WhatsApp pe", "मैसेज में" and the like: where to send, not part of the name or message.
    private val channelWords = Regex(
        """(?:whatsapp|sms|message|msg|व्हाट्सएप|व्हाट्सऐप|मैसेज|एसएमएस)(?:\s+(?:pe|par|me|mein|se|पे|पर|में|से))?""",
    )
    private val smsWords = listOf("sms", "एसएमएस")

    fun parse(raw: String): AgentIntent? {
        val text = raw.trim().lowercase().map { devanagariDigits[it] ?: it }.joinToString("")

        if (readWords.any { it in text } && screenWords.any { it in text }) {
            return AgentIntent(AgentIntent.Kind.PHONE, "read_screen", speak = "स्क्रीन पढ़ रहा हूं।")
        }

        parseMessage(text)?.let { return it }

        // "मुझे 6 बजे उठा देना" is an alarm too, but "phone uthao" without a time is not.
        val alarm = alarmWords.any { it in text }
        val time = if (alarm || wakeWords.any { it in text }) alarmTime(text) else null
        if (time != null) {
            val (hour, minute) = time
            return AgentIntent(
                AgentIntent.Kind.PHONE, "set_alarm",
                params = mapOf("hour" to "$hour", "minute" to "$minute"),
                speak = "$hour बजकर $minute मिनट का अलार्म लगा रहा हूं।",
            )
        }
        if (alarm) return AgentIntent(AgentIntent.Kind.CLARIFY, speak = "कितने बजे का अलार्म लगाऊं?")

        if (callWords.any { it in text }) {
            val name = (callTarget.find(text) ?: callEnglish.find(text))?.groupValues?.get(1)?.trim()
                ?: return null
            return AgentIntent(
                AgentIntent.Kind.PHONE, "call",
                params = mapOf("contact" to name),
                needsConfirmation = true,
                speak = "${name.replaceFirstChar(Char::titlecase)} को कॉल करूं?",
            )
        }
        return null
    }

    /** 24-hour (hour, minute) from a spoken time, or null if there is none. */
    private fun alarmTime(text: String): Pair<Int, Int>? {
        val match = timePatterns.firstNotNullOfOrNull { it.find(text) } ?: return null
        val (fraction, spokenHour, spokenMinute) = match.destructured
        var hour = hourWords[spokenHour] ?: spokenHour.toInt()
        var minute = spokenMinute.ifEmpty { "0" }.toInt()
        when (fraction) {
            "" -> {}
            "सवा", "sawa" -> minute = 15
            "पौने", "paune" -> {
                hour -= 1
                minute = 45
            }
            else -> minute = 30 // साढ़े
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        if (pmWords.any { it in text } && hour in 1..11) hour += 12
        if (nightWords.any { it in text } && hour == 12) hour = 0
        return hour to minute
    }

    private fun parseMessage(text: String): AgentIntent? {
        val target = messageTarget.find(text) ?: return null
        val rest = " " + target.groupValues[2]
        val verb = sayVerb.find(rest) ?: return null
        // The message usually follows the verb; "<name> ko main aa raha hoon bol do" puts it before,
        // and "<name> को हाय मैसेज करो व्हाट्सएप पर" leaves only the channel after it.
        val after = rest.substring(verb.range.last + 1).replace(channelWords, " ").trim()
        val message = after.ifEmpty { rest.substring(0, verb.range.first).replace(channelWords, " ").trim() }
            .removePrefix("ki ").removePrefix("कि ").trim()
        val name = target.groupValues[1].replace(channelWords, " ").trim()
        if (name.isEmpty() || message.isEmpty()) return null
        val sms = smsWords.any { it in text }
        return AgentIntent(
            AgentIntent.Kind.PHONE, "send_message",
            params = mapOf("contact" to name, "message" to message, "channel" to if (sms) "sms" else "whatsapp"),
            needsConfirmation = true,
            speak = "${name.replaceFirstChar(Char::titlecase)} को ${if (sms) "एसएमएस" else "व्हाट्सएप"} पर भेजूं: $message?",
        )
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
