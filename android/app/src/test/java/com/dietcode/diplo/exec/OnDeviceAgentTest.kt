package com.dietcode.diplo.exec

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceAgentTest {

    @Test
    fun uberOpensWithTheDropAlreadySet() {
        val link = DeepLinks.link("Uber", mapOf("destination" to "Connaught Place"))!!
        assertEquals(
            "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=Connaught%20Place",
            link.uri,
        )
        assertEquals("com.ubercab", link.pkg)
    }

    @Test
    fun youtubeOpensOnItsResults() {
        val link = DeepLinks.link("YouTube", mapOf("query" to "arijit singh songs"))!!
        assertEquals("https://www.youtube.com/results?search_query=arijit%20singh%20songs", link.uri)
    }

    @Test
    fun appsWithoutALinkJustOpen() {
        assertNull(DeepLinks.link("Rapido", mapOf("destination" to "ABES College")))
        assertNull(DeepLinks.link("Uber", emptyMap()))
    }

    @Test
    fun darkThemeOpensTheDisplayPage() {
        val params = mapOf("app" to "Settings", "goal" to "Turn on Dark theme", "target" to "Dark theme")
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, DeepLinks.settingsAction(params))
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, DeepLinks.settingsAction(mapOf("app" to "Settings", "target" to "Bluetooth")))
        assertNull(DeepLinks.settingsAction(mapOf("app" to "Instagram", "target" to "Dark theme")))
    }

    @Test
    fun modelRepliesBecomeSteps() {
        assertEquals(AgentStep.Tap(4), AgentStep.parse("""{"do":"tap","n":4}"""))
        assertEquals(AgentStep.Type(2, "dark"), AgentStep.parse("<think></think>\n```json\n{\"do\": \"type\", \"n\": 2, \"text\": \"dark\"}\n```"))
        assertEquals(AgentStep.Done, AgentStep.parse("""{"do":"done"}"""))
        assertEquals(AgentStep.Scroll, AgentStep.parse("""{"do":"scroll"}"""))
        // Anything it can't act on is Stuck, never a guess.
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"tap"}"""))
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"type","n":2}"""))
        // On the phone it copied the prompt's placeholder.
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"type","n":"2","text":"..."}"""))
        assertEquals(AgentStep.Stuck, AgentStep.parse("I would tap the button"))
    }

    @Test
    fun screenLinesShowKindLabelAndState() {
        assertEquals("[3] toggle: Dark theme (off)", ScreenElement(3, ScreenElement.Kind.TOGGLE, "Dark theme", false).line())
        assertEquals("[1] input: (no label)", ScreenElement(1, ScreenElement.Kind.INPUT, "").line())
    }

    @Test
    fun payButtonsAreNeverTappedByTheAgent() {
        for (label in listOf("Pay ₹184", "Proceed to pay", "Send money", "भुगतान करें")) {
            assertEquals(label, true, OnDeviceAgent.isPayment(label))
        }
        for (label in listOf("Confirm Uber Go", "Dark theme", "PhonePe")) {
            assertEquals(label, false, OnDeviceAgent.isPayment(label))
        }
        assertEquals(false, OnDeviceAgent.wantsOn("Turn off Dark theme"))
        assertEquals(true, OnDeviceAgent.wantsOn("Turn on Dark theme"))
    }

    @Test
    fun pagesAreLookedForBehindTheAppsMenuTabs() {
        fun button(label: String) = ScreenElement(1, ScreenElement.Kind.BUTTON, label)
        for (label in listOf("You", "Profile", "More options", "Open navigation drawer", "प्रोफ़ाइल")) {
            assertEquals(label, true, OnDeviceAgent.isMenu(button(label)))
        }
        // Not a video titled "You belong with me", and not text.
        assertEquals(false, OnDeviceAgent.isMenu(button("You belong with me - Taylor Swift (Official Video)")))
        assertEquals(false, OnDeviceAgent.isMenu(button("Youtube Music")))
        assertEquals(false, OnDeviceAgent.isMenu(ScreenElement(1, ScreenElement.Kind.TEXT, "You")))
    }

    @Test
    fun onlyOnAndOffGoalsFlipSwitches() {
        assertEquals(true, OnDeviceAgent.flips("Turn on Dark theme"))
        assertEquals(true, OnDeviceAgent.flips("Turn off Dark theme"))
        // Reaching a page must never flip a switch that happens to be on it.
        assertEquals(false, OnDeviceAgent.flips("Open stopwatch"))
        assertEquals(true, OnDeviceAgent.opensPage("Open Bluetooth"))
        assertEquals(false, OnDeviceAgent.opensPage("Open Instagram and tell me how many likes my last post has"))
    }

    @Test
    fun questionsAreReadBackFromTheScreen() {
        assertEquals(true, OnDeviceAgent.asksSomething("Open Instagram and tell me how many likes my last post has"))
        assertEquals(true, OnDeviceAgent.asksSomething("Check the price of AirPods on Amazon"))
        assertEquals(false, OnDeviceAgent.asksSomething("Turn on Dark theme"))
        assertEquals(false, OnDeviceAgent.asksSomething("Search for arijit singh"))
    }
}
