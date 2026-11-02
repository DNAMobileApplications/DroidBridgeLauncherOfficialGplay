/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft or MCSR Ranked and does not grant rights to
 * Minecraft, Mojang, Microsoft, MCSR Ranked, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Android native compatibility for MCSR Ranked's bundled Xerial Snappy 1.1.10.
 *
 * MCSR Ranked ships the correct Android JNI libraries in its own jar, including
 * Linux/android-aarch64/libsnappyjava.so. Xerial's OSInfo, however, detects
 * Android only when java.runtime.name contains "android". DroidBridge uses an
 * OpenJDK runtime, so Snappy sees Linux/aarch64 and extracts its glibc binary,
 * which depends on libm.so.6/libc.so.6 and cannot be loaded by Android/Bionic.
 *
 * DroidBridge does not alter MCSR Ranked's jar or its replay/anti-cheat code.
 * It extracts the Android JNI library already distributed by the mod and uses
 * Snappy's own org.xerial.snappy.lib.path/lib.name override mechanism.
 */
public final class MCSRRankedCompat {
    private static final String TAG = "MCSRRankedCompat";

    private static final String MOD_CLASS_ENTRY =
            "com/mcsrranked/client/MCSRRankedClient.class";
    private static final String SNAPPY_ANDROID_ARM64_ENTRY =
            "org/xerial/snappy/native/Linux/android-aarch64/libsnappyjava.so";
    private static final String SNAPPY_ANDROID_ARM_ENTRY =
            "org/xerial/snappy/native/Linux/android-arm/libsnappyjava.so";
    private static final String SNAPPY_LIBRARY_NAME = "libsnappyjava.so";

    private static final String SNAPPY_LIB_PATH_PREFIX = "-Dorg.xerial.snappy.lib.path=";
    private static final String SNAPPY_LIB_NAME_PREFIX = "-Dorg.xerial.snappy.lib.name=";

    private static final String AUTH_AGENT_ASSET =
            "components/mcsr_ranked_compat/mcsr_ranked_compat_agent.jar";
    private static final String AUTH_AGENT_DIR = "mcsr-ranked-android-compat";
    private static final String AUTH_AGENT_NAME = "mcsr_ranked_compat_agent.jar";
    private static final String HARDWARE_UUID_PROPERTY = "-Ddroidbridge.mcsrranked.hardwareUuid=";

    private MCSRRankedCompat() {
    }

    /**
     * If MCSR Ranked is present, extract its bundled Android Snappy JNI and make
     * Xerial load that exact file. No mod bytecode or jar contents are changed.
     */
    public static void applySnappyCompatibility(
            @NonNull android.content.Context context,
            @NonNull File gameDirectory,
            @NonNull List<String> jvmArgs
    ) {
        File rankedJar = findMcsrRankedJar(gameDirectory);
        if (rankedJar == null) return;

        // MCSR Ranked 5.8.26 now asks NetworkInterface.getHardwareAddress() while
        // building its authentication payload. Android 10+ intentionally blocks the
        // SIOCGIFHWADDR ioctl for app UIDs, so upstream returns null and Ranked then
        // rejects its own authentication JSON. Keep this Android-only and scoped to
        // MCSR Ranked: provide a stable app/device UUID to a tiny javaagent that only
        // replaces UUIDUtils.getHardwareUUID(). No socket, replay, anti-cheat, or
        // authentication-signing code is changed.
        applyAuthenticationCompatibility(context, jvmArgs);

        String nativeEntry = resolveAndroidSnappyEntry();
        if (nativeEntry == null) {
            Logging.i(TAG, "MCSR Ranked detected but this CPU ABI has no bundled Android Snappy target: "
                    + Architecture.archAsString(Architecture.getDeviceArchitecture()));
            return;
        }

        File nativeDir = new File(context.getCacheDir(), "mcsrranked-snappy-android");
        File nativeFile = new File(nativeDir, SNAPPY_LIBRARY_NAME);

        try {
            extractBundledNative(rankedJar, nativeEntry, nativeDir, nativeFile);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare MCSR Ranked Android Snappy JNI from "
                    + rankedJar.getAbsolutePath(), throwable);
            return;
        }

