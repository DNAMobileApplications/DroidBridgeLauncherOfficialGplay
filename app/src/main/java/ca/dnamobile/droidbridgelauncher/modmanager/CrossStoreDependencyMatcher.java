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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Matches a dependency selected from one catalogue with content that DroidBridge
 * already tracks from another catalogue.
 *
 * Modrinth and CurseForge use unrelated project ids, so cross-store identity must
 * never be inferred from those ids. Strong file identity (SHA-1) wins immediately;
 * otherwise the installed entry must match the current Minecraft/loader target and
 * then share a stable filename, slug, or title identity with the requested project.
 *
 * A match only reuses the existing file. It deliberately does not create an alias
 * manifest entry for the second store and does not change the existing dependency flag.
 */
public final class CrossStoreDependencyMatcher {
    private CrossStoreDependencyMatcher() {
    }

    public static final class Match {
        @NonNull public final JSONObject entry;
        @NonNull public final ModManagerSource source;
        @NonNull public final String reason;

        Match(@NonNull JSONObject entry, @NonNull ModManagerSource source, @NonNull String reason) {
            this.entry = entry;
            this.source = source;
            this.reason = reason;
        }

        @NonNull
        public String describe(@NonNull ModrinthProject requestedProject) {
            String installedTitle = entry.optString("title", "").trim();
            if (installedTitle.isEmpty()) installedTitle = requestedProject.title;
            String installedVersion = entry.optString("versionNumber", "").trim();
            return "Existing " + source.getDisplayName() + " install already satisfies dependency "
                    + installedTitle
                    + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                    + " (matched by " + reason + "); skipping duplicate download/tracking.";
        }
    }

    @Nullable
    public static Match findInstalledEquivalent(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType contentType,
            @NonNull ModManagerSource requestedSource,
            @NonNull ModrinthProject requestedProject,
            @NonNull ModrinthVersion requestedVersion,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) {
        ModrinthFile requestedFile = requestedVersion.getPrimaryFile();
        String wantedSha1 = normalizeSha1(requestedFile == null ? null : requestedFile.sha1);
        String wantedFileName = normalizeFileName(requestedFile == null ? null : requestedFile.filename);
        String wantedSlug = identityKey(requestedProject.slug);
        String wantedTitle = identityKey(requestedProject.title);

        ArrayList<JSONObject> installed = ModManagerManifest.getInstalledEntriesIncludingModpack(gameDirectory, contentType);

        // Exact file hash is definitive even when old manifest entries are missing
        // Minecraft/loader fields. This is the same binary already on disk.
        if (!wantedSha1.isEmpty()) {
            for (JSONObject entry : installed) {
                if (entry == null) continue;
                String installedSha1 = normalizeSha1(entry.optString("sha1", ""));
                if (!installedSha1.isEmpty() && wantedSha1.equals(installedSha1)) {
                    return new Match(entry, ModManagerManifest.getSource(entry), "SHA-1");
                }
            }
        }

        for (JSONObject entry : installed) {
            if (entry == null) continue;
            ModManagerSource installedSource = ModManagerManifest.getSource(entry);

            // Same-store projects already have authoritative project-id matching in
            // each installer. The fuzzy identity rules below are intentionally only
            // used across stores (or for manually tracked content).
            if (installedSource == requestedSource) continue;
            if (!isTargetCompatible(entry, contentType, minecraftVersion, loader)) continue;

            String installedFileName = normalizeFileName(entry.optString("fileName", ""));
            boolean sameVersion = sameRequestedVersion(entry, requestedVersion);
            if (sameVersion && !wantedFileName.isEmpty() && wantedFileName.equals(installedFileName)) {
                return new Match(entry, installedSource, "filename + version");
            }

            String installedSlug = identityKey(entry.optString("slug", ""));
            String installedTitle = identityKey(entry.optString("title", ""));

            // A title/slug proves project identity, not that an older bundled jar
            // satisfies the selected dependency version. Only reuse fuzzy project
            // matches when their version also matches; otherwise the installer must
            // replace the old jar rather than leaving two versions side by side.
            if (sameVersion && sameUsefulIdentity(wantedSlug, installedSlug)) {
                return new Match(entry, installedSource, "project slug + version");
            }
            if (sameVersion && sameUsefulIdentity(wantedTitle, installedTitle)) {
                return new Match(entry, installedSource, "project title + version");
            }
            if (sameVersion && (sameUsefulIdentity(wantedSlug, installedTitle)
                    || sameUsefulIdentity(wantedTitle, installedSlug))) {
                return new Match(entry, installedSource, "project identity + version");
            }
        }

        return null;
    }

    private static boolean isTargetCompatible(
            @NonNull JSONObject entry,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) {
        String installedMinecraft = entry.optString("minecraftVersion", "").trim();
        String wantedMinecraft = minecraftVersion.trim();
        // Known mismatches are never reusable. Missing target metadata is allowed for
        // legacy/migrated entries only when the strong project/file identity checks
        // in this matcher succeed and the tracked file is still present on disk.
        if (!installedMinecraft.isEmpty() && !installedMinecraft.equals(wantedMinecraft)) return false;

        if (!contentType.isLoaderSpecific()) return true;
        String wantedLoader = ModrinthApiClient.normalizeLoader(loader);
        if (wantedLoader.isEmpty() || "vanilla".equals(wantedLoader)) return true;

        String installedLoader = ModrinthApiClient.normalizeLoader(entry.optString("loader", ""));
        return installedLoader.isEmpty() || wantedLoader.equals(installedLoader);
    }


    private static boolean sameRequestedVersion(
            @NonNull JSONObject entry,
            @NonNull ModrinthVersion requestedVersion
    ) {
        String installedVersion = entry.optString("versionNumber", "").trim();
        String requested = requestedVersion.versionNumber == null ? "" : requestedVersion.versionNumber.trim();
        return !installedVersion.isEmpty()
                && !requested.isEmpty()
                && installedVersion.equalsIgnoreCase(requested);
    }

    private static boolean sameUsefulIdentity(@NonNull String a, @NonNull String b) {
        // Tiny names such as "api" or "lib" are too collision-prone for a fuzzy
        // cross-store match. Exact SHA/file-name checks above still handle them.
        return a.length() >= 4 && a.equals(b);
    }

    @NonNull
    private static String normalizeSha1(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        return normalized.matches("[0-9a-f]{40}") ? normalized : "";
    }

    @NonNull
    private static String normalizeFileName(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.endsWith(".disabled")) {
            normalized = normalized.substring(0, normalized.length() - ".disabled".length());
        }
        return normalized;
    }

    @NonNull
    private static String identityKey(@Nullable String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.US);
        if (lower.isEmpty()) return "";
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
