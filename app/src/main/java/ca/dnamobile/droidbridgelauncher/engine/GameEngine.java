/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The game engines DroidBridge can start an instance with.
 *
 * JAVA is Mojang's game running on the bundled OpenJDK: every version from 1.0 to 26.3,
 * every mod loader. HORIZON is the Rust reimplementation of vanilla 26.3: faster world
 * loading and lower memory use, but no Java mods.
 */
public enum GameEngine {
    JAVA("java"),
    HORIZON("horizon");

    @NonNull
    public final String id;

    GameEngine(@NonNull String id) {
        this.id = id;
    }

    @Nullable
    public static GameEngine fromId(@Nullable String id) {
        if (id == null) return null;
        for (GameEngine engine : values()) {
            if (engine.id.equals(id)) return engine;
        }
        return null;
    }
}
