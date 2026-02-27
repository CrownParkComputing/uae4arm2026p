package com.uae4arm2026;

import android.content.Intent;
import android.content.SharedPreferences;

final class UaeDrivePrefs {
    private UaeDrivePrefs() {
    }

    static final class FloppyPaths {
        final String df0;
        final String df1;
        final String df2;
        final String df3;

        FloppyPaths(String df0, String df1, String df2, String df3) {
            this.df0 = safeTrim(df0);
            this.df1 = safeTrim(df1);
            this.df2 = safeTrim(df2);
            this.df3 = safeTrim(df3);
        }
    }

    static final class HardDriveConfig {
        final boolean dh0Configured;
        final boolean dh1Configured;
        final boolean dh2Configured;
        final boolean dh3Configured;
        final boolean dh4Configured;
        final boolean dh5Configured;
        final boolean dh6Configured;

        HardDriveConfig(
            String hdf0, String dir0,
            String hdf1, String dir1,
            String hdf2, String dir2,
            String hdf3, String dir3,
            String hdf4, String dir4,
            String hdf5, String dir5,
            String hdf6, String dir6
        ) {
            dh0Configured = !safeTrim(hdf0).isEmpty() || !safeTrim(dir0).isEmpty();
            dh1Configured = !safeTrim(hdf1).isEmpty() || !safeTrim(dir1).isEmpty();
            dh2Configured = !safeTrim(hdf2).isEmpty() || !safeTrim(dir2).isEmpty();
            dh3Configured = !safeTrim(hdf3).isEmpty() || !safeTrim(dir3).isEmpty();
            dh4Configured = !safeTrim(hdf4).isEmpty() || !safeTrim(dir4).isEmpty();
            dh5Configured = !safeTrim(hdf5).isEmpty() || !safeTrim(dir5).isEmpty();
            dh6Configured = !safeTrim(hdf6).isEmpty() || !safeTrim(dir6).isEmpty();
        }
    }

    static FloppyPaths readFloppyPaths(SharedPreferences prefs) {
        if (prefs == null) return new FloppyPaths("", "", "", "");
        return new FloppyPaths(
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, "")
        );
    }

    static HardDriveConfig readHardDriveConfig(SharedPreferences prefs) {
        if (prefs == null) return new HardDriveConfig("", "", "", "", "", "", "", "", "", "", "", "", "", "");
        return new HardDriveConfig(
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR0_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF1_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR1_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF2_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR2_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF3_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR3_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF4_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR4_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF5_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR5_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_HDF6_PATH, ""),
            prefs.getString(UaeOptionKeys.UAE_DRIVE_DIR6_PATH, "")
        );
    }

    static void putHotSwapExtras(Intent intent, FloppyPaths paths) {
        if (intent == null || paths == null) return;
        intent.putExtra(AmiberryActivity.EXTRA_HOTSWAP_DF0_PATH, paths.df0);
        intent.putExtra(AmiberryActivity.EXTRA_HOTSWAP_DF1_PATH, paths.df1);
        intent.putExtra(AmiberryActivity.EXTRA_HOTSWAP_DF2_PATH, paths.df2);
        intent.putExtra(AmiberryActivity.EXTRA_HOTSWAP_DF3_PATH, paths.df3);
    }

    static void putRelaunchExtras(Intent intent, FloppyPaths paths) {
        if (intent == null || paths == null) return;
        intent.putExtra(AmiberryActivity.EXTRA_DF0_DISK_FILE, paths.df0);
        intent.putExtra(AmiberryActivity.EXTRA_DF1_DISK_FILE, paths.df1);
        intent.putExtra(AmiberryActivity.EXTRA_DF2_DISK_FILE, paths.df2);
        intent.putExtra(AmiberryActivity.EXTRA_DF3_DISK_FILE, paths.df3);
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        s = s.trim();
        return s;
    }
}
