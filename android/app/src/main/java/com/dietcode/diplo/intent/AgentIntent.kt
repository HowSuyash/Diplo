package com.dietcode.diplo.intent

import org.json.JSONObject

/** Mirrors the backend Intent schema. Keep in sync with docs/API_CONTRACT.md. */
data class AgentIntent(
    val kind: Kind,
    val action: String = "none",
    val params: Map<String, String> = emptyMap(),
    val needsConfirmation: Boolean = false,
    val speak: String = "",
) {
    enum class Kind { PHONE, WEB, CHAT, CLARIFY, UNKNOWN }

    companion object {
        fun fromJson(json: JSONObject): AgentIntent {
            val params = json.optJSONObject("params")
            return AgentIntent(
                kind = runCatching { Kind.valueOf(json.getString("kind").uppercase()) }
                    .getOrDefault(Kind.UNKNOWN),
                action = json.optString("action", "none"),
                params = params?.keys()?.asSequence()?.associateWith { params.getString(it) } ?: emptyMap(),
                needsConfirmation = json.optBoolean("needs_confirmation", false),
                speak = json.optString("speak", ""),
            )
        }
    }
}
