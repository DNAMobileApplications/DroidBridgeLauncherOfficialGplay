/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * SDL3 EGL compatibility shim for Minecraft 26.3 Snapshot 4 wrapped OpenGL renderers.
 *
 * Snapshot 4 asks SDL3 for a desktop OpenGL EGL configuration/context. Android's
 * system EGL only exposes OpenGL ES, while DroidBridge wrappers such as MobileGlues, Krypton, and MobileGL supply desktop GL
 * entry points over an Android GLES context. This library is selected for the
 * SDL3 + wrapped renderer OpenGL route and for NeoForge/FML's short-lived OpenGL
 * early display when Minecraft 26.3 will switch to Vulkan afterward. It translates
 * SDL's desktop EGL request to GLES 3, keeps the selected wrapper on the established
 * Android EGL lifecycle, and falls back to Android EGL when the wrapper does not
 * export an EGL entry point.
 */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif
#ifndef EGL_OPENGL_BIT
#define EGL_OPENGL_BIT 0x0008
#endif
#ifndef EGL_OPENGL_API
#define EGL_OPENGL_API 0x30A2
#endif
#ifndef EGL_OPENGL_ES_API
#define EGL_OPENGL_ES_API 0x30A0
#endif
#ifndef EGL_CONTEXT_CLIENT_VERSION
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#endif
#ifndef EGL_CONTEXT_MAJOR_VERSION_KHR
#define EGL_CONTEXT_MAJOR_VERSION_KHR 0x3098
#endif
#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif
#ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
#endif
#ifndef EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_KHR
#define EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_KHR 0x31BD
#endif
#ifndef EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT
#define EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT 0x30BF
#endif
#ifndef EGL_CONTEXT_OPENGL_NO_ERROR_KHR
#define EGL_CONTEXT_OPENGL_NO_ERROR_KHR 0x31B3
#endif
#ifndef EGL_CONTEXT_FLAGS_KHR
#define EGL_CONTEXT_FLAGS_KHR 0x30FC
#endif
#ifndef EGL_COLOR_COMPONENT_TYPE_EXT
#define EGL_COLOR_COMPONENT_TYPE_EXT 0x3339
#endif
#ifndef EGL_CONTEXT_CLIENT_TYPE
#define EGL_CONTEXT_CLIENT_TYPE 0x3097
#endif
#ifndef EGL_CONFORMANT
#define EGL_CONFORMANT 0x3042
#endif
#ifndef EGL_CLIENT_APIS
#define EGL_CLIENT_APIS 0x308D
#endif
#ifndef EGL_GL_COLORSPACE_KHR
#define EGL_GL_COLORSPACE_KHR 0x309D
#endif
#ifndef EGL_GL_COLORSPACE_SRGB_KHR
#define EGL_GL_COLORSPACE_SRGB_KHR 0x3089
#endif

/* OpenGL tokens used while LWJGL builds its capabilities table. */
#define DB_GL_VERSION 0x1F02u
#define DB_GL_EXTENSIONS 0x1F03u
#define DB_GL_MAJOR_VERSION 0x821Bu
#define DB_GL_MINOR_VERSION 0x821Cu
#define DB_BLOCKED_BUFFER_STORAGE_ARB "GL_ARB_buffer_storage"
#define DB_BLOCKED_BUFFER_STORAGE_EXT "GL_EXT_buffer_storage"
#define DB_DISABLED_EXTENSION_NAME "GL_DROIDBRIDGE_buffer_storage_disabled"

typedef const unsigned char *(*DbGlGetStringFn)(unsigned int);
typedef const unsigned char *(*DbGlGetStringiFn)(unsigned int, unsigned int);
typedef void (*DbGlGetIntegervFn)(unsigned int, int *);
typedef unsigned int (*DbGlCreateShaderFn)(unsigned int);
typedef void (*DbGlShaderSourceFn)(unsigned int, int, const char *const *, const int *);
typedef void (*DbGlCompileShaderFn)(unsigned int);
typedef unsigned int (*DbGlCreateProgramFn)(void);
typedef unsigned int (*DbGlGetErrorFn)(void);

static const char *DB_TAG = "DroidBridgeSDL3EGL";
static pthread_mutex_t g_load_lock = PTHREAD_MUTEX_INITIALIZER;
static void *g_system_egl = NULL;
static void *g_gl_wrapper = NULL;
static void *g_simplefpe = NULL;
static int g_system_load_attempted = 0;
static int g_gl_wrapper_load_attempted = 0;
static int g_simplefpe_load_attempted = 0;
static int g_desktop_api_requested = 0;
static thread_local EGLint g_deferred_error = EGL_SUCCESS;
static int g_logged_choose_translation = 0;
static int g_logged_context_translation = 0;
static int g_logged_proc_provider = 0;
static int g_logged_client_apis = 0;
static int g_logged_backend_egl = 0;
static int g_logged_srgb_surface = 0;
static int g_logged_srgb_surface_fallback = 0;
static DbGlGetStringFn g_real_gl_get_string = NULL;
static DbGlGetStringiFn g_real_gl_get_string_i = NULL;
static DbGlGetIntegervFn g_real_gl_get_integerv = NULL;
static DbGlCreateShaderFn g_real_gl_create_shader = NULL;
static DbGlShaderSourceFn g_real_gl_shader_source = NULL;
static DbGlCompileShaderFn g_real_gl_compile_shader = NULL;
static DbGlCreateProgramFn g_real_gl_create_program = NULL;
static pthread_mutex_t g_gl_capability_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_filtered_gl_version[256];
static char *g_filtered_gl_extensions = NULL;
static int g_filtered_gl_extensions_ready = 0;
static int g_logged_buffer_filter = 0;
static int g_logged_version_filter = 0;
static int g_logged_blocked_buffer_proc = 0;
static int g_logged_mobileglues_shader_normalization = 0;
static int g_logged_wrapped_create_shader = 0;
static int g_logged_wrapped_create_program = 0;
static int g_logged_krypton_context_cache = 0;
static int g_logged_krypton_context_restore = 0;
static int g_logged_krypton_context_unbind = 0;
static int g_logged_krypton_first_swap = 0;
static int g_logged_krypton_first_damage_swap = 0;
static int g_logged_krypton_swap_failure = 0;
static thread_local EGLDisplay g_krypton_cached_display = EGL_NO_DISPLAY;
static thread_local EGLSurface g_krypton_cached_draw = EGL_NO_SURFACE;
static thread_local EGLSurface g_krypton_cached_read = EGL_NO_SURFACE;
static thread_local EGLContext g_krypton_cached_context = EGL_NO_CONTEXT;
static thread_local int g_krypton_cached_context_live = 0;
static thread_local int g_krypton_allow_context_release = 0;
static thread_local int g_krypton_restore_guard = 0;
static thread_local int g_krypton_force_rebind_pending = 0;

static void db_log(int priority, const char *fmt, ...) {
    char message[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(message, sizeof(message), fmt, ap);
    va_end(ap);
    __android_log_print(priority, DB_TAG, "%s", message);
    fprintf(stderr, "DroidBridgeSDL3EGL: %s\n", message);
}

static int db_truthy(const char *key) {
    const char *value = getenv(key);
    if (value == NULL || value[0] == '\0') return 0;
    return strcmp(value, "0") != 0
            && strcasecmp(value, "false") != 0
            && strcasecmp(value, "no") != 0
            && strcasecmp(value, "off") != 0;
}

static int db_compat_enabled(void) {
    return db_truthy("DROIDBRIDGE_SDL3_WRAPPED_OPENGL")
            || db_truthy("DROIDBRIDGE_SDL3_MOBILEGLUES_OPENGL");
}

__attribute__((constructor))
static void db_sdl3_egl_provider_loaded(void) {
    db_log(ANDROID_LOG_INFO,
           "dedicated EGL compatibility provider loaded compat=%d pid=%d",
           db_compat_enabled(), getpid());
}

static void *db_try_dlopen(const char *path, int flags, const char *kind) {
    if (path == NULL || path[0] == '\0') return NULL;
    dlerror();
    void *handle = dlopen(path, flags);
    if (handle != NULL) {
        db_log(ANDROID_LOG_INFO, "loaded %s provider=%s handle=%p", kind, path, handle);
        return handle;
    }
    const char *error = dlerror();
    db_log(ANDROID_LOG_WARN, "failed loading %s provider=%s error=%s",
           kind, path, error != NULL ? error : "unknown");
    return NULL;
}

static void *db_load_system_egl_locked(void) {
    if (g_system_egl != NULL || g_system_load_attempted) return g_system_egl;
    g_system_load_attempted = 1;

    /* libEGL.so is an NDK public library and is the correct owner of the
     * Android window surface/GLES context. Use RTLD_LOCAL to avoid replacing
     * this shim's exported EGL symbols in other consumers. */
    g_system_egl = db_try_dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL, "Android EGL");
#if defined(__aarch64__) || defined(__x86_64__)
    if (g_system_egl == NULL) {
        g_system_egl = db_try_dlopen("/system/lib64/libEGL.so", RTLD_NOW | RTLD_LOCAL, "Android EGL");
    }
#else
    if (g_system_egl == NULL) {
        g_system_egl = db_try_dlopen("/system/lib/libEGL.so", RTLD_NOW | RTLD_LOCAL, "Android EGL");
    }
#endif
    return g_system_egl;
}

static void *db_load_system_egl(void) {
    pthread_mutex_lock(&g_load_lock);
    void *handle = db_load_system_egl_locked();
    pthread_mutex_unlock(&g_load_lock);
    return handle;
}

