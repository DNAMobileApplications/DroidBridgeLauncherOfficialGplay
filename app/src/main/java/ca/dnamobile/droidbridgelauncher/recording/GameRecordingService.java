/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.recording;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/** Foreground owner for a single-display Minecraft recording session. */
public final class GameRecordingService extends Service {
    private static final String TAG = "GameRecordingService";
    private static final String CHANNEL_ID = "droidbridge_game_recording";
    private static final int NOTIFICATION_ID = 7412;

    private static final String ACTION_START =
            "ca.dnamobile.droidbridgelauncher.recording.START";
    private static final String ACTION_STOP =
            "ca.dnamobile.droidbridgelauncher.recording.STOP";

    public static final String ACTION_STATE =
            "ca.dnamobile.droidbridgelauncher.recording.STATE";
    public static final String EXTRA_STATE = "recording_state";
    public static final String EXTRA_MESSAGE = "recording_message";
    public static final String EXTRA_FILE_NAME = "recording_file_name";
    public static final String STATE_STARTED = "started";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_ERROR = "error";

    private static final String EXTRA_RESULT_CODE = "projection_result_code";
    private static final String EXTRA_PROJECTION_DATA = "projection_data";
    private static final String EXTRA_WIDTH = "recording_width";
    private static final String EXTRA_HEIGHT = "recording_height";
    private static final String EXTRA_DENSITY = "recording_density";
    private static final String EXTRA_AUDIO_MODE = "recording_audio_mode";

    private static volatile boolean recording;
    private static volatile boolean starting;
    private static volatile boolean finalizing;
    @Nullable private static volatile Context processContext;

    @Nullable private GameScreenRecorder recorder;
    private volatile boolean stopRequested;
    private boolean foregroundStarted;
    private boolean recordingUsesMicrophone;
    private final AtomicBoolean stopWorkerStarted = new AtomicBoolean(false);

    public static boolean isRecordingOrStarting() {
        Context context = processContext;
        return recording || starting || finalizing
                || DualScreenRecordingSession.isRecordingOrFinalizing()
                || (context != null && RecordingProcessState.isBusy(context));
    }

    public static boolean isRecording() {
        Context context = processContext;
        return recording || (context != null && RecordingProcessState.isRecording(context));
    }

    public static boolean isFinalizing() {
        Context context = processContext;
        return finalizing || (context != null && RecordingProcessState.isFinalizing(context));
    }

    public static void start(
            @NonNull Context context,
            int resultCode,
            @NonNull Intent projectionData,
            int width,
            int height,
            int densityDpi
    ) {
        processContext = context.getApplicationContext();
        RecordingProcessState.markStarting(processContext);
        Intent intent = new Intent(context, GameRecordingService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_PROJECTION_DATA, projectionData);
        intent.putExtra(EXTRA_WIDTH, width);
        intent.putExtra(EXTRA_HEIGHT, height);
        intent.putExtra(EXTRA_DENSITY, densityDpi);
        intent.putExtra(EXTRA_AUDIO_MODE, RecordingPreferences.getAudioMode(context));
        safeStartService(context, intent, true);
    }

    public static void stop(@NonNull Context context) {
        processContext = context.getApplicationContext();

        // Do not mutate service-owned static state here. This method normally runs in :game,
        // while the recorder service lives in :recording. A process-local finalizing=true set
        // here can never be cleared by the service process and leaves the in-game UI believing
        // recording is active until Minecraft exits. The service marks the shared state file
        // FINALIZING as soon as it receives ACTION_STOP.

        // Executes in the caller process. In :game this finalizes the view-backed
        // second-screen recorder while the primary recorder continues in :recording.
        DualScreenRecordingSession.stopAsync();

        Intent intent = new Intent(context, GameRecordingService.class);
        intent.setAction(ACTION_STOP);

        // ACTION_STOP targets an already-running foreground service. Use normal service
        // command delivery instead of trying to start another foreground-service transaction.
        // The recording service itself remains foreground until MediaMuxer finalization ends.
        safeStartService(context, intent, false);
    }

