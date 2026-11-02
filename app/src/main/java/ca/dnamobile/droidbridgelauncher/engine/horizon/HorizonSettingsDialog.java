/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, Horizon, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Build;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;

/**
 * DroidBridge-native editor for Horizon's Android launch preferences.
 *
 * Horizon still uses android-launcher.json internally while bootstrapping the embedded engine,
 * but DroidBridge never needs to show Horizon's own launcher UI. This dialog reads and writes the
 * same file, preserving unknown/new fields so an engine update can add settings without this
 * launcher deleting them.
 */
public final class HorizonSettingsDialog {
    private static final String TAG = "DroidBridgeHorizon";
    private static final String FILE_NAME = "android-launcher.json";

    private HorizonSettingsDialog() {
    }

    public static void show(@NonNull Activity activity) {
        Store store = new Store(activity);
        int pad = dp(activity, 20);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, pad / 2);

        TextView note = new TextView(activity);
        note.setText("These are Horizon's engine-side Android settings. DroidBridge stays the launcher; "
                + "changes apply to the next Horizon launch. Renderer/driver choices in DroidBridge's "
                + "Game Engine screen can override Horizon's own rendering setting. Input is always "
                + "owned by DroidBridge while Horizon is embedded.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, 0, 0, pad / 2);
        content.addView(note, matchWrap());

        TextView renderer = row(activity, pad);
        TextView renderScale = row(activity, pad);
        TextView workers = row(activity, pad);
        TextView cache = row(activity, pad);
        TextView orientation = row(activity, pad);
        TextView aspect = row(activity, pad);
        TextView refresh = row(activity, pad);

        Runnable refreshRows = () -> {
            renderer.setText("Rendering: " + rendererLabel(store.text("renderer", defaultRenderer())));
            renderScale.setText("Client resolution: " + clamp(store.integer("render_scale", 100), 25, 100) + "%");
            int workerLimit = clamp(store.integer("worker_limit", 0), 0, 64);
            workers.setText("Chunk worker limit: " + (workerLimit == 0 ? "No additional limit" : workerLimit));
            int cacheMiB = store.integer("cache_budget_mib", 0);
            cache.setText("Reusable RAM cache: " + (cacheMiB == 0 ? "Engine default (80 MiB)" : cacheMiB + " MiB"));
            orientation.setText("Orientation: " + orientationLabel(store.text("orientation", "landscape")));
            aspect.setText("Aspect ratio: " + aspectLabel(store.text("aspect", "native")));
            int hz = store.integer("display_hz", 0);
            refresh.setText("Display refresh rate: " + (hz == 0 ? "System default" : hz + " Hz"));
        };

        renderer.setOnClickListener(v -> choose(activity, "Horizon rendering", new String[]{
                        "Vulkan / compatibility", "Vulkan / GPU batching", "OpenGL ES / experimental"
                }, new String[]{"vulkan-compatibility", "vulkan", "open-gl"},
                store.text("renderer", defaultRenderer()), value -> {
                    store.put("renderer", value);
                    refreshRows.run();
                }));

        renderScale.setOnClickListener(v -> {
            String[] labels = {"100%", "85%", "75%", "67%", "50%", "25%", "Custom percentage..."};
            int[] values = {100, 85, 75, 67, 50, 25};
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("Client resolution")
                    .setItems(labels, (dialog, which) -> {
                        if (which < values.length) {
                            store.put("render_scale", values[which]);
                            refreshRows.run();
                        } else {
                            numberEntry(activity, "Custom client resolution",
                                    "Enter a whole percentage from 25 to 100.",
                                    clamp(store.integer("render_scale", 100), 25, 100), 25, 100,
                                    value -> {
                                        store.put("render_scale", value);
                                        refreshRows.run();
                                    });
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        workers.setOnClickListener(v -> numberEntry(activity, "Chunk worker limit",
                "0 means no additional limit. Horizon accepts 0 to 64 workers.",
                clamp(store.integer("worker_limit", 0), 0, 64), 0, 64, value -> {
                    store.put("worker_limit", value);
                    refreshRows.run();
                }));

        cache.setOnClickListener(v -> chooseInt(activity, "Reusable RAM cache",
                new String[]{"Engine default (80 MiB)", "16 MiB", "32 MiB", "64 MiB", "128 MiB", "256 MiB"},
                new int[]{0, 16, 32, 64, 128, 256}, store.integer("cache_budget_mib", 0), value -> {
                    store.put("cache_budget_mib", value);
                    refreshRows.run();
                }));

        orientation.setOnClickListener(v -> choose(activity, "Screen orientation", new String[]{
                        "Landscape (sensor)", "Portrait (sensor)", "Landscape (reversed)",
                        "Portrait (reversed)", "Automatic rotation"
                }, new String[]{"landscape", "portrait", "reverse-landscape", "reverse-portrait", "auto"},
                store.text("orientation", "landscape"), value -> {
                    store.put("orientation", value);
                    refreshRows.run();
                }));

        aspect.setOnClickListener(v -> choose(activity, "Aspect ratio",
                new String[]{"Native", "16:9", "4:3", "1:1"},
                new String[]{"native", "wide", "classic", "square"},
                store.text("aspect", "native"), value -> {
                    store.put("aspect", value);
                    refreshRows.run();
                }));

        refresh.setOnClickListener(v -> chooseInt(activity, "Display refresh rate",
                new String[]{"System default", "30 Hz", "60 Hz", "90 Hz", "120 Hz", "144 Hz", "165 Hz"},
                new int[]{0, 30, 60, 90, 120, 144, 165}, store.integer("display_hz", 0), value -> {
                    store.put("display_hz", value);
                    refreshRows.run();
                }));

        content.addView(renderer, matchWrap());
        content.addView(renderScale, matchWrap());
        content.addView(workers, matchWrap());
        content.addView(cache, matchWrap());
        content.addView(orientation, matchWrap());

        CheckBox immersive = toggle(activity, "Immersive fullscreen", store.bool("immersive", true),
                checked -> store.put("immersive", checked));
        CheckBox cutout = toggle(activity, "Avoid camera cutout", store.bool("avoid_cutout", false),
                checked -> store.put("avoid_cutout", checked));
        content.addView(immersive, matchWrap());
        content.addView(cutout, matchWrap());
        content.addView(aspect, matchWrap());
        content.addView(refresh, matchWrap());

        CheckBox hints = toggle(activity, "Performance hints (ADPF)", store.bool("performance_hints", false),
                checked -> store.put("performance_hints", checked));
        CheckBox gpu = toggle(activity, "GPU profiling", store.bool("gpu_profile", false),
                checked -> store.put("gpu_profile", checked));
        CheckBox recording = toggle(activity, "Performance recording", store.bool("record_performance", false),
                checked -> store.put("record_performance", checked));
        content.addView(hints, matchWrap());
        content.addView(gpu, matchWrap());
        content.addView(recording, matchWrap());

        refreshRows.run();

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Horizon Settings")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Reset", (dialog, which) -> confirmReset(activity))
                .show();
    }

    private static void confirmReset(@NonNull Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Reset Horizon settings?")
                .setMessage("This resets only the Horizon Android engine settings shown here. "
                        + "DroidBridge's Game Engine renderer, driver and control choices are separate.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    try {
                        new Store(activity).resetManagedSettings();
                        LauncherLogManager.append("Horizon: reset Android engine settings from DroidBridge");
                        Toast.makeText(activity, "Horizon settings reset.", Toast.LENGTH_SHORT).show();
                    } catch (Throwable throwable) {
                        Log.e(TAG, "Could not reset Horizon settings", throwable);
                        Toast.makeText(activity, "Could not reset Horizon settings.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface StringChoice { void selected(@NonNull String value); }
    private interface IntChoice { void selected(int value); }
    private interface BoolChoice { void selected(boolean value); }

    private static void choose(@NonNull Activity activity, @NonNull String title,
                               @NonNull String[] labels, @NonNull String[] values,
                               @NonNull String current, @NonNull StringChoice chosen) {
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    chosen.selected(values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void chooseInt(@NonNull Activity activity, @NonNull String title,
                                  @NonNull String[] labels, @NonNull int[] values,
                                  int current, @NonNull IntChoice chosen) {
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) checked = i;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    chosen.selected(values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void numberEntry(@NonNull Activity activity, @NonNull String title,
                                    @NonNull String message, int current, int min, int max,
                                    @NonNull IntChoice chosen) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);
        int pad = dp(activity, 20);
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input, matchWrap());
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setView(wrap)
                .setPositiveButton("Save", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable throwable) {
                        input.setError("Enter a number from " + min + " to " + max);
                        return;
                    }
                    if (value < min || value > max) {
                        input.setError("Enter a number from " + min + " to " + max);
                        return;
                    }
                    chosen.selected(value);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    @NonNull
    private static CheckBox toggle(@NonNull Activity activity, @NonNull String label,
                                   boolean checked, @NonNull BoolChoice changed) {
        CheckBox box = new CheckBox(activity);
        box.setText(label);
        box.setChecked(checked);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.setPadding(0, dp(activity, 7), 0, dp(activity, 7));
        box.setOnCheckedChangeListener((button, isChecked) -> changed.selected(isChecked));
        return box;
    }

    @NonNull
    private static TextView row(@NonNull Activity activity, int pad) {
        TextView row = new TextView(activity);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setPadding(0, pad / 2, 0, pad / 2);
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue accent = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true)) {
            row.setTextColor(accent.data);
        }
        return row;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }


    @NonNull
    private static String defaultRenderer() {
        // Match Horizon Config::load(): missing renderer defaults to OpenGL on a 32-bit
        // Android process and Vulkan compatibility on 64-bit.
        return Build.SUPPORTED_64_BIT_ABIS.length == 0 ? "open-gl" : "vulkan-compatibility";
    }

    private static String rendererLabel(String value) {
        if ("vulkan".equals(value)) return "Vulkan / GPU batching";
        if ("open-gl".equals(value)) return "OpenGL ES / experimental";
        return "Vulkan / compatibility";
    }

    private static String orientationLabel(String value) {
        switch (value) {
            case "portrait": return "Portrait (sensor)";
            case "reverse-landscape": return "Landscape (reversed)";
            case "reverse-portrait": return "Portrait (reversed)";
            case "auto": return "Automatic rotation";
            default: return "Landscape (sensor)";
        }
    }

    private static String aspectLabel(String value) {
        switch (value) {
            case "wide": return "16:9";
            case "classic": return "4:3";
            case "square": return "1:1";
            default: return "Native";
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Store {
        @NonNull private final File home;
        @NonNull private final File file;
        @NonNull private final JSONObject json;

        Store(@NonNull Activity activity) {
            home = HorizonEngine.engineHome(activity);
            file = new File(home, FILE_NAME);
            json = read(file);
        }

        @NonNull String text(@NonNull String key, @NonNull String fallback) {
            String value = json.optString(key, fallback);
            return value == null || value.trim().isEmpty() ? fallback : value;
        }

        int integer(@NonNull String key, int fallback) {
            return json.optInt(key, fallback);
        }

        boolean bool(@NonNull String key, boolean fallback) {
            return json.has(key) ? json.optBoolean(key, fallback) : fallback;
        }

        void put(@NonNull String key, @NonNull Object value) {
            try {
                json.put(key, value);
                save();
            } catch (Throwable throwable) {
                Log.e(TAG, "Could not save Horizon setting " + key, throwable);
            }
        }

        void resetManagedSettings() throws Exception {
            for (String key : new String[]{
                    "renderer", "render_scale", "worker_limit", "cache_budget_mib", "orientation",
                    "immersive", "avoid_cutout", "aspect", "display_hz",
                    "performance_hints", "gpu_profile", "record_performance"
            }) {
                json.remove(key);
            }
            save();
        }

        private void save() throws Exception {
            if (!home.isDirectory() && !home.mkdirs() && !home.isDirectory()) {
                throw new IllegalStateException("Could not create " + home);
            }
            File partial = new File(home, "android-launcher.partial");
            byte[] bytes = json.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(partial, false)) {
                output.write(bytes);
                output.flush();
                try { output.getFD().sync(); } catch (Throwable ignored) { }
            }
            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("Could not replace " + file);
            }
            if (!partial.renameTo(file)) {
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            LauncherLogManager.append("Horizon setting saved: " + file.getName());
        }

        @NonNull private static JSONObject read(@NonNull File file) {
            if (!file.isFile()) return new JSONObject();
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) Math.min(file.length(), 1024 * 1024)];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                return new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            } catch (Throwable throwable) {
                Log.w(TAG, "Could not read Horizon settings; using defaults", throwable);
                return new JSONObject();
            }
        }
    }
}
