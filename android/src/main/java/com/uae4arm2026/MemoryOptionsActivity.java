package com.uae4arm2026;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.List;

public class MemoryOptionsActivity extends Activity {

    private Spinner mChipMem;
    private Spinner mSlowMem;
    private Spinner mFastMem;
    private Spinner mZ3Fast;
    private Spinner mZ3Chip;
    private Spinner mA3000Mem;
    private Spinner mMbResMem;
    private Spinner mZ3Mapping;

    private CheckBox mRtgEnable;
    private Spinner mRtgVram;

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private static int indexOfInt(int[] values, int needle, int defIdx) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == needle) return i;
        }
        return defIdx;
    }

    private static int indexOfString(List<String> values, String needle, int defIdx) {
        if (needle == null) return defIdx;
        for (int i = 0; i < values.size(); i++) {
            if (needle.equalsIgnoreCase(values.get(i))) return i;
        }
        return defIdx;
    }

    // chipmem_size values (special units). See src/cfgfile.cpp handling for chipmem_size.
    private static final int[] CHIP_VALUES = {0, 1, 2, 3, 4, 8, 16};
    private static final String[] CHIP_LABELS = {"256 KB", "512 KB", "1 MB", "1.5 MB", "2 MB", "4 MB", "8 MB"};

    // bogomem_size is in 256K units.
    private static final int[] BOGO_VALUES = {0, 2, 4, 6, 7};
    private static final String[] BOGO_LABELS = {"None", "512 KB", "1 MB", "1.5 MB", "1.8 MB"};

    // fastmem stored as bytes. We apply it as fastmem_size_k (<1MB) or fastmem_size (>=1MB).
    private static final int[] FAST_BYTES = {0, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1 * 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024};
    private static final String[] FAST_LABELS = {"None", "64 KB", "128 KB", "256 KB", "512 KB", "1 MB", "2 MB", "4 MB", "8 MB"};

    private static final int[] Z3FAST_MB = {0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
    private static final String[] Z3FAST_LABELS = {"None", "1 MB", "2 MB", "4 MB", "8 MB", "16 MB", "32 MB", "64 MB", "128 MB", "256 MB", "512 MB", "1 GB"};

    private static final int[] Z3CHIP_MB = {0, 16, 32, 64, 128, 256, 384, 512, 768, 1024};
    private static final String[] Z3CHIP_LABELS = {"None", "16 MB", "32 MB", "64 MB", "128 MB", "256 MB", "384 MB", "512 MB", "768 MB", "1 GB"};

    private static final int[] MB_MB = {0, 1, 2, 4, 8, 16, 32, 64, 128};
    private static final String[] MB_LABELS = {"None", "1 MB", "2 MB", "4 MB", "8 MB", "16 MB", "32 MB", "64 MB", "128 MB"};

    private static final int[] RTG_VRAM_MB = {4, 8, 16, 32, 64, 128, 256};
    private static final String[] RTG_VRAM_LABELS = {"4 MB", "8 MB", "16 MB", "32 MB", "64 MB", "128 MB", "256 MB"};

    private static int indexOfIntOr(int[] values, int needle, int defIdx) {
        int idx = indexOfInt(values, needle, -1);
        return idx >= 0 ? idx : defIdx;
    }

    private static int ensureMinZ3FastMbForRtg(int z3FastMb) {
        int mb = z3FastMb;
        if (mb < 32) mb = 32;

        // Round up to a supported Z3 fastmem size.
        for (int v : Z3FAST_MB) {
            if (v > 0 && mb <= v) return v;
        }
        return Z3FAST_MB[Z3FAST_MB.length - 1];
    }

    private static List<String> z3MappingLabels() {
        List<String> v = new ArrayList<>();
        v.add("Automatic (*)");
        v.add("UAE (0x10000000)");
        v.add("Real (0x40000000)");
        return v;
    }

    private static List<String> z3MappingValues() {
        List<String> v = new ArrayList<>();
        v.add("auto");
        v.add("uae");
        v.add("real");
        return v;
    }

    private void bindSpinner(Spinner spinner, String[] labels) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
        ((ArrayAdapter<?>) spinner.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void bindSpinners() {
        bindSpinner(mChipMem, CHIP_LABELS);
        bindSpinner(mSlowMem, BOGO_LABELS);
        bindSpinner(mFastMem, FAST_LABELS);
        bindSpinner(mZ3Fast, Z3FAST_LABELS);
        bindSpinner(mZ3Chip, Z3CHIP_LABELS);
        bindSpinner(mA3000Mem, MB_LABELS);
        bindSpinner(mMbResMem, MB_LABELS);

        mZ3Mapping.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, z3MappingLabels()));
        ((ArrayAdapter<?>) mZ3Mapping.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        if (mRtgVram != null) {
            mRtgVram.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, RTG_VRAM_LABELS));
            ((ArrayAdapter<?>) mRtgVram.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }
    }

    private void load() {
        SharedPreferences p = prefs();

        int chip = p.getInt(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE, CHIP_VALUES[2]);
        int bogo = p.getInt(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE, 0);
        int fastBytes = p.getInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, 0);
        int z3 = p.getInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, 0);
        int z3chip = p.getInt(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB, 0);
        int a3000 = p.getInt(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB, 0);
        int mbres = p.getInt(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB, 0);
        String z3map = p.getString(UaeOptionKeys.UAE_MEM_Z3MAPPING, "auto");

        int rtgSize = p.getInt(UaeOptionKeys.UAE_GFXCARD_SIZE_MB, 0);
        String rtgType = p.getString(UaeOptionKeys.UAE_GFXCARD_TYPE, null);

        mChipMem.setSelection(indexOfInt(CHIP_VALUES, chip, 2));
        mSlowMem.setSelection(indexOfInt(BOGO_VALUES, bogo, 0));
        mFastMem.setSelection(indexOfInt(FAST_BYTES, fastBytes, 0));
        mZ3Fast.setSelection(indexOfInt(Z3FAST_MB, z3, 0));
        mZ3Chip.setSelection(indexOfInt(Z3CHIP_MB, z3chip, 0));
        mA3000Mem.setSelection(indexOfInt(MB_MB, a3000, 0));
        mMbResMem.setSelection(indexOfInt(MB_MB, mbres, 0));
        mZ3Mapping.setSelection(indexOfString(z3MappingValues(), z3map, 0));

        if (mRtgEnable != null) {
            mRtgEnable.setChecked(rtgSize > 0 && rtgType != null && !rtgType.trim().isEmpty());
        }
        if (mRtgVram != null) {
            int def = indexOfIntOr(RTG_VRAM_MB, 16, 2);
            int idx = indexOfIntOr(RTG_VRAM_MB, rtgSize > 0 ? rtgSize : 16, def);
            mRtgVram.setSelection(idx);
            mRtgVram.setEnabled(mRtgEnable == null || mRtgEnable.isChecked());
        }
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();

        e.putInt(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE, CHIP_VALUES[mChipMem.getSelectedItemPosition()]);
        e.putInt(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE, BOGO_VALUES[mSlowMem.getSelectedItemPosition()]);
        int fastBytes = FAST_BYTES[mFastMem.getSelectedItemPosition()];
        int z3FastMb = Z3FAST_MB[mZ3Fast.getSelectedItemPosition()];
        e.putInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, fastBytes);
        e.putInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, z3FastMb);
        e.putInt(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB, Z3CHIP_MB[mZ3Chip.getSelectedItemPosition()]);
        e.putInt(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB, MB_MB[mA3000Mem.getSelectedItemPosition()]);
        e.putInt(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB, MB_MB[mMbResMem.getSelectedItemPosition()]);
        e.putString(UaeOptionKeys.UAE_MEM_Z3MAPPING, z3MappingValues().get(mZ3Mapping.getSelectedItemPosition()));

        // RTG (Picasso96 / UAEGFX)
        boolean rtgEnabled = mRtgEnable != null && mRtgEnable.isChecked();
        if (rtgEnabled) {
            int vramMb = 16;
            if (mRtgVram != null) {
                int pos = mRtgVram.getSelectedItemPosition();
                if (pos >= 0 && pos < RTG_VRAM_MB.length) vramMb = RTG_VRAM_MB[pos];
            }
            e.putInt(UaeOptionKeys.UAE_GFXCARD_SIZE_MB, vramMb);
            // UAEGFX default config type for the internal Z3 board.
            e.putString(UaeOptionKeys.UAE_GFXCARD_TYPE, "ZorroIII");
            // RTG requires 32-bit address space.
            e.putBoolean(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING, false);

            // RTG needs 32-bit fast RAM. Move Z2 fastmem -> Z3 fastmem and clear Z2.
            int coercedZ3 = ensureMinZ3FastMbForRtg(z3FastMb);
            e.putInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, coercedZ3);
            e.putInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, 0);
        } else {
            e.remove(UaeOptionKeys.UAE_GFXCARD_SIZE_MB);
            e.remove(UaeOptionKeys.UAE_GFXCARD_TYPE);
        }

        e.apply();
    }

    private void resetAll() {
        prefs().edit()
            .remove(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE)
            .remove(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE)
            .remove(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES)
            .remove(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB)
            .remove(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB)
            .remove(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB)
            .remove(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB)
            .remove(UaeOptionKeys.UAE_MEM_Z3MAPPING)
            .remove(UaeOptionKeys.UAE_GFXCARD_SIZE_MB)
            .remove(UaeOptionKeys.UAE_GFXCARD_TYPE)
            .apply();
        load();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory_options);

        mChipMem = findViewById(R.id.spinnerChipMem);
        mSlowMem = findViewById(R.id.spinnerSlowMem);
        mFastMem = findViewById(R.id.spinnerFastMem);
        mZ3Fast = findViewById(R.id.spinnerZ3Fast);
        mZ3Chip = findViewById(R.id.spinnerZ3Chip);
        mA3000Mem = findViewById(R.id.spinnerA3000Mem);
        mMbResMem = findViewById(R.id.spinnerMbResMem);
        mZ3Mapping = findViewById(R.id.spinnerZ3Mapping);

        mRtgEnable = findViewById(R.id.chkRtgEnable);
        mRtgVram = findViewById(R.id.spinnerRtgVram);

        bindSpinners();
        load();

        if (mRtgEnable != null) {
            mRtgEnable.setOnCheckedChangeListener((b, checked) -> {
                if (mRtgVram != null) mRtgVram.setEnabled(checked);
                if (checked) {
                    int z3FastMb = Z3FAST_MB[mZ3Fast.getSelectedItemPosition()];
                    int mb = ensureMinZ3FastMbForRtg(z3FastMb);
                    mZ3Fast.setSelection(indexOfInt(Z3FAST_MB, mb, indexOfInt(Z3FAST_MB, 32, 6)));
                    mFastMem.setSelection(0);
                }
                save();
            });
        }

        Button btnSave = findViewById(R.id.btnMemorySave);
        Button btnReset = findViewById(R.id.btnMemoryReset);
        Button btnBack = findViewById(R.id.btnMemoryBack);

        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnReset.setOnClickListener(v -> resetAll());
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
