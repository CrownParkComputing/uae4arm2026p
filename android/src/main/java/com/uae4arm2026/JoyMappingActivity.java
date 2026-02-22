package com.uae4arm2026;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class JoyMappingActivity extends Activity {

    private static final String[] ACTION_LABELS = new String[]{
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
        "CD32 Fast Forward"
    };

    private static final String[] ACTION_VALUES = new String[]{
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
        "CD32_FFW"
    };

    private final LinkedHashMap<String, String> buttonPrefMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, Spinner> buttonSpinnerMap = new LinkedHashMap<>();

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_joy_mapping);

        initButtonMap();

        LinearLayout container = findViewById(R.id.mappingContainer);
        ArrayAdapter<String> actionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ACTION_LABELS);
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for (Map.Entry<String, String> entry : buttonPrefMap.entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView label = new TextView(this);
            label.setText(entry.getKey());
            label.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, lpLabel);

            Spinner spinner = new Spinner(this);
            spinner.setAdapter(actionAdapter);
            LinearLayout.LayoutParams lpSpinner = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(spinner, lpSpinner);

            container.addView(row);
            buttonSpinnerMap.put(entry.getValue(), spinner);
        }

        load();

        Button btnSave = findViewById(R.id.btnJoyMapSave);
        Button btnBack = findViewById(R.id.btnJoyMapBack);
        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnBack.setOnClickListener(v -> finish());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void initButtonMap() {
        buttonPrefMap.put("A", UaeOptionKeys.UAE_INPUT_MAP_BTN_A);
        buttonPrefMap.put("B", UaeOptionKeys.UAE_INPUT_MAP_BTN_B);
        buttonPrefMap.put("X", UaeOptionKeys.UAE_INPUT_MAP_BTN_X);
        buttonPrefMap.put("Y", UaeOptionKeys.UAE_INPUT_MAP_BTN_Y);
        buttonPrefMap.put("L1", UaeOptionKeys.UAE_INPUT_MAP_BTN_L1);
        buttonPrefMap.put("R1", UaeOptionKeys.UAE_INPUT_MAP_BTN_R1);
        buttonPrefMap.put("Back", UaeOptionKeys.UAE_INPUT_MAP_BTN_BACK);
        buttonPrefMap.put("Start", UaeOptionKeys.UAE_INPUT_MAP_BTN_START);
        buttonPrefMap.put("DPad Up", UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP);
        buttonPrefMap.put("DPad Down", UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN);
        buttonPrefMap.put("DPad Left", UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT);
        buttonPrefMap.put("DPad Right", UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT);
    }

    private static int indexOfActionValue(String action) {
        if (action == null) return 0;
        for (int i = 0; i < ACTION_VALUES.length; i++) {
            if (action.equalsIgnoreCase(ACTION_VALUES[i])) return i;
        }
        return 0;
    }

    private void load() {
        SharedPreferences p = prefs();
        for (Map.Entry<String, Spinner> entry : buttonSpinnerMap.entrySet()) {
            String stored = p.getString(entry.getKey(), "");
            entry.getValue().setSelection(indexOfActionValue(stored));
        }
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();
        for (Map.Entry<String, Spinner> entry : buttonSpinnerMap.entrySet()) {
            int idx = entry.getValue().getSelectedItemPosition();
            if (idx < 0 || idx >= ACTION_VALUES.length) idx = 0;
            String value = ACTION_VALUES[idx];
            if (value.isEmpty()) {
                e.remove(entry.getKey());
            } else {
                e.putString(entry.getKey(), value);
            }
        }
        e.apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
