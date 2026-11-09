/*
 * Horizon add-on checks for DroidBridge Launcher.
 *
 * The class name must stay dev.horizon.android.HorizonAddonsProvider: the engine's JNI export
 * (crates/horizon-client/src/android/host_addons_jni.rs) is named after it.
 *
 * It runs in the :horizon process, like HorizonDocumentsProvider and HorizonInstallService, so
 * the engine library never loads into the launcher. DroidBridge's Horizon Add-ons screen calls
 * it through ContentResolver.call() to read a .hmod or shader pack with the game's own checks,
 * to check a file from Horizon's mod registry (size, SHA-256, revocation, minisign signature)
 * before it is installed, and to list the native mods compiled into this build.
 */

package dev.horizon.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

public final class HorizonAddonsProvider extends ContentProvider {
    /** The Bundle key holding the engine's JSON answer. */
    public static final String KEY_RESULT = "result";

    private static final String TAG = "DroidBridgeHorizon";

    private static native String nativeCall(String operation, String arguments);

    private static boolean loaded;
    private static String loadError;

    private static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("horizon_android");
            loaded = true;
        } catch (Throwable error) {
            Log.w(TAG, "Horizon engine library is not available for add-on checks", error);
            loadError = String.valueOf(error.getMessage());
        }
        return loaded;
    }

    @Override
    public boolean onCreate() {
        // The engine library loads on the first call, not with the process.
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        if (method == null || method.isEmpty()) {
            reply.putString(KEY_RESULT, failure("no operation"));
            return reply;
        }
        if (!ensureLoaded()) {
            reply.putString(KEY_RESULT, failure("the Rust engine is not included in this build"
                    + (loadError == null ? "" : " (" + loadError + ")")));
            return reply;
        }
        try {
            String answer = nativeCall(method, arg == null ? "{}" : arg);
            reply.putString(KEY_RESULT, answer == null ? failure("the engine gave no answer") : answer);
        } catch (Throwable error) {
            Log.w(TAG, "Horizon add-on check failed: " + method, error);
            reply.putString(KEY_RESULT, failure(error.getClass().getSimpleName() + ": " + error.getMessage()));
        }
        return reply;
    }

    private static String failure(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Throwable ignored) {
            return "{\"ok\":false,\"error\":\"add-on check failed\"}";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
