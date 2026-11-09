/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package dev.rustcraft.android;

import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Keep;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonControlsHost;
import ca.dnamobile.droidbridgelauncher.engine.rustcraft.RustcraftEngineInput;

/**
 * Hosts the rustcraft engine (Minecraft 1.0 rewritten in Rust) in its own :rustcraft
 * process, the way HorizonActivity hosts Horizon: the launch handoff goes into the
 * environment, then NativeActivity starts the engine (android_main in
 * librustcraft_android.so), with DroidBridge's controls over it.
 *
 * The engine shows its own title screen and world list (or opens the quick play world)
 * and keeps worlds in the instance's saves folder in 1.0's format. When the player quits,
 * the engine's loop ends and calls quit() (as Horizon's does): the game's task leaves
 * Recents and this process exits, which returns the player to DroidBridge.
 *
 * With DroidBridge's controls on, every key, controller and mouse event goes through
 * DroidBridge (the controller mapper, the physical keyboard and mouse handling, the
 * "show controller input" feedback) and reaches the engine through RustcraftInput, the
 * way the Java engine gets it through CallbackBridge. NativeActivity would otherwise
 * hand them all to the engine's own window code first, which kept every controller
 * button, stick and keyboard key for itself, so only the touch controls worked.
 */
public final class RustcraftActivity extends android.app.NativeActivity {
    private static final String TAG = "DroidBridgeRustcraft";

    /** The 1.0 client jar the textures come from. Required. */
    public static final String EXTRA_JAR = "dev.rustcraft.android.extra.JAR";
    /** The instance's game folder (saves, options.txt). Required. */
    public static final String EXTRA_GAME_DIR = "dev.rustcraft.android.extra.GAME_DIR";
    /** The engine's own folder (log, error file). */
    public static final String EXTRA_ENGINE_HOME = "dev.rustcraft.android.extra.ENGINE_HOME";
    /** A world folder to open straight away (quick play). */
    public static final String EXTRA_QUICK_PLAY_WORLD = "dev.rustcraft.android.extra.QUICK_PLAY_WORLD";
    /** True: DroidBridge's touch controls, virtual mouse and controller mapper. */
    public static final String EXTRA_HOST_CONTROLS = "dev.rustcraft.android.extra.HOST_CONTROLS";
    /** "vulkan" or "opengl"; absent lets the engine choose. */
    public static final String EXTRA_RENDERER = "dev.rustcraft.android.extra.RENDERER";

    // Read by crates/rc-android/src/lib.rs.
    private static final String ENV_JAR = "RUSTCRAFT_JAR";
    private static final String ENV_GAME_DIR = "RUSTCRAFT_GAME_DIR";
    private static final String ENV_WORLD = "RUSTCRAFT_WORLD";
    private static final String ENV_RENDERER = "RUSTCRAFT_RENDERER";
    private static final String ENV_LOG = "RUSTCRAFT_LOG_FILE";
    private static final String ENV_ERROR = "RUSTCRAFT_ERROR_FILE";

    private static volatile boolean nativeLibraryLoaded;

    private static synchronized void ensureNativeLibraryLoaded() {
        if (nativeLibraryLoaded) return;
        System.loadLibrary("rustcraft_android");
        nativeLibraryLoaded = true;
    }

    /** GLFW_KEY_ESCAPE: Android's Back works as Esc (the pause menu, closing a screen). */
    private static final int GLFW_KEY_ESCAPE = 256;

