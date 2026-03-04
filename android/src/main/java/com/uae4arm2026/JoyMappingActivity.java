package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live press-to-detect controller mapper.
 *
 * Press any button on your physical controller — the screen shows which
 * button was detected and what game action (if any) it is currently assigned to.
 * Tap the detected row (or the "Assign" button) to pick a new game action.
 *
 * Supports per-game overrides when a {@code game_identifier} Intent extra is
 * supplied; otherwise edits the global defaults.
 */
public class JoyMappingActivity extends Activity {

    private static final String TAG = "JoyMapping";

    /** Intent extra key. */
    public static final String EXTRA_GAME_IDENTIFIER = "game_identifier";

    /** SharedPreferences file used for per-game joy mappings. */
    static final String PREFS_PER_GAME = "joy_mappings_per_game";

    // ── Game actions (what each button can be mapped to) ────────────────────
    static final String[] ACTION_LABELS = {
        "Unassigned",
        "Joystick Up",
        "Joystick Down",
        "Joystick Left",
        "Joystick Right",
        "Joystick Fire",
        "Joystick 2nd Fire",
        "Joystick 3rd Fire",
        "CD32 Red",
        "CD32 Blue",
        "CD32 Green",
        "CD32 Yellow",
        "CD32 Play",
        "CD32 Rewind",
        "CD32 Fast Forward",
        "──────── Mouse ────────",
        "🖱 Mouse Up",
        "🖱 Mouse Down",
        "🖱 Mouse Left",
        "🖱 Mouse Right",
        "🖱 Mouse Left Click",
        "🖱 Mouse Right Click",
        "🖱 Mouse Middle Click",
        "──────── Keyboard ────────",
        "⌨ Space",
        "⌨ Return",
        "⌨ Escape",
        "⌨ Tab",
        "⌨ Backspace",
        "⌨ Delete",
        "⌨ Cursor Up",
        "⌨ Cursor Down",
        "⌨ Cursor Left",
        "⌨ Cursor Right",
        "⌨ F1",
        "⌨ F2",
        "⌨ F3",
        "⌨ F4",
        "⌨ F5",
        "⌨ F6",
        "⌨ F7",
        "⌨ F8",
        "⌨ F9",
        "⌨ F10",
        "──────── Letters ────────",
        "⌨ A",
        "⌨ B",
        "⌨ C",
        "⌨ D",
        "⌨ E",
        "⌨ F",
        "⌨ G",
        "⌨ H",
        "⌨ I",
        "⌨ J",
        "⌨ K",
        "⌨ L",
        "⌨ M",
        "⌨ N",
        "⌨ O",
        "⌨ P",
        "⌨ Q",
        "⌨ R",
        "⌨ S",
        "⌨ T",
        "⌨ U",
        "⌨ V",
        "⌨ W",
        "⌨ X",
        "⌨ Y",
        "⌨ Z",
        "──────── Numbers ────────",
        "⌨ 0",
        "⌨ 1",
        "⌨ 2",
        "⌨ 3",
        "⌨ 4",
        "⌨ 5",
        "⌨ 6",
        "⌨ 7",
        "⌨ 8",
        "⌨ 9"
    };

    static final String[] ACTION_VALUES = {
        "",
        "UP",
        "DOWN",
        "LEFT",
        "RIGHT",
        "FIRE1",
        "FIRE2",
        "FIRE3",
        "CD32_RED",
        "CD32_BLUE",
        "CD32_GREEN",
        "CD32_YELLOW",
        "CD32_PLAY",
        "CD32_RWD",
        "CD32_FFW",
        "__SECTION_MOUSE__",
        "MOUSE_UP",
        "MOUSE_DOWN",
        "MOUSE_LEFT",
        "MOUSE_RIGHT",
        "MOUSE_LEFT_BUTTON",
        "MOUSE_RIGHT_BUTTON",
        "MOUSE_MIDDLE_BUTTON",
        "__SECTION_KEYBOARD__",
        "KEY_SPACE",
        "KEY_RETURN",
        "KEY_ESC",
        "KEY_TAB",
        "KEY_BACKSPACE",
        "KEY_DEL",
        "KEY_CURSOR_UP",
        "KEY_CURSOR_DOWN",
        "KEY_CURSOR_LEFT",
        "KEY_CURSOR_RIGHT",
        "KEY_F1",
        "KEY_F2",
        "KEY_F3",
        "KEY_F4",
        "KEY_F5",
        "KEY_F6",
        "KEY_F7",
        "KEY_F8",
        "KEY_F9",
        "KEY_F10",
        "__SECTION_LETTERS__",
        "KEY_A",
        "KEY_B",
        "KEY_C",
        "KEY_D",
        "KEY_E",
        "KEY_F",
        "KEY_G",
        "KEY_H",
        "KEY_I",
        "KEY_J",
        "KEY_K",
        "KEY_L",
        "KEY_M",
        "KEY_N",
        "KEY_O",
        "KEY_P",
        "KEY_Q",
        "KEY_R",
        "KEY_S",
        "KEY_T",
        "KEY_U",
        "KEY_V",
        "KEY_W",
        "KEY_X",
        "KEY_Y",
        "KEY_Z",
        "__SECTION_NUMBERS__",
        "KEY_0",
        "KEY_1",
        "KEY_2",
        "KEY_3",
        "KEY_4",
        "KEY_5",
        "KEY_6",
        "KEY_7",
        "KEY_8",
        "KEY_9"
    };

