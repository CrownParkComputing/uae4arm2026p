package com.uae4arm2026;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Android View-based Amiga virtual keyboard overlay.
 * Works with any rendering backend (GL, Vulkan, etc.) because it draws via Android Views,
 * not the emulator's GPU path. Resolution-independent using dp units.
 */
public class AmiberryVirtualKeyboard extends FrameLayout {

    // ─── Amiga keycodes (AK_* from keyboard.h) ───
    private static final int AK_ESC = 0x45;
    private static final int AK_F1 = 0x50, AK_F2 = 0x51, AK_F3 = 0x52, AK_F4 = 0x53, AK_F5 = 0x54;
    private static final int AK_F6 = 0x55, AK_F7 = 0x56, AK_F8 = 0x57, AK_F9 = 0x58, AK_F10 = 0x59;
    private static final int AK_BACKQUOTE = 0x00;
    private static final int AK_1 = 0x01, AK_2 = 0x02, AK_3 = 0x03, AK_4 = 0x04, AK_5 = 0x05;
    private static final int AK_6 = 0x06, AK_7 = 0x07, AK_8 = 0x08, AK_9 = 0x09, AK_0 = 0x0A;
    private static final int AK_MINUS = 0x0B, AK_EQUAL = 0x0C, AK_BACKSLASH = 0x0D, AK_BS = 0x41;
    private static final int AK_TAB = 0x42;
    private static final int AK_Q = 0x10, AK_W = 0x11, AK_E = 0x12, AK_R = 0x13, AK_T = 0x14;
    private static final int AK_Y = 0x15, AK_U = 0x16, AK_I = 0x17, AK_O = 0x18, AK_P = 0x19;
    private static final int AK_LBRACKET = 0x1A, AK_RBRACKET = 0x1B;
    private static final int AK_CTRL = 0x63, AK_CAPSLOCK = 0x62;
    private static final int AK_A = 0x20, AK_S = 0x21, AK_D = 0x22, AK_F = 0x23, AK_G = 0x24;
    private static final int AK_H = 0x25, AK_J = 0x26, AK_K = 0x27, AK_L = 0x28;
    private static final int AK_SEMICOLON = 0x29, AK_QUOTE = 0x2A, AK_NUMBERSIGN = 0x2B, AK_RET = 0x44;
    private static final int AK_LSH = 0x60, AK_LTGT = 0x30;
    private static final int AK_Z = 0x31, AK_X = 0x32, AK_C = 0x33, AK_V = 0x34, AK_B = 0x35;
    private static final int AK_N = 0x36, AK_M = 0x37, AK_COMMA = 0x38, AK_PERIOD = 0x39, AK_SLASH = 0x3A;
    private static final int AK_RSH = 0x61;
    private static final int AK_LALT = 0x64, AK_LAMI = 0x66, AK_SPC = 0x40, AK_RAMI = 0x67, AK_RALT = 0x65;
    private static final int AK_UP = 0x4C, AK_DN = 0x4D, AK_LF = 0x4F, AK_RT = 0x4E;
    private static final int AK_DEL = 0x46, AK_HELP = 0x5F;
    private static final int AK_ENT = 0x43;

