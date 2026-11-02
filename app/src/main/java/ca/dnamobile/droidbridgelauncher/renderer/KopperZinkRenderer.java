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

package ca.dnamobile.droidbridgelauncher.renderer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Experimental Mesa Zink/Kopper renderer.
 *
 * Unlike the legacy VulkanZinkRenderer, this path does not use OSMesa. It uses
 * Mesa EGL/Kopper with DroidBridge's own OpenGL proxy and Android surface bridge.
 */
public final class KopperZinkRenderer implements RendererInterface {
    public static final String RENDERER_ID = "opengles3_desktopgl_zink_kopper";
    public static final String MAIN_LIBRARY = "libGLDroidBridge.so";
    public static final String EGL_LIBRARY = "libEGL_mesa.so";

    @NonNull
    @Override
    public String getRendererId() {
        return RENDERER_ID;
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        // Keep this separate from DroidBridge's existing Vulkan Zink UUID so
        // instances can switch between the two renderers for A/B testing.
        return "2c809fc7-83b3-456c-bd94-51209307d2c9";
    }

    @NonNull
    @Override
    public String getRendererName() {
        return "Kopper Zink (Experimental)";
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return "Experimental Zink-over-Vulkan renderer using Mesa Kopper/native-window presentation instead of legacy OSMesa. Intended for direct A/B performance and compatibility testing against Vulkan Zink.";
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        // Keep the core Zink tuning aligned with VulkanZinkRenderer so an A/B
        // benchmark mostly measures the presentation/context path difference.
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
        env.put("GALLIUM_DRIVER", "zink");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("force_gl_vendor", "Mesa/DroidBridge");
        env.put("MESA_ANDROID_NO_KMS_SWRAST", "1");
        // Prefer the conservative descriptor path and preserve GL command order.
        // This targets partial atlas/glyph uploads and native-driver instability.
        env.put("ZINK_DESCRIPTORS", "lazy");
        env.put("ZINK_DEBUG", "compact,noreorder");
        env.put("mesa_glthread", "false");
        // Kopper exposes desktop OpenGL through Mesa EGL.  The generic opengles*
        // bridge otherwise requests EGL_OPENGL_ES2_BIT and finds zero configs.
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        // Correctness first. KHR_no_error can turn invalid GL usage into undefined
        // behavior, which is especially risky while validating a new Kopper path.
        env.put("MESA_NO_ERROR", "0");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NOERROR", "0");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        env.put("allow_higher_compat_version", "true");
        env.put("allow_glsl_extension_directive_midshader", "true");
        env.put("force_glsl_extensions_warn", "true");
        // FCL/Pojav sets its Android native window to opaque RGBX8888 before
        // initializing the bridge. DroidBridge does this via the surface flag.
        env.put("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1");
        // Kopper's Android WSI must use the composited TextureView owner. A direct
        // SurfaceView can expose a 90-degree panel transform and unstable native
        // window lifetime on some Android/Adreno devices.
        env.put("DROIDBRIDGE_KOPPER_FORCE_TEXTUREVIEW", "1");
        env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");
        env.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "");
        return env;
    }

    @NonNull
    @Override
    public List<String> getDlopenLibrary() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public String getRendererLibrary() {
        return MAIN_LIBRARY;
    }

    @Override
    public String getRendererEGL() {
        return EGL_LIBRARY;
    }

    public static boolean isRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String id = renderer.getRendererId() != null ? renderer.getRendererId().toLowerCase(Locale.ROOT) : "";
        String name = renderer.getRendererName() != null ? renderer.getRendererName().toLowerCase(Locale.ROOT) : "";
        return RENDERER_ID.equals(id)
                || name.contains("kopper zink");
    }
}
