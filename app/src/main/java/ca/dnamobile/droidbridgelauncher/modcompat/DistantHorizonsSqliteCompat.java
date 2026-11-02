/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
 * Android/Bionic compatibility for Distant Horizons' shaded sqlite-jdbc native.
 *
 * DH 3.3.2 can ship an AArch64 Linux sqlite-jdbc native that its shaded loader
 * cannot use on Android. This helper extracts the best ARM64 sqlite-jdbc native
 * from the installed DH jar and, when necessary, rewrites its glibc DT_NEEDED
 * names and symbol-version metadata to the Android/Bionic equivalents before
 * staging it as libsqlitejdbc.so in DroidBridge's runtime native directory.
 */
public final class DistantHorizonsSqliteCompat {
    private static final String TAG = "DistantHorizonsSqlite";
    private static final String OUTPUT_NAME = "libsqlitejdbc.so";
    private static final int MAX_NATIVE_BYTES = 64 * 1024 * 1024;

    private static final long PT_LOAD = 1L;
    private static final long PT_DYNAMIC = 2L;
    private static final long DT_NULL = 0L;
    private static final long DT_NEEDED = 1L;
    private static final long DT_STRTAB = 5L;
    private static final long DT_STRSZ = 10L;
    private static final long DT_DEBUG = 21L;
    private static final long DT_VERSYM = 0x6ffffff0L;
    private static final long DT_VERNEED = 0x6ffffffeL;
    private static final long DT_VERNEEDNUM = 0x6fffffffL;

    // DH 3.3.2 currently bundles a Linux-Musl ARM64 sqlitejdbc native whose
    // DT_NEEDED list is only [libc.so]. Musl exports libm functions from libc,
    // while Android/Bionic keeps them in libm.so. If the native imports one of
    // these functions and has no libm dependency, rewrite its lone libc
    // dependency to libm.so; Android's libm in turn depends on libc, so both
    // symbol sets remain available without growing the ELF dynamic table.
    private static final String[] BIONIC_LIBM_SYMBOLS = new String[]{
            "trunc", "truncf", "floor", "floorf", "ceil", "ceilf",
            "round", "roundf", "pow", "powf", "sqrt", "sqrtf",
            "fmod", "fmodf", "log", "logf", "log10", "log10f",
            "exp", "expf", "sin", "sinf", "cos", "cosf", "tan", "tanf"
    };

    private DistantHorizonsSqliteCompat() {
    }

    public static final class Result {
        public final boolean detected;
        public final boolean prepared;
        @Nullable public final File sourceJar;
        @Nullable public final String sourceEntry;
        @NonNull public final String summary;

        private Result(boolean detected, boolean prepared,
                       @Nullable File sourceJar,
                       @Nullable String sourceEntry,
                       @NonNull String summary) {
            this.detected = detected;
            this.prepared = prepared;
            this.sourceJar = sourceJar;
            this.sourceEntry = sourceEntry;
            this.summary = summary;
        }
    }

    @NonNull
    public static Result prepare(@NonNull Context context, @NonNull File gameDirectory) {
        ArrayList<File> jars = findDistantHorizonsJars(gameDirectory);
        if (jars.isEmpty()) {
            return new Result(false, false, null, null, "not detected");
        }

        NativeCandidate best = null;
        for (File jar : jars) {
            NativeCandidate candidate = findBestSqliteNative(jar);
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
            }
        }

        if (best == null) {
            String summary = "detected but no ARM64 libsqlitejdbc.so was found inside the DH jar";
            Logging.i(TAG, summary);
            return new Result(true, false, null, null, summary);
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
            return new Result(true, false, best.jar, best.entryName, summary);
        }

