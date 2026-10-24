package com.dietcode.diplo.intent

/**
 * Greetings, thanks and "who are you", answered instantly in Diplo's own words. The on-device
 * model is slow for these and tends to echo the user back. Jokes and other chat go to the model.
 */
object SmallTalk {

    // The first group the words match gives the reply: "namaste kaise ho" is answered as "kaise ho".
    private val replies = listOf(
        listOf(
            "tum kaun", "aap kaun", "tumhara naam", "aapka naam", "who are you", "your name",
            "तुम कौन", "आप कौन", "तुम्हारा नाम", "आपका नाम",
        ) to "मैं डिप्लो हूं, आपका फ़ोन सहायक। कोई भी काम बोलिए।",
        listOf(
            "kaise ho", "kaisi ho", "kaise hain", "kya haal", "how are you",
            "कैसे हो", "कैसी हो", "कैसे हैं", "क्या हाल",
        ) to "मैं ठीक हूं, धन्यवाद। बताइए, क्या करूं?",
        listOf("thank you", "thanks", "dhanyavad", "shukriya", "धन्यवाद", "शुक्रिया") to "आपका स्वागत है।",
        listOf("good night", "शुभ रात्रि") to "शुभ रात्रि।",
        listOf("good morning", "सुप्रभात") to "सुप्रभात! बताइए, क्या करूं?",
        listOf("namaste", "namaskar", "hello", "hi", "hey", "नमस्ते", "नमस्कार", "हेलो", "हैलो") to "नमस्ते! बताइए, क्या करूं?",
    )
    // Small talk for the model, but still not a web question.
    private val forTheModel = listOf("joke", "जोक", "चुटकुला")
    // Words that may stand around the small talk: "अरे डिप्लो, कैसे हो जी".
    private val fillers = setOf(
        "diplo", "डिप्लो", "ji", "जी", "bhai", "भाई", "are", "arey", "अरे", "ho", "हो", "hai", "है", "hain", "हैं",
        "aap", "आप", "tum", "तुम", "bahut", "बहुत", "so", "much", "very", "please", "प्लीज", "aur", "और", "and",
    )

    /** Diplo's reply when [raw] is nothing but small talk, else null. */
    fun reply(raw: String): AgentIntent? {
        val text = raw.trim().lowercase()
        val reply = replies.firstOrNull { (words, _) -> words.any { has(text, it) } }?.second ?: return null
        val rest = replies.flatMap { it.first }.fold(text) { t, word -> remove(t, word) }
        val left = rest.split(Regex("""[\s,.!?।]+""")).filter { it.isNotEmpty() && it !in fillers }
        return if (left.isEmpty()) AgentIntent(AgentIntent.Kind.CHAT, speak = reply) else null
    }

    /** Whether [lowered] has small talk in it at all: such text is never a web question. */
    fun mentions(lowered: String): Boolean =
        (replies.flatMap { it.first } + forTheModel).any { has(lowered, it) }

    // Latin as whole words ("hi" is not in "chahiye"); Devanagari as substrings, like OfflineParser.
    private fun has(text: String, word: String): Boolean =
        if (word.first() in 'a'..'z') latin(word).containsMatchIn(text) else word in text

    private fun remove(text: String, word: String): String =
        if (word.first() in 'a'..'z') text.replace(latin(word), " ") else text.replace(word, " ")

    private fun latin(word: String) = Regex("""(?<![a-z])${Regex.escape(word)}(?![a-z])""")
}
