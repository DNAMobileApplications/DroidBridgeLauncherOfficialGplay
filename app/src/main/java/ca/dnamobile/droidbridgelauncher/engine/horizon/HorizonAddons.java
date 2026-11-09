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
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.horizon.android.HorizonAddonsProvider;

/**
 * The add-ons of one Horizon instance: the files the engine reads from its game folder, beside
 * {@code options.txt} (Horizon's docs/mods.md and docs/android-content.md).
 *
 * <ul>
 *     <li>{@code horizon-mods/}: {@code .hmod} mods (data mods, and sandboxed WebAssembly code
 *     mods on 64-bit builds), and content-mod folders holding {@code horizon-mod.toml}.</li>
 *     <li>{@code horizon-mods.txt}: which mods are on, as {@code mod.<id>:true} lines. A .hmod
 *     mod is off until turned on; a native mod (compiled into the engine) is on until a
 *     {@code mod.<id>:false} line turns it off. Other lines (mod key binds) are kept.</li>
 *     <li>{@code shaderpacks/}: Horizon {@code .hshader} packs and classic {@code shaders/}
 *     ZIPs (experimental in Horizon); {@code horizon-graphics.txt}'s
 *     {@code pickedShaderPack:<file>} is the one in use.</li>
 * </ul>
 *
 * The files are checked with the game's own checks by {@link HorizonAddonsProvider} in the
 * engine's :horizon process, so the engine library never loads into the launcher. Java
 * Fabric/NeoForge/Forge jars never run in Horizon: their code is written against Minecraft's
 * Java classes, which Horizon doesn't have.
 */
public final class HorizonAddons {
    public static final String MODS_DIR = "horizon-mods";
    public static final String MOD_LIST_FILE = "horizon-mods.txt";
    public static final String SHADERS_DIR = "shaderpacks";
    public static final String RESOURCE_PACKS_DIR = "resourcepacks";
    public static final String GRAPHICS_FILE = "horizon-graphics.txt";
    public static final String SHADER_KEY = "pickedShaderPack";
    public static final String MOD_MANIFEST = "horizon-mod.toml";
    public static final String SHADER_MANIFEST = "horizon-shaders.toml";

    /** The biggest file Horizon's mod registry takes (horizon_mods::index::MAX_FILE). */
    public static final long MAX_MOD_BYTES = 64L << 20;
    /** Horizon Android's import limit for packs (docs/android-content.md). */
    public static final long MAX_PACK_BYTES = 256L << 20;

    private HorizonAddons() {
    }

    public enum Kind {
        MOD("mod"),
        SHADER("shader"),
        RESOURCE_PACK("resourcepack");

        @NonNull
        final String engineName;

        Kind(@NonNull String engineName) {
            this.engineName = engineName;
        }
    }

    /** One installed add-on as the screen shows it. */
    public static final class Item {
        @NonNull public final Kind kind;
        @NonNull public final File file;
        @Nullable public String id;
        @Nullable public String name;
        @Nullable public String version;
        @Nullable public String description;
        @NonNull public final List<String> capabilities = new ArrayList<>();
        public boolean code;
        public boolean classicShader;
        /** Why the game won't load it, when its check failed. */
        @Nullable public String problem;
        public boolean inspected;

        Item(@NonNull Kind kind, @NonNull File file) {
            this.kind = kind;
            this.file = file;
        }

        @NonNull
        public String title() {
            String base = name != null && !name.trim().isEmpty() ? name.trim() : file.getName();
            return version == null || version.isEmpty() ? base : base + " " + version;
        }
    }

    /** A native mod compiled into this engine build (tools/android_native_mods.py). */
    public static final class NativeMod {
        @NonNull public final String id;
        @NonNull public final String version;
        /** It lists itself in Options > Mods (a hook on native_mods). */
        public final boolean menu;
        /** It checks its on/off switch there (game.native_on), so mod.<id>:false turns it off. */
        public final boolean offSwitch;
        /** Its name, when the engine gives one (the engine's own bundled mods); else "". */
        @NonNull public final String title;
        @NonNull public final String description;

        NativeMod(@NonNull String id, @NonNull String version, boolean menu, boolean offSwitch,
                @NonNull String title, @NonNull String description) {
            this.id = id;
            this.version = version;
            this.menu = menu;
            this.offSwitch = offSwitch;
            this.title = title;
            this.description = description;
        }
    }

