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

package ca.dnamobile.droidbridgelauncher.controls;

import ca.dnamobile.droidbridgelauncher.ControllerNavigationActivity;
import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import android.content.ActivityNotFoundException;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public final class ControlsActivity extends ControllerNavigationActivity {
    private static final int REQUEST_IMPORT_CONTROLS = 9011;
    private static final int REQUEST_EXPORT_CONTROLS = 9012;
    private static final int REQUEST_CONVERT_SOURCE = 9013;
    private static final int REQUEST_CONVERT_SAVE = 9014;

    private final ArrayList<File> layoutFiles = new ArrayList<>();
    @Nullable private File pendingExportFile;
    @Nullable private String pendingConvertedJson;
    @NonNull private String pendingConvertedFileName = "converted_controls.json";
    private int pendingConversionDirection = TouchControlsConverter.LEGACY_TO_DROIDBRIDGE;
    private int pendingImportMode = TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE;
    private ArrayAdapter<File> adapter;
    private ListView listView;
    private TextView summary;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        TextView title = new TextView(this);
        title.setText("Touch Controls");
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        title.setPadding(dp(2), 0, dp(2), dp(2));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summary = new TextView(this);
        summary.setTextSize(13f);
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setLineSpacing(dp(1), 1.0f);
        summary.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actionsCard = new LinearLayout(this);
        actionsCard.setOrientation(LinearLayout.VERTICAL);
        actionsCard.setPadding(dp(10), dp(9), dp(10), dp(9));
        actionsCard.setBackground(cardBackground(false));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(8));
        root.addView(actionsCard, cardParams);

        TextView actionsTitle = new TextView(this);
        actionsTitle.setText("Layouts");
        actionsTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        actionsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        actionsTitle.setTextSize(14f);
        actionsTitle.setPadding(0, 0, 0, dp(6));
        actionsCard.addView(actionsTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actionsCard.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button importButton = actionButton("Import");
        importButton.setOnClickListener(view -> showImportModeDialog());
        actions.addView(importButton, actionButtonParams());

        Button editButton = actionButton("Edit Current");
        editButton.setOnClickListener(view -> startActivity(new Intent(this, ControlsEditorActivity.class)));
        actions.addView(editButton, actionButtonParams());

        Button closeButton = actionButton("Close");
        closeButton.setOnClickListener(view -> finish());
        actions.addView(closeButton, actionButtonParams());

        // Keep the original three-button row completely unchanged. Conversion is
        // intentionally isolated below it so adding this feature cannot resize or
        // disturb the existing Manage Touch Controls actions.
        Button convertButton = actionButton("Convert Controls");
        convertButton.setOnClickListener(view -> showConversionDirectionDialog());
        LinearLayout.LayoutParams convertParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        );
        convertParams.setMargins(dp(3), dp(8), dp(3), 0);
        actionsCard.addView(convertButton, convertParams);

        listView = new ListView(this);
        listView.setDivider(new ColorDrawable(LauncherDialogStyle.COLOR_CARD_STROKE));
        listView.setDividerHeight(dp(1));
        listView.setCacheColorHint(0x00000000);
        listView.setClipToPadding(false);
        // Leave room for the add-profile FAB so the final controller profile is never
        // hidden behind it. The ListView still scrolls underneath the floating button.
        listView.setPadding(0, dp(3), 0, dp(76));
        listView.setBackground(cardBackground(false));
        adapter = new ArrayAdapter<File>(this, 0, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                LayoutRowHolder holder;
                LinearLayout row;
                if (convertView instanceof LinearLayout && convertView.getTag() instanceof LayoutRowHolder) {
                    row = (LinearLayout) convertView;
                    holder = (LayoutRowHolder) row.getTag();
                } else {
                    row = new LinearLayout(ControlsActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(dp(44));
                    row.setPadding(dp(14), dp(5), dp(14), dp(5));

                    TextView titleView = new TextView(ControlsActivity.this);
                    titleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
                    titleView.setTextSize(13.5f);
                    titleView.setTypeface(Typeface.DEFAULT_BOLD);
                    titleView.setSingleLine(true);
                    titleView.setEllipsize(TextUtils.TruncateAt.END);
                    titleView.setIncludeFontPadding(false);
                    row.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView subtitleView = new TextView(ControlsActivity.this);
                    subtitleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
                    subtitleView.setTextSize(12f);
                    subtitleView.setSingleLine(true);
                    subtitleView.setEllipsize(TextUtils.TruncateAt.END);
                    subtitleView.setIncludeFontPadding(false);
                    subtitleView.setPadding(0, dp(3), 0, 0);
                    row.addView(subtitleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    holder = new LayoutRowHolder(titleView, subtitleView);
                    row.setTag(holder);
                }

                File file = getItem(position);
                if (file == null) {
                    holder.title.setText("");
                    holder.subtitle.setText("");
                    row.setBackgroundColor(0x00000000);
                    return row;
                }

                TouchControlsLayoutData data = TouchControlsStore.loadLayout(file);
                boolean selected = file.getAbsolutePath().equals(ControlsPreferences.getSelectedLayoutPath(ControlsActivity.this));
                holder.title.setText((selected ? "✓ " : "") + displayLayoutTitle(data, file));
                holder.subtitle.setText(layoutSubtitle(data, file));
                row.setBackground(rowBackground(selected));
                return row;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> selectLayout(position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showLayoutOptions(position);
            return true;
        });

        FrameLayout layoutsContainer = new FrameLayout(this);
        layoutsContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FloatingActionButton addProfileFab = new FloatingActionButton(this);
        addProfileFab.setContentDescription("Add touch controls profile");
        addProfileFab.setImageResource(android.R.drawable.ic_input_add);
        addProfileFab.setBackgroundTintList(ColorStateList.valueOf(LauncherDialogStyle.COLOR_ACCENT));
        addProfileFab.setImageTintList(ColorStateList.valueOf(LauncherDialogStyle.COLOR_DIALOG_BG));
        addProfileFab.setOnClickListener(view -> showCreateLayoutDialog());
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM
        );
        fabParams.setMargins(dp(16), dp(16), dp(16), dp(16));
        layoutsContainer.addView(addProfileFab, fabParams);

        root.addView(layoutsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        refreshList();
        root.post(this::enableImmersiveSafely);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listView != null) listView.post(this::enableImmersiveSafely);
        else enableImmersiveSafely();
        refreshList();
    }

    private void enableImmersiveSafely() {
        try { FullscreenUtils.enableImmersive(this); }
        catch (Throwable throwable) { Logging.e("ControlsActivity", "Unable to enable immersive mode", throwable); }
    }

    private void refreshList() {
        layoutFiles.clear();
        layoutFiles.addAll(TouchControlsStore.listLayouts(this));
        adapter.clear();
        adapter.addAll(layoutFiles);
        adapter.notifyDataSetChanged();
        summary.setText("Tap + to create a controller profile. Import layouts as needed, and long-press a profile for rename and other options.");
    }

    private void selectLayout(int position) {
        if (position < 0 || position >= layoutFiles.size()) return;
        File file = layoutFiles.get(position);
        ControlsPreferences.setSelectedLayoutPath(this, file.getAbsolutePath());
        Toast.makeText(this, "Selected " + file.getName(), Toast.LENGTH_SHORT).show();
        refreshList();
    }

    private void showCreateLayoutDialog() {
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                "Add touch controls",
                "Choose how you want to start the new controller profile."
        );

        final AlertDialog[] holder = new AlertDialog[1];
        addImportChoice(
                root,
                "Brand new controls",
                "Create an empty native DroidBridge profile with no buttons. You'll start with a clean canvas in the touch-control editor.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    createLayoutAndOpenEditor(false);
                }
        );
        addImportChoice(
                root,
                "Use Default controls as template",
                "Create a separate profile from DroidBridge's native bundled default controls. Your edited default profile is not copied.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    createLayoutAndOpenEditor(true);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void createLayoutAndOpenEditor(boolean useNativeDefaultTemplate) {
        try {
            File created = TouchControlsStore.createNewLayout(this, useNativeDefaultTemplate);
            TouchControlsLayoutData data = TouchControlsStore.loadLayout(created);
            Toast.makeText(
                    this,
                    "Created " + displayLayoutTitle(data, created),
                    Toast.LENGTH_SHORT
            ).show();
            refreshList();
            startActivity(new Intent(this, ControlsEditorActivity.class));
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to create touch controls profile", throwable);
            Toast.makeText(
                    this,
                    "Unable to create controller profile: " + throwable.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void showRenameLayoutDialog(@NonNull File file) {
        TouchControlsLayoutData data = TouchControlsStore.loadLayout(file);
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                "Rename controller profile",
                "Enter the new name for this touch-controls profile."
        );

        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setText(displayLayoutTitle(data, file));
        nameField.setSelection(nameField.getText().length());
        nameField.setSelectAllOnFocus(true);
        nameField.setHint("Controller profile name");
        nameField.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        nameField.setHintTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        root.addView(nameField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Rename", null)
                .create();
        dialog.setOnShowListener(unused -> {
            LauncherDialogStyle.styleDialogChrome(this, dialog);
            Button renameButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            renameButton.setOnClickListener(view -> {
                String requestedName = nameField.getText() == null
                        ? ""
                        : nameField.getText().toString().trim();
                if (requestedName.isEmpty()) {
                    nameField.setError("Enter a profile name");
                    return;
                }
                try {
                    File renamed = TouchControlsStore.renameLayout(this, file, requestedName);
                    dialog.dismiss();
                    refreshList();
                    Toast.makeText(
                            this,
                            "Renamed to " + TouchControlsStore.loadLayout(renamed).name,
                            Toast.LENGTH_SHORT
                    ).show();
                } catch (Throwable throwable) {
                    Logging.e("ControlsActivity", "Unable to rename touch controls profile", throwable);
                    nameField.setError(throwable.getMessage() == null
                            ? "Unable to rename profile"
                            : throwable.getMessage());
                }
            });
        });
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void showLayoutOptions(int position) {
        if (position < 0 || position >= layoutFiles.size()) return;
        File file = layoutFiles.get(position);

        TouchControlsLayoutData layoutData = TouchControlsStore.loadLayout(file);
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                displayLayoutTitle(layoutData, file),
                "Choose what to do with this controller profile."
        );
        final AlertDialog[] holder = new AlertDialog[1];

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        boolean useTwoColumns = screenWidth >= dp(600) || screenWidth > screenHeight;

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);

        LinearLayout firstTarget = options;
        LinearLayout secondTarget = options;
        if (useTwoColumns) {
            firstTarget = createDialogOptionRow();
            secondTarget = createDialogOptionRow();
            options.addView(firstTarget, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            options.addView(secondTarget, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        ScrollView optionsScroll = new ScrollView(this);
        optionsScroll.setFillViewport(false);
        optionsScroll.setClipToPadding(false);
        optionsScroll.setVerticalScrollBarEnabled(!useTwoColumns);
        optionsScroll.addView(options, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams optionsScrollParams;
        if (useTwoColumns) {
            // Landscape/wide devices get compact two-column option rows so the extra Rename
            // action remains visible without the Material dialog clipping the bottom rows.
            optionsScrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        } else {
            int maxOptionsHeight = Math.min(dp(420), Math.max(dp(220), Math.round(screenHeight * 0.55f)));
            optionsScrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    maxOptionsHeight
            );
        }
        root.addView(optionsScroll, optionsScrollParams);

        addLayoutOption(
                firstTarget,
                "Use this layout",
                "Select this profile for the next game launch.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    selectLayout(position);
                }
        );
        addLayoutOption(
                firstTarget,
                "Edit",
                "Open this profile in the touch-control editor.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    ControlsPreferences.setSelectedLayoutPath(this, file.getAbsolutePath());
                    startActivity(new Intent(this, ControlsEditorActivity.class));
                }
        );
        addLayoutOption(
                secondTarget,
                "Rename",
                "Change this controller profile's name.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    showRenameLayoutDialog(file);
                }
        );
        addLayoutOption(
                secondTarget,
                "Export JSON",
                "Save or share a copy of this controller profile.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openExportPicker(file);
                }
        );
        addLayoutOption(
                useTwoColumns ? options : secondTarget,
                "Delete",
                "Remove this profile from DroidBridge.",
                true,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    confirmDelete(file);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(d -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void addLayoutOption(
            @NonNull LinearLayout root,
            @NonNull String title,
            @NonNull String description,
            boolean destructive,
            @NonNull View.OnClickListener listener
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        GradientDrawable background = LauncherDialogStyle.roundedDrawable(
                this,
                destructive ? LauncherDialogStyle.COLOR_CARD_BG : LauncherDialogStyle.COLOR_CARD_BG_PRESSED,
                destructive ? LauncherDialogStyle.COLOR_ERROR : LauncherDialogStyle.COLOR_CARD_STROKE,
                14
        );
        card.setBackground(background);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(destructive
                ? LauncherDialogStyle.COLOR_ERROR
                : LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        titleView.setIncludeFontPadding(false);
        card.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextSize(12.5f);
        descriptionView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        descriptionView.setPadding(0, dp(3), 0, 0);
        descriptionView.setIncludeFontPadding(false);
        card.addView(descriptionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams params;
        if (root.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
        } else {
            params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, dp(4), 0, dp(4));
        }
        root.addView(card, params);
    }

    @NonNull
    private LinearLayout createDialogOptionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        return row;
    }

    private void confirmDelete(@NonNull File file) {
        if (file.equals(TouchControlsStore.getDefaultLayoutFile(this))) {
            Toast.makeText(this, "Default layout cannot be deleted.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Delete layout?")
                .setMessage(file.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (unused, which) -> {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    if (file.getAbsolutePath().equals(ControlsPreferences.getSelectedLayoutPath(this))) {
                        ControlsPreferences.setSelectedLayoutPath(this, TouchControlsStore.getDefaultLayoutFile(this).getAbsolutePath());
                    }
                    refreshList();
                })
                .create();
        dialog.setOnShowListener(d -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void showConversionDirectionDialog() {
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                "Convert touch controls",
                "Convert JSON layouts between DroidBridge, legacy Android launcher controls, and Zalith 2. Unsupported Zalith 2 actions are preserved as editable buttons instead of being dropped."
        );

        final AlertDialog[] holder = new AlertDialog[1];
        addImportChoice(
                root,
                "Legacy → DroidBridge",
                "Convert Zalith/Pojav/MJ-style controls into a native DroidBridge profile, including source-to-target screen-size correction.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openConversionSourcePicker(TouchControlsConverter.LEGACY_TO_DROIDBRIDGE);
                }
        );
        addImportChoice(
                root,
                "DroidBridge → Legacy",
                "Export a DroidBridge profile as a compatible Pojav-family controls.json layout with resolution-aware positions.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    showDroidBridgeConversionSourceDialog(TouchControlsConverter.DROIDBRIDGE_TO_LEGACY);
                }
        );
        addImportChoice(
                root,
                "Legacy → Zalith 2",
                "Convert Zalith 1/Pojav/MJ-style controls to Zalith 2. Drawers are flattened and unsupported actions remain as editable unbound buttons.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openConversionSourcePicker(TouchControlsConverter.LEGACY_TO_ZALITH2);
                }
        );
        addImportChoice(
                root,
                "DroidBridge → Zalith 2",
                "Convert a DroidBridge profile directly to Zalith 2 without passing through Legacy first. Unsupported actions are kept for manual rebinding.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    showDroidBridgeConversionSourceDialog(TouchControlsConverter.DROIDBRIDGE_TO_ZALITH2);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void showDroidBridgeConversionSourceDialog(int direction) {
        boolean toZalith2 = direction == TouchControlsConverter.DROIDBRIDGE_TO_ZALITH2;
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                toZalith2 ? "DroidBridge → Zalith 2 source" : "DroidBridge → Legacy source",
                "Convert the currently selected DroidBridge profile directly, or choose a DroidBridge JSON from device storage."
        );
        final AlertDialog[] holder = new AlertDialog[1];
        addImportChoice(
                root,
                "Current selected profile",
                "Use the profile currently selected in Manage Touch Controls. No export step is required first.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openCurrentSelectedForConversion(direction);
                }
        );
        addImportChoice(
                root,
                "Choose DroidBridge JSON",
                "Pick an exported DroidBridge controller JSON from device storage.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openConversionSourcePicker(direction);
                }
        );
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void openCurrentSelectedForConversion(int direction) {
        pendingConversionDirection = direction;
        pendingConvertedJson = null;
        try {
            File file = TouchControlsStore.getSelectedLayoutFile(this);
            JSONObject source = new JSONObject(TouchControlsStore.readText(file));
            int currentWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int currentHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
            showConversionSettingsDialog(
                    source,
                    baseNameWithoutJson(file.getName()),
                    TouchControlsConverter.suggestedSourceWidth(source, currentWidth),
                    TouchControlsConverter.suggestedSourceHeight(source, currentHeight),
                    currentWidth,
                    currentHeight
            );
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to load selected controls for conversion", throwable);
            Toast.makeText(this, "Unable to load selected profile: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openConversionSourcePicker(int direction) {
        pendingConversionDirection = direction;
        pendingConvertedJson = null;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_CONVERT_SOURCE); }
        catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, "No file picker found.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleConversionSourceResult(int resultCode, @Nullable Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Throwable ignored) { }

        try {
            JSONObject source = new JSONObject(readUriText(uri));
            int currentWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            int currentHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
            int suggestedWidth = TouchControlsConverter.suggestedSourceWidth(source, currentWidth);
            int suggestedHeight = TouchControlsConverter.suggestedSourceHeight(source, currentHeight);
            String sourceName = baseNameWithoutJson(uri.getLastPathSegment() == null
                    ? "controls"
                    : uri.getLastPathSegment());
            showConversionSettingsDialog(
                    source,
                    sourceName,
                    suggestedWidth,
                    suggestedHeight,
                    currentWidth,
                    currentHeight
            );
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to read conversion source", throwable);
            Toast.makeText(this, "Conversion source failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showConversionSettingsDialog(
            @NonNull JSONObject source,
            @NonNull String sourceName,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight
    ) {
        String directionName;
        if (pendingConversionDirection == TouchControlsConverter.LEGACY_TO_DROIDBRIDGE) {
            directionName = "Legacy → DroidBridge";
        } else if (pendingConversionDirection == TouchControlsConverter.DROIDBRIDGE_TO_LEGACY) {
            directionName = "DroidBridge → Legacy";
        } else if (pendingConversionDirection == TouchControlsConverter.LEGACY_TO_ZALITH2) {
            directionName = "Legacy → Zalith 2";
        } else {
            directionName = "DroidBridge → Zalith 2";
        }
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                directionName,
                "Source = the screen resolution the controls were authored/tested on. Target = the screen resolution you want the converted layout prepared for. This is what fixes 1920×1080 → 2560-wide placement problems."
        );

        TextView sourceLabel = conversionSectionLabel("Source screen size");
        root.addView(sourceLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        EditText sourceWidthField = resolutionField("Width", sourceWidth);
        EditText sourceHeightField = resolutionField("Height", sourceHeight);
        root.addView(resolutionRow(sourceWidthField, sourceHeightField));

        TextView targetLabel = conversionSectionLabel("Target screen size");
        LinearLayout.LayoutParams targetLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        targetLabelParams.topMargin = dp(10);
        root.addView(targetLabel, targetLabelParams);
        EditText targetWidthField = resolutionField("Width", targetWidth);
        EditText targetHeightField = resolutionField("Height", targetHeight);
        root.addView(resolutionRow(targetWidthField, targetHeightField));

        final EditText legacyScaleField;
        if (pendingConversionDirection == TouchControlsConverter.DROIDBRIDGE_TO_LEGACY) {
            TextView legacyScaleLabel = conversionSectionLabel("Target launcher CURRENT control scale (%)");
            LinearLayout.LayoutParams legacyScaleLabelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            legacyScaleLabelParams.topMargin = dp(10);
            root.addView(legacyScaleLabel, legacyScaleLabelParams);

            legacyScaleField = resolutionField(
                    "Scale %",
                    TouchControlsConverter.suggestedLegacyScale(source, 100)
            );
            root.addView(legacyScaleField, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
            ));

            TextView legacyScaleHint = new TextView(this);
            legacyScaleHint.setText("Usually 100%. Set this to the control/button scale CURRENTLY selected in Zalith, MJ, or Pojav. Do not copy DroidBridge's profile scale here: DroidBridge's scale is already baked into the exported positions and sizes. A mismatch will shrink/enlarge controls and shift center/right anchored buttons.");
            legacyScaleHint.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            legacyScaleHint.setTextSize(12f);
            legacyScaleHint.setPadding(dp(2), dp(6), dp(2), 0);
            root.addView(legacyScaleHint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        } else {
            legacyScaleField = null;
        }

        if (pendingConversionDirection == TouchControlsConverter.LEGACY_TO_ZALITH2
                || pendingConversionDirection == TouchControlsConverter.DROIDBRIDGE_TO_ZALITH2) {
            TextView zalith2Hint = new TextView(this);
            zalith2Hint.setText("Zalith 2 note: every control is preserved. Legacy drawers are flattened into normal buttons. Any action without a verified Zalith 2 event equivalent is exported with its label/position/size intact and no click event so you can bind it manually in the Zalith 2 editor.");
            zalith2Hint.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            zalith2Hint.setTextSize(12f);
            zalith2Hint.setPadding(dp(2), dp(8), dp(2), 0);
            root.addView(zalith2Hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        TextView hint = new TextView(this);
        hint.setText("Example: controls made at 1920 × 1080 for a 2560 × 1440 screen → Source 1920 × 1080, Target 2560 × 1440.");
        hint.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        hint.setTextSize(12f);
        hint.setPadding(dp(2), dp(8), dp(2), 0);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Convert", null)
                .create();
        dialog.setOnShowListener(unused -> {
            LauncherDialogStyle.styleDialogChrome(this, dialog);
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(view -> {
                try {
                    int parsedSourceWidth = positiveInt(sourceWidthField, "Source width");
                    int parsedSourceHeight = positiveInt(sourceHeightField, "Source height");
                    int parsedTargetWidth = positiveInt(targetWidthField, "Target width");
                    int parsedTargetHeight = positiveInt(targetHeightField, "Target height");
                    int targetLegacyScale = legacyScaleField == null
                            ? 100
                            : percentageInt(legacyScaleField, "Target launcher control scale");
                    float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
                    JSONObject converted = TouchControlsConverter.convert(
                            source,
                            pendingConversionDirection,
                            parsedSourceWidth,
                            parsedSourceHeight,
                            parsedTargetWidth,
                            parsedTargetHeight,
                            density,
                            targetLegacyScale
                    );
                    pendingConvertedJson = converted.toString(2);
                    String cleanSourceName = sourceName.trim().isEmpty() ? "controls" : sourceName.trim();
                    String suffix;
                    if (pendingConversionDirection == TouchControlsConverter.LEGACY_TO_DROIDBRIDGE) {
                        suffix = "droidbridge";
                    } else if (pendingConversionDirection == TouchControlsConverter.DROIDBRIDGE_TO_LEGACY) {
                        suffix = "legacy";
                    } else {
                        suffix = "zalith2";
                    }
                    pendingConvertedFileName = safeConvertedName(cleanSourceName, suffix);
                    dialog.dismiss();
                    openConvertedSavePicker();
                } catch (Throwable throwable) {
                    Logging.e("ControlsActivity", "Unable to convert controls", throwable);
                    Toast.makeText(this, "Conversion failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    @NonNull
    private TextView conversionSectionLabel(@NonNull String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(14f);
        label.setPadding(dp(2), dp(4), dp(2), dp(4));
        return label;
    }

    @NonNull
    private EditText resolutionField(@NonNull String hint, int value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(Integer.toString(Math.max(1, value)));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        field.setHintTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        field.setBackground(cardBackground(true));
        field.setPadding(dp(12), 0, dp(12), 0);
        return field;
    }

    @NonNull
    private LinearLayout resolutionRow(@NonNull EditText width, @NonNull EditText height) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        fieldParams.setMargins(dp(3), 0, dp(3), 0);
        row.addView(width, new LinearLayout.LayoutParams(fieldParams));
        row.addView(height, new LinearLayout.LayoutParams(fieldParams));
        return row;
    }

    private int positiveInt(@NonNull EditText field, @NonNull String label) {
        String value = field.getText() == null ? "" : field.getText().toString().trim();
        int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (Throwable ignored) { throw new IllegalArgumentException(label + " must be a number."); }
        if (parsed < 100 || parsed > 16384) {
            throw new IllegalArgumentException(label + " must be between 100 and 16384 pixels.");
        }
        return parsed;
    }

    private int percentageInt(@NonNull EditText field, @NonNull String label) {
        String value = field.getText() == null ? "" : field.getText().toString().trim();
        int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (Throwable ignored) { throw new IllegalArgumentException(label + " must be a number."); }
        if (parsed < 25 || parsed > 200) {
            throw new IllegalArgumentException(label + " must be between 25 and 200 percent.");
        }
        return parsed;
    }

    private void openConvertedSavePicker() {
        if (pendingConvertedJson == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, pendingConvertedFileName);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_CONVERT_SAVE); }
        catch (ActivityNotFoundException throwable) {
            pendingConvertedJson = null;
            Toast.makeText(this, "No file picker found.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleConvertedSaveResult(int resultCode, @Nullable Intent data) {
        String json = pendingConvertedJson;
        pendingConvertedJson = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || json == null) return;
        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Unable to open conversion destination.");
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "Converted controls saved. You can import the JSON now to test it.", Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to save converted controls", throwable);
            Toast.makeText(this, "Save failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showImportModeDialog() {
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                "Import touch controls",
                "Choose how DroidBridge should treat the selected JSON layout."
        );

        final AlertDialog[] holder = new AlertDialog[1];
        addImportChoice(
                root,
                "DroidBridge profile",
                "Convert/import using DroidBridge scaling, sizing, and positioning rules. Use this for DroidBridge-made layouts or when you want the default DroidBridge behavior.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openImportPicker(TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE);
                }
        );
        addImportChoice(
                root,
                "Legacy launcher profile",
                "Use for compatible third-party controls.json files. Preserves their screen-position formulas, density conversions, saved button scale, and legacy version rules.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openImportPicker(TouchControlsLayoutData.IMPORT_MODE_OTHER_LAUNCHER);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void addImportChoice(
            @NonNull LinearLayout root,
            @NonNull String title,
            @NonNull String message,
            @NonNull View.OnClickListener listener
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(cardBackground(true));
        card.setOnClickListener(listener);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        card.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(13f);
        messageView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        messageView.setLineSpacing(dp(1), 1.0f);
        messageView.setPadding(0, dp(4), 0, 0);
        card.addView(messageView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        root.addView(card, params);
    }

    private void openImportPicker(int importMode) {
        pendingImportMode = importMode;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_CONTROLS); }
        catch (ActivityNotFoundException throwable) { Toast.makeText(this, "No file picker found.", Toast.LENGTH_LONG).show(); }
    }

    private void openExportPicker(@NonNull File file) {
        pendingExportFile = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, safeExportName(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_EXPORT_CONTROLS); }
        catch (ActivityNotFoundException throwable) {
            pendingExportFile = null;
            Toast.makeText(this, "No file picker found.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_CONTROLS) { handleImportResult(resultCode, data); return; }
        if (requestCode == REQUEST_EXPORT_CONTROLS) { handleExportResult(resultCode, data); return; }
        if (requestCode == REQUEST_CONVERT_SOURCE) { handleConversionSourceResult(resultCode, data); return; }
        if (requestCode == REQUEST_CONVERT_SAVE) handleConvertedSaveResult(resultCode, data);
    }

    private void handleImportResult(int resultCode, @Nullable Intent data) {
        int importMode = pendingImportMode;
        pendingImportMode = TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) { }
        try {
            File imported = TouchControlsStore.saveImportedLayout(this, uri, importMode);
            Toast.makeText(this, "Imported " + imported.getName(), Toast.LENGTH_SHORT).show();
            refreshList();
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to import controls", throwable);
            Toast.makeText(this, "Import failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleExportResult(int resultCode, @Nullable Intent data) {
        File source = pendingExportFile;
        pendingExportFile = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || source == null) return;
        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Unable to open export destination.");
            String json = TouchControlsStore.readText(source);
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "Exported " + source.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to export controls", throwable);
            Toast.makeText(this, "Export failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private Button actionButton(@NonNull String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextSize(13f);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setBackground(LauncherDialogStyle.roundedDrawable(this, LauncherDialogStyle.COLOR_CARD_BG_PRESSED, LauncherDialogStyle.COLOR_CARD_STROKE, 14));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams actionButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    @NonNull
    private GradientDrawable rowBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED : 0x00000000);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    @NonNull
    private String displayLayoutTitle(@NonNull TouchControlsLayoutData data, @NonNull File file) {
        String imported = data.importedFileName.trim();
        if (!imported.isEmpty()) {
            String importedBase = baseNameWithoutJson(imported);
            if (!importedBase.isEmpty()) return importedBase;
        }

        String name = data.name == null ? "" : data.name.trim();
        if (isGenericImportedName(name) && !file.equals(TouchControlsStore.getDefaultLayoutFile(this))) {
            String fileBase = baseNameWithoutJson(file.getName());
            if (!fileBase.isEmpty()) return fileBase;
        }
        return name.isEmpty() ? baseNameWithoutJson(file.getName()) : name;
    }

    @NonNull
    private String layoutSubtitle(@NonNull TouchControlsLayoutData data, @NonNull File file) {
        String profile = data.usesOtherLauncherProfile() ? "Other launcher" : "DroidBridge";
        String source = data.importedFileName.trim().isEmpty() ? file.getName() : data.importedFileName.trim();
        if (source.trim().isEmpty()) return profile;
        return profile + " • " + source;
    }

    private static boolean isGenericImportedName(@NonNull String name) {
        String lower = name.trim().toLowerCase();
        return lower.equals("imported controls")
                || lower.equals("imported droidbridge/zalith controls")
                || lower.equals("touch controls");
    }

    @NonNull
    private static String baseNameWithoutJson(@NonNull String name) {
        String clean = name.trim();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < clean.length()) clean = clean.substring(slash + 1);
        if (clean.toLowerCase().endsWith(".json")) clean = clean.substring(0, clean.length() - 5);
        return clean.trim();
    }

    @NonNull
    private GradientDrawable cardBackground(boolean pressedTone) {
        return LauncherDialogStyle.roundedDrawable(
                this,
                pressedTone ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED : LauncherDialogStyle.COLOR_CARD_BG,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                18
        );
    }

    private static final class LayoutRowHolder {
        @NonNull final TextView title;
        @NonNull final TextView subtitle;

        LayoutRowHolder(@NonNull TextView title, @NonNull TextView subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    @NonNull
    private String readUriText(@NonNull Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Unable to open selected controls file.");
            byte[] buffer = new byte[64 * 1024];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        }
    }

    @NonNull
    private static String safeConvertedName(@NonNull String sourceName, @NonNull String suffix) {
        String base = baseNameWithoutJson(sourceName);
        if (base.isEmpty()) base = "controls";
        String safe = (base + "_" + suffix).replaceAll("[^A-Za-z0-9._-]+", "_");
        if (!safe.toLowerCase().endsWith(".json")) safe += ".json";
        return safe;
    }

    @NonNull
    private static String safeExportName(@NonNull File file) {
        String name = file.getName();
        if (!name.toLowerCase().endsWith(".json")) name += ".json";
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
