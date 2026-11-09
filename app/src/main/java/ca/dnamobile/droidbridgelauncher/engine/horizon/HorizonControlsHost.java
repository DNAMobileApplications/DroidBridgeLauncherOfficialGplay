/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.controls.ControlsActivity;
import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.controls.GameImeViewportController;
import ca.dnamobile.droidbridgelauncher.controls.InGameControlsEditorDialog;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;
import ca.dnamobile.droidbridgelauncher.controls.TouchKeyboardHelper;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.recording.RecordingPreferences;
import ca.dnamobile.droidbridgelauncher.recording.GameRecordingService;
import ca.dnamobile.droidbridgelauncher.launcher.DroidBridgeLaunchActivity;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsStore;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsLayoutData;
import ca.dnamobile.droidbridgelauncher.game.FloatingGameSettingsOverlayController;
import ca.dnamobile.droidbridgelauncher.input.GameCursorOverlay;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingDialog;
import ca.dnamobile.droidbridgelauncher.input.GamepadInputController;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingStore;
import ca.dnamobile.droidbridgelauncher.input.GyroInputController;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSdlFpsBridge;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.settings.GameOverlayPreferences;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import dev.horizon.android.HorizonInput;

/**
 * DroidBridge's own controls over the Horizon engine: the touch controls overlay with the
 * player's saved layout, the virtual mouse cursor, touch camera and menu clicks
 * (MinecraftGLSurface, used for input only), and the controller mapper.
 *
 * The engine's NativeActivity owns its window's surface and input queue, so these views
 * live in a transparent panel window above it. The panel is not focusable, so the engine
 * keeps window focus (its camera needs it); it becomes focusable only while DroidBridge's
 * Android keyboard is open. Everything the controls send goes through CallbackBridge to
 * {@link HorizonInputSink}, and the engine reports pointer grabs back so the controls switch
 * between gameplay and menu behaviour exactly as they do for the Java engine.
 *
 * The floating settings cog and FPS badge are the Java engine's too
 * ({@link FloatingGameSettingsOverlayController}): same look, saved position and settings
 * (Show in-game settings button, FPS counter size and opacity); the badge reads the engine's
 * frame rate. The cog opens DroidBridge's in-game menu for Horizon.
 */
public final class HorizonControlsHost {
    private static final String TAG = "DroidBridgeHorizon";
    private static final int REQUEST_GAME_RECORDING_CAPTURE = 0x4841;
    private static final int REQUEST_GAME_RECORDING_AUDIO_PERMISSION = 0x4842;

    private final Activity activity;
    @Nullable private final File gameDirectory;
    /** The Vulkan driver DroidBridge asked the engine to load; null for the system one. */
    @Nullable private final String vulkanDriverName;
    private final WindowManager windowManager;

    @Nullable private FrameLayout root;
    @Nullable private WindowManager.LayoutParams panelParams;
    @Nullable private MinecraftGLSurface inputSurface;
    @Nullable private GameCursorOverlay cursorOverlay;
    @Nullable private TouchControlsOverlay touchOverlay;
    @Nullable private GamepadInputController gamepad;
    /** The gyroscope camera (Controls settings), as in the Java engine. */
    @Nullable private GyroInputController gyro;
    /** The hardware mouse DPI scale last given to the engine; checked a few times a second. */
    private float engineMouseScale = Float.NaN;
    /** The menu cursor sensitivity last given to the engine. */
    private float engineMenuMouseScale = Float.NaN;
    private long lastMouseScaleCheckUptimeMs;
    @Nullable private FloatingGameSettingsOverlayController floatingSettings;
    @Nullable private HorizonGameLogOverlayController gameLogOverlay;
    /** Answers the recorder when "Hide touch controls in recordings" is on. */
    @Nullable private HorizonRecordingFrames recordingFrames;
    private int imeViewportBottomInset;
    private final GameImeViewportController.ImeViewportTarget imeViewportTarget =
            this::setEngineImeViewportBottomInset;
    private boolean destroyed;
    private boolean lastGrabbed;
    /** The engine's own window (NativeActivity): it holds focus, so pointer capture goes here. */
    @Nullable private View engineDecor;
    /** The floating cog: a physical mouse still clicks it as an Android button. */
    @Nullable private View settingsButton;
    private long lastPhysicalMouseUptimeMs;
    /**
     * The real mouse is what the player is using now. Touching the screen or using a controller
     * turns it off (the engine stops capturing the mouse and DroidBridge's cursor follows the
     * finger or the stick); moving the mouse again turns it back on. Before, once a mouse had been
     * used the engine kept it captured for the rest of the game, so its menu cursor kept pulling
     * DroidBridge's cursor back from a tap or the controller's cursor.
     */
    private boolean mouseActive;
    /** The Android button state last sent for the physical mouse in Native Mouse menus. */
    private int physicalMouseButtons;
    private long lastCaptureRequestUptimeMs;
    private boolean captureLogged;
    /** This host asked Android for the capture (it then releases only its own). */
    private boolean captureRequestedHere;
    /** False once the engine says it can't move its menu cursor from captured motion. */
    private boolean menuCaptureSupported = true;
    /** The engine was told captured motion moves its menu cursor (Native Mouse in use). */
    private boolean menuCaptureOn;
    /** The grab state the capture was last updated for, to notice the game releasing it. */
    private boolean captureGrabbed;
    /** Android Virtual Mouse: the engine keeps menu clicks for this host (drainMenuMouseClicks). */
    private boolean menuClicksToHost;
    /** A left press the cog took, or the touch controls took; its moves and release go there too. */
    private boolean cogMousePress;
    private boolean controlMousePress;
    private int lastCursorShape = -1;
    private boolean driverProblemShown;

    private final Choreographer.FrameCallback grabPoll = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (destroyed) return;
            syncGrabStateFromEngine();
            updateEngineMouseScale();
            updateEngineMouseCapture();
            drainMenuMouseClicks();
            // Dynamic mouse cursor: the cursor the game's widgets ask for (pointing hand over
            // buttons, not-allowed over disabled ones), the same SDL ids the Java engine's
            // SDL window reports, so GameCursorOverlay draws the player's chosen icons.
            int shape = HorizonInput.cursorShape();
            if (shape != lastCursorShape) {
                lastCursorShape = shape;
                CallbackBridge.onSdlSystemCursorChanged(shape);
                GameCursorOverlay cursor = cursorOverlay;
                if (cursor != null) cursor.invalidate();
            }
            if (!driverProblemShown && vulkanDriverName != null
                    && HorizonInput.vulkanDriverState() == 2) {
                driverProblemShown = true;
                Toast.makeText(activity, vulkanDriverName
                        + " did not load; the Rust engine is using the system Vulkan driver.",
                        Toast.LENGTH_LONG).show();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public HorizonControlsHost(
            @NonNull Activity activity,
            @Nullable File gameDirectory,
            @Nullable String vulkanDriverName
    ) {
        this.activity = activity;
        this.gameDirectory = gameDirectory;
        this.vulkanDriverName = vulkanDriverName;
        this.windowManager = activity.getWindowManager();
        // The Java engine's experimental game resolution (4:3, 1920x1080, custom) letterboxes
        // its own view; it doesn't apply to the Rust engine, which has its own Aspect ratio
        // setting and maps the letterbox itself from full-window positions. DroidBridge's
        // controls here address the whole window, so their touch cursor, menu cursor and
        // hotbar must not use the Java profile's box: that put them out of step with the game.
        GameResolutionSettings.setRuntimeProfileOverride(
                new GameResolutionSettings.Profile(GameResolutionSettings.MODE_NATIVE, 0, 0));
        // The same for the launcher's render scale slider: below 100% it shrank the controls'
        // coordinate space, while the Native Mouse menu cursor is in window pixels, so the game
        // hovered short of the drawn cursor (80% => 0.8x). The engine's own Client resolution
        // setting scales the Rust engine.
        GameResolutionSettings.setRuntimeRenderScaleOverride(100);
    }

    /** Adds the controls once the engine's window exists. Call from Activity.onCreate. */
    public void attach() {
        if (recordingFrames == null) {
            recordingFrames = new HorizonRecordingFrames(activity);
            recordingFrames.register();
        }
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> attachWhenReady(decor, 0));
    }

