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
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.security.MessageDigest;

public final class CurseForgeInstallManager {
    private static final ModManagerSource SOURCE = ModManagerSource.CURSEFORGE;

    private CurseForgeInstallManager() {
    }

    public static void installLatestCompatible(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        installLatestCompatible(api, gameDirectory, minecraftVersion, loader, contentType, project, null, listener);
    }

    public static void installLatestCompatible(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @Nullable File targetDirectoryOverride,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        try {
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    public static void installSpecificVersion(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        installSpecificVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, null, listener);
    }

    public static void installSpecificVersion(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @Nullable File targetDirectoryOverride,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        try {
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + " " + version.versionNumber + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    private static void installProject(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            boolean dependency,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull ModrinthInstallManager.Listener listener
    ) throws Exception {
        String projectKey = project.projectId == null ? "" : project.projectId.trim();
        if (projectKey.isEmpty()) projectKey = project.title;
        if (!installingProjects.add(projectKey)) {
            listener.onStatus("CurseForge dependency cycle already being resolved: " + project.title);
            return;
        }

        try {
            listener.onStatus((dependency ? "Installing CurseForge dependency " : "Finding CurseForge version for ") + project.title + "...");
            ArrayList<ModrinthVersion> versions = api.getProjectVersions(project.projectId, contentType, minecraftVersion, loader);
            ModrinthVersion selected = firstCompatibleVersion(versions, minecraftVersion, loader, contentType);
            if (selected == null && dependency) {
                // CurseForge occasionally returns no rows when gameVersion and
                // modLoaderType are combined even though an exact compatible file
                // exists. Retry with relaxed server-side filters, then enforce both
                // Minecraft version and loader locally before considering the
                // dependency unresolved.
                selected = findCompatibleDependencyVersionWithRelaxedApiFilters(
                        api, project, minecraftVersion, loader, contentType);
                if (selected != null) {
                    listener.onStatus("Recovered compatible CurseForge dependency file for "
                            + project.title + " using local Minecraft/loader validation.");
                }
            }
            if (selected == null && dependency) {
                // CurseForge files can carry required relations that apply only to
                // another loader build of the same parent release. Do not let a
                // Forge/NeoForge-only relation block an otherwise-compatible Fabric
                // parent (or the reverse), but skip only when the metadata proves it.
                if (isClearlyCrossLoaderDependency(api, project, minecraftVersion, loader, contentType)) {
                    listener.onStatus("Ignoring cross-loader CurseForge dependency " + project.title + " for "
                            + safeLoader(loader) + " on Minecraft " + minecraftVersion
                            + "; its available matching files target a different loader.");
                    return;
                }

                // The CurseForge relation contains only a project id, not a pinned
                // dependency file. Prefer an exact Minecraft/loader match from
                // Modrinth before falling back to an older already-installed file.
                // This keeps dependency recovery strict while avoiding same-store API
                // metadata gaps from aborting an otherwise valid parent install.
                if (installEquivalentModrinthDependency(
                        gameDirectory, minecraftVersion, loader, contentType, project,
                        targetDirectoryOverride, listener)) {
                    return;
                }

                // If neither catalogue currently exposes an exact match, keep an
                // already-installed dependency for the same loader rather than
                // downloading an arbitrary older file. This preserves the existing
                // stale-tag tolerance without weakening fresh dependency selection.
                JSONObject installed = getInstalledSameLoaderEntry(
                        gameDirectory, contentType, project.projectId, loader);
                if (installed != null) {
                    String installedMinecraft = installed.optString("minecraftVersion", "").trim();
                    String installedVersion = installed.optString("versionNumber", "").trim();
                    listener.onStatus("Keeping already-installed CurseForge dependency " + project.title
                            + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                            + " for " + safeLoader(loader)
                            + (installedMinecraft.isEmpty() ? "" : " (tagged for Minecraft " + installedMinecraft + ")")
                            + "; no exact " + minecraftVersion + " file is currently available from either catalogue.");
                    return;
                }
            }
            if (selected == null) {
                throw new IllegalStateException("No compatible CurseForge file found for " + project.title
                        + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");
            }

            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, selected, dependency, targetDirectoryOverride, installingProjects, installingVersions, listener);
        } finally {
            installingProjects.remove(projectKey);
        }
    }

    private static void installVersion(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            boolean dependency,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull ModrinthInstallManager.Listener listener
    ) throws Exception {
        String versionKey = project.projectId + ":" + version.id;
        if (!installingVersions.add(versionKey)) return;

        try {
        if (dependency && ModManagerManifest.isExactProjectVersionInstalled(
                gameDirectory, contentType, SOURCE, project.projectId, version, minecraftVersion, loader)) {
            listener.onStatus("Selected CurseForge dependency version already installed: "
                    + project.title + " " + version.versionNumber);
            return;
        }

        if (dependency) {
            CrossStoreDependencyMatcher.Match crossStoreMatch = CrossStoreDependencyMatcher.findInstalledEquivalent(
                    gameDirectory, contentType, SOURCE, project, version, minecraftVersion, loader);
            if (crossStoreMatch != null) {
                listener.onStatus(crossStoreMatch.describe(project));
                return;
            }

        }

        if (contentType.supportsDependencies()) {
            for (ModrinthDependency dep : version.dependencies) {
                if (!dep.isRequired()) continue;
                installDependency(api, gameDirectory, minecraftVersion, loader, contentType, project, dep,
                        targetDirectoryOverride, installingProjects, installingVersions, listener);
            }
        }

        // Resolve modpack-installed Modrinth identity only after this version's
        // required dependencies have succeeded. This prevents an unrelated dependency
        // failure from deleting the user's existing older jar during an upgrade.
        if (handleInstalledEquivalentModrinthProject(
                gameDirectory, minecraftVersion, loader, contentType, project, version, dependency, listener)) {
            return;
        }

        ModrinthFile file = version.getPrimaryFile();
        if (file == null || file.url.trim().isEmpty()) {
            throw new IllegalStateException("No downloadable CurseForge file found for " + project.title + " " + version.versionNumber + ".");
        }

        File targetDirectory = targetDirectoryOverride != null
                ? targetDirectoryOverride
                : contentType.getTargetDirectory(gameDirectory, minecraftVersion);
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + targetDirectory.getAbsolutePath());
        }

        ModManagerManifest.removeKnownFilesForProject(gameDirectory, contentType, SOURCE, project.projectId);

        File target = uniqueTargetFile(targetDirectory, sanitizeFileName(file.filename));
        listener.onStatus("Downloading " + project.title + " " + version.versionNumber + " from CurseForge...");
        try {
            api.downloadToFile(file.url, target);
            verifyDownloadedFile(target, file);
        } catch (Throwable downloadFailure) {
            if (target.exists() && !target.delete()) {
                // A later retry uses a unique target name, so leaving the partial file
                // behind is safe but worth preserving in the original exception path.
            }
            if (downloadFailure instanceof Exception) throw (Exception) downloadFailure;
            throw new IllegalStateException("CurseForge download failed for " + project.title, downloadFailure);
        }

        File cachedIconFile = cacheProjectIcon(api, gameDirectory, project);
        ModManagerManifest.recordInstalled(
                gameDirectory,
                contentType,
                SOURCE,
                project,
                version,
                file,
                target,
                dependency,
                minecraftVersion,
                loader,
                project.iconUrl,
                cachedIconFile
        );
        } finally {
            installingVersions.remove(versionKey);
        }
    }

    private static void installDependency(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject parentProject,
            @NonNull ModrinthDependency dep,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull ModrinthInstallManager.Listener listener
    ) throws Exception {
        if (dep.projectId == null || dep.projectId.trim().isEmpty() || "0".equals(dep.projectId.trim())) {
            throw new IllegalStateException("Required CurseForge dependency is missing a valid project id.");
        }

        String dependencyProjectId = dep.projectId.trim();
        ModrinthProject project = api.getProject(dependencyProjectId);

        if (ModDependencyCompatibility.shouldSkipRequiredDependency(parentProject, project, minecraftVersion)) {
            listener.onStatus(ModDependencyCompatibility.skippedDependencyStatus(project, minecraftVersion));
            return;
        }

        installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, true, targetDirectoryOverride, installingProjects, installingVersions, listener);
    }


