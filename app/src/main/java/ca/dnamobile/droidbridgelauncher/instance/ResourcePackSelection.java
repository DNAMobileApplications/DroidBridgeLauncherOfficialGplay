/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.instance;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which resource packs the game uses: the {@code resourcePacks} list in the instance's
 * {@code options.txt}, the same list Minecraft's Resource Packs screen (and the Rust engine's)
 * reads and writes. A pack in the resourcepacks folder is only <i>available</i>; it is used once
 * it is in this list. DroidBridge's Resource Packs tab turns a pack on and off here, so its
 * switch means what the player expects: on = used in game.
 *
 * <p>Pack ids: 1.13 and newer write {@code "file/<name>"} and keep {@code "vanilla"} (and other
 * built-in packs) in the list, bottom pack first. 1.7 to 1.12 write the bare file name, top pack
 * first. 1.6 and older have no such list ({@link #supports} is false there).
 *
 * <p>A pack turned on here is also added to {@code incompatibleResourcePacks}, as the game does
 * when the player accepts its "made for an older/newer version" warning; without that, the game
 * drops a pack whose format doesn't match when it loads the options.
 */
public final class ResourcePackSelection {
    private static final String OPTIONS = "options.txt";
    private static final String KEY = "resourcePacks";
    private static final String INCOMPATIBLE_KEY = "incompatibleResourcePacks";

    private static final Object LOCK = new Object();
    @Nullable private static String cachedPath;
    private static long cachedModified = -1L;
    private static long cachedLength = -1L;
    @NonNull private static List<String> cachedSelection = new ArrayList<>();

    private ResourcePackSelection() {
    }

    /** True when this Minecraft version keeps a resource pack list in options.txt (1.7+). */
    public static boolean supports(@Nullable String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.trim().isEmpty()) return true;
        String value = minecraftVersion.trim().toLowerCase(Locale.US);
        if (value.startsWith("1.")) {
            int minor = minorVersion(value);
            return minor < 0 || minor >= 7;
        }
        // Snapshots: 13w41a was the first 1.7 snapshot.
        int[] snapshot = snapshot(value);
        if (snapshot != null) {
            return snapshot[0] > 13 || (snapshot[0] == 13 && snapshot[1] >= 41);
        }
        // Alpha, beta and the like predate resource packs; year versions (26.x) have the list.
        return !(value.startsWith("a") || value.startsWith("b") || value.startsWith("infdev")
                || value.startsWith("indev") || value.startsWith("classic") || value.startsWith("rd-")
                || value.startsWith("c0."));
    }

    /** True for versions writing {@code "file/<name>"} ids with "vanilla" in the list (1.13+). */
    static boolean usesFileIds(@Nullable String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.trim().isEmpty()) return true;
        String value = minecraftVersion.trim().toLowerCase(Locale.US);
        if (value.startsWith("1.")) {
            int minor = minorVersion(value);
            return minor < 0 || minor >= 13;
        }
        // Snapshots: 17w43a was the first 1.13 snapshot.
        int[] snapshot = snapshot(value);
        if (snapshot != null) {
            return snapshot[0] > 17 || (snapshot[0] == 17 && snapshot[1] >= 43);
        }
        return true;
    }

    /** Whether the pack file {@code fileName} (no ".disabled") is in the game's list. */
    public static boolean isSelected(
            @NonNull File gameDirectory,
            @Nullable String minecraftVersion,
            @NonNull String fileName
    ) {
        List<String> selection = readSelection(new File(gameDirectory, OPTIONS));
        boolean fileIds = idsUseFilePrefix(selection, minecraftVersion);
        return selection.contains(packId(fileName, fileIds));
    }

    /**
     * Puts the pack in the game's list (on top, as the Resource Packs screen does) or takes it
     * out. A missing options.txt is first seeded with DroidBridge's defaults, as a launch would.
     */
    public static void setSelected(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @Nullable String minecraftVersion,
            @NonNull String fileName,
            boolean selected
    ) throws IOException {
        File options = new File(gameDirectory, OPTIONS);
        synchronized (LOCK) {
            if (!options.isFile()) {
                if (!selected) return;
                try {
                    DefaultMinecraftOptionsInstaller.tryInstallIfMissingForLaunch(
                            context, gameDirectory, minecraftVersion);
                } catch (Throwable ignored) {
                    // No defaults for this version: the game fills in the rest itself.
                }
            }
            List<String> lines = options.isFile()
                    ? readLines(options)
                    : new ArrayList<>();

            List<String> selection = parseList(valueOf(lines, KEY));
            List<String> incompatible = parseList(valueOf(lines, INCOMPATIBLE_KEY));
            boolean fileIds = idsUseFilePrefix(selection, minecraftVersion);
            String id = packId(fileName, fileIds);

            selection.remove(id);
            incompatible.remove(id);
            if (selected) {
                if (fileIds) {
                    if (!selection.contains("vanilla")) selection.add(0, "vanilla");
                    selection.add(id);
                } else {
                    selection.add(0, id);
                }
                incompatible.add(id);
            }

            setValue(lines, KEY, toList(selection));
            if (selected || valueOf(lines, INCOMPATIBLE_KEY) != null) {
                setValue(lines, INCOMPATIBLE_KEY, toList(incompatible));
            }
            File parent = options.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            File temporary = new File(gameDirectory, OPTIONS + ".droidbridge-tmp");
            writeLines(temporary, lines);
            if (!temporary.renameTo(options)) {
                writeLines(options, lines);
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            cachedPath = null;
        }
    }

    /** The game's list, re-read only when options.txt changed (rows ask often while scrolling). */
    @NonNull
    private static List<String> readSelection(@NonNull File options) {
        synchronized (LOCK) {
            String path = options.getAbsolutePath();
            long modified = options.lastModified();
            long length = options.length();
            if (path.equals(cachedPath) && modified == cachedModified && length == cachedLength) {
                return cachedSelection;
            }
            List<String> selection = new ArrayList<>();
            if (options.isFile()) {
                try {
                    selection = parseList(valueOf(readLines(options), KEY));
                } catch (IOException ignored) {
                }
            }
            cachedPath = path;
            cachedModified = modified;
            cachedLength = length;
            cachedSelection = selection;
            return selection;
        }
    }

    @NonNull
    private static List<String> readLines(@NonNull File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static void writeLines(@NonNull File file, @NonNull List<String> lines) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, false),
                StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    /** The list's own form wins over the version: a file written by the game says which it is. */
    private static boolean idsUseFilePrefix(@NonNull List<String> selection, @Nullable String version) {
        for (String id : selection) {
            if ("vanilla".equals(id) || id.startsWith("file/")) return true;
        }
        if (!selection.isEmpty()) return false;
        return usesFileIds(version);
    }

    @NonNull
    private static String packId(@NonNull String fileName, boolean fileIds) {
        return fileIds ? "file/" + fileName : fileName;
    }

    @Nullable
    private static String valueOf(@NonNull List<String> lines, @NonNull String key) {
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator > 0 && key.equals(line.substring(0, separator).trim())) {
                return line.substring(separator + 1).trim();
            }
        }
        return null;
    }

    private static void setValue(@NonNull List<String> lines, @NonNull String key, @NonNull String value) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int separator = line.indexOf(':');
            if (separator > 0 && key.equals(line.substring(0, separator).trim())) {
                lines.set(i, key + ":" + value);
                return;
            }
        }
        lines.add(key + ":" + value);
    }

    @NonNull
    private static List<String> parseList(@Nullable String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(value);
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "");
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        } catch (Throwable ignored) {
            // An unreadable list: the game would read it as empty too.
        }
        return out;
    }

    @NonNull
    private static String toList(@NonNull List<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        // Minecraft writes the list as compact JSON; org.json escapes "/" as "\/", which the game
        // reads back the same, but keep the file looking like the game's own.
        return array.toString().replace("\\/", "/");
    }

    private static int minorVersion(@NonNull String value) {
        int index = 2;
        int start = index;
        while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
        if (index == start) return -1;
        try {
            return Integer.parseInt(value.substring(start, index));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Nullable
    private static int[] snapshot(@NonNull String value) {
        if (value.length() >= 5
                && Character.isDigit(value.charAt(0))
                && Character.isDigit(value.charAt(1))
                && value.charAt(2) == 'w'
                && Character.isDigit(value.charAt(3))
                && Character.isDigit(value.charAt(4))) {
            try {
                return new int[]{
                        Integer.parseInt(value.substring(0, 2)),
                        Integer.parseInt(value.substring(3, 5)),
                };
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