    private void attachWhenReady(@NonNull View decor, int attempt) {
        if (destroyed || root != null) return;
        if (decor.getWindowToken() == null) {
            if (attempt < 100) decor.postDelayed(() -> attachWhenReady(decor, attempt + 1), 50L);
            else Log.e(TAG, "Engine window never got a token; DroidBridge controls unavailable");
            return;
        }
        try {
            build(decor);
        } catch (Throwable throwable) {
            Log.e(TAG, "Could not add DroidBridge controls over Horizon", throwable);
            destroy();
        }
    }

    private void build(@NonNull View decor) {
        FrameLayout panel = new InputPanel(activity);
        panel.setBackgroundColor(Color.TRANSPARENT);
        ViewGroup.LayoutParams match = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // NativeActivity renders directly into its window swapchain; there is no child
        // SurfaceView to translate. DroidBridge's existing IME viewport controller still owns
        // the inset calculation, but Horizon applies that inset in its final presentation pass.
        // This keeps the full render size and Minecraft GUI scale exactly like the Java path.
        GameImeViewportController.registerImeViewportTarget(imeViewportTarget);

        // DroidBridge's game view, used only for its touch camera, menu clicks and mouse
        // handling: start() is never called, so it creates no surface or renderer.
        MinecraftGLSurface surface = new MinecraftGLSurface(activity);
        surface.setBackgroundColor(Color.TRANSPARENT);
        panel.addView(surface, match);

        GameCursorOverlay cursor = new GameCursorOverlay(activity);
        cursor.setViewportTarget(surface);
        panel.addView(cursor, match);

        ControlsPreferences.beginVirtualMouseLaunchSession(activity);
        TouchControlsOverlay overlay = new TouchControlsOverlay(activity);
        overlay.setAppMenuListener(this::showControlsMenu);
        overlay.setPassthroughTarget(surface);
        if (gameDirectory != null) {
            overlay.setMinecraftOptionsFile(new File(gameDirectory, "options.txt"));
        }
        overlay.setInputViewportTarget(null);
        overlay.setDualScreenBottomHudHotbarMode(false);
        overlay.loadSelectedLayout();
        overlay.applyVirtualMouseLaunchSessionState();
        overlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(activity));
        panel.addView(overlay, match);

