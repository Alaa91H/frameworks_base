/*
 * SPDX-FileCopyrightText: 2026 Evolution X contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.tiles;

import android.app.ActivityManager;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.ContextThemeWrapper;

import com.google.android.material.slider.Slider;
import android.widget.TextView;

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
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

import javax.inject.Inject;

/**
 * Controls the existing Lineage Health charging limit. Does not access sysfs or
 * invent a second charging policy. The tile is absent if the service is unsupported.
 */
public class ChargingControlTile extends SecureQSTile<BooleanState> {
    public static final String TILE_SPEC = "charge_control";
    private static final String TAG = "ChargingControlTile";
    private static final String CHARGING_CONTROL_SETTINGS =
            "org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS";
    private static final int MIN_LIMIT = 70;
    private static final int MAX_LIMIT = 100;

    private final Handler mMainHandler;
    private final SystemUIDialog.Factory mDialogFactory;
    private final KeyguardStateController mKeyguardStateController;
    private final ContentObserver mSettingObserver;
    private boolean mListening;

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
            KeyguardStateController keyguardStateController,
            SystemUIDialog.Factory dialogFactory) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
                metricsLogger, statusBarStateController, activityStarter, qsLogger,
                keyguardStateController);
        mMainHandler = mainHandler;
        mDialogFactory = dialogFactory;
        mKeyguardStateController = keyguardStateController;
        mSettingObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Nullable
    private HealthInterface health() {
        try {
            HealthInterface health = HealthInterface.getInstance(mContext);
            return health.isChargingControlSupported() ? health : null;
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Lineage charging control unavailable", e);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return health() != null;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = true;
        state.hasLongClickEffect = true;
        return state;
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        if (mListening) {
            unregisterSettings();
            registerSettings();
        }
        refreshState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) registerSettings();
        else unregisterSettings();
    }

    private void registerSettings() {
        mContext.getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(
                        LineageSettings.System.CHARGING_CONTROL_ENABLED),
                false, mSettingObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(
                        LineageSettings.System.CHARGING_CONTROL_MODE),
                false, mSettingObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(
                        LineageSettings.System.CHARGING_CONTROL_LIMIT),
                false, mSettingObserver, UserHandle.USER_ALL);
    }

    private void unregisterSettings() {
        mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
    }

    @Override
    protected void handleDestroy() {
        if (mListening) {
            unregisterSettings();
            mListening = false;
        }
        super.handleDestroy();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable, boolean keyguardShowing) {
        if (checkKeyguard(expandable, keyguardShowing)
                || ActivityManager.isUserAMonkey()) return;

        HealthInterface controller = health();
        if (controller == null) {
            refreshState();
            return;
        }
        try {
            boolean expected = !controller.getEnabled();
            if (!controller.setEnabled(expected) || controller.getEnabled() != expected) {
                Log.w(TAG, "Charging control rejected enable=" + expected);
                showOperationFailed();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to toggle charging control", e);
            showOperationFailed();
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(CHARGING_CONTROL_SETTINGS)
                .addCategory(Intent.CATEGORY_DEFAULT);
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        if (ActivityManager.isUserAMonkey()) return;
        // Don't expose the limit dialog behind the secure lock screen.
        if (mKeyguardStateController.isShowing()
                && mKeyguardStateController.isMethodSecure()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                    showLimitDialogOrSettings());
        } else {
            showLimitDialogOrSettings();
        }
    }

    private void showLimitDialogOrSettings() {
        HealthInterface controller = health();
        if (controller == null) return;

        final int selectedMode;
        try {
            selectedMode = controller.getMode();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to read charging control mode", e);
            refreshState();
            return;
        }
        if (selectedMode != HealthInterface.MODE_LIMIT) {
            // Avoid changing modes implicitly: not every vendor health HAL
            // supports MODE_LIMIT even when some charging control is available.
            super.handleLongClick(null);
            return;
        }

        mMainHandler.post(() -> {
            HealthInterface current = health();
            if (current == null) return;
            final int currentLimit;
            try {
                if (current.getMode() != HealthInterface.MODE_LIMIT) return;
                currentLimit = current.getLimit();
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to read charging limit", e);
                return;
            }
            final SystemUIDialog dialog = mDialogFactory.create();
            dialog.setTitle(R.string.quick_settings_charge_control_limit_title);

            LinearLayout layout = new LinearLayout(dialog.getContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (24 * mContext.getResources()
                    .getDisplayMetrics().density + 0.5f);
            layout.setPadding(padding, padding / 2, padding, padding / 2);

            TextView value = new TextView(dialog.getContext());
            value.setTextSize(24);
            value.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            layout.addView(value);

            // Use the same Material 3 slider visual treatment as the flashlight
            // intensity dialog. Don't change the HAL while dragging.
            ContextThemeWrapper themedContext = new ContextThemeWrapper(
                    dialog.getContext(),
                    com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight);
            Slider slider = new Slider(themedContext);
            slider.setValueFrom(MIN_LIMIT);
            slider.setValueTo(MAX_LIMIT);
            slider.setStepSize(1f);
            slider.setTickVisible(false);
            slider.setLabelFormatter(level -> Math.round(level) + "%");
            int start = clampLimit(currentLimit);
            slider.setValue(start);
            value.setText(start + "%");
            slider.addOnChangeListener((bar, progress, fromUser) ->
                    value.setText(Math.round(progress) + "%"));
            layout.addView(slider);
            dialog.setView(layout);

            dialog.setPositiveButton(R.string.quick_settings_done, (d, which) -> {
                HealthInterface latest = health();
                if (latest == null) return;
                int selected = clampLimit(Math.round(slider.getValue()));
                try {
                    if (latest.getMode() != HealthInterface.MODE_LIMIT
                            || !latest.setLimit(selected)
                            || latest.getLimit() != selected) {
                        Log.w(TAG, "Charging limit write was not accepted: " + selected);
                        showOperationFailed();
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "Unable to update charging limit", e);
                    showOperationFailed();
                }
                refreshState();
            });
            dialog.setNegativeButton(android.R.string.cancel, null);
            dialog.show();
        });
    }

    private void showOperationFailed() {
        mMainHandler.post(() -> Toast.makeText(mContext,
                R.string.quick_settings_charge_control_failed, Toast.LENGTH_SHORT).show());
    }

    static int clampLimit(int limit) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_charge_control_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        HealthInterface controller = health();
        boolean available = controller != null;
        boolean enabled = false;
        int mode = HealthInterface.MODE_NONE;
        int currentLimit = 0;
        if (available) {
            try {
                enabled = controller.getEnabled();
                mode = controller.getMode();
                if (mode == HealthInterface.MODE_LIMIT) {
                    currentLimit = controller.getLimit();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to read charging control state", e);
                available = false;
            }
        }

        state.value = available && enabled;
        state.state = !available ? Tile.STATE_UNAVAILABLE
                : enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.handlesLongClick = available;
        state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_charge_control);
        state.label = getTileLabel();

        if (!available) {
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_charge_control_unavailable);
        } else if (mode == HealthInterface.MODE_LIMIT) {
            state.secondaryLabel = (enabled
                    ? mContext.getString(R.string.quick_settings_charge_control_enabled)
                    : mContext.getString(R.string.quick_settings_charge_control_disabled))
                    + " · " + currentLimit + "%";
        } else {
            state.secondaryLabel = mContext.getString(
                    enabled ? R.string.quick_settings_charge_control_other_mode
                            : R.string.quick_settings_charge_control_disabled);
        }
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }
}
