/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.modmanager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;

/** Chooses the real Java Edition datapack target: saves/<world>/datapacks. */
public final class DatapackWorldTargetPicker {
    public interface Callback {
        void onTargetSelected(@NonNull File datapacksDirectory);
    }

    private DatapackWorldTargetPicker() {
    }

    public static void choose(
            @NonNull AppCompatActivity activity,
            @NonNull File gameDirectory,
            @NonNull Callback callback
    ) {
        ArrayList<File> worlds = findWorlds(gameDirectory);
        if (worlds.isEmpty()) {
            AlertDialog dialog = LauncherDialogStyle.builder(activity)
                    .setTitle("No world found")
                    .setMessage("Java Edition data packs belong to a specific world. Create or open a world in this instance first, then install the data pack again. DroidBridge will place it in saves/<world>/datapacks.")
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(activity, dialog));
            dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(activity));
            dialog.show();
            LauncherDialogStyle.styleDialogChrome(activity, dialog);
            FullscreenUtils.enableImmersive(activity);
            return;
        }

        if (worlds.size() == 1) {
            callback.onTargetSelected(new File(worlds.get(0), "datapacks"));
            return;
        }

        CharSequence[] names = new CharSequence[worlds.size()];
        for (int i = 0; i < worlds.size(); i++) {
            names[i] = worlds.get(i).getName();
        }

        AlertDialog dialog = LauncherDialogStyle.builder(activity)
                .setTitle("Choose world for data pack")
                .setItems(names, (unused, which) -> {
                    if (which >= 0 && which < worlds.size()) {
                        callback.onTargetSelected(new File(worlds.get(which), "datapacks"));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(activity, dialog));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(activity));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(activity, dialog);
        FullscreenUtils.enableImmersive(activity);
    }

    @NonNull
    private static ArrayList<File> findWorlds(@NonNull File gameDirectory) {
        ArrayList<File> out = new ArrayList<>();
        File saves = new File(gameDirectory, "saves");
        File[] children = saves.listFiles(file -> file.isDirectory() && new File(file, "level.dat").isFile());
        if (children == null) return out;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        out.addAll(Arrays.asList(children));
        return out;
    }
}
