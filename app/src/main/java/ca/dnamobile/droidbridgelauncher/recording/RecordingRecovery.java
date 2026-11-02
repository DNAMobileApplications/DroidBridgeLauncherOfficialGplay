/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.recording;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/** Repairs DroidBridge-owned MediaStore rows orphaned by an abrupt :game process exit. */
public final class RecordingRecovery {
    private static final String TAG = "RecordingRecovery";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private RecordingRecovery() {
    }

    public static void recoverAsync(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        final Context appContext = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                recoverOrphanedPendingOutputs(appContext);
            } finally {
                RUNNING.set(false);
            }
        }, "DroidBridgeRecordingRecovery").start();
    }

    private static void recoverOrphanedPendingOutputs(@NonNull Context context) {
        // A live game still owns the recording session. Do not interfere.
        if (isNamedProcessRunning(context, context.getPackageName() + ":game")) return;

        // The recorder now lives in its own process. When Minecraft has exited but that
        // process is still alive, request an orderly stop and let MediaMuxer publish the MP4.
        // Never run orphan cleanup concurrently with that normal finalization transaction.
        if (RecordingProcessState.isBusy(context)) {
            String recordingProcess = context.getPackageName() + ":recording";
            if (isNamedProcessRunning(context, recordingProcess)) {
                Logging.i(TAG, "Minecraft process is gone; requesting recording finalization");
                GameRecordingService.stop(context);
                return;
            }

            // :recording disappeared unexpectedly. Its state marker is stale; clear it and
            // recover/delete the MediaStore orphan below.
            Logging.i(TAG, "Clearing stale recording state after recorder process exit");
            RecordingProcessState.clear(context);
        }

        ContentResolver resolver = context.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE
        };
        String selection = MediaStore.Video.Media.IS_PENDING + "=1 AND ("
                + MediaStore.Video.Media.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?) AND "
                + MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = {
                "DroidBridge_Minecraft_%",
                "DroidBridge_SecondScreen_%",
                "%DroidBridge/Recordings%"
        };

        try (Cursor cursor = resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                long size = Math.max(0L, cursor.getLong(sizeColumn));
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                if (isPlayableMp4(context, uri)) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Video.Media.IS_PENDING, 0);
                        int changed = resolver.update(uri, values, null, null);
                        Logging.i(TAG, "Recovered finalized pending recording name=" + name
                                + " bytes=" + size + " rows=" + changed);
                    } catch (Throwable throwable) {
                        Logging.e(TAG, "Unable to publish recovered recording " + name, throwable);
                    }
                } else {
                    // A MediaMuxer that was killed before writing its final MP4 metadata cannot
                    // be made playable by merely clearing IS_PENDING. Remove that broken row so
                    // it cannot remain as an eternal Pending item in gallery/file apps.
                    try {
                        resolver.delete(uri, null, null);
                        Logging.i(TAG, "Removed incomplete orphaned pending recording name="
                                + name + " bytes=" + size);
                    } catch (Throwable throwable) {
                        Logging.e(TAG, "Unable to remove incomplete pending recording " + name,
                                throwable);
                    }
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to scan for orphaned pending recordings", throwable);
        }
    }

    private static boolean isPlayableMp4(@NonNull Context context, @NonNull Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null) return false;
            return Long.parseLong(duration) > 0L;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isNamedProcessRunning(
            @NonNull Context context,
            @NonNull String processName
    ) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes;
        try {
            processes = manager.getRunningAppProcesses();
        } catch (Throwable ignored) {
            return false;
        }
        if (processes == null) return false;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process != null && processName.equals(process.processName)) return true;
        }
        return false;
    }
}
