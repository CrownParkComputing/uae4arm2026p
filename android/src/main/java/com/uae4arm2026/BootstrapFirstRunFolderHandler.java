package com.uae4arm2026;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

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

            Toast.makeText(activity, "Storage access granted.", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable t) {
            LogUtil.i("BootstrapFirstRunFolderHandler", "First-run SAF permission failed", t);
            return false;
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