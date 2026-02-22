package com.uae4arm2026;

import android.content.SharedPreferences;

import java.io.File;

final class BootstrapSelectionUtils {
    private BootstrapSelectionUtils() {
    }

    static final class FileOrUri {
        final File file;
        final String uri;

        FileOrUri(File file, String uri) {
            this.file = file;
            this.uri = safeTrim(uri);
        }

        boolean isEmpty() {
            return file == null && (uri == null || uri.isEmpty());
        }
    }

    static FileOrUri readFileOrContentUriPref(SharedPreferences prefs, String key) {
        if (prefs == null || key == null) return new FileOrUri(null, null);
        try {
            String path = prefs.getString(key, null);
            if (path == null) return new FileOrUri(null, null);
            String trimmed = path.trim();
            if (trimmed.isEmpty()) return new FileOrUri(null, null);

            if (BootstrapMediaUtils.isContentUriString(trimmed)) {
                return new FileOrUri(null, trimmed);
            }

            File f = new File(trimmed);
            if (f.exists() && f.canRead() && f.isFile() && f.length() > 0) {
                return new FileOrUri(f, null);
            }
        } catch (Throwable ignored) {
        }
        return new FileOrUri(null, null);
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
