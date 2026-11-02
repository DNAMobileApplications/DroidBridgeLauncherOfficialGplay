/*
 * DroidBridge SDL3 cross-VM ANativeWindow bridge.
 *
 * DroidBridge runs Android UI code in ART and Minecraft in an embedded OpenJDK
 * VM. A jobject Surface cannot cross VM boundaries. The ART side converts the
 * real Surface into ANativeWindow and publishes the pointer process-wide. The
 * SDL3 PLT entry for ANativeWindow_fromSurface is then replaced with a wrapper
 * that returns an acquired reference to that real window.
 *
 * Copyright (c) 2026 DNA Mobile Applications.
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#define _GNU_SOURCE 1

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <ctype.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <inttypes.h>
#include <link.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define DB_SDL3_TAG "DroidBridgeSDL3NativeWindow"
#define DB_SDL3_PTR_ENV "DROIDBRIDGE_SDL3_ANATIVEWINDOW_PTR"
#define DB_SDL3_MAX_HANDLES 8

/* SDL3 SDL_WindowFlags used by the 26.3 direct-Freedreno canonical owner. */
#define DB_SDL_WINDOW_OPENGL ((uint64_t)0x0000000000000002ULL)
#define DB_SDL_WINDOW_HIDDEN ((uint64_t)0x0000000000000008ULL)

/* SDL3 window events used to synchronize RenderPearl's final logical window
 * with the already-established scaled Android drawable. */
#define DB_SDL_EVENT_WINDOW_RESIZED 0x206u
#define DB_SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED 0x207u

/* SDL3 SDL_GLAttr values from SDL3/SDL_video.h. */
#define DB_SDL_GL_CONTEXT_MAJOR_VERSION 17
#define DB_SDL_GL_CONTEXT_MINOR_VERSION 18
#define DB_SDL_GL_CONTEXT_FLAGS 19
#define DB_SDL_GL_CONTEXT_PROFILE_MASK 20
#define DB_SDL_GL_FRAMEBUFFER_SRGB_CAPABLE 22
#define DB_SDL_GL_CONTEXT_PROFILE_CORE 0x0001
#define DB_SDL_GL_CONTEXT_PROFILE_COMPATIBILITY 0x0002
#define DB_SDL_GL_CONTEXT_PROFILE_ES 0x0004
#define DB_SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG 0x0002

/* OpenGL capability tokens used by LWJGL's GL.createCapabilities(). */
#define DB_GL_RENDERER 0x1F01u
#define DB_GL_VERSION 0x1F02u
#define DB_GL_EXTENSIONS 0x1F03u
#define DB_GL_MAJOR_VERSION 0x821Bu
#define DB_GL_MINOR_VERSION 0x821Cu
#define DB_GL_FRAMEBUFFER_SRGB 0x8DB9u
#define DB_GL_MAX_DRAW_BUFFERS 0x8824u

/* EGL tokens used by the narrow MobileGL/SDL compatibility boundary. */
#define DB_EGL_NONE 0x3038
#define DB_EGL_EXTENSIONS 0x3055
#define DB_EGL_GL_COLORSPACE_KHR 0x309D
#define DB_EGL_GL_COLORSPACE_SRGB_KHR 0x3089

/* android_dataspace_t::ADATASPACE_SRGB. Resolved dynamically so the bridge
 * remains buildable with older NDK headers while running on API 28+. */
#define DB_ANDROID_DATASPACE_SRGB ((int32_t)0x08810000)

#define DB_BLOCKED_BUFFER_STORAGE_ARB "GL_ARB_buffer_storage"
#define DB_BLOCKED_BUFFER_STORAGE_EXT "GL_EXT_buffer_storage"
#define DB_DISABLED_EXTENSION_NAME "GL_DROIDBRIDGE_buffer_storage_disabled"

static pthread_mutex_t g_surface_mutex = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *g_owned_window = NULL;
static ANativeWindow *(*g_real_from_surface)(JNIEnv *, jobject) = NULL;
static volatile int g_bridge_enabled = 0;
static volatile int g_hooked_relocations = 0;

/*
 * SDL is loaded through LWJGL's DynamicLinkLoader in the embedded JVM. Keep
 * the handles here so ndlsym can return narrow wrappers for SDL_GL_* without
 * replacing any unrelated SDL or libc symbols.
 */
typedef struct SDL_Window SDL_Window;
typedef bool (*db_sdl_gl_set_attribute_fn)(int, int);
typedef bool (*db_sdl_gl_get_attribute_fn)(int, int *);
typedef bool (*db_sdl_gl_set_swap_interval_fn)(int);
typedef void *(*db_sdl_gl_get_proc_address_fn)(const char *);
typedef void *(*db_sdl_gl_create_context_fn)(SDL_Window *);
typedef bool (*db_sdl_gl_make_current_fn)(SDL_Window *, void *);
typedef SDL_Window *(*db_sdl_gl_get_current_window_fn)(void);
typedef void *(*db_sdl_gl_get_current_context_fn)(void);
typedef bool (*db_sdl_gl_swap_window_fn)(SDL_Window *);
typedef bool (*db_sdl_gl_destroy_context_fn)(void *);

/*
 * 26.3+ direct Freedreno can keep SDL3 as the window/input API while routing
 * the actual Mesa EGL owner through the NativeGLFW backend that is already
 * stable on <=26.2. These exports are implemented by egl_bridge.c in this
 * same libdroidbridge_runtime.so.
 */
extern void *droidbridgeCreateContext(void *contextSrc);
extern void droidbridgeMakeCurrent(void *window);
extern void droidbridgeSwapBuffers(void);
extern void droidbridgeSwapInterval(int interval);
typedef SDL_Window *(*db_sdl_create_window_fn)(const char *, int, int, uint64_t);
typedef SDL_Window *(*db_sdl_create_window_with_properties_fn)(uint32_t);
typedef bool (*db_sdl_set_window_size_fn)(SDL_Window *, int, int);
typedef bool (*db_sdl_set_window_title_fn)(SDL_Window *, const char *);
typedef bool (*db_sdl_window_visibility_fn)(SDL_Window *);
typedef void (*db_sdl_destroy_window_fn)(SDL_Window *);
typedef void (*db_sdl_quit_fn)(void);
typedef void (*db_sdl_set_main_ready_fn)(void);
typedef const char *(*db_sdl_get_error_fn)(void);
typedef uint32_t (*db_sdl_get_window_id_fn)(SDL_Window *);
typedef bool (*db_sdl_push_event_fn)(void *);

typedef struct {
    uint32_t type;
    uint32_t reserved;
    uint64_t timestamp;
    uint32_t window_id;
    int32_t data1;
    int32_t data2;
} db_sdl_window_event_t;

typedef union {
    uint32_t type;
    db_sdl_window_event_t window;
    uint8_t padding[128];
} db_sdl_event_t;

/* Implemented by ctxbridges/swap_interval_no_egl.c in the same runtime. */
void setNativeWindowSwapInterval(struct ANativeWindow *nativeWindow,
                                 int swapInterval);
/* Implemented by lwjgl_dlopen_hook.c in the same droidbridge_runtime image. */
void *droidbridge_resolve_opengl_proxy_symbol(const char *name);
typedef void *(*db_dlsym_fn)(void *, const char *);
typedef void *(*db_egl_get_proc_address_fn)(const char *);
typedef const char *(*db_egl_query_string_fn)(void *, int);
typedef void *(*db_egl_create_window_surface_fn)(void *, void *, void *, const int *);
typedef void *(*db_egl_create_platform_window_surface_fn)(void *, void *, void *, const intptr_t *);
typedef const unsigned char *(*db_gl_get_string_fn)(unsigned int);
typedef const unsigned char *(*db_gl_get_string_i_fn)(unsigned int, unsigned int);
typedef void (*db_gl_get_integerv_fn)(unsigned int, int *);
typedef void (*db_gl_shader_source_fn)(unsigned int, int, const char *const *, const int *);
typedef unsigned int (*db_gl_create_shader_fn)(unsigned int);
typedef void (*db_gl_compile_shader_fn)(unsigned int);
typedef unsigned int (*db_gl_create_program_fn)(void);
typedef unsigned int (*db_gl_get_error_fn)(void);
typedef void (*db_gl_enable_fn)(unsigned int);
typedef void (*db_gl_disable_fn)(unsigned int);
typedef unsigned char (*db_gl_is_enabled_fn)(unsigned int);

static pthread_mutex_t g_sdl_gl_mutex = PTHREAD_MUTEX_INITIALIZER;
static void *g_sdl3_handles[DB_SDL3_MAX_HANDLES];
static int g_sdl3_handle_count = 0;
static void *g_active_sdl3_handle = NULL;
static db_sdl_gl_set_attribute_fn g_real_sdl_gl_set_attribute = NULL;
static db_sdl_gl_get_attribute_fn g_real_sdl_gl_get_attribute = NULL;
static db_sdl_gl_set_swap_interval_fn g_real_sdl_gl_set_swap_interval = NULL;
static db_sdl_gl_get_proc_address_fn g_real_sdl_gl_get_proc_address = NULL;
static db_sdl_gl_create_context_fn g_real_sdl_gl_create_context = NULL;
static db_sdl_gl_make_current_fn g_real_sdl_gl_make_current = NULL;
static db_sdl_gl_get_current_window_fn g_real_sdl_gl_get_current_window = NULL;
static db_sdl_gl_get_current_context_fn g_real_sdl_gl_get_current_context = NULL;
static db_sdl_gl_swap_window_fn g_real_sdl_gl_swap_window = NULL;
static db_sdl_gl_destroy_context_fn g_real_sdl_gl_destroy_context = NULL;
static db_sdl_create_window_fn g_real_sdl_create_window = NULL;
static db_sdl_create_window_with_properties_fn g_real_sdl_create_window_with_properties = NULL;
static db_sdl_set_window_size_fn g_real_sdl_set_window_size = NULL;
static db_sdl_set_window_title_fn g_real_sdl_set_window_title = NULL;
static db_sdl_window_visibility_fn g_real_sdl_show_window = NULL;
static db_sdl_destroy_window_fn g_real_sdl_destroy_window = NULL;
static db_sdl_quit_fn g_real_sdl_quit = NULL;
static db_sdl_get_window_id_fn g_real_sdl_get_window_id = NULL;
static db_sdl_push_event_fn g_real_sdl_push_event = NULL;
static pthread_mutex_t g_single_window_mutex = PTHREAD_MUTEX_INITIALIZER;
static SDL_Window *g_single_window = NULL;
static unsigned int g_single_window_refs = 0;
static unsigned int g_single_window_create_count = 0;
static int g_logged_single_window_reuse = 0;
static int g_startup_scaled_window_events_sent = 0;
/* 26.3 only: every logical window shares one SDL_Window. SDL_GL_CreateContext
 * must have the same ownership model or its temporary OpenGL probes create
 * independent EGL contexts against the one physical Android drawable. */
static pthread_mutex_t g_canonical_gl_mutex = PTHREAD_MUTEX_INITIALIZER;
static SDL_Window *g_canonical_gl_window = NULL;
static void *g_canonical_gl_context = NULL;
static unsigned int g_canonical_gl_refs = 0;
static db_sdl_get_error_fn g_real_sdl_get_error = NULL;
static db_dlsym_fn g_real_dlsym = NULL;
static void *g_sdl3_egl_provider_handle = NULL;
static void *g_sdl3_gl_wrapper_handle = NULL;
static db_egl_query_string_fn g_real_mobilegl_egl_query_string = NULL;
static db_egl_create_window_surface_fn g_real_mobilegl_egl_create_window_surface = NULL;
static db_egl_create_platform_window_surface_fn g_real_mobilegl_egl_create_platform_window_surface = NULL;
static pthread_mutex_t g_mobilegl_egl_mutex = PTHREAD_MUTEX_INITIALIZER;
static char *g_mobilegl_egl_extensions = NULL;
static int g_logged_mobilegl_egl_colorspace_advertise = 0;
static int g_logged_mobilegl_egl_colorspace_strip = 0;
static int g_requested_gl_major = -1;
static int g_requested_gl_minor = -1;
static int g_requested_gl_flags = -1;
static int g_requested_gl_profile = -1;
static int g_requested_framebuffer_srgb = -1;
static int g_logged_mobilegl_srgb_attribute = 0;
static int g_logged_gl_attribute_translation = 0;
static int g_logged_gl_proc_translation = 0;
static int g_logged_egl_symbol_translation = 0;
static db_gl_get_string_fn g_real_gl_get_string = NULL;
static db_gl_get_string_i_fn g_real_gl_get_string_i = NULL;
static db_gl_get_integerv_fn g_real_gl_get_integerv = NULL;
static pthread_mutex_t g_gl_capability_mutex = PTHREAD_MUTEX_INITIALIZER;
static char g_filtered_gl_version[256];
static char *g_filtered_gl_extensions = NULL;
static int g_filtered_gl_extensions_ready = 0;
static int g_logged_buffer_storage_filter = 0;
static int g_logged_gl_version_filter = 0;
static int g_logged_mobileglues_shader_normalization = 0;
static int g_logged_wrapped_sdl_context_cache = 0;
static int g_logged_wrapped_sdl_context_restore = 0;
static int g_logged_wrapped_sdl_transient_clear = 0;
static int g_logged_wrapped_sdl_first_swap = 0;
static int g_logged_wrapped_sdl_swap_failure = 0;
static __thread SDL_Window *g_wrapped_sdl_window = NULL;
static __thread void *g_wrapped_sdl_context = NULL;
static __thread int g_wrapped_sdl_allow_clear = 0;
static db_gl_shader_source_fn g_real_mobileglues_shader_source = NULL;
static db_gl_create_shader_fn g_real_wrapped_gl_create_shader = NULL;
static db_gl_shader_source_fn g_real_wrapped_gl_shader_source = NULL;
static db_gl_compile_shader_fn g_real_wrapped_gl_compile_shader = NULL;
static db_gl_create_program_fn g_real_wrapped_gl_create_program = NULL;
static db_gl_get_error_fn g_real_wrapped_gl_get_error = NULL;
static db_gl_enable_fn g_real_wrapped_gl_enable = NULL;
static db_gl_disable_fn g_real_wrapped_gl_disable = NULL;
static db_gl_is_enabled_fn g_real_wrapped_gl_is_enabled = NULL;
static int g_logged_mobileglues_framebuffer_srgb = 0;
static pthread_mutex_t g_openjdk_pacing_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_openjdk_swap_interval = -1;
static int g_openjdk_cached_egl_interval_applied = 0;
/* Launch-time interval requests can land on SDL's temporary bootstrap surface,
 * while live Video Settings changes arrive on Android's UI thread. Track both
 * cases and commit the interval again from the real OpenJDK render/present
 * thread after its final EGL context and ANativeWindow are current. */
static int g_openjdk_interval_reapplied_on_present = 0;
static int g_openjdk_swap_interval_pending_render_reapply = 0;
static int64_t g_openjdk_next_present_ns = 0;
static int g_logged_openjdk_pacer = 0;
/*
 * Some Android KGSL EGL stacks accept eglSwapInterval(1) but return from
 * SDL_GL_SwapWindow immediately. Detect that nonblocking behavior from the
 * live present cadence and enable the existing software guard only when the
 * driver is actually running above the requested refresh rate.
 */
static int g_openjdk_driver_interval_nonblocking = 0;
static int g_openjdk_fast_swap_streak = 0;
static int64_t g_openjdk_last_present_entry_ns = 0;
static int64_t g_openjdk_last_swap_duration_ns = 0;
static int g_logged_openjdk_adaptive_pacer = 0;
static int g_logged_wrapped_gl_create_shader = 0;
static int g_logged_wrapped_gl_create_program = 0;
static int g_logged_v12_active = 0;

/*
 * Krypton translates desktop GLSL to GLES before the source reaches the
 * device driver. Some Mali compilers reject dynamic indexing of fragment
 * output arrays even when the translated shader is otherwise valid. Keep
 * this interception completely outside Krypton's public API: only the final
 * GLES glShaderSource path is patched, and non-Mali devices pass through
 * byte-for-byte.
 */
static pthread_mutex_t g_krypton_mali_shader_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_gl_shader_source_fn g_real_krypton_driver_shader_source = NULL;
static db_egl_get_proc_address_fn g_real_krypton_driver_egl_get_proc_address = NULL;
static volatile int g_krypton_mali_shader_hook_installed = 0;
static volatile int g_krypton_mali_gpu_state = 0; /* 0 unknown, 1 Mali, -1 other */
static volatile unsigned int g_krypton_mali_shader_rewrite_serial = 0;

/*
 * SDL is loaded by Minecraft's embedded OpenJDK linker namespace. dlsym() from
 * ART is therefore not guaranteed to see it, even though both VMs are in the
 * same process. Resolve the public SDL mouse API directly from the loaded ELF
 * image so DroidBridge can ask SDL which coordinate size and cursor position it
 * is actually using instead of guessing from Android's backing-buffer size.
 */
typedef uint32_t (*db_sdl_get_mouse_state_fn)(float *, float *);
typedef SDL_Window *(*db_sdl_get_mouse_focus_fn)(void);
typedef SDL_Window *(*db_sdl_get_keyboard_focus_fn)(void);
typedef bool (*db_sdl_get_window_size_fn)(SDL_Window *, int *, int *);
typedef float (*db_sdl_get_window_float_fn)(SDL_Window *);
typedef void (*db_sdl_warp_mouse_in_window_fn)(SDL_Window *, float, float);
typedef bool (*db_sdl_set_hint_fn)(const char *, const char *);
typedef bool (*db_sdl_set_hint_with_priority_fn)(const char *, const char *, int);
typedef const char *(*db_sdl_get_hint_fn)(const char *);

static pthread_mutex_t g_sdl_api_mutex = PTHREAD_MUTEX_INITIALIZER;
static db_sdl_get_mouse_state_fn g_sdl_get_mouse_state = NULL;
static db_sdl_get_mouse_focus_fn g_sdl_get_mouse_focus = NULL;
static db_sdl_get_keyboard_focus_fn g_sdl_get_keyboard_focus = NULL;
static db_sdl_get_window_size_fn g_sdl_get_window_size = NULL;
static db_sdl_get_window_size_fn g_sdl_get_window_size_in_pixels = NULL;
static db_sdl_get_window_float_fn g_sdl_get_window_display_scale = NULL;
static db_sdl_get_window_float_fn g_sdl_get_window_pixel_density = NULL;
static db_sdl_warp_mouse_in_window_fn g_sdl_warp_mouse_in_window = NULL;
static db_sdl_set_hint_fn g_sdl_set_hint = NULL;
static db_sdl_set_hint_with_priority_fn g_sdl_set_hint_with_priority = NULL;
static db_sdl_get_hint_fn g_sdl_get_hint = NULL;
static volatile int g_sdl_warp_hint_set = 0;

static void db_log(const char *format, ...) {
    char buffer[768];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_INFO, DB_SDL3_TAG, "%s", buffer);
    printf("DroidBridgeSDL3NativeWindow: %s\n", buffer);
    fflush(stdout);
}


static bool db_sdl3_wrapped_opengl_enabled(void);
static void db_install_krypton_mali_shader_hook(void);

typedef int (*db_anativewindow_set_dataspace_fn)(ANativeWindow *, int32_t);
typedef int32_t (*db_anativewindow_get_dataspace_fn)(ANativeWindow *);
static db_anativewindow_set_dataspace_fn g_set_window_dataspace = NULL;
static db_anativewindow_get_dataspace_fn g_get_window_dataspace = NULL;
static int g_window_dataspace_resolve_attempted = 0;

static void db_apply_srgb_window_dataspace(ANativeWindow *window,
                                            const char *reason) {
    if (window == NULL || !db_sdl3_wrapped_opengl_enabled()) return;

    if (!g_window_dataspace_resolve_attempted) {
        g_window_dataspace_resolve_attempted = 1;
        void *android = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (android != NULL) {
            g_set_window_dataspace = (db_anativewindow_set_dataspace_fn)
                    dlsym(android, "ANativeWindow_setBuffersDataSpace");
            g_get_window_dataspace = (db_anativewindow_get_dataspace_fn)
                    dlsym(android, "ANativeWindow_getBuffersDataSpace");
        }
    }

    if (g_set_window_dataspace == NULL) {
        db_log("SDL3 sRGB window dataspace API unavailable reason=%s",
               reason != NULL ? reason : "unknown");
        return;
    }

    int32_t before = g_get_window_dataspace != NULL
            ? g_get_window_dataspace(window) : 0;
    int result = g_set_window_dataspace(window, DB_ANDROID_DATASPACE_SRGB);
    int32_t after = g_get_window_dataspace != NULL
            ? g_get_window_dataspace(window) : 0;
    db_log("SDL3 wrapped OpenGL window dataspace set to sRGB result=%d before=0x%x after=0x%x reason=%s",
           result,
           (unsigned int)before,
           (unsigned int)after,
           reason != NULL ? reason : "unknown");
}

static bool db_truthy_env(const char *name) {
    const char *value = getenv(name);
    if (value == NULL || value[0] == '\0') return false;
    return strcmp(value, "0") != 0
            && strcasecmp(value, "false") != 0
            && strcasecmp(value, "no") != 0
            && strcasecmp(value, "off") != 0;
}

static bool db_sdl3_wrapped_opengl_enabled(void) {
    return db_truthy_env("DROIDBRIDGE_SDL3_WRAPPED_OPENGL")
            || db_truthy_env("DROIDBRIDGE_SDL3_MOBILEGLUES_OPENGL");
}

static bool db_direct_freedreno_mesa_enabled(void) {
    if (db_truthy_env("DROIDBRIDGE_NATIVE_GLFW_KGSL")) return true;

    const char *mode = getenv("DROIDBRIDGE_MESA_MODE");
    if (mode != NULL && strcasecmp(mode, "freedreno_kgsl") == 0) return true;

    const char *renderer_mode = getenv("DROIDBRIDGE_RENDERER_MESA_MODE");
    return renderer_mode != NULL
            && strcasecmp(renderer_mode, "freedreno_kgsl") == 0;
}

static bool db_direct_mesa_single_loader_enabled(void) {
    return db_direct_freedreno_mesa_enabled()
            && db_truthy_env("DROIDBRIDGE_SDL3_MESA_SINGLE_LOADER");
}

static bool db_sdl3_nativeglfw_gl_owner_enabled(void);

static bool db_is_krypton_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    return kind != NULL && strcasecmp(kind, "krypton") == 0;
}

static bool db_is_mobileglues_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcasecmp(kind, "mobileglues") == 0) return true;
    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    return path != NULL && strstr(path, "mobileglues") != NULL;
}

static bool db_is_mobilegl_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcasecmp(kind, "mobilegl") == 0) return true;
    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    return path != NULL && strstr(path, "libMobileGL.so") != NULL;
}

static bool db_is_ltw_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcasecmp(kind, "ltw") == 0) return true;
    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    if (path != NULL && strstr(path, "ltw") != NULL) return true;
    const char *renderer = getenv("DROIDBRIDGE_RENDERER");
    return renderer != NULL && strstr(renderer, "ltw") != NULL;
}

static bool db_is_vulkan_zink_renderer(void) {
    const char *renderer = getenv("DROIDBRIDGE_RENDERER");
    if (renderer != NULL && strstr(renderer, "vulkan_zink") != NULL) return true;

    const char *driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    if (driver != NULL && strcasecmp(driver, "zink") == 0) return true;

    const char *mode = getenv("DROIDBRIDGE_MESA_MODE");
    return mode != NULL && strstr(mode, "zink") != NULL;
}

static bool db_android10_ltw_proc_consistency_enabled(void) {
    return db_is_ltw_renderer()
            && db_truthy_env("DROIDBRIDGE_SDL3_ANDROID10_SKIP_INIT_TOUCH");
}

static bool db_wrapped_context_guard_enabled(void) {
    /* Snapshot 4+ direct Freedreno is also SDL3-owned. SDL temporarily clears
     * its current-context query while rebuilding RenderPearl resources, which
     * previously let glCreateFramebuffers/glCreateBuffers run with no current
     * context. Treat direct Mesa like the existing wrapped OpenGL routes so the
     * verified SDL window/context pair remains current across that transition. */
    return db_direct_freedreno_mesa_enabled()
            || db_is_krypton_renderer()
            || db_is_mobileglues_renderer();
}

static const char *db_wrapped_renderer_name(void) {
    if (db_direct_freedreno_mesa_enabled()) return "Freedreno Mesa";
    return db_is_mobileglues_renderer() ? "MobileGlues" : "Krypton";
}

static const char *db_basename(const char *path) {
    if (path == NULL) return "";
    const char *slash = strrchr(path, '/');
    return slash != NULL ? slash + 1 : path;
}

static bool db_is_sdl3_library_name(const char *filename) {
    const char *base = db_basename(filename);
    return strcmp(base, "libSDL3.so") == 0
            || strcmp(base, "SDL3") == 0
            || strstr(base, "libSDL3.so.") == base;
}


static void db_resolve_real_dlsym(void);

/*
 * Cleanroom/lwjgly SDL 3.2.22 cross-VM JNI quarantine v1.
 *
 * Cleanroom owns SDL's lifecycle/window/context and must remain untouched by the
 * Minecraft 26.x SDL transformer.  The Android SDL video backend still contains
 * a handful of outbound Java callbacks, though.  When those callbacks originate
 * on Cleanroom's embedded OpenJDK render thread, Android_JNI_GetEnv can return
 * NULL because that thread belongs to HotSpot rather than ART.  The packaged
 * SDL 3.2.22 then dereferences that NULL JNIEnv (the observed native crash at
 * libSDL3.so+0x98118).
 *
 * DroidBridge already owns the corresponding Android responsibilities: touch is
 * fed through its input bridge, cursor shape/pointer capture are launcher-owned,
 * and Activity/window policy is maintained by GameActivity.  Therefore only the
 * Java-facing helper calls below are made harmless.  SDL_Init itself, SDL's
 * physical window, GL context, event state and swap path are not replaced.
 *
 * Every patch is guarded by an exact arm64 instruction fingerprint from the
 * bundled SDL 3.2.22 image.  A different SDL build is rejected instead of being
 * modified speculatively.
 */
#define DB_CLEANROOM_SDL322_TOUCH_CALL_OFFSET ((uintptr_t) 0x196974u)
#define DB_CLEANROOM_SDL322_TOUCH_PREV ((uint32_t) 0xbd005a80u)
#define DB_CLEANROOM_SDL322_TOUCH_CALL ((uint32_t) 0x97fffdefu)
#define DB_CLEANROOM_SDL322_TOUCH_NEXT ((uint32_t) 0x97fffc4au)

#define DB_CLEANROOM_SDL322_CREATE_CURSOR_OFFSET ((uintptr_t) 0x195b4cu)
#define DB_CLEANROOM_SDL322_SHOW_CURSOR_OFFSET ((uintptr_t) 0x195c58u)
#define DB_CLEANROOM_SDL322_RELATIVE_MOUSE_OFFSET ((uintptr_t) 0x195d4cu)
#define DB_CLEANROOM_SDL322_SET_ACTIVITY_TITLE_OFFSET ((uintptr_t) 0x9765cu)
#define DB_CLEANROOM_SDL322_SET_WINDOW_STYLE_OFFSET ((uintptr_t) 0x976c8u)
#define DB_CLEANROOM_SDL322_SET_ORIENTATION_OFFSET ((uintptr_t) 0x97704u)
#define DB_CLEANROOM_SDL322_MINIMIZE_WINDOW_OFFSET ((uintptr_t) 0x977c0u)
#define DB_CLEANROOM_SDL322_SHOULD_MINIMIZE_OFFSET ((uintptr_t) 0x977ecu)
#define DB_CLEANROOM_SDL322_SUSPEND_SCREENSAVER_OFFSET ((uintptr_t) 0x982e4u)
#define DB_CLEANROOM_SDL322_IS_CHROMEBOOK_OFFSET ((uintptr_t) 0x989d4u)
#define DB_CLEANROOM_SDL322_IS_DEX_OFFSET ((uintptr_t) 0x98a0cu)

#define DB_CLEANROOM_A64_NOP ((uint32_t) 0xd503201fu)
#define DB_CLEANROOM_A64_RET ((uint32_t) 0xd65f03c0u)
#define DB_CLEANROOM_A64_MOV_W0_0 ((uint32_t) 0x2a1f03e0u)
#define DB_CLEANROOM_A64_MOV_W0_1 ((uint32_t) 0x52800020u)
#define DB_CLEANROOM_A64_MOV_X0_XZR ((uint32_t) 0xaa1f03e0u)

