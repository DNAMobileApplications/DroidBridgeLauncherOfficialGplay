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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.modcompat.FFmpegPluginCompat;

/**
 * Android Storage Access Framework side of Music Swap's file-dialog bridge.
 *
 * Music Swap's desktop file chooser is not usable from the Minecraft JVM on Android. Music Swap
 * writes a small request file in the active instance; this class notices it, opens the Android
 * document picker, copies the selected audio document into the instance, then writes a normal
 * filesystem path back to the mod.
 */
public final class MusicSwapFilePickerBridge {
    private static final String TAG = "MusicSwapFilePicker";
    private static final int REQUEST_CODE = 0x4D53; // "MS"
    private static final long POLL_MS = 200L;
    private static final long CONVERSION_TIMEOUT_MINUTES = 12L;

    private static final String BRIDGE_DIR = ".droidbridge";
    private static final String REQUEST_NAME = "music-swap-file-dialog.request.properties";
    private static final String RESPONSE_NAME = "music-swap-file-dialog.response.properties";

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
                Logging.e(TAG, "Unable to process Music Swap file request", throwable);
            }
            if (running) handler.postDelayed(this, POLL_MS);
        }
    };

    public MusicSwapFilePickerBridge(@NonNull GameActivity activity, @NonNull File gameDirectory) {
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
        Logging.i(TAG, "Music Swap Android file picker bridge active gameDir=" + gameDirectory);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        pendingToken = null;
        pendingFilters = null;
    }

    /** Returns true when this result belonged to the Music Swap document picker. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE) return false;

        final String token = pendingToken;
        pendingToken = null;
        pendingFilters = null;

        if (token == null || token.trim().isEmpty()) {
            Logging.i(TAG, "Ignoring file picker result without a pending Music Swap token");
            return true;
        }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            writeResponse(token, "cancel", null, null);
            Logging.i(TAG, "Music Swap file picker cancelled");
            return true;
        }

        final Uri uri = data.getData();
        final String mimeType = activity.getContentResolver().getType(uri);

        // SAF results arrive on the Android UI thread. Copying a large song and especially
        // transcoding MP3 -> Ogg Vorbis must not block GameActivity / the Minecraft surface.
        Thread importThread = new Thread(() -> {
            File imported = null;
            try {
                imported = importSelectedDocument(uri, token);
                File ready = prepareSelectedAudio(imported, mimeType);
                writeResponse(token, "ok", ready.getAbsolutePath(), null);
                Logging.i(TAG, "Music Swap file ready: " + ready.getAbsolutePath());
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to import selected Music Swap file", throwable);
                writeResponse(token, "error", null, rootMessage(throwable));
                if (imported != null && isMp3(imported, mimeType)) {
                    //noinspection ResultOfMethodCallIgnored
                    imported.delete();
                }
            }
        }, "MusicSwapAudioImport-" + safeTokenPrefix(token));
        importThread.setDaemon(true);
        importThread.start();
        return true;
    }

    private void pollRequest() throws IOException {
        if (pendingToken != null || !requestFile.isFile()) return;

        Properties request = loadProperties(requestFile);
        String token = request.getProperty("token", "").trim();
        if (token.isEmpty()) {
            // Avoid repeatedly tripping over a partial/stale request.
            // Music Swap writes atomically, so this should only occur after manual corruption.
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
        File importsDir = new File(gameDirectory, "music-swap/imports");
        if (!importsDir.exists() && !importsDir.mkdirs() && !importsDir.isDirectory()) {
            throw new IOException("Unable to create " + importsDir);
        }

        String displayName = queryDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "selected-audio";
        }
        displayName = sanitizeFileName(displayName);
        if (displayName.isEmpty()) displayName = "selected-audio";

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


    @NonNull
    private File prepareSelectedAudio(@NonNull File imported, @Nullable String mimeType) throws IOException {
        if (isOgg(imported, mimeType)) {
            Logging.i(TAG, "Music Swap OGG selected; using direct byte-for-byte import: "
                    + imported.getAbsolutePath());
            return imported;
        }
        if (!isMp3(imported, mimeType)) {
            throw new IOException("Choose an Ogg Vorbis (.ogg) or MP3 (.mp3) audio file.");
        }
        return convertMp3ToOgg(imported);
    }

    @NonNull
    private File convertMp3ToOgg(@NonNull File input) throws IOException {
        FFmpegPluginCompat.Result ffmpeg = FFmpegPluginCompat.discoverInstalled(activity);
        if (!ffmpeg.available
                || ffmpeg.executablePath == null
                || ffmpeg.executablePath.trim().isEmpty()
                || ffmpeg.libraryPath == null
                || ffmpeg.libraryPath.trim().isEmpty()) {
            throw new IOException(ffmpeg.errorMessage == null || ffmpeg.errorMessage.trim().isEmpty()
                    ? "MP3 import needs the DroidBridge FFmpeg companion plugin."
                    : "MP3 import needs the DroidBridge FFmpeg companion plugin: " + ffmpeg.errorMessage);
        }

        File executable = new File(ffmpeg.executablePath);
        File libraryDir = new File(ffmpeg.libraryPath);
        if (!executable.isFile()) {
            throw new IOException("DroidBridge FFmpeg executable is missing: " + executable);
        }
        if (!libraryDir.isDirectory()) {
            throw new IOException("DroidBridge FFmpeg library directory is missing: " + libraryDir);
        }

        File linker = resolveAndroidSystemLinker();
        if (linker == null) {
            throw new IOException("Android system linker was not found; MP3 conversion cannot start.");
        }

        String base = stripExtension(input.getName());
        if (base.isEmpty()) base = "replacement";
        File output = uniqueFile(input.getParentFile(), base + ".ogg");
        File logFile = new File(output.getParentFile(), output.getName() + ".ffmpeg.log");
        File tempOutput = new File(output.getParentFile(), output.getName() + ".part.ogg");
        //noinspection ResultOfMethodCallIgnored
        tempOutput.delete();
        //noinspection ResultOfMethodCallIgnored
        logFile.delete();

        List<String> command = new ArrayList<>();
        command.add(linker.getAbsolutePath());
        command.add(executable.getAbsolutePath());
        command.add("-nostdin");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-y");
        command.add("-i");
        command.add(input.getAbsolutePath());
        command.add("-vn");
        command.add("-map_metadata");
        command.add("-1");
        command.add("-c:a");
        command.add("libvorbis");
        command.add("-q:a");
        command.add("5");
        command.add("-ac");
        command.add("2");
        command.add("-ar");
        command.add("48000");
        // Explicit container format keeps the temporary .part.ogg filename unambiguous.
        command.add("-f");
        command.add("ogg");
        command.add(tempOutput.getAbsolutePath());

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(input.getParentFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile);

            // The companion FFmpeg build has DT_NEEDED dependencies beside libffmpeg.so.
            // Keep this child isolated from Minecraft renderer/native-loader state and point
            // Android's dynamic linker at the companion directory only.
            Map<String, String> env = builder.environment();
            env.remove("LD_PRELOAD");
            env.remove("VK_ICD_FILENAMES");
            env.remove("VK_DRIVER_FILES");
            env.remove("MESA_LOADER_DRIVER_OVERRIDE");
            env.remove("GALLIUM_DRIVER");
            env.remove("LIBGL_DRIVERS_PATH");
            env.remove("EGL_DRIVERS_PATH");
            env.put("LD_LIBRARY_PATH", libraryDir.getAbsolutePath());
            File cacheDir = activity.getCacheDir();
            if (cacheDir != null) env.put("TMPDIR", cacheDir.getAbsolutePath());

            Logging.i(TAG, "Converting MP3 to Ogg Vorbis with DroidBridge FFmpeg"
                    + " package=" + ffmpeg.packageName
                    + " linker=" + linker.getAbsolutePath()
                    + " inputBytes=" + input.length());

            process = builder.start();
            boolean finished;
            try {
                finished = process.waitFor(CONVERSION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("MP3 conversion was interrupted.", interrupted);
            }

            if (!finished) {
                process.destroy();
                try {
                    if (!process.waitFor(2L, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                throw new IOException("MP3 conversion timed out after "
                        + CONVERSION_TIMEOUT_MINUTES + " minutes.");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 || !tempOutput.isFile() || tempOutput.length() <= 0L) {
                String detail = readConversionLog(logFile);
                throw new IOException(detail.isEmpty()
                        ? "DroidBridge FFmpeg conversion failed with exit code " + exitCode + "."
                        : "DroidBridge FFmpeg conversion failed (exit " + exitCode + "): " + detail);
            }

            if (!tempOutput.renameTo(output)) {
                copyFile(tempOutput, output);
                //noinspection ResultOfMethodCallIgnored
                tempOutput.delete();
            }

            Logging.i(TAG, "MP3 conversion complete bytes=" + output.length()
                    + " output=" + output.getAbsolutePath());
            // The source MP3 was only an intermediate SAF copy. Music Swap receives the OGG.
            //noinspection ResultOfMethodCallIgnored
            input.delete();
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
            return output;
        } catch (IOException exception) {
            //noinspection ResultOfMethodCallIgnored
            tempOutput.delete();
            throw exception;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static boolean isOgg(@NonNull File file, @Nullable String mimeType) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return name.endsWith(".ogg") || "audio/ogg".equals(mime) || "application/ogg".equals(mime);
    }

    private static boolean isMp3(@NonNull File file, @Nullable String mimeType) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3")
                || "audio/mpeg".equals(mime)
                || "audio/mp3".equals(mime)
                || "audio/x-mp3".equals(mime);
    }

    @Nullable
    private static File resolveAndroidSystemLinker() {
        String[] candidates = new String[] {
                "/system/bin/linker64",
                "/apex/com.android.runtime/bin/linker64",
                "/system/bin/linker",
                "/apex/com.android.runtime/bin/linker"
        };
        for (String path : candidates) {
            File linker = new File(path);
            if (linker.isFile() && linker.canExecute()) return linker;
        }
        return null;
    }

    @NonNull
    private static String readConversionLog(@NonNull File logFile) {
        if (!logFile.isFile()) return "";
        try {
            byte[] bytes = Files.readAllBytes(logFile.toPath());
            int start = Math.max(0, bytes.length - 16 * 1024);
            String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8).trim();
            return text.length() <= 2000 ? text : text.substring(text.length() - 2000);
        } catch (Throwable ignored) {
            return "";
        }
    }

    @NonNull
    private static String stripExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @NonNull
    private static String safeTokenPrefix(@NonNull String token) {
        String value = token.length() > 8 ? token.substring(0, 8) : token;
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
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
            Logging.e(TAG, "Unable to write Music Swap file picker response", throwable);
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
            properties.store(out, "DroidBridge Music Swap file picker response");
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
