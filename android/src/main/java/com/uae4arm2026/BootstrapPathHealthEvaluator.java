package com.uae4arm2026;

import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;

final class BootstrapPathHealthEvaluator {
    private BootstrapPathHealthEvaluator() {
    }

    enum ParentPromptAction {
        OPEN_SAF_PARENT,
        OPEN_PATHS_SIMPLE
    }

    static final class ParentPromptDecision {
        final String title;
        final String message;
        final ParentPromptAction action;

        ParentPromptDecision(String title, String message, ParentPromptAction action) {
            this.title = title;
            this.message = message;
            this.action = action;
        }
    }

    static final class RequiredPathsDecision {
        final String title;
        final String message;
        final String autoPick;
        final boolean openSafParent;

        RequiredPathsDecision(String title, String message, String autoPick, boolean openSafParent) {
            this.title = title;
            this.message = message;
            this.autoPick = autoPick;
            this.openSafParent = openSafParent;
        }
    }

    interface Access {
        boolean isContentUriString(String value);

        String safeTrim(String value);

        String resolveConfiguredPathForKeyWithParentFallback(SharedPreferences prefs, String key);

        boolean hasPersistedReadPermission(String uri);

        boolean hasPersistedWritePermission(String uri);

        boolean hasPersistedReadPermissionForSafJoinedDir(String joinedDir);

        boolean hasPersistedWritePermissionForSafJoinedDir(String joinedDir);

        boolean canResolveSafJoinedDir(String joinedDir);

        boolean tryEnsureSafJoinedDir(String joinedDir);

        boolean isSafPathReadable(String path);

        boolean isSafPathWritable(String path);
    }

