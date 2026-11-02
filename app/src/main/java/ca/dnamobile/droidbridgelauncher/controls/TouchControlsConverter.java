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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Converts DroidBridge/legacy touch profiles to DroidBridge, Pojav-family legacy, or Zalith 2 JSON. */
public final class TouchControlsConverter {
    public static final int LEGACY_TO_DROIDBRIDGE = 1;
    public static final int DROIDBRIDGE_TO_LEGACY = 2;
    public static final int LEGACY_TO_ZALITH2 = 3;
    public static final int DROIDBRIDGE_TO_ZALITH2 = 4;

    private static final float MIN_RESPONSIVE_CANVAS_SCALE = 0.55f;
    private static final float MAX_RESPONSIVE_CANVAS_SCALE = 2.25f;
    // UUID of Zalith 2's built-in/default button style from a valid editorVersion 10 export.
    private static final String ZALITH2_DEFAULT_STYLE_UUID = "d1096cf91caa";

    private TouchControlsConverter() {
    }

    @NonNull
    public static JSONObject convert(
            @NonNull JSONObject source,
            int direction,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            float density
    ) throws Exception {
        return convert(
                source,
                direction,
                sourceWidth,
                sourceHeight,
                targetWidth,
                targetHeight,
                density,
                suggestedLegacyScale(source, 100)
        );
    }

    @NonNull
    public static JSONObject convert(
            @NonNull JSONObject source,
            int direction,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            float density,
            int targetLegacyScalePercent
    ) throws Exception {
        int safeSourceWidth = Math.max(1, sourceWidth);
        int safeSourceHeight = Math.max(1, sourceHeight);
        int safeTargetWidth = Math.max(1, targetWidth);
        int safeTargetHeight = Math.max(1, targetHeight);
        float safeDensity = sanitizeDensity(density);
        int safeLegacyScale = clampLegacyScale(targetLegacyScalePercent);

        if (direction == LEGACY_TO_DROIDBRIDGE) {
            return convertLegacyToDroidBridge(
                    source,
                    safeSourceWidth,
                    safeSourceHeight,
                    safeTargetWidth,
                    safeTargetHeight,
                    safeDensity
            );
        }
        if (direction == DROIDBRIDGE_TO_LEGACY) {
            return convertDroidBridgeToLegacy(
                    source,
                    safeSourceWidth,
                    safeSourceHeight,
                    safeTargetWidth,
                    safeTargetHeight,
                    safeDensity,
                    safeLegacyScale
            );
        }
        if (direction == LEGACY_TO_ZALITH2) {
            return convertLegacyToZalith2(
                    source,
                    safeSourceWidth,
                    safeSourceHeight,
                    safeTargetWidth,
                    safeTargetHeight,
                    safeDensity
            );
        }
        if (direction == DROIDBRIDGE_TO_ZALITH2) {
            return convertDroidBridgeToZalith2(
                    source,
                    safeSourceWidth,
                    safeSourceHeight,
                    safeTargetWidth,
                    safeTargetHeight,
                    safeDensity
            );
        }
        throw new IllegalArgumentException("Unknown touch-control conversion direction.");
    }

    /** Best-effort source-width hint for the conversion dialog. */
    public static int suggestedSourceWidth(@NonNull JSONObject root, int fallback) {
        return Math.round(firstPositiveFloat(
                root,
                fallback,
                "sourceWidth", "source_width", "canvasWidth", "canvas_width",
                "screenWidth", "screen_width", "displayWidth", "display_width",
                "baseWidth", "base_width", "layoutWidth", "layout_width"
        ));
    }

    /** Best-effort source-height hint for the conversion dialog. */
    public static int suggestedSourceHeight(@NonNull JSONObject root, int fallback) {
        return Math.round(firstPositiveFloat(
                root,
                fallback,
                "sourceHeight", "source_height", "canvasHeight", "canvas_height",
                "screenHeight", "screen_height", "displayHeight", "display_height",
                "baseHeight", "base_height", "layoutHeight", "layout_height"
        ));
    }

    /**
     * Initial target-launcher scale hint for DroidBridge -> Legacy conversion.
     *
     * This is deliberately NOT copied from DroidBridge's profile scale. The source
     * profile scale is already baked into the converted geometry. Pojav-family
     * scaledAt instead describes the target launcher's current button-scale setting.
     * We cannot infer that from a DroidBridge JSON, so default to the caller's target
     * fallback (normally 100%).
     */
    public static int suggestedLegacyScale(@NonNull JSONObject root, int fallback) {
        return clampLegacyScale(fallback);
    }

