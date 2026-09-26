/*
 * Copyright (C) 2026 Evolution X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.RectF
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.animation.Expandable as SystemUiExpandable
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.layout.DynamicBarLayoutCalculator
import com.android.systemui.axdynamicbar.ui.layout.DynamicBarLayoutState
import com.android.systemui.cutoutprogress.ring.CameraCutoutGeometryResolver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val DynamicIslandShape = RoundedCornerShape(percent = 50)

private data class DynamicBarWindowGeometry(
    val cutout: DisplayCutout?,
    val statusBarHeightPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

@Composable
fun DynamicBarCutoutHost(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
    keyguardMode: Boolean = false,
    occupiedViews: List<View> = emptyList(),
) {
    val chipState by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val alignment by viewModel.interactor.settings.cutoutAlignment.collectAsStateWithLifecycle()
    val islandSize by viewModel.interactor.settings.islandSize.collectAsStateWithLifecycle()
    val landscapeMode by viewModel.interactor.settings.landscapeMode.collectAsStateWithLifecycle()
    val debugBounds by viewModel.interactor.settings.debugBounds.collectAsStateWithLifecycle()

    val visibleForSurface = if (keyguardMode) isOnKeyguard else !isOnKeyguard
    val currentState = chipState
    if (currentState == null || !visibleForSurface) return

    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    var windowGeometry by remember(view) { mutableStateOf(readWindowGeometry(view)) }

    DisposableEffect(
        view,
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        val listener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                windowGeometry = readWindowGeometry(view)
            }
        view.addOnLayoutChangeListener(listener)
        view.post { windowGeometry = readWindowGeometry(view) }
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }

    val occupiedBounds = rememberOccupiedBounds(occupiedViews)
    val resolver = remember(context) { CameraCutoutGeometryResolver(context) }
    val resolvedGeometry =
        remember(
            windowGeometry.cutout,
            windowGeometry.widthPx,
            windowGeometry.heightPx,
            configuration.orientation,
            density.density,
        ) {
            resolver.resolve(windowGeometry.cutout)
        }

    val layoutState =
        remember(
            resolvedGeometry,
            windowGeometry,
            alignment,
            islandSize,
            landscapeMode,
            density.density,
            occupiedBounds,
        ) {
            DynamicBarLayoutCalculator.calculate(
                displayWidthPx = windowGeometry.widthPx,
                displayHeightPx = windowGeometry.heightPx,
                statusBarHeightPx = windowGeometry.statusBarHeightPx,
                density = density.density,
                geometry = resolvedGeometry,
                alignment = alignment,
                islandSize = islandSize,
                landscapeMode = landscapeMode,
                occupiedBounds = occupiedBounds,
            )
        }

    LaunchedEffect(layoutState, windowGeometry.statusBarHeightPx) {
        if (layoutState != null) {
            viewModel.updateDynamicBarAnchor(
                centerXFraction = layoutState.centerXFraction,
                bottomPx = layoutState.anchorBottomPx,
                hasPhysicalCutout = layoutState.hasPhysicalCutout,
            )
        } else {
            viewModel.updateDynamicBarAnchor(
                centerXFraction = 0.5f,
                bottomPx = windowGeometry.statusBarHeightPx,
                hasPhysicalCutout = false,
            )
        }
    }

    val layout = layoutState ?: return
    val visualWidth = with(density) { layout.islandBounds.width().toDp() }
    val visualHeight = with(density) { layout.islandBounds.height().toDp() }
    val hostHeight =
        with(density) {
            max(windowGeometry.statusBarHeightPx.toFloat(), layout.islandBounds.bottom).toDp()
        }
    val leftWing = with(density) { layout.leftWingWidthPx.toDp() }
    val rightWing = with(density) { layout.rightWingWidthPx.toDp() }
    val cameraSlot = with(density) { layout.cameraSlotWidthPx.toDp() }

    val event = currentState.event
    val accent = chipAccentColorFor(event)
    val progress = chipProgressFor(event, includeMediaProgress = true)
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val expandableController =
        rememberExpandableController(color = Color.Black, shape = DynamicIslandShape)
    var currentExpandable by remember { mutableStateOf<SystemUiExpandable?>(null) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.88f),
        exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.88f),
        modifier =
            modifier.fillMaxWidth().height(hostHeight).then(
                if (debugBounds) Modifier.drawBehind { drawDynamicBarDebug(layout) } else Modifier
            ),
    ) {
        val islandLeft = layout.islandBounds.left.roundToInt()
        val islandTop = layout.islandBounds.top.roundToInt()

        Box(Modifier.fillMaxSize()) {
            Expandable(
                controller = expandableController,
                onClick = null,
                defaultMinSize = false,
                modifier = Modifier.offset { IntOffset(islandLeft, islandTop) },
            ) { expandable ->
                currentExpandable = expandable
                Box(
                    modifier =
                        Modifier.width(visualWidth)
                            .height(visualHeight)
                            .clip(DynamicIslandShape)
                            .background(Color.Black)
                            .drawWithContent {
                                drawContent()
                                if (progress != null) {
                                    val barHeight = 1.5.dp.toPx()
                                    val y = size.height - barHeight
                                    drawRect(
                                        Color.White.copy(alpha = 0.10f),
                                        topLeft = Offset(0f, y),
                                        size = Size(size.width, barHeight),
                                    )
                                    val fillWidth = size.width * progress.coerceIn(0f, 1f)
                                    val x = if (isRtl) size.width - fillWidth else 0f
                                    drawRect(
                                        accent.copy(alpha = 0.90f),
                                        topLeft = Offset(x, y),
                                        size = Size(fillWidth, barHeight),
                                    )
                                }
                            }
                            .pointerInput(viewModel, event.id, isRtl, keyguardMode) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                    val startX = down.position.x
                                    val startY = down.position.y
                                    val touchSlop = viewConfiguration.touchSlop
                                    var dragging = false
                                    var decided = false
                                    var totalDx = 0f

                                    while (true) {
                                        val pointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = pointerEvent.changes.firstOrNull() ?: break
                                        if (!change.pressed) {
                                            if (dragging) {
                                                change.consume()
                                                val next = if (isRtl) totalDx > 0f else totalDx < 0f
                                                if (next) viewModel.cycleNext() else viewModel.cyclePrev()
                                            } else if (!decided) {
                                                change.consume()
                                                if (keyguardMode) {
                                                    viewModel.keyguardExpansion.toggle()
                                                } else {
                                                    val current = chipState?.event
                                                    val handled =
                                                        current is IslandEvent.AospChip &&
                                                            currentExpandable != null &&
                                                            viewModel.handleAospChipTap(
                                                                current,
                                                                currentExpandable!!,
                                                            )
                                                    if (!handled) viewModel.statusBarExpansion.toggle()
                                                }
                                            }
                                            break
                                        }

                                        val dx = change.position.x - startX
                                        val dy = change.position.y - startY
                                        if (!decided &&
                                            (abs(dx) > touchSlop || abs(dy) > touchSlop)
                                        ) {
                                            if (abs(dx) >= abs(dy)) {
                                                decided = true
                                                dragging = true
                                                totalDx = dx
                                                change.consume()
                                            } else {
                                                decided = true
                                                break
                                            }
                                        } else if (dragging) {
                                            totalDx = dx
                                            change.consume()
                                        }
                                    }
                                }
                            },
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhysicalWing(leftWing, towardCamera = true) {
                                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                    if (!isRtl) {
                                        DynamicBarStartWing(event, currentState.eventCount, accent, leftWing)
                                    } else {
                                        DynamicBarEndWing(event, leftWing)
                                    }
                                }
                            }

                            Spacer(Modifier.width(cameraSlot))

                            PhysicalWing(rightWing, towardCamera = false) {
                                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                    if (isRtl) {
                                        DynamicBarStartWing(event, currentState.eventCount, accent, rightWing)
                                    } else {
                                        DynamicBarEndWing(event, rightWing)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhysicalWing(
    width: Dp,
    towardCamera: Boolean,
    content: @Composable () -> Unit,
) {
    if (width.value <= 1f) {
        Spacer(Modifier.width(width))
        return
    }
    Box(
        modifier = Modifier.width(width).fillMaxHeight().padding(horizontal = 4.dp),
        contentAlignment = if (towardCamera) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
private fun DynamicBarStartWing(
    event: IslandEvent,
    eventCount: Int,
    accent: Color,
    width: Dp,
) {
    // PhysicalWing reserves 4dp on each side. Keep the icon hidden when the remaining
    // content box cannot contain it without spilling into the camera slot.
    if (width.value < 24f) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PillEventIcon(event = event, tint = accent, animated = true)
        if (eventCount > 1 && width.value >= 48f) {
            Box(
                modifier =
                    Modifier.height(16.dp)
                        .widthIn(min = 16.dp)
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = eventCount.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DynamicBarEndWing(event: IslandEvent, width: Dp) {
    if (width.value < 18f) return
    PillEventText(
        event = event,
        modifier = Modifier.widthIn(max = if (width.value > 8f) width - 8.dp else width),
        overrideColor = Color.White,
    )
}

@Composable
private fun rememberOccupiedBounds(views: List<View>): List<RectF> {
    var bounds by remember(views) { mutableStateOf(readOccupiedBounds(views)) }

    DisposableEffect(views) {
        val listener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                bounds = readOccupiedBounds(views)
            }
        views.forEach {
            it.addOnLayoutChangeListener(listener)
            it.post { bounds = readOccupiedBounds(views) }
        }
        onDispose { views.forEach { it.removeOnLayoutChangeListener(listener) } }
    }

    return bounds
}

private fun readOccupiedBounds(views: List<View>): List<RectF> =
    views.mapNotNull { view ->
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) {
            return@mapNotNull null
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        RectF(
            location[0].toFloat(),
            location[1].toFloat(),
            (location[0] + view.width).toFloat(),
            (location[1] + view.height).toFloat(),
        )
    }

private fun readWindowGeometry(view: View): DynamicBarWindowGeometry {
    val metrics = view.resources.displayMetrics
    val insets = view.rootWindowInsets
    val statusBarInset = insets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    val fallbackStatusBar =
        run {
            val id = view.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) view.resources.getDimensionPixelSize(id)
            else (24f * metrics.density).roundToInt()
        }

    return DynamicBarWindowGeometry(
        cutout = insets?.displayCutout,
        statusBarHeightPx = max(statusBarInset, fallbackStatusBar),
        widthPx = metrics.widthPixels,
        heightPx = metrics.heightPixels,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDynamicBarDebug(
    layout: DynamicBarLayoutState,
) {
    val strokeWidth = 1.dp.toPx()
    if (layout.hasPhysicalCutout) {
        val cutout = layout.cutoutBounds
        drawRect(
            color = Color.Red.copy(alpha = 0.85f),
            topLeft = Offset(cutout.left, cutout.top),
            size = Size(cutout.width(), cutout.height()),
            style = Stroke(strokeWidth),
        )
    }

    val island = layout.islandBounds
    drawRect(
        color = Color.Cyan.copy(alpha = 0.85f),
        topLeft = Offset(island.left, island.top),
        size = Size(island.width(), island.height()),
        style = Stroke(strokeWidth),
    )

    val anchorX = layout.centerXFraction * size.width
    drawLine(
        color = Color.Green.copy(alpha = 0.80f),
        start = Offset(anchorX, 0f),
        end = Offset(anchorX, size.height),
        strokeWidth = strokeWidth,
    )
}