static void *db_published_ltw_handle(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind == NULL || strcasecmp(kind, "ltw") != 0) return NULL;

    const char *text = getenv("DROIDBRIDGE_LTW_OPENJDK_HANDLE");
    const char *path = getenv("DROIDBRIDGE_LTW_OPENJDK_PATH");
    const char *source = "OpenJDK";

    /*
     * RenderPearl compares the OpenGL loader's glGetError pointer with
     * SDL_GL_GetProcAddress("glGetError"). The LWJGL RenderSpec load happens
     * before SDL creates its GL backend, so prefer that exact LTW handle.
     * Falling back to ART's earlier preload is safe for older paths, but using
     * two LTW instances on Android 10 gives different wrapper trampoline
     * addresses and fails RenderPearl's pointer-identity check.
     */
    if (text == NULL || text[0] == '\0') {
        text = getenv("DROIDBRIDGE_LTW_PRELOADED_HANDLE");
        path = getenv("DROIDBRIDGE_LTW_PRELOADED_PATH");
        source = "ART-preloaded";
    }
    if (text == NULL || text[0] == '\0') return NULL;

    char *end = NULL;
    unsigned long long raw = strtoull(text, &end, 0);
    if (raw == 0 || end == text || (end != NULL && *end != '\0')) {
        db_log(ANDROID_LOG_WARN,
               "invalid %s LTW handle value=%s", source, text);
        return NULL;
    }

    void *handle = (void *)(uintptr_t)raw;
    dlerror();
    void *probe = dlsym(handle, "eglGetProcAddress");
    const char *error = dlerror();
    if (probe == NULL || error != NULL) {
        db_log(ANDROID_LOG_WARN,
               "%s LTW handle rejected handle=%p path=%s error=%s",
               source,
               handle,
               path != NULL && path[0] != '\0' ? path : "<unknown>",
               error != NULL ? error : "eglGetProcAddress missing");
        return NULL;
    }

    db_log(ANDROID_LOG_INFO,
           "reusing %s LTW provider handle=%p path=%s eglGetProcAddress=%p",
           source,
           handle,
           path != NULL && path[0] != '\0' ? path : "<unknown>",
           probe);
    return handle;
}

static void *db_load_gl_wrapper_locked(void) {
    if (g_gl_wrapper != NULL || g_gl_wrapper_load_attempted) return g_gl_wrapper;
    g_gl_wrapper_load_attempted = 1;

    /* Android 10 cannot safely reopen app-private LTW from every linker
     * namespace. Prefer the exact LTW handle already selected by LWJGL; fall
     * back to the ART-side preload only when the OpenJDK handle is unavailable. */
    g_gl_wrapper = db_published_ltw_handle();
    if (g_gl_wrapper != NULL) return g_gl_wrapper;

    const char *absolute = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    if (absolute == NULL || absolute[0] == '\0') {
        absolute = getenv("DROIDBRIDGE_SDL3_MOBILEGLUES_LIBRARY");
    }
    if (absolute != NULL && absolute[0] != '\0') {
        g_gl_wrapper = db_try_dlopen(
                absolute, RTLD_NOW | RTLD_GLOBAL, "wrapped OpenGL");
    }

    if (g_gl_wrapper == NULL) {
        const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
        if (kind != NULL && strcasecmp(kind, "krypton") == 0) {
            g_gl_wrapper = db_try_dlopen(
                    "libng_gl4es.so", RTLD_NOW | RTLD_GLOBAL, "Krypton OpenGL");
        } else if (kind != NULL && strcasecmp(kind, "ltw") == 0) {
            g_gl_wrapper = db_try_dlopen(
                    "libltw.so", RTLD_NOW | RTLD_GLOBAL, "LTW OpenGL");
        } else if (kind != NULL && strcasecmp(kind, "mobilegl") == 0) {
            g_gl_wrapper = db_try_dlopen(
                    "libMobileGL.so", RTLD_NOW | RTLD_GLOBAL, "MobileGL OpenGL");
        } else {
            g_gl_wrapper = db_try_dlopen(
                    "libmobileglues.so", RTLD_NOW | RTLD_GLOBAL, "MobileGlues OpenGL");
        }
    }
    return g_gl_wrapper;
}

static void *db_load_gl_wrapper(void) {
    pthread_mutex_lock(&g_load_lock);
    void *handle = db_load_gl_wrapper_locked();
    pthread_mutex_unlock(&g_load_lock);
    return handle;
}

static void *db_load_simplefpe_locked(void) {
    if (!db_truthy("DROIDBRIDGE_SIMPLEFPE_ACTIVE")) return NULL;
    if (g_simplefpe != NULL || g_simplefpe_load_attempted) return g_simplefpe;
    g_simplefpe_load_attempted = 1;

    const char *path = getenv("DROIDBRIDGE_SIMPLEFPE_LIBRARY");
    if (path == NULL || path[0] == '\0') path = "libSimpleFPEWrapper.so";
    g_simplefpe = db_try_dlopen(path, RTLD_NOW | RTLD_LOCAL, "SimpleFPEWrapper (LOCAL)");
    if (g_simplefpe != NULL) {
        db_log(ANDROID_LOG_INFO,
               "SimpleFPEWrapper GL layer active provider=%p path=%s backend=%s",
               g_simplefpe,
               path,
               getenv("SFPEW_EGL") != NULL ? getenv("SFPEW_EGL") : "<default>");
    }
    return g_simplefpe;
}

static void *db_load_simplefpe(void) {
    pthread_mutex_lock(&g_load_lock);
    void *handle = db_load_simplefpe_locked();
    pthread_mutex_unlock(&g_load_lock);
    return handle;
}

static void *db_system_symbol_direct(const char *name) {
    /* Keep EGL/context lifecycle on MobileGlues. SimpleFPEWrapper lazily
     * initializes its backend GL table on first wrapper GL use; invoking its
     * eglMakeCurrent before a context is current can initialize that table too
     * early on Android. Only GL proc lookup below is layered through SFPEW. */
    /*
     * Preserve the established MobileGlues/Krypton EGL lifecycle first. Those
     * wrappers may attach translator state during eglCreateContext/eglMakeCurrent;
     * creating the context behind their backs can leave desktop GL procedures with
     * no matching wrapper context. The attributes reaching this function have
     * already been translated by the compatibility provider, so the wrapper sees a
     * normal Android GLES request rather than SDL's unsupported desktop request.
     */
    if (db_compat_enabled()) {
        void *wrapper = db_load_gl_wrapper();
        if (wrapper != NULL) {
            void *symbol = dlsym(wrapper, name);
            if (symbol != NULL) {
                if (!g_logged_backend_egl) {
                    g_logged_backend_egl = 1;
                    db_log(ANDROID_LOG_INFO,
                           "underlying EGL calls routed through wrapped OpenGL provider firstSymbol=%s",
                           name);
                }
                return symbol;
            }
        }
    }

    void *handle = db_load_system_egl();
    if (handle != NULL) {
        void *symbol = dlsym(handle, name);
        if (symbol != NULL) {
            if (!g_logged_backend_egl) {
                g_logged_backend_egl = 1;
                db_log(ANDROID_LOG_INFO,
                       "underlying EGL calls routed through Android EGL fallback firstSymbol=%s",
                       name);
            }
            return symbol;
        }
    }
    return NULL;
}

typedef __eglMustCastToProperFunctionPointerType (*DbEglGetProcAddressFn)(const char *);

static void *db_system_symbol(const char *name) {
    void *symbol = db_system_symbol_direct(name);
    if (symbol != NULL || strcmp(name, "eglGetProcAddress") == 0) return symbol;
    DbEglGetProcAddressFn get_proc =
            (DbEglGetProcAddressFn) db_system_symbol_direct("eglGetProcAddress");
    return get_proc != NULL ? (void *) get_proc(name) : NULL;
}

static void *db_simplefpe_gl_proc(const char *name) {
    if (name == NULL || name[0] != 'g' || name[1] != 'l') return NULL;
    void *handle = db_load_simplefpe();
    if (handle == NULL) return NULL;

    DbEglGetProcAddressFn get_proc =
            (DbEglGetProcAddressFn) dlsym(handle, "eglGetProcAddress");
    if (get_proc != NULL) {
        void *symbol = (void *) get_proc(name);
        if (symbol != NULL) return symbol;
    }
    return dlsym(handle, name);
}

static int db_is_gl_proc(const char *name) {
    return name != NULL && name[0] == 'g' && name[1] == 'l';
}

static EGLint db_take_system_error(void);
static int db_is_krypton_renderer(void);
static int db_is_mobileglues_renderer(void);
static int db_is_mobilegl_renderer(void);
static int db_is_ltw_renderer(void);
static int db_context_guard_enabled(void);
static const char *db_context_guard_name(void);

static void db_clear_krypton_context_cache(void) {
    g_krypton_cached_display = EGL_NO_DISPLAY;
    g_krypton_cached_draw = EGL_NO_SURFACE;
    g_krypton_cached_read = EGL_NO_SURFACE;
    g_krypton_cached_context = EGL_NO_CONTEXT;
    g_krypton_cached_context_live = 0;
    g_krypton_force_rebind_pending = 0;
}

static EGLContext db_underlying_current_context(void) {
    typedef EGLContext (*Fn)(void);
    Fn fn = (Fn) db_system_symbol_direct("eglGetCurrentContext");
    return fn != NULL ? fn() : EGL_NO_CONTEXT;
}

