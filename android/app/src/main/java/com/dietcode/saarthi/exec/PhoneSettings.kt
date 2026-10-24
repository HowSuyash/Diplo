package com.dietcode.saarthi.exec

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings

/**
 * Changes phone settings by voice: brightness, volume, torch, Do Not Disturb, silent, text size.
 * Android 10+ doesn't let apps switch Wi-Fi, Bluetooth, mobile data or location, so for those
 * Saarthi opens the right settings panel.
 *
 * Brightness and text size need "Modify system settings", Do Not Disturb needs "Do Not Disturb
 * access"; without them Saarthi opens that permission page and says so. Over adb:
 *   adb shell appops set com.dietcode.saarthi WRITE_SETTINGS allow
 *   adb shell cmd notification allow_dnd com.dietcode.saarthi
 */
class PhoneSettings(private val context: Context) {

    /** [value]: up | down | auto | on | off | max | min | mute. Returns what Saarthi should say. */
    fun apply(setting: String, value: String): String = when (setting) {
        "brightness" -> brightness(value)
        "volume" -> volume(value)
        "torch" -> torch(value != "off")
        "dnd" -> doNotDisturb(value != "off")
        "silent" -> silent(value != "off")
        "font_size" -> fontSize(value)
        "wifi" -> panel(Settings.Panel.ACTION_WIFI, "वाई-फाई")
        "mobile_data" -> panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "इंटरनेट")
        "bluetooth" -> panel(Settings.ACTION_BLUETOOTH_SETTINGS, "ब्लूटूथ")
        "location" -> panel(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "लोकेशन")
        else -> "यह सेटिंग मैं अभी नहीं बदल सकता।"
    }

    private val resolver get() = context.contentResolver

    private fun brightness(value: String): String {
        if (!Settings.System.canWrite(context)) return askToModifySettings("ब्राइटनेस")
        if (value == "auto") {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "ऑटो ब्राइटनेस चालू कर दी। अब रोशनी अपने आप ठीक रहेगी।"
        }
        // 0–255. A quarter of the range per step is a change you can see.
        val now = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (value) {
            "up", "on" -> now + 64
            "down" -> now - 64
            "max" -> 255
            "min", "off" -> 0
            else -> value.toIntOrNull()?.let { it * 255 / 100 } ?: return "ब्राइटनेस कितनी करूं, समझ नहीं आया।"
        }.coerceIn(10, 255)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
        return when {
            target == 255 -> "ब्राइटनेस पूरी कर दी।"
            target > now -> "ब्राइटनेस बढ़ा दी।"
            else -> "ब्राइटनेस कम कर दी।"
        }
    }

    private fun volume(value: String): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val now = audio.getStreamVolume(stream)
        if (value == "mute" || value == "off") {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return "आवाज़ बंद कर दी।"
        }
        audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        val target = when (value) {
            "up", "on" -> now + (max + 3) / 4
            "down" -> now - (max + 3) / 4
            "max" -> max
            "min" -> 1
            else -> value.toIntOrNull()?.let { it * max / 100 } ?: return "आवाज़ कितनी करूं, समझ नहीं आया।"
        }.coerceIn(0, max)
        audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return when {
            target == max -> "आवाज़ पूरी कर दी।"
            target > now -> "आवाज़ बढ़ा दी।"
            else -> "आवाज़ कम कर दी।"
        }
    }

    private fun torch(on: Boolean): String {
        val camera = context.getSystemService(CameraManager::class.java)
        return try {
            val id = camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "इस फ़ोन में टॉर्च नहीं मिली।"
            camera.setTorchMode(id, on)
            if (on) "टॉर्च जला दी।" else "टॉर्च बंद कर दी।"
        } catch (e: CameraAccessException) {
            "टॉर्च अभी कैमरा इस्तेमाल होने की वजह से नहीं जल पाई।"
        }
    }

    private fun doNotDisturb(on: Boolean): String {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "पहले Saarthi को Do Not Disturb की अनुमति दीजिए, फिर दोबारा बोलिए।"
        }
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return if (on) "डू नॉट डिस्टर्ब चालू कर दिया। अब कोई आपको परेशान नहीं करेगा।" else "डू नॉट डिस्टर्ब बंद कर दिया।"
    }

    private fun silent(on: Boolean): String {
        val audio = context.getSystemService(AudioManager::class.java)
        return try {
            audio.ringerMode = if (on) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            if (on) "फ़ोन वाइब्रेट पर कर दिया।" else "रिंगटोन फिर से चालू कर दी।"
        } catch (e: SecurityException) {
            doNotDisturb(on) // some phones only allow this with Do Not Disturb access
        }
    }

    private fun fontSize(value: String): String {
        if (!Settings.System.canWrite(context)) return askToModifySettings("अक्षरों का साइज़")
        val now = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f)
        val target = when (value) {
            "up", "on" -> now + 0.15f
            "down" -> now - 0.15f
            "max" -> 1.6f
            "min" -> 0.85f
            "off", "auto" -> 1f
            else -> return "अक्षर कितने बड़े करूं, समझ नहीं आया।"
        }.coerceIn(0.85f, 1.6f)
        Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, target)
        return if (target > now) "अक्षर बड़े कर दिए।" else if (target < now) "अक्षर छोटे कर दिए।" else "अक्षर पहले से ही इतने हैं।"
    }

    private fun panel(action: String, name: String): String =
        if (start(Intent(action))) "$name की सेटिंग खोल दी है, यहां से चालू या बंद कर दीजिए।" else "$name की सेटिंग नहीं खुली।"

    private fun askToModifySettings(what: String): String {
        start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        return "$what बदलने के लिए Saarthi को सिस्टम सेटिंग बदलने की अनुमति दीजिए, फिर दोबारा बोलिए।"
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
