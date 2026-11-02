/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.modcompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/**
 * Legacy cleanup for mods disabled by DroidBridge's retired Native Mesa safety profile.
 *
 * Older builds isolated Sodium/Embeddium/Rubidium Extra, Dynamic Lights, Oculus and Iris
 * on Minecraft 1.20.1 while DroidBridge Native Mesa was selected. That isolation policy
 * has been retired; these mods are now left enabled. This class remains only so installs
 * upgraded from an older build can restore jars carrying the old disabled suffix.
 */
public final class NativeMesaRendererSafetyProfile {
    private static final String TAG = "NativeMesaSafety";
    private static final String DISABLED_SUFFIX = ".droidbridge-native-mesa-disabled";

    private NativeMesaRendererSafetyProfile() { }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;
        try {
            int restored = restorePreviouslyDisabled(getCandidateModsDirs(gameDir));
            if (restored > 0) {
                appendLog("Retired Native Mesa safety profile: restored=" + restored);
            }
        } catch (Throwable t) {
            appendLog("Retired Native Mesa safety profile cleanup failed: " + t);
            Logging.e(TAG, "Failed to restore jars disabled by the retired Native Mesa safety profile", t);
        }
    }

    private static int restorePreviouslyDisabled(List<File> dirs) {
        int restored = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles(File::isFile);
            if (files == null) continue;
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(DISABLED_SUFFIX)) continue;

                String original = name.substring(0, name.length() - DISABLED_SUFFIX.length());
                if (!isRetiredSafetyProfileTarget(original)) continue;

                File target = new File(f.getParentFile(), original);
                if (!target.exists() && f.renameTo(target)) {
                    restored++;
                    appendLog("Retired Native Mesa safety profile: restored " + original);
                }
            }
        }
        return restored;
    }

    private static boolean isRetiredSafetyProfileTarget(@NonNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.contains("embeddiumextra") || lower.contains("embeddium-extra")
                || lower.contains("rubidiumextra") || lower.contains("rubidium-extra")
                || lower.contains("sodium-extra") || lower.contains("sodiumextra")
                || lower.contains("dynamiclights") || lower.contains("dynamic-lights")
                || lower.contains("oculus") || lower.contains("iris");
    }

    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(gameDir, "mods"));
        File instance = gameDir.getParentFile();
        if (instance != null) dirs.add(new File(instance, "mods"));
        File root = instance != null ? instance.getParentFile() : null;
        if (root != null) dirs.add(new File(root, "mods"));
        return dirs;
    }

    private static void appendLog(String s) {
        try { Logger.appendToLog(s); } catch (Throwable ignored) { }
    }
}
