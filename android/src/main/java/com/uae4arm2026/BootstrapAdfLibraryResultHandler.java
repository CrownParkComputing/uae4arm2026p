package com.uae4arm2026;

import android.content.Intent;
import android.widget.Toast;

import java.io.File;

final class BootstrapAdfLibraryResultHandler {

    interface Callbacks {
        void applyDfImportResultFromOnline(int dfIndex, File sourceFile, String sourceLabel, File targetDir);

        File getInternalDisksDir();
    }

    private BootstrapAdfLibraryResultHandler() {
    }

    static boolean handle(BootstrapActivity activity,
                          int requestCode,
                          int resultCode,
                          Intent data,
                          int reqAdfLibrary,
                          Callbacks callbacks) {
        if (requestCode != reqAdfLibrary) return false;

        if (resultCode == BootstrapActivity.RESULT_OK && data != null) {
            try {
                int dfIndex = Math.max(0, Math.min(3, data.getIntExtra(AdfLibraryActivity.EXTRA_TARGET_DF, 0)));
                String selectedPath = data.getStringExtra(AdfLibraryActivity.EXTRA_SELECTED_PATH);
                String selectedTitle = data.getStringExtra(AdfLibraryActivity.EXTRA_SELECTED_TITLE);
                String[] additionalPaths = data.getStringArrayExtra(AdfLibraryActivity.EXTRA_ADDITIONAL_PATHS);
                if (selectedPath != null && !selectedPath.trim().isEmpty()) {
                    File selected = new File(selectedPath.trim());
                    if (selected.exists() && selected.isFile()) {
                        callbacks.applyDfImportResultFromOnline(dfIndex, selected, selectedTitle, callbacks.getInternalDisksDir());
                        if (additionalPaths != null && additionalPaths.length > 0) {
                            int nextSlot = dfIndex + 1;
                            for (String extraPath : additionalPaths) {
                                if (nextSlot > 3) break;
                                if (extraPath == null || extraPath.trim().isEmpty()) continue;
                                File extra = new File(extraPath.trim());
                                if (!extra.exists() || !extra.isFile()) continue;
                                String extraLabel = extra.getName();
                                callbacks.applyDfImportResultFromOnline(nextSlot, extra, extraLabel, callbacks.getInternalDisksDir());
                                nextSlot++;
                            }
                        }
                        Toast.makeText(activity, "Inserted DF" + dfIndex + ": " + (selectedTitle == null ? selected.getName() : selectedTitle), Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Throwable t) {
                Toast.makeText(activity, "ADF library result failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        return true;
    }
}