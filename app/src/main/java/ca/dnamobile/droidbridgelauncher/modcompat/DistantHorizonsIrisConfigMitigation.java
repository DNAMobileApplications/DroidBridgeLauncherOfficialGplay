/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class DistantHorizonsIrisConfigMitigation {
    private static final String TAG = "DistantHorizonsIrisConfig";
    private static final int MAX_CONFIG_SCAN_DEPTH = 5;

    // DH's rendering engine must match Minecraft's selected graphics API.
    // Older Iris compatibility may require OpenGL, but that override must not
    // carry over when the instance starts using Vulkan.
    private static final Pattern RENDERING_ENGINE_ASSIGNMENT = Pattern.compile(
            "(?im)^([ \\t]*renderingEngine[ \\t]*=[ \\t]*)(\\\"?)(AUTO|BLAZE_3D|BLAZE3D|Blaze_3D|Blaze3D|OPEN_GL|OpenGL|VULKAN|Vulkan)(\\\"?)(.*)$"
    );

    // Older DH configs used renderingApi. Keep repairing that key too, but use
    // the same engine values so a stale DroidBridge VULKAN value is never left behind.
    private static final Pattern LEGACY_RENDERING_API_ASSIGNMENT = Pattern.compile(
            "(?im)^([ \\t]*renderingApi[ \\t]*=[ \\t]*)(\\\"?)(AUTO|BLAZE_3D|BLAZE3D|Blaze_3D|Blaze3D|OPEN_GL|OpenGL|VULKAN|Vulkan)(\\\"?)(.*)$"
    );

    private static final Pattern EXPERIMENTAL_HEADER = Pattern.compile(
            "(?im)^\\s*\\[client\\.advanced\\.graphics\\.experimental]\\s*$"
    );

    private DistantHorizonsIrisConfigMitigation() {
    }

    @NonNull
    public static ArrayList<String> prepare(
            @Nullable File gameDir,
            @NonNull String graphicsApiMode,
            boolean hasIris,
            boolean modernMinecraft
    ) {
        ArrayList<String> messages = new ArrayList<>();
        boolean vulkan = "vulkan".equalsIgnoreCase(graphicsApiMode);
        String targetRenderingEngine = vulkan && modernMinecraft ? "BLAZE_3D" : "OPEN_GL";
        // The legacy renderingApi option is a separate setting. Restore AUTO
        // on Vulkan rather than incorrectly putting a Blaze3D engine value in it.
        String targetRenderingApi = vulkan && modernMinecraft ? "AUTO" : "OPEN_GL";
        if (gameDir == null) {
            messages.add("Skipped: game directory is null");
            return messages;
        }

        try {
            // Never impose the Iris OpenGL fallback on a Vulkan instance.
            // Older versions retain the existing Iris-only repair.
            if (!modernMinecraft && !hasIris) {
                messages.add("Skipped: older Minecraft without Iris");
                return messages;
            }
            if (vulkan && hasIris) {
                messages.add("Warning: Iris may not support Distant Horizons through Vulkan/Blaze3D; keeping Minecraft's selected Vulkan backend");
            }

            ArrayList<File> configDirs = getCandidateConfigDirs(gameDir);
            File primaryConfigDir = new File(gameDir, "config");
            File primaryConfig = new File(primaryConfigDir, "DistantHorizons.toml");

            ArrayList<File> candidates = new ArrayList<>();
            for (File configDir : configDirs) {
                File directConfig = new File(configDir, "DistantHorizons.toml");
                if (directConfig.isFile() && !containsFile(candidates, directConfig)) {
                    candidates.add(directConfig);
                }
                if (configDir.isDirectory()) {
                    collectDistantHorizonsConfigFiles(configDir, candidates, 0);
                }
            }

            if (!primaryConfig.isFile()) {
                writeMinimalConfig(primaryConfig, targetRenderingEngine);
                messages.add("Created primary DH config with renderingEngine=" + targetRenderingEngine + " at " + primaryConfig.getAbsolutePath());
                if (!containsFile(candidates, primaryConfig)) candidates.add(primaryConfig);
            }

            // Do not invent a parallel configs/DistantHorizons.toml. Patch any
            // existing alternative file, but keep the canonical config as the
            // only newly created file for this instance.

            if (candidates.isEmpty()) {
                // This should only happen if the primary file could not be created, but keep a safe message.
                messages.add("No Distant Horizons config files found to patch");
                logMessages(messages);
                return messages;
            }

            boolean changedAny = false;
            for (File file : candidates) {
                if (!file.isFile() || file.length() > 1024L * 1024L * 2L) continue;

                String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String patched = patchDistantHorizonsConfigText(original, targetRenderingEngine, targetRenderingApi);
                if (!patched.equals(original)) {
                    Files.write(file.toPath(), patched.getBytes(StandardCharsets.UTF_8));
                    changedAny = true;
                    messages.add("Set DH renderingEngine=" + targetRenderingEngine + " renderingApi=" + targetRenderingApi + " in " + file.getAbsolutePath());
                } else {
                    messages.add("DH config already appears patched: " + file.getAbsolutePath());
                }

                messages.add(describeRenderingApiState(file));
            }

            if (!changedAny) {
                messages.add("DH rendering config verified: engine=" + targetRenderingEngine + " api=" + targetRenderingApi);
            }
        } catch (Throwable throwable) {
            messages.add("Failed: " + throwable.getClass().getSimpleName() + ": " + (throwable.getMessage() == null ? "" : throwable.getMessage()));
            Logging.e(TAG, "Failed to align Distant Horizons rendering configuration", throwable);
        }

        logMessages(messages);
        return messages;
    }

    @NonNull
    private static String patchDistantHorizonsConfigText(
            @NonNull String original,
            @NonNull String targetRenderingEngine,
            @NonNull String targetRenderingApi
    ) {
        String patched = replaceAssignments(original, RENDERING_ENGINE_ASSIGNMENT, targetRenderingEngine);
        patched = replaceAssignments(patched, LEGACY_RENDERING_API_ASSIGNMENT, targetRenderingApi);

        if (containsRenderingEngineAssignment(patched)) {
            return patched;
        }

        Matcher headerMatcher = EXPERIMENTAL_HEADER.matcher(patched);
        if (headerMatcher.find()) {
            int insert = headerMatcher.end();
            return patched.substring(0, insert)
                    + "\nrenderingEngine = \"" + targetRenderingEngine + "\""
                    + patched.substring(insert);
        }

        String separator = patched.endsWith("\n") || patched.isEmpty() ? "" : "\n";
        return patched
                + separator
                + "\n[client.advanced.graphics.experimental]\n"
                + "renderingEngine = \"" + targetRenderingEngine + "\"\n";
    }

    @NonNull
    private static String replaceAssignments(@NonNull String text, @NonNull Pattern pattern, @NonNull String targetRenderingEngine) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(1);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(prefix + "\"" + targetRenderingEngine + "\"" + matcher.group(5)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean containsRenderingEngineAssignment(@NonNull String text) {
        Matcher matcher = Pattern.compile("(?im)^[ \\t]*renderingEngine[ \\t]*=").matcher(text);
        return matcher.find();
    }

    private static void writeMinimalConfig(@NonNull File file, @NonNull String targetRenderingEngine) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        String text = "# Distant Horizons graphics API compatibility.\n"
                + "[client.advanced.graphics.experimental]\n"
                + "renderingEngine = \"" + targetRenderingEngine + "\"\n"
                + "renderingApi = \"" + ("BLAZE_3D".equals(targetRenderingEngine) ? "AUTO" : "OPEN_GL") + "\"\n";
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }


    @NonNull
    private static String describeRenderingApiState(@NonNull File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("(?im)^[ \\t]*[^#\\n=]*render[^#\\n=]*(?:api|engine)[^#\\n=]*=[^\\n]*").matcher(text);
            ArrayList<String> lines = new ArrayList<>();
            while (matcher.find() && lines.size() < 4) {
                lines.add(matcher.group().trim());
            }
            if (lines.isEmpty()) return "Verify " + file.getName() + ": no renderingApi-like line found after patch";
            return "Verify " + file.getName() + ": " + join(lines, " | ");
        } catch (Throwable throwable) {
            return "Verify failed for " + file.getAbsolutePath() + ": " + throwable.getClass().getSimpleName();
        }
    }

    @NonNull
    private static ArrayList<File> getCandidateConfigDirs(@NonNull File gameDir) {
        ArrayList<File> dirs = new ArrayList<>();
        addUniqueDir(dirs, new File(gameDir, "config"));
        addUniqueDir(dirs, new File(gameDir, "configs"));
        return dirs;
    }

    private static void addUniqueDir(@NonNull ArrayList<File> dirs, @NonNull File dir) {
        String path = dir.getAbsolutePath();
        for (File existing : dirs) {
            if (existing.getAbsolutePath().equals(path)) return;
        }
        dirs.add(dir);
    }

    private static void collectDistantHorizonsConfigFiles(
            @NonNull File dir,
            @NonNull ArrayList<File> out,
            int depth
    ) {
        if (depth > MAX_CONFIG_SCAN_DEPTH) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (looksLikeDistantHorizonsPath(file.getName()) || depth < 2) {
                    collectDistantHorizonsConfigFiles(file, out, depth + 1);
                }
                continue;
            }

            if (!file.isFile()) continue;
            if (!looksLikeTextConfig(file.getName())) continue;
            if (!looksLikeDistantHorizonsPath(file.getName()) && !looksLikeDistantHorizonsPath(file.getParentFile() != null ? file.getParentFile().getName() : "")) {
                continue;
            }
            if (!containsFile(out, file)) out.add(file);
        }
    }

    private static boolean containsFile(@NonNull ArrayList<File> files, @NonNull File candidate) {
        String path = candidate.getAbsolutePath();
        for (File file : files) {
            if (file.getAbsolutePath().equals(path)) return true;
        }
        return false;
    }

    private static boolean looksLikeDistantHorizonsPath(@Nullable String value) {
        String normalized = normalizeName(value == null ? "" : value);
        return normalized.contains("distanthorizons")
                || normalized.contains("distanthorizon")
                || normalized.equals("dh")
                || normalized.contains("lod");
    }

    private static boolean looksLikeTextConfig(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".toml");
    }

    @NonNull
    private static String normalizeName(@NonNull String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    @NonNull
    private static String join(@NonNull ArrayList<String> values, @NonNull String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static void logMessages(@NonNull ArrayList<String> messages) {
        int updates = 0;
        boolean failed = false;
        for (String message : messages) {
            if (message.startsWith("Created ") || message.startsWith("Set ")) {
                updates++;
            } else if (message.startsWith("Failed:")) {
                failed = true;
                Logging.i(TAG, message);
            }
        }
        if (!failed) {
            Logging.i(TAG, "renderer config " + (updates > 0 ? "updated" : "verified"));
        }
    }
}