    @Nullable
    private static ModrinthVersion findCompatibleDependencyVersionWithRelaxedApiFilters(
            @NonNull CurseForgeApiClient api,
            @NonNull ModrinthProject project,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        ArrayList<ModrinthVersion> probe;
        ModrinthVersion selected;

        // Keep the exact Minecraft filter but remove the server loader filter.
        try {
            probe = api.getProjectVersions(project.projectId, contentType, minecraftVersion, null);
            selected = firstCompatibleVersion(probe, minecraftVersion, loader, contentType);
            if (selected != null) return selected;
        } catch (Throwable ignored) {
        }

        // Keep the loader filter but remove the server Minecraft filter. Local
        // validation below still requires the requested Minecraft version.
        try {
            probe = api.getProjectVersions(project.projectId, contentType, "", loader);
            selected = firstCompatibleVersion(probe, minecraftVersion, loader, contentType);
            if (selected != null) return selected;
        } catch (Throwable ignored) {
        }

        // Last same-store probe: let CurseForge return its first page unfiltered and
        // accept only a file whose own metadata passes both local checks.
        try {
            probe = api.getProjectVersions(project.projectId, contentType, "", null);
            return firstCompatibleVersion(probe, minecraftVersion, loader, contentType);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean handleInstalledEquivalentModrinthProject(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject curseForgeProject,
            @NonNull ModrinthVersion requestedVersion,
            boolean dependency,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        try {
            if (!hasPotentialInstalledModrinthCandidate(
                    gameDirectory, minecraftVersion, loader, contentType)) {
                return false;
            }

            ModrinthApiClient modrinthApi = new ModrinthApiClient();
            ModrinthApiClient.SearchResult result = modrinthApi.searchProjects(
                    curseForgeProject.title,
                    contentType,
                    minecraftVersion,
                    loader,
                    20,
                    0,
                    "relevance"
            );
            ModrinthProject equivalent = findEquivalentProject(curseForgeProject, result.hits);
            if (equivalent == null) return false;

            JSONObject installed = ModManagerManifest.getInstalledEntryForProject(
                    gameDirectory, contentType, ModManagerSource.MODRINTH, equivalent.projectId);
            if (installed == null || !installedEntryCanSatisfyTarget(
                    installed, minecraftVersion, loader, contentType)) {
                return false;
            }

            String installedVersion = installed.optString("versionNumber", "").trim();
            if (dependency && installedEntryMatchesRequestedVersion(installed, requestedVersion)) {
                listener.onStatus("Existing Modrinth install already satisfies dependency "
                        + equivalent.title
                        + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                        + " (matched by exact Modrinth project identity + selected version); skipping duplicate CurseForge download/tracking.");
                return true;
            }

            ModManagerManifest.removeKnownFilesForProject(
                    gameDirectory, contentType, ModManagerSource.MODRINTH, equivalent.projectId);
            listener.onStatus("Replacing existing Modrinth " + equivalent.title
                    + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                    + " with CurseForge " + requestedVersion.versionNumber
                    + "; removed the old file before download.");
            return false;
        } catch (Throwable ignored) {
            // Cross-store cleanup is best-effort. The normal same-store replacement
            // still runs, and a catalogue lookup failure must not abort installation.
            return false;
        }
    }

    private static boolean installedEntryMatchesRequestedVersion(
            @NonNull JSONObject installed,
            @NonNull ModrinthVersion requestedVersion
    ) {
        ModrinthFile requestedFile = requestedVersion.getPrimaryFile();
        String requestedSha1 = requestedFile == null || requestedFile.sha1 == null
                ? "" : requestedFile.sha1.trim();
        String installedSha1 = installed.optString("sha1", "").trim();
        if (!requestedSha1.isEmpty() && !installedSha1.isEmpty()
                && requestedSha1.equalsIgnoreCase(installedSha1)) {
            return true;
        }

        String installedVersion = installed.optString("versionNumber", "").trim();
        String requested = requestedVersion.versionNumber == null ? "" : requestedVersion.versionNumber.trim();
        return !installedVersion.isEmpty()
                && !requested.isEmpty()
                && installedVersion.equalsIgnoreCase(requested);
    }

    private static boolean hasPotentialInstalledModrinthCandidate(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        for (JSONObject entry : ModManagerManifest.getInstalledEntriesIncludingModpack(gameDirectory, contentType)) {
            if (entry == null || ModManagerManifest.getSource(entry) != ModManagerSource.MODRINTH) continue;
            if (installedEntryCanSatisfyTarget(entry, minecraftVersion, loader, contentType)) return true;
        }
        return false;
    }

    private static boolean installedEntryCanSatisfyTarget(
            @NonNull JSONObject entry,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        String installedMinecraft = entry.optString("minecraftVersion", "").trim();
        if (!installedMinecraft.isEmpty() && !installedMinecraft.equals(minecraftVersion.trim())) return false;

        if (!contentType.isLoaderSpecific()) return true;
        String wantedLoader = ModrinthApiClient.normalizeLoader(loader);
        if (wantedLoader.isEmpty() || "vanilla".equals(wantedLoader)) return true;

        String installedLoader = ModrinthApiClient.normalizeLoader(entry.optString("loader", ""));
        return installedLoader.isEmpty() || wantedLoader.equals(installedLoader);
    }

    private static boolean installEquivalentModrinthDependency(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject curseForgeProject,
            @Nullable File targetDirectoryOverride,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        try {
            ModrinthApiClient modrinthApi = new ModrinthApiClient();
            listener.onStatus("No compatible CurseForge file found for dependency "
                    + curseForgeProject.title + "; checking Modrinth for the same dependency...");

            ModrinthApiClient.SearchResult result = modrinthApi.searchProjects(
                    curseForgeProject.title,
                    contentType,
                    minecraftVersion,
                    loader,
                    20,
                    0,
                    "relevance"
            );

            ModrinthProject equivalent = findEquivalentProject(curseForgeProject, result.hits);
            if (equivalent == null) {
                listener.onStatus("No exact Modrinth project match found for CurseForge dependency "
                        + curseForgeProject.title + ".");
                return false;
            }

            // Fetch the full project payload before installation so slug/title and
            // version fallback metadata are complete rather than relying on a search row.
            equivalent = modrinthApi.getProjectWithFallback(equivalent.projectId, equivalent.slug);
            listener.onStatus("Using Modrinth fallback for required dependency "
                    + equivalent.title + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");

            ModrinthInstallManager.installDependencyFallback(
                    modrinthApi,
                    gameDirectory,
                    minecraftVersion,
                    loader,
                    contentType,
                    equivalent,
                    targetDirectoryOverride,
                    listener
            );
            return true;
        } catch (Throwable fallbackFailure) {
            listener.onStatus("Modrinth fallback could not resolve CurseForge dependency "
                    + curseForgeProject.title + ": " + safeMessage(fallbackFailure));
            return false;
        }
    }

    @Nullable
    private static ModrinthProject findEquivalentProject(
            @NonNull ModrinthProject requested,
            @NonNull ArrayList<ModrinthProject> candidates
    ) {
        String requestedSlug = identityKey(requested.slug);
        String requestedTitle = identityKey(requested.title);

        // A shared non-trivial slug is the strongest catalogue-independent signal.
        if (requestedSlug.length() >= 4) {
            for (ModrinthProject candidate : candidates) {
                if (candidate == null) continue;
                if (requestedSlug.equals(identityKey(candidate.slug))) return candidate;
            }
        }

        // CurseForge and Modrinth frequently use different slugs for the same project,
        // but their public titles normally match. Require a useful exact normalized
        // title rather than accepting fuzzy search relevance and risking a wrong mod.
        if (requestedTitle.length() >= 4) {
            for (ModrinthProject candidate : candidates) {
                if (candidate == null) continue;
                if (requestedTitle.equals(identityKey(candidate.title))) return candidate;
            }
        }
        return null;
    }

    @NonNull
    private static String identityKey(@Nullable String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) return throwable.getClass().getSimpleName();
        return message.trim();
    }

    private static void verifyDownloadedFile(
            @NonNull File target,
            @NonNull ModrinthFile metadata
    ) throws Exception {
        if (!target.isFile() || target.length() <= 0L) {
            throw new IllegalStateException("CurseForge download produced an empty file: " + target.getName());
        }

        if (metadata.size > 0L && target.length() != metadata.size) {
            throw new IllegalStateException("CurseForge download size mismatch for " + target.getName()
                    + ": expected=" + metadata.size + " actual=" + target.length());
        }

        if (metadata.sha1 == null || metadata.sha1.trim().isEmpty()) return;
        String actual = sha1Hex(target);
        if (!metadata.sha1.equalsIgnoreCase(actual)) {
            throw new SecurityException("CurseForge SHA-1 mismatch for " + target.getName()
                    + ": expected=" + metadata.sha1 + " actual=" + actual);
        }
    }

    @NonNull
    private static String sha1Hex(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder out = new StringBuilder(40);
        for (byte b : digest.digest()) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }

    @Nullable
    private static ModrinthVersion firstCompatibleVersion(
            @NonNull ArrayList<ModrinthVersion> versions,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        for (ModrinthVersion candidate : versions) {
            if (isVersionCompatible(candidate, minecraftVersion, loader, contentType)) return candidate;
        }
        return null;
    }

    private static boolean isVersionCompatible(
            @NonNull ModrinthVersion version,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        boolean hasMinecraftVersionMetadata = false;
        boolean minecraftMatches = false;
        for (String candidate : version.gameVersions) {
            String value = candidate == null ? "" : candidate.trim();
            if (!looksLikeMinecraftVersion(value)) continue;
            hasMinecraftVersionMetadata = true;
            if (minecraftVersion.equals(value)) minecraftMatches = true;
        }
        if (hasMinecraftVersionMetadata && !minecraftMatches) return false;
        if (!contentType.isLoaderSpecific()) return true;

        String wanted = ModrinthApiClient.normalizeLoader(loader);
        if (wanted.isEmpty() || "vanilla".equals(wanted)) return true;

        boolean hasLoaderMetadata = false;
        for (String candidate : version.loaders) {
            String normalized = ModrinthApiClient.normalizeLoader(candidate);
            if (normalized.isEmpty()) continue;
            hasLoaderMetadata = true;
            if (wanted.equals(normalized)) return true;
        }
        // CurseForge also exposes loader labels in gameVersions for some older files.
        for (String candidate : version.gameVersions) {
            String normalized = ModrinthApiClient.normalizeLoader(candidate);
            if (!isKnownLoader(normalized)) continue;
            hasLoaderMetadata = true;
            if (wanted.equals(normalized)) return true;
        }
        // The server-side modLoaderType filter is authoritative when a file omits loader metadata.
        return !hasLoaderMetadata;
    }

    private static boolean isClearlyCrossLoaderDependency(
            @NonNull CurseForgeApiClient api,
            @NonNull ModrinthProject project,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        if (!contentType.isLoaderSpecific()) return false;
        String wanted = ModrinthApiClient.normalizeLoader(loader);
        if (wanted.isEmpty() || "vanilla".equals(wanted)) return false;

        // First inspect files for this exact Minecraft version without a loader
        // filter. This is the strongest evidence: if CurseForge has 26.3 files
        // and every one explicitly says Forge/NeoForge while the instance is
        // Fabric, the required relation cannot apply to this parent build.
        try {
            ArrayList<ModrinthVersion> sameMinecraftAnyLoader = api.getProjectVersions(
                    project.projectId, contentType, minecraftVersion, null);
            if (!sameMinecraftAnyLoader.isEmpty()) {
                boolean sawDeclaredLoader = false;
                boolean sawUnknownLoader = false;
                for (ModrinthVersion candidate : sameMinecraftAnyLoader) {
                    boolean declaredForCandidate = false;
                    for (String rawLoader : candidate.loaders) {
                        String normalized = ModrinthApiClient.normalizeLoader(rawLoader);
                        if (!isKnownLoader(normalized)) continue;
                        declaredForCandidate = true;
                        sawDeclaredLoader = true;
                        if (wanted.equals(normalized)) return false;
                    }
                    if (!declaredForCandidate) sawUnknownLoader = true;
                }
                if (sawDeclaredLoader && !sawUnknownLoader) return true;
            }
        } catch (Throwable ignored) {
            // Fall through to project index metadata. A failed probe must never
            // turn a required dependency into an automatic skip.
        }

        // When there is no file for the exact Minecraft version, CurseForge's
        // latestFilesIndexes still tells us which loaders this project actually
        // publishes for. Treat that as conclusive only when every indexed loader
        // is known and none matches the requested loader.
        boolean sawProjectLoader = false;
        for (String rawLoader : project.loaders) {
            String normalized = ModrinthApiClient.normalizeLoader(rawLoader);
            if (!isKnownLoader(normalized)) continue;
            sawProjectLoader = true;
            if (wanted.equals(normalized)) return false;
        }
        return sawProjectLoader;
    }

    @Nullable
    private static JSONObject getInstalledSameLoaderEntry(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType contentType,
            @NonNull String projectId,
            @Nullable String loader
    ) {
        JSONObject entry = ModManagerManifest.getInstalledEntryForProject(
                gameDirectory, contentType, SOURCE, projectId);
        if (entry == null) return null;

        if (!contentType.isLoaderSpecific()) return entry;
        String wantedLoader = ModrinthApiClient.normalizeLoader(loader);
        if (wantedLoader.isEmpty() || "vanilla".equals(wantedLoader)) return entry;

        String installedLoader = ModrinthApiClient.normalizeLoader(entry.optString("loader", ""));
        if (installedLoader.isEmpty() || !wantedLoader.equals(installedLoader)) return null;
        return entry;
    }

    private static boolean isKnownLoader(@Nullable String value) {
        return "forge".equals(value) || "fabric".equals(value) || "quilt".equals(value) || "neoforge".equals(value);
    }

    private static boolean looksLikeMinecraftVersion(@Nullable String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+._A-Za-z0-9]*)?");
    }

    @Nullable
    private static File cacheProjectIcon(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull ModrinthProject project
    ) {
        if (project.iconUrl == null || project.iconUrl.trim().isEmpty() || project.projectId.trim().isEmpty()) return null;

        try {
            File iconDirectory = DroidBridgeMetadataPaths.childDirectoryForWrite(gameDirectory, DroidBridgeMetadataPaths.MOD_MANAGER_ICONS_DIRECTORY);
            if (!iconDirectory.exists() && !iconDirectory.mkdirs()) return null;

            File iconFile = new File(iconDirectory, "curseforge-" + sanitizeFileName(project.projectId) + ".img");
            api.downloadToFile(project.iconUrl, iconFile);
            return iconFile.isFile() ? iconFile : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static File uniqueTargetFile(@NonNull File directory, @NonNull String fileName) {
        File target = new File(directory, fileName);
        if (!target.exists()) return target;

        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        for (int i = 2; i < 1000; i++) {
            File candidate = new File(directory, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = "download.jar";
        return name;
    }

    @NonNull
    private static String safeLoader(@Nullable String loader) {
        return loader == null || loader.trim().isEmpty() ? "unknown loader" : loader.trim().toLowerCase(Locale.US);
    }
}
