package com.dietcode.diplo.intent

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
    fun askingToWriteTheMessageGoesToTheBackend() {
        assertNull(OfflineParser.parse("व्हाट्सएप पर सुरेश को मैसेज करो और अपना इंट्रोडक्शन मैसेज में लिखो उसे"))
        assertNull(OfflineParser.parse("Rahul ko WhatsApp karo aur birthday wish likh do"))
        assertNull(OfflineParser.parse("अपना इंट्रोडक्शन सुयश को व्हाट्सएप पर लिखकर भेज दो"))
        // "लिखो" as the verb itself still dictates the message.
        assertEquals("हाय", message("आलोक को व्हाट्सएप पर है लिखो")["message"])
    }

    @Test
    fun informalImperatives() {
        assertEquals(
            mapOf("contact" to "शिवम", "message" to "क्या कर रहा है तू", "channel" to "whatsapp"),
            message("शिवम को भेज क्या कर रहा है तू"),
        )
        assertEquals("हाय", message("शिवम को हाय लिख")["message"])
        assertEquals("main aa raha hoon", message("Rahul ko bol main aa raha hoon")["message"])
    }

    @Test
    fun hiHeardAsHai() {
        assertEquals("हाय", message("कृतिका को व्हाट्सएप पर है लिखो")["message"])
        assertEquals("मैं घर पर है", message("मम्मी को बोलो मैं घर पर है")["message"])
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
    fun openApps() {
        for ((text, app) in listOf(
            "यूट्यूब खोलो" to "यूट्यूब", "ज़रा कैमरा खोल दो" to "ज़रा कैमरा", "WhatsApp kholo" to "whatsapp",
            "open youtube" to "youtube", "इंस्टाग्राम ओपन करो" to "इंस्टाग्राम",
        )) {
            val intent = OfflineParser.parse(text)!!
            assertEquals(text, "open_app", intent.action)
            assertEquals(text, app, intent.params["app"])
        }
        // Turning things on is a setting, not an app.
        assertNull(OfflineParser.parse("कैमरा चालू करो"))
    }

    @Test
    fun playInsideAnApp() {
        val intent = OfflineParser.parse("यूट्यूब पर सिद्धू मूसे वाला का गाना लगाओ")!!
        assertEquals("open_app", intent.action)
        assertEquals(mapOf("app" to "यूट्यूब", "query" to "सिद्धू मूसे वाला का गाना"), intent.params)
        assertEquals("गाने", OfflineParser.parse("यूट्यूब पर गाने बजाओ")!!.params["query"])
        assertEquals("spotify", OfflineParser.parse("spotify pe arijit ke gaane chalao")!!.params["app"])
        // Searching the web is not playing in an app.
        assertNull(OfflineParser.parse("इंटरनेट पर पीएम किसान के बारे में खोजो"))
    }

    private fun setting(text: String): String {
        val intent = OfflineParser.parse(text)!!
        assertEquals(text, "set_setting", intent.action)
        return "${intent.params["setting"]}=${intent.params["value"]}"
    }

    @Test
    fun settingsFromVagueHindi() {
        assertEquals("brightness=auto", setting("ब्राइटनेस बहुत है यार कुछ कर कि अपने आप ठीक रहे"))
        assertEquals("brightness=down", setting("ब्राइटनेस बहुत है"))
        assertEquals("brightness=up", setting("ब्राइटनेस बहुत कम है"))
        assertEquals("brightness=max", setting("brightness full kar do"))
        assertEquals("brightness=max", setting("ब्राइटनेस बहुत कम है इसको बढ़ा दे फूल पर करते"))
        assertEquals("volume=up", setting("आवाज़ बढ़ाओ"))
        assertEquals("volume=up", setting("आवाज़ बढ़ाओ"))
        assertEquals("volume=up", setting("आवाज़ बढ़ाओ"))
        assertEquals("volume=down", setting("आवाज़ कम करो"))
        assertEquals("volume=mute", setting("volume mute karo"))
        assertEquals("torch=on", setting("टॉर्च जला दो"))
        assertEquals("torch=off", setting("टॉर्च बंद करो"))
        assertEquals("dnd=on", setting("मुझे डिस्टर्ब मत करो"))
        assertEquals("silent=on", setting("फोन साइलेंट कर दो"))
        assertEquals("font_size=up", setting("अक्षर बहुत छोटे हैं"))
        assertEquals("wifi=off", setting("वाईफाई बंद करो"))
        // No clear value: the backend's model decides.
        assertNull(OfflineParser.parse("ब्राइटनेस ठीक करो"))
    }

    @Test
    fun saveAndDeleteContactsAlwaysAsk() {
        for (text in listOf("राहुल का नंबर 98765 43210 सेव करो", "९८७६५४३२१० को राहुल नाम से सेव करो")) {
            val save = OfflineParser.parse(text)!!
            assertEquals(text, "save_contact", save.action)
            assertEquals(text, mapOf("name" to "राहुल", "number" to "9876543210"), save.params)
            assertEquals(true, save.needsConfirmation)
        }
        val delete = OfflineParser.parse("राहुल का कॉन्टैक्ट डिलीट कर दो")!!
        assertEquals("delete_contact", delete.action)
        assertEquals("राहुल", delete.params["contact"])
        assertEquals(true, delete.needsConfirmation)
        assertEquals("delete_contact", OfflineParser.parse("राहुल का नंबर हटा दो")!!.action)
    }

    @Test
    fun stopOnlyWhenItIsTheWholeCommand() {
        assertEquals(true, OfflineParser.isStop("रुको"))
        assertEquals(true, OfflineParser.isStop("अरे बंद करो"))
        assertEquals(true, OfflineParser.isStop("stop it"))
        assertEquals(false, OfflineParser.isStop("टॉर्च बंद करो"))
        assertEquals(false, OfflineParser.isStop("वाईफाई बंद कर दो"))
        // On the phone this one silently stopped Diplo.
        assertEquals(false, OfflineParser.isStop("clock app mein stopwatch kholo"))
    }

    @Test
    fun aPageInsideAnAppIsNotAnAppName() {
        assertEquals(null, OfflineParser.parse("clock app mein stopwatch kholo"))
        assertEquals(mapOf("app" to "youtube"), OfflineParser.parse("youtube kholo")!!.params)
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
