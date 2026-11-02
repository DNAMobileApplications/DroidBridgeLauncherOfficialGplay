DroidBridge Mesa Zink/Turnip v10

This is the full JNI folder based on v9, but with the required Mesa libdrm compatibility layer included.

Why this exists:
- v9 changed gl_bridge.c to prefer RGBX/depth/stencil configs.
- The test log then showed libEGL_mesa.so failing to load because drmGetDevice2 was missing.
- That means the build did not include the Mesa-compatible libdrm.so from v8.

Install:
1. Replace your app/src/main/jni folder contents with this folder, or copy these files over your current JNI folder.
2. Make sure droidbridge_libdrm_compat.c is in app/src/main/jni/.
3. Make sure Android.mk is this v10 Android.mk.
4. Clean project.
5. Uninstall the existing debug app from the device.
6. Rebuild and install.

Expected log:
- DroidBridgeEGLLoader: Loaded EGL libEGL_mesa.so (in namespace: 1)
- GLBridge: eglChooseConfig_p() matched configs=1 ... alpha=0 depth=24 stencil=8 ...
- GLBridge: Binding EGL_OPENGL_API for DroidBridge Mesa desktop GL
- GLBridge: eglCreateContext_p() success
