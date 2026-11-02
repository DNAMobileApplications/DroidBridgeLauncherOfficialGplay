/*
 * Horizon engine host activity for DroidBridge Launcher.
 *
 * Based on packaging/android/java/dev/horizon/android/HorizonActivity.java from
 * Horizon Android (MIT). The class name and package must stay dev.horizon.android.HorizonActivity:
 * the engine's JNI exports are named after it
 * (Java_dev_horizon_android_HorizonActivity_nativeDocumentResult).
 *
 * It runs in its own :horizon process. The engine owns one native window per process and
 * ends its process when the player quits, which returns the player to DroidBridge.
 */

package dev.horizon.android;

import android.content.Intent;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;

import androidx.annotation.Keep;

import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonControlsHost;

/** Passes the launch handoff to the engine, then lets NativeActivity start it. */
public final class HorizonActivity extends android.app.NativeActivity {
    private static final String TAG = "DroidBridgeHorizon";

    /** Absolute engine home (resources, engine settings). Required. */
    public static final String EXTRA_ENGINE_HOME = "dev.horizon.android.extra.ENGINE_HOME";
    /** Absolute game folder of the instance (saves, options.txt, packs). */
    public static final String EXTRA_GAME_DIR = "dev.horizon.android.extra.GAME_DIR";
    /** Absolute path of a 0600 session file written just before the launch. */
    public static final String EXTRA_SESSION_FILE = "dev.horizon.android.extra.SESSION_FILE";
    public static final String EXTRA_QUICK_PLAY_WORLD = "dev.horizon.android.extra.QUICK_PLAY_WORLD";
    public static final String EXTRA_QUICK_PLAY_SERVER = "dev.horizon.android.extra.QUICK_PLAY_SERVER";
    /**
     * True: DroidBridge's touch controls, virtual mouse and controller mapper drive the game,
     * and the engine's own touch controls stay hidden.
     */
    public static final String EXTRA_HOST_CONTROLS = "dev.horizon.android.extra.HOST_CONTROLS";
    /** "vulkan", "vulkan-batching" or "opengl"; absent: Horizon's own Rendering mode setting. */
    public static final String EXTRA_RENDERER = "dev.horizon.android.extra.RENDERER";
    /**
     * A Vulkan driver to load instead of the system one (Turnip, PanVK, ...): its display
     * name, the folder holding it, the driver library itself, and whether it is Turnip.
     */
    public static final String EXTRA_VULKAN_DRIVER_NAME = "dev.horizon.android.extra.VULKAN_DRIVER_NAME";
    public static final String EXTRA_VULKAN_DRIVER_DIR = "dev.horizon.android.extra.VULKAN_DRIVER_DIR";
    public static final String EXTRA_VULKAN_DRIVER_LIBRARY = "dev.horizon.android.extra.VULKAN_DRIVER_LIBRARY";
    public static final String EXTRA_VULKAN_DRIVER_TURNIP = "dev.horizon.android.extra.VULKAN_DRIVER_TURNIP";

    // Read once by crates/horizon-client/src/android/host_handoff.rs. The names are shared with
    // the engine build (the same libhorizon_android.so serves IronBridge), so they stay as is.
    private static final String ENV_HOME = "IRONBRIDGE_HORIZON_HOME";
    private static final String ENV_GAME_DIR = "IRONBRIDGE_HORIZON_GAME_DIR";
    private static final String ENV_SESSION = "IRONBRIDGE_HORIZON_SESSION";
    private static final String ENV_QUICK_PLAY_WORLD = "IRONBRIDGE_HORIZON_QUICK_PLAY_WORLD";
    private static final String ENV_QUICK_PLAY_SERVER = "IRONBRIDGE_HORIZON_QUICK_PLAY_SERVER";
    private static final String ENV_AUTOPLAY = "IRONBRIDGE_HORIZON_AUTOPLAY";
    private static final String ENV_HOST_CONTROLS = "IRONBRIDGE_HORIZON_HOST_CONTROLS";
    private static final String ENV_RENDERER = "IRONBRIDGE_HORIZON_RENDERER";
    private static final String ENV_VULKAN_DRIVER = "IRONBRIDGE_HORIZON_VULKAN_DRIVER";
    /**
     * Horizon's data collection (crates/horizon-telemetry): "off" sends nothing, not even the
     * anonymous "I'm playing" ping. DroidBridge never shows Horizon's consent screen, and its
     * Google Play data-safety answers do not cover Horizon's servers, so the engine stays quiet.
     */
    private static final String ENV_TELEMETRY_URL = "HORIZON_TELEMETRY_URL";

