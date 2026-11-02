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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Structured diagnostics for the experimental DroidBridge recorder.
 *
 * This class is intentionally observational: it records the codec chosen by Android, the
 * requested encoder format, MediaCodec diagnostic details, and a lightweight MP4 integrity
 * check when capture stops. It does not change encoder selection or recording quality.
 */
final class RecordingDiagnostics {
    private static final String TAG = "RecordingHealth";
    private static volatile boolean initialized;

    private RecordingDiagnostics() {
    }

    static void initialize(@NonNull Context context) {
        if (initialized) return;
        synchronized (RecordingDiagnostics.class) {
            if (initialized) return;
            // The recording service can be created independently of the launcher activity. Ensure
            // launcherlogs.txt is initialized in whichever process owns the service before any codec
            // failure is reported.
            Logging.init(context.getApplicationContext());
            Logging.i(TAG, "Recording diagnostics enabled (experimental recorder health logger v1)");
            initialized = true;
        }
    }

    static void logStartRequest(
            @NonNull Context context,
            @NonNull String mode,
            @NonNull String fileName,
            int width,
            int height,
            int densityDpi,
            int frameRate,
            boolean expectAudio
    ) {
        initialize(context);
        Logging.i(TAG, "START mode=" + mode
                + " file=" + safeFileName(fileName)
                + " requested=" + width + "x" + height + "@" + frameRate
                + " density=" + densityDpi
                + " audioExpected=" + expectAudio
                + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " api=" + Build.VERSION.SDK_INT
                + " package=" + context.getPackageName());
    }

    static void logVideoCodec(
            @NonNull String mode,
            @Nullable MediaCodec codec,
            @NonNull MediaFormat format,
            int width,
            int height,
            int frameRate,
            int bitrate
    ) {
        StringBuilder message = new StringBuilder();
        message.append("VIDEO_CODEC mode=").append(mode)
                .append(" codec=").append(codecName(codec))
                .append(" requested=").append(width).append('x').append(height)
                .append('@').append(frameRate)
                .append(" bitrate=").append(bitrate)
                .append(" format=").append(format);

        if (codec != null) {
            try {
                MediaCodecInfo info = codec.getCodecInfo();
                appendCodecFlags(message, info);
                MediaCodecInfo.CodecCapabilities capabilities =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                if (video != null) {
                    boolean sizeSupported = video.isSizeSupported(width, height);
                    boolean sizeRateSupported = sizeSupported
                            && video.areSizeAndRateSupported(width, height, frameRate);
                    message.append(" sizeSupported=").append(sizeSupported)
                            .append(" sizeRateSupported=").append(sizeRateSupported)
                            .append(" widthRange=").append(video.getSupportedWidths())
                            .append(" heightRange=").append(video.getSupportedHeights())
                            .append(" fpsRange=").append(video.getSupportedFrameRates())
                            .append(" bitrateRange=").append(video.getBitrateRange())
                            .append(" alignment=")
                            .append(video.getWidthAlignment()).append('x')
                            .append(video.getHeightAlignment());
                }
            } catch (Throwable capabilityError) {
                message.append(" capabilityQueryError=")
                        .append(compactThrowable(capabilityError));
            }
        }

        Logging.i(TAG, message.toString());
    }

    static void logAudioCodec(
            @NonNull String mode,
            @Nullable MediaCodec codec,
            @NonNull MediaFormat format,
            int sampleRate,
            int channelCount,
            int bitrate
    ) {
        StringBuilder message = new StringBuilder();
        message.append("AUDIO_CODEC mode=").append(mode)
                .append(" codec=").append(codecName(codec))
                .append(" requested=").append(sampleRate).append("Hz/")
                .append(channelCount).append("ch")
                .append(" bitrate=").append(bitrate)
                .append(" format=").append(format);

        if (codec != null) {
            try {
                MediaCodecInfo info = codec.getCodecInfo();
                appendCodecFlags(message, info);
                MediaCodecInfo.CodecCapabilities capabilities =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC);
                MediaCodecInfo.AudioCapabilities audio = capabilities.getAudioCapabilities();
                if (audio != null) {
                    message.append(" sampleRateSupported=")
                            .append(audio.isSampleRateSupported(sampleRate))
                            .append(" maxChannels=").append(audio.getMaxInputChannelCount())
                            .append(" bitrateRange=").append(audio.getBitrateRange());
                }
            } catch (Throwable capabilityError) {
                message.append(" capabilityQueryError=")
                        .append(compactThrowable(capabilityError));
            }
        }

