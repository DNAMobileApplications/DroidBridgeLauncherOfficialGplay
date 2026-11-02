/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.recording;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Cross-process hand-off between the :recording foreground service and GameActivity.
 *
 * When "hide touch controls in recordings" is enabled the encoder surface stays owned by
 * GameScreenRecorder, but the game process paints only Minecraft's render surface into it.
 * This avoids MediaProjection's final-composition capture (which would also contain the touch UI)
 * without hiding any controls from the player.
 */
public final class RecordingFrameBridge {
    private static final String TAG = "RecordingFrameBridge";

    public static final String ACTION_ATTACH =
            "ca.dnamobile.droidbridgelauncher.recording.CLEAN_FRAME_ATTACH";
    public static final String ACTION_DETACH =
            "ca.dnamobile.droidbridgelauncher.recording.CLEAN_FRAME_DETACH";
    public static final String ACTION_ACK =
            "ca.dnamobile.droidbridgelauncher.recording.CLEAN_FRAME_ACK";

    public static final String EXTRA_SURFACE = "encoder_surface";
    public static final String EXTRA_WIDTH = "encoder_width";
    public static final String EXTRA_HEIGHT = "encoder_height";
    public static final String EXTRA_FPS = "encoder_fps";
    public static final String EXTRA_SESSION = "encoder_session";
    public static final String EXTRA_SUCCESS = "encoder_success";
    public static final String EXTRA_MESSAGE = "encoder_message";

    private RecordingFrameBridge() {
    }

    public static final class AttachResult {
        public final boolean success;
        @Nullable public final String message;

        AttachResult(boolean success, @Nullable String message) {
            this.success = success;
            this.message = message;
        }
    }

    @NonNull
    public static AttachResult requestAttach(
            @NonNull Context context,
            @NonNull Surface surface,
            int width,
            int height,
            int fps,
            long sessionId
    ) {
        Context app = context.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        final String[] message = new String[1];

        BroadcastReceiver ackReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !ACTION_ACK.equals(intent.getAction())) return;
                if (intent.getLongExtra(EXTRA_SESSION, Long.MIN_VALUE) != sessionId) return;
                success.set(intent.getBooleanExtra(EXTRA_SUCCESS, false));
                message[0] = intent.getStringExtra(EXTRA_MESSAGE);
                latch.countDown();
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_ACK);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(ackReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(ackReceiver, filter);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to register clean-frame ACK receiver", throwable);
            return new AttachResult(false, "Unable to register clean-frame receiver");
        }

        try {
            Intent request = new Intent(ACTION_ATTACH);
            request.setPackage(app.getPackageName());
            request.putExtra(EXTRA_SURFACE, surface);
            request.putExtra(EXTRA_WIDTH, width);
            request.putExtra(EXTRA_HEIGHT, height);
            request.putExtra(EXTRA_FPS, fps);
            request.putExtra(EXTRA_SESSION, sessionId);
            app.sendBroadcast(request);

            boolean answered = latch.await(1800L, TimeUnit.MILLISECONDS);
            if (!answered) {
                sendDetach(app, sessionId);
                return new AttachResult(false, "Game view did not acknowledge clean capture");
            }
            return new AttachResult(success.get(), message[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendDetach(app, sessionId);
            return new AttachResult(false, "Clean capture was interrupted");
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to request clean game-frame capture", throwable);
            sendDetach(app, sessionId);
            return new AttachResult(false, throwable.getMessage());
        } finally {
            try {
                app.unregisterReceiver(ackReceiver);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void sendAck(
            @NonNull Context context,
            long sessionId,
            boolean success,
            @Nullable String message
    ) {
        Intent ack = new Intent(ACTION_ACK);
        ack.setPackage(context.getPackageName());
        ack.putExtra(EXTRA_SESSION, sessionId);
        ack.putExtra(EXTRA_SUCCESS, success);
        if (message != null) ack.putExtra(EXTRA_MESSAGE, message);
        context.sendBroadcast(ack);
    }

    public static void sendDetach(@NonNull Context context, long sessionId) {
        Intent detach = new Intent(ACTION_DETACH);
        detach.setPackage(context.getPackageName());
        detach.putExtra(EXTRA_SESSION, sessionId);
        try {
            context.sendBroadcast(detach);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to send clean-frame detach: " + throwable.getMessage());
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static Surface readSurface(@NonNull Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_SURFACE, Surface.class);
        }
        return intent.getParcelableExtra(EXTRA_SURFACE);
    }
}
