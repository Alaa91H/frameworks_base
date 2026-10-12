/*
 * Copyright (C) 2026 Evolution X
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.dagger;

import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.ChargingControlTile;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

/** Registers charging control with the legacy QS factory and tile picker. */
@Module
public abstract class ChargingControlTileModule {
    @Binds
    @IntoMap
    @StringKey(ChargingControlTile.TILE_SPEC)
    abstract QSTileImpl<?> bindChargingControlTile(ChargingControlTile tile);
}
