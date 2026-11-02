/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge launcher-side compatibility for e4mc.
 * The e4mc jar and its relay/network protocol are not modified.
 */
package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Launcher-side e4mc Android native compatibility.
 *
 * Modern e4mc and e4mc Retro use different Netty-Quiche ABIs. DroidBridge
 * keeps them separate: modern 6.2.1 uses 0.0.74.Final.e4mc.1, while the
 * e4mc Retro family is detected by its shaded Netty-Quiche ABI; supported Retro releases use 0.0.73.Final
 * and a dedicated Android arm64 native packaged through jniLibs.
 *
 * e4mc 6.2.1-modern embeds Netty-Quiche 0.0.74.Final.e4mc.1. Its patched
 * Quiche loader correctly recognises DroidBridge as Android, but then requests
 * the generic URL https://natives.e4mc.link/libnetty_quiche.so. That file does
 * not exist. The same loader does publish a supported override:
 *
 *   -Dlink.e4mc.native_path=/absolute/path/libnetty_quiche.so
 *
 * e4mc's native manifest contains an official Linux/aarch64 build for this
 * exact ABI together with its SHA-256. DroidBridge first prefers a genuine
 * Android build if one is supplied. For this exact e4mc ABI only, it can then
 * download the official e4mc Linux/aarch64 binary, verify the upstream hash,
 * rewrite its small glibc ELF linkage surface to Android/Bionic in a private
 * cache, and point e4mc at the adapted copy through e4mc's own override.
 *
 * The e4mc jar, broker protocol, relay selection and world-hosting logic remain
 * untouched. Unknown native ABIs or unsupported ELF dependencies are rejected
 * instead of being loaded optimistically.
 */
public final class E4MCCompat {
    private static final String TAG = "E4MCCompat";

    private static final String MODERN_MOD_CLASS = "link/e4mc/QuiclimeSession.class";
    private static final String MODERN_QUICHE_CLASS = "io/netty/incubator/codec/quic/Quiche.class";
    private static final String MODERN_QUICHE_POM =
            "META-INF/maven/io.netty.incubator/netty-incubator-codec-classes-quic/pom.properties";

    // Known e4mc Retro builds shade Netty; the exact shade prefix is discovered per JAR.
    // Keep Retro detection separate from modern e4mc so the two incompatible JNI
    // ABIs can never accidentally receive each other's native library.
    private static final String RETRO_MOD_CLASS = "link/e4mc/session/QuiclimeSession.class";
    private static final String SUPPORTED_QUICHE_VERSION = "0.0.74.Final.e4mc.1";
    private static final String RETRO_QUICHE_VERSION = "0.0.73.Final";
    private static final String NATIVE_NAME = "libnetty_quiche.so";
    // Netty's shaded JNI glue derives the relocation prefix from the loaded
    // library basename. e4mc Retro shades Netty to dev.xhyrom.e4mc.shadow, so
    // the verified Android native must keep this mangled basename when loaded.
    // Loading the same bytes as libnetty_quiche_retro.so makes JNI_OnLoad look
    // for unshaded io/netty/... classes and fails before Quiclime can start.
    private static final String RETRO_NATIVE_NAME =
            "libdev_xhyrom_e4mc_shadow_netty_quiche.so";
    private static final String RETRO_LEGACY_WRONG_NATIVE_NAME = "libnetty_quiche_retro.so";
    private static final String NATIVE_ARG_PREFIX = "-Dlink.e4mc.native_path=";
    private static final String RETRO_HINT_PROPERTY = "droidbridge.e4mc.retro.native_path";
    private static final String RETRO_HINT_ARG_PREFIX = "-D" + RETRO_HINT_PROPERTY + "=";
    private static final String RETRO_ENABLE_ARG_PREFIX = "-Ddroidbridge.e4mc.retro.enabled=";
    private static final String RETRO_APK_ENTRY = "lib/arm64-v8a/" + RETRO_NATIVE_NAME;

    // Retro release numbers are intentionally not pinned. Compatibility is gated
    // by the embedded shaded Netty-Quiche native ABI and derived JNI basename.

    // Android arm64-v8a Netty-Quiche 0.0.73.Final built with Android API 21 / NDK r28c.
    // This pinned build uses 16 KiB-compatible PT_LOAD alignment (0x4000).
    // This exact file is packaged through app/src/main/jniLibs/arm64-v8a/
    // under RETRO_NATIVE_NAME so Netty's shaded JNI package-prefix discovery works.
    private static final String RETRO_NATIVE_SHA256 =
            "0315a1a72d275ef9d10aaf46c17b382f77d0364430e70e7928906e20a057d6ae";
    // Android Gradle Plugin strips native debug sections while packaging APKs,
    // which legitimately changes the whole-file SHA-256. Pin the linker build-id
    // and ABI identity as well so stripped and unstripped copies of the exact same
    // binary are accepted without weakening the Retro ABI gate.
    private static final String RETRO_NATIVE_BUILD_ID =
            "bffe106cf8ae929f17c4f25851a782bd2e696459";
    private static final String RETRO_NATIVE_SONAME =
            "libnetty_quiche-0.0.73.Final.so";
    private static final long RETRO_MIN_PAGE_ALIGNMENT = 0x4000L;

    // These values are taken from e4mc 6.2.1-modern's own patched
    // io.netty.incubator.codec.quic.Quiche native manifest/hash table.
    private static final String OFFICIAL_LINUX_ARM64_NAME =
            "libnetty_quiche_linux_aarch_64_74.so";
    private static final String OFFICIAL_LINUX_ARM64_URL =
            "https://natives.e4mc.link/" + OFFICIAL_LINUX_ARM64_NAME;
    private static final String OFFICIAL_LINUX_ARM64_SHA256 =
            "07a5dbd61979785b6466ffa77c44b0e24523c884fc0a72ea61ea591458ae3ec0";

    // Bump whenever the ELF adaptation rules change so a stale transformed
    // native can never survive a launcher update.
    private static final int ADAPTER_REVISION = 1;
    private static final long MAX_NATIVE_BYTES = 64L * 1024L * 1024L;

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

    private static final String[] BUNDLED_ANDROID_ENTRIES = new String[] {
            "META-INF/native/libnetty_quiche.so",
            "META-INF/native/android-aarch64/libnetty_quiche.so",
            "META-INF/native/android-arm64/libnetty_quiche.so",
            "natives/android-aarch64/libnetty_quiche.so",
            "natives/android-arm64/libnetty_quiche.so",
            "native/android-aarch64/libnetty_quiche.so",
            "android-aarch64/libnetty_quiche.so",
            "lib/arm64-v8a/libnetty_quiche.so"
    };

    private E4MCCompat() {
    }

