package com.uae4arm2026;

import android.content.SharedPreferences;

final class BootstrapDrivePrefs {
    private BootstrapDrivePrefs() {
    }

    private static final String[] UAE_DIR_ENABLED_KEYS = new String[] {
        UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED,
        UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED,
        UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED,
        UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED,
        UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED,
        UaeOptionKeys.UAE_DRIVE_DIR5_ENABLED,
        UaeOptionKeys.UAE_DRIVE_DIR6_ENABLED
    };

    private static final String[] UAE_DIR_PATH_KEYS = new String[] {
        UaeOptionKeys.UAE_DRIVE_DIR0_PATH,
        UaeOptionKeys.UAE_DRIVE_DIR1_PATH,
        UaeOptionKeys.UAE_DRIVE_DIR2_PATH,
        UaeOptionKeys.UAE_DRIVE_DIR3_PATH,
        UaeOptionKeys.UAE_DRIVE_DIR4_PATH,
        UaeOptionKeys.UAE_DRIVE_DIR5_PATH,
        UaeOptionKeys.UAE_DRIVE_DIR6_PATH
    };

    private static final String[] UAE_HDF_ENABLED_KEYS = new String[] {
        UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED,
        UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED,
        UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED,
        UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED,
        UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED,
        UaeOptionKeys.UAE_DRIVE_HDF5_ENABLED,
        UaeOptionKeys.UAE_DRIVE_HDF6_ENABLED
    };

    private static final String[] UAE_HDF_PATH_KEYS = new String[] {
        UaeOptionKeys.UAE_DRIVE_HDF0_PATH,
        UaeOptionKeys.UAE_DRIVE_HDF1_PATH,
        UaeOptionKeys.UAE_DRIVE_HDF2_PATH,
        UaeOptionKeys.UAE_DRIVE_HDF3_PATH,
        UaeOptionKeys.UAE_DRIVE_HDF4_PATH,
        UaeOptionKeys.UAE_DRIVE_HDF5_PATH,
        UaeOptionKeys.UAE_DRIVE_HDF6_PATH
    };

    static String signatureFromPrefs(SharedPreferences prefs, int dhIndex) {
        if (prefs == null) return "";
        if (dhIndex < 0 || dhIndex >= UAE_DIR_ENABLED_KEYS.length) return "";
        try {
            boolean dir = prefs.getBoolean(UAE_DIR_ENABLED_KEYS[dhIndex], false);
            if (dir) return "DIR:" + normalizeMediaPath(prefs.getString(UAE_DIR_PATH_KEYS[dhIndex], ""));
            boolean hdf = prefs.getBoolean(UAE_HDF_ENABLED_KEYS[dhIndex], false);
            if (hdf) return "HDF:" + normalizeMediaPath(prefs.getString(UAE_HDF_PATH_KEYS[dhIndex], ""));
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    static boolean hasAnyMountedHardDriveFrom(SharedPreferences prefs, int startDhIndexInclusive) {
        if (prefs == null) return false;
        final int start = Math.max(0, startDhIndexInclusive);
        for (int dhIndex = start; dhIndex < UAE_DIR_ENABLED_KEYS.length; dhIndex++) {
            try {
                if (prefs.getBoolean(UAE_DIR_ENABLED_KEYS[dhIndex], false) ||
                    prefs.getBoolean(UAE_HDF_ENABLED_KEYS[dhIndex], false)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String normalizeMediaPath(String path) {
        if (path == null) return "";
        return path.trim();
    }
}
