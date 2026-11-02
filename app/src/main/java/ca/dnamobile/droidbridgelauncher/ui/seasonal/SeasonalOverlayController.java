/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * Seasonal launcher animation support for DroidBridge.
 */
package ca.dnamobile.droidbridgelauncher.ui.seasonal;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.util.Calendar;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Owns the lightweight continuous seasonal layer shown only on MainActivity's
 * instance-list screen. The caller supplies the instance-screen root explicitly so
 * the effect cannot accidentally attach to SplashActivity, Settings, or GameActivity.
 */
public final class SeasonalOverlayController {
    private static final String TAG = "SeasonalOverlay";
    private static final String OVERLAY_TAG = "droidbridge_seasonal_animation";

    private SeasonalOverlayController() {
    }

    /**
     * Legacy entry point retained because SplashActivity in older trees calls it.
     * Continuous seasonal artwork is intentionally NOT installed from SplashActivity.
     */
    public static void install(@NonNull Activity activity) {
        // Intentionally empty. MainActivity owns the seasonal layer explicitly.
    }

    /**
     * Attach or refresh the month-appropriate seasonal layer above the instance screen.
     */
    public static void showOnInstanceScreen(
            @NonNull Activity activity,
            @NonNull ViewGroup instanceScreenRoot
    ) {
        if (activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed())) {
            return;
        }

        if (!LauncherPreferences.isSeasonalAnimationsEnabled(activity)) {
            removeOverlay(instanceScreenRoot);
            return;
        }

        final SeasonalAnimationView.Theme theme = resolveTheme(
                Calendar.getInstance().get(Calendar.MONTH));

        instanceScreenRoot.post(() -> {
            if (activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    && activity.isDestroyed())) {
                return;
            }
            if (!LauncherPreferences.isSeasonalAnimationsEnabled(activity)) {
                removeOverlay(instanceScreenRoot);
                return;
            }

            final float overlayAlpha = LauncherPreferences
                    .getSeasonalAnimationsOpacityPercent(activity) / 100f;

            View existing = instanceScreenRoot.findViewWithTag(OVERLAY_TAG);
            if (existing instanceof SeasonalAnimationView
                    && ((SeasonalAnimationView) existing).getTheme() == theme) {
                existing.setAlpha(overlayAlpha);
                existing.bringToFront();
                return;
            }

            removeOverlay(instanceScreenRoot);

            try {
                SeasonalAnimationView overlay = new SeasonalAnimationView(activity, theme);
                overlay.setTag(OVERLAY_TAG);
                overlay.setClickable(false);
                overlay.setFocusable(false);
                overlay.setFocusableInTouchMode(false);
                overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                overlay.setAlpha(overlayAlpha);
                overlay.setElevation(dp(activity, 8f));

                instanceScreenRoot.addView(
                        overlay,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )
                );
                overlay.bringToFront();
                Logging.i(TAG, "Instance-screen seasonal animation active: "
                        + theme.name().toLowerCase() + " opacity="
                        + LauncherPreferences.getSeasonalAnimationsOpacityPercent(activity) + "%");
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to attach instance-screen seasonal animation", throwable);
            }
        });
    }

    /** Remove the effect as soon as the instance-list Activity is no longer visible. */
    public static void hideFromInstanceScreen(@NonNull ViewGroup instanceScreenRoot) {
        removeOverlay(instanceScreenRoot);
    }

    /**
     * Backward-compatible generic method for old MainActivity source. New code should call
     * showOnInstanceScreen() with binding.mainContent so the ownership is explicit.
     */
    public static void maybeShow(@NonNull Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            showOnInstanceScreen(activity, (ViewGroup) root);
        }
    }

    public static void dismiss(@NonNull Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            removeOverlay((ViewGroup) root);
        }
    }

    private static void removeOverlay(@NonNull ViewGroup parent) {
        View overlay = parent.findViewWithTag(OVERLAY_TAG);
        if (overlay != null) parent.removeView(overlay);
    }

    @NonNull
    private static SeasonalAnimationView.Theme resolveTheme(int month) {
        switch (month) {
            case Calendar.OCTOBER:
                return SeasonalAnimationView.Theme.HALLOWEEN;
            case Calendar.NOVEMBER:
            case Calendar.JANUARY:
            case Calendar.FEBRUARY:
            case Calendar.MARCH:
                return SeasonalAnimationView.Theme.SNOW;
            case Calendar.DECEMBER:
                return SeasonalAnimationView.Theme.CHRISTMAS;
            case Calendar.APRIL:
            case Calendar.MAY:
            case Calendar.JUNE:
                return SeasonalAnimationView.Theme.RAIN;
            case Calendar.JULY:
            case Calendar.AUGUST:
            case Calendar.SEPTEMBER:
            default:
                return SeasonalAnimationView.Theme.SUN;
        }
    }

    private static float dp(@NonNull Activity activity, float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }
}
