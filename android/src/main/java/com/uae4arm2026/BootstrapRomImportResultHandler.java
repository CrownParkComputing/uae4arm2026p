package com.uae4arm2026;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

final class BootstrapRomImportResultHandler {

    interface Callbacks {
        void takeReadPermissionIfPossible(Uri uri, int takeFlags);

        File getInternalRomsDir();

        void ensureDir(File dir);

        File guessDestFileForUri(Uri uri, File destDir, String fallbackPrefix);

        boolean importToFile(Uri uri, File dest);

        String getDisplayName(Uri uri);

        SharedPreferences getUaePrefs();

        SharedPreferences getBootstrapPrefs();

        String getSelectedQsModelId();

        void logInfo(String message);
    }

    static final class ImportOutcome {
        final boolean success;
        final File selectedFile;
        final String sourceName;
        final String pendingMapModelId;
        final boolean pendingMapIsExt;

        private ImportOutcome(boolean success,
                              File selectedFile,
                              String sourceName,
                              String pendingMapModelId,
                              boolean pendingMapIsExt) {
            this.success = success;
            this.selectedFile = selectedFile;
            this.sourceName = sourceName;
            this.pendingMapModelId = pendingMapModelId;
            this.pendingMapIsExt = pendingMapIsExt;
        }

        static ImportOutcome failed(String pendingMapModelId, boolean pendingMapIsExt) {
            return new ImportOutcome(false, null, null, pendingMapModelId, pendingMapIsExt);
        }

        static ImportOutcome success(File selectedFile,
                                     String sourceName,
                                     String pendingMapModelId,
                                     boolean pendingMapIsExt) {
            return new ImportOutcome(true, selectedFile, sourceName, pendingMapModelId, pendingMapIsExt);
        }
    }

    private BootstrapRomImportResultHandler() {
    }

    static void handleWhdloadImport(BootstrapActivity activity, Uri uri, Intent data, Callbacks callbacks) {
        if (uri == null) return;
        try {
            callbacks.takeReadPermissionIfPossible(uri, data.getFlags());
        } catch (Throwable ignored) {
        }

        Intent launch = new Intent(activity, AmiberryActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        launch.putExtra(AmiberryActivity.EXTRA_WHDLOAD_FILE, uri.toString());
        launch.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
        activity.startActivity(launch);
        activity.finish();
    }

    static ImportOutcome handleKickstartImport(BootstrapActivity activity,
                                               Uri uri,
                                               String internalKickstartPrefix,
                                               String kickMapPrefix,
                                               String pendingMapModelId,
                                               boolean pendingMapIsExt,
                                               Callbacks callbacks) {
        if (uri == null) return ImportOutcome.failed(pendingMapModelId, pendingMapIsExt);

        File romsDir = callbacks.getInternalRomsDir();
        callbacks.ensureDir(romsDir);
        File dest = callbacks.guessDestFileForUri(uri, romsDir, internalKickstartPrefix);
        callbacks.logInfo("Importing Kickstart from URI: " + uri + " -> " + dest.getAbsolutePath());
        if (!callbacks.importToFile(uri, dest)) {
            Toast.makeText(activity, "Kickstart import failed", Toast.LENGTH_SHORT).show();
            return ImportOutcome.failed(pendingMapModelId, pendingMapIsExt);
        }

        callbacks.logInfo("Imported kickstart to: " + dest.getAbsolutePath());
        String src = callbacks.getDisplayName(uri);
        String sourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
        callbacks.getUaePrefs()
            .edit()
            .putString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, dest.getAbsolutePath())
            .putString(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL, sourceName)
            .apply();

        String nextPendingId = pendingMapModelId;
        boolean nextPendingIsExt = pendingMapIsExt;
        if (pendingMapModelId != null && !pendingMapModelId.trim().isEmpty() && !pendingMapIsExt) {
            String base = pendingMapModelId.trim().toUpperCase(Locale.ROOT);
            callbacks.getBootstrapPrefs().edit().putString(kickMapPrefix + base, dest.getAbsolutePath()).apply();
            nextPendingId = null;
            nextPendingIsExt = false;
        } else {
            String modelId = callbacks.getSelectedQsModelId();
            if (modelId != null && !modelId.trim().isEmpty()) {
                callbacks.getBootstrapPrefs().edit().putString(
                    kickMapPrefix + modelId.trim().toUpperCase(Locale.ROOT),
                    dest.getAbsolutePath()).apply();
            }
        }

        return ImportOutcome.success(dest, sourceName, nextPendingId, nextPendingIsExt);
    }

    static ImportOutcome handleExtRomImport(BootstrapActivity activity,
                                            Uri uri,
                                            String internalExtRomPrefix,
                                            String extMapPrefix,
                                            String pendingMapModelId,
                                            boolean pendingMapIsExt,
                                            Callbacks callbacks) {
        if (uri == null) return ImportOutcome.failed(pendingMapModelId, pendingMapIsExt);

        File romsDir = callbacks.getInternalRomsDir();
        callbacks.ensureDir(romsDir);
        File dest = callbacks.guessDestFileForUri(uri, romsDir, internalExtRomPrefix);
        callbacks.logInfo("Importing Ext ROM from URI: " + uri + " -> " + dest.getAbsolutePath());
        if (!callbacks.importToFile(uri, dest)) {
            Toast.makeText(activity, "Ext ROM import failed", Toast.LENGTH_SHORT).show();
            return ImportOutcome.failed(pendingMapModelId, pendingMapIsExt);
        }

        callbacks.logInfo("Imported ext ROM to: " + dest.getAbsolutePath());
        String src = callbacks.getDisplayName(uri);
        String sourceName = (src == null || src.trim().isEmpty()) ? uri.toString() : src;
        callbacks.getUaePrefs()
            .edit()
            .putString(UaeOptionKeys.UAE_ROM_EXT_FILE, dest.getAbsolutePath())
            .putString(UaeOptionKeys.UAE_ROM_EXT_LABEL, sourceName)
            .apply();

        String nextPendingId = pendingMapModelId;
        boolean nextPendingIsExt = pendingMapIsExt;
        if (pendingMapModelId != null && !pendingMapModelId.trim().isEmpty() && pendingMapIsExt) {
            String base = pendingMapModelId.trim().toUpperCase(Locale.ROOT);
            callbacks.getBootstrapPrefs().edit().putString(extMapPrefix + base, dest.getAbsolutePath()).apply();
            nextPendingId = null;
            nextPendingIsExt = false;
        } else {
            String modelId = callbacks.getSelectedQsModelId();
            if (modelId != null && "CD32".equalsIgnoreCase(modelId.trim())) {
                callbacks.getBootstrapPrefs().edit().putString(extMapPrefix + "CD32", dest.getAbsolutePath()).apply();
            }
        }

        return ImportOutcome.success(dest, sourceName, nextPendingId, nextPendingIsExt);
    }
}