    /**
     * Writes to DroidBridge's launcher log from any thread. The add-ons screen works on a worker
     * thread, and the log's first use in a process loads a native library whose start-up needs the
     * main thread's Looper (it aborts the app on any other thread), so the line goes there.
     */
    static void log(@NonNull String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager.append(message));
    }

    // --- The engine's checks (HorizonAddonsProvider in :horizon) -------------------------------

    @NonNull
    private static Uri providerUri(@NonNull Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".horizon.addons");
    }

    /** Runs one engine operation and returns its {@code value}. Never call on the main thread. */
    @NonNull
    static Object call(@NonNull Context context, @NonNull String operation, @NonNull JSONObject arguments)
            throws IOException {
        Bundle reply;
        try {
            reply = context.getContentResolver().call(providerUri(context), operation, arguments.toString(), null);
        } catch (Throwable error) {
            throw new IOException("The Rust engine could not be reached: " + error.getMessage(), error);
        }
        String text = reply == null ? null : reply.getString(HorizonAddonsProvider.KEY_RESULT);
        if (text == null) throw new IOException("The Rust engine did not answer.");
        try {
            JSONObject answer = new JSONObject(text);
            if (!answer.optBoolean("ok", false)) {
                throw new IOException(answer.optString("error", "The Rust engine refused the file."));
            }
            Object value = answer.opt("value");
            return value == null ? JSONObject.NULL : value;
        } catch (JSONException error) {
            throw new IOException("The Rust engine gave an unreadable answer.", error);
        }
    }

    /** The game's check of {@code file} as {@code kind}: its facts, or an exception saying why. */
    @NonNull
    static JSONObject inspectWithEngine(@NonNull Context context, @NonNull Kind kind, @NonNull File file)
            throws IOException {
        try {
            JSONObject arguments = new JSONObject()
                    .put("kind", kind.engineName)
                    .put("path", file.getAbsolutePath());
            Object value = call(context, "inspect", arguments);
            if (value instanceof JSONObject) return (JSONObject) value;
            throw new IOException("The Rust engine gave no facts for " + file.getName());
        } catch (JSONException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    /** The registry's checks on a downloaded file: size, SHA-256, revocation, signature, manifest. */
    @NonNull
    static String verifyWithEngine(
            @NonNull Context context,
            @NonNull File index,
            @NonNull String projectId,
            @NonNull String version,
            @NonNull File file
    ) throws IOException {
        try {
            JSONObject arguments = new JSONObject()
                    .put("index", index.getAbsolutePath())
                    .put("project", projectId)
                    .put("version", version)
                    .put("path", file.getAbsolutePath());
            Object value = call(context, "verify", arguments);
            return value instanceof JSONObject ? ((JSONObject) value).optString("trust", "unsigned") : "unsigned";
        } catch (JSONException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    /** The native mods compiled into this build; empty when there are none or the engine can't say. */
    @NonNull
    static List<NativeMod> nativeMods(@NonNull Context context) {
        List<NativeMod> mods = new ArrayList<>();
        try {
            Object value = call(context, "native_mods", new JSONObject());
            if (value instanceof JSONArray) {
                JSONArray list = (JSONArray) value;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject mod = list.optJSONObject(i);
                    if (mod == null) continue;
                    String id = mod.optString("id", "");
                    if (id.isEmpty()) continue;
                    // Builds from before these were recorded: assume the usual, a switch.
                    mods.add(new NativeMod(id, mod.optString("version", ""),
                            mod.optBoolean("menu", true), mod.optBoolean("switch", true),
                            mod.optString("title", ""), mod.optString("description", "")));
                }
            }
        } catch (IOException ignored) {
            // An engine without the add-ons bridge, or none bundled: no native mods to list.
        }
        return mods;
    }

    /** A native mod the last DroidBridge build tried and left out, and why. */
    public static final class LeftOut {
        @NonNull public final String id;
        @NonNull public final String version;
        @NonNull public final String reason;

        LeftOut(@NonNull String id, @NonNull String version, @NonNull String reason) {
            this.id = id;
            this.version = version;
            this.reason = reason;
        }
    }

    /** What the build left out (HORIZON_NATIVE_MODS_REPORT); empty when it left none out. */
    @NonNull
    static List<LeftOut> nativeReport(@NonNull Context context) {
        List<LeftOut> report = new ArrayList<>();
        try {
            Object value = call(context, "native_report", new JSONObject());
            if (value instanceof JSONArray) {
                JSONArray list = (JSONArray) value;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.optJSONObject(i);
                    if (entry == null || entry.optString("id", "").isEmpty()) continue;
                    report.add(new LeftOut(entry.optString("id", ""), entry.optString("version", ""),
                            entry.optString("reason", "")));
                }
            }
        } catch (IOException ignored) {
            // An engine from before the report: nothing to show.
        }
        return report;
    }

    /** This engine's native hook points version (NATIVE_HOOKS_API), or -1 when it can't say. */
    static int engineHooksApi(@NonNull Context context) {
        try {
            Object value = call(context, "engine", new JSONObject());
            if (value instanceof JSONObject) return ((JSONObject) value).optInt("nativeHooksApi", -1);
        } catch (IOException ignored) {
        }
        return -1;
    }

    // --- Native mods: installed per instance, or waiting for the next engine build -------------

    /** What an .hnmod says about itself (horizon-native.json). */
    public static final class NativeFile {
        @NonNull public final String id;
        @NonNull public final String version;

        NativeFile(@NonNull String id, @NonNull String version) {
            this.id = id;
            this.version = version;
        }
    }

    /** Reads an .hnmod's horizon-native.json and checks it is a native mod bundle. */
    @NonNull
    public static NativeFile readNativeFile(@NonNull File file) throws IOException {
        String manifest = null;
        boolean bundle = false;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null && seen++ < 20000) {
                String name = entry.getName();
                if ("horizon-native.json".equals(name)) {
                    manifest = readSmallEntry(zip);
                } else if (name.startsWith("mixins/") && name.endsWith("/horizon-mixin.toml")) {
                    bundle = true;
                }
            }
        }
        if (manifest == null || !bundle) {
            throw new IOException("It isn't a native mod (no horizon-native.json and mixins/).");
        }
        try {
            JSONObject json = new JSONObject(manifest);
            String id = json.optString("id", "");
            String version = json.optString("version", "");
            if (!id.matches("[a-z0-9_]{1,64}") || version.isEmpty()) {
                throw new IOException("Its horizon-native.json has no valid id and version.");
            }
            return new NativeFile(id, version);
        } catch (JSONException error) {
            throw new IOException("Its horizon-native.json can't be read.", error);
        }
    }

    @NonNull
    private static String readSmallEntry(@NonNull ZipInputStream zip) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            if (output.size() + read > (1 << 20)) throw new IOException("horizon-native.json is too large.");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Where imported native mods wait for the next engine build: the app's files/horizon-native-mods
     * on shared storage, which horizon-engine/tools/android_native_mods.py copies off the phone
     * over adb when DroidBridge is built. Each mod is {@code <id>.hnmod}, with
     * {@code <id>.instances} listing the game folders to install it in once it is built in.
     */
    @Nullable
    public static File pendingFolder(@NonNull Context context) {
        File folder = context.getExternalFilesDir("horizon-native-mods");
        if (folder == null) return null;
        if (!folder.isDirectory() && !folder.mkdirs()) return null;
        return folder;
    }

    /** The native mods waiting for the next build, by id. */
    @NonNull
    public static List<NativeFile> pendingMods(@NonNull Context context) {
        List<NativeFile> mods = new ArrayList<>();
        File folder = pendingFolder(context);
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return mods;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".hnmod")) continue;
            try {
                mods.add(readNativeFile(file));
            } catch (IOException ignored) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        return mods;
    }

    /** Keeps a checked .hnmod for the next build, to be installed in {@code gameDir} then. */
    public static void savePending(@NonNull Context context, @NonNull File checked, @NonNull NativeFile mod,
                                   @NonNull File gameDir) throws IOException {
        File folder = pendingFolder(context);
        if (folder == null) throw new IOException("DroidBridge's files folder isn't available.");
        commit(checked, new File(folder, mod.id + ".hnmod"), true);
        File instances = new File(folder, mod.id + ".instances");
        List<String> lines = readLines(instances);
        String path = gameDir.getAbsolutePath();
        if (!lines.contains(path)) lines.add(path);
        writeLines(instances, lines);
    }

    public static void removePending(@NonNull Context context, @NonNull String id) {
        File folder = pendingFolder(context);
        if (folder == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(folder, id + ".hnmod").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(folder, id + ".instances").delete();
    }

    /** Installs a native mod built into this engine in one instance (mod.<id>:true). */
    public static void installNative(@NonNull File gameDir, @NonNull String id) throws IOException {
        setSwitch(gameDir, id, true, true);
    }

    /** Removes it from the instance (mod.<id>:false); the code stays in the engine, off. */
    public static void removeNative(@NonNull File gameDir, @NonNull String id) throws IOException {
        setSwitch(gameDir, id, false, true);
    }

    /**
     * Before Horizon starts in {@code gameDir}: native mods start out not installed (a built-in mod
     * with no line in horizon-mods.txt gets mod.<id>:false; the engine's own default is on), and
     * mods imported while they waited for a build are installed where they were imported, now that
     * the engine has them. Worker thread.
     */
    public static void prepareNativeMods(@NonNull Context context, @NonNull File gameDir) {
        List<NativeMod> built = nativeMods(context);
        if (built.isEmpty()) return;
        File folder = pendingFolder(context);
        try {
            Switches switches = readSwitches(gameDir);
            for (NativeMod mod : built) {
                if (folder != null && new File(folder, mod.id + ".hnmod").isFile()) {
                    for (String path : readLines(new File(folder, mod.id + ".instances"))) {
                        if (path.trim().isEmpty()) continue;
                        File dir = new File(path.trim());
                        if (dir.isDirectory()) setSwitch(dir, mod.id, true, true);
                    }
                    removePending(context, mod.id);
                    switches = readSwitches(gameDir);
                }
                if (mod.offSwitch && !switches.on.contains(mod.id) && !switches.off.contains(mod.id)) {
                    setSwitch(gameDir, mod.id, false, true);
                }
            }
        } catch (IOException error) {
            log("Add-ons: native mod defaults not saved: " + error.getMessage());
        }
    }

    // --- Folders ---------------------------------------------------------------------------------

    @NonNull
    public static File folder(@NonNull File gameDir, @NonNull Kind kind) {
        switch (kind) {
            case SHADER:
                return new File(gameDir, SHADERS_DIR);
            case RESOURCE_PACK:
                return new File(gameDir, RESOURCE_PACKS_DIR);
            case MOD:
            default:
                return new File(gameDir, MODS_DIR);
        }
    }

    private static boolean hidden(@NonNull File file) {
        return file.getName().startsWith(".");
    }

    /** The mods the game would load: {@code .hmod} files and folders holding horizon-mod.toml. */
    @NonNull
    public static List<Item> listMods(@NonNull File gameDir) {
        List<Item> items = new ArrayList<>();
        File[] files = folder(gameDir, Kind.MOD).listFiles();
        if (files == null) return items;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (hidden(file)) continue;
            boolean modFile = file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".hmod");
            boolean modFolder = file.isDirectory() && new File(file, MOD_MANIFEST).isFile();
            if (modFile || modFolder) items.add(new Item(Kind.MOD, file));
        }
        return items;
    }

    /** The shader packs Horizon lists: .hshader files, ZIPs, and pack folders. */
    @NonNull
    public static List<Item> listShaders(@NonNull File gameDir) {
        List<Item> items = new ArrayList<>();
        File[] files = folder(gameDir, Kind.SHADER).listFiles();
        if (files == null) return items;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (hidden(file)) continue;
            String lower = file.getName().toLowerCase(Locale.ROOT);
            boolean pack = file.isFile() && (lower.endsWith(".hshader") || lower.endsWith(".zip"));
            boolean packFolder = file.isDirectory()
                    && (new File(file, SHADER_MANIFEST).isFile() || new File(file, "shaders").isDirectory());
            if (pack || packFolder) items.add(new Item(Kind.SHADER, file));
        }
        return items;
    }

    /** Fills {@code item} from the game's check; a failed check becomes its {@code problem}. */
    public static void inspect(@NonNull Context context, @NonNull Item item) {
        item.inspected = true;
        if (item.kind == Kind.SHADER && isClassicShaderPack(item.file)) {
            item.classicShader = true;
            item.name = item.file.getName();
            return;
        }
        try {
            JSONObject facts = inspectWithEngine(context, item.kind, item.file);
            applyFacts(item, facts);
        } catch (IOException error) {
            item.problem = error.getMessage();
        }
    }

    static void applyFacts(@NonNull Item item, @NonNull JSONObject facts) {
        item.id = emptyToNull(facts.optString("id", ""));
        item.version = emptyToNull(facts.optString("version", ""));
        item.name = emptyToNull(facts.optString("name", ""));
        item.description = emptyToNull(facts.optString("description", ""));
        item.code = facts.optBoolean("code", false);
        item.capabilities.clear();
        JSONArray capabilities = facts.optJSONArray("capabilities");
        if (capabilities != null) {
            for (int i = 0; i < capabilities.length(); i++) {
                JSONObject capability = capabilities.optJSONObject(i);
                if (capability == null) continue;
                String text = capability.optString("text", capability.optString("id", ""));
                if (!text.isEmpty()) item.capabilities.add(text);
            }
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value) ? null : value.trim();
    }

    /**
     * A shader pack in the classic {@code shaders/} format (Iris/OptiFine): a folder with a
     * {@code shaders} folder, or a ZIP with entries under {@code shaders/} and no Horizon manifest.
     */
    public static boolean isClassicShaderPack(@NonNull File file) {
        if (file.isDirectory()) {
            return !new File(file, SHADER_MANIFEST).isFile() && new File(file, "shaders").isDirectory();
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) return false;
        boolean shaders = false;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null && seen++ < 20000) {
                String name = entry.getName();
                if (SHADER_MANIFEST.equals(name)) return false;
                if (name.startsWith("shaders/")) shaders = true;
            }
        } catch (IOException ignored) {
            return false;
        }
        return shaders;
    }

    /** True for a ZIP that holds a Horizon pack's manifest at its root. */
    public static boolean isHorizonShaderZip(@NonNull File file) {
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null && seen++ < 20000) {
                if (SHADER_MANIFEST.equals(entry.getName())) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    // --- Switches (horizon-mods.txt) -------------------------------------------------------------

    /** The ids with a {@code mod.<id>:true} line (on) and a {@code mod.<id>:false} line (off). */
    public static final class Switches {
        @NonNull public final Set<String> on = new HashSet<>();
        @NonNull public final Set<String> off = new HashSet<>();

        public boolean contentModOn(@NonNull String id) {
            return on.contains(id);
        }

        /**
         * A built-in native mod is installed in an instance only with its {@code mod.<id>:true}
         * line: DroidBridge writes {@code mod.<id>:false} for the others before Horizon starts
         * ({@link #prepareNativeMods}), so no line means not installed yet.
         */
        public boolean nativeInstalled(@NonNull String id) {
            return on.contains(id);
        }
    }

    @NonNull
    public static Switches readSwitches(@NonNull File gameDir) {
        Switches switches = new Switches();
        for (String line : readLines(new File(gameDir, MOD_LIST_FILE))) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.startsWith("mod.")) continue;
            String id = key.substring(4);
            if ("true".equals(value)) switches.on.add(id);
            if ("false".equals(value)) switches.off.add(id);
        }
        return switches;
    }

    /**
     * Turns mod {@code id} on or off for the next start. A content mod's off is no line (the
     * game's default); a native mod's off is {@code mod.<id>:false}, its on {@code mod.<id>:true}.
     */
    public static synchronized void setSwitch(@NonNull File gameDir, @NonNull String id, boolean on, boolean nativeMod)
            throws IOException {
        File file = new File(gameDir, MOD_LIST_FILE);
        List<String> kept = new ArrayList<>();
        String key = "mod." + id;
        for (String line : readLines(file)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(key)) continue;
            if (!line.trim().isEmpty()) kept.add(line);
        }
        if (on) {
            kept.add(key + ":true");
        } else if (nativeMod) {
            kept.add(key + ":false");
        }
        writeLines(file, kept);
    }

    // --- Shader pick (horizon-graphics.txt) ------------------------------------------------------

    @NonNull
    public static String pickedShader(@NonNull File gameDir) {
        for (String line : readLines(new File(gameDir, GRAPHICS_FILE))) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(SHADER_KEY)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    /** Picks shader pack {@code fileName} (a name in shaderpacks/), or none for an empty name. */
    public static synchronized void pickShader(@NonNull File gameDir, @NonNull String fileName) throws IOException {
        File file = new File(gameDir, GRAPHICS_FILE);
        List<String> kept = new ArrayList<>();
        boolean replaced = false;
        for (String line : readLines(file)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(SHADER_KEY)) {
                if (!replaced) {
                    kept.add(SHADER_KEY + ":" + fileName);
                    replaced = true;
                }
                continue;
            }
            if (!line.trim().isEmpty()) kept.add(line);
        }
        if (!replaced) kept.add(SHADER_KEY + ":" + fileName);
        writeLines(file, kept);
    }

    // --- Files -----------------------------------------------------------------------------------

    @NonNull
    private static List<String> readLines(@NonNull File file) {
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) return lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static void writeLines(@NonNull File file, @NonNull List<String> lines) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        File partial = new File(parent, "." + file.getName() + ".partial");
        try (OutputStream output = new FileOutputStream(partial)) {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        if (!partial.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("Could not save " + file.getName());
        }
    }

    /** The name a picked document has, or {@code fallback}. */
    @NonNull
    public static String displayName(@NonNull Context context, @NonNull Uri uri, @NonNull String fallback) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? fallback : last;
    }

    /** {@code name} as one plain file name: no folders, no leading dot, no odd characters. */
    @NonNull
    public static String safeFileName(@NonNull String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base.length() && out.length() < 120; i++) {
            char c = base.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ' ' || c == '(' || c == ')' || c == '+';
            out.append(plain ? c : '_');
        }
        String result = out.toString().trim();
        while (result.startsWith(".")) result = result.substring(1);
        return result.isEmpty() ? "add-on" : result;
    }

    /** Copies a picked document into {@code dir} under a hidden partial name the game ignores. */
    @NonNull
    public static File stage(@NonNull Context context, @NonNull Uri uri, @NonNull File dir, long maxBytes)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        File partial = new File(dir, ".import-" + System.nanoTime() + ".partial");
        long total = 0;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(partial)) {
            if (input == null) throw new IOException("The file could not be opened.");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("The file is larger than " + (maxBytes >> 20) + " MiB.");
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (IOException | RuntimeException error) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw error instanceof IOException ? (IOException) error : new IOException(error.getMessage(), error);
        }
        return partial;
    }

    /**
     * Moves a checked partial file to {@code destination}. An existing file there is replaced only
     * when {@code replace} says so; it is restored when the move fails.
     */
    public static void commit(@NonNull File partial, @NonNull File destination, boolean replace) throws IOException {
        File previous = null;
        if (destination.exists()) {
            if (!replace) throw new IOException(destination.getName() + " already exists.");
            previous = new File(destination.getParentFile(), "." + destination.getName() + ".previous");
            deleteRecursively(previous);
            if (!destination.renameTo(previous)) throw new IOException("Could not replace " + destination.getName());
        }
        if (!partial.renameTo(destination)) {
            if (previous != null) {
                //noinspection ResultOfMethodCallIgnored
                previous.renameTo(destination);
            }
            throw new IOException("Could not save " + destination.getName());
        }
        if (previous != null) deleteRecursively(previous);
    }

    /** A free name like {@code name (2).ext} beside {@code wanted}. */
    @NonNull
    public static File keepBothName(@NonNull File wanted) {
        String name = wanted.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(wanted.getParentFile(), stem + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(wanted.getParentFile(), stem + " (" + System.currentTimeMillis() + ")" + extension);
    }

    /** Removes an installed add-on: only a file or folder directly in its kind's folder. */
    public static void remove(@NonNull File gameDir, @NonNull Item item) throws IOException {
        File parent = folder(gameDir, item.kind).getCanonicalFile();
        File target = item.file.getAbsoluteFile();
        File targetParent = target.getParentFile();
        if (targetParent == null || !targetParent.getCanonicalFile().equals(parent)) {
            throw new IOException("Only an add-on inside " + parent.getName() + " can be removed.");
        }
        if (isSymlink(target)) {
            throw new IOException("Refusing to remove a linked add-on.");
        }
        deleteRecursively(target);
        if (target.exists()) throw new IOException("Could not remove " + target.getName());
        if (item.kind == Kind.MOD && item.id != null) {
            setSwitch(gameDir, item.id, false, false);
        }
        if (item.kind == Kind.SHADER && pickedShader(gameDir).equals(item.file.getName())) {
            pickShader(gameDir, "");
        }
    }

    private static boolean isSymlink(@NonNull File file) {
        try {
            File canonicalParent = file.getParentFile() == null ? null : file.getParentFile().getCanonicalFile();
            File relinked = canonicalParent == null ? file : new File(canonicalParent, file.getName());
            return !relinked.getCanonicalFile().equals(relinked.getAbsoluteFile());
        } catch (IOException ignored) {
            return true;
        }
    }

    static void deleteRecursively(@NonNull File file) {
        if (!file.exists()) return;
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Removes partial files a cancelled or failed import or download left behind. */
    public static void cleanPartials(@NonNull File gameDir) {
        for (Kind kind : Kind.values()) {
            File[] files = folder(gameDir, kind).listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith(".") && name.endsWith(".partial")) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
    }
}
