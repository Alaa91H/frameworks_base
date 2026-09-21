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
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import kotlin.random.Random
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.BlurMaskFilter

class EdgeLightView(context: Context) : FrameLayout(context) {

    var userStrokeWidth: Int = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = field * resources.displayMetrics.density
            invalidate()
        }

    var userSpread: Float = EDGE_LIGHT_DEFAULT_SPREAD
        set(value) {
            field = value.coerceIn(0.05f, 1f)
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

    var positionTop: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var positionSides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var positionBottom: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var auroraColorMode: String = AURORA_COLOR_SINGLE
        set(value) {
            field = if (value == AURORA_COLOR_MULTI) AURORA_COLOR_MULTI else AURORA_COLOR_SINGLE
            invalidate()
        }

    var animationEffect: String = EFFECT_NONE
        set(value) {
            field = value
            invalidate()
        }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    private val edgeClipPath = Path()

    private val totalPulseDuration =
        resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()

    var userPulseCount: Int = 3
        set(value) {
            field = value.coerceIn(1, 5)
        }

    private val fadeFraction = 0.2f
    private val MIN_SEGMENTS = 3

    private var pulseAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var effectAnimator: ValueAnimator? = null
    private var rainbowRotation: Float = 0f
    private var effectProgress: Float = 0f

    private val sparkles = mutableListOf<Sparkle>()

    private val roundedPath = Path()
    private val roundedRect = RectF()
    private var cornerRadius: Float = 0f
    private var pathLength: Float = 0f

    private var useRainbowGradient = false

    private var glowBaseColor: Int = Color.WHITE

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

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
        } catch (e: Exception) {
            32f * resources.displayMetrics.density
        }

        edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
    }

    private fun resolveGlowBase(color: Int): Int {
        if (color == Color.TRANSPARENT || color == 0) return Color.WHITE
        val opaque = color or 0xFF000000.toInt()
        return if (opaque == Color.BLACK) Color.WHITE else opaque
    }

    private fun startPulse() {
        visible = true
        alpha = 0f

        val totalSegments = maxOf(userPulseCount, MIN_SEGMENTS)
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

                if (active[segmentIndex]) {
                    alpha = when {
                        fraction < fadeFraction -> fraction / fadeFraction
                        fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                        else -> 1f
                    }
                } else {
                    alpha = 0f
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!positionTop && !positionSides && !positionBottom) return

        if (animationEffect == EFFECT_AURORA) {
            drawAurora(canvas)
            return
        }

        val save = canvas.save()
        clipToEnabledEdges(canvas)
        val needsFrameGeometry =
            edgeStyle == STYLE_ROUNDED && positionTop && positionSides && positionBottom
        if (needsFrameGeometry) {
            drawRoundedEdges(canvas)
            drawGlowRounded(canvas)
        } else {
            drawDefaultEdges(canvas)
            drawGlowDefault(canvas)
        }
        canvas.restoreToCount(save)
    }

    private fun clipToEnabledEdges(canvas: Canvas) {
        edgeClipPath.reset()

        val density = resources.displayMetrics.density
        val minBand = edgePaint.strokeWidth * 4f + 8f * density
        val horizontalBand = maxOf(minBand, height * maxOf(userSpread, 0.12f))
            .coerceAtMost(height.toFloat())
        val verticalBand = maxOf(minBand, width * maxOf(userSpread, 0.12f))
            .coerceAtMost(width.toFloat())

        if (positionTop) {
            edgeClipPath.addRect(
                0f, 0f, width.toFloat(), horizontalBand, Path.Direction.CW
            )
        }
        if (positionBottom) {
            edgeClipPath.addRect(
                0f, height - horizontalBand, width.toFloat(), height.toFloat(),
                Path.Direction.CW
            )
        }
        if (positionSides) {
            edgeClipPath.addRect(
                0f, 0f, verticalBand, height.toFloat(), Path.Direction.CW
            )
            edgeClipPath.addRect(
                width - verticalBand, 0f, width.toFloat(), height.toFloat(),
                Path.Direction.CW
            )
        }
        canvas.clipPath(edgeClipPath)
    }

    private fun buildGlowAlpha(baseAlpha: Float): FloatArray {
        val a = (baseAlpha * userIntensity).coerceIn(0f, 1f)
        return floatArrayOf(a, a * 0.65f, a * 0.28f, a * 0.08f, 0f)
    }

    private fun spreadStops(): FloatArray {
        val s = userSpread.coerceIn(0.05f, 1f)
        return floatArrayOf(0f, s * 0.10f, s * 0.30f, s * 0.60f, s)
    }

    private fun colorWithAlpha(baseColor: Int, alphaFloat: Float): Int {
        val a = (alphaFloat * 255f).toInt().coerceIn(0, 255)
        return (baseColor and 0x00FFFFFF) or (a shl 24)
    }

    private fun currentGlowAlpha(): Float = alpha.coerceIn(0f, 1f)

    private fun rainbowGlowColor(): Int {
        val index = ((rainbowRotation / 360f) * (RAINBOW.size - 1)).toInt()
            .coerceIn(0, RAINBOW.size - 2)
        return RAINBOW[index]
    }

    private fun effectiveGlowBaseColor(): Int =
        if (useRainbowGradient) rainbowGlowColor() else glowBaseColor

    private fun drawGlowDefault(canvas: Canvas) {
        if (userIntensity == 0f) return
        val base = effectiveGlowBaseColor() and 0x00FFFFFF
        val alphas = buildGlowAlpha(currentGlowAlpha())
        val stops = spreadStops()
        val spreadPxH = width * userSpread
        val spreadPxV = height * userSpread
        val buildColors = { a: FloatArray -> IntArray(5) { colorWithAlpha(base, a[it]) } }
        val revStops = FloatArray(5) { 1f - stops[4 - it] }

        if (positionSides) {
            glowPaint.shader = LinearGradient(
                0f, 0f, spreadPxH, 0f,
                buildColors(alphas), stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, spreadPxH, height.toFloat(), glowPaint)

            glowPaint.shader = LinearGradient(
                width - spreadPxH, 0f, width.toFloat(), 0f,
                buildColors(FloatArray(5) { alphas[4 - it] }),
                revStops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(width - spreadPxH, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }

        if (positionTop) {
            glowPaint.shader = LinearGradient(
                0f, 0f, 0f, spreadPxV,
                buildColors(alphas), stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), spreadPxV, glowPaint)
        }

        if (positionBottom) {
            glowPaint.shader = LinearGradient(
                0f, height - spreadPxV, 0f, height.toFloat(),
                buildColors(FloatArray(5) { alphas[4 - it] }),
                revStops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, height - spreadPxV, width.toFloat(), height.toFloat(), glowPaint)
        }
    }

    private fun drawGlowRounded(canvas: Canvas) {
        if (userIntensity == 0f) return
        val base = effectiveGlowBaseColor() and 0x00FFFFFF
        val alphas = buildGlowAlpha(currentGlowAlpha())
        val stops = spreadStops()
        val spreadPxH = width  * userSpread
        val spreadPxV = height * userSpread

        val buildColors = { a: FloatArray -> IntArray(5) { colorWithAlpha(base, a[it]) } }
        val revStops = FloatArray(5) { 1f - stops[4 - it] }

        glowPaint.shader = LinearGradient(0f, 0f, spreadPxH, 0f,
            buildColors(alphas), stops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, spreadPxH, height.toFloat(), glowPaint)

        glowPaint.shader = LinearGradient(width - spreadPxH, 0f, width.toFloat(), 0f,
            buildColors(FloatArray(5) { alphas[4 - it] }), revStops, Shader.TileMode.CLAMP)
        canvas.drawRect(width - spreadPxH, 0f, width.toFloat(), height.toFloat(), glowPaint)

        glowPaint.shader = LinearGradient(0f, 0f, 0f, spreadPxV,
            buildColors(alphas), stops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), spreadPxV, glowPaint)

        glowPaint.shader = LinearGradient(0f, height - spreadPxV, 0f, height.toFloat(),
            buildColors(FloatArray(5) { alphas[4 - it] }), revStops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height - spreadPxV, width.toFloat(), height.toFloat(), glowPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (useRainbowGradient) {
            updateRainbowGradient()
            invalidate()
        }
    }

    private fun drawDefaultEdges(canvas: Canvas) {
        edgePaint.strokeCap =
            if (edgeStyle == STYLE_ROUNDED) Paint.Cap.ROUND else Paint.Cap.BUTT

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                drawSelectedBaseLines(canvas, edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffect(canvas)
            EFFECT_SPARKLE -> drawSparkleEffect(canvas)
            EFFECT_CHASE -> drawChaseEffect(canvas)
            EFFECT_COMET -> drawCometEffect(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                drawSelectedBaseLines(canvas, edgePaint)
            }
        }
    }

    private fun drawSelectedBaseLines(canvas: Canvas, paint: Paint) {
        val halfStroke = paint.strokeWidth / 2f
        if (positionSides) {
            canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), paint)
            canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), paint)
        }
        if (positionTop) {
            canvas.drawLine(0f, halfStroke, width.toFloat(), halfStroke, paint)
        }
        if (positionBottom) {
            canvas.drawLine(0f, height - halfStroke, width.toFloat(), height - halfStroke, paint)
        }
    }

    private fun drawRoundedEdges(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.ROUND

        roundedRect.set(
            halfStroke,
            halfStroke,
            width.toFloat() - halfStroke,
            height.toFloat() - halfStroke
        )

        roundedPath.reset()
        val radius = if (edgeStyle == STYLE_ROUNDED) cornerRadius else 0f
        roundedPath.addRoundRect(
            roundedRect,
            radius,
            radius,
            Path.Direction.CW
        )

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                canvas.drawPath(roundedPath, edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffectRounded(canvas)
            EFFECT_SPARKLE -> drawSparkleEffectRounded(canvas)
            EFFECT_CHASE -> drawChaseEffectRounded(canvas)
            EFFECT_COMET -> drawCometEffectRounded(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                canvas.drawPath(roundedPath, edgePaint)
            }
        }
    }

    private fun applyBreathingEffect() {
        val cycle = sin(effectProgress * 2 * PI).toFloat()
        val normalizedCycle = (cycle + 1f) / 2f

        edgePaint.alpha = (155 + (normalizedCycle * 100)).toInt().coerceIn(0, 255)
        edgePaint.strokeWidth = (userStrokeWidth * resources.displayMetrics.density) * (0.7f + normalizedCycle * 0.6f)
        edgePaint.maskFilter = null
    }

    private fun drawWaveEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val segments = 50
        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        if (positionSides) {
            val waveWidth = width * 0.05f
            for (i in 0 until segments) {
                val y1 = (i.toFloat() / segments) * height
                val y2 = ((i + 1).toFloat() / segments) * height
                val offset1 =
                    sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 2 * PI)).toFloat() *
                        waveWidth
                val offset2 =
                    sin((effectProgress * 2 * PI + ((i + 1).toFloat() / segments) * 2 * PI)).toFloat() *
                        waveWidth
                canvas.drawLine(halfStroke + offset1, y1, halfStroke + offset2, y2, edgePaint)
                canvas.drawLine(
                    width - halfStroke + offset1, y1,
                    width - halfStroke + offset2, y2, edgePaint
                )
            }
        }

        if (positionTop || positionBottom) {
            val waveHeight = height * 0.025f
            for (i in 0 until segments) {
                val x1 = (i.toFloat() / segments) * width
                val x2 = ((i + 1).toFloat() / segments) * width
                val offset1 =
                    sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 2 * PI)).toFloat() *
                        waveHeight
                val offset2 =
                    sin((effectProgress * 2 * PI + ((i + 1).toFloat() / segments) * 2 * PI)).toFloat() *
                        waveHeight
                if (positionTop) {
                    canvas.drawLine(x1, halfStroke + offset1, x2, halfStroke + offset2, edgePaint)
                }
                if (positionBottom) {
                    canvas.drawLine(
                        x1, height - halfStroke + offset1,
                        x2, height - halfStroke + offset2, edgePaint
                    )
                }
            }
        }
    }

    private fun drawWaveEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length
        val segments = 100
        val waveAmplitude = edgePaint.strokeWidth * 0.5f

        val wavePath = Path()
        for (i in 0..segments) {
            val distance = (i.toFloat() / segments) * pathLength
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            measure.getPosTan(distance, pos, tan)

            val waveOffset = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 4 * PI)).toFloat() * waveAmplitude
            val normalX = -tan[1]
            val normalY = tan[0]

            val x = pos[0] + normalX * waveOffset
            val y = pos[1] + normalY * waveOffset

            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }

        canvas.drawPath(wavePath, edgePaint)
    }

    private fun drawSparkleEffect(canvas: Canvas) {
        edgePaint.alpha = 100
        edgePaint.maskFilter = null
        drawSelectedBaseLines(canvas, edgePaint)

        sparkles.removeAll { it.lifetime <= 0f }

        val activeEdges = mutableListOf<Int>()
        if (positionSides) {
            activeEdges += EDGE_LEFT
            activeEdges += EDGE_RIGHT
        }
        if (positionTop) activeEdges += EDGE_TOP
        if (positionBottom) activeEdges += EDGE_BOTTOM

        if (activeEdges.isNotEmpty() && sparkles.size < 18 && Random.nextFloat() < 0.3f) {
            val edge = activeEdges[Random.nextInt(activeEdges.size)]
            val halfStroke = edgePaint.strokeWidth / 2f
            val x: Float
            val y: Float
            when (edge) {
                EDGE_LEFT -> {
                    x = halfStroke
                    y = Random.nextFloat() * height
                }
                EDGE_RIGHT -> {
                    x = width - halfStroke
                    y = Random.nextFloat() * height
                }
                EDGE_TOP -> {
                    x = Random.nextFloat() * width
                    y = halfStroke
                }
                else -> {
                    x = Random.nextFloat() * width
                    y = height - halfStroke
                }
            }
            sparkles.add(
                Sparkle(
                    x = x,
                    y = y,
                    lifetime = 1f,
                    maxSize = edgePaint.strokeWidth * (2f + Random.nextFloat() * 2f)
                )
            )
        }

        val sparklePaint = Paint(edgePaint).apply { strokeCap = Paint.Cap.ROUND }
        sparkles.forEach { sparkle ->
            sparkle.lifetime -= 0.03f
            val alpha = (sparkle.lifetime * 255).toInt().coerceIn(0, 255)
            val size = sparkle.maxSize * sin(sparkle.lifetime * PI).toFloat()
            sparklePaint.alpha = alpha
            sparklePaint.strokeWidth = size
            canvas.drawPoint(sparkle.x, sparkle.y, sparklePaint)
        }
    }

    private fun drawSparkleEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 100
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length

        sparkles.removeAll { it.lifetime <= 0f }

        if (sparkles.size < 20 && Random.nextFloat() < 0.3f) {
            val distance = Random.nextFloat() * pathLength
            val pos = FloatArray(2)
            measure.getPosTan(distance, pos, null)

            sparkles.add(
                Sparkle(
                    x = pos[0],
                    y = pos[1],
                    lifetime = 1f,
                    maxSize = edgePaint.strokeWidth * (2f + Random.nextFloat() * 2f)
                )
            )
        }

        val sparklePaint = Paint(edgePaint).apply { strokeCap = Paint.Cap.ROUND }
        sparkles.forEach { sparkle ->
            sparkle.lifetime -= 0.03f
            val alpha = (sparkle.lifetime * 255).toInt().coerceIn(0, 255)
            val size = sparkle.maxSize * sin(sparkle.lifetime * PI).toFloat()

            sparklePaint.alpha = alpha
            sparklePaint.strokeWidth = size
            canvas.drawPoint(sparkle.x, sparkle.y, sparklePaint)
        }
    }

    private fun drawChaseEffect(canvas: Canvas) {
        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        drawSelectedBaseLines(canvas, edgePaint)

        val halfStroke = edgePaint.strokeWidth / 2f
        val numChasers = 3
        val baseColor = edgePaint.color
        val transparent = baseColor and 0x00FFFFFF

        if (positionSides) {
            val segmentLength = height * 0.15f
            for (i in 0 until numChasers) {
                val offset = (effectProgress + i.toFloat() / numChasers) % 1f
                val centerY = offset * height
                val gradient = LinearGradient(
                    0f, centerY - segmentLength,
                    0f, centerY + segmentLength,
                    intArrayOf(transparent, baseColor, transparent),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                val chaserPaint = Paint(edgePaint).apply {
                    shader = gradient
                    alpha = 255
                }
                val y1 = (centerY - segmentLength).coerceAtLeast(0f)
                val y2 = (centerY + segmentLength).coerceAtMost(height.toFloat())
                canvas.drawLine(halfStroke, y1, halfStroke, y2, chaserPaint)
                canvas.drawLine(width - halfStroke, y1, width - halfStroke, y2, chaserPaint)
            }
        }

        if (positionTop || positionBottom) {
            val segmentLength = width * 0.15f
            for (i in 0 until numChasers) {
                val offset = (effectProgress + i.toFloat() / numChasers) % 1f
                val centerX = offset * width
                val gradient = LinearGradient(
                    centerX - segmentLength, 0f,
                    centerX + segmentLength, 0f,
                    intArrayOf(transparent, baseColor, transparent),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                val chaserPaint = Paint(edgePaint).apply {
                    shader = gradient
                    alpha = 255
                }
                val x1 = (centerX - segmentLength).coerceAtLeast(0f)
                val x2 = (centerX + segmentLength).coerceAtMost(width.toFloat())
                if (positionTop) canvas.drawLine(x1, halfStroke, x2, halfStroke, chaserPaint)
                if (positionBottom) {
                    canvas.drawLine(
                        x1, height - halfStroke, x2, height - halfStroke, chaserPaint
                    )
                }
            }
        }
    }

    private fun drawChaseEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length
        val segmentLength = pathLength * 0.15f
        val numChasers = 3

        for (i in 0 until numChasers) {
            val offset = (effectProgress + i.toFloat() / numChasers) % 1f
            val centerDistance = offset * pathLength

            val chaserPath = Path()
            val startDist = (centerDistance - segmentLength).coerceAtLeast(0f)
            val endDist = (centerDistance + segmentLength).coerceAtMost(pathLength)

            measure.getSegment(startDist, endDist, chaserPath, true)

            val chaserPaint = Paint(edgePaint).apply { alpha = 255 }
            canvas.drawPath(chaserPath, chaserPaint)
        }
    }

    private fun drawCometEffect(canvas: Canvas) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        drawSelectedBaseLines(canvas, edgePaint)

        val halfStroke = edgePaint.strokeWidth / 2f
        val baseColor = edgePaint.color
        val transparent = baseColor and 0x00FFFFFF

        if (positionSides) {
            val tailLength = height * 0.25f
            val cometY = effectProgress * height
            val start = (cometY - tailLength).coerceAtLeast(0f)
            val gradient = LinearGradient(
                0f, start, 0f, cometY,
                intArrayOf(transparent, baseColor),
                null,
                Shader.TileMode.CLAMP
            )
            val cometPaint = Paint(edgePaint).apply {
                shader = gradient
                alpha = 255
                strokeWidth = edgePaint.strokeWidth * 1.5f
            }
            canvas.drawLine(halfStroke, start, halfStroke, cometY, cometPaint)
            canvas.drawLine(width - halfStroke, start, width - halfStroke, cometY, cometPaint)
        }

        if (positionTop || positionBottom) {
            val tailLength = width * 0.25f
            val cometX = effectProgress * width
            val start = (cometX - tailLength).coerceAtLeast(0f)
            val gradient = LinearGradient(
                start, 0f, cometX, 0f,
                intArrayOf(transparent, baseColor),
                null,
                Shader.TileMode.CLAMP
            )
            val cometPaint = Paint(edgePaint).apply {
                shader = gradient
                alpha = 255
                strokeWidth = edgePaint.strokeWidth * 1.5f
            }
            if (positionTop) canvas.drawLine(start, halfStroke, cometX, halfStroke, cometPaint)
            if (positionBottom) {
                canvas.drawLine(
                    start, height - halfStroke, cometX, height - halfStroke, cometPaint
                )
            }
        }
    }

    private fun drawCometEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length
        val tailLength = pathLength * 0.2f
        val cometDistance = effectProgress * pathLength

        val cometPath = Path()
        val startDist = (cometDistance - tailLength).coerceAtLeast(0f)
        measure.getSegment(startDist, cometDistance, cometPath, true)

        val cometPaint = Paint(edgePaint).apply {
            alpha = 255
            strokeWidth = edgePaint.strokeWidth * 1.5f
        }
        canvas.drawPath(cometPath, cometPaint)
    }

    private fun drawAurora(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val baseStroke = userStrokeWidth * density
        val strength = (0.35f + userIntensity * 0.65f).coerceIn(0.35f, 1f)
        val sideSpread = maxOf(baseStroke * 3f, width * (0.06f + userSpread * 0.24f))
            .coerceAtMost(width * 0.55f)
        val horizontalSpread =
            maxOf(baseStroke * 3f, height * (0.035f + userSpread * 0.13f))
                .coerceAtMost(height * 0.32f)

        if (positionSides) {
            drawAuroraVerticalBand(canvas, true, sideSpread, strength)
            drawAuroraVerticalBand(canvas, false, sideSpread, strength)
        }
        if (positionTop) {
            drawAuroraHorizontalBand(canvas, true, horizontalSpread, strength)
        }
        if (positionBottom) {
            drawAuroraHorizontalBand(canvas, false, horizontalSpread, strength)
        }

        drawAuroraCore(canvas, baseStroke)
    }

    private fun drawAuroraVerticalBand(
        canvas: Canvas,
        left: Boolean,
        spread: Float,
        strength: Float,
    ) {
        val colorShader = auroraLongitudinalShader(
            horizontal = false,
            length = height.toFloat(),
            phase = effectProgress,
        )
        val fadeColors = if (left) {
            intArrayOf(
                alphaColor(Color.WHITE, strength),
                alphaColor(Color.WHITE, strength * 0.72f),
                alphaColor(Color.WHITE, strength * 0.34f),
                Color.TRANSPARENT,
            )
        } else {
            intArrayOf(
                Color.TRANSPARENT,
                alphaColor(Color.WHITE, strength * 0.34f),
                alphaColor(Color.WHITE, strength * 0.72f),
                alphaColor(Color.WHITE, strength),
            )
        }
        val fadeShader = LinearGradient(
            if (left) 0f else width - spread,
            0f,
            if (left) spread else width.toFloat(),
            0f,
            fadeColors,
            AURORA_FADE_STOPS,
            Shader.TileMode.CLAMP,
        )
        auroraPaint.shader = ComposeShader(colorShader, fadeShader, PorterDuff.Mode.SRC_IN)
        if (left) {
            canvas.drawRect(0f, 0f, spread, height.toFloat(), auroraPaint)
        } else {
            canvas.drawRect(width - spread, 0f, width.toFloat(), height.toFloat(), auroraPaint)
        }
    }

    private fun drawAuroraHorizontalBand(
        canvas: Canvas,
        top: Boolean,
        spread: Float,
        strength: Float,
    ) {
        val colorShader = auroraLongitudinalShader(
            horizontal = true,
            length = width.toFloat(),
            phase = effectProgress,
        )
        val fadeColors = if (top) {
            intArrayOf(
                alphaColor(Color.WHITE, strength),
                alphaColor(Color.WHITE, strength * 0.72f),
                alphaColor(Color.WHITE, strength * 0.34f),
                Color.TRANSPARENT,
            )
        } else {
            intArrayOf(
                Color.TRANSPARENT,
                alphaColor(Color.WHITE, strength * 0.34f),
                alphaColor(Color.WHITE, strength * 0.72f),
                alphaColor(Color.WHITE, strength),
            )
        }
        val fadeShader = LinearGradient(
            0f,
            if (top) 0f else height - spread,
            0f,
            if (top) spread else height.toFloat(),
            fadeColors,
            AURORA_FADE_STOPS,
            Shader.TileMode.CLAMP,
        )
        auroraPaint.shader = ComposeShader(colorShader, fadeShader, PorterDuff.Mode.SRC_IN)
        if (top) {
            canvas.drawRect(0f, 0f, width.toFloat(), spread, auroraPaint)
        } else {
            canvas.drawRect(0f, height - spread, width.toFloat(), height.toFloat(), auroraPaint)
        }
    }

    private fun drawAuroraCore(canvas: Canvas, baseStroke: Float) {
        val core = Paint(edgePaint).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = maxOf(1f, baseStroke * 0.55f)
            alpha = 235
            shader = auroraLongitudinalShader(
                horizontal = false,
                length = height.toFloat(),
                phase = effectProgress,
            )
        }
        val half = core.strokeWidth / 2f
        if (positionSides) {
            canvas.drawLine(half, 0f, half, height.toFloat(), core)
            canvas.drawLine(width - half, 0f, width - half, height.toFloat(), core)
        }
        if (positionTop) {
            core.shader = auroraLongitudinalShader(
                horizontal = true,
                length = width.toFloat(),
                phase = effectProgress,
            )
            canvas.drawLine(0f, half, width.toFloat(), half, core)
        }
        if (positionBottom) {
            core.shader = auroraLongitudinalShader(
                horizontal = true,
                length = width.toFloat(),
                phase = (effectProgress + 0.35f) % 1f,
            )
            canvas.drawLine(0f, height - half, width.toFloat(), height - half, core)
        }
    }

    private fun auroraLongitudinalShader(
        horizontal: Boolean,
        length: Float,
        phase: Float,
    ): Shader {
        val safeLength = length.coerceAtLeast(1f)
        val colors = if (auroraColorMode == AURORA_COLOR_MULTI) {
            AURORA_MULTI
        } else {
            val base = effectiveGlowBaseColor()
            intArrayOf(
                alphaColor(base, 0.45f),
                alphaColor(base, 0.95f),
                alphaColor(base, 0.62f),
                alphaColor(base, 1f),
                alphaColor(base, 0.45f),
            )
        }
        val positions = if (auroraColorMode == AURORA_COLOR_MULTI) {
            AURORA_MULTI_STOPS
        } else {
            AURORA_SINGLE_STOPS
        }
        val shader = if (horizontal) {
            LinearGradient(
                -safeLength,
                0f,
                safeLength,
                0f,
                colors,
                positions,
                Shader.TileMode.MIRROR,
            )
        } else {
            LinearGradient(
                0f,
                -safeLength,
                0f,
                safeLength,
                colors,
                positions,
                Shader.TileMode.MIRROR,
            )
        }
        val matrix = Matrix()
        val offset = safeLength * 2f * phase
        if (horizontal) matrix.setTranslate(offset, 0f) else matrix.setTranslate(0f, offset)
        shader.setLocalMatrix(matrix)
        return shader
    }

    private fun alphaColor(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            post { _updateRainbowGradient() }
        } else {
            _updateRainbowGradient()
        }
    }

    private fun _updateRainbowGradient() {
        edgePaint.shader = when (edgeStyle) {
            STYLE_ROUNDED -> {
                val matrix = Matrix()
                matrix.postRotate(rainbowRotation, width / 2f, height / 2f)
                SweepGradient(width / 2f, height / 2f, RAINBOW, null).also {
                    it.setLocalMatrix(matrix)
                }
            }
            else -> {
                val offset = (rainbowRotation / 360f) * height
                LinearGradient(
                    0f, -offset, 0f, height.toFloat() - offset,
                    RAINBOW, null, Shader.TileMode.REPEAT
                )
            }
        }
    }

    private fun startRainbowAnimation() {
        if (!useRainbowGradient || edgePaint.shader == null) return
        if (animationEffect in MOVING_EFFECT) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                rainbowRotation = animator.animatedValue as Float
                glowBaseColor = rainbowGlowColor()
                _updateRainbowGradient()
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

    private fun startEffectAnimation() {
        if (animationEffect == EFFECT_NONE) return
        if (effectAnimator?.isRunning == true) return

        val duration = when (animationEffect) {
            EFFECT_BREATHING -> 3000L
            EFFECT_WAVE -> 2000L
            EFFECT_SPARKLE -> 100L
            EFFECT_CHASE -> 2500L
            EFFECT_COMET -> 2000L
            EFFECT_AURORA -> 6500L
            else -> 2000L
        }

        effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
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

    private data class Sparkle(
        var x: Float,
        var y: Float,
        var lifetime: Float,
        val maxSize: Float
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
        const val AURORA_COLOR_SINGLE = "single"
        const val AURORA_COLOR_MULTI = "multicolor"
        private val MOVING_EFFECT =
            arrayOf(EFFECT_WAVE, EFFECT_SPARKLE, EFFECT_CHASE, EFFECT_COMET, EFFECT_AURORA)
        private val AURORA_FADE_STOPS = floatArrayOf(0f, 0.22f, 0.58f, 1f)
        private val AURORA_SINGLE_STOPS = floatArrayOf(0f, 0.23f, 0.5f, 0.77f, 1f)
        private val AURORA_MULTI_STOPS =
            floatArrayOf(0f, 0.12f, 0.26f, 0.42f, 0.58f, 0.74f, 0.88f, 1f)
        private const val EDGE_LEFT = 0
        private const val EDGE_RIGHT = 1
        private const val EDGE_TOP = 2
        private const val EDGE_BOTTOM = 3
        private val AURORA_MULTI = intArrayOf(
            0xFF00E5FF.toInt(),
            0xFF00FFB3.toInt(),
            0xFF4D7CFF.toInt(),
            0xFF7C4DFF.toInt(),
            0xFFFF4FD8.toInt(),
            0xFF9C6BFF.toInt(),
            0xFF00D9FF.toInt(),
            0xFF00E5FF.toInt(),
        )
        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(),
            0xFF9400D3.toInt(), 0xFFFF0000.toInt()
        )
    }
}