        try {
            byte[] raw = readEntry(best.jar, best.entryName);
            Adaptation adaptation = adaptForAndroid(raw);
            if (!adaptation.accepted) {
                String summary = "found " + best.entryName + " but Android adaptation was rejected: "
                        + adaptation.reason;
                Logging.i(TAG, summary);
                return new Result(true, false, best.jar, best.entryName, summary);
            }

            File target = new File(outputDirectory, OUTPUT_NAME);
            writeFileAtomically(target, adaptation.data);
            ensureLoadablePermissions(target);

            String summary = "Android sqlitejdbc prepared from=" + best.entryName
                    + " jar=" + best.jar.getName()
                    + " mode=" + adaptation.reason
                    + " target=" + target.getAbsolutePath();
            Logging.i(TAG, summary);
            return new Result(true, true, best.jar, best.entryName, summary);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare DH sqlitejdbc native", throwable);
            String summary = "failed: " + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage());
            return new Result(true, false, best.jar, best.entryName, summary);
        }
    }

    @Nullable
    private static NativeCandidate findBestSqliteNative(@NonNull File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            NativeCandidate best = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith("libsqlitejdbc.so")) continue;
                if (!(lower.contains("aarch64") || lower.contains("arm64"))) continue;
                long size = entry.getSize();
                if (size > MAX_NATIVE_BYTES) continue;

                int score = 10;
                if (lower.contains("linux-android") || lower.contains("android")) score += 100;
                if (lower.contains("linux")) score += 50;
                if (lower.contains("aarch64")) score += 5;
                if (best == null || score > best.score) {
                    best = new NativeCandidate(jar, name, score);
                }
            }
            return best;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to inspect DH jar for sqlitejdbc: " + jar.getAbsolutePath(), throwable);
            return null;
        }
    }

    @NonNull
    private static byte[] readEntry(@NonNull File jar, @NonNull String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException("missing sqlite native entry " + entryName);
            }
            try (InputStream raw = new BufferedInputStream(zip.getInputStream(entry))) {
                return readLimited(raw, MAX_NATIVE_BYTES);
            }
        }
    }

    @NonNull
    private static Adaptation adaptForAndroid(@NonNull byte[] original) {
        try {
            byte[] data = original.clone();
            ElfLayout elf = ElfLayout.parse(data);
            if (elf.machine != 183) {
                return Adaptation.reject("not AArch64 e_machine=" + elf.machine);
            }
            if (elf.dynamicOffset < 0 || elf.dynamicSize < 16) {
                return Adaptation.reject("ELF has no PT_DYNAMIC table");
            }

            long strtabAddress = -1L;
            long strtabSize = -1L;
            ArrayList<DynamicRecord> records = new ArrayList<>();
            int dynamicEnd = checkedInt(Math.min((long) data.length,
                    (long) elf.dynamicOffset + (long) elf.dynamicSize));
            for (int off = elf.dynamicOffset; off + 16 <= dynamicEnd; off += 16) {
                long tag = readU64LE(data, off);
                long value = readU64LE(data, off + 8);
                records.add(new DynamicRecord(off, tag, value));
                if (tag == DT_STRTAB) strtabAddress = value;
                if (tag == DT_STRSZ) strtabSize = value;
                if (tag == DT_NULL) break;
            }

            if (strtabAddress < 0L || strtabSize <= 0L) {
                return Adaptation.reject("ELF dynamic string table is missing");
            }
            int strtabOffset = elf.virtualAddressToFileOffset(strtabAddress);
            if (strtabOffset < 0 || strtabSize > Integer.MAX_VALUE
                    || (long) strtabOffset + strtabSize > data.length) {
                return Adaptation.reject("ELF dynamic string table is outside file");
            }
            int strtabEnd = strtabOffset + (int) strtabSize;

            ArrayList<String> beforeDeps = new ArrayList<>();
            ArrayList<String> afterDeps = new ArrayList<>();
            boolean needsAdaptation = false;

            // First inspect dependencies without mutating anything.
            for (DynamicRecord record : records) {
                if (record.tag != DT_NEEDED) continue;
                if (record.value < 0L || record.value >= strtabSize) {
                    return Adaptation.reject("invalid DT_NEEDED string offset=" + record.value);
                }
                int nameOffset = strtabOffset + checkedInt(record.value);
                String before = readCString(data, nameOffset, strtabEnd);
                beforeDeps.add(before);
                String replacement = androidDependencyFor(before);
                if (replacement != null && !before.equals(replacement)) {
                    needsAdaptation = true;
                } else if (replacement == null && looksLikeDesktopRuntimeDependency(before)) {
                    return Adaptation.reject("unsupported desktop DT_NEEDED=" + before
                            + " dependencies=" + beforeDeps);
                }
            }

            boolean needsBionicMathBridge = !containsDependency(beforeDeps, "libm.so")
                    && importsAnyDynamicSymbol(data, strtabOffset, strtabEnd, BIONIC_LIBM_SYMBOLS);
            if (needsBionicMathBridge) {
                // The DH 3.3.2 Linux-Musl native reports libc.so even though it
                // imports trunc()/other libm functions. On Bionic, libm.so is
                // the correct direct dependency. Reusing the existing libc.so
                // DT_NEEDED string keeps the patch in-place and avoids changing
                // ELF segment sizes; libm.so brings libc.so transitively.
                if (!(containsDependency(beforeDeps, "libc.so")
                        || containsDependency(beforeDeps, "libc.so.6"))) {
                    return Adaptation.reject("imports Android libm symbols but has no libc DT_NEEDED to bridge: "
                            + beforeDeps);
                }
                needsAdaptation = true;
            }

            if (!needsAdaptation) {
                return Adaptation.accept(original.clone(),
                        "native-android dependencies=" + beforeDeps);
            }

            int dependencyPatches = 0;
            int versionTagsRemoved = 0;
            boolean mathBridgeApplied = false;
            for (DynamicRecord record : records) {
                if (record.tag == DT_NEEDED) {
                    int nameOffset = strtabOffset + checkedInt(record.value);
                    String before = readCString(data, nameOffset, strtabEnd);
                    String replacement = androidDependencyFor(before);

                    if (needsBionicMathBridge
                            && ("libc.so".equals(before) || "libc.so.6".equals(before))) {
                        replacement = "libm.so";
                        mathBridgeApplied = true;
                    }

                    if (replacement == null) {
                        afterDeps.add(before);
                        continue;
                    }
                    if (!before.equals(replacement)) {
                        if (!patchCStringInPlace(data, nameOffset, strtabEnd, before, replacement)) {
                            return Adaptation.reject("unable to rewrite DT_NEEDED "
                                    + before + " -> " + replacement);
                        }
                        dependencyPatches++;
                    }
                    afterDeps.add(replacement);
                } else if (record.tag == DT_VERSYM
                        || record.tag == DT_VERNEED
                        || record.tag == DT_VERNEEDNUM) {
                    writeU64LE(data, record.fileOffset, DT_DEBUG);
                    writeU64LE(data, record.fileOffset + 8, 0L);
                    versionTagsRemoved++;
                }
            }

            if (needsBionicMathBridge && !mathBridgeApplied) {
                return Adaptation.reject("unable to apply libc->libm Bionic math bridge");
            }

            int symbolPatches = 0;
            // DH 3.3.x's Linux-Musl sqlite native imports glibc/musl's
            // __assert_fail, which Android/Bionic does not export. This is an
            // assertion-failure path (noreturn); route it to Bionic abort() so
            // the library can be loaded without changing normal SQLite logic.
            // Extra AArch64 argument registers are harmless for abort().
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__assert_fail", "abort");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__errno_location", "__errno");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__xpg_strerror_r", "strerror_r");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "secure_getenv", "getenv");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__isoc99_sscanf", "sscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__isoc99_vsscanf", "vsscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__isoc99_fscanf", "fscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__isoc99_vfscanf", "vfscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__isoc99_scanf", "scanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "__isoc99_vscanf", "vscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "open64", "open");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "fopen64", "fopen");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "freopen64", "freopen");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "lseek64", "lseek");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "mmap64", "mmap");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "stat64", "stat");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "fstat64", "fstat");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "lstat64", "lstat");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "pread64", "pread");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "pwrite64", "pwrite");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "fcntl64", "fcntl");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "truncate64", "truncate");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "ftruncate64", "ftruncate");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd, "readdir64", "readdir");

            String mode = "linux-aarch64->android needed=" + beforeDeps + "->" + afterDeps
                    + " dependencyPatches=" + dependencyPatches
                    + " bionicMathBridge=" + mathBridgeApplied
                    + " versionTagsRemoved=" + versionTagsRemoved
                    + " symbolAliases=" + symbolPatches;
            return Adaptation.accept(data, mode);
        } catch (Throwable throwable) {
            return Adaptation.reject(throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage()));
        }
    }

    private static boolean containsDependency(@NonNull List<String> dependencies,
                                              @NonNull String wanted) {
        for (String dependency : dependencies) {
            if (wanted.equals(dependency)) return true;
        }
        return false;
    }

    private static boolean importsAnyDynamicSymbol(byte[] data, int start, int end,
                                                   @NonNull String[] names) {
        for (String name : names) {
            if (containsCString(data, start, end, name)) return true;
        }
        return false;
    }

    private static boolean containsCString(byte[] data, int start, int end,
                                           @NonNull String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        for (int i = start; i + needle.length < end; i++) {
            if (i > start && data[i - 1] != 0) continue;
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match && data[i + needle.length] == 0) return true;
        }
        return false;
    }

    private static boolean containsDesktopDependency(@NonNull List<String> dependencies) {
        for (String dependency : dependencies) {
            if (looksLikeDesktopRuntimeDependency(dependency)) return true;
        }
        return false;
    }

    @Nullable
    private static String androidDependencyFor(@NonNull String dependency) {
        switch (dependency) {
            case "libc.so.6": return "libc.so";
            case "libm.so.6": return "libm.so";
            case "libdl.so.2": return "libdl.so";
            case "libpthread.so.0":
            case "librt.so.1":
            case "libresolv.so.2": return "libc.so";
            case "libz.so.1": return "libz.so";
            default: return null;
        }
    }

    private static boolean looksLikeDesktopRuntimeDependency(@NonNull String dependency) {
        String lower = dependency.toLowerCase(Locale.ROOT);
        return lower.contains("libc.so.")
                || lower.contains("libm.so.")
                || lower.contains("libdl.so.")
                || lower.contains("libpthread.so.")
                || lower.contains("librt.so.")
                || lower.contains("libresolv.so.")
                || lower.contains("libgcc_s")
                || lower.contains("libstdc++")
                || lower.contains("ld-linux")
                || lower.contains("ld-musl");
    }

    private static int patchDynString(byte[] data, int start, int end,
                                      String before, String after) {
        byte[] needle = before.getBytes(StandardCharsets.US_ASCII);
        int count = 0;
        for (int i = start; i + needle.length < end; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (!match || data[i + needle.length] != 0) continue;
            if (patchCStringInPlace(data, i, end, before, after)) count++;
            i += needle.length;
        }
        return count;
    }

    private static boolean patchCStringInPlace(byte[] data, int offset, int end,
                                               String before, String after) {
        byte[] beforeBytes = before.getBytes(StandardCharsets.US_ASCII);
        byte[] afterBytes = after.getBytes(StandardCharsets.US_ASCII);
        if (afterBytes.length > beforeBytes.length || offset < 0
                || offset + beforeBytes.length >= end) return false;
        for (int i = 0; i < beforeBytes.length; i++) {
            if (data[offset + i] != beforeBytes[i]) return false;
        }
        for (int i = 0; i < beforeBytes.length; i++) data[offset + i] = 0;
        System.arraycopy(afterBytes, 0, data, offset, afterBytes.length);
        return true;
    }

    @NonNull
    private static String readCString(byte[] data, int offset, int end) {
        int cursor = offset;
        while (cursor < end && data[cursor] != 0) cursor++;
        return new String(data, offset, cursor - offset, StandardCharsets.US_ASCII);
    }

    private static long readU32LE(byte[] data, int offset) {
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24);
    }

    private static int readU16LE(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readU64LE(byte[] data, int offset) {
        long value = 0L;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | ((long) data[offset + i] & 0xffL);
        }
        return value;
    }

    private static void writeU64LE(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            data[offset + i] = (byte) ((value >>> (8 * i)) & 0xffL);
        }
    }

    private static int checkedInt(long value) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer overflow " + value);
        }
        return (int) value;
    }

    @NonNull
    private static byte[] readLimited(@NonNull InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IllegalStateException("native exceeds " + maxBytes + " bytes");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void writeFileAtomically(@NonNull File target, @NonNull byte[] data) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) throw new IllegalStateException("target has no parent");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("unable to create " + parent.getAbsolutePath());
        }
        File temp = new File(parent, target.getName() + ".tmp");
        if (temp.exists() && !temp.delete()) {
            throw new IllegalStateException("unable to replace stale " + temp.getAbsolutePath());
        }
        try (FileOutputStream fos = new FileOutputStream(temp);
             BufferedOutputStream output = new BufferedOutputStream(fos)) {
            output.write(data);
            output.flush();
            fos.getFD().sync();
        }
        ensureLoadablePermissions(temp);
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("unable to replace " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            try (FileInputStream fis = new FileInputStream(temp);
                 BufferedInputStream input = new BufferedInputStream(fis);
                 FileOutputStream fos = new FileOutputStream(target);
                 BufferedOutputStream output = new BufferedOutputStream(fos)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                fos.getFD().sync();
            }
            if (!temp.delete()) temp.deleteOnExit();
        }
        ensureLoadablePermissions(target);
    }

    private static void ensureLoadablePermissions(@NonNull File file) {
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(true, true);
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
                try { key = file.getCanonicalPath(); }
                catch (Throwable ignored) { key = file.getAbsolutePath(); }
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
        if (parent != null) addDirectory(dirs, seen, new File(parent, "mods"));
        if ("mods".equalsIgnoreCase(gameDirectory.getName())) addDirectory(dirs, seen, gameDirectory);
        return dirs;
    }

    private static void addDirectory(@NonNull List<File> out, @NonNull Set<String> seen,
                                     @NonNull File directory) {
        if (!directory.isDirectory()) return;
        String key;
        try { key = directory.getCanonicalPath(); }
        catch (Throwable ignored) { key = directory.getAbsolutePath(); }
        if (seen.add(key)) out.add(directory);
    }

    private static final class NativeCandidate {
        final File jar;
        final String entryName;
        final int score;
        NativeCandidate(File jar, String entryName, int score) {
            this.jar = jar;
            this.entryName = entryName;
            this.score = score;
        }
    }

    private static final class Adaptation {
        final boolean accepted;
        final byte[] data;
        final String reason;
        private Adaptation(boolean accepted, byte[] data, String reason) {
            this.accepted = accepted;
            this.data = data;
            this.reason = reason;
        }
        static Adaptation accept(byte[] data, String reason) {
            return new Adaptation(true, data, reason);
        }
        static Adaptation reject(String reason) {
            return new Adaptation(false, new byte[0], reason);
        }
    }

    private static final class DynamicRecord {
        final int fileOffset;
        final long tag;
        final long value;
        DynamicRecord(int fileOffset, long tag, long value) {
            this.fileOffset = fileOffset;
            this.tag = tag;
            this.value = value;
        }
    }

    private static final class ElfLoadSegment {
        final long fileOffset;
        final long virtualAddress;
        final long fileSize;
        ElfLoadSegment(long fileOffset, long virtualAddress, long fileSize) {
            this.fileOffset = fileOffset;
            this.virtualAddress = virtualAddress;
            this.fileSize = fileSize;
        }
    }

    private static final class ElfLayout {
        final int machine;
        final int dynamicOffset;
        final int dynamicSize;
        final ArrayList<ElfLoadSegment> loadSegments;
        ElfLayout(int machine, int dynamicOffset, int dynamicSize,
                  ArrayList<ElfLoadSegment> loadSegments) {
            this.machine = machine;
            this.dynamicOffset = dynamicOffset;
            this.dynamicSize = dynamicSize;
            this.loadSegments = loadSegments;
        }

        static ElfLayout parse(byte[] data) {
            if (data.length < 64 || (data[0] & 0xff) != 0x7f
                    || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
                throw new IllegalArgumentException("not ELF");
            }
            if ((data[4] & 0xff) != 2) throw new IllegalArgumentException("not ELF64");
            if ((data[5] & 0xff) != 1) throw new IllegalArgumentException("not little-endian ELF");
            int machine = readU16LE(data, 18);
            long phoff = readU64LE(data, 32);
            int phentsize = readU16LE(data, 54);
            int phnum = readU16LE(data, 56);
            if (phentsize < 56 || phnum <= 0 || phoff < 0L
                    || phoff + (long) phentsize * phnum > data.length) {
                throw new IllegalArgumentException("invalid program header table");
            }
            int dynamicOffset = -1;
            int dynamicSize = -1;
            ArrayList<ElfLoadSegment> segments = new ArrayList<>();
            for (int i = 0; i < phnum; i++) {
                int off = checkedInt(phoff + (long) i * phentsize);
                long type = readU32LE(data, off);
                long fileOffset = readU64LE(data, off + 8);
                long virtualAddress = readU64LE(data, off + 16);
                long fileSize = readU64LE(data, off + 32);
                if (type == PT_LOAD) {
                    segments.add(new ElfLoadSegment(fileOffset, virtualAddress, fileSize));
                } else if (type == PT_DYNAMIC) {
                    if (fileOffset < 0L || fileSize < 0L
                            || fileOffset > Integer.MAX_VALUE || fileSize > Integer.MAX_VALUE
                            || fileOffset + fileSize > data.length) {
                        throw new IllegalArgumentException("PT_DYNAMIC outside file");
                    }
                    dynamicOffset = (int) fileOffset;
                    dynamicSize = (int) fileSize;
                }
            }
            return new ElfLayout(machine, dynamicOffset, dynamicSize, segments);
        }

        int virtualAddressToFileOffset(long address) {
            for (ElfLoadSegment segment : loadSegments) {
                long start = segment.virtualAddress;
                long end = start + segment.fileSize;
                if (address >= start && address < end) {
                    long offset = segment.fileOffset + (address - start);
                    if (offset >= 0L && offset <= Integer.MAX_VALUE) return (int) offset;
                }
            }
            return -1;
        }
    }
}
