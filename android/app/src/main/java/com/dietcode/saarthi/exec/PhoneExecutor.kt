package com.dietcode.saarthi.exec

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.dietcode.saarthi.intent.AgentIntent

/** Runs confirmed phone intents with Android Intents, falling back to accessibility taps. */
class PhoneExecutor(private val context: Context) {

    data class Contact(val name: String, val number: String)

    private val settings = PhoneSettings(context)

    /** Returns what Saarthi should say about the result. */
    fun execute(intent: AgentIntent): String {
        val p = intent.params
        return when (intent.action) {
            "call", "send_message", "delete_contact" -> {
                // AgentController resolves the contact before asking, so the question names who it is.
                val spoken = p["contact"].orEmpty()
                val contact = p["number"]?.let { Contact(spoken, it) } ?: findContact(spoken)
                    ?: return "${spoken.replaceFirstChar(Char::titlecase)} का नंबर नहीं मिला।"
                when (intent.action) {
                    "call" -> call(contact)
                    "send_message" -> sendMessage(contact, p["message"].orEmpty(), p["channel"] ?: "whatsapp")
                    else -> deleteContact(contact)
                }
            }
            "save_contact" -> saveContact(p["name"].orEmpty(), p["number"].orEmpty())
            "set_alarm" -> setAlarm(p["hour"]?.toIntOrNull(), p["minute"]?.toIntOrNull() ?: 0, p["label"].orEmpty())
            "read_screen" -> readScreen()
            "open_app" -> openApp(p["app"].orEmpty(), p["query"].orEmpty())
            "set_setting" -> settings.apply(p["setting"].orEmpty(), p["value"].orEmpty())
            else -> "यह काम मैं अभी नहीं कर सकता।"
        }
    }

    private fun call(contact: Contact): String {
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (start(Intent(action, Uri.parse("tel:${contact.number}")))) "${contact.name} को कॉल कर रहा हूं।" else "कॉल नहीं हो पाया।"
    }

