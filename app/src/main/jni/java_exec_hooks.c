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

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <unistd.h>

#include <environ/environ.h>
#include <android/log.h>
#include <utils.h>

#define EXEC_HOOK_TAG "JavaLauncherExecHook"
#define ARRAY_SIZE(a) ((int)(sizeof(a) / sizeof((a)[0])))

static jint (*orig_ProcessImpl_forkAndExec)(JNIEnv *env, jobject process, jint mode,
                                            jbyteArray helperpath, jbyteArray prog, jbyteArray argBlock, jint argc,
                                            jbyteArray envBlock, jint envc, jbyteArray dir, jintArray std_fds,
                                            jboolean redirectErrorStream);

static void log_info(const char *fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, EXEC_HOOK_TAG, "%s", buffer);
    printf("%s: %s\n", EXEC_HOOK_TAG, buffer);
}

static void log_warn(const char *fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_WARN, EXEC_HOOK_TAG, "%s", buffer);
    printf("%s WARN: %s\n", EXEC_HOOK_TAG, buffer);
}

static char *byte_array_to_c_string(JNIEnv *env, jbyteArray array) {
    if (array == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, array);
    if (length <= 0) return NULL;

    char *out = (char *)calloc((size_t)length + 1, 1);
    if (out == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)out);
    out[length] = '\0';
    return out;
}

static jbyteArray string_to_bytes(JNIEnv *env, const char *string) {
    if (string == NULL) return NULL;
    jsize length = (jsize)strlen(string) + 1;
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)string);
    return result;
}

static jbyteArray prepend_arg_block(JNIEnv *env, jbyteArray oldBlock, const char *firstArg) {
    if (firstArg == NULL || firstArg[0] == '\0') return NULL;

    jsize old_len = oldBlock != NULL ? (*env)->GetArrayLength(env, oldBlock) : 0;
    size_t first_len = strlen(firstArg) + 1;
    size_t new_len = first_len + (size_t)(old_len > 0 ? old_len : 0);

    jbyteArray out = (*env)->NewByteArray(env, (jsize)new_len);
    if (out == NULL) return NULL;

    (*env)->SetByteArrayRegion(env, out, 0, (jsize)first_len, (const jbyte *)firstArg);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, out);
        return NULL;
    }

    if (oldBlock != NULL && old_len > 0) {
        jbyte *old_data = (jbyte *)malloc((size_t)old_len);
        if (old_data == NULL) {
            (*env)->DeleteLocalRef(env, out);
            return NULL;
        }
        (*env)->GetByteArrayRegion(env, oldBlock, 0, old_len, old_data);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            free(old_data);
            (*env)->DeleteLocalRef(env, out);
            return NULL;
        }
        (*env)->SetByteArrayRegion(env, out, (jsize)first_len, old_len, old_data);
        free(old_data);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, out);
            return NULL;
        }
    }
    return out;
}

