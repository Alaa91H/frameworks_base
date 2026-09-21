/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.edgelight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen edge light renderer.
 *
 * The renderer intentionally keeps the location selection independent from the visual style:
 * top, sides and bottom can be enabled in any combination. Rounded style only changes the
 * geometry at corners; it does not force any zone on.
 */
class EdgeLightView(context: Context) : FrameLayout(context) {

    var userStrokeWidth: Int = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = baseStrokeWidth()
            invalidate()
        }

    var userSpread: Float = EDGE_LIGHT_DEFAULT_SPREAD
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var userIntensity: Float = EDGE_LIGHT_DEFAULT_INTENSITY
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var edgeStyle: String = STYLE_DEFAULT
        set(value) {
            field = value
            if (useRainbowGradient) updateRainbowGradient()
            invalidate()
        }

    var animationEffect: String = EFFECT_NONE
        set(value) {
            field = value
            invalidate()
        }

    var auroraColorMode: String = AURORA_SINGLE
        set(value) {
            field = if (value == AURORA_MULTI) AURORA_MULTI else AURORA_SINGLE
            invalidate()
        }

    var showTop: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showSides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showBottom: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var userPulseCount: Int = 3
        set(value) {
            field = value.coerceIn(1, 5)
        }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val totalPulseDuration =
        resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()

    private val fadeFraction = 0.2f
    private val minSegments = 3

    private var pulseAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var effectAnimator: ValueAnimator? = null
    private var rainbowRotation = 0f
    private var effectProgress = 0f

    private val sparkles = mutableListOf<Sparkle>()
    private var cornerRadius = 0f
    private var useRainbowGradient = false
    private var glowBaseColor = Color.WHITE

    var visible: Boolean
        get() = isVisible
        set(value) {
            isVisible = value
        }

    var paintColor: Int
        get() = edgePaint.color
        set(value) {
            if (value != COLOR_RAINBOW) {
                useRainbowGradient = false
                edgePaint.shader = null
                edgePaint.color = value
                edgePaint.alpha = 255
                glowBaseColor = resolveGlowBase(value)
            } else {
                useRainbowGradient = true
                glowBaseColor = RAINBOW[0]
                updateRainbowGradient()
            }
            invalidate()
        }

    var pulseRunning: Boolean
        get() = pulseAnimator?.isRunning == true
        set(value) {
            if (value && !pulseRunning) {
                startPulse()
                startEffectAnimation()
                startRainbowAnimation()
            } else if (!value) {
                stopRainbowAnimation()
                stopEffectAnimation()
                stopPulse()
                visible = false
            }
        }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visible = false

        cornerRadius = try {
            resources.getDimension(
                resources.getIdentifier("rounded_corner_radius", "dimen", "android")
            )
        } catch (_: Exception) {
            32f * resources.displayMetrics.density
        }

        edgePaint.strokeWidth = baseStrokeWidth()
    }

    override fun onDetachedFromWindow() {
        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (useRainbowGradient) updateRainbowGradient()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || !hasActiveZone()) return

        if (animationEffect != EFFECT_BREATHING) {
            edgePaint.strokeWidth = baseStrokeWidth()
        }

        val paths = buildActivePaths()
        if (paths.isEmpty()) return

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                drawPaths(canvas, paths, edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffect(canvas, paths)
            EFFECT_SPARKLE -> drawSparkleEffect(canvas, paths)
            EFFECT_CHASE -> drawChaseEffect(canvas, paths)
            EFFECT_COMET -> drawCometEffect(canvas, paths)
            EFFECT_AURORA -> drawAuroraEffect(canvas, paths)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                drawPaths(canvas, paths, edgePaint)
            }
        }

        // Aurora already has several luminous high-resolution layers, but the normal glow control
        // remains additive so the user's spread/intensity preferences still work.
        drawGlow(canvas)
    }

    private fun hasActiveZone(): Boolean = showTop || showSides || showBottom

    private fun baseStrokeWidth(): Float =
        userStrokeWidth * resources.displayMetrics.density

    /**
     * Builds independent paths for each selected zone. Keeping them independent makes moving
     * effects loop continuously inside the selected area instead of disappearing while an
     * animation travels through a disabled edge.
     */
    private fun buildActivePaths(): List<Path> {
        val strokeHalf = edgePaint.strokeWidth / 2f
        val left = strokeHalf
        val top = strokeHalf
        val right = width.toFloat() - strokeHalf
        val bottom = height.toFloat() - strokeHalf
        if (right <= left || bottom <= top) return emptyList()

        val rounded = isFrameStyle(edgeStyle)
        val radius = if (rounded) {
            cornerRadius.coerceAtMost((right - left) / 2f).coerceAtMost((bottom - top) / 2f)
        } else {
            0f
        }
        val paths = mutableListOf<Path>()

        if (showTop) {
            paths += Path().apply {
                moveTo(left + radius, top)
                lineTo(right - radius, top)
            }
        }

        if (showSides) {
            paths += Path().apply {
                moveTo(left, top + radius)
                lineTo(left, bottom - radius)
            }
            paths += Path().apply {
                moveTo(right, top + radius)
                lineTo(right, bottom - radius)
            }
        }

        if (showBottom) {
            paths += Path().apply {
                moveTo(left + radius, bottom)
                lineTo(right - radius, bottom)
            }
        }

        if (rounded && radius > 0f) {
            if (showTop && showSides) {
                paths += Path().apply {
                    arcTo(
                        RectF(left, top, left + radius * 2f, top + radius * 2f),
                        180f,
                        90f,
                        false,
                    )
                }
                paths += Path().apply {
                    arcTo(
                        RectF(right - radius * 2f, top, right, top + radius * 2f),
                        270f,
                        90f,
                        false,
                    )
                }
            }
            if (showBottom && showSides) {
                paths += Path().apply {
                    arcTo(
                        RectF(left, bottom - radius * 2f, left + radius * 2f, bottom),
                        90f,
                        90f,
                        false,
                    )
                }
                paths += Path().apply {
                    arcTo(
                        RectF(right - radius * 2f, bottom - radius * 2f, right, bottom),
                        0f,
                        90f,
                        false,
                    )
                }
            }
        }

        return paths
    }

    private fun drawPaths(canvas: Canvas, paths: List<Path>, paint: Paint) {
        paths.forEach { canvas.drawPath(it, paint) }
    }

    private fun applyBreathingEffect() {
        val normalized = (sin(effectProgress * 2.0 * PI).toFloat() + 1f) / 2f
        edgePaint.alpha = (155 + normalized * 100f).toInt().coerceIn(0, 255)
        edgePaint.strokeWidth = baseStrokeWidth() * (0.7f + normalized * 0.6f)
        edgePaint.maskFilter = null
    }

    private fun drawWaveEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 255
        edgePaint.maskFilter = null
        val amplitude = baseStrokeWidth() * 0.75f
        paths.forEachIndexed { index, path ->
            val wave = buildWavyPath(path, amplitude, effectProgress + index * 0.13f)
            canvas.drawPath(wave, edgePaint)
        }
    }

    private fun drawSparkleEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 90
        edgePaint.maskFilter = null
        drawPaths(canvas, paths, edgePaint)

        sparkles.removeAll { it.lifetime <= 0f }
        if (sparkles.size < 24 && Random.nextFloat() < 0.38f) {
            randomPointOnPaths(paths)?.let { (x, y) ->
                sparkles += Sparkle(
                    x = x,
                    y = y,
                    lifetime = 1f,
                    maxSize = baseStrokeWidth() * (1.5f + Random.nextFloat() * 2.5f),
                )
            }
        }

        val sparklePaint = Paint(edgePaint).apply {
            shader = null
            strokeCap = Paint.Cap.ROUND
        }
        sparkles.forEach { sparkle ->
            sparkle.lifetime -= 0.035f
            sparklePaint.alpha = (sparkle.lifetime * 255f).toInt().coerceIn(0, 255)
            sparklePaint.strokeWidth =
                sparkle.maxSize * sin(sparkle.lifetime.toDouble() * PI).toFloat()
            sparklePaint.color = effectiveGlowBaseColor()
            canvas.drawPoint(sparkle.x, sparkle.y, sparklePaint)
        }
    }

    private fun drawChaseEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 48
        edgePaint.maskFilter = null
        drawPaths(canvas, paths, edgePaint)

        paths.forEach { path ->
            val measure = PathMeasure(path, false)
            val length = measure.length
            if (length <= 0f) return@forEach
            val trail = length * 0.18f
            repeat(3) { i ->
                val center = ((effectProgress + i / 3f) % 1f) * length
                val start = (center - trail).coerceAtLeast(0f)
                val end = (center + trail).coerceAtMost(length)
                val segment = Path()
                measure.getSegment(start, end, segment, true)
                val p = Paint(edgePaint).apply {
                    alpha = 255
                    strokeWidth = baseStrokeWidth() * 1.15f
                }
                canvas.drawPath(segment, p)
            }
        }
    }

    private fun drawCometEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        drawPaths(canvas, paths, edgePaint)

        paths.forEach { path ->
            val measure = PathMeasure(path, false)
            val length = measure.length
            if (length <= 0f) return@forEach
            val head = effectProgress * length
            val trail = length * 0.30f
            val start = (head - trail).coerceAtLeast(0f)
            val segment = Path()
            measure.getSegment(start, head, segment, true)
            val p = Paint(edgePaint).apply {
                alpha = 255
                strokeWidth = baseStrokeWidth() * 1.55f
            }
            canvas.drawPath(segment, p)
        }
    }

    /**
     * High quality Aurora renderer. It uses densely sampled flowing geometry plus four luminous
     * layers. "single" follows the selected edge-light color; "multi" uses a smooth aurora palette.
     */
    private fun drawAuroraEffect(canvas: Canvas, paths: List<Path>) {
        val baseWidth = baseStrokeWidth()
        val multi = auroraColorMode == AURORA_MULTI

        paths.forEachIndexed { index, path ->
            val primaryWave = buildWavyPath(
                path,
                amplitude = baseWidth * 1.15f,
                phase = effectProgress + index * 0.17f,
                highQuality = true,
            )
            val secondaryWave = buildWavyPath(
                path,
                amplitude = baseWidth * 0.55f,
                phase = effectProgress * 0.72f + 0.33f + index * 0.11f,
                highQuality = true,
            )

            drawAuroraLayers(canvas, primaryWave, baseWidth, multi, 1f)
            drawAuroraLayers(canvas, secondaryWave, baseWidth * 0.72f, multi, 0.68f)
        }
    }

    private fun drawAuroraLayers(
        canvas: Canvas,
        path: Path,
        baseWidth: Float,
        multi: Boolean,
        alphaScale: Float,
    ) {
        val shader = if (multi) auroraShader() else null
        val color = effectiveGlowBaseColor()

        val widths = floatArrayOf(6.2f, 4.1f, 2.35f, 1.0f)
        val alphas = intArrayOf(22, 42, 92, 238)
        val blur = floatArrayOf(2.8f, 1.8f, 0.8f, 0f)

        for (i in widths.indices) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = baseWidth * widths[i]
                alpha = (alphas[i] * alphaScale).toInt().coerceIn(0, 255)
                if (shader != null) {
                    this.shader = shader
                } else {
                    this.color = color
                }
                if (blur[i] > 0f) {
                    maskFilter = BlurMaskFilter(
                        (baseWidth * blur[i]).coerceAtLeast(1f),
                        BlurMaskFilter.Blur.NORMAL,
                    )
                }
            }
            canvas.drawPath(path, p)
        }
    }

    private fun buildWavyPath(
        source: Path,
        amplitude: Float,
        phase: Float,
        highQuality: Boolean = false,
    ): Path {
        val measure = PathMeasure(source, false)
        val length = measure.length
        if (length <= 0f) return Path(source)

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val samples = if (highQuality) {
            max(160, (length / (density * 1.75f)).toInt()).coerceAtMost(480)
        } else {
            max(48, (length / (density * 5f)).toInt()).coerceAtMost(180)
        }
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val out = Path()

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            measure.getPosTan(length * t, pos, tan)
            val harmonic =
                sin((t * 3.0 + phase * 2.0) * PI).toFloat() * 0.72f +
                sin((t * 7.0 - phase * 1.35) * PI).toFloat() * 0.28f
            val offset = harmonic * amplitude
            val x = pos[0] - tan[1] * offset
            val y = pos[1] + tan[0] * offset
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        return out
    }

    private fun randomPointOnPaths(paths: List<Path>): Pair<Float, Float>? {
        val measured = paths.map { it to PathMeasure(it, false) }
            .filter { it.second.length > 0f }
        if (measured.isEmpty()) return null
        val total = measured.sumOf { it.second.length.toDouble() }.toFloat()
        var target = Random.nextFloat() * total
        for ((_, measure) in measured) {
            if (target <= measure.length) {
                val pos = FloatArray(2)
                measure.getPosTan(target, pos, null)
                return pos[0] to pos[1]
            }
            target -= measure.length
        }
        return null
    }

    private fun drawGlow(canvas: Canvas) {
        if (userIntensity <= 0f || userSpread <= 0f) return
        val base = effectiveGlowBaseColor() and 0x00FFFFFF
        val alpha = (alpha.coerceIn(0f, 1f) * userIntensity).coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val sideSpread = (width * userSpread).coerceAtMost(width * 0.5f)
        val verticalSpread = (height * userSpread).coerceAtMost(height * 0.35f)
        val colors = intArrayOf(
            colorWithAlpha(base, alpha),
            colorWithAlpha(base, alpha * 0.62f),
            colorWithAlpha(base, alpha * 0.28f),
            colorWithAlpha(base, alpha * 0.09f),
            colorWithAlpha(base, 0f),
        )
        val stops = floatArrayOf(0f, 0.12f, 0.34f, 0.68f, 1f)

        if (showSides && sideSpread > 0f) {
            glowPaint.shader = LinearGradient(
                0f, 0f, sideSpread, 0f, colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, sideSpread, height.toFloat(), glowPaint)

            glowPaint.shader = LinearGradient(
                width.toFloat(), 0f, width - sideSpread, 0f,
                colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(width - sideSpread, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }

        if (showTop && verticalSpread > 0f) {
            glowPaint.shader = LinearGradient(
                0f, 0f, 0f, verticalSpread, colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), verticalSpread, glowPaint)
        }

        if (showBottom && verticalSpread > 0f) {
            glowPaint.shader = LinearGradient(
                0f, height.toFloat(), 0f, height - verticalSpread,
                colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                0f, height - verticalSpread, width.toFloat(), height.toFloat(), glowPaint
            )
        }

        glowPaint.shader = null
    }

    private fun colorWithAlpha(baseColor: Int, alphaFloat: Float): Int {
        val a = (alphaFloat * 255f).toInt().coerceIn(0, 255)
        return (baseColor and 0x00FFFFFF) or (a shl 24)
    }

    private fun resolveGlowBase(color: Int): Int {
        if (color == Color.TRANSPARENT || color == 0) return Color.WHITE
        val opaque = color or 0xFF000000.toInt()
        return if (opaque == Color.BLACK) Color.WHITE else opaque
    }

    private fun rainbowGlowColor(): Int {
        val index = ((rainbowRotation / 360f) * (RAINBOW.size - 1)).toInt()
            .coerceIn(0, RAINBOW.size - 2)
        return RAINBOW[index]
    }

    private fun effectiveGlowBaseColor(): Int =
        if (useRainbowGradient) rainbowGlowColor() else glowBaseColor

    private fun auroraShader(): Shader {
        val matrix = Matrix().apply {
            postRotate(effectProgress * 360f, width / 2f, height / 2f)
        }
        return SweepGradient(width / 2f, height / 2f, AURORA_COLORS, null).apply {
            setLocalMatrix(matrix)
        }
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            post { applyRainbowGradient() }
        } else {
            applyRainbowGradient()
        }
    }

    private fun applyRainbowGradient() {
        if (width <= 0 || height <= 0) return
        edgePaint.shader =
            if (isFrameStyle(edgeStyle) || showTop || showBottom) {
                val matrix = Matrix().apply {
                    postRotate(rainbowRotation, width / 2f, height / 2f)
                }
                SweepGradient(width / 2f, height / 2f, RAINBOW, null).apply {
                    setLocalMatrix(matrix)
                }
            } else {
                val offset = (rainbowRotation / 360f) * height
                LinearGradient(
                    0f,
                    -offset,
                    0f,
                    height.toFloat() - offset,
                    RAINBOW,
                    null,
                    Shader.TileMode.REPEAT,
                )
            }
    }

    private fun startPulse() {
        visible = true
        alpha = 0f

        val totalSegments = max(userPulseCount, minSegments)
        val active = BooleanArray(totalSegments)
        for (i in 0 until userPulseCount) {
            val idx = (((i + 0.5f) * totalSegments) / userPulseCount).toInt()
                .coerceIn(0, totalSegments - 1)
            active[idx] = true
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, totalSegments.toFloat()).apply {
            duration = totalPulseDuration
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val segmentIndex = progress.toInt().coerceIn(0, totalSegments - 1)
                val fraction = progress - segmentIndex
                alpha = if (active[segmentIndex]) {
                    when {
                        fraction < fadeFraction -> fraction / fadeFraction
                        fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                        else -> 1f
                    }
                } else {
                    0f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visible = false
                    pulseAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    visible = false
                    pulseAnimator = null
                }
            })
            repeatCount = 0
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun startEffectAnimation() {
        if (animationEffect == EFFECT_NONE) return
        if (effectAnimator?.isRunning == true) return

        val duration = when (animationEffect) {
            EFFECT_BREATHING -> 3000L
            EFFECT_WAVE -> 2000L
            EFFECT_SPARKLE -> 100L
            EFFECT_CHASE -> 2500L
            EFFECT_COMET -> 2000L
            EFFECT_AURORA -> 4200L
            else -> 2000L
        }

        effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                effectProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    effectAnimator = null
                    effectProgress = 0f
                    sparkles.clear()
                }

                override fun onAnimationCancel(animation: Animator) {
                    effectAnimator = null
                    effectProgress = 0f
                    sparkles.clear()
                }
            })
            start()
        }
    }

    private fun stopEffectAnimation() {
        effectAnimator?.cancel()
        effectAnimator = null
        effectProgress = 0f
        sparkles.clear()
    }

    private fun startRainbowAnimation() {
        if (!useRainbowGradient || edgePaint.shader == null) return
        if (animationEffect in MOVING_EFFECTS) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                rainbowRotation = animator.animatedValue as Float
                glowBaseColor = rainbowGlowColor()
                applyRainbowGradient()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }

                override fun onAnimationCancel(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }
            })
            start()
        }
    }

    private fun stopRainbowAnimation() {
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        rainbowRotation = 0f
    }

    private data class Sparkle(
        val x: Float,
        val y: Float,
        var lifetime: Float,
        val maxSize: Float,
    )

    companion object {
        const val STYLE_DEFAULT = "default"
        const val STYLE_ROUNDED = "rounded"
        const val COLOR_RAINBOW = -1

        const val EFFECT_NONE = "none"
        const val EFFECT_BREATHING = "breathing"
        const val EFFECT_WAVE = "wave"
        const val EFFECT_SPARKLE = "sparkle"
        const val EFFECT_CHASE = "chase"
        const val EFFECT_COMET = "comet"
        const val EFFECT_AURORA = "aurora"

        const val AURORA_SINGLE = "single"
        const val AURORA_MULTI = "multi"

        private val MOVING_EFFECTS = arrayOf(
            EFFECT_WAVE,
            EFFECT_SPARKLE,
            EFFECT_CHASE,
            EFFECT_COMET,
            EFFECT_AURORA,
        )

        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFF7F00.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00C8FF.toInt(),
            0xFF4050FF.toInt(),
            0xFF9B32FF.toInt(),
            0xFFFF2DAA.toInt(),
            0xFFFF0000.toInt(),
        )

        // Aurora palette intentionally repeats the first color for seamless animated sweep.
        private val AURORA_COLORS = intArrayOf(
            0xFF2DFFDB.toInt(),
            0xFF22B8FF.toInt(),
            0xFF635BFF.toInt(),
            0xFFB64CFF.toInt(),
            0xFFFF4FD8.toInt(),
            0xFF6DFF9A.toInt(),
            0xFF2DFFDB.toInt(),
        )

        private fun isFrameStyle(style: String): Boolean {
            val s = style.trim()
            return s.equals("rounded", ignoreCase = true) ||
                s.equals("frame", ignoreCase = true)
        }
    }
}
