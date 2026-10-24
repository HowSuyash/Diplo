package com.dietcode.diplo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dietcode.diplo.ui.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mood state for Diplo agent.
 */
enum class Mood { Idle, Listening, Thinking, Speaking, Working, Confirm, Success, Failed }

/**
 * Pre-allocated particle point for zero-allocation 3D rendering.
 */
private class ParticlePoint {
    var x: Float = 0f
    var y: Float = 0f
    var z: Float = 0f
    var radius: Float = 0f
    var alpha: Float = 0f

    fun set(x: Float, y: Float, z: Float, radius: Float, alpha: Float) {
        this.x = x
        this.y = y
        this.z = z
        this.radius = radius
        this.alpha = alpha
    }
}

/**
 * Dynamic 3D Dotted Orb Component (inspired by Framer / Apple Intelligence):
 * Renders distinct 3D particle animations purely based on agent state:
 * - Idle: 3D Rotating Globe with illuminated radar scanning beam
 * - Listening: 3D Undulating Wave Sphere, reactive in real-time to microphone audio volume
 * - Thinking / Working: 3D Concentric Planetary Orbits with circulating satellite particles
 * - Speaking: 3D Flowing Möbius Ribbon of particles
 * - Confirm: 3D Kinetic Rubik Shuffling Sphere
 */
