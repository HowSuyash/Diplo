package com.dietcode.diplo.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebRulesTest {

    @Test
    fun pnrWithTenDigitsAsksBeforeTheBilledRun() {
        val intent = WebRules.parse("PNR 123 456 7890 ka status batao")!!
        assertEquals(AgentIntent.Kind.WEB, intent.kind)
        assertEquals(mapOf("flow" to "pnr_status", "pnr" to "1234567890"), intent.params)
        assertEquals(true, intent.needsConfirmation)
        assertEquals("पीएनआर 1234567890 का स्टेटस देखूं?", intent.speak)
    }

    @Test
    fun pnrWithoutTheNumberAsksForIt() {
        val intent = WebRules.parse("मेरी ट्रेन टिकट का स्टेटस बताओ")!!
        assertEquals(AgentIntent.Kind.CLARIFY, intent.kind)
        assertEquals("अपना दस अंकों का पीएनआर नंबर बोलिए।", intent.speak)
    }

    @Test
    fun scholarshipReadsDevanagariDigits() {
        val intent = WebRules.parse("मेरी स्कॉलरशिप का स्टेटस बताओ २०२६४५६७")!!
        assertEquals(mapOf("flow" to "scholarship_status", "application_id" to "20264567"), intent.params)
    }

    @Test
    fun questionsAreWebSearchesWithoutAsking() {
        for (text in listOf("PM Kisan yojana kya hai", "आज दिल्ली का मौसम कैसा है", "who won the match")) {
            val intent = WebRules.parse(text)!!
            assertEquals(text, mapOf("flow" to "web_search", "query" to text), intent.params)
            assertEquals(false, intent.needsConfirmation)
        }
    }

    @Test
    fun phoneCommandsAndAppTasksAreNotWebQuestions() {
        for (text in listOf(
            "Mummy ko batao main late hoon",
            "whatsapp pe rahul ko hi bolo",
            "instagram pe meri last post ke likes batao",
            "uber se cp kaise jaun",
            "dark mode on karo",
        )) {
            assertNull(text, WebRules.parse(text))
        }
    }

    @Test
    fun smallTalkIsForTheOnDeviceModel() {
        // "kaise" is a question word, and "namaste kaise ho" went to the web on the phone.
        for (text in listOf("namaste kaise ho", "नमस्ते कैसे हो", "tum kaun ho", "ek joke sunao", "how are you")) {
            assertNull(text, WebRules.parse(text))
        }
    }
}
