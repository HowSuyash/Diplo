package com.dietcode.diplo.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceAgentTest {

    @Test
    fun plansAreARepliesQuestionsOrTasks() {
        assertEquals(AgentPlan.Reply("नमस्ते!"), AgentPlan.parse("""{"reply":"नमस्ते!"}"""))
        assertEquals(AgentPlan.Ask("कहां जाना है?"), AgentPlan.parse("<think></think>\n```json\n{\"ask\": \"कहां जाना है?\"}\n```"))
        assertEquals(
            AgentPlan.Task("Uber", "Book a ride to Connaught Place", listOf("Tap Where to", "Type Connaught Place")),
            AgentPlan.parse(
                """{"app":"Uber","goal":"Book a ride to Connaught Place","steps":["Tap Where to","Type Connaught Place",""]}""",
            ),
        )
        // No app or no goal is not a task.
        assertNull(AgentPlan.parse("""{"app":"Uber","steps":[]}"""))
        assertNull(AgentPlan.parse("I will open Uber"))
    }

    @Test
    fun modelRepliesBecomeSteps() {
        assertEquals(AgentStep.Tap(4), AgentStep.parse("""{"do":"tap","n":4}"""))
        assertEquals(AgentStep.Type(2, "dark"), AgentStep.parse("<think></think>\n```json\n{\"do\": \"type\", \"n\": 2, \"text\": \"dark\"}\n```"))
        assertEquals(AgentStep.Open("Uber"), AgentStep.parse("""{"do":"open","app":"Uber"}"""))
        assertEquals(AgentStep.Done("हो गया"), AgentStep.parse("""{"do":"done","say":"हो गया"}"""))
        assertEquals(AgentStep.Done(""), AgentStep.parse("""{"do":"done"}"""))
        assertEquals(AgentStep.Ask("कौन सी राइड?"), AgentStep.parse("""{"do":"ask","say":"कौन सी राइड?"}"""))
        assertEquals(AgentStep.Scroll, AgentStep.parse("""{"do":"scroll"}"""))
        assertEquals(AgentStep.Wait, AgentStep.parse("""{"do":"wait"}"""))
        // Anything it can't act on is Stuck, never a guess.
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"tap"}"""))
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"type","n":2}"""))
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"ask"}"""))
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"open"}"""))
        assertEquals(AgentStep.Stuck, AgentStep.parse("I would tap the button"))
        // On the phone the small model copied the prompt's placeholder.
        assertEquals(AgentStep.Stuck, AgentStep.parse("""{"do":"type","n":"2","text":"..."}"""))
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
        for (label in listOf("Choose Uber Go AC", "Dark theme", "PhonePe")) {
            assertEquals(label, false, OnDeviceAgent.isPayment(label))
        }
    }
}
