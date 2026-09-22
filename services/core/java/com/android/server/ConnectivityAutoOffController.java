/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

/**
 * Schedules optional automatic shutdown timers for Wi-Fi and Bluetooth.
 *
 * <p>Timeouts are stored in {@link Settings.Global} as milliseconds. A value of {@code 0}
 * disables automatic shutdown. A timer runs only while the corresponding radio is enabled and
 * idle/disconnected. Active or connecting Wi-Fi/Bluetooth sessions cancel the timer; a new timer
 * starts when the radio becomes idle again.</p>
 */
final class ConnectivityAutoOffController {

    static final String WIFI_AUTO_OFF_TIMEOUT = "wifi_auto_off_timeout";
    static final String BLUETOOTH_AUTO_OFF_TIMEOUT = "bluetooth_auto_off_timeout";

    private static final String TAG = "ConnectivityAutoOff";
    private static final long TIMEOUT_DISABLED = 0L;
    private static final long TIMEOUT_MIN = 15_000L;
    private static final long TIMEOUT_MAX = 8 * 60 * 60 * 1000L;

    private static final String ACTION_WIFI_AUTO_OFF =
            "com.android.server.action.WIFI_AUTO_OFF";
    private static final String ACTION_BLUETOOTH_AUTO_OFF =
            "com.android.server.action.BLUETOOTH_AUTO_OFF";

    private static final int REQUEST_WIFI_AUTO_OFF = 1;
    private static final int REQUEST_BLUETOOTH_AUTO_OFF = 2;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final AlarmManager mAlarmManager;
    private final WifiManager mWifiManager;
    private final BluetoothAdapter mBluetoothAdapter;
    private final PendingIntent mWifiAlarmIntent;
    private final PendingIntent mBluetoothAlarmIntent;

    private long mWifiAlarmAt;
    private long mBluetoothAlarmAt;
    private long mWifiAlarmTimeout;
    private long mBluetoothAlarmTimeout;

    ConnectivityAutoOffController(Context context, Handler handler) {
        mContext = context;
        mResolver = context.getContentResolver();
        mHandler = handler;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mWifiManager = context.getSystemService(WifiManager.class);

        final BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        mBluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        mWifiAlarmIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_WIFI_AUTO_OFF,
                new Intent(ACTION_WIFI_AUTO_OFF).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mBluetoothAlarmIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_BLUETOOTH_AUTO_OFF,
                new Intent(ACTION_BLUETOOTH_AUTO_OFF).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void start() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiverForAllUsers(mStateReceiver, filter, null, mHandler);

        final IntentFilter alarmFilter = new IntentFilter();
        alarmFilter.addAction(ACTION_WIFI_AUTO_OFF);
        alarmFilter.addAction(ACTION_BLUETOOTH_AUTO_OFF);
        mContext.registerReceiverForAllUsers(
                mAlarmReceiver, alarmFilter, Manifest.permission.DUMP, mHandler);

        mResolver.registerContentObserver(
                Settings.Global.getUriFor(WIFI_AUTO_OFF_TIMEOUT),
                false,
                mSettingsObserver);
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(BLUETOOTH_AUTO_OFF_TIMEOUT),
                false,
                mSettingsObserver);

