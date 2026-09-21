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

import android.app.Notification
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.view.Display
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.widget.FrameLayout

import com.android.settingslib.Utils

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils
import com.android.internal.util.ContrastColorUtil

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class EdgeLightViewController 
@Inject 
constructor(
    private val context: Context,
    private val listener: NotificationListener,
) : NotificationListener.NotificationHandler,
    ScrimUtils.ScrimEventListener {

    private val edgeLightView = EdgeLightView(context)
    private val settingsRepo = EdgeLightSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)!!

    private var currentSettings = settingsRepo.currentSettings()

    private var job: Job? = null

    private var isDozing = false
    private var pendingDisplayMode: DisplayMode? = null

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 3000L
    private var lastNotifColor: Int = Utils.getColorAccentDefaultColor(context)

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        listener.addNotificationHandler(this)
        observeSettings()
    }

    fun getEdgeLightView(): FrameLayout = edgeLightView

    private fun getColor(): Int =
        when (currentSettings.colorMode) {
            COLOR_MODE_ACCENT -> Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_CUSTOM -> currentSettings.customColor
            COLOR_MODE_WALLPAPER -> wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor?.toArgb() ?: Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_NOTIFICATION -> lastNotifColor
            COLOR_MODE_RAINBOW -> EdgeLightView.COLOR_RAINBOW
            else -> Utils.getColorAccentDefaultColor(context)
        }

    private fun observeSettings() {
        job?.cancel()
        job = scope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                applySettings(settings)
            }
        }
    }

    private fun applySettings(settings: EdgeLightSettings) {
        edgeLightView.userPulseCount = settings.pulseCount
        edgeLightView.userStrokeWidth = settings.strokeWidth
        edgeLightView.edgeStyle = settings.edgeStyle
        edgeLightView.animationEffect = settings.animationEffect
        edgeLightView.userSpread = settings.spread
        edgeLightView.userIntensity = settings.intensity
        edgeLightView.positionTop = settings.positionTop
        edgeLightView.positionSides = settings.positionSides
        edgeLightView.positionBottom = settings.positionBottom
        edgeLightView.auroraColorMode = settings.auroraColorMode
        edgeLightView.paintColor = getColor()

        if (!settings.isEnabled ||
                (!settings.positionTop && !settings.positionSides && !settings.positionBottom) ||
                !isDisplayModeEnabled(currentDisplayMode())) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    private fun currentDisplayMode(): DisplayMode {
        val state = context.display?.state ?: Display.STATE_ON
        return when {
            state == Display.STATE_OFF -> DisplayMode.SCREEN_OFF
            isDozing || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND ->
                DisplayMode.AOD
            else -> DisplayMode.SCREEN_ON
        }
    }

    private fun isDisplayModeEnabled(mode: DisplayMode): Boolean = when (mode) {
        DisplayMode.SCREEN_ON -> currentSettings.runScreenOn
        DisplayMode.SCREEN_OFF -> currentSettings.runScreenOff
        DisplayMode.AOD -> currentSettings.runAod
    }

    private fun showEdgeLights() {
        if (!currentSettings.isEnabled) return
        val mode = pendingDisplayMode ?: currentDisplayMode()
        if (!isDisplayModeEnabled(mode)) return
        if (!currentSettings.positionTop &&
                !currentSettings.positionSides &&
                !currentSettings.positionBottom) return

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
            (now - lastNotificationTime <= deduplicationWindowMs)) {
            return
        }

        lastNotificationKey = currentKey
        lastNotificationText = currentText
        lastNotificationTime = now

        val notifColor = sbn.notification.color
        val accent = Utils.getColorAccentDefaultColor(context)
        lastNotifColor = when {
            notifColor == Color.TRANSPARENT || notifColor == 0 -> accent
            ContrastColorUtil.isColorDark(notifColor) -> accent
            else -> notifColor
        }

        val mode = currentDisplayMode()
        pendingDisplayMode = mode
        if (!isDisplayModeEnabled(mode)) return

        // A fully-on display and AOD can render immediately. A truly-off display must wait
        // for SystemUI's doze pulse callback, otherwise the panel cannot physically show pixels.
        if (mode == DisplayMode.SCREEN_ON || mode == DisplayMode.AOD) {
            showEdgeLights()
            pendingDisplayMode = null
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        if (!currentSettings.isEnabled) return

        val mode = currentDisplayMode()
        if (!isDisplayModeEnabled(mode)) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!currentSettings.isEnabled) return
        if (!showing && !currentSettings.runScreenOn) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (!currentSettings.isEnabled) return
        if (fadingAway && !currentSettings.runScreenOn) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (!currentSettings.isEnabled) return
        if (goingAway && !currentSettings.runScreenOn) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!currentSettings.isEnabled || !pulsing) return

        val mode = pendingDisplayMode ?: currentDisplayMode()
        if (!isDisplayModeEnabled(mode)) {
            pendingDisplayMode = null
            return
        }

        showEdgeLights()
        pendingDisplayMode = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    private enum class DisplayMode {
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
        fun get(context: Context): EdgeLightViewController {
            return INSTANCE ?: throw IllegalStateException(
                "EdgeLightViewController not initialized"
            )
        }
    }
}