static const char *safe_basename(const char *path) {
    if (path == NULL || path[0] == '\0') return "";
    const char *slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static bool is_open_folder_command(const char *base) {
    return strcmp(base, "xdg-open") == 0
           || strcmp(base, "open") == 0
           || strcmp(base, "gio") == 0
           || strcmp(base, "kioclient") == 0
           || strcmp(base, "kde-open") == 0
           || strcmp(base, "gnome-open") == 0;
}

static char *first_open_target_from_arg_block(JNIEnv *env, jbyteArray argBlock) {
    if (argBlock == NULL) return NULL;

    jsize length = (*env)->GetArrayLength(env, argBlock);
    if (length <= 0) return NULL;

    char *data = (char *)malloc((size_t)length + 1);
    if (data == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, argBlock, 0, length, (jbyte *)data);
    data[length] = '\0';

    char *fallback = NULL;
    size_t i = 0;
    while (i < (size_t)length) {
        const char *entry = data + i;
        size_t entry_len = strnlen(entry, (size_t)length - i);
        if (entry_len == 0) {
            i++;
            continue;
        }

        const char *base = safe_basename(entry);
        bool option = entry[0] == '-';
        if (!is_open_folder_command(base) && !option) {
            fallback = strndup(entry, entry_len);
            break;
        }
        i += entry_len + 1;
    }

    free(data);
    return fallback;
}

static bool starts_with(const char *value, const char *prefix) {
    return value != NULL && prefix != NULL && strncmp(value, prefix, strlen(prefix)) == 0;
}

static bool is_ffmpeg_name(const char *base) {
    return strcmp(base, "ffmpeg") == 0 || strcmp(base, "libffmpeg.so") == 0;
}

static bool is_ffprobe_name(const char *base) {
    return strcmp(base, "ffprobe") == 0 || strcmp(base, "libffprobe.so") == 0;
}

static char *dirname_dup(const char *path) {
    if (path == NULL) return NULL;
    const char *slash = strrchr(path, '/');
    if (slash == NULL) return strdup(".");
    if (slash == path) return strdup("/");

    size_t len = (size_t)(slash - path);
    char *out = (char *)malloc(len + 1);
    if (out == NULL) return NULL;
    memcpy(out, path, len);
    out[len] = '\0';
    return out;
}

static char *dup_env_value_from_block(JNIEnv *env, jbyteArray envBlock, const char *key) {
    if (envBlock == NULL || key == NULL) return NULL;

    jsize length = (*env)->GetArrayLength(env, envBlock);
    if (length <= 0) return NULL;

    char *data = (char *)malloc((size_t)length + 1);
    if (data == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, envBlock, 0, length, (jbyte *)data);
    data[length] = '\0';

    size_t key_len = strlen(key);
    size_t i = 0;
    while (i < (size_t)length) {
        const char *entry = data + i;
        size_t entry_len = strnlen(entry, (size_t)length - i);
        if (entry_len == 0) {
            i++;
            continue;
        }
        if (entry_len > key_len && strncmp(entry, key, key_len) == 0 && entry[key_len] == '=') {
            char *out = strdup(entry + key_len + 1);
            free(data);
            return out;
        }
        i += entry_len + 1;
    }

    free(data);
    return NULL;
}

static char *resolve_env_path(JNIEnv *env, jbyteArray envBlock, const char **keys, int key_count) {
    for (int i = 0; i < key_count; i++) {
        char *from_block = dup_env_value_from_block(env, envBlock, keys[i]);
        if (from_block != NULL && from_block[0] != '\0') return from_block;
        free(from_block);

        const char *from_process = getenv(keys[i]);
        if (from_process != NULL && from_process[0] != '\0') return strdup(from_process);
    }
    return NULL;
}

static bool string_is_truthy(const char *value) {
    if (value == NULL || value[0] == '\0') return false;
    return strcmp(value, "1") == 0
           || strcasecmp(value, "true") == 0
           || strcasecmp(value, "yes") == 0
           || strcasecmp(value, "on") == 0;
}

static bool should_use_android_linker(JNIEnv *env, jbyteArray envBlock, const char *targetPath) {
    // The DroidBridge companion packages ffmpeg/ffprobe as PIE executables named
    // libffmpeg.so/libffprobe.so. JavaRuntimeBootstrap also sets this explicit
    // flag for the companion path. Either signal is enough to use linker/linker64.
    char *flag = dup_env_value_from_block(env, envBlock, "JAVALAUNCHER_FFMPEG_USE_LINKER");
    if (flag == NULL || flag[0] == '\0') {
        free(flag);
        const char *process_flag = getenv("JAVALAUNCHER_FFMPEG_USE_LINKER");
        flag = process_flag != NULL ? strdup(process_flag) : NULL;
    }

    bool flagged = string_is_truthy(flag);
    free(flag);

    const char *base = safe_basename(targetPath);
    bool companion_name = strcmp(base, "libffmpeg.so") == 0
                          || strcmp(base, "libffprobe.so") == 0;
    return flagged || companion_name;
}

static const char *first_non_empty(const char *a, const char *b) {
    if (a != NULL && a[0] != '\0') return a;
    if (b != NULL && b[0] != '\0') return b;
    return NULL;
}

static char *prepend_path_value(const char *prefix, const char *old_value) {
    if (prefix == NULL || prefix[0] == '\0') return old_value != NULL ? strdup(old_value) : strdup("");
    if (old_value == NULL || old_value[0] == '\0') return strdup(prefix);

    size_t prefix_len = strlen(prefix);
    const char *cursor = old_value;
    while (cursor != NULL && cursor[0] != '\0') {
        const char *colon = strchr(cursor, ':');
        size_t part_len = colon == NULL ? strlen(cursor) : (size_t)(colon - cursor);
        if (part_len == prefix_len && strncmp(cursor, prefix, prefix_len) == 0) {
            return strdup(old_value);
        }
        cursor = colon == NULL ? NULL : colon + 1;
    }

    size_t len = strlen(prefix) + 1 + strlen(old_value) + 1;
    char *out = (char *)malloc(len);
    if (out == NULL) return NULL;
    snprintf(out, len, "%s:%s", prefix, old_value);
    return out;
}

static jbyteArray merge_ffmpeg_env(JNIEnv *env, jbyteArray oldBlock, jint oldEnvc, const char *binDir, jint *newEnvc) {
    char *old_ld = dup_env_value_from_block(env, oldBlock, "LD_LIBRARY_PATH");
    char *old_path = dup_env_value_from_block(env, oldBlock, "PATH");
    const char *process_ld = getenv("LD_LIBRARY_PATH");
    const char *process_path = getenv("PATH");

    char *merged_ld_value = prepend_path_value(binDir, first_non_empty(old_ld, process_ld));
    char *path_value = first_non_empty(old_path, process_path) != NULL
                       ? strdup(first_non_empty(old_path, process_path))
                       : strdup("");

    free(old_ld);
    free(old_path);

    if (merged_ld_value == NULL || path_value == NULL) {
        free(merged_ld_value);
        free(path_value);
        return oldBlock;
    }

    size_t ld_entry_len = strlen("LD_LIBRARY_PATH=") + strlen(merged_ld_value) + 1;
    size_t path_entry_len = strlen("PATH=") + strlen(path_value) + 1;

    jsize old_len = oldBlock != NULL ? (*env)->GetArrayLength(env, oldBlock) : 0;
    char *old_data = NULL;
    if (old_len > 0) {
        old_data = (char *)malloc((size_t)old_len + 1);
        if (old_data == NULL) {
            free(merged_ld_value);
            free(path_value);
            return oldBlock;
        }
        (*env)->GetByteArrayRegion(env, oldBlock, 0, old_len, (jbyte *)old_data);
        old_data[old_len] = '\0';
    }

    size_t kept_len = 0;
    jint kept_count = 0;
    size_t i = 0;
    while (old_data != NULL && i < (size_t)old_len) {
        const char *entry = old_data + i;
        size_t entry_len = strnlen(entry, (size_t)old_len - i);
        if (entry_len == 0) {
            i++;
            continue;
        }
        if (!starts_with(entry, "LD_LIBRARY_PATH=") && !starts_with(entry, "PATH=")) {
            kept_len += entry_len + 1;
            kept_count++;
        }
        i += entry_len + 1;
    }

    size_t new_len = kept_len + ld_entry_len + path_entry_len;
    char *new_data = (char *)calloc(new_len, 1);
    if (new_data == NULL) {
        free(old_data);
        free(merged_ld_value);
        free(path_value);
        return oldBlock;
    }

    size_t offset = 0;
    i = 0;
    while (old_data != NULL && i < (size_t)old_len) {
        const char *entry = old_data + i;
        size_t entry_len = strnlen(entry, (size_t)old_len - i);
        if (entry_len == 0) {
            i++;
            continue;
        }
        if (!starts_with(entry, "LD_LIBRARY_PATH=") && !starts_with(entry, "PATH=")) {
            memcpy(new_data + offset, entry, entry_len + 1);
            offset += entry_len + 1;
        }
        i += entry_len + 1;
    }

    int wrote = snprintf(new_data + offset, new_len - offset, "LD_LIBRARY_PATH=%s", merged_ld_value);
    offset += (size_t)wrote + 1;
    wrote = snprintf(new_data + offset, new_len - offset, "PATH=%s", path_value);
    (void)wrote;

    jbyteArray out = (*env)->NewByteArray(env, (jsize)new_len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)new_len, (const jbyte *)new_data);
        *newEnvc = kept_count + 2;
    } else {
        out = oldBlock;
        *newEnvc = oldEnvc;
    }

    free(old_data);
    free(new_data);
    free(merged_ld_value);
    free(path_value);
    return out;
}

