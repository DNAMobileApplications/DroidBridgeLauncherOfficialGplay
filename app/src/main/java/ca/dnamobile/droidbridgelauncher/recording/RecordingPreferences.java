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
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Preferences shared between the launcher, :game, and :recording processes. */
public final class RecordingPreferences {
    private static final String PREFS_NAME = "droidbridge_recording_preferences";
    private static final String KEY_HIDE_TOUCH_CONTROLS = "hide_touch_controls_in_recording";
    private static final String KEY_RECORD_BOTH_SCREENS = "record_both_screens";
    private static final String KEY_AUDIO_MODE = "recording_audio_mode";
    private static final String KEY_VIDEO_PRESET = "recording_video_preset";
    private static final String KEY_VIDEO_FRAME_RATE = "recording_video_frame_rate";
    private static final String KEY_VIDEO_RESOLUTION = "recording_video_resolution";
    private static final String KEY_VIDEO_EXPLICIT = "recording_video_explicit_selection";

    public static final String PRESET_CUSTOM = "custom";
    public static final String PRESET_LOW = "low";
    public static final String PRESET_MEDIUM = "medium";
    public static final String PRESET_HIGH = "high";
    public static final String RESOLUTION_NATIVE = "native";
    public static final String RESOLUTION_720P = "720p";
    public static final String RESOLUTION_1080P = "1080p";

    public static final String AUDIO_MODE_GAME_ONLY = "game_only";
    public static final String AUDIO_MODE_VOICE_ONLY = "voice_only";
    public static final String AUDIO_MODE_VOICE_AND_GAME = "voice_and_game";

    private RecordingPreferences() {
    }

    @NonNull
    public static String getVideoPreset(@NonNull Context context) {
        String stored = prefs(context).getString(KEY_VIDEO_PRESET, PRESET_CUSTOM);
        if (PRESET_LOW.equals(stored) || PRESET_MEDIUM.equals(stored)
                || PRESET_HIGH.equals(stored)) return stored;
        return PRESET_CUSTOM;
    }

    public static void setVideoPreset(@NonNull Context context, @NonNull String preset) {
        String normalized = PRESET_LOW.equals(preset) || PRESET_MEDIUM.equals(preset)
                || PRESET_HIGH.equals(preset) ? preset : PRESET_CUSTOM;
        prefs(context).edit().putString(KEY_VIDEO_PRESET, normalized)
                .putBoolean(KEY_VIDEO_EXPLICIT, true).commit();
    }

