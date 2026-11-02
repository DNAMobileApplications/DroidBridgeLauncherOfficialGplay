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

//
// Created by Vera-Firefly on 17.01.2025
//

#ifndef LINKER_HOOK_H
#define LINKER_HOOK_H

#include <android/dlext.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void set_handles(void* handle, void* dlopen_ext, void* get_namespace);
void* dlopen_ext(const char* filename, int flags, const android_dlextinfo* extinfo);
void* load_sphal_library(const char* filename, int flags);
uint64_t hook_atrace_get_enabled_tags();

#ifdef __cplusplus
}
#endif

#endif // LINKER_HOOK_H