/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.recording;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/** Process-local owner for the optional second-screen recording. */
public final class DualScreenRecordingSession {
    private static final String TAG = "DualScreenRecordingSession";
    @Nullable private static DualScreenViewRecorder recorder;
    private static volatile boolean finalizing;

    private DualScreenRecordingSession() {
    }

    @NonNull
    public static synchronized StartResult start(
            @NonNull Context context,
            @NonNull View sourceView
    ) {
        if (recorder != null && recorder.isStarted()) {
            return new StartResult(true, recorder.getDisplayName(), null);
        }
        if (finalizing) {
            return new StartResult(false, null,
                    "The previous second-screen recording is still being finalized.");
        }

        int width = sourceView.getWidth();
        int height = sourceView.getHeight();
        if (width <= 0 || height <= 0) {
            Display display = sourceView.getDisplay();
            if (display != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                width = metrics.widthPixels;
                height = metrics.heightPixels;
            }
        }

        if (width <= 0 || height <= 0) {
            return new StartResult(false, null,
                    "The second-screen HUD is not laid out yet.");
        }

        try {
            DualScreenViewRecorder next = new DualScreenViewRecorder(
                    context,
                    sourceView,
                    width,
                    height,
                    RecordingPreferences.isHideTouchControlsEnabled(context)
            );
            next.start();
            recorder = next;
            return new StartResult(true, next.getDisplayName(), null);
        } catch (Throwable throwable) {
            // DualScreenViewRecorder already records the full start-stage stack trace. Keep this
            // owner-level line compact so launcherlogs.txt does not duplicate the same trace.
            Logging.e(TAG, "Unable to start second-screen recorder; "
                    + RecordingDiagnostics.describeThrowable(throwable));
            recorder = null;
            return new StartResult(false, null, RecordingDiagnostics.shortError(throwable));
        }
    }

    public static synchronized boolean isRecording() {
        return recorder != null && recorder.isStarted();
    }

    public static synchronized boolean isRecordingOrFinalizing() {
        return finalizing || (recorder != null && recorder.isStarted());
    }

    /** Stops on a worker so the Activity UI never blocks while MediaCodec finalizes the MP4. */
    public static void stopAsync() {
        final DualScreenViewRecorder active;
        synchronized (DualScreenRecordingSession.class) {
            active = recorder;
            recorder = null;
        }
        if (active == null) return;
        finalizing = true;
        new Thread(() -> {
            try {
                active.stop();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to finalize second-screen recording", throwable);
            } finally {
                finalizing = false;
            }
        }, "DroidBridgeSecondScreenStop").start();
    }

    /** Waits only for an already-requested second-screen finalization. */
    public static boolean awaitStopped(long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (isRecordingOrFinalizing()) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return false;
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return !isRecordingOrFinalizing();
            }
        }
        return true;
    }

    public static final class StartResult {
        public final boolean success;
        @Nullable public final String fileName;
        @Nullable public final String errorMessage;

        StartResult(boolean success, @Nullable String fileName, @Nullable String errorMessage) {
            this.success = success;
            this.fileName = fileName;
            this.errorMessage = errorMessage;
        }
    }
}
