package com.dietcode.diplo.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactNamesTest {

    @Test
    fun devanagariMatchesLatin() {
        val pairs = listOf(
            "राहुल" to "Rahul", "सुयश" to "Suyash", "पीयूष" to "Piyush", "आभास" to "Aabhas",
            "संजय" to "Sanjay", "विक्की" to "Vicky", "मम्मी" to "Mummy", "द्विवेदी" to "Dwivedi",
            "शुक्ला" to "Shukla", "तिवारी" to "Tiwari", "अंकित" to "Ankit", "हर्ष" to "Harsh",
            "ईशान" to "Ishaan", "नेहा" to "Neha", "फ़ैज़" to "Faiz", "कृतिका" to "Cryteaca💗",
            "आर्यन" to "Aryan", "श्रेया" to "Shreya",
        )
        for ((spoken, saved) in pairs) assertEquals("$spoken vs $saved", 0, ContactNames.extraWords(spoken, saved))
    }

    @Test
    fun fewerExtraWordsIsCloser() {
        assertEquals(1, ContactNames.extraWords("राहुल", "Rahul Sharma"))
        assertEquals(0, ContactNames.extraWords("राहुल शर्मा", "Rahul Sharma"))
        assertEquals(1, ContactNames.extraWords("Suyash", "Suyash (College) 🎓"))
    }

    @Test
    fun honorificsAreIgnored() {
        assertEquals(1, ContactNames.extraWords("शर्मा जी", "Ramesh Sharma"))
        assertEquals(0, ContactNames.extraWords("राहुल भैया", "Rahul"))
    }

    @Test
    fun differentNamesDoNotMatch() {
        assertNull(ContactNames.extraWords("राहुल", "Rohit"))
        assertNull(ContactNames.extraWords("राहुल शर्मा", "Rahul Verma"))
        assertNull(ContactNames.extraWords("", "Rahul"))
    }
}
