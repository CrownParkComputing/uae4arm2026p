package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdfLibraryActivity extends Activity {
    public static final String EXTRA_TARGET_DF = "extra_target_df";
    public static final String EXTRA_SELECTED_PATH = "extra_selected_path";
    public static final String EXTRA_SELECTED_TITLE = "extra_selected_title";
    public static final String EXTRA_ADDITIONAL_PATHS = "extra_additional_paths";

    private static final String PREFS_NAME = "bootstrap";
    private static final String PREF_IGDB_CLIENT_ID = "igdb_client_id";
    private static final String PREF_IGDB_CLIENT_SECRET = "igdb_client_secret";
    private static final String PREF_IGDB_ACCESS_TOKEN = "igdb_access_token";
    private static final String PREF_IGDB_TITLE_CACHE_PREFIX = "igdb_title_cache_";
    private static final String PREF_IGDB_SUMMARY_CACHE_PREFIX = "igdb_summary_cache_";
    private static final String PREF_IGDB_COVER_URL_CACHE_PREFIX = "igdb_cover_url_cache_";
    private static final String PREF_IGDB_QUERY_OVERRIDE_PREFIX = "igdb_query_override_";
    private static final String PREF_LIBRARY_HIDDEN_PREFIX = "library_hidden_";
    private static final String PREF_LIBRARY_GROUP_OVERRIDE_PREFIX = "library_group_override_";
    private static final String PREF_LIBRARY_VIEW_MODE = "library_view_mode";
    private static final int VIEW_MODE_LIST = 0;
    private static final int VIEW_MODE_COVERS = 1;
    private static final int VIEW_MODE_CAROUSEL = 2;
    private static final String TOSEC_BASE = "https://tosec.ikod.se";
    private static final int TOSEC_MODE_ALL = 0;
    private static final int TOSEC_MODE_AGA = 1;
    private static final int TOSEC_MODE_NON_AGA = 2;

    private final ArrayList<LibraryEntry> allEntries = new ArrayList<>();
    private final ArrayList<LibraryEntry> filteredEntries = new ArrayList<>();
    private final Map<String, ArrayList<LibraryEntry>> visibleGroups = new HashMap<>();
    private final Map<String, Bitmap> coverCache = new HashMap<>();
    private final Set<String> coverLoading = new HashSet<>();

    private TextView diskCountView;
    private ProgressBar progress;
    private View refreshIgdbBtn;
    private View logsBtn;
    private View searchNewBtn;
    private ImageButton viewModeBtn;
    private ListView listView;
    private HorizontalScrollView carouselScroll;
    private LinearLayout carouselStrip;
    private LibraryAdapter adapter;
    private int targetDf;
    private int libraryViewMode = VIEW_MODE_LIST;
    private volatile String lastSyncNote;

    private static final class LibraryEntry {
        final File file;
        final String fileName;
        String title;
        String summary;
        String coverUrl;
        String contentUri; // non-null when discovered via SAF (content:// URI)

        LibraryEntry(File file, String fileName, String title) {
            this.file = file;
            this.fileName = fileName;
            this.title = title;
            this.summary = "";
            this.coverUrl = null;
        }

        /** Return the path/URI to pass to the emulator. Prefers SAF content URI when available. */
        String getEmulatorPath() {
            if (contentUri != null && !contentUri.trim().isEmpty()) return contentUri;
            return file != null ? file.getAbsolutePath() : null;
        }
    }

    private static final class TosecResult {
        final String token;
        final String title;
        final int diskNo;
        final int diskTotal;
        String suggestedFileName;

        TosecResult(String token, String title, int diskNo, int diskTotal) {
            this.token = token;
            this.title = title;
            this.diskNo = diskNo;
            this.diskTotal = diskTotal;
            this.suggestedFileName = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetDf = Math.max(0, Math.min(3, getIntent().getIntExtra(EXTRA_TARGET_DF, 0)));

        setTitle("ADF Library (DF" + targetDf + ")");
        libraryViewMode = getSavedLibraryViewMode();
        buildUi();
        loadLibraryAsync();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, dp(8));

        searchNewBtn = createTopIconButton(android.R.drawable.ic_menu_search, "Search New ADF", v -> promptAndSearchNewAdf());
        topRow.addView(searchNewBtn);

        viewModeBtn = createTopIconButton(android.R.drawable.ic_menu_sort_by_size, "Toggle Library View", v -> cycleLibraryViewMode());
        topRow.addView(viewModeBtn);

        refreshIgdbBtn = createTopIconButton(android.R.drawable.ic_popup_sync, "Refresh IGDB Data", v -> enrichFromIgdbAsync(true));
        topRow.addView(refreshIgdbBtn);

        logsBtn = createTopIconButton(android.R.drawable.ic_menu_info_details, "Logs", v -> startActivity(new Intent(this, LogsActivity.class)));
        topRow.addView(logsBtn);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(24), dp(24));
        p.leftMargin = dp(10);
        topRow.addView(progress, p);

        TextView title = new TextView(this);
        title.setText("ADF Library");
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, dp(6));

        diskCountView = new TextView(this);
        diskCountView.setTextSize(12f);
        diskCountView.setPadding(0, dp(4), 0, dp(2));
        diskCountView.setText("Showing 0 of 0 disks");

        listView = new ListView(this);
        adapter = new LibraryAdapter();
        listView.setAdapter(adapter);

        carouselStrip = new LinearLayout(this);
        carouselStrip.setOrientation(LinearLayout.HORIZONTAL);
        carouselStrip.setGravity(Gravity.CENTER_VERTICAL);
        carouselStrip.setPadding(dp(4), dp(4), dp(4), dp(4));

        carouselScroll = new HorizontalScrollView(this);
        carouselScroll.setHorizontalScrollBarEnabled(true);
        carouselScroll.setFillViewport(true);
        carouselScroll.addView(carouselStrip, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout contentHost = new FrameLayout(this);
        contentHost.addView(listView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        contentHost.addView(carouselScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        applyLibraryViewModeUi();

        root.addView(topRow);
        root.addView(title);
        root.addView(diskCountView);
        root.addView(contentHost, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        setContentView(root);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filteredEntries.size()) return;
            onEntrySelected(filteredEntries.get(position));
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filteredEntries.size()) return true;
            promptEntryActions(filteredEntries.get(position));
            return true;
        });
    }

    private void onEntrySelected(LibraryEntry selected) {
        if (selected == null || selected.file == null) return;
        ArrayList<LibraryEntry> group = visibleGroups.get(selected.file.getAbsolutePath());
        if (group == null || group.isEmpty()) {
            group = new ArrayList<>();
            group.add(selected);
        }
        group.sort((a, b) -> Integer.compare(extractDiskNo(a), extractDiskNo(b)));

        LibraryEntry firstDisk = group.get(0);
        for (LibraryEntry entry : group) {
            int diskNo = extractDiskNo(entry);
            if (diskNo == 1) {
                firstDisk = entry;
                break;
            }
        }

        ArrayList<String> additional = new ArrayList<>();
        for (LibraryEntry entry : group) {
            if (entry == null || entry.file == null) continue;
            if (entry.file.equals(firstDisk.file)) continue;
            additional.add(entry.getEmulatorPath());
        }

        Intent out = new Intent();
        out.putExtra(EXTRA_TARGET_DF, targetDf);
        out.putExtra(EXTRA_SELECTED_PATH, firstDisk.getEmulatorPath());
        out.putExtra(EXTRA_SELECTED_TITLE, firstDisk.title);
        out.putExtra(EXTRA_ADDITIONAL_PATHS, additional.toArray(new String[0]));
        if (firstDisk.contentUri != null) {
            out.setData(Uri.parse(firstDisk.contentUri));
        } else {
            out.setData(Uri.fromFile(firstDisk.file));
        }
        setResult(RESULT_OK, out);
        finish();
    }

    private ImageButton createTopIconButton(int iconRes, String contentDescription, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setBackground(null);
        button.setContentDescription(contentDescription);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private int getSavedLibraryViewMode() {
        try {
            int mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_LIBRARY_VIEW_MODE, VIEW_MODE_LIST);
            if (mode < VIEW_MODE_LIST || mode > VIEW_MODE_CAROUSEL) return VIEW_MODE_LIST;
            return mode;
        } catch (Throwable ignored) {
            return VIEW_MODE_LIST;
        }
    }

    private void setSavedLibraryViewMode(int mode) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_LIBRARY_VIEW_MODE, mode).apply();
        } catch (Throwable ignored) {
        }
    }

    private void cycleLibraryViewMode() {
        libraryViewMode++;
        if (libraryViewMode > VIEW_MODE_CAROUSEL) {
            libraryViewMode = VIEW_MODE_LIST;
        }
        setSavedLibraryViewMode(libraryViewMode);
        applyLibraryViewModeUi();
        if (adapter != null) adapter.notifyDataSetChanged();

        String label = libraryViewMode == VIEW_MODE_LIST
            ? "List view"
            : (libraryViewMode == VIEW_MODE_COVERS ? "Covers view" : "Carousel view");
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    private void applyLibraryViewModeUi() {
        if (viewModeBtn != null) {
            if (libraryViewMode == VIEW_MODE_LIST) {
                viewModeBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size);
                viewModeBtn.setContentDescription("View mode: List");
            } else if (libraryViewMode == VIEW_MODE_COVERS) {
                viewModeBtn.setImageResource(android.R.drawable.ic_menu_gallery);
                viewModeBtn.setContentDescription("View mode: Covers");
            } else {
                viewModeBtn.setImageResource(android.R.drawable.ic_menu_slideshow);
                viewModeBtn.setContentDescription("View mode: Carousel");
            }
        }
        if (listView != null) {
            listView.setDividerHeight(libraryViewMode == VIEW_MODE_COVERS ? dp(2) : dp(6));
            listView.setVisibility(libraryViewMode == VIEW_MODE_CAROUSEL ? View.GONE : View.VISIBLE);
        }
        if (carouselScroll != null) {
            carouselScroll.setVisibility(libraryViewMode == VIEW_MODE_CAROUSEL ? View.VISIBLE : View.GONE);
            if (libraryViewMode == VIEW_MODE_CAROUSEL) {
                rebuildCarouselStrip();
            }
        }
    }

    private void rebuildCarouselStrip() {
        if (carouselStrip == null) return;
        carouselStrip.removeAllViews();
        if (filteredEntries.isEmpty()) return;

        for (LibraryEntry e : filteredEntries) {
            if (e == null) continue;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT);
            cardLp.rightMargin = dp(8);

            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(180), dp(240));
            card.addView(cover, coverLp);

            TextView title = new TextView(this);
            title.setTextSize(15f);
            title.setMaxLines(2);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = dp(6);
            card.addView(title, titleLp);

            String t = (e.title == null || e.title.trim().isEmpty()) ? e.fileName : e.title;
            ArrayList<LibraryEntry> group = visibleGroups.get(e.file.getAbsolutePath());
            int groupCount = group == null ? 1 : group.size();
            boolean aga = isAgaGroup(group) || isAgaEntry(e);
            if (groupCount > 1) {
                t = stripDiskMarker(t) + " (" + groupCount + " disks)";
            }
            if (aga && !containsAgaMarker(t)) {
                t = t + " [AGA]";
            }
            title.setText(t);

            bindCover(cover, e.coverUrl);

            card.setOnClickListener(v -> onEntrySelected(e));
            card.setOnLongClickListener(v -> {
                promptEntryActions(e);
                return true;
            });

            carouselStrip.addView(card, cardLp);
        }
    }

    private void promptEntryActions(LibraryEntry selected) {
        if (selected == null) return;
        ArrayList<LibraryEntry> group = visibleGroups.get(selected.file.getAbsolutePath());
        if (group == null || group.isEmpty()) {
            group = new ArrayList<>();
            group.add(selected);
        }

        ArrayList<String> options = new ArrayList<>();
        options.add("Correct IGDB Match");
        options.add("Delete this file");
        options.add("Add disk to this group");
        boolean hasGroup = group.size() > 1;
        if (hasGroup) {
            options.add("Delete one disk from group");
            options.add("Delete whole disk group (" + group.size() + ")");
        }

        String[] labels = options.toArray(new String[0]);
        ArrayList<LibraryEntry> finalGroup = group;
        new AlertDialog.Builder(this)
            .setTitle(selected.title == null || selected.title.trim().isEmpty() ? selected.fileName : selected.title)
            .setItems(labels, (d, which) -> {
                if (which == 0) {
                    promptIgdbCorrection(selected);
                    return;
                }
                if (which == 1) {
                    confirmDeleteSingleFile(selected);
                    return;
                }
                if (which == 2) {
                    promptAddDiskToGroup(selected, finalGroup);
                    return;
                }
                if (!hasGroup) return;
                if (which == 3) {
                    promptDeleteOneFromGroup(finalGroup);
                } else if (which == 4) {
                    confirmDeleteGroup(finalGroup);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void promptAddDiskToGroup(LibraryEntry selected, ArrayList<LibraryEntry> currentGroup) {
        if (selected == null) return;
        String targetGroupKey = libraryGroupKey(selected);
        if (targetGroupKey == null || targetGroupKey.trim().isEmpty()) return;

        HashSet<String> inGroupPaths = new HashSet<>();
        if (currentGroup != null) {
            for (LibraryEntry e : currentGroup) {
                if (e == null || e.file == null) continue;
                inGroupPaths.add(e.file.getAbsolutePath());
            }
        }

        ArrayList<LibraryEntry> candidates = new ArrayList<>();
        for (LibraryEntry e : allEntries) {
            if (e == null || e.file == null) continue;
            if (inGroupPaths.contains(e.file.getAbsolutePath())) continue;
            candidates.add(e);
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No candidate disks available", Toast.LENGTH_SHORT).show();
            return;
        }

        candidates.sort((a, b) -> {
            String af = a.fileName == null ? "" : a.fileName;
            String bf = b.fileName == null ? "" : b.fileName;
            return af.compareToIgnoreCase(bf);
        });

        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            LibraryEntry e = candidates.get(i);
            labels[i] = (e.fileName == null || e.fileName.trim().isEmpty()) ? e.file.getName() : e.fileName;
        }

        new AlertDialog.Builder(this)
            .setTitle("Add disk to group")
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= candidates.size()) return;
                LibraryEntry picked = candidates.get(which);
                if (picked == null) return;
                putGroupOverride(picked.fileName, targetGroupKey);
                AppLog.append(this, "Library group override file=" + picked.fileName + " -> groupKey=\"" + targetGroupKey + "\"");
                applyFilter();
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "Disk added to group", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDeleteSingleFile(LibraryEntry entry) {
        if (entry == null || entry.file == null) return;
        String label = entry.fileName == null ? entry.file.getName() : entry.fileName;
        new AlertDialog.Builder(this)
            .setTitle("Delete file")
            .setMessage("Delete this file?\n\n" + label)
            .setPositiveButton("Delete", (d, w) -> deleteLibraryEntriesAsync(singleton(entry), false))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void promptDeleteOneFromGroup(ArrayList<LibraryEntry> group) {
        if (group == null || group.isEmpty()) return;
        ArrayList<LibraryEntry> items = new ArrayList<>();
        for (LibraryEntry e : group) {
            if (e != null && e.file != null) items.add(e);
        }
        if (items.isEmpty()) return;
        items.sort((a, b) -> Integer.compare(extractDiskNo(a), extractDiskNo(b)));

        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            LibraryEntry e = items.get(i);
            String diskLabel = "Disk " + (i + 1);
            int diskNo = extractDiskNo(e);
            if (diskNo != Integer.MAX_VALUE) diskLabel = "Disk " + diskNo;
            String name = e.fileName == null ? e.file.getName() : e.fileName;
            labels[i] = diskLabel + "  —  " + name;
        }

        new AlertDialog.Builder(this)
            .setTitle("Delete one disk")
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= items.size()) return;
                confirmDeleteSingleFile(items.get(which));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDeleteGroup(ArrayList<LibraryEntry> group) {
        if (group == null || group.isEmpty()) return;
        int count = 0;
        for (LibraryEntry e : group) {
            if (e != null && e.file != null) count++;
        }
        if (count <= 0) return;

        ArrayList<LibraryEntry> toDelete = new ArrayList<>();
        for (LibraryEntry e : group) {
            if (e != null && e.file != null) toDelete.add(e);
        }

        new AlertDialog.Builder(this)
            .setTitle("Delete disk group")
            .setMessage("Delete all " + count + " files in this grouped game?")
            .setPositiveButton("Delete All", (d, w) -> deleteLibraryEntriesAsync(toDelete, true))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private ArrayList<LibraryEntry> singleton(LibraryEntry entry) {
        ArrayList<LibraryEntry> out = new ArrayList<>();
        if (entry != null) out.add(entry);
        return out;
    }

    private void deleteLibraryEntriesAsync(ArrayList<LibraryEntry> entries, boolean grouped) {
        if (entries == null || entries.isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        refreshIgdbBtn.setEnabled(false);

        new Thread(() -> {
            int deleted = 0;
            for (LibraryEntry e : entries) {
                if (e == null || e.file == null) continue;
                try {
                    String fileName = e.fileName == null ? e.file.getName() : e.fileName;
                    boolean ok = e.file.exists() && e.file.isFile() && e.file.delete();
                    markLibraryHidden(fileName, true);
                    if (ok || isLibraryHidden(fileName)) {
                        clearCachedMetadataForFile(fileName);
                        deleted++;
                    }
                } catch (Throwable ignored) {
                }
            }

            final int deletedFinal = deleted;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                refreshIgdbBtn.setEnabled(true);
                if (deletedFinal > 0) {
                    String msg = grouped
                        ? ("Deleted " + deletedFinal + " files from group")
                        : ("Deleted " + deletedFinal + " file");
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    AppLog.append(this, "Library delete success grouped=" + grouped + " deleted=" + deletedFinal);
                } else {
                    Toast.makeText(this, "No files deleted", Toast.LENGTH_SHORT).show();
                    AppLog.append(this, "Library delete skipped grouped=" + grouped + " deleted=0");
                }
                loadLibraryAsync();
            });
        }).start();
    }

    private void clearCachedMetadataForFile(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return;
            String keyBase = fileName.trim().toLowerCase(Locale.ROOT);
            SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            e.remove(PREF_IGDB_TITLE_CACHE_PREFIX + keyBase);
            e.remove(PREF_IGDB_SUMMARY_CACHE_PREFIX + keyBase);
            e.remove(PREF_IGDB_COVER_URL_CACHE_PREFIX + keyBase);
            e.remove(PREF_IGDB_QUERY_OVERRIDE_PREFIX + keyBase);
            e.remove(PREF_LIBRARY_GROUP_OVERRIDE_PREFIX + keyBase);
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadLibraryAsync() {
        AppLog.append(this, "ADF library page opened (DF" + targetDf + ")");
        progress.setVisibility(View.VISIBLE);
        refreshIgdbBtn.setEnabled(false);
        new Thread(() -> {
            ArrayList<LibraryEntry> loaded = new ArrayList<>();
            try {
                File disks = resolveLibraryDiskRoot();
                if (disks != null) {
                    collectDiskEntriesRecursive(disks, loaded);
                }
            } catch (Throwable ignored) {
            }

            // If raw File scan found nothing, try scanning via SAF DocumentFile.
            if (loaded.isEmpty()) {
                try {
                    collectDiskEntriesViaSaf(loaded);
                } catch (Throwable t) {
                    AppLog.append(AdfLibraryActivity.this, "SAF library scan failed: " + t.getMessage());
                }
            }

            runOnUiThread(() -> {
                allEntries.clear();
                allEntries.addAll(loaded);
                applyFilter();
                AppLog.append(this, "Loaded local ADF library entries=" + loaded.size());
                if (lastSyncNote != null && !lastSyncNote.trim().isEmpty()) {
                    Toast.makeText(this, lastSyncNote, Toast.LENGTH_SHORT).show();
                }
                progress.setVisibility(View.GONE);
                refreshIgdbBtn.setEnabled(true);
                if (allEntries.isEmpty()) {
                    Toast.makeText(this, "No local ADF library found yet.", Toast.LENGTH_SHORT).show();
                } else {
                    enrichFromIgdbAsync(false);
                }
            });
        }).start();
    }

    private File resolveLibraryDiskRoot() {
        try {
            SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String floppiesUri = prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
            String parentTreeUri = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);

            File fsRoot = resolvePrimaryFilesystemFloppiesDir(floppiesUri, parentTreeUri);
            if (fsRoot != null && fsRoot.exists() && fsRoot.isDirectory()
                    && isDirectoryTrulyAccessible(fsRoot)) {
                lastSyncNote = "Library root: " + fsRoot.getAbsolutePath();
                return fsRoot;
            } else if (fsRoot != null) {
                AppLog.append(this, "Filesystem floppies dir not accessible (scoped storage): " + fsRoot.getAbsolutePath());
            }

            File base = AppPaths.getBaseDir(this);
            File internalDisks = new File(base, "disks");
            ensureDir(internalDisks);
            syncConfiguredFloppiesToInternal(internalDisks);
            return internalDisks;
        } catch (Throwable t) {
            try {
                File base = AppPaths.getBaseDir(this);
                File internalDisks = new File(base, "disks");
                ensureDir(internalDisks);
                return internalDisks;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * Verify a directory is truly accessible via raw File APIs — not blocked
     * by Android scoped storage.  On Android 11+ the directory may physically
     * exist but listFiles() returns null and writes throw EPERM.
     */
    private boolean isDirectoryTrulyAccessible(File dir) {
        if (dir == null) return false;
        try {
            // listFiles() returns null when the app lacks read access
            if (dir.listFiles() == null) return false;
            // canWrite() is false when scoped storage blocks write access
            return dir.canWrite();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void collectDiskEntriesRecursive(File dir, ArrayList<LibraryEntry> out) {
        if (dir == null || out == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collectDiskEntriesRecursive(f, out);
                continue;
            }
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name == null || name.trim().isEmpty()) continue;
            if (!isSupportedLibraryDiskFile(name)) continue;
            if (isLibraryHidden(name)) continue;

            String cached = getCachedIgdbTitle(name);
            String title = (cached == null || cached.trim().isEmpty()) ? derivePrettyTitle(name) : cached.trim();
            LibraryEntry entry = new LibraryEntry(f, name, title);
            String cachedSummary = getCachedIgdbSummary(name);
            String cachedCover = getCachedIgdbCoverUrl(name);
            if (cachedSummary != null) entry.summary = cachedSummary;
            if (cachedCover != null) entry.coverUrl = cachedCover;
            out.add(entry);
        }
    }

    /**
     * Scan the user's configured floppies folder(s) via SAF DocumentFile.
     * Used as a fallback when raw File-based scanning fails due to scoped storage.
     * Creates LibraryEntry objects with contentUri set so the emulator receives
     * a content:// URI it can open via SafFileBridge.
     */
    private void collectDiskEntriesViaSaf(ArrayList<LibraryEntry> out) {
        SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
        String floppiesUri = prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
        String parentTreeUri = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);

        ArrayList<DocumentFile> roots = resolveFloppyRoots(floppiesUri, parentTreeUri);
        int before = out.size();
        for (DocumentFile root : roots) {
            if (root == null || !root.exists() || !root.isDirectory()) continue;
            collectDiskEntriesFromDocumentFile(root, out);
        }
        int found = out.size() - before;
        AppLog.append(this, "SAF library scan found " + found + " entries from " + roots.size() + " root(s)");
        if (found > 0) {
            lastSyncNote = "Library: " + found + " disks (via SAF)";
        }
    }

    private void collectDiskEntriesFromDocumentFile(DocumentFile dir, ArrayList<LibraryEntry> out) {
        if (dir == null || out == null || !dir.exists() || !dir.isDirectory()) return;
        DocumentFile[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (children == null) return;
        for (DocumentFile child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectDiskEntriesFromDocumentFile(child, out);
                continue;
            }
            String name = child.getName();
            if (name == null || name.trim().isEmpty()) continue;
            if (!isSupportedLibraryDiskFile(name)) continue;
            if (isLibraryHidden(name)) continue;

            Uri docUri = child.getUri();
            if (docUri == null) continue;

            // Create a synthetic File for display/grouping; the real path for the
            // emulator is the content URI stored in contentUri.
            File syntheticFile = new File("/saf-library/" + name);
            String cached = getCachedIgdbTitle(name);
            String title = (cached == null || cached.trim().isEmpty()) ? derivePrettyTitle(name) : cached.trim();
            LibraryEntry entry = new LibraryEntry(syntheticFile, name, title);
            entry.contentUri = docUri.toString();
            String cachedSummary = getCachedIgdbSummary(name);
            String cachedCover = getCachedIgdbCoverUrl(name);
            if (cachedSummary != null) entry.summary = cachedSummary;
            if (cachedCover != null) entry.coverUrl = cachedCover;
            out.add(entry);
        }
    }

    private void syncConfiguredFloppiesToInternal(File internalDisksDir) {
        if (internalDisksDir == null) return;
        lastSyncNote = null;
        try {
            SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String floppiesUri = prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
            String parentTreeUri = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);

            SyncStats stats = new SyncStats();
            ArrayList<DocumentFile> roots = resolveFloppyRoots(floppiesUri, parentTreeUri);
            for (DocumentFile root : roots) {
                if (root == null || !root.exists() || !root.isDirectory()) continue;
                syncDocumentFolderRecursive(root, internalDisksDir, stats);
            }

            if (stats.scanned == 0) {
                File fsFloppiesDir = resolvePrimaryFilesystemFloppiesDir(floppiesUri, parentTreeUri);
                if (fsFloppiesDir != null && fsFloppiesDir.exists() && fsFloppiesDir.isDirectory()) {
                    syncFilesystemFolderRecursive(fsFloppiesDir, internalDisksDir, stats);
                }
            }

            if (stats.scanned == 0) {
                AppLog.append(this, "External floppies sync skipped: unresolved/empty path floppiesUri=" + safeForLog(floppiesUri) + " parentTree=" + safeForLog(parentTreeUri));
            }
            AppLog.append(this, "External floppies sync scanned=" + stats.scanned + " imported=" + stats.imported);
            lastSyncNote = "Floppies sync: " + stats.imported + " imported / " + stats.scanned + " scanned";
        } catch (Throwable t) {
            AppLog.append(this, "External floppies sync error: " + t.getMessage());
            lastSyncNote = "Floppies sync failed";
        }
    }

    private ArrayList<DocumentFile> resolveFloppyRoots(String floppiesUri, String parentTreeUri) {
        ArrayList<DocumentFile> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        addRoot(out, seen, resolveFloppiesRoot(floppiesUri, parentTreeUri));

        try {
            if (parentTreeUri != null && !parentTreeUri.trim().isEmpty()) {
                DocumentFile parentTree = DocumentFile.fromTreeUri(this, Uri.parse(parentTreeUri.trim()));
                if (parentTree != null && parentTree.exists() && parentTree.isDirectory()) {
                    for (DocumentFile child : parentTree.listFiles()) {
                        if (child == null || !child.exists() || !child.isDirectory()) continue;
                        String name = child.getName();
                        if (name == null) continue;
                        String n = name.trim().toLowerCase(Locale.ROOT);
                        if ("floppies".equals(n) || "disks".equals(n)) {
                            addRoot(out, seen, child);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    private void addRoot(ArrayList<DocumentFile> out, Set<String> seen, DocumentFile root) {
        if (out == null || seen == null || root == null) return;
        try {
            Uri uri = root.getUri();
            String key = uri == null ? null : uri.toString();
            if (key != null && !key.trim().isEmpty()) {
                if (seen.contains(key)) return;
                seen.add(key);
            }
            out.add(root);
        } catch (Throwable ignored) {
        }
    }

    private static final class SyncStats {
        int scanned;
        int imported;
    }

    private DocumentFile resolveFloppiesRoot(String floppiesUri, String parentTreeUri) {
        DocumentFile floppiesRoot = null;
        try {
            if (floppiesUri != null && !floppiesUri.trim().isEmpty()) {
                String trimmed = floppiesUri.trim();
                if (ConfigStorage.isSafJoinedPath(trimmed)) {
                    ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(trimmed);
                    DocumentFile resolved = resolveSafJoinedDirectory(sp);
                    if (resolved != null && resolved.exists() && resolved.isDirectory()) {
                        return resolved;
                    }
                    DocumentFile fallback = resolveSafJoinedFloppiesFallback(sp);
                    if (fallback != null && fallback.exists() && fallback.isDirectory()) {
                        return fallback;
                    }
                } else {
                    Uri uri = Uri.parse(trimmed);
                    floppiesRoot = DocumentFile.fromTreeUri(this, uri);
                    if (floppiesRoot != null && floppiesRoot.exists()) {
                        if (floppiesRoot.isDirectory()) return floppiesRoot;
                        DocumentFile parent = floppiesRoot.getParentFile();
                        if (parent != null && parent.exists() && parent.isDirectory()) return parent;
                    }

                    if (DocumentsContract.isDocumentUri(this, uri)) {
                        DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
                        if (doc != null && doc.exists()) {
                            if (doc.isDirectory()) return doc;
                            DocumentFile parent = doc.getParentFile();
                            if (parent != null && parent.exists() && parent.isDirectory()) return parent;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (parentTreeUri == null || parentTreeUri.trim().isEmpty()) return null;
            DocumentFile parentTree = DocumentFile.fromTreeUri(this, Uri.parse(parentTreeUri.trim()));
            if (parentTree == null || !parentTree.exists() || !parentTree.isDirectory()) return null;

            for (DocumentFile child : parentTree.listFiles()) {
                if (child == null || !child.isDirectory()) continue;
                String name = child.getName();
                if (name != null && "floppies".equalsIgnoreCase(name.trim())) {
                    return child;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private DocumentFile resolveSafJoinedFloppiesFallback(ConfigStorage.SafPath sp) {
        if (sp == null || sp.treeUri == null || sp.treeUri.trim().isEmpty()) return null;
        String rel = sp.relPath == null ? "" : sp.relPath.trim();
        if (rel.isEmpty()) return null;

        String altRel = null;
        String relNorm = rel.toLowerCase(Locale.ROOT);
        if (relNorm.contains("/floppies")) {
            altRel = rel.replaceAll("(?i)floppies", "disks");
        } else if (relNorm.contains("/disks")) {
            altRel = rel.replaceAll("(?i)disks", "floppies");
        }
        if (altRel == null || altRel.trim().isEmpty() || altRel.equals(rel)) return null;

        ConfigStorage.SafPath alt = new ConfigStorage.SafPath(sp.treeUri, altRel);
        return resolveSafJoinedDirectory(alt);
    }

    private DocumentFile resolveSafJoinedDirectory(ConfigStorage.SafPath sp) {
        if (sp == null || sp.treeUri == null || sp.treeUri.trim().isEmpty()) return null;
        DocumentFile cur;
        try {
            cur = DocumentFile.fromTreeUri(this, Uri.parse(sp.treeUri.trim()));
        } catch (Throwable t) {
            return null;
        }
        if (cur == null || !cur.exists()) return null;

        String rel = sp.relPath == null ? "" : sp.relPath.trim();
        if (rel.isEmpty() || "/".equals(rel)) return cur;
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        if (rel.isEmpty()) return cur;

        String[] parts = rel.split("/");
        for (String part : parts) {
            if (part == null) continue;
            String seg = part.trim();
            if (seg.isEmpty()) continue;
            DocumentFile next = cur.findFile(seg);
            if (next == null || !next.exists()) return null;
            cur = next;
        }
        return cur;
    }

    private String safeForLog(String value) {
        if (value == null) return "";
        String out = value.trim();
        if (out.length() <= 160) return out;
        return out.substring(0, 160) + "...";
    }

    private void syncDocumentFolderRecursive(DocumentFile sourceDir, File internalDisksDir, SyncStats stats) {
        if (sourceDir == null || internalDisksDir == null || stats == null) return;
        DocumentFile[] children;
        try {
            children = sourceDir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (children == null) return;

        for (DocumentFile child : children) {
            if (child == null) continue;
            try {
                if (child.isDirectory()) {
                    syncDocumentFolderRecursive(child, internalDisksDir, stats);
                    continue;
                }
                if (!child.isFile()) continue;
                String name = child.getName();
                if (name == null || name.trim().isEmpty()) continue;
                if (!isSupportedLibraryDiskFile(name)) continue;
                if (isLibraryHidden(name)) continue;

                stats.scanned++;
                File target = new File(internalDisksDir, name);
                long sourceLen = child.length();
                if (target.exists() && target.isFile()) {
                    if (sourceLen > 0 && target.length() == sourceLen) {
                        continue;
                    }
                    target = uniqueFile(target);
                }

                if (copyDocumentToFile(child.getUri(), target)) {
                    stats.imported++;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean copyDocumentToFile(Uri sourceUri, File target) {
        if (sourceUri == null || target == null) return false;
        try {
            InputStream in = getContentResolver().openInputStream(sourceUri);
            if (in == null) return false;
            ensureDir(target.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                fos.flush();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
            return target.exists() && target.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void syncFilesystemFolderRecursive(File sourceDir, File internalDisksDir, SyncStats stats) {
        if (sourceDir == null || internalDisksDir == null || stats == null) return;
        File[] children = sourceDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            try {
                if (child.isDirectory()) {
                    syncFilesystemFolderRecursive(child, internalDisksDir, stats);
                    continue;
                }
                if (!child.isFile()) continue;
                String name = child.getName();
                if (name == null || name.trim().isEmpty()) continue;
                if (!isSupportedLibraryDiskFile(name)) continue;
                if (isLibraryHidden(name)) continue;

                stats.scanned++;
                File target = new File(internalDisksDir, name);
                if (target.exists() && target.isFile() && target.length() == child.length()) {
                    continue;
                }
                if (target.exists()) {
                    target = uniqueFile(target);
                }
                if (copyFileToFile(child, target)) {
                    stats.imported++;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean copyFileToFile(File source, File target) {
        if (source == null || target == null || !source.exists() || !source.isFile()) return false;
        try {
            ensureDir(target.getParentFile());
            try (FileInputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            }
            return target.exists() && target.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private File resolvePrimaryFilesystemFloppiesDir(String floppiesUri, String parentTreeUri) {
        try {
            if (floppiesUri != null && !floppiesUri.trim().isEmpty()) {
                String trimmed = floppiesUri.trim();
                if (ConfigStorage.isSafJoinedPath(trimmed)) {
                    ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(trimmed);
                    File resolved = resolvePrimaryTreeFilesystemDir(sp == null ? null : sp.treeUri, sp == null ? null : sp.relPath);
                    if (resolved != null) return resolved;

                    File alt = resolvePrimaryTreeFilesystemDir(sp == null ? null : sp.treeUri, replaceFloppiesWithDisks(sp == null ? null : sp.relPath));
                    if (alt != null) return alt;
                }
            }
            if (parentTreeUri != null && !parentTreeUri.trim().isEmpty()) {
                File fromParentDisks = resolvePrimaryTreeFilesystemDir(parentTreeUri.trim(), "/disks");
                if (fromParentDisks != null) return fromParentDisks;
                File fromParentFloppies = resolvePrimaryTreeFilesystemDir(parentTreeUri.trim(), "/floppies");
                if (fromParentFloppies != null) return fromParentFloppies;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String replaceFloppiesWithDisks(String relPath) {
        if (relPath == null) return null;
        String out = relPath;
        if (out.toLowerCase(Locale.ROOT).contains("floppies")) {
            out = out.replaceAll("(?i)floppies", "disks");
        }
        return out;
    }

    private File resolvePrimaryTreeFilesystemDir(String treeUriString, String relPath) {
        if (treeUriString == null || treeUriString.trim().isEmpty()) return null;
        try {
            Uri treeUri = Uri.parse(treeUriString.trim());
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null || !docId.startsWith("primary:")) return null;

            String baseRel = docId.substring("primary:".length());
            String rel = relPath == null ? "" : relPath.trim();

            while (baseRel.startsWith("/")) baseRel = baseRel.substring(1);
            while (baseRel.endsWith("/")) baseRel = baseRel.substring(0, baseRel.length() - 1);
            while (rel.startsWith("/")) rel = rel.substring(1);
            while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);

            String combined;
            if (baseRel.isEmpty()) combined = rel;
            else if (rel.isEmpty()) combined = baseRel;
            else combined = baseRel + "/" + rel;

            File root = Environment.getExternalStorageDirectory();
            if (root == null) return null;
            File out = combined == null || combined.trim().isEmpty() ? root : new File(root, combined);
            return out.exists() && out.isDirectory() ? out : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isSupportedLibraryDiskFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".adf")
            || lower.endsWith(".adz")
            || lower.endsWith(".dms")
            || lower.endsWith(".ipf")
            || lower.endsWith(".zip");
    }

    private void enrichFromIgdbAsync(boolean force) {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String clientId = IgdbCredentialProvider.resolveClientId(p, PREF_IGDB_CLIENT_ID);
        String clientSecret = IgdbCredentialProvider.resolveClientSecret(p, PREF_IGDB_CLIENT_SECRET);
        String accessToken = IgdbCredentialProvider.resolveAccessToken(p, PREF_IGDB_ACCESS_TOKEN);
        if (clientId == null || clientId.trim().isEmpty() || accessToken == null || accessToken.trim().isEmpty()) {
            if (clientSecret == null || clientSecret.trim().isEmpty()) {
                if (force) {
                    Toast.makeText(this, "IGDB credentials not configured", Toast.LENGTH_SHORT).show();
                }
                AppLog.append(this, "IGDB enrichment skipped: missing credentials");
                return;
            }
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            if (force) {
                Toast.makeText(this, "IGDB credentials not configured", Toast.LENGTH_SHORT).show();
            }
            AppLog.append(this, "IGDB enrichment skipped: missing client id");
            return;
        }

        progress.setVisibility(View.VISIBLE);
        refreshIgdbBtn.setEnabled(false);

        new Thread(() -> {
            int updated = 0;
            for (int i = 0; i < allEntries.size(); i++) {
                LibraryEntry e = allEntries.get(i);
                if (e == null) continue;
                if (!force && e.coverUrl != null && !e.coverUrl.trim().isEmpty()) continue;
                String queryOverride = getIgdbQueryOverride(e.fileName);
                String query = (queryOverride == null || queryOverride.trim().isEmpty())
                    ? derivePrettyTitle(e.fileName)
                    : queryOverride.trim();
                try {
                    String normalized = OnlineAdfCatalogService.normalizeIgdbSearchTerm(query);
                    AppLog.append(this, "IGDB search start file=" + e.fileName + " normalizedQuery=\"" + normalized + "\" platform=Amiga");
                    OnlineAdfCatalogService.IgdbResult r = OnlineAdfCatalogService.searchIgdb(clientId, accessToken, clientSecret, query);
                    if (r != null) {
                        boolean changed = false;
                        if (r.name != null && !r.name.trim().isEmpty()) {
                            e.title = r.name.trim();
                            putCachedIgdbTitle(e.fileName, e.title);
                            changed = true;
                        }
                        if (r.summary != null) {
                            e.summary = r.summary.trim();
                            putCachedIgdbSummary(e.fileName, e.summary);
                            changed = true;
                        }
                        if (r.coverUrl != null && !r.coverUrl.trim().isEmpty()) {
                            e.coverUrl = r.coverUrl.trim();
                            putCachedIgdbCoverUrl(e.fileName, e.coverUrl);
                            changed = true;
                        }
                        AppLog.append(this, "IGDB search hit file=" + e.fileName + " title=\"" + (e.title == null ? "" : e.title) + "\"");
                        if (changed) updated++;
                    } else {
                        AppLog.append(this, "IGDB search miss file=" + e.fileName + " query=\"" + query + "\"");
                    }
                } catch (Throwable t) {
                    AppLog.append(this, "IGDB search error file=" + e.fileName + " msg=" + t.getMessage());
                }

                if (i % 3 == 0) {
                    runOnUiThread(() -> {
                        applyFilter();
                        adapter.notifyDataSetChanged();
                    });
                }
            }

            final int updatedFinal = updated;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                refreshIgdbBtn.setEnabled(true);
                applyFilter();
                adapter.notifyDataSetChanged();
                if (updatedFinal > 0) {
                    Toast.makeText(this, "IGDB updated " + updatedFinal + " entries", Toast.LENGTH_SHORT).show();
                }
                AppLog.append(this, "IGDB enrichment complete updated=" + updatedFinal + " total=" + allEntries.size());
            });
        }).start();
    }

    private void promptIgdbCorrection(LibraryEntry entry) {
        if (entry == null) return;
        final EditText input = new EditText(this);
        String currentOverride = getIgdbQueryOverride(entry.fileName);
        String seed = (currentOverride == null || currentOverride.trim().isEmpty())
            ? (entry.title == null ? derivePrettyTitle(entry.fileName) : entry.title)
            : currentOverride;
        input.setText(seed == null ? "" : seed);
        input.setSelection(input.getText() == null ? 0 : input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle("Correct IGDB Match")
            .setMessage("Enter the title to use for IGDB search (saved for this disk).")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String term = input.getText() == null ? "" : input.getText().toString().trim();
                if (term.isEmpty()) {
                    Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show();
                    return;
                }
                putIgdbQueryOverride(entry.fileName, term);
                AppLog.append(this, "IGDB correction requested file=" + entry.fileName + " userQuery=\"" + term + "\"");
                searchAndSelectIgdbCorrectionAsync(entry, term);
            })
            .setNeutralButton("Clear", (d, w) -> {
                clearIgdbQueryOverride(entry.fileName);
                Toast.makeText(this, "Saved IGDB override cleared", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void applyManualIgdbCorrectionAsync(LibraryEntry entry, String query) {
        if (entry == null) return;
        final String corrected = query == null ? "" : query.trim();
        if (corrected.isEmpty()) return;

        progress.setVisibility(View.VISIBLE);
        refreshIgdbBtn.setEnabled(false);

        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String clientId = IgdbCredentialProvider.resolveClientId(p, PREF_IGDB_CLIENT_ID);
        final String clientSecret = IgdbCredentialProvider.resolveClientSecret(p, PREF_IGDB_CLIENT_SECRET);
        final String accessToken = IgdbCredentialProvider.resolveAccessToken(p, PREF_IGDB_ACCESS_TOKEN);

        new Thread(() -> {
            String message;
            try {
                OnlineAdfCatalogService.IgdbResult result = null;
                if (clientId != null && !clientId.trim().isEmpty()
                    && ((accessToken != null && !accessToken.trim().isEmpty()) || (clientSecret != null && !clientSecret.trim().isEmpty()))) {
                    result = OnlineAdfCatalogService.searchIgdb(clientId, accessToken, clientSecret, corrected);
                }

                if (result != null) {
                    if (result.name != null && !result.name.trim().isEmpty()) {
                        entry.title = result.name.trim();
                        putCachedIgdbTitle(entry.fileName, entry.title);
                    } else {
                        entry.title = corrected;
                        putCachedIgdbTitle(entry.fileName, entry.title);
                    }
                    if (result.summary != null) {
                        entry.summary = result.summary.trim();
                        putCachedIgdbSummary(entry.fileName, entry.summary);
                    }
                    if (result.coverUrl != null && !result.coverUrl.trim().isEmpty()) {
                        entry.coverUrl = result.coverUrl.trim();
                        putCachedIgdbCoverUrl(entry.fileName, entry.coverUrl);
                    }
                    AppLog.append(this, "IGDB correction saved file=" + entry.fileName + " query=\"" + corrected + "\" result=hit");
                    message = "Saved IGDB correction";
                } else {
                    entry.title = corrected;
                    putCachedIgdbTitle(entry.fileName, corrected);
                    AppLog.append(this, "IGDB correction saved file=" + entry.fileName + " query=\"" + corrected + "\" result=miss");
                    message = "Saved title override";
                }
            } catch (Throwable t) {
                entry.title = corrected;
                putCachedIgdbTitle(entry.fileName, corrected);
                AppLog.append(this, "IGDB correction error file=" + entry.fileName + " msg=" + t.getMessage());
                message = "Saved title override";
            }

            final String toastMessage = message;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                refreshIgdbBtn.setEnabled(true);
                applyFilter();
                adapter.notifyDataSetChanged();
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void searchAndSelectIgdbCorrectionAsync(LibraryEntry entry, String query) {
        if (entry == null) return;
        final String corrected = query == null ? "" : query.trim();
        if (corrected.isEmpty()) return;

        progress.setVisibility(View.VISIBLE);
        refreshIgdbBtn.setEnabled(false);

        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String clientId = IgdbCredentialProvider.resolveClientId(p, PREF_IGDB_CLIENT_ID);
        final String clientSecret = IgdbCredentialProvider.resolveClientSecret(p, PREF_IGDB_CLIENT_SECRET);
        final String accessToken = IgdbCredentialProvider.resolveAccessToken(p, PREF_IGDB_ACCESS_TOKEN);

        new Thread(() -> {
            ArrayList<OnlineAdfCatalogService.IgdbResult> matches = new ArrayList<>();
            try {
                if (clientId != null && !clientId.trim().isEmpty()
                    && ((accessToken != null && !accessToken.trim().isEmpty()) || (clientSecret != null && !clientSecret.trim().isEmpty()))) {
                    ArrayList<OnlineAdfCatalogService.IgdbResult> result = OnlineAdfCatalogService.searchIgdbCandidates(
                        clientId,
                        accessToken,
                        clientSecret,
                        corrected,
                        8,
                        false
                    );
                    if (result != null) matches.addAll(result);
                }
            } catch (Throwable t) {
                AppLog.append(this, "IGDB correction candidate search error file=" + entry.fileName + " msg=" + t.getMessage());
            }

            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                refreshIgdbBtn.setEnabled(true);

                if (matches.isEmpty()) {
                    applyManualIgdbCorrectionAsync(entry, corrected);
                    return;
                }

                String[] labels = new String[matches.size()];
                for (int i = 0; i < matches.size(); i++) {
                    OnlineAdfCatalogService.IgdbResult r = matches.get(i);
                    String name = (r == null || r.name == null) ? "" : r.name.trim();
                    String summary = (r == null || r.summary == null) ? "" : r.summary.trim();
                    if (!summary.isEmpty()) {
                        if (summary.length() > 80) summary = summary.substring(0, 80) + "...";
                        labels[i] = name + "\n" + summary;
                    } else {
                        labels[i] = name;
                    }
                }

                new AlertDialog.Builder(this)
                    .setTitle("Select IGDB Match")
                    .setItems(labels, (d, which) -> {
                        if (which < 0 || which >= matches.size()) return;
                        OnlineAdfCatalogService.IgdbResult chosen = matches.get(which);
                        if (chosen == null) return;

                        if (chosen.name != null && !chosen.name.trim().isEmpty()) {
                            entry.title = chosen.name.trim();
                            putCachedIgdbTitle(entry.fileName, entry.title);
                        } else {
                            entry.title = corrected;
                            putCachedIgdbTitle(entry.fileName, entry.title);
                        }
                        if (chosen.summary != null) {
                            entry.summary = chosen.summary.trim();
                            putCachedIgdbSummary(entry.fileName, entry.summary);
                        }
                        if (chosen.coverUrl != null && !chosen.coverUrl.trim().isEmpty()) {
                            entry.coverUrl = chosen.coverUrl.trim();
                            putCachedIgdbCoverUrl(entry.fileName, entry.coverUrl);
                        }

                        AppLog.append(this, "IGDB correction selected file=" + entry.fileName + " query=\"" + corrected + "\" match=\"" + (entry.title == null ? "" : entry.title) + "\"");
                        applyFilter();
                        adapter.notifyDataSetChanged();
                        Toast.makeText(this, "Saved IGDB match", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }).start();
    }

    private void promptAndSearchNewAdf() {
        final EditText input = new EditText(this);
        input.setHint("Enter title to search/download");
        new AlertDialog.Builder(this)
            .setTitle("Search New ADF")
            .setView(input)
            .setPositiveButton("Search", (d, w) -> {
                String term = input.getText() == null ? "" : input.getText().toString().trim();
                if (term.isEmpty()) {
                    Toast.makeText(this, "Enter a search term", Toast.LENGTH_SHORT).show();
                    return;
                }
                AppLog.append(this, "NEW ADF SEARCH requested term=\"" + term + "\"");
                promptTosecMode(term);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void promptTosecMode(String term) {
        final String[] modes = new String[]{"All", "AGA only", "Non-AGA only"};
        new AlertDialog.Builder(this)
            .setTitle("Search mode")
            .setItems(modes, (d, which) -> searchTosecAsync(term, which))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void searchTosecAsync(String term, int mode) {
        progress.setVisibility(View.VISIBLE);
        searchNewBtn.setEnabled(false);
        refreshIgdbBtn.setEnabled(false);
        new Thread(() -> {
            String error = null;
            ArrayList<TosecResult> results = new ArrayList<>();
            try {
                results.addAll(searchTosec(term, mode));
                AppLog.append(this, "TOSEC search term=\"" + term + "\" mode=" + mode + " results=" + results.size());
            } catch (Throwable t) {
                error = t.getMessage();
                AppLog.append(this, "TOSEC search error term=\"" + term + "\" msg=" + error);
            }

            final String err = error;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                searchNewBtn.setEnabled(true);
                refreshIgdbBtn.setEnabled(true);
                if (err != null) {
                    Toast.makeText(this, "TOSEC search failed: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                if (results.isEmpty()) {
                    Toast.makeText(this, "No TOSEC matches", Toast.LENGTH_SHORT).show();
                    return;
                }
                chooseTosecGameAndVariant(results);
            });
        }).start();
    }

    private ArrayList<TosecResult> searchTosec(String term, int mode) throws Exception {
        String body = "search_text=" + URLEncoder.encode(term, "UTF-8")
            + "&section_games=on"
            + "&sort_column=title&sort_dir=ASC&page=0";

        HttpURLConnection conn = (HttpURLConnection) new URL(TOSEC_BASE + "/index.php").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "uae4arm_2026/1.0");
        byte[] payload = body.getBytes("UTF-8");
        conn.getOutputStream().write(payload);
        conn.getOutputStream().flush();
        conn.getOutputStream().close();

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) throw new IllegalStateException("HTTP " + code);
        String html;
        try {
            html = new String(readAllBytes(in), "UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            conn.disconnect();
        }

        ArrayList<TosecResult> out = new ArrayList<>();
        Pattern p = Pattern.compile("javascript:tosecGetFile\\('([^']+)'\\)[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        Set<String> seen = new HashSet<>();
        while (m.find() && out.size() < 80) {
            String token = m.group(1) == null ? "" : m.group(1).trim();
            String titleRaw = m.group(2) == null ? "" : m.group(2);
            String title = android.text.Html.fromHtml(titleRaw).toString().trim();
            title = title.replaceAll("\\s+", " ");
            if (token.isEmpty() || title.isEmpty()) continue;
            boolean isAga = title.toLowerCase(Locale.ROOT).contains("(aga)");
            if (mode == TOSEC_MODE_AGA && !isAga) continue;
            if (mode == TOSEC_MODE_NON_AGA && isAga) continue;
            String key = token + "|" + title;
            if (seen.contains(key)) continue;
            seen.add(key);
            int[] disk = extractDiskMeta(title);
            out.add(new TosecResult(token, title, disk[0], disk[1]));
        }
        return out;
    }

    private void chooseTosecGameAndVariant(ArrayList<TosecResult> results) {
        Map<String, ArrayList<TosecResult>> byGame = new HashMap<>();
        for (TosecResult result : results) {
            String key = gameKeyForTosec(result.title);
            ArrayList<TosecResult> list = byGame.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byGame.put(key, list);
            }
            list.add(result);
        }

        ArrayList<String> gameKeys = new ArrayList<>(byGame.keySet());
        gameKeys.sort(String::compareToIgnoreCase);
        if (gameKeys.isEmpty()) return;
        if (gameKeys.size() == 1) {
            chooseTosecVariant(gameKeys.get(0), byGame.get(gameKeys.get(0)));
            return;
        }

        String[] labels = gameKeys.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Select Game")
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= gameKeys.size()) return;
                String key = gameKeys.get(which);
                chooseTosecVariant(key, byGame.get(key));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void chooseTosecVariant(String gameKey, ArrayList<TosecResult> gameResults) {
        if (gameResults == null || gameResults.isEmpty()) return;
        Map<String, ArrayList<TosecResult>> variants = new HashMap<>();
        for (TosecResult result : gameResults) {
            String v = variantKeyForTosec(result.title);
            ArrayList<TosecResult> list = variants.get(v);
            if (list == null) {
                list = new ArrayList<>();
                variants.put(v, list);
            }
            list.add(result);
        }

        ArrayList<String> keys = new ArrayList<>(variants.keySet());
        keys.sort(String::compareToIgnoreCase);
        if (keys.size() == 1) {
            String key = keys.get(0);
            chooseTosecDisks(gameKey, key, variants.get(key));
            return;
        }

        String[] labels = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            ArrayList<TosecResult> variant = variants.get(keys.get(i));
            int count = variant == null ? 0 : variant.size();
            labels[i] = keys.get(i) + "  [" + count + " disks/files]";
        }

        new AlertDialog.Builder(this)
            .setTitle("Select Version: " + gameKey)
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= keys.size()) return;
                String key = keys.get(which);
                chooseTosecDisks(gameKey, key, variants.get(key));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void chooseTosecDisks(String gameKey, String variantLabel, ArrayList<TosecResult> variant) {
        if (variant == null || variant.isEmpty()) return;

        ArrayList<TosecResult> sorted = new ArrayList<>(variant);
        sorted.sort((a, b) -> {
            int da = a == null ? Integer.MAX_VALUE : (a.diskNo <= 0 ? Integer.MAX_VALUE : a.diskNo);
            int db = b == null ? Integer.MAX_VALUE : (b.diskNo <= 0 ? Integer.MAX_VALUE : b.diskNo);
            if (da != db) return Integer.compare(da, db);
            String ta = a == null || a.title == null ? "" : a.title;
            String tb = b == null || b.title == null ? "" : b.title;
            return ta.compareToIgnoreCase(tb);
        });

        progress.setVisibility(View.VISIBLE);
        setTopActionsEnabled(false);

        new Thread(() -> {
            for (TosecResult item : sorted) {
                if (item == null) continue;
                if (item.suggestedFileName != null && !item.suggestedFileName.trim().isEmpty()) continue;
                try {
                    item.suggestedFileName = fetchTosecRemoteFileName(item);
                } catch (Throwable ignored) {
                }
            }

            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                setTopActionsEnabled(true);
                showTosecDiskPickerDialog(gameKey, variantLabel, sorted);
            });
        }).start();
    }

    private void showTosecDiskPickerDialog(String gameKey, String variantLabel, ArrayList<TosecResult> sorted) {
        if (sorted == null || sorted.isEmpty()) return;

        String[] labels = new String[sorted.size()];
        boolean[] checked = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            TosecResult r = sorted.get(i);
            checked[i] = false;
            if (r == null) {
                labels[i] = "(unknown)";
                continue;
            }

            String diskLabel;
            if (r.diskNo > 0 && r.diskTotal > 0) {
                diskLabel = "Disk " + r.diskNo + " of " + r.diskTotal;
            } else if (r.diskNo > 0) {
                diskLabel = "Disk " + r.diskNo;
            } else {
                diskLabel = "File";
            }
            String fileNameLabel = tosecFileNameLabel(r);
            labels[i] = diskLabel + " — " + fileNameLabel;
        }

        String title = "Select Disks: " + gameKey;
        if (variantLabel != null && !variantLabel.trim().isEmpty() && !variantLabel.equals(gameKey)) {
            title += "\n" + variantLabel;
        }

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("Download", (d, w) -> {
                ArrayList<TosecResult> selected = new ArrayList<>();
                for (int i = 0; i < sorted.size(); i++) {
                    if (!checked[i]) continue;
                    TosecResult item = sorted.get(i);
                    if (item != null) selected.add(item);
                }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Select at least one disk/file", Toast.LENGTH_SHORT).show();
                    return;
                }
                downloadTosecVariantAsync(selected, variantLabel == null ? gameKey : variantLabel);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setTopActionsEnabled(boolean enabled) {
        if (searchNewBtn != null) searchNewBtn.setEnabled(enabled);
        if (refreshIgdbBtn != null) refreshIgdbBtn.setEnabled(enabled);
        if (logsBtn != null) logsBtn.setEnabled(enabled);
    }

    private void downloadTosecAsync(TosecResult item) {
        if (item == null || item.token == null || item.token.trim().isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        searchNewBtn.setEnabled(false);
        refreshIgdbBtn.setEnabled(false);

        new Thread(() -> {
            String error = null;
            File downloaded = null;
            try {
                downloaded = downloadTosec(item);
                AppLog.append(this, "TOSEC download success title=\"" + item.title + "\" file=" + (downloaded == null ? "" : downloaded.getName()));
            } catch (Throwable t) {
                error = t.getMessage();
                AppLog.append(this, "TOSEC download error title=\"" + item.title + "\" msg=" + error);
            }

            final String err = error;
            final File downloadedFinal = downloaded;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                searchNewBtn.setEnabled(true);
                refreshIgdbBtn.setEnabled(true);
                if (err != null || downloadedFinal == null) {
                    Toast.makeText(this, "Download failed: " + (err == null ? "unknown" : err), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "Downloaded: " + downloadedFinal.getName(), Toast.LENGTH_SHORT).show();
                loadLibraryAsync();
            });
        }).start();
    }

    private void downloadTosecVariantAsync(ArrayList<TosecResult> variant, String variantLabel) {
        if (variant == null || variant.isEmpty()) return;
        variant.sort((a, b) -> {
            int da = a == null ? Integer.MAX_VALUE : (a.diskNo <= 0 ? Integer.MAX_VALUE : a.diskNo);
            int db = b == null ? Integer.MAX_VALUE : (b.diskNo <= 0 ? Integer.MAX_VALUE : b.diskNo);
            if (da != db) return Integer.compare(da, db);
            String ta = a == null || a.title == null ? "" : a.title;
            String tb = b == null || b.title == null ? "" : b.title;
            return ta.compareToIgnoreCase(tb);
        });

        progress.setVisibility(View.VISIBLE);
        searchNewBtn.setEnabled(false);
        refreshIgdbBtn.setEnabled(false);

        new Thread(() -> {
            String error = null;
            int downloaded = 0;
            try {
                for (TosecResult item : variant) {
                    if (item == null) continue;
                    File file = downloadTosec(item);
                    if (file != null && file.exists()) downloaded++;
                }
                AppLog.append(this, "TOSEC variant download success variant=\"" + variantLabel + "\" files=" + downloaded);
            } catch (Throwable t) {
                error = t.getMessage();
                AppLog.append(this, "TOSEC variant download error variant=\"" + variantLabel + "\" msg=" + error);
            }

            final String err = error;
            final int files = downloaded;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                searchNewBtn.setEnabled(true);
                refreshIgdbBtn.setEnabled(true);
                if (err != null) {
                    Toast.makeText(this, "Download failed: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "Downloaded " + files + " file(s)", Toast.LENGTH_SHORT).show();
                loadLibraryAsync();
            });
        }).start();
    }

    private File downloadTosec(TosecResult item) throws Exception {
        String u = TOSEC_BASE + "/download.php?tosec=" + URLEncoder.encode(item.token.trim(), "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "uae4arm_2026/1.0");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }

        String fileName = extractFileNameFromContentDisposition(conn.getHeaderField("Content-Disposition"));
        if ((fileName == null || fileName.trim().isEmpty()) && item != null && item.suggestedFileName != null) {
            fileName = item.suggestedFileName;
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = sanitizeFileName(item.title) + ".zip";
        }

        // Buffer the entire download into memory first
        byte[] data;
        InputStream in = conn.getInputStream();
        try {
            data = readAllBytes(in);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            conn.disconnect();
        }

        // Try writing to the SAF floppies folder first (the user's chosen external folder)
        File internalCopy = writeToSafFloppiesThenSyncInternal(fileName, data);
        if (internalCopy != null && internalCopy.exists() && internalCopy.length() > 0) {
            return internalCopy;
        }

        // Fallback: write directly to internal disks dir (raw File I/O always works here)
        AppLog.append(this, "SAF floppies write unavailable, saving to internal storage only");
        File internalDisks = new File(AppPaths.getBaseDir(this), "disks");
        ensureDir(internalDisks);
        File out = uniqueFile(new File(internalDisks, fileName));
        writeBytes(out, data);

        if (!out.exists() || out.length() <= 0) {
            throw new IllegalStateException("Downloaded file empty");
        }
        return out;
    }

    /**
     * Write a downloaded file to the SAF floppies folder, then sync a copy to
     * internal storage so the emulator can read it via raw File path.
     * Returns the internal File copy, or null if SAF write failed.
     */
    private File writeToSafFloppiesThenSyncInternal(String fileName, byte[] data) {
        try {
            SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String floppiesUri = prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
            String parentTreeUri = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);

            DocumentFile safDir = resolveFloppiesRoot(floppiesUri, parentTreeUri);
            if (safDir == null || !safDir.exists() || !safDir.isDirectory()) {
                AppLog.append(this, "SAF floppies root not resolved for download");
                return null;
            }

            // Determine MIME type for createFile
            String mime = "application/octet-stream";
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".adf")) mime = "application/x-amiga-disk-format";
            else if (lower.endsWith(".zip")) mime = "application/zip";

            // Avoid overwriting: check if file already exists
            String baseName = fileName;
            DocumentFile existing = safDir.findFile(baseName);
            if (existing != null && existing.exists()) {
                int dot = baseName.lastIndexOf('.');
                String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
                String ext = dot > 0 ? baseName.substring(dot) : "";
                for (int i = 2; i < 999; i++) {
                    String candidate = stem + "_" + i + ext;
                    DocumentFile check = safDir.findFile(candidate);
                    if (check == null || !check.exists()) {
                        baseName = candidate;
                        break;
                    }
                }
            }

            DocumentFile created = safDir.createFile(mime, baseName);
            if (created == null) {
                AppLog.append(this, "SAF createFile returned null for: " + baseName);
                return null;
            }

            // Write data to SAF
            try (OutputStream os = getContentResolver().openOutputStream(created.getUri())) {
                if (os == null) {
                    AppLog.append(this, "SAF openOutputStream returned null");
                    return null;
                }
                os.write(data);
                os.flush();
            }
            AppLog.append(this, "Downloaded to SAF floppies: " + baseName + " (" + data.length + " bytes)");

            // Sync a copy to internal disks dir so the emulator can read it
            File internalDisks = new File(AppPaths.getBaseDir(this), "disks");
            ensureDir(internalDisks);
            File internalTarget = uniqueFile(new File(internalDisks, baseName));
            if (copyDocumentToFile(created.getUri(), internalTarget)) {
                AppLog.append(this, "Synced SAF download to internal: " + internalTarget.getName());
                return internalTarget;
            }

            // Direct fallback: write bytes to internal
            writeBytes(internalTarget, data);
            return internalTarget;
        } catch (Throwable t) {
            AppLog.append(this, "SAF floppies download failed: " + t.getMessage());
            return null;
        }
    }

    private void writeBytes(File target, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(data);
            fos.flush();
        }
    }

    private String fetchTosecRemoteFileName(TosecResult item) throws Exception {
        if (item == null || item.token == null || item.token.trim().isEmpty()) {
            return null;
        }
        String u = TOSEC_BASE + "/download.php?tosec=" + URLEncoder.encode(item.token.trim(), "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "uae4arm_2026/1.0");
        conn.setRequestProperty("Range", "bytes=0-0");

        try {
            int code = conn.getResponseCode();
            if ((code < 200 || code >= 300) && code != 206) {
                return null;
            }
            String fromHeader = extractFileNameFromContentDisposition(conn.getHeaderField("Content-Disposition"));
            if (fromHeader != null && !fromHeader.trim().isEmpty()) {
                return fromHeader;
            }
            return null;
        } finally {
            try {
                InputStream in = conn.getInputStream();
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            conn.disconnect();
        }
    }

    private String extractFileNameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.trim().isEmpty()) return null;
        String cd = contentDisposition;

        Matcher quoted = Pattern.compile("(?i)filename\\*=UTF-8''([^;]+)|filename=\\\"([^\\\"]+)\\\"|filename=([^;]+)").matcher(cd);
        if (quoted.find()) {
            String value = quoted.group(1);
            if (value == null || value.trim().isEmpty()) value = quoted.group(2);
            if (value == null || value.trim().isEmpty()) value = quoted.group(3);
            if (value != null) {
                value = value.trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);
                }
                try {
                    value = java.net.URLDecoder.decode(value, "UTF-8");
                } catch (Throwable ignored) {
                }
                if (!value.isEmpty()) return value;
            }
        }

        int idx = cd.toLowerCase(Locale.ROOT).indexOf("filename=");
        if (idx >= 0) {
            String value = cd.substring(idx + 9).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private File uniqueFile(File initial) {
        if (initial == null) return null;
        if (!initial.exists()) return initial;
        String name = initial.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        File dir = initial.getParentFile();
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private String sanitizeFileName(String value) {
        if (value == null) return "download";
        String s = value.trim();
        if (s.isEmpty()) return "download";
        s = s.replace('/', '_').replace('\\', '_').replace(':', '_').replace('*', '_')
            .replace('?', '_').replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_');
        return s;
    }

    private byte[] readAllBytes(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private void applyFilter() {
        String q = "";
        filteredEntries.clear();
        visibleGroups.clear();

        Map<String, ArrayList<LibraryEntry>> grouped = new HashMap<>();
        for (LibraryEntry entry : allEntries) {
            if (entry == null) continue;
            String key = libraryGroupKey(entry);
            ArrayList<LibraryEntry> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(key, list);
            }
            list.add(entry);
        }

        for (ArrayList<LibraryEntry> group : grouped.values()) {
            if (group == null || group.isEmpty()) continue;
            LibraryEntry representative = pickRepresentativeDisk(group);

            String t = representative.title == null ? "" : representative.title.toLowerCase(Locale.ROOT);
            String f = representative.fileName == null ? "" : representative.fileName.toLowerCase(Locale.ROOT);
            String s = representative.summary == null ? "" : representative.summary.toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !t.contains(q) && !f.contains(q) && !s.contains(q)) {
                boolean anyMatch = false;
                for (LibraryEntry e : group) {
                    String et = e.title == null ? "" : e.title.toLowerCase(Locale.ROOT);
                    String ef = e.fileName == null ? "" : e.fileName.toLowerCase(Locale.ROOT);
                    String es = e.summary == null ? "" : e.summary.toLowerCase(Locale.ROOT);
                    if (et.contains(q) || ef.contains(q) || es.contains(q)) {
                        anyMatch = true;
                        break;
                    }
                }
                if (!anyMatch) continue;
            }

            filteredEntries.add(representative);
            visibleGroups.put(representative.file.getAbsolutePath(), group);
        }

        filteredEntries.sort((a, b) -> {
            String at = a == null || a.title == null ? "" : a.title;
            String bt = b == null || b.title == null ? "" : b.title;
            return at.compareToIgnoreCase(bt);
        });
        updateDiskCountSummary();
        adapter.notifyDataSetChanged();
        if (libraryViewMode == VIEW_MODE_CAROUSEL) {
            rebuildCarouselStrip();
        }
    }

    private void updateDiskCountSummary() {
        if (diskCountView == null) return;
        int visibleDiskCount = 0;
        for (ArrayList<LibraryEntry> group : visibleGroups.values()) {
            if (group == null) continue;
            visibleDiskCount += group.size();
        }
        diskCountView.setText("Showing " + visibleDiskCount + " disks");
    }

    private String tosecFileNameLabel(TosecResult result) {
        if (result == null || result.title == null) return "";
        if (result.suggestedFileName != null && !result.suggestedFileName.trim().isEmpty()) {
            return result.suggestedFileName.trim();
        }
        String label = result.title.trim();
        if (label.isEmpty()) return "";
        String fallback = sanitizeFileName(label) + ".zip";
        if (result.token != null && !result.token.trim().isEmpty()) {
            String token = result.token.trim();
            if (token.length() > 8) token = token.substring(token.length() - 8);
            fallback += " [" + token + "]";
        }
        return fallback;
    }

    private LibraryEntry pickRepresentativeDisk(ArrayList<LibraryEntry> group) {
        if (group == null || group.isEmpty()) return null;
        LibraryEntry best = group.get(0);
        for (LibraryEntry entry : group) {
            if (entry == null) continue;
            if (extractDiskNo(entry) == 1) return entry;
        }
        return best;
    }

    private int extractDiskNo(LibraryEntry entry) {
        if (entry == null) return Integer.MAX_VALUE;
        int[] fromTitle = extractDiskMeta(entry.title);
        if (fromTitle[0] > 0) return fromTitle[0];
        int[] fromFile = extractDiskMeta(entry.fileName);
        if (fromFile[0] > 0) return fromFile[0];
        return Integer.MAX_VALUE;
    }

    private int[] extractDiskMeta(String text) {
        if (text == null) return new int[]{-1, -1};
        Matcher m = Pattern.compile("(?i)(?:disk\\s*)?(\\d+)\\s*of\\s*(\\d+)").matcher(text);
        if (m.find()) {
            try {
                int disk = Integer.parseInt(m.group(1));
                int total = Integer.parseInt(m.group(2));
                return new int[]{disk, total};
            } catch (Throwable ignored) {
            }
        }
        return new int[]{-1, -1};
    }

    private String stripDiskMarker(String value) {
        if (value == null) return "";
        String out = value.replaceAll("(?i)\\(\\s*(?:disk\\s*)?\\d+\\s*of\\s*\\d+[^)]*\\)", " ");
        out = out.replaceAll("(?i)(?:disk\\s*)?\\d+\\s*of\\s*\\d+", " ");
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    private String libraryGroupKey(LibraryEntry entry) {
        if (entry == null) return "";
        String override = getGroupOverride(entry.fileName);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim().toLowerCase(Locale.ROOT);
        }

        int[] diskMeta = extractDiskMeta(entry.title);
        if (diskMeta[1] <= 1) {
            diskMeta = extractDiskMeta(entry.fileName);
        }
        boolean isExplicitMultiDisk = diskMeta[0] > 0 && diskMeta[1] > 1;
        if (!isExplicitMultiDisk) {
            String single = entry.fileName == null ? "" : entry.fileName.trim().toLowerCase(Locale.ROOT);
            if (single.isEmpty() && entry.file != null) {
                single = entry.file.getAbsolutePath().toLowerCase(Locale.ROOT);
            }
            return "single::" + single;
        }

        String pretty = derivePrettyTitle(entry.fileName);
        String key = prefixBeforeDiskMarker(pretty);
        if (key == null || key.trim().isEmpty()) {
            key = stripDiskMarker(pretty);
        }
        if (key == null || key.trim().isEmpty()) {
            key = derivePrettyTitle(entry.fileName);
        }
        return key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isLibraryHidden(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return false;
            String key = PREF_LIBRARY_HIDDEN_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(key, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void markLibraryHidden(String fileName, boolean hidden) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return;
            String key = PREF_LIBRARY_HIDDEN_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(key, hidden).apply();
        } catch (Throwable ignored) {
        }
    }

    private String getGroupOverride(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return null;
            String key = PREF_LIBRARY_GROUP_OVERRIDE_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putGroupOverride(String fileName, String groupKey) {
        try {
            if (fileName == null || groupKey == null) return;
            String f = fileName.trim().toLowerCase(Locale.ROOT);
            String g = groupKey.trim().toLowerCase(Locale.ROOT);
            if (f.isEmpty() || g.isEmpty()) return;
            String key = PREF_LIBRARY_GROUP_OVERRIDE_PREFIX + f;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, g).apply();
        } catch (Throwable ignored) {
        }
    }

    private String prefixBeforeDiskMarker(String value) {
        if (value == null) return "";
        String in = value.trim();
        if (in.isEmpty()) return "";

        Matcher withParens = Pattern.compile("(?i)\\(\\s*(?:disk\\s*)?\\d+\\s*of\\s*\\d+\\s*\\)").matcher(in);
        if (withParens.find()) {
            String out = in.substring(0, withParens.start()).trim();
            return out.replaceAll("\\s+", " ");
        }

        Matcher plain = Pattern.compile("(?i)(?:disk\\s*)?\\d+\\s*of\\s*\\d+").matcher(in);
        if (plain.find()) {
            String out = in.substring(0, plain.start()).trim();
            return out.replaceAll("\\s+", " ");
        }

        return in;
    }

    private String gameKeyForTosec(String title) {
        if (title == null) return "";
        int idx = title.indexOf('(');
        String base = (idx > 0) ? title.substring(0, idx) : title;
        return base.replaceAll("\\s+", " ").trim();
    }

    private String variantKeyForTosec(String title) {
        String key = stripDiskMarker(title == null ? "" : title);
        if (key == null || key.trim().isEmpty()) {
            key = prefixBeforeDiskMarker(title == null ? "" : title);
        }
        return key == null ? "" : key;
    }

    private String derivePrettyTitle(String fileName) {
        if (fileName == null) return "";
        String n = fileName.trim();
        if (n.isEmpty()) return "";
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.startsWith("df0__") || lower.startsWith("df1__") || lower.startsWith("df2__") || lower.startsWith("df3__")) {
            n = n.substring(5);
        }
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replace('_', ' ').replace('-', ' ').trim();
    }

    private String getCachedIgdbTitle(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return null;
            String key = PREF_IGDB_TITLE_CACHE_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putCachedIgdbTitle(String fileName, String title) {
        try {
            if (fileName == null || title == null) return;
            String f = fileName.trim().toLowerCase(Locale.ROOT);
            String t = title.trim();
            if (f.isEmpty() || t.isEmpty()) return;
            String key = PREF_IGDB_TITLE_CACHE_PREFIX + f;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, t).apply();
        } catch (Throwable ignored) {
        }
    }

    private String getCachedIgdbSummary(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return null;
            String key = PREF_IGDB_SUMMARY_CACHE_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putCachedIgdbSummary(String fileName, String summary) {
        try {
            if (fileName == null || summary == null) return;
            String f = fileName.trim().toLowerCase(Locale.ROOT);
            String s = summary.trim();
            if (f.isEmpty() || s.isEmpty()) return;
            String key = PREF_IGDB_SUMMARY_CACHE_PREFIX + f;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, s).apply();
        } catch (Throwable ignored) {
        }
    }

    private String getCachedIgdbCoverUrl(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return null;
            String key = PREF_IGDB_COVER_URL_CACHE_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putCachedIgdbCoverUrl(String fileName, String coverUrl) {
        try {
            if (fileName == null || coverUrl == null) return;
            String f = fileName.trim().toLowerCase(Locale.ROOT);
            String u = coverUrl.trim();
            if (f.isEmpty() || u.isEmpty()) return;
            String key = PREF_IGDB_COVER_URL_CACHE_PREFIX + f;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, u).apply();
        } catch (Throwable ignored) {
        }
    }

    private String getIgdbQueryOverride(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return null;
            String key = PREF_IGDB_QUERY_OVERRIDE_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(key, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putIgdbQueryOverride(String fileName, String value) {
        try {
            if (fileName == null || value == null) return;
            String f = fileName.trim().toLowerCase(Locale.ROOT);
            String v = value.trim();
            if (f.isEmpty() || v.isEmpty()) return;
            String key = PREF_IGDB_QUERY_OVERRIDE_PREFIX + f;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, v).apply();
        } catch (Throwable ignored) {
        }
    }

    private void clearIgdbQueryOverride(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return;
            String key = PREF_IGDB_QUERY_OVERRIDE_PREFIX + fileName.trim().toLowerCase(Locale.ROOT);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(key).apply();
        } catch (Throwable ignored) {
        }
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            if (libraryViewMode < VIEW_MODE_LIST || libraryViewMode > VIEW_MODE_CAROUSEL) {
                return VIEW_MODE_LIST;
            }
            return libraryViewMode;
        }

        @Override
        public int getCount() {
            return filteredEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int type = getItemViewType(position);
            RowHolder h;
            if (convertView == null) {
                if (type == VIEW_MODE_COVERS) {
                    convertView = createCoversRow();
                } else if (type == VIEW_MODE_CAROUSEL) {
                    convertView = createCarouselRow();
                } else {
                    convertView = createListRow();
                }
            }
            h = (RowHolder) convertView.getTag();

            LibraryEntry e = filteredEntries.get(position);
            String title = (e.title == null || e.title.trim().isEmpty()) ? e.fileName : e.title;
            ArrayList<LibraryEntry> group = visibleGroups.get(e.file.getAbsolutePath());
            int groupCount = group == null ? 1 : group.size();
            boolean aga = isAgaGroup(group) || isAgaEntry(e);
            if (groupCount > 1) {
                title = stripDiskMarker(title) + " (" + groupCount + " disks)";
            }
            if (aga && !containsAgaMarker(title)) {
                title = title + " [AGA]";
            }

            if (h.title != null) {
                h.title.setText(title);
            }
            if (h.summary != null) {
                h.summary.setText((e.summary == null || e.summary.trim().isEmpty()) ? "No IGDB summary yet" : e.summary);
            }
            if (h.fileName != null) {
                h.fileName.setText(e.fileName == null ? "" : e.fileName);
            }

            bindCover(h.cover, e.coverUrl);
            return convertView;
        }

        private View createListRow() {
            LinearLayout row = new LinearLayout(AdfLibraryActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setMinimumHeight(dp(110));

            ImageView cover = new ImageView(AdfLibraryActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(72), dp(96));
            clp.rightMargin = dp(10);
            row.addView(cover, clp);

            LinearLayout textWrap = new LinearLayout(AdfLibraryActivity.this);
            textWrap.setOrientation(LinearLayout.VERTICAL);
            textWrap.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(AdfLibraryActivity.this);
            title.setTextSize(17f);
            title.setMaxLines(1);

            TextView summary = new TextView(AdfLibraryActivity.this);
            summary.setTextSize(13f);
            summary.setMaxLines(3);

            TextView fileName = new TextView(AdfLibraryActivity.this);
            fileName.setTextSize(11f);
            fileName.setMaxLines(1);

            textWrap.addView(title);
            textWrap.addView(summary);
            textWrap.addView(fileName);
            row.addView(textWrap);

            RowHolder h = new RowHolder();
            h.cover = cover;
            h.title = title;
            h.summary = summary;
            h.fileName = fileName;
            row.setTag(h);
            return row;
        }

        private View createCoversRow() {
            LinearLayout row = new LinearLayout(AdfLibraryActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));

            ImageView cover = new ImageView(AdfLibraryActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(120), dp(160));
            row.addView(cover, clp);

            RowHolder h = new RowHolder();
            h.cover = cover;
            row.setTag(h);
            return row;
        }

        private View createCarouselRow() {
            LinearLayout row = new LinearLayout(AdfLibraryActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));

            ImageView cover = new ImageView(AdfLibraryActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(180), dp(240));
            row.addView(cover, clp);

            TextView title = new TextView(AdfLibraryActivity.this);
            title.setTextSize(16f);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setMaxLines(1);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(6);
            row.addView(title, tlp);

            RowHolder h = new RowHolder();
            h.cover = cover;
            h.title = title;
            row.setTag(h);
            return row;
        }
    }

    private static final class RowHolder {
        ImageView cover;
        TextView title;
        TextView summary;
        TextView fileName;
    }

    private boolean isAgaGroup(ArrayList<LibraryEntry> group) {
        if (group == null || group.isEmpty()) return false;
        for (LibraryEntry entry : group) {
            if (isAgaEntry(entry)) return true;
        }
        return false;
    }

    private boolean isAgaEntry(LibraryEntry entry) {
        if (entry == null) return false;
        return containsAgaMarker(entry.title) || containsAgaMarker(entry.fileName);
    }

    private boolean containsAgaMarker(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.contains("(aga)")
            || v.contains("[aga]")
            || v.contains(" aga ")
            || v.startsWith("aga ")
            || v.endsWith(" aga");
    }

    private void bindCover(ImageView iv, String url) {
        iv.setImageResource(android.R.drawable.ic_menu_gallery);
        if (url == null || url.trim().isEmpty()) return;

        File coverFile = getCoverCacheFile(url);
        if (coverFile.exists() && coverFile.isFile() && coverFile.length() > 0) {
            Bitmap diskBmp = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
            if (diskBmp != null) {
                synchronized (coverCache) {
                    coverCache.put(url, diskBmp);
                }
                iv.setImageBitmap(diskBmp);
                return;
            }
        }

        Bitmap cached;
        synchronized (coverCache) {
            cached = coverCache.get(url);
        }
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        iv.setTag(url);
        synchronized (coverLoading) {
            if (coverLoading.contains(url)) return;
            coverLoading.add(url);
        }

        new Thread(() -> {
            Bitmap bmp = null;
            try {
                bmp = downloadBitmap(url, coverFile);
                if (bmp != null) {
                    synchronized (coverCache) {
                        coverCache.put(url, bmp);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (coverLoading) {
                    coverLoading.remove(url);
                }
            }

            final Bitmap finalBmp = bmp;
            runOnUiThread(() -> {
                Object tag = iv.getTag();
                if (tag != null && tag.equals(url) && finalBmp != null) {
                    iv.setImageBitmap(finalBmp);
                }
            });
        }).start();
    }

    private Bitmap downloadBitmap(String url, File outFile) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "uae4arm_2026/1.0");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            in = conn.getInputStream();
            if (outFile != null) {
                ensureDir(outFile.getParentFile());
                out = new FileOutputStream(outFile);
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
                return BitmapFactory.decodeFile(outFile.getAbsolutePath());
            }
            return BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
            try {
                if (conn != null) conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private File getCoverCacheDir() {
        File dir = new File(getCacheDir(), "igdb_covers");
        ensureDir(dir);
        return dir;
    }

    private File getCoverCacheFile(String url) {
        String key = sha1(url == null ? "" : url.trim());
        if (key == null || key.trim().isEmpty()) {
            key = Integer.toHexString((url == null ? "" : url).hashCode());
        }
        return new File(getCoverCacheDir(), key + ".img");
    }

    private String sha1(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((text == null ? "" : text).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
