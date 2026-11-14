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
 * The open-source licenses pages, one for each Rust engine, from the notices each engine's
 * build writes. Sections are separated by lines holding only "----" and shown as a list,
 * since the full text is long.
 *
 * <ul>
 * <li>Horizon (the 26.3 engine): horizon-engine/tools/android_notices.py, shipped as
 * assets/horizon/THIRD_PARTY_NOTICES.md: its own license, the code it includes, the native
 * mods built into it and every Rust library compiled into it.</li>
 * <li>Rust Craft (the 1.0 engine, a separate project with nothing of Horizon in it):
 * rustcraft/tools/android_notices.py, shipped as assets/rustcraft/THIRD_PARTY_NOTICES.md and
 * assets/rustcraft/LICENSE.txt: its own license and every Rust library compiled into it.
 * Without those files the page still shows Rust Craft's own license.</li>
 * </ul>
 */
public final class OpenSourceLicenses {
    private static final String NOTICES = "horizon/THIRD_PARTY_NOTICES.md";
    private static final String ENGINE_LICENSE = "horizon/LICENSE.txt";
    private static final String RUSTCRAFT_NOTICES = "rustcraft/THIRD_PARTY_NOTICES.md";
    private static final String RUSTCRAFT_LICENSE = "rustcraft/LICENSE.txt";
    private static final String SEPARATOR = "\n\n----\n\n";

    /** Rust Craft's own license, for builds without its notice files. */
    private static final String RUSTCRAFT_MIT = "MIT License\n\n"
            + "Copyright (c) 2026 DNA Mobile Applications\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy "
            + "of this software and associated documentation files (the \"Software\"), to deal "
            + "in the Software without restriction, including without limitation the rights "
            + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell "
            + "copies of the Software, and to permit persons to whom the Software is "
            + "furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all "
            + "copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR "
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, "
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE "
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER "
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, "
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE "
            + "SOFTWARE.";

    private OpenSourceLicenses() {
    }

    /** Shows Horizon's page. The notices are read off the main thread. */
    public static void show(@NonNull Activity activity) {
        showLoaded(activity, "Open-source licenses", () -> load(activity));
    }

    /** Shows Rust Craft's page (the 1.0 engine's own, not Horizon's). */
    public static void showRustcraft(@NonNull Activity activity) {
        showLoaded(activity, "Rust Craft licenses", () -> loadRustcraft(activity));
    }

    private interface Loader {
        @NonNull
        List<String> load();
    }

    private static void showLoaded(@NonNull Activity activity, @NonNull String title, @NonNull Loader loader) {
        Handler main = new Handler(Looper.getMainLooper());
        Thread reader = new Thread(() -> {
            List<String> sections = loader.load();
            main.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                present(activity, title, sections);
            });
        }, "DroidBridgeLicenses");
        reader.setDaemon(true);
        reader.start();
    }

    /** Rust Craft's notices: its license first, then each library compiled into it. */
    @NonNull
    private static List<String> loadRustcraft(@NonNull Activity activity) {
        String notices = readAsset(activity, RUSTCRAFT_NOTICES);
        String license = readAsset(activity, RUSTCRAFT_LICENSE);
        List<String> sections = new ArrayList<>();
        if (notices != null && notices.contains("----")) {
            // Written by Rust Craft's build: it already starts with Rust Craft's license.
            for (String section : notices.replace("\r\n", "\n").split(SEPARATOR)) {
                if (!section.trim().isEmpty()) sections.add(section.trim());
            }
            return sections;
        }
        sections.add("# Rust Craft\n\nMinecraft: Java Edition rewritten in Rust (a title update "
                + "for each version: TU1 plays as 1.0, TU2 as 1.1). It reads the game's pictures "
                + "and sounds from your own copy of Minecraft and contains "
                + "none of Minecraft's code or files. Not an official Minecraft product: not "
                + "approved by or associated with Mojang or Microsoft.\n\n"
                + (license != null ? license.trim() : RUSTCRAFT_MIT));
        if (notices != null && !notices.trim().isEmpty()) {
            sections.add(notices.trim());
        } else {
            sections.add("The libraries compiled into the Rust Craft engine (its graphics, window "
                    + "and input libraries, among others) are under the MIT and Apache 2.0 "
                    + "licenses; their full notices come with an engine build made by Rust "
                    + "Craft's tools/build_android.py.");
        }
        return sections;
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

    private static void present(@NonNull Activity activity, @NonNull String title, @NonNull List<String> sections) {
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
                .setTitle(title)
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