    static ParentPromptDecision evaluateParentPrompt(SharedPreferences prefs, Access access) {
        if (prefs == null || access == null) return null;

        String parentTree = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
        String parentDir = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
        String conf = prefs.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null);
        String roms = prefs.getString(UaeOptionKeys.UAE_PATH_ROMS_DIR, null);
        String flops = prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
        String cds = prefs.getString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, null);
        String hds = prefs.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null);

        boolean hasAnyParent = !access.safeTrim(parentTree).isEmpty() || !access.safeTrim(parentDir).isEmpty();
        boolean usingSaf = access.isContentUriString(parentTree)
            || ConfigStorage.isSafJoinedPath(conf)
            || ConfigStorage.isSafJoinedPath(roms)
            || ConfigStorage.isSafJoinedPath(flops)
            || ConfigStorage.isSafJoinedPath(cds)
            || ConfigStorage.isSafJoinedPath(hds);

        if (!hasAnyParent) {
            return new ParentPromptDecision(
                "Set Paths Parent Folder",
                "Paths parent folder is not set.\n\nPlease select a parent folder in Paths so configs and media can be accessed.",
                ParentPromptAction.OPEN_SAF_PARENT
            );
        }

        if (!usingSaf) {
            try {
                if (!access.safeTrim(conf).isEmpty() && !access.isContentUriString(conf) && !ConfigStorage.isSafJoinedPath(conf)) {
                    File confDir = new File(conf.trim());
                    boolean ok;
                    if (confDir.exists()) {
                        ok = confDir.isDirectory() && confDir.canRead() && confDir.canWrite();
                    } else {
                        ok = confDir.mkdirs() && confDir.isDirectory() && confDir.canRead() && confDir.canWrite();
                    }
                    if (!ok) {
                        return new ParentPromptDecision(
                            "Paths Folder Not Accessible",
                            "Your config folder (conf) is not readable/writable:\n\n" + conf.trim() + "\n\n"
                                + "This is commonly caused by Android storage restrictions.\n\n"
                                + "Open Paths and re-select a parent folder (SAF) or use Internal storage.",
                            ParentPromptAction.OPEN_PATHS_SIMPLE
                        );
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        boolean hasParentTree = !access.safeTrim(parentTree).isEmpty() && access.isContentUriString(parentTree);
        boolean hasParentPerm = hasParentTree
            && access.hasPersistedReadPermission(parentTree)
            && access.hasPersistedWritePermission(parentTree);

        boolean confLooksSaf = ConfigStorage.isSafJoinedPath(conf);
        boolean confPermOk = !confLooksSaf
            || (access.hasPersistedReadPermissionForSafJoinedDir(conf)
            && access.hasPersistedWritePermissionForSafJoinedDir(conf));
        boolean confDirOk = !confLooksSaf || access.canResolveSafJoinedDir(conf);

        if (confLooksSaf && !confDirOk && hasParentPerm) {
            confDirOk = access.tryEnsureSafJoinedDir(conf);
        }

        if (!hasParentTree || !hasParentPerm || !confPermOk || !confDirOk) {
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
            return new ParentPromptDecision("Set Paths Parent Folder", message, ParentPromptAction.OPEN_SAF_PARENT);
        }

        return null;
    }

    static RequiredPathsDecision evaluateRequiredPathsPrompt(SharedPreferences prefs, Access access) {
        if (prefs == null || access == null) return null;

        String floppies = access.resolveConfiguredPathForKeyWithParentFallback(prefs, UaeOptionKeys.UAE_PATH_FLOPPIES_DIR);
        String lha = access.resolveConfiguredPathForKeyWithParentFallback(prefs, UaeOptionKeys.UAE_PATH_LHA_DIR);
        String harddrives = access.resolveConfiguredPathForKeyWithParentFallback(prefs, UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR);
        String conf = access.resolveConfiguredPathForKeyWithParentFallback(prefs, UaeOptionKeys.UAE_PATH_CONF_DIR);

        ArrayList<String> missing = new ArrayList<>();
        String firstAutoPick = null;

        if (access.safeTrim(floppies).isEmpty()) {
            missing.add("Floppies");
            if (firstAutoPick == null) firstAutoPick = "floppies";
        }
        if (access.safeTrim(lha).isEmpty()) {
            missing.add("LHA");
            if (firstAutoPick == null) firstAutoPick = "lha";
        }
        if (access.safeTrim(harddrives).isEmpty()) {
            missing.add("Harddrives");
            if (firstAutoPick == null) firstAutoPick = "harddrives";
        }

        if (!missing.isEmpty()) {
            String autoPick = firstAutoPick == null ? "conf" : firstAutoPick;
            return new RequiredPathsDecision(
                "Set Required Paths",
                "Some required Paths are not set: " + android.text.TextUtils.join(", ", missing)
                    + "\n\nPlease open Paths and set these folders.",
                autoPick,
                false
            );
        }

        if (!access.isSafPathReadable(floppies)
            || !access.isSafPathReadable(lha)
            || !access.isSafPathReadable(harddrives)
            || !access.isSafPathWritable(conf)) {
            return new RequiredPathsDecision(
                "Paths Permission Required",
                "One or more configured Paths no longer have valid SAF permissions.\n\n"
                    + "Open Paths and re-select the parent folder (or affected folders) to restore access.",
                null,
                true
            );
        }

        return null;
    }

    static String computePathsQuickstartFlagLine(SharedPreferences prefs, Access access) {
        if (prefs == null || access == null) return "Paths: (unknown)";

        String parentTree = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
        String parentDir = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
        String conf = prefs.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null);

        boolean hasAnyParent = !access.safeTrim(parentTree).isEmpty() || !access.safeTrim(parentDir).isEmpty();
        if (!hasAnyParent) return "Paths: NOT SET";

        if (access.isContentUriString(parentTree) || ConfigStorage.isSafJoinedPath(conf)) {
            if (ConfigStorage.isSafJoinedPath(conf)) {
                if (!access.hasPersistedReadPermissionForSafJoinedDir(conf)) return "Paths: permission required";
                if (!access.canResolveSafJoinedDir(conf)) return "Paths: conf folder missing";
            }
            if (access.isContentUriString(parentTree) && !access.hasPersistedReadPermission(parentTree)) {
                return "Paths: parent permission required";
            }
            return "Paths: SAF OK";
        }

        return "Paths: Local";
    }
}