    static { System.loadLibrary("horizon_android"); }

    private native void nativeDocumentResult(int request, int result, String uri);

    private HorizonControlsHost controls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // NativeActivity starts the engine thread inside super.onCreate, so the handoff
        // has to be in the environment first.
        Intent intent = getIntent();
        String home = intent == null ? null : intent.getStringExtra(EXTRA_ENGINE_HOME);
        if (home == null || home.trim().isEmpty()) {
            // Never let the engine fall back to the launcher's own files directory.
            home = new File(getFilesDir(), "horizon").getAbsolutePath();
        }
        new File(home).mkdirs();
        String gameDir = extra(intent, EXTRA_GAME_DIR);
        boolean hostControls = intent != null && intent.getBooleanExtra(EXTRA_HOST_CONTROLS, false);
        setEnv(ENV_HOME, home);
        setEnv(ENV_GAME_DIR, gameDir);
        setEnv(ENV_SESSION, extra(intent, EXTRA_SESSION_FILE));
        setEnv(ENV_QUICK_PLAY_WORLD, extra(intent, EXTRA_QUICK_PLAY_WORLD));
        setEnv(ENV_QUICK_PLAY_SERVER, extra(intent, EXTRA_QUICK_PLAY_SERVER));
        // DroidBridge never exposes Horizon's standalone launcher UI. This Activity is the
        // embedded engine host, so always enter Horizon's autoplay/game path.
        setEnv(ENV_AUTOPLAY, "1");
        setEnv(ENV_HOST_CONTROLS, hostControls ? "1" : null);
        setEnv(ENV_TELEMETRY_URL, "off");
        setEnv("HORIZON_NO_HEARTBEAT", "1");
        setEnv(ENV_RENDERER, extra(intent, EXTRA_RENDERER));
        String vulkanDriver = prepareVulkanDriver(intent);
        super.onCreate(savedInstanceState);