    public static void applyNativeCompatibility(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @NonNull List<String> jvmArgs
    ) {
        // REV15 safety gate: do absolutely nothing Retro-specific until an
        // actual e4mc Retro JAR has been positively detected in this instance.
        // This keeps normal Minecraft, modern e4mc, and Java 25/Fabric startup
        // on the same path as the known-good 0.9.19 launcher.
        DetectedE4mc detected = findE4mc(gameDirectory);
        if (detected == null) {
            removePropertyArg(jvmArgs, RETRO_ENABLE_ARG_PREFIX);
            removePropertyArg(jvmArgs, RETRO_HINT_ARG_PREFIX);
            return;
        }

        if (Architecture.getDeviceArchitecture() != Architecture.ARCH_ARM64) {
            Logging.i(TAG, "e4mc detected but Android Netty-Quiche compatibility is arm64-v8a only: jar="
                    + detected.jar.getName());
            return;
        }

        if (detected.variant == E4mcVariant.RETRO) {
            replaceBooleanPropertyArg(jvmArgs, RETRO_ENABLE_ARG_PREFIX, true);
            File retroNativeHint = null;
            try {
                retroNativeHint = preparePackagedRetroAndroidNative(context, RETRO_NATIVE_NAME);
                if (retroNativeHint != null) {
                    replacePathPropertyArg(jvmArgs, RETRO_HINT_ARG_PREFIX, retroNativeHint);
                    Logging.i(TAG, "e4mc Retro opt-in native handoff armed: native="
                            + retroNativeHint.getAbsolutePath());
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to prepare packaged e4mc Retro Android native", throwable);
            }
            applyRetroNativeCompatibility(context, detected, jvmArgs, retroNativeHint);
            return;
        }

        // Modern e4mc must never inherit a Retro flag/hint.
        removePropertyArg(jvmArgs, RETRO_ENABLE_ARG_PREFIX);
        removePropertyArg(jvmArgs, RETRO_HINT_ARG_PREFIX);
        applyModernNativeCompatibility(context, gameDirectory, detected.jar, jvmArgs);
    }

    private static void applyModernNativeCompatibility(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @NonNull File e4mcJar,
            @NonNull List<String> jvmArgs
    ) {
        final String quicheVersion = readQuicheVersion(e4mcJar);
        if (!SUPPORTED_QUICHE_VERSION.equals(quicheVersion)) {
            Logging.i(TAG, "e4mc detected but native ABI is not supported by this DroidBridge adapter: jar="
                    + e4mcJar.getName() + " quiche=" + quicheVersion
                    + " expected=" + SUPPORTED_QUICHE_VERSION);
            return;
        }

        File nativeFile = null;
        try {
            nativeFile = findExistingAndroidNative(context, gameDirectory);
            if (nativeFile == null) {
                nativeFile = extractBundledAndroidNativeIfPresent(context, e4mcJar);
            }
            if (nativeFile == null) {
                nativeFile = prepareOfficialArm64NativeForAndroid(context);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare e4mc Android Netty-Quiche native", throwable);
        }

        if (nativeFile == null) {
            File preferred = new File(new File(gameDirectory, "droidbridge_compat/e4mc"), NATIVE_NAME);
            Logging.i(TAG, "e4mc detected but DroidBridge could not prepare a loadable Android arm64 Netty-Quiche native. "
                    + "Local override path=" + preferred.getAbsolutePath()
                    + ". Stock e4mc will otherwise request its missing generic Android URL.");
            return;
        }

        ValidationResult validation = validateAndroidArm64Elf(nativeFile, true);
        if (!validation.accepted) {
            Logging.i(TAG, "Rejected e4mc native candidate=" + nativeFile.getAbsolutePath()
                    + " reason=" + validation.reason);
            return;
        }

        replaceNativeOverrideArg(jvmArgs, nativeFile);
        Logging.i(TAG, "e4mc Android Netty-Quiche compatibility active: variant=modern jar="
                + e4mcJar.getName() + " quiche=" + quicheVersion
                + " native=" + nativeFile.getAbsolutePath());
    }

    private static void applyRetroNativeCompatibility(
            @NonNull Context context,
            @NonNull DetectedE4mc detected,
            @NonNull List<String> jvmArgs,
            @Nullable File preparedNative
    ) {
        String quicheVersion = readRetroQuicheVersion(detected.jar, detected.retroNativeName);
        if (!RETRO_QUICHE_VERSION.equals(quicheVersion)) {
            Logging.i(TAG, "e4mc Retro detected but its Netty-Quiche ABI is not supported: jar="
                    + detected.jar.getName() + " quiche=" + quicheVersion
                    + " expected=" + RETRO_QUICHE_VERSION);
            return;
        }

        File nativeFile = preparedNative;
        if (nativeFile != null) {
            try {
                nativeFile = stageRetroNativeWithBasename(context, nativeFile, detected.retroNativeName);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to stage e4mc Retro native under required shaded basename", throwable);
                nativeFile = null;
            }
        }
        if (nativeFile == null) {
            try {
                nativeFile = preparePackagedRetroAndroidNative(context, detected.retroNativeName);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to prepare packaged e4mc Retro Android native", throwable);
            }
        }
        if (nativeFile == null) {
            Logging.i(TAG, "e4mc Retro detected but packaged Android native is missing: requiredName="
                    + detected.retroNativeName + " canonicalApkEntry=" + RETRO_APK_ENTRY);
            return;
        }

        if (!isExpectedRetroNative(nativeFile)) {
            String nativeHash = "<unavailable>";
            try { nativeHash = sha256Hex(nativeFile); } catch (Throwable ignored) {}
            Logging.i(TAG, "Rejected e4mc Retro native because its ABI identity does not match the pinned 0.0.73.Final Android build: "
                    + nativeFile.getAbsolutePath() + " sha256=" + nativeHash);
            return;
        }

        ensureLoadablePermissions(nativeFile);
        replacePathPropertyArg(jvmArgs, RETRO_HINT_ARG_PREFIX, nativeFile);
        replaceNativeOverrideArg(jvmArgs, nativeFile);
        Logging.i(TAG, "e4mc Android Netty-Quiche compatibility active: variant=retro jar="
                + detected.jar.getName() + " quiche=" + quicheVersion
                + " shadedClass=" + detected.retroQuicheClass
                + " native=" + nativeFile.getAbsolutePath());
    }

    @Nullable
    private static File preparePackagedRetroAndroidNative(@NonNull Context context, @NonNull String requiredNativeName) throws Exception {
        // REV11: never hand an unshaded/wrong basename to LibPatcher. The native
        // bytes are valid, but Netty JNI uses the basename to recover e4mc's
        // shade prefix.
        File installed = findInstalledRetroAndroidNative(context, requiredNativeName);
        if (isExpectedRetroNative(installed)) {
            ensureLoadablePermissions(installed);
            return installed;
        }

        File stagedDir = new File(context.getFilesDir(),
                "droidbridge-native/e4mc-retro/" + RETRO_QUICHE_VERSION);
        ensureDirectory(stagedDir);
        File staleWrongName = new File(stagedDir, RETRO_LEGACY_WRONG_NATIVE_NAME);
        if (staleWrongName.exists()) {
            Logging.i(TAG, "Removing stale e4mc Retro native with unshaded JNI basename="
                    + staleWrongName.getAbsolutePath());
            //noinspection ResultOfMethodCallIgnored
            staleWrongName.delete();
        }
        File staged = new File(stagedDir, requiredNativeName);
        if (isExpectedRetroNative(staged)) {
            ensureLoadablePermissions(staged);
            return staged;
        }
        if (staged.exists()) {
            Logging.i(TAG, "Discarding stale e4mc Retro staged native=" + staged.getAbsolutePath());
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }

        ArrayList<File> apks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            addApk(apks, seen, context.getApplicationInfo().sourceDir);
        } catch (Throwable ignored) {
        }
        try {
            String[] splitSourceDirs = context.getApplicationInfo().splitSourceDirs;
            if (splitSourceDirs != null) {
                for (String split : splitSourceDirs) addApk(apks, seen, split);
            }
        } catch (Throwable ignored) {
        }
        try {
            addApk(apks, seen, context.getPackageCodePath());
        } catch (Throwable ignored) {
        }

        for (File apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry(RETRO_APK_ENTRY);
                if (entry == null || entry.isDirectory() || entry.getSize() == 0L) continue;
                if (entry.getSize() > MAX_NATIVE_BYTES) {
                    Logging.i(TAG, "Ignoring oversized e4mc Retro native APK entry apk="
                            + apk.getAbsolutePath() + " size=" + entry.getSize());
                    continue;
                }

                File tmp = new File(stagedDir, requiredNativeName + ".tmp");
                deleteIfExists(tmp);
                try (InputStream raw = zip.getInputStream(entry);
                     BufferedInputStream input = new BufferedInputStream(raw);
                     FileOutputStream fos = new FileOutputStream(tmp);
                     BufferedOutputStream output = new BufferedOutputStream(fos)) {
                    copyBounded(input, output, MAX_NATIVE_BYTES);
                    output.flush();
                    fos.getFD().sync();
                }

                if (!isExpectedRetroNative(tmp)) {
                    String hash;
                    try {
                        hash = sha256Hex(tmp);
                    } catch (Throwable ignored) {
                        hash = "<unavailable>";
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    Logging.i(TAG, "Rejected packaged e4mc Retro native from APK="
                            + apk.getAbsolutePath() + " sha256=" + hash);
                    continue;
                }

                replaceFile(tmp, staged);
                ensureLoadablePermissions(staged);
                Logging.i(TAG, "Staged verified e4mc Retro Android native from APK="
                        + apk.getAbsolutePath() + " entry=" + RETRO_APK_ENTRY
                        + " path=" + staged.getAbsolutePath());
                return staged;
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to inspect APK for e4mc Retro native apk="
                        + apk.getAbsolutePath() + " reason=" + throwable.getClass().getSimpleName()
                        + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
            }
        }
        return null;
    }

    @Nullable
    private static File stageRetroNativeWithBasename(
            @NonNull Context context,
            @NonNull File source,
            @NonNull String requiredNativeName
    ) throws Exception {
        if (!isExpectedRetroNative(source)) return null;
        if (requiredNativeName.equals(source.getName())) {
            ensureLoadablePermissions(source);
            return source;
        }
        File dir = new File(context.getFilesDir(),
                "droidbridge-native/e4mc-retro/" + RETRO_QUICHE_VERSION);
        ensureDirectory(dir);
        File destination = new File(dir, requiredNativeName);
        if (isExpectedRetroNative(destination)) {
            ensureLoadablePermissions(destination);
            return destination;
        }
        File tmp = new File(dir, requiredNativeName + ".copy");
        deleteIfExists(tmp);
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(tmp);
             BufferedOutputStream output = new BufferedOutputStream(fos)) {
            copyBounded(input, output, MAX_NATIVE_BYTES);
            output.flush();
            fos.getFD().sync();
        }
        if (!isExpectedRetroNative(tmp)) {
            deleteIfExists(tmp);
            return null;
        }
        replaceFile(tmp, destination);
        ensureLoadablePermissions(destination);
        Logging.i(TAG, "Staged e4mc Retro native under discovered shaded JNI basename source="
                + source.getAbsolutePath() + " target=" + destination.getAbsolutePath());
        return destination;
    }

