package com.uae4arm2026;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.List;

public class ChipsetOptionsActivity extends Activity {

    private Spinner mChipsetCompatibleSpinner;
    private Spinner mVideoAspectSpinner;
    private RadioGroup mChipsetRadio;
    private CheckBox mCycleExactFull;
    private CheckBox mCycleExactMemory;
    private CheckBox mNtsc;

    private static final int VIDEO_ASPECT_4_3 = 0;
    private static final int VIDEO_ASPECT_16_9 = 1;

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private static int indexOf(List<String> values, String needle, int defIdx) {
        if (needle == null) return defIdx;
        for (int i = 0; i < values.size(); i++) {
            if (needle.equalsIgnoreCase(values.get(i))) return i;
        }
        return defIdx;
    }

    private static final String CHIPSET_OCS = "ocs";
    private static final String CHIPSET_ECS_AGNUS = "ecs_agnus";
    private static final String CHIPSET_ECS_DENISE = "ecs_denise";
    private static final String CHIPSET_ECS = "ecs";
    private static final String CHIPSET_AGA = "aga";

    private static List<String> chipsetCompatibleValues() {
        // Mirrors src/cfgfile.cpp cscompa[] labels.
        List<String> v = new ArrayList<>();
        v.add("-");
        v.add("Generic");
        v.add("CDTV");
        v.add("CDTV-CR");
        v.add("CD32");
        v.add("A500");
        v.add("A500+");
        v.add("A600");
        v.add("A1000");
        v.add("A1200");
        v.add("A2000");
        v.add("A3000");
        v.add("A3000T");
        v.add("A4000");
        v.add("A4000T");
        v.add("Velvet");
        v.add("Casablanca");
        v.add("DraCo");
        return v;
    }

    private static List<String> videoAspectLabels() {
        List<String> v = new ArrayList<>();
        v.add("Standard 4:3");
        v.add("Widescreen 16:9");
        return v;
    }

