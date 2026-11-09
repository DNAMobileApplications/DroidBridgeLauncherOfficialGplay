/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.engine.horizon;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;

/**
 * Horizon Add-ons for one instance: its Horizon mods, its native mods' switches, its shader
 * packs, and Horizon's own mod registry.
 *
 * What Horizon runs (Horizon's docs/mods.md):
 * <ul>
 *     <li>{@code .hmod} mods: data mods (blocks, items, recipes, loot, worldgen, textures) and
 *     sandboxed WebAssembly code mods. Installed here into the instance's horizon-mods folder.</li>
 *     <li>Built-in mods: native mods ({@code .hnmod}, Rust source) compiled into the engine when
 *     DroidBridge is built (horizon-engine/native-mods). A phone can't compile them, so they can't be
 *     added here; this screen lists the ones this DroidBridge carries, each with an on/off switch
 *     per instance.</li>
 *     <li>Shader packs: Horizon's {@code .hshader}, and classic shaders/ ZIPs (experimental).</li>
 * </ul>
 * Fabric, NeoForge and Forge jars do not run in Horizon.
 *
 * Every file is checked with the game's own checks in the engine's process
 * ({@link dev.horizon.android.HorizonAddonsProvider}) before it is put in place. Changes apply the
 * next time Horizon starts.
 */
