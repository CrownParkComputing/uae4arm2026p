package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

final class BootstrapMediaPickerUtils {
    private BootstrapMediaPickerUtils() {
    }

    interface InitialUriConfigurer {
        void configure(Intent intent, String prefKey);
    }

    static Intent createFloppyPickIntent(InitialUriConfigurer initialUriConfigurer) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream"
        });

        if (initialUriConfigurer != null) {
            initialUriConfigurer.configure(intent, UaeOptionKeys.UAE_PATH_FLOPPIES_DIR);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        return intent;
    }

    static Intent createCdPickIntent(InitialUriConfigurer initialUriConfigurer) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/octet-stream",
            "application/x-cd-image",
            "application/x-iso9660-image"
        });

        if (initialUriConfigurer != null) {
            initialUriConfigurer.configure(intent, UaeOptionKeys.UAE_PATH_CDROMS_DIR);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return intent;
    }

    static boolean isValidFloppyExtension(String name) {
        if (name == null) return false;
        String ext = lowerExt(name);
        return "adf".equals(ext) || "zip".equals(ext);
    }

    static boolean isValidHdfExtension(String name) {
        if (name == null) return false;
        return "hdf".equals(lowerExt(name));
    }

    static boolean isValidCdExtension(String name) {
        if (name == null) return false;
        String ext = lowerExt(name);
        return "iso".equals(ext)
            || "cue".equals(ext)
            || "bin".equals(ext)
            || "chd".equals(ext)
            || "wav".equals(ext)
            || "flac".equals(ext)
            || "mp3".equals(ext)
            || "ogg".equals(ext)
            || "aiff".equals(ext)
            || "aif".equals(ext);
    }

    static boolean validateAndRejectIfWrongExtension(Activity activity, Uri uri, String displayName, String[] allowed, String hint) {
        if (activity == null || uri == null) return false;
        String name = displayName != null ? displayName : "";
        String ext = lowerExt(name);
        for (String allowedExt : allowed) {
            if (allowedExt.equals(ext)) return false;
        }

        Toast.makeText(
            activity,
            "Wrong file type selected: ." + (ext.isEmpty() ? "?" : ext) + "\n" + hint,
            Toast.LENGTH_LONG
        ).show();
        return true;
    }

    private static String lowerExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
