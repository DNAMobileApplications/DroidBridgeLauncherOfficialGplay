/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dev.horizon.android.HorizonInstaller;

/**
 * Prepares the Horizon engine's Minecraft 26.3 resources before the game starts.
 *
 * Runs in the :horizon process (see the manifest), so the engine library never loads into
 * the launcher. Progress goes back to HorizonLaunchActivity through a ResultReceiver, which
 * shows it in DroidBridge's own dialog instead of Horizon's launcher screen.
 */
public final class HorizonInstallService extends Service {
    private static final String TAG = "DroidBridgeHorizon";

    public static final String EXTRA_RECEIVER = "ca.dnamobile.droidbridgelauncher.horizon.install.RECEIVER";
    public static final String EXTRA_HOME = "ca.dnamobile.droidbridgelauncher.horizon.install.HOME";

    public static final int RESULT_PROGRESS = 1;
    public static final int RESULT_READY = 2;
    public static final int RESULT_FAILED = 3;

    public static final String KEY_STAGE = "stage";
    public static final String KEY_STAGE_COUNT = "stageCount";
    public static final String KEY_LABEL = "label";
    public static final String KEY_DONE = "done";
    public static final String KEY_TOTAL = "total";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_ERROR = "error";

    private static final String ENGINE_DATA = "engine-data.zip";

    private final Object lock = new Object();
    /** The latest launch asking for progress; a new Play press replaces an older one. */
    @Nullable private ResultReceiver receiver;
    private boolean running;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ResultReceiver newReceiver = intent == null ? null : intent.getParcelableExtra(EXTRA_RECEIVER);
        String home = intent == null ? null : intent.getStringExtra(EXTRA_HOME);
        if (newReceiver == null || home == null || home.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        synchronized (lock) {
            receiver = newReceiver;
            if (running) {
                // The install already in progress reports to the new receiver.
                return START_NOT_STICKY;
            }
            running = true;
        }
        Thread worker = new Thread(() -> {
            try {
                runInstall(new File(home));
            } finally {
                synchronized (lock) {
                    running = false;
                }
                stopSelf();
            }
        }, "DroidBridgeHorizonInstall");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runInstall(@NonNull File home) {
        try {
            if (!home.isDirectory() && !home.mkdirs()) {
                fail("Cannot create " + home);
                return;
            }
            if (HorizonInstaller.isReady(home.getAbsolutePath())) {
                send(RESULT_READY, new Bundle());
                return;
            }
            copyEngineData(home);
            File cache = new File(getCacheDir(), "horizon");
            if (!cache.isDirectory() && !cache.mkdirs()) {
                fail("Cannot create " + cache);
                return;
            }
            String error = HorizonInstaller.install(home.getAbsolutePath(), cache.getAbsolutePath(),
                    (stageIndex, label, done, total) -> {
                        Bundle progress = new Bundle();
                        progress.putInt(KEY_STAGE, stageIndex);
                        progress.putInt(KEY_STAGE_COUNT, HorizonInstaller.STAGE_COUNT);
                        progress.putString(KEY_LABEL, label);
                        progress.putLong(KEY_DONE, done);
                        progress.putLong(KEY_TOTAL, total);
                        progress.putBoolean(KEY_BYTES, stageIndex == HorizonInstaller.STAGE_DOWNLOAD);
                        send(RESULT_PROGRESS, progress);
                    });
            if (error == null && HorizonInstaller.isReady(home.getAbsolutePath())) {
                send(RESULT_READY, new Bundle());
            } else {
                fail(error == null ? "The installation did not complete." : error);
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "Horizon resource install failed", throwable);
            fail(throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage());
        }
    }

    /** The installer unpacks the engine's generated tables from the APK copy in its home. */
    private void copyEngineData(@NonNull File home) throws IOException {
        File target = new File(home, ENGINE_DATA);
        File partial = new File(home, ENGINE_DATA + ".host-partial");
        try (InputStream input = getAssets().open(ENGINE_DATA);
             OutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace " + target);
        }
        if (!partial.renameTo(target)) {
            throw new IOException("Cannot write " + target);
        }
    }

    private void fail(@NonNull String message) {
        Bundle data = new Bundle();
        data.putString(KEY_ERROR, message);
        send(RESULT_FAILED, data);
    }

    private void send(int code, @NonNull Bundle data) {
        ResultReceiver target;
        synchronized (lock) {
            target = receiver;
        }
        if (target != null) {
            try {
                target.send(code, data);
            } catch (Throwable throwable) {
                Log.w(TAG, "Launcher stopped listening to install progress", throwable);
            }
        }
    }
}
