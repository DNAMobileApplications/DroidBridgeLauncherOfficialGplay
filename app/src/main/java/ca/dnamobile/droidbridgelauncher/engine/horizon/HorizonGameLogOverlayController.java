/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Horizon equivalent of GameActivity's movable/resizable live latestlog window.
 * It intentionally uses the same LauncherPreferences keys so the Java and Rust
 * engines share visibility, opacity, size and position.
 */
final class HorizonGameLogOverlayController {
    private static final String TAG = "HorizonGameLog";

    @NonNull private final android.app.Activity activity;
    @NonNull private final FrameLayout root;
    @NonNull private final LinearLayout window;
    @NonNull private final TextView title;
    @NonNull private final ImageButton pausePlay;
    @NonNull private final TextView minimize;
    @NonNull private final TextView close;
    @NonNull private final ScrollView scroll;
    @NonNull private final TextView text;
    @NonNull private final TextView resizeHandle;
    @NonNull private final ImageButton restore;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean paused;
    private boolean minimized;
    private boolean destroyed;
    private long lastLength = -1L;
    private long lastModified = -1L;

    @NonNull private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            refreshLog();
            if (isWindowVisible()) handler.postDelayed(this, 500L);
        }
    };

    HorizonGameLogOverlayController(
            @NonNull android.app.Activity activity,
            @NonNull FrameLayout root
    ) {
        this.activity = activity;
        this.root = root;

        window = new LinearLayout(activity);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setPadding(dp(8), dp(8), dp(8), dp(8));
        window.setClickable(true);
        window.setFocusable(false);
        FrameLayout.LayoutParams windowParams = new FrameLayout.LayoutParams(dp(360), dp(420), Gravity.START | Gravity.TOP);
        root.addView(window, windowParams);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.bg_game_log_header);
        window.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        title = new TextView(activity);
        title.setText(activity.getString(R.string.game_log_overlay_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(11f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(10), 0, dp(6), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        pausePlay = new ImageButton(activity);
        pausePlay.setBackgroundColor(Color.TRANSPARENT);
        pausePlay.setImageResource(R.drawable.ic_pause_24);
        pausePlay.setPadding(dp(10), dp(10), dp(10), dp(10));
        header.addView(pausePlay, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT));

        minimize = headerButton("−", 20f);
        minimize.setContentDescription(activity.getString(R.string.game_log_minimize));
        header.addView(minimize, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT));

        close = headerButton("×", 22f);
        close.setContentDescription(activity.getString(R.string.game_log_hide));
        header.addView(close, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(4);
        window.addView(scroll, scrollParams);

        text = new TextView(activity);
        text.setText("Waiting for log output...");
        text.setTextColor(Color.WHITE);
        text.setTextSize(10f);
        text.setTypeface(Typeface.MONOSPACE);
        text.setIncludeFontPadding(false);
        scroll.addView(text, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        resizeHandle = new TextView(activity);
        resizeHandle.setText(activity.getString(R.string.game_log_resize));
        resizeHandle.setTextColor(0xCCFFFFFF);
        resizeHandle.setTextSize(10f);
        resizeHandle.setTypeface(Typeface.DEFAULT_BOLD);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setMinWidth(dp(76));
        resizeHandle.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams resizeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        resizeParams.gravity = Gravity.END;
        window.addView(resizeHandle, resizeParams);

        restore = new ImageButton(activity);
        restore.setBackgroundResource(R.drawable.bg_ingame_settings_button);
        restore.setImageResource(R.drawable.ic_game_log_paper_24);
        restore.setPadding(dp(10), dp(10), dp(10), dp(10));
        restore.setContentDescription(activity.getString(R.string.game_log_overlay_restore));
        root.addView(restore, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.TOP));

        pausePlay.setOnClickListener(v -> setPaused(!paused));
        minimize.setOnClickListener(v -> minimize());
        close.setOnClickListener(v -> hide());
        restore.setOnClickListener(v -> restore());
        installGestures();
        configure();
    }

    private TextView headerButton(@NonNull String label, float sp) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        return view;
    }

    void configure() {
        if (destroyed) return;
        handler.removeCallbacks(ticker);
        boolean enabled = LauncherPreferences.isShowGameLogOverlay(activity);
        minimized = enabled && LauncherPreferences.isGameLogOverlayMinimized(activity);
        window.setVisibility(enabled && !minimized ? View.VISIBLE : View.GONE);
        restore.setVisibility(enabled && minimized ? View.VISIBLE : View.GONE);
        applyOpacity();
        if (!enabled) return;

        if (minimized) {
            root.post(this::applySavedRestorePosition);
            restore.bringToFront();
        } else {
            root.post(this::applySavedWindowBounds);
            window.bringToFront();
            lastLength = -1L;
            lastModified = -1L;
            handler.post(ticker);
        }
    }

    void setEnabled(boolean enabled) {
        LauncherPreferences.setShowGameLogOverlay(activity, enabled);
        LauncherPreferences.setGameLogOverlayMinimized(activity, false);
        paused = false;
        configure();
    }

    void setOpacityPercent(int percent) {
        LauncherPreferences.setGameLogOverlayOpacityPercent(activity, percent);
        applyOpacity();
    }

    private void setPaused(boolean next) {
        paused = next;
        pausePlay.setImageResource(paused ? R.drawable.ic_play_arrow_24 : R.drawable.ic_pause_24);
        pausePlay.setContentDescription(activity.getString(paused
                ? R.string.game_log_overlay_resume : R.string.game_log_overlay_pause));
        if (!paused) {
            lastLength = -1L;
            lastModified = -1L;
            refreshLog();
        }
    }

    private void hide() {
        if (window.getVisibility() == View.VISIBLE) persistWindowBounds();
        LauncherPreferences.setShowGameLogOverlay(activity, false);
        LauncherPreferences.setGameLogOverlayMinimized(activity, false);
        minimized = false;
        paused = false;
        configure();
    }

    private void minimize() {
        if (!isWindowVisible()) return;
        persistWindowBounds();
        minimized = true;
        LauncherPreferences.setGameLogOverlayMinimized(activity, true);
        configure();
    }

    private void restore() {
        if (!LauncherPreferences.isShowGameLogOverlay(activity)) return;
        persistRestorePosition();
        minimized = false;
        LauncherPreferences.setGameLogOverlayMinimized(activity, false);
        configure();
    }

    void bringToFront() {
        if (isWindowVisible()) window.bringToFront();
        if (restore.getVisibility() == View.VISIBLE) restore.bringToFront();
    }

    private boolean isWindowVisible() {
        return window.getVisibility() == View.VISIBLE;
    }

    private void applyOpacity() {
        int percent = LauncherPreferences.getGameLogOverlayOpacityPercent(activity);
        int alpha = Math.round(255f * (percent / 100f));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(alpha, 0, 0, 0));
        background.setCornerRadius(dp(18));
        int borderAlpha = Math.max(36, Math.round(88f * (percent / 100f)));
        background.setStroke(Math.max(1, dp(1)), Color.argb(borderAlpha, 255, 255, 255));
        window.setBackground(background);
        window.setAlpha(1f);
        restore.setAlpha(1f);
    }

    private void applySavedWindowBounds() {
        if (destroyed || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        int rootWidth = root.getWidth();
        int rootHeight = root.getHeight();
        int minWidth = dp(220);
        int minHeight = dp(180);
        int defaultWidth = Math.min(dp(420), Math.max(dp(280), Math.round(rootWidth * 0.38f)));
        int defaultHeight = Math.min(rootHeight - dp(24), Math.max(dp(260), Math.round(rootHeight * 0.55f)));
        int savedWidth = LauncherPreferences.getGameLogOverlayWidth(activity);
        int savedHeight = LauncherPreferences.getGameLogOverlayHeight(activity);
        int width = savedWidth > 0 ? savedWidth : defaultWidth;
        int height = savedHeight > 0 ? savedHeight : defaultHeight;
        width = Math.max(minWidth, Math.min(width, rootWidth));
        height = Math.max(minHeight, Math.min(height, rootHeight));
        ViewGroup.LayoutParams params = window.getLayoutParams();
        params.width = width;
        params.height = height;
        window.setLayoutParams(params);
        int savedX = LauncherPreferences.getGameLogOverlayX(activity);
        int savedY = LauncherPreferences.getGameLogOverlayY(activity);
        window.setX(clamp(savedX >= 0 ? savedX : dp(8), 0f, Math.max(0f, rootWidth - width)));
        window.setY(clamp(savedY >= 0 ? savedY : dp(56), 0f, Math.max(0f, rootHeight - height)));
    }

    private void applySavedRestorePosition() {
        if (destroyed || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        int width = restore.getWidth() > 0 ? restore.getWidth() : dp(48);
        int height = restore.getHeight() > 0 ? restore.getHeight() : dp(48);
        int savedX = LauncherPreferences.getGameLogOverlayX(activity);
        int savedY = LauncherPreferences.getGameLogOverlayY(activity);
        restore.setX(clamp(savedX >= 0 ? savedX : dp(8), 0f, Math.max(0f, root.getWidth() - width)));
        restore.setY(clamp(savedY >= 0 ? savedY : dp(56), 0f, Math.max(0f, root.getHeight() - height)));
    }

    private void persistWindowBounds() {
        LauncherPreferences.setGameLogOverlayBounds(activity,
                Math.round(window.getX()), Math.round(window.getY()),
                Math.max(1, window.getWidth()), Math.max(1, window.getHeight()));
    }

    private void persistRestorePosition() {
        int width = LauncherPreferences.getGameLogOverlayWidth(activity);
        int height = LauncherPreferences.getGameLogOverlayHeight(activity);
        if (width <= 0) width = Math.max(1, window.getWidth());
        if (height <= 0) height = Math.max(1, window.getHeight());
        LauncherPreferences.setGameLogOverlayBounds(activity,
                Math.round(restore.getX()), Math.round(restore.getY()), width, height);
    }

    private void installGestures() {
        final float[] drag = new float[4];
        title.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag[0] = event.getRawX(); drag[1] = event.getRawY();
                    drag[2] = window.getX(); drag[3] = window.getY();
                    window.bringToFront(); return true;
                case MotionEvent.ACTION_MOVE:
                    window.setX(clamp(drag[2] + event.getRawX() - drag[0], 0f,
                            Math.max(0f, root.getWidth() - window.getWidth())));
                    window.setY(clamp(drag[3] + event.getRawY() - drag[1], 0f,
                            Math.max(0f, root.getHeight() - window.getHeight())));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    persistWindowBounds(); view.performClick(); return true;
                default: return false;
            }
        });

        final float[] resize = new float[4];
        resizeHandle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resize[0] = event.getRawX(); resize[1] = event.getRawY();
                    resize[2] = window.getWidth(); resize[3] = window.getHeight();
                    window.bringToFront(); return true;
                case MotionEvent.ACTION_MOVE:
                    int minWidth = dp(220), minHeight = dp(180);
                    int maxWidth = Math.max(minWidth, root.getWidth() - Math.round(window.getX()));
                    int maxHeight = Math.max(minHeight, root.getHeight() - Math.round(window.getY()));
                    int width = Math.max(minWidth, Math.min(Math.round(resize[2] + event.getRawX() - resize[0]), maxWidth));
                    int height = Math.max(minHeight, Math.min(Math.round(resize[3] + event.getRawY() - resize[1]), maxHeight));
                    ViewGroup.LayoutParams params = window.getLayoutParams();
                    params.width = width; params.height = height; window.setLayoutParams(params);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    persistWindowBounds(); view.performClick(); return true;
                default: return false;
            }
        });

        final float[] restoreDrag = new float[4];
        final boolean[] moved = new boolean[1];
        restore.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    restoreDrag[0] = event.getRawX(); restoreDrag[1] = event.getRawY();
                    restoreDrag[2] = view.getX(); restoreDrag[3] = view.getY();
                    moved[0] = false; view.bringToFront(); return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - restoreDrag[0], dy = event.getRawY() - restoreDrag[1];
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved[0] = true;
                    int width = view.getWidth() > 0 ? view.getWidth() : dp(48);
                    int height = view.getHeight() > 0 ? view.getHeight() : dp(48);
                    view.setX(clamp(restoreDrag[2] + dx, 0f, Math.max(0f, root.getWidth() - width)));
                    view.setY(clamp(restoreDrag[3] + dy, 0f, Math.max(0f, root.getHeight() - height)));
                    return true;
                case MotionEvent.ACTION_UP:
                    persistRestorePosition(); if (!moved[0]) view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL:
                    persistRestorePosition(); return true;
                default: return false;
            }
        });
    }

    private void refreshLog() {
        if (!isWindowVisible() || paused || destroyed) return;
        File file = readableLatestLogFile();
        if (file == null || !file.isFile()) {
            text.setText("Waiting for latestlog.txt...");
            return;
        }
        long length = file.length();
        long modified = file.lastModified();
        if (length == lastLength && modified == lastModified) return;
        lastLength = length;
        lastModified = modified;
        try {
            String tail = readTail(file, 96 * 1024);
            if (!tail.trim().isEmpty()) text.setText(tail);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to refresh Horizon game log overlay", throwable);
        }
    }

    @Nullable private File readableLatestLogFile() {
        File file = Logger.getCurrentLogFile();
        if (file != null && file.isFile()) return file;
        try {
            file = LauncherLogManager.resolveLatestLogFile(activity);
            return file.isFile() ? file : null;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to resolve latestlog.txt", throwable);
            return null;
        }
    }

    @NonNull private static String readTail(@NonNull File file, int maxBytes) throws Exception {
        long length = file.length();
        long start = Math.max(0L, length - maxBytes);
        int count = (int) Math.max(0L, length - start);
        byte[] buffer = new byte[count];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(buffer);
        }
        String value = new String(buffer, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        if (start > 0) {
            int first = value.indexOf('\n');
            if (first >= 0 && first + 1 < value.length()) value = "...\n" + value.substring(first + 1);
        }
        String[] lines = value.split("\n", -1);
        if (lines.length <= 280) return value;
        StringBuilder out = new StringBuilder("...\n");
        for (int i = lines.length - 280; i < lines.length; i++) {
            out.append(lines[i]);
            if (i + 1 < lines.length) out.append('\n');
        }
        return out.toString();
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (window.getParent() == root) root.removeView(window);
        if (restore.getParent() == root) root.removeView(restore);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
