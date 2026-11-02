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

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.dualscreen.AynThorDisplayCompat;

/**
 * Small preference wrapper for touch controls. Kept separate from LauncherPreferences
 * so this system can grow without bloating the normal launcher settings class.
 */
public final class ControlsPreferences {
    private static final String PREFS = "java_launcher_touch_controls";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SELECTED_LAYOUT = "selected_layout";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_PRESSED_BUTTON_ALPHA_PERCENT =
            "pressed_button_alpha_percent";
    private static final String KEY_GLOBAL_BUTTON_SCALE_PERCENT = "global_button_scale_percent";
    private static final String KEY_GLOBAL_BUTTON_TEXT_CASE = "global_button_text_case";
    private static final String KEY_DUAL_SCREEN_BUTTON_SCALE_PERCENT = "dual_screen_button_scale_percent";
    private static final String KEY_EDIT_GRID = "edit_grid";
    private static final String KEY_SNAP_CONTROLS = "snap_controls";
    private static final String KEY_SIZE_PREVIEW_PERCENT = "size_preview_percent";
    private static final String KEY_VIRTUAL_MOUSE_ENABLED = "virtual_mouse_enabled";
    private static final String KEY_VIRTUAL_MOUSE_START_ENABLED = "virtual_mouse_start_enabled";
    private static final String KEY_VIRTUAL_MOUSE_SPEED_PERCENT = "virtual_mouse_speed_percent";
    private static final String KEY_GYROSCOPE_CAMERA_ENABLED = "gyroscope_camera_enabled";
    private static final String KEY_MINECRAFT_TOUCH_GESTURES = "minecraft_touch_gestures";
    private static final String KEY_DOUBLE_TAP_TO_DROP = "double_tap_to_drop";
    private static final String KEY_DOUBLE_TAP_HOTBAR_TO_OFFHAND =
            "double_tap_hotbar_to_offhand";
    private static final String KEY_AUTO_HIDE_TOUCH_CONTROLS_WITH_CONTROLLER =
            "auto_hide_touch_controls_with_controller";
    private static final String KEY_SHOW_GAMEPAD_INPUT_ON_TOUCH_CONTROLS =
            "show_gamepad_input_on_touch_controls";

    private static final String KEY_MOUSE_CURSOR_STYLE = "mouse_cursor_style";
    private static final String KEY_MOUSE_CURSOR_SIZE_PERCENT = "mouse_cursor_size_percent";
    private static final String KEY_CUSTOM_MOUSE_CURSOR_PATH = "custom_mouse_cursor_path";
    private static final String KEY_DYNAMIC_MOUSE_CURSOR_ENABLED = "dynamic_mouse_cursor_enabled";
    private static final String KEY_DYNAMIC_MOUSE_CURSOR_STYLE_PREFIX = "dynamic_mouse_cursor_style_";
    private static final String KEY_DYNAMIC_MOUSE_CURSOR_PATH_PREFIX = "dynamic_mouse_cursor_path_";

    private static final String KEY_HOTBAR_HITBOX_DEBUG = "hotbar_hitbox_debug";
    private static final String KEY_HOTBAR_GUI_SCALE_OVERRIDE = "hotbar_gui_scale_override";
    private static final String KEY_HOTBAR_WIDTH_GUI = "hotbar_width_gui";
    private static final String KEY_HOTBAR_HEIGHT_GUI = "hotbar_height_gui";
    private static final String KEY_HOTBAR_X_OFFSET_DP = "hotbar_x_offset_dp";
    private static final String KEY_HOTBAR_Y_OFFSET_DP = "hotbar_y_offset_dp";
    private static final String KEY_HOTBAR_VERTICAL_PADDING_DP = "hotbar_vertical_padding_dp";

    public static final float DEFAULT_HOTBAR_WIDTH_GUI = 180f;
    public static final float DEFAULT_HOTBAR_HEIGHT_GUI = 20f;
    public static final float DEFAULT_HOTBAR_X_OFFSET_DP = 0f;
    public static final float DEFAULT_HOTBAR_Y_OFFSET_DP = 0f;
    public static final float DEFAULT_HOTBAR_VERTICAL_PADDING_DP = 18f;

    public static final String MOUSE_CURSOR_STYLE_CROSSHAIR = "crosshair";
    public static final String MOUSE_CURSOR_STYLE_DOT = "dot";
    public static final String MOUSE_CURSOR_STYLE_ARROW = "arrow";
    public static final String MOUSE_CURSOR_STYLE_TEXT = "text";
    public static final String MOUSE_CURSOR_STYLE_HAND = "hand";
    public static final String MOUSE_CURSOR_STYLE_RESIZE = "resize";
    public static final String MOUSE_CURSOR_STYLE_CUSTOM = "custom";

    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_DEFAULT = "default";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_TEXT = "text";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_POINTER = "pointer";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE = "resize";
    // Keep the original four preference keys valid when upgrading existing cursor profiles.
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_CROSSHAIR = "crosshair";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_EW = "resize_ew";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NS = "resize_ns";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NWSE = "resize_nwse";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NESW = "resize_nesw";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_MOVE = "move";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_NOT_ALLOWED = "not_allowed";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_WAIT = "wait";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_PROGRESS = "progress";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_HELP = "help";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_CONTEXT_MENU = "context_menu";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_CELL = "cell";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_VERTICAL_TEXT = "vertical_text";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_ALIAS = "alias";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_COPY = "copy";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_NO_DROP = "no_drop";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_GRAB = "grab";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_GRABBING = "grabbing";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_IN = "zoom_in";
    public static final String DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_OUT = "zoom_out";

