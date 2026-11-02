/*
 * DroidBridge Launcher native runtime bridge component.
 *
 * Original project:
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>
#include "droidbridge_renderspec.h"
#include "ctxbridges/egl_loader.h"
#include "driver_helper/nsbypass.h"

#include <dlfcn.h>
#include <stdbool.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef unsigned int DB_GLenum;
typedef unsigned int DB_GLuint;
typedef unsigned char DB_GLubyte;
typedef const DB_GLubyte* (*db_gl_get_string_fn)(DB_GLenum name);
typedef const DB_GLubyte* (*db_gl_get_string_i_fn)(DB_GLenum name, DB_GLuint index);
typedef void (*db_gl_get_integerv_fn)(DB_GLenum name, int* value);
typedef DB_GLuint (*db_gl_create_shader_fn)(DB_GLenum type);
typedef void (*db_gl_shader_source_fn)(DB_GLuint shader, int count,
                                        const char* const* strings, const int* lengths);
typedef void (*db_gl_compile_shader_fn)(DB_GLuint shader);
typedef DB_GLuint (*db_gl_create_program_fn)(void);
typedef DB_GLenum (*db_gl_get_error_fn)(void);
typedef void (*db_gl_enable_fn)(DB_GLenum cap);
typedef void (*db_gl_disable_fn)(DB_GLenum cap);
typedef unsigned char (*db_gl_is_enabled_fn)(DB_GLenum cap);
typedef void* (*db_egl_get_proc_address_fn)(const char* name);
typedef void* (*db_egl_get_current_display_fn)(void);
typedef void* (*db_egl_get_current_surface_fn)(int readdraw);
typedef void* (*db_egl_get_current_context_fn)(void);
typedef unsigned int (*db_egl_make_current_fn)(void* display, void* draw, void* read, void* context);
typedef int (*db_egl_get_error_code_fn)(void);
typedef unsigned int (*db_egl_query_surface_fn)(void* display, void* surface, int attribute, int* value);
typedef void* (*db_gl_map_buffer_range_fn)(DB_GLenum target, intptr_t offset, intptr_t length, unsigned int access);
typedef void* (*db_gl_map_buffer_fn)(DB_GLenum target, DB_GLenum access);
typedef unsigned char (*db_gl_unmap_buffer_fn)(DB_GLenum target);
typedef void (*db_gl_flush_mapped_buffer_range_fn)(DB_GLenum target, intptr_t offset, intptr_t length);
typedef void (*db_gl_buffer_sub_data_fn)(DB_GLenum target, intptr_t offset, intptr_t size, const void* data);
typedef void (*db_gl_get_buffer_sub_data_fn)(DB_GLenum target, intptr_t offset, intptr_t size, void* data);
typedef void (*db_gl_bind_buffer_fn)(DB_GLenum target, DB_GLuint buffer);
typedef void (*db_gl_get_buffer_parameter_iv_fn)(DB_GLenum target, DB_GLenum pname, int* params);
typedef void (*db_gl_bind_framebuffer_fn)(DB_GLenum target, DB_GLuint framebuffer);
typedef void (*db_gl_blit_framebuffer_fn)(int srcX0, int srcY0, int srcX1, int srcY1,
                                           int dstX0, int dstY0, int dstX1, int dstY1,
                                           unsigned int mask, DB_GLenum filter);
typedef void (*db_gl_blit_named_framebuffer_fn)(DB_GLuint readFramebuffer, DB_GLuint drawFramebuffer,
                                                 int srcX0, int srcY0, int srcX1, int srcY1,
                                                 int dstX0, int dstY0, int dstX1, int dstY1,
                                                 unsigned int mask, DB_GLenum filter);
typedef void (*db_gl_get_booleanv_fn)(DB_GLenum pname, unsigned char* data);
typedef void (*db_gl_get_framebuffer_attachment_parameter_iv_fn)(
        DB_GLenum target, DB_GLenum attachment, DB_GLenum pname, int* params);
typedef void (*db_gl_active_texture_fn)(DB_GLenum texture);
typedef void (*db_gl_bind_texture_fn)(DB_GLenum target, DB_GLuint texture);
typedef void (*db_gl_use_program_fn)(DB_GLuint program);
typedef void (*db_gl_attach_shader_fn)(DB_GLuint program, DB_GLuint shader);
typedef void (*db_gl_link_program_fn)(DB_GLuint program);
typedef void (*db_gl_get_shader_iv_fn)(DB_GLuint shader, DB_GLenum pname, int* params);
typedef void (*db_gl_get_shader_info_log_fn)(DB_GLuint shader, int maxLength, int* length, char* infoLog);
typedef void (*db_gl_get_program_iv_fn)(DB_GLuint program, DB_GLenum pname, int* params);
typedef void (*db_gl_get_program_info_log_fn)(DB_GLuint program, int maxLength, int* length, char* infoLog);
typedef void (*db_gl_delete_shader_fn)(DB_GLuint shader);
typedef int (*db_gl_get_uniform_location_fn)(DB_GLuint program, const char* name);
typedef void (*db_gl_uniform_1i_fn)(int location, int value);
typedef void (*db_gl_uniform_4f_fn)(int location, float v0, float v1, float v2, float v3);
typedef void (*db_gl_gen_vertex_arrays_fn)(int n, DB_GLuint* arrays);
typedef void (*db_gl_bind_vertex_array_fn)(DB_GLuint array);
typedef void (*db_gl_draw_arrays_fn)(DB_GLenum mode, int first, int count);
typedef void (*db_gl_viewport_fn)(int x, int y, int width, int height);
typedef void (*db_gl_color_mask_fn)(unsigned char red, unsigned char green, unsigned char blue, unsigned char alpha);

#define DB_GL_VENDOR 0x1F00u
#define DB_GL_VERSION 0x1F02u
#define DB_GL_EXTENSIONS 0x1F03u
#define DB_GL_MAJOR_VERSION 0x821Bu
#define DB_GL_MINOR_VERSION 0x821Cu
#define DB_GL_FRAMEBUFFER_SRGB 0x8DB9u
#define DB_BLOCKED_BUFFER_STORAGE_ARB "GL_ARB_buffer_storage"
#define DB_BLOCKED_BUFFER_STORAGE_EXT "GL_EXT_buffer_storage"
#define DB_DISABLED_EXTENSION_NAME "GL_DROIDBRIDGE_buffer_storage_disabled"
#define DB_GL_MAP_READ_BIT 0x0001u
#define DB_GL_MAP_WRITE_BIT 0x0002u
#define DB_GL_MAP_INVALIDATE_RANGE_BIT 0x0004u
#define DB_GL_MAP_INVALIDATE_BUFFER_BIT 0x0008u
#define DB_GL_READ_ONLY 0x88B8u
#define DB_GL_WRITE_ONLY 0x88B9u
#define DB_GL_READ_WRITE 0x88BAu
#define DB_GL_BUFFER_SIZE 0x8764u
#define DB_GL_READ_FRAMEBUFFER 0x8CA8u
#define DB_GL_DRAW_FRAMEBUFFER 0x8CA9u
#define DB_GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6u
#define DB_GL_READ_FRAMEBUFFER_BINDING 0x8CAAu
#define DB_GL_READ_BUFFER 0x0C02u
#define DB_GL_COLOR_ATTACHMENT0 0x8CE0u
#define DB_GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE 0x8CD0u
#define DB_GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME 0x8CD1u
#define DB_GL_TEXTURE 0x1702u
#define DB_GL_TEXTURE_2D 0x0DE1u
#define DB_GL_TEXTURE0 0x84C0u
#define DB_GL_ACTIVE_TEXTURE 0x84E0u
#define DB_GL_TEXTURE_BINDING_2D 0x8069u
#define DB_GL_CURRENT_PROGRAM 0x8B8Du
#define DB_GL_VERTEX_ARRAY_BINDING 0x85B5u
#define DB_GL_VIEWPORT 0x0BA2u
#define DB_GL_COLOR_WRITEMASK 0x0C23u
#define DB_GL_SCISSOR_TEST 0x0C11u
#define DB_GL_BLEND 0x0BE2u
#define DB_GL_DEPTH_TEST 0x0B71u
#define DB_GL_STENCIL_TEST 0x0B90u
#define DB_GL_CULL_FACE 0x0B44u
#define DB_GL_RASTERIZER_DISCARD 0x8C89u
#define DB_GL_VERTEX_SHADER 0x8B31u
#define DB_GL_FRAGMENT_SHADER 0x8B30u
#define DB_GL_COMPILE_STATUS 0x8B81u
#define DB_GL_LINK_STATUS 0x8B82u
#define DB_GL_INFO_LOG_LENGTH 0x8B84u
#define DB_GL_TRIANGLES 0x0004u
#define DB_GL_COLOR_BUFFER_BIT 0x00004000u
#define DB_EGL_DRAW 0x3059
#define DB_EGL_READ 0x305A
#define DB_EGL_WIDTH 0x3057
#define DB_EGL_HEIGHT 0x3056
#define DB_EGL_SUCCESS 0x3000
#define DB_MAX_SHADOW_MAPS 16
#define TAG "LwjglLinkerHook"

static void* g_droidbridge_opengl_proxy_handle = NULL;
static void* g_droidbridge_simplefpe_handle = NULL;
static bool g_droidbridge_simplefpe_load_attempted = false;
static pthread_mutex_t g_droidbridge_simplefpe_lock = PTHREAD_MUTEX_INITIALIZER;
static db_gl_get_string_fn g_real_glGetString = NULL;
static db_gl_get_string_i_fn g_real_glGetStringi = NULL;
static db_gl_get_integerv_fn g_real_glGetIntegerv = NULL;
static db_gl_create_shader_fn g_real_glCreateShader = NULL;
static db_gl_shader_source_fn g_real_glShaderSource = NULL;
static db_gl_compile_shader_fn g_real_glCompileShader = NULL;
static db_gl_create_program_fn g_real_glCreateProgram = NULL;
static db_gl_get_error_fn g_real_glGetError = NULL;
static db_gl_enable_fn g_real_glEnable = NULL;
static db_gl_disable_fn g_real_glDisable = NULL;
static db_gl_is_enabled_fn g_real_glIsEnabled = NULL;
static char g_droidbridge_vendor_string[256];
static char g_droidbridge_filtered_version[768];
static char* g_droidbridge_filtered_extensions = NULL;
static bool g_droidbridge_filtered_extensions_ready = false;
static bool g_droidbridge_logged_buffer_filter = false;
static bool g_droidbridge_logged_shadow_map = false;
static bool g_droidbridge_logged_shadow_upload = false;
static bool g_droidbridge_logged_mobileglues_shader_normalization = false;
static bool g_droidbridge_logged_wrapped_create_shader = false;
static bool g_droidbridge_logged_wrapped_create_program = false;
static bool g_droidbridge_logged_framebuffer_srgb = false;
static bool g_droidbridge_runtime_hooks_installed = false;
static bool g_droidbridge_buffer_map_fallback_installed = false;
static bool g_droidbridge_gl_string_reporting_installed = false;
static bool g_droidbridge_logged_gl_version_report = false;

/* Resolved by ctxbridges/osmesa_loader.c for the legacy Vulkan Zink path.
 * Keeping this as an extern avoids changing LWJGL itself while still letting the
 * JNI nglGetString override call the exact Mesa/Zink frontend that owns the
 * current OSMesa context. */
extern unsigned char* (*glGetString_p)(unsigned int name);
static void* g_droidbridge_wrapped_gl_handle = NULL;
static db_gl_map_buffer_range_fn g_real_glMapBufferRange = NULL;
static db_gl_map_buffer_fn g_real_glMapBuffer = NULL;
static db_gl_unmap_buffer_fn g_real_glUnmapBuffer = NULL;
static db_gl_flush_mapped_buffer_range_fn g_real_glFlushMappedBufferRange = NULL;
static db_gl_buffer_sub_data_fn g_real_glBufferSubData = NULL;
static db_gl_get_buffer_sub_data_fn g_real_glGetBufferSubData = NULL;
static db_gl_bind_buffer_fn g_real_glBindBuffer = NULL;
static db_gl_get_buffer_parameter_iv_fn g_real_glGetBufferParameteriv = NULL;
static db_gl_bind_framebuffer_fn g_real_glBindFramebuffer = NULL;
static db_gl_blit_framebuffer_fn g_real_glBlitFramebuffer = NULL;
static db_gl_blit_framebuffer_fn g_real_glBlitFramebufferKopper = NULL;
static db_gl_get_integerv_fn g_real_glGetIntegervKopper = NULL;
static db_gl_blit_named_framebuffer_fn g_real_glBlitNamedFramebufferKopper = NULL;
static bool g_droidbridge_logged_kopper_present_scale = false;
static bool g_droidbridge_logged_freedreno_dsa_blit = false;
static bool g_droidbridge_logged_freedreno_present_draw = false;
static bool g_droidbridge_logged_freedreno_present_draw_failure = false;
static db_egl_get_current_display_fn g_present_eglGetCurrentDisplay = NULL;
static db_egl_get_current_surface_fn g_present_eglGetCurrentSurface = NULL;
static db_egl_query_surface_fn g_present_eglQuerySurface = NULL;
static int g_present_last_logical_width = -1;
static int g_present_last_logical_height = -1;
static int g_present_last_drawable_width = -1;
static int g_present_last_drawable_height = -1;

typedef struct {
    bool resolved;
    bool initialized;
    db_gl_get_booleanv_fn glGetBooleanv;
    db_gl_get_framebuffer_attachment_parameter_iv_fn glGetFramebufferAttachmentParameteriv;
    db_gl_active_texture_fn glActiveTexture;
    db_gl_bind_texture_fn glBindTexture;
    db_gl_use_program_fn glUseProgram;
    db_gl_create_shader_fn glCreateShader;
    db_gl_shader_source_fn glShaderSource;
    db_gl_compile_shader_fn glCompileShader;
    db_gl_get_shader_iv_fn glGetShaderiv;
    db_gl_get_shader_info_log_fn glGetShaderInfoLog;
    db_gl_create_program_fn glCreateProgram;
    db_gl_attach_shader_fn glAttachShader;
    db_gl_link_program_fn glLinkProgram;
    db_gl_get_program_iv_fn glGetProgramiv;
    db_gl_get_program_info_log_fn glGetProgramInfoLog;
    db_gl_delete_shader_fn glDeleteShader;
    db_gl_get_uniform_location_fn glGetUniformLocation;
    db_gl_uniform_1i_fn glUniform1i;
    db_gl_uniform_4f_fn glUniform4f;
    db_gl_gen_vertex_arrays_fn glGenVertexArrays;
    db_gl_bind_vertex_array_fn glBindVertexArray;
    db_gl_draw_arrays_fn glDrawArrays;
    db_gl_viewport_fn glViewport;
    db_gl_color_mask_fn glColorMask;
    DB_GLuint program;
    DB_GLuint vao;
    int sourceRectLocation;
} db_freedreno_present_draw_state;

static db_freedreno_present_draw_state g_freedreno_present_draw;

typedef struct {
    bool active;
    bool real_mapping;
    DB_GLenum target;
    DB_GLuint buffer;
    intptr_t offset;
    intptr_t length;
    unsigned int access;
    void* pointer;
} db_shadow_map;

static __thread db_shadow_map g_shadow_maps[DB_MAX_SHADOW_MAPS];

static bool droidbridge_is_direct_freedreno_renderer(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");

    bool direct_freedreno = false;
    if (renderer != NULL && strcmp(renderer, "freedreno_kgsl") == 0) direct_freedreno = true;
    if (mesa_mode != NULL && strcmp(mesa_mode, "freedreno_kgsl") == 0) direct_freedreno = true;

    if (!direct_freedreno) return false;
    return mesa_driver == NULL || mesa_driver[0] == '\0' || strcmp(mesa_driver, "kgsl") == 0;
}

static bool droidbridge_is_kopper_zink_renderer(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    return (renderer != NULL
            && strcmp(renderer, "opengles3_desktopgl_zink_kopper") == 0)
            || (mesa_mode != NULL && strcmp(mesa_mode, "kopper_zink") == 0);
}

static bool droidbridge_truthy_env(const char* key) {
    const char* value = getenv(key);
    if (value == NULL || value[0] == '\0') return false;
    return strcmp(value, "0") != 0
            && strcmp(value, "false") != 0
            && strcmp(value, "no") != 0
            && strcmp(value, "off") != 0;
}

/*
 * Minecraft 26.3+ RenderPearl presents the final OpenGL texture with
 * glBlitNamedFramebuffer. Direct Mesa/KGSL reaches Android's default drawable
 * through a DSA path that crashes inside libEGL_mesa on current DroidBridge
 * Mesa builds. DROIDBRIDGE_SDL3_MESA_SINGLE_LOADER is intentionally enabled
 * only for the 26.3+ direct-Freedreno SDL3 route, so it is also a stable scope
 * gate that leaves the known-good <=26.2 GLFW route untouched.
 */
static bool droidbridge_freedreno_renderpearl_blit_compat_enabled(void) {
    /*
     * PID 27902 proves that moving the 26.3 EGL surface/context to NativeGLFW
     * does not make Mesa's Android default-framebuffer named blit safe:
     * RenderPearl's first glBlitNamedFramebuffer(..., drawFramebuffer=0, ...)
     * still faults at libEGL_mesa.so+0x2dc34.
     *
     * Keep the compatibility presenter enabled for every 26.3+ direct-Freedreno
     * single-loader route, including the NativeGLFW-owned EGL surface.  The
     * presenter avoids Mesa's default-FBO blit path by drawing the source color
     * texture as a fullscreen triangle instead.  <=26.2 never enables the
     * single-loader SDL3 route and remains untouched.
     */
    return droidbridge_is_direct_freedreno_renderer()
            && droidbridge_truthy_env("DROIDBRIDGE_SDL3_MESA_SINGLE_LOADER");
}

static void droidbridge_glBlitNamedFramebuffer_freedreno_compat(
        DB_GLuint readFramebuffer, DB_GLuint drawFramebuffer,
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1,
        unsigned int mask, DB_GLenum filter);

static void droidbridge_glBlitNamedFramebuffer_kopper_scale(
        DB_GLuint readFramebuffer, DB_GLuint drawFramebuffer,
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1,
        unsigned int mask, DB_GLenum filter);

static void droidbridge_glBlitFramebuffer_kopper_scale(
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1,
        unsigned int mask, DB_GLenum filter);

static bool droidbridge_is_gl_symbol_name(const char* name) {
    return name != NULL && name[0] == 'g' && name[1] == 'l';
}

static void* droidbridge_load_simplefpe(void) {
    if (!droidbridge_truthy_env("DROIDBRIDGE_SIMPLEFPE_ACTIVE")) return NULL;
    if (g_droidbridge_simplefpe_handle != NULL) return g_droidbridge_simplefpe_handle;

    pthread_mutex_lock(&g_droidbridge_simplefpe_lock);
    if (g_droidbridge_simplefpe_handle == NULL
            && !g_droidbridge_simplefpe_load_attempted) {
        g_droidbridge_simplefpe_load_attempted = true;
        const char* path = getenv("DROIDBRIDGE_SIMPLEFPE_LIBRARY");
        if (path == NULL || path[0] == '\0') path = "libSimpleFPEWrapper.so";

        dlerror();
        g_droidbridge_simplefpe_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        const char* error = dlerror();
        if (g_droidbridge_simplefpe_handle != NULL) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "SimpleFPEWrapper loaded LOCAL handle=%p path=%s backend=%s",
                    g_droidbridge_simplefpe_handle,
                    path,
                    getenv("SFPEW_EGL") != NULL ? getenv("SFPEW_EGL") : "<default>");
            fprintf(stderr,
                    "DroidBridgeSimpleFPE: loaded LOCAL handle=%p path=%s backend=%s\n",
                    g_droidbridge_simplefpe_handle,
                    path,
                    getenv("SFPEW_EGL") != NULL ? getenv("SFPEW_EGL") : "<default>");
            fflush(stderr);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                    "SimpleFPEWrapper load failed path=%s error=%s",
                    path, error != NULL ? error : "unknown");
            fprintf(stderr,
                    "DroidBridgeSimpleFPE: load failed path=%s error=%s\n",
                    path, error != NULL ? error : "unknown");
            fflush(stderr);
        }
    }
    void* handle = g_droidbridge_simplefpe_handle;
    pthread_mutex_unlock(&g_droidbridge_simplefpe_lock);
    return handle;
}

static void* droidbridge_resolve_simplefpe_gl_symbol(const char* name) {
    if (!droidbridge_is_gl_symbol_name(name)) return NULL;
    void* handle = droidbridge_load_simplefpe();
    if (handle == NULL) return NULL;

    /* SimpleFPEWrapper owns its GL proc routing. Use its eglGetProcAddress first
     * so LWJGL and SDL receive the same wrapper trampoline for extension/core
     * functions. glXGetProcAddress itself is an ELF export and must be dlsym'd. */
    if (strcmp(name, "glXGetProcAddress") != 0
            && strcmp(name, "glXGetProcAddressARB") != 0) {
        db_egl_get_proc_address_fn get_proc =
                (db_egl_get_proc_address_fn)dlsym(handle, "eglGetProcAddress");
        if (get_proc != NULL) {
            void* symbol = get_proc(name);
            if (symbol != NULL) return symbol;
        }
    }
    return dlsym(handle, name);
}

static bool droidbridge_snapshot4_safe_buffers_enabled(void) {
    /*
     * Do not depend only on the Java-side marker. The v3 logs proved that
     * LWJGL can resolve the wrapped GL library before that marker is visible
     * to this linker hook. Wrapped SDL OpenGL is itself enough to identify the
     * Snapshot 4 path that must not expose persistent buffer storage.
     *
     * LTW is the exception. It implements coherent dynamic storage itself and
     * MJ's working 26.3 path exposes GL_ARB_buffer_storage. The Java launcher
     * also explicitly leaves DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING off
     * for LTW, so do not accidentally re-enable the old compatibility filter
     * merely because LTW participates in the wrapped SDL3 route.
     */
    if (droidbridge_truthy_env("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING")) {
        return true;
    }

    const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* library = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    const bool ltw = (kind != NULL && strcmp(kind, "ltw") == 0)
            || (renderer != NULL && strstr(renderer, "ltw") != NULL)
            || (library != NULL && strstr(library, "ltw") != NULL);
    if (ltw) return false;

    return droidbridge_truthy_env("DROIDBRIDGE_SDL3_WRAPPED_OPENGL")
            || droidbridge_truthy_env("DROIDBRIDGE_SDL3_MOBILEGLUES_OPENGL");
}

static bool droidbridge_is_krypton_wrapped_renderer(void) {
    const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    return kind != NULL && strcmp(kind, "krypton") == 0;
}

static bool droidbridge_is_ltw_renderer(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    if (renderer != NULL && strstr(renderer, "ltw") != NULL) return true;
    const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcmp(kind, "ltw") == 0) return true;
    const char* library = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    return library != NULL && strstr(library, "ltw") != NULL;
}

static bool droidbridge_is_vulkan_zink_renderer(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    if (renderer != NULL && strstr(renderer, "vulkan_zink") != NULL) return true;

    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    if (mesa_driver != NULL && strcmp(mesa_driver, "zink") == 0) return true;

    const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    return kind != NULL && strcmp(kind, "zink") == 0;
}

static bool droidbridge_renderpearl_requires_canonical_gl_procs(void) {
    return droidbridge_is_ltw_renderer() || droidbridge_is_vulkan_zink_renderer();
}

static void droidbridge_publish_openjdk_ltw_handle(void* handle) {
    if (handle == NULL || !droidbridge_is_ltw_renderer()) return;

    char handle_text[2 + sizeof(uintptr_t) * 2 + 1];
    snprintf(handle_text, sizeof(handle_text), "0x%llx",
             (unsigned long long)(uintptr_t)handle);
    setenv("DROIDBRIDGE_LTW_OPENJDK_HANDLE", handle_text, 1);

    const char* path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    if (path == NULL || path[0] == '\0') path = getenv("DROIDBRIDGE_RENDERSPEC_EGL");
    if (path == NULL || path[0] == '\0') path = getenv("DROIDBRIDGE_EGL");
    if (path != NULL && path[0] != '\0') {
        setenv("DROIDBRIDGE_LTW_OPENJDK_PATH", path, 1);
    }

    fprintf(stderr,
            "DroidBridgeSDL3GL: published OpenJDK LTW provider handle=%p path=%s\n",
            handle,
            path != NULL && path[0] != '\0' ? path : "<unknown>");
    fflush(stderr);
}

static bool droidbridge_is_blocked_buffer_storage_extension(const char* extension) {
    if (extension == NULL) return false;
    return strcmp(extension, DB_BLOCKED_BUFFER_STORAGE_ARB) == 0
            || strcmp(extension, DB_BLOCKED_BUFFER_STORAGE_EXT) == 0;
}

static bool droidbridge_is_blocked_buffer_storage_proc(const char* name) {
    if (name == NULL) return false;
    return strcmp(name, "glBufferStorage") == 0
            || strcmp(name, "glBufferStorageEXT") == 0
            || strcmp(name, "glNamedBufferStorage") == 0
            || strcmp(name, "glNamedBufferStorageEXT") == 0;
}

static void* droidbridge_resolve_gl_symbol(void* handle, const char* name) {
    if (handle == NULL || name == NULL) return NULL;

    if (droidbridge_freedreno_renderpearl_blit_compat_enabled()
            && strcmp(name, "glBlitNamedFramebuffer") == 0) {
        return (void*)&droidbridge_glBlitNamedFramebuffer_freedreno_compat;
    }

    /* Kopper keeps its Android EGL drawable at the physical TextureView size.
     * RenderPearl still presents using the logical resolution-scale dimensions,
     * so stretch only the final color blit to the live drawable. This does not
     * resize/recreate the native window and is deliberately Kopper-only; the
     * existing Vulkan Zink/OSMesa path remains untouched. */
    if (droidbridge_is_kopper_zink_renderer()
            && strcmp(name, "glBlitNamedFramebuffer") == 0) {
        return (void*)&droidbridge_glBlitNamedFramebuffer_kopper_scale;
    }
    if (droidbridge_is_kopper_zink_renderer()
            && strcmp(name, "glBlitFramebuffer") == 0) {
        return (void*)&droidbridge_glBlitFramebuffer_kopper_scale;
    }

    /*
     * SimpleFPEWrapper is the authoritative GL function provider whenever the
     * selected MobileGlues plugin ships it. Do NOT key this on one particular
     * dlopen handle. LWJGL can open the same renderer through the DroidBridge
     * sentinel, the renderer soname, or another equivalent handle; requiring
     * pointer identity lets those alternate paths fall straight through to
     * MobileGlues and leaves legacy desktop entry points (for example
     * glFogfv) unresolved.
     *
     * This does not expose SFPEW to MobileGlues' own dlsym(RTLD_DEFAULT,...):
     * SFPEW itself remains RTLD_LOCAL, and this function is only used by the
     * launcher/LWJGL resolver. MobileGlues' internal native dlsym calls never
     * pass through this JNI hook.
     */
    if (droidbridge_truthy_env("DROIDBRIDGE_SIMPLEFPE_ACTIVE")
            && droidbridge_is_gl_symbol_name(name)) {
        void* simplefpe = droidbridge_resolve_simplefpe_gl_symbol(name);
        if (simplefpe != NULL) return simplefpe;
    }

    /* RenderPearl 26.3 verifies that the OpenGL loader and SDL's
     * SDL_GL_GetProcAddress return the exact same function pointer for core
     * entry points such as glGetError. LTW and Mesa/Zink can both expose an
     * ELF export through dlsym while eglGetProcAddress returns the provider's
     * canonical dispatch trampoline. Prefer the EGL resolver for these wrapped
     * renderers so both sides of RenderPearl's identity check see one pointer. */
    db_egl_get_proc_address_fn get_proc =
            (db_egl_get_proc_address_fn)dlsym(handle, "eglGetProcAddress");
    if (droidbridge_renderpearl_requires_canonical_gl_procs() && get_proc != NULL) {
        void* via_egl = get_proc(name);
        if (via_egl != NULL) return via_egl;
    }

    void* symbol = dlsym(handle, name);
    if (symbol == NULL && get_proc != NULL) symbol = get_proc(name);
    return symbol;
}

/*
 * RenderPearl 26.3 presents its final color texture by blitting an off-screen
 * framebuffer into framebuffer 0. On Android Mesa/KGSL, both the DSA
 * glBlitNamedFramebuffer route and the equivalent bound glBlitFramebuffer
 * route eventually enter the same native-window drawable helper and fault at
 * libEGL_mesa.so+0x2dc34 after several frames.
 *
 * For the exact presentation case (color-only, destination framebuffer 0),
 * avoid framebuffer blitting altogether. The source framebuffer is backed by
 * RenderPearl's GpuTextureView, so bind that texture and draw a fullscreen
 * triangle into the active default framebuffer. Ordinary FBO-to-FBO blits
 * still use Mesa's real glBlitFramebuffer implementation.
 */
static void* droidbridge_present_resolve(const char* name) {
    void* handle = g_droidbridge_opengl_proxy_handle;
    if (handle == NULL || name == NULL) return NULL;
    return droidbridge_resolve_gl_symbol(handle, name);
}

/*
 * Query the drawable that Mesa is actually presenting to. Resolution scaling
 * changes Minecraft/RenderPearl's logical framebuffer without recreating the
 * Android EGL window surface on the direct-Freedreno route. The final present
 * therefore has to stretch the logical image to the live EGL drawable instead
 * of using RenderPearl's smaller destination rectangle as a physical viewport.
 *
 * This is read-only EGL state inspection. It never detaches, resizes, recreates,
 * or otherwise mutates the surface/context owner.
 */
static bool droidbridge_present_query_drawable_size(int* out_width, int* out_height) {
    if (out_width == NULL || out_height == NULL) return false;

    void* handle = g_droidbridge_opengl_proxy_handle;
    if (handle == NULL) return false;

    if (g_present_eglGetCurrentDisplay == NULL) {
        g_present_eglGetCurrentDisplay = (db_egl_get_current_display_fn)
                dlsym(handle, "eglGetCurrentDisplay");
    }
    if (g_present_eglGetCurrentSurface == NULL) {
        g_present_eglGetCurrentSurface = (db_egl_get_current_surface_fn)
                dlsym(handle, "eglGetCurrentSurface");
    }
    if (g_present_eglQuerySurface == NULL) {
        g_present_eglQuerySurface = (db_egl_query_surface_fn)
                dlsym(handle, "eglQuerySurface");
    }
    if (g_present_eglGetCurrentDisplay == NULL
            || g_present_eglGetCurrentSurface == NULL
            || g_present_eglQuerySurface == NULL) {
        return false;
    }

    void* display = g_present_eglGetCurrentDisplay();
    void* surface = g_present_eglGetCurrentSurface(DB_EGL_DRAW);
    if (display == NULL || surface == NULL) return false;

    int width = 0;
    int height = 0;
    if (!g_present_eglQuerySurface(display, surface, DB_EGL_WIDTH, &width)
            || !g_present_eglQuerySurface(display, surface, DB_EGL_HEIGHT, &height)
            || width <= 0 || height <= 0) {
        return false;
    }

    *out_width = width;
    *out_height = height;
    return true;
}

/*
 * Do not manipulate Mesa EGLSurface ownership directly here. The bridge owns
 * presentation lifecycle differently depending on route: SDL on the original
 * 26.3 path, NativeGLFW on the current direct-Freedreno path. Reassert through
 * the bridge's owner helper so the fullscreen presenter never detaches or
 * recreates EGL state behind the active owner.
 */
extern int droidbridge_sdl3_reassert_current_gl_context(const char* reason);

static bool droidbridge_present_reassert_current_sdl_context(void) {
    static bool logged_success = false;
    static bool logged_failure = false;

    int ok = droidbridge_sdl3_reassert_current_gl_context(
            "RenderPearl default-FBO present");
    if (!ok) {
        if (!logged_failure) {
            logged_failure = true;
            fprintf(stderr,
                    "DroidBridgeFreedrenoPresent: drawable-owner reassert FAILED; present suppressed to avoid stale Mesa drawable\n");
            fflush(stderr);
        }
        return false;
    }

    if (!logged_success) {
        logged_success = true;
        fprintf(stderr,
                "DroidBridgeFreedrenoPresent: 26.3+ drawable-owner reassert active\n");
        fflush(stderr);
    }
    return true;
}

static bool droidbridge_present_resolve_functions(void) {
    db_freedreno_present_draw_state* st = &g_freedreno_present_draw;
    if (st->resolved) return true;

#define DB_PRESENT_RESOLVE(field, type, symbol) \
    do { \
        st->field = (type)droidbridge_present_resolve(symbol); \
        if (st->field == NULL) return false; \
    } while (0)

    DB_PRESENT_RESOLVE(glGetBooleanv, db_gl_get_booleanv_fn, "glGetBooleanv");
    DB_PRESENT_RESOLVE(glGetFramebufferAttachmentParameteriv,
            db_gl_get_framebuffer_attachment_parameter_iv_fn,
            "glGetFramebufferAttachmentParameteriv");
    DB_PRESENT_RESOLVE(glActiveTexture, db_gl_active_texture_fn, "glActiveTexture");
    DB_PRESENT_RESOLVE(glBindTexture, db_gl_bind_texture_fn, "glBindTexture");
    DB_PRESENT_RESOLVE(glUseProgram, db_gl_use_program_fn, "glUseProgram");
    DB_PRESENT_RESOLVE(glCreateShader, db_gl_create_shader_fn, "glCreateShader");
    DB_PRESENT_RESOLVE(glShaderSource, db_gl_shader_source_fn, "glShaderSource");
    DB_PRESENT_RESOLVE(glCompileShader, db_gl_compile_shader_fn, "glCompileShader");
    DB_PRESENT_RESOLVE(glGetShaderiv, db_gl_get_shader_iv_fn, "glGetShaderiv");
    DB_PRESENT_RESOLVE(glGetShaderInfoLog, db_gl_get_shader_info_log_fn, "glGetShaderInfoLog");
    DB_PRESENT_RESOLVE(glCreateProgram, db_gl_create_program_fn, "glCreateProgram");
    DB_PRESENT_RESOLVE(glAttachShader, db_gl_attach_shader_fn, "glAttachShader");
    DB_PRESENT_RESOLVE(glLinkProgram, db_gl_link_program_fn, "glLinkProgram");
    DB_PRESENT_RESOLVE(glGetProgramiv, db_gl_get_program_iv_fn, "glGetProgramiv");
    DB_PRESENT_RESOLVE(glGetProgramInfoLog, db_gl_get_program_info_log_fn, "glGetProgramInfoLog");
    DB_PRESENT_RESOLVE(glDeleteShader, db_gl_delete_shader_fn, "glDeleteShader");
    DB_PRESENT_RESOLVE(glGetUniformLocation, db_gl_get_uniform_location_fn, "glGetUniformLocation");
    DB_PRESENT_RESOLVE(glUniform1i, db_gl_uniform_1i_fn, "glUniform1i");
    DB_PRESENT_RESOLVE(glUniform4f, db_gl_uniform_4f_fn, "glUniform4f");
    DB_PRESENT_RESOLVE(glGenVertexArrays, db_gl_gen_vertex_arrays_fn, "glGenVertexArrays");
    DB_PRESENT_RESOLVE(glBindVertexArray, db_gl_bind_vertex_array_fn, "glBindVertexArray");
    DB_PRESENT_RESOLVE(glDrawArrays, db_gl_draw_arrays_fn, "glDrawArrays");
    DB_PRESENT_RESOLVE(glViewport, db_gl_viewport_fn, "glViewport");
    DB_PRESENT_RESOLVE(glColorMask, db_gl_color_mask_fn, "glColorMask");
#undef DB_PRESENT_RESOLVE

    if (g_real_glGetIntegerv == NULL) {
        g_real_glGetIntegerv = (db_gl_get_integerv_fn)
                droidbridge_present_resolve("glGetIntegerv");
    }
    if (g_real_glBindFramebuffer == NULL) {
        g_real_glBindFramebuffer = (db_gl_bind_framebuffer_fn)
                droidbridge_present_resolve("glBindFramebuffer");
    }
    if (g_real_glBlitFramebuffer == NULL) {
        g_real_glBlitFramebuffer = (db_gl_blit_framebuffer_fn)
                droidbridge_present_resolve("glBlitFramebuffer");
    }
    if (g_real_glEnable == NULL) {
        g_real_glEnable = (db_gl_enable_fn)droidbridge_present_resolve("glEnable");
    }
    if (g_real_glDisable == NULL) {
        g_real_glDisable = (db_gl_disable_fn)droidbridge_present_resolve("glDisable");
    }
    if (g_real_glIsEnabled == NULL) {
        g_real_glIsEnabled = (db_gl_is_enabled_fn)droidbridge_present_resolve("glIsEnabled");
    }

    if (g_real_glGetIntegerv == NULL
            || g_real_glBindFramebuffer == NULL
            || g_real_glBlitFramebuffer == NULL
            || g_real_glEnable == NULL
            || g_real_glDisable == NULL
            || g_real_glIsEnabled == NULL) {
        return false;
    }

    st->resolved = true;
    return true;
}

static bool droidbridge_present_compile_shader(
        DB_GLuint shader, const char* source, const char* label) {
    db_freedreno_present_draw_state* st = &g_freedreno_present_draw;
    st->glShaderSource(shader, 1, &source, NULL);
    st->glCompileShader(shader);

    int ok = 0;
    st->glGetShaderiv(shader, DB_GL_COMPILE_STATUS, &ok);
    if (ok) return true;

    int logLength = 0;
    st->glGetShaderiv(shader, DB_GL_INFO_LOG_LENGTH, &logLength);
    if (logLength < 1) logLength = 1;
    if (logLength > 4096) logLength = 4096;
    char logBuffer[4096];
    int written = 0;
    logBuffer[0] = '\0';
    st->glGetShaderInfoLog(shader, logLength, &written, logBuffer);
    fprintf(stderr,
            "DroidBridgeFreedrenoPresent: fullscreen %s shader compile failed: %s\n",
            label, logBuffer);
    fflush(stderr);
    return false;
}

static bool droidbridge_present_initialize(void) {
    db_freedreno_present_draw_state* st = &g_freedreno_present_draw;
    if (st->initialized) return true;
    if (!droidbridge_present_resolve_functions()) return false;

    static const char* vertexSource =
            "#version 330 core\n"
            "out vec2 dbUV;\n"
            "void main() {\n"
            "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
            "  dbUV = p;\n"
            "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
            "}\n";

    static const char* fragmentSource =
            "#version 330 core\n"
            "uniform sampler2D dbSource;\n"
            "uniform vec4 dbSourceRect;\n"
            "in vec2 dbUV;\n"
            "out vec4 dbColor;\n"
            "void main() {\n"
            "  ivec2 ts = textureSize(dbSource, 0);\n"
            "  vec2 src = mix(dbSourceRect.xy, dbSourceRect.zw, dbUV);\n"
            "  ivec2 p = ivec2(floor(src));\n"
            "  p = clamp(p, ivec2(0), max(ts - ivec2(1), ivec2(0)));\n"
            "  dbColor = texelFetch(dbSource, p, 0);\n"
            "}\n";

    DB_GLuint vertex = st->glCreateShader(DB_GL_VERTEX_SHADER);
    DB_GLuint fragment = st->glCreateShader(DB_GL_FRAGMENT_SHADER);
    if (vertex == 0 || fragment == 0) return false;

    if (!droidbridge_present_compile_shader(vertex, vertexSource, "vertex")
            || !droidbridge_present_compile_shader(fragment, fragmentSource, "fragment")) {
        st->glDeleteShader(vertex);
        st->glDeleteShader(fragment);
        return false;
    }

    DB_GLuint program = st->glCreateProgram();
    if (program == 0) {
        st->glDeleteShader(vertex);
        st->glDeleteShader(fragment);
        return false;
    }

    st->glAttachShader(program, vertex);
    st->glAttachShader(program, fragment);
    st->glLinkProgram(program);

    int linked = 0;
    st->glGetProgramiv(program, DB_GL_LINK_STATUS, &linked);
    if (!linked) {
        int logLength = 0;
        st->glGetProgramiv(program, DB_GL_INFO_LOG_LENGTH, &logLength);
        if (logLength < 1) logLength = 1;
        if (logLength > 4096) logLength = 4096;
        char logBuffer[4096];
        int written = 0;
        logBuffer[0] = '\0';
        st->glGetProgramInfoLog(program, logLength, &written, logBuffer);
        fprintf(stderr,
                "DroidBridgeFreedrenoPresent: fullscreen program link failed: %s\n",
                logBuffer);
        fflush(stderr);
        st->glDeleteShader(vertex);
        st->glDeleteShader(fragment);
        return false;
    }

    st->glDeleteShader(vertex);
    st->glDeleteShader(fragment);

    DB_GLuint vao = 0;
    st->glGenVertexArrays(1, &vao);
    if (vao == 0) return false;

    st->program = program;
    st->vao = vao;
    st->sourceRectLocation = st->glGetUniformLocation(program, "dbSourceRect");

    int sourceLocation = st->glGetUniformLocation(program, "dbSource");
    int previousProgram = 0;
    g_real_glGetIntegerv(DB_GL_CURRENT_PROGRAM, &previousProgram);
    st->glUseProgram(program);
    if (sourceLocation >= 0) st->glUniform1i(sourceLocation, 0);
    st->glUseProgram((DB_GLuint)previousProgram);

    st->initialized = true;
    fprintf(stderr,
            "DroidBridgeFreedrenoPresent: 26.3+ fullscreen texture presenter initialized program=%u vao=%u\n",
            st->program, st->vao);
    fflush(stderr);
    return true;
}

static void droidbridge_restore_capability(DB_GLenum cap, unsigned char enabled) {
    if (enabled) {
        g_real_glEnable(cap);
    } else {
        g_real_glDisable(cap);
    }
}

static bool droidbridge_present_texture_to_default(
        DB_GLuint readFramebuffer,
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1) {
    db_freedreno_present_draw_state* st = &g_freedreno_present_draw;
    if (!droidbridge_present_initialize()) return false;

    /* The first fullscreen present is known to succeed.  PID 19227 proves the
     * stale drawable appears only after that frame has been swapped.  Refresh
     * Mesa's current EGL/DRI drawable before every subsequent default-FBO
     * touch.  If the refresh cannot be completed, suppress this present rather
     * than intentionally re-entering the known-crashing drawable. */
    if (g_droidbridge_logged_freedreno_present_draw
            && !droidbridge_present_reassert_current_sdl_context()) {
        return true;
    }

    int previousRead = 0;
    int previousDraw = 0;
    int previousProgram = 0;
    int previousVao = 0;
    int previousActiveTexture = 0;
    int previousUnit0Texture = 0;
    int previousViewport[4] = {0, 0, 0, 0};
    unsigned char previousColorMask[4] = {1, 1, 1, 1};

    g_real_glGetIntegerv(DB_GL_READ_FRAMEBUFFER_BINDING, &previousRead);
    g_real_glGetIntegerv(DB_GL_DRAW_FRAMEBUFFER_BINDING, &previousDraw);
    g_real_glGetIntegerv(DB_GL_CURRENT_PROGRAM, &previousProgram);
    g_real_glGetIntegerv(DB_GL_VERTEX_ARRAY_BINDING, &previousVao);
    g_real_glGetIntegerv(DB_GL_ACTIVE_TEXTURE, &previousActiveTexture);
    g_real_glGetIntegerv(DB_GL_VIEWPORT, previousViewport);
    st->glGetBooleanv(DB_GL_COLOR_WRITEMASK, previousColorMask);

    unsigned char scissorEnabled = g_real_glIsEnabled(DB_GL_SCISSOR_TEST);
    unsigned char blendEnabled = g_real_glIsEnabled(DB_GL_BLEND);
    unsigned char depthEnabled = g_real_glIsEnabled(DB_GL_DEPTH_TEST);
    unsigned char stencilEnabled = g_real_glIsEnabled(DB_GL_STENCIL_TEST);
    unsigned char cullEnabled = g_real_glIsEnabled(DB_GL_CULL_FACE);
    unsigned char rasterizerDiscardEnabled = g_real_glIsEnabled(DB_GL_RASTERIZER_DISCARD);

    g_real_glBindFramebuffer(DB_GL_READ_FRAMEBUFFER, readFramebuffer);

    int readBuffer = 0;
    g_real_glGetIntegerv(DB_GL_READ_BUFFER, &readBuffer);
    DB_GLenum attachment = DB_GL_COLOR_ATTACHMENT0;
    if (readBuffer >= (int)DB_GL_COLOR_ATTACHMENT0
            && readBuffer < (int)(DB_GL_COLOR_ATTACHMENT0 + 32u)) {
        attachment = (DB_GLenum)readBuffer;
    }

    int objectType = 0;
    int sourceTexture = 0;
    st->glGetFramebufferAttachmentParameteriv(
            DB_GL_READ_FRAMEBUFFER,
            attachment,
            DB_GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,
            &objectType);
    st->glGetFramebufferAttachmentParameteriv(
            DB_GL_READ_FRAMEBUFFER,
            attachment,
            DB_GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,
            &sourceTexture);

    if (objectType != (int)DB_GL_TEXTURE || sourceTexture <= 0) {
        if (!g_droidbridge_logged_freedreno_present_draw_failure) {
            g_droidbridge_logged_freedreno_present_draw_failure = true;
            fprintf(stderr,
                    "DroidBridgeFreedrenoPresent: fullscreen presenter unavailable read=%u readBuffer=0x%x attachment=0x%x type=0x%x object=%d; skipping default-FBO blit to avoid Mesa drawable crash\n",
                    readFramebuffer, readBuffer, attachment, objectType, sourceTexture);
            fflush(stderr);
        }
        g_real_glBindFramebuffer(DB_GL_READ_FRAMEBUFFER, (DB_GLuint)previousRead);
        g_real_glBindFramebuffer(DB_GL_DRAW_FRAMEBUFFER, (DB_GLuint)previousDraw);
        return true;
    }

    st->glActiveTexture(DB_GL_TEXTURE0);
    g_real_glGetIntegerv(DB_GL_TEXTURE_BINDING_2D, &previousUnit0Texture);
    st->glBindTexture(DB_GL_TEXTURE_2D, (DB_GLuint)sourceTexture);

    g_real_glBindFramebuffer(DB_GL_DRAW_FRAMEBUFFER, 0);

    int dstMinX = dstX0 < dstX1 ? dstX0 : dstX1;
    int dstMinY = dstY0 < dstY1 ? dstY0 : dstY1;
    int dstWidth = dstX1 >= dstX0 ? (dstX1 - dstX0) : (dstX0 - dstX1);
    int dstHeight = dstY1 >= dstY0 ? (dstY1 - dstY0) : (dstY0 - dstY1);
    if (dstWidth < 1) dstWidth = 1;
    if (dstHeight < 1) dstHeight = 1;

    int presentX = dstMinX;
    int presentY = dstMinY;
    int presentWidth = dstWidth;
    int presentHeight = dstHeight;

    /*
     * RenderPearl describes the destination in its logical framebuffer space.
     * When DroidBridge resolution scaling is below 100%, the NativeGLFW EGL
     * window surface intentionally remains at the stable Android size. Using the
     * logical 960x540 destination as an EGL-surface viewport leaves the old
     * 1920x1080 image visible around it. For a full-frame present, stretch to the
     * real current EGL drawable while leaving Minecraft's render resolution alone.
     */
    int srcWidth = srcX1 >= srcX0 ? (srcX1 - srcX0) : (srcX0 - srcX1);
    int srcHeight = srcY1 >= srcY0 ? (srcY1 - srcY0) : (srcY0 - srcY1);
    bool fullFramePresent = dstMinX == 0
            && dstMinY == 0
            && srcWidth > 0
            && srcHeight > 0
            && dstWidth == srcWidth
            && dstHeight == srcHeight;
    int drawableWidth = 0;
    int drawableHeight = 0;
    if (fullFramePresent
            && droidbridge_present_query_drawable_size(&drawableWidth, &drawableHeight)) {
        presentX = 0;
        presentY = 0;
        presentWidth = drawableWidth;
        presentHeight = drawableHeight;

        if (dstWidth != g_present_last_logical_width
                || dstHeight != g_present_last_logical_height
                || drawableWidth != g_present_last_drawable_width
                || drawableHeight != g_present_last_drawable_height) {
            g_present_last_logical_width = dstWidth;
            g_present_last_logical_height = dstHeight;
            g_present_last_drawable_width = drawableWidth;
            g_present_last_drawable_height = drawableHeight;
            fprintf(stderr,
                    "DroidBridgeFreedrenoPresent: presentation viewport logical=%dx%d drawable=%dx%d\n",
                    dstWidth, dstHeight, drawableWidth, drawableHeight);
            fflush(stderr);
        }
    }

    st->glViewport(presentX, presentY, presentWidth, presentHeight);
    g_real_glDisable(DB_GL_SCISSOR_TEST);
    g_real_glDisable(DB_GL_BLEND);
    g_real_glDisable(DB_GL_DEPTH_TEST);
    g_real_glDisable(DB_GL_STENCIL_TEST);
    g_real_glDisable(DB_GL_CULL_FACE);
    g_real_glDisable(DB_GL_RASTERIZER_DISCARD);
    st->glColorMask(1, 1, 1, 1);

    st->glUseProgram(st->program);
    st->glBindVertexArray(st->vao);
    if (st->sourceRectLocation >= 0) {
        st->glUniform4f(
                st->sourceRectLocation,
                (float)srcX0, (float)srcY0,
                (float)srcX1, (float)srcY1);
    }
    st->glDrawArrays(DB_GL_TRIANGLES, 0, 3);

    st->glBindVertexArray((DB_GLuint)previousVao);
    st->glUseProgram((DB_GLuint)previousProgram);

    st->glColorMask(
            previousColorMask[0], previousColorMask[1],
            previousColorMask[2], previousColorMask[3]);
    droidbridge_restore_capability(DB_GL_SCISSOR_TEST, scissorEnabled);
    droidbridge_restore_capability(DB_GL_BLEND, blendEnabled);
    droidbridge_restore_capability(DB_GL_DEPTH_TEST, depthEnabled);
    droidbridge_restore_capability(DB_GL_STENCIL_TEST, stencilEnabled);
    droidbridge_restore_capability(DB_GL_CULL_FACE, cullEnabled);
    droidbridge_restore_capability(DB_GL_RASTERIZER_DISCARD, rasterizerDiscardEnabled);
    st->glViewport(
            previousViewport[0], previousViewport[1],
            previousViewport[2], previousViewport[3]);

    st->glBindTexture(DB_GL_TEXTURE_2D, (DB_GLuint)previousUnit0Texture);
    st->glActiveTexture((DB_GLenum)previousActiveTexture);

    g_real_glBindFramebuffer(DB_GL_READ_FRAMEBUFFER, (DB_GLuint)previousRead);
    g_real_glBindFramebuffer(DB_GL_DRAW_FRAMEBUFFER, (DB_GLuint)previousDraw);

    if (!g_droidbridge_logged_freedreno_present_draw) {
        g_droidbridge_logged_freedreno_present_draw = true;
        fprintf(stderr,
                "DroidBridgeFreedrenoPresent: 26.3+ default-FBO blit replaced by fullscreen texture draw read=%u texture=%d attachment=0x%x src=%d,%d-%d,%d dst=%d,%d-%d,%d\n",
                readFramebuffer, sourceTexture, attachment,
                srcX0, srcY0, srcX1, srcY1,
                dstX0, dstY0, dstX1, dstY1);
        fflush(stderr);
    }
    return true;
}

static void droidbridge_glBlitNamedFramebuffer_kopper_scale(
        DB_GLuint readFramebuffer, DB_GLuint drawFramebuffer,
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1,
        unsigned int mask, DB_GLenum filter) {
    if (g_real_glBlitNamedFramebufferKopper == NULL) {
        void* handle = g_droidbridge_opengl_proxy_handle;
        if (handle != NULL) {
            db_egl_get_proc_address_fn get_proc =
                    (db_egl_get_proc_address_fn)dlsym(handle, "eglGetProcAddress");
            if (get_proc != NULL) {
                g_real_glBlitNamedFramebufferKopper =
                        (db_gl_blit_named_framebuffer_fn)get_proc("glBlitNamedFramebuffer");
            }
            if (g_real_glBlitNamedFramebufferKopper == NULL) {
                g_real_glBlitNamedFramebufferKopper =
                        (db_gl_blit_named_framebuffer_fn)dlsym(handle, "glBlitNamedFramebuffer");
            }
        }
    }

    if (g_real_glBlitNamedFramebufferKopper == NULL) {
        fprintf(stderr,
                "DroidBridgeKopperPresentScale: real glBlitNamedFramebuffer unavailable; present suppressed\n");
        fflush(stderr);
        return;
    }

    int outDstX0 = dstX0;
    int outDstY0 = dstY0;
    int outDstX1 = dstX1;
    int outDstY1 = dstY1;

    const int srcWidth = srcX1 >= srcX0 ? (srcX1 - srcX0) : (srcX0 - srcX1);
    const int srcHeight = srcY1 >= srcY0 ? (srcY1 - srcY0) : (srcY0 - srcY1);
    const int dstWidth = dstX1 >= dstX0 ? (dstX1 - dstX0) : (dstX0 - dstX1);
    const int dstHeight = dstY1 >= dstY0 ? (dstY1 - dstY0) : (dstY0 - dstY1);
    const int dstMinX = dstX0 < dstX1 ? dstX0 : dstX1;
    const int dstMinY = dstY0 < dstY1 ? dstY0 : dstY1;

    const bool fullFramePresent = drawFramebuffer == 0
            && mask == DB_GL_COLOR_BUFFER_BIT
            && dstMinX == 0
            && dstMinY == 0
            && srcWidth > 0
            && srcHeight > 0
            && dstWidth == srcWidth
            && dstHeight == srcHeight;

    int drawableWidth = 0;
    int drawableHeight = 0;
    if (fullFramePresent
            && droidbridge_present_query_drawable_size(&drawableWidth, &drawableHeight)
            && drawableWidth > 0
            && drawableHeight > 0) {
        outDstX0 = dstX0 <= dstX1 ? 0 : drawableWidth;
        outDstX1 = dstX0 <= dstX1 ? drawableWidth : 0;
        outDstY0 = dstY0 <= dstY1 ? 0 : drawableHeight;
        outDstY1 = dstY0 <= dstY1 ? drawableHeight : 0;

        if (!g_droidbridge_logged_kopper_present_scale
                || dstWidth != g_present_last_logical_width
                || dstHeight != g_present_last_logical_height
                || drawableWidth != g_present_last_drawable_width
                || drawableHeight != g_present_last_drawable_height) {
            g_droidbridge_logged_kopper_present_scale = true;
            g_present_last_logical_width = dstWidth;
            g_present_last_logical_height = dstHeight;
            g_present_last_drawable_width = drawableWidth;
            g_present_last_drawable_height = drawableHeight;
            fprintf(stderr,
                    "DroidBridgeKopperPresentScale: final blit logical=%dx%d drawable=%dx%d filter=0x%x nativeWindowResize=false\n",
                    dstWidth, dstHeight, drawableWidth, drawableHeight, filter);
            fflush(stderr);
        }
    }

    g_real_glBlitNamedFramebufferKopper(
            readFramebuffer, drawFramebuffer,
            srcX0, srcY0, srcX1, srcY1,
            outDstX0, outDstY0, outDstX1, outDstY1,
            mask, filter);
}

static void droidbridge_glBlitFramebuffer_kopper_scale(
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1,
        unsigned int mask, DB_GLenum filter) {
    if (g_real_glBlitFramebufferKopper == NULL || g_real_glGetIntegervKopper == NULL) {
        void* handle = g_droidbridge_opengl_proxy_handle;
        if (handle != NULL) {
            db_egl_get_proc_address_fn get_proc =
                    (db_egl_get_proc_address_fn)dlsym(handle, "eglGetProcAddress");
            if (get_proc != NULL) {
                if (g_real_glBlitFramebufferKopper == NULL) {
                    g_real_glBlitFramebufferKopper =
                            (db_gl_blit_framebuffer_fn)get_proc("glBlitFramebuffer");
                }
                if (g_real_glGetIntegervKopper == NULL) {
                    g_real_glGetIntegervKopper =
                            (db_gl_get_integerv_fn)get_proc("glGetIntegerv");
                }
            }
            if (g_real_glBlitFramebufferKopper == NULL) {
                g_real_glBlitFramebufferKopper =
                        (db_gl_blit_framebuffer_fn)dlsym(handle, "glBlitFramebuffer");
            }
            if (g_real_glGetIntegervKopper == NULL) {
                g_real_glGetIntegervKopper =
                        (db_gl_get_integerv_fn)dlsym(handle, "glGetIntegerv");
            }
        }
    }

    if (g_real_glBlitFramebufferKopper == NULL) {
        fprintf(stderr,
                "DroidBridgeKopperPresentScale: real glBlitFramebuffer unavailable; present suppressed\n");
        fflush(stderr);
        return;
    }

    int outDstX0 = dstX0;
    int outDstY0 = dstY0;
    int outDstX1 = dstX1;
    int outDstY1 = dstY1;

    int drawFramebuffer = -1;
    if (g_real_glGetIntegervKopper != NULL) {
        g_real_glGetIntegervKopper(DB_GL_DRAW_FRAMEBUFFER_BINDING, &drawFramebuffer);
    }

    const int srcWidth = srcX1 >= srcX0 ? (srcX1 - srcX0) : (srcX0 - srcX1);
    const int srcHeight = srcY1 >= srcY0 ? (srcY1 - srcY0) : (srcY0 - srcY1);
    const int dstWidth = dstX1 >= dstX0 ? (dstX1 - dstX0) : (dstX0 - dstX1);
    const int dstHeight = dstY1 >= dstY0 ? (dstY1 - dstY0) : (dstY0 - dstY1);
    const int dstMinX = dstX0 < dstX1 ? dstX0 : dstX1;
    const int dstMinY = dstY0 < dstY1 ? dstY0 : dstY1;

    const bool fullFramePresent = drawFramebuffer == 0
            && mask == DB_GL_COLOR_BUFFER_BIT
            && dstMinX == 0
            && dstMinY == 0
            && srcWidth > 0
            && srcHeight > 0
            && dstWidth == srcWidth
            && dstHeight == srcHeight;

    int drawableWidth = 0;
    int drawableHeight = 0;
    if (fullFramePresent
            && droidbridge_present_query_drawable_size(&drawableWidth, &drawableHeight)
            && drawableWidth > 0
            && drawableHeight > 0) {
        outDstX0 = dstX0 <= dstX1 ? 0 : drawableWidth;
        outDstX1 = dstX0 <= dstX1 ? drawableWidth : 0;
        outDstY0 = dstY0 <= dstY1 ? 0 : drawableHeight;
        outDstY1 = dstY0 <= dstY1 ? drawableHeight : 0;

        if (!g_droidbridge_logged_kopper_present_scale
                || dstWidth != g_present_last_logical_width
                || dstHeight != g_present_last_logical_height
                || drawableWidth != g_present_last_drawable_width
                || drawableHeight != g_present_last_drawable_height) {
            g_droidbridge_logged_kopper_present_scale = true;
            g_present_last_logical_width = dstWidth;
            g_present_last_logical_height = dstHeight;
            g_present_last_drawable_width = drawableWidth;
            g_present_last_drawable_height = drawableHeight;
            fprintf(stderr,
                    "DroidBridgeKopperPresentScale: bound final blit logical=%dx%d drawable=%dx%d filter=0x%x nativeWindowResize=false\n",
                    dstWidth, dstHeight, drawableWidth, drawableHeight, filter);
            fflush(stderr);
        }
    }

    g_real_glBlitFramebufferKopper(
            srcX0, srcY0, srcX1, srcY1,
            outDstX0, outDstY0, outDstX1, outDstY1,
            mask, filter);
}

static void droidbridge_glBlitNamedFramebuffer_freedreno_compat(
        DB_GLuint readFramebuffer, DB_GLuint drawFramebuffer,
        int srcX0, int srcY0, int srcX1, int srcY1,
        int dstX0, int dstY0, int dstX1, int dstY1,
        unsigned int mask, DB_GLenum filter) {
    if (!droidbridge_present_resolve_functions()) {
        fprintf(stderr,
                "DroidBridgeFreedrenoPresent: present compatibility functions unavailable; named blit suppressed to avoid Mesa default-drawable crash\n");
        fflush(stderr);
        return;
    }

    if (drawFramebuffer == 0
            && mask == DB_GL_COLOR_BUFFER_BIT
            && droidbridge_present_texture_to_default(
                    readFramebuffer,
                    srcX0, srcY0, srcX1, srcY1,
                    dstX0, dstY0, dstX1, dstY1)) {
        return;
    }

    int previousRead = 0;
    int previousDraw = 0;
    g_real_glGetIntegerv(DB_GL_READ_FRAMEBUFFER_BINDING, &previousRead);
    g_real_glGetIntegerv(DB_GL_DRAW_FRAMEBUFFER_BINDING, &previousDraw);

    if (!g_droidbridge_logged_freedreno_dsa_blit) {
        g_droidbridge_logged_freedreno_dsa_blit = true;
        fprintf(stderr,
                "DroidBridgeFreedrenoPresent: non-present DSA blit translated to bound blit read=%u draw=%u mask=0x%x filter=0x%x\n",
                readFramebuffer, drawFramebuffer, mask, filter);
        fflush(stderr);
    }

    g_real_glBindFramebuffer(DB_GL_READ_FRAMEBUFFER, readFramebuffer);
    g_real_glBindFramebuffer(DB_GL_DRAW_FRAMEBUFFER, drawFramebuffer);
    g_real_glBlitFramebuffer(
            srcX0, srcY0, srcX1, srcY1,
            dstX0, dstY0, dstX1, dstY1,
            mask, filter);
    g_real_glBindFramebuffer(DB_GL_READ_FRAMEBUFFER, (DB_GLuint)previousRead);
    g_real_glBindFramebuffer(DB_GL_DRAW_FRAMEBUFFER, (DB_GLuint)previousDraw);
}

/*
 * Share the exact OpenGL provider selected by LWJGL with the SDL3 bridge.
 * Both source files are linked into libdroidbridge_runtime.so, so this avoids
 * reopening Mesa/OSMesa from another linker namespace and guarantees pointer
 * identity for RenderPearl's glGetError sanity check.
 */
__attribute__((visibility("default")))
void* droidbridge_resolve_opengl_proxy_symbol(const char* name) {
    if (g_droidbridge_opengl_proxy_handle == NULL || name == NULL || name[0] == '\0') {
        return NULL;
    }
    return droidbridge_resolve_gl_symbol(g_droidbridge_opengl_proxy_handle, name);
}

static const DB_GLubyte* droidbridge_glGetString_compat_filter(DB_GLenum name);
static const DB_GLubyte* droidbridge_glGetStringi_compat_filter(DB_GLenum name, DB_GLuint index);

/*
 * Kopper presents desktop OpenGL through Mesa EGL on Android, but LWJGL's
 * Linux OpenGL loader only probes glXGetProcAddress/glXGetProcAddressARB (or
 * OSMesaGetProcAddress). FCL normally satisfies that contract with
 * libglxshim.so. DroidBridge already owns the DynamicLinkLoader hook and can
 * provide the same proc-address contract directly: resolve GL entry points
 * through the active Mesa EGL handle's eglGetProcAddress.
 *
 * This intentionally has a GLX-compatible signature without depending on GLX
 * headers, keeping the Android native build free of X11/GLX linkage.
 */
static void* droidbridge_glx_get_proc_address_egl(const unsigned char* proc_name) {
    if (proc_name == NULL || proc_name[0] == '\0') return NULL;

    void* handle = g_droidbridge_opengl_proxy_handle;
    if (handle == NULL) return NULL;

    const char* name = (const char*)proc_name;
    void* symbol = droidbridge_resolve_gl_symbol(handle, name);
    if (symbol == NULL) return NULL;

    /* Modern LWJGL often resolves desktop GL through the synthesized
     * glXGetProcAddress adapter rather than ndlsym(). Route glGetString through
     * the same compatibility/reporting filter so Minecraft F3 can show the
     * selected Vulkan ICD underneath Zink instead of only Mesa's Zink version. */
    if (strcmp(name, "glGetString") == 0) {
        g_real_glGetString = (db_gl_get_string_fn)symbol;
        return (void*)&droidbridge_glGetString_compat_filter;
    }
    if (droidbridge_snapshot4_safe_buffers_enabled()
            && strcmp(name, "glGetStringi") == 0) {
        g_real_glGetStringi = (db_gl_get_string_i_fn)symbol;
        return (void*)&droidbridge_glGetStringi_compat_filter;
    }
    return symbol;
}


static bool droidbridge_is_mobileglues_wrapped_renderer(void) {
    if (droidbridge_truthy_env("DROIDBRIDGE_SIMPLEFPE_ACTIVE")) return true;
    const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcmp(kind, "mobileglues") == 0) return true;
    const char* library = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    return library != NULL && strstr(library, "mobileglues") != NULL;
}

static bool droidbridge_is_wrapped_context_guard_renderer(void) {
    return droidbridge_is_krypton_wrapped_renderer()
            || droidbridge_is_mobileglues_wrapped_renderer();
}

/* Minecraft 26+ maps transient staging buffers even when persistent mapping is
 * filtered out. MobileGlues can return NULL for these staging maps and needs the
 * CPU shadow-map fallback. Krypton must keep LWJGL's original native mapping
 * path: registering the replacement JNI methods globally makes both 1.20.1 and
 * 1.21.11 fail with "Can't map buffer, opengl error 0" even when Krypton reports
 * no GL error. Keep the fallback MobileGlues-only. */
static bool droidbridge_is_shadow_map_fallback_renderer(void) {
    return droidbridge_is_mobileglues_wrapped_renderer();
}

static const char* droidbridge_wrapped_renderer_name(void) {
    return droidbridge_is_mobileglues_wrapped_renderer()
            ? "MobileGlues" : "Krypton";
}

static void droidbridge_ensure_wrapped_context(const char* reason);


static bool droidbridge_mobileglues_framebuffer_srgb_workaround_enabled(void) {
    return droidbridge_is_mobileglues_wrapped_renderer()
            && droidbridge_truthy_env(
                    "DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB");
}

static void droidbridge_glEnable_mobileglues_filter(DB_GLenum cap) {
    if (cap == DB_GL_FRAMEBUFFER_SRGB
            && droidbridge_mobileglues_framebuffer_srgb_workaround_enabled()) {
        if (g_real_glDisable != NULL) g_real_glDisable(cap);
        if (!g_droidbridge_logged_framebuffer_srgb) {
            g_droidbridge_logged_framebuffer_srgb = true;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "MobileGlues GL_FRAMEBUFFER_SRGB enable suppressed for Snapshot 4 color output");
            fprintf(stderr,
                    "DroidBridgeSDL3GL: MobileGlues GL_FRAMEBUFFER_SRGB enable suppressed for Snapshot 4 color output\n");
        }
        return;
    }
    if (g_real_glEnable != NULL) g_real_glEnable(cap);
}

static void droidbridge_glDisable_mobileglues_filter(DB_GLenum cap) {
    if (g_real_glDisable != NULL) g_real_glDisable(cap);
}

static unsigned char droidbridge_glIsEnabled_mobileglues_filter(DB_GLenum cap) {
    if (cap == DB_GL_FRAMEBUFFER_SRGB
            && droidbridge_mobileglues_framebuffer_srgb_workaround_enabled()) {
        return 0u;
    }
    return g_real_glIsEnabled != NULL ? g_real_glIsEnabled(cap) : 0u;
}

static DB_GLenum droidbridge_binding_enum_for_target(DB_GLenum target) {
    switch (target) {
        case 0x8892u: return 0x8894u; /* GL_ARRAY_BUFFER */
        case 0x8893u: return 0x8895u; /* GL_ELEMENT_ARRAY_BUFFER */
        case 0x88EBu: return 0x88EDu; /* GL_PIXEL_PACK_BUFFER */
        case 0x88ECu: return 0x88EFu; /* GL_PIXEL_UNPACK_BUFFER */
        case 0x8A11u: return 0x8A28u; /* GL_UNIFORM_BUFFER */
        case 0x8C8Eu: return 0x8C8Fu; /* GL_TRANSFORM_FEEDBACK_BUFFER */
        case 0x8F36u: return 0x8F36u; /* GL_COPY_READ_BUFFER */
        case 0x8F37u: return 0x8F37u; /* GL_COPY_WRITE_BUFFER */
        case 0x8F3Fu: return 0x8F43u; /* GL_DRAW_INDIRECT_BUFFER */
        case 0x90EEu: return 0x90EFu; /* GL_DISPATCH_INDIRECT_BUFFER */
        case 0x90D2u: return 0x90D3u; /* GL_SHADER_STORAGE_BUFFER */
        case 0x92C0u: return 0x92C1u; /* GL_ATOMIC_COUNTER_BUFFER */
        case 0x9192u: return 0x9193u; /* GL_QUERY_BUFFER */
        default: return 0u;
    }
}

static void* droidbridge_get_wrapped_gl_handle(void) {
    if (g_droidbridge_wrapped_gl_handle != NULL) return g_droidbridge_wrapped_gl_handle;
    if (g_droidbridge_opengl_proxy_handle != NULL) {
        g_droidbridge_wrapped_gl_handle = g_droidbridge_opengl_proxy_handle;
        return g_droidbridge_wrapped_gl_handle;
    }
    const char* path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    if (path != NULL && path[0] != '\0') {
        g_droidbridge_wrapped_gl_handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    }
    if (g_droidbridge_wrapped_gl_handle == NULL) {
        const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
        const char* fallback = kind != NULL && strcmp(kind, "krypton") == 0
                ? "libng_gl4es.so" : "libmobileglues.so";
        g_droidbridge_wrapped_gl_handle = dlopen(fallback, RTLD_NOW | RTLD_GLOBAL);
    }
    return g_droidbridge_wrapped_gl_handle;
}

static void droidbridge_resolve_buffer_functions(void) {
    void* handle = droidbridge_get_wrapped_gl_handle();
    if (handle == NULL) return;
    if (g_real_glMapBufferRange == NULL) g_real_glMapBufferRange =
            (db_gl_map_buffer_range_fn)droidbridge_resolve_gl_symbol(handle, "glMapBufferRange");
    if (g_real_glMapBuffer == NULL) g_real_glMapBuffer =
            (db_gl_map_buffer_fn)droidbridge_resolve_gl_symbol(handle, "glMapBuffer");
    if (g_real_glUnmapBuffer == NULL) g_real_glUnmapBuffer =
            (db_gl_unmap_buffer_fn)droidbridge_resolve_gl_symbol(handle, "glUnmapBuffer");
    if (g_real_glFlushMappedBufferRange == NULL) g_real_glFlushMappedBufferRange =
            (db_gl_flush_mapped_buffer_range_fn)droidbridge_resolve_gl_symbol(handle, "glFlushMappedBufferRange");
    if (g_real_glBufferSubData == NULL) g_real_glBufferSubData =
            (db_gl_buffer_sub_data_fn)droidbridge_resolve_gl_symbol(handle, "glBufferSubData");
    if (g_real_glGetBufferSubData == NULL) g_real_glGetBufferSubData =
            (db_gl_get_buffer_sub_data_fn)droidbridge_resolve_gl_symbol(handle, "glGetBufferSubData");
    if (g_real_glBindBuffer == NULL) g_real_glBindBuffer =
            (db_gl_bind_buffer_fn)droidbridge_resolve_gl_symbol(handle, "glBindBuffer");
    if (g_real_glGetBufferParameteriv == NULL) g_real_glGetBufferParameteriv =
            (db_gl_get_buffer_parameter_iv_fn)droidbridge_resolve_gl_symbol(handle, "glGetBufferParameteriv");
    if (g_real_glGetIntegerv == NULL) g_real_glGetIntegerv =
            (db_gl_get_integerv_fn)droidbridge_resolve_gl_symbol(handle, "glGetIntegerv");
}

static db_shadow_map* droidbridge_find_shadow_map(DB_GLenum target) {
    for (int i = 0; i < DB_MAX_SHADOW_MAPS; ++i) {
        if (g_shadow_maps[i].active && g_shadow_maps[i].target == target) return &g_shadow_maps[i];
    }
    return NULL;
}

static db_shadow_map* droidbridge_alloc_shadow_map(DB_GLenum target) {
    db_shadow_map* existing = droidbridge_find_shadow_map(target);
    if (existing != NULL) return existing;
    for (int i = 0; i < DB_MAX_SHADOW_MAPS; ++i) {
        if (!g_shadow_maps[i].active) {
            memset(&g_shadow_maps[i], 0, sizeof(g_shadow_maps[i]));
            g_shadow_maps[i].active = true;
            g_shadow_maps[i].target = target;
            return &g_shadow_maps[i];
        }
    }
    return NULL;
}

static DB_GLuint droidbridge_current_buffer(DB_GLenum target) {
    droidbridge_resolve_buffer_functions();
    DB_GLenum binding = droidbridge_binding_enum_for_target(target);
    if (binding == 0u || g_real_glGetIntegerv == NULL) return 0u;
    int value = 0;
    g_real_glGetIntegerv(binding, &value);
    return value > 0 ? (DB_GLuint)value : 0u;
}

static void* droidbridge_shadow_map_range(DB_GLenum target, intptr_t offset,
                                          intptr_t length, unsigned int access) {
    droidbridge_resolve_buffer_functions();
    if (length <= 0) return NULL;

    if (!droidbridge_is_shadow_map_fallback_renderer()) {
        return g_real_glMapBufferRange != NULL
                ? g_real_glMapBufferRange(target, offset, length, access) : NULL;
    }

    /* Krypton normally supports glMapBufferRange, so preserve its native fast
     * path and fall back only when it actually returns NULL. Restoring the SDL
     * wrapped context first also avoids a false NULL after focus/surface churn. */
    if (droidbridge_is_krypton_wrapped_renderer() && g_real_glMapBufferRange != NULL) {
        droidbridge_ensure_wrapped_context("glMapBufferRange");
        void* mapped = g_real_glMapBufferRange(target, offset, length, access);
        if (mapped != NULL) return mapped;
    }

    db_shadow_map* map = droidbridge_alloc_shadow_map(target);
    if (map == NULL) return NULL;
    if (map->pointer != NULL && !map->real_mapping) free(map->pointer);
    memset(map, 0, sizeof(*map));
    map->active = true;
    map->target = target;
    map->buffer = droidbridge_current_buffer(target);
    map->offset = offset;
    map->length = length;
    map->access = access;

    /* MobileGlues 1.3.5 and affected Krypton paths can return NULL for
     * Snapshot/26+ staging maps. Use a CPU shadow allocation and upload it with
     * glBufferSubData during unmap. */
    map->pointer = calloc(1u, (size_t)length);
    if (map->pointer == NULL) {
        memset(map, 0, sizeof(*map));
        return NULL;
    }

    if ((access & DB_GL_MAP_READ_BIT) != 0u
            && (access & (DB_GL_MAP_INVALIDATE_RANGE_BIT | DB_GL_MAP_INVALIDATE_BUFFER_BIT)) == 0u
            && g_real_glGetBufferSubData != NULL) {
        g_real_glGetBufferSubData(target, offset, length, map->pointer);
    }

    if (!g_droidbridge_logged_shadow_map) {
        g_droidbridge_logged_shadow_map = true;
        __android_log_print(ANDROID_LOG_INFO, TAG,
                "%s CPU-backed glMapBufferRange fallback active target=0x%x length=%ld access=0x%x buffer=%u",
                droidbridge_wrapped_renderer_name(), target, (long)length, access, map->buffer);
        fprintf(stderr,
                "DroidBridgeSDL3GL: %s CPU map fallback target=0x%x length=%ld access=0x%x buffer=%u\n",
                droidbridge_wrapped_renderer_name(), target, (long)length, access, map->buffer);
    }
    return map->pointer;
}

static unsigned char droidbridge_shadow_unmap(DB_GLenum target) {
    droidbridge_resolve_buffer_functions();
    db_shadow_map* map = droidbridge_find_shadow_map(target);
    if (map == NULL) {
        return g_real_glUnmapBuffer != NULL ? g_real_glUnmapBuffer(target) : 0u;
    }
    if (map->real_mapping) {
        unsigned char result = g_real_glUnmapBuffer != NULL ? g_real_glUnmapBuffer(target) : 0u;
        memset(map, 0, sizeof(*map));
        return result;
    }

    unsigned char result = 1u;
    if ((map->access & DB_GL_MAP_WRITE_BIT) != 0u) {
        DB_GLuint previous = droidbridge_current_buffer(target);
        if (g_real_glBindBuffer != NULL && map->buffer != 0u && previous != map->buffer) {
            g_real_glBindBuffer(target, map->buffer);
        }
        if (g_real_glBufferSubData != NULL) {
            g_real_glBufferSubData(target, map->offset, map->length, map->pointer);
            if (!g_droidbridge_logged_shadow_upload) {
                g_droidbridge_logged_shadow_upload = true;
                fprintf(stderr,
                        "DroidBridgeSDL3GL: %s CPU map uploaded through glBufferSubData length=%ld\n",
                        droidbridge_wrapped_renderer_name(), (long)map->length);
            }
        } else {
            result = 0u;
        }
        if (g_real_glBindBuffer != NULL && map->buffer != 0u && previous != map->buffer) {
            g_real_glBindBuffer(target, previous);
        }
    }
    free(map->pointer);
    memset(map, 0, sizeof(*map));
    return result;
}

static jlong droidbridge_jni_nglMapBufferRange(JNIEnv* env, jclass clazz,
                                                jint target, jlong offset,
                                                jlong length, jint access) {
    (void)env; (void)clazz;
    return (jlong)(intptr_t)droidbridge_shadow_map_range(
            (DB_GLenum)target, (intptr_t)offset, (intptr_t)length, (unsigned int)access);
}

static jlong droidbridge_jni_nglMapBuffer(JNIEnv* env, jclass clazz,
                                           jint target, jint access) {
    (void)env; (void)clazz;
    droidbridge_resolve_buffer_functions();
    if (!droidbridge_is_shadow_map_fallback_renderer()) {
        return (jlong)(intptr_t)(g_real_glMapBuffer != NULL
                ? g_real_glMapBuffer((DB_GLenum)target, (DB_GLenum)access) : NULL);
    }
    if (droidbridge_is_krypton_wrapped_renderer() && g_real_glMapBuffer != NULL) {
        droidbridge_ensure_wrapped_context("glMapBuffer");
        void* mapped = g_real_glMapBuffer((DB_GLenum)target, (DB_GLenum)access);
        if (mapped != NULL) return (jlong)(intptr_t)mapped;
    }
    int size = 0;
    if (g_real_glGetBufferParameteriv != NULL) {
        g_real_glGetBufferParameteriv((DB_GLenum)target, DB_GL_BUFFER_SIZE, &size);
    }
    unsigned int bits = access == (jint)DB_GL_READ_ONLY ? DB_GL_MAP_READ_BIT
            : access == (jint)DB_GL_WRITE_ONLY ? DB_GL_MAP_WRITE_BIT
            : (DB_GL_MAP_READ_BIT | DB_GL_MAP_WRITE_BIT);
    return size > 0 ? (jlong)(intptr_t)droidbridge_shadow_map_range(
            (DB_GLenum)target, 0, size, bits) : 0;
}

static jboolean droidbridge_jni_glUnmapBuffer(JNIEnv* env, jclass clazz, jint target) {
    (void)env; (void)clazz;
    return droidbridge_shadow_unmap((DB_GLenum)target) ? JNI_TRUE : JNI_FALSE;
}

static void droidbridge_jni_glFlushMappedBufferRange(JNIEnv* env, jclass clazz,
                                                      jint target, jlong offset,
                                                      jlong length) {
    (void)env; (void)clazz; (void)offset; (void)length;
    db_shadow_map* map = droidbridge_find_shadow_map((DB_GLenum)target);
    if (map != NULL && !map->real_mapping) return;
    droidbridge_resolve_buffer_functions();
    if (g_real_glFlushMappedBufferRange != NULL) {
        g_real_glFlushMappedBufferRange((DB_GLenum)target, (intptr_t)offset, (intptr_t)length);
    }
}

static bool droidbridge_register_buffer_map_fallback(JNIEnv* env) {
    if (g_droidbridge_buffer_map_fallback_installed) return true;

    /* RegisterNatives replaces LWJGL's mapping entry points for the whole JVM.
     * Do not install this on Krypton (or any renderer other than MobileGlues),
     * because even the pass-through branch resolves the wrapper symbol through
     * a different path and can return NULL while glGetError still reports 0. */
    if (!droidbridge_is_mobileglues_wrapped_renderer()) {
        const char* kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
        const char* library = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
        fprintf(stderr,
                "DroidBridgeSDL3GL: LWJGL buffer-map JNI fallback skipped kind=%s library=%s\n",
                kind != NULL && kind[0] != '\0' ? kind : "<unset>",
                library != NULL && library[0] != '\0' ? library : "<unset>");
        return true;
    }

    jclass gl30c = (*env)->FindClass(env, "org/lwjgl/opengl/GL30C");
    if (gl30c == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        fprintf(stderr, "DroidBridgeSDL3GL: GL30C class unavailable for CPU map fallback\n");
        return false;
    }
    JNINativeMethod gl30Methods[] = {
            {"nglMapBufferRange", "(IJJI)J", (void*)&droidbridge_jni_nglMapBufferRange},
            {"glFlushMappedBufferRange", "(IJJ)V", (void*)&droidbridge_jni_glFlushMappedBufferRange}
    };
    jint r30 = (*env)->RegisterNatives(env, gl30c, gl30Methods, 2);
    (*env)->DeleteLocalRef(env, gl30c);
    if (r30 != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        fprintf(stderr, "DroidBridgeSDL3GL: failed registering GL30C CPU map fallback result=%d\n", r30);
        return false;
    }

    jclass gl15c = (*env)->FindClass(env, "org/lwjgl/opengl/GL15C");
    if (gl15c == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        fprintf(stderr, "DroidBridgeSDL3GL: GL15C class unavailable for CPU map fallback\n");
        return false;
    }
    JNINativeMethod gl15Methods[] = {
            {"nglMapBuffer", "(II)J", (void*)&droidbridge_jni_nglMapBuffer},
            {"glUnmapBuffer", "(I)Z", (void*)&droidbridge_jni_glUnmapBuffer}
    };
    jint r15 = (*env)->RegisterNatives(env, gl15c, gl15Methods, 2);
    (*env)->DeleteLocalRef(env, gl15c);
    if (r15 != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        fprintf(stderr, "DroidBridgeSDL3GL: failed registering GL15C CPU map fallback result=%d\n", r15);
        return false;
    }
    g_droidbridge_buffer_map_fallback_installed = true;
    fprintf(stderr, "DroidBridgeSDL3GL: LWJGL buffer-map JNI fallback installed\n");
    return true;
}


static jlong droidbridge_jni_nglGetString_report(JNIEnv* env, jclass clazz, jint name) {
    (void)env;
    (void)clazz;

    db_gl_get_string_fn real = g_real_glGetString;
    if (real == NULL && glGetString_p != NULL) {
        real = (db_gl_get_string_fn)glGetString_p;
        g_real_glGetString = real;
    }
    if (real == NULL && g_droidbridge_opengl_proxy_handle != NULL) {
        real = (db_gl_get_string_fn)droidbridge_resolve_gl_symbol(
                g_droidbridge_opengl_proxy_handle, "glGetString");
        if (real != NULL) g_real_glGetString = real;
    }
    if (real == NULL) {
        fprintf(stderr,
                "DroidBridgeVulkanDriver: GL11C.nglGetString reporting hook has no real glGetString yet\n");
        return (jlong)0;
    }

    return (jlong)(uintptr_t)droidbridge_glGetString_compat_filter((DB_GLenum)name);
}

static bool droidbridge_register_gl_string_reporting(JNIEnv* env) {
    if (g_droidbridge_gl_string_reporting_installed) return true;

    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* report = getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT");
    const bool zink_renderer = renderer != NULL
            && (strstr(renderer, "zink") != NULL || strstr(renderer, "ZINK") != NULL);
    if (!zink_renderer || report == NULL || report[0] == '\0') return true;

    jclass gl11c = (*env)->FindClass(env, "org/lwjgl/opengl/GL11C");
    if (gl11c == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        fprintf(stderr,
                "DroidBridgeVulkanDriver: GL11C unavailable; F3 Vulkan ICD reporting JNI hook not installed\n");
        return false;
    }

    JNINativeMethod methods[] = {
            {"nglGetString", "(I)J", (void*)&droidbridge_jni_nglGetString_report}
    };
    jint result = (*env)->RegisterNatives(env, gl11c, methods, 1);
    (*env)->DeleteLocalRef(env, gl11c);
    if (result != 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        fprintf(stderr,
                "DroidBridgeVulkanDriver: failed to register GL11C.nglGetString F3 reporting hook result=%d\n",
                result);
        return false;
    }

    g_droidbridge_gl_string_reporting_installed = true;
    fprintf(stderr,
            "DroidBridgeVulkanDriver: GL11C.nglGetString F3 reporting hook installed report=%s\n",
            report);
    return true;
}

static const DB_GLubyte* droidbridge_glGetString_compat_filter(DB_GLenum name) {
    const DB_GLubyte* result = g_real_glGetString != NULL
            ? g_real_glGetString(name) : NULL;

    if (name == DB_GL_VENDOR && droidbridge_is_direct_freedreno_renderer()) {
        snprintf(g_droidbridge_vendor_string, sizeof(g_droidbridge_vendor_string),
                 "freedreno/DroidBridge");
        return (const DB_GLubyte*) g_droidbridge_vendor_string;
    }

    // Keep third-party builder branding out of the user-visible GL vendor string
    // while preserving GL_RENDERER unchanged so logs can still prove whether the
    // actual Vulkan device is Turnip or the Android system driver.
    if (name == DB_GL_VENDOR
            && getenv("DROIDBRIDGE_RENDERER") != NULL
            && strcmp(getenv("DROIDBRIDGE_RENDERER"), "opengles3_desktopgl_zink_kopper") == 0) {
        snprintf(g_droidbridge_vendor_string, sizeof(g_droidbridge_vendor_string),
                 "Mesa/DroidBridge");
        return (const DB_GLubyte*)g_droidbridge_vendor_string;
    }

    if (name == DB_GL_VERSION && result != NULL) {
        const char* renderer = getenv("DROIDBRIDGE_RENDERER");
        const char* driver_report = getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT");
        const bool zink_renderer = renderer != NULL
                && (strstr(renderer, "zink") != NULL || strstr(renderer, "ZINK") != NULL);
        if (zink_renderer && driver_report != NULL && driver_report[0] != '\0') {
            /* Mesa 23.x here is the OpenGL/Zink frontend, not the selected Vulkan
             * ICD. Keep both pieces of information instead of replacing Mesa's GL
             * version with a Vulkan version and making the OpenGL string false. */
            snprintf(g_droidbridge_filtered_version,
                     sizeof(g_droidbridge_filtered_version),
                     "%s [Zink frontend] | Vulkan ICD: %s",
                     (const char*)result,
                     driver_report);
            if (!g_droidbridge_logged_gl_version_report) {
                fprintf(stderr,
                        "DroidBridgeVulkanDriver: F3 GL_VERSION report frontend='%s' ICD='%s'\n",
                        (const char*)result, driver_report);
                g_droidbridge_logged_gl_version_report = true;
            }
            return (const DB_GLubyte*)g_droidbridge_filtered_version;
        }
    }

    if (!droidbridge_snapshot4_safe_buffers_enabled() || result == NULL) return result;

    if (name == DB_GL_VERSION) {
        int major = 0;
        int minor = 0;
        if (sscanf((const char*)result, "%d.%d", &major, &minor) == 2
                && (major > 4 || (major == 4 && minor > 3))) {
            snprintf(g_droidbridge_filtered_version,
                     sizeof(g_droidbridge_filtered_version),
                     "4.3 DroidBridge Snapshot 4 compatibility (%s)",
                     (const char*)result);
            return (const DB_GLubyte*)g_droidbridge_filtered_version;
        }
    } else if (name == DB_GL_EXTENSIONS) {
        if (!g_droidbridge_filtered_extensions_ready) {
            const char* source = (const char*)result;
            size_t source_length = strlen(source);
            g_droidbridge_filtered_extensions = (char*)malloc(source_length + 1);
            if (g_droidbridge_filtered_extensions != NULL) {
                size_t output = 0;
                const char* cursor = source;
                while (*cursor != '\0') {
                    while (*cursor == ' ') cursor++;
                    const char* start = cursor;
                    while (*cursor != '\0' && *cursor != ' ') cursor++;
                    size_t length = (size_t)(cursor - start);
                    if (length == 0) continue;
                    bool blocked = (length == strlen(DB_BLOCKED_BUFFER_STORAGE_ARB)
                            && strncmp(start, DB_BLOCKED_BUFFER_STORAGE_ARB, length) == 0)
                            || (length == strlen(DB_BLOCKED_BUFFER_STORAGE_EXT)
                            && strncmp(start, DB_BLOCKED_BUFFER_STORAGE_EXT, length) == 0);
                    if (!blocked) {
                        if (output != 0) g_droidbridge_filtered_extensions[output++] = ' ';
                        memcpy(g_droidbridge_filtered_extensions + output, start, length);
                        output += length;
                    }
                }
                g_droidbridge_filtered_extensions[output] = '\0';
            }
            g_droidbridge_filtered_extensions_ready = true;
        }
        if (!g_droidbridge_logged_buffer_filter) {
            g_droidbridge_logged_buffer_filter = true;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "Snapshot 4 direct LWJGL buffer-storage filter active");
            fprintf(stderr,
                    "DroidBridgeSDL3GL: direct LWJGL buffer-storage filter active\n");
        }
        return g_droidbridge_filtered_extensions != NULL
                ? (const DB_GLubyte*)g_droidbridge_filtered_extensions : result;
    }

    return result;
}

static const DB_GLubyte* droidbridge_glGetStringi_compat_filter(
        DB_GLenum name, DB_GLuint index) {
    const DB_GLubyte* result = g_real_glGetStringi != NULL
            ? g_real_glGetStringi(name, index) : NULL;
    if (!droidbridge_snapshot4_safe_buffers_enabled()
            || name != DB_GL_EXTENSIONS || result == NULL) {
        return result;
    }
    if (droidbridge_is_blocked_buffer_storage_extension((const char*)result)) {
        if (!g_droidbridge_logged_buffer_filter) {
            g_droidbridge_logged_buffer_filter = true;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "Snapshot 4 direct LWJGL buffer-storage filter active");
            fprintf(stderr,
                    "DroidBridgeSDL3GL: direct LWJGL buffer-storage filter active\n");
        }
        return (const DB_GLubyte*)DB_DISABLED_EXTENSION_NAME;
    }
    return result;
}

static void droidbridge_glGetIntegerv_compat_filter(DB_GLenum name, int* value) {
    if (g_real_glGetIntegerv == NULL) return;
    g_real_glGetIntegerv(name, value);
    if (!droidbridge_snapshot4_safe_buffers_enabled() || value == NULL) return;

    if (name == DB_GL_MAJOR_VERSION && *value > 4) {
        *value = 4;
    } else if (name == DB_GL_MINOR_VERSION) {
        int major = 0;
        g_real_glGetIntegerv(DB_GL_MAJOR_VERSION, &major);
        if (major > 4 || (major == 4 && *value > 3)) *value = 3;
    }
}

extern void* maybe_load_vulkan(void);

/*
 * Snapshot 4 SDL/OpenGL bridge entry points implemented in
 * sdl3_native_window_bridge.c. The load callback runs immediately after
 * LWJGL maps libSDL3.so, before RenderPearl can resolve SDL_GL_* or create a
 * window. The symbol callback replaces only the SDL OpenGL entry points that
 * require Android GLES translation.
 */
extern void droidbridge_sdl3_after_library_load(const char* filename, void* handle);
extern int droidbridge_sdl3_patch_cleanroom_java_callbacks(const char* filename, void* handle);
extern void* droidbridge_sdl3_override_lwjgl_symbol(void* handle, const char* name);
extern int droidbridge_sdl3_ensure_current_gl_context(const char* reason);

static void droidbridge_ensure_wrapped_context(const char* reason) {
    if (!droidbridge_is_wrapped_context_guard_renderer()) return;
    if (!droidbridge_sdl3_ensure_current_gl_context(reason)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                "Unable to restore %s GL context before %s",
                droidbridge_wrapped_renderer_name(), reason);
        fprintf(stderr,
                "DroidBridgeSDL3GL: unable to restore %s context before %s\n",
                droidbridge_wrapped_renderer_name(), reason);
    }
}

static bool droidbridge_glsl_ident_char(char c) {
    return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '_';
}

/*
 * Snapshot 5+ RenderPearl emits `_uniform` as an internal GLSL identifier.
 * MobileGlues 2.0's translator can interpret the `uniform` substring as the
 * declaration keyword and corrupt the generated GLES shader. Rename only the
 * complete identifier token, preserving byte length so line/offset diagnostics
 * stay useful.
 */
static int droidbridge_mobileglues_sanitize_shader_identifiers(char* source, size_t length) {
    if (source == NULL || length < 8u
            || !droidbridge_truthy_env("DROIDBRIDGE_MOBILEGLUES_SHADER_IDENTIFIER_FIX")) {
        return 0;
    }
    static const char needle[] = "_uniform";
    static const char replacement[] = "_db_uvar";
    int replacements = 0;
    for (size_t i = 0; i + 8u <= length; ++i) {
        if (memcmp(source + i, needle, 8u) != 0) continue;
        bool left_ok = i == 0u || !droidbridge_glsl_ident_char(source[i - 1u]);
        bool right_ok = i + 8u == length || !droidbridge_glsl_ident_char(source[i + 8u]);
        if (!left_ok || !right_ok) continue;
        memcpy(source + i, replacement, 8u);
        replacements++;
        i += 7u;
    }
    return replacements;
}

static DB_GLenum droidbridge_wrapped_gl_error(void) {
    if (g_real_glGetError == NULL) {
        void* handle = droidbridge_get_wrapped_gl_handle();
        g_real_glGetError = (db_gl_get_error_fn)
                droidbridge_resolve_gl_symbol(handle, "glGetError");
    }
    return g_real_glGetError != NULL ? g_real_glGetError() : 0u;
}

static DB_GLuint droidbridge_glCreateShader_context_guard(DB_GLenum type) {
    droidbridge_ensure_wrapped_context("glCreateShader");
    DB_GLuint shader = g_real_glCreateShader != NULL ? g_real_glCreateShader(type) : 0;
    if (!g_droidbridge_logged_wrapped_create_shader || shader == 0u) {
        g_droidbridge_logged_wrapped_create_shader = true;
        DB_GLenum error = shader == 0u ? droidbridge_wrapped_gl_error() : 0u;
        fprintf(stderr,
                "DroidBridgeSDL3GL: %s direct glCreateShader type=0x%x result=%u error=0x%x\n",
                droidbridge_wrapped_renderer_name(), type, shader, error);
    }
    return shader;
}

static void droidbridge_glShaderSource_context_guard(
        DB_GLuint shader, int count, const char* const* strings, const int* lengths) {
    droidbridge_ensure_wrapped_context("glShaderSource");
    if (g_real_glShaderSource != NULL) {
        g_real_glShaderSource(shader, count, strings, lengths);
    }
}

static void droidbridge_glShaderSource_mobileglues_normalized(
        DB_GLuint shader, int count, const char* const* strings, const int* lengths) {
    droidbridge_ensure_wrapped_context("glShaderSource");
    if (g_real_glShaderSource == NULL || count <= 0 || strings == NULL) {
        if (g_real_glShaderSource != NULL) g_real_glShaderSource(shader, count, strings, lengths);
        return;
    }
    size_t total = 0;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t length = lengths != NULL && lengths[i] >= 0
                ? (size_t)lengths[i] : strlen(strings[i]);
        if (length > (64u * 1024u * 1024u) || total > (64u * 1024u * 1024u) - length) {
            g_real_glShaderSource(shader, count, strings, lengths);
            return;
        }
        total += length;
    }
    char* combined = (char*)malloc(total + 1u);
    if (combined == NULL) {
        g_real_glShaderSource(shader, count, strings, lengths);
        return;
    }
    size_t output = 0;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t length = lengths != NULL && lengths[i] >= 0
                ? (size_t)lengths[i] : strlen(strings[i]);
        memcpy(combined + output, strings[i], length);
        output += length;
    }
    combined[output] = '\0';
    int renamed = droidbridge_mobileglues_sanitize_shader_identifiers(combined, output);
    if (renamed > 0) {
        fprintf(stderr,
                "DroidBridgeSnapshot8GL: sanitized MobileGlues shader=%u renamed _uniform tokens=%d\n",
                shader, renamed);
    }
    const char* one = combined;
    g_real_glShaderSource(shader, 1, &one, NULL);
    if (!g_droidbridge_logged_mobileglues_shader_normalization) {
        g_droidbridge_logged_mobileglues_shader_normalization = true;
        fprintf(stderr,
                "DroidBridgeSDL3GL: MobileGlues direct shader source normalized shader=%u parts=%d bytes=%zu\n",
                shader, count, output);
    }
    free(combined);
}

static void droidbridge_glCompileShader_context_guard(DB_GLuint shader) {
    droidbridge_ensure_wrapped_context("glCompileShader");
    if (g_real_glCompileShader != NULL) g_real_glCompileShader(shader);
}

static DB_GLuint droidbridge_glCreateProgram_context_guard(void) {
    droidbridge_ensure_wrapped_context("glCreateProgram");
    DB_GLuint program = g_real_glCreateProgram != NULL ? g_real_glCreateProgram() : 0;
    if (!g_droidbridge_logged_wrapped_create_program || program == 0u) {
        g_droidbridge_logged_wrapped_create_program = true;
        DB_GLenum error = program == 0u ? droidbridge_wrapped_gl_error() : 0u;
        fprintf(stderr,
                "DroidBridgeSDL3GL: %s direct glCreateProgram result=%u error=0x%x\n",
                droidbridge_wrapped_renderer_name(), program, error);
    }
    return program;
}

#define DB_OPENGL_PROXY_SONAME "libGLDroidBridge.so"
#define DB_OPENGL_PROXY_ALT_SONAME "libGLDroidBridgeMesa.so"
static const char* basename_or_self(const char* filename) {
    if (filename == NULL) return "";
    const char* base = strrchr(filename, '/');
    return base != NULL ? base + 1 : filename;
}

/**
 * Returns true when the requested library name is a Vulkan loader soname.
 * Accepts both direct names and full paths.
 */
static bool is_vulkan_loader_name(const char* filename) {
    const char* base = basename_or_self(filename);
    return strcmp(base, "libvulkan.so") == 0 ||
           strcmp(base, "libvulkan.so.1") == 0;
}

/**
 * DroidBridge OpenGL proxy names.
 *
 * LWJGL requests this sentinel name so DroidBridge can return its configured
 * RenderSpec EGL provider instead of loading a random system libGL.
 */
static bool is_droidbridge_opengl_proxy_name(const char* filename) {
    if (filename == NULL) return false;
    const char* base = basename_or_self(filename);

    /* Exact DroidBridge-owned sentinel names. */
    if (strcmp(base, DB_OPENGL_PROXY_SONAME) == 0 ||
        strcmp(base, DB_OPENGL_PROXY_ALT_SONAME) == 0) {
        return true;
    }

    /* Be tolerant of LWJGL name-mapping differences, such as GLDroidBridge,
     * /full/path/libGLDroidBridge.so, or accidental liblib... wrapping. */
    if (strstr(base, "GLDroidBridge") != NULL ||
        strstr(base, "DroidBridgeMesa") != NULL) {
        return true;
    }


    return false;
}

static const char* first_non_empty(const char* a, const char* b, const char* c,
                                   const char* d, const char* e) {
    if (a != NULL && a[0] != '\0') return a;
    if (b != NULL && b[0] != '\0') return b;
    if (c != NULL && c[0] != '\0') return c;
    if (d != NULL && d[0] != '\0') return d;
    if (e != NULL && e[0] != '\0') return e;
    return NULL;
}

static void* try_dlopen_with_log(const char* library, int mode) {
    if (library == NULL || library[0] == '\0') return NULL;

    dlerror();
    void* handle = dlopen(library, mode);
    if (handle != NULL) {
        printf("LWJGL linkerhook: DroidBridge RenderSpec using %s handle=%p\n", library, handle);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook: DroidBridge RenderSpec failed to open %s: %s\n",
           library,
           err != NULL ? err : "unknown");
    return NULL;
}

static bool env_enabled_lwjgl_hook(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0;
}


static void* acquire_native_glfw_opengl_handle_v82(void) {
    if (!env_enabled_lwjgl_hook("DROIDBRIDGE_NATIVE_GLFW_KGSL")) return NULL;

    /* Minecraft 26.3 creates the real Android EGL drawable through SDL3.
     * Creating an earlier NativeGLFW EGL context on the very same ANativeWindow
     * forces eglTerminate()/surface cleanup before SDL3 is initialized. The
     * resulting Mesa drawable callback may still hold the old display state.
     * Resolve the RenderSpec EGL library (in acquire_droidbridge_opengl_handle)
     * without allocating a second, temporary GL context or display instead.
     * 26.2's proven NativeGLFW path is deliberately left unchanged.
     *
     * The launch plan, not a Minecraft version guess in native code, enables
     * this flag only for Minecraft 26.3 with direct KGSL Freedreno and SDL3.
     */
    if (env_enabled_lwjgl_hook("DROIDBRIDGE_SDL3_SKIP_NATIVE_GLFW_PRECONTEXT")) {
        printf("LWJGL linkerhook-v83: SDL3 owns EGL context; skipped NativeGLFW precontext, using configured RenderSpec EGL provider\n");
        fflush(stdout);
        return NULL;
    }

    typedef void* (*bridge_acquire_fn_t)(void);
    dlerror();
    bridge_acquire_fn_t bridge_acquire =
            (bridge_acquire_fn_t)dlsym(RTLD_DEFAULT, "droidbridge_runtime_native_glfw_acquire_opengl_handle");
    if (bridge_acquire != NULL) {
        void* bridge_handle = bridge_acquire();
        if (bridge_handle != NULL) {
            printf("LWJGL linkerhook-v82: using NativeGLFW handle from libdroidbridge_runtime bridge handle=%p\n", bridge_handle);
            return bridge_handle;
        }
        printf("LWJGL linkerhook-v82: libdroidbridge_runtime bridge returned NULL; trying direct NativeGLFW acquire\n");
    } else {
        const char* bridge_error = dlerror();
        printf("LWJGL linkerhook-v82: libdroidbridge_runtime bridge acquire symbol missing error=%s\n", bridge_error ? bridge_error : "unknown");
    }

    const char* native_dir = first_non_empty(getenv("DROIDBRIDGE_NATIVEDIR"),
                                             getenv("DROIDBRIDGE_MESA_NATIVE_DIR"),
                                             getenv("DROIDBRIDGE_APP_NATIVE_DIR"),
                                             getenv("DROIDBRIDGE_LIBRARY_PATH"),
                                             NULL);
    char absolute_path[1024];
    const char* library = "libdroidbridge_native_glfw_v82.so";
    if (native_dir != NULL && native_dir[0] != '\0') {
        snprintf(absolute_path, sizeof(absolute_path), "%s/%s", native_dir, library);
        library = absolute_path;
    }

    dlerror();
    void* native_glfw = dlopen(library, RTLD_NOW | RTLD_GLOBAL);
    if (native_glfw == NULL && library == absolute_path) {
        const char* first_error = dlerror();
        printf("LWJGL linkerhook-v82: NativeGLFW absolute dlopen failed %s error=%s\n",
               library, first_error ? first_error : "unknown");
        dlerror();
        native_glfw = dlopen("libdroidbridge_native_glfw_v82.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (native_glfw == NULL) {
        const char* err = dlerror();
        printf("LWJGL linkerhook-v82: NativeGLFW dlopen failed error=%s\n", err ? err : "unknown");
        return NULL;
    }

    typedef void* (*acquire_fn_t)(void);
    dlerror();
    acquire_fn_t acquire = (acquire_fn_t)dlsym(native_glfw, "droidbridge_native_glfw_acquire_opengl_handle");
    if (acquire == NULL) {
        const char* err = dlerror();
        printf("LWJGL linkerhook-v82: NativeGLFW missing acquire_opengl_handle error=%s\n", err ? err : "unknown");
        return NULL;
    }

    void* egl_handle = acquire();
    if (egl_handle != NULL) {
        /*
         * Direct fallback acquisition may leave NativeGLFW's EGL context current
         * on this preload thread. Ask the runtime bridge to release it so the
         * Minecraft render thread can bind it without EGL_BAD_ACCESS.
         */
        typedef int (*bridge_release_fn_t)(void);
        dlerror();
        bridge_release_fn_t bridge_release =
                (bridge_release_fn_t)dlsym(RTLD_DEFAULT,
                        "droidbridge_runtime_native_glfw_release_current_context");
        if (bridge_release != NULL) {
            int released = bridge_release();
            printf("LWJGL linkerhook-v82: preload-thread NativeGLFW context release=%d\n", released);
        }
        printf("LWJGL linkerhook-v82: using NativeGLFW EGL handle for OpenGL handle=%p\n", egl_handle);
        return egl_handle;
    }

    printf("LWJGL linkerhook-v82: NativeGLFW acquire returned NULL; falling back\n");
    return NULL;
}


static const char* droidbridge_filename_only(const char* path) {
    if (path == NULL || path[0] == '\0') return path;
    const char* slash = strrchr(path, '/');
    return slash != NULL ? slash + 1 : path;
}


static bool string_equals(const char* value, const char* expected) {
    return value != NULL && expected != NULL && strcmp(value, expected) == 0;
}

/*
 * Kopper creates Zink inside libEGL_mesa.so, so the selected Vulkan loader
 * must already be authoritative before Mesa is dlopened. Older Minecraft
 * versions do not necessarily initialize org.lwjgl.vulkan before OpenGL,
 * which means the -Dorg.lwjgl.vulkan.libname proxy alone cannot guarantee
 * that ordering.
 *
 * Keep this deliberately Kopper-only. Regular Vulkan Zink (including custom
 * Turnip and PanVK) retains its existing early-proxy/OSMesa path unchanged.
 */
static bool droidbridge_prepare_kopper_vulkan_before_mesa(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const bool kopper = string_equals(renderer, "opengles3_desktopgl_zink_kopper")
            || string_equals(mesa_mode, "kopper_zink")
            || droidbridge_truthy_env("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN");
    if (!kopper) return true;

    const bool use_system = droidbridge_truthy_env("DROIDBRIDGE_USE_SYSTEM_VULKAN");
    const bool strict_custom = droidbridge_truthy_env("DROIDBRIDGE_LOAD_CUSTOM_VULKAN")
            && !use_system;
    if (!strict_custom) {
        return true;
    }

    const char* selected = getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER");
    if (selected == NULL || selected[0] == '\0') {
        fprintf(stderr,
                "DroidBridgeKopperEarlyVulkan: strict custom Kopper launch has no selected ICD path; refusing Mesa preload\n");
        fflush(stderr);
        return false;
    }

    const char* existing_ptr = getenv("VULKAN_PTR");
    if (existing_ptr != NULL && existing_ptr[0] != '\0') {
        fprintf(stderr,
                "DroidBridgeKopperEarlyVulkan: selected Vulkan loader already ready before Mesa ptr=%s ICD=%s\n",
                existing_ptr,
                getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") != NULL
                        ? getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") : "<unset>");
        fflush(stderr);
        return true;
    }

    void* loader = maybe_load_vulkan();
    if (loader == NULL) {
        fprintf(stderr,
                "DroidBridgeKopperEarlyVulkan: failed to prepare selected Vulkan loader before Mesa; refusing system fallback ICD=%s report=%s\n",
                getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") != NULL
                        ? getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") : "<unset>",
                getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT") != NULL
                        ? getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT") : "<unset>");
        fflush(stderr);
        return false;
    }

    fprintf(stderr,
            "DroidBridgeKopperEarlyVulkan: selected Vulkan loader prepared before Mesa loader=%p ICD=%s report=%s\n",
            loader,
            getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") != NULL
                    ? getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") : "<unset>",
            getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT") != NULL
                    ? getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT") : "<unset>");
    fflush(stderr);
    return true;
}

static void* try_existing_egl_loader_handle_for_direct_mesa(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");

    if (!string_equals(renderer, "freedreno_kgsl") &&
        !string_equals(mesa_mode, "freedreno_kgsl")) {
        return NULL;
    }
    if (mesa_driver != NULL && mesa_driver[0] != '\0' &&
        strcmp(mesa_driver, "kgsl") != 0 &&
        strcmp(mesa_driver, "zink") != 0) {
        return NULL;
    }

    void* existing = droidbridge_egl_get_handle();
    if (existing == NULL) {
        printf("LWJGL linkerhook-v69: direct Freedreno has no existing EGL loader handle yet; using fallback loader\n");
        return NULL;
    }

    void* current = NULL;
    if (eglGetCurrentContext_p != NULL) {
        current = (void*) eglGetCurrentContext_p();
    }

    printf("LWJGL linkerhook-v69: reusing existing GLBridge EGL handle=%p loaded=%s currentContext=%p for direct Freedreno\n",
           existing,
           droidbridge_egl_get_loaded_name(),
           current);
    return existing;
}

static void* try_namespace_dlopen_with_log(const char* library, int mode) {
    if (library == NULL || library[0] == '\0') return NULL;
    if (!env_enabled_lwjgl_hook("DROIDBRIDGE_MESA_NAMESPACE")) return NULL;

    const char* namespace_path = getenv("DROIDBRIDGE_MESA_NAMESPACE_PATH");
    if (namespace_path == NULL || namespace_path[0] == '\0') {
        namespace_path = getenv("DROIDBRIDGE_MESA_NATIVE_DIR");
    }
    if (namespace_path == NULL || namespace_path[0] == '\0') return NULL;

    if (!linker_ns_load(namespace_path)) {
        printf("LWJGL linkerhook-v69: namespace load failed path=%s library=%s\n",
               namespace_path,
               library);
        return NULL;
    }

    int dl_mode = mode;
    if ((dl_mode & RTLD_NOW) == 0 && (dl_mode & RTLD_LAZY) == 0) {
        dl_mode |= RTLD_NOW;
    }
    dl_mode |= RTLD_GLOBAL;

    const char* short_name = droidbridge_filename_only(library);
    if (short_name == NULL || short_name[0] == '\0') short_name = library;

    dlerror();
    void* handle = linker_ns_dlopen(short_name, dl_mode);
    if (handle != NULL) {
        printf("LWJGL linkerhook-v69: DroidBridge RenderSpec using namespace %s path=%s handle=%p\n",
               short_name,
               namespace_path,
               handle);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook-v69: namespace short-name load failed library=%s path=%s error=%s\n",
           short_name,
           namespace_path,
           err != NULL ? err : "unknown");

    const char* absolute = library;
    if (absolute != NULL && strchr(absolute, '/') != NULL) {
        dlerror();
        handle = linker_ns_dlopen(absolute, dl_mode);
        if (handle != NULL) {
            printf("LWJGL linkerhook-v69: DroidBridge RenderSpec using namespace absolute %s path=%s handle=%p\n",
                   absolute,
                   namespace_path,
                   handle);
            return handle;
        }
        err = dlerror();
        printf("LWJGL linkerhook-v69: namespace absolute load failed library=%s path=%s error=%s\n",
               absolute,
               namespace_path,
               err != NULL ? err : "unknown");
    }

    return NULL;
}

static void* acquire_configured_droidbridge_renderspec(void) {
    const droidbridge_renderspec_t* rspec = droidbridge_renderspec_get();
    if (rspec == NULL || !rspec->configured || rspec->egl_acquire == NULL || rspec->egl_path == NULL) {
        printf("LWJGL linkerhook-v74: DroidBridge RenderSpec is not configured yet; using env fallback (this should not happen on v74 Freedreno)\n");
        return NULL;
    }

    /* Use the public RenderSpec acquisition callback rather than an optional
     * cached-handle field. Historical RenderSpec headers have egl_acquire and
     * egl_path, but no egl_handle member. The returned handle belongs to LWJGL;
     * do not return a potentially borrowed, launcher-owned reference here.
     */
    void* handle = rspec->egl_acquire(rspec->egl_path);
    if (droidbridge_is_direct_freedreno_renderer()) {
        printf("LWJGL linkerhook: acquired RenderSpec EGL=%s handle=%p\n",
               rspec->egl_path, handle);
        fflush(stdout);
    }
    if (handle != NULL) {
        printf("LWJGL linkerhook-v84: replacing OpenGL with RenderSpec driver EGL=%s handle=%p singleProvider=1 forceGles=%d overrideMajor=%d\n",
               rspec->egl_path,
               handle,
               rspec->force_gles_context,
               rspec->override_major_version);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook: configured DroidBridge RenderSpec failed for %s: %s\n",
           rspec->egl_path,
           err != NULL ? err : "unknown");
    return NULL;
}

/* In 26.3, SDL3 owns the EGL display, context and window surface. Obtain
 * Mesa's app-local EGL loader directly without creating a temporary NativeGLFW
 * display on the same ANativeWindow. Do not use an isolated RenderSpec namespace
 * for the normal path: the SDL3 EGL context must use the same loaded Mesa image
 * as LWJGL's OpenGL function table. On unexpected loader failure, fall through
 * to the existing configured RenderSpec acquisition without constructing a
 * second context. This applies only when JavaRuntimeBootstrap explicitly gates
 * the 26.3 KGSL SDL3 path; 26.2 remains byte-for-byte equivalent at runtime.
 */
static void* acquire_sdl3_direct_mesa_without_context(void) {
    if (!env_enabled_lwjgl_hook("DROIDBRIDGE_NATIVE_GLFW_KGSL")
            || !env_enabled_lwjgl_hook("DROIDBRIDGE_SDL3_SKIP_NATIVE_GLFW_PRECONTEXT")) {
        return NULL;
    }

    // Single-loader 26.3 route: there must be no launcher/ART Mesa image.
    // Acquire the exact SDL_EGL_LIBRARY from inside OpenJDK's loader namespace.
    // SDL3 subsequently dlopens the identical path and shares this ELF image.
    if (env_enabled_lwjgl_hook("DROIDBRIDGE_SDL3_MESA_SINGLE_LOADER")) {
        const char* path = getenv("SDL_EGL_LIBRARY");
        if (path == NULL || path[0] == '\0') path = getenv("DROIDBRIDGE_MESA_EGL");
        if (path == NULL || path[0] == '\0') {
            printf("DroidBridgeMesaSingleLoader: no configured Mesa EGL path; refusing provider fallback\n");
            fflush(stdout);
            return NULL;
        }
        dlerror();
        void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        printf("DroidBridgeMesaSingleLoader: OpenJDK EGL path=%s handle=%p error=%s\n",
               path, handle, handle ? "none" : (dlerror() ? "dlopen failed" : "unknown"));
        fflush(stdout);
        return handle;
    }

    /* Legacy path, when single-loader mode is not enabled. Acquire a handle
     * through the RenderSpec callback; do not assume egl_handle exists in its
     * struct (it was absent from the historical RenderSpec ABI).
     */
    const droidbridge_renderspec_t* rspec = droidbridge_renderspec_get();
    if (rspec != NULL && rspec->configured && rspec->egl_acquire != NULL
            && rspec->egl_path != NULL) {
        void* handle = rspec->egl_acquire(rspec->egl_path);
        if (handle != NULL) {
            printf("LWJGL linkerhook-v84: SDL3 legacy RenderSpec acquired EGL=%s handle=%p\n",
                   rspec->egl_path, handle);
            fflush(stdout);
            return handle;
        }
    }

    printf("LWJGL linkerhook-v84: SDL3 legacy RenderSpec acquire unavailable; using normal fallback\n");
    fflush(stdout);
    return NULL;
}

static void* acquire_droidbridge_opengl_handle(int mode) {
    void* sdl3_mesa_handle = acquire_sdl3_direct_mesa_without_context();
    if (sdl3_mesa_handle != NULL) return sdl3_mesa_handle;
    if (env_enabled_lwjgl_hook("DROIDBRIDGE_SDL3_MESA_SINGLE_LOADER")) {
        printf("DroidBridgeMesaSingleLoader: EGL acquisition failed; not loading a second provider\n");
        fflush(stdout);
        return NULL;
    }

    void* native_glfw_handle = acquire_native_glfw_opengl_handle_v82();
    if (native_glfw_handle != NULL) return native_glfw_handle;

    /* Kopper/Zink must see the chosen Vulkan ICD before Mesa EGL is opened.
     * This is intentionally placed after the direct-Freedreno/NativeGLFW
     * exits so those renderers, and regular Vulkan Zink, remain unchanged. */
    if (!droidbridge_prepare_kopper_vulkan_before_mesa()) {
        printf("LWJGL linkerhook-v85: Kopper Vulkan loader preparation failed before Mesa; OpenGL proxy acquisition aborted\n");
        fflush(stdout);
        return NULL;
    }

    void* configured_handle = acquire_configured_droidbridge_renderspec();
    if (configured_handle != NULL) return configured_handle;
    void* existing_egl_handle = try_existing_egl_loader_handle_for_direct_mesa();
    if (existing_egl_handle != NULL) return existing_egl_handle;

    int dl_mode = mode;
    if ((dl_mode & RTLD_NOW) == 0 && (dl_mode & RTLD_LAZY) == 0) {
        dl_mode |= RTLD_NOW;
    }
    dl_mode |= RTLD_GLOBAL;

    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    const char* renderer_mesa_mode = getenv("DROIDBRIDGE_RENDERER_MESA_MODE");

    printf("LWJGL linkerhook: DroidBridge RenderSpec request renderer=%s mesaMode=%s mesaDriver=%s rendererMesaMode=%s\n",
           renderer != NULL ? renderer : "",
           mesa_mode != NULL ? mesa_mode : "",
           mesa_driver != NULL ? mesa_driver : "",
           renderer_mesa_mode != NULL ? renderer_mesa_mode : "");

    const char* preferred = first_non_empty(
            getenv("DROIDBRIDGE_RENDERSPEC_EGL"),
            getenv("DROIDBRIDGE_MESA_EGL"),
            getenv("DROIDBRIDGE_EGL"),
            getenv("DROIDBRIDGE_EGL_LIBRARY"),
            getenv("DROIDBRIDGE_EGL_LIBRARY")
    );

    void* handle = try_namespace_dlopen_with_log(preferred, dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log(preferred, dl_mode);
    if (handle != NULL) return handle;

    handle = try_namespace_dlopen_with_log("libEGL_mesa.so", dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log("libEGL_mesa.so", dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log("libEGL.so", dl_mode);
    if (handle != NULL) return handle;

    printf("LWJGL linkerhook: DroidBridge RenderSpec failed; returning NULL for OpenGL proxy\n");
    return NULL;
}
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv* env,
                            __attribute__((unused)) jclass clazz,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;
    int mode = (int) jmode;

    if (is_vulkan_loader_name(filename)) {
        const char* selected_icd = getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME");
        const char* selected_report = getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT");
        const bool strict_custom_icd = droidbridge_truthy_env("DROIDBRIDGE_LOAD_CUSTOM_VULKAN")
                && !droidbridge_truthy_env("DROIDBRIDGE_USE_SYSTEM_VULKAN");
        printf("LWJGL linkerhook-v28: intercepted Vulkan loader request=%s selectedICD=%s report=%s strictCustom=%d\n",
               filename,
               selected_icd != NULL && selected_icd[0] != '\0' ? selected_icd : "<system/default>",
               selected_report != NULL && selected_report[0] != '\0' ? selected_report : "<unset>",
               strict_custom_icd ? 1 : 0);

        void* handle = maybe_load_vulkan();
        if (handle != NULL) {
            printf("LWJGL linkerhook-v28: Vulkan loader %s routed through selected driver handle=%p ICD=%s\n",
                   filename,
                   handle,
                   selected_icd != NULL && selected_icd[0] != '\0' ? selected_icd : "system/default");
            return (jlong) handle;
        }

        if (strict_custom_icd) {
            printf("LWJGL linkerhook-v28: selected custom Vulkan ICD failed; refusing libvulkan.so system fallback\n");
            return (jlong)0;
        }

        printf("LWJGL linkerhook-v28: maybe_load_vulkan() returned NULL; system Vulkan fallback remains allowed for this launch\n");
    }

    if (is_droidbridge_opengl_proxy_name(filename)) {
        printf("LWJGL linkerhook-v74: matched DroidBridge OpenGL proxy filename=%s\n",
               filename != NULL ? filename : "");
        void* handle = acquire_droidbridge_opengl_handle(mode);
        if (handle != NULL) {
            g_droidbridge_opengl_proxy_handle = handle;
            droidbridge_publish_openjdk_ltw_handle(handle);
            if (g_real_glGetString == NULL) {
                /* Keep LTW on the same canonical EGL-resolved entry point from the
                 * first OpenGL symbol lookup onward. Non-LTW renderers retain the
                 * existing dlsym-first behavior inside droidbridge_resolve_gl_symbol(). */
                g_real_glGetString = (db_gl_get_string_fn)
                        droidbridge_resolve_gl_symbol(handle, "glGetString");
                printf("LWJGL linkerhook-v26: glGetString branding filter real=%p handle=%p\n",
                       (void*) g_real_glGetString,
                       handle);
            }
            return (jlong) handle;
        }
    }

    void* handle = dlopen(filename, mode);
    /*
     * Cleanroom's lwjgly runtime owns its SDL3 lifecycle. DroidBridge still
     * supplies the Android SDL binary and ART-side JNI registration, but the
     * Minecraft 26.x native-window/main-ready hook must not rewrite Cleanroom's
     * SDL image. Every non-Cleanroom launch keeps the existing post-load hook.
     */
    if (!droidbridge_truthy_env("DROIDBRIDGE_CLEANROOM_OWNS_SDL3")) {
        droidbridge_sdl3_after_library_load(filename, handle);
    } else if (filename != NULL && strstr(filename, "SDL3") != NULL) {
        /*
         * Cleanroom keeps full SDL lifecycle/window/context ownership.  Apply only
         * the exact-image Java-JNI quarantine needed for Android callbacks that
         * cannot safely run from its embedded OpenJDK thread.  This deliberately
         * does not enable Minecraft 26.x window/main-ready/presentation hooks.
         */
        int java_jni_isolated =
                droidbridge_sdl3_patch_cleanroom_java_callbacks(filename, handle);
        fprintf(stderr,
                "DroidBridgeCleanroomSDL3: leaving Cleanroom SDL lifecycle/window/context unmodified handle=%p file=%s javaJniQuarantineV1=%d\n",
                handle, filename, java_jni_isolated);
        fflush(stderr);
    }
    return (jlong) handle;
}

/*
 * Experimental System Vulkan compatibility layer.
 *
 * Minecraft 26.2 uses VK_KHR_push_descriptor and VK_EXT_multi_draw across its
 * Vulkan renderer. Qualcomm System Vulkan can expose the required feature set
 * while still behaving differently from desktop Vulkan drivers for particular
 * draw/resource paths. DroidBridge can rebuild incremental push-descriptor
 * updates, filter selected extensions, and passively trace image creation,
 * copies, descriptors and synchronization2 layout transitions. Image tracing
 * intentionally does not rewrite barriers/layouts until a failing transition is
 * identified from logs. API-version and vertex-divisor fallbacks remain behind
 * explicit compatibility modes.
 */
typedef struct VkInstance_T* DB_VkInstance;
typedef struct VkPhysicalDevice_T* DB_VkPhysicalDevice;
typedef struct VkDevice_T* DB_VkDevice;
typedef struct VkCommandBuffer_T* DB_VkCommandBuffer;
typedef uint64_t DB_VkPipelineLayout;
typedef uint64_t DB_VkPipeline;
typedef uint64_t DB_VkPipelineCache;
typedef uint64_t DB_VkShaderModule;
typedef uint64_t DB_VkCommandPool;
typedef uint64_t DB_VkSampler;
typedef uint64_t DB_VkImage;
typedef uint64_t DB_VkImageView;
typedef uint64_t DB_VkBuffer;
typedef uint64_t DB_VkBufferView;
typedef uint64_t DB_VkDeviceMemory;
typedef uint64_t DB_VkSurfaceKHR;
typedef uint64_t DB_VkSwapchainKHR;
typedef struct VkQueue_T* DB_VkQueue;
typedef uint64_t DB_VkSemaphore;
typedef uint64_t DB_VkFence;
typedef int32_t DB_VkResult;
typedef int32_t DB_VkPipelineBindPoint;
typedef int32_t DB_VkDescriptorType;
typedef void (*DB_PFN_vkVoidFunction)(void);

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t depth;
} DB_VkExtent3D;

typedef struct {
    uint32_t width;
    uint32_t height;
} DB_VkExtent2D;

typedef struct {
    uint32_t minImageCount;
    uint32_t maxImageCount;
    DB_VkExtent2D currentExtent;
    DB_VkExtent2D minImageExtent;
    DB_VkExtent2D maxImageExtent;
    uint32_t maxImageArrayLayers;
    uint32_t supportedTransforms;
    uint32_t currentTransform;
    uint32_t supportedCompositeAlpha;
    uint32_t supportedUsageFlags;
} DB_VkSurfaceCapabilitiesKHR;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkSurfaceKHR surface;
} DB_VkPhysicalDeviceSurfaceInfo2KHR;

typedef struct {
    int32_t sType;
    void* pNext;
    DB_VkSurfaceCapabilitiesKHR surfaceCapabilities;
} DB_VkSurfaceCapabilities2KHR;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    DB_VkSurfaceKHR surface;
    uint32_t minImageCount;
    int32_t imageFormat;
    int32_t imageColorSpace;
    DB_VkExtent2D imageExtent;
    uint32_t imageArrayLayers;
    uint32_t imageUsage;
    int32_t imageSharingMode;
    uint32_t queueFamilyIndexCount;
    const uint32_t* pQueueFamilyIndices;
    uint32_t preTransform;
    uint32_t compositeAlpha;
    int32_t presentMode;
    uint32_t clipped;
    DB_VkSwapchainKHR oldSwapchain;
} DB_VkSwapchainCreateInfoKHR;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkSwapchainKHR swapchain;
    uint64_t timeout;
    DB_VkSemaphore semaphore;
    DB_VkFence fence;
    uint32_t deviceMask;
} DB_VkAcquireNextImageInfoKHR;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t waitSemaphoreCount;
    const DB_VkSemaphore* pWaitSemaphores;
    uint32_t swapchainCount;
    const DB_VkSwapchainKHR* pSwapchains;
    const uint32_t* pImageIndices;
    DB_VkResult* pResults;
} DB_VkPresentInfoKHR;

typedef struct {
    uint32_t aspectMask;
    uint32_t baseMipLevel;
    uint32_t levelCount;
    uint32_t baseArrayLayer;
    uint32_t layerCount;
} DB_VkImageSubresourceRange;

typedef struct {
    int32_t r;
    int32_t g;
    int32_t b;
    int32_t a;
} DB_VkComponentMapping;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    int32_t imageType;
    int32_t format;
    DB_VkExtent3D extent;
    uint32_t mipLevels;
    uint32_t arrayLayers;
    uint32_t samples;
    int32_t tiling;
    uint32_t usage;
    int32_t sharingMode;
    uint32_t queueFamilyIndexCount;
    const uint32_t* pQueueFamilyIndices;
    int32_t initialLayout;
} DB_VkImageCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    DB_VkImage image;
    int32_t viewType;
    int32_t format;
    DB_VkComponentMapping components;
    DB_VkImageSubresourceRange subresourceRange;
} DB_VkImageViewCreateInfo;

typedef struct {
    uint32_t aspectMask;
    uint32_t mipLevel;
    uint32_t baseArrayLayer;
    uint32_t layerCount;
} DB_VkImageSubresourceLayers;

typedef struct {
    int32_t x;
    int32_t y;
    int32_t z;
} DB_VkOffset3D;

typedef struct {
    uint64_t bufferOffset;
    uint32_t bufferRowLength;
    uint32_t bufferImageHeight;
    DB_VkImageSubresourceLayers imageSubresource;
    DB_VkOffset3D imageOffset;
    DB_VkExtent3D imageExtent;
} DB_VkBufferImageCopy;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkDeviceMemory memory;
    uint64_t offset;
    uint64_t size;
} DB_VkMappedMemoryRange;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkBuffer buffer;
    DB_VkDeviceMemory memory;
    uint64_t memoryOffset;
} DB_VkBindBufferMemoryInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint64_t bufferOffset;
    uint32_t bufferRowLength;
    uint32_t bufferImageHeight;
    DB_VkImageSubresourceLayers imageSubresource;
    DB_VkOffset3D imageOffset;
    DB_VkExtent3D imageExtent;
} DB_VkBufferImageCopy2;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkBuffer srcBuffer;
    DB_VkImage dstImage;
    int32_t dstImageLayout;
    uint32_t regionCount;
    const DB_VkBufferImageCopy2* pRegions;
} DB_VkCopyBufferToImageInfo2;

typedef struct {
    DB_VkImageSubresourceLayers srcSubresource;
    DB_VkOffset3D srcOffset;
    DB_VkImageSubresourceLayers dstSubresource;
    DB_VkOffset3D dstOffset;
    DB_VkExtent3D extent;
} DB_VkImageCopy;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkImage srcImage;
    int32_t srcImageLayout;
    DB_VkImage dstImage;
    int32_t dstImageLayout;
    uint32_t regionCount;
    const void* pRegions;
} DB_VkCopyImageInfo2;

typedef struct {
    DB_VkImageSubresourceLayers srcSubresource;
    DB_VkOffset3D srcOffsets[2];
    DB_VkImageSubresourceLayers dstSubresource;
    DB_VkOffset3D dstOffsets[2];
} DB_VkImageBlit;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkImage srcImage;
    int32_t srcImageLayout;
    DB_VkImage dstImage;
    int32_t dstImageLayout;
    uint32_t regionCount;
    const void* pRegions;
    int32_t filter;
} DB_VkBlitImageInfo2;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t srcAccessMask;
    uint32_t dstAccessMask;
    int32_t oldLayout;
    int32_t newLayout;
    uint32_t srcQueueFamilyIndex;
    uint32_t dstQueueFamilyIndex;
    DB_VkImage image;
    DB_VkImageSubresourceRange subresourceRange;
} DB_VkImageMemoryBarrier;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint64_t srcStageMask;
    uint64_t srcAccessMask;
    uint64_t dstStageMask;
    uint64_t dstAccessMask;
    int32_t oldLayout;
    int32_t newLayout;
    uint32_t srcQueueFamilyIndex;
    uint32_t dstQueueFamilyIndex;
    DB_VkImage image;
    DB_VkImageSubresourceRange subresourceRange;
} DB_VkImageMemoryBarrier2;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t dependencyFlags;
    uint32_t memoryBarrierCount;
    const void* pMemoryBarriers;
    uint32_t bufferMemoryBarrierCount;
    const void* pBufferMemoryBarriers;
    uint32_t imageMemoryBarrierCount;
    const DB_VkImageMemoryBarrier2* pImageMemoryBarriers;
} DB_VkDependencyInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t srcAccessMask;
    uint32_t dstAccessMask;
} DB_VkMemoryBarrier;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint64_t srcStageMask;
    uint64_t srcAccessMask;
    uint64_t dstStageMask;
    uint64_t dstAccessMask;
} DB_VkMemoryBarrier2;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint64_t srcStageMask;
    uint64_t srcAccessMask;
    uint64_t dstStageMask;
    uint64_t dstAccessMask;
    uint32_t srcQueueFamilyIndex;
    uint32_t dstQueueFamilyIndex;
    DB_VkBuffer buffer;
    uint64_t offset;
    uint64_t size;
} DB_VkBufferMemoryBarrier2;

typedef struct {
    uint64_t srcOffset;
    uint64_t dstOffset;
    uint64_t size;
} DB_VkBufferCopy;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint64_t srcOffset;
    uint64_t dstOffset;
    uint64_t size;
} DB_VkBufferCopy2;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkBuffer srcBuffer;
    DB_VkBuffer dstBuffer;
    uint32_t regionCount;
    const DB_VkBufferCopy2* pRegions;
} DB_VkCopyBufferInfo2;

typedef struct {
    int32_t offsetX;
    int32_t offsetY;
    uint32_t width;
    uint32_t height;
} DB_VkRect2DFlat;

typedef struct {
    float x;
    float y;
    float width;
    float height;
    float minDepth;
    float maxDepth;
} DB_VkViewport;

typedef union {
    float float32[4];
    int32_t int32[4];
    uint32_t uint32[4];
} DB_VkClearColorValue;

typedef union {
    DB_VkClearColorValue color;
    struct { float depth; uint32_t stencil; } depthStencil;
} DB_VkClearValue;

typedef struct {
    int32_t sType;
    const void* pNext;
    DB_VkImageView imageView;
    int32_t imageLayout;
    uint32_t resolveMode;
    DB_VkImageView resolveImageView;
    int32_t resolveImageLayout;
    int32_t loadOp;
    int32_t storeOp;
    DB_VkClearValue clearValue;
} DB_VkRenderingAttachmentInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    DB_VkRect2DFlat renderArea;
    uint32_t layerCount;
    uint32_t viewMask;
    uint32_t colorAttachmentCount;
    const DB_VkRenderingAttachmentInfo* pColorAttachments;
    const DB_VkRenderingAttachmentInfo* pDepthAttachment;
    const DB_VkRenderingAttachmentInfo* pStencilAttachment;
} DB_VkRenderingInfo;

typedef struct {
    DB_VkSampler sampler;
    DB_VkImageView imageView;
    int32_t imageLayout;
} DB_VkDescriptorImageInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    int32_t magFilter;
    int32_t minFilter;
    int32_t mipmapMode;
    int32_t addressModeU;
    int32_t addressModeV;
    int32_t addressModeW;
    float mipLodBias;
    uint32_t anisotropyEnable;
    float maxAnisotropy;
    uint32_t compareEnable;
    int32_t compareOp;
    float minLod;
    float maxLod;
    int32_t borderColor;
    uint32_t unnormalizedCoordinates;
} DB_VkSamplerCreateInfo;

typedef struct {
    DB_VkBuffer buffer;
    uint64_t offset;
    uint64_t range;
} DB_VkDescriptorBufferInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint64_t dstSet;
    uint32_t dstBinding;
    uint32_t dstArrayElement;
    uint32_t descriptorCount;
    DB_VkDescriptorType descriptorType;
    const DB_VkDescriptorImageInfo* pImageInfo;
    const DB_VkDescriptorBufferInfo* pBufferInfo;
    const DB_VkBufferView* pTexelBufferView;
} DB_VkWriteDescriptorSet;

typedef struct {
    char extensionName[256];
    uint32_t specVersion;
} DB_VkExtensionProperties;

typedef struct {
    uint32_t apiVersion;
    uint32_t driverVersion;
    uint32_t vendorID;
    uint32_t deviceID;
    int32_t deviceType;
    char deviceName[256];
} DB_VkPhysicalDevicePropertiesPrefix;

typedef struct {
    int32_t sType;
    void* pNext;
    DB_VkPhysicalDevicePropertiesPrefix properties;
} DB_VkPhysicalDeviceProperties2Prefix;

typedef struct {
    int32_t sType;
    void* pNext;
} DB_VkBaseOutStructure;

typedef struct {
    int32_t sType;
    void* pNext;
} DB_VkPhysicalDeviceFeatures2Prefix;

typedef struct {
    int32_t sType;
    void* pNext;
    uint32_t vertexAttributeInstanceRateDivisor;
    uint32_t vertexAttributeInstanceRateZeroDivisor;
} DB_VkPhysicalDeviceVertexAttributeDivisorFeatures;

typedef struct {
    int32_t sType;
    const void* pNext;
} DB_VkBaseInStructure;

typedef struct {
    uint32_t binding;
    uint32_t stride;
    uint32_t inputRate;
} DB_VkVertexInputBindingDescription;

typedef struct {
    uint32_t location;
    uint32_t binding;
    int32_t format;
    uint32_t offset;
} DB_VkVertexInputAttributeDescription;

#define DB_VK_FORMAT_R8G8B8_SNORM 24
#define DB_VK_FORMAT_R8G8B8A8_SNORM 38

typedef struct {
    uint32_t binding;
    uint32_t divisor;
} DB_VkVertexInputBindingDivisorDescriptionEXT;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t vertexBindingDescriptionCount;
    const DB_VkVertexInputBindingDescription* pVertexBindingDescriptions;
    uint32_t vertexAttributeDescriptionCount;
    const DB_VkVertexInputAttributeDescription* pVertexAttributeDescriptions;
} DB_VkPipelineVertexInputStateCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t vertexBindingDivisorCount;
    const DB_VkVertexInputBindingDivisorDescriptionEXT* pVertexBindingDivisors;
} DB_VkPipelineVertexInputDivisorStateCreateInfoEXT;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t stage;
    DB_VkShaderModule module;
    const char* pName;
    const void* pSpecializationInfo;
} DB_VkPipelineShaderStageCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    int32_t topology;
    uint32_t primitiveRestartEnable;
} DB_VkPipelineInputAssemblyStateCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t depthClampEnable;
    uint32_t rasterizerDiscardEnable;
    int32_t polygonMode;
    uint32_t cullMode;
    int32_t frontFace;
    uint32_t depthBiasEnable;
    float depthBiasConstantFactor;
    float depthBiasClamp;
    float depthBiasSlopeFactor;
    float lineWidth;
} DB_VkPipelineRasterizationStateCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t rasterizationSamples;
    uint32_t sampleShadingEnable;
    float minSampleShading;
    const uint32_t* pSampleMask;
    uint32_t alphaToCoverageEnable;
    uint32_t alphaToOneEnable;
} DB_VkPipelineMultisampleStateCreateInfo;

typedef struct {
    int32_t failOp;
    int32_t passOp;
    int32_t depthFailOp;
    int32_t compareOp;
    uint32_t compareMask;
    uint32_t writeMask;
    uint32_t reference;
} DB_VkStencilOpState;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t depthTestEnable;
    uint32_t depthWriteEnable;
    int32_t depthCompareOp;
    uint32_t depthBoundsTestEnable;
    uint32_t stencilTestEnable;
    DB_VkStencilOpState front;
    DB_VkStencilOpState back;
    float minDepthBounds;
    float maxDepthBounds;
} DB_VkPipelineDepthStencilStateCreateInfo;

typedef struct {
    uint32_t blendEnable;
    int32_t srcColorBlendFactor;
    int32_t dstColorBlendFactor;
    int32_t colorBlendOp;
    int32_t srcAlphaBlendFactor;
    int32_t dstAlphaBlendFactor;
    int32_t alphaBlendOp;
    uint32_t colorWriteMask;
} DB_VkPipelineColorBlendAttachmentState;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t logicOpEnable;
    int32_t logicOp;
    uint32_t attachmentCount;
    const DB_VkPipelineColorBlendAttachmentState* pAttachments;
    float blendConstants[4];
} DB_VkPipelineColorBlendStateCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t dynamicStateCount;
    const int32_t* pDynamicStates;
} DB_VkPipelineDynamicStateCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t viewMask;
    uint32_t colorAttachmentCount;
    const int32_t* pColorAttachmentFormats;
    int32_t depthAttachmentFormat;
    int32_t stencilAttachmentFormat;
} DB_VkPipelineRenderingCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    size_t codeSize;
    const uint32_t* pCode;
} DB_VkShaderModuleCreateInfo;

typedef struct {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    uint32_t stageCount;
    const DB_VkPipelineShaderStageCreateInfo* pStages;
    const DB_VkPipelineVertexInputStateCreateInfo* pVertexInputState;
    const DB_VkPipelineInputAssemblyStateCreateInfo* pInputAssemblyState;
    const void* pTessellationState;
    const void* pViewportState;
    const DB_VkPipelineRasterizationStateCreateInfo* pRasterizationState;
    const DB_VkPipelineMultisampleStateCreateInfo* pMultisampleState;
    const DB_VkPipelineDepthStencilStateCreateInfo* pDepthStencilState;
    const DB_VkPipelineColorBlendStateCreateInfo* pColorBlendState;
    const DB_VkPipelineDynamicStateCreateInfo* pDynamicState;
    DB_VkPipelineLayout layout;
    uint64_t renderPass;
    uint32_t subpass;
    DB_VkPipeline basePipelineHandle;
    int32_t basePipelineIndex;
} DB_VkGraphicsPipelineCreateInfoPrefix;

typedef struct {
    int32_t sType;
    const void* pNext;
    int32_t objectType;
    uint64_t objectHandle;
    const char* pObjectName;
} DB_VkDebugUtilsObjectNameInfoEXT;

typedef DB_PFN_vkVoidFunction (*DB_PFN_vkGetInstanceProcAddr)(DB_VkInstance, const char*);
typedef DB_PFN_vkVoidFunction (*DB_PFN_vkGetDeviceProcAddr)(DB_VkDevice, const char*);
typedef DB_VkResult (*DB_PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)(
        DB_VkPhysicalDevice, DB_VkSurfaceKHR, DB_VkSurfaceCapabilitiesKHR*);
typedef DB_VkResult (*DB_PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR)(
        DB_VkPhysicalDevice, const DB_VkPhysicalDeviceSurfaceInfo2KHR*, DB_VkSurfaceCapabilities2KHR*);
typedef DB_VkResult (*DB_PFN_vkCreateSwapchainKHR)(
        DB_VkDevice, const DB_VkSwapchainCreateInfoKHR*, const void*, DB_VkSwapchainKHR*);
typedef DB_VkResult (*DB_PFN_vkAcquireNextImageKHR)(
        DB_VkDevice, DB_VkSwapchainKHR, uint64_t, DB_VkSemaphore, DB_VkFence, uint32_t*);
typedef DB_VkResult (*DB_PFN_vkAcquireNextImage2KHR)(
        DB_VkDevice, const DB_VkAcquireNextImageInfoKHR*, uint32_t*);
typedef DB_VkResult (*DB_PFN_vkQueuePresentKHR)(
        DB_VkQueue, const DB_VkPresentInfoKHR*);
typedef DB_VkResult (*DB_PFN_vkEnumerateDeviceExtensionProperties)(
        DB_VkPhysicalDevice, const char*, uint32_t*, DB_VkExtensionProperties*);
typedef void (*DB_PFN_vkGetPhysicalDeviceProperties)(
        DB_VkPhysicalDevice, DB_VkPhysicalDevicePropertiesPrefix*);
typedef void (*DB_PFN_vkGetPhysicalDeviceProperties2)(
        DB_VkPhysicalDevice, DB_VkPhysicalDeviceProperties2Prefix*);
typedef void (*DB_PFN_vkGetPhysicalDeviceFeatures2)(
        DB_VkPhysicalDevice, DB_VkPhysicalDeviceFeatures2Prefix*);
typedef void (*DB_PFN_vkCmdPushDescriptorSetKHR)(
        DB_VkCommandBuffer, DB_VkPipelineBindPoint, DB_VkPipelineLayout,
        uint32_t, uint32_t, const DB_VkWriteDescriptorSet*);
typedef DB_VkResult (*DB_PFN_vkCreateGraphicsPipelines)(
        DB_VkDevice, DB_VkPipelineCache, uint32_t,
        const DB_VkGraphicsPipelineCreateInfoPrefix*, const void*, DB_VkPipeline*);
typedef DB_VkResult (*DB_PFN_vkCreateShaderModule)(
        DB_VkDevice, const DB_VkShaderModuleCreateInfo*, const void*, DB_VkShaderModule*);
typedef void (*DB_PFN_vkDestroyShaderModule)(DB_VkDevice, DB_VkShaderModule, const void*);
typedef void (*DB_PFN_vkCmdBindPipeline)(
        DB_VkCommandBuffer, DB_VkPipelineBindPoint, DB_VkPipeline);
typedef void (*DB_PFN_vkCmdSetViewport)(
        DB_VkCommandBuffer, uint32_t, uint32_t, const DB_VkViewport*);
typedef void (*DB_PFN_vkCmdSetScissor)(
        DB_VkCommandBuffer, uint32_t, uint32_t, const DB_VkRect2DFlat*);
typedef void (*DB_PFN_vkCmdSetScissorWithCount)(
        DB_VkCommandBuffer, uint32_t, const DB_VkRect2DFlat*);
typedef void (*DB_PFN_vkCmdDraw)(
        DB_VkCommandBuffer, uint32_t, uint32_t, uint32_t, uint32_t);
typedef DB_VkResult (*DB_PFN_vkSetDebugUtilsObjectNameEXT)(
        DB_VkDevice, const DB_VkDebugUtilsObjectNameInfoEXT*);
typedef DB_VkResult (*DB_PFN_vkCreateSampler)(
        DB_VkDevice, const DB_VkSamplerCreateInfo*, const void*, DB_VkSampler*);
typedef void (*DB_PFN_vkDestroySampler)(DB_VkDevice, DB_VkSampler, const void*);
typedef DB_VkResult (*DB_PFN_vkMapMemory)(
        DB_VkDevice, DB_VkDeviceMemory, uint64_t, uint64_t, uint32_t, void**);
typedef void (*DB_PFN_vkUnmapMemory)(DB_VkDevice, DB_VkDeviceMemory);
typedef DB_VkResult (*DB_PFN_vkFlushMappedMemoryRanges)(
        DB_VkDevice, uint32_t, const DB_VkMappedMemoryRange*);
typedef DB_VkResult (*DB_PFN_vkBindBufferMemory)(
        DB_VkDevice, DB_VkBuffer, DB_VkDeviceMemory, uint64_t);
typedef DB_VkResult (*DB_PFN_vkBindBufferMemory2)(
        DB_VkDevice, uint32_t, const DB_VkBindBufferMemoryInfo*);
typedef DB_VkResult (*DB_PFN_vkCreateImage)(
        DB_VkDevice, const DB_VkImageCreateInfo*, const void*, DB_VkImage*);
typedef void (*DB_PFN_vkDestroyImage)(DB_VkDevice, DB_VkImage, const void*);
typedef DB_VkResult (*DB_PFN_vkCreateImageView)(
        DB_VkDevice, const DB_VkImageViewCreateInfo*, const void*, DB_VkImageView*);
typedef void (*DB_PFN_vkDestroyImageView)(DB_VkDevice, DB_VkImageView, const void*);
typedef void (*DB_PFN_vkCmdCopyBuffer)(
        DB_VkCommandBuffer, DB_VkBuffer, DB_VkBuffer,
        uint32_t, const DB_VkBufferCopy*);
typedef void (*DB_PFN_vkCmdCopyBuffer2)(
        DB_VkCommandBuffer, const DB_VkCopyBufferInfo2*);
typedef void (*DB_PFN_vkCmdUpdateBuffer)(
        DB_VkCommandBuffer, DB_VkBuffer, uint64_t, uint64_t, const void*);
typedef void (*DB_PFN_vkCmdCopyBufferToImage)(
        DB_VkCommandBuffer, DB_VkBuffer, DB_VkImage, int32_t,
        uint32_t, const DB_VkBufferImageCopy*);
typedef void (*DB_PFN_vkCmdCopyBufferToImage2)(
        DB_VkCommandBuffer, const DB_VkCopyBufferToImageInfo2*);
typedef void (*DB_PFN_vkCmdCopyImage)(
        DB_VkCommandBuffer, DB_VkImage, int32_t, DB_VkImage, int32_t,
        uint32_t, const DB_VkImageCopy*);
typedef void (*DB_PFN_vkCmdCopyImage2)(
        DB_VkCommandBuffer, const DB_VkCopyImageInfo2*);
typedef void (*DB_PFN_vkCmdBlitImage)(
        DB_VkCommandBuffer, DB_VkImage, int32_t, DB_VkImage, int32_t,
        uint32_t, const DB_VkImageBlit*, int32_t);
typedef void (*DB_PFN_vkCmdBlitImage2)(
        DB_VkCommandBuffer, const DB_VkBlitImageInfo2*);
typedef void (*DB_PFN_vkCmdPipelineBarrier)(
        DB_VkCommandBuffer, uint32_t, uint32_t, uint32_t,
        uint32_t, const void*, uint32_t, const void*,
        uint32_t, const DB_VkImageMemoryBarrier*);
typedef void (*DB_PFN_vkCmdPipelineBarrier2)(
        DB_VkCommandBuffer, const DB_VkDependencyInfo*);
typedef void (*DB_PFN_vkCmdBeginRendering)(
        DB_VkCommandBuffer, const DB_VkRenderingInfo*);
typedef void (*DB_PFN_vkCmdEndRendering)(DB_VkCommandBuffer);
typedef DB_VkResult (*DB_PFN_vkBeginCommandBuffer)(DB_VkCommandBuffer, const void*);
typedef DB_VkResult (*DB_PFN_vkResetCommandBuffer)(DB_VkCommandBuffer, uint32_t);
typedef void (*DB_PFN_vkFreeCommandBuffers)(
        DB_VkDevice, DB_VkCommandPool, uint32_t, const DB_VkCommandBuffer*);

#define DB_VK_SUCCESS 0
#define DB_VK_SUBOPTIMAL_KHR 1000001003
#define DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR 0x00000001u
#define DB_VK_INCOMPLETE 5
#define DB_VK_ERROR_INITIALIZATION_FAILED (-3)
#define DB_VK_ERROR_INVALID_EXTERNAL_HANDLE (-1000072003)
#define DB_VK_PRESENT_MODE_IMMEDIATE_KHR 0
#define DB_VK_PRESENT_MODE_MAILBOX_KHR 1
#define DB_VK_PRESENT_MODE_FIFO_KHR 2
#define DB_VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE 6
#define DB_VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET 35
#define DB_VK_WHOLE_SIZE (~0ULL)
#define DB_VK_STRUCTURE_TYPE_MEMORY_BARRIER 46
#define DB_VK_STRUCTURE_TYPE_MEMORY_BARRIER_2 1000314000
#define DB_VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2 1000314001
#define DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2 1000314002
#define DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO 1000314003
#define DB_VK_PIPELINE_STAGE_ALL_COMMANDS_BIT 0x00010000u
#define DB_VK_ACCESS_MEMORY_READ_BIT 0x00008000u
#define DB_VK_ACCESS_MEMORY_WRITE_BIT 0x00010000u
#define DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT 0x0000000000010000ULL
#define DB_VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT 0x0000000000000008ULL
#define DB_VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT 0x0000000000000080ULL
#define DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT 0x0000000000001000ULL
#define DB_VK_PIPELINE_STAGE_2_HOST_BIT 0x0000000000004000ULL
#define DB_VK_ACCESS_2_UNIFORM_READ_BIT 0x0000000000000008ULL
#define DB_VK_ACCESS_2_TRANSFER_WRITE_BIT 0x0000000000001000ULL
#define DB_VK_ACCESS_2_HOST_WRITE_BIT 0x0000000000004000ULL
#define DB_VK_ACCESS_2_MEMORY_READ_BIT 0x0000000000008000ULL
#define DB_VK_ACCESS_2_MEMORY_WRITE_BIT 0x0000000000010000ULL
#define DB_VK_IMAGE_USAGE_TRANSFER_SRC_BIT 0x00000001u
#define DB_VK_IMAGE_USAGE_TRANSFER_DST_BIT 0x00000002u
#define DB_VK_IMAGE_USAGE_SAMPLED_BIT 0x00000004u
#define DB_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT 0x00000010u
#define DB_VK_IMAGE_ASPECT_COLOR_BIT 0x00000001u
#define DB_VK_QUEUE_FAMILY_IGNORED 0xffffffffu
#define DB_VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES 1000190002
#define DB_VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_DIVISOR_STATE_CREATE_INFO_EXT 1000190001
#define DB_VK_OBJECT_TYPE_SHADER_MODULE 15
#define DB_VK_SHADER_STAGE_VERTEX_BIT 0x00000001u
#define DB_VK_SHADER_STAGE_FRAGMENT_BIT 0x00000010u
#define DB_VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO 1000044002
#define DB_VK_PIPELINE_BIND_POINT_GRAPHICS 0
#define DB_VK_OBJECT_TYPE_PIPELINE_LAYOUT 17
#define DB_VK_OBJECT_TYPE_PIPELINE 19
#define DB_VK_API_VERSION_1_3 0x00403000u
#define DB_VK_DESCRIPTOR_TYPE_SAMPLER 0
#define DB_VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER 1
#define DB_VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE 2
#define DB_VK_DESCRIPTOR_TYPE_STORAGE_IMAGE 3
#define DB_VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER 4
#define DB_VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER 5
#define DB_VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER 6
#define DB_VK_DESCRIPTOR_TYPE_STORAGE_BUFFER 7
#define DB_VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC 8
#define DB_VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC 9
#define DB_VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT 10
#define DB_VK_IMAGE_LAYOUT_UNDEFINED 0
#define DB_VK_IMAGE_LAYOUT_GENERAL 1
#define DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL 5
#define DB_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL 6
#define DB_VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL 7

static DB_PFN_vkGetInstanceProcAddr g_db_real_vkGetInstanceProcAddr = NULL;
static DB_PFN_vkGetDeviceProcAddr g_db_real_vkGetDeviceProcAddr = NULL;
static DB_PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR g_db_real_vkGetPhysicalDeviceSurfaceCapabilitiesKHR = NULL;
static DB_PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR g_db_real_vkGetPhysicalDeviceSurfaceCapabilities2KHR = NULL;
static DB_PFN_vkCreateSwapchainKHR g_db_real_vkCreateSwapchainKHR = NULL;
static DB_PFN_vkAcquireNextImageKHR g_db_real_vkAcquireNextImageKHR = NULL;
static DB_PFN_vkAcquireNextImage2KHR g_db_real_vkAcquireNextImage2KHR = NULL;
static DB_PFN_vkQueuePresentKHR g_db_real_vkQueuePresentKHR = NULL;
static pthread_mutex_t g_db_vk_surface_transform_mutex = PTHREAD_MUTEX_INITIALIZER;
static DB_VkSurfaceKHR g_db_vk_surface_transform_surface = 0u;
static uint32_t g_db_vk_surface_supported_transforms = 0u;
static uint32_t g_db_vk_surface_real_current_transform = 0u;
static DB_VkExtent2D g_db_vk_surface_current_extent = {0u, 0u};
static DB_VkSwapchainKHR g_db_vk_identity_swapchain = 0u;
static DB_VkExtent2D g_db_vk_identity_swapchain_extent = {0u, 0u};
static bool g_db_vk_surface_identity_rejected = false;
static bool g_db_vk_identity_suboptimal_logged = false;
static uint64_t g_db_vk_identity_suboptimal_suppressed = 0u;
static bool g_db_vk_surface_transform_logged = false;
static bool g_db_vk_swapchain_transform_logged = false;
static DB_PFN_vkEnumerateDeviceExtensionProperties g_db_real_vkEnumerateDeviceExtensionProperties = NULL;
static DB_PFN_vkGetPhysicalDeviceProperties g_db_real_vkGetPhysicalDeviceProperties = NULL;
static DB_PFN_vkGetPhysicalDeviceProperties2 g_db_real_vkGetPhysicalDeviceProperties2 = NULL;
static DB_PFN_vkGetPhysicalDeviceFeatures2 g_db_real_vkGetPhysicalDeviceFeatures2 = NULL;
static DB_PFN_vkCmdPushDescriptorSetKHR g_db_real_vkCmdPushDescriptorSetKHR = NULL;
static DB_PFN_vkCreateGraphicsPipelines g_db_real_vkCreateGraphicsPipelines = NULL;
static DB_PFN_vkCreateShaderModule g_db_real_vkCreateShaderModule = NULL;
static DB_PFN_vkDestroyShaderModule g_db_real_vkDestroyShaderModule = NULL;
static DB_PFN_vkCmdBindPipeline g_db_real_vkCmdBindPipeline = NULL;
static DB_PFN_vkCmdSetViewport g_db_real_vkCmdSetViewport = NULL;
static DB_PFN_vkCmdSetScissor g_db_real_vkCmdSetScissor = NULL;
static DB_PFN_vkCmdSetScissorWithCount g_db_real_vkCmdSetScissorWithCount = NULL;
static DB_PFN_vkCmdDraw g_db_real_vkCmdDraw = NULL;
static DB_PFN_vkSetDebugUtilsObjectNameEXT g_db_real_vkSetDebugUtilsObjectNameEXT = NULL;
static DB_PFN_vkCreateSampler g_db_real_vkCreateSampler = NULL;
static DB_PFN_vkDestroySampler g_db_real_vkDestroySampler = NULL;
static DB_PFN_vkMapMemory g_db_real_vkMapMemory = NULL;
static DB_PFN_vkUnmapMemory g_db_real_vkUnmapMemory = NULL;
static DB_PFN_vkFlushMappedMemoryRanges g_db_real_vkFlushMappedMemoryRanges = NULL;
static DB_PFN_vkBindBufferMemory g_db_real_vkBindBufferMemory = NULL;
static DB_PFN_vkBindBufferMemory2 g_db_real_vkBindBufferMemory2 = NULL;
static DB_PFN_vkCreateImage g_db_real_vkCreateImage = NULL;
static DB_PFN_vkDestroyImage g_db_real_vkDestroyImage = NULL;
static DB_PFN_vkCreateImageView g_db_real_vkCreateImageView = NULL;
static DB_PFN_vkDestroyImageView g_db_real_vkDestroyImageView = NULL;
static DB_PFN_vkCmdCopyBuffer g_db_real_vkCmdCopyBuffer = NULL;
static DB_PFN_vkCmdCopyBuffer2 g_db_real_vkCmdCopyBuffer2 = NULL;
static DB_PFN_vkCmdUpdateBuffer g_db_real_vkCmdUpdateBuffer = NULL;
static DB_PFN_vkCmdCopyBufferToImage g_db_real_vkCmdCopyBufferToImage = NULL;
static DB_PFN_vkCmdCopyBufferToImage2 g_db_real_vkCmdCopyBufferToImage2 = NULL;
static DB_PFN_vkCmdCopyImage g_db_real_vkCmdCopyImage = NULL;
static DB_PFN_vkCmdCopyImage2 g_db_real_vkCmdCopyImage2 = NULL;
static DB_PFN_vkCmdBlitImage g_db_real_vkCmdBlitImage = NULL;
static DB_PFN_vkCmdBlitImage2 g_db_real_vkCmdBlitImage2 = NULL;
static DB_PFN_vkCmdPipelineBarrier g_db_real_vkCmdPipelineBarrier = NULL;
static DB_PFN_vkCmdPipelineBarrier2 g_db_real_vkCmdPipelineBarrier2 = NULL;
static DB_PFN_vkCmdBeginRendering g_db_real_vkCmdBeginRendering = NULL;
static DB_PFN_vkCmdEndRendering g_db_real_vkCmdEndRendering = NULL;
static DB_PFN_vkBeginCommandBuffer g_db_real_vkBeginCommandBuffer = NULL;
static DB_PFN_vkResetCommandBuffer g_db_real_vkResetCommandBuffer = NULL;
static DB_PFN_vkFreeCommandBuffers g_db_real_vkFreeCommandBuffers = NULL;
static bool g_db_vk_compat_logged = false;
static bool g_db_vk_device_logged = false;
static bool g_db_vk_multi_draw_logged = false;
static bool g_db_vk_zero_divisor_logged = false;
static bool g_db_vk_api_cap_logged = false;
static bool g_db_vk_unsupported_push_logged = false;
static bool g_db_vk_proxy_gipa_logged = false;
static bool g_db_vk_proxy_gdpa_logged = false;
static bool g_db_vk_proxy_symbol_logged = false;
static bool g_db_vk_push_intercept_logged = false;
static bool g_db_vk_image_diag_intercept_logged = false;
static void* g_db_vk_proxy_loader_handle = NULL;
static uint64_t g_db_vk_normalized_push_calls = 0;
static uint64_t g_db_vk_suspicious_image_descriptors = 0;
static uint64_t g_db_vk_created_images = 0;
static uint64_t g_db_vk_created_image_views = 0;
static uint64_t g_db_vk_copy_buffer_to_image_calls = 0;
static uint64_t g_db_vk_copy_image_calls = 0;
static uint64_t g_db_vk_blit_image_calls = 0;
static uint64_t g_db_vk_pipeline_barrier_calls = 0;
static uint64_t g_db_vk_pipeline_barrier2_calls = 0;
static uint64_t g_db_vk_panvk_g57_render_area_clamps = 0;
static uint64_t g_db_vk_panvk_g57_scissor_clamps = 0;
static uint64_t g_db_vk_observed_push_calls = 0;
static uint64_t g_db_vk_graphics_pipeline_calls = 0;
static uint64_t g_db_vk_debug_name_calls = 0;
static bool g_db_vk_shader_toolchain_logged = false;
static bool g_db_vk_pipeline_diag_intercept_logged = false;
static bool g_db_vk_atlas_sync_intercept_logged = false;
static uint64_t g_db_vk_atlas_sync_barriers = 0;
static bool g_db_vk_atlas_source_sync_intercept_logged = false;
static bool g_db_vk_atlas_source_sync_missing_barrier_logged = false;
static uint64_t g_db_vk_atlas_source_sync_barriers = 0;
static bool g_db_vk_atlas_build_input_audit_logged = false;
static uint64_t g_db_vk_atlas_build_scope_serial = 0;
static uint64_t g_db_vk_atlas_build_input_packets = 0;
static bool g_db_vk_static_atlas_layout_repair_logged = false;
static uint64_t g_db_vk_static_atlas_layout_transitions = 0;
static uint64_t g_db_vk_static_atlas_layout_descriptor_rewrites = 0;
static bool g_db_vk_sprite_upload_layout_repair_logged = false;
static uint64_t g_db_vk_sprite_upload_layout_transitions = 0;
static uint64_t g_db_vk_sprite_upload_layout_reopens = 0;
static uint64_t g_db_vk_sprite_upload_layout_descriptor_rewrites = 0;
static bool g_db_vk_asset_staging_repair_logged = false;
static uint64_t g_db_vk_asset_staging_flush_attempts = 0;
static uint64_t g_db_vk_asset_staging_flush_successes = 0;
static uint64_t g_db_vk_asset_staging_hashes = 0;
static bool g_db_vk_atlas_ubo_flush_repair_logged = false;
static bool g_db_vk_atlas_ubo_flush_missing_barrier_logged = false;
static uint64_t g_db_vk_atlas_ubo_flush_packets = 0;
static uint64_t g_db_vk_atlas_ubo_flush_successes = 0;
static uint64_t g_db_vk_atlas_ubo_buffer_barriers = 0;
static uint64_t g_db_vk_atlas_ubo_source_flushes = 0;

typedef enum {
    DB_VK_BUFFER_WRITE_NONE = 0,
    DB_VK_BUFFER_WRITE_COPY = 1,
    DB_VK_BUFFER_WRITE_UPDATE = 2
} db_vk_buffer_write_kind;

typedef struct db_vk_buffer_write_record {
    DB_VkBuffer dstBuffer;
    DB_VkBuffer srcBuffer;
    uint64_t srcOffset;
    uint64_t dstOffset;
    uint64_t size;
    uint64_t updateHash;
    uint64_t updateNonZeroBytes;
    uint64_t sequence;
    db_vk_buffer_write_kind kind;
    struct db_vk_buffer_write_record* next;
} db_vk_buffer_write_record;
static bool g_db_vk_special_draw_sync_intercept_logged = false;
static bool g_db_vk_special_draw_sync_missing_barrier_logged = false;
static uint64_t g_db_vk_special_draw_sync_barriers = 0;
static bool g_db_vk_entity_normal_format_intercept_logged = false;
static uint64_t g_db_vk_entity_normal_format_rewrites = 0;
static bool g_db_vk_entity_aux_intercept_logged = false;
static uint64_t g_db_vk_entity_aux_packets = 0;

typedef struct db_vk_memory_map_record {
    DB_VkDevice device;
    DB_VkDeviceMemory memory;
    void* mapped;
    uint64_t mapOffset;
    uint64_t mapSize;
    struct db_vk_memory_map_record* next;
} db_vk_memory_map_record;

typedef struct db_vk_buffer_memory_record {
    DB_VkDevice device;
    DB_VkBuffer buffer;
    DB_VkDeviceMemory memory;
    uint64_t memoryOffset;
    struct db_vk_buffer_memory_record* next;
} db_vk_buffer_memory_record;

static pthread_mutex_t g_db_vk_memory_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_vk_memory_map_record* g_db_vk_memory_maps = NULL;
static db_vk_buffer_memory_record* g_db_vk_buffer_bindings = NULL;
static db_vk_buffer_write_record* g_db_vk_buffer_writes = NULL;
static uint64_t g_db_vk_buffer_write_sequence = 0;

typedef struct db_vk_image_record {
    DB_VkImage image;
    uint32_t width;
    uint32_t height;
    uint32_t depth;
    uint32_t mipLevels;
    uint32_t arrayLayers;
    int32_t format;
    uint32_t usage;
    uint32_t copyLogCount;
    uint32_t barrierLogCount;
    uint64_t bufferUploadCalls;
    uint64_t imageCopyWriteCalls;
    uint64_t imageCopyReadCalls;
    uint64_t imageBlitWriteCalls;
    uint64_t imageBlitReadCalls;
    uint64_t renderTargetBeginCalls;
    uint64_t barrierCalls;
    int32_t lastRenderLoadOp;
    int32_t lastRenderStoreOp;
    float lastRenderClearColor[4];
    int32_t lastOldLayout;
    int32_t lastNewLayout;
    uint64_t lastSrcStageMask;
    uint64_t lastSrcAccessMask;
    uint64_t lastDstStageMask;
    uint64_t lastDstAccessMask;
    DB_VkBuffer lastUploadBuffer;
    uint64_t lastUploadBufferOffset;
    uint32_t lastUploadBufferRowLength;
    uint32_t lastUploadBufferImageHeight;
    uint32_t lastUploadWidth;
    uint32_t lastUploadHeight;
    uint32_t lastUploadDepth;
    uint32_t lastUploadMipLevel;
    uint32_t lastUploadBaseArrayLayer;
    uint32_t lastUploadLayerCount;
    int32_t lastUploadLayout;
    bool staticShaderReadLayoutRepair;
    bool spriteUploadShaderReadLayoutRepair;
    struct db_vk_image_record* next;
} db_vk_image_record;

typedef struct db_vk_image_view_record {
    DB_VkImageView view;
    DB_VkImage image;
    int32_t format;
    bool descriptorLogged;
    struct db_vk_image_view_record* next;
} db_vk_image_view_record;

typedef struct {
    DB_VkImage image;
    uint32_t width;
    uint32_t height;
    uint32_t depth;
    uint32_t mipLevels;
    uint32_t arrayLayers;
    int32_t format;
    uint32_t usage;
    uint64_t bufferUploadCalls;
    uint64_t imageCopyWriteCalls;
    uint64_t imageCopyReadCalls;
    uint64_t imageBlitWriteCalls;
    uint64_t imageBlitReadCalls;
    uint64_t renderTargetBeginCalls;
    uint64_t barrierCalls;
    int32_t lastRenderLoadOp;
    int32_t lastRenderStoreOp;
    float lastRenderClearColor[4];
    int32_t lastOldLayout;
    int32_t lastNewLayout;
    uint64_t lastSrcStageMask;
    uint64_t lastSrcAccessMask;
    uint64_t lastDstStageMask;
    uint64_t lastDstAccessMask;
    DB_VkBuffer lastUploadBuffer;
    uint64_t lastUploadBufferOffset;
    uint32_t lastUploadBufferRowLength;
    uint32_t lastUploadBufferImageHeight;
    uint32_t lastUploadWidth;
    uint32_t lastUploadHeight;
    uint32_t lastUploadDepth;
    uint32_t lastUploadMipLevel;
    uint32_t lastUploadBaseArrayLayer;
    uint32_t lastUploadLayerCount;
    int32_t lastUploadLayout;
    bool staticShaderReadLayoutRepair;
    bool spriteUploadShaderReadLayoutRepair;
} db_vk_image_summary;

// Qualcomm atlas compositor bypass. The known-good items-atlas copy proved that
// paintings/banner_patterns can sample their original target images/views correctly
// once valid pixels exist. The V4 coverage probe then exposed Mojang's exact atlas
// vertex UBO layout: SpriteAnimationInfo = ProjectionMatrix + SpriteMatrix + padding.
// V5 keeps every normal atlas shader untouched and rebuilds only the small static
// 512x256/1024x512 atlases from the live source VkImages. Exact destination placement
// is decoded from SpriteMatrix (scale + translation), including Mojang's one-pixel
// repeat padding border, rather than guessed from viewport/scissor state.
#define DB_VK_ATLAS_COMPOSE_MAX_PACKETS 128u
typedef struct {
    db_vk_image_summary source;
    int32_t dstOuterX;
    int32_t dstOuterY;
    uint32_t dstOuterWidth;
    uint32_t dstOuterHeight;
    uint32_t dstInnerX;
    uint32_t dstInnerY;
    uint32_t dstInnerWidth;
    uint32_t dstInnerHeight;
    float uPadding;
    float vPadding;
    int32_t mipMapLevel;
    bool placementValid;
} db_vk_atlas_compose_packet;
static bool g_db_vk_atlas_direct_compose_logged = false;
static uint64_t g_db_vk_atlas_direct_compose_scopes = 0u;
static uint64_t g_db_vk_atlas_direct_compose_copies = 0u;
static uint64_t g_db_vk_atlas_direct_compose_unresolved = 0u;
static uint64_t g_db_vk_atlas_direct_compose_transfer_src_images = 0u;
// Legacy content-copy isolation state retained for source compatibility; the
// launcher no longer enables this mode in the direct-compositor build.
static bool g_db_vk_atlas_image_copy_test_logged = false;
static bool g_db_vk_atlas_image_copy_items_seen = false;
static db_vk_image_summary g_db_vk_atlas_copy_painting_target;
static db_vk_image_summary g_db_vk_atlas_copy_static_1024_targets[2];
static uint32_t g_db_vk_atlas_copy_static_1024_count = 0u;
static uint64_t g_db_vk_atlas_image_copy_operations = 0u;


typedef struct db_vk_sampler_record {
    DB_VkSampler sampler;
    int32_t magFilter;
    int32_t minFilter;
    int32_t mipmapMode;
    int32_t addressModeU;
    int32_t addressModeV;
    int32_t addressModeW;
    uint32_t anisotropyEnable;
    float maxAnisotropy;
    uint32_t compareEnable;
    int32_t compareOp;
    float minLod;
    float maxLod;
    uint32_t unnormalizedCoordinates;
    struct db_vk_sampler_record* next;
} db_vk_sampler_record;

typedef struct {
    bool found;
    int32_t magFilter;
    int32_t minFilter;
    int32_t mipmapMode;
    int32_t addressModeU;
    int32_t addressModeV;
    int32_t addressModeW;
    uint32_t anisotropyEnable;
    float maxAnisotropy;
    uint32_t compareEnable;
    int32_t compareOp;
    float minLod;
    float maxLod;
    uint32_t unnormalizedCoordinates;
} db_vk_sampler_summary;

typedef struct db_vk_render_scope_state {
    DB_VkCommandBuffer commandBuffer;
    bool sampledTarget;
    uint32_t targetCount;
    db_vk_image_summary firstTarget;
    uint64_t firstTargetRenderCall;
    bool atlasBuildAuditInteresting;
    uint64_t atlasBuildAuditScope;
    uint64_t atlasBuildAuditPackets;
    uint64_t atlasImageCopyPackets;
    DB_VkViewport currentViewport;
    DB_VkRect2DFlat currentScissor;
    bool currentViewportValid;
    bool currentScissorValid;
    uint32_t activeTargetWidth;
    uint32_t activeTargetHeight;
    bool activeTargetBoundsValid;
    db_vk_image_summary currentAtlasSource;
    int32_t currentAtlasDstOuterX;
    int32_t currentAtlasDstOuterY;
    uint32_t currentAtlasDstOuterWidth;
    uint32_t currentAtlasDstOuterHeight;
    uint32_t currentAtlasDstInnerX;
    uint32_t currentAtlasDstInnerY;
    uint32_t currentAtlasDstInnerWidth;
    uint32_t currentAtlasDstInnerHeight;
    float currentAtlasUPadding;
    float currentAtlasVPadding;
    int32_t currentAtlasMipMapLevel;
    bool currentAtlasPlacementValid;
    bool currentAtlasSourceValid;
    uint32_t atlasComposePacketCount;
    bool atlasComposeOverflow;
    db_vk_atlas_compose_packet atlasComposePackets[DB_VK_ATLAS_COMPOSE_MAX_PACKETS];
    struct db_vk_render_scope_state* next;
} db_vk_render_scope_state;

static pthread_mutex_t g_db_vk_render_scope_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_vk_render_scope_state* g_db_vk_render_scopes = NULL;

static db_vk_render_scope_state* db_vk_get_render_scope_locked(
        DB_VkCommandBuffer commandBuffer, bool create);


static pthread_mutex_t g_db_vk_image_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_vk_image_record* g_db_vk_images = NULL;
static db_vk_image_view_record* g_db_vk_image_views = NULL;
static pthread_mutex_t g_db_vk_sampler_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_vk_sampler_record* g_db_vk_samplers = NULL;

typedef enum {
    DB_VK_DESCRIPTOR_PAYLOAD_NONE = 0,
    DB_VK_DESCRIPTOR_PAYLOAD_IMAGE = 1,
    DB_VK_DESCRIPTOR_PAYLOAD_BUFFER = 2,
    DB_VK_DESCRIPTOR_PAYLOAD_TEXEL = 3
} db_vk_descriptor_payload_kind;

typedef struct db_vk_descriptor_entry {
    uint32_t binding;
    uint32_t arrayElement;
    DB_VkDescriptorType descriptorType;
    db_vk_descriptor_payload_kind payloadKind;
    DB_VkDescriptorImageInfo imageInfo;
    DB_VkDescriptorBufferInfo bufferInfo;
    DB_VkBufferView texelBufferView;
    struct db_vk_descriptor_entry* next;
} db_vk_descriptor_entry;

typedef struct db_vk_push_state {
    DB_VkCommandBuffer commandBuffer;
    DB_VkPipelineBindPoint pipelineBindPoint;
    DB_VkPipelineLayout layout;
    uint32_t set;
    db_vk_descriptor_entry* entries;
    struct db_vk_push_state* next;
} db_vk_push_state;

static pthread_mutex_t g_db_vk_push_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_vk_push_state* g_db_vk_push_states = NULL;

static pthread_mutex_t g_db_vk_pipeline_state_mutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct db_vk_shader_module_record {
    DB_VkShaderModule module;
    uint64_t hash;
    size_t codeSize;
    struct db_vk_shader_module_record* next;
} db_vk_shader_module_record;

typedef struct db_vk_pipeline_record {
    DB_VkPipeline pipeline;
    DB_VkPipelineLayout layout;
    DB_VkShaderModule vertexModule;
    DB_VkShaderModule fragmentModule;
    uint64_t vertexHash;
    uint64_t fragmentHash;
    uint32_t vertexBindingCount;
    uint32_t vertexAttributeCount;
    uint32_t vertexStride0;
    int32_t topology;
    uint32_t primitiveRestartEnable;
    uint32_t rasterizerDiscardEnable;
    int32_t polygonMode;
    uint32_t cullMode;
    int32_t frontFace;
    uint32_t depthBiasEnable;
    uint32_t rasterizationSamples;
    uint32_t sampleShadingEnable;
    uint32_t depthTestEnable;
    uint32_t depthWriteEnable;
    int32_t depthCompareOp;
    uint32_t stencilTestEnable;
    uint32_t blendAttachmentCount;
    uint32_t blendEnable0;
    int32_t srcColorBlendFactor0;
    int32_t dstColorBlendFactor0;
    int32_t colorBlendOp0;
    int32_t srcAlphaBlendFactor0;
    int32_t dstAlphaBlendFactor0;
    int32_t alphaBlendOp0;
    uint32_t colorWriteMask0;
    uint32_t dynamicStateCount;
    int32_t dynamicStates[16];
    uint64_t renderPass;
    uint32_t subpass;
    uint32_t dynamicRenderingColorCount;
    int32_t dynamicRenderingColor0;
    int32_t dynamicRenderingDepth;
    int32_t dynamicRenderingStencil;
    struct db_vk_pipeline_record* next;
} db_vk_pipeline_record;

typedef struct db_vk_command_pipeline_state {
    DB_VkCommandBuffer commandBuffer;
    DB_VkPipelineBindPoint bindPoint;
    DB_VkPipeline pipeline;
    struct db_vk_command_pipeline_state* next;
} db_vk_command_pipeline_state;

static db_vk_shader_module_record* g_db_vk_shader_modules = NULL;
static db_vk_pipeline_record* g_db_vk_pipeline_records = NULL;
static db_vk_command_pipeline_state* g_db_vk_command_pipelines = NULL;
static uint64_t g_db_vk_shader_modules_created = 0u;
static uint64_t g_db_vk_pipeline_correlations = 0u;
static bool g_db_vk_pipeline_correlation_intercept_logged = false;

// MC 26.2 / Adreno 830 shader-pair isolation from entity-pipeline correlation.
// Broken pair observed only on banner_patterns + paintings in the captured run.
// Working pair is the ordinary item/entity path with the same descriptor packet,
// 36-byte/6-attribute vertex layout and fixed-function state.
#define DB_VK_BAD_ENTITY_VERTEX_HASH 0xa96fb6e837b0053aULL
#define DB_VK_BAD_ENTITY_FRAGMENT_HASH 0xf25b19b9bd1a1879ULL
#define DB_VK_GOOD_ITEM_VERTEX_HASH 0xbd4514b24b899506ULL
#define DB_VK_GOOD_ITEM_FRAGMENT_HASH 0x793c9c73b8dc7e7cULL

// MC 26.2 atlas stitching pipeline isolated from the build-input audit.
// The same vertex/fragment pair is used for particles, paintings, shield patterns,
// banner patterns and items. Keep Mojang's vertex stage/140-byte placement UBO
// untouched and replace only the fragment sampling operation on Qualcomm.
#define DB_VK_ATLAS_BUILDER_VERTEX_HASH 0x26b437a4f4517240ULL
#define DB_VK_ATLAS_BUILDER_FRAGMENT_HASH 0x1feac3c2df1c5968ULL
static uint64_t g_db_vk_atlas_fragment_rewrites = 0u;
static bool g_db_vk_atlas_fragment_repair_logged = false;
static bool g_db_vk_atlas_vertex_v4_logged = false;
static uint64_t g_db_vk_atlas_v4_ubo_packets = 0u;

static uint32_t* g_db_vk_good_item_vertex_code = NULL;
static size_t g_db_vk_good_item_vertex_code_size = 0u;
static uint32_t* g_db_vk_good_item_fragment_code = NULL;
static size_t g_db_vk_good_item_fragment_code_size = 0u;
static uint64_t g_db_vk_entity_shader_rewrites = 0u;
static bool g_db_vk_entity_shader_fallback_intercept_logged = false;
static bool g_db_vk_entity_shader_disk_cache_checked = false;
static bool g_db_vk_entity_shader_disk_cache_complete_logged = false;

// Descriptor-isolation state. The texture-op experiment proved the target shader
// receives valid UVs but every read of the banner/painting binding-6 image is
// black, including textureLod() and texelFetch(). Cache the binding-6 combined
// image sampler from the known-good item shader pair, then temporarily feed that
// exact descriptor to the affected pipeline. This distinguishes bad atlas image
// contents/view state from a binding-6 resource-access failure in the pipeline.
static pthread_mutex_t g_db_vk_entity_atlas_swap_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool g_db_vk_good_item_binding6_cached = false;
static DB_VkDescriptorImageInfo g_db_vk_good_item_binding6;
static uint64_t g_db_vk_entity_atlas_swaps = 0u;
static bool g_db_vk_entity_atlas_cache_logged = false;

#define DB_VK_ENTITY_SHADER_CACHE_VERTEX_FILE "droidbridge-mc26.2-qcom-good-entity-vertex.spv"
#define DB_VK_ENTITY_SHADER_CACHE_FRAGMENT_FILE "droidbridge-mc26.2-qcom-good-entity-fragment.spv"

static uint64_t db_vk_fnv1a64(const void* data, size_t size) {
    const unsigned char* bytes = (const unsigned char*)data;
    uint64_t hash = 1469598103934665603ULL;
    if (bytes == NULL) return hash;
    for (size_t i = 0u; i < size; ++i) {
        hash ^= (uint64_t)bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

static bool db_vk_entity_shader_cache_path(
        const char* fileName, char* out, size_t outSize) {
    if (fileName == NULL || out == NULL || outSize == 0u) return false;
    const char* root = getenv("TMPDIR");
    if (root == NULL || root[0] == '\0') root = getenv("DROIDBRIDGE_VK_SHADER_CACHE_DIR");
    if (root == NULL || root[0] == '\0') return false;
    int written = snprintf(out, outSize, "%s/%s", root, fileName);
    return written > 0 && (size_t)written < outSize;
}

static bool db_vk_load_entity_shader_cache_file(
        const char* fileName,
        uint64_t expectedHash,
        uint32_t** codeOut,
        size_t* sizeOut,
        const char* label) {
    if (codeOut == NULL || sizeOut == NULL || *codeOut != NULL) return false;
    char path[1024];
    if (!db_vk_entity_shader_cache_path(fileName, path, sizeof(path))) return false;

    FILE* file = fopen(path, "rb");
    if (file == NULL) return false;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return false;
    }
    long length = ftell(file);
    if (length <= 0 || (length & 3L) != 0 || length > (16L * 1024L * 1024L)) {
        fclose(file);
        return false;
    }
    if (fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        return false;
    }

    uint32_t* code = (uint32_t*)malloc((size_t)length);
    if (code == NULL) {
        fclose(file);
        return false;
    }
    size_t got = fread(code, 1u, (size_t)length, file);
    fclose(file);
    if (got != (size_t)length || db_vk_fnv1a64(code, got) != expectedHash) {
        free(code);
        fprintf(stderr,
                "DroidBridgeVulkanEntityShaderBaseline: ignored invalid persisted %s SPIR-V cache path=%s size=%zu expectedHash=%016llx\n",
                label != NULL ? label : "shader",
                path,
                got,
                (unsigned long long)expectedHash);
        fflush(stderr);
        return false;
    }

    *codeOut = code;
    *sizeOut = got;
    fprintf(stderr,
            "DroidBridgeVulkanEntityShaderBaseline: preloaded persisted working %s SPIR-V hash=%016llx codeSize=%zu path=%s\n",
            label != NULL ? label : "shader",
            (unsigned long long)expectedHash,
            got,
            path);
    fflush(stderr);
    return true;
}

static void db_vk_load_persisted_entity_shader_cache_once(void) {
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    if (g_db_vk_entity_shader_disk_cache_checked) {
        pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
        return;
    }
    g_db_vk_entity_shader_disk_cache_checked = true;
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);

    db_vk_load_entity_shader_cache_file(
            DB_VK_ENTITY_SHADER_CACHE_VERTEX_FILE,
            DB_VK_GOOD_ITEM_VERTEX_HASH,
            &g_db_vk_good_item_vertex_code,
            &g_db_vk_good_item_vertex_code_size,
            "vertex");
    db_vk_load_entity_shader_cache_file(
            DB_VK_ENTITY_SHADER_CACHE_FRAGMENT_FILE,
            DB_VK_GOOD_ITEM_FRAGMENT_HASH,
            &g_db_vk_good_item_fragment_code,
            &g_db_vk_good_item_fragment_code_size,
            "fragment");

    if (g_db_vk_good_item_vertex_code != NULL
            && g_db_vk_good_item_fragment_code != NULL) {
        fprintf(stderr,
                "DroidBridgeVulkanEntityShaderBaseline: persisted working item reference pair available; recompile mode does not substitute it\n");
        fflush(stderr);
        g_db_vk_entity_shader_disk_cache_complete_logged = true;
    }
}

static void db_vk_persist_entity_shader_cache_file(
        const char* fileName,
        uint64_t expectedHash,
        const DB_VkShaderModuleCreateInfo* createInfo,
        const char* label) {
    if (createInfo == NULL || createInfo->pCode == NULL || createInfo->codeSize == 0u) return;
    if (db_vk_fnv1a64(createInfo->pCode, createInfo->codeSize) != expectedHash) return;

    char path[1024];
    char tempPath[1088];
    if (!db_vk_entity_shader_cache_path(fileName, path, sizeof(path))) return;
    int written = snprintf(tempPath, sizeof(tempPath), "%s.tmp", path);
    if (written <= 0 || (size_t)written >= sizeof(tempPath)) return;

    FILE* existing = fopen(path, "rb");
    if (existing != NULL) {
        fclose(existing);
        return;
    }

    FILE* file = fopen(tempPath, "wb");
    if (file == NULL) return;
    size_t saved = fwrite(createInfo->pCode, 1u, createInfo->codeSize, file);
    bool ok = saved == createInfo->codeSize && fflush(file) == 0;
    fclose(file);
    if (!ok || rename(tempPath, path) != 0) {
        remove(tempPath);
        return;
    }

    fprintf(stderr,
            "DroidBridgeVulkanEntityShaderBaseline: persisted working %s SPIR-V hash=%016llx codeSize=%zu path=%s\n",
            label != NULL ? label : "shader",
            (unsigned long long)expectedHash,
            createInfo->codeSize,
            path);
    fflush(stderr);
}

static void db_vk_cache_known_good_entity_shader(
        uint64_t hash, const DB_VkShaderModuleCreateInfo* createInfo) {
    if (createInfo == NULL || createInfo->pCode == NULL || createInfo->codeSize == 0u) return;

    uint32_t** dstCode = NULL;
    size_t* dstSize = NULL;
    const char* label = NULL;
    if (hash == DB_VK_GOOD_ITEM_VERTEX_HASH) {
        dstCode = &g_db_vk_good_item_vertex_code;
        dstSize = &g_db_vk_good_item_vertex_code_size;
        label = "vertex";
    } else if (hash == DB_VK_GOOD_ITEM_FRAGMENT_HASH) {
        dstCode = &g_db_vk_good_item_fragment_code;
        dstSize = &g_db_vk_good_item_fragment_code_size;
        label = "fragment";
    } else {
        return;
    }

    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    if (*dstCode == NULL) {
        uint32_t* copy = (uint32_t*)malloc(createInfo->codeSize);
        if (copy != NULL) {
            memcpy(copy, createInfo->pCode, createInfo->codeSize);
            *dstCode = copy;
            *dstSize = createInfo->codeSize;
            fprintf(stderr,
                    "DroidBridgeVulkanEntityShaderBaseline: cached working %s SPIR-V hash=%016llx codeSize=%zu\n",
                    label,
                    (unsigned long long)hash,
                    createInfo->codeSize);
            fflush(stderr);
        }
    }
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);

    if (hash == DB_VK_GOOD_ITEM_VERTEX_HASH) {
        db_vk_persist_entity_shader_cache_file(
                DB_VK_ENTITY_SHADER_CACHE_VERTEX_FILE, hash, createInfo, "vertex");
    } else if (hash == DB_VK_GOOD_ITEM_FRAGMENT_HASH) {
        db_vk_persist_entity_shader_cache_file(
                DB_VK_ENTITY_SHADER_CACHE_FRAGMENT_FILE, hash, createInfo, "fragment");
    }

    if (!g_db_vk_entity_shader_disk_cache_complete_logged
            && g_db_vk_good_item_vertex_code != NULL
            && g_db_vk_good_item_fragment_code != NULL) {
        g_db_vk_entity_shader_disk_cache_complete_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanEntityShaderBaseline: working item reference pair cached for diagnostics; fragment recompile mode preserves painting/banner shader semantics\n");
        fflush(stderr);
    }
}

// Dynamically bind the SPIRV-Cross C ABI and shaderc C ABI already loaded by LWJGL.
// This keeps the launcher native bridge independent of their build-time headers while
// using their stable C interfaces at runtime.
typedef void* DB_spvc_context;
typedef void* DB_spvc_parsed_ir;
typedef void* DB_spvc_compiler;
typedef void* DB_spvc_compiler_options;
typedef int DB_spvc_result;

typedef DB_spvc_result (*DB_spvc_context_create_fn)(DB_spvc_context*);
typedef void (*DB_spvc_context_destroy_fn)(DB_spvc_context);
typedef const char* (*DB_spvc_context_get_last_error_string_fn)(DB_spvc_context);
typedef DB_spvc_result (*DB_spvc_context_parse_spirv_fn)(
        DB_spvc_context, const uint32_t*, size_t, DB_spvc_parsed_ir*);
typedef DB_spvc_result (*DB_spvc_context_create_compiler_fn)(
        DB_spvc_context, int, DB_spvc_parsed_ir, int, DB_spvc_compiler*);
typedef DB_spvc_result (*DB_spvc_compiler_create_compiler_options_fn)(
        DB_spvc_compiler, DB_spvc_compiler_options*);
typedef DB_spvc_result (*DB_spvc_compiler_options_set_bool_fn)(
        DB_spvc_compiler_options, int, unsigned char);
typedef DB_spvc_result (*DB_spvc_compiler_options_set_uint_fn)(
        DB_spvc_compiler_options, int, unsigned int);
typedef DB_spvc_result (*DB_spvc_compiler_install_compiler_options_fn)(
        DB_spvc_compiler, DB_spvc_compiler_options);
typedef DB_spvc_result (*DB_spvc_compiler_compile_fn)(DB_spvc_compiler, const char**);

typedef void* DB_shaderc_compiler;
typedef void* DB_shaderc_result;
typedef DB_shaderc_compiler (*DB_shaderc_compiler_initialize_fn)(void);
typedef void (*DB_shaderc_compiler_release_fn)(DB_shaderc_compiler);
typedef DB_shaderc_result (*DB_shaderc_compile_into_spv_fn)(
        DB_shaderc_compiler, const char*, size_t, int, const char*, const char*, const void*);
typedef int (*DB_shaderc_result_get_compilation_status_fn)(DB_shaderc_result);
typedef size_t (*DB_shaderc_result_get_length_fn)(DB_shaderc_result);
typedef const char* (*DB_shaderc_result_get_bytes_fn)(DB_shaderc_result);
typedef const char* (*DB_shaderc_result_get_error_message_fn)(DB_shaderc_result);
typedef void (*DB_shaderc_result_release_fn)(DB_shaderc_result);

#define DB_SPVC_SUCCESS 0
#define DB_SPVC_BACKEND_GLSL 1
#define DB_SPVC_CAPTURE_MODE_TAKE_OWNERSHIP 1
#define DB_SPVC_COMPILER_OPTION_GLSL_BIT 0x02000000
#define DB_SPVC_COMPILER_OPTION_GLSL_SEPARATE_SHADER_OBJECTS (6 | DB_SPVC_COMPILER_OPTION_GLSL_BIT)
#define DB_SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION (7 | DB_SPVC_COMPILER_OPTION_GLSL_BIT)
#define DB_SPVC_COMPILER_OPTION_GLSL_VERSION (8 | DB_SPVC_COMPILER_OPTION_GLSL_BIT)
#define DB_SPVC_COMPILER_OPTION_GLSL_ES (9 | DB_SPVC_COMPILER_OPTION_GLSL_BIT)
#define DB_SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS (10 | DB_SPVC_COMPILER_OPTION_GLSL_BIT)
#define DB_SHADERC_GLSL_FRAGMENT_SHADER 1
#define DB_SHADERC_COMPILATION_STATUS_SUCCESS 0

static bool db_vk_find_glsl_identifier_before_semicolon(
        const char* lineStart, const char* lineEnd, char* out, size_t outSize) {
    if (lineStart == NULL || lineEnd == NULL || out == NULL || outSize < 2u || lineEnd <= lineStart) {
        return false;
    }
    const char* semi = lineStart;
    while (semi < lineEnd && *semi != ';') semi++;
    if (semi >= lineEnd || *semi != ';') return false;
    const char* end = semi - 1;
    while (end >= lineStart && (*end == ' ' || *end == '\t' || *end == '\r')) end--;
    if (end < lineStart) return false;
    const char* begin = end;
    while (begin > lineStart) {
        char c = begin[-1];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_')) break;
        begin--;
    }
    size_t len = (size_t)(end - begin + 1);
    if (len == 0u || len + 1u > outSize) return false;
    memcpy(out, begin, len);
    out[len] = '\0';
    return true;
}

static bool db_vk_find_glsl_decl_identifier(
        const char* glsl, const char* requiredA, const char* requiredB,
        const char* requiredC, char* out, size_t outSize) {
    if (glsl == NULL || out == NULL) return false;
    const char* line = glsl;
    while (*line != '\0') {
        const char* lineEnd = strchr(line, '\n');
        if (lineEnd == NULL) lineEnd = line + strlen(line);
        size_t len = (size_t)(lineEnd - line);
        bool hasA = requiredA == NULL || (strstr(line, requiredA) != NULL
                && strstr(line, requiredA) < lineEnd);
        bool hasB = requiredB == NULL || (strstr(line, requiredB) != NULL
                && strstr(line, requiredB) < lineEnd);
        bool hasC = requiredC == NULL || (strstr(line, requiredC) != NULL
                && strstr(line, requiredC) < lineEnd);
        if (hasA && hasB && hasC && len > 0u
                && db_vk_find_glsl_identifier_before_semicolon(line, lineEnd, out, outSize)) {
            return true;
        }
        if (*lineEnd == '\0') break;
        line = lineEnd + 1;
    }
    return false;
}

static bool db_vk_base_texture_bad_entity_fragment(
        const DB_VkShaderModuleCreateInfo* original,
        DB_VkShaderModuleCreateInfo* replacement,
        uint64_t* replacementHashOut,
        uint32_t** ownedCodeOut) {
    if (original == NULL || original->pCode == NULL || original->codeSize == 0u
            || replacement == NULL || replacementHashOut == NULL || ownedCodeOut == NULL) {
        return false;
    }

    DB_spvc_context_create_fn spvc_context_create =
            (DB_spvc_context_create_fn)dlsym(RTLD_DEFAULT, "spvc_context_create");
    DB_spvc_context_destroy_fn spvc_context_destroy =
            (DB_spvc_context_destroy_fn)dlsym(RTLD_DEFAULT, "spvc_context_destroy");
    DB_spvc_context_get_last_error_string_fn spvc_context_get_last_error_string =
            (DB_spvc_context_get_last_error_string_fn)dlsym(RTLD_DEFAULT, "spvc_context_get_last_error_string");
    DB_spvc_context_parse_spirv_fn spvc_context_parse_spirv =
            (DB_spvc_context_parse_spirv_fn)dlsym(RTLD_DEFAULT, "spvc_context_parse_spirv");
    DB_spvc_context_create_compiler_fn spvc_context_create_compiler =
            (DB_spvc_context_create_compiler_fn)dlsym(RTLD_DEFAULT, "spvc_context_create_compiler");
    DB_spvc_compiler_create_compiler_options_fn spvc_compiler_create_compiler_options =
            (DB_spvc_compiler_create_compiler_options_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_create_compiler_options");
    DB_spvc_compiler_options_set_bool_fn spvc_compiler_options_set_bool =
            (DB_spvc_compiler_options_set_bool_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_options_set_bool");
    DB_spvc_compiler_options_set_uint_fn spvc_compiler_options_set_uint =
            (DB_spvc_compiler_options_set_uint_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_options_set_uint");
    DB_spvc_compiler_install_compiler_options_fn spvc_compiler_install_compiler_options =
            (DB_spvc_compiler_install_compiler_options_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_install_compiler_options");
    DB_spvc_compiler_compile_fn spvc_compiler_compile =
            (DB_spvc_compiler_compile_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_compile");

    DB_shaderc_compiler_initialize_fn shaderc_compiler_initialize =
            (DB_shaderc_compiler_initialize_fn)dlsym(RTLD_DEFAULT, "shaderc_compiler_initialize");
    DB_shaderc_compiler_release_fn shaderc_compiler_release =
            (DB_shaderc_compiler_release_fn)dlsym(RTLD_DEFAULT, "shaderc_compiler_release");
    DB_shaderc_compile_into_spv_fn shaderc_compile_into_spv =
            (DB_shaderc_compile_into_spv_fn)dlsym(RTLD_DEFAULT, "shaderc_compile_into_spv");
    DB_shaderc_result_get_compilation_status_fn shaderc_result_get_compilation_status =
            (DB_shaderc_result_get_compilation_status_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_compilation_status");
    DB_shaderc_result_get_length_fn shaderc_result_get_length =
            (DB_shaderc_result_get_length_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_length");
    DB_shaderc_result_get_bytes_fn shaderc_result_get_bytes =
            (DB_shaderc_result_get_bytes_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_bytes");
    DB_shaderc_result_get_error_message_fn shaderc_result_get_error_message =
            (DB_shaderc_result_get_error_message_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_error_message");
    DB_shaderc_result_release_fn shaderc_result_release =
            (DB_shaderc_result_release_fn)dlsym(RTLD_DEFAULT, "shaderc_result_release");

    if (spvc_context_create == NULL || spvc_context_destroy == NULL
            || spvc_context_parse_spirv == NULL || spvc_context_create_compiler == NULL
            || spvc_compiler_create_compiler_options == NULL
            || spvc_compiler_options_set_bool == NULL || spvc_compiler_options_set_uint == NULL
            || spvc_compiler_install_compiler_options == NULL || spvc_compiler_compile == NULL
            || shaderc_compiler_initialize == NULL || shaderc_compiler_release == NULL
            || shaderc_compile_into_spv == NULL || shaderc_result_get_compilation_status == NULL
            || shaderc_result_get_length == NULL || shaderc_result_get_bytes == NULL
            || shaderc_result_release == NULL) {
        fprintf(stderr,
                "DroidBridgeVulkanEntityBaseTexture: SPIRV-Cross/shaderc C ABI unavailable; keeping original fragment shader\n");
        fflush(stderr);
        return false;
    }

    DB_spvc_context context = NULL;
    DB_spvc_parsed_ir parsed = NULL;
    DB_spvc_compiler compiler = NULL;
    DB_spvc_compiler_options options = NULL;
    const char* glsl = NULL;
    DB_spvc_result spvcResult = spvc_context_create(&context);
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_context_parse_spirv(
                context, original->pCode, original->codeSize / sizeof(uint32_t), &parsed);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_context_create_compiler(
                context, DB_SPVC_BACKEND_GLSL, parsed,
                DB_SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &compiler);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_create_compiler_options(compiler, &options);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_uint(
                options, DB_SPVC_COMPILER_OPTION_GLSL_VERSION, 450u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_ES, 0u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, 1u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_SEPARATE_SHADER_OBJECTS, 1u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, 1u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_install_compiler_options(compiler, options);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_compile(compiler, &glsl);
    }
    if (spvcResult != DB_SPVC_SUCCESS || glsl == NULL || glsl[0] == '\0') {
        const char* error = (context != NULL && spvc_context_get_last_error_string != NULL)
                ? spvc_context_get_last_error_string(context) : NULL;
        fprintf(stderr,
                "DroidBridgeVulkanEntityBaseTexture: SPIRV-Cross failed result=%d error=%s; keeping original fragment shader\n",
                spvcResult, error != NULL ? error : "<unavailable>");
        fflush(stderr);
        if (context != NULL) spvc_context_destroy(context);
        return false;
    }

    const char* mainStart = strstr(glsl, "void main()");
    char samplerName[128] = {0};
    char outputName[128] = {0};
    char uvName[128] = {0};
    bool samplerFound = db_vk_find_glsl_decl_identifier(
            glsl, "binding = 6", "uniform", "sampler", samplerName, sizeof(samplerName));
    if (!samplerFound) {
        samplerFound = db_vk_find_glsl_decl_identifier(
                glsl, "binding=6", "uniform", "sampler", samplerName, sizeof(samplerName));
    }
    bool outputFound = db_vk_find_glsl_decl_identifier(
            glsl, "location = 0", " out ", "vec4", outputName, sizeof(outputName));
    if (!outputFound) {
        outputFound = db_vk_find_glsl_decl_identifier(
                glsl, "location=0", " out ", "vec4", outputName, sizeof(outputName));
    }
    bool uvFound = db_vk_find_glsl_decl_identifier(
            glsl, "location = 1", " in ", "vec2", uvName, sizeof(uvName));
    if (!uvFound) {
        uvFound = db_vk_find_glsl_decl_identifier(
                glsl, "location=1", " in ", "vec2", uvName, sizeof(uvName));
    }
    if (!uvFound) {
        uvFound = db_vk_find_glsl_decl_identifier(
                glsl, NULL, " in ", "vec2", uvName, sizeof(uvName));
    }

    if (mainStart == NULL || !samplerFound || !outputFound || !uvFound) {
        fprintf(stderr,
                "DroidBridgeVulkanEntityBaseTexture: GLSL interface discovery failed main=%d sampler6=%d output0=%d uv=%d; keeping original fragment shader\n",
                mainStart != NULL ? 1 : 0, samplerFound ? 1 : 0,
                outputFound ? 1 : 0, uvFound ? 1 : 0);
        fflush(stderr);
        spvc_context_destroy(context);
        return false;
    }

    size_t prefixLen = (size_t)(mainStart - glsl);
    const char* bodyFormat =
            "void main() {\n"
            "    vec2 dbUv = %s;\n"
            "    vec2 dbClampedUv = clamp(dbUv, vec2(0.0), vec2(0.999999));\n"
            "    float dbBand = mod(floor(gl_FragCoord.x / 10.0), 4.0);\n"
            "    if (dbBand < 1.0) {\n"
            "        %s = vec4(0.25 + 0.75 * dbClampedUv.x, 0.25 + 0.75 * dbClampedUv.y, 0.5, 1.0);\n"
            "    } else if (dbBand < 2.0) {\n"
            "        vec4 dbImplicit = texture(%s, dbClampedUv);\n"
            "        %s = vec4(dbImplicit.rgb, 1.0);\n"
            "    } else if (dbBand < 3.0) {\n"
            "        vec4 dbLod0 = textureLod(%s, dbClampedUv, 0.0);\n"
            "        %s = vec4(dbLod0.rgb, 1.0);\n"
            "    } else {\n"
            "        ivec2 dbSize = textureSize(%s, 0);\n"
            "        ivec2 dbMax = max(dbSize - ivec2(1), ivec2(0));\n"
            "        ivec2 dbTexel = clamp(ivec2(dbClampedUv * vec2(dbSize)), ivec2(0), dbMax);\n"
            "        vec4 dbFetch = texelFetch(%s, dbTexel, 0);\n"
            "        %s = vec4(dbFetch.rgb, 1.0);\n"
            "    }\n"
            "}\n";
    int bodyLenInt = snprintf(NULL, 0, bodyFormat,
            uvName, outputName, samplerName, outputName,
            samplerName, outputName, samplerName, samplerName, outputName);
    if (bodyLenInt <= 0) {
        spvc_context_destroy(context);
        return false;
    }
    size_t bodyLen = (size_t)bodyLenInt;
    if (prefixLen > (4u * 1024u * 1024u) || bodyLen > 4096u) {
        spvc_context_destroy(context);
        return false;
    }
    char* isolatedGlsl = (char*)malloc(prefixLen + bodyLen + 1u);
    if (isolatedGlsl == NULL) {
        spvc_context_destroy(context);
        return false;
    }
    memcpy(isolatedGlsl, glsl, prefixLen);
    snprintf(isolatedGlsl + prefixLen, bodyLen + 1u, bodyFormat,
            uvName, outputName, samplerName, outputName,
            samplerName, outputName, samplerName, samplerName, outputName);

    fprintf(stderr,
            "DroidBridgeVulkanEntityTextureOps: interface sampler=%s uv=%s output=%s sourceBytes=%zu semantics=band0-uv band1-texture band2-textureLod0 band3-texelFetch\n",
            samplerName, uvName, outputName, prefixLen + bodyLen);
    fflush(stderr);

    DB_shaderc_compiler shaderc = shaderc_compiler_initialize();
    if (shaderc == NULL) {
        free(isolatedGlsl);
        spvc_context_destroy(context);
        return false;
    }
    DB_shaderc_result result = shaderc_compile_into_spv(
            shaderc, isolatedGlsl, prefixLen + bodyLen, DB_SHADERC_GLSL_FRAGMENT_SHADER,
            "droidbridge_entity_fragment_texture_op_isolation.frag", "main", NULL);
    if (result == NULL || shaderc_result_get_compilation_status(result)
            != DB_SHADERC_COMPILATION_STATUS_SUCCESS) {
        const char* error = (result != NULL && shaderc_result_get_error_message != NULL)
                ? shaderc_result_get_error_message(result) : NULL;
        fprintf(stderr,
                "DroidBridgeVulkanEntityBaseTexture: shaderc failed sourceBytes=%zu error=%s; keeping original fragment shader\n",
                prefixLen + bodyLen, error != NULL ? error : "<unavailable>");
        fflush(stderr);
        if (result != NULL) shaderc_result_release(result);
        shaderc_compiler_release(shaderc);
        free(isolatedGlsl);
        spvc_context_destroy(context);
        return false;
    }

    size_t outputSize = shaderc_result_get_length(result);
    const char* outputBytes = shaderc_result_get_bytes(result);
    if (outputBytes == NULL || outputSize == 0u || (outputSize & 3u) != 0u
            || outputSize > (16u * 1024u * 1024u)) {
        fprintf(stderr,
                "DroidBridgeVulkanEntityBaseTexture: shaderc returned invalid SPIR-V size=%zu; keeping original fragment shader\n",
                outputSize);
        fflush(stderr);
        shaderc_result_release(result);
        shaderc_compiler_release(shaderc);
        free(isolatedGlsl);
        spvc_context_destroy(context);
        return false;
    }

    uint32_t* output = (uint32_t*)malloc(outputSize);
    if (output == NULL) {
        shaderc_result_release(result);
        shaderc_compiler_release(shaderc);
        free(isolatedGlsl);
        spvc_context_destroy(context);
        return false;
    }
    memcpy(output, outputBytes, outputSize);
    uint64_t outputHash = db_vk_fnv1a64(output, outputSize);

    *replacement = *original;
    replacement->pCode = output;
    replacement->codeSize = outputSize;
    *replacementHashOut = outputHash;
    *ownedCodeOut = output;

    uint64_t rewrite = ++g_db_vk_entity_shader_rewrites;
    fprintf(stderr,
            "DroidBridgeVulkanEntityTextureOps: split #%llu stage=fragment originalHash=%016llx originalSize=%zu diagnosticHash=%016llx diagnosticSize=%zu sampler=%s uv=%s semantics=uv/texture/textureLod0/texelFetch\n",
            (unsigned long long)rewrite,
            (unsigned long long)DB_VK_BAD_ENTITY_FRAGMENT_HASH,
            original->codeSize,
            (unsigned long long)outputHash,
            outputSize,
            samplerName,
            uvName);
    fflush(stderr);

    shaderc_result_release(result);
    shaderc_compiler_release(shaderc);
    free(isolatedGlsl);
    spvc_context_destroy(context);
    return true;
}


static void db_vk_log_atlas_builder_vertex_v4(const DB_VkShaderModuleCreateInfo* original) {
    if (g_db_vk_atlas_vertex_v4_logged || original == NULL || original->pCode == NULL
            || original->codeSize == 0u) return;

    DB_spvc_context_create_fn spvc_context_create =
            (DB_spvc_context_create_fn)dlsym(RTLD_DEFAULT, "spvc_context_create");
    DB_spvc_context_destroy_fn spvc_context_destroy =
            (DB_spvc_context_destroy_fn)dlsym(RTLD_DEFAULT, "spvc_context_destroy");
    DB_spvc_context_get_last_error_string_fn spvc_context_get_last_error_string =
            (DB_spvc_context_get_last_error_string_fn)dlsym(RTLD_DEFAULT, "spvc_context_get_last_error_string");
    DB_spvc_context_parse_spirv_fn spvc_context_parse_spirv =
            (DB_spvc_context_parse_spirv_fn)dlsym(RTLD_DEFAULT, "spvc_context_parse_spirv");
    DB_spvc_context_create_compiler_fn spvc_context_create_compiler =
            (DB_spvc_context_create_compiler_fn)dlsym(RTLD_DEFAULT, "spvc_context_create_compiler");
    DB_spvc_compiler_create_compiler_options_fn spvc_compiler_create_compiler_options =
            (DB_spvc_compiler_create_compiler_options_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_create_compiler_options");
    DB_spvc_compiler_options_set_bool_fn spvc_compiler_options_set_bool =
            (DB_spvc_compiler_options_set_bool_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_options_set_bool");
    DB_spvc_compiler_options_set_uint_fn spvc_compiler_options_set_uint =
            (DB_spvc_compiler_options_set_uint_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_options_set_uint");
    DB_spvc_compiler_install_compiler_options_fn spvc_compiler_install_compiler_options =
            (DB_spvc_compiler_install_compiler_options_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_install_compiler_options");
    DB_spvc_compiler_compile_fn spvc_compiler_compile =
            (DB_spvc_compiler_compile_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_compile");

    if (spvc_context_create == NULL || spvc_context_destroy == NULL
            || spvc_context_parse_spirv == NULL || spvc_context_create_compiler == NULL
            || spvc_compiler_create_compiler_options == NULL
            || spvc_compiler_options_set_bool == NULL || spvc_compiler_options_set_uint == NULL
            || spvc_compiler_install_compiler_options == NULL || spvc_compiler_compile == NULL) {
        fprintf(stderr, "DroidBridgeVulkanAtlasVertexV4: SPIRV-Cross unavailable; vertex GLSL dump skipped\n");
        fflush(stderr);
        return;
    }

    DB_spvc_context context = NULL;
    DB_spvc_parsed_ir parsed = NULL;
    DB_spvc_compiler compiler = NULL;
    DB_spvc_compiler_options options = NULL;
    const char* glsl = NULL;
    DB_spvc_result result = spvc_context_create(&context);
    if (result == DB_SPVC_SUCCESS) {
        result = spvc_context_parse_spirv(
                context, original->pCode, original->codeSize / sizeof(uint32_t), &parsed);
    }
    if (result == DB_SPVC_SUCCESS) {
        result = spvc_context_create_compiler(
                context, DB_SPVC_BACKEND_GLSL, parsed,
                DB_SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &compiler);
    }
    if (result == DB_SPVC_SUCCESS) result = spvc_compiler_create_compiler_options(compiler, &options);
    if (result == DB_SPVC_SUCCESS) {
        result = spvc_compiler_options_set_uint(options, DB_SPVC_COMPILER_OPTION_GLSL_VERSION, 450u);
    }
    if (result == DB_SPVC_SUCCESS) {
        result = spvc_compiler_options_set_bool(options, DB_SPVC_COMPILER_OPTION_GLSL_ES, 0u);
    }
    if (result == DB_SPVC_SUCCESS) {
        result = spvc_compiler_options_set_bool(options, DB_SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, 1u);
    }
    if (result == DB_SPVC_SUCCESS) {
        result = spvc_compiler_options_set_bool(options, DB_SPVC_COMPILER_OPTION_GLSL_SEPARATE_SHADER_OBJECTS, 1u);
    }
    if (result == DB_SPVC_SUCCESS) result = spvc_compiler_install_compiler_options(compiler, options);
    if (result == DB_SPVC_SUCCESS) result = spvc_compiler_compile(compiler, &glsl);

    if (result != DB_SPVC_SUCCESS || glsl == NULL || glsl[0] == '\0') {
        const char* error = context != NULL && spvc_context_get_last_error_string != NULL
                ? spvc_context_get_last_error_string(context) : NULL;
        fprintf(stderr, "DroidBridgeVulkanAtlasVertexV4: decompile failed result=%d error=%s\n",
                result, error != NULL ? error : "<unavailable>");
        fflush(stderr);
        if (context != NULL) spvc_context_destroy(context);
        return;
    }

    g_db_vk_atlas_vertex_v4_logged = true;
    fprintf(stderr,
            "DroidBridgeVulkanAtlasVertexV4: GLSL-BEGIN hash=%016llx codeSize=%zu\n",
            (unsigned long long)DB_VK_ATLAS_BUILDER_VERTEX_HASH, original->codeSize);
    const char* line = glsl;
    size_t emitted = 0u;
    while (*line != '\0' && emitted < 32768u) {
        const char* end = strchr(line, '\n');
        size_t len = end != NULL ? (size_t)(end - line) : strlen(line);
        if (len > 1024u) len = 1024u;
        fprintf(stderr, "DroidBridgeVulkanAtlasVertexV4: GLSL %.*s\n", (int)len, line);
        emitted += len + 1u;
        if (end == NULL) break;
        line = end + 1;
    }
    fprintf(stderr, "DroidBridgeVulkanAtlasVertexV4: GLSL-END bytes=%zu\n", emitted);
    fflush(stderr);
    spvc_context_destroy(context);
}

static bool db_vk_rewrite_atlas_builder_fragment(
        const DB_VkShaderModuleCreateInfo* original,
        DB_VkShaderModuleCreateInfo* replacement,
        uint64_t* replacementHashOut,
        uint32_t** ownedCodeOut) {
    if (original == NULL || original->pCode == NULL || original->codeSize == 0u
            || replacement == NULL || replacementHashOut == NULL || ownedCodeOut == NULL) {
        return false;
    }

    DB_spvc_context_create_fn spvc_context_create =
            (DB_spvc_context_create_fn)dlsym(RTLD_DEFAULT, "spvc_context_create");
    DB_spvc_context_destroy_fn spvc_context_destroy =
            (DB_spvc_context_destroy_fn)dlsym(RTLD_DEFAULT, "spvc_context_destroy");
    DB_spvc_context_get_last_error_string_fn spvc_context_get_last_error_string =
            (DB_spvc_context_get_last_error_string_fn)dlsym(RTLD_DEFAULT, "spvc_context_get_last_error_string");
    DB_spvc_context_parse_spirv_fn spvc_context_parse_spirv =
            (DB_spvc_context_parse_spirv_fn)dlsym(RTLD_DEFAULT, "spvc_context_parse_spirv");
    DB_spvc_context_create_compiler_fn spvc_context_create_compiler =
            (DB_spvc_context_create_compiler_fn)dlsym(RTLD_DEFAULT, "spvc_context_create_compiler");
    DB_spvc_compiler_create_compiler_options_fn spvc_compiler_create_compiler_options =
            (DB_spvc_compiler_create_compiler_options_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_create_compiler_options");
    DB_spvc_compiler_options_set_bool_fn spvc_compiler_options_set_bool =
            (DB_spvc_compiler_options_set_bool_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_options_set_bool");
    DB_spvc_compiler_options_set_uint_fn spvc_compiler_options_set_uint =
            (DB_spvc_compiler_options_set_uint_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_options_set_uint");
    DB_spvc_compiler_install_compiler_options_fn spvc_compiler_install_compiler_options =
            (DB_spvc_compiler_install_compiler_options_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_install_compiler_options");
    DB_spvc_compiler_compile_fn spvc_compiler_compile =
            (DB_spvc_compiler_compile_fn)dlsym(RTLD_DEFAULT, "spvc_compiler_compile");

    DB_shaderc_compiler_initialize_fn shaderc_compiler_initialize =
            (DB_shaderc_compiler_initialize_fn)dlsym(RTLD_DEFAULT, "shaderc_compiler_initialize");
    DB_shaderc_compiler_release_fn shaderc_compiler_release =
            (DB_shaderc_compiler_release_fn)dlsym(RTLD_DEFAULT, "shaderc_compiler_release");
    DB_shaderc_compile_into_spv_fn shaderc_compile_into_spv =
            (DB_shaderc_compile_into_spv_fn)dlsym(RTLD_DEFAULT, "shaderc_compile_into_spv");
    DB_shaderc_result_get_compilation_status_fn shaderc_result_get_compilation_status =
            (DB_shaderc_result_get_compilation_status_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_compilation_status");
    DB_shaderc_result_get_length_fn shaderc_result_get_length =
            (DB_shaderc_result_get_length_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_length");
    DB_shaderc_result_get_bytes_fn shaderc_result_get_bytes =
            (DB_shaderc_result_get_bytes_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_bytes");
    DB_shaderc_result_get_error_message_fn shaderc_result_get_error_message =
            (DB_shaderc_result_get_error_message_fn)dlsym(RTLD_DEFAULT, "shaderc_result_get_error_message");
    DB_shaderc_result_release_fn shaderc_result_release =
            (DB_shaderc_result_release_fn)dlsym(RTLD_DEFAULT, "shaderc_result_release");

    if (spvc_context_create == NULL || spvc_context_destroy == NULL
            || spvc_context_parse_spirv == NULL || spvc_context_create_compiler == NULL
            || spvc_compiler_create_compiler_options == NULL
            || spvc_compiler_options_set_bool == NULL || spvc_compiler_options_set_uint == NULL
            || spvc_compiler_install_compiler_options == NULL || spvc_compiler_compile == NULL
            || shaderc_compiler_initialize == NULL || shaderc_compiler_release == NULL
            || shaderc_compile_into_spv == NULL || shaderc_result_get_compilation_status == NULL
            || shaderc_result_get_length == NULL || shaderc_result_get_bytes == NULL
            || shaderc_result_release == NULL) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasCoverageV4: SPIRV-Cross/shaderc ABI unavailable; keeping Mojang atlas fragment shader\n");
        fflush(stderr);
        return false;
    }

    DB_spvc_context context = NULL;
    DB_spvc_parsed_ir parsed = NULL;
    DB_spvc_compiler compiler = NULL;
    DB_spvc_compiler_options options = NULL;
    const char* glsl = NULL;
    DB_spvc_result spvcResult = spvc_context_create(&context);
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_context_parse_spirv(
                context, original->pCode, original->codeSize / sizeof(uint32_t), &parsed);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_context_create_compiler(
                context, DB_SPVC_BACKEND_GLSL, parsed,
                DB_SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &compiler);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_create_compiler_options(compiler, &options);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_uint(
                options, DB_SPVC_COMPILER_OPTION_GLSL_VERSION, 450u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_ES, 0u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, 1u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_SEPARATE_SHADER_OBJECTS, 1u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_options_set_bool(
                options, DB_SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, 1u);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_install_compiler_options(compiler, options);
    }
    if (spvcResult == DB_SPVC_SUCCESS) {
        spvcResult = spvc_compiler_compile(compiler, &glsl);
    }
    if (spvcResult != DB_SPVC_SUCCESS || glsl == NULL || glsl[0] == '\0') {
        const char* error = (context != NULL && spvc_context_get_last_error_string != NULL)
                ? spvc_context_get_last_error_string(context) : NULL;
        fprintf(stderr,
                "DroidBridgeVulkanAtlasCoverageV4: SPIRV-Cross failed result=%d error=%s; keeping original\n",
                spvcResult, error != NULL ? error : "<unavailable>");
        fflush(stderr);
        if (context != NULL) spvc_context_destroy(context);
        return false;
    }

    const char* mainStart = strstr(glsl, "void main()");
    char samplerName[128] = {0};
    char outputName[128] = {0};
    char uvName[128] = {0};
    bool samplerFound = db_vk_find_glsl_decl_identifier(
            glsl, "binding = 1", "uniform", "sampler", samplerName, sizeof(samplerName));
    if (!samplerFound) {
        samplerFound = db_vk_find_glsl_decl_identifier(
                glsl, "binding=1", "uniform", "sampler", samplerName, sizeof(samplerName));
    }
    bool outputFound = db_vk_find_glsl_decl_identifier(
            glsl, "location = 0", " out ", "vec4", outputName, sizeof(outputName));
    if (!outputFound) {
        outputFound = db_vk_find_glsl_decl_identifier(
                glsl, "location=0", " out ", "vec4", outputName, sizeof(outputName));
    }
    bool uvFound = db_vk_find_glsl_decl_identifier(
            glsl, "location = 0", " in ", "vec2", uvName, sizeof(uvName));
    if (!uvFound) {
        uvFound = db_vk_find_glsl_decl_identifier(
                glsl, "location=0", " in ", "vec2", uvName, sizeof(uvName));
    }
    if (!uvFound) {
        uvFound = db_vk_find_glsl_decl_identifier(
                glsl, NULL, " in ", "vec2", uvName, sizeof(uvName));
    }

    if (mainStart == NULL || !samplerFound || !outputFound || !uvFound) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasCoverageV4: interface discovery failed main=%d sampler1=%d output0=%d uv=%d; keeping original\n",
                mainStart != NULL ? 1 : 0, samplerFound ? 1 : 0,
                outputFound ? 1 : 0, uvFound ? 1 : 0);
        fflush(stderr);
        spvc_context_destroy(context);
        return false;
    }

    size_t prefixLen = (size_t)(mainStart - glsl);
    const char* bodyFormat =
            "void main() {\n"
            "    %s = vec4(1.0, 0.0, 1.0, 1.0);\n"
            "}\n";
    int bodyLenInt = snprintf(NULL, 0, bodyFormat, outputName);
    if (bodyLenInt <= 0) {
        spvc_context_destroy(context);
        return false;
    }
    size_t bodyLen = (size_t)bodyLenInt;
    if (prefixLen > (4u * 1024u * 1024u) || bodyLen > 2048u) {
        spvc_context_destroy(context);
        return false;
    }
    char* repairedGlsl = (char*)malloc(prefixLen + bodyLen + 1u);
    if (repairedGlsl == NULL) {
        spvc_context_destroy(context);
        return false;
    }
    memcpy(repairedGlsl, glsl, prefixLen);
    snprintf(repairedGlsl + prefixLen, bodyLen + 1u, bodyFormat, outputName);

    DB_shaderc_compiler shaderc = shaderc_compiler_initialize();
    if (shaderc == NULL) {
        free(repairedGlsl);
        spvc_context_destroy(context);
        return false;
    }
    DB_shaderc_result result = shaderc_compile_into_spv(
            shaderc, repairedGlsl, prefixLen + bodyLen, DB_SHADERC_GLSL_FRAGMENT_SHADER,
            "droidbridge_atlas_coverage_v4.frag", "main", NULL);
    if (result == NULL || shaderc_result_get_compilation_status(result)
            != DB_SHADERC_COMPILATION_STATUS_SUCCESS) {
        const char* error = (result != NULL && shaderc_result_get_error_message != NULL)
                ? shaderc_result_get_error_message(result) : NULL;
        fprintf(stderr,
                "DroidBridgeVulkanAtlasCoverageV4: shaderc failed sourceBytes=%zu error=%s; keeping original\n",
                prefixLen + bodyLen, error != NULL ? error : "<unavailable>");
        fflush(stderr);
        if (result != NULL) shaderc_result_release(result);
        shaderc_compiler_release(shaderc);
        free(repairedGlsl);
        spvc_context_destroy(context);
        return false;
    }

    size_t outputSize = shaderc_result_get_length(result);
    const char* outputBytes = shaderc_result_get_bytes(result);
    if (outputBytes == NULL || outputSize == 0u || (outputSize & 3u) != 0u
            || outputSize > (16u * 1024u * 1024u)) {
        if (result != NULL) shaderc_result_release(result);
        shaderc_compiler_release(shaderc);
        free(repairedGlsl);
        spvc_context_destroy(context);
        return false;
    }

    uint32_t* output = (uint32_t*)malloc(outputSize);
    if (output == NULL) {
        shaderc_result_release(result);
        shaderc_compiler_release(shaderc);
        free(repairedGlsl);
        spvc_context_destroy(context);
        return false;
    }
    memcpy(output, outputBytes, outputSize);
    uint64_t outputHash = db_vk_fnv1a64(output, outputSize);

    *replacement = *original;
    replacement->pCode = output;
    replacement->codeSize = outputSize;
    *replacementHashOut = outputHash;
    *ownedCodeOut = output;

    uint64_t rewrite = ++g_db_vk_atlas_fragment_rewrites;
    fprintf(stderr,
            "DroidBridgeVulkanAtlasCoverageV4: rewrite #%llu originalHash=%016llx originalSize=%zu repairedHash=%016llx repairedSize=%zu sampler=%s uv=%s output=%s output=solid-magenta placement=original-vertex-ubo source-sampling=bypassed\n",
            (unsigned long long)rewrite,
            (unsigned long long)DB_VK_ATLAS_BUILDER_FRAGMENT_HASH,
            original->codeSize,
            (unsigned long long)outputHash,
            outputSize, samplerName, uvName, outputName);
    fflush(stderr);

    shaderc_result_release(result);
    shaderc_compiler_release(shaderc);
    free(repairedGlsl);
    spvc_context_destroy(context);
    return true;
}

static bool db_vk_prepare_entity_shader_fallback(
        uint64_t inputHash,
        const DB_VkShaderModuleCreateInfo* original,
        DB_VkShaderModuleCreateInfo* replacement,
        uint64_t* replacementHashOut,
        uint32_t** ownedCodeOut) {
    if (original == NULL || replacement == NULL || replacementHashOut == NULL
            || ownedCodeOut == NULL) return false;

    // The whole-pair item shader substitution was proven semantically incompatible:
    // fragment-only and full-pair substitution both made paintings invisible.
    // Keep the original vertex shader and isolate the exact broken fragment to
    // a direct sample of its binding-6 base atlas using the original UV input.
    if (inputHash != DB_VK_BAD_ENTITY_FRAGMENT_HASH) return false;
    return db_vk_base_texture_bad_entity_fragment(
            original, replacement, replacementHashOut, ownedCodeOut);
}

static uint64_t db_vk_shader_hash_locked(DB_VkShaderModule module) {
    for (db_vk_shader_module_record* rec = g_db_vk_shader_modules;
         rec != NULL; rec = rec->next) {
        if (rec->module == module) return rec->hash;
    }
    return 0u;
}

static void db_vk_store_shader_module(
        DB_VkShaderModule module, const DB_VkShaderModuleCreateInfo* createInfo) {
    if (module == 0u || createInfo == NULL || createInfo->pCode == NULL
            || createInfo->codeSize == 0u) {
        return;
    }
    db_vk_shader_module_record* rec =
            (db_vk_shader_module_record*)calloc(1u, sizeof(*rec));
    if (rec == NULL) return;
    rec->module = module;
    rec->codeSize = createInfo->codeSize;
    rec->hash = db_vk_fnv1a64(createInfo->pCode, createInfo->codeSize);
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    rec->next = g_db_vk_shader_modules;
    g_db_vk_shader_modules = rec;
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);

    uint64_t count = ++g_db_vk_shader_modules_created;
    if (env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ENTITY_AUX_DIAGNOSTICS")
            && (count <= 192u || (count % 512u) == 0u)) {
        fprintf(stderr,
                "DroidBridgeVulkanShader: module #%llu handle=%llu codeSize=%zu fnv64=%016llx\n",
                (unsigned long long)count,
                (unsigned long long)module,
                createInfo->codeSize,
                (unsigned long long)rec->hash);
        fflush(stderr);
    }
}

static void db_vk_remove_shader_module(DB_VkShaderModule module) {
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    db_vk_shader_module_record** cursor = &g_db_vk_shader_modules;
    while (*cursor != NULL) {
        if ((*cursor)->module == module) {
            db_vk_shader_module_record* dead = *cursor;
            *cursor = dead->next;
            free(dead);
            break;
        }
        cursor = &(*cursor)->next;
    }
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
}

static const DB_VkPipelineRenderingCreateInfo*
db_vk_find_pipeline_rendering_info(const void* pNext) {
    const DB_VkBaseInStructure* node = (const DB_VkBaseInStructure*)pNext;
    unsigned int guard = 0u;
    while (node != NULL && guard++ < 32u) {
        if (node->sType == DB_VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO) {
            return (const DB_VkPipelineRenderingCreateInfo*)node;
        }
        node = (const DB_VkBaseInStructure*)node->pNext;
    }
    return NULL;
}

static void db_vk_store_pipeline_record(
        DB_VkPipeline pipeline, const DB_VkGraphicsPipelineCreateInfoPrefix* ci) {
    if (pipeline == 0u || ci == NULL) return;
    db_vk_pipeline_record* rec =
            (db_vk_pipeline_record*)calloc(1u, sizeof(*rec));
    if (rec == NULL) return;
    rec->pipeline = pipeline;
    rec->layout = ci->layout;
    rec->renderPass = ci->renderPass;
    rec->subpass = ci->subpass;

    if (ci->pStages != NULL) {
        for (uint32_t i = 0u; i < ci->stageCount; ++i) {
            const DB_VkPipelineShaderStageCreateInfo* stage = &ci->pStages[i];
            if ((stage->stage & DB_VK_SHADER_STAGE_VERTEX_BIT) != 0u) {
                rec->vertexModule = stage->module;
            }
            if ((stage->stage & DB_VK_SHADER_STAGE_FRAGMENT_BIT) != 0u) {
                rec->fragmentModule = stage->module;
            }
        }
    }
    if (ci->pVertexInputState != NULL) {
        rec->vertexBindingCount = ci->pVertexInputState->vertexBindingDescriptionCount;
        rec->vertexAttributeCount = ci->pVertexInputState->vertexAttributeDescriptionCount;
        if (ci->pVertexInputState->pVertexBindingDescriptions != NULL
                && rec->vertexBindingCount > 0u) {
            rec->vertexStride0 = ci->pVertexInputState->pVertexBindingDescriptions[0].stride;
        }
    }
    if (ci->pInputAssemblyState != NULL) {
        rec->topology = ci->pInputAssemblyState->topology;
        rec->primitiveRestartEnable = ci->pInputAssemblyState->primitiveRestartEnable;
    }
    if (ci->pRasterizationState != NULL) {
        rec->rasterizerDiscardEnable = ci->pRasterizationState->rasterizerDiscardEnable;
        rec->polygonMode = ci->pRasterizationState->polygonMode;
        rec->cullMode = ci->pRasterizationState->cullMode;
        rec->frontFace = ci->pRasterizationState->frontFace;
        rec->depthBiasEnable = ci->pRasterizationState->depthBiasEnable;
    }
    if (ci->pMultisampleState != NULL) {
        rec->rasterizationSamples = ci->pMultisampleState->rasterizationSamples;
        rec->sampleShadingEnable = ci->pMultisampleState->sampleShadingEnable;
    }
    if (ci->pDepthStencilState != NULL) {
        rec->depthTestEnable = ci->pDepthStencilState->depthTestEnable;
        rec->depthWriteEnable = ci->pDepthStencilState->depthWriteEnable;
        rec->depthCompareOp = ci->pDepthStencilState->depthCompareOp;
        rec->stencilTestEnable = ci->pDepthStencilState->stencilTestEnable;
    }
    if (ci->pColorBlendState != NULL) {
        rec->blendAttachmentCount = ci->pColorBlendState->attachmentCount;
        if (ci->pColorBlendState->pAttachments != NULL
                && ci->pColorBlendState->attachmentCount > 0u) {
            const DB_VkPipelineColorBlendAttachmentState* a =
                    &ci->pColorBlendState->pAttachments[0];
            rec->blendEnable0 = a->blendEnable;
            rec->srcColorBlendFactor0 = a->srcColorBlendFactor;
            rec->dstColorBlendFactor0 = a->dstColorBlendFactor;
            rec->colorBlendOp0 = a->colorBlendOp;
            rec->srcAlphaBlendFactor0 = a->srcAlphaBlendFactor;
            rec->dstAlphaBlendFactor0 = a->dstAlphaBlendFactor;
            rec->alphaBlendOp0 = a->alphaBlendOp;
            rec->colorWriteMask0 = a->colorWriteMask;
        }
    }
    if (ci->pDynamicState != NULL && ci->pDynamicState->pDynamicStates != NULL) {
        rec->dynamicStateCount = ci->pDynamicState->dynamicStateCount;
        uint32_t copyCount = rec->dynamicStateCount < 16u
                ? rec->dynamicStateCount : 16u;
        for (uint32_t i = 0u; i < copyCount; ++i) {
            rec->dynamicStates[i] = ci->pDynamicState->pDynamicStates[i];
        }
    }
    const DB_VkPipelineRenderingCreateInfo* rendering =
            db_vk_find_pipeline_rendering_info(ci->pNext);
    if (rendering != NULL) {
        rec->dynamicRenderingColorCount = rendering->colorAttachmentCount;
        if (rendering->pColorAttachmentFormats != NULL
                && rendering->colorAttachmentCount > 0u) {
            rec->dynamicRenderingColor0 = rendering->pColorAttachmentFormats[0];
        }
        rec->dynamicRenderingDepth = rendering->depthAttachmentFormat;
        rec->dynamicRenderingStencil = rendering->stencilAttachmentFormat;
    }

    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    rec->vertexHash = db_vk_shader_hash_locked(rec->vertexModule);
    rec->fragmentHash = db_vk_shader_hash_locked(rec->fragmentModule);
    rec->next = g_db_vk_pipeline_records;
    g_db_vk_pipeline_records = rec;
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
}

static bool db_vk_lookup_pipeline_record(
        DB_VkPipeline pipeline, db_vk_pipeline_record* out) {
    if (out == NULL || pipeline == 0u) return false;
    bool found = false;
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    for (db_vk_pipeline_record* rec = g_db_vk_pipeline_records;
         rec != NULL; rec = rec->next) {
        if (rec->pipeline == pipeline) {
            *out = *rec;
            out->next = NULL;
            found = true;
            break;
        }
    }
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
    return found;
}

static void db_vk_set_bound_pipeline(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint bindPoint,
        DB_VkPipeline pipeline) {
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    db_vk_command_pipeline_state* state = g_db_vk_command_pipelines;
    while (state != NULL) {
        if (state->commandBuffer == commandBuffer && state->bindPoint == bindPoint) break;
        state = state->next;
    }
    if (state == NULL) {
        state = (db_vk_command_pipeline_state*)calloc(1u, sizeof(*state));
        if (state != NULL) {
            state->commandBuffer = commandBuffer;
            state->bindPoint = bindPoint;
            state->next = g_db_vk_command_pipelines;
            g_db_vk_command_pipelines = state;
        }
    }
    if (state != NULL) state->pipeline = pipeline;
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
}

static DB_VkPipeline db_vk_get_bound_pipeline(
        DB_VkCommandBuffer commandBuffer, DB_VkPipelineBindPoint bindPoint) {
    DB_VkPipeline pipeline = 0u;
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    for (db_vk_command_pipeline_state* state = g_db_vk_command_pipelines;
         state != NULL; state = state->next) {
        if (state->commandBuffer == commandBuffer && state->bindPoint == bindPoint) {
            pipeline = state->pipeline;
            break;
        }
    }
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
    return pipeline;
}

static void db_vk_clear_bound_pipeline(DB_VkCommandBuffer commandBuffer) {
    pthread_mutex_lock(&g_db_vk_pipeline_state_mutex);
    db_vk_command_pipeline_state** cursor = &g_db_vk_command_pipelines;
    while (*cursor != NULL) {
        if ((*cursor)->commandBuffer == commandBuffer) {
            db_vk_command_pipeline_state* dead = *cursor;
            *cursor = dead->next;
            free(dead);
            continue;
        }
        cursor = &(*cursor)->next;
    }
    pthread_mutex_unlock(&g_db_vk_pipeline_state_mutex);
}

static bool db_vk_push_descriptor_fix_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_FULL_PUSH_DESCRIPTORS");
}

static bool db_vk_disable_multi_draw_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_DISABLE_MULTI_DRAW");
}

static bool db_vk_disable_zero_divisor_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_DISABLE_ZERO_DIVISOR");
}

static bool db_vk_api_cap_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_CAP_API_1_3");
}

static bool db_vk_image_diagnostics_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_IMAGE_DIAGNOSTICS");
}

static bool db_vk_pipeline_diagnostics_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_PIPELINE_DIAGNOSTICS");
}

static bool db_vk_atlas_sync_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_SYNC_REPAIR");
}

static bool db_vk_atlas_source_sync_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_SOURCE_SYNC_REPAIR");
}

static bool db_vk_atlas_build_input_audit_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_BUILD_INPUT_AUDIT");
}

static bool db_vk_static_atlas_layout_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_STATIC_ATLAS_LAYOUT_REPAIR");
}

static bool db_vk_sprite_upload_layout_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_SPRITE_UPLOAD_LAYOUT_REPAIR");
}

static bool db_vk_asset_staging_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ASSET_STAGING_REPAIR");
}

static bool db_vk_atlas_ubo_flush_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_UBO_FLUSH_REPAIR");
}

static bool db_vk_atlas_image_copy_test_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_IMAGE_COPY_TEST");
}

static bool db_vk_atlas_direct_compose_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_DIRECT_COMPOSE_REPAIR");
}

static bool db_vk_atlas_fragment_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ATLAS_FRAGMENT_REPAIR");
}

static bool db_vk_memory_tracking_enabled(void) {
    return db_vk_asset_staging_repair_enabled()
            || db_vk_atlas_ubo_flush_repair_enabled()
            || db_vk_atlas_direct_compose_repair_enabled()
            || db_vk_atlas_fragment_repair_enabled();
}

static bool db_vk_special_draw_sync_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_SPECIAL_DRAW_SYNC_REPAIR");
}

static bool db_vk_entity_normal_format_repair_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ENTITY_NORMAL_FORMAT_REPAIR");
}

static bool db_vk_entity_aux_diagnostics_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ENTITY_AUX_DIAGNOSTICS");
}

static bool db_vk_entity_shader_fallback_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_ENTITY_SHADER_FALLBACK");
}

static bool db_vk_panvk_g57_safe_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_VK_PANVK_G57_SAFE");
}

static bool db_vk_surface_identity_enabled(void) {
    return env_enabled_lwjgl_hook("DROIDBRIDGE_SDL3_FORCE_VULKAN_IDENTITY");
}

/*
 * Custom PanVK is routed through DroidBridge's Vulkan proxy even when none of
 * the optional VulkanMod compatibility toggles are enabled. Some Android
 * PanVK builds accept device creation but reject MAILBOX swapchains with
 * VK_ERROR_INVALID_EXTERNAL_HANDLE. Detect only an explicitly selected custom
 * PanVK/Panfrost ICD; native/System Vulkan and non-PanVK drivers stay untouched.
 */
static bool db_vk_custom_panvk_swapchain_compat_enabled(void) {
    if (!droidbridge_truthy_env("DROIDBRIDGE_LOAD_CUSTOM_VULKAN")
            || droidbridge_truthy_env("DROIDBRIDGE_USE_SYSTEM_VULKAN")) {
        return false;
    }
    const char* name = getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME");
    const char* report = getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT");
    return (name != NULL && (strstr(name, "panfrost") != NULL
            || strstr(name, "PanVK") != NULL || strstr(name, "panvk") != NULL))
            || (report != NULL && (strstr(report, "PanVK") != NULL
            || strstr(report, "panvk") != NULL || strstr(report, "panfrost") != NULL));
}

static bool db_vk_any_compat_enabled(void) {
    return db_vk_push_descriptor_fix_enabled()
            || db_vk_disable_multi_draw_enabled()
            || db_vk_disable_zero_divisor_enabled()
            || db_vk_api_cap_enabled()
            || db_vk_image_diagnostics_enabled()
            || db_vk_pipeline_diagnostics_enabled()
            || db_vk_atlas_sync_repair_enabled()
            || db_vk_atlas_source_sync_repair_enabled()
            || db_vk_atlas_build_input_audit_enabled()
            || db_vk_static_atlas_layout_repair_enabled()
            || db_vk_sprite_upload_layout_repair_enabled()
            || db_vk_asset_staging_repair_enabled()
            || db_vk_atlas_ubo_flush_repair_enabled()
            || db_vk_atlas_image_copy_test_enabled()
            || db_vk_atlas_direct_compose_repair_enabled()
            || db_vk_atlas_fragment_repair_enabled()
            || db_vk_special_draw_sync_repair_enabled()
            || db_vk_entity_normal_format_repair_enabled()
            || db_vk_entity_aux_diagnostics_enabled()
            || db_vk_entity_shader_fallback_enabled()
            || db_vk_panvk_g57_safe_enabled()
            || db_vk_surface_identity_enabled()
            || db_vk_custom_panvk_swapchain_compat_enabled();
}

static void db_vk_log_compat_once(void) {
    if (g_db_vk_compat_logged || !db_vk_any_compat_enabled()) return;
    g_db_vk_compat_logged = true;
    fprintf(stderr,
            "DroidBridgeVulkanCompat: active pushDescriptors=%d disableMultiDraw=%d disableZeroDivisor=%d capApi13=%d imageDiagnostics=%d pipelineDiagnostics=%d atlasSyncRepair=%d atlasSourceSyncRepair=%d atlasBuildInputAudit=%d staticAtlasLayoutRepair=%d spriteUploadLayoutRepair=%d assetStagingRepair=%d atlasUboFlushRepair=%d atlasImageCopyTest=%d atlasDirectComposeRepair=%d atlasFragmentRepair=%d specialDrawSyncRepair=%d entityNormalFormatRepair=%d entityAuxDiagnostics=%d entityShaderFallback=%d panVkG57Safe=%d surfaceIdentity=%d customPanVkSwapchain=%d\n",
            db_vk_push_descriptor_fix_enabled() ? 1 : 0,
            db_vk_disable_multi_draw_enabled() ? 1 : 0,
            db_vk_disable_zero_divisor_enabled() ? 1 : 0,
            db_vk_api_cap_enabled() ? 1 : 0,
            db_vk_image_diagnostics_enabled() ? 1 : 0,
            db_vk_pipeline_diagnostics_enabled() ? 1 : 0,
            db_vk_atlas_sync_repair_enabled() ? 1 : 0,
            db_vk_atlas_source_sync_repair_enabled() ? 1 : 0,
            db_vk_atlas_build_input_audit_enabled() ? 1 : 0,
            db_vk_static_atlas_layout_repair_enabled() ? 1 : 0,
            db_vk_sprite_upload_layout_repair_enabled() ? 1 : 0,
            db_vk_asset_staging_repair_enabled() ? 1 : 0,
            db_vk_atlas_ubo_flush_repair_enabled() ? 1 : 0,
            db_vk_atlas_image_copy_test_enabled() ? 1 : 0,
            db_vk_atlas_direct_compose_repair_enabled() ? 1 : 0,
            db_vk_atlas_fragment_repair_enabled() ? 1 : 0,
            db_vk_special_draw_sync_repair_enabled() ? 1 : 0,
            db_vk_entity_normal_format_repair_enabled() ? 1 : 0,
            db_vk_entity_aux_diagnostics_enabled() ? 1 : 0,
            db_vk_entity_shader_fallback_enabled() ? 1 : 0,
            db_vk_panvk_g57_safe_enabled() ? 1 : 0,
            db_vk_surface_identity_enabled() ? 1 : 0,
            db_vk_custom_panvk_swapchain_compat_enabled() ? 1 : 0);
    fflush(stderr);
}

__attribute__((used, visibility("default"))) int
droidbridge_vulkan_compat_real_loader_ready(void) {
    return g_db_real_vkGetInstanceProcAddr != NULL;
}

__attribute__((used, visibility("default"))) int
droidbridge_vulkan_compat_prepare_real_loader(void* realLoaderHandle) {
    if (realLoaderHandle == NULL) return 0;

    /*
     * The Vulkan proxy is also the early-loader gate for Kopper. In that mode
     * no Vulkan compatibility transforms are enabled; still capture the real
     * loader's proc-address functions so the proxy is a transparent passthrough.
     */
    if (db_vk_any_compat_enabled()) db_vk_log_compat_once();
    g_db_real_vkGetInstanceProcAddr = (DB_PFN_vkGetInstanceProcAddr)
            dlsym(realLoaderHandle, "vkGetInstanceProcAddr");
    g_db_real_vkGetDeviceProcAddr = (DB_PFN_vkGetDeviceProcAddr)
            dlsym(realLoaderHandle, "vkGetDeviceProcAddr");
    if (g_db_real_vkGetDeviceProcAddr == NULL
            && g_db_real_vkGetInstanceProcAddr != NULL) {
        g_db_real_vkGetDeviceProcAddr = (DB_PFN_vkGetDeviceProcAddr)
                g_db_real_vkGetInstanceProcAddr(NULL, "vkGetDeviceProcAddr");
    }

    if (g_db_real_vkGetInstanceProcAddr == NULL) {
        const char* error = dlerror();
        fprintf(stderr,
                "DroidBridgeVulkanCompat: real loader has no vkGetInstanceProcAddr handle=%p error=%s\n",
                realLoaderHandle,
                error != NULL ? error : "unknown");
        fflush(stderr);
        return 0;
    }

    fprintf(stderr,
            "DroidBridgeVulkanCompat: real loader prepared handle=%p gipa=%p gdpa=%p\n",
            realLoaderHandle,
            (void*)g_db_real_vkGetInstanceProcAddr,
            (void*)g_db_real_vkGetDeviceProcAddr);
    fflush(stderr);
    return 1;
}

__attribute__((used, visibility("default"))) void*
droidbridge_vulkan_compat_select_loader(void* realLoaderHandle) {
    if (!db_vk_any_compat_enabled() || realLoaderHandle == NULL) {
        return realLoaderHandle;
    }
    if (!droidbridge_vulkan_compat_prepare_real_loader(realLoaderHandle)) {
        return realLoaderHandle;
    }

    if (g_db_vk_proxy_loader_handle == NULL) {
        dlerror();
        g_db_vk_proxy_loader_handle = dlopen(
                "libdroidbridge_vulkan_proxy.so", RTLD_NOW | RTLD_LOCAL);
        if (g_db_vk_proxy_loader_handle == NULL) {
            const char* error = dlerror();
            fprintf(stderr,
                    "DroidBridgeVulkanCompat: unable to load Vulkan proxy; using real loader error=%s\n",
                    error != NULL ? error : "unknown");
            fflush(stderr);
            return realLoaderHandle;
        }
    }

    void* proxyGipa = dlsym(g_db_vk_proxy_loader_handle, "vkGetInstanceProcAddr");
    if (proxyGipa == NULL) {
        fprintf(stderr,
                "DroidBridgeVulkanCompat: proxy missing vkGetInstanceProcAddr; using real loader\n");
        fflush(stderr);
        return realLoaderHandle;
    }

    fprintf(stderr,
            "DroidBridgeVulkanCompat: loader proxy selected real=%p proxy=%p gipa=%p\n",
            realLoaderHandle,
            g_db_vk_proxy_loader_handle,
            proxyGipa);
    fflush(stderr);
    return g_db_vk_proxy_loader_handle;
}

static const char* db_vk_layout_name(int32_t layout) {
    switch (layout) {
        case DB_VK_IMAGE_LAYOUT_UNDEFINED: return "UNDEFINED";
        case DB_VK_IMAGE_LAYOUT_GENERAL: return "GENERAL";
        case DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL: return "SHADER_READ_ONLY";
        case DB_VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL: return "TRANSFER_SRC";
        case DB_VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL: return "TRANSFER_DST";
        default: return "OTHER";
    }
}

static db_vk_image_record* db_vk_find_image_locked(DB_VkImage image) {
    for (db_vk_image_record* record = g_db_vk_images;
         record != NULL;
         record = record->next) {
        if (record->image == image) return record;
    }
    return NULL;
}

static db_vk_image_view_record* db_vk_find_image_view_locked(DB_VkImageView view) {
    for (db_vk_image_view_record* record = g_db_vk_image_views;
         record != NULL;
         record = record->next) {
        if (record->view == view) return record;
    }
    return NULL;
}

static void db_vk_fill_image_summary_locked(
        const db_vk_image_record* record,
        db_vk_image_summary* out) {
    if (record == NULL || out == NULL) return;
    out->image = record->image;
    out->width = record->width;
    out->height = record->height;
    out->depth = record->depth;
    out->mipLevels = record->mipLevels;
    out->arrayLayers = record->arrayLayers;
    out->format = record->format;
    out->usage = record->usage;
    out->bufferUploadCalls = record->bufferUploadCalls;
    out->imageCopyWriteCalls = record->imageCopyWriteCalls;
    out->imageCopyReadCalls = record->imageCopyReadCalls;
    out->imageBlitWriteCalls = record->imageBlitWriteCalls;
    out->imageBlitReadCalls = record->imageBlitReadCalls;
    out->renderTargetBeginCalls = record->renderTargetBeginCalls;
    out->barrierCalls = record->barrierCalls;
    out->lastRenderLoadOp = record->lastRenderLoadOp;
    out->lastRenderStoreOp = record->lastRenderStoreOp;
    memcpy(out->lastRenderClearColor, record->lastRenderClearColor,
           sizeof(out->lastRenderClearColor));
    out->lastOldLayout = record->lastOldLayout;
    out->lastNewLayout = record->lastNewLayout;
    out->lastSrcStageMask = record->lastSrcStageMask;
    out->lastSrcAccessMask = record->lastSrcAccessMask;
    out->lastDstStageMask = record->lastDstStageMask;
    out->lastDstAccessMask = record->lastDstAccessMask;
    out->lastUploadBuffer = record->lastUploadBuffer;
    out->lastUploadBufferOffset = record->lastUploadBufferOffset;
    out->lastUploadBufferRowLength = record->lastUploadBufferRowLength;
    out->lastUploadBufferImageHeight = record->lastUploadBufferImageHeight;
    out->lastUploadWidth = record->lastUploadWidth;
    out->lastUploadHeight = record->lastUploadHeight;
    out->lastUploadDepth = record->lastUploadDepth;
    out->lastUploadMipLevel = record->lastUploadMipLevel;
    out->lastUploadBaseArrayLayer = record->lastUploadBaseArrayLayer;
    out->lastUploadLayerCount = record->lastUploadLayerCount;
    out->lastUploadLayout = record->lastUploadLayout;
    out->staticShaderReadLayoutRepair = record->staticShaderReadLayoutRepair;
    out->spriteUploadShaderReadLayoutRepair = record->spriteUploadShaderReadLayoutRepair;
}

static bool db_vk_lookup_image_summary(DB_VkImage image, db_vk_image_summary* out) {
    if (image == 0u || out == NULL) return false;
    bool found = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(image);
    if (record != NULL) {
        memset(out, 0, sizeof(*out));
        db_vk_fill_image_summary_locked(record, out);
        found = true;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return found;
}

static bool db_vk_lookup_image_view_summary(DB_VkImageView view, db_vk_image_summary* out) {
    if (view == 0u || out == NULL) return false;
    bool found = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_view_record* viewRecord = db_vk_find_image_view_locked(view);
    if (viewRecord != NULL) {
        db_vk_image_record* imageRecord = db_vk_find_image_locked(viewRecord->image);
        if (imageRecord != NULL) {
            memset(out, 0, sizeof(*out));
            db_vk_fill_image_summary_locked(imageRecord, out);
            found = true;
        }
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return found;
}

static bool db_vk_set_static_shader_read_layout_repair(DB_VkImage image, bool enabled) {
    if (image == 0u) return false;
    bool found = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(image);
    if (record != NULL) {
        record->staticShaderReadLayoutRepair = enabled;
        found = true;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return found;
}

static bool db_vk_view_has_static_shader_read_layout_repair(
        DB_VkImageView view, db_vk_image_summary* out) {
    if (view == 0u) return false;
    bool active = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_view_record* viewRecord = db_vk_find_image_view_locked(view);
    if (viewRecord != NULL) {
        db_vk_image_record* imageRecord = db_vk_find_image_locked(viewRecord->image);
        if (imageRecord != NULL && imageRecord->staticShaderReadLayoutRepair) {
            active = true;
            if (out != NULL) {
                memset(out, 0, sizeof(*out));
                db_vk_fill_image_summary_locked(imageRecord, out);
            }
        }
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return active;
}

static db_vk_descriptor_payload_kind db_vk_payload_kind(DB_VkDescriptorType type);

static bool db_vk_set_sprite_upload_shader_read_layout_repair(
        DB_VkImage image, bool enabled) {
    if (image == 0u) return false;
    bool found = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(image);
    if (record != NULL) {
        record->spriteUploadShaderReadLayoutRepair = enabled;
        found = true;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return found;
}

static bool db_vk_view_has_sprite_upload_shader_read_layout_repair(
        DB_VkImageView view, db_vk_image_summary* out) {
    if (view == 0u) return false;
    bool active = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_view_record* viewRecord = db_vk_find_image_view_locked(view);
    if (viewRecord != NULL) {
        db_vk_image_record* imageRecord = db_vk_find_image_locked(viewRecord->image);
        if (imageRecord != NULL && imageRecord->spriteUploadShaderReadLayoutRepair) {
            active = true;
            if (out != NULL) {
                memset(out, 0, sizeof(*out));
                db_vk_fill_image_summary_locked(imageRecord, out);
            }
        }
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return active;
}

static uint64_t db_vk_fnv1a64_update(uint64_t hash, const uint8_t* data, size_t size);

static db_vk_memory_map_record* db_vk_find_memory_map_locked(DB_VkDeviceMemory memory) {
    for (db_vk_memory_map_record* record = g_db_vk_memory_maps;
         record != NULL; record = record->next) {
        if (record->memory == memory) return record;
    }
    return NULL;
}

static db_vk_buffer_memory_record* db_vk_find_buffer_binding_locked(DB_VkBuffer buffer) {
    for (db_vk_buffer_memory_record* record = g_db_vk_buffer_bindings;
         record != NULL; record = record->next) {
        if (record->buffer == buffer) return record;
    }
    return NULL;
}

static db_vk_buffer_write_record* db_vk_find_buffer_write_locked(DB_VkBuffer buffer) {
    for (db_vk_buffer_write_record* record = g_db_vk_buffer_writes;
         record != NULL; record = record->next) {
        if (record->dstBuffer == buffer) return record;
    }
    return NULL;
}

static void db_vk_record_buffer_write(
        DB_VkBuffer dstBuffer, DB_VkBuffer srcBuffer,
        uint64_t srcOffset, uint64_t dstOffset, uint64_t size,
        db_vk_buffer_write_kind kind, const void* updateData) {
    if ((!db_vk_atlas_ubo_flush_repair_enabled()
            && !db_vk_atlas_direct_compose_repair_enabled()
            && !db_vk_atlas_fragment_repair_enabled())
            || dstBuffer == 0u || size == 0u) return;
    uint64_t updateHash = 0u;
    uint64_t updateNonZero = 0u;
    if (kind == DB_VK_BUFFER_WRITE_UPDATE && updateData != NULL) {
        uint64_t bytes = size > 512u ? 512u : size;
        updateHash = 1469598103934665603ULL;
        updateHash = db_vk_fnv1a64_update(updateHash, (const uint8_t*)updateData, (size_t)bytes);
        for (uint64_t i = 0u; i < bytes; ++i) {
            if (((const uint8_t*)updateData)[i] != 0u) ++updateNonZero;
        }
    }
    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_buffer_write_record* record = db_vk_find_buffer_write_locked(dstBuffer);
    if (record == NULL) {
        record = (db_vk_buffer_write_record*)calloc(1u, sizeof(*record));
        if (record != NULL) {
            record->dstBuffer = dstBuffer;
            record->next = g_db_vk_buffer_writes;
            g_db_vk_buffer_writes = record;
        }
    }
    if (record != NULL) {
        record->srcBuffer = srcBuffer;
        record->srcOffset = srcOffset;
        record->dstOffset = dstOffset;
        record->size = size;
        record->kind = kind;
        record->updateHash = updateHash;
        record->updateNonZeroBytes = updateNonZero;
        record->sequence = ++g_db_vk_buffer_write_sequence;
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);
}

static void db_vk_cmd_copy_buffer(
        DB_VkCommandBuffer commandBuffer, DB_VkBuffer srcBuffer, DB_VkBuffer dstBuffer,
        uint32_t regionCount, const DB_VkBufferCopy* regions) {
    if (regions != NULL) {
        for (uint32_t i = 0u; i < regionCount; ++i) {
            db_vk_record_buffer_write(dstBuffer, srcBuffer,
                    regions[i].srcOffset, regions[i].dstOffset, regions[i].size,
                    DB_VK_BUFFER_WRITE_COPY, NULL);
        }
    }
    if (g_db_real_vkCmdCopyBuffer != NULL) {
        g_db_real_vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, regionCount, regions);
    }
}

static void db_vk_cmd_copy_buffer2(
        DB_VkCommandBuffer commandBuffer, const DB_VkCopyBufferInfo2* copyInfo) {
    if (copyInfo != NULL && copyInfo->pRegions != NULL) {
        for (uint32_t i = 0u; i < copyInfo->regionCount; ++i) {
            db_vk_record_buffer_write(copyInfo->dstBuffer, copyInfo->srcBuffer,
                    copyInfo->pRegions[i].srcOffset, copyInfo->pRegions[i].dstOffset,
                    copyInfo->pRegions[i].size, DB_VK_BUFFER_WRITE_COPY, NULL);
        }
    }
    if (g_db_real_vkCmdCopyBuffer2 != NULL) {
        g_db_real_vkCmdCopyBuffer2(commandBuffer, copyInfo);
    }
}

static void db_vk_cmd_update_buffer(
        DB_VkCommandBuffer commandBuffer, DB_VkBuffer dstBuffer,
        uint64_t dstOffset, uint64_t dataSize, const void* data) {
    db_vk_record_buffer_write(dstBuffer, 0u, 0u, dstOffset, dataSize,
            DB_VK_BUFFER_WRITE_UPDATE, data);
    if (g_db_real_vkCmdUpdateBuffer != NULL) {
        g_db_real_vkCmdUpdateBuffer(commandBuffer, dstBuffer, dstOffset, dataSize, data);
    }
}

static DB_VkResult db_vk_map_memory(
        DB_VkDevice device,
        DB_VkDeviceMemory memory,
        uint64_t offset,
        uint64_t size,
        uint32_t flags,
        void** data) {
    DB_PFN_vkMapMemory real = g_db_real_vkMapMemory;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, memory, offset, size, flags, data);
    if (!db_vk_memory_tracking_enabled()
            || result != DB_VK_SUCCESS || data == NULL || *data == NULL) {
        return result;
    }
    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_memory_map_record* record = db_vk_find_memory_map_locked(memory);
    if (record == NULL) {
        record = (db_vk_memory_map_record*)calloc(1u, sizeof(*record));
        if (record != NULL) {
            record->memory = memory;
            record->next = g_db_vk_memory_maps;
            g_db_vk_memory_maps = record;
        }
    }
    if (record != NULL) {
        record->device = device;
        record->mapped = *data;
        record->mapOffset = offset;
        record->mapSize = size;
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);
    return result;
}

static void db_vk_unmap_memory(DB_VkDevice device, DB_VkDeviceMemory memory) {
    if (db_vk_asset_staging_repair_enabled()) {
        bool canFlushWhole = false;
        pthread_mutex_lock(&g_db_vk_memory_mutex);
        db_vk_memory_map_record* record = db_vk_find_memory_map_locked(memory);
        if (record != NULL) {
            canFlushWhole = record->mapped != NULL
                    && record->mapOffset == 0u
                    && record->mapSize == DB_VK_WHOLE_SIZE;
        }
        pthread_mutex_unlock(&g_db_vk_memory_mutex);
        if (canFlushWhole) {
            if (g_db_real_vkFlushMappedMemoryRanges == NULL
                    && g_db_real_vkGetDeviceProcAddr != NULL) {
                g_db_real_vkFlushMappedMemoryRanges = (DB_PFN_vkFlushMappedMemoryRanges)
                        g_db_real_vkGetDeviceProcAddr(device, "vkFlushMappedMemoryRanges");
            }
            if (g_db_real_vkFlushMappedMemoryRanges != NULL) {
                DB_VkMappedMemoryRange flushRange;
                memset(&flushRange, 0, sizeof(flushRange));
                flushRange.sType = DB_VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
                flushRange.memory = memory;
                flushRange.offset = 0u;
                flushRange.size = DB_VK_WHOLE_SIZE;
                ++g_db_vk_asset_staging_flush_attempts;
                if (g_db_real_vkFlushMappedMemoryRanges(device, 1u, &flushRange) == DB_VK_SUCCESS) {
                    ++g_db_vk_asset_staging_flush_successes;
                }
            }
        }
    }
    if (db_vk_memory_tracking_enabled()) {
        pthread_mutex_lock(&g_db_vk_memory_mutex);
        db_vk_memory_map_record* record = db_vk_find_memory_map_locked(memory);
        if (record != NULL) {
            record->mapped = NULL;
            record->mapOffset = 0u;
            record->mapSize = 0u;
        }
        pthread_mutex_unlock(&g_db_vk_memory_mutex);
    }
    if (g_db_real_vkUnmapMemory != NULL) g_db_real_vkUnmapMemory(device, memory);
}

static DB_VkResult db_vk_flush_mapped_memory_ranges(
        DB_VkDevice device,
        uint32_t rangeCount,
        const DB_VkMappedMemoryRange* ranges) {
    DB_PFN_vkFlushMappedMemoryRanges real = g_db_real_vkFlushMappedMemoryRanges;
    return real != NULL ? real(device, rangeCount, ranges) : DB_VK_ERROR_INITIALIZATION_FAILED;
}

static void db_vk_record_buffer_binding(
        DB_VkDevice device,
        DB_VkBuffer buffer,
        DB_VkDeviceMemory memory,
        uint64_t memoryOffset) {
    if (!db_vk_memory_tracking_enabled() || buffer == 0u || memory == 0u) return;
    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_buffer_memory_record* record = db_vk_find_buffer_binding_locked(buffer);
    if (record == NULL) {
        record = (db_vk_buffer_memory_record*)calloc(1u, sizeof(*record));
        if (record != NULL) {
            record->buffer = buffer;
            record->next = g_db_vk_buffer_bindings;
            g_db_vk_buffer_bindings = record;
        }
    }
    if (record != NULL) {
        record->device = device;
        record->memory = memory;
        record->memoryOffset = memoryOffset;
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);
}

static DB_VkResult db_vk_bind_buffer_memory(
        DB_VkDevice device,
        DB_VkBuffer buffer,
        DB_VkDeviceMemory memory,
        uint64_t memoryOffset) {
    DB_PFN_vkBindBufferMemory real = g_db_real_vkBindBufferMemory;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, buffer, memory, memoryOffset);
    if (result == DB_VK_SUCCESS) {
        db_vk_record_buffer_binding(device, buffer, memory, memoryOffset);
    }
    return result;
}

static DB_VkResult db_vk_bind_buffer_memory2(
        DB_VkDevice device,
        uint32_t bindInfoCount,
        const DB_VkBindBufferMemoryInfo* bindInfos) {
    DB_PFN_vkBindBufferMemory2 real = g_db_real_vkBindBufferMemory2;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, bindInfoCount, bindInfos);
    if (result == DB_VK_SUCCESS && bindInfos != NULL) {
        for (uint32_t i = 0u; i < bindInfoCount; ++i) {
            db_vk_record_buffer_binding(
                    device, bindInfos[i].buffer, bindInfos[i].memory, bindInfos[i].memoryOffset);
        }
    }
    return result;
}

static uint64_t db_vk_fnv1a64_update(uint64_t hash, const uint8_t* data, size_t size) {
    const uint64_t prime = 1099511628211ULL;
    if (data == NULL) return hash;
    for (size_t i = 0u; i < size; ++i) {
        hash ^= (uint64_t)data[i];
        hash *= prime;
    }
    return hash;
}

static bool db_vk_asset_staging_prepare_copy(
        DB_VkBuffer srcBuffer,
        const db_vk_image_summary* dstSummary,
        uint32_t regionCount,
        const DB_VkBufferImageCopy* regions) {
    if (!db_vk_asset_staging_repair_enabled()
            || srcBuffer == 0u || dstSummary == NULL
            || dstSummary->format != 37 || regions == NULL || regionCount == 0u) {
        return false;
    }

    DB_VkDevice device = NULL;
    DB_VkDeviceMemory memory = 0u;
    uint64_t bindingOffset = 0u;
    void* mapped = NULL;
    uint64_t mapOffset = 0u;
    uint64_t mapSize = 0u;
    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_buffer_memory_record* binding = db_vk_find_buffer_binding_locked(srcBuffer);
    if (binding != NULL) {
        device = binding->device;
        memory = binding->memory;
        bindingOffset = binding->memoryOffset;
        db_vk_memory_map_record* map = db_vk_find_memory_map_locked(memory);
        if (map != NULL) {
            mapped = map->mapped;
            mapOffset = map->mapOffset;
            mapSize = map->mapSize;
            if (device == NULL) device = map->device;
        }
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);

    bool flushAttempted = false;
    bool flushSucceeded = false;
    if (device != NULL && g_db_real_vkFlushMappedMemoryRanges == NULL
            && g_db_real_vkGetDeviceProcAddr != NULL) {
        g_db_real_vkFlushMappedMemoryRanges = (DB_PFN_vkFlushMappedMemoryRanges)
                g_db_real_vkGetDeviceProcAddr(device, "vkFlushMappedMemoryRanges");
    }
    if (device != NULL && memory != 0u && mapped != NULL
            && g_db_real_vkFlushMappedMemoryRanges != NULL
            && mapOffset == 0u && mapSize == DB_VK_WHOLE_SIZE) {
        DB_VkMappedMemoryRange flushRange;
        memset(&flushRange, 0, sizeof(flushRange));
        flushRange.sType = DB_VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        flushRange.memory = memory;
        flushRange.offset = 0u;
        flushRange.size = DB_VK_WHOLE_SIZE;
        ++g_db_vk_asset_staging_flush_attempts;
        flushAttempted = true;
        flushSucceeded = g_db_real_vkFlushMappedMemoryRanges(device, 1u, &flushRange) == DB_VK_SUCCESS;
        if (flushSucceeded) ++g_db_vk_asset_staging_flush_successes;
    }

    const DB_VkBufferImageCopy* r = &regions[0];
    uint64_t hash = 1469598103934665603ULL;
    uint64_t rgbNonZeroPixels = 0u;
    uint64_t alphaNonZeroPixels = 0u;
    uint64_t sampledPixels = 0u;
    bool hashed = false;
    if (mapped != NULL && r->imageExtent.depth == 1u
            && r->imageExtent.width > 0u && r->imageExtent.height > 0u
            && r->imageExtent.width <= 4096u && r->imageExtent.height <= 4096u) {
        uint64_t absoluteOffset = bindingOffset + r->bufferOffset;
        if (absoluteOffset >= mapOffset) {
            uint64_t relativeOffset = absoluteOffset - mapOffset;
            uint64_t rowPixels = r->bufferRowLength != 0u
                    ? (uint64_t)r->bufferRowLength : (uint64_t)r->imageExtent.width;
            uint64_t rowBytes = rowPixels * 4u;
            uint64_t logicalRowBytes = (uint64_t)r->imageExtent.width * 4u;
            uint64_t lastByte = relativeOffset
                    + (uint64_t)(r->imageExtent.height - 1u) * rowBytes
                    + logicalRowBytes;
            bool withinMap = mapSize == DB_VK_WHOLE_SIZE || lastByte <= mapSize;
            if (withinMap && logicalRowBytes <= (16u * 1024u * 1024u)) {
                const uint8_t* base = (const uint8_t*)mapped + relativeOffset;
                for (uint32_t y = 0u; y < r->imageExtent.height; ++y) {
                    const uint8_t* row = base + (uint64_t)y * rowBytes;
                    hash = db_vk_fnv1a64_update(hash, row, (size_t)logicalRowBytes);
                    for (uint32_t x = 0u; x < r->imageExtent.width; ++x) {
                        const uint8_t* px = row + (size_t)x * 4u;
                        if (px[0] != 0u || px[1] != 0u || px[2] != 0u) ++rgbNonZeroPixels;
                        if (px[3] != 0u) ++alphaNonZeroPixels;
                        ++sampledPixels;
                    }
                }
                hashed = true;
                ++g_db_vk_asset_staging_hashes;
            }
        }
    }

    if (!g_db_vk_asset_staging_repair_logged) {
        g_db_vk_asset_staging_repair_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanAssetStaging: Qualcomm asset staging repair armed; mapped staging memory is flushed before RGBA8 buffer-to-image uploads and logical RGBA bytes are fingerprinted without modifying assets\n");
    }
    uint64_t serial = g_db_vk_asset_staging_hashes + g_db_vk_asset_staging_flush_attempts;
    bool interesting = dstSummary->width <= 128u && dstSummary->height <= 128u;
    if (interesting && (serial <= 128u || (serial % 1024u) == 0u)) {
        fprintf(stderr,
                "DroidBridgeVulkanAssetStaging: upload image=%llu size=%ux%u srcBuffer=%llu memory=%llu bindOffset=%llu copyOffset=%llu rowLength=%u imageHeight=%u extent=%ux%u flush=%s hash=%s%016llx rgbNonZero=%llu/%llu alphaNonZero=%llu/%llu\n",
                (unsigned long long)dstSummary->image,
                dstSummary->width, dstSummary->height,
                (unsigned long long)srcBuffer,
                (unsigned long long)memory,
                (unsigned long long)bindingOffset,
                (unsigned long long)r->bufferOffset,
                r->bufferRowLength, r->bufferImageHeight,
                r->imageExtent.width, r->imageExtent.height,
                flushAttempted ? (flushSucceeded ? "ok" : "failed") : "unavailable",
                hashed ? "" : "unavailable/",
                (unsigned long long)(hashed ? hash : 0u),
                (unsigned long long)rgbNonZeroPixels,
                (unsigned long long)sampledPixels,
                (unsigned long long)alphaNonZeroPixels,
                (unsigned long long)sampledPixels);
        fflush(stderr);
    }
    return flushSucceeded || hashed;
}

static bool db_vk_sprite_upload_layout_candidate(
        const db_vk_image_summary* summary) {
    if (summary == NULL) return false;
    const uint32_t required = DB_VK_IMAGE_USAGE_TRANSFER_DST_BIT | DB_VK_IMAGE_USAGE_SAMPLED_BIT;
    return summary->format == 37
            && summary->mipLevels == 1u
            && summary->arrayLayers == 1u
            && summary->width > 0u && summary->height > 0u
            && summary->width <= 128u && summary->height <= 128u
            && (summary->usage & required) == required
            && (summary->usage & DB_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0u;
}

static bool db_vk_transition_sprite_upload_layout(
        DB_VkCommandBuffer commandBuffer,
        DB_VkImage image,
        int32_t oldLayout,
        int32_t newLayout,
        bool enableFlag,
        bool reopen) {
    if (!db_vk_sprite_upload_layout_repair_enabled()
            || image == 0u
            || oldLayout == newLayout
            || g_db_real_vkCmdPipelineBarrier2 == NULL) {
        return false;
    }

    db_vk_image_summary summary;
    memset(&summary, 0, sizeof(summary));
    if (!db_vk_lookup_image_summary(image, &summary)
            || !db_vk_sprite_upload_layout_candidate(&summary)) {
        return false;
    }

    DB_VkImageMemoryBarrier2 imageBarrier;
    memset(&imageBarrier, 0, sizeof(imageBarrier));
    imageBarrier.sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
    imageBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    imageBarrier.srcAccessMask = reopen
            ? DB_VK_ACCESS_2_MEMORY_READ_BIT
            : DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
    imageBarrier.dstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    imageBarrier.dstAccessMask = reopen
            ? DB_VK_ACCESS_2_MEMORY_WRITE_BIT
            : DB_VK_ACCESS_2_MEMORY_READ_BIT;
    imageBarrier.oldLayout = oldLayout;
    imageBarrier.newLayout = newLayout;
    imageBarrier.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.image = image;
    imageBarrier.subresourceRange.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    imageBarrier.subresourceRange.baseMipLevel = 0u;
    imageBarrier.subresourceRange.levelCount = 1u;
    imageBarrier.subresourceRange.baseArrayLayer = 0u;
    imageBarrier.subresourceRange.layerCount = 1u;

    DB_VkDependencyInfo dependency;
    memset(&dependency, 0, sizeof(dependency));
    dependency.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
    dependency.imageMemoryBarrierCount = 1u;
    dependency.pImageMemoryBarriers = &imageBarrier;
    g_db_real_vkCmdPipelineBarrier2(commandBuffer, &dependency);

    db_vk_set_sprite_upload_shader_read_layout_repair(image, enableFlag);
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(image);
    if (record != NULL) {
        ++record->barrierCalls;
        record->lastOldLayout = oldLayout;
        record->lastNewLayout = newLayout;
        record->lastSrcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        record->lastSrcAccessMask = imageBarrier.srcAccessMask;
        record->lastDstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        record->lastDstAccessMask = imageBarrier.dstAccessMask;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    uint64_t serial = reopen
            ? ++g_db_vk_sprite_upload_layout_reopens
            : ++g_db_vk_sprite_upload_layout_transitions;
    if (!g_db_vk_sprite_upload_layout_repair_logged) {
        g_db_vk_sprite_upload_layout_repair_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanSpriteUploadRepair: uploaded sprite shader-read repair armed; eligible RGBA8 single-mip <=128x128 TRANSFER_DST|SAMPLED images transition immediately after upload and later descriptors are matched to SHADER_READ_ONLY\n");
    }
    if (serial <= 96u || (serial % 1024u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanSpriteUploadRepair: %s #%llu image=%llu size=%ux%u %s(%d)->%s(%d) uploadBuffer=%llu offset=%llu rowLength=%u imageHeight=%u extent=%ux%ux%u\n",
                reopen ? "reopen" : "transition",
                (unsigned long long)serial,
                (unsigned long long)image,
                summary.width, summary.height,
                db_vk_layout_name(oldLayout), oldLayout,
                db_vk_layout_name(newLayout), newLayout,
                (unsigned long long)summary.lastUploadBuffer,
                (unsigned long long)summary.lastUploadBufferOffset,
                summary.lastUploadBufferRowLength,
                summary.lastUploadBufferImageHeight,
                summary.lastUploadWidth,
                summary.lastUploadHeight,
                summary.lastUploadDepth);
        fflush(stderr);
    }
    return true;
}

static void db_vk_record_buffer_upload_metadata(
        DB_VkImage dstImage,
        DB_VkBuffer srcBuffer,
        int32_t dstImageLayout,
        uint32_t regionCount,
        const DB_VkBufferImageCopy* regions) {
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(dstImage);
    if (record != NULL) {
        ++record->bufferUploadCalls;
        record->lastUploadBuffer = srcBuffer;
        record->lastUploadLayout = dstImageLayout;
        if (regions != NULL && regionCount > 0u) {
            const DB_VkBufferImageCopy* region = &regions[0];
            record->lastUploadBufferOffset = region->bufferOffset;
            record->lastUploadBufferRowLength = region->bufferRowLength;
            record->lastUploadBufferImageHeight = region->bufferImageHeight;
            record->lastUploadWidth = region->imageExtent.width;
            record->lastUploadHeight = region->imageExtent.height;
            record->lastUploadDepth = region->imageExtent.depth;
            record->lastUploadMipLevel = region->imageSubresource.mipLevel;
            record->lastUploadBaseArrayLayer = region->imageSubresource.baseArrayLayer;
            record->lastUploadLayerCount = region->imageSubresource.layerCount;
        }
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
}

static bool db_vk_rewrite_sprite_upload_descriptor_layouts(
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites,
        DB_VkWriteDescriptorSet** rewrittenWritesOut,
        DB_VkDescriptorImageInfo** rewrittenImageInfosOut) {
    if (rewrittenWritesOut != NULL) *rewrittenWritesOut = NULL;
    if (rewrittenImageInfosOut != NULL) *rewrittenImageInfosOut = NULL;
    if (!db_vk_sprite_upload_layout_repair_enabled()
            || descriptorWriteCount == 0u
            || descriptorWrites == NULL
            || rewrittenWritesOut == NULL
            || rewrittenImageInfosOut == NULL) {
        return false;
    }

    uint32_t totalImageInfos = 0u;
    bool needsRewrite = false;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (db_vk_payload_kind(write->descriptorType) != DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                || write->pImageInfo == NULL) {
            continue;
        }
        totalImageInfos += write->descriptorCount;
        for (uint32_t j = 0u; j < write->descriptorCount; ++j) {
            if (db_vk_view_has_sprite_upload_shader_read_layout_repair(
                    write->pImageInfo[j].imageView, NULL)) {
                needsRewrite = true;
            }
        }
    }
    if (!needsRewrite || totalImageInfos == 0u) return false;

    DB_VkWriteDescriptorSet* rewrittenWrites = (DB_VkWriteDescriptorSet*)calloc(
            descriptorWriteCount, sizeof(*rewrittenWrites));
    DB_VkDescriptorImageInfo* rewrittenInfos = (DB_VkDescriptorImageInfo*)calloc(
            totalImageInfos, sizeof(*rewrittenInfos));
    if (rewrittenWrites == NULL || rewrittenInfos == NULL) {
        free(rewrittenWrites);
        free(rewrittenInfos);
        return false;
    }
    memcpy(rewrittenWrites, descriptorWrites,
           descriptorWriteCount * sizeof(*rewrittenWrites));

    uint32_t cursor = 0u;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* sourceWrite = &descriptorWrites[i];
        if (db_vk_payload_kind(sourceWrite->descriptorType) != DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                || sourceWrite->pImageInfo == NULL) {
            continue;
        }
        DB_VkDescriptorImageInfo* dstInfos = &rewrittenInfos[cursor];
        memcpy(dstInfos, sourceWrite->pImageInfo,
               sourceWrite->descriptorCount * sizeof(*dstInfos));
        rewrittenWrites[i].pImageInfo = dstInfos;
        for (uint32_t j = 0u; j < sourceWrite->descriptorCount; ++j) {
            db_vk_image_summary summary;
            memset(&summary, 0, sizeof(summary));
            if (!db_vk_view_has_sprite_upload_shader_read_layout_repair(
                    dstInfos[j].imageView, &summary)) {
                continue;
            }
            int32_t oldLayout = dstInfos[j].imageLayout;
            dstInfos[j].imageLayout = DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            uint64_t serial = ++g_db_vk_sprite_upload_layout_descriptor_rewrites;
            if (serial <= 96u || (serial % 8192u) == 0u) {
                fprintf(stderr,
                        "DroidBridgeVulkanSpriteUploadRepair: descriptor #%llu binding=%u view=%llu image=%llu size=%ux%u %s(%d)->SHADER_READ_ONLY(5) uploadOffset=%llu rowLength=%u imageHeight=%u\n",
                        (unsigned long long)serial,
                        sourceWrite->dstBinding,
                        (unsigned long long)dstInfos[j].imageView,
                        (unsigned long long)summary.image,
                        summary.width, summary.height,
                        db_vk_layout_name(oldLayout), oldLayout,
                        (unsigned long long)summary.lastUploadBufferOffset,
                        summary.lastUploadBufferRowLength,
                        summary.lastUploadBufferImageHeight);
                fflush(stderr);
            }
        }
        cursor += sourceWrite->descriptorCount;
    }

    *rewrittenWritesOut = rewrittenWrites;
    *rewrittenImageInfosOut = rewrittenInfos;
    return true;
}

static bool db_vk_lookup_sampler_summary(DB_VkSampler sampler, db_vk_sampler_summary* out) {
    if (out == NULL) return false;
    memset(out, 0, sizeof(*out));
    if (sampler == 0u) return false;
    pthread_mutex_lock(&g_db_vk_sampler_mutex);
    for (db_vk_sampler_record* record = g_db_vk_samplers; record != NULL; record = record->next) {
        if (record->sampler == sampler) {
            out->found = true;
            out->magFilter = record->magFilter;
            out->minFilter = record->minFilter;
            out->mipmapMode = record->mipmapMode;
            out->addressModeU = record->addressModeU;
            out->addressModeV = record->addressModeV;
            out->addressModeW = record->addressModeW;
            out->anisotropyEnable = record->anisotropyEnable;
            out->maxAnisotropy = record->maxAnisotropy;
            out->compareEnable = record->compareEnable;
            out->compareOp = record->compareOp;
            out->minLod = record->minLod;
            out->maxLod = record->maxLod;
            out->unnormalizedCoordinates = record->unnormalizedCoordinates;
            break;
        }
    }
    pthread_mutex_unlock(&g_db_vk_sampler_mutex);
    return out->found;
}

static DB_VkResult db_vk_create_sampler(
        DB_VkDevice device, const DB_VkSamplerCreateInfo* createInfo,
        const void* allocator, DB_VkSampler* sampler) {
    DB_PFN_vkCreateSampler real = g_db_real_vkCreateSampler;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, createInfo, allocator, sampler);
    if (!db_vk_entity_aux_diagnostics_enabled() || result != DB_VK_SUCCESS
            || createInfo == NULL || sampler == NULL || *sampler == 0u) return result;
    db_vk_sampler_record* record = (db_vk_sampler_record*)calloc(1u, sizeof(*record));
    if (record != NULL) {
        record->sampler = *sampler;
        record->magFilter = createInfo->magFilter;
        record->minFilter = createInfo->minFilter;
        record->mipmapMode = createInfo->mipmapMode;
        record->addressModeU = createInfo->addressModeU;
        record->addressModeV = createInfo->addressModeV;
        record->addressModeW = createInfo->addressModeW;
        record->anisotropyEnable = createInfo->anisotropyEnable;
        record->maxAnisotropy = createInfo->maxAnisotropy;
        record->compareEnable = createInfo->compareEnable;
        record->compareOp = createInfo->compareOp;
        record->minLod = createInfo->minLod;
        record->maxLod = createInfo->maxLod;
        record->unnormalizedCoordinates = createInfo->unnormalizedCoordinates;
        pthread_mutex_lock(&g_db_vk_sampler_mutex);
        record->next = g_db_vk_samplers;
        g_db_vk_samplers = record;
        pthread_mutex_unlock(&g_db_vk_sampler_mutex);
    }
    return result;
}

static void db_vk_destroy_sampler(DB_VkDevice device, DB_VkSampler sampler, const void* allocator) {
    DB_PFN_vkDestroySampler real = g_db_real_vkDestroySampler;
    if (db_vk_entity_aux_diagnostics_enabled() && sampler != 0u) {
        pthread_mutex_lock(&g_db_vk_sampler_mutex);
        db_vk_sampler_record** cursor = &g_db_vk_samplers;
        while (*cursor != NULL) {
            if ((*cursor)->sampler == sampler) {
                db_vk_sampler_record* dead = *cursor;
                *cursor = dead->next;
                free(dead);
                break;
            }
            cursor = &(*cursor)->next;
        }
        pthread_mutex_unlock(&g_db_vk_sampler_mutex);
    }
    if (real != NULL) real(device, sampler, allocator);
}

static bool db_vk_is_sampled_offscreen_target(const db_vk_image_summary* summary) {
    if (summary == NULL) return false;
    uint32_t required = DB_VK_IMAGE_USAGE_SAMPLED_BIT | DB_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    if ((summary->usage & required) != required) return false;
    if (summary->width < 64u || summary->height < 32u) return false;
    // Avoid the main 16:9 presentation-sized color target. Atlas and item/model
    // targets are square, 2:1, 4:1, etc., and remain eligible.
    if (summary->width >= 1280u && summary->height >= 720u) {
        uint64_t lhs = (uint64_t)summary->width * 9u;
        uint64_t rhs = (uint64_t)summary->height * 16u;
        uint64_t diff = lhs > rhs ? lhs - rhs : rhs - lhs;
        if (diff <= 32u) return false;
    }
    return true;
}

static bool db_vk_image_summary_is_diagnostic_target(
        const db_vk_image_summary* summary) {
    if (summary == NULL) return false;
    // Minecraft builds many tiny temporary sprite images while stitching atlases.
    // Logging every one produced multi-megabyte logs and obscured the actual
    // atlas resources. Keep diagnostics focused on atlas/render-sized images.
    return summary->width >= 256u || summary->height >= 256u;
}

static bool db_vk_mark_image_copy_log(
        DB_VkImage image,
        db_vk_image_summary* out,
        uint32_t maxLogs) {
    bool shouldLog = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(image);
    if (record != NULL && record->copyLogCount < maxLogs) {
        ++record->copyLogCount;
        if (out != NULL) {
            memset(out, 0, sizeof(*out));
            db_vk_fill_image_summary_locked(record, out);
        }
        shouldLog = true;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return shouldLog;
}

static bool db_vk_mark_image_barrier_log(
        DB_VkImage image,
        db_vk_image_summary* out,
        uint32_t maxLogs) {
    bool shouldLog = false;
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(image);
    if (record != NULL && record->barrierLogCount < maxLogs) {
        ++record->barrierLogCount;
        if (out != NULL) {
            memset(out, 0, sizeof(*out));
            db_vk_fill_image_summary_locked(record, out);
        }
        shouldLog = true;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);
    return shouldLog;
}

static void db_vk_log_descriptor_image_once(
        DB_VkImageView view,
        int32_t descriptorLayout,
        uint32_t binding,
        uint32_t arrayElement,
        DB_VkDescriptorType descriptorType) {
    if (!db_vk_image_diagnostics_enabled() || view == 0u) return;

    bool shouldLog = false;
    int32_t viewFormat = 0;
    db_vk_image_summary summary;
    memset(&summary, 0, sizeof(summary));

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_view_record* viewRecord = db_vk_find_image_view_locked(view);
    if (viewRecord != NULL && !viewRecord->descriptorLogged) {
        db_vk_image_record* imageRecord = db_vk_find_image_locked(viewRecord->image);
        if (imageRecord != NULL) {
            viewRecord->descriptorLogged = true;
            viewFormat = viewRecord->format;
            db_vk_fill_image_summary_locked(imageRecord, &summary);
            shouldLog = db_vk_image_summary_is_diagnostic_target(&summary);
        }
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    if (shouldLog) {
        fprintf(stderr,
                "DroidBridgeVulkanImage: descriptor view=%llu image=%llu size=%ux%ux%u mips=%u layers=%u imageFormat=%d viewFormat=%d usage=0x%x layout=%s(%d) binding=%u array=%u type=%d\n",
                (unsigned long long)view,
                (unsigned long long)summary.image,
                summary.width,
                summary.height,
                summary.depth,
                summary.mipLevels,
                summary.arrayLayers,
                summary.format,
                viewFormat,
                summary.usage,
                db_vk_layout_name(descriptorLayout),
                descriptorLayout,
                binding,
                arrayElement,
                descriptorType);
        fprintf(stderr,
                "DroidBridgeVulkanAtlasHistory: image=%llu size=%ux%u usage=0x%x writes=buffer:%llu copy:%llu blit:%llu render:%llu reads=copy:%llu blit:%llu barriers=%llu lastRender=load%d/store%d/clear%.3f,%.3f,%.3f,%.3f lastBarrier=%s(%d)->%s(%d) srcStage=0x%llx srcAccess=0x%llx dstStage=0x%llx dstAccess=0x%llx\n",
                (unsigned long long)summary.image,
                summary.width, summary.height, summary.usage,
                (unsigned long long)summary.bufferUploadCalls,
                (unsigned long long)summary.imageCopyWriteCalls,
                (unsigned long long)summary.imageBlitWriteCalls,
                (unsigned long long)summary.renderTargetBeginCalls,
                (unsigned long long)summary.imageCopyReadCalls,
                (unsigned long long)summary.imageBlitReadCalls,
                (unsigned long long)summary.barrierCalls,
                summary.lastRenderLoadOp, summary.lastRenderStoreOp,
                summary.lastRenderClearColor[0], summary.lastRenderClearColor[1],
                summary.lastRenderClearColor[2], summary.lastRenderClearColor[3],
                db_vk_layout_name(summary.lastOldLayout), summary.lastOldLayout,
                db_vk_layout_name(summary.lastNewLayout), summary.lastNewLayout,
                (unsigned long long)summary.lastSrcStageMask,
                (unsigned long long)summary.lastSrcAccessMask,
                (unsigned long long)summary.lastDstStageMask,
                (unsigned long long)summary.lastDstAccessMask);
        fflush(stderr);
    }
}

static DB_VkResult db_vk_create_image(
        DB_VkDevice device,
        const DB_VkImageCreateInfo* createInfo,
        const void* allocator,
        DB_VkImage* image) {
    DB_PFN_vkCreateImage real = g_db_real_vkCreateImage;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;

    DB_VkImageCreateInfo localCreateInfo;
    const DB_VkImageCreateInfo* effectiveCreateInfo = createInfo;
    bool directComposeTransferSrcAdded = false;
    if (createInfo != NULL && db_vk_atlas_direct_compose_repair_enabled()) {
        const uint32_t uploadSampled =
                DB_VK_IMAGE_USAGE_TRANSFER_DST_BIT | DB_VK_IMAGE_USAGE_SAMPLED_BIT;
        if ((createInfo->usage & uploadSampled) == uploadSampled
                && (createInfo->usage & DB_VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0u
                && (createInfo->usage & DB_VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0u
                && createInfo->format == 37
                && createInfo->extent.depth == 1u
                && createInfo->arrayLayers == 1u) {
            localCreateInfo = *createInfo;
            localCreateInfo.usage |= DB_VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            effectiveCreateInfo = &localCreateInfo;
            directComposeTransferSrcAdded = true;
        }
    }

    DB_VkResult result = real(device, effectiveCreateInfo, allocator, image);
    if (!db_vk_image_diagnostics_enabled()
            || result != DB_VK_SUCCESS
            || effectiveCreateInfo == NULL
            || image == NULL
            || *image == 0u) {
        return result;
    }

    db_vk_image_record* record = (db_vk_image_record*)calloc(1u, sizeof(*record));
    if (record != NULL) {
        record->image = *image;
        record->width = effectiveCreateInfo->extent.width;
        record->height = effectiveCreateInfo->extent.height;
        record->depth = effectiveCreateInfo->extent.depth;
        record->mipLevels = effectiveCreateInfo->mipLevels;
        record->arrayLayers = effectiveCreateInfo->arrayLayers;
        record->format = effectiveCreateInfo->format;
        record->usage = effectiveCreateInfo->usage;
        pthread_mutex_lock(&g_db_vk_image_mutex);
        record->next = g_db_vk_images;
        g_db_vk_images = record;
        pthread_mutex_unlock(&g_db_vk_image_mutex);
    }

    if (directComposeTransferSrcAdded) {
        uint64_t enabledIndex = ++g_db_vk_atlas_direct_compose_transfer_src_images;
        if (enabledIndex <= 24u || (enabledIndex % 512u) == 0u) {
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasComposeV5: source-transfer-src V5 #%llu image=%llu size=%ux%u usage=0x%x->0x%x\n",
                    (unsigned long long)enabledIndex,
                    (unsigned long long)*image,
                    createInfo->extent.width, createInfo->extent.height,
                    createInfo->usage, effectiveCreateInfo->usage);
            fflush(stderr);
        }
    }

    ++g_db_vk_created_images;
    db_vk_image_summary createdSummary;
    memset(&createdSummary, 0, sizeof(createdSummary));
    createdSummary.width = effectiveCreateInfo->extent.width;
    createdSummary.height = effectiveCreateInfo->extent.height;
    if (g_db_vk_created_images <= 24u
            || db_vk_image_summary_is_diagnostic_target(&createdSummary)
            || (g_db_vk_created_images % 512u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanImage: create #%llu image=%llu size=%ux%ux%u mips=%u layers=%u format=%d usage=0x%x initialLayout=%s(%d)\n",
                (unsigned long long)g_db_vk_created_images,
                (unsigned long long)*image,
                effectiveCreateInfo->extent.width,
                effectiveCreateInfo->extent.height,
                effectiveCreateInfo->extent.depth,
                effectiveCreateInfo->mipLevels,
                effectiveCreateInfo->arrayLayers,
                effectiveCreateInfo->format,
                effectiveCreateInfo->usage,
                db_vk_layout_name(effectiveCreateInfo->initialLayout),
                effectiveCreateInfo->initialLayout);
        fflush(stderr);
    }
    return result;
}

static void db_vk_destroy_image(
        DB_VkDevice device,
        DB_VkImage image,
        const void* allocator) {
    if (db_vk_image_diagnostics_enabled() && image != 0u) {
        pthread_mutex_lock(&g_db_vk_image_mutex);
        db_vk_image_record** cursor = &g_db_vk_images;
        while (*cursor != NULL) {
            if ((*cursor)->image == image) {
                db_vk_image_record* dead = *cursor;
                *cursor = dead->next;
                free(dead);
                break;
            }
            cursor = &(*cursor)->next;
        }
        pthread_mutex_unlock(&g_db_vk_image_mutex);
    }
    if (g_db_real_vkDestroyImage != NULL) {
        g_db_real_vkDestroyImage(device, image, allocator);
    }
}

static DB_VkResult db_vk_create_image_view(
        DB_VkDevice device,
        const DB_VkImageViewCreateInfo* createInfo,
        const void* allocator,
        DB_VkImageView* view) {
    DB_PFN_vkCreateImageView real = g_db_real_vkCreateImageView;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, createInfo, allocator, view);
    if (!db_vk_image_diagnostics_enabled()
            || result != DB_VK_SUCCESS
            || createInfo == NULL
            || view == NULL
            || *view == 0u) {
        return result;
    }

    db_vk_image_view_record* record =
            (db_vk_image_view_record*)calloc(1u, sizeof(*record));
    if (record != NULL) {
        record->view = *view;
        record->image = createInfo->image;
        record->format = createInfo->format;
        pthread_mutex_lock(&g_db_vk_image_mutex);
        record->next = g_db_vk_image_views;
        g_db_vk_image_views = record;
        pthread_mutex_unlock(&g_db_vk_image_mutex);
    }

    ++g_db_vk_created_image_views;
    db_vk_image_summary summary;
    bool haveSummary = db_vk_lookup_image_summary(createInfo->image, &summary);
    if (g_db_vk_created_image_views <= 24u
            || (haveSummary && db_vk_image_summary_is_diagnostic_target(&summary))
            || (g_db_vk_created_image_views % 512u) == 0u) {
        if (haveSummary) {
            fprintf(stderr,
                    "DroidBridgeVulkanImage: view #%llu view=%llu image=%llu size=%ux%u mips=%u imageFormat=%d viewFormat=%d baseMip=%u levels=%u\n",
                    (unsigned long long)g_db_vk_created_image_views,
                    (unsigned long long)*view,
                    (unsigned long long)createInfo->image,
                    summary.width,
                    summary.height,
                    summary.mipLevels,
                    summary.format,
                    createInfo->format,
                    createInfo->subresourceRange.baseMipLevel,
                    createInfo->subresourceRange.levelCount);
            fflush(stderr);
        }
    }
    return result;
}

static void db_vk_destroy_image_view(
        DB_VkDevice device,
        DB_VkImageView view,
        const void* allocator) {
    if (db_vk_image_diagnostics_enabled() && view != 0u) {
        pthread_mutex_lock(&g_db_vk_image_mutex);
        db_vk_image_view_record** cursor = &g_db_vk_image_views;
        while (*cursor != NULL) {
            if ((*cursor)->view == view) {
                db_vk_image_view_record* dead = *cursor;
                *cursor = dead->next;
                free(dead);
                break;
            }
            cursor = &(*cursor)->next;
        }
        pthread_mutex_unlock(&g_db_vk_image_mutex);
    }
    if (g_db_real_vkDestroyImageView != NULL) {
        g_db_real_vkDestroyImageView(device, view, allocator);
    }
}

static void db_vk_cmd_copy_buffer_to_image(
        DB_VkCommandBuffer commandBuffer,
        DB_VkBuffer srcBuffer,
        DB_VkImage dstImage,
        int32_t dstImageLayout,
        uint32_t regionCount,
        const DB_VkBufferImageCopy* regions) {
    if (db_vk_asset_staging_repair_enabled()) {
        db_vk_image_summary stagingSummary;
        memset(&stagingSummary, 0, sizeof(stagingSummary));
        if (db_vk_lookup_image_summary(dstImage, &stagingSummary)) {
            db_vk_asset_staging_prepare_copy(
                    srcBuffer, &stagingSummary, regionCount, regions);
        }
    }
    bool wasSpriteShaderRead = false;
    if (db_vk_sprite_upload_layout_repair_enabled()) {
        db_vk_image_summary before;
        memset(&before, 0, sizeof(before));
        if (db_vk_lookup_image_summary(dstImage, &before)
                && before.spriteUploadShaderReadLayoutRepair
                && dstImageLayout == DB_VK_IMAGE_LAYOUT_GENERAL) {
            wasSpriteShaderRead = db_vk_transition_sprite_upload_layout(
                    commandBuffer, dstImage,
                    DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    DB_VK_IMAGE_LAYOUT_GENERAL, false, true);
        }
    }
    (void)wasSpriteShaderRead;
    if (g_db_real_vkCmdCopyBufferToImage != NULL) {
        g_db_real_vkCmdCopyBufferToImage(
                commandBuffer, srcBuffer, dstImage, dstImageLayout, regionCount, regions);
    }
    if (!db_vk_image_diagnostics_enabled()) return;

    db_vk_record_buffer_upload_metadata(
            dstImage, srcBuffer, dstImageLayout, regionCount, regions);
    if (db_vk_sprite_upload_layout_repair_enabled()
            && dstImageLayout == DB_VK_IMAGE_LAYOUT_GENERAL) {
        db_vk_transition_sprite_upload_layout(
                commandBuffer, dstImage,
                DB_VK_IMAGE_LAYOUT_GENERAL,
                DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, true, false);
    }

    ++g_db_vk_copy_buffer_to_image_calls;
    db_vk_image_summary summary;
    if (db_vk_mark_image_copy_log(dstImage, &summary, 3u)
            && db_vk_image_summary_is_diagnostic_target(&summary)) {
        uint32_t copyWidth = 0u;
        uint32_t copyHeight = 0u;
        uint32_t copyDepth = 0u;
        uint32_t mip = 0u;
        if (regions != NULL && regionCount > 0u) {
            copyWidth = regions[0].imageExtent.width;
            copyHeight = regions[0].imageExtent.height;
            copyDepth = regions[0].imageExtent.depth;
            mip = regions[0].imageSubresource.mipLevel;
        }
        fprintf(stderr,
                "DroidBridgeVulkanImage: copy #%llu image=%llu size=%ux%u mips=%u layout=%s(%d) regions=%u firstExtent=%ux%ux%u firstMip=%u\n",
                (unsigned long long)g_db_vk_copy_buffer_to_image_calls,
                (unsigned long long)dstImage,
                summary.width,
                summary.height,
                summary.mipLevels,
                db_vk_layout_name(dstImageLayout),
                dstImageLayout,
                regionCount,
                copyWidth,
                copyHeight,
                copyDepth,
                mip);
        fflush(stderr);
    }
}

static void db_vk_cmd_copy_buffer_to_image2(
        DB_VkCommandBuffer commandBuffer,
        const DB_VkCopyBufferToImageInfo2* copyInfo) {
    if (copyInfo != NULL && db_vk_asset_staging_repair_enabled()
            && copyInfo->pRegions != NULL && copyInfo->regionCount > 0u) {
        db_vk_image_summary stagingSummary;
        memset(&stagingSummary, 0, sizeof(stagingSummary));
        if (db_vk_lookup_image_summary(copyInfo->dstImage, &stagingSummary)) {
            DB_VkBufferImageCopy region;
            memset(&region, 0, sizeof(region));
            region.bufferOffset = copyInfo->pRegions[0].bufferOffset;
            region.bufferRowLength = copyInfo->pRegions[0].bufferRowLength;
            region.bufferImageHeight = copyInfo->pRegions[0].bufferImageHeight;
            region.imageSubresource = copyInfo->pRegions[0].imageSubresource;
            region.imageOffset = copyInfo->pRegions[0].imageOffset;
            region.imageExtent = copyInfo->pRegions[0].imageExtent;
            db_vk_asset_staging_prepare_copy(copyInfo->srcBuffer, &stagingSummary, 1u, &region);
        }
    }
    if (copyInfo != NULL && db_vk_sprite_upload_layout_repair_enabled()) {
        db_vk_image_summary before;
        memset(&before, 0, sizeof(before));
        if (db_vk_lookup_image_summary(copyInfo->dstImage, &before)
                && before.spriteUploadShaderReadLayoutRepair
                && copyInfo->dstImageLayout == DB_VK_IMAGE_LAYOUT_GENERAL) {
            db_vk_transition_sprite_upload_layout(
                    commandBuffer, copyInfo->dstImage,
                    DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    DB_VK_IMAGE_LAYOUT_GENERAL, false, true);
        }
    }
    if (g_db_real_vkCmdCopyBufferToImage2 != NULL) {
        g_db_real_vkCmdCopyBufferToImage2(commandBuffer, copyInfo);
    }
    if (!db_vk_image_diagnostics_enabled() || copyInfo == NULL) return;

    db_vk_record_buffer_upload_metadata(
            copyInfo->dstImage, copyInfo->srcBuffer, copyInfo->dstImageLayout, 0u, NULL);
    if (db_vk_sprite_upload_layout_repair_enabled()
            && copyInfo->dstImageLayout == DB_VK_IMAGE_LAYOUT_GENERAL) {
        db_vk_transition_sprite_upload_layout(
                commandBuffer, copyInfo->dstImage,
                DB_VK_IMAGE_LAYOUT_GENERAL,
                DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, true, false);
    }

    ++g_db_vk_copy_buffer_to_image_calls;
    db_vk_image_summary summary;
    if (db_vk_mark_image_copy_log(copyInfo->dstImage, &summary, 3u)
            && db_vk_image_summary_is_diagnostic_target(&summary)) {
        fprintf(stderr,
                "DroidBridgeVulkanImage: copy2 #%llu image=%llu size=%ux%u mips=%u layout=%s(%d) regions=%u\n",
                (unsigned long long)g_db_vk_copy_buffer_to_image_calls,
                (unsigned long long)copyInfo->dstImage,
                summary.width,
                summary.height,
                summary.mipLevels,
                db_vk_layout_name(copyInfo->dstImageLayout),
                copyInfo->dstImageLayout,
                copyInfo->regionCount);
        fflush(stderr);
    }
}

static bool db_vk_should_log_image_transfer_pair(
        DB_VkImage srcImage,
        DB_VkImage dstImage,
        db_vk_image_summary* srcSummary,
        db_vk_image_summary* dstSummary,
        uint32_t maxLogs) {
    bool haveSrc = db_vk_lookup_image_summary(srcImage, srcSummary);
    bool haveDst = db_vk_lookup_image_summary(dstImage, dstSummary);
    bool srcTarget = haveSrc && db_vk_image_summary_is_diagnostic_target(srcSummary);
    bool dstTarget = haveDst && db_vk_image_summary_is_diagnostic_target(dstSummary);
    if (dstTarget) return db_vk_mark_image_copy_log(dstImage, NULL, maxLogs);
    if (srcTarget) return db_vk_mark_image_copy_log(srcImage, NULL, maxLogs);
    return false;
}

static void db_vk_cmd_copy_image(
        DB_VkCommandBuffer commandBuffer,
        DB_VkImage srcImage,
        int32_t srcImageLayout,
        DB_VkImage dstImage,
        int32_t dstImageLayout,
        uint32_t regionCount,
        const DB_VkImageCopy* regions) {
    if (g_db_real_vkCmdCopyImage != NULL) {
        g_db_real_vkCmdCopyImage(commandBuffer, srcImage, srcImageLayout,
                dstImage, dstImageLayout, regionCount, regions);
    }
    if (!db_vk_image_diagnostics_enabled()) return;

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* copySrcRecord = db_vk_find_image_locked(srcImage);
    db_vk_image_record* copyDstRecord = db_vk_find_image_locked(dstImage);
    if (copySrcRecord != NULL) ++copySrcRecord->imageCopyReadCalls;
    if (copyDstRecord != NULL) ++copyDstRecord->imageCopyWriteCalls;
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    ++g_db_vk_copy_image_calls;
    db_vk_image_summary srcSummary;
    db_vk_image_summary dstSummary;
    memset(&srcSummary, 0, sizeof(srcSummary));
    memset(&dstSummary, 0, sizeof(dstSummary));
    if (!db_vk_should_log_image_transfer_pair(
            srcImage, dstImage, &srcSummary, &dstSummary, 12u)) return;

    uint32_t width = 0u;
    uint32_t height = 0u;
    uint32_t depth = 0u;
    uint32_t srcMip = 0u;
    uint32_t dstMip = 0u;
    if (regions != NULL && regionCount > 0u) {
        width = regions[0].extent.width;
        height = regions[0].extent.height;
        depth = regions[0].extent.depth;
        srcMip = regions[0].srcSubresource.mipLevel;
        dstMip = regions[0].dstSubresource.mipLevel;
    }
    fprintf(stderr,
            "DroidBridgeVulkanImage: copyImage #%llu src=%llu(%ux%u,%s/%d,mip=%u) dst=%llu(%ux%u,%s/%d,mip=%u) regions=%u firstExtent=%ux%ux%u\n",
            (unsigned long long)g_db_vk_copy_image_calls,
            (unsigned long long)srcImage,
            srcSummary.width,
            srcSummary.height,
            db_vk_layout_name(srcImageLayout),
            srcImageLayout,
            srcMip,
            (unsigned long long)dstImage,
            dstSummary.width,
            dstSummary.height,
            db_vk_layout_name(dstImageLayout),
            dstImageLayout,
            dstMip,
            regionCount,
            width,
            height,
            depth);
    fflush(stderr);
}

static void db_vk_cmd_copy_image2(
        DB_VkCommandBuffer commandBuffer,
        const DB_VkCopyImageInfo2* copyInfo) {
    if (g_db_real_vkCmdCopyImage2 != NULL) {
        g_db_real_vkCmdCopyImage2(commandBuffer, copyInfo);
    }
    if (!db_vk_image_diagnostics_enabled() || copyInfo == NULL) return;

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* copySrcRecord = db_vk_find_image_locked(copyInfo->srcImage);
    db_vk_image_record* copyDstRecord = db_vk_find_image_locked(copyInfo->dstImage);
    if (copySrcRecord != NULL) ++copySrcRecord->imageCopyReadCalls;
    if (copyDstRecord != NULL) ++copyDstRecord->imageCopyWriteCalls;
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    ++g_db_vk_copy_image_calls;
    db_vk_image_summary srcSummary;
    db_vk_image_summary dstSummary;
    memset(&srcSummary, 0, sizeof(srcSummary));
    memset(&dstSummary, 0, sizeof(dstSummary));
    if (!db_vk_should_log_image_transfer_pair(
            copyInfo->srcImage, copyInfo->dstImage, &srcSummary, &dstSummary, 12u)) return;
    fprintf(stderr,
            "DroidBridgeVulkanImage: copyImage2 #%llu src=%llu(%ux%u,%s/%d) dst=%llu(%ux%u,%s/%d) regions=%u\n",
            (unsigned long long)g_db_vk_copy_image_calls,
            (unsigned long long)copyInfo->srcImage,
            srcSummary.width,
            srcSummary.height,
            db_vk_layout_name(copyInfo->srcImageLayout),
            copyInfo->srcImageLayout,
            (unsigned long long)copyInfo->dstImage,
            dstSummary.width,
            dstSummary.height,
            db_vk_layout_name(copyInfo->dstImageLayout),
            copyInfo->dstImageLayout,
            copyInfo->regionCount);
    fflush(stderr);
}

static void db_vk_cmd_blit_image(
        DB_VkCommandBuffer commandBuffer,
        DB_VkImage srcImage,
        int32_t srcImageLayout,
        DB_VkImage dstImage,
        int32_t dstImageLayout,
        uint32_t regionCount,
        const DB_VkImageBlit* regions,
        int32_t filter) {
    if (g_db_real_vkCmdBlitImage != NULL) {
        g_db_real_vkCmdBlitImage(commandBuffer, srcImage, srcImageLayout,
                dstImage, dstImageLayout, regionCount, regions, filter);
    }
    if (!db_vk_image_diagnostics_enabled()) return;

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* blitSrcRecord = db_vk_find_image_locked(srcImage);
    db_vk_image_record* blitDstRecord = db_vk_find_image_locked(dstImage);
    if (blitSrcRecord != NULL) ++blitSrcRecord->imageBlitReadCalls;
    if (blitDstRecord != NULL) ++blitDstRecord->imageBlitWriteCalls;
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    ++g_db_vk_blit_image_calls;
    db_vk_image_summary srcSummary;
    db_vk_image_summary dstSummary;
    memset(&srcSummary, 0, sizeof(srcSummary));
    memset(&dstSummary, 0, sizeof(dstSummary));
    if (!db_vk_should_log_image_transfer_pair(
            srcImage, dstImage, &srcSummary, &dstSummary, 12u)) return;
    fprintf(stderr,
            "DroidBridgeVulkanImage: blitImage #%llu src=%llu(%ux%u,%s/%d) dst=%llu(%ux%u,%s/%d) regions=%u filter=%d\n",
            (unsigned long long)g_db_vk_blit_image_calls,
            (unsigned long long)srcImage,
            srcSummary.width,
            srcSummary.height,
            db_vk_layout_name(srcImageLayout),
            srcImageLayout,
            (unsigned long long)dstImage,
            dstSummary.width,
            dstSummary.height,
            db_vk_layout_name(dstImageLayout),
            dstImageLayout,
            regionCount,
            filter);
    fflush(stderr);
}

static void db_vk_cmd_blit_image2(
        DB_VkCommandBuffer commandBuffer,
        const DB_VkBlitImageInfo2* blitInfo) {
    if (g_db_real_vkCmdBlitImage2 != NULL) {
        g_db_real_vkCmdBlitImage2(commandBuffer, blitInfo);
    }
    if (!db_vk_image_diagnostics_enabled() || blitInfo == NULL) return;

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* blitSrcRecord = db_vk_find_image_locked(blitInfo->srcImage);
    db_vk_image_record* blitDstRecord = db_vk_find_image_locked(blitInfo->dstImage);
    if (blitSrcRecord != NULL) ++blitSrcRecord->imageBlitReadCalls;
    if (blitDstRecord != NULL) ++blitDstRecord->imageBlitWriteCalls;
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    ++g_db_vk_blit_image_calls;
    db_vk_image_summary srcSummary;
    db_vk_image_summary dstSummary;
    memset(&srcSummary, 0, sizeof(srcSummary));
    memset(&dstSummary, 0, sizeof(dstSummary));
    if (!db_vk_should_log_image_transfer_pair(
            blitInfo->srcImage, blitInfo->dstImage, &srcSummary, &dstSummary, 12u)) return;
    fprintf(stderr,
            "DroidBridgeVulkanImage: blitImage2 #%llu src=%llu(%ux%u,%s/%d) dst=%llu(%ux%u,%s/%d) regions=%u filter=%d\n",
            (unsigned long long)g_db_vk_blit_image_calls,
            (unsigned long long)blitInfo->srcImage,
            srcSummary.width,
            srcSummary.height,
            db_vk_layout_name(blitInfo->srcImageLayout),
            blitInfo->srcImageLayout,
            (unsigned long long)blitInfo->dstImage,
            dstSummary.width,
            dstSummary.height,
            db_vk_layout_name(blitInfo->dstImageLayout),
            blitInfo->dstImageLayout,
            blitInfo->regionCount,
            blitInfo->filter);
    fflush(stderr);
}

static void db_vk_cmd_pipeline_barrier(
        DB_VkCommandBuffer commandBuffer,
        uint32_t srcStageMask,
        uint32_t dstStageMask,
        uint32_t dependencyFlags,
        uint32_t memoryBarrierCount,
        const void* memoryBarriers,
        uint32_t bufferMemoryBarrierCount,
        const void* bufferMemoryBarriers,
        uint32_t imageMemoryBarrierCount,
        const DB_VkImageMemoryBarrier* imageMemoryBarriers) {
    if (g_db_real_vkCmdPipelineBarrier != NULL) {
        g_db_real_vkCmdPipelineBarrier(commandBuffer, srcStageMask, dstStageMask,
                dependencyFlags, memoryBarrierCount, memoryBarriers,
                bufferMemoryBarrierCount, bufferMemoryBarriers,
                imageMemoryBarrierCount, imageMemoryBarriers);
    }
    if (!db_vk_image_diagnostics_enabled() || imageMemoryBarriers == NULL) return;

    ++g_db_vk_pipeline_barrier_calls;
    for (uint32_t i = 0u; i < imageMemoryBarrierCount; ++i) {
        const DB_VkImageMemoryBarrier* barrier = &imageMemoryBarriers[i];
        pthread_mutex_lock(&g_db_vk_image_mutex);
        db_vk_image_record* barrierRecord = db_vk_find_image_locked(barrier->image);
        if (barrierRecord != NULL) {
            ++barrierRecord->barrierCalls;
            barrierRecord->lastOldLayout = barrier->oldLayout;
            barrierRecord->lastNewLayout = barrier->newLayout;
            barrierRecord->lastSrcStageMask = srcStageMask;
            barrierRecord->lastSrcAccessMask = barrier->srcAccessMask;
            barrierRecord->lastDstStageMask = dstStageMask;
            barrierRecord->lastDstAccessMask = barrier->dstAccessMask;
        }
        pthread_mutex_unlock(&g_db_vk_image_mutex);
        db_vk_image_summary summary;
        if (!db_vk_mark_image_barrier_log(barrier->image, &summary, 8u)
                || !db_vk_image_summary_is_diagnostic_target(&summary)) {
            continue;
        }
        fprintf(stderr,
                "DroidBridgeVulkanImage: barrier call=%llu image=%llu size=%ux%u mips=%u %s(%d)->%s(%d) srcStage=0x%x srcAccess=0x%x dstStage=0x%x dstAccess=0x%x baseMip=%u levels=%u\n",
                (unsigned long long)g_db_vk_pipeline_barrier_calls,
                (unsigned long long)barrier->image,
                summary.width,
                summary.height,
                summary.mipLevels,
                db_vk_layout_name(barrier->oldLayout),
                barrier->oldLayout,
                db_vk_layout_name(barrier->newLayout),
                barrier->newLayout,
                srcStageMask,
                barrier->srcAccessMask,
                dstStageMask,
                barrier->dstAccessMask,
                barrier->subresourceRange.baseMipLevel,
                barrier->subresourceRange.levelCount);
        fflush(stderr);
    }
}

static void db_vk_cmd_pipeline_barrier2(
        DB_VkCommandBuffer commandBuffer,
        const DB_VkDependencyInfo* dependencyInfo) {
    if (g_db_real_vkCmdPipelineBarrier2 != NULL) {
        g_db_real_vkCmdPipelineBarrier2(commandBuffer, dependencyInfo);
    }
    if (!db_vk_image_diagnostics_enabled()
            || dependencyInfo == NULL
            || dependencyInfo->pImageMemoryBarriers == NULL) {
        return;
    }

    ++g_db_vk_pipeline_barrier2_calls;
    for (uint32_t i = 0u; i < dependencyInfo->imageMemoryBarrierCount; ++i) {
        const DB_VkImageMemoryBarrier2* barrier = &dependencyInfo->pImageMemoryBarriers[i];
        pthread_mutex_lock(&g_db_vk_image_mutex);
        db_vk_image_record* barrierRecord = db_vk_find_image_locked(barrier->image);
        if (barrierRecord != NULL) {
            ++barrierRecord->barrierCalls;
            barrierRecord->lastOldLayout = barrier->oldLayout;
            barrierRecord->lastNewLayout = barrier->newLayout;
            barrierRecord->lastSrcStageMask = barrier->srcStageMask;
            barrierRecord->lastSrcAccessMask = barrier->srcAccessMask;
            barrierRecord->lastDstStageMask = barrier->dstStageMask;
            barrierRecord->lastDstAccessMask = barrier->dstAccessMask;
        }
        pthread_mutex_unlock(&g_db_vk_image_mutex);
        db_vk_image_summary summary;
        if (!db_vk_mark_image_barrier_log(barrier->image, &summary, 8u)
                || !db_vk_image_summary_is_diagnostic_target(&summary)) {
            continue;
        }
        fprintf(stderr,
                "DroidBridgeVulkanImage: barrier2 call=%llu image=%llu size=%ux%u mips=%u %s(%d)->%s(%d) srcStage=0x%llx srcAccess=0x%llx dstStage=0x%llx dstAccess=0x%llx baseMip=%u levels=%u\n",
                (unsigned long long)g_db_vk_pipeline_barrier2_calls,
                (unsigned long long)barrier->image,
                summary.width,
                summary.height,
                summary.mipLevels,
                db_vk_layout_name(barrier->oldLayout),
                barrier->oldLayout,
                db_vk_layout_name(barrier->newLayout),
                barrier->newLayout,
                (unsigned long long)barrier->srcStageMask,
                (unsigned long long)barrier->srcAccessMask,
                (unsigned long long)barrier->dstStageMask,
                (unsigned long long)barrier->dstAccessMask,
                barrier->subresourceRange.baseMipLevel,
                barrier->subresourceRange.levelCount);
        fflush(stderr);
    }
}

static db_vk_descriptor_payload_kind db_vk_payload_kind(DB_VkDescriptorType type) {
    switch (type) {
        case DB_VK_DESCRIPTOR_TYPE_SAMPLER:
        case DB_VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:
        case DB_VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE:
        case DB_VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:
        case DB_VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT:
            return DB_VK_DESCRIPTOR_PAYLOAD_IMAGE;
        case DB_VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER:
        case DB_VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER:
            return DB_VK_DESCRIPTOR_PAYLOAD_TEXEL;
        case DB_VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:
        case DB_VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:
        case DB_VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC:
        case DB_VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC:
            return DB_VK_DESCRIPTOR_PAYLOAD_BUFFER;
        default:
            return DB_VK_DESCRIPTOR_PAYLOAD_NONE;
    }
}

static void db_vk_free_entries(db_vk_descriptor_entry* entry) {
    while (entry != NULL) {
        db_vk_descriptor_entry* next = entry->next;
        free(entry);
        entry = next;
    }
}

static void db_vk_clear_command_buffer_locked(DB_VkCommandBuffer commandBuffer) {
    db_vk_push_state** cursor = &g_db_vk_push_states;
    while (*cursor != NULL) {
        db_vk_push_state* state = *cursor;
        if (state->commandBuffer == commandBuffer) {
            *cursor = state->next;
            db_vk_free_entries(state->entries);
            free(state);
            continue;
        }
        cursor = &state->next;
    }
}

static void db_vk_clear_command_buffer(DB_VkCommandBuffer commandBuffer) {
    if (commandBuffer == NULL) return;
    pthread_mutex_lock(&g_db_vk_push_mutex);
    db_vk_clear_command_buffer_locked(commandBuffer);
    pthread_mutex_unlock(&g_db_vk_push_mutex);
    db_vk_clear_bound_pipeline(commandBuffer);
}

static db_vk_push_state* db_vk_find_or_create_state_locked(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint pipelineBindPoint,
        DB_VkPipelineLayout layout,
        uint32_t set) {
    for (db_vk_push_state* state = g_db_vk_push_states;
         state != NULL;
         state = state->next) {
        if (state->commandBuffer == commandBuffer
                && state->pipelineBindPoint == pipelineBindPoint
                && state->layout == layout
                && state->set == set) {
            return state;
        }
    }

    db_vk_push_state* state = (db_vk_push_state*)calloc(1u, sizeof(db_vk_push_state));
    if (state == NULL) return NULL;
    state->commandBuffer = commandBuffer;
    state->pipelineBindPoint = pipelineBindPoint;
    state->layout = layout;
    state->set = set;
    state->next = g_db_vk_push_states;
    g_db_vk_push_states = state;
    return state;
}

static db_vk_descriptor_entry* db_vk_find_or_create_entry_locked(
        db_vk_push_state* state,
        uint32_t binding,
        uint32_t arrayElement) {
    for (db_vk_descriptor_entry* entry = state->entries;
         entry != NULL;
         entry = entry->next) {
        if (entry->binding == binding && entry->arrayElement == arrayElement) {
            return entry;
        }
    }

    db_vk_descriptor_entry* entry =
            (db_vk_descriptor_entry*)calloc(1u, sizeof(db_vk_descriptor_entry));
    if (entry == NULL) return NULL;
    entry->binding = binding;
    entry->arrayElement = arrayElement;
    entry->next = state->entries;
    state->entries = entry;
    return entry;
}

static bool db_vk_write_supported(const DB_VkWriteDescriptorSet* write) {
    if (write == NULL || write->descriptorCount == 0u) return true;
    if (write->pNext != NULL) return false;
    db_vk_descriptor_payload_kind kind = db_vk_payload_kind(write->descriptorType);
    if (kind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE) return write->pImageInfo != NULL;
    if (kind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER) return write->pBufferInfo != NULL;
    if (kind == DB_VK_DESCRIPTOR_PAYLOAD_TEXEL) return write->pTexelBufferView != NULL;
    return false;
}

static bool db_vk_apply_write_locked(
        db_vk_push_state* state,
        const DB_VkWriteDescriptorSet* write) {
    db_vk_descriptor_payload_kind kind = db_vk_payload_kind(write->descriptorType);
    for (uint32_t i = 0; i < write->descriptorCount; ++i) {
        db_vk_descriptor_entry* entry = db_vk_find_or_create_entry_locked(
                state,
                write->dstBinding,
                write->dstArrayElement + i);
        if (entry == NULL) return false;
        entry->descriptorType = write->descriptorType;
        entry->payloadKind = kind;
        if (kind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE) {
            entry->imageInfo = write->pImageInfo[i];
        } else if (kind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER) {
            entry->bufferInfo = write->pBufferInfo[i];
        } else if (kind == DB_VK_DESCRIPTOR_PAYLOAD_TEXEL) {
            entry->texelBufferView = write->pTexelBufferView[i];
        }
    }
    return true;
}

static size_t db_vk_count_entries(const db_vk_push_state* state) {
    size_t count = 0u;
    for (const db_vk_descriptor_entry* entry = state->entries;
         entry != NULL;
         entry = entry->next) {
        ++count;
    }
    return count;
}

static int db_vk_compare_entry_ptrs(const void* left, const void* right) {
    const db_vk_descriptor_entry* a = *(const db_vk_descriptor_entry* const*)left;
    const db_vk_descriptor_entry* b = *(const db_vk_descriptor_entry* const*)right;
    if (a->binding < b->binding) return -1;
    if (a->binding > b->binding) return 1;
    if (a->arrayElement < b->arrayElement) return -1;
    if (a->arrayElement > b->arrayElement) return 1;
    return 0;
}

static void db_vk_observe_push_descriptor_writes(
        uint32_t set,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_image_diagnostics_enabled()
            || descriptorWriteCount == 0u
            || descriptorWrites == NULL) {
        return;
    }

    size_t imageEntries = 0u;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (db_vk_payload_kind(write->descriptorType) != DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                || write->pImageInfo == NULL) {
            continue;
        }
        for (uint32_t j = 0u; j < write->descriptorCount; ++j) {
            const DB_VkDescriptorImageInfo* info = &write->pImageInfo[j];
            ++imageEntries;
            db_vk_log_descriptor_image_once(
                    info->imageView,
                    info->imageLayout,
                    write->dstBinding,
                    write->dstArrayElement + j,
                    write->descriptorType);
        }
    }

    ++g_db_vk_observed_push_calls;
    if (g_db_vk_observed_push_calls == 1u
            || (g_db_vk_observed_push_calls % 65536u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanCompat: observed raw push descriptors call=%llu writes=%u images=%zu set=%u mutation=none\n",
                (unsigned long long)g_db_vk_observed_push_calls,
                descriptorWriteCount,
                imageEntries,
                set);
        fflush(stderr);
    }
}

static bool db_vk_push_has_special_texture_binding(
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites,
        uint32_t* bufferWritesOut) {
    if (bufferWritesOut != NULL) *bufferWritesOut = 0u;
    if (descriptorWriteCount == 0u || descriptorWrites == NULL) return false;
    bool specialTexture = false;
    uint32_t bufferWrites = 0u;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        db_vk_descriptor_payload_kind kind = db_vk_payload_kind(write->descriptorType);
        if (kind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                && write->dstBinding == 6u
                && write->descriptorCount > 0u
                && write->pImageInfo != NULL) {
            specialTexture = true;
        }
        if (kind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER
                && write->descriptorCount > 0u
                && write->pBufferInfo != NULL) {
            bufferWrites += write->descriptorCount;
        }
    }
    if (bufferWritesOut != NULL) *bufferWritesOut = bufferWrites;
    return specialTexture;
}

static const DB_VkWriteDescriptorSet* db_vk_find_binding6_image_write(
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites,
        uint32_t* indexOut) {
    if (indexOut != NULL) *indexOut = UINT32_MAX;
    if (descriptorWriteCount == 0u || descriptorWrites == NULL) return NULL;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (db_vk_payload_kind(write->descriptorType) == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                && write->dstBinding == 6u
                && write->descriptorCount == 1u
                && write->pImageInfo != NULL) {
            if (indexOut != NULL) *indexOut = i;
            return write;
        }
    }
    return NULL;
}

static bool db_vk_try_entity_known_good_atlas_swap(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint pipelineBindPoint,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites,
        DB_VkWriteDescriptorSet** rewrittenWritesOut,
        DB_VkDescriptorImageInfo* replacementInfoOut) {
    if (rewrittenWritesOut != NULL) *rewrittenWritesOut = NULL;
    if (!db_vk_entity_shader_fallback_enabled()
            || rewrittenWritesOut == NULL
            || replacementInfoOut == NULL) {
        return false;
    }

    uint32_t binding6Index = UINT32_MAX;
    const DB_VkWriteDescriptorSet* binding6Write = db_vk_find_binding6_image_write(
            descriptorWriteCount, descriptorWrites, &binding6Index);
    if (binding6Write == NULL || binding6Index == UINT32_MAX) return false;

    DB_VkPipeline pipeline = db_vk_get_bound_pipeline(commandBuffer, pipelineBindPoint);
    db_vk_pipeline_record pipelineSummary;
    memset(&pipelineSummary, 0, sizeof(pipelineSummary));
    if (!db_vk_lookup_pipeline_record(pipeline, &pipelineSummary)) return false;

    const DB_VkDescriptorImageInfo incoming = binding6Write->pImageInfo[0];

    if (pipelineSummary.vertexHash == DB_VK_GOOD_ITEM_VERTEX_HASH
            && pipelineSummary.fragmentHash == DB_VK_GOOD_ITEM_FRAGMENT_HASH
            && incoming.sampler != 0u
            && incoming.imageView != 0u) {
        db_vk_image_summary imageSummary;
        memset(&imageSummary, 0, sizeof(imageSummary));
        bool imageKnown = db_vk_lookup_image_view_summary(incoming.imageView, &imageSummary);
        pthread_mutex_lock(&g_db_vk_entity_atlas_swap_mutex);
        g_db_vk_good_item_binding6 = incoming;
        g_db_vk_good_item_binding6_cached = true;
        bool shouldLog = !g_db_vk_entity_atlas_cache_logged;
        g_db_vk_entity_atlas_cache_logged = true;
        pthread_mutex_unlock(&g_db_vk_entity_atlas_swap_mutex);
        if (shouldLog) {
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasAudit: cached known-good item binding6 pipeline=%llu sampler=%llu view=%llu layout=%s(%d) imageKnown=%d size=%ux%u usage=0x%x\n",
                    (unsigned long long)pipeline,
                    (unsigned long long)incoming.sampler,
                    (unsigned long long)incoming.imageView,
                    db_vk_layout_name(incoming.imageLayout), incoming.imageLayout,
                    imageKnown ? 1 : 0,
                    imageKnown ? imageSummary.width : 0u,
                    imageKnown ? imageSummary.height : 0u,
                    imageKnown ? imageSummary.usage : 0u);
            fflush(stderr);
        }
        return false;
    }

    // The affected banner/painting family is identified by its original vertex
    // shader. In this build its fragment module is the texture-op diagnostic.
    if (pipelineSummary.vertexHash != DB_VK_BAD_ENTITY_VERTEX_HASH) return false;

    DB_VkDescriptorImageInfo reference;
    memset(&reference, 0, sizeof(reference));
    bool cached = false;
    pthread_mutex_lock(&g_db_vk_entity_atlas_swap_mutex);
    if (g_db_vk_good_item_binding6_cached) {
        reference = g_db_vk_good_item_binding6;
        cached = true;
    }
    pthread_mutex_unlock(&g_db_vk_entity_atlas_swap_mutex);
    if (!cached || reference.imageView == 0u) return false;

    db_vk_image_summary targetSummary;
    db_vk_image_summary itemSummary;
    memset(&targetSummary, 0, sizeof(targetSummary));
    memset(&itemSummary, 0, sizeof(itemSummary));
    bool targetKnown = db_vk_lookup_image_view_summary(incoming.imageView, &targetSummary);
    bool itemKnown = db_vk_lookup_image_view_summary(reference.imageView, &itemSummary);
    uint64_t audit = ++g_db_vk_entity_atlas_swaps;
    if (audit <= 32u || (audit % 8192u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasAudit: compare #%llu pipeline=%llu targetView=%llu targetSize=%ux%u targetWrites=buffer:%llu/copy:%llu/blit:%llu/render:%llu targetBarriers=%llu itemView=%llu itemSize=%ux%u itemWrites=buffer:%llu/copy:%llu/blit:%llu/render:%llu itemBarriers=%llu mutation=none\n",
                (unsigned long long)audit,
                (unsigned long long)pipeline,
                (unsigned long long)incoming.imageView,
                targetKnown ? targetSummary.width : 0u,
                targetKnown ? targetSummary.height : 0u,
                (unsigned long long)(targetKnown ? targetSummary.bufferUploadCalls : 0u),
                (unsigned long long)(targetKnown ? targetSummary.imageCopyWriteCalls : 0u),
                (unsigned long long)(targetKnown ? targetSummary.imageBlitWriteCalls : 0u),
                (unsigned long long)(targetKnown ? targetSummary.renderTargetBeginCalls : 0u),
                (unsigned long long)(targetKnown ? targetSummary.barrierCalls : 0u),
                (unsigned long long)reference.imageView,
                itemKnown ? itemSummary.width : 0u,
                itemKnown ? itemSummary.height : 0u,
                (unsigned long long)(itemKnown ? itemSummary.bufferUploadCalls : 0u),
                (unsigned long long)(itemKnown ? itemSummary.imageCopyWriteCalls : 0u),
                (unsigned long long)(itemKnown ? itemSummary.imageBlitWriteCalls : 0u),
                (unsigned long long)(itemKnown ? itemSummary.renderTargetBeginCalls : 0u),
                (unsigned long long)(itemKnown ? itemSummary.barrierCalls : 0u));
        fflush(stderr);
    }
    return false;
}

static void db_vk_emit_special_draw_host_visibility_barrier(
        DB_VkCommandBuffer commandBuffer,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_special_draw_sync_repair_enabled()) return;
    uint32_t bufferWrites = 0u;
    if (!db_vk_push_has_special_texture_binding(
            descriptorWriteCount, descriptorWrites, &bufferWrites)) {
        return;
    }
    DB_PFN_vkCmdPipelineBarrier2 barrier2 = g_db_real_vkCmdPipelineBarrier2;
    if (barrier2 == NULL) {
        if (!g_db_vk_special_draw_sync_missing_barrier_logged) {
            g_db_vk_special_draw_sync_missing_barrier_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanSpecialSync: binding-6 special draw observed but vkCmdPipelineBarrier2 is unavailable\n");
            fflush(stderr);
        }
        return;
    }

    DB_VkMemoryBarrier2 memoryBarrier;
    memset(&memoryBarrier, 0, sizeof(memoryBarrier));
    memoryBarrier.sType = DB_VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
    memoryBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_HOST_BIT;
    memoryBarrier.srcAccessMask = DB_VK_ACCESS_2_HOST_WRITE_BIT;
    memoryBarrier.dstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    memoryBarrier.dstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;

    DB_VkDependencyInfo dependency;
    memset(&dependency, 0, sizeof(dependency));
    dependency.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
    dependency.memoryBarrierCount = 1u;
    dependency.pMemoryBarriers = &memoryBarrier;
    barrier2(commandBuffer, &dependency);

    uint64_t call = ++g_db_vk_special_draw_sync_barriers;
    if (call <= 48u || (call % 4096u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanSpecialSync: HOST_WRITE visibility barrier #%llu binding=6 bufferDescriptors=%u stage=HOST->ALL_COMMANDS access=HOST_WRITE->MEMORY_READ\n",
                (unsigned long long)call,
                bufferWrites);
        fflush(stderr);
    }
}

static void db_vk_log_entity_aux_descriptor_packet(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint pipelineBindPoint,
        DB_VkPipelineLayout pipelineLayout,
        uint32_t set,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_entity_aux_diagnostics_enabled()) return;
    uint32_t bufferWrites = 0u;
    if (!db_vk_push_has_special_texture_binding(
            descriptorWriteCount, descriptorWrites, &bufferWrites)) {
        return;
    }

    uint64_t packet = ++g_db_vk_entity_aux_packets;
    if (packet > 64u && (packet % 8192u) != 0u) return;

    DB_VkPipeline boundPipeline =
            db_vk_get_bound_pipeline(commandBuffer, pipelineBindPoint);
    db_vk_pipeline_record pipelineSummary;
    memset(&pipelineSummary, 0, sizeof(pipelineSummary));
    bool pipelineKnown = db_vk_lookup_pipeline_record(boundPipeline, &pipelineSummary);

    fprintf(stderr,
            "DroidBridgeVulkanEntityShader: packet #%llu layout=%llu set=%u writes=%u bufferDescriptors=%u pipeline=%llu pipelineKnown=%d\n",
            (unsigned long long)packet,
            (unsigned long long)pipelineLayout,
            set, descriptorWriteCount, bufferWrites,
            (unsigned long long)boundPipeline,
            pipelineKnown ? 1 : 0);

    if (pipelineKnown) {
        ++g_db_vk_pipeline_correlations;
        fprintf(stderr,
                "DroidBridgeVulkanEntityShader:   pipelineState createLayout=%llu layoutMatch=%d vmod=%llu vhash=%016llx fmod=%llu fhash=%016llx vertex=%u/%u/stride%u topology=%d restart=%u raster=discard%u/poly%d/cull0x%x/front%d/depthBias%u msaa=%u/sampleShade%u depth=test%u/write%u/op%d/stencil%u blend=count%u/en%u/srcC%d/dstC%d/opC%d/srcA%d/dstA%d/opA%d/mask0x%x renderPass=%llu/subpass%u dynRender=colorCount%u/color0=%d/depth=%d/stencil=%d dynStates=%u\n",
                (unsigned long long)pipelineSummary.layout,
                pipelineSummary.layout == pipelineLayout ? 1 : 0,
                (unsigned long long)pipelineSummary.vertexModule,
                (unsigned long long)pipelineSummary.vertexHash,
                (unsigned long long)pipelineSummary.fragmentModule,
                (unsigned long long)pipelineSummary.fragmentHash,
                pipelineSummary.vertexBindingCount,
                pipelineSummary.vertexAttributeCount,
                pipelineSummary.vertexStride0,
                pipelineSummary.topology,
                pipelineSummary.primitiveRestartEnable,
                pipelineSummary.rasterizerDiscardEnable,
                pipelineSummary.polygonMode,
                pipelineSummary.cullMode,
                pipelineSummary.frontFace,
                pipelineSummary.depthBiasEnable,
                pipelineSummary.rasterizationSamples,
                pipelineSummary.sampleShadingEnable,
                pipelineSummary.depthTestEnable,
                pipelineSummary.depthWriteEnable,
                pipelineSummary.depthCompareOp,
                pipelineSummary.stencilTestEnable,
                pipelineSummary.blendAttachmentCount,
                pipelineSummary.blendEnable0,
                pipelineSummary.srcColorBlendFactor0,
                pipelineSummary.dstColorBlendFactor0,
                pipelineSummary.colorBlendOp0,
                pipelineSummary.srcAlphaBlendFactor0,
                pipelineSummary.dstAlphaBlendFactor0,
                pipelineSummary.alphaBlendOp0,
                pipelineSummary.colorWriteMask0,
                (unsigned long long)pipelineSummary.renderPass,
                pipelineSummary.subpass,
                pipelineSummary.dynamicRenderingColorCount,
                pipelineSummary.dynamicRenderingColor0,
                pipelineSummary.dynamicRenderingDepth,
                pipelineSummary.dynamicRenderingStencil,
                pipelineSummary.dynamicStateCount);
        if (pipelineSummary.dynamicStateCount > 0u) {
            uint32_t count = pipelineSummary.dynamicStateCount < 16u
                    ? pipelineSummary.dynamicStateCount : 16u;
            fprintf(stderr, "DroidBridgeVulkanEntityShader:   dynamicStateValues=[");
            for (uint32_t i = 0u; i < count; ++i) {
                fprintf(stderr, "%s%d", i == 0u ? "" : ",",
                        pipelineSummary.dynamicStates[i]);
            }
            if (pipelineSummary.dynamicStateCount > count) fprintf(stderr, ",...");
            fprintf(stderr, "]\n");
        }
    }

    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        db_vk_descriptor_payload_kind kind = db_vk_payload_kind(write->descriptorType);
        if (kind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE && write->pImageInfo != NULL) {
            for (uint32_t j = 0u; j < write->descriptorCount; ++j) {
                const DB_VkDescriptorImageInfo* info = &write->pImageInfo[j];
                db_vk_image_summary summary;
                memset(&summary, 0, sizeof(summary));
                bool known = db_vk_lookup_image_view_summary(info->imageView, &summary);
                db_vk_sampler_summary samplerSummary;
                bool samplerKnown = db_vk_lookup_sampler_summary(info->sampler, &samplerSummary);
                if (known) {
                    fprintf(stderr,
                            "DroidBridgeVulkanEntityShader:   image binding=%u array=%u type=%d sampler=%llu samplerKnown=%d mag=%d min=%d mip=%d addr=%d/%d/%d aniso=%u:%.2f compare=%u:%d lod=%.2f..%.2f unnorm=%u view=%llu layout=%s(%d) image=%llu size=%ux%ux%u mips=%u layers=%u format=%d usage=0x%x\n",
                            write->dstBinding, write->dstArrayElement + j,
                            write->descriptorType,
                            (unsigned long long)info->sampler,
                            samplerKnown ? 1 : 0,
                            samplerSummary.magFilter, samplerSummary.minFilter, samplerSummary.mipmapMode,
                            samplerSummary.addressModeU, samplerSummary.addressModeV, samplerSummary.addressModeW,
                            samplerSummary.anisotropyEnable, samplerSummary.maxAnisotropy,
                            samplerSummary.compareEnable, samplerSummary.compareOp,
                            samplerSummary.minLod, samplerSummary.maxLod,
                            samplerSummary.unnormalizedCoordinates,
                            (unsigned long long)info->imageView,
                            db_vk_layout_name(info->imageLayout), info->imageLayout,
                            (unsigned long long)summary.image,
                            summary.width, summary.height, summary.depth,
                            summary.mipLevels, summary.arrayLayers,
                            summary.format, summary.usage);
                } else {
                    fprintf(stderr,
                            "DroidBridgeVulkanEntityShader:   image binding=%u array=%u type=%d sampler=%llu view=%llu layout=%s(%d) image=untracked\n",
                            write->dstBinding, write->dstArrayElement + j,
                            write->descriptorType,
                            (unsigned long long)info->sampler,
                            (unsigned long long)info->imageView,
                            db_vk_layout_name(info->imageLayout), info->imageLayout);
                }
            }
        } else if (kind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER && write->pBufferInfo != NULL) {
            for (uint32_t j = 0u; j < write->descriptorCount; ++j) {
                const DB_VkDescriptorBufferInfo* info = &write->pBufferInfo[j];
                fprintf(stderr,
                        "DroidBridgeVulkanEntityShader:   buffer binding=%u array=%u type=%d buffer=%llu offset=%llu range=%llu\n",
                        write->dstBinding, write->dstArrayElement + j,
                        write->descriptorType,
                        (unsigned long long)info->buffer,
                        (unsigned long long)info->offset,
                        (unsigned long long)info->range);
            }
        } else if (kind == DB_VK_DESCRIPTOR_PAYLOAD_TEXEL && write->pTexelBufferView != NULL) {
            for (uint32_t j = 0u; j < write->descriptorCount; ++j) {
                fprintf(stderr,
                        "DroidBridgeVulkanEntityShader:   texel binding=%u array=%u type=%d view=%llu\n",
                        write->dstBinding, write->dstArrayElement + j,
                        write->descriptorType,
                        (unsigned long long)write->pTexelBufferView[j]);
            }
        } else {
            fprintf(stderr,
                    "DroidBridgeVulkanEntityShader:   binding=%u array=%u type=%d count=%u payload=none\n",
                    write->dstBinding, write->dstArrayElement,
                    write->descriptorType, write->descriptorCount);
        }
    }
    fflush(stderr);
}

static bool db_vk_atlas_build_audit_dimension(const db_vk_image_summary* summary) {
    if (summary == NULL || !db_vk_is_sampled_offscreen_target(summary)) return false;
    return (summary->width == 1024u && summary->height == 512u)
            || (summary->width == 512u && summary->height == 256u);
}

static bool db_vk_transition_static_atlas_to_shader_read(
        DB_VkCommandBuffer commandBuffer,
        const db_vk_image_summary* target,
        uint64_t buildPackets) {
    if (!db_vk_static_atlas_layout_repair_enabled()
            || target == NULL
            || !db_vk_atlas_build_audit_dimension(target)
            || buildPackets == 0u
            || buildPackets > 64u) {
        return false;
    }

    DB_PFN_vkCmdPipelineBarrier2 barrier2 = g_db_real_vkCmdPipelineBarrier2;
    if (barrier2 == NULL) {
        if (!g_db_vk_static_atlas_layout_repair_logged) {
            g_db_vk_static_atlas_layout_repair_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasLayoutRepair: requested but vkCmdPipelineBarrier2 is unavailable\n");
            fflush(stderr);
        }
        return false;
    }

    DB_VkImageMemoryBarrier2 imageBarrier;
    memset(&imageBarrier, 0, sizeof(imageBarrier));
    imageBarrier.sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
    imageBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    imageBarrier.srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
    imageBarrier.dstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    imageBarrier.dstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;
    imageBarrier.oldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    imageBarrier.newLayout = DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imageBarrier.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.image = target->image;
    imageBarrier.subresourceRange.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    imageBarrier.subresourceRange.baseMipLevel = 0u;
    imageBarrier.subresourceRange.levelCount = target->mipLevels > 0u ? target->mipLevels : 1u;
    imageBarrier.subresourceRange.baseArrayLayer = 0u;
    imageBarrier.subresourceRange.layerCount = target->arrayLayers > 0u ? target->arrayLayers : 1u;

    DB_VkDependencyInfo dependency;
    memset(&dependency, 0, sizeof(dependency));
    dependency.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
    dependency.imageMemoryBarrierCount = 1u;
    dependency.pImageMemoryBarriers = &imageBarrier;
    barrier2(commandBuffer, &dependency);

    db_vk_set_static_shader_read_layout_repair(target->image, true);
    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* record = db_vk_find_image_locked(target->image);
    if (record != NULL) {
        ++record->barrierCalls;
        record->lastOldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
        record->lastNewLayout = DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        record->lastSrcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        record->lastSrcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
        record->lastDstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        record->lastDstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    uint64_t serial = ++g_db_vk_static_atlas_layout_transitions;
    if (!g_db_vk_static_atlas_layout_repair_logged) {
        g_db_vk_static_atlas_layout_repair_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanAtlasLayoutRepair: static low-draw atlas GENERAL->SHADER_READ_ONLY repair armed; targets are identified by first build size plus <=64 atlas-build descriptor packets; descriptors are rewritten to match the repaired layout\n");
    }
    fprintf(stderr,
            "DroidBridgeVulkanAtlasLayoutRepair: transition #%llu image=%llu size=%ux%u packets=%llu GENERAL->SHADER_READ_ONLY mips=%u layers=%u\n",
            (unsigned long long)serial,
            (unsigned long long)target->image,
            target->width, target->height,
            (unsigned long long)buildPackets,
            target->mipLevels, target->arrayLayers);
    fflush(stderr);
    return true;
}

static bool db_vk_rewrite_static_atlas_descriptor_layouts(
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites,
        DB_VkWriteDescriptorSet** rewrittenWritesOut,
        DB_VkDescriptorImageInfo** rewrittenImageInfosOut) {
    if (rewrittenWritesOut != NULL) *rewrittenWritesOut = NULL;
    if (rewrittenImageInfosOut != NULL) *rewrittenImageInfosOut = NULL;
    if (!db_vk_static_atlas_layout_repair_enabled()
            || descriptorWriteCount == 0u
            || descriptorWrites == NULL
            || rewrittenWritesOut == NULL
            || rewrittenImageInfosOut == NULL) {
        return false;
    }

    uint32_t totalImageInfos = 0u;
    bool needsRewrite = false;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (db_vk_payload_kind(write->descriptorType) != DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                || write->pImageInfo == NULL) {
            continue;
        }
        totalImageInfos += write->descriptorCount;
        for (uint32_t j = 0u; j < write->descriptorCount; ++j) {
            if (db_vk_view_has_static_shader_read_layout_repair(
                    write->pImageInfo[j].imageView, NULL)) {
                needsRewrite = true;
            }
        }
    }
    if (!needsRewrite || totalImageInfos == 0u) return false;

    DB_VkWriteDescriptorSet* rewrittenWrites = (DB_VkWriteDescriptorSet*)calloc(
            descriptorWriteCount, sizeof(*rewrittenWrites));
    DB_VkDescriptorImageInfo* rewrittenInfos = (DB_VkDescriptorImageInfo*)calloc(
            totalImageInfos, sizeof(*rewrittenInfos));
    if (rewrittenWrites == NULL || rewrittenInfos == NULL) {
        free(rewrittenWrites);
        free(rewrittenInfos);
        return false;
    }
    memcpy(rewrittenWrites, descriptorWrites,
           descriptorWriteCount * sizeof(*rewrittenWrites));

    uint32_t cursor = 0u;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* sourceWrite = &descriptorWrites[i];
        if (db_vk_payload_kind(sourceWrite->descriptorType) != DB_VK_DESCRIPTOR_PAYLOAD_IMAGE
                || sourceWrite->pImageInfo == NULL) {
            continue;
        }
        DB_VkDescriptorImageInfo* dstInfos = &rewrittenInfos[cursor];
        memcpy(dstInfos, sourceWrite->pImageInfo,
               sourceWrite->descriptorCount * sizeof(*dstInfos));
        rewrittenWrites[i].pImageInfo = dstInfos;
        for (uint32_t j = 0u; j < sourceWrite->descriptorCount; ++j) {
            db_vk_image_summary summary;
            memset(&summary, 0, sizeof(summary));
            if (!db_vk_view_has_static_shader_read_layout_repair(
                    dstInfos[j].imageView, &summary)) {
                continue;
            }
            int32_t oldLayout = dstInfos[j].imageLayout;
            dstInfos[j].imageLayout = DB_VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            uint64_t serial = ++g_db_vk_static_atlas_layout_descriptor_rewrites;
            if (serial <= 64u || (serial % 8192u) == 0u) {
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasLayoutRepair: descriptor #%llu binding=%u view=%llu image=%llu size=%ux%u %s(%d)->SHADER_READ_ONLY(5)\n",
                        (unsigned long long)serial,
                        sourceWrite->dstBinding,
                        (unsigned long long)dstInfos[j].imageView,
                        (unsigned long long)summary.image,
                        summary.width, summary.height,
                        db_vk_layout_name(oldLayout), oldLayout);
                fflush(stderr);
            }
        }
        cursor += sourceWrite->descriptorCount;
    }

    *rewrittenWritesOut = rewrittenWrites;
    *rewrittenImageInfosOut = rewrittenInfos;
    return true;
}

static void db_vk_log_atlas_build_input_packet(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint pipelineBindPoint,
        DB_VkPipelineLayout layout,
        uint32_t set,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_atlas_build_input_audit_enabled()
            || descriptorWriteCount == 0u || descriptorWrites == NULL) {
        return;
    }

    db_vk_image_summary target;
    memset(&target, 0, sizeof(target));
    uint64_t scopeSerial = 0u;
    uint64_t scopePacket = 0u;
    bool interesting = false;

    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* scope = db_vk_get_render_scope_locked(commandBuffer, false);
    if (scope != NULL && scope->atlasBuildAuditInteresting) {
        target = scope->firstTarget;
        scopeSerial = scope->atlasBuildAuditScope;
        scopePacket = ++scope->atlasBuildAuditPackets;
        interesting = true;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
    if (!interesting || scopePacket > 24u) return;

    DB_VkPipeline pipeline = db_vk_get_bound_pipeline(commandBuffer, pipelineBindPoint);
    db_vk_pipeline_record pipelineSummary;
    memset(&pipelineSummary, 0, sizeof(pipelineSummary));
    bool pipelineKnown = db_vk_lookup_pipeline_record(pipeline, &pipelineSummary);
    uint64_t packet = ++g_db_vk_atlas_build_input_packets;

    fprintf(stderr,
            "DroidBridgeVulkanAtlasBuildInput: packet #%llu scope=%llu scopePacket=%llu target=%llu size=%ux%u pipeline=%llu known=%d layout=%llu set=%u writes=%u mutation=none\n",
            (unsigned long long)packet,
            (unsigned long long)scopeSerial,
            (unsigned long long)scopePacket,
            (unsigned long long)target.image,
            target.width, target.height,
            (unsigned long long)pipeline,
            pipelineKnown ? 1 : 0,
            (unsigned long long)layout,
            set, descriptorWriteCount);
    if (pipelineKnown) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasBuildInput:   pipeline vhash=%016llx fhash=%016llx vertex=%u/%u/stride%u topology=%d cull0x%x depth=test%u/write%u/op%d blend=en%u/srcC%d/dstC%d/opC%d/srcA%d/dstA%d/opA%d/mask0x%x color0=%d depthFmt=%d\n",
                (unsigned long long)pipelineSummary.vertexHash,
                (unsigned long long)pipelineSummary.fragmentHash,
                pipelineSummary.vertexBindingCount,
                pipelineSummary.vertexAttributeCount,
                pipelineSummary.vertexStride0,
                pipelineSummary.topology,
                pipelineSummary.cullMode,
                pipelineSummary.depthTestEnable,
                pipelineSummary.depthWriteEnable,
                pipelineSummary.depthCompareOp,
                pipelineSummary.blendEnable0,
                pipelineSummary.srcColorBlendFactor0,
                pipelineSummary.dstColorBlendFactor0,
                pipelineSummary.colorBlendOp0,
                pipelineSummary.srcAlphaBlendFactor0,
                pipelineSummary.dstAlphaBlendFactor0,
                pipelineSummary.alphaBlendOp0,
                pipelineSummary.colorWriteMask0,
                pipelineSummary.dynamicRenderingColor0,
                pipelineSummary.dynamicRenderingDepth);
    }

    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        db_vk_descriptor_payload_kind kind = db_vk_payload_kind(write->descriptorType);
        if (kind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE && write->pImageInfo != NULL) {
            uint32_t count = write->descriptorCount < 4u ? write->descriptorCount : 4u;
            for (uint32_t j = 0u; j < count; ++j) {
                const DB_VkDescriptorImageInfo* info = &write->pImageInfo[j];
                db_vk_image_summary imageSummary;
                memset(&imageSummary, 0, sizeof(imageSummary));
                bool imageKnown = db_vk_lookup_image_view_summary(info->imageView, &imageSummary);
                db_vk_sampler_summary samplerSummary;
                memset(&samplerSummary, 0, sizeof(samplerSummary));
                bool samplerKnown = db_vk_lookup_sampler_summary(info->sampler, &samplerSummary);
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasBuildInput:   image binding=%u array=%u type=%d sampler=%llu samplerKnown=%d filter=%d/%d mip=%d addr=%d/%d/%d lod=%.2f..%.2f view=%llu layout=%s(%d)",
                        write->dstBinding, write->dstArrayElement + j,
                        write->descriptorType,
                        (unsigned long long)info->sampler,
                        samplerKnown ? 1 : 0,
                        samplerSummary.magFilter, samplerSummary.minFilter,
                        samplerSummary.mipmapMode,
                        samplerSummary.addressModeU, samplerSummary.addressModeV,
                        samplerSummary.addressModeW,
                        samplerSummary.minLod, samplerSummary.maxLod,
                        (unsigned long long)info->imageView,
                        db_vk_layout_name(info->imageLayout), info->imageLayout);
                if (imageKnown) {
                    fprintf(stderr,
                            " image=%llu size=%ux%ux%u mips=%u layers=%u format=%d usage=0x%x writes=buffer:%llu/copy:%llu/blit:%llu/render:%llu barriers=%llu\n",
                            (unsigned long long)imageSummary.image,
                            imageSummary.width, imageSummary.height, imageSummary.depth,
                            imageSummary.mipLevels, imageSummary.arrayLayers,
                            imageSummary.format, imageSummary.usage,
                            (unsigned long long)imageSummary.bufferUploadCalls,
                            (unsigned long long)imageSummary.imageCopyWriteCalls,
                            (unsigned long long)imageSummary.imageBlitWriteCalls,
                            (unsigned long long)imageSummary.renderTargetBeginCalls,
                            (unsigned long long)imageSummary.barrierCalls);
                    if (imageSummary.bufferUploadCalls > 0u) {
                        fprintf(stderr,
                                "DroidBridgeVulkanAtlasBuildInput:     upload buffer=%llu offset=%llu rowLength=%u imageHeight=%u extent=%ux%ux%u mip=%u layer=%u+%u layout=%s(%d) spriteShaderRead=%d\n",
                                (unsigned long long)imageSummary.lastUploadBuffer,
                                (unsigned long long)imageSummary.lastUploadBufferOffset,
                                imageSummary.lastUploadBufferRowLength,
                                imageSummary.lastUploadBufferImageHeight,
                                imageSummary.lastUploadWidth,
                                imageSummary.lastUploadHeight,
                                imageSummary.lastUploadDepth,
                                imageSummary.lastUploadMipLevel,
                                imageSummary.lastUploadBaseArrayLayer,
                                imageSummary.lastUploadLayerCount,
                                db_vk_layout_name(imageSummary.lastUploadLayout),
                                imageSummary.lastUploadLayout,
                                imageSummary.spriteUploadShaderReadLayoutRepair ? 1 : 0);
                    }
                } else {
                    fprintf(stderr, " image=untracked\n");
                }
            }
        } else if (kind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER && write->pBufferInfo != NULL) {
            uint32_t count = write->descriptorCount < 4u ? write->descriptorCount : 4u;
            for (uint32_t j = 0u; j < count; ++j) {
                const DB_VkDescriptorBufferInfo* info = &write->pBufferInfo[j];
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasBuildInput:   buffer binding=%u array=%u type=%d buffer=%llu offset=%llu range=%llu\n",
                        write->dstBinding, write->dstArrayElement + j,
                        write->descriptorType,
                        (unsigned long long)info->buffer,
                        (unsigned long long)info->offset,
                        (unsigned long long)info->range);
            }
        }
    }
    fflush(stderr);
}


static bool db_vk_atlas_ubo_scope_target(
        DB_VkCommandBuffer commandBuffer,
        db_vk_image_summary* outTarget) {
    bool active = false;
    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* scope = db_vk_get_render_scope_locked(commandBuffer, false);
    if (scope != NULL
            && scope->sampledTarget
            && scope->firstTargetRenderCall == 1u
            && db_vk_atlas_build_audit_dimension(&scope->firstTarget)) {
        active = true;
        if (outTarget != NULL) *outTarget = scope->firstTarget;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
    return active;
}

static bool db_vk_round_float_to_int(float value, int32_t* out);

static const DB_VkDescriptorBufferInfo* db_vk_find_atlas_binding0_ubo(
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (descriptorWrites == NULL) return NULL;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (write->dstBinding == 0u && write->pBufferInfo != NULL
                && write->descriptorCount > 0u) {
            return &write->pBufferInfo[0];
        }
    }
    return NULL;
}

static size_t db_vk_read_descriptor_buffer_bytes(
        const DB_VkDescriptorBufferInfo* bufferInfo,
        uint8_t* outBytes, size_t outCapacity) {
    if (bufferInfo == NULL || outBytes == NULL || outCapacity == 0u
            || bufferInfo->buffer == 0u || bufferInfo->range == 0u) return 0u;

    size_t captured = 0u;
    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_buffer_write_record* writeRecord = db_vk_find_buffer_write_locked(bufferInfo->buffer);
    if (writeRecord != NULL) {
        db_vk_buffer_write_record wr = *writeRecord;
        uint64_t uboStart = bufferInfo->offset;
        uint64_t uboEnd = bufferInfo->range == DB_VK_WHOLE_SIZE
                ? UINT64_MAX : uboStart + bufferInfo->range;
        uint64_t writeEnd = wr.dstOffset + wr.size;
        bool overlaps = wr.dstOffset < uboEnd && writeEnd > uboStart;
        if (overlaps && wr.kind == DB_VK_BUFFER_WRITE_COPY && wr.srcBuffer != 0u) {
            db_vk_buffer_memory_record* srcBinding = db_vk_find_buffer_binding_locked(wr.srcBuffer);
            if (srcBinding != NULL) {
                db_vk_memory_map_record* srcMap = db_vk_find_memory_map_locked(srcBinding->memory);
                if (srcMap != NULL && srcMap->mapped != NULL) {
                    uint64_t overlapStart = wr.dstOffset > uboStart ? wr.dstOffset : uboStart;
                    uint64_t delta = overlapStart - wr.dstOffset;
                    if (delta < wr.size) {
                        uint64_t logical = wr.srcOffset + delta;
                        uint64_t absolute = srcBinding->memoryOffset + logical;
                        size_t want = bufferInfo->range == DB_VK_WHOLE_SIZE
                                ? outCapacity
                                : (bufferInfo->range < outCapacity
                                    ? (size_t)bufferInfo->range : outCapacity);
                        if ((uint64_t)want > wr.size - delta) want = (size_t)(wr.size - delta);
                        if (absolute >= srcMap->mapOffset) {
                            uint64_t relative = absolute - srcMap->mapOffset;
                            bool within = srcMap->mapSize == DB_VK_WHOLE_SIZE
                                    || relative + want <= srcMap->mapSize;
                            if (within && want > 0u) {
                                memcpy(outBytes, (const uint8_t*)srcMap->mapped + relative, want);
                                captured = want;
                            }
                        }
                    }
                }
            }
        }
    }
    if (captured == 0u) {
        db_vk_buffer_memory_record* binding = db_vk_find_buffer_binding_locked(bufferInfo->buffer);
        if (binding != NULL) {
            db_vk_memory_map_record* map = db_vk_find_memory_map_locked(binding->memory);
            if (map != NULL && map->mapped != NULL) {
                uint64_t absolute = binding->memoryOffset + bufferInfo->offset;
                size_t want = bufferInfo->range == DB_VK_WHOLE_SIZE
                        ? outCapacity
                        : (bufferInfo->range < outCapacity
                            ? (size_t)bufferInfo->range : outCapacity);
                if (absolute >= map->mapOffset) {
                    uint64_t relative = absolute - map->mapOffset;
                    bool within = map->mapSize == DB_VK_WHOLE_SIZE
                            || relative + want <= map->mapSize;
                    if (within && want > 0u) {
                        memcpy(outBytes, (const uint8_t*)map->mapped + relative, want);
                        captured = want;
                    }
                }
            }
        }
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);
    return captured;
}

static bool db_vk_decode_atlas_sprite_placement(
        const db_vk_image_summary* target,
        const db_vk_image_summary* source,
        const DB_VkDescriptorBufferInfo* ubo,
        db_vk_atlas_compose_packet* outPacket) {
    if (target == NULL || source == NULL || ubo == NULL || outPacket == NULL) return false;
    uint8_t bytes[140];
    memset(bytes, 0, sizeof(bytes));
    size_t captured = db_vk_read_descriptor_buffer_bytes(ubo, bytes, sizeof(bytes));
    if (captured < sizeof(bytes)) return false;

    float values[34];
    memset(values, 0, sizeof(values));
    memcpy(values, bytes, sizeof(values));
    int32_t mipMapLevel = 0;
    memcpy(&mipMapLevel, bytes + 34u * sizeof(uint32_t), sizeof(mipMapLevel));

    // std140 layout verified from the V4 SPIRV-Cross dump:
    // words 0..15 ProjectionMatrix, 16..31 SpriteMatrix,
    // 32 UPadding, 33 VPadding, 34 MipMapLevel.
    const float projectionX = values[0];
    const float projectionY = values[5];
    const float scaleX = values[16];
    const float scaleY = values[21];
    const float translateX = values[28];
    const float translateY = values[29];
    const float uPadding = values[32];
    const float vPadding = values[33];

    float expectedProjectionX = target->width > 0u ? 2.0f / (float)target->width : 0.0f;
    float expectedProjectionY = target->height > 0u ? 2.0f / (float)target->height : 0.0f;
    float dx = projectionX - expectedProjectionX;
    float dy = projectionY - expectedProjectionY;
    if (dx < 0.0f) dx = -dx;
    if (dy < 0.0f) dy = -dy;
    if (dx > 0.00001f || dy > 0.00001f || mipMapLevel != 0) return false;

    int32_t outerX = 0, outerY = 0, outerW = 0, outerH = 0;
    if (!db_vk_round_float_to_int(translateX, &outerX)
            || !db_vk_round_float_to_int(translateY, &outerY)
            || !db_vk_round_float_to_int(scaleX, &outerW)
            || !db_vk_round_float_to_int(scaleY, &outerH)
            || outerX < 0 || outerY < 0 || outerW <= 0 || outerH <= 0) {
        return false;
    }

    uint32_t sourceWidth = source->lastUploadWidth > 0u
            ? source->lastUploadWidth : source->width;
    uint32_t sourceHeight = source->lastUploadHeight > 0u
            ? source->lastUploadHeight : source->height;
    if (sourceWidth == 0u || sourceHeight == 0u
            || sourceWidth > source->width || sourceHeight > source->height) return false;

    // The captured MC 26.2 UBOs consistently use one repeat-padding texel on
    // every side: SpriteMatrix scale = source dimensions + 2, padding=1/source.
    // Accept an unpadded exact-size sprite as a safe fallback, but reject any
    // other geometry rather than corrupting unrelated atlases.
    int32_t extraX = outerW - (int32_t)sourceWidth;
    int32_t extraY = outerH - (int32_t)sourceHeight;
    if (!((extraX == 2 || extraX == 0) && (extraY == 2 || extraY == 0))) return false;
    uint32_t borderX = extraX == 2 ? 1u : 0u;
    uint32_t borderY = extraY == 2 ? 1u : 0u;

    if ((uint64_t)outerX + (uint32_t)outerW > target->width
            || (uint64_t)outerY + (uint32_t)outerH > target->height) return false;

    if (borderX == 1u) {
        float expected = 1.0f / (float)sourceWidth;
        float diff = uPadding - expected;
        if (diff < 0.0f) diff = -diff;
        if (diff > 0.0005f) return false;
    }
    if (borderY == 1u) {
        float expected = 1.0f / (float)sourceHeight;
        float diff = vPadding - expected;
        if (diff < 0.0f) diff = -diff;
        if (diff > 0.0005f) return false;
    }

    outPacket->dstOuterX = outerX;
    outPacket->dstOuterY = outerY;
    outPacket->dstOuterWidth = (uint32_t)outerW;
    outPacket->dstOuterHeight = (uint32_t)outerH;
    outPacket->dstInnerX = (uint32_t)outerX + borderX;
    outPacket->dstInnerY = (uint32_t)outerY + borderY;
    outPacket->dstInnerWidth = sourceWidth;
    outPacket->dstInnerHeight = sourceHeight;
    outPacket->uPadding = uPadding;
    outPacket->vPadding = vPadding;
    outPacket->mipMapLevel = mipMapLevel;
    outPacket->placementValid = true;
    return true;
}

static void db_vk_log_atlas_ubo_words_v4(
        DB_VkCommandBuffer commandBuffer,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_atlas_fragment_repair_enabled()
            || descriptorWrites == NULL || descriptorWriteCount == 0u) return;

    db_vk_image_summary target;
    memset(&target, 0, sizeof(target));
    if (!db_vk_atlas_ubo_scope_target(commandBuffer, &target)) return;
    if (!((target.width == 512u && target.height == 256u)
            || (target.width == 1024u && target.height == 512u))) return;

    const DB_VkDescriptorBufferInfo* ubo = NULL;
    for (uint32_t i = 0u; i < descriptorWriteCount && ubo == NULL; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (write->dstBinding == 0u && write->pBufferInfo != NULL
                && write->descriptorCount > 0u) {
            ubo = &write->pBufferInfo[0];
        }
    }
    if (ubo == NULL || ubo->buffer == 0u || ubo->range == 0u) return;

    uint8_t bytes[140];
    memset(bytes, 0, sizeof(bytes));
    size_t captured = 0u;
    uint64_t writeSeq = 0u;
    uint64_t srcBuffer = 0u;
    uint64_t srcOffset = 0u;
    uint64_t dstOffset = 0u;
    const char* source = "unavailable";

    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_buffer_write_record* writeRecord = db_vk_find_buffer_write_locked(ubo->buffer);
    if (writeRecord != NULL) {
        db_vk_buffer_write_record wr = *writeRecord;
        uint64_t uboStart = ubo->offset;
        uint64_t uboEnd = ubo->range == DB_VK_WHOLE_SIZE ? UINT64_MAX : uboStart + ubo->range;
        uint64_t writeEnd = wr.dstOffset + wr.size;
        bool overlaps = wr.dstOffset < uboEnd && writeEnd > uboStart;
        writeSeq = wr.sequence;
        srcBuffer = wr.srcBuffer;
        srcOffset = wr.srcOffset;
        dstOffset = wr.dstOffset;
        if (overlaps && wr.kind == DB_VK_BUFFER_WRITE_COPY && wr.srcBuffer != 0u) {
            db_vk_buffer_memory_record* srcBinding = db_vk_find_buffer_binding_locked(wr.srcBuffer);
            if (srcBinding != NULL) {
                db_vk_memory_map_record* srcMap = db_vk_find_memory_map_locked(srcBinding->memory);
                if (srcMap != NULL && srcMap->mapped != NULL) {
                    uint64_t overlapStart = wr.dstOffset > uboStart ? wr.dstOffset : uboStart;
                    uint64_t delta = overlapStart - wr.dstOffset;
                    uint64_t logical = wr.srcOffset + delta;
                    uint64_t absolute = srcBinding->memoryOffset + logical;
                    size_t want = ubo->range < sizeof(bytes) ? (size_t)ubo->range : sizeof(bytes);
                    if (want > wr.size - delta) want = (size_t)(wr.size - delta);
                    if (absolute >= srcMap->mapOffset) {
                        uint64_t relative = absolute - srcMap->mapOffset;
                        bool within = srcMap->mapSize == DB_VK_WHOLE_SIZE
                                || relative + want <= srcMap->mapSize;
                        if (within && want > 0u) {
                            memcpy(bytes, (const uint8_t*)srcMap->mapped + relative, want);
                            captured = want;
                            source = "copy-source";
                        }
                    }
                }
            }
        }
    }
    if (captured == 0u) {
        db_vk_buffer_memory_record* binding = db_vk_find_buffer_binding_locked(ubo->buffer);
        if (binding != NULL) {
            db_vk_memory_map_record* map = db_vk_find_memory_map_locked(binding->memory);
            if (map != NULL && map->mapped != NULL) {
                uint64_t absolute = binding->memoryOffset + ubo->offset;
                size_t want = ubo->range < sizeof(bytes) ? (size_t)ubo->range : sizeof(bytes);
                if (absolute >= map->mapOffset) {
                    uint64_t relative = absolute - map->mapOffset;
                    bool within = map->mapSize == DB_VK_WHOLE_SIZE
                            || relative + want <= map->mapSize;
                    if (within && want > 0u) {
                        memcpy(bytes, (const uint8_t*)map->mapped + relative, want);
                        captured = want;
                        source = "dst-mapped";
                    }
                }
            }
        }
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);

    uint64_t packet = ++g_db_vk_atlas_v4_ubo_packets;
    if (packet > 160u) return;
    fprintf(stderr,
            "DroidBridgeVulkanAtlasUboV4: packet=%llu target=%llu size=%ux%u buffer=%llu offset=%llu range=%llu source=%s captured=%zu writeSeq=%llu srcBuffer=%llu srcOffset=%llu dstOffset=%llu words=",
            (unsigned long long)packet,
            (unsigned long long)target.image,
            target.width, target.height,
            (unsigned long long)ubo->buffer,
            (unsigned long long)ubo->offset,
            (unsigned long long)ubo->range,
            source, captured,
            (unsigned long long)writeSeq,
            (unsigned long long)srcBuffer,
            (unsigned long long)srcOffset,
            (unsigned long long)dstOffset);
    size_t wordCount = captured / 4u;
    for (size_t i = 0u; i < wordCount; ++i) {
        uint32_t word = 0u;
        memcpy(&word, bytes + i * 4u, sizeof(word));
        fprintf(stderr, "%s%08x", i == 0u ? "" : ",", word);
    }
    fprintf(stderr, "\n");
    fflush(stderr);
}

static bool db_vk_flush_atlas_ubo_buffer(
        DB_VkCommandBuffer commandBuffer,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_atlas_ubo_flush_repair_enabled()
            || descriptorWrites == NULL || descriptorWriteCount == 0u) {
        return false;
    }

    db_vk_image_summary target;
    memset(&target, 0, sizeof(target));
    if (!db_vk_atlas_ubo_scope_target(commandBuffer, &target)) return false;

    const DB_VkDescriptorBufferInfo* ubo = NULL;
    for (uint32_t i = 0u; i < descriptorWriteCount && ubo == NULL; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (write->dstBinding != 0u || write->pBufferInfo == NULL
                || write->descriptorCount == 0u) {
            continue;
        }
        ubo = &write->pBufferInfo[0];
    }
    if (ubo == NULL || ubo->buffer == 0u || ubo->range == 0u) return false;

    DB_VkDevice device = NULL;
    DB_VkDeviceMemory memory = 0u;
    uint64_t bindingOffset = 0u;
    void* mapped = NULL;
    uint64_t mapOffset = 0u;
    uint64_t mapSize = 0u;
    db_vk_buffer_write_record lastWrite;
    memset(&lastWrite, 0, sizeof(lastWrite));
    bool haveWrite = false;

    pthread_mutex_lock(&g_db_vk_memory_mutex);
    db_vk_buffer_memory_record* binding = db_vk_find_buffer_binding_locked(ubo->buffer);
    if (binding != NULL) {
        device = binding->device;
        memory = binding->memory;
        bindingOffset = binding->memoryOffset;
        db_vk_memory_map_record* map = db_vk_find_memory_map_locked(memory);
        if (map != NULL) {
            mapped = map->mapped;
            mapOffset = map->mapOffset;
            mapSize = map->mapSize;
            if (device == NULL) device = map->device;
        }
    }
    db_vk_buffer_write_record* writeRecord = db_vk_find_buffer_write_locked(ubo->buffer);
    if (writeRecord != NULL) {
        lastWrite = *writeRecord;
        lastWrite.next = NULL;
        haveWrite = true;
    }
    pthread_mutex_unlock(&g_db_vk_memory_mutex);

    uint64_t uboStart = ubo->offset;
    uint64_t uboEnd = ubo->range == DB_VK_WHOLE_SIZE ? UINT64_MAX : uboStart + ubo->range;
    bool writeOverlaps = false;
    if (haveWrite) {
        uint64_t writeEnd = lastWrite.dstOffset + lastWrite.size;
        writeOverlaps = lastWrite.dstOffset < uboEnd && writeEnd > uboStart;
    }

    bool flushAttempted = false;
    bool flushSucceeded = false;
    uint64_t hash = 1469598103934665603ULL;
    uint64_t nonZeroBytes = 0u;
    uint64_t fingerprintBytes = 0u;
    bool hashed = false;
    const char* fingerprintSource = "none";

    /* Host-mapped destination UBO path, retained as a fallback. */
    uint64_t absoluteOffset = bindingOffset + ubo->offset;
    if (mapped != NULL && absoluteOffset >= mapOffset) {
        uint64_t relativeOffset = absoluteOffset - mapOffset;
        uint64_t bytes = ubo->range;
        if (bytes > 512u) bytes = 512u;
        bool withinMap = mapSize == DB_VK_WHOLE_SIZE
                || relativeOffset + bytes <= mapSize;
        if (withinMap && bytes > 0u) {
            const uint8_t* base = (const uint8_t*)mapped + relativeOffset;
            hash = db_vk_fnv1a64_update(hash, base, (size_t)bytes);
            for (uint64_t i = 0u; i < bytes; ++i) {
                if (base[i] != 0u) ++nonZeroBytes;
            }
            fingerprintBytes = bytes;
            hashed = true;
            fingerprintSource = "dst-mapped";
        }
    }

    /* Device-local UBOs are commonly fed by a mapped staging buffer. Follow the
       last vkCmdCopyBuffer source so we can flush/fingerprint the bytes that will
       become this exact 140-byte atlas UBO range. */
    if (!hashed && haveWrite && writeOverlaps && lastWrite.kind == DB_VK_BUFFER_WRITE_COPY
            && lastWrite.srcBuffer != 0u) {
        DB_VkDevice srcDevice = NULL;
        DB_VkDeviceMemory srcMemory = 0u;
        uint64_t srcBindingOffset = 0u;
        void* srcMapped = NULL;
        uint64_t srcMapOffset = 0u;
        uint64_t srcMapSize = 0u;
        pthread_mutex_lock(&g_db_vk_memory_mutex);
        db_vk_buffer_memory_record* srcBinding = db_vk_find_buffer_binding_locked(lastWrite.srcBuffer);
        if (srcBinding != NULL) {
            srcDevice = srcBinding->device;
            srcMemory = srcBinding->memory;
            srcBindingOffset = srcBinding->memoryOffset;
            db_vk_memory_map_record* srcMap = db_vk_find_memory_map_locked(srcMemory);
            if (srcMap != NULL) {
                srcMapped = srcMap->mapped;
                srcMapOffset = srcMap->mapOffset;
                srcMapSize = srcMap->mapSize;
                if (srcDevice == NULL) srcDevice = srcMap->device;
            }
        }
        pthread_mutex_unlock(&g_db_vk_memory_mutex);

        uint64_t overlapStart = lastWrite.dstOffset > uboStart ? lastWrite.dstOffset : uboStart;
        uint64_t delta = overlapStart - lastWrite.dstOffset;
        uint64_t sourceLogicalOffset = lastWrite.srcOffset + delta;
        uint64_t availableFromWrite = lastWrite.size - delta;
        uint64_t bytes = ubo->range;
        if (bytes > availableFromWrite) bytes = availableFromWrite;
        if (bytes > 512u) bytes = 512u;
        uint64_t sourceAbsoluteOffset = srcBindingOffset + sourceLogicalOffset;

        if (srcDevice != NULL && g_db_real_vkFlushMappedMemoryRanges == NULL
                && g_db_real_vkGetDeviceProcAddr != NULL) {
            g_db_real_vkFlushMappedMemoryRanges = (DB_PFN_vkFlushMappedMemoryRanges)
                    g_db_real_vkGetDeviceProcAddr(srcDevice, "vkFlushMappedMemoryRanges");
        }
        if (srcDevice != NULL && srcMemory != 0u && srcMapped != NULL
                && g_db_real_vkFlushMappedMemoryRanges != NULL
                && srcMapOffset == 0u && srcMapSize == DB_VK_WHOLE_SIZE) {
            DB_VkMappedMemoryRange flushRange;
            memset(&flushRange, 0, sizeof(flushRange));
            flushRange.sType = DB_VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            flushRange.memory = srcMemory;
            flushRange.offset = 0u;
            flushRange.size = DB_VK_WHOLE_SIZE;
            flushAttempted = true;
            flushSucceeded = g_db_real_vkFlushMappedMemoryRanges(
                    srcDevice, 1u, &flushRange) == DB_VK_SUCCESS;
            if (flushSucceeded) ++g_db_vk_atlas_ubo_source_flushes;
        }

        if (srcMapped != NULL && sourceAbsoluteOffset >= srcMapOffset && bytes > 0u) {
            uint64_t relativeOffset = sourceAbsoluteOffset - srcMapOffset;
            bool withinMap = srcMapSize == DB_VK_WHOLE_SIZE
                    || relativeOffset + bytes <= srcMapSize;
            if (withinMap) {
                const uint8_t* base = (const uint8_t*)srcMapped + relativeOffset;
                hash = 1469598103934665603ULL;
                hash = db_vk_fnv1a64_update(hash, base, (size_t)bytes);
                nonZeroBytes = 0u;
                for (uint64_t i = 0u; i < bytes; ++i) {
                    if (base[i] != 0u) ++nonZeroBytes;
                }
                fingerprintBytes = bytes;
                hashed = true;
                fingerprintSource = "copy-source";
            }
        }
    } else if (!hashed && haveWrite && writeOverlaps
            && lastWrite.kind == DB_VK_BUFFER_WRITE_UPDATE && lastWrite.updateHash != 0u) {
        hash = lastWrite.updateHash;
        nonZeroBytes = lastWrite.updateNonZeroBytes;
        fingerprintBytes = lastWrite.size > 512u ? 512u : lastWrite.size;
        hashed = true;
        fingerprintSource = "update-data";
    }

    if (!flushAttempted && device != NULL && memory != 0u && mapped != NULL
            && g_db_real_vkFlushMappedMemoryRanges != NULL
            && mapOffset == 0u && mapSize == DB_VK_WHOLE_SIZE) {
        DB_VkMappedMemoryRange flushRange;
        memset(&flushRange, 0, sizeof(flushRange));
        flushRange.sType = DB_VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        flushRange.memory = memory;
        flushRange.offset = 0u;
        flushRange.size = DB_VK_WHOLE_SIZE;
        flushAttempted = true;
        flushSucceeded = g_db_real_vkFlushMappedMemoryRanges(
                device, 1u, &flushRange) == DB_VK_SUCCESS;
        if (flushSucceeded) ++g_db_vk_atlas_ubo_flush_successes;
    }

    bool barrierIssued = false;
    DB_PFN_vkCmdPipelineBarrier2 barrier2 = g_db_real_vkCmdPipelineBarrier2;
    if (barrier2 == NULL && device != NULL && g_db_real_vkGetDeviceProcAddr != NULL) {
        barrier2 = (DB_PFN_vkCmdPipelineBarrier2)
                g_db_real_vkGetDeviceProcAddr(device, "vkCmdPipelineBarrier2");
        if (barrier2 != NULL) g_db_real_vkCmdPipelineBarrier2 = barrier2;
    }
    if (barrier2 != NULL) {
        DB_VkBufferMemoryBarrier2 bufferBarrier;
        memset(&bufferBarrier, 0, sizeof(bufferBarrier));
        bufferBarrier.sType = DB_VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2;
        if (haveWrite && writeOverlaps
                && (lastWrite.kind == DB_VK_BUFFER_WRITE_COPY
                    || lastWrite.kind == DB_VK_BUFFER_WRITE_UPDATE)) {
            bufferBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT;
            bufferBarrier.srcAccessMask = DB_VK_ACCESS_2_TRANSFER_WRITE_BIT;
        } else if (mapped != NULL) {
            bufferBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_HOST_BIT;
            bufferBarrier.srcAccessMask = DB_VK_ACCESS_2_HOST_WRITE_BIT;
        } else {
            bufferBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
            bufferBarrier.srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
        }
        bufferBarrier.dstStageMask = DB_VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
                | DB_VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
        bufferBarrier.dstAccessMask = DB_VK_ACCESS_2_UNIFORM_READ_BIT;
        bufferBarrier.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
        bufferBarrier.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
        bufferBarrier.buffer = ubo->buffer;
        bufferBarrier.offset = ubo->offset;
        bufferBarrier.size = ubo->range;

        DB_VkDependencyInfo dependency;
        memset(&dependency, 0, sizeof(dependency));
        dependency.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
        dependency.bufferMemoryBarrierCount = 1u;
        dependency.pBufferMemoryBarriers = &bufferBarrier;
        barrier2(commandBuffer, &dependency);
        barrierIssued = true;
        ++g_db_vk_atlas_ubo_buffer_barriers;
    } else if (!g_db_vk_atlas_ubo_flush_missing_barrier_logged) {
        g_db_vk_atlas_ubo_flush_missing_barrier_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanAtlasUbo: vkCmdPipelineBarrier2 unavailable; continuing with write-path audit/flush only\n");
    }

    uint64_t packet = ++g_db_vk_atlas_ubo_flush_packets;
    if (!g_db_vk_atlas_ubo_flush_repair_logged) {
        g_db_vk_atlas_ubo_flush_repair_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanAtlasUbo: Qualcomm atlas UBO transfer-visibility repair armed; device-local binding-0 buffers are correlated with vkCmdCopyBuffer/vkCmdUpdateBuffer and receive exact write->UNIFORM_READ buffer barriers before atlas draws\n");
    }
    if (packet <= 96u || (packet % 256u) == 0u) {
        const char* writeKind = !haveWrite ? "none"
                : lastWrite.kind == DB_VK_BUFFER_WRITE_COPY ? "copy"
                : lastWrite.kind == DB_VK_BUFFER_WRITE_UPDATE ? "update" : "none";
        fprintf(stderr,
                "DroidBridgeVulkanAtlasUbo: packet #%llu target=%llu size=%ux%u buffer=%llu offset=%llu range=%llu memory=%llu mapped=%d lastWrite=%s overlap=%d writeSeq=%llu srcBuffer=%llu srcOffset=%llu dstOffset=%llu writeSize=%llu flush=%s barrier=%s fingerprint=%s/%s%016llx nonZeroBytes=%llu/%llu\n",
                (unsigned long long)packet,
                (unsigned long long)target.image,
                target.width, target.height,
                (unsigned long long)ubo->buffer,
                (unsigned long long)ubo->offset,
                (unsigned long long)ubo->range,
                (unsigned long long)memory,
                mapped != NULL ? 1 : 0,
                writeKind,
                writeOverlaps ? 1 : 0,
                (unsigned long long)(haveWrite ? lastWrite.sequence : 0u),
                (unsigned long long)(haveWrite ? lastWrite.srcBuffer : 0u),
                (unsigned long long)(haveWrite ? lastWrite.srcOffset : 0u),
                (unsigned long long)(haveWrite ? lastWrite.dstOffset : 0u),
                (unsigned long long)(haveWrite ? lastWrite.size : 0u),
                flushAttempted ? (flushSucceeded ? "ok" : "failed") : "unavailable",
                barrierIssued ? "buffer-write->uniform-read" : "unavailable",
                fingerprintSource,
                hashed ? "" : "unavailable/",
                (unsigned long long)(hashed ? hash : 0u),
                (unsigned long long)nonZeroBytes,
                (unsigned long long)fingerprintBytes);
        fflush(stderr);
    }
    return flushSucceeded || barrierIssued || hashed;
}



static bool db_vk_find_atlas_source_descriptor(
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites,
        db_vk_image_summary* out) {
    if (out != NULL) memset(out, 0, sizeof(*out));
    if (descriptorWrites == NULL || out == NULL) return false;
    for (uint32_t i = 0u; i < descriptorWriteCount; ++i) {
        const DB_VkWriteDescriptorSet* write = &descriptorWrites[i];
        if (write->dstBinding != 1u || write->pImageInfo == NULL
                || write->descriptorCount == 0u) continue;
        const DB_VkDescriptorImageInfo* info = &write->pImageInfo[0];
        if (info->imageView == 0u) continue;
        if (db_vk_lookup_image_view_summary(info->imageView, out)
                && out->image != 0u && out->lastUploadBuffer != 0u
                && out->lastUploadWidth > 0u && out->lastUploadHeight > 0u) {
            return true;
        }
    }
    return false;
}

static void db_vk_capture_atlas_direct_compose_source(
        DB_VkCommandBuffer commandBuffer,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    if (!db_vk_atlas_direct_compose_repair_enabled()) return;
    db_vk_image_summary source;
    if (!db_vk_find_atlas_source_descriptor(
            descriptorWriteCount, descriptorWrites, &source)) return;

    db_vk_image_summary target;
    memset(&target, 0, sizeof(target));
    if (!db_vk_atlas_ubo_scope_target(commandBuffer, &target)) return;
    if (!((target.width == 512u && target.height == 256u)
            || (target.width == 1024u && target.height == 512u))) return;

    const DB_VkDescriptorBufferInfo* ubo = db_vk_find_atlas_binding0_ubo(
            descriptorWriteCount, descriptorWrites);
    db_vk_atlas_compose_packet decoded;
    memset(&decoded, 0, sizeof(decoded));
    decoded.source = source;
    bool placementValid = db_vk_decode_atlas_sprite_placement(
            &target, &source, ubo, &decoded);

    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* scope = db_vk_get_render_scope_locked(commandBuffer, false);
    if (scope != NULL && scope->sampledTarget && scope->firstTargetRenderCall == 1u
            && scope->firstTarget.image == target.image) {
        scope->currentAtlasSource = source;
        scope->currentAtlasDstOuterX = decoded.dstOuterX;
        scope->currentAtlasDstOuterY = decoded.dstOuterY;
        scope->currentAtlasDstOuterWidth = decoded.dstOuterWidth;
        scope->currentAtlasDstOuterHeight = decoded.dstOuterHeight;
        scope->currentAtlasDstInnerX = decoded.dstInnerX;
        scope->currentAtlasDstInnerY = decoded.dstInnerY;
        scope->currentAtlasDstInnerWidth = decoded.dstInnerWidth;
        scope->currentAtlasDstInnerHeight = decoded.dstInnerHeight;
        scope->currentAtlasUPadding = decoded.uPadding;
        scope->currentAtlasVPadding = decoded.vPadding;
        scope->currentAtlasMipMapLevel = decoded.mipMapLevel;
        scope->currentAtlasPlacementValid = placementValid;
        scope->currentAtlasSourceValid = true;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
}

static void db_vk_capture_atlas_direct_compose_draw(DB_VkCommandBuffer commandBuffer) {
    if (!db_vk_atlas_direct_compose_repair_enabled()) return;
    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* scope = db_vk_get_render_scope_locked(commandBuffer, false);
    if (scope != NULL && scope->sampledTarget && scope->firstTargetRenderCall == 1u
            && scope->currentAtlasSourceValid) {
        if (scope->atlasComposePacketCount < DB_VK_ATLAS_COMPOSE_MAX_PACKETS) {
            db_vk_atlas_compose_packet* packet =
                    &scope->atlasComposePackets[scope->atlasComposePacketCount++];
            memset(packet, 0, sizeof(*packet));
            packet->source = scope->currentAtlasSource;
            packet->dstOuterX = scope->currentAtlasDstOuterX;
            packet->dstOuterY = scope->currentAtlasDstOuterY;
            packet->dstOuterWidth = scope->currentAtlasDstOuterWidth;
            packet->dstOuterHeight = scope->currentAtlasDstOuterHeight;
            packet->dstInnerX = scope->currentAtlasDstInnerX;
            packet->dstInnerY = scope->currentAtlasDstInnerY;
            packet->dstInnerWidth = scope->currentAtlasDstInnerWidth;
            packet->dstInnerHeight = scope->currentAtlasDstInnerHeight;
            packet->uPadding = scope->currentAtlasUPadding;
            packet->vPadding = scope->currentAtlasVPadding;
            packet->mipMapLevel = scope->currentAtlasMipMapLevel;
            packet->placementValid = scope->currentAtlasPlacementValid;
        } else {
            scope->atlasComposeOverflow = true;
        }
        scope->currentAtlasSourceValid = false;
        scope->currentAtlasPlacementValid = false;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
}

static void db_vk_cmd_draw(
        DB_VkCommandBuffer commandBuffer, uint32_t vertexCount,
        uint32_t instanceCount, uint32_t firstVertex, uint32_t firstInstance) {
    db_vk_capture_atlas_direct_compose_draw(commandBuffer);
    if (g_db_real_vkCmdDraw != NULL) {
        g_db_real_vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
    }
}

static bool db_vk_round_float_to_int(float value, int32_t* out) {
    if (out == NULL || value < -65536.0f || value > 65536.0f) return false;
    int32_t rounded = (int32_t)(value >= 0.0f ? value + 0.5f : value - 0.5f);
    float diff = value - (float)rounded;
    if (diff < 0.0f) diff = -diff;
    if (diff > 0.05f) return false;
    *out = rounded;
    return true;
}

static bool db_vk_atlas_packet_destination(
        const db_vk_atlas_compose_packet* packet,
        const db_vk_image_summary* target) {
    if (packet == NULL || target == NULL || !packet->placementValid) return false;
    if (packet->dstInnerWidth == 0u || packet->dstInnerHeight == 0u
            || packet->dstOuterWidth < packet->dstInnerWidth
            || packet->dstOuterHeight < packet->dstInnerHeight) return false;
    if (packet->dstOuterX < 0 || packet->dstOuterY < 0) return false;
    if ((uint64_t)packet->dstOuterX + packet->dstOuterWidth > target->width
            || (uint64_t)packet->dstOuterY + packet->dstOuterHeight > target->height) return false;
    if ((uint64_t)packet->dstInnerX + packet->dstInnerWidth > target->width
            || (uint64_t)packet->dstInnerY + packet->dstInnerHeight > target->height) return false;
    return true;
}

static void db_vk_init_image_copy_region(
        DB_VkImageCopy* region,
        int32_t srcX, int32_t srcY,
        int32_t dstX, int32_t dstY,
        uint32_t width, uint32_t height) {
    memset(region, 0, sizeof(*region));
    region->srcSubresource.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    region->srcSubresource.mipLevel = 0u;
    region->srcSubresource.baseArrayLayer = 0u;
    region->srcSubresource.layerCount = 1u;
    region->srcOffset.x = srcX;
    region->srcOffset.y = srcY;
    region->srcOffset.z = 0;
    region->dstSubresource = region->srcSubresource;
    region->dstOffset.x = dstX;
    region->dstOffset.y = dstY;
    region->dstOffset.z = 0;
    region->extent.width = width;
    region->extent.height = height;
    region->extent.depth = 1u;
}

static bool db_vk_replay_atlas_source_image_packet(
        DB_VkCommandBuffer commandBuffer,
        const db_vk_image_summary* target,
        const db_vk_atlas_compose_packet* packet,
        uint32_t index) {
    if (target == NULL || packet == NULL || g_db_real_vkCmdCopyImage == NULL
            || g_db_real_vkCmdPipelineBarrier2 == NULL
            || packet->source.image == 0u
            || (packet->source.usage & DB_VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0u
            || (target->usage & DB_VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0u
            || !db_vk_atlas_packet_destination(packet, target)) {
        ++g_db_vk_atlas_direct_compose_unresolved;
        if (index < 16u) {
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasComposeV5: unresolved packet=%u target=%llu(%ux%u) sourceImage=%llu source=%ux%u upload=%ux%u placementValid=%d outer=%d,%d %ux%u inner=%u,%u %ux%u padding=%.6f,%.6f mip=%d\n",
                    index + 1u, (unsigned long long)(target != NULL ? target->image : 0u),
                    target != NULL ? target->width : 0u,
                    target != NULL ? target->height : 0u,
                    (unsigned long long)(packet != NULL ? packet->source.image : 0u),
                    packet != NULL ? packet->source.width : 0u,
                    packet != NULL ? packet->source.height : 0u,
                    packet != NULL ? packet->source.lastUploadWidth : 0u,
                    packet != NULL ? packet->source.lastUploadHeight : 0u,
                    packet != NULL && packet->placementValid ? 1 : 0,
                    packet != NULL ? packet->dstOuterX : 0,
                    packet != NULL ? packet->dstOuterY : 0,
                    packet != NULL ? packet->dstOuterWidth : 0u,
                    packet != NULL ? packet->dstOuterHeight : 0u,
                    packet != NULL ? packet->dstInnerX : 0u,
                    packet != NULL ? packet->dstInnerY : 0u,
                    packet != NULL ? packet->dstInnerWidth : 0u,
                    packet != NULL ? packet->dstInnerHeight : 0u,
                    packet != NULL ? packet->uPadding : 0.0f,
                    packet != NULL ? packet->vPadding : 0.0f,
                    packet != NULL ? packet->mipMapLevel : -1);
            fflush(stderr);
        }
        return false;
    }

    uint32_t sourceWidth = packet->dstInnerWidth;
    uint32_t sourceHeight = packet->dstInnerHeight;
    if (sourceWidth > packet->source.width || sourceHeight > packet->source.height) return false;

    DB_VkImageMemoryBarrier2 sourceBarrier;
    memset(&sourceBarrier, 0, sizeof(sourceBarrier));
    sourceBarrier.sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
    sourceBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    sourceBarrier.srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
    sourceBarrier.dstStageMask = DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT;
    sourceBarrier.dstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;
    sourceBarrier.oldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    sourceBarrier.newLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    sourceBarrier.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    sourceBarrier.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    sourceBarrier.image = packet->source.image;
    sourceBarrier.subresourceRange.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    sourceBarrier.subresourceRange.baseMipLevel = 0u;
    sourceBarrier.subresourceRange.levelCount = 1u;
    sourceBarrier.subresourceRange.baseArrayLayer = 0u;
    sourceBarrier.subresourceRange.layerCount = 1u;
    DB_VkDependencyInfo dep;
    memset(&dep, 0, sizeof(dep));
    dep.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
    dep.imageMemoryBarrierCount = 1u;
    dep.pImageMemoryBarriers = &sourceBarrier;
    g_db_real_vkCmdPipelineBarrier2(commandBuffer, &dep);

    DB_VkImageCopy regions[9];
    uint32_t regionCount = 0u;
    int32_t innerX = (int32_t)packet->dstInnerX;
    int32_t innerY = (int32_t)packet->dstInnerY;
    db_vk_init_image_copy_region(&regions[regionCount++],
            0, 0, innerX, innerY, sourceWidth, sourceHeight);

    uint32_t leftBorder = packet->dstInnerX - (uint32_t)packet->dstOuterX;
    uint32_t topBorder = packet->dstInnerY - (uint32_t)packet->dstOuterY;
    uint32_t rightBorder = packet->dstOuterWidth - leftBorder - sourceWidth;
    uint32_t bottomBorder = packet->dstOuterHeight - topBorder - sourceHeight;
    if (leftBorder > 1u || rightBorder > 1u || topBorder > 1u || bottomBorder > 1u) {
        return false;
    }

    // Mojang's atlas shader uses REPEAT with UV padding. Recreate the one-pixel
    // wrap border exactly so the direct copy preserves edge sampling semantics.
    if (leftBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                (int32_t)sourceWidth - 1, 0,
                packet->dstOuterX, innerY, 1u, sourceHeight);
    }
    if (rightBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                0, 0, innerX + (int32_t)sourceWidth, innerY, 1u, sourceHeight);
    }
    if (topBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                0, (int32_t)sourceHeight - 1,
                innerX, packet->dstOuterY, sourceWidth, 1u);
    }
    if (bottomBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                0, 0,
                innerX, innerY + (int32_t)sourceHeight, sourceWidth, 1u);
    }
    if (leftBorder == 1u && topBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                (int32_t)sourceWidth - 1, (int32_t)sourceHeight - 1,
                packet->dstOuterX, packet->dstOuterY, 1u, 1u);
    }
    if (rightBorder == 1u && topBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                0, (int32_t)sourceHeight - 1,
                innerX + (int32_t)sourceWidth, packet->dstOuterY, 1u, 1u);
    }
    if (leftBorder == 1u && bottomBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                (int32_t)sourceWidth - 1, 0,
                packet->dstOuterX, innerY + (int32_t)sourceHeight, 1u, 1u);
    }
    if (rightBorder == 1u && bottomBorder == 1u) {
        db_vk_init_image_copy_region(&regions[regionCount++],
                0, 0,
                innerX + (int32_t)sourceWidth,
                innerY + (int32_t)sourceHeight, 1u, 1u);
    }

    g_db_real_vkCmdCopyImage(commandBuffer,
            packet->source.image, DB_VK_IMAGE_LAYOUT_GENERAL,
            target->image, DB_VK_IMAGE_LAYOUT_GENERAL,
            regionCount, regions);

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* srcRecord = db_vk_find_image_locked(packet->source.image);
    db_vk_image_record* dstRecord = db_vk_find_image_locked(target->image);
    if (srcRecord != NULL) {
        ++srcRecord->imageCopyReadCalls;
        ++srcRecord->barrierCalls;
    }
    if (dstRecord != NULL) ++dstRecord->imageCopyWriteCalls;
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    ++g_db_vk_atlas_direct_compose_copies;
    if (index < 20u || (index % 32u) == 31u) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasComposeV5: replay #%llu packet=%u target=%llu outer=%d,%d %ux%u inner=%u,%u %ux%u regions=%u sourceImage=%llu sourceUsage=0x%x placement=SpriteMatrix\n",
                (unsigned long long)g_db_vk_atlas_direct_compose_copies,
                index + 1u, (unsigned long long)target->image,
                packet->dstOuterX, packet->dstOuterY,
                packet->dstOuterWidth, packet->dstOuterHeight,
                packet->dstInnerX, packet->dstInnerY,
                packet->dstInnerWidth, packet->dstInnerHeight,
                regionCount,
                (unsigned long long)packet->source.image,
                packet->source.usage);
        fflush(stderr);
    }
    return true;
}

static uint32_t db_vk_rebuild_static_atlas_from_source_images(
        DB_VkCommandBuffer commandBuffer,
        const db_vk_image_summary* target,
        const db_vk_atlas_compose_packet* packets,
        uint32_t packetCount) {
    if (!db_vk_atlas_direct_compose_repair_enabled() || target == NULL
            || packets == NULL || packetCount == 0u
            || g_db_real_vkCmdPipelineBarrier2 == NULL
            || g_db_real_vkCmdCopyImage == NULL) return 0u;

    DB_VkImageMemoryBarrier2 before;
    memset(&before, 0, sizeof(before));
    before.sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
    before.srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    before.srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
    before.dstStageMask = DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT;
    before.dstAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
    before.oldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    before.newLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    before.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    before.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    before.image = target->image;
    before.subresourceRange.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    before.subresourceRange.baseMipLevel = 0u;
    before.subresourceRange.levelCount = 1u;
    before.subresourceRange.baseArrayLayer = 0u;
    before.subresourceRange.layerCount = 1u;
    DB_VkDependencyInfo dep;
    memset(&dep, 0, sizeof(dep));
    dep.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
    dep.imageMemoryBarrierCount = 1u;
    dep.pImageMemoryBarriers = &before;
    g_db_real_vkCmdPipelineBarrier2(commandBuffer, &dep);

    uint32_t copied = 0u;
    for (uint32_t i = 0u; i < packetCount; ++i) {
        if (db_vk_replay_atlas_source_image_packet(commandBuffer, target, &packets[i], i)) {
            ++copied;
        }
    }

    if (copied > 0u) {
        DB_VkImageMemoryBarrier2 after;
        memset(&after, 0, sizeof(after));
        after.sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
        after.srcStageMask = DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT;
        after.srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
        after.dstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        after.dstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;
        after.oldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
        after.newLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
        after.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
        after.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
        after.image = target->image;
        after.subresourceRange = before.subresourceRange;
        dep.pImageMemoryBarriers = &after;
        g_db_real_vkCmdPipelineBarrier2(commandBuffer, &dep);
        pthread_mutex_lock(&g_db_vk_image_mutex);
        db_vk_image_record* targetRecord = db_vk_find_image_locked(target->image);
        if (targetRecord != NULL) targetRecord->barrierCalls += 2u;
        pthread_mutex_unlock(&g_db_vk_image_mutex);
    }
    return copied;
}

static void db_vk_count_atlas_image_copy_packet(DB_VkCommandBuffer commandBuffer) {
    if (!db_vk_atlas_image_copy_test_enabled()) return;
    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* scope = db_vk_get_render_scope_locked(commandBuffer, false);
    if (scope != NULL && scope->sampledTarget && scope->firstTargetRenderCall == 1u
            && (scope->firstTarget.width == 512u || scope->firstTarget.width == 1024u)
            && (scope->firstTarget.height == 256u || scope->firstTarget.height == 512u)) {
        ++scope->atlasImageCopyPackets;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
}

static void db_vk_cmd_push_descriptor_set_khr(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint pipelineBindPoint,
        DB_VkPipelineLayout layout,
        uint32_t set,
        uint32_t descriptorWriteCount,
        const DB_VkWriteDescriptorSet* descriptorWrites) {
    DB_PFN_vkCmdPushDescriptorSetKHR real = g_db_real_vkCmdPushDescriptorSetKHR;
    if (real == NULL) return;
    if (!db_vk_push_descriptor_fix_enabled()) {
        db_vk_observe_push_descriptor_writes(set, descriptorWriteCount, descriptorWrites);
        db_vk_log_atlas_build_input_packet(
                commandBuffer, pipelineBindPoint, layout,
                set, descriptorWriteCount, descriptorWrites);
        db_vk_log_entity_aux_descriptor_packet(
                commandBuffer, pipelineBindPoint, layout,
                set, descriptorWriteCount, descriptorWrites);
        db_vk_capture_atlas_direct_compose_source(
                commandBuffer, descriptorWriteCount, descriptorWrites);
        db_vk_count_atlas_image_copy_packet(commandBuffer);
        db_vk_log_atlas_ubo_words_v4(
                commandBuffer, descriptorWriteCount, descriptorWrites);
        db_vk_flush_atlas_ubo_buffer(
                commandBuffer, descriptorWriteCount, descriptorWrites);
        db_vk_emit_special_draw_host_visibility_barrier(
                commandBuffer, descriptorWriteCount, descriptorWrites);

        DB_VkWriteDescriptorSet* atlasSwapWrites = NULL;
        DB_VkDescriptorImageInfo atlasSwapInfo;
        memset(&atlasSwapInfo, 0, sizeof(atlasSwapInfo));
        if (db_vk_try_entity_known_good_atlas_swap(
                commandBuffer, pipelineBindPoint,
                descriptorWriteCount, descriptorWrites,
                &atlasSwapWrites, &atlasSwapInfo)) {
            real(commandBuffer, pipelineBindPoint, layout, set,
                 descriptorWriteCount, atlasSwapWrites);
            free(atlasSwapWrites);
            return;
        }

        DB_VkWriteDescriptorSet* spriteLayoutWrites = NULL;
        DB_VkDescriptorImageInfo* spriteLayoutInfos = NULL;
        if (db_vk_rewrite_sprite_upload_descriptor_layouts(
                descriptorWriteCount, descriptorWrites,
                &spriteLayoutWrites, &spriteLayoutInfos)) {
            real(commandBuffer, pipelineBindPoint, layout, set,
                 descriptorWriteCount, spriteLayoutWrites);
            free(spriteLayoutInfos);
            free(spriteLayoutWrites);
            return;
        }

        DB_VkWriteDescriptorSet* layoutWrites = NULL;
        DB_VkDescriptorImageInfo* layoutInfos = NULL;
        if (db_vk_rewrite_static_atlas_descriptor_layouts(
                descriptorWriteCount, descriptorWrites,
                &layoutWrites, &layoutInfos)) {
            real(commandBuffer, pipelineBindPoint, layout, set,
                 descriptorWriteCount, layoutWrites);
            free(layoutInfos);
            free(layoutWrites);
            return;
        }

        real(commandBuffer, pipelineBindPoint, layout, set,
             descriptorWriteCount, descriptorWrites);
        return;
    }
    if (descriptorWriteCount == 0u || descriptorWrites == NULL) {
        real(commandBuffer, pipelineBindPoint, layout, set,
             descriptorWriteCount, descriptorWrites);
        return;
    }

    for (uint32_t i = 0; i < descriptorWriteCount; ++i) {
        if (!db_vk_write_supported(&descriptorWrites[i])) {
            if (!g_db_vk_unsupported_push_logged) {
                g_db_vk_unsupported_push_logged = true;
                fprintf(stderr,
                        "DroidBridgeVulkanCompat: bypassed unsupported push descriptor type=%d pNext=%p\n",
                        descriptorWrites[i].descriptorType,
                        descriptorWrites[i].pNext);
            }
            real(commandBuffer, pipelineBindPoint, layout, set,
                 descriptorWriteCount, descriptorWrites);
            return;
        }
    }

    DB_VkWriteDescriptorSet* normalizedWrites = NULL;
    DB_VkDescriptorImageInfo* imageInfos = NULL;
    DB_VkDescriptorBufferInfo* bufferInfos = NULL;
    DB_VkBufferView* texelViews = NULL;
    db_vk_descriptor_entry** orderedEntries = NULL;
    size_t entryCount = 0u;
    bool success = false;

    pthread_mutex_lock(&g_db_vk_push_mutex);
    db_vk_push_state* state = db_vk_find_or_create_state_locked(
            commandBuffer, pipelineBindPoint, layout, set);
    if (state != NULL) {
        success = true;
        for (uint32_t i = 0; i < descriptorWriteCount; ++i) {
            if (!db_vk_apply_write_locked(state, &descriptorWrites[i])) {
                success = false;
                break;
            }
        }
    }

    if (success) {
        entryCount = db_vk_count_entries(state);
        if (entryCount > 0u) {
            normalizedWrites = (DB_VkWriteDescriptorSet*)calloc(
                    entryCount, sizeof(DB_VkWriteDescriptorSet));
            imageInfos = (DB_VkDescriptorImageInfo*)calloc(
                    entryCount, sizeof(DB_VkDescriptorImageInfo));
            bufferInfos = (DB_VkDescriptorBufferInfo*)calloc(
                    entryCount, sizeof(DB_VkDescriptorBufferInfo));
            texelViews = (DB_VkBufferView*)calloc(entryCount, sizeof(DB_VkBufferView));
            orderedEntries = (db_vk_descriptor_entry**)calloc(
                    entryCount, sizeof(db_vk_descriptor_entry*));
            success = normalizedWrites != NULL && imageInfos != NULL
                    && bufferInfos != NULL && texelViews != NULL
                    && orderedEntries != NULL;
        }
    }

    if (success && entryCount > 0u) {
        size_t index = 0u;
        for (db_vk_descriptor_entry* entry = state->entries;
             entry != NULL && index < entryCount;
             entry = entry->next) {
            orderedEntries[index++] = entry;
        }
        qsort(orderedEntries, entryCount, sizeof(db_vk_descriptor_entry*),
              db_vk_compare_entry_ptrs);

        for (size_t i = 0u; i < entryCount; ++i) {
            const db_vk_descriptor_entry* entry = orderedEntries[i];
            DB_VkWriteDescriptorSet* write = &normalizedWrites[i];
            write->sType = DB_VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write->pNext = NULL;
            write->dstSet = 0u;
            write->dstBinding = entry->binding;
            write->dstArrayElement = entry->arrayElement;
            write->descriptorCount = 1u;
            write->descriptorType = entry->descriptorType;
            if (entry->payloadKind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE) {
                imageInfos[i] = entry->imageInfo;
                write->pImageInfo = &imageInfos[i];
            } else if (entry->payloadKind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER) {
                bufferInfos[i] = entry->bufferInfo;
                write->pBufferInfo = &bufferInfos[i];
            } else if (entry->payloadKind == DB_VK_DESCRIPTOR_PAYLOAD_TEXEL) {
                texelViews[i] = entry->texelBufferView;
                write->pTexelBufferView = &texelViews[i];
            }
        }
    }
    pthread_mutex_unlock(&g_db_vk_push_mutex);

    if (!success || entryCount == 0u) {
        free(normalizedWrites);
        free(imageInfos);
        free(bufferInfos);
        free(texelViews);
        free(orderedEntries);
        real(commandBuffer, pipelineBindPoint, layout, set,
             descriptorWriteCount, descriptorWrites);
        return;
    }

    size_t imageEntryCount = 0u;
    size_t bufferEntryCount = 0u;
    size_t texelEntryCount = 0u;
    for (size_t i = 0u; i < entryCount; ++i) {
        const db_vk_descriptor_entry* entry = orderedEntries[i];
        if (entry->payloadKind == DB_VK_DESCRIPTOR_PAYLOAD_IMAGE) {
            ++imageEntryCount;
            db_vk_log_descriptor_image_once(
                    entry->imageInfo.imageView,
                    entry->imageInfo.imageLayout,
                    entry->binding,
                    entry->arrayElement,
                    entry->descriptorType);
            bool suspicious = false;
            if (entry->descriptorType == DB_VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
                suspicious = entry->imageInfo.sampler == 0u
                        || entry->imageInfo.imageView == 0u
                        || entry->imageInfo.imageLayout == 0;
            } else if (entry->descriptorType == DB_VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                    || entry->descriptorType == DB_VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                    || entry->descriptorType == DB_VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT) {
                suspicious = entry->imageInfo.imageView == 0u
                        || entry->imageInfo.imageLayout == 0;
            } else if (entry->descriptorType == DB_VK_DESCRIPTOR_TYPE_SAMPLER) {
                suspicious = entry->imageInfo.sampler == 0u;
            }
            if (suspicious) {
                ++g_db_vk_suspicious_image_descriptors;
                if (g_db_vk_suspicious_image_descriptors <= 16u) {
                    fprintf(stderr,
                            "DroidBridgeVulkanCompat: suspicious image descriptor #%llu binding=%u array=%u type=%d sampler=%llu view=%llu layout=%d\n",
                            (unsigned long long)g_db_vk_suspicious_image_descriptors,
                            entry->binding,
                            entry->arrayElement,
                            entry->descriptorType,
                            (unsigned long long)entry->imageInfo.sampler,
                            (unsigned long long)entry->imageInfo.imageView,
                            entry->imageInfo.imageLayout);
                }
            }
        } else if (entry->payloadKind == DB_VK_DESCRIPTOR_PAYLOAD_BUFFER) {
            ++bufferEntryCount;
        } else if (entry->payloadKind == DB_VK_DESCRIPTOR_PAYLOAD_TEXEL) {
            ++texelEntryCount;
        }
    }

    real(commandBuffer, pipelineBindPoint, layout, set,
         (uint32_t)entryCount, normalizedWrites);
    ++g_db_vk_normalized_push_calls;
    if (g_db_vk_normalized_push_calls == 1u
            || (g_db_vk_normalized_push_calls % 65536u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanCompat: normalized push descriptors call=%llu incoming=%u complete=%zu images=%zu buffers=%zu texels=%zu set=%u suspiciousImages=%llu\n",
                (unsigned long long)g_db_vk_normalized_push_calls,
                descriptorWriteCount,
                entryCount,
                imageEntryCount,
                bufferEntryCount,
                texelEntryCount,
                set,
                (unsigned long long)g_db_vk_suspicious_image_descriptors);
    }

    free(normalizedWrites);
    free(imageInfos);
    free(bufferInfos);
    free(texelViews);
    free(orderedEntries);
}

static DB_VkResult db_vk_begin_command_buffer(
        DB_VkCommandBuffer commandBuffer, const void* beginInfo) {
    db_vk_clear_command_buffer(commandBuffer);
    return g_db_real_vkBeginCommandBuffer != NULL
            ? g_db_real_vkBeginCommandBuffer(commandBuffer, beginInfo)
            : DB_VK_ERROR_INITIALIZATION_FAILED;
}

static DB_VkResult db_vk_reset_command_buffer(
        DB_VkCommandBuffer commandBuffer, uint32_t flags) {
    db_vk_clear_command_buffer(commandBuffer);
    return g_db_real_vkResetCommandBuffer != NULL
            ? g_db_real_vkResetCommandBuffer(commandBuffer, flags)
            : DB_VK_ERROR_INITIALIZATION_FAILED;
}

static void db_vk_free_command_buffers(
        DB_VkDevice device,
        DB_VkCommandPool commandPool,
        uint32_t commandBufferCount,
        const DB_VkCommandBuffer* commandBuffers) {
    if (commandBuffers != NULL) {
        for (uint32_t i = 0; i < commandBufferCount; ++i) {
            db_vk_clear_command_buffer(commandBuffers[i]);
        }
    }
    if (g_db_real_vkFreeCommandBuffers != NULL) {
        g_db_real_vkFreeCommandBuffers(
                device, commandPool, commandBufferCount, commandBuffers);
    }
}

static DB_VkResult db_vk_enumerate_device_extension_properties(
        DB_VkPhysicalDevice physicalDevice,
        const char* layerName,
        uint32_t* propertyCount,
        DB_VkExtensionProperties* properties) {
    DB_PFN_vkEnumerateDeviceExtensionProperties real =
            g_db_real_vkEnumerateDeviceExtensionProperties;
    if (real == NULL || propertyCount == NULL) {
        return DB_VK_ERROR_INITIALIZATION_FAILED;
    }
    const bool filterMultiDraw = db_vk_disable_multi_draw_enabled();
    const bool filterVertexDivisor = false; /* Minecraft 26.2 requires this extension by name. */
    if ((!filterMultiDraw && !filterVertexDivisor) || layerName != NULL) {
        return real(physicalDevice, layerName, propertyCount, properties);
    }

    uint32_t fullCount = 0u;
    DB_VkResult result = real(physicalDevice, NULL, &fullCount, NULL);
    if (result != DB_VK_SUCCESS && result != DB_VK_INCOMPLETE) return result;
    if (fullCount == 0u) {
        *propertyCount = 0u;
        return DB_VK_SUCCESS;
    }

    DB_VkExtensionProperties* full = (DB_VkExtensionProperties*)calloc(
            fullCount, sizeof(DB_VkExtensionProperties));
    if (full == NULL) return real(physicalDevice, layerName, propertyCount, properties);
    uint32_t returnedCount = fullCount;
    result = real(physicalDevice, NULL, &returnedCount, full);
    if (result != DB_VK_SUCCESS && result != DB_VK_INCOMPLETE) {
        free(full);
        return result;
    }

    uint32_t filteredCount = 0u;
    for (uint32_t i = 0; i < returnedCount; ++i) {
        if (filterMultiDraw
                && strcmp(full[i].extensionName, "VK_EXT_multi_draw") == 0) {
            if (!g_db_vk_multi_draw_logged) {
                g_db_vk_multi_draw_logged = true;
                fprintf(stderr,
                        "DroidBridgeVulkanCompat: hiding VK_EXT_multi_draw from Minecraft\n");
                fflush(stderr);
            }
            continue;
        }
        if (filterVertexDivisor
                && strcmp(full[i].extensionName, "VK_EXT_vertex_attribute_divisor") == 0) {
            if (!g_db_vk_zero_divisor_logged) {
                g_db_vk_zero_divisor_logged = true;
                fprintf(stderr,
                        "DroidBridgeVulkanCompat: hiding VK_EXT_vertex_attribute_divisor from Minecraft; forcing fallback vertex-input path\n");
                fflush(stderr);
            }
            continue;
        }
        full[filteredCount++] = full[i];
    }

    if (properties == NULL) {
        *propertyCount = filteredCount;
        free(full);
        return DB_VK_SUCCESS;
    }

    uint32_t capacity = *propertyCount;
    uint32_t copied = capacity < filteredCount ? capacity : filteredCount;
    if (copied > 0u) memcpy(properties, full, copied * sizeof(DB_VkExtensionProperties));
    *propertyCount = copied;
    free(full);
    return copied < filteredCount ? DB_VK_INCOMPLETE : DB_VK_SUCCESS;
}

static void db_vk_apply_properties_compat(DB_VkPhysicalDevicePropertiesPrefix* properties) {
    if (properties == NULL) return;
    if (!g_db_vk_device_logged) {
        g_db_vk_device_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: device vendor=0x%04x device=0x%04x api=0x%08x name=%s\n",
                properties->vendorID,
                properties->deviceID,
                properties->apiVersion,
                properties->deviceName);
    }
    if (db_vk_api_cap_enabled() && properties->apiVersion > DB_VK_API_VERSION_1_3) {
        uint32_t original = properties->apiVersion;
        properties->apiVersion = DB_VK_API_VERSION_1_3;
        if (!g_db_vk_api_cap_logged) {
            g_db_vk_api_cap_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanCompat: capped reported Vulkan API 0x%08x -> 0x%08x\n",
                    original,
                    properties->apiVersion);
        }
    }
}

static void db_vk_get_physical_device_properties(
        DB_VkPhysicalDevice physicalDevice,
        DB_VkPhysicalDevicePropertiesPrefix* properties) {
    if (g_db_real_vkGetPhysicalDeviceProperties != NULL) {
        g_db_real_vkGetPhysicalDeviceProperties(physicalDevice, properties);
        db_vk_apply_properties_compat(properties);
    }
}

static void db_vk_get_physical_device_properties2(
        DB_VkPhysicalDevice physicalDevice,
        DB_VkPhysicalDeviceProperties2Prefix* properties) {
    if (g_db_real_vkGetPhysicalDeviceProperties2 != NULL) {
        g_db_real_vkGetPhysicalDeviceProperties2(physicalDevice, properties);
        if (properties != NULL) db_vk_apply_properties_compat(&properties->properties);
    }
}

static void db_vk_apply_zero_divisor_compat(
        DB_VkPhysicalDeviceFeatures2Prefix* features) {
    if (!db_vk_disable_zero_divisor_enabled() || features == NULL) return;

    DB_VkBaseOutStructure* node = (DB_VkBaseOutStructure*)features->pNext;
    uint32_t guard = 0u;
    while (node != NULL && guard++ < 128u) {
        if (node->sType ==
                DB_VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES) {
            DB_VkPhysicalDeviceVertexAttributeDivisorFeatures* divisor =
                    (DB_VkPhysicalDeviceVertexAttributeDivisorFeatures*)node;
            uint32_t originalDivisor = divisor->vertexAttributeInstanceRateDivisor;
            uint32_t originalZero = divisor->vertexAttributeInstanceRateZeroDivisor;
            divisor->vertexAttributeInstanceRateZeroDivisor = 0u;
            if (!g_db_vk_zero_divisor_logged) {
                g_db_vk_zero_divisor_logged = true;
                fprintf(stderr,
                        "DroidBridgeVulkanCompat: vertex divisor features divisor=%u zeroDivisor=%u -> zeroDivisor=0\n",
                        originalDivisor,
                        originalZero);
                fflush(stderr);
            }
            return;
        }
        node = (DB_VkBaseOutStructure*)node->pNext;
    }

    if (!g_db_vk_zero_divisor_logged) {
        g_db_vk_zero_divisor_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: vertex-divisor fallback active; feature struct was not queried (extension filtering is authoritative)\n");
        fflush(stderr);
    }
}

static void db_vk_get_physical_device_features2(
        DB_VkPhysicalDevice physicalDevice,
        DB_VkPhysicalDeviceFeatures2Prefix* features) {
    if (g_db_real_vkGetPhysicalDeviceFeatures2 != NULL) {
        g_db_real_vkGetPhysicalDeviceFeatures2(physicalDevice, features);
        db_vk_apply_zero_divisor_compat(features);
    }
}

static void db_vk_log_shader_toolchain_once(void) {
    if (g_db_vk_shader_toolchain_logged || !db_vk_pipeline_diagnostics_enabled()) return;
    g_db_vk_shader_toolchain_logged = true;

    typedef void (*db_shaderc_get_spv_version_fn)(unsigned int*, unsigned int*);
    typedef void (*db_spvc_get_version_fn)(unsigned int*, unsigned int*, unsigned int*);
    typedef const char* (*db_spvc_get_commit_fn)(void);

    db_shaderc_get_spv_version_fn shadercGetSpvVersion =
            (db_shaderc_get_spv_version_fn)dlsym(RTLD_DEFAULT, "shaderc_get_spv_version");
    db_spvc_get_version_fn spvcGetVersion =
            (db_spvc_get_version_fn)dlsym(RTLD_DEFAULT, "spvc_get_version");
    db_spvc_get_commit_fn spvcGetCommit =
            (db_spvc_get_commit_fn)dlsym(RTLD_DEFAULT, "spvc_get_commit_revision_and_timestamp");

    unsigned int spvVersion = 0u;
    unsigned int spvRevision = 0u;
    if (shadercGetSpvVersion != NULL) {
        shadercGetSpvVersion(&spvVersion, &spvRevision);
    }

    unsigned int spvcMajor = 0u;
    unsigned int spvcMinor = 0u;
    unsigned int spvcPatch = 0u;
    if (spvcGetVersion != NULL) {
        spvcGetVersion(&spvcMajor, &spvcMinor, &spvcPatch);
    }
    const char* commit = spvcGetCommit != NULL ? spvcGetCommit() : NULL;

    fprintf(stderr,
            "DroidBridgeVulkanPipeline: shader toolchain shadercSpv=0x%x revision=%u "
            "spvcRuntime=%u.%u.%u spvcJavaBindingExpected=0.68.0 commit=%s "
            "shadercSymbol=%p spvcSymbol=%p\n",
            spvVersion,
            spvRevision,
            spvcMajor,
            spvcMinor,
            spvcPatch,
            commit != NULL && commit[0] != '\0' ? commit : "<unavailable>",
            (void*)shadercGetSpvVersion,
            (void*)spvcGetVersion);
    fflush(stderr);
}

static const DB_VkPipelineVertexInputDivisorStateCreateInfoEXT*
db_vk_find_vertex_divisor_state(const DB_VkPipelineVertexInputStateCreateInfo* vertexInput) {
    if (vertexInput == NULL) return NULL;
    const DB_VkBaseInStructure* node = (const DB_VkBaseInStructure*)vertexInput->pNext;
    unsigned int guard = 0u;
    while (node != NULL && guard++ < 32u) {
        if (node->sType == DB_VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_DIVISOR_STATE_CREATE_INFO_EXT) {
            return (const DB_VkPipelineVertexInputDivisorStateCreateInfoEXT*)node;
        }
        node = (const DB_VkBaseInStructure*)node->pNext;
    }
    return NULL;
}

static DB_VkResult db_vk_create_shader_module(
        DB_VkDevice device,
        const DB_VkShaderModuleCreateInfo* createInfo,
        const void* allocator,
        DB_VkShaderModule* shaderModule) {
    DB_PFN_vkCreateShaderModule real = g_db_real_vkCreateShaderModule;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;

    const DB_VkShaderModuleCreateInfo* effectiveCreateInfo = createInfo;
    DB_VkShaderModuleCreateInfo replacementCreateInfo;
    uint64_t replacementHash = 0u;
    uint64_t inputHash = 0u;
    uint32_t* ownedReplacementCode = NULL;

    if (db_vk_entity_shader_fallback_enabled()) {
        db_vk_load_persisted_entity_shader_cache_once();
    }

    if (createInfo != NULL && createInfo->pCode != NULL && createInfo->codeSize > 0u) {
        inputHash = db_vk_fnv1a64(createInfo->pCode, createInfo->codeSize);
        if (db_vk_atlas_fragment_repair_enabled()
                && inputHash == DB_VK_ATLAS_BUILDER_VERTEX_HASH) {
            db_vk_log_atlas_builder_vertex_v4(createInfo);
        }
        if (db_vk_atlas_fragment_repair_enabled()
                && inputHash == DB_VK_ATLAS_BUILDER_FRAGMENT_HASH) {
            if (db_vk_rewrite_atlas_builder_fragment(
                    createInfo, &replacementCreateInfo, &replacementHash,
                    &ownedReplacementCode)) {
                effectiveCreateInfo = &replacementCreateInfo;
            } else {
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasCoverageV4: exact atlas target remained unchanged hash=%016llx codeSize=%zu; repair compilation unavailable\n",
                        (unsigned long long)inputHash, createInfo->codeSize);
                fflush(stderr);
            }
        } else if (db_vk_entity_shader_fallback_enabled()) {
            db_vk_cache_known_good_entity_shader(inputHash, createInfo);
            if (db_vk_prepare_entity_shader_fallback(
                    inputHash, createInfo, &replacementCreateInfo, &replacementHash,
                    &ownedReplacementCode)) {
                effectiveCreateInfo = &replacementCreateInfo;
            } else if (inputHash == DB_VK_BAD_ENTITY_FRAGMENT_HASH) {
                fprintf(stderr,
                        "DroidBridgeVulkanEntityBaseTexture: exact target shader remained unchanged hash=%016llx codeSize=%zu (base-texture diagnostic unavailable or vertex stage intentionally preserved)\n",
                        (unsigned long long)inputHash,
                        createInfo->codeSize);
                fflush(stderr);
            }
        }
    }

    DB_VkResult result = real(device, effectiveCreateInfo, allocator, shaderModule);
    if (result == DB_VK_SUCCESS && shaderModule != NULL && *shaderModule != 0u
            && (db_vk_pipeline_diagnostics_enabled()
                    || db_vk_entity_shader_fallback_enabled()
                    || db_vk_atlas_fragment_repair_enabled())) {
        db_vk_store_shader_module(*shaderModule, effectiveCreateInfo);
        if (replacementHash != 0u) {
            fprintf(stderr,
                    "DroidBridgeVulkanEntityUvSampler: created diagnostic module=%llu effectiveHash=%016llx originalHash=%016llx\n",
                    (unsigned long long)*shaderModule,
                    (unsigned long long)replacementHash,
                    (unsigned long long)inputHash);
            fflush(stderr);
        }
    }
    free(ownedReplacementCode);
    return result;
}

static void db_vk_destroy_shader_module(
        DB_VkDevice device, DB_VkShaderModule shaderModule, const void* allocator) {
    if ((db_vk_pipeline_diagnostics_enabled() || db_vk_entity_shader_fallback_enabled()
            || db_vk_atlas_fragment_repair_enabled()) && shaderModule != 0u) {
        db_vk_remove_shader_module(shaderModule);
    }
    if (g_db_real_vkDestroyShaderModule != NULL) {
        g_db_real_vkDestroyShaderModule(device, shaderModule, allocator);
    }
}

static void db_vk_cmd_bind_pipeline(
        DB_VkCommandBuffer commandBuffer,
        DB_VkPipelineBindPoint pipelineBindPoint,
        DB_VkPipeline pipeline) {
    if (g_db_real_vkCmdBindPipeline != NULL) {
        g_db_real_vkCmdBindPipeline(commandBuffer, pipelineBindPoint, pipeline);
    }
    if ((db_vk_entity_aux_diagnostics_enabled()
            || db_vk_atlas_build_input_audit_enabled()
            || db_vk_static_atlas_layout_repair_enabled())
            && pipelineBindPoint == DB_VK_PIPELINE_BIND_POINT_GRAPHICS) {
        db_vk_set_bound_pipeline(commandBuffer, pipelineBindPoint, pipeline);
    }
}

static DB_VkResult db_vk_create_graphics_pipelines(
        DB_VkDevice device,
        DB_VkPipelineCache pipelineCache,
        uint32_t createInfoCount,
        const DB_VkGraphicsPipelineCreateInfoPrefix* createInfos,
        const void* allocator,
        DB_VkPipeline* pipelines) {
    DB_PFN_vkCreateGraphicsPipelines real = g_db_real_vkCreateGraphicsPipelines;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;

    const bool repairEntityNormal = db_vk_entity_normal_format_repair_enabled();
    const DB_VkPipelineVertexInputStateCreateInfo** originalVertexInput = NULL;
    DB_VkPipelineVertexInputStateCreateInfo* vertexInputCopies = NULL;
    DB_VkVertexInputAttributeDescription** attributeCopies = NULL;
    bool anyRewrite = false;

    if (repairEntityNormal && createInfos != NULL && createInfoCount > 0u) {
        originalVertexInput = (const DB_VkPipelineVertexInputStateCreateInfo**)calloc(
                createInfoCount, sizeof(*originalVertexInput));
        vertexInputCopies = (DB_VkPipelineVertexInputStateCreateInfo*)calloc(
                createInfoCount, sizeof(*vertexInputCopies));
        attributeCopies = (DB_VkVertexInputAttributeDescription**)calloc(
                createInfoCount, sizeof(*attributeCopies));

        if (originalVertexInput != NULL && vertexInputCopies != NULL && attributeCopies != NULL) {
            DB_VkGraphicsPipelineCreateInfoPrefix* mutableInfos =
                    (DB_VkGraphicsPipelineCreateInfoPrefix*)(uintptr_t)createInfos;
            for (uint32_t i = 0u; i < createInfoCount; ++i) {
                const DB_VkPipelineVertexInputStateCreateInfo* vi = createInfos[i].pVertexInputState;
                if (vi == NULL
                        || vi->vertexBindingDescriptionCount != 1u
                        || vi->pVertexBindingDescriptions == NULL
                        || vi->pVertexBindingDescriptions[0].stride != 36u
                        || vi->vertexAttributeDescriptionCount != 6u
                        || vi->pVertexAttributeDescriptions == NULL) {
                    continue;
                }

                int normalIndex = -1;
                for (uint32_t a = 0u; a < vi->vertexAttributeDescriptionCount; ++a) {
                    const DB_VkVertexInputAttributeDescription* attr =
                            &vi->pVertexAttributeDescriptions[a];
                    if (attr->offset == 32u && attr->format == DB_VK_FORMAT_R8G8B8_SNORM) {
                        normalIndex = (int)a;
                        break;
                    }
                }
                if (normalIndex < 0) continue;

                DB_VkVertexInputAttributeDescription* attrs =
                        (DB_VkVertexInputAttributeDescription*)malloc(
                                vi->vertexAttributeDescriptionCount * sizeof(*attrs));
                if (attrs == NULL) continue;
                memcpy(attrs, vi->pVertexAttributeDescriptions,
                       vi->vertexAttributeDescriptionCount * sizeof(*attrs));
                attrs[normalIndex].format = DB_VK_FORMAT_R8G8B8A8_SNORM;

                vertexInputCopies[i] = *vi;
                vertexInputCopies[i].pVertexAttributeDescriptions = attrs;
                attributeCopies[i] = attrs;
                originalVertexInput[i] = vi;
                mutableInfos[i].pVertexInputState = &vertexInputCopies[i];
                anyRewrite = true;

                uint64_t rewrite = ++g_db_vk_entity_normal_format_rewrites;
                if (rewrite <= 64u || (rewrite % 256u) == 0u) {
                    fprintf(stderr,
                            "DroidBridgeVulkanEntityVertex: normal-format rewrite #%llu pipelineInfo=%u stride=36 attrs=6 location=%u offset=32 format=%d->%d paddingByteConsumed=1\n",
                            (unsigned long long)rewrite,
                            i,
                            attrs[normalIndex].location,
                            DB_VK_FORMAT_R8G8B8_SNORM,
                            DB_VK_FORMAT_R8G8B8A8_SNORM);
                    fflush(stderr);
                }
            }
        }
    }

    DB_VkResult result = real(device, pipelineCache, createInfoCount, createInfos, allocator, pipelines);

    if (anyRewrite && originalVertexInput != NULL) {
        DB_VkGraphicsPipelineCreateInfoPrefix* mutableInfos =
                (DB_VkGraphicsPipelineCreateInfoPrefix*)(uintptr_t)createInfos;
        for (uint32_t i = 0u; i < createInfoCount; ++i) {
            if (originalVertexInput[i] != NULL) {
                mutableInfos[i].pVertexInputState = originalVertexInput[i];
            }
        }
    }
    if (attributeCopies != NULL) {
        for (uint32_t i = 0u; i < createInfoCount; ++i) free(attributeCopies[i]);
    }
    free(attributeCopies);
    free(vertexInputCopies);
    free(originalVertexInput);

    if (db_vk_pipeline_diagnostics_enabled()
            && result == DB_VK_SUCCESS
            && createInfos != NULL
            && pipelines != NULL) {
        for (uint32_t i = 0u; i < createInfoCount; ++i) {
            db_vk_store_pipeline_record(pipelines[i], &createInfos[i]);
        }
    }

    if (!db_vk_pipeline_diagnostics_enabled() || createInfos == NULL) return result;

    db_vk_log_shader_toolchain_once();
    for (uint32_t i = 0u; i < createInfoCount; ++i) {
        const DB_VkGraphicsPipelineCreateInfoPrefix* ci = &createInfos[i];
        const DB_VkPipelineVertexInputStateCreateInfo* vi = ci->pVertexInputState;
        const DB_VkPipelineVertexInputDivisorStateCreateInfoEXT* divisor =
                db_vk_find_vertex_divisor_state(vi);
        uint64_t call = ++g_db_vk_graphics_pipeline_calls;
        uint32_t bindingCount = vi != NULL ? vi->vertexBindingDescriptionCount : 0u;
        uint32_t attributeCount = vi != NULL ? vi->vertexAttributeDescriptionCount : 0u;
        uint32_t divisorCount = divisor != NULL ? divisor->vertexBindingDivisorCount : 0u;
        DB_VkPipeline handle = pipelines != NULL ? pipelines[i] : 0u;

        fprintf(stderr,
                "DroidBridgeVulkanPipeline: create #%llu result=%d pipeline=%llu stages=%u bindings=%u attrs=%u divisorCount=%u",
                (unsigned long long)call,
                result,
                (unsigned long long)handle,
                ci->stageCount,
                bindingCount,
                attributeCount,
                divisorCount);

        if (vi != NULL && vi->pVertexBindingDescriptions != NULL && bindingCount > 0u) {
            uint32_t maxBindings = bindingCount < 6u ? bindingCount : 6u;
            fprintf(stderr, " bindingState=[");
            for (uint32_t b = 0u; b < maxBindings; ++b) {
                const DB_VkVertexInputBindingDescription* bd = &vi->pVertexBindingDescriptions[b];
                fprintf(stderr, "%s%u:%u:%u", b == 0u ? "" : ",",
                        bd->binding, bd->stride, bd->inputRate);
            }
            if (bindingCount > maxBindings) fprintf(stderr, ",...");
            fprintf(stderr, "]");
        }

        if (vi != NULL && vi->pVertexAttributeDescriptions != NULL && attributeCount > 0u
                && (attributeCount >= 4u || repairEntityNormal)) {
            uint32_t maxAttrs = attributeCount < 8u ? attributeCount : 8u;
            fprintf(stderr, " attrState=[");
            for (uint32_t a = 0u; a < maxAttrs; ++a) {
                const DB_VkVertexInputAttributeDescription* ad = &vi->pVertexAttributeDescriptions[a];
                fprintf(stderr, "%s%u:%u:%d:%u", a == 0u ? "" : ",",
                        ad->location, ad->binding, ad->format, ad->offset);
            }
            if (attributeCount > maxAttrs) fprintf(stderr, ",...");
            fprintf(stderr, "]");
        }

        if (divisor != NULL && divisor->pVertexBindingDivisors != NULL && divisorCount > 0u) {
            uint32_t maxDivisors = divisorCount < 8u ? divisorCount : 8u;
            fprintf(stderr, " divisors=[");
            for (uint32_t d = 0u; d < maxDivisors; ++d) {
                const DB_VkVertexInputBindingDivisorDescriptionEXT* dd =
                        &divisor->pVertexBindingDivisors[d];
                fprintf(stderr, "%s%u:%u", d == 0u ? "" : ",", dd->binding, dd->divisor);
            }
            if (divisorCount > maxDivisors) fprintf(stderr, ",...");
            fprintf(stderr, "]");
        }
        fprintf(stderr, "\n");
    }
    fflush(stderr);
    return result;
}

static DB_VkResult db_vk_set_debug_utils_object_name_ext(
        DB_VkDevice device,
        const DB_VkDebugUtilsObjectNameInfoEXT* nameInfo) {
    DB_PFN_vkSetDebugUtilsObjectNameEXT real = g_db_real_vkSetDebugUtilsObjectNameEXT;
    if (db_vk_pipeline_diagnostics_enabled() && nameInfo != NULL && nameInfo->pObjectName != NULL) {
        bool interestingType = nameInfo->objectType == DB_VK_OBJECT_TYPE_PIPELINE
                || nameInfo->objectType == DB_VK_OBJECT_TYPE_PIPELINE_LAYOUT
                || nameInfo->objectType == DB_VK_OBJECT_TYPE_SHADER_MODULE;
        if (interestingType && g_db_vk_debug_name_calls < 512u) {
            ++g_db_vk_debug_name_calls;
            fprintf(stderr,
                    "DroidBridgeVulkanPipeline: objectName type=%d handle=%llu name=%s\n",
                    nameInfo->objectType,
                    (unsigned long long)nameInfo->objectHandle,
                    nameInfo->pObjectName);
            fflush(stderr);
        }
    }
    return real != NULL ? real(device, nameInfo) : DB_VK_SUCCESS;
}

static db_vk_render_scope_state* db_vk_get_render_scope_locked(
        DB_VkCommandBuffer commandBuffer, bool create) {
    for (db_vk_render_scope_state* state = g_db_vk_render_scopes;
         state != NULL; state = state->next) {
        if (state->commandBuffer == commandBuffer) return state;
    }
    if (!create) return NULL;
    db_vk_render_scope_state* state = (db_vk_render_scope_state*)calloc(1u, sizeof(*state));
    if (state == NULL) return NULL;
    state->commandBuffer = commandBuffer;
    state->next = g_db_vk_render_scopes;
    g_db_vk_render_scopes = state;
    return state;
}

static bool db_vk_emit_global_write_read_visibility_barrier(
        DB_VkCommandBuffer commandBuffer,
        const char* diagnosticTag) {
    DB_PFN_vkCmdPipelineBarrier2 barrier2 = g_db_real_vkCmdPipelineBarrier2;
    if (barrier2 != NULL) {
        DB_VkMemoryBarrier2 memoryBarrier;
        memset(&memoryBarrier, 0, sizeof(memoryBarrier));
        memoryBarrier.sType = DB_VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
        memoryBarrier.srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        memoryBarrier.srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
        memoryBarrier.dstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        memoryBarrier.dstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;

        DB_VkDependencyInfo dependency;
        memset(&dependency, 0, sizeof(dependency));
        dependency.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
        dependency.memoryBarrierCount = 1u;
        dependency.pMemoryBarriers = &memoryBarrier;
        barrier2(commandBuffer, &dependency);
        return true;
    }

    /*
     * PanVK G57 / VulkanMod 0.6.8 records legacy vkCmdPipelineBarrier calls.
     * In that configuration vkCmdPipelineBarrier2 is never resolved, so the
     * v6.10 atlas repair was armed but became a no-op. Fall back to the Vulkan
     * 1.0 memory barrier that provides the same ALL_COMMANDS write->read
     * visibility guarantee without changing layouts or descriptors.
     */
    DB_PFN_vkCmdPipelineBarrier barrier = g_db_real_vkCmdPipelineBarrier;
    if (barrier != NULL) {
        DB_VkMemoryBarrier memoryBarrier;
        memset(&memoryBarrier, 0, sizeof(memoryBarrier));
        memoryBarrier.sType = DB_VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memoryBarrier.srcAccessMask = DB_VK_ACCESS_MEMORY_WRITE_BIT;
        memoryBarrier.dstAccessMask = DB_VK_ACCESS_MEMORY_READ_BIT;
        barrier(commandBuffer,
                DB_VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                DB_VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0u,
                1u, &memoryBarrier,
                0u, NULL,
                0u, NULL);
        if (diagnosticTag != NULL) {
            static bool s_legacy_visibility_fallback_logged = false;
            if (!s_legacy_visibility_fallback_logged) {
                s_legacy_visibility_fallback_logged = true;
                fprintf(stderr,
                        "%s: using legacy vkCmdPipelineBarrier fallback (ALL_COMMANDS write->read)\n",
                        diagnosticTag);
                fflush(stderr);
            }
        }
        return true;
    }

    if (diagnosticTag != NULL) {
        fprintf(stderr,
                "%s: repair unavailable; neither vkCmdPipelineBarrier2 nor vkCmdPipelineBarrier is resolved\n",
                diagnosticTag);
        fflush(stderr);
    }
    return false;
}

static void db_vk_emit_atlas_source_read_visibility_barrier(
        DB_VkCommandBuffer commandBuffer,
        const db_vk_image_summary* targetSummary,
        uint32_t targetCount) {
    if (!db_vk_atlas_source_sync_repair_enabled()) return;
    if (g_db_real_vkCmdPipelineBarrier2 == NULL
            && g_db_real_vkCmdPipelineBarrier == NULL) {
        if (!g_db_vk_atlas_source_sync_missing_barrier_logged) {
            g_db_vk_atlas_source_sync_missing_barrier_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasSourceSync: repair requested but no Vulkan pipeline barrier entry point is available\n");
            fflush(stderr);
        }
        return;
    }

    /*
     * The V2 audit proved paintings/banner_patterns/items atlases all receive a
     * real dynamic-rendering STORE into GENERAL, while only the first two read
     * black later. The failed post-render atlas repair synchronized the target
     * after it had already been populated. This experiment instead establishes
     * visibility for prior sprite uploads immediately BEFORE Minecraft starts
     * rendering into a sampled offscreen atlas. If Adreno was feeding stale or
     * zero source texels to the atlas-building fragment shader, the target will
     * now be built from visible source data without changing shaders, layouts,
     * descriptors, load/store operations, or the atlas image itself.
     */
    if (!db_vk_emit_global_write_read_visibility_barrier(
            commandBuffer, "DroidBridgeVulkanAtlasSourceSync")) {
        return;
    }
    uint64_t call = ++g_db_vk_atlas_source_sync_barriers;
    if (call <= 64u || (call % 256u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasSourceSync: pre-render source visibility barrier #%llu targets=%u firstImage=%llu size=%ux%u usage=0x%x stage=ALL_COMMANDS write->read layout=unchanged\n",
                (unsigned long long)call,
                targetCount,
                (unsigned long long)(targetSummary != NULL ? targetSummary->image : 0u),
                targetSummary != NULL ? targetSummary->width : 0u,
                targetSummary != NULL ? targetSummary->height : 0u,
                targetSummary != NULL ? targetSummary->usage : 0u);
        fflush(stderr);
    }
}

static bool db_vk_panvk_g57_accumulate_view_bounds(
        DB_VkImageView view,
        uint32_t* width,
        uint32_t* height,
        bool* found) {
    if (view == 0u || width == NULL || height == NULL || found == NULL) return false;
    db_vk_image_summary summary;
    memset(&summary, 0, sizeof(summary));
    if (!db_vk_lookup_image_view_summary(view, &summary)
            || summary.width == 0u || summary.height == 0u) {
        return false;
    }
    if (!*found) {
        *width = summary.width;
        *height = summary.height;
        *found = true;
    } else {
        if (summary.width < *width) *width = summary.width;
        if (summary.height < *height) *height = summary.height;
    }
    return true;
}

static bool db_vk_panvk_g57_render_bounds(
        const DB_VkRenderingInfo* renderingInfo,
        uint32_t* width,
        uint32_t* height) {
    if (!db_vk_panvk_g57_safe_enabled() || renderingInfo == NULL
            || width == NULL || height == NULL) {
        return false;
    }

    uint32_t minWidth = 0u;
    uint32_t minHeight = 0u;
    bool found = false;
    if (renderingInfo->pColorAttachments != NULL) {
        uint32_t count = renderingInfo->colorAttachmentCount;
        if (count > 16u) count = 16u;
        for (uint32_t i = 0u; i < count; ++i) {
            db_vk_panvk_g57_accumulate_view_bounds(
                    renderingInfo->pColorAttachments[i].imageView,
                    &minWidth, &minHeight, &found);
        }
    }
    if (renderingInfo->pDepthAttachment != NULL) {
        db_vk_panvk_g57_accumulate_view_bounds(
                renderingInfo->pDepthAttachment->imageView,
                &minWidth, &minHeight, &found);
    }
    if (renderingInfo->pStencilAttachment != NULL) {
        db_vk_panvk_g57_accumulate_view_bounds(
                renderingInfo->pStencilAttachment->imageView,
                &minWidth, &minHeight, &found);
    }
    if (!found || minWidth == 0u || minHeight == 0u) return false;
    *width = minWidth;
    *height = minHeight;
    return true;
}

static bool db_vk_panvk_g57_clamp_rect(
        DB_VkRect2DFlat* rect,
        uint32_t width,
        uint32_t height) {
    if (rect == NULL || width == 0u || height == 0u) return false;

    int64_t x0 = rect->offsetX;
    int64_t y0 = rect->offsetY;
    int64_t x1 = x0 + (int64_t)rect->width;
    int64_t y1 = y0 + (int64_t)rect->height;
    int64_t left = x0 < 0 ? 0 : x0;
    int64_t top = y0 < 0 ? 0 : y0;
    int64_t right = x1 > (int64_t)width ? (int64_t)width : x1;
    int64_t bottom = y1 > (int64_t)height ? (int64_t)height : y1;

    if (left > (int64_t)width) left = width;
    if (top > (int64_t)height) top = height;
    if (right < left) right = left;
    if (bottom < top) bottom = top;

    DB_VkRect2DFlat original = *rect;
    rect->offsetX = (int32_t)left;
    rect->offsetY = (int32_t)top;
    rect->width = (uint32_t)(right - left);
    rect->height = (uint32_t)(bottom - top);
    return rect->offsetX != original.offsetX
            || rect->offsetY != original.offsetY
            || rect->width != original.width
            || rect->height != original.height;
}

static void db_vk_cmd_set_scissor(
        DB_VkCommandBuffer commandBuffer,
        uint32_t firstScissor,
        uint32_t scissorCount,
        const DB_VkRect2DFlat* scissors) {
    DB_PFN_vkCmdSetScissor real = g_db_real_vkCmdSetScissor;
    if (real == NULL) return;
    if (!db_vk_panvk_g57_safe_enabled() || scissors == NULL || scissorCount == 0u) {
        real(commandBuffer, firstScissor, scissorCount, scissors);
        return;
    }

    uint32_t targetWidth = 0u;
    uint32_t targetHeight = 0u;
    bool boundsValid = false;
    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* state = db_vk_get_render_scope_locked(commandBuffer, false);
    if (state != NULL && state->activeTargetBoundsValid) {
        targetWidth = state->activeTargetWidth;
        targetHeight = state->activeTargetHeight;
        boundsValid = targetWidth > 0u && targetHeight > 0u;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
    if (!boundsValid) {
        real(commandBuffer, firstScissor, scissorCount, scissors);
        return;
    }

    DB_VkRect2DFlat local[8];
    DB_VkRect2DFlat* patched = local;
    bool heap = false;
    if (scissorCount > 8u) {
        patched = (DB_VkRect2DFlat*)malloc((size_t)scissorCount * sizeof(*patched));
        if (patched == NULL) {
            real(commandBuffer, firstScissor, scissorCount, scissors);
            return;
        }
        heap = true;
    }
    memcpy(patched, scissors, (size_t)scissorCount * sizeof(*patched));

    bool changed = false;
    for (uint32_t i = 0u; i < scissorCount; ++i) {
        DB_VkRect2DFlat before = patched[i];
        if (db_vk_panvk_g57_clamp_rect(&patched[i], targetWidth, targetHeight)) {
            changed = true;
            uint64_t call = ++g_db_vk_panvk_g57_scissor_clamps;
            if (call <= 96u || (call % 256u) == 0u) {
                fprintf(stderr,
                        "DroidBridgePanVKG57: scissor clamp #%llu cb=%p index=%u target=%ux%u rect=%d,%d %ux%u -> %d,%d %ux%u\n",
                        (unsigned long long)call,
                        (void*)commandBuffer,
                        firstScissor + i,
                        targetWidth, targetHeight,
                        before.offsetX, before.offsetY, before.width, before.height,
                        patched[i].offsetX, patched[i].offsetY,
                        patched[i].width, patched[i].height);
                fflush(stderr);
            }
        }
    }

    real(commandBuffer, firstScissor, scissorCount, changed ? patched : scissors);
    if (heap) free(patched);
}

static void db_vk_cmd_set_scissor_with_count(
        DB_VkCommandBuffer commandBuffer,
        uint32_t scissorCount,
        const DB_VkRect2DFlat* scissors) {
    DB_PFN_vkCmdSetScissorWithCount real = g_db_real_vkCmdSetScissorWithCount;
    if (real == NULL) return;
    if (!db_vk_panvk_g57_safe_enabled() || scissors == NULL || scissorCount == 0u) {
        real(commandBuffer, scissorCount, scissors);
        return;
    }

    uint32_t targetWidth = 0u;
    uint32_t targetHeight = 0u;
    bool boundsValid = false;
    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* state = db_vk_get_render_scope_locked(commandBuffer, false);
    if (state != NULL && state->activeTargetBoundsValid) {
        targetWidth = state->activeTargetWidth;
        targetHeight = state->activeTargetHeight;
        boundsValid = targetWidth > 0u && targetHeight > 0u;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);
    if (!boundsValid) {
        real(commandBuffer, scissorCount, scissors);
        return;
    }

    DB_VkRect2DFlat local[8];
    DB_VkRect2DFlat* patched = local;
    bool heap = false;
    if (scissorCount > 8u) {
        patched = (DB_VkRect2DFlat*)malloc((size_t)scissorCount * sizeof(*patched));
        if (patched == NULL) {
            real(commandBuffer, scissorCount, scissors);
            return;
        }
        heap = true;
    }
    memcpy(patched, scissors, (size_t)scissorCount * sizeof(*patched));

    bool changed = false;
    for (uint32_t i = 0u; i < scissorCount; ++i) {
        DB_VkRect2DFlat before = patched[i];
        if (db_vk_panvk_g57_clamp_rect(&patched[i], targetWidth, targetHeight)) {
            changed = true;
            uint64_t call = ++g_db_vk_panvk_g57_scissor_clamps;
            if (call <= 96u || (call % 256u) == 0u) {
                fprintf(stderr,
                        "DroidBridgePanVKG57: scissorWithCount clamp #%llu cb=%p index=%u target=%ux%u rect=%d,%d %ux%u -> %d,%d %ux%u\n",
                        (unsigned long long)call,
                        (void*)commandBuffer,
                        i,
                        targetWidth, targetHeight,
                        before.offsetX, before.offsetY, before.width, before.height,
                        patched[i].offsetX, patched[i].offsetY,
                        patched[i].width, patched[i].height);
                fflush(stderr);
            }
        }
    }

    real(commandBuffer, scissorCount, changed ? patched : scissors);
    if (heap) free(patched);
}

static void db_vk_cmd_begin_rendering(
        DB_VkCommandBuffer commandBuffer,
        const DB_VkRenderingInfo* renderingInfo) {
    DB_PFN_vkCmdBeginRendering real = g_db_real_vkCmdBeginRendering;
    if (real == NULL) return;

    uint32_t panVkTargetWidth = 0u;
    uint32_t panVkTargetHeight = 0u;
    bool panVkTargetBoundsValid = db_vk_panvk_g57_render_bounds(
            renderingInfo, &panVkTargetWidth, &panVkTargetHeight);
    DB_VkRenderingInfo panVkPatchedRenderingInfo;
    if (panVkTargetBoundsValid && renderingInfo != NULL) {
        panVkPatchedRenderingInfo = *renderingInfo;
        DB_VkRect2DFlat before = panVkPatchedRenderingInfo.renderArea;
        if (db_vk_panvk_g57_clamp_rect(
                &panVkPatchedRenderingInfo.renderArea,
                panVkTargetWidth,
                panVkTargetHeight)) {
            uint64_t call = ++g_db_vk_panvk_g57_render_area_clamps;
            if (call <= 96u || (call % 256u) == 0u) {
                fprintf(stderr,
                        "DroidBridgePanVKG57: renderArea clamp #%llu cb=%p target=%ux%u area=%d,%d %ux%u -> %d,%d %ux%u\n",
                        (unsigned long long)call,
                        (void*)commandBuffer,
                        panVkTargetWidth, panVkTargetHeight,
                        before.offsetX, before.offsetY, before.width, before.height,
                        panVkPatchedRenderingInfo.renderArea.offsetX,
                        panVkPatchedRenderingInfo.renderArea.offsetY,
                        panVkPatchedRenderingInfo.renderArea.width,
                        panVkPatchedRenderingInfo.renderArea.height);
                fflush(stderr);
            }
        }
        renderingInfo = &panVkPatchedRenderingInfo;
    }

    bool sampledTarget = false;
    uint32_t targetCount = 0u;
    uint64_t firstTargetRenderCall = 0u;
    db_vk_image_summary firstTarget;
    memset(&firstTarget, 0, sizeof(firstTarget));
    if ((db_vk_image_diagnostics_enabled() || db_vk_atlas_source_sync_repair_enabled())
            && renderingInfo != NULL
            && renderingInfo->pColorAttachments != NULL) {
        uint32_t count = renderingInfo->colorAttachmentCount;
        if (count > 8u) count = 8u;
        for (uint32_t i = 0u; i < count; ++i) {
            const DB_VkRenderingAttachmentInfo* attachment =
                    &renderingInfo->pColorAttachments[i];
            DB_VkImageView view = attachment->imageView;
            db_vk_image_summary summary;
            memset(&summary, 0, sizeof(summary));
            if (db_vk_lookup_image_view_summary(view, &summary)) {
                uint64_t renderCalls = 0u;
                pthread_mutex_lock(&g_db_vk_image_mutex);
                db_vk_image_record* renderRecord = db_vk_find_image_locked(summary.image);
                if (renderRecord != NULL) {
                    ++renderRecord->renderTargetBeginCalls;
                    renderCalls = renderRecord->renderTargetBeginCalls;
                    renderRecord->lastRenderLoadOp = attachment->loadOp;
                    renderRecord->lastRenderStoreOp = attachment->storeOp;
                    memcpy(renderRecord->lastRenderClearColor,
                           attachment->clearValue.color.float32,
                           sizeof(renderRecord->lastRenderClearColor));
                }
                pthread_mutex_unlock(&g_db_vk_image_mutex);

                if (db_vk_image_diagnostics_enabled()
                        && renderCalls > 0u && renderCalls <= 2u
                        && db_vk_image_summary_is_diagnostic_target(&summary)) {
                    fprintf(stderr,
                            "DroidBridgeVulkanAtlasRenderV2: begin image=%llu view=%llu size=%ux%u usage=0x%x call=%llu layout=%s(%d) load=%d store=%d clear=%.3f,%.3f,%.3f,%.3f mutation=none\n",
                            (unsigned long long)summary.image,
                            (unsigned long long)view,
                            summary.width, summary.height, summary.usage,
                            (unsigned long long)renderCalls,
                            db_vk_layout_name(attachment->imageLayout),
                            attachment->imageLayout,
                            attachment->loadOp, attachment->storeOp,
                            attachment->clearValue.color.float32[0],
                            attachment->clearValue.color.float32[1],
                            attachment->clearValue.color.float32[2],
                            attachment->clearValue.color.float32[3]);
                    fflush(stderr);
                }

                if ((db_vk_atlas_sync_repair_enabled()
                        || db_vk_atlas_source_sync_repair_enabled()
                        || db_vk_atlas_build_input_audit_enabled()
                        || db_vk_atlas_ubo_flush_repair_enabled()
                        || db_vk_atlas_image_copy_test_enabled()
                        || db_vk_atlas_direct_compose_repair_enabled())
                        && db_vk_is_sampled_offscreen_target(&summary)) {
                    if (!sampledTarget) {
                        firstTarget = summary;
                        firstTargetRenderCall = renderCalls;
                    }
                    sampledTarget = true;
                    ++targetCount;
                }
            }
        }
    }

    if (db_vk_atlas_source_sync_repair_enabled() && sampledTarget) {
        if (!g_db_vk_atlas_source_sync_intercept_logged) {
            g_db_vk_atlas_source_sync_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasSourceSync: sampled-offscreen atlas source-read visibility repair armed; original shaders/descriptors/layouts preserved\n");
            fflush(stderr);
        }
        db_vk_emit_atlas_source_read_visibility_barrier(
                commandBuffer, &firstTarget, targetCount);
    }

    bool auditInteresting = db_vk_atlas_build_input_audit_enabled()
            && sampledTarget
            && firstTargetRenderCall == 1u
            && db_vk_atlas_build_audit_dimension(&firstTarget);
    uint64_t auditScope = 0u;
    if (auditInteresting) {
        auditScope = ++g_db_vk_atlas_build_scope_serial;
        if (!g_db_vk_atlas_build_input_audit_logged) {
            g_db_vk_atlas_build_input_audit_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasBuildInput: first-render build-input audit armed for 1024x512 and 512x256 sampled offscreen atlases; original shaders/descriptors/layouts preserved; mutation=none\n");
        }
        fprintf(stderr,
                "DroidBridgeVulkanAtlasBuildInput: scope #%llu begin target=%llu view=%llu size=%ux%u usage=0x%x renderCall=%llu load=%d store=%d layout=%s(%d) mutation=none\n",
                (unsigned long long)auditScope,
                (unsigned long long)firstTarget.image,
                (unsigned long long)(renderingInfo != NULL && renderingInfo->pColorAttachments != NULL
                        ? renderingInfo->pColorAttachments[0].imageView : 0u),
                firstTarget.width, firstTarget.height, firstTarget.usage,
                (unsigned long long)firstTargetRenderCall,
                renderingInfo != NULL && renderingInfo->pColorAttachments != NULL
                        ? renderingInfo->pColorAttachments[0].loadOp : -1,
                renderingInfo != NULL && renderingInfo->pColorAttachments != NULL
                        ? renderingInfo->pColorAttachments[0].storeOp : -1,
                renderingInfo != NULL && renderingInfo->pColorAttachments != NULL
                        ? db_vk_layout_name(renderingInfo->pColorAttachments[0].imageLayout) : "UNKNOWN",
                renderingInfo != NULL && renderingInfo->pColorAttachments != NULL
                        ? renderingInfo->pColorAttachments[0].imageLayout : -1);
        fflush(stderr);
    }

    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* state = db_vk_get_render_scope_locked(commandBuffer, true);
    if (state != NULL) {
        state->sampledTarget = sampledTarget;
        state->targetCount = targetCount;
        state->firstTarget = firstTarget;
        state->firstTargetRenderCall = firstTargetRenderCall;
        state->atlasBuildAuditInteresting = auditInteresting;
        state->atlasBuildAuditScope = auditScope;
        state->atlasBuildAuditPackets = 0u;
        state->atlasImageCopyPackets = 0u;
        state->currentAtlasSourceValid = false;
        state->currentAtlasPlacementValid = false;
        state->activeTargetWidth = panVkTargetWidth;
        state->activeTargetHeight = panVkTargetHeight;
        state->activeTargetBoundsValid = panVkTargetBoundsValid;
        state->atlasComposePacketCount = 0u;
        state->atlasComposeOverflow = false;
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);

    real(commandBuffer, renderingInfo);
}

static void db_vk_emit_sampled_target_visibility_barrier(
        DB_VkCommandBuffer commandBuffer,
        const db_vk_image_summary* firstTarget,
        uint32_t targetCount) {
    if (g_db_real_vkCmdPipelineBarrier2 == NULL
            && g_db_real_vkCmdPipelineBarrier == NULL) {
        if (g_db_vk_atlas_sync_barriers == 0u) {
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasSync: sampled-target repair requested but no Vulkan pipeline barrier entry point is available\n");
            fflush(stderr);
        }
        return;
    }

    if (!db_vk_emit_global_write_read_visibility_barrier(
            commandBuffer, "DroidBridgeVulkanAtlasSync")) {
        return;
    }
    uint64_t call = ++g_db_vk_atlas_sync_barriers;
    if (call <= 48u || (call % 256u) == 0u) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasSync: visibility barrier #%llu targets=%u firstImage=%llu size=%ux%u usage=0x%x stage=ALL_COMMANDS write->read\n",
                (unsigned long long)call,
                targetCount,
                (unsigned long long)(firstTarget != NULL ? firstTarget->image : 0u),
                firstTarget != NULL ? firstTarget->width : 0u,
                firstTarget != NULL ? firstTarget->height : 0u,
                firstTarget != NULL ? firstTarget->usage : 0u);
        fflush(stderr);
    }
}


static bool db_vk_copy_known_good_items_into_atlas(
        DB_VkCommandBuffer commandBuffer,
        const db_vk_image_summary* items,
        const db_vk_image_summary* target,
        const char* label) {
    if (items == NULL || target == NULL || items->image == 0u || target->image == 0u
            || items->image == target->image || g_db_real_vkCmdCopyImage == NULL
            || g_db_real_vkCmdPipelineBarrier2 == NULL) {
        return false;
    }
    if ((items->usage & DB_VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0u
            || (target->usage & DB_VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0u) {
        return false;
    }

    DB_VkImageMemoryBarrier2 before[2];
    memset(before, 0, sizeof(before));
    for (uint32_t i = 0u; i < 2u; ++i) {
        before[i].sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
        before[i].srcStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        before[i].srcAccessMask = DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
        before[i].dstStageMask = DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT;
        before[i].dstAccessMask = (i == 0u)
                ? DB_VK_ACCESS_2_MEMORY_READ_BIT : DB_VK_ACCESS_2_MEMORY_WRITE_BIT;
        before[i].oldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
        before[i].newLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
        before[i].srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
        before[i].dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
        before[i].subresourceRange.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
        before[i].subresourceRange.baseMipLevel = 0u;
        before[i].subresourceRange.levelCount = 1u;
        before[i].subresourceRange.baseArrayLayer = 0u;
        before[i].subresourceRange.layerCount = 1u;
    }
    before[0].image = items->image;
    before[1].image = target->image;

    DB_VkDependencyInfo dep;
    memset(&dep, 0, sizeof(dep));
    dep.sType = DB_VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
    dep.imageMemoryBarrierCount = 2u;
    dep.pImageMemoryBarriers = before;
    g_db_real_vkCmdPipelineBarrier2(commandBuffer, &dep);

    DB_VkImageCopy region;
    memset(&region, 0, sizeof(region));
    region.srcSubresource.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.mipLevel = 0u;
    region.srcSubresource.baseArrayLayer = 0u;
    region.srcSubresource.layerCount = 1u;
    region.dstSubresource = region.srcSubresource;
    region.extent.width = target->width < items->width ? target->width : items->width;
    region.extent.height = target->height < items->height ? target->height : items->height;
    region.extent.depth = 1u;
    g_db_real_vkCmdCopyImage(commandBuffer,
            items->image, DB_VK_IMAGE_LAYOUT_GENERAL,
            target->image, DB_VK_IMAGE_LAYOUT_GENERAL,
            1u, &region);

    DB_VkImageMemoryBarrier2 after;
    memset(&after, 0, sizeof(after));
    after.sType = DB_VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
    after.srcStageMask = DB_VK_PIPELINE_STAGE_2_TRANSFER_BIT;
    after.srcAccessMask = DB_VK_ACCESS_2_TRANSFER_WRITE_BIT;
    after.dstStageMask = DB_VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    after.dstAccessMask = DB_VK_ACCESS_2_MEMORY_READ_BIT;
    after.oldLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    after.newLayout = DB_VK_IMAGE_LAYOUT_GENERAL;
    after.srcQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    after.dstQueueFamilyIndex = DB_VK_QUEUE_FAMILY_IGNORED;
    after.image = target->image;
    after.subresourceRange.aspectMask = DB_VK_IMAGE_ASPECT_COLOR_BIT;
    after.subresourceRange.baseMipLevel = 0u;
    after.subresourceRange.levelCount = 1u;
    after.subresourceRange.baseArrayLayer = 0u;
    after.subresourceRange.layerCount = 1u;
    dep.imageMemoryBarrierCount = 1u;
    dep.pImageMemoryBarriers = &after;
    g_db_real_vkCmdPipelineBarrier2(commandBuffer, &dep);

    pthread_mutex_lock(&g_db_vk_image_mutex);
    db_vk_image_record* srcRec = db_vk_find_image_locked(items->image);
    db_vk_image_record* dstRec = db_vk_find_image_locked(target->image);
    if (srcRec != NULL) ++srcRec->imageCopyReadCalls;
    if (dstRec != NULL) {
        ++dstRec->imageCopyWriteCalls;
        ++dstRec->barrierCalls;
    }
    pthread_mutex_unlock(&g_db_vk_image_mutex);

    ++g_db_vk_atlas_image_copy_operations;
    fprintf(stderr,
            "DroidBridgeVulkanAtlasCopyTest: copy #%llu source=items/%llu(%ux%u) target=%s/%llu(%ux%u) extent=%ux%u layout=GENERAL->GENERAL mutation=item-pixels-into-original-target-image\n",
            (unsigned long long)g_db_vk_atlas_image_copy_operations,
            (unsigned long long)items->image, items->width, items->height,
            label != NULL ? label : "target",
            (unsigned long long)target->image, target->width, target->height,
            region.extent.width, region.extent.height);
    fflush(stderr);
    return true;
}

static void db_vk_cmd_end_rendering(DB_VkCommandBuffer commandBuffer) {
    DB_PFN_vkCmdEndRendering real = g_db_real_vkCmdEndRendering;
    if (real == NULL) return;
    real(commandBuffer);

    bool sampledTarget = false;
    uint32_t targetCount = 0u;
    bool auditInteresting = false;
    uint64_t auditScope = 0u;
    uint64_t auditPackets = 0u;
    uint64_t copyPackets = 0u;
    uint64_t firstTargetRenderCall = 0u;
    uint32_t composePacketCount = 0u;
    bool composeOverflow = false;
    db_vk_atlas_compose_packet composePackets[DB_VK_ATLAS_COMPOSE_MAX_PACKETS];
    memset(composePackets, 0, sizeof(composePackets));
    db_vk_image_summary firstTarget;
    memset(&firstTarget, 0, sizeof(firstTarget));
    pthread_mutex_lock(&g_db_vk_render_scope_mutex);
    db_vk_render_scope_state* state = db_vk_get_render_scope_locked(commandBuffer, false);
    if (state != NULL) {
        sampledTarget = state->sampledTarget;
        targetCount = state->targetCount;
        firstTarget = state->firstTarget;
        firstTargetRenderCall = state->firstTargetRenderCall;
        auditInteresting = state->atlasBuildAuditInteresting;
        auditScope = state->atlasBuildAuditScope;
        auditPackets = state->atlasBuildAuditPackets;
        copyPackets = state->atlasImageCopyPackets;
        composePacketCount = state->atlasComposePacketCount;
        composeOverflow = state->atlasComposeOverflow;
        if (composePacketCount > DB_VK_ATLAS_COMPOSE_MAX_PACKETS) {
            composePacketCount = DB_VK_ATLAS_COMPOSE_MAX_PACKETS;
        }
        if (composePacketCount > 0u) {
            memcpy(composePackets, state->atlasComposePackets,
                   (size_t)composePacketCount * sizeof(composePackets[0]));
        }
        state->sampledTarget = false;
        state->targetCount = 0u;
        state->firstTargetRenderCall = 0u;
        state->atlasBuildAuditInteresting = false;
        state->atlasBuildAuditScope = 0u;
        state->atlasBuildAuditPackets = 0u;
        state->atlasImageCopyPackets = 0u;
        state->currentAtlasSourceValid = false;
        state->currentAtlasPlacementValid = false;
        state->activeTargetWidth = 0u;
        state->activeTargetHeight = 0u;
        state->activeTargetBoundsValid = false;
        state->atlasComposePacketCount = 0u;
        state->atlasComposeOverflow = false;
        memset(&state->firstTarget, 0, sizeof(state->firstTarget));
    }
    pthread_mutex_unlock(&g_db_vk_render_scope_mutex);

    if (db_vk_atlas_direct_compose_repair_enabled()
            && sampledTarget && firstTargetRenderCall == 1u
            && !composeOverflow
            && composePacketCount >= 24u && composePacketCount < DB_VK_ATLAS_COMPOSE_MAX_PACKETS
            && ((firstTarget.width == 512u && firstTarget.height == 256u)
                || (firstTarget.width == 1024u && firstTarget.height == 512u))) {
        if (!g_db_vk_atlas_direct_compose_logged) {
            g_db_vk_atlas_direct_compose_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasComposeV5: Qualcomm SpriteMatrix direct atlas compositor repair V5 armed; small static paintings/shield/banner atlases are rebuilt from live source VkImages using exact destination rectangles decoded from Mojang's 140-byte SpriteAnimationInfo UBO; one-pixel REPEAT borders are preserved and all normal atlas shaders remain untouched\n");
            fflush(stderr);
        }
        uint32_t rebuilt = db_vk_rebuild_static_atlas_from_source_images(
                commandBuffer, &firstTarget, composePackets, composePacketCount);
        ++g_db_vk_atlas_direct_compose_scopes;
        fprintf(stderr,
                "DroidBridgeVulkanAtlasComposeV5: scope #%llu target=%llu size=%ux%u draws=%u rebuilt=%u unresolved=%u overflow=0 mutation=source-image-SpriteMatrix-direct-to-atlas\n",
                (unsigned long long)g_db_vk_atlas_direct_compose_scopes,
                (unsigned long long)firstTarget.image,
                firstTarget.width, firstTarget.height,
                composePacketCount, rebuilt, composePacketCount - rebuilt);
        fflush(stderr);
    } else if (db_vk_atlas_direct_compose_repair_enabled()
            && sampledTarget && firstTargetRenderCall == 1u && composeOverflow
            && ((firstTarget.width == 512u && firstTarget.height == 256u)
                || (firstTarget.width == 1024u && firstTarget.height == 512u))) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasComposeV5: skipped target=%llu size=%ux%u because packet capture overflowed %u; working large atlases (particles/items) are intentionally left untouched\n",
                (unsigned long long)firstTarget.image,
                firstTarget.width, firstTarget.height,
                DB_VK_ATLAS_COMPOSE_MAX_PACKETS);
        fflush(stderr);
    }

    if (db_vk_atlas_image_copy_test_enabled()
            && sampledTarget && firstTargetRenderCall == 1u && copyPackets > 0u) {
        if (!g_db_vk_atlas_image_copy_test_logged) {
            g_db_vk_atlas_image_copy_test_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasCopyTest: known-good items-atlas GPU copy isolation armed; original target images/views/descriptors are preserved and receive item-atlas pixels only after atlas construction\n");
            fflush(stderr);
        }

        if (firstTarget.width == 512u && firstTarget.height == 256u
                && copyPackets >= 32u && copyPackets <= 128u
                && g_db_vk_atlas_copy_painting_target.image == 0u) {
            g_db_vk_atlas_copy_painting_target = firstTarget;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasCopyTest: captured painting candidate image=%llu size=512x256 packets=%llu\n",
                    (unsigned long long)firstTarget.image,
                    (unsigned long long)copyPackets);
        } else if (firstTarget.width == 1024u && firstTarget.height == 512u
                && copyPackets >= 24u && copyPackets <= 128u
                && g_db_vk_atlas_copy_static_1024_count < 2u) {
            g_db_vk_atlas_copy_static_1024_targets[g_db_vk_atlas_copy_static_1024_count++] = firstTarget;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasCopyTest: captured static 1024x512 candidate #%u image=%llu packets=%llu\n",
                    g_db_vk_atlas_copy_static_1024_count,
                    (unsigned long long)firstTarget.image,
                    (unsigned long long)copyPackets);
        } else if (firstTarget.width == 1024u && firstTarget.height == 512u
                && copyPackets >= 256u && !g_db_vk_atlas_image_copy_items_seen) {
            g_db_vk_atlas_image_copy_items_seen = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasCopyTest: captured known-good items atlas image=%llu packets=%llu; copying its GPU pixels into previously captured original atlas images now\n",
                    (unsigned long long)firstTarget.image,
                    (unsigned long long)copyPackets);
            db_vk_copy_known_good_items_into_atlas(
                    commandBuffer, &firstTarget, &g_db_vk_atlas_copy_painting_target, "paintings");
            for (uint32_t i = 0u; i < g_db_vk_atlas_copy_static_1024_count; ++i) {
                db_vk_copy_known_good_items_into_atlas(
                        commandBuffer, &firstTarget,
                        &g_db_vk_atlas_copy_static_1024_targets[i],
                        i == 0u ? "shield_patterns" : "banner_patterns");
            }
        }
    }

    if (db_vk_static_atlas_layout_repair_enabled()
            && auditInteresting
            && auditPackets > 0u
            && auditPackets <= 64u) {
        db_vk_transition_static_atlas_to_shader_read(
                commandBuffer, &firstTarget, auditPackets);
    }

    if (auditInteresting) {
        fprintf(stderr,
                "DroidBridgeVulkanAtlasBuildInput: scope #%llu end target=%llu size=%ux%u pushPackets=%llu loggedLimit=24 mutation=none\n",
                (unsigned long long)auditScope,
                (unsigned long long)firstTarget.image,
                firstTarget.width, firstTarget.height,
                (unsigned long long)auditPackets);
        fflush(stderr);
    }

    if (db_vk_atlas_sync_repair_enabled() && sampledTarget) {
        db_vk_emit_sampled_target_visibility_barrier(commandBuffer, &firstTarget, targetCount);
    }
}


static void db_vk_remember_surface_transform(
        DB_VkSurfaceKHR surface, const DB_VkSurfaceCapabilitiesKHR* capabilities) {
    if (surface == 0u || capabilities == NULL) return;
    pthread_mutex_lock(&g_db_vk_surface_transform_mutex);
    if (g_db_vk_surface_transform_surface != surface) {
        g_db_vk_surface_identity_rejected = false;
        g_db_vk_identity_swapchain = 0u;
        g_db_vk_identity_swapchain_extent.width = 0u;
        g_db_vk_identity_swapchain_extent.height = 0u;
    }
    g_db_vk_surface_transform_surface = surface;
    g_db_vk_surface_supported_transforms = capabilities->supportedTransforms;
    g_db_vk_surface_real_current_transform = capabilities->currentTransform;
    g_db_vk_surface_current_extent = capabilities->currentExtent;
    pthread_mutex_unlock(&g_db_vk_surface_transform_mutex);
}

static void db_vk_force_identity_surface_capabilities(
        DB_VkSurfaceKHR surface, DB_VkSurfaceCapabilitiesKHR* capabilities,
        const char* source) {
    if (capabilities == NULL || !db_vk_surface_identity_enabled()) return;
    db_vk_remember_surface_transform(surface, capabilities);
    bool identityRejected = false;
    pthread_mutex_lock(&g_db_vk_surface_transform_mutex);
    identityRejected = g_db_vk_surface_identity_rejected
            && g_db_vk_surface_transform_surface == surface;
    pthread_mutex_unlock(&g_db_vk_surface_transform_mutex);
    if (identityRejected) {
        return;
    }
    if ((capabilities->supportedTransforms & DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) == 0u) {
        if (!g_db_vk_surface_transform_logged) {
            g_db_vk_surface_transform_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanSurfaceTransform: identity unsupported source=%s surface=%llu current=0x%x supported=0x%x; leaving driver transform unchanged\n",
                    source != NULL ? source : "unknown",
                    (unsigned long long)surface,
                    capabilities->currentTransform,
                    capabilities->supportedTransforms);
            fflush(stderr);
        }
        return;
    }
    const uint32_t realCurrent = capabilities->currentTransform;
    capabilities->currentTransform = DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    if (!g_db_vk_surface_transform_logged || realCurrent != DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
        g_db_vk_surface_transform_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanSurfaceTransform: source=%s surface=%llu current=0x%x supported=0x%x -> reportedCurrent=IDENTITY extent=%ux%u\n",
                source != NULL ? source : "unknown",
                (unsigned long long)surface,
                realCurrent,
                capabilities->supportedTransforms,
                capabilities->currentExtent.width,
                capabilities->currentExtent.height);
        fflush(stderr);
    }
}

static DB_VkResult db_vk_get_physical_device_surface_capabilities_khr(
        DB_VkPhysicalDevice physicalDevice,
        DB_VkSurfaceKHR surface,
        DB_VkSurfaceCapabilitiesKHR* capabilities) {
    DB_PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR real =
            g_db_real_vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(physicalDevice, surface, capabilities);
    if (result == DB_VK_SUCCESS) {
        db_vk_force_identity_surface_capabilities(surface, capabilities,
                "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    }
    return result;
}

static DB_VkResult db_vk_get_physical_device_surface_capabilities2_khr(
        DB_VkPhysicalDevice physicalDevice,
        const DB_VkPhysicalDeviceSurfaceInfo2KHR* surfaceInfo,
        DB_VkSurfaceCapabilities2KHR* capabilities) {
    DB_PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR real =
            g_db_real_vkGetPhysicalDeviceSurfaceCapabilities2KHR;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(physicalDevice, surfaceInfo, capabilities);
    if (result == DB_VK_SUCCESS && surfaceInfo != NULL && capabilities != NULL) {
        db_vk_force_identity_surface_capabilities(surfaceInfo->surface,
                &capabilities->surfaceCapabilities,
                "vkGetPhysicalDeviceSurfaceCapabilities2KHR");
    }
    return result;
}

static DB_VkResult db_vk_create_swapchain_khr(
        DB_VkDevice device,
        const DB_VkSwapchainCreateInfoKHR* createInfo,
        const void* allocator,
        DB_VkSwapchainKHR* swapchain) {
    DB_PFN_vkCreateSwapchainKHR real = g_db_real_vkCreateSwapchainKHR;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    if (createInfo == NULL) {
        return real(device, createInfo, allocator, swapchain);
    }

    if (!db_vk_surface_identity_enabled()) {
        DB_VkResult result = real(device, createInfo, allocator, swapchain);
        if (result == DB_VK_ERROR_INVALID_EXTERNAL_HANDLE
                && db_vk_custom_panvk_swapchain_compat_enabled()) {
            /*
             * Preserve the game's requested present mode first. Only when the
             * custom PanVK Android WSI rejects that exact swapchain do we retry.
             * IMMEDIATE keeps VSync-off semantics; FIFO is the Vulkan-required
             * Android-safe fallback and matches the known-good G57 route.
             */
            const int32_t requestedMode = createInfo->presentMode;
            int32_t retryModes[2] = {
                    DB_VK_PRESENT_MODE_IMMEDIATE_KHR,
                    DB_VK_PRESENT_MODE_FIFO_KHR
            };
            for (unsigned int i = 0u; i < 2u; ++i) {
                if (retryModes[i] == requestedMode) continue;
                DB_VkSwapchainCreateInfoKHR fallback = *createInfo;
                fallback.presentMode = retryModes[i];
                if (swapchain != NULL) *swapchain = 0u;
                DB_VkResult retryResult = real(device, &fallback, allocator, swapchain);
                fprintf(stderr,
                        "DroidBridgePanVKSwapchain: requestedMode=%d rejected=%d retryMode=%d result=%d surface=%llu extent=%ux%u\n",
                        requestedMode,
                        result,
                        fallback.presentMode,
                        retryResult,
                        (unsigned long long)createInfo->surface,
                        fallback.imageExtent.width,
                        fallback.imageExtent.height);
                fflush(stderr);
                if (retryResult == DB_VK_SUCCESS) return retryResult;
            }
        }
        return result;
    }

    uint32_t supported = 0u;
    uint32_t realCurrent = 0u;
    bool matchesSurface = false;
    bool identityRejected = false;
    pthread_mutex_lock(&g_db_vk_surface_transform_mutex);
    matchesSurface = (g_db_vk_surface_transform_surface == createInfo->surface);
    if (matchesSurface) {
        supported = g_db_vk_surface_supported_transforms;
        realCurrent = g_db_vk_surface_real_current_transform;
        identityRejected = g_db_vk_surface_identity_rejected;
    }
    pthread_mutex_unlock(&g_db_vk_surface_transform_mutex);

    if (matchesSurface && identityRejected) {
        DB_VkSwapchainCreateInfoKHR fallback = *createInfo;
        if (realCurrent != 0u && (supported & realCurrent) != 0u) {
            fallback.preTransform = realCurrent;
        }
        return real(device, &fallback, allocator, swapchain);
    }

    if (!matchesSurface
            || (supported & DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) == 0u) {
        if (!g_db_vk_swapchain_transform_logged) {
            g_db_vk_swapchain_transform_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanSurfaceTransform: swapchain identity not forced surface=%llu cached=%d requestedPreTransform=0x%x supported=0x%x\n",
                    (unsigned long long)createInfo->surface,
                    matchesSurface ? 1 : 0,
                    createInfo->preTransform,
                    supported);
            fflush(stderr);
        }
        return real(device, createInfo, allocator, swapchain);
    }

    DB_VkSwapchainCreateInfoKHR patched = *createInfo;
    const uint32_t requested = patched.preTransform;
    patched.preTransform = DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    if (!g_db_vk_swapchain_transform_logged
            || requested != DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
        g_db_vk_swapchain_transform_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanSurfaceTransform: vkCreateSwapchainKHR surface=%llu realCurrent=0x%x requestedPreTransform=0x%x -> IDENTITY extent=%ux%u presentMode=%d\n",
                (unsigned long long)createInfo->surface,
                realCurrent,
                requested,
                patched.imageExtent.width,
                patched.imageExtent.height,
                patched.presentMode);
        fflush(stderr);
    }
    DB_VkResult result = real(device, &patched, allocator, swapchain);
    if (result != DB_VK_SUCCESS
            && realCurrent != 0u
            && realCurrent != DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            && (supported & realCurrent) != 0u) {
        /*
         * Some Android WSI implementations (notably PanVK builds) advertise
         * IDENTITY in supportedTransforms but reject an IDENTITY swapchain for a
         * surface whose real currentTransform is a quarter-turn.  Do not strand
         * Minecraft on a valid VkSurfaceKHR with no swapchain: retry once using
         * the driver's real transform and remember that decision for this surface.
         * Subsequent capability queries then expose the real transform instead of
         * repeatedly asking the driver to create a swapchain it already rejected.
         */
        DB_VkSwapchainCreateInfoKHR fallback = *createInfo;
        fallback.preTransform = realCurrent;
        if (swapchain != NULL) *swapchain = 0u;
        DB_VkResult fallbackResult = real(device, &fallback, allocator, swapchain);
        fprintf(stderr,
                "DroidBridgeVulkanSurfaceTransform: identity swapchain rejected result=%d surface=%llu; retry realCurrent=0x%x fallbackResult=%d\n",
                result,
                (unsigned long long)createInfo->surface,
                realCurrent,
                fallbackResult);
        fflush(stderr);
        if (fallbackResult == DB_VK_SUCCESS) {
            pthread_mutex_lock(&g_db_vk_surface_transform_mutex);
            g_db_vk_surface_identity_rejected = true;
            g_db_vk_identity_swapchain = 0u;
            g_db_vk_identity_swapchain_extent.width = 0u;
            g_db_vk_identity_swapchain_extent.height = 0u;
            pthread_mutex_unlock(&g_db_vk_surface_transform_mutex);
            return fallbackResult;
        }
    }
    if (result == DB_VK_SUCCESS && swapchain != NULL && *swapchain != 0u) {
        pthread_mutex_lock(&g_db_vk_surface_transform_mutex);
        g_db_vk_identity_swapchain = *swapchain;
        g_db_vk_identity_swapchain_extent = patched.imageExtent;
        pthread_mutex_unlock(&g_db_vk_surface_transform_mutex);
    }
    return result;
}

static bool db_vk_should_suppress_identity_suboptimal(DB_VkSwapchainKHR swapchain) {
    if (!db_vk_surface_identity_enabled() || swapchain == 0u) return false;
    bool suppress = false;
    pthread_mutex_lock(&g_db_vk_surface_transform_mutex);
    suppress = (g_db_vk_identity_swapchain == swapchain
            && g_db_vk_surface_real_current_transform != 0u
            && g_db_vk_surface_real_current_transform != DB_VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            && g_db_vk_surface_current_extent.width == g_db_vk_identity_swapchain_extent.width
            && g_db_vk_surface_current_extent.height == g_db_vk_identity_swapchain_extent.height);
    pthread_mutex_unlock(&g_db_vk_surface_transform_mutex);
    return suppress;
}

static void db_vk_log_identity_suboptimal_suppressed(const char* source, DB_VkSwapchainKHR swapchain) {
    ++g_db_vk_identity_suboptimal_suppressed;
    if (!g_db_vk_identity_suboptimal_logged || g_db_vk_identity_suboptimal_suppressed <= 3u
            || (g_db_vk_identity_suboptimal_suppressed % 120u) == 0u) {
        g_db_vk_identity_suboptimal_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanSurfaceTransform: suppressed transform-only VK_SUBOPTIMAL_KHR source=%s swapchain=%llu count=%llu; real out-of-date errors remain authoritative\n",
                source != NULL ? source : "unknown",
                (unsigned long long)swapchain,
                (unsigned long long)g_db_vk_identity_suboptimal_suppressed);
        fflush(stderr);
    }
}

static DB_VkResult db_vk_acquire_next_image_khr(
        DB_VkDevice device, DB_VkSwapchainKHR swapchain, uint64_t timeout,
        DB_VkSemaphore semaphore, DB_VkFence fence, uint32_t* imageIndex) {
    DB_PFN_vkAcquireNextImageKHR real = g_db_real_vkAcquireNextImageKHR;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, swapchain, timeout, semaphore, fence, imageIndex);
    if (result == DB_VK_SUBOPTIMAL_KHR && db_vk_should_suppress_identity_suboptimal(swapchain)) {
        db_vk_log_identity_suboptimal_suppressed("vkAcquireNextImageKHR", swapchain);
        return DB_VK_SUCCESS;
    }
    return result;
}

static DB_VkResult db_vk_acquire_next_image2_khr(
        DB_VkDevice device, const DB_VkAcquireNextImageInfoKHR* acquireInfo, uint32_t* imageIndex) {
    DB_PFN_vkAcquireNextImage2KHR real = g_db_real_vkAcquireNextImage2KHR;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(device, acquireInfo, imageIndex);
    DB_VkSwapchainKHR swapchain = acquireInfo != NULL ? acquireInfo->swapchain : 0u;
    if (result == DB_VK_SUBOPTIMAL_KHR && db_vk_should_suppress_identity_suboptimal(swapchain)) {
        db_vk_log_identity_suboptimal_suppressed("vkAcquireNextImage2KHR", swapchain);
        return DB_VK_SUCCESS;
    }
    return result;
}

static DB_VkResult db_vk_queue_present_khr(DB_VkQueue queue, const DB_VkPresentInfoKHR* presentInfo) {
    DB_PFN_vkQueuePresentKHR real = g_db_real_vkQueuePresentKHR;
    if (real == NULL) return DB_VK_ERROR_INITIALIZATION_FAILED;
    DB_VkResult result = real(queue, presentInfo);
    if (presentInfo == NULL || presentInfo->pSwapchains == NULL) return result;

    bool identitySwapchainPresent = false;
    DB_VkSwapchainKHR identitySwapchain = 0u;
    for (uint32_t i = 0u; i < presentInfo->swapchainCount; ++i) {
        DB_VkSwapchainKHR swapchain = presentInfo->pSwapchains[i];
        if (!db_vk_should_suppress_identity_suboptimal(swapchain)) continue;
        identitySwapchainPresent = true;
        identitySwapchain = swapchain;
        if (presentInfo->pResults != NULL && presentInfo->pResults[i] == DB_VK_SUBOPTIMAL_KHR) {
            presentInfo->pResults[i] = DB_VK_SUCCESS;
            db_vk_log_identity_suboptimal_suppressed("vkQueuePresentKHR[pResults]", swapchain);
        }
    }
    if (result == DB_VK_SUBOPTIMAL_KHR && identitySwapchainPresent) {
        db_vk_log_identity_suboptimal_suppressed("vkQueuePresentKHR", identitySwapchain);
        return DB_VK_SUCCESS;
    }
    return result;
}

static void db_vk_capture_real_symbol(const char* name, void* symbol) {
    if (name == NULL || symbol == NULL) return;
    if (strcmp(name, "vkGetInstanceProcAddr") == 0) {
        g_db_real_vkGetInstanceProcAddr = (DB_PFN_vkGetInstanceProcAddr)symbol;
    } else if (strcmp(name, "vkGetDeviceProcAddr") == 0) {
        g_db_real_vkGetDeviceProcAddr = (DB_PFN_vkGetDeviceProcAddr)symbol;
    } else if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0) {
        g_db_real_vkGetPhysicalDeviceSurfaceCapabilitiesKHR =
                (DB_PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)symbol;
    } else if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilities2KHR") == 0) {
        g_db_real_vkGetPhysicalDeviceSurfaceCapabilities2KHR =
                (DB_PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR)symbol;
    } else if (strcmp(name, "vkCreateSwapchainKHR") == 0) {
        g_db_real_vkCreateSwapchainKHR = (DB_PFN_vkCreateSwapchainKHR)symbol;
    } else if (strcmp(name, "vkAcquireNextImageKHR") == 0) {
        g_db_real_vkAcquireNextImageKHR = (DB_PFN_vkAcquireNextImageKHR)symbol;
    } else if (strcmp(name, "vkAcquireNextImage2KHR") == 0) {
        g_db_real_vkAcquireNextImage2KHR = (DB_PFN_vkAcquireNextImage2KHR)symbol;
    } else if (strcmp(name, "vkQueuePresentKHR") == 0) {
        g_db_real_vkQueuePresentKHR = (DB_PFN_vkQueuePresentKHR)symbol;
    } else if (strcmp(name, "vkEnumerateDeviceExtensionProperties") == 0) {
        g_db_real_vkEnumerateDeviceExtensionProperties =
                (DB_PFN_vkEnumerateDeviceExtensionProperties)symbol;
    } else if (strcmp(name, "vkGetPhysicalDeviceProperties") == 0) {
        g_db_real_vkGetPhysicalDeviceProperties =
                (DB_PFN_vkGetPhysicalDeviceProperties)symbol;
    } else if (strcmp(name, "vkGetPhysicalDeviceProperties2") == 0
            || strcmp(name, "vkGetPhysicalDeviceProperties2KHR") == 0) {
        g_db_real_vkGetPhysicalDeviceProperties2 =
                (DB_PFN_vkGetPhysicalDeviceProperties2)symbol;
    } else if (strcmp(name, "vkGetPhysicalDeviceFeatures2") == 0
            || strcmp(name, "vkGetPhysicalDeviceFeatures2KHR") == 0) {
        g_db_real_vkGetPhysicalDeviceFeatures2 =
                (DB_PFN_vkGetPhysicalDeviceFeatures2)symbol;
    } else if (strcmp(name, "vkCmdPushDescriptorSetKHR") == 0) {
        g_db_real_vkCmdPushDescriptorSetKHR =
                (DB_PFN_vkCmdPushDescriptorSetKHR)symbol;
    } else if (strcmp(name, "vkCreateGraphicsPipelines") == 0) {
        g_db_real_vkCreateGraphicsPipelines =
                (DB_PFN_vkCreateGraphicsPipelines)symbol;
    } else if (strcmp(name, "vkCreateShaderModule") == 0) {
        g_db_real_vkCreateShaderModule = (DB_PFN_vkCreateShaderModule)symbol;
    } else if (strcmp(name, "vkDestroyShaderModule") == 0) {
        g_db_real_vkDestroyShaderModule = (DB_PFN_vkDestroyShaderModule)symbol;
    } else if (strcmp(name, "vkCmdBindPipeline") == 0) {
        g_db_real_vkCmdBindPipeline = (DB_PFN_vkCmdBindPipeline)symbol;
    } else if (strcmp(name, "vkCmdSetViewport") == 0) {
        g_db_real_vkCmdSetViewport = (DB_PFN_vkCmdSetViewport)symbol;
    } else if (strcmp(name, "vkCmdSetScissor") == 0) {
        g_db_real_vkCmdSetScissor = (DB_PFN_vkCmdSetScissor)symbol;
    } else if (strcmp(name, "vkCmdSetScissorWithCount") == 0
            || strcmp(name, "vkCmdSetScissorWithCountEXT") == 0) {
        g_db_real_vkCmdSetScissorWithCount = (DB_PFN_vkCmdSetScissorWithCount)symbol;
    } else if (strcmp(name, "vkCmdDraw") == 0) {
        g_db_real_vkCmdDraw = (DB_PFN_vkCmdDraw)symbol;
    } else if (strcmp(name, "vkSetDebugUtilsObjectNameEXT") == 0) {
        g_db_real_vkSetDebugUtilsObjectNameEXT =
                (DB_PFN_vkSetDebugUtilsObjectNameEXT)symbol;
    } else if (strcmp(name, "vkCreateSampler") == 0) {
        g_db_real_vkCreateSampler = (DB_PFN_vkCreateSampler)symbol;
    } else if (strcmp(name, "vkDestroySampler") == 0) {
        g_db_real_vkDestroySampler = (DB_PFN_vkDestroySampler)symbol;
    } else if (strcmp(name, "vkMapMemory") == 0) {
        g_db_real_vkMapMemory = (DB_PFN_vkMapMemory)symbol;
    } else if (strcmp(name, "vkUnmapMemory") == 0) {
        g_db_real_vkUnmapMemory = (DB_PFN_vkUnmapMemory)symbol;
    } else if (strcmp(name, "vkFlushMappedMemoryRanges") == 0) {
        g_db_real_vkFlushMappedMemoryRanges = (DB_PFN_vkFlushMappedMemoryRanges)symbol;
    } else if (strcmp(name, "vkBindBufferMemory") == 0) {
        g_db_real_vkBindBufferMemory = (DB_PFN_vkBindBufferMemory)symbol;
    } else if (strcmp(name, "vkBindBufferMemory2") == 0
            || strcmp(name, "vkBindBufferMemory2KHR") == 0) {
        g_db_real_vkBindBufferMemory2 = (DB_PFN_vkBindBufferMemory2)symbol;
    } else if (strcmp(name, "vkCreateImage") == 0) {
        g_db_real_vkCreateImage = (DB_PFN_vkCreateImage)symbol;
    } else if (strcmp(name, "vkDestroyImage") == 0) {
        g_db_real_vkDestroyImage = (DB_PFN_vkDestroyImage)symbol;
    } else if (strcmp(name, "vkCreateImageView") == 0) {
        g_db_real_vkCreateImageView = (DB_PFN_vkCreateImageView)symbol;
    } else if (strcmp(name, "vkDestroyImageView") == 0) {
        g_db_real_vkDestroyImageView = (DB_PFN_vkDestroyImageView)symbol;
    } else if (strcmp(name, "vkCmdCopyBuffer") == 0) {
        g_db_real_vkCmdCopyBuffer = (DB_PFN_vkCmdCopyBuffer)symbol;
    } else if (strcmp(name, "vkCmdCopyBuffer2") == 0
            || strcmp(name, "vkCmdCopyBuffer2KHR") == 0) {
        g_db_real_vkCmdCopyBuffer2 = (DB_PFN_vkCmdCopyBuffer2)symbol;
    } else if (strcmp(name, "vkCmdUpdateBuffer") == 0) {
        g_db_real_vkCmdUpdateBuffer = (DB_PFN_vkCmdUpdateBuffer)symbol;
    } else if (strcmp(name, "vkCmdCopyBufferToImage") == 0) {
        g_db_real_vkCmdCopyBufferToImage = (DB_PFN_vkCmdCopyBufferToImage)symbol;
    } else if (strcmp(name, "vkCmdCopyBufferToImage2") == 0
            || strcmp(name, "vkCmdCopyBufferToImage2KHR") == 0) {
        g_db_real_vkCmdCopyBufferToImage2 = (DB_PFN_vkCmdCopyBufferToImage2)symbol;
    } else if (strcmp(name, "vkCmdCopyImage") == 0) {
        g_db_real_vkCmdCopyImage = (DB_PFN_vkCmdCopyImage)symbol;
    } else if (strcmp(name, "vkCmdCopyImage2") == 0
            || strcmp(name, "vkCmdCopyImage2KHR") == 0) {
        g_db_real_vkCmdCopyImage2 = (DB_PFN_vkCmdCopyImage2)symbol;
    } else if (strcmp(name, "vkCmdBlitImage") == 0) {
        g_db_real_vkCmdBlitImage = (DB_PFN_vkCmdBlitImage)symbol;
    } else if (strcmp(name, "vkCmdBlitImage2") == 0
            || strcmp(name, "vkCmdBlitImage2KHR") == 0) {
        g_db_real_vkCmdBlitImage2 = (DB_PFN_vkCmdBlitImage2)symbol;
    } else if (strcmp(name, "vkCmdPipelineBarrier") == 0) {
        g_db_real_vkCmdPipelineBarrier = (DB_PFN_vkCmdPipelineBarrier)symbol;
    } else if (strcmp(name, "vkCmdPipelineBarrier2") == 0
            || strcmp(name, "vkCmdPipelineBarrier2KHR") == 0) {
        g_db_real_vkCmdPipelineBarrier2 = (DB_PFN_vkCmdPipelineBarrier2)symbol;
    } else if (strcmp(name, "vkCmdBeginRendering") == 0
            || strcmp(name, "vkCmdBeginRenderingKHR") == 0) {
        g_db_real_vkCmdBeginRendering = (DB_PFN_vkCmdBeginRendering)symbol;
    } else if (strcmp(name, "vkCmdEndRendering") == 0
            || strcmp(name, "vkCmdEndRenderingKHR") == 0) {
        g_db_real_vkCmdEndRendering = (DB_PFN_vkCmdEndRendering)symbol;
    } else if (strcmp(name, "vkBeginCommandBuffer") == 0) {
        g_db_real_vkBeginCommandBuffer = (DB_PFN_vkBeginCommandBuffer)symbol;
    } else if (strcmp(name, "vkResetCommandBuffer") == 0) {
        g_db_real_vkResetCommandBuffer = (DB_PFN_vkResetCommandBuffer)symbol;
    } else if (strcmp(name, "vkFreeCommandBuffers") == 0) {
        g_db_real_vkFreeCommandBuffers = (DB_PFN_vkFreeCommandBuffers)symbol;
    }
}

static DB_PFN_vkVoidFunction db_vk_get_instance_proc_addr(DB_VkInstance instance, const char* name);
static DB_PFN_vkVoidFunction db_vk_get_device_proc_addr(DB_VkDevice device, const char* name);

static DB_PFN_vkVoidFunction db_vk_override_resolved_proc(
        const char* name, DB_PFN_vkVoidFunction real) {
    if (name == NULL || !db_vk_any_compat_enabled()) return real;
    db_vk_capture_real_symbol(name, (void*)real);
    if (strcmp(name, "vkGetInstanceProcAddr") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_instance_proc_addr;
    }
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_device_proc_addr;
    }
    if (db_vk_surface_identity_enabled()
            && strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_physical_device_surface_capabilities_khr;
    }
    if (db_vk_surface_identity_enabled()
            && strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilities2KHR") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_physical_device_surface_capabilities2_khr;
    }
    if ((db_vk_surface_identity_enabled() || db_vk_custom_panvk_swapchain_compat_enabled())
            && strcmp(name, "vkCreateSwapchainKHR") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_create_swapchain_khr;
    }
    if (db_vk_surface_identity_enabled()
            && strcmp(name, "vkAcquireNextImageKHR") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_acquire_next_image_khr;
    }
    if (db_vk_surface_identity_enabled()
            && strcmp(name, "vkAcquireNextImage2KHR") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_acquire_next_image2_khr;
    }
    if (db_vk_surface_identity_enabled()
            && strcmp(name, "vkQueuePresentKHR") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_queue_present_khr;
    }
    if (db_vk_disable_multi_draw_enabled()
            && strcmp(name, "vkEnumerateDeviceExtensionProperties") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_enumerate_device_extension_properties;
    }
    if (db_vk_api_cap_enabled()
            && strcmp(name, "vkGetPhysicalDeviceProperties") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_physical_device_properties;
    }
    if (db_vk_api_cap_enabled()
            && (strcmp(name, "vkGetPhysicalDeviceProperties2") == 0
                || strcmp(name, "vkGetPhysicalDeviceProperties2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_physical_device_properties2;
    }
    if (db_vk_disable_zero_divisor_enabled()
            && (strcmp(name, "vkGetPhysicalDeviceFeatures2") == 0
                || strcmp(name, "vkGetPhysicalDeviceFeatures2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_get_physical_device_features2;
    }
    if ((db_vk_pipeline_diagnostics_enabled() || db_vk_entity_shader_fallback_enabled()
            || db_vk_atlas_fragment_repair_enabled())
            && strcmp(name, "vkCreateShaderModule") == 0) {
        if (db_vk_atlas_fragment_repair_enabled() && !g_db_vk_atlas_fragment_repair_logged) {
            g_db_vk_atlas_fragment_repair_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasCoverageV4: armed exact MC26.2 atlas vertex/coverage isolation vhash=%016llx fhash=%016llx; atlas builder fragment output is forced opaque magenta, source sampling is bypassed, original vertex placement UBO is preserved, and UBO source bytes are captured read-only\n",
                    (unsigned long long)DB_VK_ATLAS_BUILDER_VERTEX_HASH,
                    (unsigned long long)DB_VK_ATLAS_BUILDER_FRAGMENT_HASH);
            fflush(stderr);
        }
        if (db_vk_entity_shader_fallback_enabled()
                && !g_db_vk_entity_shader_fallback_intercept_logged) {
            g_db_vk_entity_shader_fallback_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanAtlasAuditV2: armed exact MC26.2 Qualcomm atlas render/write-path audit v2 badF=%016llx bands=uv/texture/textureLod0/texelFetch; caches working item binding6 for comparison only; target descriptors are not mutated\n",
                    (unsigned long long)DB_VK_BAD_ENTITY_FRAGMENT_HASH);
            fflush(stderr);
        }
        return (DB_PFN_vkVoidFunction)&db_vk_create_shader_module;
    }
    if ((db_vk_pipeline_diagnostics_enabled() || db_vk_entity_shader_fallback_enabled()
            || db_vk_atlas_fragment_repair_enabled())
            && strcmp(name, "vkDestroyShaderModule") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_destroy_shader_module;
    }
    if (db_vk_panvk_g57_safe_enabled()
            && strcmp(name, "vkCmdSetScissor") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_set_scissor;
    }
    if (db_vk_panvk_g57_safe_enabled()
            && (strcmp(name, "vkCmdSetScissorWithCount") == 0
                || strcmp(name, "vkCmdSetScissorWithCountEXT") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_set_scissor_with_count;
    }
    if ((db_vk_entity_aux_diagnostics_enabled()
            || db_vk_atlas_build_input_audit_enabled()
            || db_vk_static_atlas_layout_repair_enabled())
            && strcmp(name, "vkCmdBindPipeline") == 0) {
        if (!g_db_vk_pipeline_correlation_intercept_logged) {
            g_db_vk_pipeline_correlation_intercept_logged = true;
            if (db_vk_atlas_build_input_audit_enabled()
                    || db_vk_static_atlas_layout_repair_enabled()) {
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasBuildInput: graphics-pipeline correlation armed; atlas-build packets will include exact bound pipeline and shader hashes\n");
            } else {
                fprintf(stderr,
                        "DroidBridgeVulkanEntityShader: pipeline correlation armed; binding-6 packets will include the exact bound graphics pipeline and shader hashes\n");
            }
            fflush(stderr);
        }
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_bind_pipeline;
    }
    if ((db_vk_pipeline_diagnostics_enabled() || db_vk_entity_normal_format_repair_enabled()
            || db_vk_entity_shader_fallback_enabled())
            && strcmp(name, "vkCreateGraphicsPipelines") == 0) {
        if (!g_db_vk_pipeline_diag_intercept_logged) {
            g_db_vk_pipeline_diag_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanPipeline: graphics-pipeline diagnostics armed entityNormalFormatRepair=%d\n",
                    db_vk_entity_normal_format_repair_enabled() ? 1 : 0);
            fflush(stderr);
            db_vk_log_shader_toolchain_once();
        }
        if (db_vk_entity_normal_format_repair_enabled()
                && !g_db_vk_entity_normal_format_intercept_logged) {
            g_db_vk_entity_normal_format_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanEntityVertex: Adreno entity-normal repair armed; exact stride=36 attrs=6 normal offset=32 format R8G8B8_SNORM will be expanded to R8G8B8A8_SNORM\n");
            fflush(stderr);
        }
        return (DB_PFN_vkVoidFunction)&db_vk_create_graphics_pipelines;
    }
    if (db_vk_pipeline_diagnostics_enabled()
            && strcmp(name, "vkSetDebugUtilsObjectNameEXT") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_set_debug_utils_object_name_ext;
    }
    if ((db_vk_push_descriptor_fix_enabled() || db_vk_image_diagnostics_enabled()
            || db_vk_special_draw_sync_repair_enabled()
            || db_vk_entity_aux_diagnostics_enabled()
            || db_vk_atlas_build_input_audit_enabled()
            || db_vk_static_atlas_layout_repair_enabled()
            || db_vk_atlas_ubo_flush_repair_enabled()
            || db_vk_atlas_direct_compose_repair_enabled())
            && strcmp(name, "vkCmdPushDescriptorSetKHR") == 0) {
        if (!g_db_vk_push_intercept_logged) {
            g_db_vk_push_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanCompat: vkCmdPushDescriptorSetKHR observer armed real=%p rewrite=%d specialDrawSync=%d entityAuxDiagnostics=%d\n",
                    (void*)real,
                    db_vk_push_descriptor_fix_enabled() ? 1 : 0,
                    db_vk_special_draw_sync_repair_enabled() ? 1 : 0,
                    db_vk_entity_aux_diagnostics_enabled() ? 1 : 0);
            fflush(stderr);
        }
        if (db_vk_entity_aux_diagnostics_enabled()
                && !g_db_vk_entity_aux_intercept_logged) {
            g_db_vk_entity_aux_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanEntityShader: auxiliary descriptor diagnostics armed; binding-6 packets will log companion images/samplers and buffer ranges without mutation\n");
            fflush(stderr);
        }
        if (db_vk_special_draw_sync_repair_enabled()
                && !g_db_vk_special_draw_sync_intercept_logged) {
            g_db_vk_special_draw_sync_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanSpecialSync: binding-6 special/entity HOST_WRITE visibility repair armed; descriptors and image layouts remain unchanged\n");
            fflush(stderr);
        }
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_push_descriptor_set_khr;
    }
    if (db_vk_atlas_direct_compose_repair_enabled()
            && strcmp(name, "vkCmdDraw") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_draw;
    }
    if (db_vk_entity_aux_diagnostics_enabled()
            && strcmp(name, "vkCreateSampler") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_create_sampler;
    }
    if (db_vk_entity_aux_diagnostics_enabled()
            && strcmp(name, "vkDestroySampler") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_destroy_sampler;
    }
    if (db_vk_memory_tracking_enabled() && strcmp(name, "vkMapMemory") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_map_memory;
    }
    if (db_vk_memory_tracking_enabled() && strcmp(name, "vkUnmapMemory") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_unmap_memory;
    }
    if (db_vk_asset_staging_repair_enabled() && strcmp(name, "vkFlushMappedMemoryRanges") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_flush_mapped_memory_ranges;
    }
    if (db_vk_memory_tracking_enabled() && strcmp(name, "vkBindBufferMemory") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_bind_buffer_memory;
    }
    if (db_vk_memory_tracking_enabled()
            && (strcmp(name, "vkBindBufferMemory2") == 0
                || strcmp(name, "vkBindBufferMemory2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_bind_buffer_memory2;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkCreateImage") == 0) {
        if (!g_db_vk_image_diag_intercept_logged) {
            g_db_vk_image_diag_intercept_logged = true;
            fprintf(stderr,
                    "DroidBridgeVulkanImage: image creation/copy/barrier diagnostics armed\n");
            fflush(stderr);
        }
        return (DB_PFN_vkVoidFunction)&db_vk_create_image;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkDestroyImage") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_destroy_image;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkCreateImageView") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_create_image_view;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkDestroyImageView") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_destroy_image_view;
    }
    if ((db_vk_atlas_ubo_flush_repair_enabled() || db_vk_atlas_direct_compose_repair_enabled()
            || db_vk_atlas_fragment_repair_enabled())
            && strcmp(name, "vkCmdCopyBuffer") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_copy_buffer;
    }
    if ((db_vk_atlas_ubo_flush_repair_enabled() || db_vk_atlas_direct_compose_repair_enabled()
            || db_vk_atlas_fragment_repair_enabled())
            && (strcmp(name, "vkCmdCopyBuffer2") == 0
                || strcmp(name, "vkCmdCopyBuffer2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_copy_buffer2;
    }
    if ((db_vk_atlas_ubo_flush_repair_enabled() || db_vk_atlas_direct_compose_repair_enabled()
            || db_vk_atlas_fragment_repair_enabled())
            && strcmp(name, "vkCmdUpdateBuffer") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_update_buffer;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkCmdCopyBufferToImage") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_copy_buffer_to_image;
    }
    if (db_vk_image_diagnostics_enabled()
            && (strcmp(name, "vkCmdCopyBufferToImage2") == 0
                || strcmp(name, "vkCmdCopyBufferToImage2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_copy_buffer_to_image2;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkCmdCopyImage") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_copy_image;
    }
    if (db_vk_image_diagnostics_enabled()
            && (strcmp(name, "vkCmdCopyImage2") == 0
                || strcmp(name, "vkCmdCopyImage2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_copy_image2;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkCmdBlitImage") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_blit_image;
    }
    if (db_vk_image_diagnostics_enabled()
            && (strcmp(name, "vkCmdBlitImage2") == 0
                || strcmp(name, "vkCmdBlitImage2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_blit_image2;
    }
    if (db_vk_image_diagnostics_enabled()
            && strcmp(name, "vkCmdPipelineBarrier") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_pipeline_barrier;
    }
    if (db_vk_image_diagnostics_enabled()
            && (strcmp(name, "vkCmdPipelineBarrier2") == 0
                || strcmp(name, "vkCmdPipelineBarrier2KHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_pipeline_barrier2;
    }
    if ((db_vk_image_diagnostics_enabled() || db_vk_atlas_sync_repair_enabled()
            || db_vk_atlas_source_sync_repair_enabled()
            || db_vk_atlas_build_input_audit_enabled()
            || db_vk_static_atlas_layout_repair_enabled()
            || db_vk_atlas_ubo_flush_repair_enabled()
            || db_vk_atlas_direct_compose_repair_enabled())
            && (strcmp(name, "vkCmdBeginRendering") == 0
                || strcmp(name, "vkCmdBeginRenderingKHR") == 0)) {
        if (!g_db_vk_atlas_sync_intercept_logged) {
            g_db_vk_atlas_sync_intercept_logged = true;
            if (db_vk_atlas_sync_repair_enabled()) {
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasSync: sampled offscreen target visibility repair armed; descriptors and layouts remain unchanged\n");
            } else {
                fprintf(stderr,
                        "DroidBridgeVulkanAtlasAuditV2: dynamic-rendering write tracking armed; mutation=none\n");
            }
            fflush(stderr);
        }
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_begin_rendering;
    }
    if ((db_vk_image_diagnostics_enabled() || db_vk_atlas_sync_repair_enabled()
            || db_vk_atlas_source_sync_repair_enabled()
            || db_vk_atlas_build_input_audit_enabled()
            || db_vk_static_atlas_layout_repair_enabled()
            || db_vk_atlas_ubo_flush_repair_enabled()
            || db_vk_atlas_direct_compose_repair_enabled())
            && (strcmp(name, "vkCmdEndRendering") == 0
                || strcmp(name, "vkCmdEndRenderingKHR") == 0)) {
        return (DB_PFN_vkVoidFunction)&db_vk_cmd_end_rendering;
    }
    if (db_vk_push_descriptor_fix_enabled()
            && strcmp(name, "vkBeginCommandBuffer") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_begin_command_buffer;
    }
    if (db_vk_push_descriptor_fix_enabled()
            && strcmp(name, "vkResetCommandBuffer") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_reset_command_buffer;
    }
    if (db_vk_push_descriptor_fix_enabled()
            && strcmp(name, "vkFreeCommandBuffers") == 0) {
        return (DB_PFN_vkVoidFunction)&db_vk_free_command_buffers;
    }
    return real;
}

static DB_PFN_vkVoidFunction db_vk_get_instance_proc_addr(
        DB_VkInstance instance, const char* name) {
    DB_PFN_vkGetInstanceProcAddr real = g_db_real_vkGetInstanceProcAddr;
    if (real == NULL) return NULL;
    if (!g_db_vk_proxy_gipa_logged) {
        g_db_vk_proxy_gipa_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: proxy vkGetInstanceProcAddr active first=%s instance=%p\n",
                name != NULL ? name : "<null>",
                (void*)instance);
        fflush(stderr);
    }
    DB_PFN_vkVoidFunction resolved = real(instance, name);
    return db_vk_override_resolved_proc(name, resolved);
}

static DB_PFN_vkVoidFunction db_vk_get_device_proc_addr(
        DB_VkDevice device, const char* name) {
    DB_PFN_vkGetDeviceProcAddr real = g_db_real_vkGetDeviceProcAddr;
    if (real == NULL) return NULL;
    if (!g_db_vk_proxy_gdpa_logged) {
        g_db_vk_proxy_gdpa_logged = true;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: proxy vkGetDeviceProcAddr active first=%s device=%p\n",
                name != NULL ? name : "<null>",
                (void*)device);
        fflush(stderr);
    }
    DB_PFN_vkVoidFunction resolved = real(device, name);
    return db_vk_override_resolved_proc(name, resolved);
}

__attribute__((used, visibility("default"))) DB_PFN_vkVoidFunction
droidbridge_vulkan_compat_get_instance_proc_addr(
        DB_VkInstance instance, const char* name) {
    return db_vk_get_instance_proc_addr(instance, name);
}

__attribute__((used, visibility("default"))) DB_PFN_vkVoidFunction
droidbridge_vulkan_compat_get_device_proc_addr(
        DB_VkDevice device, const char* name) {
    return db_vk_get_device_proc_addr(device, name);
}

static void* droidbridge_vulkan_compat_override_symbol(void* handle, const char* name) {
    if (handle == NULL || name == NULL || !db_vk_any_compat_enabled()) return NULL;
    db_vk_log_compat_once();

    /*
     * When LWJGL is explicitly pointed at libdroidbridge_vulkan_proxy.so,
     * return the proxy's uniquely named entries directly. Do not feed the
     * proxy's standard vkGet*ProcAddr export back into db_vk_capture_real_symbol:
     * doing that replaces the saved real Android loader address with the proxy
     * and either bypasses interception or creates a recursive forwarding loop.
     */
    if (handle == g_db_vk_proxy_loader_handle) {
        const char* proxyName = NULL;
        if (strcmp(name, "vkGetInstanceProcAddr") == 0) {
            proxyName = "droidbridge_vulkan_proxy_get_instance_proc_addr";
        } else if (strcmp(name, "vkGetDeviceProcAddr") == 0) {
            proxyName = "droidbridge_vulkan_proxy_get_device_proc_addr";
        }
        if (proxyName != NULL) {
            void* proxyEntry = dlsym(handle, proxyName);
            if (proxyEntry != NULL) {
                if (!g_db_vk_proxy_symbol_logged) {
                    g_db_vk_proxy_symbol_logged = true;
                    fprintf(stderr,
                            "DroidBridgeVulkanCompat: LWJGL resolved authoritative proxy symbol=%s address=%p\n",
                            proxyName,
                            proxyEntry);
                    fflush(stderr);
                }
                return proxyEntry;
            }
        }
    }

    void* real = dlsym(handle, name);
    if (real == NULL) return NULL;
    if ((strcmp(name, "vkGetInstanceProcAddr") == 0
                && real == (void*)&db_vk_get_instance_proc_addr)
            || (strcmp(name, "vkGetDeviceProcAddr") == 0
                && real == (void*)&db_vk_get_device_proc_addr)) {
        return real;
    }
    DB_PFN_vkVoidFunction overridden = db_vk_override_resolved_proc(
            name, (DB_PFN_vkVoidFunction)real);
    return overridden != (DB_PFN_vkVoidFunction)real ? (void*)overridden : NULL;
}


static jlong ndlsym_branding_hook(__attribute__((unused)) JNIEnv* env,
                                  __attribute__((unused)) jclass clazz,
                                  jlong handle_ptr,
                                  jlong name_ptr) {
    void* handle = (void*) handle_ptr;
    const char* name = (const char*) name_ptr;

    void* vulkan_override = droidbridge_vulkan_compat_override_symbol(handle, name);
    if (vulkan_override != NULL) {
        return (jlong)(uintptr_t)vulkan_override;
    }

    void* sdl_override = droidbridge_sdl3_override_lwjgl_symbol(handle, name);
    if (sdl_override != NULL) {
        return (jlong)(uintptr_t)sdl_override;
    }

    /*
     * Snapshot 4's OpenGL capability discovery does not necessarily use
     * SDL_GL_GetProcAddress. LWJGL frequently resolves the wrapper's GL
     * exports directly through this ndlsym native. Filter this authoritative
     * route as well so MobileGlues cannot advertise immutable persistent
     * storage after the SDL/EGL bridge has already done the right thing.
     */
    if (droidbridge_snapshot4_safe_buffers_enabled()
            && droidbridge_is_blocked_buffer_storage_proc(name)) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
                "Blocking Snapshot 4 buffer-storage proc %s", name);
        fprintf(stderr,
                "DroidBridgeSDL3GL: blocked direct LWJGL proc=%s\n", name);
        return 0;
    }

    /* LWJGL desktop OpenGL on Linux requires a GLX-style proc resolver.
     * When the DroidBridge OpenGL proxy is backed by Mesa EGL (Kopper/direct
     * Mesa), synthesize glXGetProcAddress[ARB] and forward each lookup to
     * eglGetProcAddress on the already-loaded provider. */
    if (name != NULL
            && (strcmp(name, "glXGetProcAddress") == 0
                || strcmp(name, "glXGetProcAddressARB") == 0)) {
        if (droidbridge_truthy_env("DROIDBRIDGE_SIMPLEFPE_ACTIVE")) {
            void* simplefpe_glx = droidbridge_resolve_simplefpe_gl_symbol(name);
            if (simplefpe_glx != NULL) {
                fprintf(stderr,
                        "DroidBridgeSimpleFPE: authoritative GLX resolver name=%s handle=%p proc=%p\n",
                        name, handle, simplefpe_glx);
                fflush(stderr);
                return (jlong)(uintptr_t)simplefpe_glx;
            }
        }
        if (handle == g_droidbridge_opengl_proxy_handle) {
            /* Kopper needs the DroidBridge GLX->EGL adapter as well. Returning
             * Mesa's provider-native glXGetProcAddress here bypasses
             * droidbridge_resolve_gl_symbol(), which means our final-present
             * blit scaler is never installed and scaled Minecraft frames land
             * only in the lower-left corner of the full Android drawable. */
            if (droidbridge_is_kopper_zink_renderer()) {
                static bool logged_kopper_glx_adapter = false;
                if (!logged_kopper_glx_adapter) {
                    logged_kopper_glx_adapter = true;
                    fprintf(stderr,
                            "DroidBridgeKopperPresentScale: canonical GLX->EGL resolver enabled for final-blit scaling\n");
                    fflush(stderr);
                }
                return (jlong)(uintptr_t)&droidbridge_glx_get_proc_address_egl;
            }

            /* 26.3+ direct Freedreno needs one surgical GL-proc substitution:
             * glBlitNamedFramebuffer -> bind/blit/restore. Returning Mesa's
             * native GLX resolver here would bypass DroidBridge's symbol router
             * entirely, so use the canonical GLX->EGL adapter for this route.
             * Every symbol except the named blit still resolves to Mesa's exact
             * eglGetProcAddress result. */
            if (droidbridge_freedreno_renderpearl_blit_compat_enabled()) {
                static bool logged_freedreno_glx_adapter = false;
                if (!logged_freedreno_glx_adapter) {
                    logged_freedreno_glx_adapter = true;
                    fprintf(stderr,
                            "DroidBridgeFreedrenoPresent: canonical GLX->EGL resolver enabled for 26.3+ named-blit compatibility\n");
                    fflush(stderr);
                }
                return (jlong)(uintptr_t)&droidbridge_glx_get_proc_address_egl;
            }

            /* The current Mesa build exports glXGetProcAddress as a native alias of
             * eglGetProcAddress. Prefer the provider's own export so LWJGL sees
             * the same provider resolver. The synthetic adapter remains
             * only for older/wrapped EGL providers that genuinely lack GLX. */
            void* provider_glx = dlsym(handle, name);
            if (provider_glx == NULL && strcmp(name, "glXGetProcAddressARB") == 0) {
                provider_glx = dlsym(handle, "glXGetProcAddress");
            }
            if (provider_glx != NULL) {
                fprintf(stderr,
                        "DroidBridgeSDL3GL: using provider-native GLX resolver name=%s handle=%p proc=%p\n",
                        name, handle, provider_glx);
                fflush(stderr);
                return (jlong)(uintptr_t)provider_glx;
            }
            fprintf(stderr,
                    "DroidBridgeSDL3GL: provider lacks native GLX resolver; using GLX->EGL adapter name=%s handle=%p\n",
                    name, handle);
            return (jlong)(uintptr_t)&droidbridge_glx_get_proc_address_egl;
        }
    }

    if (handle == g_droidbridge_opengl_proxy_handle
            && droidbridge_renderpearl_requires_canonical_gl_procs()
            && name != NULL && strcmp(name, "glGetError") == 0) {
        void* gl_get_error = droidbridge_resolve_gl_symbol(handle, name);
        const char* renderer = getenv("DROIDBRIDGE_RENDERER");
        fprintf(stderr,
                "DroidBridgeSDL3GL: RenderPearl glGetError canonical renderer=%s proc=%p handle=%p\n",
                renderer != NULL ? renderer : "<unset>", gl_get_error, handle);
        return (jlong)(uintptr_t)gl_get_error;
    }

    if (name != NULL && strcmp(name, "glGetString") == 0) {
        db_gl_get_string_fn real = (db_gl_get_string_fn)
                droidbridge_resolve_gl_symbol(handle, name);
        if (real != NULL) {
            g_real_glGetString = real;
            printf("LWJGL linkerhook-v27: returning DroidBridge glGetString compatibility filter for handle=%p\n",
                   handle);
            return (jlong)(uintptr_t)&droidbridge_glGetString_compat_filter;
        }
    }

    if (droidbridge_snapshot4_safe_buffers_enabled()
            && name != NULL && strcmp(name, "glGetStringi") == 0) {
        db_gl_get_string_i_fn real = (db_gl_get_string_i_fn)
                droidbridge_resolve_gl_symbol(handle, name);
        if (real != NULL) {
            g_real_glGetStringi = real;
            return (jlong)(uintptr_t)&droidbridge_glGetStringi_compat_filter;
        }
    }

    if (droidbridge_snapshot4_safe_buffers_enabled()
            && name != NULL && strcmp(name, "glGetIntegerv") == 0) {
        db_gl_get_integerv_fn real = (db_gl_get_integerv_fn)
                droidbridge_resolve_gl_symbol(handle, name);
        if (real != NULL) {
            g_real_glGetIntegerv = real;
            return (jlong)(uintptr_t)&droidbridge_glGetIntegerv_compat_filter;
        }
    }

    if (droidbridge_mobileglues_framebuffer_srgb_workaround_enabled()
            && name != NULL) {
        if (strcmp(name, "glEnable") == 0) {
            g_real_glEnable = (db_gl_enable_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glDisable == NULL) {
                g_real_glDisable = (db_gl_disable_fn)
                        droidbridge_resolve_gl_symbol(handle, "glDisable");
            }
            if (g_real_glEnable != NULL) {
                return (jlong)(uintptr_t)&droidbridge_glEnable_mobileglues_filter;
            }
        } else if (strcmp(name, "glDisable") == 0) {
            g_real_glDisable = (db_gl_disable_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glDisable != NULL) {
                return (jlong)(uintptr_t)&droidbridge_glDisable_mobileglues_filter;
            }
        } else if (strcmp(name, "glIsEnabled") == 0) {
            g_real_glIsEnabled = (db_gl_is_enabled_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glIsEnabled != NULL) {
                return (jlong)(uintptr_t)&droidbridge_glIsEnabled_mobileglues_filter;
            }
        }
    }

    /* Snapshot 4 can resolve wrapped GL either through SDL_GL_GetProcAddress
     * or directly through LWJGL ndlsym. Guard both renderers on this direct path. */
    if (droidbridge_is_wrapped_context_guard_renderer() && name != NULL) {
        if (strcmp(name, "glCreateShader") == 0) {
            g_real_glCreateShader = (db_gl_create_shader_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glCreateShader != NULL) {
                return (jlong)(uintptr_t)&droidbridge_glCreateShader_context_guard;
            }
        } else if (strcmp(name, "glShaderSource") == 0) {
            g_real_glShaderSource = (db_gl_shader_source_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glShaderSource != NULL) {
                return (jlong)(uintptr_t)(droidbridge_is_mobileglues_wrapped_renderer()
                        ? &droidbridge_glShaderSource_mobileglues_normalized
                        : &droidbridge_glShaderSource_context_guard);
            }
        } else if (strcmp(name, "glCompileShader") == 0) {
            g_real_glCompileShader = (db_gl_compile_shader_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glCompileShader != NULL) {
                return (jlong)(uintptr_t)&droidbridge_glCompileShader_context_guard;
            }
        } else if (strcmp(name, "glCreateProgram") == 0) {
            g_real_glCreateProgram = (db_gl_create_program_fn)
                    droidbridge_resolve_gl_symbol(handle, name);
            if (g_real_glCreateProgram != NULL) {
                return (jlong)(uintptr_t)&droidbridge_glCreateProgram_context_guard;
            }
        }
    }

    // Core/legacy GL exports can arrive through more than one equivalent
    // MobileGlues/libGL handle. Keep SFPEW authoritative for every LWJGL GL
    // lookup while its MobileGlues integration is active.
    if (droidbridge_truthy_env("DROIDBRIDGE_SIMPLEFPE_ACTIVE")
            && droidbridge_is_gl_symbol_name(name)) {
        void* routed = droidbridge_resolve_gl_symbol(handle, name);
        if (routed != NULL) {
            static bool logged_simplefpe_authoritative = false;
            if (!logged_simplefpe_authoritative) {
                logged_simplefpe_authoritative = true;
                __android_log_print(ANDROID_LOG_INFO, TAG,
                        "SimpleFPEWrapper authoritative LWJGL GL routing active first=%s handle=%p proc=%p",
                        name, handle, routed);
                fprintf(stderr,
                        "DroidBridgeSimpleFPE: authoritative LWJGL GL routing active first=%s handle=%p proc=%p\n",
                        name, handle, routed);
                fflush(stderr);
            }
            return (jlong)(uintptr_t)routed;
        }
    }

    return (jlong)(uintptr_t)dlsym(handle, name);
}

static bool installLwjglDlopenHookForEnv(JNIEnv* env, const char* origin) {
    if (env == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "LWJGL hook registration skipped: JNIEnv is null origin=%s",
                            origin != NULL ? origin : "unknown");
        fprintf(stderr,
                "DroidBridgeSDL3GL: linker hook registration skipped null JNIEnv origin=%s\n",
                origin != NULL ? origin : "unknown");
        return false;
    }

    const bool early_bootstrap = origin != NULL
            && strcmp(origin, "OpenJDKBootstrapShim") == 0;

    /* Snapshot 5 reaches org.lwjgl.opengl.GL before GLFW/CallbackBridge. Register
     * DynamicLinkLoader first so libGLDroidBridge.so is intercepted before any GL
     * class can trigger native discovery. The optional GL30/GL15 buffer-map JNI
     * fallback is intentionally deferred during this early bootstrap because
     * resolving those classes would initialize OpenGL too soon. */
    if (!g_droidbridge_runtime_hooks_installed) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "Installing LWJGL ndlopen/ndlsym hook origin=%s",
                            origin != NULL ? origin : "unknown");
        fprintf(stderr,
                "DroidBridgeSDL3GL: installing OpenJDK linker hooks origin=%s\n",
                origin != NULL ? origin : "unknown");

        jclass dynamicLinkLoader =
                (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
        if (dynamicLinkLoader == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "Failed to find org/lwjgl/system/linux/DynamicLinkLoader origin=%s",
                                origin != NULL ? origin : "unknown");
            fprintf(stderr,
                    "DroidBridgeSDL3GL: DynamicLinkLoader class not found origin=%s\n",
                    origin != NULL ? origin : "unknown");
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            return false;
        }

        JNINativeMethod linkerMethods[] = {
                {"ndlopen", "(JI)J", (void*)&ndlopen_bugfix},
                {"ndlsym", "(JJ)J", (void*)&ndlsym_branding_hook}
        };

        jint result = (*env)->RegisterNatives(env, dynamicLinkLoader, linkerMethods, 2);
        (*env)->DeleteLocalRef(env, dynamicLinkLoader);
        if (result != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "Failed to register hooked ndlopen/ndlsym origin=%s result=%d",
                                origin != NULL ? origin : "unknown", result);
            fprintf(stderr,
                    "DroidBridgeSDL3GL: linker hook registration failed origin=%s result=%d\n",
                    origin != NULL ? origin : "unknown", result);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            return false;
        }

        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "LWJGL ndlopen/ndlsym hook installed origin=%s",
                            origin != NULL ? origin : "unknown");
        fprintf(stderr,
                "DroidBridgeSDL3GL: OpenJDK linker hooks installed origin=%s\n",
                origin != NULL ? origin : "unknown");
        g_droidbridge_runtime_hooks_installed = true;
    }

    if (!early_bootstrap) {
        (void)droidbridge_register_gl_string_reporting(env);
        (void)droidbridge_register_buffer_map_fallback(env);
    } else {
        fprintf(stderr,
                "DroidBridgeNativeMesa: deferred GL buffer-map fallback until after early linker hook\n");
    }

    return g_droidbridge_runtime_hooks_installed;
}

void installLwjglDlopenHook(void) {
    JNIEnv* env = droidbridge_environ != NULL
            ? droidbridge_environ->runtimeJNIEnvPtr_JRE : NULL;
    (void) installLwjglDlopenHookForEnv(env, "JNI_OnLoad");
}

/*
 * Called by the uniquely named libdroidbridge_openjdk_hooks.so bootstrap.
 * That library is loaded only inside the embedded OpenJDK VM, avoiding the
 * two-VM native-library association problem caused by libdroidbridge_runtime
 * already being loaded by Android/ART.
 */
__attribute__((visibility("default")))
int droidbridge_install_openjdk_runtime_hooks_for_env(JNIEnv* env) {
    if (env == NULL) return 0;
    if (droidbridge_environ != NULL) {
        droidbridge_environ->runtimeJNIEnvPtr_JRE = env;
    }
    (void) installLwjglDlopenHookForEnv(env, "OpenJDKBootstrapShim");
    /* The GL30/GL15 map fallback may not be available this early, but the
     * DynamicLinkLoader ndlopen/ndlsym hook can still be installed correctly.
     * Report the linker-hook state rather than the optional map-fallback state. */
    return g_droidbridge_runtime_hooks_installed ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeInstallOpenJdkRuntimeHooks(
        JNIEnv* env, jclass clazz) {
    (void) clazz;
    return droidbridge_install_openjdk_runtime_hooks_for_env(env)
            ? JNI_TRUE : JNI_FALSE;
}
