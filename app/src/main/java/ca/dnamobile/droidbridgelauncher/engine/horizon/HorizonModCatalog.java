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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Horizon's own mod registry (github.com/horizon-rust/horizon-mods): {@code index.json}, which
 * the registry's CI publishes, and its files, which are that repository's release assets.
 *
 * Downloads come only from those releases and are checked by the engine before they are
 * installed ({@link HorizonAddons#verifyWithEngine}: size, SHA-256, revocation, the project's
 * minisign signature, and the file's own manifest against the index entry).
 */
final class HorizonModCatalog {
    static final String INDEX_URL = "https://horizon-rust.github.io/horizon-mods/index.json";
    /** Where files may come from (horizon_mods::index::FILE_HOST). */
    static final String FILE_HOST = "https://github.com/horizon-rust/horizon-mods/releases/download/";
    static final String REGISTRY_PAGE = "https://github.com/horizon-rust/horizon-mods";

    private static final long INDEX_MAX_AGE_MS = 30L * 60L * 1000L;
    private static final long INDEX_MAX_BYTES = 16L << 20;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    private HorizonModCatalog() {
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    static final class Version {
        @NonNull final String version;
        @NonNull final String game;
        @NonNull final String published;
        @NonNull final String changelog;
        @NonNull final String fileName;
        @NonNull final String url;
        /** Lowercase hex, as the index lists it. */
        @NonNull final String sha256;
        final long size;

        Version(@NonNull JSONObject json) {
            version = json.optString("version", "");
            game = json.optString("game", "");
            published = json.optString("published", "");
            changelog = json.optString("changelog", "");
            JSONObject file = json.optJSONObject("file");
            fileName = file == null ? "" : file.optString("name", "");
            url = file == null ? "" : file.optString("url", "");
            size = file == null ? -1 : file.optLong("size", -1);
            sha256 = file == null ? "" : file.optString("sha256", "").toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What a native mod's .hnmod does, read from its files (nothing in it is run): whether it lists
     * itself in Options > Mods, whether it honours its on/off switch there, whether it hooks only
     * Horizon's stable native hook points (game/native_hooks.rs), and the hook points version it
     * needs. Mods that hook only those points are the ones Horizon keeps building across versions,
     * so they are the best fit for DroidBridge's own build of the engine.
     */
    static final class NativeFacts {
        boolean menu;
        boolean offSwitch;
        boolean hookPointsOnly;
        /** The hook points version it needs, or -1 when it names none. */
        int hooks = -1;
        @Nullable String error;

        /** Settings page, off switch, stable hook points, and no newer hook points than the engine's. */
        boolean recommended(int engineHooks) {
            return error == null && menu && offSwitch && hookPointsOnly && !tooNew(engineHooks);
        }

        boolean tooNew(int engineHooks) {
            return engineHooks > 0 && hooks > engineHooks;
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("menu", menu)
                    .put("switch", offSwitch)
                    .put("hookPointsOnly", hookPointsOnly)
                    .put("hooks", hooks);
        }

        @NonNull
        static NativeFacts fromJson(@NonNull JSONObject json) {
            NativeFacts facts = new NativeFacts();
            facts.menu = json.optBoolean("menu", false);
            facts.offSwitch = json.optBoolean("switch", false);
            facts.hookPointsOnly = json.optBoolean("hookPointsOnly", false);
            facts.hooks = json.optInt("hooks", -1);
            return facts;
        }
    }

    private static final Pattern TARGET = Pattern.compile("target\\s*=\\s*\"([^\"]+)\"");
    private static final long MAX_ENTRY_TEXT = 4L << 20;

    /** {@link NativeFacts} for one version of a native mod, cached by the file's SHA-256. Worker thread. */
    @NonNull
    static NativeFacts nativeFacts(@NonNull Context context, @NonNull Project project, @NonNull Version version) {
        NativeFacts failed = new NativeFacts();
        if (!version.sha256.matches("[0-9a-f]{64}")) {
            failed.error = "the registry lists no checksum for it";
            return failed;
        }
        File folder = new File(context.getCacheDir(), "horizon-native-facts");
        File cached = new File(folder, version.sha256 + ".json");
        if (cached.isFile()) {
            try {
                return NativeFacts.fromJson(new JSONObject(readText(cached)));
            } catch (IOException | JSONException ignored) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
        }
        File file = null;
        try {
            file = downloadFile(project, version, folder, null);
            if (!version.sha256.equals(sha256(file))) {
                failed.error = "the downloaded file isn't the one the registry lists";
                return failed;
            }
            NativeFacts facts = analyze(file);
            File partial = new File(folder, "." + cached.getName() + ".partial");
            try (OutputStream output = new FileOutputStream(partial)) {
                output.write(facts.toJson().toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!partial.renameTo(cached)) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            return facts;
        } catch (IOException | JSONException | RuntimeException error) {
            failed.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return failed;
        } finally {
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    @NonNull
    static NativeFacts analyze(@NonNull File file) throws IOException {
        NativeFacts facts = new NativeFacts();
        List<String> targets = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                boolean manifest = "horizon-native.json".equals(name);
                if (!manifest && !name.endsWith(".toml") && !name.endsWith(".rs")) continue;
                String text = readEntry(zip);
                if (manifest) {
                    try {
                        facts.hooks = new JSONObject(text).optInt("hooks", -1);
                    } catch (JSONException error) {
                        throw new IOException("its horizon-native.json can't be read", error);
                    }
                } else if (name.endsWith(".toml")) {
                    Matcher matcher = TARGET.matcher(text);
                    while (matcher.find()) targets.add(matcher.group(1));
                } else if (text.contains("native_on(")) {
                    facts.offSwitch = true;
                }
            }
        }
        boolean hookPointsOnly = !targets.isEmpty();
        for (String target : targets) {
            if (target.endsWith("::native_mods")) facts.menu = true;
            if (!target.contains("native_hooks::")) hookPointsOnly = false;
        }
        facts.hookPointsOnly = hookPointsOnly;
        return facts;
    }

    @NonNull
    private static String readEntry(@NonNull ZipInputStream zip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        long total = 0;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > MAX_ENTRY_TEXT) throw new IOException("a file in it is too large");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    @NonNull
    static String sha256(@NonNull File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is not available", error);
        } catch (FileNotFoundException error) {
            throw new IOException(file.getName() + " is missing", error);
        }
    }

    static final class Project {
        @NonNull final String id;
        @NonNull final String kind;
        @NonNull final String name;
        @NonNull final String summary;
        @NonNull final String authors;
        @NonNull final String license;
        @NonNull final String source;
        final long downloads;
        @NonNull final List<Version> versions = new ArrayList<>();

        Project(@NonNull JSONObject json) {
            id = json.optString("id", "");
            kind = json.optString("kind", "");
            name = json.optString("name", id);
            summary = json.optString("summary", "");
            license = json.optString("license", "");
            source = json.optString("source", "");
            downloads = json.optLong("downloads", 0);
            JSONArray people = json.optJSONArray("authors");
            StringBuilder joined = new StringBuilder();
            if (people != null) {
                for (int i = 0; i < people.length(); i++) {
                    String person = people.optString(i, "");
                    if (person.isEmpty()) continue;
                    if (joined.length() > 0) joined.append(", ");
                    joined.append(person);
                }
            }
            authors = joined.toString();
            JSONArray list = json.optJSONArray("versions");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject version = list.optJSONObject(i);
                    if (version != null) versions.add(new Version(version));
                }
            }
        }

        /** The newest version made for {@code game} (the index lists versions newest first). */
        @Nullable
        Version newestFor(@NonNull String game) {
            for (Version version : versions) {
                if (game.equals(version.game)) return version;
            }
            return null;
        }

        boolean isNative() {
            return "native-mod".equals(kind);
        }

        /** The kinds this screen installs on the phone. */
        @Nullable
        HorizonAddons.Kind installKind() {
            switch (kind) {
                case "mod":
                    return HorizonAddons.Kind.MOD;
                case "shader":
                    return HorizonAddons.Kind.SHADER;
                case "resourcepack":
                    return HorizonAddons.Kind.RESOURCE_PACK;
                default:
                    return null;
            }
        }
    }

    @NonNull
    static File indexFile(@NonNull Context context) {
        return new File(context.getCacheDir(), "horizon-mods-index.json");
    }

    /** The registry's projects, from a copy at most 30 minutes old unless {@code refresh}. */
    @NonNull
    static List<Project> load(@NonNull Context context, boolean refresh) throws IOException {
        File index = indexFile(context);
        boolean fresh = index.isFile()
                && System.currentTimeMillis() - index.lastModified() < INDEX_MAX_AGE_MS;
        if (refresh || !fresh) {
            try {
                download(INDEX_URL, index, INDEX_MAX_BYTES, -1, null);
            } catch (IOException error) {
                if (!index.isFile()) throw error;
                // Offline: the last copy still lists what the registry had.
            }
        }
        return parse(readText(index));
    }

    @NonNull
    static List<Project> parse(@NonNull String text) throws IOException {
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("schema", 0) > 1) {
                throw new IOException("The mod catalog uses a newer format than this DroidBridge reads.");
            }
            JSONArray list = root.optJSONArray("projects");
            List<Project> projects = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject project = list.optJSONObject(i);
                    if (project != null) projects.add(new Project(project));
                }
            }
            return projects;
        } catch (JSONException error) {
            throw new IOException("The mod catalog could not be read.", error);
        }
    }

    /** Downloads {@code version}'s file of {@code project} into a hidden partial file in {@code dir}. */
    @NonNull
    static File downloadFile(
            @NonNull Project project,
            @NonNull Version version,
            @NonNull File dir,
            @Nullable Progress progress
    ) throws IOException {
        if (!version.url.startsWith(FILE_HOST)) {
            throw new IOException(project.name + "'s file is not hosted in the mod catalog.");
        }
        if (version.size <= 0 || version.size > HorizonAddons.MAX_MOD_BYTES) {
            throw new IOException(project.name + "'s file size is not one the registry accepts.");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        File partial = new File(dir, ".download-" + project.id + "-" + System.nanoTime() + ".partial");
        try {
            download(version.url, partial, version.size, version.size, progress);
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw error;
        }
        return partial;
    }

    private static void download(
            @NonNull String address,
            @NonNull File destination,
            long maxBytes,
            long expectedBytes,
            @Nullable Progress progress
    ) throws IOException {
        if (!address.startsWith("https://")) throw new IOException("Only HTTPS downloads are allowed.");
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "DroidBridge-Launcher (add-ons)");
            connection.setRequestProperty("Accept", "*/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("The download answered HTTP " + status + ".");
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("The download was redirected away from HTTPS.");
            }
            long total = expectedBytes > 0 ? expectedBytes : connection.getContentLengthLong();
            long done = 0;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    done += read;
                    if (done > maxBytes) throw new IOException("The download is larger than expected.");
                    output.write(buffer, 0, read);
                    if (progress != null) progress.onProgress(done, total);
                }
                output.flush();
            }
            if (expectedBytes > 0 && done != expectedBytes) {
                throw new IOException("The download was " + done + " bytes; the registry lists " + expectedBytes + ".");
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Could not replace " + destination.getName());
            }
            if (!partial.renameTo(destination)) throw new IOException("Could not save " + destination.getName());
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
        }
    }

    @NonNull
    private static String readText(@NonNull File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