static bool db_cleanroom_patch_code_words(uint32_t *site,
                                          const uint32_t *expected,
                                          const uint32_t *replacement,
                                          size_t count,
                                          const char *label) {
#if !defined(__aarch64__)
    (void) site;
    (void) expected;
    (void) replacement;
    (void) count;
    (void) label;
    return false;
#else
    if (site == NULL || expected == NULL || replacement == NULL || count == 0) {
        return false;
    }

    bool already = true;
    for (size_t i = 0; i < count; ++i) {
        uint32_t current = __atomic_load_n(site + i, __ATOMIC_ACQUIRE);
        if (current != replacement[i]) {
            already = false;
            if (current != expected[i]) {
                db_log("Cleanroom SDL 3.2.22 JNI quarantine v1 fingerprint mismatch label=%s site=%p index=%zu expected=0x%08x current=0x%08x replacement=0x%08x",
                       label != NULL ? label : "unknown",
                       (void *) site,
                       i,
                       expected[i],
                       current,
                       replacement[i]);
                return false;
            }
        }
    }
    if (already) return true;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    uintptr_t first = (uintptr_t) site;
    uintptr_t last = (uintptr_t) (site + count - 1);
    uintptr_t first_page = first & ~((uintptr_t) page_size - 1u);
    uintptr_t last_page = last & ~((uintptr_t) page_size - 1u);
    size_t span = (size_t) ((last_page - first_page) + (uintptr_t) page_size);

    if (mprotect((void *) first_page, span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        int rwx_errno = errno;
        if (mprotect((void *) first_page, span, PROT_READ | PROT_WRITE) != 0) {
            db_log("Cleanroom SDL 3.2.22 JNI quarantine v1 mprotect failed label=%s site=%p rwxErrno=%d rwErrno=%d",
                   label != NULL ? label : "unknown",
                   (void *) site,
                   rwx_errno,
                   errno);
            return false;
        }
    }

    for (size_t i = 0; i < count; ++i) {
        __atomic_store_n(site + i, replacement[i], __ATOMIC_RELEASE);
    }
    __builtin___clear_cache((char *) site, (char *) (site + count));
    (void) mprotect((void *) first_page, span, PROT_READ | PROT_EXEC);
    return true;
#endif
}

int droidbridge_sdl3_patch_cleanroom_java_callbacks(const char *filename, void *handle) {
    if (!db_truthy_env("DROIDBRIDGE_CLEANROOM_OWNS_SDL3")
            || handle == NULL
            || !db_is_sdl3_library_name(filename)) {
        return 0;
    }
#if !defined(__aarch64__)
    db_log("Cleanroom SDL 3.2.22 JNI quarantine v1 unsupported architecture");
    return 0;
#else
    db_resolve_real_dlsym();
    if (g_real_dlsym == NULL) return 0;

    void *sdl_init = g_real_dlsym(handle, "SDL_Init");
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (sdl_init == NULL
            || dladdr(sdl_init, &info) == 0
            || info.dli_fbase == NULL
            || info.dli_fname == NULL
            || !db_is_sdl3_library_name(info.dli_fname)) {
        db_log("Cleanroom SDL 3.2.22 JNI quarantine v1 rejected image=%s handle=%p SDL_Init=%p base=%p",
               filename != NULL ? filename : "<null>",
               handle,
               sdl_init,
               info.dli_fbase);
        return 0;
    }

    uintptr_t base = (uintptr_t) info.dli_fbase;
    uint32_t *touch = (uint32_t *) (base + DB_CLEANROOM_SDL322_TOUCH_CALL_OFFSET);
    uint32_t touch_prev = __atomic_load_n(touch - 1, __ATOMIC_ACQUIRE);
    uint32_t touch_cur = __atomic_load_n(touch, __ATOMIC_ACQUIRE);
    uint32_t touch_next = __atomic_load_n(touch + 1, __ATOMIC_ACQUIRE);
    bool touch_fingerprint = touch_prev == DB_CLEANROOM_SDL322_TOUCH_PREV
            && (touch_cur == DB_CLEANROOM_SDL322_TOUCH_CALL
                || touch_cur == DB_CLEANROOM_A64_NOP)
            && touch_next == DB_CLEANROOM_SDL322_TOUCH_NEXT;
    bool touch_ok = false;
    if (touch_fingerprint) {
        const uint32_t expected[] = { DB_CLEANROOM_SDL322_TOUCH_CALL };
        const uint32_t replacement[] = { DB_CLEANROOM_A64_NOP };
        touch_ok = touch_cur == DB_CLEANROOM_A64_NOP
                || db_cleanroom_patch_code_words(touch, expected, replacement, 1, "initTouch");
    } else {
        db_log("Cleanroom SDL 3.2.22 JNI quarantine v1 touch fingerprint mismatch image=%s base=%p prev=0x%08x current=0x%08x next=0x%08x",
               info.dli_fname,
               info.dli_fbase,
               touch_prev,
               touch_cur,
               touch_next);
    }

    const uint32_t create_cursor_expected[] = { 0xa9bd7bfdu, 0xf9000bf5u };
    const uint32_t create_cursor_replacement[] = {
            DB_CLEANROOM_A64_MOV_X0_XZR, DB_CLEANROOM_A64_RET
    };
    bool create_cursor_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_CREATE_CURSOR_OFFSET),
            create_cursor_expected, create_cursor_replacement, 2, "createCustomCursor");

    const uint32_t show_cursor_expected[] = { 0xa9be7bfdu, 0xa9014ff4u };
    const uint32_t true_replacement[] = { DB_CLEANROOM_A64_MOV_W0_1, DB_CLEANROOM_A64_RET };
    bool show_cursor_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_SHOW_CURSOR_OFFSET),
            show_cursor_expected, true_replacement, 2, "showCursor");

    const uint32_t relative_mouse_expected[] = { 0xa9be7bfdu, 0xf9000bf3u };
    bool relative_mouse_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_RELATIVE_MOUSE_OFFSET),
            relative_mouse_expected, true_replacement, 2, "relativeMouse");

    const uint32_t title_expected[] = { 0xa9be7bfdu, 0xa9014ff4u };
    bool title_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_SET_ACTIVITY_TITLE_OFFSET),
            title_expected, true_replacement, 2, "setActivityTitle");

    const uint32_t void_replacement[] = { DB_CLEANROOM_A64_RET, DB_CLEANROOM_A64_NOP };
    const uint32_t style_expected[] = { 0xa9be7bfdu, 0xf9000bf3u };
    bool style_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_SET_WINDOW_STYLE_OFFSET),
            style_expected, void_replacement, 2, "setWindowStyle");

    const uint32_t orientation_expected[] = { 0xa9bc7bfdu, 0xf9000bf7u };
    bool orientation_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_SET_ORIENTATION_OFFSET),
            orientation_expected, void_replacement, 2, "setOrientation");

    const uint32_t minimize_expected[] = { 0xa9bf7bfdu, 0x910003fdu };
    bool minimize_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_MINIMIZE_WINDOW_OFFSET),
            minimize_expected, void_replacement, 2, "minimizeWindow");

    const uint32_t false_replacement[] = { DB_CLEANROOM_A64_MOV_W0_0, DB_CLEANROOM_A64_RET };
    bool should_minimize_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_SHOULD_MINIMIZE_OFFSET),
            minimize_expected, false_replacement, 2, "shouldMinimizeOnFocusLoss");

    bool screensaver_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_SUSPEND_SCREENSAVER_OFFSET),
            style_expected, true_replacement, 2, "suspendScreenSaver");

    bool chromebook_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_IS_CHROMEBOOK_OFFSET),
            minimize_expected, false_replacement, 2, "isChromebook");
    bool dex_ok = db_cleanroom_patch_code_words(
            (uint32_t *) (base + DB_CLEANROOM_SDL322_IS_DEX_OFFSET),
            minimize_expected, false_replacement, 2, "isDeXMode");

    bool ok = touch_ok
            && create_cursor_ok
            && show_cursor_ok
            && relative_mouse_ok
            && title_ok
            && style_ok
            && orientation_ok
            && minimize_ok
            && should_minimize_ok
            && screensaver_ok
            && chromebook_ok
            && dex_ok;
    db_log("Cleanroom SDL 3.2.22 Java-JNI quarantine v1 %s image=%s base=%p SDL_Init=%p touch=%d cursor=%d/%d relative=%d title=%d style=%d orientation=%d minimize=%d focusLoss=%d screensaver=%d chromebook=%d dex=%d windowOwnership=unchanged",
           ok ? "applied" : "incomplete",
           info.dli_fname,
           info.dli_fbase,
           sdl_init,
           touch_ok ? 1 : 0,
           create_cursor_ok ? 1 : 0,
           show_cursor_ok ? 1 : 0,
           relative_mouse_ok ? 1 : 0,
           title_ok ? 1 : 0,
           style_ok ? 1 : 0,
           orientation_ok ? 1 : 0,
           minimize_ok ? 1 : 0,
           should_minimize_ok ? 1 : 0,
           screensaver_ok ? 1 : 0,
           chromebook_ok ? 1 : 0,
           dex_ok ? 1 : 0);
    return ok ? 1 : 0;
#endif
}

static void db_resolve_real_dlsym(void) {
    if (g_real_dlsym != NULL) return;
    /* This library does not export dlsym, so the direct reference resolves to
     * Android's linker implementation before any SDL relocation is patched. */
    g_real_dlsym = (db_dlsym_fn) dlsym;
}

static void *db_load_sdl3_egl_provider(void) {
    /*
     * Minecraft 26.3 direct Freedreno single-loader rule:
     * libEGL_mesa.so is acquired by LWJGL/OpenJDK and then used by SDL3.
     * This bridge is an ART/native-side image; dlopen() here can create a
     * second Mesa/Gallium instance in a different Android linker namespace.
     * Refuse every bridge-side reopen, not only the SDL post-load callback.
     */
    if (db_direct_mesa_single_loader_enabled()) return NULL;
    if (g_sdl3_egl_provider_handle != NULL) return g_sdl3_egl_provider_handle;
    const char *path = getenv("SDL_EGL_LIBRARY");
    if (path == NULL || path[0] == '\0') return NULL;
    g_sdl3_egl_provider_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (g_sdl3_egl_provider_handle == NULL) {
        const char *error = dlerror();
        db_log("early EGL provider load failed path=%s error=%s",
               path, error != NULL ? error : "unknown");
    } else {
        db_log("early EGL provider loaded path=%s handle=%p",
               path, g_sdl3_egl_provider_handle);
    }
    return g_sdl3_egl_provider_handle;
}


/*
 * Snapshot 4+ direct Freedreno uses SDL3 to own the real window/context, while
 * the older NativeGLFW bridge only creates a temporary preload context for
 * LWJGL symbol resolution. Applying eglSwapInterval to that temporary context
 * cannot control the game. Resolve Mesa's EGL entry points from the same
 * app-local provider and apply the interval to SDL3's currently bound display.
 * If SDL has temporarily cleared its current-context query, the caller retries
 * this on the first present after the LWJGL cached-context helper restores it.
 */
static int db_direct_mesa_apply_swap_interval(int interval,
                                               const char *reason) {
    typedef void *(*db_egl_get_current_display_fn)(void);
    typedef int (*db_egl_swap_interval_fn)(void *, int);
    typedef int (*db_egl_get_error_fn)(void);
    static void *provider = NULL;
    static db_egl_get_current_display_fn get_current_display = NULL;
    static db_egl_swap_interval_fn set_swap_interval = NULL;
    static db_egl_get_error_fn get_error = NULL;
    static int resolve_attempted = 0;
    static int logged_first = 0;
    static int logged_failure = 0;

    if (!db_direct_freedreno_mesa_enabled()) return 0;
    if (db_direct_mesa_single_loader_enabled()) return 0;

    if (!resolve_attempted) {
        resolve_attempted = 1;
        const char *path = getenv("DROIDBRIDGE_NATIVE_GLFW_EGL");
        if (path == NULL || path[0] == '\0') path = getenv("DROIDBRIDGE_MESA_EGL");
        if (path == NULL || path[0] == '\0') path = getenv("SDL_EGL_LIBRARY");

        if (path != NULL && path[0] != '\0') {
            provider = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        }
        if (provider != NULL) {
            get_current_display = (db_egl_get_current_display_fn)
                    dlsym(provider, "eglGetCurrentDisplay");
            set_swap_interval = (db_egl_swap_interval_fn)
                    dlsym(provider, "eglSwapInterval");
            get_error = (db_egl_get_error_fn)dlsym(provider, "eglGetError");
        }

        db_log("Freedreno Mesa SDL3 VSync provider path=%s handle=%p getDisplay=%p swapInterval=%p",
               path != NULL ? path : "<none>",
               provider,
               (void *)get_current_display,
               (void *)set_swap_interval);
    }

    if (get_current_display == NULL || set_swap_interval == NULL) return 0;

    void *display = get_current_display();
    if (display == NULL) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log("Freedreno Mesa SDL3 eglSwapInterval deferred requested=%d reason=%s currentDisplay=%p",
                   interval,
                   reason != NULL ? reason : "unknown",
                   display);
        }
        return 0;
    }

    int effective = interval > 0 ? 1 : 0;
    int result = set_swap_interval(display, effective);
    int error = result != 0 ? 0x3000 : (get_error != NULL ? get_error() : 0);
    if (!logged_first || (result == 0 && !logged_failure)) {
        if (!logged_first) logged_first = 1;
        if (result == 0) logged_failure = 1;
        db_log("Freedreno Mesa SDL3 eglSwapInterval requested=%d effective=%d result=%d error=0x%x reason=%s display=%p",
               interval,
               effective,
               result != 0 ? 1 : 0,
               error,
               reason != NULL ? reason : "unknown",
               display);
    }
    return result != 0 ? 1 : 0;
}

/*
 * Cleanroom 0.7+ (lwjgly) owns the SDL3 window/context lifecycle. Its legacy
 * LWJGL2 compatibility facade still calls DroidBridge's GLFW-shaped context
 * entry points, however. Expose only the already-current SDL context/window so
 * egl_bridge.c can reuse them instead of creating a second EGLWindowSurface on
 * the same Android ANativeWindow (which Android rejects with EGL_BAD_ALLOC).
 *
 * These helpers are inert unless the caller explicitly selects the Cleanroom
 * ownership path; Minecraft 26.x continues through the existing SDL wrappers.
 */
static SDL_Window *g_cleanroom_sdl_window = NULL;
static void *g_cleanroom_sdl_context = NULL;
static int g_logged_cleanroom_context_capture = 0;
static int g_logged_cleanroom_context_restore = 0;
static int g_logged_cleanroom_swap = 0;

__attribute__((visibility("default")))
void *droidbridge_sdl3_get_current_gl_context(void) {
    db_sdl_gl_get_current_context_fn get_context = NULL;
    db_sdl_gl_get_current_window_fn get_window = NULL;

    pthread_mutex_lock(&g_sdl_gl_mutex);
    get_context = g_real_sdl_gl_get_current_context;
    get_window = g_real_sdl_gl_get_current_window;
    pthread_mutex_unlock(&g_sdl_gl_mutex);

    void *context = get_context != NULL ? get_context() : NULL;
    SDL_Window *window = get_window != NULL ? get_window() : NULL;
    if (context != NULL) g_cleanroom_sdl_context = context;
    if (window != NULL) g_cleanroom_sdl_window = window;

    if (context != NULL && window != NULL && !g_logged_cleanroom_context_capture) {
        g_logged_cleanroom_context_capture = 1;
        db_log("Cleanroom SDL context captured window=%p context=%p",
               g_cleanroom_sdl_window,
               g_cleanroom_sdl_context);
    }
    return context != NULL ? context : g_cleanroom_sdl_context;
}

__attribute__((visibility("default")))
int droidbridge_sdl3_make_existing_gl_context_current(void *context, const char *reason) {
    db_sdl_gl_make_current_fn make_current = NULL;
    db_sdl_gl_get_current_context_fn get_context = NULL;
    db_sdl_gl_get_current_window_fn get_window = NULL;

    pthread_mutex_lock(&g_sdl_gl_mutex);
    make_current = g_real_sdl_gl_make_current;
    get_context = g_real_sdl_gl_get_current_context;
    get_window = g_real_sdl_gl_get_current_window;
    pthread_mutex_unlock(&g_sdl_gl_mutex);

    void *target_context = context != NULL ? context : g_cleanroom_sdl_context;
    SDL_Window *target_window = g_cleanroom_sdl_window;
    if (get_window != NULL) {
        SDL_Window *current_window = get_window();
        if (current_window != NULL) target_window = current_window;
    }
    if (target_context == NULL && get_context != NULL) {
        target_context = get_context();
    }
    if (target_context == NULL || target_window == NULL || make_current == NULL) {
        db_log("Cleanroom SDL context restore unavailable reason=%s window=%p context=%p makeCurrent=%p",
               reason != NULL ? reason : "unknown",
               target_window,
               target_context,
               (void *)make_current);
        return 0;
    }

    if (get_context != NULL && get_context() == target_context) {
        g_cleanroom_sdl_context = target_context;
        g_cleanroom_sdl_window = target_window;
        return 1;
    }

    bool ok = make_current(target_window, target_context);
    if (ok) {
        g_cleanroom_sdl_context = target_context;
        g_cleanroom_sdl_window = target_window;
        if (!g_logged_cleanroom_context_restore) {
            g_logged_cleanroom_context_restore = 1;
            db_log("Cleanroom SDL context restored reason=%s window=%p context=%p",
                   reason != NULL ? reason : "unknown",
                   target_window,
                   target_context);
        }
    } else {
        const char *error = g_real_sdl_get_error != NULL ? g_real_sdl_get_error() : "";
        db_log("Cleanroom SDL context restore failed reason=%s window=%p context=%p error=%s",
               reason != NULL ? reason : "unknown",
               target_window,
               target_context,
               error != NULL ? error : "<null>");
    }
    return ok ? 1 : 0;
}

__attribute__((visibility("default")))
int droidbridge_sdl3_swap_existing_gl_window(void) {
    db_sdl_gl_swap_window_fn swap_window = NULL;
    db_sdl_gl_get_current_window_fn get_window = NULL;

    pthread_mutex_lock(&g_sdl_gl_mutex);
    swap_window = g_real_sdl_gl_swap_window;
    get_window = g_real_sdl_gl_get_current_window;
    pthread_mutex_unlock(&g_sdl_gl_mutex);

    SDL_Window *window = g_cleanroom_sdl_window;
    if (get_window != NULL) {
        SDL_Window *current_window = get_window();
        if (current_window != NULL) window = current_window;
    }
    if (window == NULL || swap_window == NULL) return 0;

    bool ok = swap_window(window);
    if (ok) {
        g_cleanroom_sdl_window = window;
        if (!g_logged_cleanroom_swap) {
            g_logged_cleanroom_swap = 1;
            db_log("Cleanroom SDL presentation reused window=%p", window);
        }
    } else {
        const char *error = g_real_sdl_get_error != NULL ? g_real_sdl_get_error() : "";
        db_log("Cleanroom SDL presentation failed window=%p error=%s",
               window,
               error != NULL ? error : "<null>");
    }
    return ok ? 1 : 0;
}

__attribute__((visibility("default")))
int droidbridge_sdl3_set_existing_gl_swap_interval(int interval) {
    db_sdl_gl_set_swap_interval_fn set_interval = NULL;
    pthread_mutex_lock(&g_sdl_gl_mutex);
    set_interval = g_real_sdl_gl_set_swap_interval;
    pthread_mutex_unlock(&g_sdl_gl_mutex);
    if (set_interval == NULL) return 0;
    return set_interval(interval > 0 ? 1 : 0) ? 1 : 0;
}

__attribute__((visibility("default")))
int droidbridge_sdl3_ensure_current_gl_context(const char *reason) {
    typedef int (*ensure_current_fn)(const char *);

    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        if (g_wrapped_sdl_context == NULL) return 0;
        droidbridgeMakeCurrent(NULL);
        return 1;
    }

    /* Direct Mesa does not export the wrapped-provider ensure-current helper.
     * Reassert SDL's own cached context directly so presentation and resource
     * creation use the same context that SDL_GL_CreateContext returned. */
    if (db_direct_freedreno_mesa_enabled()
            && g_wrapped_sdl_window != NULL
            && g_wrapped_sdl_context != NULL
            && g_real_sdl_gl_make_current != NULL) {
        SDL_Window *current_window = g_real_sdl_gl_get_current_window != NULL
                ? g_real_sdl_gl_get_current_window() : NULL;
        void *current_context = g_real_sdl_gl_get_current_context != NULL
                ? g_real_sdl_gl_get_current_context() : NULL;
        if (current_window == g_wrapped_sdl_window
                && current_context == g_wrapped_sdl_context) {
            return 1;
        }
        bool restored = g_real_sdl_gl_make_current(
                g_wrapped_sdl_window, g_wrapped_sdl_context);
        if (restored && !g_logged_wrapped_sdl_context_restore) {
            g_logged_wrapped_sdl_context_restore = 1;
            db_log("Freedreno Mesa SDL current context restored reason=%s window=%p context=%p",
                   reason != NULL ? reason : "unknown",
                   g_wrapped_sdl_window,
                   g_wrapped_sdl_context);
        }
        return restored ? 1 : 0;
    }

    void *provider = db_load_sdl3_egl_provider();
    if (provider == NULL) return 0;
    db_resolve_real_dlsym();
    ensure_current_fn ensure_current = g_real_dlsym != NULL
            ? (ensure_current_fn) g_real_dlsym(
                    provider, "droidbridge_sdl3_egl_ensure_current_context")
            : NULL;
    return ensure_current != NULL ? ensure_current(reason) : 0;
}


/*
 * Force a benign reassert of the canonical direct-Freedreno SDL GL binding.
 * This intentionally does NOT detach EGL and does not call eglMakeCurrent
 * directly. SDL owns the EGLSurface/native-window lifecycle, so reasserting
 * through SDL_GL_MakeCurrent keeps SDL's internal surface bookkeeping intact.
 */
__attribute__((visibility("default")))
int droidbridge_sdl3_reassert_current_gl_context(const char *reason) {
    static int logged_success = 0;
    static int logged_failure = 0;

    if (!db_direct_freedreno_mesa_enabled()) {
        return droidbridge_sdl3_ensure_current_gl_context(reason);
    }
    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        if (g_wrapped_sdl_context == NULL) return 0;
        droidbridgeMakeCurrent(NULL);
        if (!logged_success) {
            logged_success = 1;
            db_log("26.3+ Freedreno drawable owner reasserted through NativeGLFW reason=%s context=%p",
                   reason != NULL ? reason : "unknown", g_wrapped_sdl_context);
        }
        return 1;
    }

    db_sdl_gl_make_current_fn make_current = NULL;
    db_sdl_gl_get_current_context_fn get_context = NULL;
    db_sdl_gl_get_current_window_fn get_window = NULL;
    SDL_Window *target_window = NULL;
    void *target_context = NULL;

    pthread_mutex_lock(&g_sdl_gl_mutex);
    make_current = g_real_sdl_gl_make_current;
    get_context = g_real_sdl_gl_get_current_context;
    get_window = g_real_sdl_gl_get_current_window;
    target_window = g_wrapped_sdl_window;
    target_context = g_wrapped_sdl_context;
    pthread_mutex_unlock(&g_sdl_gl_mutex);

    if (make_current == NULL || target_window == NULL || target_context == NULL) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log("Freedreno Mesa SDL drawable reassert unavailable reason=%s window=%p context=%p makeCurrent=%p",
                   reason != NULL ? reason : "unknown",
                   target_window, target_context, (void *)make_current);
        }
        return 0;
    }

    /* Reassert the same SDL window/context without a preceding NULL detach. */
    bool ok = make_current(target_window, target_context);
    SDL_Window *current_window = get_window != NULL ? get_window() : target_window;
    void *current_context = get_context != NULL ? get_context() : target_context;

    if (!ok || current_window != target_window || current_context != target_context) {
        if (!logged_failure) {
            logged_failure = 1;
            const char *error = g_real_sdl_get_error != NULL ? g_real_sdl_get_error() : "";
            db_log("Freedreno Mesa SDL drawable reassert FAILED reason=%s ok=%d targetWindow=%p currentWindow=%p targetContext=%p currentContext=%p error=%s",
                   reason != NULL ? reason : "unknown",
                   ok ? 1 : 0,
                   target_window, current_window,
                   target_context, current_context,
                   error != NULL ? error : "<null>");
        }
        return 0;
    }

    if (!logged_success) {
        logged_success = 1;
        db_log("26.3 Freedreno SDL-owned drawable reassert active reason=%s window=%p context=%p",
               reason != NULL ? reason : "unknown",
               target_window, target_context);
    }
    return 1;
}

static void *db_load_sdl3_gl_wrapper(void) {
    /*
     * Direct Mesa is not a wrapped-OpenGL library.  In single-loader mode the
     * bridge must never dlopen the Mesa EGL image just to obtain GL symbols.
     * GL entry points come from eglGetProcAddress on the OpenJDK-owned image.
     */
    if (db_direct_mesa_single_loader_enabled()) return NULL;
    if (g_sdl3_gl_wrapper_handle != NULL) return g_sdl3_gl_wrapper_handle;

    /*
     * Minecraft 26.3/RenderPearl requires the OpenGL function provider and
     * SDL_GL_GetProcAddress to resolve from one LTW image. Android 10 can map
     * another copy of the same app-private library when this runtime bridge
     * calls dlopen() from a different linker namespace. Reuse the exact handle
     * that the LWJGL RenderSpec proxy published after its successful load.
     */
    if (db_is_ltw_renderer()) {
        const char *handle_text = getenv("DROIDBRIDGE_LTW_OPENJDK_HANDLE");
        if (handle_text != NULL && handle_text[0] != '\0') {
            char *end = NULL;
            unsigned long long raw = strtoull(handle_text, &end, 0);
            if (raw != 0 && end != handle_text && (end == NULL || *end == '\0')) {
                void *published = (void *)(uintptr_t)raw;
                db_resolve_real_dlsym();
                dlerror();
                void *probe = g_real_dlsym != NULL
                        ? g_real_dlsym(published, "eglGetProcAddress") : NULL;
                const char *error = dlerror();
                if (probe != NULL && error == NULL) {
                    g_sdl3_gl_wrapper_handle = published;
                    db_log("reusing OpenJDK LTW wrapper handle=%p eglGetProcAddress=%p",
                           published, probe);
                    return g_sdl3_gl_wrapper_handle;
                }
                db_log("OpenJDK LTW wrapper handle rejected handle=%p error=%s",
                       published,
                       error != NULL ? error : "eglGetProcAddress missing");
            }
        }
    }

    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    if (path == NULL || path[0] == '\0') path = getenv("SDL_OPENGL_LIBRARY");
    if (path == NULL || path[0] == '\0') return NULL;
    g_sdl3_gl_wrapper_handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (g_sdl3_gl_wrapper_handle == NULL) {
        const char *error = dlerror();
        db_log("early wrapped OpenGL load failed path=%s error=%s",
               path, error != NULL ? error : "unknown");
    } else {
        db_log("early wrapped OpenGL loaded path=%s handle=%p",
               path, g_sdl3_gl_wrapper_handle);
        if (db_is_krypton_renderer()) {
            db_install_krypton_mali_shader_hook();
        }
    }
    return g_sdl3_gl_wrapper_handle;
}

static bool db_is_known_sdl3_handle(void *handle) {
    if (handle == NULL) return false;
    for (int i = 0; i < g_sdl3_handle_count; ++i) {
        if (g_sdl3_handles[i] == handle) return true;
    }
    return false;
}

static void db_register_sdl3_handle(void *handle) {
    if (handle == NULL) return;
    pthread_mutex_lock(&g_sdl_gl_mutex);
    if (!db_is_known_sdl3_handle(handle)
            && g_sdl3_handle_count < DB_SDL3_MAX_HANDLES) {
        g_sdl3_handles[g_sdl3_handle_count++] = handle;
    }
    g_active_sdl3_handle = handle;
    pthread_mutex_unlock(&g_sdl_gl_mutex);
}

static void db_mark_sdl3_main_ready(void *handle, const char *reason) {
    if (handle == NULL) return;
    db_resolve_real_dlsym();
    if (g_real_dlsym == NULL) return;

    /*
     * Minecraft 26.3 invokes SDL_Init() directly through LWJGL instead of
     * entering through SDL_main(). On Android, SDL deliberately rejects that
     * path until SDL_SetMainReady() has been called. The ART-side SDL bootstrap
     * can initialize a different linker-namespace mapping than the embedded
     * OpenJDK VM, so marking only the launcher copy ready is not sufficient.
     *
     * Run SDL_SetMainReady() on the exact libSDL3 handle LWJGL just loaded.
     * This is idempotent in SDL3 and must happen before SDLInit$Functions can
     * call SDL_Init().
     */
    db_sdl_set_main_ready_fn set_main_ready = (db_sdl_set_main_ready_fn)
            g_real_dlsym(handle, "SDL_SetMainReady");
    if (set_main_ready != NULL) {
        set_main_ready();
        db_log("LWJGL SDL main-ready set handle=%p reason=%s",
               handle,
               reason != NULL ? reason : "unknown");
    } else {
        db_log("LWJGL SDL main-ready symbol missing handle=%p reason=%s",
               handle,
               reason != NULL ? reason : "unknown");
    }
}

