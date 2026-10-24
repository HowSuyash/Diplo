package com.dietcode.diplo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class MicMode { Mic, Send, Stop }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiploScreen(
    state: UiState,
    heard: String,
    spokenText: String?,
    spokenWord: IntRange?,
    level: Float,
    accessibilityOn: Boolean,
    language: AppLanguage = AppLanguage.Hindi,
    onLanguageChange: (AppLanguage) -> Unit = {},
    onMic: () -> Unit,
    onStop: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onSubmitText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onEnableAccessibility: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    var isTyping by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = { text: String ->
        onSubmitText(text)
        typed = ""
        isTyping = false
        keyboard?.hide()
        focus.clearFocus()
    }

    val speaking = spokenText != null
    val mood = when {
        state is UiState.Listening -> Mood.Listening
        state is UiState.Thinking -> Mood.Thinking
        state is UiState.Working -> Mood.Working
        speaking -> Mood.Speaking
        state is UiState.Confirm -> Mood.Confirm
        state is UiState.Done -> if (state.failed) Mood.Failed else Mood.Success
        else -> Mood.Idle
    }

    var wordPulses by remember { mutableIntStateOf(0) }
    LaunchedEffect(spokenWord) { if (spokenWord != null) wordPulses++ }

    val busy = speaking || state is UiState.Listening || state is UiState.Thinking ||
        state is UiState.Working || state is UiState.Confirm
    val imeOpen = WindowInsets.isImeVisible
    val orbSize by animateDpAsState(
        when {
            imeOpen || isTyping -> 160.dp
            mood == Mood.Idle || mood == Mood.Listening -> 270.dp
            else -> 230.dp
        },
        spring(dampingRatio = 0.65f, stiffness = 180f),
        label = "orbSize",
    )

    Box(Modifier.fillMaxSize().background(Palette.CeramicBg)) {
        Aurora(mood, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Minimalist Ceramic Top Bar (D I P L O  |  • तैयार  |  ≡)
            Header(mood = mood, language = language)
            Spacer(Modifier.height(8.dp))

            // Dynamic 3D Dotted Orb Hero
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(orbSize + 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Orb(
                    mood = mood,
                    level = level,
                    wordPulse = wordPulses,
                    modifier = Modifier.size(orbSize),
                )
            }

            // Central Content Hub
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(380)) + slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 6 })
                            .togetherWith(fadeOut(tween(160)))
                    },
                    label = "content",
                ) { shown ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (shown) {
                            UiState.Idle -> Greeting(language, onLanguageChange)
                            UiState.Listening -> Listening(language)
                            is UiState.Thinking -> {
                                Heard(shown.heard)
                                Spacer(Modifier.height(18.dp))
                                ThinkingDots()
                            }
                            is UiState.Confirm -> {
                                Heard(heard)
                                Spacer(Modifier.height(14.dp))
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .glass(24.dp)
                                        .padding(horizontal = 20.dp, vertical = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Palette.PillBg)
                                            .border(1.dp, Palette.PillBorder, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text("🛡️", fontSize = 11.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            Strings.confirmBadge(language),
                                            color = Palette.Charcoal,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                        )
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Spoken(shown.question, spokenText, spokenWord, Palette.Charcoal, size = 21.sp)
                                }
                                Spacer(Modifier.height(20.dp))
                                ConfirmButtons(onAnswer, language)
                            }
                            is UiState.Working -> {
                                Heard(heard)
                                Spacer(Modifier.height(16.dp))
                                Steps(shown.steps, language)
                                shown.watchUrl?.let { url ->
                                    Spacer(Modifier.height(14.dp))
                                    CeramicActionButton(Strings.watchBrowser(language), onClick = { onOpenUrl(url) })
                                }
                            }
                            is UiState.Done -> {
                                Heard(heard)
                                Spacer(Modifier.height(14.dp))
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .glass(22.dp)
                                        .padding(horizontal = 20.dp, vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (shown.failed) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Palette.AppleRed.copy(alpha = 0.12f))
                                                .border(1.dp, Palette.AppleRed.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        ) {
                                            Text("⚠️", fontSize = 11.sp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                Strings.noticeBadge(language),
                                                color = Palette.AppleRed,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp,
                                            )
                                        }
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    Spoken(
                                        shown.text,
                                        spokenText,
                                        spokenWord,
                                        if (shown.failed) Palette.AppleRed else Palette.Charcoal,
                                        size = 21.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Bottom Control Area (Keyboard icon, Recessed Matte Black Mic, Waveform)
            if (isTyping || typed.isNotEmpty()) {
                CeramicInputBar(
                    typed = typed,
                    onTyped = { typed = it },
                    mode = when {
                        typed.isNotBlank() -> MicMode.Send
                        state is UiState.Listening -> MicMode.Stop
                        else -> MicMode.Mic
                    },
                    level = level,
                    busy = busy,
                    language = language,
                    onCloseTyping = { isTyping = false },
                    onSend = { if (typed.isNotBlank()) submit(typed) },
                    onMic = { if (typed.isNotBlank()) submit(typed) else onMic() },
                    onStop = onStop,
                )
            } else {
                SculpturalBottomBar(
                    mode = when {
                        state is UiState.Listening -> MicMode.Stop
                        else -> MicMode.Mic
                    },
                    level = level,
                    busy = busy,
                    onOpenKeyboard = { isTyping = true },
                    onMic = onMic,
                    onStop = onStop,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Top Bar: D I P L O  |  • तैयार  |  ≡ */
@Composable
private fun Header(
    mood: Mood,
    language: AppLanguage,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // D I P L O brand title in clean tracked charcoal
        Text(
            "D I P L O",
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 6.0.sp,
            color = Palette.Charcoal,
        )
        Spacer(Modifier.weight(1f))

        // Status Pill: • तैयार
        StatusPill(mood, language)
        Spacer(Modifier.width(14.dp))

        // Minimalist Menu Button ≡
        MenuButton()
    }
}

/** Status Pill with dark dot and clean monolingual text. */
@Composable
private fun StatusPill(
    mood: Mood,
    language: AppLanguage,
) {
    val label = Strings.statusLabel(mood, language)

    val dotColor = when (mood) {
        Mood.Idle, Mood.Success -> Palette.Charcoal
        Mood.Listening, Mood.Working -> Palette.Charcoal
        Mood.Thinking -> Palette.Charcoal
        Mood.Speaking -> Palette.Charcoal
        Mood.Confirm -> Palette.AppleOrange
        Mood.Failed -> Palette.AppleRed
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = Palette.Charcoal,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** Minimalist 3 horizontal lines ≡ */
@Composable
private fun MenuButton() {
    Box(
        Modifier
            .size(36.dp)
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp, 12.dp)) {
            val stroke = 1.6.dp.toPx()
            val w = size.width
            val h = size.height
            drawLine(Palette.Charcoal, Offset(0f, 0f), Offset(w, 0f), stroke, StrokeCap.Round)
            drawLine(Palette.Charcoal, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), stroke, StrokeCap.Round)
            drawLine(Palette.Charcoal, Offset(0f, h), Offset(w, h), stroke, StrokeCap.Round)
        }
    }
}

/** Hero Typography: न म स् ते  &  आज मैं क्या मदद करूँ? */
@Composable
private fun Greeting(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    Text(
        Strings.greetingKicker(language), // "न म स् ते"
        color = Palette.CharcoalMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 4.sp,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        Strings.greetingHeadline(language), // "आज मैं क्या मदद करूँ?"
        fontSize = 28.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        color = Palette.Charcoal,
        letterSpacing = (-0.2).sp,
    )
    Spacer(Modifier.height(18.dp))

    // Interactive Minimalist Segmented Language Capsule
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, RoundedCornerShape(20.dp))
            .padding(3.dp),
    ) {
        AppLanguage.entries.forEach { langOption ->
            val selected = langOption == language
            val segModifier = if (selected) {
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .shadow(2.dp, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            } else {
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onLanguageChange(langOption) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            }
            Box(segModifier) {
                Text(
                    langOption.displayName,
                    color = if (selected) Palette.Charcoal else Palette.CharcoalMuted,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun Listening(language: AppLanguage) {
    Text(
        Strings.listeningTitle(language),
        color = Palette.Charcoal,
        fontSize = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.2).sp,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        Strings.listeningSubtitle(language),
        color = Palette.CharcoalMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Heard(text: String) {
    if (text.isBlank()) return
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "“$text”",
            color = Palette.Charcoal,
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Spoken(text: String, spokenText: String?, word: IntRange?, color: Color, size: TextUnit = 24.sp) {
    val live = spokenText == text && word != null && word.first in text.indices
    val styled = remember(text, live, word) {
        buildAnnotatedString {
            append(text)
            if (live && word != null) {
                val end = (word.last + 1).coerceAtMost(text.length)
                addStyle(SpanStyle(color = color.copy(alpha = 0.35f)), end, text.length)
                addStyle(
                    SpanStyle(
                        color = Palette.Charcoal,
                        fontWeight = FontWeight.Bold,
                    ),
                    word.first, end,
                )
            }
        }
    }
    Text(
        styled,
        color = color,
        fontSize = size,
        lineHeight = size * 1.48f,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        letterSpacing = 0.2.sp,
    )
}

@Composable
private fun ThinkingDots() {
    val wave = rememberInfiniteTransition(label = "dots")
    val t by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "t")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 3) {
            val p = ((t - i * 0.18f) % 1f + 1f) % 1f
            val lift = if (p < 0.5f) p * 2f else (1f - p) * 2f
            Box(
                Modifier
                    .size(9.dp)
                    .graphicsLayer { translationY = -lift * 10f; alpha = 0.35f + lift * 0.65f }
                    .clip(CircleShape)
                    .background(Palette.Charcoal),
            )
        }
    }
}

@Composable
private fun ConfirmButtons(onAnswer: (Boolean) -> Unit, language: AppLanguage) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pill(Strings.confirmNo(language), Modifier.weight(1f), filled = false) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onAnswer(false)
        }
        Pill(Strings.confirmYes(language), Modifier.weight(1f), filled = true) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAnswer(true)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(Strings.confirmHint(language), color = Palette.CharcoalMuted, fontSize = 13.sp)
}

@Composable
private fun Pill(label: String, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "press")
    val shape = RoundedCornerShape(28.dp)
    val base = modifier.defaultMinSize(minHeight = 54.dp).graphicsLayer { scaleX = scale; scaleY = scale }
    val styled = if (filled) {
        base.shadow(6.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x22000000))
            .clip(shape)
            .background(Palette.MicBlack)
    } else {
        base.clip(shape)
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, shape)
    }
    Box(
        styled.clickable(interaction, indication = null, onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) Color.White else Palette.Charcoal,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
        )
    }
}

@Composable
private fun Steps(steps: List<String>, language: AppLanguage) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(20.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Charcoal))
            Spacer(Modifier.width(8.dp))
            Text(
                Strings.executionBadge(language),
                color = Palette.Charcoal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (steps.isEmpty()) StepRow(Strings.startingExecution(language), current = true)
        val shown = steps.takeLast(5)
        val first = steps.size - shown.size
        shown.forEachIndexed { i, step -> key(first + i) { StepRow(step, current = i == shown.lastIndex) } }
    }
}

