package com.dietcode.diplo.intent

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

    // "<app> खोलो" / "<app> kholo" / "open <app>". Not "चालू करो": that is for the torch or Wi-Fi.
    private val openTarget = Regex(
        """^(.+?)\s+(?:को\s+|ko\s+)?(?:खोलो|खोल\s+दो|ओपन\s+करो|ओपन\s+कर\s+दो|चलाओ|लॉन्च\s+करो|""" +
            """kholo|khol\s+do|open\s+karo|open\s+kar\s+do|chalao|launch\s+karo)$""",
    )
    private val openEnglish = Regex("""^(?:open|launch)\s+(.+)$""")
    private val insideApp = Regex("""\s(?:mein|me|pe|par|में|पर|पे)\s""")
    // "यूट्यूब पर सिद्धू मूसे वाला का गाना लगाओ": play inside an app. Only play verbs, because
    // "इंटरनेट पर ... खोजो" is a web search.
    private val playIn = Regex(
        """^(.+?)\s+(?:पर|पे|में|pe|par|mein|me)\s+(.+?)\s+(?:लगाओ|लगा\s+दो|चलाओ|चला\s+दो|बजाओ|बजा\s+दो|सुनाओ|""" +
            """lagao|laga\s+do|chalao|chala\s+do|bajao|baja\s+do|sunao|play\s+karo)$""",
    )

    // Settings by name, and complaints about them: "brightness bahut hai, apne aap theek rahe" -> auto.
    private val settingWords = listOf(
        "brightness" to listOf("ब्राइटनेस", "brightness", "रोशनी", "roshni"),
        "torch" to listOf("टॉर्च", "टोर्च", "torch", "फ्लैशलाइट", "flashlight", "flash light"),
        "dnd" to listOf("डू नॉट डिस्टर्ब", "do not disturb", "dnd", "डिस्टर्ब", "disturb"),
        "silent" to listOf("साइलेंट", "silent", "वाइब्रेट", "vibrate"),
        "volume" to listOf("वॉल्यूम", "volume", "आवाज़", "आवाज", "awaaz", "awaz", "साउंड", "sound"),
        "font_size" to listOf("फ़ॉन्ट", "फॉन्ट", "font", "अक्षर", "akshar", "text size", "लिखावट"),
        "wifi" to listOf("वाईफाई", "वाई-फाई", "वाई फाई", "wifi", "wi-fi", "wi fi"),
        "bluetooth" to listOf("ब्लूटूथ", "bluetooth"),
        "mobile_data" to listOf("मोबाइल डेटा", "mobile data"),
        "location" to listOf("लोकेशन", "location", "जीपीएस", "gps"),
    )
    // Checked in order, so a complaint ("कम है": too low, turn it up) wins over the word in it ("कम").
    private val settingValues = listOf(
        "auto" to listOf("अपने आप", "apne aap", "ऑटो", "auto", "खुद से", "khud se"),
        // The recognizer often writes "full" as "फूल" (flower).
        "max" to listOf("फुल", "फूल", "full", "सबसे ज़्यादा", "सबसे ज्यादा", "maximum", "max", "पूरी"),
        "min" to listOf("सबसे कम", "minimum"),
        "mute" to listOf("म्यूट", "mute"),
        "up" to listOf(
            "कम है", "kam hai", "छोटे हैं", "chhote hain", "छोटा है", "chhota hai", "दिखाई नहीं", "dikhai nahi",
            "सुनाई नहीं", "sunai nahi", "अंधेरा", "andhera",
        ),
        "down" to listOf(
            "बहुत है", "bahut hai", "ज़्यादा है", "ज्यादा है", "zyada hai", "तेज़ है", "तेज है", "tez hai",
            "चुभ", "chubh", "बड़े हैं", "bade hain",
        ),
        "off" to listOf("बंद", "band", "ऑफ", "off", "बुझा", "bujha", "हटाओ", "hatao"),
        "on" to listOf("चालू", "chalu", "ऑन", "on", "जला", "jala"),
        "down" to listOf(
            "कम", "kam", "घटा", "ghata", "धीमा", "धीमी", "dheema", "dheemi", "छोटा", "छोटे", "chhota", "chhote",
            "decrease", "lower",
        ),
        "up" to listOf(
            "बढ़ा", "badha", "ज़्यादा", "ज्यादा", "zyada", "तेज़", "तेज", "tez", "बड़ा", "बड़े", "bada", "bade",
            "increase", "louder",
        ),
    )
    // Said with no value: "मुझे डिस्टर्ब मत करो", "फ़ोन साइलेंट कर दो", "टॉर्च".
    private val settingDefaults = mapOf("dnd" to "on", "silent" to "on", "torch" to "on")

    // "राहुल का नंबर 98765 43210 सेव करो" / "98765 43210 को राहुल नाम से सेव करो"; the backend's
    // model handles other phrasings.
    private val number = """(\+?\d[\d\s-]{8,}\d)"""
    private val saveNameFirst = Regex("""^(.+?)\s+(?:का|ka)\s+(?:नंबर|नम्बर|number)\s+$number\s+(?:सेव|save)""")
    private val saveNumberFirst = Regex("""^$number\s+(?:को|ko)\s+(.+?)\s+(?:नाम\s+से|naam\s+se)\s+(?:सेव|save)""")
    // "राहुल का कॉन्टैक्ट डिलीट करो" / "राहुल का नंबर हटा दो"
    private val deleteTarget =
        Regex("""^(.+?)\s+(?:का|की|ka|ki)\s+(?:कॉन्टैक्ट|कांटेक्ट|contact|नंबर|नम्बर|number)\s+(?:डिलीट|delete|हटा)""")

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
        // Informal imperatives too ("शिवम को भेज क्या कर रहा है"): bare भेज, बोल, लिख, बता, कह.
        """\s(?:bol(?:o|na|\s+do)?|bhej(?:o|\s+do)?|kaho|keh(?:\s+do)?|batao|bata(?:\s+do)?|likho|likh(?:\s+do)?|""" +
            """(?:message|msg|whatsapp|sms)\s+kar(?:o|\s+do)|""" +
            """बोलो|बोल\s+दो|बोल|भेजो|भेज\s+दो|भेज|कहो|कह\s+दो|कह|बताओ|बता\s+दो|बता|लिखो|लिख\s+दो|लिख|""" +
            """(?:मैसेज|व्हाट्सएप|एसएमएस)\s+कर(?:ो|\s+दो))(?=\s|$)""",
    )
    // "WhatsApp pe", "मैसेज में" and the like: where to send, not part of the name or message.
    private val channelWords = Regex(
        """(?:whatsapp|sms|message|msg|व्हाट्सएप|व्हाट्सऐप|मैसेज|एसएमएस)(?:\s+(?:pe|par|me|mein|se|पे|पर|में|से))?""",
    )
    private val smsWords = listOf("sms", "एसएमएस")
    private val composeWords =
        Regex("""^(?:और|aur)\s|(?:^|\s)(?:लिखो|लिख\s+दो|लिखकर|लिख\s+के|likho|likh\s+do|likhkar|likh\s+ke)(?=\s|$)""")
    private val spaces = Regex("""\s+""")

    fun parse(raw: String): AgentIntent? {
        val text = raw.trim().lowercase().map { devanagariDigits[it] ?: it }.joinToString("")

        if (readWords.any { it in text } && screenWords.any { it in text }) {
            return AgentIntent(AgentIntent.Kind.PHONE, "read_screen", speak = "स्क्रीन पढ़ रहा हूं।")
        }

        parseMessage(text)?.let { return it }

        parseContactEdit(text)?.let { return it }

        playIn.find(text)?.let { match ->
            val (app, query) = match.destructured
            return AgentIntent(
                AgentIntent.Kind.PHONE, "open_app",
                params = mapOf("app" to app.trim(), "query" to query.trim()),
                speak = "${app.trim()} पर ${query.trim()} चला रहा हूं।",
            )
        }

        (openTarget.find(text) ?: openEnglish.find(text))?.let { match ->
            val app = match.groupValues[1].trim()
            // "clock app mein stopwatch kholo" opens a page inside an app: a task for the agent.
            if (!insideApp.containsMatchIn(app)) {
                return AgentIntent(AgentIntent.Kind.PHONE, "open_app", params = mapOf("app" to app), speak = "$app खोल रहा हूं।")
            }
        }

        parseSetting(text)?.let { return it }

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

    /** Saving or deleting a contact. Both always ask first; deleting names the saved contact. */
    private fun parseContactEdit(text: String): AgentIntent? {
        val save = saveNameFirst.find(text)?.destructured?.let { (name, num) -> name to num }
            ?: saveNumberFirst.find(text)?.destructured?.let { (num, name) -> name to num }
        if (save != null) {
            val (name, num) = save
            val digits = num.filter(Char::isDigit)
            return AgentIntent(
                AgentIntent.Kind.PHONE, "save_contact",
                params = mapOf("name" to name.trim(), "number" to digits),
                needsConfirmation = true,
                // Read the number in two groups, the way people say it.
                speak = "${name.trim()}, नंबर ${digits.chunked(5).joinToString(" ")}, कॉन्टैक्ट में सेव करूं?",
            )
        }
        val name = deleteTarget.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return AgentIntent(
            AgentIntent.Kind.PHONE, "delete_contact",
            params = mapOf("contact" to name),
            needsConfirmation = true,
            speak = "$name का कॉन्टैक्ट डिलीट करूं? यह वापस नहीं आएगा।",
        )
    }

    /** A setting with a clear value; vaguer ones ("ब्राइटनेस ठीक करो") are left to the backend's model. */
    private fun parseSetting(text: String): AgentIntent? {
        val setting = settingWords.firstOrNull { (_, words) -> words.any { has(text, it) } }?.first ?: return null
        val value = settingValues.firstOrNull { (_, words) -> words.any { has(text, it) } }?.first
            ?: settingDefaults[setting] ?: return null
        return AgentIntent(
            AgentIntent.Kind.PHONE, "set_setting",
            params = mapOf("setting" to setting, "value" to value),
            speak = "ठीक है।",
        )
    }

    // Latin words match whole words ("on" must not match "phone"); Devanagari matches substrings.
    private fun has(text: String, phrase: String): Boolean =
        if (phrase.first() in 'a'..'z') Regex("""(?<![a-z])${Regex.escape(phrase)}(?![a-z])""").containsMatchIn(text)
        else decompose(phrase) in decompose(text)

    // Nukta letters come precomposed (ढ़ U+095D) or as letter + nukta; compare one form.
    private val nuktaBase = mapOf(
        'क़' to 'क', 'ख़' to 'ख', 'ग़' to 'ग', 'ज़' to 'ज',
        'ड़' to 'ड', 'ढ़' to 'ढ', 'फ़' to 'फ', 'य़' to 'य',
    )

    private fun decompose(s: String) = buildString {
        for (c in s) nuktaBase[c]?.let { append(it).append('़') } ?: append(c)
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
            .replace(spaces, " ")
            // The hi-IN recognizer hears "hi" as "है", which is never a message on its own.
            .let { if (it == "है") "हाय" else it }
        // "सुरेश को मैसेज करो और अपना इंट्रोडक्शन लिखो": an instruction to write the text, not the
        // text itself. The backend's LLM writes it, and the confirmation shows what it wrote.
        if (composeWords.containsMatchIn(message)) return null
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

    // "यस", "ओके": spoken English in Devanagari, as the hi-IN recognizer writes it.
    private val yesWords = listOf("haan", "han", "हाँ", "हां", "हा", "yes", "ok", "okay", "kar do", "कर दो", "ji", "जी", "यस", "ओके")
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

    // Not "बस": it also means "bus". Latin as whole words: "stopwatch kholo" is not a stop.
    private val stopWords = Regex("""\b(stop|ruko)\b|रुको""")
    // Only on their own: "टॉर्च बंद करो" turns the torch off, it doesn't stop Diplo.
    private val stopPhrases = listOf("band karo", "band kar do", "बंद करो", "बंद कर दो")
    private val stopFillers = setOf("डिप्लो", "diplo", "अरे", "are", "please", "प्लीज", "ji", "जी")

    fun isStop(raw: String): Boolean {
        val text = raw.trim().lowercase()
        if (stopWords.containsMatchIn(text)) return true
        if (stopPhrases.none { it in text }) return false
        val rest = stopPhrases.fold(text) { t, phrase -> t.replace(phrase, " ") }
        return rest.split(Regex("""\s+""")).all { it.isEmpty() || it in stopFillers }
    }
}
