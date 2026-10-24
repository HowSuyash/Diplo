package com.dietcode.diplo.intent

/**
 * The common tasks inside apps, recognised instantly without the on-device model (which takes
 * seconds): dark mode, booking a ride in a named cab app, searching inside an app. They become
 * the same app_task intents LocalBrain returns, for OnDeviceAgent. Both scripts, like OfflineParser.
 */
object AppTaskRules {

    private val cabApps = listOf(
        "Uber" to listOf("uber", "उबर"),
        "Ola" to listOf("ola", "ओला"),
        "Rapido" to listOf("rapido", "रैपीडो", "रैपिडो", "रापिडो"),
        "inDrive" to listOf("indrive", "इनड्राइव"),
    )
    private val rideWords = Regex(
        """\b(book|ride|cab|bike|auto|taxi|chalo|jana|jaana)\b|बुक|राइड|कैब|बाइक|ऑटो|टैक्सी|चलो|जाना""",
    )
    private val modes = listOf(
        "bike" to listOf("bike", "बाइक"),
        "auto" to listOf("auto", "ऑटो"),
        "cab" to listOf("cab", "कैब", "taxi", "टैक्सी", "car", "कार"),
    )
    // "rapido se ABES college ke liye …" / "uber से कनॉट प्लेस तक …" / "book an uber to connaught place"
    private val destinationHindi = Regex("""(?:\bse\b|से|\bpe\b|पे|\bpar\b|पर)\s+(.+?)\s+(?:ke liye|के लिए|\btak\b|तक|\bjana\b|जाना|\bchalo\b|चलो)""")
    private val destinationEnglish = Regex("""\b(?:to|till|for)\s+(.+?)(?:\s+(?:on|with|via|using|in)\s+\S+)?\s*$""")
    // The recognizer writes spoken English in Devanagari: "बुक ए उबर टू कनॉट प्लेस".
    private val destinationSpoken = Regex("""(?:टू|फॉर)\s+(.+?)\s*$""")
    // "book an uber ride for me" names no place.
    private val notPlaces = setOf("me", "us", "mujhe", "hum", "मी", "मुझे", "हम", "अस")
    private val placeFillers = Regex(
        """(?<![\p{L}\p{M}])(mujhe|मुझे|jana hai|jaana hai|jana he|जाना है|tak|तक|chalo|चलो|please|प्लीज|ko|को)(?![\p{L}\p{M}])""",
    )

    private val darkMode = Regex("""dark (mode|theme)|डार्क (मोड|थीम)""")
    private val off = Regex("""\b(off|band|disable)\b|बंद|ऑफ""")

    // "youtube pe arijit singh search karo" / "amazon में headphones खोजो"
    private val searchIn = Regex(
        """^(.+?)\s+(?:app\s+|ऐप\s+)?(?:pe|par|mein|me|पे|पर|में)\s+(.+?)\s+(?:search|khojo|dhundho|dhoondo|सर्च|खोजो|ढूंढो)(?:\s+(?:karo|kar do|करो|कर दो))?$""",
    )
    // "clock app mein stopwatch kholo" / "youtube mein meri history dikhao" / "settings में ब्लूटूथ खोलो":
    // a page inside an app. (The on-device model took the YouTube one for a web question.)
    private val openIn = Regex(
        """^(.+?)\s+(?:app\s+|ऐप\s+)?(?:pe|par|mein|me|पे|पर|में)\s+(.+?)\s+""" +
            """(?:kholo|khol do|open karo|open kar do|dikhao|dikha do|खोलो|खोल दो|ओपन करो|ओपन कर दो|दिखाओ|दिखा दो)$""",
    )
    // "meri history" is the "History" page.
    private val possessive = Regex("""^(?:meri|mera|mere|my|apni|apna|apne|मेरी|मेरा|मेरे|अपनी|अपना|अपने)\s+""")

    // "meri instagram profile dikhao" / "whatsapp ki settings kholo": a page named along with its
    // app. (The on-device model took the Instagram one for a call.)
    private val apps = listOf(
        "Instagram" to listOf("instagram", "insta", "इंस्टाग्राम"),
        "WhatsApp" to listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप"),
        "YouTube" to listOf("youtube", "यूट्यूब"),
        "Spotify" to listOf("spotify", "स्पॉटिफाई"),
        "Gmail" to listOf("gmail", "जीमेल"),
        "Maps" to listOf("maps", "मैप्स"),
        "Clock" to listOf("clock", "घड़ी"),
        "Settings" to listOf("settings", "setting", "सेटिंग्स", "सेटिंग"),
    )
    private val showVerb = Regex(
        """\s+(?:kholo|khol do|open karo|open kar do|dikhao|dikha do|खोलो|खोल दो|ओपन करो|ओपन कर दो|दिखाओ|दिखा दो)$""",
    )
    private val joiners = setOf(
        "ki", "ka", "ke", "की", "का", "के", "app", "ऐप", "mein", "me", "में", "pe", "par", "पे", "पर",
        "meri", "mera", "mere", "my", "apni", "apna", "apne", "मेरी", "मेरा", "मेरे", "अपनी", "अपना", "अपने",
    )

