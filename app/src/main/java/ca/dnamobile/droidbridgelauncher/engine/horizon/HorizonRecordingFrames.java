/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.recording.PrimaryRecordingFramePump;
import ca.dnamobile.droidbridgelauncher.recording.RecordingFrameBridge;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

/**
 * "Hide touch controls in recordings" for the Rust engine.
 *
 * <p>With that setting on, the recorder (in the :recording process) asks the game's process for
 * the game picture alone through {@link RecordingFrameBridge}. The Java engine answers from
 * GameActivity; this is the same answer for the Rust engine's :horizon process. Without it the
 * request went unanswered, so the recorder fell back to recording the whole screen, controls
 * included.
 *
 * <p>The engine draws into its own activity window, and DroidBridge's controls, cog, FPS counter
 * and log sit in a separate panel window over it. A {@link PixelCopy} of the engine's window is
 * therefore the game picture without any of them, while the player still sees and uses the
 * controls as normal.
 *
 * <p>Before answering yes, one test copy must succeed; otherwise the recorder is told no and
 * records the screen as before, rather than writing a black video.
 */
final class HorizonRecordingFrames {
    private static final String TAG = "DroidBridgeHorizonRec";

    @NonNull private final Activity activity;
    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean registered;
    @Nullable private PrimaryRecordingFramePump pump;
    private long pumpSessionId;
    /** The attach request currently being test-copied, so a detach can cancel it. */
    private long pendingSessionId;

    private final PrimaryRecordingFramePump.FrameSource windowSource =
            new PrimaryRecordingFramePump.FrameSource() {
                @Override
                public boolean isRecordingFrameCaptureReady() {
                    View decor = decor();
                    return decor != null
                            && decor.isAttachedToWindow()
                            && decor.getWidth() > 1
                            && decor.getHeight() > 1;
                }

                @Override
                public int getRecordingFrameWidth() {
                    View decor = decor();
                    return decor == null ? 0 : Math.max(0, decor.getWidth());
                }

                @Override
                public int getRecordingFrameHeight() {
                    View decor = decor();
                    return decor == null ? 0 : Math.max(0, decor.getHeight());
                }

                @Override
                public boolean requestRecordingFrame(
                        @NonNull Bitmap bitmap,
                        @NonNull Handler callbackHandler,
                        @NonNull MinecraftGLSurface.RecordingFrameCallback callback
                ) {
                    return copyWindow(bitmap, callbackHandler, callback);
                }
            };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            long sessionId = intent.getLongExtra(RecordingFrameBridge.EXTRA_SESSION, Long.MIN_VALUE);
            if (RecordingFrameBridge.ACTION_DETACH.equals(action)) {
                if (sessionId == pendingSessionId) pendingSessionId = 0L;
                if (sessionId == pumpSessionId) stopPump();
                return;
            }
            if (RecordingFrameBridge.ACTION_ATTACH.equals(action)) {
                attach(intent, sessionId);
            }
        }
    };

    HorizonRecordingFrames(@NonNull Activity activity) {
        this.activity = activity;
    }

    void register() {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(RecordingFrameBridge.ACTION_ATTACH);
        filter.addAction(RecordingFrameBridge.ACTION_DETACH);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(receiver, filter);
            }
            registered = true;
        } catch (Throwable throwable) {
            Log.e(TAG, "Unable to register the clean recording receiver", throwable);
        }
    }

    void unregister() {
        pendingSessionId = 0L;
        stopPump();
        if (!registered) return;
        registered = false;
        try {
            activity.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    private void attach(@NonNull Intent intent, long sessionId) {
        Surface encoderSurface = RecordingFrameBridge.readSurface(intent);
        int width = intent.getIntExtra(RecordingFrameBridge.EXTRA_WIDTH, 0);
        int height = intent.getIntExtra(RecordingFrameBridge.EXTRA_HEIGHT, 0);
        int fps = intent.getIntExtra(RecordingFrameBridge.EXTRA_FPS, 30);
        stopPump();
        pendingSessionId = 0L;

        if (encoderSurface == null || !encoderSurface.isValid()) {
            answer(sessionId, false, "Encoder surface is unavailable", encoderSurface);
            return;
        }
        if (!windowSource.isRecordingFrameCaptureReady()) {
            answer(sessionId, false, "Rust engine window is not ready", encoderSurface);
            return;
        }

        // One test copy first: only a window that really copies gets the clean recording.
        Bitmap probe;
        try {
            probe = Bitmap.createBitmap(
                    Math.max(2, windowSource.getRecordingFrameWidth() / 4),
                    Math.max(2, windowSource.getRecordingFrameHeight() / 4),
                    Bitmap.Config.ARGB_8888);
        } catch (Throwable throwable) {
            answer(sessionId, false, "Unable to allocate a test frame", encoderSurface);
            return;
        }
        pendingSessionId = sessionId;
        boolean started = copyWindow(probe, mainHandler, success -> {
            probe.recycle();
            if (pendingSessionId != sessionId) {
                // Detached (or replaced) while the test copy ran.
                releaseQuietly(encoderSurface);
                return;
            }
            pendingSessionId = 0L;
            if (!success) {
                answer(sessionId, false, "Rust engine window could not be copied", encoderSurface);
                return;
            }
            PrimaryRecordingFramePump next = new PrimaryRecordingFramePump(
                    windowSource, encoderSurface, width, height, fps);
            if (next.start()) {
                pump = next;
                pumpSessionId = sessionId;
                answer(sessionId, true, "Rust engine render-only frame pump attached", null);
            } else {
                next.stop();
                answer(sessionId, false, "Rust engine frame pump could not start", null);
            }
        });
        if (!started) {
            // copyWindow already reported the failure through the callback above.
            Log.i(TAG, "Clean recording test copy could not start");
        }
    }

    private void answer(long sessionId, boolean success, @NonNull String detail,
            @Nullable Surface releaseOnFailure) {
        if (!success && releaseOnFailure != null) releaseQuietly(releaseOnFailure);
        Log.i(TAG, "Clean recording " + (success ? "attached" : "not attached") + ": " + detail);
        RecordingFrameBridge.sendAck(activity, sessionId, success, detail);
    }

    /** PixelCopy of the engine's own window: the game picture, without the controls panel. */
    private boolean copyWindow(
            @NonNull Bitmap bitmap,
            @NonNull Handler callbackHandler,
            @NonNull MinecraftGLSurface.RecordingFrameCallback callback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onFrameReady(false);
            return false;
        }
        Window window = activity.getWindow();
        if (window == null || activity.isFinishing()) {
            callback.onFrameReady(false);
            return false;
        }
        try {
            PixelCopy.request(window, bitmap,
                    result -> callback.onFrameReady(result == PixelCopy.SUCCESS),
                    callbackHandler);
            return true;
        } catch (Throwable throwable) {
            // IllegalArgumentException: the window has no surface right now (backgrounded).
            callback.onFrameReady(false);
            return false;
        }
    }

    @Nullable
    private View decor() {
        Window window = activity.getWindow();
        return window == null ? null : window.peekDecorView();
    }

    private void stopPump() {
        PrimaryRecordingFramePump current = pump;
        pump = null;
        pumpSessionId = 0L;
        if (current != null) {
            try {
                current.stop();
            } catch (Throwable throwable) {
                Log.i(TAG, "Unable to stop the clean recording frame pump: " + throwable.getMessage());
            }
        }
    }

    private static void releaseQuietly(@NonNull Surface surface) {
        try {
            surface.release();
        } catch (Throwable ignored) {
        }
    }
}
