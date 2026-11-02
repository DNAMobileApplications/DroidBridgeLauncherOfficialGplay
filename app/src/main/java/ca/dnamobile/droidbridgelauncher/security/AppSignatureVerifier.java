/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.BuildConfig;

/**
 * Central signer verification for release-sensitive actions.
 *
 * Compatibility mode preserves Google Play App Signing installs when the configured
 * fingerprint is still the upload certificate. Strict mode removes that fallback and
 * requires one of EXPECTED_RELEASE_CERT_SHA256 to match the installed signer.
 */
public final class AppSignatureVerifier {
    private static final boolean ENFORCE_IN_DEBUG_BUILDS = false;
    private static final String GOOGLE_PLAY_PACKAGE = "com.android.vending";

    private AppSignatureVerifier() {
    }

    public static boolean shouldEnforceSignatureCheck() {
        if (BuildConfig.DEBUG && !BuildConfig.HARDENED_BUILD && !ENFORCE_IN_DEBUG_BUILDS) return false;
        return BuildConfig.LOCAL_SIGNATURE_CHECK_ENABLED || !getExpectedHashes().isEmpty();
    }

    public static boolean isStrictMode() {
        return BuildConfig.LOCAL_SIGNATURE_CHECK_ENABLED;
    }

    public static boolean isExpectedSignature(@NonNull Context context) {
        if (!shouldEnforceSignatureCheck()) return true;

        List<String> expectedHashes = getExpectedHashes();
        if (expectedHashes.isEmpty()) {
            // Never treat an absent signing-certificate allowlist as approval.
            return false;
        }

        try {
            Signature[] signatures = getVerificationSignatures(context);
            if (signatures != null) {
                for (Signature signature : signatures) {
                    if (expectedHashes.contains(sha256Hex(signature.toByteArray()))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Strict mode deliberately fails closed below.
        }

        // A self-reported installer package is not proof of signing identity.
        return false;
    }

    public static void requireExpectedSignature(@NonNull Context context) {
        if (!isExpectedSignature(context)) {
            throw new SecurityException("Blocked modified or re-signed DroidBridge Launcher build.");
        }
    }

    /**
     * Returns a stable SHA-256 digest representing the certificate(s) signing the
     * currently installed APK. This deliberately uses current signers, not signing
     * history, so an offline entitlement is bound to the exact installed signer.
     */
    @NonNull
    public static String getCurrentSignerSetDigest(@NonNull Context context) throws Exception {
        Signature[] signatures = getCurrentSignatures(context);
        if (signatures == null || signatures.length == 0) {
            throw new IllegalStateException("No installed app signing certificate was available.");
        }

        ArrayList<String> hashes = new ArrayList<>();
        for (Signature signature : signatures) {
            hashes.add(sha256Hex(signature.toByteArray()));
        }
        Collections.sort(hashes);
        return String.join(".", hashes);
    }

    private static boolean isInstalledFromGooglePlay(@NonNull Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            String packageName = context.getPackageName();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.InstallSourceInfo source = packageManager.getInstallSourceInfo(packageName);
                if (source != null) {
                    if (isGooglePlayPackage(source.getInstallingPackageName())
                            || isGooglePlayPackage(source.getInitiatingPackageName())
                            || isGooglePlayPackage(source.getOriginatingPackageName())) {
                        return true;
                    }
                }
            }

            @SuppressWarnings("deprecation")
            String installer = packageManager.getInstallerPackageName(packageName);
            return isGooglePlayPackage(installer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isGooglePlayPackage(@Nullable String packageName) {
        return GOOGLE_PLAY_PACKAGE.equals(packageName);
    }

    @NonNull
    private static List<String> getExpectedHashes() {
        ArrayList<String> hashes = new ArrayList<>();
        String raw = safeString(BuildConfig.EXPECTED_RELEASE_CERT_SHA256);
        if (raw.isEmpty() || raw.contains("PUT_YOUR") || raw.contains("CHANGE_ME")) {
            return hashes;
        }

        String[] parts = raw.split("[,;\\n\\r]+");
        for (String part : parts) {
            String normalized = normalizeSha256(part);
            if (normalized.length() == 64 && !hashes.contains(normalized)) {
                hashes.add(normalized);
            }
        }
        return hashes;
    }

    @Nullable
    private static Signature[] getVerificationSignatures(@NonNull Context context) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
            if (packageInfo.signingInfo == null) return null;
            if (packageInfo.signingInfo.hasMultipleSigners()) {
                return packageInfo.signingInfo.getApkContentsSigners();
            }
            return packageInfo.signingInfo.getSigningCertificateHistory();
        }

        @SuppressWarnings("deprecation")
        PackageInfo packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        @SuppressWarnings("deprecation")
        Signature[] signatures = packageInfo.signatures;
        return signatures;
    }

    @Nullable
    private static Signature[] getCurrentSignatures(@NonNull Context context) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
            if (packageInfo.signingInfo == null) return null;
            return packageInfo.signingInfo.getApkContentsSigners();
        }

        @SuppressWarnings("deprecation")
        PackageInfo packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        @SuppressWarnings("deprecation")
        Signature[] signatures = packageInfo.signatures;
        return signatures;
    }

    @NonNull
    private static String sha256Hex(@NonNull byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    @NonNull
    private static String normalizeSha256(@Nullable String value) {
        if (value == null) return "";
        return value.replace(":", "")
                .replace(" ", "")
                .replace("-", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String safeString(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