static void db_bind_active_sdl3_symbols(void *handle) {
    if (handle == NULL) return;
    db_resolve_real_dlsym();
    if (g_real_dlsym == NULL) return;
    g_active_sdl3_handle = handle;
    g_real_sdl_gl_set_attribute = (db_sdl_gl_set_attribute_fn)
            g_real_dlsym(handle, "SDL_GL_SetAttribute");
    g_real_sdl_gl_get_attribute = (db_sdl_gl_get_attribute_fn)
            g_real_dlsym(handle, "SDL_GL_GetAttribute");
    g_real_sdl_gl_set_swap_interval = (db_sdl_gl_set_swap_interval_fn)
            g_real_dlsym(handle, "SDL_GL_SetSwapInterval");
    g_real_sdl_gl_get_proc_address = (db_sdl_gl_get_proc_address_fn)
            g_real_dlsym(handle, "SDL_GL_GetProcAddress");
    g_real_sdl_gl_create_context = (db_sdl_gl_create_context_fn)
            g_real_dlsym(handle, "SDL_GL_CreateContext");
    g_real_sdl_gl_make_current = (db_sdl_gl_make_current_fn)
            g_real_dlsym(handle, "SDL_GL_MakeCurrent");
    g_real_sdl_gl_get_current_window = (db_sdl_gl_get_current_window_fn)
            g_real_dlsym(handle, "SDL_GL_GetCurrentWindow");
    g_real_sdl_gl_get_current_context = (db_sdl_gl_get_current_context_fn)
            g_real_dlsym(handle, "SDL_GL_GetCurrentContext");
    g_real_sdl_gl_swap_window = (db_sdl_gl_swap_window_fn)
            g_real_dlsym(handle, "SDL_GL_SwapWindow");
    g_real_sdl_gl_destroy_context = (db_sdl_gl_destroy_context_fn)
            g_real_dlsym(handle, "SDL_GL_DestroyContext");
    g_real_sdl_create_window = (db_sdl_create_window_fn)
            g_real_dlsym(handle, "SDL_CreateWindow");
    g_real_sdl_create_window_with_properties = (db_sdl_create_window_with_properties_fn)
            g_real_dlsym(handle, "SDL_CreateWindowWithProperties");
    g_real_sdl_set_window_size = (db_sdl_set_window_size_fn)
            g_real_dlsym(handle, "SDL_SetWindowSize");
    g_real_sdl_set_window_title = (db_sdl_set_window_title_fn)
            g_real_dlsym(handle, "SDL_SetWindowTitle");
    g_real_sdl_show_window = (db_sdl_window_visibility_fn)
            g_real_dlsym(handle, "SDL_ShowWindow");
    g_real_sdl_destroy_window = (db_sdl_destroy_window_fn)
            g_real_dlsym(handle, "SDL_DestroyWindow");
    g_real_sdl_quit = (db_sdl_quit_fn)
            g_real_dlsym(handle, "SDL_Quit");
    g_real_sdl_get_error = (db_sdl_get_error_fn)
            g_real_dlsym(handle, "SDL_GetError");
    g_real_sdl_get_window_id = (db_sdl_get_window_id_fn)
            g_real_dlsym(handle, "SDL_GetWindowID");
    g_real_sdl_push_event = (db_sdl_push_event_fn)
            g_real_dlsym(handle, "SDL_PushEvent");
}

static bool db_sdl3_single_window_reuse_enabled(void) {
    return db_truthy_env("DROIDBRIDGE_SDL3_SINGLE_WINDOW_REUSE");
}

static bool db_sdl3_promote_reused_window_enabled(void) {
    return db_truthy_env("DROIDBRIDGE_SDL3_PROMOTE_REUSED_WINDOW");
}

static bool db_sdl3_canonical_freedreno_context_enabled(void) {
    return db_direct_freedreno_mesa_enabled()
            && db_sdl3_single_window_reuse_enabled()
            && db_truthy_env("DROIDBRIDGE_SDL3_CANONICAL_GAME_SURFACE");
}

/*
 * This gate exists only on the 26.3+ direct-Freedreno SDL3 route. <=26.2 never
 * enables the SDL3 single-loader/canonical-surface markers and therefore keeps
 * its existing GLFW code path completely unchanged.
 */
static bool db_sdl3_nativeglfw_gl_owner_enabled(void) {
    return db_direct_mesa_single_loader_enabled()
            && db_truthy_env("DROIDBRIDGE_NATIVE_GLFW_KGSL")
            && db_sdl3_single_window_reuse_enabled()
            && db_truthy_env("DROIDBRIDGE_SDL3_CANONICAL_GAME_SURFACE");
}

static bool db_neoforge_early_context_phase_active(void) {
    const char *phase = getenv("DROIDBRIDGE_SDL3_NEOFORGE_EARLY_CONTEXT_HANDOFF");
    return phase != NULL
            && (strcmp(phase, "early") == 0 || strcmp(phase, "1") == 0);
}

static void db_neoforge_finish_early_context_phase(unsigned int logical_create) {
    if (logical_create < 2 || !db_neoforge_early_context_phase_active()) return;
    setenv("DROIDBRIDGE_SDL3_NEOFORGE_EARLY_CONTEXT_HANDOFF", "final", 1);
    db_log("NeoForge early-display context phase complete logicalCreate=%u; final Minecraft context guard restored",
           logical_create);
}

/*
 * The direct-Freedreno 26.3 route establishes the scaled Android drawable before
 * RenderPearl asks for its final Minecraft SDL window. At sub-100% scale the one
 * retained SDL_Window is therefore already 960x540 (for example), but RenderPearl
 * later performs a logical create using the full 1920x1080 device size. Because
 * Android cannot resize that live SDL window, the create is intentionally aliased
 * and no normal SDL resize event is generated. Minecraft then keeps the requested
 * full-size dimensions until a later PiP/live-resolution transition happens.
 *
 * Queue only the two ordinary SDL3 window-size events here, immediately after the
 * final logical create is recognized. This updates RenderPearl/Minecraft before its
 * event loop begins without touching ANativeWindow, BufferQueue, EGLSurface, or the
 * working NativeGLFW context ownership.
 */
static void db_sdl3_queue_scaled_startup_window_events(
        SDL_Window *window,
        unsigned int logical_create,
        const char *title,
        int requested_width,
        int requested_height
) {
    if (window == NULL
            || g_startup_scaled_window_events_sent
            || !db_sdl3_nativeglfw_gl_owner_enabled()
            || logical_create < 3
            || requested_width <= 0
            || requested_height <= 0) {
        return;
    }

    /* Do not treat RenderPearl's hidden utility/test windows as the final game
     * window. The final request is normally titled "Minecraft* ...". */
    if (title == NULL || title[0] == '\0'
            || strstr(title, "RenderPearl OpenGL Hidden") != NULL) {
        return;
    }

    int render_width = 0;
    int render_height = 0;
    pthread_mutex_lock(&g_surface_mutex);
    ANativeWindow *native = g_owned_window;
    if (native != NULL) ANativeWindow_acquire(native);
    pthread_mutex_unlock(&g_surface_mutex);
    if (native != NULL) {
        render_width = ANativeWindow_getWidth(native);
        render_height = ANativeWindow_getHeight(native);
        ANativeWindow_release(native);
    }

    if (render_width <= 0 || render_height <= 0
            || (render_width == requested_width && render_height == requested_height)) {
        return;
    }

    if (g_real_sdl_get_window_id == NULL || g_real_sdl_push_event == NULL) {
        db_log("26.3 Freedreno startup size event unavailable logicalCreate=%u requested=%dx%d render=%dx%d getWindowID=%p pushEvent=%p",
               logical_create,
               requested_width,
               requested_height,
               render_width,
               render_height,
               (void *)g_real_sdl_get_window_id,
               (void *)g_real_sdl_push_event);
        return;
    }

    uint32_t window_id = g_real_sdl_get_window_id(window);
    if (window_id == 0) {
        db_log("26.3 Freedreno startup size event skipped: SDL_GetWindowID returned 0 logicalCreate=%u",
               logical_create);
        return;
    }

    db_sdl_event_t resized;
    memset(&resized, 0, sizeof(resized));
    resized.window.type = DB_SDL_EVENT_WINDOW_RESIZED;
    resized.window.window_id = window_id;
    resized.window.data1 = render_width;
    resized.window.data2 = render_height;

    db_sdl_event_t pixel_changed;
    memset(&pixel_changed, 0, sizeof(pixel_changed));
    pixel_changed.window.type = DB_SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED;
    pixel_changed.window.window_id = window_id;
    pixel_changed.window.data1 = render_width;
    pixel_changed.window.data2 = render_height;

    bool resized_ok = g_real_sdl_push_event(&resized);
    bool pixel_ok = g_real_sdl_push_event(&pixel_changed);
    if (resized_ok && pixel_ok) {
        g_startup_scaled_window_events_sent = 1;
    }

    db_log("26.3 Freedreno startup size synchronized before event loop logicalCreate=%u window=%p id=%u requested=%dx%d render=%dx%d resizedEvent=%d pixelEvent=%d surfaceMutation=0",
           logical_create,
           window,
           window_id,
           requested_width,
           requested_height,
           render_width,
           render_height,
           resized_ok ? 1 : 0,
           pixel_ok ? 1 : 0);
}

static SDL_Window *db_sdl3_reuse_or_create_window(
        const char *title,
        int width,
        int height,
        uint64_t flags,
        uint32_t properties,
        bool use_properties
) {
    db_sdl_create_window_fn create_window = g_real_sdl_create_window;
    db_sdl_create_window_with_properties_fn create_with_properties =
            g_real_sdl_create_window_with_properties;

    if (!db_sdl3_single_window_reuse_enabled()) {
        return use_properties
                ? (create_with_properties != NULL ? create_with_properties(properties) : NULL)
                : (create_window != NULL ? create_window(title, width, height, flags) : NULL);
    }

    pthread_mutex_lock(&g_single_window_mutex);
    if (g_single_window != NULL) {
        ++g_single_window_refs;
        ++g_single_window_create_count;
        SDL_Window *reused = g_single_window;
        unsigned int refs = g_single_window_refs;
        unsigned int creates = g_single_window_create_count;

        /*
         * 26.3 Direct Freedreno now lets this native bridge own the entire
         * logical-window lifecycle. Unlike the old Java cache, this means the
         * native wrapper can see the real window's requested dimensions/title
         * after RenderPearl's early 320x480 utility create. Promote the retained
         * physical SDL window to each later logical request so SDL/Mesa do not
         * stay permanently configured as the bootstrap utility window.
         */
        if (db_sdl3_promote_reused_window_enabled() && !use_properties) {
            bool size_ok = true;
            bool title_ok = true;
            if (width > 0 && height > 0 && g_real_sdl_set_window_size != NULL) {
                /* SDL's Android video backend does not support resizing a
                 * live native window. The 26.3 canonical route allocates the
                 * physical SDL window at Android surface size up front; do
                 * not attempt the unsupported 320x480 probe resize or a
                 * redundant final-game resize against its active EGLSurface. */
                if (!db_sdl3_canonical_freedreno_context_enabled()) {
                    size_ok = g_real_sdl_set_window_size(reused, width, height);
                }
            }
            if (title != NULL && title[0] != '\0' && g_real_sdl_set_window_title != NULL) {
                title_ok = g_real_sdl_set_window_title(reused, title);
            }
            db_log("26.3 Freedreno native window promotion physical=%p logicalCreate=%u size=%dx%d sizeOk=%d titleOk=%d title=%s",
                   reused,
                   creates,
                   width,
                   height,
                   size_ok ? 1 : 0,
                   title_ok ? 1 : 0,
                   title != NULL ? title : "<null>");
        }

        db_sdl3_queue_scaled_startup_window_events(
                reused, creates, title, width, height);

        if (!g_logged_single_window_reuse) {
            g_logged_single_window_reuse = 1;
            db_log("Snapshot 9 Android single-window compatibility active physical=%p logicalCreates=%u refs=%u",
                   reused, creates, refs);
        }
        db_log("Snapshot 9 logical SDL window reused physical=%p logicalCreate=%u refs=%u title=%s size=%dx%d flags=0x%" PRIx64 " props=%u",
               reused,
               creates,
               refs,
               title != NULL ? title : "<properties>",
               width,
               height,
               flags,
               properties);
        db_neoforge_finish_early_context_phase(creates);
        pthread_mutex_unlock(&g_single_window_mutex);
        return reused;
    }

    /* On 26.3 the first native SDL window is a 320x480 RenderPearl probe,
     * but Android's SDL backend rejects SDL_SetWindowSize when that window is
     * later reused as the 1920x1080 game window. Give the *physical* window
     * the actual Android surface dimensions at creation, before its EGL
     * drawable/context is allocated. This affects no other version/renderer. */
    int physical_width = width;
    int physical_height = height;
    uint64_t physical_flags = flags;
    bool canonical_bootstrap_window = db_sdl3_canonical_freedreno_context_enabled()
            && !use_properties
            && title != NULL
            && strstr(title, "RenderPearl OpenGL Hidden") != NULL;
    if (db_sdl3_nativeglfw_gl_owner_enabled() && !use_properties) {
        /*
         * NativeGLFW is the sole EGL producer on this route. SDL still owns
         * the SDL_Window for Android events/input, but creating that physical
         * window with SDL_WINDOW_OPENGL lets SDL's Android backend connect the
         * same ANativeWindow to EGL before NativeGLFW gets it. Android then
         * rejects NativeGLFW's eglCreateWindowSurface with EGL_BAD_ALLOC.
         *
         * RenderPearl's SDL_GL_* entry points are already redirected to
         * DroidBridge/NativeGLFW, so the physical SDL window does not need the
         * OPENGL flag. Strip only the physical flag; logical RenderPearl flags
         * and <=26.2 behavior remain untouched.
         */
        uint64_t before_owner_flags = physical_flags;
        physical_flags &= ~DB_SDL_WINDOW_OPENGL;
        if (before_owner_flags != physical_flags) {
            db_log("26.3+ Freedreno NativeGLFW EGL owner: stripped SDL_WINDOW_OPENGL from physical window requestedFlags=0x%" PRIx64 " physicalFlags=0x%" PRIx64,
                   before_owner_flags, physical_flags);
        }
    }

    if (canonical_bootstrap_window) {
        pthread_mutex_lock(&g_surface_mutex);
        ANativeWindow *native = g_owned_window;
        if (native != NULL) {
            ANativeWindow_acquire(native);
        }
        pthread_mutex_unlock(&g_surface_mutex);
        if (native != NULL) {
            int w = ANativeWindow_getWidth(native);
            int h = ANativeWindow_getHeight(native);
            ANativeWindow_release(native);
            if (w > 0 && h > 0) {
                physical_width = w;
                physical_height = h;
            }
        }

        /*
         * RenderPearl's first logical window is a hidden 320x480 utility
         * window.  Reusing that SDL_Window as Minecraft's final Android window
         * used to require a later hidden->visible transition.  SDL's Android EGL
         * backend is allowed to rebuild the window surface during that transition,
         * while Mesa's DRI drawable can still retain the old loaderPrivate
         * pointer.  The observed 26.3 crash is exactly that stale pointer being
         * dereferenced from the default framebuffer blit.
         *
         * The Android Activity is already the visibility owner, so create the one
         * physical SDL window visible and at the real ANativeWindow dimensions
         * from the start.  Logical RenderPearl windows can still be hidden/visible
         * conceptually, but no physical EGLSurface transition is required.
         */
        physical_flags &= ~DB_SDL_WINDOW_HIDDEN;
        db_log("26.3 Freedreno canonical bootstrap window physical=%dx%d requested=%dx%d requestedFlags=0x%" PRIx64 " physicalFlags=0x%" PRIx64,
               physical_width,
               physical_height,
               width,
               height,
               flags,
               physical_flags);
    }
    SDL_Window *created = use_properties
            ? (create_with_properties != NULL ? create_with_properties(properties) : NULL)
            : (create_window != NULL ? create_window(title, physical_width, physical_height, physical_flags) : NULL);
    if (created != NULL) {
        g_single_window = created;
        g_single_window_refs = 1;
        g_single_window_create_count = 1;
        g_startup_scaled_window_events_sent = 0;
        db_log("Snapshot 9 physical Android SDL window created=%p refs=1 title=%s requestedSize=%dx%d physicalSize=%dx%d requestedFlags=0x%" PRIx64 " physicalFlags=0x%" PRIx64 " props=%u",
               created,
               title != NULL ? title : "<properties>",
               width,
               height,
               physical_width,
               physical_height,
               flags,
               physical_flags,
               properties);
    }
    pthread_mutex_unlock(&g_single_window_mutex);
    return created;
}

/*
 * 26.4 creates the final RenderPearl window hidden (0x202a) and then calls
 * SDL_ShowWindow.  On the direct-Freedreno route all logical RenderPearl
 * windows alias one physical Android SDL_Window which was intentionally made
 * visible at bootstrap time.  Re-forwarding the logical ShowWindow into the
 * Android backend is therefore redundant and, unlike 26.3, can touch the
 * native window lifecycle underneath Mesa.  Make only this new 26.4 logical
 * transition a no-op; leave size/fullscreen/other SDL state behavior identical
 * to the proven 26.3 path.
 */
static bool db_sdl3_show_window(SDL_Window *window) {
    if (window != NULL && db_sdl3_nativeglfw_gl_owner_enabled()) {
        pthread_mutex_lock(&g_single_window_mutex);
        bool matches = (g_single_window != NULL && window == g_single_window);
        pthread_mutex_unlock(&g_single_window_mutex);
        if (matches) {
            db_log("26.4 logical SDL_ShowWindow suppressed on canonical Freedreno physical window=%p", window);
            return true;
        }
    }
    return g_real_sdl_show_window != NULL ? g_real_sdl_show_window(window) : false;
}

static SDL_Window *db_sdl3_create_window(
        const char *title,
        int width,
        int height,
        uint64_t flags
) {
    return db_sdl3_reuse_or_create_window(title, width, height, flags, 0u, false);
}

static SDL_Window *db_sdl3_create_window_with_properties(uint32_t properties) {
    return db_sdl3_reuse_or_create_window(NULL, 0, 0, 0u, properties, true);
}

static void db_sdl3_destroy_window(SDL_Window *window) {
    db_sdl_destroy_window_fn destroy_window = g_real_sdl_destroy_window;
    if (destroy_window == NULL || window == NULL) return;
    if (!db_sdl3_single_window_reuse_enabled()) {
        destroy_window(window);
        return;
    }

    bool destroy_physical = true;
    pthread_mutex_lock(&g_single_window_mutex);
    if (window == g_single_window && g_single_window_refs > 0) {
        if (g_single_window_refs > 1) {
            --g_single_window_refs;
            destroy_physical = false;
            db_log("Snapshot 9 logical SDL window destroy suppressed physical=%p remainingRefs=%u",
                   window, g_single_window_refs);
        } else {
            g_single_window_refs = 0;
            g_single_window = NULL;
            g_single_window_create_count = 0;
            g_logged_single_window_reuse = 0;
            db_log("Snapshot 9 final SDL window owner released physical=%p", window);
        }
    }
    pthread_mutex_unlock(&g_single_window_mutex);

    if (destroy_physical) destroy_window(window);
}

static void db_sdl3_quit(void) {
    db_sdl_quit_fn quit = g_real_sdl_quit;
    if (quit != NULL) quit();

    pthread_mutex_lock(&g_single_window_mutex);
    g_single_window = NULL;
    g_single_window_refs = 0;
    g_single_window_create_count = 0;
    g_logged_single_window_reuse = 0;
    pthread_mutex_unlock(&g_single_window_mutex);
    if (db_sdl3_canonical_freedreno_context_enabled()) {
        pthread_mutex_lock(&g_canonical_gl_mutex);
        g_canonical_gl_context = NULL;
        g_canonical_gl_window = NULL;
        g_canonical_gl_refs = 0;
        pthread_mutex_unlock(&g_canonical_gl_mutex);
        g_wrapped_sdl_context = NULL;
        g_wrapped_sdl_window = NULL;
    }
    db_log("Snapshot 9 Android single-window state reset after SDL_Quit");
}

static void *db_sdl3_gl_create_context(SDL_Window *window) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        pthread_mutex_lock(&g_canonical_gl_mutex);
        if (g_canonical_gl_context != NULL) {
            ++g_canonical_gl_refs;
            void *shared = g_canonical_gl_context;
            unsigned int refs = g_canonical_gl_refs;
            g_canonical_gl_window = window;
            pthread_mutex_unlock(&g_canonical_gl_mutex);
            droidbridgeMakeCurrent(NULL);
            g_wrapped_sdl_window = window;
            g_wrapped_sdl_context = shared;
            db_log("26.3+ Freedreno SDL logical context mapped to NativeGLFW owner window=%p context=%p refs=%u",
                   window, shared, refs);
            return shared;
        }
        pthread_mutex_unlock(&g_canonical_gl_mutex);

        /* Never call SDL_GL_CreateContext on this route. SDL's Mesa
         * EGLWindowSurface is the object proven to go stale at +0x2dc34. */
        void *context = droidbridgeCreateContext(NULL);
        if (context == NULL) {
            db_log("26.3+ Freedreno NativeGLFW SDL context creation FAILED window=%p", window);
            return NULL;
        }

        pthread_mutex_lock(&g_canonical_gl_mutex);
        g_canonical_gl_window = window;
        g_canonical_gl_context = context;
        g_canonical_gl_refs = 1;
        pthread_mutex_unlock(&g_canonical_gl_mutex);
        g_wrapped_sdl_window = window;
        g_wrapped_sdl_context = context;
        db_log("26.3+ Freedreno NativeGLFW owns SDL GL context/surface window=%p context=%p refs=1 SDLContextCreated=0",
               window, context);
        return context;
    }

    db_sdl_gl_create_context_fn real = g_real_sdl_gl_create_context;
    if (real == NULL) return NULL;

    bool canonical = db_sdl3_canonical_freedreno_context_enabled();
    if (canonical) {
        pthread_mutex_lock(&g_canonical_gl_mutex);
        if (g_canonical_gl_context != NULL && g_canonical_gl_window == window
                && g_real_sdl_gl_make_current != NULL) {
            /* SDL_GL_CreateContext implicitly makes the new context current.
             * Restore that contract when a logical creator receives the
             * existing physical context. A failed make-current is not success. */
            if (g_real_sdl_gl_make_current(window, g_canonical_gl_context)) {
                ++g_canonical_gl_refs;
                void *shared = g_canonical_gl_context;
                unsigned int refs = g_canonical_gl_refs;
                pthread_mutex_unlock(&g_canonical_gl_mutex);
                g_wrapped_sdl_window = window;
                g_wrapped_sdl_context = shared;
                db_log("26.3 Freedreno logical SDL context reused window=%p context=%p refs=%u",
                       window, shared, refs);
                return shared;
            }
            db_log("26.3 Freedreno canonical SDL context make-current failed; using real SDL context creation error=%s",
                   g_real_sdl_get_error != NULL ? g_real_sdl_get_error() : "unknown");
        }
        pthread_mutex_unlock(&g_canonical_gl_mutex);
    }

    void *context = real(window);
    if (canonical && context != NULL) {
        pthread_mutex_lock(&g_canonical_gl_mutex);
        if (g_canonical_gl_context == NULL) {
            g_canonical_gl_window = window;
            g_canonical_gl_context = context;
            g_canonical_gl_refs = 1;
            db_log("26.3 Freedreno physical SDL GL context established window=%p context=%p refs=1",
                   window, context);
        }
        pthread_mutex_unlock(&g_canonical_gl_mutex);
    }
    if (db_wrapped_context_guard_enabled() && context != NULL) {
        g_wrapped_sdl_window = window;
        g_wrapped_sdl_context = context;
        if (!g_logged_wrapped_sdl_context_cache) {
            g_logged_wrapped_sdl_context_cache = 1;
            db_log("%s SDL context cached window=%p context=%p", db_wrapped_renderer_name(), window, context);
        }
    }
    return context;
}

static bool db_sdl3_gl_make_current(SDL_Window *window, void *context) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        if (context == NULL) {
            if (!g_logged_wrapped_sdl_transient_clear) {
                g_logged_wrapped_sdl_transient_clear = 1;
                db_log("26.3+ Freedreno NativeGLFW SDL_GL_MakeCurrent(NULL) suppressed window=%p context=%p",
                       window, g_wrapped_sdl_context);
            }
            return true;
        }
        droidbridgeMakeCurrent(NULL);
        if (window != NULL) g_wrapped_sdl_window = window;
        g_wrapped_sdl_context = context;
        return true;
    }

    db_sdl_gl_make_current_fn real = g_real_sdl_gl_make_current;
    if (real == NULL) return false;

    /* Snapshot 4 performs a transient SDL_GL_MakeCurrent(..., NULL) while
     * rebuilding render resources. Let SDL keep ownership of the live context
     * instead of repairing only Android EGL after SDL has already cleared its
     * own current-context state. */
    if (db_wrapped_context_guard_enabled() && context == NULL
            && g_wrapped_sdl_context != NULL && !g_wrapped_sdl_allow_clear) {
        if (!db_neoforge_early_context_phase_active()) {
            if (!g_logged_wrapped_sdl_transient_clear) {
                g_logged_wrapped_sdl_transient_clear = 1;
                db_log("%s transient SDL_GL_MakeCurrent(NULL) suppressed window=%p cachedWindow=%p context=%p",
                       db_wrapped_renderer_name(), window, g_wrapped_sdl_window, g_wrapped_sdl_context);
            }
            return true;
        }

        bool released = real(window, context);
        if (released) {
            g_wrapped_sdl_context = NULL;
            g_wrapped_sdl_window = NULL;
            db_log("%s NeoForge early-display SDL_GL_MakeCurrent(NULL) passed through",
                   db_wrapped_renderer_name());
        }
        return released;
    }

    bool result = real(window, context);
    if (db_wrapped_context_guard_enabled() && result && context != NULL) {
        g_wrapped_sdl_window = window;
        g_wrapped_sdl_context = context;
    } else if (db_wrapped_context_guard_enabled() && result && context == NULL
            && db_neoforge_early_context_phase_active()) {
        g_wrapped_sdl_context = NULL;
        g_wrapped_sdl_window = NULL;
    }
    return result;
}

static void *db_sdl3_gl_get_current_context(void) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()) return g_wrapped_sdl_context;
    db_sdl_gl_get_current_context_fn get_current = g_real_sdl_gl_get_current_context;
    void *current = get_current != NULL ? get_current() : NULL;
    if (db_wrapped_context_guard_enabled() && current == NULL
            && g_wrapped_sdl_context != NULL && g_real_sdl_gl_make_current != NULL) {
        SDL_Window *window = g_wrapped_sdl_window;
        if (window != NULL && g_real_sdl_gl_make_current(window, g_wrapped_sdl_context)) {
            current = get_current != NULL ? get_current() : g_wrapped_sdl_context;
            if (!g_logged_wrapped_sdl_context_restore) {
                g_logged_wrapped_sdl_context_restore = 1;
                db_log("%s SDL current context restored window=%p context=%p",
                       db_wrapped_renderer_name(), window, g_wrapped_sdl_context);
            }
        }
    }
    return current;
}

static SDL_Window *db_sdl3_gl_get_current_window(void) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()) return g_wrapped_sdl_window;
    db_sdl_gl_get_current_window_fn get_window = g_real_sdl_gl_get_current_window;
    SDL_Window *current = get_window != NULL ? get_window() : NULL;
    if (db_wrapped_context_guard_enabled() && current == NULL && g_wrapped_sdl_window != NULL
            && g_wrapped_sdl_context != NULL && g_real_sdl_gl_make_current != NULL) {
        if (g_real_sdl_gl_make_current(g_wrapped_sdl_window, g_wrapped_sdl_context)) {
            current = get_window != NULL ? get_window() : g_wrapped_sdl_window;
        }
    }
    return current;
}

static bool db_sdl3_gl_swap_window(SDL_Window *window) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        if (g_wrapped_sdl_context == NULL) return false;
        droidbridgeMakeCurrent(NULL);
        droidbridgeSwapBuffers();
        static unsigned int logged_nativeglfw_swaps = 0;
        if (logged_nativeglfw_swaps < 3) {
            ++logged_nativeglfw_swaps;
            db_log("26.3+ Freedreno SDL_GL_SwapWindow routed to NativeGLFW frame=%u window=%p context=%p",
                   logged_nativeglfw_swaps,
                   window != NULL ? window : g_wrapped_sdl_window,
                   g_wrapped_sdl_context);
        }
        return true;
    }

    db_sdl_gl_swap_window_fn real = g_real_sdl_gl_swap_window;
    if (real == NULL) return false;

    if (db_wrapped_context_guard_enabled() && g_wrapped_sdl_context != NULL
            && g_real_sdl_gl_make_current != NULL) {
        void *current_context = g_real_sdl_gl_get_current_context != NULL
                ? g_real_sdl_gl_get_current_context() : NULL;
        SDL_Window *current_window = g_real_sdl_gl_get_current_window != NULL
                ? g_real_sdl_gl_get_current_window() : NULL;
        SDL_Window *target_window = window != NULL ? window : g_wrapped_sdl_window;
        if (current_context != g_wrapped_sdl_context || current_window != target_window) {
            (void) g_real_sdl_gl_make_current(target_window, g_wrapped_sdl_context);
        }
        (void) droidbridge_sdl3_ensure_current_gl_context("SDL_GL_SwapWindow");
    }

    bool result = real(window);
    if (db_wrapped_context_guard_enabled()) {
        const char *error = (!result && g_real_sdl_get_error != NULL)
                ? g_real_sdl_get_error() : "";
        if (!g_logged_wrapped_sdl_first_swap || (!result && !g_logged_wrapped_sdl_swap_failure)) {
            if (!g_logged_wrapped_sdl_first_swap) g_logged_wrapped_sdl_first_swap = 1;
            if (!result) g_logged_wrapped_sdl_swap_failure = 1;
            db_log("%s SDL_GL_SwapWindow result=%d window=%p currentWindow=%p currentContext=%p cachedContext=%p error=%s",
                   db_wrapped_renderer_name(),
                   result ? 1 : 0,
                   window,
                   g_real_sdl_gl_get_current_window != NULL ? g_real_sdl_gl_get_current_window() : NULL,
                   g_real_sdl_gl_get_current_context != NULL ? g_real_sdl_gl_get_current_context() : NULL,
                   g_wrapped_sdl_context,
                   error != NULL ? error : "<null>");
        }
    }
    return result;
}


