/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * Event-driven, networkless exact-icon transport.
 */
package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Network-free replacement for the old localhost live-icon client.
 *
 * The important behavior intentionally matches the working localhost implementation:
 *  - the bridge is NOT ready just because the Android view exists;
 *  - readiness is published by the Fabric companion after its request watcher is armed;
 *  - one Android request causes one Fabric resolveRequest() lookup;
 *  - retries are bounded and spaced, never an idle polling loop;
 *  - the draw path is RAM-only.
 */
final class DualScreenLiveBridge implements Closeable {
    interface Listener {
        void onBridgeChanged();
    }

    private static final int MAX_ICON_CACHE = 256;
    private static final String DIRECTORY = "droidbridge_dual_screen_live_icons_v9";
    private static final int MAX_REQUEST_ATTEMPTS = 5;
    private static final int MAX_REQUEST_ATTEMPTS_MODERN = 14;
    private static final long[] RETRY_DELAYS_MS = {900L, 1300L, 2200L, 3000L};
    // A brand-new 26.2/26.3 instance can finish its modern GuiItemAtlas/native capture well
    // after the normal request budget. Keep the key pending long enough for FileObserver to
    // accept that late first-run response instead of requiring a full Minecraft restart.
    private static final long LATE_RESPONSE_WATCH_MODERN_MS = 60000L;
    private static final long LATE_RESPONSE_RECHECK_MODERN_MS = 3000L;
    private static final long EXHAUSTED_COOLDOWN_MS = 15000L;
    // FileObserver delivery for atomic rename into app cache is not reliable on every Android
    // filesystem/device combination. During a live exact-icon request, probe the tiny response
    // directory a few times so a completed Minecraft PNG can update the HUD in the same session.
    private static final long[] FAST_RESPONSE_RECHECK_MS = {90L, 180L, 350L, 700L, 1200L};

    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @NonNull private final Object lock = new Object();
    @NonNull private final Map<String, Bitmap> iconCache =
            new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() <= MAX_ICON_CACHE) return false;
                    Bitmap bitmap = eldest.getValue();
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    iconUpdatedAtMs.remove(eldest.getKey());
                    return true;
                }
            };
    @NonNull private final Map<String, Long> iconUpdatedAtMs = new HashMap<>();
    @NonNull private final Map<String, Long> exhaustedUntilMs = new HashMap<>();
    @NonNull private final Map<String, Long> lateWatchDeadlineMs = new HashMap<>();
    @NonNull private final Set<String> pendingKeys = new HashSet<>();
    @NonNull private final File rootDirectory;
    @NonNull private final File requestDirectory;
    @NonNull private final File responseDirectory;
    @NonNull private final File readyFile;
    @Nullable private final File fallbackRootDirectory;
    @Nullable private final File fallbackRequestDirectory;
    @Nullable private final File fallbackResponseDirectory;
    @Nullable private final File fallbackReadyFile;
    private final boolean modernColdStartRuntime;

    @Nullable private volatile Listener listener;
    @Nullable private HandlerThread ioThread;
    @Nullable private Handler ioHandler;
    @Nullable private FileObserver rootObserver;
    @Nullable private FileObserver responseObserver;
    @Nullable private FileObserver fallbackRootObserver;
    @Nullable private FileObserver fallbackResponseObserver;
    private volatile boolean running;
    private volatile boolean protocolReady;

    DualScreenLiveBridge(
            @Nullable Listener listener,
            @NonNull File hudStateFile,
            @NonNull File privateCacheDir
    ) {
        this.listener = listener;
        modernColdStartRuntime = Boolean.getBoolean(
                "droidbridge.dualscreen.android.modern_cold_start"
        );

        // Restore the proven v9 mailbox used by the known-good launcher builds. 26.2 still
        // gets its dedicated renderer transformer in the JVM, but Android and the companion
        // once again exchange requests/responses through the exact transport that worked
        // before the v11/per-instance split was introduced.
        rootDirectory = new File(privateCacheDir, DIRECTORY);
        requestDirectory = new File(rootDirectory, "requests");
        responseDirectory = new File(rootDirectory, "responses");
        readyFile = new File(rootDirectory, "ready.current");

        fallbackRootDirectory = null;
        fallbackRequestDirectory = null;
        fallbackResponseDirectory = null;
        fallbackReadyFile = null;
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    boolean isMinecraft262Mailbox() {
        // Retained for source compatibility with DualScreenControlsView. The broken v11
        // transport is no longer selected; 26.2 is back on the proven v9 mailbox.
        return false;
    }

    void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            protocolReady = false;

            HandlerThread thread = new HandlerThread(
                    modernColdStartRuntime
                            ? "DroidBridge-ExactIconMailbox-v9-modern"
                            : "DroidBridge-ExactIconMailbox-v9",
                    Process.THREAD_PRIORITY_BACKGROUND
            );
            thread.start();
            ioThread = thread;
            ioHandler = new Handler(thread.getLooper());
        }

        Handler handler = ioHandler;
        if (handler != null) handler.post(this::startObserversOnIoThread);
    }

    private void startObserversOnIoThread() {
        if (!running) return;
        try {
            ensureDirectory(rootDirectory);
            ensureDirectory(requestDirectory);
            ensureDirectory(responseDirectory);
            if (fallbackRootDirectory != null) ensureDirectory(fallbackRootDirectory);
            if (fallbackRequestDirectory != null) ensureDirectory(fallbackRequestDirectory);
            if (fallbackResponseDirectory != null) ensureDirectory(fallbackResponseDirectory);

            stopObserver(rootObserver);
            stopObserver(responseObserver);
            stopObserver(fallbackRootObserver);
            stopObserver(fallbackResponseObserver);

            rootObserver = createReadyObserver(rootDirectory);
            responseObserver = createResponseObserver(responseDirectory);
            rootObserver.startWatching();
            responseObserver.startWatching();

            if (fallbackRootDirectory != null && fallbackResponseDirectory != null) {
                fallbackRootObserver = createReadyObserver(fallbackRootDirectory);
                fallbackResponseObserver = createResponseObserver(fallbackResponseDirectory);
                fallbackRootObserver.startWatching();
                fallbackResponseObserver.startWatching();
            }

            // Handles the race where either companion mailbox became ready just before
            // observers armed. 26.2 and older versions now share the proven v9 transport.
            refreshReadyOnIoThread();
        } catch (Throwable ignored) {
            // No polling fallback: observer failure must never steal Minecraft frame time.
        }
    }

    @NonNull
    private FileObserver createReadyObserver(@NonNull File root) {
        return new FileObserver(
                root.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MODIFY
        ) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (!running || path == null || !"ready.current".equals(path)) return;
                Handler handler = ioHandler;
                if (handler != null) handler.post(DualScreenLiveBridge.this::refreshReadyOnIoThread);
            }
        };
    }

    @NonNull
    private FileObserver createResponseObserver(@NonNull File responses) {
        return new FileObserver(
                responses.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MODIFY
        ) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (!running) return;
                if (path != null && !path.toLowerCase(Locale.ROOT).endsWith(".png")) return;
                Handler handler = ioHandler;
                if (handler != null) handler.post(DualScreenLiveBridge.this::loadPendingOnIoThread);
            }
        };
    }

    private static void stopObserver(@Nullable FileObserver observer) {
        if (observer == null) return;
        try { observer.stopWatching(); } catch (Throwable ignored) { }
    }

    private void refreshReadyOnIoThread() {
        if (!running) return;
        boolean primaryReady = readyFile.isFile() && readyFile.length() > 0L;
        boolean fallbackReady = fallbackReadyFile != null
                && fallbackReadyFile.isFile() && fallbackReadyFile.length() > 0L;
        boolean ready = primaryReady || fallbackReady;
        boolean changed;
        synchronized (lock) {
            changed = protocolReady != ready;
            protocolReady = ready;
        }
        if (changed) notifyChanged();
        if (ready) loadPendingOnIoThread();
    }

    @Override
    public void close() {
        HandlerThread thread;
        FileObserver root;
        FileObserver responses;
        FileObserver fallbackRoot;
        FileObserver fallbackResponses;
        synchronized (lock) {
            if (!running && ioThread == null) return;
            running = false;
            protocolReady = false;
            pendingKeys.clear();
            exhaustedUntilMs.clear();
            lateWatchDeadlineMs.clear();

            root = rootObserver;
            rootObserver = null;
            responses = responseObserver;
            responseObserver = null;
            fallbackRoot = fallbackRootObserver;
            fallbackRootObserver = null;
            fallbackResponses = fallbackResponseObserver;
            fallbackResponseObserver = null;
            ioHandler = null;
            thread = ioThread;
            ioThread = null;

            for (Bitmap bitmap : iconCache.values()) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
            iconCache.clear();
            iconUpdatedAtMs.clear();
        }
        if (root != null) {
            try { root.stopWatching(); } catch (Throwable ignored) { }
        }
        if (responses != null) {
            try { responses.stopWatching(); } catch (Throwable ignored) { }
        }
        if (fallbackRoot != null) {
            try { fallbackRoot.stopWatching(); } catch (Throwable ignored) { }
        }
        if (fallbackResponses != null) {
            try { fallbackResponses.stopWatching(); } catch (Throwable ignored) { }
        }
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) { thread.quit(); }
        }
        notifyChanged();
    }

    boolean isProtocolReady() {
        return protocolReady;
    }

    boolean suppliesMinecraftRenderedIcons() {
        return protocolReady;
    }

    void restoreMirroredGameHud() {
        // HUD visibility remains controlled by DroidBridge's existing shared control-state file.
    }

    /** RAM-only: never touch disk/hash from the HUD draw path. */
    @Nullable
    Bitmap getIcon(@Nullable String itemId) {
        String key = normalizeKey(itemId);
        if (key.isEmpty()) return null;
        synchronized (lock) {
            Bitmap bitmap = iconCache.get(key);
            return bitmap == null || bitmap.isRecycled() ? null : bitmap;
        }
    }

    long getIconUpdatedAtMs(@Nullable String itemId) {
        String key = normalizeKey(itemId);
        if (key.isEmpty()) return 0L;
        synchronized (lock) {
            Long value = iconUpdatedAtMs.get(key);
            return value == null ? 0L : value;
        }
    }

    boolean isIconKnownMissing(@Nullable String itemId) {
        String key = normalizeKey(itemId);
        if (key.isEmpty()) return true;
        synchronized (lock) {
            Bitmap bitmap = iconCache.get(key);
            return bitmap == null || bitmap.isRecycled();
        }
    }

    void requestIcon(@Nullable String itemId) {
        requestIconInternal(itemId, false);
    }

    void requestIconFresh(@Nullable String itemId) {
        requestIconInternal(itemId, true);
    }

    private void requestIconInternal(@Nullable String itemId, boolean forceFresh) {
        final String key = normalizeKey(itemId);
        if (key.isEmpty() || "minecraft:air".equals(key)) return;

        Handler handler;
        final boolean hadCachedBitmap;
        synchronized (lock) {
            if (!running || !protocolReady) return;
            Bitmap cached = iconCache.get(key);
            hadCachedBitmap = cached != null && !cached.isRecycled();
            if (hadCachedBitmap && !forceFresh) return;
            if (hadCachedBitmap) {
                iconCache.remove(key);
                iconUpdatedAtMs.remove(key);
                if (!cached.isRecycled()) cached.recycle();
            }

            Long exhaustedUntil = exhaustedUntilMs.get(key);
            long now = SystemClock.uptimeMillis();
            if (exhaustedUntil != null && now < exhaustedUntil && !forceFresh) return;
            if (exhaustedUntil != null) exhaustedUntilMs.remove(key);

            // Different dynamic stacks can share the same registry item ID. White and
            // ominous banners are the important example: both are white_banner plus
            // different component fingerprints. The Fabric mailbox intentionally only
            // reconciles a copied render stack while one key for that item is pending.
            // Serialize requests per base item so captures can never be ambiguous.
            String group = requestGroup(key);
            String replaceLatePending = null;
            for (String pending : pendingKeys) {
                if (pending.equals(key) || !group.equals(requestGroup(pending))) continue;
                if (modernColdStartRuntime && lateWatchDeadlineMs.containsKey(pending)) {
                    replaceLatePending = pending;
                    break;
                }
                return;
            }
            if (replaceLatePending != null) {
                pendingKeys.remove(replaceLatePending);
                lateWatchDeadlineMs.remove(replaceLatePending);
            }

            if (!pendingKeys.add(key)) return;
            handler = ioHandler;
        }

        if (handler != null) {
            handler.post(() -> {
                // If Android previously decoded an unusable placeholder/blank response, remove
                // its on-disk copy as well. Otherwise the Fabric mailbox sees an existing PNG
                // and intentionally declines to recapture it.
                if (hadCachedBitmap) deleteResponseOnIoThread(key);

                if (loadOneOnIoThread(key)) {
                    notifyChanged();
                    return;
                }
                sendAttemptOnIoThread(key, 0);
                if (modernColdStartRuntime) scheduleFastResponseRechecks(key);
            });
        }
    }

    private void sendAttemptOnIoThread(@NonNull String key, int attempt) {
        synchronized (lock) {
            if (!running || !protocolReady || !pendingKeys.contains(key)) return;
        }

        if (loadOneOnIoThread(key)) {
            notifyChanged();
            return;
        }

        writeRequestOnIoThread(key, attempt);

        Handler handler = ioHandler;
        if (handler == null) return;
        int maxAttempts = modernColdStartRuntime ? MAX_REQUEST_ATTEMPTS_MODERN : MAX_REQUEST_ATTEMPTS;
        if (attempt + 1 >= maxAttempts) {
            handler.postDelayed(() -> finishAttemptSeriesOnIoThread(key), 1000L);
            return;
        }

        long delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
        handler.postDelayed(() -> sendAttemptOnIoThread(key, attempt + 1), delay);
    }

    private void scheduleFastResponseRechecks(@NonNull String key) {
        Handler handler = ioHandler;
        if (handler == null) return;
        for (long delayMs : FAST_RESPONSE_RECHECK_MS) {
            handler.postDelayed(() -> {
                synchronized (lock) {
                    if (!running || !pendingKeys.contains(key)) return;
                }
                if (loadOneOnIoThread(key)) notifyChanged();
            }, delayMs);
        }
    }

    private void deleteResponseOnIoThread(@NonNull String key) {
        try {
            String hash = sha256(key);
            File response = new File(responseDirectory, hash + ".png");
            if (response.isFile()) response.delete();
            if (fallbackResponseDirectory != null) {
                File fallback = new File(fallbackResponseDirectory, hash + ".png");
                if (fallback.isFile()) fallback.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void finishAttemptSeriesOnIoThread(@NonNull String key) {
        if (loadOneOnIoThread(key)) {
            notifyChanged();
            return;
        }

        // 26.2/26.3 cold-start special case: the companion may complete its first GuiItemAtlas
        // capture after the active retry series. Keep this key in pendingKeys so a late PNG
        // CREATE/MOVED_TO event is still consumed by loadPendingOnIoThread(). Also recheck the
        // file periodically in case an OEM filesystem misses the observer edge. This directly
        // addresses the "first launch blank, second launch works" pattern without changing the
        // renderer/capture transformer or requiring a second Minecraft launch.
        if (modernColdStartRuntime) {
            long deadline;
            synchronized (lock) {
                if (!running || !pendingKeys.contains(key)) return;
                long now = SystemClock.uptimeMillis();
                Long existing = lateWatchDeadlineMs.get(key);
                deadline = existing == null ? now + LATE_RESPONSE_WATCH_MODERN_MS : existing;
                lateWatchDeadlineMs.put(key, deadline);
            }
            scheduleLateResponseWatchModern(key, deadline);
            return;
        }

        synchronized (lock) {
            if (!running) return;
            pendingKeys.remove(key);
            exhaustedUntilMs.put(key, SystemClock.uptimeMillis() + EXHAUSTED_COOLDOWN_MS);
        }
        // Let the HUD advance another fingerprint for the same base item instead
        // of leaving it blocked behind a request that exhausted its retry budget.
        notifyChanged();
    }

    private void scheduleLateResponseWatchModern(@NonNull String key, long deadlineMs) {
        Handler handler = ioHandler;
        if (handler == null) return;
        handler.postDelayed(() -> {
            synchronized (lock) {
                if (!running || !pendingKeys.contains(key)) return;
            }
            if (loadOneOnIoThread(key)) {
                notifyChanged();
                return;
            }

            long now = SystemClock.uptimeMillis();
            if (now >= deadlineMs) {
                synchronized (lock) {
                    if (!running) return;
                    pendingKeys.remove(key);
                    lateWatchDeadlineMs.remove(key);
                    exhaustedUntilMs.put(key, now + EXHAUSTED_COOLDOWN_MS);
                }
                notifyChanged();
                return;
            }

            // Re-emit the request as well as checking for a late response. The first-run
            // capture side is event-driven, so this gives a fully initialized 26.2/26.3 atlas one
            // more chance to consume the same stack key without touching Minecraft's renderer.
            writeRequestOnIoThread(key, MAX_REQUEST_ATTEMPTS_MODERN);
            scheduleLateResponseWatchModern(key, deadlineMs);
        }, LATE_RESPONSE_RECHECK_MODERN_MS);
    }

    private void writeRequestOnIoThread(@NonNull String key, int attempt) {
        boolean wrote = false;
        if (readyFile.isFile() && readyFile.length() > 0L) {
            wrote |= writeRequestToDirectory(requestDirectory, key, attempt);
        }
        if (fallbackReadyFile != null && fallbackRequestDirectory != null
                && fallbackReadyFile.isFile() && fallbackReadyFile.length() > 0L) {
            wrote |= writeRequestToDirectory(fallbackRequestDirectory, key, attempt);
        }
        // A readiness event can race the request. Keep the active v9 mailbox as the
        // last-resort write target so the next retry can be consumed immediately.
        if (!wrote) writeRequestToDirectory(requestDirectory, key, attempt);
    }

    private boolean writeRequestToDirectory(
            @NonNull File directory,
            @NonNull String key,
            int attempt
    ) {
        try {
            ensureDirectory(directory);
            String hash = sha256(key);
            File request = new File(directory, hash + ".req");
            File temp = new File(directory, hash + ".tmp");
            FileOutputStream output = new FileOutputStream(temp, false);
            try {
                String value = key + "\n" + attempt + "\n" + SystemClock.uptimeMillis();
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                try { output.close(); } catch (Throwable ignored) { }
            }
            if (!temp.renameTo(request)) {
                if (request.exists() && !request.delete()) return false;
                if (!temp.renameTo(request)) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void loadPendingOnIoThread() {
        String[] snapshot;
        synchronized (lock) {
            if (!running || pendingKeys.isEmpty()) return;
            snapshot = pendingKeys.toArray(new String[0]);
        }
        boolean changed = false;
        for (String key : snapshot) {
            if (!running) return;
            if (loadOneOnIoThread(key)) changed = true;
        }
        if (changed) notifyChanged();
    }

    private boolean loadOneOnIoThread(@NonNull String key) {
        synchronized (lock) {
            if (!running) return false;
            Bitmap existing = iconCache.get(key);
            if (existing != null && !existing.isRecycled()) {
                pendingKeys.remove(key);
                return false;
            }
        }

        Bitmap decoded = null;
        try {
            String hash = sha256(key);
            decoded = decodeResponseFile(new File(responseDirectory, hash + ".png"), null);
            if (decoded == null && fallbackResponseDirectory != null && fallbackReadyFile != null) {
                // v9 is shared across instances, so only accept a response written for the
                // current mailbox session. That preserves compatibility without allowing a
                // stale 32x32 capture from an older launch to override 26.2's v11 geometry.
                decoded = decodeResponseFile(
                        new File(fallbackResponseDirectory, hash + ".png"),
                        fallbackReadyFile
                );
            }
            if (decoded == null) return false;
            if (isBitmapEffectivelyBlank(decoded)) {
                if (!decoded.isRecycled()) decoded.recycle();
                decoded = null;
                // Never let a transparent/near-empty first capture become authoritative.
                // Removing it allows the next request to force Minecraft to recapture instead
                // of the companion short-circuiting forever because a .png already exists.
                deleteResponseOnIoThread(key);
                return false;
            }

            synchronized (lock) {
                if (!running) {
                    if (!decoded.isRecycled()) decoded.recycle();
                    return false;
                }
                Bitmap old = iconCache.put(key, decoded);
                iconUpdatedAtMs.put(key, SystemClock.uptimeMillis());
                pendingKeys.remove(key);
                exhaustedUntilMs.remove(key);
                lateWatchDeadlineMs.remove(key);
                if (old != null && old != decoded && !old.isRecycled()) old.recycle();
            }
            return true;
        } catch (Throwable ignored) {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            return false;
        }
    }

    @Nullable
    private static Bitmap decodeResponseFile(
            @NonNull File file,
            @Nullable File currentSessionMarker
    ) {
        if (!file.isFile() || file.length() <= 8L) return null;
        if (currentSessionMarker != null) {
            if (!currentSessionMarker.isFile() || currentSessionMarker.length() <= 0L) return null;
            long markerTime = currentSessionMarker.lastModified();
            long responseTime = file.lastModified();
            if (markerTime > 0L && responseTime > 0L && responseTime + 1000L < markerTime) {
                return null;
            }
        }
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            return null;
        }
        return decoded;
    }

    private static boolean isBitmapEffectivelyBlank(@NonNull Bitmap bitmap) {
        if (bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w <= 0 || h <= 0) return true;
            int stepX = Math.max(1, w / 12);
            int stepY = Math.max(1, h / 12);
            int visible = 0;
            int lit = 0;
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    int pixel = bitmap.getPixel(x, y);
                    if (Color.alpha(pixel) <= 8) continue;
                    visible++;
                    if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 18) lit++;
                }
            }
            return visible == 0 || lit < 4;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void ensureDirectory(@NonNull File directory) {
        if (!directory.isDirectory()) {
            try { directory.mkdirs(); } catch (Throwable ignored) { }
        }
    }

    @NonNull
    private static String normalizeKey(@Nullable String value) {
        String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("live_stack:")) key = key.substring("live_stack:".length()).trim();
        return key;
    }

    @NonNull
    private static String requestGroup(@NonNull String key) {
        int fingerprint = key.indexOf("#db-");
        return fingerprint > 0 ? key.substring(0, fingerprint) : key;
    }

    @NonNull
    private static String sha256(@NonNull String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            if (v < 16) out.append('0');
            out.append(Integer.toHexString(v));
        }
        return out.toString();
    }

    private void notifyChanged() {
        Listener current = listener;
        if (current == null) return;
        mainHandler.post(() -> {
            Listener again = listener;
            if (again != null) again.onBridgeChanged();
        });
    }
}
