/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.recording;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;

/**
 * Compatibility shim for branches that still reference the short-lived secure-window mirror.
 *
 * A fullscreen FLAG_SECURE window can make MediaProjection encode a black frame, so that design
 * is intentionally retired. Current recording uses RecordingFrameBridge instead and never changes
 * the live touch-control overlay. start() therefore fails closed without modifying the UI.
 */
@Deprecated
public final class RecordingTouchControlsMirror {
    @NonNull private final TouchControlsOverlay sourceOverlay;

    public RecordingTouchControlsMirror(
            @NonNull Activity activity,
            @NonNull TouchControlsOverlay sourceOverlay
    ) {
        this.sourceOverlay = sourceOverlay;
    }

    public boolean start() {
        sourceOverlay.setAlpha(1f);
        return false;
    }

    public boolean isActive() {
        return false;
    }

    public boolean owns(@Nullable TouchControlsOverlay overlay) {
        return sourceOverlay == overlay;
    }

    public void stop() {
        sourceOverlay.setAlpha(1f);
    }
}
