package com.dietcode.diplo.exec

import com.dietcode.diplo.exec.ScreenElement.Kind

/**
 * One step of a plan, in the planner's small grammar (Google ARTEMIS plans in a grammar too):
 * `tap <words on the screen>`, `type <text>`, `search <text>`, `turn on <setting>`,
 * `turn off <setting>`, `open <app>`, `scroll`, `back`. Every verb is a skill that works in any
 * app; none knows a particular app.
 */
data class PlanStep(val verb: Verb, val target: String) {
    enum class Verb { OPEN, TAP, TYPE, SEARCH, TURN_ON, TURN_OFF, SCROLL, BACK }

    companion object {
        // Longer prefixes first: "turn on" before "turn", "search for" before "search".
        private val verbs = listOf(
            "turn on " to Verb.TURN_ON, "switch on " to Verb.TURN_ON, "enable " to Verb.TURN_ON,
            "turn off " to Verb.TURN_OFF, "switch off " to Verb.TURN_OFF, "disable " to Verb.TURN_OFF,
            "search for " to Verb.SEARCH, "search " to Verb.SEARCH,
            "type " to Verb.TYPE, "enter " to Verb.TYPE, "write " to Verb.TYPE,
            "open " to Verb.OPEN, "launch " to Verb.OPEN,
            "tap on " to Verb.TAP, "tap " to Verb.TAP, "click on " to Verb.TAP, "click " to Verb.TAP,
            "press " to Verb.TAP, "choose " to Verb.TAP, "select " to Verb.TAP, "pick " to Verb.TAP,
        )
        private val filler = Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE)

        /** A plan step as the planner wrote it; null when it is outside the grammar. */
        fun parse(step: String): PlanStep? {
            val text = step.trim().trimEnd('.', '!')
            val lower = text.lowercase()
            if (lower == "scroll" || lower.startsWith("scroll ")) return PlanStep(Verb.SCROLL, "")
            if (lower == "back" || lower == "go back" || lower == "press back") return PlanStep(Verb.BACK, "")
            val (prefix, verb) = verbs.firstOrNull { (prefix, _) -> lower.startsWith(prefix) } ?: return null
            val target = text.substring(prefix.length).trim().replace(filler, "").trim().trim('"', '\'', '“', '”').trim()
            return if (target.isEmpty()) null else PlanStep(verb, target)
        }
    }
}

/** Finding what a step names on the screen: the same for every app. */
object Skills {

    // Words a planner adds that the screen doesn't show: "tap the Display option".
    private val noise = setOf("the", "a", "an", "button", "box", "option", "tab", "icon", "field", "link", "menu item")
    private val searchLike = Regex("""search|where|destination|find|kahan|kahaan|खोज|कहां|कहाँ""", RegexOption.IGNORE_CASE)

    /**
     * The element whose label best matches [target], or null when nothing is close enough. Not an
     * input: after "type India Gate" the box itself says "India Gate", and the suggestion is meant.
     */
    fun best(elements: List<ScreenElement>, target: String): ScreenElement? =
        elements.filter { it.kind != Kind.LIST && it.kind != Kind.INPUT && it.label.isNotBlank() }
            // "Choose / Confirm / Book": the planner's alternatives when it can't know the exact words.
            .map { element -> element to target.split(" / ", " or ").maxOf { score(element.label, it.trim()) } }
            .filter { (_, score) -> score >= MIN_SCORE }
            // The best score; among equals a button over a text ("Choose Uber Go AC" over the heading
            // "Choose a trip"), then the shortest label ("Display" over a long description).
            .minWithOrNull(
                compareByDescending<Pair<ScreenElement, Int>> { it.second }
                    .thenBy { if (it.first.kind == Kind.TEXT) 1 else 0 }
                    .thenBy { it.first.label.length },
            )
            ?.first

    /** How well [label] matches [target], 0..100. */
    fun score(label: String, target: String): Int {
        val l = normal(label)
        val t = normal(target)
        if (l.isEmpty() || t.isEmpty()) return 0
        if (l == t) return 100
        if (l.startsWith(t) || t.startsWith(l) && l.length >= 3) return 90
        if (t in l) return 80
        if (l.length >= 3 && l in t) return 70
        val wanted = t.split(' ').filter { it !in noise }
        if (wanted.isEmpty()) return 0
        val have = l.split(' ').toSet()
        val share = wanted.count { it in have }.toFloat() / wanted.size
        return if (share >= 0.5f) (50 + 20 * share).toInt() else 0
    }

    /** The box to type into: an input, preferring one that looks like a search. */
    // The one with the cursor first, as a person types there: Uber's search page focuses "Where to?",
    // and its pickup box comes first on the screen.
    fun input(elements: List<ScreenElement>): ScreenElement? =
        elements.filter { it.kind == Kind.INPUT }.let { inputs ->
            inputs.firstOrNull { it.focused } ?: inputs.firstOrNull { searchLike.containsMatchIn(it.label) } ?: inputs.firstOrNull()
        }

    /** Whether [box] is surely the one to type into, not just the first box on a page still loading. */
    fun sure(box: ScreenElement) = box.focused || searchLike.containsMatchIn(box.label)

    /** What opens a search box when there is none yet ("Where to?", "Search"), never the mic. */
    // The shortest such label: a frame around the whole header ("Current location Inbox Where to?")
    // also contains the words, and tapping it opens nothing.
    fun searchOpener(elements: List<ScreenElement>): ScreenElement? =
        elements.filter { it.kind != Kind.INPUT && searchLike.containsMatchIn(it.label) && !voice.containsMatchIn(it.label) }
            .minWithOrNull(compareBy<ScreenElement> { if (it.kind == Kind.TEXT) 1 else 0 }.thenBy { it.label.length })

    private val voice = Regex("""\b(voice|mic|camera|image|lens|photo)\b""", RegexOption.IGNORE_CASE)

    private fun normal(text: String) = text.lowercase().replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ").trim()

    private const val MIN_SCORE = 50
}
