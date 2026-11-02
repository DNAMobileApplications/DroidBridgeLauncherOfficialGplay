/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import dev.horizon.android.HorizonActivity;

/**
 * The Horizon engine's process and game task, seen from the launcher.
 *
 * The engine runs one game per process (one native window, one event loop). A game the
 * player left with Home or the app switcher keeps that process busy, so a new launch must
 * either bring it back or end it first; sending it a second game would hang.
 */
final class HorizonSession {
    private static final String TAG = "DroidBridgeHorizon";
    private static final String PROCESS_SUFFIX = ":horizon";
    private static final long END_TIMEOUT_MS = 4000L;

    private HorizonSession() {
    }

    /** The task showing a Horizon game, if one exists (alive or left over). */
    @Nullable
    static ActivityManager.AppTask findGameTask(@NonNull Context context) {
        ActivityManager manager = activityManager(context);
        if (manager == null) return null;
        String horizon = HorizonActivity.class.getName();
        try {
            List<ActivityManager.AppTask> tasks = manager.getAppTasks();
            for (ActivityManager.AppTask task : tasks) {
                try {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (isComponent(info.baseActivity, horizon) || isComponent(info.topActivity, horizon)) {
                        return task;
                    }
                } catch (Throwable ignored) {
                    // The task ended while we looked.
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not list app tasks", throwable);
        }
        return null;
    }

    /** The engine process id, or -1 when it is not running. */
    static int enginePid(@NonNull Context context) {
        ActivityManager manager = activityManager(context);
        if (manager == null) return -1;
        String name = context.getPackageName() + PROCESS_SUFFIX;
        try {
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return -1;
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (name.equals(process.processName)) return process.pid;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not list app processes", throwable);
        }
        return -1;
    }

    /** True when a Horizon game is still open: its task exists and its engine process runs. */
    static boolean isGameRunning(@NonNull Context context) {
        return findGameTask(context) != null && enginePid(context) > 0;
    }

    /** Removes a game task whose engine process is gone, so it cannot be restored later. */
    static void removeStaleGameTask(@NonNull Context context) {
        if (enginePid(context) > 0) return;
        ActivityManager.AppTask task = findGameTask(context);
        if (task != null) {
            try {
                task.finishAndRemoveTask();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Brings a running game back to the front. */
    static boolean resumeGame(@NonNull Context context) {
        ActivityManager.AppTask task = findGameTask(context);
        if (task == null) return false;
        try {
            task.moveToFront();
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not resume the Horizon game", throwable);
            return false;
        }
    }

    /**
     * Ends the engine: removes its game task and stops its process, then runs {@code done} on
     * the main thread once the process is gone (or after a timeout).
     */
    static void endEngine(@NonNull Context context, @NonNull Runnable done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            ActivityManager.AppTask task = findGameTask(app);
            if (task != null) {
                try {
                    task.finishAndRemoveTask();
                } catch (Throwable ignored) {
                }
            }
            int pid = enginePid(app);
            if (pid > 0) {
                Log.i(TAG, "Ending the Horizon engine process " + pid);
                // Same app and user: the launcher may stop its own engine process.
                Process.killProcess(pid);
            }
            long deadline = SystemClock.uptimeMillis() + END_TIMEOUT_MS;
            while (enginePid(app) > 0 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100L);
            }
            main.post(done);
        }, "DroidBridgeHorizonEnd");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isComponent(@Nullable ComponentName component, @NonNull String className) {
        return component != null && className.equals(component.getClassName());
    }

    @Nullable
    private static ActivityManager activityManager(@NonNull Context context) {
        try {
            return (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
