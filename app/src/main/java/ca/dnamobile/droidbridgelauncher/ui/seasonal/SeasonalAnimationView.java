/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.seasonal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * Dependency-free seasonal foreground animation for the DroidBridge launcher.
 * Drawing is intentionally lightweight and paced from Choreographer. Motion stays tied to
 * real elapsed time so a missed frame does not turn into slow-motion on lower-end devices.
 */
final class SeasonalAnimationView extends View {
    enum Theme {
        HALLOWEEN,
        SNOW,
        CHRISTMAS,
        RAIN,
        SUN
    }

    private static final long TARGET_FRAME_NS = 16_666_667L; // ~60 FPS, paced from display vsync.
    private static final long MAX_VSYNC_DELTA_NS = TARGET_FRAME_NS * 6L;
    private static final float MAX_SIMULATION_STEP_SECONDS = 0.125f;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float SQRT_THREE_OVER_TWO = 0.8660254f;
    private static final int[] LEAF_COLORS = {
            Color.rgb(226, 91, 30),
            Color.rgb(244, 153, 36),
            Color.rgb(188, 53, 34),
            Color.rgb(238, 186, 54)
    };
    private static final float[] SUN_RAY_COS = new float[12];
    private static final float[] SUN_RAY_SIN = new float[12];

    static {
        for (int i = 0; i < 12; i++) {
            double angle = (i / 12.0) * Math.PI * 2.0;
            SUN_RAY_COS[i] = (float) Math.cos(angle);
            SUN_RAY_SIN[i] = (float) Math.sin(angle);
        }
    }

    private final Theme theme;
    private final float density;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random random = new Random();

    private Particle[] particles = new Particle[0];
    private long lastFrameMs;
    private long lastVsyncNs;
    private long frameAccumulatorNs;
    private boolean frameCallbackPosted;
    private boolean running;
    private boolean windowVisible;

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        frameCallbackPosted = false;
        if (!running || !windowVisible) return;

        if (lastVsyncNs == 0L) {
            lastVsyncNs = frameTimeNanos;
            frameAccumulatorNs = TARGET_FRAME_NS;
        } else {
            long vsyncDelta = Math.max(0L, frameTimeNanos - lastVsyncNs);
            lastVsyncNs = frameTimeNanos;
            frameAccumulatorNs += Math.min(vsyncDelta, MAX_VSYNC_DELTA_NS);
        }

