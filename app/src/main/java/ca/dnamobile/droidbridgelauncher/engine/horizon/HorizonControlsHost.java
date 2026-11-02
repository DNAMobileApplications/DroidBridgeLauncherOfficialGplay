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
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
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
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSdlFpsBridge;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.settings.GameOverlayPreferences;
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
    @Nullable private FloatingGameSettingsOverlayController floatingSettings;
    @Nullable private HorizonGameLogOverlayController gameLogOverlay;
    private int imeViewportBottomInset;
    private final GameImeViewportController.ImeViewportTarget imeViewportTarget =
            this::setEngineImeViewportBottomInset;
    private boolean destroyed;
    private boolean lastGrabbed;
    private int lastCursorShape = -1;
    private boolean driverProblemShown;

    private final Choreographer.FrameCallback grabPoll = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (destroyed) return;
            syncGrabStateFromEngine();
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
                        + " did not load; Horizon is using the system Vulkan driver.",
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
    }

    /** Adds the controls once the engine's window exists. Call from Activity.onCreate. */
    public void attach() {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windowManager.addView(panel, params);

        root = panel;
        panelParams = params;
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

        gamepad = new GamepadInputController(panel);
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
            if (HorizonControlsHost.this.handleKeyEvent(event)) return true;
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (HorizonControlsHost.this.handleMotionEvent(event)) return true;
            return super.dispatchGenericMotionEvent(event);
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
                .setMessage("This should only be used when Minecraft is frozen, crashed, or will not close normally. DroidBridge will preserve the latest log when log history is enabled, finalize any active recording, and close the Horizon game process.")
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
    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        if (destroyed) return false;
        syncGrabStateFromEngine();
        GamepadInputController controller = gamepad;
        if (controller != null && controller.handleKeyEvent(event)) return true;
        return isControllerSource(event.getSource());
    }

    /** Controller sticks/triggers are also owned exclusively by DroidBridge. */
    public boolean handleMotionEvent(@NonNull MotionEvent event) {
        if (destroyed) return false;
        syncGrabStateFromEngine();
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
        FloatingGameSettingsOverlayController floating = floatingSettings;
        if (floating != null) {
            // Picks up cog/FPS settings changed in the launcher, and restarts the FPS poll.
            floating.resume();
            floating.bringToFront();
        }
    }

    /** Stops the FPS poll while the game is in the background. */
    public void onPause() {
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
        touchOverlay = null;
        cursorOverlay = null;
        inputSurface = null;
    }
}
