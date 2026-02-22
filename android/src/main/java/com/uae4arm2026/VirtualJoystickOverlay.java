package com.uae4arm2026;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;

public class VirtualJoystickOverlay extends FrameLayout {

    private View dpadView;
    private View btnAView;
    private View btnBView;

    public VirtualJoystickOverlay(Context context) {
        super(context);
        init();
    }

    public VirtualJoystickOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // D-Pad View (Bottom Left)
        dpadView = new View(getContext()) {
            Paint paint = new Paint();
            @Override
            protected void onDraw(Canvas canvas) {
                paint.setColor(0x80444444); // Semi-transparent gray
                canvas.drawCircle(getWidth() / 2, getHeight() / 2, getWidth() / 2, paint);
                paint.setColor(0xAAFFFFFF);
                // Draw cross
                float w = getWidth();
                float h = getHeight();
                canvas.drawRect(w/3, 0, 2*w/3, h, paint);
                canvas.drawRect(0, h/3, w, 2*h/3, paint);
            }
        };
        dpadView.setOnTouchListener((v, event) -> handleDpadTouch(event));

        int dpadSize = dpToPx(160);
        LayoutParams lpDpad = new LayoutParams(dpadSize, dpadSize);
        lpDpad.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.LEFT;
        lpDpad.leftMargin = dpToPx(32);
        lpDpad.bottomMargin = dpToPx(32);
        addView(dpadView, lpDpad);

        // Button A (Bottom Right)
        btnAView = createButton("A", 0xAAFF0000);
        btnAView.setOnTouchListener((v, event) -> handleButtonTouch(event, KeyEvent.KEYCODE_BUTTON_A)); // Fire 1
        LayoutParams lpA = new LayoutParams(dpToPx(64), dpToPx(64));
        lpA.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        lpA.rightMargin = dpToPx(32);
        lpA.bottomMargin = dpToPx(32);
        addView(btnAView, lpA);

        // Button B (Bottom Right, offset)
        btnBView = createButton("B", 0xAA0000FF);
        btnBView.setOnTouchListener((v, event) -> handleButtonTouch(event, KeyEvent.KEYCODE_BUTTON_B)); // Fire 2
        LayoutParams lpB = new LayoutParams(dpToPx(64), dpToPx(64));
        lpB.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        lpB.rightMargin = dpToPx(112);
        lpB.bottomMargin = dpToPx(64);
        addView(btnBView, lpB);
    }

    private View createButton(String text, int color) {
        return new View(getContext()) {
            Paint paint = new Paint();
            @Override
            protected void onDraw(Canvas canvas) {
                paint.setColor(color);
                canvas.drawCircle(getWidth() / 2, getHeight() / 2, getWidth() / 2, paint);
            }
        };
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private int lastDpadState = 0; // 0=center, 1=up, 2=right, 3=down, 4=left

    private boolean handleDpadTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float x = event.getX();
            float y = event.getY();
            float cx = dpadView.getWidth() / 2f;
            float cy = dpadView.getHeight() / 2f;

            float dx = x - cx;
            float dy = y - cy;

            // Use larger deadzone for center detection
            float deadzone = dpadView.getWidth() / 4f;
            if (Math.abs(dx) < deadzone && Math.abs(dy) < deadzone) {
                updateDpadState(0); // Center
            } else if (Math.abs(dx) > Math.abs(dy)) {
                if (dx > 0) updateDpadState(2); // Right
                else updateDpadState(4); // Left
            } else {
                if (dy > 0) updateDpadState(3); // Down
                else updateDpadState(1); // Up
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            updateDpadState(0);
        }
        return true;
    }

    private void updateDpadState(int newState) {
        if (lastDpadState == newState) return;

        // axis: 0=horiz, 1=vert. value: -1, 0, 1.
        int x = 0;
        int y = 0;

        // Explicitly handle center state
        if (newState == 0) {
            x = 0;
            y = 0;
        } else if (newState == 1) {
            y = -1; // Up
        } else if (newState == 2) {
            x = 1; // Right
        } else if (newState == 3) {
            y = 1; // Down
        } else if (newState == 4) {
            x = -1; // Left
        }

        // Always send BOTH axis values to ensure proper center/reset
        AmiberryActivity.nativeSendVirtualJoystick(0, x, -1, 0); // X axis
        AmiberryActivity.nativeSendVirtualJoystick(1, y, -1, 0); // Y axis

        lastDpadState = newState;
    }

    private boolean handleButtonTouch(MotionEvent event, int keycode) {
        int action = event.getActionMasked();
        int btn = -1;
        if (keycode == KeyEvent.KEYCODE_BUTTON_A) btn = 0;
        else if (keycode == KeyEvent.KEYCODE_BUTTON_B) btn = 1;

        if (btn >= 0) {
            if (action == MotionEvent.ACTION_DOWN) {
                AmiberryActivity.nativeSendVirtualJoystick(-1, 0, btn, 1);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                AmiberryActivity.nativeSendVirtualJoystick(-1, 0, btn, 0);
            }
        }
        return true;
    }
}
