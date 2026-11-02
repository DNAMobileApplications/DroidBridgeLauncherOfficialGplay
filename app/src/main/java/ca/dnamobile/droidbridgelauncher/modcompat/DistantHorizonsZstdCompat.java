/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft or Distant Horizons and does not grant rights to
 * Minecraft, Mojang, Microsoft, Distant Horizons, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Android/Bionic native compatibility for Distant Horizons' shaded zstd-jni.
 *
 * DH's shaded zstd-jni reports DroidBridge's OpenJDK runtime as Linux/aarch64,
 * extracts a glibc Linux native, and then fails on Android because that native
 * depends on libpthread.so.0. DroidBridge already packages an Android/Bionic
 * compatible DH zstd JNI. The only thing that changes between DH releases is
 * often the zstd-jni loadLibrary suffix (for example 1.5.7-6 -> 1.5.7-12).
 *
 * Rather than hard-code one DH version, this class reads the exact
 * "zstd-jni_dh-*" name requested by the installed DH jar and stages the
 * packaged Android native under that name in DIR_RUNTIME_MOD. That directory
 * is already part of java.library.path and LD_LIBRARY_PATH for Minecraft.
 */
public final class DistantHorizonsZstdCompat {
    private static final String TAG = "DistantHorizonsZstd";

    private static final String PACKAGED_ANDROID_LIBRARY =
            "libzstd-jni_dh-1.5.7-6.so";
    private static final String REQUEST_PREFIX = "zstd-jni_dh-";
    private static final String KNOWN_DH_332_REQUEST = "zstd-jni_dh-1.5.7-12";
    private static final int MAX_CLASS_BYTES = 2 * 1024 * 1024;

    private DistantHorizonsZstdCompat() {
    }

    public static final class Result {
        public final boolean detected;
        public final boolean prepared;
        @NonNull public final List<String> requestedNames;
        @Nullable public final File sourceLibrary;
        @NonNull public final String summary;

        private Result(
                boolean detected,
                boolean prepared,
                @NonNull List<String> requestedNames,
                @Nullable File sourceLibrary,
                @NonNull String summary
        ) {
            this.detected = detected;
            this.prepared = prepared;
            this.requestedNames = requestedNames;
            this.sourceLibrary = sourceLibrary;
            this.summary = summary;
        }
    }

    /**
     * Prepare aliases before JavaLaunchBuilder finalizes java.library.path.
     * This only writes to DroidBridge private app storage and never modifies
     * the Distant Horizons jar.
     */
    @NonNull
    public static Result prepare(
            @NonNull Context context,
            @NonNull File gameDirectory
    ) {
        ArrayList<File> dhJars = findDistantHorizonsJars(gameDirectory);
        if (dhJars.isEmpty()) {
            return new Result(false, false, new ArrayList<>(), null, "not detected");
        }

        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (File jar : dhJars) {
            collectRequestedLibraryNames(jar, requested);
        }

        // Known-safe fallbacks. The jar scan above is authoritative when it
        // succeeds; these only cover shaded/obfuscated layouts where the
        // Native.class path changes in a future DH build.
        requested.add("zstd-jni_dh-1.5.7-6");
        requested.add(KNOWN_DH_332_REQUEST);

        File source = findPackagedAndroidLibrary(context);
        if (source == null || !source.isFile() || source.length() <= 0L) {
            String summary = "detected but packaged Android DH Zstd JNI is missing";
            Logging.i(TAG, summary);
            return new Result(true, false, new ArrayList<>(requested), source, summary);
        }

        File outputDirectory = PathManager.DIR_RUNTIME_MOD;
        if (outputDirectory == null) {
            outputDirectory = context.getDir("runtime_mod", Context.MODE_PRIVATE);
        }

        if (!outputDirectory.isDirectory()
                && !outputDirectory.mkdirs()
                && !outputDirectory.isDirectory()) {
            String summary = "unable to create runtime native directory "
                    + outputDirectory.getAbsolutePath();
            Logging.i(TAG, summary);
            return new Result(true, false, new ArrayList<>(requested), source, summary);
        }

        int preparedCount = 0;
        ArrayList<String> preparedNames = new ArrayList<>();
        for (String requestedName : requested) {
            String fileName = toLibraryFileName(requestedName);
            if (fileName == null) continue;

            File target = new File(outputDirectory, fileName);
            try {
                installAlias(source, target);
                preparedCount++;
                preparedNames.add(stripLibraryDecorations(fileName));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to stage DH Android Zstd alias "
                        + target.getAbsolutePath(), throwable);
            }
        }

