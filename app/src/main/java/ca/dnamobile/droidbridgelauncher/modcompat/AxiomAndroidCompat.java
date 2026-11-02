/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */
package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects AFA (Axiom For Android) and arms DroidBridge's launcher-side mouse policy.
 *
 * AFA is intentionally left completely stock. DroidBridge does not transform AFA, Axiom,
 * or Minecraft classes. Compatibility mode only disables DroidBridge's own menu-edge
 * recapture / generic re-center behavior that can fight AFA's cursor-state synchronizer.
 * This mirrors the stable launcher model: let AFA own its GLFW/SDL cursor state and keep
 * the Android launcher bridge from trying to become a second cursor authority.
 */
public final class AxiomAndroidCompat {
    public static final String BACKEND_GLFW = "glfw";
    public static final String BACKEND_SDL3 = "sdl3";

    private static volatile boolean active;
    @Nullable private static volatile String detectedVersion;
    @Nullable private static volatile String detectedBackend;
    @Nullable private static volatile String detectedJar;

    private AxiomAndroidCompat() {
    }

    public static final class Detection {
        public final boolean detected;
        @Nullable public final String version;
        @Nullable public final String backend;
        @Nullable public final String jarName;

        private Detection(boolean detected, @Nullable String version,
                          @Nullable String backend, @Nullable String jarName) {
            this.detected = detected;
            this.version = version;
            this.backend = backend;
            this.jarName = jarName;
        }

        @NonNull
        public String describe() {
            if (!detected) return "not detected";
            return "version=" + safe(version)
                    + " backend=" + safe(backend)
                    + " jar=" + safe(jarName);
        }
    }

    @NonNull
    public static Detection prepare(@NonNull Context context, @NonNull File gameDirectory) {
        clear();
        Detection detection = detect(gameDirectory);
        if (!detection.detected) return detection;

        if (!LauncherPreferences.isAxiomAndroidCompatibilityEnabled(context)) {
            LauncherLogManager.append("DroidBridgeAxiomCompat: detected but disabled "
                    + detection.describe());
            return detection;
        }

        active = true;
        detectedVersion = detection.version;
        detectedBackend = detection.backend;
        detectedJar = detection.jarName;
        LauncherLogManager.append("DroidBridgeAxiomCompat: armed " + detection.describe()
                + " mode=stock-afa launcher-edge-capture-bypass=true game-class-patching=false");
        return detection;
    }

    public static void clear() {
        active = false;
        detectedVersion = null;
        detectedBackend = null;
        detectedJar = null;
    }

    public static boolean isActive() {
        return active;
    }

    @Nullable
    public static String getDetectedVersion() {
        return detectedVersion;
    }

    @Nullable
    public static String getDetectedBackend() {
        return detectedBackend;
    }

    @Nullable
    public static String getDetectedJar() {
        return detectedJar;
    }

    @NonNull
    public static Detection detect(@NonNull File gameDirectory) {
        ArrayList<File> modDirectories = new ArrayList<>();
        addDirectory(modDirectories, new File(gameDirectory, "mods"));

        File instanceRoot = gameDirectory.getParentFile();
        if (instanceRoot != null) addDirectory(modDirectories, new File(instanceRoot, "mods"));
        if (PathManager.DIR_MINECRAFT_HOME != null
                && !PathManager.DIR_MINECRAFT_HOME.trim().isEmpty()) {
            addDirectory(modDirectories, new File(PathManager.DIR_MINECRAFT_HOME, "mods"));
        }

        for (File modsDir : modDirectories) {
            File[] jars = modsDir.listFiles((dir, name) ->
                    name != null && name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars == null) continue;
            Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            for (File jar : jars) {
                Detection detection = inspectJar(jar);
                if (detection.detected) return detection;
            }
        }
        return new Detection(false, null, null, null);
    }

    @NonNull
    private static Detection inspectJar(@NonNull File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null) return new Detection(false, null, null, null);

            String meta = readText(zip, metadata, 256 * 1024);
            String compact = meta.toLowerCase(Locale.ROOT)
                    .replace(" ", "")
                    .replace("\n", "")
                    .replace("\r", "")
                    .replace("\t", "");
            if (!compact.contains("\"id\":\"axiom_android\"")) {
                return new Detection(false, null, null, null);
            }

            String version = jsonStringValue(meta, "version");
            String backend = null;
            ZipEntry mixins = zip.getEntry("axiom_android.mixins.json");
            if (mixins != null) {
                String mixinText = readText(zip, mixins, 128 * 1024);
                if (mixinText.contains("SdlWindowerMixin")) {
                    backend = BACKEND_SDL3;
                } else if (mixinText.contains("GlfwWindowerMixin")) {
                    backend = BACKEND_GLFW;
                }
            }

            return new Detection(true, version, backend, jar.getName());
        } catch (Throwable throwable) {
            return new Detection(false, null, null, null);
        }
    }

    @NonNull
    private static String readText(@NonNull ZipFile zip, @NonNull ZipEntry entry, int maxBytes)
            throws Exception {
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int allowed = Math.min(read, maxBytes - total);
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                    total += allowed;
                }
                if (total >= maxBytes) break;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Nullable
    private static String jsonStringValue(@NonNull String json, @NonNull String key) {
        String needle = "\"" + key + "\"";
        int keyAt = json.indexOf(needle);
        if (keyAt < 0) return null;
        int colon = json.indexOf(':', keyAt + needle.length());
        if (colon < 0) return null;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        String value = json.substring(open + 1, close).trim();
        return value.isEmpty() ? null : value;
    }

    private static void addDirectory(@NonNull ArrayList<File> out, @NonNull File directory) {
        if (directory.isDirectory() && !out.contains(directory)) out.add(directory);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? "<unknown>" : value;
    }
}
