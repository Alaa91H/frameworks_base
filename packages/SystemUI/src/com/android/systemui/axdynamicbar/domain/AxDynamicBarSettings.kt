package com.android.systemui.axdynamicbar.domain

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.EVENT_TYPE_IDS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@SysUISingleton
class AxDynamicBarSettings @Inject constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
    private val context: android.content.Context,
) {
    companion object {
        const val KEY_ENABLED = "ax_dynamic_bar_enabled"
        const val KEY_EVENTS = "ax_dynamic_bar_events"
        const val KEY_KEYGUARD_ENABLED = "ax_dynamic_bar_keyguard_enabled"
        const val KEY_KEYGUARD_MUSIC_PILL_ENABLED = "ax_dynamic_bar_keyguard_music_pill"
        const val KEY_KEYGUARD_BATTERY_CHIP_MODE = "ax_dynamic_bar_keyguard_battery_chip_mode"
        const val KEY_CHIP_STYLE = "ax_dynamic_bar_chip_style"
        const val KEY_CUTOUT_ALIGNMENT = "ax_dynamic_bar_cutout_alignment"
        const val KEY_ISLAND_SIZE = "ax_dynamic_bar_island_size"
        const val KEY_LANDSCAPE_MODE = "ax_dynamic_bar_landscape_mode"
        const val KEY_DEBUG_BOUNDS = "ax_dynamic_bar_debug_bounds"
    }

    private val contentResolver = context.contentResolver

    private val _isEnabled = MutableStateFlow(false)
    @get:JvmName("getIsEnabled") val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isKeyguardEnabled = MutableStateFlow(true)
    val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _isKeyguardMusicPillEnabled = MutableStateFlow(false)
    val isKeyguardMusicPillEnabled: StateFlow<Boolean> = _isKeyguardMusicPillEnabled.asStateFlow()

    private val _keyguardBatteryChipMode = MutableStateFlow(1)
    val keyguardBatteryChipMode: StateFlow<Int> = _keyguardBatteryChipMode.asStateFlow()

    private val _useWaveformSeekBar = MutableStateFlow(false)
    val useWaveformSeekBar: StateFlow<Boolean> = _useWaveformSeekBar.asStateFlow()

    private val _chipStyle = MutableStateFlow(0)
    val chipStyle: StateFlow<Int> = _chipStyle.asStateFlow()

    private val _cutoutAlignment = MutableStateFlow(0)
    val cutoutAlignment: StateFlow<Int> = _cutoutAlignment.asStateFlow()

    private val _islandSize = MutableStateFlow(0)
    val islandSize: StateFlow<Int> = _islandSize.asStateFlow()

    private val _landscapeMode = MutableStateFlow(0)
    val landscapeMode: StateFlow<Int> = _landscapeMode.asStateFlow()

    private val _debugBounds = MutableStateFlow(false)
    val debugBounds: StateFlow<Boolean> = _debugBounds.asStateFlow()

    private val _disabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val disabledEventTypes: StateFlow<Set<String>> = _disabledEventTypes.asStateFlow()

    init {
        refresh()
    }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        refresh()
        secureSettings.registerContentObserverForUserSync(
            KEY_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_EVENTS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_MUSIC_PILL_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_BATTERY_CHIP_MODE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CHIP_STYLE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CUTOUT_ALIGNMENT,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_ISLAND_SIZE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_LANDSCAPE_MODE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_DEBUG_BOUNDS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.MEDIA_WAVEFORM_SEEKBAR),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
    }

    fun destroy() {
        if (!initialized) return
        initialized = false
        val secureResolver = secureSettings.getContentResolver()
        secureResolver.unregisterContentObserver(settingsObserver)
        if (contentResolver !== secureResolver) {
            contentResolver.unregisterContentObserver(settingsObserver)
        }
    }

    private fun refresh() {
        _isEnabled.value =
            secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _isKeyguardEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_ENABLED, 1, UserHandle.USER_CURRENT) == 1
        _keyguardBatteryChipMode.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_BATTERY_CHIP_MODE, 1, UserHandle.USER_CURRENT)
        _isKeyguardMusicPillEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_MUSIC_PILL_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _chipStyle.value =
            secureSettings.getIntForUser(KEY_CHIP_STYLE, 0, UserHandle.USER_CURRENT)
        _cutoutAlignment.value =
            secureSettings.getIntForUser(KEY_CUTOUT_ALIGNMENT, 0, UserHandle.USER_CURRENT)
        _islandSize.value =
            secureSettings.getIntForUser(KEY_ISLAND_SIZE, 0, UserHandle.USER_CURRENT)
        _landscapeMode.value =
            secureSettings.getIntForUser(KEY_LANDSCAPE_MODE, 0, UserHandle.USER_CURRENT)
        _debugBounds.value =
            secureSettings.getIntForUser(KEY_DEBUG_BOUNDS, 0, UserHandle.USER_CURRENT) == 1
        _useWaveformSeekBar.value =
            Settings.System.getIntForUser(
                contentResolver, Settings.System.MEDIA_WAVEFORM_SEEKBAR, 0, UserHandle.USER_CURRENT,) == 1

        val json = secureSettings.getStringForUser(KEY_EVENTS, UserHandle.USER_CURRENT) ?: ""
        _disabledEventTypes.value =
            try {
                if (json.isBlank()) emptySet()
                else {
                    val arr = JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
                }
            } catch (_: Exception) {
                emptySet()
            }
    }

    fun isEventEnabled(event: IslandEvent): Boolean {
        val typeId = EVENT_TYPE_IDS[event::class.java] ?: return true
        return typeId !in _disabledEventTypes.value
    }

    fun isKeyguardBiometricUnlockEventsActive(): Boolean =
        _isEnabled.value && _isKeyguardEnabled.value &&
            "biometric_unlock" !in _disabledEventTypes.value
}
