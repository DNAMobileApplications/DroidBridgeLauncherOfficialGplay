/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.update;

import android.app.Activity;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.BuildConfig;

/**
 * Google Play update checker.
 *
 * <p>For a copy installed from Google Play, DroidBridge asks the Play Store itself through
 * Google Play's in-app update API ({@code com.google.android.play:app-update}): it answers
 * for this device and this account, and the update can run inside the app
 * ({@link #startPlayUpdate}). It used to be called through reflection with no dependency
 * declared, so the classes were never in the app and every check fell through to reading the
 * Play web page, which no longer shows the version: the check failed.</p>
 *
 * <p>A copy from anywhere else (a debug build, a sideloaded APK) can't be checked by the
 * Play Store. For those the public Play listing of the release package is read as a best
 * effort, and when it shows no version the player is told to look on Google Play.</p>
 */
public final class LauncherUpdateChecker {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    /** Suffixes of the builds that aren't on Google Play (build.gradle's applicationIdSuffix). */
    private static final String[] NON_PLAY_SUFFIXES = new String[]{".debug", ".obfdebug"};

    private static final Pattern[] PLAY_VERSION_PATTERNS = new Pattern[]{
            Pattern.compile("itemprop=[\\\"']softwareVersion[\\\"'][^>]*content=[\\\"']([^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("content=[\\\"']([^\\\"']+)[\\\"'][^>]*itemprop=[\\\"']softwareVersion[\\\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\"softwareVersion\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\\\"softwareVersion\\\\\\\"\\s*:\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\"", Pattern.CASE_INSENSITIVE),
            // The page's embedded data today: the version as [[["0.11.11"]], ... Only a
            // version with three or more parts is taken, so "8.0" (the Android it needs)
            // can't pass for it.
            Pattern.compile("\\[\\[\\[\\\"(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)\\\"\\]\\]")
    };

    private static final Pattern[] PLAY_VERSION_CODE_PATTERNS = new Pattern[]{
            Pattern.compile("\\\"versionCode\\\"\\s*:\\s*\\\"?(\\d+)\\\"?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\\\"versionCode\\\\\\\"\\s*:\\s*\\\\\\\"?(\\d+)\\\\\\\"?", Pattern.CASE_INSENSITIVE)
    };

    @Nullable
    private static AppUpdateManager playManager;

    private LauncherUpdateChecker() {
    }

    public interface Callback {
        void onResult(@NonNull LauncherUpdateInfo info);

        void onNoUpdate(@NonNull LauncherUpdateInfo latestInfo);

        void onError(@NonNull String message, @Nullable Throwable throwable);
    }

    public static void checkAsync(@NonNull Context context, @NonNull Callback callback) {
        Context appContext = context.getApplicationContext();
        AtomicBoolean delivered = new AtomicBoolean(false);

        if (isInstalledFromPlay(appContext)) {
            checkWithPlay(appContext, callback, delivered);
        } else {
            checkPlayListingAsync(appContext, callback, delivered);
        }
    }

    @NonNull
    private static synchronized AppUpdateManager manager(@NonNull Context context) {
        if (playManager == null) {
            playManager = AppUpdateManagerFactory.create(context.getApplicationContext());
        }
        return playManager;
    }

    /** Asks the Play Store (main thread callbacks). */
    private static void checkWithPlay(
            @NonNull Context context,
            @NonNull Callback callback,
            @NonNull AtomicBoolean delivered
    ) {
        try {
            manager(context).getAppUpdateInfo()
                    .addOnSuccessListener(info -> {
                        int availability = info.updateAvailability();
                        if (availability == UpdateAvailability.UPDATE_AVAILABLE
                                || availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                            deliverResult(callback, new LauncherUpdateInfo(
                                    "",
                                    info.availableVersionCode(),
                                    getPlayStoreWebUrl(context),
                                    true,
                                    true,
                                    info
                            ), delivered);
                        } else if (availability == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                            deliverResult(callback, new LauncherUpdateInfo(
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                    getPlayStoreWebUrl(context),
                                    false,
                                    true
                            ), delivered);
                        } else {
                            // UNKNOWN: the Play Store couldn't tell; the listing may.
                            checkPlayListingAsync(context, callback, delivered);
                        }
                    })
                    .addOnFailureListener(error ->
                            // Not signed in to Play, Play Store too old, no network...
                            checkPlayListingAsync(context, callback, delivered));
        } catch (Throwable error) {
            checkPlayListingAsync(context, callback, delivered);
        }
    }

    /**
     * Starts Google Play's own update screen for an update the Play Store reported, from
     * {@code activity}. False when that can't be done (not from the Play Store, or Play won't
     * run it): open the Play listing instead.
     */
    public static boolean startPlayUpdate(@NonNull Activity activity, @NonNull LauncherUpdateInfo info) {
        AppUpdateInfo playInfo = info.playUpdateInfo;
        if (playInfo == null || !playInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            return false;
        }
        try {
            manager(activity).startUpdateFlow(
                    playInfo,
                    activity,
                    AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
            );
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** Whether the Play Store installed this copy (only those can be checked by it). */
    public static boolean isInstalledFromPlay(@NonNull Context context) {
        if (isNonPlayPackage(context.getPackageName())) return false;
        try {
            PackageManager pm = context.getPackageManager();
            String installer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo source = pm.getInstallSourceInfo(context.getPackageName());
                installer = source.getInstallingPackageName();
            } else {
                installer = pm.getInstallerPackageName(context.getPackageName());
            }
            return PLAY_STORE_PACKAGE.equals(installer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNonPlayPackage(@NonNull String packageName) {
        for (String suffix : NON_PLAY_SUFFIXES) {
            if (packageName.endsWith(suffix)) return true;
        }
        return false;
    }

    /** The package on Google Play: this one without a debug build's suffix. */
    @NonNull
    private static String playPackageName(@NonNull Context context) {
        String packageName = context.getPackageName();
        for (String suffix : NON_PLAY_SUFFIXES) {
            if (packageName.endsWith(suffix)) {
                return packageName.substring(0, packageName.length() - suffix.length());
            }
        }
        return packageName;
    }

    private static void checkPlayListingAsync(
            @NonNull Context context,
            @NonNull Callback callback,
            @NonNull AtomicBoolean delivered
    ) {
        if (delivered.get()) return;
        EXECUTOR.execute(() -> {
            try {
                LauncherUpdateInfo info = fetchFromPlayListing(context);
                deliverResult(callback, info, delivered);
            } catch (Throwable throwable) {
                deliverError(callback, throwable, delivered);
            }
        });
    }

    @NonNull
    private static LauncherUpdateInfo fetchFromPlayListing(@NonNull Context context) throws Exception {
        String html = getText(getPlayStoreWebUrl(context));
        String remoteVersion = extractPublishedVersion(html);
        long remoteVersionCode = extractPublishedVersionCode(html);
        if (remoteVersion.isEmpty() && remoteVersionCode <= 0L) {
            throw new IllegalStateException(isInstalledFromPlay(context)
                    ? "Google Play didn't answer the update check. Open DroidBridge on Google Play "
                    + "to see if there's an update."
                    : "This copy of DroidBridge wasn't installed from Google Play, so Google Play "
                    + "can't check it for updates. Open DroidBridge on Google Play to see the "
                    + "latest version.");
        }

        String currentVersion = currentVersionName();
        boolean updateAvailable = !remoteVersion.isEmpty()
                ? isRemoteVersionNewer(currentVersion, remoteVersion)
                : remoteVersionCode > BuildConfig.VERSION_CODE;
        return new LauncherUpdateInfo(
                remoteVersion,
                remoteVersionCode,
                getPlayStoreWebUrl(context),
                updateAvailable,
                false
        );
    }

    /** This build's version name without a debug build's suffix ("-debug"). */
    @NonNull
    private static String currentVersionName() {
        String name = BuildConfig.VERSION_NAME;
        int dash = name.indexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    @NonNull
    private static String extractPublishedVersion(@NonNull String html) {
        for (Pattern pattern : PLAY_VERSION_PATTERNS) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String value = decodeBasicHtmlEntities(matcher.group(1)).trim();
                if (!value.isEmpty() && !"Varies with device".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    private static long extractPublishedVersionCode(@NonNull String html) {
        for (Pattern pattern : PLAY_VERSION_CODE_PATTERNS) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (Throwable ignored) {
                }
            }
        }
        return -1L;
    }

    @NonNull
    private static String decodeBasicHtmlEntities(@NonNull String value) {
        return value
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static boolean isRemoteVersionNewer(@NonNull String currentVersion, @NonNull String remoteVersion) {
        List<Integer> current = extractVersionNumbers(currentVersion);
        List<Integer> remote = extractVersionNumbers(remoteVersion);
        if (remote.isEmpty()) return false;
        if (current.isEmpty()) return true;

        int max = Math.max(current.size(), remote.size());
        for (int i = 0; i < max; i++) {
            int c = i < current.size() ? current.get(i) : 0;
            int r = i < remote.size() ? remote.get(i) : 0;
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    @NonNull
    private static List<Integer> extractVersionNumbers(@NonNull String value) {
        String clean = value.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }

        ArrayList<Integer> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(clean);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group(1)));
            } catch (Throwable ignored) {
            }
        }
        return numbers;
    }

    @NonNull
    public static String getPlayStoreWebUrl(@NonNull Context context) {
        String packageName = playPackageName(context);
        try {
            return "https://play.google.com/store/apps/details?id="
                    + URLEncoder.encode(packageName, "UTF-8");
        } catch (Throwable ignored) {
            return "https://play.google.com/store/apps/details?id=" + packageName;
        }
    }

    @NonNull
    public static String getPlayStoreMarketUrl(@NonNull Context context) {
        return "market://details?id=" + playPackageName(context);
    }

    @NonNull
    private static String getText(@NonNull String requestUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(requestUrl + "&hl=en_US&gl=US");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "Chrome/142.0 Mobile Safari/537.36 DroidBridge/"
                            + BuildConfig.VERSION_NAME
            );

            int code = connection.getResponseCode();
            if (code == 404) {
                throw new IllegalStateException(
                        "DroidBridge's Google Play page wasn't found (it may not be published yet).");
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Google Play update check failed. HTTP " + code);
            }

            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void deliverResult(
            @NonNull Callback callback,
            @NonNull LauncherUpdateInfo info,
            @NonNull AtomicBoolean delivered
    ) {
        if (!delivered.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            if (info.updateAvailable) {
                callback.onResult(info);
            } else {
                callback.onNoUpdate(info);
            }
        });
    }

    private static void deliverError(
            @NonNull Callback callback,
            @NonNull Throwable throwable,
            @NonNull AtomicBoolean delivered
    ) {
        if (!delivered.compareAndSet(false, true)) return;
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "Unable to check Google Play for launcher updates.";
        }
        String finalMessage = message;
        MAIN.post(() -> callback.onError(finalMessage, throwable));
    }
}
