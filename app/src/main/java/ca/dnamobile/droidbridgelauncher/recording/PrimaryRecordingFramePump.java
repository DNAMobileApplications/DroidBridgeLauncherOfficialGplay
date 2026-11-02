/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.recording;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.NonNull;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

/** Paints only Minecraft's render surface into the primary recording encoder. */
public final class PrimaryRecordingFramePump {
    private static final String TAG = "PrimaryRecordingFramePump";

    @NonNull private final MinecraftGLSurface source;
    @NonNull private final Surface encoderSurface;
    private final int targetWidth;
    private final int targetHeight;
    private final long frameIntervalMs;
    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @NonNull private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    @NonNull private final Rect sourceRect = new Rect();
    @NonNull private final RectF targetRect = new RectF();

    private boolean running;
    private boolean copyInFlight;
    private long nextFrameUptimeMs;
    private int consecutiveFailures;
    private Bitmap frameBitmap;
    private boolean encoderSurfaceReleased;

    private final Runnable frameRunnable = this::requestNextFrame;

    public PrimaryRecordingFramePump(
            @NonNull MinecraftGLSurface source,
            @NonNull Surface encoderSurface,
            int targetWidth,
            int targetHeight,
            int fps
    ) {
        this.source = source;
        this.encoderSurface = encoderSurface;
        this.targetWidth = Math.max(2, targetWidth);
        this.targetHeight = Math.max(2, targetHeight);
        this.frameIntervalMs = Math.max(8L, Math.round(1000f / Math.max(1, Math.min(120, fps))));
    }

    /**
     * Starts only when the render surface is actually capture-capable. This lets the recording
     * service fall back to ordinary MediaProjection rather than ever producing a black video.
     */
    public boolean start() {
        if (running) return true;
        if (!encoderSurface.isValid() || !source.isRecordingFrameCaptureReady()) {
            return false;
        }
        int sourceWidth = source.getRecordingFrameWidth();
        int sourceHeight = source.getRecordingFrameHeight();
        if (sourceWidth <= 1 || sourceHeight <= 1) return false;

        try {
            frameBitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to allocate clean recording frame buffer", throwable);
            return false;
        }

        running = true;
        copyInFlight = false;
        consecutiveFailures = 0;
        nextFrameUptimeMs = SystemClock.uptimeMillis();
        mainHandler.post(frameRunnable);
        Logging.i(TAG, "Clean Minecraft frame pump started source=" + sourceWidth + "x" + sourceHeight
                + " encoder=" + targetWidth + "x" + targetHeight
                + " intervalMs=" + frameIntervalMs);
        return true;
    }

    public void stop() {
        if (!running && frameBitmap == null && encoderSurfaceReleased) return;
        running = false;
        copyInFlight = false;
        mainHandler.removeCallbacks(frameRunnable);
        Bitmap bitmap = frameBitmap;
        frameBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        if (!encoderSurfaceReleased) {
            encoderSurfaceReleased = true;
            try {
                encoderSurface.release();
            } catch (Throwable ignored) {
            }
        }
        Logging.i(TAG, "Clean Minecraft frame pump stopped");
    }

    private void requestNextFrame() {
        if (!running || copyInFlight || !encoderSurface.isValid()) return;

        int sourceWidth = source.getRecordingFrameWidth();
        int sourceHeight = source.getRecordingFrameHeight();
        if (sourceWidth <= 1 || sourceHeight <= 1) {
            scheduleNextFrame();
            return;
        }

        Bitmap bitmap = frameBitmap;
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() != sourceWidth || bitmap.getHeight() != sourceHeight) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            try {
                bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
                frameBitmap = bitmap;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to resize clean recording frame buffer", throwable);
                stop();
                return;
            }
        }

        copyInFlight = true;
        final Bitmap captureTarget = bitmap;
        boolean accepted = source.requestRecordingFrame(captureTarget, mainHandler, success -> {
            copyInFlight = false;
            if (!running) return;
            if (success) {
                consecutiveFailures = 0;
                drawFrame(captureTarget);
            } else {
                consecutiveFailures++;
                if (consecutiveFailures == 1 || consecutiveFailures % 30 == 0) {
                    Logging.i(TAG, "Minecraft frame copy failed count=" + consecutiveFailures);
                }
            }
            scheduleNextFrame();
        });

        if (!accepted) {
            copyInFlight = false;
            consecutiveFailures++;
            scheduleNextFrame();
        }
    }

    private void drawFrame(@NonNull Bitmap bitmap) {
        Canvas canvas = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    canvas = encoderSurface.lockHardwareCanvas();
                } catch (Throwable ignored) {
                    canvas = null;
                }
            }
            if (canvas == null) canvas = encoderSurface.lockCanvas(null);
            if (canvas == null) return;

            canvas.drawColor(Color.BLACK);
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            sourceRect.set(0, 0, bw, bh);

            float scale = Math.min(targetWidth / (float) bw, targetHeight / (float) bh);
            float drawWidth = bw * scale;
            float drawHeight = bh * scale;
            float left = (targetWidth - drawWidth) * 0.5f;
            float top = (targetHeight - drawHeight) * 0.5f;
            targetRect.set(left, top, left + drawWidth, top + drawHeight);
            canvas.drawBitmap(bitmap, sourceRect, targetRect, paint);
        } catch (Throwable throwable) {
            consecutiveFailures++;
            if (consecutiveFailures == 1 || consecutiveFailures % 30 == 0) {
                Logging.e(TAG, "Unable to draw clean recording frame", throwable);
            }
        } finally {
            if (canvas != null) {
                try {
                    encoderSurface.unlockCanvasAndPost(canvas);
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Unable to queue clean recording frame: " + throwable.getMessage());
                }
            }
        }
    }

    private void scheduleNextFrame() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        nextFrameUptimeMs = Math.max(nextFrameUptimeMs + frameIntervalMs, now + 1L);
        mainHandler.postAtTime(frameRunnable, nextFrameUptimeMs);
    }
}
