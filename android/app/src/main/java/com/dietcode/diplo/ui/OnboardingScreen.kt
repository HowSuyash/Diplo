package com.dietcode.diplo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OnboardingScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    micGranted: Boolean,
    phoneGranted: Boolean,
    accessibilityOn: Boolean,
    onRequestMic: () -> Unit,
    onRequestPhone: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onComplete: () -> Unit,
) {
    var step by remember { mutableIntStateOf(1) }

    Box(Modifier.fillMaxSize().background(Palette.DarkEspresso)) {
        Aurora(Mood.Idle, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: Apple-style D I P L O brand & 4-step indicator
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "D I P L O",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = Palette.Ivory,
                    modifier = Modifier.paintWith(Palette.WarmHeadlineGradient),
                )

                // 4-step micro pill indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 1..4) {
                        val active = i == step
                        val passed = i < step
                        val color = if (active) Palette.GoldWire else if (passed) Palette.Mint else Color(0x2BFFFFFF)
                        Box(
                            Modifier
                                .size(if (active) 26.dp else 8.dp, 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Main step content with slide animations
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it } + fadeIn()).togetherWith(
                                slideOutHorizontally(tween(280)) { -it } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it } + fadeIn()).togetherWith(
                                slideOutHorizontally(tween(280)) { it } + fadeOut()
                            )
                        }
                    },
                    label = "onboarding_step",
                ) { currentStep ->
                    when (currentStep) {
                        1 -> LanguageSelectionStep(
                            language = language,
                            onSelect = onLanguageChange,
                        )
                        2 -> StepView(
                            iconType = StepIcon.Mic,
                            accentColor = Palette.Saffron,
                            tag = Strings.micStepTag(language),
                            title = Strings.micStepTitle(language),
                            subtitle = Strings.micStepSubtitle(language),
                            description = Strings.micStepDesc(language),
                            isGranted = micGranted,
                            grantedLabel = Strings.micStepGranted(language),
                            actionLabel = Strings.micStepAction(language),
                            onAction = onRequestMic,
                        )
                        3 -> StepView(
                            iconType = StepIcon.Phone,
                            accentColor = Palette.Violet,
                            tag = Strings.phoneStepTag(language),
                            title = Strings.phoneStepTitle(language),
                            subtitle = Strings.phoneStepSubtitle(language),
                            description = Strings.phoneStepDesc(language),
                            footerNote = Strings.phoneStepFooter(language),
                            isGranted = phoneGranted,
                            grantedLabel = Strings.phoneStepGranted(language),
                            actionLabel = Strings.phoneStepAction(language),
                            onAction = onRequestPhone,
                        )
                        4 -> StepView(
                            iconType = StepIcon.Accessibility,
                            accentColor = Palette.Cyan,
                            tag = Strings.a11yStepTag(language),
                            title = Strings.a11yStepTitle(language),
                            subtitle = Strings.a11yStepSubtitle(language),
                            description = Strings.a11yStepDesc(language),
                            footerNote = Strings.a11yStepFooter(language),
                            isGranted = accessibilityOn,
                            grantedLabel = Strings.a11yStepGranted(language),
                            actionLabel = Strings.a11yStepAction(language),
                            onAction = onOpenAccessibilitySettings,
                        )
                    }
                }
            }

            // Bottom Navigation Row
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 1) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x18FFFFFF))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
                            .clickable { step-- }
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                    ) {
                        Text(
                            Strings.back(language),
                            color = Palette.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onComplete() }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        Text(
                            Strings.skip(language),
                            color = Palette.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }

                if (step < 4) {
                    val canNext = (step == 1) || (step == 2 && micGranted) || (step == 3 && phoneGranted)
                    PrimaryButton(
                        text = Strings.next(language),
                        filled = canNext,
                        onClick = { step++ },
                    )
                } else {
                    PrimaryButton(
                        text = Strings.enterApp(language),
                        filled = true,
                        onClick = onComplete,
                    )
                }
            }
        }
    }
}

/** Step 1: Apple-style Dedicated Language Selection Page */
@Composable
private fun LanguageSelectionStep(
    language: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(32.dp)
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Tag badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.GoldWire.copy(alpha = 0.16f))
                .border(1.dp, Palette.GoldWire.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                Strings.langStepTag(language),
                color = Palette.GoldWire,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Miniature Celestial Pearl Node Hero
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                // Ambient glow
                drawCircle(
                    Brush.radialGradient(
                        listOf(Palette.Champagne.copy(alpha = 0.35f), Color.Transparent),
                        center = center,
                        radius = r * 1.5f,
                    ),
                    radius = r * 1.5f,
                )
                // Frosted base
                drawCircle(
                    Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))),
                    radius = r * 0.9f,
                )
                drawCircle(
                    Palette.GoldWire.copy(alpha = 0.5f),
                    radius = r * 0.9f,
                    style = Stroke(1.5.dp.toPx()),
                )
                // Pearl core
                drawCircle(
                    Brush.radialGradient(
                        listOf(Palette.PearlSunlight, Palette.PearlPeach, Palette.PearlLilac),
                        center = center - Offset(r * 0.2f, r * 0.2f),
                        radius = r * 0.6f,
                    ),
                    radius = r * 0.5f,
                )
                // Gold orbit ring
                drawArc(
                    Palette.GoldWire,
                    0f, 360f, false,
                    topLeft = Offset(center.x - r * 0.75f, center.y - r * 0.35f),
                    size = Size(r * 1.5f, r * 0.7f),
                    style = Stroke(1.2.dp.toPx()),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Title
        Text(
            Strings.langStepTitle(language),
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            Strings.langStepSubtitle(language),
            color = Palette.ChampagneSoft.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // 3 Apple Luxury Language Cards
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppLanguage.entries.forEach { langOption ->
                val selected = langOption == language
                LanguageCard(
                    option = langOption,
                    isSelected = selected,
                    onSelect = { onSelect(langOption) },
                )
            }
        }
    }
}

