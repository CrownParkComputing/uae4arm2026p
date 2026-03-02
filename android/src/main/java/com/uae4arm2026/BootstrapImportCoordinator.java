package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

final class BootstrapImportCoordinator {
    interface IntentCustomizer {
        void apply(Intent intent);
    }

    private BootstrapImportCoordinator() {
    }

    static void openDocumentPicker(Activity activity, int requestCode, String mimeType,
                                   boolean localOnly, boolean allowWrite,
                                   IntentCustomizer customizer) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType == null ? "*/*" : mimeType);
        if (customizer != null) {
            try {
                customizer.apply(intent);
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (localOnly) {
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (allowWrite) {
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        activity.startActivityForResult(intent, requestCode);
    }

    static void openDocumentTreePicker(Activity activity, int requestCode,
                                       IntentCustomizer customizer) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (customizer != null) {
            try {
                customizer.apply(intent);
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        activity.startActivityForResult(intent, requestCode);
    }
}
