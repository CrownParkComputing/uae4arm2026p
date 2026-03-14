package com.uae4arm2026;

import android.content.Intent;
import android.widget.Toast;

import java.io.File;

final class BootstrapAdfLibraryResultHandler {

    interface Callbacks {
        void applyDfImportResultFromOnline(int dfIndex, File sourceFile, String sourceLabel, File targetDir);

        void applyDfSelectionFromContentUri(int dfIndex, String contentUri, String sourceLabel);

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
                    String trimmed = selectedPath.trim();
                    boolean isSaf = trimmed.startsWith("content://");
                    if (isSaf) {
                        callbacks.applyDfSelectionFromContentUri(dfIndex, trimmed, selectedTitle);
                        if (additionalPaths != null && additionalPaths.length > 0) {
                            int nextSlot = dfIndex + 1;
                            for (String extraPath : additionalPaths) {
                                if (nextSlot > 3) break;
                                if (extraPath == null || extraPath.trim().isEmpty()) continue;
                                String ep = extraPath.trim();
                                if (ep.startsWith("content://")) {
                                    String extraLabel = safDisplayName(ep);
                                    callbacks.applyDfSelectionFromContentUri(nextSlot, ep, extraLabel);
                                } else {
                                    File extra = new File(ep);
                                    if (!extra.exists() || !extra.isFile()) continue;
                                    callbacks.applyDfImportResultFromOnline(nextSlot, extra, extra.getName(), callbacks.getInternalDisksDir());
                                }
                                nextSlot++;
                            }
                        }
                        Toast.makeText(activity, "Inserted DF" + dfIndex + ": " + (selectedTitle == null ? trimmed : selectedTitle), Toast.LENGTH_SHORT).show();
                    } else {
                        File selected = new File(trimmed);
                        if (selected.exists() && selected.isFile()) {
                            callbacks.applyDfImportResultFromOnline(dfIndex, selected, selectedTitle, callbacks.getInternalDisksDir());
                            if (additionalPaths != null && additionalPaths.length > 0) {
                                int nextSlot = dfIndex + 1;
                                for (String extraPath : additionalPaths) {
                                    if (nextSlot > 3) break;
                                    if (extraPath == null || extraPath.trim().isEmpty()) continue;
                                    String ep = extraPath.trim();
                                    if (ep.startsWith("content://")) {
                                        String extraLabel = safDisplayName(ep);
                                        callbacks.applyDfSelectionFromContentUri(nextSlot, ep, extraLabel);
                                    } else {
                                        File extra = new File(ep);
                                        if (!extra.exists() || !extra.isFile()) continue;
                                        callbacks.applyDfImportResultFromOnline(nextSlot, extra, extra.getName(), callbacks.getInternalDisksDir());
                                    }
                                    nextSlot++;
                                }
                            }
                            Toast.makeText(activity, "Inserted DF" + dfIndex + ": " + (selectedTitle == null ? selected.getName() : selectedTitle), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (Throwable t) {
                Toast.makeText(activity, "ADF library result failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        return true;
    }

    /** Extract a filename from a content URI for display purposes. */
    private static String safDisplayName(String contentUri) {
        if (contentUri == null) return null;
        // Try to get last path segment as display name
        int lastSlash = contentUri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < contentUri.length() - 1) {
            String segment = contentUri.substring(lastSlash + 1);
            // URI-decode %XX sequences
            try {
                return java.net.URLDecoder.decode(segment, "UTF-8");
            } catch (Throwable ignored) {
                return segment;
            }
        }
        return contentUri;
    }
}