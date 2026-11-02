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
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Single-display recorder for DroidBridge's game process.
 *
 * Video is supplied by MediaProjection to a surface-input H.264 encoder. Minecraft audio is
 * captured with AudioPlaybackCaptureConfiguration restricted to DroidBridge's own UID, encoded
 * as AAC, and muxed into the same MP4. Android 10+ is required because playback capture was
 * introduced in API 29.
 */
public final class GameScreenRecorder {
    private static final String TAG = "GameScreenRecorder";
    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int VIDEO_IFRAME_INTERVAL_SEC = 2;
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNEL_COUNT = 2;
    private static final int AUDIO_BITRATE = 192_000;
    private static final int PCM_BYTES_PER_FRAME = AUDIO_CHANNEL_COUNT * 2;
    private static final long CODEC_TIMEOUT_US = 10_000L;

    public interface Listener {
        void onProjectionStoppedUnexpectedly();
    }

    @NonNull private final Context context;
    private final int resultCode;
    @NonNull private final Intent projectionData;
    private int width;
    private int height;
    private int frameRate;
    private int videoBitrate;
    @NonNull private final String qualityPreset;
    private final int densityDpi;
    @Nullable private final Listener listener;
    @NonNull private final String audioMode;

    @Nullable private MediaProjection mediaProjection;
    @Nullable private VirtualDisplay virtualDisplay;
    private boolean cleanFrameCaptureActive;
    private long cleanFrameCaptureSessionId;
    @Nullable private MediaCodec videoEncoder;
    @Nullable private MediaCodec audioEncoder;
    @Nullable private Surface videoInputSurface;
    @Nullable private AudioRecord gameAudioRecord;
    @Nullable private AudioRecord microphoneAudioRecord;
    @Nullable private MediaMuxer muxer;
    @Nullable private ParcelFileDescriptor outputPfd;
    @Nullable private Uri outputUri;
    @Nullable private Thread audioThread;
    @Nullable private Thread drainThread;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile boolean audioEosQueued;
    private volatile boolean forceDrainExit;
    private volatile boolean muxerStarted;
    private volatile boolean wroteSamples;
    private volatile boolean wroteVideoSample;
    private volatile boolean wroteAudioSample;
    private volatile boolean videoHasKeyframe;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private long firstVideoPtsUs = Long.MIN_VALUE;
    private long firstAudioPtsUs = Long.MIN_VALUE;
    private long queuedAudioFrames;
    private long videoSampleCount;
    private long audioSampleCount;
    private long videoEncodedBytes;
    private long audioEncodedBytes;
    private long sessionStartedElapsedMs;
    @NonNull private String displayName = "";

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (!stopping.get() && listener != null) {
                listener.onProjectionStoppedUnexpectedly();
            }
        }
    };

    public GameScreenRecorder(
            @NonNull Context context,
            int resultCode,
            @NonNull Intent projectionData,
            int width,
            int height,
            int densityDpi,
            @NonNull String audioMode,
            @Nullable Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.resultCode = resultCode;
        this.projectionData = new Intent(projectionData);
        RecordingVideoOptions.Target selection = RecordingPreferences.getVideoTarget(
                this.context, width, height);
        this.width = selection.width;
        this.height = selection.height;
        this.frameRate = selection.frameRate;
        this.videoBitrate = selection.bitrate;
        this.qualityPreset = RecordingPreferences.getVideoPreset(this.context);
        this.densityDpi = Math.max(1, densityDpi);
        this.listener = listener;
        this.audioMode = RecordingPreferences.normalizeAudioMode(audioMode);
    }

    public void start() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IllegalStateException("Minecraft screen + internal audio recording requires Android 10 or newer.");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Recorder has already been started.");
        }

        sessionStartedElapsedMs = SystemClock.elapsedRealtime();
        String startStage = "create-output";
        try {
            createOutput();
            startStage = "create-media-projection";
            createProjection();
            startStage = "create-muxer";
            createMuxer();
            startStage = "create-video-encoder";
            createVideoEncoder();
            RecordingDiagnostics.logStartRequest(context, "primary", displayName,
                    width, height, densityDpi, frameRate, true);
            startStage = "create-audio-capture-and-encoder";
            createAudioCaptureAndEncoder();

            startStage = "start-drain-thread";
            drainThread = new Thread(this::drainEncoders, "DroidBridgeRecordingMux");
            drainThread.start();

            startStage = "start-audio-capture";
            startAudioRecord(gameAudioRecord, "internal game audio");
            startAudioRecord(microphoneAudioRecord, "microphone audio");
            audioThread = new Thread(this::pumpAudio, "DroidBridgeRecordingAudio");
            audioThread.start();

            boolean wantsCleanGameFrames = RecordingPreferences.isHideTouchControlsEnabled(context);
            if (wantsCleanGameFrames && videoInputSurface != null) {
                startStage = "attach-clean-game-frame-source";
                cleanFrameCaptureSessionId = SystemClock.elapsedRealtimeNanos();
                RecordingFrameBridge.AttachResult cleanResult = RecordingFrameBridge.requestAttach(
                        context,
                        videoInputSurface,
                        width,
                        height,
                        RecordingPreferences.isVideoConfigurationExplicit(context)
                                ? frameRate : Math.min(30, frameRate),
                        cleanFrameCaptureSessionId
                );
                cleanFrameCaptureActive = cleanResult.success;
                Logging.i(TAG, "Clean Minecraft frame source requested result="
                        + cleanFrameCaptureActive
                        + " detail=" + cleanResult.message);
            }

            if (!cleanFrameCaptureActive) {
                startStage = "create-virtual-display";
                virtualDisplay = mediaProjection.createVirtualDisplay(
                        "DroidBridge Minecraft Recording",
                        width,
                        height,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        videoInputSurface,
                        null,
                        null
                );
                if (virtualDisplay == null) {
                    throw new IllegalStateException("Android could not create the recording display.");
                }
            }

            Logging.i(TAG, "Recording started " + width + "x" + height + " @ " + frameRate
                    + "fps videoSource="
                    + (cleanFrameCaptureActive ? "minecraft-render-only" : "media-projection")
                    + " audioMode=" + RecordingPreferences.getAudioModeLabel(audioMode)
                    + " -> " + displayName);
        } catch (Throwable throwable) {
            RecordingDiagnostics.logSessionFailure(
                    "primary", displayName, startStage, throwable);
            stopping.set(true);
            forceDrainExit = true;
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

    /** Stops capture, flushes both encoders, finalizes the MP4, and publishes it in MediaStore. */
    public boolean stop() {
        if (!started.get()) return false;
        if (!stopping.compareAndSet(false, true)) return wroteVideoSample && wroteAudioSample;

        try {
            if (cleanFrameCaptureSessionId != 0L) {
                RecordingFrameBridge.sendDetach(context, cleanFrameCaptureSessionId);
                cleanFrameCaptureActive = false;
            }

            VirtualDisplay display = virtualDisplay;
            virtualDisplay = null;
            if (display != null) {
                try {
                    display.release();
                } catch (Throwable ignored) {
                }
            }

            stopAudioRecord(gameAudioRecord);
            stopAudioRecord(microphoneAudioRecord);

            MediaCodec video = videoEncoder;
            if (video != null) {
                try {
                    video.signalEndOfInputStream();
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Unable to signal video EOS: " + throwable.getMessage());
                }
            }

            Thread audioWorker = audioThread;
            if (audioWorker != null && audioWorker != Thread.currentThread()) {
                joinQuietly(audioWorker, 1500L);
            }
            queueAudioEosIfNeeded();

            Thread drainWorker = drainThread;
            if (drainWorker != null && drainWorker != Thread.currentThread()) {
                joinQuietly(drainWorker, 5000L);
                if (drainWorker.isAlive()) {
                    forceDrainExit = true;
                    drainWorker.interrupt();
                    joinQuietly(drainWorker, 750L);
                }
            }
        } finally {
            releaseAll(wroteVideoSample && wroteAudioSample && muxerStarted);
        }

        boolean complete = wroteVideoSample && wroteAudioSample;
        Logging.i(TAG, "Recording stopped; video=" + wroteVideoSample
                + " audio=" + wroteAudioSample + " file=" + displayName);
        return complete;
    }

    private void createProjection() {
        MediaProjectionManager manager = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("MediaProjection service is unavailable.");
        }
        mediaProjection = manager.getMediaProjection(resultCode, projectionData);
        if (mediaProjection == null) {
            throw new IllegalStateException("Android did not return a valid screen-capture session.");
        }
        mediaProjection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
    }

    private void createOutput() throws IOException {
        displayName = "DroidBridge_Minecraft_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/DroidBridge/Recordings");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            throw new IOException("Unable to create a file in Movies/DroidBridge/Recordings.");
        }
        outputPfd = resolver.openFileDescriptor(outputUri, "rw");
        if (outputPfd == null) {
            throw new IOException("Unable to open the new recording file.");
        }
    }

    private void createMuxer() throws IOException {
        if (outputPfd == null) throw new IOException("Recording output is not open.");
        muxer = new MediaMuxer(outputPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void createVideoEncoder() throws Exception {
        MediaCodec codec = null;
        MediaFormat format = null;
        try {
            codec = MediaCodec.createEncoderByType(VIDEO_MIME);
            videoEncoder = codec;
            RecordingVideoOptions.Target compatible = RecordingVideoOptions.supported(codec,
                    new RecordingVideoOptions.Target(width, height, frameRate, videoBitrate),
                    qualityPreset);
            if (compatible.width != width || compatible.height != height
                    || compatible.frameRate != frameRate) {
                Logging.i(TAG, "Recording encoder fallback: requested=" + width + "x" + height
                        + "@" + frameRate + " actual=" + compatible.width + "x"
                        + compatible.height + "@" + compatible.frameRate);
            }
            width = compatible.width;
            height = compatible.height;
            frameRate = compatible.frameRate;
            videoBitrate = compatible.bitrate;
            format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL_SEC);
            RecordingDiagnostics.logVideoCodec(
                    "primary", codec, format, width, height, frameRate, videoBitrate);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoInputSurface = codec.createInputSurface();
            codec.start();
            Logging.i(TAG, "Video encoder started codec=" + codec.getName());
        } catch (Exception exception) {
            RecordingDiagnostics.logCodecFailure(
                    "primary", "video-configure-or-start", codec, format, exception);
            throw exception;
        }
    }

    private void createAudioCaptureAndEncoder() throws Exception {
        boolean captureGame = RecordingPreferences.AUDIO_MODE_GAME_ONLY.equals(audioMode)
                || RecordingPreferences.AUDIO_MODE_VOICE_AND_GAME.equals(audioMode);
        boolean captureMic = RecordingPreferences.AUDIO_MODE_VOICE_ONLY.equals(audioMode)
                || RecordingPreferences.AUDIO_MODE_VOICE_AND_GAME.equals(audioMode);

        int stereoMinBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (stereoMinBuffer <= 0) stereoMinBuffer = AUDIO_SAMPLE_RATE * PCM_BYTES_PER_FRAME / 5;
        int stereoCaptureBuffer = Math.max(
                stereoMinBuffer * 4,
                AUDIO_SAMPLE_RATE * PCM_BYTES_PER_FRAME / 2
        );

        if (captureGame) {
            AudioPlaybackCaptureConfiguration captureConfig =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUid(Process.myUid())
                            .build();
            AudioFormat gameFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            gameAudioRecord = new AudioRecord.Builder()
                    .setAudioFormat(gameFormat)
                    .setBufferSizeInBytes(stereoCaptureBuffer)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build();
            if (gameAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Internal Minecraft audio capture could not be initialized.");
            }
        }

        if (captureMic) {
            int micMinBuffer = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (micMinBuffer <= 0) micMinBuffer = AUDIO_SAMPLE_RATE * 2 / 5;
            int micCaptureBuffer = Math.max(micMinBuffer * 4, AUDIO_SAMPLE_RATE);
            AudioFormat micFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            microphoneAudioRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(micFormat)
                    .setBufferSizeInBytes(micCaptureBuffer)
                    .build();
            if (microphoneAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone capture could not be initialized.");
            }
        }

        MediaFormat encoderFormat = MediaFormat.createAudioFormat(
                AUDIO_MIME,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
        );
        encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, stereoCaptureBuffer);

        MediaCodec codec = null;
        try {
            codec = MediaCodec.createEncoderByType(AUDIO_MIME);
            audioEncoder = codec;
            RecordingDiagnostics.logAudioCodec(
                    "primary",
                    codec,
                    encoderFormat,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNEL_COUNT,
                    AUDIO_BITRATE
            );
            codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            Logging.i(TAG, "Audio encoder started codec=" + codec.getName()
                    + " mode=" + RecordingPreferences.getAudioModeLabel(audioMode));
        } catch (Exception exception) {
            RecordingDiagnostics.logCodecFailure(
                    "primary", "audio-configure-or-start", codec, encoderFormat, exception);
            throw exception;
        }
    }

    private void startAudioRecord(@Nullable AudioRecord recorder, @NonNull String label) {
        if (recorder == null) return;
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Android could not start " + label + ".");
        }
    }

    private static void stopAudioRecord(@Nullable AudioRecord recorder) {
        if (recorder == null) return;
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        } catch (Throwable ignored) {
        }
    }

    private static void releaseAudioRecord(@Nullable AudioRecord recorder) {
        if (recorder == null) return;
        stopAudioRecord(recorder);
        try {
            recorder.release();
        } catch (Throwable ignored) {
        }
    }

    private void pumpAudio() {
        MediaCodec encoder = audioEncoder;
        if (encoder == null) return;

        final int chunkFrames = 960; // 20 ms at 48 kHz.
        final byte[] gameBuffer = new byte[chunkFrames * PCM_BYTES_PER_FRAME];
        final byte[] micBuffer = new byte[chunkFrames * 2];
        final byte[] encodedPcm = new byte[chunkFrames * PCM_BYTES_PER_FRAME];

        try {
            while (!stopping.get()) {
                int bytesReady;
                if (RecordingPreferences.AUDIO_MODE_GAME_ONLY.equals(audioMode)) {
                    bytesReady = readAudio(gameAudioRecord, gameBuffer, "Internal audio");
                    if (bytesReady <= 0) continue;
                    bytesReady -= bytesReady % PCM_BYTES_PER_FRAME;
                    if (bytesReady <= 0) continue;
                    System.arraycopy(gameBuffer, 0, encodedPcm, 0, bytesReady);
                } else if (RecordingPreferences.AUDIO_MODE_VOICE_ONLY.equals(audioMode)) {
                    int micBytes = readAudio(microphoneAudioRecord, micBuffer, "Microphone");
                    if (micBytes <= 0) continue;
                    int frames = micBytes / 2;
                    bytesReady = frames * PCM_BYTES_PER_FRAME;
                    upmixMonoToStereo(micBuffer, frames, encodedPcm);
                } else {
                    int gameBytes = readAudio(gameAudioRecord, gameBuffer, "Internal audio");
                    if (stopping.get()) break;
                    int micBytes = readAudio(microphoneAudioRecord, micBuffer, "Microphone");
                    if (gameBytes <= 0 || micBytes <= 0) continue;
                    int frames = Math.min(gameBytes / PCM_BYTES_PER_FRAME, micBytes / 2);
                    if (frames <= 0) continue;
                    bytesReady = frames * PCM_BYTES_PER_FRAME;
                    mixGameAndMicrophone(gameBuffer, micBuffer, frames, encodedPcm);
                }

                queuePcmToEncoder(encoder, encodedPcm, bytesReady);
            }
        } finally {
            queueAudioEosIfNeeded();
        }
    }

    private int readAudio(@Nullable AudioRecord recorder, @NonNull byte[] buffer, @NonNull String label) {
        if (recorder == null) return -1;
        try {
            return recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
        } catch (Throwable throwable) {
            if (!stopping.get()) Logging.e(TAG, label + " read failed", throwable);
            return -1;
        }
    }

    private void queuePcmToEncoder(
            @NonNull MediaCodec encoder,
            @NonNull byte[] pcm,
            int length
    ) {
        int offset = 0;
        while (offset < length && !stopping.get()) {
            int inputIndex;
            try {
                inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            } catch (Throwable throwable) {
                Logging.e(TAG, "AAC input dequeue failed", throwable);
                return;
            }
            if (inputIndex < 0) continue;

            ByteBuffer input = encoder.getInputBuffer(inputIndex);
            if (input == null) {
                encoder.queueInputBuffer(inputIndex, 0, 0, audioPtsForQueuedFrames(), 0);
                continue;
            }
            input.clear();
            int copy = Math.min(input.remaining(), length - offset);
            copy -= copy % PCM_BYTES_PER_FRAME;
            if (copy <= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, audioPtsForQueuedFrames(), 0);
                continue;
            }
            input.put(pcm, offset, copy);
            long ptsUs = audioPtsForQueuedFrames();
            encoder.queueInputBuffer(inputIndex, 0, copy, ptsUs, 0);
            queuedAudioFrames += copy / PCM_BYTES_PER_FRAME;
            offset += copy;
        }
    }

    private static void upmixMonoToStereo(
            @NonNull byte[] mono,
            int frames,
            @NonNull byte[] stereo
    ) {
        for (int frame = 0; frame < frames; frame++) {
            int monoOffset = frame * 2;
            byte lo = mono[monoOffset];
            byte hi = mono[monoOffset + 1];
            int stereoOffset = frame * 4;
            stereo[stereoOffset] = lo;
            stereo[stereoOffset + 1] = hi;
            stereo[stereoOffset + 2] = lo;
            stereo[stereoOffset + 3] = hi;
        }
    }

    private static void mixGameAndMicrophone(
            @NonNull byte[] gameStereo,
            @NonNull byte[] micMono,
            int frames,
            @NonNull byte[] mixedStereo
    ) {
        for (int frame = 0; frame < frames; frame++) {
            int gameOffset = frame * 4;
            int micOffset = frame * 2;
            int mic = readPcm16(micMono, micOffset);
            int left = saturatePcm16(readPcm16(gameStereo, gameOffset) + mic);
            int right = saturatePcm16(readPcm16(gameStereo, gameOffset + 2) + mic);
            writePcm16(mixedStereo, gameOffset, left);
            writePcm16(mixedStereo, gameOffset + 2, right);
        }
    }

    private static int readPcm16(@NonNull byte[] buffer, int offset) {
        return (short) ((buffer[offset] & 0xff) | (buffer[offset + 1] << 8));
    }

    private static void writePcm16(@NonNull byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xff);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static int saturatePcm16(int value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return value;
    }

    private long audioPtsForQueuedFrames() {
        return (queuedAudioFrames * 1_000_000L) / AUDIO_SAMPLE_RATE;
    }

    private synchronized void queueAudioEosIfNeeded() {
        if (audioEosQueued) return;
        MediaCodec encoder = audioEncoder;
        if (encoder == null) {
            audioEosQueued = true;
            return;
        }

        long deadline = System.nanoTime() + 700_000_000L;
        while (!audioEosQueued && System.nanoTime() < deadline) {
            try {
                int inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            audioPtsForQueuedFrames(),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    );
                    audioEosQueued = true;
                    return;
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to queue audio EOS: " + throwable.getMessage());
                break;
            }
        }
        audioEosQueued = true;
    }

    private void drainEncoders() {
        MediaCodec video = videoEncoder;
        MediaCodec audio = audioEncoder;
        if (video == null || audio == null) return;

        MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        boolean videoDone = false;
        boolean audioDone = false;

        while (!forceDrainExit && (!videoDone || !audioDone)) {
            boolean progressed = false;
            if (!videoDone) {
                DrainStatus status = drainOne(video, true, videoInfo);
                progressed |= status.progressed;
                videoDone = status.eos;
            }
            if (!audioDone) {
                DrainStatus status = drainOne(audio, false, audioInfo);
                progressed |= status.progressed;
                audioDone = status.eos;
            }
            if (!progressed) {
                try {
                    Thread.sleep(4L);
                } catch (InterruptedException ignored) {
                    if (forceDrainExit) break;
                }
            }
        }
    }

    @NonNull
    private DrainStatus drainOne(
            @NonNull MediaCodec encoder,
            boolean video,
            @NonNull MediaCodec.BufferInfo info
    ) {
        int outputIndex;
        try {
            outputIndex = encoder.dequeueOutputBuffer(info, 0L);
        } catch (Throwable throwable) {
            Logging.e(TAG, (video ? "Video" : "Audio") + " encoder drain failed", throwable);
            return new DrainStatus(false, stopping.get());
        }

        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return new DrainStatus(false, false);
        }
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat format = encoder.getOutputFormat();
            RecordingDiagnostics.logTrackFormat(
                    "primary", video ? "video" : "audio", format);
            MediaMuxer activeMuxer = muxer;
            if (activeMuxer == null) return new DrainStatus(true, false);
            try {
                if (video) {
                    if (videoTrack >= 0) throw new IllegalStateException("Video format changed twice.");
                    videoTrack = activeMuxer.addTrack(format);
                } else {
                    if (audioTrack >= 0) throw new IllegalStateException("Audio format changed twice.");
                    audioTrack = activeMuxer.addTrack(format);
                }
                startMuxerWhenReady();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to add recording track", throwable);
                forceDrainExit = true;
            }
            return new DrainStatus(true, false);
        }
        if (outputIndex < 0) {
            return new DrainStatus(false, false);
        }

        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        try {
            ByteBuffer output = encoder.getOutputBuffer(outputIndex);
            if (output != null
                    && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    && muxerStarted) {
                if (video && !videoHasKeyframe) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        videoHasKeyframe = true;
                    }
                }

                if (!video || videoHasKeyframe) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    long originalPts = info.presentationTimeUs;
                    info.presentationTimeUs = normalizePts(video, originalPts);
                    MediaMuxer activeMuxer = muxer;
                    if (activeMuxer != null) {
                        activeMuxer.writeSampleData(video ? videoTrack : audioTrack, output, info);
                        wroteSamples = true;
                        if (video) {
                            wroteVideoSample = true;
                            videoSampleCount++;
                            videoEncodedBytes += Math.max(0, info.size);
                        } else {
                            wroteAudioSample = true;
                            audioSampleCount++;
                            audioEncodedBytes += Math.max(0, info.size);
                        }
                    }
                    info.presentationTimeUs = originalPts;
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write " + (video ? "video" : "audio") + " sample", throwable);
            forceDrainExit = true;
        } finally {
            try {
                encoder.releaseOutputBuffer(outputIndex, false);
            } catch (Throwable ignored) {
            }
        }

        return new DrainStatus(true, eos);
    }

    private void startMuxerWhenReady() {
        if (muxerStarted || videoTrack < 0 || audioTrack < 0 || muxer == null) return;
        muxer.start();
        muxerStarted = true;
        requestVideoSyncFrame();
    }

    private void requestVideoSyncFrame() {
        MediaCodec video = videoEncoder;
        if (video == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            video.setParameters(params);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to request initial recording keyframe: " + throwable.getMessage());
        }
    }

    private long normalizePts(boolean video, long ptsUs) {
        if (video) {
            if (firstVideoPtsUs == Long.MIN_VALUE) firstVideoPtsUs = ptsUs;
            return Math.max(0L, ptsUs - firstVideoPtsUs);
        }
        if (firstAudioPtsUs == Long.MIN_VALUE) firstAudioPtsUs = ptsUs;
        return Math.max(0L, ptsUs - firstAudioPtsUs);
    }

    private void releaseAll(boolean publishOutput) {
        forceDrainExit = true;

        if (cleanFrameCaptureSessionId != 0L) {
            RecordingFrameBridge.sendDetach(context, cleanFrameCaptureSessionId);
            cleanFrameCaptureSessionId = 0L;
            cleanFrameCaptureActive = false;
        }

        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback);
            } catch (Throwable ignored) {
            }
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
        }

        VirtualDisplay display = virtualDisplay;
        virtualDisplay = null;
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
        }

        AudioRecord gameRecorder = gameAudioRecord;
        gameAudioRecord = null;
        releaseAudioRecord(gameRecorder);

        AudioRecord micRecorder = microphoneAudioRecord;
        microphoneAudioRecord = null;
        releaseAudioRecord(micRecorder);

        releaseCodec(videoEncoder);
        videoEncoder = null;
        releaseCodec(audioEncoder);
        audioEncoder = null;

        Surface surface = videoInputSurface;
        videoInputSurface = null;
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
        }

        MediaMuxer activeMuxer = muxer;
        muxer = null;
        if (activeMuxer != null) {
            if (muxerStarted) {
                try {
                    activeMuxer.stop();
                } catch (Throwable throwable) {
                    publishOutput = false;
                    Logging.i(TAG, "Muxer stop failed: " + throwable.getMessage());
                }
            }
            try {
                activeMuxer.release();
            } catch (Throwable ignored) {
            }
        }

        ParcelFileDescriptor pfd = outputPfd;
        outputPfd = null;
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }

        boolean keepOutput = publishOutput && wroteSamples;
        long runtimeMs = sessionStartedElapsedMs > 0L
                ? SystemClock.elapsedRealtime() - sessionStartedElapsedMs
                : 0L;
        RecordingDiagnostics.logFinalHealth(
                context,
                "primary",
                displayName,
                outputUri,
                true,
                width,
                height,
                keepOutput,
                muxerStarted,
                videoHasKeyframe,
                videoSampleCount,
                audioSampleCount,
                videoEncodedBytes,
                audioEncodedBytes,
                runtimeMs
        );
        finishOutput(keepOutput);
    }

    private void finishOutput(boolean keep) {
        Uri uri = outputUri;
        if (uri == null) return;
        ContentResolver resolver = context.getContentResolver();
        try {
            if (keep) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            } else {
                resolver.delete(uri, null, null);
                outputUri = null;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to finalize recording output", throwable);
        }
    }

    private static void releaseCodec(@Nullable MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
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
        return (value & 1) == 0 ? value : value - 1;
    }

    private static final class DrainStatus {
        final boolean progressed;
        final boolean eos;

        DrainStatus(boolean progressed, boolean eos) {
            this.progressed = progressed;
            this.eos = eos;
        }
    }
}
