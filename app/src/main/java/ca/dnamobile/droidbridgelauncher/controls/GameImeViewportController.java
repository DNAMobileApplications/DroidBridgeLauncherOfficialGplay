/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.controls;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

/**
 * Keeps Minecraft's GLFW viewport above Android's IME.
 *
 * the legacy implementation's useful behavior is not a visible Android dialog. It uses a real Android
 * EditText only as the IME anchor, while the game surface/window is allowed to
 * resize so Minecraft's own chat/edit widgets render above the keyboard.
 *
 * DroidBridge is immersive/fullscreen, so Android may not physically resize the
 * Activity for us. This controller mirrors the visible-window bottom inset into
 * MinecraftGLSurface; the surface then shrinks its child render view and sends a
 * smaller GLFW window height to Minecraft. That makes the in-game chat line sit
 * directly above the Android keyboard instead of behind it.
 */
public final class GameImeViewportController {
    /**
     * Optional non-GLFW render target that should receive the same visual IME push.
     * Horizon uses this to translate NativeActivity's real native SurfaceView while
     * DroidBridge's transparent MinecraftGLSurface continues to own touch input.
     */
    public interface ImeViewportTarget {
        void setImeViewportBottomInset(int bottomInsetPx);
    }

    @Nullable private static GameImeViewportController active;
    @Nullable private static WeakReference<MinecraftGLSurface> registeredMinecraftSurface;
    @Nullable private static ImeViewportTarget registeredImeViewportTarget;

    @NonNull private final View anchor;
    @NonNull private final View root;
    @NonNull private final View gameWindowRoot;
    @Nullable private final MinecraftGLSurface minecraftSurface;
    private int lastSurfaceInset = -1;
    private int lastTargetInset = -1;
    private boolean detached;
    private boolean fallbackInsetAllowed;
    private boolean sawRealKeyboardInset;

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateImeInset;

    private GameImeViewportController(@NonNull View anchor, @NonNull View root, @Nullable MinecraftGLSurface minecraftSurface) {
        this.anchor = anchor;
        this.root = root;
        this.minecraftSurface = minecraftSurface;

        // The IME anchor can live in DroidBridge's TYPE_APPLICATION_PANEL overlay while the
        // actual game is the Activity's native window. Those windows can have different
        // bottoms when layout-no-limits, cutouts or gesture navigation are involved. Keep
        // the Activity decor separately so Horizon's inset is calculated in the renderer's
        // own coordinate space rather than the controls overlay's coordinate space.
        Activity activity = findActivity(anchor.getContext());
        View decor = activity != null && activity.getWindow() != null
                ? activity.getWindow().getDecorView() : null;
        this.gameWindowRoot = decor != null ? decor : root;
    }

    /**
     * Register the real Minecraft surface from GameActivity.
     *
     * This is important because the Input touch button may live inside a controls
     * overlay root that is not a direct sibling of the game surface. Searching
     * source.getRootView() alone can therefore miss the surface and the IME resize
     * path becomes a no-op.
     */
    public static void registerMinecraftSurface(@Nullable MinecraftGLSurface surface) {
        registeredMinecraftSurface = surface == null ? null : new WeakReference<>(surface);
    }

    /** Registers an additional visual target for hosts whose real renderer is not MinecraftGLSurface. */
    public static void registerImeViewportTarget(@Nullable ImeViewportTarget target) {
        registeredImeViewportTarget = target;
    }

    /** Removes the target only when it is still the caller's registration. */
    public static void unregisterImeViewportTarget(@NonNull ImeViewportTarget target) {
        if (registeredImeViewportTarget == target) {
            registeredImeViewportTarget = null;
        }
    }

    public static void attach(@NonNull View imeAnchor, @NonNull View searchRoot) {
        detachActive(false);

        View root = bestRootFor(searchRoot);
        MinecraftGLSurface surface = findMinecraftSurface(root);
        if (surface == null) {
            surface = getRegisteredMinecraftSurface();
            if (surface != null) {
                root = bestRootFor(surface);
            }
        }
        GameImeViewportController controller = new GameImeViewportController(imeAnchor, root, surface);
        active = controller;
        controller.attachInternal();
    }

    public static void detachActive(boolean keyboardClosing) {
        GameImeViewportController controller = active;
        active = null;
        if (controller != null) {
            controller.detachInternal(keyboardClosing);
        }
    }

