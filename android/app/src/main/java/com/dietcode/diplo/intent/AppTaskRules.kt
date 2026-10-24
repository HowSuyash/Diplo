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

    private val darkMode = Regex("""dark (mode|theme)|डार्क (मोड|थीम)""")
    private val off = Regex("""\b(off|band|disable)\b|बंद|ऑफ""")

    // "youtube pe arijit singh search karo" / "amazon में headphones खोजो"
    private val searchIn = Regex(
        """^(.+?)\s+(?:pe|par|mein|me|पे|पर|में)\s+(.+?)\s+(?:search|khojo|dhundho|dhoondo|सर्च|खोजो|ढूंढो)(?:\s+(?:karo|kar do|करो|कर दो))?$""",
    )

    fun parse(raw: String): AgentIntent? {
        val text = raw.trim().lowercase()

        if (darkMode.containsMatchIn(text)) {
            val wanted = if (off.containsMatchIn(text)) "off" else "on"
            return task(mapOf("app" to "Settings", "goal" to "Turn $wanted Dark theme", "target" to "Dark theme"))
        }

        val cab = cabApps.firstOrNull { (_, names) -> names.any { has(text, it) } }?.first
        if (cab != null && rideWords.containsMatchIn(text)) {
            val destination = (destinationHindi.find(text) ?: destinationEnglish.find(text))?.groupValues?.get(1)
                ?.let(::withoutCabWords)?.trim()
            val mode = modes.firstOrNull { (_, words) -> words.any { has(text, it) } }?.first ?: "ride"
            val params = mutableMapOf("app" to cab, "goal" to "Book a $mode${destination?.let { " to $it" } ?: ""}")
            destination?.takeIf { it.isNotEmpty() }?.let { params["destination"] = it }
            return task(params)
        }

        searchIn.find(text)?.let { match ->
            val (app, query) = match.destructured
            return task(mapOf("app" to app.trim(), "goal" to "Search for ${query.trim()}", "query" to query.trim(), "target" to ""))
        }
        return null
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
