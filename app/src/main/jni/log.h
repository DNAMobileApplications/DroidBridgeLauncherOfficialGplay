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

#ifdef __ANDROID__
#include <android/log.h>

#define TAG "jrelog"
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_SILENT,    TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_SILENT,    TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)

#ifdef __cplusplus
}
#endif

