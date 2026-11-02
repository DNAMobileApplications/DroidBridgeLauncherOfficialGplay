/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.system.Os;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.LauncherSettingsActivity;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.engine.EnginePreferences;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthConfigPersonal;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthManagerPersonal;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.security.LauncherSecurity;
import dev.horizon.android.HorizonActivity;

/**
 * Runs one Horizon launch with DroidBridge's own screens.
 *
 * 1. Account: the same rules as GameActivity before the Java engine starts (official build,
 *    verified Microsoft account that owns Minecraft, fresh token, refreshed when expired).
 * 2. Resources: on the first launch, HorizonInstallService (in :horizon) downloads and
 *    prepares Minecraft 26.3; this activity shows the progress in its own dialog.
 * 3. Game: writes the session and starts dev.horizon.android.HorizonActivity straight into
 *    the game. The engine never shows its own launcher in this flow.
 * 4. Return: when the player quits, this activity comes back to the front, shows the
 *    engine's error if it stopped with one, and closes.
 */
public final class HorizonLaunchActivity extends AppCompatActivity {
    private static final String TAG = "HorizonLaunch";

    public static final String EXTRA_INSTANCE_KEY = "ca.dnamobile.droidbridgelauncher.horizon.extra.INSTANCE_KEY";
    public static final String EXTRA_GAME_DIR = "ca.dnamobile.droidbridgelauncher.horizon.extra.GAME_DIR";
    public static final String EXTRA_QUICK_PLAY_WORLD = "ca.dnamobile.droidbridgelauncher.horizon.extra.QUICK_PLAY_WORLD";
    public static final String EXTRA_QUICK_PLAY_SERVER = "ca.dnamobile.droidbridgelauncher.horizon.extra.QUICK_PLAY_SERVER";
    /** False opens the engine's own launcher screen (renderer, touch and engine settings). */

    private static final String STATE_ENGINE_STARTED = "engineStarted";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    /** The engine refuses session files older than this (horizon auth MAX_SESSION_FILE_AGE). */
    private static final long SESSION_FILE_MAX_AGE_MS = 120_000L;
    private static final long REFRESH_TIMEOUT_MS = 90_000L;
    /** Where the engine leaves the reason it stopped (android_main / Launcher::fail). */
    private static final String ENGINE_ERROR_FILE = "android-error.txt";
    /** The installer answers within a second when its process is free; never wait forever. */
    private static final long INSTALL_ANSWER_TIMEOUT_MS = 20_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private AccountStore accountStore;
    @Nullable private MicrosoftAuthManagerPersonal authManager;
    @Nullable private Runnable refreshTimeout;
    private boolean refreshAttempted;
    private boolean finished;
    /** The engine's activity was started; the next resume means the player came back. */
    private boolean engineStarted;
    private boolean pausedForEngine;
    /** The engine's installer has answered at least once for this launch. */
    private boolean installAnswered;
    @Nullable private Runnable installWatchdog;
    private long downloadStartedAtMs;
    private long downloadStartedAtBytes = -1;

    @Nullable private AlertDialog progressDialog;
    @Nullable private TextView progressStage;
    @Nullable private TextView progressDetail;
    @Nullable private ProgressBar progressBar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The auth manager registers an activity-result launcher, which must happen here.
        try {
            accountStore = new AccountStore(this);
            authManager = new MicrosoftAuthManagerPersonal(this, accountStore);
            authManager.setListener(new MicrosoftAuthManagerPersonal.Listener() {
                @Override
                public void onSignedIn(@NonNull AccountStore.Account account) {
                    onRefreshFinished(null);
                }

                @Override
                public void onError(@NonNull String message) {
                    onRefreshFinished(message);
                }
            });
        } catch (Throwable throwable) {
            Logging.e(TAG, "Microsoft account helper initialization failed", throwable);
            authManager = null;
        }