        boolean prepared = preparedCount > 0;
        String summary = prepared
                ? "Android JNI aliases prepared=" + preparedNames
                        + " source=" + source.getName()
                        + " dir=" + outputDirectory.getAbsolutePath()
                : "detected but no Android JNI aliases could be prepared";
        Logging.i(TAG, summary);
        return new Result(true, prepared, new ArrayList<>(requested), source, summary);
    }

    @NonNull
    private static ArrayList<File> findDistantHorizonsJars(@NonNull File gameDirectory) {
        ArrayList<File> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        for (File modsDir : candidateModsDirs(gameDirectory)) {
            File[] files = modsDir.listFiles(File::isFile);
            if (files == null) continue;
            for (File file : files) {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar")) continue;
                String normalized = lower.replace("-", "").replace("_", "");
                if (!normalized.contains("distanthorizons")) continue;

                String key;
                try {
                    key = file.getCanonicalPath();
                } catch (Throwable ignored) {
                    key = file.getAbsolutePath();
                }
                if (seen.add(key)) result.add(file);
            }
        }
        return result;
    }

    @NonNull
    private static List<File> candidateModsDirs(@NonNull File gameDirectory) {
        ArrayList<File> dirs = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addDirectory(dirs, seen, new File(gameDirectory, "mods"));

        File parent = gameDirectory.getParentFile();
        if (parent != null) {
            addDirectory(dirs, seen, new File(parent, "mods"));
        }

        // Some instance layouts pass the instance directory itself rather than
        // its game/ child. Supporting both costs only one directory check.
        if ("mods".equalsIgnoreCase(gameDirectory.getName())) {
            addDirectory(dirs, seen, gameDirectory);
        }
        return dirs;
    }

    private static void addDirectory(
            @NonNull List<File> out,
            @NonNull Set<String> seen,
            @NonNull File directory
    ) {
        if (!directory.isDirectory()) return;
        String key;
        try {
            key = directory.getCanonicalPath();
        } catch (Throwable ignored) {
            key = directory.getAbsolutePath();
        }
        if (seen.add(key)) out.add(directory);
    }

    private static void collectRequestedLibraryNames(
            @NonNull File dhJar,
            @NonNull Set<String> out
    ) {
        try (ZipFile zip = new ZipFile(dhJar)) {
            // Current DH relocates zstd-jni to dhcomgithubluben.*.
            ZipEntry exact = zip.getEntry("dhcomgithubluben/zstd/util/Native.class");
            if (exact != null && !exact.isDirectory()) {
                collectFromEntry(zip, exact, out);
            }

            // Future DH builds may alter the relocation prefix. Only inspect the
            // small zstd Native class family rather than scanning the entire jar.
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".class")) continue;
                if (!lower.contains("zstd") || !lower.endsWith("/native.class")) continue;
                if (exact != null && exact.getName().equals(entry.getName())) continue;
                collectFromEntry(zip, entry, out);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to inspect DH jar for zstd-jni request: "
                    + dhJar.getAbsolutePath(), throwable);
        }
    }

    private static void collectFromEntry(
            @NonNull ZipFile zip,
            @NonNull ZipEntry entry,
            @NonNull Set<String> out
    ) {
        long size = entry.getSize();
        if (size > MAX_CLASS_BYTES) return;
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
            byte[] bytes = readLimited(input, MAX_CLASS_BYTES);
            collectClassUtf8Tokens(bytes, out);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to inspect " + entry.getName() + " for DH Zstd request", throwable);
        }
    }

    private static void collectClassUtf8Tokens(
            @NonNull byte[] bytes,
            @NonNull Set<String> out
    ) {
        // Parse the class constant pool instead of scanning arbitrary class bytes.
        // This prevents bytes belonging to the next constant-pool entry from
        // accidentally becoming part of the requested library name.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0xCAFEBABE) return;
            input.readUnsignedShort(); // minor
            input.readUnsignedShort(); // major
            int constantPoolCount = input.readUnsignedShort();

            for (int index = 1; index < constantPoolCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1: { // CONSTANT_Utf8
                        int length = input.readUnsignedShort();
                        byte[] utf8 = new byte[length];
                        input.readFully(utf8);
                        collectTokenFromUtf8(new String(utf8, StandardCharsets.UTF_8), out);
                        break;
                    }
                    case 3: // Integer
                    case 4: // Float
                        input.skipBytes(4);
                        break;
                    case 5: // Long (two constant-pool slots)
                    case 6: // Double (two constant-pool slots)
                        input.skipBytes(8);
                        index++;
                        break;
                    case 7:  // Class
                    case 8:  // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        input.skipBytes(2);
                        break;
                    case 9:  // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        input.skipBytes(4);
                        break;
                    case 15: // MethodHandle
                        input.skipBytes(3);
                        break;
                    default:
                        // Unknown future constant-pool tag: stop rather than
                        // guessing offsets and manufacturing a false alias.
                        return;
                }
            }
        } catch (Throwable ignored) {
            // A changed/obfuscated DH class layout still gets the known safe
            // -6 and -12 fallbacks added by prepare().
        }
    }

    private static void collectTokenFromUtf8(
            @NonNull String value,
            @NonNull Set<String> out
    ) {
        int searchFrom = 0;
        while (searchFrom < value.length()) {
            int start = value.indexOf(REQUEST_PREFIX, searchFrom);
            if (start < 0) return;

            int end = start + REQUEST_PREFIX.length();
            while (end < value.length() && isVersionTokenChar(value.charAt(end))) {
                end++;
            }

            if (end > start + REQUEST_PREFIX.length()) {
                String token = value.substring(start, end);
                token = stripLibraryDecorations(token);
                if (token.startsWith(REQUEST_PREFIX)
                        && token.length() > REQUEST_PREFIX.length()) {
                    out.add(token);
                }
            }
            searchFrom = Math.max(end, start + REQUEST_PREFIX.length());
        }
    }

    private static boolean isVersionTokenChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == '-';
    }

    @NonNull
    private static byte[] readLimited(@NonNull InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IllegalStateException("class entry exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    @Nullable
    private static File findPackagedAndroidLibrary(@NonNull Context context) {
        ArrayList<File> candidates = new ArrayList<>();
        if (PathManager.DIR_NATIVE_LIB != null && !PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            candidates.add(new File(PathManager.DIR_NATIVE_LIB, PACKAGED_ANDROID_LIBRARY));
        }
        try {
            if (context.getApplicationInfo() != null
                    && context.getApplicationInfo().nativeLibraryDir != null) {
                candidates.add(new File(context.getApplicationInfo().nativeLibraryDir,
                        PACKAGED_ANDROID_LIBRARY));
            }
        } catch (Throwable ignored) {
        }

        for (File candidate : candidates) {
            if (candidate.isFile() && candidate.length() > 0L) return candidate;
        }
        return null;
    }

    private static void installAlias(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("target has no parent: " + target.getAbsolutePath());
        }
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("unable to create " + parent.getAbsolutePath());
        }

        // Always refresh the alias from the APK's packaged native. This prevents
        // an app update from leaving an older private-storage copy behind.
        File temp = new File(parent, target.getName() + ".tmp");
        if (temp.exists() && !temp.delete()) {
            throw new IllegalStateException("unable to replace stale " + temp.getAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(source);
             BufferedInputStream input = new BufferedInputStream(fis);
             FileOutputStream fos = new FileOutputStream(temp);
             BufferedOutputStream output = new BufferedOutputStream(fos)) {
            byte[] buffer = new byte[32 * 1024];
            long written = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                written += count;
            }
            output.flush();
            fos.getFD().sync();
            if (written <= 0L || written != source.length()) {
                throw new IllegalStateException("incomplete DH Zstd alias copy: source="
                        + source.length() + " wrote=" + written);
            }
        }

        ensureLoadablePermissions(temp);
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("unable to replace " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            copyFile(temp, target);
            if (!temp.delete()) temp.deleteOnExit();
        }
        ensureLoadablePermissions(target);
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        try (FileInputStream fis = new FileInputStream(source);
             BufferedInputStream input = new BufferedInputStream(fis);
             FileOutputStream fos = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(fos)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            fos.getFD().sync();
        }
    }

    private static void ensureLoadablePermissions(@NonNull File file) {
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(true, true);
    }

    @Nullable
    private static String toLibraryFileName(@Nullable String requestedName) {
        if (requestedName == null) return null;
        String normalized = stripLibraryDecorations(requestedName.trim());
        if (!normalized.startsWith(REQUEST_PREFIX)
                || normalized.length() <= REQUEST_PREFIX.length()) {
            return null;
        }
        return "lib" + normalized + ".so";
    }

    @NonNull
    private static String stripLibraryDecorations(@NonNull String value) {
        String result = value;
        if (result.startsWith("lib" + REQUEST_PREFIX)) {
            result = result.substring(3);
        }
        if (result.endsWith(".so")) {
            result = result.substring(0, result.length() - 3);
        }
        return result;
    }
}
