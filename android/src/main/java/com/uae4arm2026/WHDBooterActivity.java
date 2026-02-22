package com.uae4arm2026;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class WHDBooterActivity extends Activity {
    
    private static final String TAG = "WHDBooterActivity";
    
    private static final String PREFS_NAME = "whdbooter";
    private static final String PREF_SELECTED_GAME = "selected_game";
    private static final String PREF_USE_JST = "use_jst";
    private static final String PREF_WRITE_CACHE = "write_cache";
    private static final String PREF_SHOW_SPLASH = "show_splash";
    private static final String PREF_QUIT_ON_EXIT = "quit_on_exit";
    private static final String PREF_MODEL = "model";
    
    private static final int REQ_SELECT_GAME = 2001;
    
    private TextView mTxtGameStatus;
    private TextView mTxtGameName;
    private TextView mTxtGamePath;
    private LinearLayout mSectionGameDetails;
    private CheckBox mChkUseJst;
    private CheckBox mChkWriteCache;
    private CheckBox mChkShowSplash;
    private CheckBox mChkQuitOnExit;
    
    private String mSelectedGamePath;
    private String mSelectedGameName;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whdbooter);
        
        initViews();
        loadPreferences();
        setupListeners();
    }
    
    private void initViews() {
        mTxtGameStatus = findViewById(R.id.txtGameStatus);
        mTxtGameName = findViewById(R.id.txtGameName);
        mTxtGamePath = findViewById(R.id.txtGamePath);
        mSectionGameDetails = findViewById(R.id.sectionGameDetails);
        mChkUseJst = findViewById(R.id.chkUseJst);
        mChkWriteCache = findViewById(R.id.chkWriteCache);
        mChkShowSplash = findViewById(R.id.chkShowSplash);
        mChkQuitOnExit = findViewById(R.id.chkQuitOnExit);
    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        mSelectedGamePath = prefs.getString(PREF_SELECTED_GAME, null);
        
        mChkUseJst.setChecked(prefs.getBoolean(PREF_USE_JST, false));
        mChkWriteCache.setChecked(prefs.getBoolean(PREF_WRITE_CACHE, true));
        mChkShowSplash.setChecked(prefs.getBoolean(PREF_SHOW_SPLASH, true));
        mChkQuitOnExit.setChecked(prefs.getBoolean(PREF_QUIT_ON_EXIT, true));
        
        updateGameDisplay();
    }
    
    private void savePreferences() {
        SharedPreferences.Editor prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        
        if (mSelectedGamePath != null) {
            prefs.putString(PREF_SELECTED_GAME, mSelectedGamePath);
        } else {
            prefs.remove(PREF_SELECTED_GAME);
        }
        
        prefs.putBoolean(PREF_USE_JST, mChkUseJst.isChecked());
        prefs.putBoolean(PREF_WRITE_CACHE, mChkWriteCache.isChecked());
        prefs.putBoolean(PREF_SHOW_SPLASH, mChkShowSplash.isChecked());
        prefs.putBoolean(PREF_QUIT_ON_EXIT, mChkQuitOnExit.isChecked());
        
        prefs.apply();
    }
    
    private void setupListeners() {
        // Select Game button
        Button btnSelectGame = findViewById(R.id.btnSelectGame);
        btnSelectGame.setOnClickListener(v -> selectGame());
        
        // Clear Game button
        Button btnClearGame = findViewById(R.id.btnClearGame);
        btnClearGame.setOnClickListener(v -> clearGame());
        
        // Back button
        Button btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        
        // Start button
        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> startGame());
    }
    
    private void selectGame() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        
        // Filter for LHA files
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/x-lzh",
            "application/x-lha",
            "application/x-archive",
            "*/*"
        });
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        
        startActivityForResult(intent, REQ_SELECT_GAME);
    }
    
    private void clearGame() {
        mSelectedGamePath = null;
        mSelectedGameName = null;
        updateGameDisplay();
        savePreferences();
    }
    
    private void updateGameDisplay() {
        if (mSelectedGamePath != null && !mSelectedGamePath.isEmpty()) {
            mTxtGameStatus.setText(mSelectedGameName != null ? mSelectedGameName : "Game selected");
            mTxtGameName.setText(mSelectedGameName != null ? mSelectedGameName : "");
            mTxtGamePath.setText(mSelectedGamePath);
            mSectionGameDetails.setVisibility(View.VISIBLE);
        } else {
            mTxtGameStatus.setText("No game selected");
            mTxtGameName.setText("");
            mTxtGamePath.setText("");
            mSectionGameDetails.setVisibility(View.GONE);
        }
    }
    
    private String materializeWHDLoadUriIfNeeded(String whdPathOrUri) {
        if (whdPathOrUri == null || whdPathOrUri.trim().isEmpty()) return whdPathOrUri;
        String p = whdPathOrUri.trim();
        if (!p.startsWith("content://")) return p;

        try {
            ContentResolver cr = getContentResolver();
            Uri uri = Uri.parse(p);

            String fileName = basenameFromMaybeEncodedDocId(uri.getLastPathSegment());
            if (!fileName.toLowerCase().endsWith(".lha") && !fileName.toLowerCase().endsWith(".lzh")) {
                fileName = fileName + ".lha";
            }

            File outDir = new File(getCacheDir(), "whdload-import");
            ensureDir(outDir);
            File outFile = new File(outDir, fileName);

            try (InputStream in = cr.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile, false)) {
                if (in == null) {
                    Log.i(TAG, "WHDLoad URI materialize failed (null input stream), using URI directly: " + p);
                    return p;
                }
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }

            Log.i(TAG, "Materialized WHDLoad URI to local file: " + outFile.getAbsolutePath());
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "WHDLoad URI materialize failed, using URI directly: " + t);
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
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void startGame() {
        if (mSelectedGamePath == null || mSelectedGamePath.isEmpty()) {
            Toast.makeText(this, "Please select a game first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Save preferences before launching
        savePreferences();
        
        // Materialize the WHDLoad file to a local path if it's a content:// URI
        // The native code needs a real filesystem path, not a SAF URI
        String localGamePath = materializeWHDLoadUriIfNeeded(mSelectedGamePath);
        Log.i(TAG, "Starting WHDLoad game with path: " + localGamePath);
        
        // Build intent to launch the emulator with WHDBooter
        Intent i = new Intent(this, AmiberryActivity.class);

        // WHDBooter sessions should always start from an A1200 baseline.
        i.putExtra(AmiberryActivity.EXTRA_QS_MODEL, "A1200");
        i.putExtra(AmiberryActivity.EXTRA_MACHINE_PRESET, "A1200");
        
        // Pass the LOCAL file path, not the URI
        i.putExtra(AmiberryActivity.EXTRA_WHDLOAD_FILE, localGamePath);
        
        // Pass WHDBooter settings
        i.putExtra(AmiberryActivity.EXTRA_WHD_USE_JST, mChkUseJst.isChecked());
        i.putExtra(AmiberryActivity.EXTRA_WHD_WRITE_CACHE, mChkWriteCache.isChecked());
        i.putExtra(AmiberryActivity.EXTRA_WHD_SHOW_SPLASH, mChkShowSplash.isChecked());
        i.putExtra(AmiberryActivity.EXTRA_WHD_QUIT_ON_EXIT, mChkQuitOnExit.isChecked());
        
        // Enable logging for debugging
        final boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            i.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
        }
        
        startActivity(i);
        finish();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_SELECT_GAME) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                
                // Take persistable permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(uri, flags);
                    } catch (SecurityException ignored) {
                        // Try read-only
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored2) {
                        }
                    }
                }
                
                mSelectedGamePath = uri.toString();
                
                // Try to get display name
                try {
                    android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                if (nameIndex >= 0) {
                                    mSelectedGameName = cursor.getString(nameIndex);
                                }
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                } catch (Exception ignored) {
                }
                
                // If no display name, extract from URI
                if (mSelectedGameName == null || mSelectedGameName.isEmpty()) {
                    String uriString = uri.toString();
                    int lastSlash = uriString.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        mSelectedGameName = uriString.substring(lastSlash + 1);
                        // Remove file extension
                        int lastDot = mSelectedGameName.lastIndexOf('.');
                        if (lastDot > 0) {
                            mSelectedGameName = mSelectedGameName.substring(0, lastDot);
                        }
                    }
                }
                
                updateGameDisplay();
                savePreferences();
                
                Toast.makeText(this, "Game selected: " + mSelectedGameName, Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        savePreferences();
    }
}