static EGLBoolean db_rebind_krypton_context(const char *reason, int force_cycle) {
    if (!db_context_guard_enabled() || !g_krypton_cached_context_live
            || g_krypton_restore_guard) {
        return EGL_TRUE;
    }

    EGLContext current = db_underlying_current_context();
    if (!force_cycle && !g_krypton_force_rebind_pending
            && current == g_krypton_cached_context) {
        return EGL_TRUE;
    }

    typedef EGLBoolean (*BindFn)(EGLenum);
    typedef EGLBoolean (*MakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    BindFn bind = (BindFn) db_system_symbol_direct("eglBindAPI");
    MakeCurrentFn make_current =
            (MakeCurrentFn) db_system_symbol_direct("eglMakeCurrent");
    if (make_current == NULL) return EGL_FALSE;

    g_krypton_restore_guard = 1;
    if (bind != NULL) (void) bind(EGL_OPENGL_ES_API);

    /* Do not unbind first. GL4ES stores translator state in thread-local
     * storage, and an unbind/rebind cycle can leave Android EGL current while
     * GL4ES itself reports no current context. SDL3 now owns the cached
     * SDL_GLContext; this layer only repairs a genuinely missing EGL binding. */
    (void) force_cycle;
    EGLBoolean result = make_current(
            g_krypton_cached_display,
            g_krypton_cached_draw,
            g_krypton_cached_read,
            g_krypton_cached_context);
    EGLint restore_error = result == EGL_TRUE ? EGL_SUCCESS : db_take_system_error();
    g_krypton_restore_guard = 0;

    if (result == EGL_TRUE) {
        g_krypton_force_rebind_pending = 0;
        if (!g_logged_krypton_context_restore) {
            g_logged_krypton_context_restore = 1;
            db_log(ANDROID_LOG_INFO,
                   "%s current context restored without unbind reason=%s context=%p draw=%p",
                   db_context_guard_name(), reason != NULL ? reason : "unknown",
                   g_krypton_cached_context,
                   g_krypton_cached_draw);
        }
    } else {
        g_krypton_force_rebind_pending = 1;
        db_log(ANDROID_LOG_ERROR,
               "%s current context restore failed reason=%s context=%p error=0x%x",
               db_context_guard_name(), reason != NULL ? reason : "unknown",
               g_krypton_cached_context,
               restore_error);
    }
    return result;
}

static EGLBoolean db_restore_krypton_context_if_needed(const char *reason) {
    return db_rebind_krypton_context(reason, 0);
}

static int db_glsl_ident_char(char c) {
    return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '_';
}

static int db_mobileglues_sanitize_shader_identifiers(char *source, size_t length) {
    if (source == NULL || length < 8u
            || !db_truthy("DROIDBRIDGE_MOBILEGLUES_SHADER_IDENTIFIER_FIX")) {
        return 0;
    }
    static const char needle[] = "_uniform";
    static const char replacement[] = "_db_uvar";
    int replacements = 0;
    for (size_t i = 0; i + 8u <= length; ++i) {
        if (memcmp(source + i, needle, 8u) != 0) continue;
        int left_ok = i == 0u || !db_glsl_ident_char(source[i - 1u]);
        int right_ok = i + 8u == length || !db_glsl_ident_char(source[i + 8u]);
        if (!left_ok || !right_ok) continue;
        memcpy(source + i, replacement, 8u);
        replacements++;
        i += 7u;
    }
    return replacements;
}

static unsigned int db_wrapped_gl_error(void) {
    DbGlGetErrorFn fn = (DbGlGetErrorFn) db_simplefpe_gl_proc("glGetError");
    if (fn == NULL) {
        void *handle = db_load_gl_wrapper();
        fn = handle != NULL ? (DbGlGetErrorFn) dlsym(handle, "glGetError") : NULL;
    }
    return fn != NULL ? fn() : 0u;
}

static unsigned int db_krypton_gl_create_shader_guard(unsigned int type) {
    (void) db_restore_krypton_context_if_needed("glCreateShader");
    unsigned int shader = g_real_gl_create_shader != NULL
            ? g_real_gl_create_shader(type) : 0u;
    if (!g_logged_wrapped_create_shader || shader == 0u) {
        g_logged_wrapped_create_shader = 1;
        unsigned int error = shader == 0u ? db_wrapped_gl_error() : 0u;
        db_log(shader != 0u ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
               "%s glCreateShader type=0x%x result=%u current=%p error=0x%x",
               db_context_guard_name(), type, shader,
               db_underlying_current_context(), error);
    }
    return shader;
}

static void db_mobileglues_gl_shader_source_normalized(
        unsigned int shader, int count, const char *const *strings, const int *lengths) {
    (void) db_restore_krypton_context_if_needed("glShaderSource");
    if (g_real_gl_shader_source == NULL || count <= 0 || strings == NULL) {
        if (g_real_gl_shader_source != NULL) {
            g_real_gl_shader_source(shader, count, strings, lengths);
        }
        return;
    }
    size_t total = 0;
    for (int i = 0; i < count; ++i) {
        if (strings[i] == NULL) continue;
        size_t length = lengths != NULL && lengths[i] >= 0
                ? (size_t) lengths[i] : strlen(strings[i]);
        if (length > (64u * 1024u * 1024u)
                || total > (64u * 1024u * 1024u) - length) {
            g_real_gl_shader_source(shader, count, strings, lengths);
            return;
        }
        total += length;
    }
    char *combined = (char *) malloc(total + 1u);
    if (combined == NULL) {
        g_real_gl_shader_source(shader, count, strings, lengths);
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
        db_log(ANDROID_LOG_INFO,
               "Snapshot 8 MobileGlues shader sanitized shader=%u renamed _uniform tokens=%d",
               shader, renamed);
    }
    const char *one = combined;
    g_real_gl_shader_source(shader, 1, &one, NULL);
    if (!g_logged_mobileglues_shader_normalization) {
        g_logged_mobileglues_shader_normalization = 1;
        db_log(ANDROID_LOG_INFO,
               "MobileGlues shader source normalized shader=%u parts=%d bytes=%zu",
               shader, count, output);
    }
    free(combined);
}

static void db_krypton_gl_shader_source_guard(
        unsigned int shader, int count, const char *const *strings, const int *lengths) {
    (void) db_restore_krypton_context_if_needed("glShaderSource");
    if (g_real_gl_shader_source != NULL) {
        g_real_gl_shader_source(shader, count, strings, lengths);
    }
}

static void db_krypton_gl_compile_shader_guard(unsigned int shader) {
    (void) db_restore_krypton_context_if_needed("glCompileShader");
    if (g_real_gl_compile_shader != NULL) g_real_gl_compile_shader(shader);
}

static unsigned int db_krypton_gl_create_program_guard(void) {
    (void) db_restore_krypton_context_if_needed("glCreateProgram");
    unsigned int program = g_real_gl_create_program != NULL
            ? g_real_gl_create_program() : 0u;
    if (!g_logged_wrapped_create_program || program == 0u) {
        g_logged_wrapped_create_program = 1;
        unsigned int error = program == 0u ? db_wrapped_gl_error() : 0u;
        db_log(program != 0u ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
               "%s glCreateProgram result=%u current=%p error=0x%x",
               db_context_guard_name(), program,
               db_underlying_current_context(), error);
    }
    return program;
}

static int db_disable_persistent_mapping(void) {
    /*
     * MobileGlues/Krypton use DroidBridge's mutable-buffer compatibility path.
     * LTW has its own coherent dynamic-storage implementation and must retain
     * buffer_storage on Minecraft 26+, matching its working Android profile.
     */
    if (db_truthy("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING")) return 1;
    return db_compat_enabled() && !db_is_ltw_renderer() && !db_is_mobilegl_renderer();
}

static int db_is_krypton_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    return kind != NULL && strcasecmp(kind, "krypton") == 0;
}

static int db_is_mobileglues_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcasecmp(kind, "mobileglues") == 0) return 1;
    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    return path != NULL && strstr(path, "mobileglues") != NULL;
}

static int db_is_mobilegl_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcasecmp(kind, "mobilegl") == 0) return 1;
    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    if (path == NULL) return 0;
    const char *base = strrchr(path, '/');
    base = base != NULL ? base + 1 : path;
    return strcasecmp(base, "libMobileGL.so") == 0;
}

static int db_is_ltw_renderer(void) {
    const char *kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    if (kind != NULL && strcasecmp(kind, "ltw") == 0) return 1;
    const char *path = getenv("DROIDBRIDGE_SDL3_OPENGL_LIBRARY");
    return path != NULL && strstr(path, "libltw") != NULL;
}

static int db_context_guard_enabled(void) {
    return db_is_krypton_renderer()
            || db_is_mobileglues_renderer()
            || db_is_mobilegl_renderer()
            || db_is_ltw_renderer();
}

static const char *db_context_guard_name(void) {
    if (db_is_mobileglues_renderer()) return "MobileGlues";
    if (db_is_mobilegl_renderer()) return "MobileGL";
    if (db_is_ltw_renderer()) return "LTW";
    return "Krypton";
}

static int db_is_blocked_buffer_storage_proc(const char *name) {
    if (name == NULL) return 0;
    return strcmp(name, "glBufferStorage") == 0
            || strcmp(name, "glBufferStorageEXT") == 0
            || strcmp(name, "glNamedBufferStorage") == 0
            || strcmp(name, "glNamedBufferStorageEXT") == 0;
}

static int db_blocked_buffer_storage_extension(const char *extension) {
    if (extension == NULL) return 0;
    return strcmp(extension, DB_BLOCKED_BUFFER_STORAGE_ARB) == 0
            || strcmp(extension, DB_BLOCKED_BUFFER_STORAGE_EXT) == 0;
}

