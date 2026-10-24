package com.dietcode.diplo.exec

import org.json.JSONException
import org.json.JSONObject

/** A screen element as the on-device agent's model sees it: plain text, no Android types. */
data class ScreenElement(val n: Int, val kind: Kind, val label: String, val checked: Boolean? = null) {
    enum class Kind { BUTTON, INPUT, TOGGLE, LIST, TEXT }

    /** How it is shown to the model: `[3] toggle: Dark theme (off)`. */
    fun line(): String {
        val state = when (checked) {
            true -> " (on)"
            false -> " (off)"
            null -> ""
        }
        return "[$n] ${kind.name.lowercase()}: ${label.ifEmpty { "(no label)" }}$state"
    }
}

/** One decision of the on-device agent. */
sealed interface AgentStep {
    data class Tap(val n: Int) : AgentStep
    data class Type(val n: Int, val text: String) : AgentStep
    data object Scroll : AgentStep
    data object Back : AgentStep
    data object Done : AgentStep
    data object Stuck : AgentStep

    companion object {
        private val thinking = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)

        /** The model's reply, e.g. `{"do":"tap","n":4}`. Anything it can't act on is [Stuck]. */
        fun parse(reply: String): AgentStep {
            val text = reply.replace(thinking, "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return Stuck
            val json = try {
                JSONObject(text.substring(start, end + 1))
            } catch (e: JSONException) {
                return Stuck
            }
            val n = json.optInt("n", 0)
            return when (json.optString("do").lowercase()) {
                "tap" -> if (n > 0) Tap(n) else Stuck
                // Not the prompt's own "..." placeholder: the small model copies it.
                "type" -> json.optString("text").trim().takeIf { n > 0 && it.any(Char::isLetterOrDigit) }?.let { Type(n, it) } ?: Stuck
                "scroll" -> Scroll
                "back" -> Back
                "done" -> Done
                else -> Stuck
            }
        }
    }
}