static int db_openjdk_target_fps(void) {
    const char *value = getenv("DROIDBRIDGE_VSYNC_TARGET_FPS");
    if (value == NULL || value[0] == '\0') return 60;
    char *end = NULL;
    long parsed = strtol(value, &end, 10);
    if (end == value || parsed < 30 || parsed > 360) return 60;
    return (int)parsed;
}

static int64_t db_monotonic_time_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return ((int64_t)now.tv_sec * 1000000000LL) + (int64_t)now.tv_nsec;
}

static void db_sleep_ns(int64_t duration_ns) {
    if (duration_ns <= 0) return;
    struct timespec request;
    request.tv_sec = (time_t)(duration_ns / 1000000000LL);
    request.tv_nsec = (long)(duration_ns % 1000000000LL);
    while (nanosleep(&request, &request) != 0 && errno == EINTR) {
        /* Continue sleeping for the remaining duration. */
    }
}

static void db_pace_openjdk_present(void) {
    if (g_openjdk_swap_interval <= 0
            || !db_truthy_env("DROIDBRIDGE_VSYNC_FRAME_PACING")) {
        return;
    }

    /*
     * A successful eglSwapInterval return is not sufficient on Android KGSL:
     * Mesa can accept interval 1 while SDL_GL_SwapWindow remains nonblocking.
     * Avoid double-throttling a driver that really waits, but keep the adaptive
     * guard available after the live cadence detector proves the interval is
     * being ignored.
     */
    if (g_openjdk_cached_egl_interval_applied
            && !g_openjdk_driver_interval_nonblocking) {
        return;
    }

    int target_fps = db_openjdk_target_fps();
    int64_t period_ns = 1000000000LL / target_fps;
    int64_t now_ns = db_monotonic_time_ns();
    if (now_ns <= 0 || period_ns <= 0) return;

    int64_t wait_ns = 0;
    pthread_mutex_lock(&g_openjdk_pacing_mutex);
    if (g_openjdk_next_present_ns <= 0
            || now_ns > g_openjdk_next_present_ns + (period_ns * 4)) {
        g_openjdk_next_present_ns = now_ns + period_ns;
    } else {
        if (now_ns < g_openjdk_next_present_ns) {
            wait_ns = g_openjdk_next_present_ns - now_ns;
        }
        g_openjdk_next_present_ns += period_ns;
    }
    pthread_mutex_unlock(&g_openjdk_pacing_mutex);

    if (!g_logged_openjdk_pacer) {
        g_logged_openjdk_pacer = 1;
        db_log("%s OpenJDK adaptive frame pacing enabled targetFps=%d interval=%d driverEgl=%d nonblocking=%d",
               db_wrapped_renderer_name(),
               target_fps,
               g_openjdk_swap_interval,
               g_openjdk_cached_egl_interval_applied,
               g_openjdk_driver_interval_nonblocking);
    }
    db_sleep_ns(wait_ns);
}

/*
 * Verify whether Mesa's accepted swap interval actually blocks presentation.
 * The detector only arms when rendering is faster than the requested refresh
 * rate and the swap itself repeatedly consumes less than one quarter frame.
 * Slow rendering is never delayed, and a genuinely blocking driver disables
 * the software guard.
 */
static void db_update_openjdk_present_behavior(int64_t present_entry_ns,
                                                int64_t swap_start_ns,
                                                int64_t swap_end_ns) {
    if (!(db_direct_freedreno_mesa_enabled()
                    || db_sdl3_wrapped_opengl_enabled())
            || g_openjdk_swap_interval <= 0
            || !db_truthy_env("DROIDBRIDGE_VSYNC_FRAME_PACING")
            || present_entry_ns <= 0
            || swap_start_ns <= 0
            || swap_end_ns < swap_start_ns) {
        return;
    }

    int target_fps = db_openjdk_target_fps();
    int64_t period_ns = 1000000000LL / target_fps;
    if (period_ns <= 0) return;

    int64_t swap_duration_ns = swap_end_ns - swap_start_ns;
    int64_t present_interval_ns = 0;
    int should_log = 0;

    pthread_mutex_lock(&g_openjdk_pacing_mutex);
    if (g_openjdk_last_present_entry_ns > 0
            && present_entry_ns > g_openjdk_last_present_entry_ns) {
        present_interval_ns = present_entry_ns - g_openjdk_last_present_entry_ns;
    }
    g_openjdk_last_present_entry_ns = present_entry_ns;
    g_openjdk_last_swap_duration_ns = swap_duration_ns;

    if (g_openjdk_cached_egl_interval_applied) {
        const int64_t fast_frame_threshold = (period_ns * 9) / 10;
        const int64_t blocking_swap_threshold = period_ns / 2;
        const int64_t fast_swap_threshold = period_ns / 4;

        if (swap_duration_ns >= blocking_swap_threshold) {
            g_openjdk_fast_swap_streak = 0;
            g_openjdk_driver_interval_nonblocking = 0;
            g_openjdk_next_present_ns = 0;
        } else if (present_interval_ns > 0
                && present_interval_ns < fast_frame_threshold
                && swap_duration_ns < fast_swap_threshold) {
            if (g_openjdk_fast_swap_streak < 1000) {
                g_openjdk_fast_swap_streak++;
            }
            if (!g_openjdk_driver_interval_nonblocking
                    && g_openjdk_fast_swap_streak >= 6) {
                g_openjdk_driver_interval_nonblocking = 1;
                g_openjdk_next_present_ns = swap_end_ns + period_ns;
                should_log = 1;
            }
        } else if (!g_openjdk_driver_interval_nonblocking) {
            g_openjdk_fast_swap_streak = 0;
        }
    }
    pthread_mutex_unlock(&g_openjdk_pacing_mutex);

    if (should_log && !g_logged_openjdk_adaptive_pacer) {
        g_logged_openjdk_adaptive_pacer = 1;
        db_log("%s eglSwapInterval accepted but SDL swap is nonblocking; adaptive pacing armed targetFps=%d presentIntervalNs=%lld swapDurationNs=%lld",
               db_wrapped_renderer_name(),
               target_fps,
               (long long)present_interval_ns,
               (long long)swap_duration_ns);
    }
}

__attribute__((visibility("default")))
int droidbridge_sdl3_set_openjdk_swap_interval(int requested,
                                                void *original_setter_ptr) {
    typedef bool (*direct_setter_fn)(int);
    typedef int (*cached_egl_interval_fn)(int, const char *);
    static int logged_first = 0;
    static int logged_failure = 0;

    int effective = (db_truthy_env("FORCE_VSYNC")
            || db_truthy_env("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC"))
            ? 1 : (requested > 0 ? 1 : 0);

    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        droidbridgeSwapInterval(effective);
        pthread_mutex_lock(&g_openjdk_pacing_mutex);
        g_openjdk_swap_interval = effective;
        g_openjdk_cached_egl_interval_applied = 1;
        g_openjdk_swap_interval_pending_render_reapply = 0;
        g_openjdk_interval_reapplied_on_present = 1;
        pthread_mutex_unlock(&g_openjdk_pacing_mutex);
        if (!logged_first) {
            logged_first = 1;
            db_log("26.3+ Freedreno OpenJDK SDL_GL_SetSwapInterval routed to NativeGLFW requested=%d effective=%d",
                   requested, effective);
        }
        return 1;
    }
    direct_setter_fn original = (direct_setter_fn)original_setter_ptr;
    bool sdl_result = original != NULL ? original(effective) : false;

    int egl_result = 0;
    if (db_sdl3_wrapped_opengl_enabled()) {
        void *provider = db_load_sdl3_egl_provider();
        db_resolve_real_dlsym();
        cached_egl_interval_fn set_cached = provider != NULL && g_real_dlsym != NULL
                ? (cached_egl_interval_fn)g_real_dlsym(
                        provider,
                        "droidbridge_sdl3_egl_set_cached_swap_interval")
                : NULL;
        if (set_cached != NULL) {
            egl_result = set_cached(effective, "OpenJDKDirectSDL_GL_SetSwapInterval");
        }
    }

    int direct_mesa_egl_result = db_direct_mesa_apply_swap_interval(
            effective,
            "OpenJDKDirectSDL_GL_SetSwapInterval");
    int surface_interval_requested = 0;
    if (db_direct_freedreno_mesa_enabled()
            || db_sdl3_wrapped_opengl_enabled()) {
        ANativeWindow *surface_window = NULL;
        pthread_mutex_lock(&g_surface_mutex);
        if (g_owned_window != NULL) {
            surface_window = g_owned_window;
            ANativeWindow_acquire(surface_window);
        }
        pthread_mutex_unlock(&g_surface_mutex);
        if (surface_window != NULL) {
            setNativeWindowSwapInterval(surface_window, effective);
            ANativeWindow_release(surface_window);
            surface_interval_requested = 1;
        }
    }
    int driver_interval_applied = egl_result != 0 || direct_mesa_egl_result != 0;

    pthread_mutex_lock(&g_openjdk_pacing_mutex);
    int previous_interval = g_openjdk_swap_interval;
    g_openjdk_swap_interval = effective;
    g_openjdk_cached_egl_interval_applied = driver_interval_applied ? 1 : 0;
    if (original_setter_ptr == NULL) {
        g_openjdk_swap_interval_pending_render_reapply = 1;
    } else {
        g_openjdk_swap_interval_pending_render_reapply = 0;
    }
    if (previous_interval != effective) {
        g_openjdk_interval_reapplied_on_present = 0;
        g_openjdk_next_present_ns = 0;
        g_openjdk_driver_interval_nonblocking = 0;
        g_openjdk_fast_swap_streak = 0;
        g_openjdk_last_present_entry_ns = 0;
        g_openjdk_last_swap_duration_ns = 0;
    }
    pthread_mutex_unlock(&g_openjdk_pacing_mutex);

    bool pacing_requested = effective > 0
            && db_truthy_env("DROIDBRIDGE_VSYNC_FRAME_PACING");
    bool adaptive_guard = pacing_requested
            && (db_direct_freedreno_mesa_enabled()
                || db_sdl3_wrapped_opengl_enabled());
    bool pacing_ready = pacing_requested
            && (!driver_interval_applied || adaptive_guard);
    bool result = sdl_result || driver_interval_applied || pacing_ready;
    if (!logged_first || (!result && !logged_failure)) {
        if (!logged_first) logged_first = 1;
        if (!result) logged_failure = 1;
        db_log("%s OpenJDK SDL_GL_SetSwapInterval requested=%d effective=%d sdl=%d wrappedEgl=%d mesaEgl=%d surface=%d pacingGuard=%d targetFps=%d function=%p",
               db_wrapped_renderer_name(),
               requested,
               effective,
               sdl_result ? 1 : 0,
               egl_result,
               direct_mesa_egl_result,
               surface_interval_requested,
               adaptive_guard ? 1 : (pacing_ready ? 1 : 0),
               db_openjdk_target_fps(),
               original_setter_ptr);
    }
    return result ? 1 : 0;
}

static bool db_sdl3_gl_set_swap_interval(int interval) {
    return droidbridge_sdl3_set_openjdk_swap_interval(
            interval,
            (void *)g_real_sdl_gl_set_swap_interval) != 0;
}

/*
 * Direct presentation entry used by the OpenJDK-only bootstrap shim.
 *
 * Snapshot 4 resolves SDLVideo$Functions before DroidBridge can safely
 * rebind LWJGL's DynamicLinkLoader natives on Java 25. The Java binding now
 * passes SDL's already-resolved SDL_GL_SwapWindow function pointer here for
 * each frame. This keeps the original SDL presentation owner while allowing
 * the EGL compatibility layer to restore MobileGlues' cached context first.
 */
__attribute__((visibility("default")))
int droidbridge_sdl3_present_openjdk_frame(void *window_ptr,
                                            void *original_swap_ptr) {
    typedef bool (*direct_swap_fn)(SDL_Window *);
    typedef int (*cached_egl_swap_fn)(const char *);
    static int logged_first = 0;
    static int logged_failure = 0;

    SDL_Window *window = (SDL_Window *)window_ptr;
    direct_swap_fn original = (direct_swap_fn)original_swap_ptr;

    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        static unsigned int logged_nativeglfw_direct_swaps = 0;
        if (g_wrapped_sdl_context == NULL) {
            if (!logged_failure) {
                logged_failure = 1;
                db_log("26.3+ Freedreno OpenJDK NativeGLFW present missing context window=%p", window);
            }
            return 0;
        }
        droidbridgeMakeCurrent(NULL);
        droidbridgeSwapBuffers();
        if (logged_nativeglfw_direct_swaps < 3) {
            ++logged_nativeglfw_direct_swaps;
            db_log("26.3+ Freedreno OpenJDK SDL_GL_SwapWindow routed to NativeGLFW frame=%u window=%p context=%p originalSDL=%p",
                   logged_nativeglfw_direct_swaps, window, g_wrapped_sdl_context, original_swap_ptr);
        }
        return 1;
    }

    if (original == NULL) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log("OpenJDK direct SDL presentation missing original function window=%p",
                   window);
        }
        return 0;
    }

    int64_t present_entry_ns = db_monotonic_time_ns();

    /* The interval must be applied while the final game presentation context is
     * current. This handles both launch-time bootstrap-surface success and live
     * off/on changes delivered by Android's options watcher. */
    int context_ready = droidbridge_sdl3_ensure_current_gl_context(
            "OpenJDKDirectSDL_GL_SwapWindow");

    if (g_openjdk_swap_interval < 0) {
        int initial_interval = db_truthy_env("FORCE_VSYNC")
                || db_truthy_env("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC") ? 1 : 0;
        (void)droidbridge_sdl3_set_openjdk_swap_interval(
                initial_interval,
                (void *)g_real_sdl_gl_set_swap_interval);
    }

    int pending_render_reapply = 0;
    int cached_interval_applied = 0;
    int reapplied_on_present = 0;
    pthread_mutex_lock(&g_openjdk_pacing_mutex);
    pending_render_reapply = g_openjdk_swap_interval_pending_render_reapply;
    cached_interval_applied = g_openjdk_cached_egl_interval_applied;
    reapplied_on_present = g_openjdk_interval_reapplied_on_present;
    pthread_mutex_unlock(&g_openjdk_pacing_mutex);

    if ((db_sdl3_wrapped_opengl_enabled()
                    || db_direct_freedreno_mesa_enabled())
            && (pending_render_reapply
                || !cached_interval_applied
                || !reapplied_on_present)) {
        (void)droidbridge_sdl3_set_openjdk_swap_interval(
                g_openjdk_swap_interval,
                (void *)g_real_sdl_gl_set_swap_interval);
        pthread_mutex_lock(&g_openjdk_pacing_mutex);
        if (g_openjdk_cached_egl_interval_applied) {
            g_openjdk_interval_reapplied_on_present = 1;
        }
        g_openjdk_swap_interval_pending_render_reapply = 0;
        pthread_mutex_unlock(&g_openjdk_pacing_mutex);
    }
    db_pace_openjdk_present();

    int64_t swap_start_ns = db_monotonic_time_ns();
    bool result = original(window);
    int64_t swap_end_ns = db_monotonic_time_ns();
    db_update_openjdk_present_behavior(
            present_entry_ns,
            swap_start_ns,
            swap_end_ns);
    int egl_fallback = 0;

    /* SDL may reject a swap when its internal current-context bookkeeping was
     * cleared even though the Android EGL context and surface are still live.
     * In that specific failure case, present the cached SDL-owned EGL surface
     * through the compatibility provider instead of dropping the frame. */
    if (!result && db_sdl3_wrapped_opengl_enabled()) {
        void *provider = db_load_sdl3_egl_provider();
        db_resolve_real_dlsym();
        cached_egl_swap_fn fallback = provider != NULL && g_real_dlsym != NULL
                ? (cached_egl_swap_fn)g_real_dlsym(
                        provider,
                        "droidbridge_sdl3_egl_swap_cached_surface")
                : NULL;
        if (fallback != NULL) {
            egl_fallback = fallback("OpenJDKDirectSDL_GL_SwapWindow");
            result = egl_fallback != 0;
        }
    }

    if (!logged_first || (!result && !logged_failure)) {
        if (!logged_first) logged_first = 1;
        if (!result) logged_failure = 1;
        const char *error = (!result && g_real_sdl_get_error != NULL)
                ? g_real_sdl_get_error() : "";
        db_log("%s OpenJDK direct SDL_GL_SwapWindow result=%d contextReady=%d eglFallback=%d window=%p function=%p error=%s",
               db_wrapped_renderer_name(),
               result ? 1 : 0,
               context_ready,
               egl_fallback,
               window,
               original_swap_ptr,
               error != NULL ? error : "<null>");
    }
    return result ? 1 : 0;
}

static bool db_sdl3_gl_destroy_context(void *context) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()) {
        if (context == NULL) return true;
        pthread_mutex_lock(&g_canonical_gl_mutex);
        if (context == g_canonical_gl_context && g_canonical_gl_refs > 1) {
            --g_canonical_gl_refs;
            unsigned int refs = g_canonical_gl_refs;
            pthread_mutex_unlock(&g_canonical_gl_mutex);
            db_log("26.3+ Freedreno NativeGLFW logical SDL context destroy deferred context=%p remainingRefs=%u",
                   context, refs);
            return true;
        }
        pthread_mutex_unlock(&g_canonical_gl_mutex);
        db_log("26.3+ Freedreno NativeGLFW final SDL context destroy suppressed context=%p", context);
        return true;
    }

    db_sdl_gl_destroy_context_fn real = g_real_sdl_gl_destroy_context;
    if (real == NULL) return false;
    if (db_sdl3_canonical_freedreno_context_enabled() && context != NULL) {
        pthread_mutex_lock(&g_canonical_gl_mutex);
        if (context == g_canonical_gl_context && g_canonical_gl_refs > 1) {
            --g_canonical_gl_refs;
            unsigned int refs = g_canonical_gl_refs;
            pthread_mutex_unlock(&g_canonical_gl_mutex);
            db_log("26.3 Freedreno logical SDL context delete deferred context=%p remainingRefs=%u",
                   context, refs);
            return true;
        }
        if (context == g_canonical_gl_context) {
            g_canonical_gl_context = NULL;
            g_canonical_gl_window = NULL;
            g_canonical_gl_refs = 0;
        }
        pthread_mutex_unlock(&g_canonical_gl_mutex);
    }
    if (db_wrapped_context_guard_enabled() && context == g_wrapped_sdl_context) {
        g_wrapped_sdl_allow_clear = 1;
        if (g_real_sdl_gl_make_current != NULL) {
            (void) g_real_sdl_gl_make_current(NULL, NULL);
        }
        g_wrapped_sdl_allow_clear = 0;
        g_wrapped_sdl_context = NULL;
        g_wrapped_sdl_window = NULL;
    }
    return real(context);
}

static bool db_sdl3_gl_set_attribute(int attr, int value) {
    db_sdl_gl_set_attribute_fn real = g_real_sdl_gl_set_attribute;
    if (real == NULL) return false;
    if (!db_sdl3_wrapped_opengl_enabled()) return real(attr, value);

    int translated = value;
    switch (attr) {
        case DB_SDL_GL_CONTEXT_MAJOR_VERSION:
            g_requested_gl_major = value;
            if (translated > 3) translated = 3;
            break;
        case DB_SDL_GL_CONTEXT_MINOR_VERSION:
            g_requested_gl_minor = value;
            if (translated > 2) translated = 2;
            break;
        case DB_SDL_GL_CONTEXT_FLAGS:
            g_requested_gl_flags = value;
            translated &= ~DB_SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG;
            break;
        case DB_SDL_GL_CONTEXT_PROFILE_MASK:
            g_requested_gl_profile = value;
            if ((value & (DB_SDL_GL_CONTEXT_PROFILE_CORE
                    | DB_SDL_GL_CONTEXT_PROFILE_COMPATIBILITY)) != 0) {
                translated = DB_SDL_GL_CONTEXT_PROFILE_ES;
            }
            break;
        case DB_SDL_GL_FRAMEBUFFER_SRGB_CAPABLE:
            if (db_is_mobilegl_renderer()) {
                /*
                 * Minecraft 26.2/26.3 RenderPearl asks SDL for an sRGB-capable
                 * system framebuffer. SDL refuses to create the EGL surface when
                 * the provider does not advertise EGL_KHR_gl_colorspace. MobileGL
                 * currently exports its own EGL implementation but does not expose
                 * that extension, so Espryt is rejected before MobileGL can create
                 * a GL context and Minecraft silently falls back to Vulkan.
                 *
                 * Android's default window surface already presents in the normal
                 * sRGB display colorspace. Suppress SDL's explicit sRGB EGL request
                 * for MobileGL only, while remembering the logical request so
                 * SDL_GL_GetAttribute remains consistent for RenderPearl.
                 */
                g_requested_framebuffer_srgb = value;
                translated = 0;
                if (!g_logged_mobilegl_srgb_attribute) {
                    g_logged_mobilegl_srgb_attribute = 1;
                    db_log("MobileGL SDL sRGB framebuffer request suppressed requested=%d android=%d",
                           value, translated);
                }
            }
            break;
        default:
            break;
    }

    if (translated != value) {
        db_log("SDL_GL_SetAttribute translated attr=%d requested=%d android=%d",
               attr, value, translated);
        g_logged_gl_attribute_translation = 1;
    }
    return real(attr, translated);
}

static bool db_sdl3_gl_get_attribute(int attr, int *value) {
    db_sdl_gl_get_attribute_fn real = g_real_sdl_gl_get_attribute;
    if (real == NULL) return false;
    bool result = real(attr, value);
    if (!result || value == NULL || !db_sdl3_wrapped_opengl_enabled()) return result;

    switch (attr) {
        case DB_SDL_GL_CONTEXT_MAJOR_VERSION:
            if (g_requested_gl_major >= 0) *value = g_requested_gl_major;
            break;
        case DB_SDL_GL_CONTEXT_MINOR_VERSION:
            if (g_requested_gl_minor >= 0) *value = g_requested_gl_minor;
            break;
        case DB_SDL_GL_CONTEXT_FLAGS:
            if (g_requested_gl_flags >= 0) *value = g_requested_gl_flags;
            break;
        case DB_SDL_GL_CONTEXT_PROFILE_MASK:
            if (g_requested_gl_profile >= 0) *value = g_requested_gl_profile;
            break;
        case DB_SDL_GL_FRAMEBUFFER_SRGB_CAPABLE:
            if (db_is_mobilegl_renderer() && g_requested_framebuffer_srgb >= 0) {
                *value = g_requested_framebuffer_srgb;
            }
            break;
        default:
            break;
    }
    return result;
}

static bool db_disable_persistent_mapping(void) {
    if (db_truthy_env("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING")) return true;
    return db_sdl3_wrapped_opengl_enabled() && !db_is_ltw_renderer();
}

static bool db_is_blocked_buffer_storage_proc(const char *name) {
    if (name == NULL) return false;
    return strcmp(name, "glBufferStorage") == 0
            || strcmp(name, "glBufferStorageEXT") == 0
            || strcmp(name, "glNamedBufferStorage") == 0
            || strcmp(name, "glNamedBufferStorageEXT") == 0;
}

static bool db_is_blocked_buffer_storage_extension(const char *extension) {
    if (extension == NULL) return false;
    return strcmp(extension, DB_BLOCKED_BUFFER_STORAGE_ARB) == 0
            || strcmp(extension, DB_BLOCKED_BUFFER_STORAGE_EXT) == 0;
}

static void *db_lookup_wrapped_gl_proc(const char *name) {
    if (name == NULL || name[0] == '\0') return NULL;
    db_resolve_real_dlsym();
    void *wrapper = db_load_sdl3_gl_wrapper();
    if (wrapper == NULL || g_real_dlsym == NULL) return NULL;

    typedef void *(*egl_get_proc_fn)(const char *);
    egl_get_proc_fn get_proc = (egl_get_proc_fn)
            g_real_dlsym(wrapper, "eglGetProcAddress");
    void *symbol = NULL;

    /*
     * On API 29 LTW overrides core GL entry points through eglGetProcAddress.
     * RenderPearl requires SDL_GL_GetProcAddress and the OpenGL loader to return
     * the exact same pointer. Use the same EGL-first resolver as the LWJGL
     * RenderSpec hook, then fall back to the ELF export only if LTW has no proc.
     * Keep newer Android devices on the established dlsym-first path.
     */
    if (db_android10_ltw_proc_consistency_enabled() && get_proc != NULL) {
        symbol = get_proc(name);
        if (name != NULL && strcmp(name, "glGetError") == 0) {
            db_log("Android 10 LTW canonical SDL glGetError proc=%p wrapper=%p",
                   symbol, wrapper);
        }
    }

    if (symbol == NULL) symbol = g_real_dlsym(wrapper, name);
    if (symbol == NULL && get_proc != NULL) symbol = get_proc(name);
    return symbol;
}

static const unsigned char *db_gl_get_string_filter(unsigned int name) {
    if (g_real_gl_get_string == NULL) {
        g_real_gl_get_string = (db_gl_get_string_fn)
                db_lookup_wrapped_gl_proc("glGetString");
    }
    if (g_real_gl_get_string == NULL) return NULL;

    const unsigned char *raw = g_real_gl_get_string(name);
    if (!db_disable_persistent_mapping() || raw == NULL) return raw;

    if (name == DB_GL_VERSION) {
        int major = 0;
        int minor = 0;
        if (sscanf((const char *) raw, "%d.%d", &major, &minor) == 2
                && (major > 4 || (major == 4 && minor > 3))) {
            pthread_mutex_lock(&g_gl_capability_mutex);
            snprintf(g_filtered_gl_version, sizeof(g_filtered_gl_version),
                     "4.3 DroidBridge Snapshot 4 compatibility (%s)",
                     (const char *) raw);
            pthread_mutex_unlock(&g_gl_capability_mutex);
            if (!g_logged_gl_version_filter) {
                g_logged_gl_version_filter = 1;
                db_log("OpenGL version capped for safe buffers original=%s exposed=4.3",
                       (const char *) raw);
            }
            return (const unsigned char *) g_filtered_gl_version;
        }
        return raw;
    }

    if (name == DB_GL_EXTENSIONS) {
        pthread_mutex_lock(&g_gl_capability_mutex);
        if (!g_filtered_gl_extensions_ready) {
            const char *source = (const char *) raw;
            size_t source_length = strlen(source);
            g_filtered_gl_extensions = (char *) malloc(source_length + 1);
            if (g_filtered_gl_extensions != NULL) {
                size_t output = 0;
                const char *cursor = source;
                while (*cursor != '\0') {
                    while (*cursor == ' ') cursor++;
                    const char *start = cursor;
                    while (*cursor != '\0' && *cursor != ' ') cursor++;
                    size_t length = (size_t) (cursor - start);
                    if (length == 0) continue;
                    bool blocked = (length == strlen(DB_BLOCKED_BUFFER_STORAGE_ARB)
                            && strncmp(start, DB_BLOCKED_BUFFER_STORAGE_ARB, length) == 0)
                            || (length == strlen(DB_BLOCKED_BUFFER_STORAGE_EXT)
                            && strncmp(start, DB_BLOCKED_BUFFER_STORAGE_EXT, length) == 0);
                    if (!blocked) {
                        if (output != 0) g_filtered_gl_extensions[output++] = ' ';
                        memcpy(g_filtered_gl_extensions + output, start, length);
                        output += length;
                    }
                }
                g_filtered_gl_extensions[output] = '\0';
            }
            g_filtered_gl_extensions_ready = 1;
        }
        const unsigned char *filtered = g_filtered_gl_extensions != NULL
                ? (const unsigned char *) g_filtered_gl_extensions : raw;
        pthread_mutex_unlock(&g_gl_capability_mutex);
        if (!g_logged_buffer_storage_filter) {
            g_logged_buffer_storage_filter = 1;
            db_log("persistent buffer capability filter active renderer=%s maxGL=4.3 blocked=%s,%s",
                   getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") != NULL
                           ? getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") : "unknown",
                   DB_BLOCKED_BUFFER_STORAGE_ARB,
                   DB_BLOCKED_BUFFER_STORAGE_EXT);
        }
        return filtered;
    }

    return raw;
}

static const unsigned char *db_gl_get_string_i_filter(unsigned int name,
                                                       unsigned int index) {
    if (g_real_gl_get_string_i == NULL) {
        g_real_gl_get_string_i = (db_gl_get_string_i_fn)
                db_lookup_wrapped_gl_proc("glGetStringi");
    }
    if (g_real_gl_get_string_i == NULL) return NULL;
    const unsigned char *raw = g_real_gl_get_string_i(name, index);
    if (!db_disable_persistent_mapping() || name != DB_GL_EXTENSIONS || raw == NULL) {
        return raw;
    }
    if (db_is_blocked_buffer_storage_extension((const char *) raw)) {
        if (!g_logged_buffer_storage_filter) {
            g_logged_buffer_storage_filter = 1;
            db_log("persistent buffer capability filter active renderer=%s maxGL=4.3 blocked=%s,%s",
                   getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") != NULL
                           ? getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") : "unknown",
                   DB_BLOCKED_BUFFER_STORAGE_ARB,
                   DB_BLOCKED_BUFFER_STORAGE_EXT);
        }
        return (const unsigned char *) DB_DISABLED_EXTENSION_NAME;
    }
    return raw;
}