static const unsigned char *db_filtered_gl_get_string(unsigned int name) {
    if (g_real_gl_get_string == NULL) return NULL;
    const unsigned char *raw = g_real_gl_get_string(name);
    if (!db_disable_persistent_mapping() || raw == NULL) return raw;

    if (name == DB_GL_VERSION) {
        int major = 0;
        int minor = 0;
        if (sscanf((const char *) raw, "%d.%d", &major, &minor) == 2
                && (major > 4 || (major == 4 && minor > 3))) {
            pthread_mutex_lock(&g_gl_capability_lock);
            snprintf(g_filtered_gl_version, sizeof(g_filtered_gl_version),
                     "4.3 DroidBridge Snapshot 4 compatibility (%s)",
                     (const char *) raw);
            pthread_mutex_unlock(&g_gl_capability_lock);
            if (!g_logged_version_filter) {
                g_logged_version_filter = 1;
                db_log(ANDROID_LOG_INFO,
                       "OpenGL version capped for safe buffers original=%s exposed=4.3",
                       (const char *) raw);
            }
            return (const unsigned char *) g_filtered_gl_version;
        }
        return raw;
    }

    if (name == DB_GL_EXTENSIONS) {
        pthread_mutex_lock(&g_gl_capability_lock);
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
                    int blocked = (length == strlen(DB_BLOCKED_BUFFER_STORAGE_ARB)
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
        pthread_mutex_unlock(&g_gl_capability_lock);
        if (!g_logged_buffer_filter) {
            g_logged_buffer_filter = 1;
            db_log(ANDROID_LOG_INFO,
                   "persistent buffer capability filter active renderer=%s maxGL=4.3 blocked=%s,%s",
                   getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") != NULL
                           ? getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") : "unknown",
                   DB_BLOCKED_BUFFER_STORAGE_ARB,
                   DB_BLOCKED_BUFFER_STORAGE_EXT);
        }
        return filtered;
    }

    return raw;
}

static const unsigned char *db_filtered_gl_get_string_i(unsigned int name,
                                                         unsigned int index) {
    if (g_real_gl_get_string_i == NULL) return NULL;
    const unsigned char *raw = g_real_gl_get_string_i(name, index);
    if (!db_disable_persistent_mapping() || name != DB_GL_EXTENSIONS || raw == NULL) {
        return raw;
    }
    if (db_blocked_buffer_storage_extension((const char *) raw)) {
        if (!g_logged_buffer_filter) {
            g_logged_buffer_filter = 1;
            db_log(ANDROID_LOG_INFO,
                   "persistent buffer capability filter active renderer=%s maxGL=4.3 blocked=%s,%s",
                   getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") != NULL
                           ? getenv("DROIDBRIDGE_SDL3_OPENGL_KIND") : "unknown",
                   DB_BLOCKED_BUFFER_STORAGE_ARB,
                   DB_BLOCKED_BUFFER_STORAGE_EXT);
        }
        return (const unsigned char *) DB_DISABLED_EXTENSION_NAME;
    }
    return raw;
}

static void db_filtered_gl_get_integerv(unsigned int name, int *value) {
    if (g_real_gl_get_integerv == NULL) return;
    g_real_gl_get_integerv(name, value);
    if (!db_disable_persistent_mapping() || value == NULL) return;

    if (name == DB_GL_MAJOR_VERSION && *value > 4) {
        *value = 4;
        if (!g_logged_version_filter) {
            g_logged_version_filter = 1;
            db_log(ANDROID_LOG_INFO,
                   "OpenGL integer version capped for safe buffers exposed=4.3");
        }
    } else if (name == DB_GL_MINOR_VERSION) {
        int major = 0;
        g_real_gl_get_integerv(DB_GL_MAJOR_VERSION, &major);
        if (major > 4 || (major == 4 && *value > 3)) {
            *value = 3;
            if (!g_logged_version_filter) {
                g_logged_version_filter = 1;
                db_log(ANDROID_LOG_INFO,
                       "OpenGL integer version capped for safe buffers exposed=4.3");
            }
        }
    }
}

static void *db_wrapper_gl_proc(const char *name) {
    if (name == NULL) return NULL;

    if (db_disable_persistent_mapping()
            && db_is_blocked_buffer_storage_proc(name)) {
        if (!g_logged_blocked_buffer_proc) {
            g_logged_blocked_buffer_proc = 1;
            db_log(ANDROID_LOG_INFO,
                   "blocked persistent buffer-storage entry point firstProc=%s",
                   name);
        }
        return NULL;
    }

    /*
     * Resolve from the selected wrapper before RTLD_DEFAULT. Android's system
     * libGLES also exports names such as glGetError; taking that symbol first is
     * exactly what makes RenderPearl compare two different GL providers and report
     * "glGetError mismatch" with Krypton.
     *
     * LTW is a special case. It intentionally overrides core GL entry points via
     * eglGetProcAddress(), while dlsym() can expose a different ELF entry point for
     * the same function. Minecraft 26.3/RenderPearl compares the pointer loaded by
     * LWJGL with SDL_GL_GetProcAddress and rejects the backend when they differ.
     * DroidBridge's LWJGL RenderSpec hook already makes LTW's EGL resolver
     * authoritative; do the same here so SDL and LWJGL see one canonical provider.
     */
    // SimpleFPEWrapper is the outer GL layer for MobileGlues when present.
    // Keep g_gl_wrapper as MobileGlues so EGL display/context lifecycle still
    // comes from the complete provider underneath it.
    void *handle = db_load_gl_wrapper();
    DbEglGetProcAddressFn get_proc = handle != NULL
            ? (DbEglGetProcAddressFn) dlsym(handle, "eglGetProcAddress")
            : NULL;

    /*
     * Minecraft 26.3 RenderPearl performs a pointer-identity sanity check for
     * glGetError: the address obtained from the OpenGL library itself must be
     * exactly the same address returned by SDL_GL_GetProcAddress(). On Android
     * the direct OpenGL side opens libmobileglues.so, while our SDL/EGL shim
     * normally layers every gl* lookup through SimpleFPEWrapper. That makes the
     * check compare MobileGlues' ELF glGetError export with SFPEW's glGetError
     * trampoline and RenderPearl rejects OpenGL with "glGetError mismatch",
     * then falls back to Vulkan even when the user selected OpenGL.
     *
     * glGetError itself does not need fixed-function emulation, so make only
     * this identity probe use the underlying MobileGlues symbol. All other GL
     * procedures still route through SimpleFPEWrapper, preserving the legacy
     * compatibility path used by older Minecraft/modpacks. Prefer dlsym here
     * because that is the same lookup RenderPearl uses on its direct library
     * handle; only fall back to MobileGlues' eglGetProcAddress if necessary.
     */
    void *symbol = NULL;
    if (db_is_mobileglues_renderer()
            && db_truthy("DROIDBRIDGE_SIMPLEFPE_ACTIVE")
            && strcmp(name, "glGetError") == 0
            && handle != NULL) {
        symbol = dlsym(handle, name);
        if (symbol == NULL && get_proc != NULL) {
            symbol = (void *) get_proc(name);
        }
        if (symbol != NULL) {
            static int logged_renderpearl_glgeterror_identity = 0;
            if (!logged_renderpearl_glgeterror_identity) {
                logged_renderpearl_glgeterror_identity = 1;
                db_log(ANDROID_LOG_INFO,
                       "MobileGlues RenderPearl glGetError identity bridge active proc=%p provider=%p",
                       symbol, handle);
            }
        }
    }

    if (symbol == NULL) {
        symbol = db_simplefpe_gl_proc(name);
    }

    if (symbol == NULL && db_is_ltw_renderer() && get_proc != NULL) {
        symbol = (void *) get_proc(name);
        if (symbol != NULL && strcmp(name, "glGetError") == 0) {
            db_log(ANDROID_LOG_INFO,
                   "LTW canonical glGetError resolved through eglGetProcAddress proc=%p provider=%p",
                   symbol, handle);
        }
    }

    if (symbol == NULL && handle != NULL) {
        symbol = dlsym(handle, name);
    }

    if (symbol == NULL && get_proc != NULL) {
        symbol = (void *) get_proc(name);
    }

    if (symbol == NULL) {
        symbol = dlsym(RTLD_DEFAULT, name);
    }

    if (symbol != NULL && db_is_mobileglues_renderer()) {
        if (strcmp(name, "glCreateShader") == 0) {
            g_real_gl_create_shader = (DbGlCreateShaderFn) symbol;
            symbol = (void *) db_krypton_gl_create_shader_guard;
        } else if (strcmp(name, "glShaderSource") == 0) {
            g_real_gl_shader_source = (DbGlShaderSourceFn) symbol;
            symbol = (void *) db_mobileglues_gl_shader_source_normalized;
        } else if (strcmp(name, "glCompileShader") == 0) {
            g_real_gl_compile_shader = (DbGlCompileShaderFn) symbol;
            symbol = (void *) db_krypton_gl_compile_shader_guard;
        } else if (strcmp(name, "glCreateProgram") == 0) {
            g_real_gl_create_program = (DbGlCreateProgramFn) symbol;
            symbol = (void *) db_krypton_gl_create_program_guard;
        }
    }

    if (symbol != NULL && db_is_krypton_renderer()) {
        if (strcmp(name, "glCreateShader") == 0) {
            g_real_gl_create_shader = (DbGlCreateShaderFn) symbol;
            symbol = (void *) db_krypton_gl_create_shader_guard;
        } else if (strcmp(name, "glShaderSource") == 0) {
            g_real_gl_shader_source = (DbGlShaderSourceFn) symbol;
            symbol = (void *) db_krypton_gl_shader_source_guard;
        } else if (strcmp(name, "glCompileShader") == 0) {
            g_real_gl_compile_shader = (DbGlCompileShaderFn) symbol;
            symbol = (void *) db_krypton_gl_compile_shader_guard;
        } else if (strcmp(name, "glCreateProgram") == 0) {
            g_real_gl_create_program = (DbGlCreateProgramFn) symbol;
            symbol = (void *) db_krypton_gl_create_program_guard;
        }
    }

    if (symbol != NULL && db_disable_persistent_mapping()) {
        if (strcmp(name, "glGetString") == 0) {
            g_real_gl_get_string = (DbGlGetStringFn) symbol;
            symbol = (void *) db_filtered_gl_get_string;
        } else if (strcmp(name, "glGetStringi") == 0) {
            g_real_gl_get_string_i = (DbGlGetStringiFn) symbol;
            symbol = (void *) db_filtered_gl_get_string_i;
        } else if (strcmp(name, "glGetIntegerv") == 0) {
            g_real_gl_get_integerv = (DbGlGetIntegervFn) symbol;
            symbol = (void *) db_filtered_gl_get_integerv;
        }
    }

    if (symbol != NULL && !g_logged_proc_provider) {
        g_logged_proc_provider = 1;
        db_log(ANDROID_LOG_INFO,
               "desktop GL procedure routing active through wrapped provider firstProc=%s",
               name);
    }
    return symbol;
}

static EGLint db_take_system_error(void) {
    typedef EGLint (*Fn)(void);
    Fn fn = (Fn) db_system_symbol_direct("eglGetError");
    return fn != NULL ? fn() : EGL_BAD_ACCESS;
}

static void db_clear_system_error(void) {
    EGLint error;
    do {
        error = db_take_system_error();
    } while (error != EGL_SUCCESS);
}

static int db_requests_desktop_gl(const EGLint *attrs) {
    if (attrs == NULL) return 0;
    for (int i = 0; attrs[i] != EGL_NONE; i += 2) {
        if ((attrs[i] == EGL_RENDERABLE_TYPE || attrs[i] == EGL_CONFORMANT)
                && (attrs[i + 1] & EGL_OPENGL_BIT) != 0) {
            return 1;
        }
    }
    return 0;
}

static const EGLint *db_rewrite_config_attrs(
        const EGLint *input,
        EGLint *output,
        int capacity,
        int relaxed,
        int es2_only) {
    int n = 0;
    int saw_renderable = 0;
    int saw_alpha = 0;
    const int force_opaque = db_truthy("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE");
    const EGLint gles_bits = es2_only
            ? EGL_OPENGL_ES2_BIT
            : (EGL_OPENGL_ES3_BIT_KHR | EGL_OPENGL_ES2_BIT);

    if (input != NULL) {
        for (int i = 0; input[i] != EGL_NONE && n + 2 < capacity; i += 2) {
            EGLint key = input[i];
            EGLint value = input[i + 1];

            if (key == EGL_RENDERABLE_TYPE) {
                value &= ~EGL_OPENGL_BIT;
                value |= gles_bits;
                saw_renderable = 1;
            } else if (key == EGL_ALPHA_SIZE) {
                saw_alpha = 1;
                if (force_opaque) value = 0;
            } else if (key == EGL_CONFORMANT) {
                if (relaxed) continue;
                value &= ~EGL_OPENGL_BIT;
                value |= gles_bits;
            } else if (relaxed && (key == EGL_COLOR_COMPONENT_TYPE_EXT
                    || key == EGL_MATCH_NATIVE_PIXMAP
                    || key == EGL_CONFIG_CAVEAT
                    || key == EGL_LEVEL
                    || key == EGL_MAX_SWAP_INTERVAL
                    || key == EGL_MIN_SWAP_INTERVAL)) {
                continue;
            }

            output[n++] = key;
            output[n++] = value;
        }
    }

    if (!saw_renderable && n + 2 < capacity) {
        output[n++] = EGL_RENDERABLE_TYPE;
        output[n++] = gles_bits;
    }
    if (force_opaque && !saw_alpha && n + 2 < capacity) {
        output[n++] = EGL_ALPHA_SIZE;
        output[n++] = 0;
    }
    output[n++] = EGL_NONE;
    return output;
}

static const EGLint *db_rewrite_context_attrs(
        const EGLint *input,
        EGLint *output,
        int capacity,
        int client_version,
        int preserve_optional) {
    int n = 0;
    if (preserve_optional && input != NULL) {
        for (int i = 0; input[i] != EGL_NONE && n + 2 < capacity; i += 2) {
            EGLint key = input[i];
            EGLint value = input[i + 1];
            if (key == EGL_CONTEXT_CLIENT_VERSION
                    || key == EGL_CONTEXT_MAJOR_VERSION_KHR
                    || key == EGL_CONTEXT_MINOR_VERSION_KHR
                    || key == EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
                    || key == EGL_CONTEXT_FLAGS_KHR
                    || key == EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_KHR
                    || key == EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT
                    || key == EGL_CONTEXT_OPENGL_NO_ERROR_KHR) {
                continue;
            }
            output[n++] = key;
            output[n++] = value;
        }
    }
    output[n++] = EGL_CONTEXT_CLIENT_VERSION;
    output[n++] = client_version;
    output[n++] = EGL_NONE;
    return output;
}


static void db_log_config_attrs_once(const EGLint *attrs) {
    static int logged = 0;
    if (logged || attrs == NULL) return;
    logged = 1;

    char buffer[1024];
    size_t used = 0;
    used += (size_t) snprintf(buffer + used, sizeof(buffer) - used,
                              "SDL desktop config attrs:");
    for (int i = 0; attrs[i] != EGL_NONE && used + 32 < sizeof(buffer); i += 2) {
        used += (size_t) snprintf(buffer + used, sizeof(buffer) - used,
                                  " 0x%x=0x%x", attrs[i], attrs[i + 1]);
    }
    db_log(ANDROID_LOG_INFO, "%s", buffer);
}

static EGLBoolean db_choose_minimal_android_config(
        EGLBoolean (*choose)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *),
        EGLDisplay dpy,
        EGLConfig *configs,
        EGLint config_size,
        EGLint *count) {
    const EGLint candidates[][21] = {
            {
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 24,
                    EGL_STENCIL_SIZE, 8,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_NONE
            },
            {
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 0,
                    EGL_DEPTH_SIZE, 24,
                    EGL_STENCIL_SIZE, 0,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_NONE
            },
            {
                    EGL_RED_SIZE, 5,
                    EGL_GREEN_SIZE, 6,
                    EGL_BLUE_SIZE, 5,
                    EGL_ALPHA_SIZE, 0,
                    EGL_DEPTH_SIZE, 16,
                    EGL_STENCIL_SIZE, 0,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_NONE
            }
    };

    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); ++i) {
        EGLint local_count = 0;
        db_clear_system_error();
        EGLBoolean result = choose(dpy, candidates[i], configs, config_size, &local_count);
        if (result == EGL_TRUE && local_count > 0) {
            if (count != NULL) *count = local_count;
            db_log(ANDROID_LOG_INFO,
                   "minimal Android EGL config fallback succeeded index=%zu count=%d",
                   i, local_count);
            return EGL_TRUE;
        }
        (void) db_take_system_error();
    }
    if (count != NULL) *count = 0;
    return EGL_FALSE;
}