@Composable
private fun StepRow(text: String, current: Boolean) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 24f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepMark(current)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            color = if (current) Palette.Charcoal else Palette.CharcoalMuted,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepMark(current: Boolean) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Crossfade(current, label = "mark") { inProgress ->
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.8.dp.toPx()
            if (inProgress) {
                drawCircle(Palette.Charcoal.copy(alpha = 0.20f), style = Stroke(stroke))
                drawArc(
                    Palette.Charcoal,
                    angle, 270f, false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            } else {
                drawCircle(Palette.Mint.copy(alpha = 0.20f))
                val tick = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.45f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.36f)
                }
                drawPath(tick, Palette.Mint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun CeramicActionButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, color = Palette.Charcoal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The Exact Sculptural Bottom Bar from Reference Photo:
 * - Left: Keyboard Grid Glyph (:::)
 * - Center: Concentric Recessed Ring + 68dp Matte Black Circular Button with Crisp White Mic
 * - Right: 4-bar Audio Waveform Equalizer (||||)
 */
@Composable
private fun SculpturalBottomBar(
    mode: MicMode,
    level: Float,
    busy: Boolean,
    onOpenKeyboard: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
    val voice by animateFloatAsState(if (mode == MicMode.Stop) level else 0f, spring(stiffness = 300f), label = "voice")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: Keyboard Grid Icon (:::)
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenKeyboard),
            contentAlignment = Alignment.Center,
        ) {
            KeyboardGridGlyph(Modifier.size(24.dp), Palette.Charcoal)
        }

        // Center: Recessed Dial & Matte Black Mic Button
        Box(
            Modifier.size(92.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Concentric Hairline Outer Ring
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Palette.Charcoal.copy(alpha = 0.12f),
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(1.0.dp.toPx()),
                )
            }

            // Matte Black 68dp Button
            Box(
                Modifier
                    .size(68.dp)
                    .graphicsLayer {
                        scaleX = scale * (1f + voice * 0.08f)
                        scaleY = scaleX
                    }
                    .shadow(10.dp, CircleShape, ambientColor = Color(0x35000000), spotColor = Color(0x22000000))
                    .clip(CircleShape)
                    .background(Palette.MicBlack)
                    .clickable(interaction, indication = null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (mode == MicMode.Stop) onStop() else onMic()
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (mode == MicMode.Stop) "रुको" else "बोलिए"
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (mode == MicMode.Stop) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                    )
                } else {
                    MicOutlineGlyph(Modifier.size(26.dp), Color.White)
                }
            }
        }

        // Right: 4-bar Audio Waveform Equalizer (||||)
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onMic),
            contentAlignment = Alignment.Center,
        ) {
            WaveformEqualizerGlyph(Modifier.size(22.dp), Palette.Charcoal, level = level)
        }
    }
}