static void db_gl_get_integerv_filter(unsigned int name, int *value) {
    if (g_real_gl_get_integerv == NULL) {
        g_real_gl_get_integerv = (db_gl_get_integerv_fn)
                db_lookup_wrapped_gl_proc("glGetIntegerv");
    }
    if (g_real_gl_get_integerv == NULL) return;
    g_real_gl_get_integerv(name, value);
    if (!db_disable_persistent_mapping() || value == NULL) return;

    if (name == DB_GL_MAJOR_VERSION && *value > 4) {
        *value = 4;
        if (!g_logged_gl_version_filter) {
            g_logged_gl_version_filter = 1;
            db_log("OpenGL integer version capped for safe buffers exposed=4.3");
        }
    } else if (name == DB_GL_MINOR_VERSION) {
        int major = 0;
        g_real_gl_get_integerv(DB_GL_MAJOR_VERSION, &major);
        if (major > 4 || (major == 4 && *value > 3)) {
            *value = 3;
            if (!g_logged_gl_version_filter) {
                g_logged_gl_version_filter = 1;
                db_log("OpenGL integer version capped for safe buffers exposed=4.3");
            }
        }
    }
}

static bool db_restore_wrapped_context(const char *reason) {
    if (!db_wrapped_context_guard_enabled()) return true;

    bool sdl_ready = true;
    if (g_wrapped_sdl_context != NULL && g_real_sdl_gl_make_current != NULL) {
        void *current_context = g_real_sdl_gl_get_current_context != NULL
                ? g_real_sdl_gl_get_current_context() : NULL;
        SDL_Window *current_window = g_real_sdl_gl_get_current_window != NULL
                ? g_real_sdl_gl_get_current_window() : NULL;
        if (current_context != g_wrapped_sdl_context
                || current_window != g_wrapped_sdl_window) {
            sdl_ready = g_real_sdl_gl_make_current(
                    g_wrapped_sdl_window, g_wrapped_sdl_context);
        }
    }

    int egl_ready = droidbridge_sdl3_ensure_current_gl_context(reason);
    if (!sdl_ready || !egl_ready) {
        db_log("%s context restore before %s sdl=%d egl=%d cachedWindow=%p cachedContext=%p currentWindow=%p currentContext=%p",
               db_wrapped_renderer_name(),
               reason != NULL ? reason : "unknown",
               sdl_ready ? 1 : 0, egl_ready,
               g_wrapped_sdl_window, g_wrapped_sdl_context,
               g_real_sdl_gl_get_current_window != NULL
                       ? g_real_sdl_gl_get_current_window() : NULL,
               g_real_sdl_gl_get_current_context != NULL
                       ? g_real_sdl_gl_get_current_context() : NULL);
    }
    return sdl_ready && egl_ready;
}

static bool db_glsl_ident_char(char c) {
    return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '_';
}

static int db_mobileglues_sanitize_shader_identifiers(char *source, size_t length) {
    if (source == NULL || length < 8u
            || !db_truthy_env("DROIDBRIDGE_MOBILEGLUES_SHADER_IDENTIFIER_FIX")) {
        return 0;
    }
    static const char needle[] = "_uniform";
    static const char replacement[] = "_db_uvar";
    int replacements = 0;
    for (size_t i = 0; i + 8u <= length; ++i) {
        if (memcmp(source + i, needle, 8u) != 0) continue;
        bool left_ok = i == 0u || !db_glsl_ident_char(source[i - 1u]);
        bool right_ok = i + 8u == length || !db_glsl_ident_char(source[i + 8u]);
        if (!left_ok || !right_ok) continue;
        memcpy(source + i, replacement, 8u);
        replacements++;
        i += 7u;
    }
    return replacements;
}

static unsigned int db_wrapped_gl_error(void) {
    if (g_real_wrapped_gl_get_error == NULL) {
        g_real_wrapped_gl_get_error = (db_gl_get_error_fn)
                db_lookup_wrapped_gl_proc("glGetError");
    }
    return g_real_wrapped_gl_get_error != NULL
            ? g_real_wrapped_gl_get_error() : 0u;
}

static unsigned int db_wrapped_gl_create_shader_guard(unsigned int type) {
    (void) db_restore_wrapped_context("glCreateShader");
    unsigned int shader = g_real_wrapped_gl_create_shader != NULL
            ? g_real_wrapped_gl_create_shader(type) : 0u;
    if (!g_logged_wrapped_gl_create_shader || shader == 0u) {
        g_logged_wrapped_gl_create_shader = 1;
        unsigned int error = shader == 0u ? db_wrapped_gl_error() : 0u;
        db_log("%s glCreateShader type=0x%x result=%u currentContext=%p error=0x%x",
               db_wrapped_renderer_name(), type, shader,
               g_real_sdl_gl_get_current_context != NULL
                       ? g_real_sdl_gl_get_current_context() : NULL,
               error);
    }
    return shader;
}

static void db_wrapped_gl_shader_source_guard(
        unsigned int shader, int count, const char *const *strings, const int *lengths) {
    (void) db_restore_wrapped_context("glShaderSource");
    if (g_real_wrapped_gl_shader_source != NULL) {
        g_real_wrapped_gl_shader_source(shader, count, strings, lengths);
    }
}

static void db_wrapped_gl_compile_shader_guard(unsigned int shader) {
    (void) db_restore_wrapped_context("glCompileShader");
    if (g_real_wrapped_gl_compile_shader != NULL) {
        g_real_wrapped_gl_compile_shader(shader);
    }
}

static unsigned int db_wrapped_gl_create_program_guard(void) {
    (void) db_restore_wrapped_context("glCreateProgram");
    unsigned int program = g_real_wrapped_gl_create_program != NULL
            ? g_real_wrapped_gl_create_program() : 0u;
    if (!g_logged_wrapped_gl_create_program || program == 0u) {
        g_logged_wrapped_gl_create_program = 1;
        unsigned int error = program == 0u ? db_wrapped_gl_error() : 0u;
        db_log("%s glCreateProgram result=%u currentContext=%p error=0x%x",
               db_wrapped_renderer_name(), program,
               g_real_sdl_gl_get_current_context != NULL
                       ? g_real_sdl_gl_get_current_context() : NULL,
               error);
    }
    return program;
}

static void db_mobileglues_shader_source_normalized(
        unsigned int shader, int count, const char *const *strings, const int *lengths) {
    (void) db_restore_wrapped_context("glShaderSource");
    db_gl_shader_source_fn real = g_real_mobileglues_shader_source;
    if (real == NULL || count <= 0 || strings == NULL) {
        if (real != NULL) real(shader, count, strings, lengths);
        return;
    }

    size_t total = 0;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t length = lengths != NULL && lengths[i] >= 0
                ? (size_t) lengths[i] : strlen(strings[i]);
        if (length > (64u * 1024u * 1024u) || total > (64u * 1024u * 1024u) - length) {
            real(shader, count, strings, lengths);
            return;
        }
        total += length;
    }

    char *combined = (char *) malloc(total + 1u);
    if (combined == NULL) {
        real(shader, count, strings, lengths);
        return;
    }
    size_t output = 0;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t length = lengths != NULL && lengths[i] >= 0
                ? (size_t) lengths[i] : strlen(strings[i]);
        memcpy(combined + output, strings[i], length);
        output += length;
    }
    combined[output] = '\0';
    int renamed = db_mobileglues_sanitize_shader_identifiers(combined, output);
    if (renamed > 0) {
        db_log("Snapshot 8 MobileGlues shader sanitized shader=%u renamed _uniform tokens=%d",
               shader, renamed);
    }
    const char *one = combined;
    /* MobileGlues corrupts some multi-string/explicit-length shader uploads.
     * Give it one owned, NUL-terminated source string after Snapshot 8 sanitizing. */
    real(shader, 1, &one, NULL);
    if (!g_logged_mobileglues_shader_normalization) {
        g_logged_mobileglues_shader_normalization = 1;
        db_log("MobileGlues shader source normalized shader=%u parts=%d bytes=%zu",
               shader, count, output);
    }
    free(combined);
}


static bool db_mobileglues_framebuffer_srgb_workaround_enabled(void) {
    return db_is_mobileglues_renderer()
            && db_truthy_env("DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB");
}

static void db_mobileglues_gl_enable_filter(unsigned int cap) {
    if (cap == DB_GL_FRAMEBUFFER_SRGB
            && db_mobileglues_framebuffer_srgb_workaround_enabled()) {
        if (g_real_wrapped_gl_disable == NULL) {
            g_real_wrapped_gl_disable = (db_gl_disable_fn)
                    db_lookup_wrapped_gl_proc("glDisable");
        }
        if (g_real_wrapped_gl_disable != NULL) {
            g_real_wrapped_gl_disable(cap);
        }
        if (!g_logged_mobileglues_framebuffer_srgb) {
            g_logged_mobileglues_framebuffer_srgb = 1;
            db_log("MobileGlues GL_FRAMEBUFFER_SRGB enable suppressed for Snapshot 4 color output");
        }
        return;
    }
    if (g_real_wrapped_gl_enable != NULL) {
        g_real_wrapped_gl_enable(cap);
    }
}

static void db_mobileglues_gl_disable_filter(unsigned int cap) {
    if (g_real_wrapped_gl_disable != NULL) {
        g_real_wrapped_gl_disable(cap);
    }
}

static unsigned char db_mobileglues_gl_is_enabled_filter(unsigned int cap) {
    if (cap == DB_GL_FRAMEBUFFER_SRGB
            && db_mobileglues_framebuffer_srgb_workaround_enabled()) {
        return 0u;
    }
    return g_real_wrapped_gl_is_enabled != NULL
            ? g_real_wrapped_gl_is_enabled(cap) : 0u;
}

static void *db_sdl3_gl_get_proc_address(const char *name) {
    if (db_sdl3_nativeglfw_gl_owner_enabled()
            && name != NULL && name[0] == 'g' && name[1] == 'l') {
        void *canonical = droidbridge_resolve_opengl_proxy_symbol(name);
        if (canonical != NULL) {
            static int logged_nativeglfw_proc = 0;
            if (!logged_nativeglfw_proc) {
                logged_nativeglfw_proc = 1;
                db_log("26.3+ Freedreno SDL_GL_GetProcAddress uses NativeGLFW/LWJGL Mesa provider firstProc=%s proc=%p",
                       name, canonical);
            }
            return canonical;
        }
    }

    /* Keep SDL and LWJGL on the same 26.3+ direct-Freedreno presentation
     * trampoline. The LWJGL loader receives this symbol through its GLX->EGL
     * adapter; return that exact same address if SDL asks for it too. */
    if (name != NULL
            && strcmp(name, "glBlitNamedFramebuffer") == 0
            && db_direct_mesa_single_loader_enabled()) {
        void *compat = droidbridge_resolve_opengl_proxy_symbol(name);
        if (compat != NULL) {
            static int logged_freedreno_named_blit = 0;
            if (!logged_freedreno_named_blit) {
                logged_freedreno_named_blit = 1;
                db_log("26.3+ direct Freedreno SDL glBlitNamedFramebuffer routed to DroidBridge compat proc=%p",
                       compat);
            }
            return compat;
        }
    }

    /*
     * Legacy Vulkan Zink normally lets SDL resolve GL entry points through its
     * Android EGL path while LWJGL loads Mesa/OSMesa directly. RenderPearl
     * 26.2+ compares those two addresses for glGetError and rejects OpenGL when
     * they differ. Reuse the exact already-loaded LWJGL provider for that one
     * identity probe; leave every other SDL GL proc and all context lifecycle
     * untouched.
     */
    if (name != NULL && strcmp(name, "glGetError") == 0
            && db_is_vulkan_zink_renderer()) {
        void *canonical = droidbridge_resolve_opengl_proxy_symbol(name);
        if (canonical != NULL) {
            static int logged_zink_glgeterror_identity = 0;
            if (!logged_zink_glgeterror_identity) {
                logged_zink_glgeterror_identity = 1;
                db_log("Vulkan Zink canonical SDL glGetError proc=%p source=LWJGL-OpenGL-proxy",
                       canonical);
            }
            return canonical;
        }
        static int logged_zink_glgeterror_missing = 0;
        if (!logged_zink_glgeterror_missing) {
            logged_zink_glgeterror_missing = 1;
            db_log("Vulkan Zink canonical SDL glGetError provider not ready; falling back to SDL");
        }
    }

    /*
     * SimpleFPEWrapper must have one canonical GL proc-address path on
     * Minecraft 26.3/RenderPearl. RenderPearl compares the function pointer
     * loaded by its OpenGL library bootstrap with SDL_GL_GetProcAddress().
     *
     * The DroidBridge EGL shim already owns the SimpleFPE -> MobileGlues GL
     * routing and returns the exact same wrapper trampoline used by the direct
     * loader. Do not independently resolve the symbol from libmobileglues.so
     * here or RenderPearl sees (for example) two different glGetError pointers
     * and incorrectly falls back to Vulkan even when OpenGL was selected.
     *
     * Reusing the EGL shim also avoids dlopening a second SimpleFPE image from
     * this bridge/linker namespace. EGL/context lifecycle remains owned by the
     * existing MobileGlues/SDL path; this is GL procedure lookup only.
     */
    if (name != NULL && name[0] == 'g' && name[1] == 'l'
            && db_sdl3_wrapped_opengl_enabled()
            && db_truthy_env("DROIDBRIDGE_SIMPLEFPE_ACTIVE")) {
        db_resolve_real_dlsym();
        void *egl_provider = db_load_sdl3_egl_provider();
        if (egl_provider != NULL && g_real_dlsym != NULL) {
            typedef void *(*db_egl_get_proc_address_fn)(const char *);
            db_egl_get_proc_address_fn canonical_get_proc =
                    (db_egl_get_proc_address_fn)g_real_dlsym(
                            egl_provider, "eglGetProcAddress");
            if (canonical_get_proc != NULL) {
                void *canonical = canonical_get_proc(name);
                if (canonical != NULL) {
                    static int logged_simplefpe_canonical = 0;
                    if (!logged_simplefpe_canonical) {
                        logged_simplefpe_canonical = 1;
                        db_log("SimpleFPE canonical SDL_GL_GetProcAddress active firstProc=%s proc=%p eglProvider=%p",
                               name, canonical, egl_provider);
                    }
                    return canonical;
                }
            }
        }
    }

    if (name != NULL && name[0] == 'g' && name[1] == 'l'
            && db_sdl3_wrapped_opengl_enabled()) {
        if (db_disable_persistent_mapping()
                && db_is_blocked_buffer_storage_proc(name)) {
            db_log("blocked SDL_GL_GetProcAddress buffer-storage proc=%s", name);
            return NULL;
        }
        void *wrapper = db_load_sdl3_gl_wrapper();
        void *symbol = db_lookup_wrapped_gl_proc(name);
        if (symbol != NULL) {
            if (db_mobileglues_framebuffer_srgb_workaround_enabled()) {
                if (strcmp(name, "glEnable") == 0) {
                    g_real_wrapped_gl_enable = (db_gl_enable_fn)symbol;
                    if (g_real_wrapped_gl_disable == NULL) {
                        g_real_wrapped_gl_disable = (db_gl_disable_fn)
                                db_lookup_wrapped_gl_proc("glDisable");
                    }
                    return (void *)db_mobileglues_gl_enable_filter;
                }
                if (strcmp(name, "glDisable") == 0) {
                    g_real_wrapped_gl_disable = (db_gl_disable_fn)symbol;
                    return (void *)db_mobileglues_gl_disable_filter;
                }
                if (strcmp(name, "glIsEnabled") == 0) {
                    g_real_wrapped_gl_is_enabled = (db_gl_is_enabled_fn)symbol;
                    return (void *)db_mobileglues_gl_is_enabled_filter;
                }
            }
            if (db_disable_persistent_mapping()) {
                if (strcmp(name, "glGetString") == 0) {
                    g_real_gl_get_string = (db_gl_get_string_fn) symbol;
                    return (void *) db_gl_get_string_filter;
                }
                if (strcmp(name, "glGetStringi") == 0) {
                    g_real_gl_get_string_i = (db_gl_get_string_i_fn) symbol;
                    return (void *) db_gl_get_string_i_filter;
                }
                if (strcmp(name, "glGetIntegerv") == 0) {
                    g_real_gl_get_integerv = (db_gl_get_integerv_fn) symbol;
                    return (void *) db_gl_get_integerv_filter;
                }
            }
            if (db_wrapped_context_guard_enabled()) {
                if (strcmp(name, "glCreateShader") == 0) {
                    g_real_wrapped_gl_create_shader = (db_gl_create_shader_fn) symbol;
                    return (void *) db_wrapped_gl_create_shader_guard;
                }
                if (strcmp(name, "glShaderSource") == 0) {
                    if (db_is_mobileglues_renderer()) {
                        g_real_mobileglues_shader_source = (db_gl_shader_source_fn) symbol;
                        return (void *) db_mobileglues_shader_source_normalized;
                    }
                    g_real_wrapped_gl_shader_source = (db_gl_shader_source_fn) symbol;
                    return (void *) db_wrapped_gl_shader_source_guard;
                }
                if (strcmp(name, "glCompileShader") == 0) {
                    g_real_wrapped_gl_compile_shader = (db_gl_compile_shader_fn) symbol;
                    return (void *) db_wrapped_gl_compile_shader_guard;
                }
                if (strcmp(name, "glCreateProgram") == 0) {
                    g_real_wrapped_gl_create_program = (db_gl_create_program_fn) symbol;
                    return (void *) db_wrapped_gl_create_program_guard;
                }
            }
            if (!g_logged_gl_proc_translation) {
                g_logged_gl_proc_translation = 1;
                db_log("SDL_GL_GetProcAddress routed to wrapped OpenGL firstProc=%s provider=%p",
                       name, wrapper);
            }
            return symbol;
        }
    }
    return g_real_sdl_gl_get_proc_address != NULL
            ? g_real_sdl_gl_get_proc_address(name) : NULL;
}

/*
 * SDL 3.2+ rejects an sRGB-capable OpenGL window before context creation when
 * the selected EGL provider does not advertise EGL_KHR_gl_colorspace. Current
 * MobileGL exports a complete EGL API but omits that extension from its EGL
 * extension string, so Minecraft 26.2/26.3 RenderPearl abandons OpenGL and
 * falls back to Vulkan before Espryt ever receives a GL context.
 *
 * Keep this workaround entirely inside SDL's private EGL dispatch table:
 * advertise the capability to SDL, then remove SDL's EGL_GL_COLORSPACE_KHR
 * window-surface attribute before calling MobileGL. The Android window stays
 * in the normal sRGB dataspace and MobileGL still owns the EGL lifecycle.
 */
static const char *db_mobilegl_egl_query_string(void *display, int name) {
    db_egl_query_string_fn real = g_real_mobilegl_egl_query_string;
    const char *value = real != NULL ? real(display, name) : NULL;
    if (name != DB_EGL_EXTENSIONS || value == NULL
            || strstr(value, "EGL_KHR_gl_colorspace") != NULL) {
        return value;
    }

    pthread_mutex_lock(&g_mobilegl_egl_mutex);
    if (g_mobilegl_egl_extensions == NULL) {
        static const char suffix[] = " EGL_KHR_gl_colorspace";
        size_t base = strlen(value);
        size_t extra = sizeof(suffix);
        char *combined = (char *) malloc(base + extra);
        if (combined != NULL) {
            memcpy(combined, value, base);
            memcpy(combined + base, suffix, extra);
            g_mobilegl_egl_extensions = combined;
        }
    }
    const char *result = g_mobilegl_egl_extensions != NULL
            ? g_mobilegl_egl_extensions : value;
    if (!g_logged_mobilegl_egl_colorspace_advertise) {
        g_logged_mobilegl_egl_colorspace_advertise = 1;
        db_log("MobileGL SDL EGL compatibility advertising EGL_KHR_gl_colorspace to SDL only");
    }
    pthread_mutex_unlock(&g_mobilegl_egl_mutex);
    return result;
}

static bool db_mobilegl_strip_egl_colorspace_ints(
        const int *attributes, int *filtered, size_t capacity) {
    if (attributes == NULL || filtered == NULL || capacity < 1u) return false;
    size_t out = 0u;
    bool stripped = false;
    for (size_t in = 0u; in + 1u < capacity; ) {
        int key = attributes[in++];
        if (key == DB_EGL_NONE) {
            filtered[out++] = DB_EGL_NONE;
            return stripped;
        }
        int value = attributes[in++];
        if (key == DB_EGL_GL_COLORSPACE_KHR) {
            stripped = true;
            continue;
        }
        if (out + 2u >= capacity) return false;
        filtered[out++] = key;
        filtered[out++] = value;
    }
    return false;
}

static bool db_mobilegl_strip_egl_colorspace_attribs(
        const intptr_t *attributes, intptr_t *filtered, size_t capacity) {
    if (attributes == NULL || filtered == NULL || capacity < 1u) return false;
    size_t out = 0u;
    bool stripped = false;
    for (size_t in = 0u; in + 1u < capacity; ) {
        intptr_t key = attributes[in++];
        if (key == (intptr_t) DB_EGL_NONE) {
            filtered[out++] = (intptr_t) DB_EGL_NONE;
            return stripped;
        }
        intptr_t value = attributes[in++];
        if (key == (intptr_t) DB_EGL_GL_COLORSPACE_KHR) {
            stripped = true;
            continue;
        }
        if (out + 2u >= capacity) return false;
        filtered[out++] = key;
        filtered[out++] = value;
    }
    return false;
}

static void db_mobilegl_log_colorspace_strip_once(const char *entrypoint) {
    pthread_mutex_lock(&g_mobilegl_egl_mutex);
    if (!g_logged_mobilegl_egl_colorspace_strip) {
        g_logged_mobilegl_egl_colorspace_strip = 1;
        db_log("MobileGL SDL EGL compatibility stripped EGL_GL_COLORSPACE_KHR entrypoint=%s",
               entrypoint != NULL ? entrypoint : "unknown");
    }
    pthread_mutex_unlock(&g_mobilegl_egl_mutex);
}

static void *db_mobilegl_egl_create_window_surface(
        void *display, void *config, void *window, const int *attributes) {
    db_egl_create_window_surface_fn real = g_real_mobilegl_egl_create_window_surface;
    if (real == NULL) return NULL;
    if (attributes == NULL) return real(display, config, window, NULL);

    int filtered[64];
    if (db_mobilegl_strip_egl_colorspace_ints(
                attributes, filtered, sizeof(filtered) / sizeof(filtered[0]))) {
        db_mobilegl_log_colorspace_strip_once("eglCreateWindowSurface");
        return real(display, config, window, filtered);
    }
    return real(display, config, window, attributes);
}

static void *db_mobilegl_egl_create_platform_window_surface(
        void *display, void *config, void *window, const intptr_t *attributes) {
    db_egl_create_platform_window_surface_fn real =
            g_real_mobilegl_egl_create_platform_window_surface;
    if (real == NULL) return NULL;
    if (attributes == NULL) return real(display, config, window, NULL);

    intptr_t filtered[64];
    if (db_mobilegl_strip_egl_colorspace_attribs(
                attributes, filtered, sizeof(filtered) / sizeof(filtered[0]))) {
        db_mobilegl_log_colorspace_strip_once("eglCreatePlatformWindowSurface");
        return real(display, config, window, filtered);
    }
    return real(display, config, window, attributes);
}

/* Replacement for libSDL3.so's imported dlsym. It only redirects EGL symbol
 * lookups while wrapped OpenGL compatibility is active; all other lookups use
 * the original Android linker function unchanged. */
static void *db_sdl3_dlsym(void *handle, const char *name) {
    db_resolve_real_dlsym();
    if (name != NULL && strncmp(name, "egl", 3) == 0
            && db_sdl3_wrapped_opengl_enabled()) {
        void *provider = db_load_sdl3_egl_provider();
        void *symbol = (provider != NULL && g_real_dlsym != NULL)
                ? g_real_dlsym(provider, name) : NULL;
        if (symbol != NULL) {
            if (db_is_mobilegl_renderer()) {
                if (strcmp(name, "eglQueryString") == 0) {
                    g_real_mobilegl_egl_query_string = (db_egl_query_string_fn) symbol;
                    symbol = (void *) db_mobilegl_egl_query_string;
                } else if (strcmp(name, "eglCreateWindowSurface") == 0) {
                    g_real_mobilegl_egl_create_window_surface =
                            (db_egl_create_window_surface_fn) symbol;
                    symbol = (void *) db_mobilegl_egl_create_window_surface;
                } else if (strcmp(name, "eglCreatePlatformWindowSurface") == 0) {
                    g_real_mobilegl_egl_create_platform_window_surface =
                            (db_egl_create_platform_window_surface_fn) symbol;
                    symbol = (void *) db_mobilegl_egl_create_platform_window_surface;
                }
            }
            if (!g_logged_egl_symbol_translation) {
                g_logged_egl_symbol_translation = 1;
                db_log("SDL internal EGL lookup redirected firstSymbol=%s provider=%p",
                       name, provider);
            }
            return symbol;
        }
    }
    return g_real_dlsym != NULL ? g_real_dlsym(handle, name) : NULL;
}

static void resolve_real_from_surface(void) {
    if (g_real_from_surface != NULL) return;
    void *android = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (android != NULL) {
        g_real_from_surface =
                (ANativeWindow *(*)(JNIEnv *, jobject)) dlsym(android,
                        "ANativeWindow_fromSurface");
    }
}

static ANativeWindow *window_from_process_environment(void) {
    const char *text = getenv(DB_SDL3_PTR_ENV);
    if (text == NULL || text[0] == '\0') return NULL;

    char *end = NULL;
    errno = 0;
    uintptr_t value = (uintptr_t) strtoull(text, &end, 16);
    if (errno != 0 || end == text || value == 0) return NULL;
    return (ANativeWindow *) value;
}

/* Replacement used only by libSDL3.so's imported ANativeWindow_fromSurface. */
static ANativeWindow *db_sdl3_from_surface(JNIEnv *env, jobject surface) {
    if (__atomic_load_n(&g_bridge_enabled, __ATOMIC_ACQUIRE)) {
        ANativeWindow *published = window_from_process_environment();
        if (published != NULL) {
            ANativeWindow_acquire(published);
            db_log("SDL requested native window; returning published window=%p", published);
            return published;
        }
    }

    resolve_real_from_surface();
    return g_real_from_surface != NULL ? g_real_from_surface(env, surface) : NULL;
}

static int protection_for_address(uintptr_t address) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == NULL) return PROT_READ;

    char line[512];
    int protection = PROT_READ;
    while (fgets(line, sizeof(line), maps) != NULL) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char perms[5] = {0};
        if (sscanf(line, "%llx-%llx %4s", &start, &end, perms) != 3) continue;
        if (address < (uintptr_t) start || address >= (uintptr_t) end) continue;
        protection = 0;
        if (perms[0] == 'r') protection |= PROT_READ;
        if (perms[1] == 'w') protection |= PROT_WRITE;
        if (perms[2] == 'x') protection |= PROT_EXEC;
        break;
    }
    fclose(maps);
    return protection;
}

static uintptr_t loaded_address(uintptr_t base, uintptr_t image_end, uintptr_t raw) {
    if (raw >= base && raw < image_end) return raw;
    return base + raw;
}


static uint32_t db_elf_hash(const char *name) {
    uint32_t hash = 0;
    uint32_t high;
    while (*name != '\0') {
        hash = (hash << 4) + (uint8_t) *name++;
        high = hash & 0xf0000000u;
        if (high != 0) hash ^= high >> 24;
        hash &= ~high;
    }
    return hash;
}

static uint32_t db_gnu_hash(const char *name) {
    uint32_t hash = 5381u;
    for (const unsigned char *cursor = (const unsigned char *) name;
         *cursor != '\0'; ++cursor) {
        hash = hash * 33u + *cursor;
    }
    return hash;
}

static void *db_symbol_runtime_address(
        uintptr_t base,
        uintptr_t image_end,
        const ElfW(Sym) *symbol
) {
    if (symbol == NULL || symbol->st_shndx == SHN_UNDEF || symbol->st_value == 0) {
        return NULL;
    }
    return (void *) loaded_address(base, image_end, (uintptr_t) symbol->st_value);
}

static void *db_lookup_sysv_symbol(
        uintptr_t base,
        uintptr_t image_end,
        const ElfW(Sym) *symbols,
        const char *strings,
        const uint32_t *hash_table,
        const char *wanted
) {
    if (symbols == NULL || strings == NULL || hash_table == NULL || wanted == NULL) {
        return NULL;
    }
    uint32_t bucket_count = hash_table[0];
    uint32_t chain_count = hash_table[1];
    if (bucket_count == 0 || chain_count == 0) return NULL;
    const uint32_t *buckets = hash_table + 2;
    const uint32_t *chains = buckets + bucket_count;
    uint32_t index = buckets[db_elf_hash(wanted) % bucket_count];
    while (index != STN_UNDEF && index < chain_count) {
        const ElfW(Sym) *symbol = &symbols[index];
        const char *name = strings + symbol->st_name;
        if (strcmp(name, wanted) == 0) {
            return db_symbol_runtime_address(base, image_end, symbol);
        }
        index = chains[index];
    }
    return NULL;
}