#if defined(__GNUC__)
#pragma GCC visibility push(default)
#endif

extern "C" {

EGLint eglGetError(void) {
    if (g_deferred_error != EGL_SUCCESS) {
        EGLint error = g_deferred_error;
        g_deferred_error = EGL_SUCCESS;
        return error;
    }
    return db_take_system_error();
}

EGLDisplay eglGetDisplay(EGLNativeDisplayType display_id) {
    typedef EGLDisplay (*Fn)(EGLNativeDisplayType);
    Fn fn = (Fn) db_system_symbol("eglGetDisplay");
    return fn != NULL ? fn(display_id) : EGL_NO_DISPLAY;
}

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLint *, EGLint *);
    Fn fn = (Fn) db_system_symbol("eglInitialize");
    EGLBoolean result = fn != NULL ? fn(dpy, major, minor) : EGL_FALSE;
    db_log(result == EGL_TRUE ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
           "Android EGL initialize result=%d version=%d.%d compat=%d",
           result, (result == EGL_TRUE && major != NULL) ? *major : -1,
           (result == EGL_TRUE && minor != NULL) ? *minor : -1,
           db_compat_enabled());
    return result;
}

EGLBoolean eglTerminate(EGLDisplay dpy) {
    typedef EGLBoolean (*Fn)(EGLDisplay);
    Fn fn = (Fn) db_system_symbol("eglTerminate");
    EGLBoolean result = fn != NULL ? fn(dpy) : EGL_FALSE;
    if (result == EGL_TRUE && dpy == g_krypton_cached_display) {
        db_clear_krypton_context_cache();
    }
    return result;
}

const char *eglQueryString(EGLDisplay dpy, EGLint name) {
    typedef const char *(*Fn)(EGLDisplay, EGLint);
    Fn fn = (Fn) db_system_symbol("eglQueryString");
    const char *result = fn != NULL ? fn(dpy, name) : NULL;
    if (db_compat_enabled() && name == EGL_CLIENT_APIS) {
        if (!g_logged_client_apis) {
            g_logged_client_apis = 1;
            db_log(ANDROID_LOG_INFO, "reporting EGL client APIs as OpenGL + OpenGL_ES (system=%s)",
                   result != NULL ? result : "null");
        }
        return "OpenGL OpenGL_ES";
    }
    return result;
}

EGLBoolean eglGetConfigs(EGLDisplay dpy, EGLConfig *configs, EGLint config_size, EGLint *num_config) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLConfig *, EGLint, EGLint *);
    Fn fn = (Fn) db_system_symbol("eglGetConfigs");
    return fn != NULL ? fn(dpy, configs, config_size, num_config) : EGL_FALSE;
}

EGLBoolean eglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list,
                           EGLConfig *configs, EGLint config_size, EGLint *num_config) {
    typedef EGLBoolean (*Fn)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
    Fn fn = (Fn) db_system_symbol("eglChooseConfig");
    if (fn == NULL) return EGL_FALSE;

    const int desktop_request = db_compat_enabled() && db_requests_desktop_gl(attrib_list);
    if (!desktop_request) return fn(dpy, attrib_list, configs, config_size, num_config);

    g_desktop_api_requested = 1;
    db_log_config_attrs_once(attrib_list);
    if (db_truthy("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE")) {
        static int logged_opaque = 0;
        if (!logged_opaque) {
            logged_opaque = 1;
            db_log(ANDROID_LOG_INFO,
                   "%s SDL3 EGL config forcing EGL_ALPHA_SIZE=0 for opaque TextureView presentation",
                   db_context_guard_name());
        }
    }
    EGLint attrs[128];
    EGLint relaxed[128];
    EGLint es2[128];
    EGLBoolean result;
    EGLint count = 0;
    EGLint last_error = EGL_SUCCESS;

    db_clear_system_error();
    result = fn(dpy, db_rewrite_config_attrs(attrib_list, attrs, 128, 0, 0),
                configs, config_size, &count);
    if (result != EGL_TRUE || count <= 0) {
        last_error = db_take_system_error();
        result = fn(dpy, db_rewrite_config_attrs(attrib_list, relaxed, 128, 1, 0),
                    configs, config_size, &count);
    }
    if (result != EGL_TRUE || count <= 0) {
        EGLint error = db_take_system_error();
        if (error != EGL_SUCCESS) last_error = error;
        result = fn(dpy, db_rewrite_config_attrs(attrib_list, es2, 128, 1, 1),
                    configs, config_size, &count);
    }
    if (result != EGL_TRUE || count <= 0) {
        EGLint error = db_take_system_error();
        if (error != EGL_SUCCESS) last_error = error;
        result = db_choose_minimal_android_config(
                fn, dpy, configs, config_size, &count);
    }

    if (num_config != NULL) *num_config = count;
    if (result == EGL_TRUE && count > 0) {
        db_clear_system_error();
        if (!g_logged_choose_translation) {
            g_logged_choose_translation = 1;
            db_log(ANDROID_LOG_INFO,
                   "translated SDL desktop eglChooseConfig to Android GLES config count=%d configSize=%d",
                   count, config_size);
        }
        return EGL_TRUE;
    }

    EGLint error = db_take_system_error();
    if (error != EGL_SUCCESS) last_error = error;
    g_deferred_error = last_error != EGL_SUCCESS ? last_error : EGL_BAD_ATTRIBUTE;
    db_log(ANDROID_LOG_ERROR,
           "all desktop->GLES eglChooseConfig attempts failed error=0x%x", g_deferred_error);
    return EGL_FALSE;
}

EGLBoolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLConfig, EGLint, EGLint *);
    Fn fn = (Fn) db_system_symbol("eglGetConfigAttrib");
    EGLBoolean result = fn != NULL ? fn(dpy, config, attribute, value) : EGL_FALSE;
    if (result == EGL_TRUE && value != NULL && db_compat_enabled()
            && (attribute == EGL_RENDERABLE_TYPE || attribute == EGL_CONFORMANT)) {
        *value |= EGL_OPENGL_BIT;
    }
    return result;
}

static const EGLint *db_strip_srgb_surface_attrs(const EGLint *attrib_list,
                                                  EGLint *out,
                                                  size_t capacity,
                                                  EGLint *removed_colorspace) {
    if (removed_colorspace != NULL) *removed_colorspace = EGL_NONE;
    if (attrib_list == NULL || out == NULL || capacity < 1) return attrib_list;

    size_t output = 0;
    int removed = 0;
    for (size_t i = 0; i < 126 && attrib_list[i] != EGL_NONE; i += 2) {
        EGLint key = attrib_list[i];
        EGLint value = attrib_list[i + 1];
        if (key == EGL_GL_COLORSPACE_KHR) {
            if (removed_colorspace != NULL) *removed_colorspace = value;
            removed = 1;
            continue;
        }
        if (output + 3 >= capacity) return attrib_list;
        out[output++] = key;
        out[output++] = value;
    }

    if (!removed) return attrib_list;
    out[output] = EGL_NONE;
    return out;
}

EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config,
                                  EGLNativeWindowType win, const EGLint *attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
    Fn fn = (Fn) db_system_symbol("eglCreateWindowSurface");
    if (fn == NULL) return EGL_NO_SURFACE;

    if (!db_context_guard_enabled()) {
        return fn(dpy, config, win, attrib_list);
    }

    /*
     * Snapshot 4's SDL path requests EGL_GL_COLORSPACE_SRGB_KHR. On both
     * MobileGlues and Krypton that makes the default framebuffer perform an
     * additional sRGB transfer. The screenshots show the exact result: the
     * Mojang red becomes pink and world/menu mid-tones are lifted. Preserve the
     * Android window's sRGB dataspace, but remove SDL's EGL colorspace attribute
     * so GL writes legacy display-encoded bytes without a second conversion.
     */
    if (!db_truthy("DROIDBRIDGE_SDL3_LEGACY_SRGB_PASSTHROUGH")) {
        return fn(dpy, config, win, attrib_list);
    }

    EGLint passthrough_attrs[128];
    EGLint removed_colorspace = EGL_NONE;
    const EGLint *effective_attrs = db_strip_srgb_surface_attrs(
            attrib_list,
            passthrough_attrs,
            sizeof(passthrough_attrs) / sizeof(passthrough_attrs[0]),
            &removed_colorspace);

    db_clear_system_error();
    EGLSurface surface = fn(dpy, config, win, effective_attrs);
    if (surface != EGL_NO_SURFACE) {
        if (!g_logged_srgb_surface) {
            g_logged_srgb_surface = 1;
            db_log(ANDROID_LOG_INFO,
                   "%s SDL3 EGL legacy sRGB byte pass-through active removedColorspace=0x%x opaqueConfig=%d",
                   db_context_guard_name(),
                   removed_colorspace,
                   db_truthy("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE"));
        }
        return surface;
    }

    EGLint passthrough_error = db_take_system_error();
    surface = fn(dpy, config, win, attrib_list);
    if (surface != EGL_NO_SURFACE) {
        if (!g_logged_srgb_surface_fallback) {
            g_logged_srgb_surface_fallback = 1;
            db_log(ANDROID_LOG_ERROR,
                   "%s SDL3 EGL pass-through attribute removal failed error=0x%x; used original attributes",
                   db_context_guard_name(),
                   passthrough_error);
        }
        return surface;
    }

    EGLint fallback_error = db_take_system_error();
    g_deferred_error = fallback_error != EGL_SUCCESS
            ? fallback_error : passthrough_error;
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, const EGLint *);
    Fn fn = (Fn) db_system_symbol("eglCreatePbufferSurface");
    return fn != NULL ? fn(dpy, config, attrib_list) : EGL_NO_SURFACE;
}

EGLSurface eglCreatePixmapSurface(EGLDisplay dpy, EGLConfig config,
                                  EGLNativePixmapType pixmap, const EGLint *attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, EGLNativePixmapType, const EGLint *);
    Fn fn = (Fn) db_system_symbol("eglCreatePixmapSurface");
    return fn != NULL ? fn(dpy, config, pixmap, attrib_list) : EGL_NO_SURFACE;
}

EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface);
    typedef EGLBoolean (*MakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    if (db_context_guard_enabled() && g_krypton_cached_context_live
            && (surface == g_krypton_cached_draw || surface == g_krypton_cached_read)) {
        MakeCurrentFn make_current =
                (MakeCurrentFn) db_system_symbol_direct("eglMakeCurrent");
        g_krypton_allow_context_release = 1;
        if (make_current != NULL) {
            (void) make_current(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        g_krypton_allow_context_release = 0;
        db_clear_krypton_context_cache();
    }
    Fn fn = (Fn) db_system_symbol("eglDestroySurface");
    return fn != NULL ? fn(dpy, surface) : EGL_FALSE;
}

EGLBoolean eglQuerySurface(EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint *value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint, EGLint *);
    Fn fn = (Fn) db_system_symbol("eglQuerySurface");
    return fn != NULL ? fn(dpy, surface, attribute, value) : EGL_FALSE;
}

EGLBoolean eglBindAPI(EGLenum api) {
    typedef EGLBoolean (*Fn)(EGLenum);
    Fn fn = (Fn) db_system_symbol("eglBindAPI");
    if (fn == NULL) return EGL_FALSE;
    if (db_compat_enabled() && api == EGL_OPENGL_API) {
        g_desktop_api_requested = 1;
        api = EGL_OPENGL_ES_API;
        db_log(ANDROID_LOG_INFO, "translated eglBindAPI OpenGL -> OpenGL_ES");
    }
    EGLBoolean result = fn(api);
    if (result == EGL_TRUE && db_context_guard_enabled()
            && g_krypton_cached_context_live && !g_krypton_restore_guard
            && db_underlying_current_context() == EGL_NO_CONTEXT) {
        g_krypton_force_rebind_pending = 1;
        (void) db_rebind_krypton_context("eglBindAPI", 0);
    }
    return result;
}

EGLenum eglQueryAPI(void) {
    typedef EGLenum (*Fn)(void);
    Fn fn = (Fn) db_system_symbol("eglQueryAPI");
    EGLenum result = fn != NULL ? fn() : EGL_NONE;
    if (db_compat_enabled() && g_desktop_api_requested && result == EGL_OPENGL_ES_API) {
        return EGL_OPENGL_API;
    }
    return result;
}

EGLBoolean eglWaitClient(void) {
    typedef EGLBoolean (*Fn)(void);
    Fn fn = (Fn) db_system_symbol("eglWaitClient");
    return fn != NULL ? fn() : EGL_FALSE;
}

EGLBoolean eglReleaseThread(void) {
    if (db_context_guard_enabled() && g_krypton_cached_context_live
            && !g_krypton_allow_context_release) {
        if (!g_logged_krypton_context_unbind) {
            g_logged_krypton_context_unbind = 1;
            db_log(ANDROID_LOG_INFO,
                   "%s transient eglReleaseThread suppressed while SDL context is live",
                   db_context_guard_name());
        }
        return EGL_TRUE;
    }
    typedef EGLBoolean (*Fn)(void);
    Fn fn = (Fn) db_system_symbol("eglReleaseThread");
    return fn != NULL ? fn() : EGL_FALSE;
}

EGLSurface eglCreatePbufferFromClientBuffer(EGLDisplay dpy, EGLenum buftype,
                                            EGLClientBuffer buffer, EGLConfig config,
                                            const EGLint *attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLenum, EGLClientBuffer, EGLConfig, const EGLint *);
    Fn fn = (Fn) db_system_symbol("eglCreatePbufferFromClientBuffer");
    return fn != NULL ? fn(dpy, buftype, buffer, config, attrib_list) : EGL_NO_SURFACE;
}

EGLBoolean eglSurfaceAttrib(EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint, EGLint);
    Fn fn = (Fn) db_system_symbol("eglSurfaceAttrib");
    return fn != NULL ? fn(dpy, surface, attribute, value) : EGL_FALSE;
}

EGLBoolean eglBindTexImage(EGLDisplay dpy, EGLSurface surface, EGLint buffer) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint);
    Fn fn = (Fn) db_system_symbol("eglBindTexImage");
    return fn != NULL ? fn(dpy, surface, buffer) : EGL_FALSE;
}

