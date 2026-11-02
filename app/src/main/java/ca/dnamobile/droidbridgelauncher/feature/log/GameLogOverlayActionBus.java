/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.feature.log;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local dispatcher for launcher-owned controls that toggle the live game log window.
 */
public final class GameLogOverlayActionBus {
    public interface Listener {
        void onGameLogOverlayToggleRequested();
    }

    private static final AtomicReference<Listener> LISTENER = new AtomicReference<>();

    private GameLogOverlayActionBus() {
    }

    public static void setListener(@Nullable Listener next) {
        LISTENER.set(next);
    }

    public static void clearListener(@Nullable Listener expected) {
        if (expected == null) {
            LISTENER.set(null);
            return;
        }
        LISTENER.compareAndSet(expected, null);
    }

    public static void requestToggle() {
        Listener current = LISTENER.get();
        if (current != null) {
            current.onGameLogOverlayToggleRequested();
        }
    }
}
