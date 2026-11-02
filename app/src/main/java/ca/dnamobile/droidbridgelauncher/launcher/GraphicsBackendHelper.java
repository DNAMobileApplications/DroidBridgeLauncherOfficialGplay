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

package ca.dnamobile.droidbridgelauncher.launcher;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.modcompat.PreLaunchModScan;
import ca.dnamobile.droidbridgelauncher.instance.DefaultMinecraftOptionsInstaller;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Handles Mojang's built-in Graphics API setting for vanilla Minecraft 26.2+.
 *
 * DroidBridge intentionally leaves this setting alone for pre-26.2 Minecraft versions
 * and for 26.2 instances that contain VulkanMod. VulkanMod has its own backend
 * compatibility path and must not have its options.txt preference rewritten here.
 */
public final class GraphicsBackendHelper {
    private static final String TAG = "GraphicsBackendHelper";
    private static final String PREFERRED_BACKEND_KEY = "preferredGraphicsBackend";
    private static final String LEGACY_BACKEND_KEY = "graphicsBackend";
    private static final String VSYNC_KEY = "enableVsync";
    private static final String STARTED_CLEANLY_KEY = "startedCleanly";
    private static final String MANAGED_BACKEND_MARKER = ".droidbridge/graphics-backend-managed";

    private GraphicsBackendHelper() {
    }

