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
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;

static bool db_contains_bytes(const char* data, size_t len, const char* needle) {
    if (data == NULL || needle == NULL) return false;
    size_t needle_len = strlen(needle);
    if (needle_len == 0 || needle_len > len) return false;

    for (size_t i = 0; i + needle_len <= len; i++) {
        if (memcmp(data + i, needle, needle_len) == 0) return true;
    }
    return false;
}

static bool db_starts_with_bytes(const char* data, size_t len, const char* prefix) {
    if (data == NULL || prefix == NULL) return false;
    size_t prefix_len = strlen(prefix);
    return prefix_len <= len && memcmp(data, prefix, prefix_len) == 0;
}

/*
 * Suppress only high-frequency success diagnostics that have no value in a
 * normal latestlog. Failure lines, EGL lifecycle transitions, surface
 * recovery, swap milestones and real OpenGL errors remain visible.
 */
static bool shouldSuppressLogLine(const char* line, size_t len) {
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) len--;

    if (db_starts_with_bytes(
            line,
            len,
            "DroidBridgeNativeGLFW: makeCurrent ok display=")) {
        return true;
    }

    if (db_starts_with_bytes(
            line,
            len,
            "DroidBridgeNativeGLFW: exported make_current result=1 error=")) {
        return true;
    }

    static const char* made_current =
            "DroidBridgeNativeGLFW: droidbridgeMakeCurrent made nativeglfw context current";
    if (len == strlen(made_current) && memcmp(line, made_current, len) == 0) {
        return true;
    }

    /*
     * Mesa can emit these same GLSL 1.40 UBO warnings hundreds or thousands
     * of times while resources compile. Keep genuine shader errors/debug
     * messages; discard only these two known warning texts.
     */
    if (db_contains_bytes(line, len, "OpenGL debug message:")
            && db_contains_bytes(line, len, "source=SHADER COMPILER")
            && (db_contains_bytes(
                        line,
                        len,
                        "warning: #version 140 / GL_ARB_uniform_buffer_object layout qualifier `std140' is used")
                || db_contains_bytes(
                        line,
                        len,
                        "warning: #version 140 / GL_ARB_uniform_buffer_object required for defining uniform blocks"))) {
        return true;
    }

    return false;
}

static bool recordBuffer(const char* buf, ssize_t len) {
    if (buf == NULL || len <= 0) return false;
    if (db_contains_bytes(buf, (size_t) len, "Session ID is")) return false;

    if (latestlog_fd != -1) {
        const char* cursor = buf;
        ssize_t remaining = len;
        while (remaining > 0) {
            ssize_t written = write(latestlog_fd, cursor, (size_t) remaining);
            if (written > 0) {
                cursor += written;
                remaining -= written;
                continue;
            }
            if (written < 0 && errno == EINTR) continue;
            break;
        }
        fdatasync(latestlog_fd);
    }
    return true;
}

static void dispatchLogLine(JNIEnv* env, const char* line, size_t len) {
    if (line == NULL || len == 0) return;
    if (shouldSuppressLogLine(line, len)) return;

    bool recorded = recordBuffer(line, (ssize_t) len);
    if (!recorded || logListener == NULL || logger_onEventLogged == NULL) return;

    size_t clean_len = len;
    while (clean_len > 0
            && (line[clean_len - 1] == '\n' || line[clean_len - 1] == '\r')) {
        clean_len--;
    }

    char* callback_line = (char*) malloc(clean_len + 1U);
    if (callback_line == NULL) return;
    if (clean_len > 0) memcpy(callback_line, line, clean_len);
    callback_line[clean_len] = '\0';

    jstring writeString = (*env)->NewStringUTF(env, callback_line);
    free(callback_line);
    if (writeString == NULL) return;

    (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
    (*env)->DeleteLocalRef(env, writeString);

    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
    }
}

