package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.security.MessageDigest;

public class LhaLibraryActivity extends Activity {
    private static final String PREFS_BOOTSTRAP = "bootstrap";
    private static final String PREFS_WHDBOOTER = "whdbooter";
    private static final String PREF_USE_JST = "use_jst";
    private static final String PREF_WRITE_CACHE = "write_cache";
    private static final String PREF_SHOW_SPLASH = "show_splash";
    private static final String PREF_QUIT_ON_EXIT = "quit_on_exit";
    private static final String PREF_IGDB_CLIENT_ID = "igdb_client_id";
    private static final String PREF_IGDB_CLIENT_SECRET = "igdb_client_secret";
    private static final String PREF_IGDB_ACCESS_TOKEN = "igdb_access_token";
    private static final String PREF_IGDB_TITLE_CACHE_PREFIX = "igdb_title_cache_";
    private static final String PREF_IGDB_COVER_URL_CACHE_PREFIX = "igdb_cover_url_cache_";
    private static final String PREF_LIBRARY_VIEW_MODE = "lha_library_view_mode";
    private static final int VIEW_MODE_LIST = 0;
    private static final int VIEW_MODE_COVERS = 1;
    private static final int VIEW_MODE_CAROUSEL = 2;
    private static final String PREF_LHA_CATEGORY_PREFIX = "lha_category_";
    private static final String CATEGORY_ARCADE = "arcade";
    private static final List<String> CONSOLE_MARKERS = Arrays.asList(
        "snes", "super nintendo", "nes", "nintendo 64", "n64", "game boy", "gameboy",
        "playstation", "ps1", "ps2", "ps3", "ps4", "ps5", "xbox", "switch",
        "megadrive", "mega drive", "genesis", "master system", "saturn", "dreamcast"
    );
    private static final List<String> ARCADE_MARKERS = Arrays.asList(
        "arcade", "coin-op", "coin op", "coinop", "mame", "cps", "cps2", "cps3"
    );

    private static final class IgdbLookupOutcome {
        final String query;
        final OnlineAdfCatalogService.IgdbResult result;

        IgdbLookupOutcome(String query, OnlineAdfCatalogService.IgdbResult result) {
            this.query = query;
            this.result = result;
        }
    }

    private static final class LhaEntry {
        final String fileName;
        final String sourcePath;
        final boolean contentUri;
        String displayTitle;
        String coverUrl;
        String category;

        LhaEntry(String fileName, String sourcePath, boolean contentUri, String displayTitle, String coverUrl, String category) {
            this.fileName = fileName;
            this.displayTitle = displayTitle;
            this.sourcePath = sourcePath;
            this.contentUri = contentUri;
            this.coverUrl = coverUrl;
            this.category = category;
        }
    }

    private final ArrayList<LhaEntry> entries = new ArrayList<>();
    private final Map<String, Bitmap> coverCache = new HashMap<>();
    private final Set<String> coverLoading = new HashSet<>();

