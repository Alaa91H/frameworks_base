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
import android.hardware.display.AmbientDisplayConfiguration
import android.os.PowerManager
import android.os.UserHandle
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
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val ambientDisplayConfiguration = AmbientDisplayConfiguration(context)

    private var currentSettings = settingsRepo.currentSettings()

    private var job: Job? = null
    private var notificationHandlerRegistered = false

    private var isDozing = false

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 3000L
    private var lastNotifColor: Int = Utils.getColorAccentDefaultColor(context)

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        updateView()
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

    private fun updateView() {
        job?.cancel()
        job = scope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                updateNotificationRegistration()

                if (!settings.isEnabled) {
                    edgeLightView.pulseRunning = false
                    edgeLightView.visible = false
                    return@collectLatest
                }

                edgeLightView.paintColor = getColor()
                edgeLightView.userPulseCount = settings.pulseCount
                edgeLightView.userStrokeWidth = settings.strokeWidth
                edgeLightView.edgeStyle = settings.edgeStyle
                edgeLightView.animationEffect = settings.animationEffect
                edgeLightView.userSpread = settings.spread
                edgeLightView.userIntensity = settings.intensity
                edgeLightView.showTop = settings.showTop
                edgeLightView.showSides = settings.showSides
                edgeLightView.showBottom = settings.showBottom
                edgeLightView.auroraColorMode = settings.auroraColorMode

                val hasEnabledRegion = settings.showTop || settings.showSides || settings.showBottom
                if (!hasEnabledRegion || !shouldShowInCurrentDisplayState()) {
                    edgeLightView.pulseRunning = false
                    edgeLightView.visible = false
                }
            }
        }
    }

    private fun updateNotificationRegistration() {
        if (currentSettings.isEnabled && !notificationHandlerRegistered) {
            listener.addNotificationHandler(this)
            notificationHandlerRegistered = true
        } else if (!currentSettings.isEnabled && notificationHandlerRegistered) {
            listener.removeNotificationHandler(this)
            notificationHandlerRegistered = false
        }
    }

    private fun isAlwaysOnEnabled(): Boolean = try {
        ambientDisplayConfiguration.alwaysOnEnabled(UserHandle.USER_CURRENT)
    } catch (_: Exception) {
        false
    }

    private fun shouldShowInCurrentDisplayState(): Boolean {
        if (powerManager.isInteractive) {
            return currentSettings.showScreenOn
        }
        return if (isDozing && isAlwaysOnEnabled()) {
            currentSettings.showAod
        } else {
            currentSettings.showScreenOff
        }
    }

    private fun triggerPulse() {
        if (!currentSettings.isEnabled ||
                (!currentSettings.showTop && !currentSettings.showSides && !currentSettings.showBottom)) {
            return
        }
        // Restart the finite pulse for each accepted notification/event.
        edgeLightView.pulseRunning = false
        edgeLightView.visible = true
        edgeLightView.pulseRunning = true
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

        // Doze/AOD pulses are started by setPulsing(), which guarantees the display is actually
        // drawing. While the screen is interactive there is no doze callback, so start here.
        if (powerManager.isInteractive && currentSettings.showScreenOn) {
            triggerPulse()
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        // Keep state current even while the feature is disabled; enabling it during an existing
        // doze/AOD session must immediately use the correct display-state preference.
        isDozing = dozing
        if (!currentSettings.isEnabled) return
        if (!isDozing) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!currentSettings.isEnabled) return
        if (!showing) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
        // Notification registration is tied to the feature toggle, not keyguard visibility, so
        // "screen on" works while the device is unlocked too.
        updateNotificationRegistration()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (!currentSettings.isEnabled) return
        if (fadingAway) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (!currentSettings.isEnabled) return
        if (goingAway) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!currentSettings.isEnabled || !pulsing || !isDozing) return
        if (!shouldShowInCurrentDisplayState()) return
        triggerPulse()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

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
