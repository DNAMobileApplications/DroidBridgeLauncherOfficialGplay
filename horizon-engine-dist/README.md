# Horizon engine drop folder

Optional. Put the Horizon Android build output here when `HORIZON_ANDROID_OUTPUT` is not set
in `local.properties`:

```
horizon-engine-dist/
  jniLibs/arm64-v8a/libhorizon_android.so   <- horizon-engine/.local/android/gradle-jni/arm64-v8a/
  assets/engine-data.zip                    <- horizon-engine/.local/android/gradle-assets/
  assets/LICENSE.txt                        <- (optional, shipped as assets/horizon/LICENSE.txt)
  assets/THIRD_PARTY_NOTICES.md             <- (optional)
```

Empty means the app builds with the Java engine only. Do not commit these binaries: a
non-`--public` engine-data.zip contains tables generated from Minecraft's game files.
