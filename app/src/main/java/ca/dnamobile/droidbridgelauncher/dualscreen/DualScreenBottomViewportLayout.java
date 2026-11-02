/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */
package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Owns the actual lower-screen content image bounds.
 *
 * The old 16:9 / 4:3 option only changed an internal HUD safe area, so backgrounds and
 * touch controls still occupied the whole physical display and the setting was barely
 * visible. This container makes the choice apply to the complete lower-screen image:
 * background + HUD + launcher buttons + the real TouchControlsOverlay.
 *
 * Requested behavior:
 * - ordinary displays, 16:9: fit a centered 16:9 image inside the physical display;
 * - ordinary displays, 4:3 option: use a centered short-edge square image
 *   (for example 1920x1080 -> 1080x1080);
 * - AYN Thor: always use the native panel width/height exactly.
 */
final class DualScreenBottomViewportLayout extends FrameLayout {
    private static final String PREFS_NAME = "launcher_preferences";
    private static final String KEY_DUAL_SCREEN_ASPECT_RATIO = "dual_screen_aspect_ratio";

    @Nullable private SharedPreferences preferences;
    @Nullable private View parentView;
    private boolean applyingLayout;

    @NonNull
    private final OnLayoutChangeListener parentLayoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> refreshViewport();

    @NonNull
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if (KEY_DUAL_SCREEN_ASPECT_RATIO.equals(key)) {
                    post(this::refreshViewport);
                }
            };

    DualScreenBottomViewportLayout(@NonNull Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachParentListener();
        preferences = getContext().getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        post(this::refreshViewport);
    }

    @Override
    protected void onDetachedFromWindow() {
        SharedPreferences prefs = preferences;
        preferences = null;
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        detachParentListener();
        super.onDetachedFromWindow();
    }

    void refreshViewport() {
        if (applyingLayout) return;
        attachParentListener();
        View parent = parentView;
        if (parent == null) return;

        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (parentWidth <= 0 || parentHeight <= 0) return;

        final int targetWidth;
        final int targetHeight;
        if (AynThorDisplayCompat.isAynThorDevice(getContext())) {
            targetWidth = parentWidth;
            targetHeight = parentHeight;
        } else if (LauncherPreferences.isDualScreenFourThreeLayout(getContext())) {
            // This intentionally follows the requested compact behavior rather than a
            // mathematical 4:3 crop: a 1920x1080 controls display becomes 1080x1080.
            int side = Math.min(parentWidth, parentHeight);
            targetWidth = side;
            targetHeight = side;
        } else {
            // Real centered 16:9 fit. A native 1920x1080 panel remains 1920x1080.
            float targetAspect = 16f / 9f;
            if ((parentWidth / (float) parentHeight) > targetAspect) {
                targetHeight = parentHeight;
                targetWidth = Math.max(1, Math.round(parentHeight * targetAspect));
            } else {
                targetWidth = parentWidth;
                targetHeight = Math.max(1, Math.round(parentWidth / targetAspect));
            }
        }

        ViewGroup.LayoutParams raw = getLayoutParams();
        FrameLayout.LayoutParams next;
        if (raw instanceof FrameLayout.LayoutParams) {
            next = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) raw);
        } else {
            next = new FrameLayout.LayoutParams(targetWidth, targetHeight);
        }
        next.width = targetWidth;
        next.height = targetHeight;
        next.gravity = Gravity.CENTER;

        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams current = (FrameLayout.LayoutParams) raw;
            if (current.width == next.width
                    && current.height == next.height
                    && current.gravity == next.gravity) {
                return;
            }
        }

        applyingLayout = true;
        try {
            setLayoutParams(next);
        } finally {
            applyingLayout = false;
        }
    }

    private void attachParentListener() {
        ViewParent rawParent = getParent();
        View nextParent = rawParent instanceof View ? (View) rawParent : null;
        if (parentView == nextParent) return;
        detachParentListener();
        parentView = nextParent;
        if (nextParent != null) {
            nextParent.addOnLayoutChangeListener(parentLayoutListener);
        }
    }

    private void detachParentListener() {
        View oldParent = parentView;
        parentView = null;
        if (oldParent != null) {
            oldParent.removeOnLayoutChangeListener(parentLayoutListener);
        }
    }
}
