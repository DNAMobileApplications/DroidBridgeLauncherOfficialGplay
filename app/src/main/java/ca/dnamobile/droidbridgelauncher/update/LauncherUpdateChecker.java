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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
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
 * <p>DroidBridge first asks the official Google Play in-app update API when Play Core is
 * present in the final application. The call is performed through reflection so this source
 * remains compatible with builds that do not bundle Play Core. If Play Core is unavailable,
 * the checker falls back to the public Google Play listing and compares the published version
 * name. No direct APK download endpoint is used.</p>
 */
public final class LauncherUpdateChecker {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private static final Pattern[] PLAY_VERSION_PATTERNS = new Pattern[]{
            Pattern.compile("itemprop=[\\\"']softwareVersion[\\\"'][^>]*content=[\\\"']([^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("content=[\\\"']([^\\\"']+)[\\\"'][^>]*itemprop=[\\\"']softwareVersion[\\\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\"softwareVersion\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\\\"softwareVersion\\\\\\\"\\s*:\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\"", Pattern.CASE_INSENSITIVE)
    };

    private static final Pattern[] PLAY_VERSION_CODE_PATTERNS = new Pattern[]{
            Pattern.compile("\\\"versionCode\\\"\\s*:\\s*\\\"?(\\d+)\\\"?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\\\\\\"versionCode\\\\\\\"\\s*:\\s*\\\\\\\"?(\\d+)\\\\\\\"?", Pattern.CASE_INSENSITIVE)
    };

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

        if (tryCheckWithPlayCore(appContext, callback, delivered)) {
            return;
        }

        checkPlayListingAsync(appContext, callback, delivered);
    }

    /**
     * Uses the official Play update API when its classes are available in the app build.
     * No compile-time Play Core dependency is required by this source file.
     */
    private static boolean tryCheckWithPlayCore(
            @NonNull Context context,
            @NonNull Callback callback,
            @NonNull AtomicBoolean delivered
    ) {
        try {
            Class<?> factoryClass = Class.forName("com.google.android.play.core.appupdate.AppUpdateManagerFactory");
            Method createMethod = factoryClass.getMethod("create", Context.class);
            Object manager = createMethod.invoke(null, context);
            if (manager == null) return false;

            Class<?> managerClass = Class.forName("com.google.android.play.core.appupdate.AppUpdateManager");
            Method getInfoMethod = managerClass.getMethod("getAppUpdateInfo");
            Object task = getInfoMethod.invoke(manager);
            if (task == null) return false;

            Class<?> listenerClass = Class.forName("com.google.android.gms.tasks.OnCompleteListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if (!"onComplete".equals(method.getName()) || args == null || args.length == 0) {
                            return null;
                        }

                        Object completedTask = args[0];
                        try {
                            Class<?> taskClass = Class.forName("com.google.android.gms.tasks.Task");
                            boolean successful = (Boolean) taskClass
                                    .getMethod("isSuccessful")
                                    .invoke(completedTask);
                            if (!successful) {
                                checkPlayListingAsync(context, callback, delivered);
                                return null;
                            }

                            Object appUpdateInfo = taskClass
                                    .getMethod("getResult")
                                    .invoke(completedTask);
                            if (appUpdateInfo == null) {
                                checkPlayListingAsync(context, callback, delivered);
                                return null;
                            }

                            Class<?> appUpdateInfoClass = Class.forName("com.google.android.play.core.appupdate.AppUpdateInfo");
                            int availability = ((Number) appUpdateInfoClass
                                    .getMethod("updateAvailability")
                                    .invoke(appUpdateInfo)).intValue();
                            long availableVersionCode = readAvailableVersionCode(appUpdateInfo);

                            int updateNotAvailable = readPlayConstant("UPDATE_NOT_AVAILABLE", 1);
                            int updateAvailable = readPlayConstant("UPDATE_AVAILABLE", 2);
                            int updateInProgress = readPlayConstant("DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS", 3);

                            if (availability != updateNotAvailable
                                    && availability != updateAvailable
                                    && availability != updateInProgress) {
                                // UNKNOWN can happen for sideloaded/debug installs. The public Play
                                // listing is still useful in that case, so do not report a false
                                // "up to date" result.
                                checkPlayListingAsync(context, callback, delivered);
                                return null;
                            }

                            boolean hasUpdate = availability == updateAvailable || availability == updateInProgress;
                            LauncherUpdateInfo info = new LauncherUpdateInfo(
                                    "",
                                    availableVersionCode,
                                    getPlayStoreWebUrl(context),
                                    hasUpdate,
                                    true
                            );
                            deliverResult(callback, info, delivered);
                        } catch (Throwable ignored) {
                            checkPlayListingAsync(context, callback, delivered);
                        }
                        return null;
                    }
            );

            Class<?> taskClass = Class.forName("com.google.android.gms.tasks.Task");
            Method addOnComplete = taskClass.getMethod("addOnCompleteListener", listenerClass);
            addOnComplete.invoke(task, listener);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long readAvailableVersionCode(@NonNull Object appUpdateInfo) {
        try {
            Class<?> appUpdateInfoClass = Class.forName("com.google.android.play.core.appupdate.AppUpdateInfo");
            Object result = appUpdateInfoClass.getMethod("availableVersionCode").invoke(appUpdateInfo);
            return result instanceof Number ? ((Number) result).longValue() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static int readPlayConstant(@NonNull String name, int fallback) {
        try {
            Class<?> availabilityClass = Class.forName("com.google.android.play.core.install.model.UpdateAvailability");
            Field field = availabilityClass.getField(name);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
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
            throw new IllegalStateException(
                    "Google Play did not expose the published version for this check. "
                            + "You can still open DroidBridge on Google Play and update from there."
            );
        }

        boolean updateAvailable = !remoteVersion.isEmpty()
                ? isRemoteVersionNewer(BuildConfig.VERSION_NAME, remoteVersion)
                : remoteVersionCode > BuildConfig.VERSION_CODE;
        return new LauncherUpdateInfo(
                remoteVersion,
                remoteVersionCode,
                getPlayStoreWebUrl(context),
                updateAvailable,
                false
        );
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
        try {
            return "https://play.google.com/store/apps/details?id="
                    + URLEncoder.encode(context.getPackageName(), "UTF-8");
        } catch (Throwable ignored) {
            return "https://play.google.com/store/apps/details?id=" + context.getPackageName();
        }
    }

    @NonNull
    public static String getPlayStoreMarketUrl(@NonNull Context context) {
        return "market://details?id=" + context.getPackageName();
    }

    @NonNull
    private static String getText(@NonNull String requestUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(requestUrl);
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
