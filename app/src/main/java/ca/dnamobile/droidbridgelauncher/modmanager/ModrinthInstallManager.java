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
import java.util.HashSet;
import java.util.Locale;

public final class ModrinthInstallManager {
    private static final ModManagerSource SOURCE = ModManagerSource.MODRINTH;

    public interface Listener {
        void onStatus(@NonNull String message);
        void onComplete(@NonNull String message);
        void onError(@NonNull Throwable throwable);
    }

    private ModrinthInstallManager() {
    }

    public static void installLatestCompatible(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull Listener listener
    ) {
        installLatestCompatible(gameDirectory, minecraftVersion, loader, contentType, project, null, listener);
    }

    public static void installLatestCompatible(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @Nullable File targetDirectoryOverride,
            @NonNull Listener listener
    ) {
        try {
            ModrinthApiClient api = new ModrinthApiClient();
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    public static void installSpecificVersion(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull Listener listener
    ) {
        installSpecificVersion(gameDirectory, minecraftVersion, loader, contentType, project, version, null, listener);
    }

    public static void installSpecificVersion(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @Nullable File targetDirectoryOverride,
            @NonNull Listener listener
    ) {
        try {
            ModrinthApiClient api = new ModrinthApiClient();
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + " " + version.versionNumber + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    /**
     * Install a project as a required dependency without emitting a top-level
     * completion callback. This is used when another catalogue resolves an
     * equivalent dependency on Modrinth after its own catalogue has no exact
     * Minecraft/loader file.
     */
    static void installDependencyFallback(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @Nullable File targetDirectoryOverride,
            @NonNull Listener listener
    ) throws Exception {
        HashSet<String> installingProjects = new HashSet<>();
        HashSet<String> installingVersions = new HashSet<>();
        installProject(
                api,
                gameDirectory,
                minecraftVersion,
                loader,
                contentType,
                project,
                true,
                targetDirectoryOverride,
                installingProjects,
                installingVersions,
                listener
        );
    }

    private static void installProject(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            boolean dependency,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        String projectKey = project.projectId == null ? "" : project.projectId.trim();
        if (projectKey.isEmpty()) projectKey = project.slug == null ? project.title : project.slug.trim();
        if (!installingProjects.add(projectKey)) {
            listener.onStatus("Dependency cycle already being resolved: " + project.title);
            return;
        }

        try {
            listener.onStatus((dependency ? "Installing dependency " : "Finding version for ") + project.title + "...");
            ArrayList<ModrinthVersion> versions = api.getProjectVersionsWithFallback(
                    project,
                    contentType,
                    minecraftVersion,
                    loader,
                    false
            );

            ModrinthVersion selected = firstCompatibleVersion(versions, minecraftVersion, loader, contentType);
            if (selected == null) {
                throw new IllegalStateException("No compatible Modrinth version found for " + project.title
                        + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");
            }

            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, selected, dependency, targetDirectoryOverride, installingProjects, installingVersions, listener);
        } finally {
            installingProjects.remove(projectKey);
        }
    }

    private static void installVersion(
            @NonNull ModrinthApiClient api,
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
            @NonNull Listener listener
    ) throws Exception {
        if (!installingVersions.add(version.id)) return;

        try {
            if (dependency && ModManagerManifest.isExactProjectVersionInstalled(
                    gameDirectory, contentType, SOURCE, project.projectId, version, minecraftVersion, loader)) {
                listener.onStatus("Selected Modrinth dependency version already installed: "
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

            // Resolve modpack-installed CurseForge identity only after this version's
            // required dependencies have succeeded. This avoids deleting the existing
            // jar if another required dependency fails before the replacement download.
            if (handleInstalledEquivalentCurseForgeProject(
                    gameDirectory, minecraftVersion, loader, contentType, project, version, dependency, listener)) {
                return;
            }

            ModrinthFile file = version.getPrimaryFile();
            if (file == null || file.url.trim().isEmpty()) {
                throw new IllegalStateException("No downloadable file found for " + project.title + " " + version.versionNumber + ".");
            }

            File targetDirectory = targetDirectoryOverride != null
                    ? targetDirectoryOverride
                    : contentType.getTargetDirectory(gameDirectory, minecraftVersion);
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create folder: " + targetDirectory.getAbsolutePath());
            }

            ModManagerManifest.removeKnownFilesForProject(gameDirectory, contentType, SOURCE, project.projectId);

            File target = uniqueTargetFile(targetDirectory, sanitizeFileName(file.filename));
            listener.onStatus("Downloading " + project.title + " " + version.versionNumber + "...");
            api.downloadToFile(file.url, target);

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
            installingVersions.remove(version.id);
        }
    }

    private static void installDependency(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject parentProject,
            @NonNull ModrinthDependency dep,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        Throwable exactFailure = null;
        String resolvedDependencyProjectId = dep.projectId == null ? null : dep.projectId.trim();
        if (resolvedDependencyProjectId != null && resolvedDependencyProjectId.isEmpty()) {
            resolvedDependencyProjectId = null;
        }

        // An exact dependency link is the strongest signal the publisher can give us.
        // Trust it across stale Minecraft-version tags as long as the dependency still
        // targets this loader. A Forge dependency is never installed into Fabric (or
        // vice versa); that falls through to the project-level loader-aware resolver.
        if (dep.versionId != null && !dep.versionId.trim().isEmpty()) {
            try {
                ModrinthVersion version = api.getVersion(dep.versionId.trim());
                ModrinthProject project = api.getProject(version.projectId);
                if (resolvedDependencyProjectId == null && !project.projectId.trim().isEmpty()) {
                    resolvedDependencyProjectId = project.projectId.trim();
                }
                if (ModDependencyCompatibility.shouldSkipRequiredDependency(parentProject, project, minecraftVersion)) {
                    listener.onStatus(ModDependencyCompatibility.skippedDependencyStatus(project, minecraftVersion));
                    return;
                }

                if (!isVersionLoaderCompatible(version, loader, contentType)) {
                    throw new IllegalStateException("Exact dependency version targets loader(s) "
                            + describeLoaders(version) + ", not " + safeLoader(loader) + ".");
                }

                if (!isVersionMinecraftCompatible(version, minecraftVersion)) {
                    listener.onStatus("Using publisher-pinned dependency " + project.title + " "
                            + version.versionNumber + " even though its Modrinth Minecraft tags do not include "
                            + minecraftVersion + "; the selected " + parentProject.title
                            + " version explicitly requires it.");
                }

                installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, true,
                        targetDirectoryOverride, installingProjects, installingVersions, listener);
                return;
            } catch (Throwable throwable) {
                exactFailure = throwable;
                listener.onStatus("Exact dependency version unavailable or for another loader; trying a compatible project release...");
            }
        }

        if (resolvedDependencyProjectId != null) {
            String dependencyProjectId = resolvedDependencyProjectId;

            try {
                ModrinthProject project = api.getProject(dependencyProjectId);
                if (ModDependencyCompatibility.shouldSkipRequiredDependency(parentProject, project, minecraftVersion)) {
                    listener.onStatus(ModDependencyCompatibility.skippedDependencyStatus(project, minecraftVersion));
                    return;
                }
                listener.onStatus("Installing dependency " + project.title + "...");
                ArrayList<ModrinthVersion> versions = api.getProjectVersionsWithFallback(
                        project,
                        contentType,
                        minecraftVersion,
                        loader,
                        false
                );
                ModrinthVersion selected = firstCompatibleVersion(versions, minecraftVersion, loader, contentType);
                if (selected != null) {
                    installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, selected, true,
                            targetDirectoryOverride, installingProjects, installingVersions, listener);
                    return;
                }

                // Some multi-loader releases incorrectly publish all required dependency
                // relations on every loader build. If this dependency has releases for
                // the requested Minecraft version, but every one of them explicitly
                // targets another loader, it cannot be a valid dependency for this
                // instance. Skip that stale cross-loader relation instead of aborting the
                // otherwise-compatible parent mod.
                if (isClearlyCrossLoaderDependency(api, project, minecraftVersion, loader, contentType)) {
                    listener.onStatus("Ignoring cross-loader dependency " + project.title + " for "
                            + safeLoader(loader) + " on Minecraft " + minecraftVersion
                            + "; its matching Minecraft releases target a different loader.");
                    return;
                }

                // If the dependency project has not published a new Minecraft tag yet,
                // keep an already-installed dependency for the same loader. This is much
                // safer than downloading an arbitrary release for another game version,
                // and avoids blocking parent updates solely because dependency metadata
                // is lagging behind.
                JSONObject installed = getInstalledSameLoaderEntry(
                        gameDirectory, contentType, dependencyProjectId, loader);
                if (installed != null) {
                    String installedMinecraft = installed.optString("minecraftVersion", "").trim();
                    String installedVersion = installed.optString("versionNumber", "").trim();
                    listener.onStatus("Keeping already-installed dependency " + project.title
                            + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                            + " for " + safeLoader(loader)
                            + (installedMinecraft.isEmpty() ? "" : " (tagged for Minecraft " + installedMinecraft + ")")
                            + "; no " + minecraftVersion + " release is currently tagged on Modrinth.");
                    return;
                }

                throw new IllegalStateException(
                        "No compatible Modrinth version found for " + project.title
                                + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");
            } catch (Throwable projectFailure) {
                if (exactFailure != null && projectFailure != exactFailure) projectFailure.addSuppressed(exactFailure);
                if (projectFailure instanceof Exception) throw (Exception) projectFailure;
                throw new IllegalStateException("Unable to resolve required Modrinth dependency " + dependencyProjectId, projectFailure);
            }
        }

        if (dep.fileName != null && !dep.fileName.trim().isEmpty()) {
            File targetDirectory = targetDirectoryOverride != null
                    ? targetDirectoryOverride
                    : contentType.getTargetDirectory(gameDirectory, minecraftVersion);
            File local = new File(targetDirectory, sanitizeFileName(dep.fileName));
            if (local.isFile() && local.length() > 0L) {
                listener.onStatus("Filename-only dependency already present: " + dep.fileName);
                return;
            }
            throw new IllegalStateException("Required Modrinth dependency only supplied file_name='"
                    + dep.fileName + "' with no project_id/version_id, and that file is not installed.");
        }

        if (exactFailure != null) {
            if (exactFailure instanceof Exception) throw (Exception) exactFailure;
            throw new IllegalStateException("Unable to resolve required Modrinth dependency", exactFailure);
        }
        throw new IllegalStateException("Required Modrinth dependency has no project_id, version_id, or file_name.");
    }

    private static boolean isVersionMinecraftCompatible(
            @NonNull ModrinthVersion version,
            @NonNull String minecraftVersion
    ) {
        return version.gameVersions.isEmpty() || version.gameVersions.contains(minecraftVersion);
    }

    private static boolean isVersionLoaderCompatible(
            @NonNull ModrinthVersion version,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        if (!contentType.isLoaderSpecific()) return true;
        String wanted = ModrinthApiClient.normalizeLoader(loader);
        if (wanted.isEmpty() || "vanilla".equals(wanted) || version.loaders.isEmpty()) return true;
        for (String candidate : version.loaders) {
            if (wanted.equals(ModrinthApiClient.normalizeLoader(candidate))) return true;
        }
        return false;
    }

    private static boolean isClearlyCrossLoaderDependency(
            @NonNull ModrinthApiClient api,
            @NonNull ModrinthProject project,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        if (!contentType.isLoaderSpecific()) return false;
        String wanted = ModrinthApiClient.normalizeLoader(loader);
        if (wanted.isEmpty() || "vanilla".equals(wanted)) return false;

        // The project-level loader list is the strongest cheap signal and also
        // works when the other-loader dependency has not published a build for
        // this exact Minecraft version yet.
        boolean projectDeclaresLoader = false;
        for (String rawLoader : project.loaders) {
            String normalized = ModrinthApiClient.normalizeLoader(rawLoader);
            if (normalized.isEmpty()) continue;
            projectDeclaresLoader = true;
            if (wanted.equals(normalized)) return false;
        }
        if (projectDeclaresLoader) return true;

        try {
            ArrayList<ModrinthVersion> sameMinecraftAnyLoader = api.getProjectVersionsWithFallback(
                    project,
                    contentType,
                    minecraftVersion,
                    null,
                    false
            );
            if (sameMinecraftAnyLoader.isEmpty()) return false;

            boolean sawDeclaredLoader = false;
            boolean sawUnknownLoader = false;
            for (ModrinthVersion candidate : sameMinecraftAnyLoader) {
                if (candidate.loaders.isEmpty()) {
                    sawUnknownLoader = true;
                    continue;
                }
                boolean versionDeclaredLoader = false;
                for (String rawLoader : candidate.loaders) {
                    String normalized = ModrinthApiClient.normalizeLoader(rawLoader);
                    if (normalized.isEmpty()) continue;
                    versionDeclaredLoader = true;
                    sawDeclaredLoader = true;
                    if (wanted.equals(normalized)) return false;
                }
                if (!versionDeclaredLoader) sawUnknownLoader = true;
            }

            // Only skip when the evidence is conclusive. Empty/unknown loader metadata
            // is not enough to prove that a required dependency belongs elsewhere.
            return sawDeclaredLoader && !sawUnknownLoader;
        } catch (Throwable ignored) {
            return false;
        }
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

    @NonNull
    private static String describeLoaders(@NonNull ModrinthVersion version) {
        if (version.loaders.isEmpty()) return "unspecified";
        StringBuilder out = new StringBuilder();
        for (String loader : version.loaders) {
            String normalized = ModrinthApiClient.normalizeLoader(loader);
            if (normalized.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(normalized);
        }
        return out.length() == 0 ? "unspecified" : out.toString();
    }

    private static boolean isVersionCompatible(
            @NonNull ModrinthVersion version,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        return isVersionMinecraftCompatible(version, minecraftVersion)
                && isVersionLoaderCompatible(version, loader, contentType);
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

    private static boolean handleInstalledEquivalentCurseForgeProject(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject modrinthProject,
            @NonNull ModrinthVersion requestedVersion,
            boolean dependency,
            @NonNull Listener listener
    ) {
        try {
            if (!hasPotentialInstalledCurseForgeCandidate(
                    gameDirectory, minecraftVersion, loader, contentType)) {
                return false;
            }

            CurseForgeApiClient curseForgeApi = new CurseForgeApiClient();
            CurseForgeApiClient.SearchResult result = curseForgeApi.searchProjects(
                    modrinthProject.title,
                    contentType,
                    minecraftVersion,
                    loader,
                    20,
                    0,
                    "relevance"
            );
            ModrinthProject equivalent = findEquivalentProject(modrinthProject, result.hits);
            if (equivalent == null) return false;

            JSONObject installed = ModManagerManifest.getInstalledEntryForProject(
                    gameDirectory, contentType, ModManagerSource.CURSEFORGE, equivalent.projectId);
            if (installed == null || !installedEntryCanSatisfyTarget(
                    installed, minecraftVersion, loader, contentType)) {
                return false;
            }

            String installedVersion = installed.optString("versionNumber", "").trim();
            if (dependency && installedEntryMatchesRequestedVersion(installed, requestedVersion)) {
                listener.onStatus("Existing CurseForge install already satisfies dependency "
                        + equivalent.title
                        + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                        + " (matched by exact CurseForge project identity + selected version); skipping duplicate Modrinth download/tracking.");
                return true;
            }

            ModManagerManifest.removeKnownFilesForProject(
                    gameDirectory, contentType, ModManagerSource.CURSEFORGE, equivalent.projectId);
            listener.onStatus("Replacing existing CurseForge " + equivalent.title
                    + (installedVersion.isEmpty() ? "" : " " + installedVersion)
                    + " with Modrinth " + requestedVersion.versionNumber
                    + "; removed the old file before download.");
            return false;
        } catch (Throwable ignored) {
            // Cross-store cleanup is best-effort. Same-store replacement still runs.
            return false;
        }
    }

    private static boolean hasPotentialInstalledCurseForgeCandidate(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        for (JSONObject entry : ModManagerManifest.getInstalledEntriesIncludingModpack(gameDirectory, contentType)) {
            if (entry == null || ModManagerManifest.getSource(entry) != ModManagerSource.CURSEFORGE) continue;
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

    @Nullable
    private static ModrinthProject findEquivalentProject(
            @NonNull ModrinthProject requested,
            @NonNull ArrayList<ModrinthProject> candidates
    ) {
        String requestedSlug = identityKey(requested.slug);
        String requestedTitle = identityKey(requested.title);
        if (requestedSlug.length() >= 4) {
            for (ModrinthProject candidate : candidates) {
                if (candidate != null && requestedSlug.equals(identityKey(candidate.slug))) return candidate;
            }
        }
        if (requestedTitle.length() >= 4) {
            for (ModrinthProject candidate : candidates) {
                if (candidate != null && requestedTitle.equals(identityKey(candidate.title))) return candidate;
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

    @Nullable
    private static File cacheProjectIcon(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull ModrinthProject project
    ) {
        if (project.iconUrl == null || project.iconUrl.trim().isEmpty() || project.projectId.trim().isEmpty()) {
            return null;
        }

        try {
            File iconDirectory = DroidBridgeMetadataPaths.childDirectoryForWrite(gameDirectory, DroidBridgeMetadataPaths.MOD_MANAGER_ICONS_DIRECTORY);
            if (!iconDirectory.exists() && !iconDirectory.mkdirs()) return null;

            File iconFile = new File(iconDirectory, sanitizeFileName(project.projectId) + ".img");
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
