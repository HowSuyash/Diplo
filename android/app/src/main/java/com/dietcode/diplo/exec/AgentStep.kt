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

private val thinking = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)

// The JSON object in a model reply, which may come wrapped in a code fence or thinking.
private fun jsonIn(reply: String): JSONObject? {
    val text = reply.replace(thinking, "")
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return try {
        JSONObject(text.substring(start, end + 1))
    } catch (e: JSONException) {
        null
    }
}

/**
 * What the planner made of a request, as Google ARTEMIS plans before it acts: a reply, a question
 * for a detail the user left out, or a task in an app with its steps.
 */
sealed interface AgentPlan {
    data class Reply(val say: String) : AgentPlan
    data class Ask(val say: String) : AgentPlan
    data class Task(val app: String, val goal: String, val steps: List<String>) : AgentPlan

    companion object {
        /** The planner's JSON, or null when there is nothing usable in it. */
        fun parse(reply: String): AgentPlan? {
            val json = jsonIn(reply) ?: return null
            json.optString("reply").trim().takeIf { it.isNotEmpty() }?.let { return Reply(it) }
            json.optString("ask").trim().takeIf { it.isNotEmpty() }?.let { return Ask(it) }
            val app = json.optString("app").trim()
            val goal = json.optString("goal").trim()
            if (app.isEmpty() || goal.isEmpty()) return null
            val steps = json.optJSONArray("steps")
                ?.let { array -> (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty) }
                .orEmpty()
            return Task(app, goal, steps)
        }
    }
}

/** One decision of the on-device agent, ARTEMIS's actions on the accessibility tree. */
sealed interface AgentStep {
    data class Open(val app: String) : AgentStep
    data class Tap(val n: Int) : AgentStep
    data class Type(val n: Int, val text: String) : AgentStep
    data object Scroll : AgentStep
    data object Back : AgentStep
    data object Wait : AgentStep
    data class Done(val say: String) : AgentStep
    data class Ask(val say: String) : AgentStep
    data object Stuck : AgentStep

    companion object {
        /** The model's reply, e.g. `{"do":"tap","n":4}`. Anything it can't act on is [Stuck]. */
        fun parse(reply: String): AgentStep {
            val json = jsonIn(reply) ?: return Stuck
            val n = json.optInt("n", 0)
            val say = json.optString("say").trim()
            return when (json.optString("do").lowercase()) {
                "open" -> json.optString("app").trim().takeIf { it.isNotEmpty() }?.let(::Open) ?: Stuck
                "tap" -> if (n > 0) Tap(n) else Stuck
                // Not the prompt's own "..." placeholder: the small model copies it.
                "type" -> json.optString("text").trim().takeIf { n > 0 && it.any(Char::isLetterOrDigit) }?.let { Type(n, it) } ?: Stuck
                "scroll" -> Scroll
                "back" -> Back
                "wait" -> Wait
                "done" -> Done(say)
                "ask" -> if (say.isNotEmpty()) Ask(say) else Stuck
                else -> Stuck
            }
        }
    }
}
