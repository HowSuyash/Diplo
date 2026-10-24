package com.dietcode.diplo.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalBrainTest {

    @Test
    fun complaintBecomesASetting() {
        val intent = LocalBrain.fromReply(
            """{"kind": "phone", "action": "set_setting", "params": {"setting": "silent", "value": "on"}, "speak": ""}""",
        )!!
        assertEquals(AgentIntent.Kind.PHONE, intent.kind)
        assertEquals(mapOf("setting" to "silent", "value" to "on"), intent.params)
        assertEquals("ठीक है।", intent.speak)
        assertEquals(false, intent.needsConfirmation)
    }

    @Test
    fun callsAlwaysAskInOurWords() {
        // The model claims it is already calling; Diplo asks first, in its own fixed sentence.
        val intent = LocalBrain.fromReply(
            """{"kind": "phone", "action": "call", "params": {"contact": "पापा"}, "speak": "पापा को कॉल कर रहा हूं"}""",
        )!!
        assertEquals("पापा को कॉल करूं?", intent.speak)
        assertEquals(true, intent.needsConfirmation)
    }

    @Test
    fun numbersAndThinkingAndFencesAreTolerated() {
        val intent = LocalBrain.fromReply(
            "<think>alarm</think>\n```json\n{\"kind\": \"phone\", \"action\": \"set_alarm\", \"params\": {\"hour\": 6, \"minute\": 30}}\n```",
        )!!
        assertEquals(mapOf("hour" to "6", "minute" to "30"), intent.params)
        assertEquals("6 बजकर 30 मिनट का अलार्म लगा रहा हूं।", intent.speak)
    }

    @Test
    fun messageEndingIsTrimmedInTheQuestion() {
        val intent = LocalBrain.fromReply(
            """{"kind": "phone", "action": "send_message", "params": {"contact": "राहुल", "message": "मैं आ रहा हूँ।", "channel": "whatsapp"}}""",
        )!!
        assertEquals("राहुल को यह मैसेज भेजूं: मैं आ रहा हूँ?", intent.speak)
        assertEquals("मैं आ रहा हूँ।", intent.params["message"])
    }

    @Test
    fun chatKeepsOnlyTheReply() {
        val intent = LocalBrain.fromReply("""{"kind": "chat", "action": "joke", "params": {"x": "y"}, "speak": "नमस्ते!"}""")!!
        assertEquals(AgentIntent(AgentIntent.Kind.CHAT, speak = "नमस्ते!"), intent)
        // Qwen3-0.6B on the phone named the kind itself.
        assertEquals(AgentIntent.Kind.CHAT, LocalBrain.fromReply("""{"kind":"small talk","speak":"एक चुटकुला"}""")!!.kind)
    }

    @Test
    fun tasksInsideAppsGoToTheAgent() {
        val dark = LocalBrain.fromReply(
            """{"kind":"app","action":"app_task","params":{"app":"Settings","goal":"Turn on Dark theme","target":"Dark theme"}}""",
        )!!
        assertEquals("app_task", dark.action)
        assertEquals("Dark theme", dark.params["target"])
        assertEquals(false, dark.needsConfirmation) // changing a setting doesn't ask
        assertEquals("", dark.speak) // AgentController asks in the user's own words
        val ride = LocalBrain.fromReply(
            """{"kind":"phone","action":"app_task","params":{"app":"Rapido","goal":"Book a bike ride","destination":"ABES College"}}""",
        )!!
        assertEquals(true, ride.needsConfirmation) // booking does
        assertNull(LocalBrain.fromReply("""{"kind":"app","action":"app_task","params":{"goal":"Book a ride"}}"""))
    }

    @Test
    fun webQuestionsGoToTinyFish() {
        val intent = LocalBrain.fromReply("""{"kind":"web","action":"web_search","params":{"query":"aaj ka mausam"}}""")!!
        assertEquals(mapOf("flow" to "web_search", "query" to "aaj ka mausam"), intent.params)
    }

    @Test
    fun anythingUnsafeOrBrokenIsDropped() {
        assertNull(LocalBrain.fromReply("""{"kind": "phone", "action": "pay_upi", "params": {}}"""))
        assertNull(LocalBrain.fromReply("""{"kind": "phone", "action": "call", "params": {}}"""))
        assertNull(LocalBrain.fromReply("""{"kind": "chat", "speak": ""}"""))
        assertNull(LocalBrain.fromReply("मुझे नहीं पता"))
        assertNull(LocalBrain.fromReply("""{"kind": "phone", "action": "call""""))
    }
}
