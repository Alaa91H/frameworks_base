/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.edgelight

import android.app.Notification
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.Display
import android.widget.FrameLayout

import com.android.internal.util.ContrastColorUtil
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SysUISingleton
class EdgeLightViewController @Inject constructor(
    private val context: Context,
    private val listener: NotificationListener,
) : NotificationListener.NotificationHandler, ScrimUtils.ScrimEventListener {

    private val edgeLightView = EdgeLightView(context)
    private val settingsRepo = EdgeLightSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)!!

    private var currentSettings = settingsRepo.currentSettings()
    private var isDozing = false
    private var notificationHandlerRegistered = false
    private var pendingPulseState: DisplayContext? = null
    private var activePulseState: DisplayContext? = null

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 3000L
    private var lastNotifColor: Int = Utils.getColorAccentDefaultColor(context)

    init {
        INSTANCE = this
        ScrimUtils.get().addListener(this)
        scope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                applySettings()
            }
        }
    }

    fun getEdgeLightView(): FrameLayout = edgeLightView

    private fun getColor(): Int = when (currentSettings.colorMode) {
        COLOR_MODE_ACCENT -> Utils.getColorAccentDefaultColor(context)
        COLOR_MODE_CUSTOM -> currentSettings.customColor
        COLOR_MODE_WALLPAPER -> wallpaperManager
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()
            ?: Utils.getColorAccentDefaultColor(context)
        COLOR_MODE_NOTIFICATION -> lastNotifColor
        COLOR_MODE_RAINBOW -> EdgeLightView.COLOR_RAINBOW
        else -> Utils.getColorAccentDefaultColor(context)
    }

    private fun applySettings() {
        if (!currentSettings.isEnabled) {
            pendingPulseState = null
            activePulseState = null
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
            setNotificationHandlerRegistered(false)
            return
        }

        edgeLightView.apply {
            paintColor = getColor()
            userPulseCount = currentSettings.pulseCount
            userStrokeWidth = currentSettings.strokeWidth
            edgeStyle = currentSettings.edgeStyle
            animationEffect = currentSettings.animationEffect
            userSpread = currentSettings.spread
            userIntensity = currentSettings.intensity
            showTop = currentSettings.showTop
            showSides = currentSettings.showSides
            showBottom = currentSettings.showBottom
            auroraMulticolor = currentSettings.auroraMulticolor
        }
        setNotificationHandlerRegistered(true)
    }

    private fun setNotificationHandlerRegistered(register: Boolean) {
        if (register == notificationHandlerRegistered) return
        if (register) {
            listener.addNotificationHandler(this)
        } else {
            listener.removeNotificationHandler(this)
        }
        notificationHandlerRegistered = register
    }

    private fun classifyDisplayState(): DisplayContext {
        val state = context.display?.state ?: Display.STATE_UNKNOWN
        return when {
            state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND -> DisplayContext.AOD
            state == Display.STATE_OFF -> DisplayContext.SCREEN_OFF
            state == Display.STATE_ON && !isDozing -> DisplayContext.SCREEN_ON
            isDozing -> DisplayContext.AOD
            else -> DisplayContext.SCREEN_ON
        }
    }

    private fun isAllowed(state: DisplayContext): Boolean {
        if (currentSettings.showAllStates) return true
        return when (state) {
            DisplayContext.SCREEN_ON -> currentSettings.showScreenOn
            DisplayContext.SCREEN_OFF -> currentSettings.showScreenOff
            DisplayContext.AOD -> currentSettings.showAod
        }
    }

    private fun startPulse(state: DisplayContext) {
        if (!currentSettings.isEnabled || !isAllowed(state)) return
        activePulseState = state
        edgeLightView.apply {
            paintColor = getColor()
            visible = true
            pulseRunning = true
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!currentSettings.isEnabled) return

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()
        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            now - lastNotificationTime <= deduplicationWindowMs
        ) {
            return
        }

        lastNotificationKey = currentKey
        lastNotificationText = currentText
        lastNotificationTime = now

        if (currentSettings.colorMode == COLOR_MODE_NOTIFICATION) {
            val notifColor = sbn.notification.color
            val accent = Utils.getColorAccentDefaultColor(context)
            lastNotifColor = when {
                notifColor == Color.TRANSPARENT || notifColor == 0 -> accent
                ContrastColorUtil.isColorDark(notifColor) -> accent
                else -> notifColor
            }
            edgeLightView.paintColor = lastNotifColor
        }

        val state = classifyDisplayState()
        pendingPulseState = state

        if (state == DisplayContext.SCREEN_ON) {
            startPulse(state)
            pendingPulseState = null
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!currentSettings.isEnabled) return

        if (!pulsing) {
            if (activePulseState != DisplayContext.SCREEN_ON) {
                edgeLightView.pulseRunning = false
                edgeLightView.visible = false
                activePulseState = null
            }
            return
        }

        val state = pendingPulseState ?: classifyDisplayState()
        pendingPulseState = null
        if (state != DisplayContext.SCREEN_ON) {
            startPulse(state)
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        if (!dozing && activePulseState != DisplayContext.SCREEN_ON) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
            activePulseState = null
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!currentSettings.isEnabled) return
        if (!showing && activePulseState != DisplayContext.SCREEN_ON) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
            activePulseState = null
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (fadingAway && activePulseState != DisplayContext.SCREEN_ON) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
            activePulseState = null
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (goingAway && activePulseState != DisplayContext.SCREEN_ON) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
            activePulseState = null
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    private enum class DisplayContext {
        SCREEN_ON,
        SCREEN_OFF,
        AOD,
    }

    companion object {
        private const val COLOR_MODE_ACCENT = "accent"
        private const val COLOR_MODE_NOTIFICATION = "notification"
        private const val COLOR_MODE_WALLPAPER = "wallpaper"
        private const val COLOR_MODE_CUSTOM = "custom"
        private const val COLOR_MODE_RAINBOW = "rainbow"

        @Volatile
        private var INSTANCE: EdgeLightViewController? = null

        @JvmStatic
        fun get(context: Context): EdgeLightViewController =
            INSTANCE ?: throw IllegalStateException("EdgeLightViewController not initialized")
    }
}
