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

package ca.dnamobile.droidbridgelauncher.ui.instance;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.launcher.DistantHorizonsGcMitigation;
import ca.dnamobile.droidbridgelauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.droidbridgelauncher.launcher.GraphicsBackendHelper;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.renderer.Driver;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.RendererCompatibility;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;
import ca.dnamobile.droidbridgelauncher.shortcuts.InstanceShortcutHelper;

/**
 * CreateInstanceDialog-style editor for per-instance launch overrides.
 *
 * The action buttons live inside the custom Material card rather than using the
 * default AlertDialog button bar. That keeps Save/Cancel/Reset visible in
 * landscape and prevents showFullscreenSafeDialog-style OnShowListener
 * replacement from breaking the button callbacks.
 */
public final class PerInstanceSettingsDialog {

    public static final int REQUEST_PICK_VULKAN_DRIVER = 9130;

    private final Activity activity;
    private final String settingsKey;
    private final ArrayList<String> aliasKeys;
    @Nullable
    private final InstanceShortcutHelper.ShortcutData shortcutData;
    @Nullable
    private final Runnable onDismiss;

    private AlertDialog dialog;

    private final ArrayList<RendererInterface> renderers = new ArrayList<>();
    private final ArrayList<String> rendererLabels = new ArrayList<>();
    private final ArrayList<Driver> vulkanDrivers = new ArrayList<>();
    private final ArrayList<String> vulkanDriverLabels = new ArrayList<>();
    private int selectedRendererIndex;
    private int selectedVulkanDriverIndex;
    private int selectedRuntimeIndex;
    private int selectedGraphicsApiModeIndex;
    private boolean graphicsApiSystemVulkanLocked;
    private int selectedVulkanCompatibilityModeIndex;
    private int selectedResolutionModeIndex;
    private int selectedLaunchTargetModeIndex;

    private MaterialAutoCompleteTextView rendererDropdown;
    private TextView rendererCompatibilitySummary;
    private MaterialAutoCompleteTextView vulkanDriverDropdown;
    private LinearLayout vulkanDriverSection;
    private MaterialAutoCompleteTextView runtimeDropdown;
    private MaterialAutoCompleteTextView graphicsApiDropdown;
    private MaterialAutoCompleteTextView vulkanCompatibilityDropdown;
    private MaterialAutoCompleteTextView resolutionDropdown;
    private MaterialAutoCompleteTextView launchTargetDropdown;
    private TextInputEditText resolutionWidthInput;
    private TextInputEditText resolutionHeightInput;
    private TextInputLayout resolutionWidthLayout;
    private TextInputLayout resolutionHeightLayout;
    private TextInputEditText jvmArgsInput;
    private SwitchMaterial distantHorizonsZgcSwitch;
    private SwitchMaterial customRamSwitch;
    private TextInputEditText ramInput;
    private TextInputLayout ramInputLayout;
    private MaterialButton ramUnlockButton;
    private Slider ramSlider;
    private TextView ramSummary;
    private TextView ramRangeText;

    private int minRamMb;
    private int maxRamMb;
    private int sliderMaxRamMb;
    private int ramStepMb;
    private int selectedRamMb;
    private boolean updatingRamText;

    public PerInstanceSettingsDialog(
            @NonNull Activity activity,
            @NonNull String settingsKey,
            @Nullable List<String> aliasKeys,
            @Nullable Runnable onDismiss
    ) {
        this(activity, settingsKey, aliasKeys, null, onDismiss);
    }

    public PerInstanceSettingsDialog(
            @NonNull Activity activity,
            @NonNull String settingsKey,
            @Nullable List<String> aliasKeys,
            @Nullable InstanceShortcutHelper.ShortcutData shortcutData,
            @Nullable Runnable onDismiss
    ) {
        this.activity = activity;
        this.settingsKey = settingsKey;
        this.aliasKeys = new ArrayList<>();
        this.shortcutData = shortcutData;
        addAlias(settingsKey);
        if (aliasKeys != null) {
            for (String key : aliasKeys) addAlias(key);
        }
        this.onDismiss = onDismiss;
    }

    public void show() {
        LauncherDialogStyle.syncTheme(activity);
        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(activity, settingsKey);
        prepareRendererChoices(settings);
        prepareVulkanDriverChoices(settings);
        prepareRamBounds(settings);

        FrameLayout outer = new FrameLayout(activity);
        outer.setPadding(dp(4), dp(4), dp(4), dp(4));

        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(26));
        card.setCardBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        card.setStrokeColor(LauncherDialogStyle.COLOR_CARD_STROKE);
        card.setStrokeWidth(dp(1));
        card.setCardElevation(0f);
        card.setUseCompatPadding(false);
        card.setPreventCornerOverlap(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(14));
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        content.addView(createHeader(), matchWrap());

