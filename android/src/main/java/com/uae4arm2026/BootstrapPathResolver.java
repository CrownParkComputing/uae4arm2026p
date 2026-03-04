package com.uae4arm2026;

import android.content.SharedPreferences;

import java.io.File;

final class BootstrapPathResolver {
    private BootstrapPathResolver() {
    }

    static String resolveConfiguredPathForKeyWithParentFallback(SharedPreferences uaePrefs, String key) {
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
            String parent = parentDir.trim();
            if (ConfigStorage.isSafJoinedPath(parent)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(parent);
                if (sp != null && sp.treeUri != null && !sp.treeUri.trim().isEmpty()) {
                    String joined = joinSafTreeBase(sp.treeUri, rel);
                    if (joined != null && !joined.trim().isEmpty()) return joined.trim();
                }
            } else if (isContentUriString(parent)) {
                String joined = joinSafTreeBase(parent, rel);
                if (joined != null && !joined.trim().isEmpty()) return joined.trim();
            } else {
                return new File(parent, rel).getAbsolutePath();
            }
        }

        return null;
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

    private static String joinSafTreeBase(String treeBase, String relPath) {
        if (treeBase == null) return null;
        String base = treeBase.trim();
        if (base.isEmpty()) return null;

        if (base.contains("::")) {
            ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(base);
            if (sp != null && sp.treeUri != null && !sp.treeUri.trim().isEmpty()) {
                base = sp.treeUri.trim();
            }
        }

        if (!isContentUriString(base)) return null;

        String rel = relPath == null ? "" : relPath.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);

        String joined = base + "::";
        if (!rel.isEmpty()) joined += "/" + rel;
        return joined;
    }

    private static boolean isContentUriString(String s) {
        if (s == null) return false;
        return s.trim().startsWith("content://");
    }
}
