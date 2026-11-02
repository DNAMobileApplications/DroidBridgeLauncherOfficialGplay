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

package ca.dnamobile.droidbridgelauncher.modmanager;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persistent per-provider favourites for the global modpack browser. */
public final class ModpackFavouritesStore {
    private static final String PREFS = "modpack_favourites_v1";
    private static final String KEY_INITIALIZED_PREFIX = "initialized_";
    private static final String KEY_ENTRIES_PREFIX = "entries_";

    public static final String KEY_DEVELOPER_OPTIMOBILE = "developer:optimobile";
    public static final String KEY_DEVELOPER_VULKAN_DROID = "developer:vulkan-droid";

    private static final String OPTIMOBILE_CF_FABRIC_ID = "1456510";
    private static final String OPTIMOBILE_CF_FORGE_ID = "1385364";
    private static final String VULKAN_DROID_CF_ID = "1421646";
    private static final String VULKAN_DROID_MODRINTH_ID = "PXY8IEra";

    private ModpackFavouritesStore() {
    }

    public static final class FavouriteRecord {
        @NonNull public final String key;
        @NonNull public final String projectId;
        @NonNull public final String slug;
        @NonNull public final String title;

        public FavouriteRecord(
                @NonNull String key,
                @Nullable String projectId,
                @Nullable String slug,
                @Nullable String title
        ) {
            this.key = clean(key);
            this.projectId = clean(projectId);
            this.slug = clean(slug);
            this.title = clean(title);
        }