static bool redirect_tool_direct(JNIEnv *env, const char *toolName, const char *targetPath,
                                 jbyteArray *prog, jbyteArray *envBlock, jint *envc) {
    if (targetPath == NULL || targetPath[0] == '\0') {
        log_warn("%s requested, but no target path was set", toolName);
        return false;
    }

    char *bin_dir = dirname_dup(targetPath);
    if (bin_dir == NULL) {
        log_warn("%s requested, unable to resolve directory from %s", toolName, targetPath);
        return false;
    }

    jbyteArray new_prog = string_to_bytes(env, targetPath);
    if (new_prog == NULL) {
        log_warn("%s requested, unable to allocate direct executable path", toolName);
        free(bin_dir);
        return false;
    }

    *envBlock = merge_ffmpeg_env(env, *envBlock, *envc, bin_dir, envc);
    *prog = new_prog;
    log_info("%s requested, direct target=%s", toolName, targetPath);
    free(bin_dir);
    return true;
}

static bool redirect_tool_via_android_linker(JNIEnv *env, const char *toolName, const char *targetPath,
                                             jbyteArray *prog, jbyteArray *argBlock, jint *argc,
                                             jbyteArray *envBlock, jint *envc) {
    if (targetPath == NULL || targetPath[0] == '\0') {
        log_warn("%s requested, but no target path was set", toolName);
        return false;
    }

#if defined(__LP64__)
    const char *android_linker = "/system/bin/linker64";
#else
    const char *android_linker = "/system/bin/linker";
#endif

    // Do not replace a previously-working direct path if the expected system
    // linker is unavailable for some unusual Android/runtime layout.
    if (access(android_linker, X_OK) != 0) {
        log_warn("%s requested, %s unavailable; falling back to direct execution",
                 toolName, android_linker);
        return redirect_tool_direct(env, toolName, targetPath, prog, envBlock, envc);
    }

    char *bin_dir = dirname_dup(targetPath);
    if (bin_dir == NULL) {
        log_warn("%s requested, unable to resolve directory from %s", toolName, targetPath);
        return false;
    }

    // ProcessImpl receives the executable separately from a NUL-separated block
    // containing argv[1..]. When switching the executable to linker/linker64,
    // the companion PIE must become argv[1] and argc must grow by one.
    jbyteArray new_args = prepend_arg_block(env, *argBlock, targetPath);
    jbyteArray new_prog = string_to_bytes(env, android_linker);
    if (new_args == NULL || new_prog == NULL) {
        if (new_args != NULL) (*env)->DeleteLocalRef(env, new_args);
        if (new_prog != NULL) (*env)->DeleteLocalRef(env, new_prog);
        free(bin_dir);
        log_warn("%s requested, unable to build linker command; falling back to direct execution",
                 toolName);
        return redirect_tool_direct(env, toolName, targetPath, prog, envBlock, envc);
    }

    *envBlock = merge_ffmpeg_env(env, *envBlock, *envc, bin_dir, envc);
    *argBlock = new_args;
    *argc = *argc + 1;
    *prog = new_prog;
    log_info("%s requested, linker target=%s executable=%s", toolName, android_linker, targetPath);
    free(bin_dir);
    return true;
}

