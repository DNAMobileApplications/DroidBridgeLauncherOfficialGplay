/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.seasonal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.Random;

/** One short, transparent snowfall sequence used in November and January-March. */
final class SeasonalSnowfallView extends View {
    private static final long DURATION_MS = 2400L;
    private static final long FADE_OUT_MS = 320L;
    private static final int FLAKE_COUNT = 54;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Flake[] flakes = new Flake[FLAKE_COUNT];
    @NonNull
    private final Runnable onFinished;
    private long startedAtMs;
    private boolean finished;

    SeasonalSnowfallView(@NonNull Context context, @NonNull Runnable onFinished) {
        super(context);
        this.onFinished = onFinished;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);

        Random random = new Random(System.nanoTime());
        for (int i = 0; i < flakes.length; i++) {
            Flake flake = new Flake();
            flake.x = random.nextFloat();
            flake.y = random.nextFloat() * 1.35f - 0.35f;
            flake.speed = 0.16f + random.nextFloat() * 0.24f;
            flake.drift = (random.nextFloat() - 0.5f) * 0.055f;
            flake.radiusDp = 2.4f + random.nextFloat() * 5.6f;
            flake.alpha = 135 + random.nextInt(121);
            flake.phase = random.nextFloat() * 6.2831855f;
            flakes[i] = flake;
        }
        setWillNotDraw(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAtMs = SystemClock.uptimeMillis();
        finished = false;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (finished || getWidth() <= 0 || getHeight() <= 0) return;

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - startedAtMs);
        float seconds = elapsed / 1000f;
        float density = getResources().getDisplayMetrics().density;
        float fade = elapsed > DURATION_MS - FADE_OUT_MS
                ? Math.max(0f, (DURATION_MS - elapsed) / (float) FADE_OUT_MS)
                : 1f;

        for (Flake flake : flakes) {
            float wrappedY = (flake.y + flake.speed * seconds + 0.35f) % 1.35f;
            float y = (wrappedY - 0.20f) * getHeight();
            float x = (flake.x
                    + flake.drift * seconds
                    + 0.012f * (float) Math.sin(flake.phase + seconds * 2.2f)) * getWidth();
            if (x < -20f) x += getWidth() + 40f;
            if (x > getWidth() + 20f) x -= getWidth() + 40f;

            float radius = flake.radiusDp * density;
            paint.setStrokeWidth(Math.max(1f, radius * 0.14f));
            paint.setColor(Color.rgb(218, 239, 252));
            paint.setAlpha(Math.max(0, Math.min(255, Math.round(flake.alpha * fade))));
            drawSnowflake(canvas, x, y, radius, paint);
        }

        if (elapsed >= DURATION_MS) {
            finishOnce();
        } else {
            postInvalidateOnAnimation();
        }
    }

    private static void drawSnowflake(
            @NonNull Canvas canvas,
            float cx,
            float cy,
            float radius,
            @NonNull Paint paint
    ) {
        for (int branch = 0; branch < 3; branch++) {
            double angle = Math.toRadians(branch * 60.0);
            float dx = (float) Math.cos(angle) * radius;
            float dy = (float) Math.sin(angle) * radius;
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint);

            drawTipBranches(canvas, cx + dx, cy + dy, angle, radius, paint);
            drawTipBranches(canvas, cx - dx, cy - dy, angle + Math.PI, radius, paint);
        }
    }

    private static void drawTipBranches(
            @NonNull Canvas canvas,
            float x,
            float y,
            double direction,
            float radius,
            @NonNull Paint paint
    ) {
        float branchLength = radius * 0.35f;
        float inset = radius * 0.34f;
        float baseX = x - (float) Math.cos(direction) * inset;
        float baseY = y - (float) Math.sin(direction) * inset;

        double left = direction + Math.toRadians(145.0);
        double right = direction - Math.toRadians(145.0);
        canvas.drawLine(
                baseX,
                baseY,
                baseX + (float) Math.cos(left) * branchLength,
                baseY + (float) Math.sin(left) * branchLength,
                paint
        );
        canvas.drawLine(
                baseX,
                baseY,
                baseX + (float) Math.cos(right) * branchLength,
                baseY + (float) Math.sin(right) * branchLength,
                paint
        );
    }

    private void finishOnce() {
        if (finished) return;
        finished = true;
        post(onFinished);
    }

    private static final class Flake {
        float x;
        float y;
        float speed;
        float drift;
        float radiusDp;
        int alpha;
        float phase;
    }
}
