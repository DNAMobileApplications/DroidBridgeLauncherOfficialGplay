/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public final class LauncherUpdatePreferences {
    private static final String PREFS = "launcher_update_checker";
    private static final String KEY_AUTO_CHECK_ENABLED = "auto_check_enabled";
    private static final String KEY_LAST_AUTO_CHECK_MS = "last_auto_check_ms";

    /** Check at most once every 12 hours on app start. */
    public static final long AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private LauncherUpdatePreferences() {
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAutoCheckEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CHECK_ENABLED, true);
    }

    public static void setAutoCheckEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK_ENABLED, enabled).apply();
    }

    public static boolean shouldAutoCheckNow(@NonNull Context context) {
        if (!isAutoCheckEnabled(context)) return false;
        long last = prefs(context).getLong(KEY_LAST_AUTO_CHECK_MS, 0L);
        long now = System.currentTimeMillis();
        return last <= 0L || now - last >= AUTO_CHECK_INTERVAL_MS;
    }

    public static void markAutoCheckedNow(@NonNull Context context) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_CHECK_MS, System.currentTimeMillis()).apply();
    }
}