    private static void safeStartService(
            @NonNull Context context,
            @NonNull Intent intent,
            boolean foreground
    ) {
        try {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to start recording service action=" + intent.getAction(), throwable);
            if (ACTION_START.equals(intent.getAction())) {
                RecordingProcessState.clear(context.getApplicationContext());
            }
            sendStaticState(context, STATE_ERROR,
                    "Unable to start the recording service: " + safeMessage(throwable), null);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        processContext = getApplicationContext();
        RecordingDiagnostics.initialize(getApplicationContext());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            if (!foregroundStarted) {
                startForegroundCompat(buildNotification("Finishing recording..."), recordingUsesMicrophone);
            } else {
                updateNotification("Finishing recording...");
            }
            finalizing = recording || starting || recorder != null
                    || DualScreenRecordingSession.isRecordingOrFinalizing()
                    || RecordingProcessState.isBusy(getApplicationContext());
            if (finalizing) RecordingProcessState.markFinalizing(getApplicationContext());
            DualScreenRecordingSession.stopAsync();
            stopRequested = true;
            stopRecordingAsync();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (recording || starting) return START_NOT_STICKY;

        final String audioMode = RecordingPreferences.normalizeAudioMode(
                intent.getStringExtra(EXTRA_AUDIO_MODE));
        recordingUsesMicrophone = RecordingPreferences.AUDIO_MODE_VOICE_ONLY.equals(audioMode)
                || RecordingPreferences.AUDIO_MODE_VOICE_AND_GAME.equals(audioMode);
        startForegroundCompat(buildNotification("Preparing Minecraft recording..."), recordingUsesMicrophone);
        RecordingProcessState.markStarting(getApplicationContext());
        starting = true;
        stopRequested = false;

        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        final Intent projectionData = getProjectionIntent(intent);
        final int width = intent.getIntExtra(EXTRA_WIDTH, 0);
        final int height = intent.getIntExtra(EXTRA_HEIGHT, 0);
        final int density = intent.getIntExtra(EXTRA_DENSITY, 0);

        Logging.i(TAG, "Recording start request width=" + width
                + " height=" + height
                + " density=" + density
                + " dualScreenRequested="
                + RecordingPreferences.isRecordBothScreensEnabled(getApplicationContext())
                + " hideTouchControls="
                + RecordingPreferences.isHideTouchControlsEnabled(getApplicationContext())
                + " audioMode=" + RecordingPreferences.getAudioModeLabel(audioMode));

        if (projectionData == null || resultCode == 0 || width <= 0 || height <= 0 || density <= 0) {
            failAndStop("Android did not provide a valid screen-capture session.");
            return START_NOT_STICKY;
        }

        new Thread(() -> {
            try {
                GameScreenRecorder newRecorder = new GameScreenRecorder(
                        getApplicationContext(),
                        resultCode,
                        projectionData,
                        width,
                        height,
                        density,
                        audioMode,
                        () -> {
                            if (!stopRequested) {
                                stopRequested = true;
                                stopRecordingAsync();
                            }
                        }
                );
                recorder = newRecorder;
                newRecorder.start();

                if (stopRequested) {
                    stopRecordingInternal();
                    return;
                }

                starting = false;
                recording = true;
                RecordingProcessState.markRecording(getApplicationContext());
                boolean dualRecording = DualScreenRecordingSession.isRecording();
                updateNotification(dualRecording
                        ? "Recording Minecraft + second screen"
                        : "Recording Minecraft - " + RecordingPreferences.getAudioModeLabel(audioMode));
                sendState(STATE_STARTED,
                        dualRecording
                                ? "Dual-screen recording started. Audio mode: " + RecordingPreferences.getAudioModeLabel(audioMode) + "."
                                : "Recording started. Audio mode: " + RecordingPreferences.getAudioModeLabel(audioMode) + ".",
                        newRecorder.getDisplayName());
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to start Minecraft recording; "
                        + RecordingDiagnostics.describeThrowable(throwable));
                recorder = null;
                starting = false;
                recording = false;
                failAndStop("Recording could not start: " + safeMessage(throwable));
            }
        }, "DroidBridgeRecordingStart").start();

        return START_NOT_STICKY;
    }