    private HorizonControlsHost controls;
    /** Android button state last sent for the captured mouse. */
    private int capturedButtons;
    private boolean backDown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setTitle("");
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        } catch (Throwable ignored) {
        }
        Intent intent = getIntent();
        String home = extra(intent, EXTRA_ENGINE_HOME);
        if (home == null) home = new File(getFilesDir(), "rustcraft").getAbsolutePath();
        //noinspection ResultOfMethodCallIgnored
        new File(home).mkdirs();
        String gameDir = extra(intent, EXTRA_GAME_DIR);
        boolean hostControls = intent != null && intent.getBooleanExtra(EXTRA_HOST_CONTROLS, true);
        File error = new File(home, "android-error.txt");
        //noinspection ResultOfMethodCallIgnored
        error.delete();
        // NativeActivity starts the engine thread inside super.onCreate, so the handoff
        // has to be in the environment first.
        setEnv(ENV_JAR, extra(intent, EXTRA_JAR));
        setEnv(ENV_GAME_DIR, gameDir);
        setEnv(ENV_WORLD, extra(intent, EXTRA_QUICK_PLAY_WORLD));
        setEnv(ENV_RENDERER, extra(intent, EXTRA_RENDERER));
        setEnv(ENV_LOG, new File(home, "latest.log").getAbsolutePath());
        setEnv(ENV_ERROR, error.getAbsolutePath());

        ensureNativeLibraryLoaded();
        super.onCreate(savedInstanceState);
        if (hostControls) {
            // NativeActivity asked for the window's input queue in super.onCreate; the
            // window only creates it when it is first shown (after onCreate), so taking the
            // request back here leaves every key and motion event on Android's normal path:
            // dispatchKeyEvent, dispatchGenericMotionEvent and the captured-pointer listener
            // below, all of which go to DroidBridge's controls.
            try {
                getWindow().takeInputQueue(null);
            } catch (Throwable problem) {
                Log.w(TAG, "Could not keep input on the Java side", problem);
            }
            installCapturedMouse();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(splash -> splash.remove());
            } catch (Throwable ignored) {
            }
        }
        if (hostControls) {
            controls = new HorizonControlsHost(this, gameDir == null ? null : new File(gameDir),
                    null, RustcraftEngineInput.INSTANCE);
            controls.attach();
        }
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

    /** Set once quit() has started closing the game. */
    private volatile boolean quitting;

    /**
     * Called by the engine (librustcraft_android.so, from its own thread) once its game
     * loop has ended and the world is saved. NativeActivity can't host a second game loop
     * in this process, so the game's task is taken out of Recents and the :rustcraft
     * process ends, as HorizonActivity does for Horizon.
     */
    @Keep
    public void quit() {
        if (quitting) return;
        quitting = true;
        Log.i(TAG, "rustcraft finished; removing its task and closing the :rustcraft process");

        // The game runs in its own task (FLAG_ACTIVITY_NEW_TASK). finishAndRemoveTask()
        // normally removes its Recents card, but some Android builds commit that removal
        // later: remove the exact AppTask by id first, then give ActivityTaskManager time
        // to keep the removal before the process exits.
        runOnUiThread(this::removeOwnTaskFromRecents);

        Thread exitThread = new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "droidbridge-rustcraft-quit");
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
                        Log.i(TAG, "Removing the rustcraft task from Recents taskId=" + ownTaskId);
                        task.finishAndRemoveTask();
                        return;
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not remove the rustcraft task taskId=" + ownTaskId, throwable);
        }

        try {
            finishAndRemoveTask();
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not remove the finished rustcraft task", throwable);
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
        // The engine runs one game loop per process: if Android finishes the activity
        // some other way than quit(), still end the process so the next launch starts
        // clean. Home and Recents don't finish it.
        if (isFinishing() && !isChangingConfigurations() && !quitting) {
            Log.i(TAG, "rustcraft activity finished; closing the :rustcraft process");
            System.exit(0);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (controls != null) {
            if (controls.handleKeyEvent(event)) return true;
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                sendBack(event.getAction() == KeyEvent.ACTION_DOWN, event.getRepeatCount());
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** A Back gesture that arrives without a key event (some Android versions). */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (controls == null) {
            super.onBackPressed();
            return;
        }
        RustcraftInput.key(GLFW_KEY_ESCAPE, true);
        RustcraftInput.key(GLFW_KEY_ESCAPE, false);
    }

    private void sendBack(boolean down, int repeat) {
        if (down) {
            if (repeat > 0 || backDown) return;
            backDown = true;
            RustcraftInput.key(GLFW_KEY_ESCAPE, true);
        } else if (backDown) {
            backDown = false;
            RustcraftInput.key(GLFW_KEY_ESCAPE, false);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (controls != null) {
            if (controls.handleMotionEvent(event)) return true;
            if (isCapturedMouse(event)) return onCapturedMouse(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    // --- The mouse in gameplay --------------------------------------------------------

    /**
     * In gameplay DroidBridge captures a real mouse on this window (HorizonControlsHost):
     * Android then sends its motion as relative steps to the focused view. Each step moves
     * DroidBridge's cursor by that much (times the hardware mouse DPI scale), which the
     * engine turns into camera movement, as the Java engine does with a grabbed cursor.
     */
    private void installCapturedMouse() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            View.OnCapturedPointerListener listener = (view, event) -> onCapturedMouse(event);
            View decor = getWindow().getDecorView();
            decor.setOnCapturedPointerListener(listener);
            View content = findViewById(android.R.id.content);
            if (content != null) {
                content.setOnCapturedPointerListener(listener);
                if (content instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) content;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        group.getChildAt(i).setOnCapturedPointerListener(listener);
                    }
                }
            }
        } catch (Throwable problem) {
            Log.w(TAG, "Could not listen for the captured mouse", problem);
        }
    }

    private static boolean isCapturedMouse(MotionEvent event) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE)
                == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    private boolean onCapturedMouse(MotionEvent event) {
        if (controls == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL) {
            float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (vertical != 0f || horizontal != 0f) CallbackBridge.sendScroll(horizontal, vertical);
        } else if (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_HOVER_MOVE) {
            float dx = 0f;
            float dy = 0f;
            for (int h = 0; h < event.getHistorySize(); h++) {
                dx += event.getHistoricalX(h);
                dy += event.getHistoricalY(h);
            }
            dx += event.getX();
            dy += event.getY();
            if (dx != 0f || dy != 0f) {
                float scale = RustcraftEngineInput.mouseScale();
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX + dx * scale,
                        CallbackBridge.mouseY + dy * scale);
                keepCursorNearTheWindow();
            }
        }
        int buttons = action == MotionEvent.ACTION_CANCEL ? 0 : event.getButtonState();
        sendCapturedButtons(buttons);
        return true;
    }

    /** Presses and releases for the buttons whose state changed (GLFW 0 left, 1 right, ...). */
    private void sendCapturedButtons(int buttons) {
        int[][] map = {
                {MotionEvent.BUTTON_PRIMARY, 0},
                {MotionEvent.BUTTON_SECONDARY, 1},
                {MotionEvent.BUTTON_TERTIARY, 2},
                {MotionEvent.BUTTON_BACK, 3},
                {MotionEvent.BUTTON_FORWARD, 4},
        };
        for (int[] entry : map) {
            boolean was = (capturedButtons & entry[0]) != 0;
            boolean now = (buttons & entry[0]) != 0;
            if (was != now) CallbackBridge.sendMouseButton(entry[1], now);
        }
        capturedButtons = buttons;
    }

    /**
     * The relative steps carry DroidBridge's cursor anywhere; far outside the window it is
     * put back in the middle without counting as movement, so a menu opened next starts
     * from a sane place.
     */
    private void keepCursorNearTheWindow() {
        int width = Math.max(1, CallbackBridge.windowWidth);
        int height = Math.max(1, CallbackBridge.windowHeight);
        float x = CallbackBridge.mouseX;
        float y = CallbackBridge.mouseY;
        if (x < -width || x > 2f * width || y < -height || y > 2f * height) {
            CallbackBridge.setCursorPosSilently(width / 2f, height / 2f);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        HorizonControlsHost host = controls;
        if (host != null) host.onActivityResult(request, result, data);
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
