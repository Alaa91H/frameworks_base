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
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.power.Mode;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.LocalServices;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * Applies Battery Saver actions which do not have an existing PowerSaveState service type.
 *
 * <p>The actions are deliberately reversible. Values that need direct mutation are backed up in
 * Settings.Global so they can be restored even if system_server restarts while Battery Saver is
 * active.</p>
 */
final class BatterySaverCustomActions extends ContentObserver {
    private static final String TAG = "BatterySaverCustom";

    static final String SETTING_CPU_LIMIT_PERCENT = "low_power_cpu_limit_percent";
    static final String SETTING_DISABLE_5G = "low_power_disable_5g";
    static final String SETTING_SCREEN_TIMEOUT = "low_power_screen_timeout";

    private static final String SETTING_CPU_MAX_FREQ_BACKUP =
            "low_power_cpu_max_freq_backup";
    private static final String SETTING_SCREEN_TIMEOUT_BACKUPS =
            "low_power_screen_timeout_backups";
    // Legacy single-user backup keys, kept only for migration.
    private static final String SETTING_SCREEN_TIMEOUT_BACKUP =
            "low_power_screen_timeout_backup";
    private static final String SETTING_SCREEN_TIMEOUT_BACKUP_USER =
            "low_power_screen_timeout_backup_user";
    private static final String SETTING_5G_BACKUP = "low_power_5g_backup";

    private static final String CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq";
    private static final String FILE_SCALING_MAX_FREQ = "scaling_max_freq";
    private static final String FILE_CPUINFO_MAX_FREQ = "cpuinfo_max_freq";
    private static final String FILE_SCALING_AVAILABLE_FREQUENCIES =
            "scaling_available_frequencies";

    private static final int CPU_LIMIT_MIN_PERCENT = 10;
    private static final int CPU_LIMIT_MAX_PERCENT = 60;
    private static final long NO_TIMEOUT_BACKUP = -1L;
    private static final int NO_USER = -10_000;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;

    private final ArrayMap<String, Long> mPreviousCpuMaxFreqs = new ArrayMap<>();
    private final ArrayMap<String, Long> mAppliedCpuMaxFreqs = new ArrayMap<>();
    private final ArrayMap<Integer, Long> mPreviousPowerNetworkTypes = new ArrayMap<>();
    private final ArrayMap<Integer, Long> mPreviousScreenTimeouts = new ArrayMap<>();

