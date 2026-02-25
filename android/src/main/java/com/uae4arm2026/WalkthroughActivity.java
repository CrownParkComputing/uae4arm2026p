package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;

public class WalkthroughActivity extends Activity {

	private static final String TAG = "WalkthroughActivity";

	public static final String EXTRA_SKIP_WALKTHROUGH = "com.uae4arm2026.extra.SKIP_WALKTHROUGH";
	public static final String EXTRA_FORCE_WALKTHROUGH = "com.uae4arm2026.extra.FORCE_WALKTHROUGH";
	public static final String PREF_WALKTHROUGH_COMPLETED = "walkthrough_completed";
	public static final String PREF_WALKTHROUGH_DISABLED = "walkthrough_disabled";

	private LinearLayout mPageContainer;
	private ProgressBar mProgressBar;
	private TextView mPageTitle;
	private TextView mPageDescription;
	private Button mBtnBack;
	private Button mBtnNext;
	private Button mBtnSkip;

	private int mCurrentPage = 0;
	private static final int PAGE_WELCOME = 0;
	private static final int PAGE_PATHS = 1;
	private static final int PAGE_MODEL = 2;
	private static final int PAGE_KICKSTART = 3;
	private static final int PAGE_DISKS = 4;
	private static final int PAGE_COMPLETE = 5;
	private static final int PAGE_COUNT = 6;

	private static final int REQ_PICK_PARENT = 7099;
	private static final int REQ_PICK_PATH = 7100;

	private String mSelectedModel = "A500";
	private String mParentTreeUri = null;
	private TextView mPathsStatusText;
	private String mPendingPathKey = null;
	private String mPendingPathLabel = null;

	private static class PathTarget {
		final String prefKey;
		final String label;
		final String defaultSubfolder;

		PathTarget(String prefKey, String label, String defaultSubfolder) {
			this.prefKey = prefKey;
			this.label = label;
			this.defaultSubfolder = defaultSubfolder;
		}
	}

	private static final PathTarget[] PATH_TARGETS = new PathTarget[] {
		new PathTarget(UaeOptionKeys.UAE_PATH_CONF_DIR, "Configs", "conf"),
		new PathTarget(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, "Kickstarts", "kickstarts"),
		new PathTarget(UaeOptionKeys.UAE_PATH_ROMS_DIR, "ROMs", "roms"),
		new PathTarget(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, "Floppies / ADF", "floppies"),
		new PathTarget(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, "Harddrives / HDF", "harddrives"),
		new PathTarget(UaeOptionKeys.UAE_PATH_CDROMS_DIR, "CD-ROMs", "cdroms"),
		new PathTarget(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, "Savestates", "savestates"),
		new PathTarget(UaeOptionKeys.UAE_PATH_SCREENS_DIR, "Screenshots", "screenshots"),
		new PathTarget(UaeOptionKeys.UAE_PATH_LHA_DIR, "LHA / WHDLoad archives", "lha"),
		new PathTarget(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, "WHDBoot", "whdboot")
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

		setContentView(R.layout.activity_walkthrough);

		mPageContainer = findViewById(R.id.walkthrough_page_container);
		mProgressBar = findViewById(R.id.walkthrough_progress);
		mPageTitle = findViewById(R.id.walkthrough_title);
		mPageDescription = findViewById(R.id.walkthrough_description);
		mBtnBack = findViewById(R.id.walkthrough_btn_back);
		mBtnNext = findViewById(R.id.walkthrough_btn_next);
		mBtnSkip = findViewById(R.id.walkthrough_btn_skip);

		mBtnBack.setOnClickListener(v -> goToPage(mCurrentPage - 1));
		mBtnNext.setOnClickListener(v -> goToPage(mCurrentPage + 1));
		mBtnSkip.setOnClickListener(v -> skipWalkthrough());

		boolean skip = getIntent().getBooleanExtra(EXTRA_SKIP_WALKTHROUGH, false);
		boolean force = getIntent().getBooleanExtra(EXTRA_FORCE_WALKTHROUGH, false);
		if (skip || (!force && (isWalkthroughCompleted() || isWalkthroughDisabled()))) {
			startBootstrap();
			finish();
			return;
		}

		mProgressBar.setMax(PAGE_COUNT - 1);
		goToPage(PAGE_WELCOME);
	}