    /** Distinguishes older default 30-FPS clean capture from an explicit 60-FPS choice. */
    public static boolean isVideoConfigurationExplicit(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_VIDEO_EXPLICIT, false);
    }

    public static int getVideoFrameRate(@NonNull Context context) {
        String preset = getVideoPreset(context);
        if (PRESET_LOW.equals(preset)) return 30;
        if (PRESET_MEDIUM.equals(preset)) return 60;
        if (PRESET_HIGH.equals(preset)) return 120;
        return normalizeFrameRate(prefs(context).getInt(KEY_VIDEO_FRAME_RATE, 60));
    }

    @NonNull
    public static String getVideoResolution(@NonNull Context context) {
        String preset = getVideoPreset(context);
        if (PRESET_LOW.equals(preset)) return RESOLUTION_720P;
        if (PRESET_MEDIUM.equals(preset)) return RESOLUTION_1080P;
        if (PRESET_HIGH.equals(preset)) return RESOLUTION_NATIVE;
        return normalizeResolution(prefs(context).getString(KEY_VIDEO_RESOLUTION, RESOLUTION_NATIVE));
    }

    /** Selecting an individual setting switches to Custom while preserving the other choice. */
    public static void setVideoFrameRate(@NonNull Context context, int value) {
        String currentResolution = getVideoResolution(context);
        prefs(context).edit().putInt(KEY_VIDEO_FRAME_RATE, normalizeFrameRate(value))
                .putString(KEY_VIDEO_RESOLUTION, currentResolution)
                .putString(KEY_VIDEO_PRESET, PRESET_CUSTOM)
                .putBoolean(KEY_VIDEO_EXPLICIT, true).commit();
    }

    public static void setVideoResolution(@NonNull Context context, @NonNull String value) {
        int currentFrameRate = getVideoFrameRate(context);
        prefs(context).edit().putString(KEY_VIDEO_RESOLUTION, normalizeResolution(value))
                .putInt(KEY_VIDEO_FRAME_RATE, currentFrameRate)
                .putString(KEY_VIDEO_PRESET, PRESET_CUSTOM)
                .putBoolean(KEY_VIDEO_EXPLICIT, true).commit();
    }

    public static int normalizeFrameRate(int fps) {
        return fps == 30 || fps == 60 || fps == 90 || fps == 120 ? fps : 60;
    }

    @NonNull
    public static String normalizeResolution(@Nullable String value) {
        if (RESOLUTION_720P.equals(value)) return RESOLUTION_720P;
        if (RESOLUTION_1080P.equals(value)) return RESOLUTION_1080P;
        return RESOLUTION_NATIVE;
    }

    @NonNull
    public static String presetLabel(@NonNull String preset) {
        if (PRESET_LOW.equals(preset)) return "Low Quality";
        if (PRESET_MEDIUM.equals(preset)) return "Medium Quality";
        if (PRESET_HIGH.equals(preset)) return "High Quality";
        return "Custom";
    }

    @NonNull
    public static String resolutionLabel(@NonNull String resolution) {
        if (RESOLUTION_720P.equals(resolution)) return "720p (up to 1280 x 720)";
        if (RESOLUTION_1080P.equals(resolution)) return "1080p (up to 1920 x 1080)";
        return "Native resolution";
    }

    @NonNull
    public static RecordingVideoOptions.Target getVideoTarget(
            @NonNull Context context, int sourceWidth, int sourceHeight
    ) {
        return RecordingVideoOptions.requested(sourceWidth, sourceHeight,
                getVideoFrameRate(context), getVideoResolution(context), getVideoPreset(context));
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    /**
     * When enabled, DroidBridge records Minecraft from its render surface instead of from the
     * final composed display. The live touch controls remain visible and usable by the player,
     * while their artwork is outside the video source. If clean render-surface capture cannot
     * attach, the recorder falls back to ordinary MediaProjection instead of producing black video.
     */
    public static boolean isHideTouchControlsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_HIDE_TOUCH_CONTROLS, false);
    }

    /**
     * When enabled and DroidBridge dual-screen mode is active, recording captures the
     * Minecraft/game display plus the HUD/touch display as a matched pair of MP4 files.
     */
    public static boolean isRecordBothScreensEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_RECORD_BOTH_SCREENS, false);
    }

    @NonNull
    public static String getAudioMode(@NonNull Context context) {
        return normalizeAudioMode(prefs(context).getString(KEY_AUDIO_MODE, AUDIO_MODE_GAME_ONLY));
    }

    public static void setAudioMode(@NonNull Context context, @NonNull String mode) {
        String sanitized = normalizeAudioMode(mode);
        boolean saved = prefs(context).edit().putString(KEY_AUDIO_MODE, sanitized).commit();
        if (!saved) {
            prefs(context).edit().putString(KEY_AUDIO_MODE, sanitized).apply();
        }
    }

    public static boolean includesGameAudio(@NonNull Context context) {
        String mode = getAudioMode(context);
        return AUDIO_MODE_GAME_ONLY.equals(mode) || AUDIO_MODE_VOICE_AND_GAME.equals(mode);
    }

    public static boolean includesMicrophone(@NonNull Context context) {
        String mode = getAudioMode(context);
        return AUDIO_MODE_VOICE_ONLY.equals(mode) || AUDIO_MODE_VOICE_AND_GAME.equals(mode);
    }
    @NonNull
    public static String getAudioModeLabel(@NonNull Context context) {
        return getAudioModeLabel(getAudioMode(context));
    }
    @NonNull
    public static String getAudioModeLabel(@NonNull String mode) {
        String sanitized = normalizeAudioMode(mode);
        if (AUDIO_MODE_VOICE_ONLY.equals(sanitized)) return "Voice Only Audio";
        if (AUDIO_MODE_VOICE_AND_GAME.equals(sanitized)) return "Voice & Game Audio";
        return "Game Only Audio";
    }

    @NonNull
    public static String normalizeAudioMode(@Nullable String mode) {
        if (AUDIO_MODE_VOICE_ONLY.equals(mode)) return AUDIO_MODE_VOICE_ONLY;
        if (AUDIO_MODE_VOICE_AND_GAME.equals(mode)) return AUDIO_MODE_VOICE_AND_GAME;
        return AUDIO_MODE_GAME_ONLY;
    }

    public static void setRecordBothScreensEnabled(@NonNull Context context, boolean enabled) {
        boolean saved = prefs(context).edit().putBoolean(KEY_RECORD_BOTH_SCREENS, enabled).commit();
        if (!saved) {
            prefs(context).edit().putBoolean(KEY_RECORD_BOTH_SCREENS, enabled).apply();
        }
    }

    public static void setHideTouchControlsEnabled(@NonNull Context context, boolean enabled) {
        boolean saved = prefs(context).edit().putBoolean(KEY_HIDE_TOUCH_CONTROLS, enabled).commit();
        if (!saved) {
            prefs(context).edit().putBoolean(KEY_HIDE_TOUCH_CONTROLS, enabled).apply();
        }
    }
}