static void *db_lookup_gnu_symbol(
        uintptr_t base,
        uintptr_t image_end,
        const ElfW(Sym) *symbols,
        const char *strings,
        const uint32_t *gnu_hash_table,
        const char *wanted
) {
    if (symbols == NULL || strings == NULL || gnu_hash_table == NULL || wanted == NULL) {
        return NULL;
    }

    const uint32_t bucket_count = gnu_hash_table[0];
    const uint32_t symbol_offset = gnu_hash_table[1];
    const uint32_t bloom_size = gnu_hash_table[2];
    const uint32_t bloom_shift = gnu_hash_table[3];
    if (bucket_count == 0 || bloom_size == 0) return NULL;

    const ElfW(Addr) *bloom = (const ElfW(Addr) *) (gnu_hash_table + 4);
    const uint32_t *buckets = (const uint32_t *) (bloom + bloom_size);
    const uint32_t *chains = buckets + bucket_count;
    const uint32_t hash = db_gnu_hash(wanted);
    const uint32_t bits = (uint32_t) (sizeof(ElfW(Addr)) * 8u);
    const ElfW(Addr) bloom_word = bloom[(hash / bits) % bloom_size];
    const ElfW(Addr) bloom_mask =
            ((ElfW(Addr)) 1u << (hash % bits))
            | ((ElfW(Addr)) 1u << ((hash >> bloom_shift) % bits));
    if ((bloom_word & bloom_mask) != bloom_mask) return NULL;

    uint32_t index = buckets[hash % bucket_count];
    if (index < symbol_offset) return NULL;

    for (;;) {
        uint32_t chain_hash = chains[index - symbol_offset];
        if ((chain_hash | 1u) == (hash | 1u)) {
            const ElfW(Sym) *symbol = &symbols[index];
            const char *name = strings + symbol->st_name;
            if (strcmp(name, wanted) == 0) {
                return db_symbol_runtime_address(base, image_end, symbol);
            }
        }
        if ((chain_hash & 1u) != 0) break;
        ++index;
    }
    return NULL;
}

static void *db_lookup_loaded_symbol(
        uintptr_t base,
        uintptr_t image_end,
        const ElfW(Sym) *symbols,
        const char *strings,
        const uint32_t *sysv_hash,
        const uint32_t *gnu_hash,
        const char *wanted
) {
    void *address = db_lookup_gnu_symbol(
            base, image_end, symbols, strings, gnu_hash, wanted);
    if (address != NULL) return address;
    return db_lookup_sysv_symbol(
            base, image_end, symbols, strings, sysv_hash, wanted);
}

struct sdl_api_context {
    int libraries_seen;
    int resolved;
};

