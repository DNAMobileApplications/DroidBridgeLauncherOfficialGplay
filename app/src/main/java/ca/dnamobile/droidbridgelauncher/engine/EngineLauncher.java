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
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
 * Every launch path calls {@link #interceptLaunch} before it starts GameActivity. Instances
 * the Horizon engine cannot run (any version but 26.3, any mod loader, or a device or build
 * without the engine) return false at once and launch exactly as before.
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
     * @param javaLaunch the caller's normal Java-engine launch, run when the player picks Java
     * @return true when this call took over (it started Horizon or is asking the player);
     *         false when the caller should launch with the Java engine itself, right away
     */
    public static boolean interceptLaunch(
            @NonNull Activity activity,
            @NonNull Target target,
            @Nullable String quickPlayWorld,
            @Nullable String quickPlayServer,
            @NonNull Runnable javaLaunch
    ) {
        if (!offersEngineChoice(activity, target)) return false;
        GameEngine saved = EnginePreferences.get(activity, target.instanceKey);
        if (saved == GameEngine.JAVA) return false;
        if (saved == GameEngine.HORIZON) {
            startHorizon(activity, target, quickPlayWorld, quickPlayServer);
            return true;
        }
        showChooser(activity, target, quickPlayWorld, quickPlayServer, javaLaunch);
        return true;
    }

    /** The engine row of an instance's settings menu. */
    @NonNull
    public static String engineMenuLabel(@NonNull Activity activity, @NonNull Target target) {
        GameEngine saved = EnginePreferences.get(activity, target.instanceKey);
        String current = saved == GameEngine.HORIZON ? HORIZON_LABEL
                : saved == GameEngine.JAVA ? JAVA_LABEL : "Ask on Play";
        return "Game Engine: " + current;
    }

    /** Lets the player change the saved engine of an instance, or open Horizon's settings. */
    public static void showEngineSettings(@NonNull Activity activity, @NonNull Target target) {
        if (!offersEngineChoice(activity, target)) {
            String problem = HorizonEngine.supportsInstance(target.minecraftVersionId, target.loader)
                    ? HorizonEngine.deviceProblem(activity)
                    : "The Horizon engine runs vanilla " + HorizonEngine.MINECRAFT_VERSION + " instances only.";
            Toast.makeText(activity, problem == null ? "Horizon is not available." : problem,
                    Toast.LENGTH_LONG).show();
            return;
        }
        GameEngine saved = EnginePreferences.get(activity, target.instanceKey);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);

        RadioGroup engines = new RadioGroup(activity);
        RadioButton ask = new RadioButton(activity);
        ask.setId(View.generateViewId());
        ask.setText("Ask every time I press Play");
        RadioButton java = new RadioButton(activity);
        java.setId(View.generateViewId());
        java.setText(JAVA_LABEL + " — every feature, Fabric/Forge mods");
        RadioButton horizon = new RadioButton(activity);
        horizon.setId(View.generateViewId());
        horizon.setText(HORIZON_LABEL + " — faster loading, less memory, vanilla only");
        engines.addView(ask);
        engines.addView(java);
        engines.addView(horizon);
        engines.check(saved == GameEngine.JAVA ? java.getId()
                : saved == GameEngine.HORIZON ? horizon.getId() : ask.getId());
        content.addView(engines);

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
                .setTitle("Game engine for " + target.displayName)
                .setView(scroll)
                .setPositiveButton("Save", (dialog, which) -> {
                    int checked = engines.getCheckedRadioButtonId();
                    GameEngine engine = checked == java.getId() ? GameEngine.JAVA
                            : checked == horizon.getId() ? GameEngine.HORIZON : null;
                    EnginePreferences.set(activity, target.instanceKey, engine);
                    EnginePreferences.setUseDroidBridgeControlsForHorizon(activity, controls.isChecked());
                    LauncherLogManager.append("Engine choice for " + target.instanceKey + ": "
                            + (engine == null ? "ask" : engine.id)
                            + " droidbridgeControls=" + controls.isChecked());
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

    private static void showChooser(
            @NonNull Activity activity,
            @NonNull Target target,
            @Nullable String quickPlayWorld,
            @Nullable String quickPlayServer,
            @NonNull Runnable javaLaunch
    ) {
        // An AlertDialog shows either a message or a list, so the checkbox is its own view.
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, 0);
        TextView message = new TextView(activity);
        message.setText(target.displayName + " is vanilla " + HorizonEngine.MINECRAFT_VERSION
                + ", so it can run on either engine.\n\n"
                + HORIZON_LABEL + " (experimental): Minecraft rewritten in Rust. Loads worlds "
                + "faster and uses less memory. Vanilla only: Fabric, Forge and other Java mods "
                + "do not run, and some features may be missing or behave differently.\n\n"
                + JAVA_LABEL + ": Mojang's game on DroidBridge's Java runtime, with every "
                + "DroidBridge feature.\n\nBoth engines use this instance's worlds. You can change "
                + "the choice later in the instance's settings.");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        content.addView(message);
        CheckBox remember = new CheckBox(activity);
        remember.setText("Remember for this instance");
        content.addView(remember);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Choose a game engine")
                .setView(scroll)
                .setPositiveButton(HORIZON_LABEL, (dialog, which) -> {
                    if (remember.isChecked()) {
                        EnginePreferences.set(activity, target.instanceKey, GameEngine.HORIZON);
                    }
                    startHorizon(activity, target, quickPlayWorld, quickPlayServer);
                })
                .setNegativeButton(JAVA_LABEL, (dialog, which) -> {
                    if (remember.isChecked()) {
                        EnginePreferences.set(activity, target.instanceKey, GameEngine.JAVA);
                    }
                    javaLaunch.run();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
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
