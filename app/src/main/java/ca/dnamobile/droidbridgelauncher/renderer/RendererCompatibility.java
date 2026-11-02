/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.renderer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;

/**
 * User-facing Minecraft version guidance for known renderers.
 *
 * This is intentionally descriptive only. It does not block a renderer from being selected;
 * users can still test newer/older versions at their own risk. Plugin-backed guidance appears
 * only when that renderer exists in the discovered renderer list.
 */
public final class RendererCompatibility {
    private RendererCompatibility() {
    }

    @Nullable
    public static String getSummary(@NonNull Context context, @Nullable RendererInterface renderer) {
        Integer resId = getSummaryResource(renderer);
        return resId == null ? null : context.getString(resId);
    }

    @Nullable
    public static Integer getSummaryResource(@Nullable RendererInterface renderer) {
        if (renderer == null) return null;
        if (MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) {
            return R.string.renderer_compatibility_mobileglues;
        }
        if (KopperZinkRenderer.isRenderer(renderer)) {
            return R.string.renderer_compatibility_kopper_zink;
        }
        if (renderer instanceof KryptonRenderer) {
            return R.string.renderer_compatibility_krypton;
        }
        if (renderer instanceof GL4ESRenderer) {
            return R.string.renderer_compatibility_gl4es;
        }

        // LTW is normally plugin-provided, so match its discovered identity only when
        // that renderer actually exists in the current renderer list.
        String lookup = fingerprint(renderer);
        if (lookup.contains("libltw") || lookup.contains(" ltw") || lookup.startsWith("ltw")) {
            return R.string.renderer_compatibility_ltw;
        }
        return null;
    }

    @NonNull
    private static String fingerprint(@NonNull RendererInterface renderer) {
        return (renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getUniqueIdentifier() + " "
                + renderer.getRendererLibrary() + " "
                + String.valueOf(renderer.getRendererEGL()))
                .toLowerCase(Locale.ROOT);
    }
}
