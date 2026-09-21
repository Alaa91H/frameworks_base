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
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

/**
 * Schedules optional automatic shutdown timers for Wi-Fi and Bluetooth.
 *
 * <p>Timeouts are stored in {@link Settings.Global} as milliseconds. A value of {@code 0}
 * disables automatic shutdown. Alarms are scheduled from the moment a radio becomes enabled,
 * or from the moment its timeout is changed while the radio is already enabled.</p>
 */
final class ConnectivityAutoOffController {

    static final String WIFI_AUTO_OFF_TIMEOUT = "wifi_auto_off_timeout";
    static final String BLUETOOTH_AUTO_OFF_TIMEOUT = "bluetooth_auto_off_timeout";

    private static final String TAG = "ConnectivityAutoOff";
    private static final long TIMEOUT_DISABLED = 0L;

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
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
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
                    scheduleWifiAlarm();
                } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                    cancelWifiAlarm();
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    scheduleBluetoothAlarm();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    cancelBluetoothAlarm();
                }
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
        if (mWifiManager != null && mWifiManager.isWifiEnabled()) {
            scheduleWifiAlarm();
        } else {
            cancelWifiAlarm();
        }
    }

    private void updateBluetoothAlarm() {
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            scheduleBluetoothAlarm();
        } else {
            cancelBluetoothAlarm();
        }
    }

    private void scheduleWifiAlarm() {
        cancelWifiAlarm();
        final long timeout = getTimeout(WIFI_AUTO_OFF_TIMEOUT);
        if (timeout <= TIMEOUT_DISABLED || mAlarmManager == null) {
            return;
        }
        mAlarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeout,
                mWifiAlarmIntent);
    }

    private void scheduleBluetoothAlarm() {
        cancelBluetoothAlarm();
        final long timeout = getTimeout(BLUETOOTH_AUTO_OFF_TIMEOUT);
        if (timeout <= TIMEOUT_DISABLED || mAlarmManager == null) {
            return;
        }
        mAlarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeout,
                mBluetoothAlarmIntent);
    }

    private void cancelWifiAlarm() {
        if (mAlarmManager != null) {
            mAlarmManager.cancel(mWifiAlarmIntent);
        }
    }

    private void cancelBluetoothAlarm() {
        if (mAlarmManager != null) {
            mAlarmManager.cancel(mBluetoothAlarmIntent);
        }
    }

    private void handleWifiAlarm() {
        if (mWifiManager == null || !mWifiManager.isWifiEnabled()
                || getTimeout(WIFI_AUTO_OFF_TIMEOUT) <= TIMEOUT_DISABLED) {
            return;
        }
        if (!mWifiManager.setWifiEnabled(false)) {
            Slog.w(TAG, "Failed to disable Wi-Fi after auto-off timeout");
        }
    }

    private void handleBluetoothAlarm() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()
                || getTimeout(BLUETOOTH_AUTO_OFF_TIMEOUT) <= TIMEOUT_DISABLED) {
            return;
        }
        if (!mBluetoothAdapter.disable()) {
            Slog.w(TAG, "Failed to disable Bluetooth after auto-off timeout");
        }
    }

    private long getTimeout(String key) {
        return Settings.Global.getLong(mResolver, key, TIMEOUT_DISABLED);
    }
}
