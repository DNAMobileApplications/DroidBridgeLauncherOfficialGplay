/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.seasonal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;

import java.io.InputStream;

/** Plays one GIF cycle, then removes itself through the supplied callback. */
final class SeasonalOneShotGifView extends View {
    private static final int FALLBACK_DURATION_MS = 2400;

    @NonNull
    private final Runnable onFinished;
    @Nullable
    private AnimatedImageDrawable animatedDrawable;
    @Nullable
    private Movie movie;
    private int durationMs = FALLBACK_DURATION_MS;
    private long startedAtMs;
    private boolean finished;

    SeasonalOneShotGifView(
            @NonNull Context context,
            @RawRes int rawResource,
            @NonNull Runnable onFinished
    ) {
        super(context);
        this.onFinished = onFinished;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tryCreateNativeDrawable(context, rawResource)) {
            // AnimatedImageDrawable is the most reliable GIF path on modern Android.
            setWillNotDraw(false);
            return;
        }

        Movie decoded;
        try (InputStream input = context.getResources().openRawResource(rawResource)) {
            decoded = Movie.decodeStream(input);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unable to decode seasonal GIF", throwable);
        }
        if (decoded == null) {
            throw new IllegalStateException("Seasonal GIF decoded to null");
        }

        movie = decoded;
        int decodedDuration = decoded.duration();
        durationMs = decodedDuration > 0 ? decodedDuration : FALLBACK_DURATION_MS;

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
    }

    private boolean tryCreateNativeDrawable(@NonNull Context context, @RawRes int rawResource) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;

        try {
            Drawable decoded = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.getResources(), rawResource)
            );
            if (!(decoded instanceof AnimatedImageDrawable)) {
                return false;
            }

            AnimatedImageDrawable animation = (AnimatedImageDrawable) decoded;
            animation.setRepeatCount(0); // Initial playback only; no repeats.
            animation.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
                @Override
                public void onAnimationEnd(Drawable drawable) {
                    finishOnce();
                }
            });
            animation.setCallback(this);
            animatedDrawable = animation;
            return true;
        } catch (Throwable ignored) {
            animatedDrawable = null;
            return false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAtMs = SystemClock.uptimeMillis();
        finished = false;

        AnimatedImageDrawable animation = animatedDrawable;
        if (animation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            updateNativeDrawableBounds(animation);
            animation.start();
            invalidate();
        } else {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        AnimatedImageDrawable animation = animatedDrawable;
        if (animation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                animation.stop();
                animation.setCallback(null);
            } catch (Throwable ignored) {
            }
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        AnimatedImageDrawable animation = animatedDrawable;
        if (animation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            updateNativeDrawableBounds(animation);
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == animatedDrawable || super.verifyDrawable(who);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (finished || getWidth() <= 0 || getHeight() <= 0) return;

        AnimatedImageDrawable animation = animatedDrawable;
        if (animation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            animation.draw(canvas);
            return;
        }

        Movie fallbackMovie = movie;
        if (fallbackMovie == null) return;

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - startedAtMs);
        int frameTime = (int) Math.min(elapsed, Math.max(0, durationMs - 1));
        fallbackMovie.setTime(frameTime);

        float scaleX = getWidth() / (float) Math.max(1, fallbackMovie.width());
        float scaleY = getHeight() / (float) Math.max(1, fallbackMovie.height());
        float scale = Math.min(scaleX, scaleY);
        float drawWidth = fallbackMovie.width() * scale;
        float drawHeight = fallbackMovie.height() * scale;
        float left = (getWidth() - drawWidth) * 0.5f;
        float top = (getHeight() - drawHeight) * 0.5f;

        int save = canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        fallbackMovie.draw(canvas, 0f, 0f);
        canvas.restoreToCount(save);

        if (elapsed >= durationMs) {
            finishOnce();
        } else {
            postInvalidateOnAnimation();
        }
    }

    private void updateNativeDrawableBounds(@NonNull AnimatedImageDrawable animation) {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        int sourceWidth = Math.max(1, animation.getIntrinsicWidth());
        int sourceHeight = Math.max(1, animation.getIntrinsicHeight());
        float scale = Math.min(
                viewWidth / (float) sourceWidth,
                viewHeight / (float) sourceHeight
        );
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));
        int left = (viewWidth - width) / 2;
        int top = (viewHeight - height) / 2;
        animation.setBounds(new Rect(left, top, left + width, top + height));
    }

    private void finishOnce() {
        if (finished) return;
        finished = true;
        post(onFinished);
    }
}