@Composable
private fun LanguageCard(
    option: AppLanguage,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 500f), label = "card_press")

    val shape = RoundedCornerShape(20.dp)
    val cardModifier = if (isSelected) {
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .shadow(4.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x14000000))
            .background(Color.White.copy(alpha = 0.90f))
            .border(1.5.dp, Palette.Charcoal, shape)
    } else {
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Color(0x0C000000))
            .border(1.dp, Color(0x18000000), shape)
    }

    Row(
        cardModifier
            .fillMaxWidth()
            .clickable(interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left radio indicator
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isSelected) Palette.Charcoal else Color(0x14000000))
                .border(1.dp, if (isSelected) Palette.Charcoal else Color(0x22000000), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                option.displayName,
                color = Palette.Charcoal,
                fontSize = 17.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                option.subtitle,
                color = Palette.CharcoalMuted,
                fontSize = 12.sp,
            )
        }

        // Preview Badge
        val previewWord = when (option) {
            AppLanguage.Hindi -> "नमस्ते"
            AppLanguage.English -> "Hello"
            AppLanguage.Hinglish -> "Namaste"
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) Palette.PillBg else Color(0x08000000))
                .border(1.dp, if (isSelected) Palette.PillBorder else Color(0x14000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                previewWord,
                color = Palette.Charcoal,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private enum class StepIcon { Mic, Phone, Accessibility }

@Composable
private fun StepView(
    iconType: StepIcon,
    accentColor: Color,
    tag: String,
    title: String,
    subtitle: String,
    description: String,
    footerNote: String? = null,
    isGranted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(32.dp)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Tag badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.16f))
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(tag, color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }

        Spacer(Modifier.height(20.dp))

        // Large Glowing Illustrated Canvas Icon
        Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(
                    Brush.radialGradient(
                        listOf(accentColor.copy(alpha = 0.35f), Color.Transparent),
                        center = center,
                        radius = r * 1.5f,
                    ),
                    radius = r * 1.5f,
                )
                drawCircle(
                    Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))),
                    radius = r,
                )
                drawCircle(
                    accentColor.copy(alpha = 0.4f),
                    radius = r,
                    style = Stroke(1.5.dp.toPx()),
                )
            }

            StepCanvasGlyph(iconType, accentColor, Modifier.size(42.dp))
        }

        Spacer(Modifier.height(20.dp))

        // Title
        Text(
            title,
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            color = Palette.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(14.dp))

        // Description
        Text(
            description,
            color = Palette.Text.copy(alpha = 0.88f),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )

        if (footerNote != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                footerNote,
                color = Palette.TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Status or Grant Button
        if (isGranted) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Mint.copy(alpha = 0.18f))
                    .border(1.dp, Palette.Mint.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓  $grantedLabel", color = Palette.Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(accentColor, accentColor.copy(alpha = 0.75f))))
                    .clickable(onClick = onAction)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepCanvasGlyph(type: StepIcon, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            StepIcon.Mic -> {
                drawRoundRect(Color.White, Offset(w * 0.34f, h * 0.06f), Size(w * 0.32f, h * 0.52f), CornerRadius(w * 0.16f))
                drawArc(Color.White, 20f, 140f, false, Offset(w * 0.2f, h * 0.18f), Size(w * 0.6f, h * 0.56f), style = stroke)
                drawLine(Color.White, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), stroke.width, StrokeCap.Round)
            }
            StepIcon.Phone -> {
                val phone = Path().apply {
                    moveTo(w * 0.3f, h * 0.15f)
                    lineTo(w * 0.42f, h * 0.15f)
                    lineTo(w * 0.48f, h * 0.32f)
                    lineTo(w * 0.4f, h * 0.4f)
                    cubicTo(w * 0.45f, h * 0.52f, w * 0.55f, h * 0.62f, w * 0.68f, h * 0.66f)
                    lineTo(w * 0.74f, h * 0.58f)
                    lineTo(w * 0.9f, h * 0.64f)
                    lineTo(w * 0.9f, h * 0.78f)
                    cubicTo(w * 0.9f, h * 0.86f, w * 0.22f, h * 0.86f, w * 0.22f, h * 0.22f)
                    close()
                }
                drawPath(phone, Color.White, style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            StepIcon.Accessibility -> {
                val shield = Path().apply {
                    moveTo(w * 0.5f, h * 0.1f)
                    lineTo(w * 0.82f, h * 0.24f)
                    lineTo(w * 0.82f, h * 0.55f)
                    cubicTo(w * 0.82f, h * 0.76f, w * 0.5f, h * 0.92f, w * 0.5f, h * 0.92f)
                    cubicTo(w * 0.5f, h * 0.92f, w * 0.18f, h * 0.76f, w * 0.18f, h * 0.55f)
                    lineTo(w * 0.18f, h * 0.24f)
                    close()
                }
                drawPath(shield, Color.White, style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(color, radius = w * 0.1f, center = center)
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 500f), label = "btn_press")

    val shape = RoundedCornerShape(22.dp)
    val modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)

    val styled = if (filled) {
        modifier
            .shadow(6.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x22000000))
            .background(Palette.MicBlack)
    } else {
        modifier
            .background(Palette.PillBg)
            .border(1.dp, Palette.PillBorder, shape)
    }

    Box(
        styled.clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) Color.White else Palette.Charcoal,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