        engineStarted = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ENGINE_STARTED, false);
        if (engineStarted) {
            // Recreated while the game ran (the launcher process was reclaimed): the next
            // resume is the player coming back.
            pausedForEngine = true;
            return;
        }
        // One game per engine process: a game left open in the background must be resumed or
        // ended first, or this launch would wait on a busy engine.
        if (HorizonSession.isGameRunning(this)) {
            askAboutRunningGame();
            return;
        }
        HorizonSession.removeStaleGameTask(this);
        startPreflight();
    }

    private void startPreflight() {
        if (finished) return;
        showProgress("Checking your Microsoft account...", null, -1, 0, 0, false);
        checkAccountInBackground();
    }

    private void askAboutRunningGame() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Horizon is still running")
                .setMessage("A Horizon game is still open in the background. Go back to it, or close "
                        + "it and start this launch? Closing it does not save anything the game "
                        + "has not already saved.")
                .setCancelable(false)
                .setPositiveButton("Resume game", (dialog, which) -> {
                    if (!HorizonSession.resumeGame(this)) {
                        restartEngineThen(this::startPreflight);
                        return;
                    }
                    finishHandoff();
                })
                .setNeutralButton("Close it and start", (dialog, which) ->
                        restartEngineThen(this::startPreflight))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishHandoff())
                .show();
    }

    /** Ends the engine process (game or installer), then continues. */
    private void restartEngineThen(@NonNull Runnable next) {
        if (finished) return;
        showProgress("Closing the running Horizon engine...", null, -1, 0, 0, false);
        LauncherLogManager.append("Horizon: ending the engine process before a new launch");
        HorizonSession.endEngine(this, () -> {
            if (!finished) next.run();
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ENGINE_STARTED, engineStarted);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engineStarted) pausedForEngine = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engineStarted && pausedForEngine && !finished) {
            pausedForEngine = false;
            onEngineReturned();
        }
    }

    @Override
    protected void onDestroy() {
        cancelRefreshTimeout();
        cancelInstallWatchdog();
        dismissProgress();
        if (authManager != null) {
            try {
                authManager.dispose();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    // ---- 1. account ----------------------------------------------------------------------

    private void checkAccountInBackground() {
        Thread thread = new Thread(() -> {
            AccountStore.Account account = loadAccount();
            TokenCheck check = account == null ? TokenCheck.MISSING : validateToken(account);
            mainHandler.post(() -> onTokenChecked(check));
        }, "DroidBridgeHorizonPreflight");
        thread.setDaemon(true);
        thread.start();
    }

    private void onTokenChecked(@NonNull TokenCheck check) {
        if (finished) return;
        switch (check) {
            case VALID:
                verifyEntitlementThenInstall();
                break;
            case EXPIRED:
                if (!refreshAttempted && authManager != null && MicrosoftAuthConfigPersonal.isConfigured()) {
                    refreshAttempted = true;
                    showProgress("Refreshing your Microsoft account...", null, -1, 0, 0, false);
                    armRefreshTimeout();
                    try {
                        authManager.refreshMicrosoftAccount();
                    } catch (Throwable throwable) {
                        Logging.e(TAG, "Microsoft refresh could not start", throwable);
                        onRefreshFinished(throwable.getMessage() == null
                                ? throwable.getClass().getSimpleName() : throwable.getMessage());
                    }
                } else {
                    fail("Your saved Minecraft session expired and could not be refreshed. "
                            + "Sign in again in Settings.", true, false);
                }
                break;
            case OFFLINE:
                // Same as the Java engine: a verified account may still play singleplayer.
                offerOfflinePlay("DroidBridge could not reach Minecraft services to check your session. "
                        + "Singleplayer works; servers and Realms need a connection.");
                break;
            case MISSING:
            default:
                fail("Sign in with the Microsoft account that owns Minecraft: Java Edition to play.",
                        true, false);
                break;
        }
    }

    private void onRefreshFinished(@Nullable String error) {
        mainHandler.post(() -> {
            if (finished) return;
            cancelRefreshTimeout();
            if (error == null) {
                verifyEntitlementThenInstall();
            } else {
                Logging.i(TAG, "Microsoft refresh failed before Horizon launch: " + error);
                offerOfflinePlay("Microsoft account refresh failed: " + error);
            }
        });
    }

    private void offerOfflinePlay(@NonNull String message) {
        AccountStore.Account account = loadAccount();
        if (account == null || !LauncherSecurity.canLaunchWithMicrosoftAccount(this, account)) {
            fail(message + "\n\nSign in again in Settings.", true, false);
            return;
        }
        dismissProgress();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Play without a fresh session?")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Play anyway", (dialog, which) -> {
                    LauncherLogManager.append("Horizon: launching with the saved session after a failed refresh");
                    verifyEntitlementThenInstall();
                })
                .setNeutralButton("Settings", (dialog, which) -> openSettingsAndFinish())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishHandoff())
                .show();
    }

    private void verifyEntitlementThenInstall() {
        if (finished) return;
        showProgress("Checking your Minecraft license...", null, -1, 0, 0, false);
        Thread thread = new Thread(() -> {
            try {
                LauncherSecurity.requireOfficialBuildAndMicrosoftSession(this, loadAccount(), "launch Minecraft");
                mainHandler.post(this::ensureResources);
            } catch (SecurityException exception) {
                mainHandler.post(() -> fail(exception.getMessage() == null
                        ? "A verified Microsoft account is required to play." : exception.getMessage(),
                        true, false));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Horizon license check failed", throwable);
                mainHandler.post(() -> fail("Could not check your Minecraft license: "
                        + describe(throwable), false, true));
            }
        }, "DroidBridgeHorizonLicense");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- 2. resources --------------------------------------------------------------------

    private void ensureResources() {
        if (finished) return;
        showProgress("Checking Horizon's Minecraft " + HorizonEngine.MINECRAFT_VERSION + " files...",
                "Looking for a finished installation", -1, 0, 0, false);
        installAnswered = false;
        downloadStartedAtBytes = -1;
        armInstallWatchdog();
        ResultReceiver receiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                onInstallUpdate(resultCode, data == null ? new Bundle() : data);
            }
        };
        Intent intent = new Intent(this, HorizonInstallService.class);
        intent.putExtra(HorizonInstallService.EXTRA_RECEIVER, receiver);
        intent.putExtra(HorizonInstallService.EXTRA_HOME, HorizonEngine.engineHome(this).getAbsolutePath());
        try {
            startService(intent);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Could not start the Horizon installer", throwable);
            fail("Could not start the Minecraft installer: " + describe(throwable), false, true);
        }
    }

    private void armInstallWatchdog() {
        cancelInstallWatchdog();
        installWatchdog = () -> {
            installWatchdog = null;
            if (finished || installAnswered) return;
            LauncherLogManager.append("Horizon: the engine process did not answer the installer request");
            dismissProgress();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Horizon isn't responding")
                    .setMessage("The Horizon engine did not answer. It may still be busy with an "
                            + "earlier game. Restart the engine and try again?")
                    .setCancelable(false)
                    .setPositiveButton("Restart engine", (dialog, which) ->
                            restartEngineThen(this::ensureResources))
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishHandoff())
                    .show();
        };
        mainHandler.postDelayed(installWatchdog, INSTALL_ANSWER_TIMEOUT_MS);
    }

    private void cancelInstallWatchdog() {
        if (installWatchdog != null) {
            mainHandler.removeCallbacks(installWatchdog);
            installWatchdog = null;
        }
    }

    /** Download speed and time left, from the first download report of this launch. */
    @NonNull
    private String downloadRate(long done, long total) {
        long now = SystemClock.elapsedRealtime();
        if (downloadStartedAtBytes < 0) {
            downloadStartedAtBytes = done;
            downloadStartedAtMs = now;
            return "";
        }
        double seconds = (now - downloadStartedAtMs) / 1000.0;
        long bytes = done - downloadStartedAtBytes;
        if (seconds < 1.0 || bytes <= 0) return "";
        double perSecond = bytes / seconds;
        long left = (long) Math.ceil(Math.max(0, total - done) / perSecond);
        String remaining = left >= 3600 ? String.format(Locale.ROOT, "%dh %dm", left / 3600, (left % 3600) / 60)
                : left >= 60 ? String.format(Locale.ROOT, "%dm %ds", left / 60, left % 60)
                : String.format(Locale.ROOT, "%ds", left);
        return String.format(Locale.ROOT, "  ·  %.1f MB/s  ·  about %s left",
                perSecond / 1048576.0, remaining);
    }

    private void onInstallUpdate(int resultCode, @NonNull Bundle data) {
        if (finished) return;
        installAnswered = true;
        cancelInstallWatchdog();
        switch (resultCode) {
            case HorizonInstallService.RESULT_PROGRESS: {
                int stage = data.getInt(HorizonInstallService.KEY_STAGE);
                int stages = Math.max(1, data.getInt(HorizonInstallService.KEY_STAGE_COUNT, 1));
                String label = data.getString(HorizonInstallService.KEY_LABEL, "Installing");
                long done = data.getLong(HorizonInstallService.KEY_DONE);
                long total = data.getLong(HorizonInstallService.KEY_TOTAL);
                boolean bytes = data.getBoolean(HorizonInstallService.KEY_BYTES);
                String stageText = "Step " + (stage + 1) + " of " + stages + " · " + label;
                String detail = amount(done, total, bytes)
                        + (bytes && total > 0 ? downloadRate(done, total) : "");
                showProgressDetail("Installing Minecraft " + HorizonEngine.MINECRAFT_VERSION
                        + " for Horizon. This happens once; keep DroidBridge open.",
                        stageText, total > 0 ? percent(done, total) : -1, detail);
                break;
            }
            case HorizonInstallService.RESULT_READY:
                LauncherLogManager.append("Horizon: resources ready");
                writeSessionAndStart();
                break;
            case HorizonInstallService.RESULT_FAILED:
            default: {
                String error = data.getString(HorizonInstallService.KEY_ERROR, "Unknown error");
                LauncherLogManager.append("Horizon: resource install failed: " + error);
                fail("Installing Minecraft " + HorizonEngine.MINECRAFT_VERSION + " for Horizon failed:\n\n"
                        + error + "\n\nFiles that finished downloading are kept.", false, true);
                break;
            }
        }
    }

    // ---- 3. game -------------------------------------------------------------------------

    private void writeSessionAndStart() {
        if (finished) return;
        showProgress("Starting Horizon (Rust)...", null, -1, 0, 0, false);
        Thread thread = new Thread(() -> {
            try {
                AccountStore.Account account = loadAccount();
                LauncherSecurity.requireOfficialBuildAndMicrosoftSession(this, account, "launch Minecraft");
                File session = writeSessionFile(this, account);
                mainHandler.post(() -> startEngine(session));
            } catch (SecurityException exception) {
                mainHandler.post(() -> fail(exception.getMessage() == null
                        ? "A verified Microsoft account is required to play." : exception.getMessage(),
                        true, false));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Horizon launch preparation failed", throwable);
                mainHandler.post(() -> fail("Could not prepare the Horizon launch: " + describe(throwable),
                        false, true));
            }
        }, "DroidBridgeHorizonHandoff");
        thread.setDaemon(true);
        thread.start();
    }

    private void startEngine(@NonNull File session) {
        if (finished) return;

        // Do not redisplay an error left by a previous engine process.
        File previousError = new File(HorizonEngine.engineHome(this), ENGINE_ERROR_FILE);
        if (previousError.isFile() && !previousError.delete()) {
            LauncherLogManager.append("Horizon: could not clear the previous engine error file");
        }

        Intent source = getIntent();
        Intent intent = new Intent(this, HorizonActivity.class);
        // The game gets its own task, replacing any left-over one, so a new launch never
        // restores an old game's activity with an old session.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(HorizonActivity.EXTRA_ENGINE_HOME, HorizonEngine.engineHome(this).getAbsolutePath());
        intent.putExtra(HorizonActivity.EXTRA_SESSION_FILE, session.getAbsolutePath());
        // DroidBridge owns the launcher/settings experience. Always enter Horizon's embedded
        // autoplay path so no stale/internal caller can expose Horizon's standalone launcher UI.
        intent.putExtra(HorizonActivity.EXTRA_HOST_CONTROLS,
                EnginePreferences.useDroidBridgeControlsForHorizon(this));
        HorizonGraphics.applyTo(this, intent);
        copyExtra(source, EXTRA_GAME_DIR, intent, HorizonActivity.EXTRA_GAME_DIR);
        copyExtra(source, EXTRA_QUICK_PLAY_WORLD, intent, HorizonActivity.EXTRA_QUICK_PLAY_WORLD);
        copyExtra(source, EXTRA_QUICK_PLAY_SERVER, intent, HorizonActivity.EXTRA_QUICK_PLAY_SERVER);
        LauncherLogManager.append("Horizon: starting engine for instance "
                + source.getStringExtra(EXTRA_INSTANCE_KEY)
                + " autoplay=true"
                + " droidbridgeControls=" + intent.getBooleanExtra(HorizonActivity.EXTRA_HOST_CONTROLS, false));
        try {
            startActivity(intent);
            overridePendingTransition(0, 0);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Could not start the Horizon engine", throwable);
            //noinspection ResultOfMethodCallIgnored
            session.delete();
            fail("Could not start the Horizon engine: " + describe(throwable), false, false);
            return;
        }
        engineStarted = true;
        dismissProgress();
    }

    // ---- 4. return -----------------------------------------------------------------------

    private void onEngineReturned() {
        String error = readEngineError();
        if (error == null) {
            finishHandoff();
            return;
        }
        LauncherLogManager.append("Horizon: engine stopped with: " + error);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Horizon stopped")
                .setMessage(error)
                .setCancelable(false)
                .setNeutralButton("Share crash report", (dialog, which) -> {
                    LauncherLogManager.shareLatestHorizonCrashReport(this);
                    finishHandoff();
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finishHandoff())
                .show();
    }

    @Nullable
    private String readEngineError() {
        File file = new File(HorizonEngine.engineHome(this), ENGINE_ERROR_FILE);
        if (!file.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : (text.length() > 1500 ? text.substring(0, 1500) + "…" : text);
        } catch (Throwable throwable) {
            return null;
        }
    }

    // ---- session file --------------------------------------------------------------------

    private static void copyExtra(@NonNull Intent from, @NonNull String fromKey,
                                  @NonNull Intent to, @NonNull String toKey) {
        String value = from.getStringExtra(fromKey);
        if (value != null && !value.trim().isEmpty()) {
            to.putExtra(toKey, value.trim());
        }
    }

    /**
     * Writes the session the engine reads once and deletes (crates/horizon-client-render
     * auth.rs AuthSession::load): owner-only permissions, written moments before launch.
     */
    @NonNull
    static File writeSessionFile(@NonNull Context context, @NonNull AccountStore.Account account)
            throws IOException {
        File directory = HorizonEngine.sessionDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory);
        }
        deleteStaleSessions(directory);

        byte[] name = new byte[12];
        new SecureRandom().nextBytes(name);
        StringBuilder fileName = new StringBuilder("session-");
        for (byte b : name) fileName.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        File file = new File(directory, fileName.append(".json").toString());

        JSONObject body = new JSONObject();
        try {
            body.put("name", account.minecraftName);
            body.put("uuid", account.minecraftUuid);
            body.put("access_token", account.minecraftAccessToken);
            body.put("user_type", "msa");
            if (account.xuid != null && !account.xuid.trim().isEmpty()) {
                body.put("xuid", account.xuid.trim());
            }
        } catch (org.json.JSONException exception) {
            throw new IOException("Cannot encode the session", exception);
        }

        if (!file.createNewFile()) {
            throw new IOException("Session file already exists: " + file);
        }
        try {
            // The engine refuses a session that group or others could read.
            Os.chmod(file.getAbsolutePath(), 0600);
            try (OutputStream output = new FileOutputStream(file)) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw throwable instanceof IOException ? (IOException) throwable
                    : new IOException("Cannot write the session", throwable);
        }
        return file;
    }

    /** Sessions of launches that never reached the game (the engine deletes used ones). */
    private static void deleteStaleSessions(@NonNull File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File old : files) {
            if (old.isFile() && now - old.lastModified() > SESSION_FILE_MAX_AGE_MS) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private enum TokenCheck { VALID, EXPIRED, OFFLINE, MISSING }

    @Nullable
    private AccountStore.Account loadAccount() {
        try {
            AccountStore store = accountStore != null ? accountStore : new AccountStore(this);
            AccountStore.Account account = store.load();
            if (account != null && account.isMicrosoftAccount() && account.hasMinecraftSession()) {
                return account;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Could not load the saved account", throwable);
        }
        return null;
    }

    @NonNull
    private static TokenCheck validateToken(@NonNull AccountStore.Account account) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(PROFILE_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + account.minecraftAccessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) return TokenCheck.VALID;
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                return TokenCheck.EXPIRED;
            }
            return TokenCheck.OFFLINE;
        } catch (IOException exception) {
            return TokenCheck.OFFLINE;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void armRefreshTimeout() {
        cancelRefreshTimeout();
        refreshTimeout = () -> onRefreshFinished("Microsoft did not answer in time.");
        mainHandler.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS);
    }

    private void cancelRefreshTimeout() {
        if (refreshTimeout != null) {
            mainHandler.removeCallbacks(refreshTimeout);
            refreshTimeout = null;
        }
    }

    private static int percent(long done, long total) {
        if (total <= 0) return -1;
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }

    @NonNull
    private static String amount(long done, long total, boolean bytes) {
        if (total <= 0) return "";
        if (bytes) {
            return String.format(Locale.ROOT, "%.0f / %.0f MB  ·  %d%%",
                    done / 1048576.0, total / 1048576.0, percent(done, total));
        }
        return String.format(Locale.ROOT, "%d / %d  ·  %d%%", done, total, percent(done, total));
    }

    /**
     * One dialog for every waiting step: a headline, an optional step line and a bar that is
     * indeterminate while {@code percent} is negative.
     */
    private void showProgress(@NonNull String headline, @Nullable String stage, int percent,
                              long done, long total, boolean bytes) {
        showProgressDetail(headline, stage, percent, amount(done, total, bytes));
    }

    private void showProgressDetail(@NonNull String headline, @Nullable String stage, int percent,
                                    @NonNull String detail) {
        if (finished || isFinishing()) return;
        if (progressDialog == null) {
            int pad = Math.round(22 * getResources().getDisplayMetrics().density);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, pad / 2, pad, 0);
            progressStage = new TextView(this);
            progressStage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            content.addView(progressStage);
            progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            content.addView(progressBar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            progressDetail = new TextView(this);
            progressDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            content.addView(progressDetail);
            progressDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle("Horizon (Rust)")
                    .setMessage(headline)
                    .setView(content)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishHandoff())
                    .show();
        } else {
            progressDialog.setMessage(headline);
        }
        if (progressStage != null) {
            progressStage.setText(stage == null ? "" : stage);
        }
        if (progressBar != null) {
            progressBar.setIndeterminate(percent < 0);
            if (percent >= 0) progressBar.setProgress(percent);
        }
        if (progressDetail != null) {
            progressDetail.setText(detail);
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignored) {
            }
            progressDialog = null;
            progressStage = null;
            progressDetail = null;
            progressBar = null;
        }
    }

    private void fail(@NonNull String message, boolean offerSettings, boolean offerRetry) {
        if (finished || isFinishing()) return;
        dismissProgress();
        LauncherLogManager.append("Horizon: launch stopped: " + message);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Could not start Horizon")
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishHandoff());
        if (offerRetry) {
            builder.setPositiveButton("Retry", (dialog, which) -> verifyEntitlementThenInstall());
        }
        if (offerSettings) {
            builder.setNeutralButton("Settings", (dialog, which) -> openSettingsAndFinish());
        }
        builder.show();
    }

    @NonNull
    private static String describe(@NonNull Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private void openSettingsAndFinish() {
        try {
            Intent intent = new Intent(this, LauncherSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Could not open Settings", throwable);
        }
        finishHandoff();
    }

    private void finishHandoff() {
        if (finished) return;
        finished = true;
        cancelRefreshTimeout();
        cancelInstallWatchdog();
        dismissProgress();
        finish();
        overridePendingTransition(0, 0);
    }
}
