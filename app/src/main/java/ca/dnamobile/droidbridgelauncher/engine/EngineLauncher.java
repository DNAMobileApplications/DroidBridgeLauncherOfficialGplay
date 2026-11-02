/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine;

import android.app.Activity;
import android.content.Intent;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonEngine;
import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonGraphics;
import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonLaunchActivity;
import ca.dnamobile.droidbridgelauncher.engine.horizon.HorizonSettingsDialog;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Picks the engine for a Play press.
 *
 * Horizon is opt-in per instance. Every launch path calls {@link #interceptLaunch} before it
 * starts GameActivity. Java remains the default unless the player explicitly selects Horizon
 * in that instance's Per Instance Settings. No launch-time engine chooser is shown.
 */
public final class EngineLauncher {
    private static final String HORIZON_LABEL = "Horizon (Rust)";
    private static final String JAVA_LABEL = "Java";

    private EngineLauncher() {
    }

    /** The instance a Play press is for. */
    public static final class Target {
        @NonNull final String instanceKey;
        @NonNull final String displayName;
        @Nullable final String minecraftVersionId;
        @Nullable final String loader;
        @Nullable final File gameDirectory;

        public Target(
                @NonNull String instanceKey,
                @NonNull String displayName,
                @Nullable String minecraftVersionId,
                @Nullable String loader,
                @Nullable File gameDirectory
        ) {
            this.instanceKey = instanceKey;
            this.displayName = displayName;
            this.minecraftVersionId = minecraftVersionId;
            this.loader = loader;
            this.gameDirectory = gameDirectory;
        }
    }

    /** True when the engine choice applies to this instance on this device. */
    public static boolean offersEngineChoice(@NonNull Activity activity, @NonNull Target target) {
        return HorizonEngine.isAvailableFor(activity, target.minecraftVersionId, target.loader)
                && target.gameDirectory != null;
    }

    /**
     * Handles a Play press when the Horizon engine is involved.
     *
     * @param javaLaunch retained for call-site compatibility; Java remains the caller's normal path
     * @return true when this call started Horizon; false when the caller should launch Java
     */
    public static boolean interceptLaunch(
            @NonNull Activity activity,
            @NonNull Target target,
            @Nullable String quickPlayWorld,
            @Nullable String quickPlayServer,
            @NonNull Runnable ignoredJavaLaunch
    ) {
        if (!offersEngineChoice(activity, target)) return false;
        GameEngine saved = EnginePreferences.get(activity, target.instanceKey);
        if (saved != GameEngine.HORIZON) {
            // Java is deliberately the default. A missing preference never prompts and never
            // opts the player into the experimental Rust engine.
            return false;
        }
        startHorizon(activity, target, quickPlayWorld, quickPlayServer);
        return true;
    }

    /** The engine row of an instance's settings menu. */
    @NonNull
    public static String engineMenuLabel(@NonNull Activity activity, @NonNull Target target) {
        GameEngine saved = EnginePreferences.get(activity, target.instanceKey);
        String current = saved == GameEngine.HORIZON ? HORIZON_LABEL : JAVA_LABEL;
        return "Game Engine: " + current;
    }

    /** Advanced Horizon controls/graphics for an instance that has Horizon available. */
    public static void showEngineSettings(@NonNull Activity activity, @NonNull Target target) {
        if (!offersEngineChoice(activity, target)) {
            String problem = HorizonEngine.supportsInstance(target.minecraftVersionId, target.loader)
                    ? HorizonEngine.deviceProblem(activity)
                    : "The Horizon engine runs vanilla " + HorizonEngine.MINECRAFT_VERSION + " instances only.";
            Toast.makeText(activity, problem == null ? "Horizon is not available." : problem,
                    Toast.LENGTH_LONG).show();
            return;
        }
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);

        TextView note = new TextView(activity);
        note.setText("The game engine itself is selected in this instance's Per Instance Settings. "
                + "These are advanced Horizon options used only when Horizon is enabled for a "
                + "vanilla Minecraft " + HorizonEngine.MINECRAFT_VERSION + " instance.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, 0, 0, pad / 2);
        content.addView(note);

        CheckBox controls = new CheckBox(activity);
        controls.setText("Use DroidBridge controls in Horizon (touch layout, virtual mouse, "
                + "controller mapping). Off: Horizon's built-in controls.");
        controls.setChecked(EnginePreferences.useDroidBridgeControlsForHorizon(activity));
        content.addView(controls);

        // Horizon graphics: rendering mode and Vulkan driver, for every Horizon launch.
        TextView rendererRow = settingRow(activity, pad);
        TextView driverRow = settingRow(activity, pad);
        Runnable refreshGraphics = () -> {
            String renderer = EnginePreferences.getHorizonRenderer(activity);
            rendererRow.setText("Horizon renderer: " + HorizonGraphics.rendererLabel(renderer));
            boolean opengl = EnginePreferences.HORIZON_RENDERER_OPENGL.equals(renderer);
            driverRow.setText("Vulkan driver: "
                    + HorizonGraphics.driverLabel(EnginePreferences.getHorizonVulkanDriver(activity))
                    + (opengl ? " (not used with OpenGL ES)" : ""));
        };
        rendererRow.setOnClickListener(view -> {
            List<HorizonGraphics.Choice> choices = HorizonGraphics.rendererChoices();
            chooseSetting(activity, "Horizon renderer", choices,
                    EnginePreferences.getHorizonRenderer(activity), id -> {
                        EnginePreferences.setHorizonRenderer(activity, id);
                        refreshGraphics.run();
                    });
        });
        driverRow.setOnClickListener(view -> {
            List<HorizonGraphics.Choice> choices = HorizonGraphics.driverChoices(activity);
            chooseSetting(activity, "Vulkan driver for Horizon", choices,
                    EnginePreferences.getHorizonVulkanDriver(activity), id -> {
                        EnginePreferences.setHorizonVulkanDriver(activity, id);
                        refreshGraphics.run();
                    });
        });
        refreshGraphics.run();
        content.addView(rendererRow);
        content.addView(driverRow);

        TextView licenses = new TextView(activity);
        licenses.setText("Horizon open-source licenses");
        licenses.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        licenses.setPadding(0, pad / 2, 0, pad / 2);
        TypedValue accent = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true)) {
            licenses.setTextColor(accent.data);
        }
        licenses.setOnClickListener(view -> showHorizonLicenses(activity));
        content.addView(licenses);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Horizon advanced settings")
                .setView(scroll)
                .setPositiveButton("Save", (dialog, which) -> {
                    EnginePreferences.setUseDroidBridgeControlsForHorizon(activity, controls.isChecked());
                    LauncherLogManager.append("Horizon options for " + target.instanceKey
                            + ": droidbridgeControls=" + controls.isChecked());
                })
                .setNeutralButton("Horizon Settings", (dialog, which) ->
                        HorizonSettingsDialog.show(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface Chosen {
        void onChosen(@NonNull String id);
    }

    @NonNull
    private static TextView settingRow(@NonNull Activity activity, int pad) {
        TextView row = new TextView(activity);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setPadding(0, pad / 2, 0, pad / 2);
        TypedValue accent = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true)) {
            row.setTextColor(accent.data);
        }
        return row;
    }

    private static void chooseSetting(
            @NonNull Activity activity,
            @NonNull String title,
            @NonNull List<HorizonGraphics.Choice> choices,
            @NonNull String current,
            @NonNull Chosen chosen
    ) {
        String[] labels = new String[choices.size()];
        int checked = 0;
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label;
            if (choices.get(i).id.equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    chosen.onChosen(choices.get(which).id);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Horizon's MIT license and its dependencies' notices, shipped in assets/horizon/. */
    private static void showHorizonLicenses(@NonNull Activity activity) {
        StringBuilder text = new StringBuilder();
        for (String name : new String[]{"horizon/LICENSE.txt", "horizon/THIRD_PARTY_NOTICES.md"}) {
            String body = readAsset(activity, name);
            if (body == null) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(body.trim());
        }
        if (text.length() == 0) {
            text.append("Horizon is MIT licensed: https://github.com/ludovic111/horizon/blob/main/LICENSE");
        }
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextIsSelectable(true);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setPadding(pad, pad / 2, pad, pad / 2);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Horizon licenses")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Nullable
    private static String readAsset(@NonNull Activity activity, @NonNull String name) {
        try (InputStream input = activity.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void startHorizon(
            @NonNull Activity activity,
            @NonNull Target target,
            @Nullable String quickPlayWorld,
            @Nullable String quickPlayServer
    ) {
        // DroidBridge always launches the embedded game directly. Horizon's standalone
        // launcher/settings UI is intentionally not a reachable launch mode here.
        LauncherPreferences.recordInstancePlayed(activity, target.instanceKey);
        Intent intent = new Intent(activity, HorizonLaunchActivity.class);
        intent.putExtra(HorizonLaunchActivity.EXTRA_INSTANCE_KEY, target.instanceKey);
        if (target.gameDirectory != null) {
            intent.putExtra(HorizonLaunchActivity.EXTRA_GAME_DIR, target.gameDirectory.getAbsolutePath());
        }
        if (quickPlayWorld != null && !quickPlayWorld.trim().isEmpty()) {
            intent.putExtra(HorizonLaunchActivity.EXTRA_QUICK_PLAY_WORLD, quickPlayWorld.trim());
        } else if (quickPlayServer != null && !quickPlayServer.trim().isEmpty()) {
            intent.putExtra(HorizonLaunchActivity.EXTRA_QUICK_PLAY_SERVER, quickPlayServer.trim());
        }
        activity.startActivity(intent);
    }
}