        // The floating cog and FPS badge, above the touch controls as in the Java engine.
        ImageButton cog = new ImageButton(activity);
        cog.setBackgroundResource(R.drawable.bg_ingame_settings_button);
        cog.setImageResource(R.drawable.ic_ingame_settings_24);
        cog.setContentDescription(activity.getString(R.string.game_button_overlay_settings));
        int cogPadding = dp(12);
        cog.setPadding(cogPadding, cogPadding, cogPadding, cogPadding);
        FrameLayout.LayoutParams cogParams = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.END | Gravity.BOTTOM);
        cogParams.setMargins(dp(16), dp(16), dp(16), dp(16));
        panel.addView(cog, cogParams);
        settingsButton = cog;
        DroidBridgeSdlFpsBridge.setHostFpsSource(HorizonInput::fps);
        FloatingGameSettingsOverlayController floating =
                new FloatingGameSettingsOverlayController(activity, cog);
        floating.attach();
        cog.setOnClickListener(view -> showControlsMenu());
        floating.bringToFront();
        floating.resume();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.token = decor.getWindowToken();
        params.setTitle("DroidBridge controls");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Always cover exactly the game's full screen. By default Android fits a window
            // inside the visible system bars, and while this window holds focus for the
            // keyboard the navigation bar becomes visible, so the controls shrank by its height
            // under the game: every tap then landed that far (about one chat row) higher in
            // Horizon than under the finger.
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windowManager.addView(panel, params);
        keepSystemBarsHidden(panel);

        root = panel;
        panelParams = params;
        engineDecor = decor;
        inputSurface = surface;
        cursorOverlay = cursor;
        touchOverlay = overlay;
        floatingSettings = floating;
        gameLogOverlay = new HorizonGameLogOverlayController(activity, panel);
        gameLogOverlay.bringToFront();
        floating.bringToFront();

        CallbackBridge.setHostInputSink(new HorizonInputSink(panel, decor, () -> imeViewportBottomInset));
        panel.post(this::initializePointer);

        // Horizon is on the SAME display as the game. Give DroidBridge's keyboard helper
        // focus callbacks for this NOT_FOCUSABLE overlay without registering it as a
        // dual-screen host (dual-screen intentionally suppresses viewport push).
        TouchKeyboardHelper.registerSingleScreenImeHost(panel,
                () -> setPanelFocusable(true),
                () -> setPanelFocusable(false));

        HorizonInput.setKeyRepeat(LauncherPreferences.isPhysicalKeyRepeatEnabled(activity));
        gamepad = new GamepadInputController(panel);
        GyroInputController gyroscope = new GyroInputController(activity);
        gyro = gyroscope;
        gyroscope.refreshFromPreferences();
        lastGrabbed = false;
        Choreographer.getInstance().postFrameCallback(grabPoll);
        Log.i(TAG, "DroidBridge controls attached over Horizon");
    }

    /**
     * The controls window becomes focusable only while Android's IME is visible. During that
     * period controller events are delivered to this WindowManager root instead of the
     * NativeActivity window, so intercept them here too. Otherwise a DPAD release can be lost
     * while the IME owns focus and the next event appears as a stuck movement direction.
     */
    private final class InputPanel extends FrameLayout {
        InputPanel(@NonNull Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (HorizonControlsHost.this.handleControllerKeyEvent(event)) return true;
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (HorizonControlsHost.this.handleMotionEvent(event)) return true;
            if (HorizonControlsHost.this.routePhysicalMouse(event, false)) return true;
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (HorizonControlsHost.this.routePhysicalMouse(event, true)) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFingerTouch(event)) {
                switchAwayFromMouse("touch");
            }
            return super.dispatchTouchEvent(event);
        }

        /**
         * Native Mouse: the hide-Android-mouse-cursor setting over the whole controls window.
         * Android Virtual Mouse keeps its existing cursor handling.
         */
        @Override
        public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && isPhysicalMouseEvent(event)
                    && nativeMouseMode()
                    && LauncherPreferences.isHideAndroidPhysicalMouseCursor(getContext())) {
                return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
            }
            return super.onResolvePointerIcon(event, pointerIndex);
        }
    }

    // --- Physical mouse ----------------------------------------------------------------------

    /**
     * A real mouse over this controls window, which happens only while the engine's window
     * hasn't captured it yet (or the Android keyboard has the focus): once captured, its events
     * go to the engine (updateEngineMouseCapture). Android Virtual Mouse then keeps DroidBridge's
     * existing handling, touch buttons included, so nothing here takes its events.
     *
     * With Native Mouse the touch controls lie over DroidBridge's game view in this window, and
     * that view moves its cursor in menus only with Android pointer capture, which this
     * never-focused window can't get: the game's cursor stayed put while Android's own pointer
     * moved. So in menus the mouse's real position, buttons and wheel go to the game here, and
     * in gameplay the engine's own window captures the mouse (updateEngineMouseCapture). The
     * cog stays an ordinary button for the mouse.
     */
    private boolean routePhysicalMouse(@NonNull MotionEvent event, boolean touchStream) {
        if (destroyed || !isPhysicalMouseEvent(event)) return false;
        // A real mouse is in use (either mode): from now on the engine's window captures it.
        lastPhysicalMouseUptimeMs = SystemClock.uptimeMillis();
        mouseActive = true;
        if (!nativeMouseMode()) {
            // Android Virtual Mouse before the capture takes hold (or while the Android keyboard
            // has the focus): DroidBridge's existing handling, touch buttons included.
            syncGrabStateFromEngine();
            updateEngineMouseCapture();
            return false;
        }
        View cog = settingsButton;
        if (cog != null && cog.getVisibility() == View.VISIBLE && isOver(cog, event)) return false;
        syncGrabStateFromEngine();
        updateEngineMouseCapture();
        if (!lastGrabbed) return sendNativeMenuMouse(event);
        physicalMouseButtons = 0;
        // Gameplay before Android has granted the capture: DroidBridge's game view turns the
        // camera from the pointer's movement meanwhile.
        MinecraftGLSurface surface = inputSurface;
        if (surface == null) return touchStream;
        boolean handled = touchStream
                ? surface.dispatchTouchEvent(event)
                : surface.dispatchGenericMotionEvent(event);
        return handled || touchStream;
    }

    private boolean nativeMouseMode() {
        return !LauncherPreferences.isAndroidVirtualPhysicalMouse(activity);
    }

    /**
     * Native Mouse in a menu: the pointer's position in this window is the game's cursor
     * position (CallbackBridge's coordinates are this window's pixels), so DroidBridge's cursor
     * and the game's both follow the real mouse exactly.
     */
    private boolean sendNativeMenuMouse(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL) {
            float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (vertical != 0f || horizontal != 0f) CallbackBridge.sendScroll(horizontal, vertical);
            return true;
        }
        if (action != MotionEvent.ACTION_HOVER_EXIT && action != MotionEvent.ACTION_CANCEL) {
            CallbackBridge.sendHardwareCursorPos(event.getX(), event.getY());
        }
        // A mouse's touch-stream UP ends a primary click; some devices still report the
        // button in that event's state, so it is cleared there.
        int buttons = event.getButtonState();
        if (action == MotionEvent.ACTION_UP) buttons &= ~MotionEvent.BUTTON_PRIMARY;
        if (action == MotionEvent.ACTION_CANCEL) buttons = 0;
        sendMouseButtons(buttons);
        return true;
    }

    /** Presses and releases for the buttons whose state changed (GLFW 0 left, 1 right, ...). */
    private void sendMouseButtons(int buttons) {
        int[][] map = {
                {MotionEvent.BUTTON_PRIMARY, 0},
                {MotionEvent.BUTTON_SECONDARY, 1},
                {MotionEvent.BUTTON_TERTIARY, 2},
                {MotionEvent.BUTTON_BACK, 3},
                {MotionEvent.BUTTON_FORWARD, 4},
        };
        for (int[] entry : map) {
            boolean was = (physicalMouseButtons & entry[0]) != 0;
            boolean now = (buttons & entry[0]) != 0;
            if (was != now) CallbackBridge.sendMouseButton(entry[1], now);
        }
        physicalMouseButtons = buttons;
    }

    private static boolean isPhysicalMouseEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        boolean mouse = (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE);
        if (!mouse || event.getPointerCount() < 1) return false;
        return event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS;
    }

    private static boolean isOver(@NonNull View view, @NonNull MotionEvent event) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        float x = event.getX();
        float y = event.getY();
        return x >= at[0] && y >= at[1] && x < at[0] + view.getWidth() && y < at[1] + view.getHeight();
    }

    /**
     * A real mouse, in either mode: Android captures it on the engine's own window (the
     * focused one; this controls window never takes focus, so a capture asked for here would be
     * refused), in gameplay and in menus. Captured, Android hides its pointer and keeps it away
     * from the screen edges, which otherwise reveal the status and navigation bars; the mouse's
     * motion and buttons go straight to the engine. In gameplay the motion turns the camera; in
     * menus the engine moves its cursor with it (HorizonInput.setMenuMouseCapture), and
     * DroidBridge's cursor follows the engine's (followEngineMenuCursor). Android Virtual Mouse
     * also gets menu clicks first, for DroidBridge's own buttons (drainMenuMouseClicks), as in
     * the Java engine. An engine built before that captures in gameplay only; its menus then use
     * sendNativeMenuMouse (Native Mouse) or DroidBridge's game view (Virtual Mouse).
     */
    private void updateEngineMouseCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || destroyed) return;
        View decor = engineDecor;
        if (decor == null) return;
        // Only once a real mouse has been used in this game: some controllers' touchpads also
        // report themselves as a mouse, and their players must keep the normal pointer.
        boolean mouseInUse = mouseActive;
        if (mouseInUse != menuCaptureOn && menuCaptureSupported) {
            menuCaptureSupported = HorizonInput.setMenuMouseCapture(mouseInUse, -1f, -1f);
            menuCaptureOn = mouseInUse && menuCaptureSupported;
        }
        // Android Virtual Mouse: menu clicks come here first, for DroidBridge's own buttons.
        boolean clicksToHost = menuCaptureOn && !nativeMouseMode();
        if (clicksToHost != menuClicksToHost) {
            menuClicksToHost = HorizonInput.setMenuMouseClicksToHost(clicksToHost) && clicksToHost;
            if (!menuClicksToHost) endVirtualMouseUiPress();
        }
        if (menuCaptureOn && captureGrabbed && !lastGrabbed) {
            // The game released the pointer and put its cursor in the middle; so does the
            // engine's menu cursor, and DroidBridge's follows.
            FrameLayout panel = root;
            if (panel != null) {
                CallbackBridge.mouseX = panel.getWidth() / 2f;
                CallbackBridge.mouseY = panel.getHeight() / 2f;
            }
        }
        captureGrabbed = lastGrabbed;
        if (lastGrabbed) endVirtualMouseUiPress();
        boolean want = mouseInUse && (lastGrabbed || menuCaptureOn);
        boolean has;
        try {
            has = decor.hasPointerCapture();
        } catch (Throwable ignored) {
            return;
        }
        if (has && !lastGrabbed && menuCaptureOn) followEngineMenuCursor();
        if (want && !has) {
            if (!decor.hasWindowFocus()) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastCaptureRequestUptimeMs < 250L) return;
            lastCaptureRequestUptimeMs = now;
            if (!lastGrabbed && menuCaptureOn) {
                // The engine's menu cursor starts where DroidBridge's cursor is now.
                HorizonInput.setMenuMouseCapture(true,
                        CallbackBridge.mouseX + controlsToEngineX(),
                        CallbackBridge.mouseY + controlsToEngineY());
            }
            try {
                decor.requestPointerCapture();
                captureRequestedHere = true;
            } catch (Throwable throwable) {
                Log.w(TAG, "Could not capture the mouse for the engine", throwable);
            }
            if (!captureLogged) {
                captureLogged = true;
                LauncherLogManager.append("DroidBridgePhysicalMouse: Rust engine capture requested ("
                        + (lastGrabbed ? "gameplay" : "menu") + ")");
            }
        } else if (!want && captureRequestedHere) {
            captureRequestedHere = false;
            captureLogged = false;
            if (!has) return;
            try {
                decor.releasePointerCapture();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * The captured real mouse's two speeds (Controls settings): the hardware mouse DPI scale
     * turns the camera in gameplay, the menu cursor sensitivity moves the cursor in menus (the
     * crafting table, inventories). Read a few times a second, so the sliders apply live while
     * the in-game dialog is open.
     */
    private void updateEngineMouseScale() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMouseScaleCheckUptimeMs < 250L) return;
        lastMouseScaleCheckUptimeMs = now;
        float camera;
        float menu;
        try {
            GamepadMappingStore store = GamepadMappingStore.get(activity);
            camera = store.getHardwareMouseDpiScaleMultiplier();
            menu = store.getMenuCursorSensitivityMultiplier();
        } catch (Throwable ignored) {
            return;
        }
        camera = validScale(camera);
        menu = validScale(menu);
        if (camera != engineMouseScale) {
            engineMouseScale = camera;
            HorizonInput.setMouseScale(camera);
        }
        if (menu != engineMenuMouseScale) {
            engineMenuMouseScale = menu;
            HorizonInput.setMenuMouseScale(menu);
        }
    }

    private static float validScale(float scale) {
        return Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0f ? 1f : scale;
    }

    /** DroidBridge's cursor (CallbackBridge, this window's pixels) at the engine's menu cursor. */
    private void followEngineMenuCursor() {
        long packed = HorizonInput.menuCursor();
        if (packed < 0L) return;
        float x = (float) (int) (packed >>> 32) - controlsToEngineX();
        float y = (float) (int) (packed & 0xFFFFFFFFL) - controlsToEngineY();
        if (x == CallbackBridge.mouseX && y == CallbackBridge.mouseY) return;
        CallbackBridge.mouseX = x;
        CallbackBridge.mouseY = y;
        GameCursorOverlay cursor = cursorOverlay;
        if (cursor != null) cursor.invalidate();
        // A held press on DroidBridge's own button follows the cursor (dragging off cancels it).
        if (cogMousePress || controlMousePress) dispatchVirtualMouseUi(MotionEvent.ACTION_MOVE);
    }

    /**
     * Android Virtual Mouse in menus, as in the Java engine: a left click first goes to
     * DroidBridge's own on-screen buttons under the cursor (the cog, then the touch controls). If
     * one takes it, the game doesn't get it; every other click goes on to the game.
     */
    private void drainMenuMouseClicks() {
        if (!menuClicksToHost) return;
        int packed;
        int guard = 0;
        while (guard++ < 64 && (packed = HorizonInput.takeMenuMouseButton()) >= 0) {
            int button = packed >> 1;
            boolean pressed = (packed & 1) != 0;
            if (button == 0 && routeVirtualMouseClick(pressed)) continue;
            HorizonInput.mouseButton(button, pressed);
        }
    }

    /** A left press or release to DroidBridge's own buttons; true when one of them took it. */
    private boolean routeVirtualMouseClick(boolean pressed) {
        if (pressed) {
            endVirtualMouseUiPress();
            FloatingGameSettingsOverlayController floating = floatingSettings;
            float[] at = cursorOnScreen();
            if (floating != null
                    && floating.dispatchCapturedPhysicalMouseGesture(MotionEvent.ACTION_DOWN, at[0], at[1])) {
                cogMousePress = true;
                return true;
            }
            TouchControlsOverlay overlay = touchOverlay;
            if (overlay != null && overlay.dispatchCapturedPhysicalMouseToControl(
                    MotionEvent.ACTION_DOWN, at[0], at[1], SystemClock.uptimeMillis())) {
                controlMousePress = true;
                return true;
            }
            return false;
        }
        if (!cogMousePress && !controlMousePress) return false;
        dispatchVirtualMouseUi(MotionEvent.ACTION_UP);
        cogMousePress = false;
        controlMousePress = false;
        return true;
    }

    private void dispatchVirtualMouseUi(int action) {
        float[] at = cursorOnScreen();
        try {
            FloatingGameSettingsOverlayController floating = floatingSettings;
            if (cogMousePress && floating != null) {
                floating.dispatchCapturedPhysicalMouseGesture(action, at[0], at[1]);
            }
            TouchControlsOverlay overlay = touchOverlay;
            if (controlMousePress && overlay != null) {
                overlay.dispatchCapturedPhysicalMouseToControl(action, at[0], at[1],
                        SystemClock.uptimeMillis());
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Android Virtual Mouse button press failed", throwable);
        }
    }

    /** Cancels a held press on DroidBridge's own button (the game grabbed, or the mode changed). */
    private void endVirtualMouseUiPress() {
        if (!cogMousePress && !controlMousePress) return;
        dispatchVirtualMouseUi(MotionEvent.ACTION_CANCEL);
        cogMousePress = false;
        controlMousePress = false;
    }

    /** DroidBridge's cursor (CallbackBridge, this window's pixels) in screen pixels. */
    @NonNull
    private float[] cursorOnScreen() {
        float[] at = {CallbackBridge.mouseX, CallbackBridge.mouseY};
        FrameLayout panel = root;
        if (panel == null) return at;
        try {
            int[] origin = new int[2];
            panel.getLocationOnScreen(origin);
            at[0] += origin[0];
            at[1] += origin[1];
        } catch (Throwable ignored) {
        }
        return at;
    }

    /** This window's origin in the engine window's pixels (both normally cover the screen). */
    private float controlsToEngineX() {
        return originOffset(0);
    }

    private float controlsToEngineY() {
        return originOffset(1);
    }

    private float originOffset(int axis) {
        FrameLayout panel = root;
        View decor = engineDecor;
        if (panel == null || decor == null) return 0f;
        try {
            int[] panelAt = new int[2];
            int[] decorAt = new int[2];
            panel.getLocationOnScreen(panelAt);
            decor.getLocationOnScreen(decorAt);
            return panelAt[axis] - decorAt[axis];
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    /** The controls' coordinate space is the panel itself (no resolution scaling here). */
    private void initializePointer() {
        initializePointer(0);
    }

    private void initializePointer(int attempt) {
        FrameLayout panel = root;
        if (panel == null || destroyed) return;

        int width = panel.getWidth();
        int height = panel.getHeight();
        if (width <= 1 || height <= 1) {
            // WindowManager can attach the overlay before its first real layout pass.
            // Publishing a fake 1x1 coordinate space here initializes both launcher
            // cursors at 0,0 and they stay there until another event happens to recenter.
            if (attempt < 8) {
                long delay = attempt < 3 ? 16L : 48L;
                panel.postDelayed(() -> initializePointer(attempt + 1), delay);
            }
            return;
        }

        CallbackBridge.windowWidth = width;
        CallbackBridge.windowHeight = height;
        CallbackBridge.physicalWidth = width;
        CallbackBridge.physicalHeight = height;
        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX = Math.max(0f, width - 1f) / 2f;
        CallbackBridge.mouseY = Math.max(0f, height - 1f) / 2f;
        CallbackBridge.setCursorPosSilently(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    /**
     * Compatibility hook for an older Horizon engine build. DroidBridge owns keyboard launch;
     * Horizon is never allowed to pop an IME on its own. The player opens chat with T or / and
     * then uses DroidBridge's Input/Keyboard action, exactly like the Java engine.
     */
    public void showChatKeyboard() {
        Log.d(TAG, "Ignored Horizon keyboard request; DroidBridge owns the IME");
    }

    /** Compatibility hook for an older Horizon engine build. */
    public void hideKeyboard() {
        // Do not close a DroidBridge keyboard just because Horizon toggled its old IME state.
    }

    /**
     * Applies DroidBridge's normal IME viewport push to Horizon's completed frame. NativeActivity
     * owns the window surface directly, so this must cross the JNI bridge instead of trying to
     * translate a non-existent Android SurfaceView.
     */
    private void setEngineImeViewportBottomInset(int bottomInsetPx) {
        imeViewportBottomInset = Math.max(0, bottomInsetPx);
        try {
            HorizonInput.setImeViewportBottomInset(imeViewportBottomInset);
            Log.d(TAG, "Horizon IME presentation inset=" + imeViewportBottomInset);
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not update Horizon IME viewport inset", throwable);
        }
    }

    /**
     * Keep DroidBridge's controller profile in lock-step with Horizon. The Choreographer poll
     * updates normal UI state, but an IME focus handoff can occur between frames. Query Rust
     * synchronously before mapping each controller event so the first DPAD press after Send
     * cannot be mistaken for a menu Arrow key and trigger Horizon's arrow-key camera control.
     */
    private boolean syncGrabStateFromEngine() {
        boolean grabbed = lastGrabbed;
        try {
            grabbed = HorizonInput.isGrabbed();
        } catch (Throwable ignored) {
        }
        if (grabbed != lastGrabbed) {
            lastGrabbed = grabbed;
            CallbackBridge.setSdlGrabState(grabbed);
        }
        return grabbed;
    }

    private void setPanelFocusable(boolean focusable) {
        // Focus jumps between NativeActivity and the overlay window when the IME opens/closes.
        // Release any mapper-held direction before that jump so a missing Android ACTION_UP can
        // never leave W/A/S/D (or another remapped key) latched in Horizon.
        GamepadInputController controller = gamepad;
        if (controller != null) controller.onImeFocusTransition();
        syncGrabStateFromEngine();

        // Chat releases Minecraft's pointer, which normally makes DroidBridge's menu cursor
        // appear. While the Android IME owns focus that cursor is not useful and makes it look
        // like the controller changed modes. GameCursorOverlay draws into its parent's overlay,
        // so suppress its drawable explicitly rather than toggling the View's visibility.
        GameCursorOverlay cursor = cursorOverlay;
        if (cursor != null) cursor.setTemporarilySuppressed(true);

        FrameLayout panel = root;
        WindowManager.LayoutParams params = panelParams;
        if (panel == null || params == null || destroyed) return;
        int flags = focusable
                ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                : params.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (flags != params.flags) {
            params.flags = flags;
            params.softInputMode = focusable
                    ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    : params.softInputMode;
            try {
                windowManager.updateViewLayout(panel, params);
            } catch (Throwable throwable) {
                Log.w(TAG, "Could not change the controls window focus", throwable);
            }
        }

        // While this window owns focus it also decides the system bars; keep them hidden like
        // the game so the navigation bar never appears over (and resizes) the game.
        keepSystemBarsHidden(panel);
        panel.post(() -> keepSystemBarsHidden(panel));

        if (!focusable) {
            // Rust may re-grab the pointer while Android is still finishing the IME hide
            // animation. Refresh across that boundary. Keep the software cursor suppressed
            // until the focus-return/capture event has had a chance to run; if chat really is
            // still open, unsuppressing later will correctly let menu mode show it again.
            panel.post(this::syncGrabStateFromEngine);
            panel.postDelayed(this::syncGrabStateFromEngine, 16L);
            panel.postDelayed(this::syncGrabStateFromEngine, 50L);
            panel.postDelayed(() -> {
                syncGrabStateFromEngine();
                GameCursorOverlay currentCursor = cursorOverlay;
                if (currentCursor != null) currentCursor.setTemporarilySuppressed(false);
            }, 120L);
        }
    }

    /** Immersive mode for the controls window, matching the game's own window. */
    @SuppressWarnings("deprecation")
    private static void keepSystemBarsHidden(@NonNull View panel) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = panel.getWindowInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                }
            } else {
                panel.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not hide system bars for the controls window", throwable);
        }
    }

    /** The cog and overlay menu button: keep Horizon in lock-step with GameActivity's menu. */
    private void showControlsMenu() {
        if (destroyed || activity.isFinishing()) return;

        LauncherDialogStyle.syncSelectedLauncherTheme(activity);
        boolean touchEnabled = ControlsPreferences.isTouchControlsEnabled(activity);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(10));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText("In-game controls");
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(4));
        content.addView(title);

        TextView summary = new TextView(activity);
        summary.setText("Quick controls, overlay settings, and recovery actions while Minecraft is running.");
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setTextSize(13f);
        summary.setPadding(0, 0, 0, dp(12));
        content.addView(summary);

        final AlertDialog[] dialogRef = new AlertDialog[1];

        // Same live log controls as GameActivity.
        TextView logWindowLabel = new TextView(activity);
        logWindowLabel.setText("Game log window");
        logWindowLabel.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        logWindowLabel.setTextSize(15f);
        logWindowLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logWindowLabel.setPadding(0, dp(2), 0, dp(2));
        content.addView(logWindowLabel);

        TextView logWindowHint = new TextView(activity);
        logWindowHint.setText("Show or hide the movable, resizable live game log window.");
        logWindowHint.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        logWindowHint.setTextSize(12f);
        content.addView(logWindowHint);

        LinearLayout logRow = new LinearLayout(activity);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER_VERTICAL);
        logRow.setPadding(0, dp(2), 0, dp(10));

        RadioGroup choices = new RadioGroup(activity);
        choices.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton on = new RadioButton(activity);
        on.setId(View.generateViewId());
        on.setText("On");
        on.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        RadioButton off = new RadioButton(activity);
        off.setId(View.generateViewId());
        off.setText("Off");
        off.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        choices.addView(on);
        choices.addView(off);
        boolean logEnabled = LauncherPreferences.isShowGameLogOverlay(activity);
        choices.check(logEnabled ? on.getId() : off.getId());
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            boolean enabled = checkedId == on.getId();
            if (LauncherPreferences.isShowGameLogOverlay(activity) == enabled) return;
            HorizonGameLogOverlayController log = gameLogOverlay;
            if (log != null) log.setEnabled(enabled);
            else {
                LauncherPreferences.setGameLogOverlayMinimized(activity, false);
                LauncherPreferences.setShowGameLogOverlay(activity, enabled);
            }
        });
        logRow.addView(choices, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView opacityLabel = new TextView(activity);
        opacityLabel.setText("Background opacity");
        opacityLabel.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        opacityLabel.setTextSize(12f);
        opacityLabel.setPadding(dp(8), 0, dp(4), 0);
        logRow.addView(opacityLabel);

        SeekBar opacity = new SeekBar(activity);
        opacity.setMax(90);
        int opacityPercent = LauncherPreferences.getGameLogOverlayOpacityPercent(activity);
        opacity.setProgress(opacityPercent - 10);
        LinearLayout.LayoutParams opacityParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        opacityParams.setMargins(0, 0, dp(4), 0);
        logRow.addView(opacity, opacityParams);

        TextView opacityValue = new TextView(activity);
        opacityValue.setText(opacityPercent + "%");
        opacityValue.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        opacityValue.setTextSize(12f);
        opacityValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        opacityValue.setMinWidth(dp(42));
        logRow.addView(opacityValue);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = progress + 10;
                opacityValue.setText(percent + "%");
                HorizonGameLogOverlayController log = gameLogOverlay;
                if (log != null) log.setOpacityPercent(percent);
                else LauncherPreferences.setGameLogOverlayOpacityPercent(activity, percent);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        content.addView(logRow);

        content.addView(buildInGameDialogAction(
                "Controller / gamepad overlay",
                "Edit controller mappings, cursor behavior, FPS, game log window, and floating button placement.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    GamepadMappingDialog.show(activity, () -> activity.runOnUiThread(() -> {
                        GamepadInputController controller = gamepad;
                        if (controller != null) controller.onMappingsChanged();
                        TouchControlsOverlay overlay = touchOverlay;
                        if (overlay != null) {
                            overlay.loadSelectedLayout();
                            overlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(activity));
                            overlay.refreshRuntimeVisibilityPreferences();
                            overlay.invalidate();
                            overlay.bringToFront();
                        }
                        FloatingGameSettingsOverlayController floating = floatingSettings;
                        if (floating != null) floating.refreshFromPreferences();
                        HorizonGameLogOverlayController log = gameLogOverlay;
                        if (log != null) log.configure();
                    }));
                }));

        content.addView(buildInGameTouchLayoutSelector(dialogRef));

        content.addView(buildInGameDialogAction(
                touchEnabled ? "Hide touch controls" : "Show touch controls",
                touchEnabled
                        ? "Temporarily hides the active touch layout without leaving the game."
                        : "Shows the selected touch layout again.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    boolean enabled = !ControlsPreferences.isTouchControlsEnabled(activity);
                    ControlsPreferences.setTouchControlsEnabled(activity, enabled);
                    TouchControlsOverlay overlay = touchOverlay;
                    if (overlay != null) {
                        overlay.setControlsVisible(enabled);
                        overlay.setAlpha(1f);
                        overlay.bringToFront();
                    }
                }));

        content.addView(buildInGameDialogAction(
                "Edit touch controls",
                "Open the touch layout editor for the current control layout.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    try {
                        InGameControlsEditorDialog editor = new InGameControlsEditorDialog(
                                activity, touchOverlay, () -> activity.runOnUiThread(() -> {
                                    TouchControlsOverlay overlay = touchOverlay;
                                    if (overlay != null) {
                                        overlay.loadSelectedLayout();
                                        overlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(activity));
                                        overlay.refreshRuntimeVisibilityPreferences();
                                        overlay.invalidate();
                                    }
                                    CallbackBridge.setInputReady(true);
                                    syncGrabStateFromEngine();
                                }));
                        editor.show();
                    } catch (Throwable throwable) {
                        Log.e(TAG, "Could not open in-game touch editor", throwable);
                        Toast.makeText(activity, "Unable to open touch controls editor.", Toast.LENGTH_SHORT).show();
                    }
                }));

        content.addView(buildInGameDialogAction(
                "Manage / import touch layouts",
                "Choose, import, or manage touch control layouts.",
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    try {
                        activity.startActivity(new Intent(activity, ControlsActivity.class));
                    } catch (Throwable throwable) {
                        Log.e(TAG, "Could not open touch layout manager", throwable);
                    }
                }));

        boolean recordingFinalizing = GameRecordingService.isFinalizing();
        boolean recordingActive = GameRecordingService.isRecordingOrStarting();
        String recordingAudioLabel = RecordingPreferences.getAudioModeLabel(activity);
        content.addView(buildInGameDialogAction(
                recordingFinalizing ? "Finishing recording..." : (recordingActive ? "Stop recording" : "Start recording"),
                recordingFinalizing
                        ? "DroidBridge is finalizing the MP4 and saving it to Movies/DroidBridge/Recordings."
                        : (recordingActive
                        ? "Finish the current recording and save it to Movies/DroidBridge/Recordings."
                        : "Record the current Minecraft display. Audio: " + recordingAudioLabel + "."),
                false,
                () -> {
                    dismissDialog(dialogRef[0]);
                    if (recordingFinalizing) {
                        Toast.makeText(activity, "The recording is already being finalized.", Toast.LENGTH_SHORT).show();
                    } else if (recordingActive) {
                        showStopGameRecordingDialog();
                    } else {
                        requestStartGameRecording();
                    }
                }));

        TextView recoveryTitle = new TextView(activity);
        recoveryTitle.setText("Recovery");
        recoveryTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        recoveryTitle.setTextSize(12f);
        recoveryTitle.setTypeface(Typeface.DEFAULT_BOLD);
        recoveryTitle.setPadding(dp(2), dp(8), 0, dp(6));
        content.addView(recoveryTitle);

        content.addView(buildInGameDialogAction(
                "Force close game",
                "Use this if Minecraft is frozen, crashed, or will not return to the launcher normally.",
                true,
                () -> {
                    dismissDialog(dialogRef[0]);
                    showForceCloseGameDialog();
                }));

        // Horizon-specific information stays last, directly underneath Force Close.
        TextView graphicsTitle = new TextView(activity);
        graphicsTitle.setText("Graphics driver");
        graphicsTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        graphicsTitle.setTextSize(12f);
        graphicsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        graphicsTitle.setPadding(dp(2), dp(8), 0, dp(6));
        content.addView(graphicsTitle);

        content.addView(buildInGameDialogAction(
                "Driver details",
                graphicsSummary(),
                false,
                () -> Toast.makeText(activity, graphicsSummary(), Toast.LENGTH_LONG).show()));

        AlertDialog dialog = LauncherDialogStyle.builder(activity)
                .setView(scrollView)
                .create();
        dialogRef[0] = dialog;
        dialog.setOnDismissListener(ignored -> {
            syncGrabStateFromEngine();
            FrameLayout panel = root;
            if (panel != null) panel.postDelayed(this::syncGrabStateFromEngine, 16L);
            HorizonGameLogOverlayController log = gameLogOverlay;
            if (log != null) log.bringToFront();
            FloatingGameSettingsOverlayController floating = floatingSettings;
            if (floating != null) floating.bringToFront();
        });
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(activity, dialog);
    }

    @NonNull
    private View buildInGameTouchLayoutSelector(@NonNull AlertDialog[] dialogRef) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG));

        TextView title = new TextView(activity);
        title.setText("Touch controls profile");
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView summary = new TextView(activity);
        summary.setText("Switch the active touch-control profile. A selection is applied immediately and closes this dialog.");
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setTextSize(12.5f);
        summary.setPadding(0, dp(3), 0, dp(6));
        card.addView(summary);

        List<File> layouts = TouchControlsStore.listLayouts(activity);
        ArrayList<File> touchLayouts = new ArrayList<>(layouts);
        ArrayList<String> labels = new ArrayList<>();
        String selectedPath = ControlsPreferences.getSelectedLayoutPath(activity);
        int selectedIndex = 0;
        for (int i = 0; i < touchLayouts.size(); i++) {
            File file = touchLayouts.get(i);
            TouchControlsLayoutData data = TouchControlsStore.loadLayout(file);
            String name = data != null && data.name != null && !data.name.trim().isEmpty()
                    ? data.name.trim() : file.getName();
            labels.add(name + "  •  " + file.getName());
            if (selectedPath != null && file.getAbsolutePath().equals(selectedPath)) selectedIndex = i;
        }
        if (touchLayouts.isEmpty()) {
            File fallback = TouchControlsStore.getDefaultLayoutFile(activity);
            touchLayouts.add(fallback);
            labels.add("Default Touch Controls  •  " + fallback.getName());
            selectedIndex = 0;
        }

        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleTouchLayoutSpinnerRow(view, false);
                return view;
            }
            @Override public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleTouchLayoutSpinnerRow(view, true);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int safeIndex = Math.max(0, Math.min(selectedIndex, touchLayouts.size() - 1));
        spinner.setSelection(safeIndex, false);
        final String initialPath = touchLayouts.get(safeIndex).getAbsolutePath();
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= touchLayouts.size()) return;
                File selected = touchLayouts.get(position);
                String nextPath = selected.getAbsolutePath();
                String current = ControlsPreferences.getSelectedLayoutPath(activity);
                String effective = current == null ? initialPath : current;
                if (nextPath.equals(effective)) return;
                ControlsPreferences.setSelectedLayoutPath(activity, nextPath);
                TouchControlsOverlay overlay = touchOverlay;
                if (overlay != null) {
                    overlay.loadSelectedLayout();
                    overlay.applyVirtualMouseLaunchSessionState();
                    overlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(activity));
                    overlay.refreshRuntimeVisibilityPreferences();
                    overlay.invalidate();
                    overlay.bringToFront();
                }
                LauncherLogManager.append("DroidBridgeControls: switched Horizon touch profile -> " + selected.getName());
                dismissDialog(dialogRef[0]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        card.addView(spinner);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(params);
        return card;
    }

    private void styleTouchLayoutSpinnerRow(@NonNull View view, boolean dropdown) {
        view.setBackgroundColor(dropdown ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED : Color.TRANSPARENT);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
            text.setTextSize(15f);
            text.setSingleLine(false);
            text.setPadding(text.getPaddingLeft(), dp(8), text.getPaddingRight(), dp(8));
        }
    }

    @NonNull
    private View buildInGameDialogAction(
            @NonNull String title,
            @NonNull String summary,
            boolean destructive,
            @NonNull Runnable action
    ) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> action.run());
        card.setOnTouchListener((view, event) -> {
            int actionMasked = event.getActionMasked();
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                view.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG_PRESSED));
            } else if (actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL) {
                view.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG));
            }
            return false;
        });

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(destructive
                ? LauncherDialogStyle.COLOR_ERROR
                : LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        titleView.setTextSize(16f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView);

        TextView summaryView = new TextView(activity);
        summaryView.setText(summary);
        summaryView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summaryView.setTextSize(12.5f);
        summaryView.setPadding(0, dp(3), 0, 0);
        card.addView(summaryView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(params);
        return card;
    }

    @NonNull
    private GradientDrawable roundedDialogDrawable(int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(Math.max(1, dp(1)), LauncherDialogStyle.COLOR_CARD_STROKE);
        return drawable;
    }

    private static void dismissDialog(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    /** What the engine is drawing with: DroidBridge selection plus wgpu's real adapter/backend. */
    @NonNull
    private String graphicsSummary() {
        int renderer = HorizonInput.rendererState();
        String mode;
        switch (renderer) {
            case 2: mode = "Vulkan (GPU batching)"; break;
            case 3: mode = "OpenGL ES"; break;
            case 4: mode = "OpenGL ES (Vulkan failed last time)"; break;
            case 1: mode = "Vulkan"; break;
            default: mode = "Starting"; break;
        }

        String selectedDriver = "";
        if (renderer == 1 || renderer == 2) {
            int driver = HorizonInput.vulkanDriverState();
            if (driver == 1 && vulkanDriverName != null && !vulkanDriverName.trim().isEmpty()) {
                selectedDriver = vulkanDriverName.trim();
            } else if (driver == 2 && vulkanDriverName != null && !vulkanDriverName.trim().isEmpty()) {
                selectedDriver = "System driver (" + vulkanDriverName.trim() + " did not load)";
            } else {
                selectedDriver = "System Vulkan driver";
            }
        }

        String actual = HorizonInput.graphicsInfo();
        actual = actual == null ? "" : actual.trim();

        StringBuilder summary = new StringBuilder(mode);
        if (!selectedDriver.isEmpty()) summary.append(" · ").append(selectedDriver);
        if (!actual.isEmpty()) summary.append("\n").append(actual);
        return summary.toString();
    }

    private void requestStartGameRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(activity, "Minecraft screen and audio recording requires Android 10 or newer.", Toast.LENGTH_LONG).show();
            return;
        }
        if (GameRecordingService.isRecordingOrStarting()) {
            Toast.makeText(activity, "A DroidBridge recording is already active.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_GAME_RECORDING_AUDIO_PERMISSION);
            return;
        }
        requestMediaProjectionForGameRecording();
    }

    private void requestMediaProjectionForGameRecording() {
        Object service = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (!(service instanceof MediaProjectionManager)) {
            Toast.makeText(activity, "Android screen capture is unavailable on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            activity.startActivityForResult(((MediaProjectionManager) service).createScreenCaptureIntent(), REQUEST_GAME_RECORDING_CAPTURE);
        } catch (Throwable throwable) {
            Log.e(TAG, "Unable to request screen-capture permission", throwable);
            Toast.makeText(activity, "Unable to open Android's screen-capture permission.", Toast.LENGTH_LONG).show();
        }
    }

    private void startApprovedGameRecording(@NonNull Intent projectionData) {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
        if (display != null) display.getRealMetrics(metrics);
        else metrics.setTo(activity.getResources().getDisplayMetrics());
        int width = Math.max(2, metrics.widthPixels);
        int height = Math.max(2, metrics.heightPixels);
        if ((width & 1) != 0) width--;
        if ((height & 1) != 0) height--;
        GameRecordingService.start(activity, Activity.RESULT_OK, projectionData,
                width, height, Math.max(1, metrics.densityDpi));
        Toast.makeText(activity, "Starting Minecraft recording...", Toast.LENGTH_SHORT).show();
    }

    private void showStopGameRecordingDialog() {
        if (!GameRecordingService.isRecordingOrStarting()) {
            Toast.makeText(activity, "The recording has already stopped.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (GameRecordingService.isFinalizing()) {
            Toast.makeText(activity, "The recording is already being finalized.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = LauncherDialogStyle.builder(activity)
                .setTitle("Stop recording?")
                .setMessage("Are you sure you want to stop the current recording? DroidBridge will finalize the MP4 and save it to Movies/DroidBridge/Recordings.")
                .setNegativeButton("Keep recording", null)
                .setPositiveButton("Stop recording", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(LauncherDialogStyle.COLOR_ERROR);
                positive.setOnClickListener(v -> {
                    dialog.dismiss();
                    GameRecordingService.stop(activity);
                    Toast.makeText(activity, "Finishing recording...", Toast.LENGTH_SHORT).show();
                });
            }
        });
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(activity, dialog);
    }

    private void showForceCloseGameDialog() {
        AlertDialog dialog = LauncherDialogStyle.builder(activity)
                .setTitle("Force close game?")
                .setMessage("This should only be used when Minecraft is frozen, crashed, or will not close normally. DroidBridge will preserve the latest log when log history is enabled, finalize any active recording, and close the game.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Force close", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setTextColor(LauncherDialogStyle.COLOR_ERROR);
                positive.setOnClickListener(v -> {
                    dialog.dismiss();
                    forceCloseGameAndReturnToLauncher();
                });
            }
        });
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(activity, dialog);
    }

    private void forceCloseGameAndReturnToLauncher() {
        if (destroyed) return;
        try {
            LauncherLogManager.append("ForceClose: User requested force close from Horizon in-game controls");
            String logLabel = gameDirectory == null ? "horizon" : gameDirectory.getName();
            LauncherLogManager.preserveLatestLogIfEnabled(activity, logLabel == null || logLabel.trim().isEmpty() ? "horizon" : logLabel);
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not preserve Horizon latest log during force close", throwable);
        }
        if (GameRecordingService.isRecordingOrStarting()) GameRecordingService.stop(activity);
        try {
            Intent launcher = new Intent(activity, DroidBridgeLaunchActivity.class);
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(launcher);
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not reopen DroidBridge after Horizon force close", throwable);
        }
        activity.finishAndRemoveTask();
        scheduleHorizonProcessKillAfterRecordingFinalizes();
    }

    private void scheduleHorizonProcessKillAfterRecordingFinalizes() {
        Handler handler = new Handler(Looper.getMainLooper());
        long earliest = SystemClock.elapsedRealtime() + 250L;
        long deadline = SystemClock.elapsedRealtime() + 15000L;
        final Runnable[] check = new Runnable[1];
        check[0] = () -> {
            long now = SystemClock.elapsedRealtime();
            if (now < earliest) {
                handler.postDelayed(check[0], Math.min(150L, earliest - now));
                return;
            }
            if (GameRecordingService.isRecordingOrStarting() && now < deadline) {
                handler.postDelayed(check[0], 100L);
                return;
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        };
        handler.postDelayed(check[0], 250L);
    }

    /** Called by HorizonActivity for the recording MediaProjection result. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_GAME_RECORDING_CAPTURE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(activity, "Screen recording was cancelled.", Toast.LENGTH_SHORT).show();
            return true;
        }
        startApprovedGameRecording(data);
        return true;
    }

    /** Called by HorizonActivity for the recording audio permission result. */
    public boolean onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        if (requestCode != REQUEST_GAME_RECORDING_AUDIO_PERMISSION) return false;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(activity, "DroidBridge needs audio permission for the selected recording audio mode.", Toast.LENGTH_LONG).show();
        } else {
            requestMediaProjectionForGameRecording();
        }
        return true;
    }

    /** Controller buttons are owned exclusively by DroidBridge while host controls are active. */
    /**
     * HorizonActivity: a key the engine's own input left unhandled. Controller keys go to
     * DroidBridge's mapper; a physical keyboard's keys go on to the engine the way the Java
     * engine sends them. The engine only knows a keyboard by its Android source bits, and some
     * keyboards report controller or d-pad bits on every key, so the engine passed them back
     * here and the keyboard did nothing in game.
     */
    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        if (destroyed) return false;
        syncGrabStateFromEngine();
        GamepadInputController controller = gamepad;
        if (controller != null && controller.handleKeyEvent(event)) {
            noteControllerKey(event);
            return true;
        }
        if (forwardPhysicalKeyboardKey(event)) return true;
        if (isControllerKey(event)) {
            noteControllerKey(event);
            return true;
        }
        return false;
    }

    /** The controls window's own key path (it holds focus only while Android's IME is up). */
    private boolean handleControllerKeyEvent(@NonNull KeyEvent event) {
        if (destroyed) return false;
        syncGrabStateFromEngine();
        GamepadInputController controller = gamepad;
        if (controller != null && controller.handleKeyEvent(event)) {
            noteControllerKey(event);
            return true;
        }
        if (isControllerKey(event)) {
            noteControllerKey(event);
            return true;
        }
        return false;
    }

    private void noteControllerKey(@NonNull KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && isControllerKey(event)) {
            switchAwayFromMouse("controller");
        }
    }

    /** A real move of a controller's stick, trigger or D-pad hat (not resting noise). */
    private static boolean isControllerMovement(@NonNull MotionEvent event) {
        if (!isControllerSource(event.getSource())) return false;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        int[] sticks = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        for (int axis : sticks) {
            if (Math.abs(event.getAxisValue(axis)) > 0.45f) return true;
        }
        return event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.6f
                || event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.6f
                || event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.6f
                || event.getAxisValue(MotionEvent.AXIS_GAS) > 0.6f;
    }

    private static boolean isFingerTouch(@NonNull MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN) {
            return false;
        }
        int tool = event.getToolType(event.getActionIndex());
        return tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN;
    }

    /**
     * The player touched the screen or used a controller while the real mouse was active: hand
     * the cursor back to DroidBridge (the engine stops capturing the mouse and stops moving its
     * own menu cursor). Moving the mouse again takes it back, from where the cursor is then.
     */
    private void switchAwayFromMouse(@NonNull String why) {
        if (!mouseActive || destroyed) return;
        mouseActive = false;
        LauncherLogManager.append("DroidBridgePhysicalMouse: " + why
                + " used; the Rust engine stops capturing the mouse until it moves again");
        updateEngineMouseCapture();
    }

    private boolean keyboardForwardLogged;

    private boolean forwardPhysicalKeyboardKey(@NonNull KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                break;
        }
        MinecraftGLSurface surface = inputSurface;
        if (surface == null || !surface.sendHostPhysicalKeyboardEvent(event)) return false;
        if (!keyboardForwardLogged) {
            keyboardForwardLogged = true;
            InputDevice device = event.getDevice();
            LauncherLogManager.append("DroidBridgeKeyboard: sending physical keys to the Rust engine"
                    + " through DroidBridge (source=0x" + Integer.toHexString(event.getSource())
                    + " device=" + (device == null ? "?" : device.getName())
                    + " keyboardType=" + (device == null ? -1 : device.getKeyboardType()) + ")");
        }
        return true;
    }

    /**
     * A key only a controller sends. A physical keyboard with arrow keys reports
     * SOURCE_KEYBOARD | SOURCE_DPAD on every key, so the DPAD bit alone would swallow its
     * letters: those are keyboard keys unless they are the arrow/centre keys.
     */
    private static boolean isControllerKey(@NonNull KeyEvent event) {
        int source = event.getSource();
        if (!isControllerSource(source)) return false;
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
        if ((source & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) return true;
        InputDevice device = event.getDevice();
        boolean fullKeyboard = device == null
                || device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
        if (!fullKeyboard) return true;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return false;
        }
    }

    /** Controller sticks/triggers are also owned exclusively by DroidBridge. */
    public boolean handleMotionEvent(@NonNull MotionEvent event) {
        if (destroyed) return false;
        syncGrabStateFromEngine();
        if (isControllerMovement(event)) switchAwayFromMouse("controller");
        GamepadInputController controller = gamepad;
        if (controller != null && controller.handleMotionEvent(event)) return true;
        return isControllerSource(event.getSource());
    }

    private static boolean isControllerSource(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** After the controls editor or mapper: pick up the saved layout and mappings. */
    public void onResume() {
        if (destroyed) return;
        TouchControlsOverlay overlay = touchOverlay;
        if (overlay != null) {
            overlay.loadSelectedLayout();
            overlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(activity));
            overlay.refreshRuntimeVisibilityPreferences();
            overlay.invalidate();
        }
        HorizonGameLogOverlayController log = gameLogOverlay;
        if (log != null) log.configure();
        GamepadInputController controller = gamepad;
        if (controller != null) controller.onMappingsChanged();
        GyroInputController gyroscope = gyro;
        if (gyroscope != null) gyroscope.refreshFromPreferences();
        lastMouseScaleCheckUptimeMs = 0L;
        HorizonInput.setKeyRepeat(LauncherPreferences.isPhysicalKeyRepeatEnabled(activity));
        FloatingGameSettingsOverlayController floating = floatingSettings;
        if (floating != null) {
            // Picks up cog/FPS settings changed in the launcher, and restarts the FPS poll.
            floating.resume();
            floating.bringToFront();
        }
    }

    /** Stops the FPS poll while the game is in the background. */
    public void onPause() {
        GyroInputController gyroscope = gyro;
        if (gyroscope != null) gyroscope.stop();
        FloatingGameSettingsOverlayController floating = floatingSettings;
        if (floating != null) floating.pause();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        Choreographer.getInstance().removeFrameCallback(grabPoll);
        HorizonRecordingFrames frames = recordingFrames;
        recordingFrames = null;
        if (frames != null) {
            try { frames.unregister(); } catch (Throwable ignored) { }
        }
        CallbackBridge.setHostInputSink(null);
        DroidBridgeSdlFpsBridge.setHostFpsSource(null);
        HorizonGameLogOverlayController log = gameLogOverlay;
        gameLogOverlay = null;
        if (log != null) {
            try { log.destroy(); } catch (Throwable ignored) { }
        }
        FloatingGameSettingsOverlayController floating = floatingSettings;
        floatingSettings = null;
        if (floating != null) {
            try {
                floating.detach();
            } catch (Throwable ignored) {
            }
        }
        GyroInputController gyroscope = gyro;
        gyro = null;
        if (gyroscope != null) {
            try {
                gyroscope.stop();
            } catch (Throwable ignored) {
            }
        }
        GamepadInputController controller = gamepad;
        gamepad = null;
        if (controller != null) {
            try {
                controller.removeSelf();
            } catch (Throwable ignored) {
            }
        }
        GameImeViewportController.unregisterImeViewportTarget(imeViewportTarget);
        imeViewportBottomInset = 0;
        try {
            HorizonInput.setImeViewportBottomInset(0);
        } catch (Throwable ignored) {
        }
        FrameLayout panel = root;
        if (panel != null) {
            try {
                TouchKeyboardHelper.unregisterSingleScreenImeHost(panel);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        if (panel != null) {
            try {
                windowManager.removeViewImmediate(panel);
            } catch (Throwable ignored) {
            }
        }
        View decor = engineDecor;
        engineDecor = null;
        settingsButton = null;
        if (decor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (captureRequestedHere && decor.hasPointerCapture()) decor.releasePointerCapture();
            } catch (Throwable ignored) {
            }
        }
        touchOverlay = null;
        cursorOverlay = null;
        inputSurface = null;
    }
}
