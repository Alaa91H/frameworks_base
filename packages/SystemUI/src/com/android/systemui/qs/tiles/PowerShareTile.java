/*
 * Copyright (C) 2020-2025 The LineageOS Project
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.widget.Switch;

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
import com.android.systemui.statusbar.policy.BatteryController;

import org.lineageos.internal.logging.LineageMetricsLogger;

import vendor.lineage.powershare.IPowerShare;

import javax.inject.Inject;

public class PowerShareTile extends QSTileImpl<BooleanState>
        implements BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "powershare";

    private static final String CHANNEL_ID = TILE_SPEC;
    private static final int NOTIFICATION_ID = 273298;

    @Nullable
    private IPowerShare mPowerShare;
    private final BatteryController mBatteryController;
    private final NotificationManager mNotificationManager;
    private final BatteryManager mBatteryManager;

    @Nullable
    private Notification mNotification;

    @Nullable
    private Icon mIcon;

    private int mMinBatteryLevel;
    private int mBatteryLevel;
    private boolean mCallbackRegistered;

    @Inject
    public PowerShareTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BatteryController batteryController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mBatteryController = batteryController;
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        mBatteryManager = mContext.getSystemService(BatteryManager.class);
        mBatteryLevel = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        mPowerShare = getPowerShare();
        if (mPowerShare != null) {
            ensureNotification();
        }

        mBatteryController.addCallback(this);
        mCallbackRegistered = true;
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mBatteryLevel = level;
        refreshState();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        final IPowerShare powerShare = getPowerShare();
        if (isPowerSave && powerShare != null) {
            try {
                powerShare.setEnabled(false);
            } catch (Exception e) {
                clearPowerShare(powerShare);
                Log.w(TAG, "Unable to disable PowerShare for Battery Saver", e);
            }
        }
        refreshState();
    }

    private void updateNotification(boolean enabled) {
        if (enabled) {
            ensureNotification();
            if (mNotification != null) {
                mNotificationManager.notify(NOTIFICATION_ID, mNotification);
            }
        } else {
            mNotificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private void ensureNotification() {
        if (mNotification != null) {
            return;
        }

        final NotificationChannel notificationChannel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.quick_settings_powershare_label),
                NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(notificationChannel);

        final Notification.Builder builder = new Notification.Builder(mContext, CHANNEL_ID)
                .setContentTitle(
                        mContext.getString(R.string.quick_settings_powershare_enabled_label))
                .setSmallIcon(R.drawable.ic_qs_powershare)
                .setOnlyAlertOnce(true);
        mNotification = builder.build();
        mNotification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        mNotification.visibility = Notification.VISIBILITY_PUBLIC;
    }

    @Override
    public boolean isAvailable() {
        return getPowerShare() != null;
    }

    @Override
    public BooleanState newTileState() {
        final BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        final IPowerShare powerShare = getPowerShare();
        if (powerShare == null || mBatteryController.isPowerSave() || isBatteryTooLow()) {
            refreshState();
            return;
        }

        try {
            powerShare.setEnabled(!powerShare.isEnabled());
        } catch (Exception e) {
            clearPowerShare(powerShare);
            Log.w(TAG, "Unable to change PowerShare state", e);
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_powershare_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_powershare);
        }

        state.icon = mIcon;
        state.label = getTileLabel();
        state.hasLongClickEffect = false;
        state.expandedAccessibilityClassName = Switch.class.getName();

        final IPowerShare powerShare = getPowerShare();
        if (powerShare == null) {
            setUnavailableState(state, R.string.quick_settings_powershare_unavailable);
            updateNotification(false);
            return;
        }

        try {
            state.value = powerShare.isEnabled();
            if (mBatteryController.isPowerSave() && state.value) {
                powerShare.setEnabled(false);
                state.value = false;
            }
        } catch (Exception e) {
            clearPowerShare(powerShare);
            Log.w(TAG, "Unable to read PowerShare state", e);
            setUnavailableState(state, R.string.quick_settings_powershare_unavailable);
            updateNotification(false);
            return;
        }

        updateNotification(state.value);

        if (mBatteryController.isPowerSave()) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_powershare_battery_saver);
        } else if (isBatteryTooLow()) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_powershare_low_battery, mMinBatteryLevel);
        } else {
            state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            state.secondaryLabel = mContext.getString(state.value
                    ? R.string.quick_settings_state_on
                    : R.string.quick_settings_state_off);
        }

        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_POWERSHARE;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (listening) {
            refreshState();
        }
    }

    @Override
    protected void handleDestroy() {
        if (mCallbackRegistered) {
            mBatteryController.removeCallback(this);
            mCallbackRegistered = false;
        }
        super.handleDestroy();
    }

    private void setUnavailableState(BooleanState state, int reasonRes) {
        state.value = false;
        state.state = Tile.STATE_UNAVAILABLE;
        state.secondaryLabel = mContext.getString(reasonRes);
        state.stateDescription = state.secondaryLabel;
        state.contentDescription = state.label + ", " + state.secondaryLabel;
    }

    private boolean isBatteryTooLow() {
        return mBatteryLevel >= 0 && mBatteryLevel < mMinBatteryLevel;
    }

    @Nullable
    private synchronized IPowerShare getPowerShare() {
        if (isPowerShareAlive(mPowerShare)) {
            return mPowerShare;
        }

        mPowerShare = null;
        final String fqName = IPowerShare.DESCRIPTOR + "/default";

        try {
            final IBinder binder = ServiceManager.getService(fqName);
            final IPowerShare powerShare = IPowerShare.Stub.asInterface(binder);
            if (!isPowerShareAlive(powerShare)) {
                return null;
            }
            mPowerShare = powerShare;
            mMinBatteryLevel = readMinBatteryLevel(powerShare);
            ensureNotification();
            return mPowerShare;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get PowerShare service", e);
            return null;
        }
    }

    static boolean isPowerShareAlive(@Nullable IPowerShare powerShare) {
        if (powerShare == null) {
            return false;
        }
        final IBinder binder = powerShare.asBinder();
        return binder != null && binder.isBinderAlive();
    }

    private synchronized void clearPowerShare(IPowerShare powerShare) {
        if (mPowerShare == powerShare) {
            mPowerShare = null;
        }
    }

    private int readMinBatteryLevel(IPowerShare powerShare) {
        try {
            return powerShare.getMinBattery();
        } catch (Exception e) {
            Log.w(TAG, "Unable to read PowerShare minimum battery level", e);
            return 0;
        }
    }
}
