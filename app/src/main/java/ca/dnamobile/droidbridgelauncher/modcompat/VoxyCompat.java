/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, Voxy, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.LaunchPlan;
import ca.dnamobile.droidbridgelauncher.renderer.KopperZinkRenderer;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;

/**
 * Android compatibility policy for Voxy.
 *
 * Voxy 0.2.x branches used by Minecraft 1.21.11 through 26.2 are desktop-OpenGL mods, and their upstream distributions also
 * contains desktop-Linux JNI dependencies.  DroidBridge handles those two
 * concerns separately:
 *
 *  - preserve the renderer selected by the user for A/B testing;
 *  - arm the launcher javaagent's Voxy transformer, which keeps Voxy away from
 *    glibc/x86-only native storage/compression dependencies on Android.
 *
 * No Voxy jar is rewritten on disk, and neither the effective nor saved
 * renderer selection is overridden by this compatibility helper.
 */
public final class VoxyCompat {
    private static final String TAG = "VoxyCompat";
    private static final String COMPAT_PROPERTY = "-Ddroidbridge.voxy.compat=";
    private static final String GEOMETRY_BUFFER_PROPERTY = "-Dvoxy.geometryBufferSizeOverrideMB=";
    private static final String MEMORY_STORE_LIMIT_PROPERTY = "-Ddroidbridge.voxy.memoryStorageLimitMB=";
    private static final String DEFAULT_GEOMETRY_BUFFER_MB = "256";
    private static final String DEFAULT_MEMORY_STORE_LIMIT_MB = "192";

    private VoxyCompat() {
    }

    public static boolean isUsingKopperZink(@NonNull RendererInterface renderer) {
        return KopperZinkRenderer.isRenderer(renderer);
    }

    @NonNull
    public static LaunchPlan applyLaunchPlanCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull PreLaunchModScan scan
    ) {
        if (!scan.hasVoxy) return plan;

        ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());
        boolean hasGeometryOverride = false;
        boolean hasMemoryStoreLimit = false;
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (arg == null) continue;
            if (arg.startsWith(COMPAT_PROPERTY)) {
                args.remove(i);
                continue;
            }
            if (arg.startsWith(GEOMETRY_BUFFER_PROPERTY)) {
                hasGeometryOverride = true;
            } else if (arg.startsWith(MEMORY_STORE_LIMIT_PROPERTY)) {
                hasMemoryStoreLimit = true;
            }
        }
        args.add(COMPAT_PROPERTY + "true");
        if (!hasGeometryOverride) {
            args.add(GEOMETRY_BUFFER_PROPERTY + DEFAULT_GEOMETRY_BUFFER_MB);
        }
        if (!hasMemoryStoreLimit) {
            args.add(MEMORY_STORE_LIMIT_PROPERTY + DEFAULT_MEMORY_STORE_LIMIT_MB);
        }
        Logging.i(TAG, "Voxy 0.2.x Android safety limits: geometry="
                + (hasGeometryOverride ? "custom" : DEFAULT_GEOMETRY_BUFFER_MB + "MB")
                + ", sessionStore="
                + (hasMemoryStoreLimit ? "custom" : DEFAULT_MEMORY_STORE_LIMIT_MB + "MB"));
        return plan.copyWithJvmArgs(args);
    }
}