    private void stopRecordingAsync() {
        DualScreenRecordingSession.stopAsync();
        if (recording || starting || recorder != null
                || DualScreenRecordingSession.isRecordingOrFinalizing()
                || RecordingProcessState.isBusy(getApplicationContext())) {
            finalizing = true;
            RecordingProcessState.markFinalizing(getApplicationContext());
        }
        if (!stopWorkerStarted.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                stopRecordingInternal();
            } finally {
                stopWorkerStarted.set(false);
            }
        }, "DroidBridgeRecordingStop").start();
    }

    private synchronized void stopRecordingInternal() {
        GameScreenRecorder active = recorder;
        recorder = null;
        boolean wasActive = recording || starting || active != null
                || DualScreenRecordingSession.isRecordingOrFinalizing();
        if (wasActive) finalizing = true;
        recording = false;
        starting = false;

        boolean saved = false;
        String fileName = null;
        try {
            if (active != null) {
                fileName = active.getDisplayName();
                try {
                    saved = active.stop();
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Unable to finalize Minecraft recording", throwable);
                }
            }

            // Keep the foreground service alive until the optional second display has also
            // finished MediaCodec/MediaMuxer shutdown. Killing :game before this completes
            // can leave its MediaStore row permanently IS_PENDING=1.
            boolean secondScreenStopped = DualScreenRecordingSession.awaitStopped(8000L);
            if (!secondScreenStopped) {
                Logging.e(TAG, "Timed out waiting for second-screen recording finalization");
            }

            if (wasActive) {
                if (saved) {
                    sendState(STATE_STOPPED,
                            "Recording saved to Movies/DroidBridge/Recordings.",
                            fileName);
                } else if (active != null) {
                    sendState(STATE_ERROR,
                            "Recording stopped, but Android could not finalize the MP4.",
                            fileName);
                }
            }
        } finally {
            finalizing = false;
            RecordingProcessState.clear(getApplicationContext());
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void failAndStop(@NonNull String message) {
        DualScreenRecordingSession.stopAsync();
        starting = false;
        recording = false;
        finalizing = DualScreenRecordingSession.isRecordingOrFinalizing();
        if (!finalizing) {
            RecordingProcessState.clear(getApplicationContext());
            sendState(STATE_ERROR, message, null);
            stopForegroundCompat();
            stopSelf();
            return;
        }
        new Thread(() -> {
            try {
                DualScreenRecordingSession.awaitStopped(8000L);
            } finally {
                finalizing = false;
                RecordingProcessState.clear(getApplicationContext());
                sendState(STATE_ERROR, message, null);
                stopForegroundCompat();
                stopSelf();
            }
        }, "DroidBridgeRecordingFailedStartStop").start();
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private Intent getProjectionIntent(@NonNull Intent source) {
        if (Build.VERSION.SDK_INT >= 33) {
            return source.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        }
        return source.getParcelableExtra(EXTRA_PROJECTION_DATA);
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // The recording service lives in the :game process. If the user swipes DroidBridge
        // away, explicitly finalize instead of letting Android eventually kill the process
        // with an IS_PENDING MediaStore row still open.
        stopRequested = true;
        finalizing = recording || starting || recorder != null
                || DualScreenRecordingSession.isRecordingOrFinalizing()
                || RecordingProcessState.isBusy(getApplicationContext());
        if (finalizing) RecordingProcessState.markFinalizing(getApplicationContext());
        stopRecordingAsync();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        DualScreenRecordingSession.stopAsync();
        // onDestroy is the last in-process chance to close MediaMuxer cleanly. Run the
        // synchronized finalizer here too; if a stop worker is already active this waits
        // for that same transaction rather than racing it.
        if (recorder != null || recording || starting || finalizing
                || DualScreenRecordingSession.isRecordingOrFinalizing()) {
            try {
                stopRecordingInternal();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Recording finalization failed during service teardown", throwable);
                finalizing = false;
            }
        }
        recording = false;
        starting = false;
        finalizing = false;
        RecordingProcessState.clear(getApplicationContext());
        stopForegroundCompat();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(@NonNull String text) {
        Intent stopIntent = new Intent(this, GameRecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("DroidBridge recording")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent);
        return builder.build();
    }

    private void startForegroundCompat(@NonNull Notification notification, boolean includeMicrophone) {
        if (foregroundStarted) {
            updateNotification(notification.extras != null
                    ? notification.extras.getString(Notification.EXTRA_TEXT, "Recording Minecraft")
                    : "Recording Minecraft");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (includeMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, serviceType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void updateNotification(@NonNull String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Game recordings",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when DroidBridge is recording Minecraft gameplay.");
        manager.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (!foregroundStarted) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        foregroundStarted = false;
    }

    private void sendState(
            @NonNull String state,
            @NonNull String message,
            @Nullable String fileName
    ) {
        sendStaticState(this, state, message, fileName);
    }

    private static void sendStaticState(
            @NonNull Context context,
            @NonNull String state,
            @NonNull String message,
            @Nullable String fileName
    ) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        if (fileName != null) intent.putExtra(EXTRA_FILE_NAME, fileName);
        context.sendBroadcast(intent);
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable throwable) {
        return RecordingDiagnostics.shortError(throwable);
    }
}