    @NonNull
    private static JSONObject convertLegacyToDroidBridge(
            @NonNull JSONObject source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            float density
    ) throws Exception {
        TouchControlsLayoutData legacy = TouchControlsLayoutData.fromJson(
                source,
                TouchControlsLayoutData.IMPORT_MODE_OTHER_LAUNCHER,
                sourceWidth,
                sourceHeight,
                density
        );

        TouchControlsLayoutData converted = new TouchControlsLayoutData();
        converted.version = TouchControlsLayoutData.RESPONSIVE_FORMAT_VERSION;
        converted.name = convertedName(legacy.name, "DroidBridge");
        converted.importedFileName = "";
        converted.preferredScale = 100f;
        converted.coordinateUnit = TouchControlsLayoutData.UNIT_PX;
        converted.coordinateProfile = TouchControlsLayoutData.PROFILE_DROIDBRIDGE;
        converted.controlSizeUnit = TouchControlsLayoutData.UNIT_DP;
        converted.sourceWidth = targetWidth;
        converted.sourceHeight = targetHeight;
        converted.sourceDensity = density;
        converted.responsiveCanvas = true;
        converted.globalOpacity = legacy.globalOpacity;
        converted.globalButtonScalePercent = 100;
        converted.virtualMouseEnabled = legacy.virtualMouseEnabled;
        converted.snapControlsEnabled = legacy.snapControlsEnabled;

        for (TouchControlData original : legacy.controls) {
            /*
             * IMPORTANT: Pojav/Zalith positions are not a simple source-canvas pixel
             * layout. Modern legacy profiles carry dynamicX/dynamicY expressions that
             * are evaluated against the CURRENT screen, while width/height remain dp.
             *
             * The normal "Import other launcher controls" path already models that
             * correctly. Conversion must therefore resolve the imported legacy model
             * directly on the requested TARGET canvas. Resolving it on the source
             * canvas and then heuristically scaling/anchoring the resulting pixels
             * destroys expressions such as screen_width - width, centered controls,
             * px()/dp(), and Zalith drawer placement.
             *
             * sourceWidth/sourceHeight are still passed into fromJson() above because
             * legacy v1/v2 fixed x/y values need the authored screen size when they are
             * normalized into screen-relative expressions.
             */
            Geometry geometry = evaluate(legacy, original, targetWidth, targetHeight, density);
            TouchControlData control = original.copy();
            // Preserve stable IDs so drawer-child relationships survive conversion.
            control.id = original.id;

            // Bake the exact geometry Zalith/Pojav would display on the requested
            // target screen into a native responsive DroidBridge profile.
            control.x = geometry.x;
            control.y = geometry.y;

            // Legacy controls are physically sized in dp. Bake the imported profile's
            // current size preference into each control so the converted DroidBridge
            // profile can use a neutral 100% global scale without changing appearance.
            control.width = Math.max(1f, geometry.width / density);
            control.height = Math.max(1f, geometry.height / density);
            if (TouchControlActions.JOYSTICK.equals(control.action)) {
                float square = Math.max(control.width, control.height);
                control.width = square;
                control.height = square;
            }

            float legacySizeRatio = legacySizeRatio(legacy);
            control.cornerRadius = Math.max(0f, original.cornerRadius * legacySizeRatio);
            control.strokeWidth = Math.max(0f, original.strokeWidth * legacySizeRatio);
            control.rawX = null;
            control.rawY = null;
            control.positionAnchorX = inferHorizontalAnchor(control.x, control.width * density, targetWidth);
            control.positionAnchorY = inferVerticalAnchor(control.y, control.height * density, targetHeight);
            converted.controls.add(control);
        }

        JSONObject result = converted.toJson();
        addConversionMetadata(result, sourceWidth, sourceHeight, targetWidth, targetHeight, "legacy", "droidbridge");
        return result;
    }

    @NonNull
    private static JSONObject convertDroidBridgeToLegacy(
            @NonNull JSONObject source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            float density,
            int targetLegacyScalePercent
    ) throws Exception {
        TouchControlsLayoutData droidBridge = TouchControlsLayoutData.fromJson(
                source,
                TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE,
                sourceWidth,
                sourceHeight,
                density
        );

        JSONObject result = new JSONObject();
        // Keep Legacy output on the Pojav-family schema used by Zalith 1 / MJ /
        // Pojav rather than reusing DroidBridge's internal responsive format version.
        // Version 7 is the newest legacy revision whose conversion behaviour is
        // modelled by the existing importer (including the v6/v7 joystick rules).
        result.put("version", 7);
        result.put("name", convertedName(droidBridge.name, "Legacy"));

        /*
         * Pojav-family controls are saved at a specific button-size preference.
         * Their loader normalizes dimensions/placement from scaledAt and then applies
         * the launcher's CURRENT control scale. sourceGeometry below is already the
         * final DroidBridge geometry, including DroidBridge's profile scale. Therefore
         * scaledAt must match the target launcher's current scale so that importing the
         * file is a 1:1 operation instead of applying another center-relative scale.
         */
        result.put("scaledAt", clampLegacyScale(targetLegacyScalePercent));

        JSONArray buttons = new JSONArray();
        JSONArray joysticks = new JSONArray();
        JSONArray drawers = new JSONArray();
        Map<String, TouchControlData> drawerParents = new LinkedHashMap<>();
        Map<String, JSONArray> drawerChildren = new LinkedHashMap<>();
        Map<String, JSONObject> drawerParentJson = new LinkedHashMap<>();

        for (TouchControlData control : droidBridge.controls) {
            if (TouchControlActions.DRAWER.equals(control.action)) {
                drawerParents.put(control.id, control);
                drawerChildren.put(control.id, new JSONArray());
            }
        }

        float scaleX = targetWidth / (float) Math.max(1, sourceWidth);
        float scaleY = targetHeight / (float) Math.max(1, sourceHeight);

        for (TouchControlData control : droidBridge.controls) {
            Geometry sourceGeometry = evaluate(droidBridge, control, sourceWidth, sourceHeight, density);
            String horizontalAnchor = inferHorizontalAnchor(
                    sourceGeometry.x,
                    sourceGeometry.width,
                    sourceWidth
            );
            String verticalAnchor = inferVerticalAnchor(
                    sourceGeometry.y,
                    sourceGeometry.height,
                    sourceHeight
            );
            float targetX = mapAnchoredX(
                    sourceGeometry.x,
                    sourceGeometry.width,
                    sourceWidth,
                    targetWidth,
                    scaleX,
                    horizontalAnchor
            );
            float targetY = mapAnchoredY(
                    sourceGeometry.y,
                    sourceGeometry.height,
                    sourceHeight,
                    targetHeight,
                    scaleY,
                    verticalAnchor
            );

            float widthDp = Math.max(1f, sourceGeometry.width / density);
            float heightDp = Math.max(1f, sourceGeometry.height / density);
            if (TouchControlActions.JOYSTICK.equals(control.action)) {
                float square = Math.max(widthDp, heightDp);
                widthDp = square;
                heightDp = square;
            }

            JSONObject legacyControl = toLegacyControl(
                    control,
                    targetX,
                    targetY,
                    widthDp,
                    heightDp,
                    sourceGeometry.width,
                    sourceGeometry.height,
                    targetWidth,
                    targetHeight,
                    horizontalAnchor,
                    verticalAnchor,
                    droidBridge.globalButtonScalePercent / 100f,
                    droidBridge.globalOpacity
            );

            if (TouchControlActions.DRAWER.equals(control.action)) {
                drawerParentJson.put(control.id, legacyControl);
                continue;
            }

            if (control.drawerParentId != null && drawerChildren.containsKey(control.drawerParentId)) {
                drawerChildren.get(control.drawerParentId).put(legacyControl);
                continue;
            }

            if (TouchControlActions.JOYSTICK.equals(control.action)) joysticks.put(legacyControl);
            else buttons.put(legacyControl);
        }

        for (Map.Entry<String, TouchControlData> entry : drawerParents.entrySet()) {
            String id = entry.getKey();
            TouchControlData drawer = entry.getValue();
            JSONObject drawerJson = new JSONObject();
            drawerJson.put("name", drawer.label);
            JSONObject properties = drawerParentJson.get(id);
            if (properties != null) drawerJson.put("properties", properties);
            // DroidBridge stores each drawer child's final position explicitly. A
            // directional Pojav/Zalith drawer (DOWN/LEFT/UP/RIGHT) ignores those saved
            // positions and calls alignButtons(), which collapses custom grids into one
            // row/column. Export as FREE so the converted dynamicX/dynamicY values are
            // authoritative and the layout stays pixel-for-pixel where DroidBridge put it.
            drawerJson.put("orientation", "FREE");
            drawerJson.put("drawerOpenByDefault", drawer.drawerOpenByDefault);
            drawerJson.put("buttonProperties", drawerChildren.get(id));
            drawers.put(drawerJson);
        }

        result.put("mControlDataList", buttons);
        result.put("mJoystickDataList", joysticks);
        result.put("mDrawerDataList", drawers);
        // Deliberately do not append DroidBridge-only root metadata here. This JSON
        // is meant to be consumed directly by Zalith/MJ/Pojav legacy loaders.
        return result;
    }

