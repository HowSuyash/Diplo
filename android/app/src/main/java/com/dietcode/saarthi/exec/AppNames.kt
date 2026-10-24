package com.dietcode.saarthi.exec

/**
 * Matches a spoken app name to launcher labels with ContactNames' sound key, loosened for how
 * Hindi speech spells brand names: "यूट्यूब" (YouTube), "पेटीएम" (Paytm), "क्रोम" (Chrome).
 */
object AppNames {

    // Words for an app that sound nothing like its label.
    private val aliases = mapOf(
        "घड़ी" to "clock", "ghadi" to "clock", "संदेश" to "messages", "मैसेज" to "messages",
        "फोटो" to "photos", "फ़ोटो" to "photos", "photo" to "photos", "जीमेल" to "gmail",
        "फोन" to "phone", "फ़ोन" to "phone", "डायलर" to "phone", "कैलकुलेटर" to "calculator",
        "अलार्म" to "clock", "alarm" to "clock",
    )

    // Said around the name: "ज़रा यूट्यूब ऐप खोलो".
    private val fillers = setOf(
        "ऐप", "एप", "app", "एप्लीकेशन", "application", "ज़रा", "जरा", "zara", "please", "प्लीज",
        "मेरा", "मेरी", "मेरे", "mera", "meri", "mere",
    )

    private val wordBreak = Regex("""[^\p{L}\p{M}]+""")

    /** Lower is closer: 0 for the same name, more for extra label words; null if it isn't this app. */
    fun score(spoken: String, label: String): Int? {
        val words = spoken.lowercase().split(wordBreak).filter { it.isNotEmpty() && it !in fillers }
        val name = aliases[words.joinToString(" ")] ?: words.joinToString(" ")
        val said = keys(name)
        val have = keys(label)
        if (said.isEmpty() || have.isEmpty()) return null
        // Spaces differ too: "फोन पे" is "PhonePe".
        if (looseKey(name.replace(" ", "")) == looseKey(label.lowercase().replace(" ", ""))) return 0
        // "गूगल मैप्स" → "Google Maps"; a spoken word may also be the start of one ("सेटिंग" → "Settings").
        val all = said.all { s -> have.any { h -> h == s || (s.length >= 2 && h.startsWith(s)) } }
        return if (all) 1 + have.size - said.size else null
    }

    private fun keys(name: String) = name.lowercase().split(wordBreak).map(::looseKey).filter { it.length >= 2 }

    // Glides and "ch" vary most between the scripts: "youtube" vs "यूट्यूब", "chrome" vs "क्रोम".
    internal fun looseKey(word: String): String {
        val key = ContactNames.key(word).replace("y", "").replace('x', 'k')
        val out = StringBuilder()
        for (c in key) if (c != out.lastOrNull()) out.append(c)
        return out.toString()
    }
}
