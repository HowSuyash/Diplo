package com.dietcode.diplo.exec

import com.dietcode.diplo.exec.ScreenElement.Kind

/**
 * Booking a ride the way every cab app lays it out, without the on-device model: type the
 * destination where it asks "Where to?", pick the matching suggestion, pick bike, auto or cab,
 * then press Book / Confirm. Each call looks at the screen and returns the next move, or null
 * when the screen doesn't fit the recipe (then the model decides that step).
 */
object RideRecipe {

    enum class Stage { TYPE, PICK, MODE, BOOK, DONE }

    /** Tap element [n], or type [text] into it, then go on at [next]. [books]: it presses Book/Confirm. */
    data class Move(val n: Int, val text: String?, val next: Stage, val say: String, val books: Boolean = false)

    private val whereTo = Regex(
        """where to|where are you going|search destination|enter destination|drop|destination|going to|""" +
            """kahan|कहाँ|कहां|मंज़िल|मंजिल""",
        RegexOption.IGNORE_CASE,
    )
    private val book = Regex("""^(book|confirm|request|choose)\b|बुक""", RegexOption.IGNORE_CASE)
    private val modeWords = mapOf(
        "bike" to listOf("bike", "moto", "बाइक"),
        "auto" to listOf("auto", "ऑटो"),
        "cab" to listOf("cab", "go", "mini", "sedan", "कैब"),
    )

    fun next(screen: List<ScreenElement>, destination: String, mode: String?, stage: Stage, booked: Int): Move? = when (stage) {
        Stage.TYPE -> {
            val input = screen.firstOrNull { it.kind == Kind.INPUT && whereTo.containsMatchIn(it.label) }
                ?: screen.singleOrNull { it.kind == Kind.INPUT }
            if (input != null) {
                Move(input.n, destination, Stage.PICK, "लिखा: $destination")
            } else {
                // The "Where to?" bar is often a button that opens the real search box.
                screen.firstOrNull { it.kind != Kind.INPUT && whereTo.containsMatchIn(it.label) }
                    ?.let { Move(it.n, null, Stage.TYPE, "दबाया: ${it.label}") }
            }
        }
        Stage.PICK -> {
            val word = destination.split(Regex("""\s+""")).firstOrNull { it.length >= 3 } ?: destination
            screen.firstOrNull { it.kind != Kind.INPUT && it.label.contains(word, ignoreCase = true) }
                ?.let { Move(it.n, null, Stage.MODE, "चुना: ${it.label}") }
        }
        Stage.MODE -> {
            val words = mode?.let { modeWords[it] }
            val pick = words?.let { w -> screen.firstOrNull { el -> el.kind != Kind.INPUT && w.any { el.label.contains(it, ignoreCase = true) } } }
            // No such ride on this screen, or none asked for: go straight to booking.
            pick?.let { Move(it.n, null, Stage.BOOK, "चुना: ${it.label}") } ?: next(screen, destination, mode, Stage.BOOK, booked)
        }
        Stage.BOOK -> screen.firstOrNull { it.kind == Kind.BUTTON && book.containsMatchIn(it.label) && !OnDeviceAgent.isPayment(it.label) }
            // "Choose Uber Go" then "Confirm pickup": at most two presses.
            ?.let { Move(it.n, null, if (booked >= 1) Stage.DONE else Stage.BOOK, "दबाया: ${it.label}", books = true) }
        Stage.DONE -> null
    }
}
