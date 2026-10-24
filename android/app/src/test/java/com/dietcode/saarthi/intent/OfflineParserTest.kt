package com.dietcode.saarthi.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineParserTest {

    private fun message(text: String): Map<String, String> {
        val intent = OfflineParser.parse(text)!!
        assertEquals("send_message", intent.action)
        assertEquals(true, intent.needsConfirmation)
        return intent.params
    }

    @Test
    fun whatsappMessageInHinglish() {
        assertEquals(
            mapOf("contact" to "mom", "message" to "main 7 baje aaunga", "channel" to "whatsapp"),
            message("Mom ko WhatsApp pe bolo main 7 baje aaunga"),
        )
    }

    @Test
    fun whatsappMessageInDevanagari() {
        assertEquals(
            mapOf("contact" to "मम्मी", "message" to "मैं सात बजे आऊंगा", "channel" to "whatsapp"),
            message("मम्मी को व्हाट्सएप पर बोलो मैं सात बजे आऊंगा"),
        )
    }

    @Test
    fun smsWithKi() {
        assertEquals(
            mapOf("contact" to "papa", "message" to "ghar aa raha hoon", "channel" to "sms"),
            message("Papa ko SMS bhejo ki ghar aa raha hoon"),
        )
    }

    @Test
    fun messageBeforeTheVerb() {
        assertEquals("main aa raha hoon", message("Mummy ko main aa raha hoon bol do")["message"])
    }

    @Test
    fun channelAfterTheVerb() {
        assertEquals(
            mapOf("contact" to "शिवम", "message" to "हाय", "channel" to "whatsapp"),
            message("शिवम को हाय मैसेज करो व्हाट्सएप पर"),
        )
        assertEquals("हाय", message("आलोक को व्हाट्सएप पर हाय मैसेज करो")["message"])
    }

    @Test
    fun callIsNotAMessage() {
        val intent = OfflineParser.parse("Mummy ko call karo")!!
        assertEquals("call", intent.action)
        assertEquals("mummy", intent.params["contact"])
    }

    @Test
    fun alarms() {
        assertEquals("6", OfflineParser.parse("Subah 6 baje ka alarm lagao")!!.params["hour"])
        assertEquals("19", OfflineParser.parse("Shaam 7 baje ka alarm lagao")!!.params["hour"])
    }

    private fun alarm(text: String): String {
        val intent = OfflineParser.parse(text)!!
        assertEquals(text, "set_alarm", intent.action)
        return "${intent.params["hour"]}:${intent.params["minute"]}"
    }

    @Test
    fun alarmsAsTheHindiRecognizerWritesThem() {
        assertEquals("6:0", alarm("सुबह 6 बजे का अलार्म लगाओ"))
        assertEquals("6:0", alarm("सुबह छह बजे का अलार्म लगा दो"))
        assertEquals("6:30", alarm("सुबह 6:30 का अलार्म लगाओ"))
        assertEquals("6:30", alarm("साढ़े छह बजे का अलार्म"))
        assertEquals("6:30", alarm("साढ़े छह बजे का अलार्म"))
        assertEquals("7:15", alarm("सवा सात बजे का alarm"))
        assertEquals("8:45", alarm("पौने नौ बजे अलार्म लगाओ"))
        assertEquals("6:0", alarm("सुबह 6 का अलार्म लगाओ"))
        assertEquals("18:0", alarm("6 p.m. का अलार्म लगाओ"))
        assertEquals("21:0", alarm("रात 9 बजे का अलार्म"))
        assertEquals("0:0", alarm("रात 12 बजे का अलार्म"))
        assertEquals("14:0", alarm("दोपहर 2 बजे का अलार्म"))
        assertEquals("5:0", alarm("मुझे 5 बजे उठा देना"))
        assertEquals("6:0", alarm("alarm laga do 6 baje"))
    }

    @Test
    fun alarmWithoutATimeAsksForIt() {
        assertEquals(AgentIntent.Kind.CLARIFY, OfflineParser.parse("अलार्म लगा दो")!!.kind)
        // "उठा" without a time is not an alarm, and doesn't stop the call rules.
        assertEquals("call", OfflineParser.parse("राहुल को फोन करो वो उठा नहीं रहा")!!.action)
    }

    @Test
    fun readScreen() {
        assertEquals("read_screen", OfflineParser.parse("Screen pe kya likha hai padho")!!.action)
    }

    @Test
    fun webRequestsGoToTheBackend() {
        assertNull(OfflineParser.parse("Meri scholarship ka status batao"))
        assertNull(OfflineParser.parse("PM Kisan ke baare mein batao"))
    }

    @Test
    fun confirmations() {
        assertEquals(true, OfflineParser.confirmation("haan bhej do"))
        assertEquals(false, OfflineParser.confirmation("nahi haan"))
        assertNull(OfflineParser.confirmation("kya bola"))
    }
}