        ScrollView formScroll = new ScrollView(activity);
        formScroll.setFillViewport(false);
        formScroll.setClipToPadding(false);
        formScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        formScroll.setPadding(0, 0, 0, dp(8));

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, 0, 0, dp(8));
        formScroll.addView(form, matchWrap());

        form.addView(createLaunchTargetSection(settings), matchWrap());
        form.addView(createRendererSection(settings), matchWrap());
        form.addView(createVulkanDriverSection(settings), matchWrap());
        form.addView(createGraphicsApiSection(settings), matchWrap());
        form.addView(createVulkanCompatibilitySection(settings), matchWrap());
        form.addView(createResolutionSection(settings), matchWrap());
        form.addView(createRuntimeSection(settings), matchWrap());
        form.addView(createDistantHorizonsGcSection(settings), matchWrap());
        form.addView(createJvmArgsSection(settings), matchWrap());
        form.addView(createRamSection(settings), matchWrap());
        if (shortcutData != null) {
            form.addView(createShortcutSection(), matchWrap());
        }

        // Critical: the form takes remaining space only. The action row stays outside
        // the ScrollView, so Save/Cancel/Reset can never be pushed below the screen.
        content.addView(formScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        content.addView(createActionsSection(), matchWrap());

        outer.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        dialog = LauncherDialogStyle.builder(activity)
                .setView(outer)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
                int targetWidth = Math.min(screenWidth - dp(24), dp(760));
                int availableHeight = screenHeight - dp(24);
                if (availableHeight < dp(260)) {
                    availableHeight = screenHeight - dp(8);
                }
                availableHeight = Math.max(1, availableHeight);
                int targetHeight = Math.min(availableHeight, dp(640));
                window.setLayout(targetWidth, targetHeight);
            }
        });
        dialog.setOnDismissListener(dialogInterface -> {
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();
    }

    private View createHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(16));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setCornerRadius(dp(18));
        iconBackground.setColor(LauncherDialogStyle.COLOR_CARD_BG);
        icon.setBackground(iconBackground);
        row.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(16), 0, 0, 0);

        TextView title = new TextView(activity);
        title.setText(R.string.per_instance_settings_title);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        textColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.per_instance_settings_summary);
        subtitle.setTextSize(13);
        subtitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        subtitle.setPadding(0, dp(4), 0, 0);
        textColumn.addView(subtitle, matchWrap());

        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View createLaunchTargetSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                activity.getString(R.string.per_instance_grid_play_title),
                activity.getString(R.string.per_instance_grid_play_summary)
        );

        String[] labels = InstanceLaunchSettings.getLaunchTargetModeLabels();
        launchTargetDropdown = new MaterialAutoCompleteTextView(activity);
        launchTargetDropdown.setInputType(InputType.TYPE_NULL);
        launchTargetDropdown.setSingleLine(true);
        launchTargetDropdown.setOnClickListener(view -> launchTargetDropdown.showDropDown());
        launchTargetDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        selectedLaunchTargetModeIndex = InstanceLaunchSettings.launchTargetModeIndex(settings.launchTargetMode);
        if (selectedLaunchTargetModeIndex < 0 || selectedLaunchTargetModeIndex >= labels.length) {
            selectedLaunchTargetModeIndex = 0;
        }
        launchTargetDropdown.setText(labels[selectedLaunchTargetModeIndex], false);
        launchTargetDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedLaunchTargetModeIndex = Math.max(0, Math.min(position, labels.length - 1));
            launchTargetDropdown.setText(labels[selectedLaunchTargetModeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout(activity.getString(R.string.per_instance_grid_play_title));
        layout.addView(launchTargetDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createRendererSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, activity.getString(R.string.per_instance_renderer_title), activity.getString(R.string.per_instance_renderer_summary));

        rendererDropdown = new MaterialAutoCompleteTextView(activity);
        rendererDropdown.setInputType(InputType.TYPE_NULL);
        rendererDropdown.setSingleLine(true);
        rendererDropdown.setOnClickListener(view -> rendererDropdown.showDropDown());
        rendererDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, rendererLabels));
        selectedRendererIndex = resolveRendererSelectionIndex(settings.rendererIdentifier);
        rendererDropdown.setText(rendererLabels.get(selectedRendererIndex), false);
        rendererDropdown.setOnItemClickListener((parent, view, position, id) -> {
            // AutoCompleteTextView positions belong to the currently filtered adapter.
            // Resolve the actual clicked label back to the stable full renderer list so
            // selecting MobileGlues/Krypton/Default cannot silently save another item.
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            int resolvedIndex = rendererLabels.indexOf(clickedLabel);
            selectedRendererIndex = resolvedIndex >= 0 ? resolvedIndex : 0;
            rendererDropdown.setText(rendererLabels.get(selectedRendererIndex), false);
            updateRendererCompatibilitySummary();
            updateVulkanDriverSectionVisibility();
        });

        TextInputLayout layout = createDropdownLayout(activity.getString(R.string.per_instance_renderer_title));
        layout.addView(rendererDropdown, matchWrap());
        section.addView(layout, matchWrap());

        rendererCompatibilitySummary = new TextView(activity);
        rendererCompatibilitySummary.setTextSize(12);
        rendererCompatibilitySummary.setTypeface(rendererCompatibilitySummary.getTypeface(), Typeface.BOLD);
        rendererCompatibilitySummary.setPadding(0, dp(4), 0, 0);
        section.addView(rendererCompatibilitySummary, matchWrap());
        updateRendererCompatibilitySummary();
        return section;
    }

    private void updateRendererCompatibilitySummary() {
        if (rendererCompatibilitySummary == null) return;
        RendererInterface selected = null;
        if (selectedRendererIndex > 0 && selectedRendererIndex - 1 < renderers.size()) {
            selected = renderers.get(selectedRendererIndex - 1);
        } else {
            try {
                selected = Renderers.getSelectedRenderer(activity);
            } catch (Throwable ignored) {
            }
        }
        String summary = RendererCompatibility.getSummary(activity, selected);
        if (summary == null || summary.trim().isEmpty()) {
            rendererCompatibilitySummary.setText("");
            rendererCompatibilitySummary.setVisibility(View.GONE);
        } else {
            rendererCompatibilitySummary.setText(summary);
            rendererCompatibilitySummary.setVisibility(View.VISIBLE);
        }
    }

    private View createVulkanDriverSection(@NonNull InstanceLaunchSettings.Settings settings) {
        vulkanDriverSection = createSection();
        addSectionTitle(
                vulkanDriverSection,
                activity.getString(R.string.per_instance_vulkan_driver_title),
                activity.getString(R.string.per_instance_vulkan_driver_summary)
        );

        vulkanDriverDropdown = new MaterialAutoCompleteTextView(activity);
        vulkanDriverDropdown.setInputType(InputType.TYPE_NULL);
        vulkanDriverDropdown.setSingleLine(true);
        vulkanDriverDropdown.setOnClickListener(view -> vulkanDriverDropdown.showDropDown());
        refreshVulkanDriverDropdown(settings.vulkanDriverName);
        vulkanDriverDropdown.setOnItemClickListener((parent, view, position, id) -> {
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            int resolvedIndex = vulkanDriverLabels.indexOf(clickedLabel);
            selectedVulkanDriverIndex = resolvedIndex >= 0 ? resolvedIndex : 0;
            if (selectedVulkanDriverIndex < vulkanDriverLabels.size()) {
                vulkanDriverDropdown.setText(
                        vulkanDriverLabels.get(selectedVulkanDriverIndex), false);
            }
        });

        TextInputLayout layout = createDropdownLayout(activity.getString(R.string.per_instance_vulkan_driver_hint));
        layout.addView(vulkanDriverDropdown, matchWrap());
        vulkanDriverSection.addView(layout, matchWrap());

        MaterialButton importButton = new MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        importButton.setText(R.string.per_instance_import_vulkan_driver);
        importButton.setAllCaps(false);
        importButton.setOnClickListener(view -> openVulkanDriverPackagePicker());
        LinearLayout.LayoutParams importParams = matchWrap();
        importParams.setMargins(0, dp(8), 0, 0);
        vulkanDriverSection.addView(importButton, importParams);

        updateVulkanDriverSectionVisibility();
        return vulkanDriverSection;
    }

    private View createGraphicsApiSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                activity.getString(R.string.per_instance_graphics_api_title),
                activity.getString(R.string.per_instance_graphics_api_summary)
        );

        String[] graphicsApiLabels = InstanceLaunchSettings.getGraphicsApiModeLabels();
        graphicsApiDropdown = new MaterialAutoCompleteTextView(activity);
        graphicsApiDropdown.setInputType(InputType.TYPE_NULL);
        graphicsApiDropdown.setSingleLine(true);
        graphicsApiDropdown.setOnClickListener(view -> graphicsApiDropdown.showDropDown());
        graphicsApiDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, graphicsApiLabels));
        graphicsApiSystemVulkanLocked = isSystemVulkanLockedForThisInstance();
        selectedGraphicsApiModeIndex = graphicsApiSystemVulkanLocked
                ? InstanceLaunchSettings.graphicsApiModeIndex(InstanceLaunchSettings.GRAPHICS_API_VULKAN)
                : InstanceLaunchSettings.graphicsApiModeIndex(settings.graphicsApiMode);
        if (selectedGraphicsApiModeIndex < 0 || selectedGraphicsApiModeIndex >= graphicsApiLabels.length) {
            selectedGraphicsApiModeIndex = 0;
        }
        graphicsApiDropdown.setText(graphicsApiLabels[selectedGraphicsApiModeIndex], false);
        if (graphicsApiSystemVulkanLocked) {
            graphicsApiDropdown.setEnabled(false);
            graphicsApiDropdown.setAlpha(0.72f);
        }
        graphicsApiDropdown.setOnItemClickListener((parent, view, position, id) -> {
            // Do not use the filtered adapter position as the Graphics API index.
            // After a previous System Vulkan selection that can turn a click on
            // "Default" into index 0 ("Use launcher default"), resurrecting stale
            // global Vulkan state. Resolve the clicked label to its stable mode first.
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            String clickedMode = InstanceLaunchSettings.graphicsApiModeForLabel(clickedLabel);
            selectedGraphicsApiModeIndex = InstanceLaunchSettings.graphicsApiModeIndex(clickedMode);
            if (selectedGraphicsApiModeIndex < 0
                    || selectedGraphicsApiModeIndex >= graphicsApiLabels.length) {
                selectedGraphicsApiModeIndex = 0;
            }
            graphicsApiDropdown.setText(graphicsApiLabels[selectedGraphicsApiModeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout(activity.getString(R.string.per_instance_graphics_api_hint));
        layout.addView(graphicsApiDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createVulkanCompatibilitySection(
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        LinearLayout section = createSection();
        addSectionTitle(section, activity.getString(R.string.per_instance_system_vulkan_title), "");

        String[] labels = InstanceLaunchSettings.getVulkanCompatibilityModeLabels();
        vulkanCompatibilityDropdown = new MaterialAutoCompleteTextView(activity);
        vulkanCompatibilityDropdown.setInputType(InputType.TYPE_NULL);
        vulkanCompatibilityDropdown.setSingleLine(true);
        vulkanCompatibilityDropdown.setOnClickListener(
                view -> vulkanCompatibilityDropdown.showDropDown());
        vulkanCompatibilityDropdown.setAdapter(new ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, labels));
        selectedVulkanCompatibilityModeIndex =
                InstanceLaunchSettings.vulkanCompatibilityModeIndex(
                        settings.vulkanCompatibilityMode);
        if (selectedVulkanCompatibilityModeIndex < 0
                || selectedVulkanCompatibilityModeIndex >= labels.length) {
            selectedVulkanCompatibilityModeIndex = 0;
        }
        vulkanCompatibilityDropdown.setText(
                labels[selectedVulkanCompatibilityModeIndex], false);
        vulkanCompatibilityDropdown.setOnItemClickListener((parent, view, position, id) -> {
            // AutoCompleteTextView positions are positions in the *filtered* adapter.
            // Resolve the clicked label back to the stable full mode list instead of
            // treating the filtered position as a compatibility-mode index.
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            String clickedMode = InstanceLaunchSettings.vulkanCompatibilityModeForLabel(clickedLabel);
            selectedVulkanCompatibilityModeIndex =
                    InstanceLaunchSettings.vulkanCompatibilityModeIndex(clickedMode);
            if (selectedVulkanCompatibilityModeIndex < 0
                    || selectedVulkanCompatibilityModeIndex >= labels.length) {
                selectedVulkanCompatibilityModeIndex = 0;
            }
            vulkanCompatibilityDropdown.setText(
                    labels[selectedVulkanCompatibilityModeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout(activity.getString(R.string.per_instance_vulkan_compat_title));
        layout.addView(vulkanCompatibilityDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createResolutionSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                activity.getString(R.string.per_instance_resolution_experimental_title),
                activity.getString(R.string.per_instance_resolution_experimental_summary)
        );

        String[] labels = InstanceLaunchSettings.getResolutionModeLabels();
        resolutionDropdown = new MaterialAutoCompleteTextView(activity);
        resolutionDropdown.setInputType(InputType.TYPE_NULL);
        resolutionDropdown.setSingleLine(true);
        resolutionDropdown.setOnClickListener(view -> resolutionDropdown.showDropDown());
        resolutionDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        selectedResolutionModeIndex = InstanceLaunchSettings.resolutionModeIndex(settings.resolutionMode);
        if (selectedResolutionModeIndex < 0 || selectedResolutionModeIndex >= labels.length) {
            selectedResolutionModeIndex = 0;
        }
        resolutionDropdown.setText(labels[selectedResolutionModeIndex], false);
        resolutionDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedResolutionModeIndex = Math.max(0, Math.min(position, labels.length - 1));
            resolutionDropdown.setText(labels[selectedResolutionModeIndex], false);
            updateResolutionCustomInputVisibility();
        });

        TextInputLayout dropdownLayout = createDropdownLayout(activity.getString(R.string.per_instance_resolution_title));
        dropdownLayout.addView(resolutionDropdown, matchWrap());
        section.addView(dropdownLayout, matchWrap());

        GameResolutionSettings.Profile globalProfile = GameResolutionSettings.getProfile(activity);
        int initialWidth = settings.hasResolutionOverride()
                ? settings.resolutionCustomWidth
                : globalProfile.customWidth;
        int initialHeight = settings.hasResolutionOverride()
                ? settings.resolutionCustomHeight
                : globalProfile.customHeight;

        resolutionWidthInput = new TextInputEditText(activity);
        resolutionWidthInput.setSingleLine(true);
        resolutionWidthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        resolutionWidthInput.setSelectAllOnFocus(true);
        resolutionWidthInput.setText(String.valueOf(initialWidth));
        resolutionWidthLayout = createOutlinedLayout(activity.getString(R.string.per_instance_custom_width));
        resolutionWidthLayout.addView(resolutionWidthInput, matchWrap());
        LinearLayout.LayoutParams widthParams = matchWrap();
        widthParams.setMargins(0, dp(8), 0, 0);
        section.addView(resolutionWidthLayout, widthParams);

        resolutionHeightInput = new TextInputEditText(activity);
        resolutionHeightInput.setSingleLine(true);
        resolutionHeightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        resolutionHeightInput.setSelectAllOnFocus(true);
        resolutionHeightInput.setText(String.valueOf(initialHeight));
        resolutionHeightLayout = createOutlinedLayout(activity.getString(R.string.per_instance_custom_height));
        resolutionHeightLayout.addView(resolutionHeightInput, matchWrap());
        LinearLayout.LayoutParams heightParams = matchWrap();
        heightParams.setMargins(0, dp(8), 0, 0);
        section.addView(resolutionHeightLayout, heightParams);

        TextView restartNotice = new TextView(activity);
        restartNotice.setText(R.string.per_instance_resolution_summary);
        restartNotice.setTextSize(12);
        restartNotice.setPadding(0, dp(6), 0, 0);
        section.addView(restartNotice, matchWrap());

        updateResolutionCustomInputVisibility();
        return section;
    }

    private View createRuntimeSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, activity.getString(R.string.per_instance_java_runtime_title), activity.getString(R.string.per_instance_java_runtime_summary));

        String[] runtimeLabels = InstanceLaunchSettings.getRuntimeDisplayLabels();
        runtimeDropdown = new MaterialAutoCompleteTextView(activity);
        runtimeDropdown.setInputType(InputType.TYPE_NULL);
        runtimeDropdown.setSingleLine(true);
        runtimeDropdown.setOnClickListener(view -> runtimeDropdown.showDropDown());
        runtimeDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, runtimeLabels));
        selectedRuntimeIndex = InstanceLaunchSettings.runtimeIndexForName(settings.runtimeName);
        if (selectedRuntimeIndex < 0 || selectedRuntimeIndex >= runtimeLabels.length) selectedRuntimeIndex = 0;
        runtimeDropdown.setText(runtimeLabels[selectedRuntimeIndex], false);
        runtimeDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedRuntimeIndex = Math.max(0, Math.min(position, runtimeLabels.length - 1));
            runtimeDropdown.setText(runtimeLabels[selectedRuntimeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout(activity.getString(R.string.per_instance_java_runtime_title));
        layout.addView(runtimeDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createDistantHorizonsGcSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        DistantHorizonsGcMitigation.DeviceRecommendation recommendation =
                DistantHorizonsGcMitigation.getDeviceRecommendation(activity);

        addSectionTitle(
                section,
                activity.getString(R.string.per_instance_dh_gc_title),
                activity.getString(R.string.per_instance_dh_gc_summary)
        );

        String savedMode = InstanceLaunchSettings.sanitizeGcMode(settings.gcMode);
        String initialMode = settings.hasGcOverride()
                ? savedMode
                : recommendation.recommendedGcMode;

        distantHorizonsZgcSwitch = new SwitchMaterial(activity);
        distantHorizonsZgcSwitch.setText(R.string.per_instance_use_zgc);
        distantHorizonsZgcSwitch.setChecked(InstanceLaunchSettings.GC_MODE_ZGC.equals(initialMode));
        section.addView(distantHorizonsZgcSwitch, matchWrap());

        TextView recommendationText = new TextView(activity);
        recommendationText.setText(activity.getString(
                R.string.per_instance_gc_recommendation,
                DistantHorizonsGcMitigation.displayGcMode(recommendation.recommendedGcMode),
                recommendation.summary,
                activity.getString(settings.hasGcOverride()
                        ? R.string.per_instance_gc_saved_override
                        : R.string.per_instance_gc_pending_recommendation)
        ));
        recommendationText.setTextSize(12);
        recommendationText.setPadding(0, dp(4), 0, 0);
        section.addView(recommendationText, matchWrap());
        return section;
    }

    private View createJvmArgsSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, activity.getString(R.string.per_instance_jvm_args_title), activity.getString(R.string.per_instance_jvm_args_summary));

        jvmArgsInput = new TextInputEditText(activity);
        jvmArgsInput.setSingleLine(false);
        jvmArgsInput.setMinLines(2);
        jvmArgsInput.setMaxLines(4);
        jvmArgsInput.setGravity(Gravity.TOP | Gravity.START);
        jvmArgsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jvmArgsInput.setHint(R.string.per_instance_jvm_hint);
        jvmArgsInput.setText(settings.customJvmArgs == null ? "" : settings.customJvmArgs);

        TextInputLayout layout = createOutlinedLayout(activity.getString(R.string.per_instance_jvm_args_title));
        layout.addView(jvmArgsInput, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createRamSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, activity.getString(R.string.per_instance_ram_title), activity.getString(R.string.per_instance_ram_summary));

        customRamSwitch = new SwitchMaterial(activity);
        customRamSwitch.setText(R.string.per_instance_custom_ram_toggle);
        customRamSwitch.setChecked(settings.hasRamOverride());
        section.addView(customRamSwitch, matchWrap());

        ramUnlockButton = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        ramUnlockButton.setAllCaps(false);
        ramUnlockButton.setIconResource(R.drawable.ic_lock_24);
        ramUnlockButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        ramUnlockButton.setIconPadding(dp(8));
        ramUnlockButton.setOnClickListener(view -> showRamUnlockDialog());
        LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        unlockParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramUnlockButton, unlockParams);

        ramInput = new TextInputEditText(activity);
        ramInput.setSingleLine(true);
        ramInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        ramInput.setSelectAllOnFocus(true);
        ramInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) applyTypedRamValue();
        });
        ramInput.setOnEditorActionListener((view, actionId, event) -> {
            applyTypedRamValue();
            return false;
        });

        ramInputLayout = createOutlinedLayout(activity.getString(R.string.per_instance_ram_mb));
        ramInputLayout.addView(ramInput, matchWrap());
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramInputLayout, inputParams);

        ramSlider = new Slider(activity);
        ramSlider.setValueFrom(minRamMb);
        ramSlider.setValueTo(sliderMaxRamMb);
        ramSlider.setStepSize(ramStepMb);
        ramSlider.setValue(clampRamToSliderRange(selectedRamMb));
        ramSlider.setLabelFormatter(value -> Math.round(value) + " MB");
        ramSlider.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        ramSlider.addOnChangeListener((slider, value, fromUser) -> {
            selectedRamMb = Math.round(value);
            updateRamViews();
        });
        LinearLayout.LayoutParams sliderParams = matchWrap();
        sliderParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramSlider, sliderParams);

        ramSummary = new TextView(activity);
        ramSummary.setTextSize(13);
        ramSummary.setPadding(0, dp(4), 0, 0);
        section.addView(ramSummary, matchWrap());

        ramRangeText = new TextView(activity);
        ramRangeText.setTextSize(12);
        ramRangeText.setPadding(0, dp(4), 0, 0);
        section.addView(ramRangeText, matchWrap());

        customRamSwitch.setOnCheckedChangeListener((buttonView, checked) -> updateRamViews());
        updateRamUnlockButton();
        updateRamViews();
        return section;
    }

    private View createShortcutSection() {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                activity.getString(R.string.per_instance_shortcut_title),
                activity.getString(R.string.per_instance_shortcut_summary)
        );

        MaterialButton shortcutButton = new MaterialButton(activity);
        shortcutButton.setText(R.string.per_instance_add_shortcut);
        shortcutButton.setAllCaps(false);
        shortcutButton.setIconResource(R.drawable.ic_open_in_new_24);
        shortcutButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        shortcutButton.setIconPadding(dp(8));
        shortcutButton.setOnClickListener(view -> {
            if (shortcutData == null) {
                Toast.makeText(activity, R.string.per_instance_shortcut_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            InstanceShortcutHelper.requestPinShortcut(activity, shortcutData);
        });

        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(4), 0, 0);
        section.addView(shortcutButton, buttonParams);
        return section;
    }

    private View createActionsSection() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, dp(12), 0, 0);

        MaterialButton reset = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        reset.setText(R.string.per_instance_reset);
        reset.setAllCaps(false);
        reset.setOnClickListener(view -> {
            clearAllAliases();
            Toast.makeText(activity, R.string.per_instance_reset_done, Toast.LENGTH_SHORT).show();
            dismiss();
        });
        row.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton cancel = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setAllCaps(false);
        cancel.setOnClickListener(view -> dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(dp(8), 0, 0, 0);
        row.addView(cancel, cancelParams);

        MaterialButton save = new MaterialButton(activity);
        save.setText(R.string.per_instance_save);
        save.setAllCaps(false);
        save.setOnClickListener(view -> saveAndDismiss());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.setMargins(dp(8), 0, 0, 0);
        row.addView(save, saveParams);

        return row;
    }

    private void updateRamUnlockButton() {
        if (ramUnlockButton == null) return;
        ramUnlockButton.setText(MemoryAllocationUtils.isRamUnlocked(activity)
                ? activity.getString(R.string.memory_unlock_button_unlocked)
                : activity.getString(R.string.memory_unlock_button_locked));
    }

    private void showRamUnlockDialog() {
        LauncherDialogStyle.syncTheme(activity);
        boolean unlocked = MemoryAllocationUtils.isRamUnlocked(activity);
        int titleRes = unlocked
                ? R.string.memory_relock_dialog_title
                : R.string.memory_unlock_dialog_title;
        int messageRes = unlocked
                ? R.string.memory_relock_dialog_message
                : R.string.memory_unlock_dialog_message;
        int positiveRes = unlocked
                ? R.string.memory_relock_dialog_positive
                : R.string.memory_unlock_dialog_positive;

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, dp(8));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(activity);
        title.setText(activity.getString(titleRes));
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, matchWrap());

        TextView summary = new TextView(activity);
        summary.setText(activity.getString(messageRes));
        summary.setTextSize(14f);
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setPadding(dp(2), 0, dp(2), dp(12));
        root.addView(summary, matchWrap());

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG, LauncherDialogStyle.COLOR_CARD_STROKE, 18));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, 0, 0, dp(12));
        root.addView(card, cardParams);

        TextView cardTitle = new TextView(activity);
        cardTitle.setText(unlocked ? R.string.ram_available_limit : R.string.ram_maximum_access);
        cardTitle.setTextSize(18f);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        cardTitle.setPadding(0, 0, 0, dp(8));
        card.addView(cardTitle, matchWrap());

        TextView cardInfo = new TextView(activity);
        cardInfo.setText(unlocked
                ? R.string.ram_available_limit_summary
                : R.string.ram_maximum_access_summary);
        cardInfo.setTextSize(13f);
        cardInfo.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        cardInfo.setPadding(0, 0, 0, dp(8));
        card.addView(cardInfo, matchWrap());

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        MaterialButton cancel = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setAllCaps(false);
        cancel.setTextColor(LauncherDialogStyle.COLOR_ACCENT);
        cancel.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG, LauncherDialogStyle.COLOR_ACCENT_MUTED, 14));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton confirm = new MaterialButton(activity);
        confirm.setText(positiveRes);
        confirm.setAllCaps(false);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(confirm, confirmParams);
        card.addView(actions, matchWrap());

        AlertDialog ramDialog = LauncherDialogStyle.builder(activity)
                .setView(scrollView)
                .create();

        cancel.setOnClickListener(view -> ramDialog.dismiss());
        confirm.setOnClickListener(view -> {
            MemoryAllocationUtils.setRamUnlocked(activity, !unlocked);
            MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
            refreshRamBoundsFromUnlockState();
            updateRamUnlockButton();
            updateRamViews();
            ramDialog.dismiss();
        });

        ramDialog.setOnShowListener(dialogInterface -> {
            Window window = ramDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(roundedDialogDrawable(LauncherDialogStyle.COLOR_DIALOG_BG, LauncherDialogStyle.COLOR_DIALOG_BG, 22));
                window.setDimAmount(LauncherDialogStyle.DIALOG_DIM_NORMAL);
            }
        });
        ramDialog.show();
    }

    private void refreshRamBoundsFromUnlockState() {
        maxRamMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(activity);
        minRamMb = MemoryAllocationUtils.getMinimumMemoryMb(maxRamMb);
        ramStepMb = Math.max(1, MemoryAllocationUtils.RAM_STEP_MB);
        int stepCount = Math.max(1, (maxRamMb - minRamMb) / ramStepMb);
        sliderMaxRamMb = minRamMb + (stepCount * ramStepMb);
        selectedRamMb = clampRamToSliderRange(selectedRamMb);

        if (ramSlider != null) {
            ramSlider.setValueFrom(minRamMb);
            ramSlider.setValueTo(sliderMaxRamMb);
            ramSlider.setStepSize(ramStepMb);
            ramSlider.setValue(clampRamToSliderRange(selectedRamMb));
        }
    }

    private boolean isSystemVulkanLockedForThisInstance() {
        if (!LauncherPreferences.isUseSystemVulkanDriver(activity)) return false;

        LauncherInstance target = LauncherInstanceManager.findByNameOrId(activity, settingsKey);
        if (target != null) {
            if (GraphicsBackendHelper.isMinecraft262OrNewerVersionId(
                    target.getMinecraftVersionId())) {
                return true;
            }
            return GraphicsBackendHelper.isMinecraft262OrNewerVersionId(
                    target.getBaseVersionId());
        }
        return GraphicsBackendHelper.isMinecraft262OrNewerVersionId(settingsKey);
    }

    private void prepareRendererChoices(@NonNull InstanceLaunchSettings.Settings settings) {
        Renderers.reload(activity);
        renderers.clear();
        renderers.addAll(Renderers.getCompatibleRenderers(activity));
        rendererLabels.clear();
        rendererLabels.add(activity.getString(R.string.renderer_default_launcher));
        for (RendererInterface renderer : renderers) {
            rendererLabels.add(renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  " + activity.getString(R.string.renderer_plugin_suffix) : ""));
        }
        if (rendererLabels.isEmpty()) rendererLabels.add(activity.getString(R.string.renderer_default_launcher));
    }

    private void prepareVulkanDriverChoices(@NonNull InstanceLaunchSettings.Settings settings) {
        DriverPluginManager.reload(activity);
        vulkanDrivers.clear();
        vulkanDrivers.addAll(DriverPluginManager.getDrivers(activity));
        vulkanDriverLabels.clear();
        vulkanDriverLabels.add(activity.getString(R.string.vulkan_driver_use_launcher));
        for (Driver driver : vulkanDrivers) {
            vulkanDriverLabels.add(driver.getName());
        }
        selectedVulkanDriverIndex = resolveVulkanDriverSelectionIndex(settings.vulkanDriverName);
    }

    private int resolveVulkanDriverSelectionIndex(@Nullable String driverName) {
        if (driverName == null || driverName.trim().isEmpty()) return 0;
        for (int i = 0; i < vulkanDrivers.size(); i++) {
            if (driverName.trim().equals(vulkanDrivers.get(i).getName())) {
                return i + 1;
            }
        }
        return 0;
    }

    private void refreshVulkanDriverDropdown(@Nullable String preferredDriverName) {
        if (vulkanDriverDropdown == null) return;
        selectedVulkanDriverIndex = resolveVulkanDriverSelectionIndex(preferredDriverName);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_list_item_1,
                vulkanDriverLabels
        );
        vulkanDriverDropdown.setAdapter(adapter);
        if (vulkanDriverLabels.isEmpty()) {
            vulkanDriverDropdown.setText(activity.getString(R.string.vulkan_driver_use_launcher), false);
            selectedVulkanDriverIndex = 0;
            return;
        }
        selectedVulkanDriverIndex = Math.max(
                0,
                Math.min(selectedVulkanDriverIndex, vulkanDriverLabels.size() - 1)
        );
        vulkanDriverDropdown.setText(vulkanDriverLabels.get(selectedVulkanDriverIndex), false);
    }

    private boolean isVulkanZinkSelected() {
        RendererInterface selected = null;
        if (selectedRendererIndex > 0 && selectedRendererIndex - 1 < renderers.size()) {
            selected = renderers.get(selectedRendererIndex - 1);
        } else {
            try {
                selected = Renderers.getSelectedRenderer(activity);
            } catch (Throwable ignored) {
            }
        }
        return DriverPluginManager.isVulkanZinkRenderer(selected);
    }

    private void updateVulkanDriverSectionVisibility() {
        if (vulkanDriverSection == null) return;
        vulkanDriverSection.setVisibility(isVulkanZinkSelected() ? View.VISIBLE : View.GONE);
    }

    @SuppressWarnings("deprecation")
    private void openVulkanDriverPackagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_PICK_VULKAN_DRIVER);
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(activity, R.string.per_instance_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    public void onVulkanDriverPackagePicked(@NonNull Uri uri) {
        Toast.makeText(activity, R.string.per_instance_importing_vulkan, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Driver imported = DriverPluginManager.importDriverPackage(activity, uri);
                activity.runOnUiThread(() -> {
                    if (dialog == null || !dialog.isShowing()) return;
                    DriverPluginManager.reload(activity);
                    vulkanDrivers.clear();
                    vulkanDrivers.addAll(DriverPluginManager.getDrivers(activity));
                    vulkanDriverLabels.clear();
                    vulkanDriverLabels.add(activity.getString(R.string.vulkan_driver_use_launcher));
                    for (Driver driver : vulkanDrivers) {
                        vulkanDriverLabels.add(driver.getName());
                    }
                    refreshVulkanDriverDropdown(imported.getName());
                    Toast.makeText(
                            activity,
                            activity.getString(R.string.per_instance_vulkan_imported, imported.getName()),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = throwable.getClass().getSimpleName();
                }
                final String finalMessage = message;
                activity.runOnUiThread(() -> Toast.makeText(
                        activity,
                        activity.getString(R.string.per_instance_vulkan_import_failed, finalMessage),
                        Toast.LENGTH_LONG
                ).show());
            }
        }, "DroidBridge-InstanceVulkanDriverImport").start();
    }

    private void prepareRamBounds(@NonNull InstanceLaunchSettings.Settings settings) {
        maxRamMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(activity);
        minRamMb = MemoryAllocationUtils.getMinimumMemoryMb(maxRamMb);
        ramStepMb = Math.max(1, MemoryAllocationUtils.RAM_STEP_MB);
        int stepCount = Math.max(1, (maxRamMb - minRamMb) / ramStepMb);
        sliderMaxRamMb = minRamMb + (stepCount * ramStepMb);
        selectedRamMb = settings.hasRamOverride()
                ? settings.ramMb
                : MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
        selectedRamMb = clampRamToSliderRange(selectedRamMb);
    }

    private void saveAndDismiss() {
        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(activity, settingsKey);

        if (selectedRendererIndex > 0 && selectedRendererIndex - 1 < renderers.size()) {
            settings.rendererIdentifier = renderers.get(selectedRendererIndex - 1).getUniqueIdentifier();
        } else {
            settings.rendererIdentifier = InstanceLaunchSettings.RENDERER_DEFAULT;
        }

        if (isVulkanZinkSelected()
                && selectedVulkanDriverIndex > 0
                && selectedVulkanDriverIndex - 1 < vulkanDrivers.size()) {
            settings.vulkanDriverName =
                    vulkanDrivers.get(selectedVulkanDriverIndex - 1).getName();
        } else {
            settings.vulkanDriverName = InstanceLaunchSettings.VULKAN_DRIVER_INHERIT;
        }

        settings.runtimeName = InstanceLaunchSettings.runtimeNameForIndex(selectedRuntimeIndex);
        if (!graphicsApiSystemVulkanLocked) {
            settings.graphicsApiMode = InstanceLaunchSettings.graphicsApiModeForIndex(
                    selectedGraphicsApiModeIndex);
        }
        settings.vulkanCompatibilityMode =
                InstanceLaunchSettings.vulkanCompatibilityModeForIndex(
                        selectedVulkanCompatibilityModeIndex);
        settings.resolutionMode = InstanceLaunchSettings.resolutionModeForIndex(selectedResolutionModeIndex);
        settings.launchTargetMode = InstanceLaunchSettings.launchTargetModeForIndex(selectedLaunchTargetModeIndex);
        if (GameResolutionSettings.MODE_CUSTOM.equals(settings.resolutionMode)) {
            Integer width = readResolutionDimension(resolutionWidthInput);
            Integer height = readResolutionDimension(resolutionHeightInput);
            if (width == null || height == null) {
                Toast.makeText(
                        activity,
                        activity.getString(
                                R.string.per_instance_resolution_invalid,
                                GameResolutionSettings.MIN_CUSTOM_DIMENSION,
                                GameResolutionSettings.MAX_CUSTOM_DIMENSION),
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            settings.resolutionCustomWidth = width;
            settings.resolutionCustomHeight = height;
        } else {
            Integer width = readResolutionDimension(resolutionWidthInput);
            Integer height = readResolutionDimension(resolutionHeightInput);
            if (width != null) settings.resolutionCustomWidth = width;
            if (height != null) settings.resolutionCustomHeight = height;
        }
        settings.gcMode = distantHorizonsZgcSwitch != null && distantHorizonsZgcSwitch.isChecked()
                ? InstanceLaunchSettings.GC_MODE_ZGC
                : InstanceLaunchSettings.GC_MODE_G1GC;
        settings.customJvmArgs = jvmArgsInput != null && jvmArgsInput.getText() != null
                ? jvmArgsInput.getText().toString().trim()
                : "";

        boolean customRam = customRamSwitch != null && customRamSwitch.isChecked();
        if (customRam) {
            applyTypedRamValue();
            settings.ramMb = clampRamToSliderRange(selectedRamMb);
        } else {
            settings.ramMb = InstanceLaunchSettings.RAM_DEFAULT;
        }

        // Only the instance's exact ID owns these settings; other aliases can be a
        // shared Minecraft version or a duplicate display name.
        InstanceLaunchSettings.save(activity, settingsKey, settings);
        LauncherInstance target = LauncherInstanceManager.findByNameOrId(activity, settingsKey);
        if (target != null) {
            GraphicsBackendHelper.applyForSavedInstanceSettings(activity, target, settings);
        }
        Toast.makeText(activity, R.string.per_instance_saved, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    /*
     * Graphics API choices in this dialog are instance-local by design. Do not mirror
     * them into LauncherPreferences: doing that made a Vulkan test on one instance
     * silently change the launcher default for every other instance. GameActivity and
     * LaunchGame both resolve this instance key directly before native/Java startup.
     */

    private void saveAllAliases(@NonNull InstanceLaunchSettings.Settings settings) {
        InstanceLaunchSettings.save(activity, settingsKey, settings);
    }

    private void clearAllAliases() {
        InstanceLaunchSettings.clear(activity, settingsKey);
        LauncherInstance target = LauncherInstanceManager.findByNameOrId(activity, settingsKey);
        if (target != null) {
            GraphicsBackendHelper.applyForSavedInstanceSettings(
                    activity, target, new InstanceLaunchSettings.Settings());
        }
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private void updateResolutionCustomInputVisibility() {
        boolean custom = selectedResolutionModeIndex == 5;
        int visibility = custom ? View.VISIBLE : View.GONE;
        if (resolutionWidthLayout != null) resolutionWidthLayout.setVisibility(visibility);
        if (resolutionHeightLayout != null) resolutionHeightLayout.setVisibility(visibility);
    }

    @Nullable
    private Integer readResolutionDimension(@Nullable TextInputEditText input) {
        if (input == null || input.getText() == null) return null;
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < GameResolutionSettings.MIN_CUSTOM_DIMENSION
                    || value > GameResolutionSettings.MAX_CUSTOM_DIMENSION) {
                return null;
            }
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyTypedRamValue() {
        if (ramInput == null || ramInput.getText() == null) return;
        String raw = ramInput.getText().toString().trim();
        if (raw.isEmpty()) {
            selectedRamMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
        } else {
            try {
                selectedRamMb = Integer.parseInt(raw);
            } catch (Throwable ignored) {
                selectedRamMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
            }
        }
        selectedRamMb = clampRamToSliderRange(selectedRamMb);
        if (ramSlider != null && Math.round(ramSlider.getValue()) != selectedRamMb) {
            ramSlider.setValue(selectedRamMb);
        }
        updateRamViews();
    }

    private void updateRamViews() {
        boolean custom = customRamSwitch != null && customRamSwitch.isChecked();
        int globalMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);

        if (ramInput != null) {
            ramInput.setEnabled(custom);
            ramInput.setAlpha(custom ? 1f : 0.55f);
        }
        if (ramInputLayout != null) {
            ramInputLayout.setEnabled(custom);
        }
        if (ramSlider != null) {
            ramSlider.setEnabled(custom);
            ramSlider.setAlpha(custom ? 1f : 0.45f);
        }

        int displayMb = custom ? selectedRamMb : globalMb;
        setRamInputText(displayMb);

        if (ramSummary != null) {
            if (custom) {
                ramSummary.setText(activity.getString(
                        R.string.per_instance_custom_ram_value,
                        activity.getString(R.string.memory_mb_gb_value, selectedRamMb, formatGb(selectedRamMb))));
            } else {
                ramSummary.setText(activity.getString(
                        R.string.per_instance_launcher_ram_value,
                        activity.getString(R.string.memory_mb_gb_value, globalMb, formatGb(globalMb))));
            }
        }
        if (ramRangeText != null) {
            String mode = activity.getString(MemoryAllocationUtils.isRamUnlocked(activity)
                    ? R.string.memory_mode_unlocked : R.string.memory_mode_locked);
            ramRangeText.setText(activity.getString(R.string.per_instance_ram_range, mode, minRamMb, sliderMaxRamMb, ramStepMb));
        }
    }

    private void setRamInputText(int memoryMb) {
        if (ramInput == null || updatingRamText) return;
        String value = String.valueOf(memoryMb);
        String current = ramInput.getText() == null ? "" : ramInput.getText().toString();
        if (value.equals(current)) return;
        updatingRamText = true;
        ramInput.setText(value);
        ramInput.setSelection(ramInput.length());
        updatingRamText = false;
    }

    private int clampRamToSliderRange(int memoryMb) {
        int clamped = MemoryAllocationUtils.clampToAllowedRam(activity, memoryMb);
        clamped = Math.max(minRamMb, Math.min(sliderMaxRamMb, clamped));
        int offset = clamped - minRamMb;
        int roundedSteps = Math.round(offset / (float) ramStepMb);
        int rounded = minRamMb + roundedSteps * ramStepMb;
        return Math.max(minRamMb, Math.min(sliderMaxRamMb, rounded));
    }

    private int resolveRendererSelectionIndex(@Nullable String selectedRendererId) {
        if (selectedRendererId == null || selectedRendererId.trim().isEmpty()) return 0;
        for (int i = 0; i < renderers.size(); i++) {
            if (selectedRendererId.equals(renderers.get(i).getUniqueIdentifier())) {
                return i + 1;
            }
        }
        return 0;
    }

    private LinearLayout createSection() {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, dp(10));
        return section;
    }

    private void addSectionTitle(@NonNull LinearLayout root, @NonNull String titleText, @NonNull String summaryText) {
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(15);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        root.addView(title, matchWrap());

        TextView summary = new TextView(activity);
        summary.setText(summaryText);
        summary.setTextSize(12);
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setPadding(0, dp(2), 0, dp(8));
        root.addView(summary, matchWrap());
    }

    private TextInputLayout createOutlinedLayout(@NonNull String hint) {
        TextInputLayout layout = new TextInputLayout(activity);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
        return layout;
    }

    private TextInputLayout createDropdownLayout(@NonNull String hint) {
        TextInputLayout layout = createOutlinedLayout(hint);
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        return layout;
    }

    private int calculateMaxFormHeight() {
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        return Math.min(dp(440), Math.max(dp(250), height - dp(310)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    @NonNull
    private GradientDrawable roundedDialogDrawable(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(cornerDp));
        bg.setStroke(Math.max(1, dp(1)), strokeColor);
        return bg;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private String formatGb(int memoryMb) {
        return String.format(Locale.US, "%.1f", memoryMb / 1024f);
    }

    private void addAlias(@Nullable String rawKey) {
        if (rawKey == null) return;
        String key = InstanceLaunchSettings.resolveInstanceKey(rawKey, rawKey);
        if (key.trim().isEmpty()) return;
        if (!aliasKeys.contains(key)) aliasKeys.add(key);
    }
}
