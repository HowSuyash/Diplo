package com.dietcode.diplo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Supported languages for pure monolingual experience (no bilingual slashes). */
enum class AppLanguage(val code: String, val displayName: String, val subtitle: String) {
    Hindi("hi", "हिंदी", "शुद्ध हिंदी में बातचीत करें"),
    English("en", "English", "Seamless English experience"),
    Hinglish("hinglish", "Hinglish", "Everyday mix of Hindi & English"),
}

/**
 * Warm Minimalist Ceramic Gallery Palette (Apple / Dieter Rams studio aesthetic).
 */
object Palette {
    // Warm Linen Ceramic Base
    val CeramicBg = Color(0xFFE4DFD8)
    val CeramicBgTop = Color(0xFFE1DCD5)
    val CeramicBgBot = Color(0xFFE8E4DD)
    val CeramicCanvas = Brush.verticalGradient(listOf(CeramicBgTop, CeramicBgBot))

    // Deep Charcoal / Onyx
    val Charcoal = Color(0xFF1D1B18)
    val CharcoalMuted = Color(0xFF6B6661)
    val CharcoalSubtle = Color(0xFF948E86)
    val Hairline = Color(0x24000000)
    val HairlineLight = Color(0x14000000)

    // Pill & Container
    val PillBg = Color(0x0E000000)
    val PillBorder = Color(0x1A000000)
    val CardBg = Color(0x0C000000)

    // Accent colors
    val DarkEspresso = CeramicBg
    val Night = CeramicBg
    val Deep = CeramicBg
    val Void = CeramicBg
    val GlassDark = Color(0x12000000)
    val Text = Charcoal
    val TextMuted = CharcoalMuted
    val TextTertiary = CharcoalSubtle
    val Ivory = Charcoal
    val ChampagneSoft = CharcoalMuted
    val GoldWire = Charcoal
    val Saffron = Charcoal
    val Mint = Color(0xFF2E7D32)
    val Danger = Color(0xFFC62828)
    val AppleGreen = Color(0xFF2E7D32)
    val AppleRed = Color(0xFFC62828)
    val AppleOrange = Color(0xFFE65100)
    val CoralRose = Charcoal
    val SunsetAmber = Charcoal
    val BronzeSilk = CharcoalMuted
    val Champagne = Charcoal
    val GoldBead = Charcoal
    val PearlSunlight = Color(0xFFF7F4EF)
    val PearlPeach = Color(0xFFEAE4DC)
    val PearlLilac = Color(0xFFDCD5CA)
    val PearlSkyBlue = Color(0xFFD4CDC1)
    val PearlViolet = Color(0xFF706A62)
    val PearlCore = Color(0xFFFAF7F2)

    // Buttons
    val MicBlack = Color(0xFF191817)
    val OuterDial = Color(0xFFD6D0C7)
    val SlateMuted = CharcoalMuted
    val Silver = CharcoalMuted
    val Gold = Charcoal
    val Amber = Charcoal
    val Rose = Charcoal
    val Cyan = Color(0xFF1565C0)
    val Violet = Color(0xFF4A148C)

    val MicGradient = Brush.linearGradient(listOf(MicBlack, MicBlack))
    val Warm = MicGradient
    val WarmHeadlineGradient = Brush.verticalGradient(listOf(Charcoal, Charcoal))
    val AppleGlassBorder = Brush.verticalGradient(
        listOf(
            Color(0x33000000),
            Color(0x14000000),
        )
    )
}

/**
 * Pure monolingual strings dictionary:
 * Guarantees zero side-by-side slashes throughout the application.
 */
object Strings {
    fun greetingKicker(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "न म स् ते"
        AppLanguage.English -> "W E L C O M E"
        AppLanguage.Hinglish -> "N A M A S T E"
    }

    fun greetingHeadline(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "आज मैं क्या मदद करूँ?"
        AppLanguage.English -> "How can I help you today?"
        AppLanguage.Hinglish -> "Aaj main kya help karoon?"
    }

