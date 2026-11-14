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

package ca.dnamobile.droidbridgelauncher.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.play.core.appupdate.AppUpdateInfo;

/**
 * Immutable result from the Google Play update checker.
 */
public final class LauncherUpdateInfo {
    @NonNull
    public final String latestVersionName;
    public final long latestVersionCode;
    @NonNull
    public final String playStoreUrl;
    public final boolean updateAvailable;
    public final boolean verifiedByPlayCore;
    /**
     * The Play Store's own answer when it reported the update, to run Google Play's update
     * screen with ({@link LauncherUpdateChecker#startPlayUpdate}); null otherwise.
     */
    @Nullable
    public final AppUpdateInfo playUpdateInfo;

    public LauncherUpdateInfo(
            @NonNull String latestVersionName,
            long latestVersionCode,
            @NonNull String playStoreUrl,
            boolean updateAvailable,
            boolean verifiedByPlayCore
    ) {
        this(latestVersionName, latestVersionCode, playStoreUrl, updateAvailable, verifiedByPlayCore, null);
    }

    public LauncherUpdateInfo(
            @NonNull String latestVersionName,
            long latestVersionCode,
            @NonNull String playStoreUrl,
            boolean updateAvailable,
            boolean verifiedByPlayCore,
            @Nullable AppUpdateInfo playUpdateInfo
    ) {
        this.latestVersionName = latestVersionName.trim();
        this.latestVersionCode = latestVersionCode;
        this.playStoreUrl = playStoreUrl;
        this.updateAvailable = updateAvailable;
        this.verifiedByPlayCore = verifiedByPlayCore;
        this.playUpdateInfo = playUpdateInfo;
    }

    @NonNull
    public String getDisplayVersion() {
        if (!latestVersionName.isEmpty()) return latestVersionName;
        if (latestVersionCode > 0L) return "Google Play build " + latestVersionCode;
        return "";
    }
}
