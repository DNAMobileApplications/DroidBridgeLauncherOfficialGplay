package org.lwjgl.system;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.LockSupport;

/**
 * DroidBridge's renderer-independent FPS bridge for embedded LWJGL.
 *
 * Vulkan rules:
 *  - vkQueuePresentKHR is presentation/backend observation only.
 *  - FIFO present mode is requested for VSync, but some Android Vulkan/SurfaceFlinger
 *    paths do not back-pressure VulkanMod's render loop at the physical panel rate.
 *  - Never pace on glfwPollEvents/SDL_PollEvent. Pace once per successful
 *    vkAcquireNextImageKHR cycle, which is also the FPS sample source.
 *
 * OpenGL rules:
 *  - the existing SDL/OpenGL swap marker remains one frame.
 */
public final class DroidBridgeFrameCounter {
    private static final long SAMPLE_NS = 1_000_000_000L;
    private static final int VK_SUCCESS = 0;
    private static final int VK_SUBOPTIMAL_KHR = 1000001003;
    private static final int VK_ERROR_OUT_OF_DATE_KHR = -1000001004;

    private static long sampleStartNs;
    private static long frameCount;
    private static String outputPath;
    private static boolean firstSampleLogged;

    private static boolean vulkanPresentationObserved;
    private static boolean nativeVulkanNotified;
    private static boolean nativeLiveQueryAvailable = true;
    private static boolean nativePauseQueryAvailable = true;
    private static boolean nativeLifecycleGenerationQueryAvailable = true;
    private static boolean nativeTargetQueryAvailable = true;

    private static int acquireLifecycleGeneration;
    private static boolean forceAcquireOutOfDateAfterResume;
    private static boolean pausedAcquireLogged;
    private static boolean pausedTimeoutLogged;

    private static boolean swapchainStateInitialized;
    private static boolean swapchainVsyncEnabled;
    private static long swapchainGeneration;
    private static boolean swapchainRecreateLogged;

    private static boolean logicalMarkerLogged;
    private static boolean ignoredSdlMarkerLogged;

    private static long vulkanPaceDeadlineNs;
    private static int vulkanPaceTargetFps;
    private static boolean vulkanPaceLogged;

    private DroidBridgeFrameCounter() {
    }

    public static synchronized void markVulkanFramePresented() {
        if (!vulkanPresentationObserved) {
            vulkanPresentationObserved = true;
            System.out.println("DroidBridgeVulkanFPS: Vulkan presentation observed; FIFO requested and successful image-acquire cycles own supplemental pacing/FPS");
        }
        if (!nativeVulkanNotified) {
            nativeVulkanNotified = true;
            try {
                nativeNotifyVulkanPresentation();
            } catch (Throwable error) {
                System.out.println("DroidBridgeVulkanFPS: OpenJDK Vulkan notification bridge unavailable: " + error);
            }
        }
    }

    public static synchronized void markVulkanSwapchainCreated() {
        boolean enabled = querySwapchainVsyncEnabled();
        swapchainStateInitialized = true;
        swapchainVsyncEnabled = enabled;
        swapchainGeneration++;
        swapchainRecreateLogged = false;
        System.out.println("DroidBridgeVulkanVSync: swapchain generation=" + swapchainGeneration
                + " liveVsync=" + enabled);
    }

    public static synchronized int adjustVulkanPresentResult(int result) {
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            return result;
        }
        if (!swapchainStateInitialized) {
            return result;
        }