    fun statusReady(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "तैयार"
        AppLanguage.English -> "Ready"
        AppLanguage.Hinglish -> "Ready"
    }

    fun statusListening(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "सुन रहा हूं"
        AppLanguage.English -> "Listening"
        AppLanguage.Hinglish -> "Sun raha hoon"
    }

    fun statusThinking(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "सोच रहा हूं"
        AppLanguage.English -> "Thinking"
        AppLanguage.Hinglish -> "Soch raha hoon"
    }

    fun statusSpeaking(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "बोल रहा हूं"
        AppLanguage.English -> "Speaking"
        AppLanguage.Hinglish -> "Bol raha hoon"
    }

    fun statusWorking(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "कार्य प्रगति"
        AppLanguage.English -> "Working"
        AppLanguage.Hinglish -> "Kaam chalu hai"
    }

    fun statusConfirm(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "पुष्टि"
        AppLanguage.English -> "Confirm"
        AppLanguage.Hinglish -> "Confirm"
    }

    fun statusSuccess(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "सफल"
        AppLanguage.English -> "Done"
        AppLanguage.Hinglish -> "Done"
    }

    fun statusFailed(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "दिक्कत"
        AppLanguage.English -> "Failed"
        AppLanguage.Hinglish -> "Problem aayi"
    }

    fun inputPlaceholder(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "कहिए या लिखिए..."
        AppLanguage.English -> "Speak or type..."
        AppLanguage.Hinglish -> "Boliye ya type kijiye..."
    }

    fun stop(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "रुको"
        AppLanguage.English -> "Stop"
        AppLanguage.Hinglish -> "Ruko"
    }

    fun confirmYes(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "हाँ"
        AppLanguage.English -> "Yes"
        AppLanguage.Hinglish -> "Haan"
    }

    fun confirmNo(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "नहीं"
        AppLanguage.English -> "No"
        AppLanguage.Hinglish -> "Nahi"
    }

    fun confirmHint(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "या बोलिए \"हाँ\" / \"नहीं\""
        AppLanguage.English -> "Or say \"Yes\" / \"No\""
        AppLanguage.Hinglish -> "Ya boliye \"Haan\" / \"Nahi\""
    }

    fun confirmBadge(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "पुष्टि आवश्यक"
        AppLanguage.English -> "CONFIRMATION NEEDED"
        AppLanguage.Hinglish -> "CONFIRMATION ZARURI"
    }

