/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.controls.TouchKeyboardHelper;
import ca.dnamobile.droidbridgelauncher.game.FloatingGameSettingsOverlayController;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSdlFpsBridge;
import ca.dnamobile.droidbridgelauncher.runtime.LwjglGlfwKeycode;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

/**
 * DroidBridge's bottom-screen host.
 *
 * The existing DualScreenControlsView remains the one and only second-screen HUD surface.
 * Launcher-owned controls such as the custom text keyboard sit above it without changing the
 * Minecraft rendering bridge.
 */
final class DualScreenDeckView extends FrameLayout implements DualScreenLiveBridge.Listener {
    @NonNull private final DualScreenLiveBridge liveBridge;
    @NonNull private final DualScreenControlsView controlsView;
    @NonNull private final FloatingGameSettingsOverlayController floatingOverlayController;
    @NonNull private final ImageButton settingsButton;
    @NonNull private final Handler uiHandler = new Handler(Looper.getMainLooper());
    @Nullable private LegacyMinecraftButtonView textKeyboardButton;
    @Nullable private LegacyImageButtonView inventoryButton;
    @Nullable private LegacyImageButtonView craftButton;
    @Nullable private LegacyImageButtonView legacyKeyboardButton;
    @Nullable private LegacyImageButtonView pauseButton;
    @Nullable private LegacyImageButtonView launcherMenuRailButton;
    @Nullable private LegacyImageButtonView emptyRailButtonOne;
    @Nullable private LegacyImageButtonView mapRailButton;
    @Nullable private LegacyImageButtonView threeDsFpsToggleButton;
    @Nullable private LegacyImageButtonView threeDsArrowButton;
    @Nullable private MinecraftFpsView fpsView;
    @Nullable private DualScreenTextKeyboardView textKeyboardView;
    private boolean attached;
    @NonNull private String lastHudLayout = "";
    private boolean lastFourThreeLayout;
    private boolean lastFpsEnabled;
    private boolean threeDsAltButtons;
    private boolean threeDsPageAnimating;

