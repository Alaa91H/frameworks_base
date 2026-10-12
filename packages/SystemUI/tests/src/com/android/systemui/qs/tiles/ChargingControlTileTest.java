/*
 * SPDX-FileCopyrightText: 2026 Evolution X contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression tests for valid charging limits shown by the Quick Settings slider. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ChargingControlTileTest {
    @Test
    public void clampLimitToSupportedLineageSliderBounds() {
        assertThat(ChargingControlTile.clampLimit(0)).isEqualTo(70);
        assertThat(ChargingControlTile.clampLimit(69)).isEqualTo(70);
        assertThat(ChargingControlTile.clampLimit(70)).isEqualTo(70);
        assertThat(ChargingControlTile.clampLimit(80)).isEqualTo(80);
        assertThat(ChargingControlTile.clampLimit(100)).isEqualTo(100);
        assertThat(ChargingControlTile.clampLimit(120)).isEqualTo(100);
    }
}
