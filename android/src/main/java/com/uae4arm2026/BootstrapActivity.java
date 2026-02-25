package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BootstrapActivity extends Activity {
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Important for launchMode=singleTask: a new launcher tap can reuse the existing instance.
        // Always refresh our flags from the new intent so we don't get stuck in "from emulator menu" mode.
        try {
            setIntent(intent);
        } catch (Throwable ignored) {
        }
        try {
            mLaunchedFromEmulatorMenu = intent != null && intent.getBooleanExtra(EXTRA_FROM_EMULATOR_MENU, false);
            mOpenMediaSwapperOnStart = intent != null && intent.getBooleanExtra(EXTRA_OPEN_MEDIA_SWAPPER, false);
        } catch (Throwable ignored) {
            mLaunchedFromEmulatorMenu = false;
            mOpenMediaSwapperOnStart = false;
            mOpenMediaSectionOnStart = null;
        }

        // launchMode=singleTask can reuse an existing BootstrapActivity when the app icon is tapped.
        // Treat that as a fresh launcher start and clear persisted media/WHD state.
        if (!mLaunchedFromEmulatorMenu) {
            clearConnectedMediaForColdLauncherStart();
            try {
                refreshStatus();
            } catch (Throwable ignored) {
            }
        }
    }

    private void quitAppFully() {
        try {
            LogUtil.i(TAG, "Exit pressed (fromEmuMenu=" + mLaunchedFromEmulatorMenu + ")");
        } catch (Throwable ignored) {
        }

        // Always request the dedicated :emu process to terminate if it is running.
        try {
            EmulatorProcessControl.requestEmulatorProcessExit(this);
        } catch (Throwable ignored) {
        }

        // Try to go Home first (gives a clean UX even if finishAndRemoveTask is flaky).
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable ignored) {
        }

        try {
            moveTaskToBack(true);
        } catch (Throwable ignored) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finishAffinity();
            }
        } catch (Throwable t) {
            Log.w(TAG, "finishAndRemoveTask/finishAffinity failed: " + t);
            try { finish(); } catch (Throwable ignored) {}
        }

        // Ensure the process exits (some devices keep activities alive in singleTask).
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
        }
        try {
            System.exit(0);
        } catch (Throwable ignored) {
        }
    }

    private static final String TAG = "BootstrapActivity";

    private BootstrapFileImporter mFileImporter;

    private BootstrapFileImporter fileImporter() {
        if (mFileImporter == null) {
            mFileImporter = new BootstrapFileImporter(this);
        }
        return mFileImporter;
    }

    private static String describeFilePath(String path) {
        String p = safeTrim(path);
        if (p.isEmpty()) return "(empty)";
        if (p.startsWith("content://")) return "(SAF)";
        try {
            File f = new File(p);
            return "exists=" + f.exists() + " file=" + f.isFile() + " len=" + f.length() + " mtime=" + f.lastModified();
        } catch (Throwable t) {
            return "(error: " + t + ")";
        }
    }

    public static final String EXTRA_FROM_EMULATOR_MENU = "com.uae4arm2026.extra.FROM_EMULATOR_MENU";
    public static final String EXTRA_OPEN_MEDIA_SWAPPER = "com.uae4arm2026.extra.OPEN_MEDIA_SWAPPER";
    public static final String EXTRA_OPEN_MEDIA_SECTION = "com.uae4arm2026.extra.OPEN_MEDIA_SECTION";
    public static final String MEDIA_SECTION_DF = "df";
    public static final String MEDIA_SECTION_HD = "hd";
    public static final String MEDIA_SECTION_CD = "cd";

    // When launched from the running emulator, provide the current mounted floppy paths
    // so the swapper can display accurate state even if prefs are stale.
    public static final String EXTRA_EMU_CURRENT_DF0_PATH = "com.uae4arm2026.extra.EMU_CURRENT_DF0_PATH";
    public static final String EXTRA_EMU_CURRENT_DF1_PATH = "com.uae4arm2026.extra.EMU_CURRENT_DF1_PATH";
    public static final String EXTRA_EMU_CURRENT_DF2_PATH = "com.uae4arm2026.extra.EMU_CURRENT_DF2_PATH";
    public static final String EXTRA_EMU_CURRENT_DF3_PATH = "com.uae4arm2026.extra.EMU_CURRENT_DF3_PATH";

    // Imported kickstarts are stored as kick.<ext> (e.g. kick.rom or kick.zip).
    private static final String INTERNAL_KICKSTART_PREFIX = "kick";
    private static final String INTERNAL_EXT_ROM_PREFIX = "extrom";
    private static final String INTERNAL_CD0_DIR = "cd0";
    private static final String INTERNAL_HARDDRIVES_DIR = "harddrives";
    private static final String INTERNAL_DH0_DIR = "dh0";
    private static final String INTERNAL_DH1_DIR = "dh1";
    private static final String INTERNAL_DH2_DIR = "dh2";
    private static final String INTERNAL_DH3_DIR = "dh3";
    private static final String INTERNAL_DH4_DIR = "dh4";
    private static final String INTERNAL_DH0_HDF_PREFIX = "dh0";
    private static final String INTERNAL_DH1_HDF_PREFIX = "dh1";
    private static final String INTERNAL_DH2_HDF_PREFIX = "dh2";
    private static final String INTERNAL_DH3_HDF_PREFIX = "dh3";
    private static final String INTERNAL_DH4_HDF_PREFIX = "dh4";

    private static final int REQ_FIRST_RUN_FOLDER = 1000;
    private static final int REQ_IMPORT_KICKSTART = 1001;

    private static final int REQ_IMPORT_DF0 = 1002;
    private static final int REQ_IMPORT_DF1 = 1003;
    private static final int REQ_IMPORT_EXT_ROM = 1004;
    private static final int REQ_IMPORT_CDIMAGE0 = 1005;
    private static final int REQ_IMPORT_CDIMAGE0_DIR = 1006;
    private static final int REQ_IMPORT_DH0_HDF = 1007;
    private static final int REQ_IMPORT_DH0_DIR = 1008;
    private static final int REQ_IMPORT_DH1_HDF = 1011;
    private static final int REQ_IMPORT_DH1_DIR = 1012;

    private static final int REQ_IMPORT_DF2 = 1013;
    private static final int REQ_IMPORT_DF3 = 1014;

    private static final int REQ_IMPORT_DH2_HDF = 1015;
    private static final int REQ_IMPORT_DH2_DIR = 1016;
    private static final int REQ_IMPORT_DH3_HDF = 1017;
    private static final int REQ_IMPORT_DH3_DIR = 1018;
    private static final int REQ_IMPORT_DH4_HDF = 1019;
    private static final int REQ_IMPORT_DH4_DIR = 1020;
    private static final int REQ_IMPORT_WHDLOAD = 1030;

    private TextView mKickStatus;
    private TextView mExtStatus;
    private TextView mCd0Status;
    private TextView mDf0Status;
    private TextView mDf1Status;
    private TextView mDh0Status;
    private TextView mDh1Status;

    private View mExtRomSection;

    private View mDf1Controls;
    private View mBtnAddDf1;
    private boolean mDf1Added;

    private boolean mDf2Added;
    private boolean mDf3Added;

    private View mBtnAddDh0;
    private boolean mDh0Added;

    private View mCd0Section;
    private View mBtnAddCd0;
    private boolean mCd0Added;
    private boolean mDh1Added;

    private boolean mDh2Added;
    private boolean mDh3Added;
    private boolean mDh4Added;

    private Integer mFloppySpeedWhenOpenedFromEmu;

    private View mBtnExitTop;
    private View mBtnExitApp;

    private Spinner mQsModelSpinner;
    private Spinner mQsConfigSpinner;
    private TextView mQsConfigFull;
    private Button mBtnKickMap;

    private ArrayList<String> mCurrentConfigOptions = new ArrayList<>();

    private View mReturnBar;
    private View mBtnResumeToEmu;
    private View mBtnRestartEmu;
    private boolean mLaunchedFromEmulatorMenu;
    private boolean mOpenMediaSwapperOnStart;
    private String mOpenMediaSectionOnStart;
    private String mLastMediaSection = MEDIA_SECTION_DF;

    private String mDf0PathWhenOpenedFromEmu;
    private String mDf0SigWhenOpenedFromEmu;
    private String mDf1PathWhenOpenedFromEmu;
    private String mDf2PathWhenOpenedFromEmu;
    private String mDf3PathWhenOpenedFromEmu;
    private String mCd0PathWhenOpenedFromEmu;
    private String mDh0SigWhenOpenedFromEmu;
    private String mDh1SigWhenOpenedFromEmu;
    private String mDh2SigWhenOpenedFromEmu;
    private String mDh3SigWhenOpenedFromEmu;
    private String mDh4SigWhenOpenedFromEmu;

    private android.app.AlertDialog mMediaSwapperDialog;

    private boolean mReopenMediaSwapperAfterPicker;

    private boolean mReopenKickstartMapAfterPicker;

    private void requestReopenMediaSwapperAfterPicker() {
        mReopenMediaSwapperAfterPicker = true;
    }

    private void requestReopenKickstartMapAfterPicker() {
        mReopenKickstartMapAfterPicker = true;
    }

    private void maybeReopenMediaSwapperAfterPicker() {
        if (!mReopenMediaSwapperAfterPicker) return;
        mReopenMediaSwapperAfterPicker = false;

        if (mLaunchedFromEmulatorMenu) {
            try {
                int action = getPendingResumeActionFromPrefs();
                if (action == 2) {
                    requestEmulatorRestartAndFinish();
                } else if (action == 1) {
                    requestHotSwapToRunningEmulatorAndFinish();
                } else {
                    finish();
                }
            } catch (Throwable ignored) {
                finish();
            }
            return;
        }

        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (isFinishing()) return;
                    if (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;
                    showMediaSwapperDialog();
                } catch (Throwable ignored) {
                }
            }, 150);
        } catch (Throwable ignored) {
        }
    }

    private void maybeReopenKickstartMapAfterPicker() {
        if (!mReopenKickstartMapAfterPicker) return;
        mReopenKickstartMapAfterPicker = false;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (isFinishing()) return;
                    if (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;
                    showKickstartMapDialog();
                } catch (Throwable ignored) {
                }
            }, 150);
        } catch (Throwable ignored) {
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private String shortLabelForPath(String path) {
        String p = safeTrim(path);
        if (p.isEmpty()) return "(not set)";

        try {
            if (isContentUriString(p)) {
                String dn = getDisplayName(Uri.parse(p));
                if (dn != null && !dn.trim().isEmpty()) return dn.trim();
                return "(SAF)";
            }
        } catch (Throwable ignored) {
        }

        try {
            File f = new File(p);
            String n = f.getName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {
        }
        return p;
    }

    private String getUaePrefString(String key) {
        try {
            return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).getString(key, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void addMediaSwapperRow(android.widget.LinearLayout root, String label, Runnable onSwap) {
        addMediaSwapperRowWithEject(root, label, onSwap, null);
    }

    private void addMediaSwapperRowWithEject(android.widget.LinearLayout root, String label, Runnable onInsert, Runnable onEject) {
        if (root == null) return;
        try {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);

            TextView tv = new TextView(this);
            tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(label);

            Button btnInsert = new Button(this);
            btnInsert.setText("Insert");
            btnInsert.setOnClickListener(v -> {
                try {
                    requestReopenMediaSwapperAfterPicker();
                    if (mMediaSwapperDialog != null) {
                        try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) {
                }
                try { if (onInsert != null) onInsert.run(); } catch (Throwable ignored) { }
            });

            row.addView(tv);
            row.addView(btnInsert);

            // Add Eject button if eject handler provided
            if (onEject != null) {
                Button btnEject = new Button(this);
                btnEject.setText("Eject");
                btnEject.setOnClickListener(v -> {
                    try { onEject.run(); } catch (Throwable ignored) { }
                    try {
                        requestReopenMediaSwapperAfterPicker();
                        if (mMediaSwapperDialog != null) {
                            try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                        }
                        maybeReopenMediaSwapperAfterPicker();
                    } catch (Throwable ignored) {
                    }
                });
                row.addView(btnEject);
            }

            root.addView(row);
        } catch (Throwable ignored) {
        }
    }

    private void addMediaSwapperActionRow(android.widget.LinearLayout root, String label, String buttonLabel, Runnable onAction) {
        if (root == null) return;
        try {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);

            TextView tv = new TextView(this);
            tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(label);

            Button btn = new Button(this);
            btn.setText(buttonLabel == null ? "Select" : buttonLabel);
            btn.setOnClickListener(v -> {
                try {
                    requestReopenMediaSwapperAfterPicker();
                    if (mMediaSwapperDialog != null) {
                        try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) {
                }
                try { if (onAction != null) onAction.run(); } catch (Throwable ignored) { }
            });

            row.addView(tv);
            row.addView(btn);
            root.addView(row);
        } catch (Throwable ignored) {
        }
    }

    private void addMediaSwapperRowWithClear(android.widget.LinearLayout root, String label, Runnable onSwap, String clearLabel, Runnable onClear) {
        if (root == null) return;
        try {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);

            TextView tv = new TextView(this);
            tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(label);

            Button btnSwap = new Button(this);
            btnSwap.setText("Insert");
            btnSwap.setOnClickListener(v -> {
                try {
                    requestReopenMediaSwapperAfterPicker();
                    if (mMediaSwapperDialog != null) {
                        try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) {
                }
                try { if (onSwap != null) onSwap.run(); } catch (Throwable ignored) { }
            });

            Button btnClear = new Button(this);
            btnClear.setText(clearLabel == null ? "Clear" : clearLabel);
            btnClear.setOnClickListener(v -> {
                try { if (onClear != null) onClear.run(); } catch (Throwable ignored) { }
                try {
                    requestReopenMediaSwapperAfterPicker();
                    if (mMediaSwapperDialog != null) {
                        try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    }
                    maybeReopenMediaSwapperAfterPicker();
                } catch (Throwable ignored) {
                }
            });

            row.addView(tv);
            row.addView(btnSwap);
            row.addView(btnClear);
            root.addView(row);
        } catch (Throwable ignored) {
        }
    }

    private String dhSignatureFromUaePrefs(SharedPreferences uaePrefs, int dhIndex) {
        if (uaePrefs == null) return "";
        try {
            if (dhIndex == 0) {
                boolean dir = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
                if (dir) return "DIR:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, ""));
                boolean hdf = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
                if (hdf) return "HDF:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, ""));
                return "";
            }
            if (dhIndex == 1) {
                boolean dir = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false);
                if (dir) return "DIR:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, ""));
                boolean hdf = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
                if (hdf) return "HDF:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF1_PATH, ""));
                return "";
            }
            if (dhIndex == 2) {
                boolean dir = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED, false);
                if (dir) return "DIR:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DIR2_PATH, ""));
                boolean hdf = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED, false);
                if (hdf) return "HDF:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF2_PATH, ""));
                return "";
            }
            if (dhIndex == 3) {
                boolean dir = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED, false);
                if (dir) return "DIR:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DIR3_PATH, ""));
                boolean hdf = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED, false);
                if (hdf) return "HDF:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF3_PATH, ""));
                return "";
            }
            if (dhIndex == 4) {
                boolean dir = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED, false);
                if (dir) return "DIR:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DIR4_PATH, ""));
                boolean hdf = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED, false);
                if (hdf) return "HDF:" + normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF4_PATH, ""));
                return "";
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String dhSignatureFromUaePrefs(SharedPreferences uaePrefs, boolean dh1) {
        return dhSignatureFromUaePrefs(uaePrefs, dh1 ? 1 : 0);
    }

    // 0 = no change, 1 = hot-swap only (DF1/DF2/DF3), 2 = restart required (DF0/CD0/DH0/DH1)
    private int getPendingResumeActionFromPrefs() {
        if (!mLaunchedFromEmulatorMenu) return 0;
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            final boolean cdOnly = isCdOnlyModel();

            String cdNow = normalizeMediaPath(p.getString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, ""));
            String cdBefore = normalizeMediaPath(mCd0PathWhenOpenedFromEmu);
            boolean cdChanged = !cdNow.equals(cdBefore);
            if (cdOnly) {
                return cdChanged ? 2 : 0;
            }

            String dh0Now = dhSignatureFromUaePrefs(p, false);
            String dh1Now = dhSignatureFromUaePrefs(p, true);
            String dh2Now = dhSignatureFromUaePrefs(p, 2);
            String dh3Now = dhSignatureFromUaePrefs(p, 3);
            String dh4Now = dhSignatureFromUaePrefs(p, 4);
            String dh0Before = (mDh0SigWhenOpenedFromEmu == null) ? "" : mDh0SigWhenOpenedFromEmu;
            String dh1Before = (mDh1SigWhenOpenedFromEmu == null) ? "" : mDh1SigWhenOpenedFromEmu;
            String dh2Before = (mDh2SigWhenOpenedFromEmu == null) ? "" : mDh2SigWhenOpenedFromEmu;
            String dh3Before = (mDh3SigWhenOpenedFromEmu == null) ? "" : mDh3SigWhenOpenedFromEmu;
            String dh4Before = (mDh4SigWhenOpenedFromEmu == null) ? "" : mDh4SigWhenOpenedFromEmu;
            boolean dhChanged = !dh0Now.equals(dh0Before)
                || !dh1Now.equals(dh1Before)
                || !dh2Now.equals(dh2Before)
                || !dh3Now.equals(dh3Before)
                || !dh4Now.equals(dh4Before);
            if (dhChanged) return 2;
            if (cdChanged) return 2;

            String df0Now = normalizeMediaPath(p.getString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, ""));
            String df1Now = normalizeMediaPath(p.getString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, ""));
            String df2Now = normalizeMediaPath(p.getString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, ""));
            String df3Now = normalizeMediaPath(p.getString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, ""));
            String df0Before = normalizeMediaPath(mDf0PathWhenOpenedFromEmu);
            String df1Before = normalizeMediaPath(mDf1PathWhenOpenedFromEmu);
            String df2Before = normalizeMediaPath(mDf2PathWhenOpenedFromEmu);
            String df3Before = normalizeMediaPath(mDf3PathWhenOpenedFromEmu);
            
            // DF0 changes are handled via runtime hot-swap (no emulator restart).
            // Compare signature (path+size+mtime) so replacing disk.zip at the same
            // path still counts as a changed boot disk.
            String df0SigNow = floppySignatureFromPath(df0Now);
            String df0SigBefore = (mDf0SigWhenOpenedFromEmu == null) ? floppySignatureFromPath(df0Before) : mDf0SigWhenOpenedFromEmu;
            boolean df0Changed = !df0SigNow.equals(df0SigBefore);
            if (df0Changed) return 1;
            
            boolean df1Changed = !df1Now.equals(df1Before);
            boolean df2Changed = !df2Now.equals(df2Before);
            boolean df3Changed = !df3Now.equals(df3Before);
            boolean dfChanged = df1Changed || df2Changed || df3Changed;

            if (dfChanged) return 1;

            // Floppy speed changes require restart.
            try {
                int speedNow = p.getInt(UaeOptionKeys.UAE_FLOPPY_SPEED, 100);
                int speedBefore = (mFloppySpeedWhenOpenedFromEmu != null) ? mFloppySpeedWhenOpenedFromEmu : 100;
                if (speedNow != speedBefore) return 2;
            } catch (Throwable ignored2) {
            }

            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void clearDh0FromUaePrefs() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            boolean dir = p.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
            if (dir) {
                deleteRecursive(getInternalDh0Dir());
                mSelectedDh0Dir = null;
                mDh0SourceName = null;
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false)
                    .remove(UaeOptionKeys.UAE_DRIVE_DIR0_PATH)
                    .apply();
            } else {
                clearByPrefix(getInternalHarddrivesDir(), INTERNAL_DH0_HDF_PREFIX);
                mSelectedDh0Hdf = null;
                mSelectedDh0HdfPath = null;
                mDh0SourceName = null;
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false)
                    .remove(UaeOptionKeys.UAE_DRIVE_HDF0_PATH)
                    .apply();
            }

            boolean keepHd;
            try {
                SharedPreferences up = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                keepHd = up.getBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false) || up.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
            } catch (Throwable ignored) {
                keepHd = false;
            }
            mDh0Added = keepHd;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH0, keepHd).apply();
        } catch (Throwable ignored) {
        }

        try {
            saveSourceNames();
            refreshStatus();
        } catch (Throwable ignored) {
        }
    }

    private void removeDh1CompletelyFromUaePrefs() {
        try {
            deleteRecursive(getInternalDh1Dir());
        } catch (Throwable ignored) {
        }

        try {
            mSelectedDh1Dir = null;
            mSelectedDh1Hdf = null;
            mSelectedDh1HdfPath = null;
            mDh1SourceName = null;
            mDh1Added = false;

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH1, false).apply();

            getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false)
                .remove(UaeOptionKeys.UAE_DRIVE_HDF1_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DIR1_PATH)
                .apply();
        } catch (Throwable ignored) {
        }

        try {
            saveSourceNames();
            refreshStatus();
        } catch (Throwable ignored) {
        }
    }

    private void removeDfCompletelyFromUaePrefs(int dfIndex) {
        if (dfIndex < 1 || dfIndex > 3) return;
        try {
            String prefix = "df" + dfIndex;
            clearByPrefix(getInternalDisksDir(), prefix);

            SharedPreferences.Editor uaeEd = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            SharedPreferences.Editor launcherEd = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();

            if (dfIndex == 1) {
                mSelectedDf1 = null;
                mSelectedDf1Path = null;
                mDf1SourceName = null;
                mDf1Added = false;
                launcherEd.putBoolean(PREF_SHOW_DF1, false);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_DF1_PATH);
            } else if (dfIndex == 2) {
                mSelectedDf2 = null;
                mSelectedDf2Path = null;
                mDf2SourceName = null;
                mDf2Added = false;
                launcherEd.putBoolean(PREF_SHOW_DF2, false);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_DF2_PATH);
            } else if (dfIndex == 3) {
                mSelectedDf3 = null;
                mSelectedDf3Path = null;
                mDf3SourceName = null;
                mDf3Added = false;
                launcherEd.putBoolean(PREF_SHOW_DF3, false);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_DF3_PATH);
            }

            launcherEd.apply();
            uaeEd.apply();
        } catch (Throwable ignored) {
        }

        try {
            saveSourceNames();
            refreshStatus();
        } catch (Throwable ignored) {
        }
    }

    private void removeDhCompletelyFromUaePrefs(int dhIndex) {
        if (dhIndex < 2 || dhIndex > 4) return;
        try {
            SharedPreferences.Editor launcherEd = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            SharedPreferences.Editor uaeEd = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();

            if (dhIndex == 2) {
                deleteRecursive(getInternalDh2Dir());
                mSelectedDh2Dir = null;
                mSelectedDh2Hdf = null;
                mSelectedDh2HdfPath = null;
                mDh2SourceName = null;
                mDh2Added = false;
                launcherEd.putBoolean(PREF_SHOW_DH2, false);
                uaeEd.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED, false);
                uaeEd.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED, false);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_DIR2_PATH);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_HDF2_PATH);
            } else if (dhIndex == 3) {
                deleteRecursive(getInternalDh3Dir());
                mSelectedDh3Dir = null;
                mSelectedDh3Hdf = null;
                mSelectedDh3HdfPath = null;
                mDh3SourceName = null;
                mDh3Added = false;
                launcherEd.putBoolean(PREF_SHOW_DH3, false);
                uaeEd.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED, false);
                uaeEd.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED, false);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_DIR3_PATH);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_HDF3_PATH);
            } else if (dhIndex == 4) {
                deleteRecursive(getInternalDh4Dir());
                mSelectedDh4Dir = null;
                mSelectedDh4Hdf = null;
                mSelectedDh4HdfPath = null;
                mDh4SourceName = null;
                mDh4Added = false;
                launcherEd.putBoolean(PREF_SHOW_DH4, false);
                uaeEd.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED, false);
                uaeEd.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED, false);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_DIR4_PATH);
                uaeEd.remove(UaeOptionKeys.UAE_DRIVE_HDF4_PATH);
            }

            launcherEd.apply();
            uaeEd.apply();
        } catch (Throwable ignored) {
        }

        try {
            saveSourceNames();
            refreshStatus();
        } catch (Throwable ignored) {
        }
    }

    private void showDhTypePickerDialog(int dhIndex) {
        if (dhIndex < 2 || dhIndex > 4) return;
        try {
            new AlertDialog.Builder(this)
                .setTitle("Select DH" + dhIndex)
                .setItems(new CharSequence[] { "Folder", "HDF" }, (d, which) -> {
                    if (which == 0) {
                        if (dhIndex == 2) pickDh2Folder();
                        else if (dhIndex == 3) pickDh3Folder();
                        else pickDh4Folder();
                    } else {
                        if (dhIndex == 2) pickDh2Hdf();
                        else if (dhIndex == 3) pickDh3Hdf();
                        else pickDh4Hdf();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } catch (Throwable ignored) {
            // Fallback: default to folder picker.
            if (dhIndex == 2) pickDh2Folder();
            else if (dhIndex == 3) pickDh3Folder();
            else pickDh4Folder();
        }
    }

    private String normalizeMediaSection(String section) {
        String s = safeTrim(section).toLowerCase(Locale.ROOT);
        if (MEDIA_SECTION_DF.equals(s)) return MEDIA_SECTION_DF;
        if (MEDIA_SECTION_HD.equals(s)) return MEDIA_SECTION_HD;
        if (MEDIA_SECTION_CD.equals(s)) return MEDIA_SECTION_CD;
        return "";
    }

    private void showMediaSwapperDialog() {
        showMediaSwapperDialog(mLastMediaSection);
    }

    private void showMediaSwapperDialog(String openSection) {
        try {
            if (mMediaSwapperDialog != null && mMediaSwapperDialog.isShowing()) {
                try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
            }

            final boolean cdOnly = isCdOnlyModel();
            final String normalizedSection = normalizeMediaSection(openSection);
            final String section = normalizedSection.isEmpty() ? MEDIA_SECTION_DF : normalizedSection;
            mLastMediaSection = section;
            final boolean showDf = MEDIA_SECTION_DF.equals(section);
            final boolean showHd = MEDIA_SECTION_HD.equals(section);
            final boolean showCd = MEDIA_SECTION_CD.equals(section);

            android.widget.LinearLayout root = new android.widget.LinearLayout(this);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);

            TextView hint = new TextView(this);
            hint.setText("Insert / remove mounted media devices\n(Devices must be added in Quickstart before starting the emulator)");
            root.addView(hint);

            // Action buttons for adding new drives
            try {
                android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
                actions.setOrientation(android.widget.LinearLayout.VERTICAL);
                actions.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);

                android.widget.LinearLayout row1 = new android.widget.LinearLayout(this);
                row1.setOrientation(android.widget.LinearLayout.HORIZONTAL);

                Button bHardfileFs = new Button(this);
                bHardfileFs.setText("Add Hardfile");
                bHardfileFs.setAllCaps(false);
                bHardfileFs.setOnClickListener(v -> {
                    try { if (mMediaSwapperDialog != null) mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    chooseDhSlotAndPickHdf();
                });
                android.widget.LinearLayout.LayoutParams lp1 = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp1.weight = 1;
                bHardfileFs.setLayoutParams(lp1);
                row1.addView(bHardfileFs);

                android.widget.LinearLayout row2 = new android.widget.LinearLayout(this);
                row2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row2.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);

                Button bHardDrive = new Button(this);
                bHardDrive.setText("Add Hard Drive Folder");
                bHardDrive.setAllCaps(false);
                bHardDrive.setOnClickListener(v -> {
                    try { if (mMediaSwapperDialog != null) mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    chooseDhSlotAndPick(true);
                });
                android.widget.LinearLayout.LayoutParams lp3 = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp3.weight = 1;
                bHardDrive.setLayoutParams(lp3);
                row2.addView(bHardDrive);

                Button bCd = new Button(this);
                bCd.setText("Add CD Drive");
                bCd.setAllCaps(false);
                bCd.setOnClickListener(v -> {
                    try { if (mMediaSwapperDialog != null) mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                    ensureCd0AndPick();
                });
                android.widget.LinearLayout.LayoutParams lp4 = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp4.weight = 1;
                lp4.leftMargin = (int) (8 * getResources().getDisplayMetrics().density);
                bCd.setLayoutParams(lp4);
                row2.addView(bCd);

                if (cdOnly) {
                    // CD32/CDTV: hide hard drive actions.
                    row1.setVisibility(View.GONE);
                    bHardDrive.setVisibility(View.GONE);
                }

                if (!showHd) {
                    row1.setVisibility(View.GONE);
                    bHardDrive.setVisibility(View.GONE);
                }
                if (!showCd) {
                    bCd.setVisibility(View.GONE);
                }

                if (row1.getVisibility() == View.VISIBLE || bHardDrive.getVisibility() == View.VISIBLE || bCd.getVisibility() == View.VISIBLE) {
                    actions.addView(row1);
                    actions.addView(row2);
                    root.addView(actions);

                    // Separator
                    View sep = new View(this);
                    sep.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * getResources().getDisplayMetrics().density)));
                    sep.setBackgroundColor(0xFF444444);
                    sep.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
                    root.addView(sep);
                }
            } catch (Throwable ignored) {
            }

            if (!cdOnly && showDf) {
                // Floppy speed (Turbo/100/200/400/800)
                try {
                    android.widget.LinearLayout speedRow = new android.widget.LinearLayout(this);
                    speedRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    speedRow.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);

                    TextView speedLabel = new TextView(this);
                    speedLabel.setText("Floppy speed");
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.weight = 1;
                    speedLabel.setLayoutParams(lp);
                    speedRow.addView(speedLabel);

                    final String[] speedLabels = new String[]{"Turbo", "100% (compatible)", "200%", "400%", "800%"};
                    final int[] speedValues = new int[]{0, 100, 200, 400, 800};

                    android.widget.Spinner sp = new android.widget.Spinner(this);
                    sp.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, speedLabels));
                    ((android.widget.ArrayAdapter<?>) sp.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

                    int cur = 100;
                    try {
                        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                        cur = p.getInt(UaeOptionKeys.UAE_FLOPPY_SPEED, 100);
                    } catch (Throwable ignored) {
                    }
                    int idx = 1;
                    for (int i = 0; i < speedValues.length; i++) {
                        if (speedValues[i] == cur) {
                            idx = i;
                            break;
                        }
                    }
                    sp.setSelection(idx);

                    sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                        boolean first = true;

                        @Override
                        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                            int v = 100;
                            if (position >= 0 && position < speedValues.length) v = speedValues[position];
                            try {
                                int old = 100;
                                try {
                                    old = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).getInt(UaeOptionKeys.UAE_FLOPPY_SPEED, 100);
                                } catch (Throwable ignored) {
                                }

                                // Skip initial selection callback if it matches stored value.
                                if (first) {
                                    first = false;
                                    if (old == v) return;
                                }

                                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putInt(UaeOptionKeys.UAE_FLOPPY_SPEED, v)
                                    .apply();
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        public void onNothingSelected(android.widget.AdapterView<?> parent) {
                        }
                    });

                    speedRow.addView(sp);
                    root.addView(speedRow);
                } catch (Throwable ignored) {
                }

                // Use source names (original user-facing filenames) rather than the internal
                // path (which is always "disk.zip" after import) so the label is meaningful.
                String df0SwapLabel = (mDf0SourceName != null && !mDf0SourceName.trim().isEmpty())
                    ? mDf0SourceName
                    : shortLabelForPath(getUaePrefString(UaeOptionKeys.UAE_DRIVE_DF0_PATH));
                String df1SwapLabel = (mDf1SourceName != null && !mDf1SourceName.trim().isEmpty())
                    ? mDf1SourceName
                    : shortLabelForPath(getUaePrefString(UaeOptionKeys.UAE_DRIVE_DF1_PATH));
                String df2SwapLabel = (mDf2SourceName != null && !mDf2SourceName.trim().isEmpty())
                    ? mDf2SourceName
                    : shortLabelForPath(getUaePrefString(UaeOptionKeys.UAE_DRIVE_DF2_PATH));
                String df3SwapLabel = (mDf3SourceName != null && !mDf3SourceName.trim().isEmpty())
                    ? mDf3SourceName
                    : shortLabelForPath(getUaePrefString(UaeOptionKeys.UAE_DRIVE_DF3_PATH));
                addMediaSwapperRowWithEject(root, "DF0: " + df0SwapLabel, () -> pickDisk(REQ_IMPORT_DF0), () -> {
                    clearByPrefix(getInternalDisksDir(), "df0");
                    try {
                        clearFile(new File(getInternalDisksDir(), "disk.zip"));
                    } catch (Throwable ignored) {
                    }
                    mSelectedDf0 = null;
                    mSelectedDf0Path = null;
                    mDf0SourceName = null;
                    getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .remove(UaeOptionKeys.UAE_DRIVE_DF0_PATH)
                        .apply();
                    saveSourceNames();
                    refreshStatus();
                });

                // Show all DF drives in the popup. If a drive isn't added yet, offer an Add button.
                if (mDf1Added) {
                    addMediaSwapperRowWithClear(
                        root,
                        "DF1: " + df1SwapLabel,
                        () -> pickDisk(REQ_IMPORT_DF1),
                        "Remove",
                        () -> removeDfCompletelyFromUaePrefs(1)
                    );
                } else {
                    addMediaSwapperRow(root, "DF1: (disabled)", () -> {
                        mDf1Added = true;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF1, true).apply();
                        refreshStatus();
                        try {
                            if (mMediaSwapperDialog != null) {
                                try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                            }
                        } catch (Throwable ignored) {
                        }
                        pickDisk(REQ_IMPORT_DF1);
                    });
                }

                if (mDf2Added) {
                    addMediaSwapperRowWithClear(
                        root,
                        "DF2: " + df2SwapLabel,
                        () -> pickDisk(REQ_IMPORT_DF2),
                        "Remove",
                        () -> removeDfCompletelyFromUaePrefs(2)
                    );
                } else {
                    addMediaSwapperRow(root, "DF2: (disabled)", () -> {
                        mDf2Added = true;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF2, true).apply();
                        refreshStatus();
                        try {
                            if (mMediaSwapperDialog != null) {
                                try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                            }
                        } catch (Throwable ignored) {
                        }
                        pickDisk(REQ_IMPORT_DF2);
                    });
                }

                if (mDf3Added) {
                    addMediaSwapperRowWithClear(
                        root,
                        "DF3: " + df3SwapLabel,
                        () -> pickDisk(REQ_IMPORT_DF3),
                        "Remove",
                        () -> removeDfCompletelyFromUaePrefs(3)
                    );
                } else {
                    addMediaSwapperRow(root, "DF3: (disabled)", () -> {
                        mDf3Added = true;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF3, true).apply();
                        refreshStatus();
                        try {
                            if (mMediaSwapperDialog != null) {
                                try { mMediaSwapperDialog.dismiss(); } catch (Throwable ignored) { }
                            }
                        } catch (Throwable ignored) {
                        }
                        pickDisk(REQ_IMPORT_DF3);
                    });
                }
            }

            if (showCd && (cdOnly || mCd0Added)) {
                String cd0 = getUaePrefString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);
                addMediaSwapperRow(root, "CD0: " + shortLabelForPath(cd0), this::pickCdImage0);
            }

            if (!cdOnly && showHd) {
                SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                if (mDh0Added) {
                    String dh0Sig = dhSignatureFromUaePrefs(p, 0);
                    boolean dh0IsDir = dh0Sig.startsWith("DIR:");
                    String dh0Label;
                    if (dh0Sig.isEmpty()) {
                        dh0Label = "DH0: (not set)";
                    } else {
                        String path = dh0Sig.substring(4);
                        dh0Label = "DH0 (" + (dh0IsDir ? "Folder" : "HDF") + "): " + shortLabelForPath(path);
                    }
                    addMediaSwapperRowWithClear(
                        root,
                        dh0Label,
                        () -> {
                            if (dh0IsDir) pickDh0Folder(); else pickDh0Hdf();
                        },
                        "Clear",
                        this::clearDh0FromUaePrefs
                    );
                }

                String dh1Sig = dhSignatureFromUaePrefs(p, 1);
                boolean dh1IsDir = dh1Sig.startsWith("DIR:");
                if (mDh1Added) {
                    String dh1Label;
                    if (dh1Sig.isEmpty()) {
                        dh1Label = "DH1: (not set)";
                    } else {
                        String path = dh1Sig.substring(4);
                        dh1Label = "DH1 (" + (dh1IsDir ? "Folder" : "HDF") + "): " + shortLabelForPath(path);
                    }
                    addMediaSwapperRowWithClear(
                        root,
                        dh1Label,
                        () -> {
                            if (dh1IsDir) pickDh1Folder(); else pickDh1Hdf();
                        },
                        "Remove",
                        this::removeDh1CompletelyFromUaePrefs
                    );
                }

                String dh2Sig = dhSignatureFromUaePrefs(p, 2);
                boolean dh2IsDir = dh2Sig.startsWith("DIR:");
                if (mDh2Added) {
                    String label;
                    if (dh2Sig.isEmpty()) {
                        label = "DH2: (not set)";
                    } else {
                        String path = dh2Sig.substring(4);
                        label = "DH2 (" + (dh2IsDir ? "Folder" : "HDF") + "): " + shortLabelForPath(path);
                    }
                    addMediaSwapperRowWithClear(
                        root,
                        label,
                        () -> {
                            if (dh2IsDir) pickDh2Folder(); else pickDh2Hdf();
                        },
                        "Remove",
                        () -> removeDhCompletelyFromUaePrefs(2)
                    );
                }

                String dh3Sig = dhSignatureFromUaePrefs(p, 3);
                boolean dh3IsDir = dh3Sig.startsWith("DIR:");
                if (mDh3Added) {
                    String label;
                    if (dh3Sig.isEmpty()) {
                        label = "DH3: (not set)";
                    } else {
                        String path = dh3Sig.substring(4);
                        label = "DH3 (" + (dh3IsDir ? "Folder" : "HDF") + "): " + shortLabelForPath(path);
                    }
                    addMediaSwapperRowWithClear(
                        root,
                        label,
                        () -> {
                            if (dh3IsDir) pickDh3Folder(); else pickDh3Hdf();
                        },
                        "Remove",
                        () -> removeDhCompletelyFromUaePrefs(3)
                    );
                }

                String dh4Sig = dhSignatureFromUaePrefs(p, 4);
                boolean dh4IsDir = dh4Sig.startsWith("DIR:");
                if (mDh4Added) {
                    String label;
                    if (dh4Sig.isEmpty()) {
                        label = "DH4: (not set)";
                    } else {
                        String path = dh4Sig.substring(4);
                        label = "DH4 (" + (dh4IsDir ? "Folder" : "HDF") + "): " + shortLabelForPath(path);
                    }
                    addMediaSwapperRowWithClear(
                        root,
                        label,
                        () -> {
                            if (dh4IsDir) pickDh4Folder(); else pickDh4Hdf();
                        },
                        "Remove",
                        () -> removeDhCompletelyFromUaePrefs(4)
                    );
                }
            }

            if (mLaunchedFromEmulatorMenu) {
                try {
                    View sep = new View(this);
                    sep.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * getResources().getDisplayMetrics().density)));
                    sep.setBackgroundColor(0xFF444444);
                    sep.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
                    root.addView(sep);
                } catch (Throwable ignored) {
                }

                try {
                    Button quit = new Button(this);
                    quit.setText("Quit");
                    quit.setOnClickListener(v -> {
                        try {
                            new AlertDialog.Builder(this)
                                .setTitle("Quit")
                                .setMessage("Exit the emulator?")
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton("Quit", (d, w) -> {
                                    try { EmulatorProcessControl.requestEmulatorProcessExit(this); } catch (Throwable ignored) { }
                                    try { finish(); } catch (Throwable ignored) { }
                                })
                                .show();
                        } catch (Throwable ignored) {
                            try { EmulatorProcessControl.requestEmulatorProcessExit(this); } catch (Throwable ignored2) { }
                            try { finish(); } catch (Throwable ignored2) { }
                        }
                    });
                    root.addView(quit);
                } catch (Throwable ignored) {
                }
            }

            // Wrap in ScrollView so the popup is usable on smaller screens.
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(root);

            String title = "Media";
            if (MEDIA_SECTION_DF.equals(section)) title = "DF Media";
            else if (MEDIA_SECTION_HD.equals(section)) title = "HD Media / Folders";
            else if (MEDIA_SECTION_CD.equals(section)) title = "CDROM Media";

            AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll);

            // In overlay/popup mode we want a simple "Cancel" (resume without change)
            // and "Resume" (restart emulator if media changed).
            if (mLaunchedFromEmulatorMenu) {
                b.setNegativeButton(android.R.string.cancel, (d, w) -> {
                    try { finish(); } catch (Throwable ignored) { }
                });

                // "Reset" reboots the emulator using the same model, with the currently
                // selected media from UAE prefs (useful for quickly switching games).
                b.setNeutralButton("Reset", (d, w) -> {
                    try {
                        requestEmulatorRestartAndFinish();
                    } catch (Throwable ignored) {
                        try { finish(); } catch (Throwable ignored2) { }
                    }
                });

                b.setPositiveButton("Resume", (d, w) -> {
                    try {
                        int action = getPendingResumeActionFromPrefs();
                        if (action == 2) {
                            requestEmulatorRestartAndFinish();
                        } else if (action == 1) {
                            requestHotSwapToRunningEmulatorAndFinish();
                        } else {
                            finish();
                        }
                    } catch (Throwable ignored) {
                        try { finish(); } catch (Throwable ignored2) { }
                    }
                });
            } else {
                b.setNegativeButton(android.R.string.cancel, null);
                b.setPositiveButton("Start", (d, w) -> {
                    try {
                        startEmulator();
                    } catch (Throwable ignored) {
                    }
                });
            }

            mMediaSwapperDialog = b.create();
            mMediaSwapperDialog.show();
        } catch (Throwable ignored) {
        }
    }

    private void ensureCd0AndPick() {
        try {
            mCd0Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_CD0, true).apply();
            refreshStatus();
        } catch (Throwable ignored) {
        }
        pickCdImage0();
    }

    private void chooseDhSlotAndPickHdf() {
        chooseDhSlotAndPick(false);
    }

    private void chooseDhSlotAndPick(boolean folder) {
        chooseDhSlotAndPick(folder, null);
    }

    private void chooseDhSlotAndPick(boolean folder, String hdfMountMode) {
        if (isCdOnlyModel()) {
            Toast.makeText(this, "This model doesn't use hard drives.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<DhSlot> slots = new ArrayList<>();
        if (!mDh0Added) slots.add(new DhSlot(0, "DH0"));
        if (!mDh1Added) slots.add(new DhSlot(1, "DH1"));
        if (!mDh2Added) slots.add(new DhSlot(2, "DH2"));
        if (!mDh3Added) slots.add(new DhSlot(3, "DH3"));
        if (!mDh4Added) slots.add(new DhSlot(4, "DH4"));

        if (slots.isEmpty()) {
            Toast.makeText(this, "No free DH slots available.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (slots.size() == 1) {
            enableDhSlotAndPick(slots.get(0).index, folder);
            return;
        }

        CharSequence[] labels = new CharSequence[slots.size()];
        for (int i = 0; i < slots.size(); i++) labels[i] = slots.get(i).label;
        new AlertDialog.Builder(this)
            .setTitle("Select hard drive slot")
            .setItems(labels, (d, which) -> {
                if (which >= 0 && which < slots.size()) {
                    enableDhSlotAndPick(slots.get(which).index, folder);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void enableDhSlotAndPick(int dhIndex, boolean folder) {
        try {
            SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            if (dhIndex == 0) {
                mDh0Added = true;
                e.putBoolean(PREF_SHOW_DH0, true);
                e.putString(PREF_DH0_MODE, folder ? DH0_MODE_DIR : DH0_MODE_HDF);
                e.apply();
                refreshStatus();
                if (folder) pickDh0Folder(); else pickDh0Hdf();
                return;
            }

            if (dhIndex == 1) {
                mDh1Added = true;
                e.putBoolean(PREF_SHOW_DH1, true);
                e.putString(PREF_DH1_MODE, folder ? DH1_MODE_DIR : DH1_MODE_HDF);
                e.apply();
                refreshStatus();
                if (folder) pickDh1Folder(); else pickDh1Hdf();
                return;
            }

            if (dhIndex == 2) {
                mDh2Added = true;
                e.putBoolean(PREF_SHOW_DH2, true).apply();
                refreshStatus();
                if (folder) pickDh2Folder(); else pickDh2Hdf();
                return;
            }
            if (dhIndex == 3) {
                mDh3Added = true;
                e.putBoolean(PREF_SHOW_DH3, true).apply();
                refreshStatus();
                if (folder) pickDh3Folder(); else pickDh3Hdf();
                return;
            }
            if (dhIndex == 4) {
                mDh4Added = true;
                e.putBoolean(PREF_SHOW_DH4, true).apply();
                refreshStatus();
                if (folder) pickDh4Folder(); else pickDh4Hdf();
            }
        } catch (Throwable ignored) {
            // Fallback: use existing pickers.
            if (dhIndex == 0) {
                if (folder) pickDh0Folder(); else pickDh0Hdf();
            } else if (dhIndex == 1) {
                if (folder) pickDh1Folder(); else pickDh1Hdf();
            } else if (dhIndex == 2) {
                if (folder) pickDh2Folder(); else pickDh2Hdf();
            } else if (dhIndex == 3) {
                if (folder) pickDh3Folder(); else pickDh3Hdf();
            } else if (dhIndex == 4) {
                if (folder) pickDh4Folder(); else pickDh4Hdf();
            }
        }
    }

    private void clearConnectedMediaForColdLauncherStart() {
        if (mLaunchedFromEmulatorMenu) return;
        try {
            // Reset launcher-visible media sections to the minimal first-run UI.
            mDf1Added = false;
            mDf2Added = false;
            mDf3Added = false;
            mDh0Added = false;
            mDh1Added = false;
            mDh2Added = false;
            mDh3Added = false;
            mDh4Added = false;
            mCd0Added = false;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SHOW_DF1, false)
                .putBoolean(PREF_SHOW_DF2, false)
                .putBoolean(PREF_SHOW_DF3, false)
                .putBoolean(PREF_SHOW_DH0, false)
                .putBoolean(PREF_SHOW_DH1, false)
                .putBoolean(PREF_SHOW_DH2, false)
                .putBoolean(PREF_SHOW_DH3, false)
                .putBoolean(PREF_SHOW_DH4, false)
                .putBoolean(PREF_SHOW_CD0, false)
                .apply();

            // Clear mounted media paths from UAE prefs (but do NOT delete the files).
            getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(UaeOptionKeys.UAE_DRIVE_DF0_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DF1_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DF2_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DF3_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false)
                .remove(UaeOptionKeys.UAE_DRIVE_HDF0_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DIR0_PATH)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false)
                .remove(UaeOptionKeys.UAE_DRIVE_HDF1_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DIR1_PATH)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED, false)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED, false)
                .remove(UaeOptionKeys.UAE_DRIVE_HDF2_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DIR2_PATH)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED, false)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED, false)
                .remove(UaeOptionKeys.UAE_DRIVE_HDF3_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DIR3_PATH)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED, false)
                .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED, false)
                .remove(UaeOptionKeys.UAE_DRIVE_HDF4_PATH)
                .remove(UaeOptionKeys.UAE_DRIVE_DIR4_PATH)
                .apply();

            // Clear WHDBooter persisted game/settings so every fresh app start begins clean.
            getSharedPreferences("whdbooter", MODE_PRIVATE)
                .edit()
                .clear()
                .apply();

            // Clear in-memory selections so the UI doesn't show stale labels.
            mSelectedDf0 = null;
            mSelectedDf0Path = null;
            mDf0SourceName = null;
            mSelectedDf1 = null;
            mSelectedDf1Path = null;
            mDf1SourceName = null;
            mSelectedDf2 = null;
            mSelectedDf2Path = null;
            mDf2SourceName = null;
            mSelectedDf3 = null;
            mSelectedDf3Path = null;
            mDf3SourceName = null;
            mSelectedCd0 = null;
            mSelectedCd0Path = null;
            mCd0SourceName = null;
            mSelectedDh0Hdf = null;
            mSelectedDh0HdfPath = null;
            mSelectedDh0Dir = null;
            mDh0SourceName = null;
            mSelectedDh1Hdf = null;
            mSelectedDh1HdfPath = null;
            mSelectedDh1Dir = null;
            mDh1SourceName = null;
            mSelectedDh2Hdf = null;
            mSelectedDh2HdfPath = null;
            mSelectedDh2Dir = null;
            mDh2SourceName = null;
            mSelectedDh3Hdf = null;
            mSelectedDh3HdfPath = null;
            mSelectedDh3Dir = null;
            mDh3SourceName = null;
            mSelectedDh4Hdf = null;
            mSelectedDh4HdfPath = null;
            mSelectedDh4Dir = null;
            mDh4SourceName = null;

            saveSourceNames();
        } catch (Throwable ignored) {
        }
    }

    private void disableDirectMediaEditingControls() {
        int[] ids = new int[] {
            R.id.btnAddDf1,
            R.id.btnAddDh0,
            R.id.btnAddCd0,

            R.id.btnPickDf0,
            R.id.btnClearDf0,
            R.id.btnPickDf1,
            R.id.btnClearDf1,

            R.id.btnPickCd0,
            R.id.btnClearCd0,
        };

        for (int id : ids) {
            try {
                View v = findViewById(id);
                if (v != null) v.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String normalizeMediaPath(String path) {
        if (path == null) return "";
        return path.trim();
    }

    private String floppySignatureFromPath(String path) {
        String p = normalizeMediaPath(path);
        if (p.isEmpty()) return "";
        if (isContentUriString(p)) return "URI:" + p;
        try {
            File f = new File(p);
            if (f.exists() && f.isFile()) {
                return p + "|" + f.length() + "|" + f.lastModified();
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    private boolean shouldRestartForDf0Change() {
        if (!mLaunchedFromEmulatorMenu) return false;
        try {
            SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            if (isCdOnlyModel()) {
                String now = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, ""));
                String before = normalizeMediaPath(mCd0PathWhenOpenedFromEmu);
                boolean changed = !now.equals(before);
                if (changed) {
                    android.util.Log.i("BootstrapActivity", "CD0 changed while paused: '" + before + "' -> '" + now + "' (will restart)");
                } else {
                    android.util.Log.i("BootstrapActivity", "CD0 unchanged while paused (resume without restart): '" + now + "'");
                }
                return changed;
            }

            String now = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, ""));
            String before = normalizeMediaPath(mDf0PathWhenOpenedFromEmu);
            String sigNow = floppySignatureFromPath(now);
            String sigBefore = (mDf0SigWhenOpenedFromEmu == null) ? floppySignatureFromPath(before) : mDf0SigWhenOpenedFromEmu;
            boolean changed = !sigNow.equals(sigBefore);
            if (changed) {
                android.util.Log.i("BootstrapActivity", "DF0 changed while paused: '" + before + "' -> '" + now + "' (will restart)");
            } else {
                android.util.Log.i("BootstrapActivity", "DF0 unchanged while paused (resume without restart): '" + now + "'");
            }
            return changed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestHotSwapToRunningEmulatorAndFinish() {
        try {
            sendHotSwapIntentToEmulator();
        } catch (Throwable ignored) {
        }

        try { finish(); } catch (Throwable ignored) { }
    }

    private void sendHotSwapIntentToEmulator() {
        // Read the latest paths from UAE prefs (these represent the swapper selection).
        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
        final boolean cdOnly = isCdOnlyModel();

        Intent i = new Intent(this, AmiberryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        if (!cdOnly) {
            UaeDrivePrefs.FloppyPaths df = UaeDrivePrefs.readFloppyPaths(p);

            // Do not over-validate here; the emulator will do its own checks.
            // Always include the extras so empty strings can represent an eject.
            UaeDrivePrefs.putHotSwapExtras(i, df);
        }

        // Bring the emulator to the front and deliver onNewIntent().
        startActivity(i);
    }

    private void requestEmulatorRestartAndFinish() {
        android.util.Log.i("BootstrapActivity", "Requesting emulator restart (kill :emu process and relaunch)");

        // In swapper-only mode (or other reduced UIs), spinners may not be initialized.
        // Fall back to last-selected launcher model preference.
        String qsModel = null;
        try {
            qsModel = getSelectedQsModelId();
        } catch (Throwable ignored) {
        }
        if (qsModel == null || qsModel.trim().isEmpty()) {
            try {
                qsModel = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_QS_MODEL, "A500");
            } catch (Throwable ignored) {
                qsModel = "A500";
            }
        }

        final boolean isCdOnly = isCdOnlyModel();
        final String qsModelFinal = qsModel;
        final boolean isCdOnlyFinal = isCdOnly;

        // IMPORTANT: when restarting due to a disk swap while paused, force the
        // current DF0-DF3 from UAE prefs so the emulator cannot fall back to the
        // original config media on reboot.
        BootstrapRestartController.requestRestartAndRelaunch(this, () -> {
            try {
                launchEmulatorFromUaeDrivePrefs(qsModelFinal, isCdOnlyFinal);
            } catch (Throwable ignored) {
            }
        });
    }

    private void launchEmulatorFromUaeDrivePrefs(String qsModel, boolean isCdOnly) {
        Intent i = buildEmulatorIntent(qsModel, isCdOnly, /*includeFloppyExtras*/ false);

        // Mark this as a restart request so AmiberryActivity.onNewIntent() can handle it properly
        // (the emulator process may not have fully exited yet, so the intent could arrive via onNewIntent)
        i.putExtra(AmiberryActivity.EXTRA_REQUEST_RESTART, true);

        // Force DF0-DF3 from prefs (these represent the swapper selection).
        // Always include extras so empty strings can explicitly mean "eject".
        // This intentionally bypasses mSelectedDf* which can be stale.
        if (!isCdOnly) {
            try {
                SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                UaeDrivePrefs.FloppyPaths df = UaeDrivePrefs.readFloppyPaths(p);
                String df0 = df.df0;
                String df1 = df.df1;
                String df2 = df.df2;
                String df3 = df.df3;

                // Prefer the most recently picked files (from this activity instance) over prefs.
                // This avoids any edge-case where prefs are stale or overwritten.
                try {
                    if (mSelectedDf0 != null && mSelectedDf0.exists() && mSelectedDf0.length() > 0) {
                        df0 = mSelectedDf0.getAbsolutePath();
                    } else if (mSelectedDf0Path != null && !mSelectedDf0Path.trim().isEmpty() && isReadableMediaPath(mSelectedDf0Path)) {
                        df0 = mSelectedDf0Path.trim();
                    } else {
                        File latest = findByPrefix(getInternalDisksDir(), "df0");
                        if (latest != null && latest.exists() && latest.length() > 0) {
                            df0 = latest.getAbsolutePath();
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    if (mSelectedDf1 != null && mSelectedDf1.exists() && mSelectedDf1.length() > 0) {
                        df1 = mSelectedDf1.getAbsolutePath();
                    } else if (mSelectedDf1Path != null && !mSelectedDf1Path.trim().isEmpty() && isReadableMediaPath(mSelectedDf1Path)) {
                        df1 = mSelectedDf1Path.trim();
                    } else {
                        File latest = findByPrefix(getInternalDisksDir(), "df1");
                        if (latest != null && latest.exists() && latest.length() > 0) {
                            df1 = latest.getAbsolutePath();
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    if (mSelectedDf2 != null && mSelectedDf2.exists() && mSelectedDf2.length() > 0) {
                        df2 = mSelectedDf2.getAbsolutePath();
                    } else if (mSelectedDf2Path != null && !mSelectedDf2Path.trim().isEmpty() && isReadableMediaPath(mSelectedDf2Path)) {
                        df2 = mSelectedDf2Path.trim();
                    } else {
                        File latest = findByPrefix(getInternalDisksDir(), "df2");
                        if (latest != null && latest.exists() && latest.length() > 0) {
                            df2 = latest.getAbsolutePath();
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    if (mSelectedDf3 != null && mSelectedDf3.exists() && mSelectedDf3.length() > 0) {
                        df3 = mSelectedDf3.getAbsolutePath();
                    } else if (mSelectedDf3Path != null && !mSelectedDf3Path.trim().isEmpty() && isReadableMediaPath(mSelectedDf3Path)) {
                        df3 = mSelectedDf3Path.trim();
                    } else {
                        File latest = findByPrefix(getInternalDisksDir(), "df3");
                        if (latest != null && latest.exists() && latest.length() > 0) {
                            df3 = latest.getAbsolutePath();
                        }
                    }
                } catch (Throwable ignored) {
                }

                i.putExtra(AmiberryActivity.EXTRA_DF0_DISK_FILE, df0);
                if (mDf0SourceName != null && !mDf0SourceName.trim().isEmpty()) {
                    i.putExtra(AmiberryActivity.EXTRA_DF0_SOURCE_NAME, mDf0SourceName.trim());
                }
                i.putExtra(AmiberryActivity.EXTRA_DF1_DISK_FILE, df1);
                i.putExtra(AmiberryActivity.EXTRA_DF2_DISK_FILE, df2);
                i.putExtra(AmiberryActivity.EXTRA_DF3_DISK_FILE, df3);

                android.util.Log.i(TAG, "Restart launch forced DF0='" + df0 + "' (" + describeFilePath(df0) + ")");
                android.util.Log.i(TAG, "Restart launch forced DF1='" + df1 + "'");
                android.util.Log.i(TAG, "Restart launch forced DF2='" + df2 + "'");
                android.util.Log.i(TAG, "Restart launch forced DF3='" + df3 + "'");
            } catch (Throwable ignored) {
            }
        }

        startActivity(i);
        finish();
    }

    private boolean mSuppressUiCallbacks;

    private File mSelectedKick;
    private File mSelectedExt;
    private File mSelectedCd0;
    private File mSelectedDf0;
    private File mSelectedDf1;
    private File mSelectedDf2;
    private File mSelectedDf3;
    private File mSelectedDh0Hdf;
    private File mSelectedDh0Dir;
    private File mSelectedDh1Hdf;
    private File mSelectedDh1Dir;
    private File mSelectedDh2Hdf;
    private File mSelectedDh2Dir;
    private File mSelectedDh3Hdf;
    private File mSelectedDh3Dir;
    private File mSelectedDh4Hdf;
    private File mSelectedDh4Dir;

    // When media is selected via SAF, we keep it in-place and store a content:// URI.
    // File-based members above will be null for SAF selections.
    private String mSelectedCd0Path;
    private String mSelectedDf0Path;
    private String mSelectedDf1Path;
    private String mSelectedDf2Path;
    private String mSelectedDf3Path;
    private String mSelectedDh0HdfPath;
    private String mSelectedDh1HdfPath;
    private String mSelectedDh2HdfPath;
    private String mSelectedDh3HdfPath;
    private String mSelectedDh4HdfPath;

    private String mKickSourceName;
    private String mExtSourceName;
    private String mCd0SourceName;
    private String mDf0SourceName;
    private String mDf1SourceName;
    private String mDf2SourceName;
    private String mDf3SourceName;
    private String mDh0SourceName;
    private String mDh1SourceName;
    private String mDh2SourceName;
    private String mDh3SourceName;
    private String mDh4SourceName;

    // Avoid duplicate prompts (persisted across activity recreations).
    private boolean mPathsParentPromptShown = false;
    private boolean mRequiredPathsPromptShown = false;
    private boolean mHdfPromptShown = false;
    private static final String PREF_PATHS_PARENT_PROMPT_SHOWN = "paths_parent_prompt_shown";
    private static final String PREF_REQUIRED_PATHS_PROMPT_SHOWN = "required_paths_prompt_shown";

    private static boolean looksLikeBlockedExternalStoragePath(String p) {
        if (p == null) return false;
        String s = p.trim();
        if (s.isEmpty()) return false;
        // Common raw external storage paths that are often blocked under scoped storage.
        return s.startsWith("/storage/") || s.startsWith("/sdcard/");
    }

    private void openPathsAndAutoPickSafParent() {
        try {
            Intent i = new Intent(this, PathsSimpleActivity.class);
            // Explicitly guide users to re-grant the SAF parent tree when permissions are missing.
            i.putExtra(PathsSimpleActivity.EXTRA_AUTO_PICK, "parent");
            startActivity(i);
        } catch (Throwable t) {
            startActivity(new Intent(this, PathsSimpleActivity.class));
        }
    }

    private void openPathsAndAutoPick(String which) {
        try {
            Intent i = new Intent(this, PathsSimpleActivity.class);
            if (which != null && !which.trim().isEmpty()) {
                i.putExtra(PathsSimpleActivity.EXTRA_AUTO_PICK, which.trim());
            }
            startActivity(i);
        } catch (Throwable t) {
            startActivity(new Intent(this, PathsSimpleActivity.class));
        }
    }

    private void maybePromptForBlockedHdf0Path() {
        if (mHdfPromptShown) return;
        try {
            SharedPreferences launcherPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String dh0Mode = launcherPrefs.getString(PREF_DH0_MODE, DH0_MODE_HDF);
            if (!DH0_MODE_HDF.equalsIgnoreCase(dh0Mode)) return;

            SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            boolean enabled = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
            String path = uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, null);
            if (!enabled) return;
            if (path == null || path.trim().isEmpty()) return;

            String p = path.trim();
            if (isContentUriString(p)) {
                // SAF URIs are handled by existing prompts (missing persisted permissions).
                return;
            }
            if (!looksLikeBlockedExternalStoragePath(p)) return;

            // If it's readable, no prompt.
            if (isReadableMediaPath(p)) return;

            // On some devices this is the #1 reason HDF "worked on one device, not on another".
            // Prompt early so the user can grant access via SAF and keep the selection persisted.
            mHdfPromptShown = true;
            new AlertDialog.Builder(this)
                .setTitle("HDF access required")
                .setMessage(
                    "This device blocks direct access to external storage paths like:\n\n" + p + "\n\n" +
                        "Re-select the HDF so Android can grant access (recommended), or select a SAF parent folder in Paths.")
                .setPositiveButton("Select HDF", (d, w) -> pickDh0Hdf())
                .setNeutralButton("Paths", (d, w) -> openPathsAndAutoPickSafParent())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } catch (Throwable ignored) {
        }
    }

    private File mPendingCue;
    private java.util.List<String> mPendingCueTracks;

    private static final String PREF_QS_MODEL = "qs_model";
    private static final String PREF_QS_CONFIG = "qs_config";
    // Legacy prefs from the old RTG/NTSC UI (kept for migration only).
    private static final String PREF_QS_NTSC = "qs_ntsc";
    private static final String PREF_QS_MODE = "qs_mode";
    private static final String PREF_QS_RTG_ENABLED = "qs_rtg_enabled";
    private static final String PREF_QS_RTG_MODEL = "qs_rtg_model";
    private static final String PREF_QS_RTG_JIT = "qs_rtg_jit";

    // New: kickstart mapping per base model.
    private static final String PREF_KICK_MAP_PREFIX = "kick_map_";
    private static final String PREF_EXT_MAP_PREFIX = "ext_map_";

    private static int getDefaultZ3FastMbForRtg() {
        return 256;
    }

    private static final int RTG_MODE_OFF = 0;
    private static final int RTG_MODE_ON = 1;
    private static final int RTG_MODE_JIT = 2;
    private static final int RTG_VARIANTS_PER_CONFIG = 3;

    private static int encodeConfigWithRtg(int baseConfigIndex, int rtgMode) {
        int b = baseConfigIndex & 0xFF;
        int m = (rtgMode & 0x3) << 8;
        return b | m;
    }

    private static int decodeBaseConfigIndex(int encoded) {
        return encoded & 0xFF;
    }

    private static int decodeRtgMode(int encoded) {
        return (encoded >> 8) & 0x3;
    }

    private static boolean supportsRtgForModelId(String cliId) {
        if (cliId == null) return false;
        String m = cliId.trim().toUpperCase(Locale.ROOT);
        return "A1200".equals(m) || "A3000".equals(m) || "A4000".equals(m);
    }

    private static final String PREFS_NAME = "bootstrap";
    private static final String PREF_WALKTHROUGH_COMPLETED = "walkthrough_completed";
    private static final String PREF_LAST_APP_VERSION_CODE = "last_app_version_code";
    private static final String PREF_LAST_APP_UPDATE_TIME = "last_app_update_time";
    private static final String PREF_KICK_SRC = "kick_src";
    private static final String PREF_EXT_SRC = "ext_src";
    private static final String PREF_CD0_SRC = "cd0_src";
    private static final String PREF_DF0_SRC = "df0_src";
    private static final String PREF_DF1_SRC = "df1_src";
    private static final String PREF_DF2_SRC = "df2_src";
    private static final String PREF_DF3_SRC = "df3_src";
    private static final String PREF_DH0_SRC = "dh0_src";
    private static final String PREF_DH1_SRC = "dh1_src";
    private static final String PREF_DH2_SRC = "dh2_src";
    private static final String PREF_DH3_SRC = "dh3_src";
    private static final String PREF_DH4_SRC = "dh4_src";

    private static final String PREF_SHOW_DF1 = "show_df1";
    private static final String PREF_SHOW_DF2 = "show_df2";
    private static final String PREF_SHOW_DF3 = "show_df3";
    private static final String PREF_SHOW_DH1 = "show_dh1";
    private static final String PREF_SHOW_DH2 = "show_dh2";
    private static final String PREF_SHOW_DH3 = "show_dh3";
    private static final String PREF_SHOW_DH4 = "show_dh4";
    private static final String PREF_SHOW_DH0 = "show_dh0";
    private static final String PREF_SHOW_CD0 = "show_cd0";

    private String mLastSelectedModelCliId;

    private String mPendingMapModelId;
    private boolean mPendingMapIsExt;

    // Boot is automatic: if DF0 has a disk, it will boot floppy; otherwise HD/CD if available.

    private static final String PREF_DH0_MODE = "dh0_mode";
    private static final String DH0_MODE_HDF = "hdf";
    private static final String DH0_MODE_DIR = "dir";

    private static final String PREF_DH1_MODE = "dh1_mode";
    private static final String DH1_MODE_HDF = "hdf";
    private static final String DH1_MODE_DIR = "dir";

    // Mirrors the Quickstart panel model/config labels (see src/osdep/gui/PanelQuickstart.cpp).
    // Note: CLI supports: A1000, A500, A500P, A600, A2000, A3000, A1200, A4000, CD32, CDTV.
    private static final QsModel[] QS_MODELS = new QsModel[]{
        new QsModel("A500", "A500", "Amiga 500", new String[]{
            "1.3 ROM, OCS, 512 KB Chip + 512 KB Slow RAM (most common)",
            "1.3 ROM, ECS Agnus, 512 KB Chip RAM + 512 KB Slow RAM",
            "1.3 ROM, ECS Agnus, 1 MB Chip RAM",
            "1.3 ROM, OCS Agnus, 512 KB Chip RAM",
            "1.2 ROM, OCS Agnus, 512 KB Chip RAM",
            "1.2 ROM, OCS Agnus, 512 KB Chip RAM + 512 KB Slow RAM"
        }, false, false, 0, 0),
        new QsModel("A500P", "A500P", "Amiga 500+", new String[]{
            "Basic non-expanded configuration",
            "2 MB Chip RAM expanded configuration",
            "4 MB Fast RAM expanded configuration"
        }, false, false, 0, 0),
        new QsModel("A600", "A600", "Amiga 600", new String[]{
            "Basic non-expanded configuration",
            "2 MB Chip RAM expanded configuration",
            "4 MB Fast RAM expanded configuration",
            "8 MB Fast RAM expanded configuration"
        }, false, false, 0, 0),
        new QsModel("A1200", "A1200", "Amiga 1200", new String[]{
            "Basic non-expanded configuration",
            "4 MB Fast RAM expanded configuration",
            "8 MB Fast RAM expanded configuration"
        }, false, false, 0, 0),
        new QsModel("A3000", "A3000", "Amiga 3000", new String[]{
            "1.4 ROM, 2MB Chip + 8MB Fast",
            "2.04 ROM, 2MB Chip + 8MB Fast",
            "3.1 ROM, 2MB Chip + 8MB Fast"
        }, false, false, 0, 0),
        new QsModel("A4000", "A4000", "Amiga 4000", new String[]{
            "68030, 3.1 ROM, 2MB Chip + 8MB Fast",
            "68040, 3.1 ROM, 2MB Chip + 8MB Fast"
        }, false, false, 0, 0),
        new QsModel("CD32", "CD32", "CD32", new String[]{
            "CD32",
            "CD32 with Full Motion Video cartridge",
            "Cubo CD32",
            "CD32, 8MB Fast"
        }, false, false, 0, 0),
        new QsModel("CDTV", "CDTV", "CDTV", new String[]{
            "CDTV",
            "Floppy drive and 64KB SRAM card expanded",
            "CDTV-CR"
        }, false, false, 0, 0),

    };

    private static int indexOfModelId(String cliId, int defIdx) {
        if (cliId == null) return defIdx;
        for (int i = 0; i < QS_MODELS.length; i++) {
            if (cliId.equalsIgnoreCase(QS_MODELS[i].prefsId)) return i;
        }
        return defIdx;
    }

    private QsModel getSelectedQsModel() {
        if (mQsModelSpinner == null) return null;
        int idx = mQsModelSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= QS_MODELS.length) return null;
        return QS_MODELS[idx];
    }

    private String getSelectedQsPrefsId() {
        QsModel m = getSelectedQsModel();
        return m != null ? m.prefsId : null;
    }

    private String getSelectedQsModelId() {
        if (mQsModelSpinner == null) return null;
        int idx = mQsModelSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= QS_MODELS.length) return null;
        return QS_MODELS[idx].cliId;
    }

    private String getSelectedQsModelLabel() {
        if (mQsModelSpinner == null) return null;
        int idx = mQsModelSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= QS_MODELS.length) return null;
        return QS_MODELS[idx].label;
    }

    private String getSelectedQsConfigLabel() {
        if (mQsConfigSpinner == null) return null;
        Object item = mQsConfigSpinner.getSelectedItem();
        return item != null ? String.valueOf(item) : null;
    }

    private int getSelectedBaseQsConfigIndex() {
        if (mQsModelSpinner == null || mQsConfigSpinner == null) return 0;
        int midx = mQsModelSpinner.getSelectedItemPosition();
        if (midx < 0 || midx >= QS_MODELS.length) return 0;
        int pos = mQsConfigSpinner.getSelectedItemPosition();
        String modelId = QS_MODELS[midx].cliId;
        if (supportsRtgForModelId(modelId)) {
            int base = pos / RTG_VARIANTS_PER_CONFIG;
            return Math.max(0, base);
        }
        return Math.max(0, pos);
    }

    private int getSelectedRtgMode() {
        if (mQsModelSpinner == null || mQsConfigSpinner == null) return RTG_MODE_OFF;
        int midx = mQsModelSpinner.getSelectedItemPosition();
        if (midx < 0 || midx >= QS_MODELS.length) return RTG_MODE_OFF;
        String modelId = QS_MODELS[midx].cliId;
        if (!supportsRtgForModelId(modelId)) return RTG_MODE_OFF;
        int pos = mQsConfigSpinner.getSelectedItemPosition();
        int mode = pos % RTG_VARIANTS_PER_CONFIG;
        return (mode == RTG_MODE_ON || mode == RTG_MODE_JIT) ? mode : RTG_MODE_OFF;
    }

    private void updateConfigSpinnerForModel(int modelIdx, int preferredConfigIdx) {
        if (mQsConfigSpinner == null) return;
        if (modelIdx < 0 || modelIdx >= QS_MODELS.length) modelIdx = 0;
        String[] baseConfigs = QS_MODELS[modelIdx].configs;
        if (baseConfigs == null) baseConfigs = new String[0];

        ArrayList<String> configs = new ArrayList<>();
        boolean supportsRtg = supportsRtgForModelId(QS_MODELS[modelIdx].cliId);
        if (supportsRtg) {
            for (String c : baseConfigs) {
                configs.add(c);
                configs.add(c + " (RTG)");
                configs.add(c + " (RTG + JIT)");
            }
        } else {
            for (String c : baseConfigs) {
                configs.add(c);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_qs_config_item, configs);
        adapter.setDropDownViewResource(R.layout.spinner_qs_config_dropdown_item);
        mQsConfigSpinner.setAdapter(adapter);

        try {
            mCurrentConfigOptions = new ArrayList<>(configs);
        } catch (Throwable ignored) {
            mCurrentConfigOptions = new ArrayList<>();
        }

        // Make the dropdown "popout" wide enough to read the full descriptions.
        // On many devices the dropdown otherwise matches the spinner column width.
        try {
            mQsConfigSpinner.post(() -> {
                try {
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int sidePad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
                    int desiredW = Math.max(1, screenW - sidePad * 2);
                    mQsConfigSpinner.setDropDownWidth(desiredW);

                    int[] loc = new int[2];
                    mQsConfigSpinner.getLocationOnScreen(loc);
                    int offset = -loc[0] + sidePad;
                    mQsConfigSpinner.setDropDownHorizontalOffset(offset);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }

        int baseIdx = decodeBaseConfigIndex(preferredConfigIdx);
        int rtgMode = decodeRtgMode(preferredConfigIdx);
        if (!supportsRtg) rtgMode = RTG_MODE_OFF;
        if (rtgMode != RTG_MODE_ON && rtgMode != RTG_MODE_JIT) rtgMode = RTG_MODE_OFF;

        int selectedPos = supportsRtg ? (baseIdx * RTG_VARIANTS_PER_CONFIG + rtgMode) : baseIdx;
        if (selectedPos < 0) selectedPos = 0;
        if (selectedPos >= configs.size()) selectedPos = 0;
        mQsConfigSpinner.setSelection(selectedPos);

        try {
            if (mQsConfigFull != null) {
                mQsConfigFull.setText(selectedPos >= 0 && selectedPos < configs.size() ? configs.get(selectedPos) : "");
            }
        } catch (Throwable ignored) {
        }
    }

    private void applySelectedConfigPosition(int position) {
        QsModel m = getSelectedQsModel();
        boolean supportsRtg = m != null && supportsRtgForModelId(m.cliId);
        int baseIdx = supportsRtg ? (position / RTG_VARIANTS_PER_CONFIG) : position;
        int rtgMode = supportsRtg ? (position % RTG_VARIANTS_PER_CONFIG) : RTG_MODE_OFF;
        int encoded = encodeConfigWithRtg(baseIdx, rtgMode);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_QS_CONFIG, encoded)
            .apply();
        syncQuickstartToUaePrefs();
        refreshStatus();
    }

    private void showConfigPickerDialog() {
        if (mQsConfigSpinner == null) return;
        if (mCurrentConfigOptions == null || mCurrentConfigOptions.isEmpty()) return;

        try {
            final int current = Math.max(0, mQsConfigSpinner.getSelectedItemPosition());
            AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            ArrayAdapter<String> a = new ArrayAdapter<>(
                b.getContext(),
                R.layout.dialog_qs_config_choice_item,
                mCurrentConfigOptions
            );

            b
                .setTitle("Config")
                .setSingleChoiceItems(a, current, (d, which) -> {
                    try {
                        mSuppressUiCallbacks = true;
                        mQsConfigSpinner.setSelection(which);
                        if (mQsConfigFull != null && which >= 0 && which < mCurrentConfigOptions.size()) {
                            mQsConfigFull.setText(mCurrentConfigOptions.get(which));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        mSuppressUiCallbacks = false;
                    }

                    applySelectedConfigPosition(which);
                    try { d.dismiss(); } catch (Throwable ignored) { }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } catch (Throwable ignored) {
        }
    }

    private File getAmiberryBaseDir() {
        File f = AppPaths.getBaseDir(this);
        ensureDir(f);
        return f;
    }

    private File getInternalRomsDir() {
        return new File(getAmiberryBaseDir(), "roms");
    }

    private File getInternalDisksDir() {
        return new File(getAmiberryBaseDir(), "disks");
    }

    private File getInternalCd0Dir() {
        return new File(getInternalDisksDir(), INTERNAL_CD0_DIR);
    }

    private File getInternalHarddrivesDir() {
        return new File(getAmiberryBaseDir(), INTERNAL_HARDDRIVES_DIR);
    }

    private File getInternalDh0Dir() {
        return new File(getInternalHarddrivesDir(), INTERNAL_DH0_DIR);
    }

    private File getInternalDh1Dir() {
        return new File(getInternalHarddrivesDir(), INTERNAL_DH1_DIR);
    }

    private File getInternalDh2Dir() {
        return new File(getInternalHarddrivesDir(), INTERNAL_DH2_DIR);
    }

    private File getInternalDh3Dir() {
        return new File(getInternalHarddrivesDir(), INTERNAL_DH3_DIR);
    }

    private File getInternalDh4Dir() {
        return new File(getInternalHarddrivesDir(), INTERNAL_DH4_DIR);
    }

    private void repairDh0PrefsFromInternalImportIfPossible(SharedPreferences launcherPrefs) {
        if (launcherPrefs == null) return;

        String dh0Mode = launcherPrefs.getString(PREF_DH0_MODE, DH0_MODE_HDF);
        if (!DH0_MODE_HDF.equalsIgnoreCase(dh0Mode)) return;

        SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
        boolean enabled = uaePrefs.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
        String path = uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, null);
        if (enabled && path != null && !path.trim().isEmpty()) return;

        File dir = getInternalHarddrivesDir();
        if (!dir.exists() || !dir.isDirectory()) return;

        final String prefix = (dhHdfPrefixForIndex(0) + "__").toLowerCase(Locale.ROOT);
        File[] candidates = dir.listFiles((d, name) -> {
            if (name == null) return false;
            String n = name.toLowerCase(Locale.ROOT);
            return n.startsWith(prefix) && n.endsWith(".hdf");
        });
        if (candidates == null || candidates.length != 1) return;

        File hdf = candidates[0];
        if (!hdf.exists() || !hdf.isFile() || hdf.length() <= 0) return;

        LogUtil.i(TAG, "Repairing DH0 prefs from internal import: " + hdf.getAbsolutePath());

        // Make the UI show DH0 again.
        launcherPrefs.edit().putBoolean(PREF_SHOW_DH0, true).apply();
        mDh0Added = true;

        // Re-enable DH0 in UAE prefs.
        uaePrefs.edit()
            .remove("uae_drive_hdf0_mount_mode")
            .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false)
            .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, true)
            .putString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, hdf.getAbsolutePath())
            .putString(UaeOptionKeys.UAE_DRIVE_HDF0_DEVNAME, "DH0")
            .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_READONLY, false)
            .apply();
    }

    private static String dhHdfPrefixForIndex(int dhIndex) {
        switch (dhIndex) {
            case 0:
                return INTERNAL_DH0_HDF_PREFIX;
            case 1:
                return INTERNAL_DH1_HDF_PREFIX;
            case 2:
                return INTERNAL_DH2_HDF_PREFIX;
            case 3:
                return INTERNAL_DH3_HDF_PREFIX;
            case 4:
                return INTERNAL_DH4_HDF_PREFIX;
        }
        return "dh" + dhIndex;
    }

    private File getInternalDhHdfFile(int dhIndex, String displayName) {
        File dir = getInternalHarddrivesDir();
        ensureDir(dir);
        String fallback = "dh" + dhIndex + ".hdf";
        String name = safeFilename(displayName, fallback);
        if (lowerExt(name).isEmpty()) {
            name = name + ".hdf";
        }
        return new File(dir, dhHdfPrefixForIndex(dhIndex) + "__" + name);
    }

    private void importDhHdfAsync(int dhIndex, Uri uri, boolean hasWrite) {
        if (uri == null) return;

        // Avoid copying huge HDFs into app-private storage; keep the SAF URI and mount in-place.
        // AmiberryActivity will translate content:// URIs to /proc/self/fd/<fd> and keep the fd open.
        String displayName = null;
        try {
            displayName = getDisplayName(uri);
        } catch (Throwable ignored) {
        }

        String srcName;
        try {
            srcName = (displayName == null || displayName.trim().isEmpty()) ? uri.toString() : displayName;
        } catch (Throwable ignored) {
            srcName = uri.toString();
        }

        final String uriString = uri.toString();
        LogUtil.i(TAG, "Using DH" + dhIndex + " HDF in-place (no copy): " + uriString);

        try {
            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();

            if (dhIndex == 0) {
                mSelectedDh0Dir = null;
                mSelectedDh0Hdf = null;
                mSelectedDh0HdfPath = uriString;
                mDh0SourceName = srcName;
                mDh0Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH0, true).apply();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_DH0_MODE, DH0_MODE_HDF).apply();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, true);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, uriString);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF0_DEVNAME, "DH0");
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_READONLY, !hasWrite);
            } else if (dhIndex == 1) {
                mSelectedDh1Dir = null;
                mSelectedDh1Hdf = null;
                mSelectedDh1HdfPath = uriString;
                mDh1SourceName = srcName;
                mDh1Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH1, true).putString(PREF_DH1_MODE, DH1_MODE_HDF).apply();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, true);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF1_PATH, uriString);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF1_DEVNAME, "DH1");
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_READONLY, !hasWrite);
            } else if (dhIndex == 2) {
                mSelectedDh2Dir = null;
                mSelectedDh2Hdf = null;
                mSelectedDh2HdfPath = uriString;
                mDh2SourceName = srcName;
                mDh2Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH2, true).apply();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED, false);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED, true);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF2_PATH, uriString);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF2_DEVNAME, "DH2");
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF2_READONLY, !hasWrite);
            } else if (dhIndex == 3) {
                mSelectedDh3Dir = null;
                mSelectedDh3Hdf = null;
                mSelectedDh3HdfPath = uriString;
                mDh3SourceName = srcName;
                mDh3Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH3, true).apply();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED, false);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED, true);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF3_PATH, uriString);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF3_DEVNAME, "DH3");
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF3_READONLY, !hasWrite);
            } else if (dhIndex == 4) {
                mSelectedDh4Dir = null;
                mSelectedDh4Hdf = null;
                mSelectedDh4HdfPath = uriString;
                mDh4SourceName = srcName;
                mDh4Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH4, true).apply();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED, false);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED, true);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF4_PATH, uriString);
                e.putString(UaeOptionKeys.UAE_DRIVE_HDF4_DEVNAME, "DH4");
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF4_READONLY, !hasWrite);
            }

            e.apply();
            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
        } catch (Throwable t) {
            LogUtil.i(TAG, "DH" + dhIndex + " apply failed", t);
            Toast.makeText(this, "Hardfile selection failed", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isCdOnlyModel() {
        String modelId = getSelectedQsModelId();
        if (modelId == null) return false;
        String m = modelId.trim().toUpperCase(Locale.ROOT);
        return "CD32".equals(m) || "CDTV".equals(m);
    }

    private String getDh0Mode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH0_MODE, DH0_MODE_HDF);
    }

    private String getDh1Mode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH1_MODE, DH1_MODE_HDF);
    }

    private void syncBootMediumToUaePrefs() {
        SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();

        // Quickstart should be authoritative. If the user previously imported a .uae/.cfg file,
        // its stored late overrides would otherwise win over our Quickstart settings (including JIT).
        e.remove(UaeOptionKeys.UAE_IMPORTED_CFG_OVERRIDES_JSON);
        boolean wantDh0 = mDh0Added;
        if (!wantDh0) {
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
        } else {
            String mode = getDh0Mode();
            if (DH0_MODE_DIR.equals(mode)) {
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
                boolean ok = mSelectedDh0Dir != null && mSelectedDh0Dir.exists() && mSelectedDh0Dir.isDirectory();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, ok);
                if (ok) {
                    e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, mSelectedDh0Dir.getAbsolutePath());
                    e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_DEVNAME, "DH0");
                    e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_VOLNAME, "DH0");
                    e.putInt(UaeOptionKeys.UAE_DRIVE_DIR0_BOOTPRI, 10);
                    e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_READONLY, false);
                }
            } else {
                // HDF mode
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false);
                String hdfPath = null;
                if (mSelectedDh0HdfPath != null && !mSelectedDh0HdfPath.trim().isEmpty()) {
                    hdfPath = mSelectedDh0HdfPath.trim();
                } else if (mSelectedDh0Hdf != null) {
                    hdfPath = mSelectedDh0Hdf.getAbsolutePath();
                }

                boolean ok = isReadableMediaPath(hdfPath);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, ok);
                if (ok) {
                    e.putString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, hdfPath);
                    e.putString(UaeOptionKeys.UAE_DRIVE_HDF0_DEVNAME, "DH0");
                    // SAF is not inherently read-only; honor persisted or actual write access.
                    boolean readOnly = false;
                    if (isContentUriString(hdfPath)) {
                        boolean hasWrite = hasPersistedWritePermission(hdfPath) || canWriteContentUri(hdfPath);
                        readOnly = !hasWrite;
                    }
                    e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_READONLY, readOnly);
                }
            }
        }

        // DH1 (optional)
        if (!mDh1Added) {
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false);
        } else {
            String dh1Mode = getDh1Mode();
            if (DH1_MODE_DIR.equals(dh1Mode)) {
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
                boolean ok = mSelectedDh1Dir != null && mSelectedDh1Dir.exists() && mSelectedDh1Dir.isDirectory();
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, ok);
                if (ok) {
                    e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, mSelectedDh1Dir.getAbsolutePath());
                    e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_DEVNAME, "DH1");
                    e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_VOLNAME, "DH1");
                    e.putInt(UaeOptionKeys.UAE_DRIVE_DIR1_BOOTPRI, -128);
                    e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_READONLY, false);
                }
            } else {
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, false);
                String hdfPath = null;
                if (mSelectedDh1HdfPath != null && !mSelectedDh1HdfPath.trim().isEmpty()) {
                    hdfPath = mSelectedDh1HdfPath.trim();
                } else if (mSelectedDh1Hdf != null) {
                    hdfPath = mSelectedDh1Hdf.getAbsolutePath();
                }
                boolean ok = isReadableMediaPath(hdfPath);
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, ok);
                if (ok) {
                    e.putString(UaeOptionKeys.UAE_DRIVE_HDF1_PATH, hdfPath);
                    e.putString(UaeOptionKeys.UAE_DRIVE_HDF1_DEVNAME, "DH1");
                    boolean readOnly = false;
                    if (isContentUriString(hdfPath)) {
                        boolean hasWrite = hasPersistedWritePermission(hdfPath) || canWriteContentUri(hdfPath);
                        readOnly = !hasWrite;
                    }
                    e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_READONLY, readOnly);
                }
            }
        }
        e.apply();
    }

    private void pickDh0Hdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH0_HDF);
    }

    private void pickDh0Folder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH0_DIR);
    }

    private void pickDh1Hdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH1_HDF);
    }

    private void pickDh1Folder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH1_DIR);
    }

    private void pickDh2Hdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH2_HDF);
    }

    private void pickDh2Folder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH2_DIR);
    }

    private void pickDh3Hdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH3_HDF);
    }

    private void pickDh3Folder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH3_DIR);
    }

    private void pickDh4Hdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH4_HDF);
    }

    private void pickDh4Folder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_DH4_DIR);
    }

    private void pickWHDLoadFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Try to guide to the configured LHA path or whdboot path if available
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_LHA_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_WHDLOAD);
    }

    private void pickCdFolderForMissingTracks() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_CDROMS_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_CDIMAGE0_DIR);
    }

    private void maybeSetInitialUriFromUaePathPref(Intent intent, String uaePathPrefKey) {
        if (intent == null || uaePathPrefKey == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String dir = resolveConfiguredPathForKeyWithParentFallback(uaePrefs, uaePathPrefKey);
            Uri uri = buildInitialUriFromConfiguredPath(dir);
            if (uri != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
            }
        } catch (Throwable ignored) {
        }
    }

    private Uri buildInitialUriFromConfiguredPath(String configuredPath) {
        if (configuredPath == null) return null;
        String p = configuredPath.trim();
        if (p.isEmpty()) return null;

        // SAF joined path: content://treeUri::/relpath
        if (ConfigStorage.isSafJoinedPath(p)) {
            try {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(p);
                if (sp == null || sp.treeUri == null) return null;
                DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri));
                DocumentFile cur = root;
                if (cur == null) return null;

                String rel = sp.relPath == null ? "" : sp.relPath.trim();
                if (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                if (!rel.isEmpty()) {
                    String[] parts = rel.split("/");
                    for (String part : parts) {
                        if (part == null) continue;
                        String seg = part.trim();
                        if (seg.isEmpty()) continue;
                        DocumentFile next = cur.findFile(seg);
                        if (next == null) {
                            // Some providers won't navigate to non-existent folders.
                            // Best-effort create the directory chain so EXTRA_INITIAL_URI can point to it.
                            try {
                                next = cur.createDirectory(seg);
                            } catch (Throwable ignored) {
                            }
                            if (next == null) {
                                return root != null ? root.getUri() : null;
                            }
                        }
                        cur = next;
                    }
                }
                if (cur.exists() && cur.isDirectory()) return cur.getUri();
                return root != null ? root.getUri() : null;
            } catch (Throwable ignored) {
            }
            return null;
        }

        // Plain content:// URI
        if (isContentUriString(p)) {
            try {
                return Uri.parse(p);
            } catch (Throwable ignored) {
            }
            return null;
        }

        // Legacy filesystem path under /storage/... (best-effort)
        return buildExternalStorageDocumentUriFromPath(p);
    }

    private static Uri buildExternalStorageDocumentUriFromPath(String absPath) {
        return BootstrapMediaUtils.buildExternalStorageDocumentUriFromPath(absPath);
    }

    private static String safeFilename(String name, String fallback) {
        return BootstrapMediaUtils.safeFilename(name, fallback);
    }

    private static String lowerExt(String name) {
        return BootstrapMediaUtils.lowerExt(name);
    }

    private static boolean isContentUriString(String s) {
        return BootstrapMediaUtils.isContentUriString(s);
    }

    private boolean canReadContentUri(String uriString) {
        if (uriString == null) return false;
        String s = uriString.trim();
        if (s.isEmpty()) return false;
        if (!isContentUriString(s)) return false;

        try {
            // Support our joined tree format: "content://...::/path/to/fileOrDir".
            if (ConfigStorage.isSafJoinedPath(s)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(s);
                if (sp == null || sp.treeUri == null) return false;
                DocumentFile cur = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri));
                if (cur == null) return false;

                String rel = sp.relPath == null ? "" : sp.relPath.trim();
                if (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);

                if (!rel.isEmpty()) {
                    String[] parts = rel.split("/");
                    for (String part : parts) {
                        if (part == null) continue;
                        String seg = part.trim();
                        if (seg.isEmpty()) continue;
                        DocumentFile next = cur.findFile(seg);
                        if (next == null) return false;
                        cur = next;
                    }
                }

                if (cur == null || !cur.exists()) return false;
                if (cur.isDirectory()) return true;

                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(cur.getUri(), "r")) {
                    return pfd != null;
                }
            }

            Uri uri = Uri.parse(s);
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                return pfd != null;
            }
        } catch (SecurityException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean canWriteContentUri(String uriString) {
        if (uriString == null) return false;
        String s = uriString.trim();
        if (s.isEmpty()) return false;
        if (!isContentUriString(s)) return false;

        try {
            // Support our joined tree format: "content://...::/path/to/file".
            if (ConfigStorage.isSafJoinedPath(s)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(s);
                if (sp == null || sp.treeUri == null) return false;
                DocumentFile cur = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri));
                if (cur == null) return false;

                String rel = sp.relPath == null ? "" : sp.relPath.trim();
                if (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);

                if (!rel.isEmpty()) {
                    String[] parts = rel.split("/");
                    for (String part : parts) {
                        if (part == null) continue;
                        String seg = part.trim();
                        if (seg.isEmpty()) continue;
                        DocumentFile next = cur.findFile(seg);
                        if (next == null) return false;
                        cur = next;
                    }
                }

                if (cur == null || !cur.exists() || cur.isDirectory()) return false;
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(cur.getUri(), "rw")) {
                    return pfd != null;
                }
            }

            Uri uri = Uri.parse(s);
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
                return pfd != null;
            }
        } catch (SecurityException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isReadableFile(File f) {
        return f != null && f.exists() && f.canRead() && f.isFile() && f.length() > 0;
    }

    private boolean isReadableMediaPath(String path) {
        if (path == null) return false;
        String p = path.trim();
        if (p.isEmpty()) return false;
        if (isContentUriString(p)) {
            return canReadContentUri(p);
        }
        return isReadableFile(new File(p));
    }

    private boolean hasPersistedReadPermission(String uriString) {
        if (uriString == null) return false;
        String s = uriString.trim();
        if (!s.startsWith("content://")) return false;
        try {
            Uri u = Uri.parse(s);
            List<android.content.UriPermission> perms = getContentResolver().getPersistedUriPermissions();
            if (perms == null) return false;
            for (android.content.UriPermission p : perms) {
                if (p == null) continue;
                if (!p.isReadPermission()) continue;
                Uri pu = p.getUri();
                if (pu != null && pu.equals(u)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean hasPersistedWritePermission(String uriString) {
        if (uriString == null) return false;
        String s = uriString.trim();
        if (!s.startsWith("content://")) return false;
        try {
            Uri u = Uri.parse(s);
            List<android.content.UriPermission> perms = getContentResolver().getPersistedUriPermissions();
            if (perms == null) return false;
            for (android.content.UriPermission p : perms) {
                if (p == null) continue;
                if (!p.isWritePermission()) continue;
                Uri pu = p.getUri();
                if (pu != null && pu.equals(u)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean hasPersistedReadPermissionForSafJoinedDir(String joinedDir) {
        if (joinedDir == null) return false;
        if (!ConfigStorage.isSafJoinedPath(joinedDir)) return false;
        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(joinedDir);
        if (sp == null || sp.treeUri == null) return false;
        return hasPersistedReadPermission(sp.treeUri);
    }

    private boolean hasPersistedWritePermissionForSafJoinedDir(String joinedDir) {
        if (joinedDir == null) return false;
        if (!ConfigStorage.isSafJoinedPath(joinedDir)) return false;
        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(joinedDir);
        if (sp == null || sp.treeUri == null) return false;
        return hasPersistedWritePermission(sp.treeUri);
    }

    private boolean canResolveSafJoinedDir(String joinedDir) {
        if (joinedDir == null) return false;
        if (!ConfigStorage.isSafJoinedPath(joinedDir)) return false;
        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(joinedDir);
        if (sp == null) return false;

        try {
            DocumentFile cur = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri));
            if (cur == null) return false;
            String rel = sp.relPath == null ? "" : sp.relPath.trim();
            if (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (rel.isEmpty()) return cur.exists() && cur.isDirectory();

            String[] parts = rel.split("/");
            for (String part : parts) {
                if (part == null) continue;
                String seg = part.trim();
                if (seg.isEmpty()) continue;
                DocumentFile next = cur.findFile(seg);
                if (next == null) return false;
                cur = next;
            }
            return cur.exists() && cur.isDirectory();
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Like {@link #canResolveSafJoinedDir} but also creates any missing
     * intermediate segments via {@link DocumentFile#createDirectory}.
     * This handles the case where a folder was created via File.mkdirs() but
     * is not yet visible to the SAF DocumentFile API (common on Android 11+).
     */
    private boolean tryEnsureSafJoinedDir(String joinedDir) {
        if (joinedDir == null) return false;
        if (!ConfigStorage.isSafJoinedPath(joinedDir)) return false;
        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(joinedDir);
        if (sp == null) return false;

        try {
            DocumentFile cur = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri));
            if (cur == null) return false;
            String rel = sp.relPath == null ? "" : sp.relPath.trim();
            if (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (rel.isEmpty()) return cur.exists() && cur.isDirectory();

            String[] parts = rel.split("/");
            for (String part : parts) {
                if (part == null) continue;
                String seg = part.trim();
                if (seg.isEmpty()) continue;
                DocumentFile next = cur.findFile(seg);
                if (next == null || !next.exists()) {
                    next = cur.createDirectory(seg);
                }
                if (next == null) return false;
                cur = next;
            }
            return cur.exists() && cur.isDirectory();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void showReselectDialog(String title, String message, Runnable reselectAction) {
        try {
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Select", (d, which) -> {
                    if (reselectAction != null) reselectAction.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Throwable t) {
            // Last resort
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean hasSoundOverrides(SharedPreferences p) {
        if (p == null) return false;
        return p.contains(UaeOptionKeys.UAE_SOUND_OUTPUT)
            || p.contains(UaeOptionKeys.UAE_SOUND_AUTO)
            || p.contains(UaeOptionKeys.UAE_SOUND_CHANNELS)
            || p.contains(UaeOptionKeys.UAE_SOUND_FREQUENCY)
            || p.contains(UaeOptionKeys.UAE_SOUND_INTERPOL)
            || p.contains(UaeOptionKeys.UAE_SOUND_FILTER)
            || p.contains(UaeOptionKeys.UAE_SOUND_FILTER_TYPE)
            || p.contains(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION)
            || p.contains(UaeOptionKeys.UAE_SOUND_STEREO_DELAY)
            || p.contains(UaeOptionKeys.UAE_SOUND_SWAP_PAULA)
            || p.contains(UaeOptionKeys.UAE_SOUND_SWAP_AHI)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_CD)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_AHI)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI)
            || p.contains(UaeOptionKeys.UAE_SOUND_MAX_BUFF)
            || p.contains(UaeOptionKeys.UAE_SOUND_PULLMODE)
            || p.contains(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED)
            || p.contains(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY)
            || p.contains(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK);
    }

    private static int cdMainPriority(String ext) {
        return BootstrapMediaUtils.cdMainPriority(ext);
    }

    private static boolean cueHasMissingTracks(File cueFile) {
        return BootstrapMediaUtils.cueHasMissingTracks(cueFile);
    }

    private static java.util.List<String> parseCueTrackFilenames(File cueFile) {
        return BootstrapMediaUtils.parseCueTrackFilenames(cueFile);
    }

    private boolean copyDocumentFileTo(DocumentFile src, File dest) {
        return fileImporter().copyDocumentFileTo(src, dest);
    }

    private boolean copyDocumentTreeTo(DocumentFile srcDir, File destDir, int depth) {
        return fileImporter().copyDocumentTreeTo(srcDir, destDir, depth);
    }

    private static DocumentFile findFileByNameIgnoreCase(DocumentFile dir, String name, int depth) {
        return BootstrapMediaUtils.findFileByNameIgnoreCase(dir, name, depth);
    }

    private static void fixCueTrackFilenameCase(File cueFile) {
        BootstrapMediaUtils.fixCueTrackFilenameCase(cueFile);
    }

    private static void deleteRecursive(File f) {
        BootstrapMediaUtils.deleteRecursive(f);
    }


    private String getDisplayName(Uri uri) {
        return fileImporter().getDisplayName(uri);
    }

    private String guessExtensionForUri(Uri uri) {
        return fileImporter().guessExtensionForUri(uri);
    }

    private File guessDestFileForUri(Uri uri, File parentDir, String stableBaseName) {
        return fileImporter().guessDestFileForUri(uri, parentDir, stableBaseName);
    }

    private void ensureDir(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private File findByPrefix(File parentDir, String prefix) {
        if (parentDir == null) return null;
        if (!parentDir.exists()) return null;
        File[] existing = parentDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith(prefix));
        if (existing == null || existing.length == 0) {
            return null;
        }
        // Prefer the newest if multiple exist.
        File best = existing[0];
        for (File f : existing) {
            if (f != null && f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    private void clearByPrefix(File parentDir, String prefix) {
        if (parentDir == null || !parentDir.exists()) return;
        File[] existing = parentDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith(prefix));
        if (existing == null) return;
        for (File f : existing) {
            clearFile(f);
        }
    }

    private void clearByPrefixExcept(File parentDir, String prefix, File keep) {
        if (parentDir == null || !parentDir.exists()) return;
        File[] existing = parentDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith(prefix));
        if (existing == null) return;
        for (File f : existing) {
            if (keep != null) {
                try {
                    if (keep.getCanonicalPath().equals(f.getCanonicalPath())) {
                        continue;
                    }
                } catch (IOException ignored) {
                    if (keep.equals(f)) {
                        continue;
                    }
                }
            }
            clearFile(f);
        }
    }

    private void takeReadPermissionIfPossible(Uri uri, int takeFlags) {
        if (uri == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                // Best-effort: try to persist both read+write (if provider granted it),
                // but gracefully fall back to read-only.
                int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try {
                    getContentResolver().takePersistableUriPermission(uri, rw);
                    return;
                } catch (SecurityException ignored) {
                    // Fall back to read-only below.
                }

                int flags = takeFlags;
                flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                flags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) {
                // Some providers do not allow persistable permissions; that's OK for immediate copy.
            } catch (Throwable ignored) {
            }
        }
    }

    private void startEmulator() {
        applyKickstartAutoForCurrentModel(false);

        String qsModel = getSelectedQsModelId();
        boolean isCdOnly = isCdOnlyModel();

        // Trace what we think we're going to boot with (helps diagnose "HDF selected but not booting").
        try {
            SharedPreferences uae = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            SharedPreferences launcher = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String hdDir = uae.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null);
            boolean hdf0Enabled = uae.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
            String hdf0Path = uae.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, null);
            String dh0Mode = launcher.getString(PREF_DH0_MODE, DH0_MODE_HDF);
            LogUtil.i(TAG, "Start pressed: model=" + qsModel
                + " dh0Mode=" + dh0Mode
                + " harddrives_dir=" + (hdDir == null ? "" : hdDir)
                + " hdf0Enabled=" + hdf0Enabled
                + " hdf0Path=" + (hdf0Path == null ? "" : hdf0Path)
                + " hdf0Readable=" + isReadableMediaPath(hdf0Path));
        } catch (Throwable ignored) {
        }

        if ("CD32".equalsIgnoreCase(qsModel)) {
            if (mSelectedKick == null || !mSelectedKick.exists() || mSelectedKick.length() <= 0) {
                Toast.makeText(this, "CD32 requires a Kickstart ROM", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mSelectedExt == null || !mSelectedExt.exists() || mSelectedExt.length() <= 0) {
                Toast.makeText(this, "CD32 requires an Extended ROM", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // CD-only models require a valid CD image.
        if (isCdOnly) {
            String cdPath = null;
            if (mSelectedCd0Path != null && !mSelectedCd0Path.trim().isEmpty()) {
                cdPath = mSelectedCd0Path.trim();
            } else if (mSelectedCd0 != null) {
                cdPath = mSelectedCd0.getAbsolutePath();
            }

            if (!isReadableMediaPath(cdPath)) {
                if (isContentUriString(cdPath)) {
                    showReselectDialog(
                        "CD Permission Required",
                        "This config points to a CD image, but Android has not granted access (or it has been revoked).\n\nPlease re-select the CD image to grant permission.",
                        this::pickCdImage0
                    );
                    return;
                }
                Toast.makeText(this, "CD32/CDTV requires a CD image", Toast.LENGTH_SHORT).show();
                return;
            }

            if (mSelectedCd0 == null && isContentUriString(cdPath)) {
                if ("cue".equals(lowerExt(mCd0SourceName))) {
                    showReselectDialog(
                        "Re-select CUE/BIN",
                        "This CD entry uses a CUE file via SAF URI.\n\nPlease re-select the CUE (and BIN tracks) so the app can import the full set locally.",
                        this::pickCdImage0
                    );
                    return;
                }
            } else if (mSelectedCd0 != null && "cue".equals(lowerExt(mSelectedCd0.getName()))) {
                fixCueTrackFilenameCase(mSelectedCd0);
                if (cueHasMissingTracks(mSelectedCd0)) {
                    Toast.makeText(this, "CUE needs its BIN tracks. Import CUE + BIN (multi-select) or use an ISO/CHD.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }

        // DH0 validation - only require HDF if user actually wants to boot from hard drive
        // Booting from floppy (DF0) is independent of hard drive configuration
        if (!isCdOnly && mDh0Added) {
            String mode = getDh0Mode();
            if (DH0_MODE_DIR.equals(mode)) {
                if (mSelectedDh0Dir != null && (!mSelectedDh0Dir.exists() || !mSelectedDh0Dir.isDirectory())) {
                    Toast.makeText(this, "DH0 folder is missing", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                String hdfPath = null;
                if (mSelectedDh0HdfPath != null && !mSelectedDh0HdfPath.trim().isEmpty()) {
                    hdfPath = mSelectedDh0HdfPath.trim();
                } else if (mSelectedDh0Hdf != null) {
                    hdfPath = mSelectedDh0Hdf.getAbsolutePath();
                }

                // Only prompt for HDF if user explicitly added DH0 but didn't select a file
                if (hdfPath == null || hdfPath.trim().isEmpty()) {
                    // DH0 was added but no HDF selected - just disable it and allow floppy boot
                    mDh0Added = false;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_SHOW_DH0, false)
                        .apply();
                    // Continue to boot without hard drive
                } else if (!isReadableMediaPath(hdfPath)) {
                    if (isContentUriString(hdfPath)) {
                        showReselectDialog(
                            "HDF Permission Required",
                            "This config points to an HDF, but Android has not granted access (or it has been revoked).\n\nPlease re-select the HDF to grant permission.",
                            this::pickDh0Hdf
                        );
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && looksLikeBlockedExternalStoragePath(hdfPath)) {
                        // Scoped storage: file exists but is not accessible. Prompt user to reselect via SAF.
                        mHdfPromptShown = true;
                        new AlertDialog.Builder(this)
                            .setTitle("HDF access required")
                            .setMessage(
                                "Android cannot read this HDF path on this device:\n\n" + hdfPath + "\n\n" +
                                    "Re-select the HDF so Android can grant access, or select a SAF parent folder in Paths.")
                            .setPositiveButton("Select HDF", (d, w) -> pickDh0Hdf())
                            .setNeutralButton("Paths", (d, w) -> openPathsAndAutoPickSafParent())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                        return;
                    }
                    Toast.makeText(this, "DH0 HDF file is missing", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }

        // If the user selected media via SAF (content://) but permission is missing/revoked,
        // prompt them to re-select so we can take persistable permission again.
        if (!isCdOnly) {
            String df0Path = (mSelectedDf0Path != null && !mSelectedDf0Path.trim().isEmpty())
                ? mSelectedDf0Path.trim()
                : (mSelectedDf0 != null ? mSelectedDf0.getAbsolutePath() : null);
            if (isContentUriString(df0Path) && !isReadableMediaPath(df0Path)) {
                showReselectDialog(
                    "Disk Permission Required",
                    "This config points to a DF0 disk image, but Android has not granted access (or it has been revoked).\n\nPlease re-select DF0 to grant permission.",
                    () -> pickDisk(REQ_IMPORT_DF0)
                );
                return;
            }

            String df1Path = (mSelectedDf1Path != null && !mSelectedDf1Path.trim().isEmpty())
                ? mSelectedDf1Path.trim()
                : (mSelectedDf1 != null ? mSelectedDf1.getAbsolutePath() : null);
            if (isContentUriString(df1Path) && !isReadableMediaPath(df1Path)) {
                showReselectDialog(
                    "Disk Permission Required",
                    "This config points to a DF1 disk image, but Android has not granted access (or it has been revoked).\n\nPlease re-select DF1 to grant permission.",
                    () -> pickDisk(REQ_IMPORT_DF1)
                );
                return;
            }
        }

        // For non-CD-only models, a CD image may still be selected; prompt if it's a SAF URI but unreadable.
        String anyCdPath = null;
        if (mSelectedCd0Path != null && !mSelectedCd0Path.trim().isEmpty()) {
            anyCdPath = mSelectedCd0Path.trim();
        } else if (mSelectedCd0 != null) {
            anyCdPath = mSelectedCd0.getAbsolutePath();
        }
        if (isContentUriString(anyCdPath) && !isReadableMediaPath(anyCdPath)) {
            showReselectDialog(
                "CD Permission Required",
                "This config points to a CD image, but Android has not granted access (or it has been revoked).\n\nPlease re-select the CD image to grant permission.",
                this::pickCdImage0
            );
            return;
        }

        launchEmulator(qsModel, isCdOnly);
    }

    private void launchEmulator(String qsModel, boolean isCdOnly) {
        launchEmulator(qsModel, isCdOnly, /*includeFloppyExtras*/ true);
    }

    private void launchEmulator(String qsModel, boolean isCdOnly, boolean includeFloppyExtras) {
        Intent i = buildEmulatorIntent(qsModel, isCdOnly, includeFloppyExtras);
        startActivity(i);
        finish();
    }

    private Intent buildEmulatorIntent(String qsModel, boolean isCdOnly, boolean includeFloppyExtras) {
        // Ensure Kickstart Map (per-model mapping) is applied for the current selection.
        // This keeps the mapped ROM authoritative at boot time.
        try {
            applyKickstartAutoForCurrentModel(false);
        } catch (Throwable ignored) {
        }

        // Ensure the current launcher media picks are written to UAE prefs before
        // we snapshot/save configs (Last Ran + auto-named config).
        try {
            if (!mLaunchedFromEmulatorMenu) {
                syncBootMediumToUaePrefs();
            }
        } catch (Throwable ignored) {
        }

        // Ensure ROM overrides in prefs reflect the current Quickstart selections.
        // AmiberryActivity builds its final CLI args from UAE prefs (including kickstart_ext_rom_file).
        try {
            SharedPreferences.Editor romEd = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();

            if (mSelectedKick != null && mSelectedKick.exists() && mSelectedKick.length() > 0) {
                romEd.putString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, mSelectedKick.getAbsolutePath());
                if (mKickSourceName != null && !mKickSourceName.trim().isEmpty()) {
                    romEd.putString(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL, mKickSourceName);
                }
            } else {
                romEd.remove(UaeOptionKeys.UAE_ROM_KICKSTART_FILE);
                romEd.remove(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL);
            }

            if (qsModel != null && "CD32".equalsIgnoreCase(qsModel)
                && mSelectedExt != null && mSelectedExt.exists() && mSelectedExt.length() > 0) {
                romEd.putString(UaeOptionKeys.UAE_ROM_EXT_FILE, mSelectedExt.getAbsolutePath());
                if (mExtSourceName != null && !mExtSourceName.trim().isEmpty()) {
                    romEd.putString(UaeOptionKeys.UAE_ROM_EXT_LABEL, mExtSourceName);
                }
            } else {
                romEd.remove(UaeOptionKeys.UAE_ROM_EXT_FILE);
                romEd.remove(UaeOptionKeys.UAE_ROM_EXT_LABEL);
            }

            romEd.apply();
        } catch (Throwable ignored) {
        }

        Intent i = new Intent(this, AmiberryActivity.class);

        // Keep an always-available "Last Ran" snapshot of the current launcher settings.
        // Best-effort only: failures (e.g., missing SAF conf folder) should not block launching.
        try {
            boolean saved = ConfigStorage.saveLastRan(this);
            if (!saved) {
                String err = ConfigStorage.getLastError();
                String msg = "Warning: could not save Last Ran config.";
                if (err != null && !err.trim().isEmpty()) msg += " " + err.trim();
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }

        // Also keep a media/game-named snapshot so users can find configs by title,
        // not only "Last Ran (auto)".
        try {
            String autoName = guessConfigName();
            if (autoName != null) autoName = autoName.trim();
            if (autoName != null && !autoName.isEmpty()) {
                ConfigStorage.saveConfig(this, autoName);
            }
        } catch (Throwable ignored) {
        }

        // Avoid unexpected DF0 insertion (e.g., disk.zip) when launching from the native UI.
        i.putExtra(AmiberryActivity.EXTRA_ENABLE_AUTO_DF0, false);

        // Enable core logging in debug builds so we can diagnose boot issues (e.g., HDF not booting).
        final boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            i.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
        }

        if (mSelectedKick != null && mSelectedKick.exists() && mSelectedKick.length() > 0) {
            i.putExtra(AmiberryActivity.EXTRA_KICKSTART_ROM_FILE, mSelectedKick.getAbsolutePath());
        }

        if (!isCdOnly && includeFloppyExtras) {
            String df0LaunchPath = null;
            if (mSelectedDf0 != null && mSelectedDf0.exists() && mSelectedDf0.length() > 0) {
                df0LaunchPath = mSelectedDf0.getAbsolutePath();
            } else if (mSelectedDf0Path != null && !mSelectedDf0Path.trim().isEmpty() && isReadableMediaPath(mSelectedDf0Path)) {
                df0LaunchPath = mSelectedDf0Path.trim();
            }
            if (df0LaunchPath != null) {
                i.putExtra(AmiberryActivity.EXTRA_DF0_DISK_FILE, df0LaunchPath);
                if (mDf0SourceName != null && !mDf0SourceName.trim().isEmpty()) {
                    i.putExtra(AmiberryActivity.EXTRA_DF0_SOURCE_NAME, mDf0SourceName.trim());
                }
            }

            String df1LaunchPath = null;
            if (mSelectedDf1 != null && mSelectedDf1.exists() && mSelectedDf1.length() > 0) {
                df1LaunchPath = mSelectedDf1.getAbsolutePath();
            } else if (mSelectedDf1Path != null && !mSelectedDf1Path.trim().isEmpty() && isReadableMediaPath(mSelectedDf1Path)) {
                df1LaunchPath = mSelectedDf1Path.trim();
            }
            if (df1LaunchPath != null) {
                i.putExtra(AmiberryActivity.EXTRA_DF1_DISK_FILE, df1LaunchPath);
            }
        }

        // Native menu replaces Amiberry's GUI.
        i.putExtra(AmiberryActivity.EXTRA_SHOW_GUI, false);

        if (qsModel != null) {
            i.putExtra(AmiberryActivity.EXTRA_QS_MODEL, qsModel);
            i.putExtra(AmiberryActivity.EXTRA_QS_CONFIG_INDEX, getSelectedBaseQsConfigIndex());
            i.putExtra(AmiberryActivity.EXTRA_QS_NTSC, false);
            // Keep legacy behavior: do NOT force Amiberry's built-in GUI Quickstart mode.
            i.putExtra(AmiberryActivity.EXTRA_QS_MODE, false);
        }

        return i;
    }

    private boolean looksLikeRdbHardfile(File hdf) {
        if (hdf == null || !hdf.exists() || !hdf.isFile()) return false;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(hdf)) {
            byte[] buf = new byte[16 * 1024];
            int n = fis.read(buf);
            if (n <= 0) return false;
            for (int i = 0; i <= n - 4; i++) {
                if (buf[i] == 'R' && buf[i + 1] == 'D' && buf[i + 2] == 'S' && buf[i + 3] == 'K') {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            Log.w(TAG, "Failed to read HDF header for RDB check: " + hdf.getAbsolutePath(), e);
            return false;
        }
    }

    private void clearExtRomSelection() {
        // Do not delete imported ext ROM files when toggling models.
        // Only clear the active selection/prefs so CD32 configs can rehydrate later.
        mSelectedExt = null;
        mExtSourceName = null;
        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(UaeOptionKeys.UAE_ROM_EXT_FILE)
            .remove(UaeOptionKeys.UAE_ROM_EXT_LABEL)
            .apply();
        saveSourceNames();
    }

    private void clearCd0Selection() {
        // Do not delete imported media files here.
        // Clearing selection should only unmount/forget active prefs so saved configs
        // can still reload previously imported media.
        mSelectedCd0 = null;
        mSelectedCd0Path = null;
        mCd0SourceName = null;
        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH)
            .putBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, false)
            .apply();
        saveSourceNames();
    }

    private void clearFloppySelections() {
        // Do not delete imported floppy files here.
        // Model/boot-medium switches must not remove backing files needed by saved configs.
        mSelectedDf0 = null;
        mSelectedDf1 = null;
        mSelectedDf0Path = null;
        mSelectedDf1Path = null;
        mDf0SourceName = null;
        mDf1SourceName = null;
        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(UaeOptionKeys.UAE_DRIVE_DF0_PATH)
            .remove(UaeOptionKeys.UAE_DRIVE_DF1_PATH)
            .apply();
        saveSourceNames();
    }

    private void clearDh0HdfSelection() {
        // Keep imported HDF files on disk; only clear active selection/prefs.
        mSelectedDh0Hdf = null;
        mSelectedDh0HdfPath = null;
        mDh0SourceName = null;
        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false)
            .remove(UaeOptionKeys.UAE_DRIVE_HDF0_PATH)
            .apply();
        saveSourceNames();
    }

    private void clearDh0FolderSelection() {
        // Keep imported DH0 folder mirror on disk; only clear active selection/prefs.
        mSelectedDh0Dir = null;
        mDh0SourceName = null;
        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, false)
            .remove(UaeOptionKeys.UAE_DRIVE_DIR0_PATH)
            .apply();
        saveSourceNames();
    }

    private void clearDh0Selections() {
        clearDh0HdfSelection();
        clearDh0FolderSelection();
    }

    private void enforceMediaExclusivityForBootMedium(boolean bootFromHd) {
        // Quick Launch should not keep stale media across boot-medium switches.
        if (bootFromHd) {
            clearFloppySelections();
            clearCd0Selection();
            return;
        }

        // Boot from floppy: clear hard drive and CD.
        clearDh0Selections();
        clearCd0Selection();
    }

    private void enforceMediaExclusivityForDh0Mode(String dh0Mode) {
        // When switching DH0 type, clear the other selection.
        if (DH0_MODE_DIR.equalsIgnoreCase(dh0Mode)) {
            clearDh0HdfSelection();
        } else {
            clearDh0FolderSelection();
        }
    }

    private void enforceMediaExclusivityForModel(String modelId) {
        // CD-only models should not retain floppy/HD selections.
        if (modelId == null) return;
        String m = modelId.trim().toUpperCase(Locale.ROOT);
        if ("CD32".equals(m) || "CDTV".equals(m)) {
            clearFloppySelections();
            clearDh0Selections();

            // CD-only mode: CD-ROM is the only supported boot medium.
            // Keep the CD selector visible by default; disable all other "Add" toggles.
            mDf1Added = false;
            mDh0Added = false;
            mDh1Added = false;
            mCd0Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SHOW_DF1, false)
                .putBoolean(PREF_SHOW_DH0, false)
                .putBoolean(PREF_SHOW_DH1, false)
                .putBoolean(PREF_SHOW_CD0, true)
                .apply();
        }
    }

    private String describeCurrentModel() {
        String model = getSelectedQsModelLabel();
        if (model == null) model = "(not set)";
        return model;
    }

    private String buildBootSummary() {
        StringBuilder sb = new StringBuilder();

        // Quickstart indicator so users can immediately see whether Paths are ready.
        sb.append(getPathsQuickstartFlagLine()).append("\n");

        String model = getSelectedQsModelLabel();
        String config = getSelectedQsConfigLabel();
        QsModel qs = getSelectedQsModel();

        sb.append("Model: ").append(model == null ? "(not set)" : model).append("\n");
        sb.append("Config: ").append(config == null ? "(default)" : config).append("\n");
        sb.append("Video: PAL\n");

        int rtgMode = getSelectedRtgMode();
        boolean rtg = rtgMode != RTG_MODE_OFF;
        boolean rtgJit = rtgMode == RTG_MODE_JIT;
        if (rtg) {
            int vram = 32;
            int z3 = getDefaultZ3FastMbForRtg();
            sb.append("RTG: UAEGFX (")
                .append(vram)
                .append(" MB VRAM, Z3 ")
                .append(z3)
                .append(" MB, JIT ")
                .append(rtgJit ? "On" : "Off")
                .append(")\n");
        } else {
            sb.append("RTG: Off\n");
        }

        if (mSelectedKick != null && mSelectedKick.exists() && mSelectedKick.length() > 0) {
            String kickLabel = mKickSourceName != null && !mKickSourceName.trim().isEmpty()
                ? mKickSourceName
                : mSelectedKick.getName();
            sb.append("Kickstart: ").append(kickLabel).append("\n");
        } else {
            sb.append("Kickstart: AROS\n");
        }

        // Show a quick “what will be overridden” summary.
        List<String> overrides = new ArrayList<>();
        if (mSelectedKick != null && mSelectedKick.exists() && mSelectedKick.length() > 0) overrides.add("Kickstart");
        if (mSelectedExt != null && mSelectedExt.exists() && mSelectedExt.length() > 0) overrides.add("Ext ROM");
        {
            String df0Path = (mSelectedDf0Path != null && !mSelectedDf0Path.trim().isEmpty())
                ? mSelectedDf0Path
                : (mSelectedDf0 != null ? mSelectedDf0.getAbsolutePath() : null);
            if (isReadableMediaPath(df0Path)) overrides.add("DF0");
        }
        {
            String df1Path = (mSelectedDf1Path != null && !mSelectedDf1Path.trim().isEmpty())
                ? mSelectedDf1Path
                : (mSelectedDf1 != null ? mSelectedDf1.getAbsolutePath() : null);
            if (isReadableMediaPath(df1Path)) overrides.add("DF1");
        }
        {
            String cdPath = (mSelectedCd0Path != null && !mSelectedCd0Path.trim().isEmpty())
                ? mSelectedCd0Path
                : (mSelectedCd0 != null ? mSelectedCd0.getAbsolutePath() : null);
            if (isReadableMediaPath(cdPath)) overrides.add("CD");
        }
        {
            String hdfPath = (mSelectedDh0HdfPath != null && !mSelectedDh0HdfPath.trim().isEmpty())
                ? mSelectedDh0HdfPath
                : (mSelectedDh0Hdf != null ? mSelectedDh0Hdf.getAbsolutePath() : null);
            if (isReadableMediaPath(hdfPath)) overrides.add("DH0 (HDF)");
        }
        if (mSelectedDh0Dir != null && mSelectedDh0Dir.exists() && mSelectedDh0Dir.isDirectory()) overrides.add("DH0 (Folder)");

        // Option overrides stored in UAE prefs (e.g., Sound page), independent of Quickstart preset toggle.
        try {
            SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            if (hasSoundOverrides(uaePrefs)) overrides.add("Sound");
        } catch (Throwable ignored) {
        }

        if (rtg) overrides.add("RTG");

        if (!overrides.isEmpty()) {
            sb.append("Overrides: ").append(android.text.TextUtils.join(", ", overrides)).append("\n");
        }

        boolean cdOnly = false;
        String modelId = getSelectedQsModelId();
        if (modelId != null) {
            String m = modelId.trim().toUpperCase(Locale.ROOT);
            cdOnly = "CD32".equals(m) || "CDTV".equals(m);
        }

        if (mSelectedExt != null && mSelectedExt.exists() && mSelectedExt.length() > 0) {
            String extLabel = mExtSourceName != null && !mExtSourceName.trim().isEmpty()
                ? mExtSourceName
                : mSelectedExt.getName();
            sb.append("Ext ROM: ").append(extLabel).append("\n");
        } else if (cdOnly) {
            sb.append("Ext ROM: (not selected)\n");
        }

        if (cdOnly) {
            String cdLabel = null;
            if (mSelectedCd0 != null && mSelectedCd0.exists() && mSelectedCd0.length() > 0) {
                cdLabel = (mCd0SourceName != null && !mCd0SourceName.trim().isEmpty())
                    ? mCd0SourceName
                    : mSelectedCd0.getName();
            } else if (isReadableMediaPath(mSelectedCd0Path)) {
                cdLabel = (mCd0SourceName != null && !mCd0SourceName.trim().isEmpty())
                    ? mCd0SourceName
                    : "(SAF)";
            }
            sb.append("CD: ").append(cdLabel != null ? cdLabel : "(empty)");
            return sb.toString();
        }

        // DH0 summary (if selected)
        {
            String mode = getDh0Mode();
            if (DH0_MODE_DIR.equals(mode)) {
                if (mSelectedDh0Dir != null && mSelectedDh0Dir.exists() && mSelectedDh0Dir.isDirectory()) {
                    String label = (mDh0SourceName != null && !mDh0SourceName.trim().isEmpty()) ? mDh0SourceName : "(folder)";
                    sb.append("DH0 Folder: ").append(label).append("\n");
                }
            } else {
                String label = null;
                if (mSelectedDh0Hdf != null && mSelectedDh0Hdf.exists() && mSelectedDh0Hdf.length() > 0) {
                    label = (mDh0SourceName != null && !mDh0SourceName.trim().isEmpty()) ? mDh0SourceName : mSelectedDh0Hdf.getName();
                } else if (isReadableMediaPath(mSelectedDh0HdfPath)) {
                    label = (mDh0SourceName != null && !mDh0SourceName.trim().isEmpty()) ? mDh0SourceName : "(SAF)";
                }
                if (label != null) {
                    sb.append("DH0 HDF: ").append(label).append("\n");
                }
            }
        }

        String df0Label = null;
        if (mSelectedDf0 != null && mSelectedDf0.exists() && mSelectedDf0.length() > 0) {
            df0Label = (mDf0SourceName != null && !mDf0SourceName.trim().isEmpty())
                ? mDf0SourceName
                : mSelectedDf0.getName();
        } else if (isReadableMediaPath(mSelectedDf0Path)) {
            df0Label = (mDf0SourceName != null && !mDf0SourceName.trim().isEmpty()) ? mDf0SourceName : "(SAF)";
        }
        String df1Label = null;
        if (mSelectedDf1 != null && mSelectedDf1.exists() && mSelectedDf1.length() > 0) {
            df1Label = (mDf1SourceName != null && !mDf1SourceName.trim().isEmpty())
                ? mDf1SourceName
                : mSelectedDf1.getName();
        } else if (isReadableMediaPath(mSelectedDf1Path)) {
            df1Label = (mDf1SourceName != null && !mDf1SourceName.trim().isEmpty()) ? mDf1SourceName : "(SAF)";
        }

        sb.append("DF0: ").append(df0Label != null ? df0Label : "(empty)").append("\n");
        sb.append("DF1: ").append(df1Label != null ? df1Label : "(empty)");

        return sb.toString();
    }

    private void syncQuickstartToUaePrefs() {
        QsModel selected = getSelectedQsModel();
        String modelId = selected != null ? selected.cliId : null;
        if (modelId == null) return;

        String compat;
        switch (modelId.toUpperCase(Locale.ROOT)) {
            case "A500P":
                compat = "A500+";
                break;
            default:
                compat = modelId;
                break;
        }

        boolean ntsc = false;

        String cfg = getSelectedQsConfigLabel();
        String chipset = null;

        // Prefer model-implied chipset.
        String upperModel = modelId.toUpperCase(Locale.ROOT);
        if ("A1200".equals(upperModel) || "A4000".equals(upperModel) || "CD32".equals(upperModel)) {
            chipset = "aga";
        }

        // Infer chipset from config label if it contains an explicit hint.
        if (cfg != null) {
            String lc = cfg.toLowerCase(Locale.ROOT);
            if (lc.contains("ecs agnus")) chipset = "ecs_agnus";
            else if (lc.contains("ecs denise")) chipset = "ecs_denise";
            else if (lc.contains("full ecs")) chipset = "ecs";
            else if (lc.contains("ocs")) chipset = "ocs";
            else if (lc.contains("aga")) chipset = "aga";
        }

        SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
        e.putString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, compat);
        if (chipset != null) {
            e.putString(UaeOptionKeys.UAE_CHIPSET, chipset);
        }
        e.putBoolean(UaeOptionKeys.UAE_NTSC, ntsc);

        // CPU/FPU defaults from Quickstart selection.
        // Note: these are written so the option pages reflect the chosen model/config.
        String cpuModel = inferCpuModel(modelId, cfg);
        if (cpuModel != null) {
            e.putString(UaeOptionKeys.UAE_CPU_MODEL, cpuModel);
        } else {
            e.remove(UaeOptionKeys.UAE_CPU_MODEL);
        }
        int rtgMode = getSelectedRtgMode();
        boolean rtg = rtgMode != RTG_MODE_OFF;
        e.putBoolean(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING, rtg ? false : is24BitAddressing(cpuModel));
        e.putBoolean(UaeOptionKeys.UAE_CPU_COMPATIBLE, inferCpuCompatibleDefault(modelId));
        e.putBoolean(UaeOptionKeys.UAE_CPU_DATA_CACHE, false);
        String cpuSpeed = inferCpuSpeedDefault(modelId);
        if (cpuSpeed != null) {
            e.putString(UaeOptionKeys.UAE_CPU_SPEED, cpuSpeed);
        } else {
            e.remove(UaeOptionKeys.UAE_CPU_SPEED);
        }
        e.remove(UaeOptionKeys.UAE_CPU_MULTIPLIER);

        String fpuModel = inferFpuModel(cpuModel, cfg);
        if (fpuModel != null) {
            e.putString(UaeOptionKeys.UAE_FPU_MODEL, fpuModel);
        } else {
            e.remove(UaeOptionKeys.UAE_FPU_MODEL);
        }
        e.putBoolean(UaeOptionKeys.UAE_FPU_STRICT, false);

        // MMU/PPC off by default for Quickstart.
        e.remove(UaeOptionKeys.UAE_MMU_MODEL);
        e.putBoolean(UaeOptionKeys.UAE_PPC_ENABLED, false);
        e.putString(UaeOptionKeys.UAE_PPC_IMPLEMENTATION, "auto");
        e.putString(UaeOptionKeys.UAE_PPC_CPU_IDLE, "disabled");

        // JIT: optional for RTG preset (kept off otherwise).
        boolean rtgJit = rtg && (rtgMode == RTG_MODE_JIT);

        // Keep cycle-exact in sync with Amiberry's built-in Quickstart defaults for the chosen model/config.
        // (RTG modes intentionally force non-cycle-exact for performance.)
        if (!rtg) {
            String cycleExact = inferCycleExactDefault(modelId, cpuModel, cfg);
            if (cycleExact != null) {
                e.putString(UaeOptionKeys.UAE_CYCLE_EXACT, cycleExact);
            } else {
                e.remove(UaeOptionKeys.UAE_CYCLE_EXACT);
            }
        }

        // RTG performance defaults: always avoid cycle-exact and run CPU at max.
        // These settings are critical for demanding RTG titles (e.g. Cannonball).
        if (rtg) {
            e.putString(UaeOptionKeys.UAE_CYCLE_EXACT, "false");
            e.putString(UaeOptionKeys.UAE_CPU_SPEED, "max");
        }

        // Performance preset: if RTG+JIT is enabled, force non-cycle-exact and max CPU speed.
        // Cycle-exact modes can reduce performance dramatically (single-digit FPS).
        if (rtg && rtgJit) {
            // Already forced above, but keep this block for additional RTG+JIT-specific tuning.

            // Also bump CPU/FPU baseline for demanding RTG software.
            String rm = modelId.trim().toUpperCase(Locale.ROOT);
            if ("A4000".equals(rm)) {
                e.putString(UaeOptionKeys.UAE_CPU_MODEL, "68040");
                e.putString(UaeOptionKeys.UAE_FPU_MODEL, "68040");
            } else if ("A3000".equals(rm)) {
                e.putString(UaeOptionKeys.UAE_CPU_MODEL, "68030");
                e.putString(UaeOptionKeys.UAE_FPU_MODEL, "0");
            } else if ("A1200".equals(rm)) {
                e.putString(UaeOptionKeys.UAE_CPU_MODEL, "68020");
                e.putString(UaeOptionKeys.UAE_FPU_MODEL, "0");
            }
        }
        e.putBoolean(UaeOptionKeys.UAE_JIT_ENABLED, rtg && rtgJit);
        e.putInt(UaeOptionKeys.UAE_CACHESIZE, (rtg && rtgJit) ? 16384 : 0);
        if (rtg && rtgJit) {
            // JIT is generally happier with CPU compatible off.
            e.putBoolean(UaeOptionKeys.UAE_CPU_COMPATIBLE, false);
        }
        e.putBoolean(UaeOptionKeys.UAE_COMP_FPU, false);
        e.putBoolean(UaeOptionKeys.UAE_COMP_CONSTJUMP, false);
        e.putString(UaeOptionKeys.UAE_COMP_FLUSHMODE, "soft");
        e.putString(UaeOptionKeys.UAE_COMP_TRUSTMODE, "direct");
        e.putBoolean(UaeOptionKeys.UAE_COMP_NF, false);
        e.putBoolean(UaeOptionKeys.UAE_COMP_CATCHFAULT, false);

        // RAM defaults from Quickstart selection.
        applyQuickstartMemoryDefaults(e, modelId, cfg);

        // RTG defaults from Quickstart selection.
        if (rtg) {
            int vram = 32;
            int z3 = getDefaultZ3FastMbForRtg();
            e.putInt(UaeOptionKeys.UAE_GFXCARD_SIZE_MB, vram);
            e.putString(UaeOptionKeys.UAE_GFXCARD_TYPE, "ZorroIII");

            // RTG needs 32-bit Z3 fast RAM. Always clear Z2 fastmem and ensure a sane Z3 baseline.
            e.putInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, Math.max(z3, 32));
            e.putInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, 0);
        } else {
            e.remove(UaeOptionKeys.UAE_GFXCARD_SIZE_MB);
            e.remove(UaeOptionKeys.UAE_GFXCARD_TYPE);
        }

        e.apply();
    }

    private static boolean is24BitAddressing(String cpuModel) {
        if (cpuModel == null) return false;
        return "68000".equals(cpuModel) || "68010".equals(cpuModel);
    }

    private static String inferCpuModel(String modelId, String cfgLabel) {
        if (cfgLabel != null) {
            String lc = cfgLabel.toLowerCase(Locale.ROOT);
            if (lc.contains("68060")) return "68060";
            if (lc.contains("68040")) return "68040";
            if (lc.contains("68030")) return "68030";
            if (lc.contains("68020")) return "68020";
            if (lc.contains("68010")) return "68010";
            if (lc.contains("68000")) return "68000";
        }

        String upperModel = modelId == null ? "" : modelId.toUpperCase(Locale.ROOT);
        switch (upperModel) {
            case "A1200":
            case "CD32":
                return "68020";
            case "A3000":
                return "68030";
            case "A4000":
                // Common baseline; config label can override to 68040/68060.
                return "68030";
            default:
                return "68000";
        }
    }

    private static String inferCycleExactDefault(String modelId, String cpuModel, String cfgLabel) {
        if (modelId == null) return null;

        // Mirror Amiberry's built-in Quickstart presets (built_in_prefs(..., compa=0)).
        // Not all built-in presets enable cycle-exact (e.g. faster big-box presets).
        String m = modelId.trim().toUpperCase(Locale.ROOT);

        // Big-box defaults in Amiberry are generally tuned for performance.
        if ("A3000".equals(m) || "A4000".equals(m)) {
            return "false";
        }

        // A1200/CD32 defaults are cycle-exact for baseline 68020/68030 presets.
        // If the chosen config implies a 68040/68060 class CPU, avoid cycle-exact by default.
        if ("A1200".equals(m) || "CD32".equals(m)) {
            if ("68040".equals(cpuModel) || "68060".equals(cpuModel)) {
                return "false";
            }
            return "true";
        }

        // 68000-based home models default to cycle-exact in Quickstart.
        return "true";
    }

    private static boolean inferCpuCompatibleDefault(String modelId) {
        if (modelId == null) return true;
        String m = modelId.trim().toUpperCase(Locale.ROOT);

        // In upstream built-in presets, big-box defaults run with cpu_compatible disabled.
        return !("A3000".equals(m) || "A4000".equals(m));
    }

    private static String inferCpuSpeedDefault(String modelId) {
        if (modelId == null) return null;
        String m = modelId.trim().toUpperCase(Locale.ROOT);

        // Upstream built-in presets set m68k_speed=-1 (max) for A3000/A4000,
        // and m68k_speed=0 (real) for A500/A500+/A600/A1200/CD32 quickstarts.
        if ("A3000".equals(m) || "A4000".equals(m)) {
            return "max";
        }
        return "real";
    }

    private static String inferFpuModel(String cpuModel, String cfgLabel) {
        if (cfgLabel != null) {
            String lc = cfgLabel.toLowerCase(Locale.ROOT);
            if (lc.contains("fpu")) {
                if (lc.contains("68060")) return "68060";
                if (lc.contains("68040")) return "68040";
                if (lc.contains("68882")) return "68882";
                if (lc.contains("68881")) return "68881";
            }
        }

        if (cpuModel == null) return null;
        if ("68040".equals(cpuModel)) return "68040";
        if ("68060".equals(cpuModel)) return "68060";
        // Most Quickstart configs do not enable an FPU by default.
        return "0";
    }

    private static void applyQuickstartMemoryDefaults(SharedPreferences.Editor e, String modelId, String cfgLabel) {
        // Defaults depend mostly on model; config label can override.
        String upperModel = modelId == null ? "" : modelId.toUpperCase(Locale.ROOT);

        int chip = 2; // 1MB default
        int bogo = 0;
        int fastBytes = 0;

        switch (upperModel) {
            case "A1200":
            case "A4000":
            case "CD32":
                chip = 4; // 2MB chip
                bogo = 0;
                fastBytes = 0;
                break;
            case "A3000":
                chip = 4; // 2MB chip
                bogo = 0;
                fastBytes = 8 * 1024 * 1024;
                break;
            case "A500":
            case "A500P":
            case "A600":
            case "A1000":
            case "A2000":
            default:
                chip = 1; // 512KB chip
                bogo = 0;
                fastBytes = 0;
                break;
        }

        // Refine using the config label text.
        if (cfgLabel != null) {
            String lc = cfgLabel.toLowerCase(Locale.ROOT);

            if (lc.contains("256 kb chip")) chip = 0;
            else if (lc.contains("512 kb chip")) chip = 1;
            else if (lc.contains("1.5 mb chip") || lc.contains("1,5 mb chip")) chip = 3;
            else if (lc.contains("2 mb chip")) chip = 4;
            else if (lc.contains("4 mb chip")) chip = 8;
            else if (lc.contains("8 mb chip")) chip = 16;
            else if (lc.contains("1 mb chip")) chip = 2;

            if (lc.contains("512 kb slow")) bogo = 2;
            else if (lc.contains("1.5 mb slow") || lc.contains("1,5 mb slow")) bogo = 6;
            else if (lc.contains("1.8 mb") && lc.contains("slow")) bogo = 7;
            else if (lc.contains("1 mb slow")) bogo = 4;

            if (lc.contains("64 kb fast")) fastBytes = 64 * 1024;
            else if (lc.contains("128 kb fast")) fastBytes = 128 * 1024;
            else if (lc.contains("256 kb fast")) fastBytes = 256 * 1024;
            else if (lc.contains("512 kb fast")) fastBytes = 512 * 1024;
            else if (lc.contains("1 gb fast")) fastBytes = 1024 * 1024 * 1024;
            else if (lc.contains("1 mb fast")) fastBytes = 1 * 1024 * 1024;
            else if (lc.contains("2 mb fast")) fastBytes = 2 * 1024 * 1024;
            else if (lc.contains("4 mb fast")) fastBytes = 4 * 1024 * 1024;
            else if (lc.contains("8 mb fast")) fastBytes = 8 * 1024 * 1024;
        }

        e.putInt(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE, chip);
        e.putInt(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE, bogo);
        e.putInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, fastBytes);
        e.putInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB, 0);
        e.putString(UaeOptionKeys.UAE_MEM_Z3MAPPING, "auto");
    }

    private boolean importToFile(Uri uri, File dest) {
        return fileImporter().importToFile(uri, dest);
    }

    private void saveSourceNames() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_KICK_SRC, mKickSourceName)
            .putString(PREF_EXT_SRC, mExtSourceName)
            .putString(PREF_CD0_SRC, mCd0SourceName)
            .putString(PREF_DF0_SRC, mDf0SourceName)
            .putString(PREF_DF1_SRC, mDf1SourceName)
            .putString(PREF_DF2_SRC, mDf2SourceName)
            .putString(PREF_DF3_SRC, mDf3SourceName)
            .putString(PREF_DH0_SRC, mDh0SourceName)
            .putString(PREF_DH1_SRC, mDh1SourceName)
            .putString(PREF_DH2_SRC, mDh2SourceName)
            .putString(PREF_DH3_SRC, mDh3SourceName)
            .putString(PREF_DH4_SRC, mDh4SourceName)
            .apply();
    }

    private void loadSourceNames() {
        mKickSourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_KICK_SRC, null);
        mExtSourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_EXT_SRC, null);
        mCd0SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CD0_SRC, null);
        mDf0SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DF0_SRC, null);
        mDf1SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DF1_SRC, null);
        mDf2SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DF2_SRC, null);
        mDf3SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DF3_SRC, null);
        mDh0SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH0_SRC, null);
        mDh1SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH1_SRC, null);
        mDh2SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH2_SRC, null);
        mDh3SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH3_SRC, null);
        mDh4SourceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DH4_SRC, null);
    }

    private static boolean isLikelyRomFile(File f) {
        return BootstrapMediaUtils.isLikelyRomFile(f);
    }

    private static boolean isLikelyRomName(String name) {
        return BootstrapMediaUtils.isLikelyRomName(name);
    }

    private static String sanitizeFilename(String name) {
        return BootstrapMediaUtils.sanitizeFilename(name);
    }

    private static List<File> listRomCandidates(File romsDir) {
        ArrayList<File> out = new ArrayList<>();
        if (romsDir == null || !romsDir.exists() || !romsDir.isDirectory()) return out;
        File[] files = romsDir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (isLikelyRomFile(f)) out.add(f);
        }
        return out;
    }

    private List<RomSource> listRomSourcesFromKickstartsDirPref() {
        try {
            maybeMigrateKickstartsPathPref();
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String kickstarts = p.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null);
            if (kickstarts == null || kickstarts.trim().isEmpty()) return new ArrayList<>();
            String v = kickstarts.trim();
            ArrayList<RomSource> out = new ArrayList<>();

            // SAF joined path: content://...::/relative/path
            if (ConfigStorage.isSafJoinedPath(v)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(v);
                if (sp == null || sp.treeUri == null) return out;

                android.net.Uri treeUri = android.net.Uri.parse(sp.treeUri);
                androidx.documentfile.provider.DocumentFile cur = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
                if (cur == null || !cur.exists() || !cur.isDirectory()) return out;

                String rel = sp.relPath;
                if (rel != null) {
                    String rp = rel.trim();
                    if (rp.startsWith("/")) rp = rp.substring(1);
                    if (!rp.isEmpty() && !"/".equals(rp)) {
                        String[] parts = rp.split("/");
                        for (String part : parts) {
                            if (part == null) continue;
                            String seg = part.trim();
                            if (seg.isEmpty()) continue;
                            androidx.documentfile.provider.DocumentFile next = cur.findFile(seg);
                            if (next == null) return out;
                            cur = next;
                        }
                    }
                }

                if (!cur.exists() || !cur.isDirectory()) return out;
                androidx.documentfile.provider.DocumentFile[] kids = cur.listFiles();
                if (kids == null) return out;
                for (androidx.documentfile.provider.DocumentFile df : kids) {
                    if (df == null || !df.exists() || !df.isFile()) continue;
                    String name = df.getName();
                    if (!isLikelyRomName(name)) continue;
                    out.add(new RomSource(name, null, df.getUri(), false));
                }
                return out;
            }

            // Plain tree URI.
            if (isContentUriString(v)) {
                try {
                    android.net.Uri treeUri = android.net.Uri.parse(v);
                    androidx.documentfile.provider.DocumentFile dirDf = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
                    if (dirDf != null && dirDf.exists() && dirDf.isDirectory()) {
                        androidx.documentfile.provider.DocumentFile[] kids = dirDf.listFiles();
                        if (kids != null) {
                            for (androidx.documentfile.provider.DocumentFile df : kids) {
                                if (df == null || !df.exists() || !df.isFile()) continue;
                                String name = df.getName();
                                if (!isLikelyRomName(name)) continue;
                                out.add(new RomSource(name, null, df.getUri(), false));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return out;
            }

            // Regular filesystem path.
            File dir = new File(v);
            if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) return out;
            File[] files = dir.listFiles();
            if (files == null) return out;
            for (File f : files) {
                if (!isLikelyRomFile(f)) continue;
                String name = f.getName();
                out.add(new RomSource(name, f, null, false));
            }
            return out;
        } catch (Throwable ignored) {
        }
        return new ArrayList<>();
    }

    private List<RomSource> listAllRomSourcesForKickstartMap() {
        ArrayList<RomSource> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();

        // Prefer Kickstarts folder entries first, per UX request.
        for (RomSource rs : listRomSourcesFromKickstartsDirPref()) {
            if (rs == null) continue;
            String key = rs.uri != null ? rs.uri.toString() : (rs.file != null ? rs.file.getAbsolutePath() : null);
            if (key != null && seen.add(key)) out.add(rs);
        }

        File romsDir = getInternalRomsDir();
        for (File f : listRomCandidates(romsDir)) {
            if (f == null) continue;
            String key = f.getAbsolutePath();
            if (key == null || !seen.add(key)) continue;
            String name = f.getName();
            out.add(new RomSource(name, f, null, true));
        }

        return out;
    }

    private File materializeRomSourceToInternal(RomSource src, boolean ext) {
        if (src == null) return null;

        try {
            File romsDir = getInternalRomsDir();
            ensureDir(romsDir);
            String internalRoot = romsDir.getAbsolutePath();

            if (src.file != null) {
                String selPath = src.file.getAbsolutePath();
                boolean isInternal = selPath != null && internalRoot != null && selPath.startsWith(internalRoot);
                if (isInternal) return src.file;
            }

            String stable = ext ? INTERNAL_EXT_ROM_PREFIX : INTERNAL_KICKSTART_PREFIX;
            String baseName = sanitizeFilename(src.displayName != null ? src.displayName : (src.file != null ? src.file.getName() : "rom.bin"));
            File dest = new File(romsDir, stable + "__" + baseName);

            java.io.InputStream in = null;
            try {
                if (src.file != null) {
                    if (!src.file.exists() || !src.file.isFile() || !src.file.canRead()) return null;
                    in = new java.io.FileInputStream(src.file);
                } else if (src.uri != null) {
                    in = getContentResolver().openInputStream(src.uri);
                }

                if (in == null) return null;

                try (java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                    out.flush();
                }
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
            }

            if (dest.exists() && dest.length() > 0) return dest;
        } catch (Throwable ignored) {
        }

        return null;
    }

    private String displayRomName(File internalFile) {
        if (internalFile == null) return null;
        String derivedKick = deriveSourceNameFromInternalFile(internalFile, INTERNAL_KICKSTART_PREFIX);
        if (derivedKick != null && !derivedKick.trim().isEmpty()) return derivedKick;
        String derivedExt = deriveSourceNameFromInternalFile(internalFile, INTERNAL_EXT_ROM_PREFIX);
        if (derivedExt != null && !derivedExt.trim().isEmpty()) return derivedExt;
        return internalFile.getName();
    }

    private static int scoreRomForModel(String nameLower, String baseModelId, boolean wantExt) {
        if (nameLower == null) return Integer.MIN_VALUE;
        String n = nameLower;
        int score = 0;

        boolean isExt = n.contains("ext") || n.contains("extended") || n.contains("extrom");
        if (wantExt) score += isExt ? 80 : -40;
        else score += isExt ? -80 : 10;

        String m = baseModelId == null ? "" : baseModelId.trim().toUpperCase(Locale.ROOT);
        if ("CD32".equals(m)) {
            if (n.contains("cd32")) score += 120;
            if (n.contains("cdtv")) score -= 220;
            if (!wantExt && (n.contains("kick") || n.contains("kickstart"))) score += 10;
            // Many CD32 Kickstart ROMs are named primarily by version (40.060) and may omit "cd32".
            if (!wantExt) {
                if (n.contains("40060") || n.contains("40.060") || n.contains("40_060") || n.contains("kick40060")) score += 140;
                if (n.contains("3.1") || n.contains("kick31") || n.contains("kick_31")) score += 40;
            }
        } else if ("CDTV".equals(m)) {
            if (n.contains("cdtv")) score += 120;
            if (n.contains("cd32")) score -= 220;
        } else if ("A1200".equals(m)) {
            if (n.contains("a1200") || n.contains("1200")) score += 120;
        } else if ("A500".equals(m)) {
            if (n.contains("a500") || n.contains("500")) score += 40;
            // Kickstart 1.3 is commonly 34.xx
            if (n.contains("1.3") || n.contains("kick13") || n.contains("kick_13") || n.contains("34.")) score += 120;
        } else if ("A500P".equals(m)) {
            if (n.contains("a500+") || n.contains("a500p") || n.contains("500+")) score += 120;
        } else if ("A600".equals(m)) {
            if (n.contains("a600") || n.contains("600")) score += 120;
        } else if ("A3000".equals(m)) {
            if (n.contains("a3000") || n.contains("3000")) score += 120;
        } else if ("A4000".equals(m)) {
            if (n.contains("a4000") || n.contains("4000")) score += 120;
        } else if ("A1000".equals(m)) {
            if (n.contains("a1000") || n.contains("1000")) score += 120;
        } else if ("A2000".equals(m)) {
            if (n.contains("a2000") || n.contains("2000")) score += 120;
        }

        if (n.contains("3.1")) score += 10;
        if (n.endsWith(".rom") || n.endsWith(".bin")) score += 5;
        return score;
    }

    private File pickBestRomForModel(String baseModelId, boolean wantExt) {
        ArrayList<RomSource> candidates = new ArrayList<>();
        candidates.addAll(listAllRomSourcesForKickstartMap());
        int best = Integer.MIN_VALUE;
        RomSource bestSource = null;

        for (RomSource src : candidates) {
            if (src == null) continue;

            File f = src.file;
            String display = (src.displayName == null) ? "" : src.displayName;
            String derivedKick = (f != null) ? deriveSourceNameFromInternalFile(f, INTERNAL_KICKSTART_PREFIX) : null;
            String derivedExt = (f != null) ? deriveSourceNameFromInternalFile(f, INTERNAL_EXT_ROM_PREFIX) : null;
            String nameForScore = (derivedKick != null) ? derivedKick : ((derivedExt != null) ? derivedExt : display);
            String n = nameForScore.toLowerCase(Locale.ROOT);

            int s = scoreRomForModel(n, baseModelId, wantExt);

            // Prefer our stable internal naming when possible.
            if (f != null) {
                if (!wantExt && f.getName() != null && f.getName().startsWith(INTERNAL_KICKSTART_PREFIX + "__")) s += 20;
                if (wantExt && f.getName() != null && f.getName().startsWith(INTERNAL_EXT_ROM_PREFIX + "__")) s += 20;
            }

            if (s > best) {
                best = s;
                bestSource = src;
            }
        }

        if (best < 60 || bestSource == null) return null;
        if (bestSource.file != null && bestSource.isInternal) return bestSource.file;
        return materializeRomSourceToInternal(bestSource, wantExt);
    }

    private void applyKickstartAutoForCurrentModel(boolean force) {
        String baseModelId = getSelectedQsModelId();
        if (baseModelId == null) return;

        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String kickKey = PREF_KICK_MAP_PREFIX + baseModelId.toUpperCase(Locale.ROOT);
        String extKey = PREF_EXT_MAP_PREFIX + baseModelId.toUpperCase(Locale.ROOT);
        String mappedKick = p.getString(kickKey, null);
        String mappedExt = p.getString(extKey, null);

        File mappedKickFile = null;
        if (mappedKick != null && !mappedKick.trim().isEmpty()) {
            try {
                File f = new File(mappedKick.trim());
                if (f.exists() && f.isFile() && f.canRead() && f.length() > 0) mappedKickFile = f;
            } catch (Throwable ignored) {
            }
        }

        File mappedExtFile = null;
        if (mappedExt != null && !mappedExt.trim().isEmpty()) {
            try {
                File f = new File(mappedExt.trim());
                if (f.exists() && f.isFile() && f.canRead() && f.length() > 0) mappedExtFile = f;
            } catch (Throwable ignored) {
            }
        }

        // If the current selection is clearly for a different model, treat it as invalid so
        // switching models never stays stuck on the previous Kickstart.
        boolean selectionLooksWrong = false;
        try {
            if (mSelectedKick != null) {
                String selName = (mSelectedKick.getName() == null) ? "" : mSelectedKick.getName().toLowerCase(Locale.ROOT);
                String m = baseModelId.trim().toUpperCase(Locale.ROOT);
                if (!"CD32".equals(m) && !"CDTV".equals(m)) {
                    if (selName.contains("cd32") || selName.contains("cdtv")) selectionLooksWrong = true;
                }
                if ("CD32".equals(m) && selName.contains("cdtv")) selectionLooksWrong = true;
                if ("CDTV".equals(m) && selName.contains("cd32")) selectionLooksWrong = true;
            }
        } catch (Throwable ignored) {
        }

        boolean changed = false;

        if (force || mappedKickFile != null || selectionLooksWrong || mSelectedKick == null || !mSelectedKick.exists()) {
            File pick = null;

            // Prefer the mapped entry when present.
            if (mappedKickFile != null) {
                pick = mappedKickFile;
            }

            // If no valid mapping exists, auto-pick once for this model and persist as the mapping
            // so Kickstart Map stays authoritative for subsequent launches.
            if (pick == null) {
                pick = pickBestRomForModel(baseModelId, false);
                if (pick != null) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(kickKey, pick.getAbsolutePath())
                        .apply();
                }
            }

            if (pick != null && (mSelectedKick == null || !pick.equals(mSelectedKick))) {
                mSelectedKick = pick;
                mKickSourceName = displayRomName(pick);
                changed = true;
            }

            // If we forced a model change but couldn't find anything, clear the selection rather than
            // keeping a previous model's ROM.
            if (force && pick == null && selectionLooksWrong) {
                mSelectedKick = null;
                mKickSourceName = null;
                changed = true;
            }
        }

        if ("CD32".equalsIgnoreCase(baseModelId)) {
            if (force || mappedExtFile != null || mSelectedExt == null || !mSelectedExt.exists()) {
                File pick = null;

                if (mappedExtFile != null) {
                    pick = mappedExtFile;
                }

                if (pick == null) {
                    pick = pickBestRomForModel(baseModelId, true);
                    if (pick != null) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(extKey, pick.getAbsolutePath())
                            .apply();
                    }
                }

                if (pick != null && (mSelectedExt == null || !pick.equals(mSelectedExt))) {
                    mSelectedExt = pick;
                    mExtSourceName = displayRomName(pick);
                    changed = true;
                }
            }
        }

        if (changed) {
            saveSourceNames();
        }

        // Kickstart Map is authoritative; persist the resolved selection into UAE prefs
        // so nothing later can "resurrect" a stale ROM path.
        SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor uaeEd = uaePrefs.edit();

        if (mSelectedKick != null && mSelectedKick.exists() && mSelectedKick.isFile() && mSelectedKick.canRead() && mSelectedKick.length() > 0) {
            uaeEd.putString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, mSelectedKick.getAbsolutePath());
        } else {
            uaeEd.remove(UaeOptionKeys.UAE_ROM_KICKSTART_FILE);
        }

        if ("CD32".equalsIgnoreCase(baseModelId)) {
            if (mSelectedExt != null && mSelectedExt.exists() && mSelectedExt.isFile() && mSelectedExt.canRead() && mSelectedExt.length() > 0) {
                uaeEd.putString(UaeOptionKeys.UAE_ROM_EXT_FILE, mSelectedExt.getAbsolutePath());
            } else {
                uaeEd.remove(UaeOptionKeys.UAE_ROM_EXT_FILE);
            }
        } else {
            // Avoid lingering CD32 ext ROM on non-CD32 models.
            uaeEd.remove(UaeOptionKeys.UAE_ROM_EXT_FILE);
        }

        uaeEd.apply();
    }

    private void showKickstartMapDialog() {
        final String[] modelKeys = new String[]{"A500", "A500P", "A600", "A1200", "A3000", "A4000", "CDTV", "CD32", "CD32_EXT"};
        final String[] labels = new String[]{"Amiga 500", "Amiga 500+", "Amiga 600", "Amiga 1200", "Amiga 3000", "Amiga 4000", "CDTV", "CD32 (Kickstart)", "CD32 (Ext ROM)"};

        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        CharSequence[] rows = new CharSequence[modelKeys.length];
        for (int i = 0; i < modelKeys.length; i++) {
            boolean isExt = "CD32_EXT".equals(modelKeys[i]);
            String base = isExt ? "CD32" : modelKeys[i];
            String key = (isExt ? PREF_EXT_MAP_PREFIX : PREF_KICK_MAP_PREFIX) + base.toUpperCase(Locale.ROOT);
            String v = p.getString(key, null);
            String mapped = "(auto)";
            if (v != null && !v.trim().isEmpty()) {
                File f = new File(v.trim());
                String label = displayRomName(f);
                mapped = (label != null && !label.trim().isEmpty()) ? label : f.getName();
            }
            rows[i] = labels[i] + "\n  -> " + mapped;
        }

        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(
            this,
            R.layout.kick_map_list_item,
            rows
        );

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Kickstart Map")
            .setAdapter(adapter, (dlg, which) -> {
                if (which < 0 || which >= modelKeys.length) return;
                boolean isExt = "CD32_EXT".equals(modelKeys[which]);
                String base = isExt ? "CD32" : modelKeys[which];
                showKickFilePicker(base, isExt);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showKickFilePicker(String baseModelId, boolean ext) {
        ArrayList<RomSource> sources = new ArrayList<>(listAllRomSourcesForKickstartMap());

        // If the Kickstarts folder is configured but we can't see any ROM files there,
        // show a gentle warning (often means empty folder, unsupported extensions, or missing SAF permission).
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String kickDir = p.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null);
            boolean kickDirConfigured = kickDir != null && !kickDir.trim().isEmpty();
            int kickstartsCount = 0;
            for (RomSource rs : sources) {
                if (rs != null && !rs.isInternal) kickstartsCount++;
            }
            if (kickDirConfigured && kickstartsCount == 0) {
                // Build a more informative message
                StringBuilder msg = new StringBuilder();
                msg.append("A Kickstarts folder is configured:\n");
                
                // Show a simplified path
                String displayPath = kickDir;
                if (ConfigStorage.isSafJoinedPath(kickDir)) {
                    ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(kickDir);
                    if (sp != null && sp.relPath != null) {
                        displayPath = sp.relPath;
                    } else if (sp != null && sp.treeUri != null) {
                        displayPath = "(SAF folder)";
                    }
                } else if (isContentUriString(kickDir)) {
                    displayPath = "(SAF folder)";
                }
                msg.append(displayPath).append("\n\n");
                
                // Check if we can actually access the folder
                boolean canAccess = false;
                int fileCount = 0;
                try {
                    if (ConfigStorage.isSafJoinedPath(kickDir)) {
                        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(kickDir);
                        if (sp != null && sp.treeUri != null) {
                            DocumentFile cur = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri));
                            if (cur != null && cur.exists() && cur.isDirectory()) {
                                // Navigate to relative path
                                String rel = sp.relPath;
                                if (rel != null) {
                                    String rp = rel.trim();
                                    if (rp.startsWith("/")) rp = rp.substring(1);
                                    if (!rp.isEmpty()) {
                                        String[] parts = rp.split("/");
                                        for (String part : parts) {
                                            if (part == null || part.trim().isEmpty()) continue;
                                            DocumentFile next = cur.findFile(part.trim());
                                            if (next == null || !next.exists()) {
                                                // Folder may exist on filesystem but not yet
                                                // visible via SAF. Try to create it so both
                                                // this check and future access work correctly.
                                                next = cur.createDirectory(part.trim());
                                            }
                                            if (next == null) {
                                                cur = null;
                                                break;
                                            }
                                            cur = next;
                                        }
                                    }
                                }
                                if (cur != null && cur.exists() && cur.isDirectory()) {
                                    canAccess = true;
                                    DocumentFile[] kids = cur.listFiles();
                                    if (kids != null) {
                                        fileCount = kids.length;
                                    }
                                }
                            }
                        }
                    } else if (isContentUriString(kickDir)) {
                        DocumentFile cur = DocumentFile.fromTreeUri(this, Uri.parse(kickDir));
                        if (cur != null && cur.exists() && cur.isDirectory()) {
                            canAccess = true;
                            DocumentFile[] kids = cur.listFiles();
                            if (kids != null) {
                                fileCount = kids.length;
                            }
                        }
                    } else {
                        // Regular file path
                        File dir = new File(kickDir.trim());
                        if (dir.exists() && dir.isDirectory() && dir.canRead()) {
                            canAccess = true;
                            File[] files = dir.listFiles();
                            if (files != null) {
                                fileCount = files.length;
                            }
                        }
                    }
                } catch (SecurityException se) {
                    canAccess = false;
                } catch (Throwable t) {
                    canAccess = false;
                }
                
                if (!canAccess) {
                    msg.append("⚠️ Cannot access this folder.\n\n");
                    msg.append("Android may have revoked permission. Please re-select the folder in Paths.");
                } else if (fileCount == 0) {
                    msg.append("The folder is empty.\n\n");
                    msg.append("Copy Kickstart ROM files (.rom, .bin, or .zip) to this folder.");
                } else {
                    msg.append("Found ").append(fileCount).append(" file(s), but none appear to be ROM files.\n\n");
                    msg.append("ROM files should have .rom, .bin, or .zip extension.");
                }
                
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("Kickstarts Folder")
                    .setMessage(msg.toString())
                    .setPositiveButton("Continue Anyway", (d, w) -> showKickFilePickerInternal(baseModelId, ext, sources))
                    .setNeutralButton("Open Paths", (d, w) -> startActivity(new Intent(this, PathsSimpleActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
        } catch (Throwable ignored) {
        }

        showKickFilePickerInternal(baseModelId, ext, sources);
    }

    private void showKickFilePickerInternal(String baseModelId, boolean ext, ArrayList<RomSource> sources) {

        ArrayList<String> options = new ArrayList<>();
        options.add("(auto)");
        for (RomSource rs : sources) {
            if (rs == null) continue;

            String label = null;
            if (rs.file != null) {
                label = displayRomName(rs.file);
            }
            if (label == null || label.trim().isEmpty()) {
                label = rs.displayName;
            }
            if (label == null || label.trim().isEmpty()) {
                label = (rs.uri != null) ? rs.uri.toString() : "(unknown)";
            }

            String prefix = rs.isInternal ? "[Internal] " : "[Kickstarts] ";
            options.add(prefix + label);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(ext ? (baseModelId + " Ext ROM") : (baseModelId + " Kickstart"))
            .setItems(options.toArray(new String[0]), (dlg, which) -> {
                String key = (ext ? PREF_EXT_MAP_PREFIX : PREF_KICK_MAP_PREFIX) + baseModelId.toUpperCase(Locale.ROOT);
                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                if (which == 0) {
                    ed.remove(key).apply();
                    try {
                        android.widget.Toast.makeText(
                            this,
                            "Cleared " + (ext ? "Ext ROM" : "Kickstart") + " for " + baseModelId,
                            android.widget.Toast.LENGTH_SHORT
                        ).show();
                    } catch (Throwable ignored) {
                    }
                } else {
                    RomSource src = sources.get(which - 1);
                    File internal = materializeRomSourceToInternal(src, ext);
                    if (internal != null) {
                        ed.putString(key, internal.getAbsolutePath()).apply();

                        try {
                            String label = displayRomName(internal);
                            if (label == null || label.trim().isEmpty()) label = internal.getName();
                            android.widget.Toast.makeText(
                                this,
                                "Mapped " + baseModelId + " -> " + label,
                                android.widget.Toast.LENGTH_SHORT
                            ).show();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                applyKickstartAutoForCurrentModel(true);
                refreshStatus();
                requestReopenKickstartMapAfterPicker();
                maybeReopenKickstartMapAfterPicker();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static String deriveSourceNameFromInternalFile(File internalFile, String stableBaseName) {
        if (internalFile == null) return null;
        String name = internalFile.getName();
        if (name == null) return null;

        String marker = stableBaseName + "__";
        if (name.startsWith(marker) && name.length() > marker.length()) {
            return name.substring(marker.length());
        }
        return null;
    }

    private String deriveSourceNameFromUriIfMissing(String currentName, String uriString) {
        if (currentName != null && !currentName.trim().isEmpty()) return currentName;
        if (uriString == null) return currentName;
        String s = uriString.trim();
        if (s.isEmpty()) return currentName;

        try {
            String dn = getDisplayName(Uri.parse(s));
            if (dn != null && !dn.trim().isEmpty()) return dn;
        } catch (Throwable ignored) {
        }
        return currentName;
    }

    private String deriveSourceNameFromFileOrUriIfMissing(String currentName, File selectedFile, String selectedUri) {
        if (currentName != null && !currentName.trim().isEmpty()) return currentName;
        if (selectedFile != null) {
            try {
                return selectedFile.getName();
            } catch (Throwable ignored) {
            }
        }
        return deriveSourceNameFromUriIfMissing(currentName, selectedUri);
    }

    private void maybeDeriveAndPersistMissingSourceNames() {
        boolean changed = false;

        if ((mKickSourceName == null || mKickSourceName.trim().isEmpty()) && mSelectedKick != null) {
            String derived = deriveSourceNameFromInternalFile(mSelectedKick, INTERNAL_KICKSTART_PREFIX);
            if (derived != null && !derived.trim().isEmpty()) {
                mKickSourceName = derived;
                changed = true;
            }
        }
        if ((mExtSourceName == null || mExtSourceName.trim().isEmpty()) && mSelectedExt != null) {
            String derived = deriveSourceNameFromInternalFile(mSelectedExt, INTERNAL_EXT_ROM_PREFIX);
            if (derived != null && !derived.trim().isEmpty()) {
                mExtSourceName = derived;
                changed = true;
            }
        }
        if ((mCd0SourceName == null || mCd0SourceName.trim().isEmpty()) && mSelectedCd0 != null) {
            String derived = deriveSourceNameFromInternalFile(mSelectedCd0, "cdimage0");
            if (derived == null || derived.trim().isEmpty()) {
                derived = mSelectedCd0.getName();
            }
            if (derived != null && !derived.trim().isEmpty()) {
                mCd0SourceName = derived;
                changed = true;
            }
        }
        if ((mDf0SourceName == null || mDf0SourceName.trim().isEmpty()) && mSelectedDf0 != null) {
            String derived = deriveSourceNameFromInternalFile(mSelectedDf0, "df0");
            if (derived != null && !derived.trim().isEmpty()) {
                mDf0SourceName = derived;
                changed = true;
            }
        }
        if ((mDf1SourceName == null || mDf1SourceName.trim().isEmpty()) && mSelectedDf1 != null) {
            String derived = deriveSourceNameFromInternalFile(mSelectedDf1, "df1");
            if (derived != null && !derived.trim().isEmpty()) {
                mDf1SourceName = derived;
                changed = true;
            }
        }

        if ((mDh0SourceName == null || mDh0SourceName.trim().isEmpty()) && mSelectedDh0Hdf != null) {
            String derived = deriveSourceNameFromInternalFile(mSelectedDh0Hdf, INTERNAL_DH0_HDF_PREFIX);
            if (derived != null && !derived.trim().isEmpty()) {
                mDh0SourceName = derived;
                changed = true;
            }
        }

        if (changed) {
            saveSourceNames();
        }
    }

    private String labelWithSource(String label, File internalFile, String sourceName) {
        return labelWithSource(label, internalFile, sourceName, null);
    }

    private String labelWithSource(String label, File internalFile, String sourceName, String selectedPath) {
        if (internalFile != null && internalFile.exists() && internalFile.length() > 0) {
            if (sourceName != null && !sourceName.trim().isEmpty()) {
                return label + ": " + sourceName;
            }
            return label + ": " + internalFile.getName();
        }
        if (selectedPath != null && !selectedPath.trim().isEmpty() && isContentUriString(selectedPath)) {
            String p = selectedPath.trim();
            boolean readable = canReadContentUri(p);
            String base = null;
            if (sourceName != null && !sourceName.trim().isEmpty()) {
                base = sourceName;
            }
            if (readable) {
                return label + ": " + (base != null ? base : "(SAF)");
            }
            // Keep showing the selection even if it is not currently readable;
            // this typically means the user needs to re-grant access.
            return label + ": " + (base != null ? base : "(SAF)") + " (permission required)";
        }
        return label + ": (not selected)";
    }

    private void pickKickstart() {
        maybeMigrateKickstartsPathPref();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_KICKSTART);
    }

    private void pickExtRom() {
        maybeMigrateKickstartsPathPref();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, REQ_IMPORT_EXT_ROM);
    }

    private static String joinSafTreeBase(String treeBase, String relPath) {
        if (treeBase == null) return null;
        String base = treeBase.trim();
        if (base.isEmpty()) return null;

        String rel = (relPath == null) ? "" : relPath.trim();
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (!rel.isEmpty()) rel = "/" + rel;

        return base + "::" + rel;
    }

    private void maybeMigrateKickstartsPathPref() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String cur = p.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null);
            if (cur == null || cur.trim().isEmpty()) return;

            String lower = cur.trim().toLowerCase(Locale.ROOT);
            // Only migrate the known-wrong legacy target: WHDboot's internal Kickstarts.
            if (!(lower.contains("whdboot") && lower.contains("kickstarts"))) return;

            String parentTree = p.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            String parentDir = p.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);

            String migrated = null;
            if (isContentUriString(parentTree)) {
                migrated = joinSafTreeBase(parentTree, "kickstarts");
            } else if (parentDir != null && !parentDir.trim().isEmpty()) {
                String parent = parentDir.trim();
                if (isContentUriString(parent)) {
                    migrated = joinSafTreeBase(parent, "kickstarts");
                } else if (ConfigStorage.isSafJoinedPath(parent)) {
                    ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(parent);
                    if (sp != null && sp.treeUri != null && !sp.treeUri.trim().isEmpty()) {
                        migrated = joinSafTreeBase(sp.treeUri, "kickstarts");
                    }
                } else {
                    migrated = new File(parent, "kickstarts").getAbsolutePath();
                }
            }

            if (migrated == null || migrated.trim().isEmpty()) return;
            if (migrated.trim().equals(cur.trim())) return;

            p.edit().putString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, migrated.trim()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ── Extension validation helpers ──────────────────────────────────────────

    private static boolean isValidFloppyExtension(String name) {
        if (name == null) return false;
        String ext = lowerExt(name);
        return "adf".equals(ext) || "zip".equals(ext);
    }

    private static boolean isValidHdfExtension(String name) {
        if (name == null) return false;
        return "hdf".equals(lowerExt(name));
    }

    private static boolean isValidCdExtension(String name) {
        if (name == null) return false;
        String ext = lowerExt(name);
        return "iso".equals(ext) || "cue".equals(ext) || "bin".equals(ext) || "chd".equals(ext);
    }

    private boolean validateAndRejectIfWrongExtension(Uri uri, String displayName, String[] allowed, String hint) {
        if (uri == null) return false;
        String name = displayName != null ? displayName : "";
        String ext = lowerExt(name);
        for (String a : allowed) {
            if (a.equals(ext)) return false; // extension is OK
        }
        Toast.makeText(this,
            "Wrong file type selected: ." + (ext.isEmpty() ? "?" : ext) + "\n" + hint,
            Toast.LENGTH_LONG).show();
        return true; // rejected
    }

    // ── First-run uae4arm folder setup ────────────────────────────────────────

    private static final String PREF_FIRST_RUN_DONE = "first_run_folder_done";

    private long getCurrentAppVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private long getCurrentAppLastUpdateTime() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private void enforceSetupOnAppUpdateIfNeeded() {
        if (mLaunchedFromEmulatorMenu) return;

        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long currentVersion = getCurrentAppVersionCode();
            long currentUpdateTime = getCurrentAppLastUpdateTime();
            if (currentVersion <= 0L && currentUpdateTime <= 0L) return;

            long lastSeenVersion = prefs.getLong(PREF_LAST_APP_VERSION_CODE, -1L);
            long lastSeenUpdateTime = prefs.getLong(PREF_LAST_APP_UPDATE_TIME, -1L);

            if (lastSeenVersion < 0L && lastSeenUpdateTime < 0L) {
                prefs.edit()
                    .putLong(PREF_LAST_APP_VERSION_CODE, currentVersion)
                    .putLong(PREF_LAST_APP_UPDATE_TIME, currentUpdateTime)
                    .apply();
                return;
            }

            boolean updatedByVersion = currentVersion > 0L && lastSeenVersion > 0L && currentVersion > lastSeenVersion;
            boolean updatedByInstallTime = currentUpdateTime > 0L && currentUpdateTime != lastSeenUpdateTime;

            if (updatedByVersion || updatedByInstallTime) {
                prefs.edit()
                    .putLong(PREF_LAST_APP_VERSION_CODE, currentVersion)
                    .putLong(PREF_LAST_APP_UPDATE_TIME, currentUpdateTime)
                    .putBoolean(PREF_WALKTHROUGH_COMPLETED, false)
                    .putBoolean(PREF_FIRST_RUN_DONE, false)
                    .putBoolean(PREF_PATHS_PARENT_PROMPT_SHOWN, false)
                    .putBoolean(PREF_REQUIRED_PATHS_PROMPT_SHOWN, false)
                    .apply();
            } else if (currentVersion != lastSeenVersion || currentUpdateTime != lastSeenUpdateTime) {
                prefs.edit()
                    .putLong(PREF_LAST_APP_VERSION_CODE, currentVersion)
                    .putLong(PREF_LAST_APP_UPDATE_TIME, currentUpdateTime)
                    .apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void maybeSetupFirstRunFolders() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_FIRST_RUN_DONE, false)) return;

        // Create the uae4arm sub-folder tree on external storage (best-effort).
        // If external storage is unavailable, we fall back to internal files dir.
        try {
            java.io.File externalBase = null;
            java.io.File[] externalDirs = getExternalFilesDirs(null);
            if (externalDirs != null) {
                for (java.io.File d : externalDirs) {
                    if (d != null && d.canWrite()) { externalBase = d; break; }
                }
            }
            if (externalBase == null) externalBase = getFilesDir();

            // We create the folder tree inside the public Downloads folder if available,
            // otherwise alongside app-private external files.
            java.io.File publicExternal = null;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 and below: can write to public storage without permission.
                java.io.File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                if (downloads != null && downloads.canWrite()) {
                    publicExternal = new java.io.File(downloads, "uae4arm");
                }
            }
            // Fallback: use app-private external storage (always accessible, no permission needed).
            java.io.File uaeBase = (publicExternal != null) ? publicExternal
                : new java.io.File(externalBase, "uae4arm");

            String[] subFolders = {"kickstarts", "disks", "harddrives", "conf", "configs", "cdroms", "savestates", "whdboot", "lha"};
            uaeBase.mkdirs();
            for (String sf : subFolders) {
                new java.io.File(uaeBase, sf).mkdirs();
            }

            LogUtil.i(TAG, "First-run: created uae4arm folder structure at: " + uaeBase.getAbsolutePath());
        } catch (Throwable ignored) {
        }

        // Show dialog explaining the setup and asking user to grant SAF access.
        try {
            new AlertDialog.Builder(this)
                .setTitle("Welcome to UAE4ARM 2026")
                .setMessage(
                    "The app has created a \u2018uae4arm\u2019 folder for your games and ROMs.\n\n" +
                    "Please grant permission to that folder so the app can read your Kickstart ROMs, " +
                    "floppy images, and hard drive files directly from storage.\n\n" +
                    "Tap \u201cGrant Access\u201d and navigate to the \u2018uae4arm\u2019 folder.")
                .setPositiveButton("Grant Access", (d, w) -> {
                    prefs.edit().putBoolean(PREF_FIRST_RUN_DONE, true).apply();
                    // Open the standard SAF tree picker so user can grant access to the uae4arm folder.
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    }
                    startActivityForResult(intent, REQ_FIRST_RUN_FOLDER);
                })
                .setNegativeButton("Skip", (d, w) -> {
                    prefs.edit().putBoolean(PREF_FIRST_RUN_DONE, true).apply();
                })
                .setCancelable(false)
                .show();
        } catch (Throwable ignored) {
            prefs.edit().putBoolean(PREF_FIRST_RUN_DONE, true).apply();
        }
    }

    private void pickDisk(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Floppy images: ADF/ZIP (provider support varies, extension checks enforce this).
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
        });
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_FLOPPIES_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, requestCode);
    }

    private void pickCdImage0() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // CD images: ISO/CUE/BIN/CHD (provider support varies, extension checks enforce this).
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/octet-stream",
            "application/x-cd-image",
            "application/x-iso9660-image"
        });
        maybeSetInitialUriFromUaePathPref(intent, UaeOptionKeys.UAE_PATH_CDROMS_DIR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(intent, REQ_IMPORT_CDIMAGE0);
    }

    private boolean hasDf0() {
        String df0Path = (mSelectedDf0Path != null && !mSelectedDf0Path.trim().isEmpty())
            ? mSelectedDf0Path
            : (mSelectedDf0 != null ? mSelectedDf0.getAbsolutePath() : null);
        return isReadableMediaPath(df0Path);
    }

    private boolean hasDf1() {
        String df1Path = (mSelectedDf1Path != null && !mSelectedDf1Path.trim().isEmpty())
            ? mSelectedDf1Path
            : (mSelectedDf1 != null ? mSelectedDf1.getAbsolutePath() : null);
        return isReadableMediaPath(df1Path);
    }

    private boolean hasCd0() {
        String cdPath = (mSelectedCd0Path != null && !mSelectedCd0Path.trim().isEmpty())
            ? mSelectedCd0Path
            : (mSelectedCd0 != null ? mSelectedCd0.getAbsolutePath() : null);
        return isReadableMediaPath(cdPath);
    }

    private boolean hasDh0() {
        return (mSelectedDh0Dir != null && mSelectedDh0Dir.exists() && mSelectedDh0Dir.isDirectory())
            || isReadableMediaPath(mSelectedDh0HdfPath)
            || (mSelectedDh0Hdf != null && mSelectedDh0Hdf.exists() && mSelectedDh0Hdf.isFile() && mSelectedDh0Hdf.canRead());
    }

    private boolean hasDh1() {
        return (mSelectedDh1Dir != null && mSelectedDh1Dir.exists() && mSelectedDh1Dir.isDirectory())
            || isReadableMediaPath(mSelectedDh1HdfPath)
            || (mSelectedDh1Hdf != null && mSelectedDh1Hdf.exists() && mSelectedDh1Hdf.isFile() && mSelectedDh1Hdf.canRead());
    }

    private File findDiskByPrefix(String prefix) {
        return findByPrefix(getInternalDisksDir(), prefix);
    }

    private void clearFile(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void refreshStatus() {
        boolean cdOnly = isCdOnlyModel();
        String dh0Mode = getDh0Mode();
        String baseModelId = getSelectedQsModelId();

        if (mKickStatus != null) {
            if (mSelectedKick != null && mSelectedKick.exists() && mSelectedKick.length() > 0) {
                mKickStatus.setText(labelWithSource("Kickstart", mSelectedKick, mKickSourceName));
            } else {
                mKickStatus.setText("Kickstart: (not selected) - will use AROS");
            }
        }

        if (mExtRomSection != null) {
            boolean showExt = "CD32".equalsIgnoreCase(baseModelId);
            mExtRomSection.setVisibility(showExt ? View.VISIBLE : View.GONE);
        }
        if (mExtStatus != null) {
            if (mSelectedExt != null && mSelectedExt.exists() && mSelectedExt.length() > 0) {
                mExtStatus.setText(labelWithSource("Ext ROM", mSelectedExt, mExtSourceName));
            } else {
                mExtStatus.setText("Ext ROM: (not selected)");
            }
        }

        if (mCd0Status != null) {
            if (hasCd0()) {
                String label = (mCd0SourceName != null && !mCd0SourceName.trim().isEmpty())
                    ? mCd0SourceName
                    : (mSelectedCd0 != null ? mSelectedCd0.getName() : "(CD image)");
                mCd0Status.setText("CD-ROM: " + label);
            } else {
                mCd0Status.setText("CD-ROM: (not selected)");
            }
        }

        if (mDf0Status != null) {
            // CD32/CDTV: no floppy drives by default.
            if (cdOnly) {
                mDf0Status.setVisibility(View.GONE);
            } else {
                // DF0 is always present (may be empty).
                mDf0Status.setVisibility(View.VISIBLE);
                mDf0Status.setText(labelWithSource("DF0", mSelectedDf0, mDf0SourceName, mSelectedDf0Path));
            }
        }
        if (mDf1Status != null) {
            mDf1Status.setText(labelWithSource("DF1", mSelectedDf1, mDf1SourceName, mSelectedDf1Path));
        }

        boolean showDf1Controls = !cdOnly && mDf1Added;
        if (mBtnAddDf1 != null) {
            mBtnAddDf1.setVisibility(!cdOnly ? View.VISIBLE : View.GONE);
        }
        if (mDf1Controls != null) {
            mDf1Controls.setVisibility(showDf1Controls ? View.VISIBLE : View.GONE);
        }

        View bootLabel = findViewById(R.id.txtBootMediumLabel);
        View btnAddDh0 = findViewById(R.id.btnAddDh0);
        View dh0Section = findViewById(R.id.dh0Section);

        View btnAddCd0 = findViewById(R.id.btnAddCd0);
        View cd0Section = findViewById(R.id.cd0Section);

        if (bootLabel != null) bootLabel.setVisibility(View.GONE);

        boolean showDh0 = !cdOnly && (mDh0Added || hasDh0());

        // "+ HD" is now an entry point to the storage modal (allow opening even when DH0 already added).
        if (btnAddDh0 != null) btnAddDh0.setVisibility(!cdOnly ? View.VISIBLE : View.GONE);
        if (dh0Section != null) dh0Section.setVisibility(showDh0 ? View.VISIBLE : View.GONE);

        boolean showDh1Controls = showDh0 && (mDh1Added || hasDh1());

        if (mDh0Status != null) {
            if (!showDh0) {
                mDh0Status.setVisibility(View.GONE);
            } else {
                mDh0Status.setVisibility(View.VISIBLE);
                if (DH0_MODE_DIR.equals(dh0Mode)) {
                    String label = (mDh0SourceName != null && !mDh0SourceName.trim().isEmpty()) ? mDh0SourceName : "(folder)";
                    if (mSelectedDh0Dir != null && mSelectedDh0Dir.exists() && mSelectedDh0Dir.isDirectory()) {
                        mDh0Status.setText("DH0 Folder: " + label);
                    } else {
                        mDh0Status.setText("DH0 Folder: (not selected)");
                    }
                } else {
                    mDh0Status.setText(labelWithSource("DH0 HDF", mSelectedDh0Hdf, mDh0SourceName, mSelectedDh0HdfPath));
                }
            }
        }

        if (mDh1Status != null) {
            if (!showDh1Controls) {
                mDh1Status.setVisibility(View.GONE);
            } else {
                mDh1Status.setVisibility(View.VISIBLE);
                String dh1Mode = getDh1Mode();
                if (DH1_MODE_DIR.equals(dh1Mode)) {
                    String label = (mDh1SourceName != null && !mDh1SourceName.trim().isEmpty()) ? mDh1SourceName : "(folder)";
                    if (mSelectedDh1Dir != null && mSelectedDh1Dir.exists() && mSelectedDh1Dir.isDirectory()) {
                        mDh1Status.setText("DH1 Folder: " + label);
                    } else {
                        mDh1Status.setText("DH1 Folder: (not selected)");
                    }
                } else {
                    mDh1Status.setText(labelWithSource("DH1 HDF", mSelectedDh1Hdf, mDh1SourceName, mSelectedDh1HdfPath));
                }
            }
        }

        View btnPickDf0 = findViewById(R.id.btnPickDf0);
        View btnClearDf0 = findViewById(R.id.btnClearDf0);
        View btnPickDf1 = findViewById(R.id.btnPickDf1);
        View btnClearDf1 = findViewById(R.id.btnClearDf1);
        if (btnPickDf0 != null) btnPickDf0.setVisibility(cdOnly ? View.GONE : View.VISIBLE);
        if (btnClearDf0 != null) btnClearDf0.setVisibility(cdOnly ? View.GONE : View.VISIBLE);
        if (btnPickDf1 != null) btnPickDf1.setVisibility(showDf1Controls ? View.VISIBLE : View.GONE);
        if (btnClearDf1 != null) btnClearDf1.setVisibility(showDf1Controls ? View.VISIBLE : View.GONE);

        boolean showCd0 = cdOnly || mCd0Added;
        // "+ CD" is now an entry point to the storage modal (always available).
        if (btnAddCd0 != null) btnAddCd0.setVisibility(View.VISIBLE);
        if (cd0Section != null) cd0Section.setVisibility(showCd0 ? View.VISIBLE : View.GONE);

        // When invoked from the emulator overlay, treat this screen as a "resume/restart" shell.
        // Do not re-sync boot media from the launcher UI, or we can overwrite runtime swaps.
        if (!mLaunchedFromEmulatorMenu) {
            syncBootMediumToUaePrefs();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read intent flags early so we can avoid flashing the full Quickstart UI.
        try {
            Intent it = getIntent();
            mLaunchedFromEmulatorMenu = it != null && it.getBooleanExtra(EXTRA_FROM_EMULATOR_MENU, false);
            mOpenMediaSwapperOnStart = it != null && it.getBooleanExtra(EXTRA_OPEN_MEDIA_SWAPPER, false);
            mOpenMediaSectionOnStart = it != null ? it.getStringExtra(EXTRA_OPEN_MEDIA_SECTION) : null;
        } catch (Throwable ignored) {
            mLaunchedFromEmulatorMenu = false;
            mOpenMediaSwapperOnStart = false;
            mOpenMediaSectionOnStart = null;
        }

        setContentView(R.layout.activity_bootstrap);

        // After each install/update, force setup once so SAF/storage can be reconfirmed.
        enforceSetupOnAppUpdateIfNeeded();

        // Check if first-time walkthrough should be shown
        if (!mLaunchedFromEmulatorMenu && savedInstanceState == null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (!prefs.getBoolean(PREF_WALKTHROUGH_COMPLETED, false)) {
                // Launch walkthrough for first-time users
                startActivity(new Intent(this, WalkthroughActivity.class));
                finish();
                return;
            }
        }

        // Keep bottom button rows (Start/Load/Save/Paths + Resume/Restart/Exit) above the
        // system navigation/gesture area on devices where bars overlay the content.
        //
        // Apply to the ScrollView/root so the scrollable content includes the inset and the
        // user can always scroll far enough to reveal the bottom buttons.
        UiInsets.applySystemBarsPaddingBottom(findViewById(R.id.bootstrapScroll));
        UiInsets.applySystemBarsPaddingBottom(findViewById(R.id.bootstrapRoot));
        // If the current config points at blocked /storage paths for HDF boot, prompt immediately.
        // This avoids "works on one device but not another" surprises.
        maybePromptForBlockedHdf0Path();

        // Cold-launch UX: start clean (DF0 only). Persist connected media only across Reset/Restart,
        // not across full launcher starts.
        if (!mLaunchedFromEmulatorMenu && savedInstanceState == null) {
            clearConnectedMediaForColdLauncherStart();
        }

        // If this activity was launched explicitly to show ONLY the swapper, hide the full
        // Quickstart UI so the user just sees a popup.
        if (mLaunchedFromEmulatorMenu && mOpenMediaSwapperOnStart) {
            try {
                View root = findViewById(R.id.bootstrapRoot);
                if (root != null) root.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        }

        if (mLaunchedFromEmulatorMenu) {
            try {
                SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                mDf0PathWhenOpenedFromEmu = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, ""));
                mDf0SigWhenOpenedFromEmu = floppySignatureFromPath(mDf0PathWhenOpenedFromEmu);
                mDf1PathWhenOpenedFromEmu = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, ""));
                mDf2PathWhenOpenedFromEmu = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, ""));
                mDf3PathWhenOpenedFromEmu = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, ""));
                mCd0PathWhenOpenedFromEmu = normalizeMediaPath(uaePrefs.getString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, ""));
                mDh0SigWhenOpenedFromEmu = dhSignatureFromUaePrefs(uaePrefs, false);
                mDh1SigWhenOpenedFromEmu = dhSignatureFromUaePrefs(uaePrefs, true);
                mDh2SigWhenOpenedFromEmu = dhSignatureFromUaePrefs(uaePrefs, 2);
                mDh3SigWhenOpenedFromEmu = dhSignatureFromUaePrefs(uaePrefs, 3);
                mDh4SigWhenOpenedFromEmu = dhSignatureFromUaePrefs(uaePrefs, 4);
                mFloppySpeedWhenOpenedFromEmu = uaePrefs.getInt(UaeOptionKeys.UAE_FLOPPY_SPEED, 100);
            } catch (Throwable ignored) {
                mDf0PathWhenOpenedFromEmu = "";
                mDf0SigWhenOpenedFromEmu = "";
                mDf1PathWhenOpenedFromEmu = "";
                mDf2PathWhenOpenedFromEmu = "";
                mDf3PathWhenOpenedFromEmu = "";
                mCd0PathWhenOpenedFromEmu = "";
                mDh0SigWhenOpenedFromEmu = "";
                mDh1SigWhenOpenedFromEmu = "";
                mDh2SigWhenOpenedFromEmu = "";
                mDh3SigWhenOpenedFromEmu = "";
                mDh4SigWhenOpenedFromEmu = "";
                mFloppySpeedWhenOpenedFromEmu = 100;
            }

            // Prefer emulator-provided current values for display/baseline.
            // This avoids showing "(not set)" when the emulator is actually running with a disk.
            try {
                Intent it = getIntent();
                String df0FromEmu = it != null ? it.getStringExtra(EXTRA_EMU_CURRENT_DF0_PATH) : null;
                String df1FromEmu = it != null ? it.getStringExtra(EXTRA_EMU_CURRENT_DF1_PATH) : null;
                String df2FromEmu = it != null ? it.getStringExtra(EXTRA_EMU_CURRENT_DF2_PATH) : null;
                String df3FromEmu = it != null ? it.getStringExtra(EXTRA_EMU_CURRENT_DF3_PATH) : null;

                if (df0FromEmu != null && !df0FromEmu.trim().isEmpty()) {
                    mDf0PathWhenOpenedFromEmu = normalizeMediaPath(df0FromEmu);
                    mDf0SigWhenOpenedFromEmu = floppySignatureFromPath(mDf0PathWhenOpenedFromEmu);
                    try {
                        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, mDf0PathWhenOpenedFromEmu)
                            .commit();
                    } catch (Throwable ignored2) {
                    }
                } else {
                    mDf0SigWhenOpenedFromEmu = floppySignatureFromPath(mDf0PathWhenOpenedFromEmu);
                }

                if (df1FromEmu != null && !df1FromEmu.trim().isEmpty()) {
                    mDf1PathWhenOpenedFromEmu = normalizeMediaPath(df1FromEmu);
                    try {
                        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, mDf1PathWhenOpenedFromEmu)
                            .commit();
                    } catch (Throwable ignored2) {
                    }
                }

                if (df2FromEmu != null && !df2FromEmu.trim().isEmpty()) {
                    mDf2PathWhenOpenedFromEmu = normalizeMediaPath(df2FromEmu);
                    try {
                        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, mDf2PathWhenOpenedFromEmu)
                            .commit();
                    } catch (Throwable ignored2) {
                    }
                }

                if (df3FromEmu != null && !df3FromEmu.trim().isEmpty()) {
                    mDf3PathWhenOpenedFromEmu = normalizeMediaPath(df3FromEmu);
                    try {
                        getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, mDf3PathWhenOpenedFromEmu)
                            .commit();
                    } catch (Throwable ignored2) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        mReturnBar = findViewById(R.id.quickstartReturnBar);
        mBtnResumeToEmu = findViewById(R.id.btnResumeToEmu);
        mBtnRestartEmu = findViewById(R.id.btnRestartEmu);

        if (mReturnBar != null) {
            // In swapper-popup mode we don't show the return bar; the dialog has Cancel/Resume.
            if (mLaunchedFromEmulatorMenu && mOpenMediaSwapperOnStart) {
                mReturnBar.setVisibility(View.GONE);
            } else {
                // Keep return controls only for emulator-menu launch; avoid duplicate Exit on main launcher.
                mReturnBar.setVisibility(mLaunchedFromEmulatorMenu ? View.VISIBLE : View.GONE);
            }
        }

        // Resume/Restart only make sense when invoked from the emulator overlay.
        if (!mLaunchedFromEmulatorMenu) {
            try {
                if (mBtnResumeToEmu != null) mBtnResumeToEmu.setVisibility(View.GONE);
                if (mBtnRestartEmu != null) mBtnRestartEmu.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        } else {
            try {
                if (mBtnResumeToEmu != null) mBtnResumeToEmu.setVisibility(View.VISIBLE);
                if (mBtnRestartEmu != null) mBtnRestartEmu.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
            }
        }

        if (mLaunchedFromEmulatorMenu) {
            if (mBtnResumeToEmu != null) {
                mBtnResumeToEmu.setOnClickListener(v -> {
                    int action = getPendingResumeActionFromPrefs();
                    if (action == 2) {
                        requestEmulatorRestartAndFinish();
                        return;
                    }
                    if (action == 1) {
                        // Do not reboot: hot-swap media and resume.
                        requestHotSwapToRunningEmulatorAndFinish();
                        return;
                    }
                    try { finish(); } catch (Throwable ignored) { }
                });
            }

            if (mBtnRestartEmu != null) {
                mBtnRestartEmu.setOnClickListener(v -> {
                    requestEmulatorRestartAndFinish();
                });
            }

            // Optional: open the media swapper immediately (used by the in-emulator "Swap" overlay button).
            if (mOpenMediaSwapperOnStart) {
                try {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try { showMediaSwapperDialog(mOpenMediaSectionOnStart); } catch (Throwable ignored) { }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        // Ensure Back behaves like Resume when invoked from the emulator overlay.
        if (mLaunchedFromEmulatorMenu) {
            try {
                // no-op: onBackPressed override is below (kept near end of file in some revisions)
            } catch (Throwable ignored) {
            }
        }

        Button btnConfigs = findViewById(R.id.btnConfigs);
        if (btnConfigs != null) {
            btnConfigs.setOnClickListener(v -> {
                Intent i = new Intent(this, ConfigManagerActivity.class);
                if (mLaunchedFromEmulatorMenu) {
                    i.putExtra(EXTRA_FROM_EMULATOR_MENU, true);
                }
                startActivity(i);
            });
        }

        Button btnHelp = findViewById(R.id.btnHelp);
        if (btnHelp != null) btnHelp.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));

        Button btnSetup = findViewById(R.id.btnSetup);
        if (btnSetup != null) {
            btnSetup.setOnClickListener(v -> {
                Intent i = new Intent(this, WalkthroughActivity.class);
                i.putExtra(WalkthroughActivity.EXTRA_FORCE_WALKTHROUGH, true);
                startActivity(i);
            });
        }

        // Quick access buttons in the Model panel.
        Button btnModelCpuFpu = findViewById(R.id.btnModelCpuFpu);
        if (btnModelCpuFpu != null) btnModelCpuFpu.setOnClickListener(v -> startActivity(new Intent(this, CpuFpuOptionsActivity.class)));

        Button btnModelChipset = findViewById(R.id.btnModelChipset);
        if (btnModelChipset != null) btnModelChipset.setOnClickListener(v -> startActivity(new Intent(this, ChipsetOptionsActivity.class)));

        Button btnModelRam = findViewById(R.id.btnModelRam);
        if (btnModelRam != null) btnModelRam.setOnClickListener(v -> startActivity(new Intent(this, MemoryOptionsActivity.class)));

        Button btnModelSound = findViewById(R.id.btnModelSound);
        if (btnModelSound != null) btnModelSound.setOnClickListener(v -> startActivity(new Intent(this, SoundOptionsActivity.class)));

        Button btnModelInput = findViewById(R.id.btnModelInput);
        if (btnModelInput != null) btnModelInput.setOnClickListener(v -> startActivity(new Intent(this, InputOptionsActivity.class)));

        mBtnExitTop = findViewById(R.id.btnExitTop);
        if (mBtnExitTop != null) {
            mBtnExitTop.setOnClickListener(v -> {
                quitAppFully();
            });
        }

        // Main Quickstart Exit button removed - exit is now via the return bar only

        mKickStatus = findViewById(R.id.txtKickStatus);
        mExtRomSection = findViewById(R.id.extRomSection);
        mExtStatus = findViewById(R.id.txtExtStatus);
        mCd0Status = findViewById(R.id.txtCd0Status);
        mDf0Status = findViewById(R.id.txtDf0Status);
        mDf1Status = findViewById(R.id.txtDf1Status);
        mDh0Status = findViewById(R.id.txtDh0Status);
        mDh1Status = findViewById(R.id.txtDh1Status);

        // The inline ADF/Clear buttons are intentionally hidden; use the DF popup instead.
        if (mDf0Status != null) {
            mDf0Status.setOnClickListener(v -> showMediaSwapperDialog(MEDIA_SECTION_DF));
        }
        if (mDf1Status != null) {
            mDf1Status.setOnClickListener(v -> showMediaSwapperDialog(MEDIA_SECTION_DF));
        }

        mDf1Controls = findViewById(R.id.df1Controls);
        mBtnAddDf1 = findViewById(R.id.btnAddDf1);
        mBtnAddDh0 = findViewById(R.id.btnAddDh0);
        mCd0Section = findViewById(R.id.cd0Section);
        mBtnAddCd0 = findViewById(R.id.btnAddCd0);

        // WHDLoad functionality now handled by WHDBooterActivity

        // WHDBooter dedicated button - launches the new WHDBooter screen
        View btnWHDBooter = findViewById(R.id.btnWHDBooter);
        if (btnWHDBooter != null) {
            btnWHDBooter.setOnClickListener(v -> {
                Intent i = new Intent(this, WHDBooterActivity.class);
                startActivity(i);
            });
        }

        // When invoked from the emulator MENU overlay, do not allow direct media edits in-place.
        // Users must intentionally open the separate Media Swapper UI.
        if (mLaunchedFromEmulatorMenu) {
            disableDirectMediaEditingControls();
        }

        mQsModelSpinner = findViewById(R.id.spinnerQsModel);
        mQsConfigSpinner = findViewById(R.id.spinnerQsConfig);
        mQsConfigFull = findViewById(R.id.txtQsConfigFull);
        mBtnKickMap = findViewById(R.id.btnKickMap);
        if (mBtnKickMap != null) {
            mBtnKickMap.setOnClickListener(v -> showKickstartMapDialog());
        }

        String[] modelLabels = new String[QS_MODELS.length];
        for (int i = 0; i < QS_MODELS.length; i++) {
            modelLabels[i] = QS_MODELS[i].label;
        }

        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            modelLabels
        );
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mQsModelSpinner.setAdapter(modelAdapter);

        SharedPreferences launcherPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String prefsModelId = launcherPrefs.getString(PREF_QS_MODEL, "A500");
        int cfgIdx = launcherPrefs.getInt(PREF_QS_CONFIG, 0);
        boolean migratedQuickstartPrefs = false;

        // Migrate old RTG-as-model IDs (e.g. A1200_RTG[_JIT]) into base model + RTG config mode.
        try {
            if (prefsModelId != null) {
                String raw = prefsModelId.trim().toUpperCase(Locale.ROOT);
                boolean jit = raw.endsWith("_RTG_JIT");
                boolean rtg = jit || raw.endsWith("_RTG");
                if (rtg) {
                    String base = raw.replace("_RTG_JIT", "").replace("_RTG", "");
                    prefsModelId = base;
                    cfgIdx = encodeConfigWithRtg(0, jit ? RTG_MODE_JIT : RTG_MODE_ON);
                    migratedQuickstartPrefs = true;
                }
            }
        } catch (Throwable ignored) {
        }

        // Migrate the previous RTG checkbox/spinner state into RTG config mode.
        try {
            boolean legacyRtg = launcherPrefs.getBoolean(PREF_QS_RTG_ENABLED, false);
            if (legacyRtg) {
                String legacyBase = launcherPrefs.getString(PREF_QS_RTG_MODEL, "A1200");
                boolean legacyJit = launcherPrefs.getBoolean(PREF_QS_RTG_JIT, false);
                String base = (legacyBase == null || legacyBase.trim().isEmpty()) ? "A1200" : legacyBase.trim().toUpperCase(Locale.ROOT);
                prefsModelId = base;
                cfgIdx = encodeConfigWithRtg(0, legacyJit ? RTG_MODE_JIT : RTG_MODE_ON);
                migratedQuickstartPrefs = true;
            }
        } catch (Throwable ignored) {
        }

        if (migratedQuickstartPrefs) {
            launcherPrefs
                .edit()
                .putString(PREF_QS_MODEL, prefsModelId)
                .putInt(PREF_QS_CONFIG, cfgIdx)
                .remove(PREF_QS_RTG_ENABLED)
                .remove(PREF_QS_RTG_MODEL)
                .remove(PREF_QS_RTG_JIT)
                .apply();
        }

        int modelIdx = indexOfModelId(prefsModelId, 0);
        String dh0Mode = launcherPrefs.getString(PREF_DH0_MODE, DH0_MODE_HDF);
        String dh1Mode = launcherPrefs.getString(PREF_DH1_MODE, DH1_MODE_HDF);

        mDf1Added = launcherPrefs.getBoolean(PREF_SHOW_DF1, false);
        mDf2Added = launcherPrefs.getBoolean(PREF_SHOW_DF2, false);
        mDf3Added = launcherPrefs.getBoolean(PREF_SHOW_DF3, false);

        mDh1Added = launcherPrefs.getBoolean(PREF_SHOW_DH1, false);
        mDh2Added = launcherPrefs.getBoolean(PREF_SHOW_DH2, false);
        mDh3Added = launcherPrefs.getBoolean(PREF_SHOW_DH3, false);
        mDh4Added = launcherPrefs.getBoolean(PREF_SHOW_DH4, false);

        mDh0Added = launcherPrefs.getBoolean(PREF_SHOW_DH0, false);
        mCd0Added = launcherPrefs.getBoolean(PREF_SHOW_CD0, false);

        // Repair state if an internal imported DH0 hardfile exists but prefs got cleared.
        // This situation can make it look like "no HDF loads" even though the file is present.
        try {
            repairDh0PrefsFromInternalImportIfPossible(launcherPrefs);
        } catch (Throwable ignored) {
        }

        mQsModelSpinner.setSelection(modelIdx);
        updateConfigSpinnerForModel(modelIdx, cfgIdx);

        if (modelIdx >= 0 && modelIdx < QS_MODELS.length) {
            mLastSelectedModelCliId = QS_MODELS[modelIdx].cliId;
        }



        mQsModelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (mSuppressUiCallbacks) return;

                String prevModelCli = mLastSelectedModelCliId;
                String nextModelCli = (position >= 0 && position < QS_MODELS.length) ? QS_MODELS[position].cliId : null;
                boolean modelChanged = prevModelCli == null || nextModelCli == null || !prevModelCli.equalsIgnoreCase(nextModelCli);

                int prevCfg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_QS_CONFIG, 0);
                updateConfigSpinnerForModel(position, prevCfg);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_QS_MODEL, QS_MODELS[position].prefsId)
                    .apply();

                // Leaving CD32/CDTV: do not keep the CD image mounted.
                try {
                    String prev = mLastSelectedModelCliId;
                    String next = QS_MODELS[position].cliId;
                    boolean prevCdOnly = prev != null && ("CD32".equalsIgnoreCase(prev) || "CDTV".equalsIgnoreCase(prev));
                    boolean nextCdOnly = next != null && ("CD32".equalsIgnoreCase(next) || "CDTV".equalsIgnoreCase(next));
                    if (prevCdOnly && !nextCdOnly) {
                        clearCd0Selection();
                        mCd0Added = false;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_CD0, false).apply();
                    }
                } catch (Throwable ignored) {
                }

                enforceMediaExclusivityForModel(QS_MODELS[position].cliId);

                mLastSelectedModelCliId = QS_MODELS[position].cliId;

                // If we are not on CD32, ensure Extended ROM doesn't linger from a previous CD32 setup.
                if (!"CD32".equalsIgnoreCase(QS_MODELS[position].cliId)) {
                    clearExtRomSelection();
                }

                applyKickstartAutoForCurrentModel(modelChanged);
                syncQuickstartToUaePrefs();
                refreshStatus();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        mQsConfigSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (mSuppressUiCallbacks) return;

                try {
                    if (mQsConfigFull != null) {
                        Object item = parent.getItemAtPosition(position);
                        mQsConfigFull.setText(item != null ? String.valueOf(item) : "");
                    }
                } catch (Throwable ignored) {
                }

                QsModel m = getSelectedQsModel();
                boolean supportsRtg = m != null && supportsRtgForModelId(m.cliId);
                int baseIdx = supportsRtg ? (position / RTG_VARIANTS_PER_CONFIG) : position;
                int rtgMode = supportsRtg ? (position % RTG_VARIANTS_PER_CONFIG) : RTG_MODE_OFF;
                int encoded = encodeConfigWithRtg(baseIdx, rtgMode);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_QS_CONFIG, encoded)
                    .apply();
                syncQuickstartToUaePrefs();
                refreshStatus();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Some devices/themes render the Spinner dropdown unusably (only one row / greyed).
        // Use a dialog picker instead and keep the spinner as a display for the current choice.
        try {
            mQsConfigSpinner.setOnTouchListener((v, ev) -> {
                if (ev != null && ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                    showConfigPickerDialog();
                }
                return true;
            });
            mQsConfigSpinner.setOnKeyListener((v, keyCode, event) -> {
                if (event == null) return false;
                if (event.getAction() != android.view.KeyEvent.ACTION_UP) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    showConfigPickerDialog();
                    return true;
                }
                return false;
            });
        } catch (Throwable ignored) {
        }

        // Restore existing internal selections if present.
        mSelectedKick = findByPrefix(getInternalRomsDir(), INTERNAL_KICKSTART_PREFIX);
        mSelectedExt = findByPrefix(getInternalRomsDir(), INTERNAL_EXT_ROM_PREFIX);

        loadSourceNames();
    applyKickstartAutoForCurrentModel(false);

        // Restore imported disks if present, but do NOT auto-enable optional media.
        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        BootstrapSelectionUtils.FileOrUri cd0Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);
        if (cd0Sel.uri != null && canReadContentUri(cd0Sel.uri)) {
            mSelectedCd0 = null;
            mSelectedCd0Path = cd0Sel.uri;
        } else if (cd0Sel.file != null) {
            mSelectedCd0 = cd0Sel.file;
            mSelectedCd0Path = null;
        }

        // Prefer DF0/DF1 stored in UAE prefs (so configs can restore media).
        BootstrapSelectionUtils.FileOrUri df0Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_DF0_PATH);
        if (df0Sel.uri != null && canReadContentUri(df0Sel.uri)) {
            mSelectedDf0 = null;
            mSelectedDf0Path = df0Sel.uri;
        } else if (df0Sel.file != null) {
            mSelectedDf0 = df0Sel.file;
            mSelectedDf0Path = null;
        }
        if (mSelectedDf0 == null && mSelectedDf0Path == null) {
            mSelectedDf0 = findDiskByPrefix("df0");
        }

        BootstrapSelectionUtils.FileOrUri df1Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_DF1_PATH);
        if (df1Sel.uri != null && canReadContentUri(df1Sel.uri)) {
            mSelectedDf1 = null;
            mSelectedDf1Path = df1Sel.uri;
        } else if (df1Sel.file != null) {
            mSelectedDf1 = df1Sel.file;
            mSelectedDf1Path = null;
        }

        // DF1 remains hidden until the user explicitly adds it.

        // Restore DH0 selection (HDF or folder) if present, but do NOT auto-enable/mount.
        BootstrapSelectionUtils.FileOrUri hdf0Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF0_PATH);
        if (hdf0Sel.uri != null && canReadContentUri(hdf0Sel.uri)) {
            mSelectedDh0Hdf = null;
            mSelectedDh0HdfPath = hdf0Sel.uri;
        } else if (hdf0Sel.file != null) {
            mSelectedDh0Hdf = hdf0Sel.file;
            mSelectedDh0HdfPath = null;
        }
        String dir0Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, null);
        if (dir0Path != null && !dir0Path.trim().isEmpty()) {
            File f = new File(dir0Path.trim());
            if (f.exists() && f.isDirectory() && f.canRead()) {
                mSelectedDh0Dir = f;
            }
        }

        // Restore DH1 selection (HDF or folder) if present, but do NOT auto-enable/mount.
        BootstrapSelectionUtils.FileOrUri hdf1Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF1_PATH);
        if (hdf1Sel.uri != null && canReadContentUri(hdf1Sel.uri)) {
            mSelectedDh1Hdf = null;
            mSelectedDh1HdfPath = hdf1Sel.uri;
        } else if (hdf1Sel.file != null) {
            mSelectedDh1Hdf = hdf1Sel.file;
            mSelectedDh1HdfPath = null;
        }
        String dir1Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, null);
        if (dir1Path != null && !dir1Path.trim().isEmpty()) {
            File f = new File(dir1Path.trim());
            if (f.exists() && f.isDirectory() && f.canRead()) {
                mSelectedDh1Dir = f;
            }
        }
        // DH1 remains hidden until the user explicitly adds it.

        // Restore DH2/DH3/DH4 selection if present, but do NOT auto-enable/mount.
        BootstrapSelectionUtils.FileOrUri hdf2Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF2_PATH);
        if (hdf2Sel.uri != null && canReadContentUri(hdf2Sel.uri)) {
            mSelectedDh2Hdf = null;
            mSelectedDh2HdfPath = hdf2Sel.uri;
        } else if (hdf2Sel.file != null) {
            mSelectedDh2Hdf = hdf2Sel.file;
            mSelectedDh2HdfPath = null;
        }
        String dir2Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR2_PATH, null);
        if (dir2Path != null && !dir2Path.trim().isEmpty()) {
            File f = new File(dir2Path.trim());
            if (f.exists() && f.isDirectory() && f.canRead()) {
                mSelectedDh2Dir = f;
            }
        }

        BootstrapSelectionUtils.FileOrUri hdf3Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF3_PATH);
        if (hdf3Sel.uri != null && canReadContentUri(hdf3Sel.uri)) {
            mSelectedDh3Hdf = null;
            mSelectedDh3HdfPath = hdf3Sel.uri;
        } else if (hdf3Sel.file != null) {
            mSelectedDh3Hdf = hdf3Sel.file;
            mSelectedDh3HdfPath = null;
        }
        String dir3Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR3_PATH, null);
        if (dir3Path != null && !dir3Path.trim().isEmpty()) {
            File f = new File(dir3Path.trim());
            if (f.exists() && f.isDirectory() && f.canRead()) {
                mSelectedDh3Dir = f;
            }
        }

        BootstrapSelectionUtils.FileOrUri hdf4Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF4_PATH);
        if (hdf4Sel.uri != null && canReadContentUri(hdf4Sel.uri)) {
            mSelectedDh4Hdf = null;
            mSelectedDh4HdfPath = hdf4Sel.uri;
        } else if (hdf4Sel.file != null) {
            mSelectedDh4Hdf = hdf4Sel.file;
            mSelectedDh4HdfPath = null;
        }
        String dir4Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR4_PATH, null);
        if (dir4Path != null && !dir4Path.trim().isEmpty()) {
            File f = new File(dir4Path.trim());
            if (f.exists() && f.isDirectory() && f.canRead()) {
                mSelectedDh4Dir = f;
            }
        }

        // If this is an upgrade from an older build, source-name prefs may be missing.
        // Derive display names from the stable internal filename when possible.
        maybeDeriveAndPersistMissingSourceNames();

        // Kickstart button is now in Model section, no hidden buttons to manage

        Button btnPickDf0 = findViewById(R.id.btnPickDf0);
        btnPickDf0.setOnClickListener(v -> pickDisk(REQ_IMPORT_DF0));

        Button btnPickDf1 = findViewById(R.id.btnPickDf1);
        btnPickDf1.setOnClickListener(v -> pickDisk(REQ_IMPORT_DF1));

        if (mBtnAddDf1 != null) {
            mBtnAddDf1.setOnClickListener(v -> showMediaSwapperDialog(MEDIA_SECTION_DF));
        }

        if (mBtnAddDh0 != null) {
            mBtnAddDh0.setOnClickListener(v -> showMediaSwapperDialog(MEDIA_SECTION_HD));
        }

        if (mBtnAddCd0 != null) {
            mBtnAddCd0.setOnClickListener(v -> showMediaSwapperDialog(MEDIA_SECTION_CD));
        }

        // Ext ROM import/clear is intentionally hidden. Kickstart Map is the only entry point.
        try {
            View btnPickExtRom = findViewById(R.id.btnPickExtRom);
            if (btnPickExtRom != null) btnPickExtRom.setVisibility(View.GONE);
        } catch (Throwable ignored) { }
        try {
            View btnClearExtRom = findViewById(R.id.btnClearExtRom);
            if (btnClearExtRom != null) btnClearExtRom.setVisibility(View.GONE);
        } catch (Throwable ignored) { }

        Button btnPickCd0 = findViewById(R.id.btnPickCd0);
        if (btnPickCd0 != null) btnPickCd0.setOnClickListener(v -> pickCdImage0());

        Button btnClearCd0 = findViewById(R.id.btnClearCd0);
        if (btnClearCd0 != null) btnClearCd0.setOnClickListener(v -> {
            deleteRecursive(getInternalCd0Dir());
            mSelectedCd0 = null;
            mSelectedCd0Path = null;
            mCd0SourceName = null;
            mCd0Added = false;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_CD0, false).apply();
            getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH)
                .apply();
            saveSourceNames();
            refreshStatus();
        });

        Button btnClearDf0 = findViewById(R.id.btnClearDf0);
        btnClearDf0.setOnClickListener(v -> {
            clearByPrefix(getInternalDisksDir(), "df0");
            // DF0 swaps use a stable filename (disk.zip); clear it too.
            try {
                clearFile(new File(getInternalDisksDir(), "disk.zip"));
            } catch (Throwable ignored) {
            }
            mSelectedDf0 = null;
            mDf0SourceName = null;
            getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(UaeOptionKeys.UAE_DRIVE_DF0_PATH)
                .apply();
            saveSourceNames();
            refreshStatus();
        });

        Button btnClearDf1 = findViewById(R.id.btnClearDf1);
        btnClearDf1.setOnClickListener(v -> {
            clearByPrefix(getInternalDisksDir(), "df1");
            mSelectedDf1 = null;
            mSelectedDf1Path = null;
            mDf1SourceName = null;
            mDf1Added = false;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF1, false).apply();
            getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(UaeOptionKeys.UAE_DRIVE_DF1_PATH)
                .apply();
            saveSourceNames();
            refreshStatus();
        });

        // DF2/DF3 controls were removed from the simplified Quickstart UI.

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> startEmulator());

        // Show on app opening if Paths parent folder isn't set (or SAF permission is missing).
        maybePromptForPathsParentFolder();
        maybePromptForRequiredMediaPaths();

        // First-install: create uae4arm folder tree and request SAF permission.
        if (!mLaunchedFromEmulatorMenu && savedInstanceState == null) {
            maybeSetupFirstRunFolders();
        }

        refreshStatus();
        applyFeaturedGraphic();
    }

    @Override
    public void onBackPressed() {
        if (mLaunchedFromEmulatorMenu) {
            int action = getPendingResumeActionFromPrefs();
            if (action == 2) {
                requestEmulatorRestartAndFinish();
                return;
            }
            if (action == 1) {
                requestHotSwapToRunningEmulatorAndFinish();
                return;
            }
            try { finish(); } catch (Throwable ignored) { }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUiFromLauncherPrefs();
        syncMediaSelectionsFromUaePrefs();

        reconcileLauncherMediaVisibilityWithUaePrefs();

        maybePromptForPathsParentFolder();
        maybePromptForRequiredMediaPaths();
        maybePromptForBlockedHdf0Path();

        refreshStatus();
        applyFeaturedGraphic();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Defensive persistence: some device/ROM combos can skip or reorder spinner callbacks
        // around resume/recreate paths. Persist current selections to keep them stable.
        try {
            if (mQsModelSpinner == null || mQsConfigSpinner == null) return;

            int modelPos = mQsModelSpinner.getSelectedItemPosition();
            if (modelPos < 0 || modelPos >= QS_MODELS.length) return;

            int cfgPos = mQsConfigSpinner.getSelectedItemPosition();
            QsModel m = QS_MODELS[modelPos];
            boolean supportsRtg = m != null && supportsRtgForModelId(m.cliId);
            int baseIdx = supportsRtg ? (Math.max(cfgPos, 0) / RTG_VARIANTS_PER_CONFIG) : Math.max(cfgPos, 0);
            int rtgMode = supportsRtg ? (Math.max(cfgPos, 0) % RTG_VARIANTS_PER_CONFIG) : RTG_MODE_OFF;
            int encoded = encodeConfigWithRtg(baseIdx, rtgMode);

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_QS_MODEL, m.prefsId)
                .putInt(PREF_QS_CONFIG, encoded)
                .apply();
        } catch (Throwable ignored) {
        }
    }

    private void maybePromptForPathsParentFolder() {
        mPathsParentPromptShown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_PATHS_PARENT_PROMPT_SHOWN, false);
        if (mPathsParentPromptShown) return;

        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
        String parentTree = p.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
        String parentDir = p.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
        String conf = p.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null);
        String roms = p.getString(UaeOptionKeys.UAE_PATH_ROMS_DIR, null);
        String flops = p.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
        String cds = p.getString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, null);
        String hds = p.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null);

        boolean hasAnyParent = (parentTree != null && !parentTree.trim().isEmpty())
            || (parentDir != null && !parentDir.trim().isEmpty());

        boolean usingSaf = isContentUriString(parentTree)
            || ConfigStorage.isSafJoinedPath(conf)
            || ConfigStorage.isSafJoinedPath(roms)
            || ConfigStorage.isSafJoinedPath(flops)
            || ConfigStorage.isSafJoinedPath(cds)
            || ConfigStorage.isSafJoinedPath(hds);

        // If the user hasn't set any Paths parent folder at all, prompt on app open.
        if (!hasAnyParent) {
            mPathsParentPromptShown = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PATHS_PARENT_PROMPT_SHOWN, true)
                .apply();
            showReselectDialog(
                "Set Paths Parent Folder",
                "Paths parent folder is not set.\n\nPlease select a parent folder in Paths so configs and media can be accessed.",
                this::openPathsAndAutoPickSafParent
            );
            return;
        }

        // If not using SAF, nothing more to do here.
        if (!usingSaf) {
            // On newer Android versions, raw /storage paths can be unreadable even if configured.
            // If the configured config folder isn't accessible, prompt the user to use Paths
            // (ideally selecting a SAF parent folder) so configs can be saved/loaded.
            try {
                if (conf != null && !conf.trim().isEmpty() && !isContentUriString(conf) && !ConfigStorage.isSafJoinedPath(conf)) {
                    File confDir = new File(conf.trim());
                    boolean ok;
                    if (confDir.exists()) {
                        ok = confDir.isDirectory() && confDir.canRead() && confDir.canWrite();
                    } else {
                        ok = confDir.mkdirs() && confDir.isDirectory() && confDir.canRead() && confDir.canWrite();
                    }

                    if (!ok) {
                        mPathsParentPromptShown = true;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_PATHS_PARENT_PROMPT_SHOWN, true)
                            .apply();
                        showReselectDialog(
                            "Paths Folder Not Accessible",
                            "Your config folder (conf) is not readable/writable:\n\n" + conf.trim() + "\n\n" +
                                "This is commonly caused by Android storage restrictions.\n\n" +
                                "Open Paths and re-select a parent folder (SAF) or use Internal storage.",
                () -> startActivity(new Intent(this, PathsSimpleActivity.class))
                        );
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        // If we have no parent tree at all, or we lost persisted permission, the user must re-select.
        boolean hasParentTree = parentTree != null && !parentTree.trim().isEmpty() && isContentUriString(parentTree);
        boolean hasParentPerm = hasParentTree && hasPersistedReadPermission(parentTree) && hasPersistedWritePermission(parentTree);

        // Conf dir is the critical path for saving/loading configs.
        boolean confLooksSaf = ConfigStorage.isSafJoinedPath(conf);
        boolean confPermOk = !confLooksSaf
            || (hasPersistedReadPermissionForSafJoinedDir(conf) && hasPersistedWritePermissionForSafJoinedDir(conf));
        boolean confDirOk = !confLooksSaf || canResolveSafJoinedDir(conf);

        // If the conf subfolder is missing but the parent tree permission is
        // valid, try to create it now. This fixes fresh installs where the
        // filesystem mkdirs succeeded but DocumentFile.findFile() can't see
        // the folder until it has been created via the SAF API.
        if (confLooksSaf && !confDirOk && hasParentPerm) {
            confDirOk = tryEnsureSafJoinedDir(conf);
        }

        if (!hasParentTree || !hasParentPerm || !confPermOk || !confDirOk) {
            mPathsParentPromptShown = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PATHS_PARENT_PROMPT_SHOWN, true)
                .apply();
            String title = "Set Paths Parent Folder";
            String message;
            if (!hasParentTree) {
                message = "Your paths are configured to use Android Storage Access Framework (SAF), but no parent folder is selected.\n\nPlease select the parent folder in Paths so configs and media can be accessed.";
            } else if (!hasParentPerm) {
                message = "Permission for your selected Paths parent folder is missing or does not include write access.\n\nPlease re-select the parent folder in Paths to grant access again.";
            } else if (confLooksSaf && !confDirOk) {
                message = "The config folder (conf) cannot be accessed under the selected parent folder.\n\nOpen Paths and tap Apply/Save to let the app create the needed folders, or create 'conf' under the app folder in your file manager, then re-open Paths.";
            } else {
                message = "Some SAF paths cannot be accessed due to missing permissions (or write permission).\n\nPlease open Paths and re-select the parent folder.";
            }

            showReselectDialog(
                title,
                message,
                this::openPathsAndAutoPickSafParent
            );
        }
    }

    private static String defaultSubfolderForUaePathKey(String key) {
        if (UaeOptionKeys.UAE_PATH_CONF_DIR.equals(key)) return "conf";
        if (UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR.equals(key)) return "kickstarts";
        if (UaeOptionKeys.UAE_PATH_FLOPPIES_DIR.equals(key)) return "disks";
        if (UaeOptionKeys.UAE_PATH_CDROMS_DIR.equals(key)) return "cdroms";
        if (UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR.equals(key)) return "harddrives";
        if (UaeOptionKeys.UAE_PATH_LHA_DIR.equals(key)) return "lha";
        if (UaeOptionKeys.UAE_PATH_WHDBOOT_DIR.equals(key)) return "whdboot";
        if (UaeOptionKeys.UAE_PATH_SAVESTATES_DIR.equals(key)) return "savestates";
        if (UaeOptionKeys.UAE_PATH_SCREENS_DIR.equals(key)) return "screenshots";
        return null;
    }

    private String resolveConfiguredPathForKeyWithParentFallback(SharedPreferences uaePrefs, String key) {
        if (uaePrefs == null || key == null) return null;
        String configured = uaePrefs.getString(key, null);
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();

        String rel = defaultSubfolderForUaePathKey(key);
        if (rel == null || rel.trim().isEmpty()) return null;

        String parentTree = uaePrefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
        if (isContentUriString(parentTree)) {
            String joined = joinSafTreeBase(parentTree, rel);
            if (joined != null && !joined.trim().isEmpty()) return joined.trim();
        }

        String parentDir = uaePrefs.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
        if (parentDir != null && !parentDir.trim().isEmpty()) {
            String p = parentDir.trim();
            if (ConfigStorage.isSafJoinedPath(p)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(p);
                if (sp != null && sp.treeUri != null && !sp.treeUri.trim().isEmpty()) {
                    String joined = joinSafTreeBase(sp.treeUri, rel);
                    if (joined != null && !joined.trim().isEmpty()) return joined.trim();
                }
            } else if (isContentUriString(p)) {
                String joined = joinSafTreeBase(p, rel);
                if (joined != null && !joined.trim().isEmpty()) return joined.trim();
            } else {
                return new File(p, rel).getAbsolutePath();
            }
        }

        return null;
    }

    private boolean isSafPathReadable(String path) {
        String p = safeTrim(path);
        if (p.isEmpty()) return false;
        if (ConfigStorage.isSafJoinedPath(p)) {
            return hasPersistedReadPermissionForSafJoinedDir(p) && canResolveSafJoinedDir(p);
        }
        if (isContentUriString(p)) {
            return hasPersistedReadPermission(p);
        }
        return true;
    }

    private boolean isSafPathWritable(String path) {
        String p = safeTrim(path);
        if (p.isEmpty()) return false;
        if (ConfigStorage.isSafJoinedPath(p)) {
            return hasPersistedReadPermissionForSafJoinedDir(p)
                && hasPersistedWritePermissionForSafJoinedDir(p)
                && canResolveSafJoinedDir(p);
        }
        if (isContentUriString(p)) {
            return hasPersistedReadPermission(p) && hasPersistedWritePermission(p);
        }
        return true;
    }

    private void maybePromptForRequiredMediaPaths() {
        mRequiredPathsPromptShown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_REQUIRED_PATHS_PROMPT_SHOWN, false);
        if (mRequiredPathsPromptShown) return;

        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
        String floppies = resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_FLOPPIES_DIR);
        String lha = resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_LHA_DIR);
        String harddrives = resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        String conf = resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_CONF_DIR);

        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        String firstAutoPick = null;

        if (safeTrim(floppies).isEmpty()) {
            missing.add("Floppies");
            if (firstAutoPick == null) firstAutoPick = "floppies";
        }
        if (safeTrim(lha).isEmpty()) {
            missing.add("LHA");
            if (firstAutoPick == null) firstAutoPick = "lha";
        }
        if (safeTrim(harddrives).isEmpty()) {
            missing.add("Harddrives");
            if (firstAutoPick == null) firstAutoPick = "harddrives";
        }

        if (!missing.isEmpty()) {
            mRequiredPathsPromptShown = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_REQUIRED_PATHS_PROMPT_SHOWN, true)
                .apply();
            final String autoPick = firstAutoPick == null ? "conf" : firstAutoPick;
            showReselectDialog(
                "Set Required Paths",
                "Some required Paths are not set: " + android.text.TextUtils.join(", ", missing)
                    + "\n\nPlease open Paths and set these folders.",
                () -> openPathsAndAutoPick(autoPick)
            );
            return;
        }

        if (!isSafPathReadable(floppies) || !isSafPathReadable(lha) || !isSafPathReadable(harddrives) || !isSafPathWritable(conf)) {
            mRequiredPathsPromptShown = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_REQUIRED_PATHS_PROMPT_SHOWN, true)
                .apply();
            showReselectDialog(
                "Paths Permission Required",
                "One or more configured Paths no longer have valid SAF permissions.\n\n" +
                    "Open Paths and re-select the parent folder (or affected folders) to restore access.",
                this::openPathsAndAutoPickSafParent
            );
        }
    }

    private String getPathsQuickstartFlagLine() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String parentTree = p.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            String parentDir = p.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
            String conf = p.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null);

            boolean hasAnyParent = (parentTree != null && !parentTree.trim().isEmpty())
                || (parentDir != null && !parentDir.trim().isEmpty());
            if (!hasAnyParent) return "Paths: NOT SET";

            // SAF mode
            if (isContentUriString(parentTree) || ConfigStorage.isSafJoinedPath(conf)) {
                if (ConfigStorage.isSafJoinedPath(conf)) {
                    if (!hasPersistedReadPermissionForSafJoinedDir(conf)) return "Paths: permission required";
                    if (!canResolveSafJoinedDir(conf)) return "Paths: conf folder missing";
                }
                if (isContentUriString(parentTree) && !hasPersistedReadPermission(parentTree)) {
                    return "Paths: parent permission required";
                }
                return "Paths: SAF OK";
            }

            return "Paths: Local";
        } catch (Throwable ignored) {
        }
        return "Paths: (unknown)";
    }

    private void syncUiFromLauncherPrefs() {
        SharedPreferences launcherPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mSuppressUiCallbacks = true;
        try {
            String prefsModelId = launcherPrefs.getString(PREF_QS_MODEL, "A500");
            int cfgIdx = launcherPrefs.getInt(PREF_QS_CONFIG, 0);
            boolean migratedQuickstartPrefs = false;

            // Migrate old RTG-as-model IDs (e.g. A1200_RTG[_JIT]) into base model + RTG config mode.
            try {
                if (prefsModelId != null) {
                    String raw = prefsModelId.trim().toUpperCase(Locale.ROOT);
                    boolean jit = raw.endsWith("_RTG_JIT");
                    boolean rtg = jit || raw.endsWith("_RTG");
                    if (rtg) {
                        String base = raw.replace("_RTG_JIT", "").replace("_RTG", "");
                        prefsModelId = base;
                        cfgIdx = encodeConfigWithRtg(0, jit ? RTG_MODE_JIT : RTG_MODE_ON);
                        migratedQuickstartPrefs = true;
                    }
                }
            } catch (Throwable ignored) {
            }

            // Migrate the previous RTG checkbox/spinner state into RTG config mode.
            try {
                boolean legacyRtg = launcherPrefs.getBoolean(PREF_QS_RTG_ENABLED, false);
                if (legacyRtg) {
                    String legacyBase = launcherPrefs.getString(PREF_QS_RTG_MODEL, "A1200");
                    boolean legacyJit = launcherPrefs.getBoolean(PREF_QS_RTG_JIT, false);
                    String base = (legacyBase == null || legacyBase.trim().isEmpty()) ? "A1200" : legacyBase.trim().toUpperCase(Locale.ROOT);
                    prefsModelId = base;
                    cfgIdx = encodeConfigWithRtg(0, legacyJit ? RTG_MODE_JIT : RTG_MODE_ON);
                    migratedQuickstartPrefs = true;
                }
            } catch (Throwable ignored) {
            }

            if (migratedQuickstartPrefs) {
                launcherPrefs
                    .edit()
                    .putString(PREF_QS_MODEL, prefsModelId)
                    .putInt(PREF_QS_CONFIG, cfgIdx)
                    .remove(PREF_QS_RTG_ENABLED)
                    .remove(PREF_QS_RTG_MODEL)
                    .remove(PREF_QS_RTG_JIT)
                    .apply();
            }

            int modelIdx = indexOfModelId(prefsModelId, 0);
            String dh0Mode = launcherPrefs.getString(PREF_DH0_MODE, DH0_MODE_HDF);

            if (mQsModelSpinner != null) {
                int cur = mQsModelSpinner.getSelectedItemPosition();
                if (cur != modelIdx) mQsModelSpinner.setSelection(modelIdx);
                updateConfigSpinnerForModel(modelIdx, cfgIdx);
            }

            applyKickstartAutoForCurrentModel(false);
        } finally {
            mSuppressUiCallbacks = false;
        }

        // Update any user-facing source names that might have been loaded with the config.
        loadSourceNames();
    }

    private void syncMediaSelectionsFromUaePrefs() {
        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        // ROMs
        try {
            BootstrapSelectionUtils.FileOrUri kickSel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_ROM_KICKSTART_FILE);
            // Preserve existing behavior: only update when it resolves to a valid local file.
            if (kickSel.file != null) mSelectedKick = kickSel.file;
        } catch (Throwable ignored) {
        }

        try {
            BootstrapSelectionUtils.FileOrUri extSel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_ROM_EXT_FILE);
            // Preserve existing behavior: only update when it resolves to a valid local file.
            if (extSel.file != null) mSelectedExt = extSel.file;
        } catch (Throwable ignored) {
        }

        // CD
        try {
            BootstrapSelectionUtils.FileOrUri cd0Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);
            mSelectedCd0 = cd0Sel.file;
            mSelectedCd0Path = cd0Sel.uri;

            if (mSelectedCd0Path != null) {
                mCd0SourceName = deriveSourceNameFromUriIfMissing(mCd0SourceName, mSelectedCd0Path);
            }
        } catch (Throwable ignored) {
        }

        // Floppies + DH0
        syncFloppySelectionsFromUaePrefs();

        try {
            BootstrapSelectionUtils.FileOrUri hdf0Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF0_PATH);
            mSelectedDh0Hdf = hdf0Sel.file;
            mSelectedDh0HdfPath = hdf0Sel.uri;

            if (mSelectedDh0HdfPath != null) {
                mDh0SourceName = deriveSourceNameFromUriIfMissing(mDh0SourceName, mSelectedDh0HdfPath);
            }
        } catch (Throwable ignored) {
        }

        try {
            String dir0Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, null);
            if (dir0Path != null && !dir0Path.trim().isEmpty()) {
                File f = new File(dir0Path.trim());
                if (f.exists() && f.isDirectory() && f.canRead()) {
                    mSelectedDh0Dir = f;
                } else {
                    mSelectedDh0Dir = null;
                }
            } else {
                mSelectedDh0Dir = null;
            }
        } catch (Throwable ignored) {
        }

        // DH1 (optional)
        try {
            BootstrapSelectionUtils.FileOrUri hdf1Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF1_PATH);
            mSelectedDh1Hdf = hdf1Sel.file;
            mSelectedDh1HdfPath = hdf1Sel.uri;

            if (mSelectedDh1HdfPath != null) {
                mDh1SourceName = deriveSourceNameFromUriIfMissing(mDh1SourceName, mSelectedDh1HdfPath);
            }
        } catch (Throwable ignored) {
        }

        try {
            String dir1Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, null);
            if (dir1Path != null && !dir1Path.trim().isEmpty()) {
                File f = new File(dir1Path.trim());
                if (f.exists() && f.isDirectory() && f.canRead()) {
                    mSelectedDh1Dir = f;
                } else {
                    mSelectedDh1Dir = null;
                }
            } else {
                mSelectedDh1Dir = null;
            }
        } catch (Throwable ignored) {
        }

        // DH2 (optional)
        try {
            BootstrapSelectionUtils.FileOrUri hdf2Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF2_PATH);
            mSelectedDh2Hdf = hdf2Sel.file;
            mSelectedDh2HdfPath = hdf2Sel.uri;

            if (mSelectedDh2HdfPath != null) {
                mDh2SourceName = deriveSourceNameFromUriIfMissing(mDh2SourceName, mSelectedDh2HdfPath);
            }
        } catch (Throwable ignored) {
        }

        try {
            String dir2Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR2_PATH, null);
            if (dir2Path != null && !dir2Path.trim().isEmpty()) {
                File f = new File(dir2Path.trim());
                if (f.exists() && f.isDirectory() && f.canRead()) {
                    mSelectedDh2Dir = f;
                } else {
                    mSelectedDh2Dir = null;
                }
            } else {
                mSelectedDh2Dir = null;
            }
        } catch (Throwable ignored) {
        }

        // DH3 (optional)
        try {
            BootstrapSelectionUtils.FileOrUri hdf3Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF3_PATH);
            mSelectedDh3Hdf = hdf3Sel.file;
            mSelectedDh3HdfPath = hdf3Sel.uri;

            if (mSelectedDh3HdfPath != null) {
                mDh3SourceName = deriveSourceNameFromUriIfMissing(mDh3SourceName, mSelectedDh3HdfPath);
            }
        } catch (Throwable ignored) {
        }

        try {
            String dir3Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR3_PATH, null);
            if (dir3Path != null && !dir3Path.trim().isEmpty()) {
                File f = new File(dir3Path.trim());
                if (f.exists() && f.isDirectory() && f.canRead()) {
                    mSelectedDh3Dir = f;
                } else {
                    mSelectedDh3Dir = null;
                }
            } else {
                mSelectedDh3Dir = null;
            }
        } catch (Throwable ignored) {
        }

        // DH4 (optional)
        try {
            BootstrapSelectionUtils.FileOrUri hdf4Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_HDF4_PATH);
            mSelectedDh4Hdf = hdf4Sel.file;
            mSelectedDh4HdfPath = hdf4Sel.uri;

            if (mSelectedDh4HdfPath != null) {
                mDh4SourceName = deriveSourceNameFromUriIfMissing(mDh4SourceName, mSelectedDh4HdfPath);
            }
        } catch (Throwable ignored) {
        }

        try {
            String dir4Path = p.getString(UaeOptionKeys.UAE_DRIVE_DIR4_PATH, null);
            if (dir4Path != null && !dir4Path.trim().isEmpty()) {
                File f = new File(dir4Path.trim());
                if (f.exists() && f.isDirectory() && f.canRead()) {
                    mSelectedDh4Dir = f;
                } else {
                    mSelectedDh4Dir = null;
                }
            } else {
                mSelectedDh4Dir = null;
            }
        } catch (Throwable ignored) {
        }

        // Do not auto-enable DH1 on startup just because a path exists.
        // The Quickstart UI (Add DH1) is authoritative.

        // Kickstart Map should be authoritative over any stale UAE prefs values.
        applyKickstartAutoForCurrentModel(false);
    }

    private void reconcileLauncherMediaVisibilityWithUaePrefs() {
        try {
            if (isCdOnlyModel()) return;

            SharedPreferences uaePrefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            UaeDrivePrefs.HardDriveConfig dhCfg = UaeDrivePrefs.readHardDriveConfig(uaePrefs);

            boolean dh0Configured = dhCfg.dh0Configured;
            boolean dh1Configured = dhCfg.dh1Configured;
            boolean dh2Configured = dhCfg.dh2Configured;
            boolean dh3Configured = dhCfg.dh3Configured;
            boolean dh4Configured = dhCfg.dh4Configured;

            SharedPreferences launcherPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            // If DH1 is configured, the hard-drive section must be visible.
            if ((dh1Configured || dh2Configured || dh3Configured || dh4Configured) && !mDh0Added) {
                mDh0Added = true;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH0, true).apply();
            }

            if (dh2Configured && !mDh2Added) {
                mDh2Added = true;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH2, true).apply();
            }
            if (dh3Configured && !mDh3Added) {
                mDh3Added = true;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH3, true).apply();
            }
            if (dh4Configured && !mDh4Added) {
                mDh4Added = true;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH4, true).apply();
            }

            // If the UI says "added" but there is no configured media at all, hide it.
            if (mDh0Added && !dh0Configured && !dh1Configured && !dh2Configured && !dh3Configured && !dh4Configured) {
                mDh0Added = false;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH0, false).apply();
            }
            if (mDh1Added && !dh1Configured) {
                mDh1Added = false;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH1, false).apply();
            }

            if (mDh2Added && !dh2Configured) {
                mDh2Added = false;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH2, false).apply();
            }
            if (mDh3Added && !dh3Configured) {
                mDh3Added = false;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH3, false).apply();
            }
            if (mDh4Added && !dh4Configured) {
                mDh4Added = false;
                launcherPrefs.edit().putBoolean(PREF_SHOW_DH4, false).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void syncFloppySelectionsFromUaePrefs() {
        SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        BootstrapSelectionUtils.FileOrUri df0Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_DF0_PATH);
        File newDf0 = df0Sel.file;
        String newDf0Path = df0Sel.uri;
        if (newDf0 == null && newDf0Path == null) newDf0 = findDiskByPrefix("df0");

        BootstrapSelectionUtils.FileOrUri df1Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_DF1_PATH);
        File newDf1 = df1Sel.file;
        String newDf1Path = df1Sel.uri;
        // Do not auto-select a DF1 disk; DF1 should only appear if the user/config explicitly sets it.

        BootstrapSelectionUtils.FileOrUri df2Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_DF2_PATH);
        File newDf2 = df2Sel.file;
        String newDf2Path = df2Sel.uri;

        BootstrapSelectionUtils.FileOrUri df3Sel = BootstrapSelectionUtils.readFileOrContentUriPref(p, UaeOptionKeys.UAE_DRIVE_DF3_PATH);
        File newDf3 = df3Sel.file;
        String newDf3Path = df3Sel.uri;

        boolean changed = (mSelectedDf0 == null ? newDf0 != null : !mSelectedDf0.equals(newDf0))
            || (mSelectedDf1 == null ? newDf1 != null : !mSelectedDf1.equals(newDf1))
            || (mSelectedDf2 == null ? newDf2 != null : !mSelectedDf2.equals(newDf2))
            || (mSelectedDf3 == null ? newDf3 != null : !mSelectedDf3.equals(newDf3))
            || (mSelectedDf0Path == null ? newDf0Path != null : !mSelectedDf0Path.equals(newDf0Path))
            || (mSelectedDf1Path == null ? newDf1Path != null : !mSelectedDf1Path.equals(newDf1Path))
            || (mSelectedDf2Path == null ? newDf2Path != null : !mSelectedDf2Path.equals(newDf2Path))
            || (mSelectedDf3Path == null ? newDf3Path != null : !mSelectedDf3Path.equals(newDf3Path));

        mSelectedDf0 = newDf0;
        mSelectedDf1 = newDf1;
        mSelectedDf2 = newDf2;
        mSelectedDf3 = newDf3;
        mSelectedDf0Path = newDf0Path;
        mSelectedDf1Path = newDf1Path;
        mSelectedDf2Path = newDf2Path;
        mSelectedDf3Path = newDf3Path;

        if (changed) {
            mDf0SourceName = deriveSourceNameFromFileOrUriIfMissing(mDf0SourceName, mSelectedDf0, mSelectedDf0Path);
            mDf1SourceName = deriveSourceNameFromFileOrUriIfMissing(mDf1SourceName, mSelectedDf1, mSelectedDf1Path);
            mDf2SourceName = deriveSourceNameFromFileOrUriIfMissing(mDf2SourceName, mSelectedDf2, mSelectedDf2Path);
            mDf3SourceName = deriveSourceNameFromFileOrUriIfMissing(mDf3SourceName, mSelectedDf3, mSelectedDf3Path);
            saveSourceNames();
        }

        boolean hasDf1 = (mSelectedDf1 != null && mSelectedDf1.exists()) || (mSelectedDf1Path != null && !mSelectedDf1Path.trim().isEmpty());
        mDf1Added = hasDf1;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF1, hasDf1).apply();

        boolean hasDf2 = (mSelectedDf2 != null && mSelectedDf2.exists()) || (mSelectedDf2Path != null && !mSelectedDf2Path.trim().isEmpty());
        mDf2Added = hasDf2;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF2, hasDf2).apply();

        boolean hasDf3 = (mSelectedDf3 != null && mSelectedDf3.exists()) || (mSelectedDf3Path != null && !mSelectedDf3Path.trim().isEmpty());
        mDf3Added = hasDf3;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF3, hasDf3).apply();
    }

    private static boolean sSplashShown = false;

    private void applyFeaturedGraphic() {
        if (mLaunchedFromEmulatorMenu) return;
        if (sSplashShown) return;

        android.widget.ImageView splash = findViewById(R.id.splashImage);
        if (splash != null) {
            splash.setVisibility(View.VISIBLE);
            sSplashShown = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                splash.setVisibility(View.GONE);
            }, 2000);
        }
    }

    private String guessConfigName() {
        String modelPart = normalizeConfigNamePart(getSelectedQsPrefsId());
        if (modelPart.isEmpty()) {
            try {
                SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                modelPart = normalizeConfigNamePart(p.getString(PREF_QS_MODEL, null));
            } catch (Throwable ignored) {
            }
        }

        String baseName;
        if (isCdOnlyModel()) {
            baseName = firstNonEmpty(
                buildMediaConfigName("cdroms", mCd0SourceName, mSelectedCd0, mSelectedCd0Path),
                buildMediaConfigName("harddrives", mDh0SourceName, mSelectedDh0Hdf, mSelectedDh0HdfPath),
                buildMediaConfigName("harddrives", mDh1SourceName, mSelectedDh1Hdf, mSelectedDh1HdfPath),
                buildMediaConfigName("harddrives", mDh2SourceName, mSelectedDh2Hdf, mSelectedDh2HdfPath),
                buildMediaConfigName("harddrives", mDh3SourceName, mSelectedDh3Hdf, mSelectedDh3HdfPath),
                buildMediaConfigName("harddrives", mDh4SourceName, mSelectedDh4Hdf, mSelectedDh4HdfPath),
                buildMediaConfigName("floppies", mDf0SourceName, mSelectedDf0, mSelectedDf0Path),
                buildMediaConfigName("floppies", mDf1SourceName, mSelectedDf1, mSelectedDf1Path),
                buildMediaConfigName("floppies", mDf2SourceName, mSelectedDf2, mSelectedDf2Path),
                buildMediaConfigName("floppies", mDf3SourceName, mSelectedDf3, mSelectedDf3Path)
            );
        } else {
            baseName = firstNonEmpty(
                buildMediaConfigName("harddrives", mDh0SourceName, mSelectedDh0Hdf, mSelectedDh0HdfPath),
                buildMediaConfigName("harddrives", mDh1SourceName, mSelectedDh1Hdf, mSelectedDh1HdfPath),
                buildMediaConfigName("harddrives", mDh2SourceName, mSelectedDh2Hdf, mSelectedDh2HdfPath),
                buildMediaConfigName("harddrives", mDh3SourceName, mSelectedDh3Hdf, mSelectedDh3HdfPath),
                buildMediaConfigName("harddrives", mDh4SourceName, mSelectedDh4Hdf, mSelectedDh4HdfPath),
                buildMediaConfigName("floppies", mDf0SourceName, mSelectedDf0, mSelectedDf0Path),
                buildMediaConfigName("floppies", mDf1SourceName, mSelectedDf1, mSelectedDf1Path),
                buildMediaConfigName("floppies", mDf2SourceName, mSelectedDf2, mSelectedDf2Path),
                buildMediaConfigName("floppies", mDf3SourceName, mSelectedDf3, mSelectedDf3Path),
                buildMediaConfigName("cdroms", mCd0SourceName, mSelectedCd0, mSelectedCd0Path)
            );
        }

        if (baseName == null || baseName.trim().isEmpty()) {
            String fallback = firstNonEmpty(
                normalizeConfigNamePart(mKickSourceName),
                normalizeConfigNamePart(mExtSourceName)
            );
            baseName = fallback.isEmpty() ? "config" : fallback;
        }

        if (modelPart != null && !modelPart.isEmpty()) {
            String lowerBase = baseName.toLowerCase(Locale.ROOT);
            String lowerModel = modelPart.toLowerCase(Locale.ROOT);
            if (!lowerBase.endsWith("_" + lowerModel) && !lowerBase.equals(lowerModel)) {
                baseName = baseName + "_" + modelPart;
            }
        }

        String cleaned = sanitizeFilename(baseName);
        if (cleaned == null || cleaned.trim().isEmpty()) return "config";
        return cleaned;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String s = safeTrim(value);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private String buildMediaConfigName(String mediaFolder, String sourceName, File selectedFile, String selectedPath) {
        String media = resolveMediaDisplayName(sourceName, selectedFile, selectedPath);
        String mediaPart = normalizeConfigNamePart(media);
        if (mediaPart.isEmpty()) return "";
        String folderPart = normalizeConfigNamePart(mediaFolder);
        if (folderPart.isEmpty()) return mediaPart;
        return folderPart + "_" + mediaPart;
    }

    private String resolveMediaDisplayName(String sourceName, File selectedFile, String selectedPath) {
        String source = safeTrim(sourceName);
        if (!source.isEmpty()) return source;

        if (selectedFile != null) {
            try {
                String n = selectedFile.getName();
                if (n != null && !n.trim().isEmpty()) return n.trim();
            } catch (Throwable ignored) {
            }
        }

        String path = safeTrim(selectedPath);
        if (path.isEmpty()) return "";
        if (isContentUriString(path)) {
            try {
                String dn = getDisplayName(Uri.parse(path));
                if (dn != null && !dn.trim().isEmpty()) return dn.trim();
            } catch (Throwable ignored) {
            }
            return "";
        }

        try {
            File f = new File(path);
            String n = f.getName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String normalizeConfigNamePart(String raw) {
        String s = safeTrim(raw);
        if (s.isEmpty()) return "";
        s = stripKnownMediaExtension(s);
        s = s.replaceAll("[^A-Za-z0-9._ -]", "_");
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("^[_ .-]+", "");
        s = s.replaceAll("[_ .-]+$", "");
        return s;
    }

    private String stripKnownMediaExtension(String value) {
        String s = safeTrim(value);
        if (s.isEmpty()) return "";
        int dot = s.lastIndexOf('.');
        if (dot <= 0 || dot >= s.length() - 1) return s;
        String ext = s.substring(dot + 1).toLowerCase(Locale.ROOT);
        if ("zip".equals(ext)
            || "adf".equals(ext)
            || "adz".equals(ext)
            || "dms".equals(ext)
            || "hdf".equals(ext)
            || "iso".equals(ext)
            || "cue".equals(ext)
            || "ccd".equals(ext)
            || "lha".equals(ext)
            || "lzh".equals(ext)
            || "chd".equals(ext)
            || "bin".equals(ext)
            || "img".equals(ext)
            || "rom".equals(ext)) {
            return s.substring(0, dot);
        }
        return s;
    }

    private void promptSaveConfig() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(guessConfigName());

        new AlertDialog.Builder(this)
            .setTitle("Save Config")
            .setMessage("Save current Quickstart settings as a config file")
            .setView(input)
            .setPositiveButton("Save", (d, which) -> {
                try {
                    if (!mLaunchedFromEmulatorMenu) {
                        syncBootMediumToUaePrefs();
                    }
                } catch (Throwable ignored) {
                }

                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.isEmpty()) name = "config";
                boolean ok = ConfigStorage.saveConfig(this, name);
                if (ok) {
                    Toast.makeText(this, "Saved config: " + ConfigStorage.ensureFilename(name), Toast.LENGTH_SHORT).show();
                } else {
                    String conf = ConfigStorage.getConfigDirString(this);
                    String err = ConfigStorage.getLastError();
                    if (ConfigStorage.isSafJoinedPath(conf)) {
                        boolean hasPerm = hasPersistedReadPermissionForSafJoinedDir(conf);
                        boolean canResolve = canResolveSafJoinedDir(conf);
                        String msg;
                        if (!hasPerm) {
                            msg = "Save failed: missing permission for your config folder.\n\nOpen Paths and re-select the base folder so Android can grant access.";
                        } else if (!canResolve) {
                            msg = "Save failed: config folder does not exist in the selected SAF location.\n\nCreate the 'conf' folder in your file manager (or adjust Paths).";
                        } else {
                            msg = "Save failed writing to SAF config folder.\n\nTry re-selecting the base folder in Paths.";
                        }
                        if (err != null && !err.trim().isEmpty()) msg += "\n\nDetails: " + err.trim();
                        showReselectDialog(
                            "Cannot Save Config",
                            msg,
                            () -> startActivity(new Intent(this, PathsSimpleActivity.class))
                        );
                    } else {
                        String msg = "Save failed writing to config folder:\n" + conf;
                        if (err != null && !err.trim().isEmpty()) msg += "\n\nDetails: " + err.trim();
                        msg += "\n\nOn newer Android versions, saving to arbitrary filesystem paths may be blocked. Use Paths and select a SAF parent folder.";
                        showReselectDialog(
                            "Cannot Save Config",
                            msg,
                            () -> startActivity(new Intent(this, PathsSimpleActivity.class))
                        );
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || (data.getData() == null && data.getClipData() == null)) {
            return;
        }

        final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        Uri uri = data.getData();
        // Some providers return the selected URI only in ClipData (even for single-select).
        if (uri == null) {
            try {
                android.content.ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    uri = cd.getItemAt(0).getUri();
                }
            } catch (Throwable ignored) {
            }
        }
        if (uri != null) {
            takeReadPermissionIfPossible(uri, takeFlags);
        }


        // ── First-run SAF folder permission result ────────────────────────────
        if (requestCode == REQ_FIRST_RUN_FOLDER) {
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    // Persist read+write access to the selected uae4arm tree.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    }

                    // Save as the Paths parent tree URI and derive standard sub-paths.
                    String treeUriStr = uri.toString();
                    SharedPreferences.Editor ed = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
                    ed.putString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, treeUriStr);
                    ed.putString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, joinSafTreeBase(treeUriStr, "kickstarts"));
                    ed.putString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR,   joinSafTreeBase(treeUriStr, "disks"));
                    ed.putString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, joinSafTreeBase(treeUriStr, "harddrives"));
                    ed.putString(UaeOptionKeys.UAE_PATH_CONF_DIR,       joinSafTreeBase(treeUriStr, "conf"));
                    ed.putString(UaeOptionKeys.UAE_PATH_CDROMS_DIR,     joinSafTreeBase(treeUriStr, "cdroms"));
                    ed.putString(UaeOptionKeys.UAE_PATH_ROMS_DIR,       joinSafTreeBase(treeUriStr, "kickstarts"));
                    ed.putString(UaeOptionKeys.UAE_PATH_LHA_DIR,        joinSafTreeBase(treeUriStr, "lha"));
                    ed.putString(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR,    joinSafTreeBase(treeUriStr, "whdboot"));
                    ed.putString(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, joinSafTreeBase(treeUriStr, "savestates"));
                    ed.apply();

                    // Create standard subfolders in the tree via DocumentFile so they're
                    // immediately visible to SAF path checks. Using File.mkdirs() alone is
                    // unreliable — DocumentFile.findFile() may not see filesystem-created
                    // folders on many Android versions (they only appear after a media-scan).
                    try {
                        DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
                        if (tree != null) {
                            String[] subs = {"kickstarts", "disks", "harddrives", "conf", "configs", "cdroms", "savestates", "whdboot", "lha"};
                            for (String sub : subs) {
                                DocumentFile existing = tree.findFile(sub);
                                if (existing == null || !existing.exists()) {
                                    tree.createDirectory(sub);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }

                    Toast.makeText(this, "Storage access granted – uae4arm folder is ready.", Toast.LENGTH_SHORT).show();
                    mPathsParentPromptShown = true;
                    mRequiredPathsPromptShown = true;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_PATHS_PARENT_PROMPT_SHOWN, true)
                        .putBoolean(PREF_REQUIRED_PATHS_PROMPT_SHOWN, true)
                        .apply();
                } catch (Throwable t) {
                    LogUtil.i(TAG, "First-run SAF permission failed", t);
                }
            }
            return;
        }

        if (requestCode == REQ_IMPORT_WHDLOAD) {
            if (uri == null) return;
            try {
                takeReadPermissionIfPossible(uri, data.getFlags());
            } catch (Throwable ignored) {
            }

            Intent i = new Intent(this, AmiberryActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra(AmiberryActivity.EXTRA_WHDLOAD_FILE, uri.toString());
            // Enable core logfile for WHDLoad autoload sessions: native WHDBooter logging is
            // not reliably visible in logcat on all devices.
            i.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
            startActivity(i);
            finish();
            return;
        }

        if (requestCode == REQ_IMPORT_CDIMAGE0_DIR) {
            if (uri == null) return;
            if (mPendingCue == null || mPendingCueTracks == null || mPendingCueTracks.isEmpty()) {
                Toast.makeText(this, "No pending CUE tracks to import", Toast.LENGTH_SHORT).show();
                return;
            }

            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.isDirectory()) {
                Toast.makeText(this, "Invalid folder selected", Toast.LENGTH_SHORT).show();
                return;
            }

            File cd0Dir = getInternalCd0Dir();
            ensureDir(cd0Dir);

            for (String trackName : mPendingCueTracks) {
                if (trackName == null || trackName.trim().isEmpty()) continue;
                DocumentFile src = findFileByNameIgnoreCase(tree, trackName, 2);
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

            fixCueTrackFilenameCase(mPendingCue);
            if (cueHasMissingTracks(mPendingCue)) {
                Toast.makeText(this, "CUE still missing tracks after folder import", Toast.LENGTH_LONG).show();
                return;
            }

            mSelectedCd0 = mPendingCue;
            if (mCd0SourceName == null || mCd0SourceName.trim().isEmpty()) {
                mCd0SourceName = mPendingCue.getName();
            }

            mCd0Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_CD0, true).apply();

            mPendingCue = null;
            mPendingCueTracks = null;

            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, mSelectedCd0.getAbsolutePath());
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, true);
            e.apply();

            saveSourceNames();
            refreshStatus();
            return;
        }

        if (requestCode == REQ_IMPORT_DH0_DIR) {
            if (uri == null) return;
            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.isDirectory()) {
                Toast.makeText(this, "Invalid DH0 folder", Toast.LENGTH_SHORT).show();
                return;
            }

            File destDir = getInternalDh0Dir();
            deleteRecursive(destDir);
            ensureDir(destDir);

            if (!copyDocumentTreeTo(tree, destDir, 8)) {
                Toast.makeText(this, "Failed to import DH0 folder", Toast.LENGTH_LONG).show();
                return;
            }

            mSelectedDh0Dir = destDir;
            String srcName = tree.getName();
            mDh0SourceName = (srcName == null || srcName.trim().isEmpty()) ? uri.toString() : srcName;

            mDh0Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DH0, true).apply();

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_DH0_MODE, DH0_MODE_DIR)
                .apply();

            // Apply into UAE prefs
            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED, true);
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, destDir.getAbsolutePath());
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_DEVNAME, "DH0");
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR0_VOLNAME, "DH0");
            e.putInt(UaeOptionKeys.UAE_DRIVE_DIR0_BOOTPRI, 10);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR0_READONLY, false);
            e.apply();

            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
            return;
        }

        if (requestCode == REQ_IMPORT_DH0_HDF) {
            if (uri == null) return;
            String name = getDisplayName(uri);
            if (!isValidHdfExtension(name)) {
                Toast.makeText(this, "Please select a hardfile image (.hdf)", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                takeReadPermissionIfPossible(uri, data.getFlags());
            } catch (Throwable ignored) {
            }
            boolean hasWrite = (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            importDhHdfAsync(0, uri, hasWrite);
            return;
        }

        if (requestCode == REQ_IMPORT_DH1_DIR) {
            if (uri == null) return;
            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.isDirectory()) {
                Toast.makeText(this, "Invalid DH1 folder", Toast.LENGTH_SHORT).show();
                return;
            }

            File destDir = getInternalDh1Dir();
            deleteRecursive(destDir);
            ensureDir(destDir);

            if (!copyDocumentTreeTo(tree, destDir, 8)) {
                Toast.makeText(this, "Failed to import DH1 folder", Toast.LENGTH_LONG).show();
                return;
            }

            mSelectedDh1Dir = destDir;
            mSelectedDh1Hdf = null;
            mSelectedDh1HdfPath = null;
            String srcName = tree.getName();
            mDh1SourceName = (srcName == null || srcName.trim().isEmpty()) ? uri.toString() : srcName;

            mDh1Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SHOW_DH1, true)
                .putString(PREF_DH1_MODE, DH1_MODE_DIR)
                .apply();

            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED, true);
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, destDir.getAbsolutePath());
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_DEVNAME, "DH1");
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR1_VOLNAME, "DH1");
            e.putInt(UaeOptionKeys.UAE_DRIVE_DIR1_BOOTPRI, -128);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR1_READONLY, false);
            e.apply();

            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
            return;
        }

        if (requestCode == REQ_IMPORT_DH1_HDF) {
            if (uri == null) return;
            String name = getDisplayName(uri);
            if (!isValidHdfExtension(name)) {
                Toast.makeText(this, "Please select a hardfile image (.hdf)", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                takeReadPermissionIfPossible(uri, data.getFlags());
            } catch (Throwable ignored) {
            }
            boolean hasWrite = (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            importDhHdfAsync(1, uri, hasWrite);
            return;
        }

        if (requestCode == REQ_IMPORT_KICKSTART) {
            if (uri == null) return;
            File romsDir = getInternalRomsDir();
            ensureDir(romsDir);
            File dest = guessDestFileForUri(uri, romsDir, INTERNAL_KICKSTART_PREFIX);
            LogUtil.i(TAG, "Importing Kickstart from URI: " + uri + " -> " + dest.getAbsolutePath());
            if (importToFile(uri, dest)) {
                LogUtil.i(TAG, "Imported kickstart to: " + dest.getAbsolutePath());
                mSelectedKick = dest;
                String src = getDisplayName(uri);
                mKickSourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, dest.getAbsolutePath())
                    .putString(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL, mKickSourceName)
                    .apply();

                if (mPendingMapModelId != null && !mPendingMapModelId.trim().isEmpty() && !mPendingMapIsExt) {
                    String base = mPendingMapModelId.trim().toUpperCase(Locale.ROOT);
                    String key = PREF_KICK_MAP_PREFIX + base;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, dest.getAbsolutePath()).apply();
                    mPendingMapModelId = null;
                    mPendingMapIsExt = false;
                } else {
                    // If the user imported Kickstart directly (not via map dialog), automatically map it
                    // to the currently selected model so Kickstart Map stays authoritative.
                    String modelId = getSelectedQsModelId();
                    if (modelId != null && !modelId.trim().isEmpty()) {
                        String key = PREF_KICK_MAP_PREFIX + modelId.trim().toUpperCase(Locale.ROOT);
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, dest.getAbsolutePath()).apply();
                    }
                }

                saveSourceNames();
                refreshStatus();
            } else {
                Toast.makeText(this, "Kickstart import failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_IMPORT_EXT_ROM) {
            if (uri == null) return;
            File romsDir = getInternalRomsDir();
            ensureDir(romsDir);
            File dest = guessDestFileForUri(uri, romsDir, INTERNAL_EXT_ROM_PREFIX);
            LogUtil.i(TAG, "Importing Ext ROM from URI: " + uri + " -> " + dest.getAbsolutePath());
            if (importToFile(uri, dest)) {
                LogUtil.i(TAG, "Imported ext ROM to: " + dest.getAbsolutePath());
                mSelectedExt = dest;
                String src = getDisplayName(uri);
                mExtSourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(UaeOptionKeys.UAE_ROM_EXT_FILE, dest.getAbsolutePath())
                    .putString(UaeOptionKeys.UAE_ROM_EXT_LABEL, mExtSourceName)
                    .apply();

                if (mPendingMapModelId != null && !mPendingMapModelId.trim().isEmpty() && mPendingMapIsExt) {
                    String base = mPendingMapModelId.trim().toUpperCase(Locale.ROOT);
                    String key = PREF_EXT_MAP_PREFIX + base;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, dest.getAbsolutePath()).apply();
                    mPendingMapModelId = null;
                    mPendingMapIsExt = false;
                } else {
                    // Direct ext-ROM import: only makes sense for CD32.
                    String modelId = getSelectedQsModelId();
                    if (modelId != null && "CD32".equalsIgnoreCase(modelId.trim())) {
                        String key = PREF_EXT_MAP_PREFIX + "CD32";
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, dest.getAbsolutePath()).apply();
                    }
                }

                saveSourceNames();
                refreshStatus();
            } else {
                Toast.makeText(this, "Ext ROM import failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_IMPORT_CDIMAGE0) {
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            java.util.List<Uri> validCdUris = new java.util.ArrayList<>();
            java.util.Map<Uri, String> displayNameByUri = new java.util.HashMap<>();
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
                Toast.makeText(this, "No CD files selected", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri bestUri = null;
            String bestLabel = null;
            int bestPri = 999;

            for (Uri u : uris) {
                if (u == null) continue;
                takeReadPermissionIfPossible(u, takeFlags);
                String displayName = getDisplayName(u);
                if (displayName == null || displayName.trim().isEmpty()) displayName = "cd.bin";
                if (!isValidCdExtension(displayName)) {
                    continue;
                }
                validCdUris.add(u);
                displayNameByUri.put(u, displayName);
                int pri = cdMainPriority(lowerExt(displayName));
                if (pri < bestPri) {
                    bestPri = pri;
                    bestUri = u;
                    bestLabel = displayName;
                }
            }

            if (bestUri == null) {
                Toast.makeText(this, "Please select a CD image (.iso, .cue, .bin, or .chd)", Toast.LENGTH_LONG).show();
                return;
            }

            if ("cue".equals(lowerExt(bestLabel))) {
                File cd0Dir = getInternalCd0Dir();
                deleteRecursive(cd0Dir);
                ensureDir(cd0Dir);

                File cueDest = null;
                String fallbackName = "cdimage0.cue";

                for (Uri u : validCdUris) {
                    if (u == null) continue;
                    String name = displayNameByUri.get(u);
                    if (name == null || name.trim().isEmpty()) name = fallbackName;
                    File dest = new File(cd0Dir, safeFilename(name, fallbackName));
                    if (!importToFile(u, dest)) {
                        continue;
                    }
                    if (u.equals(bestUri)) {
                        cueDest = dest;
                    }
                }

                if (cueDest == null || !cueDest.exists() || cueDest.length() <= 0) {
                    Toast.makeText(this, "Failed to import selected CUE", Toast.LENGTH_LONG).show();
                    return;
                }

                fixCueTrackFilenameCase(cueDest);
                if (cueHasMissingTracks(cueDest)) {
                    java.util.List<String> tracks = parseCueTrackFilenames(cueDest);
                    java.util.List<String> missing = new java.util.ArrayList<>();
                    for (String track : tracks) {
                        if (track == null || track.trim().isEmpty()) continue;
                        if (!new File(cd0Dir, track).exists()) {
                            missing.add(track);
                        }
                    }

                    if (!missing.isEmpty()) {
                        mPendingCue = cueDest;
                        mPendingCueTracks = missing;
                        mCd0SourceName = (bestLabel == null || bestLabel.trim().isEmpty()) ? cueDest.getName() : bestLabel;
                        Toast.makeText(this, "Select the folder containing the CUE/BIN tracks", Toast.LENGTH_LONG).show();
                        pickCdFolderForMissingTracks();
                        return;
                    }

                    Toast.makeText(this, "CUE import failed: missing BIN tracks", Toast.LENGTH_LONG).show();
                    return;
                }

                mPendingCue = null;
                mPendingCueTracks = null;
                mSelectedCd0 = cueDest;
                mSelectedCd0Path = null;
                mCd0SourceName = (bestLabel == null || bestLabel.trim().isEmpty()) ? cueDest.getName() : bestLabel;

                mCd0Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_CD0, true).apply();

                SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
                e.putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, cueDest.getAbsolutePath());
                e.putBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, true);
                e.apply();

                saveSourceNames();
                refreshStatus();
                maybeReopenMediaSwapperAfterPicker();
                return;
            }

            String bestUriString = bestUri.toString();
            if (!isReadableMediaPath(bestUriString)) {
                Toast.makeText(this, "Cannot read selected CD image", Toast.LENGTH_SHORT).show();
                return;
            }

            mSelectedCd0 = null;
            mSelectedCd0Path = bestUriString;
            mCd0SourceName = (bestLabel == null || bestLabel.trim().isEmpty()) ? bestUriString : bestLabel;

            mCd0Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_CD0, true).apply();

            // Ensure CD device is enabled for CD32/CDTV.
            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, bestUriString);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, true);
            e.apply();

            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
            return;
        }

        File disksDir = getInternalDisksDir();
        ensureDir(disksDir);

        if (requestCode == REQ_IMPORT_DF0) {
            if (uri == null) return;
            // Validate extension: only .adf and .zip are accepted as floppy disk images.
            String df0DisplayName = getDisplayName(uri);
            if (validateAndRejectIfWrongExtension(uri, df0DisplayName, new String[]{"adf", "zip"},
                "Please select an Amiga floppy disk image (.adf or .zip)")) return;
            // Use the picked filename (prefixed for DF0) so saved configs preserve
            // the actual media identity instead of a generic "disk.zip".
            File dest = guessDestFileForUri(uri, disksDir, "df0");
            LogUtil.i(TAG, "Importing DF0 from URI: " + uri + " -> " + dest.getAbsolutePath());
            if (importToFile(uri, dest)) {
                LogUtil.i(TAG, "Imported DF0 to: " + dest.getAbsolutePath() + " (len=" + dest.length() + " mtime=" + dest.lastModified() + ")");
                // Keep prior DF0 imports so older saved configs still reference valid files.
                try {
                    File legacyDf0 = new File(disksDir, "disk.zip");
                    if (!legacyDf0.equals(dest)) {
                        clearFile(legacyDf0);
                    }
                } catch (Throwable ignored) {
                }
                mSelectedDf0 = dest;
                mSelectedDf0Path = null;
                String src = getDisplayName(uri);
                mDf0SourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
                // Always commit synchronously so a subsequent Reset cannot read stale data.
                //noinspection ResultOfMethodCallIgnored
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, dest.getAbsolutePath())
                    .commit();
                saveSourceNames();
                refreshStatus();
                maybeReopenMediaSwapperAfterPicker();
            } else {
                Toast.makeText(this, "DF0 import failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQ_IMPORT_DF1) {
            if (uri == null) return;
            String df1DisplayName = getDisplayName(uri);
            if (validateAndRejectIfWrongExtension(uri, df1DisplayName, new String[]{"adf", "zip"},
                "Please select an Amiga floppy disk image (.adf or .zip)")) return;
            File dest = guessDestFileForUri(uri, disksDir, "df1");
            LogUtil.i(TAG, "Importing DF1 from URI: " + uri + " -> " + dest.getAbsolutePath());
            if (importToFile(uri, dest)) {
                LogUtil.i(TAG, "Imported DF1 to: " + dest.getAbsolutePath());
                mSelectedDf1 = dest;
                mSelectedDf1Path = null;
                String src = getDisplayName(uri);
                mDf1SourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
                mDf1Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF1, true).apply();
                //noinspection ResultOfMethodCallIgnored
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, dest.getAbsolutePath())
                    .commit();
                saveSourceNames();
                refreshStatus();
                maybeReopenMediaSwapperAfterPicker();
            } else {
                Toast.makeText(this, "DF1 import failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_IMPORT_DF2) {
            if (uri == null) return;
            String df2DisplayName = getDisplayName(uri);
            if (validateAndRejectIfWrongExtension(uri, df2DisplayName, new String[]{"adf", "zip"},
                "Please select an Amiga floppy disk image (.adf or .zip)")) return;
            File dest = guessDestFileForUri(uri, disksDir, "df2");
            LogUtil.i(TAG, "Importing DF2 from URI: " + uri + " -> " + dest.getAbsolutePath());
            if (importToFile(uri, dest)) {
                LogUtil.i(TAG, "Imported DF2 to: " + dest.getAbsolutePath());
                mSelectedDf2 = dest;
                mSelectedDf2Path = null;
                String src = getDisplayName(uri);
                mDf2SourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
                mDf2Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF2, true).apply();

                //noinspection ResultOfMethodCallIgnored
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, dest.getAbsolutePath())
                    .commit();

                saveSourceNames();
                refreshStatus();
                maybeReopenMediaSwapperAfterPicker();
            } else {
                Toast.makeText(this, "DF2 import failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_IMPORT_DF3) {
            if (uri == null) return;
            String df3DisplayName = getDisplayName(uri);
            if (validateAndRejectIfWrongExtension(uri, df3DisplayName, new String[]{"adf", "zip"},
                "Please select an Amiga floppy disk image (.adf or .zip)")) return;
            File dest = guessDestFileForUri(uri, disksDir, "df3");
            LogUtil.i(TAG, "Importing DF3 from URI: " + uri + " -> " + dest.getAbsolutePath());
            if (importToFile(uri, dest)) {
                LogUtil.i(TAG, "Imported DF3 to: " + dest.getAbsolutePath());
                mSelectedDf3 = dest;
                mSelectedDf3Path = null;
                String src = getDisplayName(uri);
                mDf3SourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
                mDf3Added = true;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_DF3, true).apply();

                //noinspection ResultOfMethodCallIgnored
                getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, dest.getAbsolutePath())
                    .commit();

                saveSourceNames();
                refreshStatus();
                maybeReopenMediaSwapperAfterPicker();
            } else {
                Toast.makeText(this, "DF3 import failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_IMPORT_DH2_DIR) {
            if (uri == null) return;
            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.isDirectory()) {
                Toast.makeText(this, "Invalid DH2 folder", Toast.LENGTH_SHORT).show();
                return;
            }

            File destDir = getInternalDh2Dir();
            deleteRecursive(destDir);
            ensureDir(destDir);

            if (!copyDocumentTreeTo(tree, destDir, 8)) {
                Toast.makeText(this, "Failed to import DH2 folder", Toast.LENGTH_LONG).show();
                return;
            }

            mSelectedDh2Dir = destDir;
            mSelectedDh2Hdf = null;
            mSelectedDh2HdfPath = null;
            String srcName = tree.getName();
            mDh2SourceName = (srcName == null || srcName.trim().isEmpty()) ? uri.toString() : srcName;

            mDh2Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SHOW_DH2, true)
                .apply();

            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED, true);
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR2_PATH, destDir.getAbsolutePath());
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR2_DEVNAME, "DH2");
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR2_VOLNAME, "DH2");
            e.putInt(UaeOptionKeys.UAE_DRIVE_DIR2_BOOTPRI, -128);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR2_READONLY, false);
            e.apply();

            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
            return;
        }

        if (requestCode == REQ_IMPORT_DH2_HDF) {
            if (uri == null) return;
            String name = getDisplayName(uri);
            if (!isValidHdfExtension(name)) {
                Toast.makeText(this, "Please select a hardfile image (.hdf)", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                takeReadPermissionIfPossible(uri, data.getFlags());
            } catch (Throwable ignored) {
            }
            boolean hasWrite = (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            importDhHdfAsync(2, uri, hasWrite);
            return;
        }

        if (requestCode == REQ_IMPORT_DH3_DIR) {
            if (uri == null) return;
            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.isDirectory()) {
                Toast.makeText(this, "Invalid DH3 folder", Toast.LENGTH_SHORT).show();
                return;
            }

            File destDir = getInternalDh3Dir();
            deleteRecursive(destDir);
            ensureDir(destDir);

            if (!copyDocumentTreeTo(tree, destDir, 8)) {
                Toast.makeText(this, "Failed to import DH3 folder", Toast.LENGTH_LONG).show();
                return;
            }

            mSelectedDh3Dir = destDir;
            mSelectedDh3Hdf = null;
            mSelectedDh3HdfPath = null;
            String srcName = tree.getName();
            mDh3SourceName = (srcName == null || srcName.trim().isEmpty()) ? uri.toString() : srcName;

            mDh3Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SHOW_DH3, true)
                .apply();

            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED, true);
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR3_PATH, destDir.getAbsolutePath());
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR3_DEVNAME, "DH3");
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR3_VOLNAME, "DH3");
            e.putInt(UaeOptionKeys.UAE_DRIVE_DIR3_BOOTPRI, -128);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR3_READONLY, false);
            e.apply();

            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
            return;
        }

        if (requestCode == REQ_IMPORT_DH3_HDF) {
            if (uri == null) return;
            String name = getDisplayName(uri);
            if (!isValidHdfExtension(name)) {
                Toast.makeText(this, "Please select a hardfile image (.hdf)", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                takeReadPermissionIfPossible(uri, data.getFlags());
            } catch (Throwable ignored) {
            }
            boolean hasWrite = (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            importDhHdfAsync(3, uri, hasWrite);
            return;
        }

        if (requestCode == REQ_IMPORT_DH4_DIR) {
            if (uri == null) return;
            DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
            if (tree == null || !tree.isDirectory()) {
                Toast.makeText(this, "Invalid DH4 folder", Toast.LENGTH_SHORT).show();
                return;
            }

            File destDir = getInternalDh4Dir();
            deleteRecursive(destDir);
            ensureDir(destDir);

            if (!copyDocumentTreeTo(tree, destDir, 8)) {
                Toast.makeText(this, "Failed to import DH4 folder", Toast.LENGTH_LONG).show();
                return;
            }

            mSelectedDh4Dir = destDir;
            mSelectedDh4Hdf = null;
            mSelectedDh4HdfPath = null;
            String srcName = tree.getName();
            mDh4SourceName = (srcName == null || srcName.trim().isEmpty()) ? uri.toString() : srcName;

            mDh4Added = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SHOW_DH4, true)
                .apply();

            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED, false);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED, true);
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR4_PATH, destDir.getAbsolutePath());
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR4_DEVNAME, "DH4");
            e.putString(UaeOptionKeys.UAE_DRIVE_DIR4_VOLNAME, "DH4");
            e.putInt(UaeOptionKeys.UAE_DRIVE_DIR4_BOOTPRI, -128);
            e.putBoolean(UaeOptionKeys.UAE_DRIVE_DIR4_READONLY, false);
            e.apply();

            saveSourceNames();
            refreshStatus();
            maybeReopenMediaSwapperAfterPicker();
            return;
        }

        if (requestCode == REQ_IMPORT_DH4_HDF) {
            if (uri == null) return;
            String name = getDisplayName(uri);
            if (!isValidHdfExtension(name)) {
                Toast.makeText(this, "Please select a hardfile image (.hdf)", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                takeReadPermissionIfPossible(uri, data.getFlags());
            } catch (Throwable ignored) {
            }
            boolean hasWrite = (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
            importDhHdfAsync(4, uri, hasWrite);
            return;
        }
    }
}
