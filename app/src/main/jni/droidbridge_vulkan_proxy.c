/*
 * DroidBridge Vulkan loader proxy.
 *
 * LWJGL receives this library handle instead of the proprietary Android
 * Vulkan loader whenever a DroidBridge Vulkan compatibility mode is active.
 * The proxy forwards every proc-address request through libdroidbridge_runtime,
 * where only the selected compatibility entry points are replaced.
 */

#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct VkInstance_T* DB_VkInstance;
typedef struct VkDevice_T* DB_VkDevice;
typedef void (*DB_PFN_vkVoidFunction)(void);

extern DB_PFN_vkVoidFunction droidbridge_vulkan_compat_get_instance_proc_addr(
        DB_VkInstance instance, const char* name);
extern DB_PFN_vkVoidFunction droidbridge_vulkan_compat_get_device_proc_addr(
        DB_VkDevice device, const char* name);
extern int droidbridge_vulkan_compat_prepare_real_loader(void* realLoaderHandle);
extern int droidbridge_vulkan_compat_real_loader_ready(void);
extern void* maybe_load_vulkan(void);

static int db_proxy_env_enabled(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0'
            && strcmp(value, "0") != 0
            && strcmp(value, "false") != 0
            && strcmp(value, "FALSE") != 0;
}

static int db_proxy_strict_custom_vulkan(void) {
    const char* selected = getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER");
    return db_proxy_env_enabled("DROIDBRIDGE_LOAD_CUSTOM_VULKAN")
            && !db_proxy_env_enabled("DROIDBRIDGE_USE_SYSTEM_VULKAN")
            && selected != NULL
            && selected[0] != '\0';
}

static int g_proxy_instance_logged = 0;
static int g_proxy_device_logged = 0;
static pthread_once_t g_real_loader_once = PTHREAD_ONCE_INIT;
static void* g_real_loader_handle = NULL;
static int g_real_loader_ready = 0;

/*
 * org.lwjgl.vulkan.libname makes LWJGL open this proxy before OSMDroid's
 * normal Vulkan-loader path runs. Bootstrap Android's real Vulkan loader
 * here on the first proc-address request so the proxy can satisfy LWJGL's
 * global command lookup instead of returning NULL for vkCreateInstance.
 */
static void droidbridge_vulkan_proxy_prepare_real_loader_once(void) {
    void* handle = NULL;
    const int strict_custom = db_proxy_strict_custom_vulkan();

    if (strict_custom) {
        /*
         * LWJGL 3.4 can load Vulkan before DroidBridge's ndlopen hook is installed.
         * Bootstrap the selected custom ICD here for every strict custom Vulkan
         * launch (regular Zink, Kopper, Turnip, or PanVK) instead of allowing the
         * Android system loader to become authoritative first.
         */
        handle = maybe_load_vulkan();
        if (handle == NULL) {
            fprintf(stderr,
                    "DroidBridgeVulkanCompat: early proxy could not bootstrap selected custom ICD; refusing system Vulkan fallback\n");
            fflush(stderr);
            return;
        }
        fprintf(stderr,
                "DroidBridgeVulkanCompat: early proxy received custom-ICD loader handle=%p ICD=%s report=%s\n",
                handle,
                getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") != NULL
                        ? getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER_NAME") : "<unset>",
                getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT") != NULL
                        ? getenv("DROIDBRIDGE_VULKAN_DRIVER_REPORT") : "<unset>");
        fflush(stderr);
    } else {
        dlerror();
        handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == NULL) {
            const char* error = dlerror();
            fprintf(stderr,
                    "DroidBridgeVulkanCompat: proxy failed to open real Vulkan loader error=%s\n",
                    error != NULL ? error : "unknown");
            fflush(stderr);
            return;
        }
    }

    /*
     * When a Vulkan compatibility mode is active, maybe_load_vulkan() may
     * legitimately return this proxy handle: load_vulkan() prepares the real
     * Turnip/PanVK loader first, then droidbridge_vulkan_compat_select_loader()
     * publishes the proxy through VULKAN_PTR. Never feed that proxy back into
     * droidbridge_vulkan_compat_prepare_real_loader(), otherwise the runtime
     * replaces its saved real vkGetInstanceProcAddr with our own export and
     * vkGetInstanceProcAddr recursively calls itself until the native stack
     * overflows.
     */
    void* proxy_entry = dlsym(handle,
            "droidbridge_vulkan_proxy_get_instance_proc_addr");
    if (proxy_entry != NULL) {
        if (!droidbridge_vulkan_compat_real_loader_ready()) {
            fprintf(stderr,
                    "DroidBridgeVulkanCompat: proxy self-handoff detected but real ICD was not prepared; refusing recursive Vulkan loader setup handle=%p\n",
                    handle);
            fflush(stderr);
            return;
        }
        g_real_loader_handle = handle;
        g_real_loader_ready = 1;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: proxy self-handoff detected handle=%p; reusing already-prepared real ICD without recursive prepare\n",
                handle);
        fflush(stderr);
        return;
    }

    if (!droidbridge_vulkan_compat_prepare_real_loader(handle)) {
        fprintf(stderr,
                "DroidBridgeVulkanCompat: proxy could not prepare real Vulkan loader handle=%p strictCustom=%d\n",
                handle,
                strict_custom);
        fflush(stderr);
        if (!strict_custom) dlclose(handle);
        return;
    }

    g_real_loader_handle = handle;
    g_real_loader_ready = 1;
    fprintf(stderr,
            "DroidBridgeVulkanCompat: proxy bootstrapped real Vulkan loader handle=%p strictCustom=%d\n",
            handle,
            strict_custom);
    fflush(stderr);
}