public final class HorizonAddonsActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_DIR = "ca.dnamobile.droidbridgelauncher.horizon.addons.GAME_DIR";
    public static final String EXTRA_INSTANCE_NAME = "ca.dnamobile.droidbridgelauncher.horizon.addons.INSTANCE_NAME";

    private static final int REQUEST_IMPORT_MOD = 4101;
    private static final int REQUEST_IMPORT_SHADER = 4102;

    private enum Tab {
        MODS("Mods"),
        SHADERS("Shaders"),
        CATALOG("Mod catalog");

        @NonNull final String label;

        Tab(@NonNull String label) {
            this.label = label;
        }
    }

    /** Opens the add-ons of the instance whose game folder is {@code gameDir}. */
    public static void start(@NonNull Activity activity, @Nullable File gameDir, @Nullable String instanceName) {
        if (gameDir == null) {
            Toast.makeText(activity, "This instance has no game folder yet. Play it once first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(activity, HorizonAddonsActivity.class);
        intent.putExtra(EXTRA_GAME_DIR, gameDir.getAbsolutePath());
        intent.putExtra(EXTRA_INSTANCE_NAME, instanceName == null ? "" : instanceName);
        activity.startActivity(intent);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The game's check of each file, by path, size and time, so a tab switch doesn't redo it. */
    private final Map<String, HorizonAddons.Item> inspected = new HashMap<>();

    private File gameDir;
    private String instanceName = "";
    private Tab tab = Tab.MODS;
    private volatile int generation;
    private boolean busy;

    private final TextView[] tabButtons = new TextView[Tab.values().length];
    private LinearLayout actions;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;

    @Nullable private List<HorizonAddons.NativeMod> nativeMods;
    @Nullable private List<HorizonModCatalog.Project> catalog;
    /** This engine's native hook points version: 0 not asked yet, -1 unknown. */
    private int engineHooks;
    /** For the catalog's built-in rows: this instance's switches. */
    @NonNull private HorizonAddons.Switches catalogSwitches = new HorizonAddons.Switches();
    private boolean refreshCatalog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_GAME_DIR);
        if (path == null || path.trim().isEmpty()) {
            finish();
            return;
        }
        gameDir = new File(path);
        String name = getIntent().getStringExtra(EXTRA_INSTANCE_NAME);
        instanceName = name == null ? "" : name;
        LauncherDialogStyle.syncSelectedLauncherTheme(this);
        buildLayout();
        FullscreenUtils.enableImmersive(this);
        worker.execute(() -> HorizonAddons.cleanPartials(gameDir));
        showTab(Tab.MODS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenUtils.enableImmersive(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) FullscreenUtils.enableImmersive(this);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // --- Layout ----------------------------------------------------------------------------------

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        root.setPadding(dp(20), dp(14), dp(20), dp(12));
        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        TextView back = textButton("‹", false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        back.setContentDescription("Back");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Add-ons");
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText((instanceName.isEmpty() ? "" : instanceName + " · ")
                + "Changes apply the next time the game starts");
        subtitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titles.addView(subtitle, matchWrap());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabsParams = matchWrap();
        tabsParams.topMargin = dp(12);
        root.addView(tabs, tabsParams);
        for (Tab each : Tab.values()) {
            TextView button = textButton(each.label, false);
            button.setOnClickListener(view -> {
                if (tab != each) showTab(each);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
            params.setMargins(each.ordinal() == 0 ? 0 : dp(4), 0, each.ordinal() == Tab.values().length - 1 ? 0 : dp(4), 0);
            tabs.addView(button, params);
            tabButtons[each.ordinal()] = button;
        }

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(10);
        root.addView(actions, actionParams);

        status = new TextView(this);
        status.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(dp(2), dp(8), dp(2), 0);
        root.addView(status, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(6);
        root.addView(scroll, scrollParams);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showTab(@NonNull Tab next) {
        tab = next;
        generation++;
        for (Tab each : Tab.values()) styleTab(tabButtons[each.ordinal()], each == tab);
        actions.removeAllViews();
        list.removeAllViews();
        setStatus("", false);
        switch (tab) {
            case MODS:
                addAction("Import .hmod file", true, view -> pick(REQUEST_IMPORT_MOD));
                addAction("Browse the mod catalog", false, view -> showTab(Tab.CATALOG));
                loadMods();
                break;
            case SHADERS:
                addAction("Import shader pack", true, view -> pick(REQUEST_IMPORT_SHADER));
                loadShaders();
                break;
            case CATALOG:
                addAction("Refresh", true, view -> {
                    refreshCatalog = true;
                    showTab(Tab.CATALOG);
                });
                addAction("Registry page", false, view -> openLink(HorizonModCatalog.REGISTRY_PAGE));
                loadCatalog();
                break;
        }
    }

    private void addAction(@NonNull String label, boolean filled, @NonNull View.OnClickListener listener) {
        TextView button = textButton(label, filled);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
        params.setMargins(actions.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
        actions.addView(button, params);
    }

    // --- Mods tab --------------------------------------------------------------------------------

    private void loadMods() {
        int at = generation;
        setStatus("Reading this instance's mods...", true);
        worker.execute(() -> {
            if (nativeMods == null) nativeMods = HorizonAddons.nativeMods(this);
            List<HorizonAddons.Item> items = inspectAll(HorizonAddons.listMods(gameDir));
            HorizonAddons.Switches switches = HorizonAddons.readSwitches(gameDir);
            List<HorizonAddons.NativeMod> natives = new ArrayList<>(nativeMods);
            post(at, () -> renderMods(natives, items, switches));
        });
    }

    private void renderMods(
            @NonNull List<HorizonAddons.NativeMod> natives,
            @NonNull List<HorizonAddons.Item> items,
            @NonNull HorizonAddons.Switches switches
    ) {
        list.removeAllViews();
        setStatus("", false);

        section("Added mods");
        if (items.isEmpty()) {
            LinearLayout empty = card();
            cardText(empty, "No mods added to this instance yet.", LauncherDialogStyle.COLOR_TEXT_PRIMARY, 14);
            cardText(empty, "Tap Import .hmod file to add one, or install one from the Mod catalog tab.",
                    LauncherDialogStyle.COLOR_TEXT_MUTED, 12);
        }
        for (HorizonAddons.Item item : items) {
            LinearLayout row = card();
            String meta = (item.id == null ? item.file.getName() : item.id)
                    + (item.file.isDirectory() ? " · folder" : "")
                    + (item.problem != null ? "" : item.code ? " · runs sandboxed code" : " · content only");
            boolean on = item.id != null && switches.contentModOn(item.id);
            SwitchMaterial toggle = rowWithSwitch(row, item.title(), meta, on);
            if (item.id == null) {
                toggle.setEnabled(false);
            } else {
                String id = item.id;
                toggle.setOnCheckedChangeListener((button, checked) -> saveSwitch(id, checked, false, button));
            }
            if (item.description != null) {
                cardText(row, item.description, LauncherDialogStyle.COLOR_TEXT_SECONDARY, 13);
            }
            if (!item.capabilities.isEmpty()) {
                cardText(row, "May " + TextUtils.join("; ", item.capabilities) + ".",
                        LauncherDialogStyle.COLOR_TEXT_MUTED, 12);
            }
            if (item.problem != null) {
                cardText(row, "Won't load: " + item.problem, LauncherDialogStyle.COLOR_ERROR, 12);
            }
            rowActions(row, "Remove", view -> confirmRemove(item));
        }

        if (natives.isEmpty()) return;
        section("Built-in mods (" + natives.size() + ")");
        LinearLayout about = card();
        cardText(about, "These come with DroidBridge. Turn one on to use it in this instance; its "
                + "settings are in game under Options > Mods.", LauncherDialogStyle.COLOR_TEXT_MUTED, 12);
        for (HorizonAddons.NativeMod mod : natives) {
            builtInRow(mod, switches);
        }
    }

    /** A built-in mod with its on/off switch for this instance (or "Always on"). */
    private void builtInRow(@NonNull HorizonAddons.NativeMod mod, @NonNull HorizonAddons.Switches switches) {
        LinearLayout row = card();
        String title = nativeTitle(mod);
        if (!mod.offSwitch) {
            rowTitle(row, title, "Always on: it has no on/off switch");
        } else {
            SwitchMaterial toggle = rowWithSwitch(row, title, "Built in", switches.nativeInstalled(mod.id));
            toggle.setOnCheckedChangeListener((button, checked) -> saveSwitch(mod.id, checked, true, button));
        }
        if (!mod.description.isEmpty()) {
            cardText(row, mod.description, LauncherDialogStyle.COLOR_TEXT_SECONDARY, 13);
        }
    }

    /** The engine's own name for a native mod when it has one (its bundled mods), else modTitle. */
    @NonNull
    private static String nativeTitle(@NonNull HorizonAddons.NativeMod mod) {
        if (mod.title.isEmpty()) return modTitle(mod.id, mod.version);
        return mod.version.isEmpty() ? mod.title : mod.title + " " + mod.version;
    }

    /** "coords_hud" and "2.0.0" as "Coords hud 2.0.0". */
    @NonNull
    private static String modTitle(@NonNull String id, @NonNull String version) {
        String name = id.replace('_', ' ').trim();
        if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return version.isEmpty() ? name : name + " " + version;
    }

    private void saveSwitch(@NonNull String id, boolean on, boolean nativeMod, @NonNull View control) {
        worker.execute(() -> {
            try {
                HorizonAddons.setSwitch(gameDir, id, on, nativeMod);
                HorizonAddons.log("Horizon add-ons: " + id + (on ? " on" : " off") + " for " + instanceName);
            } catch (IOException error) {
                main.post(() -> {
                    Toast.makeText(this, "Could not save the switch: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    if (control instanceof SwitchMaterial) {
                        SwitchMaterial toggle = (SwitchMaterial) control;
                        toggle.setOnCheckedChangeListener(null);
                        toggle.setChecked(!on);
                        toggle.setOnCheckedChangeListener((button, checked) -> saveSwitch(id, checked, nativeMod, button));
                    }
                });
            }
        });
    }

    // --- Shaders tab -----------------------------------------------------------------------------

    private void loadShaders() {
        int at = generation;
        setStatus("Reading this instance's shader packs...", true);
        worker.execute(() -> {
            List<HorizonAddons.Item> items = inspectAll(HorizonAddons.listShaders(gameDir));
            String picked = HorizonAddons.pickedShader(gameDir);
            post(at, () -> renderShaders(items, picked));
        });
    }

    private void renderShaders(@NonNull List<HorizonAddons.Item> items, @NonNull String picked) {
        list.removeAllViews();
        setStatus("Folder: " + HorizonAddons.folder(gameDir, HorizonAddons.Kind.SHADER).getAbsolutePath(), false);

        LinearLayout about = card();
        cardText(about, "Native shader packs (.hshader) are fully supported. Classic shaders/ ZIPs (Iris and "
                + "OptiFine packs) are experimental in the Rust engine, so some effects won't show.",
                LauncherDialogStyle.COLOR_TEXT_SECONDARY, 13);
        cardText(about, "You can also pick one in game, under Shader Packs in the engine's settings.",
                LauncherDialogStyle.COLOR_TEXT_MUTED, 12);

        List<RadioButton> radios = new ArrayList<>();
        LinearLayout none = card();
        RadioButton noneRadio = rowWithRadio(none, "No shader pack", "The engine's own rendering", picked.isEmpty());
        radios.add(noneRadio);
        noneRadio.setOnClickListener(view -> chooseShader("", radios, noneRadio));

        for (HorizonAddons.Item item : items) {
            LinearLayout row = card();
            String meta = item.classicShader ? "Classic shader pack · experimental"
                    : item.problem != null ? item.file.getName() : "Native shader pack · " + item.file.getName();
            RadioButton radio = rowWithRadio(row, item.title(), meta, item.file.getName().equals(picked));
            radios.add(radio);
            radio.setOnClickListener(view -> chooseShader(item.file.getName(), radios, radio));
            if (item.problem != null) {
                cardText(row, "Won't load: " + item.problem, LauncherDialogStyle.COLOR_ERROR, 12);
            }
            if (item.file.isFile()) rowActions(row, "Remove", view -> confirmRemove(item));
        }
    }

    private void chooseShader(@NonNull String fileName, @NonNull List<RadioButton> radios, @NonNull RadioButton chosen) {
        for (RadioButton radio : radios) radio.setChecked(radio == chosen);
        worker.execute(() -> {
            try {
                HorizonAddons.pickShader(gameDir, fileName);
            } catch (IOException error) {
                main.post(() -> Toast.makeText(this, "Could not save the shader pick: " + error.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    // --- Catalog tab -----------------------------------------------------------------------------

    private void loadCatalog() {
        int at = generation;
        boolean refresh = refreshCatalog || catalog == null;
        refreshCatalog = false;
        setStatus("Reading the mod catalog...", true);
        worker.execute(() -> {
            List<HorizonModCatalog.Project> projects;
            try {
                projects = refresh ? HorizonModCatalog.load(this, true) : catalog;
                if (projects == null) projects = HorizonModCatalog.load(this, false);
                catalog = projects;
            } catch (IOException error) {
                String message = error.getMessage();
                post(at, () -> {
                    list.removeAllViews();
                    setStatus("", false);
                    LinearLayout failed = card();
                    cardText(failed, "The mod catalog could not be reached.", LauncherDialogStyle.COLOR_TEXT_PRIMARY, 14);
                    cardText(failed, message == null ? "Check your connection and press Refresh." : message,
                            LauncherDialogStyle.COLOR_TEXT_MUTED, 12);
                });
                return;
            }
            if (nativeMods == null) nativeMods = HorizonAddons.nativeMods(this);
            Map<String, String> installedMods = new HashMap<>();
            for (HorizonAddons.Item item : inspectAll(HorizonAddons.listMods(gameDir))) {
                if (item.id != null) installedMods.put(item.id, item.version == null ? "" : item.version);
            }
            if (engineHooks == 0) engineHooks = HorizonAddons.engineHooksApi(this);
            HorizonAddons.Switches switches = HorizonAddons.readSwitches(gameDir);
            main.post(() -> catalogSwitches = switches);
            int hooks = engineHooks;
            List<HorizonModCatalog.Project> shown = projects;
            List<HorizonAddons.NativeMod> natives = new ArrayList<>(nativeMods);
            post(at, () -> renderCatalog(shown, installedMods, natives, hooks));
        });
    }

    private void renderCatalog(
            @NonNull List<HorizonModCatalog.Project> projects,
            @NonNull Map<String, String> installedMods,
            @NonNull List<HorizonAddons.NativeMod> natives,
            int engineHooksApi
    ) {
        list.removeAllViews();
        String game = HorizonEngine.MINECRAFT_VERSION;
        List<HorizonModCatalog.Project> installable = new ArrayList<>();
        List<HorizonModCatalog.Project> nativeProjects = new ArrayList<>();
        int other = 0;
        for (HorizonModCatalog.Project project : projects) {
            if (project.newestFor(game) == null) continue;
            if (project.installKind() != null) installable.add(project);
            else if (project.isNative()) nativeProjects.add(project);
            else other++;
        }
        setStatus(projects.size() + " projects in the mod catalog · showing those made for " + game
                + (other > 0 ? " · " + other + " modpacks or other kinds not shown" : ""), false);

        section("Install here");
        if (installable.isEmpty()) {
            LinearLayout empty = card();
            cardText(empty, "Nothing installable on the phone is listed for " + game + " right now.",
                    LauncherDialogStyle.COLOR_TEXT_MUTED, 12);
        }
        for (HorizonModCatalog.Project project : installable) {
            HorizonModCatalog.Version version = project.newestFor(game);
            if (version == null) continue;
            LinearLayout row = card();
            rowTitle(row, project.name, kindLabel(project) + " · " + version.version
                    + (project.authors.isEmpty() ? "" : " · by " + project.authors));
            if (!project.summary.isEmpty()) cardText(row, project.summary, LauncherDialogStyle.COLOR_TEXT_SECONDARY, 13);
            String button = "Install";
            if (project.installKind() == HorizonAddons.Kind.MOD && installedMods.containsKey(project.id)) {
                button = version.version.equals(installedMods.get(project.id)) ? "Reinstall" : "Update";
            } else if (project.installKind() != HorizonAddons.Kind.MOD && destinationFor(project, version).exists()) {
                button = "Reinstall";
            }
            rowActions(row, button, view -> install(project, version));
        }

        if (!natives.isEmpty()) {
            section("Built into DroidBridge (" + natives.size() + ")");
            for (HorizonAddons.NativeMod mod : natives) {
                builtInRow(mod, catalogSwitches);
            }
        }
    }

    @NonNull
    private static String kindLabel(@NonNull HorizonModCatalog.Project project) {
        switch (project.kind) {
            case "mod":
                return "Mod";
            case "shader":
                return "Shader pack";
            case "resourcepack":
                return "Resource pack";
            default:
                return project.kind;
        }
    }

    @NonNull
    private File destinationFor(@NonNull HorizonModCatalog.Project project, @NonNull HorizonModCatalog.Version version) {
        HorizonAddons.Kind kind = project.installKind();
        File folder = HorizonAddons.folder(gameDir, kind == null ? HorizonAddons.Kind.MOD : kind);
        if (kind == HorizonAddons.Kind.MOD) return new File(folder, project.id + ".hmod");
        return new File(folder, HorizonAddons.safeFileName(version.fileName.isEmpty()
                ? project.id + ".zip" : version.fileName));
    }

    private void install(@NonNull HorizonModCatalog.Project project, @NonNull HorizonModCatalog.Version version) {
        HorizonAddons.Kind kind = project.installKind();
        if (kind == null || busy) return;
        busy = true;
        setStatus("Downloading " + project.name + " " + version.version + "...", true);
        worker.execute(() -> {
            File partial = null;
            try {
                File folder = HorizonAddons.folder(gameDir, kind);
                long[] lastPost = {0};
                partial = HorizonModCatalog.downloadFile(project, version, folder, (done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastPost[0] < 200 && done < total) return;
                    lastPost[0] = now;
                    String percent = total > 0 ? " " + (done * 100 / total) + "%" : "";
                    main.post(() -> setStatus("Downloading " + project.name + percent, true));
                });
                main.post(() -> setStatus("Checking " + project.name + "...", true));
                String trust = HorizonAddons.verifyWithEngine(this, HorizonModCatalog.indexFile(this),
                        project.id, version.version, partial);
                File destination = destinationFor(project, version);
                HorizonAddons.commit(partial, destination, true);
                partial = null;
                HorizonAddons.Item item = new HorizonAddons.Item(kind, destination);
                if (kind == HorizonAddons.Kind.MOD) {
                    HorizonAddons.inspect(this, item);
                    removeOtherCopies(project.id, destination);
                }
                HorizonAddons.log("Horizon add-ons: installed " + project.id + " " + version.version
                        + " (" + trust + ") into " + destination);
                main.post(() -> {
                    busy = false;
                    setStatus("", false);
                    afterInstall(item, project.name + " " + version.version, trust);
                });
            } catch (IOException | RuntimeException error) {
                if (partial != null) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                }
                String message = error.getMessage();
                main.post(() -> {
                    busy = false;
                    setStatus("", false);
                    showMessage("Not installed", project.name + " was not installed: "
                            + (message == null ? error.getClass().getSimpleName() : message));
                });
            }
        });
    }

    /** A mod id loads once: other files of the same mod would be refused by the game. */
    private void removeOtherCopies(@NonNull String id, @NonNull File keep) {
        for (HorizonAddons.Item item : inspectAll(HorizonAddons.listMods(gameDir))) {
            if (id.equals(item.id) && !item.file.getAbsoluteFile().equals(keep.getAbsoluteFile())) {
                HorizonAddons.deleteRecursively(item.file);
            }
        }
    }

    private void afterInstall(@NonNull HorizonAddons.Item item, @NonNull String title, @Nullable String trust) {
        String checked = trust == null ? "" : "verified".equals(trust)
                ? "\n\nIts signature matches its author's key, and its SHA-256 the registry's reviewed file."
                : "\n\nIts SHA-256 matches the registry's reviewed file (its author doesn't sign releases).";
        switch (item.kind) {
            case MOD: {
                String may = item.capabilities.isEmpty() ? "" : "\n\nIt may " + TextUtils.join("; ", item.capabilities) + ".";
                String id = item.id;
                AlertDialog dialog = LauncherDialogStyle.builder(this)
                        .setTitle("Installed " + title)
                        .setMessage("New mods start off." + may + checked)
                        .setPositiveButton(id == null ? "OK" : "Turn on", (d, which) -> {
                            if (id != null) saveSwitch(id, true, false, list);
                            refreshTab();
                        })
                        .setNegativeButton(id == null ? null : "Leave off", (d, which) -> refreshTab())
                        .create();
                showDialog(dialog);
                break;
            }
            case SHADER: {
                String file = item.file.getName();
                AlertDialog dialog = LauncherDialogStyle.builder(this)
                        .setTitle("Installed " + title)
                        .setMessage("Use it the next time the game starts?" + checked)
                        .setPositiveButton("Use it", (d, which) -> worker.execute(() -> {
                            try {
                                HorizonAddons.pickShader(gameDir, file);
                            } catch (IOException ignored) {
                            }
                            main.post(this::refreshTab);
                        }))
                        .setNegativeButton("Not now", (d, which) -> refreshTab())
                        .create();
                showDialog(dialog);
                break;
            }
            case RESOURCE_PACK:
            default:
                showMessage("Installed " + title, "It is in this instance's resourcepacks folder. Turn it on "
                        + "in game under Options > Resource Packs." + checked);
                refreshTab();
                break;
        }
    }

    // --- Import ----------------------------------------------------------------------------------

    private void pick(int request) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No file picker is available.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_MOD) importMod(uri);
        if (requestCode == REQUEST_IMPORT_SHADER) importShader(uri);
    }

    private void importMod(@NonNull Uri uri) {
        String name = HorizonAddons.displayName(this, uri, "mod.hmod");
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) {
            showMessage("Java mods don't run in the Rust engine", name + " is a Fabric, NeoForge or Forge mod. Its "
                    + "code is written for Minecraft's Java classes, which the Rust engine doesn't have, so it "
                    + "can't run here. Look for a version of it in the Mod catalog tab, or play this "
                    + "instance with the Java engine.");
            return;
        }
        if (lower.endsWith(".hnmod")) {
            showMessage("Only .hmod files can be imported", name + " is a native mod. Native mods are "
                    + "built into DroidBridge, so they can't be added on the phone: the ones this "
                    + "version includes are under Built-in mods, each with its own on/off switch.");
            return;
        }
        if (!lower.endsWith(".hmod")) {
            showMessage("Only .hmod files can be imported", name + " isn't a .hmod file. The Rust "
                    + "engine's mods are .hmod files; you can also install them from the Mod catalog tab.");
            return;
        }
        if (busy) return;
        busy = true;
        setStatus("Checking " + name + "...", true);
        worker.execute(() -> {
            File staged = null;
            try {
                File folder = HorizonAddons.folder(gameDir, HorizonAddons.Kind.MOD);
                staged = HorizonAddons.stage(this, uri, folder, HorizonAddons.MAX_PACK_BYTES);
                JSONObject facts = HorizonAddons.inspectWithEngine(this, HorizonAddons.Kind.MOD, staged);
                String id = facts.optString("id", "");
                if (id.isEmpty()) throw new IOException("Its manifest has no id.");
                HorizonAddons.Item incoming = new HorizonAddons.Item(HorizonAddons.Kind.MOD,
                        new File(folder, id + ".hmod"));
                HorizonAddons.applyFacts(incoming, facts);
                HorizonAddons.Item existing = null;
                for (HorizonAddons.Item item : inspectAll(HorizonAddons.listMods(gameDir))) {
                    if (id.equals(item.id) || item.file.getAbsoluteFile().equals(incoming.file.getAbsoluteFile())) {
                        existing = item;
                    }
                }
                File ready = staged;
                staged = null;
                HorizonAddons.Item replaced = existing;
                main.post(() -> {
                    busy = false;
                    setStatus("", false);
                    if (replaced == null) {
                        commitImport(ready, incoming, false, null);
                    } else {
                        AlertDialog dialog = LauncherDialogStyle.builder(this)
                                .setTitle("Replace " + replaced.title() + "?")
                                .setMessage("This instance already has the mod " + id + ". The engine loads a mod "
                                        + "once, so " + incoming.title() + " replaces it.")
                                .setPositiveButton("Replace", (d, which) -> commitImport(ready, incoming, true, replaced))
                                .setNegativeButton(android.R.string.cancel, (d, which) -> worker.execute(ready::delete))
                                .setCancelable(false)
                                .create();
                        showDialog(dialog);
                    }
                });
            } catch (IOException | RuntimeException error) {
                if (staged != null) {
                    //noinspection ResultOfMethodCallIgnored
                    staged.delete();
                }
                String message = error.getMessage();
                main.post(() -> {
                    busy = false;
                    setStatus("", false);
                    showMessage("Not a mod the Rust engine can load", name + " was not imported: "
                            + (message == null ? error.getClass().getSimpleName() : message));
                });
            }
        });
    }

    private void commitImport(
            @NonNull File staged,
            @NonNull HorizonAddons.Item incoming,
            boolean replace,
            @Nullable HorizonAddons.Item replaced
    ) {
        worker.execute(() -> {
            try {
                if (replaced != null && !replaced.file.getAbsoluteFile().equals(incoming.file.getAbsoluteFile())) {
                    HorizonAddons.deleteRecursively(replaced.file);
                }
                HorizonAddons.commit(staged, incoming.file, replace);
                HorizonAddons.log("Horizon add-ons: imported " + incoming.file.getName() + " for " + instanceName);
                main.post(() -> afterInstall(incoming, incoming.title(), null));
            } catch (IOException error) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
                main.post(() -> showMessage("Not imported", String.valueOf(error.getMessage())));
            }
        });
    }

    private void importShader(@NonNull Uri uri) {
        String name = HorizonAddons.safeFileName(HorizonAddons.displayName(this, uri, "shader.zip"));
        if (busy) return;
        busy = true;
        setStatus("Checking " + name + "...", true);
        worker.execute(() -> {
            File staged = null;
            try {
                File folder = HorizonAddons.folder(gameDir, HorizonAddons.Kind.SHADER);
                staged = HorizonAddons.stage(this, uri, folder, HorizonAddons.MAX_PACK_BYTES);
                String lower = name.toLowerCase(Locale.ROOT);
                String fileName;
                HorizonAddons.Item incoming;
                if (lower.endsWith(".hshader") || HorizonAddons.isHorizonShaderZip(staged)) {
                    JSONObject facts = HorizonAddons.inspectWithEngine(this, HorizonAddons.Kind.SHADER, staged);
                    String stem = lower.endsWith(".hshader") ? name.substring(0, name.length() - 8)
                            : lower.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
                    fileName = stem + ".hshader";
                    incoming = new HorizonAddons.Item(HorizonAddons.Kind.SHADER, new File(folder, fileName));
                    HorizonAddons.applyFacts(incoming, facts);
                } else if (HorizonAddons.isClassicShaderPack(staged)) {
                    fileName = lower.endsWith(".zip") ? name : name + ".zip";
                    incoming = new HorizonAddons.Item(HorizonAddons.Kind.SHADER, new File(folder, fileName));
                    incoming.classicShader = true;
                } else {
                    throw new IOException("It has neither a horizon-shaders.toml file nor a shaders/ folder.");
                }
                File ready = staged;
                staged = null;
                main.post(() -> {
                    busy = false;
                    setStatus("", false);
                    if (!incoming.file.exists()) {
                        commitShader(ready, incoming, false);
                        return;
                    }
                    AlertDialog dialog = LauncherDialogStyle.builder(this)
                            .setTitle(incoming.file.getName() + " already exists")
                            .setMessage("Replace the shader pack already there, or keep both?")
                            .setPositiveButton("Replace", (d, which) -> commitShader(ready, incoming, true))
                            .setNeutralButton("Keep both", (d, which) -> {
                                HorizonAddons.Item renamed = new HorizonAddons.Item(HorizonAddons.Kind.SHADER,
                                        HorizonAddons.keepBothName(incoming.file));
                                renamed.classicShader = incoming.classicShader;
                                renamed.name = incoming.name;
                                renamed.version = incoming.version;
                                commitShader(ready, renamed, false);
                            })
                            .setNegativeButton(android.R.string.cancel, (d, which) -> worker.execute(ready::delete))
                            .setCancelable(false)
                            .create();
                    showDialog(dialog);
                });
            } catch (IOException | RuntimeException error) {
                if (staged != null) {
                    //noinspection ResultOfMethodCallIgnored
                    staged.delete();
                }
                String message = error.getMessage();
                main.post(() -> {
                    busy = false;
                    setStatus("", false);
                    showMessage("Not a shader pack the Rust engine can load", name + " was not imported: "
                            + (message == null ? error.getClass().getSimpleName() : message));
                });
            }
        });
    }

    private void commitShader(@NonNull File staged, @NonNull HorizonAddons.Item incoming, boolean replace) {
        worker.execute(() -> {
            try {
                HorizonAddons.commit(staged, incoming.file, replace);
                HorizonAddons.log("Horizon add-ons: imported shader pack " + incoming.file.getName());
                main.post(() -> afterInstall(incoming, incoming.title(), null));
            } catch (IOException error) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
                main.post(() -> showMessage("Not imported", String.valueOf(error.getMessage())));
            }
        });
    }

    // --- Remove ----------------------------------------------------------------------------------

    private void confirmRemove(@NonNull HorizonAddons.Item item) {
        AlertDialog dialog = LauncherDialogStyle.builder(this)
                .setTitle("Remove " + item.title() + "?")
                .setMessage("This deletes " + item.file.getName() + " from this instance. Worlds are not touched.")
                .setPositiveButton("Remove", (d, which) -> worker.execute(() -> {
                    try {
                        HorizonAddons.remove(gameDir, item);
                        HorizonAddons.log("Horizon add-ons: removed " + item.file.getName());
                        main.post(this::refreshTab);
                    } catch (IOException error) {
                        main.post(() -> showMessage("Not removed", String.valueOf(error.getMessage())));
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showDialog(dialog);
    }

    private void refreshTab() {
        showTab(tab);
    }

    // --- Engine checks ---------------------------------------------------------------------------

    /** Each item checked by the game, reusing an earlier check of the same file. Worker thread. */
    @NonNull
    private List<HorizonAddons.Item> inspectAll(@NonNull List<HorizonAddons.Item> items) {
        List<HorizonAddons.Item> out = new ArrayList<>();
        for (HorizonAddons.Item item : items) {
            String key = item.kind + "|" + item.file.getAbsolutePath() + "|" + item.file.length()
                    + "|" + item.file.lastModified();
            HorizonAddons.Item known;
            synchronized (inspected) {
                known = inspected.get(key);
            }
            if (known == null) {
                HorizonAddons.inspect(this, item);
                synchronized (inspected) {
                    inspected.put(key, item);
                }
                known = item;
            }
            out.add(known);
        }
        return out;
    }

    // --- Views -----------------------------------------------------------------------------------

    private void post(int at, @NonNull Runnable action) {
        main.post(() -> {
            if (at != generation || isFinishing() || isDestroyed()) return;
            action.run();
        });
    }

    private void setStatus(@NonNull String text, boolean working) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
    }

    private void section(@NonNull String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), dp(14), dp(2), dp(2));
        list.addView(title, matchWrap());
    }

    @NonNull
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(LauncherDialogStyle.roundedDrawable(this,
                LauncherDialogStyle.COLOR_CARD_BG, LauncherDialogStyle.COLOR_CARD_STROKE, 16));
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(8), 0, 0);
        list.addView(card, params);
        return card;
    }

    private void cardText(@NonNull LinearLayout card, @NonNull String text, int color, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(3), 0, dp(1));
        card.addView(view, matchWrap());
    }

    @NonNull
    private LinearLayout rowTitle(@NonNull LinearLayout card, @NonNull String title, @NonNull String meta) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(name, matchWrap());
        TextView sub = new TextView(this);
        sub.setText(meta);
        sub.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        column.addView(sub, matchWrap());
        card.addView(column, matchWrap());
        return column;
    }

    @NonNull
    private SwitchMaterial rowWithSwitch(@NonNull LinearLayout card, @NonNull String title, @NonNull String meta, boolean on) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, matchWrap());
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(name, matchWrap());
        TextView sub = new TextView(this);
        sub.setText(meta);
        sub.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        column.addView(sub, matchWrap());
        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setChecked(on);
        toggle.setContentDescription(title);
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return toggle;
    }

    @NonNull
    private RadioButton rowWithRadio(@NonNull LinearLayout card, @NonNull String title, @NonNull String meta, boolean on) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, matchWrap());
        RadioButton radio = new RadioButton(this);
        radio.setChecked(on);
        radio.setContentDescription(title);
        row.addView(radio, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(6), 0, 0, 0);
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(name, matchWrap());
        TextView sub = new TextView(this);
        sub.setText(meta);
        sub.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        column.addView(sub, matchWrap());
        row.setOnClickListener(view -> radio.performClick());
        return radio;
    }

    private void rowActions(@NonNull LinearLayout card, @NonNull String label, @NonNull View.OnClickListener listener) {
        rowActions(card, label, listener, null, null);
    }

    private void rowActions(
            @NonNull LinearLayout card,
            @NonNull String label,
            @NonNull View.OnClickListener listener,
            @Nullable String secondLabel,
            @Nullable View.OnClickListener secondListener
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        card.addView(row, params);
        if (secondLabel != null && secondListener != null) {
            TextView second = textButton(secondLabel, false);
            second.setOnClickListener(secondListener);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            secondParams.rightMargin = dp(8);
            row.addView(second, secondParams);
        }
        TextView button = textButton(label, false);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
    }

    @NonNull
    private TextView textButton(@NonNull String text, boolean filled) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setClickable(true);
        button.setFocusable(true);
        paintButton(button, filled);
        return button;
    }

    private void paintButton(@NonNull TextView button, boolean filled) {
        int accent = LauncherDialogStyle.COLOR_ACCENT;
        button.setTextColor(filled ? onAccent() : accent);
        button.setBackground(LauncherDialogStyle.roundedDrawable(this,
                filled ? accent : Color.TRANSPARENT, accent, 16));
    }

    private void styleTab(@NonNull TextView button, boolean selected) {
        paintButton(button, selected);
    }

    /** Readable text on the accent colour: dark on light accents, white on dark ones. */
    private int onAccent() {
        int accent = LauncherDialogStyle.COLOR_ACCENT;
        double luminance = (0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)) / 255.0;
        return luminance > 0.6 ? Color.rgb(16, 20, 24) : Color.WHITE;
    }

    private void showMessage(@NonNull String title, @NonNull String message) {
        AlertDialog dialog = LauncherDialogStyle.builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        showDialog(dialog);
    }

    private void showDialog(@NonNull AlertDialog dialog) {
        if (isFinishing() || isDestroyed()) return;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
    }

    private void copy(@NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Mod link", text));
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
    }

    private void openLink(@NonNull String url) {
        if (!url.startsWith("https://")) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            copy(url);
        }
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
