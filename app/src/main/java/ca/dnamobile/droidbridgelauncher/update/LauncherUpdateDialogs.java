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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;

public final class LauncherUpdateDialogs {
    private LauncherUpdateDialogs() {
    }

    public static void checkOnStartup(@NonNull Activity activity) {
        if (!LauncherUpdatePreferences.shouldAutoCheckNow(activity)) return;
        LauncherUpdatePreferences.markAutoCheckedNow(activity);

        // Avoid competing with startup/legal dialogs and keep the launch path responsive.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isActivityAlive(activity)) return;
            checkInternal(activity, false);
        }, 1500L);
    }

    public static void checkManually(@NonNull Activity activity) {
        Toast.makeText(activity, "Checking Google Play for updates...", Toast.LENGTH_SHORT).show();
        checkInternal(activity, true);
    }

    private static void checkInternal(@NonNull Activity activity, boolean manual) {
        LauncherUpdateChecker.checkAsync(activity, new LauncherUpdateChecker.Callback() {
            @Override
            public void onResult(@NonNull LauncherUpdateInfo info) {
                if (!isActivityAlive(activity)) return;
                showUpdateAvailableDialog(activity, info);
            }

            @Override
            public void onNoUpdate(@NonNull LauncherUpdateInfo latestInfo) {
                if (!manual || !isActivityAlive(activity)) return;
                String version = latestInfo.getDisplayVersion();
                Toast.makeText(
                        activity,
                        version.isEmpty()
                                ? "DroidBridge is up to date on Google Play."
                                : "DroidBridge is up to date: " + version,
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(@NonNull String message, @Nullable Throwable throwable) {
                if (!manual || !isActivityAlive(activity)) return;
                LauncherDialogStyle.builder(activity)
                        .setTitle("Google Play update check failed")
                        .setMessage(message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Open Google Play", (dialog, which) -> openPlayStore(activity))
                        .show();
            }
        });
    }

    private static void showUpdateAvailableDialog(@NonNull Activity activity, @NonNull LauncherUpdateInfo info) {
        StringBuilder message = new StringBuilder();
        message.append("Current version: ").append(BuildConfig.VERSION_NAME).append('\n');

        String latest = info.getDisplayVersion();
        if (!latest.isEmpty()) {
            message.append("Google Play: ").append(latest).append("\n\n");
        } else {
            message.append('\n');
        }

        message.append("A newer DroidBridge version is available on Google Play. ")
                .append("You can update now or choose Later and keep using this version.");

        LauncherDialogStyle.builder(activity)
                .setTitle("DroidBridge update available")
                .setMessage(message.toString())
                .setNegativeButton("Later", null)
                .setPositiveButton("Update", (dialog, which) -> openPlayStore(activity))
                .show();
    }

    private static void openPlayStore(@NonNull Activity activity) {
        try {
            Intent marketIntent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(LauncherUpdateChecker.getPlayStoreMarketUrl(activity))
            );
            marketIntent.setPackage("com.android.vending");
            activity.startActivity(marketIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Fall through to the web listing for devices without the Play Store app.
        } catch (Throwable ignored) {
        }

        try {
            activity.startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(LauncherUpdateChecker.getPlayStoreWebUrl(activity))
            ));
        } catch (Throwable ignored) {
            Toast.makeText(activity, "Unable to open DroidBridge on Google Play.", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isActivityAlive(@NonNull Activity activity) {
        if (activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }
}
