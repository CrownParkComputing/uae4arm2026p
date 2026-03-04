package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class DrivesOptionsActivity extends Activity {

    private static final String TAG = "DrivesOptionsActivity";

    private static final int REQ_PICK_CD_IMAGE0 = 2001;
    private static final int REQ_PICK_HDF0 = 2002;
    private static final int REQ_PICK_HDF1 = 2005;
    private static final int REQ_PICK_DIR0 = 2003;
    private static final int REQ_PICK_DIR1 = 2004;
    private static final int REQ_PICK_DOS_FS = 2006;
    private static final int REQ_PICK_CD_BIN_FOLDER = 2007;
    private static final int REQ_PICK_AGS_ROOT = 2008;

    // Pending CUE state when BIN tracks need to be fetched from a folder.
    private File mPendingCueFile;
    private List<String> mPendingCueTracks;

    private SharedPreferences prefs;

    private Switch swDir0Enabled;
    private TextView tvDir0Path;
    private EditText etDir0Dev;
    private EditText etDir0Vol;
    private CheckBox cbDir0ReadOnly;
    private EditText etDir0BootPri;

    private Switch swDir1Enabled;
    private TextView tvDir1Path;
    private EditText etDir1Dev;
    private EditText etDir1Vol;
    private CheckBox cbDir1ReadOnly;
    private EditText etDir1BootPri;

    private Switch swHdf0Enabled;
    private TextView tvHdf0Path;
    private EditText etHdf0Dev;
    private CheckBox cbHdf0ReadOnly;

    private Switch swHdf1Enabled;
    private TextView tvHdf1Path;
    private EditText etHdf1Dev;
    private CheckBox cbHdf1ReadOnly;

    private TextView tvCd0Path;
    private TextView tvCdHeader;
    private Button btnCd0Pick;
    private Button btnCd0Clear;
    private Switch swCd32CdEnabled;
    private CheckBox cbMapCdDrives;
    private CheckBox cbCdTurbo;

    private TextView tvDosFsPath;
    private Switch swAgsAutoMountEnabled;
    private TextView tvAgsBasePath;
    private TextView tvAgsMountPreview;
    private android.view.View agsLegacySection;
    private boolean mAgsOrderedProfileReady;

    private static final class AgsOrderedMount {
        final String sourceName;
        final String dev;
        final int unit;

        AgsOrderedMount(String sourceName, String dev, int unit) {
            this.sourceName = sourceName;
            this.dev = dev;
            this.unit = unit;
        }
    }

    private static final AgsOrderedMount[] AGS_ORDERED_MOUNTS = new AgsOrderedMount[] {
        new AgsOrderedMount("Workbench.hdf", "DH0", 0),
        new AgsOrderedMount("Work.hdf", "DH1", 8),
        new AgsOrderedMount("Music.hdf", "DH2", 11),
        new AgsOrderedMount("Media.hdf", "DH3", 9),
        new AgsOrderedMount("AGS_Drive.hdf", "DH4", 1),
        new AgsOrderedMount("Games.hdf", "DH5", 2),
        new AgsOrderedMount("Premium.hdf", "DH6", 12),
        new AgsOrderedMount("Emulators.hdf", "DH7", 10),
        new AgsOrderedMount("Emulators2.hdf", "DH8", 15),
        new AgsOrderedMount("WHD_Demos.hdf", "DH9", 6),
        new AgsOrderedMount("WHD_Games.hdf", "DH10", 14),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drives_options);

        // Keep Save/Back buttons visible above system navigation/gesture UI.
        UiInsets.applySystemBarsPaddingBottom(findViewById(android.R.id.content));

        prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        swDir0Enabled = findViewById(R.id.swDir0Enabled);
        tvDir0Path = findViewById(R.id.tvDir0Path);
        etDir0Dev = findViewById(R.id.etDir0Dev);
        etDir0Vol = findViewById(R.id.etDir0Vol);
        cbDir0ReadOnly = findViewById(R.id.cbDir0ReadOnly);
        etDir0BootPri = findViewById(R.id.etDir0BootPri);

        swDir1Enabled = findViewById(R.id.swDir1Enabled);
        tvDir1Path = findViewById(R.id.tvDir1Path);
        etDir1Dev = findViewById(R.id.etDir1Dev);
        etDir1Vol = findViewById(R.id.etDir1Vol);
        cbDir1ReadOnly = findViewById(R.id.cbDir1ReadOnly);
        etDir1BootPri = findViewById(R.id.etDir1BootPri);

        swHdf0Enabled = findViewById(R.id.swHdf0Enabled);
        tvHdf0Path = findViewById(R.id.tvHdf0Path);
        etHdf0Dev = findViewById(R.id.etHdf0Dev);
        cbHdf0ReadOnly = findViewById(R.id.cbHdf0ReadOnly);

        swHdf1Enabled = findViewById(R.id.swHdf1Enabled);
        tvHdf1Path = findViewById(R.id.tvHdf1Path);
        etHdf1Dev = findViewById(R.id.etHdf1Dev);
        cbHdf1ReadOnly = findViewById(R.id.cbHdf1ReadOnly);

        tvCd0Path = findViewById(R.id.tvCd0Path);
        tvCdHeader = findViewById(R.id.tvCdHeader);
        swCd32CdEnabled = findViewById(R.id.swCd32CdEnabled);
        cbMapCdDrives = findViewById(R.id.cbMapCdDrives);
        cbCdTurbo = findViewById(R.id.cbCdTurbo);

        tvDosFsPath = findViewById(R.id.tvDosFsPath);
        swAgsAutoMountEnabled = findViewById(R.id.swAgsAutoMountEnabled);
        tvAgsBasePath = findViewById(R.id.tvAgsBasePath);
        tvAgsMountPreview = findViewById(R.id.tvAgsMountPreview);
        agsLegacySection = findViewById(R.id.agsLegacySection);

        Button btnDir0Pick = findViewById(R.id.btnDir0Pick);
        Button btnDir0Clear = findViewById(R.id.btnDir0Clear);
        Button btnDir1Pick = findViewById(R.id.btnDir1Pick);
        Button btnDir1Clear = findViewById(R.id.btnDir1Clear);

        Button btnHdf0Pick = findViewById(R.id.btnHdf0Pick);
        Button btnHdf0Clear = findViewById(R.id.btnHdf0Clear);

        Button btnHdf1Pick = findViewById(R.id.btnHdf1Pick);
        Button btnHdf1Clear = findViewById(R.id.btnHdf1Clear);

        btnCd0Pick = findViewById(R.id.btnCd0Pick);
        btnCd0Clear = findViewById(R.id.btnCd0Clear);

        Button btnDosFsPick = findViewById(R.id.btnDosFsPick);
        Button btnDosFsClear = findViewById(R.id.btnDosFsClear);
        Button btnAgsPathPick = findViewById(R.id.btnAgsPathPick);
        Button btnAgsPathClear = findViewById(R.id.btnAgsPathClear);

        Button btnSave = findViewById(R.id.btnDrivesSave);
        Button btnBack = findViewById(R.id.btnDrivesBack);
        Button btnLaunchAgs = findViewById(R.id.btnDrivesLaunchAgs);

        btnDir0Pick.setOnClickListener(v -> pickDirectory(REQ_PICK_DIR0));
        btnDir1Pick.setOnClickListener(v -> pickDirectory(REQ_PICK_DIR1));

        btnDir0Clear.setOnClickListener(v -> {
            clearDirMount(0);
            refreshUiFromPrefs();
        });
        btnDir1Clear.setOnClickListener(v -> {
            clearDirMount(1);
            refreshUiFromPrefs();
        });

        btnHdf0Pick.setOnClickListener(v -> pickFile(REQ_PICK_HDF0, "*/*"));
        btnHdf0Clear.setOnClickListener(v -> {
            clearHdf0();
            refreshUiFromPrefs();
        });

        btnHdf1Pick.setOnClickListener(v -> pickFile(REQ_PICK_HDF1, "*/*"));
        btnHdf1Clear.setOnClickListener(v -> {
            clearHdf1();
            refreshUiFromPrefs();
        });

        btnCd0Pick.setOnClickListener(v -> pickCdImage0());
        btnCd0Clear.setOnClickListener(v -> {
            clearCd0();
            refreshUiFromPrefs();
        });

        btnDosFsPick.setOnClickListener(v -> pickFile(REQ_PICK_DOS_FS, "*/*"));
        btnDosFsClear.setOnClickListener(v -> {
            clearDosFsModule();
            refreshUiFromPrefs();
        });

        btnAgsPathPick.setOnClickListener(v -> pickDirectory(REQ_PICK_AGS_ROOT));
        btnAgsPathClear.setOnClickListener(v -> {
            clearAgsAutoMount();
            refreshUiFromPrefs();
        });

        swAgsAutoMountEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> applyAgsModeUi(isChecked));

        btnSave.setOnClickListener(v -> {
            saveToPrefs();
            finish();
        });

        if (btnLaunchAgs != null) {
            btnLaunchAgs.setOnClickListener(v -> {
                saveToPrefs();

                String agsBase = prefs.getString(UaeOptionKeys.UAE_DRIVE_AGS_BASE_PATH, "");
                if (agsBase == null || agsBase.trim().isEmpty()) {
                    Toast.makeText(this, "Select AGS parent folder first", Toast.LENGTH_SHORT).show();
                    return;
                }

                prefs.edit()
                    .putBoolean(UaeOptionKeys.UAE_DRIVE_AGS_AUTOMOUNT_ENABLED, true)
                    .putBoolean(UaeOptionKeys.UAE_DRIVE_AGS_LAUNCH_ONCE, true)
                    .apply();

                Intent i = new Intent(this, BootstrapActivity.class);
                i.putExtra(BootstrapActivity.EXTRA_LAUNCH_AGS_FROM_SETUP, true);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            });
        }

        btnBack.setOnClickListener(v -> finish());

        refreshUiFromPrefs();
    }

    private void refreshUiFromPrefs() {
        boolean dir0Enabled = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
        String dir0Path = prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, "");
        String dir0Dev = prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR0_DEVNAME, "DH0");
        String dir0Vol = prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR0_VOLNAME, "Work");
        boolean dir0Ro = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_READONLY, false);
        int dir0Boot = prefs.getInt(UaeOptionKeys.UAE_DRIVE_DIR0_BOOTPRI, 0);

        swDir0Enabled.setChecked(dir0Enabled);
        tvDir0Path.setText(dir0Path == null || dir0Path.isEmpty() ? "(not set)" : dir0Path);
        etDir0Dev.setText(dir0Dev);
        etDir0Vol.setText(dir0Vol);
        cbDir0ReadOnly.setChecked(dir0Ro);
        etDir0BootPri.setText(String.valueOf(dir0Boot));

        boolean dir1Enabled = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false);
        String dir1Path = prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, "");
        String dir1Dev = prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR1_DEVNAME, "DH1");
        String dir1Vol = prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR1_VOLNAME, "Work2");
        boolean dir1Ro = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_READONLY, false);
        int dir1Boot = prefs.getInt(UaeOptionKeys.UAE_DRIVE_DIR1_BOOTPRI, -128);

        swDir1Enabled.setChecked(dir1Enabled);
        tvDir1Path.setText(dir1Path == null || dir1Path.isEmpty() ? "(not set)" : dir1Path);
        etDir1Dev.setText(dir1Dev);
        etDir1Vol.setText(dir1Vol);
        cbDir1ReadOnly.setChecked(dir1Ro);
        etDir1BootPri.setText(String.valueOf(dir1Boot));

        boolean hdf0Enabled = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
        String hdf0Path = prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, "");
        String hdf0Dev = prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF0_DEVNAME, "DH0");
        boolean hdf0Ro = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_READONLY, false);

        swHdf0Enabled.setChecked(hdf0Enabled);
        tvHdf0Path.setText(hdf0Path == null || hdf0Path.isEmpty() ? "(not set)" : hdf0Path);
        etHdf0Dev.setText(hdf0Dev);
        cbHdf0ReadOnly.setChecked(hdf0Ro);

        boolean hdf1Enabled = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
        String hdf1Path = prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF1_PATH, "");
        String hdf1Dev = prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF1_DEVNAME, "DH1");
        boolean hdf1Ro = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_READONLY, false);

        swHdf1Enabled.setChecked(hdf1Enabled);
        tvHdf1Path.setText(hdf1Path == null || hdf1Path.isEmpty() ? "(not set)" : hdf1Path);
        etHdf1Dev.setText(hdf1Dev);
        cbHdf1ReadOnly.setChecked(hdf1Ro);

        String cd0 = prefs.getString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, "");
        tvCd0Path.setText(cd0 == null || cd0.isEmpty() ? "(not set)" : cd0);

        boolean cd32cd = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, false);
        boolean mapCd = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_MAP_CD_DRIVES, false);
        boolean turbo = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_CD_TURBO, false);

        swCd32CdEnabled.setChecked(cd32cd);
        cbMapCdDrives.setChecked(mapCd);
        cbCdTurbo.setChecked(turbo);

        String dosFs = prefs.getString(UaeOptionKeys.UAE_DRIVE_DOS_FS_MODULE_PATH, "");
        tvDosFsPath.setText(dosFs == null || dosFs.isEmpty() ? "(not set)" : dosFs);

        boolean agsEnabled = prefs.getBoolean(UaeOptionKeys.UAE_DRIVE_AGS_AUTOMOUNT_ENABLED, false);
        String agsBase = prefs.getString(UaeOptionKeys.UAE_DRIVE_AGS_BASE_PATH, "");
        swAgsAutoMountEnabled.setChecked(agsEnabled);
        tvAgsBasePath.setText(agsBase == null || agsBase.isEmpty() ? "(not set)" : agsBase);
        updateAgsMountPreview(agsBase);

        applyAgsModeUi(agsEnabled);
    }

    private void updateAgsMountPreview(String agsBase) {
        if (tvAgsMountPreview == null) return;
        if (agsBase == null || agsBase.trim().isEmpty()) {
            tvAgsMountPreview.setText("Mount order preview: (select parent folder)");
            return;
        }

        String base = agsBase.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("Mount order preview\n");

        int found = 0;
        for (AgsOrderedMount item : AGS_ORDERED_MOUNTS) {
            String hit = findAgsNamedChild(base, item.sourceName, false, 4);
            if (hit != null && !hit.trim().isEmpty()) {
                found++;
                sb.append("✓ ")
                    .append(item.dev)
                    .append(" <= ")
                    .append(item.sourceName)
                    .append(" (uae")
                    .append(item.unit)
                    .append(")\n");
            } else {
                sb.append("• ")
                    .append(item.dev)
                    .append(" <= ")
                    .append(item.sourceName)
                    .append(" (missing)\n");
            }
        }

        String shared = findAgsNamedChild(base, "SHARED", true, 4);
        if (shared == null || shared.trim().isEmpty()) {
            shared = findAgsNamedChild(base, "SHARD", true, 4);
        }
        boolean hasShared = shared != null && !shared.trim().isEmpty();
        if (shared != null && !shared.trim().isEmpty()) {
            sb.append("✓ Shared <= SHARED");
        } else {
            sb.append("• Shared <= SHARED (missing)");
        }

        mAgsOrderedProfileReady = (found >= AGS_ORDERED_MOUNTS.length && hasShared);
        if (mAgsOrderedProfileReady) {
            sb.append("\nAGS profile ready: A1200 + JIT + RTG + 2MB chip");
        }

        sb.append("\nFound ").append(found).append("/").append(AGS_ORDERED_MOUNTS.length).append(" HDF files");
        tvAgsMountPreview.setText(sb.toString());
    }

    private String findAgsNamedChild(String basePathOrTreeUri, String childName, boolean directory, int maxDepth) {
        if (basePathOrTreeUri == null || basePathOrTreeUri.trim().isEmpty()) return null;
        if (childName == null || childName.trim().isEmpty()) return null;
        if (maxDepth < 0) return null;

        String base = basePathOrTreeUri.trim();
        String target = childName.trim();

        if (base.startsWith("content://")) {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(base));
                return findSafNamedRecursive(root, target, directory, 0, maxDepth);
            } catch (Throwable ignored) {
                return null;
            }
        }

        return findFileNamedRecursive(new File(base), target, directory, 0, maxDepth);
    }

    private String findFileNamedRecursive(File dir, String targetName, boolean directory, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        if (depth > maxDepth) return null;

        File[] kids = dir.listFiles();
        if (kids == null) return null;

        for (File kid : kids) {
            if (kid == null) continue;
            String name = kid.getName();
            if (name == null) continue;
            if (name.equalsIgnoreCase(targetName)) {
                if (directory && kid.isDirectory()) return kid.getAbsolutePath();
                if (!directory && kid.isFile()) return kid.getAbsolutePath();
            }
        }

        for (File kid : kids) {
            if (kid == null || !kid.isDirectory()) continue;
            String hit = findFileNamedRecursive(kid, targetName, directory, depth + 1, maxDepth);
            if (hit != null && !hit.trim().isEmpty()) return hit;
        }

        return null;
    }

    private String findSafNamedRecursive(DocumentFile dir, String targetName, boolean directory, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory()) return null;
        if (depth > maxDepth) return null;

        DocumentFile[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable ignored) {
            return null;
        }
        if (kids == null) return null;

        for (DocumentFile kid : kids) {
            if (kid == null) continue;
            String name = kid.getName();
            if (name == null) continue;
            if (name.equalsIgnoreCase(targetName)) {
                if (directory && kid.isDirectory()) return kid.getUri().toString();
                if (!directory && kid.isFile()) return kid.getUri().toString();
            }
        }

        for (DocumentFile kid : kids) {
            if (kid == null || !kid.isDirectory()) continue;
            String hit = findSafNamedRecursive(kid, targetName, directory, depth + 1, maxDepth);
            if (hit != null && !hit.trim().isEmpty()) return hit;
        }

        return null;
    }

    private void applyAgsModeUi(boolean agsEnabled) {
        if (agsEnabled) {
            tvCd0Path.setText("(disabled by AGS auto-mount)");
        }

        if (agsLegacySection != null) {
            agsLegacySection.setVisibility(agsEnabled ? android.view.View.GONE : android.view.View.VISIBLE);
        }

        int visibility = android.view.View.GONE;
        if (tvCdHeader != null) tvCdHeader.setVisibility(visibility);
        if (tvCd0Path != null) tvCd0Path.setVisibility(visibility);
        if (btnCd0Pick != null) {
            btnCd0Pick.setEnabled(!agsEnabled);
            btnCd0Pick.setVisibility(visibility);
        }
        if (btnCd0Clear != null) {
            btnCd0Clear.setEnabled(!agsEnabled);
            btnCd0Clear.setVisibility(visibility);
        }
        if (swCd32CdEnabled != null) swCd32CdEnabled.setVisibility(visibility);
        if (cbMapCdDrives != null) cbMapCdDrives.setVisibility(visibility);
        if (cbCdTurbo != null) cbCdTurbo.setVisibility(visibility);
    }

    private void saveToPrefs() {
        SharedPreferences.Editor e = prefs.edit();

        e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, swDir0Enabled.isChecked());
        e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_DEVNAME, safeText(etDir0Dev, "DH0"));
        e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_VOLNAME, safeText(etDir0Vol, "Work"));
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_READONLY, cbDir0ReadOnly.isChecked());
        e.putInt(UaeOptionKeys.UAE_DRIVE_DIR0_BOOTPRI, safeInt(etDir0BootPri, 0));

        e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, swDir1Enabled.isChecked());
        e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_DEVNAME, safeText(etDir1Dev, "DH1"));
        e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_VOLNAME, safeText(etDir1Vol, "Work2"));
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_READONLY, cbDir1ReadOnly.isChecked());
        e.putInt(UaeOptionKeys.UAE_DRIVE_DIR1_BOOTPRI, safeInt(etDir1BootPri, -128));

        e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, swHdf0Enabled.isChecked());
        e.putString(UaeOptionKeys.UAE_DRIVE_HDF0_DEVNAME, safeText(etHdf0Dev, "DH0"));
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_READONLY, cbHdf0ReadOnly.isChecked());

        e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, swHdf1Enabled.isChecked());
        e.putString(UaeOptionKeys.UAE_DRIVE_HDF1_DEVNAME, safeText(etHdf1Dev, "DH1"));
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_READONLY, cbHdf1ReadOnly.isChecked());

        boolean agsEnabled = swAgsAutoMountEnabled.isChecked();
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, !agsEnabled && swCd32CdEnabled.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_MAP_CD_DRIVES, !agsEnabled && cbMapCdDrives.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_CD_TURBO, !agsEnabled && cbCdTurbo.isChecked());

        e.putBoolean(UaeOptionKeys.UAE_DRIVE_AGS_AUTOMOUNT_ENABLED, agsEnabled);
        if (agsEnabled) {
            e.remove(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);
            if (mAgsOrderedProfileReady) {
                applyAgsBaselineProfile(e);
            }
        }

        e.apply();
    }

    private void applyAgsBaselineProfile(SharedPreferences.Editor e) {
        if (e == null) return;

        e.putString("qs_model", "A1200");
        e.putInt("qs_config", 0);

        e.putString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, "A1200");
        e.putString(UaeOptionKeys.UAE_CHIPSET, "aga");

        e.putString(UaeOptionKeys.UAE_CPU_MODEL, "68020");
        e.putBoolean(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING, false);
        e.putBoolean(UaeOptionKeys.UAE_CPU_COMPATIBLE, false);
        e.putString(UaeOptionKeys.UAE_CPU_SPEED, "max");
        e.putString(UaeOptionKeys.UAE_CYCLE_EXACT, "false");

        e.putBoolean(UaeOptionKeys.UAE_JIT_ENABLED, true);
        e.putInt(UaeOptionKeys.UAE_CACHESIZE, 16384);

        e.putInt(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE, 2);
        e.putInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, 512);

        e.putInt(UaeOptionKeys.UAE_GFXCARD_SIZE_MB, 32);
        e.putString(UaeOptionKeys.UAE_GFXCARD_TYPE, "ZorroIII");
    }

    private void clearAgsAutoMount() {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_AGS_AUTOMOUNT_ENABLED, false);
        e.remove(UaeOptionKeys.UAE_DRIVE_AGS_BASE_PATH);
        e.apply();
    }

    private void clearDirMount(int idx) {
        SharedPreferences.Editor e = prefs.edit();
        if (idx == 0) {
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
            e.remove(UaeOptionKeys.UAE_DRIVE_DIR0_PATH);
        } else {
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false);
            e.remove(UaeOptionKeys.UAE_DRIVE_DIR1_PATH);
        }
        e.apply();

        File d = new File(getAmiberryBaseDir(), idx == 0 ? "hdd0" : "hdd1");
        deleteRecursive(d);
    }

    private void clearHdf0() {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
        e.remove(UaeOptionKeys.UAE_DRIVE_HDF0_PATH);
        e.apply();

        File d = new File(getAmiberryBaseDir(), "hdf");
        deleteRecursive(d);
    }

    private void clearHdf1() {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
        e.remove(UaeOptionKeys.UAE_DRIVE_HDF1_PATH);
        e.apply();

        File d = new File(getAmiberryBaseDir(), "hdf");
        // Keep it simple: removing the whole folder also clears HDF0 if present.
        // Users can re-import as needed.
        deleteRecursive(d);
    }

    private void clearCd0() {
        SharedPreferences.Editor e = prefs.edit();
        e.remove(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);
        e.apply();

        // Clear the legacy "cd" folder and the newer "disks/cd0" import folder.
        deleteRecursive(new File(getAmiberryBaseDir(), "cd"));
        deleteRecursive(getInternalCd0Dir());
    }

    private void clearDosFsModule() {
        SharedPreferences.Editor e = prefs.edit();
        String existing = prefs.getString(UaeOptionKeys.UAE_DRIVE_DOS_FS_MODULE_PATH, null);
        e.remove(UaeOptionKeys.UAE_DRIVE_DOS_FS_MODULE_PATH);
        e.apply();

        if (existing != null && !existing.trim().isEmpty()) {
            try {
                File f = new File(existing.trim());
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void pickCdImage0() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(intent, REQ_PICK_CD_IMAGE0);
    }

    private void pickFile(int req, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        startActivityForResult(intent, req);
    }

    private void pickDirectory(int req) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        }
        startActivityForResult(intent, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();

        // For single-file results, take persistable permission immediately.
        // For multi-select (CD picker), permissions are taken per-URI below.
        if (uri != null) {
            try {
                int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Throwable ignored) {
            }
        }
        // Also take permissions for all ClipData items (multi-select).
        if (data.getClipData() != null) {
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            android.content.ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u == null) continue;
                try {
                    getContentResolver().takePersistableUriPermission(u, takeFlags);
                } catch (Throwable ignored) {
                }
            }
        }

        // For multi-select CD picks, getData() may be null; only require it for single-file pickers.
        if (uri == null && requestCode != REQ_PICK_CD_IMAGE0 && requestCode != REQ_PICK_CD_BIN_FOLDER) return;

        try {
            if (requestCode == REQ_PICK_CD_IMAGE0) {
                // Collect all selected URIs (multi-select for CUE+BIN bundling).
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    android.content.ClipData cd = data.getClipData();
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                } else if (uri != null) {
                    uris.add(uri);
                }

                if (uris.isEmpty()) {
                    Toast.makeText(this, "No CD file selected", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Find the best main file URI and build display-name map.
                Uri bestUri = null;
                String bestLabel = null;
                int bestPri = 999;
                java.util.Map<Uri, String> nameByUri = new java.util.HashMap<>();

                for (Uri u : uris) {
                    String displayName = queryDisplayName(u);
                    if (displayName == null || displayName.trim().isEmpty()) displayName = "cd.bin";
                    nameByUri.put(u, displayName);
                    String ext = lowerExt(displayName);
                    if (!isValidCdExtension(ext)) continue;
                    int pri = cdMainPriority(ext);
                    if (pri < bestPri) {
                        bestPri = pri;
                        bestUri = u;
                        bestLabel = displayName;
                    }
                }

                if (bestUri == null) {
                    Toast.makeText(this, "Please select an .iso, .cue, .bin, or .chd file", Toast.LENGTH_LONG).show();
                    return;
                }

                if ("cue".equals(lowerExt(bestLabel))) {
                    // CUE: copy everything to internal storage so the native CUE parser can
                    // resolve the companion BIN files by plain filesystem path.
                    handleCueImport(uris, nameByUri, bestUri, bestLabel);
                } else {
                    // ISO/BIN/CHD: store content:// URI directly.
                    // manglefilename in zfile.cpp now preserves content:// so the native core
                    // can open it via SafFileBridge.
                    prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, bestUri.toString()).apply();
                }
            } else if (requestCode == REQ_PICK_CD_BIN_FOLDER) {
                handleCdBinFolderResult(uri, data);
                return; // refreshUiFromPrefs called inside
            } else if (requestCode == REQ_PICK_HDF0) {
                String path = tryResolveToFilesystemPath(uri);
                prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, path != null ? path : uri.toString()).apply();
            } else if (requestCode == REQ_PICK_HDF1) {
                String path = tryResolveToFilesystemPath(uri);
                prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_HDF1_PATH, path != null ? path : uri.toString()).apply();
            } else if (requestCode == REQ_PICK_DOS_FS) {
                // Copy the selected filesystem module into app-internal storage, because the core
                // expects to open it as a normal file path (zfile_fopen), not via SAF.
                File out = copyUriToInternalFsModule(uri);
                if (out != null) {
                    prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_DOS_FS_MODULE_PATH, out.getAbsolutePath()).apply();
                }
            } else if (requestCode == REQ_PICK_AGS_ROOT) {
                String path = tryResolveToFilesystemPath(uri);
                prefs.edit()
                    .putString(UaeOptionKeys.UAE_DRIVE_AGS_BASE_PATH, path != null ? path : uri.toString())
                    .putBoolean(UaeOptionKeys.UAE_DRIVE_AGS_AUTOMOUNT_ENABLED, true)
                    .apply();
            } else if (requestCode == REQ_PICK_DIR0 || requestCode == REQ_PICK_DIR1) {
                // Directory mounts (filesystem2) currently require native directory support.
                // We still store the tree URI so we can enable proper SAF-backed directory access later.
                int idx = (requestCode == REQ_PICK_DIR0) ? 0 : 1;
                if (idx == 0) {
                    prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, uri.toString()).apply();
                } else {
                    prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, uri.toString()).apply();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "SAF selection failed: " + t.getMessage(), t);
        }

        refreshUiFromPrefs();
    }

    // ---- CD image import helpers ----

    private static final String INTERNAL_CD0_DIR = "cd0";

    private File getInternalCd0Dir() {
        return new File(new File(getAmiberryBaseDir(), "disks"), INTERNAL_CD0_DIR);
    }

    private static boolean isValidCdExtension(String ext) {
        return "iso".equals(ext) || "cue".equals(ext) || "bin".equals(ext) || "chd".equals(ext);
    }

    private static int cdMainPriority(String ext) {
        switch (ext == null ? "" : ext) {
            case "cue": return 0;
            case "iso": return 1;
            case "chd": return 2;
            case "bin": return 3;
            default:    return 99;
        }
    }

    private static String lowerExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Imports a CUE (and companion BINs from multi-select) into internal storage so that
     * the native CUE parser can resolve BIN companion files via plain filesystem paths.
     */
    private void handleCueImport(List<Uri> uris, java.util.Map<Uri, String> nameByUri,
                                  Uri cueUri, String cueLabel) {
        File cd0Dir = getInternalCd0Dir();
        deleteRecursive(cd0Dir);
        ensureDir(cd0Dir);

        // Copy every selected file into cd0Dir.
        File cueDest = null;
        for (Uri u : uris) {
            String name = nameByUri.get(u);
            if (name == null || name.trim().isEmpty()) name = "track.bin";
            String ext = lowerExt(name);
            if (!isValidCdExtension(ext) && !"bin".equals(ext)) continue;
            File dest = new File(cd0Dir, BootstrapMediaUtils.safeFilename(name, "track.bin"));
            if (!copyUriToFile(u, dest)) {
                Log.w(TAG, "Failed to copy " + name + " to internal storage");
                continue;
            }
            if (u.equals(cueUri)) {
                cueDest = dest;
            }
        }

        if (cueDest == null || !cueDest.exists() || cueDest.length() <= 0) {
            Toast.makeText(this, "Failed to import CUE file", Toast.LENGTH_LONG).show();
            return;
        }

        BootstrapMediaUtils.fixCueTrackFilenameCase(cueDest);

        if (BootstrapMediaUtils.cueHasMissingTracks(cueDest)) {
            List<String> tracks = BootstrapMediaUtils.parseCueTrackFilenames(cueDest);
            List<String> missing = new ArrayList<>();
            for (String track : tracks) {
                if (track == null || track.trim().isEmpty()) continue;
                if (!new File(cd0Dir, track).exists()) {
                    missing.add(track);
                }
            }
            if (!missing.isEmpty()) {
                mPendingCueFile = cueDest;
                mPendingCueTracks = missing;
                Toast.makeText(this, "Select the folder containing the BIN track(s)", Toast.LENGTH_LONG).show();
                pickCdBinFolder();
                return;
            }
            Toast.makeText(this, "CUE import failed: missing BIN tracks", Toast.LENGTH_LONG).show();
            return;
        }

        // All tracks present — store the filesystem path.
        mPendingCueFile = null;
        mPendingCueTracks = null;
        prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, cueDest.getAbsolutePath()).apply();
        Toast.makeText(this, "CUE imported successfully", Toast.LENGTH_SHORT).show();
        refreshUiFromPrefs();
    }

    private void pickCdBinFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        }
        startActivityForResult(intent, REQ_PICK_CD_BIN_FOLDER);
    }

    private void handleCdBinFolderResult(Uri treeUri, Intent data) {
        if (treeUri == null || mPendingCueFile == null || mPendingCueTracks == null
                || mPendingCueTracks.isEmpty()) {
            Toast.makeText(this, "No pending CUE tracks to import", Toast.LENGTH_SHORT).show();
            return;
        }

        DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
        if (tree == null || !tree.isDirectory()) {
            Toast.makeText(this, "Invalid folder selected", Toast.LENGTH_SHORT).show();
            return;
        }

        File cd0Dir = getInternalCd0Dir();
        ensureDir(cd0Dir);

        for (String trackName : mPendingCueTracks) {
            if (trackName == null || trackName.trim().isEmpty()) continue;
            DocumentFile src = BootstrapMediaUtils.findFileByNameIgnoreCase(tree, trackName, 2);
            if (src == null) {
                Toast.makeText(this, "Missing track in selected folder: " + trackName, Toast.LENGTH_LONG).show();
                return;
            }
            File dest = new File(cd0Dir, trackName);
            if (!copyDocumentFileTo(src, dest)) {
                Toast.makeText(this, "Failed to import track: " + trackName, Toast.LENGTH_LONG).show();
                return;
            }
        }

        BootstrapMediaUtils.fixCueTrackFilenameCase(mPendingCueFile);
        if (BootstrapMediaUtils.cueHasMissingTracks(mPendingCueFile)) {
            Toast.makeText(this, "CUE still missing tracks after folder import", Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit().putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, mPendingCueFile.getAbsolutePath()).apply();
        Toast.makeText(this, "CUE/BIN imported successfully", Toast.LENGTH_SHORT).show();

        mPendingCueFile = null;
        mPendingCueTracks = null;
        refreshUiFromPrefs();
    }

    private boolean copyUriToFile(Uri uri, File dest) {
        if (uri == null || dest == null) return false;
        ensureDir(dest.getParentFile());
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest, false)) {
            if (in == null) return false;
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
            return dest.exists() && dest.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "copyUriToFile failed: " + t.getMessage(), t);
            return false;
        }
    }

    private boolean copyDocumentFileTo(DocumentFile src, File dest) {
        if (src == null || !src.isFile()) return false;
        Uri srcUri = src.getUri();
        if (srcUri == null) return false;
        return copyUriToFile(srcUri, dest);
    }

    // ---- end CD image import helpers ----

    private String tryResolveToFilesystemPath(Uri uri) {
        if (uri == null) return null;
        try {
            // If it's already a file:// URI, use it.
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }

            // Best-effort conversion for common SAF providers.
            if (DocumentsContract.isDocumentUri(this, uri)) {
                final String authority = uri.getAuthority();
                final String docId = DocumentsContract.getDocumentId(uri);

                // ExternalStorageProvider
                if ("com.android.externalstorage.documents".equals(authority)) {
                    String[] split = docId.split(":", 2);
                    String type = split.length > 0 ? split[0] : "";
                    String rel = split.length > 1 ? split[1] : "";
                    if ("primary".equalsIgnoreCase(type)) {
                        return new File(Environment.getExternalStorageDirectory(), rel).getAbsolutePath();
                    }
                    // Likely removable SD card, type is the volume ID.
                    if (!type.isEmpty()) {
                        return new File(new File("/storage", type), rel).getAbsolutePath();
                    }
                }

                // DownloadsProvider (may not be resolvable on modern Android)
                if ("com.android.providers.downloads.documents".equals(authority)) {
                    // Some IDs are "raw:/storage/...".
                    if (docId != null && docId.startsWith("raw:")) {
                        return docId.substring(4);
                    }
                }

                // MediaProvider (may not expose _data)
                if ("com.android.providers.media.documents".equals(authority)) {
                    // Fall through to generic query below.
                }
            }

            // Generic attempt: query for _data. Works on some devices/Android versions.
            String data = queryDataColumn(uri);
            if (data != null && !data.trim().isEmpty()) return data;

            return null;
        } catch (Throwable t) {
            Log.w(TAG, "Failed resolving URI to path: " + uri + " err=" + t);
            return null;
        }
    }

    private String queryDataColumn(Uri uri) {
        Cursor c = null;
        try {
            String[] projection = new String[] {"_data"};
            c = getContentResolver().query(uri, projection, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private File copyUriToInternalFsModule(Uri uri) {
        if (uri == null) return null;
        try {
            String name = queryDisplayName(uri);
            if (name == null || name.trim().isEmpty()) {
                name = "FastFileSystem";
            }

            // Normalize common names so users can pick either "FastFileSystem" or a renamed file.
            // Keep original extension if any.
            File dir = new File(getAmiberryBaseDir(), "fs");
            ensureDir(dir);
            File out = new File(dir, name);

            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out, false)) {
                if (in == null) return null;
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                }
                os.flush();
            }

            if (!out.exists() || out.length() <= 0) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                return null;
            }

            LogUtil.i(TAG, "Imported filesystem module to " + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to import filesystem module: " + t.getMessage(), t);
            return null;
        }
    }

    // Note: HDF/CD images can be huge; we keep them in-place and store a filesystem path whenever
    // possible (resolved from SAF). If resolution isn't possible, a content:// URI will be stored.

    private void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private String safeText(EditText et, String def) {
        if (et == null) return def;
        String s = et.getText() != null ? et.getText().toString().trim() : "";
        return s.isEmpty() ? def : s;
    }

    private int safeInt(EditText et, int def) {
        String s = safeText(et, "");
        if (s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return def;
        }
    }

    private String guessExtensionForUri(Uri uri) {
        String name = queryDisplayName(uri);
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return "";
        return name.substring(dot);
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private File getAmiberryBaseDir() {
        // App-private storage root (legacy folder supported).
        File f = AppPaths.getBaseDir(this);
        ensureDir(f);
        return f;
    }
}