    private static final String[] DYNAMIC_MOUSE_CURSOR_ROLES = {
            DYNAMIC_MOUSE_CURSOR_ROLE_DEFAULT, DYNAMIC_MOUSE_CURSOR_ROLE_TEXT,
            DYNAMIC_MOUSE_CURSOR_ROLE_POINTER, DYNAMIC_MOUSE_CURSOR_ROLE_CROSSHAIR,
            DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_EW, DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NS,
            DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NWSE, DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NESW,
            DYNAMIC_MOUSE_CURSOR_ROLE_MOVE, DYNAMIC_MOUSE_CURSOR_ROLE_NOT_ALLOWED,
            DYNAMIC_MOUSE_CURSOR_ROLE_WAIT, DYNAMIC_MOUSE_CURSOR_ROLE_PROGRESS,
            DYNAMIC_MOUSE_CURSOR_ROLE_HELP, DYNAMIC_MOUSE_CURSOR_ROLE_CONTEXT_MENU,
            DYNAMIC_MOUSE_CURSOR_ROLE_CELL, DYNAMIC_MOUSE_CURSOR_ROLE_VERTICAL_TEXT,
            DYNAMIC_MOUSE_CURSOR_ROLE_ALIAS, DYNAMIC_MOUSE_CURSOR_ROLE_COPY,
            DYNAMIC_MOUSE_CURSOR_ROLE_NO_DROP, DYNAMIC_MOUSE_CURSOR_ROLE_GRAB,
            DYNAMIC_MOUSE_CURSOR_ROLE_GRABBING, DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_IN,
            DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_OUT
    };

    @NonNull
    public static String[] getDynamicMouseCursorRoles() {
        return DYNAMIC_MOUSE_CURSOR_ROLES.clone();
    }

    @NonNull
    public static String getDefaultDynamicMouseCursorStyle(@NonNull Context context, @Nullable String role) {
        String normalized = normalizeDynamicMouseCursorRole(role);
        switch (normalized) {
            case DYNAMIC_MOUSE_CURSOR_ROLE_DEFAULT: return getMouseCursorStyle(context);
            case DYNAMIC_MOUSE_CURSOR_ROLE_TEXT: return MOUSE_CURSOR_STYLE_TEXT;
            case DYNAMIC_MOUSE_CURSOR_ROLE_VERTICAL_TEXT: return "vertical_text";
            case DYNAMIC_MOUSE_CURSOR_ROLE_POINTER: return MOUSE_CURSOR_STYLE_HAND;
            case DYNAMIC_MOUSE_CURSOR_ROLE_CROSSHAIR: return MOUSE_CURSOR_STYLE_CROSSHAIR;
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE: case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_EW: return MOUSE_CURSOR_STYLE_RESIZE;
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NS: return "resize_ns";
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NWSE: return "resize_nwse";
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NESW: return "resize_nesw";
            case DYNAMIC_MOUSE_CURSOR_ROLE_NO_DROP: case DYNAMIC_MOUSE_CURSOR_ROLE_NOT_ALLOWED: return "not_allowed";
            case DYNAMIC_MOUSE_CURSOR_ROLE_MOVE: return "move";
            case DYNAMIC_MOUSE_CURSOR_ROLE_WAIT: return "wait";
            case DYNAMIC_MOUSE_CURSOR_ROLE_PROGRESS: return "progress";
            case DYNAMIC_MOUSE_CURSOR_ROLE_HELP: return "help";
            case DYNAMIC_MOUSE_CURSOR_ROLE_CONTEXT_MENU: return "context_menu";
            case DYNAMIC_MOUSE_CURSOR_ROLE_CELL: return "cell";
            case DYNAMIC_MOUSE_CURSOR_ROLE_ALIAS: return "alias";
            case DYNAMIC_MOUSE_CURSOR_ROLE_COPY: return "copy";
            case DYNAMIC_MOUSE_CURSOR_ROLE_GRAB: return "grab";
            case DYNAMIC_MOUSE_CURSOR_ROLE_GRABBING: return "grabbing";
            case DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_IN: return "zoom_in";
            case DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_OUT: return "zoom_out";
            default: return getMouseCursorStyle(context);
        }
    }


    public static final int MIN_MOUSE_CURSOR_SIZE_PERCENT = 50;
    public static final int MAX_MOUSE_CURSOR_SIZE_PERCENT = 200;
    public static final int DEFAULT_MOUSE_CURSOR_SIZE_PERCENT = 100;

    public static final int MIN_VIRTUAL_MOUSE_SPEED_PERCENT = 25;
    public static final int MAX_VIRTUAL_MOUSE_SPEED_PERCENT = 300;
    public static final int DEFAULT_VIRTUAL_MOUSE_SPEED_PERCENT = 100;