        if (frameAccumulatorNs >= TARGET_FRAME_NS) {
            // Keep the remainder so 90/120/144 Hz panels average close to 60 FPS
            // instead of falling into a rough integer-divisor cadence.
            frameAccumulatorNs %= TARGET_FRAME_NS;
            invalidate();
        }
        scheduleNextFrame();
    };

    SeasonalAnimationView(@NonNull Context context, @NonNull Theme theme) {
        super(context);
        this.theme = theme;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @NonNull
    Theme getTheme() {
        return theme;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        windowVisible = getWindowVisibility() == VISIBLE;
        resetFrameClock();
        scheduleNextFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        windowVisible = false;
        if (frameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallbackPosted = false;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        if (running && windowVisible) {
            resetFrameClock();
            scheduleNextFrame();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Seasonal sprites are independent foreground accents. Telling HWUI this avoids
        // a full-view temporary alpha layer when the user chooses reduced opacity.
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildParticles(w, h);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!running || getWidth() <= 0 || getHeight() <= 0) return;

        long now = SystemClock.uptimeMillis();
        float dt = Math.min(
                MAX_SIMULATION_STEP_SECONDS,
                Math.max(0f, (now - lastFrameMs) / 1000f)
        );
        lastFrameMs = now;

        if (theme == Theme.SUN) {
            drawSun(canvas, now);
        } else {
            updateParticles(dt);
            drawParticles(canvas, now);
        }

    }

    private void resetFrameClock() {
        lastFrameMs = SystemClock.uptimeMillis();
        lastVsyncNs = 0L;
        frameAccumulatorNs = 0L;
    }

    private void scheduleNextFrame() {
        if (!running || !windowVisible || frameCallbackPosted) return;
        frameCallbackPosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void rebuildParticles(int width, int height) {
        if (width <= 0 || height <= 0 || theme == Theme.SUN) {
            particles = new Particle[0];
            return;
        }

        final int count;
        switch (theme) {
            case HALLOWEEN:
                count = 30;
                break;
            case CHRISTMAS:
                count = 36;
                break;
            case RAIN:
                count = 58;
                break;
            case SNOW:
            default:
                count = 46;
                break;
        }

        particles = new Particle[count];
        for (int i = 0; i < count; i++) {
            Particle particle = new Particle();
            particle.kind = chooseKind(i);
            particle.variant = random.nextInt(4);
            particles[i] = particle;
            resetParticle(particle, true);
        }
    }

    private int chooseKind(int index) {
        if (theme == Theme.HALLOWEEN) {
            // Leaves are most common, with pumpkins and other Halloween accents mixed in.
            int slot = index % 10;
            if (slot <= 4) return 0; // leaf
            if (slot <= 6) return 1; // pumpkin
            if (slot <= 8) return 2; // bat
            return 3;                // candy corn
        }
        if (theme == Theme.CHRISTMAS) {
            int slot = index % 10;
            if (slot <= 4) return 0; // snowflake
            if (slot <= 6) return 1; // ornament
            if (slot <= 8) return 2; // gift
            return 3;                // candy cane
        }
        return 0;
    }

    private void resetParticle(@NonNull Particle p, boolean initial) {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());

        p.x = random.nextFloat() * width;
        p.phase = random.nextFloat() * TWO_PI;
        p.rotation = random.nextFloat() * 360f;
        p.rotationSpeed = -35f + random.nextFloat() * 70f;

        if (theme == Theme.RAIN) {
            p.size = (12f + random.nextFloat() * 18f) * density;
            p.speed = (500f + random.nextFloat() * 520f) * density;
            p.drift = (90f + random.nextFloat() * 85f) * density;
            p.y = initial ? random.nextFloat() * height : -p.size * (1f + random.nextFloat() * 4f);
            return;
        }

        if (theme == Theme.SNOW) {
            p.size = (3.5f + random.nextFloat() * 6.5f) * density;
            p.speed = (28f + random.nextFloat() * 58f) * density;
            p.drift = (-10f + random.nextFloat() * 20f) * density;
        } else if (theme == Theme.HALLOWEEN) {
            if (p.kind == 1) {
                p.size = (15f + random.nextFloat() * 12f) * density;
            } else {
                p.size = (10f + random.nextFloat() * 12f) * density;
            }
            p.speed = (42f + random.nextFloat() * 75f) * density;
            p.drift = (-20f + random.nextFloat() * 40f) * density;
        } else {
            if (p.kind == 1 || p.kind == 2) {
                p.size = (12f + random.nextFloat() * 12f) * density;
            } else {
                p.size = (8f + random.nextFloat() * 11f) * density;
            }
            p.speed = (35f + random.nextFloat() * 62f) * density;
            p.drift = (-16f + random.nextFloat() * 32f) * density;
        }

        p.y = initial
                ? -p.size + random.nextFloat() * (height + p.size)
                : -p.size * (1.5f + random.nextFloat() * 5f);
    }

    private void updateParticles(float dt) {
        if (particles.length == 0) return;
        float width = getWidth();
        float height = getHeight();

        for (Particle p : particles) {
            p.y += p.speed * dt;
            p.x += p.drift * dt;
            p.x += (float) Math.sin(p.phase + p.y * 0.008f) * 9f * dt;
            p.rotation += p.rotationSpeed * dt;

            if (p.x < -p.size * 2f) p.x = width + p.size;
            if (p.x > width + p.size * 2f) p.x = -p.size;
            if (p.y > height + p.size * 3f) resetParticle(p, false);
        }
    }

    private void drawParticles(@NonNull Canvas canvas, long nowMs) {
        for (Particle p : particles) {
            switch (theme) {
                case HALLOWEEN:
                    drawHalloweenParticle(canvas, p);
                    break;
                case CHRISTMAS:
                    drawChristmasParticle(canvas, p);
                    break;
                case RAIN:
                    drawRainDrop(canvas, p);
                    break;
                case SNOW:
                default:
                    drawSnowflake(canvas, p.x, p.y, p.size, Color.rgb(225, 244, 255));
                    break;
            }
        }
    }

    private void drawHalloweenParticle(@NonNull Canvas canvas, @NonNull Particle p) {
        canvas.save();
        canvas.translate(p.x, p.y);
        canvas.rotate(p.rotation);
        switch (p.kind) {
            case 1:
                drawPumpkin(canvas, p.size);
                break;
            case 2:
                drawBat(canvas, p.size);
                break;
            case 3:
                drawCandyCorn(canvas, p.size);
                break;
            case 0:
            default:
                drawLeaf(canvas, p.size, p.variant);
                break;
        }
        canvas.restore();
    }

    private void drawChristmasParticle(@NonNull Canvas canvas, @NonNull Particle p) {
        if (p.kind == 0) {
            drawSnowflake(canvas, p.x, p.y, p.size, Color.rgb(230, 247, 255));
            return;
        }

        canvas.save();
        canvas.translate(p.x, p.y);
        canvas.rotate(p.rotation);
        if (p.kind == 1) {
            drawOrnament(canvas, p.size, p.variant);
        } else if (p.kind == 2) {
            drawGift(canvas, p.size, p.variant);
        } else {
            drawCandyCane(canvas, p.size);
        }
        canvas.restore();
    }

    private void drawLeaf(@NonNull Canvas canvas, float s, int variant) {
        fillPaint.setColor(LEAF_COLORS[Math.abs(variant) % LEAF_COLORS.length]);
        strokePaint.setColor(Color.rgb(117, 61, 28));
        strokePaint.setStrokeWidth(Math.max(1f, s * 0.08f));

        path.reset();
        path.moveTo(0f, -s);
        path.lineTo(s * 0.22f, -s * 0.38f);
        path.lineTo(s * 0.67f, -s * 0.50f);
        path.lineTo(s * 0.42f, -s * 0.05f);
        path.lineTo(s * 0.78f, s * 0.20f);
        path.lineTo(s * 0.18f, s * 0.28f);
        path.lineTo(0f, s);
        path.lineTo(-s * 0.18f, s * 0.28f);
        path.lineTo(-s * 0.78f, s * 0.20f);
        path.lineTo(-s * 0.42f, -s * 0.05f);
        path.lineTo(-s * 0.67f, -s * 0.50f);
        path.lineTo(-s * 0.22f, -s * 0.38f);
        path.close();
        canvas.drawPath(path, fillPaint);
        canvas.drawLine(0f, -s * 0.65f, 0f, s * 1.18f, strokePaint);
    }

    private void drawPumpkin(@NonNull Canvas canvas, float s) {
        fillPaint.setColor(Color.rgb(244, 111, 24));
        rect.set(-s, -s * 0.72f, s, s * 0.72f);
        canvas.drawOval(rect, fillPaint);

        strokePaint.setColor(Color.rgb(193, 73, 12));
        strokePaint.setStrokeWidth(Math.max(1f, s * 0.09f));
        canvas.drawArc(rect, 105f, 150f, false, strokePaint);
        canvas.drawArc(rect, -75f, 150f, false, strokePaint);

        fillPaint.setColor(Color.rgb(76, 96, 38));
        canvas.drawRoundRect(-s * 0.13f, -s * 0.98f, s * 0.13f, -s * 0.58f,
                s * 0.08f, s * 0.08f, fillPaint);

        fillPaint.setColor(Color.rgb(48, 28, 18));
        path.reset();
        path.moveTo(-s * 0.48f, -s * 0.20f);
        path.lineTo(-s * 0.18f, -s * 0.20f);
        path.lineTo(-s * 0.33f, s * 0.06f);
        path.close();
        canvas.drawPath(path, fillPaint);
        path.reset();
        path.moveTo(s * 0.18f, -s * 0.20f);
        path.lineTo(s * 0.48f, -s * 0.20f);
        path.lineTo(s * 0.33f, s * 0.06f);
        path.close();
        canvas.drawPath(path, fillPaint);
        rect.set(-s * 0.48f, s * 0.10f, s * 0.48f, s * 0.43f);
        canvas.drawArc(rect, 15f, 150f, false, strokePaintFor(Color.rgb(48, 28, 18), s * 0.10f));
    }

    private void drawBat(@NonNull Canvas canvas, float s) {
        fillPaint.setColor(Color.rgb(38, 31, 45));
        path.reset();
        path.moveTo(0f, s * 0.10f);
        path.cubicTo(-s * 0.25f, -s * 0.45f, -s * 0.58f, -s * 0.48f, -s, -s * 0.12f);
        path.lineTo(-s * 0.70f, s * 0.08f);
        path.lineTo(-s * 0.46f, s * 0.03f);
        path.lineTo(-s * 0.24f, s * 0.38f);
        path.lineTo(0f, s * 0.18f);
        path.lineTo(s * 0.24f, s * 0.38f);
        path.lineTo(s * 0.46f, s * 0.03f);
        path.lineTo(s * 0.70f, s * 0.08f);
        path.lineTo(s, -s * 0.12f);
        path.cubicTo(s * 0.58f, -s * 0.48f, s * 0.25f, -s * 0.45f, 0f, s * 0.10f);
        path.close();
        canvas.drawPath(path, fillPaint);
        fillPaint.setColor(Color.rgb(244, 168, 36));
        canvas.drawCircle(-s * 0.09f, 0f, Math.max(1.3f, s * 0.055f), fillPaint);
        canvas.drawCircle(s * 0.09f, 0f, Math.max(1.3f, s * 0.055f), fillPaint);
    }

    private void drawCandyCorn(@NonNull Canvas canvas, float s) {
        path.reset();
        path.moveTo(0f, -s);
        path.lineTo(s * 0.72f, s);
        path.lineTo(-s * 0.72f, s);
        path.close();
        fillPaint.setColor(Color.rgb(248, 145, 27));
        canvas.drawPath(path, fillPaint);
        fillPaint.setColor(Color.rgb(255, 241, 208));
        path.reset();
        path.moveTo(0f, -s);
        path.lineTo(s * 0.25f, -s * 0.30f);
        path.lineTo(-s * 0.25f, -s * 0.30f);
        path.close();
        canvas.drawPath(path, fillPaint);
        fillPaint.setColor(Color.rgb(247, 204, 51));
        path.reset();
        path.moveTo(-s * 0.72f, s);
        path.lineTo(s * 0.72f, s);
        path.lineTo(s * 0.46f, s * 0.30f);
        path.lineTo(-s * 0.46f, s * 0.30f);
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    private void drawSnowflake(@NonNull Canvas canvas, float x, float y, float r, int color) {
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(Math.max(1.2f, r * 0.13f));
        canvas.drawLine(x - r, y, x + r, y, strokePaint);
        float half = r * 0.5f;
        float vertical = r * SQRT_THREE_OVER_TWO;
        canvas.drawLine(x - half, y - vertical, x + half, y + vertical, strokePaint);
        canvas.drawLine(x + half, y - vertical, x - half, y + vertical, strokePaint);
    }

    private void drawOrnament(@NonNull Canvas canvas, float s, int variant) {
        int color = (variant & 1) == 0 ? Color.rgb(201, 38, 45) : Color.rgb(42, 137, 72);
        fillPaint.setColor(color);
        canvas.drawCircle(0f, s * 0.10f, s * 0.72f, fillPaint);
        fillPaint.setColor(Color.rgb(220, 177, 70));
        canvas.drawRoundRect(-s * 0.22f, -s * 0.82f, s * 0.22f, -s * 0.55f,
                s * 0.08f, s * 0.08f, fillPaint);
        strokePaint.setColor(Color.rgb(246, 222, 151));
        strokePaint.setStrokeWidth(Math.max(1f, s * 0.07f));
        canvas.drawCircle(-s * 0.18f, -s * 0.02f, s * 0.10f, strokePaint);
        canvas.drawCircle(s * 0.20f, s * 0.24f, s * 0.08f, strokePaint);
    }

    private void drawGift(@NonNull Canvas canvas, float s, int variant) {
        fillPaint.setColor((variant & 1) == 0 ? Color.rgb(198, 39, 50) : Color.rgb(38, 135, 72));
        rect.set(-s * 0.78f, -s * 0.58f, s * 0.78f, s * 0.72f);
        canvas.drawRoundRect(rect, s * 0.10f, s * 0.10f, fillPaint);
        fillPaint.setColor(Color.rgb(224, 180, 61));
        canvas.drawRect(-s * 0.13f, -s * 0.58f, s * 0.13f, s * 0.72f, fillPaint);
        canvas.drawRect(-s * 0.78f, -s * 0.08f, s * 0.78f, s * 0.14f, fillPaint);
        strokePaint.setColor(Color.rgb(224, 180, 61));
        strokePaint.setStrokeWidth(Math.max(1.5f, s * 0.11f));
        rect.set(-s * 0.48f, -s * 0.94f, 0f, -s * 0.48f);
        canvas.drawOval(rect, strokePaint);
        rect.set(0f, -s * 0.94f, s * 0.48f, -s * 0.48f);
        canvas.drawOval(rect, strokePaint);
    }

    private void drawCandyCane(@NonNull Canvas canvas, float s) {
        strokePaint.setStrokeWidth(Math.max(3f, s * 0.30f));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setColor(Color.rgb(246, 244, 235));
        path.reset();
        path.moveTo(0f, s);
        path.lineTo(0f, -s * 0.35f);
        rect.set(-s * 0.02f, -s, s * 0.95f, -s * 0.10f);
        path.arcTo(rect, 180f, 180f, false);
        canvas.drawPath(path, strokePaint);

        strokePaint.setColor(Color.rgb(204, 39, 48));
        strokePaint.setStrokeWidth(Math.max(2f, s * 0.10f));
        for (int i = -3; i <= 3; i++) {
            float y = i * s * 0.27f;
            canvas.drawLine(-s * 0.12f, y - s * 0.10f, s * 0.12f, y + s * 0.10f, strokePaint);
        }
    }

    private void drawRainDrop(@NonNull Canvas canvas, @NonNull Particle p) {
        strokePaint.setColor(Color.rgb(121, 188, 232));
        strokePaint.setStrokeWidth(Math.max(1.2f, p.size * 0.07f));
        canvas.drawLine(p.x, p.y, p.x + p.size * 0.28f, p.y + p.size, strokePaint);
    }

    private void drawSun(@NonNull Canvas canvas, long nowMs) {
        float radius = 31f * density;
        float cx = getWidth() - 48f * density;
        float cy = 48f * density;
        float pulse = 1f + 0.04f * (float) Math.sin(nowMs / 900.0);
        float rayRadius = radius * 1.58f * pulse;

        strokePaint.setColor(Color.rgb(255, 182, 42));
        strokePaint.setStrokeWidth(3f * density);
        float inner = radius * 1.16f;
        int save = canvas.save();
        float rotationDegrees = (nowMs % 87_964L) * (360f / 87_964f);
        canvas.rotate(rotationDegrees, cx, cy);
        for (int i = 0; i < 12; i++) {
            float dx = SUN_RAY_COS[i];
            float dy = SUN_RAY_SIN[i];
            canvas.drawLine(
                    cx + dx * inner,
                    cy + dy * inner,
                    cx + dx * rayRadius,
                    cy + dy * rayRadius,
                    strokePaint
            );
        }
        canvas.restoreToCount(save);
        fillPaint.setColor(Color.rgb(255, 204, 53));
        canvas.drawCircle(cx, cy, radius * pulse, fillPaint);
        fillPaint.setColor(Color.rgb(255, 226, 96));
        canvas.drawCircle(cx - radius * 0.25f, cy - radius * 0.25f, radius * 0.32f, fillPaint);
    }

    @NonNull
    private Paint strokePaintFor(int color, float width) {
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(Math.max(1f, width));
        return strokePaint;
    }

    private static final class Particle {
        int kind;
        int variant;
        float x;
        float y;
        float size;
        float speed;
        float drift;
        float rotation;
        float rotationSpeed;
        float phase;
    }
}
