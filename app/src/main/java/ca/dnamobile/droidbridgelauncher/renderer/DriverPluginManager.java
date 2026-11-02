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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Vulkan ICD manager shared by Vulkan Zink and direct Vulkan clients such as VulkanMod.
 *
 * Renderer plugins are OpenGL wrappers. Vulkan drivers are separate ICD payloads such as
 * Turnip/Freedreno or PanVK. Keeping these separate prevents the renderer dropdown from
 * becoming polluted with Vulkan-driver packages while still letting one selected ICD back
 * both Zink and direct Vulkan loader requests.
 */
public final class DriverPluginManager {
    private static final String TAG = "DriverPluginManager";

    public static final String DEFAULT_MESA_DRIVER = "Bundled Turnip/Freedreno (default)";
    public static final String LEGACY_DEFAULT_MESA_DRIVER = "Default Mesa driver";
    public static final String SYSTEM_VULKAN_DRIVER = "System Vulkan driver";

    private static final String[] KNOWN_DRIVER_PLUGIN_PACKAGES = new String[]{
            "com.fcl.plugin.driver.freedreno",
            "com.fcl.plugin.driver.turnip",
            "com.fcl.plugin.turnip",
            "com.fcl.plugin.adreno",
            "com.bzlzhh.plugin.driver.freedreno",
            "com.bzlzhh.plugin.driver.turnip",
            "com.bzlzhh.plugin.turnip",
            "com.bzlzhh.plugin.adreno",
            "com.tungsten.fcl.plugin.driver.freedreno",
            "com.tungsten.fcl.plugin.driver.turnip"
    };

    private static final String[] TURNIP_LIBRARY_NAMES = new String[]{
            "libvulkan_freedreno.so",
            "libvulkan_turnip.so",
            "libvulkan_adreno.so"
    };

    private static final String PANVK_LIBRARY_NAME = "libvulkan_panfrost.so";
    private static final long MAX_DRIVER_PACKAGE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_DRIVER_LIBRARY_BYTES = 192L * 1024L * 1024L;
    private static final long MAX_DRIVER_VERSION_SCAN_BYTES = 64L * 1024L * 1024L;