    @Nullable
    private static File findInstalledRetroAndroidNative(@NonNull Context context, @NonNull String requiredNativeName) {
        ArrayList<File> candidates = new ArrayList<>();
        try {
            if (PathManager.DIR_NATIVE_LIB != null) {
                candidates.add(new File(PathManager.DIR_NATIVE_LIB, requiredNativeName));
                if (!RETRO_NATIVE_NAME.equals(requiredNativeName)) candidates.add(new File(PathManager.DIR_NATIVE_LIB, RETRO_NATIVE_NAME));
            }
        } catch (Throwable ignored) {
        }
        try {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            if (nativeDir != null && !nativeDir.trim().isEmpty()) {
                candidates.add(new File(nativeDir, requiredNativeName));
                if (!RETRO_NATIVE_NAME.equals(requiredNativeName)) candidates.add(new File(nativeDir, RETRO_NATIVE_NAME));
            }
        } catch (Throwable ignored) {
        }

        Set<String> seen = new LinkedHashSet<>();
        for (File candidate : candidates) {
            String path = candidate.getAbsolutePath();
            if (!seen.add(path)) continue;
            if (isExpectedRetroNative(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isExpectedRetroNative(@Nullable File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) return false;
        try {
            ValidationResult validation = validateAndroidArm64Elf(file, false);
            if (!validation.accepted) return false;

            // Fast path for the exact unstripped artifact produced by our Quiche build.
            if (RETRO_NATIVE_SHA256.equalsIgnoreCase(sha256Hex(file))) return true;

            // AGP normally strips .debug/.symtab sections from jniLibs when building
            // the APK. Those sections are outside PT_LOAD and stripping does not alter
            // the linker build-id, SONAME, machine ABI, or page alignment. Verify those
            // immutable runtime properties instead of rejecting the legitimate packaged
            // copy merely because its whole-file hash changed.
            byte[] data = readBoundedFile(file, MAX_NATIVE_BYTES);
            if (!containsAscii(data, RETRO_NATIVE_SONAME)) return false;
            if (!containsBytes(data, hexToBytes(RETRO_NATIVE_BUILD_ID))) return false;
            if (!hasRetroCompatibleLoadSegments(data)) return false;
            return elfNeedsLibrary(data, "libc++_shared.so");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean elfNeedsLibrary(
            @NonNull byte[] data,
            @NonNull String expectedLibrary
    ) {
        try {
            ElfLayout elf = ElfLayout.parse(data);
            if (elf.dynamicOffset < 0 || elf.dynamicSize < 16) return false;

            long strtabAddress = -1L;
            long strtabSize = -1L;
            ArrayList<Long> neededOffsets = new ArrayList<>();

            int dynamicEnd = checkedInt(Math.min((long) data.length,
                    (long) elf.dynamicOffset + (long) elf.dynamicSize));
            for (int off = elf.dynamicOffset; off + 16 <= dynamicEnd; off += 16) {
                long tag = readU64LE(data, off);
                long value = readU64LE(data, off + 8);
                if (tag == DT_NULL) break;
                if (tag == DT_NEEDED) neededOffsets.add(value);
                else if (tag == DT_STRTAB) strtabAddress = value;
                else if (tag == DT_STRSZ) strtabSize = value;
            }

            if (strtabAddress < 0L || strtabSize <= 0L || strtabSize > Integer.MAX_VALUE) {
                return false;
            }
            int strtabOffset = elf.virtualAddressToFileOffset(strtabAddress);
            if (strtabOffset < 0 || (long) strtabOffset + strtabSize > data.length) {
                return false;
            }
            int strtabEnd = strtabOffset + checkedInt(strtabSize);

            for (Long neededOffset : neededOffsets) {
                long rel = neededOffset.longValue();
                if (rel < 0L || rel >= strtabSize || rel > Integer.MAX_VALUE) continue;
                String name = readCString(data, strtabOffset + checkedInt(rel), strtabEnd);
                if (expectedLibrary.equals(name)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasRetroCompatibleLoadSegments(@NonNull byte[] data) {
        try {
            if (data.length < 64
                    || (data[0] & 0xff) != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F'
                    || (data[4] & 0xff) != 2 || (data[5] & 0xff) != 1
                    || readU16LE(data, 18) != 183) {
                return false;
            }
            long phoff = readU64LE(data, 32);
            int phentsize = readU16LE(data, 54);
            int phnum = readU16LE(data, 56);
            if (phentsize < 56 || phnum <= 0
                    || phoff < 0L || phoff > Integer.MAX_VALUE
                    || phoff + (long) phentsize * phnum > data.length) {
                return false;
            }
            int loadCount = 0;
            for (int i = 0; i < phnum; i++) {
                int off = checkedInt(phoff + (long) i * phentsize);
                if (readU32LE(data, off) != PT_LOAD) continue;
                loadCount++;
                long fileOffset = readU64LE(data, off + 8);
                long virtualAddress = readU64LE(data, off + 16);
                long alignment = readU64LE(data, off + 48);
                if (alignment < RETRO_MIN_PAGE_ALIGNMENT) return false;
                long mask = RETRO_MIN_PAGE_ALIGNMENT - 1L;
                if (((fileOffset - virtualAddress) & mask) != 0L) return false;
            }
            return loadCount > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsAscii(@NonNull byte[] data, @NonNull String value) {
        return containsBytes(data, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean containsBytes(@NonNull byte[] data, @NonNull byte[] needle) {
        if (needle.length == 0 || needle.length > data.length) return false;
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @NonNull
    private static byte[] hexToBytes(@NonNull String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void addApk(
            @NonNull List<File> out,
            @NonNull Set<String> seen,
            @Nullable String path
    ) {
        if (path == null || path.trim().isEmpty()) return;
        File file = new File(path);
        if (!file.isFile()) return;
        String key;
        try {
            key = file.getCanonicalPath();
        } catch (Throwable ignored) {
            key = file.getAbsolutePath();
        }
        if (seen.add(key)) out.add(file);
    }

    private static void replaceNativeOverrideArg(
            @NonNull List<String> jvmArgs,
            @NonNull File nativeFile
    ) {
        replacePathPropertyArg(jvmArgs, NATIVE_ARG_PREFIX, nativeFile);
    }

    private static void removePropertyArg(
            @NonNull List<String> jvmArgs,
            @NonNull String prefix
    ) {
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String arg = jvmArgs.get(i);
            if (arg != null && arg.startsWith(prefix)) {
                jvmArgs.remove(i);
            }
        }
    }

    private static void replaceBooleanPropertyArg(
            @NonNull List<String> jvmArgs,
            @NonNull String prefix,
            boolean value
    ) {
        removePropertyArg(jvmArgs, prefix);
        jvmArgs.add(prefix + Boolean.toString(value));
    }

    private static void replacePathPropertyArg(
            @NonNull List<String> jvmArgs,
            @NonNull String prefix,
            @NonNull File nativeFile
    ) {
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String arg = jvmArgs.get(i);
            if (arg != null && arg.startsWith(prefix)) {
                jvmArgs.remove(i);
            }
        }
        jvmArgs.add(prefix + nativeFile.getAbsolutePath());
    }

    @Nullable
    private static File findExistingAndroidNative(
            @NonNull Context context,
            @NonNull File gameDirectory
    ) {
        ArrayList<File> candidates = new ArrayList<>();
        try {
            if (PathManager.DIR_NATIVE_LIB != null) {
                candidates.add(new File(PathManager.DIR_NATIVE_LIB, NATIVE_NAME));
            }
        } catch (Throwable ignored) {
        }
        candidates.add(new File(new File(gameDirectory, "droidbridge_compat/e4mc"), NATIVE_NAME));
        candidates.add(new File(new File(context.getFilesDir(), "e4mc-android-native"), NATIVE_NAME));
        candidates.add(new File(new File(context.getCacheDir(), "e4mc-android-native"), NATIVE_NAME));

        for (File candidate : candidates) {
            if (!candidate.isFile() || candidate.length() <= 0L) continue;
            ValidationResult result = validateAndroidArm64Elf(candidate, false);
            if (result.accepted) {
                ensureLoadablePermissions(candidate);
                return candidate;
            }
            Logging.i(TAG, "Ignoring incompatible e4mc native candidate="
                    + candidate.getAbsolutePath() + " reason=" + result.reason);
        }
        return null;
    }

    @Nullable
    private static File extractBundledAndroidNativeIfPresent(
            @NonNull Context context,
            @NonNull File e4mcJar
    ) throws Exception {
        try (ZipFile zip = new ZipFile(e4mcJar)) {
            ZipEntry selected = null;
            for (String entryName : BUNDLED_ANDROID_ENTRIES) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry != null && !entry.isDirectory() && entry.getSize() != 0L) {
                    selected = entry;
                    break;
                }
            }
            if (selected == null) return null;

            File dir = new File(context.getCacheDir(), "e4mc-android-native");
            ensureDirectory(dir);
            File out = new File(dir, NATIVE_NAME);
            File tmp = new File(dir, NATIVE_NAME + ".tmp");
            deleteIfExists(tmp);

            try (InputStream raw = zip.getInputStream(selected);
                 BufferedInputStream input = new BufferedInputStream(raw);
                 FileOutputStream fos = new FileOutputStream(tmp);
                 BufferedOutputStream output = new BufferedOutputStream(fos)) {
                copyBounded(input, output, MAX_NATIVE_BYTES);
                output.flush();
                fos.getFD().sync();
            }

            ValidationResult result = validateAndroidArm64Elf(tmp, false);
            if (!result.accepted) {
                tmp.delete();
                Logging.i(TAG, "e4mc jar contains a native candidate but it is not Android arm64 compatible: entry="
                        + selected.getName() + " reason=" + result.reason);
                return null;
            }

            replaceFile(tmp, out);
            ensureLoadablePermissions(out);
            Logging.i(TAG, "Extracted Android e4mc native from mod jar entry=" + selected.getName());
            return out;
        }
    }

    /**
     * Downloads e4mc's official Linux/aarch64 Quiche build for ABI 74, verifies
     * the exact SHA-256 embedded in e4mc itself, then creates a Bionic-linked
     * private copy. The downloaded original is retained for deterministic
     * re-adaptation after an adapter revision bump.
     */
    @Nullable
    private static File prepareOfficialArm64NativeForAndroid(@NonNull Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "e4mc-android-native/"
                + SUPPORTED_QUICHE_VERSION + "/r" + ADAPTER_REVISION);
        ensureDirectory(dir);

        File source = new File(dir, OFFICIAL_LINUX_ARM64_NAME);
        File adapted = new File(dir, NATIVE_NAME);

        if (adapted.isFile() && adapted.length() > 0L) {
            ValidationResult cached = validateAndroidArm64Elf(adapted, true);
            if (cached.accepted) {
                ensureLoadablePermissions(adapted);
                Logging.i(TAG, "Using cached DroidBridge-adapted e4mc Quiche native="
                        + adapted.getAbsolutePath());
                return adapted;
            }
            Logging.i(TAG, "Discarding stale/invalid adapted e4mc native reason=" + cached.reason);
            adapted.delete();
        }

        boolean sourceValid = source.isFile()
                && OFFICIAL_LINUX_ARM64_SHA256.equalsIgnoreCase(sha256Hex(source));
        if (!sourceValid) {
            if (source.exists()) source.delete();
            downloadVerifiedOfficialNative(source);
        }

        String actualHash = sha256Hex(source);
        if (!OFFICIAL_LINUX_ARM64_SHA256.equalsIgnoreCase(actualHash)) {
            source.delete();
            throw new IllegalStateException("e4mc official native SHA-256 mismatch after download: " + actualHash);
        }

        File tmp = new File(dir, NATIVE_NAME + ".tmp");
        deleteIfExists(tmp);
        AdaptationResult adaptation = adaptLinuxArm64ElfToAndroid(source, tmp);
        if (!adaptation.accepted) {
            tmp.delete();
            Logging.i(TAG, "Official e4mc Linux/aarch64 Quiche native cannot be safely adapted on Android: "
                    + adaptation.reason);
            return null;
        }

        ValidationResult transformed = validateAndroidArm64Elf(tmp, true);
        if (!transformed.accepted) {
            tmp.delete();
            Logging.i(TAG, "Adapted e4mc Quiche native failed Android validation: " + transformed.reason);
            return null;
        }

        replaceFile(tmp, adapted);
        ensureLoadablePermissions(adapted);
        Logging.i(TAG, "Prepared official e4mc Quiche native for Android/Bionic: "
                + adapted.getAbsolutePath() + " changes=" + adaptation.reason);
        return adapted;
    }

    private static void downloadVerifiedOfficialNative(@NonNull File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent == null) throw new IllegalStateException("native destination has no parent");
        ensureDirectory(parent);

        File tmp = new File(parent, destination.getName() + ".download");
        deleteIfExists(tmp);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(OFFICIAL_LINUX_ARM64_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "DroidBridge-e4mc-compat/" + ADAPTER_REVISION);
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("e4mc native download HTTP " + code + " from "
                        + OFFICIAL_LINUX_ARM64_URL);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_NATIVE_BYTES) {
                throw new IllegalStateException("e4mc native download is unexpectedly large: " + contentLength);
            }

            try (InputStream raw = connection.getInputStream();
                 BufferedInputStream input = new BufferedInputStream(raw);
                 FileOutputStream fos = new FileOutputStream(tmp);
                 BufferedOutputStream output = new BufferedOutputStream(fos)) {
                copyBounded(input, output, MAX_NATIVE_BYTES);
                output.flush();
                fos.getFD().sync();
            }

            String actualHash = sha256Hex(tmp);
            if (!OFFICIAL_LINUX_ARM64_SHA256.equalsIgnoreCase(actualHash)) {
                throw new SecurityException("e4mc native SHA-256 mismatch expected="
                        + OFFICIAL_LINUX_ARM64_SHA256 + " actual=" + actualHash);
            }
            replaceFile(tmp, destination);
            Logging.i(TAG, "Downloaded and verified official e4mc Linux/aarch64 Quiche native sha256="
                    + actualHash);
        } finally {
            if (connection != null) connection.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    @NonNull
    private static AdaptationResult adaptLinuxArm64ElfToAndroid(
            @NonNull File source,
            @NonNull File destination
    ) {
        try {
            byte[] data = readBoundedFile(source, MAX_NATIVE_BYTES);
            ElfLayout elf = ElfLayout.parse(data);

            if (elf.machine != 183) {
                return AdaptationResult.reject("not AArch64 e_machine=" + elf.machine);
            }
            if (elf.dynamicOffset < 0 || elf.dynamicSize < 16) {
                return AdaptationResult.reject("ELF has no PT_DYNAMIC table");
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
                return AdaptationResult.reject("ELF dynamic string table is missing");
            }
            int strtabOffset = elf.virtualAddressToFileOffset(strtabAddress);
            if (strtabOffset < 0 || strtabSize > Integer.MAX_VALUE
                    || (long) strtabOffset + strtabSize > data.length) {
                return AdaptationResult.reject("ELF dynamic string table is outside file");
            }
            int strtabEnd = strtabOffset + (int) strtabSize;

            ArrayList<String> beforeDependencies = new ArrayList<>();
            ArrayList<String> afterDependencies = new ArrayList<>();
            int dependencyPatches = 0;
            int versionTagsRemoved = 0;

            for (DynamicRecord record : records) {
                if (record.tag == DT_NEEDED) {
                    if (record.value < 0L || record.value >= strtabSize) {
                        return AdaptationResult.reject("invalid DT_NEEDED string offset=" + record.value);
                    }
                    int nameOffset = strtabOffset + checkedInt(record.value);
                    String before = readCString(data, nameOffset, strtabEnd);
                    beforeDependencies.add(before);
                    String replacement = androidDependencyFor(before);
                    if (replacement == null) {
                        if (looksLikeDesktopRuntimeDependency(before)) {
                            return AdaptationResult.reject("unsupported desktop DT_NEEDED=" + before
                                    + " dependencies=" + beforeDependencies);
                        }
                        afterDependencies.add(before);
                        continue;
                    }
                    if (!before.equals(replacement)) {
                        if (!patchCStringInPlace(data, nameOffset, strtabEnd, before, replacement)) {
                            return AdaptationResult.reject("unable to rewrite DT_NEEDED " + before
                                    + " -> " + replacement);
                        }
                        dependencyPatches++;
                    }
                    afterDependencies.add(replacement);
                } else if (record.tag == DT_VERSYM
                        || record.tag == DT_VERNEED
                        || record.tag == DT_VERNEEDNUM) {
                    // Remove glibc's symbol-version lookup metadata. Android/Bionic
                    // exports unversioned POSIX symbols, so keeping GLIBC_x.y
                    // requirements would make otherwise compatible symbols fail
                    // resolution. DT_DEBUG is harmless for a shared object and does
                    // not terminate the dynamic table like DT_NULL would.
                    writeU64LE(data, record.fileOffset, DT_DEBUG);
                    writeU64LE(data, record.fileOffset + 8, 0L);
                    versionTagsRemoved++;
                }
            }

            int symbolPatches = 0;
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__errno_location", "__errno");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__xpg_strerror_r", "strerror_r");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "secure_getenv", "getenv");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__isoc99_sscanf", "sscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__isoc99_vsscanf", "vsscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__isoc99_fscanf", "fscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__isoc99_vfscanf", "vfscanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__isoc99_scanf", "scanf");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "__isoc99_vscanf", "vscanf");

            // 64-bit Android has a single 64-bit time/off_t ABI. These glibc
            // compatibility entry points can use the corresponding Bionic names.
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "open64", "open");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "fopen64", "fopen");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "freopen64", "freopen");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "lseek64", "lseek");
            symbolPatches += patchDynString(data, strtabOffset, strtabEnd,
                    "mmap64", "mmap");

            if (dependencyPatches == 0 && versionTagsRemoved == 0 && symbolPatches == 0) {
                return AdaptationResult.reject("official native did not expose the expected Linux/glibc linkage surface");
            }

            writeFileAtomically(destination, data);
            return AdaptationResult.accept("needed=" + beforeDependencies + "->" + afterDependencies
                    + " dependencyPatches=" + dependencyPatches
                    + " versionTagsRemoved=" + versionTagsRemoved
                    + " symbolAliases=" + symbolPatches);
        } catch (Throwable throwable) {
            return AdaptationResult.reject(throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage()));
        }
    }

    @Nullable
    private static String androidDependencyFor(@NonNull String dependency) {
        switch (dependency) {
            case "libc.so.6":
                return "libc.so";
            case "libm.so.6":
                return "libm.so";
            case "libdl.so.2":
                return "libdl.so";
            case "libpthread.so.0":
            case "librt.so.1":
            case "libresolv.so.2":
                // Modern Bionic exposes these POSIX APIs from libc.
                return "libc.so";
            case "libz.so.1":
                return "libz.so";
            default:
                return null;
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

    private static int patchDynString(
            @NonNull byte[] data,
            int start,
            int end,
            @NonNull String from,
            @NonNull String to
    ) {
        byte[] needle = from.getBytes(StandardCharsets.US_ASCII);
        int patches = 0;
        for (int i = start; i + needle.length < end; i++) {
            if (i > start && data[i - 1] != 0) continue;
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;
            int terminator = i + needle.length;
            if (terminator >= end || data[terminator] != 0) continue;
            if (patchCStringInPlace(data, i, end, from, to)) {
                patches++;
                i += needle.length;
            }
        }
        return patches;
    }

    private static boolean patchCStringInPlace(
            @NonNull byte[] data,
            int offset,
            int end,
            @NonNull String oldValue,
            @NonNull String newValue
    ) {
        byte[] oldBytes = oldValue.getBytes(StandardCharsets.US_ASCII);
        byte[] newBytes = newValue.getBytes(StandardCharsets.US_ASCII);
        if (newBytes.length > oldBytes.length) return false;
        if (offset < 0 || offset + oldBytes.length >= end) return false;
        for (int i = 0; i < oldBytes.length; i++) {
            if (data[offset + i] != oldBytes[i]) return false;
        }
        if (data[offset + oldBytes.length] != 0) return false;
        System.arraycopy(newBytes, 0, data, offset, newBytes.length);
        for (int i = newBytes.length; i < oldBytes.length; i++) {
            data[offset + i] = 0;
        }
        return true;
    }

    @NonNull
    private static ValidationResult validateAndroidArm64Elf(
            @NonNull File file,
            boolean allowStrippedGlibcVersionStrings
    ) {
        if (!file.isFile() || file.length() < 64L) {
            return ValidationResult.reject("missing/too small");
        }
        byte[] prefix = new byte[64];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(prefix);
            if (read < 20) return ValidationResult.reject("short ELF header");
        } catch (Throwable throwable) {
            return ValidationResult.reject("read failed: " + throwable.getClass().getSimpleName());
        }

        if ((prefix[0] & 0xff) != 0x7f || prefix[1] != 'E' || prefix[2] != 'L' || prefix[3] != 'F') {
            return ValidationResult.reject("not ELF");
        }
        if ((prefix[4] & 0xff) != 2) {
            return ValidationResult.reject("not ELF64");
        }
        if ((prefix[5] & 0xff) != 1) {
            return ValidationResult.reject("not little-endian ELF");
        }
        int machine = (prefix[18] & 0xff) | ((prefix[19] & 0xff) << 8);
        if (machine != 183) {
            return ValidationResult.reject("not AArch64 e_machine=" + machine);
        }

        try {
            if (binaryContains(file, "libc.so.6")
                    || binaryContains(file, "libm.so.6")
                    || binaryContains(file, "libdl.so.2")
                    || binaryContains(file, "libpthread.so.0")
                    || binaryContains(file, "librt.so.1")
                    || binaryContains(file, "libresolv.so.2")
                    || binaryContains(file, "libgcc_s.so.1")
                    || binaryContains(file, "libstdc++.so.6")
                    || binaryContains(file, "ld-linux-aarch64")) {
                return ValidationResult.reject("desktop runtime dependency detected");
            }
            if (!allowStrippedGlibcVersionStrings && binaryContains(file, "GLIBC_")) {
                return ValidationResult.reject("desktop glibc symbol-version requirement detected");
            }
        } catch (Throwable throwable) {
            return ValidationResult.reject("ELF dependency scan failed: "
                    + throwable.getClass().getSimpleName());
        }
        return ValidationResult.accept();
    }

    @Nullable
    private static String readQuicheVersion(@NonNull File e4mcJar) {
        try (ZipFile zip = new ZipFile(e4mcJar)) {
            ZipEntry entry = zip.getEntry(MODERN_QUICHE_POM);
            if (entry == null || entry.isDirectory()) return null;
            Properties properties = new Properties();
            try (InputStream input = zip.getInputStream(entry)) {
                properties.load(input);
            }
            String version = properties.getProperty("version");
            return version == null ? null : version.trim();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to identify e4mc Netty-Quiche ABI from " + e4mcJar.getName(), throwable);
            return null;
        }
    }

    private static boolean binaryContains(@NonNull File file, @NonNull String needle) throws Exception {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024 + target.length];
            int carry = 0;
            while (true) {
                int count = input.read(buffer, carry, 64 * 1024);
                if (count < 0) return false;
                int length = carry + count;
                outer:
                for (int i = 0; i <= length - target.length; i++) {
                    for (int j = 0; j < target.length; j++) {
                        if (buffer[i + j] != target[j]) continue outer;
                    }
                    return true;
                }
                carry = Math.min(target.length - 1, length);
                if (carry > 0) {
                    System.arraycopy(buffer, length - carry, buffer, 0, carry);
                }
            }
        }
    }

    @NonNull
    private static String sha256Hex(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    @NonNull
    private static byte[] readBoundedFile(@NonNull File file, long maxBytes) throws Exception {
        if (!file.isFile()) throw new IllegalStateException("missing file " + file.getAbsolutePath());
        long length = file.length();
        if (length <= 0L || length > maxBytes || length > Integer.MAX_VALUE) {
            throw new IllegalStateException("unexpected file size=" + length);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) length);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            copyBounded(input, output, maxBytes);
        }
        return output.toByteArray();
    }