    private fun sendMessage(contact: Contact, message: String, channel: String): String {
        val number = contact.number
        if (channel == "sms") {
            // TODO(track B): tap Send in the SMS app via accessibility, like WhatsApp below.
            start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
            return "मैसेज लिख दिया है।"
        }
        val digits = number.filter(Char::isDigit).let { if (it.length == 10) "91$it" else it }
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}"))
            .setPackage("com.whatsapp")
        if (!start(chat)) return "व्हाट्सएप नहीं खुला।"
        tapWhatsAppSend()
        return "${contact.name} को व्हाट्सएप पर मैसेज भेज रहा हूं।"
    }

    // The user already said "haan", so tap Send once WhatsApp has opened the chat. A cold start
    // can take several seconds, so keep trying for ~9 s, and only while WhatsApp is in front.
    private fun tapWhatsAppSend(attempt: Int = 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            val service = SaarthiAccessibilityService.instance
            val inWhatsApp = service?.activePackage() == "com.whatsapp"
            val tapped = inWhatsApp && service != null &&
                (service.clickById("com.whatsapp:id/send") || service.clickByText("Send"))
            if (tapped || service == null || attempt >= 16) {
                Log.i("Saarthi", "WhatsApp Send tapped: $tapped (try ${attempt + 1}, in WhatsApp: $inWhatsApp)")
            } else {
                tapWhatsAppSend(attempt + 1)
            }
        }, if (attempt == 0) 1200L else 500L)
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

    /** Opens the app; with a [query], plays it there (YouTube, YouTube Music, Spotify) or searches for it. */
    private fun openApp(name: String, query: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        // As with contacts, the parser can take words before the name ("ज़रा मेरा यूट्यूब").
        val words = name.trim().split(Regex("""\s+"""))
        val (pkg, label) = words.indices.firstNotNullOfOrNull { drop ->
            val spoken = words.drop(drop).joinToString(" ")
            apps.mapNotNull { app -> AppNames.score(spoken, app.second)?.let { it to app } }
                .minByOrNull { it.first }?.second
        } ?: return "${name.replaceFirstChar(Char::titlecase)} नाम का ऐप नहीं मिला।"
        if (query.isNotBlank()) {
            val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            if (start(play)) return "$label पर $query चला रहा हूं।"
            val search = Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
            if (start(search)) return "$label में $query खोज रहा हूं।"
        }
        val open = pm.getLaunchIntentForPackage(pkg) ?: return "$label नहीं खुला।"
        return if (start(open)) "$label खोल रहा हूं।" else "$label नहीं खुला।"
    }

    /** A new phone-only contact (no Google account), so it shows up at once. */
    private fun saveContact(name: String, number: String): String {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (name.isBlank() || digits.count(Char::isDigit) < 10) return "नाम और दस अंकों का नंबर, दोनों बोलिए।"
        if (!granted(Manifest.permission.WRITE_CONTACTS)) return "कॉन्टैक्ट सेव करने की अनुमति नहीं है।"
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name)
                .build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, digits)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build(),
        )
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            "$name का नंबर सेव कर दिया।"
        } catch (e: OperationApplicationException) {
            "कॉन्टैक्ट सेव नहीं हो पाया।"
        } catch (e: RemoteException) {
            "कॉन्टैक्ट सेव नहीं हो पाया।"
        }
    }

    /** Deletes the whole contact, so a synced Google contact goes too. Only after "haan". */
    private fun deleteContact(contact: Contact): String {
        if (!granted(Manifest.permission.WRITE_CONTACTS)) return "कॉन्टैक्ट डिलीट करने की अनुमति नहीं है।"
        val resolver = context.contentResolver
        val id = resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID),
            "${Phone.DISPLAY_NAME} = ? AND ${Phone.NUMBER} = ?",
            arrayOf(contact.name, contact.number),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: return "${contact.name} का कॉन्टैक्ट नहीं मिला।"
        val deleted = resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), null, null)
        return if (deleted > 0) "${contact.name} का कॉन्टैक्ट डिलीट कर दिया।" else "कॉन्टैक्ट डिलीट नहीं हो पाया।"
    }

    private fun readScreen(): String {
        val service = SaarthiAccessibilityService.instance
            ?: return "पहले सेटिंग्स में Saarthi की एक्सेसिबिलिटी चालू कीजिए।"
        val lines = service.readScreen()
        return if (lines.isEmpty()) "स्क्रीन पर कुछ लिखा नहीं मिला।" else lines.joinToString(". ")
    }

    // Spoken relation words rarely match how the contact is saved, so try the common spellings.
    private val relationGroups = listOf(
        listOf("मम्मी", "माँ", "मां", "मॉम", "मम्मा", "अम्मी", "mummy", "mumma", "mom", "maa", "mother", "ammi", "amma"),
        listOf("पापा", "डैड", "पिताजी", "अब्बू", "papa", "dad", "father", "pitaji", "abbu"),
        listOf("भैया", "भाई", "bhaiya", "bhai", "brother"),
        listOf("दीदी", "didi", "sister"),
    )

    /** The saved contact that best matches a spoken name, in either script. */
    fun findContact(name: String): Contact? {
        if (name.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return null
        // The parser can take words before the name ("मेरे दोस्त राहुल"), so drop them one by one.
        val words = name.trim().lowercase().split(Regex("""\s+"""))
        return words.indices.firstNotNullOfOrNull { matchContact(words.drop(it).joinToString(" ")) }
    }

    private fun matchContact(spoken: String): Contact? {
        relationGroups.firstOrNull { spoken in it }?.forEach { candidate ->
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$candidate%"),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) return Contact(cursor.getString(0), cursor.getString(1)) }
        }
        // Prefer the closest name: "राहुल" picks "Rahul" over "Rahul Sharma".
        var best: Contact? = null
        var bestExtra = Int.MAX_VALUE
        context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val saved = cursor.getString(0) ?: continue
                    val extra = ContactNames.extraWords(spoken, saved) ?: continue
                    if (extra < bestExtra) {
                        best = Contact(saved, cursor.getString(1))
                        bestExtra = extra
                    }
                }
            }
        return best
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
