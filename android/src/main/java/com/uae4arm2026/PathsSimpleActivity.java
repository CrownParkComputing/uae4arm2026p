package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple per-folder Paths screen (Internal / SD / SAF Pick for each category).
 *
 * This avoids the "parent folder -> derived subfolders" model, which can be confusing and can
 * break when a user’s existing folder layout doesn’t match the expected structure.
 */
public class PathsSimpleActivity extends Activity {

    // Optional: auto-open the SAF picker for a specific category key.
    // Use values like: "conf", "kickstarts", "harddrives", ...
    public static final String EXTRA_AUTO_PICK = "com.uae4arm2026.extra.PATHS_SIMPLE_AUTO_PICK";

    private static final int REQ_PICK_PARENT = 7099;

    private static final class Slot {
        final String label;
        final String subdir;     // internal/app-scoped folder name
        final String prefKey;    // UaeOptionKeys.UAE_PATH_* key
        final int reqCode;

        TextView tvValue;

        Slot(String label, String subdir, String prefKey, int reqCode) {
            this.label = label;
            this.subdir = subdir;
            this.prefKey = prefKey;
            this.reqCode = reqCode;
        }
    }

    private SharedPreferences prefs;
    private final Map<Integer, Slot> reqToSlot = new LinkedHashMap<>();
    private final Map<String, Slot> nameToSlot = new LinkedHashMap<>();
    private final Map<String, String> pending = new HashMap<>();
    private TextView tvParent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paths_simple);

        UiInsets.applySystemBarsPaddingBottom(findViewById(android.R.id.content));

        prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        tvParent = findViewById(R.id.tvPathsSimpleParent);
        Button btnPickParent = findViewById(R.id.btnPathsSimplePickParent);
        Button btnAutoFill = findViewById(R.id.btnPathsSimpleAutoFill);
        if (btnPickParent != null) {
            compactButton(btnPickParent);
            btnPickParent.setOnClickListener(v -> pickSafFolder(REQ_PICK_PARENT));
        }
        if (btnAutoFill != null) {
            compactButton(btnAutoFill);
            btnAutoFill.setOnClickListener(v -> {
                if (!autoFillFromParent()) {
                    Toast.makeText(this, "Pick a parent folder first", Toast.LENGTH_SHORT).show();
                } else {
                    saveAll();
                    Toast.makeText(this, "Auto-filled", Toast.LENGTH_SHORT).show();
                }
            });
        }

        LinearLayout singleCol = findViewById(R.id.pathsSingleCol);
        if (singleCol != null) {
            buildSlotsSingleCol(singleCol);
        } else {
            // Fallback to two-column if available
            LinearLayout left = findViewById(R.id.pathsColLeft);
            LinearLayout right = findViewById(R.id.pathsColRight);
            if (left != null && right != null) {
                buildSlotsTwoCol(left, right);
            }
        }

        Button save = findViewById(R.id.btnPathsSimpleSave);
        if (save != null) {
            compactButton(save);
            save.setOnClickListener(v -> {
                saveAll();
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            });
        }

        Button back = findViewById(R.id.btnPathsSimpleBack);
        if (back != null) {
            compactButton(back);
            back.setOnClickListener(v -> finish());
        }

        loadPendingFromPrefs();
        refreshAll();

        // Optional: auto-open picker.
        tryAutoPick();
    }

    private void tryAutoPick() {
        try {
            Intent it = getIntent();
            String which = it != null ? it.getStringExtra(EXTRA_AUTO_PICK) : null;
            if (which == null || which.trim().isEmpty()) return;
            if ("parent".equalsIgnoreCase(which.trim())) {
                pickSafFolder(REQ_PICK_PARENT);
                return;
            }
            Slot s = nameToSlot.get(which.trim().toLowerCase());
            if (s != null) {
                pickSafFolder(s.reqCode);
            }
        } catch (Throwable ignored) {
        }
    }

    private void buildSlotsSingleCol(LinearLayout container) {
        // Stable order.
        Slot[] slots = new Slot[] {
            new Slot("Configs (conf)", "conf", UaeOptionKeys.UAE_PATH_CONF_DIR, 7001),
            new Slot("Kickstarts", "kickstarts", UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, 7002),
            new Slot("Harddrives", "harddrives", UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, 7003),
            new Slot("Floppies", "disks", UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, 7004),
            new Slot("CD-ROMs", "cdroms", UaeOptionKeys.UAE_PATH_CDROMS_DIR, 7005),
            new Slot("ROMs", "roms", UaeOptionKeys.UAE_PATH_ROMS_DIR, 7006),
            new Slot("Savestates", "savestates", UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, 7007),
            new Slot("Screens", "screens", UaeOptionKeys.UAE_PATH_SCREENS_DIR, 7008),
            new Slot("LHA", "lha", UaeOptionKeys.UAE_PATH_LHA_DIR, 7009),
            new Slot("WHDboot", "whdboot", UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, 7010),
        };

        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            reqToSlot.put(s.reqCode, s);
            nameToSlot.put(s.subdir.toLowerCase(), s);
            if ("disks".equalsIgnoreCase(s.subdir)) nameToSlot.put("floppies", s);
            if ("conf".equalsIgnoreCase(s.subdir)) nameToSlot.put("configs", s);
            container.addView(buildSlotRowCompact(s));
        }
    }

    private View buildSlotRowLarge(Slot s) {
        float d = getResources().getDisplayMetrics().density;
        int padV = (int) (10 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, padV, 0, padV);

        // Card-like background for each row
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setCornerRadius(8 * d);
        root.setBackground(bg);
        root.setPadding((int)(12*d), (int)(10*d), (int)(12*d), (int)(10*d));

        TextView title = new TextView(this);
        title.setText(s.label);
        title.setTextSize(15);
        title.setTextColor(0xFF4CAF50);
        title.setPadding(0, 0, 0, (int) (4 * d));

        TextView value = new TextView(this);
        value.setText("(not set)");
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setTextSize(13);
        value.setMaxLines(3);
        value.setTextColor(0xFFFFFFFF);
        value.setPadding(0, 0, 0, (int) (8 * d));
        s.tvValue = value;

        Button bBrowse = new Button(this);
        bBrowse.setText("Browse");
        bBrowse.setTextSize(13);
        bBrowse.setAllCaps(false);
        compactButton(bBrowse);
        bBrowse.setOnClickListener(v -> pickSafFolder(s.reqCode));

        root.addView(title);
        root.addView(value);
        root.addView(bBrowse);
        return root;
    }

    private void buildSlotsTwoCol(LinearLayout left, LinearLayout right) {
        // Stable order.
        Slot[] slots = new Slot[] {
            new Slot("Configs (conf)", "conf", UaeOptionKeys.UAE_PATH_CONF_DIR, 7001),
            new Slot("Kickstarts", "kickstarts", UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, 7002),
            new Slot("Harddrives", "harddrives", UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, 7003),
            new Slot("Floppies", "disks", UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, 7004),
            new Slot("CD-ROMs", "cdroms", UaeOptionKeys.UAE_PATH_CDROMS_DIR, 7005),
            new Slot("ROMs", "roms", UaeOptionKeys.UAE_PATH_ROMS_DIR, 7006),
            new Slot("Savestates", "savestates", UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, 7007),
            new Slot("Screens", "screens", UaeOptionKeys.UAE_PATH_SCREENS_DIR, 7008),
            new Slot("LHA", "lha", UaeOptionKeys.UAE_PATH_LHA_DIR, 7009),
            new Slot("WHDboot", "whdboot", UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, 7010),
        };

        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            reqToSlot.put(s.reqCode, s);
            nameToSlot.put(s.subdir.toLowerCase(), s);
            if ("disks".equalsIgnoreCase(s.subdir)) nameToSlot.put("floppies", s);
            if ("conf".equalsIgnoreCase(s.subdir)) nameToSlot.put("configs", s);

            // Alternate to keep a balanced 2-column layout (fits on one page in landscape).
            if ((i % 2) == 0) {
                left.addView(buildSlotRowCompact(s));
            } else {
                right.addView(buildSlotRowCompact(s));
            }
        }
    }

    private View buildSlotRowCompact(Slot s) {
        float d = getResources().getDisplayMetrics().density;
        int padV = (int) (4 * d);
        int gap = (int) (6 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, padV, 0, padV);

        TextView title = new TextView(this);
        title.setText(s.label);
        title.setTextSize(13);
        title.setPadding(0, 0, 0, (int) (1 * d));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = new TextView(this);
        value.setText("(not set)");
        value.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        value.setTextSize(12);
        value.setMaxLines(1);
        LinearLayout.LayoutParams lpValue = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        value.setLayoutParams(lpValue);
        s.tvValue = value;

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        buttons.setPadding(0, gap, 0, 0);

        Button bBrowse = new Button(this);
        bBrowse.setText("…");
        compactButton(bBrowse);
        bBrowse.setOnClickListener(v -> pickSafFolder(s.reqCode));

        // Put the thin browse button on the same line as the value so rows stay compact.
        line.addView(value);
        line.addView(space((int) (6 * d)));
        line.addView(bBrowse);

        root.addView(title);
        root.addView(line);
        return root;
    }

    private void compactButton(Button b) {
        if (b == null) return;
        try { b.setAllCaps(false); } catch (Throwable ignored) {}
        try { b.setTextSize(12); } catch (Throwable ignored) {}
        try {
            b.setIncludeFontPadding(false);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
        } catch (Throwable ignored) {
        }
        try {
            int padH = (int) (8 * getResources().getDisplayMetrics().density);
            int padV = (int) (2 * getResources().getDisplayMetrics().density);
            b.setPadding(padH, padV, padH, padV);
        } catch (Throwable ignored) {
        }
    }

    private View space(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, 1));
        return v;
    }


    private void refreshAll() {
        if (tvParent != null) {
            String parentTree = pending.get(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI);
            tvParent.setText(formatUserFacingPath(parentTree));
        }
        for (Slot s : reqToSlot.values()) {
            String v = pending.get(s.prefKey);
            if (s.tvValue != null) {
                s.tvValue.setText(formatUserFacingPath(v));
            }
        }
    }

    private String formatUserFacingPath(String configuredPath) {
        try {
            if (configuredPath == null || configuredPath.trim().isEmpty()) return "(not set)";
            String p = configuredPath.trim();

            // SAF joined path: show the full friendly path with subfolder
            if (ConfigStorage.isSafJoinedPath(p)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(p);
                if (sp != null && sp.treeUri != null) {
                    String pretty = prettySafTreeLabel(sp.treeUri);
                    return (pretty == null || pretty.trim().isEmpty()) ? "SAF folder" : pretty.trim();
                }
                return "SAF folder";
            }

            // Regular filesystem path: show as-is.
            return p;
        } catch (Throwable ignored) {
            return "(not set)";
        }
    }

    private String prettySafTreeLabel(String treeUriString) {
        if (treeUriString == null) return null;
        try {
            Uri u = Uri.parse(treeUriString.trim());
            DocumentFile df = DocumentFile.fromTreeUri(this, u);
            String name = (df != null) ? df.getName() : null;
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Throwable ignored) {
        }

        // Fallbacks: attempt to decode common ExternalStorageProvider tree IDs
        try {
            Uri u = Uri.parse(treeUriString.trim());
            String auth = u.getAuthority();
            if ("com.android.externalstorage.documents".equals(auth)) {
                String docId = DocumentsContract.getTreeDocumentId(u);
                if (docId != null) {
                    // docId commonly looks like "primary:uae4arm2025/harddrives"
                    return docId.replace("primary:", "Internal:");
                }
            }
        } catch (Throwable ignored) {
        }

        // Last resort: just show the last segment.
        try {
            Uri u = Uri.parse(treeUriString.trim());
            String last = u.getLastPathSegment();
            return (last == null) ? "Selected folder" : last;
        } catch (Throwable ignored) {
            return "Selected folder";
        }
    }

    private void loadPendingFromPrefs() {
        for (Slot s : reqToSlot.values()) {
            String v = prefs.getString(s.prefKey, null);
            if (v != null) pending.put(s.prefKey, v);
        }
        try {
            String parentTree = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
            if (parentTree != null) pending.put(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, parentTree);
            String parentDir = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_DIR, null);
            if (parentDir != null) pending.put(UaeOptionKeys.UAE_PATH_PARENT_DIR, parentDir);
        } catch (Throwable ignored) {
        }
    }

    private void saveAll() {
        SharedPreferences.Editor e = prefs.edit();
        for (Slot s : reqToSlot.values()) {
            String v = pending.get(s.prefKey);
            if (v == null || v.trim().isEmpty()) {
                e.remove(s.prefKey);
            } else {
                e.putString(s.prefKey, v.trim());
            }
        }
        // Also persist parent tree (used by bootstrap prompts and as a default initial folder).
        try {
            String parentTree = pending.get(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI);
            if (parentTree == null || parentTree.trim().isEmpty()) e.remove(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI);
            else e.putString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, parentTree.trim());

            String parentDir = pending.get(UaeOptionKeys.UAE_PATH_PARENT_DIR);
            if (parentDir == null || parentDir.trim().isEmpty()) e.remove(UaeOptionKeys.UAE_PATH_PARENT_DIR);
            else e.putString(UaeOptionKeys.UAE_PATH_PARENT_DIR, parentDir.trim());
        } catch (Throwable ignored) {
        }
        e.apply();
    }

    private boolean autoFillFromParent() {
        try {
            String treeUri = pending.get(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI);
            if (treeUri == null || treeUri.trim().isEmpty()) return false;

            Uri parentTree = Uri.parse(treeUri.trim());
            String base = toSafJoinedRoot(parentTree);
            if (base == null || base.trim().isEmpty()) return false;

            // Convenience: also store a "parent dir" in joined form for older code paths.
            pending.put(UaeOptionKeys.UAE_PATH_PARENT_DIR, base);

            // Fill each slot as <parent>/<slot.subdir>/ (as a SAF joined folder)
            for (Slot s : reqToSlot.values()) {
                String joined = base;
                if (!joined.endsWith("/")) joined = joined + "/";
                joined = joined + s.subdir + "/";
                pending.put(s.prefKey, joined);
            }
            refreshAll();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void pickSafFolder(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        }

        // Best-effort: start inside the currently configured folder if it's SAF.
        try {
            Slot s = reqToSlot.get(requestCode);
            if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String cur = pending.get(s.prefKey);
                if (cur == null || cur.trim().isEmpty()) {
                    cur = prefs.getString(s.prefKey, null);
                }
                Uri initial = buildInitialUri(cur);
                if (initial != null) {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                }
            }
        } catch (Throwable ignored) {
        }

        startActivityForResult(intent, requestCode);
    }

    private static String toSafJoinedRoot(Uri treeUri) {
        if (treeUri == null) return null;
        String s = treeUri.toString();
        if (!s.startsWith("content://")) return null;
        // joined format: content://...::/  (root)
        return s + "::/";
    }

    private Uri buildInitialUri(String configuredPath) {
        if (configuredPath == null) return null;
        String p = configuredPath.trim();
        if (p.isEmpty()) return null;

        if (ConfigStorage.isSafJoinedPath(p)) {
            try {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(p);
                if (sp != null && sp.treeUri != null) return Uri.parse(sp.treeUri);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void repairMalformedPathsForSelectedParent(String joinedParent) {
        if (joinedParent == null || joinedParent.trim().isEmpty()) return;

        fixPathForParent(UaeOptionKeys.UAE_PATH_CONF_DIR, joinedParent, "conf");
        fixPathForParent(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, joinedParent, "kickstarts");
        fixPathForParent(UaeOptionKeys.UAE_PATH_ROMS_DIR, joinedParent, "roms");
        fixPathForParent(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, joinedParent, "disks");
        fixPathForParent(UaeOptionKeys.UAE_PATH_CDROMS_DIR, joinedParent, "cdroms");
        fixPathForParent(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, joinedParent, "harddrives");
        fixPathForParent(UaeOptionKeys.UAE_PATH_LHA_DIR, joinedParent, "lha");
        fixPathForParent(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, joinedParent, "whdboot");
        fixPathForParent(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, joinedParent, "savestates");
        fixPathForParent(UaeOptionKeys.UAE_PATH_SCREENS_DIR, joinedParent, "screenshots");
    }

    private void fixPathForParent(String key, String joinedParent, String rel) {
        if (key == null || joinedParent == null) return;
        String current = pending.get(key);
        if (current == null
            || current.trim().isEmpty()
            || looksMalformedSafChildTree(current)
            || looksLikeWrongMappedSubfolder(current, joinedParent, rel)) {
            String normalized = joinedParent;
            if (!normalized.endsWith("/")) normalized = normalized + "/";
            normalized = normalized + rel + "/";
            pending.put(key, normalized);
        }
    }

    private static boolean looksMalformedSafChildTree(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return false;
        return v.contains("%2ffloppies") || v.contains("%2fdisks") || v.contains("/floppies") || v.contains("/disks");
    }

    private static String normalizeRel(String rel) {
        if (rel == null) return "";
        String r = rel.trim();
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isKnownDefaultSubfolder(String rel) {
        if (rel == null || rel.isEmpty()) return false;
        return "conf".equals(rel)
            || "kickstarts".equals(rel)
            || "roms".equals(rel)
            || "disks".equals(rel)
            || "floppies".equals(rel)
            || "cdroms".equals(rel)
            || "harddrives".equals(rel)
            || "lha".equals(rel)
            || "whdboot".equals(rel)
            || "savestates".equals(rel)
            || "screenshots".equals(rel)
            || "screens".equals(rel);
    }

    private static boolean looksLikeWrongMappedSubfolder(String currentPath, String joinedParent, String expectedRel) {
        if (currentPath == null || joinedParent == null || expectedRel == null) return false;
        if (!ConfigStorage.isSafJoinedPath(currentPath) || !ConfigStorage.isSafJoinedPath(joinedParent)) return false;

        try {
            ConfigStorage.SafPath current = ConfigStorage.splitSafJoinedPath(currentPath);
            ConfigStorage.SafPath parent = ConfigStorage.splitSafJoinedPath(joinedParent);
            if (current == null || parent == null) return false;
            if (current.treeUri == null || parent.treeUri == null) return false;
            if (!current.treeUri.trim().equals(parent.treeUri.trim())) return false;

            String currentRel = normalizeRel(current.relPath);
            String expected = normalizeRel(expectedRel);
            if (currentRel.isEmpty() || expected.isEmpty()) return false;
            if (currentRel.equals(expected)) return false;

            return isKnownDefaultSubfolder(currentRel);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK_PARENT) {
            Uri uri = data.getData();
            if (uri == null) return;

            // Persist SAF access (best-effort).
            try {
                int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try {
                    getContentResolver().takePersistableUriPermission(uri, rw);
                } catch (SecurityException ignored) {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    flags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, flags);
                }
            } catch (Throwable ignored) {
            }

            pending.put(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, uri.toString());
            // Keep parent dir in joined form too (so older prompts have something stable).
            String joinedParent = toSafJoinedRoot(uri);
            if (joinedParent != null) {
                pending.put(UaeOptionKeys.UAE_PATH_PARENT_DIR, joinedParent);
                repairMalformedPathsForSelectedParent(joinedParent);
            }
            saveAll();
            refreshAll();
            return;
        }

        Slot slot = reqToSlot.get(requestCode);
        if (slot == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        // Persist SAF access (best-effort).
        try {
            int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(uri, rw);
            } catch (SecurityException ignored) {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                flags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            }
        } catch (Throwable ignored) {
        }

        String joined = toSafJoinedRoot(uri);
        if (joined == null) {
            Toast.makeText(this, "Invalid folder selection", Toast.LENGTH_SHORT).show();
            return;
        }

        // For compatibility with existing launcher prompts, keep a "last selected tree".
        pending.put(slot.prefKey, joined);
        saveAll();

        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            saveAll();
        } catch (Throwable ignored) {
        }
    }
}

