/*
 * Copyright (C) 2026 Evolution X
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
package com.android.server.power.batterysaver;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Map;

/**
 * Applies Battery Saver actions which do not have an existing PowerSaveState service type.
 *
 * <p>The actions are deliberately reversible. Screen timeout is backed up in Settings.Global so
 * it can be restored even if system_server restarts. The telephony POWER reason is backed up per
 * subscription and restored when full Battery Saver is disabled.</p>
 */
final class BatterySaverCustomActions extends ContentObserver {
    private static final String TAG = "BatterySaverCustom";

    static final String SETTING_DISABLE_5G = "low_power_disable_5g";
    static final String SETTING_SCREEN_TIMEOUT = "low_power_screen_timeout";

    private static final String SETTING_SCREEN_TIMEOUT_BACKUP =
            "low_power_screen_timeout_backup";
    private static final String SETTING_SCREEN_TIMEOUT_BACKUP_USER =
            "low_power_screen_timeout_backup_user";
    private static final String SETTING_5G_BACKUP = "low_power_5g_backup";

    private static final long SCREEN_TIMEOUT_MS = 30_000L;
    private static final long NO_TIMEOUT_BACKUP = -1L;
    private static final int NO_USER = -10_000;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;

    private final ArrayMap<Integer, Long> mPreviousPowerNetworkTypes = new ArrayMap<>();

    private boolean mFullBatterySaverEnabled;

    BatterySaverCustomActions(Context context, Handler handler) {
        super(handler);
        mContext = context;
        mResolver = context.getContentResolver();
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
    }

