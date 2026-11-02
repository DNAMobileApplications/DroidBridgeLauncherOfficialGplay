/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;

/**
 * Records DroidBridge's own second-screen HUD/touch view without requesting another
 * MediaProjection session. The view is rendered directly into a surface-input H.264 encoder.
 *
 * This recorder is intentionally video-only. Minecraft's internal audio remains attached to
 * the primary/game MP4 so Android audio capture is opened exactly once per recording session.
 */
public final class DualScreenViewRecorder {
    private static final String TAG = "DualScreenViewRecorder";
    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int IFRAME_INTERVAL_SEC = 2;

    @NonNull private final Context context;
    @NonNull private final View sourceView;
    private int width;
    private int height;
    private int frameRate;
    private int bitrate;
    private long nextFrameUptimeMs;
    @NonNull private final String qualityPreset;
    private final boolean hideTouchControls;
    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private MediaCodec encoder;
    @Nullable private Surface inputSurface;
    @Nullable private MediaMuxer muxer;
    @Nullable private ParcelFileDescriptor outputPfd;
    @Nullable private Uri outputUri;
    @Nullable private Thread drainThread;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile boolean forceDrainExit;
    private volatile boolean muxerStarted;
    private volatile boolean wroteVideoSample;
    private volatile boolean videoHasKeyframe;
    private long videoSampleCount;
    private long videoEncodedBytes;
    private long sessionStartedElapsedMs;
    private int videoTrack = -1;
    @NonNull private String displayName = "";

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started.get() || stopping.get()) return;
            try {
                drawFrame();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to render dual-screen recording frame", throwable);
            }
            if (!stopping.get()) {
                long intervalMs = Math.max(8L, Math.round(1000f / frameRate));
                nextFrameUptimeMs = Math.max(nextFrameUptimeMs + intervalMs,
                        SystemClock.uptimeMillis() + 1L);
                mainHandler.postAtTime(this, nextFrameUptimeMs);
            }
        }
    };

    public DualScreenViewRecorder(
            @NonNull Context context,
            @NonNull View sourceView,
            int width,
            int height,
            boolean hideTouchControls
    ) {
        this.context = context.getApplicationContext();
        this.sourceView = sourceView;
        RecordingVideoOptions.Target selected = RecordingPreferences.getVideoTarget(
                this.context, width, height);
        this.width = selected.width;
        this.height = selected.height;
        this.frameRate = selected.frameRate;
        this.bitrate = selected.bitrate;
        this.qualityPreset = RecordingPreferences.getVideoPreset(this.context);
        this.hideTouchControls = hideTouchControls;
    }

    public void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Dual-screen recorder has already been started.");
        }

        sessionStartedElapsedMs = SystemClock.elapsedRealtime();
        String startStage = "create-output";
        try {
            createOutput();
            startStage = "create-muxer";
            createMuxer();
            startStage = "create-video-encoder";
            createEncoder();
            RecordingDiagnostics.logStartRequest(context, "second-screen", displayName,
                    width, height, sourceView.getResources().getDisplayMetrics().densityDpi,
                    frameRate, false);
            startStage = "start-drain-thread";
            drainThread = new Thread(this::drainEncoder, "DroidBridgeSecondScreenMux");
            drainThread.start();

            // View.draw() must happen on the UI thread. Start on the next UI turn so the
            // Presentation/local HUD view has finished its current layout pass.
            nextFrameUptimeMs = SystemClock.uptimeMillis();
            mainHandler.post(frameRunnable);
            Logging.i(TAG, "Second-screen recording started " + width + "x" + height
                    + " @ " + frameRate + "fps hideTouchControls=" + hideTouchControls
                    + " -> " + displayName);
        } catch (Throwable throwable) {
            RecordingDiagnostics.logSessionFailure(
                    "second-screen", displayName, startStage, throwable);
            stopping.set(true);
            forceDrainExit = true;
            mainHandler.removeCallbacks(frameRunnable);
            releaseAll(false);
            if (throwable instanceof Exception) throw (Exception) throwable;
            throw new RuntimeException(throwable);
        }
    }

    public boolean isStarted() {
        return started.get() && !stopping.get();
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public Uri getOutputUri() {
        return outputUri;
    }

    public boolean stop() {
        if (!started.get()) return false;
        if (!stopping.compareAndSet(false, true)) return wroteVideoSample;

        mainHandler.removeCallbacks(frameRunnable);
        waitForUiThreadTurn();

        MediaCodec active = encoder;
        if (active != null) {
            try {
                active.signalEndOfInputStream();
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to signal second-screen video EOS: " + throwable.getMessage());
            }
        }

        Thread drain = drainThread;
        if (drain != null && drain != Thread.currentThread()) {
            joinQuietly(drain, 5000L);
            if (drain.isAlive()) {
                forceDrainExit = true;
                drain.interrupt();
                joinQuietly(drain, 750L);
            }
        }

        releaseAll(wroteVideoSample && muxerStarted);
        Logging.i(TAG, "Second-screen recording stopped; video=" + wroteVideoSample
                + " file=" + displayName);
        return wroteVideoSample;
    }

    private void createOutput() throws IOException {
        displayName = "DroidBridge_SecondScreen_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/DroidBridge/Recordings");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        ContentResolver resolver = context.getContentResolver();
        outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            throw new IOException("Unable to create the second-screen recording file.");
        }
        outputPfd = resolver.openFileDescriptor(outputUri, "rw");
        if (outputPfd == null) {
            throw new IOException("Unable to open the second-screen recording file.");
        }
    }

    private void createMuxer() throws IOException {
        if (outputPfd == null) throw new IOException("Second-screen recording output is not open.");
        muxer = new MediaMuxer(
                outputPfd.getFileDescriptor(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        );
    }

    private void createEncoder() throws Exception {
        MediaCodec codec = null;
        MediaFormat format = null;
        try {
            codec = MediaCodec.createEncoderByType(VIDEO_MIME);
            encoder = codec;
            RecordingVideoOptions.Target compatible = RecordingVideoOptions.supported(codec,
                    new RecordingVideoOptions.Target(width, height, frameRate, bitrate), qualityPreset);
            if (compatible.width != width || compatible.height != height
                    || compatible.frameRate != frameRate) {
                Logging.i(TAG, "Second-screen encoder fallback: requested=" + width + "x"
                        + height + "@" + frameRate + " actual=" + compatible.width + "x"
                        + compatible.height + "@" + compatible.frameRate);
            }
            width = compatible.width;
            height = compatible.height;
            frameRate = compatible.frameRate;
            bitrate = compatible.bitrate;
            format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC);
            RecordingDiagnostics.logVideoCodec(
                    "second-screen", codec, format, width, height, frameRate, bitrate);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();
            Logging.i(TAG, "Second-screen video encoder started codec=" + codec.getName());
        } catch (Exception exception) {
            RecordingDiagnostics.logCodecFailure(
                    "second-screen", "video-configure-or-start", codec, format, exception);
            throw exception;
        }
    }

    private void drawFrame() {
        Surface surface = inputSurface;
        if (surface == null || !surface.isValid()) return;

        int sourceWidth = sourceView.getWidth();
        int sourceHeight = sourceView.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) return;

        Canvas canvas = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    canvas = surface.lockHardwareCanvas();
                } catch (Throwable ignored) {
                    canvas = null;
                }
            }
            if (canvas == null) {
                canvas = surface.lockCanvas(null);
            }
            if (canvas == null) return;

            canvas.drawColor(Color.BLACK);
            float scale = Math.min(
                    width / (float) sourceWidth,
                    height / (float) sourceHeight
            );
            float drawnWidth = sourceWidth * scale;
            float drawnHeight = sourceHeight * scale;
            float left = (width - drawnWidth) * 0.5f;
            float top = (height - drawnHeight) * 0.5f;

            int save = canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            if (hideTouchControls) {
                TouchControlsOverlay.setRecordingArtworkSuppressed(true);
            }
            try {
                sourceView.draw(canvas);
            } finally {
                if (hideTouchControls) {
                    TouchControlsOverlay.setRecordingArtworkSuppressed(false);
                }
            }
            canvas.restoreToCount(save);
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Unable to queue second-screen recording frame", throwable);
                }
            }
        }
    }

    private void drainEncoder() {
        MediaCodec active = encoder;
        if (active == null) return;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;
        while (!forceDrainExit && !eos) {
            int outputIndex;
            try {
                outputIndex = active.dequeueOutputBuffer(info, 10_000L);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Second-screen encoder drain failed", throwable);
                break;
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (stopping.get()) {
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException ignored) {
                        if (forceDrainExit) break;
                    }
                }
                continue;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = active.getOutputFormat();
                RecordingDiagnostics.logTrackFormat(
                        "second-screen", "video", outputFormat);
                MediaMuxer activeMuxer = muxer;
                if (activeMuxer == null) break;
                try {
                    if (videoTrack >= 0) {
                        throw new IllegalStateException("Second-screen video format changed twice.");
                    }
                    videoTrack = activeMuxer.addTrack(outputFormat);
                    activeMuxer.start();
                    muxerStarted = true;
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Unable to start second-screen MP4 muxer", throwable);
                    forceDrainExit = true;
                }
                continue;
            }

            if (outputIndex < 0) continue;
            eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

            try {
                ByteBuffer output = active.getOutputBuffer(outputIndex);
                if (output != null
                        && info.size > 0
                        && muxerStarted
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    MediaMuxer activeMuxer = muxer;
                    if (activeMuxer != null && videoTrack >= 0) {
                        activeMuxer.writeSampleData(videoTrack, output, info);
                        wroteVideoSample = true;
                        videoSampleCount++;
                        videoEncodedBytes += Math.max(0, info.size);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            videoHasKeyframe = true;
                        }
                    }
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to write second-screen video sample", throwable);
                forceDrainExit = true;
            } finally {
                try {
                    active.releaseOutputBuffer(outputIndex, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void releaseAll(boolean publish) {
        mainHandler.removeCallbacks(frameRunnable);

        Surface surface = inputSurface;
        inputSurface = null;
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
        }

        MediaCodec activeEncoder = encoder;
        encoder = null;
        if (activeEncoder != null) {
            try { activeEncoder.stop(); } catch (Throwable ignored) {}
            try { activeEncoder.release(); } catch (Throwable ignored) {}
        }

        MediaMuxer activeMuxer = muxer;
        muxer = null;
        if (activeMuxer != null) {
            if (muxerStarted) {
                try {
                    activeMuxer.stop();
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Second-screen muxer stop failed", throwable);
                }
            }
            try { activeMuxer.release(); } catch (Throwable ignored) {}
        }

        ParcelFileDescriptor pfd = outputPfd;
        outputPfd = null;
        if (pfd != null) {
            try { pfd.close(); } catch (Throwable ignored) {}
        }

        Uri uri = outputUri;
        long runtimeMs = sessionStartedElapsedMs > 0L
                ? SystemClock.elapsedRealtime() - sessionStartedElapsedMs
                : 0L;
        RecordingDiagnostics.logFinalHealth(
                context,
                "second-screen",
                displayName,
                uri,
                false,
                width,
                height,
                publish,
                muxerStarted,
                videoHasKeyframe,
                videoSampleCount,
                0L,
                videoEncodedBytes,
                0L,
                runtimeMs
        );

        if (uri != null) {
            ContentResolver resolver = context.getContentResolver();
            if (publish) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Video.Media.IS_PENDING, 0);
                        resolver.update(uri, values, null, null);
                    } catch (Throwable throwable) {
                        Logging.e(TAG, "Unable to publish second-screen recording", throwable);
                    }
                }
            } else {
                try { resolver.delete(uri, null, null); } catch (Throwable ignored) {}
                outputUri = null;
            }
        }
    }

    private void waitForUiThreadTurn() {
        if (Looper.myLooper() == Looper.getMainLooper()) return;
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(latch::countDown);
        try {
            latch.await(750L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(@NonNull Thread thread, long timeoutMs) {
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int makeEven(int value) {
        return (value & 1) == 0 ? value : Math.max(2, value - 1);
    }
}
