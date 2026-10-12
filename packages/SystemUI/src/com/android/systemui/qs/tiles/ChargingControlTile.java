/*
 * Copyright (C) 2026 Evolution X
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

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

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
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
 * Charging-control QS tile. Uses the ROM's own Lineage Health service instead
 * of writing battery sysfs nodes or creating an independent charge controller.
 *
 * Tap: toggle the existing charging-control setting.
 * Long press: open a percentage slider and select limit mode on confirmation.
 * Both paths preserve the hardware-specific health HAL's safety behavior.
 */
public class ChargingControlTile extends QSTileImpl<BooleanState> {
    public static final String TILE_SPEC = "charging_control";

    private static final String TAG = "ChargingControlTile";
    static final int MIN_CHARGE_LIMIT = 70;
    static final int MAX_CHARGE_LIMIT = 100;
    private static final String SETTINGS_ACTION =
            "org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS";

    private final Handler mMainHandler;
    private final SystemUIDialog.Factory mDialogFactory;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final ContentObserver mSettingsObserver;
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
            QSLogger qsLogger,
            SystemUIDialog.Factory dialogFactory,
            DialogTransitionAnimator dialogTransitionAnimator
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
                metricsLogger, statusBarStateController, activityStarter, qsLogger);
        mMainHandler = mainHandler;
        mDialogFactory = dialogFactory;
        mDialogTransitionAnimator = dialogTransitionAnimator;

        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Override
    public boolean isAvailable() {
        try {
            return HealthInterface.isChargingControlSupported(mContext);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Charging control service is unavailable", exception);
            return false;
        }
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
        if (mObserving == listening) {
            return;
        }
        mObserving = listening;
        ContentResolver resolver = mContext.getContentResolver();
        if (listening) {
            resolver.registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_ENABLED),
                    false, mSettingsObserver);
            resolver.registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_MODE),
                    false, mSettingsObserver);
            resolver.registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.CHARGING_CONTROL_LIMIT),
                    false, mSettingsObserver);
            refreshState();
        } else {
            resolver.unregisterContentObserver(mSettingsObserver);
        }
    }

    @Override
    protected void handleDestroy() {
        if (mObserving) {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            mObserving = false;
        }
        super.handleDestroy();
    }

    @Nullable
    private HealthInterface health() {
        if (!isAvailable()) return null;
        try {
            return HealthInterface.getInstance(mContext);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to connect to charging control service", exception);
            return null;
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        HealthInterface health = health();
        if (health == null) return;

        try {
            final boolean wanted = !health.getEnabled();
            if (!health.setEnabled(wanted) || health.getEnabled() != wanted) {
                Log.w(TAG, "Charging control rejected enable state " + wanted);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Charging control toggle failed", exception);
        }
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        HealthInterface health = health();
        if (health == null) return;

        final int currentLimit;
        try {
            currentLimit = clampLimit(health.getLimit());
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read charge limit", exception);
            return;
        }
        // QS events run on a background looper; dialogs must be built on main.
        mMainHandler.post(() -> showLimitDialog(expandable, currentLimit));
    }

    private void showLimitDialog(@Nullable Expandable expandable, int initialLimit) {
        if (!isAvailable()) return;

        SystemUIDialog dialog = mDialogFactory.create();
        dialog.setTitle(R.string.quick_settings_charging_control_dialog_title);

        final int padding = (int) (20 * mContext.getResources()
                .getDisplayMetrics().density + 0.5f);
        LinearLayout layout = new LinearLayout(mContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        TextView percent = new TextView(mContext);
        percent.setTextSize(24);
        percent.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        layout.addView(percent);

        SeekBar seekBar = new SeekBar(mContext);
        seekBar.setMax(MAX_CHARGE_LIMIT - MIN_CHARGE_LIMIT);
        seekBar.setProgress(initialLimit - MIN_CHARGE_LIMIT);
        seekBar.setContentDescription(mContext.getString(
                R.string.quick_settings_charging_control_dialog_title));
        layout.addView(seekBar);
        percent.setText(mContext.getString(
                R.string.quick_settings_charging_control_limit, initialLimit));

        TextView hint = new TextView(mContext);
        hint.setText(R.string.quick_settings_charging_control_dialog_hint);
        layout.addView(hint);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                percent.setText(mContext.getString(
                        R.string.quick_settings_charging_control_limit,
                        MIN_CHARGE_LIMIT + progress));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        dialog.setView(layout);
        dialog.setPositiveButton(android.R.string.ok, (ignored, which) -> {
            final int requested = MIN_CHARGE_LIMIT + seekBar.getProgress();
            // Do binder I/O off the UI thread; keep the enabled state unchanged.
            mHandler.post(() -> applyChargeLimit(requested));
        });
        dialog.setNegativeButton(android.R.string.cancel, null);

        if (expandable != null) {
            DialogTransitionAnimator.Controller controller =
                    expandable.dialogTransitionController(new DialogCuj(
                            InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, TILE_SPEC));
            if (controller != null) {
                mDialogTransitionAnimator.show(dialog, controller);
                return;
            }
        }
        dialog.show();
    }

    static int clampLimit(int limit) {
        return Math.max(MIN_CHARGE_LIMIT, Math.min(MAX_CHARGE_LIMIT, limit));
    }

    private void applyChargeLimit(int requested) {
        HealthInterface health = health();
        if (health == null || requested < MIN_CHARGE_LIMIT
                || requested > MAX_CHARGE_LIMIT) return;

        try {
            final int oldMode = health.getMode();
            final int oldLimit = health.getLimit();
            // Set limit mode first so the selected percentage is meaningful.
            if (oldMode != HealthInterface.MODE_LIMIT
                    && !health.setMode(HealthInterface.MODE_LIMIT)) {
                Log.w(TAG, "Limit mode is not supported by this device");
                refreshState();
                return;
            }
            if (!health.setLimit(requested) || health.getLimit() != requested) {
                Log.w(TAG, "Charge limit " + requested + "% was rejected");
                if (oldLimit >= 0 && oldLimit <= 100) health.setLimit(oldLimit);
                if (oldMode != HealthInterface.MODE_LIMIT) health.setMode(oldMode);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to configure charging limit", exception);
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        // The stock settings screen remains available as a fallback and via search.
        return new Intent(SETTINGS_ACTION).setPackage("org.lineageos.lineageparts");
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_charging_control_label);
    }

    @Override
    public int getMetricsCategory() {
        return com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = getTileLabel();
        state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_control);
        state.handlesLongClick = true;
        state.hasLongClickEffect = true;
        state.expandedAccessibilityClassName = Switch.class.getName();

        HealthInterface health = health();
        if (health == null) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.value = false;
            state.secondaryLabel =
                    mContext.getString(R.string.quick_settings_charging_control_unavailable);
            state.contentDescription = state.label;
            return;
        }

        try {
            state.value = health.getEnabled();
            int mode = health.getMode();
            if (mode == HealthInterface.MODE_LIMIT) {
                int limit = clampLimit(health.getLimit());
                state.secondaryLabel = mContext.getString(
                        R.string.quick_settings_charging_control_limit, limit);
                // Compact QS layouts can hide the secondary label. Keep the
                // selected charge cap visible in the primary label while on.
                if (state.value) {
                    state.label = mContext.getString(
                            R.string.quick_settings_charging_control_active_limit, limit);
                }
            } else if (mode == HealthInterface.MODE_AUTO) {
                state.secondaryLabel =
                        mContext.getString(R.string.quick_settings_charging_control_automatic);
            } else {
                state.secondaryLabel =
                        mContext.getString(R.string.quick_settings_charging_control_scheduled);
            }
            state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            state.contentDescription = state.label + ", " + state.secondaryLabel
                    + ", " + mContext.getString(state.value
                        ? R.string.quick_settings_charging_control_on
                        : R.string.quick_settings_charging_control_off);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read charging control state", exception);
            state.state = Tile.STATE_UNAVAILABLE;
            state.value = false;
            state.secondaryLabel =
                    mContext.getString(R.string.quick_settings_charging_control_unavailable);
            state.contentDescription = state.label;
        }
    }
}