        // Remove stale/manual overrides so the DroidBridge-selected Android native
        // is authoritative for this launch only.
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String arg = jvmArgs.get(i);
            if (arg == null) continue;
            if (arg.startsWith(SNAPPY_LIB_PATH_PREFIX)
                    || arg.startsWith(SNAPPY_LIB_NAME_PREFIX)) {
                jvmArgs.remove(i);
            }
        }

        jvmArgs.add(SNAPPY_LIB_PATH_PREFIX + nativeDir.getAbsolutePath());
        jvmArgs.add(SNAPPY_LIB_NAME_PREFIX + SNAPPY_LIBRARY_NAME);

        Logging.i(TAG, "MCSR Ranked Android Snappy compatibility active: jar="
                + rankedJar.getName()
                + " entry=" + nativeEntry
                + " native=" + nativeFile.getAbsolutePath());
    }


    private static void applyAuthenticationCompatibility(
            @NonNull android.content.Context context,
            @NonNull List<String> jvmArgs
    ) {
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) {
                androidId = context.getPackageName();
            }
            UUID stableUuid = UUID.nameUUIDFromBytes(
                    ("DroidBridge-MCSR-Ranked:" + androidId).getBytes(StandardCharsets.UTF_8));

            for (int i = jvmArgs.size() - 1; i >= 0; i--) {
                String arg = jvmArgs.get(i);
                if (arg == null) continue;
                if (arg.startsWith(HARDWARE_UUID_PROPERTY)
                        || (arg.startsWith("-javaagent:") && arg.contains(AUTH_AGENT_NAME))) {
                    jvmArgs.remove(i);
                }
            }

            File agent = installAuthenticationAgent(context);
            if (agent == null) {
                Logging.i(TAG, "MCSR Ranked auth compatibility unavailable: agent asset missing");
                return;
            }

            jvmArgs.add(0, "-javaagent:" + agent.getAbsolutePath());
            jvmArgs.add(HARDWARE_UUID_PROPERTY + stableUuid.toString());
            Logging.i(TAG, "MCSR Ranked Android authentication compatibility active");
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare MCSR Ranked Android authentication compatibility", throwable);
        }
    }

    @Nullable
    private static File installAuthenticationAgent(@NonNull android.content.Context context) {
        File dir = new File(context.getFilesDir(), AUTH_AGENT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return null;
        File out = new File(dir, AUTH_AGENT_NAME);
        File tmp = new File(dir, AUTH_AGENT_NAME + ".tmp");
        try (InputStream input = context.getAssets().open(AUTH_AGENT_ASSET);
             FileOutputStream output = new FileOutputStream(tmp, false)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
            if (out.exists() && !out.delete()) return null;
            if (!tmp.renameTo(out)) return null;
            return out.isFile() && out.length() > 0L ? out : null;
        } catch (Throwable throwable) {
            try { tmp.delete(); } catch (Throwable ignored) { }
            Logging.e(TAG, "Unable to install MCSR Ranked auth compatibility agent", throwable);
            return null;
        }
    }

    @Nullable
    private static String resolveAndroidSnappyEntry() {
        switch (Architecture.getDeviceArchitecture()) {
            case Architecture.ARCH_ARM64:
                return SNAPPY_ANDROID_ARM64_ENTRY;
            case Architecture.ARCH_ARM:
                return SNAPPY_ANDROID_ARM_ENTRY;
            default:
                return null;
        }
    }

    private static void extractBundledNative(
            @NonNull File rankedJar,
            @NonNull String entryName,
            @NonNull File nativeDir,
            @NonNull File nativeFile
    ) throws Exception {
        if (!nativeDir.isDirectory() && !nativeDir.mkdirs() && !nativeDir.isDirectory()) {
            throw new IllegalStateException("unable to create " + nativeDir.getAbsolutePath());
        }

        try (ZipFile zip = new ZipFile(rankedJar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException("MCSR Ranked jar does not contain " + entryName);
            }

            // The compatibility target is tiny compared with the mod jar. Reuse an
            // existing extraction only when its byte length still matches the jar.
            long expectedSize = entry.getSize();
            if (nativeFile.isFile()
                    && nativeFile.length() > 0L
                    && (expectedSize < 0L || nativeFile.length() == expectedSize)) {
                ensureLoadablePermissions(nativeFile);
                return;
            }

            File temp = new File(nativeDir, SNAPPY_LIBRARY_NAME + ".tmp");
            if (temp.exists() && !temp.delete()) {
                throw new IllegalStateException("unable to replace stale " + temp.getAbsolutePath());
            }

            try (InputStream raw = zip.getInputStream(entry);
                 BufferedInputStream input = new BufferedInputStream(raw);
                 FileOutputStream fos = new FileOutputStream(temp);
                 BufferedOutputStream output = new BufferedOutputStream(fos)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                long written = 0L;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    written += count;
                }
                output.flush();
                fos.getFD().sync();

                if (written <= 0L || (expectedSize >= 0L && written != expectedSize)) {
                    throw new IllegalStateException("incomplete Snappy JNI extraction: expected="
                            + expectedSize + " wrote=" + written);
                }
            }

            ensureLoadablePermissions(temp);

            if (nativeFile.exists() && !nativeFile.delete()) {
                throw new IllegalStateException("unable to replace " + nativeFile.getAbsolutePath());
            }
            if (!temp.renameTo(nativeFile)) {
                throw new IllegalStateException("unable to install " + nativeFile.getAbsolutePath());
            }
            ensureLoadablePermissions(nativeFile);
        }
    }

    private static void ensureLoadablePermissions(@NonNull File file) {
        // Private app storage remains private; these flags only make the extracted
        // JNI library loadable by the Minecraft JVM running under the same UID.
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setExecutable(true, true);
    }

    @Nullable
    private static File findMcsrRankedJar(@NonNull File gameDirectory) {
        List<File> modDirs = candidateModsDirs(gameDirectory);

        // Fast path: normal filename.
        for (File dir : modDirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!isJar(file)) continue;
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (lower.contains("mcsrranked") || lower.contains("mcsr-ranked")) {
                    if (containsRequiredEntries(file)) return file;
                }
            }
        }

        // Renamed jars are still supported, but only after verifying the actual
        // MCSR Ranked client class and Android Snappy resource are both present.
        for (File dir : modDirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (isJar(file) && containsRequiredEntries(file)) return file;
            }
        }

        return null;
    }

    private static boolean containsRequiredEntries(@NonNull File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry(MOD_CLASS_ENTRY) != null
                    && (zip.getEntry(SNAPPY_ANDROID_ARM64_ENTRY) != null
                    || zip.getEntry(SNAPPY_ANDROID_ARM_ENTRY) != null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isJar(@Nullable File file) {
        return file != null
                && file.isFile()
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
}
