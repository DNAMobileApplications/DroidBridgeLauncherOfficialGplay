# DroidBridge release hardening rules.
# Keep R8 from breaking native/JNI entry points while still allowing the rest
# of the app to be obfuscated in obfuscatedDebug / obfuscatedRelease tests.

# BuildConfig is read by literal class name in config/auth code.
-keep class ca.dnamobile.droidbridgelauncher.BuildConfig { public static <fields>; }

# Android entry points. AGP usually keeps manifest components, but keeping these
# avoids fragile XML/notification/shortcut/provider references.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# Generic JNI safety: never rename native method declarations.
-keepclasseswithmembernames class * {
    native <methods>;
}

# DroidBridge launcher/native bridge.
-keep class ca.dnamobile.droidbridgelauncher.launcher.NativeLauncherBridge { *; }

# DroidBridge/bridge compatibility classes used directly from libdroidbridge_runtime.so.
# These must keep their binary class names, inner class names, methods, and fields.
-keep class org.lwjgl.glfw.** { *; }
-keep class org.lwjgl.system.** { *; }
-keep class ca.dnamobile.droidbridgelauncher.runtime.** { *; }
-keep class ca.dnamobile.droidbridgelauncher.runtime.**$* { *; }

# SDL Java bridge used by libSDL3.so.  Release crashes if R8 removes/renames
# SDLActivity.onNativeSoftReturnKey() or related SDL callbacks.
-keep class org.libsdl.** { *; }
-keep class org.libsdl.app.** { *; }
-keep class org.libsdl.app.**$* { *; }
-keepclassmembers class org.libsdl.app.SDLActivity {
    *;
}

# Runtime extraction / compressed asset helpers.
-keep class ca.dnamobile.droidbridgelauncher.feature.unpack.** { *; }
-keep class ca.dnamobile.droidbridgelauncher.launcher.RuntimeCompat { *; }
-keep class ca.dnamobile.droidbridgelauncher.launcher.JavaRuntimeBootstrap { *; }
# MultiRTUtils loads these archive classes by literal class name/reflection. Keep only
# those reflection targets instead of exempting both entire libraries from R8.
-keep class org.apache.commons.compress.archivers.tar.TarArchiveInputStream { public *; }
-keep class org.apache.commons.compress.archivers.tar.TarArchiveEntry { public *; }
-keep class org.apache.commons.compress.compressors.xz.XZCompressorInputStream { public *; }
-keep class org.tukaani.xz.XZInputStream { public *; }

# Reflection-sensitive launcher data containers.
-keepclassmembers class ca.dnamobile.droidbridgelauncher.launcher.LaunchPlan {
    <fields>;
    <methods>;
}
-keepclassmembers class ca.dnamobile.droidbridgelauncher.utils.path.LibPath {
    <fields>;
}
-keepclassmembers class ca.dnamobile.droidbridgelauncher.utils.path.PathManager {
    public static <fields>;
}

# Exact members reached by reflection from launcher UI/compatibility code. Keep the
# members, not the whole packages, so the rest of these classes remain obfuscatable.
-keepclassmembers class ca.dnamobile.droidbridgelauncher.GameActivity {
    ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenController dualScreenController;
}
-keepclassmembers class ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenController {
    public boolean isExternalGameModeActive();
    public void setScreenSwap(boolean, boolean);
    public void toggleScreenSwap(boolean);
}
-keepclassmembers class ca.dnamobile.droidbridgelauncher.modmanager.ModrinthVersion {
    public <fields>;
}
-keepclassmembers class ca.dnamobile.droidbridgelauncher.modmanager.ModpackInstallManager$ModpackVersionChoice {
    public <fields>;
}
-keepclassmembers class ca.dnamobile.droidbridgelauncher.modmanager.ModManagerManifest {
    <methods>;
}

# LiteGLES plugin preloader reflection helper.
-keep class ca.dnamobile.droidbridgelauncher.renderer.LiteGlesLaunchPreloader { *; }

# Keep reflection/library metadata useful without keeping your entire app readable.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Reduce source file leakage in release stack traces.
-renamesourcefileattribute DroidBridge

# Horizon engine host classes: the Rust library binds JNI exports to these names.
-keep class dev.horizon.android.** { *; }
