/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.instance;

import android.content.Context;
import androidx.annotation.NonNull;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.storage.StorageLocationStore;

/** Plans multi-selection deletion as a dependency-aware operation, without deleting extra versions. */
public final class LauncherInstanceDeletionPlanner {
    private LauncherInstanceDeletionPlanner() { }

    public static final class Plan {
        public final ArrayList<LauncherInstance> deletable;
        public final ArrayList<String> blocked;

        private Plan(ArrayList<LauncherInstance> deletable, ArrayList<String> blocked) {
            this.deletable = deletable;
            this.blocked = blocked;
        }
    }

    private static final class Node {
        final String key;
        final String label;
        final Set<String> dependsOn = new LinkedHashSet<>();
        Node(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    @NonNull
    public static Plan plan(@NonNull Context context, @NonNull List<LauncherInstance> selection) {
        Map<String, Node> installed = new LinkedHashMap<>();
        Map<String, LauncherInstance> requested = new LinkedHashMap<>();
        Set<String> selected = new LinkedHashSet<>();

        for (File home : StorageLocationStore.getVisibleMinecraftHomes(context)) {
            File versionsRoot = new File(home, "versions");
            File[] versions = versionsRoot.listFiles();
            if (versions == null) continue;
            for (File version : versions) {
                if (!version.isDirectory()) continue;
                File metadata = new File(version, version.getName() + ".json");
                if (!metadata.isFile()) continue;
                String key = versionKey(home, version.getName());
                Node node = installed.computeIfAbsent(key, unused -> new Node(key, version.getName()));
                try {
                    JSONObject json = new JSONObject(read(metadata));
                    String parent = json.optString("inheritsFrom", "").trim();
                    if (!parent.isEmpty() && !parent.equals(version.getName())) {
                        node.dependsOn.add(versionKey(home, parent));
                    }
                } catch (Exception ignored) {
                    // Do not infer missing dependencies from unreadable metadata.
                    // The delete manager still independently validates shared deletions.
                }
            }
        }

        for (LauncherInstance instance : LauncherInstanceManager.findInstances(context)) {
            if (!instance.isIsolated()) continue;
            String key = isolatedKey(instance);
            Node node = installed.computeIfAbsent(key, unused -> new Node(key, instance.getName()));
            File home = minecraftHome(instance);
            if (!instance.getBaseVersionId().trim().isEmpty()) {
                node.dependsOn.add(versionKey(home, instance.getBaseVersionId()));
            }
            if (!instance.getMinecraftVersionId().trim().isEmpty()) {
                node.dependsOn.add(versionKey(home, instance.getMinecraftVersionId()));
            }
        }

        for (LauncherInstance instance : selection) {
            String key = instance.isIsolated() ? isolatedKey(instance)
                    : versionKey(instance.getRootDirectory(), instance.getBaseVersionId());
            requested.putIfAbsent(key, instance);
            selected.add(key);
            installed.computeIfAbsent(key, unused -> new Node(key, instance.getName()));
        }

        Map<String, String> blocked = new LinkedHashMap<>();
        boolean changed;
        do {
            changed = false;
            for (String candidate : selected) {
                if (blocked.containsKey(candidate)) continue;
                for (Node dependent : installed.values()) {
                    if (candidate.equals(dependent.key) || !dependent.dependsOn.contains(candidate)) continue;
                    if (!selected.contains(dependent.key) || blocked.containsKey(dependent.key)) {
                        blocked.put(candidate, dependent.label);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);

        // Dependency edges are child -> parent. Topologically delete children first.
        LinkedHashSet<String> remaining = new LinkedHashSet<>(selected);
        remaining.removeAll(blocked.keySet());
        ArrayList<LauncherInstance> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            boolean madeProgress = false;
            for (String candidate : new ArrayList<>(remaining)) {
                boolean hasUndeletedDependent = false;
                for (String other : remaining) {
                    if (!other.equals(candidate) && installed.get(other).dependsOn.contains(candidate)) {
                        hasUndeletedDependent = true;
                        break;
                    }
                }
                if (hasUndeletedDependent) continue;
                ordered.add(requested.get(candidate));
                remaining.remove(candidate);
                madeProgress = true;
            }
            if (!madeProgress) {
                // Cyclic/malformed inheritance must not be deleted on a guess.
                for (String candidate : remaining) blocked.put(candidate, "circular version dependency");
                break;
            }
        }

        ArrayList<String> notices = new ArrayList<>();
        for (Map.Entry<String, String> entry : blocked.entrySet()) {
            LauncherInstance instance = requested.get(entry.getKey());
            if (instance != null) {
                notices.add(instance.getName() + " — required by " + entry.getValue());
            }
        }
        return new Plan(ordered, notices);
    }

    /**
     * An explicit cleanup choice. Never remove shared profiles implicitly during
     * ordinary instance deletion; include them only if the user selects cleanup.
     * The ordinary dependency planner will refuse versions still needed elsewhere.
     */
    @NonNull
    public static Plan planWithUnusedVersions(
            @NonNull Context context,
            @NonNull List<LauncherInstance> selection
    ) {
        ArrayList<LauncherInstance> expanded = new ArrayList<>(selection);
        Set<String> visited = new HashSet<>();
        for (LauncherInstance instance : selection) {
            if (!instance.isIsolated()) continue;
            File home = minecraftHome(instance);
            addInstalledVersionChain(home, instance.getBaseVersionId(), expanded, visited);
            addInstalledVersionChain(home, instance.getMinecraftVersionId(), expanded, visited);
        }
        return plan(context, expanded);
    }

    private static void addInstalledVersionChain(
            File home,
            String versionId,
            ArrayList<LauncherInstance> expanded,
            Set<String> visited
    ) {
        String current = versionId == null ? "" : versionId.trim();
        while (!current.isEmpty()) {
            String key = versionKey(home, current);
            if (!visited.add(key)) break;
            File jsonFile = new File(new File(new File(home, "versions"), current), current + ".json");
            if (!jsonFile.isFile()) break;
            expanded.add(LauncherInstance.sharedInstalledVersion(current, "release", home, ""));
            try {
                String parent = new JSONObject(read(jsonFile)).optString("inheritsFrom", "").trim();
                if (parent.equals(current)) break;
                current = parent;
            } catch (Exception ignored) {
                break;
            }
        }
    }

    private static String isolatedKey(LauncherInstance instance) {
        return "I:" + path(instance.getRootDirectory());
    }

    private static String versionKey(File home, String versionId) {
        return "V:" + path(home) + File.separator + "versions" + File.separator + versionId;
    }

    private static File minecraftHome(LauncherInstance instance) {
        if (!instance.isIsolated()) return instance.getRootDirectory();
        File parent = instance.getRootDirectory().getParentFile();
        return parent != null && parent.getParentFile() != null
                ? parent.getParentFile() : instance.getRootDirectory();
    }

    private static String path(File file) {
        try { return file.getCanonicalPath(); }
        catch (Exception ignored) { return file.getAbsolutePath(); }
    }

    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
