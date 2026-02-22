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
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CpuFpuOptionsActivity extends Activity {


    private RadioGroup mCpuModelGroup;
    private CheckBox mCpu24Bit;
    private CheckBox mCpuCompatible;
    private CheckBox mCpuDataCache;

    private Spinner mCpuSpeed;
    private Spinner mCpuMultiplier;

    private Spinner mMmuModel;

    private CheckBox mPpcEnable;
    private Spinner mPpcImplementation;
    private Spinner mPpcCpuIdle;

    private RadioGroup mFpuModelGroup;
    private CheckBox mFpuStrict;

    private CheckBox mJitEnable;
    private Button mJitAdvanced;
    private LinearLayout mJitAdvancedLayout;
    private Spinner mJitCacheSize;
    private CheckBox mJitFpuSupport;
    private CheckBox mJitConstJump;
    private Spinner mJitFlushMode;
    private RadioGroup mJitTrustGroup;
    private CheckBox mJitNoFlags;
    private CheckBox mJitCatchFault;

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private static List<String> cpuSpeedValues() {
        List<String> v = new ArrayList<>();
        v.add("(Default)");
        v.add("real");
        v.add("max");
        for (int i = 1; i <= 20; i++) {
            v.add(String.valueOf(i));
        }
        return v;
    }

    private static List<String> cpuMultiplierValues() {
        return Arrays.asList("(Default)", "1", "2", "4", "8", "16");
    }

    private static List<String> mmuModelValues() {
        // Stored as a string parsed by cfgfile.cpp:
        // - "68030" / "68040" / "68060"
        // - or "68ec030" / "68ec040" / "68ec060"
        return Arrays.asList(
            "(Default)",
            "none",
            "68030",
            "68ec030",
            "68040",
            "68ec040",
            "68060",
            "68ec060"
        );
    }

    private static List<String> ppcImplementationValues() {
        // Mirrors src/cfgfile.cpp ppc_implementations[].
        return Arrays.asList("auto", "dummy", "pearpc", "qemu");
    }

    private static List<String> ppcCpuIdleValues() {
        // Mirrors src/cfgfile.cpp ppc_cpu_idle[].
        List<String> v = new ArrayList<>();
        v.add("disabled");
        for (int i = 1; i <= 9; i++) {
            v.add(String.valueOf(i));
        }
        v.add("max");
        return v;
    }

    private static List<String> jitCacheSizeValues() {
        // cachesize is MB. 0 disables JIT.
        return Arrays.asList("0", "4", "8", "16", "32", "64");
    }

    private static List<String> jitFlushModeValues() {
        return Arrays.asList("soft", "hard");
    }

    private static List<String> jitTrustModeValues() {
        return Arrays.asList("direct", "indirect");
    }

    private static int indexOfIgnoreCase(List<String> values, String needle, int defIdx) {
        if (needle == null) return defIdx;
        for (int i = 0; i < values.size(); i++) {
            if (needle.equalsIgnoreCase(values.get(i))) return i;
        }
        return defIdx;
    }

    private static String radioValueOrNull(RadioGroup group) {
        if (group == null) return null;
        int id = group.getCheckedRadioButtonId();
        if (id == View.NO_ID) return null;
        View v = group.findViewById(id);
        if (!(v instanceof RadioButton)) return null;
        Object tag = v.getTag();
        return tag == null ? null : String.valueOf(tag);
    }

    private static void checkRadioByTag(RadioGroup group, String tag) {
        if (group == null || tag == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton) {
                Object t = child.getTag();
                if (t != null && tag.equalsIgnoreCase(String.valueOf(t))) {
                    ((RadioButton) child).setChecked(true);
                    return;
                }
            }
        }
    }

    private void bindSpinners() {
        mCpuSpeed.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, cpuSpeedValues()));
        ((ArrayAdapter<?>) mCpuSpeed.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mCpuMultiplier.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, cpuMultiplierValues()));
        ((ArrayAdapter<?>) mCpuMultiplier.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mMmuModel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, mmuModelValues()));
        ((ArrayAdapter<?>) mMmuModel.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mPpcImplementation.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ppcImplementationValues()));
        ((ArrayAdapter<?>) mPpcImplementation.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mPpcCpuIdle.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ppcCpuIdleValues()));
        ((ArrayAdapter<?>) mPpcCpuIdle.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mJitCacheSize.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, jitCacheSizeValues()));
        ((ArrayAdapter<?>) mJitCacheSize.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mJitFlushMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, jitFlushModeValues()));
        ((ArrayAdapter<?>) mJitFlushMode.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void updateEnabledStates() {
        boolean enabled = true;

        mCpuModelGroup.setEnabled(enabled);
        for (int i = 0; i < mCpuModelGroup.getChildCount(); i++) {
            View child = mCpuModelGroup.getChildAt(i);
            child.setEnabled(enabled);
        }

        mCpu24Bit.setEnabled(enabled);
        mCpuCompatible.setEnabled(enabled);
        mCpuDataCache.setEnabled(enabled);
        mCpuSpeed.setEnabled(enabled);
        mCpuMultiplier.setEnabled(enabled);
        mMmuModel.setEnabled(enabled);

        mPpcEnable.setEnabled(enabled);

        boolean ppc = mPpcEnable.isChecked();
        mPpcImplementation.setEnabled(enabled && ppc);
        mPpcCpuIdle.setEnabled(enabled && ppc);

        mFpuModelGroup.setEnabled(enabled);
        for (int i = 0; i < mFpuModelGroup.getChildCount(); i++) {
            View child = mFpuModelGroup.getChildAt(i);
            child.setEnabled(enabled);
        }
        mFpuStrict.setEnabled(enabled);

        mJitEnable.setEnabled(enabled);
        boolean jit = mJitEnable.isChecked();
        mJitAdvanced.setEnabled(enabled);
        mJitAdvancedLayout.setEnabled(enabled);
        mJitCacheSize.setEnabled(enabled && jit);
        mJitFpuSupport.setEnabled(enabled && jit);
        mJitConstJump.setEnabled(enabled && jit);
        mJitFlushMode.setEnabled(enabled && jit);
        mJitTrustGroup.setEnabled(enabled && jit);
        for (int i = 0; i < mJitTrustGroup.getChildCount(); i++) {
            View child = mJitTrustGroup.getChildAt(i);
            child.setEnabled(enabled && jit);
        }
        mJitNoFlags.setEnabled(enabled && jit);
        mJitCatchFault.setEnabled(enabled && jit);
    }

    private void load() {
        SharedPreferences p = prefs();

        String cpuModel = p.getString(UaeOptionKeys.UAE_CPU_MODEL, null);
        String fpuModel = p.getString(UaeOptionKeys.UAE_FPU_MODEL, null);

        mCpu24Bit.setChecked(p.getBoolean(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING, false));
        mCpuCompatible.setChecked(p.getBoolean(UaeOptionKeys.UAE_CPU_COMPATIBLE, false));
        mCpuDataCache.setChecked(p.getBoolean(UaeOptionKeys.UAE_CPU_DATA_CACHE, false));

        mCpuSpeed.setSelection(indexOfIgnoreCase(cpuSpeedValues(), p.getString(UaeOptionKeys.UAE_CPU_SPEED, null), 0));
        mCpuMultiplier.setSelection(indexOfIgnoreCase(cpuMultiplierValues(), p.getString(UaeOptionKeys.UAE_CPU_MULTIPLIER, null), 0));

        mMmuModel.setSelection(indexOfIgnoreCase(mmuModelValues(), p.getString(UaeOptionKeys.UAE_MMU_MODEL, null), 0));

        boolean ppcEnabled = p.getBoolean(UaeOptionKeys.UAE_PPC_ENABLED, false);
        mPpcEnable.setChecked(ppcEnabled);
        mPpcImplementation.setSelection(indexOfIgnoreCase(ppcImplementationValues(), p.getString(UaeOptionKeys.UAE_PPC_IMPLEMENTATION, "auto"), 0));
        mPpcCpuIdle.setSelection(indexOfIgnoreCase(ppcCpuIdleValues(), p.getString(UaeOptionKeys.UAE_PPC_CPU_IDLE, "disabled"), 0));

        boolean jitEnabled = p.getBoolean(UaeOptionKeys.UAE_JIT_ENABLED, false);
        mJitEnable.setChecked(jitEnabled);
        mJitCacheSize.setSelection(indexOfIgnoreCase(jitCacheSizeValues(), String.valueOf(p.getInt(UaeOptionKeys.UAE_CACHESIZE, 0)), 0));
        mJitFpuSupport.setChecked(p.getBoolean(UaeOptionKeys.UAE_COMP_FPU, false));
        mJitConstJump.setChecked(p.getBoolean(UaeOptionKeys.UAE_COMP_CONSTJUMP, false));
        mJitFlushMode.setSelection(indexOfIgnoreCase(jitFlushModeValues(), p.getString(UaeOptionKeys.UAE_COMP_FLUSHMODE, "soft"), 0));
        String trust = p.getString(UaeOptionKeys.UAE_COMP_TRUSTMODE, "direct");
        if ("indirect".equalsIgnoreCase(trust)) {
            mJitTrustGroup.check(R.id.radioJitTrustIndirect);
        } else {
            mJitTrustGroup.check(R.id.radioJitTrustDirect);
        }
        mJitNoFlags.setChecked(p.getBoolean(UaeOptionKeys.UAE_COMP_NF, false));
        mJitCatchFault.setChecked(p.getBoolean(UaeOptionKeys.UAE_COMP_CATCHFAULT, false));

        mFpuStrict.setChecked(p.getBoolean(UaeOptionKeys.UAE_FPU_STRICT, false));

        if (cpuModel != null) {
            checkRadioByTag(mCpuModelGroup, cpuModel);
        }
        if (fpuModel != null) {
            checkRadioByTag(mFpuModelGroup, fpuModel);
        }

        updateEnabledStates();
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();

        // CPU model
        String cpuModel = radioValueOrNull(mCpuModelGroup);
        if (cpuModel == null || cpuModel.trim().isEmpty()) {
            e.remove(UaeOptionKeys.UAE_CPU_MODEL);
        } else {
            e.putString(UaeOptionKeys.UAE_CPU_MODEL, cpuModel);
        }

        // FPU model
        String fpuModel = radioValueOrNull(mFpuModelGroup);
        if (fpuModel == null || fpuModel.trim().isEmpty()) {
            e.remove(UaeOptionKeys.UAE_FPU_MODEL);
        } else {
            e.putString(UaeOptionKeys.UAE_FPU_MODEL, fpuModel);
        }

        e.putBoolean(UaeOptionKeys.UAE_FPU_STRICT, mFpuStrict.isChecked());

        e.putBoolean(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING, mCpu24Bit.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_CPU_COMPATIBLE, mCpuCompatible.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_CPU_DATA_CACHE, mCpuDataCache.isChecked());

        String cpuSpeedSel = String.valueOf(mCpuSpeed.getSelectedItem());
        if (cpuSpeedSel.startsWith("(")) e.remove(UaeOptionKeys.UAE_CPU_SPEED);
        else e.putString(UaeOptionKeys.UAE_CPU_SPEED, cpuSpeedSel);

        String multSel = String.valueOf(mCpuMultiplier.getSelectedItem());
        if (multSel.startsWith("(")) e.remove(UaeOptionKeys.UAE_CPU_MULTIPLIER);
        else e.putString(UaeOptionKeys.UAE_CPU_MULTIPLIER, multSel);

        String mmuSel = String.valueOf(mMmuModel.getSelectedItem());
        if (mmuSel.startsWith("(") || "none".equalsIgnoreCase(mmuSel)) e.remove(UaeOptionKeys.UAE_MMU_MODEL);
        else e.putString(UaeOptionKeys.UAE_MMU_MODEL, mmuSel);

        boolean ppcEnabled = mPpcEnable.isChecked();
        e.putBoolean(UaeOptionKeys.UAE_PPC_ENABLED, ppcEnabled);
        e.putString(UaeOptionKeys.UAE_PPC_IMPLEMENTATION, String.valueOf(mPpcImplementation.getSelectedItem()));
        e.putString(UaeOptionKeys.UAE_PPC_CPU_IDLE, String.valueOf(mPpcCpuIdle.getSelectedItem()));

        boolean jitEnabled = mJitEnable.isChecked();
        e.putBoolean(UaeOptionKeys.UAE_JIT_ENABLED, jitEnabled);
        int cacheSize = 0;
        try {
            cacheSize = Integer.parseInt(String.valueOf(mJitCacheSize.getSelectedItem()));
        } catch (Throwable ignored) {
        }
        e.putInt(UaeOptionKeys.UAE_CACHESIZE, cacheSize);
        e.putBoolean(UaeOptionKeys.UAE_COMP_FPU, mJitFpuSupport.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_COMP_CONSTJUMP, mJitConstJump.isChecked());
        e.putString(UaeOptionKeys.UAE_COMP_FLUSHMODE, String.valueOf(mJitFlushMode.getSelectedItem()));

        int trustId = mJitTrustGroup.getCheckedRadioButtonId();
        String trustMode = (trustId == R.id.radioJitTrustIndirect) ? "indirect" : "direct";
        e.putString(UaeOptionKeys.UAE_COMP_TRUSTMODE, trustMode);
        e.putBoolean(UaeOptionKeys.UAE_COMP_NF, mJitNoFlags.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_COMP_CATCHFAULT, mJitCatchFault.isChecked());

        e.apply();
    }

    private void reset() {
        prefs().edit()
            .remove(UaeOptionKeys.UAE_CPU_MODEL)
            .remove(UaeOptionKeys.UAE_FPU_MODEL)
            .remove(UaeOptionKeys.UAE_CPU_SPEED)
            .remove(UaeOptionKeys.UAE_MMU_MODEL)
            .remove(UaeOptionKeys.UAE_PPC_IMPLEMENTATION)
            .remove(UaeOptionKeys.UAE_PPC_CPU_IDLE)
            .remove(UaeOptionKeys.UAE_COMP_FLUSHMODE)
            .remove(UaeOptionKeys.UAE_COMP_TRUSTMODE)
            .remove(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING)
            .remove(UaeOptionKeys.UAE_CPU_COMPATIBLE)
            .remove(UaeOptionKeys.UAE_CPU_DATA_CACHE)
            .remove(UaeOptionKeys.UAE_PPC_ENABLED)
            .remove(UaeOptionKeys.UAE_JIT_ENABLED)
            .remove(UaeOptionKeys.UAE_CACHESIZE)
            .remove(UaeOptionKeys.UAE_COMP_FPU)
            .remove(UaeOptionKeys.UAE_COMP_CONSTJUMP)
            .remove(UaeOptionKeys.UAE_COMP_NF)
            .remove(UaeOptionKeys.UAE_COMP_CATCHFAULT)
            .remove(UaeOptionKeys.UAE_FPU_STRICT)
            .apply();

        // Default selection in radio groups: 68000 / FPU none.
        checkRadioByTag(mCpuModelGroup, "68000");
        checkRadioByTag(mFpuModelGroup, "0");

        load();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cpu_fpu_options);

        mCpuModelGroup = findViewById(R.id.groupCpuModel);
        mCpu24Bit = findViewById(R.id.chkCpu24);
        mCpuCompatible = findViewById(R.id.chkCpuCompatible);
        mCpuDataCache = findViewById(R.id.chkCpuDataCache);
        mCpuSpeed = findViewById(R.id.spinnerCpuSpeed);
        mCpuMultiplier = findViewById(R.id.spinnerCpuMultiplier);
        mMmuModel = findViewById(R.id.spinnerMmuModel);

        mPpcEnable = findViewById(R.id.chkPpcEnable);
        mPpcImplementation = findViewById(R.id.spinnerPpcImplementation);
        mPpcCpuIdle = findViewById(R.id.spinnerPpcCpuIdle);

        mFpuModelGroup = findViewById(R.id.groupFpuModel);
        mFpuStrict = findViewById(R.id.chkFpuStrict);

        mJitEnable = findViewById(R.id.chkJitEnable);
        mJitAdvanced = findViewById(R.id.btnJitAdvanced);
        mJitAdvancedLayout = findViewById(R.id.layoutJitAdvanced);
        mJitCacheSize = findViewById(R.id.spinnerJitCacheSize);
        mJitFpuSupport = findViewById(R.id.chkJitFpu);
        mJitConstJump = findViewById(R.id.chkJitConstJump);
        mJitFlushMode = findViewById(R.id.spinnerJitFlush);
        mJitTrustGroup = findViewById(R.id.groupJitTrust);
        mJitNoFlags = findViewById(R.id.chkJitNoFlags);
        mJitCatchFault = findViewById(R.id.chkJitCatchFault);

        bindSpinners();
        mPpcEnable.setOnCheckedChangeListener((b, c) -> updateEnabledStates());
        mJitEnable.setOnCheckedChangeListener((b, c) -> updateEnabledStates());

        mJitAdvanced.setOnClickListener(v -> {
            if (mJitAdvancedLayout.getVisibility() == View.VISIBLE) {
                mJitAdvancedLayout.setVisibility(View.GONE);
            } else {
                mJitAdvancedLayout.setVisibility(View.VISIBLE);
            }
        });

        load();

        Button btnSave = findViewById(R.id.btnCpuFpuSave);
        Button btnReset = findViewById(R.id.btnCpuFpuReset);
        Button btnBack = findViewById(R.id.btnCpuFpuBack);

        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnBack.setOnClickListener(v -> finish());
        btnReset.setOnClickListener(v -> reset());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