    private static void copyBounded(
            @NonNull InputStream input,
            @NonNull java.io.OutputStream output,
            long maxBytes
    ) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IllegalStateException("native payload exceeded " + maxBytes + " bytes");
            }
            output.write(buffer, 0, count);
        }
    }

    private static void writeFileAtomically(@NonNull File destination, @NonNull byte[] data) throws Exception {
        File parent = destination.getParentFile();
        if (parent == null) throw new IllegalStateException("destination has no parent");
        ensureDirectory(parent);
        try (FileOutputStream fos = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(fos)) {
            output.write(data);
            output.flush();
            fos.getFD().sync();
        }
    }

    private static void ensureDirectory(@NonNull File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("unable to create " + dir.getAbsolutePath());
        }
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("unable to replace " + file.getAbsolutePath());
        }
    }

    private static void replaceFile(@NonNull File source, @NonNull File destination) {
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("unable to replace " + destination.getAbsolutePath());
        }
        if (!source.renameTo(destination)) {
            throw new IllegalStateException("unable to install " + destination.getAbsolutePath());
        }
    }

    private static void ensureLoadablePermissions(@NonNull File file) {
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(true, true);
    }

    private enum E4mcVariant {
        MODERN,
        RETRO
    }

    private static final class DetectedE4mc {
        @NonNull final File jar;
        @NonNull final E4mcVariant variant;
        @Nullable final String retroQuicheClass;
        @Nullable final String retroNativeName;

        DetectedE4mc(@NonNull File jar, @NonNull E4mcVariant variant,
                     @Nullable String retroQuicheClass, @Nullable String retroNativeName) {
            this.jar = jar;
            this.variant = variant;
            this.retroQuicheClass = retroQuicheClass;
            this.retroNativeName = retroNativeName;
        }
    }

    @Nullable
    private static DetectedE4mc findE4mc(@NonNull File gameDirectory) {
        /*
         * Hybrid priority rule:
         *
         * The active instance's mods directory is authoritative. Older working
         * DroidBridge builds effectively stopped at the first compatible e4mc
         * found there. A later cross-directory ambiguity check could disable
         * regular e4mc entirely when a stale/global e4mc Retro jar existed in a
         * parent or shared mods directory (or vice versa).
         *
         * Keep ambiguity protection inside a single active mods directory, but
         * never let a lower-priority fallback directory cancel an e4mc selected
         * from the instance itself. This preserves regular e4mc while retaining
         * the opt-in Retro path.
         */
        List<File> modDirs = candidateModsDirs(gameDirectory);
        for (File dir : modDirs) {
            ArrayList<File> ordered = new ArrayList<>();
            Set<String> seenFiles = new LinkedHashSet<>();

            for (int pass = 0; pass < 2; pass++) {
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File file : files) {
                    if (!isJar(file)) continue;
                    boolean named = file.getName().toLowerCase(Locale.ROOT).contains("e4mc");
                    if ((pass == 0) != named) continue;
                    String key;
                    try {
                        key = file.getCanonicalPath();
                    } catch (Throwable ignored) {
                        key = file.getAbsolutePath();
                    }
                    if (seenFiles.add(key)) ordered.add(file);
                }
            }

            DetectedE4mc foundInDirectory = null;
            for (File file : ordered) {
                DetectedE4mc detected = detectE4mc(file);
                if (detected == null) continue;
                if (foundInDirectory != null && !sameFile(foundInDirectory.jar, file)) {
                    Logging.i(TAG, "Multiple e4mc implementations detected in the same active mods directory; "
                            + "refusing to choose a native ABI automatically: "
                            + foundInDirectory.jar.getName() + " and " + file.getName()
                            + " dir=" + dir.getAbsolutePath());
                    return null;
                }
                foundInDirectory = detected;
            }

            if (foundInDirectory != null) {
                Logging.i(TAG, "Selected e4mc from highest-priority mods directory: jar="
                        + foundInDirectory.jar.getName() + " variant=" + foundInDirectory.variant
                        + " dir=" + dir.getAbsolutePath());
                return foundInDirectory;
            }
        }
        return null;
    }

    @Nullable
    private static DetectedE4mc detectE4mc(@NonNull File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            if (zip.getEntry(MODERN_MOD_CLASS) != null && zip.getEntry(MODERN_QUICHE_CLASS) != null) {
                return new DetectedE4mc(jar, E4mcVariant.MODERN, null, null);
            }

            String suffix = "io/netty/incubator/codec/quic/Quiche.class";
            String shadedQuiche = null;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(suffix) || name.equals(suffix)) continue;
                if (!name.toLowerCase(Locale.ROOT).contains("e4mc")) continue;
                shadedQuiche = name;
                break;
            }
            if (shadedQuiche == null) return null;

            boolean retroCore = zip.getEntry(RETRO_MOD_CLASS) != null
                    || zip.getEntry("link/e4mc/E4mcClient.class") != null
                    || zip.getEntry("link/e4mc/E4mcClientForge.class") != null
                    || zip.getEntry("link/e4mc/E4mcClientFabric.class") != null;
            if (!retroCore) return null;

            String shadePrefix = shadedQuiche.substring(0, shadedQuiche.length() - suffix.length());
            String requiredNativeName = "lib" + shadePrefix.replace('/', '_') + "netty_quiche.so";
            return new DetectedE4mc(jar, E4mcVariant.RETRO, shadedQuiche, requiredNativeName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String readRetroQuicheVersion(@NonNull File jar, @Nullable String requiredNativeName) {
        if (requiredNativeName == null || !requiredNativeName.endsWith(".so")) return null;
        String base = requiredNativeName.substring(0, requiredNativeName.length() - 3);
        String[] entries = new String[] {
                "META-INF/native/" + base + "_linux_aarch_64.so",
                "META-INF/native/" + base + "_linux_x86_64.so"
        };
        try (ZipFile zip = new ZipFile(jar)) {
            for (String entryName : entries) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null || entry.isDirectory() || entry.getSize() == 0L
                        || entry.getSize() > MAX_NATIVE_BYTES) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (BufferedInputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0L;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_NATIVE_BYTES) return null;
                        out.write(buffer, 0, count);
                    }
                }
                byte[] data = out.toByteArray();
                String arch = entryName.contains("aarch_64") ? "aarch_64" : "x86_64";
                String expected = "libnetty_quiche_linux_" + arch + "-" + RETRO_QUICHE_VERSION + ".so";
                if (containsAscii(data, expected)) return RETRO_QUICHE_VERSION;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to inspect e4mc Retro embedded Quiche ABI from " + jar.getName(), throwable);
        }
        return null;
    }

    private static boolean sameFile(@NonNull File left, @NonNull File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Throwable ignored) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }

    @Nullable
    private static String sha256ZipEntry(@NonNull File jar, @NonNull String entryName) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to fingerprint e4mc Quiche class from " + jar.getName(), throwable);
            return null;
        }
    }

    private static boolean isJar(@Nullable File file) {
        return file != null && file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    @NonNull
    private static List<File> candidateModsDirs(@NonNull File gameDirectory) {
        ArrayList<File> dirs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addDir(dirs, seen, new File(gameDirectory, "mods"));
        File parent = gameDirectory.getParentFile();
        if (parent != null) addDir(dirs, seen, new File(parent, "mods"));
        try {
            if (PathManager.DIR_MINECRAFT_HOME != null) {
                addDir(dirs, seen, new File(PathManager.DIR_MINECRAFT_HOME, "mods"));
            }
        } catch (Throwable ignored) {
        }
        return dirs;
    }

    private static void addDir(
            @NonNull List<File> out,
            @NonNull Set<String> seen,
            @Nullable File dir
    ) {
        if (dir == null || !dir.isDirectory()) return;
        String path;
        try {
            path = dir.getCanonicalPath();
        } catch (Throwable ignored) {
            path = dir.getAbsolutePath();
        }
        if (seen.add(path)) out.add(dir);
    }

    private static int checkedInt(long value) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("value outside int range: " + value);
        }
        return (int) value;
    }

    private static int readU16LE(@NonNull byte[] data, int offset) {
        requireRange(data, offset, 2);
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readU32LE(@NonNull byte[] data, int offset) {
        requireRange(data, offset, 4);
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24);
    }

    private static long readU64LE(@NonNull byte[] data, int offset) {
        requireRange(data, offset, 8);
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= ((long) data[offset + i] & 0xffL) << (8 * i);
        }
        return value;
    }

    private static void writeU64LE(@NonNull byte[] data, int offset, long value) {
        requireRange(data, offset, 8);
        for (int i = 0; i < 8; i++) {
            data[offset + i] = (byte) ((value >>> (8 * i)) & 0xffL);
        }
    }

    private static void requireRange(@NonNull byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("ELF range outside file offset=" + offset + " length=" + length);
        }
    }

    @NonNull
    private static String readCString(@NonNull byte[] data, int offset, int end) {
        if (offset < 0 || offset >= end || end > data.length) {
            throw new IllegalArgumentException("bad C string range");
        }
        int cursor = offset;
        while (cursor < end && data[cursor] != 0) cursor++;
        if (cursor == end) throw new IllegalArgumentException("unterminated ELF string");
        return new String(data, offset, cursor - offset, StandardCharsets.US_ASCII);
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
        @NonNull final ArrayList<ElfLoadSegment> loadSegments;

        ElfLayout(
                int machine,
                int dynamicOffset,
                int dynamicSize,
                @NonNull ArrayList<ElfLoadSegment> loadSegments
        ) {
            this.machine = machine;
            this.dynamicOffset = dynamicOffset;
            this.dynamicSize = dynamicSize;
            this.loadSegments = loadSegments;
        }

        @NonNull
        static ElfLayout parse(@NonNull byte[] data) {
            if (data.length < 64
                    || (data[0] & 0xff) != 0x7f
                    || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
                throw new IllegalArgumentException("not ELF");
            }
            if ((data[4] & 0xff) != 2) throw new IllegalArgumentException("not ELF64");
            if ((data[5] & 0xff) != 1) throw new IllegalArgumentException("not little-endian ELF");

            int machine = readU16LE(data, 18);
            long programHeaderOffset = readU64LE(data, 32);
            int programHeaderEntrySize = readU16LE(data, 54);
            int programHeaderCount = readU16LE(data, 56);
            if (programHeaderEntrySize < 56 || programHeaderCount <= 0) {
                throw new IllegalArgumentException("invalid program header table");
            }
            if (programHeaderOffset < 0L
                    || programHeaderOffset > Integer.MAX_VALUE
                    || programHeaderOffset + (long) programHeaderEntrySize * programHeaderCount > data.length) {
                throw new IllegalArgumentException("program header table outside file");
            }

            int dynamicOffset = -1;
            int dynamicSize = -1;
            ArrayList<ElfLoadSegment> loadSegments = new ArrayList<>();
            for (int i = 0; i < programHeaderCount; i++) {
                int off = checkedInt(programHeaderOffset + (long) i * programHeaderEntrySize);
                long type = readU32LE(data, off);
                long fileOffset = readU64LE(data, off + 8);
                long virtualAddress = readU64LE(data, off + 16);
                long fileSize = readU64LE(data, off + 32);

                if (type == PT_LOAD) {
                    loadSegments.add(new ElfLoadSegment(fileOffset, virtualAddress, fileSize));
                } else if (type == PT_DYNAMIC) {
                    if (fileOffset > Integer.MAX_VALUE || fileSize > Integer.MAX_VALUE
                            || fileOffset < 0L || fileSize < 0L
                            || fileOffset + fileSize > data.length) {
                        throw new IllegalArgumentException("PT_DYNAMIC outside file");
                    }
                    dynamicOffset = (int) fileOffset;
                    dynamicSize = (int) fileSize;
                }
            }
            return new ElfLayout(machine, dynamicOffset, dynamicSize, loadSegments);
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

    private static final class ValidationResult {
        final boolean accepted;
        @NonNull final String reason;

        private ValidationResult(boolean accepted, @NonNull String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        static ValidationResult accept() {
            return new ValidationResult(true, "ok");
        }

        static ValidationResult reject(@NonNull String reason) {
            return new ValidationResult(false, reason);
        }
    }

    private static final class AdaptationResult {
        final boolean accepted;
        @NonNull final String reason;

        private AdaptationResult(boolean accepted, @NonNull String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        static AdaptationResult accept(@NonNull String reason) {
            return new AdaptationResult(true, reason);
        }

        static AdaptationResult reject(@NonNull String reason) {
            return new AdaptationResult(false, reason);
        }
    }
}