    public static final int MIN_GLOBAL_BUTTON_SCALE_PERCENT = 50;
    public static final int MAX_GLOBAL_BUTTON_SCALE_PERCENT = 200;
    public static final int DEFAULT_GLOBAL_BUTTON_SCALE_PERCENT = 100;

    public static final int MIN_PRESSED_BUTTON_ALPHA_PERCENT = 0;
    public static final int MAX_PRESSED_BUTTON_ALPHA_PERCENT = 100;
    public static final int DEFAULT_PRESSED_BUTTON_ALPHA_PERCENT = 60;

    public static final int MIN_DUAL_SCREEN_BUTTON_SCALE_PERCENT = 75;
    public static final int MAX_DUAL_SCREEN_BUTTON_SCALE_PERCENT = 300;
    public static final int DEFAULT_DUAL_SCREEN_BUTTON_SCALE_PERCENT = 100;
    public static final int DEFAULT_THOR_DUAL_SCREEN_BUTTON_SCALE_PERCENT = 150;

    private ControlsPreferences() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isTouchControlsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setTouchControlsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    @Nullable
    public static String getSelectedLayoutPath(@NonNull Context context) {
        String value = prefs(context).getString(KEY_SELECTED_LAYOUT, null);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public static void setSelectedLayoutPath(@NonNull Context context, @Nullable String path) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (path == null || path.trim().isEmpty()) {
            editor.remove(KEY_SELECTED_LAYOUT);
        } else {
            editor.putString(KEY_SELECTED_LAYOUT, path);
        }
        editor.apply();
    }

    public static float getGlobalOpacity(@NonNull Context context) {
        return clamp(prefs(context).getFloat(KEY_OPACITY, 1f), 0f, 1f);
    }

    public static void setGlobalOpacity(@NonNull Context context, float value) {
        prefs(context).edit().putFloat(KEY_OPACITY, clamp(value, 0f, 1f)).apply();
    }

    /**
     * Visibility multiplier used while a touch button is physically pressed or while
     * physical gamepad input is being mirrored onto the touch-control HUD. 100 keeps
     * the button at its normal resolved opacity; lower values dim it relative to that
     * normal opacity.
     */
    public static int getPressedButtonAlphaPercent(@NonNull Context context) {
        return clampInt(
                prefs(context).getInt(
                        KEY_PRESSED_BUTTON_ALPHA_PERCENT,
                        DEFAULT_PRESSED_BUTTON_ALPHA_PERCENT
                ),
                MIN_PRESSED_BUTTON_ALPHA_PERCENT,
                MAX_PRESSED_BUTTON_ALPHA_PERCENT
        );
    }

    public static void setPressedButtonAlphaPercent(@NonNull Context context, int percent) {
        prefs(context).edit()
                .putInt(
                        KEY_PRESSED_BUTTON_ALPHA_PERCENT,
                        clampInt(
                                percent,
                                MIN_PRESSED_BUTTON_ALPHA_PERCENT,
                                MAX_PRESSED_BUTTON_ALPHA_PERCENT
                        )
                )
                .apply();
    }

