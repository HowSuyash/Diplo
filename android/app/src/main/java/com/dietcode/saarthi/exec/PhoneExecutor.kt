package com.dietcode.saarthi.exec

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import com.dietcode.saarthi.intent.AgentIntent

/** Runs confirmed phone intents with Android Intents, falling back to accessibility taps. */
class PhoneExecutor(private val context: Context) {

    /** Returns what Saarthi should say about the result. */
    fun execute(intent: AgentIntent): String {
        val p = intent.params
        return when (intent.action) {
            "call" -> call(p["contact"].orEmpty())
            "send_message" -> sendMessage(p["contact"].orEmpty(), p["message"].orEmpty(), p["channel"] ?: "whatsapp")
            "set_alarm" -> setAlarm(p["hour"]?.toIntOrNull(), p["minute"]?.toIntOrNull() ?: 0, p["label"].orEmpty())
            "read_screen" -> readScreen()
            else -> "यह काम मैं अभी नहीं कर सकता।"
        }
    }

    private fun call(name: String): String {
        val number = findNumber(name) ?: return "$name का नंबर नहीं मिला।"
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (start(Intent(action, Uri.parse("tel:$number")))) "$name को कॉल कर रहा हूं।" else "कॉल नहीं हो पाया।"
    }

    private fun sendMessage(name: String, message: String, channel: String): String {
        val number = findNumber(name) ?: return "$name का नंबर नहीं मिला।"
        if (channel == "sms") {
            // TODO(track B): tap Send in the SMS app via accessibility, like WhatsApp below.
            start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
            return "मैसेज लिख दिया है।"
        }
        val digits = number.filter(Char::isDigit).let { if (it.length == 10) "91$it" else it }
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}"))
            .setPackage("com.whatsapp")
        if (!start(chat)) return "व्हाट्सएप नहीं खुला।"
        // The user already said "haan", so tap Send once WhatsApp has opened the chat.
        // TODO(track B): replace the fixed delay with a window-change event from the service.
        Handler(Looper.getMainLooper()).postDelayed({
            SaarthiAccessibilityService.instance?.let { it.clickById("com.whatsapp:id/send") || it.clickByText("Send") }
        }, 2500)
        return "$name को व्हाट्सएप पर मैसेज भेज रहा हूं।"
    }

    private fun setAlarm(hour: Int?, minute: Int, label: String): String {
        if (hour == null || hour !in 0..23 || minute !in 0..59) return "समय समझ नहीं आया।"
        val alarm = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifEmpty { "Saarthi" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(alarm)) "$hour बजकर $minute मिनट का अलार्म लगा दिया।" else "अलार्म नहीं लग पाया।"
    }

    private fun readScreen(): String {
        val service = SaarthiAccessibilityService.instance
            ?: return "पहले सेटिंग्स में Saarthi की एक्सेसिबिलिटी चालू कीजिए।"
        val lines = service.readScreen()
        return if (lines.isEmpty()) "स्क्रीन पर कुछ लिखा नहीं मिला।" else lines.joinToString(". ")
    }

    // Spoken relation words rarely match how the contact is saved, so try the common spellings.
    private val relationGroups = listOf(
        listOf("मम्मी", "माँ", "मां", "mummy", "mumma", "mom", "maa", "mother"),
        listOf("पापा", "papa", "dad", "father"),
        listOf("भैया", "भाई", "bhaiya", "bhai", "brother"),
        listOf("दीदी", "didi", "sister"),
    )

    private fun findNumber(name: String): String? {
        if (name.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return null
        val spoken = name.trim().lowercase()
        val candidates = relationGroups.firstOrNull { spoken in it } ?: listOf(spoken)
        for (candidate in candidates) {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER),
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$candidate%"),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        }
        return null
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