    /**
     * Resolves Mojang's built-in Graphics API for this launch after the renderer has
     * been selected. For Minecraft 26.2+, Vulkan Zink + Use System Vulkan Driver is
     * a Vulkan launch request even when the instance API preference is Default/inherit.
     * An explicit per-instance OpenGL choice remains authoritative.
     */
    @NonNull
    public static String resolveEffectiveGraphicsApiForLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull InstanceLaunchSettings.Settings settings,
            @Nullable RendererInterface renderer
    ) {
        return resolveEffectiveGraphicsApiForVersion(
                context,
                resolveEffectiveMinecraftVersion(launchVersionId, versionJson),
                settings,
                renderer
        );
    }

    /**
     * Version-resolved variant used by GameActivity before SDL3 loads. The early
     * native context setup and the later options.txt rewrite must agree on the
     * same backend, especially on Minecraft 26.3/RenderPearl.
     */
    @NonNull
    public static String resolveEffectiveGraphicsApiForVersion(
            @NonNull Context context,
            @NonNull String effectiveVersion,
            @NonNull InstanceLaunchSettings.Settings settings,
            @Nullable RendererInterface renderer
    ) {
        String backend = InstanceLaunchSettings.resolveEffectiveGraphicsApiMode(context, settings);
        boolean modernGraphicsApi = isMinecraft262OrNewerVersion(effectiveVersion);

        /*
         * Absolute precedence for Minecraft 26.2+:
         * Use System Vulkan Driver ALWAYS means Mojang/RenderPearl uses Vulkan.
         * Renderer defaults, stale OpenGL instance values, MobileGlues, and the
         * custom-PanVK/Zink compatibility route may not demote this to OpenGL.
         */
        if (modernGraphicsApi && LauncherPreferences.isUseSystemVulkanDriver(context)) {
            if (!InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(backend)) {
                Logging.i(TAG, "Minecraft 26.2+ System Vulkan lock: Graphics API "
                        + backend + " -> vulkan for " + effectiveVersion);
            }
            return InstanceLaunchSettings.GRAPHICS_API_VULKAN;
        }

        /*
         * Minecraft 26.2 still uses the older native-window path. On the tested
         * Mali/PanVK build that path rejects direct Android swapchain creation
         * with VK_ERROR_INVALID_EXTERNAL_HANDLE, while the same ICD works under
         * Zink. Keep the compatibility demotion strictly on 26.2.
         *
         * Minecraft 26.3+ moved to RenderPearl/SDL3 and must be allowed to use
         * its native Vulkan backend directly. A working Mali-G57/26.3 launch
         * proves that extending the 26.2 workaround to 26.3 was incorrect.
         * System Vulkan is handled above and therefore never enters this route.
         */
        if (isMinecraft262Version(effectiveVersion)
                && InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(backend)
                && DriverPluginManager.isVulkanZinkRenderer(renderer)
                && !InstanceLaunchSettings.resolveEffectiveSystemVulkanDriver(context, settings)) {
            String driverName = InstanceLaunchSettings.resolveEffectiveVulkanDriverName(
                    context, settings);
            if (DriverPluginManager.isPanVkDriver(
                    DriverPluginManager.getDriver(context, driverName))) {
                Logging.i(TAG, "Minecraft 26.2 PanVK/Zink stability route: Graphics API "
                        + backend + " -> opengl for " + effectiveVersion
                        + "; retaining selected PanVK ICD under Zink only for the "
                        + "26.2 native-window path");
                return InstanceLaunchSettings.GRAPHICS_API_OPENGL;
            }
        }

        return backend;
    }

    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        applyBeforeLaunch(context, launchVersionId, versionJson, gameDirectory, settings, false, null);
    }

    /**
     * Applies the launcher's selected Graphics API directly to vanilla Minecraft 26.2+.
     *
     * Semantics are intentionally strict:
     * - Default uses Minecraft's built-in default/fallback behavior.
     * - OpenGL explicitly writes opengl before launch.
     * - System Vulkan explicitly writes vulkan before launch.
     * - VulkanMod 26.2 is never rewritten here because its own compatibility layer
     *   owns backend selection.
     */
    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @NonNull InstanceLaunchSettings.Settings settings,
            boolean hasVulkanMod
    ) {
        applyBeforeLaunch(context, launchVersionId, versionJson, gameDirectory, settings, hasVulkanMod, null);
    }

    /**
     * Applies the 26.2+ backend policy with the already-resolved renderer. Vulkan Zink
     * plus the launcher System Vulkan switch means Minecraft itself must start on its
     * built-in Vulkan API unless the user explicitly forced OpenGL for this instance.
     */
    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @NonNull InstanceLaunchSettings.Settings settings,
            boolean hasVulkanMod,
            @Nullable RendererInterface renderer
    ) {
        String effectiveVersion = resolveEffectiveMinecraftVersion(launchVersionId, versionJson);
        if (!isMinecraft262OrNewerVersion(effectiveVersion)) {
            Logging.i(TAG, "Skipping graphics API override for pre-26.2 version: " + effectiveVersion);
            return;
        }

        // Keep the existing 26.2 VulkanMod exception. 26.2 VulkanMod owns its own
        // compatibility path and must not be confused with Mojang's built-in Vulkan backend.
        if (hasVulkanMod && isMinecraft262Version(effectiveVersion)) {
            Logging.i(TAG, "Skipping Minecraft 26.2 Graphics API options override because VulkanMod "
                    + "was detected; leaving options.txt backend selection to VulkanMod compatibility");
            return;
        }

        String backend = resolveEffectiveGraphicsApiForLaunch(
                context, launchVersionId, versionJson, settings, renderer);
        File optionsFile = new File(gameDirectory, "options.txt");
        File managedMarker = new File(gameDirectory, MANAGED_BACKEND_MARKER);
        boolean hasExplicitChoice = settings.hasGraphicsApiOverride();
        boolean hasGlobalChoice = LauncherPreferences.isUseSystemVulkanDriver(context)
                || LauncherPreferences.isUseOpenGlForMinecraft26Plus(context);
        // Do not rewrite modpack/user graphics settings when the launcher has never
        // owned this option and neither instance nor global settings request it.
        if (!hasExplicitChoice && !hasGlobalChoice && !managedMarker.isFile()) {
            Logging.i(TAG, "Graphics API default: retaining Minecraft options without override for "
                    + effectiveVersion + " options=" + optionsFile.getAbsolutePath());
            return;
        }

        try {
            // Always seed a complete options file before forcing the backend. This keeps the
            // first-launch path from replacing a tiny backend-only options.txt later in startup.
            if (!optionsFile.isFile()) {
                boolean seeded = DefaultMinecraftOptionsInstaller.tryInstallIfMissingForLaunch(
                        context,
                        gameDirectory,
                        launchVersionId
                );
                Logging.i(TAG, "First-launch graphics API preparation seededOptions=" + seeded
                        + " backend=" + backend
                        + " options=" + optionsFile.getAbsolutePath());
            }

            // The launcher VSync switch owns Vulkan presentation. That includes
            // Vulkan Zink even when PanVK stability routing deliberately makes
            // Minecraft use its OpenGL frontend: Zink still presents through
            // Vulkan underneath, so leaving enableVsync untouched would let a
            // stale Minecraft option keep VSync enabled after Force VSync is off.
            boolean vulkanPresentation = InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(backend)
                    || DriverPluginManager.isVulkanZinkRenderer(renderer);
            Boolean forcedVsync = vulkanPresentation
                    ? LauncherPreferences.isVulkanVsyncEnabled(context)
                    : null;

            /*
             * Minecraft's options loader decodes the Graphics API through its option
             * codec, so string enum values must remain JSON strings in options.txt.
             * Writing raw `vulkan`/`opengl` on 26.3 makes the option get reported as
             * "Skipping bad option" and Minecraft silently falls back to OpenGL while
             * DroidBridge has already prepared the System Vulkan runtime. Keep the same
             * quoted representation for every supported 26.2+ build.
             */
            writeBackendValue(
                    optionsFile,
                    backend,
                    forcedVsync,
                    InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(backend)
            );
            // Mark this options file as managed so that switching back to launcher
            // default restores Minecraft's default even after a previous Vulkan choice.
            File markerParent = managedMarker.getParentFile();
            if (markerParent != null && !markerParent.exists()) markerParent.mkdirs();
            if (!managedMarker.isFile()) managedMarker.createNewFile();
            Logging.i(TAG, "Applied Minecraft 26.2+ graphics backend=" + backend
                    + " (preferredGraphicsBackend + graphicsBackend)"
                    + " encoding=quoted-json"
                    + " startedCleanly=" + (InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(backend) ? "true" : "unchanged")
                    + (forcedVsync != null ? " enableVsync=" + forcedVsync : "")
                    + " for Minecraft " + effectiveVersion
                    + " options=" + optionsFile.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to set Graphics API backend for Minecraft "
                    + effectiveVersion + " backend=" + backend, throwable);
        }
    }


    /**
     * Recover only a confirmed Minecraft 26.2+ graphics-driver startup failure.
     * The selected instance is resolved by its exact ID, not a version label, so
     * multiple installs of the same Minecraft version remain independent.
     *
     * An abrupt native SIGSEGV cannot be handled inside the crashed JVM. The
     * launcher calls this from its surviving process, and the following launch
     * then uses OpenGL. Explicit Vulkan/OpenGL preferences are never replaced.
     */
    public static boolean applyOpenGlFallbackAfterFailure(
            @NonNull Context context,
            @Nullable String instanceId,
            @Nullable File crashLog
    ) {
        if (instanceId == null || instanceId.trim().isEmpty()
                || crashLog == null || !crashLog.isFile()) return false;

        LauncherInstance instance = LauncherInstanceManager.findByNameOrId(context, instanceId.trim());
        if (instance == null || !instanceId.trim().equals(instance.getId())) return false;
        if (!isMinecraft262OrNewerVersion(instance.getBaseVersionId())) return false;

        // System Vulkan is an explicit 26.2+ policy. Do not let crash recovery
        // persist OpenGL behind the user's back after a failed launch.
        if (LauncherPreferences.isUseSystemVulkanDriver(context)) {
            Logging.i(TAG, "Skipping OpenGL recovery for " + instance.getId()
                    + ": Minecraft 26.2+ System Vulkan lock is enabled");
            return false;
        }

        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(context, instance.getId());

        /*
         * Explicit per-instance Vulkan/OpenGL choices are never rewritten. The
         * global System Vulkan case has already returned above because 26.2+ now
         * treats that switch as an absolute Vulkan policy. Automatic recovery is
         * therefore limited to Minecraft Default/inherit launches only.
         */
        // InstanceLaunchSettings.load() already normalizes this persisted value.
        String perInstanceMode = settings.graphicsApiMode;
        if (InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(perInstanceMode)
                || InstanceLaunchSettings.GRAPHICS_API_OPENGL.equals(perInstanceMode)) {
            return false;
        }

        String effectiveMode = InstanceLaunchSettings.resolveEffectiveGraphicsApiMode(
                context, settings);
        boolean minecraftDefault = InstanceLaunchSettings.GRAPHICS_API_DEFAULT.equals(effectiveMode);
        boolean inheritedGlobalVulkan = isMinecraft264Version(instance.getBaseVersionId())
                && !settings.hasGraphicsApiOverride()
                && InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(effectiveMode)
                && LauncherPreferences.isUseSystemVulkanDriver(context);
        if (!minecraftDefault && !inheritedGlobalVulkan) return false;

        if (PreLaunchModScan.scan(instance.getGameDirectory()).hasVulkanMod
                && isMinecraft262Version(instance.getBaseVersionId())) {
            return false;
        }

        String log = readFailureLog(crashLog).toLowerCase(Locale.ROOT);
        if (!log.contains("===== droidbridge launch =====") || !log.contains("game: ")) {
            return false;
        }
        if (minecraftDefault && !log.contains("graphics api: default")) return false;
        if (inheritedGlobalVulkan
                && (!log.contains("graphics api: vulkan")
                || !log.contains("use system vulkan driver setting: on"))) {
            return false;
        }

        boolean incompatibleDriver =
                (log.contains("graphics driver")
                        && (log.contains("incompatible") || log.contains("unsupported")
                        || log.contains("not supported") || log.contains("does not support")))
                || log.contains("incompatible graphics driver")
                || log.contains("no compatible graphics device")
                || log.contains("graphics backend is not supported")
                || log.contains("failed to create a vulkan device")
                || log.contains("no suitable vulkan device")
                || log.contains("vulkan is not supported by the graphics driver")
                || (log.contains("failed to create backend vulkan")
                        && (log.contains("device missing capabilities")
                        || log.contains("does not support required features")
                        || log.contains("does not support required extensions")));
        // Known 26.3 Default + MobileGlues early-native-crash signature. Never
        // interpret an unrelated SIGSEGV or a shader/mod warning as driver failure.
        boolean knownDefaultMobileGluesCrash =
                minecraftDefault
                && isMinecraft263Version(instance.getBaseVersionId())
                && log.contains("renderer: mobileglues")
                && log.contains("a fatal error has been detected by the java runtime environment")
                && log.contains("libmobileglues.so")
                && log.contains("glteximage2d");
        if (!incompatibleDriver && !knownDefaultMobileGluesCrash) return false;

        settings.graphicsApiMode = InstanceLaunchSettings.GRAPHICS_API_OPENGL;
        InstanceLaunchSettings.save(context, instance.getId(), settings);
        Logging.i(TAG, "Recognized graphics backend failure for instance=" + instance.getId()
                + " signature=" + (knownDefaultMobileGluesCrash ? "26.3-mobileglues-texture" : "driver-incompatible")
                + " source=" + (inheritedGlobalVulkan ? "global-system-vulkan" : "minecraft-default")
                + "; selected OpenGL for next launch; global Vulkan and explicit per-instance Vulkan remain unchanged");
        return true;
    }

    private static boolean isMinecraft263Version(@NonNull String version) {
        int[] number = findLastDottedVersion(version.trim().toLowerCase(Locale.ROOT));
        return number != null && number[0] == 26 && number[1] == 3;
    }

    private static boolean isMinecraft264Version(@NonNull String version) {
        int[] number = findLastDottedVersion(version.trim().toLowerCase(Locale.ROOT));
        return number != null && number[0] == 26 && number[1] == 4;
    }

    @NonNull
    private static String readFailureLog(@NonNull File file) {
        // The header identifies the selected Graphics API; the tail carries the
        // crash. Do not load an arbitrarily large Minecraft log into Android UI RAM.
        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            long size = reader.length();
            int head = (int) Math.min(size, 128 * 1024L);
            byte[] beginning = new byte[head];
            reader.readFully(beginning);
            if (size <= head) return new String(beginning, StandardCharsets.UTF_8);
            int tail = (int) Math.min(size - head, 512 * 1024L);
            byte[] ending = new byte[tail];
            reader.seek(size - tail);
            reader.readFully(ending);
            return new String(beginning, StandardCharsets.UTF_8)
                    + "\n" + new String(ending, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Logging.e(TAG, "Unable to inspect graphics failure log", e);
            return "";
        }
    }

    /** Writes the chosen Graphics API when Save is pressed, before first launch. */
    public static void applyForSavedInstanceSettings(
            @NonNull Context context,
            @NonNull LauncherInstance instance,
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        String minecraftVersion = instance.getMinecraftVersionId();
        JSONObject versionInfo = new JSONObject();
        try {
            versionInfo.put("id", minecraftVersion);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare instance graphics API version", throwable);
            return;
        }
        boolean hasVulkanMod = PreLaunchModScan.scan(instance.getGameDirectory()).hasVulkanMod;
        applyBeforeLaunch(context, minecraftVersion, versionInfo,
                instance.getGameDirectory(), settings, hasVulkanMod);
    }

    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory
    ) {
        applyBeforeLaunch(
                context,
                launchVersionId,
                versionJson,
                gameDirectory,
                new InstanceLaunchSettings.Settings(),
                false
        );
    }
    @NonNull
    private static String resolveEffectiveMinecraftVersion(
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
        if (!inheritsFrom.isEmpty()) return inheritsFrom;

        String flattenedParent = versionJson.optString("javaLauncherFlattenedParent", "").trim();
        if (!flattenedParent.isEmpty()) return flattenedParent;

        String id = versionJson.optString("id", launchVersionId).trim();
        return id.isEmpty() ? launchVersionId : id;
    }

    public static boolean isMinecraft262(
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        return isMinecraft262Version(
                resolveEffectiveMinecraftVersion(launchVersionId, versionJson));
    }

    public static boolean supportsBuiltInGraphicsApi(
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        return isMinecraft262OrNewerVersion(
                resolveEffectiveMinecraftVersion(launchVersionId, versionJson));
    }

    private static boolean isMinecraft262Version(@NonNull String rawVersion) {
        int[] version = findLastDottedVersion(rawVersion.trim().toLowerCase(Locale.ROOT));
        return version != null && version[0] == 26 && version[1] == 2;
    }

    public static boolean isMinecraft262OrNewerVersionId(@NonNull String rawVersion) {
        return isMinecraft262OrNewerVersion(rawVersion);
    }

    private static boolean isMinecraft262OrNewerVersion(@NonNull String rawVersion) {
        int[] version = findLastDottedVersion(rawVersion.trim().toLowerCase(Locale.ROOT));
        if (version == null) return false;
        return version[0] > 26 || (version[0] == 26 && version[1] >= 2);
    }

    @Nullable
    private static int[] findLastDottedVersion(@NonNull String value) {
        int[] last = null;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            if (!Character.isDigit(value.charAt(i))) continue;

            int majorStart = i;
            int majorEnd = majorStart;
            while (majorEnd < length && Character.isDigit(value.charAt(majorEnd))) majorEnd++;
            if (majorEnd >= length || value.charAt(majorEnd) != '.') {
                i = majorEnd;
                continue;
            }

            int minorStart = majorEnd + 1;
            int minorEnd = minorStart;
            while (minorEnd < length && Character.isDigit(value.charAt(minorEnd))) minorEnd++;
            if (minorEnd == minorStart) {
                i = minorEnd;
                continue;
            }

            try {
                int major = Integer.parseInt(value.substring(majorStart, majorEnd));
                int minor = Integer.parseInt(value.substring(minorStart, minorEnd));
                last = new int[]{major, minor};
            } catch (Throwable ignored) {
                // Keep scanning for the Minecraft version after a loader version.
            }
            i = minorEnd - 1;
        }
        return last;
    }

    private static void writeBackendValue(
            @NonNull File optionsFile,
            @NonNull String backend,
            @Nullable Boolean forcedVsync,
            boolean clearStaleStartupRecovery
    ) throws Exception {
        File parent = optionsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create options directory: " + parent.getAbsolutePath());
        }

        List<String> lines = new ArrayList<>();
        if (optionsFile.isFile()) {
            String text = readFile(optionsFile);
            for (String line : text.split("\\r?\\n", -1)) {
                if (!line.isEmpty()) lines.add(line);
            }
        }

        boolean replacedPreferred = false;
        boolean replacedLegacy = false;
        boolean replacedVsync = false;
        boolean replacedStartedCleanly = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(PREFERRED_BACKEND_KEY + ":")) {
                lines.set(i, formatBackendOptionLine(PREFERRED_BACKEND_KEY, backend));
                replacedPreferred = true;
            } else if (line.startsWith(LEGACY_BACKEND_KEY + ":")) {
                lines.set(i, formatBackendOptionLine(LEGACY_BACKEND_KEY, backend));
                replacedLegacy = true;
            } else if (forcedVsync != null && line.startsWith(VSYNC_KEY + ":")) {
                lines.set(i, VSYNC_KEY + ":" + forcedVsync);
                replacedVsync = true;
            } else if (clearStaleStartupRecovery && line.startsWith(STARTED_CLEANLY_KEY + ":")) {
                lines.set(i, STARTED_CLEANLY_KEY + ":true");
                replacedStartedCleanly = true;
            }
        }

        // Minecraft 26.2 builds have used both names during the backend-option
        // rollout. Always write and synchronize both keys. Previously the legacy
        // key was only updated after Minecraft had launched once and created it,
        // which is why Vulkan/OpenGL appeared as Default on a brand-new instance.
        if (!replacedPreferred) {
            lines.add(formatBackendOptionLine(PREFERRED_BACKEND_KEY, backend));
        }
        if (!replacedLegacy) {
            lines.add(formatBackendOptionLine(LEGACY_BACKEND_KEY, backend));
        }
        if (forcedVsync != null && !replacedVsync) {
            lines.add(VSYNC_KEY + ":" + forcedVsync);
        }
        if (clearStaleStartupRecovery && !replacedStartedCleanly) {
            lines.add(STARTED_CLEANLY_KEY + ":true");
        }

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            out.append(line).append('\n');
        }

        try (FileOutputStream output = new FileOutputStream(optionsFile)) {
            output.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }


    @NonNull
    private static String formatBackendOptionLine(
            @NonNull String key,
            @NonNull String backend
    ) {
        return key + ":\"" + backend + "\"";
    }

    @NonNull
    private static String readFile(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }
}
