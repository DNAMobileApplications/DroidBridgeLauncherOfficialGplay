/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.engine.EnginePreferences;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.renderer.Driver;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import dev.horizon.android.HorizonActivity;

/**
 * Horizon's rendering mode and Vulkan driver, chosen in DroidBridge.
 *
 * Horizon draws through wgpu, which talks to Vulkan or OpenGL ES directly, so DroidBridge's
 * OpenGL translation renderers (GL4ES, MobileGlues, Zink, ...) do not apply. What does apply
 * is the Vulkan driver: the same drivers DroidBridge offers its Java engine (bundled Turnip,
 * imported Turnip or PanVK packages) load through its driver helper library, and the engine hands
 * that loader to wgpu instead of the system one.
 */
public final class HorizonGraphics {
    private HorizonGraphics() {
    }

    /** One row of the renderer choice. */
    public static final class Choice {
        @NonNull public final String id;
        @NonNull public final String label;

        Choice(@NonNull String id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }
    }

    @NonNull
    public static List<Choice> rendererChoices() {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(EnginePreferences.HORIZON_RENDERER_VULKAN, "Vulkan (recommended)"));
        choices.add(new Choice(EnginePreferences.HORIZON_RENDERER_VULKAN_BATCHING, "Vulkan with GPU batching"));
        choices.add(new Choice(EnginePreferences.HORIZON_RENDERER_OPENGL, "OpenGL ES (experimental)"));
        choices.add(new Choice(EnginePreferences.HORIZON_RENDERER_ENGINE, "Horizon's own setting"));
        return choices;
    }

    @NonNull
    public static String rendererLabel(@NonNull String id) {
        for (Choice choice : rendererChoices()) {
            if (choice.id.equals(id)) return choice.label;
        }
        return "Vulkan (recommended)";
    }

    /** "System Vulkan driver" first, then DroidBridge's Vulkan drivers by their own names. */
    @NonNull
    public static List<Choice> driverChoices(@NonNull Context context) {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice("", DriverPluginManager.SYSTEM_VULKAN_DRIVER));
        try {
            for (Driver driver : DriverPluginManager.getDrivers(context)) {
                if (isLoadable(driver)) choices.add(new Choice(driver.getName(), driver.getName()));
            }
        } catch (Throwable ignored) {
            // Without the driver list, the system driver is still a choice.
        }
        return choices;
    }

    @NonNull
    public static String driverLabel(@NonNull String name) {
        return name.isEmpty() ? DriverPluginManager.SYSTEM_VULKAN_DRIVER : name;
    }

    /** Adds the saved graphics choices to a game launch (not to Horizon's settings screen). */
    public static void applyTo(@NonNull Context context, @NonNull Intent intent) {
        String renderer = EnginePreferences.getHorizonRenderer(context);
        if (!renderer.isEmpty()) {
            intent.putExtra(HorizonActivity.EXTRA_RENDERER, renderer);
        }
        if (EnginePreferences.HORIZON_RENDERER_OPENGL.equals(renderer)) return;

        String name = EnginePreferences.getHorizonVulkanDriver(context);
        if (name.isEmpty()) return;
        Driver driver = findDriver(context, name);
        if (driver == null) {
            LauncherLogManager.append("Horizon: Vulkan driver '" + name
                    + "' is no longer installed; using the system Vulkan driver");
            return;
        }
        File nativeDir = driver.getNativeLibraryDir();
        File library = driver.getVulkanLibrary();
        intent.putExtra(HorizonActivity.EXTRA_VULKAN_DRIVER_NAME, driver.getName());
        intent.putExtra(HorizonActivity.EXTRA_VULKAN_DRIVER_DIR, nativeDir.getAbsolutePath());
        intent.putExtra(HorizonActivity.EXTRA_VULKAN_DRIVER_LIBRARY, library.getAbsolutePath());
        intent.putExtra(HorizonActivity.EXTRA_VULKAN_DRIVER_TURNIP, driver.getType() == Driver.Type.TURNIP);
        LauncherLogManager.append("Horizon: Vulkan driver " + driver.getName() + " (" + library + ")");
    }

    @Nullable
    private static Driver findDriver(@NonNull Context context, @NonNull String name) {
        try {
            for (Driver driver : DriverPluginManager.getDrivers(context)) {
                if (name.equals(driver.getName()) && isLoadable(driver)) return driver;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isLoadable(@Nullable Driver driver) {
        if (driver == null) return false;
        File nativeDir = driver.getNativeLibraryDir();
        File library = driver.getVulkanLibrary();
        return nativeDir != null && nativeDir.isDirectory() && library != null && library.isFile();
    }
}