    // ── Physical buttons we track (label → pref key) ───────────────────────
    // Ordered list so we can build stable rows.
    private static final String[][] BUTTONS = {
        {"A",          UaeOptionKeys.UAE_INPUT_MAP_BTN_A},
        {"B",          UaeOptionKeys.UAE_INPUT_MAP_BTN_B},
        {"X",          UaeOptionKeys.UAE_INPUT_MAP_BTN_X},
        {"Y",          UaeOptionKeys.UAE_INPUT_MAP_BTN_Y},
        {"L1",         UaeOptionKeys.UAE_INPUT_MAP_BTN_L1},
        {"R1",         UaeOptionKeys.UAE_INPUT_MAP_BTN_R1},
        {"Back",       UaeOptionKeys.UAE_INPUT_MAP_BTN_BACK},
        {"Start",      UaeOptionKeys.UAE_INPUT_MAP_BTN_START},
        {"DPad Up",    UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP},
        {"DPad Down",  UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN},
        {"DPad Left",  UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT},
        {"DPad Right", UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT},
    };

    // ── Per-row UI state ───────────────────────────────────────────────────
    /** Maps pref key → its row container view so we can highlight on press. */
    private final LinkedHashMap<String, View> rowViews = new LinkedHashMap<>();
    /** Maps pref key → the action-label TextView on the right side. */
    private final LinkedHashMap<String, TextView> actionLabels = new LinkedHashMap<>();
    /** Maps pref key → currently chosen action value (in-memory, saved on Save). */
    private final LinkedHashMap<String, String> currentMappings = new LinkedHashMap<>();

    private String gameIdentifier;
    private TextView tvStatus;
    private TextView tvScope;
    private String lastHighlightedKey = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean mPromptingExistingProfile = false;

    // ── Preference helpers ─────────────────────────────────────────────────

    private SharedPreferences globalPrefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private SharedPreferences perGamePrefs() {
        return getSharedPreferences(PREFS_PER_GAME, MODE_PRIVATE);
    }

    static String perGameKey(String gameId, String globalKey) {
        return gameId + "/" + globalKey;
    }

    private static String legacyGameId(String gameId) {
        if (gameId == null) return null;
        int at = gameId.indexOf('@');
        if (at <= 0) return gameId;
        return gameId.substring(0, at);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_joy_mapping);

        gameIdentifier = getIntent().getStringExtra(EXTRA_GAME_IDENTIFIER);
        if (gameIdentifier != null && gameIdentifier.trim().isEmpty()) {
            gameIdentifier = null;
        }

        tvScope = findViewById(R.id.tvJoyMapScope);
        tvStatus = findViewById(R.id.tvJoyMapStatus);
        updateScopeLabel();
        setStatus("Press any button on your controller…");

        LinearLayout container = findViewById(R.id.mappingContainer);
        float d = getResources().getDisplayMetrics().density;

        // Load existing mappings into memory.
        loadMappings();

        // Build a row for each physical button.
        for (String[] btn : BUTTONS) {
            String label = btn[0];
            String prefKey = btn[1];
            container.addView(buildRow(label, prefKey, d));
        }

        maybePromptExistingProfile();

        // Bottom buttons.
        Button btnSave = findViewById(R.id.btnJoyMapSave);
        Button btnBack = findViewById(R.id.btnJoyMapBack);
        Button btnReset = findViewById(R.id.btnJoyMapReset);