    private ProgressBar progress;
    private TextView status;
    private ListView list;
    private HorizontalScrollView carouselScroll;
    private LinearLayout carouselStrip;
    private ImageButton refreshIgdbBtn;
    private ImageButton viewModeBtn;
    private ImageButton logsBtn;
    private ImageButton setupBtn;
    private LibraryAdapter adapter;
    private int libraryViewMode = VIEW_MODE_LIST;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        libraryViewMode = getSavedLibraryViewMode();
        buildUi();
        loadLibraryAsync();
    }

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        refreshIgdbBtn = new ImageButton(this);
        refreshIgdbBtn.setImageResource(android.R.drawable.ic_popup_sync);
        refreshIgdbBtn.setBackground(null);
        refreshIgdbBtn.setContentDescription("Refresh IGDB Data");
        refreshIgdbBtn.setOnClickListener(v -> enrichFromIgdbAsync(true));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d));
        refreshLp.rightMargin = (int) (8 * d);
        top.addView(refreshIgdbBtn, refreshLp);

        viewModeBtn = new ImageButton(this);
        viewModeBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        viewModeBtn.setBackground(null);
        viewModeBtn.setContentDescription("Toggle Library View");
        viewModeBtn.setOnClickListener(v -> cycleLibraryViewMode());
        LinearLayout.LayoutParams viewLp = new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d));
        viewLp.rightMargin = (int) (8 * d);
        top.addView(viewModeBtn, viewLp);

        logsBtn = new ImageButton(this);
        logsBtn.setImageResource(android.R.drawable.ic_menu_info_details);
        logsBtn.setBackground(null);
        logsBtn.setContentDescription("Logs");
        logsBtn.setOnClickListener(v -> startActivity(new Intent(this, LogsActivity.class)));
        LinearLayout.LayoutParams logsLp = new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d));
        logsLp.rightMargin = (int) (8 * d);
        top.addView(logsBtn, logsLp);

        setupBtn = new ImageButton(this);
        setupBtn.setImageResource(android.R.drawable.ic_menu_manage);
        setupBtn.setBackground(null);
        setupBtn.setContentDescription("Rerun Setup");
        setupBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, WalkthroughActivity.class);
            i.putExtra(WalkthroughActivity.EXTRA_FORCE_WALKTHROUGH, true);
            startActivity(i);
        });
        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d));
        setupLp.rightMargin = (int) (8 * d);
        top.addView(setupBtn, setupLp);

        TextView title = new TextView(this);
        title.setText("LHA Library");
        title.setTextSize(22f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(title, titleLp);

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText("Loading LHA games...");
        status.setPadding(0, (int) (8 * d), 0, (int) (8 * d));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);

        list = new ListView(this);
        adapter = new LibraryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) return;
            launchEntry(entries.get(position));
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) return true;
            promptEntryActions(entries.get(position));
            return true;
        });

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
        contentHost.addView(list, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        contentHost.addView(carouselScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        applyLibraryViewModeUi();

        root.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(contentHost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int getSavedLibraryViewMode() {
        try {
            int mode = getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE).getInt(PREF_LIBRARY_VIEW_MODE, VIEW_MODE_LIST);
            if (mode < VIEW_MODE_LIST || mode > VIEW_MODE_CAROUSEL) return VIEW_MODE_LIST;
            return mode;
        } catch (Throwable ignored) {
            return VIEW_MODE_LIST;
        }
    }

    private void setSavedLibraryViewMode(int mode) {
        try {
            getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE).edit().putInt(PREF_LIBRARY_VIEW_MODE, mode).apply();
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
        notifyLibraryDataChanged();
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
        if (list != null) {
            list.setDividerHeight(libraryViewMode == VIEW_MODE_COVERS ? dp(2) : dp(6));
            list.setVisibility(libraryViewMode == VIEW_MODE_CAROUSEL ? View.GONE : View.VISIBLE);
        }
        if (carouselScroll != null) {
            carouselScroll.setVisibility(libraryViewMode == VIEW_MODE_CAROUSEL ? View.VISIBLE : View.GONE);
            if (libraryViewMode == VIEW_MODE_CAROUSEL) {
                rebuildCarouselStrip();
            }
        }
    }

    private void notifyLibraryDataChanged() {
        if (adapter != null) adapter.notifyDataSetChanged();
        if (libraryViewMode == VIEW_MODE_CAROUSEL) rebuildCarouselStrip();
    }

    private void rebuildCarouselStrip() {
        if (carouselStrip == null) return;
        carouselStrip.removeAllViews();
        if (entries.isEmpty()) return;

        for (LhaEntry e : entries) {
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

            TextView subtitle = new TextView(this);
            subtitle.setTextSize(12f);
            subtitle.setAlpha(0.85f);
            subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
            card.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            String t = (e.displayTitle == null || e.displayTitle.trim().isEmpty()) ? displayNameFromArchive(e.fileName) : e.displayTitle;
            title.setText(t);
            String sub = e.fileName == null ? "(unknown)" : e.fileName;
            if (isArcadeCategory(e)) sub = sub + "  •  Arcade";
            subtitle.setText(sub);

            bindCover(cover, e.coverUrl);

            card.setOnClickListener(v -> launchEntry(e));
            card.setOnLongClickListener(v -> {
                promptEntryActions(e);
                return true;
            });

            carouselStrip.addView(card, cardLp);
        }
    }

    private void loadLibraryAsync() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            ArrayList<LhaEntry> loaded = new ArrayList<>();
            try {
                SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                String lhaPath = BootstrapPathResolver.resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_LHA_DIR);

                if (lhaPath != null && !lhaPath.trim().isEmpty()) {
                    if (ConfigStorage.isSafJoinedPath(lhaPath)) {
                        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(lhaPath);
                        if (sp != null && sp.treeUri != null && !sp.treeUri.trim().isEmpty()) {
                            DocumentFile root = resolveSafDirectory(sp.treeUri.trim(), sp.relPath);
                            collectSafLhaEntries(root, loaded);
                        }
                    } else if (lhaPath.startsWith("content://")) {
                        DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(lhaPath));
                        collectSafLhaEntries(root, loaded);
                    } else {
                        collectFsLhaEntries(new File(lhaPath), loaded);
                    }
                }
            } catch (Throwable ignored) {
            }

            Collections.sort(loaded, Comparator.comparing(a -> a.displayTitle.toLowerCase(Locale.ROOT)));

            runOnUiThread(() -> {
                entries.clear();
                entries.addAll(loaded);
                notifyLibraryDataChanged();
                progress.setVisibility(View.GONE);
                if (entries.isEmpty()) {
                    status.setText("No LHA files found in configured lha folder");
                    Toast.makeText(this, "No LHA games found", Toast.LENGTH_SHORT).show();
                } else {
                    updateStatusText();
                    enrichFromIgdbAsync(false);
                }
            });
        }).start();
    }

    private void collectFsLhaEntries(File dir, ArrayList<LhaEntry> out) {
        if (dir == null || out == null || !dir.exists() || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (kid == null || !kid.exists() || !kid.isFile()) continue;
            String name = kid.getName();
            if (!isLhaName(name)) continue;
            String title = getCachedIgdbTitle(name);
            if (title == null || title.trim().isEmpty()) {
                title = displayNameFromArchive(name);
            }
            out.add(new LhaEntry(name, kid.getAbsolutePath(), false, title, getCachedIgdbCoverUrl(name), getCategory(name)));
        }
    }

    private void collectSafLhaEntries(DocumentFile dir, ArrayList<LhaEntry> out) {
        if (dir == null || out == null || !dir.exists() || !dir.isDirectory()) return;
        DocumentFile[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable ignored) {
            return;
        }
        if (kids == null) return;
        for (DocumentFile kid : kids) {
            if (kid == null || !kid.exists() || !kid.isFile()) continue;
            String name = kid.getName();
            if (!isLhaName(name)) continue;
            Uri u = kid.getUri();
            if (u == null) continue;
            String title = getCachedIgdbTitle(name);
            if (title == null || title.trim().isEmpty()) {
                title = displayNameFromArchive(name);
            }
            out.add(new LhaEntry(name, u.toString(), true, title, getCachedIgdbCoverUrl(name), getCategory(name)));
        }
    }

    private String categoryKey(String fileName) {
        String n = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        return PREF_LHA_CATEGORY_PREFIX + n;
    }

    private String getCategory(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return null;
            String cat = getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE).getString(categoryKey(fileName), null);
            if (cat == null || cat.trim().isEmpty()) return null;
            return cat.trim().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putCategory(String fileName, String category) {
        try {
            if (fileName == null || fileName.trim().isEmpty() || category == null || category.trim().isEmpty()) return;
            getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .edit()
                .putString(categoryKey(fileName), category.trim().toLowerCase(Locale.ROOT))
                .apply();
        } catch (Throwable ignored) {
        }
    }

    private void clearCategory(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return;
            getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .edit()
                .remove(categoryKey(fileName))
                .apply();
        } catch (Throwable ignored) {
        }
    }

    private int countArcadeEntries() {
        int count = 0;
        for (LhaEntry e : entries) {
            if (e == null || e.category == null) continue;
            if (CATEGORY_ARCADE.equalsIgnoreCase(e.category.trim())) count++;
        }
        return count;
    }

    private void updateStatusText() {
        if (entries.isEmpty()) {
            status.setText("No LHA files found in configured lha folder");
            return;
        }
        int arcade = countArcadeEntries();
        status.setText(entries.size() + " games (Arcade: " + arcade + ") — long-press for actions");
    }

    private DocumentFile resolveSafDirectory(String treeUriString, String relPath) {
        try {
            DocumentFile current = DocumentFile.fromTreeUri(this, Uri.parse(treeUriString));
            if (current == null || !current.isDirectory()) return null;

            String rel = relPath == null ? "" : relPath.trim();
            while (rel.startsWith("/")) rel = rel.substring(1);
            while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (rel.isEmpty()) return current;

            String[] parts = rel.split("/");
            for (String part : parts) {
                if (part == null) continue;
                String p = part.trim();
                if (p.isEmpty()) continue;
                DocumentFile next = current.findFile(p);
                if (next == null || !next.isDirectory()) return null;
                current = next;
            }
            return current;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isLhaName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.endsWith(".lha") || n.endsWith(".lzh");
    }

    private String displayNameFromArchive(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return "(unnamed)";
        String base = fileName.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        String lower = base.toLowerCase(Locale.ROOT);
        int vIdx = lower.indexOf("_v");
        if (vIdx > 0) {
            base = base.substring(0, vIdx);
        }

        base = base.replace('_', ' ').trim();
        return base.isEmpty() ? fileName : base;
    }

    private String igdbLookupFromArchive(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return "";
        String base = fileName.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        int underscore = base.indexOf('_');
        if (underscore > 0) {
            base = base.substring(0, underscore);
        }

        base = base.replace('-', ' ').replace('_', ' ').trim();
        base = base.replaceAll("\\s+", " ");
        return base;
    }

    private String igdbCacheKey(String fileName) {
        String n = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        return PREF_IGDB_TITLE_CACHE_PREFIX + n;
    }

    private String getCachedIgdbTitle(String fileName) {
        try {
            return getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .getString(igdbCacheKey(fileName), null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putCachedIgdbTitle(String fileName, String title) {
        if (fileName == null || title == null) return;
        try {
            getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .edit()
                .putString(igdbCacheKey(fileName), title.trim())
                .apply();
        } catch (Throwable ignored) {
        }
    }

    private String igdbCoverCacheKey(String fileName) {
        String n = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        return PREF_IGDB_COVER_URL_CACHE_PREFIX + n;
    }

    private String getCachedIgdbCoverUrl(String fileName) {
        try {
            return getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .getString(igdbCoverCacheKey(fileName), null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putCachedIgdbCoverUrl(String fileName, String coverUrl) {
        if (fileName == null || coverUrl == null) return;
        try {
            getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .edit()
                .putString(igdbCoverCacheKey(fileName), coverUrl.trim())
                .apply();
        } catch (Throwable ignored) {
        }
    }

    private void logIgdb(String message) {
        try {
            AppLog.append(this, "LHA IGDB " + message);
        } catch (Throwable ignored) {
        }
    }

    private String fullNameLookupFromArchive(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return "";
        String base = fileName.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replace('-', ' ').replace('_', ' ').trim();
        return base.replaceAll("\\s+", " ");
    }

    private ArrayList<String> buildIgdbQueries(LhaEntry entry, String preferredQuery) {
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        addExpandedQuery(uniq, preferredQuery);
        if (entry != null) {
            String shortLookup = igdbLookupFromArchive(entry.fileName);
            addExpandedQuery(uniq, shortLookup);

            String fullLookup = fullNameLookupFromArchive(entry.fileName);
            addExpandedQuery(uniq, fullLookup);

            String displayFallback = displayNameFromArchive(entry.fileName);
            addExpandedQuery(uniq, displayFallback);

            if (entry.displayTitle != null && !entry.displayTitle.trim().isEmpty()) {
                addExpandedQuery(uniq, entry.displayTitle.trim());
            }
        }
        return new ArrayList<>(uniq);
    }

    private void addExpandedQuery(LinkedHashSet<String> uniq, String raw) {
        if (uniq == null || raw == null) return;
        String base = raw.trim();
        if (base.isEmpty()) return;
        uniq.add(base);

        String normalized = OnlineAdfCatalogService.normalizeIgdbSearchTerm(base);
        if (!normalized.isEmpty()) uniq.add(normalized);

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains(" v ") || lower.endsWith(" v") || lower.startsWith("v ")) {
            uniq.add(normalized.replaceAll("(?i)\\bv\\b", "vball"));
            uniq.add(normalized.replaceAll("(?i)\\bv\\b", "v ball"));
            uniq.add(normalized.replaceAll("(?i)\\bv\\b", "v'ball"));
        }
        if (lower.contains("championship") && !lower.startsWith("us ") && !lower.startsWith("u.s.")) {
            uniq.add("US " + normalized);
            uniq.add("U.S. " + normalized);
        }
        if (lower.contains("championship v")) {
            uniq.add("US Championship V'Ball");
            uniq.add("US Championship VBall");
        }
    }

    private OnlineAdfCatalogService.IgdbResult searchIgdbSingle(LhaEntry entry, String query) {
        try {
            SharedPreferences p = getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE);
            String clientId = IgdbCredentialProvider.resolveClientId(p, PREF_IGDB_CLIENT_ID);
            String clientSecret = IgdbCredentialProvider.resolveClientSecret(p, PREF_IGDB_CLIENT_SECRET);
            String accessToken = IgdbCredentialProvider.resolveAccessToken(p, PREF_IGDB_ACCESS_TOKEN);
            if (!isArcadeCategory(entry)) {
                return OnlineAdfCatalogService.searchIgdb(clientId, accessToken, clientSecret, query);
            }

            String arcadeWhere = OnlineAdfCatalogService.buildArcadePlatformsWhere(clientId, accessToken, clientSecret);
            ArrayList<OnlineAdfCatalogService.IgdbResult> candidates =
                OnlineAdfCatalogService.searchIgdbCandidates(clientId, accessToken, clientSecret, query, 12, true, arcadeWhere);
            return pickBestArcadeCandidate(query, candidates);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ArrayList<OnlineAdfCatalogService.IgdbResult> searchIgdbCandidatesForEntry(LhaEntry entry, String query, int limit) {
        ArrayList<OnlineAdfCatalogService.IgdbResult> out = new ArrayList<>();
        try {
            SharedPreferences p = getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE);
            String clientId = IgdbCredentialProvider.resolveClientId(p, PREF_IGDB_CLIENT_ID);
            String clientSecret = IgdbCredentialProvider.resolveClientSecret(p, PREF_IGDB_CLIENT_SECRET);
            String accessToken = IgdbCredentialProvider.resolveAccessToken(p, PREF_IGDB_ACCESS_TOKEN);

            if (clientId == null || clientId.trim().isEmpty()) return out;

            ArrayList<OnlineAdfCatalogService.IgdbResult> candidates;
            if (isArcadeCategory(entry)) {
                String arcadeWhere = OnlineAdfCatalogService.buildArcadePlatformsWhere(clientId, accessToken, clientSecret);
                candidates = OnlineAdfCatalogService.searchIgdbCandidates(clientId, accessToken, clientSecret, query, limit, true, arcadeWhere);
                candidates.sort((a, b) -> Integer.compare(scoreArcadeCandidate(query, b), scoreArcadeCandidate(query, a)));
            } else {
                candidates = OnlineAdfCatalogService.searchIgdbCandidates(clientId, accessToken, clientSecret, query, limit, false);
            }
            if (candidates != null) out.addAll(candidates);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private ArrayList<OnlineAdfCatalogService.IgdbResult> searchIgdbCandidatesWithFallback(LhaEntry entry, String preferredQuery, int totalLimit) {
        ArrayList<OnlineAdfCatalogService.IgdbResult> out = new ArrayList<>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        ArrayList<String> queries = buildIgdbQueries(entry, preferredQuery);
        String mode = isArcadeCategory(entry) ? "arcade-any" : "amiga-only";
        int perQueryLimit = Math.max(3, Math.min(10, totalLimit));

        for (String q : queries) {
            if (q == null || q.trim().isEmpty()) continue;
            String query = q.trim();
            String normalized = OnlineAdfCatalogService.normalizeIgdbSearchTerm(query);
            String file = entry == null || entry.fileName == null ? "(unknown)" : entry.fileName;
            logIgdb("candidate search start file=" + file + " mode=" + mode + " query=\"" + normalized + "\"");

            ArrayList<OnlineAdfCatalogService.IgdbResult> chunk = searchIgdbCandidatesForEntry(entry, query, perQueryLimit);
            if (chunk == null || chunk.isEmpty()) {
                logIgdb("candidate search miss file=" + file + " mode=" + mode + " query=\"" + normalized + "\"");
                continue;
            }

            for (OnlineAdfCatalogService.IgdbResult r : chunk) {
                if (r == null || r.name == null || r.name.trim().isEmpty()) continue;
                String key = r.name.trim().toLowerCase(Locale.ROOT);
                if (seenNames.contains(key)) continue;
                seenNames.add(key);
                out.add(r);
                if (out.size() >= totalLimit) break;
            }

            logIgdb("candidate search hit file=" + file + " mode=" + mode + " query=\"" + normalized + "\" count=" + chunk.size());
            if (out.size() >= totalLimit) break;
        }
        return out;
    }

    private int scoreArcadeCandidate(String query, OnlineAdfCatalogService.IgdbResult candidate) {
        if (candidate == null || candidate.name == null || candidate.name.trim().isEmpty()) return Integer.MIN_VALUE;
        String qNorm = normalizeTitleForMatch(query);
        String[] qTokens = qNorm.isEmpty() ? new String[0] : qNorm.split(" ");
        String nameNorm = normalizeTitleForMatch(candidate.name);
        int score = 0;

        if (!qNorm.isEmpty() && nameNorm.equals(qNorm)) score += 120;
        else if (!qNorm.isEmpty() && nameNorm.startsWith(qNorm)) score += 70;
        else if (!qNorm.isEmpty() && nameNorm.contains(qNorm)) score += 35;

        if (qTokens.length > 0) {
            int overlap = 0;
            for (String t : qTokens) {
                if (!t.isEmpty() && nameNorm.contains(t)) overlap++;
            }
            score += overlap * 10;
        }

        String lower = nameNorm.toLowerCase(Locale.ROOT);
        for (String marker : CONSOLE_MARKERS) {
            if (lower.contains(marker)) score -= 45;
        }
        for (String marker : ARCADE_MARKERS) {
            if (lower.contains(marker)) score += 18;
        }
        return score;
    }

    private OnlineAdfCatalogService.IgdbResult pickBestArcadeCandidate(String query, ArrayList<OnlineAdfCatalogService.IgdbResult> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        String qNorm = normalizeTitleForMatch(query);
        String[] qTokens = qNorm.isEmpty() ? new String[0] : qNorm.split(" ");

        OnlineAdfCatalogService.IgdbResult best = null;
        int bestScore = Integer.MIN_VALUE;

        for (OnlineAdfCatalogService.IgdbResult c : candidates) {
            if (c == null || c.name == null || c.name.trim().isEmpty()) continue;
            String nameNorm = normalizeTitleForMatch(c.name);
            int score = 0;

            if (!qNorm.isEmpty() && nameNorm.equals(qNorm)) score += 120;
            else if (!qNorm.isEmpty() && nameNorm.startsWith(qNorm)) score += 70;
            else if (!qNorm.isEmpty() && nameNorm.contains(qNorm)) score += 35;

            if (qTokens.length > 0) {
                int overlap = 0;
                for (String t : qTokens) {
                    if (!t.isEmpty() && nameNorm.contains(t)) overlap++;
                }
                score += overlap * 10;
            }

            String lower = nameNorm.toLowerCase(Locale.ROOT);
            for (String marker : CONSOLE_MARKERS) {
                if (lower.contains(marker)) score -= 45;
            }
            for (String marker : ARCADE_MARKERS) {
                if (lower.contains(marker)) score += 18;
            }

            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private String normalizeTitleForMatch(String value) {
        if (value == null) return "";
        String s = OnlineAdfCatalogService.normalizeIgdbSearchTerm(value);
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    private IgdbLookupOutcome searchIgdbForEntryWithFallback(LhaEntry entry, String preferredQuery) {
        ArrayList<String> queries = buildIgdbQueries(entry, preferredQuery);
        String mode = isArcadeCategory(entry) ? "arcade-any" : "amiga-only";

        for (String q : queries) {
            if (q == null || q.trim().isEmpty()) continue;
            String query = q.trim();
            String normalized = OnlineAdfCatalogService.normalizeIgdbSearchTerm(query);
            String file = entry == null || entry.fileName == null ? "(unknown)" : entry.fileName;
            logIgdb("search start file=" + file + " mode=" + mode + " query=\"" + normalized + "\"");

            try {
                OnlineAdfCatalogService.IgdbResult result = searchIgdbSingle(entry, query);
                if (result != null && result.name != null && !result.name.trim().isEmpty()) {
                    logIgdb("search hit file=" + file + " mode=" + mode + " query=\"" + normalized + "\" title=\"" + result.name.trim() + "\"");
                    return new IgdbLookupOutcome(query, result);
                }
                logIgdb("search miss file=" + file + " mode=" + mode + " query=\"" + normalized + "\"");
            } catch (Throwable t) {
                logIgdb("search error file=" + file + " mode=" + mode + " query=\"" + normalized + "\" msg=" + (t.getMessage() == null ? "" : t.getMessage()));
            }
        }

        return new IgdbLookupOutcome(preferredQuery, null);
    }

    private boolean isArcadeCategory(LhaEntry entry) {
        return entry != null
            && entry.category != null
            && CATEGORY_ARCADE.equalsIgnoreCase(entry.category.trim());
    }

    private OnlineAdfCatalogService.IgdbResult searchIgdbForEntry(LhaEntry entry, String query) {
        IgdbLookupOutcome out = searchIgdbForEntryWithFallback(entry, query);
        return out == null ? null : out.result;
    }

    private void enrichFromIgdbAsync(boolean forceRefresh) {
        if (entries.isEmpty()) {
            Toast.makeText(this, "No LHA games to refresh", Toast.LENGTH_SHORT).show();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            int updated = 0;
            for (LhaEntry entry : entries) {
                if (entry == null || entry.fileName == null) continue;
                String cached = getCachedIgdbTitle(entry.fileName);
                String cachedCover = getCachedIgdbCoverUrl(entry.fileName);
                if (!forceRefresh
                    && cached != null && !cached.trim().isEmpty()
                    && cachedCover != null && !cachedCover.trim().isEmpty()) {
                    entry.displayTitle = cached.trim();
                    entry.coverUrl = cachedCover.trim();
                    continue;
                }

                String fallback = igdbLookupFromArchive(entry.fileName);
                if (fallback == null || fallback.trim().isEmpty()) {
                    fallback = displayNameFromArchive(entry.fileName);
                }
                IgdbLookupOutcome lookup = searchIgdbForEntryWithFallback(entry, fallback);
                if (lookup != null && lookup.result != null && lookup.result.name != null && !lookup.result.name.trim().isEmpty()) {
                    String next = lookup.result.name.trim();
                    if (!next.equals(entry.displayTitle)) {
                        updated++;
                    }
                    entry.displayTitle = next;
                    putCachedIgdbTitle(entry.fileName, next);
                    OnlineAdfCatalogService.IgdbResult result = lookup.result;
                    if (result != null && result.coverUrl != null && !result.coverUrl.trim().isEmpty()) {
                        entry.coverUrl = result.coverUrl.trim();
                        putCachedIgdbCoverUrl(entry.fileName, entry.coverUrl);
                    }
                }
            }

            Collections.sort(entries, Comparator.comparing(a -> a.displayTitle.toLowerCase(Locale.ROOT)));
            int updatedFinal = updated;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                notifyLibraryDataChanged();
                Toast.makeText(this, "IGDB refresh: updated " + updatedFinal + " / " + entries.size(), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void promptEntryActions(LhaEntry entry) {
        if (entry == null) return;
        String title = entry.displayTitle == null || entry.displayTitle.trim().isEmpty()
            ? displayNameFromArchive(entry.fileName)
            : entry.displayTitle.trim();

        boolean isArcade = entry.category != null && CATEGORY_ARCADE.equalsIgnoreCase(entry.category.trim());
        String[] items = new String[]{
            "Correct IGDB Match",
            "Rename file from IGDB title",
            "Delete this file",
            "Clear cached IGDB title",
            isArcade ? "Clear Arcade Category" : "Mark as Arcade"
        };

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    promptIgdbCorrection(entry);
                    return;
                }
                if (which == 1) {
                    promptRenameFromIgdb(entry);
                    return;
                }
                if (which == 2) {
                    confirmDeleteEntry(entry);
                    return;
                }
                if (which == 4) {
                    if (isArcade) {
                        clearCategory(entry.fileName);
                        entry.category = null;
                        Toast.makeText(this, "Arcade category cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        putCategory(entry.fileName, CATEGORY_ARCADE);
                        entry.category = CATEGORY_ARCADE;
                        Toast.makeText(this, "Marked as Arcade", Toast.LENGTH_SHORT).show();
                    }
                    notifyLibraryDataChanged();
                    updateStatusText();
                    return;
                }
                clearCachedIgdbTitle(entry.fileName);
                entry.displayTitle = displayNameFromArchive(entry.fileName);
                entry.coverUrl = null;
                Collections.sort(entries, Comparator.comparing(a -> a.displayTitle.toLowerCase(Locale.ROOT)));
                notifyLibraryDataChanged();
                Toast.makeText(this, "Cached IGDB title cleared", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void promptRenameFromIgdb(LhaEntry entry) {
        if (entry == null) return;
        EditText input = new EditText(this);
        input.setHint("Game title to search");
        String defaultQuery = igdbLookupFromArchive(entry.fileName);
        if (defaultQuery == null || defaultQuery.trim().isEmpty()) {
            defaultQuery = entry.displayTitle == null ? displayNameFromArchive(entry.fileName) : entry.displayTitle;
        }
        input.setText(defaultQuery);

        new AlertDialog.Builder(this)
            .setTitle("Rename from IGDB")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Search", (d, w) -> {
                String query = input.getText() == null ? "" : input.getText().toString().trim();
                if (query.isEmpty()) return;
                progress.setVisibility(View.VISIBLE);
                new Thread(() -> {
                    IgdbLookupOutcome lookup = searchIgdbForEntryWithFallback(entry, query);
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        if (lookup == null || lookup.result == null || lookup.result.name == null || lookup.result.name.trim().isEmpty()) {
                            Toast.makeText(this, "No IGDB match found", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        String extension = extensionForFileName(entry.fileName);
                        String proposedBase = sanitizeForFileName(lookup.result.name.trim());
                        if (proposedBase.isEmpty()) {
                            Toast.makeText(this, "Invalid IGDB title for rename", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String proposed = proposedBase + extension;
                        if (entry.fileName != null && entry.fileName.equalsIgnoreCase(proposed)) {
                            Toast.makeText(this, "Filename already matches", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        new AlertDialog.Builder(this)
                            .setTitle("Confirm rename")
                            .setMessage("Rename:\n" + (entry.fileName == null ? "(unknown)" : entry.fileName) + "\n\nTo:\n" + proposed)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Rename", (dd, ww) -> renameEntryTo(entry, proposed, lookup.result))
                            .show();
                    });
                }).start();
            })
            .show();
    }

    private String extensionForFileName(String fileName) {
        if (fileName == null) return ".lha";
        String n = fileName.trim();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot >= n.length() - 1) return ".lha";
        return n.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String sanitizeForFileName(String title) {
        if (title == null) return "";
        String s = title.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private void renameEntryTo(LhaEntry entry, String newFileName, OnlineAdfCatalogService.IgdbResult igdbResult) {
        if (entry == null || newFileName == null || newFileName.trim().isEmpty()) return;
        String oldFileName = entry.fileName;
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean ok = false;
            String newPath = null;

            try {
                if (entry.contentUri) {
                    Uri uri = Uri.parse(entry.sourcePath);
                    DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
                    if (doc != null && doc.exists()) {
                        ok = doc.renameTo(newFileName);
                        if (ok) {
                            Uri next = doc.getUri();
                            if (next != null) newPath = next.toString();
                            if (newPath == null || newPath.trim().isEmpty()) newPath = entry.sourcePath;
                        }
                    }
                } else {
                    File oldFile = new File(entry.sourcePath);
                    File parent = oldFile.getParentFile();
                    File newFile = new File(parent, newFileName);
                    if (!newFile.exists()) {
                        ok = oldFile.exists() && oldFile.renameTo(newFile);
                        if (ok) newPath = newFile.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {
                ok = false;
            }

            boolean finalOk = ok;
            String finalNewPath = newPath;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (!finalOk) {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
                    return;
                }

                clearCachedIgdbTitle(oldFileName);
                clearCategory(oldFileName);

                entry.displayTitle = igdbResult != null && igdbResult.name != null && !igdbResult.name.trim().isEmpty()
                    ? igdbResult.name.trim()
                    : entry.displayTitle;
                entry.coverUrl = igdbResult != null && igdbResult.coverUrl != null && !igdbResult.coverUrl.trim().isEmpty()
                    ? igdbResult.coverUrl.trim()
                    : entry.coverUrl;

                LhaEntry renamed = new LhaEntry(
                    newFileName,
                    finalNewPath == null ? entry.sourcePath : finalNewPath,
                    entry.contentUri,
                    entry.displayTitle,
                    entry.coverUrl,
                    entry.category
                );
                int idx = entries.indexOf(entry);
                if (idx >= 0) {
                    entries.set(idx, renamed);
                }

                if (renamed.displayTitle != null && !renamed.displayTitle.trim().isEmpty()) {
                    putCachedIgdbTitle(renamed.fileName, renamed.displayTitle);
                }
                if (renamed.coverUrl != null && !renamed.coverUrl.trim().isEmpty()) {
                    putCachedIgdbCoverUrl(renamed.fileName, renamed.coverUrl);
                }
                if (renamed.category != null && !renamed.category.trim().isEmpty()) {
                    putCategory(renamed.fileName, renamed.category);
                }

                Collections.sort(entries, Comparator.comparing(a -> a.displayTitle.toLowerCase(Locale.ROOT)));
                notifyLibraryDataChanged();
                updateStatusText();
                Toast.makeText(this, "Renamed to " + newFileName, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void clearCachedIgdbTitle(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return;
        try {
            getSharedPreferences(PREFS_BOOTSTRAP, MODE_PRIVATE)
                .edit()
                .remove(igdbCacheKey(fileName))
                .remove(igdbCoverCacheKey(fileName))
                .apply();
        } catch (Throwable ignored) {
        }
    }

    private void confirmDeleteEntry(LhaEntry entry) {
        if (entry == null) return;
        String title = entry.fileName == null ? "this file" : entry.fileName;
        new AlertDialog.Builder(this)
            .setTitle("Delete LHA")
            .setMessage("Delete " + title + "?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Delete", (d, w) -> {
                boolean ok = deleteEntryFile(entry);
                if (!ok) {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
                    return;
                }
                clearCachedIgdbTitle(entry.fileName);
                clearCategory(entry.fileName);
                entries.remove(entry);
                notifyLibraryDataChanged();
                updateStatusText();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private boolean deleteEntryFile(LhaEntry entry) {
        if (entry == null || entry.sourcePath == null || entry.sourcePath.trim().isEmpty()) return false;
        try {
            if (entry.contentUri) {
                Uri uri = Uri.parse(entry.sourcePath.trim());
                DocumentFile df = DocumentFile.fromSingleUri(this, uri);
                if (df != null && df.exists()) {
                    return df.delete();
                }
                return false;
            }

            File f = new File(entry.sourcePath.trim());
            return f.exists() && f.isFile() && f.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void promptIgdbCorrection(LhaEntry entry) {
        if (entry == null) return;
        EditText input = new EditText(this);
        input.setHint("Game title to search");
        String defaultQuery = igdbLookupFromArchive(entry.fileName);
        if (defaultQuery == null || defaultQuery.trim().isEmpty()) {
            defaultQuery = entry.displayTitle == null ? displayNameFromArchive(entry.fileName) : entry.displayTitle;
        }
        input.setText(defaultQuery);

        new AlertDialog.Builder(this)
            .setTitle("Correct IGDB Match")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Apply", (d, w) -> {
                String query = input.getText() == null ? "" : input.getText().toString().trim();
                if (query.isEmpty()) return;
                progress.setVisibility(View.VISIBLE);
                new Thread(() -> {
                    ArrayList<OnlineAdfCatalogService.IgdbResult> matches = searchIgdbCandidatesWithFallback(entry, query, 8);
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        if (matches == null || matches.isEmpty()) {
                            Toast.makeText(this, "No IGDB match found", Toast.LENGTH_SHORT).show();
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
                            .setItems(labels, (dd, which) -> {
                                if (which < 0 || which >= matches.size()) return;
                                OnlineAdfCatalogService.IgdbResult chosen = matches.get(which);
                                if (chosen == null || chosen.name == null || chosen.name.trim().isEmpty()) return;

                                entry.displayTitle = chosen.name.trim();
                                putCachedIgdbTitle(entry.fileName, entry.displayTitle);
                                if (chosen.coverUrl != null && !chosen.coverUrl.trim().isEmpty()) {
                                    entry.coverUrl = chosen.coverUrl.trim();
                                    putCachedIgdbCoverUrl(entry.fileName, entry.coverUrl);
                                }

                                Collections.sort(entries, Comparator.comparing(a -> a.displayTitle.toLowerCase(Locale.ROOT)));
                                notifyLibraryDataChanged();
                                Toast.makeText(this, "IGDB title updated", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    });
                }).start();
            })
            .show();
    }

    private static final class RowHolder {
        int mode;
        ImageView cover;
        TextView title;
        TextView subtitle;
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int mode = libraryViewMode == VIEW_MODE_COVERS ? VIEW_MODE_COVERS : VIEW_MODE_LIST;
            RowHolder holder;
            if (convertView == null || !(convertView.getTag() instanceof RowHolder) || ((RowHolder) convertView.getTag()).mode != mode) {
                boolean coversMode = mode == VIEW_MODE_COVERS;
                LinearLayout row = new LinearLayout(LhaLibraryActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(10), coversMode ? dp(6) : dp(8), dp(10), coversMode ? dp(6) : dp(8));

                ImageView cover = new ImageView(LhaLibraryActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    coversMode ? dp(96) : dp(72),
                    coversMode ? dp(128) : dp(96)
                );
                clp.rightMargin = dp(10);
                row.addView(cover, clp);

                LinearLayout textWrap = new LinearLayout(LhaLibraryActivity.this);
                textWrap.setOrientation(LinearLayout.VERTICAL);
                textWrap.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView title = new TextView(LhaLibraryActivity.this);
                title.setTextSize(coversMode ? 17f : 16f);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setMaxLines(coversMode ? 2 : 1);
                textWrap.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                TextView subtitle = new TextView(LhaLibraryActivity.this);
                subtitle.setTextSize(12f);
                subtitle.setAlpha(0.85f);
                subtitle.setMaxLines(coversMode ? 3 : 1);
                textWrap.addView(subtitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                row.addView(textWrap);

                holder = new RowHolder();
                holder.mode = mode;
                holder.cover = cover;
                holder.title = title;
                holder.subtitle = subtitle;
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            LhaEntry e = entries.get(position);
            holder.title.setText(e.displayTitle == null || e.displayTitle.trim().isEmpty() ? displayNameFromArchive(e.fileName) : e.displayTitle);
            String subtitle = e.fileName == null ? "(unknown)" : e.fileName;
            if (e.category != null && CATEGORY_ARCADE.equalsIgnoreCase(e.category.trim())) {
                subtitle = subtitle + "  •  Arcade";
            }
            holder.subtitle.setText(subtitle);
            bindCover(holder.cover, e.coverUrl);
            return convertView;
        }
    }

    private void bindCover(ImageView iv, String url) {
        if (iv == null) return;
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void launchEntry(LhaEntry entry) {
        if (entry == null || entry.sourcePath == null || entry.sourcePath.trim().isEmpty()) return;

        String source = entry.sourcePath.trim();
        String localGamePath = entry.contentUri ? materializeWHDLoadUriIfNeeded(source) : source;

        SharedPreferences whdPrefs = getSharedPreferences(PREFS_WHDBOOTER, MODE_PRIVATE);
        boolean useJst = whdPrefs.getBoolean(PREF_USE_JST, false);
        boolean writeCache = whdPrefs.getBoolean(PREF_WRITE_CACHE, true);
        boolean showSplash = whdPrefs.getBoolean(PREF_SHOW_SPLASH, true);
        boolean quitOnExit = whdPrefs.getBoolean(PREF_QUIT_ON_EXIT, true);

        Intent i = new Intent(this, AmiberryActivity.class);
        i.putExtra(AmiberryActivity.EXTRA_QS_MODEL, "A1200");
        i.putExtra(AmiberryActivity.EXTRA_MACHINE_PRESET, "A1200");
        i.putExtra(AmiberryActivity.EXTRA_WHDLOAD_FILE, localGamePath);
        i.putExtra(AmiberryActivity.EXTRA_WHD_USE_JST, useJst);
        i.putExtra(AmiberryActivity.EXTRA_WHD_WRITE_CACHE, writeCache);
        i.putExtra(AmiberryActivity.EXTRA_WHD_SHOW_SPLASH, showSplash);
        i.putExtra(AmiberryActivity.EXTRA_WHD_QUIT_ON_EXIT, quitOnExit);

        final boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            i.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
        }

        startActivity(i);
        finish();
    }

    private String materializeWHDLoadUriIfNeeded(String whdPathOrUri) {
        if (whdPathOrUri == null || whdPathOrUri.trim().isEmpty()) return whdPathOrUri;
        String p = whdPathOrUri.trim();
        if (!p.startsWith("content://")) return p;

        try {
            ContentResolver cr = getContentResolver();
            Uri uri = Uri.parse(p);

            String fileName = basenameFromMaybeEncodedDocId(uri.getLastPathSegment());
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".lha") && !fileName.toLowerCase(Locale.ROOT).endsWith(".lzh")) {
                fileName = fileName + ".lha";
            }

            File outDir = new File(getCacheDir(), "whdload-import");
            ensureDir(outDir);
            File outFile = new File(outDir, fileName);

            try (InputStream in = cr.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile, false)) {
                if (in == null) return p;
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            return p;
        }
    }

    private String basenameFromMaybeEncodedDocId(String raw) {
        if (raw == null) return "whdload.lha";
        String s = Uri.decode(raw);
        if (s == null || s.trim().isEmpty()) return "whdload.lha";
        s = s.trim();
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) s = s.substring(colon + 1);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < s.length()) s = s.substring(slash + 1);
        if (s.isEmpty()) s = "whdload.lha";
        return s;
    }

    private void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();
    }
}