    // Key definition: label, keycode, relative width (1.0 = standard key)
    private static final Object[][][] ROWS = {
        // Row 0: Esc F1-F10 Help
        {{"Esc", AK_ESC, 1.0f}, {"F1", AK_F1, 1.0f}, {"F2", AK_F2, 1.0f}, {"F3", AK_F3, 1.0f},
         {"F4", AK_F4, 1.0f}, {"F5", AK_F5, 1.0f}, {"F6", AK_F6, 1.0f}, {"F7", AK_F7, 1.0f},
         {"F8", AK_F8, 1.0f}, {"F9", AK_F9, 1.0f}, {"F10", AK_F10, 1.0f}, {"Help", AK_HELP, 1.0f}, {"Del", AK_DEL, 1.0f}},
        // Row 1: `1234567890-=\Bksp
        {{"`", AK_BACKQUOTE, 1.0f}, {"1", AK_1, 1.0f}, {"2", AK_2, 1.0f}, {"3", AK_3, 1.0f},
         {"4", AK_4, 1.0f}, {"5", AK_5, 1.0f}, {"6", AK_6, 1.0f}, {"7", AK_7, 1.0f},
         {"8", AK_8, 1.0f}, {"9", AK_9, 1.0f}, {"0", AK_0, 1.0f}, {"-", AK_MINUS, 1.0f},
         {"=", AK_EQUAL, 1.0f}, {"\\", AK_BACKSLASH, 1.0f}, {"\u232B", AK_BS, 1.2f}},
        // Row 2: Tab QWERTYUIOP[]
        {{"Tab", AK_TAB, 1.4f}, {"Q", AK_Q, 1.0f}, {"W", AK_W, 1.0f}, {"E", AK_E, 1.0f},
         {"R", AK_R, 1.0f}, {"T", AK_T, 1.0f}, {"Y", AK_Y, 1.0f}, {"U", AK_U, 1.0f},
         {"I", AK_I, 1.0f}, {"O", AK_O, 1.0f}, {"P", AK_P, 1.0f}, {"[", AK_LBRACKET, 1.0f},
         {"]", AK_RBRACKET, 1.0f}},
        // Row 3: Ctrl Caps ASDFGHJKL;'# Ret
        {{"Ctrl", AK_CTRL, 1.3f}, {"Caps", AK_CAPSLOCK, 1.2f}, {"A", AK_A, 1.0f}, {"S", AK_S, 1.0f},
         {"D", AK_D, 1.0f}, {"F", AK_F, 1.0f}, {"G", AK_G, 1.0f}, {"H", AK_H, 1.0f},
         {"J", AK_J, 1.0f}, {"K", AK_K, 1.0f}, {"L", AK_L, 1.0f}, {";", AK_SEMICOLON, 1.0f},
         {"'", AK_QUOTE, 1.0f}, {"Ret", AK_RET, 1.5f}},
        // Row 4: Shift <> ZXCVBNM,./ Shift Up
        {{"Shft", AK_LSH, 1.5f}, {"<>", AK_LTGT, 1.0f}, {"Z", AK_Z, 1.0f}, {"X", AK_X, 1.0f},
         {"C", AK_C, 1.0f}, {"V", AK_V, 1.0f}, {"B", AK_B, 1.0f}, {"N", AK_N, 1.0f},
         {"M", AK_M, 1.0f}, {",", AK_COMMA, 1.0f}, {".", AK_PERIOD, 1.0f}, {"/", AK_SLASH, 1.0f},
         {"Shft", AK_RSH, 1.3f}, {"\u25B2", AK_UP, 1.0f}},
        // Row 5: Alt Amiga Space Amiga Alt Left Down Right
        {{"Alt", AK_LALT, 1.3f}, {"A\u25C0", AK_LAMI, 1.2f}, {" ", AK_SPC, 5.0f},
         {"A\u25B6", AK_RAMI, 1.2f}, {"Alt", AK_RALT, 1.3f},
         {"\u25C0", AK_LF, 1.0f}, {"\u25BC", AK_DN, 1.0f}, {"\u25B6", AK_RT, 1.0f}},
    };

    // Sticky modifier state
    private boolean mShiftActive = false;
    private boolean mCtrlActive = false;
    private boolean mAltActive = false;

    private boolean mVisible = false;

    public AmiberryVirtualKeyboard(Context context) {
        super(context);
        setVisibility(View.GONE);
        buildKeyboard(context);
    }

    private void buildKeyboard(Context context) {
        float d = context.getResources().getDisplayMetrics().density;

        // Outer container with background
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD1A1A1A);
        bg.setCornerRadii(new float[]{12*d, 12*d, 12*d, 12*d, 0, 0, 0, 0});
        container.setBackground(bg);
        container.setPadding((int)(4*d), (int)(6*d), (int)(4*d), (int)(4*d));

        // Wrap in a HorizontalScrollView so it doesn't clip on narrow screens
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setFillViewport(true);
        scroller.setHorizontalScrollBarEnabled(false);

        LinearLayout scrollContent = new LinearLayout(context);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setGravity(Gravity.CENTER_HORIZONTAL);

