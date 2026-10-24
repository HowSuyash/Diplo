package com.dietcode.saarthi.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNamesTest {

    @Test
    fun hindiSpellingsOfAppNames() {
        val pairs = listOf(
            "यूट्यूब" to "YouTube", "व्हाट्सएप" to "WhatsApp", "इंस्टाग्राम" to "Instagram",
            "क्रोम" to "Chrome", "पेटीएम" to "Paytm", "फेसबुक" to "Facebook", "स्पॉटिफाई" to "Spotify",
            "कैमरा" to "Camera", "नेटफ्लिक्स" to "Netflix", "ज़ोमैटो" to "Zomato", "स्विगी" to "Swiggy",
            "फोन पे" to "PhonePe", "गूगल पे" to "Google Pay", "घड़ी" to "Clock", "youtube" to "YouTube",
        )
        for ((spoken, label) in pairs) assertEquals("$spoken vs $label", 0, AppNames.score(spoken, label))
    }

    @Test
    fun partialNamesAndFillers() {
        assertEquals(0, AppNames.score("ज़रा यूट्यूब ऐप", "YouTube"))
        assertEquals(1, AppNames.score("सेटिंग", "Settings"))
        assertEquals(2, AppNames.score("मैप्स", "Google Maps"))
        assertEquals(0, AppNames.score("प्ले स्टोर", "Play Store"))
    }

    @Test
    fun theClosestAppWins() {
        val phone = AppNames.score("फोन", "Phone")!!
        val phonePe = AppNames.score("फोन", "PhonePe")!!
        assertTrue(phone < phonePe)
    }

    @Test
    fun otherAppsDoNotMatch() {
        assertNull(AppNames.score("यूट्यूब", "Instagram"))
        assertNull(AppNames.score("कैमरा", "Calculator"))
        assertNull(AppNames.score("", "YouTube"))
    }
}
