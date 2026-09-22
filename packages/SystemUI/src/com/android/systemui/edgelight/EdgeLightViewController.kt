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
import android.os.PowerManager
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.Display
import android.widget.FrameLayout

import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils
import com.android.internal.util.ContrastColorUtil

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class EdgeLightViewController
@Inject
constructor(
    private val context: Context,
    private val listener: NotificationListener,
) : NotificationListener.NotificationHandler,
    ScrimUtils.ScrimEventListener {

    private enum class TriggerState {
        SCREEN_ON,
        SCREEN_OFF,
        AOD,
    }

    private val edgeLightView = EdgeLightView(context)
    private val settingsRepo = EdgeLightSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)!!
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private var currentSettings = settingsRepo.currentSettings()
    private var isDozing = ScrimUtils.get().isDozing()
    private var isScreenOn = powerManager?.isInteractive == true

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private var pendingTriggerState: TriggerState? = null
    private val deduplicationWindowMs = 3000L
    private val pendingStateWindowMs = 5000L
    private var lastNotifColor: Int = Utils.getColorAccentDefaultColor(context)

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        // Keep the handler registered while SystemUI is alive. Screen-on edge lighting must also
        // receive notifications after keyguard is dismissed.
        listener.addNotificationHandler(this)

        scope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                applySettings(settings)
            }
        }
    }

    fun getEdgeLightView(): FrameLayout = edgeLightView

    private fun isMulticolorMode(): Boolean {
        return if (currentSettings.animationEffect == EFFECT_AURORA) {
            currentSettings.auroraColorMode == AURORA_MULTICOLOR
        } else {
            currentSettings.colorMode == COLOR_MODE_RAINBOW
        }
    }

    private fun getColor(): Int {
        // Fixed Aurora intentionally uses the custom picker so it remains a true single color.
        if (currentSettings.animationEffect == EFFECT_AURORA) {
            return currentSettings.customColor
        }

        return when (currentSettings.colorMode) {
            COLOR_MODE_ACCENT -> Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_CUSTOM -> currentSettings.customColor
            COLOR_MODE_WALLPAPER -> wallpaperManager
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.primaryColor?.toArgb() ?: Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_NOTIFICATION -> lastNotifColor
            COLOR_MODE_RAINBOW -> Utils.getColorAccentDefaultColor(context)
            else -> Utils.getColorAccentDefaultColor(context)
        }
    }

    private fun applyRenderColor() {
        edgeLightView.paintColor = getColor()
        edgeLightView.rainbowEnabled = isMulticolorMode()
    }

    private fun applySettings(settings: EdgeLightSettings) {
        if (!settings.isEnabled) {
            stopEdgeLight()
            return
        }

        applyRenderColor()
        edgeLightView.apply {
            userPulseCount = settings.pulseCount
            userStrokeWidth = settings.strokeWidth
            edgeStyle = settings.edgeStyle
            animationEffect = settings.animationEffect
            userSpread = settings.spread
            userIntensity = settings.intensity
            showTop = settings.topEnabled
            showSides = settings.sidesEnabled
            showBottom = settings.bottomEnabled
        }

        if (!isStateEnabled(currentTriggerState())) {
            stopEdgeLight()
        }
    }

    private fun currentTriggerState(): TriggerState {
        return when (context.display?.state) {
            Display.STATE_OFF -> TriggerState.SCREEN_OFF
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> TriggerState.AOD
            Display.STATE_ON, Display.STATE_VR -> TriggerState.SCREEN_ON
            else -> when {
                isDozing -> TriggerState.AOD
                isScreenOn -> TriggerState.SCREEN_ON
                else -> TriggerState.SCREEN_OFF
            }
        }
    }

    private fun isStateEnabled(state: TriggerState): Boolean = when (state) {
        TriggerState.SCREEN_ON -> currentSettings.screenOnEnabled
        TriggerState.SCREEN_OFF -> currentSettings.screenOffEnabled
        TriggerState.AOD -> currentSettings.aodEnabled
    }

    private fun showEdgeLights() {
        if (!currentSettings.isEnabled) return
        if (!currentSettings.topEnabled &&
            !currentSettings.sidesEnabled &&
            !currentSettings.bottomEnabled) {
            stopEdgeLight()
            return
        }

        applyRenderColor()
        edgeLightView.apply {
            visible = true
            pulseRunning = true
        }
    }

    private fun stopEdgeLight() {
        edgeLightView.pulseRunning = false
        edgeLightView.visible = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!currentSettings.isEnabled) return

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            now - lastNotificationTime <= deduplicationWindowMs) {
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

        val triggerState = currentTriggerState()
        pendingTriggerState = triggerState

        // Do not consume the pulse animation while the panel is physically off. Keep the
        // notification state pending and start it when doze/pulsing brings pixels back.
        if (triggerState != TriggerState.SCREEN_OFF && isStateEnabled(triggerState)) {
            showEdgeLights()
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        val pendingScreenOff = pendingTriggerState == TriggerState.SCREEN_OFF &&
            System.currentTimeMillis() - lastNotificationTime <= pendingStateWindowMs
        if (dozing && pendingScreenOff && currentSettings.screenOffEnabled) {
            showEdgeLights()
        } else if (!isStateEnabled(currentTriggerState())) {
            stopEdgeLight()
        }
    }

    override fun onStartedWakingUp() {
        isScreenOn = true
        if (!currentSettings.screenOnEnabled && !isDozing) {
            stopEdgeLight()
        }
    }

    override fun onScreenTurnedOff() {
        isScreenOn = false
        if (!currentSettings.screenOffEnabled && !currentSettings.aodEnabled) {
            stopEdgeLight()
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!currentSettings.isEnabled) return
        if (!showing && !currentSettings.screenOnEnabled) {
            stopEdgeLight()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (fadingAway && !currentSettings.screenOnEnabled) {
            stopEdgeLight()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (goingAway && !currentSettings.screenOnEnabled) {
            stopEdgeLight()
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!currentSettings.isEnabled || !pulsing) return

        val now = System.currentTimeMillis()
        val triggerState = if (now - lastNotificationTime <= pendingStateWindowMs) {
            pendingTriggerState ?: currentTriggerState()
        } else {
            currentTriggerState()
        }

        if (isStateEnabled(triggerState)) {
            showEdgeLights()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    companion object {
        private const val COLOR_MODE_ACCENT = "accent"
        private const val COLOR_MODE_NOTIFICATION = "notification"
        private const val COLOR_MODE_WALLPAPER = "wallpaper"
        private const val COLOR_MODE_CUSTOM = "custom"
        private const val COLOR_MODE_RAINBOW = "rainbow"

        private const val EFFECT_AURORA = "aurora"
        private const val AURORA_MULTICOLOR = "multicolor"

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