    private final ContentObserver mScreenTimeoutObserver;
    private final SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionsChangedListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    if (mFullBatterySaverEnabled
                            && Settings.Global.getInt(
                                    mResolver, SETTING_DISABLE_5G, 0) != 0) {
                        update5g(true);
                    }
                }
            };
    private final BroadcastReceiver mUserSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateScreenTimeout(getScreenTimeoutOverride());
        }
    };
    private boolean mFullBatterySaverEnabled;

    BatterySaverCustomActions(Context context, Handler handler) {
        super(handler);
        mContext = context;
        mResolver = context.getContentResolver();
        mHandler = handler;
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mScreenTimeoutObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                handleScreenTimeoutChanged();
            }
        };
    }

    void systemReady() {
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_CPU_LIMIT_PERCENT), false, this);
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_DISABLE_5G), false, this);
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_SCREEN_TIMEOUT), false, this);
        mResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false,
                mScreenTimeoutObserver, UserHandle.USER_ALL);
        final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiverForAllUsers(mUserSwitchReceiver, userFilter, null, mHandler);
        if (mSubscriptionManager != null) {
            mSubscriptionManager.addOnSubscriptionsChangedListener(
                    command -> mHandler.post(command), mSubscriptionsChangedListener);
        }
        loadCpuMaxFreqBackups();
        loadNetworkTypeBackups();
        loadScreenTimeoutBackups();
    }

    void prepareForLowPowerTransition() {
        snapshotCpuMaxFreqs();
    }

    void setFullBatterySaverEnabled(boolean enabled) {
        mFullBatterySaverEnabled = enabled;
        apply();
    }

    void reapplyCpuLimit() {
        updateCpuLimit(getCpuLimitOverride());
    }

    @Override
    public void onChange(boolean selfChange) {
        apply();
    }

    @Override
    public void onChange(boolean selfChange, android.net.Uri uri) {
        if (uri == null) {
            apply();
            return;
        }

        if (Settings.Global.getUriFor(SETTING_CPU_LIMIT_PERCENT).equals(uri)) {
            updateCpuLimit(getCpuLimitOverride());
        } else if (Settings.Global.getUriFor(SETTING_DISABLE_5G).equals(uri)) {
            update5g(getDisable5gOverride());
        } else if (Settings.Global.getUriFor(SETTING_SCREEN_TIMEOUT).equals(uri)) {
            updateScreenTimeout(getScreenTimeoutOverride());
        } else {
            apply();
        }
    }

    private void apply() {
        updateCpuLimit(getCpuLimitOverride());
        update5g(getDisable5gOverride());
        updateScreenTimeout(getScreenTimeoutOverride());
    }

    private int getCpuLimitOverride() {
        return mFullBatterySaverEnabled
                ? Settings.Global.getInt(mResolver, SETTING_CPU_LIMIT_PERCENT, -1) : -1;
    }

    private boolean getDisable5gOverride() {
        return mFullBatterySaverEnabled
                && Settings.Global.getInt(mResolver, SETTING_DISABLE_5G, 0) != 0;
    }

    private int getScreenTimeoutOverride() {
        if (!mFullBatterySaverEnabled) {
            return -1;
        }

        final int timeoutMs = Settings.Global.getInt(mResolver, SETTING_SCREEN_TIMEOUT, -1);
        // Compatibility with the first implementation where this setting was a boolean switch.
        return timeoutMs == 1 ? 30_000 : timeoutMs;
    }

    private void snapshotCpuMaxFreqs() {
        final File cpuFreqRoot = new File(CPUFREQ_DIR);
        final File[] policyDirs = cpuFreqRoot.listFiles(
                file -> file.isDirectory() && file.getName().startsWith("policy"));
        if (policyDirs == null || policyDirs.length == 0) {
            return;
        }

        boolean backupChanged = false;
        for (File policyDir : policyDirs) {
            final String policyName = policyDir.getName();
            if (mPreviousCpuMaxFreqs.containsKey(policyName)) {
                continue;
            }

            final File scalingMaxFile = new File(policyDir, FILE_SCALING_MAX_FREQ);
            if (!scalingMaxFile.exists()) {
                continue;
            }

            try {
                final long currentMax = readLong(scalingMaxFile);
                if (currentMax <= 0) {
                    continue;
                }
                mPreviousCpuMaxFreqs.put(policyName, currentMax);
                backupChanged = true;
            } catch (IOException | NumberFormatException e) {
                Slog.w(TAG, "Unable to snapshot CPU max frequency for " + policyDir, e);
            }
        }

        if (backupChanged) {
            persistCpuMaxFreqBackups();
        }
    }

    private void updateCpuLimit(int requestedPercent) {
        if (requestedPercent < CPU_LIMIT_MIN_PERCENT
                || requestedPercent > CPU_LIMIT_MAX_PERCENT) {
            if (mFullBatterySaverEnabled) {
                // If the custom limit is disabled while Battery Saver is still active, remove our
                // cap but keep the original backup for the eventual exit, then let the Power HAL
                // re-assert its normal LOW_POWER behavior.
                restoreCpuMaxFreqs(false);
                reapplyLowPowerMode();
            } else {
                restoreCpuMaxFreqs(true);
            }
            return;
        }

        final int percent = Math.max(CPU_LIMIT_MIN_PERCENT,
                Math.min(CPU_LIMIT_MAX_PERCENT, requestedPercent));
        final File cpuFreqRoot = new File(CPUFREQ_DIR);
        final File[] policyDirs = cpuFreqRoot.listFiles(
                file -> file.isDirectory() && file.getName().startsWith("policy"));
        if (policyDirs == null || policyDirs.length == 0) {
            return;
        }

        boolean backupChanged = false;
        for (File policyDir : policyDirs) {
            final File scalingMaxFile = new File(policyDir, FILE_SCALING_MAX_FREQ);
            if (!scalingMaxFile.exists()) {
                continue;
            }

            try {
                final String policyName = policyDir.getName();
                long previousMax;
                if (mPreviousCpuMaxFreqs.containsKey(policyName)) {
                    previousMax = mPreviousCpuMaxFreqs.get(policyName);
                } else {
                    previousMax = readLong(scalingMaxFile);
                    if (previousMax <= 0) {
                        continue;
                    }
                    mPreviousCpuMaxFreqs.put(policyName, previousMax);
                    backupChanged = true;
                }

                long hardwareMax = readLong(new File(policyDir, FILE_CPUINFO_MAX_FREQ));
                if (hardwareMax <= 0) {
                    hardwareMax = previousMax;
                }

                long target = Math.max(1L, (hardwareMax * percent) / 100L);
                target = chooseAvailableFrequency(policyDir, target);
                // Battery Saver must never raise the cap that existed before it became active.
                target = Math.min(target, previousMax);

                final long currentMax = readLong(scalingMaxFile);
                final Long lastApplied = mAppliedCpuMaxFreqs.get(policyName);
                final boolean stillOwnsCurrentValue =
                        lastApplied != null && currentMax == lastApplied;

                // If another component (for example thermal or the vendor Power HAL) changed the
                // cap after our last write, never raise that newer cap. We may still lower it if
                // the configured Battery Saver cap is more restrictive.
                if (!stillOwnsCurrentValue && currentMax > 0) {
                    target = Math.min(target, currentMax);
                }

                if (currentMax != target) {
                    FileUtils.stringToFile(scalingMaxFile, Long.toString(target));
                    mAppliedCpuMaxFreqs.put(policyName, target);
                } else if (!stillOwnsCurrentValue) {
                    // The current value belongs to another component; don't claim ownership just
                    // because it happens to satisfy our requested cap.
                    mAppliedCpuMaxFreqs.remove(policyName);
                }
            } catch (IOException | NumberFormatException e) {
                Slog.w(TAG, "Unable to cap CPU frequency for " + policyDir, e);
            }
        }

        if (backupChanged) {
            persistCpuMaxFreqBackups();
        }
    }

    private long chooseAvailableFrequency(File policyDir, long target) throws IOException {
        final File availableFile = new File(policyDir, FILE_SCALING_AVAILABLE_FREQUENCIES);
        if (!availableFile.exists()) {
            return target;
        }

        final String contents = FileUtils.readTextFile(availableFile, 0, null).trim();
        if (contents.isEmpty()) {
            return target;
        }

        long bestAtOrBelow = -1L;
        long lowest = Long.MAX_VALUE;
        for (String token : contents.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            final long frequency;
            try {
                frequency = Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (frequency <= 0) {
                continue;
            }
            lowest = Math.min(lowest, frequency);
            if (frequency <= target) {
                bestAtOrBelow = Math.max(bestAtOrBelow, frequency);
            }
        }

        if (bestAtOrBelow > 0) {
            return bestAtOrBelow;
        }
        return lowest != Long.MAX_VALUE ? lowest : target;
    }

    private void restoreCpuMaxFreqs(boolean clearBackup) {
        if (mPreviousCpuMaxFreqs.isEmpty()) {
            mAppliedCpuMaxFreqs.clear();
            return;
        }

        final ArrayList<String> completed = new ArrayList<>();
        for (Map.Entry<String, Long> entry : mPreviousCpuMaxFreqs.entrySet()) {
            final String policyName = entry.getKey();
            final File scalingMaxFile = new File(
                    new File(CPUFREQ_DIR, policyName), FILE_SCALING_MAX_FREQ);
            try {
                if (!scalingMaxFile.exists()) {
                    if (clearBackup) {
                        completed.add(policyName);
                    }
                    mAppliedCpuMaxFreqs.remove(policyName);
                    continue;
                }

                final Long appliedMax = mAppliedCpuMaxFreqs.get(policyName);
                if (appliedMax == null) {
                    // We no longer own the current value. On final exit, discard the stale backup
                    // without overwriting a value managed by another component.
                    if (clearBackup) {
                        completed.add(policyName);
                    }
                    continue;
                }

                final long currentMax = readLong(scalingMaxFile);
                if (currentMax != appliedMax) {
                    Slog.i(TAG, "Skipping CPU max restore for " + policyName
                            + "; current value changed from our applied cap");
                    mAppliedCpuMaxFreqs.remove(policyName);
                    if (clearBackup) {
                        completed.add(policyName);
                    }
                    continue;
                }

                FileUtils.stringToFile(scalingMaxFile, Long.toString(entry.getValue()));
                mAppliedCpuMaxFreqs.remove(policyName);
                if (clearBackup) {
                    completed.add(policyName);
                }
            } catch (IOException | NumberFormatException e) {
                Slog.w(TAG, "Unable to restore CPU max frequency for " + policyName, e);
            }
        }

        if (clearBackup) {
            for (String policy : completed) {
                mPreviousCpuMaxFreqs.remove(policy);
            }
            persistCpuMaxFreqBackups();
        }
    }

    private void reapplyLowPowerMode() {
        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi != null) {
            pmi.setPowerMode(Mode.LOW_POWER, true);
        }
    }

    private long readLong(File file) throws IOException, NumberFormatException {
        if (!file.exists()) {
            return -1L;
        }
        return Long.parseLong(FileUtils.readTextFile(file, 0, null).trim());
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

    private void updateScreenTimeout(int timeoutMs) {
        if (timeoutMs != 15_000 && timeoutMs != 30_000) {
            restoreScreenTimeout();
            return;
        }

        final int userId = ActivityManager.getCurrentUser();
        if (!mPreviousScreenTimeouts.containsKey(userId)) {
            final long currentTimeout = Settings.System.getLongForUser(
                    mResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs, userId);
            mPreviousScreenTimeouts.put(userId, currentTimeout);
            persistScreenTimeoutBackups();
        }

        Settings.System.putLongForUser(
                mResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs, userId);
    }

    private void handleScreenTimeoutChanged() {
        if (!mFullBatterySaverEnabled) {
            return;
        }

        final int timeoutMs = getScreenTimeoutOverride();
        if (timeoutMs != 15_000 && timeoutMs != 30_000) {
            return;
        }

        final int userId = ActivityManager.getCurrentUser();
        final long currentTimeout = Settings.System.getLongForUser(
                mResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs, userId);
        if (currentTimeout == timeoutMs) {
            return;
        }

        // Preserve changes made while Battery Saver is active so they become the normal
        // timeout after Battery Saver exits, then keep the temporary saver timeout applied.
        mPreviousScreenTimeouts.put(userId, currentTimeout);
        persistScreenTimeoutBackups();
        Settings.System.putLongForUser(
                mResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs, userId);
    }

    private void restoreScreenTimeout() {
        if (mPreviousScreenTimeouts.isEmpty()) {
            return;
        }

        final ArrayList<Integer> restored = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : mPreviousScreenTimeouts.entrySet()) {
            if (Settings.System.putLongForUser(
                    mResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                    entry.getValue(), entry.getKey())) {
                restored.add(entry.getKey());
            }
        }

        for (int userId : restored) {
            mPreviousScreenTimeouts.remove(userId);
        }
        persistScreenTimeoutBackups();
    }

    private void loadScreenTimeoutBackups() {
        mPreviousScreenTimeouts.clear();

        final String serialized = Settings.Global.getString(
                mResolver, SETTING_SCREEN_TIMEOUT_BACKUPS);
        if (serialized != null && !serialized.isEmpty()) {
            for (String item : serialized.split(";")) {
                final int separator = item.indexOf('=');
                if (separator <= 0 || separator >= item.length() - 1) {
                    continue;
                }
                try {
                    final int userId = Integer.parseInt(item.substring(0, separator));
                    final long timeout = Long.parseLong(item.substring(separator + 1));
                    if (timeout >= 0) {
                        mPreviousScreenTimeouts.put(userId, timeout);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed stale entries.
                }
            }
        }

        final long legacyTimeout = Settings.Global.getLong(
                mResolver, SETTING_SCREEN_TIMEOUT_BACKUP, NO_TIMEOUT_BACKUP);
        final int legacyUser = Settings.Global.getInt(
                mResolver, SETTING_SCREEN_TIMEOUT_BACKUP_USER, NO_USER);
        if (legacyTimeout != NO_TIMEOUT_BACKUP && legacyUser != NO_USER
                && !mPreviousScreenTimeouts.containsKey(legacyUser)) {
            mPreviousScreenTimeouts.put(legacyUser, legacyTimeout);
        }

        if (legacyTimeout != NO_TIMEOUT_BACKUP || legacyUser != NO_USER) {
            Settings.Global.putLong(
                    mResolver, SETTING_SCREEN_TIMEOUT_BACKUP, NO_TIMEOUT_BACKUP);
            Settings.Global.putInt(
                    mResolver, SETTING_SCREEN_TIMEOUT_BACKUP_USER, NO_USER);
            persistScreenTimeoutBackups();
        }
    }

    private void persistScreenTimeoutBackups() {
        if (mPreviousScreenTimeouts.isEmpty()) {
            Settings.Global.putString(mResolver, SETTING_SCREEN_TIMEOUT_BACKUPS, null);
            return;
        }

        final StringBuilder serialized = new StringBuilder();
        for (Map.Entry<Integer, Long> entry : mPreviousScreenTimeouts.entrySet()) {
            if (serialized.length() > 0) {
                serialized.append(';');
            }
            serialized.append(entry.getKey()).append('=').append(entry.getValue());
        }
        Settings.Global.putString(
                mResolver, SETTING_SCREEN_TIMEOUT_BACKUPS, serialized.toString());
    }

    private void loadCpuMaxFreqBackups() {
        mPreviousCpuMaxFreqs.clear();
        final String serialized = Settings.Global.getString(
                mResolver, SETTING_CPU_MAX_FREQ_BACKUP);
        if (serialized == null || serialized.isEmpty()) {
            return;
        }

        for (String item : serialized.split(";")) {
            final int separator = item.indexOf('=');
            if (separator <= 0 || separator >= item.length() - 1) {
                continue;
            }
            try {
                final String policy = item.substring(0, separator);
                final long maxFreq = Long.parseLong(item.substring(separator + 1));
                mPreviousCpuMaxFreqs.put(policy, maxFreq);
            } catch (NumberFormatException ignored) {
                // Ignore malformed stale entries.
            }
        }
    }

    private void persistCpuMaxFreqBackups() {
        if (mPreviousCpuMaxFreqs.isEmpty()) {
            Settings.Global.putString(mResolver, SETTING_CPU_MAX_FREQ_BACKUP, null);
            return;
        }

        final StringBuilder serialized = new StringBuilder();
        for (Map.Entry<String, Long> entry : mPreviousCpuMaxFreqs.entrySet()) {
            if (serialized.length() > 0) {
                serialized.append(';');
            }
            serialized.append(entry.getKey()).append('=').append(entry.getValue());
        }
        Settings.Global.putString(
                mResolver, SETTING_CPU_MAX_FREQ_BACKUP, serialized.toString());
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
