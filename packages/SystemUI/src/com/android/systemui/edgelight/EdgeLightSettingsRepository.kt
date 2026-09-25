/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.android.systemui.edgelight

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

const val EDGE_LIGHT_DEFAULT_SPREAD = 0.00f
const val EDGE_LIGHT_DEFAULT_INTENSITY = 0.00f

data class EdgeLightSettings(
    val isEnabled: Boolean,
    val colorMode: String,
    val customColor: Int,
    val pulseCount: Int,
    val strokeWidth: Int,
    val edgeStyle: String,
    val animationEffect: String,
    val spread: Float = EDGE_LIGHT_DEFAULT_SPREAD,
    val intensity: Float = EDGE_LIGHT_DEFAULT_INTENSITY,
    val showTop: Boolean = false,
    val showSides: Boolean = true,
    val showBottom: Boolean = false,
    val showScreenOn: Boolean = false,
    val showScreenOff: Boolean = true,
    val showAod: Boolean = true,
    val showAllStates: Boolean = false,
    val auroraMulticolor: Boolean = true,
)

class EdgeLightSettingsRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val defaultCustomColor = Color.WHITE

    val settingsFlow: Flow<EdgeLightSettings> = combine(
        observeSettingInt(SETTING_ENABLED, 0),
        observeSettingString(SETTING_COLOR_MODE, "accent"),
        observeSettingInt(SETTING_CUSTOM_COLOR, defaultCustomColor),
        observeSettingInt(SETTING_PULSE_COUNT, 3),
        observeSettingInt(SETTING_STROKE_WIDTH, 8),
        observeSettingString(SETTING_EDGE_STYLE, "default"),
        observeSettingString(SETTING_ANIMATION_EFFECT, "none"),
        observeSettingInt(SETTING_SPREAD, (EDGE_LIGHT_DEFAULT_SPREAD * 100).toInt()),
        observeSettingInt(SETTING_INTENSITY, (EDGE_LIGHT_DEFAULT_INTENSITY * 100).toInt()),
        observeSettingInt(SETTING_LOCATION_TOP, 0),
        observeSettingInt(SETTING_LOCATION_SIDES, 1),
        observeSettingInt(SETTING_LOCATION_BOTTOM, 0),
        observeSettingInt(SETTING_SCREEN_ON, 0),
        observeSettingInt(SETTING_SCREEN_OFF, 1),
        observeSettingInt(SETTING_AOD, 1),
        observeSettingInt(SETTING_ALL_STATES, 0),
        observeSettingInt(SETTING_AURORA_MULTICOLOR, 1),
    ) { values: Array<Any?> ->
        val pulseCount = (values[3] as Int).coerceIn(1, 5)
        val strokeWidth = (values[4] as Int).coerceIn(2, 32)
        val spread = ((values[7] as Int) / 100f).coerceIn(0f, 1f)
        val intensity = ((values[8] as Int) / 100f).coerceIn(0f, 1f)

        EdgeLightSettings(
            isEnabled = values[0] as Int == 1,
            colorMode = values[1] as String,
            customColor = values[2] as Int,
            pulseCount = pulseCount,
            strokeWidth = strokeWidth,
            edgeStyle = values[5] as String,
            animationEffect = values[6] as String,
            spread = spread,
            intensity = intensity,
            showTop = values[9] as Int == 1,
            showSides = values[10] as Int == 1,
            showBottom = values[11] as Int == 1,
            showScreenOn = values[12] as Int == 1,
            showScreenOff = values[13] as Int == 1,
            showAod = values[14] as Int == 1,
            showAllStates = values[15] as Int == 1,
            auroraMulticolor = values[16] as Int == 1,
        )
    }.distinctUntilChanged()

    fun currentSettings(): EdgeLightSettings = EdgeLightSettings(
        isEnabled = readInt(SETTING_ENABLED, 0) == 1,
        colorMode = readString(SETTING_COLOR_MODE, "accent"),
        customColor = readInt(SETTING_CUSTOM_COLOR, defaultCustomColor),
        pulseCount = readInt(SETTING_PULSE_COUNT, 3).coerceIn(1, 5),
        strokeWidth = readInt(SETTING_STROKE_WIDTH, 8).coerceIn(2, 32),
        edgeStyle = readString(SETTING_EDGE_STYLE, "default"),
        animationEffect = readString(SETTING_ANIMATION_EFFECT, "none"),
        spread = (readInt(SETTING_SPREAD, 0) / 100f).coerceIn(0f, 1f),
        intensity = (readInt(SETTING_INTENSITY, 0) / 100f).coerceIn(0f, 1f),
        showTop = readInt(SETTING_LOCATION_TOP, 0) == 1,
        showSides = readInt(SETTING_LOCATION_SIDES, 1) == 1,
        showBottom = readInt(SETTING_LOCATION_BOTTOM, 0) == 1,
        showScreenOn = readInt(SETTING_SCREEN_ON, 0) == 1,
        showScreenOff = readInt(SETTING_SCREEN_OFF, 1) == 1,
        showAod = readInt(SETTING_AOD, 1) == 1,
        showAllStates = readInt(SETTING_ALL_STATES, 0) == 1,
        auroraMulticolor = readInt(SETTING_AURORA_MULTICOLOR, 1) == 1,
    )

    private fun readInt(key: String, default: Int): Int =
        Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)

    private fun readString(key: String, default: String): String =
        Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: default

    private fun observeSettingInt(key: String, default: Int): Flow<Int> = callbackFlow {
        val uri = Settings.System.getUriFor(key)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(readInt(key, default))
            }
        }
        resolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL)
        trySend(readInt(key, default))
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun observeSettingString(key: String, default: String): Flow<String> = callbackFlow {
        val uri = Settings.System.getUriFor(key)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(readString(key, default))
            }
        }
        resolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL)
        trySend(readString(key, default))
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    companion object {
        private const val SETTING_ENABLED = Settings.System.EDGE_LIGHT_ENABLED
        private const val SETTING_COLOR_MODE = Settings.System.EDGE_LIGHT_COLOR_MODE
        private const val SETTING_CUSTOM_COLOR = Settings.System.EDGE_LIGHT_CUSTOM_COLOR
        private const val SETTING_PULSE_COUNT = Settings.System.EDGE_LIGHT_PULSE_COUNT
        private const val SETTING_STROKE_WIDTH = Settings.System.EDGE_LIGHT_STROKE_WIDTH
        private const val SETTING_EDGE_STYLE = Settings.System.EDGE_LIGHT_STYLE
        private const val SETTING_ANIMATION_EFFECT = Settings.System.EDGE_LIGHT_ANIMATION_EFFECT
        private const val SETTING_SPREAD = Settings.System.EDGE_LIGHT_SPREAD
        private const val SETTING_INTENSITY = Settings.System.EDGE_LIGHT_INTENSITY
        private const val SETTING_LOCATION_TOP = Settings.System.EDGE_LIGHT_LOCATION_TOP
        private const val SETTING_LOCATION_SIDES = Settings.System.EDGE_LIGHT_LOCATION_SIDES
        private const val SETTING_LOCATION_BOTTOM = Settings.System.EDGE_LIGHT_LOCATION_BOTTOM
        private const val SETTING_SCREEN_ON = Settings.System.EDGE_LIGHT_SCREEN_ON
        private const val SETTING_SCREEN_OFF = Settings.System.EDGE_LIGHT_SCREEN_OFF
        private const val SETTING_AOD = Settings.System.EDGE_LIGHT_AOD
        private const val SETTING_ALL_STATES = Settings.System.EDGE_LIGHT_ALL_STATES
        private const val SETTING_AURORA_MULTICOLOR = Settings.System.EDGE_LIGHT_AURORA_MULTICOLOR
    }
}