        @NonNull
        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("key", key);
                object.put("projectId", projectId);
                object.put("slug", slug);
                object.put("title", title);
            } catch (Throwable ignored) {
            }
            return object;
        }

        @NonNull
        static FavouriteRecord fromJson(@Nullable JSONObject object) {
            if (object == null) return new FavouriteRecord("", "", "", "");
            return new FavouriteRecord(
                    object.optString("key", ""),
                    object.optString("projectId", ""),
                    object.optString("slug", ""),
                    object.optString("title", "")
            );
        }
    }

    @NonNull
    public static ArrayList<FavouriteRecord> list(
            @NonNull Context context,
            @NonNull ModManagerSource source
    ) {
        ensureDefaults(context, source);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(entriesKey(source), "[]");
        ArrayList<FavouriteRecord> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                FavouriteRecord record = FavouriteRecord.fromJson(array.optJSONObject(i));
                if (!record.key.isEmpty()) out.add(record);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean isFavourite(
            @NonNull Context context,
            @NonNull ModrinthProject project
    ) {
        String key = canonicalKey(project);
        if (key.isEmpty()) return false;
        for (FavouriteRecord record : list(context, project.source)) {
            if (key.equals(record.key)) return true;
        }
        return false;
    }

    public static void setFavourite(
            @NonNull Context context,
            @NonNull ModrinthProject project,
            boolean favourite
    ) {
        String key = canonicalKey(project);
        if (key.isEmpty()) return;

        ArrayList<FavouriteRecord> records = list(context, project.source);
        for (int i = records.size() - 1; i >= 0; i--) {
            if (key.equals(records.get(i).key)) records.remove(i);
        }
        if (favourite) {
            records.add(new FavouriteRecord(key, project.projectId, project.slug, project.title));
        }
        save(context, project.source, records);
    }

    public static boolean isDeveloperPick(@Nullable ModrinthProject project) {
        if (project == null) return false;
        String key = canonicalKey(project);
        return KEY_DEVELOPER_OPTIMOBILE.equals(key)
                || KEY_DEVELOPER_VULKAN_DROID.equals(key);
    }

    @NonNull
    public static String canonicalKey(@Nullable ModrinthProject project) {
        if (project == null) return "";
        if (isOptiMobile(project)) return KEY_DEVELOPER_OPTIMOBILE;
        if (isVulkanDroid(project)) return KEY_DEVELOPER_VULKAN_DROID;

        String id = clean(project.projectId).toLowerCase(Locale.US);
        if (!id.isEmpty()) return "id:" + id;
        String slug = normalize(project.slug);
        if (!slug.isEmpty()) return "slug:" + slug;
        String title = normalize(project.title);
        return title.isEmpty() ? "" : "title:" + title;
    }

    private static void ensureDefaults(
            @NonNull Context context,
            @NonNull ModManagerSource source
    ) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String initializedKey = initializedKey(source);
        if (prefs.getBoolean(initializedKey, false)) return;

        ArrayList<FavouriteRecord> defaults = new ArrayList<>();
        if (source == ModManagerSource.CURSEFORGE) {
            defaults.add(new FavouriteRecord(
                    KEY_DEVELOPER_OPTIMOBILE,
                    OPTIMOBILE_CF_FABRIC_ID,
                    "optimobile-fabric",
                    "OptiMobile"
            ));
            defaults.add(new FavouriteRecord(
                    KEY_DEVELOPER_VULKAN_DROID,
                    VULKAN_DROID_CF_ID,
                    "vulkan-droid",
                    "Vulkan Droid"
            ));
        } else {
            defaults.add(new FavouriteRecord(
                    KEY_DEVELOPER_OPTIMOBILE,
                    "",
                    "optimobile",
                    "OptiMobile"
            ));
            defaults.add(new FavouriteRecord(
                    KEY_DEVELOPER_VULKAN_DROID,
                    VULKAN_DROID_MODRINTH_ID,
                    "vulkan-droid",
                    "Vulkan Droid"
            ));
        }

        JSONArray array = new JSONArray();
        for (FavouriteRecord record : defaults) array.put(record.toJson());
        prefs.edit()
                .putString(entriesKey(source), array.toString())
                .putBoolean(initializedKey, true)
                .apply();
    }

    private static void save(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull List<FavouriteRecord> records
    ) {
        JSONArray array = new JSONArray();
        for (FavouriteRecord record : records) {
            if (!record.key.isEmpty()) array.put(record.toJson());
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(entriesKey(source), array.toString())
                .putBoolean(initializedKey(source), true)
                .apply();
    }

    private static boolean isOptiMobile(@NonNull ModrinthProject project) {
        String title = normalize(project.title);
        String slug = normalize(project.slug);
        String id = clean(project.projectId);
        if (project.source == ModManagerSource.CURSEFORGE) {
            return OPTIMOBILE_CF_FABRIC_ID.equals(id)
                    || OPTIMOBILE_CF_FORGE_ID.equals(id)
                    || slug.equals("optimobile-fabric")
                    || slug.endsWith("/optimobile-fabric")
                    || slug.equals("optimobile-forge")
                    || slug.endsWith("/optimobile-forge")
                    || title.equals("optimobile")
                    || title.equals("optimobile fabric")
                    || title.equals("optimobile forge");
        }
        return title.equals("optimobile")
                || title.equals("optimobile fabric")
                || title.equals("optimobile forge")
                || slug.equals("optimobile")
                || slug.equals("optimobile-fabric")
                || slug.equals("optimobile-forge")
                || id.equalsIgnoreCase("optimobile");
    }

    private static boolean isVulkanDroid(@NonNull ModrinthProject project) {
        String title = normalize(project.title);
        String slug = normalize(project.slug);
        String id = clean(project.projectId);
        if (project.source == ModManagerSource.CURSEFORGE) {
            return VULKAN_DROID_CF_ID.equals(id)
                    || slug.equals("vulkan-droid")
                    || title.contains("vulkan droid");
        }
        return VULKAN_DROID_MODRINTH_ID.equalsIgnoreCase(id)
                || slug.equals("vulkan-droid")
                || title.contains("vulkan droid");
    }

    @NonNull
    private static String entriesKey(@NonNull ModManagerSource source) {
        return KEY_ENTRIES_PREFIX + source.name().toLowerCase(Locale.US);
    }

    @NonNull
    private static String initializedKey(@NonNull ModManagerSource source) {
        return KEY_INITIALIZED_PREFIX + source.name().toLowerCase(Locale.US);
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return clean(value)
                .toLowerCase(Locale.US)
                .replace('+', '-')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace('_', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
