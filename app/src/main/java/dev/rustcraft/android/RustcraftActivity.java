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
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.Rational;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.Keep;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.engine.EngineWindowLayout;
import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonControlsHost;
import ca.dnamobile.droidbridgelauncher.engine.rustcraft.RustcraftEngineInput;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;

/**
 * Hosts a Rust Craft engine (Minecraft rewritten in Rust) in its own :rustcraft
 * process, the way HorizonActivity hosts Horizon: the launch handoff goes into the
 * environment, then NativeActivity starts the engine (android_main in
 * librustcraft_android.so), with DroidBridge's controls over it.
 *
 * The engine shows its own title screen and world list (or opens the quick play world)
 * and keeps worlds in the instance's saves folder in its Minecraft version's format. This class
 * starts TU1 (Minecraft 1.0, librustcraft_android.so); each later title update is a subclass
 * with its own library (RustcraftTu2Activity: 1.1, librustcraft_tu2.so), since a NativeActivity
 * takes its library from its own manifest entry. When the player quits,
 * the engine saves, its loop ends and it calls {@link #quit()}, which takes the game's task
 * out of Recents and ends this process (as HorizonActivity does), returning the player to
 * DroidBridge.
 *
 * With DroidBridge's "picture-in-picture" setting on, leaving the game (Home, another app)
 * shrinks it into a floating window, as the Java engine's GameActivity does; closing that
 * window saves the world and ends the game.
 *
 * With DroidBridge's controls on, every key, controller and mouse event goes through
 * DroidBridge (the controller mapper, the physical keyboard and mouse handling, the
 * "show controller input" feedback) and reaches the engine through RustcraftInput, the
 * way the Java engine gets it through CallbackBridge. NativeActivity would otherwise
 * hand them all to the engine's own window code first, which kept every controller
 * button, stick and keyboard key for itself, so only the touch controls worked.
 */
public class RustcraftActivity extends android.app.NativeActivity {
    private static final String TAG = "DroidBridgeRustcraft";

    /** The client jar of the Minecraft version this title update plays as: its textures. Required. */
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
    /** The player's name (the Microsoft account's Minecraft name); absent: "Player". */
    public static final String EXTRA_USERNAME = "dev.rustcraft.android.extra.USERNAME";

    // Read by crates/rc-android/src/lib.rs.
    private static final String ENV_JAR = "RUSTCRAFT_JAR";
    private static final String ENV_GAME_DIR = "RUSTCRAFT_GAME_DIR";
    private static final String ENV_WORLD = "RUSTCRAFT_WORLD";
    private static final String ENV_RENDERER = "RUSTCRAFT_RENDERER";
    private static final String ENV_USERNAME = "RUSTCRAFT_USERNAME";
    private static final String ENV_LOG = "RUSTCRAFT_LOG_FILE";
    private static final String ENV_ERROR = "RUSTCRAFT_ERROR_FILE";

    /** The engine library this process loaded (one title update per :rustcraft process). */
    private static volatile String loadedLibrary;

    /**
     * This title update's native library: TU1's here; each later title update's subclass
     * names its own (the same name as its manifest entry's android.app.lib_name).
     */
    protected String engineLibrary() {
        return "rustcraft_android";
    }

    private void ensureNativeLibraryLoaded() {
        String library = engineLibrary();
        synchronized (RustcraftActivity.class) {
            String loaded = loadedLibrary;
            if (library.equals(loaded)) return;
            if (loaded != null) {
                // Each game ends its process when the player quits, so this shouldn't happen.
                Log.w(TAG, "Engine " + loaded + " is already loaded in this process; loading " + library);
            }
            System.loadLibrary(library);
            loadedLibrary = library;
        }
    }

    /** GLFW_KEY_ESCAPE: Android's Back works as Esc (the pause menu, closing a screen). */
    private static final int GLFW_KEY_ESCAPE = 256;

    /** The picture-in-picture window's close button (Android 13 on). */
    private static final String ACTION_CLOSE_PICTURE_IN_PICTURE =
            "ca.dnamobile.droidbridgelauncher.action.CLOSE_RUSTCRAFT_PIP";
    /** How long a game asked to close may take to save before the process is ended anyway. */
    private static final long CLOSE_TIMEOUT_MS = 12000L;

