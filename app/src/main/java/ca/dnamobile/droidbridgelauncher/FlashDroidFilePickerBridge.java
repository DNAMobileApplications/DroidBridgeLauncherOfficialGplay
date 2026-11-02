package ca.dnamobile.droidbridgelauncher;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Properties;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Android Storage Access Framework side of Flash Droid's file-dialog bridge.
 *
 * Flashback's desktop file chooser is not usable from the Minecraft JVM on Android. Flash Droid
 * writes a small request file in the active instance; this class notices it, opens the Android
 * document picker, copies the selected document into the instance, then writes a normal filesystem
 * path back for Flashback.
 */
public final class FlashDroidFilePickerBridge {
    private static final String TAG = "FlashDroidFilePicker";
    private static final int REQUEST_CODE = 0x4644; // "FD"
    private static final long POLL_MS = 200L;

    private static final String BRIDGE_DIR = ".droidbridge";
    private static final String REQUEST_NAME = "flashdroid-file-dialog.request.properties";
    private static final String RESPONSE_NAME = "flashdroid-file-dialog.response.properties";

    @NonNull private final GameActivity activity;
    @NonNull private final File gameDirectory;
    @NonNull private final File bridgeDirectory;
    @NonNull private final File requestFile;
    @NonNull private final File responseFile;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running;
    @Nullable private String pendingToken;
    @Nullable private String pendingFilters;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                pollRequest();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to process Flash Droid file request", throwable);
            }
            if (running) handler.postDelayed(this, POLL_MS);
        }
    };

    public FlashDroidFilePickerBridge(@NonNull GameActivity activity, @NonNull File gameDirectory) {
        this.activity = activity;
        this.gameDirectory = gameDirectory;
        this.bridgeDirectory = new File(gameDirectory, BRIDGE_DIR);
        this.requestFile = new File(bridgeDirectory, REQUEST_NAME);
        this.responseFile = new File(bridgeDirectory, RESPONSE_NAME);
    }

    public void start() {
        if (running) return;
        running = true;
        if (!bridgeDirectory.exists() && !bridgeDirectory.mkdirs()) {
            Logging.i(TAG, "Bridge directory will be retried: " + bridgeDirectory);
        }
        handler.post(pollRunnable);
        Logging.i(TAG, "Flash Droid Android file picker bridge active gameDir=" + gameDirectory);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        pendingToken = null;
        pendingFilters = null;
    }

    /** Returns true when this result belonged to the Flash Droid document picker. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE) return false;

        final String token = pendingToken;
        pendingToken = null;
        pendingFilters = null;

        if (token == null || token.trim().isEmpty()) {
            Logging.i(TAG, "Ignoring file picker result without a pending Flash Droid token");
            return true;
        }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            writeResponse(token, "cancel", null, null);
            Logging.i(TAG, "Flash Droid file picker cancelled");
            return true;
        }

        Uri uri = data.getData();
        try {
            File imported = importSelectedDocument(uri, token);
            writeResponse(token, "ok", imported.getAbsolutePath(), null);
            Logging.i(TAG, "Flash Droid file selected: " + imported.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to import selected Flash Droid file", throwable);
            writeResponse(token, "error", null, rootMessage(throwable));
        }
        return true;
    }

    private void pollRequest() throws IOException {
        if (pendingToken != null || !requestFile.isFile()) return;

        Properties request = loadProperties(requestFile);
        String token = request.getProperty("token", "").trim();
        if (token.isEmpty()) {
            // Avoid repeatedly tripping over a partial/stale request.
            // Flash Droid writes atomically, so this should only occur after manual corruption.
            //noinspection ResultOfMethodCallIgnored
            requestFile.delete();
            return;
        }

        pendingToken = token;
        pendingFilters = request.getProperty("filters", "");
        // Consume the request before launching the picker so lifecycle pauses do not relaunch it.
        //noinspection ResultOfMethodCallIgnored
        requestFile.delete();

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(resolveMimeType(pendingFilters));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            activity.startActivityForResult(intent, REQUEST_CODE);
            Logging.i(TAG, "Opened Android document picker token=" + token
                    + " filters=" + pendingFilters + " mime=" + intent.getType());
        } catch (Throwable throwable) {
            pendingToken = null;
            pendingFilters = null;
            writeResponse(token, "error", null, rootMessage(throwable));
            Logging.e(TAG, "Unable to open Android document picker", throwable);
        }
    }

    @NonNull
    private File importSelectedDocument(@NonNull Uri uri, @NonNull String token) throws IOException {
        File importsDir = new File(gameDirectory, "flashback/imports");
        if (!importsDir.exists() && !importsDir.mkdirs() && !importsDir.isDirectory()) {
            throw new IOException("Unable to create " + importsDir);
        }

        String displayName = queryDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "selected-file";
        }
        displayName = sanitizeFileName(displayName);
        if (displayName.isEmpty()) displayName = "selected-file";

        String prefix = token.length() > 8 ? token.substring(0, 8) : token;
        File output = uniqueFile(importsDir, prefix + "-" + displayName);
        File temp = new File(output.getParentFile(), output.getName() + ".part");

        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Android document provider returned no input stream");
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) out.write(buffer, 0, read);
                }
                out.flush();
            }
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (throwable instanceof IOException) throw (IOException) throwable;
            throw new IOException("Unable to copy selected Android document", throwable);
        }

        if (!temp.renameTo(output)) {
            copyFile(temp, output);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return output;
    }

    @Nullable
    private String queryDisplayName(@NonNull Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            );
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last;
    }

    private void writeResponse(
            @NonNull String token,
            @NonNull String status,
            @Nullable String path,
            @Nullable String message
    ) {
        try {
            if (!bridgeDirectory.exists() && !bridgeDirectory.mkdirs() && !bridgeDirectory.isDirectory()) {
                throw new IOException("Unable to create bridge directory " + bridgeDirectory);
            }
            Properties response = new Properties();
            response.setProperty("token", token);
            response.setProperty("status", status);
            if (path != null) response.setProperty("path", path);
            if (message != null) response.setProperty("message", message);
            writePropertiesAtomic(responseFile, response);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write Flash Droid file picker response", throwable);
        }
    }

    @NonNull
    private static String resolveMimeType(@Nullable String rawFilters) {
        String filters = rawFilters == null ? "" : rawFilters.toLowerCase(Locale.ROOT);
        if (filters.isEmpty() || filters.contains("*")) return "*/*";
        if (containsAny(filters, "zip", "flashback")) return "application/zip";
        if (containsAny(filters, "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus")) {
            return "audio/*";
        }
        if (containsAny(filters, "png", "jpg", "jpeg", "webp", "bmp", "gif")) {
            return "image/*";
        }
        return "*/*";
    }

    private static boolean containsAny(@NonNull String haystack, @NonNull String... needles) {
        for (String needle : needles) if (haystack.contains(needle)) return true;
        return false;
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String name) {
        StringBuilder out = new StringBuilder(Math.min(name.length(), 160));
        for (int i = 0; i < name.length() && out.length() < 160; i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\u0000' || c == ':' || c == '\n' || c == '\r') {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        String cleaned = out.toString().trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        return cleaned;
    }

    @NonNull
    private static File uniqueFile(@NonNull File directory, @NonNull String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10_000; i++) {
            candidate = new File(directory, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, System.currentTimeMillis() + "-" + name);
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
        }
    }

    @NonNull
    private static Properties loadProperties(@NonNull File file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static void writePropertiesAtomic(@NonNull File target, @NonNull Properties properties)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create " + parent);
        }
        File temp = new File(parent, target.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            properties.store(out, "DroidBridge Flash Droid file picker response");
            out.flush();
        }
        if (target.exists() && !target.delete()) {
            // renameTo cannot replace on all Android/Linux combinations.
            copyFile(temp, target);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        if (!temp.renameTo(target)) {
            copyFile(temp, target);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    @NonNull
    private static String rootMessage(@NonNull Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + message;
    }
}