        if (hostControls) {
            controls = new HorizonControlsHost(this, gameDir == null ? null : new File(gameDir),
                    vulkanDriver);
            controls.attach();
        }
    }

    /**
     * The environment DroidBridge's driver helper library reads to build a Vulkan loader
     * bound to the chosen driver: the same variables DroidBridge's Java engine sets for Turnip and PanVK
     * (DriverPluginManager.applyCustomVulkanDriverEnvironment). The engine calls the helper
     * before it creates its first Vulkan instance.
     *
     * @return the driver's display name, or null for the system Vulkan driver
     */
    private String prepareVulkanDriver(Intent intent) {
        String name = extra(intent, EXTRA_VULKAN_DRIVER_NAME);
        String dir = extra(intent, EXTRA_VULKAN_DRIVER_DIR);
        String library = extra(intent, EXTRA_VULKAN_DRIVER_LIBRARY);
        boolean turnip = intent != null && intent.getBooleanExtra(EXTRA_VULKAN_DRIVER_TURNIP, false);
        boolean custom = name != null && dir != null && library != null
                && new File(library).isFile() && new File(dir).isDirectory();
        String libraryName = custom ? new File(library).getName() : null;
        String supportDir = getApplicationInfo() == null ? null : getApplicationInfo().nativeLibraryDir;
        setEnv(ENV_VULKAN_DRIVER, custom ? "custom" : null);
        setEnv("DROIDBRIDGE_USE_SYSTEM_VULKAN", custom ? "0" : "1");
        setEnv("DROIDBRIDGE_LOAD_CUSTOM_VULKAN", custom ? "1" : null);
        setEnv("DRIVER_PATH", custom ? dir : null);
        setEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_DIR", custom ? dir : null);
        setEnv("DROIDBRIDGE_CUSTOM_VULKAN_SUPPORT_DIR", custom ? supportDir : null);
        setEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", custom ? library : null);
        setEnv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME", libraryName);
        setEnv("DROIDBRIDGE_TURNIP_DRIVER_DIR", custom && turnip ? dir : null);
        setEnv("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY", custom && turnip ? library : null);
        setEnv("DROIDBRIDGE_LOAD_TURNIP", custom && turnip ? "1" : null);
        setEnv("DROIDBRIDGE_USE_CUSTOM_TURNIP", custom && turnip ? "1" : null);
        setEnv("DROIDBRIDGE_VULKAN_DRIVER_REPORT", custom ? name : null);
        // The helper copies the loader into a private namespace under TMPDIR.
        File cache = getCacheDir();
        setEnv("TMPDIR", cache == null ? null : cache.getAbsolutePath());
        // The helper's private-loader alias path, as DroidBridge's Vulkan Zink and Kopper use:
        // its namespace resolves Android platform libraries from /system first. The plain
        // path fails on this kind of device with "cannot locate symbol native_handle_clone"
        // while loading the private libvulkan.so copy.
        File aliasDir = custom && cache != null ? new File(cache, "horizon-vulkan-loader") : null;
        if (aliasDir != null) {
            //noinspection ResultOfMethodCallIgnored
            aliasDir.mkdirs();
            File stale = new File(aliasDir, "libvulkan.so");
            if (stale.isFile() && !stale.delete()) {
                Log.w(TAG, "Could not delete the previous Vulkan loader alias " + stale);
            }
        }
        setEnv("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", aliasDir != null ? "1" : null);
        setEnv("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR",
                aliasDir != null ? aliasDir.getAbsolutePath() : null);
        if (custom) Log.i(TAG, "Vulkan driver for Horizon: " + name + " (" + library + ")");
        return custom ? name : null;
    }

    /**
     * Called from the hosted Horizon engine when Minecraft chat requests Android text input.
     * The engine thread may call this through JNI, so always hop to Android's UI thread.
     */
    @Keep
    public void showDroidBridgeChatKeyboard() {
        runOnUiThread(() -> {
            HorizonControlsHost host = controls;
            if (host != null) host.showChatKeyboard();
        });
    }

    /** Hides DroidBridge's hosted chat IME when Horizon closes/submits the chat field. */
    @Keep
    public void hideDroidBridgeKeyboard() {
        runOnUiThread(() -> {
            HorizonControlsHost host = controls;
            if (host != null) host.hideKeyboard();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controls != null) controls.onResume();
    }

    @Override
    protected void onPause() {
        if (controls != null) controls.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controls != null) {
            controls.destroy();
            controls = null;
        }
        super.onDestroy();
    }

    /** Controller buttons the engine left to DroidBridge's mapper (host controls only). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (controls != null && controls.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Controller sticks and triggers the engine left to DroidBridge's mapper. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (controls != null && controls.handleMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        HorizonControlsHost host = controls;
        if (host != null && host.onActivityResult(request, result, data)) return;
        if (request != 4101) return;
        nativeDocumentResult(request, result,
                data == null || data.getData() == null ? null : data.getData().toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        HorizonControlsHost host = controls;
        if (host != null) host.onRequestPermissionsResult(requestCode, grantResults);
    }

    private static String extra(Intent intent, String key) {
        if (intent == null) return null;
        String value = intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void setEnv(String key, String value) {
        try {
            if (value == null) {
                Os.unsetenv(key);
            } else {
                Os.setenv(key, value, true);
            }
        } catch (ErrnoException error) {
            Log.w(TAG, "Could not set " + key, error);
        }
    }
}