    public static void clearImeViewportInset(@NonNull View searchRoot) {
        MinecraftGLSurface surface = findMinecraftSurface(bestRootFor(searchRoot));
        if (surface == null) {
            surface = getRegisteredMinecraftSurface();
        }
        if (surface != null) {
            surface.setImeViewportBottomInset(0);
        }
        ImeViewportTarget target = registeredImeViewportTarget;
        if (target != null) {
            target.setImeViewportBottomInset(0);
        }
    }

    private void attachInternal() {
        try {
            root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        } catch (Throwable ignored) {
        }
        root.post(this::updateImeInset);
        root.postDelayed(this::updateImeInset, 80L);
        root.postDelayed(() -> {
            fallbackInsetAllowed = true;
            updateImeInset();
        }, 160L);
        root.postDelayed(this::updateImeInset, 260L);
        root.postDelayed(this::updateImeInset, 520L);
        root.postDelayed(this::updateImeInset, 1000L);
    }

    private void detachInternal(boolean keyboardClosing) {
        if (detached) return;
        detached = true;
        try {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        } catch (Throwable ignored) {
        }
        if (minecraftSurface != null) {
            minecraftSurface.setImeViewportBottomInset(0);
        }
        ImeViewportTarget target = registeredImeViewportTarget;
        if (target != null) {
            target.setImeViewportBottomInset(0);
        }
        if (!keyboardClosing) {
            clearImeViewportInset(anchor);
        }
    }

    private void updateImeInset() {
        if (detached) return;

        // Measure the IME as an absolute screen boundary first. This is the key difference
        // from the old implementation: the controls overlay and Horizon's NativeActivity
        // window are not assumed to share the same bottom coordinate.
        int keyboardTopOnScreen = calculateKeyboardTopOnScreen(root);

        if (keyboardTopOnScreen >= 0) {
            sawRealKeyboardInset = true;
            applyKeyboardTop(keyboardTopOnScreen);
            return;
        }

        if (sawRealKeyboardInset) {
            // A real keyboard boundary existed and is now gone. Do not fall back to an
            // estimate, otherwise minimizing Gboard can leave the game stuck halfway up.
            applyInsets(0, 0);
            root.post(TouchKeyboardHelper::notifyImeHiddenBySystem);
            return;
        }

        if (fallbackInsetAllowed) {
            // Old/buggy immersive Android builds can expose neither IME insets nor a useful
            // visible display frame. The fallback remains per-window rather than reusing one
            // guessed pixel value for both windows.
            MinecraftGLSurface surface = minecraftSurface != null
                    ? minecraftSurface : getRegisteredMinecraftSurface();
            int surfaceInset = surface != null ? estimateKeyboardBottomInset(surface) : 0;
            int targetInset = registeredImeViewportTarget != null
                    ? estimateKeyboardBottomInset(gameWindowRoot) : 0;
            applyInsets(surfaceInset, targetInset);
        } else {
            applyInsets(0, 0);
        }
    }

    /**
     * Converts one physical keyboard top edge into the covered height of each actual target.
     * This makes the result independent of keyboard brand/height and of overlay-window insets.
     */
    private void applyKeyboardTop(int keyboardTopOnScreen) {
        MinecraftGLSurface surface = minecraftSurface != null
                ? minecraftSurface : getRegisteredMinecraftSurface();
        int surfaceInset = surface != null
                ? coveredBottomPixels(surface, keyboardTopOnScreen) : 0;
        int targetInset = registeredImeViewportTarget != null
                ? coveredBottomPixels(gameWindowRoot, keyboardTopOnScreen) : 0;
        applyInsets(surfaceInset, targetInset);
    }

