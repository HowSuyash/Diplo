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
    }

    @Test
    fun anythingUnsafeOrBrokenIsDropped() {
        assertNull(LocalBrain.fromReply("""{"kind": "phone", "action": "pay_upi", "params": {}}"""))
        assertNull(LocalBrain.fromReply("""{"kind": "phone", "action": "call", "params": {}}"""))
        assertNull(LocalBrain.fromReply("""{"kind": "web", "action": "web_search", "params": {"query": "x"}}"""))
        assertNull(LocalBrain.fromReply("""{"kind": "chat", "speak": ""}"""))
        assertNull(LocalBrain.fromReply("मुझे नहीं पता"))
        assertNull(LocalBrain.fromReply("""{"kind": "phone", "action": "call""""))
    }
}