    /** Mirrors physical controller input onto matching visible touch controls. */
    public static boolean isShowGamepadInputOnTouchControlsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHOW_GAMEPAD_INPUT_ON_TOUCH_CONTROLS, false);
    }

    public static void setShowGamepadInputOnTouchControlsEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        prefs(context).edit()
                .putBoolean(KEY_SHOW_GAMEPAD_INPUT_ON_TOUCH_CONTROLS, enabled)
                .apply();
    }

    public static int getGlobalButtonScalePercent(@NonNull Context context) {
        return clampInt(
                prefs(context).getInt(KEY_GLOBAL_BUTTON_SCALE_PERCENT, DEFAULT_GLOBAL_BUTTON_SCALE_PERCENT),
                MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                MAX_GLOBAL_BUTTON_SCALE_PERCENT
        );
    }

    public static void setGlobalButtonScalePercent(@NonNull Context context, int percent) {
        prefs(context).edit()
                .putInt(KEY_GLOBAL_BUTTON_SCALE_PERCENT, clampInt(
                        percent,
                        MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                        MAX_GLOBAL_BUTTON_SCALE_PERCENT
                ))
                .apply();
    }

    @NonNull
    public static String getGlobalButtonTextCase(@NonNull Context context) {
        String value = prefs(context).getString(
                KEY_GLOBAL_BUTTON_TEXT_CASE,
                TouchControlsLayoutData.DEFAULT_PROFILE_BUTTON_TEXT_CASE
        );
        return TouchControlsLayoutData.normalizeButtonTextCase(
                value == null ? TouchControlsLayoutData.DEFAULT_PROFILE_BUTTON_TEXT_CASE : value
        );
    }

    public static void setGlobalButtonTextCase(@NonNull Context context, @NonNull String value) {
        prefs(context).edit()
                .putString(KEY_GLOBAL_BUTTON_TEXT_CASE, TouchControlsLayoutData.normalizeButtonTextCase(value))
                .apply();
    }

    /** Additional scale applied only while the touch overlay is hosted on the bottom screen. */
    public static int getDualScreenButtonScalePercent(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        int defaultPercent = AynThorDisplayCompat.isAynThorDevice(context)
                ? DEFAULT_THOR_DUAL_SCREEN_BUTTON_SCALE_PERCENT
                : DEFAULT_DUAL_SCREEN_BUTTON_SCALE_PERCENT;
        return clampInt(
                preferences.getInt(
                        KEY_DUAL_SCREEN_BUTTON_SCALE_PERCENT,
                        defaultPercent
                ),
                MIN_DUAL_SCREEN_BUTTON_SCALE_PERCENT,
                MAX_DUAL_SCREEN_BUTTON_SCALE_PERCENT
        );
    }

    public static void setDualScreenButtonScalePercent(@NonNull Context context, int percent) {
        prefs(context).edit()
                .putInt(KEY_DUAL_SCREEN_BUTTON_SCALE_PERCENT, clampInt(
                        percent,
                        MIN_DUAL_SCREEN_BUTTON_SCALE_PERCENT,
                        MAX_DUAL_SCREEN_BUTTON_SCALE_PERCENT
                ))
                .apply();
    }

    public static boolean isEditGridEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_EDIT_GRID, true);
    }

    public static void setEditGridEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_EDIT_GRID, enabled).apply();
    }

    public static boolean isSnapControlsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SNAP_CONTROLS, true);
    }

    public static void setSnapControlsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SNAP_CONTROLS, enabled).apply();
    }

    public static boolean isSizePreviewPercentEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SIZE_PREVIEW_PERCENT, true);
    }

    public static void setSizePreviewPercentEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SIZE_PREVIEW_PERCENT, enabled).apply();
    }

    public static boolean isVirtualMouseEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_VIRTUAL_MOUSE_ENABLED, false);
    }

    public static void setVirtualMouseEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_VIRTUAL_MOUSE_ENABLED, enabled).apply();
    }

    public static boolean isVirtualMouseStartEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_VIRTUAL_MOUSE_START_ENABLED, false);
    }

    public static void setVirtualMouseStartEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_VIRTUAL_MOUSE_START_ENABLED, enabled).apply();
    }

    /**
     * Enables device-gyroscope camera look. Off by default.
     */
    public static boolean isGyroscopeCameraEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_GYROSCOPE_CAMERA_ENABLED, false);
    }

    public static void setGyroscopeCameraEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        prefs(context).edit()
                .putBoolean(KEY_GYROSCOPE_CAMERA_ENABLED, enabled)
                .apply();
    }

    private static final Object VIRTUAL_MOUSE_LAUNCH_SESSION_LOCK = new Object();
    private static boolean virtualMouseLaunchSessionInitialized;
    private static boolean virtualMouseLaunchSessionEnabled;

    /**
     * Starts a new game-session cursor state from the launcher setting. The
     * selected touch-control profile is left unchanged; its Virtual Cursor
     * button can still override this state while the game is running.
     */
    public static void beginVirtualMouseLaunchSession(@NonNull Context context) {
        boolean enabled = isVirtualMouseStartEnabled(context);
        synchronized (VIRTUAL_MOUSE_LAUNCH_SESSION_LOCK) {
            virtualMouseLaunchSessionInitialized = true;
            virtualMouseLaunchSessionEnabled = enabled;
        }
        setVirtualMouseEnabled(context, enabled);
    }

    public static boolean getVirtualMouseLaunchSessionEnabled(@NonNull Context context) {
        synchronized (VIRTUAL_MOUSE_LAUNCH_SESSION_LOCK) {
            if (!virtualMouseLaunchSessionInitialized) {
                virtualMouseLaunchSessionInitialized = true;
                virtualMouseLaunchSessionEnabled = isVirtualMouseStartEnabled(context);
            }
            return virtualMouseLaunchSessionEnabled;
        }
    }

    public static void setVirtualMouseLaunchSessionEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        synchronized (VIRTUAL_MOUSE_LAUNCH_SESSION_LOCK) {
            virtualMouseLaunchSessionInitialized = true;
            virtualMouseLaunchSessionEnabled = enabled;
        }
        setVirtualMouseEnabled(context, enabled);
    }

    /**
     * Scales only the finger-driven virtual cursor delta. This deliberately does
     * not move the cursor away from the finger, alter its visual offset, or affect
     * controller/hardware-mouse sensitivity.
     */
    public static int getVirtualMouseSpeedPercent(@NonNull Context context) {
        return clampInt(
                prefs(context).getInt(
                        KEY_VIRTUAL_MOUSE_SPEED_PERCENT,
                        DEFAULT_VIRTUAL_MOUSE_SPEED_PERCENT
                ),
                MIN_VIRTUAL_MOUSE_SPEED_PERCENT,
                MAX_VIRTUAL_MOUSE_SPEED_PERCENT
        );
    }

    public static void setVirtualMouseSpeedPercent(@NonNull Context context, int percent) {
        prefs(context).edit()
                .putInt(KEY_VIRTUAL_MOUSE_SPEED_PERCENT, clampInt(
                        percent,
                        MIN_VIRTUAL_MOUSE_SPEED_PERCENT,
                        MAX_VIRTUAL_MOUSE_SPEED_PERCENT
                ))
                .apply();
    }

    @NonNull
    public static String getMouseCursorStyle(@NonNull Context context) {
        return normalizeMouseCursorStyle(prefs(context).getString(
                KEY_MOUSE_CURSOR_STYLE,
                MOUSE_CURSOR_STYLE_CROSSHAIR
        ));
    }

    public static void setMouseCursorStyle(@NonNull Context context, @Nullable String style) {
        prefs(context).edit()
                .putString(KEY_MOUSE_CURSOR_STYLE, normalizeMouseCursorStyle(style))
                .apply();
    }

    public static int getMouseCursorSizePercent(@NonNull Context context) {
        return clampInt(
                prefs(context).getInt(KEY_MOUSE_CURSOR_SIZE_PERCENT, DEFAULT_MOUSE_CURSOR_SIZE_PERCENT),
                MIN_MOUSE_CURSOR_SIZE_PERCENT,
                MAX_MOUSE_CURSOR_SIZE_PERCENT
        );
    }

    public static void setMouseCursorSizePercent(@NonNull Context context, int percent) {
        prefs(context).edit()
                .putInt(KEY_MOUSE_CURSOR_SIZE_PERCENT, clampInt(
                        percent,
                        MIN_MOUSE_CURSOR_SIZE_PERCENT,
                        MAX_MOUSE_CURSOR_SIZE_PERCENT
                ))
                .apply();
    }

    @Nullable
    public static String getCustomMouseCursorPath(@NonNull Context context) {
        String value = prefs(context).getString(KEY_CUSTOM_MOUSE_CURSOR_PATH, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static void setCustomMouseCursorPath(@NonNull Context context, @Nullable String path) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (path == null || path.trim().isEmpty()) {
            editor.remove(KEY_CUSTOM_MOUSE_CURSOR_PATH);
        } else {
            editor.putString(KEY_CUSTOM_MOUSE_CURSOR_PATH, path.trim());
        }
        editor.apply();
    }

    @NonNull
    public static File getCustomMouseCursorFile(@NonNull Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "mouse_cursor");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, "custom_mouse_cursor.png");
    }

    public static boolean hasCustomMouseCursorIcon(@NonNull Context context) {
        String path = getCustomMouseCursorPath(context);
        if (path == null) return false;
        File file = new File(path);
        return file.isFile() && file.length() > 0L;
    }

    public static boolean isDynamicMouseCursorEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DYNAMIC_MOUSE_CURSOR_ENABLED, false);
    }

    public static void setDynamicMouseCursorEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_MOUSE_CURSOR_ENABLED, enabled).apply();
    }

    @NonNull
    public static String getDynamicMouseCursorStyle(
            @NonNull Context context,
            @Nullable String role
    ) {
        String normalizedRole = normalizeDynamicMouseCursorRole(role);
        SharedPreferences store = prefs(context);
        String key = KEY_DYNAMIC_MOUSE_CURSOR_STYLE_PREFIX + normalizedRole;
        if (store.contains(key)) {
            return normalizeMouseCursorStyle(store.getString(key, null));
        }
        if (isDirectionalResizeRole(normalizedRole)) {
            String legacyKey = KEY_DYNAMIC_MOUSE_CURSOR_STYLE_PREFIX + DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE;
            if (store.contains(legacyKey)) {
                return normalizeMouseCursorStyle(store.getString(legacyKey, null));
            }
        }
        return getDefaultDynamicMouseCursorStyle(context, normalizedRole);
    }

    public static void setDynamicMouseCursorStyle(
            @NonNull Context context,
            @Nullable String role,
            @Nullable String style
    ) {
        String normalizedRole = normalizeDynamicMouseCursorRole(role);
        prefs(context).edit()
                .putString(KEY_DYNAMIC_MOUSE_CURSOR_STYLE_PREFIX + normalizedRole,
                        normalizeMouseCursorStyle(style))
                .apply();
    }

    @Nullable
    public static String getDynamicMouseCursorCustomPath(
            @NonNull Context context,
            @Nullable String role
    ) {
        String normalizedRole = normalizeDynamicMouseCursorRole(role);
        SharedPreferences store = prefs(context);
        String key = KEY_DYNAMIC_MOUSE_CURSOR_PATH_PREFIX + normalizedRole;
        if (store.contains(key)) {
            String value = store.getString(key, null);
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }
        if (isDirectionalResizeRole(normalizedRole)) {
            String oldKey = KEY_DYNAMIC_MOUSE_CURSOR_PATH_PREFIX + DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE;
            if (store.contains(oldKey)) {
                String legacyPath = store.getString(oldKey, null);
                return legacyPath == null || legacyPath.trim().isEmpty() ? null : legacyPath.trim();
            }
        }
        if (DYNAMIC_MOUSE_CURSOR_ROLE_DEFAULT.equals(normalizedRole)) {
            return getCustomMouseCursorPath(context);
        }
        return null;
    }

    private static boolean isDirectionalResizeRole(@NonNull String role) {
        return DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_EW.equals(role)
                || DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NS.equals(role)
                || DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NWSE.equals(role)
                || DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NESW.equals(role);
    }

    public static void setDynamicMouseCursorCustomPath(
            @NonNull Context context,
            @Nullable String role,
            @Nullable String path
    ) {
        String normalizedRole = normalizeDynamicMouseCursorRole(role);
        String key = KEY_DYNAMIC_MOUSE_CURSOR_PATH_PREFIX + normalizedRole;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (path == null || path.trim().isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, path.trim());
        }
        editor.apply();
    }

    @NonNull
    public static File getDynamicMouseCursorCustomFile(
            @NonNull Context context,
            @Nullable String role
    ) {
        String normalizedRole = normalizeDynamicMouseCursorRole(role);
        File dir = new File(context.getApplicationContext().getFilesDir(), "mouse_cursor");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, "custom_mouse_cursor_" + normalizedRole + ".png");
    }

    public static boolean hasDynamicMouseCursorCustomIcon(
            @NonNull Context context,
            @Nullable String role
    ) {
        String path = getDynamicMouseCursorCustomPath(context, role);
        if (path == null) return false;
        File file = new File(path);
        return file.isFile() && file.length() > 0L;
    }

    @NonNull
    public static String getDynamicMouseCursorRoleLabel(@Nullable String role) {
        switch (normalizeDynamicMouseCursorRole(role)) {
            case DYNAMIC_MOUSE_CURSOR_ROLE_TEXT: return "Text fields";
            case DYNAMIC_MOUSE_CURSOR_ROLE_POINTER: return "Clickable / links";
            case DYNAMIC_MOUSE_CURSOR_ROLE_CROSSHAIR: return "Precision / crosshair";
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE: case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_EW: return "Resize: horizontal";
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NS: return "Resize: vertical";
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NWSE: return "Resize: diagonal NW–SE";
            case DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE_NESW: return "Resize: diagonal NE–SW";
            case DYNAMIC_MOUSE_CURSOR_ROLE_MOVE: return "Move / all-direction scroll";
            case DYNAMIC_MOUSE_CURSOR_ROLE_NOT_ALLOWED: return "Unavailable action";
            case DYNAMIC_MOUSE_CURSOR_ROLE_WAIT: return "Busy / wait";
            case DYNAMIC_MOUSE_CURSOR_ROLE_PROGRESS: return "Working in background";
            case DYNAMIC_MOUSE_CURSOR_ROLE_HELP: return "Help";
            case DYNAMIC_MOUSE_CURSOR_ROLE_CONTEXT_MENU: return "Context menu";
            case DYNAMIC_MOUSE_CURSOR_ROLE_CELL: return "Cell selection";
            case DYNAMIC_MOUSE_CURSOR_ROLE_VERTICAL_TEXT: return "Vertical text";
            case DYNAMIC_MOUSE_CURSOR_ROLE_ALIAS: return "Shortcut / alias";
            case DYNAMIC_MOUSE_CURSOR_ROLE_COPY: return "Copy";
            case DYNAMIC_MOUSE_CURSOR_ROLE_NO_DROP: return "Cannot drop";
            case DYNAMIC_MOUSE_CURSOR_ROLE_GRAB: return "Grab / draggable";
            case DYNAMIC_MOUSE_CURSOR_ROLE_GRABBING: return "Grabbing / dragging";
            case DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_IN: return "Zoom in";
            case DYNAMIC_MOUSE_CURSOR_ROLE_ZOOM_OUT: return "Zoom out";
            default: return "Normal / default";
        }
    }

    @NonNull
    public static String normalizeDynamicMouseCursorRole(@Nullable String role) {
        String value = role == null ? "" : role.trim().toLowerCase(java.util.Locale.US);
        if (DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE.equals(value)) return value; // Legacy setting.
        for (String supported : DYNAMIC_MOUSE_CURSOR_ROLES) {
            if (supported.equals(value)) return value;
        }
        return DYNAMIC_MOUSE_CURSOR_ROLE_DEFAULT;
    }

    public static void resetAllMouseCursorIcons(@NonNull Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(KEY_MOUSE_CURSOR_STYLE, MOUSE_CURSOR_STYLE_CROSSHAIR);
        editor.remove(KEY_CUSTOM_MOUSE_CURSOR_PATH);
        for (String role : DYNAMIC_MOUSE_CURSOR_ROLES) {
            editor.remove(KEY_DYNAMIC_MOUSE_CURSOR_STYLE_PREFIX + role);
            editor.remove(KEY_DYNAMIC_MOUSE_CURSOR_PATH_PREFIX + role);
        }
        editor.remove(KEY_DYNAMIC_MOUSE_CURSOR_STYLE_PREFIX + DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE);
        editor.remove(KEY_DYNAMIC_MOUSE_CURSOR_PATH_PREFIX + DYNAMIC_MOUSE_CURSOR_ROLE_RESIZE);
        editor.apply();
        File dir = new File(context.getApplicationContext().getFilesDir(), "mouse_cursor");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith("custom_mouse_cursor")
                        && file.getName().endsWith(".png")) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
    }

    @NonNull
    public static String getMouseCursorResourceName(@Nullable String style) {
        String normalized = normalizeMouseCursorStyle(style);
        if (MOUSE_CURSOR_STYLE_DOT.equals(normalized)) return "ic_cursor_dot";
        if (MOUSE_CURSOR_STYLE_CROSSHAIR.equals(normalized)) return "ic_gamepad_pointer";
        if (MOUSE_CURSOR_STYLE_TEXT.equals(normalized)) return "ic_cursor_text";
        if (MOUSE_CURSOR_STYLE_HAND.equals(normalized)) return "ic_cursor_hand";
        if (MOUSE_CURSOR_STYLE_RESIZE.equals(normalized)) return "ic_cursor_resize";
        switch (normalized) {
            case "resize_ns": case "resize_nwse": case "resize_nesw":
            case "move": case "not_allowed": case "wait": case "progress":
            case "help": case "context_menu": case "cell": case "vertical_text":
            case "alias": case "copy": case "grab": case "grabbing":
            case "zoom_in": case "zoom_out":
                return "ic_cursor_" + normalized;
            default: break;
        }

        return "ic_cursor_arrow";
    }

    @NonNull
    public static String getMouseCursorStyleLabel(@Nullable String style) {
        String normalized = normalizeMouseCursorStyle(style);
        if (MOUSE_CURSOR_STYLE_DOT.equals(normalized)) return "Dot crosshair";
        if (MOUSE_CURSOR_STYLE_ARROW.equals(normalized)) return "Mouse pointer";
        if (MOUSE_CURSOR_STYLE_TEXT.equals(normalized)) return "Text cursor";
        if (MOUSE_CURSOR_STYLE_HAND.equals(normalized)) return "Pointing hand";
        if (MOUSE_CURSOR_STYLE_RESIZE.equals(normalized)) return "Resize arrows";
        if (MOUSE_CURSOR_STYLE_CUSTOM.equals(normalized)) return "Custom image";
        switch (normalized) {
            case "resize_ns": return "Vertical resize arrows";
            case "resize_nwse": return "Diagonal NW–SE resize";
            case "resize_nesw": return "Diagonal NE–SW resize";
            case "move": return "Four-way move";
            case "not_allowed": return "Not allowed";
            case "wait": return "Busy / wait";
            case "progress": return "Working in background";
            case "help": return "Help pointer";
            case "context_menu": return "Context menu";
            case "cell": return "Cell selection";
            case "vertical_text": return "Vertical text";
            case "alias": return "Shortcut / alias";
            case "copy": return "Copy";
            case "grab": return "Open hand / grab";
            case "grabbing": return "Closed hand / grabbing";
            case "zoom_in": return "Zoom in";
            case "zoom_out": return "Zoom out";
            default: break;
        }

        return "Crosshair";
    }

    @NonNull
    public static String normalizeMouseCursorStyle(@Nullable String style) {
        String value = style == null ? "" : style.trim().toLowerCase(java.util.Locale.US);
        if (MOUSE_CURSOR_STYLE_DOT.equals(value)) return MOUSE_CURSOR_STYLE_DOT;
        if (MOUSE_CURSOR_STYLE_ARROW.equals(value)) return MOUSE_CURSOR_STYLE_ARROW;
        if (MOUSE_CURSOR_STYLE_TEXT.equals(value)) return MOUSE_CURSOR_STYLE_TEXT;
        if (MOUSE_CURSOR_STYLE_HAND.equals(value)) return MOUSE_CURSOR_STYLE_HAND;
        if (MOUSE_CURSOR_STYLE_RESIZE.equals(value)) return MOUSE_CURSOR_STYLE_RESIZE;
        if (MOUSE_CURSOR_STYLE_CUSTOM.equals(value)) return MOUSE_CURSOR_STYLE_CUSTOM;
        switch (value) {
            case "resize_ns": case "resize_nwse": case "resize_nesw":
            case "move": case "not_allowed": case "wait": case "progress":
            case "help": case "context_menu": case "cell": case "vertical_text":
            case "alias": case "copy": case "grab": case "grabbing":
            case "zoom_in": case "zoom_out": return value;
            default: break;
        }

        return MOUSE_CURSOR_STYLE_CROSSHAIR;
    }

    /**
     * Launcher-side touchscreen gestures while Minecraft has grabbed the mouse.
     * When disabled, empty-space touch still moves the camera, but quick-tap and
     * long-press punching/attacking are blocked. On-screen buttons still work.
     */
    public static boolean isMinecraftTouchGesturesEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_MINECRAFT_TOUCH_GESTURES, true);
    }

    public static void setMinecraftTouchGesturesEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MINECRAFT_TOUCH_GESTURES, enabled).apply();
    }

    /**
     * Optional helper gesture: double-tapping the empty in-game look area sends Q
     * to drop the currently selected item. Kept off by default so normal tapping
     * cannot accidentally drop items.
     */
    public static boolean isDoubleTapToDropEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DOUBLE_TAP_TO_DROP, false);
    }

    public static void setDoubleTapToDropEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DOUBLE_TAP_TO_DROP, enabled).apply();
    }

    /**
     * Double-tapping the same in-game hotbar slot sends Minecraft's Swap Item
     * With Offhand key (F). This is independent from the empty-look-area
     * double-tap-to-drop gesture and remains enabled by default for existing users.
     */
    public static boolean isDoubleTapHotbarToOffhandEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DOUBLE_TAP_HOTBAR_TO_OFFHAND, true);
    }

    public static void setDoubleTapHotbarToOffhandEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        prefs(context).edit().putBoolean(KEY_DOUBLE_TAP_HOTBAR_TO_OFFHAND, enabled).apply();
    }

    /**
     * Temporarily hides the visual touch buttons while Android reports a connected
     * game controller. This does not change the normal touch-controls enabled state,
     * so the controls return automatically when the controller disconnects.
     */
    public static boolean isAutoHideTouchControlsWithControllerEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_AUTO_HIDE_TOUCH_CONTROLS_WITH_CONTROLLER, false);
    }

    public static void setAutoHideTouchControlsWithControllerEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        prefs(context).edit()
                .putBoolean(KEY_AUTO_HIDE_TOUCH_CONTROLS_WITH_CONTROLLER, enabled)
                .apply();
        // This setting is commonly changed while a controller is already attached.
        // InputManager will not emit an add/change callback just because a preference
        // changed, so update every live overlay immediately instead of waiting for
        // the player to move a stick or press a button.
        TouchControlsOverlay.notifyControllerAutoHidePreferenceChanged();
    }

    public static boolean isHotbarHitboxDebugEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_HOTBAR_HITBOX_DEBUG, false);
    }

    public static void setHotbarHitboxDebugEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HOTBAR_HITBOX_DEBUG, enabled).apply();
    }

    /**
     * 0 means launcher auto-estimate. Use 2/3/4/etc. to match Minecraft's GUI scale.
     */
    public static int getHotbarGuiScaleOverride(@NonNull Context context) {
        int value = prefs(context).getInt(KEY_HOTBAR_GUI_SCALE_OVERRIDE, 0);
        return Math.max(0, Math.min(8, value));
    }

    public static void setHotbarGuiScaleOverride(@NonNull Context context, int scale) {
        prefs(context).edit().putInt(KEY_HOTBAR_GUI_SCALE_OVERRIDE, Math.max(0, Math.min(8, scale))).apply();
    }

    public static float getHotbarWidthGui(@NonNull Context context) {
        return clamp(prefs(context).getFloat(KEY_HOTBAR_WIDTH_GUI, DEFAULT_HOTBAR_WIDTH_GUI), 90f, 260f);
    }

    public static void setHotbarWidthGui(@NonNull Context context, float value) {
        prefs(context).edit().putFloat(KEY_HOTBAR_WIDTH_GUI, clamp(value, 90f, 260f)).apply();
    }

    public static float getHotbarHeightGui(@NonNull Context context) {
        return clamp(prefs(context).getFloat(KEY_HOTBAR_HEIGHT_GUI, DEFAULT_HOTBAR_HEIGHT_GUI), 12f, 60f);
    }

    public static void setHotbarHeightGui(@NonNull Context context, float value) {
        prefs(context).edit().putFloat(KEY_HOTBAR_HEIGHT_GUI, clamp(value, 12f, 60f)).apply();
    }

    public static float getHotbarXOffsetDp(@NonNull Context context) {
        return clamp(prefs(context).getFloat(KEY_HOTBAR_X_OFFSET_DP, DEFAULT_HOTBAR_X_OFFSET_DP), -160f, 160f);
    }

    public static void setHotbarXOffsetDp(@NonNull Context context, float value) {
        prefs(context).edit().putFloat(KEY_HOTBAR_X_OFFSET_DP, clamp(value, -160f, 160f)).apply();
    }

    public static float getHotbarYOffsetDp(@NonNull Context context) {
        return clamp(prefs(context).getFloat(KEY_HOTBAR_Y_OFFSET_DP, DEFAULT_HOTBAR_Y_OFFSET_DP), -80f, 160f);
    }

    public static void setHotbarYOffsetDp(@NonNull Context context, float value) {
        prefs(context).edit().putFloat(KEY_HOTBAR_Y_OFFSET_DP, clamp(value, -80f, 160f)).apply();
    }

    public static float getHotbarVerticalPaddingDp(@NonNull Context context) {
        return clamp(prefs(context).getFloat(KEY_HOTBAR_VERTICAL_PADDING_DP, DEFAULT_HOTBAR_VERTICAL_PADDING_DP), 0f, 80f);
    }

    public static void setHotbarVerticalPaddingDp(@NonNull Context context, float value) {
        prefs(context).edit().putFloat(KEY_HOTBAR_VERTICAL_PADDING_DP, clamp(value, 0f, 80f)).apply();
    }

    public static void resetHotbarHitboxSettings(@NonNull Context context) {
        prefs(context).edit()
                .remove(KEY_HOTBAR_HITBOX_DEBUG)
                .remove(KEY_HOTBAR_GUI_SCALE_OVERRIDE)
                .remove(KEY_HOTBAR_WIDTH_GUI)
                .remove(KEY_HOTBAR_HEIGHT_GUI)
                .remove(KEY_HOTBAR_X_OFFSET_DP)
                .remove(KEY_HOTBAR_Y_OFFSET_DP)
                .remove(KEY_HOTBAR_VERTICAL_PADDING_DP)
                .apply();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

