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

package ca.dnamobile.droidbridgelauncher.renderer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Version-gated SimpleFPEWrapper integration for MobileGlues renderer plugins.
 *
 * <p>SimpleFPEWrapper is only required for the legacy fixed-function Minecraft
 * path (1.16.5 and older). Minecraft 1.17+ must resolve GL directly through
 * MobileGlues; layering SimpleFPEWrapper there changes the authoritative GL
 * proc-address provider and can destabilize modern GLES drivers.</p>
 *
 * <p>SimpleFPEWrapper is a GL/FPE layer, not a complete EGL provider. On legacy
 * launches MobileGlues remains DroidBridge's EGL/context provider and
 * SFPEW_EGL points at that exact MobileGlues library.</p>
 */
public final class SimpleFpeWrapperCompat {
    public static final String LIBRARY_NAME = "libSimpleFPEWrapper.so";
    public static final String ENV_ACTIVE = "DROIDBRIDGE_SIMPLEFPE_ACTIVE";
    public static final String ENV_LIBRARY = "DROIDBRIDGE_SIMPLEFPE_LIBRARY";
    public static final String ENV_BACKEND = "DROIDBRIDGE_SIMPLEFPE_BACKEND";
    public static final String ENV_SFPEW_EGL = "SFPEW_EGL";

    private static final String[] MOBILEGLUES_LIBRARY_NAMES = new String[]{
            "libmobileglues.so",
            "libMobileGlues.so"
    };

    private SimpleFpeWrapperCompat() {
    }

    /**
     * Returns the launch environment for the legacy SimpleFPEWrapper route.
     *
     * <p>The reset values are intentionally returned even when the wrapper is
     * disabled. Renderer/plugin variables are merged before this helper in both
     * launch phases, so these empty values guarantee that stale process state or
     * an over-eager plugin cannot accidentally layer SimpleFPEWrapper onto
     * Minecraft 1.17+.</p>
     */
    @NonNull
    public static Map<String, String> buildEnvironment(
            @NonNull RendererInterface renderer,
            @Nullable String minecraftVersion
    ) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        resetEnvironment(env);

        if (!MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) return env;
        if (!shouldEnableForMinecraftVersion(minecraftVersion)) return env;

        File wrapper = findWrapperLibrary(renderer);
        if (wrapper == null) return env;

        String backend = resolveMobileGluesBackend(renderer);
        if (backend.isEmpty()) return env;

        env.put(ENV_ACTIVE, "1");
        env.put(ENV_LIBRARY, wrapper.getAbsolutePath());
        env.put(ENV_BACKEND, backend);
        env.put(ENV_SFPEW_EGL, backend);
        return env;
    }

    /** Apply the version-gated wrapper environment after renderer/plugin variables are merged. */
    public static boolean applyEnvironment(
            @NonNull Map<String, String> env,
            @NonNull RendererInterface renderer,
            @Nullable String minecraftVersion
    ) {
        Map<String, String> wrapperEnv = buildEnvironment(renderer, minecraftVersion);
        env.putAll(wrapperEnv);
        return isActiveEnvironment(wrapperEnv);
    }

    /** True only for release-like Minecraft versions at or below 1.16.5. */
    public static boolean shouldEnableForMinecraftVersion(@Nullable String minecraftVersion) {
        String normalized = RendererVersionRules.normalizeMinecraftRelease(minecraftVersion);
        return normalized != null
                && RendererVersionRules.compareVersions(normalized, "1.16.5") <= 0;
    }

    public static boolean isActiveEnvironment(@NonNull Map<String, String> env) {
        return "1".equals(env.get(ENV_ACTIVE));
    }

    private static void resetEnvironment(@NonNull Map<String, String> env) {
        env.put(ENV_ACTIVE, "");
        env.put(ENV_LIBRARY, "");
        env.put(ENV_BACKEND, "");
        env.put(ENV_SFPEW_EGL, "");
    }

    public static boolean isWrapperLibrary(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return false;
        return LIBRARY_NAME.equalsIgnoreCase(new File(path.trim()).getName());
    }

    @Nullable
    public static File findWrapperLibrary(@NonNull RendererInterface renderer) {
        LinkedHashSet<File> candidates = new LinkedHashSet<>();

        for (String library : renderer.getDlopenLibrary()) {
            if (isWrapperLibrary(library)) {
                File candidate = new File(library.trim());
                if (candidate.isAbsolute()) candidates.add(candidate);
            }
        }

        String rendererLibrary = renderer.getRendererLibrary();
        if (rendererLibrary != null && !rendererLibrary.trim().isEmpty()) {
            File main = new File(rendererLibrary.trim());
            File parent = main.isAbsolute() ? main.getParentFile() : null;
            if (parent != null) candidates.add(new File(parent, LIBRARY_NAME));
        }

        for (File searchPath : renderer.getLibrarySearchPaths()) {
            if (searchPath != null) candidates.add(new File(searchPath, LIBRARY_NAME));
        }

        for (File candidate : candidates) {
            File readable = canonicalReadableFile(candidate);
            if (readable != null) return readable;
        }
        return null;
    }

    @NonNull
    private static String resolveMobileGluesBackend(@NonNull RendererInterface renderer) {
        String rawLibrary = renderer.getRendererLibrary();
        if (rawLibrary != null && !rawLibrary.trim().isEmpty()) {
            File raw = new File(rawLibrary.trim());
            if (raw.isAbsolute()) {
                File readable = canonicalReadableFile(raw);
                if (readable != null) return readable.getAbsolutePath();
            }
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (rawLibrary != null && !rawLibrary.trim().isEmpty()) {
            String rawName = new File(rawLibrary.trim()).getName();
            if (!rawName.isEmpty()) names.add(rawName);
        }
        for (String name : MOBILEGLUES_LIBRARY_NAMES) names.add(name);

        for (File searchPath : renderer.getLibrarySearchPaths()) {
            if (searchPath == null) continue;
            for (String name : names) {
                File readable = canonicalReadableFile(new File(searchPath, name));
                if (readable != null) return readable.getAbsolutePath();
            }
        }

        // The renderer itself can still be linker-resolvable by basename even
        // when Android does not expose its extracted file as a normal File.
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("mobileglues") && lower.endsWith(".so")) return name;
        }
        return "";
    }

    @Nullable
    private static File canonicalReadableFile(@NonNull File file) {
        try {
            if (!file.isFile() || !file.canRead()) return null;
            return file.getCanonicalFile();
        } catch (Throwable ignored) {
            return file.isFile() && file.canRead() ? file.getAbsoluteFile() : null;
        }
    }
}
