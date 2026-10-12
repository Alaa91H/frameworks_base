/*
 * Copyright (C) 2026 Evolution X contributors
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

package com.android.systemui.qs.tiles;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression coverage for the LineageParts-compatible charging limit range. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ChargingControlTileTest {
    @Test
    public void minimumChargingLimitIsSeventyPercent() {
        assertThat(ChargingControlTile.clampLimit(0)).isEqualTo(70);
        assertThat(ChargingControlTile.clampLimit(69)).isEqualTo(70);
        assertThat(ChargingControlTile.clampLimit(70)).isEqualTo(70);
    }

    @Test
    public void chargingLimitPreservesValidSelections() {
        assertThat(ChargingControlTile.clampLimit(75)).isEqualTo(75);
        assertThat(ChargingControlTile.clampLimit(80)).isEqualTo(80);
        assertThat(ChargingControlTile.clampLimit(95)).isEqualTo(95);
    }

    @Test
    public void chargingLimitNeverExceedsOneHundredPercent() {
        assertThat(ChargingControlTile.clampLimit(100)).isEqualTo(100);
        assertThat(ChargingControlTile.clampLimit(101)).isEqualTo(100);
        assertThat(ChargingControlTile.clampLimit(Integer.MAX_VALUE)).isEqualTo(100);
    }
}