    void systemReady() {
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_DISABLE_5G), false, this);
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_SCREEN_TIMEOUT), false, this);
        loadNetworkTypeBackups();
    }

    void setFullBatterySaverEnabled(boolean enabled) {
        mFullBatterySaverEnabled = enabled;
        apply();
    }

    @Override
    public void onChange(boolean selfChange) {
        apply();
    }

    private void apply() {
        final boolean disable5g = mFullBatterySaverEnabled
                && Settings.Global.getInt(mResolver, SETTING_DISABLE_5G, 0) != 0;
        final boolean forceScreenTimeout = mFullBatterySaverEnabled
                && Settings.Global.getInt(mResolver, SETTING_SCREEN_TIMEOUT, 0) != 0;

        update5g(disable5g);
        updateScreenTimeout(forceScreenTimeout);
    }

    private void update5g(boolean disable5g) {
        if (mTelephonyManager == null || mSubscriptionManager == null) {
            return;
        }

        if (!disable5g) {
            restoreNetworkTypes();
            return;
        }

        final int[] subscriptionIds;
        try {
            subscriptionIds = mSubscriptionManager.getActiveSubscriptionIdList();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to query active subscriptions", e);
            return;
        }

        boolean backupChanged = false;
        for (int subId : subscriptionIds) {
            final TelephonyManager telephony = mTelephonyManager.createForSubscriptionId(subId);
            try {
                long previous = mPreviousPowerNetworkTypes.containsKey(subId)
                        ? mPreviousPowerNetworkTypes.get(subId)
                        : telephony.getAllowedNetworkTypesForReason(
                                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER);

                if (previous < 0) {
                    continue;
                }

                if (!mPreviousPowerNetworkTypes.containsKey(subId)) {
                    mPreviousPowerNetworkTypes.put(subId, previous);
                    backupChanged = true;
                }

                final long withoutNr =
                        previous & ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
                telephony.setAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER, withoutNr);
            } catch (IllegalArgumentException | IllegalStateException
                    | SecurityException | UnsupportedOperationException e) {
                Slog.w(TAG, "Unable to disable 5G for subscription " + subId, e);
            }
        }

        if (backupChanged) {
            persistNetworkTypeBackups();
        }
    }

    private void restoreNetworkTypes() {
        if (mPreviousPowerNetworkTypes.isEmpty() || mTelephonyManager == null) {
            return;
        }

        final ArrayList<Integer> restored = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : mPreviousPowerNetworkTypes.entrySet()) {
            final int subId = entry.getKey();
            try {
                mTelephonyManager.createForSubscriptionId(subId)
                        .setAllowedNetworkTypesForReason(
                                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER,
                                entry.getValue());
                restored.add(subId);
            } catch (IllegalArgumentException | IllegalStateException
                    | SecurityException | UnsupportedOperationException e) {
                Slog.w(TAG, "Unable to restore network types for subscription " + subId, e);
            }
        }

        for (int subId : restored) {
            mPreviousPowerNetworkTypes.remove(subId);
        }
        persistNetworkTypeBackups();
    }

    private void updateScreenTimeout(boolean forceThirtySeconds) {
        if (forceThirtySeconds) {
            final int userId = ActivityManager.getCurrentUser();
            final long existingBackup = Settings.Global.getLong(
                    mResolver, SETTING_SCREEN_TIMEOUT_BACKUP, NO_TIMEOUT_BACKUP);
            final int backupUser = Settings.Global.getInt(
                    mResolver, SETTING_SCREEN_TIMEOUT_BACKUP_USER, NO_USER);

            if (existingBackup == NO_TIMEOUT_BACKUP || backupUser != userId) {
                final long currentTimeout = Settings.System.getLongForUser(
                        mResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                        SCREEN_TIMEOUT_MS, userId);
                Settings.Global.putLong(
                        mResolver, SETTING_SCREEN_TIMEOUT_BACKUP, currentTimeout);
                Settings.Global.putInt(
                        mResolver, SETTING_SCREEN_TIMEOUT_BACKUP_USER, userId);
            }

            Settings.System.putLongForUser(
                    mResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                    SCREEN_TIMEOUT_MS, userId);
            return;
        }

        final long previousTimeout = Settings.Global.getLong(
                mResolver, SETTING_SCREEN_TIMEOUT_BACKUP, NO_TIMEOUT_BACKUP);
        final int backupUser = Settings.Global.getInt(
                mResolver, SETTING_SCREEN_TIMEOUT_BACKUP_USER, NO_USER);
        if (previousTimeout == NO_TIMEOUT_BACKUP || backupUser == NO_USER) {
            return;
        }

        if (Settings.System.putLongForUser(
                mResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                previousTimeout, backupUser)) {
            Settings.Global.putLong(
                    mResolver, SETTING_SCREEN_TIMEOUT_BACKUP, NO_TIMEOUT_BACKUP);
            Settings.Global.putInt(
                    mResolver, SETTING_SCREEN_TIMEOUT_BACKUP_USER, NO_USER);
        }
    }

    private void loadNetworkTypeBackups() {
        mPreviousPowerNetworkTypes.clear();
        final String serialized = Settings.Global.getString(mResolver, SETTING_5G_BACKUP);
        if (serialized == null || serialized.isEmpty()) {
            return;
        }

        for (String item : serialized.split(";")) {
            final int separator = item.indexOf('=');
            if (separator <= 0 || separator >= item.length() - 1) {
                continue;
            }
            try {
                final int subId = Integer.parseInt(item.substring(0, separator));
                final long mask = Long.parseLong(item.substring(separator + 1));
                mPreviousPowerNetworkTypes.put(subId, mask);
            } catch (NumberFormatException ignored) {
                // Ignore malformed stale entries.
            }
        }
    }

    private void persistNetworkTypeBackups() {
        if (mPreviousPowerNetworkTypes.isEmpty()) {
            Settings.Global.putString(mResolver, SETTING_5G_BACKUP, null);
            return;
        }

        final StringBuilder serialized = new StringBuilder();
        for (Map.Entry<Integer, Long> entry : mPreviousPowerNetworkTypes.entrySet()) {
            if (serialized.length() > 0) {
                serialized.append(';');
            }
            serialized.append(entry.getKey()).append('=').append(entry.getValue());
        }
        Settings.Global.putString(mResolver, SETTING_5G_BACKUP, serialized.toString());
    }
}
