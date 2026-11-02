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
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;

/**
 * Install-bound proof that a real Microsoft/Minecraft sign-in completed once.
 *
 * The receipt is authenticated with a non-exportable Android Keystore HMAC key and
 * is also bound to the APK's current signing certificate. It intentionally contains
 * no Microsoft access or refresh token. The key is not backed up and disappears on
 * uninstall, so copying SharedPreferences to a fresh/re-signed install does not grant
 * offline access.
 */
public final class InstallEntitlementVault {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_ALIAS = "droidbridge.install.entitlement.hmac.v2";
    private static final String PREFS = "droidbridge_install_entitlement_v2";

    private static final int SCHEMA = 2;
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_SIGNER = "signer";
    private static final String KEY_UUID = "minecraft_uuid";
    private static final String KEY_ISSUED_AT = "issued_at";
    private static final String KEY_NONCE = "nonce";
    private static final String KEY_MAC = "mac";

    private static final SecureRandom RANDOM = new SecureRandom();

    private InstallEntitlementVault() {
    }

    public static void issue(@NonNull Context context, @NonNull AccountStore.Account account) {
        Context appContext = context.getApplicationContext();
        if (!isVerifiedMicrosoftAccount(account)) {
            throw new IllegalArgumentException("A verified Microsoft/Minecraft account is required to issue offline access.");
        }

        if (BuildConfig.HARDENED_BUILD && !AppSignatureVerifier.isExpectedSignature(appContext)) {
            throw new SecurityException("Cannot issue offline access to a modified or re-signed build.");
        }

        try {
            String signer = AppSignatureVerifier.getCurrentSignerSetDigest(appContext);
            String uuid = normalizeUuid(account.minecraftUuid);
            long issuedAt = System.currentTimeMillis();
            String nonce = randomNonce();
            String canonical = canonical(appContext, signer, uuid, issuedAt, nonce);
            String mac = base64(hmac(getOrCreateKey(), canonical.getBytes(StandardCharsets.UTF_8)));

            boolean stored = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_SCHEMA, SCHEMA)
                    .putString(KEY_SIGNER, signer)
                    .putString(KEY_UUID, uuid)
                    .putLong(KEY_ISSUED_AT, issuedAt)
                    .putString(KEY_NONCE, nonce)
                    .putString(KEY_MAC, mac)
                    .commit();
            if (!stored) {
                throw new IllegalStateException("Could not store the offline entitlement receipt.");
            }
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not seal offline entitlement on this device.", exception);
        }
    }

    public static boolean hasValidEntitlement(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        try {
            if (BuildConfig.HARDENED_BUILD && !AppSignatureVerifier.isExpectedSignature(appContext)) {
                return false;
            }

            SharedPreferences preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (preferences.getInt(KEY_SCHEMA, -1) != SCHEMA) return false;

            String signer = safe(preferences.getString(KEY_SIGNER, ""));
            String uuid = normalizeUuid(preferences.getString(KEY_UUID, ""));
            long issuedAt = preferences.getLong(KEY_ISSUED_AT, 0L);
            String nonce = safe(preferences.getString(KEY_NONCE, ""));
            String storedMac = safe(preferences.getString(KEY_MAC, ""));

            if (signer.isEmpty() || uuid.length() != 32 || issuedAt <= 0L || nonce.isEmpty() || storedMac.isEmpty()) {
                return false;
            }

            String currentSigner = AppSignatureVerifier.getCurrentSignerSetDigest(appContext);
            if (!MessageDigest.isEqual(
                    signer.getBytes(StandardCharsets.UTF_8),
                    currentSigner.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }

            SecretKey key = getExistingKey();
            if (key == null) return false;

            String canonical = canonical(appContext, signer, uuid, issuedAt, nonce);
            byte[] expectedMac = hmac(key, canonical.getBytes(StandardCharsets.UTF_8));
            byte[] actualMac = Base64.decode(storedMac, Base64.NO_WRAP);
            return MessageDigest.isEqual(expectedMac, actualMac);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The receipt is account-specific. A valid receipt for an old/saved account
     * cannot authorize a different profile or a handcrafted offline identity.
     */
    public static boolean hasValidEntitlementForAccount(
            @NonNull Context context,
            @Nullable AccountStore.Account account
    ) {
        if (!isVerifiedMicrosoftAccount(account) || !hasValidEntitlement(context)) return false;
        try {
            String receiptUuid = normalizeUuid(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_UUID, ""));
            String accountUuid = normalizeUuid(account.minecraftUuid);
            return !accountUuid.isEmpty() && MessageDigest.isEqual(
                    accountUuid.getBytes(StandardCharsets.UTF_8),
                    receiptUuid.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clear(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = getExistingKey();
        if (existing != null) return existing;

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
        )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    @Nullable
    private static SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) return null;
        java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
        return key instanceof SecretKey ? (SecretKey) key : null;
    }

    @NonNull
    private static byte[] hmac(@NonNull SecretKey key, @NonNull byte[] data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(key);
        return mac.doFinal(data);
    }

    @NonNull
    private static String canonical(
            @NonNull Context context,
            @NonNull String signer,
            @NonNull String uuid,
            long issuedAt,
            @NonNull String nonce
    ) {
        return SCHEMA + "|" + context.getPackageName() + "|" + signer + "|" + uuid + "|" + issuedAt + "|" + nonce;
    }

    private static boolean isVerifiedMicrosoftAccount(@Nullable AccountStore.Account account) {
        return account != null
                && account.isMicrosoftAccount()
                && account.hasMinecraftSession()
                && !safe(account.minecraftAccessToken).isEmpty()
                && !"0".equals(safe(account.minecraftAccessToken))
                && normalizeUuid(account.minecraftUuid).length() == 32;
    }

    @NonNull
    private static String randomNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return base64(bytes);
    }

    @NonNull
    private static String normalizeUuid(@Nullable String value) {
        String normalized = safe(value).replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{32}") ? normalized : "";
    }

    @NonNull
    private static String base64(@NonNull byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