    fun parse(raw: String): AgentIntent? {
        val text = raw.trim().lowercase()

        if (darkMode.containsMatchIn(text)) {
            val wanted = if (off.containsMatchIn(text)) "off" else "on"
            return task(mapOf("app" to "Settings", "goal" to "Turn $wanted Dark theme", "target" to "Dark theme"))
        }

        val cab = cabApps.firstOrNull { (_, names) -> names.any { has(text, it) } }?.first
        if (cab != null && rideWords.containsMatchIn(text)) {
            val destination = (destinationHindi.find(text) ?: destinationEnglish.find(text) ?: destinationSpoken.find(text))
                ?.groupValues?.get(1)?.let(::withoutCabWords)?.trim()?.takeUnless { it.isEmpty() || it in notPlaces }
            val mode = modes.firstOrNull { (_, words) -> words.any { has(text, it) } }?.first ?: "ride"
            // Without a destination the cab app can't be driven anywhere (the model pressed Back
            // and closed Uber), so ask for it; AgentController hands the answer to destinationAnswer.
            if (destination == null) {
                return AgentIntent(
                    AgentIntent.Kind.CLARIFY,
                    params = mapOf("ask" to "destination", "app" to cab, "mode" to mode),
                    speak = "कहां जाना है?",
                )
            }
            return ride(cab, mode, destination)
        }

        searchIn.find(text)?.let { match ->
            val (app, query) = match.destructured
            return task(mapOf("app" to app.trim(), "goal" to "Search for ${query.trim()}", "query" to query.trim(), "target" to ""))
        }

        openIn.find(text)?.let { match ->
            val (app, named) = match.destructured
            val page = named.trim().replace(possessive, "")
            return task(mapOf("app" to app.trim(), "goal" to "Open $page", "target" to page))
        }

        return pageOfApp(text)
    }

    /** The answer to "कहां जाना है?" ("connaught place jana hai") as the ride it completes. */
    fun destinationAnswer(asked: AgentIntent, answer: String): AgentIntent? {
        val app = asked.params["app"] ?: return null
        val place = answer.trim().lowercase().replace(placeFillers, " ").replace(Regex("""\s+"""), " ").trim()
        if (place.isEmpty()) return null
        return ride(app, asked.params["mode"] ?: "ride", place)
    }

    private fun ride(app: String, mode: String, destination: String): AgentIntent {
        val params = mutableMapOf("app" to app, "goal" to "Book a $mode to $destination", "destination" to destination)
        if (mode != "ride") params["mode"] = mode
        // The "haan" question names what gets booked, not just the words heard.
        val what = mapOf("bike" to "बाइक", "auto" to "ऑटो", "cab" to "कैब")[mode] ?: "राइड"
        return task(params).copy(speak = "$app से $destination के लिए $what बुक करूं?")
    }

    private fun pageOfApp(text: String): AgentIntent? {
        val verb = showVerb.find(text) ?: return null
        val body = text.substring(0, verb.range.first)
        val (app, names) = apps.firstOrNull { (_, names) -> names.any { has(body, it) } } ?: return null
        // What is left once the app and the little words are gone is the page: "profile".
        val page = body.split(Regex("""\s+""")).filter { it.isNotEmpty() && it !in names && it !in joiners }.joinToString(" ")
        if (page.isEmpty()) return null // just "instagram kholo": OfflineParser opens the app
        return task(mapOf("app" to app, "goal" to "Open $page", "target" to page))
    }

    // The app names and ride words left in the matched destination ("an uber to cp" → "cp").
    private fun withoutCabWords(text: String): String =
        cabApps.flatMap { it.second }.fold(text) { t, name -> t.replace(Regex("""(?<![\p{L}])${Regex.escape(name)}(?![\p{L}])"""), " ") }
            .replace(Regex("""\s+"""), " ")

    private fun task(params: Map<String, String>): AgentIntent {
        val goal = params["goal"].orEmpty()
        // Booking acts for the user, so it asks "haan" first; a setting or a search doesn't.
        val asks = Regex("""\b(book|order|send|pay|buy)\b""", RegexOption.IGNORE_CASE).containsMatchIn(goal)
        return AgentIntent(AgentIntent.Kind.PHONE, "app_task", params.filterValues { it.isNotEmpty() }, needsConfirmation = asks)
    }

    // Latin names as whole words ("ola" is not in "bola"); Devanagari as substrings.
    private fun has(text: String, name: String): Boolean =
        if (name.first() in 'a'..'z') Regex("""(?<![a-z])${Regex.escape(name)}(?![a-z])""").containsMatchIn(text) else name in text
}
