package com.uae4arm2026;

import android.content.Context;

import java.io.File;

final class AppPaths {
    static final String BASE_DIR_NAME = "uae4arm";
    static final String LEGACY_BASE_DIR_NAME = "amiberry";

    private AppPaths() {
    }

    static File getBaseDir(Context ctx) {
        File preferred = new File(ctx.getFilesDir(), BASE_DIR_NAME);
        File legacy = new File(ctx.getFilesDir(), LEGACY_BASE_DIR_NAME);

        if (legacy.exists() && (!preferred.exists() || isEmptyDir(preferred))) {
            return legacy;
        }
        return preferred;
    }

    static boolean isEmptyDir(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return true;
            String[] kids = dir.list();
            return kids == null || kids.length == 0;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