    @NonNull
    private static JSONObject convertLegacyToZalith2(
            @NonNull JSONObject source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            float density
    ) throws Exception {
        TouchControlsLayoutData legacy = TouchControlsLayoutData.fromJson(
                source,
                TouchControlsLayoutData.IMPORT_MODE_OTHER_LAUNCHER,
                sourceWidth,
                sourceHeight,
                density
        );

        JSONArray buttons = new JSONArray();
        int[] manualBindingCount = new int[]{0};
        for (TouchControlData control : legacy.controls) {
            /*
             * Resolve legacy dynamicX/dynamicY on the requested target canvas using
             * the same importer model that already works for Legacy -> DroidBridge.
             *
             * Zalith 2 has no Pojav/Zalith-1 drawer object in the supplied schema, so
             * drawer parents and children are intentionally flattened into ordinary
             * buttons. Unsupported actions remain editable buttons with no click event
             * instead of being dropped.
             */
            Geometry geometry = evaluate(legacy, control, targetWidth, targetHeight, density);
            buttons.put(toZalith2Button(
                    control,
                    geometry,
                    targetWidth,
                    targetHeight,
                    manualBindingCount
            ));
        }

        return buildZalith2Document(
                convertedName(legacy.name, "Zalith 2"),
                buttons,
                manualBindingCount[0],
                targetWidth,
                targetHeight
        );
    }

    @NonNull
    private static JSONObject convertDroidBridgeToZalith2(
            @NonNull JSONObject source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            float density
    ) throws Exception {
        TouchControlsLayoutData droidBridge = TouchControlsLayoutData.fromJson(
                source,
                TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE,
                sourceWidth,
                sourceHeight,
                density
        );

        JSONArray buttons = new JSONArray();
        int[] manualBindingCount = new int[]{0};
        float scaleX = targetWidth / (float) Math.max(1, sourceWidth);
        float scaleY = targetHeight / (float) Math.max(1, sourceHeight);
        float sizeScale = targetHeight / (float) Math.max(1, sourceHeight);

        for (TouchControlData control : droidBridge.controls) {
            Geometry sourceGeometry = evaluate(droidBridge, control, sourceWidth, sourceHeight, density);
            String horizontalAnchor = TouchControlData.normalizeHorizontalPositionAnchor(control.positionAnchorX);
            if (horizontalAnchor == null) {
                horizontalAnchor = inferHorizontalAnchor(sourceGeometry.x, sourceGeometry.width, sourceWidth);
            }
            String verticalAnchor = TouchControlData.normalizeVerticalPositionAnchor(control.positionAnchorY);
            if (verticalAnchor == null) {
                verticalAnchor = inferVerticalAnchor(sourceGeometry.y, sourceGeometry.height, sourceHeight);
            }

            float targetControlWidth = Math.max(1f, sourceGeometry.width * sizeScale);
            float targetControlHeight = Math.max(1f, sourceGeometry.height * sizeScale);
            float targetX = mapAnchoredXScaled(
                    sourceGeometry.x,
                    sourceGeometry.width,
                    sourceWidth,
                    targetWidth,
                    scaleX,
                    targetControlWidth,
                    horizontalAnchor
            );
            float targetY = mapAnchoredYScaled(
                    sourceGeometry.y,
                    sourceGeometry.height,
                    sourceHeight,
                    targetHeight,
                    scaleY,
                    targetControlHeight,
                    verticalAnchor
            );

            buttons.put(toZalith2Button(
                    control,
                    new Geometry(targetX, targetY, targetControlWidth, targetControlHeight),
                    targetWidth,
                    targetHeight,
                    manualBindingCount
            ));
        }

        return buildZalith2Document(
                convertedName(droidBridge.name, "Zalith 2"),
                buttons,
                manualBindingCount[0],
                targetWidth,
                targetHeight
        );
    }

    @NonNull
    private static JSONObject buildZalith2Document(
            @NonNull String name,
            @NonNull JSONArray buttons,
            int manualBindingCount,
            int targetWidth,
            int targetHeight
    ) throws Exception {
        JSONObject root = new JSONObject();

        JSONObject info = new JSONObject();
        info.put("name", zalith2LocalizedText(name));
        info.put("author", zalith2LocalizedText("DroidBridge"));
        String description = "Converted by DroidBridge for Zalith 2. "
                + "Positions/sizes are preserved on a " + targetWidth + "x" + targetHeight + " target canvas. "
                + "Drawers are flattened. "
                + (manualBindingCount > 0
                ? manualBindingCount + " control(s) need manual event binding in Zalith 2."
                : "All recognized actions were mapped.");
        info.put("description", zalith2LocalizedText(description));
        info.put("versionCode", 0);
        info.put("versionName", "1.0");
        root.put("info", info);

        JSONArray layers = new JSONArray();
        JSONObject layer = new JSONObject();
        layer.put("name", "DroidBridge Import");
        layer.put("uuid", "db2a11e50001");
        layer.put("hide", false);
        layer.put("visibilityType", "always");
        layer.put("normalButtons", buttons);
        layers.put(layer);
        root.put("layers", layers);

        JSONArray styles = new JSONArray();
        styles.put(buildZalith2DefaultStyle());
        root.put("styles", styles);
        root.put("editorVersion", 10);
        return root;
    }

