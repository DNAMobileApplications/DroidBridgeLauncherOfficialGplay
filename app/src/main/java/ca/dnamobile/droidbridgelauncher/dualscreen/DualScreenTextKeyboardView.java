/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.reflect.Method;

import ca.dnamobile.droidbridgelauncher.R;

/**
 * Bottom-screen text keyboard that talks directly to Minecraft's GLFW bridge.
 *
 * This is intentionally NOT an Android IME. It owns no EditText, never asks
 * InputMethodManager to show, and therefore cannot steal the system keyboard/focus.
 */
final class DualScreenTextKeyboardView extends FrameLayout {
    interface Listener {
        void onCloseRequested();
        void onSubmitted();
    }

    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_BACKSPACE = 259;
    private static final int GLFW_KEY_ESCAPE = 256;

    @NonNull private final Listener listener;
    @NonNull private final DualScreenControlsView fontRenderer;
    private boolean shift;
    private boolean symbols;

    DualScreenTextKeyboardView(@NonNull Context context,
                               @NonNull DualScreenControlsView fontRenderer,
                               @NonNull Listener listener) {
        super(context);
        this.fontRenderer = fontRenderer;
        this.listener = listener;
        setClickable(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setBackgroundColor(Color.argb(205, 18, 18, 18));
        rebuild();
    }

    private void rebuild() {
        removeAllViews();

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(makePanelBackground());
        addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(getContext());
        title.setText("Minecraft Keyboard");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MinecraftKeyButton close = makeKey("Close");
        close.setOnClickListener(v -> listener.onCloseRequested());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(100), dp(40)));
        panel.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView hint = new TextView(getContext());
        hint.setText("Direct Minecraft text input - Android keyboard stays untouched");
        hint.setTextColor(Color.rgb(214, 214, 214));
        hint.setTextSize(10.5f);
        hint.setPadding(0, dp(2), 0, dp(6));
        panel.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (symbols) {
            addCharRow(panel, "1234567890");
            addCharRow(panel, "-_=+[]{}\\|");
            addCharRow(panel, ";:'\"<>,.?/");
            addCharRow(panel, "!@#$%^&*()`~");
        } else {
            addCharRow(panel, "1234567890");
            addCharRow(panel, shift ? "QWERTYUIOP" : "qwertyuiop");
            addCharRow(panel, shift ? "ASDFGHJKL" : "asdfghjkl");
            addCharRow(panel, shift ? "ZXCVBNM" : "zxcvbnm");
        }

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);

        MinecraftKeyButton shiftButton = makeKey(shift ? "Shift ON" : "Shift");
        shiftButton.setOnClickListener(v -> {
            shift = !shift;
            if (symbols) symbols = false;
            rebuild();
        });
        addWeighted(bottom, shiftButton, 1.5f);

        MinecraftKeyButton symbolsButton = makeKey(symbols ? "ABC" : "123!?");
        symbolsButton.setOnClickListener(v -> {
            symbols = !symbols;
            rebuild();
        });
        addWeighted(bottom, symbolsButton, 1.35f);

        MinecraftKeyButton space = makeKey("Space");
        space.setOnClickListener(v -> sendChar(' '));
        addWeighted(bottom, space, 3.6f);

        MinecraftKeyButton back = makeKey("Back");
        back.setOnClickListener(v -> sendKeyTap(GLFW_KEY_BACKSPACE));
        addWeighted(bottom, back, 1.35f);

        MinecraftKeyButton enter = makeKey("Enter");
        enter.setOnClickListener(v -> {
            sendKeyTap(GLFW_KEY_ENTER);
            listener.onSubmitted();
        });
        addWeighted(bottom, enter, 1.55f);

        panel.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        ));
    }

    private void addCharRow(@NonNull LinearLayout parent, @NonNull String characters) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < characters.length(); i++) {
            final char c = characters.charAt(i);
            MinecraftKeyButton key = makeKey(String.valueOf(c));
            key.setOnClickListener(v -> {
                sendChar(c);
                if (shift && Character.isLetter(c)) {
                    shift = false;
                    rebuild();
                }
            });
            addWeighted(row, key, 1f);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
    }

    private void addWeighted(@NonNull LinearLayout row, @NonNull View key, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(key, params);
    }

    @NonNull
    private MinecraftKeyButton makeKey(@NonNull String label) {
        return new MinecraftKeyButton(getContext(), fontRenderer, label);
    }

    @NonNull
    private GradientDrawable makePanelBackground() {
        GradientDrawable panel = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF0A09CA0, 0xF07B7B7B}
        );
        panel.setCornerRadius(0f);
        panel.setStroke(dp(3), 0xFF454545);
        return panel;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Custom bottom-screen key. It uses the exact supplied Legacy button bitmap and delegates
     * all label rendering to the Minecraft ascii.png renderer in DualScreenControlsView.
     */
    private static final class MinecraftKeyButton extends View {
        @NonNull private final DualScreenControlsView fontRenderer;
        @NonNull private final String label;
        @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @NonNull private final RectF target = new RectF();
        @Nullable private Bitmap chrome;

        MinecraftKeyButton(@NonNull Context context,
                           @NonNull DualScreenControlsView fontRenderer,
                           @NonNull String label) {
            super(context);
            this.fontRenderer = fontRenderer;
            this.label = label;
            setClickable(true);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setWillNotDraw(false);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            chrome = BitmapFactory.decodeResource(
                    getResources(),
                    R.drawable.droidbridge_legacy_button,
                    options
            );
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            target.set(0f, 0f, getWidth(), getHeight());

            Bitmap bitmap = chrome;
            if (bitmap != null && !bitmap.isRecycled()) {
                paint.setFilterBitmap(false);
                paint.setDither(false);
                canvas.drawBitmap(bitmap, null, target, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(142, 142, 142));
                canvas.drawRect(target, paint);
            }

            if (isPressed()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(62, 0, 0, 0));
                canvas.drawRect(target, paint);
            }

            RectF labelBounds = new RectF(
                    getWidth() * 0.06f,
                    getHeight() * 0.08f,
                    getWidth() * 0.94f,
                    getHeight() * 0.92f
            );
            fontRenderer.drawMinecraftAsciiOverlayLabel(canvas, label, labelBounds, Color.WHITE);
        }

        @Override
        protected void onDetachedFromWindow() {
            Bitmap bitmap = chrome;
            chrome = null;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            super.onDetachedFromWindow();
        }
    }

    static void openMinecraftChatIfNeeded() {
        if (!CallbackBridge.isGrabbing()) return;
        sendKeyTap(84); // GLFW_KEY_T
    }

    private static void sendKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable ignored) {
        }
    }

    private static void sendChar(char c) {
        try {
            CallbackBridge.setInputReady(true);
        } catch (Throwable ignored) {
        }
        if (sendCharByReflection(c)) return;

        // Conservative fallback for bridges without a char callback.
        if (c == '\n' || c == '\r') {
            sendKeyTap(GLFW_KEY_ENTER);
        } else if (c == '\b') {
            sendKeyTap(GLFW_KEY_BACKSPACE);
        } else if (c == 27) {
            sendKeyTap(GLFW_KEY_ESCAPE);
        } else {
            int key = keyCodeForChar(c);
            if (key > 0) sendKeyTap(key);
        }
    }

    private static boolean sendCharByReflection(char c) {
        Class<?> clazz = CallbackBridge.class;
        Object[][] candidates = new Object[][]{
                {"sendChar", new Class[]{char.class, int.class}, new Object[]{c, CallbackBridge.getCurrentMods()}},
                {"sendChar", new Class[]{char.class, int.class}, new Object[]{c, 0}},
                {"sendChar", new Class[]{char.class}, new Object[]{c}},
                {"sendCharMods", new Class[]{int.class, int.class}, new Object[]{(int) c, CallbackBridge.getCurrentMods()}},
                {"sendCharMods", new Class[]{char.class, int.class}, new Object[]{c, CallbackBridge.getCurrentMods()}},
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class},
                        new Object[]{0, c, 0, CallbackBridge.getCurrentMods(), true}}
        };
        for (Object[] candidate : candidates) {
            try {
                Method method = clazz.getMethod((String) candidate[0], (Class<?>[]) candidate[1]);
                method.invoke(null, (Object[]) candidate[2]);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static int keyCodeForChar(char c) {
        if (c >= 'a' && c <= 'z') return Character.toUpperCase(c);
        if (c >= 'A' && c <= 'Z') return c;
        if (c >= '0' && c <= '9') return c;
        switch (c) {
            case ' ': return 32;
            case '\'': return 39;
            case ',': return 44;
            case '-': return 45;
            case '.': return 46;
            case '/': return 47;
            case ';': return 59;
            case '=': return 61;
            case '[': return 91;
            case '\\': return 92;
            case ']': return 93;
            case '`': return 96;
            default: return 0;
        }
    }
}