/**
 * Minimalist Ceramic Typing Dock (expands when keyboard icon is tapped).
 */
@Composable
private fun CeramicInputBar(
    typed: String,
    onTyped: (String) -> Unit,
    mode: MicMode,
    level: Float,
    busy: Boolean,
    language: AppLanguage,
    onCloseTyping: () -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, RoundedCornerShape(32.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tap keyboard glyph to close typing
        Box(
            Modifier
                .size(36.dp)
                .clickable(onClick = onCloseTyping),
            contentAlignment = Alignment.Center,
        ) {
            KeyboardGridGlyph(Modifier.size(20.dp), Palette.CharcoalMuted)
        }
        Spacer(Modifier.width(10.dp))

        BasicTextField(
            value = typed,
            onValueChange = onTyped,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Charcoal, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.Charcoal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box {
                    if (typed.isEmpty()) {
                        Text(
                            Strings.inputPlaceholder(language),
                            color = Palette.CharcoalMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                    field()
                }
            },
        )

        // Matte black send button
        Box(
            Modifier
                .size(46.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Palette.MicBlack)
                .clickable {
                    if (typed.isNotBlank()) onSend() else onMic()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (typed.isNotBlank()) {
                Canvas(Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val arrow = Path().apply {
                        moveTo(w * 0.15f, h * 0.5f)
                        lineTo(w * 0.85f, h * 0.5f)
                        moveTo(w * 0.55f, h * 0.2f)
                        lineTo(w * 0.85f, h * 0.5f)
                        lineTo(w * 0.55f, h * 0.8f)
                    }
                    drawPath(arrow, Color.White, style = stroke)
                }
            } else {
                MicOutlineGlyph(Modifier.size(20.dp), Color.White)
            }
        }
    }
}

/** Vector Keyboard Grid Glyph (:::) */
@Composable
private fun KeyboardGridGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Outer rounded frame
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.18f),
            size = Size(w * 0.84f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.16f),
            style = stroke,
        )

        // 6 keyboard dots
        val dotR = 1.0.dp.toPx()
        drawCircle(color, dotR, Offset(w * 0.32f, h * 0.40f))
        drawCircle(color, dotR, Offset(w * 0.50f, h * 0.40f))
        drawCircle(color, dotR, Offset(w * 0.68f, h * 0.40f))
        drawCircle(color, dotR, Offset(w * 0.32f, h * 0.60f))
        drawCircle(color, dotR, Offset(w * 0.50f, h * 0.60f))
        drawCircle(color, dotR, Offset(w * 0.68f, h * 0.60f))
    }
}

