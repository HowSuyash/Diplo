package com.dietcode.diplo.exec

import com.dietcode.diplo.exec.PlanStep.Verb
import com.dietcode.diplo.exec.ScreenElement.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillsTest {

    @Test
    fun planStepsInTheGrammar() {
        assertEquals(PlanStep(Verb.TAP, "Display"), PlanStep.parse("tap Display"))
        assertEquals(PlanStep(Verb.TAP, "Where to?"), PlanStep.parse("Tap on the \"Where to?\""))
        assertEquals(PlanStep(Verb.TURN_OFF, "Dark theme"), PlanStep.parse("turn off Dark theme."))
        assertEquals(PlanStep(Verb.TURN_ON, "Bluetooth"), PlanStep.parse("Enable Bluetooth"))
        assertEquals(PlanStep(Verb.SEARCH, "Arijit Singh"), PlanStep.parse("search for Arijit Singh"))
        assertEquals(PlanStep(Verb.TYPE, "India Gate"), PlanStep.parse("type India Gate"))
        assertEquals(PlanStep(Verb.TAP, "Bike"), PlanStep.parse("choose Bike"))
        assertEquals(PlanStep(Verb.OPEN, "Uber"), PlanStep.parse("Open Uber"))
        assertEquals(PlanStep(Verb.SCROLL, ""), PlanStep.parse("scroll down"))
        assertEquals(PlanStep(Verb.BACK, ""), PlanStep.parse("go back"))
        // Outside the grammar: the model takes that step.
        assertNull(PlanStep.parse("Increase screen brightness"))
        assertNull(PlanStep.parse("tap"))
    }

    private fun el(n: Int, kind: Kind, label: String) = ScreenElement(n, kind, label)

    @Test
    fun theClosestLabelWinsAndTheShortestAmongEquals() {
        val settings = listOf(
            el(1, Kind.TEXT, "Settings"),
            el(2, Kind.BUTTON, "Display & brightness settings for your screen"),
            el(3, Kind.BUTTON, "Display"),
            el(4, Kind.BUTTON, "Battery"),
        )
        assertEquals(3, Skills.best(settings, "Display")?.n)
        assertEquals(3, Skills.best(settings, "the Display option")?.n)
        assertNull(Skills.best(settings, "Bluetooth"))
    }

    @Test
    fun uberAsTheAgentSeesIt() {
        // Frame-only buttons are named by their inner text (DiploAccessibilityService).
        val uber = listOf(
            el(1, Kind.TEXT, "Choose a trip"),
            el(2, Kind.BUTTON, "selected,Uber Go AC,Fare ₹194.27,Estimated drop-off: 04:00"),
            el(3, Kind.BUTTON, "Bike,Fare ₹146.15,Estimated drop-off: 04:01"),
            el(4, Kind.BUTTON, "Choose Uber Go AC"),
            el(5, Kind.BUTTON, "Google Map"),
        )
        assertEquals(3, Skills.best(uber, "Bike")?.n)
        assertEquals(4, Skills.best(uber, "Choose Uber Go AC")?.n)
        assertEquals(4, Skills.best(uber, "Choose")?.n)
    }

    @Test
    fun typedTextIsNotTappedAgain() {
        val suggestions = listOf(
            el(1, Kind.INPUT, "India Gate"),
            el(2, Kind.BUTTON, "India Gate, Rajpath, New Delhi"),
        )
        assertEquals(2, Skills.best(suggestions, "India Gate")?.n)
    }

    @Test
    fun theBoxToTypeIntoOrWhatOpensIt() {
        val home = listOf(el(1, Kind.BUTTON, "Search with your voice"), el(2, Kind.BUTTON, "Where to?"), el(3, Kind.BUTTON, "Home"))
        assertNull(Skills.input(home))
        assertEquals(2, Skills.searchOpener(home)?.n) // not the mic
        // Uber's home: the header frame carries the words too.
        val uber = listOf(el(1, Kind.BUTTON, "Current location Inbox Where to?"), el(2, Kind.BUTTON, "Where to?"), el(3, Kind.TEXT, "Where to?"))
        assertEquals(2, Skills.searchOpener(uber)?.n)
        val search = listOf(el(1, Kind.INPUT, "Pickup location"), el(2, Kind.INPUT, "Where to?"))
        assertEquals(2, Skills.input(search)?.n)
    }
}
