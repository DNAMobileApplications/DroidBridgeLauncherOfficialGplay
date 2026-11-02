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

package ca.dnamobile.droidbridgelauncher.modmanager;

import java.util.Locale;

/**
 * Narrow compatibility exceptions for store metadata that is known to span
 * multiple Minecraft generations even though the dependency itself does not.
 *
 * Keep these rules deliberately specific. A required dependency is still fatal
 * unless a rule here proves that the relation belongs to a different generation
 * of the same parent project.
 */
final class ModDependencyCompatibility {
    private ModDependencyCompatibility() {
    }

    static boolean shouldSkipRequiredDependency(
            ModrinthProject parentProject,
            ModrinthProject dependencyProject,
            String minecraftVersion
    ) {
        if (!isE4mcRetro(parentProject)) return false;
        if (!isUniMixins(dependencyProject)) return false;

        // e4mc Retro's legacy 1.7.10 build genuinely uses UniMixins. Some store
        // metadata exposes that project relation on later legacy files as well.
        // Installing UniMixins into 1.8+ is not a valid compatibility fallback;
        // ignore only that stale cross-generation relation instead.
        return !"1.7.10".equals(normalizeMinecraftVersion(minecraftVersion));
    }

    static String skippedDependencyStatus(
            ModrinthProject dependencyProject,
            String minecraftVersion
    ) {
        String title = dependencyProject == null || dependencyProject.title == null
                ? "legacy dependency"
                : dependencyProject.title.trim();
        if (title.isEmpty()) title = "legacy dependency";
        return "Ignoring stale e4mc Retro dependency " + title
                + " for Minecraft " + normalizeMinecraftVersion(minecraftVersion)
                + "; UniMixins is retained for the 1.7.10 Retro generation only.";
    }

    private static boolean isE4mcRetro(ModrinthProject project) {
        if (project == null) return false;
        String identity = identity(project);
        return identity.contains("e4mc") && identity.contains("retro");
    }

    private static boolean isUniMixins(ModrinthProject project) {
        if (project == null) return false;
        String identity = identity(project).replace("-", "").replace("_", "").replace(" ", "");
        return identity.contains("unimixins");
    }

    private static String identity(ModrinthProject project) {
        StringBuilder out = new StringBuilder();
        append(out, project.title);
        append(out, project.slug);
        append(out, project.projectId);
        return out.toString().toLowerCase(Locale.US);
    }

    private static void append(StringBuilder out, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(trimmed);
    }

    private static String normalizeMinecraftVersion(String value) {
        return value == null ? "" : value.trim();
    }
}
