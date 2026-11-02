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

package ca.dnamobile.droidbridgelauncher.controls;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;

import ca.dnamobile.droidbridgelauncher.security.AppSignatureVerifier;

/**
 * UI wrapper for the central app-signature verifier.
 */
public final class ControlsMain {
    private ControlsMain() {
    }

    private static final String BLOCK_TITLE = "App integrity check failed";
    private static final String BLOCK_MESSAGE =
            "This copy of DroidBridge Launcher was not signed by DNA Mobile Applications. " +
                    "For your safety, this build is blocked.";
    private static final String CLOSE_BUTTON = "Close";

    public static boolean shouldEnforceSignatureCheck() {
        return AppSignatureVerifier.shouldEnforceSignatureCheck();
    }

    public static boolean isExpectedSignature(@NonNull Context context) {
        return AppSignatureVerifier.isExpectedSignature(context);
    }

    public static boolean blockIfInvalidSignature(@NonNull Activity activity) {
        if (isExpectedSignature(activity)) return false;
        showBlockingDialog(activity);
        return true;
    }

    public static boolean shouldBlockSensitiveAction(@NonNull Context context) {
        return !isExpectedSignature(context);
    }

    public static void throwIfInvalidSignature(@NonNull Context context) {
        AppSignatureVerifier.requireExpectedSignature(context);
    }

    public static boolean toastAndBlockIfInvalidSignature(@NonNull Context context) {
        if (!shouldBlockSensitiveAction(context)) return false;
        Toast.makeText(context, BLOCK_TITLE, Toast.LENGTH_LONG).show();
        return true;
    }

    private static void showBlockingDialog(@NonNull Activity activity) {
        if (activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            if (activity.isFinishing()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

            try {
                LauncherDialogStyle.builder(activity)
                        .setTitle(BLOCK_TITLE)
                        .setMessage(BLOCK_MESSAGE)
                        .setCancelable(false)
                        .setPositiveButton(CLOSE_BUTTON, (dialog, which) -> {
                            dialog.dismiss();
                            closeApp(activity);
                        })
                        .show();
            } catch (Throwable ignored) {
                closeApp(activity);
            }
        });
    }

    private static void closeApp(@NonNull Activity activity) {
        try {
            activity.finishAffinity();
            return;
        } catch (Throwable ignored) {
        }
        try {
            activity.finish();
        } catch (Throwable ignored) {
        }
    }
}