static bool redirect_ffmpeg_tool(JNIEnv *env, const char *toolName, const char *targetPath,
                                 jbyteArray *prog, jbyteArray *argBlock, jint *argc,
                                 jbyteArray *envBlock, jint *envc) {
    if (targetPath == NULL || targetPath[0] == '\0') {
        log_warn("%s requested, but no target path was set", toolName);
        return false;
    }

    if (should_use_android_linker(env, *envBlock, targetPath)) {
        return redirect_tool_via_android_linker(env, toolName, targetPath,
                                                prog, argBlock, argc, envBlock, envc);
    }

    // Preserve old behavior for any future/custom FFmpeg path that is a normal
    // executable rather than DroidBridge's libffmpeg.so/libffprobe.so companion.
    return redirect_tool_direct(env, toolName, targetPath, prog, envBlock, envc);
}

static jint hooked_ProcessImpl_forkAndExec(JNIEnv *env, jobject process, jint mode,
                                           jbyteArray helperpath, jbyteArray prog, jbyteArray argBlock, jint argc,
                                           jbyteArray envBlock, jint envc, jbyteArray dir, jintArray std_fds,
                                           jboolean redirectErrorStream) {

    char *program = byte_array_to_c_string(env, prog);
    const char *base = safe_basename(program);

    if (is_open_folder_command(base)) {
        char *target = first_open_target_from_arg_block(env, argBlock);
        jbyteArray targetBytes = target != NULL ? string_to_bytes(env, target) : argBlock;
        log_info("%s requested, Android open target=%s", base, target != NULL ? target : "<raw-arg-block>");
        Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(env, NULL, CLIPBOARD_OPEN, targetBytes);
        free(target);
        free(program);
        return 0;
    }

    if (is_ffmpeg_name(base)) {
        const char *keys[] = {
                "DROIDBRIDGE_FFMPEG_PATH",
                "JAVALAUNCHER_FFMPEG_PATH",
                "FFMPEG_PATH"
        };
        char *target = resolve_env_path(env, envBlock, keys, ARRAY_SIZE(keys));
        redirect_ffmpeg_tool(env, "ffmpeg", target, &prog, &argBlock, &argc, &envBlock, &envc);
        free(target);
    } else if (is_ffprobe_name(base)) {
        const char *keys[] = {
                "DROIDBRIDGE_FFPROBE_PATH",
                "JAVALAUNCHER_FFPROBE_PATH",
                "FFPROBE_PATH"
        };
        char *target = resolve_env_path(env, envBlock, keys, ARRAY_SIZE(keys));
        redirect_ffmpeg_tool(env, "ffprobe", target, &prog, &argBlock, &argc, &envBlock, &envc);
        free(target);
    }

    free(program);
    return orig_ProcessImpl_forkAndExec(env, process, mode, helperpath, prog, argBlock,
                                        argc, envBlock, envc, dir, std_fds, redirectErrorStream);
}

