package org.lwjgl.glfw;

import java.util.concurrent.locks.LockSupport;

/**
 * BTA 8-specific GLFW presentation fallback.
 *
 * Real EGL swap interval remains enabled. This helper only waits when the
 * previous swap returned before the next display-rate deadline, so a driver
 * that already blocks on VSync is not paced a second time.
 */
final class DroidBridgeBtaVsync {
    private static volatile int swapInterval;
    private static long nextFrameNanos;
    private static boolean fallbackLogged;

    private DroidBridgeBtaVsync() {
    }

    static void onSwapInterval(int interval) {
        if (!Boolean.getBoolean("droidbridge.bta.vsyncPacer")) return;
        swapInterval = Math.max(0, interval);
        nextFrameNanos = 0L;
        fallbackLogged = false;
        System.out.println("DroidBridgeBtaVsync: live glfwSwapInterval=" + swapInterval
                + " adaptiveFallback=" + (swapInterval > 0));
    }

    static void afterSwap() {
        if (!Boolean.getBoolean("droidbridge.bta.vsyncPacer")) return;

        int interval = swapInterval;
        if (interval <= 0) {
            nextFrameNanos = 0L;
            return;
        }

        int targetFps = 60;
        String configured = System.getenv("DROIDBRIDGE_VSYNC_TARGET_FPS");
        if (configured != null && !configured.isEmpty()) {
            try {
                targetFps = Integer.parseInt(configured.trim());
            } catch (NumberFormatException ignored) {
                targetFps = 60;
            }
        }
        if (targetFps < 30 || targetFps > 360) targetFps = 60;

        long period = (1_000_000_000L / targetFps) * (long) interval;
        long now = System.nanoTime();
        long next = nextFrameNanos;

        // First frame, resume, refresh-rate switch, or a long stall: re-anchor.
        if (next == 0L || now > next + period * 2L) {
            nextFrameNanos = now + period;
            return;
        }

        long wait = next - now;
        if (wait > 0L) {
            if (!fallbackLogged) {
                fallbackLogged = true;
                System.out.println("DroidBridgeBtaVsync: adaptive fallback engaged targetFps="
                        + targetFps + " interval=" + interval);
            }
            LockSupport.parkNanos(wait);
        }
        nextFrameNanos = next + period;
    }
}
