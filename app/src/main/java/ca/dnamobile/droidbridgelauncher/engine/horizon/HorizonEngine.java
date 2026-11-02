/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.BuildConfig;

/**
 * What the bundled Horizon engine can run, and where it keeps its files.
 *
 * Horizon (https://github.com/ludovic111/horizon, MIT) is Minecraft: Java Edition 26.3
 * rewritten in Rust. It reproduces exactly one game version, so it can only stand in for
 * the Java engine on vanilla 26.3 instances. Everything else keeps using the Java engine.
 */
public final class HorizonEngine {
    /** The only Minecraft version the engine implements (horizon_core::VANILLA_VERSION). */
    public static final String MINECRAFT_VERSION = "26.3";
    /** The engine's native library and its NativeActivity entry point. */
    public static final String LIBRARY_NAME = "horizon_android";

    /** The engine's Android build targets API 28 (tools/android_build.py, arm64-v8a). */
    private static final int MIN_SDK = Build.VERSION_CODES.P;
    /** The only ABI app/build.gradle bundles the engine for. */
    private static final String ENGINE_ABI = "arm64-v8a";
    private static final int GLES_3_0 = 0x00030000;

    private HorizonEngine() {
    }

    /** True when this APK was built with libhorizon_android.so and engine-data.zip. */
    public static boolean isBundled() {
        return BuildConfig.HORIZON_ENGINE_BUNDLED;
    }

    /** True for instances the engine can run: vanilla 26.3, no mod loader. */
    public static boolean supportsInstance(@Nullable String minecraftVersionId, @Nullable String loader) {
        if (minecraftVersionId == null || !MINECRAFT_VERSION.equals(minecraftVersionId.trim())) {
            return false;
        }
        String cleanLoader = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        return cleanLoader.isEmpty() || "vanilla".equals(cleanLoader);
    }

    /**
     * Why this device cannot run the engine, or {@code null} when it can.
     * Horizon renders with Vulkan and falls back to OpenGL ES 3.
     */
    @Nullable
    public static String deviceProblem(@NonNull Context context) {
        if (!isBundled()) {
            return "This build of DroidBridge does not include the Horizon engine.";
        }
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return "Horizon needs Android 9 or newer.";
        }
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (ENGINE_ABI.equals(abi)) {
                arm64 = true;
                break;
            }
        }
        if (!arm64) {
            return "Horizon needs a 64-bit ARM (arm64-v8a) device.";
        }
        PackageManager packageManager = context.getPackageManager();
        boolean vulkan = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 0)
                || packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
        if (!vulkan && glesVersion(context) < GLES_3_0) {
            return "Horizon needs a GPU with Vulkan or OpenGL ES 3.";
        }
        return null;
    }

    /** True when the Horizon engine is offered for this instance on this device. */
    public static boolean isAvailableFor(
            @NonNull Context context,
            @Nullable String minecraftVersionId,
            @Nullable String loader
    ) {
        return supportsInstance(minecraftVersionId, loader) && deviceProblem(context) == null;
    }

    /**
     * The engine's own files: its downloaded 26.3 resources, renderer and touch settings,
     * diagnostics. Shared by every Horizon instance; worlds stay in each instance.
     */
    @NonNull
    public static File engineHome(@NonNull Context context) {
        return new File(context.getFilesDir(), "horizon");
    }

    /** Where the launcher hands the engine its signed-in session, one file per launch. */
    @NonNull
    public static File sessionDirectory(@NonNull Context context) {
        return new File(engineHome(context), "host-sessions");
    }

    private static int glesVersion(@NonNull Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return 0;
            ConfigurationInfo info = manager.getDeviceConfigurationInfo();
            return info == null ? 0 : info.reqGlEsVersion;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