	private boolean isWalkthroughCompleted() {
		SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		return p.getBoolean(PREF_WALKTHROUGH_COMPLETED, false);
	}

	private boolean isWalkthroughDisabled() {
		SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		return p.getBoolean(PREF_WALKTHROUGH_DISABLED, false);
	}

	private void markWalkthroughCompleted() {
		SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		p.edit().putBoolean(PREF_WALKTHROUGH_COMPLETED, true).apply();
	}

	private void goToPage(int page) {
		if (page < 0) page = 0;

		if (mCurrentPage == PAGE_PATHS && page > PAGE_PATHS && !hasParentTreeConfigured()) {
			Toast.makeText(this, "Please select a parent folder first", Toast.LENGTH_SHORT).show();
			return;
		}

		if (page >= PAGE_COUNT) {
			markWalkthroughCompleted();
			startBootstrap();
			finish();
			return;
		}

		mCurrentPage = page;
		mProgressBar.setProgress(page);
		mPageContainer.removeAllViews();

		switch (page) {
			case PAGE_WELCOME:
				showWelcomePage();
				break;
			case PAGE_MODEL:
				showModelPage();
				break;
			case PAGE_KICKSTART:
				showKickstartPage();
				break;
			case PAGE_DISKS:
				showDisksPage();
				break;
			case PAGE_PATHS:
				showPathsPage();
				break;
			case PAGE_COMPLETE:
				showCompletePage();
				break;
		}

		updateNavigationButtons();
	}

	private void showWelcomePage() {
		mPageTitle.setText(R.string.walkthrough_welcome_title);
		mPageDescription.setText(R.string.walkthrough_welcome_desc);
		mBtnSkip.setVisibility(View.VISIBLE);
	}

	private void showModelPage() {
		mPageTitle.setText(R.string.walkthrough_model_title);
		mPageDescription.setText(R.string.walkthrough_model_desc);

		RadioGroup rg = new RadioGroup(this);
		rg.setOrientation(RadioGroup.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		rg.setPadding(pad, pad, pad, pad);

		String[] models = {"A500", "A1200", "A4000", "CD32", "CDTV"};
		int[] modelIds = {R.id.walkthrough_model_a500, R.id.walkthrough_model_a1200, R.id.walkthrough_model_a4000, R.id.walkthrough_model_cd32, R.id.walkthrough_model_cdtv};
		String[] descriptions = {
			"Amiga 500 (OCS/ECS, 512KB Chip) - Classic games",
			"Amiga 1200 (AGA, 2MB Chip) - Advanced games",
			"Amiga 4000 (AGA, faster CPU) - High-end",
			"CD32 - CD-based games",
			"CDTV - Multimedia/CD"
		};

		for (int i = 0; i < models.length; i++) {
			RadioButton rb = new RadioButton(this);
			rb.setId(modelIds[i]);
			rb.setText(models[i] + "\n" + descriptions[i]);
			rb.setTextSize(14);
			rb.setChecked(models[i].equals(mSelectedModel));
			rb.setTag(models[i]);
			rg.addView(rb);
		}

		rg.setOnCheckedChangeListener((group, checkedId) -> {
			RadioButton rb = findViewById(checkedId);
			if (rb != null && rb.getTag() != null) {
				mSelectedModel = (String) rb.getTag();
			}
		});

		mPageContainer.addView(rg);
		mBtnSkip.setVisibility(View.VISIBLE);
	}

	private void showKickstartPage() {
		mPageTitle.setText(R.string.walkthrough_kickstart_title);
		mPageDescription.setText(R.string.walkthrough_kickstart_desc);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, pad);

		boolean hasKickstarts = hasKickstartFilesSaf();
		boolean hasAros = hasArosRomsSaf();

		TextView statusText = new TextView(this);
		statusText.setTextSize(14);
		if (hasKickstarts) {
			statusText.setText("✅ Kickstart ROMs found!\n\nYour kickstarts are ready to use.");
			statusText.setTextColor(0xFF4CAF50);
		} else if (hasAros) {
			statusText.setText("⚠️ AROS ROMs available (built-in)\n\nFor best compatibility, you may want to add real Kickstart ROMs.");
			statusText.setTextColor(0xFFFF9800);
		} else {
			statusText.setText("❌ No Kickstart ROMs found\n\nYou need a Kickstart ROM to run the Amiga.");
			statusText.setTextColor(0xFFF44336);
		}
		layout.addView(statusText);

		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		String kickstartsPath = prefs.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null);
		String romsPath = prefs.getString(UaeOptionKeys.UAE_PATH_ROMS_DIR, null);

