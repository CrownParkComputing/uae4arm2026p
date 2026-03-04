package com.uae4arm2026;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BootstrapCdImageImportHandler {

    interface Callbacks {
        void takeReadPermissionIfPossible(Uri uri, int takeFlags);

        String getDisplayName(Uri uri);

        boolean isValidCdExtension(String displayName);

        void handleCueImport(List<Uri> uris, Map<Uri, String> displayNameByUri, Uri cueUri, String cueLabel);

        File getInternalCd0Dir();

        void deleteRecursive(File fileOrDir);

        void ensureDir(File dir);

        String safeFilename(String name, String fallback);

        boolean importToFile(Uri uri, File dest);

        void applyCdSelectionPath(String cdPathOrUri, String sourceName);
    }

    private BootstrapCdImageImportHandler() {
    }

    static boolean handle(BootstrapActivity activity,
                          int requestCode,
                          int reqImportCdImage0,
                          Intent data,
                          int takeFlags,
                          Uri uri,
                          Callbacks callbacks) {
        if (requestCode != reqImportCdImage0) return false;

        List<Uri> uris = new ArrayList<>();
        Map<Uri, String> displayNameByUri = new HashMap<>();
        if (data != null && data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (uri != null) {
            uris.add(uri);
        }

        if (uris.isEmpty()) {
            Toast.makeText(activity, "No CD files selected", Toast.LENGTH_SHORT).show();
            return true;
        }

        Uri bestUri = null;
        String bestLabel = null;
        int bestPri = 999;

        for (Uri u : uris) {
            if (u == null) continue;
            callbacks.takeReadPermissionIfPossible(u, takeFlags);
            String displayName = callbacks.getDisplayName(u);
            if (displayName == null || displayName.trim().isEmpty()) displayName = "cd.bin";
            if (!callbacks.isValidCdExtension(displayName)) {
                continue;
            }
            displayNameByUri.put(u, displayName);
            int pri = BootstrapMediaUtils.cdMainPriority(BootstrapMediaUtils.lowerExt(displayName));
            if (pri < bestPri) {
                bestPri = pri;
                bestUri = u;
                bestLabel = displayName;
            }
        }

        if (bestUri == null) {
            Toast.makeText(activity, "Please select a CD image (.iso, .cue, .bin, or .chd)", Toast.LENGTH_LONG).show();
            return true;
        }

        if ("cue".equals(BootstrapMediaUtils.lowerExt(bestLabel))) {
            callbacks.handleCueImport(uris, displayNameByUri, bestUri, bestLabel);
            return true;
        }

        File cd0Dir = callbacks.getInternalCd0Dir();
        callbacks.deleteRecursive(cd0Dir);
        callbacks.ensureDir(cd0Dir);

        String fallbackName = "cdimage0.bin";
        String ext = BootstrapMediaUtils.lowerExt(bestLabel);
        if (ext != null && !ext.trim().isEmpty()) {
            fallbackName = "cdimage0." + ext;
        }
        File dest = new File(cd0Dir, callbacks.safeFilename(bestLabel, fallbackName));
        if (!callbacks.importToFile(bestUri, dest)) {
            Toast.makeText(activity, "Failed to import CD image", Toast.LENGTH_SHORT).show();
            return true;
        }

        callbacks.applyCdSelectionPath(dest.getAbsolutePath(), bestLabel);
        return true;
    }
}