    fun listeningTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "बोलिए, मैं सुन रहा हूं…"
        AppLanguage.English -> "Go ahead, I'm listening…"
        AppLanguage.Hinglish -> "Boliye, main sun raha hoon…"
    }

    fun listeningSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "रुकने के लिए \"रुको\" बोलिए या माइक दोबारा दबाइए"
        AppLanguage.English -> "Say \"Stop\" or tap the microphone to pause"
        AppLanguage.Hinglish -> "Rukne ke liye \"Ruko\" boliye ya mic dabaiye"
    }

    fun executionBadge(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "कार्य प्रगति"
        AppLanguage.English -> "EXECUTION PIPELINE"
        AppLanguage.Hinglish -> "EXECUTION PIPELINE"
    }

    fun startingExecution(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "शुरू कर रहा हूं…"
        AppLanguage.English -> "Starting execution…"
        AppLanguage.Hinglish -> "Shuru kar raha hoon…"
    }

    fun watchBrowser(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "लाइव ब्राउज़र देखें ↗"
        AppLanguage.English -> "View Live Browser ↗"
        AppLanguage.Hinglish -> "Live Browser Dekhein ↗"
    }

    fun noticeBadge(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "सूचना"
        AppLanguage.English -> "NOTICE"
        AppLanguage.Hinglish -> "NOTICE"
    }

    fun statusLabel(mood: Mood, lang: AppLanguage): String = when (mood) {
        Mood.Idle -> statusReady(lang)
        Mood.Listening -> statusListening(lang)
        Mood.Thinking -> statusThinking(lang)
        Mood.Speaking -> statusSpeaking(lang)
        Mood.Working -> statusWorking(lang)
        Mood.Confirm -> statusConfirm(lang)
        Mood.Success -> statusSuccess(lang)
        Mood.Failed -> statusFailed(lang)
    }

    // Onboarding - Step 1: Language Selection
    fun langStepTag(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "भाषा"
        AppLanguage.English -> "LANGUAGE"
        AppLanguage.Hinglish -> "BHASHA"
    }

    fun langStepTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "अपनी भाषा चुनें"
        AppLanguage.English -> "Choose Your Language"
        AppLanguage.Hinglish -> "Apni Bhasha Chunein"
    }

    fun langStepSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "डिप्लो आपके साथ पूरी तरह इसी भाषा में बात करेगा"
        AppLanguage.English -> "Diplo will communicate completely in your chosen language"
        AppLanguage.Hinglish -> "Diplo aapse poori tarah is bhasha mein baat karega"
    }

    // Onboarding - Step 2: Microphone
    fun micStepTag(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "आवाज़"
        AppLanguage.English -> "VOICE"
        AppLanguage.Hinglish -> "VOICE"
    }

    fun micStepTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "आपकी आवाज़ सुनने के लिए"
        AppLanguage.English -> "To Listen to Your Voice"
        AppLanguage.Hinglish -> "Aapki Aawaaz Sunne Ke Liye"
    }

    fun micStepSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "माइक की अनुमति"
        AppLanguage.English -> "Microphone Permission"
        AppLanguage.Hinglish -> "Microphone Permission"
    }

    fun micStepDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "डिप्लो आपकी बात सुनकर फ़ोन और वेब पर काम करता है। बोलने के लिए माइक की अनुमति चालू करें।"
        AppLanguage.English -> "Diplo takes voice commands to control apps and web pages. Enable microphone to speak."
        AppLanguage.Hinglish -> "Diplo aapki baat sunkar phone aur web par kaam karta hai. Bolne ke liye mic allow karein."
    }

    fun micStepGranted(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "माइक्रोफ़ोन चालू है"
        AppLanguage.English -> "Microphone Enabled"
        AppLanguage.Hinglish -> "Microphone Chalu Hai"
    }

    fun micStepAction(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "माइक की अनुमति दें"
        AppLanguage.English -> "Allow Microphone"
        AppLanguage.Hinglish -> "Mic Permission Dein"
    }

    // Onboarding - Step 3: Phone & Contacts
    fun phoneStepTag(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "संपर्क"
        AppLanguage.English -> "CONTACTS"
        AppLanguage.Hinglish -> "CONTACTS"
    }

    fun phoneStepTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "कॉल और मैसेज के लिए"
        AppLanguage.English -> "For Calls and Messages"
        AppLanguage.Hinglish -> "Calls Aur Messages Ke Liye"
    }

    fun phoneStepSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "फ़ोन व कॉन्टैक्ट्स"
        AppLanguage.English -> "Phone & Contacts Access"
        AppLanguage.Hinglish -> "Phone & Contacts Access"
    }

    fun phoneStepDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "“माँ को कॉल मिलाओ” या “सुरेश को मैसेज भेजो” कहने पर सही व्यक्ति को ढूंढने और कॉल मिलाने के लिए अनुमति दें।"
        AppLanguage.English -> "Allows Diplo to find the right contact and dial calls when you say \"Call Mom\" or \"Message Suresh\"."
        AppLanguage.Hinglish -> "“Maa ko call milao” ya “Suresh ko message bhejo” bolne par contact dhoondhkar call milane ke liye allow karein."
    }

    fun phoneStepFooter(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "🔒 आपकी कॉन्टैक्ट लिस्ट आपके फ़ोन पर सुरक्षित रहती है।"
        AppLanguage.English -> "🔒 Your contacts stay strictly private on your phone."
        AppLanguage.Hinglish -> "🔒 Aapki contact list phone par hi secure rehti hai."
    }

    fun phoneStepGranted(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "फ़ोन व संपर्क चालू हैं"
        AppLanguage.English -> "Contacts Connected"
        AppLanguage.Hinglish -> "Contacts Connected Hain"
    }

    fun phoneStepAction(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "अनुमति दें"
        AppLanguage.English -> "Allow Access"
        AppLanguage.Hinglish -> "Allow Access"
    }

    // Onboarding - Step 4: Accessibility
    fun a11yStepTag(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "ऑटोमेशन"
        AppLanguage.English -> "AUTOMATION"
        AppLanguage.Hinglish -> "AUTOMATION"
    }

    fun a11yStepTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "स्क्रीन देखकर काम करने के लिए"
        AppLanguage.English -> "To Interact With Your Screen"
        AppLanguage.Hinglish -> "Screen Dekhkar Kaam Karne Ke Liye"
    }

    fun a11yStepSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "एक्सेसिबिलिटी सर्विस"
        AppLanguage.English -> "Accessibility Service"
        AppLanguage.Hinglish -> "Accessibility Service"
    }

    fun a11yStepDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "डिप्लो स्क्रीन को समझकर आपके कहे अनुसार बटन दबाता है (जैसे WhatsApp पर Send दबाना)। पासवर्ड और पिन कभी नहीं पढ़े जाते।"
        AppLanguage.English -> "Diplo understands your screen and taps buttons as requested (such as tapping Send in WhatsApp). Passwords and PINs are never read."
        AppLanguage.Hinglish -> "Diplo screen ko samajhkar aapke kahe mutabiq button dabata hai. Passwords aur PINs kabhi nahi padhe jaate."
    }

    fun a11yStepFooter(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "🛡️ हर संवेदनशील काम से पहले डिप्लो आपसे पुष्टि मांगेगा।"
        AppLanguage.English -> "🛡️ Diplo will always ask for your explicit confirmation before sensitive actions."
        AppLanguage.Hinglish -> "🛡️ Har sensitive action se pehle Diplo aapse confirm karega."
    }

    fun a11yStepGranted(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "एक्सेसिबिलिटी चालू है"
        AppLanguage.English -> "Accessibility Active"
        AppLanguage.Hinglish -> "Accessibility Chalu Hai"
    }

    fun a11yStepAction(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "सेटिंग्स में चालू करें ↗"
        AppLanguage.English -> "Enable in Settings ↗"
        AppLanguage.Hinglish -> "Settings Mein Chalu Karein ↗"
    }

    fun next(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "आगे बढ़ें →"
        AppLanguage.English -> "Continue →"
        AppLanguage.Hinglish -> "Aage badhein →"
    }

    fun back(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "← पीछे"
        AppLanguage.English -> "← Back"
        AppLanguage.Hinglish -> "← Peeche"
    }

    fun skip(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "छोड़ें"
        AppLanguage.English -> "Skip"
        AppLanguage.Hinglish -> "Skip"
    }

    fun enterApp(lang: AppLanguage) = when (lang) {
        AppLanguage.Hindi -> "डिप्लो शुरू करें ✨"
        AppLanguage.English -> "Enter Diplo ✨"
        AppLanguage.Hinglish -> "Diplo shuru karein ✨"
    }
}

/**
 * Warm minimalist frosted card.
 */
fun Modifier.glass(radius: Dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape)
        .background(Color(0x0C000000))
        .border(
            width = 1.dp,
            color = Color(0x1A000000),
            shape = shape,
        )
}

/**
 * Minimalist pebble container for status pills and menus.
 */
fun Modifier.applePill(radius: Dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape)
        .background(Palette.PillBg)
        .border(
            width = 1.dp,
            color = Palette.PillBorder,
            shape = shape,
        )
}

fun Modifier.chromaticGlass(radius: Dp, accentColor: Color = Palette.ChampagneSoft): Modifier = glass(radius)

fun Modifier.paintWith(brush: Brush): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(brush, blendMode = BlendMode.SrcIn)
        }
