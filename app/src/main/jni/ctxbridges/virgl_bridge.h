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
// Created by Vera-Firefly on 20.08.2024.
//

#ifndef VIRGL_BRIDGE_H
#define VIRGL_BRIDGE_H

bool loadSymbolsVirGL();
int virglInit();
void* virglCreateContext(void* contextSrc);
void* virglGetCurrentContext();
void virglMakeCurrent(void* window);
void virglSwapBuffers();
void virglSwapInterval(int interval);

#endif //VIRGL_BRIDGE_H