    @NonNull
    private static JSONObject toZalith2Button(
            @NonNull TouchControlData control,
            @NonNull Geometry geometry,
            int screenWidth,
            int screenHeight,
            @NonNull int[] manualBindingCount
    ) throws Exception {
        JSONObject button = new JSONObject();
        button.put("text", zalith2LocalizedText(control.label));
        button.put("uuid", zalith2Uuid(control.id));

        JSONObject position = new JSONObject();
        position.put("x", zalith2Position(geometry.x, geometry.width, screenWidth));
        position.put("y", zalith2Position(geometry.y, geometry.height, screenHeight));
        button.put("position", position);

        JSONObject buttonSize = new JSONObject();
        buttonSize.put("type", "percentage");
        buttonSize.put("widthDp", 50.0d);
        buttonSize.put("heightDp", 50.0d);
        buttonSize.put("widthPercentage", zalith2SizePercentage(geometry.width, screenHeight));
        buttonSize.put("heightPercentage", zalith2SizePercentage(geometry.height, screenHeight));
        buttonSize.put("widthReference", "screen_height");
        buttonSize.put("heightReference", "screen_height");
        button.put("buttonSize", buttonSize);

        button.put("buttonStyle", ZALITH2_DEFAULT_STYLE_UUID);
        button.put("visibilityType", "always");

        JSONArray events = new JSONArray();
        boolean needsManualBinding = false;

        if (TouchControlActions.KEY.equals(control.action)) {
            int[] codes = control.normalizedKeyCodes();
            if (codes.length == 0) {
                needsManualBinding = true;
            } else {
                for (int code : codes) {
                    String key = glfwKeyName(code);
                    if (key == null) {
                        needsManualBinding = true;
                        continue;
                    }
                    events.put(zalith2Event("key", key));
                }
            }
        } else if (TouchControlActions.MOUSE.equals(control.action)) {
            String mouse = control.mouseButton == 1
                    ? "GLFW_MOUSE_BUTTON_RIGHT"
                    : control.mouseButton == 2
                    ? "GLFW_MOUSE_BUTTON_MIDDLE"
                    : "GLFW_MOUSE_BUTTON_LEFT";
            events.put(zalith2Event("launcher_event", mouse));
        } else if (TouchControlActions.KEYBOARD.equals(control.action)
                || TouchControlActions.KEY_SENDER_KEYBOARD.equals(control.action)) {
            events.put(zalith2Event("launcher_event", "launcher.event.switch_ime"));
        } else {
            /*
             * The supplied ZL2 default establishes no direct equivalent for legacy
             * drawers or DroidBridge-only GUI/menu/scroll/virtual-mouse/screen-swap
             * actions. Preserve the button and let the user bind it in Zalith 2.
             */
            needsManualBinding = true;
        }

        if (events.length() > 0) button.put("clickEvents", events);
        if (needsManualBinding) manualBindingCount[0]++;

        button.put("isSwipple", control.swipeGesture);
        button.put("isPenetrable", control.mousePassThrough);
        button.put("isToggleable", control.toggle);
        return button;
    }

    @NonNull
    private static JSONObject zalith2LocalizedText(@Nullable String text) throws Exception {
        JSONObject localized = new JSONObject();
        localized.put("default", text == null ? "" : text);
        localized.put("matchQueue", new JSONArray());
        return localized;
    }

