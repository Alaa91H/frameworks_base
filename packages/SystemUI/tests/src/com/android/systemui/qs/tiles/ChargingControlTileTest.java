/*
 * Copyright (C) 2026 Evolution X
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks that the Quick Settings charge limit matches LineageParts' 70–100% slider. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ChargingControlTileTest {
    @Test
    public void typicalChargeLimitIsUnchanged() {
        assertThat(ChargingControlTile.clampLimit(80)).isEqualTo(80);
    }

    @Test
    public void lowerThanHardwareSettingsRangeIsClamped() {
        assertThat(ChargingControlTile.clampLimit(0)).isEqualTo(70);
        assertThat(ChargingControlTile.clampLimit(69)).isEqualTo(70);
    }

    @Test
    public void upperBoundIsExactlyOneHundredPercent() {
        assertThat(ChargingControlTile.clampLimit(100)).isEqualTo(100);
        assertThat(ChargingControlTile.clampLimit(101)).isEqualTo(100);
    }
}