    private static final Pattern MESA_VERSION_PATTERN = Pattern.compile(
            "Mesa\\s+([0-9]+\\.[0-9]+\\.[0-9]+(?:-devel)?(?:\\s*\\(git-[^)]+\\))?)"
    );
    private static final Pattern MESA_DEVEL_VERSION_PATTERN = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+-devel(?:\\s*\\(git-[^)]+\\))?)"
    );

    private static final String[] EMULATOR_TURNIP_LIBRARY_NAMES = new String[]{
            "vulkan.adreno.so",
            "vulkan.freedreno.so",
            "turnip.adreno.so"
    };

    private static final ArrayList<Driver> DRIVERS = new ArrayList<>();
    private static boolean initialized;

    private DriverPluginManager() {
    }

    public static synchronized void reload(@NonNull Context context) {
        initialized = false;
        init(context);
    }

    public static synchronized void init(@NonNull Context context) {
        PathManager.initContextConstants(context);
        if (initialized) return;
        initialized = true;
        DRIVERS.clear();

        // The old UI exposed both "Default Mesa driver" and "Bundled Turnip/Freedreno"
        // even though Vulkan Zink resolved the former to the latter. That made a
        // successful bundled-Turnip launch look like a no-driver/system-Vulkan test.
        // Expose one truthful default entry instead. Android system Vulkan remains the
        // separate "Use System Vulkan Driver" switch.
        Driver bundled = MesaZinkTurnipDriver.createDriverIfAvailable(context);
        if (bundled != null) {
            addDriver(new Driver(
                    DEFAULT_MESA_DRIVER,
                    Driver.Type.TURNIP,
                    bundled.getPackageName(),
                    bundled.getNativeLibraryDir(),
                    bundled.getVulkanLibrary()));
        } else {
            addDriver(new Driver(
                    "Automatic Vulkan driver (system fallback)",
                    Driver.Type.DEFAULT_MESA,
                    null, null, null));
        }
        discoverImportedDriverPackages(context);
        discoverInstalledDriverPlugins(context);
    }

    @NonNull
    public static synchronized List<Driver> getDrivers(@NonNull Context context) {
        init(context);
        return new ArrayList<>(DRIVERS);
    }

    @NonNull
    public static synchronized Driver getSelectedDriver(@NonNull Context context) {
        init(context);
        String selected = LauncherPreferences.getSelectedVulkanDriverName(context);
        if (isLegacyBundledSelection(selected)) {
            return DRIVERS.get(0);
        }
        for (int i = DRIVERS.size() - 1; i >= 0; i--) {
            Driver driver = DRIVERS.get(i);
            if (driver.getName().equals(selected)) return driver;
        }

        return DRIVERS.get(0);
    }

    /**
     * Resolves an optional instance-specific Vulkan driver name without mutating
     * the launcher-wide selection. Missing/deleted instance drivers gracefully
     * fall back to the current launcher selection.
     */
    @NonNull
    public static synchronized Driver getDriver(
            @NonNull Context context,
            @Nullable String driverNameOverride
    ) {
        init(context);
        if (driverNameOverride == null || driverNameOverride.trim().isEmpty()) {
            return getSelectedDriver(context);
        }

        String selected = driverNameOverride.trim();
        if (isLegacyBundledSelection(selected)) {
            return DRIVERS.get(0);
        }
        for (int i = DRIVERS.size() - 1; i >= 0; i--) {
            Driver driver = DRIVERS.get(i);
            if (driver.getName().equals(selected)) return driver;
        }

        Driver fallback = getSelectedDriver(context);
        Logging.i(TAG, "Instance Vulkan driver is unavailable; falling back to launcher selection: "
                + selected + " -> " + fallback.getName());
        return fallback;
    }

    private static boolean isLegacyBundledSelection(@Nullable String name) {
        if (name == null) return false;
        return LEGACY_DEFAULT_MESA_DRIVER.equals(name)
                || "Bundled Turnip/Freedreno".equals(name)
                || MesaZinkTurnipDriver.DRIVER_NAME.equals(name);
    }

    public static synchronized int indexOfDriver(@NonNull Context context, @Nullable String selectedName) {
        init(context);
        Driver selected = getSelectedDriver(context);
        String effectiveName = selectedName != null ? selectedName : selected.getName();
        for (int i = 0; i < DRIVERS.size(); i++) {
            if (effectiveName.equals(DRIVERS.get(i).getName())) return i;
        }
        for (int i = 0; i < DRIVERS.size(); i++) {
            if (selected.getName().equals(DRIVERS.get(i).getName())) return i;
        }
        return 0;
    }

    public static boolean isVulkanZinkRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getRendererId() + " " + renderer.getRendererName() + " " + renderer.getRendererLibrary())
                .toLowerCase(Locale.ROOT);
        return combined.contains("vulkan_zink")
                || combined.contains("zink")
                || combined.contains("osmesa")
                || DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer);
    }

    public static boolean isKopperZinkRenderer(@Nullable RendererInterface renderer) {
        return KopperZinkRenderer.isRenderer(renderer);
    }

    public static boolean isPanVkDriver(@Nullable Driver driver) {
        if (driver == null) return false;
        File library = driver.getVulkanLibrary();
        if (library != null && PANVK_LIBRARY_NAME.equalsIgnoreCase(library.getName())) return true;
        String combined = (driver.getName() + " "
                + (driver.getDescription() == null ? "" : driver.getDescription()))
                .toLowerCase(Locale.ROOT);
        return combined.contains("panvk") || combined.contains("panfrost");
    }

    /**
     * True only when the current renderer can actually consume the selected PanVK
     * ICD. Keeping the renderer check here prevents a stale PanVK selection from
     * lowering resolution for OpenGL ES/MobileGlues launches.
     */
    public static boolean isPanVkPerformanceModeActive(@NonNull Context context) {
        if (!LauncherPreferences.isPanVkPerformanceModeEnabled(context)
                || LauncherPreferences.isUseSystemVulkanDriver(context)) {
            return false;
        }
        Driver selected = getSelectedDriver(context);
        if (!isPanVkDriver(selected)) return false;
        return isVulkanZinkRenderer(Renderers.getCurrentRenderer(context));
    }

    @NonNull
    public static List<File> getSelectedDriverLibrarySearchPaths(@NonNull Context context, @Nullable RendererInterface renderer) {
        return getSelectedDriverLibrarySearchPaths(
                context,
                renderer,
                LauncherPreferences.isUseSystemVulkanDriver(context)
        );
    }

    @NonNull
    public static List<File> getSelectedDriverLibrarySearchPaths(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            boolean useSystemVulkan
    ) {
        return getSelectedDriverLibrarySearchPaths(context, renderer, useSystemVulkan, null);
    }

    @NonNull
    public static List<File> getSelectedDriverLibrarySearchPaths(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            boolean useSystemVulkan,
            @Nullable String driverNameOverride
    ) {
        ArrayList<File> paths = new ArrayList<>();

        // Driver plugins are Vulkan ICDs. Do not leak Turnip/Freedreno into LTW,
        // MobileGlues, GL4ES, or Krypton. LTW especially must use the normal
        // system Vulkan loader path used by the prior implementation, otherwise AdrenoSupp/Turnip
        // can be loaded before LTW and glMapBuffer/glDraw paths become unstable.
        if (!isVulkanZinkRenderer(renderer)) {
            return paths;
        }

        if (useSystemVulkan) {
            return paths;
        }

        Driver driver = getDriver(context, driverNameOverride);
        File dir;

        if (driver.getType() == Driver.Type.TURNIP || driver.getType() == Driver.Type.CUSTOM_ICD) {
            dir = driver.getNativeLibraryDir();
        } else {
            dir = findBundledDriverDir(context);
        }

        if (dir != null && dir.isDirectory()) paths.add(dir);
        return paths;
    }

    @NonNull
    public static Map<String, String> buildEnvironment(@NonNull Context context, @Nullable RendererInterface renderer) {
        return buildEnvironment(
                context,
                renderer,
                LauncherPreferences.isUseSystemVulkanDriver(context)
        );
    }

    @NonNull
    public static Map<String, String> buildEnvironment(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            boolean useSystemVulkan
    ) {
        return buildEnvironment(context, renderer, useSystemVulkan, null);
    }

    @NonNull
    public static Map<String, String> buildEnvironment(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            boolean useSystemVulkan,
            @Nullable String driverNameOverride
    ) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        boolean zink = isVulkanZinkRenderer(renderer);
        Driver driver = getDriver(context, driverNameOverride);

        // Zink must not silently fall through to Android's Qualcomm ICD when the
        // user has System Vulkan disabled. If the generic "Default Mesa driver" is
        // selected, make bundled Turnip/Freedreno the effective Vulkan driver.
        if (zink && !useSystemVulkan && driver.getType() == Driver.Type.DEFAULT_MESA) {
            Driver bundledTurnip = MesaZinkTurnipDriver.createDriverIfAvailable(context);
            if (bundledTurnip != null) {
                driver = bundledTurnip;
                Logging.i(TAG, "Zink: Default Mesa driver resolved to bundled Turnip/Freedreno");
            } else {
                Logging.i(TAG, "Zink: bundled Turnip/Freedreno unavailable; custom Vulkan cannot be forced");
            }
        }

        env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", useSystemVulkan ? "1" : "0");
        env.put("JAVA_LAUNCHER_VULKAN_DRIVER", useSystemVulkan ? SYSTEM_VULKAN_DRIVER : driver.getName());

        if (!zink) {
            env.put("DROIDBRIDGE_VULKAN_DRIVER_REPORT", "");
            env.put("DROIDBRIDGE_VULKAN_DRIVER_VERSION", "");
            env.put("DROIDBRIDGE_VULKAN_DRIVER_LIBRARY", "");
            // Non-Zink renderers are OpenGL wrappers. Clear stale custom Vulkan ICD
            // state from previous launches, but do NOT claim System Vulkan is active
            // unless the resolved launch mode actually selected it. The old hardcoded
            // value of 1 made MobileGlues/Krypton OpenGL launches inherit Vulkan
            // runtime behavior even when the per-instance API was explicitly OpenGL.
            env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", useSystemVulkan ? "1" : "0");
            env.put("DRIVER_PATH", "");
            env.put("DROIDBRIDGE_LOAD_CUSTOM_VULKAN", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_SUPPORT_DIR", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_DIR", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            env.put("LIBGL_DRIVERS_PATH", "");
            env.put("EGL_DRIVERS_PATH", "");
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "");
            env.put("GALLIUM_DRIVER", "");
            env.put("force_gl_vendor", "");
            return env;
        }

        applyDriverReportEnvironment(env, driver, useSystemVulkan);
        applyGlobalVulkanDriverEnvironment(context, env, driver, true, useSystemVulkan);
        if (isKopperZinkRenderer(renderer)) {
            applyKopperZinkEnvironment(context, env);
            // Kopper owns presentation through Mesa EGL/native-window. Never enable
            // the legacy OSMesa RGBA workaround for this renderer.
            DroidBridgeMesaSupport.applyZinkSurfaceWorkaround(null);
        } else {
            applyZinkMesaEnvironment(context, env);

            // v56: first force the safe Android presentation path for all legacy Vulkan Zink
            // launches. The log proves Zink creates a context, while the Adreno 740
            // visual issue remains in the final displayed frame.
            DroidBridgeMesaSupport.applyZinkSurfaceWorkaround(renderer);

            // Explicit custom Mesa-Zink entries can still use the experimental route.
            DroidBridgeMesaSupport.applyZinkTurnipEnvironment(context, renderer, env);
        }
        if (!useSystemVulkan && isPanVkDriver(driver)
                && LauncherPreferences.isPanVkPerformanceModeEnabled(context)) {
            applyPanVkPerformanceEnvironment(env);
        }
        if (useSystemVulkan) {
            applyGlobalVulkanDriverEnvironment(context, env, driver, true, true);
            applyDriverReportEnvironment(env, driver, true);
            env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "1");
            env.put("JAVA_LAUNCHER_VULKAN_DRIVER", SYSTEM_VULKAN_DRIVER);
        }
        return env;
    }

    private static void applyPanVkPerformanceEnvironment(
            @NonNull LinkedHashMap<String, String> env
    ) {
        // Keep correctness-risky features such as PANVK_AFBC and forced early-Z off.
        // The FristOneRR G57 build does, however, expose several explicit performance
        // controls that are safe to gate behind DroidBridge's opt-in PanVK profile:
        // - max kbase DVFS asks the driver to hold the highest available GPU clock;
        // - overlap allows the driver's out-of-order/overlap scheduler;
        // - LS/UBO passes reduce CPU/shader-side load on Minecraft's Zink workload;
        // - lazy Zink descriptors reduce descriptor churn on this older Mesa frontend.
        // If the kernel refuses DVFS control the driver logs the refusal and continues.
        env.put("DROIDBRIDGE_PANVK_PERFORMANCE", "1");
        env.put("DROIDBRIDGE_PANVK_RENDER_SCALE_CAP",
                Integer.toString(LauncherPreferences.PANVK_PERFORMANCE_SCALE_CAP_PERCENT));
        env.put("PANVK_STATS", "0");
        env.put("PANVK_KBASE_DVFS", "max");
        env.put("PANVK_OVERLAP", "1");
        env.put("PANVK_LS_OPT", "1");
        env.put("PANVK_UBO_CSE", "1");
        env.put("PANVK_UBO_VEC", "1");
        env.put("ZINK_DESCRIPTORS", "lazy");
        env.put("MESA_SHADER_CACHE_DISABLE", "false");
        env.put("MESA_GLSL_CACHE_DISABLE", "false");
        Logging.i(TAG, "PanVK performance profile active: render-scale cap="
                + LauncherPreferences.PANVK_PERFORMANCE_SCALE_CAP_PERCENT
                + "% dvfs=max overlap=1 lsOpt=1 uboCse=1 uboVec=1 zinkDescriptors=lazy"
                + " PANVK_STATS=0 shaderCache=on");
    }

    private static void applyDirectFreedrenoKgslEnvironment(@NonNull LinkedHashMap<String, String> env) {
        // v61: saved Mesa Freedreno KGSL is no longer a Vulkan Zink alias.
        // Reinforce the renderer's direct-renderer direct Mesa env here because
        // DriverPluginManager is merged after renderer.getRendererEnv().
        env.put("DROIDBRIDGE_MESA", "1");
        env.put("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_MESA_SAFE_SWAPS", "1");
        env.put("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        env.put("force_gl_vendor", "freedreno/DroidBridge");
        env.put("DROIDBRIDGE_FREEDRENO_MESA_V61", "1");
        env.put("DROIDBRIDGE_MESA_AAR_SINGLE_SOURCE", "1");
        env.put("DROIDBRIDGE_FREEDRENO_MESA_AAR_V72", "1");

        env.put("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        env.put("GALLIUM_DRIVER", "");
        env.put("EGL_PLATFORM", "android");
        env.put("FORCE_VSYNC", "false");

        env.put("LIBGL_ES", "2");
        env.put("MESA_GL_VERSION_OVERRIDE", "3.3COMPAT");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "330");
        env.put("MESA_GLSL_CACHE_DISABLE", "false");
        env.put("MESA_SHADER_CACHE_DISABLE", "false");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_NOERROR", "0");
        env.put("allow_higher_compat_version", "true");
        env.put("force_glsl_extensions_warn", "true");
        env.put("allow_glsl_extension_directive_midshader", "true");

        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");

        env.put("DROIDBRIDGE_EGL", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("LIB_MESA_NAME", DroidBridgeMesaSupport.LIB_EGL_MESA);

        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "");
        env.put("DRIVER_PATH", "");
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_DIR", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_SUPPORT_DIR", "");
        env.put("DROIDBRIDGE_LOAD_CUSTOM_VULKAN", "");
        env.put("ZINK_DEBUG", "");
        env.put("ZINK_DESCRIPTORS", "");

        env.put("OSMESA_LIB", "");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("LIBGL_OSMESA", "");
    }

    private static void applyGlobalVulkanDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver,
            boolean zink,
            boolean useSystemVulkan
    ) {
        if (useSystemVulkan) {
            env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("DROIDBRIDGE_LOAD_CUSTOM_VULKAN", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_SUPPORT_DIR", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_DIR", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            return;
        }

        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "0");

        if (driver.getType() == Driver.Type.TURNIP) {
            applyTurnipDriverEnvironment(context, env, driver);
            return;
        }

        if (driver.getType() == Driver.Type.CUSTOM_ICD) {
            applyCustomVulkanDriverEnvironment(context, env, driver, false);
            return;
        }

        if (zink) {
            File bundled = findBundledDriverDir(context);
            if (bundled != null && bundled.isDirectory()) {
                env.put("DRIVER_PATH", bundled.getAbsolutePath());
            } else {
                // No custom/bundled Turnip is available. Fall back to system Vulkan
                // so Zink still has a usable Vulkan loader instead of a null path.
                env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
                env.put("DRIVER_PATH", "");
            }
        } else {
            // Non-Zink renderers can still launch Vulkan mods. If no custom driver
            // is selected, clear any stale Turnip env left from an earlier launch.
            env.put("DRIVER_PATH", "");
            env.put("DROIDBRIDGE_LOAD_CUSTOM_VULKAN", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_SUPPORT_DIR", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_DIR", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
        }
    }

    private static void applyTurnipDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver
    ) {
        applyCustomVulkanDriverEnvironment(context, env, driver, true);
    }

    private static void applyCustomVulkanDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver,
            boolean turnipCompatibility
    ) {
        File nativeDir = driver.getNativeLibraryDir();
        File vulkan = driver.getVulkanLibrary();
        if (nativeDir != null && nativeDir.isDirectory()) {
            env.put("DRIVER_PATH", nativeDir.getAbsolutePath());
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_DIR", nativeDir.getAbsolutePath());
            String appNativeDir = context.getApplicationInfo().nativeLibraryDir;
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_SUPPORT_DIR", appNativeDir != null ? appNativeDir : "");
            if (turnipCompatibility) {
                env.put("DROIDBRIDGE_TURNIP_DRIVER_DIR", nativeDir.getAbsolutePath());
            }
        }
        if (vulkan != null && vulkan.isFile()) {
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", vulkan.getAbsolutePath());
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME", vulkan.getName());
            if (turnipCompatibility) {
                env.put("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY", vulkan.getAbsolutePath());
            } else {
                env.put("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY", "");
            }
        }

        env.put("DROIDBRIDGE_LOAD_CUSTOM_VULKAN", "1");
        env.put("DROIDBRIDGE_LOAD_TURNIP", turnipCompatibility ? "1" : "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", turnipCompatibility ? "1" : "");
        env.put("DROIDBRIDGE_ZINK_PREFER_SYSTEM_DRIVER", "");
        env.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "");

        File icd = buildIcdFile(context, driver);
        if (icd != null && icd.isFile()) {
            env.put("VK_ICD_FILENAMES", icd.getAbsolutePath());
            env.put("VK_DRIVER_FILES", icd.getAbsolutePath());
        } else {
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
        }
    }

    private static void applyKopperZinkEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env
    ) {
        env.put("DROIDBRIDGE_RENDERER", KopperZinkRenderer.RENDERER_ID);
        env.put("POJAV_RENDERER", KopperZinkRenderer.RENDERER_ID);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
        env.put("POJAV_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
        env.put("DROIDBRIDGE_EGL", KopperZinkRenderer.EGL_LIBRARY);
        env.put("DROIDBRIDGE_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);
        env.put("POJAVEXEC_EGL", KopperZinkRenderer.EGL_LIBRARY);
        env.put("POJAVEXEC_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);

        env.put("GALLIUM_DRIVER", "zink");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("force_gl_vendor", "Mesa/DroidBridge");
        env.put("MESA_ANDROID_NO_KMS_SWRAST", "1");
        env.put("LIBGL_ES", "3");
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
        env.put("MESA_NO_ERROR", "0");
        env.put("LIBGL_NOERROR", "0");
        env.put("ZINK_DESCRIPTORS", "lazy");
        env.put("ZINK_DEBUG", "compact,noreorder");
        env.put("mesa_glthread", "false");
        env.put("MESA_VK_WSI_PRESENT_MODE", LauncherPreferences.isVulkanVsyncEnabled(context) ? "fifo" : "immediate");
        env.put("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");

        // Load Mesa EGL inside the same Kopper/Turnip namespace. v14 gives that
        // namespace a dedicated Vulkan-only alias directory first, then Android
        // /system, so Mesa's own libvulkan lookup cannot escape to Qualcomm while
        // platform dependencies still resolve against the real Android libraries.
        File mesaNativeDir = DroidBridgeMesaSupport.resolveMesaNativeDir(context);
        File mesaAliasDir = DroidBridgeMesaSupport.prepareMesaLibraryAliases(context, null);
        File kopperVulkanAliasDir = new File(PathManager.DIR_CACHE, "kopper-vulkan-loader");
        //noinspection ResultOfMethodCallIgnored
        kopperVulkanAliasDir.mkdirs();
        File staleKopperVulkanAlias = new File(kopperVulkanAliasDir, "libvulkan.so");
        if (staleKopperVulkanAlias.isFile() && !staleKopperVulkanAlias.delete()) {
            Logging.i(TAG, "Kopper Zink: could not delete stale Vulkan alias before launch: "
                    + staleKopperVulkanAlias.getAbsolutePath());
        }
        env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", kopperVulkanAliasDir.getAbsolutePath());
        String driverPath = env.get("DRIVER_PATH");
        StringBuilder namespacePath = new StringBuilder(kopperVulkanAliasDir.getAbsolutePath())
                .append(File.pathSeparator)
                .append(mesaNativeDir.getAbsolutePath());
        if (mesaAliasDir != null && mesaAliasDir.isDirectory()) {
            namespacePath.append(File.pathSeparator).append(mesaAliasDir.getAbsolutePath());
            env.put("DROIDBRIDGE_MESA_ALIAS_DIR", mesaAliasDir.getAbsolutePath());
        }
        if (driverPath != null && !driverPath.trim().isEmpty()
                && !driverPath.equals(mesaNativeDir.getAbsolutePath())) {
            namespacePath.append(File.pathSeparator).append(driverPath);
        }
        env.put("DROIDBRIDGE_MESA", "1");
        env.put("DROIDBRIDGE_MESA_MODE", "kopper_zink");
        env.put("DROIDBRIDGE_MESA_DRIVER", "zink");
        env.put("DROIDBRIDGE_MESA_NATIVE_DIR", mesaNativeDir.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "1");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", namespacePath.toString());
        env.put("DROIDBRIDGE_MESA_EGL", new File(mesaNativeDir, KopperZinkRenderer.EGL_LIBRARY).getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");

        // Most important difference from legacy Vulkan Zink: no OSMesa aliases.
        env.put("LIB_MESA_NAME", "");
        env.put("OSMESA_LIB", "");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("LIBGL_OSMESA", "");
        env.put("DROIDBRIDGE_ZINK_V57_FORCE_OSMESA_EGL", "");
        env.put("DROIDBRIDGE_ZINK_V61_CLEAN_ZINK", "");
        Logging.i(TAG, "Kopper Zink: dedicated Vulkan alias dir="
                + kopperVulkanAliasDir.getAbsolutePath()
                + " namespacePath=" + namespacePath);
    }

    private static void applyZinkMesaEnvironment(@NonNull Context context, @NonNull LinkedHashMap<String, String> env) {
        // Regular Vulkan Zink still uses the OSMesa frontend, but it must use the
        // same private Android Vulkan-loader alias that Kopper uses when System
        // Vulkan is disabled. The old generic unique-loader path can fail on
        // Android 13 while resolving libhidlbase/native_handle_close, which makes
        // OSMesa silently fall back to Qualcomm system Vulkan.
        String customVulkanLibrary = env.get("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER");
        boolean customVulkanRequested = !"1".equals(env.get("DROIDBRIDGE_USE_SYSTEM_VULKAN"))
                && "1".equals(env.get("DROIDBRIDGE_LOAD_CUSTOM_VULKAN"))
                && customVulkanLibrary != null
                && !customVulkanLibrary.trim().isEmpty();
        if (customVulkanRequested) {
            File zinkVulkanAliasDir = new File(PathManager.DIR_CACHE, "zink-vulkan-loader");
            //noinspection ResultOfMethodCallIgnored
            zinkVulkanAliasDir.mkdirs();
            File staleVulkanAlias = new File(zinkVulkanAliasDir, "libvulkan.so");
            if (staleVulkanAlias.isFile() && !staleVulkanAlias.delete()) {
                Logging.i(TAG, "Vulkan Zink: could not delete stale Vulkan alias before launch: "
                        + staleVulkanAlias.getAbsolutePath());
            }

            // Reuse the already-shipped native alias-loader implementation. This
            // flag name originated in the Kopper path, but the helper itself is
            // renderer-agnostic: it creates a private libvulkan.so clone and hooks
            // that loader to bundled Turnip/Freedreno before publishing VULKAN_PTR.
            env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");
            env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", zinkVulkanAliasDir.getAbsolutePath());
            Logging.i(TAG, "Vulkan Zink: using private Turnip Vulkan-loader alias dir="
                    + zinkVulkanAliasDir.getAbsolutePath());
        } else {
            // Do not leave strict custom-loader state behind when the user explicitly
            // selected System Vulkan or the bundled Turnip payload is unavailable.
            env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "");
            env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", "");
        }

        // v57: Always make the Java environment match the known-good Vulkan Zink
        // route. The latest Freedreno-alias log still had DROIDBRIDGE_RENDERER=vulkan_zink
        // but DROIDBRIDGE_EGL was overwritten back to libEGL_mesa.so, which made
        // LWJGL report "no OpenGL context current" even though OSMesaMakeCurrent
        // succeeded. Keep these values set for every Zink launch, not just the
        // legacy alias helper.
        env.put("DROIDBRIDGE_RENDERER", "vulkan_zink");
        env.put("DROIDBRIDGE_EGL", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("LIB_MESA_NAME", "libOSMesa_8.so");
        env.put("OSMESA_LIB", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "libOSMesa_8.so");
        env.put("OSMESA_LIBRARY", "libOSMesa_8.so");
        env.put("LIBGL_OSMESA", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_ZINK_V57_FORCE_OSMESA_EGL", "1");
        env.put("DROIDBRIDGE_ZINK_V61_CLEAN_ZINK", "1");

        // legacy Android launcher's OSMesa/Zink path expects these basic Mesa values.
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("GALLIUM_DRIVER", "zink");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NOERROR", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("MESA_SHADER_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("MESA_VK_WSI_PRESENT_MODE", LauncherPreferences.isVulkanVsyncEnabled(context) ? "fifo" : "immediate");
    }

    @Nullable
    private static File findBundledDriverDir(@NonNull Context context) {
        ArrayList<File> dirs = new ArrayList<>();
        if (context.getApplicationInfo().nativeLibraryDir != null) {
            addDirIfValid(dirs, new File(context.getApplicationInfo().nativeLibraryDir));
        }
        if (PathManager.DIR_NATIVE_LIB != null) addDirIfValid(dirs, new File(PathManager.DIR_NATIVE_LIB));
        for (File dir : dirs) {
            if (findTurnipLibrary(dir) != null) return dir;
        }
        return null;
    }

    private static void applyDriverReportEnvironment(
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver,
            boolean useSystemVulkan
    ) {
        if (useSystemVulkan) {
            env.put("DROIDBRIDGE_VULKAN_DRIVER_REPORT", SYSTEM_VULKAN_DRIVER);
            env.put("DROIDBRIDGE_VULKAN_DRIVER_VERSION", "Android system driver");
            env.put("DROIDBRIDGE_VULKAN_DRIVER_LIBRARY", "libvulkan.so");
            return;
        }

        String version = detectDriverVersion(driver);
        String report = driver.getName();
        if (version != null && !version.isEmpty()
                && !report.toLowerCase(Locale.ROOT).contains(version.toLowerCase(Locale.ROOT))) {
            report += " — " + version;
        }
        File library = driver.getVulkanLibrary();
        env.put("DROIDBRIDGE_VULKAN_DRIVER_REPORT", report);
        env.put("DROIDBRIDGE_VULKAN_DRIVER_VERSION", version != null ? version : "");
        env.put("DROIDBRIDGE_VULKAN_DRIVER_LIBRARY", library != null ? library.getName() : "");
    }

    @Nullable
    public static String detectDriverVersion(@NonNull Driver driver) {
        File nativeDir = driver.getNativeLibraryDir();
        if (nativeDir != null && nativeDir.isDirectory()) {
            File metaFile = new File(nativeDir, "meta.json");
            if (metaFile.isFile()) {
                try {
                    JSONObject meta = new JSONObject(readFileText(metaFile, 256 * 1024L));
                    String declared = firstNonBlank(
                            meta.optString("driverVersion", null),
                            meta.optString("packageVersion", null));
                    if (declared != null && !declared.equals("driver") && !declared.trim().isEmpty()) {
                        return declared.trim();
                    }
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Unable to read imported Vulkan driver metadata version from "
                            + metaFile.getAbsolutePath() + ": " + throwable);
                }
            }
        }

        File library = driver.getVulkanLibrary();
        if (library == null || !library.isFile()) return null;
        try {
            return detectMesaVersionFromLibrary(library);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read Vulkan driver version from "
                    + library.getAbsolutePath() + ": " + throwable);
            return null;
        }
    }

    @Nullable
    private static String detectMesaVersionFromLibrary(@NonNull File library) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        String carry = "";
        long total = 0L;
        try (InputStream input = new BufferedInputStream(new FileInputStream(library))) {
            while (total < MAX_DRIVER_VERSION_SCAN_BYTES) {
                int allowed = (int) Math.min(buffer.length, MAX_DRIVER_VERSION_SCAN_BYTES - total);
                int read = input.read(buffer, 0, allowed);
                if (read < 0) break;
                total += read;
                String chunk = carry + new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                Matcher matcher = MESA_VERSION_PATTERN.matcher(chunk);
                if (matcher.find()) {
                    return "Mesa " + matcher.group(1).trim();
                }
                Matcher develMatcher = MESA_DEVEL_VERSION_PATTERN.matcher(chunk);
                if (develMatcher.find()) {
                    return "Mesa " + develMatcher.group(1).trim();
                }
                int keep = Math.min(512, chunk.length());
                carry = chunk.substring(chunk.length() - keep);
            }
        }
        return null;
    }

    @NonNull
    public static String getDriverReport(@NonNull Context context) {
        Driver driver = getSelectedDriver(context);
        String version = detectDriverVersion(driver);
        if (version == null || version.isEmpty()
                || driver.getName().toLowerCase(Locale.ROOT).contains(version.toLowerCase(Locale.ROOT))) {
            return driver.getName();
        }
        return driver.getName() + " — " + version;
    }

    @Nullable
    private static File buildIcdFile(@NonNull Context context, @NonNull Driver driver) {
        File vulkan = driver.getVulkanLibrary();
        if (vulkan == null || !vulkan.isFile()) return null;

        File dir = new File(PathManager.DIR_CACHE, "vulkan_icd");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String libraryPath = vulkan.getAbsolutePath();
        String safeName = (driver.getPackageName() != null ? driver.getPackageName() : driver.getName())
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        File icd = new File(dir, safeName + ".json");

        try {
            JSONObject root = new JSONObject();
            JSONObject icdObject = new JSONObject();
            icdObject.put("library_path", libraryPath);
            icdObject.put("api_version", "1.3.0");
            root.put("file_format_version", "1.0.0");
            root.put("ICD", icdObject);

            try (FileOutputStream outputStream = new FileOutputStream(icd, false)) {
                outputStream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            return icd;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write Vulkan ICD for " + driver.getName(), throwable);
            return null;
        }
    }

    private static void addBuiltInTurnipIfAvailable(@NonNull Context context) {
        ArrayList<File> dirs = new ArrayList<>();
        if (context.getApplicationInfo().nativeLibraryDir != null) {
            addDirIfValid(dirs, new File(context.getApplicationInfo().nativeLibraryDir));
        }
        if (PathManager.DIR_NATIVE_LIB != null) addDirIfValid(dirs, new File(PathManager.DIR_NATIVE_LIB));

        for (File dir : dirs) {
            File vulkan = findTurnipLibrary(dir);
            if (vulkan != null) {
                addDriver(new Driver("Bundled Turnip/Freedreno", Driver.Type.TURNIP, null, dir, vulkan));
                return;
            }
        }
    }

    private static void addDirIfValid(@NonNull ArrayList<File> dirs, @Nullable File dir) {
        if (dir != null && dir.isDirectory() && !dirs.contains(dir)) dirs.add(dir);
    }

    private static void discoverInstalledDriverPlugins(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();

        for (String packageName : KNOWN_DRIVER_PLUGIN_PACKAGES) {
            try {
                PackageInfo info = getPackageInfo(pm, packageName);
                if (info != null) parsePluginPackage(context, info);
            } catch (Throwable ignored) {
            }
        }

        try {
            List<ApplicationInfo> apps;
            int flags = PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 33) {
                apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                apps = pm.getInstalledApplications(flags);
            }
            for (ApplicationInfo app : apps) {
                if (app == null || app.packageName == null) continue;
                PackageInfo info = getPackageInfo(pm, app.packageName);
                if (info != null) parsePluginPackage(context, info);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Installed driver plugin scan was limited by Android package visibility: " + throwable);
        }
    }

    @Nullable
    private static PackageInfo getPackageInfo(@NonNull PackageManager pm, @NonNull String packageName) {
        try {
            long flags = PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                return pm.getPackageInfo(packageName, (int) flags);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void parsePluginPackage(@NonNull Context context, @NonNull PackageInfo info) {
        ApplicationInfo app = info.applicationInfo;
        if (app == null || app.packageName == null) return;
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;

        String lowerPackage = app.packageName.toLowerCase(Locale.ROOT);
        Bundle meta = app.metaData;
        boolean fclPlugin = meta != null && meta.getBoolean("fclPlugin", false);
        String declaredDriver = meta != null ? meta.getString("driver") : null;
        boolean packageLooksLikeDriver = lowerPackage.contains("driver")
                || lowerPackage.contains("turnip")
                || lowerPackage.contains("freedreno")
                || lowerPackage.contains("adreno")
                || lowerPackage.contains("panvk")
                || lowerPackage.contains("panfrost")
                || lowerPackage.contains("mali");

        File nativeDir = app.nativeLibraryDir != null ? new File(app.nativeLibraryDir) : null;
        File vulkan = nativeDir != null ? findVulkanLibrary(nativeDir) : null;

        // Only real Vulkan driver plugins belong in this dropdown. Do not show
        // renderer plugins or generic legacy Android launcher plugins unless a Turnip/Freedreno
        // Vulkan library is actually present.
        if (vulkan == null) return;
        if (!fclPlugin && !packageLooksLikeDriver) return;
        if (nativeDir == null || !nativeDir.isDirectory()) return;

        String label = declaredDriver;
        if (label == null || label.trim().isEmpty()) {
            try {
                CharSequence appLabel = context.getPackageManager().getApplicationLabel(app);
                label = appLabel != null ? appLabel.toString() : app.packageName;
            } catch (Throwable ignored) {
                label = app.packageName;
            }
        }

        Driver.Type type = isTurnipLibrary(vulkan) ? Driver.Type.TURNIP : Driver.Type.CUSTOM_ICD;
        addDriver(new Driver(label, type, app.packageName, nativeDir, vulkan));
    }

    @Nullable
    private static File findTurnipLibrary(@NonNull File nativeDir) {
        for (String name : TURNIP_LIBRARY_NAMES) {
            File candidate = new File(nativeDir, name);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    @Nullable
    private static File findVulkanLibrary(@NonNull File nativeDir) {
        File turnip = findTurnipLibrary(nativeDir);
        if (turnip != null) return turnip;

        File panvk = new File(nativeDir, PANVK_LIBRARY_NAME);
        if (panvk.isFile()) return panvk;

        File[] files = nativeDir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && name.startsWith("libvulkan_") && name.endsWith(".so")) {
                return file;
            }
        }
        return null;
    }

    private static boolean isTurnipLibrary(@Nullable File file) {
        if (file == null) return false;
        String name = file.getName();
        for (String turnipName : TURNIP_LIBRARY_NAMES) {
            if (turnipName.equals(name)) return true;
        }
        return false;
    }

    /**
     * Imports a release ZIP using the simple metadata format used by the PanVK
     * packages: root-level meta.json plus the library named by libraryName.
     */
    @NonNull
    public static synchronized Driver importDriverPackage(@NonNull Context context, @NonNull Uri uri) throws Exception {
        PathManager.initContextConstants(context);
        if (Build.VERSION.SDK_INT < 28) {
            throw new IllegalStateException("Custom Vulkan ICD packages require Android 9 (API 28) or newer.");
        }
        boolean arm64 = false;
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        if (supportedAbis != null) {
            for (String abi : supportedAbis) {
                if ("arm64-v8a".equalsIgnoreCase(abi)) {
                    arm64 = true;
                    break;
                }
            }
        }
        if (!arm64) {
            throw new IllegalStateException("Imported Vulkan drivers are ARM64-only in DroidBridge.");
        }

        File root = PathManager.DIR_INSTALLED_VULKAN_DRIVER;
        if (root == null) throw new IllegalStateException("Vulkan driver storage is unavailable.");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("Unable to create Vulkan driver storage.");
        }

        String selectedFileName = getSelectedDocumentName(context, uri);
        File tempZip = File.createTempFile("vulkan-driver-", ".zip", context.getCacheDir());
        try {
            try (InputStream input = new BufferedInputStream(context.getContentResolver().openInputStream(uri));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(tempZip, false))) {
                if (input == null) throw new IllegalStateException("Unable to open the selected driver package.");
                copyBounded(input, output, MAX_DRIVER_PACKAGE_BYTES, "Vulkan driver package is too large.");
            }

            try (ZipFile zip = new ZipFile(tempZip)) {
                ZipEntry metaEntry = zip.getEntry("meta.json");
                JSONObject sourceMeta = null;
                if (metaEntry != null && !metaEntry.isDirectory()) {
                    sourceMeta = new JSONObject(readZipText(zip, metaEntry, 256 * 1024));
                    if (sourceMeta.has("schemaVersion")) {
                        int schemaVersion = sourceMeta.optInt("schemaVersion", -1);
                        if (schemaVersion != 1) {
                            throw new IllegalArgumentException("Unsupported Vulkan driver package schema: " + schemaVersion);
                        }
                    }
                }

                String declaredLibrary = sourceMeta != null
                        ? sourceMeta.optString("libraryName", "").trim() : "";
                ZipEntry libraryEntry = findDriverLibraryEntry(zip, declaredLibrary);
                if (libraryEntry == null) {
                    throw new IllegalArgumentException(
                            "No supported ARM64 Vulkan ICD was found. Expected a Turnip/PanVK libvulkan_*.so or emulator-style vulkan.adreno.so package.");
                }
                if (libraryEntry.getSize() > MAX_DRIVER_LIBRARY_BYTES) {
                    throw new IllegalArgumentException("Vulkan driver library is too large.");
                }

                String sourceBaseName = baseName(libraryEntry.getName());
                String installedLibraryName = normalizeImportedLibraryName(sourceBaseName);
                if (!isSafeSoName(installedLibraryName)) {
                    throw new IllegalArgumentException("Invalid Vulkan library name: " + sourceBaseName);
                }

                String fallbackName = stripZipExtension(selectedFileName);
                String name = sourceMeta != null
                        ? firstNonBlank(sourceMeta.optString("name", null), fallbackName)
                        : fallbackName;
                if (name == null || name.trim().isEmpty()) name = "Imported Vulkan driver";
                name = name.trim();

                String packageVersion = sourceMeta != null
                        ? firstNonBlank(
                                sourceMeta.optString("packageVersion", null),
                                sourceMeta.optString("driverVersion", null),
                                name)
                        : name;
                int minApi = Math.max(28, sourceMeta != null ? sourceMeta.optInt("minApi", 28) : 28);
                if (Build.VERSION.SDK_INT < minApi) {
                    throw new IllegalStateException(name + " requires Android API " + minApi
                            + "+; this device is API " + Build.VERSION.SDK_INT + ".");
                }

                String safeDirName = sanitizePackageName(name + "-" + packageVersion);
                File primaryTarget = new File(root, safeDirName);
                File target = chooseImportedTargetDirectory(root, safeDirName, selectedFileName);
                boolean alternateInstall = !target.getName().equals(primaryTarget.getName());
                String displayName = null;
                File stage = new File(root, ".importing-" + target.getName() + "-" + System.nanoTime());
                if (!stage.mkdirs()) throw new IllegalStateException("Unable to create driver staging directory.");

                String detectedVersion = null;
                try {
                    File libraryFile = new File(stage, installedLibraryName);
                    writeZipEntry(zip, libraryEntry, libraryFile, MAX_DRIVER_LIBRARY_BYTES);
                    validateArm64VulkanLibrary(libraryFile);
                    //noinspection ResultOfMethodCallIgnored
                    libraryFile.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    libraryFile.setExecutable(true, false);

                    copyImportedCompanionLibraries(zip, libraryEntry, stage, installedLibraryName);
                    detectedVersion = detectMesaVersionFromLibrary(libraryFile);
                    if ((packageVersion == null || packageVersion.equals(name)) && detectedVersion != null) {
                        packageVersion = detectedVersion;
                    }
                    displayName = buildImportedDisplayName(
                            name, packageVersion, selectedFileName, alternateInstall);

                    JSONObject normalizedMeta = new JSONObject();
                    normalizedMeta.put("schemaVersion", 1);
                    normalizedMeta.put("name", name);
                    normalizedMeta.put("packageVersion", packageVersion);
                    normalizedMeta.put("displayName", displayName);
                    normalizedMeta.put("installId", target.getName());
                    String declaredDriverVersion = sourceMeta != null
                            ? sourceMeta.optString("driverVersion", "").trim() : "";
                    normalizedMeta.put("driverVersion", !declaredDriverVersion.isEmpty()
                            ? declaredDriverVersion
                            : (detectedVersion != null ? detectedVersion : packageVersion));
                    normalizedMeta.put("libraryName", installedLibraryName);
                    normalizedMeta.put("minApi", minApi);
                    normalizedMeta.put("sourceArchive", selectedFileName);
                    normalizedMeta.put("sourceLibrary", libraryEntry.getName());
                    normalizedMeta.put("importedBy", "DroidBridge");
                    try (FileOutputStream metaOutput = new FileOutputStream(new File(stage, "meta.json"), false)) {
                        metaOutput.write(normalizedMeta.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String entryName = entry.getName();
                        String upper = entryName.toUpperCase(Locale.ROOT);
                        if (entryName.indexOf('/') < 0 && upper.startsWith("LICENSE")
                                && entry.getSize() <= 2L * 1024L * 1024L) {
                            writeZipEntry(zip, entry, new File(stage, entryName), 2L * 1024L * 1024L);
                        }
                    }

                    PathManager.deleteQuietly(target);
                    if (!stage.renameTo(target)) {
                        throw new IllegalStateException("Unable to activate imported Vulkan driver package.");
                    }
                } finally {
                    PathManager.deleteQuietly(stage);
                }

                File library = new File(target, installedLibraryName);
                Driver driver = new Driver(
                        displayName,
                        isTurnipLibraryName(sourceBaseName) || isTurnipLibrary(library)
                                ? Driver.Type.TURNIP : Driver.Type.CUSTOM_ICD,
                        "imported:" + target.getName(),
                        target,
                        library
                );
                reload(context);
                Logging.i(TAG, "Imported Vulkan driver package " + driver.getName()
                        + " source=" + libraryEntry.getName()
                        + " library=" + library.getAbsolutePath()
                        + " detectedVersion=" + detectedVersion
                        + " minApi=" + minApi);
                return driver;
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
        }
    }

    @NonNull
    private static String buildImportedDisplayName(
            @NonNull String name,
            @NonNull String packageVersion,
            @NonNull String sourceArchive,
            boolean alternateInstall
    ) {
        String displayName = name + (packageVersion.equals(name) ? "" : " (" + packageVersion + ")");
        if (!alternateInstall) return displayName;

        String archiveLabel = stripZipExtension(sourceArchive);
        if (!archiveLabel.equalsIgnoreCase(name)
                && !displayName.toLowerCase(Locale.ROOT).contains(archiveLabel.toLowerCase(Locale.ROOT))) {
            displayName += " — " + archiveLabel;
        }
        return displayName;
    }

    @NonNull
    private static File chooseImportedTargetDirectory(
            @NonNull File root,
            @NonNull String safeDirName,
            @NonNull String sourceArchive
    ) {
        File primary = new File(root, safeDirName);
        if (!primary.exists() || importedDirectoryMatchesSource(primary, sourceArchive)) {
            return primary;
        }

        String archiveSuffix = sanitizePackageName(stripZipExtension(sourceArchive));
        if (archiveSuffix.isEmpty() || "driver".equals(archiveSuffix)) archiveSuffix = "alternate";
        File candidate = new File(root, safeDirName + "-" + archiveSuffix);
        if (!candidate.exists() || importedDirectoryMatchesSource(candidate, sourceArchive)) {
            return candidate;
        }

        for (int i = 2; i < 1000; i++) {
            candidate = new File(root, safeDirName + "-" + archiveSuffix + "-" + i);
            if (!candidate.exists() || importedDirectoryMatchesSource(candidate, sourceArchive)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Too many installed Vulkan drivers use the same package name.");
    }

    private static boolean importedDirectoryMatchesSource(
            @NonNull File directory,
            @NonNull String sourceArchive
    ) {
        File metaFile = new File(directory, "meta.json");
        if (!metaFile.isFile()) return false;
        try {
            JSONObject meta = new JSONObject(readFileText(metaFile, 256 * 1024L));
            String existingSource = meta.optString("sourceArchive", "").trim();
            return !existingSource.isEmpty() && existingSource.equalsIgnoreCase(sourceArchive.trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isImportedDriver(@Nullable Driver driver) {
        if (driver == null) return false;
        String packageName = driver.getPackageName();
        return packageName != null && packageName.startsWith("imported:");
    }

    public static synchronized boolean deleteImportedDriver(
            @NonNull Context context,
            @NonNull Driver driver
    ) throws Exception {
        PathManager.initContextConstants(context);
        if (!isImportedDriver(driver)) {
            throw new IllegalArgumentException("Only imported Turnip/PanVK drivers can be deleted here.");
        }

        File root = PathManager.DIR_INSTALLED_VULKAN_DRIVER;
        File directory = driver.getNativeLibraryDir();
        if (root == null || directory == null) {
            throw new IllegalStateException("Imported Vulkan driver storage is unavailable.");
        }

        String rootPath = root.getCanonicalPath();
        String directoryPath = directory.getCanonicalPath();
        String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        if (!directoryPath.startsWith(prefix) || directoryPath.equals(rootPath)) {
            throw new SecurityException("Refusing to delete a Vulkan driver outside DroidBridge storage.");
        }

        String selectedName = LauncherPreferences.getSelectedVulkanDriverName(context);
        boolean deletingSelected = driver.getName().equals(selectedName);
        PathManager.deleteQuietly(directory);
        if (directory.exists()) {
            throw new IllegalStateException("Unable to delete imported Vulkan driver: " + driver.getName());
        }

        reload(context);
        if (deletingSelected && !DRIVERS.isEmpty()) {
            LauncherPreferences.setSelectedVulkanDriverName(context, DRIVERS.get(0).getName());
        }
        Logging.i(TAG, "Deleted imported Vulkan driver " + driver.getName()
                + " directory=" + directoryPath);
        return true;
    }

    @NonNull
    private static String getSelectedDocumentName(@NonNull Context context, @NonNull Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String fallback = uri.getLastPathSegment();
        return fallback != null && !fallback.trim().isEmpty() ? fallback.trim() : "Imported Vulkan driver.zip";
    }

    @NonNull
    private static String stripZipExtension(@NonNull String name) {
        String result = name.trim();
        if (result.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            result = result.substring(0, result.length() - 4);
        }
        return result.isEmpty() ? "Imported Vulkan driver" : result;
    }

    @Nullable
    private static ZipEntry findDriverLibraryEntry(@NonNull ZipFile zip, @Nullable String declaredLibrary) {
        if (declaredLibrary != null && !declaredLibrary.trim().isEmpty()) {
            ZipEntry exact = zip.getEntry(declaredLibrary.trim());
            if (exact != null && !exact.isDirectory()) return exact;
        }

        ZipEntry best = null;
        int bestScore = Integer.MIN_VALUE;
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String path = entry.getName();
            String lowerPath = path.toLowerCase(Locale.ROOT);
            String base = baseName(path).toLowerCase(Locale.ROOT);
            if (!looksLikeVulkanDriverLibrary(base)) continue;
            if (lowerPath.contains("x86") || lowerPath.contains("armeabi-v7a")
                    || lowerPath.contains("/armeabi/") || lowerPath.contains("/armv7")) {
                continue;
            }

            int score = 0;
            if (declaredLibrary != null && !declaredLibrary.trim().isEmpty()
                    && base.equals(baseName(declaredLibrary.trim()).toLowerCase(Locale.ROOT))) score += 500;
            if (base.equals("libvulkan_freedreno.so")) score += 300;
            else if (base.equals("libvulkan_panfrost.so")) score += 290;
            else if (base.equals("libvulkan_turnip.so") || base.equals("libvulkan_adreno.so")) score += 280;
            else if (Arrays.asList(EMULATOR_TURNIP_LIBRARY_NAMES).contains(base)) score += 270;
            else if (base.startsWith("libvulkan_") && base.endsWith(".so")) score += 200;
            if (lowerPath.contains("arm64-v8a") || lowerPath.contains("aarch64") || lowerPath.contains("/lib64/")) score += 80;
            if (path.indexOf('/') < 0) score += 40;
            if (entry.getSize() > 0 && entry.getSize() <= MAX_DRIVER_LIBRARY_BYTES) score += 10;

            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best;
    }

    private static boolean looksLikeVulkanDriverLibrary(@NonNull String baseName) {
        String lower = baseName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("libvulkan_") && lower.endsWith(".so")) return true;
        for (String name : EMULATOR_TURNIP_LIBRARY_NAMES) {
            if (name.equals(lower)) return true;
        }
        return lower.equals("vulkan.panfrost.so") || lower.equals("vulkan.panvk.so");
    }

    @NonNull
    private static String normalizeImportedLibraryName(@NonNull String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        if (lower.equals("vulkan.adreno.so") || lower.equals("vulkan.freedreno.so")
                || lower.equals("turnip.adreno.so")) {
            return "libvulkan_freedreno.so";
        }
        if (lower.equals("vulkan.panfrost.so") || lower.equals("vulkan.panvk.so")) {
            return PANVK_LIBRARY_NAME;
        }
        return sourceName;
    }

    private static boolean isTurnipLibraryName(@NonNull String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        for (String name : TURNIP_LIBRARY_NAMES) {
            if (name.equals(lower)) return true;
        }
        for (String name : EMULATOR_TURNIP_LIBRARY_NAMES) {
            if (name.equals(lower)) return true;
        }
        return lower.contains("turnip") || lower.contains("freedreno") || lower.contains("adreno");
    }

    @NonNull
    private static String baseName(@NonNull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void copyImportedCompanionLibraries(
            @NonNull ZipFile zip,
            @NonNull ZipEntry primary,
            @NonNull File stage,
            @NonNull String installedPrimaryName
    ) throws Exception {
        String primaryPath = primary.getName();
        int slash = primaryPath.lastIndexOf('/');
        String primaryParent = slash >= 0 ? primaryPath.substring(0, slash + 1) : "";
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        int copied = 0;
        while (entries.hasMoreElements() && copied < 32) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || entry.getSize() <= 0
                    || entry.getSize() > MAX_DRIVER_LIBRARY_BYTES) continue;
            String path = entry.getName();
            if (path.equals(primaryPath)) continue;
            String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".so")) continue;
            if (!primaryParent.isEmpty() && !path.startsWith(primaryParent)) continue;
            if (lower.contains("x86") || lower.contains("armeabi-v7a") || lower.contains("/armv7")) continue;
            String base = baseName(path);
            if (!isSafeSoName(base) || base.equals(installedPrimaryName)) continue;
            File destination = new File(stage, base);
            if (destination.exists()) continue;
            writeZipEntry(zip, entry, destination, MAX_DRIVER_LIBRARY_BYTES);
            if (!isArm64Elf(destination)) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            destination.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            destination.setExecutable(true, false);
            copied++;
        }
    }

    public static synchronized void clearImportedDriverPackages(@NonNull Context context) {
        PathManager.initContextConstants(context);
        File root = PathManager.DIR_INSTALLED_VULKAN_DRIVER;
        if (root != null) PathManager.deleteQuietly(root);
        if (root != null && !root.exists()) root.mkdirs();
        reload(context);
    }

    private static void discoverImportedDriverPackages(@NonNull Context context) {
        File root = PathManager.DIR_INSTALLED_VULKAN_DRIVER;
        File[] dirs = root != null && root.isDirectory() ? root.listFiles() : null;
        if (dirs == null) return;

        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".importing-")) continue;
            File metaFile = new File(dir, "meta.json");
            if (!metaFile.isFile()) continue;
            try {
                String json = readFileText(metaFile, 256 * 1024L);
                JSONObject meta = new JSONObject(json);
                if (meta.optInt("schemaVersion", -1) != 1) continue;
                int minApi = Math.max(28, meta.optInt("minApi", 28));
                if (Build.VERSION.SDK_INT < minApi) {
                    Logging.i(TAG, "Skipping imported Vulkan driver " + dir.getName()
                            + ": requires API " + minApi);
                    continue;
                }
                String name = requireMetadataString(meta, "name");
                String packageVersion = firstNonBlank(
                        meta.optString("packageVersion", null),
                        meta.optString("driverVersion", null),
                        name
                );
                String libraryName = requireMetadataString(meta, "libraryName");
                if (!isSafeLibraryName(libraryName)) continue;
                File vulkan = new File(dir, libraryName);
                if (!vulkan.isFile()) continue;
                String displayName = firstNonBlank(
                        meta.optString("displayName", null),
                        name + (packageVersion.equals(name) ? "" : " (" + packageVersion + ")")
                );
                Driver.Type type = isTurnipLibrary(vulkan) ? Driver.Type.TURNIP : Driver.Type.CUSTOM_ICD;
                addDriver(new Driver(
                        displayName,
                        type,
                        "imported:" + dir.getName(),
                        dir,
                        vulkan
                ));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to load imported Vulkan driver from " + dir.getAbsolutePath(), throwable);
            }
        }
    }

    @NonNull
    private static String requireMetadataString(@NonNull JSONObject meta, @NonNull String key) {
        String value = meta.optString(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Driver metadata is missing " + key + ".");
        return value;
    }

    @NonNull
    private static String firstNonBlank(@Nullable String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
        }
        return "driver";
    }

    private static boolean isSafeLibraryName(@Nullable String name) {
        if (!isSafeSoName(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("libvulkan_") && lower.endsWith(".so");
    }

    private static boolean isSafeSoName(@Nullable String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".so") && name.matches("[A-Za-z0-9_.+-]+\\.so");
    }

    @NonNull
    private static String sanitizePackageName(@NonNull String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "driver" : safe;
    }

    private static void validateArm64VulkanLibrary(@NonNull File file) throws Exception {
        if (!isArm64Elf(file)) {
            throw new IllegalArgumentException("Vulkan driver library must be a little-endian ARM64 (AArch64) ELF shared library.");
        }
    }

    private static boolean isArm64Elf(@NonNull File file) throws Exception {
        byte[] header = new byte[20];
        int offset = 0;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        if (offset < header.length
                || header[0] != 0x7f
                || header[1] != 'E'
                || header[2] != 'L'
                || header[3] != 'F') {
            return false;
        }
        // EI_CLASS=2 is ELF64, EI_DATA=1 is little endian. e_machine=183 is AArch64.
        int elfClass = header[4] & 0xff;
        int dataEncoding = header[5] & 0xff;
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        return elfClass == 2 && dataEncoding == 1 && machine == 183;
    }

    @NonNull
    private static String readFileText(@NonNull File file, long maxBytes) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            copyBounded(input, output, maxBytes, "Driver metadata is too large.");
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    @NonNull
    private static String readZipText(@NonNull ZipFile zip, @NonNull ZipEntry entry, long maxBytes) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
            copyBounded(input, output, maxBytes, "Driver metadata is too large.");
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeZipEntry(
            @NonNull ZipFile zip,
            @NonNull ZipEntry entry,
            @NonNull File destination,
            long maxBytes
    ) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create " + parent.getAbsolutePath());
        }
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination, false))) {
            copyBounded(input, output, maxBytes, "Vulkan driver file is too large.");
        }
    }

    private static void copyBounded(
            @NonNull InputStream input,
            @NonNull OutputStream output,
            long maxBytes,
            @NonNull String tooLargeMessage
    ) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IllegalArgumentException(tooLargeMessage);
            output.write(buffer, 0, read);
        }
    }

    private static void addDriver(@NonNull Driver driver) {
        for (int i = 0; i < DRIVERS.size(); i++) {
            Driver existing = DRIVERS.get(i);
            if (sameDriver(existing, driver)) return;
        }
        DRIVERS.add(driver);
    }

    private static boolean sameDriver(@NonNull Driver a, @NonNull Driver b) {
        if (a.getPackageName() != null && a.getPackageName().equals(b.getPackageName())) return true;
        File av = a.getVulkanLibrary();
        File bv = b.getVulkanLibrary();
        return av != null && bv != null && av.getAbsolutePath().equals(bv.getAbsolutePath());
    }
}