    @NonNull
    private static JSONObject zalith2Event(@NonNull String type, @NonNull String key) throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", type);
        event.put("key", key);
        return event;
    }

    @NonNull
    private static JSONObject buildZalith2DefaultStyle() throws Exception {
        /*
         * Keep this schema byte-for-byte equivalent (apart from JSON formatting) to
         * Zalith 2's own built-in "default" style from a valid editorVersion 10
         * export. Kotlin serialization used by Zalith 2 treats every StyleConfig
         * field below as required, so do not emit a minimal/custom style here.
         */
        JSONObject style = new JSONObject();
        style.put("name", "default");
        style.put("uuid", ZALITH2_DEFAULT_STYLE_UUID);
        style.put("animateSwap", false);
        style.put("lightStyle", buildZalith2DefaultStyleState());
        style.put("darkStyle", buildZalith2DefaultStyleState());
        return style;
    }

    @NonNull
    private static JSONObject buildZalith2DefaultStyleState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("alpha", 1.0d);
        state.put("pressedAlpha", 1.0d);
        state.put("backgroundColor", -9223372036854775808L);
        state.put("pressedBackgroundColor", -5510004026390872064L);
        state.put("contentColor", -4294967296L);
        state.put("pressedContentColor", -4294967296L);
        state.put("borderWidth", 0);
        state.put("pressedBorderWidth", 0);
        state.put("borderColor", -4294967296L);
        state.put("pressedBorderColor", -4294967296L);
        state.put("borderRadius", zalith2DefaultRadius());
        state.put("pressedBorderRadius", zalith2DefaultRadius());
        return state;
    }

    @NonNull
    private static JSONObject zalith2DefaultRadius() throws Exception {
        JSONObject radius = new JSONObject();
        radius.put("topStart", 0.0d);
        radius.put("topEnd", 0.0d);
        radius.put("bottomEnd", 0.0d);
        radius.put("bottomStart", 0.0d);
        return radius;
    }

    private static int zalith2Position(float topLeft, float size, int screenSize) {
        float available = Math.max(0f, screenSize - size);
        if (available <= 0.0001f) return 0;
        float normalized = clamp(topLeft / available, 0f, 1f);
        return Math.round(normalized * 10000f);
    }

    private static int zalith2SizePercentage(float sizePx, int screenHeight) {
        float safeHeight = Math.max(1f, screenHeight);
        return Math.max(1, Math.round((Math.max(1f, sizePx) / safeHeight) * 10000f));
    }

    @NonNull
    private static String zalith2Uuid(@Nullable String sourceId) {
        String seed = sourceId == null ? "" : sourceId;
        UUID uuid = UUID.nameUUIDFromBytes(("droidbridge-zl2:" + seed).getBytes(StandardCharsets.UTF_8));
        String compact = uuid.toString().replace("-", "");
        return compact.substring(0, Math.min(18, compact.length()));
    }

    @Nullable
    private static String glfwKeyName(int code) {
        if (code >= 48 && code <= 57) return "GLFW_KEY_" + (char) ('0' + (code - 48));
        if (code >= 65 && code <= 90) return "GLFW_KEY_" + (char) ('A' + (code - 65));
        if (code >= 290 && code <= 314) return "GLFW_KEY_F" + (code - 289);
        if (code >= 320 && code <= 329) return "GLFW_KEY_KP_" + (code - 320);

        switch (code) {
            case 32: return "GLFW_KEY_SPACE";
            case 39: return "GLFW_KEY_APOSTROPHE";
            case 44: return "GLFW_KEY_COMMA";
            case 45: return "GLFW_KEY_MINUS";
            case 46: return "GLFW_KEY_PERIOD";
            case 47: return "GLFW_KEY_SLASH";
            case 59: return "GLFW_KEY_SEMICOLON";
            case 61: return "GLFW_KEY_EQUAL";
            case 91: return "GLFW_KEY_LEFT_BRACKET";
            case 92: return "GLFW_KEY_BACKSLASH";
            case 93: return "GLFW_KEY_RIGHT_BRACKET";
            case 96: return "GLFW_KEY_GRAVE_ACCENT";
            case 161: return "GLFW_KEY_WORLD_1";
            case 162: return "GLFW_KEY_WORLD_2";
            case 256: return "GLFW_KEY_ESCAPE";
            case 257: return "GLFW_KEY_ENTER";
            case 258: return "GLFW_KEY_TAB";
            case 259: return "GLFW_KEY_BACKSPACE";
            case 260: return "GLFW_KEY_INSERT";
            case 261: return "GLFW_KEY_DELETE";
            case 262: return "GLFW_KEY_RIGHT";
            case 263: return "GLFW_KEY_LEFT";
            case 264: return "GLFW_KEY_DOWN";
            case 265: return "GLFW_KEY_UP";
            case 266: return "GLFW_KEY_PAGE_UP";
            case 267: return "GLFW_KEY_PAGE_DOWN";
            case 268: return "GLFW_KEY_HOME";
            case 269: return "GLFW_KEY_END";
            case 280: return "GLFW_KEY_CAPS_LOCK";
            case 281: return "GLFW_KEY_SCROLL_LOCK";
            case 282: return "GLFW_KEY_NUM_LOCK";
            case 283: return "GLFW_KEY_PRINT_SCREEN";
            case 284: return "GLFW_KEY_PAUSE";
            case 330: return "GLFW_KEY_KP_DECIMAL";
            case 331: return "GLFW_KEY_KP_DIVIDE";
            case 332: return "GLFW_KEY_KP_MULTIPLY";
            case 333: return "GLFW_KEY_KP_SUBTRACT";
            case 334: return "GLFW_KEY_KP_ADD";
            case 335: return "GLFW_KEY_KP_ENTER";
            case 336: return "GLFW_KEY_KP_EQUAL";
            case 340: return "GLFW_KEY_LEFT_SHIFT";
            case 341: return "GLFW_KEY_LEFT_CONTROL";
            case 342: return "GLFW_KEY_LEFT_ALT";
            case 343: return "GLFW_KEY_LEFT_SUPER";
            case 344: return "GLFW_KEY_RIGHT_SHIFT";
            case 345: return "GLFW_KEY_RIGHT_CONTROL";
            case 346: return "GLFW_KEY_RIGHT_ALT";
            case 347: return "GLFW_KEY_RIGHT_SUPER";
            case 348: return "GLFW_KEY_MENU";
            default: return null;
        }
    }

    private static float mapAnchoredXScaled(
            float sourceX,
            float sourceControlWidth,
            float sourceWidth,
            float targetWidth,
            float scaleX,
            float targetControlWidth,
            @NonNull String anchor
    ) {
        if (TouchControlData.POSITION_ANCHOR_RIGHT.equals(anchor)) {
            float right = sourceWidth - sourceX - sourceControlWidth;
            return targetWidth - (right * scaleX) - targetControlWidth;
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float offset = sourceX + sourceControlWidth / 2f - sourceWidth / 2f;
            return targetWidth / 2f + offset * scaleX - targetControlWidth / 2f;
        }
        return sourceX * scaleX;
    }

    private static float mapAnchoredYScaled(
            float sourceY,
            float sourceControlHeight,
            float sourceHeight,
            float targetHeight,
            float scaleY,
            float targetControlHeight,
            @NonNull String anchor
    ) {
        if (TouchControlData.POSITION_ANCHOR_BOTTOM.equals(anchor)) {
            float bottom = sourceHeight - sourceY - sourceControlHeight;
            return targetHeight - (bottom * scaleY) - targetControlHeight;
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float offset = sourceY + sourceControlHeight / 2f - sourceHeight / 2f;
            return targetHeight / 2f + offset * scaleY - targetControlHeight / 2f;
        }
        return sourceY * scaleY;
    }


    @NonNull
    private static JSONObject toLegacyControl(
            @NonNull TouchControlData control,
            float x,
            float y,
            float widthDp,
            float heightDp,
            float visibleWidthPx,
            float visibleHeightPx,
            int screenWidth,
            int screenHeight,
            @NonNull String horizontalAnchor,
            @NonNull String verticalAnchor,
            float sourceGlobalScale,
            float sourceGlobalOpacity
    ) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", control.id);
        json.put("name", control.label);
        json.put("x", x);
        json.put("y", y);
        json.put("width", widthDp);
        json.put("height", heightDp);
        json.put("dynamicX", legacyDynamicX(x, visibleWidthPx, screenWidth, horizontalAnchor));
        json.put("dynamicY", legacyDynamicY(y, visibleHeightPx, screenHeight, verticalAnchor));

        JSONArray keycodes = new JSONArray();
        int[] codes = legacyKeyCodes(control);
        for (int code : codes) keycodes.put(code);
        json.put("keycodes", keycodes);

        // Legacy Pojav-family files use transparency as 0..100 (0 = opaque).
        // DroidBridge's importer accepts this exact field too, so keeping the native
        // legacy representation avoids relying on DroidBridge-only opacity handling.
        float finalOpacity = clamp(control.opacity * sourceGlobalOpacity, 0f, 1f);
        json.put("transparency", Math.round((1f - finalOpacity) * 100f));
        json.put("cornerRadius", legacyCornerRadiusPercent(control));
        json.put("strokeWidth", Math.max(0f, control.strokeWidth * Math.max(0.01f, sourceGlobalScale)));
        json.put("strokeColor", control.strokeColor);
        json.put("bgColor", control.backgroundColor);
        json.put("isToggle", control.toggle);
        json.put("displayInGame", control.visibleInGame);
        json.put("displayInMenu", control.visibleInMenu);
        json.put("isHideable", !control.visibleWhenControlsHidden);
        json.put("passThruEnabled", control.mousePassThrough);
        json.put("isSwipeable", control.swipeGesture);
        if (control.bitmapTag != null && !control.bitmapTag.trim().isEmpty()) {
            json.put("bitmapTag", control.bitmapTag.trim());
        }

        if (TouchControlActions.JOYSTICK.equals(control.action)) {
            json.put("absolute", control.joystickAbsolute);
            json.put("forwardLock", control.joystickForwardLock);
            json.put("deadzonePercent", TouchControlData.clampJoystickDeadzonePercent(control.joystickDeadzonePercent));
        }
        return json;
    }

    @NonNull
    private static int[] legacyKeyCodes(@NonNull TouchControlData control) {
        if (TouchControlActions.KEY.equals(control.action)) return control.normalizedKeyCodes();
        if (TouchControlActions.TOGGLE_CONTROLS.equals(control.action)) return new int[]{TouchControlData.SPECIAL_TOGGLE_CONTROLS};
        if (TouchControlActions.MOUSE.equals(control.action)) {
            if (control.mouseButton == 1) return new int[]{TouchControlData.SPECIAL_MOUSE_RIGHT};
            if (control.mouseButton == 2) return new int[]{TouchControlData.SPECIAL_MOUSE_MIDDLE};
            return new int[]{TouchControlData.SPECIAL_MOUSE_LEFT};
        }
        if (TouchControlActions.SCROLL.equals(control.action)) {
            return new int[]{control.scrollY < 0 ? TouchControlData.SPECIAL_SCROLL_DOWN : TouchControlData.SPECIAL_SCROLL_UP};
        }
        if (TouchControlActions.MENU.equals(control.action)) return new int[]{TouchControlData.SPECIAL_MENU};
        if (TouchControlActions.KEYBOARD.equals(control.action)) return new int[]{TouchControlData.SPECIAL_KEYBOARD};
        if (TouchControlActions.KEY_SENDER_KEYBOARD.equals(control.action)) return new int[]{TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD};
        if (TouchControlActions.VIRTUAL_MOUSE.equals(control.action)) return new int[]{TouchControlData.SPECIAL_VIRTUAL_MOUSE};
        if (TouchControlActions.DUAL_SCREEN_SWAP.equals(control.action)) return new int[]{TouchControlData.SPECIAL_DUAL_SCREEN_SWAP};
        return new int[]{0};
    }

    private static float legacyCornerRadiusPercent(@NonNull TouchControlData control) {
        float halfShortest = Math.min(Math.max(1f, control.width), Math.max(1f, control.height)) / 2f;
        if (halfShortest <= 0f) return 0f;
        return clamp((control.cornerRadius / halfShortest) * 100f, 0f, 100f);
    }

    @NonNull
    private static String legacyDynamicX(
            float x,
            float visibleWidthPx,
            int screenWidth,
            @NonNull String anchor
    ) {
        float safeScreen = Math.max(1f, screenWidth);
        if (TouchControlData.POSITION_ANCHOR_RIGHT.equals(anchor)) {
            float right = Math.max(0f, safeScreen - x - visibleWidthPx);
            return "${screen_width} - ${width} - (" + ratio(right / safeScreen) + " * ${screen_width})";
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float centerOffset = (x + visibleWidthPx / 2f) - safeScreen / 2f;
            return "(${screen_width} / 2) + (" + ratio(centerOffset / safeScreen)
                    + " * ${screen_width}) - (${width} / 2)";
        }
        return ratio(x / safeScreen) + " * ${screen_width}";
    }

    @NonNull
    private static String legacyDynamicY(
            float y,
            float visibleHeightPx,
            int screenHeight,
            @NonNull String anchor
    ) {
        float safeScreen = Math.max(1f, screenHeight);
        if (TouchControlData.POSITION_ANCHOR_BOTTOM.equals(anchor)) {
            float bottom = Math.max(0f, safeScreen - y - visibleHeightPx);
            return "${screen_height} - ${height} - (" + ratio(bottom / safeScreen) + " * ${screen_height})";
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float centerOffset = (y + visibleHeightPx / 2f) - safeScreen / 2f;
            return "(${screen_height} / 2) + (" + ratio(centerOffset / safeScreen)
                    + " * ${screen_height}) - (${height} / 2)";
        }
        return ratio(y / safeScreen) + " * ${screen_height}";
    }

    @NonNull
    private static Geometry evaluate(
            @NonNull TouchControlsLayoutData layout,
            @NonNull TouchControlData control,
            int screenWidth,
            int screenHeight,
            float density
    ) {
        float baseWidth;
        float baseHeight;
        float sourceCanvasWidth;
        float sourceCanvasHeight;
        float xScale;
        float yScale;
        float formulaPixelScale;
        float formulaDpScale;
        float sourceSizeScale;
        boolean responsiveAnchors;
        float globalScale;

        if (layout.usesOtherLauncherRuntimeRules()) {
            float sizeScale = density * legacySizeRatio(layout);
            baseWidth = Math.max(32f, control.width * sizeScale);
            baseHeight = Math.max(32f, control.height * sizeScale);
            sourceCanvasWidth = screenWidth;
            sourceCanvasHeight = screenHeight;
            xScale = 1f;
            yScale = 1f;
            formulaPixelScale = density;
            formulaDpScale = 1f / density;
            sourceSizeScale = 1f;
            responsiveAnchors = false;
            globalScale = 1f;
        } else if (layout.usesResponsiveCanvas()) {
            sourceCanvasWidth = Math.max(1f, layout.resolvedSourceWidth(screenWidth));
            sourceCanvasHeight = Math.max(1f, layout.resolvedSourceHeight(screenHeight));
            float positionXScale = screenWidth / sourceCanvasWidth;
            float positionYScale = screenHeight / sourceCanvasHeight;
            xScale = positionXScale;
            yScale = positionYScale;
            responsiveAnchors = true;

            if (layout.usesResponsiveDpSizes()) {
                baseWidth = Math.max(32f, control.width * density);
                baseHeight = Math.max(32f, control.height * density);
                formulaPixelScale = Math.min(positionXScale, positionYScale);
                formulaDpScale = density;
                sourceSizeScale = layout.resolvedSourceDensity();
            } else {
                float uniform = clamp(
                        Math.min(positionXScale, positionYScale),
                        MIN_RESPONSIVE_CANVAS_SCALE,
                        MAX_RESPONSIVE_CANVAS_SCALE
                );
                xScale = uniform;
                yScale = uniform;
                baseWidth = Math.max(32f, control.width * uniform);
                baseHeight = Math.max(32f, control.height * uniform);
                formulaPixelScale = uniform;
                formulaDpScale = density;
                sourceSizeScale = 1f;
            }
            globalScale = clamp(layout.globalButtonScalePercent / 100f, 0.5f, 4f);
        } else if (layout.usesPixelCoordinates()) {
            sourceCanvasWidth = Math.max(1f, layout.resolvedSourceWidth(screenWidth));
            sourceCanvasHeight = Math.max(1f, layout.resolvedSourceHeight(screenHeight));
            float uniform = Math.min(screenWidth / sourceCanvasWidth, screenHeight / sourceCanvasHeight);
            xScale = Math.max(0.1f, uniform);
            yScale = Math.max(0.1f, uniform);
            baseWidth = Math.max(32f, control.width * xScale);
            baseHeight = Math.max(32f, control.height * yScale);
            formulaPixelScale = xScale;
            formulaDpScale = density;
            sourceSizeScale = 1f;
            responsiveAnchors = false;
            globalScale = clamp(layout.globalButtonScalePercent / 100f, 0.5f, 4f);
        } else {
            sourceCanvasWidth = screenWidth / density;
            sourceCanvasHeight = screenHeight / density;
            xScale = density;
            yScale = density;
            baseWidth = Math.max(32f, control.width * density);
            baseHeight = Math.max(32f, control.height * density);
            formulaPixelScale = density;
            formulaDpScale = density;
            sourceSizeScale = 1f;
            responsiveAnchors = false;
            globalScale = clamp(layout.globalButtonScalePercent / 100f, 0.5f, 4f);
        }

        if (TouchControlActions.JOYSTICK.equals(control.action)) {
            float square = Math.max(baseWidth, baseHeight);
            baseWidth = square;
            baseHeight = square;
        }

        float fallbackX;
        float fallbackY;
        if (layout.usesOtherLauncherRuntimeRules()) {
            fallbackX = control.x;
            fallbackY = control.y;
        } else if (layout.usesPixelCoordinates()) {
            float sourceControlWidth = Math.max(1f, control.width * sourceSizeScale);
            float sourceControlHeight = Math.max(1f, control.height * sourceSizeScale);
            fallbackX = mapSourceX(
                    control,
                    sourceCanvasWidth,
                    screenWidth,
                    xScale,
                    sourceControlWidth,
                    baseWidth,
                    responsiveAnchors
            );
            fallbackY = mapSourceY(
                    control,
                    sourceCanvasHeight,
                    screenHeight,
                    yScale,
                    sourceControlHeight,
                    baseHeight,
                    responsiveAnchors
            );
        } else {
            fallbackX = control.x * xScale;
            fallbackY = control.y * yScale;
        }

        float baseX = control.rawX == null
                ? fallbackX
                : ExpressionResolver.resolve(
                        control.rawX,
                        fallbackX,
                        screenWidth,
                        screenHeight,
                        baseWidth,
                        baseHeight,
                        density,
                        layout.usesOtherLauncherRuntimeRules()
                                ? layout.globalButtonScalePercent
                                : layout.preferredScale,
                        formulaPixelScale,
                        formulaDpScale
                );
        float baseY = control.rawY == null
                ? fallbackY
                : ExpressionResolver.resolve(
                        control.rawY,
                        fallbackY,
                        screenWidth,
                        screenHeight,
                        baseWidth,
                        baseHeight,
                        density,
                        layout.usesOtherLauncherRuntimeRules()
                                ? layout.globalButtonScalePercent
                                : layout.preferredScale,
                        formulaPixelScale,
                        formulaDpScale
                );

        if (layout.usesOtherLauncherRuntimeRules()) {
            return new Geometry(baseX, baseY, baseWidth, baseHeight);
        }

        float scaledWidth = baseWidth * globalScale;
        float scaledHeight = baseHeight * globalScale;
        float x = scaleAroundParentCenter(baseX, baseWidth, scaledWidth, screenWidth, globalScale);
        float y = scaleAroundParentCenter(baseY, baseHeight, scaledHeight, screenHeight, globalScale);
        return new Geometry(x, y, scaledWidth, scaledHeight);
    }

    private static float mapSourceX(
            @NonNull TouchControlData control,
            float sourceWidth,
            float parentWidth,
            float xScale,
            float sourceControlWidth,
            float screenControlWidth,
            boolean responsiveAnchors
    ) {
        if (!responsiveAnchors) return control.x * xScale;
        String anchor = TouchControlData.normalizeHorizontalPositionAnchor(control.positionAnchorX);
        if (anchor == null) anchor = inferHorizontalAnchor(control.x, sourceControlWidth, sourceWidth);
        if (TouchControlData.POSITION_ANCHOR_RIGHT.equals(anchor)) {
            float rightOffset = sourceWidth - control.x - sourceControlWidth;
            return parentWidth - (rightOffset * xScale) - screenControlWidth;
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float sourceCenterOffset = control.x + sourceControlWidth / 2f - sourceWidth / 2f;
            return parentWidth / 2f + sourceCenterOffset * xScale - screenControlWidth / 2f;
        }
        return control.x * xScale;
    }

    private static float mapSourceY(
            @NonNull TouchControlData control,
            float sourceHeight,
            float parentHeight,
            float yScale,
            float sourceControlHeight,
            float screenControlHeight,
            boolean responsiveAnchors
    ) {
        if (!responsiveAnchors) return control.y * yScale;
        String anchor = TouchControlData.normalizeVerticalPositionAnchor(control.positionAnchorY);
        if (anchor == null) anchor = inferVerticalAnchor(control.y, sourceControlHeight, sourceHeight);
        if (TouchControlData.POSITION_ANCHOR_BOTTOM.equals(anchor)) {
            float bottomOffset = sourceHeight - control.y - sourceControlHeight;
            return parentHeight - (bottomOffset * yScale) - screenControlHeight;
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float sourceCenterOffset = control.y + sourceControlHeight / 2f - sourceHeight / 2f;
            return parentHeight / 2f + sourceCenterOffset * yScale - screenControlHeight / 2f;
        }
        return control.y * yScale;
    }

    private static float scaleAroundParentCenter(
            float base,
            float baseSize,
            float scaledSize,
            float parentSize,
            float scale
    ) {
        float safeScale = scale <= 0.001f ? 1f : scale;
        float parentCenter = parentSize / 2f;
        float baseCenter = base + baseSize / 2f;
        float scaledCenter = parentCenter + ((baseCenter - parentCenter) * safeScale);
        return scaledCenter - scaledSize / 2f;
    }

    private static float mapAnchoredX(
            float sourceX,
            float sourceControlWidth,
            float sourceWidth,
            float targetWidth,
            float scaleX,
            @NonNull String anchor
    ) {
        if (TouchControlData.POSITION_ANCHOR_RIGHT.equals(anchor)) {
            float right = sourceWidth - sourceX - sourceControlWidth;
            return targetWidth - (right * scaleX) - sourceControlWidth;
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float offset = sourceX + sourceControlWidth / 2f - sourceWidth / 2f;
            return targetWidth / 2f + offset * scaleX - sourceControlWidth / 2f;
        }
        return sourceX * scaleX;
    }

    private static float mapAnchoredY(
            float sourceY,
            float sourceControlHeight,
            float sourceHeight,
            float targetHeight,
            float scaleY,
            @NonNull String anchor
    ) {
        if (TouchControlData.POSITION_ANCHOR_BOTTOM.equals(anchor)) {
            float bottom = sourceHeight - sourceY - sourceControlHeight;
            return targetHeight - (bottom * scaleY) - sourceControlHeight;
        }
        if (TouchControlData.POSITION_ANCHOR_CENTER.equals(anchor)) {
            float offset = sourceY + sourceControlHeight / 2f - sourceHeight / 2f;
            return targetHeight / 2f + offset * scaleY - sourceControlHeight / 2f;
        }
        return sourceY * scaleY;
    }

    @NonNull
    private static String inferHorizontalAnchor(float x, float width, float screenWidth) {
        float center = x + width / 2f;
        if (center <= screenWidth * 0.42f) return TouchControlData.POSITION_ANCHOR_LEFT;
        if (center >= screenWidth * 0.58f) return TouchControlData.POSITION_ANCHOR_RIGHT;
        return TouchControlData.POSITION_ANCHOR_CENTER;
    }

    @NonNull
    private static String inferVerticalAnchor(float y, float height, float screenHeight) {
        float center = y + height / 2f;
        if (center <= screenHeight * 0.42f) return TouchControlData.POSITION_ANCHOR_TOP;
        if (center >= screenHeight * 0.58f) return TouchControlData.POSITION_ANCHOR_BOTTOM;
        return TouchControlData.POSITION_ANCHOR_CENTER;
    }

    private static float legacySizeRatio(@NonNull TouchControlsLayoutData layout) {
        float authored = layout.preferredScale > 0f
                && !Float.isNaN(layout.preferredScale)
                && !Float.isInfinite(layout.preferredScale)
                ? layout.preferredScale
                : 100f;
        return Math.max(0.01f, layout.globalButtonScalePercent / authored);
    }

    @NonNull
    private static String legacyDrawerOrientation(@Nullable String orientation) {
        // Pojav/Zalith serialize ControlDrawerData.Orientation as the enum name,
        // not its ordinal. Writing 0..4 causes Gson to deserialize the value as
        // null and Zalith crashes when ControlDrawer.alignButtons() calls ordinal().
        String normalized = TouchControlData.normalizeDrawerOrientation(orientation);
        if ("down".equals(normalized)) return "DOWN";
        if ("left".equals(normalized)) return "LEFT";
        if ("up".equals(normalized)) return "UP";
        if ("free".equals(normalized)) return "FREE";
        return "RIGHT";
    }

    private static void addConversionMetadata(
            @NonNull JSONObject root,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            @NonNull String from,
            @NonNull String to
    ) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("from", from);
        metadata.put("to", to);
        metadata.put("sourceWidth", sourceWidth);
        metadata.put("sourceHeight", sourceHeight);
        metadata.put("targetWidth", targetWidth);
        metadata.put("targetHeight", targetHeight);
        root.put("droidBridgeConversion", metadata);
    }

    @NonNull
    private static String convertedName(@Nullable String original, @NonNull String suffix) {
        String base = original == null ? "" : original.trim();
        if (base.isEmpty()) base = "Touch Controls";
        return base + " (" + suffix + ")";
    }

    @NonNull
    private static String ratio(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0f;
        return String.format(Locale.US, "%.8f", value);
    }

    private static float firstPositiveFloat(
            @NonNull JSONObject root,
            float fallback,
            @NonNull String... keys
    ) {
        for (String key : keys) {
            if (!root.has(key) || root.isNull(key)) continue;
            double value = root.optDouble(key, 0d);
            if (value > 0d && !Double.isNaN(value) && !Double.isInfinite(value)) return (float) value;
        }
        return Math.max(1f, fallback);
    }

    private static int clampLegacyScale(int value) {
        return Math.max(25, Math.min(200, value));
    }

    private static float sanitizeDensity(float density) {
        if (density <= 0f || Float.isNaN(density) || Float.isInfinite(density)) return 1f;
        return density;
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static final class Geometry {
        final float x;
        final float y;
        final float width;
        final float height;

        Geometry(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = Math.max(1f, width);
            this.height = Math.max(1f, height);
        }
    }
}