    private HorizonControlsHost controls;
    /** quit() has run: the task is going and the process ends. */
    private volatile boolean quitting;
    /** Closing was asked of the engine (picture-in-picture closed). */
    private boolean closeRequested;
    private boolean pictureInPictureEnterRequested;
    /**
     * True from entering picture-in-picture until the game is full size again: some Android
     * versions report it ended before onStop() when the player dismisses the window.
     */
    private boolean pictureInPictureWasActive;
    private boolean pictureInPictureCloseReceiverRegistered;
    private final BroadcastReceiver pictureInPictureCloseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_CLOSE_PICTURE_IN_PICTURE.equals(intent.getAction())) {
                closeGame("picture-in-picture close button");
            }
        }
    };
    /** Android button state last sent for the captured mouse. */
    private int capturedButtons;
    private boolean backDown;
    /** Lets Rust Craft hear LAN worlds announced on Wi-Fi (most phones drop multicast otherwise). */
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

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
        setEnv(ENV_USERNAME, extra(intent, EXTRA_USERNAME));
        setEnv(ENV_LOG, new File(home, "latest.log").getAbsolutePath());
        setEnv(ENV_ERROR, error.getAbsolutePath());

        ensureNativeLibraryLoaded();
        // DroidBridge's Game Orientation (Portrait, Reverse landscape, Centered Game View...)
        EngineWindowLayout.applyOrientation(this);
        super.onCreate(savedInstanceState);
        acquireMulticastLock();
        applyDisplaySettings();
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
            controls.setFollowEngineWindow(true);
            controls.attach();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                registerReceiver(pictureInPictureCloseReceiver,
                        new IntentFilter(ACTION_CLOSE_PICTURE_IN_PICTURE),
                        Context.RECEIVER_NOT_EXPORTED);
                pictureInPictureCloseReceiverRegistered = true;
            } catch (RuntimeException problem) {
                Log.w(TAG, "Could not register the picture-in-picture close action", problem);
            }
        }
    }

    // --- Leaving the game: quit(), picture-in-picture --------------------------------

    /**
     * Called by the engine's thread (crates/rc-android/src/lib.rs) once the player has quit,
     * the world is saved and the game's loop is over. NativeActivity can't host a second game
     * in this process, and a plain finish() leaves the game's task (started with
     * FLAG_ACTIVITY_NEW_TASK) in Recents, where opening it again showed a dead window: so the
     * task is taken out of Recents and the :rustcraft process ends, as HorizonActivity does.
     */
    @Keep
    public void quit() {
        if (quitting) return;
        quitting = true;
        Log.i(TAG, "rustcraft's loop finished; removing its task and ending the :rustcraft process");
        runOnUiThread(this::removeOwnTaskFromRecents);
        // (some Android builds remove the Recents card asynchronously: time to persist it)
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "droidbridge-rustcraft-quit");
        exit.setDaemon(false);
        exit.start();
    }

    private void removeOwnTaskFromRecents() {
        final int ownTaskId = getTaskId();
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager != null) {
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info != null && info.taskId == ownTaskId) {
                        Log.i(TAG, "Removing the rustcraft task from Recents, taskId=" + ownTaskId);
                        task.finishAndRemoveTask();
                        return;
                    }
                }
            }
        } catch (Throwable problem) {
            Log.w(TAG, "Could not remove the rustcraft task " + ownTaskId, problem);
        }
        try {
            finishAndRemoveTask();
        } catch (Throwable problem) {
            Log.w(TAG, "Could not remove the finished rustcraft task", problem);
            finish();
        }
    }

    /**
     * Ends the game from outside it (the picture-in-picture window closed): the engine saves
     * and closes as Save and Quit does, then calls quit(). An engine too old to be asked (or
     * one that doesn't answer in time) has its task removed and its process ended; Android
     * taking its window away already saved the world.
     */
    private void closeGame(String reason) {
        if (closeRequested || quitting) return;
        closeRequested = true;
        pictureInPictureEnterRequested = false;
        pictureInPictureWasActive = false;
        Log.i(TAG, "Closing rustcraft: " + reason);
        boolean asked = RustcraftInput.requestQuit();
        long timeout = asked ? CLOSE_TIMEOUT_MS : 1500L;
        Thread fallback = new Thread(() -> {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (quitting) return;
            Log.w(TAG, "rustcraft did not close in time; ending it");
            quitting = true;
            runOnUiThread(this::removeOwnTaskFromRecents);
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "droidbridge-rustcraft-close");
        fallback.setDaemon(false);
        fallback.start();
    }

    /** Home or another app: into picture-in-picture, when DroidBridge's setting has it on. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !LauncherPreferences.isGamePictureInPictureEnabled(this)
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                || quitting || closeRequested || isFinishing() || isInPictureInPictureMode()) {
            return;
        }
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(pictureInPictureAspect());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && pictureInPictureCloseReceiverRegistered) {
                Intent close = new Intent(ACTION_CLOSE_PICTURE_IN_PICTURE).setPackage(getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(this, 7422, close,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setCloseAction(new RemoteAction(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Close game", "Save the world and close Rust Craft", pending));
            }
            pictureInPictureEnterRequested = true;
            if (!enterPictureInPictureMode(builder.build())) {
                pictureInPictureEnterRequested = false;
                Log.i(TAG, "Android declined picture-in-picture");
            }
        } catch (RuntimeException problem) {
            pictureInPictureEnterRequested = false;
            Log.w(TAG, "Picture-in-picture unavailable", problem);
        }
    }

    /** The game window's own shape, within what Android allows (2.39:1 either way). */
    private Rational pictureInPictureAspect() {
        View decor = getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) return new Rational(16, 9);
        float ratio = width / (float) height;
        if (ratio > 2.39f) return new Rational(239, 100);
        if (ratio < 1f / 2.39f) return new Rational(100, 239);
        return new Rational(width, height);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPicture, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPicture, newConfig);
        pictureInPictureEnterRequested = false;
        if (inPictureInPicture) pictureInPictureWasActive = true;
        Log.i(TAG, "picture-in-picture " + (inPictureInPicture ? "on" : "off"));
        // the floating window shows the whole game (Centered Game View again when full size)
        EngineWindowLayout.applyWindowSize(this);
        if (controls != null) controls.setPictureInPicture(inPictureInPicture);
    }

    /** Turned, folded or resized: the game's window (Centered Game View) and the controls follow. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyDisplaySettings();
    }

    @Override
    protected void onStop() {
        // the floating window dismissed (swiped away, or its close button on older Android)
        boolean dismissed = pictureInPictureWasActive
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !isInPictureInPictureMode()
                && !isChangingConfigurations();
        super.onStop();
        if (dismissed) closeGame("picture-in-picture window dismissed");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // full size again (expanded from picture-in-picture, or back from Recents)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode()) {
            pictureInPictureWasActive = false;
        }
        applyDisplaySettings();
        if (controls != null) controls.onResume();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // (some OEMs reset the window's attributes after a focus or orientation change)
        if (hasFocus) applyDisplaySettings();
    }

    // --- Display: Force Fullscreen, Ignore display notch, Game Orientation -----------

    /**
     * DroidBridge's Force Fullscreen and Ignore display notch settings, as the Java engine's
     * GameActivity has them (FullscreenUtils: system bars, notch policy).
     *
     * NativeActivity draws its whole window, system bars included (there are no views to
     * pad), so with Force Fullscreen off the window itself is kept out of the bars: the game
     * then sits between them, as Java's does. Ignore display notch on keeps the window out of
     * the notch (LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER). The engine follows its window's new
     * size; DroidBridge's controls follow the window (HorizonControlsHost).
     *
     * Game Orientation as the Java engine has it, Portrait (Centered Game View) included: the
     * game's window is then the centered rectangle (EngineWindowLayout).
     */
    @SuppressWarnings("deprecation")
    private void applyDisplaySettings() {
        try {
            FullscreenUtils.enableImmersive(this);
            boolean fullscreen = LauncherPreferences.isForceFullscreenMode(this);
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                int fit = fullscreen ? 0 : WindowInsets.Type.systemBars();
                if (attrs.getFitInsetsTypes() != fit) {
                    attrs.setFitInsetsTypes(fit);
                    window.setAttributes(attrs);
                }
            } else {
                int inScreen = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                if (fullscreen) {
                    window.addFlags(inScreen);
                } else {
                    window.clearFlags(inScreen | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                }
            }
        } catch (Throwable problem) {
            Log.w(TAG, "Could not apply the display settings", problem);
        }
        EngineWindowLayout.applyOrientation(this);
        EngineWindowLayout.applyWindowSize(this);
        if (controls != null) {
            HorizonControlsHost host = controls;
            getWindow().getDecorView().post(host::followEngineWindowNow);
        }
    }

    @Override
    protected void onPause() {
        if (controls != null) controls.onPause();
        super.onPause();
    }

    private void acquireMulticastLock() {
        try {
            android.net.wifi.WifiManager wifi =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            multicastLock = wifi.createMulticastLock("RustcraftLan");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException e) {
            Log.w(TAG, "No Wi-Fi multicast for LAN worlds", e);
            multicastLock = null;
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) return;
        try {
            if (multicastLock.isHeld()) multicastLock.release();
        } catch (RuntimeException ignored) {
        }
        multicastLock = null;
    }

    @Override
    protected void onDestroy() {
        releaseMulticastLock();
        if (pictureInPictureCloseReceiverRegistered) {
            try {
                unregisterReceiver(pictureInPictureCloseReceiver);
            } catch (RuntimeException ignored) {
            }
            pictureInPictureCloseReceiverRegistered = false;
        }
        if (controls != null) {
            controls.destroy();
            controls = null;
        }
        super.onDestroy();
        // The engine runs one game loop per process: an activity finished some other way
        // than quit() (an engine older than quit(), or Android finishing it) ends the process
        // here so the next launch starts clean, and its task leaves Recents.
        if (isFinishing() && !isChangingConfigurations() && !quitting) {
            Log.i(TAG, "rustcraft finished; closing the :rustcraft process");
            quitting = true;
            try {
                finishAndRemoveTask();
            } catch (Throwable ignored) {
            }
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
