/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The engine each instance should start with. No saved choice means Java (the default).
 * Horizon (26.3) and rustcraft (1.0) are used only when the player explicitly enables them
 * for that instance.
 * Keys are the same instance keys the launcher already uses for per-instance settings.
 */
public final class EnginePreferences {
    private static final String PREFS = "droidbridge_game_engine";
    private static final String KEY_PREFIX = "engine.";

    private EnginePreferences() {
    }

    @Nullable
    public static GameEngine get(@NonNull Context context, @NonNull String instanceKey) {
        try {
            return GameEngine.fromId(prefs(context).getString(KEY_PREFIX + instanceKey, null));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Saves the engine for this instance; {@code null} returns to the Java default. */
    public static void set(@NonNull Context context, @NonNull String instanceKey, @Nullable GameEngine engine) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (engine == null) {
            editor.remove(KEY_PREFIX + instanceKey);
        } else {
            editor.putString(KEY_PREFIX + instanceKey, engine.id);
        }
        editor.apply();
    }

    // Horizon always uses DroidBridge's controls (it has no built-in touch controls any more),
    // so there is no Horizon controls preference.

    // Horizon graphics, for every Horizon launch (not per instance).
    private static final String KEY_HORIZON_RENDERER = "horizon.renderer";
    private static final String KEY_HORIZON_VULKAN_DRIVER = "horizon.vulkan_driver";

    /** Horizon's own Rendering mode setting (its launcher's Settings) decides. */
    public static final String HORIZON_RENDERER_ENGINE = "";
    public static final String HORIZON_RENDERER_VULKAN = "vulkan";
    public static final String HORIZON_RENDERER_VULKAN_BATCHING = "vulkan-batching";
    public static final String HORIZON_RENDERER_OPENGL = "opengl";

    /** The rendering mode DroidBridge asks Horizon for; "" leaves it to Horizon's setting. */
    @NonNull
    public static String getHorizonRenderer(@NonNull Context context) {
        try {
            String value = prefs(context).getString(KEY_HORIZON_RENDERER, HORIZON_RENDERER_VULKAN);
            return value == null ? HORIZON_RENDERER_VULKAN : value;
        } catch (Throwable ignored) {
            return HORIZON_RENDERER_VULKAN;
        }
    }

    public static void setHorizonRenderer(@NonNull Context context, @NonNull String renderer) {
        prefs(context).edit().putString(KEY_HORIZON_RENDERER, renderer).apply();
    }

    /**
     * The Vulkan driver Horizon loads: a name from DriverPluginManager (bundled Turnip,
     * an imported PanVK or Turnip package, ...), or "" for the system Vulkan driver.
     */
    @NonNull
    public static String getHorizonVulkanDriver(@NonNull Context context) {
        try {
            String value = prefs(context).getString(KEY_HORIZON_VULKAN_DRIVER, "");
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void setHorizonVulkanDriver(@NonNull Context context, @NonNull String driverName) {
        prefs(context).edit().putString(KEY_HORIZON_VULKAN_DRIVER, driverName).apply();
    }

    // rustcraft (Minecraft 1.0 in Rust), for every rustcraft launch (not per instance).
    private static final String KEY_RUSTCRAFT_HOST_CONTROLS = "rustcraft.droidbridge_controls";
    private static final String KEY_RUSTCRAFT_RENDERER = "rustcraft.renderer";

    /** The engine picks: Vulkan when the device has it, otherwise OpenGL ES. */
    public static final String RUSTCRAFT_RENDERER_AUTO = "";
    public static final String RUSTCRAFT_RENDERER_VULKAN = "vulkan";
    public static final String RUSTCRAFT_RENDERER_OPENGL = "opengl";

    /**
     * True (the default) when rustcraft uses DroidBridge's touch controls, virtual mouse and
     * controller mapper. The engine has no touch controls of its own, so off means a
     * physical keyboard and mouse only.
     */
    public static boolean useDroidBridgeControlsForRustcraft(@NonNull Context context) {
        try {
            return prefs(context).getBoolean(KEY_RUSTCRAFT_HOST_CONTROLS, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setUseDroidBridgeControlsForRustcraft(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RUSTCRAFT_HOST_CONTROLS, enabled).apply();
    }

    /** The renderer DroidBridge asks rustcraft for; "" lets the engine choose. */
    @NonNull
    public static String getRustcraftRenderer(@NonNull Context context) {
        try {
            String value = prefs(context).getString(KEY_RUSTCRAFT_RENDERER, RUSTCRAFT_RENDERER_AUTO);
            return value == null ? RUSTCRAFT_RENDERER_AUTO : value;
        } catch (Throwable ignored) {
            return RUSTCRAFT_RENDERER_AUTO;
        }
    }

    public static void setRustcraftRenderer(@NonNull Context context, @NonNull String renderer) {
        prefs(context).edit().putString(KEY_RUSTCRAFT_RENDERER, renderer).apply();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
