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

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.Window;
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
    /** Private app-owned directory used when shared/emulated storage cannot provide flock(). */
    private static final String ENV_LOCK_DIR = "IRONBRIDGE_HORIZON_LOCK_DIR";
    /**
     * Horizon's data collection (crates/horizon-telemetry): "off" sends nothing, not even the
     * anonymous "I'm playing" ping. DroidBridge never shows Horizon's consent screen, and its
     * Google Play data-safety answers do not cover Horizon's servers, so the engine stays quiet.
     */
    private static final String ENV_TELEMETRY_URL = "HORIZON_TELEMETRY_URL";

    private static volatile boolean nativeLibraryLoaded;

    private static synchronized void ensureNativeLibraryLoaded() {
        if (nativeLibraryLoaded) return;
        System.loadLibrary("horizon_android");
        nativeLibraryLoaded = true;
    }

    private native void nativeDocumentResult(int request, int result, String uri);

    private HorizonControlsHost controls;
    private volatile boolean quitting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // NativeActivity can briefly expose its own title/window before the first native frame.
        // DroidBridge owns the whole launch UI, so keep that transition black and titleless.
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setTitle("");
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        } catch (Throwable ignored) {
        }
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
        File lockDir = new File(getCacheDir(), "horizon-world-locks");
        //noinspection ResultOfMethodCallIgnored
        lockDir.mkdirs();
        setEnv(ENV_LOCK_DIR, lockDir.getAbsolutePath());
        setEnv(ENV_RENDERER, extra(intent, EXTRA_RENDERER));
        String vulkanDriver = prepareVulkanDriver(intent);

        // Load Horizon only after every host handoff variable is in place. Some native/global
        // initialization is process-wide; loading the .so from the class initializer can expose
        // one frame of Horizon's standalone state before DroidBridge's autoplay flags exist.
        ensureNativeLibraryLoaded();
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(splash -> splash.remove());
            } catch (Throwable ignored) {
            }
        }

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


    /**
     * Called by Horizon's Rust game thread only after game::run() has returned and the
     * current world/session lock has been dropped. NativeActivity cannot host another
     * Horizon run in this process, so remove the task and end the dedicated :horizon process.
     */
    @Keep
    public void quit() {
        if (quitting) return;
        quitting = true;
        Log.i(TAG, "Horizon native loop finished; removing task and closing dedicated :horizon process");

        // Horizon is launched with FLAG_ACTIVITY_NEW_TASK, so it owns a separate Android task.
        // finishAndRemoveTask() normally removes its Recents card, but some Android builds commit
        // that removal asynchronously. Remove the exact AppTask by ID first, then give
        // ActivityTaskManager enough time to persist the removal before this process exits.
        runOnUiThread(this::removeOwnTaskFromRecents);

        Thread exitThread = new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "droidbridge-horizon-quit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

    private void removeOwnTaskFromRecents() {
        final int ownTaskId = getTaskId();
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager != null) {
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info != null && info.taskId == ownTaskId) {
                        Log.i(TAG, "Removing Horizon AppTask from Recents taskId=" + ownTaskId);
                        task.finishAndRemoveTask();
                        return;
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not remove Horizon AppTask taskId=" + ownTaskId, throwable);
        }

        try {
            finishAndRemoveTask();
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not remove finished Horizon task", throwable);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (controls != null) {
            controls.destroy();
            controls = null;
        }
        super.onDestroy();
        // Match Horizon's own Android host. If Android permanently finishes the native
        // Activity outside the explicit Rust quit callback, this process cannot safely be
        // reused for another native event loop. Home/Recents does not finish the Activity.
        if (isFinishing() && !isChangingConfigurations() && !quitting) {
            Log.i(TAG, "Horizon Activity finished; closing dedicated :horizon process");
            System.exit(0);
        }
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
