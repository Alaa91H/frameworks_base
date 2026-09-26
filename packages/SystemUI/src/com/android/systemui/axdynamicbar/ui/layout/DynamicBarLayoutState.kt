/*
 * Copyright (C) 2026 Evolution X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.axdynamicbar.ui.layout

import android.graphics.RectF
import com.android.systemui.cutoutprogress.ring.CameraCutoutGeometryResolver
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class DynamicBarLayoutMode {
    CUTOUT,
    CENTERED,
    COMPACT,
}

data class DynamicBarLayoutState(
    val mode: DynamicBarLayoutMode,
    val cutoutBounds: RectF,
    val islandBounds: RectF,
    val leftWingWidthPx: Float,
    val rightWingWidthPx: Float,
    val cameraSlotWidthPx: Float,
    val centerXFraction: Float,
    val anchorBottomPx: Int,
    val hasPhysicalCutout: Boolean,
    val pillLike: Boolean,
    val rotation: Int,
)

object DynamicBarLayoutCalculator {
    const val ALIGNMENT_AUTO = 0
    const val ALIGNMENT_CENTER = 1

    const val SIZE_AUTO = 0
    const val SIZE_COMPACT = 1
    const val SIZE_LARGE = 2

    const val LANDSCAPE_AUTO = 0
    const val LANDSCAPE_ALWAYS = 1
    const val LANDSCAPE_COMPACT = 2
    const val LANDSCAPE_DISABLED = 3

    fun calculate(
        displayWidthPx: Int,
        displayHeightPx: Int,
        statusBarHeightPx: Int,
        density: Float,
        geometry: CameraCutoutGeometryResolver.ResolvedGeometry?,
        alignment: Int,
        islandSize: Int,
        landscapeMode: Int,
        occupiedBounds: List<RectF> = emptyList(),
    ): DynamicBarLayoutState? {
        if (displayWidthPx <= 0 || displayHeightPx <= 0 || density <= 0f) return null

        val isLandscape = displayWidthPx > displayHeightPx
        if (isLandscape && landscapeMode == LANDSCAPE_DISABLED) return null

        val physicalBounds = geometry?.bounds?.let { RectF(it) }
        val topCutout =
            physicalBounds != null &&
                physicalBounds.centerY() <= max(statusBarHeightPx.toFloat(), 32f * density) * 1.35f

        val compactLandscape =
            isLandscape &&
                (landscapeMode == LANDSCAPE_COMPACT ||
                    (landscapeMode == LANDSCAPE_AUTO && !topCutout))

        val forceCenter = alignment == ALIGNMENT_CENTER || compactLandscape
        val physicalCutoutEligible =
            if (isLandscape) {
                landscapeMode == LANDSCAPE_ALWAYS || topCutout
            } else {
                topCutout
            }
        val usePhysical =
            physicalBounds != null &&
                !forceCenter &&
                physicalCutoutEligible

        val effectiveSize = if (compactLandscape) SIZE_COMPACT else islandSize
        val baseHeightDp =
            when (effectiveSize) {
                SIZE_COMPACT -> 24f
                SIZE_LARGE -> 30f
                else -> 26f
            }
        val targetWingDp =
            when (effectiveSize) {
                SIZE_COMPACT -> 38f
                SIZE_LARGE -> 68f
                else -> 50f
            }

        val baseHeightPx = baseHeightDp * density
        val targetWingPx = targetWingDp * density
        val cameraGapPx = 4f * density
        val edgeMarginPx = 4f * density
        val contentGapPx = 4f * density

        if (usePhysical) {
            val cutout = RectF(physicalBounds)
            val islandHeightPx = max(baseHeightPx, cutout.height() + 4f * density)
            val desiredCameraSlotWidthPx =
                max(cutout.width() + cameraGapPx * 2f, 14f * density)
            val cameraSlotLeft =
                max(0f, cutout.centerX() - desiredCameraSlotWidthPx / 2f)
            val cameraSlotRight =
                min(displayWidthPx.toFloat(), cutout.centerX() + desiredCameraSlotWidthPx / 2f)
            val cameraSlotWidthPx = max(0f, cameraSlotRight - cameraSlotLeft)
            val verticallyRelevant =
                occupiedBounds.filter {
                    !it.isEmpty &&
                        it.bottom > cutout.top &&
                        it.top < max(cutout.bottom, statusBarHeightPx.toFloat())
                }
            val leftContentEdge =
                verticallyRelevant
                    .filter { it.centerX() < cutout.centerX() && it.left < cutout.left }
                    .maxOfOrNull { it.right }
                    ?: edgeMarginPx
            val rightContentEdge =
                verticallyRelevant
                    .filter { it.centerX() > cutout.centerX() && it.right > cutout.right }
                    .minOfOrNull { it.left }
                    ?: (displayWidthPx.toFloat() - edgeMarginPx)

            val leftAvailable =
                max(
                    0f,
                    cameraSlotLeft -
                        max(edgeMarginPx, leftContentEdge + contentGapPx),
                )
            val rightAvailable =
                max(
                    0f,
                    min(
                        displayWidthPx.toFloat() - edgeMarginPx,
                        rightContentEdge - contentGapPx,
                    ) -
                        cameraSlotRight,
                )
            val leftWing = min(targetWingPx, leftAvailable)
            val rightWing = min(targetWingPx, rightAvailable)

            val barHeight =
                max(
                    max(statusBarHeightPx.toFloat(), islandHeightPx),
                    cutout.bottom + 2f * density,
                )
            val maxTop = max(0f, barHeight - islandHeightPx)
            val top = (cutout.centerY() - islandHeightPx / 2f).coerceIn(0f, maxTop)
            val left = max(0f, cameraSlotLeft - leftWing)
            val right = min(displayWidthPx.toFloat(), cameraSlotRight + rightWing)
            val island = RectF(left, top, right, top + islandHeightPx)
            val centerFraction =
                (cutout.centerX() / displayWidthPx.toFloat()).coerceIn(0f, 1f)
            val anchorBottom =
                ceil(max(island.bottom, cutout.bottom + cameraGapPx)).toInt()

            return DynamicBarLayoutState(
                mode = DynamicBarLayoutMode.CUTOUT,
                cutoutBounds = cutout,
                islandBounds = island,
                leftWingWidthPx = leftWing,
                rightWingWidthPx = rightWing,
                cameraSlotWidthPx = cameraSlotWidthPx,
                centerXFraction = centerFraction,
                anchorBottomPx = anchorBottom,
                hasPhysicalCutout = true,
                pillLike = geometry?.pillLike == true,
                rotation = geometry?.rotation ?: 0,
            )
        }

        val islandHeightPx = baseHeightPx
        val desiredCameraSlotWidthPx =
            when (effectiveSize) {
                SIZE_COMPACT -> 6f * density
                SIZE_LARGE -> 12f * density
                else -> 8f * density
            }
        val usableWidthPx = max(0f, displayWidthPx.toFloat() - edgeMarginPx * 2f)
        val cameraSlotWidthPx = min(desiredCameraSlotWidthPx, usableWidthPx)
        val wingBudgetPx = max(0f, (usableWidthPx - cameraSlotWidthPx) / 2f)
        val leftWing = min(targetWingPx, wingBudgetPx)
        val rightWing = min(targetWingPx, wingBudgetPx)
        val totalWidth = leftWing + cameraSlotWidthPx + rightWing
        val centerX = displayWidthPx / 2f
        val barHeight = max(statusBarHeightPx.toFloat(), islandHeightPx)
        val top = max(0f, (barHeight - islandHeightPx) / 2f)
        val left = max(edgeMarginPx, centerX - totalWidth / 2f)
        val right = min(displayWidthPx.toFloat() - edgeMarginPx, left + totalWidth)
        val island = RectF(left, top, right, top + islandHeightPx)
        val virtualCutout = RectF(centerX, top, centerX, top + islandHeightPx)

        return DynamicBarLayoutState(
            mode =
                if (effectiveSize == SIZE_COMPACT) {
                    DynamicBarLayoutMode.COMPACT
                } else {
                    DynamicBarLayoutMode.CENTERED
                },
            cutoutBounds = virtualCutout,
            islandBounds = island,
            leftWingWidthPx = leftWing,
            rightWingWidthPx = rightWing,
            cameraSlotWidthPx = cameraSlotWidthPx,
            centerXFraction = 0.5f,
            anchorBottomPx = ceil(island.bottom).toInt(),
            hasPhysicalCutout = false,
            pillLike = false,
            rotation = geometry?.rotation ?: 0,
        )
    }
}