EGLBoolean eglReleaseTexImage(EGLDisplay dpy, EGLSurface surface, EGLint buffer) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint);
    Fn fn = (Fn) db_system_symbol("eglReleaseTexImage");
    return fn != NULL ? fn(dpy, surface, buffer) : EGL_FALSE;
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLint);
    Fn fn = (Fn) db_system_symbol("eglSwapInterval");
    return fn != NULL ? fn(dpy, interval) : EGL_FALSE;
}

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                            EGLContext share_context, const EGLint *attrib_list) {
    typedef EGLContext (*Fn)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
    typedef EGLBoolean (*BindFn)(EGLenum);
    Fn fn = (Fn) db_system_symbol("eglCreateContext");
    BindFn bind = (BindFn) db_system_symbol("eglBindAPI");
    if (fn == NULL) return EGL_NO_CONTEXT;

    if (!db_compat_enabled() || !g_desktop_api_requested) {
        return fn(dpy, config, share_context, attrib_list);
    }

    EGLint attrs3[64];
    EGLint attrs2[64];
    EGLint last_error = EGL_SUCCESS;
    db_clear_system_error();
    if (bind != NULL) bind(EGL_OPENGL_ES_API);

    EGLContext context = fn(dpy, config, share_context,
                            db_rewrite_context_attrs(attrib_list, attrs3, 64, 3, 1));
    if (context == EGL_NO_CONTEXT) {
        last_error = db_take_system_error();
        if (bind != NULL) bind(EGL_OPENGL_ES_API);
        context = fn(dpy, config, share_context,
                     db_rewrite_context_attrs(attrib_list, attrs2, 64, 2, 0));
    }

    if (context != EGL_NO_CONTEXT) {
        db_clear_system_error();
        if (!g_logged_context_translation) {
            g_logged_context_translation = 1;
            db_log(ANDROID_LOG_INFO,
                   "created Android GLES context for SDL desktop OpenGL; the selected wrapper supplies desktop GL entry points");
        }
        return context;
    }

    EGLint error = db_take_system_error();
    if (error != EGL_SUCCESS) last_error = error;
    g_deferred_error = last_error != EGL_SUCCESS ? last_error : EGL_BAD_MATCH;
    db_log(ANDROID_LOG_ERROR, "desktop->GLES eglCreateContext failed error=0x%x", g_deferred_error);
    return EGL_NO_CONTEXT;
}

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLContext);
    typedef EGLBoolean (*MakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    if (db_context_guard_enabled() && g_krypton_cached_context_live
            && ctx == g_krypton_cached_context) {
        MakeCurrentFn make_current =
                (MakeCurrentFn) db_system_symbol_direct("eglMakeCurrent");
        g_krypton_allow_context_release = 1;
        if (make_current != NULL) {
            (void) make_current(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        g_krypton_allow_context_release = 0;
        db_clear_krypton_context_cache();
    }
    Fn fn = (Fn) db_system_symbol("eglDestroyContext");
    return fn != NULL ? fn(dpy, ctx) : EGL_FALSE;
}

EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    typedef EGLBoolean (*BindFn)(EGLenum);
    Fn fn = (Fn) db_system_symbol("eglMakeCurrent");
    BindFn bind = (BindFn) db_system_symbol("eglBindAPI");
    if (fn == NULL) return EGL_FALSE;

    /* SDL3's public SDL_GL_MakeCurrent wrapper suppresses transient context
     * clears before SDL mutates its own current-context state. Keep a second
     * guard here for internal EGL clears so wrapped-renderer TLS remains live. */
    if (db_context_guard_enabled() && ctx == EGL_NO_CONTEXT
            && g_krypton_cached_context_live
            && !g_krypton_allow_context_release) {
        /*
         * NeoForge/FML 12's 26.3 loading display can release, re-bind and release
         * the same wrapped OpenGL context several times before Minecraft creates
         * its final graphics window. A one-shot exception is not enough: consuming
         * the token after the first clear leaves a later FML clear suppressed, so
         * the old thread still owns the EGLSurface and Minecraft's final context
         * fails with EGL_BAD_ACCESS.
         *
         * GameActivity arms an "early" phase for NeoForge. The SDL window bridge
         * changes it to "final" exactly when the second logical SDL window is
         * requested (Minecraft's real Window). Until then every genuine FML clear
         * must reach EGL. Once the phase is final, the normal DroidBridge transient
         * context guard resumes unchanged.
         */
        const char *neoForgeHandoff =
                getenv("DROIDBRIDGE_SDL3_NEOFORGE_EARLY_CONTEXT_HANDOFF");
        const bool neoForgeEarlyPhase = neoForgeHandoff != NULL
                && (strcmp(neoForgeHandoff, "early") == 0
                    || strcmp(neoForgeHandoff, "1") == 0);
        if (neoForgeEarlyPhase) {
            EGLBoolean released = fn(dpy, draw, read, ctx);
            if (released == EGL_TRUE) {
                db_clear_krypton_context_cache();
                db_log(ANDROID_LOG_INFO,
                       "%s NeoForge early-display EGL context release passed through",
                       db_context_guard_name());
            } else {
                db_log(ANDROID_LOG_ERROR,
                       "%s NeoForge early-display EGL context release failed error=0x%x",
                       db_context_guard_name(), db_take_system_error());
            }
            return released;
        }

        if (!g_logged_krypton_context_unbind) {
            g_logged_krypton_context_unbind = 1;
            db_log(ANDROID_LOG_INFO,
                   "%s transient eglMakeCurrent(NO_CONTEXT) suppressed while SDL context is live",
                   db_context_guard_name());
        }
        return EGL_TRUE;
    }

    if (db_compat_enabled() && g_desktop_api_requested && bind != NULL) {
        (void) bind(EGL_OPENGL_ES_API);
    }
    EGLBoolean result = fn(dpy, draw, read, ctx);
    if (db_context_guard_enabled() && result == EGL_TRUE && ctx != EGL_NO_CONTEXT) {
        g_krypton_cached_display = dpy;
        g_krypton_cached_draw = draw;
        g_krypton_cached_read = read;
        g_krypton_cached_context = ctx;
        g_krypton_cached_context_live = 1;
        g_krypton_force_rebind_pending = 0;
        if (!g_logged_krypton_context_cache) {
            g_logged_krypton_context_cache = 1;
            db_log(ANDROID_LOG_INFO,
                   "%s live SDL context cached context=%p draw=%p read=%p",
                   db_context_guard_name(), ctx, draw, read);
        }
    } else if (result != EGL_TRUE) {
        db_log(ANDROID_LOG_ERROR,
               "eglMakeCurrent failed renderer=%s context=%p error=0x%x",
               db_context_guard_name(),
               ctx, db_take_system_error());
    }
    return result;
}

EGLContext eglGetCurrentContext(void) {
    typedef EGLContext (*Fn)(void);
    Fn fn = (Fn) db_system_symbol("eglGetCurrentContext");
    EGLContext result = fn != NULL ? fn() : EGL_NO_CONTEXT;
    if (result == EGL_NO_CONTEXT && db_context_guard_enabled()
            && g_krypton_cached_context_live) {
        if (db_restore_krypton_context_if_needed("eglGetCurrentContext") == EGL_TRUE) {
            result = fn != NULL ? fn() : g_krypton_cached_context;
        }
    }
    return result;
}

EGLSurface eglGetCurrentSurface(EGLint readdraw) {
    typedef EGLSurface (*Fn)(EGLint);
    Fn fn = (Fn) db_system_symbol("eglGetCurrentSurface");
    return fn != NULL ? fn(readdraw) : EGL_NO_SURFACE;
}

EGLDisplay eglGetCurrentDisplay(void) {
    typedef EGLDisplay (*Fn)(void);
    Fn fn = (Fn) db_system_symbol("eglGetCurrentDisplay");
    return fn != NULL ? fn() : EGL_NO_DISPLAY;
}

EGLBoolean eglQueryContext(EGLDisplay dpy, EGLContext ctx, EGLint attribute, EGLint *value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLContext, EGLint, EGLint *);
    Fn fn = (Fn) db_system_symbol("eglQueryContext");
    EGLBoolean result = fn != NULL ? fn(dpy, ctx, attribute, value) : EGL_FALSE;
    if (result == EGL_TRUE && value != NULL && db_compat_enabled()
            && g_desktop_api_requested && attribute == EGL_CONTEXT_CLIENT_TYPE) {
        *value = EGL_OPENGL_API;
    }
    return result;
}

EGLBoolean eglWaitGL(void) {
    typedef EGLBoolean (*Fn)(void);
    Fn fn = (Fn) db_system_symbol("eglWaitGL");
    return fn != NULL ? fn() : EGL_FALSE;
}

EGLBoolean eglWaitNative(EGLint engine) {
    typedef EGLBoolean (*Fn)(EGLint);
    Fn fn = (Fn) db_system_symbol("eglWaitNative");
    return fn != NULL ? fn(engine) : EGL_FALSE;
}

static void db_log_krypton_present_result(const char *path,
                                           EGLBoolean result,
                                           EGLint error,
                                           EGLSurface surface) {
    if (!db_is_krypton_renderer()) return;
    int *first_flag = strcmp(path, "eglSwapBuffers") == 0
            ? &g_logged_krypton_first_swap
            : &g_logged_krypton_first_damage_swap;
    if (!*first_flag) {
        *first_flag = 1;
        db_log(ANDROID_LOG_INFO,
               "Krypton first %s result=%d error=0x%x surface=%p current=%p cached=%p",
               path, result, error, surface,
               db_underlying_current_context(),
               g_krypton_cached_context);
    } else if (result != EGL_TRUE && !g_logged_krypton_swap_failure) {
        g_logged_krypton_swap_failure = 1;
        db_log(ANDROID_LOG_ERROR,
               "Krypton %s failed error=0x%x surface=%p current=%p cached=%p",
               path, error, surface,
               db_underlying_current_context(),
               g_krypton_cached_context);
    }
}

EGLBoolean eglSwapBuffersWithDamageKHR(EGLDisplay dpy, EGLSurface surface,
                                       EGLint *rects, EGLint n_rects);
EGLBoolean eglSwapBuffersWithDamageEXT(EGLDisplay dpy, EGLSurface surface,
                                       EGLint *rects, EGLint n_rects);

extern "C" __attribute__((visibility("default")))
int droidbridge_sdl3_krypton_prepare_present(void) {
    if (!db_is_krypton_renderer() || !g_krypton_cached_context_live
            || g_krypton_cached_display == EGL_NO_DISPLAY
            || g_krypton_cached_draw == EGL_NO_SURFACE) {
        return 0;
    }

    EGLBoolean result = db_rebind_krypton_context(
            "SDL_GL_SwapWindow", g_krypton_force_rebind_pending);
    if (result != EGL_TRUE) {
        db_log(ANDROID_LOG_ERROR,
               "Krypton SDL_GL_SwapWindow context preparation failed current=%p cached=%p",
               db_underlying_current_context(), g_krypton_cached_context);
        return 0;
    }
    return 1;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface surface) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface);
    if (db_is_krypton_renderer()) {
        (void) db_rebind_krypton_context("eglSwapBuffers", g_krypton_force_rebind_pending);
    }
    Fn fn = (Fn) db_system_symbol("eglSwapBuffers");
    EGLBoolean result = fn != NULL ? fn(dpy, surface) : EGL_FALSE;
    if (db_is_krypton_renderer()) {
        EGLint error = result == EGL_TRUE ? EGL_SUCCESS : db_take_system_error();
        db_log_krypton_present_result("eglSwapBuffers", result, error, surface);
    }
    return result;
}

EGLBoolean eglSwapBuffersWithDamageKHR(EGLDisplay dpy, EGLSurface surface,
                                       EGLint *rects, EGLint n_rects) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint *, EGLint);
    if (db_is_krypton_renderer()) {
        (void) db_rebind_krypton_context(
                "eglSwapBuffersWithDamageKHR", g_krypton_force_rebind_pending);
    }
    Fn fn = (Fn) db_system_symbol("eglSwapBuffersWithDamageKHR");
    EGLBoolean result = fn != NULL
            ? fn(dpy, surface, rects, n_rects)
            : eglSwapBuffers(dpy, surface);
    if (db_is_krypton_renderer()) {
        EGLint error = result == EGL_TRUE ? EGL_SUCCESS : db_take_system_error();
        db_log_krypton_present_result(
                "eglSwapBuffersWithDamageKHR", result, error, surface);
    }
    return result;
}

EGLBoolean eglSwapBuffersWithDamageEXT(EGLDisplay dpy, EGLSurface surface,
                                       EGLint *rects, EGLint n_rects) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint *, EGLint);
    if (db_is_krypton_renderer()) {
        (void) db_rebind_krypton_context(
                "eglSwapBuffersWithDamageEXT", g_krypton_force_rebind_pending);
    }
    Fn fn = (Fn) db_system_symbol("eglSwapBuffersWithDamageEXT");
    EGLBoolean result = fn != NULL
            ? fn(dpy, surface, rects, n_rects)
            : eglSwapBuffers(dpy, surface);
    if (db_is_krypton_renderer()) {
        EGLint error = result == EGL_TRUE ? EGL_SUCCESS : db_take_system_error();
        db_log_krypton_present_result(
                "eglSwapBuffersWithDamageEXT", result, error, surface);
    }
    return result;
}