    private void applyInsets(int surfaceInset, int targetInset) {
        MinecraftGLSurface surface = minecraftSurface != null
                ? minecraftSurface : getRegisteredMinecraftSurface();
        int safeTargetInset = clampKeyboardInset(gameWindowRoot, targetInset);
        // With a registered target (an embedded engine such as Horizon) the surface only
        // translates touches; the picture is shifted by the target inset. Use that exact value
        // for touches too, so a tap always lands where the finger is, even if the controls
        // window and the game window ever differ in size.
        int safeSurfaceInset = surface == null ? 0
                : (registeredImeViewportTarget != null
                        ? safeTargetInset
                        : clampKeyboardInset(surface, surfaceInset));

        if (surface != null && safeSurfaceInset != lastSurfaceInset) {
            lastSurfaceInset = safeSurfaceInset;
            surface.setImeViewportBottomInset(safeSurfaceInset);
        }

        ImeViewportTarget target = registeredImeViewportTarget;
        if (target != null && safeTargetInset != lastTargetInset) {
            lastTargetInset = safeTargetInset;
            target.setImeViewportBottomInset(safeTargetInset);
        }
    }

    /**
     * Returns the physical screen Y where the keyboard begins, or -1 when Android is not
     * currently exposing a real IME boundary. Both modern WindowInsets and the visible-frame
     * path are sampled; the higher keyboard top wins so we never under-count the obstruction.
     */
    private static int calculateKeyboardTopOnScreen(@NonNull View imeRoot) {
        int minKeyboard = minKeyboardPx(imeRoot);
        int[] location = new int[2];
        try {
            imeRoot.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            location[0] = 0;
            location[1] = 0;
        }
        int rootBottomOnScreen = location[1] + Math.max(1, imeRoot.getHeight());
        int bestTop = Integer.MAX_VALUE;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInsets insets = imeRoot.getRootWindowInsets();
                if (insets != null) {
                    int imeBottom = Math.max(0, insets.getInsets(WindowInsets.Type.ime()).bottom);
                    boolean imeVisible = insets.isVisible(WindowInsets.Type.ime());
                    if (imeBottom >= minKeyboard || (imeVisible && imeBottom > 0)) {
                        int top = rootBottomOnScreen - imeBottom;
                        if (rootBottomOnScreen - top >= minKeyboard) {
                            bestTop = Math.min(bestTop, top);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Rect rect = new Rect();
            imeRoot.getWindowVisibleDisplayFrame(rect);
            int obscuredBottom = rootBottomOnScreen - rect.bottom;
            if (obscuredBottom >= minKeyboard) {
                // rect.bottom is already an absolute screen coordinate.
                bestTop = Math.min(bestTop, rect.bottom);
            }
        } catch (Throwable ignored) {
        }

        return bestTop == Integer.MAX_VALUE ? -1 : bestTop;
    }

    private static int coveredBottomPixels(@NonNull View target, int keyboardTopOnScreen) {
        try {
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            int targetBottomOnScreen = location[1] + Math.max(1, target.getHeight());
            return Math.max(0, targetBottomOnScreen - keyboardTopOnScreen);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int estimateKeyboardBottomInset(@NonNull View root) {
        int height = Math.max(1, root.getHeight());
        int minKeyboard = minKeyboardPx(root);

        // Landscape Android keyboards commonly cover roughly 45-55% of the screen.
        // Use this only as an immersive-fullscreen fallback when Android refuses to
        // report the real IME inset. Keep at least a small playable viewport visible.
        int estimated = Math.max(minKeyboard, Math.round(height * 0.48f));
        return clampKeyboardInset(root, estimated);
    }

    private static int minKeyboardPx(@NonNull View root) {
        return Math.max(80, Math.round(root.getResources().getDisplayMetrics().density * 96f));
    }

    private static int clampKeyboardInset(@NonNull View root, int inset) {
        int height = Math.max(1, root.getHeight());
        int minVisible = Math.max(120, Math.round(root.getResources().getDisplayMetrics().density * 120f));
        return Math.max(0, Math.min(inset, Math.max(1, height - minVisible)));
    }

    @NonNull
    private static View bestRootFor(@NonNull View view) {
        View root = view.getRootView();
        if (root != null) return root;

        Activity activity = findActivity(view.getContext());
        if (activity != null) {
            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decor != null) return decor;
        }

        return view;
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @Nullable
    private static MinecraftGLSurface getRegisteredMinecraftSurface() {
        WeakReference<MinecraftGLSurface> ref = registeredMinecraftSurface;
        return ref == null ? null : ref.get();
    }

    @Nullable
    private static MinecraftGLSurface findMinecraftSurface(@Nullable View view) {
        if (view == null) return null;
        if (view instanceof MinecraftGLSurface) return (MinecraftGLSurface) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                MinecraftGLSurface found = findMinecraftSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
