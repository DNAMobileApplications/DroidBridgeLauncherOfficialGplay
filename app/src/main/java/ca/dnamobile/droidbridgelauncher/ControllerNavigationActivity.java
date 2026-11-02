/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */

package ca.dnamobile.droidbridgelauncher;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared controller navigation for launcher UI screens.
 *
 * <p>D-pad and the left stick move focus between actionable launcher controls, A confirms,
 * B goes back, and shoulder/trigger buttons can be assigned by individual activities to switch
 * high-level sections. Touch remains fully supported; the controller focus ring is hidden as soon
 * as the user touches the screen again.</p>
 */
public abstract class ControllerNavigationActivity extends AppCompatActivity {
    private static final float STICK_DEAD_ZONE = 0.55f;
    private static final float TRIGGER_THRESHOLD = 0.55f;
    private static final long ANALOG_REPEAT_MS = 175L;

    private boolean controllerMode;
    private int lastAnalogDirection;
    private long lastAnalogNavigationTime;
    private boolean leftTriggerLatched;
    private boolean rightTriggerLatched;

    private GradientDrawable focusRing;
    private boolean focusRingAttached;
    private View decorView;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChangeListener =
            (oldFocus, newFocus) -> updateControllerFocusRing(newFocus);
    private final ViewTreeObserver.OnScrollChangedListener scrollChangedListener =
            () -> updateControllerFocusRing(getCurrentFocus());
    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener = () -> {
        if (!controllerMode) return;
        refreshControllerTargets();
        updateControllerFocusRing(getCurrentFocus());
    };