        Logging.i(TAG, message.toString());
    }

    static void logCodecFailure(
            @NonNull String mode,
            @NonNull String stage,
            @Nullable MediaCodec codec,
            @Nullable MediaFormat format,
            @NonNull Throwable throwable
    ) {
        Logging.e(TAG,
                "CODEC_FAILURE mode=" + mode
                        + " stage=" + stage
                        + " codec=" + codecName(codec)
                        + " format=" + (format == null ? "<none>" : format.toString())
                        + " detail=" + describeThrowable(throwable));
    }

    static void logSessionFailure(
            @NonNull String mode,
            @NonNull String fileName,
            @NonNull String stage,
            @NonNull Throwable throwable
    ) {
        Logging.e(TAG,
                "START_FAILED mode=" + mode
                        + " file=" + safeFileName(fileName)
                        + " stage=" + stage
                        + " detail=" + describeThrowable(throwable),
                throwable);
    }

    static void logTrackFormat(
            @NonNull String mode,
            @NonNull String track,
            @NonNull MediaFormat format
    ) {
        Logging.i(TAG, "TRACK_READY mode=" + mode
                + " track=" + track
                + " format=" + format);
    }

    static void logFinalHealth(
            @NonNull Context context,
            @NonNull String mode,
            @NonNull String fileName,
            @Nullable Uri uri,
            boolean expectAudio,
            int expectedWidth,
            int expectedHeight,
            boolean intendedToKeep,
            boolean muxerStarted,
            boolean sawVideoKeyframe,
            long videoSamples,
            long audioSamples,
            long videoBytes,
            long audioBytes,
            long runtimeMs
    ) {
        if (uri == null) {
            Logging.e(TAG, "FINAL_HEALTH result=BROKEN mode=" + mode
                    + " file=" + safeFileName(fileName)
                    + " reason=no-output-uri"
                    + runtimeSummary(intendedToKeep, muxerStarted, sawVideoKeyframe,
                    videoSamples, audioSamples, videoBytes, audioBytes, runtimeMs));
            return;
        }

        Inspection inspection = inspectOutput(context, uri, expectAudio, expectedWidth, expectedHeight);
        if (!muxerStarted) {
            inspection.brokenReasons.add("runtime-muxer-never-started");
        }
        if (videoSamples <= 0L) {
            inspection.brokenReasons.add("runtime-no-video-samples");
        }
        if (expectAudio && audioSamples <= 0L) {
            inspection.brokenReasons.add("runtime-no-audio-samples");
        }
        if (!sawVideoKeyframe) {
            inspection.warningReasons.add("runtime-no-keyframe-observed");
        }
        if (!intendedToKeep) {
            inspection.warningReasons.add("output-marked-for-discard");
        }

        String result;
        if (!inspection.brokenReasons.isEmpty()) {
            result = "BROKEN";
        } else if (!inspection.warningReasons.isEmpty()) {
            result = "WARNING";
        } else {
            result = "OK";
        }

        String message = "FINAL_HEALTH result=" + result
                + " mode=" + mode
                + " file=" + safeFileName(fileName)
                + " uri=" + uri
                + " fileBytes=" + inspection.fileBytes
                + " tracks=" + inspection.trackCount
                + " video=" + inspection.videoMime
                + " audio=" + inspection.audioMime
                + " parsedSize=" + inspection.videoWidth + "x" + inspection.videoHeight
                + " durationMs=" + inspection.durationMs
                + " brokenReasons=" + joinReasons(inspection.brokenReasons)
                + " warnings=" + joinReasons(inspection.warningReasons)
                + runtimeSummary(intendedToKeep, muxerStarted, sawVideoKeyframe,
                videoSamples, audioSamples, videoBytes, audioBytes, runtimeMs);

        if ("BROKEN".equals(result)) {
            Logging.e(TAG, message);
        } else {
            Logging.i(TAG, message);
        }
    }

    @NonNull
    static String describeThrowable(@NonNull Throwable throwable) {
        StringBuilder result = new StringBuilder(compactThrowable(throwable));
        MediaCodec.CodecException codecException = findCodecException(throwable);
        if (codecException != null) {
            try {
                result.append(" diagnostic=").append(codecException.getDiagnosticInfo());
            } catch (Throwable ignored) {
            }
            try {
                result.append(" recoverable=").append(codecException.isRecoverable())
                        .append(" transient=").append(codecException.isTransient());
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    result.append(" errorCode=").append(codecException.getErrorCode());
                } catch (Throwable ignored) {
                }
            }
        }
        return result.toString();
    }

    @NonNull
    static String shortError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        if (message != null && !message.trim().isEmpty()) return message.trim();
        MediaCodec.CodecException codecException = findCodecException(throwable);
        if (codecException != null) {
            try {
                String diagnostic = codecException.getDiagnosticInfo();
                if (diagnostic != null && !diagnostic.trim().isEmpty()) {
                    return "CodecException (" + diagnostic.trim() + ")";
                }
            } catch (Throwable ignored) {
            }
        }
        return throwable.getClass().getSimpleName();
    }

    @NonNull
    private static Inspection inspectOutput(
            @NonNull Context context,
            @NonNull Uri uri,
            boolean expectAudio,
            int expectedWidth,
            int expectedHeight
    ) {
        Inspection result = new Inspection();

        try (ParcelFileDescriptor descriptor = context.getContentResolver()
                .openFileDescriptor(uri, "r")) {
            if (descriptor == null) {
                result.brokenReasons.add("output-not-openable");
            } else {
                result.fileBytes = descriptor.getStatSize();
                if (result.fileBytes == 0L) {
                    result.brokenReasons.add("zero-byte-file");
                }
            }
        } catch (Throwable throwable) {
            result.brokenReasons.add("file-open-failed:" + compactThrowable(throwable));
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            result.trackCount = extractor.getTrackCount();
            if (result.trackCount <= 0) {
                result.brokenReasons.add("no-media-tracks");
            }

            long longestDurationUs = -1L;
            for (int i = 0; i < result.trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) mime = "<unknown>";

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    try {
                        longestDurationUs = Math.max(longestDurationUs,
                                format.getLong(MediaFormat.KEY_DURATION));
                    } catch (Throwable ignored) {
                    }
                }

                if (mime.startsWith("video/") && result.videoMime == null) {
                    result.videoMime = mime;
                    result.videoWidth = getInt(format, MediaFormat.KEY_WIDTH, -1);
                    result.videoHeight = getInt(format, MediaFormat.KEY_HEIGHT, -1);
                } else if (mime.startsWith("audio/") && result.audioMime == null) {
                    result.audioMime = mime;
                }
            }

            if (longestDurationUs >= 0L) {
                result.durationMs = longestDurationUs / 1000L;
            }
        } catch (Throwable throwable) {
            result.brokenReasons.add("container-parse-failed:" + compactThrowable(throwable));
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }

        if (result.videoMime == null) {
            result.brokenReasons.add("missing-video-track");
        }
        if (expectAudio && result.audioMime == null) {
            result.brokenReasons.add("missing-audio-track");
        }
        if (result.durationMs == 0L) {
            result.brokenReasons.add("zero-duration");
        } else if (result.durationMs < 0L) {
            result.warningReasons.add("duration-unavailable");
        }

        if (result.videoMime != null
                && expectedWidth > 0 && expectedHeight > 0
                && result.videoWidth > 0 && result.videoHeight > 0
                && (result.videoWidth != expectedWidth || result.videoHeight != expectedHeight)) {
            result.warningReasons.add("dimension-mismatch-expected-"
                    + expectedWidth + "x" + expectedHeight);
        }

        if (result.fileBytes > 0L && result.fileBytes < 32L * 1024L) {
            result.warningReasons.add("very-small-file");
        }

        return result;
    }

    private static int getInt(@NonNull MediaFormat format, @NonNull String key, int fallback) {
        if (!format.containsKey(key)) return fallback;
        try {
            return format.getInteger(key);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void appendCodecFlags(
            @NonNull StringBuilder message,
            @NonNull MediaCodecInfo info
    ) {
        message.append(" encoder=").append(info.isEncoder());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                message.append(" hardware=").append(info.isHardwareAccelerated())
                        .append(" software=").append(info.isSoftwareOnly())
                        .append(" vendor=").append(info.isVendor());
            } catch (Throwable ignored) {
            }
        }
    }

    @NonNull
    private static String codecName(@Nullable MediaCodec codec) {
        if (codec == null) return "<not-created>";
        try {
            return codec.getName();
        } catch (Throwable throwable) {
            return "<unknown>";
        }
    }

    @Nullable
    private static MediaCodec.CodecException findCodecException(@NonNull Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof MediaCodec.CodecException) {
                return (MediaCodec.CodecException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    @NonNull
    private static String compactThrowable(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        String simple = throwable.getClass().getSimpleName();
        if (message == null || message.trim().isEmpty()) return simple;
        return simple + ":" + message.trim().replace('\n', ' ').replace('\r', ' ');
    }

    @NonNull
    private static String runtimeSummary(
            boolean intendedToKeep,
            boolean muxerStarted,
            boolean sawVideoKeyframe,
            long videoSamples,
            long audioSamples,
            long videoBytes,
            long audioBytes,
            long runtimeMs
    ) {
        return " intendedToKeep=" + intendedToKeep
                + " muxerStarted=" + muxerStarted
                + " keyframe=" + sawVideoKeyframe
                + " videoSamples=" + videoSamples
                + " audioSamples=" + audioSamples
                + " encodedVideoBytes=" + videoBytes
                + " encodedAudioBytes=" + audioBytes
                + " runtimeMs=" + Math.max(0L, runtimeMs);
    }

    @NonNull
    private static String joinReasons(@NonNull List<String> reasons) {
        if (reasons.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (String reason : reasons) {
            if (builder.length() > 0) builder.append('|');
            builder.append(reason);
        }
        return builder.toString();
    }

    @NonNull
    private static String safeFileName(@Nullable String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return "<not-created>";
        return fileName.trim();
    }

    private static final class Inspection {
        long fileBytes = -1L;
        int trackCount = -1;
        @Nullable String videoMime;
        @Nullable String audioMime;
        int videoWidth = -1;
        int videoHeight = -1;
        long durationMs = -1L;
        @NonNull final List<String> brokenReasons = new ArrayList<>();
        @NonNull final List<String> warningReasons = new ArrayList<>();
    }
}