static int resolve_loaded_sdl3_api(struct dl_phdr_info *info, size_t size, void *opaque) {
    (void) size;
    struct sdl_api_context *context = (struct sdl_api_context *) opaque;
    const char *name = info->dlpi_name;
    if (name == NULL || strstr(name, "libSDL3.so") == NULL) return 0;
    context->libraries_seen++;

    uintptr_t base = (uintptr_t) info->dlpi_addr;
    uintptr_t image_end = base;
    const ElfW(Dyn) *dynamic = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *phdr = &info->dlpi_phdr[i];
        uintptr_t end = base + phdr->p_vaddr + phdr->p_memsz;
        if (end > image_end) image_end = end;
        if (phdr->p_type == PT_DYNAMIC) {
            dynamic = (const ElfW(Dyn) *) (base + phdr->p_vaddr);
        }
    }
    if (dynamic == NULL) return 0;

    const ElfW(Sym) *symbols = NULL;
    const char *strings = NULL;
    const uint32_t *sysv_hash = NULL;
    const uint32_t *gnu_hash = NULL;
    for (const ElfW(Dyn) *entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symbols = (const ElfW(Sym) *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strings = (const char *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_HASH:
                sysv_hash = (const uint32_t *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_GNU_HASH:
                gnu_hash = (const uint32_t *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            default:
                break;
        }
    }
    if (symbols == NULL || strings == NULL) return 0;

#define DB_RESOLVE_SDL_API(field, type, symbol_name) \
    do { \
        if ((field) == NULL) { \
            (field) = (type) db_lookup_loaded_symbol( \
                    base, image_end, symbols, strings, sysv_hash, gnu_hash, symbol_name); \
        } \
    } while (0)

    DB_RESOLVE_SDL_API(g_sdl_get_mouse_state, db_sdl_get_mouse_state_fn,
                       "SDL_GetMouseState");
    DB_RESOLVE_SDL_API(g_sdl_get_mouse_focus, db_sdl_get_mouse_focus_fn,
                       "SDL_GetMouseFocus");
    DB_RESOLVE_SDL_API(g_sdl_get_keyboard_focus, db_sdl_get_keyboard_focus_fn,
                       "SDL_GetKeyboardFocus");
    DB_RESOLVE_SDL_API(g_sdl_get_window_size, db_sdl_get_window_size_fn,
                       "SDL_GetWindowSize");
    DB_RESOLVE_SDL_API(g_sdl_get_window_size_in_pixels, db_sdl_get_window_size_fn,
                       "SDL_GetWindowSizeInPixels");
    DB_RESOLVE_SDL_API(g_sdl_get_window_display_scale, db_sdl_get_window_float_fn,
                       "SDL_GetWindowDisplayScale");
    DB_RESOLVE_SDL_API(g_sdl_get_window_pixel_density, db_sdl_get_window_float_fn,
                       "SDL_GetWindowPixelDensity");
    DB_RESOLVE_SDL_API(g_sdl_warp_mouse_in_window, db_sdl_warp_mouse_in_window_fn,
                       "SDL_WarpMouseInWindow");
    DB_RESOLVE_SDL_API(g_sdl_set_hint, db_sdl_set_hint_fn,
                       "SDL_SetHint");
    DB_RESOLVE_SDL_API(g_sdl_set_hint_with_priority,
                       db_sdl_set_hint_with_priority_fn,
                       "SDL_SetHintWithPriority");
    DB_RESOLVE_SDL_API(g_sdl_get_hint, db_sdl_get_hint_fn,
                       "SDL_GetHint");
#undef DB_RESOLVE_SDL_API

    if (g_sdl_get_mouse_state != NULL
            && g_sdl_get_window_size != NULL
            && (g_sdl_get_mouse_focus != NULL || g_sdl_get_keyboard_focus != NULL)) {
        context->resolved = 1;
        return 1;
    }
    return 0;
}

static bool ensure_sdl_mouse_api(void) {
    if (g_sdl_get_mouse_state != NULL
            && g_sdl_get_window_size != NULL
            && (g_sdl_get_mouse_focus != NULL || g_sdl_get_keyboard_focus != NULL)) {
        return true;
    }

    pthread_mutex_lock(&g_sdl_api_mutex);
    if (g_sdl_get_mouse_state == NULL) {
        g_sdl_get_mouse_state = (db_sdl_get_mouse_state_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetMouseState");
    }
    if (g_sdl_get_mouse_focus == NULL) {
        g_sdl_get_mouse_focus = (db_sdl_get_mouse_focus_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetMouseFocus");
    }
    if (g_sdl_get_keyboard_focus == NULL) {
        g_sdl_get_keyboard_focus = (db_sdl_get_keyboard_focus_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetKeyboardFocus");
    }
    if (g_sdl_get_window_size == NULL) {
        g_sdl_get_window_size = (db_sdl_get_window_size_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetWindowSize");
    }
    if (g_sdl_get_window_size_in_pixels == NULL) {
        g_sdl_get_window_size_in_pixels = (db_sdl_get_window_size_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetWindowSizeInPixels");
    }
    if (g_sdl_get_window_display_scale == NULL) {
        g_sdl_get_window_display_scale = (db_sdl_get_window_float_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetWindowDisplayScale");
    }
    if (g_sdl_get_window_pixel_density == NULL) {
        g_sdl_get_window_pixel_density = (db_sdl_get_window_float_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetWindowPixelDensity");
    }
    if (g_sdl_warp_mouse_in_window == NULL) {
        g_sdl_warp_mouse_in_window = (db_sdl_warp_mouse_in_window_fn)
                dlsym(RTLD_DEFAULT, "SDL_WarpMouseInWindow");
    }
    if (g_sdl_set_hint == NULL) {
        g_sdl_set_hint = (db_sdl_set_hint_fn)
                dlsym(RTLD_DEFAULT, "SDL_SetHint");
    }
    if (g_sdl_set_hint_with_priority == NULL) {
        g_sdl_set_hint_with_priority = (db_sdl_set_hint_with_priority_fn)
                dlsym(RTLD_DEFAULT, "SDL_SetHintWithPriority");
    }
    if (g_sdl_get_hint == NULL) {
        g_sdl_get_hint = (db_sdl_get_hint_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetHint");
    }

    struct sdl_api_context context = {0, 0};
    if (g_sdl_get_mouse_state == NULL
            || g_sdl_get_window_size == NULL
            || (g_sdl_get_mouse_focus == NULL && g_sdl_get_keyboard_focus == NULL)) {
        dl_iterate_phdr(resolve_loaded_sdl3_api, &context);
    }
    bool ready = g_sdl_get_mouse_state != NULL
            && g_sdl_get_window_size != NULL
            && (g_sdl_get_mouse_focus != NULL || g_sdl_get_keyboard_focus != NULL);
    pthread_mutex_unlock(&g_sdl_api_mutex);
    return ready;
}

static bool ensure_sdl_hint_api(void) {
    if (g_sdl_set_hint_with_priority != NULL || g_sdl_set_hint != NULL) {
        return true;
    }

    pthread_mutex_lock(&g_sdl_api_mutex);
    if (g_sdl_set_hint == NULL) {
        g_sdl_set_hint = (db_sdl_set_hint_fn)
                dlsym(RTLD_DEFAULT, "SDL_SetHint");
    }
    if (g_sdl_set_hint_with_priority == NULL) {
        g_sdl_set_hint_with_priority = (db_sdl_set_hint_with_priority_fn)
                dlsym(RTLD_DEFAULT, "SDL_SetHintWithPriority");
    }
    if (g_sdl_get_hint == NULL) {
        g_sdl_get_hint = (db_sdl_get_hint_fn)
                dlsym(RTLD_DEFAULT, "SDL_GetHint");
    }

    if (g_sdl_set_hint_with_priority == NULL && g_sdl_set_hint == NULL) {
        struct sdl_api_context context = {0, 0};
        dl_iterate_phdr(resolve_loaded_sdl3_api, &context);
    }

    bool ready = g_sdl_set_hint_with_priority != NULL || g_sdl_set_hint != NULL;
    pthread_mutex_unlock(&g_sdl_api_mutex);
    return ready;
}

static bool set_sdl_hint_override(const char *name, const char *value) {
    /* SDL_HINT_OVERRIDE is the highest SDL_HintPriority value. */
    const int SDL_HINT_OVERRIDE_VALUE = 2;
    if (g_sdl_set_hint_with_priority != NULL) {
        return g_sdl_set_hint_with_priority(
                name, value, SDL_HINT_OVERRIDE_VALUE);
    }
    return g_sdl_set_hint != NULL && g_sdl_set_hint(name, value);
}

/*
 * SDL can be mapped more than once when ART loads its Android frontend and the
 * embedded OpenJDK later loads Minecraft's SDL copy through another linker
 * namespace. Caching the first SDL_SetHint address only configures one mapping.
 * Walk every loaded libSDL3 image and apply the provider hints to each image's
 * own SDL global state before Minecraft creates its window.
 */
struct sdl_hint_apply_context {
    const char *egl_path;
    const char *gl_path;
    const char *reason;
    int libraries_seen;
    int libraries_configured;
};

static int apply_open_gl_hints_to_loaded_sdl3(
        struct dl_phdr_info *info,
        size_t size,
        void *opaque
) {
    (void) size;
    struct sdl_hint_apply_context *context =
            (struct sdl_hint_apply_context *) opaque;
    const char *library_name = info->dlpi_name;
    if (library_name == NULL || strstr(library_name, "libSDL3.so") == NULL) return 0;
    context->libraries_seen++;

    uintptr_t base = (uintptr_t) info->dlpi_addr;
    uintptr_t image_end = base;
    const ElfW(Dyn) *dynamic = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *phdr = &info->dlpi_phdr[i];
        uintptr_t end = base + phdr->p_vaddr + phdr->p_memsz;
        if (end > image_end) image_end = end;
        if (phdr->p_type == PT_DYNAMIC) {
            dynamic = (const ElfW(Dyn) *) (base + phdr->p_vaddr);
        }
    }
    if (dynamic == NULL) return 0;

    const ElfW(Sym) *symbols = NULL;
    const char *strings = NULL;
    const uint32_t *sysv_hash = NULL;
    const uint32_t *gnu_hash = NULL;
    for (const ElfW(Dyn) *entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symbols = (const ElfW(Sym) *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strings = (const char *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_HASH:
                sysv_hash = (const uint32_t *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_GNU_HASH:
                gnu_hash = (const uint32_t *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            default:
                break;
        }
    }
    if (symbols == NULL || strings == NULL) return 0;

    db_sdl_set_hint_with_priority_fn set_with_priority =
            (db_sdl_set_hint_with_priority_fn) db_lookup_loaded_symbol(
                    base, image_end, symbols, strings, sysv_hash, gnu_hash,
                    "SDL_SetHintWithPriority");
    db_sdl_set_hint_fn set_hint =
            (db_sdl_set_hint_fn) db_lookup_loaded_symbol(
                    base, image_end, symbols, strings, sysv_hash, gnu_hash,
                    "SDL_SetHint");
    db_sdl_get_hint_fn get_hint =
            (db_sdl_get_hint_fn) db_lookup_loaded_symbol(
                    base, image_end, symbols, strings, sysv_hash, gnu_hash,
                    "SDL_GetHint");
    if (set_with_priority == NULL && set_hint == NULL) {
        db_log("OpenGL hint scan could not resolve SDL_SetHint library=%s reason=%s",
               library_name[0] != '\0' ? library_name : "<anonymous>",
               context->reason);
        return 0;
    }

    const int SDL_HINT_OVERRIDE_VALUE = 2;
#define DB_SET_LOCAL_HINT(name, value) \
    (set_with_priority != NULL \
            ? set_with_priority((name), (value), SDL_HINT_OVERRIDE_VALUE) \
            : set_hint((name), (value)))

    bool force_ok = DB_SET_LOCAL_HINT("SDL_VIDEO_FORCE_EGL", "1");
    bool egl_ok = DB_SET_LOCAL_HINT("SDL_EGL_LIBRARY", context->egl_path);
    bool gl_ok = DB_SET_LOCAL_HINT("SDL_OPENGL_LIBRARY", context->gl_path);
    /* SDL's Android backend may need the legacy eglGetDisplay fallback on
     * vendor stacks that reject eglGetPlatformDisplay before initialization. */
    bool display_fallback_ok = DB_SET_LOCAL_HINT(
            "SDL_VIDEO_EGL_ALLOW_GETDISPLAY_FALLBACK", "1");

#undef DB_SET_LOCAL_HINT

    const char *actual_force = get_hint != NULL
            ? get_hint("SDL_VIDEO_FORCE_EGL") : NULL;
    const char *actual_egl = get_hint != NULL
            ? get_hint("SDL_EGL_LIBRARY") : NULL;
    const char *actual_gl = get_hint != NULL
            ? get_hint("SDL_OPENGL_LIBRARY") : NULL;

    bool verified = force_ok && egl_ok && gl_ok;
    if (actual_force != NULL && strcmp(actual_force, "1") != 0) verified = false;
    if (actual_egl != NULL && strcmp(actual_egl, context->egl_path) != 0) {
        verified = false;
    }
    if (actual_gl != NULL && strcmp(actual_gl, context->gl_path) != 0) {
        verified = false;
    }
    if (verified) context->libraries_configured++;

    db_log("OpenGL hints applied mapping=%s base=%p reason=%s force=%d egl=%d gl=%d displayFallback=%d verified=%d actualEgl=%s actualGl=%s",
           library_name[0] != '\0' ? library_name : "<anonymous>",
           (void *) base,
           context->reason,
           force_ok ? 1 : 0,
           egl_ok ? 1 : 0,
           gl_ok ? 1 : 0,
           display_fallback_ok ? 1 : 0,
           verified ? 1 : 0,
           actual_egl != NULL ? actual_egl : "<unavailable>",
           actual_gl != NULL ? actual_gl : "<unavailable>");
    return 0;
}

static bool apply_open_gl_hints_to_all_loaded_sdl3(const char *reason) {
    const char *enabled = getenv("DROIDBRIDGE_SDL3_WRAPPED_OPENGL");
    const char *egl_path = getenv("SDL_EGL_LIBRARY");
    const char *gl_path = getenv("SDL_OPENGL_LIBRARY");
    if (enabled == NULL || strcmp(enabled, "1") != 0
            || egl_path == NULL || egl_path[0] == '\0'
            || gl_path == NULL || gl_path[0] == '\0') {
        return false;
    }

    struct sdl_hint_apply_context context = {
            egl_path,
            gl_path,
            reason != NULL ? reason : "unspecified",
            0,
            0
    };
    dl_iterate_phdr(apply_open_gl_hints_to_loaded_sdl3, &context);
    db_log("OpenGL hint scan reason=%s libraries=%d configured=%d egl=%s gl=%s",
           context.reason,
           context.libraries_seen,
           context.libraries_configured,
           egl_path,
           gl_path);
    return context.libraries_seen > 0
            && context.libraries_configured == context.libraries_seen;
}

static SDL_Window *focused_sdl_window(void) {
    SDL_Window *window = NULL;
    if (g_sdl_get_mouse_focus != NULL) window = g_sdl_get_mouse_focus();
    if (window == NULL && g_sdl_get_keyboard_focus != NULL) {
        window = g_sdl_get_keyboard_focus();
    }
    return window;
}

struct hook_context {
    int libraries_seen;
    int relocations_patched;
};

static bool patch_slot(void **slot, void *replacement) {
    if (slot == NULL || *slot == replacement) return false;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    uintptr_t page = ((uintptr_t) slot) & ~((uintptr_t) page_size - 1u);
    int original_protection = protection_for_address((uintptr_t) slot);
    int write_protection = original_protection | PROT_READ | PROT_WRITE;

    if (mprotect((void *) page, (size_t) page_size, write_protection) != 0) {
        db_log("mprotect writable failed slot=%p errno=%d", slot, errno);
        return false;
    }

    __atomic_store_n(slot, replacement, __ATOMIC_RELEASE);
    __builtin___clear_cache((char *) slot, (char *) slot + sizeof(void *));

    if ((original_protection & PROT_WRITE) == 0) {
        (void) mprotect((void *) page, (size_t) page_size, original_protection);
    }
    return true;
}


#define DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS 16
#define DB_KRYPTON_MALI_MAX_ARRAY_SIZE 32
#define DB_KRYPTON_MALI_MAX_SHADER_BYTES (64u * 1024u * 1024u)

typedef struct db_krypton_mali_output_array {
    char name[96];
    int size;
    size_t declaration_start;
    size_t declaration_end;
} db_krypton_mali_output_array;

typedef struct db_krypton_mali_string_builder {
    char *data;
    size_t length;
    size_t capacity;
} db_krypton_mali_string_builder;

typedef struct db_krypton_hook_context {
    int libraries_seen;
    int relocations_patched;
} db_krypton_hook_context;

static bool db_krypton_mali_builder_reserve(
        db_krypton_mali_string_builder *builder, size_t additional) {
    if (builder == NULL) return false;
    if (additional > SIZE_MAX - builder->length - 1u) return false;
    size_t needed = builder->length + additional + 1u;
    if (needed <= builder->capacity) return true;
    size_t capacity = builder->capacity > 0u ? builder->capacity : 4096u;
    while (capacity < needed) {
        if (capacity > SIZE_MAX / 2u) {
            capacity = needed;
            break;
        }
        capacity *= 2u;
    }
    char *grown = (char *) realloc(builder->data, capacity);
    if (grown == NULL) return false;
    builder->data = grown;
    builder->capacity = capacity;
    return true;
}

static bool db_krypton_mali_builder_append_n(
        db_krypton_mali_string_builder *builder, const char *text, size_t length) {
    if (length == 0u) return true;
    if (builder == NULL || text == NULL
            || !db_krypton_mali_builder_reserve(builder, length)) {
        return false;
    }
    memcpy(builder->data + builder->length, text, length);
    builder->length += length;
    builder->data[builder->length] = '\0';
    return true;
}

static bool db_krypton_mali_builder_append(
        db_krypton_mali_string_builder *builder, const char *text) {
    return text != NULL
            && db_krypton_mali_builder_append_n(builder, text, strlen(text));
}

static bool db_krypton_mali_builder_append_int(
        db_krypton_mali_string_builder *builder, int value) {
    char number[32];
    int count = snprintf(number, sizeof(number), "%d", value);
    return count > 0
            && (size_t) count < sizeof(number)
            && db_krypton_mali_builder_append_n(builder, number, (size_t) count);
}

static bool db_krypton_mali_ident_char(char value) {
    unsigned char c = (unsigned char) value;
    return isalnum(c) != 0 || value == '_';
}

static bool db_krypton_mali_identifier_at(
        const char *source, size_t length, size_t offset,
        const char *identifier) {
    if (source == NULL || identifier == NULL || offset >= length) return false;
    size_t name_length = strlen(identifier);
    if (name_length == 0u || name_length > length - offset) return false;
    if (memcmp(source + offset, identifier, name_length) != 0) return false;
    if (offset > 0u && db_krypton_mali_ident_char(source[offset - 1u])) return false;
    if (offset + name_length < length
            && db_krypton_mali_ident_char(source[offset + name_length])) return false;
    return true;
}

static size_t db_krypton_mali_skip_space(
        const char *source, size_t length, size_t offset) {
    while (offset < length && isspace((unsigned char) source[offset]) != 0) offset++;
    return offset;
}

static bool db_krypton_mali_span_has_token(
        const char *source, size_t start, size_t end, const char *token,
        size_t *token_offset) {
    if (source == NULL || token == NULL || start >= end) return false;
    size_t token_length = strlen(token);
    bool line_comment = false;
    bool block_comment = false;
    for (size_t i = start; i + token_length <= end; ++i) {
        if (line_comment) {
            if (source[i] == '\n') line_comment = false;
            continue;
        }
        if (block_comment) {
            if (source[i] == '*' && i + 1u < end && source[i + 1u] == '/') {
                block_comment = false;
                i++;
            }
            continue;
        }
        if (source[i] == '/' && i + 1u < end) {
            if (source[i + 1u] == '/') {
                line_comment = true;
                i++;
                continue;
            }
            if (source[i + 1u] == '*') {
                block_comment = true;
                i++;
                continue;
            }
        }
        if (memcmp(source + i, token, token_length) != 0) continue;
        if (i > start && db_krypton_mali_ident_char(source[i - 1u])) continue;
        if (i + token_length < end
                && db_krypton_mali_ident_char(source[i + token_length])) continue;
        if (token_offset != NULL) *token_offset = i;
        return true;
    }
    return false;
}

static int db_krypton_mali_lookup_define_int(
        const char *source, size_t length, const char *name, size_t name_length) {
    if (source == NULL || name == NULL || name_length == 0u || name_length >= 96u) {
        return 0;
    }
    char target[96];
    memcpy(target, name, name_length);
    target[name_length] = '\0';
    size_t line_start = 0u;
    while (line_start < length) {
        size_t line_end = line_start;
        while (line_end < length && source[line_end] != '\n') line_end++;
        size_t p = db_krypton_mali_skip_space(source, line_end, line_start);
        if (p < line_end && source[p] == '#') {
            p = db_krypton_mali_skip_space(source, line_end, p + 1u);
            static const char define_word[] = "define";
            if (p + sizeof(define_word) - 1u <= line_end
                    && memcmp(source + p, define_word, sizeof(define_word) - 1u) == 0
                    && (p + sizeof(define_word) - 1u == line_end
                        || !db_krypton_mali_ident_char(
                                source[p + sizeof(define_word) - 1u]))) {
                p += sizeof(define_word) - 1u;
                p = db_krypton_mali_skip_space(source, line_end, p);
                size_t identifier_start = p;
                while (p < line_end && db_krypton_mali_ident_char(source[p])) p++;
                if (p - identifier_start == name_length
                        && memcmp(source + identifier_start, target, name_length) == 0) {
                    p = db_krypton_mali_skip_space(source, line_end, p);
                    char *end_pointer = NULL;
                    long parsed = strtol(source + p, &end_pointer, 10);
                    if (end_pointer != source + p && parsed > 0
                            && parsed <= DB_KRYPTON_MALI_MAX_ARRAY_SIZE) {
                        return (int) parsed;
                    }
                }
            }
        }
        line_start = line_end < length ? line_end + 1u : length;
    }
    return 0;
}

static int db_krypton_mali_max_draw_buffers(void) {
    int value = 8;
    db_gl_get_integerv_fn get_integerv = (db_gl_get_integerv_fn)
            db_lookup_wrapped_gl_proc("glGetIntegerv");
    if (get_integerv != NULL) {
        int reported = 0;
        get_integerv(DB_GL_MAX_DRAW_BUFFERS, &reported);
        if (reported > 0 && reported <= DB_KRYPTON_MALI_MAX_ARRAY_SIZE) value = reported;
    }
    return value;
}

static int db_krypton_mali_parse_array_size(
        const char *source, size_t length, size_t start, size_t end) {
    if (source == NULL || start >= end || end > length) return 0;
    while (start < end && isspace((unsigned char) source[start]) != 0) start++;
    while (end > start && isspace((unsigned char) source[end - 1u]) != 0) end--;
    if (start >= end) return 0;
    bool digits = true;
    size_t numeric_end = end;
    if (numeric_end > start
            && (source[numeric_end - 1u] == 'u' || source[numeric_end - 1u] == 'U')) {
        numeric_end--;
    }
    for (size_t i = start; i < numeric_end; ++i) {
        if (isdigit((unsigned char) source[i]) == 0) {
            digits = false;
            break;
        }
    }
    if (digits && numeric_end > start) {
        char number[32];
        size_t number_length = numeric_end - start;
        if (number_length < sizeof(number)) {
            memcpy(number, source + start, number_length);
            number[number_length] = '\0';
            long parsed = strtol(number, NULL, 10);
            if (parsed > 0 && parsed <= DB_KRYPTON_MALI_MAX_ARRAY_SIZE) return (int) parsed;
        }
    }
    static const char max_draw_buffers[] = "gl_MaxDrawBuffers";
    if (end - start == sizeof(max_draw_buffers) - 1u
            && memcmp(source + start, max_draw_buffers,
                      sizeof(max_draw_buffers) - 1u) == 0) {
        return db_krypton_mali_max_draw_buffers();
    }
    int defined = db_krypton_mali_lookup_define_int(
            source, length, source + start, end - start);
    if (defined > 0) return defined;
    return db_krypton_mali_max_draw_buffers();
}

static size_t db_krypton_mali_find_matching_bracket(
        const char *source, size_t length, size_t open_offset) {
    if (source == NULL || open_offset >= length || source[open_offset] != '[') {
        return SIZE_MAX;
    }
    int depth = 1;
    bool line_comment = false;
    bool block_comment = false;
    for (size_t i = open_offset + 1u; i < length; ++i) {
        if (line_comment) {
            if (source[i] == '\n') line_comment = false;
            continue;
        }
        if (block_comment) {
            if (source[i] == '*' && i + 1u < length && source[i + 1u] == '/') {
                block_comment = false;
                i++;
            }
            continue;
        }
        if (source[i] == '/' && i + 1u < length) {
            if (source[i + 1u] == '/') {
                line_comment = true;
                i++;
                continue;
            }
            if (source[i + 1u] == '*') {
                block_comment = true;
                i++;
                continue;
            }
        }
        if (source[i] == '[') depth++;
        else if (source[i] == ']') {
            depth--;
            if (depth == 0) return i;
        }
    }
    return SIZE_MAX;
}

static int db_krypton_mali_find_output_arrays(
        const char *source, size_t length,
        db_krypton_mali_output_array *arrays, int capacity) {
    if (source == NULL || arrays == NULL || capacity <= 0) return 0;
    int count = 0;
    int brace_depth = 0;
    bool line_comment = false;
    bool block_comment = false;
    size_t statement_start = 0u;
    for (size_t i = 0u; i < length; ++i) {
        if (line_comment) {
            if (source[i] == '\n') line_comment = false;
            continue;
        }
        if (block_comment) {
            if (source[i] == '*' && i + 1u < length && source[i + 1u] == '/') {
                block_comment = false;
                i++;
            }
            continue;
        }
        if (source[i] == '/' && i + 1u < length) {
            if (source[i + 1u] == '/') {
                line_comment = true;
                i++;
                continue;
            }
            if (source[i + 1u] == '*') {
                block_comment = true;
                i++;
                continue;
            }
        }
        if (source[i] == '{') {
            brace_depth++;
            continue;
        }
        if (source[i] == '}') {
            if (brace_depth > 0) brace_depth--;
            if (brace_depth == 0) statement_start = i + 1u;
            continue;
        }
        if (source[i] != ';' || brace_depth != 0) continue;

        size_t statement_end = i + 1u;
        size_t out_token = SIZE_MAX;
        if (db_krypton_mali_span_has_token(
                    source, statement_start, statement_end, "out", &out_token)) {
            for (size_t bracket = out_token + 3u; bracket < i; ++bracket) {
                if (source[bracket] != '[') continue;
                size_t close = db_krypton_mali_find_matching_bracket(
                        source, statement_end, bracket);
                if (close == SIZE_MAX || close >= statement_end) break;
                size_t name_end = bracket;
                while (name_end > out_token
                        && isspace((unsigned char) source[name_end - 1u]) != 0) {
                    name_end--;
                }
                size_t name_start = name_end;
                while (name_start > out_token
                        && db_krypton_mali_ident_char(source[name_start - 1u])) {
                    name_start--;
                }
                size_t name_length = name_end - name_start;
                if (name_length == 0u || name_length >= sizeof(arrays[0].name)) {
                    bracket = close;
                    continue;
                }
                bool duplicate = false;
                for (int existing = 0; existing < count; ++existing) {
                    if (strlen(arrays[existing].name) == name_length
                            && memcmp(arrays[existing].name,
                                      source + name_start, name_length) == 0) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate && count < capacity) {
                    memcpy(arrays[count].name, source + name_start, name_length);
                    arrays[count].name[name_length] = '\0';
                    arrays[count].size = db_krypton_mali_parse_array_size(
                            source, length, bracket + 1u, close);
                    if (arrays[count].size <= 0) {
                        arrays[count].size = db_krypton_mali_max_draw_buffers();
                    }
                    if (arrays[count].size > DB_KRYPTON_MALI_MAX_ARRAY_SIZE) {
                        arrays[count].size = DB_KRYPTON_MALI_MAX_ARRAY_SIZE;
                    }
                    arrays[count].declaration_start = statement_start;
                    arrays[count].declaration_end = statement_end;
                    count++;
                }
                bracket = close;
            }
        }
        statement_start = statement_end;
    }

    /* Some translators use the compatibility built-in without emitting a
     * source declaration. Constant-index lowering is still legal for it. */
    if (count < capacity && strstr(source, "gl_FragData[") != NULL) {
        bool present = false;
        for (int i = 0; i < count; ++i) {
            if (strcmp(arrays[i].name, "gl_FragData") == 0) {
                present = true;
                break;
            }
        }
        if (!present) {
            strcpy(arrays[count].name, "gl_FragData");
            arrays[count].size = db_krypton_mali_max_draw_buffers();
            arrays[count].declaration_start = SIZE_MAX;
            arrays[count].declaration_end = SIZE_MAX;
            count++;
        }
    }
    return count;
}

static bool db_krypton_mali_is_declaration_use(
        const db_krypton_mali_output_array *array, size_t offset) {
    return array != NULL
            && array->declaration_start != SIZE_MAX
            && offset >= array->declaration_start
            && offset < array->declaration_end;
}

static bool db_krypton_mali_index_is_constant(
        const char *source, size_t start, size_t end) {
    if (source == NULL || start >= end) return false;
    while (start < end && isspace((unsigned char) source[start]) != 0) start++;
    while (end > start && isspace((unsigned char) source[end - 1u]) != 0) end--;
    if (start >= end) return false;
    if (source[start] == '+' || source[start] == '-') start++;
    size_t digits_end = end;
    if (digits_end > start
            && (source[digits_end - 1u] == 'u' || source[digits_end - 1u] == 'U')) {
        digits_end--;
    }
    if (start >= digits_end) return false;
    for (size_t i = start; i < digits_end; ++i) {
        if (isdigit((unsigned char) source[i]) == 0) return false;
    }
    return true;
}

static size_t db_krypton_mali_consume_lvalue_suffix(
        const char *source, size_t length, size_t offset) {
    size_t p = offset;
    for (;;) {
        p = db_krypton_mali_skip_space(source, length, p);
        if (p < length && source[p] == '.') {
            size_t q = p + 1u;
            if (q >= length || !db_krypton_mali_ident_char(source[q])) break;
            while (q < length && db_krypton_mali_ident_char(source[q])) q++;
            p = q;
            continue;
        }
        if (p < length && source[p] == '[') {
            size_t close = db_krypton_mali_find_matching_bracket(source, length, p);
            if (close == SIZE_MAX) break;
            p = close + 1u;
            continue;
        }
        break;
    }
    return p;
}

static size_t db_krypton_mali_assignment_operator(
        const char *source, size_t length, size_t offset, size_t *operator_length) {
    size_t p = db_krypton_mali_skip_space(source, length, offset);
    if (operator_length != NULL) *operator_length = 0u;
    if (p >= length) return SIZE_MAX;
    if (source[p] == '=' && (p + 1u >= length || source[p + 1u] != '=')) {
        if (operator_length != NULL) *operator_length = 1u;
        return p;
    }
    if (p + 1u < length && source[p + 1u] == '='
            && (source[p] == '+' || source[p] == '-' || source[p] == '*'
                || source[p] == '/' || source[p] == '%')) {
        if (operator_length != NULL) *operator_length = 2u;
        return p;
    }
    return SIZE_MAX;
}

static size_t db_krypton_mali_find_statement_semicolon(
        const char *source, size_t length, size_t offset) {
    int paren_depth = 0;
    int bracket_depth = 0;
    bool line_comment = false;
    bool block_comment = false;
    for (size_t i = offset; i < length; ++i) {
        if (line_comment) {
            if (source[i] == '\n') line_comment = false;
            continue;
        }
        if (block_comment) {
            if (source[i] == '*' && i + 1u < length && source[i + 1u] == '/') {
                block_comment = false;
                i++;
            }
            continue;
        }
        if (source[i] == '/' && i + 1u < length) {
            if (source[i + 1u] == '/') {
                line_comment = true;
                i++;
                continue;
            }
            if (source[i + 1u] == '*') {
                block_comment = true;
                i++;
                continue;
            }
        }
        if (source[i] == '(') paren_depth++;
        else if (source[i] == ')' && paren_depth > 0) paren_depth--;
        else if (source[i] == '[') bracket_depth++;
        else if (source[i] == ']' && bracket_depth > 0) bracket_depth--;
        else if (source[i] == ';' && paren_depth == 0 && bracket_depth == 0) return i;
        else if ((source[i] == '{' || source[i] == '}')
                 && paren_depth == 0 && bracket_depth == 0) return SIZE_MAX;
    }
    return SIZE_MAX;
}

static bool db_krypton_mali_append_index_expression(
        db_krypton_mali_string_builder *builder,
        const char *source, size_t expression_start, size_t expression_end) {
    while (expression_start < expression_end
            && isspace((unsigned char) source[expression_start]) != 0) {
        expression_start++;
    }
    while (expression_end > expression_start
            && isspace((unsigned char) source[expression_end - 1u]) != 0) {
        expression_end--;
    }
    return db_krypton_mali_builder_append_n(
            builder, source + expression_start, expression_end - expression_start);
}

static bool db_krypton_mali_append_read_selection(
        db_krypton_mali_string_builder *builder,
        const db_krypton_mali_output_array *array,
        const char *source, size_t expression_start, size_t expression_end) {
    if (array == NULL || array->size <= 0) return false;
    for (int index = 0; index < array->size - 1; ++index) {
        if (!db_krypton_mali_builder_append(builder, "((int(")) return false;
        if (!db_krypton_mali_append_index_expression(
                    builder, source, expression_start, expression_end)) return false;
        if (!db_krypton_mali_builder_append(builder, ") == ")) return false;
        if (!db_krypton_mali_builder_append_int(builder, index)) return false;
        if (!db_krypton_mali_builder_append(builder, ") ? ")) return false;
        if (!db_krypton_mali_builder_append(builder, array->name)) return false;
        if (!db_krypton_mali_builder_append(builder, "[")) return false;
        if (!db_krypton_mali_builder_append_int(builder, index)) return false;
        if (!db_krypton_mali_builder_append(builder, "] : ")) return false;
    }
    if (!db_krypton_mali_builder_append(builder, array->name)) return false;
    if (!db_krypton_mali_builder_append(builder, "[")) return false;
    if (!db_krypton_mali_builder_append_int(builder, array->size - 1)) return false;
    if (!db_krypton_mali_builder_append(builder, "]")) return false;
    for (int index = 0; index < array->size - 1; ++index) {
        if (!db_krypton_mali_builder_append(builder, ")")) return false;
    }
    return true;
}

static bool db_krypton_mali_rewrite_reads(
        const char *source, size_t length,
        char **rewritten_source, size_t *rewritten_length, int *rewrite_count) {
    db_krypton_mali_output_array arrays[DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS];
    memset(arrays, 0, sizeof(arrays));
    int array_count = db_krypton_mali_find_output_arrays(
            source, length, arrays, DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS);
    if (array_count <= 0) return false;

    db_krypton_mali_string_builder builder = {0};
    bool changed = false;
    int rewrites = 0;
    bool line_comment = false;
    bool block_comment = false;
    size_t copy_start = 0u;
    for (size_t i = 0u; i < length;) {
        if (line_comment) {
            if (source[i] == '\n') line_comment = false;
            i++;
            continue;
        }
        if (block_comment) {
            if (source[i] == '*' && i + 1u < length && source[i + 1u] == '/') {
                block_comment = false;
                i += 2u;
            } else {
                i++;
            }
            continue;
        }
        if (source[i] == '/' && i + 1u < length) {
            if (source[i + 1u] == '/') {
                line_comment = true;
                i += 2u;
                continue;
            }
            if (source[i + 1u] == '*') {
                block_comment = true;
                i += 2u;
                continue;
            }
        }

        const db_krypton_mali_output_array *array = NULL;
        for (int candidate = 0; candidate < array_count; ++candidate) {
            if (db_krypton_mali_identifier_at(
                        source, length, i, arrays[candidate].name)) {
                array = &arrays[candidate];
                break;
            }
        }
        if (array == NULL || db_krypton_mali_is_declaration_use(array, i)) {
            i++;
            continue;
        }
        size_t name_length = strlen(array->name);
        size_t open = db_krypton_mali_skip_space(source, length, i + name_length);
        if (open >= length || source[open] != '[') {
            i += name_length;
            continue;
        }
        size_t close = db_krypton_mali_find_matching_bracket(source, length, open);
        if (close == SIZE_MAX || db_krypton_mali_index_is_constant(source, open + 1u, close)) {
            i = close != SIZE_MAX ? close + 1u : open + 1u;
            continue;
        }
        size_t suffix_end = db_krypton_mali_consume_lvalue_suffix(
                source, length, close + 1u);
        size_t operator_length = 0u;
        size_t assignment = db_krypton_mali_assignment_operator(
                source, length, suffix_end, &operator_length);
        if (assignment != SIZE_MAX) {
            /* This is a write target. Leave it untouched for the write pass. */
            i = close + 1u;
            continue;
        }

        if (!db_krypton_mali_builder_append_n(
                    &builder, source + copy_start, i - copy_start)
                || !db_krypton_mali_append_read_selection(
                    &builder, array, source, open + 1u, close)) {
            free(builder.data);
            return false;
        }
        changed = true;
        rewrites++;
        i = close + 1u;
        copy_start = i;
    }
    if (!changed) {
        free(builder.data);
        return false;
    }
    if (!db_krypton_mali_builder_append_n(
                &builder, source + copy_start, length - copy_start)) {
        free(builder.data);
        return false;
    }
    if (rewritten_source != NULL) *rewritten_source = builder.data;
    else free(builder.data);
    if (rewritten_length != NULL) *rewritten_length = builder.length;
    if (rewrite_count != NULL) *rewrite_count = rewrites;
    return true;
}

static bool db_krypton_mali_rewrite_writes(
        const char *source, size_t length,
        char **rewritten_source, size_t *rewritten_length, int *rewrite_count) {
    db_krypton_mali_output_array arrays[DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS];
    memset(arrays, 0, sizeof(arrays));
    int array_count = db_krypton_mali_find_output_arrays(
            source, length, arrays, DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS);
    if (array_count <= 0) return false;

    db_krypton_mali_string_builder builder = {0};
    bool changed = false;
    int rewrites = 0;
    bool line_comment = false;
    bool block_comment = false;
    size_t copy_start = 0u;
    unsigned int local_serial = 0u;
    for (size_t i = 0u; i < length;) {
        if (line_comment) {
            if (source[i] == '\n') line_comment = false;
            i++;
            continue;
        }
        if (block_comment) {
            if (source[i] == '*' && i + 1u < length && source[i + 1u] == '/') {
                block_comment = false;
                i += 2u;
            } else {
                i++;
            }
            continue;
        }
        if (source[i] == '/' && i + 1u < length) {
            if (source[i + 1u] == '/') {
                line_comment = true;
                i += 2u;
                continue;
            }
            if (source[i + 1u] == '*') {
                block_comment = true;
                i += 2u;
                continue;
            }
        }

        const db_krypton_mali_output_array *array = NULL;
        for (int candidate = 0; candidate < array_count; ++candidate) {
            if (db_krypton_mali_identifier_at(
                        source, length, i, arrays[candidate].name)) {
                array = &arrays[candidate];
                break;
            }
        }
        if (array == NULL || db_krypton_mali_is_declaration_use(array, i)) {
            i++;
            continue;
        }
        size_t name_length = strlen(array->name);
        size_t open = db_krypton_mali_skip_space(source, length, i + name_length);
        if (open >= length || source[open] != '[') {
            i += name_length;
            continue;
        }
        size_t close = db_krypton_mali_find_matching_bracket(source, length, open);
        if (close == SIZE_MAX || db_krypton_mali_index_is_constant(source, open + 1u, close)) {
            i = close != SIZE_MAX ? close + 1u : open + 1u;
            continue;
        }
        size_t suffix_end = db_krypton_mali_consume_lvalue_suffix(
                source, length, close + 1u);
        size_t operator_length = 0u;
        size_t assignment = db_krypton_mali_assignment_operator(
                source, length, suffix_end, &operator_length);
        if (assignment == SIZE_MAX) {
            i = close + 1u;
            continue;
        }
        size_t semicolon = db_krypton_mali_find_statement_semicolon(
                source, length, assignment + operator_length);
        if (semicolon == SIZE_MAX) {
            i = close + 1u;
            continue;
        }

        if (!db_krypton_mali_builder_append_n(
                    &builder, source + copy_start, i - copy_start)) {
            free(builder.data);
            return false;
        }
        char temp_name[64];
        snprintf(temp_name, sizeof(temp_name), "__db_mali_idx_%u", local_serial++);
        if (!db_krypton_mali_builder_append(&builder, "{ int ")
                || !db_krypton_mali_builder_append(&builder, temp_name)
                || !db_krypton_mali_builder_append(&builder, " = int(")
                || !db_krypton_mali_append_index_expression(
                    &builder, source, open + 1u, close)
                || !db_krypton_mali_builder_append(&builder, "); ")) {
            free(builder.data);
            return false;
        }
        for (int index = 0; index < array->size; ++index) {
            if (index == 0) {
                if (!db_krypton_mali_builder_append(&builder, "if (")) goto write_fail;
            } else {
                if (!db_krypton_mali_builder_append(&builder, " else if (")) goto write_fail;
            }
            if (!db_krypton_mali_builder_append(&builder, temp_name)
                    || !db_krypton_mali_builder_append(&builder, " == ")
                    || !db_krypton_mali_builder_append_int(&builder, index)
                    || !db_krypton_mali_builder_append(&builder, ") ")
                    || !db_krypton_mali_builder_append(&builder, array->name)
                    || !db_krypton_mali_builder_append(&builder, "[")
                    || !db_krypton_mali_builder_append_int(&builder, index)
                    || !db_krypton_mali_builder_append(&builder, "]")
                    || !db_krypton_mali_builder_append_n(
                        &builder, source + close + 1u, assignment - (close + 1u))
                    || !db_krypton_mali_builder_append_n(
                        &builder, source + assignment, operator_length)
                    || !db_krypton_mali_builder_append_n(
                        &builder, source + assignment + operator_length,
                        semicolon - (assignment + operator_length))
                    || !db_krypton_mali_builder_append(&builder, ";")) {
                goto write_fail;
            }
        }
        if (!db_krypton_mali_builder_append(&builder, " }") ) goto write_fail;
        changed = true;
        rewrites++;
        i = semicolon + 1u;
        copy_start = i;
        continue;

write_fail:
        free(builder.data);
        return false;
    }
    if (!changed) {
        free(builder.data);
        return false;
    }
    if (!db_krypton_mali_builder_append_n(
                &builder, source + copy_start, length - copy_start)) {
        free(builder.data);
        return false;
    }
    if (rewritten_source != NULL) *rewritten_source = builder.data;
    else free(builder.data);
    if (rewritten_length != NULL) *rewritten_length = builder.length;
    if (rewrite_count != NULL) *rewrite_count = rewrites;
    return true;
}

static int db_krypton_mali_count_dynamic_output_accesses(
        const char *source, size_t length) {
    db_krypton_mali_output_array arrays[DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS];
    memset(arrays, 0, sizeof(arrays));
    int array_count = db_krypton_mali_find_output_arrays(
            source, length, arrays, DB_KRYPTON_MALI_MAX_OUTPUT_ARRAYS);
    int dynamic_count = 0;
    for (size_t i = 0u; i < length; ++i) {
        for (int candidate = 0; candidate < array_count; ++candidate) {
            const db_krypton_mali_output_array *array = &arrays[candidate];
            if (!db_krypton_mali_identifier_at(source, length, i, array->name)
                    || db_krypton_mali_is_declaration_use(array, i)) {
                continue;
            }
            size_t open = db_krypton_mali_skip_space(
                    source, length, i + strlen(array->name));
            if (open >= length || source[open] != '[') continue;
            size_t close = db_krypton_mali_find_matching_bracket(source, length, open);
            if (close != SIZE_MAX
                    && !db_krypton_mali_index_is_constant(source, open + 1u, close)) {
                dynamic_count++;
            }
        }
    }
    return dynamic_count;
}

static bool db_krypton_mali_gpu_active(void) {
    int cached = __atomic_load_n(&g_krypton_mali_gpu_state, __ATOMIC_ACQUIRE);
    if (cached != 0) return cached > 0;
    db_gl_get_string_fn get_string = (db_gl_get_string_fn)
            db_lookup_wrapped_gl_proc("glGetString");
    if (get_string == NULL) return false;
    const unsigned char *renderer_bytes = get_string(DB_GL_RENDERER);
    if (renderer_bytes == NULL || renderer_bytes[0] == '\0') return false;
    const char *renderer = (const char *) renderer_bytes;
    bool mali = strcasestr(renderer, "Mali") != NULL;
    __atomic_store_n(&g_krypton_mali_gpu_state, mali ? 1 : -1, __ATOMIC_RELEASE);
    db_log("Krypton shader compatibility renderer=%s mali=%d finalGlesLowering=%d",
           renderer, mali ? 1 : 0, mali ? 1 : 0);
    return mali;
}

static bool db_krypton_symbol_is_wrapper(void *symbol) {
    if (symbol == NULL) return false;
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr(symbol, &info) == 0 || info.dli_fname == NULL) return false;
    const char *configured = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    const char *configured_base = db_basename(configured);
    const char *actual_base = db_basename(info.dli_fname);
    if (configured_base != NULL && configured_base[0] != '\0'
            && strcmp(actual_base, configured_base) == 0) return true;
    return strstr(actual_base, "libng_gl4es") != NULL;
}

static void db_krypton_mali_driver_gl_shader_source(
        unsigned int shader, int count, const char *const *strings, const int *lengths) {
    db_gl_shader_source_fn real = g_real_krypton_driver_shader_source;
    if (real == NULL || real == &db_krypton_mali_driver_gl_shader_source) return;
    if (!db_is_krypton_renderer() || !db_krypton_mali_gpu_active()
            || count <= 0 || strings == NULL) {
        real(shader, count, strings, lengths);
        return;
    }

    size_t total = 0u;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t part = lengths != NULL && lengths[i] >= 0
                ? (size_t) lengths[i] : strlen(strings[i]);
        if (part > DB_KRYPTON_MALI_MAX_SHADER_BYTES
                || total > DB_KRYPTON_MALI_MAX_SHADER_BYTES - part) {
            real(shader, count, strings, lengths);
            return;
        }
        total += part;
    }
    if (total == 0u) {
        real(shader, count, strings, lengths);
        return;
    }
    char *combined = (char *) malloc(total + 1u);
    if (combined == NULL) {
        real(shader, count, strings, lengths);
        return;
    }
    size_t output = 0u;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t part = lengths != NULL && lengths[i] >= 0
                ? (size_t) lengths[i] : strlen(strings[i]);
        memcpy(combined + output, strings[i], part);
        output += part;
    }
    combined[output] = '\0';

    if (strstr(combined, "out") == NULL || strchr(combined, '[') == NULL) {
        free(combined);
        real(shader, count, strings, lengths);
        return;
    }

    char *read_source = NULL;
    size_t read_length = 0u;
    int read_rewrites = 0;
    const char *stage_source = combined;
    size_t stage_length = total;
    if (db_krypton_mali_rewrite_reads(
                combined, total, &read_source, &read_length, &read_rewrites)) {
        stage_source = read_source;
        stage_length = read_length;
    }

    char *write_source = NULL;
    size_t write_length = 0u;
    int write_rewrites = 0;
    if (db_krypton_mali_rewrite_writes(
                stage_source, stage_length,
                &write_source, &write_length, &write_rewrites)) {
        stage_source = write_source;
        stage_length = write_length;
    }

    int remaining = db_krypton_mali_count_dynamic_output_accesses(
            stage_source, stage_length);
    if ((read_rewrites > 0 || write_rewrites > 0) && remaining == 0) {
        const char *one = stage_source;
        int one_length = stage_length <= (size_t) INT32_MAX ? (int) stage_length : -1;
        unsigned int serial = __atomic_add_fetch(
                &g_krypton_mali_shader_rewrite_serial, 1u, __ATOMIC_RELAXED);
        if (serial <= 64u || (serial % 128u) == 0u) {
            db_log("Krypton Mali final GLES shader lowered shader=%u bytes=%zu->%zu reads=%d writes=%d remaining=%d serial=%u",
                   shader, total, stage_length, read_rewrites, write_rewrites,
                   remaining, serial);
        }
        real(shader, 1, &one, one_length >= 0 ? &one_length : NULL);
    } else {
        if ((read_rewrites > 0 || write_rewrites > 0) && remaining > 0) {
            db_log("Krypton Mali final GLES shader lowering incomplete shader=%u reads=%d writes=%d remaining=%d; preserving original",
                   shader, read_rewrites, write_rewrites, remaining);
        }
        real(shader, count, strings, lengths);
    }
    free(write_source);
    free(read_source);
    free(combined);
}

static void *db_krypton_mali_egl_get_proc_address(const char *name) {
    db_egl_get_proc_address_fn real_get_proc = g_real_krypton_driver_egl_get_proc_address;
    void *resolved = real_get_proc != NULL ? real_get_proc(name) : NULL;
    if (name != NULL && strcmp(name, "glShaderSource") == 0
            && resolved != NULL && !db_krypton_symbol_is_wrapper(resolved)) {
        pthread_mutex_lock(&g_krypton_mali_shader_mutex);
        if (g_real_krypton_driver_shader_source == NULL) {
            g_real_krypton_driver_shader_source = (db_gl_shader_source_fn) resolved;
        }
        pthread_mutex_unlock(&g_krypton_mali_shader_mutex);
        return (void *) &db_krypton_mali_driver_gl_shader_source;
    }
    return resolved;
}

static void *db_krypton_mali_dlsym(void *handle, const char *name) {
    db_resolve_real_dlsym();
    void *resolved = g_real_dlsym != NULL ? g_real_dlsym(handle, name) : NULL;
    if (name == NULL || resolved == NULL || db_krypton_symbol_is_wrapper(resolved)) {
        return resolved;
    }
    if (strcmp(name, "eglGetProcAddress") == 0) {
        pthread_mutex_lock(&g_krypton_mali_shader_mutex);
        if (g_real_krypton_driver_egl_get_proc_address == NULL) {
            g_real_krypton_driver_egl_get_proc_address =
                    (db_egl_get_proc_address_fn) resolved;
        }
        pthread_mutex_unlock(&g_krypton_mali_shader_mutex);
        return (void *) &db_krypton_mali_egl_get_proc_address;
    }
    if (strcmp(name, "glShaderSource") == 0) {
        pthread_mutex_lock(&g_krypton_mali_shader_mutex);
        if (g_real_krypton_driver_shader_source == NULL) {
            g_real_krypton_driver_shader_source = (db_gl_shader_source_fn) resolved;
        }
        pthread_mutex_unlock(&g_krypton_mali_shader_mutex);
        return (void *) &db_krypton_mali_driver_gl_shader_source;
    }
    return resolved;
}

static void db_patch_krypton_rela_table(
        uintptr_t base, const ElfW(Rela) *relocations, size_t count,
        const ElfW(Sym) *symbols, const char *strings,
        db_krypton_hook_context *context) {
    if (relocations == NULL || symbols == NULL || strings == NULL || context == NULL) return;
    for (size_t i = 0u; i < count; ++i) {
        size_t symbol_index = ELF64_R_SYM(relocations[i].r_info);
        const char *name = strings + symbols[symbol_index].st_name;
        if (name == NULL) continue;
        void **slot = (void **) (base + relocations[i].r_offset);
        void *original = *slot;
        void *replacement = NULL;
        if (strcmp(name, "dlsym") == 0) {
            db_resolve_real_dlsym();
            replacement = (void *) &db_krypton_mali_dlsym;
        } else if (strcmp(name, "eglGetProcAddress") == 0
                && original != NULL && !db_krypton_symbol_is_wrapper(original)) {
            if (g_real_krypton_driver_egl_get_proc_address == NULL) {
                g_real_krypton_driver_egl_get_proc_address =
                        (db_egl_get_proc_address_fn) original;
            }
            replacement = (void *) &db_krypton_mali_egl_get_proc_address;
        } else if (strcmp(name, "glShaderSource") == 0
                && original != NULL && !db_krypton_symbol_is_wrapper(original)) {
            if (g_real_krypton_driver_shader_source == NULL) {
                g_real_krypton_driver_shader_source = (db_gl_shader_source_fn) original;
            }
            replacement = (void *) &db_krypton_mali_driver_gl_shader_source;
        }
        if (replacement != NULL && patch_slot(slot, replacement)) {
            context->relocations_patched++;
            db_log("patched Krypton driver relocation name=%s slot=%p original=%p replacement=%p",
                   name, slot, original, replacement);
        }
    }
}

static void db_patch_krypton_rel_table(
        uintptr_t base, const ElfW(Rel) *relocations, size_t count,
        const ElfW(Sym) *symbols, const char *strings,
        db_krypton_hook_context *context) {
    if (relocations == NULL || symbols == NULL || strings == NULL || context == NULL) return;
    for (size_t i = 0u; i < count; ++i) {
        size_t symbol_index = ELF64_R_SYM(relocations[i].r_info);
        const char *name = strings + symbols[symbol_index].st_name;
        if (name == NULL) continue;
        void **slot = (void **) (base + relocations[i].r_offset);
        void *original = *slot;
        void *replacement = NULL;
        if (strcmp(name, "dlsym") == 0) {
            db_resolve_real_dlsym();
            replacement = (void *) &db_krypton_mali_dlsym;
        } else if (strcmp(name, "eglGetProcAddress") == 0
                && original != NULL && !db_krypton_symbol_is_wrapper(original)) {
            if (g_real_krypton_driver_egl_get_proc_address == NULL) {
                g_real_krypton_driver_egl_get_proc_address =
                        (db_egl_get_proc_address_fn) original;
            }
            replacement = (void *) &db_krypton_mali_egl_get_proc_address;
        } else if (strcmp(name, "glShaderSource") == 0
                && original != NULL && !db_krypton_symbol_is_wrapper(original)) {
            if (g_real_krypton_driver_shader_source == NULL) {
                g_real_krypton_driver_shader_source = (db_gl_shader_source_fn) original;
            }
            replacement = (void *) &db_krypton_mali_driver_gl_shader_source;
        }
        if (replacement != NULL && patch_slot(slot, replacement)) {
            context->relocations_patched++;
            db_log("patched Krypton driver relocation name=%s slot=%p original=%p replacement=%p",
                   name, slot, original, replacement);
        }
    }
}

static int db_hook_loaded_krypton(struct dl_phdr_info *info, size_t size, void *opaque) {
    (void) size;
    db_krypton_hook_context *context = (db_krypton_hook_context *) opaque;
    if (context == NULL || info == NULL || info->dlpi_name == NULL) return 0;
    const char *configured = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    const char *configured_base = db_basename(configured);
    const char *loaded_base = db_basename(info->dlpi_name);
    bool matches = strstr(loaded_base, "libng_gl4es") != NULL
            || (configured_base != NULL && configured_base[0] != '\0'
                && strcmp(loaded_base, configured_base) == 0);
    if (!matches) return 0;
    context->libraries_seen++;

    uintptr_t base = (uintptr_t) info->dlpi_addr;
    uintptr_t image_end = base;
    const ElfW(Dyn) *dynamic = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *phdr = &info->dlpi_phdr[i];
        uintptr_t end = base + phdr->p_vaddr + phdr->p_memsz;
        if (end > image_end) image_end = end;
        if (phdr->p_type == PT_DYNAMIC) {
            dynamic = (const ElfW(Dyn) *) (base + phdr->p_vaddr);
        }
    }
    if (dynamic == NULL) return 0;

    const ElfW(Sym) *symbols = NULL;
    const char *strings = NULL;
    const void *jmprel = NULL;
    size_t pltrelsz = 0u;
    ElfW(Sword) pltrel_type = DT_RELA;
    const ElfW(Rela) *rela = NULL;
    size_t relasz = 0u;
    const ElfW(Rel) *rel = NULL;
    size_t relsz = 0u;
    for (const ElfW(Dyn) *entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symbols = (const ElfW(Sym) *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strings = (const char *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (const void *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_PLTRELSZ: pltrelsz = (size_t) entry->d_un.d_val; break;
            case DT_PLTREL: pltrel_type = (ElfW(Sword)) entry->d_un.d_val; break;
            case DT_RELA:
                rela = (const ElfW(Rela) *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_RELASZ: relasz = (size_t) entry->d_un.d_val; break;
            case DT_REL:
                rel = (const ElfW(Rel) *) loaded_address(
                        base, image_end, (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_RELSZ: relsz = (size_t) entry->d_un.d_val; break;
            default: break;
        }
    }
    if (jmprel != NULL && pltrelsz > 0u) {
        if (pltrel_type == DT_RELA) {
            db_patch_krypton_rela_table(
                    base, (const ElfW(Rela) *) jmprel,
                    pltrelsz / sizeof(ElfW(Rela)), symbols, strings, context);
        } else if (pltrel_type == DT_REL) {
            db_patch_krypton_rel_table(
                    base, (const ElfW(Rel) *) jmprel,
                    pltrelsz / sizeof(ElfW(Rel)), symbols, strings, context);
        }
    }
    if (rela != NULL && relasz > 0u) {
        db_patch_krypton_rela_table(
                base, rela, relasz / sizeof(ElfW(Rela)), symbols, strings, context);
    }
    if (rel != NULL && relsz > 0u) {
        db_patch_krypton_rel_table(
                base, rel, relsz / sizeof(ElfW(Rel)), symbols, strings, context);
    }
    return 0;
}

static void db_install_krypton_mali_shader_hook(void) {
    if (!db_is_krypton_renderer()) return;
    int expected = 0;
    if (!__atomic_compare_exchange_n(
                &g_krypton_mali_shader_hook_installed, &expected, 1,
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    db_krypton_hook_context context = {0, 0};
    dl_iterate_phdr(db_hook_loaded_krypton, &context);
    if (context.libraries_seen == 0) {
        __atomic_store_n(&g_krypton_mali_shader_hook_installed, 0, __ATOMIC_RELEASE);
    }
    db_log("Krypton final GLES Mali shader hook armed libraries=%d patched=%d driverShaderSource=%p driverEglGetProc=%p",
           context.libraries_seen, context.relocations_patched,
           g_real_krypton_driver_shader_source,
           g_real_krypton_driver_egl_get_proc_address);
}

static void patch_rela_table(
        uintptr_t base,
        const ElfW(Rela) *relocations,
        size_t count,
        const ElfW(Sym) *symbols,
        const char *strings,
        struct hook_context *context
) {
    if (relocations == NULL || symbols == NULL || strings == NULL) return;
    for (size_t i = 0; i < count; ++i) {
        size_t symbol_index = ELF64_R_SYM(relocations[i].r_info);
        const char *name = strings + symbols[symbol_index].st_name;
        if (name == NULL) continue;
        void *replacement = NULL;
        if (strcmp(name, "ANativeWindow_fromSurface") == 0) {
            replacement = (void *) db_sdl3_from_surface;
        } else if (strcmp(name, "dlsym") == 0 && db_sdl3_wrapped_opengl_enabled()) {
            db_resolve_real_dlsym();
            replacement = (void *) db_sdl3_dlsym;
        } else {
            continue;
        }
        void **slot = (void **) (base + relocations[i].r_offset);
        void *original = *slot;
        if (patch_slot(slot, replacement)) {
            context->relocations_patched++;
            db_log("patched libSDL3 relocation name=%s slot=%p original=%p replacement=%p",
                   name, slot, original, replacement);
        }
    }
}

static void patch_rel_table(
        uintptr_t base,
        const ElfW(Rel) *relocations,
        size_t count,
        const ElfW(Sym) *symbols,
        const char *strings,
        struct hook_context *context
) {
    if (relocations == NULL || symbols == NULL || strings == NULL) return;
    for (size_t i = 0; i < count; ++i) {
        size_t symbol_index = ELF64_R_SYM(relocations[i].r_info);
        const char *name = strings + symbols[symbol_index].st_name;
        if (name == NULL) continue;
        void *replacement = NULL;
        if (strcmp(name, "ANativeWindow_fromSurface") == 0) {
            replacement = (void *) db_sdl3_from_surface;
        } else if (strcmp(name, "dlsym") == 0 && db_sdl3_wrapped_opengl_enabled()) {
            db_resolve_real_dlsym();
            replacement = (void *) db_sdl3_dlsym;
        } else {
            continue;
        }
        void **slot = (void **) (base + relocations[i].r_offset);
        void *original = *slot;
        if (patch_slot(slot, replacement)) {
            context->relocations_patched++;
            db_log("patched libSDL3 relocation name=%s slot=%p original=%p replacement=%p",
                   name, slot, original, replacement);
        }
    }
}

static int hook_loaded_sdl3(struct dl_phdr_info *info, size_t size, void *opaque) {
    (void) size;
    struct hook_context *context = (struct hook_context *) opaque;
    const char *name = info->dlpi_name;
    if (name == NULL || strstr(name, "libSDL3.so") == NULL) return 0;
    context->libraries_seen++;

    uintptr_t base = (uintptr_t) info->dlpi_addr;
    uintptr_t image_end = base;
    const ElfW(Dyn) *dynamic = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *phdr = &info->dlpi_phdr[i];
        uintptr_t end = base + phdr->p_vaddr + phdr->p_memsz;
        if (end > image_end) image_end = end;
        if (phdr->p_type == PT_DYNAMIC) {
            dynamic = (const ElfW(Dyn) *) (base + phdr->p_vaddr);
        }
    }
    if (dynamic == NULL) return 0;

    const ElfW(Sym) *symbols = NULL;
    const char *strings = NULL;
    const void *jmprel = NULL;
    size_t pltrelsz = 0;
    ElfW(Sword) pltrel_type = DT_RELA;
    const ElfW(Rela) *rela = NULL;
    size_t relasz = 0;
    const ElfW(Rel) *rel = NULL;
    size_t relsz = 0;

    for (const ElfW(Dyn) *entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symbols = (const ElfW(Sym) *) loaded_address(base, image_end,
                        (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strings = (const char *) loaded_address(base, image_end,
                        (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (const void *) loaded_address(base, image_end,
                        (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = (size_t) entry->d_un.d_val;
                break;
            case DT_PLTREL:
                pltrel_type = (ElfW(Sword)) entry->d_un.d_val;
                break;
            case DT_RELA:
                rela = (const ElfW(Rela) *) loaded_address(base, image_end,
                        (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_RELASZ:
                relasz = (size_t) entry->d_un.d_val;
                break;
            case DT_REL:
                rel = (const ElfW(Rel) *) loaded_address(base, image_end,
                        (uintptr_t) entry->d_un.d_ptr);
                break;
            case DT_RELSZ:
                relsz = (size_t) entry->d_un.d_val;
                break;
            default:
                break;
        }
    }

    if (jmprel != NULL && pltrelsz > 0) {
        if (pltrel_type == DT_RELA) {
            patch_rela_table(base, (const ElfW(Rela) *) jmprel,
                    pltrelsz / sizeof(ElfW(Rela)), symbols, strings, context);
        } else if (pltrel_type == DT_REL) {
            patch_rel_table(base, (const ElfW(Rel) *) jmprel,
                    pltrelsz / sizeof(ElfW(Rel)), symbols, strings, context);
        }
    }
    if (rela != NULL && relasz > 0) {
        patch_rela_table(base, rela, relasz / sizeof(ElfW(Rela)),
                symbols, strings, context);
    }
    if (rel != NULL && relsz > 0) {
        patch_rel_table(base, rel, relsz / sizeof(ElfW(Rel)),
                symbols, strings, context);
    }
    return 0;
}

/* Called directly from the LWJGL ndlopen hook immediately after libSDL3.so
 * is mapped. This is earlier than SDLActivity.getNativeSurface(), so SDL's
 * internal EGL function table cannot be populated from system libEGL first. */
__attribute__((visibility("default")))
void droidbridge_sdl3_after_library_load(const char *filename, void *handle) {
    if (handle == NULL || !db_is_sdl3_library_name(filename)) return;
    db_register_sdl3_handle(handle);

    /*
     * LWJGL is now holding the exact SDL image that Minecraft will call.
     * Mark this OpenJDK-side mapping ready before any SDL_Init() invocation.
     */
    db_mark_sdl3_main_ready(handle, "lwjgl-post-dlopen");
    db_bind_active_sdl3_symbols(handle);
    // The single-loader game acquires Mesa through LWJGL's configured native
    // OpenGL handle.  Do not reopen the provider during SDL3 post-load handling:
    // on Android that code may execute in a different linker namespace.
    if (db_direct_mesa_single_loader_enabled()) {
        db_log("Freedreno Mesa single-loader active: bridge-side Mesa reopen disabled");
    } else {
        (void) db_load_sdl3_egl_provider();
        (void) db_load_sdl3_gl_wrapper();
    }

    struct hook_context context = {0, 0};
    dl_iterate_phdr(hook_loaded_sdl3, &context);
    if (context.relocations_patched > 0) {
        __atomic_add_fetch(&g_hooked_relocations, context.relocations_patched,
                           __ATOMIC_RELAXED);
    }
    (void) apply_open_gl_hints_to_all_loaded_sdl3("lwjgl-post-dlopen");
    db_log("LWJGL SDL load intercepted file=%s handle=%p libraries=%d patched=%d glSet=%p glGet=%p glProc=%p",
           filename != NULL ? filename : "<null>",
           handle,
           context.libraries_seen,
           context.relocations_patched,
           g_real_sdl_gl_set_attribute,
           g_real_sdl_gl_get_attribute,
           g_real_sdl_gl_get_proc_address);
}

/* Called from the LWJGL ndlsym hook. Returning NULL means no override. */
__attribute__((visibility("default")))
void *droidbridge_sdl3_override_lwjgl_symbol(void *handle, const char *name) {
    /* Snapshot 9+ creates a graphics bootstrap window before Minecraft's normal
     * Window. SDL Android intentionally supports one window, so the create and
     * destroy wrappers must also be available to Vulkan, not only the wrapped
     * OpenGL/context-guard paths below. */
    if (handle == NULL || name == NULL) return NULL;
    bool single_window_reuse = db_sdl3_single_window_reuse_enabled();
    bool context_guard = db_wrapped_context_guard_enabled();
    bool mobilegl = db_is_mobilegl_renderer();
    bool zink_gl_identity = db_is_vulkan_zink_renderer();
    /* MobileGL intentionally leaves DroidBridge's wrapped-context guard off so
     * libMobileGL.so can own its EGL lifecycle. Vulkan Zink similarly keeps its
     * legacy OSMesa context/presentation path; it only needs the narrow
     * SDL_GL_GetProcAddress identity bridge below. */
    if (!single_window_reuse && !context_guard && !mobilegl && !zink_gl_identity) return NULL;

    pthread_mutex_lock(&g_sdl_gl_mutex);
    bool known = db_is_known_sdl3_handle(handle);
    if (known && handle != g_active_sdl3_handle) {
        db_bind_active_sdl3_symbols(handle);
    }
    pthread_mutex_unlock(&g_sdl_gl_mutex);
    if (!known) return NULL;

    if (single_window_reuse) {
        if (strcmp(name, "SDL_CreateWindow") == 0) {
            db_log("LWJGL Snapshot 9 single-window override active name=%s handle=%p", name, handle);
            return (void *) db_sdl3_create_window;
        }
        if (strcmp(name, "SDL_CreateWindowWithProperties") == 0) {
            db_log("LWJGL Snapshot 9 single-window override active name=%s handle=%p", name, handle);
            return (void *) db_sdl3_create_window_with_properties;
        }
        if (strcmp(name, "SDL_ShowWindow") == 0) {
            return (void *) db_sdl3_show_window;
        }
        if (strcmp(name, "SDL_DestroyWindow") == 0) {
            return (void *) db_sdl3_destroy_window;
        }
        if (strcmp(name, "SDL_Quit") == 0) {
            return (void *) db_sdl3_quit;
        }
    }

    /* MobileGL does not need DroidBridge to own its EGL context lifecycle, but
     * it does need the SDL_GL attribute wrappers so RenderPearl's explicit sRGB
     * default-framebuffer request can be translated before SDL checks
     * EGL_KHR_gl_colorspace. Keep the rest of MobileGL's EGL lifecycle native. */
    if (mobilegl) {
        if (strcmp(name, "SDL_GL_SetAttribute") == 0) {
            db_log("MobileGL LWJGL symbol override active name=%s handle=%p", name, handle);
            return (void *) db_sdl3_gl_set_attribute;
        }
        if (strcmp(name, "SDL_GL_GetAttribute") == 0) {
            return (void *) db_sdl3_gl_get_attribute;
        }
    }

    if (zink_gl_identity
            && (strcmp(name, "SDL_GL_GetProcAddress") == 0
                || strcmp(name, "SDL_EGL_GetProcAddress") == 0)) {
        db_log("Vulkan Zink LWJGL proc identity override active name=%s handle=%p",
               name, handle);
        return (void *) db_sdl3_gl_get_proc_address;
    }

    if (!context_guard) return NULL;

    /* The SDL context/current-window wrappers are required by direct Freedreno
     * too. Keep wrapped OpenGL routing unchanged; db_sdl3_gl_get_proc_address()
     * still falls through to SDL for direct Mesa. */
    if (strcmp(name, "SDL_GL_SetAttribute") == 0) {
        db_log("LWJGL symbol override active name=%s handle=%p", name, handle);
        return (void *) db_sdl3_gl_set_attribute;
    }
    if (strcmp(name, "SDL_GL_GetAttribute") == 0) {
        return (void *) db_sdl3_gl_get_attribute;
    }
    if (strcmp(name, "SDL_GL_CreateContext") == 0) {
        return (void *) db_sdl3_gl_create_context;
    }
    if (strcmp(name, "SDL_GL_MakeCurrent") == 0) {
        return (void *) db_sdl3_gl_make_current;
    }
    if (strcmp(name, "SDL_GL_GetCurrentWindow") == 0) {
        return (void *) db_sdl3_gl_get_current_window;
    }
    if (strcmp(name, "SDL_GL_GetCurrentContext") == 0) {
        return (void *) db_sdl3_gl_get_current_context;
    }
    if (strcmp(name, "SDL_GL_SetSwapInterval") == 0) {
        db_log("LWJGL symbol override active name=%s handle=%p", name, handle);
        return (void *) db_sdl3_gl_set_swap_interval;
    }
    if (strcmp(name, "SDL_GL_SwapWindow") == 0) {
        db_log("LWJGL symbol override active name=%s handle=%p", name, handle);
        return (void *) db_sdl3_gl_swap_window;
    }
    if (strcmp(name, "SDL_GL_DestroyContext") == 0) {
        return (void *) db_sdl3_gl_destroy_context;
    }
    if (strcmp(name, "SDL_GL_GetProcAddress") == 0
            || strcmp(name, "SDL_EGL_GetProcAddress") == 0) {
        db_log("LWJGL symbol override active name=%s handle=%p", name, handle);
        return (void *) db_sdl3_gl_get_proc_address;
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeInstallSdl3NativeWindowHook(
        JNIEnv *env,
        jclass clazz
) {
    (void) env;
    (void) clazz;
    resolve_real_from_surface();
    __atomic_store_n(&g_bridge_enabled, 1, __ATOMIC_RELEASE);
    if (!g_logged_v12_active) {
        g_logged_v12_active = 1;
        db_log("Snapshot 4 OpenGL bridge v23 active resolver=Snapshot9-single-window+final-context-launch-live-vsync+legacy-sRGB-pass-through+OpenJDK-present");
    }

    struct hook_context context = {0, 0};
    dl_iterate_phdr(hook_loaded_sdl3, &context);
    if (context.relocations_patched > 0) {
        __atomic_add_fetch(&g_hooked_relocations, context.relocations_patched,
                           __ATOMIC_RELAXED);
    }
    (void) apply_open_gl_hints_to_all_loaded_sdl3("hook-install");
    db_log("hook scan libraries=%d patched=%d total=%d",
           context.libraries_seen,
           context.relocations_patched,
           __atomic_load_n(&g_hooked_relocations, __ATOMIC_RELAXED));
    return (context.relocations_patched > 0
            || __atomic_load_n(&g_hooked_relocations, __ATOMIC_RELAXED) > 0)
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativePublishSurface(
        JNIEnv *env,
        jclass clazz,
        jobject surface
) {
    (void) clazz;
    resolve_real_from_surface();
    if (surface == NULL || g_real_from_surface == NULL) return JNI_FALSE;

    ANativeWindow *window = g_real_from_surface(env, surface);
    if (window == NULL) {
        db_log("publish failed: ANativeWindow_fromSurface returned NULL");
        return JNI_FALSE;
    }

    /*
     * Keep the Android layer tagged as ordinary display sRGB. The EGL shim
     * separately removes SDL's sRGB *framebuffer* request for Snapshot 4, so GL
     * writes legacy display-encoded bytes while SurfaceFlinger interprets those
     * bytes as sRGB. This avoids the extra framebuffer transfer that turned the
     * Mojang red pink and raised world/menu mid-tones.
     */
    db_apply_srgb_window_dataspace(window, "publishSurface");

    pthread_mutex_lock(&g_surface_mutex);
    ANativeWindow *old = g_owned_window;
    g_owned_window = window;

    pthread_mutex_lock(&g_openjdk_pacing_mutex);
    g_openjdk_interval_reapplied_on_present = 0;
    g_openjdk_cached_egl_interval_applied = 0;
    g_openjdk_swap_interval_pending_render_reapply = 1;
    g_openjdk_next_present_ns = 0;
    pthread_mutex_unlock(&g_openjdk_pacing_mutex);

    char pointer_text[2 + sizeof(uintptr_t) * 2 + 1];
    snprintf(pointer_text, sizeof(pointer_text), "%" PRIxPTR, (uintptr_t) window);
    setenv(DB_SDL3_PTR_ENV, pointer_text, 1);
    pthread_mutex_unlock(&g_surface_mutex);

    if (old != NULL) ANativeWindow_release(old);
    db_log("published window=%p size=%dx%d",
           window, ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeClearSurface(
        JNIEnv *env,
        jclass clazz
) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_surface_mutex);
    unsetenv(DB_SDL3_PTR_ENV);
    ANativeWindow *old = g_owned_window;
    g_owned_window = NULL;
    pthread_mutex_lock(&g_openjdk_pacing_mutex);
    g_openjdk_interval_reapplied_on_present = 0;
    g_openjdk_cached_egl_interval_applied = 0;
    g_openjdk_swap_interval_pending_render_reapply = 1;
    g_openjdk_next_present_ns = 0;
    pthread_mutex_unlock(&g_openjdk_pacing_mutex);
    pthread_mutex_unlock(&g_surface_mutex);
    if (old != NULL) ANativeWindow_release(old);
    db_log("published native window cleared");
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeHasPublishedWindow(
        JNIEnv *env,
        jclass clazz
) {
    (void) env;
    (void) clazz;
    /* The embedded JVM can map its active SDL copy after ART's initial setup. */
    (void) apply_open_gl_hints_to_all_loaded_sdl3("published-window-query");
    return window_from_process_environment() != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeConfigureSdlOpenGlBridge(
        JNIEnv *env,
        jclass clazz,
        jstring egl_library,
        jstring gl_library,
        jstring renderer_kind
) {
    (void) clazz;
    if (egl_library == NULL || gl_library == NULL) return JNI_FALSE;

    const char *egl_path = (*env)->GetStringUTFChars(env, egl_library, NULL);
    const char *gl_path = (*env)->GetStringUTFChars(env, gl_library, NULL);
    const char *kind = renderer_kind != NULL
            ? (*env)->GetStringUTFChars(env, renderer_kind, NULL)
            : NULL;
    if (egl_path == NULL || gl_path == NULL) {
        if (egl_path != NULL) (*env)->ReleaseStringUTFChars(env, egl_library, egl_path);
        if (gl_path != NULL) (*env)->ReleaseStringUTFChars(env, gl_library, gl_path);
        if (kind != NULL) (*env)->ReleaseStringUTFChars(env, renderer_kind, kind);
        return JNI_FALSE;
    }

    /*
     * Keep process environment in sync for code that reads hints before calling
     * SDL_GetHint(), but do not rely on it as the authoritative route.
     */
    setenv("SDL_VIDEO_FORCE_EGL", "1", 1);
    setenv("SDL_EGL_LIBRARY", egl_path, 1);
    setenv("SDL_OPENGL_LIBRARY", gl_path, 1);
    setenv("SDL_VIDEO_EGL_DRIVER", egl_path, 1);
    setenv("SDL_VIDEO_GL_DRIVER", gl_path, 1);
    setenv("SDL_VIDEO_EGL_ALLOW_GETDISPLAY_FALLBACK", "1", 1);
    setenv("DROIDBRIDGE_SDL3_WRAPPED_OPENGL", "1", 1);
    setenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY", gl_path, 1);
    /* Only OpenJDK may dlopen Mesa in 26.3's single-loader route.  Loading
     * it here through ART's JNI bridge creates an independent Gallium image.
     * Preserve SDL's hints; its own loader resolves this same path later. */
    if (!db_truthy_env("DROIDBRIDGE_SDL3_MESA_SINGLE_LOADER")) {
        (void) db_load_sdl3_egl_provider();
        (void) db_load_sdl3_gl_wrapper();
    }
    if (kind != NULL && kind[0] != '\0') {
        setenv("DROIDBRIDGE_SDL3_OPENGL_KIND", kind, 1);
    }

    /* The TextureView may have been published before renderer selection was
     * finalized. Reapply the shared SDL3 OpenGL dataspace now that the route is
     * authoritative, while holding our own ANativeWindow reference. */
    ANativeWindow *configured_window = NULL;
    pthread_mutex_lock(&g_surface_mutex);
    if (g_owned_window != NULL) {
        configured_window = g_owned_window;
        ANativeWindow_acquire(configured_window);
    }
    pthread_mutex_unlock(&g_surface_mutex);
    if (configured_window != NULL) {
        db_apply_srgb_window_dataspace(configured_window, "configureSdlOpenGlBridge");
        ANativeWindow_release(configured_window);
    }

    bool api_ready = ensure_sdl_hint_api();
    bool force_ok = false;
    bool egl_ok = false;
    bool gl_ok = false;
    if (api_ready) {
        force_ok = set_sdl_hint_override("SDL_VIDEO_FORCE_EGL", "1");
        egl_ok = set_sdl_hint_override("SDL_EGL_LIBRARY", egl_path);
        gl_ok = set_sdl_hint_override("SDL_OPENGL_LIBRARY", gl_path);
    }

    const char *actual_force = g_sdl_get_hint != NULL
            ? g_sdl_get_hint("SDL_VIDEO_FORCE_EGL") : NULL;
    const char *actual_egl = g_sdl_get_hint != NULL
            ? g_sdl_get_hint("SDL_EGL_LIBRARY") : NULL;
    const char *actual_gl = g_sdl_get_hint != NULL
            ? g_sdl_get_hint("SDL_OPENGL_LIBRARY") : NULL;

    bool all_mappings_verified =
            apply_open_gl_hints_to_all_loaded_sdl3("explicit-configure");
    bool verified = api_ready && force_ok && egl_ok && gl_ok
            && all_mappings_verified;
    if (actual_force != NULL && strcmp(actual_force, "1") != 0) verified = false;
    if (actual_egl != NULL && strcmp(actual_egl, egl_path) != 0) verified = false;
    if (actual_gl != NULL && strcmp(actual_gl, gl_path) != 0) verified = false;

    db_log("direct OpenGL hints ready=%d force=%d egl=%d gl=%d allMappings=%d verified=%d kind=%s requestedEgl=%s requestedGl=%s actualForce=%s actualEgl=%s actualGl=%s",
           api_ready ? 1 : 0,
           force_ok ? 1 : 0,
           egl_ok ? 1 : 0,
           gl_ok ? 1 : 0,
           all_mappings_verified ? 1 : 0,
           verified ? 1 : 0,
           kind != NULL ? kind : "unknown",
           egl_path,
           gl_path,
           actual_force != NULL ? actual_force : "<unavailable>",
           actual_egl != NULL ? actual_egl : "<unavailable>",
           actual_gl != NULL ? actual_gl : "<unavailable>");

    (*env)->ReleaseStringUTFChars(env, egl_library, egl_path);
    (*env)->ReleaseStringUTFChars(env, gl_library, gl_path);
    if (kind != NULL) (*env)->ReleaseStringUTFChars(env, renderer_kind, kind);
    return verified ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeQuerySdlMouseState(
        JNIEnv *env,
        jclass clazz,
        jfloatArray out_state
) {
    (void) clazz;
    if (out_state == NULL || (*env)->GetArrayLength(env, out_state) < 9) {
        return JNI_FALSE;
    }
    if (!ensure_sdl_mouse_api()) return JNI_FALSE;

    SDL_Window *window = focused_sdl_window();
    if (window == NULL) return JNI_FALSE;

    int window_width = 0;
    int window_height = 0;
    if (!g_sdl_get_window_size(window, &window_width, &window_height)
            || window_width <= 0 || window_height <= 0) {
        return JNI_FALSE;
    }

    int pixel_width = window_width;
    int pixel_height = window_height;
    if (g_sdl_get_window_size_in_pixels != NULL) {
        int queried_width = 0;
        int queried_height = 0;
        if (g_sdl_get_window_size_in_pixels(
                window, &queried_width, &queried_height)
                && queried_width > 0 && queried_height > 0) {
            pixel_width = queried_width;
            pixel_height = queried_height;
        }
    }

    float window_display_scale = 1.0f;
    if (g_sdl_get_window_display_scale != NULL) {
        float queried_scale = g_sdl_get_window_display_scale(window);
        if (queried_scale > 0.0f && queried_scale <= 8.0f) {
            window_display_scale = queried_scale;
        }
    }

    float window_pixel_density = 1.0f;
    if (g_sdl_get_window_pixel_density != NULL) {
        float queried_density = g_sdl_get_window_pixel_density(window);
        if (queried_density > 0.0f && queried_density <= 8.0f) {
            window_pixel_density = queried_density;
        }
    } else if (window_width > 0 && window_height > 0) {
        float density_x = (float) pixel_width / (float) window_width;
        float density_y = (float) pixel_height / (float) window_height;
        if (density_x > 0.0f && density_y > 0.0f) {
            /* Geometric mean avoids aspect-ratio rounding bias. */
            window_pixel_density = __builtin_sqrtf(density_x * density_y);
        }
    }

    float mouse_x = 0.0f;
    float mouse_y = 0.0f;
    uint32_t buttons = g_sdl_get_mouse_state(&mouse_x, &mouse_y);
    jfloat values[9] = {
            mouse_x,
            mouse_y,
            (jfloat) window_width,
            (jfloat) window_height,
            (jfloat) pixel_width,
            (jfloat) pixel_height,
            (jfloat) buttons,
            window_display_scale,
            window_pixel_density
    };
    (*env)->SetFloatArrayRegion(env, out_state, 0, 9, values);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeWarpSdlMouse(
        JNIEnv *env,
        jclass clazz,
        jfloat x,
        jfloat y
) {
    (void) env;
    (void) clazz;
    if (!ensure_sdl_mouse_api() || g_sdl_warp_mouse_in_window == NULL) {
        return JNI_FALSE;
    }
    SDL_Window *window = focused_sdl_window();
    if (window == NULL) return JNI_FALSE;

    if (!__atomic_exchange_n(&g_sdl_warp_hint_set, 1, __ATOMIC_ACQ_REL)
            && g_sdl_set_hint != NULL) {
        /* Synthetic menu recentering must never trigger SDL's relative-warp emulation. */
        (void) g_sdl_set_hint("SDL_MOUSE_EMULATE_WARP_WITH_RELATIVE", "0");
    }
    g_sdl_warp_mouse_in_window(window, x, y);
    return JNI_TRUE;
}

/* Google Play package aliases. The shared native implementation remains the
 * GitHub/original package entry point; these wrappers preserve GPlay JNI. */
JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeInstallSdl3NativeWindowHook(
        JNIEnv *env, jclass clazz) {
    return Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeInstallSdl3NativeWindowHook(env, clazz);
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativePublishSurface(
        JNIEnv *env, jclass clazz, jobject surface) {
    return Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativePublishSurface(env, clazz, surface);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeClearSurface(
        JNIEnv *env, jclass clazz) {
    Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeClearSurface(env, clazz);
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeHasPublishedWindow(
        JNIEnv *env, jclass clazz) {
    return Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeHasPublishedWindow(env, clazz);
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeConfigureSdlOpenGlBridge(
        JNIEnv *env, jclass clazz, jstring egl_library, jstring gl_library, jstring renderer_kind) {
    return Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeConfigureSdlOpenGlBridge(
            env, clazz, egl_library, gl_library, renderer_kind);
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeQuerySdlMouseState(
        JNIEnv *env, jclass clazz, jfloatArray out_state) {
    return Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeQuerySdlMouseState(
            env, clazz, out_state);
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeWarpSdlMouse(
        JNIEnv *env, jclass clazz, jfloat x, jfloat y) {
    return Java_ca_dnamobile_javalauncher_runtime_DroidBridgeSDL3NativeWindowBridge_nativeWarpSdlMouse(
            env, clazz, x, y);
}
