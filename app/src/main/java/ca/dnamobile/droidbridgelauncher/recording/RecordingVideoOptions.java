/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */
package ca.dnamobile.droidbridgelauncher.recording;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;

import androidx.annotation.NonNull;

/** Video targets for both the primary game and optional second-display recorder. */
public final class RecordingVideoOptions {
    private RecordingVideoOptions() { }

    public static final class Target {
        public final int width;
        public final int height;
        public final int frameRate;
        public final int bitrate;

        public Target(int width, int height, int frameRate, int bitrate) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
        }
    }

    @NonNull
    public static Target requested(
            int sourceWidth, int sourceHeight, int frameRate, @NonNull String resolution,
            @NonNull String preset
    ) {
        int[] size = sizeFor(sourceWidth, sourceHeight, resolution);
        return new Target(size[0], size[1], frameRate,
                bitrateFor(size[0], size[1], frameRate, preset));
    }

    /** 720p/1080p constrain the longer source edge; never upscale small screens. */
    @NonNull
    public static int[] sizeFor(int sourceWidth, int sourceHeight, @NonNull String resolution) {
        int width = Math.max(2, sourceWidth);
        int height = Math.max(2, sourceHeight);
        int bound = RecordingPreferences.RESOLUTION_720P.equals(resolution) ? 1280
                : RecordingPreferences.RESOLUTION_1080P.equals(resolution) ? 1920
                : "480p".equals(resolution) ? 854 : 0;
        int longEdge = Math.max(width, height);
        if (bound > 0 && longEdge > bound) {
            double scale = bound / (double) longEdge;
            width = (int) Math.round(width * scale);
            height = (int) Math.round(height * scale);
        }
        return new int[]{even(width), even(height)};
    }

    /** Encoder bitrates (bits/sec) scaled by actual resolution, fps and selected preset. */
    public static int bitrateFor(int width, int height, int fps, @NonNull String preset) {
        final double bitsPerPixelPerFrame;
        final int minBitrate;
        final int maxBitrate;
        switch (preset) {
            case RecordingPreferences.PRESET_LOW:
                bitsPerPixelPerFrame = 0.10;
                minBitrate = 2_000_000;
                maxBitrate = 6_000_000;
                break;
            case RecordingPreferences.PRESET_MEDIUM:
                bitsPerPixelPerFrame = 0.085;
                minBitrate = 4_000_000;
                maxBitrate = 20_000_000;
                break;
            case RecordingPreferences.PRESET_HIGH:
                bitsPerPixelPerFrame = 0.13;
                minBitrate = 8_000_000;
                maxBitrate = 40_000_000;
                break;
            default:
                bitsPerPixelPerFrame = 0.10;
                minBitrate = 4_000_000;
                maxBitrate = 36_000_000;
                break;
        }
        long target = Math.round((double) width * height * fps * bitsPerPixelPerFrame);
        return (int) Math.max(minBitrate, Math.min(maxBitrate, target));
    }

    /**
     * Check the actual device H.264 encoder before creating the capture surface. If the chosen
     * profile exceeds hardware capabilities, first reduce FPS, then the encoded resolution.
     * Actual game / display refresh may still be lower than the accepted encoder frame rate.
     */
    @NonNull
    public static Target supported(@NonNull MediaCodec encoder, @NonNull Target desired,
                                   @NonNull String preset) {
        final MediaCodecInfo.VideoCapabilities video;
        try {
            video = encoder.getCodecInfo()
                    .getCapabilitiesForType("video/avc").getVideoCapabilities();
        } catch (RuntimeException unavailable) {
            return desired;
        }
        if (video == null) return desired;
        int[][] choices = {
                {desired.width, desired.height},
                sizeFor(desired.width, desired.height, RecordingPreferences.RESOLUTION_1080P),
                sizeFor(desired.width, desired.height, RecordingPreferences.RESOLUTION_720P),
                sizeFor(desired.width, desired.height, "480p")
        };
        for (int[] choice : choices) {
            int w = alignDown(choice[0], video.getWidthAlignment());
            int h = alignDown(choice[1], video.getHeightAlignment());
            for (int fps : new int[]{120, 90, 60, 30}) {
                if (fps > desired.frameRate) continue;
                try {
                    if (video.areSizeAndRateSupported(w, h, fps)) {
                        int calculated = bitrateFor(w, h, fps, preset);
                        int clamped = Math.max(video.getBitrateRange().getLower(),
                                Math.min(video.getBitrateRange().getUpper(), calculated));
                        return new Target(w, h, fps, clamped);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        throw new IllegalArgumentException("Device H.264 encoder cannot support the selected screen-recording settings, even at 30 FPS. Select a lower recording resolution.");
    }

    private static int even(int n) {
        return Math.max(2, n & ~1);
    }

    private static int alignDown(int value, int alignment) {
        int validAlignment = Math.max(2, alignment);
        return Math.max(validAlignment, (value / validAlignment) * validAlignment);
    }
}
