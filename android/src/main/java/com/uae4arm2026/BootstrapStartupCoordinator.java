package com.uae4arm2026;

import android.content.SharedPreferences;
import android.os.Bundle;

final class BootstrapStartupCoordinator {
    private BootstrapStartupCoordinator() {
    }

    interface SafPermissionChecker {
        boolean hasReadPermission(String uriString);
        boolean hasWritePermission(String uriString);
    }

    interface PathSetupChecker {
        boolean hasUsablePathSetupForStartup();
    }

    static void enforceSetupOnAppUpdateIfNeeded(
        boolean launchedFromEmulatorMenu,
        SharedPreferences launcherPrefs,
        SharedPreferences uaePrefs,
        long currentVersion,
        long currentUpdateTime,
        String prefLastVersion,
        String prefLastUpdate,
        String prefForceWalkthrough,
        String prefWalkthroughCompleted,
        String prefFirstRunDone,
        String prefPathsParentPromptShown,
        String prefRequiredPathsPromptShown,
        SafPermissionChecker permissionChecker
    ) {
        if (launchedFromEmulatorMenu || launcherPrefs == null || uaePrefs == null || permissionChecker == null) {
            return;
        }

        if (currentVersion <= 0L && currentUpdateTime <= 0L) return;

        long lastSeenVersion = launcherPrefs.getLong(prefLastVersion, -1L);
        long lastSeenUpdateTime = launcherPrefs.getLong(prefLastUpdate, -1L);

        if (lastSeenVersion < 0L && lastSeenUpdateTime < 0L) {
            launcherPrefs.edit()
                .putLong(prefLastVersion, currentVersion)
                .putLong(prefLastUpdate, currentUpdateTime)
                .apply();
            return;
        }

        boolean updatedByVersion = currentVersion > 0L && lastSeenVersion > 0L && currentVersion > lastSeenVersion;
        boolean updatedByInstallTime = currentUpdateTime > 0L && currentUpdateTime != lastSeenUpdateTime;

        if (updatedByVersion || updatedByInstallTime) {
            boolean safPermissionStillValid = false;
            String parentTree = uaePrefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            if (parentTree != null && !parentTree.trim().isEmpty() && isContentUriString(parentTree)) {
                safPermissionStillValid = permissionChecker.hasReadPermission(parentTree)
                    && permissionChecker.hasWritePermission(parentTree);
            } else if (parentTree == null || parentTree.trim().isEmpty()) {
                safPermissionStillValid = false;
            } else {
                safPermissionStillValid = true;
            }

            SharedPreferences.Editor editor = launcherPrefs.edit()
                .putLong(prefLastVersion, currentVersion)
                .putLong(prefLastUpdate, currentUpdateTime)
                .putBoolean(prefForceWalkthrough, true);

            if (!safPermissionStillValid) {
                editor.putBoolean(prefWalkthroughCompleted, false)
                    .putBoolean(prefFirstRunDone, false)
                    .putBoolean(prefPathsParentPromptShown, false)
                    .putBoolean(prefRequiredPathsPromptShown, false);
            }
            editor.apply();
            return;
        }

        if (currentVersion != lastSeenVersion || currentUpdateTime != lastSeenUpdateTime) {
            launcherPrefs.edit()
                .putLong(prefLastVersion, currentVersion)
                .putLong(prefLastUpdate, currentUpdateTime)
                .apply();
        }
    }

    static void markFirstRunDoneIfNeeded(SharedPreferences launcherPrefs, String prefFirstRunDone) {
        if (launcherPrefs == null || prefFirstRunDone == null) return;
        if (launcherPrefs.getBoolean(prefFirstRunDone, false)) return;
        launcherPrefs.edit().putBoolean(prefFirstRunDone, true).apply();
    }

    static boolean shouldLaunchStartupWalkthrough(
        boolean launchedFromEmulatorMenu,
        Bundle savedInstanceState,
        SharedPreferences launcherPrefs,
        SharedPreferences uaePrefs,
        String prefForceWalkthrough,
        String prefFirstRunDone,
        PathSetupChecker pathSetupChecker
    ) {
        if (launchedFromEmulatorMenu || savedInstanceState != null || launcherPrefs == null || uaePrefs == null || pathSetupChecker == null) {
            return false;
        }

        boolean forceWalkthrough = launcherPrefs.getBoolean(prefForceWalkthrough, false);
        boolean walkthroughCompleted = uaePrefs.getBoolean(WalkthroughActivity.PREF_WALKTHROUGH_COMPLETED, false);
        boolean walkthroughDisabled = uaePrefs.getBoolean(WalkthroughActivity.PREF_WALKTHROUGH_DISABLED, false);

        boolean needsSetup = !pathSetupChecker.hasUsablePathSetupForStartup();
        boolean shouldLaunch = forceWalkthrough || (!walkthroughDisabled && !walkthroughCompleted && needsSetup);
        if (!shouldLaunch) return false;

        launcherPrefs.edit()
            .putBoolean(prefForceWalkthrough, false)
            .putBoolean(prefFirstRunDone, true)
            .apply();
        return true;
    }

    private static boolean isContentUriString(String value) {
        return value != null && value.trim().startsWith("content://");
    }
}