		TextView pathText = new TextView(this);
		pathText.setTextSize(12);
		pathText.setPadding(0, pad, 0, 0);
		String pathDisplay = "Scanning:\n";
		if (kickstartsPath != null && !kickstartsPath.trim().isEmpty()) {
			pathDisplay += "• " + formatSafPath(kickstartsPath) + "\n";
		}
		if (romsPath != null && !romsPath.trim().isEmpty()) {
			pathDisplay += "• " + formatSafPath(romsPath);
		}
		pathText.setText(pathDisplay);
		layout.addView(pathText);

		TextView helpText = new TextView(this);
		helpText.setTextSize(12);
		helpText.setPadding(0, pad, 0, 0);
		helpText.setText("Common filenames:\n" +
			"• kick.rom (any Kickstart)\n" +
			"• kick34005.A500 (Kickstart 1.3)\n" +
			"• kick40063.A600 (Kickstart 3.1)\n" +
			"• kick40068.A1200 (Kickstart 3.1 A1200)\n" +
			"• *.zip (compressed ROMs)\n\n" +
			"You can legally obtain Kickstart ROMs from:\n" +
			"• Amiga Forever (www.amigaforever.com)\n" +
			"• Cloanto's Amiga products");
		layout.addView(helpText);

		mPageContainer.addView(layout);
		mBtnSkip.setVisibility(View.VISIBLE);
	}

	private String formatSafPath(String safPath) {
		if (safPath == null) return "(not set)";
		if (ConfigStorage.isSafJoinedPath(safPath)) {
			try {
				ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(safPath);
				if (sp != null && sp.treeUri != null) {
					String label = getSafFolderLabel(sp.treeUri);
					String relPath = sp.relPath;
					if (relPath != null && !relPath.isEmpty()) {
						return label + "/" + relPath;
					}
					return label;
				}
			} catch (Throwable ignored) {
			}
		}
		return safPath;
	}

	private boolean hasKickstartFilesSaf() {
		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		String[] pathKeys = {UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, UaeOptionKeys.UAE_PATH_ROMS_DIR};

		for (String key : pathKeys) {
			String path = prefs.getString(key, null);
			if (path != null && !path.trim().isEmpty()) {
				if (scanSafFolderForKickstarts(path)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean scanSafFolderForKickstarts(String safPath) {
		if (safPath == null || safPath.trim().isEmpty()) return false;

		try {
			Uri treeUri;
			String subpath = "";

			if (ConfigStorage.isSafJoinedPath(safPath)) {
				ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(safPath);
				if (sp == null || sp.treeUri == null) return false;
				treeUri = Uri.parse(sp.treeUri);
				subpath = sp.relPath != null ? sp.relPath : "";
			} else if (safPath.startsWith("content://")) {
				treeUri = Uri.parse(safPath);
			} else {
				return false;
			}

			DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
			if (tree == null || !tree.exists()) return false;

			DocumentFile searchDir = tree;
			if (subpath != null && !subpath.isEmpty() && !subpath.equals("/")) {
				String[] parts = subpath.split("/");
				for (String part : parts) {
					if (part.isEmpty()) continue;
					DocumentFile child = findChild(tree, part);
					if (child != null) {
						tree = child;
					}
				}
				searchDir = tree;
			}

			return scanDocumentFileForKickstarts(searchDir);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private DocumentFile findChild(DocumentFile parent, String name) {
		if (parent == null || name == null) return null;
		try {
			DocumentFile[] children = parent.listFiles();
			if (children == null) return null;
			for (DocumentFile child : children) {
				if (name.equals(child.getName())) {
					return child;
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private boolean scanDocumentFileForKickstarts(DocumentFile dir) {
		if (dir == null || !dir.exists() || !dir.isDirectory()) return false;

		try {
			DocumentFile[] files = dir.listFiles();
			if (files == null) return false;

			for (DocumentFile f : files) {
				String name = f.getName();
				if (name == null) continue;
				String lower = name.toLowerCase();

				if (lower.contains("kick") || lower.contains("rom")) {
					if (lower.endsWith(".rom") || lower.endsWith(".bin") || lower.endsWith(".zip")) {
						return true;
					}
				}

				if (lower.startsWith("kick") && (lower.endsWith(".rom") || lower.endsWith(".bin") || lower.endsWith(".zip"))) {
					return true;
				}
			}
		} catch (Throwable ignored) {
		}

		return false;
	}

	private boolean hasArosRomsSaf() {
		try {
			String[] assets = getAssets().list("");
			if (assets != null) {
				for (String a : assets) {
					if (a.contains("aros")) return true;
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private void showDisksPage() {
		mPageTitle.setText(R.string.walkthrough_disks_title);
		mPageDescription.setText(R.string.walkthrough_disks_desc);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, pad);

		File disksDir = getDisksDir();
		boolean hasDisks = hasDiskImages(disksDir);

		TextView statusText = new TextView(this);
		statusText.setTextSize(14);
		if (hasDisks) {
			statusText.setText("✅ Disk images found!\n\nYou can load ADF, HDF, and WHDLoad games.");
			statusText.setTextColor(0xFF4CAF50);
		} else {
			statusText.setText("ℹ️ No disk images found yet\n\nYou can add disks later from the launcher.");
			statusText.setTextColor(0xFF2196F3);
		}
		layout.addView(statusText);

		TextView helpText = new TextView(this);
		helpText.setTextSize(12);
		helpText.setPadding(0, pad, 0, 0);
		helpText.setText("Supported formats:\n" +
			"• ADF - Amiga Disk File (floppy)\n" +
			"• ADZ - Compressed ADF\n" +
			"• DMS - DiskMasher compressed\n" +
			"• HDF - Hard Disk image\n" +
			"• LHA/LZH - WHDLoad archives\n" +
			"• ZIP - Compressed disk images\n\n" +
			"Disk images should be placed in:\n" + disksDir.getAbsolutePath());
		layout.addView(helpText);

		mPageContainer.addView(layout);
		mBtnSkip.setVisibility(View.VISIBLE);
	}

	private void showPathsPage() {
		mPageTitle.setText(R.string.walkthrough_paths_title);
		mPageDescription.setText(R.string.walkthrough_paths_desc);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, pad);

		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		mParentTreeUri = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);

		mPathsStatusText = new TextView(this);
		mPathsStatusText.setTextSize(14);
		updatePathsStatusText();
		layout.addView(mPathsStatusText);

		Button btnPickFolder = new Button(this);
		btnPickFolder.setText("Select Parent Folder");
		btnPickFolder.setOnClickListener(v -> pickSafParentFolder());
		layout.addView(btnPickFolder);

		TextView perTypeTitle = new TextView(this);
		perTypeTitle.setTextSize(14);
		perTypeTitle.setPadding(0, pad, 0, 0);
		perTypeTitle.setText("Select folder for each content type:");
		layout.addView(perTypeTitle);

		for (PathTarget target : PATH_TARGETS) {
			addPathTargetRow(layout, target, pad);
		}

		TextView helpText = new TextView(this);
		helpText.setTextSize(12);
		helpText.setPadding(0, pad, 0, 0);
		helpText.setText("Select a parent folder where your Amiga files are stored.\n\n" +
			"The emulator will look for these subfolders:\n" +
			"• roms/ - Kickstart ROMs\n" +
			"• disks/ - Floppy/HDD images\n" +
			"• conf/ - Configuration files\n" +
			"• savestates/ - Save states\n" +
			"• screenshots/ - Screenshots\n" +
			"• whdboot/ - WHDLoad boot files\n\n" +
			"If the folder doesn't exist, it will be created.");
		layout.addView(helpText);

		mPageContainer.addView(layout);
		mBtnSkip.setVisibility(View.VISIBLE);

		if (mParentTreeUri == null || mParentTreeUri.trim().isEmpty()) {
			new Handler(Looper.getMainLooper()).post(() -> new AlertDialog.Builder(this)
				.setTitle("Parent folder required")
				.setMessage("Please select a parent folder first. Default folders will be created inside it.")
				.setPositiveButton("Select Folder", (d, w) -> pickSafParentFolder())
				.setNegativeButton("Later", null)
				.show());
		}
	}

	private void addPathTargetRow(LinearLayout parent, PathTarget target, int pad) {
		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		String current = prefs.getString(target.prefKey, null);

		TextView label = new TextView(this);
		label.setTextSize(13);
		label.setPadding(0, pad, 0, 0);
		String shown = (current == null || current.trim().isEmpty()) ? "(not set)" : formatSafPath(current);
		label.setText("• " + target.label + ":\n" + shown);
		parent.addView(label);

		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);

		Button btnUseDefault = new Button(this);
		btnUseDefault.setText("Use Parent Default");
		btnUseDefault.setOnClickListener(v -> applyParentDefaultPath(target));
		row.addView(btnUseDefault);

		Button btnPickCustom = new Button(this);
		btnPickCustom.setText("Pick Folder");
		btnPickCustom.setOnClickListener(v -> pickPathFolder(target));
		row.addView(btnPickCustom);

		parent.addView(row);
	}

	private void updatePathsStatusText() {
		if (mPathsStatusText == null) return;

		if (mParentTreeUri != null && !mParentTreeUri.trim().isEmpty()) {
			String label = getSafFolderLabel(mParentTreeUri);
			mPathsStatusText.setText("✅ Parent folder selected:\n" + label);
			mPathsStatusText.setTextColor(0xFF4CAF50);
		} else {
			mPathsStatusText.setText("❌ No parent folder selected.\n\nPlease select a folder to store your Amiga files.");
			mPathsStatusText.setTextColor(0xFFF44336);
		}
	}

	private String getSafFolderLabel(String treeUriString) {
		if (treeUriString == null) return "Selected folder";
		try {
			Uri u = Uri.parse(treeUriString.trim());
			DocumentFile df = DocumentFile.fromTreeUri(this, u);
			String name = (df != null) ? df.getName() : null;
			if (name != null && !name.trim().isEmpty()) return name.trim();
		} catch (Throwable ignored) {
		}
		try {
			Uri u = Uri.parse(treeUriString.trim());
			String docId = DocumentsContract.getTreeDocumentId(u);
			if (docId != null) {
				return docId.replace("primary:", "Internal:");
			}
		} catch (Throwable ignored) {
		}
		return "Selected folder";
	}

	private void pickSafParentFolder() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
		}
		startActivityForResult(intent, REQ_PICK_PARENT);
	}

	private void pickPathFolder(PathTarget target) {
		mPendingPathKey = target.prefKey;
		mPendingPathLabel = target.label;

		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
		}
		startActivityForResult(intent, REQ_PICK_PATH);
	}

	private void applyParentDefaultPath(PathTarget target) {
		if (mParentTreeUri == null || mParentTreeUri.trim().isEmpty()) {
			Toast.makeText(this, "Select parent folder first", Toast.LENGTH_SHORT).show();
			pickSafParentFolder();
			return;
		}

		String joined = buildJoinedPath(mParentTreeUri, target.defaultSubfolder + "/");
		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		prefs.edit().putString(target.prefKey, joined).apply();
		ensureDefaultFoldersInParent();
		Toast.makeText(this, target.label + " path set to parent default", Toast.LENGTH_SHORT).show();
		goToPage(PAGE_PATHS);
	}

	private String buildJoinedPath(String treeUri, String rel) {
		String path = rel == null ? "" : rel;
		if (!path.isEmpty() && !path.endsWith("/")) {
			path = path + "/";
		}
		return treeUri + "::/" + path;
	}

	private boolean hasParentTreeConfigured() {
		if (mParentTreeUri != null && !mParentTreeUri.trim().isEmpty()) return true;
		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		String parent = prefs.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
		return parent != null && !parent.trim().isEmpty();
	}

	private void ensureDefaultFoldersInParent() {
		if (mParentTreeUri == null || mParentTreeUri.trim().isEmpty()) return;
		try {
			DocumentFile parent = DocumentFile.fromTreeUri(this, Uri.parse(mParentTreeUri));
			if (parent == null || !parent.exists()) return;
			for (PathTarget target : PATH_TARGETS) {
				ensureSubfolderExists(parent, target.defaultSubfolder);
			}
		} catch (Throwable ignored) {
		}
	}

	private void ensureSubfolderExists(DocumentFile parent, String subfolder) {
		if (parent == null || subfolder == null || subfolder.trim().isEmpty()) return;
		DocumentFile existing = findChild(parent, subfolder);
		if (existing == null || !existing.exists()) {
			try {
				parent.createDirectory(subfolder);
			} catch (Throwable ignored) {
			}
		}
	}

	private void takePersistablePermissions(Uri uri, Intent data) {
		if (uri == null) return;
		try {
			int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
			try {
				getContentResolver().takePersistableUriPermission(uri, rw);
			} catch (SecurityException ignored) {
				int flags = (data != null ? data.getFlags() : 0) & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
				flags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				getContentResolver().takePersistableUriPermission(uri, flags);
			}
		} catch (Throwable ignored) {
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != RESULT_OK || data == null) return;

		if (requestCode == REQ_PICK_PARENT) {
			Uri uri = data.getData();
			if (uri == null) return;

			takePersistablePermissions(uri, data);

			mParentTreeUri = uri.toString();
			saveParentPathAndAutoFill(uri);
			ensureDefaultFoldersInParent();
			updatePathsStatusText();
			Toast.makeText(this, "Parent folder set", Toast.LENGTH_SHORT).show();
			if (mCurrentPage == PAGE_PATHS) goToPage(PAGE_PATHS);
			return;
		}

		if (requestCode == REQ_PICK_PATH) {
			Uri uri = data.getData();
			if (uri == null || mPendingPathKey == null) return;

			takePersistablePermissions(uri, data);

			String joined = buildJoinedPath(uri.toString(), "");
			SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
			prefs.edit().putString(mPendingPathKey, joined).apply();

			String label = mPendingPathLabel != null ? mPendingPathLabel : "Path";
			Toast.makeText(this, label + " folder selected", Toast.LENGTH_SHORT).show();

			mPendingPathKey = null;
			mPendingPathLabel = null;
			if (mCurrentPage == PAGE_PATHS) goToPage(PAGE_PATHS);
		}
	}

	private void saveParentPathAndAutoFill(Uri parentTree) {
		SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		SharedPreferences.Editor e = prefs.edit();

		e.putString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, parentTree.toString());
		String base = parentTree.toString() + "::/";

		e.putString(UaeOptionKeys.UAE_PATH_PARENT_DIR, base);

		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_CONF_DIR, base + "conf/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, base + "kickstarts/");
		} else {
			String currentKickstarts = prefs.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null);
			if (looksLikeWrongKickstartsUnderFloppies(currentKickstarts)) {
				e.putString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, base + "kickstarts/");
			}
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_ROMS_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_ROMS_DIR, base + "roms/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, base + "floppies/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, base + "harddrives/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, base + "cdroms/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, base + "savestates/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_SCREENS_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_SCREENS_DIR, base + "screenshots/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_LHA_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_LHA_DIR, base + "lha/");
		}
		if (isBlank(prefs.getString(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, null))) {
			e.putString(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, base + "whdboot/");
		}

		e.apply();
	}

	private static boolean looksLikeWrongKickstartsUnderFloppies(String value) {
		if (value == null) return false;
		String v = value.trim().toLowerCase(java.util.Locale.ROOT);
		if (v.isEmpty()) return false;
		return v.contains("/floppies/kickstarts") || v.contains("/disks/kickstarts");
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private void showCompletePage() {
		mPageTitle.setText(R.string.walkthrough_complete_title);
		mPageDescription.setText(R.string.walkthrough_complete_desc);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, pad);

		TextView summaryText = new TextView(this);
		summaryText.setTextSize(14);
		summaryText.setText("Setup Summary:\n\n" +
			"📱 Model: " + mSelectedModel + "\n\n" +
			"You're ready to start!\n\n" +
			"Use the launcher to:\n" +
			"• Select a Quickstart model\n" +
			"• Load disk images (ADF/HDF)\n" +
			"• Play WHDLoad games\n" +
			"• Configure hardware options");
		layout.addView(summaryText);

		saveSelectedModel();

		mPageContainer.addView(layout);
		mBtnSkip.setVisibility(View.GONE);
		mBtnNext.setText("Start");
	}

	private void saveSelectedModel() {
		SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
		SharedPreferences.Editor e = p.edit();

		String chipset = "A500";
		if (mSelectedModel.contains("1200") || mSelectedModel.contains("4000")) {
			chipset = "A1200";
		} else if (mSelectedModel.equals("CD32")) {
			chipset = "CD32";
		} else if (mSelectedModel.equals("CDTV")) {
			chipset = "CDTV";
		}
		e.putString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, chipset);

		e.apply();
	}

	private void updateNavigationButtons() {
		mBtnBack.setVisibility(mCurrentPage > 0 ? View.VISIBLE : View.GONE);

		if (mCurrentPage == PAGE_COMPLETE) {
			mBtnNext.setText(R.string.walkthrough_start);
			mBtnSkip.setVisibility(View.GONE);
		} else {
			mBtnNext.setText(R.string.walkthrough_next);
			mBtnSkip.setVisibility(View.VISIBLE);
		}
	}

	private void skipWalkthrough() {
		new AlertDialog.Builder(this)
			.setTitle(R.string.walkthrough_skip_title)
			.setMessage(R.string.walkthrough_skip_message)
			.setPositiveButton(R.string.walkthrough_skip_yes, (d, w) -> {
				markWalkthroughCompleted();
				startBootstrap();
				finish();
			})
			.setNegativeButton(R.string.walkthrough_skip_no, null)
			.show();
	}

	private void startBootstrap() {
		Intent i = new Intent(this, BootstrapActivity.class);
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		startActivity(i);
	}

	private File getDisksDir() {
		File base = AppPaths.getBaseDir(this);
		File disks = new File(base, "disks");
		if (!disks.exists()) disks.mkdirs();
		return disks;
	}

	private boolean hasDiskImages(File dir) {
		if (dir == null || !dir.exists()) return false;
		File[] files = dir.listFiles();
		if (files == null) return false;

		for (File f : files) {
			String name = f.getName().toLowerCase();
			if (name.endsWith(".adf") || name.endsWith(".adz") || name.endsWith(".dms") ||
				name.endsWith(".hdf") || name.endsWith(".lha") || name.endsWith(".lzh") ||
				name.endsWith(".zip")) {
				return true;
			}
		}
		return false;
	}

	public static boolean shouldShowWalkthrough(Context ctx) {
		SharedPreferences p = ctx.getSharedPreferences(UaeOptionKeys.PREFS_NAME, Context.MODE_PRIVATE);
		boolean completed = p.getBoolean(PREF_WALKTHROUGH_COMPLETED, false);
		boolean disabled = p.getBoolean(PREF_WALKTHROUGH_DISABLED, false);
		return !completed && !disabled;
	}

	public static void setWalkthroughDisabled(Context ctx, boolean disabled) {
		SharedPreferences p = ctx.getSharedPreferences(UaeOptionKeys.PREFS_NAME, Context.MODE_PRIVATE);
		p.edit().putBoolean(PREF_WALKTHROUGH_DISABLED, disabled).apply();
	}
}