        boolean live = querySwapchainVsyncEnabled();
        if (live == swapchainVsyncEnabled) {
            return result;
        }
        if (!swapchainRecreateLogged) {
            swapchainRecreateLogged = true;
            System.out.println("DroidBridgeVulkanVSync: live toggle changed swapchain mode "
                    + swapchainVsyncEnabled + " -> " + live
                    + "; requesting one Vulkan swapchain recreation");
        }
        return VK_ERROR_OUT_OF_DATE_KHR;
    }

    public static synchronized boolean isLogicalVsyncEnabledForSwapchain() {
        return querySwapchainVsyncEnabled();
    }

    /**
     * Kept for ABI compatibility with the patched GLFW class. Event polling is not a
     * render-frame boundary and must never sleep or increment the Vulkan FPS counter.
     */
    public static synchronized void markVulkanLogicalFrame() {
        logIgnoredLogicalMarker("GLFW.glfwPollEvents");
    }

    /** Kept for ABI compatibility with the patched SDL3 class. */
    public static synchronized void markSdlVulkanLogicalFrame() {
        logIgnoredLogicalMarker("SDL3.SDL_PollEvent queue drain");
    }

    /** Kept for ABI compatibility with older SDL3 instrumentation. */
    public static synchronized void markSdlPollResult(boolean hadEvent) {
        if (!hadEvent && envEnabled("DROIDBRIDGE_SDL3_FORCE_VULKAN_IDENTITY")) {
            logIgnoredLogicalMarker("SDL3.SDL_PollEvent queue drain");
        }
    }

    private static void logIgnoredLogicalMarker(String source) {
        if (!vulkanPresentationObserved || logicalMarkerLogged) return;
        logicalMarkerLogged = true;
        System.out.println("DroidBridgeVulkanFPS: " + source
                + " is event pumping only; not used for Vulkan FPS or pacing");
        System.out.println("DroidBridgeVulkanVSync: event-loop sleep disabled; Vulkan acquire-cycle pacing is used only when VSync is enabled");
    }

    public static synchronized void markFramePresented() {
        if (vulkanPresentationObserved) {
            if (!ignoredSdlMarkerLogged) {
                ignoredSdlMarkerLogged = true;
                System.out.println("DroidBridgeSDL3FPS: ignored OpenGL swap marker because Vulkan presentation is active");
            }
            return;
        }
        recordFrame("sdl-opengl");
    }

    public static synchronized void beforeVulkanAcquire() {
        acquireLifecycleGeneration = queryPresentationGeneration();
        if (!queryPresentationPaused()) return;

        if (!pausedAcquireLogged) {
            pausedAcquireLogged = true;
            System.out.println("DroidBridgeVulkanLifecycle: waiting before acquire until Android Surface resumes");
        }
        awaitPresentationResume();
        forceAcquireOutOfDateAfterResume = true;
        pausedAcquireLogged = false;
    }

    public static synchronized int adjustVulkanAcquireResult(int result) {
        boolean paused = queryPresentationPaused();
        int generation = queryPresentationGeneration();
        boolean changed = forceAcquireOutOfDateAfterResume
                || generation != acquireLifecycleGeneration;

        if (paused) {
            if (!pausedTimeoutLogged) {
                pausedTimeoutLogged = true;
                System.out.println("DroidBridgeVulkanLifecycle: acquire returned while Android Surface paused; waiting for resume then requesting swapchain recreation");
            }
            awaitPresentationResume();
            changed = true;
            pausedTimeoutLogged = false;
        }

        if (changed && (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR || result == 2)) {
            forceAcquireOutOfDateAfterResume = false;
            acquireLifecycleGeneration = queryPresentationGeneration();
            System.out.println("DroidBridgeVulkanLifecycle: Android Surface generation changed; returning VK_ERROR_OUT_OF_DATE_KHR for swapchain recovery");
            return VK_ERROR_OUT_OF_DATE_KHR;
        }

        if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
            paceVulkanAcquireCycle();
            recordFrame("vulkan-acquire");
        }
        return result;
    }

    /**
     * VulkanMod can run substantially faster than the physical display on Android even
     * with FIFO selected. Pacing here is safe because this hook runs once for each
     * successful swapchain image-acquire cycle. It therefore cannot double-sleep when
     * VulkanMod pumps GLFW/SDL events more than once per rendered frame.
     *
     * The deadline pacer is self-correcting: if vkAcquireNextImageKHR/FIFO already
     * blocks long enough, there is nothing left to sleep.
     */
    private static void paceVulkanAcquireCycle() {
        // VulkanMod's stable-present compatibility mode may pin FIFO to keep
        // swapchain image counts consistent, but DroidBridge must not add a
        // second software/acquire pacer on top of VulkanMod.
        if (envEnabled("DROIDBRIDGE_VULKANMOD_STABLE_PRESENT_MODE")
                || !queryLiveVsyncEnabled()) {
            resetVulkanPacer();
            return;
        }

        int targetFps = queryLiveTargetFps();
        long intervalNs = SAMPLE_NS / Math.max(1, targetFps);
        long now = System.nanoTime();

        if (vulkanPaceDeadlineNs == 0L || vulkanPaceTargetFps != targetFps) {
            vulkanPaceTargetFps = targetFps;
            vulkanPaceDeadlineNs = now + intervalNs;
            if (!vulkanPaceLogged) {
                vulkanPaceLogged = true;
                System.out.println("DroidBridgeVulkanVSync: acquire-cycle pacing enabled targetFps="
                        + targetFps + " intervalNs=" + intervalNs
                        + " source=vkAcquireNextImageKHR");
            } else {
                System.out.println("DroidBridgeVulkanVSync: acquire-cycle pacing target changed to "
                        + targetFps + " FPS");
            }
            return;
        }

        long sleepNs = vulkanPaceDeadlineNs - now;
        if (sleepNs > 0L) {
            LockSupport.parkNanos(sleepNs);
            now = System.nanoTime();
        }

        // Advance by one interval when close to schedule. If rendering/acquire stalled
        // for more than a frame, resync to now instead of allowing catch-up bursts.
        if (now - vulkanPaceDeadlineNs > intervalNs) {
            vulkanPaceDeadlineNs = now + intervalNs;
        } else {
            vulkanPaceDeadlineNs += intervalNs;
        }
    }

    private static void resetVulkanPacer() {
        vulkanPaceDeadlineNs = 0L;
        vulkanPaceTargetFps = 0;
    }

    private static void awaitPresentationResume() {
        while (queryPresentationPaused()) {
            LockSupport.parkNanos(5_000_000L);
        }
    }

    private static boolean querySwapchainVsyncEnabled() {
        if (envEnabled("DROIDBRIDGE_VULKANMOD_STABLE_PRESENT_MODE")) {
            return envEnabled("DROIDBRIDGE_VULKANMOD_STABLE_FORCE_FIFO");
        }
        return queryLiveVsyncEnabled();
    }

    private static boolean queryLiveVsyncEnabled() {
        if (nativeLiveQueryAvailable) {
            try {
                return nativeIsLogicalVsyncEnabled();
            } catch (Throwable error) {
                nativeLiveQueryAvailable = false;
                System.out.println("DroidBridgeVulkanVSync: OpenJDK live-state bridge unavailable; falling back to launch environment");
            }
        }
        return envEnabled("DROIDBRIDGE_VSYNC_FRAME_PACING")
                || envEnabled("DROIDBRIDGE_VULKAN_FORCE_FIFO");
    }

    private static boolean queryPresentationPaused() {
        if (nativePauseQueryAvailable) {
            try {
                return nativeIsPresentationPaused();
            } catch (Throwable error) {
                nativePauseQueryAvailable = false;
            }
        }
        return false;
    }

    private static int queryPresentationGeneration() {
        if (nativeLifecycleGenerationQueryAvailable) {
            try {
                return nativeGetPresentationGeneration();
            } catch (Throwable error) {
                nativeLifecycleGenerationQueryAvailable = false;
            }
        }
        return 0;
    }

    @SuppressWarnings("unused")
    private static int queryLiveTargetFps() {
        if (nativeTargetQueryAvailable) {
            try {
                return normalizeTargetFps(nativeGetVsyncTargetFps());
            } catch (Throwable error) {
                nativeTargetQueryAvailable = false;
            }
        }
        return parseTargetFps(System.getenv("DROIDBRIDGE_VSYNC_TARGET_FPS"));
    }

    private static int normalizeTargetFps(int fps) {
        return fps >= 30 && fps <= 360 ? fps : 60;
    }

    private static int parseTargetFps(String value) {
        try {
            return normalizeTargetFps(Integer.parseInt(value == null ? "" : value.trim()));
        } catch (Throwable ignored) {
            return 60;
        }
    }

    private static boolean envEnabled(String name) {
        String value = System.getenv(name);
        if (value == null) return false;
        value = value.trim();
        return !value.isEmpty()
                && !"0".equals(value)
                && !"false".equalsIgnoreCase(value)
                && !"off".equalsIgnoreCase(value)
                && !"no".equalsIgnoreCase(value);
    }

    private static void recordFrame(String source) {
        long now = System.nanoTime();
        if (sampleStartNs == 0L) {
            sampleStartNs = now;
            frameCount = 1L;
            return;
        }

        frameCount++;
        long elapsed = now - sampleStartNs;
        if (elapsed < SAMPLE_NS) return;

        int measured = (int) Math.max(0L, Math.min(10_000L,
                (frameCount * SAMPLE_NS + elapsed / 2L) / Math.max(1L, elapsed)));
        frameCount = 0L;
        sampleStartNs = now;
        writeSample(measured, source);
    }

    private static void writeSample(int fps, String source) {
        String path = outputPath;
        if (path == null || path.length() == 0) {
            path = System.getenv("DROIDBRIDGE_SDL3_FPS_FILE");
            if (path == null || path.length() == 0) {
                path = System.getProperty("droidbridge.sdl3.fps.file", "");
            }
            outputPath = path;
        }
        if (path == null || path.length() == 0) return;

        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            String payload = fps + " " + System.currentTimeMillis() + "\n";
            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(payload.getBytes(StandardCharsets.US_ASCII));
                stream.flush();
            }
            if (!firstSampleLogged) {
                firstSampleLogged = true;
                System.out.println("DroidBridgeSDL3FPS: first " + source + " sample=" + fps + " path=" + path);
            }
        } catch (Throwable error) {
            System.out.println("DroidBridgeSDL3FPS: sample write failed path=" + path + " error=" + error);
        }
    }

    private static native void nativeNotifyVulkanPresentation();
    private static native boolean nativeIsLogicalVsyncEnabled();
    private static native int nativeGetVsyncTargetFps();
    private static native boolean nativeIsPresentationPaused();
    private static native int nativeGetPresentationGeneration();
}
