package com.uae4arm2026;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Small helper to keep UI controls away from system bars (navigation bar, gesture area, cutouts).
 *
 * Some devices/ROMs (and immersive modes) can place the system navigation UI over app content.
 * Applying WindowInsets as padding ensures bottom buttons stay tappable.
 */
final class UiInsets {
    private UiInsets() {}

    static void applySystemBarsPaddingBottom(View v) {
        applySystemBarsPadding(v, /*left*/false, /*top*/false, /*right*/false, /*bottom*/true);
    }

    static void applySystemBarsPadding(View v, boolean left, boolean top, boolean right, boolean bottom) {
        if (v == null) return;

        final int baseLeft = v.getPaddingLeft();
        final int baseTop = v.getPaddingTop();
        final int baseRight = v.getPaddingRight();
        final int baseBottom = v.getPaddingBottom();

        v.setOnApplyWindowInsetsListener((view, insets) -> {
            if (insets == null) return insets;

            int insetLeft = insets.getSystemWindowInsetLeft();
            int insetTop = insets.getSystemWindowInsetTop();
            int insetRight = insets.getSystemWindowInsetRight();
            int insetBottom = insets.getSystemWindowInsetBottom();

            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                insetLeft = Math.max(insetLeft, insets.getDisplayCutout().getSafeInsetLeft());
                insetTop = Math.max(insetTop, insets.getDisplayCutout().getSafeInsetTop());
                insetRight = Math.max(insetRight, insets.getDisplayCutout().getSafeInsetRight());
                insetBottom = Math.max(insetBottom, insets.getDisplayCutout().getSafeInsetBottom());
            }

            view.setPadding(
                baseLeft + (left ? insetLeft : 0),
                baseTop + (top ? insetTop : 0),
                baseRight + (right ? insetRight : 0),
                baseBottom + (bottom ? insetBottom : 0)
            );

            return insets;
        });

        // Kick off an insets pass.
        try {
            v.requestApplyInsets();
        } catch (Throwable ignored) {
        }
    }
}