    private void bindSpinners() {
        mChipsetCompatibleSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, chipsetCompatibleValues()));
        ((ArrayAdapter<?>) mChipsetCompatibleSpinner.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mVideoAspectSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, videoAspectLabels()));
        ((ArrayAdapter<?>) mVideoAspectSpinner.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void updateEnabledStates() {
        // Cycle exact checkboxes are mutually exclusive.
        mCycleExactFull.setEnabled(!mCycleExactMemory.isChecked());
        mCycleExactMemory.setEnabled(!mCycleExactFull.isChecked());
    }

    private void load() {
        SharedPreferences p = prefs();
        String chipset = p.getString(UaeOptionKeys.UAE_CHIPSET, null);
        String cycleExact = p.getString(UaeOptionKeys.UAE_CYCLE_EXACT, null);
        String compat = p.getString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, "-");
        boolean ntsc = p.getBoolean(UaeOptionKeys.UAE_NTSC, false);
        int videoAspectMode = p.getInt(UaeOptionKeys.UAE_VIDEO_ASPECT_MODE, VIDEO_ASPECT_16_9);

        if (CHIPSET_ECS_AGNUS.equalsIgnoreCase(chipset)) {
            mChipsetRadio.check(R.id.radioChipsetEcsAgnus);
        } else if (CHIPSET_ECS_DENISE.equalsIgnoreCase(chipset)) {
            mChipsetRadio.check(R.id.radioChipsetEcsDenise);
        } else if (CHIPSET_ECS.equalsIgnoreCase(chipset)) {
            mChipsetRadio.check(R.id.radioChipsetFullEcs);
        } else if (CHIPSET_AGA.equalsIgnoreCase(chipset)) {
            mChipsetRadio.check(R.id.radioChipsetAga);
        } else {
            mChipsetRadio.check(R.id.radioChipsetOcs);
        }

        // Cycle exact: "true" (Full), "memory" (DMA/Memory). Anything else is off.
        mCycleExactFull.setChecked("true".equalsIgnoreCase(cycleExact));
        mCycleExactMemory.setChecked("memory".equalsIgnoreCase(cycleExact));

        mChipsetCompatibleSpinner.setSelection(indexOf(chipsetCompatibleValues(), compat, 0));
        mNtsc.setChecked(ntsc);
        mVideoAspectSpinner.setSelection(videoAspectMode == VIDEO_ASPECT_4_3 ? VIDEO_ASPECT_4_3 : VIDEO_ASPECT_16_9);

        updateEnabledStates();
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();

        String chipsetSel;
        int checked = mChipsetRadio.getCheckedRadioButtonId();
        if (checked == R.id.radioChipsetEcsAgnus) chipsetSel = CHIPSET_ECS_AGNUS;
        else if (checked == R.id.radioChipsetEcsDenise) chipsetSel = CHIPSET_ECS_DENISE;
        else if (checked == R.id.radioChipsetFullEcs) chipsetSel = CHIPSET_ECS;
        else if (checked == R.id.radioChipsetAga) chipsetSel = CHIPSET_AGA;
        else chipsetSel = CHIPSET_OCS;

        e.putString(UaeOptionKeys.UAE_CHIPSET, chipsetSel);

        if (mCycleExactFull.isChecked()) {
            e.putString(UaeOptionKeys.UAE_CYCLE_EXACT, "true");
        } else if (mCycleExactMemory.isChecked()) {
            e.putString(UaeOptionKeys.UAE_CYCLE_EXACT, "memory");
        } else {
            e.remove(UaeOptionKeys.UAE_CYCLE_EXACT);
        }

        String compatSel = String.valueOf(mChipsetCompatibleSpinner.getSelectedItem());
        if (compatSel == null || compatSel.trim().isEmpty()) {
            e.putString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, "-");
        } else {
            e.putString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, compatSel);
        }

        e.putBoolean(UaeOptionKeys.UAE_NTSC, mNtsc.isChecked());
        e.putInt(UaeOptionKeys.UAE_VIDEO_ASPECT_MODE,
            mVideoAspectSpinner.getSelectedItemPosition() == VIDEO_ASPECT_4_3 ? VIDEO_ASPECT_4_3 : VIDEO_ASPECT_16_9);
        e.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chipset_options);

        mChipsetCompatibleSpinner = findViewById(R.id.spinnerChipsetCompatible);
        mVideoAspectSpinner = findViewById(R.id.spinnerVideoAspect);
        mChipsetRadio = findViewById(R.id.radioChipset);
        mCycleExactFull = findViewById(R.id.chkCycleExactFull);
        mCycleExactMemory = findViewById(R.id.chkCycleExactMemory);
        mNtsc = findViewById(R.id.chkNtsc);

        bindSpinners();
        load();
        mCycleExactFull.setOnCheckedChangeListener((b, c) -> updateEnabledStates());
        mCycleExactMemory.setOnCheckedChangeListener((b, c) -> updateEnabledStates());

        Button btnSave = findViewById(R.id.btnChipsetSave);
        Button btnBack = findViewById(R.id.btnChipsetBack);
        Button btnReset = findViewById(R.id.btnChipsetReset);

        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnBack.setOnClickListener(v -> finish());
        btnReset.setOnClickListener(v -> {
            prefs().edit()
                .remove(UaeOptionKeys.UAE_CHIPSET)
                .remove(UaeOptionKeys.UAE_CYCLE_EXACT)
                .putString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, "-")
                .putBoolean(UaeOptionKeys.UAE_NTSC, false)
                .putInt(UaeOptionKeys.UAE_VIDEO_ASPECT_MODE, VIDEO_ASPECT_16_9)
                .apply();
            load();
        });

        // Auto-save when leaving via Back button in Android UI.
        View root = findViewById(android.R.id.content);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Persist any changes even if user leaves via system navigation.
        save();
    }
}
