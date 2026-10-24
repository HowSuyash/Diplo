package com.dietcode.diplo.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTaskRulesTest {

    private fun task(text: String): Map<String, String> {
        val intent = AppTaskRules.parse(text)!!
        assertEquals("app_task", intent.action)
        return intent.params
    }

    @Test
    fun darkModeInBothScripts() {
        assertEquals(
            mapOf("app" to "Settings", "goal" to "Turn on Dark theme", "target" to "Dark theme"),
            task("dark mode on karo"),
        )
        assertEquals("Turn off Dark theme", task("डार्क मोड बंद करो")["goal"])
        assertEquals(false, AppTaskRules.parse("dark mode on karo")!!.needsConfirmation)
    }

    @Test
    fun ridesUseTheNamedCabAppAndAskFirst() {
        assertEquals(
            mapOf(
                "app" to "Rapido", "goal" to "Book a bike to abes engineering college",
                "destination" to "abes engineering college", "mode" to "bike",
            ),
            task("rapido se abes engineering college ke liye bike book karo"),
        )
        assertEquals(
            mapOf("app" to "Uber", "goal" to "Book a ride to connaught place", "destination" to "connaught place"),
            task("book an uber to connaught place"),
        )
        assertEquals("एबीईएस कॉलेज", task("रैपीडो से एबीईएस कॉलेज के लिए बाइक बुक करो")["destination"])
        assertEquals(true, AppTaskRules.parse("uber se cp jana hai")!!.needsConfirmation)
        assertEquals("cp", task("uber se cp jana hai")["destination"])
    }

    @Test
    fun searchInsideAnApp() {
        assertEquals(
            mapOf("app" to "youtube", "goal" to "Search for arijit singh", "query" to "arijit singh"),
            task("youtube pe arijit singh search karo"),
        )
    }

    @Test
    fun aPageInsideAnApp() {
        assertEquals(
            mapOf("app" to "clock", "goal" to "Open stopwatch", "target" to "stopwatch"),
            task("clock app mein stopwatch kholo"),
        )
        assertEquals(
            mapOf("app" to "settings", "goal" to "Open ब्लूटूथ", "target" to "ब्लूटूथ"),
            task("settings में ब्लूटूथ खोलो"),
        )
        assertEquals(false, AppTaskRules.parse("clock app mein stopwatch kholo")!!.needsConfirmation)
        assertEquals(
            mapOf("app" to "youtube", "goal" to "Open history", "target" to "history"),
            task("youtube mein meri history dikhao"),
        )
        assertEquals(
            mapOf("app" to "Instagram", "goal" to "Open profile", "target" to "profile"),
            task("meri instagram profile dikhao"),
        )
        assertEquals("settings", task("whatsapp ki settings kholo")["target"])
        assertEquals("WhatsApp", task("whatsapp ki settings kholo")["app"])
        assertNull(AppTaskRules.parse("instagram kholo")) // just the app: OfflineParser
        // The word "app" is not part of its name.
        assertEquals("youtube", task("youtube app pe arijit singh search karo")["app"])
    }

    @Test
    fun otherCommandsAreLeftAlone() {
        assertNull(AppTaskRules.parse("mummy ko bola main aa raha hoon")) // "bola" is not Ola
        assertNull(AppTaskRules.parse("PM Kisan yojana kya hai"))
        assertNull(AppTaskRules.parse("ola ka share price kya hai")) // a cab app, but no ride
    }
}
