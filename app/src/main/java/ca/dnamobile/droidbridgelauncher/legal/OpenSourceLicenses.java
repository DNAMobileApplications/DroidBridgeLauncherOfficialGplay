/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.legal;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;

/**
 * The open-source licenses page: the notices the engine build writes
 * (horizon-engine/tools/android_notices.py, shipped as assets/horizon/THIRD_PARTY_NOTICES.md):
 * the Rust engine's own license, the code it includes, the native mods built into it and every
 * Rust library compiled into it. Sections are separated by lines holding only "----" and shown
 * as a list, since the full text is long.
 */
public final class OpenSourceLicenses {
    private static final String NOTICES = "horizon/THIRD_PARTY_NOTICES.md";
    private static final String ENGINE_LICENSE = "horizon/LICENSE.txt";
    private static final String SEPARATOR = "\n\n----\n\n";

    private OpenSourceLicenses() {
    }

    /** Shows the page. The notices are read off the main thread. */
    public static void show(@NonNull Activity activity) {
        Handler main = new Handler(Looper.getMainLooper());
        Thread reader = new Thread(() -> {
            List<String> sections = load(activity);
            main.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                present(activity, sections);
            });
        }, "DroidBridgeLicenses");
        reader.setDaemon(true);
        reader.start();
    }

    @NonNull
    private static List<String> load(@NonNull Activity activity) {
        String notices = readAsset(activity, NOTICES);
        String license = readAsset(activity, ENGINE_LICENSE);
        List<String> sections = new ArrayList<>();
        if (notices != null && notices.contains("----")) {
            // Written by the engine build: it already holds the engine's license.
            for (String section : notices.replace("\r\n", "\n").split(SEPARATOR)) {
                if (!section.trim().isEmpty()) sections.add(section.trim());
            }
        } else {
            if (license != null) sections.add("Rust engine (Horizon, MIT)\n\n" + license.trim());
            if (notices != null) sections.add(notices.trim());
        }
        if (sections.isEmpty()) {
            sections.add("This build of DroidBridge doesn't include the Rust engine, so it has no "
                    + "engine notices to show.");
        }
        return sections;
    }

    private static void present(@NonNull Activity activity, @NonNull List<String> sections) {
        LauncherDialogStyle.syncSelectedLauncherTheme(activity);
        int pad = LauncherDialogStyle.dp(activity, 16);
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setPadding(pad, pad / 2, pad, pad / 2);
        list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return sections.size();
            }

            @Override
            public Object getItem(int position) {
                return sections.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(activity);
                String text = sections.get(position);
                boolean heading = text.startsWith("#");
                view.setText(stripMarks(text));
                view.setTextColor(position == 0 ? LauncherDialogStyle.COLOR_TEXT_PRIMARY
                        : LauncherDialogStyle.COLOR_TEXT_SECONDARY);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, heading && text.length() < 400 ? 14 : 12);
                view.setPadding(0, pad / 2, 0, pad / 2);
                view.setTextIsSelectable(false);
                return view;
            }
        });
        AlertDialog dialog = LauncherDialogStyle.builder(activity)
                .setTitle("Open-source licenses")
                .setView(list)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(activity, dialog));
        dialog.show();
    }

    /** Markdown heading marks ("## ") read as plain text. */
    @NonNull
    private static String stripMarks(@NonNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : Arrays.asList(text.split("\n", -1))) {
            String trimmed = line;
            while (trimmed.startsWith("#")) trimmed = trimmed.substring(1);
            if (trimmed.length() != line.length()) trimmed = trimmed.trim();
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString();
    }

    private static String readAsset(@NonNull Activity activity, @NonNull String name) {
        try (InputStream input = activity.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
