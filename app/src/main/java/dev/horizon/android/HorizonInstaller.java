/*
 * Horizon engine resource installer bridge for DroidBridge Launcher.
 *
 * The natives are implemented in crates/horizon-client/src/android/host_install.rs of the
 * patched Horizon engine. The class must stay dev.horizon.android.HorizonInstaller because the
 * JNI exports are named after it. Only call it from the :horizon process, so the engine
 * library never loads into the launcher's own process.
 */

package dev.horizon.android;

/** Installs Minecraft 26.3's resources for the Horizon engine, with progress callbacks. */
public final class HorizonInstaller {
    static { System.loadLibrary("horizon_android"); }

    /** Receives progress on the thread that called {@link #install}. */
    public interface Listener {
        /**
         * @param stageIndex 0-based stage, of {@link #STAGE_COUNT}
         * @param label      the stage's description
         * @param done       bytes (download stage) or items done; 0 when unknown
         * @param total      bytes or items in this stage; 0 when unknown
         */
        void onProgress(int stageIndex, String label, long done, long total);
    }

    /** Metadata, download, unpack, world data, touch controls, finish. */
    public static final int STAGE_COUNT = 6;
    /** The stage whose progress is in bytes. */
    public static final int STAGE_DOWNLOAD = 1;

    private HorizonInstaller() {
    }

    /** True when the engine home already holds a complete installation. */
    public static boolean isReady(String home) {
        return nativeReady(home);
    }

    /**
     * Downloads and prepares the resources. Blocks; call it off the main thread.
     *
     * @param home  the engine home, which must already contain engine-data.zip
     * @param cache a cache directory for the downloads
     * @return null on success, otherwise a message for the player
     */
    public static String install(String home, String cache, Listener listener) {
        return nativeInstall(home, cache, listener);
    }

    private static native boolean nativeReady(String home);

    private static native String nativeInstall(String home, String cache, Listener listener);
}
