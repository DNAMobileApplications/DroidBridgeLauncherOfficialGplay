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

package ca.dnamobile.droidbridgelauncher.security;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;

public final class LauncherSecurity {
    private LauncherSecurity() {
    }

    public static boolean isHardenedBuild() {
        return BuildConfig.HARDENED_BUILD;
    }

    /** Legacy API kept for callers: offline/local user-created profiles are forbidden. */
    public static boolean allowsOfflineProfileAuth() {
        return false;
    }

    public static boolean hasValidMicrosoftSession(@Nullable AccountStore.Account account) {
        return account != null
                && account.isMicrosoftAccount()
                && account.hasMinecraftSession()
                && !isBlank(account.minecraftAccessToken)
                && !"0".equals(account.minecraftAccessToken.trim())
                && !isBlank(account.minecraftName)
                && !isBlank(account.minecraftUuid);
    }

    public static boolean hasValidMicrosoftSession(@Nullable AccountStore accountStore) {
        if (accountStore == null) return false;
        try {
            return hasValidMicrosoftSession(accountStore.load());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Deprecated profile entitlement: no offline-account identity can launch. */
    public static boolean hasValidOfflineEntitlement(@NonNull Context context) {
        return false;
    }

    /**
     * A remembered *Microsoft* profile may play while disconnected, but only if
     * the device has a valid, signer-bound receipt for that exact Minecraft UUID.
     */
    public static boolean canLaunchWithMicrosoftAccount(
            @NonNull Context context,
            @Nullable AccountStore.Account account
    ) {
        if (!hasValidMicrosoftSession(account)) return false;
        AccountStore.Account savedAccount;
        try {
            savedAccount = new AccountStore(context).load();
        } catch (Throwable ignored) {
            return false;
        }
        if (!hasValidMicrosoftSession(savedAccount)) return false;
        if (!sameUuid(account.minecraftUuid, savedAccount.minecraftUuid)) return false;
        if (!safeEquals(account.minecraftAccessToken, savedAccount.minecraftAccessToken)) return false;
        if (!BuildConfig.HARDENED_BUILD) return true;
        // A demo account has no entitlement by design; its launches are always the demo
        // (JavaLaunchBuilder adds --demo), and the Rust engine refuses it (isDemoAccount).
        if (account.isDemoAccount() && savedAccount.isDemoAccount()) return true;
        return InstallEntitlementVault.hasValidEntitlementForAccount(context, account);
    }

    /** A demo account: Microsoft sign-in without Java Edition (plays Minecraft's demo only). */
    public static boolean isDemoAccount(@Nullable AccountStore.Account account) {
        return account != null && account.isMicrosoftAccount() && account.isDemoAccount();
    }

    public static void requireOfficialBuild(@NonNull Context context) {
        if (!BuildConfig.HARDENED_BUILD) return;
        AppSignatureVerifier.requireExpectedSignature(context);
    }

    /** Enforce the same account-specific validation on every game launch path. */
    public static void requireOfficialBuildAndMicrosoftSession(
            @NonNull Context context,
            @Nullable AccountStore.Account account,
            @NonNull String action
    ) {
        requireOfficialBuild(context);
        if (!canLaunchWithMicrosoftAccount(context, account)) {
            throw new SecurityException(
                    "A verified Microsoft/Minecraft account is required to " + action
                            + ". Sign in again if upgrading from an older build."
            );
        }
    }

    /**
     * Network/install gate. Offline entitlement deliberately does not satisfy this.
     */
    @NonNull
    public static AccountStore.Account requireActiveMicrosoftAccount(
            @NonNull Context context,
            @NonNull String action
    ) {
        requireOfficialBuild(context);
        AccountStore.Account account = null;
        try {
            account = new AccountStore(context).load();
        } catch (Throwable ignored) {
        }

        if (!hasValidMicrosoftSession(account)) {
            throw new SecurityException(
                    "A live Microsoft/Minecraft account is required to " + action + "."
            );
        }
        if (BuildConfig.HARDENED_BUILD && !account.isDemoAccount()
                && !InstallEntitlementVault.hasValidEntitlementForAccount(context, account)) {
            throw new SecurityException(
                    "This install has not completed its verified Microsoft/Minecraft activation."
            );
        }
        return account;
    }

    private static boolean sameUuid(@Nullable String first, @Nullable String second) {
        return !isBlank(first) && !isBlank(second)
                && first.replace("-", "").equalsIgnoreCase(second.replace("-", ""));
    }

    private static boolean safeEquals(@Nullable String first, @Nullable String second) {
        return first != null && first.equals(second);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
