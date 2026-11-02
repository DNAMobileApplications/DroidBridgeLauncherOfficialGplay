/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */
package ca.dnamobile.droidbridgelauncher.controls;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;

import java.util.Locale;

/** Small dependency-free HSV colour wheel used by the touch-control editor. */
final class TouchColorPickerDialog {
    interface Listener {
        void onColorPicked(int color);
    }

    private TouchColorPickerDialog() {
    }

    static void show(
            @NonNull Context context,
            @NonNull String title,
            int initialColor,
            @NonNull Listener listener
    ) {
        show(context, title, initialColor, true, false, listener);
    }

    static void show(
            @NonNull Context context,
            @NonNull String title,
            int initialColor,
            boolean allowAlpha,
            @NonNull Listener listener
    ) {
        show(context, title, initialColor, allowAlpha, false, listener);
    }

    static void show(
            @NonNull Context context,
            @NonNull String title,
            int initialColor,
            boolean allowAlpha,
            boolean allowClear,
            @NonNull Listener listener
    ) {
        float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);
        final float[] hue = new float[]{hsv[0]};
        final float[] saturation = new float[]{hsv[1]};
        final float[] value = new float[]{hsv[2]};
        // Button fill colour and control opacity are separate concepts. Callers that
        // disable alpha start at fully opaque so a previously transparent legacy fill
        // cannot keep a newly selected hue invisible.
        final int[] alpha = new int[]{allowAlpha ? Color.alpha(initialColor) : 255};

