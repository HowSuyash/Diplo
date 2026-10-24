package com.dietcode.diplo.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmallTalkTest {

    private fun reply(text: String) = SmallTalk.reply(text)?.speak

    @Test
    fun greetingsGetDiplosOwnReply() {
        // On the phone the model answered this with kind "small talk", echoing the user back.
        assertEquals("मैं ठीक हूं, धन्यवाद। बताइए, क्या करूं?", reply("namaste kaise ho"))
        assertEquals("मैं ठीक हूं, धन्यवाद। बताइए, क्या करूं?", reply("अरे डिप्लो, कैसे हो जी"))
        assertEquals("नमस्ते! बताइए, क्या करूं?", reply("Hello Diplo"))
        assertEquals("मैं डिप्लो हूं, आपका फ़ोन सहायक। कोई भी काम बोलिए।", reply("tum kaun ho"))
        assertEquals("आपका स्वागत है।", reply("thank you so much"))
        assertEquals(AgentIntent.Kind.CHAT, SmallTalk.reply("नमस्ते")!!.kind)
    }

    @Test
    fun aCommandAfterAGreetingIsNotSmallTalk() {
        assertNull(reply("hello youtube pe arijit singh search karo"))
        assertNull(reply("namaste papa ko call karo"))
        assertNull(reply("ek joke sunao")) // the model tells jokes
        assertNull(reply("mujhe ek kitab chahiye")) // "hi" is not in "chahiye"
    }
}
