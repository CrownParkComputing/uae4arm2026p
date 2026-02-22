package com.uae4arm2026;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;

public class PathsOptionsActivity extends Activity {

    private static final int REQ_PICK_PARENT_TREE = 6001;
    static final String EXTRA_AUTO_PICK_SAF_PARENT = "com.uae4arm2026.extra.AUTO_PICK_SAF_PARENT";

    private static final String DEST_SUBDIR_NAME = AppPaths.BASE_DIR_NAME;
    private static final String LEGACY_SUBDIR_NAME = AppPaths.LEGACY_BASE_DIR_NAME;

    private SharedPreferences prefs;

    private EditText etParent;

    private TextView tvConfig;
    private TextView tvRoms;
    private TextView tvFloppies;
    private TextView tvCdroms;
    private TextView tvHarddrives;
    private TextView tvLha;
    private TextView tvWhdboot;
    private TextView tvKickstarts;
    private TextView tvSavestates;
    private TextView tvScreens;

    private TextView tvSafSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paths_options);

        // Keep Apply/Save/Back visible above system navigation/gesture UI.
        UiInsets.applySystemBarsPaddingBottom(findViewById(android.R.id.content));

        prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        // Optional: jump straight into the SAF folder picker (used when launcher detects missing/revoked permissions).
        try {
            Intent it = getIntent();
            boolean autoPick = it != null && it.getBooleanExtra(EXTRA_AUTO_PICK_SAF_PARENT, false);
            if (autoPick) {
                pickDirectoryTree();
            }
        } catch (Throwable ignored) {
        }

        etParent = findViewById(R.id.etPathsParent);

        tvSafSource = findViewById(R.id.tvPathsSafSource);

        tvConfig = findViewById(R.id.tvPathConf);
        tvRoms = findViewById(R.id.tvPathRoms);
        tvFloppies = findViewById(R.id.tvPathFloppies);
        tvCdroms = findViewById(R.id.tvPathCdroms);
        tvHarddrives = findViewById(R.id.tvPathHarddrives);
        tvLha = findViewById(R.id.tvPathLha);
        tvWhdboot = findViewById(R.id.tvPathWhdboot);
        tvKickstarts = findViewById(R.id.tvPathKickstarts);
        tvSavestates = findViewById(R.id.tvPathSavestates);
        tvScreens = findViewById(R.id.tvPathScreens);

        Button btnUseInternal = findViewById(R.id.btnPathsUseInternal);
        Button btnBrowseSaf = findViewById(R.id.btnPathsBrowseSaf);
        Button btnUseExternal = findViewById(R.id.btnPathsUseExternal);
        Button btnUseSd = findViewById(R.id.btnPathsUseSd);
        Button btnApply = findViewById(R.id.btnPathsApply);
        Button btnSave = findViewById(R.id.btnPathsSave);
        Button btnBack = findViewById(R.id.btnPathsBack);
        Button btnCleanCopies = findViewById(R.id.btnPathsCleanCopies);

        btnUseInternal.setOnClickListener(v -> {
            etParent.setText(getInternalParent().getAbsolutePath());
            refreshPreview();
        });

        btnUseExternal.setOnClickListener(v -> {
            File p = getExternalParent(false);
            if (p != null) {
                etParent.setText(p.getAbsolutePath());
            } else {
                Toast.makeText(this, "External storage not available", Toast.LENGTH_SHORT).show();
            }
            refreshPreview();
        });

        btnUseSd.setOnClickListener(v -> {
            File p = getExternalParent(true);
            if (p != null) {
                etParent.setText(p.getAbsolutePath());
            } else {
                Toast.makeText(this, "SD card not available", Toast.LENGTH_SHORT).show();
            }
            refreshPreview();
        });

        btnBrowseSaf.setOnClickListener(v -> pickDirectoryTree());

        btnApply.setOnClickListener(v -> {
            applyFromParent(true);
            refreshPreview();
        });

        btnSave.setOnClickListener(v -> {
            applyFromParent(true);
            finish();
        });

        btnBack.setOnClickListener(v -> finish());

        btnCleanCopies.setOnClickListener(v -> {
            cleanAppCopies();
            Toast.makeText(this, "Cleaned app copies", Toast.LENGTH_SHORT).show();
        });

        String parent = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
        if (parent == null || parent.trim().isEmpty()) {
            File def = getExternalParent(false);
            if (def == null) def = getInternalParent();
            parent = def.getAbsolutePath();
        }
        etParent.setText(parent);

        String lastTree = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
        if (lastTree != null && !lastTree.trim().isEmpty()) {
            tvSafSource.setText("Selected SAF folder: " + lastTree.trim());
        } else {
            tvSafSource.setText("Selected SAF folder: (none)");
        }
        refreshPreview();

        try {
            btnUseSd.setEnabled(getExternalParent(true) != null);
        } catch (Throwable ignored) {
        }
    }

    private void pickDirectoryTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        }
        // Try to start inside the last used tree if available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String last = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            if (last != null && !last.trim().isEmpty()) {
                try {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(last.trim()));
                } catch (Throwable ignored) {
                }
            }
        }
        startActivityForResult(intent, REQ_PICK_PARENT_TREE);
    }

    private static boolean isSafTreeBase(String s) {
        if (s == null) return false;
        String p = s.trim();
        return p.startsWith("content://") && p.contains("::");
    }

    private static String normalizeSafTreeBase(String treeUri) {
        if (treeUri == null) return null;
        String p = treeUri.trim();
        if (!p.startsWith("content://")) return p;
        if (!p.contains("::")) p = p + "::";
        return p;
    }

    private static String joinSafTree(String treeBase, String relPath) {
        String base = normalizeSafTreeBase(treeBase);
        if (base == null) return null;
        if (!isSafTreeBase(base)) return null;
        if (relPath == null) return base;
        String rel = relPath.trim();
        if (rel.isEmpty()) return base;
        if (rel.startsWith("/")) rel = rel.substring(1);
        return base + "/" + rel;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK_PARENT_TREE) {
            Uri uri = data.getData();
            if (uri == null) return;

            // Persist SAF access (best-effort; some providers disallow persist).
            try {
                // Try to persist both read+write if possible (required for saving configs to SAF).
                int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try {
                    getContentResolver().takePersistableUriPermission(uri, rw);
                } catch (SecurityException ignored) {
                    // Fall back to whatever the provider granted in this result.
                    int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                        flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    }
                    flags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, flags);
                }
            } catch (Throwable ignored) {
            }

            prefs.edit().putString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, uri.toString()).apply();

            tvSafSource.setText("Selected SAF folder: " + uri);

            // Keep the SAF tree URI as our parent (Android 13/14 cannot reliably use /storage paths).
            // We store a tree-base in the form: content://...::
            String treeBase = normalizeSafTreeBase(uri.toString());
            etParent.setText(treeBase);
            applyFromParent(true);
            refreshPreview();
        }
    }

    private void setUiEnabled(boolean enabled) {
        try {
            findViewById(R.id.btnPathsUseInternal).setEnabled(enabled);
            findViewById(R.id.btnPathsUseExternal).setEnabled(enabled);
            findViewById(R.id.btnPathsUseSd).setEnabled(enabled);
            findViewById(R.id.btnPathsBrowseSaf).setEnabled(enabled);
            findViewById(R.id.btnPathsApply).setEnabled(enabled);
            findViewById(R.id.btnPathsSave).setEnabled(enabled);
            findViewById(R.id.btnPathsBack).setEnabled(enabled);
            etParent.setEnabled(enabled);
        } catch (Throwable ignored) {
        }
    }

    private File getDefaultParent() {
        File ext = getExternalParent(false);
        if (ext != null) return ext;
        return getInternalParent();
    }

    private File getInternalParent() {
        File preferred = new File(getFilesDir(), DEST_SUBDIR_NAME);
        File legacy = new File(getFilesDir(), LEGACY_SUBDIR_NAME);
        if (legacy.exists() && (!preferred.exists() || AppPaths.isEmptyDir(preferred))) return legacy;
        return preferred;
    }

    private File getExternalParent(boolean preferRemovable) {
        try {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs == null || dirs.length == 0) return null;

            File best = null;
            for (File d : dirs) {
                if (d == null) continue;
                boolean removable = Environment.isExternalStorageRemovable(d);
                if (preferRemovable) {
                    if (removable) {
                        best = d;
                        break;
                    }
                } else {
                    if (!removable) {
                        best = d;
                        break;
                    }
                }
            }

            if (best == null && !preferRemovable) {
                best = dirs[0];
            }
            if (best == null) return null;

            File preferred = new File(best, DEST_SUBDIR_NAME);
            File legacy = new File(best, LEGACY_SUBDIR_NAME);
            if (legacy.exists() && (!preferred.exists() || AppPaths.isEmptyDir(preferred))) return legacy;
            return preferred;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void cleanAppCopies() {
        // Remove any previously-imported/copied folders under our app-scoped parents.
        deleteRecursive(new File(getFilesDir(), DEST_SUBDIR_NAME));
        deleteRecursive(new File(getFilesDir(), LEGACY_SUBDIR_NAME));
        File ext = getExternalParent(false);
        if (ext != null) deleteRecursive(ext);
        File sd = getExternalParent(true);
        if (sd != null) deleteRecursive(sd);
    }

    private String tryResolveTreeUriToFilesystemPath(Uri treeUri) {
        if (treeUri == null) return null;
        try {
            String authority = treeUri.getAuthority();
            if (authority == null) return null;

            // Works for the standard ExternalStorageProvider from Android DocumentsUI.
            if ("com.android.externalstorage.documents".equals(authority)) {
                String docId = DocumentsContract.getTreeDocumentId(treeUri);
                if (docId == null) return null;

                int colon = docId.indexOf(':');
                String volume = colon >= 0 ? docId.substring(0, colon) : docId;
                String relPath = colon >= 0 ? docId.substring(colon + 1) : "";

                File base;
                if ("primary".equalsIgnoreCase(volume)) {
                    base = Environment.getExternalStorageDirectory();
                } else {
                    base = new File("/storage/" + volume);
                }

                File out = relPath.isEmpty() ? base : new File(base, relPath);
                return out.getAbsolutePath();
            }

            // Other providers (Downloads, cloud, etc.) generally cannot be mapped safely.
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalizeDir(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private void refreshPreview() {
        String parent = normalizeDir(etParent.getText() != null ? etParent.getText().toString() : "");

        if (isSafTreeBase(parent)) {
            String appBase = resolveSafAppSubdirName(/*create*/false);
            String prefix = (appBase == null || appBase.trim().isEmpty()) ? "" : (appBase.trim() + "/");

            String conf = joinSafTree(parent, prefix + "conf");
            String roms = joinSafTree(parent, prefix + "roms");
            String flops = joinSafTree(parent, prefix + "floppies");
            String cds = joinSafTree(parent, prefix + "cdroms");
            String hds = joinSafTree(parent, prefix + "harddrives");
            String lha = joinSafTree(parent, prefix + "lha");
            String whdboot = joinSafTree(parent, prefix + "whdboot");
            String kickstarts = joinSafTree(parent, prefix + "kickstarts");
            String saves = joinSafTree(parent, prefix + "savestates");
            String screens = joinSafTree(parent, prefix + "screens");

            tvConfig.setText(conf);
            tvRoms.setText(roms);
            tvFloppies.setText(flops);
            tvCdroms.setText(cds);
            tvHarddrives.setText(hds);
            tvLha.setText(lha);
            tvWhdboot.setText(whdboot);
            tvKickstarts.setText(kickstarts);
            tvSavestates.setText(saves);
            tvScreens.setText(screens);
            return;
        }

        // Always preview based on the current parent field.
        // (Using saved prefs here makes the UI look like it never changes, since those values
        // remain non-empty after the first save/import.)
        String conf = new File(parent, "conf").getAbsolutePath();
        String roms = new File(parent, "roms").getAbsolutePath();
        String flops = new File(parent, "floppies").getAbsolutePath();
        String cds = new File(parent, "cdroms").getAbsolutePath();
        String hds = new File(parent, "harddrives").getAbsolutePath();
        String lha = new File(parent, "lha").getAbsolutePath();
        String whdboot = new File(parent, "whdboot").getAbsolutePath();
        String kickstarts = new File(parent, "kickstarts").getAbsolutePath();
        String saves = new File(parent, "savestates").getAbsolutePath();
        String screens = new File(parent, "screens").getAbsolutePath();

        tvConfig.setText(conf);
        tvRoms.setText(roms);
        tvFloppies.setText(flops);
        tvCdroms.setText(cds);
        tvHarddrives.setText(hds);
        tvLha.setText(lha);
        tvWhdboot.setText(whdboot);
        tvKickstarts.setText(kickstarts);
        tvSavestates.setText(saves);
        tvScreens.setText(screens);
    }

    private void applyFromParent(boolean createDirs) {
        String parent = normalizeDir(etParent.getText() != null ? etParent.getText().toString() : "");
        if (parent.isEmpty()) {
            parent = getDefaultParent().getAbsolutePath();
        }

        if (isSafTreeBase(parent)) {
            // Prefer keeping all app folders under a dedicated subfolder (uae4arm/ or legacy amiberry/).
            String appBase = resolveSafAppSubdirName(createDirs);
            String prefix = (appBase == null || appBase.trim().isEmpty()) ? "" : (appBase.trim() + "/");

            String conf = joinSafTree(parent, prefix + "conf");
            String roms = joinSafTree(parent, prefix + "roms");
            String flops = joinSafTree(parent, prefix + "floppies");
            String cds = joinSafTree(parent, prefix + "cdroms");
            String hds = joinSafTree(parent, prefix + "harddrives");
            String lha = joinSafTree(parent, prefix + "lha");
            String whdboot = joinSafTree(parent, prefix + "whdboot");
            String kickstarts = joinSafTree(parent, prefix + "kickstarts");
            String saves = joinSafTree(parent, prefix + "savestates");
            String screens = joinSafTree(parent, prefix + "screens");

            SharedPreferences.Editor e = prefs.edit();
            e.putString(UaeOptionKeys.UAE_PATH_PARENT_DIR, parent);
            e.putString(UaeOptionKeys.UAE_PATH_CONF_DIR, conf);
            e.putString(UaeOptionKeys.UAE_PATH_ROMS_DIR, roms);
            e.putString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, flops);
            e.putString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, cds);
            e.putString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, hds);
            e.putString(UaeOptionKeys.UAE_PATH_LHA_DIR, lha);
            e.putString(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, whdboot);
            e.putString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, kickstarts);
            e.putString(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, saves);
            e.putString(UaeOptionKeys.UAE_PATH_SCREENS_DIR, screens);
            e.apply();

            if (createDirs) {
                // Best-effort: create the standard folder structure inside the selected tree.
                // This prevents "conf folder cannot be accessed" on first run.
                if (!ensureSafDirsExist(prefix)) {
                    Toast.makeText(this, "Unable to create folders in selected SAF location. Please re-select a different folder.", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }

        String conf = new File(parent, "conf").getAbsolutePath();
        String roms = new File(parent, "roms").getAbsolutePath();
        String flops = new File(parent, "floppies").getAbsolutePath();
        String cds = new File(parent, "cdroms").getAbsolutePath();
        String hds = new File(parent, "harddrives").getAbsolutePath();
        String lha = new File(parent, "lha").getAbsolutePath();
        String whdboot = new File(parent, "whdboot").getAbsolutePath();
        String kickstarts = new File(parent, "kickstarts").getAbsolutePath();
        String saves = new File(parent, "savestates").getAbsolutePath();
        String screens = new File(parent, "screens").getAbsolutePath();

        SharedPreferences.Editor e = prefs.edit();
        e.putString(UaeOptionKeys.UAE_PATH_PARENT_DIR, parent);
        e.putString(UaeOptionKeys.UAE_PATH_CONF_DIR, conf);
        e.putString(UaeOptionKeys.UAE_PATH_ROMS_DIR, roms);
        e.putString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, flops);
        e.putString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, cds);
        e.putString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, hds);
        e.putString(UaeOptionKeys.UAE_PATH_LHA_DIR, lha);
        e.putString(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, whdboot);
        e.putString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, kickstarts);
        e.putString(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, saves);
        e.putString(UaeOptionKeys.UAE_PATH_SCREENS_DIR, screens);
        e.apply();

        if (createDirs) {
            new File(parent).mkdirs();
            new File(conf).mkdirs();
            new File(roms).mkdirs();
            new File(flops).mkdirs();
            new File(cds).mkdirs();
            new File(hds).mkdirs();
            new File(lha).mkdirs();
            new File(whdboot).mkdirs();
            new File(kickstarts).mkdirs();
            new File(saves).mkdirs();
            new File(screens).mkdirs();
        }
    }

    private boolean ensureSafDirsExist(String prefix) {
        try {
            String parentTree = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            if (parentTree == null || parentTree.trim().isEmpty()) return false;

            Uri treeUri = Uri.parse(parentTree.trim());
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.exists() || !root.isDirectory()) return false;

            String base = (prefix == null) ? "" : prefix.trim();
            if (base.startsWith("/")) base = base.substring(1);
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            DocumentFile baseDir = root;
            if (!base.isEmpty()) {
                baseDir = ensureDirectory(root, base);
                if (baseDir == null) return false;
            }

            String[] dirs = new String[] {
                "conf",
                "roms",
                "floppies",
                "cdroms",
                "harddrives",
                "lha",
                "whdboot",
                "kickstarts",
                "savestates",
                "screens"
            };
            for (String d : dirs) {
                if (d == null || d.trim().isEmpty()) continue;
                if (ensureDirectory(baseDir, d.trim()) == null) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static DocumentFile ensureDirectory(DocumentFile parent, String name) {
        if (parent == null || name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        try {
            DocumentFile existing = parent.findFile(n);
            if (existing != null && existing.exists() && existing.isDirectory()) return existing;
        } catch (Throwable ignored) {
        }
        try {
            return parent.createDirectory(n);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveSafAppSubdirName(boolean create) {
        try {
            String parentTree = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            if (parentTree == null || parentTree.trim().isEmpty()) return DEST_SUBDIR_NAME;

            Uri treeUri = Uri.parse(parentTree.trim());
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.exists() || !root.isDirectory()) return DEST_SUBDIR_NAME;

            // If the user selected a folder that already looks like an app base (it already contains
            // conf/ or kickstarts/ etc.), don't force an extra uae4arm/ layer. This preserves
            // existing directory layouts and avoids "Kickstarts folder empty" surprises.
            try {
                DocumentFile conf = root.findFile("conf");
                if (conf != null && conf.exists() && conf.isDirectory()) return "";
            } catch (Throwable ignored) {
            }
            try {
                DocumentFile kick = root.findFile("kickstarts");
                if (kick != null && kick.exists() && kick.isDirectory()) return "";
            } catch (Throwable ignored) {
            }
            try {
                DocumentFile roms = root.findFile("roms");
                if (roms != null && roms.exists() && roms.isDirectory()) return "";
            } catch (Throwable ignored) {
            }

            DocumentFile preferred = null;
            DocumentFile legacy = null;
            try { preferred = root.findFile(DEST_SUBDIR_NAME); } catch (Throwable ignored) {}
            try { legacy = root.findFile(LEGACY_SUBDIR_NAME); } catch (Throwable ignored) {}

            boolean hasPreferred = preferred != null && preferred.exists() && preferred.isDirectory();
            boolean hasLegacy = legacy != null && legacy.exists() && legacy.isDirectory();

            if (hasPreferred) return DEST_SUBDIR_NAME;
            if (hasLegacy) return LEGACY_SUBDIR_NAME;

            if (create) {
                DocumentFile created = ensureDirectory(root, DEST_SUBDIR_NAME);
                if (created != null) return DEST_SUBDIR_NAME;
            }
        } catch (Throwable ignored) {
        }
        return DEST_SUBDIR_NAME;
    }
}