static bool register_fork_and_exec(JNIEnv *env, const char *className) {
    jclass cls = (*env)->FindClass(env, className);
    if (cls == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return false;
    }

    JNINativeMethod methods[] = {
            {"forkAndExec", "(I[B[B[BI[BI[B[IZ)I", (void *)&hooked_ProcessImpl_forkAndExec}
    };

    jint result = (*env)->RegisterNatives(env, cls, methods, 1);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (result == 0) {
        log_info("Registered forkAndExec for %s", className);
        return true;
    }

    log_warn("RegisterNatives failed for %s result=%d", className, result);
    return false;
}

void hookExec(void) {
    JNIEnv *env = droidbridge_environ->runtimeJNIEnvPtr_JRE;
    if (env == NULL) {
        log_warn("runtime JNI env is null, cannot hook forkAndExec");
        return;
    }

    orig_ProcessImpl_forkAndExec = dlsym(RTLD_DEFAULT, "Java_java_lang_ProcessImpl_forkAndExec");
    if (orig_ProcessImpl_forkAndExec == NULL) {
        orig_ProcessImpl_forkAndExec = dlsym(RTLD_DEFAULT, "Java_java_lang_UNIXProcess_forkAndExec");
    }

    if (orig_ProcessImpl_forkAndExec == NULL) {
        log_warn("Unable to find original forkAndExec symbol");
        return;
    }

    bool process_impl = register_fork_and_exec(env, "java/lang/ProcessImpl");
    bool unix_process = register_fork_and_exec(env, "java/lang/UNIXProcess");
    log_info("forkAndExec registration complete ProcessImpl=%d UNIXProcess=%d",
             process_impl ? 1 : 0,
             unix_process ? 1 : 0);
}
