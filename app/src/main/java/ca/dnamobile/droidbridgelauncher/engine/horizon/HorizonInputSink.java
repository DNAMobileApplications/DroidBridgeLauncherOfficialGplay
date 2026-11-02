/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.view.View;

import androidx.annotation.NonNull;

import org.lwjgl.glfw.CallbackBridge;

import java.util.function.IntSupplier;

import dev.horizon.android.HorizonInput;

/**
 * CallbackBridge's input, sent to the Horizon engine instead of a GLFW window.
 *
 * DroidBridge's controls address the game in CallbackBridge's own coordinate space
 * (windowWidth x windowHeight, which DroidBridge's resolution scaling may shrink). The engine
 * expects Android window pixels, so positions are rescaled to the controls window and
 * shifted to the engine window's origin.
 */
final class HorizonInputSink implements CallbackBridge.HostInputSink {
    private final View controlsRoot;
    private final View engineDecor;
    private final IntSupplier imeViewportInset;
    private final int[] rootOnScreen = new int[2];
    private final int[] decorOnScreen = new int[2];

    HorizonInputSink(
            @NonNull View controlsRoot,
            @NonNull View engineDecor,
            @NonNull IntSupplier imeViewportInset
    ) {
        this.controlsRoot = controlsRoot;
        this.engineDecor = engineDecor;
        this.imeViewportInset = imeViewportInset;
    }

    private float toWindowX(float x) {
        float scale = Math.max(1, controlsRoot.getWidth()) / (float) Math.max(1, CallbackBridge.windowWidth);
        return x * scale + originDeltaX();
    }

    private float toWindowY(float y) {
        float scale = Math.max(1, controlsRoot.getHeight()) / (float) Math.max(1, CallbackBridge.windowHeight);
        // GameImeViewportController visually translates Horizon upward while the IME is
        // open. Map touches back into the engine's original, unshifted window coordinates
        // exactly as MinecraftGLSurface does for the Java engine.
        return y * scale + originDeltaY() + Math.max(0, imeViewportInset.getAsInt());
    }

    private float originDeltaX() {
        try {
            controlsRoot.getLocationOnScreen(rootOnScreen);
            engineDecor.getLocationOnScreen(decorOnScreen);
            return rootOnScreen[0] - decorOnScreen[0];
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private float originDeltaY() {
        try {
            controlsRoot.getLocationOnScreen(rootOnScreen);
            engineDecor.getLocationOnScreen(decorOnScreen);
            return rootOnScreen[1] - decorOnScreen[1];
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    @Override
    public void cursorPos(float x, float y) {
        HorizonInput.cursor(toWindowX(x), toWindowY(y));
    }

    @Override
    public void cursorPosSilently(float x, float y) {
        HorizonInput.cursorSilently(toWindowX(x), toWindowY(y));
    }

    @Override
    public void mouseButton(int glfwButton, boolean down) {
        HorizonInput.mouseButton(glfwButton, down);
    }

    @Override
    public void scroll(double xOffset, double yOffset) {
        HorizonInput.scroll((float) xOffset, (float) yOffset);
    }

    @Override
    public void key(int glfwKey, boolean down, int mods) {
        HorizonInput.key(glfwKey, down);
    }

    @Override
    public void character(char codepoint) {
        HorizonInput.text(String.valueOf(codepoint));
    }
}
