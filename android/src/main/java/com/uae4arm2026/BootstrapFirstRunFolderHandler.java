package com.uae4arm2026;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.util.LinkedHashMap;
import java.util.Map;

final class BootstrapFirstRunFolderHandler {

    private BootstrapFirstRunFolderHandler() {
    }

    static boolean handle(BootstrapActivity activity, int resultCode, Uri uri) {
        if (resultCode != BootstrapActivity.RESULT_OK || uri == null) {
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                activity.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }

            String treeUriStr = uri.toString();
            SharedPreferences.Editor editor = activity
                .getSharedPreferences(UaeOptionKeys.PREFS_NAME, BootstrapActivity.MODE_PRIVATE)
                .edit();

            editor.putString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, treeUriStr);
            for (Map.Entry<String, String> e : pathMappings().entrySet()) {
                editor.putString(e.getKey(), joinSafTreeBase(treeUriStr, e.getValue()));
            }
            editor.apply();

            // Create sub-folders so they exist before the emulator tries to use them.
            ensureDefaultSubfolders(activity, uri);

            Toast.makeText(activity, "Storage access granted.", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable t) {
            LogUtil.i("BootstrapFirstRunFolderHandler", "First-run SAF permission failed", t);
            return false;
        }
    }

    /**
     * Creates the default sub-folders under the selected parent tree so they are ready to use.
     */
    private static void ensureDefaultSubfolders(BootstrapActivity activity, Uri parentUri) {
        try {
            DocumentFile parent = DocumentFile.fromTreeUri(activity, parentUri);
            if (parent == null || !parent.exists()) return;
            // Collect unique subfolder names from the path mappings.
            java.util.Set<String> subfolders = new java.util.LinkedHashSet<>(pathMappings().values());
            // Also include screenshots which is not in pathMappings but used by the app.
            subfolders.add("screenshots");
            for (String subfolder : subfolders) {
                ensureSubfolderExists(parent, subfolder);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureSubfolderExists(DocumentFile parent, String subfolder) {
        if (parent == null || subfolder == null || subfolder.trim().isEmpty()) return;
        try {
            DocumentFile existing = parent.findFile(subfolder);
            if (existing != null && existing.exists() && existing.isDirectory()) {
                return; // Already exists as a directory.
            }
            if (existing != null && existing.exists()) {
                // A file (not a directory) blocks the creation; log and skip.
                LogUtil.i("BootstrapFirstRunFolderHandler",
                    "Cannot create subfolder '" + subfolder + "': a file with that name already exists");
                return;
            }
            DocumentFile created = parent.createDirectory(subfolder);
            if (created == null) {
                LogUtil.i("BootstrapFirstRunFolderHandler",
                    "Failed to create subfolder: " + subfolder);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> pathMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, "kickstarts");
        mappings.put(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, "disks");
        mappings.put(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, "harddrives");
        mappings.put(UaeOptionKeys.UAE_PATH_CONF_DIR, "conf");
        mappings.put(UaeOptionKeys.UAE_PATH_CDROMS_DIR, "cdroms");
        mappings.put(UaeOptionKeys.UAE_PATH_ROMS_DIR, "kickstarts");
        mappings.put(UaeOptionKeys.UAE_PATH_LHA_DIR, "lha");
        mappings.put(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, "whdboot");
        mappings.put(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, "savestates");
        return mappings;
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
}