        updateWifiAlarm();
        updateBluetoothAlarm();
    }

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                final int state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    updateWifiAlarm();
                } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                    cancelWifiAlarm();
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                final NetworkInfo networkInfo = intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO, NetworkInfo.class);
                if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
                    cancelWifiAlarm();
                } else {
                    updateWifiAlarm();
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    updateBluetoothAlarm();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    cancelBluetoothAlarm();
                }
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                updateBluetoothAlarm();
            }
        }
    };

    private final BroadcastReceiver mAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_WIFI_AUTO_OFF.equals(action)) {
                handleWifiAlarm();
            } else if (ACTION_BLUETOOTH_AUTO_OFF.equals(action)) {
                handleBluetoothAlarm();
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, android.net.Uri uri) {
            if (Settings.Global.getUriFor(WIFI_AUTO_OFF_TIMEOUT).equals(uri)) {
                updateWifiAlarm();
            } else if (Settings.Global.getUriFor(BLUETOOTH_AUTO_OFF_TIMEOUT).equals(uri)) {
                updateBluetoothAlarm();
            }
        }
    };

    private void updateWifiAlarm() {
        if (mWifiManager != null && mWifiManager.isWifiEnabled()
                && !isWifiConnectedOrConnecting()) {
            scheduleWifiAlarm();
        } else {
            cancelWifiAlarm();
        }
    }

    private void updateBluetoothAlarm() {
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()
                && !isBluetoothConnectedOrConnecting()) {
            scheduleBluetoothAlarm();
        } else {
            cancelBluetoothAlarm();
        }
    }

    private boolean isWifiConnectedOrConnecting() {
        if (mWifiManager == null) {
            return false;
        }

        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            return false;
        }

        final SupplicantState state = wifiInfo.getSupplicantState();
        switch (state) {
            case ASSOCIATING:
            case ASSOCIATED:
            case AUTHENTICATING:
            case FOUR_WAY_HANDSHAKE:
            case GROUP_HANDSHAKE:
            case COMPLETED:
                return true;
            default:
                return false;
        }
    }

    private boolean isBluetoothConnectedOrConnecting() {
        if (mBluetoothAdapter == null) {
            return false;
        }

        final int connectionState = mBluetoothAdapter.getConnectionState();
        return connectionState == BluetoothAdapter.STATE_CONNECTED
                || connectionState == BluetoothAdapter.STATE_CONNECTING;
    }

    private void scheduleWifiAlarm() {
        final long timeout = getTimeout(WIFI_AUTO_OFF_TIMEOUT);
        if (timeout <= TIMEOUT_DISABLED || mAlarmManager == null) {
            cancelWifiAlarm();
            return;
        }

        if (mWifiAlarmAt != 0 && mWifiAlarmTimeout == timeout) {
            return;
        }

        cancelWifiAlarm();
        mWifiAlarmAt = SystemClock.elapsedRealtime() + timeout;
        mWifiAlarmTimeout = timeout;
        mAlarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mWifiAlarmAt,
                mWifiAlarmIntent);
    }

    private void scheduleBluetoothAlarm() {
        final long timeout = getTimeout(BLUETOOTH_AUTO_OFF_TIMEOUT);
        if (timeout <= TIMEOUT_DISABLED || mAlarmManager == null) {
            cancelBluetoothAlarm();
            return;
        }

        if (mBluetoothAlarmAt != 0 && mBluetoothAlarmTimeout == timeout) {
            return;
        }

        cancelBluetoothAlarm();
        mBluetoothAlarmAt = SystemClock.elapsedRealtime() + timeout;
        mBluetoothAlarmTimeout = timeout;
        mAlarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mBluetoothAlarmAt,
                mBluetoothAlarmIntent);
    }

    private void cancelWifiAlarm() {
        if (mAlarmManager != null && mWifiAlarmAt != 0) {
            mAlarmManager.cancel(mWifiAlarmIntent);
        }
        mWifiAlarmAt = 0;
        mWifiAlarmTimeout = 0;
    }

    private void cancelBluetoothAlarm() {
        if (mAlarmManager != null && mBluetoothAlarmAt != 0) {
            mAlarmManager.cancel(mBluetoothAlarmIntent);
        }
        mBluetoothAlarmAt = 0;
        mBluetoothAlarmTimeout = 0;
    }

    private void handleWifiAlarm() {
        mWifiAlarmAt = 0;
        mWifiAlarmTimeout = 0;
        if (mWifiManager == null || !mWifiManager.isWifiEnabled()
                || getTimeout(WIFI_AUTO_OFF_TIMEOUT) <= TIMEOUT_DISABLED) {
            return;
        }
        if (isWifiConnectedOrConnecting()) {
            return;
        }
        if (!mWifiManager.setWifiEnabled(false)) {
            Slog.w(TAG, "Failed to disable Wi-Fi after auto-off timeout");
        }
    }

    private void handleBluetoothAlarm() {
        mBluetoothAlarmAt = 0;
        mBluetoothAlarmTimeout = 0;
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()
                || getTimeout(BLUETOOTH_AUTO_OFF_TIMEOUT) <= TIMEOUT_DISABLED) {
            return;
        }
        if (isBluetoothConnectedOrConnecting()) {
            return;
        }
        if (!mBluetoothAdapter.disable()) {
            Slog.w(TAG, "Failed to disable Bluetooth after auto-off timeout");
        }
    }

    private long getTimeout(String key) {
        final long timeout = Settings.Global.getLong(mResolver, key, TIMEOUT_DISABLED);
        if (timeout == TIMEOUT_DISABLED) {
            return TIMEOUT_DISABLED;
        }
        if (timeout < TIMEOUT_MIN || timeout > TIMEOUT_MAX) {
            Slog.w(TAG, "Ignoring invalid auto-off timeout for " + key + ": " + timeout);
            return TIMEOUT_DISABLED;
        }
        return timeout;
    }
}