        btnSave.setOnClickListener(v -> { save(); Toast.makeText(this, "Mappings saved", Toast.LENGTH_SHORT).show(); finish(); });
        btnBack.setOnClickListener(v -> finish());
        btnReset.setOnClickListener(v -> confirmReset());
    }

    // ── Build one mapping row ──────────────────────────────────────────────

    private View buildRow(String label, String prefKey, float d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(8 * d);
        bg.setColor(0xFF2A2A2A);
        row.setBackground(bg);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(4);
        row.setLayoutParams(rowLp);

        // Left: physical button name.
        TextView tvBtn = new TextView(this);
        tvBtn.setText(label);
        tvBtn.setTextColor(0xFFCCCCCC);
        tvBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tvBtn, lpBtn);

        // Middle: arrow.
        TextView tvArrow = new TextView(this);
        tvArrow.setText("→");
        tvArrow.setTextColor(0xFF888888);
        tvArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvArrow.setPadding(dp(8), 0, dp(8), 0);
        row.addView(tvArrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Right: assigned game action (or "Not mapped").
        TextView tvAction = new TextView(this);
        String actionVal = currentMappings.get(prefKey);
        tvAction.setText(actionLabelFor(actionVal));
        tvAction.setTextColor(actionVal != null && !actionVal.isEmpty() ? 0xFF4CAF50 : 0xFFFF5252);
        tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvAction.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lpAction = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        row.addView(tvAction, lpAction);

        // Tap the row to reassign.
        row.setOnClickListener(v -> showActionPicker(prefKey));
        row.setClickable(true);
        row.setFocusable(true);

        rowViews.put(prefKey, row);
        actionLabels.put(prefKey, tvAction);
        return row;
    }

    // ── Live controller input detection ────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String prefKey = keyCodeToPrefKey(keyCode);
        if (prefKey != null) {
            highlightRow(prefKey);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Consume gamepad key-ups so they don't trigger Back etc.
        if (keyCodeToPrefKey(keyCode) != null) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.getSource() & InputDevice.SOURCE_GAMEPAD) != 0) {
            // Check D-pad / hat axes.
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (hatY < -0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP); return true; }
            if (hatY >  0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN); return true; }
            if (hatX < -0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT); return true; }
            if (hatX >  0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT); return true; }

            // Analog stick as directional.
            float axisX = event.getAxisValue(MotionEvent.AXIS_X);
            float axisY = event.getAxisValue(MotionEvent.AXIS_Y);
            if (axisY < -0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP); return true; }
            if (axisY >  0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN); return true; }
            if (axisX < -0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT); return true; }
            if (axisX >  0.5f) { highlightRow(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT); return true; }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** Map Android KeyEvent keycodes to our pref keys. */
    private String keyCodeToPrefKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return UaeOptionKeys.UAE_INPUT_MAP_BTN_A;
            case KeyEvent.KEYCODE_BUTTON_B: return UaeOptionKeys.UAE_INPUT_MAP_BTN_B;
            case KeyEvent.KEYCODE_BUTTON_X: return UaeOptionKeys.UAE_INPUT_MAP_BTN_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return UaeOptionKeys.UAE_INPUT_MAP_BTN_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return UaeOptionKeys.UAE_INPUT_MAP_BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return UaeOptionKeys.UAE_INPUT_MAP_BTN_R1;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK: return UaeOptionKeys.UAE_INPUT_MAP_BTN_BACK;
            case KeyEvent.KEYCODE_BUTTON_START: return UaeOptionKeys.UAE_INPUT_MAP_BTN_START;
            case KeyEvent.KEYCODE_DPAD_UP: return UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT;
            default: return null;
        }
    }

    // ── Highlight & pick ───────────────────────────────────────────────────

    private void highlightRow(String prefKey) {
        // Clear previous highlight.
        if (lastHighlightedKey != null) {
            View prev = rowViews.get(lastHighlightedKey);
            if (prev != null) {
                ((GradientDrawable) prev.getBackground()).setColor(0xFF2A2A2A);
            }
        }

        View row = rowViews.get(prefKey);
        if (row == null) return;

        ((GradientDrawable) row.getBackground()).setColor(0xFF1B5E20); // dark green flash
        lastHighlightedKey = prefKey;

        // Find human-readable button name.
        String btnName = prefKeyToLabel(prefKey);
        String actionVal = currentMappings.get(prefKey);
        String actionText = actionLabelFor(actionVal);

        setStatus("Detected: " + btnName + "  →  " + actionText +
                  "\nTap the row or press again then tap to reassign");

        // Scroll to make visible.
        ScrollView sv = findViewById(R.id.mappingScroll);
        if (sv != null) {
            sv.post(() -> sv.smoothScrollTo(0, row.getTop()));
        }

        // Auto-dim after 3 seconds.
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            ((GradientDrawable) row.getBackground()).setColor(0xFF2A2A2A);
            setStatus("Press any button on your controller…");
        }, 3000);
    }

    /** Show a dialog to pick the game action for a button. */
    private void showActionPicker(String prefKey) {
        String btnName = prefKeyToLabel(prefKey);
        String current = currentMappings.get(prefKey);
        int checkedIdx = indexOfActionValue(current);

        new AlertDialog.Builder(this)
                .setTitle("Assign action for " + btnName)
                .setSingleChoiceItems(ACTION_LABELS, checkedIdx, (dialog, which) -> {
                    String newAction = ACTION_VALUES[which];
                    if (newAction.startsWith("__SECTION_")) {
                        return;
                    }
                    currentMappings.put(prefKey, newAction);

                    // Update row UI.
                    TextView tv = actionLabels.get(prefKey);
                    if (tv != null) {
                        tv.setText(actionLabelFor(newAction));
                        tv.setTextColor(newAction.isEmpty() ? 0xFFFF5252 : 0xFF4CAF50);
                    }

                    setStatus(btnName + " → " + ACTION_LABELS[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Label helpers ──────────────────────────────────────────────────────

    private static String actionLabelFor(String actionValue) {
        if (actionValue == null || actionValue.isEmpty()) return "Not mapped";
        int idx = indexOfActionValue(actionValue);
        return (idx > 0) ? ACTION_LABELS[idx] : actionValue;
    }

    static int indexOfActionValue(String action) {
        if (action == null) return 0;
        for (int i = 0; i < ACTION_VALUES.length; i++) {
            if (action.equalsIgnoreCase(ACTION_VALUES[i])) return i;
        }
        return 0;
    }

    private String prefKeyToLabel(String prefKey) {
        for (String[] btn : BUTTONS) {
            if (btn[1].equals(prefKey)) return btn[0];
        }
        return prefKey;
    }

    private void setStatus(String msg) {
        if (tvStatus != null) tvStatus.setText(msg);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── Scope label ────────────────────────────────────────────────────────

    private void updateScopeLabel() {
        if (tvScope == null) return;
        if (gameIdentifier != null) {
            tvScope.setText("Config: " + gameIdentifier);
            tvScope.setTextColor(0xFF81C784);
        } else {
            tvScope.setText("Config: Global");
            tvScope.setTextColor(0xFFBBBBBB);
        }
    }

    private void maybePromptExistingProfile() {
        if (gameIdentifier == null || mPromptingExistingProfile) return;
        if (!hasStoredPerGameProfile(gameIdentifier)) return;

        mPromptingExistingProfile = true;
        new AlertDialog.Builder(this)
                .setTitle("Existing profile found")
                .setMessage("Profile \"" + gameIdentifier + "\" already exists.\n\nLoad existing mappings or start from global mappings?")
                .setPositiveButton("Load Existing", (d, w) -> {
                    loadMappings();
                    refreshAllRows();
                    setStatus("Loaded existing config: " + gameIdentifier);
                })
                .setNegativeButton("Start Global", (d, w) -> {
                    loadGlobalMappingsOnly();
                    refreshAllRows();
                    setStatus("Using global mappings. Save to overwrite config: " + gameIdentifier);
                })
                .setCancelable(true)
                .show();
    }

    private boolean hasStoredPerGameProfile(String gameId) {
        if (gameId == null) return false;
        SharedPreferences perGame = perGamePrefs();
        for (String[] btn : BUTTONS) {
            if (perGame.contains(perGameKey(gameId, btn[1]))) {
                return true;
            }
        }
        return false;
    }

    private void loadGlobalMappingsOnly() {
        SharedPreferences global = globalPrefs();
        currentMappings.clear();
        for (String[] btn : BUTTONS) {
            String prefKey = btn[1];
            currentMappings.put(prefKey, global.getString(prefKey, ""));
        }
    }

    // ── Load / Save ────────────────────────────────────────────────────────

    private void loadMappings() {
        SharedPreferences global = globalPrefs();
        SharedPreferences perGame = (gameIdentifier != null) ? perGamePrefs() : null;
        String legacyGame = legacyGameId(gameIdentifier);
        currentMappings.clear();
        for (String[] btn : BUTTONS) {
            String prefKey = btn[1];
            String stored = null;
            if (perGame != null) {
                stored = perGame.getString(perGameKey(gameIdentifier, prefKey), null);
                if (stored == null && legacyGame != null && !legacyGame.equals(gameIdentifier)) {
                    stored = perGame.getString(perGameKey(legacyGame, prefKey), null);
                }
            }
            if (stored == null) {
                stored = global.getString(prefKey, "");
            }
            currentMappings.put(prefKey, stored);
        }
    }

    private void save() {
        if (gameIdentifier != null) {
            SharedPreferences.Editor e = perGamePrefs().edit();
            for (Map.Entry<String, String> entry : currentMappings.entrySet()) {
                String pgKey = perGameKey(gameIdentifier, entry.getKey());
                String v = entry.getValue();
                if (v == null || v.isEmpty()) {
                    e.remove(pgKey);
                } else {
                    e.putString(pgKey, v);
                }
            }
            e.apply();
        } else {
            SharedPreferences.Editor e = globalPrefs().edit();
            for (Map.Entry<String, String> entry : currentMappings.entrySet()) {
                String v = entry.getValue();
                if (v == null || v.isEmpty()) {
                    e.remove(entry.getKey());
                } else {
                    e.putString(entry.getKey(), v);
                }
            }
            e.apply();
        }
    }

    // ── Reset ──────────────────────────────────────────────────────────────

    private void confirmReset() {
        String msg = (gameIdentifier != null)
                ? "Clear per-game overrides for \"" + gameIdentifier + "\"?\nGlobal defaults will be used instead."
                : "Reset all global mappings to factory defaults?";
        new AlertDialog.Builder(this)
                .setTitle("Reset Mappings")
                .setMessage(msg)
                .setPositiveButton("Reset", (d, w) -> doReset())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doReset() {
        if (gameIdentifier != null) {
            SharedPreferences.Editor e = perGamePrefs().edit();
            for (String[] btn : BUTTONS) {
                e.remove(perGameKey(gameIdentifier, btn[1]));
            }
            e.apply();
        } else {
            SharedPreferences.Editor e = globalPrefs().edit();
            e.putString(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP,    "UP");
            e.putString(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN,  "DOWN");
            e.putString(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT,  "LEFT");
            e.putString(UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT, "RIGHT");
            e.putString(UaeOptionKeys.UAE_INPUT_MAP_BTN_A,          "FIRE1");
            e.putString(UaeOptionKeys.UAE_INPUT_MAP_BTN_B,          "FIRE2");
            e.remove(UaeOptionKeys.UAE_INPUT_MAP_BTN_X);
            e.remove(UaeOptionKeys.UAE_INPUT_MAP_BTN_Y);
            e.remove(UaeOptionKeys.UAE_INPUT_MAP_BTN_L1);
            e.remove(UaeOptionKeys.UAE_INPUT_MAP_BTN_R1);
            e.remove(UaeOptionKeys.UAE_INPUT_MAP_BTN_BACK);
            e.remove(UaeOptionKeys.UAE_INPUT_MAP_BTN_START);
            e.apply();
        }
        loadMappings();
        refreshAllRows();
        Toast.makeText(this, "Mappings reset", Toast.LENGTH_SHORT).show();
    }

    private void refreshAllRows() {
        for (String[] btn : BUTTONS) {
            String prefKey = btn[1];
            TextView tv = actionLabels.get(prefKey);
            if (tv != null) {
                String v = currentMappings.get(prefKey);
                tv.setText(actionLabelFor(v));
                tv.setTextColor(v != null && !v.isEmpty() ? 0xFF4CAF50 : 0xFFFF5252);
            }
        }
    }

    // ── Static helpers used by AmiberryActivity ────────────────────────────

    static String getEffectiveMapping(SharedPreferences global,
                                      SharedPreferences perGame,
                                      String gameId,
                                      String globalKey) {
        if (gameId != null && perGame != null) {
            String v = perGame.getString(perGameKey(gameId, globalKey), null);
            if (v != null) return v;
            String legacy = legacyGameId(gameId);
            if (legacy != null && !legacy.equals(gameId)) {
                v = perGame.getString(perGameKey(legacy, globalKey), null);
                if (v != null) return v;
            }
        }
        return global.getString(globalKey, "");
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
