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

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

import javax.inject.Inject;

/**
 * A Quick Settings shortcut for the ROM's existing Lineage Health charging controller.
 *
 * Short press toggles charging control without changing the selected mode/limit.
 * Long press offers the same 70–100% percentage range as LineageParts and switches
 * to limit mode only after the user confirms a new value. Neither path accesses sysfs.
 */
public class ChargingControlTile extends QSTileImpl<BooleanState> {
    public static final String TILE_SPEC = "charging_control";

    private static final String TAG = "ChargingControlTile";
    private static final int MIN_LIMIT = 70;
    private static final int MAX_LIMIT = 100;

    private final Handler mMainHandler;
    private final ContentResolver mResolver;
    private final ContentObserver mSettingsObserver;
    @Nullable private SystemUIDialog mLimitDialog;
    private boolean mObserving;

    @Inject
    public ChargingControlTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mMainHandler = mainHandler;
        mResolver = mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Nullable
    private HealthInterface health() {
        try {
            return HealthInterface.getInstance(mContext);
        } catch (RuntimeException e) {
            Log.w(TAG, "Lineage Health is unavailable", e);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return HealthInterface.isChargingControlSupported(mContext);
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = true;
        return state;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mObserving == listening) return;
        mObserving = listening;
        if (listening) {
            mResolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.CHARGING_CONTROL_ENABLED), false, mSettingsObserver);
            mResolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.CHARGING_CONTROL_MODE), false, mSettingsObserver);
            mResolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.CHARGING_CONTROL_LIMIT), false, mSettingsObserver);
            refreshState();
        } else {
            mResolver.unregisterContentObserver(mSettingsObserver);
        }
    }

    @Override
    protected void handleDestroy() {
        if (mObserving) {
            mResolver.unregisterContentObserver(mSettingsObserver);
            mObserving = false;
        }
        mMainHandler.post(() -> {
            if (mLimitDialog != null) mLimitDialog.dismiss();
            mLimitDialog = null;
        });
        super.handleDestroy();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        HealthInterface health = health();
        if (health == null || !health.isChargingControlSupported()) {
            showError();
            refreshState();
            return;
        }

        boolean expected = !health.getEnabled();
        if (!health.setEnabled(expected) || health.getEnabled() != expected) {
            showError();
        }
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        HealthInterface health = health();
        if (health == null || !health.isChargingControlSupported()) {
            showError();
            return;
        }
        final int currentLimit = clampLimit(health.getLimit());
        mMainHandler.post(() -> showLimitDialog(currentLimit));
    }

    @Override
    @Nullable
    public Intent getLongClickIntent() {
        // Long press opens an inline slider, like the flashlight strength tile.
        return null;
    }

    private void showLimitDialog(int currentLimit) {
        if (mLimitDialog != null && mLimitDialog.isShowing()) return;

        SystemUIDialog dialog = new SystemUIDialog(mContext);
        mLimitDialog = dialog;
        dialog.setTitle(R.string.qs_charging_control_dialog_title);

        int padding = Math.round(24 * mContext.getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(dialog.getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding / 2, padding, padding / 2);

        TextView percentage = new TextView(dialog.getContext());
        percentage.setTextSize(22);
        percentage.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        percentage.setText(mContext.getString(R.string.qs_charging_control_percent, currentLimit));
        container.addView(percentage);

        SeekBar slider = new SeekBar(dialog.getContext());
        slider.setMax(MAX_LIMIT - MIN_LIMIT);
        slider.setProgress(currentLimit - MIN_LIMIT);
        slider.setContentDescription(mContext.getString(R.string.qs_charging_control_dialog_title));
        container.addView(slider);

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                percentage.setText(mContext.getString(
                        R.string.qs_charging_control_percent, MIN_LIMIT + progress));
            }

            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });

        TextView explanation = new TextView(dialog.getContext());
        explanation.setText(R.string.qs_charging_control_mode_notice);
        container.addView(explanation);

        dialog.setView(container);
        dialog.setButton(SystemUIDialog.BUTTON_NEGATIVE,
                mContext.getString(android.R.string.cancel), (d, which) -> {});
        dialog.setButton(SystemUIDialog.BUTTON_POSITIVE,
                mContext.getString(android.R.string.ok), (d, which) -> {
                    int limit = MIN_LIMIT + slider.getProgress();
                    mHandler.post(() -> saveLimit(limit));
                });
        dialog.setOnDismissListener(d -> {
            if (mLimitDialog == dialog) mLimitDialog = null;
        });
        dialog.show();
    }

    private void saveLimit(int limit) {
        HealthInterface health = health();
        if (health == null || !health.isChargingControlSupported()) {
            showError();
            return;
        }
        int value = clampLimit(limit);
        int previousMode = health.getMode();
        int previousLimit = health.getLimit();

        // Set the threshold before selecting limit mode. On failure restore
        // the previous threshold to avoid unexpected mode/limit combinations.
        if (!health.setLimit(value)) {
            showError();
            refreshState();
            return;
        }
        if (previousMode != HealthInterface.MODE_LIMIT
                && !health.setMode(HealthInterface.MODE_LIMIT)) {
            health.setLimit(previousLimit);
            showError();
            refreshState();
            return;
        }

        if (health.getMode() != HealthInterface.MODE_LIMIT || health.getLimit() != value) {
            Log.w(TAG, "Charging limit readback does not match requested value");
            showError();
        }
        refreshState();
    }

    static int clampLimit(int limit) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    private void showError() {
        mMainHandler.post(() -> Toast.makeText(mContext,
                R.string.qs_charging_control_error, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        HealthInterface health = health();
        boolean supported = health != null && health.isChargingControlSupported();
        state.label = mContext.getString(R.string.qs_charging_control_label);
        state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_control);
        state.handlesLongClick = supported;
        state.hasLongClickEffect = supported;
        state.expandedAccessibilityClassName = Switch.class.getName();

        if (!supported) {
            state.value = false;
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = mContext.getString(R.string.qs_charging_control_unavailable);
        } else {
            state.value = health.getEnabled();
            int mode = health.getMode();
            int limit = clampLimit(health.getLimit());
            state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            state.secondaryLabel = mode == HealthInterface.MODE_LIMIT
                    ? mContext.getString(R.string.qs_charging_control_percent, limit)
                    : mContext.getString(R.string.qs_charging_control_other_mode);
        }

        state.contentDescription = state.label + ", " + state.secondaryLabel;
        state.stateDescription = state.secondaryLabel;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_charging_control_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }
}