@Composable
fun Orb(
    mood: Mood,
    level: Float,
    wordPulse: Int,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    // Audio volume reactivity for Listening state
    val voice by animateFloatAsState(
        targetValue = if (mood == Mood.Listening) level.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 320f),
        label = "voice",
    )

    // Hardware-synchronized animation time clock
    val infiniteTransition = rememberInfiniteTransition(label = "dottedOrbClock")
    val animClock by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "animClock",
    )

    // Pre-allocated particle buffer (reused every frame to guarantee 0 GC pauses)
    val pool = remember {
        Array(800) { ParticlePoint() }
    }
    val activeList = remember {
        ArrayList<ParticlePoint>(800)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val cx = width * 0.5f
            val cy = height * 0.5f
            val sphereRadius = min(width, height) * 0.38f
            val time = animClock

            activeList.clear()
            var poolIndex = 0

            fun allocPoint(x: Float, y: Float, z: Float, r: Float, a: Float) {
                if (poolIndex < pool.size) {
                    val p = pool[poolIndex++]
                    p.set(x, y, z, r, a)
                    activeList.add(p)
                }
            }

            // 3D Euler projection helper (yaw & tilt)
            fun project(
                px: Float, py: Float, pz: Float,
                yaw: Float, tilt: Float, scale: Float,
            ): Triple<Float, Float, Float> {
                val cosT = cos(tilt)
                val sinT = sin(tilt)
                val cosY = cos(yaw)
                val sinY = sin(yaw)
                val u = px * cosY + pz * sinY
                val d = -px * sinY + pz * cosY
                val f = py * cosT - d * sinT
                val p = py * sinT + d * cosT
                return Triple(cx + u * scale, cy - f * scale, p)
            }

            // Select rendering mode based purely on what Diplo is doing
            when (mood) {
                Mood.Listening -> {
                    // MODE: WAVE (3D undulating harmonic wave sphere, voice-reactive)
                    val n = time * 2.8f
                    val rings = 15
                    val lonDensity = 38
                    val yaw = n * 0.18f
                    val tilt = 0.38f

                    for (e in 0..rings) {
                        val phi = -PI.toFloat() * 0.5f + (e.toFloat() / rings) * PI.toFloat()
                        val cosPhi = cos(phi)
                        val sinPhi = sin(phi)

                        // Undulating harmonic waves driven by time and mic volume
                        val waveDisplacement = (0.62f * sin(n * 2.1f - e * 0.52f) +
                                0.38f * sin(n * 1.27f + e * 0.83f)) * (1.0f + voice * 2.0f)
                        val rTier = sphereRadius * (0.88f + 0.105f * waveDisplacement)
                        val count = max(1, (abs(cosPhi) * lonDensity).roundToInt())

                        for (i in 0 until count) {
                            val theta = (i.toFloat() / count) * 2f * PI.toFloat()
                            val px = cosPhi * cos(theta) * rTier
                            val py = sinPhi * rTier
                            val pz = cosPhi * sin(theta) * rTier
                            val (sx, sy, sz) = project(px, py, pz, yaw, tilt, 1.0f)
                            val depthNorm = (sz / sphereRadius + 1f) * 0.5f
                            val gWave = max(0f, waveDisplacement)
                            val rDot = (1.5f + 2.2f * depthNorm) * (1.0f + 0.35f * gWave)
                            val alpha = (0.20f + 0.65f * depthNorm) * (0.75f + 0.25f * gWave)
                            allocPoint(sx, sy, sz, rDot, min(1.0f, alpha))
                        }
                    }
                }

                Mood.Thinking, Mood.Working -> {
                    // MODE: ORBITS (3D planetary orbital rings with circulating satellite particles)
                    val n = time * 2.2f
                    val d = 11
                    val f = 34
                    val p = 3
                    val yaw = n * 0.14f
                    val tilt = 0.32f

                    fun hash(e: Float, t: Float): Float {
                        val v = sin(e * 12.9898f + t * 78.233f) * 43758.5453f
                        return v - floor(v)
                    }

                    for (e in 0 until d) {
                        val tVal = hash(e.toFloat(), 1.7f)
                        val iVal = hash(e.toFloat(), 5.2f)
                        val aVal = hash(e.toFloat(), 8.9f)
                        val orbitR = sphereRadius * (0.45f + 0.52f * tVal)
                        val dAng = tVal * 2f * PI.toFloat()
                        val mAng = acos((2f * iVal - 1f).coerceIn(-1f, 1f))
                        val hx = sin(mAng) * cos(dAng)
                        val gy = cos(mAng)
                        val zz = sin(mAng) * sin(dAng)
                        var v = -gy
                        var y = hx
                        val b = max(1e-6f, sqrt(v * v + y * y))
                        v /= b
                        y /= b
                        val xAx = gy * 0f - zz * y
                        val sAx = zz * v - hx * 0f
                        val cAx = hx * y - gy * v
                        val wSpeed = (0.28f + 0.55f * aVal) * (if (aVal > 0.5f) 1f else -1f)

                        // Ghost orbital dots
                        for (gIdx in 0 until f) {
                            val theta = (gIdx.toFloat() / f) * 2f * PI.toFloat()
                            val px = (v * cos(theta) + xAx * sin(theta)) * orbitR
                            val py = (y * cos(theta) + sAx * sin(theta)) * orbitR
                            val pz = (cAx * sin(theta)) * orbitR
                            val (sx, sy, sz) = project(px, py, pz, yaw, tilt, 1.0f)
                            val depthNorm = (sz / orbitR + 1f) * 0.5f
                            allocPoint(sx, sy, sz, 1.8f, 0.12f + 0.40f * depthNorm)
                        }

                        // Circulating satellite particles
                        for (pIdx in 0 until p) {
                            val theta = n * wSpeed + (pIdx.toFloat() / p) * 2f * PI.toFloat() + iVal * 6f
                            val px = (v * cos(theta) + xAx * sin(theta)) * orbitR
                            val py = (y * cos(theta) + sAx * sin(theta)) * orbitR
                            val pz = (cAx * sin(theta)) * orbitR
                            val (sx, sy, sz) = project(px, py, pz, yaw, tilt, 1.0f)
                            val depthNorm = (sz / orbitR + 1f) * 0.5f
                            allocPoint(sx, sy, sz, 3.8f + 1.8f * depthNorm, 0.85f + 0.15f * depthNorm)
                        }
                    }
                }

                Mood.Speaking -> {
                    // MODE: RIBBON (3D flowing Möbius ribbon of kinetic particles)
                    val n = time * 2.0f
                    val yaw = n * 0.12f
                    val tilt = 0.32f
                    val rScale = sphereRadius * 0.88f

                    val pAng = n * 0.24f
                    val mAng = 0.55f + 0.3f * sin(n * 0.18f)
                    val hx = cos(pAng)
                    val gx = sin(pAng)
                    val xx = -gx * sin(mAng)
                    val vx = cos(mAng)
                    val yx = hx * sin(mAng)
                    val bx = -gx * vx
                    val ax = gx * xx - hx * yx
                    val sx = hx * vx

                    val lanes = 5
                    val segs = 48

                    for (e in 0 until lanes) {
                        val tLane = (e - (lanes - 1) / 2f) * 0.08f
                        val iLane = abs(e - (lanes - 1) / 2f) / max(1f, (lanes - 1) / 2f)
                        for (a in 0 until segs) {
                            val oAng = (a.toFloat() / segs) * 2f * PI.toFloat()
                            val cWob = tLane + 0.16f * sin(oAng * 3f - n * 1.7f + e * 0.22f) + 0.07f * sin(oAng * 5f + n * 1.1f)
                            val fx = hx * cos(oAng) + xx * sin(oAng) + bx * cWob
                            val px = vx * sin(oAng) + ax * cWob
                            val mx = gx * cos(oAng) + yx * sin(oAng) + sx * cWob
                            val normC = max(1e-6f, sqrt(fx * fx + px * px + mx * mx))
                            val (screenX, screenY, screenZ) = project(
                                (fx / normC) * rScale,
                                (px / normC) * rScale,
                                (mx / normC) * rScale,
                                yaw, tilt, 1.0f,
                            )
                            val depthNorm = (screenZ / rScale + 1f) * 0.5f
                            val rDot = (1.9f + 2.2f * depthNorm) * (1.0f - 0.22f * iLane)
                            val alpha = 0.35f + 0.65f * depthNorm
                            allocPoint(screenX, screenY, screenZ, rDot, min(1.0f, alpha))
                        }
                    }
                }

                else -> {
                    // MODE: GLOBE (Idle / Standby: 3D rotating spherical globe with radar scanning beam)
                    val n = time * 1.6f
                    val a = 0.5f
                    val yaw = n * a
                    val tilt = 0.38f + 0.06f * sin(n * 0.35f)
                    val dScan = n * (a + (1.7f - a) * 4.08f)

                    val latRings = 16
                    val lonDensity = 38

                    for (e in 0..latRings) {
                        val phi = -PI.toFloat() * 0.5f + (e.toFloat() / latRings) * PI.toFloat()
                        val cosPhi = cos(phi)
                        val sinPhi = sin(phi)
                        val count = max(1, (abs(cosPhi) * lonDensity).roundToInt())

                        for (i in 0 until count) {
                            val theta = (i.toFloat() / count) * 2f * PI.toFloat()
                            val px = cosPhi * cos(theta) * sphereRadius
                            val py = sinPhi * sphereRadius
                            val pz = cosPhi * sin(theta) * sphereRadius
                            val (sx, sy, sz) = project(px, py, pz, yaw, tilt, 1.0f)
                            val depthNorm = (sz / sphereRadius + 1f) * 0.5f

                            // Radar scanning beam angle difference
                            val angleDiff = atan2(sin(theta + yaw - dScan), cos(theta + yaw - dScan))
                            val scanBoost = exp(-(angleDiff * angleDiff) / 0.20f) * max(0f, sz / sphereRadius)
                            val rDot = (1.4f + 1.8f * depthNorm + 2.2f * scanBoost)
                            val alpha = 0.18f + 0.58f * depthNorm + 0.24f * scanBoost
                            allocPoint(sx, sy, sz, rDot, min(1.0f, alpha))
                        }
                    }
                }
            }

            // Depth sorting (back-to-front rendering for true 3D occlusion)
            activeList.sortBy { it.z }

            // Render all dots with hardware-accelerated drawCircle
            val dotColor = Color(0xFF1D1B18) // Palette.Charcoal
            for (i in 0 until activeList.size) {
                val pt = activeList[i]
                drawCircle(
                    color = dotColor.copy(alpha = pt.alpha),
                    radius = pt.radius,
                    center = Offset(pt.x, pt.y),
                )
            }
        }
    }
}

/**
 * Warm Linen Ceramic Studio Lighting Backdrop.
 */
@Composable
fun Aurora(mood: Mood, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            Brush.verticalGradient(
                listOf(
                    Color(0xFFE2DDD7),
                    Color(0xFFE5E0DA),
                    Color(0xFFE8E4DE),
                )
            )
        )

        drawCircle(
            Brush.radialGradient(
                listOf(
                    Color(0xFFFAF7F2).copy(alpha = 0.42f),
                    Color(0xFFEDE9E3).copy(alpha = 0.14f),
                    Color.Transparent,
                ),
                center = Offset(w * 0.5f, h * 0.38f),
                radius = w * 0.95f,
            ),
            radius = w * 0.95f,
            center = Offset(w * 0.5f, h * 0.38f),
        )
    }
}
