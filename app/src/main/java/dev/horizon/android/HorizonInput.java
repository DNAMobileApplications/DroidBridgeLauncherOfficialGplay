/*
 * Host controls bridge for the Horizon engine in DroidBridge Launcher.
 *
 * The natives are implemented in crates/horizon-client/src/android/host_input_jni.rs of the
 * patched Horizon engine and must stay on dev.horizon.android.HorizonInput. Only call them
 * from the :horizon process, after HorizonActivity has started the engine with host controls.
 */

package dev.horizon.android;

/** GLFW-style keyboard and mouse input into the running Horizon engine. */
public final class HorizonInput {
    static { System.loadLibrary("horizon_android"); }

    private HorizonInput() {
    }

    /** A GLFW key code (glfw3.h) pressed or released. */
    public static void key(int glfwKey, boolean pressed) {
        nativeKey(glfwKey, pressed);
    }

    /** Typed or committed text, for text fields such as chat and signs. */
    public static void text(String text) {
        if (text != null && !text.isEmpty()) nativeText(text);
    }

    /** The pointer at a position in the game window, in Android window pixels. */
    public static void cursor(float x, float y) {
        nativeCursor(x, y);
    }

    /** Moves the pointer baseline without generating motion. */
    public static void cursorSilently(float x, float y) {
        nativeCursorSilently(x, y);
    }

    /** A GLFW mouse button (0 left, 1 right, 2 middle) pressed or released. */
    public static void mouseButton(int glfwButton, boolean pressed) {
        nativeMouseButton(glfwButton, pressed);
    }

    /** Wheel lines; positive y scrolls up. */
    public static void scroll(float x, float y) {
        nativeScroll(x, y);
    }

    /** True while the game holds the pointer: no screen open, the camera follows the mouse. */
    public static boolean isGrabbed() {
        return nativeIsGrabbed();
    }

    /**
     * DroidBridge's existing IME viewport controller reports how many physical Android pixels
     * the completed Horizon frame should be translated upward while the system keyboard is open.
     * This is presentation-only: Horizon keeps the same render size and Minecraft GUI scale.
     */
    public static void setImeViewportBottomInset(int bottomInsetPx) {
        try {
            nativeSetImeViewportBottomInset(Math.max(0, bottomInsetPx));
        } catch (UnsatisfiedLinkError ignored) {
            // Older engine build: leave the frame unshifted rather than crashing the game.
        }
    }

    /** The game's frames per second over the last second; 0 when not known yet. */
    public static int fps() {
        try {
            return nativeFps();
        } catch (UnsatisfiedLinkError error) {
            // An engine library built before the FPS hook.
            return 0;
        }
    }

    /** The cursor the game wants, as an SDL system cursor id (0 arrow, 10 not allowed, 11 hand). */
    public static int cursorShape() {
        try {
            return nativeCursorShape();
        } catch (UnsatisfiedLinkError error) {
            return 0;
        }
    }

    /** GPU, backend and driver information reported by wgpu. */
    public static String graphicsInfo() {
        try {
            String value = nativeGraphicsInfo();
            return value == null ? "" : value;
        } catch (UnsatisfiedLinkError error) {
            return "";
        }
    }

    /** 0 not started, 1 Vulkan, 2 Vulkan with GPU batching, 3 OpenGL ES, 4 OpenGL ES recovery. */
    public static int rendererState() {
        try {
            return nativeRendererState();
        } catch (UnsatisfiedLinkError error) {
            return 0;
        }
    }

    /** 0 the system Vulkan driver, 1 the chosen driver, 2 the chosen driver failed to load. */
    public static int vulkanDriverState() {
        try {
            return nativeVulkanDriverState();
        } catch (UnsatisfiedLinkError error) {
            return 0;
        }
    }

    private static native void nativeKey(int glfwKey, boolean pressed);
    private static native void nativeText(String text);
    private static native void nativeCursor(float x, float y);
    private static native void nativeCursorSilently(float x, float y);
    private static native void nativeMouseButton(int glfwButton, boolean pressed);
    private static native void nativeScroll(float x, float y);
    private static native boolean nativeIsGrabbed();
    private static native void nativeSetImeViewportBottomInset(int bottomInsetPx);
    private static native int nativeFps();
    private static native int nativeCursorShape();
    private static native String nativeGraphicsInfo();
    private static native int nativeRendererState();
    private static native int nativeVulkanDriverState();
}
