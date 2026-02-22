package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class InputOptionsActivity extends Activity {

    private CheckBox mUsePreset;
    private Spinner mControllerSource;
    private Spinner mPort0;
    private Spinner mPort1;
    private SeekBar mMouseSpeed;
    private TextView mMouseSpeedInfo;
    private CheckBox mAutofire;

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private static final List<String> PORT_LABELS = Arrays.asList(
        "Mouse",
        "External Joystick 0",
        "External Joystick 1",
        "CD32 Pad"
    );

    private static final List<String> CONTROLLER_SOURCE_LABELS = Arrays.asList(
        "External Controller",
        "Virtual Joypad"
    );

    private static final List<String> CONTROLLER_SOURCE_VALUES = Arrays.asList(
        "external",
        "virtual"
    );

    private static final List<String> PORT_VALUES = Arrays.asList(
        "mouse",
        "joy0",
        "joy1",
        "cd32"
    );

    private static int indexOfIgnoreCase(List<String> values, String needle, int defIdx) {
        if (needle == null) return defIdx;
        for (int i = 0; i < values.size(); i++) {
            if (needle.equalsIgnoreCase(values.get(i))) return i;
        }
        return defIdx;
    }

    private boolean hasOverrides(SharedPreferences p) {
        return p.contains(UaeOptionKeys.UAE_INPUT_PORT0_MODE)
            || p.contains(UaeOptionKeys.UAE_INPUT_PORT1_MODE)
            || p.contains(UaeOptionKeys.UAE_INPUT_MOUSE_SPEED)
            || p.contains(UaeOptionKeys.UAE_INPUT_AUTOFIRE_ENABLED);
    }

    private void setControlsEnabled(boolean enabled) {
        mControllerSource.setEnabled(enabled);
        mPort0.setEnabled(enabled);
        mPort1.setEnabled(enabled);
        mMouseSpeed.setEnabled(enabled);
        mAutofire.setEnabled(enabled);
    }

    private void load() {
        SharedPreferences p = prefs();
        boolean usePreset = !hasOverrides(p);
        mUsePreset.setChecked(usePreset);

        String source = p.getString(UaeOptionKeys.UAE_INPUT_CONTROLLER_SOURCE, "external");
        mControllerSource.setSelection(indexOfIgnoreCase(CONTROLLER_SOURCE_VALUES, source, 0));

        String p0 = p.getString(UaeOptionKeys.UAE_INPUT_PORT0_MODE, "mouse");
        mPort0.setSelection(indexOfIgnoreCase(PORT_VALUES, p0, 0));

        String p1 = p.getString(UaeOptionKeys.UAE_INPUT_PORT1_MODE, "joy0");
        mPort1.setSelection(indexOfIgnoreCase(PORT_VALUES, p1, 1));

        int speed = p.getInt(UaeOptionKeys.UAE_INPUT_MOUSE_SPEED, 100);
        mMouseSpeed.setProgress(speed);
        mMouseSpeedInfo.setText(speed + "%");

        boolean autofire = p.getBoolean(UaeOptionKeys.UAE_INPUT_AUTOFIRE_ENABLED, false);
        mAutofire.setChecked(autofire);

        setControlsEnabled(!usePreset);
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();

        if (mUsePreset.isChecked()) {
            e.remove(UaeOptionKeys.UAE_INPUT_CONTROLLER_SOURCE);
            e.remove(UaeOptionKeys.UAE_INPUT_PORT0_MODE);
            e.remove(UaeOptionKeys.UAE_INPUT_PORT1_MODE);
            e.remove(UaeOptionKeys.UAE_INPUT_MOUSE_SPEED);
            e.remove(UaeOptionKeys.UAE_INPUT_AUTOFIRE_ENABLED);
            e.apply();
            return;
        }

        int sourceIdx = mControllerSource.getSelectedItemPosition();
        if (sourceIdx < 0) sourceIdx = 0;
        e.putString(UaeOptionKeys.UAE_INPUT_CONTROLLER_SOURCE, CONTROLLER_SOURCE_VALUES.get(sourceIdx));

        int p0Idx = mPort0.getSelectedItemPosition();
        if (p0Idx < 0) p0Idx = 0;
        e.putString(UaeOptionKeys.UAE_INPUT_PORT0_MODE, PORT_VALUES.get(p0Idx));

        int p1Idx = mPort1.getSelectedItemPosition();
        if (p1Idx < 0) p1Idx = 1;
        e.putString(UaeOptionKeys.UAE_INPUT_PORT1_MODE, PORT_VALUES.get(p1Idx));

        e.putInt(UaeOptionKeys.UAE_INPUT_MOUSE_SPEED, mMouseSpeed.getProgress());
        e.putBoolean(UaeOptionKeys.UAE_INPUT_AUTOFIRE_ENABLED, mAutofire.isChecked());

        e.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_options); // We need to create this layout too!

        mUsePreset = findViewById(R.id.chkInputUsePreset);
        mControllerSource = findViewById(R.id.spinnerControllerSource);
        mPort0 = findViewById(R.id.spinnerPort0);
        mPort1 = findViewById(R.id.spinnerPort1);
        mMouseSpeed = findViewById(R.id.seekMouseSpeed);
        mMouseSpeedInfo = findViewById(R.id.txtMouseSpeedInfo);
        mAutofire = findViewById(R.id.chkAutofire);

        mControllerSource.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, CONTROLLER_SOURCE_LABELS));
        ((ArrayAdapter<?>) mControllerSource.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mPort0.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PORT_LABELS));
        ((ArrayAdapter<?>) mPort0.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mPort1.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PORT_LABELS));
        ((ArrayAdapter<?>) mPort1.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mMouseSpeed.setMax(500); // 500% max speed

        load();

        mUsePreset.setOnCheckedChangeListener((b, checked) -> setControlsEnabled(!checked));

        mMouseSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mMouseSpeedInfo.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Button btnSave = findViewById(R.id.btnInputSave);
        Button btnBack = findViewById(R.id.btnInputBack);
        Button btnJoyMapping = findViewById(R.id.btnJoyMapping);

        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnBack.setOnClickListener(v -> finish());
        btnJoyMapping.setOnClickListener(v -> startActivity(new Intent(this, JoyMappingActivity.class)));
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