        int pad = dp(context, 18f);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(context, 8f), pad, dp(context, 4f));

        ColorWheelView wheel = new ColorWheelView(context);
        wheel.setSelection(hue[0], saturation[0]);

        // Size the wheel from BOTH axes. The old width-only calculation could choose
        // a 300+dp wheel on a landscape/short display, leaving MaterialDialog to clip
        // the top and bottom. Keep the wheel comfortably inside the current display
        // and let the surrounding ScrollView handle unusually small displays.
        int screenWidth = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
        int screenHeight = Math.max(1, context.getResources().getDisplayMetrics().heightPixels);
        int widthBudget = Math.max(dp(context, 96f),
                screenWidth - (pad * 2) - dp(context, 32f));
        float heightFraction = screenWidth > screenHeight ? 0.34f : 0.43f;
        int heightBudget = Math.max(dp(context, 96f), Math.round(screenHeight * heightFraction));
        int wheelSize = Math.min(dp(context, 300f), Math.min(widthBudget, heightBudget));
        LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(wheelSize, wheelSize);
        wheelParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(wheel, wheelParams);

        View swatch = new View(context);
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 42f)
        );
        swatchParams.topMargin = dp(context, 12f);
        root.addView(swatch, swatchParams);

        TextView hexValue = new TextView(context);
        hexValue.setGravity(Gravity.CENTER);
        hexValue.setTextSize(14f);
        hexValue.setPadding(0, dp(context, 7f), 0, dp(context, 6f));
        root.addView(hexValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView brightnessLabel = label(context, "Brightness");
        root.addView(brightnessLabel);
        SeekBar brightness = new SeekBar(context);
        brightness.setMax(100);
        brightness.setProgress(Math.round(value[0] * 100f));
        root.addView(brightness, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView alphaLabel = null;
        SeekBar alphaBar = null;
        if (allowAlpha) {
            alphaLabel = label(context, "Opacity");
            root.addView(alphaLabel);
            alphaBar = new SeekBar(context);
            alphaBar.setMax(255);
            alphaBar.setProgress(alpha[0]);
            root.addView(alphaBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        final TextView alphaLabelFinal = alphaLabel;

        Runnable refresh = () -> {
            int color = Color.HSVToColor(alpha[0], new float[]{hue[0], saturation[0], value[0]});
            GradientDrawable swatchBg = new GradientDrawable();
            swatchBg.setCornerRadius(dp(context, 10f));
            swatchBg.setColor(color);
            swatchBg.setStroke(Math.max(1, dp(context, 1f)), 0x66FFFFFF);
            swatch.setBackground(swatchBg);
            hexValue.setText(String.format(Locale.US, "#%08X", color));
            hexValue.setTextColor(0xFFE0E0E0);
            brightnessLabel.setText("Brightness: " + Math.round(value[0] * 100f) + "%");
            if (alphaLabelFinal != null) {
                alphaLabelFinal.setText("Opacity: " + Math.round((alpha[0] / 255f) * 100f) + "%");
            }
        };

        // Legacy/default button fills are commonly black (and sometimes transparent
        // black). In HSV that means V=0, so changing only hue/saturation on the wheel
        // still produces black. On the first wheel interaction, lift a black/near-black
        // starting colour to full brightness so choosing blue/red/etc. actually selects
        // that visible colour. The user can still darken it afterward with the
        // Brightness slider.
        final boolean[] autoLiftBlackOnFirstWheelPick = new boolean[]{value[0] <= 0.08f};
        wheel.setListener((selectedHue, selectedSaturation) -> {
            hue[0] = selectedHue;
            saturation[0] = selectedSaturation;
            if (autoLiftBlackOnFirstWheelPick[0]) {
                autoLiftBlackOnFirstWheelPick[0] = false;
                value[0] = 1f;
                if (brightness.getProgress() != 100) {
                    brightness.setProgress(100);
                }
            }
            refresh.run();
        });

        brightness.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value[0] = Math.max(0f, Math.min(1f, progress / 100f));
                refresh.run();
            }
        });
        if (alphaBar != null) {
            alphaBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    alpha[0] = Math.max(0, Math.min(255, progress));
                    refresh.run();
                }
            });
        }
        refresh.run();

        // MaterialAlertDialog does not automatically make a tall custom view scroll.
        // Wrapping the contents prevents clipping on short landscape displays while
        // preserving the same layout on larger portrait/tablet screens.
        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        MaterialAlertDialogBuilder builder = LauncherDialogStyle.builder(context)
                .setTitle(title)
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use colour", (dialog, which) -> listener.onColorPicked(
                        Color.HSVToColor(alpha[0], new float[]{hue[0], saturation[0], value[0]})
                ));
        if (allowClear) {
            builder.setNeutralButton("Clear fill",
                    (dialog, which) -> listener.onColorPicked(Color.TRANSPARENT));
        }
        builder.show();
    }

    @NonNull
    private static TextView label(@NonNull Context context, @NonNull String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xFFE0E0E0);
        view.setTextSize(13f);
        view.setPadding(0, dp(context, 8f), 0, 0);
        return view;
    }

    private static int dp(@NonNull Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    private static final class ColorWheelView extends View {
        interface SelectionListener {
            void onSelected(float hue, float saturation);
        }

        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint markerOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerInner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bitmap;
        private float hue;
        private float saturation;
        private SelectionListener listener;

        ColorWheelView(@NonNull Context context) {
            super(context);
            markerOuter.setStyle(Paint.Style.STROKE);
            markerOuter.setStrokeWidth(dp(context, 3f));
            markerOuter.setColor(Color.BLACK);
            markerInner.setStyle(Paint.Style.STROKE);
            markerInner.setStrokeWidth(dp(context, 1.5f));
            markerInner.setColor(Color.WHITE);
            setClickable(true);
        }

        void setSelection(float hue, float saturation) {
            this.hue = normalizeHue(hue);
            this.saturation = Math.max(0f, Math.min(1f, saturation));
            invalidate();
        }

        void setListener(SelectionListener listener) {
            this.listener = listener;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
            if (w <= 0 || h <= 0) return;
            bitmap = buildWheelBitmap(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap != null) canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.max(1f, (Math.min(getWidth(), getHeight()) / 2f) - dp(getContext(), 3f));
            double radians = Math.toRadians(hue);
            float x = cx + ((float) Math.cos(radians) * radius * saturation);
            float y = cy + ((float) Math.sin(radians) * radius * saturation);
            float markerRadius = dp(getContext(), 8f);
            canvas.drawCircle(x, y, markerRadius, markerOuter);
            canvas.drawCircle(x, y, markerRadius, markerInner);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    updateFromTouch(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    updateFromTouch(event.getX(), event.getY());
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private void updateFromTouch(float x, float y) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float dx = x - cx;
            float dy = y - cy;
            float radius = Math.max(1f, (Math.min(getWidth(), getHeight()) / 2f) - dp(getContext(), 3f));
            float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
            saturation = Math.max(0f, Math.min(1f, distance / radius));
            hue = normalizeHue((float) Math.toDegrees(Math.atan2(dy, dx)));
            if (listener != null) listener.onSelected(hue, saturation);
            invalidate();
        }

        private static Bitmap buildWheelBitmap(int width, int height) {
            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];
            float cx = width / 2f;
            float cy = height / 2f;
            float radius = Math.max(1f, Math.min(width, height) / 2f);
            float[] hsv = new float[]{0f, 0f, 1f};
            int index = 0;
            for (int y = 0; y < height; y++) {
                float dy = y - cy;
                for (int x = 0; x < width; x++, index++) {
                    float dx = x - cx;
                    float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
                    if (distance > radius) {
                        pixels[index] = Color.TRANSPARENT;
                        continue;
                    }
                    hsv[0] = normalizeHue((float) Math.toDegrees(Math.atan2(dy, dx)));
                    hsv[1] = Math.max(0f, Math.min(1f, distance / radius));
                    pixels[index] = Color.HSVToColor(hsv);
                }
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height);
            return output;
        }

        private static float normalizeHue(float hue) {
            float normalized = hue % 360f;
            return normalized < 0f ? normalized + 360f : normalized;
        }
    }
}