        for (Object[][] row : ROWS) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER_HORIZONTAL);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowLp.topMargin = (int)(1 * d);

            for (Object[] key : row) {
                String label = (String) key[0];
                int code = (Integer) key[1];
                float relWidth = (Float) key[2];

                Button btn = new Button(context);
                btn.setText(label);
                btn.setAllCaps(false);
                btn.setTextColor(0xFFEEEEEE);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                btn.setIncludeFontPadding(false);
                btn.setMinHeight(0);
                btn.setMinimumHeight(0);
                btn.setMinWidth(0);
                btn.setMinimumWidth(0);

                int keyW = (int)(relWidth * 34 * d);
                int keyH = (int)(31 * d);
                btn.setPadding((int)(2*d), (int)(1*d), (int)(2*d), (int)(1*d));

                GradientDrawable keyBg = new GradientDrawable();
                keyBg.setColor(0xFF333333);
                keyBg.setCornerRadius(4 * d);
                keyBg.setStroke(1, 0xFF555555);
                btn.setBackground(keyBg);

                LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(keyW, keyH);
                kLp.setMargins((int)(1*d), 0, (int)(1*d), 0);

                boolean isModifier = (code == AK_LSH || code == AK_RSH ||
                                      code == AK_CTRL || code == AK_CAPSLOCK ||
                                      code == AK_LALT || code == AK_RALT ||
                                      code == AK_LAMI || code == AK_RAMI);

                btn.setOnTouchListener((v, ev) -> {
                    int action = ev.getActionMasked();
                    if (isModifier) {
                        if (action == MotionEvent.ACTION_DOWN) {
                            handleModifierToggle(code, btn);
                        }
                    } else {
                        if (action == MotionEvent.ACTION_DOWN) {
                            sendKey(code, 1);
                            highlightKey(btn, true);
                        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            sendKey(code, 0);
                            highlightKey(btn, false);
                            // Auto-release any sticky modifiers after a normal key press
                            releaseAllModifiers();
                        }
                    }
                    return true;
                });

                rowLayout.addView(btn, kLp);
            }

            scrollContent.addView(rowLayout, rowLp);
        }

        scroller.addView(scrollContent);
        container.addView(scroller);

        // Position at the bottom of the screen
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.BOTTOM;
        addView(container, lp);
    }

    private void handleModifierToggle(int code, Button btn) {
        boolean nowActive;
        switch (code) {
            case AK_LSH: case AK_RSH:
                mShiftActive = !mShiftActive;
                nowActive = mShiftActive;
                break;
            case AK_CTRL:
                mCtrlActive = !mCtrlActive;
                nowActive = mCtrlActive;
                break;
            case AK_LALT: case AK_RALT:
                mAltActive = !mAltActive;
                nowActive = mAltActive;
                break;
            case AK_CAPSLOCK:
            case AK_LAMI: case AK_RAMI:
                // Toggle press/release
                sendKey(code, 1);
                btn.postDelayed(() -> sendKey(code, 0), 100);
                return;
            default:
                return;
        }
        sendKey(code, nowActive ? 1 : 0);
        highlightModifier(btn, nowActive);
    }

    private void releaseAllModifiers() {
        if (mShiftActive) {
            mShiftActive = false;
            sendKey(AK_LSH, 0);
            sendKey(AK_RSH, 0);
        }
        if (mCtrlActive) {
            mCtrlActive = false;
            sendKey(AK_CTRL, 0);
        }
        if (mAltActive) {
            mAltActive = false;
            sendKey(AK_LALT, 0);
            sendKey(AK_RALT, 0);
        }
        // Update visual state of all modifier buttons
        refreshModifierVisuals();
    }

    private void refreshModifierVisuals() {
        // Walk through all keys to update modifier button visuals
        try {
            ViewGroup container = (ViewGroup) getChildAt(0);
            if (container == null) return;
            HorizontalScrollView sv = (HorizontalScrollView) container.getChildAt(0);
            if (sv == null) return;
            ViewGroup content = (ViewGroup) sv.getChildAt(0);
            if (content == null) return;
            for (int r = 0; r < content.getChildCount(); r++) {
                ViewGroup row = (ViewGroup) content.getChildAt(r);
                for (int k = 0; k < row.getChildCount(); k++) {
                    View v = row.getChildAt(k);
                    if (v instanceof Button) {
                        highlightModifier((Button) v, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void highlightKey(Button btn, boolean pressed) {
        try {
            GradientDrawable bg = (GradientDrawable) btn.getBackground();
            bg.setColor(pressed ? 0xFF1565C0 : 0xFF333333);
        } catch (Throwable ignored) {}
    }

    private void highlightModifier(Button btn, boolean active) {
        try {
            GradientDrawable bg = (GradientDrawable) btn.getBackground();
            bg.setColor(active ? 0xFF4CAF50 : 0xFF333333);
        } catch (Throwable ignored) {}
    }

    private void sendKey(int keycode, int pressed) {
        try {
            AmiberryActivity.nativeSendAmigaKey(keycode, pressed);
        } catch (Throwable ignored) {}
    }

    public void toggle() {
        mVisible = !mVisible;
        setVisibility(mVisible ? View.VISIBLE : View.GONE);
    }

    public boolean isActive() {
        return mVisible;
    }

    public void hide() {
        mVisible = false;
        setVisibility(View.GONE);
    }
}
