/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * Process-local install state used only to survive Activity recreation caused by
 * rotation, display-size changes, multi-window transitions, or external-display
 * hotplug. The actual installer thread remains the source of truth.
 */
package ca.dnamobile.droidbridgelauncher.installation;

import androidx.annotation.NonNull;

public final class InstallSessionState {
    private static long generation;
    private static boolean active;
    @NonNull
    private static String displayName = "Minecraft";
    @NonNull
    private static String title = "Installing Minecraft";
    @NonNull
    private static String message = "Preparing installation...";
    private static int progress;

    private InstallSessionState() {
    }

    public static synchronized long begin(@NonNull String newDisplayName,
                                          @NonNull String newTitle,
                                          @NonNull String newMessage) {
        generation++;
        active = true;
        displayName = newDisplayName;
        title = newTitle;
        message = newMessage;
        progress = 0;
        return generation;
    }

    public static synchronized void update(long expectedGeneration,
                                           int newProgress,
                                           @NonNull String newMessage) {
        if (!active || expectedGeneration != generation) return;
        progress = Math.max(0, Math.min(100, newProgress));
        message = newMessage;
    }

    public static synchronized boolean finish(long expectedGeneration) {
        if (expectedGeneration != generation) return false;
        active = false;
        return true;
    }

    @NonNull
    public static synchronized Snapshot snapshot() {
        return new Snapshot(generation, active, displayName, title, message, progress);
    }

    public static final class Snapshot {
        public final long generation;
        public final boolean active;
        @NonNull public final String displayName;
        @NonNull public final String title;
        @NonNull public final String message;
        public final int progress;

        private Snapshot(long generation,
                         boolean active,
                         @NonNull String displayName,
                         @NonNull String title,
                         @NonNull String message,
                         int progress) {
            this.generation = generation;
            this.active = active;
            this.displayName = displayName;
            this.title = title;
            this.message = message;
            this.progress = progress;
        }
    }
}
