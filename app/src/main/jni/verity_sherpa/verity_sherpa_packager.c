/*
 * Packaging-only anchor for Verity's official Sherpa-ONNX Android JNI.
 *
 * Android Gradle Plugin does not package an unreferenced PREBUILT_SHARED_LIBRARY
 * from an external ndk-build graph. This tiny, never-loaded library references
 * one stable Sherpa 1.13.3 JNI export so ndk-build/Gradle include
 * libsherpa-onnx-jni.so in the APK. It is not loaded by DroidBridge.
 */
#include <stdint.h>

extern void Java_com_k2fsa_sherpa_onnx_VersionInfo_getVersionStr2(void);

__attribute__((visibility("default"), used))
uintptr_t droidbridge_verity_sherpa_packaging_anchor(void) {
    return (uintptr_t)&Java_com_k2fsa_sherpa_onnx_VersionInfo_getVersionStr2;
}