    private final Runnable fpsTicker = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            String hudLayout = LauncherPreferences.getDualScreenHudLayout(getContext());
            boolean fourThree = LauncherPreferences.isDualScreenFourThreeLayout(getContext());
            boolean fpsEnabled = LauncherPreferences.isDualScreenFpsEnabled(getContext());
            if (!hudLayout.equals(lastHudLayout) || fourThree != lastFourThreeLayout || fpsEnabled != lastFpsEnabled) {
                updateLowerScreenMenuLayout();
            }
            refreshFpsCounter();
            uiHandler.postDelayed(this, 500L);
        }
    };

    DualScreenDeckView(
            @NonNull Context context,
            @NonNull File hudStateFile,
            @NonNull Runnable launcherMenuCallback
    ) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(Color.TRANSPARENT);

        controlsView = new DualScreenControlsView(context, hudStateFile, launcherMenuCallback);
        addView(controlsView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        settingsButton = new ImageButton(context);
        settingsButton.setBackgroundResource(R.drawable.bg_ingame_settings_button);
        settingsButton.setImageTintList(null);
        settingsButton.setImageResource(R.drawable.ic_ingame_settings_24);
        settingsButton.setScaleType(ImageView.ScaleType.CENTER);
        settingsButton.setImageAlpha(255);
        settingsButton.setAlpha(1f);
        settingsButton.setContentDescription(context.getString(R.string.game_button_overlay_settings));
        int densityPadding = dp(context, 12);
        settingsButton.setPadding(densityPadding, densityPadding, densityPadding, densityPadding);
        settingsButton.setOnClickListener(view -> launcherMenuCallback.run());

        LayoutParams settingsParams = new LayoutParams(dp(context, 48), dp(context, 48));
        settingsParams.gravity = Gravity.END | Gravity.BOTTOM;
        int settingsMargin = dp(context, 16);
        settingsParams.setMargins(settingsMargin, settingsMargin, settingsMargin, settingsMargin);
        addView(settingsButton, settingsParams);

        LegacyMinecraftButtonView keyboardButton = new LegacyMinecraftButtonView(
                context,
                controlsView,
                "Keyboard"
        );
        keyboardButton.setContentDescription("Open DroidBridge Minecraft text keyboard");
        keyboardButton.setOnClickListener(view -> showTextKeyboard());
        addView(keyboardButton, new LayoutParams(dp(context, 126), dp(context, 42)));
        textKeyboardButton = keyboardButton;

        LegacyImageButtonView inventory = new LegacyImageButtonView(context, R.drawable.inventory_button);
        inventory.setContentDescription("Open Minecraft inventory");
        inventory.setOnClickListener(view -> sendMinecraftKeyTap(LwjglGlfwKeycode.GLFW_KEY_E));
        inventory.setVisibility(GONE);
        addView(inventory, new LayoutParams(dp(context, 96), dp(context, 48)));
        inventoryButton = inventory;

        LegacyImageButtonView craft = new LegacyImageButtonView(context, R.drawable.craft_button);
        craft.setContentDescription("Open Minecraft crafting/inventory menu");
        craft.setOnClickListener(view -> sendMinecraftKeyTap(LwjglGlfwKeycode.GLFW_KEY_E));
        craft.setVisibility(GONE);
        addView(craft, new LayoutParams(dp(context, 96), dp(context, 48)));
        craftButton = craft;

        LegacyImageButtonView legacyKeyboard = new LegacyImageButtonView(context, R.drawable.keyboard_button);
        legacyKeyboard.setContentDescription("Open DroidBridge Minecraft text keyboard");
        legacyKeyboard.setOnClickListener(view -> showTextKeyboard());
        legacyKeyboard.setVisibility(GONE);
        addView(legacyKeyboard, new LayoutParams(dp(context, 96), dp(context, 48)));
        legacyKeyboardButton = legacyKeyboard;

        LegacyImageButtonView pause = new LegacyImageButtonView(context, R.drawable.pause_button);
        pause.setContentDescription("Open Minecraft game menu");
        pause.setOnClickListener(view -> sendMinecraftKeyTap(LwjglGlfwKeycode.GLFW_KEY_ESCAPE));
        pause.setVisibility(GONE);
        addView(pause, new LayoutParams(dp(context, 96), dp(context, 48)));
        pauseButton = pause;

        LegacyImageButtonView launcherMenu = new LegacyImageButtonView(context, R.drawable.settings_button);
        launcherMenu.setContentDescription("Open DroidBridge launcher in-game menu");
        launcherMenu.setOnClickListener(view -> launcherMenuCallback.run());
        launcherMenu.setVisibility(GONE);
        addView(launcherMenu, new LayoutParams(dp(context, 96), dp(context, 48)));
        launcherMenuRailButton = launcherMenu;

        LegacyImageButtonView emptyOne = new LegacyImageButtonView(context, R.drawable.empty_button);
        emptyOne.setContentDescription("Reserved legacy button slot");
        emptyOne.setOnClickListener(view -> { });
        emptyOne.setVisibility(GONE);
        addView(emptyOne, new LayoutParams(dp(context, 96), dp(context, 48)));
        emptyRailButtonOne = emptyOne;

        LegacyImageButtonView mapButton = new LegacyImageButtonView(context, R.drawable.map_button);
        mapButton.setContentDescription("Show or hide the dual-screen map");
        mapButton.setOnClickListener(view -> controlsView.toggleMapFromDeckButton());
        mapButton.setVisibility(GONE);
        addView(mapButton, new LayoutParams(dp(context, 96), dp(context, 48)));
        mapRailButton = mapButton;

        LegacyImageButtonView fpsToggle = new LegacyImageButtonView(context, R.drawable.show_fps);
        fpsToggle.setContentDescription("Show or hide the dual-screen FPS counter");
        fpsToggle.setOnClickListener(view -> {
            boolean enabled = !LauncherPreferences.isDualScreenFpsEnabled(getContext());
            LauncherPreferences.setDualScreenFpsEnabled(getContext(), enabled);
            updateLowerScreenMenuLayout();
            refreshFpsCounter();
        });
        fpsToggle.setVisibility(GONE);
        addView(fpsToggle, new LayoutParams(dp(context, 96), dp(context, 48)));
        threeDsFpsToggleButton = fpsToggle;

        LegacyImageButtonView arrow = new LegacyImageButtonView(context, R.drawable.arrow_changer);
        arrow.setContentDescription("Switch 3DS button page");
        arrow.setOnClickListener(view -> toggleThreeDsButtonPage());
        arrow.setVisibility(GONE);
        addView(arrow, new LayoutParams(dp(context, 32), dp(context, 36)));
        threeDsArrowButton = arrow;

        MinecraftFpsView counter = new MinecraftFpsView(context, controlsView);
        counter.setVisibility(GONE);
        addView(counter, new LayoutParams(dp(context, 104), dp(context, 28)));
        fpsView = counter;

        floatingOverlayController = new FloatingGameSettingsOverlayController(context, settingsButton);
        // The dual-screen deck owns both the in-game settings affordance and the FPS readout.
        // Suppress the floating Android overlay entirely while dual-screen is active.
        floatingOverlayController.setFpsSuppressed(true);
        floatingOverlayController.setSuppressed(true);
        settingsButton.setVisibility(GONE);

        liveBridge = new DualScreenLiveBridge(this, hudStateFile, context.getCacheDir());
        controlsView.setLiveIconBridge(liveBridge);

        post(this::updateLowerScreenMenuLayout);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateLowerScreenMenuLayout();
    }

    private void updateLowerScreenMenuLayout() {
        LegacyMinecraftButtonView modernKeyboard = textKeyboardButton;
        LegacyImageButtonView inventory = inventoryButton;
        LegacyImageButtonView craft = craftButton;
        LegacyImageButtonView keyboard = legacyKeyboardButton;
        LegacyImageButtonView pause = pauseButton;
        LegacyImageButtonView launcherMenu = launcherMenuRailButton;
        LegacyImageButtonView emptyOne = emptyRailButtonOne;
        LegacyImageButtonView mapButton = mapRailButton;
        LegacyImageButtonView fpsToggle = threeDsFpsToggleButton;
        LegacyImageButtonView arrow = threeDsArrowButton;
        MinecraftFpsView counter = fpsView;
        if (modernKeyboard == null || inventory == null || craft == null || keyboard == null || pause == null
                || launcherMenu == null || emptyOne == null || mapButton == null || fpsToggle == null
                || arrow == null || counter == null) return;

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        String hudLayout = LauncherPreferences.getDualScreenHudLayout(getContext());
        boolean legacy = LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_LEGACY.equals(hudLayout);
        boolean threeDs = LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS.equals(hudLayout);
        boolean fourThree = LauncherPreferences.isDualScreenFourThreeLayout(getContext());
        boolean fpsEnabled = LauncherPreferences.isDualScreenFpsEnabled(getContext());
        boolean enteringThreeDs = threeDs && !LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS.equals(lastHudLayout);
        if (enteringThreeDs) {
            threeDsAltButtons = false;
            threeDsPageAnimating = false;
        }
        lastHudLayout = hudLayout;
        lastFourThreeLayout = fourThree;
        lastFpsEnabled = fpsEnabled;

        RectF viewport = calculateLayoutViewport(width, height, fourThree);
        float density = getResources().getDisplayMetrics().density;
        float pad = Math.max(10f * density, Math.min(viewport.width(), viewport.height()) * 0.025f);

        modernKeyboard.setLegacyMode(false);
        modernKeyboard.setVisibility(!legacy && !threeDs ? VISIBLE : GONE);
        boolean primary3dsPage = threeDs && !threeDsAltButtons;
        boolean secondary3dsPage = threeDs && threeDsAltButtons;
        inventory.setVisibility(primary3dsPage ? VISIBLE : GONE);
        craft.setVisibility((legacy || primary3dsPage) ? VISIBLE : GONE);
        keyboard.setVisibility((legacy || secondary3dsPage) ? VISIBLE : GONE);
        pause.setVisibility((legacy || primary3dsPage) ? VISIBLE : GONE);
        launcherMenu.setVisibility(secondary3dsPage || !threeDs ? VISIBLE : GONE);
        fpsToggle.setVisibility(secondary3dsPage ? VISIBLE : GONE);
        arrow.setVisibility(threeDs ? VISIBLE : GONE);
        emptyOne.setVisibility(legacy ? VISIBLE : GONE);
        mapButton.setVisibility(legacy ? VISIBLE : GONE);
        // FPS remains controlled by the same persistent user preference, including when
        // it is toggled from the 3DS page-two FPS button.
        counter.setVisibility(fpsEnabled ? VISIBLE : GONE);

        // Keep all launcher-owned HUD bitmaps pixel-crisp. These are Minecraft-style
        // pixel-art controls, so bilinear filtering makes them visibly soft when scaled.
        // The view bounds are already rounded to whole physical pixels by setMenuItemLayout().
        inventory.setSmoothScaling(false);
        craft.setSmoothScaling(false);
        pause.setSmoothScaling(false);
        keyboard.setSmoothScaling(false);
        launcherMenu.setSmoothScaling(false);
        fpsToggle.setSmoothScaling(false);
        arrow.setSmoothScaling(false);
        if (!threeDsPageAnimating) {
            // The themed arrow drawable includes its own beveled button chrome, so keep
            // the view upright on both pages. Rotating the whole bitmap would also rotate
            // the light/dark bevel and make the button look backwards.
            arrow.setRotation(0f);
            arrow.setAlpha(1f);
        }

        if (threeDs) {
            // Author against the exact 640x480 inner canvas from the user's reference.
            // One uniform scale fixes the stretched vertical spacing seen on Thor's native
            // lower panel and keeps the rail/map relationship identical to the screenshot.
            final float referenceWidth = 640f;
            final float referenceHeight = 480f;
            float scale = Math.min(viewport.width() / referenceWidth, viewport.height() / referenceHeight);
            float designWidth = referenceWidth * scale;
            float designLeft = viewport.centerX() - designWidth * 0.5f;
            float designTop = viewport.top;

            // Reference coordinates inside the 640px-wide content area:
            //   map       x64 y156 288x288 (drawn by DualScreenControlsView)
            //   inventory x416 y162 192x96
            //   craft     x416 y266 192x96
            //   pause     x416 y370 192x96
            // Give the page-switch arrow real breathing room on the right. The first
            // pass used x=416, which put the arrow almost against both the craft button
            // and the viewport edge. Shift the full three-button rail left without
            // changing button size or vertical spacing.
            // Every rail bitmap is exactly 96x48 in drawable-nodpi. Do not stretch
            // those source pixels to an arbitrary physical size. On the Thor the old
            // 640x480 layout scale is 1.9375, so 192x96 became 372x186: a 3.875x
            // source scale. Nearest-neighbour still looks uneven at that fractional
            // ratio because some source pixels become 3 physical pixels wide while
            // others become 4. Snap the bitmap itself to the nearest whole-number
            // source-pixel multiple (4x on Thor), while preserving the intended
            // reference-space center for the rail.
            float idealButtonWidth = 192f * scale;
            float idealButtonHeight = 96f * scale;
            int buttonPixelScale = Math.max(1, Math.round(Math.min(
                    idealButtonWidth / 96f,
                    idealButtonHeight / 48f
            )));
            float buttonWidth = 96f * buttonPixelScale;
            float buttonHeight = 48f * buttonPixelScale;
            float buttonCenterX = designLeft + (392f + 96f) * scale;
            float buttonLeft = buttonCenterX - buttonWidth * 0.5f;
            float firstCenterY = designTop + (162f + 48f) * scale;
            float firstTop = firstCenterY - buttonHeight * 0.5f;
            float rowStep = 104f * scale;

            // Both button pages occupy exactly the same three slots. Page one is
            // Inventory / Craft / Pause. Page two is Keyboard / Settings / Show-Hide FPS.
            setMenuItemLayout(inventory, buttonLeft, firstTop, buttonWidth, buttonHeight);
            setMenuItemLayout(craft, buttonLeft, firstTop + rowStep, buttonWidth, buttonHeight);
            setMenuItemLayout(pause, buttonLeft, firstTop + rowStep * 2f, buttonWidth, buttonHeight);
            setMenuItemLayout(keyboard, buttonLeft, firstTop, buttonWidth, buttonHeight);
            setMenuItemLayout(launcherMenu, buttonLeft, firstTop + rowStep, buttonWidth, buttonHeight);
            setMenuItemLayout(fpsToggle, buttonLeft, firstTop + rowStep * 2f, buttonWidth, buttonHeight);

            // The page switch is now a small beveled 3DS-style button rather than a
            // bare triangle. It is vertically centered beside the craft/settings slot
            // with a visible gap from both the rail and the right edge.
            setMenuItemLayout(arrow,
                    designLeft + 596f * scale,
                    designTop + 286f * scale,
                    36f * scale,
                    56f * scale);

            if (fpsEnabled) {
                // ControlsView now keeps the map slightly smaller and lower: x72/y174,
                // 272x272. Keep an explicit gap beneath it and make the label itself
                // wider; MinecraftFpsView uses a larger 3DS-only text scale below.
                float mapLeft = designLeft + 72f * scale;
                float mapWidth = 272f * scale;
                float fpsWidth = 170f * scale;
                float fpsHeight = 26f * scale;
                float fpsTop = designTop + 452f * scale;
                float fpsLeft = mapLeft + (mapWidth - fpsWidth) * 0.5f;
                setMenuItemLayout(counter, fpsLeft, fpsTop, fpsWidth, fpsHeight);
            }
            return;
        }

        if (!legacy) {
            int keyboardWidth = dp(getContext(), 126);
            int keyboardHeight = dp(getContext(), 42);
            LayoutParams params = new LayoutParams(keyboardWidth, keyboardHeight);
            params.gravity = Gravity.START | Gravity.TOP;
            params.setMargins(
                    Math.round(viewport.left + pad),
                    Math.round(viewport.top + pad),
                    0,
                    0
            );
            modernKeyboard.setLayoutParams(params);

            int menuWidth = dp(getContext(), 96);
            int menuHeight = dp(getContext(), 48);
            int modernFpsWidth = dp(getContext(), 104);
            LayoutParams menuParams = new LayoutParams(menuWidth, menuHeight);
            menuParams.gravity = Gravity.START | Gravity.TOP;
            // Center the 96dp settings button over the 104dp FPS readout instead of
            // right-aligning both widths, which left their centers 4dp apart.
            menuParams.setMargins(
                    Math.round(viewport.right - pad - modernFpsWidth + (modernFpsWidth - menuWidth) * 0.5f),
                    Math.round(viewport.top + pad),
                    0,
                    0
            );
            launcherMenu.setLayoutParams(menuParams);

            if (fpsEnabled) {
                int fpsWidth = modernFpsWidth;
                int fpsHeight = dp(getContext(), 28);
                LayoutParams fpsParams = new LayoutParams(fpsWidth, fpsHeight);
                fpsParams.gravity = Gravity.START | Gravity.TOP;
                fpsParams.setMargins(
                        Math.round(viewport.right - pad - fpsWidth),
                        Math.round(viewport.top + pad + menuHeight + dp(getContext(), 8)),
                        0,
                        0
                );
                counter.setLayoutParams(fpsParams);
            }
            return;
        }

        // Legacy lower-screen controls: exactly two vertical stacks, three buttons each.
        // Left column: Craft / Keyboard / Pause. Right column: Empty / Empty / Settings.
        // This matches the requested 3-left + 3-right layout and keeps both columns clear of the map.
        float hotbarHeight = Math.max(44f * density, Math.min(dp(getContext(), 88), viewport.height() * 0.108f));
        float coordStripGap = Math.max(dp(getContext(), 6), hotbarHeight * 0.06f);
        float coordStripHeight = Math.max(dp(getContext(), 34), hotbarHeight * 1.55f);
        float lowerAreaTop = viewport.top + pad + Math.max(dp(getContext(), 44), Math.min(viewport.height() * 0.078f, dp(getContext(), 82)))
                + hotbarHeight + coordStripGap + coordStripHeight + Math.max(dp(getContext(), 10), viewport.height() * 0.018f);
        float lowerAreaBottom = viewport.bottom - pad;
        float lowerAreaHeight = Math.max(1f, lowerAreaBottom - lowerAreaTop);

        float mapSide = Math.min(viewport.width() * 0.44f, lowerAreaHeight * 0.86f);
        mapSide = Math.max(Math.min(viewport.width(), lowerAreaHeight) * 0.34f, mapSide);
        float mapLeft = viewport.left + pad + Math.max(0f, viewport.width() * 0.010f);
        float mapRight = mapLeft + mapSide;

        float outerRightInset = Math.max(pad, viewport.width() * 0.035f);
        float mapToButtonsGap = Math.max(dp(getContext(), 14), viewport.width() * 0.018f);
        float columnGap = Math.max(dp(getContext(), 12), viewport.width() * 0.014f);
        float availableWidth = Math.max(dp(getContext(), 190),
                viewport.right - outerRightInset - mapRight - mapToButtonsGap);
        float maxColumnWidth = Math.max(dp(getContext(), 88), (availableWidth - columnGap) * 0.5f);
        float desiredButtonWidth = Math.max(dp(getContext(), 112), Math.min(dp(getContext(), 164), viewport.width() * 0.115f));
        float buttonWidth = Math.min(desiredButtonWidth, maxColumnWidth);
        float buttonHeight = buttonWidth * 0.5f;

        float rowGap = Math.max(dp(getContext(), 9), buttonHeight * 0.16f);
        float fpsHeight = fpsEnabled ? Math.max(dp(getContext(), 20), buttonHeight * 0.42f) : 0f;
        float fpsGap = fpsEnabled ? Math.max(dp(getContext(), 8), rowGap * 0.75f) : 0f;
        float stackHeight = buttonHeight * 3f + rowGap * 2f;
        float totalHeight = stackHeight + fpsGap + fpsHeight;
        if (totalHeight > lowerAreaHeight) {
            float availableForButtons = Math.max(dp(getContext(), 66), lowerAreaHeight - rowGap * 2f - fpsGap - fpsHeight);
            buttonHeight = availableForButtons / 3f;
            buttonWidth = buttonHeight * 2f;
            stackHeight = buttonHeight * 3f + rowGap * 2f;
            totalHeight = stackHeight + fpsGap + fpsHeight;
        }

        float columnsWidth = buttonWidth * 2f + columnGap;
        float columnsLeft = mapRight + mapToButtonsGap
                + Math.max(0f, (availableWidth - columnsWidth) * 0.5f);
        float leftColumn = columnsLeft;
        float rightColumn = columnsLeft + buttonWidth + columnGap;
        float top = lowerAreaTop + Math.max(0f, (lowerAreaHeight - totalHeight) * 0.5f);

        setMenuItemLayout(craft, leftColumn, top, buttonWidth, buttonHeight);
        setMenuItemLayout(keyboard, leftColumn, top + buttonHeight + rowGap, buttonWidth, buttonHeight);
        setMenuItemLayout(pause, leftColumn, top + (buttonHeight + rowGap) * 2f, buttonWidth, buttonHeight);

        setMenuItemLayout(emptyOne, rightColumn, top, buttonWidth, buttonHeight);
        setMenuItemLayout(mapButton, rightColumn, top + buttonHeight + rowGap, buttonWidth, buttonHeight);
        float settingsTop = top + (buttonHeight + rowGap) * 2f;
        setMenuItemLayout(launcherMenu, rightColumn, settingsTop, buttonWidth, buttonHeight);

        if (fpsEnabled) {
            float fpsWidth = Math.max(buttonWidth, dp(getContext(), 104));
            setMenuItemLayout(counter, rightColumn + buttonWidth - fpsWidth,
                    settingsTop + buttonHeight + fpsGap, fpsWidth, fpsHeight);
        }
    }

    private void toggleThreeDsButtonPage() {
        if (!LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS.equals(
                LauncherPreferences.getDualScreenHudLayout(getContext())) || threeDsPageAnimating) {
            return;
        }

        LegacyImageButtonView inventory = inventoryButton;
        LegacyImageButtonView craft = craftButton;
        LegacyImageButtonView pause = pauseButton;
        LegacyImageButtonView keyboard = legacyKeyboardButton;
        LegacyImageButtonView settings = launcherMenuRailButton;
        LegacyImageButtonView fpsToggle = threeDsFpsToggleButton;
        LegacyImageButtonView arrow = threeDsArrowButton;
        if (inventory == null || craft == null || pause == null || keyboard == null
                || settings == null || fpsToggle == null || arrow == null) return;

        final View[] outgoing = threeDsAltButtons
                ? new View[]{keyboard, settings, fpsToggle}
                : new View[]{inventory, craft, pause};
        final View[] incoming = threeDsAltButtons
                ? new View[]{inventory, craft, pause}
                : new View[]{keyboard, settings, fpsToggle};
        final boolean showingAlt = !threeDsAltButtons;
        final float travel = dp(getContext(), 12);
        final float incomingOffset = showingAlt ? travel : -travel;
        final float outgoingOffset = -incomingOffset;

        threeDsAltButtons = showingAlt;
        threeDsPageAnimating = true;

        for (View view : incoming) {
            view.animate().cancel();
            view.setVisibility(VISIBLE);
            view.setAlpha(0f);
            view.setTranslationX(incomingOffset);
            view.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(160L)
                    .start();
        }
        for (View view : outgoing) {
            view.animate().cancel();
            view.setVisibility(VISIBLE);
            view.setAlpha(1f);
            view.setTranslationX(0f);
            view.animate()
                    .alpha(0f)
                    .translationX(outgoingOffset)
                    .setDuration(110L)
                    .withEndAction(() -> {
                        view.setVisibility(GONE);
                        view.setAlpha(1f);
                        view.setTranslationX(0f);
                    })
                    .start();
        }

        arrow.animate().cancel();
        // Keep the chrome upright and use a short press/fade pulse while the three
        // rail buttons slide between pages. The same arrow toggles back on the next tap.
        arrow.animate()
                .alpha(0.72f)
                .setDuration(80L)
                .withEndAction(() -> arrow.animate()
                        .alpha(1f)
                        .setDuration(80L)
                        .withEndAction(() -> {
                            threeDsPageAnimating = false;
                            updateLowerScreenMenuLayout();
                        })
                        .start())
                .start();
    }

    private void setMenuItemLayout(@NonNull View view, float left, float top, float width, float height) {
        LayoutParams params = new LayoutParams(
                Math.max(1, Math.round(width)),
                Math.max(1, Math.round(height))
        );
        params.gravity = Gravity.START | Gravity.TOP;
        params.setMargins(Math.round(left), Math.round(top), 0, 0);
        view.setLayoutParams(params);
    }

    @NonNull
    private static RectF calculateLayoutViewport(int width, int height, boolean fourThree) {
        // DualScreenBottomViewportLayout now owns the real lower-screen image bounds.
        // Do not apply a second 16:9/4:3 crop here or compact mode would be cropped twice.
        return new RectF(0f, 0f, Math.max(0, width), Math.max(0, height));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        uiHandler.removeCallbacks(fpsTicker);
        uiHandler.post(fpsTicker);
        TouchKeyboardHelper.registerDualScreenImeHost(this);
        liveBridge.setListener(this);
        liveBridge.start();
        // onDetachedFromWindow() intentionally clears the view's bridge reference. Rebind it
        // every attach so returning from launcher home cannot leave the hotbar on fallback paths.
        controlsView.setLiveIconBridge(liveBridge);
        controlsView.setLegacyTransportActive(true);
        floatingOverlayController.attach();
        floatingOverlayController.resume();
        floatingOverlayController.bringToFront();
        updateLowerScreenMenuLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        uiHandler.removeCallbacks(fpsTicker);
        hideTextKeyboard();
        TouchKeyboardHelper.unregisterDualScreenImeHost(this);
        controlsView.setLegacyTransportActive(false);
        floatingOverlayController.pause();
        floatingOverlayController.detach();
        liveBridge.setListener(null);
        controlsView.setLiveIconBridge(null);
        liveBridge.close();
        LegacyMinecraftButtonView button = textKeyboardButton;
        if (button != null) button.release();
        if (inventoryButton != null) inventoryButton.release();
        if (craftButton != null) craftButton.release();
        if (legacyKeyboardButton != null) legacyKeyboardButton.release();
        if (pauseButton != null) pauseButton.release();
        if (launcherMenuRailButton != null) launcherMenuRailButton.release();
        if (emptyRailButtonOne != null) emptyRailButtonOne.release();
        if (mapRailButton != null) mapRailButton.release();
        if (threeDsFpsToggleButton != null) threeDsFpsToggleButton.release();
        if (threeDsArrowButton != null) threeDsArrowButton.release();
        super.onDetachedFromWindow();
    }

    @Override
    public void onBridgeChanged() {
        if (!attached) return;
        controlsView.onLiveIconBridgeChanged();
    }

    void previewControllerHotbarDelta(int delta) {
        controlsView.previewControllerHotbarDelta(delta);
    }

    private void refreshFpsCounter() {
        MinecraftFpsView counter = fpsView;
        if (counter == null || !LauncherPreferences.isDualScreenFpsEnabled(getContext())) return;

        int fps = DroidBridgeSdlFpsBridge.readCurrentFps(getContext());
        if (fps <= 0) {
            try {
                fps = Math.max(0, CallbackBridge.getCurrentFps());
            } catch (Throwable ignored) {
                fps = 0;
            }
        }
        counter.setLabel(fps > 0 ? fps + " FPS" : "FPS");
    }

    private static void sendMinecraftKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            int mods = CallbackBridge.getCurrentMods();
            CallbackBridge.sendKeyPress(keyCode, mods, true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, mods, false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable ignored) {
        }
    }

    private void showTextKeyboard() {
        if (textKeyboardView != null) return;

        // This intentionally bypasses Android's IME. In grabbed gameplay the first tap opens
        // Minecraft chat, then the bottom-screen keyboard sends GLFW text/key events directly.
        DualScreenTextKeyboardView.openMinecraftChatIfNeeded();
        postDelayed(() -> {
            if (!attached || textKeyboardView != null) return;
            DualScreenTextKeyboardView keyboard = new DualScreenTextKeyboardView(
                    getContext(),
                    controlsView,
                    new DualScreenTextKeyboardView.Listener() {
                        @Override
                        public void onCloseRequested() {
                            hideTextKeyboard();
                        }

                        @Override
                        public void onSubmitted() {
                            hideTextKeyboard();
                        }
                    }
            );
            textKeyboardView = keyboard;
            addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            keyboard.bringToFront();
        }, 80L);
    }

    private void hideTextKeyboard() {
        DualScreenTextKeyboardView keyboard = textKeyboardView;
        textKeyboardView = null;
        if (keyboard != null) {
            try {
                removeView(keyboard);
            } catch (Throwable ignored) {
            }
        }
    }

    void restoreGameHud() {
        if (liveBridge.isProtocolReady()) liveBridge.restoreMirroredGameHud();
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Exact Legacy button chrome supplied by the user. Text is rendered by
     * DualScreenControlsView from Minecraft's ascii.png, so "Keyboard" and the custom
     * keyboard keys use the same bitmap font as the Minecraft HUD.
     */
    private static final class LegacyMinecraftButtonView extends View {
        @NonNull private final DualScreenControlsView fontRenderer;
        @NonNull private final String label;
        @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @NonNull private final RectF target = new RectF();
        @Nullable private Bitmap chrome;
        private boolean legacyMode;

        LegacyMinecraftButtonView(@NonNull Context context,
                                  @NonNull DualScreenControlsView fontRenderer,
                                  @NonNull String label) {
            super(context);
            this.fontRenderer = fontRenderer;
            this.label = label;
            setClickable(true);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setWillNotDraw(false);
            loadChrome();
        }

        void setLegacyMode(boolean legacyMode) {
            if (this.legacyMode == legacyMode) return;
            this.legacyMode = legacyMode;
            invalidate();
        }

        private void loadChrome() {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            chrome = BitmapFactory.decodeResource(
                    getResources(),
                    R.drawable.droidbridge_legacy_button,
                    options
            );
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (chrome == null || chrome.isRecycled()) loadChrome();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            target.set(0f, 0f, getWidth(), getHeight());
            int alpha = isPressed() ? 153 : 255;
            int save = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), alpha);

            Bitmap bitmap = chrome;
            if (bitmap != null && !bitmap.isRecycled()) {
                paint.setFilterBitmap(false);
                paint.setDither(false);
                paint.setAlpha(255);
                canvas.drawBitmap(bitmap, null, target, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(142, 142, 142));
                canvas.drawRect(target, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 2f));
                paint.setColor(Color.rgb(32, 32, 32));
                canvas.drawRect(target, paint);
            }

            RectF labelBounds = new RectF(
                    getWidth() * 0.06f,
                    getHeight() * 0.08f,
                    getWidth() * 0.94f,
                    getHeight() * 0.92f
            );
            fontRenderer.drawMinecraftAsciiOverlayLabel(canvas, label, labelBounds, Color.WHITE);
            canvas.restoreToCount(save);
        }

        void release() {
            Bitmap bitmap = chrome;
            chrome = null;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Exact user-supplied Legacy rail button art; no text or Android font is drawn over it. */
    private static final class LegacyImageButtonView extends View {
        private final int drawableResId;
        @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @NonNull private final RectF target = new RectF();
        @Nullable private Bitmap bitmap;
        private boolean smoothScaling;

        LegacyImageButtonView(@NonNull Context context, int drawableResId) {
            super(context);
            this.drawableResId = drawableResId;
            setClickable(true);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setWillNotDraw(false);
            loadBitmap();
        }

        private void loadBitmap() {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            bitmap = BitmapFactory.decodeResource(getResources(), drawableResId, options);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (bitmap == null || bitmap.isRecycled()) loadBitmap();
        }

        void setSmoothScaling(boolean smoothScaling) {
            if (this.smoothScaling == smoothScaling) return;
            this.smoothScaling = smoothScaling;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            target.set(0f, 0f, getWidth(), getHeight());
            int alpha = isPressed() ? 153 : 255;
            int save = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), alpha);
            Bitmap image = bitmap;
            if (image != null && !image.isRecycled()) {
                // Pixel-art controls must use nearest-neighbour sampling. Android's
                // bitmap filtering/AA blends adjacent texels and is what caused the
                // blurred keyboard/settings/FPS buttons on the 3DS layout.
                paint.setFilterBitmap(false);
                paint.setDither(false);
                paint.setAntiAlias(false);
                paint.setAlpha(255);
                canvas.drawBitmap(image, null, target, paint);
            }
            canvas.restoreToCount(save);
        }

        void release() {
            Bitmap image = bitmap;
            bitmap = null;
            if (image != null && !image.isRecycled()) image.recycle();
        }
    }

    /** Lower-screen FPS badge rendered with the same Minecraft ascii.png helper as HUD text. */
    private static final class MinecraftFpsView extends View {
        @NonNull private final DualScreenControlsView fontRenderer;
        @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @NonNull private final RectF bounds = new RectF();
        @NonNull private String label = "FPS";

        MinecraftFpsView(@NonNull Context context, @NonNull DualScreenControlsView fontRenderer) {
            super(context);
            this.fontRenderer = fontRenderer;
            setClickable(false);
            setFocusable(false);
            setWillNotDraw(false);
        }

        void setLabel(@NonNull String label) {
            if (this.label.equals(label)) return;
            this.label = label;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            bounds.set(0f, 0f, getWidth(), getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(188, 38, 38, 38));
            canvas.drawRect(bounds, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
            paint.setColor(Color.argb(210, 170, 170, 170));
            canvas.drawRect(bounds, paint);

            RectF textBounds = new RectF(
                    bounds.left + getWidth() * 0.06f,
                    bounds.top + getHeight() * 0.08f,
                    bounds.right - getWidth() * 0.06f,
                    bounds.bottom - getHeight() * 0.08f
            );
            boolean threeDs = LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS.equals(
                    LauncherPreferences.getDualScreenHudLayout(getContext()));
            if (threeDs) {
                fontRenderer.drawMinecraftAsciiOverlayLabelLarge(canvas, label, textBounds, Color.WHITE);
            } else {
                fontRenderer.drawMinecraftAsciiOverlayLabel(canvas, label, textBounds, Color.WHITE);
            }
        }
    }

}
