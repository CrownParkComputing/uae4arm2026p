package com.uae4arm2026;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class BootstrapFileImporter {

    private static final String TAG = "BootstrapFileImporter";

    private final Context context;

    BootstrapFileImporter(Context context) {
        this.context = context;
    }

    boolean importToFile(Uri uri, File dest) {
        if (uri == null || dest == null) return false;
        ensureDir(dest.getParentFile());

        ContentResolver cr = context.getContentResolver();
        try (InputStream in = cr.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                return false;
            }
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
            return dest.exists() && dest.length() > 0;
        } catch (IOException e) {
            Log.w(TAG, "Import failed for " + uri + " -> " + dest.getAbsolutePath(), e);
            return false;
        }
    }

    boolean copyDocumentFileTo(DocumentFile src, File dest) {
        if (src == null || !src.isFile()) return false;
        Uri uri = src.getUri();
        if (uri == null) return false;
        return importToFile(uri, dest);
    }

    boolean copyDocumentTreeTo(DocumentFile srcDir, File destDir, int depth) {
        if (srcDir == null || !srcDir.isDirectory()) return false;
        if (depth < 0) return true;
        ensureDir(destDir);

        DocumentFile[] kids = srcDir.listFiles();
        if (kids == null) return true;

        for (DocumentFile kid : kids) {
            if (kid == null) continue;
            String name = kid.getName();
            if (name == null || name.trim().isEmpty()) continue;
            name = BootstrapMediaUtils.safeFilename(name, "file.bin");
            File out = new File(destDir, name);
            if (kid.isDirectory()) {
                if (!copyDocumentTreeTo(kid, out, depth - 1)) return false;
            } else if (kid.isFile()) {
                if (!copyDocumentFileTo(kid, out)) return false;
            }
        }
        return true;
    }

    String getDisplayName(Uri uri) {
        if (uri == null) return null;
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor == null) return null;
            try {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx);
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    String guessExtensionForUri(Uri uri) {
        if (uri == null) return "";
        String name = getDisplayName(uri);
        if (name == null || name.trim().isEmpty()) {
            try {
                String last = uri.getLastPathSegment();
                if (last != null) {
                    int idx = last.lastIndexOf('/');
                    name = idx >= 0 ? last.substring(idx + 1) : last;
                }
            } catch (Throwable ignored) {
            }
        }

        if (name == null || name.trim().isEmpty()) {
            return "";
        }

        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            return lower.substring(dot);
        }
        return "";
    }

    File guessDestFileForUri(Uri uri, File parentDir, String stableBaseName) {
        // Keep a stable prefix (so we can find/clear by prefix), but also preserve the original
        // picked filename so the launcher can display what the user actually selected.
        String displayName = getDisplayName(uri);
        if (displayName != null) {
            displayName = displayName.trim();
        }

        if (displayName != null && !displayName.isEmpty()) {
            // Basic sanitization (avoid path separators and weird chars).
            String safe = displayName
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_')
                .replace('"', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace('|', '_');

            // Ensure we still have an extension if the provider didn't include one.
            String ext = guessExtensionForUri(uri);
            if (!ext.isEmpty() && !safe.toLowerCase(Locale.ROOT).endsWith(ext)) {
                safe += ext;
            }

            return new File(parentDir, stableBaseName + "__" + safe);
        }

        // Fallback: Preserve original extension when possible (e.g. .adf, .zip, .rom).
        String ext = guessExtensionForUri(uri);
        return new File(parentDir, stableBaseName + ext);
    }

    private static void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }
}
