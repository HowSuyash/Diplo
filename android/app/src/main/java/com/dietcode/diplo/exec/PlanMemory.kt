package com.dietcode.diplo.exec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plans that worked, kept on the phone, as Google ARTEMIS replays steps it has done before: the
 * same request again goes straight to the skills, with no model call. Every plan here was written
 * by the on-device model; a plan that stops working is forgotten.
 */
class PlanMemory(context: Context) {

    private val prefs = context.getSharedPreferences("plans", Context.MODE_PRIVATE)

    fun recall(request: String): AgentPlan.Task? =
        prefs.getString(key(request), null)?.let { AgentPlan.parse(it) as? AgentPlan.Task }

    fun keep(request: String, task: AgentPlan.Task) {
        val json = JSONObject().put("app", task.app).put("goal", task.goal).put("steps", JSONArray(task.steps))
        prefs.edit().putString(key(request), json.toString()).apply()
    }

    fun forget(request: String) {
        prefs.edit().remove(key(request)).apply()
    }

    companion object {
        /** "Dark mode OFF karo!" and "dark mode off karo" are the same request. */
        fun key(request: String) = request.lowercase().replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ").trim()
    }
}