static void *logger_thread() {
    JNIEnv *env;

    JavaVM* dvm = droidbridge_environ->dalvikJavaVMPtr;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);

    /*
     * stdout is line-buffered, but pipe reads are not guaranteed to line up
     * with write boundaries. Keep a carry buffer so filtering never drops an
     * important line merely because two messages arrived together.
     */
    char read_buf[4096];
    char pending[65536];
    size_t pending_len = 0;

    ssize_t rsize;
    while ((rsize = read(pfd[0], read_buf, sizeof(read_buf))) > 0) {
        for (ssize_t i = 0; i < rsize; i++) {
            char ch = read_buf[i];

            if (pending_len == sizeof(pending)) {
                dispatchLogLine(env, pending, pending_len);
                pending_len = 0;
            }

            pending[pending_len++] = ch;
            if (ch == '\n') {
                dispatchLogLine(env, pending, pending_len);
                pending_len = 0;
            }
        }
    }

    if (pending_len > 0) {
        dispatchLogLine(env, pending, pending_len);
    }

    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if (latestlog_fd != -1)
    {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }

    if (logger_onEventLogged == NULL)
    {
        jclass eventLogListener = (*env)->FindClass(env, "ca/dnamobile/droidbridgelauncher/runtime/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }

    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC | O_CREAT | O_APPEND, 0644);

    if (latestlog_fd == -1)
    {
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, 0);

    if (result != 0)
    {
        close(latestlog_fd);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);

    if (errorCode == JNI_EDETACHED)
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);

    if (errorCode != JNI_OK)
        killpg(getpgrp(), SIGTERM);

    if (code != 0 && exitTrap_exitClass != NULL && exitTrap_staticMethod != NULL && exitTrap_ctx != NULL)
        (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);

    // Delete the reference, not gonna need 'em later anyway
    if (exitTrap_ctx != NULL) (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    if (exitTrap_exitClass != NULL) (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text){
    if (text == NULL) return;

    jsize utfLength = (*env)->GetStringUTFLength(env, text);
    char *buffer = (char *) malloc((size_t) utfLength + 2U);
    if (buffer == NULL) return;

    (*env)->GetStringUTFRegion(
            env,
            text,
            0,
            (*env)->GetStringLength(env, text),
            buffer
    );

    // appendToLog() itself owns the final line break. Strip any CR/LF already
    // supplied by Java callers so one message cannot create an empty line.
    jsize cleanLength = utfLength;
    while (cleanLength > 0
            && (buffer[cleanLength - 1] == '\n' || buffer[cleanLength - 1] == '\r')) {
        cleanLength--;
    }

    buffer[cleanLength] = '\n';
    buffer[cleanLength + 1] = '\0';

    if (shouldSuppressLogLine(buffer, (size_t) cleanLength + 1U)) {
        free(buffer);
        return;
    }

    bool recorded = recordBuffer(buffer, cleanLength + 1);

    if (recorded && logListener != NULL && logger_onEventLogged != NULL) {
        buffer[cleanLength] = '\0';
        jstring cleanText = (*env)->NewStringUTF(env, buffer);
        if (cleanText != NULL) {
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, cleanText);
            (*env)->DeleteLocalRef(env, cleanText);
        }
    }

    free(buffer);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject oldListener = logListener;
    jobject newListener = NULL;

    if (log_listener != NULL)
    {
        // A Java listener can be registered before Logger.begin() redirects stdout.
        // Resolve the callback from the actual listener object so appendToLog() can
        // never reach CallVoidMethod with a null jmethodID.
        if (logger_onEventLogged == NULL)
        {
            jclass listenerClass = (*env)->GetObjectClass(env, log_listener);
            if (listenerClass != NULL)
            {
                jmethodID callback = (*env)->GetMethodID(
                        env,
                        listenerClass,
                        "onEventLogged",
                        "(Ljava/lang/String;)V"
                );
                (*env)->DeleteLocalRef(env, listenerClass);

                if ((*env)->ExceptionCheck(env) == JNI_TRUE)
                {
                    (*env)->ExceptionClear(env);
                    callback = NULL;
                }
                logger_onEventLogged = callback;
            }
        }

        if (logger_onEventLogged == NULL)
        {
            // Reject an unusable listener instead of leaving a partial JNI state.
            return;
        }

        newListener = (*env)->NewGlobalRef(env, log_listener);
        if (newListener == NULL) return;
    }

    logListener = newListener;
    if (oldListener != NULL && oldListener != newListener)
        (*env)->DeleteGlobalRef(env, oldListener);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env, context);
    (*env)->GetJavaVM(env, &exitTrap_jvm);

    jclass localExitClass = (*env)->FindClass(env, "ca/dnamobile/droidbridgelauncher/ErrorActivity");
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
        localExitClass = NULL;
    }

    if (localExitClass == NULL) {
        exitTrap_exitClass = NULL;
        exitTrap_staticMethod = NULL;
        return;
    }

    exitTrap_exitClass = (*env)->NewGlobalRef(env, localExitClass);
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env, exitTrap_exitClass, "showExitMessage", "(Landroid/content/Context;IZ)V");
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
        exitTrap_staticMethod = NULL;
    }
}