package com.dietcode.saarthi.exec

/**
 * Matches a spoken name to saved contact names across scripts. The hi-IN recognizer writes names
 * in Devanagari ("राहुल") but most contacts are saved in Latin ("Rahul"), so both are reduced to a
 * rough sound key: romanized, then vowels and aspiration dropped ("rhl").
 */
object ContactNames {

    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // Precomposed nukta letters: क़ ख़ ग़ ज़ ड़ ढ़ फ़ य़
        'क़' to "q", 'ख़' to "kh", 'ग़' to "g", 'ज़' to "z",
        'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f", 'य़' to "y",
    )

    // The same letters written as base + combining nukta.
    private val nuktaForms = mapOf('क' to "q", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")

    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )

    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )

    private const val LATIN_VOWELS = "aeiou"
    private val aspiration = Regex("([^aeiouh])h")
    private val softC = Regex("c(?=[eiy])")
    private val vowelY = Regex("(?<=[^aeiou])y(?=[^aeiou]|$)")
    private val wordBreak = Regex("""[^\p{L}\p{M}]+""")

    // Said around a name but rarely saved with it: "Sharma ji", "Rahul bhai".
    private val honorifics = setOf(
        "ji", "जी", "bhai", "भाई", "bhaiya", "भैया", "sir", "सर", "madam", "मैडम",
        "uncle", "अंकल", "aunty", "आंटी", "didi", "दीदी",
    )

    /** How many words [saved] has beyond the spoken ones (lower is closer), or null if it doesn't match. */
    fun extraWords(spoken: String, saved: String): Int? {
        val words = words(spoken)
        val wanted = keys(words.filter { it !in honorifics }.ifEmpty { words })
        if (wanted.isEmpty()) return null
        val have = keys(words(saved))
        return if (have.containsAll(wanted)) have.size - wanted.size else null
    }

    private fun words(name: String) = name.lowercase().split(wordBreak).filter { it.isNotEmpty() }

    private fun keys(words: List<String>) = words.map(::key).filter { it.isNotEmpty() }

    internal fun key(word: String): String {
        var s = romanize(word).filter { it in 'a'..'z' }
        if (s.isEmpty()) return ""
        s = s.replace("x", "ks").replace("chh", "x").replace("ch", "x").replace("ck", "k")
        s = softC.replace(s, "s").replace("c", "k") // "Lucy", "Facebook": c before e/i/y sounds like s
            .replace("sh", "s").replace("ph", "f").replace('w', 'v').replace('z', 'j').replace('q', 'k')
        s = aspiration.replace(s, "$1")
        // A y after a consonant and before another one (or the end) is a vowel sound: "Mummy",
        // "Vicky", "Cryteaca" (Kritika). "Aryan" and "Shreya" keep theirs.
        s = vowelY.replace(s, "")
        val key = StringBuilder(if (s.first() in LATIN_VOWELS) "_" else "")
        for (c in s) if (c !in LATIN_VOWELS && c != key.lastOrNull()) key.append(c)
        return key.toString()
    }

    internal fun romanize(word: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            val consonant = consonants[c]
            when {
                consonant != null -> {
                    val nukta = word.getOrNull(i + 1) == NUKTA
                    out.append(if (nukta) nuktaForms[c] ?: consonant else consonant)
                    if (nukta) i++
                    // Every consonant carries an "a" unless a vowel sign or virama follows.
                    val next = word.getOrNull(i + 1)
                    if (next == null || (next !in matras && next != VIRAMA)) out.append('a')
                }
                c in matras -> out.append(matras.getValue(c))
                c in vowels -> out.append(vowels.getValue(c))
                c == 'ं' || c == 'ँ' -> out.append('n')
                c == 'ः' -> out.append('h')
                c == VIRAMA || c == NUKTA -> {}
                else -> out.append(c.lowercaseChar())
            }
            i++
        }
        return out.toString()
    }
}