static int droidbridge_vulkan_proxy_ensure_real_loader(void) {
    pthread_once(&g_real_loader_once,
                 droidbridge_vulkan_proxy_prepare_real_loader_once);
    return g_real_loader_ready;
}

/*
 * Unique, non-Vulkan symbol names let libdroidbridge_runtime resolve these
 * entries without Android's already-loaded system libvulkan pre-empting the
 * standard Vulkan symbol names.
 */
__attribute__((visibility("default"), noinline)) DB_PFN_vkVoidFunction
droidbridge_vulkan_proxy_get_instance_proc_addr(DB_VkInstance instance, const char* name) {
    if (!droidbridge_vulkan_proxy_ensure_real_loader()) {
        return NULL;
    }
    if (!g_proxy_instance_logged) {
        g_proxy_instance_logged = 1;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: authoritative proxy instance entry first=%s instance=%p\n",
                name != NULL ? name : "<null>",
                (void*)instance);
        fflush(stderr);
    }
    return droidbridge_vulkan_compat_get_instance_proc_addr(instance, name);
}

__attribute__((visibility("default"), noinline)) DB_PFN_vkVoidFunction
droidbridge_vulkan_proxy_get_device_proc_addr(DB_VkDevice device, const char* name) {
    if (!droidbridge_vulkan_proxy_ensure_real_loader()) {
        return NULL;
    }
    if (!g_proxy_device_logged) {
        g_proxy_device_logged = 1;
        fprintf(stderr,
                "DroidBridgeVulkanCompat: authoritative proxy device entry first=%s device=%p\n",
                name != NULL ? name : "<null>",
                (void*)device);
        fflush(stderr);
    }
    return droidbridge_vulkan_compat_get_device_proc_addr(device, name);
}

__attribute__((visibility("default"), noinline)) DB_PFN_vkVoidFunction
vkGetInstanceProcAddr(DB_VkInstance instance, const char* name) {
    return droidbridge_vulkan_proxy_get_instance_proc_addr(instance, name);
}

__attribute__((visibility("default"), noinline)) DB_PFN_vkVoidFunction
vkGetDeviceProcAddr(DB_VkDevice device, const char* name) {
    return droidbridge_vulkan_proxy_get_device_proc_addr(device, name);
}