    @Override
    public void onContentChanged() {
        super.onContentChanged();

        // Keep normal/tablet layouts untouched. On narrow phones and short landscape windows,
        // relax launcher button labels after inflation so long localized text can wrap or shrink
        // instead of being clipped/squashed. This applies to every launcher Activity that uses
        // ControllerNavigationActivity, including rotation-specific layouts.
        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.postDelayed(this::refreshCompactUiTuning, 120L);
        }
    }

    /** Re-applies compact sizing after an Activity adds controls dynamically. */
    protected final void refreshCompactUiTuning() {
        View content = findViewById(android.R.id.content);
        if (content != null) applyCompactUiTuning(content);
    }

    private void applyCompactUiTuning(View root) {
        Configuration configuration = getResources().getConfiguration();
        int widthDp = configuration.screenWidthDp;
        int heightDp = configuration.screenHeightDp;
        boolean compact = (widthDp > 0 && widthDp < 400)
                || (heightDp > 0 && heightDp < 400);
        if (!compact) return;

        tuneCompactView(root);
    }

    private void tuneCompactView(View view) {
        if (view instanceof Button) {
            TextView textView = (TextView) view;
            CharSequence text = textView.getText();

            // Only tune labels that are actually long enough to need help on compact screens.
            // Leaving short buttons and every non-button TextView untouched avoids overriding
            // layout-specific typography, EditTexts, headings, and instance-detail labels.
            if (text != null && text.length() >= 12) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    textView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                    textView.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                }

                textView.setHorizontallyScrolling(false);
                textView.setSingleLine(false);
                textView.setMaxLines(2);
                textView.setEllipsize(android.text.TextUtils.TruncateAt.END);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    textView.setAutoSizeTextTypeUniformWithConfiguration(
                            11,
                            14,
                            1,
                            TypedValue.COMPLEX_UNIT_SP
                    );
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tuneCompactView(group.getChildAt(i));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        installControllerFocusObservers();
    }

    @Override
    protected void onPause() {
        removeControllerFocusObservers();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        removeControllerFocusObservers();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            controllerMode = false;
            removeControllerFocusRing();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null || !isControllerKeyEvent(event)) {
            return super.dispatchKeyEvent(event);
        }

        activateControllerMode();

        final int keyCode = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean up = event.getAction() == KeyEvent.ACTION_UP;

        if (isPreviousSectionKey(keyCode)) {
            if (down && event.getRepeatCount() == 0 && onControllerPreviousSection()) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (isNextSectionKey(keyCode)) {
            if (down && event.getRepeatCount() == 0 && onControllerNextSection()) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (isDirectionalKey(keyCode)) {
            if (!down) return true;

            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                return super.dispatchKeyEvent(event);
            }
            if (focused instanceof SeekBar
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                return super.dispatchKeyEvent(event);
            }

            int direction = keyCodeToFocusDirection(keyCode);
            if (direction != 0 && moveControllerFocus(direction)) return true;
            return super.dispatchKeyEvent(event);
        }

        if (isConfirmKey(keyCode)) {
            if (up && !event.isCanceled()) {
                View focused = getCurrentFocus();
                if (focused == null || !isControllerTarget(focused)) {
                    focusFirstControllerTarget(null);
                    focused = getCurrentFocus();
                }
                if (focused != null && focused.isEnabled()) {
                    if (focused instanceof EditText) {
                        focused.requestFocus();
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT);
                        updateControllerFocusRing(focused);
                        return true;
                    }
                    if (focused.performClick()) {
                        updateControllerFocusRing(focused);
                        return true;
                    }
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (up && !event.isCanceled()) {
                onBackPressed();
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event == null || !isControllerMotionEvent(event)) {
            return super.dispatchGenericMotionEvent(event);
        }

        activateControllerMode();

        boolean handled = handleControllerTriggers(event);

        float horizontal = strongestAxis(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_X));
        float vertical = strongestAxis(
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y));

        int direction = 0;
        if (Math.abs(horizontal) >= STICK_DEAD_ZONE || Math.abs(vertical) >= STICK_DEAD_ZONE) {
            if (Math.abs(horizontal) > Math.abs(vertical)) {
                direction = horizontal < 0f ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
            } else {
                direction = vertical < 0f ? View.FOCUS_UP : View.FOCUS_DOWN;
            }
        }

        long now = SystemClock.uptimeMillis();
        if (direction == 0) {
            lastAnalogDirection = 0;
            lastAnalogNavigationTime = 0L;
        } else if (direction != lastAnalogDirection
                || now - lastAnalogNavigationTime >= ANALOG_REPEAT_MS) {
            if (moveControllerFocus(direction)) {
                handled = true;
                lastAnalogDirection = direction;
                lastAnalogNavigationTime = now;
            }
        }

        return handled || super.dispatchGenericMotionEvent(event);
    }

    /** Override in sectioned launcher screens such as Settings. */
    protected boolean onControllerPreviousSection() {
        return false;
    }

    /** Override in sectioned launcher screens such as Settings. */
    protected boolean onControllerNextSection() {
        return false;
    }

    /**
     * Requests focus on the first actionable controller target within a scope.
     * Passing null uses the whole activity content.
     */
    protected final boolean focusFirstControllerTarget(@Nullable View scope) {
        activateControllerMode();
        refreshControllerTargets();
        List<View> targets = collectControllerTargets(scope != null ? scope : getDecorView());
        if (targets.isEmpty()) return false;

        targets.sort(Comparator
                .comparingInt((View view) -> getViewRect(view).top)
                .thenComparingInt(view -> getViewRect(view).left));

        for (View target : targets) {
            if (requestControllerFocus(target)) return true;
        }
        return false;
    }

    private boolean handleControllerTriggers(MotionEvent event) {
        float left = Math.max(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float right = Math.max(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS));

        boolean handled = false;
        boolean leftPressed = left >= TRIGGER_THRESHOLD;
        boolean rightPressed = right >= TRIGGER_THRESHOLD;

        if (leftPressed && !leftTriggerLatched) {
            handled = onControllerPreviousSection();
        }
        if (rightPressed && !rightTriggerLatched) {
            handled = onControllerNextSection() || handled;
        }

        leftTriggerLatched = leftPressed;
        rightTriggerLatched = rightPressed;
        return handled;
    }

    private boolean moveControllerFocus(int direction) {
        refreshControllerTargets();

        View current = getCurrentFocus();
        if (current == null || !isControllerTarget(current)) {
            return focusFirstControllerTarget(null);
        }

        View candidate = current.focusSearch(direction);
        if (candidate != null && candidate != current && isControllerTarget(candidate)) {
            return requestControllerFocus(candidate);
        }

        candidate = findSpatialControllerTarget(current, direction);
        return candidate != null && requestControllerFocus(candidate);
    }

    @Nullable
    private View findSpatialControllerTarget(View current, int direction) {
        Rect currentRect = getViewRect(current);
        float currentCx = currentRect.exactCenterX();
        float currentCy = currentRect.exactCenterY();
        View best = null;
        double bestScore = Double.MAX_VALUE;

        for (View candidate : collectControllerTargets(getDecorView())) {
            if (candidate == current) continue;
            Rect rect = getViewRect(candidate);
            float cx = rect.exactCenterX();
            float cy = rect.exactCenterY();

            float major;
            float minor;
            switch (direction) {
                case View.FOCUS_LEFT:
                    if (cx >= currentCx - 1f) continue;
                    major = currentCx - cx;
                    minor = Math.abs(currentCy - cy);
                    break;
                case View.FOCUS_RIGHT:
                    if (cx <= currentCx + 1f) continue;
                    major = cx - currentCx;
                    minor = Math.abs(currentCy - cy);
                    break;
                case View.FOCUS_UP:
                    if (cy >= currentCy - 1f) continue;
                    major = currentCy - cy;
                    minor = Math.abs(currentCx - cx);
                    break;
                case View.FOCUS_DOWN:
                    if (cy <= currentCy + 1f) continue;
                    major = cy - currentCy;
                    minor = Math.abs(currentCx - cx);
                    break;
                default:
                    continue;
            }

            double score = major * major * 13.0 + minor * minor;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean requestControllerFocus(View target) {
        if (target == null || !isControllerTarget(target)) return false;
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        if (!target.requestFocus()) return false;

        target.post(() -> {
            Rect local = new Rect(0, 0, target.getWidth(), target.getHeight());
            target.requestRectangleOnScreen(local, false);
            updateControllerFocusRing(target);
        });
        return true;
    }

    private void activateControllerMode() {
        if (!controllerMode) {
            controllerMode = true;
            installControllerFocusObservers();
        }
        refreshControllerTargets();
        if (getCurrentFocus() == null || !isControllerTarget(getCurrentFocus())) {
            focusFirstControllerTargetWithoutActivation();
        } else {
            updateControllerFocusRing(getCurrentFocus());
        }
    }

    private void focusFirstControllerTargetWithoutActivation() {
        List<View> targets = collectControllerTargets(getDecorView());
        if (targets.isEmpty()) return;
        targets.sort(Comparator
                .comparingInt((View view) -> getViewRect(view).top)
                .thenComparingInt(view -> getViewRect(view).left));
        for (View target : targets) {
            if (requestControllerFocus(target)) break;
        }
    }

    private void refreshControllerTargets() {
        View root = getDecorView();
        if (root == null) return;
        makeInteractiveViewsFocusable(root);
    }

    private void makeInteractiveViewsFocusable(View view) {
        if (view == null) return;
        if (isPotentialControllerTarget(view)) {
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                makeInteractiveViewsFocusable(group.getChildAt(i));
            }
        }
    }

    private List<View> collectControllerTargets(View root) {
        ArrayList<View> result = new ArrayList<>();
        collectControllerTargetsRecursive(root, result);
        return result;
    }

    private void collectControllerTargetsRecursive(View view, List<View> out) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()) return;
        if (isControllerTarget(view)) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectControllerTargetsRecursive(group.getChildAt(i), out);
            }
        }
    }

    private boolean isPotentialControllerTarget(View view) {
        if (view == null || !view.isEnabled()) return false;
        return view.isClickable()
                || view.isLongClickable()
                || view instanceof SeekBar
                || view instanceof Spinner
                || view instanceof EditText;
    }

    private boolean isControllerTarget(View view) {
        return isPotentialControllerTarget(view)
                && view.getVisibility() == View.VISIBLE
                && view.isShown()
                && view.getAlpha() > 0.15f;
    }

    private Rect getViewRect(View view) {
        Rect rect = new Rect();
        if (view == null) return rect;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        rect.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        return rect;
    }

    private void installControllerFocusObservers() {
        View decor = getDecorView();
        if (decor == null || decor == decorView) return;
        removeControllerFocusObservers();
        decorView = decor;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.addOnGlobalFocusChangeListener(focusChangeListener);
            observer.addOnScrollChangedListener(scrollChangedListener);
            observer.addOnGlobalLayoutListener(globalLayoutListener);
        }
    }

    private void removeControllerFocusObservers() {
        if (decorView != null) {
            ViewTreeObserver observer = decorView.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalFocusChangeListener(focusChangeListener);
                observer.removeOnScrollChangedListener(scrollChangedListener);
                observer.removeOnGlobalLayoutListener(globalLayoutListener);
            }
        }
        decorView = null;
        removeControllerFocusRing();
    }

    @Nullable
    private View getDecorView() {
        try {
            return getWindow() != null ? getWindow().getDecorView() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void updateControllerFocusRing(@Nullable View focused) {
        if (!controllerMode || focused == null || !isControllerTarget(focused)) {
            removeControllerFocusRing();
            return;
        }

        View decor = getDecorView();
        if (decor == null || focused.getWidth() <= 0 || focused.getHeight() <= 0) return;

        if (focusRing == null) {
            focusRing = new GradientDrawable();
            focusRing.setColor(Color.TRANSPARENT);
            focusRing.setCornerRadius(dp(10));
            focusRing.setStroke(Math.max(2, dp(3)), resolveFocusRingColor());
        }

        int[] focusedLocation = new int[2];
        int[] decorLocation = new int[2];
        focused.getLocationOnScreen(focusedLocation);
        decor.getLocationOnScreen(decorLocation);
        int inset = dp(3);
        int left = focusedLocation[0] - decorLocation[0] - inset;
        int top = focusedLocation[1] - decorLocation[1] - inset;
        int right = left + focused.getWidth() + inset * 2;
        int bottom = top + focused.getHeight() + inset * 2;
        focusRing.setBounds(left, top, right, bottom);

        if (!focusRingAttached) {
            decor.getOverlay().add(focusRing);
            focusRingAttached = true;
        }
        decor.invalidate();
    }

    private void removeControllerFocusRing() {
        View decor = getDecorView();
        if (decor != null && focusRing != null && focusRingAttached) {
            decor.getOverlay().remove(focusRing);
        }
        focusRingAttached = false;
    }

    private int resolveFocusRingColor() {
        TypedValue value = new TypedValue();
        if (getTheme() != null && getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return getResources().getColor(value.resourceId);
                } catch (Throwable ignored) {
                    // Fall through to raw data / default.
                }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return Color.WHITE;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean isDirectionalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static int keyCodeToFocusDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return View.FOCUS_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP:
                return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return View.FOCUS_DOWN;
            default:
                return 0;
        }
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private static boolean isPreviousSectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2;
    }

    private static boolean isNextSectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2;
    }

    private static boolean isControllerKeyEvent(KeyEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
            return true;
        }

        int keyCode = event.getKeyCode();
        return keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE;
    }

    private static boolean isControllerMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private static float strongestAxis(float primary, float secondary) {
        return Math.abs(primary) >= Math.abs(secondary) ? primary : secondary;
    }
}
