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

import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Resolves the nine Minecraft hotbar key bindings from the active options.txt.
 *
 * Modern Minecraft stores values such as key.keyboard.b / key.mouse.left while
 * LWJGL2-era versions store integer Keyboard/Mouse codes.  The touch hotbar lives
 * in Android, outside Minecraft's JVM, so reading the same options.txt is the one
 * cross-version source of truth that works for modern, legacy and BTA profiles.
 */
public final class MinecraftHotbarBindingResolver {
    private static final long READ_THROTTLE_MS = 500L;
    private static final int SLOT_COUNT = 9;

    private static final int TYPE_NONE = 0;
    private static final int TYPE_KEY = 1;
    private static final int TYPE_MOUSE = 2;

    @Nullable private static File lastFile;
    private static long lastReadAtMs;
    private static long lastModified;
    @NonNull private static Binding[] cachedBindings = defaults();

    private MinecraftHotbarBindingResolver() {
    }

    public static void clearCache() {
        lastFile = null;
        lastReadAtMs = 0L;
        lastModified = 0L;
        cachedBindings = defaults();
    }

    /**
     * Sends the key/mouse binding currently assigned to the requested hotbar slot.
     * Falls back to vanilla 1..9 only when options.txt cannot be read or does not
     * contain a value for that slot. An explicitly unbound slot remains unbound.
     */
    public static boolean sendConfiguredHotbarTap(@Nullable File optionsFile, int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return false;
        Binding binding = resolve(optionsFile, slot);
        if (binding.type == TYPE_NONE) return true;

        CallbackBridge.setInputReady(true);
        if (binding.type == TYPE_MOUSE) {
            CallbackBridge.sendMouseButton(binding.code, true);
            CallbackBridge.sendMouseButton(binding.code, false);
            return true;
        }

        CallbackBridge.setModifiers(binding.code, true);
        CallbackBridge.sendKeyPress(binding.code, CallbackBridge.getCurrentMods(), true);
        CallbackBridge.sendKeyPress(binding.code, CallbackBridge.getCurrentMods(), false);
        CallbackBridge.setModifiers(binding.code, false);
        return true;
    }

    @NonNull
    private static Binding resolve(@Nullable File optionsFile, int slot) {
        readBindingsCached(optionsFile);
        Binding binding = cachedBindings[slot];
        return binding == null ? defaultBinding(slot) : binding;
    }

    private static void readBindingsCached(@Nullable File optionsFile) {
        if (optionsFile == null || !optionsFile.isFile()) {
            lastFile = optionsFile;
            cachedBindings = defaults();
            lastModified = 0L;
            lastReadAtMs = System.currentTimeMillis();
            return;
        }

        long now = System.currentTimeMillis();
        long modified = safeLastModified(optionsFile);
        if (sameFile(optionsFile, lastFile)
                && modified == lastModified
                && now - lastReadAtMs < READ_THROTTLE_MS) {
            return;
        }

        lastFile = optionsFile;
        lastModified = modified;
        lastReadAtMs = now;
        cachedBindings = readBindings(optionsFile);
    }