EGLBoolean eglCopyBuffers(EGLDisplay dpy, EGLSurface surface, EGLNativePixmapType target) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLNativePixmapType);
    Fn fn = (Fn) db_system_symbol("eglCopyBuffers");
    return fn != NULL ? fn(dpy, surface, target) : EGL_FALSE;
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *procname) {
    if (procname != NULL && strcmp(procname, "eglSwapBuffersWithDamageKHR") == 0) {
        return (__eglMustCastToProperFunctionPointerType) eglSwapBuffersWithDamageKHR;
    }
    if (procname != NULL && strcmp(procname, "eglSwapBuffersWithDamageEXT") == 0) {
        return (__eglMustCastToProperFunctionPointerType) eglSwapBuffersWithDamageEXT;
    }
    if (db_compat_enabled() && db_disable_persistent_mapping()
            && db_is_blocked_buffer_storage_proc(procname)) {
        if (!g_logged_blocked_buffer_proc) {
            g_logged_blocked_buffer_proc = 1;
            db_log(ANDROID_LOG_INFO,
                   "blocked eglGetProcAddress buffer-storage entry point firstProc=%s",
                   procname);
        }
        return NULL;
    }
    if (db_compat_enabled() && db_is_gl_proc(procname)) {
        void *wrapper = db_wrapper_gl_proc(procname);
        if (wrapper != NULL) {
            return (__eglMustCastToProperFunctionPointerType) wrapper;
        }
    }

    DbEglGetProcAddressFn system_get_proc =
            (DbEglGetProcAddressFn) db_system_symbol_direct("eglGetProcAddress");
    if (system_get_proc != NULL) {
        __eglMustCastToProperFunctionPointerType ptr = system_get_proc(procname);
        if (ptr != NULL) return ptr;
    }

    return (__eglMustCastToProperFunctionPointerType) db_system_symbol_direct(procname);
}

EGLDisplay eglGetPlatformDisplay(EGLenum platform, void *native_display, const EGLAttrib *attrib_list) {
    typedef EGLDisplay (*Fn)(EGLenum, void *, const EGLAttrib *);
    Fn fn = (Fn) db_system_symbol("eglGetPlatformDisplay");
    return fn != NULL ? fn(platform, native_display, attrib_list) : EGL_NO_DISPLAY;
}

EGLSurface eglCreatePlatformWindowSurface(EGLDisplay dpy, EGLConfig config,
                                          void *native_window, const EGLAttrib *attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, void *, const EGLAttrib *);
    Fn fn = (Fn) db_system_symbol("eglCreatePlatformWindowSurface");
    return fn != NULL ? fn(dpy, config, native_window, attrib_list) : EGL_NO_SURFACE;
}

EGLSurface eglCreatePlatformPixmapSurface(EGLDisplay dpy, EGLConfig config,
                                          void *native_pixmap, const EGLAttrib *attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, void *, const EGLAttrib *);
    Fn fn = (Fn) db_system_symbol("eglCreatePlatformPixmapSurface");
    return fn != NULL ? fn(dpy, config, native_pixmap, attrib_list) : EGL_NO_SURFACE;
}

EGLSync eglCreateSync(EGLDisplay dpy, EGLenum type, const EGLAttrib *attrib_list) {
    typedef EGLSync (*Fn)(EGLDisplay, EGLenum, const EGLAttrib *);
    Fn fn = (Fn) db_system_symbol("eglCreateSync");
    return fn != NULL ? fn(dpy, type, attrib_list) : EGL_NO_SYNC;
}

EGLBoolean eglDestroySync(EGLDisplay dpy, EGLSync sync) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSync);
    Fn fn = (Fn) db_system_symbol("eglDestroySync");
    return fn != NULL ? fn(dpy, sync) : EGL_FALSE;
}

EGLint eglClientWaitSync(EGLDisplay dpy, EGLSync sync, EGLint flags, EGLTime timeout) {
    typedef EGLint (*Fn)(EGLDisplay, EGLSync, EGLint, EGLTime);
    Fn fn = (Fn) db_system_symbol("eglClientWaitSync");
    return fn != NULL ? fn(dpy, sync, flags, timeout) : EGL_FALSE;
}

EGLBoolean eglGetSyncAttrib(EGLDisplay dpy, EGLSync sync, EGLint attribute, EGLAttrib *value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSync, EGLint, EGLAttrib *);
    Fn fn = (Fn) db_system_symbol("eglGetSyncAttrib");
    return fn != NULL ? fn(dpy, sync, attribute, value) : EGL_FALSE;
}

__attribute__((visibility("default")))
int droidbridge_sdl3_egl_ensure_current_context(const char *reason) {
    /* MobileGlues keeps translator/context state in its EGL path. Android EGL can
     * still report the expected context while that wrapper-side state is stale,
     * which makes glCreateShader return 0. Re-apply the cached SDL-owned context
     * before each guarded shader/program operation. db_rebind_krypton_context()
     * no longer performs an unbind cycle, so this is safe for both wrappers. */
    int force_reapply = db_is_mobileglues_renderer() ? 1 : 0;
    return db_rebind_krypton_context(reason, force_reapply) == EGL_TRUE ? 1 : 0;
}


__attribute__((visibility("default")))
int droidbridge_sdl3_egl_set_cached_swap_interval(int interval, const char *reason) {
    static int logged_first = 0;
    static int logged_failure = 0;

    if (!db_context_guard_enabled() || !g_krypton_cached_context_live
            || g_krypton_cached_display == EGL_NO_DISPLAY) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log(ANDROID_LOG_ERROR,
                   "%s cached EGL swap interval unavailable requested=%d reason=%s display=%p context=%p live=%d",
                   db_context_guard_name(),
                   interval,
                   reason != NULL ? reason : "unknown",
                   g_krypton_cached_display,
                   g_krypton_cached_context,
                   g_krypton_cached_context_live);
        }
        return 0;
    }

    int effective = interval > 0 ? 1 : 0;
    int force_reapply = db_is_mobileglues_renderer() ? 1 : 0;
    if (db_rebind_krypton_context(reason, force_reapply) != EGL_TRUE) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log(ANDROID_LOG_ERROR,
                   "%s cached EGL swap interval context restore failed requested=%d reason=%s",
                   db_context_guard_name(),
                   effective,
                   reason != NULL ? reason : "unknown");
        }
        return 0;
    }

    typedef EGLBoolean (*SwapIntervalFn)(EGLDisplay, EGLint);
    SwapIntervalFn set_interval =
            (SwapIntervalFn)db_system_symbol_direct("eglSwapInterval");
    EGLBoolean result = set_interval != NULL
            ? set_interval(g_krypton_cached_display, effective)
            : EGL_FALSE;
    EGLint error = result == EGL_TRUE ? EGL_SUCCESS : db_take_system_error();

    if (!logged_first || (result != EGL_TRUE && !logged_failure)) {
        if (!logged_first) logged_first = 1;
        if (result != EGL_TRUE) logged_failure = 1;
        db_log(result == EGL_TRUE ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
               "%s cached EGL swap interval requested=%d effective=%d result=%d error=0x%x reason=%s display=%p context=%p",
               db_context_guard_name(),
               interval,
               effective,
               result == EGL_TRUE ? 1 : 0,
               error,
               reason != NULL ? reason : "unknown",
               g_krypton_cached_display,
               g_krypton_cached_context);
    }
    return result == EGL_TRUE ? 1 : 0;
}

__attribute__((visibility("default")))
int droidbridge_sdl3_egl_swap_cached_surface(const char *reason) {
    static int logged_first = 0;
    static int logged_failure = 0;

    if (!db_context_guard_enabled() || !g_krypton_cached_context_live
            || g_krypton_cached_display == EGL_NO_DISPLAY
            || g_krypton_cached_draw == EGL_NO_SURFACE) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log(ANDROID_LOG_ERROR,
                   "%s cached EGL presentation unavailable reason=%s display=%p draw=%p context=%p live=%d",
                   db_context_guard_name(),
                   reason != NULL ? reason : "unknown",
                   g_krypton_cached_display,
                   g_krypton_cached_draw,
                   g_krypton_cached_context,
                   g_krypton_cached_context_live);
        }
        return 0;
    }

    int force_reapply = db_is_mobileglues_renderer() ? 1 : 0;
    if (db_rebind_krypton_context(reason, force_reapply) != EGL_TRUE) {
        if (!logged_failure) {
            logged_failure = 1;
            db_log(ANDROID_LOG_ERROR,
                   "%s cached EGL presentation context restore failed reason=%s",
                   db_context_guard_name(),
                   reason != NULL ? reason : "unknown");
        }
        return 0;
    }

    typedef EGLBoolean (*SwapFn)(EGLDisplay, EGLSurface);
    SwapFn swap = (SwapFn)db_system_symbol_direct("eglSwapBuffers");
    EGLBoolean result = swap != NULL
            ? swap(g_krypton_cached_display, g_krypton_cached_draw)
            : EGL_FALSE;
    EGLint error = result == EGL_TRUE ? EGL_SUCCESS : db_take_system_error();

    if (!logged_first || (result != EGL_TRUE && !logged_failure)) {
        if (!logged_first) logged_first = 1;
        if (result != EGL_TRUE) logged_failure = 1;
        db_log(result == EGL_TRUE ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
               "%s cached EGL presentation result=%d error=0x%x reason=%s display=%p draw=%p context=%p",
               db_context_guard_name(),
               result == EGL_TRUE ? 1 : 0,
               error,
               reason != NULL ? reason : "unknown",
               g_krypton_cached_display,
               g_krypton_cached_draw,
               g_krypton_cached_context);
    }
    return result == EGL_TRUE ? 1 : 0;
}

EGLImage eglCreateImage(EGLDisplay dpy, EGLContext ctx, EGLenum target,
                        EGLClientBuffer buffer, const EGLAttrib *attrib_list) {
    typedef EGLImage (*Fn)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLAttrib *);
    Fn fn = (Fn) db_system_symbol("eglCreateImage");
    return fn != NULL ? fn(dpy, ctx, target, buffer, attrib_list) : EGL_NO_IMAGE;
}

EGLBoolean eglDestroyImage(EGLDisplay dpy, EGLImage image) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLImage);
    Fn fn = (Fn) db_system_symbol("eglDestroyImage");
    return fn != NULL ? fn(dpy, image) : EGL_FALSE;
}

} /* extern \"C\" */

#if defined(__GNUC__)
#pragma GCC visibility pop
#endif
