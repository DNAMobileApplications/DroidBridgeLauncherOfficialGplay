/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.recording;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Tiny cross-process state bridge between DroidBridge's :game, launcher and :recording
 * processes. The recorder deliberately lives outside :game so Minecraft may exit without
 * killing MediaMuxer before its MP4 footer / MediaStore publish step completes.
 */
final class RecordingProcessState {
    private static final String TAG = "RecordingProcessState";
    private static final String FILE_NAME = "droidbridge_recording_session.state";

    static final String IDLE = "idle";
    static final String STARTING = "starting";
    static final String RECORDING = "recording";
    static final String FINALIZING = "finalizing";

    private RecordingProcessState() {
    }

    static boolean isBusy(@NonNull Context context) {
        String state = read(context);
        return STARTING.equals(state) || RECORDING.equals(state) || FINALIZING.equals(state);
    }

    static boolean isRecording(@NonNull Context context) {
        return RECORDING.equals(read(context));
    }

    static boolean isFinalizing(@NonNull Context context) {
        return FINALIZING.equals(read(context));
    }

    static void markStarting(@NonNull Context context) {
        write(context, STARTING);
    }

    static void markRecording(@NonNull Context context) {
        write(context, RECORDING);
    }

    static void markFinalizing(@NonNull Context context) {
        write(context, FINALIZING);
    }

    static void clear(@NonNull Context context) {
        write(context, IDLE);
    }

    @NonNull
    private static String read(@NonNull Context context) {
        File file = stateFile(context);
        if (!file.isFile()) return IDLE;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            return value == null ? IDLE : value.trim();
        } catch (Throwable throwable) {
            return IDLE;
        }
    }

    private static void write(@NonNull Context context, @NonNull String value) {
        File file = stateFile(context);
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream stream = new FileOutputStream(tmp, false)) {
            stream.write(bytes);
            stream.flush();
            try {
                stream.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to persist recording state=" + value, throwable);
            return;
        }
        if (!tmp.renameTo(file)) {
            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(bytes);
                stream.flush();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to replace recording state=" + value, throwable);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @NonNull
    private static File stateFile(@NonNull Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