    @NonNull
    private static Binding[] readBindings(@NonNull File optionsFile) {
        Binding[] result = defaults();

        try (BufferedReader reader = new BufferedReader(new FileReader(optionsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                int delimiter = trimmed.indexOf(':');
                if (delimiter < 0) delimiter = trimmed.indexOf('=');
                if (delimiter <= 0 || delimiter >= trimmed.length() - 1) continue;

                String name = trimmed.substring(0, delimiter).trim();
                int slot = hotbarSlotFromName(name);
                if (slot < 0) continue;

                String rawValue = trimmed.substring(delimiter + 1).trim();
                Binding parsed = parseBinding(rawValue);
                if (parsed != null) {
                    result[slot] = parsed;
                }
            }
        } catch (Throwable ignored) {
            return defaults();
        }

        // Missing rows retain their vanilla fallback. Rows that were explicitly
        // present but unknown/unbound are represented by Binding.none().
        return result;
    }

    private static int hotbarSlotFromName(@Nullable String rawName) {
        if (rawName == null) return -1;
        String name = rawName.trim().toLowerCase(Locale.ROOT);

        String[] prefixes = new String[] {
                "key_key.hotbar.",
                "key.hotbar.",
                "key_hotbar."
        };
        for (String prefix : prefixes) {
            if (!name.startsWith(prefix)) continue;
            try {
                int oneBased = Integer.parseInt(name.substring(prefix.length()).trim());
                return oneBased >= 1 && oneBased <= SLOT_COUNT ? oneBased - 1 : -1;
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    @Nullable
    private static Binding parseBinding(@Nullable String rawValue) {
        if (rawValue == null) return null;
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;

        if (value.startsWith("key.keyboard.")) {
            return parseModernKeyboard(value.substring("key.keyboard.".length()));
        }
        if (value.startsWith("keyboard.")) {
            return parseModernKeyboard(value.substring("keyboard.".length()));
        }
        if (value.startsWith("key.mouse.")) {
            return parseModernMouse(value.substring("key.mouse.".length()));
        }
        if (value.startsWith("mouse.")) {
            return parseModernMouse(value.substring("mouse.".length()));
        }
        if (value.startsWith("key.scancode.") || value.startsWith("scancode.")) {
            // GLFW scancodes are platform dependent and cannot be safely converted
            // into a key token here. Keep the slot unbound rather than sending 1..9.
            return Binding.none();
        }

        try {
            return parseLegacyCode(Integer.parseInt(value));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static Binding parseModernKeyboard(@NonNull String keyName) {
        String name = keyName.trim().toLowerCase(Locale.ROOT);
        if (name.equals("unknown") || name.equals("none")) return Binding.none();

        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'a' && c <= 'z') return Binding.key(Character.toUpperCase(c));
            if (c >= '0' && c <= '9') return Binding.key(c);
            switch (c) {
                case ' ': return Binding.key(32);
                case '\'': return Binding.key(39);
                case ',': return Binding.key(44);
                case '-': return Binding.key(45);
                case '.': return Binding.key(46);
                case '/': return Binding.key(47);
                case ';': return Binding.key(59);
                case '=': return Binding.key(61);
                case '[': return Binding.key(91);
                case '\\': return Binding.key(92);
                case ']': return Binding.key(93);
                case '`': return Binding.key(96);
                default: break;
            }
        }

        if (name.startsWith("f")) {
            try {
                int f = Integer.parseInt(name.substring(1));
                if (f >= 1 && f <= 25) return Binding.key(289 + f);
            } catch (Throwable ignored) {
            }
        }

        if (name.startsWith("keypad.")) {
            String keypad = name.substring("keypad.".length());
            switch (keypad) {
                case "0": return Binding.key(320);
                case "1": return Binding.key(321);
                case "2": return Binding.key(322);
                case "3": return Binding.key(323);
                case "4": return Binding.key(324);
                case "5": return Binding.key(325);
                case "6": return Binding.key(326);
                case "7": return Binding.key(327);
                case "8": return Binding.key(328);
                case "9": return Binding.key(329);
                case "decimal": return Binding.key(330);
                case "divide": return Binding.key(331);
                case "multiply": return Binding.key(332);
                case "subtract": return Binding.key(333);
                case "add": return Binding.key(334);
                case "enter": return Binding.key(335);
                case "equal": return Binding.key(336);
                default: return Binding.none();
            }
        }

        switch (name) {
            case "space": return Binding.key(32);
            case "apostrophe": return Binding.key(39);
            case "comma": return Binding.key(44);
            case "minus": return Binding.key(45);
            case "period": return Binding.key(46);
            case "slash": return Binding.key(47);
            case "semicolon": return Binding.key(59);
            case "equal": return Binding.key(61);
            case "left.bracket": return Binding.key(91);
            case "backslash": return Binding.key(92);
            case "right.bracket": return Binding.key(93);
            case "grave.accent": return Binding.key(96);
            case "escape": return Binding.key(256);
            case "enter": return Binding.key(257);
            case "tab": return Binding.key(258);
            case "backspace": return Binding.key(259);
            case "insert": return Binding.key(260);
            case "delete": return Binding.key(261);
            case "right": return Binding.key(262);
            case "left": return Binding.key(263);
            case "down": return Binding.key(264);
            case "up": return Binding.key(265);
            case "page.up": return Binding.key(266);
            case "page.down": return Binding.key(267);
            case "home": return Binding.key(268);
            case "end": return Binding.key(269);
            case "caps.lock": return Binding.key(280);
            case "scroll.lock": return Binding.key(281);
            case "num.lock": return Binding.key(282);
            case "print.screen": return Binding.key(283);
            case "pause": return Binding.key(284);
            case "left.shift": return Binding.key(340);
            case "left.control": return Binding.key(341);
            case "left.alt": return Binding.key(342);
            case "left.win":
            case "left.super": return Binding.key(343);
            case "right.shift": return Binding.key(344);
            case "right.control": return Binding.key(345);
            case "right.alt": return Binding.key(346);
            case "right.win":
            case "right.super": return Binding.key(347);
            case "menu": return Binding.key(348);
            default: return Binding.none();
        }
    }

    @NonNull
    private static Binding parseModernMouse(@NonNull String mouseName) {
        String name = mouseName.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "left": return Binding.mouse(0);
            case "right": return Binding.mouse(1);
            case "middle": return Binding.mouse(2);
            case "unknown":
            case "none": return Binding.none();
            default:
                try {
                    int oneBased = Integer.parseInt(name);
                    return oneBased > 0 ? Binding.mouse(oneBased - 1) : Binding.none();
                } catch (Throwable ignored) {
                    return Binding.none();
                }
        }
    }

    @NonNull
    private static Binding parseLegacyCode(int legacyCode) {
        // Legacy KeyBinding stores mouse buttons as button - 100:
        // left=-100, right=-99, middle=-98, etc.
        if (legacyCode < 0) {
            int mouseButton = legacyCode + 100;
            return mouseButton >= 0 ? Binding.mouse(mouseButton) : Binding.none();
        }
        if (legacyCode == 0) return Binding.none();

        int glfw = legacyKeyboardToGlfw(legacyCode);
        return glfw > 0 ? Binding.key(glfw) : Binding.none();
    }

    private static int legacyKeyboardToGlfw(int code) {
        if (code >= 2 && code <= 10) return 47 + code; // 2..10 => 1..9
        if (code == 11) return 48; // 0
        if (code >= 59 && code <= 68) return 231 + code; // F1..F10 => 290..299
        if (code >= 100 && code <= 102) return 202 + code; // F13..F15 => 302..304

        switch (code) {
            case 1: return 256;
            case 12: return 45;
            case 13: return 61;
            case 14: return 259;
            case 15: return 258;
            case 16: return 81;
            case 17: return 87;
            case 18: return 69;
            case 19: return 82;
            case 20: return 84;
            case 21: return 89;
            case 22: return 85;
            case 23: return 73;
            case 24: return 79;
            case 25: return 80;
            case 26: return 91;
            case 27: return 93;
            case 28: return 257;
            case 29: return 341;
            case 30: return 65;
            case 31: return 83;
            case 32: return 68;
            case 33: return 70;
            case 34: return 71;
            case 35: return 72;
            case 36: return 74;
            case 37: return 75;
            case 38: return 76;
            case 39: return 59;
            case 40: return 39;
            case 41: return 96;
            case 42: return 340;
            case 43: return 92;
            case 44: return 90;
            case 45: return 88;
            case 46: return 67;
            case 47: return 86;
            case 48: return 66;
            case 49: return 78;
            case 50: return 77;
            case 51: return 44;
            case 52: return 46;
            case 53: return 47;
            case 54: return 344;
            case 55: return 332;
            case 56: return 342;
            case 57: return 32;
            case 58: return 280;
            case 69: return 282;
            case 70: return 281;
            case 71: return 327;
            case 72: return 328;
            case 73: return 329;
            case 74: return 333;
            case 75: return 324;
            case 76: return 325;
            case 77: return 326;
            case 78: return 334;
            case 79: return 321;
            case 80: return 322;
            case 81: return 323;
            case 82: return 320;
            case 83: return 330;
            case 87: return 300;
            case 88: return 301;
            case 141: return 336;
            case 156: return 335;
            case 157: return 345;
            case 181: return 331;
            case 183: return 283;
            case 184: return 346;
            case 197: return 284;
            case 199: return 268;
            case 200: return 265;
            case 201: return 266;
            case 203: return 263;
            case 205: return 262;
            case 207: return 269;
            case 208: return 264;
            case 209: return 267;
            case 210: return 260;
            case 211: return 261;
            case 219: return 343;
            case 220: return 347;
            case 221: return 348;
            default: return -1;
        }
    }

    @NonNull
    private static Binding[] defaults() {
        Binding[] bindings = new Binding[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) bindings[i] = defaultBinding(i);
        return bindings;
    }

    @NonNull
    private static Binding defaultBinding(int slot) {
        return Binding.key(49 + Math.max(0, Math.min(SLOT_COUNT - 1, slot)));
    }

    private static long safeLastModified(@NonNull File file) {
        try {
            return file.lastModified();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean sameFile(@Nullable File a, @Nullable File b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private static final class Binding {
        final int type;
        final int code;

        Binding(int type, int code) {
            this.type = type;
            this.code = code;
        }

        @NonNull
        static Binding none() {
            return new Binding(TYPE_NONE, -1);
        }

        @NonNull
        static Binding key(int code) {
            return new Binding(TYPE_KEY, code);
        }

        @NonNull
        static Binding mouse(int button) {
            return new Binding(TYPE_MOUSE, button);
        }
    }
}