/** Vector Mic Outline Glyph matching reference photo */
@Composable
private fun MicOutlineGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Mic capsule
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.35f, h * 0.12f),
            size = Size(w * 0.30f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.15f),
            style = stroke,
        )

        // Arc cradle
        drawArc(
            color = color,
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.24f),
            size = Size(w * 0.56f, h * 0.48f),
            style = stroke,
        )

        // Center stem
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.72f),
            end = Offset(w * 0.5f, h * 0.88f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

/** Vector Waveform Equalizer Glyph (||||) matching reference photo */
@Composable
private fun WaveformEqualizerGlyph(modifier: Modifier, color: Color, level: Float = 0f) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        val xs = listOf(w * 0.18f, w * 0.39f, w * 0.61f, w * 0.82f)
        val baseH = listOf(h * 0.38f, h * 0.78f, h * 0.58f, h * 0.32f)

        xs.forEachIndexed { i, x ->
            val barH = (baseH[i] * (1f + level * 0.35f)).coerceAtMost(h * 0.95f)
            val y1 = (h - barH) / 2f
            val y2 = y1 + barH
            drawLine(color, Offset(x, y1), Offset(x, y2), stroke, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Composable
private fun DiploPreviewContainer(
    state: UiState,
    heard: String = "",
    spokenText: String? = null,
    spokenWord: IntRange? = null,
    level: Float = 0f,
    accessibilityOn: Boolean = true,
    language: AppLanguage = AppLanguage.Hindi,
) {
    DiploScreen(
        state = state,
        heard = heard,
        spokenText = spokenText,
        spokenWord = spokenWord,
        level = level,
        accessibilityOn = accessibilityOn,
        language = language,
        onLanguageChange = {},
        onMic = {},
        onStop = {},
        onAnswer = {},
        onSubmitText = {},
        onOpenUrl = {},
        onEnableAccessibility = {},
    )
}

@Preview(name = "1. Idle - Main Screen", showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
private fun PreviewIdle() {
    DiploPreviewContainer(state = UiState.Idle)
}
