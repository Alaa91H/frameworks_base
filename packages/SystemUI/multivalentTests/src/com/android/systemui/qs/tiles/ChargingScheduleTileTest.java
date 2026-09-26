/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ChargingScheduleTileTest {

    @Test
    public void sanitizeSecondOfDay_validValue_preserved() {
        assertThat(ChargingScheduleTile.sanitizeSecondOfDay(86399, 3600)).isEqualTo(86399);
    }

    @Test
    public void sanitizeSecondOfDay_negativeValue_usesFallback() {
        assertThat(ChargingScheduleTile.sanitizeSecondOfDay(-1, 3600)).isEqualTo(3600);
    }

    @Test
    public void sanitizeSecondOfDay_fullDayValue_usesFallback() {
        assertThat(ChargingScheduleTile.sanitizeSecondOfDay(86400, 7200)).isEqualTo(7200);
    }

    @Test
    public void sanitizeSecondOfDay_invalidValueAndFallback_usesMidnight() {
        assertThat(ChargingScheduleTile.sanitizeSecondOfDay(86400, -1)).isEqualTo(0);
    }
}