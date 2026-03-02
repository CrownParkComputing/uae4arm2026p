package com.uae4arm2026;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.documentfile.provider.DocumentFile;

import org.libsdl.app.SDLActivity;

import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

// SDLActivity is provided by SDL2's Android Java sources.
// You must include SDL2 in the Android build for this to compile.
// (import already declared above)

public class AmiberryActivity extends SDLActivity {

    private static final String TAG = "AmiberryActivity";

    private static volatile Boolean sDebuggable;

    private static boolean isDebuggable() {
        Boolean cached = sDebuggable;
        if (cached != null) return cached;
        try {
            Context ctx = SDLActivity.getContext();
            // SDLActivity context can be null early in startup; don't cache a false result in that case.
            if (ctx == null) return false;
            ApplicationInfo ai = ctx.getApplicationInfo();
            boolean debuggable = (ai != null) && ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            sDebuggable = debuggable;
            return debuggable;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void logI(String msg) {
        // Always log: these messages are critical for diagnosing mounting issues.
        Log.i(TAG, msg);
    }

    // Note on SAF + hardfiles:
    // Passing "/proc/self/fd/<n>" into native code can still break on some Android ROMs,
    // because native code may end up resolving/re-opening the underlying /storage/... path.
    // We instead pass the original "content://..." URI to native code; the native layer
    // already supports SAF URIs via SafFileBridge (see `osdep/target.h` and `my_open`).

    private static final class ResolvedMediaPath {
        final String corePath;
        final boolean forcedReadOnly;

        ResolvedMediaPath(String corePath, boolean forcedReadOnly) {
            this.corePath = corePath;
            this.forcedReadOnly = forcedReadOnly;
        }
    }

    private static ResolvedMediaPath resolveForCorePathIfNeeded(String originalPathOrUri, boolean wantWrite) {
        if (originalPathOrUri == null) return new ResolvedMediaPath(null, false);
        String p = originalPathOrUri.trim();
        if (p.isEmpty()) return new ResolvedMediaPath(p, false);
        if (!p.startsWith("content://")) return new ResolvedMediaPath(p, false);

        try {
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return new ResolvedMediaPath(p, true);
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = Uri.parse(p);

            boolean forcedReadOnly;
            if (wantWrite) {
                forcedReadOnly = false;
                try {
                    // Probe write access; if denied we'll fall back to read-only.
                    try (ParcelFileDescriptor ignored = cr.openFileDescriptor(uri, "rw")) {
                        // ok
                    }
                } catch (Throwable ignored) {
                    forcedReadOnly = true;
                    // Probe read access (throws if missing/revoked).
                    try (ParcelFileDescriptor ignored2 = cr.openFileDescriptor(uri, "r")) {
                        // ok
                    }
                }
            } else {
                forcedReadOnly = true;
                // Probe read access (throws if missing/revoked).
                try (ParcelFileDescriptor ignored = cr.openFileDescriptor(uri, "r")) {
                    // ok
                }
            }

            // Keep the URI as the core path. Native code will open it via SAF.
            return new ResolvedMediaPath(p, forcedReadOnly);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to resolve SAF URI for core: " + p + " err=" + t);
            return new ResolvedMediaPath(p, true);
        }
    }

    // Legacy no-op (older builds kept a list of open ParcelFileDescriptors).
    // We intentionally do not keep them open anymore; native code opens SAF URIs on demand.
    private static void closeOpenMediaFds() {
    }

    private static String describeDiskPath(String path) {
        String p = (path == null) ? "" : path.trim();
        if (p.isEmpty()) return "(empty)";
        if (p.startsWith("content://")) return "(SAF)";
        try {
            File f = new File(p);
            return "exists=" + f.exists() + " file=" + f.isFile() + " len=" + f.length() + " mtime=" + f.lastModified();
        } catch (Throwable t) {
            return "(error: " + t + ")";
        }
    }

    private static boolean sLoggedVkbdProvisionFailure = false;
    private static boolean sLoggedVkbdInterceptorFailure = false;
    private static boolean sLoggedVkbdTouchFailure = false;
    private static boolean sLoggedVkbdActiveQueryFailure = false;

    // Optional intent extra to force a specific kickstart ROM file.
    // If provided, this overrides external kick.rom lookup and :AROS.
    public static final String EXTRA_KICKSTART_ROM_FILE = "com.uae4arm2026.extra.KICKSTART_ROM_FILE";

    // Optional intent extra to autoload a WHDLoad game (LHA/LZH/ZIP).
    public static final String EXTRA_WHDLOAD_FILE = "com.uae4arm2026.extra.WHDLOAD_FILE";

    // Optional intent extras for WHDBooter settings
    public static final String EXTRA_WHD_USE_JST = "com.uae4arm2026.extra.WHD_USE_JST";
    public static final String EXTRA_WHD_WRITE_CACHE = "com.uae4arm2026.extra.WHD_WRITE_CACHE";
    public static final String EXTRA_WHD_SHOW_SPLASH = "com.uae4arm2026.extra.WHD_SHOW_SPLASH";
    public static final String EXTRA_WHD_QUIT_ON_EXIT = "com.uae4arm2026.extra.WHD_QUIT_ON_EXIT";

    // Optional intent extra to force a specific DF0 disk image file.
    // If provided, this overrides internal/external disk auto-detection.
    public static final String EXTRA_DF0_DISK_FILE = "com.uae4arm2026.extra.DF0_DISK_FILE";
    public static final String EXTRA_DF0_SOURCE_NAME = "com.uae4arm2026.extra.DF0_SOURCE_NAME";

    // Optional intent extra to force a specific CD image file (ISO/CUE/CHD/etc).
    // If provided, this is preferred over prefs for cdimage0 mounting.
    public static final String EXTRA_CD_IMAGE0_FILE = "com.uae4arm2026.extra.CD_IMAGE0_FILE";

    // Optional intent extras to force disk images for DF1–DF3.
    public static final String EXTRA_DF1_DISK_FILE = "com.uae4arm2026.extra.DF1_DISK_FILE";
    public static final String EXTRA_DF2_DISK_FILE = "com.uae4arm2026.extra.DF2_DISK_FILE";
    public static final String EXTRA_DF3_DISK_FILE = "com.uae4arm2026.extra.DF3_DISK_FILE";

    // Optional intent extra to allow automatically mounting a default DF0 disk image
    // (disk.zip) when the launcher did not explicitly provide DF0.
    // Default is false to avoid unexpected disk insertion (e.g., when booting from HD).
    public static final String EXTRA_ENABLE_AUTO_DF0 = "com.uae4arm2026.extra.ENABLE_AUTO_DF0";

    // Hot-swap requests sent while the emulator is already running.
    public static final String EXTRA_HOTSWAP_DF0_PATH = "com.uae4arm2026.extra.HOTSWAP_DF0_PATH";
    public static final String EXTRA_HOTSWAP_DF1_PATH = "com.uae4arm2026.extra.HOTSWAP_DF1_PATH";
    public static final String EXTRA_HOTSWAP_DF2_PATH = "com.uae4arm2026.extra.HOTSWAP_DF2_PATH";
    public static final String EXTRA_HOTSWAP_DF3_PATH = "com.uae4arm2026.extra.HOTSWAP_DF3_PATH";

    // Optional intent extra to enable core logging to a file via write_logfile/logfile_path.
    // This is useful for debugging headless boot issues on Android.
    public static final String EXTRA_ENABLE_LOGFILE = "com.uae4arm2026.extra.ENABLE_LOGFILE";

    // Optional intent extra to control whether Amiberry shows its GUI.
    // Default is false (launcher activity provides UI).
    public static final String EXTRA_SHOW_GUI = "com.uae4arm2026.extra.SHOW_GUI";

    // Optional intent extra to quickly set a CPU type (e.g., 68000, 68020).
    // This is a convenience preset; users can still change things in the GUI.
    public static final String EXTRA_CPU_TYPE = "com.uae4arm2026.extra.CPU_TYPE";

    // Optional intent extra to set a built-in machine preset.
    // Values are passed through to Amiberry's config key `chipset_compatible`.
    // Examples: "A500" (non-AGA), "A1200" (AGA)
    public static final String EXTRA_MACHINE_PRESET = "com.uae4arm2026.extra.MACHINE_PRESET";

    // Quickstart-style launch options (mirrors the Amiberry Quickstart panel semantics).
    public static final String EXTRA_QS_MODEL = "com.uae4arm2026.extra.QS_MODEL";
    public static final String EXTRA_QS_CONFIG_INDEX = "com.uae4arm2026.extra.QS_CONFIG_INDEX";
    public static final String EXTRA_QS_NTSC = "com.uae4arm2026.extra.QS_NTSC";
    public static final String EXTRA_QS_MODE = "com.uae4arm2026.extra.QS_MODE";

    // Sent by BootstrapActivity when the user wants to apply changed media/settings.
    public static final String EXTRA_REQUEST_RESTART = "com.uae4arm2026.extra.REQUEST_RESTART";

    private boolean mRestartRequested;

    // Expected ROM filenames for Kickstart replacement :AROS
    private static final String AROS_ROM = "aros-rom.bin";
    private static final String AROS_EXT = "aros-ext.bin";

    // If you bundle the AROS ROMs inside the APK/AAR, put them under assets/roms/.
    private static final String ASSET_AROS_ROM = "roms/" + AROS_ROM;
    private static final String ASSET_AROS_EXT = "roms/" + AROS_EXT;

    // External Kickstart path requested:
    // /storage/emulated/0/uae4arm2026/kickstarts/kick.rom
    private static final String EXTERNAL_KICKSTART_REL = "uae4arm2026/kickstarts/kick.rom";

    // DF0 disk image (zip/adf) requested.
    // We'll try a few common locations and mount it as DF0 via the Amiberry CLI (-0 <diskimage>).
    private static final String DF0_DISK_NAME = "disk.zip";
    private static final String EXTERNAL_DF0_REL_1 = "uae4arm2026/kickstarts/" + DF0_DISK_NAME;
    private static final String EXTERNAL_DF0_REL_2 = "uae4arm2026/disks/" + DF0_DISK_NAME;

    // If set, overrides kickstart_rom_file. Otherwise defaults to :AROS.
    private String mKickstartRomFile = ":AROS";

    // Optional WHDLoad file.
    private String mWHDLoadFile = null;

    // Optional DF0 disk image.
    private String mDf0DiskImagePath = null;

    // Optional forced CD image.
    private String mCdImagePath = null;
    private String mDf0SourceName = null;

    // Optional DF1–DF3 disk images.
    private String mDf1DiskImagePath = null;
    private String mDf2DiskImagePath = null;
    private String mDf3DiskImagePath = null;

    private boolean mShowGui = false;
    private String mCpuType = null;
    private String mMachinePreset = null;

    private boolean mEnableLogfile = false;

    private String mQsModel = null;
    private int mQsConfigIndex = 0;
    private boolean mQsNtsc = false;
    private boolean mQsMode = true;

    private ViewGroup mGuiLayer;
    private FrameLayout mRootLayer;
    private FrameLayout mEmulatorLayer;
    private boolean mPausedByOverlay = false;

    /**
     * Derive mapper profile id from currently loaded media.
     * Format: MEDIA_GAMENAME (e.g. WHD_LOTUS2, DF0_SHADOW_OF_THE_BEAST).
     */
    private String getGameIdentifier() {
        String media = null;
        String game = filenameWithoutExtension(mWHDLoadFile);
        if (game != null) {
            media = "WHD";
        } else {
            game = resolveDf0GameName();
            if (game != null) {
                media = "DF0";
            }
        }
        if (media == null || game == null) {
            return null;
        }

        return media + "_" + sanitizeIdComponent(game);
    }

    private String resolveDf0GameName() {
        String sourceName = filenameWithoutExtension(mDf0SourceName);
        if (sourceName != null && !sourceName.trim().isEmpty()) {
            return sourceName;
        }

        String fromPath = filenameWithoutExtension(mDf0DiskImagePath);
        if (fromPath == null || fromPath.trim().isEmpty()) {
            return null;
        }

        if ("disk".equalsIgnoreCase(fromPath)) {
            try {
                String bootstrapSource = getSharedPreferences("bootstrap", MODE_PRIVATE).getString("df0_src", null);
                String fromBootstrap = filenameWithoutExtension(bootstrapSource);
                if (fromBootstrap != null && !fromBootstrap.trim().isEmpty()) {
                    return fromBootstrap;
                }
            } catch (Throwable ignored) {
            }
        }

        return fromPath;
    }

    private static String sanitizeIdComponent(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        String s = value.trim().toUpperCase(java.util.Locale.ROOT);
        s = s.replaceAll("[^A-Z0-9_-]", "_");
        if (s.isEmpty()) return "UNKNOWN";
        return s;
    }

    /** Strip directory and extension from a path, returning null for blank/null inputs. */
    private static String filenameWithoutExtension(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String name = path.trim();
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (sep >= 0) name = name.substring(sep + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.isEmpty() ? null : name;
    }

    private VirtualJoystickOverlay mVirtualJoystick;
    private View mVkbdTouchInterceptor;
    private View mInEmuOverlay;
    private int mVkbdForwardLogCount = 0;
    private int mVkbdProbeLogCount = 0;
    private long mTapDownTimeMs = 0;
    private float mTapDownX = 0f;
    private float mTapDownY = 0f;
    private boolean mTapMoved = false;
    private boolean mTwoFingerTapCandidate = false;
    private long mTwoFingerDownTimeMs = 0;
    private float mTwoFingerDownCx = 0f;
    private float mTwoFingerDownCy = 0f;

    private boolean handleTouchMouseTapGesture(View v, MotionEvent ev) {
        final int action = ev.getActionMasked();
        final float density = getResources().getDisplayMetrics().density;
        final float moveSlopPx = 12f * density;
        final long tapMaxMs = 250;

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mTapDownTimeMs = ev.getEventTime();
                mTapDownX = ev.getX();
                mTapDownY = ev.getY();
                mTapMoved = false;
                mTwoFingerTapCandidate = false;
                return false;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (ev.getPointerCount() == 2) {
                    mTwoFingerTapCandidate = true;
                    mTapMoved = false;
                    mTwoFingerDownTimeMs = ev.getEventTime();
                    mTwoFingerDownCx = (ev.getX(0) + ev.getX(1)) * 0.5f;
                    mTwoFingerDownCy = (ev.getY(0) + ev.getY(1)) * 0.5f;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mTwoFingerTapCandidate && ev.getPointerCount() >= 2) {
                    float cx = (ev.getX(0) + ev.getX(1)) * 0.5f;
                    float cy = (ev.getY(0) + ev.getY(1)) * 0.5f;
                    if (Math.abs(cx - mTwoFingerDownCx) > moveSlopPx || Math.abs(cy - mTwoFingerDownCy) > moveSlopPx) {
                        mTapMoved = true;
                    }
                } else if (ev.getPointerCount() == 1) {
                    if (Math.abs(ev.getX() - mTapDownX) > moveSlopPx || Math.abs(ev.getY() - mTapDownY) > moveSlopPx) {
                        mTapMoved = true;
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (mTwoFingerTapCandidate && ev.getPointerCount() == 2 && !mTapMoved) {
                    long dt = ev.getEventTime() - mTwoFingerDownTimeMs;
                    if (dt <= tapMaxMs) {
                        float cx = (ev.getX(0) + ev.getX(1)) * 0.5f;
                        float cy = (ev.getY(0) + ev.getY(1)) * 0.5f;
                        try {
                            SDLActivity.onNativeMouse(MotionEvent.BUTTON_SECONDARY, MotionEvent.ACTION_DOWN, cx, cy, false);
                            SDLActivity.onNativeMouse(MotionEvent.BUTTON_SECONDARY, MotionEvent.ACTION_UP, cx, cy, false);
                            Log.i(TAG, "Touch mouse: two-finger tap => right click at " + (int) cx + "," + (int) cy);
                            mTwoFingerTapCandidate = false;
                            return true;
                        } catch (Throwable t) {
                            Log.w(TAG, "Unable to emit right-click from two-finger tap: " + t);
                        }
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_UP: {
                if (mTwoFingerTapCandidate) {
                    mTwoFingerTapCandidate = false;
                    return false;
                }
                if (!mTapMoved) {
                    long dt = ev.getEventTime() - mTapDownTimeMs;
                    if (dt <= tapMaxMs) {
                        float x = ev.getX();
                        float y = ev.getY();
                        try {
                            SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, x, y, false);
                            SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_UP, x, y, false);
                            Log.i(TAG, "Touch mouse: one-finger tap => left click at " + (int) x + "," + (int) y);
                            return true;
                        } catch (Throwable t) {
                            Log.w(TAG, "Unable to emit left-click from tap: " + t);
                        }
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL: {
                mTapMoved = false;
                mTwoFingerTapCandidate = false;
                return false;
            }
            default:
                return false;
        }
    }

    private File getAmiberryDataDir() {
        // Must match native get_data_directory() on Android:
        //   SDL_AndroidGetInternalStoragePath() + "/amiberry/data/"
        File internal = getFilesDir();
        if (internal == null) return new File("/data/local/tmp/amiberry/data");
        return new File(internal, "amiberry/data");
    }

    private void provisionVkbdAssetsIfPossible() {
        try {
            File vkbdDir = new File(getAmiberryDataDir(), "vkbd");
            ensureDir(vkbdDir);

            AssetManager am = getAssets();
            String[] files = am.list("vkbd");
            if (files == null || files.length == 0) {
                Log.w(TAG, "No VKBD assets found in APK (assets/vkbd). VKBD may not render.");
                return;
            }
            for (String name : files) {
                if (name == null) continue;
                String n = name.trim();
                if (n.isEmpty()) continue;
                if (!n.toLowerCase().endsWith(".png")) continue;
                copyAssetIfMissing("vkbd/" + n, new File(vkbdDir, n));
            }
        } catch (Throwable t) {
            if (!sLoggedVkbdProvisionFailure) {
                Log.w(TAG, "VKBD provisioning failed: " + t);
                sLoggedVkbdProvisionFailure = true;
            }
        }
    }

    private void provisionWhdbootAssetsIfPossible() {
        // WHDBooter requires real filesystem paths (std::filesystem) for boot-data and helpers.
        // On Android we bundle these into APK assets and copy them into app-private storage.
        try {
            File base = getAmiberryBaseDir();
            File whdbootDir = new File(base, "whdboot");
            logI("Provisioning WHDboot assets to: " + whdbootDir.getAbsolutePath());
            ensureDir(whdbootDir);

            logI("Copying boot-data.zip...");
            copyAssetIfMissing("whdboot/boot-data.zip", new File(whdbootDir, "boot-data.zip"));
            logI("Copying WHDLoad...");
            copyAssetIfMissing("whdboot/WHDLoad", new File(whdbootDir, "WHDLoad"));
            logI("Copying JST...");
            copyAssetIfMissing("whdboot/JST", new File(whdbootDir, "JST"));
            logI("Copying AmiQuit...");
            copyAssetIfMissing("whdboot/AmiQuit", new File(whdbootDir, "AmiQuit"));

            // Optional DB (improves slave selection); copy only if present in assets.
            try {
                AssetManager am = getAssets();
                String[] kids = am.list("whdboot/game-data");
                boolean hasDb = false;
                if (kids != null) {
                    for (String k : kids) {
                        if (k != null && k.trim().equalsIgnoreCase("whdload_db.xml")) {
                            hasDb = true;
                            break;
                        }
                    }
                }
                if (hasDb) {
                    File gameData = new File(whdbootDir, "game-data");
                    ensureDir(gameData);
                    logI("Copying whdload_db.xml...");
                    copyAssetIfMissing("whdboot/game-data/whdload_db.xml", new File(gameData, "whdload_db.xml"));
                }
            } catch (Throwable ignored) {
            }
            logI("WHDboot assets provisioned successfully");
        } catch (Throwable t) {
            Log.w(TAG, "WHDboot provisioning failed: " + t);
        }
    }

    private static String escapeForUaeQuoted(String s) {
        if (s == null) return "";
        // cfgfile_unescape understands backslash escaping; keep it simple.
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private String materializeWHDLoadUriIfNeeded(String whdPathOrUri) {
        if (whdPathOrUri == null || whdPathOrUri.trim().isEmpty()) return whdPathOrUri;
        String p = whdPathOrUri.trim();
        if (!p.startsWith("content://")) return p;

        try {
            Uri uri = Uri.parse(p);
            String outName = basenameFromMaybeEncodedDocId(uri.getLastPathSegment());

            File stageDir = new File(getCacheDir(), "whdload_uri");
            ensureDir(stageDir);
            File outFile = new File(stageDir, outName);

            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return p;
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }

            logI("Materialized WHDLoad URI to local file: " + outFile.getAbsolutePath());
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "WHDLoad URI materialize failed, using URI directly: " + t);
            return p;
        }
    }

    private String materializeCdImageUriIfNeeded(String cdPathOrUri) {
        if (cdPathOrUri == null || cdPathOrUri.trim().isEmpty()) return cdPathOrUri;
        String p = cdPathOrUri.trim();
        if (!p.startsWith("content://")) return p;

        try {
            Uri uri = Uri.parse(p);
            String outName = basenameFromMaybeEncodedDocId(uri.getLastPathSegment());
            String ext = BootstrapMediaUtils.lowerExt(outName);

            if ("cue".equals(ext)) {
                logI("Refusing direct SAF CUE mount (requires sibling track files): " + p);
                return null;
            }

            File stageDir = new File(getFilesDir(), "uae4arm/cd0_runtime");
            ensureDir(stageDir);
            File outFile = new File(stageDir, outName);

            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }

            logI("Materialized CD URI to local file: " + outFile.getAbsolutePath());
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "CD URI materialize failed: " + t);
            return null;
        }
    }

    private static boolean looksLikeRdbHardfile(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        String p = path.trim();

        // Match Amiberry heuristics: scan a small header window only.
        final int scanBlocks = 16;
        final int blockSize = 512;

        if (p.startsWith("content://")) {
            try {
                Context ctx = SDLActivity.getContext();
                if (ctx == null) return false;
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = Uri.parse(p);
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                    if (pfd == null) return false;
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                        byte[] blk = new byte[blockSize];
                        for (int i = 0; i < scanBlocks; i++) {
                            int r = fis.read(blk);
                            if (r < 4) return false;
                            if (isRdbSignatureBlock(blk)) return true;
                            if (r < blockSize) return false;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        File f = new File(p);
        if (!f.exists() || !f.isFile() || f.length() < blockSize) return false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] blk = new byte[blockSize];
            for (int i = 0; i < scanBlocks; i++) {
                raf.seek((long) i * (long) blockSize);
                int r = raf.read(blk);
                if (r < 4) return false;
                if (isRdbSignatureBlock(blk)) return true;
                if (r < blockSize) return false;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isRdbSignatureBlock(byte[] blk) {
        if (blk == null || blk.length < 4) return false;
        // ADIDE encoded "CPRM"
        if ((blk[0] & 0xFF) == 0x39 && (blk[1] & 0xFF) == 0x10 && (blk[2] & 0xFF) == 0xD3 && (blk[3] & 0xFF) == 0x12) {
            return true;
        }
        // A2090 BABE marker (seen with some controllers)
        if ((blk[0] & 0xFF) == 0xBA && (blk[1] & 0xFF) == 0xBE) {
            return true;
        }
        // Classic RDB signatures
        return (blk[0] == 'R' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K')
            || (blk[0] == 'D' && blk[1] == 'R' && blk[2] == 'K' && blk[3] == 'S')
            || (blk[0] == 'C' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K');
    }

    private static final class RdbGeometry {
        final int sectors;
        final int heads;
        final int reserved;
        final int blocksize;

        RdbGeometry(int sectors, int heads, int reserved, int blocksize) {
            this.sectors = sectors;
            this.heads = heads;
            this.reserved = reserved;
            this.blocksize = blocksize;
        }
    }

    private static int readBeU16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long readBeU32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24)
            | ((long) (b[off + 1] & 0xff) << 16)
            | ((long) (b[off + 2] & 0xff) << 8)
            | ((long) (b[off + 3] & 0xff));
    }

    private static boolean isLikelyValidRdbBlock(byte[] blk) {
        if (blk == null || blk.length < 512) return false;

        // Must start with RDSK or CDSK.
        boolean sig = (blk[0] == 'R' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K')
            || (blk[0] == 'C' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K');
        if (!sig) return false;

        // RigidDiskBlock layout uses a size (in 32-bit longwords) at offset 0x04.
        // Typical is 0x40 (64 longwords = 256 bytes). Accept a reasonable range.
        long sizeLong = readBeU32(blk, 0x04);
        if (sizeLong < 8 || sizeLong > 128) return false;
        int sizeBytes = (int) (sizeLong * 4);
        if (sizeBytes > 512) return false;

        // Validate checksum: sum of all longwords in the block equals 0 (signed 32-bit wrap).
        // This is a strong filter against accidental "RDSK" byte sequences in data.
        int sum = 0;
        for (int i = 0; i < sizeLong; i++) {
            int lw = (int) readBeU32(blk, i * 4);
            sum += lw;
        }
        if (sum != 0) return false;

        // Basic sanity on blocksize.
        int blocksize = (int) readBeU32(blk, 0x10);
        if (blocksize <= 0) blocksize = 512;
        if (!(blocksize == 512 || blocksize == 1024 || blocksize == 2048 || blocksize == 4096)) return false;

        // Basic sanity on CHS fields.
        int sectors = (int) readBeU32(blk, 0x44);
        int heads = (int) readBeU32(blk, 0x48);
        if (sectors <= 0 || heads <= 0 || sectors > 255 || heads > 255) {
            // Some variants store as 16-bit; try that as a fallback.
            sectors = readBeU16(blk, 0x44 + 2);
            heads = readBeU16(blk, 0x48 + 2);
        }
        if (sectors <= 0 || heads <= 0 || sectors > 255 || heads > 255) return false;

        return true;
    }

    private static RdbGeometry tryReadRdbGeometry(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String p = path.trim();

        // Scan further than the classic "first 16 blocks" heuristic to avoid false negatives.
        final int MAX_SCAN_BLOCKS = 2048;

        if (p.startsWith("content://")) {
            try {
                Context ctx = SDLActivity.getContext();
                if (ctx == null) return null;
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = Uri.parse(p);

                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                    if (pfd == null) return null;
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileChannel ch = fis.getChannel()) {
                        long size = ch.size();
                        if (size < 512) return null;

                        byte[] blk = new byte[512];
                        long maxBlocks = Math.min(MAX_SCAN_BLOCKS, Math.max(1, size / 512));
                        ByteBuffer buf = ByteBuffer.wrap(blk);
                        boolean found = false;
                        for (int i = 0; i < maxBlocks; i++) {
                            buf.clear();
                            ch.position((long) i * 512L);
                            int r = ch.read(buf);
                            if (r < 76) continue;
                            if (!isLikelyValidRdbBlock(blk)) continue;
                            found = true;
                            break;
                        }
                        if (!found) return null;

                        int blocksize = (int) readBeU32(blk, 0x10);
                        if (blocksize <= 0) blocksize = 512;

                        int sectors = (int) readBeU32(blk, 0x44);
                        int heads = (int) readBeU32(blk, 0x48);
                        if (sectors <= 0 || heads <= 0) {
                            sectors = readBeU16(blk, 0x44 + 2);
                            heads = readBeU16(blk, 0x48 + 2);
                        }
                        if (sectors <= 0 || heads <= 0) return null;

                        int reserved = 2;
                        long partListBlock = readBeU32(blk, 0x1c);
                        if (partListBlock > 0) {
                            long partOffset = partListBlock * (long) blocksize;
                            if (partOffset >= 0 && partOffset + 256 <= size) {
                                byte[] part = new byte[Math.max(512, blocksize)];
                                ByteBuffer pbuf = ByteBuffer.wrap(part);
                                pbuf.clear();
                                ch.position(partOffset);
                                int pr = ch.read(pbuf);
                                if (pr >= 160 && part[0] == 'P' && part[1] == 'A' && part[2] == 'R' && part[3] == 'T') {
                                    long res = readBeU32(part, 128 + 6 * 4);
                                    if (res >= 0 && res <= 1024) {
                                        reserved = (int) res;
                                    }
                                }
                            }
                        }

                        return new RdbGeometry(sectors, heads, reserved, blocksize);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Unable to parse RDB geometry from HDF URI: " + t);
                return null;
            }
        }

        File f = new File(p);
        if (!f.exists() || !f.isFile() || f.length() < 512) return null;

        // RDSK layout reference (see filesys.cpp):
        //  blocksize @ 0x10 (u32)
        //  partlist   @ 0x1c (u32 block number)
        //  cylinders  @ 0x40 (u32)
        //  sectors    @ 0x44 (u32)
        //  heads      @ 0x48 (u32)
        // Partition env (PART, at +128): reserved @ (128 + 6*4)
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] blk = new byte[512];
            long maxBlocks = Math.min(MAX_SCAN_BLOCKS, Math.max(1, f.length() / 512));
            boolean found = false;
            for (int i = 0; i < maxBlocks; i++) {
                raf.seek((long) i * 512L);
                int r = raf.read(blk);
                if (r < 76) continue;
                if (!isLikelyValidRdbBlock(blk)) continue;
                found = true;
                break;
            }
            if (!found) return null;

            int blocksize = (int) readBeU32(blk, 0x10);
            if (blocksize <= 0) blocksize = 512;

            int sectors = (int) readBeU32(blk, 0x44);
            int heads = (int) readBeU32(blk, 0x48);
            if (sectors <= 0 || heads <= 0) {
                // Some variants store as 16-bit; try that as a fallback.
                sectors = readBeU16(blk, 0x44 + 2);
                heads = readBeU16(blk, 0x48 + 2);
            }
            if (sectors <= 0 || heads <= 0) return null;

            int reserved = 2;
            long partListBlock = readBeU32(blk, 0x1c);
            if (partListBlock > 0) {
                long partOffset = partListBlock * (long) blocksize;
                if (partOffset >= 0 && partOffset + 256 <= f.length()) {
                    byte[] part = new byte[Math.max(512, blocksize)];
                    raf.seek(partOffset);
                    int pr = raf.read(part);
                    if (pr >= 160 && part[0] == 'P' && part[1] == 'A' && part[2] == 'R' && part[3] == 'T') {
                        long res = readBeU32(part, 128 + 6 * 4);
                        if (res >= 0 && res <= 1024) {
                            reserved = (int) res;
                        }
                    }
                }
            }

            return new RdbGeometry(sectors, heads, reserved, blocksize);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to parse RDB geometry from HDF: " + t);
            return null;
        }
    }

    private static final class ChsGeometry {
        final int sectors;
        final int heads;

        ChsGeometry(int sectors, int heads) {
            this.sectors = sectors;
            this.heads = heads;
        }
    }

    private static ChsGeometry chooseChsGeometryForHardfile(String path, int blocksize) {
        return chooseChsGeometryForHardfile(path, blocksize, 0);
    }

    private static ChsGeometry chooseChsGeometryForHardfile(String path, int blocksize, int reservedBlocks) {
        String p = path;
        long size = 0;
        if (p != null && p.startsWith("content://")) {
            try {
                Context ctx = SDLActivity.getContext();
                if (ctx != null) {
                    ContentResolver cr = ctx.getContentResolver();
                    Uri uri = Uri.parse(p);
                    try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                        if (pfd != null) {
                            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                                 FileChannel ch = fis.getChannel()) {
                                size = ch.size();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        } else {
            File f = new File(path);
            size = f.length();
        }
        if (blocksize <= 0) blocksize = 512;
        long totalBlocks = (size > 0) ? (size / (long) blocksize) : 0;
        if (reservedBlocks > 0 && reservedBlocks < totalBlocks) {
            totalBlocks -= reservedBlocks;
        }
        if (totalBlocks <= 0) {
            return new ChsGeometry(32, 16);
        }

        // Prefer common geometries that divide the image size exactly.
        // (We want cylinders to be an integer and reasonably small.)
        int[][] candidates = new int[][]{
            // Common PC-ish geometries first.
            {32, 16}, {32, 8}, {32, 4}, {32, 2}, {32, 1},
            {63, 16}, {63, 8}, {63, 4}, {63, 2}, {63, 1},
            {127, 16}, {127, 8}, {127, 4}, {127, 2}, {127, 1},

            // More permissive fallbacks: improve odds of finding an exact divisor.
            // (Some HDFs are sized in ways that don't divide the common geometries.)
            {16, 16}, {16, 8}, {16, 4}, {16, 2}, {16, 1},
            {8, 16}, {8, 8}, {8, 4}, {8, 2}, {8, 1},
            {4, 16}, {4, 8}, {4, 4}, {4, 2}, {4, 1},
            {2, 16}, {2, 8}, {2, 4}, {2, 2}, {2, 1},
            {1, 16}, {1, 8}, {1, 4}, {1, 2}, {1, 1},
        };

        for (int[] c : candidates) {
            int sectors = c[0];
            int heads = c[1];
            long spc = (long) sectors * (long) heads;
            if (spc <= 0) continue;
            if ((totalBlocks % spc) != 0) continue;
            long cylinders = totalBlocks / spc;
            if (cylinders >= 1 && cylinders <= 65535) {
                return new ChsGeometry(sectors, heads);
            }
        }

        // Fall back to a sensible default (may truncate a tiny remainder).
        return new ChsGeometry(32, 16);
    }

    private static boolean isAmigaDosBootblockDostype(long dt) {
        // AmigaDOS volumes use 'DOS\0'..'DOS\7' variants.
        // Mask low byte which carries flags/version.
        return (dt & 0xFFFFFF00L) == 0x444F5300L; // 'D''O''S'\0
    }

    private static int findAmigaDosBootblockOffsetBlocks(String path, int maxBlocks, int blocksize) {
        if (path == null || path.trim().isEmpty()) return -1;
        if (maxBlocks <= 0) return -1;
        if (blocksize <= 0) blocksize = 512;

        String p = path.trim();
        try {
            if (p.startsWith("content://")) {
                Context ctx = SDLActivity.getContext();
                if (ctx == null) return -1;
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = Uri.parse(p);
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                    if (pfd == null) return -1;
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileChannel ch = fis.getChannel()) {
                        long size = 0;
                        try { size = ch.size(); } catch (Throwable ignored) { }
                        byte[] buf = new byte[4];
                        ByteBuffer bb = ByteBuffer.wrap(buf);
                        for (int i = 0; i < maxBlocks; i++) {
                            long off = (long) i * (long) blocksize;
                            if (size > 0 && off + 4 > size) break;
                            bb.clear();
                            ch.position(off);
                            int r = ch.read(bb);
                            if (r < 4) continue;
                            long dt = readBeU32(buf, 0);
                            if (isAmigaDosBootblockDostype(dt)) return i;
                        }
                    }
                }
                return -1;
            }

            File f = new File(p);
            if (!f.exists() || !f.isFile()) return -1;
            long size = f.length();
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                byte[] buf = new byte[4];
                for (int i = 0; i < maxBlocks; i++) {
                    long off = (long) i * (long) blocksize;
                    if (off + 4 > size) break;
                    raf.seek(off);
                    int r = raf.read(buf);
                    if (r < 4) continue;
                    long dt = readBeU32(buf, 0);
                    if (isAmigaDosBootblockDostype(dt)) return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static long readBootBlockDostype(String path) {
        if (path == null || path.trim().isEmpty()) return 0;
        String p = path.trim();

        try {
            byte[] buf = new byte[4];

            if (p.startsWith("content://")) {
                Context ctx = SDLActivity.getContext();
                if (ctx == null) return 0;
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = Uri.parse(p);
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                    if (pfd == null) return 0;
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                        int r = fis.read(buf);
                        if (r < 4) return 0;
                    }
                }
            } else {
                File f = new File(p);
                if (!f.exists() || !f.isFile() || f.length() < 4) return 0;
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(0);
                    int r = raf.read(buf);
                    if (r < 4) return 0;
                }
            }

            return readBeU32(buf, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String findFastFileSystemNearKickstart(String kickstartRomFile) {
        try {
            if (kickstartRomFile == null) return null;
            String ks = kickstartRomFile.trim();
            if (ks.isEmpty() || ks.startsWith(":")) return null;
            File rom = new File(ks);
            File dir = rom.getParentFile();
            if (dir == null) return null;

            // Common names used by UAE/WinUAE setups.
            String[] names = new String[] {
                "FastFileSystem",
                "fastfilesystem",
                "FastFileSystem.rom",
                "fastfilesystem.rom"
            };
            for (String n : names) {
                File f = new File(dir, n);
                if (f.exists() && f.isFile() && f.length() > 0) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String normalizeTreeRelBase(String relBase) {
        if (relBase == null) return "/";
        String b = relBase.trim();
        if (b.isEmpty()) return "/";
        if (!b.startsWith("/")) b = "/" + b;
        // Some callers include a trailing slash ("/roms/") and some do not.
        if (!b.endsWith("/")) b = b + "/";
        return b;
    }

    private static String tryImportFastFileSystemFromRomPath(String romPath) {
        try {
            if (romPath == null) return null;
            String rp = romPath.trim();
            if (rp.isEmpty()) return null;

            final Context ctx = SDLActivity.getContext();
            if (ctx == null) return null;

            // We support both:
            //  - file paths (e.g., /sdcard/Amiga/roms/)
            //  - SAF tree paths (our native layer often uses: <treeUri>::<relBase>)
            if (!rp.startsWith("content://")) {
                // Local filesystem: just look for a file.
                File dir = new File(rp);
                if (dir.isDirectory()) {
                    String[] names = new String[] {
                        "FastFileSystem",
                        "fastfilesystem",
                        "FastFileSystem.rom",
                        "fastfilesystem.rom"
                    };
                    for (String n : names) {
                        File f = new File(dir, n);
                        if (f.exists() && f.isFile() && f.length() > 0) {
                            return f.getAbsolutePath();
                        }
                    }
                }
                return null;
            }

            // SAF tree: split "treeUri::/relBase" if present.
            String treeUri = rp;
            String relBase = "/";
            int split = rp.indexOf("::");
            if (split > 0) {
                treeUri = rp.substring(0, split);
                relBase = rp.substring(split + 2);
            }
            relBase = normalizeTreeRelBase(relBase);

            logI("Searching for FastFileSystem in rom_path treeUri=" + treeUri + " relBase=" + relBase);

            // Candidate names/locations to look for in the configured ROM folder.
            // Many setups keep it next to Kickstart, but a full AmigaOS tree may have it in L/.
            String[] names = new String[] {
                "FastFileSystem",
                "fastfilesystem",
                "FastFileSystem.rom",
                "fastfilesystem.rom",
                "L/FastFileSystem",
                "L/fastfilesystem",
                "l/FastFileSystem",
                "l/fastfilesystem"
            };

            // Destination: always copy into the app-private ROM directory with the canonical name
            // the core probes for (".../roms/FastFileSystem").
            File destDir = new File(ctx.getFilesDir(), "amiberry/roms");
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            File destFile = new File(destDir, "FastFileSystem");
            if (destFile.exists() && destFile.isFile() && destFile.length() > 0) {
                return destFile.getAbsolutePath();
            }

            for (String n : names) {
                String rel = relBase + n;
                if (!SafFileBridge.existsTreePath(treeUri, rel)) continue;

                int fd = SafFileBridge.openDetachedFdTreePath(treeUri, rel, "r");
                if (fd < 0) continue;

                ParcelFileDescriptor pfd = null;
                try {
                    pfd = ParcelFileDescriptor.adoptFd(fd);
                    try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                         FileOutputStream out = new FileOutputStream(destFile)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            out.write(buf, 0, r);
                        }
                        out.flush();
                    }
                    if (destFile.exists() && destFile.isFile() && destFile.length() > 0) {
                        logI("Imported FastFileSystem from rom_path into: " + destFile.getAbsolutePath());
                        return destFile.getAbsolutePath();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed importing FastFileSystem from rom_path: " + t);
                } finally {
                    try {
                        if (pfd != null) pfd.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean addHardfile2FromPrefs(
        List<String> args,
        SharedPreferences p,
        String keyEnabled,
        String keyPath,
        String keyDev,
        String keyRo,
        String defDev,
        int controllerUnit,
        String kickstartRomFile,
        String romPath
    ) {
        if (!p.getBoolean(keyEnabled, false)) return false;
        String path = p.getString(keyPath, null);
        if (path == null || path.trim().isEmpty()) return false;

        String dev = p.getString(keyDev, defDev);
        if (dev == null || dev.trim().isEmpty()) dev = defDev;
        final String devTrim = dev.trim();
        boolean ro = p.getBoolean(keyRo, false);

        // Minimal auto-detect: treat images with an RDB signature as RDB, otherwise raw hardfile.
        // Keep this conservative to avoid false positives (which can break non-RDB HDFs).
        final String originalPath = path.trim();
        boolean rdb = looksLikeRdbHardfile(originalPath);

        // If the path is a SAF content:// URI, translate it into a stable /proc/self/fd/<fd> path
        // and keep the fd open for the duration of the emulator run.
        ResolvedMediaPath resolved = resolveForCorePathIfNeeded(originalPath, /*wantWrite*/ !ro);
        String corePath = resolved.corePath;
        if (resolved.forcedReadOnly && !ro) {
            logI("SAF URI did not grant write access; forcing read-only for " + devTrim);
            ro = true;
        }

        // We emit hardfile2=... entries.
        // NOTE: Mixing filesystem2/hardfile2 with uaehfN hdf entries can cause uaehf hdf entries
        // to be ignored (see cfgfile_parse_filesys got_fs2_hdf2 handling). Using hardfile2 avoids this.
        // hardfile2 format (cfgfile_parse_newfilesys, type=1):
        //   hardfile2=<ro|rw>,<devname>:"<path>",<sectors>,<surfaces>,<reserved>,<blocksize>,<bootpri>,,<controller>
        //
        // Important:
        // - RDB-partitioned HDFs must be mounted in RDB mode (sectors/surfaces/reserved = 0,0,0)
        //   so the emulator will scan for partition tables.
        // - Non-RDB "filesystem/partition image" HDFs must NOT be mounted in RDB mode, otherwise
        //   you will get "failed, no supported partition tables detected" and the machine will
        //   drop to Kickstart.
        int sectors;
        int surfaces;
        int reserved;
        int blocksize = 512;
        // Default boot priorities:
        // - DH0 should win over other drives so a bootable filesystem HDF can boot.
        // - other DHx should not steal boot unless explicitly desired.
        int bootPri = "DH0".equalsIgnoreCase(devTrim) ? 10 : -128;

        // Use the UAE hardfile controller by default.
        // This exposes the drive via uaehf.device and requires the UAE Boot ROM to be enabled.
        final String controller = "uae" + controllerUnit;

        logI("HDF mount probe dev=" + devTrim
            + " path=" + (originalPath.startsWith("content://") ? "(SAF)" : originalPath)
            + " corePath=" + (originalPath.startsWith("content://") ? corePath : "(same)")
            + " rdb=" + rdb);

        if (rdb) {
            // RDB mode: lets the core scan for RDB/BABE partition tables.
            sectors = 0;
            surfaces = 0;
            reserved = 0;
            logI("Mounting hardfile2 " + devTrim + " as RDB (s=0 h=0 r=0) bs=" + blocksize + " bootpri=" + bootPri);
        } else {
            // Non-RDB hardfile: use the emulator defaults.
            // In UAE/Amiberry this corresponds to uci_set_defaults(rdb=false): sectors=32, surfaces=1, reserved=2.
            // Many AmigaDOS-formatted partitions expect reserved=2 (bootblocks). Using reserved=0 commonly triggers
            // the on-screen "Not a DOS disk" error.
            sectors = 32;
            surfaces = 1;
            reserved = 2;
            logI("Mounting hardfile2 " + devTrim + " as raw hardfile (s=" + sectors + " h=" + surfaces + " r=" + reserved + ") bs=" + blocksize + " bootpri=" + bootPri);
        }

        String hardfile2 =
            "hardfile2=" + (ro ? "ro" : "rw") + "," + devTrim + ":\"" + escapeForUaeQuoted(corePath) + "\"," +
                sectors + "," + surfaces + "," + reserved + "," + blocksize + "," + bootPri + ",," + controller;

        logI("Emitting -s " + hardfile2);
        args.add("-s");
        args.add(hardfile2);

        // Caller can use this to decide whether to force-enable the UAE Boot ROM.
        return true;
    }

    private static int probeReservedBlocksForDosBootblock(String path, int blocksize) {
        // Only probe regular file paths. For SAF content:// URIs we can’t reliably RandomAccessFile.
        if (path == null || path.trim().isEmpty()) return 0;
        String p = path.trim();
        if (p.startsWith("content://")) return 0;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(p, "r")) {
            // DOS\0 magic (0x44 0x4F 0x53 0x00) commonly appears at the start of a bootblock.
            if (hasDosMagicAt(raf, 0)) return 0;
            long off2 = (long) blocksize * 2L;
            if (hasDosMagicAt(raf, off2)) return 2;
        } catch (Throwable t) {
            logI("Reserved-block probe failed for " + p + ": " + t);
        }
        return 0;
    }

    private static boolean hasDosMagicAt(java.io.RandomAccessFile raf, long offset) throws java.io.IOException {
        if (raf == null) return false;
        if (offset < 0) return false;
        if (offset + 4 > raf.length()) return false;
        raf.seek(offset);
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        return b0 == 'D' && b1 == 'O' && b2 == 'S' && b3 == 0;
    }

    private static void addFilesystem2FromPrefs(
        List<String> args,
        SharedPreferences p,
        String keyEnabled,
        String keyPath,
        String keyDev,
        String keyVol,
        String keyRo,
        String keyBootPri,
        String defDev,
        String defVol,
        int defBootPri
    ) {
        if (!p.getBoolean(keyEnabled, false)) return;

        String path = p.getString(keyPath, null);
        if (path == null || path.trim().isEmpty()) return;

        String dev = p.getString(keyDev, defDev);
        if (dev == null || dev.trim().isEmpty()) dev = defDev;

        String vol = p.getString(keyVol, defVol);
        if (vol == null || vol.trim().isEmpty()) vol = defVol;

        boolean ro = p.getBoolean(keyRo, false);
        int bootPri = p.getInt(keyBootPri, defBootPri);

        // filesystem2 format (cfgfile_parse_newfilesys, type=0):
        //   <ro|rw>,<devname>:<volname>:"<rootdir>",<bootpri>
        args.add("-s");
        args.add(
            "filesystem2=" + (ro ? "ro" : "rw") + "," + dev.trim() + ":" + vol.trim() + ":\"" +
                escapeForUaeQuoted(path.trim()) + "\"," + bootPri
        );
    }

    private static final class AgsHardfileSpec {
        final String fileName;
        final String devName;
        final int controllerUnit;

        AgsHardfileSpec(String fileName, String devName, int controllerUnit) {
            this.fileName = fileName;
            this.devName = devName;
            this.controllerUnit = controllerUnit;
        }
    }

    private static final class AgsAutoMountResult {
        final boolean mountedAny;
        final boolean mountedHardfile;

        AgsAutoMountResult(boolean mountedAny, boolean mountedHardfile) {
            this.mountedAny = mountedAny;
            this.mountedHardfile = mountedHardfile;
        }
    }

    private static final AgsHardfileSpec[] AGS_HARDFILE_SPECS = new AgsHardfileSpec[] {
        new AgsHardfileSpec("Workbench.hdf", "DH0", 0),
        new AgsHardfileSpec("Work.hdf", "DH1", 8),
        new AgsHardfileSpec("Music.hdf", "DH2", 11),
        new AgsHardfileSpec("Media.hdf", "DH3", 9),
        new AgsHardfileSpec("AGS_Drive.hdf", "DH4", 1),
        new AgsHardfileSpec("Games.hdf", "DH5", 2),
        new AgsHardfileSpec("Premium.hdf", "DH6", 12),
        new AgsHardfileSpec("Emulators.hdf", "DH7", 10),
        new AgsHardfileSpec("Emulators2.hdf", "DH15", 15),
        new AgsHardfileSpec("WHD_Demos.hdf", "DH13", 6),
        new AgsHardfileSpec("WHD_Games.hdf", "DH14", 14),
    };

    private static String deriveAgsBasePathFromPrefs(SharedPreferences p) {
        if (p == null) return null;

        String explicit = p.getString(UaeOptionKeys.UAE_DRIVE_AGS_BASE_PATH, null);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }

        String harddrivePath = p.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null);
        if (harddrivePath == null || harddrivePath.trim().isEmpty()) return null;

        String candidate = harddrivePath.trim();
        if (candidate.startsWith("content://")) return candidate;

        try {
            File direct = new File(candidate);
            if (direct.exists() && direct.isDirectory()) {
                File rootMarker = new File(direct, "Workbench.hdf");
                if (rootMarker.exists() && rootMarker.isFile()) {
                    return direct.getAbsolutePath();
                }
                File child = new File(direct, "AGS_UAE");
                if (child.exists() && child.isDirectory()) {
                    File childMarker = new File(child, "Workbench.hdf");
                    if (childMarker.exists() && childMarker.isFile()) {
                        return child.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return candidate;
    }

    private static String findChildUnderAgsBase(String basePathOrTreeUri, String childName, boolean directory) {
        if (basePathOrTreeUri == null || basePathOrTreeUri.trim().isEmpty()) return null;
        String base = basePathOrTreeUri.trim();

        if (base.startsWith("content://")) {
            try {
                Context ctx = SDLActivity.getContext();
                if (ctx == null) return null;
                DocumentFile root = DocumentFile.fromTreeUri(ctx, Uri.parse(base));
                if (root == null || !root.isDirectory()) return null;
                DocumentFile[] kids = root.listFiles();
                if (kids == null) return null;
                for (DocumentFile kid : kids) {
                    if (kid == null) continue;
                    String name = kid.getName();
                    if (name == null || !name.equalsIgnoreCase(childName)) continue;
                    if (directory && kid.isDirectory()) return kid.getUri().toString();
                    if (!directory && kid.isFile()) return kid.getUri().toString();
                }
            } catch (Throwable t) {
                Log.w(TAG, "AGS tree child resolve failed: " + t);
            }
            return null;
        }

        try {
            File f = new File(base, childName);
            if (!f.exists()) return null;
            if (directory && !f.isDirectory()) return null;
            if (!directory && !f.isFile()) return null;
            return f.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AgsAutoMountResult addAgsAutoMountsFromPrefs(List<String> args, SharedPreferences p) {
        if (args == null || p == null) return new AgsAutoMountResult(false, false);
        if (!p.getBoolean(UaeOptionKeys.UAE_DRIVE_AGS_AUTOMOUNT_ENABLED, false)) {
            return new AgsAutoMountResult(false, false);
        }

        String agsBase = deriveAgsBasePathFromPrefs(p);
        if (agsBase == null || agsBase.trim().isEmpty()) {
            logI("AGS auto-mount enabled but no AGS base folder configured");
            return new AgsAutoMountResult(false, false);
        }

        logI("AGS auto-mount probe base=" + agsBase);

        boolean mountedAny = false;
        boolean mountedHardfile = false;

        for (AgsHardfileSpec spec : AGS_HARDFILE_SPECS) {
            String sourcePath = findChildUnderAgsBase(agsBase, spec.fileName, false);
            if (sourcePath == null || sourcePath.trim().isEmpty()) {
                logI("AGS missing " + spec.fileName + " under " + agsBase + " (skipping)");
                continue;
            }

            ResolvedMediaPath resolved = resolveForCorePathIfNeeded(sourcePath, /*wantWrite*/ true);
            String corePath = (resolved != null && resolved.corePath != null) ? resolved.corePath.trim() : sourcePath;
            boolean ro = (resolved != null && resolved.forcedReadOnly);

            String hardfile2 =
                "hardfile2=" + (ro ? "ro" : "rw") + "," + spec.devName + ":\"" + escapeForUaeQuoted(corePath) +
                    "\",0,0,0,512,0,,uae" + spec.controllerUnit;

            logI("AGS mount: -s " + hardfile2);
            args.add("-s");
            args.add(hardfile2);
            mountedAny = true;
            mountedHardfile = true;
        }

        String sharedPath = findChildUnderAgsBase(agsBase, "SHARED", true);
        if (sharedPath != null && !sharedPath.trim().isEmpty()) {
            if (sharedPath.startsWith("content://")) {
                logI("AGS SHARED is SAF tree URI; skipping filesystem2 mount because directory mounts require filesystem path");
            } else {
                String fs2 = "filesystem2=rw,DH9:SHARED:\"" + escapeForUaeQuoted(sharedPath) + "\",0";
                logI("AGS mount: -s " + fs2);
                args.add("-s");
                args.add(fs2);
                mountedAny = true;
            }
        }

        if (!mountedAny) {
            logI("AGS auto-mount enabled but no AGS media entries were found");
        }

        return new AgsAutoMountResult(mountedAny, mountedHardfile);
    }

    private static void addSettingArg(List<String> args, String key, String value) {
        if (args == null || key == null || value == null) return;
        args.add("-s");
        args.add(key + "=" + value);
    }

    private static void addCd32SafetyOverrides(List<String> args) {
        addSettingArg(args, "cd32cd", "1");
        addSettingArg(args, "cd32c2p", "true");
        addSettingArg(args, "cd32nvram", "true");
        addSettingArg(args, "cpu_24bit_addressing", "true");
        addSettingArg(args, "cpu_compatible", "true");
    }

    private static String joyEventForAction(int port, String action) {
        if (action == null || action.trim().isEmpty()) return null;
        String keyboardEvent = keyboardEventForAction(action);
        if (keyboardEvent != null) return keyboardEvent;
        // Amiberry's find_inputevent() matches against the display name defined
        // in inputevents.def (e.g. "Joy1 Up", "Joy1 Fire/Mouse1 Left Button").
        // Port 0 = joyport0 = Joy1 in Amiberry; Port 1 = joyport1 = Joy2.
        final String jn = (port == 0) ? "Joy1" : "Joy2";
        final String mn = (port == 0) ? "Mouse1" : "Mouse2";
        switch (action.trim().toUpperCase()) {
            case "UP":          return jn + " Up";
            case "DOWN":        return jn + " Down";
            case "LEFT":        return jn + " Left";
            case "RIGHT":       return jn + " Right";
            case "FIRE1":       return jn + " Fire/" + mn + " Left Button";
            case "FIRE2":       return jn + " 2nd Button/" + mn + " Right Button";
            case "FIRE3":       return jn + " 3rd Button/" + mn + " Middle Button";
            case "CD32_RED":    return jn + " CD32 Red";
            case "CD32_BLUE":   return jn + " CD32 Blue";
            case "CD32_GREEN":  return jn + " CD32 Green";
            case "CD32_YELLOW": return jn + " CD32 Yellow";
            case "CD32_PLAY":   return jn + " CD32 Play";
            case "CD32_RWD":    return jn + " CD32 RWD";
            case "CD32_FFW":    return jn + " CD32 FFW";
            default: return null;
        }
    }

    private static String keyboardEventForAction(String action) {
        if (action == null || action.trim().isEmpty()) return null;
        switch (action.trim().toUpperCase()) {
            case "KEY_SPACE": return "Space";
            case "KEY_RETURN": return "Return";
            case "KEY_ESC": return "ESC";
            case "KEY_TAB": return "Tab";
            case "KEY_BACKSPACE": return "Backspace";
            case "KEY_DEL": return "Del";
            case "KEY_CURSOR_UP": return "Cursor Up";
            case "KEY_CURSOR_DOWN": return "Cursor Down";
            case "KEY_CURSOR_LEFT": return "Cursor Left";
            case "KEY_CURSOR_RIGHT": return "Cursor Right";
            case "KEY_F1": return "F1";
            case "KEY_F2": return "F2";
            case "KEY_F3": return "F3";
            case "KEY_F4": return "F4";
            case "KEY_F5": return "F5";
            case "KEY_F6": return "F6";
            case "KEY_F7": return "F7";
            case "KEY_F8": return "F8";
            case "KEY_F9": return "F9";
            case "KEY_F10": return "F10";
            case "KEY_A": return "A";
            case "KEY_B": return "B";
            case "KEY_C": return "C";
            case "KEY_D": return "D";
            case "KEY_E": return "E";
            case "KEY_F": return "F";
            case "KEY_G": return "G";
            case "KEY_H": return "H";
            case "KEY_I": return "I";
            case "KEY_J": return "J";
            case "KEY_K": return "K";
            case "KEY_L": return "L";
            case "KEY_M": return "M";
            case "KEY_N": return "N";
            case "KEY_O": return "O";
            case "KEY_P": return "P";
            case "KEY_Q": return "Q";
            case "KEY_R": return "R";
            case "KEY_S": return "S";
            case "KEY_T": return "T";
            case "KEY_U": return "U";
            case "KEY_V": return "V";
            case "KEY_W": return "W";
            case "KEY_X": return "X";
            case "KEY_Y": return "Y";
            case "KEY_Z": return "Z";
            case "KEY_0": return "0";
            case "KEY_1": return "1";
            case "KEY_2": return "2";
            case "KEY_3": return "3";
            case "KEY_4": return "4";
            case "KEY_5": return "5";
            case "KEY_6": return "6";
            case "KEY_7": return "7";
            case "KEY_8": return "8";
            case "KEY_9": return "9";
            default: return null;
        }
    }

    private static void addJoyMappingOption(List<String> args, int port, String buttonSuffix, String actionValue) {
        String eventName = joyEventForAction(port, actionValue);
        if (eventName == null) return;
        String opt = "joyport" + port + "_amiberry_custom_none_" + buttonSuffix + "=" + eventName;
        Log.i(TAG, "Joy map arg: -s " + opt);
        args.add("-s");
        args.add(opt);
    }

    private static void addAndroidJoyMappingsFromPrefs(List<String> args,
                                                       SharedPreferences globalPrefs,
                                                       SharedPreferences perGamePrefs,
                                                       String gameId) {
        // Read effective per-button mappings (per-game override → global fallback).
        String mapA     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_A);
        String mapB     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_B);
        String mapX     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_X);
        String mapY     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_Y);
        String mapL1    = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_L1);
        String mapR1    = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_R1);
        String mapBack  = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_BACK);
        String mapStart = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_START);
        String mapDpadUp    = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP);
        String mapDpadDown  = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN);
        String mapDpadLeft  = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT);
        String mapDpadRight = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT);

        if (gameId != null) {
            Log.i(TAG, "Joy mapping: using per-game overrides for \"" + gameId + "\"");
        }

        java.util.ArrayList<Integer> targetPorts = new java.util.ArrayList<>();
        try {
            String p0 = globalPrefs.getString(UaeOptionKeys.UAE_INPUT_PORT0_MODE, "mouse");
            if (p0 != null && p0.trim().toLowerCase(java.util.Locale.ROOT).startsWith("joy")) {
                targetPorts.add(0);
            }
        } catch (Throwable ignored) {
        }
        try {
            String p1 = globalPrefs.getString(UaeOptionKeys.UAE_INPUT_PORT1_MODE, "joy0");
            if (p1 != null && p1.trim().toLowerCase(java.util.Locale.ROOT).startsWith("joy")) {
                targetPorts.add(1);
            }
        } catch (Throwable ignored) {
        }
        if (targetPorts.isEmpty()) {
            targetPorts.add(1);
        }

        for (int port : targetPorts) {
            addJoyMappingOption(args, port, "a", mapA);
            addJoyMappingOption(args, port, "b", mapB);
            addJoyMappingOption(args, port, "x", mapX);
            addJoyMappingOption(args, port, "y", mapY);
            addJoyMappingOption(args, port, "leftshoulder", mapL1);
            addJoyMappingOption(args, port, "rightshoulder", mapR1);
            addJoyMappingOption(args, port, "back", mapBack);
            addJoyMappingOption(args, port, "start", mapStart);
            addJoyMappingOption(args, port, "dpup", mapDpadUp);
            addJoyMappingOption(args, port, "dpdown", mapDpadDown);
            addJoyMappingOption(args, port, "dpleft", mapDpadLeft);
            addJoyMappingOption(args, port, "dpright", mapDpadRight);
        }
    }

    private File getExternalKickstartFile() {
        File root = Environment.getExternalStorageDirectory();
        return new File(root, EXTERNAL_KICKSTART_REL);
    }

    private File getInternalDf0DiskFile() {
        return new File(new File(getAmiberryBaseDir(), "disks"), DF0_DISK_NAME);
    }

    private File getExternalDf0DiskFile(String rel) {
        File root = Environment.getExternalStorageDirectory();
        return new File(root, rel);
    }

    private void configureKickstartSource() {
        // Prefer any Kickstart selected by the launcher (stored in our prefs).
        // This ensures Quickstart ROM selection wins over any legacy external kick.rom.
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String prefKick = p.getString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, null);
            if (prefKick != null && !prefKick.trim().isEmpty()) {
                File f = new File(prefKick.trim());
                if (f.exists() && f.canRead()) {
                    mKickstartRomFile = f.getAbsolutePath();
                    logI("Using Kickstart from prefs: " + mKickstartRomFile);
                    return;
                }
                Log.w(TAG, "Kickstart in prefs is missing or unreadable: " + prefKick);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read Kickstart from prefs: " + t);
        }

        File externalKick = getExternalKickstartFile();

        if (externalKick.exists()) {
            if (externalKick.canRead()) {
                mKickstartRomFile = externalKick.getAbsolutePath();
                logI("Using external Kickstart: " + mKickstartRomFile);
                return;
            }

            Log.w(TAG, "External Kickstart exists but is not readable: " + externalKick);

            // On Android 11+ scoped storage blocks raw /storage access without broad permissions.
            // We avoid requesting "All files access"; use SAF-based import/select instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                new AlertDialog.Builder(this)
                    .setTitle("Kickstart not accessible")
                    .setMessage(
                        "Found kick.rom at:\n" + externalKick.getAbsolutePath() + "\n\n" +
                            "But the app cannot read it due to Android storage restrictions.\n\n" +
                            "Use Kickstart Map (in the launcher) to select an accessible Kickstart, " +
                            "or remove/rename kick.rom to use bundled AROS instead.")
                    .setPositiveButton("Use AROS", (d, w) -> {
                        mKickstartRomFile = ":AROS";
                        provisionArosRomsIfPossible();
                    })
                    .setCancelable(false)
                    .show();
                return;
            }

            // Fallback: use bundled AROS when external kick is present but unreadable.
            mKickstartRomFile = ":AROS";
            provisionArosRomsIfPossible();
            return;
        }

        // Default: use bundled AROS.
        mKickstartRomFile = ":AROS";
        provisionArosRomsIfPossible();
    }

    private boolean copyAssetIfMissing(String assetPath, File destFile) {
        if (destFile.exists() && destFile.length() > 0) {
            return true;
        }
        ensureDir(destFile.getParentFile());
        AssetManager am = getAssets();
        try (InputStream in = am.open(assetPath); FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            // Asset not present or copy failed.
            android.util.Log.w(TAG, "Unable to copy asset " + assetPath + " to " + destFile + ": " + e.getMessage());
            return false;
        }
    }

    private void provisionArosRomsIfPossible() {
        // Only required when using :AROS.
        if (!":AROS".equals(mKickstartRomFile)) {
            logI("Skipping AROS ROM provisioning because kickstart_rom_file is set to: " + mKickstartRomFile);
            return;
        }

        File romsDir = getRomsDir();
        ensureDir(romsDir);

        logI("Provisioning AROS ROMs into: " + romsDir.getAbsolutePath());

        // Try to copy from assets if you bundled them there.
        copyAssetIfMissing(ASSET_AROS_ROM, new File(romsDir, AROS_ROM));
        copyAssetIfMissing(ASSET_AROS_EXT, new File(romsDir, AROS_EXT));

        // If still missing, the embedding app (Flutter) must provide them.
        File rom = new File(romsDir, AROS_ROM);
        File ext = new File(romsDir, AROS_EXT);
        if (!rom.exists() || !ext.exists()) {
            Log.e(TAG,
                "Missing AROS ROMs. Expected files in: " + romsDir.getAbsolutePath() +
                    " (" + AROS_ROM + ", " + AROS_EXT + "). " +
                    "Bundle them as assets/roms/* or copy them there from Flutter before launching.");

            // Fail fast with a visible message, otherwise SDL shows a blank surface.
            new AlertDialog.Builder(this)
                .setTitle("Missing AROS ROMs")
                .setMessage(
                    "Copy these files to:\n" + romsDir.getAbsolutePath() + "\n\n" +
                        "- " + AROS_ROM + "\n" +
                        "- " + AROS_EXT + "\n\n" +
                        "Or bundle them into assets/roms/ in the APK.")
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setCancelable(false)
                .show();
        }
    }

    @Override
    protected String[] getLibraries() {
        // Load the stub plugin early so any native dlopen("libpenguin.so") resolves.
        return new String[]{
                "SDL2",
                "penguin",
                "main"
        };
    }

    private void showSaveStateDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Save States");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        b.setView(scroll);

        for (int i = 0; i < 10; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));

            TextView label = new TextView(this);
            label.setText("Slot " + slot);
            label.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            label.setLayoutParams(lp);

            Button save = new Button(this);
            save.setText("Save");
            save.setOnClickListener(v -> {
                nativeSaveState(slot, 1);
                android.widget.Toast.makeText(this, "Saving to Slot " + slot + "...", android.widget.Toast.LENGTH_SHORT).show();
            });

            Button load = new Button(this);
            load.setText("Load");
            load.setOnClickListener(v -> {
                nativeSaveState(slot, 0);
                android.widget.Toast.makeText(this, "Loading Slot " + slot + "...", android.widget.Toast.LENGTH_SHORT).show();
            });

            row.addView(label);
            row.addView(save);
            row.addView(load);
            layout.addView(row);
        }

        b.setNegativeButton("Close", null);
        b.show();
    }

    public ViewGroup getGuiLayer() {
        return mGuiLayer;
    }

    private void styleOverlayButton(Button b) {
        if (b == null) return;
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);

        // Android Buttons have large default minimum sizes; override to keep the overlay compact.
        try {
            b.setIncludeFontPadding(false);
        } catch (Throwable ignored) {
        }
        try {
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
        } catch (Throwable ignored) {
        }

        try {
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        } catch (Throwable ignored) {
        }

        float d = getResources().getDisplayMetrics().density;
        int padH = (int) (8 * d);
        int padV = (int) (4 * d);
        b.setPadding(padH, padV, padH, padV);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xAA1E88E5); // semi-transparent blue
        bg.setCornerRadius(10 * d);
        b.setBackground(bg);
    }

    private static int normalizeVideoAspectMode(int mode) {
        return mode == 0 ? 0 : 1;
    }

    private int getSavedVideoAspectMode() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            return normalizeVideoAspectMode(p.getInt(UaeOptionKeys.UAE_VIDEO_ASPECT_MODE, 1));
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read saved video aspect mode: " + t);
            return 1;
        }
    }

    private void saveVideoAspectMode(int mode) {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            p.edit().putInt(UaeOptionKeys.UAE_VIDEO_ASPECT_MODE, normalizeVideoAspectMode(mode)).apply();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to save video aspect mode: " + t);
        }
    }

    private void applyVideoAspectModeRuntime(int mode) {
        final int normalized = normalizeVideoAspectMode(mode);
        try {
            nativeSetStretchToFill(normalized == 1);
            nativeSetVideoAspectMode(normalized);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply runtime video aspect mode: " + t);
        }
        saveVideoAspectMode(normalized);
    }

    private void updateAspectButtonLabel(Button button, int mode) {
        if (button == null) return;
        button.setText(mode == 0 ? "4:3" : "16:9");
    }

    private boolean isVirtualJoystickEnabledPref() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            String source = p.getString(UaeOptionKeys.UAE_INPUT_CONTROLLER_SOURCE, "external");
            return "virtual".equalsIgnoreCase(source);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read virtual joystick pref: " + t);
            return false;
        }
    }

    private void updateVirtualJoystickButtonLabel(Button button, boolean enabled) {
        if (button == null) return;
        button.setText("🕹");
        button.setAlpha(enabled ? 1.0f : 0.55f);
    }

    private void applyVirtualJoystickRuntime(boolean enabled, boolean persist) {
        try {
            if (mVirtualJoystick != null) {
                mVirtualJoystick.setVisibility(enabled ? View.VISIBLE : View.GONE);
            }
            nativeSetVirtualJoystickEnabled(enabled);
            if (persist) {
                SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
                p.edit().putString(UaeOptionKeys.UAE_INPUT_CONTROLLER_SOURCE, enabled ? "virtual" : "external").apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply virtual joystick runtime state: " + t);
        }
    }

    private boolean isCdOnlyQuickstartModel() {
        try {
            if (mQsModel == null) return false;
            String m = mQsModel.trim().toUpperCase(java.util.Locale.ROOT);
            return "CD32".equals(m) || "CDTV".equals(m) || "ALG".equals(m) || "ARCADIA".equals(m);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasMediaPath(String path) {
        return path != null && !path.trim().isEmpty();
    }

    private boolean getFloppySoundEnabledPref() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            return p.getBoolean(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED, true);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read floppy sound enabled pref: " + t);
            return true;
        }
    }

    private int getFloppySoundVolumePref() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            int attenuation = p.getInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK, 33);
            if (attenuation < 0) attenuation = 0;
            if (attenuation > 100) attenuation = 100;
            int loudness = 100 - attenuation;
            if (loudness < 0) loudness = 0;
            if (loudness > 100) loudness = 100;
            return loudness;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read floppy sound volume pref: " + t);
            return 33;
        }
    }

    private int getEmulatorSoundVolumePref() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            int attenuation;
            if (p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_MASTER)) {
                attenuation = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_MASTER, 0);
            } else {
                attenuation = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA, 0);
            }
            if (attenuation < 0) attenuation = 0;
            if (attenuation > 100) attenuation = 100;
            int loudness = 100 - attenuation;
            if (loudness < 0) loudness = 0;
            if (loudness > 100) loudness = 100;
            return loudness;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read emulator sound volume pref: " + t);
            return 100;
        }
    }

    private void setFloppySoundPrefs(boolean enabled, int volume) {
        int loudness = Math.max(0, Math.min(100, volume));
        int attenuation = 100 - loudness;
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            p.edit()
                .putBoolean(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED, enabled)
                .putInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK, attenuation)
                .putInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY, attenuation)
                .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to save floppy sound prefs: " + t);
        }
    }

    private void applyRuntimeFloppySound(boolean enabled, int volume) {
        int loudness = Math.max(0, Math.min(100, volume));
        int runtimePercent = enabled ? loudness : 0;
        try {
            nativeSetFloppySoundVolumePercent(runtimePercent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply runtime floppy sound settings: " + t);
        }
    }

    private void setEmulatorSoundPrefs(int volume) {
        int loudness = Math.max(0, Math.min(100, volume));
        int attenuation = 100 - loudness;
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            p.edit().putInt(UaeOptionKeys.UAE_SOUND_VOLUME_MASTER, attenuation).apply();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to save emulator sound prefs: " + t);
        }
    }

    private void applyRuntimeEmulatorSound(int volume) {
        int loudness = Math.max(0, Math.min(100, volume));
        try {
            nativeSetEmulatorSoundVolumePercent(loudness);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply runtime emulator sound settings: " + t);
        }
    }

    private void updateFloppySoundButtonLabel(Button button, boolean enabled) {
        if (button == null) return;
        button.setText(enabled ? "🔊" : "🔇");
    }

    private void showFloppySoundDialog(Button btnFloppySoundQuick) {
        final boolean originalEnabled = getFloppySoundEnabledPref();
        final int originalDriveVolume = getFloppySoundVolumePref();
        final int originalEmuVolume = getEmulatorSoundVolumePref();

        final Switch swEnabled = new Switch(this);
        swEnabled.setText("Drive sounds enabled");
        swEnabled.setChecked(originalEnabled);

        final TextView tvLevel = new TextView(this);
        tvLevel.setTextColor(0xFFFFFFFF);
        tvLevel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);

        final SeekBar sbLevel = new SeekBar(this);
        sbLevel.setMax(100);
        int currentVolume = originalDriveVolume;
        sbLevel.setProgress(currentVolume);

        final Runnable updateDriveLevelLabel = () -> {
            int progress = sbLevel.getProgress();
            if (swEnabled.isChecked()) {
                tvLevel.setText("Drive sound level: " + progress + "%");
            } else {
                tvLevel.setText("Drive sound level: 0% (muted)");
            }
        };

        updateDriveLevelLabel.run();
        sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDriveLevelLabel.run();
                if (fromUser) {
                    applyRuntimeFloppySound(swEnabled.isChecked(), progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateDriveLevelLabel.run();
            applyRuntimeFloppySound(isChecked, sbLevel.getProgress());
        });

        final TextView tvEmuLevel = new TextView(this);
        tvEmuLevel.setTextColor(0xFFFFFFFF);
        tvEmuLevel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);

        final SeekBar sbEmuLevel = new SeekBar(this);
        sbEmuLevel.setMax(100);
        int currentEmuVolume = originalEmuVolume;
        sbEmuLevel.setProgress(currentEmuVolume);
        if (currentEmuVolume <= 0) {
            tvEmuLevel.setText("Emulator sound level: 0% (muted)");
        } else {
            tvEmuLevel.setText("Emulator sound level: " + currentEmuVolume + "%");
        }
        sbEmuLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress <= 0) {
                    tvEmuLevel.setText("Emulator sound level: 0% (muted)");
                } else {
                    tvEmuLevel.setText("Emulator sound level: " + progress + "%");
                }
                if (fromUser) {
                    applyRuntimeEmulatorSound(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.addView(swEnabled);
        root.addView(tvLevel);
        root.addView(sbLevel);
        root.addView(tvEmuLevel);
        root.addView(sbEmuLevel);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Drive Sounds")
            .setView(root)
            .setNegativeButton("Cancel", (d, w) -> {
                applyRuntimeFloppySound(originalEnabled, originalDriveVolume);
                applyRuntimeEmulatorSound(originalEmuVolume);
            })
            .setPositiveButton("Apply", (dlg, which) -> {
                boolean enabled = swEnabled.isChecked();
                int driveVolume = sbLevel.getProgress();
                int emuVolume = sbEmuLevel.getProgress();
                setFloppySoundPrefs(enabled, driveVolume);
                applyRuntimeFloppySound(enabled, driveVolume);
                setEmulatorSoundPrefs(emuVolume);
                applyRuntimeEmulatorSound(emuVolume);
                updateFloppySoundButtonLabel(btnFloppySoundQuick, enabled);
                android.widget.Toast.makeText(this, "Drive sound settings applied", android.widget.Toast.LENGTH_SHORT).show();
            })
            .create();
        dialog.show();
    }

    private void addInEmulatorMenuButton() {
        if (mGuiLayer == null) return;

        final float d = getResources().getDisplayMetrics().density;
        final int marginH = (int) (12 * d);
        final int marginV = (int) (8 * d);
        final int gap = (int) (8 * d);
        final int btnPaddingH = (int) (10 * d);
        final int btnPaddingV = (int) (6 * d);

        // Main overlay container
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setClipToPadding(false);
        overlay.setClipChildren(false);
        overlay.setClickable(false);
        overlay.setFocusable(false);

        // Hamburger button - positioned at bottom-left
        final Button btnHamburger = new Button(this);
        btnHamburger.setText("☰");
        styleOverlayButton(btnHamburger);
        btnHamburger.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        btnHamburger.setPadding(btnPaddingH, btnPaddingV, btnPaddingH, btnPaddingV);

        final Button btnKeyboard = new Button(this);
        btnKeyboard.setText("⌨");
        styleOverlayButton(btnKeyboard);
        btnKeyboard.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        btnKeyboard.setPadding(btnPaddingH, btnPaddingV, btnPaddingH, btnPaddingV);

        final Button btnVirtualJoyToggle = new Button(this);
        styleOverlayButton(btnVirtualJoyToggle);
        btnVirtualJoyToggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        btnVirtualJoyToggle.setPadding(btnPaddingH, btnPaddingV, btnPaddingH, btnPaddingV);
        updateVirtualJoystickButtonLabel(btnVirtualJoyToggle, isVirtualJoystickEnabledPref());

        // Center menu panel (initially hidden)
        final LinearLayout menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setGravity(android.view.Gravity.CENTER);
        menuPanel.setVisibility(View.GONE);
        menuPanel.setAlpha(0.95f);

        // Semi-transparent background for menu panel
        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
        panelBg.setColor(0xE0202020); // Dark semi-transparent
        panelBg.setCornerRadius(16 * d);
        menuPanel.setBackground(panelBg);
        menuPanel.setPadding((int)(20*d), (int)(16*d), (int)(20*d), (int)(16*d));

        // Menu title
        final TextView menuTitle = new TextView(this);
        menuTitle.setText("EMULATOR CONTROLS");
        menuTitle.setTextColor(0xFF4CAF50);
        menuTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        menuTitle.setPadding(0, 0, 0, (int)(12*d));
        menuTitle.setGravity(android.view.Gravity.CENTER);

        // Create larger styled menu buttons with color coding
        final Button btnMenu = createMenuButton("📋  Menu", d, 0xFF1565C0);      // Blue
        final Button btnStates = createMenuButton("💿  Save States", d, 0xFFF57C00); // Orange
        final Button btnInputMap = createMenuButton("🎮  Input Mapping", d, 0xFF2E7D32); // Green
        final Button btnReset = createMenuButton("🔄  Soft Reset", d, 0xFFC62828);    // Red
        final Button btnRestart = createMenuButton("🔁  Cold Restart", d, 0xFF7B1FA2); // Deep purple
        int runtimeAspectMode = getSavedVideoAspectMode();
        try {
            runtimeAspectMode = normalizeVideoAspectMode(nativeGetVideoAspectMode());
        } catch (Throwable ignored) {}

        final Button btnAspectQuick = new Button(this);
        styleOverlayButton(btnAspectQuick);
        btnAspectQuick.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btnAspectQuick.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        updateAspectButtonLabel(btnAspectQuick, runtimeAspectMode);

        final Button btnWhdLhaQuick = new Button(this);
        styleOverlayButton(btnWhdLhaQuick);
        btnWhdLhaQuick.setText("LHA");
        btnWhdLhaQuick.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btnWhdLhaQuick.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        btnWhdLhaQuick.setVisibility(View.GONE);

        final Button btnHdQuick = new Button(this);
        styleOverlayButton(btnHdQuick);
        btnHdQuick.setText("HDF");
        btnHdQuick.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btnHdQuick.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        btnHdQuick.setVisibility(View.GONE);

        final Button btnFloppySoundQuick = new Button(this);
        styleOverlayButton(btnFloppySoundQuick);
        btnFloppySoundQuick.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btnFloppySoundQuick.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        updateFloppySoundButtonLabel(btnFloppySoundQuick, getFloppySoundEnabledPref());

        // RTG status row
        final LinearLayout rtgRow = new LinearLayout(this);
        rtgRow.setOrientation(LinearLayout.HORIZONTAL);
        rtgRow.setGravity(android.view.Gravity.CENTER);
        rtgRow.setPadding(0, 0, 0, 0);

        final TextView rtgIndicator = new TextView(this);
        rtgIndicator.setText("RTG");
        rtgIndicator.setTextColor(0xFFFFFFFF);
        rtgIndicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        rtgIndicator.setPadding((int)(12*d), (int)(6*d), (int)(12*d), (int)(6*d));
        android.graphics.drawable.GradientDrawable rtgBg = new android.graphics.drawable.GradientDrawable();
        rtgBg.setColor(0xFF4CAF50);
        rtgBg.setCornerRadius(8 * d);
        rtgIndicator.setBackground(rtgBg);
        rtgIndicator.setVisibility(View.GONE);

        final TextView rendererIndicator = new TextView(this);
        rendererIndicator.setText("DDG");
        rendererIndicator.setTextColor(0xFFFFFFFF);
        rendererIndicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        rendererIndicator.setPadding((int)(12*d), (int)(6*d), (int)(12*d), (int)(6*d));
        android.graphics.drawable.GradientDrawable rendererBg = new android.graphics.drawable.GradientDrawable();
        rendererBg.setColor(0xFF455A64);
        rendererBg.setCornerRadius(8 * d);
        rendererIndicator.setBackground(rendererBg);
        rendererIndicator.setVisibility(View.VISIBLE);
        rendererIndicator.setOnClickListener(v -> showRendererInfoDialog());

        final TextView df0Indicator = new TextView(this);
        df0Indicator.setText("DF0");
        df0Indicator.setTextColor(0xFFFFFFFF);
        df0Indicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        df0Indicator.setPadding((int)(12*d), (int)(6*d), (int)(12*d), (int)(6*d));
        android.graphics.drawable.GradientDrawable df0Bg = new android.graphics.drawable.GradientDrawable();
        df0Bg.setColor(0xFF2196F3);
        df0Bg.setCornerRadius(8 * d);
        df0Indicator.setBackground(df0Bg);

        final TextView df1Indicator = new TextView(this);
        df1Indicator.setText("DF1");
        df1Indicator.setTextColor(0xFFFFFFFF);
        df1Indicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        df1Indicator.setPadding((int)(12*d), (int)(6*d), (int)(12*d), (int)(6*d));
        android.graphics.drawable.GradientDrawable df1Bg = new android.graphics.drawable.GradientDrawable();
        df1Bg.setColor(0xFFFF9800);
        df1Bg.setCornerRadius(8 * d);
        df1Indicator.setBackground(df1Bg);
        df1Indicator.setVisibility(hasMediaPath(mDf1DiskImagePath) ? View.VISIBLE : View.GONE);

        final TextView df2Indicator = new TextView(this);
        df2Indicator.setText("DF2");
        df2Indicator.setTextColor(0xFFFFFFFF);
        df2Indicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        df2Indicator.setPadding((int)(12*d), (int)(6*d), (int)(12*d), (int)(6*d));
        android.graphics.drawable.GradientDrawable df2Bg = new android.graphics.drawable.GradientDrawable();
        df2Bg.setColor(0xFF8E24AA);
        df2Bg.setCornerRadius(8 * d);
        df2Indicator.setBackground(df2Bg);
        df2Indicator.setVisibility(hasMediaPath(mDf2DiskImagePath) ? View.VISIBLE : View.GONE);

        final TextView df3Indicator = new TextView(this);
        df3Indicator.setText("DF3");
        df3Indicator.setTextColor(0xFFFFFFFF);
        df3Indicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        df3Indicator.setPadding((int)(12*d), (int)(6*d), (int)(12*d), (int)(6*d));
        android.graphics.drawable.GradientDrawable df3Bg = new android.graphics.drawable.GradientDrawable();
        df3Bg.setColor(0xFF00897B);
        df3Bg.setCornerRadius(8 * d);
        df3Indicator.setBackground(df3Bg);
        df3Indicator.setVisibility(hasMediaPath(mDf3DiskImagePath) ? View.VISIBLE : View.GONE);

        rtgRow.addView(rtgIndicator);
        rtgRow.addView(df0Indicator);
        android.widget.LinearLayout.LayoutParams lpIndicator = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpIndicator.leftMargin = (int)(8*d);
        rtgRow.addView(df1Indicator, lpIndicator);
        android.widget.LinearLayout.LayoutParams lpIndicator2 = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpIndicator2.leftMargin = (int)(8*d);
        rtgRow.addView(df2Indicator, lpIndicator2);
        android.widget.LinearLayout.LayoutParams lpIndicator3 = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpIndicator3.leftMargin = (int)(8*d);
        rtgRow.addView(df3Indicator, lpIndicator3);
        android.widget.LinearLayout.LayoutParams lpSoundQuick = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpSoundQuick.leftMargin = (int)(8*d);
        rtgRow.addView(btnFloppySoundQuick, lpSoundQuick);
        android.widget.LinearLayout.LayoutParams lpAspectQuick = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpAspectQuick.leftMargin = (int)(8*d);
        rtgRow.addView(btnAspectQuick, lpAspectQuick);
        android.widget.LinearLayout.LayoutParams lpWhdQuick = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpWhdQuick.leftMargin = (int)(8*d);
        rtgRow.addView(btnWhdLhaQuick, lpWhdQuick);
        android.widget.LinearLayout.LayoutParams lpHdQuick = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpHdQuick.leftMargin = (int)(8*d);
        rtgRow.addView(btnHdQuick, lpHdQuick);

        // Build menu panel
        menuPanel.addView(menuTitle);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpBtn.topMargin = gap;
        menuPanel.addView(btnMenu, lpBtn);
        menuPanel.addView(btnStates, lpBtn);
        menuPanel.addView(btnInputMap, lpBtn);
        menuPanel.addView(btnReset, lpBtn);
        menuPanel.addView(btnRestart, lpBtn);

        // Position hamburger at bottom-left
        final FrameLayout.LayoutParams lpHamburger = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lpHamburger.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        lpHamburger.leftMargin = marginH;
        lpHamburger.bottomMargin = marginV;
        overlay.addView(btnHamburger, lpHamburger);

        // Position keyboard button at bottom-right
        final FrameLayout.LayoutParams lpKeyboard = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lpKeyboard.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lpKeyboard.rightMargin = marginH;
        lpKeyboard.bottomMargin = marginV;
        overlay.addView(btnKeyboard, lpKeyboard);

        // Position virtual-joystick toggle above keyboard button (icon-only)
        final FrameLayout.LayoutParams lpVirtualJoyToggle = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lpVirtualJoyToggle.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lpVirtualJoyToggle.rightMargin = marginH;
        lpVirtualJoyToggle.bottomMargin = marginV + (int) (52 * d);
        overlay.addView(btnVirtualJoyToggle, lpVirtualJoyToggle);

        // Position status indicators at top-right so they stay visible while running
        final FrameLayout.LayoutParams lpIndicators = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lpIndicators.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lpIndicators.topMargin = marginV;
        lpIndicators.rightMargin = marginH;
        overlay.addView(rtgRow, lpIndicators);

        // Position renderer badge at top-left
        final FrameLayout.LayoutParams lpRendererIndicator = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lpRendererIndicator.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lpRendererIndicator.topMargin = marginV;
        lpRendererIndicator.leftMargin = marginH;
        overlay.addView(rendererIndicator, lpRendererIndicator);

        // Position menu panel in center
        final FrameLayout.LayoutParams lpMenu = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lpMenu.gravity = android.view.Gravity.CENTER;
        overlay.addView(menuPanel, lpMenu);

        // Handle window insets
        if (Build.VERSION.SDK_INT >= 20) {
            overlay.setOnApplyWindowInsetsListener((v, insets) -> {
                int insetBottom = insets.getSystemWindowInsetBottom();
                int insetLeft = insets.getSystemWindowInsetLeft();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    insetBottom = Math.max(insetBottom, insets.getDisplayCutout().getSafeInsetBottom());
                    insetLeft = Math.max(insetLeft, insets.getDisplayCutout().getSafeInsetLeft());
                }
                int insetTop = insets.getSystemWindowInsetTop();
                int insetRight = insets.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    insetTop = Math.max(insetTop, insets.getDisplayCutout().getSafeInsetTop());
                    insetRight = Math.max(insetRight, insets.getDisplayCutout().getSafeInsetRight());
                }
                lpHamburger.leftMargin = marginH + insetLeft;
                lpHamburger.bottomMargin = marginV + insetBottom;
                btnHamburger.setLayoutParams(lpHamburger);
                lpKeyboard.rightMargin = marginH + insetRight;
                lpKeyboard.bottomMargin = marginV + insetBottom;
                btnKeyboard.setLayoutParams(lpKeyboard);
                lpVirtualJoyToggle.rightMargin = marginH + insetRight;
                lpVirtualJoyToggle.bottomMargin = marginV + insetBottom + (int) (52 * d);
                btnVirtualJoyToggle.setLayoutParams(lpVirtualJoyToggle);
                lpIndicators.topMargin = marginV + insetTop;
                lpIndicators.rightMargin = marginH + insetRight;
                rtgRow.setLayoutParams(lpIndicators);
                lpRendererIndicator.topMargin = marginV + insetTop;
                lpRendererIndicator.leftMargin = marginH + insetLeft;
                rendererIndicator.setLayoutParams(lpRendererIndicator);
                return insets;
            });
            overlay.requestApplyInsets();
        }

        // Toggle menu panel on hamburger click
        btnHamburger.setOnClickListener(v -> {
            if (menuPanel.getVisibility() == View.VISIBLE) {
                menuPanel.setVisibility(View.GONE);
                btnHamburger.setText("☰");
                // Resume emulator when menu closes
                mPausedByOverlay = false;
                try { SDLActivity.nativeResume(); } catch (Throwable ignored) {}
            } else {
                menuPanel.setVisibility(View.VISIBLE);
                btnHamburger.setText("✕");
                // Pause emulator when menu opens
                mPausedByOverlay = true;
                try { SDLActivity.nativePause(); } catch (Throwable ignored) {}
            }
        });

        // Menu button actions
        btnMenu.setOnClickListener(v -> {
            menuPanel.setVisibility(View.GONE);
            btnHamburger.setText("☰");
            try {
                Intent i = new Intent(this, BootstrapActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                i.putExtra(BootstrapActivity.EXTRA_FROM_EMULATOR_MENU, true);
                i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF0_PATH, mDf0DiskImagePath);
                i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF1_PATH, mDf1DiskImagePath);
                i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF2_PATH, mDf2DiskImagePath);
                i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF3_PATH, mDf3DiskImagePath);
                startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to open Quickstart: " + t);
            }
        });

        btnKeyboard.setOnClickListener(v -> {
            try {
                nativeToggleVkbd();
                v.postDelayed(() -> {
                    try {
                        boolean active = nativeIsVkbdActive();
                        if (active && mVkbdTouchInterceptor != null) {
                            mVkbdTouchInterceptor.bringToFront();
                        }
                        if (mInEmuOverlay != null) {
                            mInEmuOverlay.bringToFront();
                        }
                        Log.i(TAG, "VKBD toggle requested; active=" + active);
                        android.widget.Toast.makeText(this,
                            active ? "Virtual keyboard ON" : "Virtual keyboard OFF",
                            android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }, 120);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to toggle virtual keyboard: " + t);
            }
        });

        btnStates.setOnClickListener(v -> {
            menuPanel.setVisibility(View.GONE);
            btnHamburger.setText("☰");
            showSaveStateDialog();
        });

        btnInputMap.setOnClickListener(v -> {
            menuPanel.setVisibility(View.GONE);
            btnHamburger.setText("☰");
            mPausedByOverlay = false;
            try { SDLActivity.nativeResume(); } catch (Throwable ignored) {}
            try {
                Intent i = new Intent(this, JoyMappingActivity.class);
                String gid = getGameIdentifier();
                if (gid != null) {
                    i.putExtra(JoyMappingActivity.EXTRA_GAME_IDENTIFIER, gid);
                }
                startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to open Input Mapping: " + t);
            }
        });

        btnVirtualJoyToggle.setOnClickListener(v -> {
            boolean enabled = !isVirtualJoystickEnabledPref();
            applyVirtualJoystickRuntime(enabled, true);
            updateVirtualJoystickButtonLabel(btnVirtualJoyToggle, enabled);
            android.widget.Toast.makeText(this,
                enabled ? "Virtual joystick ON" : "Virtual joystick OFF",
                android.widget.Toast.LENGTH_SHORT).show();
        });

        btnReset.setOnClickListener(v -> {
            menuPanel.setVisibility(View.GONE);
            btnHamburger.setText("☰");
            mPausedByOverlay = false;
            try { SDLActivity.nativeResume(); } catch (Throwable ignored) {}
            try {
                applyRuntimeFloppyPathsAndReset();
                android.widget.Toast.makeText(this, "Soft reset complete", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Log.w(TAG, "Unable to reset emulator: " + t);
            }
        });

        btnRestart.setOnClickListener(v -> {
            menuPanel.setVisibility(View.GONE);
            btnHamburger.setText("☰");
            mPausedByOverlay = false;
            try { SDLActivity.nativeResume(); } catch (Throwable ignored) {}
            try {
                new AlertDialog.Builder(this)
                    .setTitle("Return to launcher?")
                    .setMessage("This stops the current emulation session and returns to setup.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Return", (dialog, which) -> coldRestartToSetupGui())
                    .show();
            } catch (Throwable t) {
                Log.w(TAG, "Restart confirmation failed, continuing with restart: " + t);
                coldRestartToSetupGui();
            }
        });

        btnAspectQuick.setOnClickListener(v -> {
            int current = getSavedVideoAspectMode();
            try {
                current = normalizeVideoAspectMode(nativeGetVideoAspectMode());
            } catch (Throwable ignored) {}
            final int next = current == 0 ? 1 : 0;
            applyVideoAspectModeRuntime(next);
            updateAspectButtonLabel(btnAspectQuick, next);
            android.widget.Toast.makeText(this,
                next == 0 ? "Video aspect: 4:3" : "Video aspect: 16:9",
                android.widget.Toast.LENGTH_SHORT).show();
        });

        btnWhdLhaQuick.setOnLongClickListener(v -> {
            openMediaSwapperFromOverlay(BootstrapActivity.MEDIA_SECTION_HD);
            return true;
        });
        btnWhdLhaQuick.setLongClickable(true);

        btnWhdLhaQuick.setOnClickListener(v ->
            android.widget.Toast.makeText(this, "Long press LHA for media selector", android.widget.Toast.LENGTH_SHORT).show());

        btnHdQuick.setOnLongClickListener(v -> {
            openMediaSwapperFromOverlay(BootstrapActivity.MEDIA_SECTION_HD);
            return true;
        });
        btnHdQuick.setLongClickable(true);

        btnHdQuick.setOnClickListener(v ->
            android.widget.Toast.makeText(this, "Long press HDF for media selector", android.widget.Toast.LENGTH_SHORT).show());

        View.OnLongClickListener dfLongPressListener = v -> {
            openMediaSwapperFromOverlay(BootstrapActivity.MEDIA_SECTION_DF);
            return true;
        };
        df0Indicator.setOnLongClickListener(dfLongPressListener);
        df1Indicator.setOnLongClickListener(dfLongPressListener);
        df2Indicator.setOnLongClickListener(dfLongPressListener);
        df3Indicator.setOnLongClickListener(dfLongPressListener);
        df0Indicator.setLongClickable(true);
        df1Indicator.setLongClickable(true);
        df2Indicator.setLongClickable(true);
        df3Indicator.setLongClickable(true);
        df0Indicator.setOnClickListener(v -> android.widget.Toast.makeText(this, "Long press DF for media selector", android.widget.Toast.LENGTH_SHORT).show());
        df1Indicator.setOnClickListener(v -> android.widget.Toast.makeText(this, "Long press DF for media selector", android.widget.Toast.LENGTH_SHORT).show());
        df2Indicator.setOnClickListener(v -> android.widget.Toast.makeText(this, "Long press DF for media selector", android.widget.Toast.LENGTH_SHORT).show());
        df3Indicator.setOnClickListener(v -> android.widget.Toast.makeText(this, "Long press DF for media selector", android.widget.Toast.LENGTH_SHORT).show());

        btnFloppySoundQuick.setOnClickListener(v -> showFloppySoundDialog(btnFloppySoundQuick));

        // Store references
        mRtgIndicator = rtgIndicator;
        mRendererIndicator = rendererIndicator;
        mDf0Indicator = df0Indicator;
        mDf1Indicator = df1Indicator;
        mDf2Indicator = df2Indicator;
        mDf3Indicator = df3Indicator;
        mDf0IndicatorBg = df0Bg;
        mDf1IndicatorBg = df1Bg;
        mDf2IndicatorBg = df2Bg;
        mDf3IndicatorBg = df3Bg;
        mFloppySoundQuickButton = btnFloppySoundQuick;
        mAspectQuickButton = btnAspectQuick;
        mWhdLhaQuickButton = btnWhdLhaQuick;
        mHdQuickButton = btnHdQuick;
        mMenuPanel = menuPanel;
        mBtnHamburger = btnHamburger;

        FrameLayout.LayoutParams lpOverlay = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        mGuiLayer.addView(overlay, lpOverlay);
        mInEmuOverlay = overlay;
    }

    private Button createMenuButton(String text, float d, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setPadding((int)(20*d), (int)(12*d), (int)(20*d), (int)(12*d));
        btn.setMinWidth((int)(200 * d));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(12 * d);
        btn.setBackground(bg);

        return btn;
    }

    // References to status indicators for runtime updates
    private TextView mRtgIndicator;
    private TextView mRendererIndicator;
    private TextView mLedIndicator;
    private TextView mDf0Indicator;
    private TextView mDf1Indicator;
    private TextView mDf2Indicator;
    private TextView mDf3Indicator;
    private LinearLayout mMenuPanel;
    private Button mBtnHamburger;
    private Button mFloppySoundQuickButton;
    private Button mAspectQuickButton;
    private Button mWhdLhaQuickButton;
    private Button mHdQuickButton;
    private android.graphics.drawable.GradientDrawable mDf0IndicatorBg;
    private android.graphics.drawable.GradientDrawable mDf1IndicatorBg;
    private android.graphics.drawable.GradientDrawable mDf2IndicatorBg;
    private android.graphics.drawable.GradientDrawable mDf3IndicatorBg;
    private final android.os.Handler mDfBlinkHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mDfBlinkRed = false;
    private final Runnable mDfBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            mDfBlinkRed = !mDfBlinkRed;
            updateDfIndicatorVisuals();
            if (shouldRunDfBlinkTicker()) {
                mDfBlinkHandler.postDelayed(this, 500);
            }
        }
    };

    private boolean shouldRunDfBlinkTicker() {
        boolean isWHDLoadMode = (mWHDLoadFile != null && !mWHDLoadFile.trim().isEmpty());
        if (isWHDLoadMode) return false;
        return hasMediaPath(mDf0DiskImagePath)
            || hasMediaPath(mDf1DiskImagePath)
            || hasMediaPath(mDf2DiskImagePath)
            || hasMediaPath(mDf3DiskImagePath);
    }

    private void updateDfIndicatorVisuals() {
        if (mDf0IndicatorBg != null) mDf0IndicatorBg.setColor(0xFF2196F3);
        if (mDf1IndicatorBg != null) mDf1IndicatorBg.setColor(0xFFFF9800);
        if (mDf2IndicatorBg != null) mDf2IndicatorBg.setColor(0xFF8E24AA);
        if (mDf3IndicatorBg != null) mDf3IndicatorBg.setColor(0xFF00897B);
    }

    private void restartDfBlinkTicker() {
        mDfBlinkHandler.removeCallbacks(mDfBlinkRunnable);
        mDfBlinkRed = false;
        updateDfIndicatorVisuals();
    }

    // Method to update RTG indicator visibility
    private void updateRtgIndicator(boolean rtgActive) {
        if (mRtgIndicator != null) {
            mRtgIndicator.setVisibility(rtgActive ? View.VISIBLE : View.GONE);
        }
    }

    private void updateRendererIndicator() {
        if (mRendererIndicator == null) return;
        String info = null;
        try {
            info = nativeGetRendererDebugInfo();
        } catch (Throwable ignored) {
        }
        String label = "DBG";
        if (info != null && !info.trim().isEmpty()) {
            String driver = extractRendererDriverLabel(info);
            if (driver != null && !driver.trim().isEmpty()) {
                label = driver;
            } else {
                String lc = info.toLowerCase(java.util.Locale.ROOT);
                if (lc.contains("build.use_vulkan=1")) {
                    label = "Vulkan";
                } else if (lc.contains("gl.renderer=") && !lc.contains("gl.renderer=n/a")) {
                    label = "OpenGL";
                } else if (lc.contains("gfx_api=sdl2")) {
                    label = "SDL";
                }
            }
        }
        mRendererIndicator.setText(label);
        mRendererIndicator.setVisibility(View.VISIBLE);
    }

    private String extractRendererDriverLabel(String info) {
        if (info == null || info.trim().isEmpty()) return null;

        String vkDevice = extractDebugInfoValue(info, "vk.device_name");
        if (vkDevice != null && !vkDevice.trim().isEmpty() && !"n/a".equalsIgnoreCase(vkDevice.trim())) {
            String normalized = normalizeDriverLabel(vkDevice);
            if (normalized != null && !normalized.trim().isEmpty()) return normalized;
        }

        String glRenderer = extractDebugInfoValue(info, "gl.renderer");
        if (glRenderer != null && !glRenderer.trim().isEmpty() && !"n/a".equalsIgnoreCase(glRenderer.trim())) {
            String normalized = normalizeDriverLabel(glRenderer);
            if (normalized != null && !normalized.trim().isEmpty()) return normalized;
        }

        return null;
    }

    private String extractDebugInfoValue(String info, String key) {
        if (info == null || key == null || key.trim().isEmpty()) return null;
        String needle = key + "=";
        String[] lines = info.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.startsWith(needle)) {
                return trimmed.substring(needle.length()).trim();
            }
        }
        return null;
    }

    private String normalizeDriverLabel(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;

        String lc = v.toLowerCase(java.util.Locale.ROOT);
        if (lc.contains("turnip")) return "Turnip";
        if (lc.contains("qualcomm") || lc.contains("adreno")) return "Qualcomm";
        if (lc.contains("mali")) return "Mali";
        if (lc.contains("powervr")) return "PowerVR";

        int cut = v.indexOf('(');
        if (cut > 0) v = v.substring(0, cut).trim();
        return v;
    }

    private void showRendererInfoDialog() {
        String aspect = "16:9";
        try {
            aspect = normalizeVideoAspectMode(nativeGetVideoAspectMode()) == 0 ? "4:3" : "16:9";
        } catch (Throwable ignored) {
        }
        String dbg = "";
        try {
            dbg = nativeGetRendererDebugInfo();
        } catch (Throwable ignored) {
        }
        StringBuilder info = new StringBuilder();
        info.append("Graphic renderer / driver (runtime)");
        info.append("\nVideo aspect: ").append(aspect);
        info.append("\nRTG: ").append((mRtgIndicator != null && mRtgIndicator.getVisibility() == View.VISIBLE) ? "ON" : "OFF");
        if (dbg != null && !dbg.trim().isEmpty()) {
            info.append("\n\n").append(dbg.trim());
        }

        new AlertDialog.Builder(this)
            .setTitle("Renderer Info")
            .setMessage(info.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    private boolean hasAnyHardDriveConfigured() {
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            for (int i = 0; i <= 4; i++) {
                String hdfEnabledKey = (i == 0) ? UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED
                    : (i == 1) ? UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED
                    : (i == 2) ? UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED
                    : (i == 3) ? UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED
                    : UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED;
                String hdfPathKey = (i == 0) ? UaeOptionKeys.UAE_DRIVE_HDF0_PATH
                    : (i == 1) ? UaeOptionKeys.UAE_DRIVE_HDF1_PATH
                    : (i == 2) ? UaeOptionKeys.UAE_DRIVE_HDF2_PATH
                    : (i == 3) ? UaeOptionKeys.UAE_DRIVE_HDF3_PATH
                    : UaeOptionKeys.UAE_DRIVE_HDF4_PATH;
                String dirEnabledKey = (i == 0) ? UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED
                    : (i == 1) ? UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED
                    : (i == 2) ? UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED
                    : (i == 3) ? UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED
                    : UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED;
                String dirPathKey = (i == 0) ? UaeOptionKeys.UAE_DRIVE_DIR0_PATH
                    : (i == 1) ? UaeOptionKeys.UAE_DRIVE_DIR1_PATH
                    : (i == 2) ? UaeOptionKeys.UAE_DRIVE_DIR2_PATH
                    : (i == 3) ? UaeOptionKeys.UAE_DRIVE_DIR3_PATH
                    : UaeOptionKeys.UAE_DRIVE_DIR4_PATH;

                if (p.getBoolean(hdfEnabledKey, false) && hasMediaPath(p.getString(hdfPathKey, null))) return true;
                if (p.getBoolean(dirEnabledKey, false) && hasMediaPath(p.getString(dirPathKey, null))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void refreshOverlayDriveStatus() {
        boolean isWHDLoadMode = (mWHDLoadFile != null && !mWHDLoadFile.trim().isEmpty());
        boolean isCdOnlyMode = isCdOnlyQuickstartModel();

        updateRendererIndicator();

        if (mWhdLhaQuickButton != null) {
            mWhdLhaQuickButton.setVisibility(View.GONE);
        }
        if (mHdQuickButton != null) {
            mHdQuickButton.setVisibility(View.GONE);
        }
        if (mFloppySoundQuickButton != null) {
            mFloppySoundQuickButton.setVisibility(isWHDLoadMode ? View.GONE : View.VISIBLE);
            updateFloppySoundButtonLabel(mFloppySoundQuickButton, getFloppySoundEnabledPref());
        }

        if (mDf0Indicator != null) mDf0Indicator.setVisibility((isWHDLoadMode || isCdOnlyMode) ? View.GONE : View.VISIBLE);
        if (mDf1Indicator != null) mDf1Indicator.setVisibility((!isWHDLoadMode && !isCdOnlyMode && hasMediaPath(mDf1DiskImagePath)) ? View.VISIBLE : View.GONE);
        if (mDf2Indicator != null) mDf2Indicator.setVisibility((!isWHDLoadMode && !isCdOnlyMode && hasMediaPath(mDf2DiskImagePath)) ? View.VISIBLE : View.GONE);
        if (mDf3Indicator != null) mDf3Indicator.setVisibility((!isWHDLoadMode && !isCdOnlyMode && hasMediaPath(mDf3DiskImagePath)) ? View.VISIBLE : View.GONE);
        restartDfBlinkTicker();
    }

    private void openMediaSwapperFromOverlay(String mediaSection) {
        if (mMenuPanel != null) {
            mMenuPanel.setVisibility(View.GONE);
        }
        if (mBtnHamburger != null) {
            mBtnHamburger.setText("☰");
        }
        try {
            Intent i = new Intent(this, BootstrapActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra(BootstrapActivity.EXTRA_FROM_EMULATOR_MENU, true);
            i.putExtra(BootstrapActivity.EXTRA_OPEN_MEDIA_SWAPPER, true);
            if (mediaSection != null && !mediaSection.trim().isEmpty()) {
                i.putExtra(BootstrapActivity.EXTRA_OPEN_MEDIA_SECTION, mediaSection);
            }
            i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF0_PATH, mDf0DiskImagePath);
            i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF1_PATH, mDf1DiskImagePath);
            i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF2_PATH, mDf2DiskImagePath);
            i.putExtra(BootstrapActivity.EXTRA_EMU_CURRENT_DF3_PATH, mDf3DiskImagePath);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to open Media Swapper: " + t);
        }
    }

    private void coldRestartToSetupGui() {
        Log.i(TAG, "coldRestartToSetupGui() - relaunching BootstrapActivity");
        try {
            persistResolvedFloppyPathsToPrefs();

            Intent i = new Intent(this, BootstrapActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.putExtra(BootstrapActivity.EXTRA_FROM_EMULATOR_MENU, false);
            startActivity(i);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finishAffinity();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to cold restart to setup GUI: " + t);
            try { finish(); } catch (Throwable ignored) {}
        }
    }

    private void restartEmulator() {
        // When the emulator needs to restart (e.g., DF0 disk change), we need to
        // re-launch ourselves with the new disk configuration.
        // Simply killing the process doesn't work because there's no one to restart us.
        
        Log.i(TAG, "restartEmulator() - relaunching with current disk configuration");
        
        try {
            // Build a restart intent from the current launch context so we preserve
            // the active emulator configuration (quickstart/model/cpu/etc).
            Intent currentIntent = getIntent();
            Intent restartIntent = (currentIntent != null)
                ? new Intent(currentIntent)
                : new Intent(this, AmiberryActivity.class);

            restartIntent.setClass(this, AmiberryActivity.class);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            restartIntent.removeExtra(EXTRA_REQUEST_RESTART);
            restartIntent.removeExtra(EXTRA_HOTSWAP_DF0_PATH);
            restartIntent.removeExtra(EXTRA_HOTSWAP_DF1_PATH);
            restartIntent.removeExtra(EXTRA_HOTSWAP_DF2_PATH);
            restartIntent.removeExtra(EXTRA_HOTSWAP_DF3_PATH);

            restartIntent.putExtra(EXTRA_REQUEST_RESTART, false); // Don't loop
            restartIntent.putExtra(EXTRA_ENABLE_AUTO_DF0, false);
            restartIntent.putExtra(EXTRA_SHOW_GUI, mShowGui);
            restartIntent.putExtra(EXTRA_ENABLE_LOGFILE, mEnableLogfile);

            if (mKickstartRomFile != null && !mKickstartRomFile.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_KICKSTART_ROM_FILE, mKickstartRomFile);
            } else {
                restartIntent.removeExtra(EXTRA_KICKSTART_ROM_FILE);
            }

            if (mWHDLoadFile != null && !mWHDLoadFile.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_WHDLOAD_FILE, mWHDLoadFile);
            } else {
                restartIntent.removeExtra(EXTRA_WHDLOAD_FILE);
            }

            if (mCpuType != null && !mCpuType.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_CPU_TYPE, mCpuType);
            } else {
                restartIntent.removeExtra(EXTRA_CPU_TYPE);
            }

            if (mMachinePreset != null && !mMachinePreset.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_MACHINE_PRESET, mMachinePreset);
            } else {
                restartIntent.removeExtra(EXTRA_MACHINE_PRESET);
            }

            if (mQsModel != null && !mQsModel.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_QS_MODEL, mQsModel);
            } else {
                restartIntent.removeExtra(EXTRA_QS_MODEL);
            }
            restartIntent.putExtra(EXTRA_QS_CONFIG_INDEX, mQsConfigIndex);
            restartIntent.putExtra(EXTRA_QS_NTSC, mQsNtsc);
            restartIntent.putExtra(EXTRA_QS_MODE, mQsMode);
            
            // Include current disk paths
            if (mDf0DiskImagePath != null && !mDf0DiskImagePath.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_DF0_DISK_FILE, mDf0DiskImagePath);
                Log.i(TAG, "Restart with DF0: " + mDf0DiskImagePath);
            } else {
                restartIntent.putExtra(EXTRA_DF0_DISK_FILE, "");
            }
            if (mDf1DiskImagePath != null && !mDf1DiskImagePath.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_DF1_DISK_FILE, mDf1DiskImagePath);
            } else {
                restartIntent.putExtra(EXTRA_DF1_DISK_FILE, "");
            }
            if (mDf2DiskImagePath != null && !mDf2DiskImagePath.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_DF2_DISK_FILE, mDf2DiskImagePath);
            } else {
                restartIntent.putExtra(EXTRA_DF2_DISK_FILE, "");
            }
            if (mDf3DiskImagePath != null && !mDf3DiskImagePath.trim().isEmpty()) {
                restartIntent.putExtra(EXTRA_DF3_DISK_FILE, mDf3DiskImagePath);
            } else {
                restartIntent.putExtra(EXTRA_DF3_DISK_FILE, "");
            }
            
            // Launch the new instance
            startActivity(restartIntent);
            
            // Finish this instance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finishAffinity();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to restart emulator activity: " + t);
            try { finish(); } catch (Throwable ignored) {}
        }

        // Ensure the process exits so the new instance starts fresh
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {
                }
                try {
                    System.exit(0);
                } catch (Throwable ignored) {
                }
            }, 300);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to schedule process exit during restart: " + t);
        }
    }

    private void applyRuntimeFloppyPathsAndReset() {
        try {
            nativeInsertFloppy(0, (mDf0DiskImagePath == null) ? "" : mDf0DiskImagePath.trim());
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply DF0 before reset: " + t);
        }
        try {
            nativeInsertFloppy(1, (mDf1DiskImagePath == null) ? "" : mDf1DiskImagePath.trim());
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply DF1 before reset: " + t);
        }
        try {
            nativeInsertFloppy(2, (mDf2DiskImagePath == null) ? "" : mDf2DiskImagePath.trim());
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply DF2 before reset: " + t);
        }
        try {
            nativeInsertFloppy(3, (mDf3DiskImagePath == null) ? "" : mDf3DiskImagePath.trim());
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply DF3 before reset: " + t);
        }

        try {
            nativeResetEmulator();
            Log.i(TAG, "Applied runtime floppy changes and reset emulator");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to reset emulator after applying floppy changes: " + t);
        }
    }

    private void quitToLauncher() {
        try {
            mPausedByOverlay = false;
            SDLActivity.nativePause();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to pause SDL before quit: " + t);
        }

        // Fully close the application and remove it from recents when possible.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finishAffinity();
            }
        } catch (Throwable t) {
            Log.w(TAG, "finishAndRemoveTask/finishAffinity failed: " + t);
            try { finish(); } catch (Throwable ignored) {}
        }

        // Ensure the SDL/native process doesn't linger.
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {
                }
                try {
                    System.exit(0);
                } catch (Throwable ignored) {
                }
            }, 250);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to schedule process exit: " + t);
        }
    }

    private void showSetupFallbackOverlay() {
        // No-op: the native launcher menu is the only UI.
    }

    private void installDisplaySafeAreaInsetsHandler() {
        if (mRootLayer == null || mEmulatorLayer == null || Build.VERSION.SDK_INT < 20) return;

        mRootLayer.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetLeft = insets.getSystemWindowInsetLeft();
            int insetTop = insets.getSystemWindowInsetTop();
            int insetRight = insets.getSystemWindowInsetRight();
            int insetBottom = insets.getSystemWindowInsetBottom();

            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                insetLeft = Math.max(insetLeft, insets.getDisplayCutout().getSafeInsetLeft());
                insetTop = Math.max(insetTop, insets.getDisplayCutout().getSafeInsetTop());
                insetRight = Math.max(insetRight, insets.getDisplayCutout().getSafeInsetRight());
                insetBottom = Math.max(insetBottom, insets.getDisplayCutout().getSafeInsetBottom());
            }

            mEmulatorLayer.setPadding(insetLeft, insetTop, insetRight, insetBottom);
            return insets;
        });
        mRootLayer.requestApplyInsets();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        // Ensure no "heading"/status bar and give SDL a full canvas.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // Rebuild the view hierarchy so the emulator renders into its own surface layer,
        // and we have a separate container on top for GUI (Flutter or native Android).
        try {
            ViewGroup sdlRoot = org.libsdl.app.SDLActivity.mLayout;
            View sdlSurface = org.libsdl.app.SDLActivity.mSurface;
            if (sdlRoot != null && sdlSurface != null) {
                sdlRoot.removeView(sdlSurface);

                FrameLayout root = new FrameLayout(this);

                FrameLayout emulatorLayer = new FrameLayout(this);
                emulatorLayer.setId(R.id.amiberry_emulator_container);
                emulatorLayer.addView(sdlSurface,
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));

                mGuiLayer = new FrameLayout(this);
                mGuiLayer.setId(R.id.amiberry_gui_container);

                root.addView(emulatorLayer,
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));
                root.addView(mGuiLayer,
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));

                setContentView(root);
                org.libsdl.app.SDLActivity.mLayout = root;
                mRootLayer = root;
                mEmulatorLayer = emulatorLayer;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to re-layer SDL surface (continuing): " + t);
        }

        installDisplaySafeAreaInsetsHandler();

        // Intercept touch events when VKBD is visible and forward real MotionEvent coordinates
        // to native VKBD hit-testing. This bypasses SDL relative mouse mode on some devices,
        // which can otherwise report a constant click position (making every key press the same).
        try {
            if (mGuiLayer != null) {
                mVkbdTouchInterceptor = new View(this);
                mVkbdTouchInterceptor.setClickable(false);
                mVkbdTouchInterceptor.setFocusable(false);
                FrameLayout.LayoutParams lpTouch = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

                mVkbdTouchInterceptor.setOnTouchListener((v, ev) -> {
                    boolean vkbdActive = false;
                    try {
                        vkbdActive = nativeIsVkbdActive();
                    } catch (Throwable t) {
                        if (!sLoggedVkbdActiveQueryFailure) {
                            Log.w(TAG, "nativeIsVkbdActive failed: " + t);
                            sLoggedVkbdActiveQueryFailure = true;
                        }
                    }

                    if (mVkbdProbeLogCount < 20) {
                        Log.i(TAG, "VKBD touch probe action=" + ev.getActionMasked()
                            + " x=" + (int) ev.getX() + " y=" + (int) ev.getY()
                            + " active=" + vkbdActive);
                        mVkbdProbeLogCount++;
                    }

                    // When VKBD is not active, let touch events pass through to SDL
                    // for normal mouse movement. Return false so SDL handles the event.
                    if (!vkbdActive) {
                        return false;
                    }

                    final int action = ev.getActionMasked();
                    final int vw = v.getWidth();
                    final int vh = v.getHeight();
                    final int x = (int) ev.getX();
                    final int y = (int) ev.getY();
                    if (vw <= 0 || vh <= 0) {
                        return true;
                    }

                    try {
                        nativeSendVkbdTouch(action, x, y, vw, vh);
                        if (mVkbdForwardLogCount < 12) {
                            Log.i(TAG, "VKBD touch forwarded action=" + action
                                + " x=" + x + " y=" + y
                                + " view=" + vw + "x" + vh);
                            mVkbdForwardLogCount++;
                        }
                    } catch (Throwable t) {
                        if (!sLoggedVkbdTouchFailure) {
                            Log.w(TAG, "nativeSendVkbdTouch failed: " + t);
                            sLoggedVkbdTouchFailure = true;
                        }
                    }
                    return true;
                });

                // Insert underneath the overlay button bar so Menu/VKBD buttons still work.
                mGuiLayer.addView(mVkbdTouchInterceptor, 0, lpTouch);
            }
        } catch (Throwable t) {
            if (!sLoggedVkbdInterceptorFailure) {
                Log.w(TAG, "Unable to install VKBD touch interceptor: " + t);
                sLoggedVkbdInterceptorFailure = true;
            }
        }

        // Add native overlay menu button on top of SDL.
        addInEmulatorMenuButton();

        // Add Virtual Joystick Overlay
        if (mGuiLayer != null) {
            mVirtualJoystick = new VirtualJoystickOverlay(this);
            // Virtual joystick visibility comes from Input Options controller source.
            boolean virtualEnabled = isVirtualJoystickEnabledPref();
            applyVirtualJoystickRuntime(virtualEnabled, false);
            mGuiLayer.addView(mVirtualJoystick, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));

            // Keep z-order stable:
            // 1) virtual joystick below VKBD touch interceptor
            // 2) overlay buttons/menu above everything else
            try {
                if (mVkbdTouchInterceptor != null) mVkbdTouchInterceptor.bringToFront();
                if (mInEmuOverlay != null) mInEmuOverlay.bringToFront();
            } catch (Throwable ignored) {
            }
        }

        logI("onCreate() - starting ROM provisioning");

        // If a kickstart ROM was provided explicitly (e.g., imported into internal storage), use it.
        // Otherwise prefer external Kickstart if present; otherwise use bundled AROS.
        String forcedKick = null;
        String forcedWHDLoad = null;
        String forcedDf0 = null;
        String forcedDf1 = null;
        String forcedDf2 = null;
        String forcedDf3 = null;
        String forcedCd0 = null;
        boolean hasForcedDf0 = false;
        boolean hasForcedDf1 = false;
        boolean hasForcedDf2 = false;
        boolean hasForcedDf3 = false;
        boolean hasForcedCd0 = false;
        try {
            Intent intent = getIntent();
            if (intent != null) {
                forcedKick = intent.getStringExtra(EXTRA_KICKSTART_ROM_FILE);
                forcedWHDLoad = intent.getStringExtra(EXTRA_WHDLOAD_FILE);
            hasForcedDf0 = intent.hasExtra(EXTRA_DF0_DISK_FILE);
            hasForcedDf1 = intent.hasExtra(EXTRA_DF1_DISK_FILE);
            hasForcedDf2 = intent.hasExtra(EXTRA_DF2_DISK_FILE);
            hasForcedDf3 = intent.hasExtra(EXTRA_DF3_DISK_FILE);
            hasForcedCd0 = intent.hasExtra(EXTRA_CD_IMAGE0_FILE);
            forcedDf0 = hasForcedDf0 ? intent.getStringExtra(EXTRA_DF0_DISK_FILE) : null;
            forcedDf1 = hasForcedDf1 ? intent.getStringExtra(EXTRA_DF1_DISK_FILE) : null;
            forcedDf2 = hasForcedDf2 ? intent.getStringExtra(EXTRA_DF2_DISK_FILE) : null;
            forcedDf3 = hasForcedDf3 ? intent.getStringExtra(EXTRA_DF3_DISK_FILE) : null;
            forcedCd0 = hasForcedCd0 ? intent.getStringExtra(EXTRA_CD_IMAGE0_FILE) : null;
            logI("Intent forced CD extra present=" + hasForcedCd0);
                mShowGui = intent.getBooleanExtra(EXTRA_SHOW_GUI, false);
                mCpuType = intent.getStringExtra(EXTRA_CPU_TYPE);
                mMachinePreset = intent.getStringExtra(EXTRA_MACHINE_PRESET);

                mQsModel = intent.getStringExtra(EXTRA_QS_MODEL);
                mQsConfigIndex = intent.getIntExtra(EXTRA_QS_CONFIG_INDEX, 0);
                mQsNtsc = intent.getBooleanExtra(EXTRA_QS_NTSC, false);
                mQsMode = intent.getBooleanExtra(EXTRA_QS_MODE, true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read intent extras: " + t);
        }

        if (forcedKick != null && !forcedKick.isEmpty()) {
            mKickstartRomFile = forcedKick;
            logI("Using forced Kickstart ROM from intent: " + mKickstartRomFile);
        } else {
            configureKickstartSource();
        }

        if (forcedWHDLoad != null && !forcedWHDLoad.isEmpty()) {
            mWHDLoadFile = forcedWHDLoad;
            logI("Using forced WHDLoad file from intent: " + mWHDLoadFile);
        }

        try {
            Intent intent = getIntent();
            if (intent != null) {
                String src = intent.getStringExtra(EXTRA_DF0_SOURCE_NAME);
                mDf0SourceName = (src == null || src.trim().isEmpty()) ? null : src.trim();
            }
        } catch (Throwable ignored) {
        }

        // Ensure VKBD graphics are available under get_data_path()/vkbd/ before runtime toggle.
        provisionVkbdAssetsIfPossible();
        provisionFloppySoundsIfPossible();
        provisionWhdbootAssetsIfPossible();

        // Keep VKBD mapped to full device logical space; video aspect is applied separately in native render.
        try {
            SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            int aspectMode = normalizeVideoAspectMode(p.getInt(UaeOptionKeys.UAE_VIDEO_ASPECT_MODE, 1));
            nativeSetStretchToFill(aspectMode == 1);
            nativeSetVideoAspectMode(aspectMode);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply Android video defaults: " + t);
        }

        if (hasForcedDf0) {
            String s = (forcedDf0 == null) ? "" : forcedDf0.trim();
            if (s.isEmpty()) {
                mDf0DiskImagePath = null;
                logI("Using forced DF0 disk image from intent: (eject)");
            } else if (s.startsWith("content://")) {
                mDf0DiskImagePath = s;
                logI("Using forced DF0 disk image from intent (SAF): " + mDf0DiskImagePath);
            } else {
                File f = new File(s);
                if (f.exists() && f.canRead()) {
                    mDf0DiskImagePath = f.getAbsolutePath();
                    logI("Using forced DF0 disk image from intent: " + mDf0DiskImagePath + " (" + describeDiskPath(mDf0DiskImagePath) + ")");
                } else {
                    Log.w(TAG, "Forced DF0 disk is missing or unreadable: " + forcedDf0);
                }
            }
        }

        if (hasForcedDf1) {
            String s = (forcedDf1 == null) ? "" : forcedDf1.trim();
            if (s.isEmpty()) {
                mDf1DiskImagePath = null;
                logI("Using forced DF1 disk image from intent: (eject)");
            } else if (s.startsWith("content://")) {
                mDf1DiskImagePath = s;
                logI("Using forced DF1 disk image from intent (SAF): " + mDf1DiskImagePath);
            } else {
                File f = new File(s);
                if (f.exists() && f.canRead()) {
                    mDf1DiskImagePath = f.getAbsolutePath();
                    logI("Using forced DF1 disk image from intent: " + mDf1DiskImagePath);
                } else {
                    Log.w(TAG, "Forced DF1 disk is missing or unreadable: " + forcedDf1);
                }
            }
        }

        if (hasForcedDf2) {
            String s = (forcedDf2 == null) ? "" : forcedDf2.trim();
            if (s.isEmpty()) {
                mDf2DiskImagePath = null;
                logI("Using forced DF2 disk image from intent: (eject)");
            } else {
                File f = new File(s);
                if (f.exists() && f.canRead()) {
                    mDf2DiskImagePath = f.getAbsolutePath();
                    logI("Using forced DF2 disk image from intent: " + mDf2DiskImagePath);
                } else {
                    Log.w(TAG, "Forced DF2 disk is missing or unreadable: " + forcedDf2);
                }
            }
        }

        if (hasForcedDf3) {
            String s = (forcedDf3 == null) ? "" : forcedDf3.trim();
            if (s.isEmpty()) {
                mDf3DiskImagePath = null;
                logI("Using forced DF3 disk image from intent: (eject)");
            } else {
                File f = new File(s);
                if (f.exists() && f.canRead()) {
                    mDf3DiskImagePath = f.getAbsolutePath();
                    logI("Using forced DF3 disk image from intent: " + mDf3DiskImagePath);
                } else {
                    Log.w(TAG, "Forced DF3 disk is missing or unreadable: " + forcedDf3);
                }
            }
        }

        if (hasForcedCd0) {
            String s = (forcedCd0 == null) ? "" : forcedCd0.trim();
            if (s.isEmpty()) {
                mCdImagePath = null;
                logI("Using forced CD image from intent: (eject)");
            } else if (s.startsWith("content://")) {
                mCdImagePath = s;
                logI("Using forced CD image from intent (SAF): " + mCdImagePath);
            } else {
                File f = new File(s);
                if (f.exists() && f.canRead()) {
                    mCdImagePath = f.getAbsolutePath();
                    logI("Using forced CD image from intent: " + mCdImagePath);
                } else {
                    Log.w(TAG, "Forced CD image is missing or unreadable: " + forcedCd0);
                }
            }

            try {
                SharedPreferences.Editor cdEd = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();
                if (mCdImagePath != null && !mCdImagePath.trim().isEmpty()) {
                    cdEd.putString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, mCdImagePath.trim());
                    cdEd.putBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, true);
                } else {
                    cdEd.remove(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);
                }
                cdEd.apply();
            } catch (Throwable ignored) {
            }
        }

        boolean enableAutoDf0 = false;
        try {
            Intent intent = getIntent();
            if (intent != null) {
                enableAutoDf0 = intent.getBooleanExtra(EXTRA_ENABLE_AUTO_DF0, false);
                mEnableLogfile = intent.getBooleanExtra(EXTRA_ENABLE_LOGFILE, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read " + EXTRA_ENABLE_AUTO_DF0 + ": " + t);
        }

        logI("Core logfile enabled (intent): " + mEnableLogfile);

        if (mDf0DiskImagePath == null && enableAutoDf0) {
            configureDf0DiskSource();
        }

        // If no disk was provided via intent, allow configs to supply DF0/DF1 via prefs.
        configureFloppySourcesFromPrefs();
        refreshOverlayDriveStatus();

        try {
            logI("Resolved floppy paths: DF0='" + (mDf0DiskImagePath == null ? "" : mDf0DiskImagePath) + "' (" + describeDiskPath(mDf0DiskImagePath) + ")");
            logI("Resolved floppy paths: DF1='" + (mDf1DiskImagePath == null ? "" : mDf1DiskImagePath) + "'");
            logI("Resolved floppy paths: DF2='" + (mDf2DiskImagePath == null ? "" : mDf2DiskImagePath) + "'");
            logI("Resolved floppy paths: DF3='" + (mDf3DiskImagePath == null ? "" : mDf3DiskImagePath) + "'");

            File internalDf0 = new File(getDisksDir(), DF0_DISK_NAME);
            if (internalDf0.exists()) {
                logI("Internal disks/" + DF0_DISK_NAME + " stats: len=" + internalDf0.length() + " mtime=" + internalDf0.lastModified());
            }
        } catch (Throwable ignored) {
        }

        persistResolvedFloppyPathsToPrefs();
        provisionArosRomsIfPossible();

        // No overlay UI: the native launcher handles UI and then starts this activity.
    }

    private void persistResolvedFloppyPathsToPrefs() {
        try {
            SharedPreferences.Editor e = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE).edit();

            if (mDf0DiskImagePath != null && !mDf0DiskImagePath.trim().isEmpty()) {
                e.putString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, mDf0DiskImagePath.trim());
            } else {
                e.remove(UaeOptionKeys.UAE_DRIVE_DF0_PATH);
            }
            if (mDf1DiskImagePath != null && !mDf1DiskImagePath.trim().isEmpty()) {
                e.putString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, mDf1DiskImagePath.trim());
            } else {
                e.remove(UaeOptionKeys.UAE_DRIVE_DF1_PATH);
            }
            if (mDf2DiskImagePath != null && !mDf2DiskImagePath.trim().isEmpty()) {
                e.putString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, mDf2DiskImagePath.trim());
            } else {
                e.remove(UaeOptionKeys.UAE_DRIVE_DF2_PATH);
            }
            if (mDf3DiskImagePath != null && !mDf3DiskImagePath.trim().isEmpty()) {
                e.putString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, mDf3DiskImagePath.trim());
            } else {
                e.remove(UaeOptionKeys.UAE_DRIVE_DF3_PATH);
            }

            e.apply();
        } catch (Throwable ignored) {
        }
    }

    // JNI bridge implemented in the Android libmain.so wrapper. This queues an AKS_OSK toggle
    // on the emulation thread (safer than calling vkbd_toggle() directly from the UI thread).
    public static native void nativeToggleVkbd();

    // Returns true if VKBD is currently visible in the native core.
    public static native boolean nativeIsVkbdActive();

    // Forward touch coordinates (view space) to the emulation thread for VKBD hit-testing.
    // action is MotionEvent.ACTION_*.
    public static native void nativeSendVkbdTouch(int action, int x, int y, int viewW, int viewH);

    // Android host-only display toggle.
    public static native void nativeSetStretchToFill(boolean enabled);
    public static native boolean nativeGetStretchToFill();
    public static native void nativeSetVideoAspectMode(int mode);
    public static native void nativeSetVirtualJoystickEnabled(boolean enabled);
    public static native void nativeSetFloppySoundVolumePercent(int percent);
    public static native void nativeSetEmulatorSoundVolumePercent(int percent);
    public static native int nativeGetVideoAspectMode();
    public static native String nativeGetRendererDebugInfo();

    // Hot-swap a floppy image in the running emulator.
    // drive: 0 = DF0, 1 = DF1.
    public static native void nativeInsertFloppy(int drive, String path);

    // Save/Load state. mode: 1=save, 0=load.
    public static native void nativeSaveState(int slot, int mode);

    // Reset the emulator (soft reset)
    public static native void nativeResetEmulator();

    // Virtual Joystick injection.
    // axis: 0=horiz, 1=vert. value: -1, 0, 1.
    // button: 0=fire1, 1=fire2. pressed: 0=up, 1=down.
    // Pass -1 for axis/button to ignore that field.
    public static native void nativeSendVirtualJoystick(int axis, int value, int button, int pressed);

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try {
            if (intent != null && intent.getBooleanExtra(EXTRA_REQUEST_RESTART, false)) {
                Log.i(TAG, "Received EXTRA_REQUEST_RESTART");
                mRestartRequested = false;

                // Refresh quickstart model/config context from the incoming intent
                // so CD32 <-> DF transitions don't keep stale model behavior.
                if (intent.hasExtra(EXTRA_QS_MODEL)) {
                    String qsModel = intent.getStringExtra(EXTRA_QS_MODEL);
                    mQsModel = (qsModel == null || qsModel.trim().isEmpty()) ? null : qsModel.trim();
                }
                mQsConfigIndex = intent.getIntExtra(EXTRA_QS_CONFIG_INDEX, mQsConfigIndex);
                mQsNtsc = intent.getBooleanExtra(EXTRA_QS_NTSC, mQsNtsc);
                mQsMode = intent.getBooleanExtra(EXTRA_QS_MODE, mQsMode);
                
                // When restarting, read the DF0-DF3 extras from the intent
                // These represent the new disk selection from the swapper
                if (intent.hasExtra(EXTRA_DF0_DISK_FILE)) {
                    String df0 = intent.getStringExtra(EXTRA_DF0_DISK_FILE);
                    String path = (df0 == null) ? "" : df0.trim();
                    Log.i(TAG, "Restart with DF0: " + (path.isEmpty() ? "(eject)" : path));
                    mDf0DiskImagePath = path.isEmpty() ? null : path;
                }
                if (intent.hasExtra(EXTRA_DF1_DISK_FILE)) {
                    String df1 = intent.getStringExtra(EXTRA_DF1_DISK_FILE);
                    String path = (df1 == null) ? "" : df1.trim();
                    Log.i(TAG, "Restart with DF1: " + (path.isEmpty() ? "(eject)" : path));
                    mDf1DiskImagePath = path.isEmpty() ? null : path;
                }
                if (intent.hasExtra(EXTRA_DF2_DISK_FILE)) {
                    String df2 = intent.getStringExtra(EXTRA_DF2_DISK_FILE);
                    String path = (df2 == null) ? "" : df2.trim();
                    Log.i(TAG, "Restart with DF2: " + (path.isEmpty() ? "(eject)" : path));
                    mDf2DiskImagePath = path.isEmpty() ? null : path;
                }
                if (intent.hasExtra(EXTRA_DF3_DISK_FILE)) {
                    String df3 = intent.getStringExtra(EXTRA_DF3_DISK_FILE);
                    String path = (df3 == null) ? "" : df3.trim();
                    Log.i(TAG, "Restart with DF3: " + (path.isEmpty() ? "(eject)" : path));
                    mDf3DiskImagePath = path.isEmpty() ? null : path;
                }
                persistResolvedFloppyPathsToPrefs();
                refreshOverlayDriveStatus();

                // Request a full process/activity restart so all runtime args (CD/HDF/CPU/model)
                // are rebuilt from the newly loaded config, not just floppy hot-swap state.
                mRestartRequested = true;
                return;
            }
        } catch (Throwable ignored) {
        }

        // Hot-swap media without restarting.
        try {
            if (intent != null) {
                boolean changed = false;

                if (intent.hasExtra(EXTRA_HOTSWAP_DF0_PATH)) {
                    String df0 = intent.getStringExtra(EXTRA_HOTSWAP_DF0_PATH);
                    String path = (df0 == null) ? "" : df0.trim();
                    Log.i(TAG, "Hot-swap DF0 requested: " + (path.isEmpty() ? "(eject)" : path));
                    // Always re-insert DF0 even when the path string is unchanged (e.g. stable disk.zip
                    // whose content changed from disk 1 -> disk 2).
                    try {
                        nativeInsertFloppy(0, path);
                    } catch (Throwable t) {
                        Log.w(TAG, "DF0 hot-swap failed via nativeInsertFloppy: " + t);
                    }
                    mDf0DiskImagePath = path.isEmpty() ? null : path;
                    changed = true;
                    persistResolvedFloppyPathsToPrefs();
                    refreshOverlayDriveStatus();
                }

                if (intent.hasExtra(EXTRA_HOTSWAP_DF1_PATH)) {
                    String df1 = intent.getStringExtra(EXTRA_HOTSWAP_DF1_PATH);
                    String path = (df1 == null) ? "" : df1.trim();
                    Log.i(TAG, "Hot-swap DF1 requested: " + (path.isEmpty() ? "(eject)" : path));
                    nativeInsertFloppy(1, path);
                    mDf1DiskImagePath = path.isEmpty() ? null : path;
                    changed = true;
                }

                if (intent.hasExtra(EXTRA_HOTSWAP_DF2_PATH)) {
                    String df2 = intent.getStringExtra(EXTRA_HOTSWAP_DF2_PATH);
                    String path = (df2 == null) ? "" : df2.trim();
                    Log.i(TAG, "Hot-swap DF2 requested: " + (path.isEmpty() ? "(eject)" : path));
                    nativeInsertFloppy(2, path);
                    mDf2DiskImagePath = path.isEmpty() ? null : path;
                    changed = true;
                }

                if (intent.hasExtra(EXTRA_HOTSWAP_DF3_PATH)) {
                    String df3 = intent.getStringExtra(EXTRA_HOTSWAP_DF3_PATH);
                    String path = (df3 == null) ? "" : df3.trim();
                    Log.i(TAG, "Hot-swap DF3 requested: " + (path.isEmpty() ? "(eject)" : path));
                    nativeInsertFloppy(3, path);
                    mDf3DiskImagePath = path.isEmpty() ? null : path;
                    changed = true;
                }

                if (changed) {
                    persistResolvedFloppyPathsToPrefs();
                    refreshOverlayDriveStatus();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply hot-swap intent: " + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mRestartRequested) {
            Log.i(TAG, "Restart requested; restarting emulator process");
            mRestartRequested = false;
            restartEmulator();
            return;
        }

        // If we previously launched an overlay Activity, SDLActivity lifecycle already handled
        // pause/resume; just clear our bookkeeping flag.
        mPausedByOverlay = false;
    }

    @Override
    protected void onDestroy() {
        try {
            mDfBlinkHandler.removeCallbacks(mDfBlinkRunnable);
        } catch (Throwable ignored) {
        }
        try {
            closeOpenMediaFds();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected String[] getArguments() {
        // Default to headless: the native launcher provides UI.
        // Also set rom_path to the app-private ROM directory.
        final SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        // If we restart the emulator process/activity without fully exiting, avoid leaking fds.
        // Any current run will immediately re-resolve SAF URIs as needed.
        try {
            closeOpenMediaFds();
        } catch (Throwable ignored) {
        }

        try {
            boolean hdf0Enabled = p.getBoolean(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED, false);
            String hdf0Path = p.getString(UaeOptionKeys.UAE_DRIVE_HDF0_PATH, null);
            logI("getArguments() invoked pid=" + android.os.Process.myPid()
                    + " hdf0Enabled=" + hdf0Enabled
                    + " hdf0Path=" + hdf0Path
                    + " (" + describeDiskPath(hdf0Path) + ")");
        } catch (Throwable t) {
            Log.w(TAG, "getArguments() logging failed: " + t);
        }

        String romPath = getRomsDir().getAbsolutePath() + "/";

        // If the user has configured a custom ROMs folder via the Paths screen, honor it.
        // Note: this is an advanced option; if the folder is not readable, the core will fall back.
        try {
            String configuredRoms = p.getString(UaeOptionKeys.UAE_PATH_ROMS_DIR, null);
            if (configuredRoms != null && !configuredRoms.trim().isEmpty()) {
                String r = configuredRoms.trim();
                if (!r.endsWith("/") && !r.endsWith("\\")) {
                    r = r + "/";
                }
                romPath = r;
            }
        } catch (Throwable ignored) {
        }

        final String useGuiValue = mShowGui ? "yes" : "no";
        logI("Launching with use_gui=" + useGuiValue + ", rom_path=" + romPath + ", kickstart_rom_file=" + mKickstartRomFile +
            (mMachinePreset != null ? ", chipset_compatible=" + mMachinePreset : "") +
            (mCpuType != null ? ", cpu_type=" + mCpuType : "") +
            (mDf0DiskImagePath != null ? ", df0=" + mDf0DiskImagePath : "") +
            (mDf1DiskImagePath != null ? ", df1=" + mDf1DiskImagePath : "") +
            (mDf2DiskImagePath != null ? ", df2=" + mDf2DiskImagePath : "") +
            (mDf3DiskImagePath != null ? ", df3=" + mDf3DiskImagePath : ""));

        List<String> args = new ArrayList<>();

        String effectiveQsModel = mQsModel;
        if (effectiveQsModel == null || effectiveQsModel.trim().isEmpty()) {
            effectiveQsModel = p.getString("qs_model", null);
        }
        if (effectiveQsModel == null || effectiveQsModel.trim().isEmpty()) {
            effectiveQsModel = p.getString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, null);
        }
        if (effectiveQsModel != null) {
            effectiveQsModel = effectiveQsModel.trim();
            if (effectiveQsModel.isEmpty()) {
                effectiveQsModel = null;
            }
        }

        // Apply Quickstart model first (sets the baseline prefs internally), then override via -s.
        if (effectiveQsModel != null) {
            args.add("--model");
            args.add(effectiveQsModel);
        }

        // Optional: record the GUI's "Start in Quickstart mode" flag in amiberry.conf.
        // This doesn't affect headless launches but keeps behavior consistent if GUI is enabled later.
        args.add("-o");
        args.add("Quickstart=" + (mQsMode ? "yes" : "no"));
        args.add("-s");
        args.add("use_gui=" + useGuiValue);
        args.add("-s");
        args.add("rom_path=" + romPath);
        args.add("-s");
        args.add("kickstart_rom_file=" + mKickstartRomFile);

        if (mWHDLoadFile != null && !mWHDLoadFile.isEmpty()) {
            String autoloadPath = materializeWHDLoadUriIfNeeded(mWHDLoadFile);
            args.add("--autoload");
            args.add(autoloadPath);
        }

        if (mEnableLogfile) {
            // Ensure base dir exists so the core can create the logfile.
            ensureDir(getAmiberryBaseDir());
            final File logFile = getLogFile();
            logI("Enabling core logfile: " + logFile.getAbsolutePath());
            // NOTE: These are Amiberry (osdep) settings, not UAE prefs.
            // They must be passed via -o so they are applied before logging_init().
            args.add("-o");
            args.add("logfile_path=" + logFile.getAbsolutePath());
            args.add("-o");
            args.add("write_logfile=yes");
        }

        // On-screen statusline (drive LEDs + counters), including RTG.
        args.add("-s");
        args.add("show_leds=true");
        args.add("-s");
        args.add("show_leds_rtg=true");
        // Make the RTG statusline easier to read on high-DPI displays.
        args.add("-s");
        args.add("show_leds_size_rtg=2.0");

        // Limit the visible floppy devices to what we actually support in the launcher.
        // This keeps the RTG statusline from showing DF2/DF3.
        boolean isCdOnlyQuickstart = false;
        if (effectiveQsModel != null) {
            String m = effectiveQsModel.toUpperCase(java.util.Locale.ROOT);
            isCdOnlyQuickstart = "CD32".equals(m) || "CDTV".equals(m) || "ALG".equals(m) || "ARCADIA".equals(m);
        }

        int nrFloppies;
        if (isCdOnlyQuickstart) {
            nrFloppies = 0;
        } else if (mDf3DiskImagePath != null && !mDf3DiskImagePath.trim().isEmpty()) {
            nrFloppies = 4;
        } else if (mDf2DiskImagePath != null && !mDf2DiskImagePath.trim().isEmpty()) {
            nrFloppies = 3;
        } else if (mDf1DiskImagePath != null && !mDf1DiskImagePath.trim().isEmpty()) {
            nrFloppies = 2;
        } else {
            nrFloppies = 1;
        }

        args.add("-s");
        args.add("nr_floppies=" + nrFloppies);

        // For CD-only machines (CD32, CDTV, etc.) explicitly disable all floppy drives.
        // DRV_NONE = -1. The quickstart preset may still enable DF0 so we must override it.
        if (isCdOnlyQuickstart) {
            args.add("-s");
            args.add("floppy0type=-1");
            args.add("-s");
            args.add("floppy1type=-1");
            args.add("-s");
            args.add("floppy2type=-1");
            args.add("-s");
            args.add("floppy3type=-1");
        } else {
            // Hide unused floppy units so the statusline matches what the launcher is exposing.
            // Use -1 (DRV_NONE) to truly disable them rather than 0 (DRV_35_DD with no disk).
            if (nrFloppies < 2) {
                args.add("-s");
                args.add("floppy1type=-1");
            }
            if (nrFloppies < 3) {
                args.add("-s");
                args.add("floppy2type=-1");
            }
            if (nrFloppies < 4) {
                args.add("-s");
                args.add("floppy3type=-1");
            }
        }

        // NTSC from Quickstart UI.
        if (mQsModel != null && !mQsModel.trim().isEmpty()) {
            args.add("-s");
            args.add("ntsc=" + (mQsNtsc ? "true" : "false"));
        }

        // Apply saved native Android options (Chipset/CPU/FPU/Paths). Quickstart selects a baseline,
        // and these settings override on top.

        // Paths overrides (Amiberry/osdep settings; use -o so they are persisted to amiberry.conf)
        // These mirror the desktop GUI "Paths" tab and control default folders.
        String configPath = p.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null);
        if (configPath != null && !configPath.trim().isEmpty()) {
            String cp = configPath.trim();
            if (!cp.startsWith("content://")) {
                args.add("-o");
                args.add("config_path=" + cp);
            } else {
                logI("Skipping SAF config_path override (unsupported for -o): " + cp);
            }
        }

        String floppyPath = p.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
        if (floppyPath != null && !floppyPath.trim().isEmpty()) {
            String fp = floppyPath.trim();
            if (!fp.startsWith("content://")) {
                args.add("-o");
                args.add("floppy_path=" + fp);
            } else {
                logI("Skipping SAF floppy_path override (unsupported for -o): " + fp);
            }
        }

        String cdromPath = p.getString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, null);
        if (cdromPath != null && !cdromPath.trim().isEmpty()) {
            String cp2 = cdromPath.trim();
            if (!cp2.startsWith("content://")) {
                args.add("-o");
                args.add("cdrom_path=" + cp2);
            } else {
                logI("Skipping SAF cdrom_path override (unsupported for -o): " + cp2);
            }
        }

        String harddrivePath = p.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null);
        if (harddrivePath != null && !harddrivePath.trim().isEmpty()) {
            String hp = harddrivePath.trim();
            if (!hp.startsWith("content://")) {
                args.add("-o");
                args.add("harddrive_path=" + hp);
            } else {
                logI("Skipping SAF harddrive_path override (unsupported for -o): " + hp);
            }
        }

        String lhaPath = p.getString(UaeOptionKeys.UAE_PATH_LHA_DIR, null);
        if (lhaPath != null && !lhaPath.trim().isEmpty()) {
            String lp = lhaPath.trim();
            if (!lp.startsWith("content://")) {
                args.add("-o");
                args.add("whdload_arch_path=" + lp);
            } else {
                logI("Skipping SAF whdload_arch_path override (unsupported for -o): " + lp);
            }
        }

        String whdbootPath = p.getString(UaeOptionKeys.UAE_PATH_WHDBOOT_DIR, null);
        if (whdbootPath != null && !whdbootPath.trim().isEmpty()) {
            String wp = whdbootPath.trim();
            if (!wp.startsWith("content://")) {
                args.add("-o");
                args.add("whdboot_path=" + wp);
            } else {
                // WHDBooter relies on std::filesystem for boot-data and kickstarts.
                // SAF tree URIs cannot be used for these host paths.
                logI("Skipping SAF whdboot_path override (required to be a filesystem path): " + wp);

                // Fall back to our app-private whdboot folder where we provision assets.
                try {
                    File base = getAmiberryBaseDir();
                    File wb = new File(base, "whdboot");
                    ensureDir(wb);
                    args.add("-o");
                    args.add("whdboot_path=" + wb.getAbsolutePath());
                    logI("Falling back whdboot_path to: " + wb.getAbsolutePath());
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to fall back whdboot_path: " + t);
                }
            }
        } else {
            // Critical default: point whdboot_path at our app base dir, where we provision assets.
            // This avoids native defaults pointing at a different legacy folder.
            try {
                File base = getAmiberryBaseDir();
                File wb = new File(base, "whdboot");
                ensureDir(wb);
                args.add("-o");
                args.add("whdboot_path=" + wb.getAbsolutePath());
                logI("Defaulting whdboot_path to: " + wb.getAbsolutePath());
            } catch (Throwable t) {
                Log.w(TAG, "Unable to default whdboot_path: " + t);
            }
        }

        String savestatesDir = p.getString(UaeOptionKeys.UAE_PATH_SAVESTATES_DIR, null);
        if (savestatesDir != null && !savestatesDir.trim().isEmpty()) {
            String sp = savestatesDir.trim();
            if (!sp.startsWith("content://")) {
                args.add("-o");
                args.add("savestate_dir=" + sp);
            } else {
                logI("Skipping SAF savestate_dir override (unsupported for -o): " + sp);
            }
        }

        String screensDir = p.getString(UaeOptionKeys.UAE_PATH_SCREENS_DIR, null);
        if (screensDir != null && !screensDir.trim().isEmpty()) {
            String sd = screensDir.trim();
            if (!sd.startsWith("content://")) {
                args.add("-o");
                args.add("screenshot_dir=" + sd);
            } else {
                logI("Skipping SAF screenshot_dir override (unsupported for -o): " + sd);
            }
        }

        // Sound overrides (mirrors desktop GUI "Sound" tab)
        if (p.contains(UaeOptionKeys.UAE_SOUND_OUTPUT)) {
            String v = p.getString(UaeOptionKeys.UAE_SOUND_OUTPUT, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("sound_output=" + v.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_AUTO)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_SOUND_AUTO, false);
            args.add("-s");
            args.add("sound_auto=" + (v ? "true" : "false"));
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_CHANNELS)) {
            String v = p.getString(UaeOptionKeys.UAE_SOUND_CHANNELS, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("sound_channels=" + v.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_FREQUENCY)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_FREQUENCY, 44100);
            args.add("-s");
            args.add("sound_frequency=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_INTERPOL)) {
            String v = p.getString(UaeOptionKeys.UAE_SOUND_INTERPOL, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("sound_interpol=" + v.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_FILTER) || p.contains(UaeOptionKeys.UAE_SOUND_FILTER_TYPE)) {
            String filter = p.getString(UaeOptionKeys.UAE_SOUND_FILTER, "emulated");
            String filterType = p.getString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "standard");

            if (filter != null && !filter.trim().isEmpty()) {
                args.add("-s");
                args.add("sound_filter=" + filter.trim());
            }
            if (filterType != null && !filterType.trim().isEmpty()) {
                args.add("-s");
                args.add("sound_filter_type=" + filterType.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION, 7);
            args.add("-s");
            args.add("sound_stereo_separation=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_STEREO_DELAY)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_STEREO_DELAY, 0);
            args.add("-s");
            args.add("sound_stereo_mixing_delay=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_SWAP_PAULA)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_SOUND_SWAP_PAULA, false);
            args.add("-s");
            args.add("sound_stereo_swap_paula=" + (v ? "true" : "false"));
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_SWAP_AHI)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_SOUND_SWAP_AHI, false);
            args.add("-s");
            args.add("sound_stereo_swap_ahi=" + (v ? "true" : "false"));
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_MASTER)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_MASTER, 0);
            args.add("-s");
            args.add("sound_volume=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA, 0);
            args.add("-s");
            args.add("sound_volume_paula=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_CD)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_CD, 0);
            args.add("-s");
            args.add("sound_volume_cd=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_AHI)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_AHI, 0);
            args.add("-s");
            args.add("sound_volume_ahi=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI, 0);
            args.add("-s");
            args.add("sound_volume_midi=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_SOUND_MAX_BUFF)) {
            int v = p.getInt(UaeOptionKeys.UAE_SOUND_MAX_BUFF, 16384);
            args.add("-s");
            args.add("sound_max_buff=" + v);
        }

        // NOTE: sound_pullmode is a UAE pref (cfgfile key: sound_pullmode). It must be passed via -s.
        // Passing it via -o will abort startup because -o is parsed as amiberry.conf settings.
        if (p.contains(UaeOptionKeys.UAE_SOUND_PULLMODE)) {
            boolean pull = p.getBoolean(UaeOptionKeys.UAE_SOUND_PULLMODE, true);
            args.add("-s");
            args.add("sound_pullmode=" + (pull ? "1" : "0"));
        }

        // Skip floppy sounds when WHDLoad is active - WHDLoad doesn't use DF0
        boolean isWHDLoadMode = (mWHDLoadFile != null && !mWHDLoadFile.isEmpty());
        if (!isWHDLoadMode) {
            boolean floppySoundEnabled = p.getBoolean(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED, true);
            int emptyAtt = p.getInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY, 33);
            int diskAtt = p.getInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK, 33);
            for (int i = 0; i < 4; i++) {
                args.add("-s");
                args.add("floppy" + i + "sound=" + (floppySoundEnabled ? "1" : "0"));
                args.add("-s");
                args.add("floppy" + i + "soundvolume_empty=" + emptyAtt);
                args.add("-s");
                args.add("floppy" + i + "soundvolume_disk=" + diskAtt);
            }
        } else {
            // Explicitly disable floppy sounds in WHDLoad mode
            for (int i = 0; i < 4; i++) {
                args.add("-s");
                args.add("floppy" + i + "sound=0");
            }
        }

        if (p.contains(UaeOptionKeys.UAE_FLOPPY_SPEED)) {
            int v = p.getInt(UaeOptionKeys.UAE_FLOPPY_SPEED, 100);
            args.add("-s");
            args.add("floppy_speed=" + v);
        }

        // Input options — always emit joyport settings so WHDBooter gets the
        // user's chosen configuration (defaults: mouse for port 0, joy0 for port 1)
        {
            String source = p.getString(UaeOptionKeys.UAE_INPUT_CONTROLLER_SOURCE, "external");
            String p0 = p.getString(UaeOptionKeys.UAE_INPUT_PORT0_MODE, "mouse");
            if (p0 == null || p0.trim().isEmpty()) p0 = "mouse";
            String p0Trim = p0.trim();
            if (!"virtual".equalsIgnoreCase(source) && p0Trim.toLowerCase(java.util.Locale.ROOT).startsWith("joy")) {
                p0Trim = "mouse";
            }
            args.add("-s");
            args.add("joyport0=" + p0Trim);
        }
        {
            String p1 = p.getString(UaeOptionKeys.UAE_INPUT_PORT1_MODE, "joy0");
            if (p1 == null || p1.trim().isEmpty()) p1 = "joy0";
            args.add("-s");
            args.add("joyport1=" + p1.trim());
        }
        if (p.contains(UaeOptionKeys.UAE_INPUT_MOUSE_SPEED)) {
            args.add("-s");
            args.add("input_mouse_speed=" + p.getInt(UaeOptionKeys.UAE_INPUT_MOUSE_SPEED, 100));
        }
        if (p.contains(UaeOptionKeys.UAE_INPUT_AUTOFIRE_ENABLED)) {
            boolean af = p.getBoolean(UaeOptionKeys.UAE_INPUT_AUTOFIRE_ENABLED, false);
            if (af) {
               args.add("-s");
               args.add("autofire=true");
            }
        }

        // External gamepad per-button mapping to Amiga Joystick/CD32 events.
        // Per-game overrides take priority over global defaults.
        SharedPreferences perGamePrefs = getSharedPreferences(JoyMappingActivity.PREFS_PER_GAME, MODE_PRIVATE);
        String gameId = getGameIdentifier();
        addAndroidJoyMappingsFromPrefs(args, p, perGamePrefs, gameId);

        // Drives / CD overrides
        // CD image (cdimage0) is supported by Amiberry (see amiberry_whdbooter.cpp) in the form:
        //   cdimage0=<path>,image
        String cd0 = null;
        if (mCdImagePath != null && !mCdImagePath.trim().isEmpty()) {
            cd0 = mCdImagePath.trim();
        } else if (p.contains(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH)) {
            cd0 = p.getString(UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH, null);
            if (cd0 != null) cd0 = cd0.trim();
        }
        if (cd0 != null && !cd0.isEmpty()) {
            if (cd0.startsWith("content://")) {
                cd0 = materializeCdImageUriIfNeeded(cd0);
            }
            if (cd0 == null || cd0.trim().isEmpty()) {
                logI("Skipping cdimage0 because selected CD path is unavailable for core mount");
            } else {
            ResolvedMediaPath resolvedCd0 = resolveForCorePathIfNeeded(cd0, /*wantWrite*/ false);
            String cdCorePath = (resolvedCd0 != null && resolvedCd0.corePath != null)
                ? resolvedCd0.corePath.trim()
                : cd0;

            // Match upstream CD parsing behavior: mount the selected image directly.
            // For local CUE files, keep FILE refs normalized to basename/case.
            try {
                if (!cdCorePath.startsWith("content://")) {
                    File cueFile = new File(cdCorePath);
                    if (cueFile.exists() && cueFile.isFile()
                        && "cue".equals(BootstrapMediaUtils.lowerExt(cueFile.getName()))) {
                        BootstrapMediaUtils.fixCueTrackFilenameCase(cueFile);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "CUE normalization failed", t);
            }

            args.add("-s");
            args.add("cdimage0=\"" + escapeForUaeQuoted(cdCorePath) + "\",image");
            logI("Emitting cdimage0=" + cdCorePath);
            }
        }

        boolean isCd32Quickstart = false;
        if (effectiveQsModel != null) {
            String m = effectiveQsModel.toUpperCase(java.util.Locale.ROOT);
            isCd32Quickstart = "CD32".equals(m);
        }

        if (isCd32Quickstart) {
            args.add("-s");
            args.add("cd32cd=1");
        } else if (p.contains(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_DRIVE_CD32CD_ENABLED, false);
            args.add("-s");
            args.add("cd32cd=" + (v ? "1" : "0"));
        }

        if (p.contains(UaeOptionKeys.UAE_DRIVE_MAP_CD_DRIVES)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_DRIVE_MAP_CD_DRIVES, false);
            args.add("-s");
            args.add("map_cd_drives=" + (v ? "true" : "false"));
        }

        if (p.contains(UaeOptionKeys.UAE_DRIVE_CD_TURBO)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_DRIVE_CD_TURBO, false);
            args.add("-s");
            // PanelHD: cd_speed = 0 when turbo enabled, else 100.
            args.add("cd_speed=" + (v ? "0" : "100"));
        }

        // Directory/HDF mounts are disabled for CD-only quickstarts to prevent stale HD prefs
        // from interfering with CD32/CDTV game boot/runtime behavior.
        boolean needsUaeBootRom = false;
        if (!isCdOnlyQuickstart) {
            AgsAutoMountResult agsResult = addAgsAutoMountsFromPrefs(args, p);
            needsUaeBootRom |= agsResult.mountedHardfile;

            if (!agsResult.mountedAny) {
                // Directory mounts (filesystem2)
                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR0_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR0_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR0_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR0_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR0_BOOTPRI,
                    "DH0", "Work", 0);

                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR1_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR1_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR1_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR1_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR1_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR1_BOOTPRI,
                    "DH1", "Work2", -128);

                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR2_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR2_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR2_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR2_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR2_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR2_BOOTPRI,
                    "DH2", "Work3", -129);

                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR3_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR3_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR3_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR3_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR3_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR3_BOOTPRI,
                    "DH3", "Work4", -130);

                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR4_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR4_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR4_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR4_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR4_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR4_BOOTPRI,
                    "DH4", "Work5", -131);

                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR5_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR5_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR5_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR5_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR5_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR5_BOOTPRI,
                    "DH5", "Work6", -132);

                addFilesystem2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_DIR6_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_DIR6_PATH,
                    UaeOptionKeys.UAE_DRIVE_DIR6_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR6_VOLNAME,
                    UaeOptionKeys.UAE_DRIVE_DIR6_READONLY,
                    UaeOptionKeys.UAE_DRIVE_DIR6_BOOTPRI,
                    "DH6", "Work7", -133);

                // HDF mount (hardfile2)
                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF0_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF0_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF0_READONLY,
                    "DH0",
                    0,
                    mKickstartRomFile,
                    romPath);

                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF1_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF1_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF1_READONLY,
                    "DH1",
                    1,
                    mKickstartRomFile,
                    romPath);

                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF2_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF2_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF2_READONLY,
                    "DH2",
                    2,
                    mKickstartRomFile,
                    romPath);

                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF3_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF3_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF3_READONLY,
                    "DH3",
                    3,
                    mKickstartRomFile,
                    romPath);

                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF4_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF4_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF4_READONLY,
                    "DH4",
                    4,
                    mKickstartRomFile,
                    romPath);

                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF5_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF5_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF5_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF5_READONLY,
                    "DH5",
                    5,
                    mKickstartRomFile,
                    romPath);

                needsUaeBootRom |= addHardfile2FromPrefs(args, p,
                    UaeOptionKeys.UAE_DRIVE_HDF6_ENABLED,
                    UaeOptionKeys.UAE_DRIVE_HDF6_PATH,
                    UaeOptionKeys.UAE_DRIVE_HDF6_DEVNAME,
                    UaeOptionKeys.UAE_DRIVE_HDF6_READONLY,
                    "DH6",
                    6,
                    mKickstartRomFile,
                    romPath);
            }
        }

        // If any hardfile2 entry is using the "uae" controller, the uaehf.device driver must be
        // available early. Ensure the UAE Boot ROM is enabled unless the user explicitly chose a
        // uaeboard/boot_rom_uae policy.
        if (needsUaeBootRom && !p.contains(UaeOptionKeys.UAE_ROM_UAEBOARD_INDEX)) {
            args.add("-s");
            args.add("uaeboard=min");
            args.add("-s");
            args.add("boot_rom_uae=automatic");
        }

        // ROM overrides
        if (p.contains(UaeOptionKeys.UAE_ROM_KICKSTART_FILE)) {
            String v = p.getString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("kickstart_rom_file=" + v.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_ROM_EXT_FILE)) {
            String v = p.getString(UaeOptionKeys.UAE_ROM_EXT_FILE, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("kickstart_ext_rom_file=" + v.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_ROM_CART_FILE)) {
            String v = p.getString(UaeOptionKeys.UAE_ROM_CART_FILE, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("cart_file=" + v.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_ROM_MAPROM)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_ROM_MAPROM, false);
            args.add("-s");
            args.add("maprom=" + (v ? "0x0f000000" : "0"));
        }

        if (p.contains(UaeOptionKeys.UAE_ROM_KICKSHIFTER)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_ROM_KICKSHIFTER, false);
            args.add("-s");
            args.add("kickshifter=" + (v ? "true" : "false"));
        }

        if (p.contains(UaeOptionKeys.UAE_ROM_UAEBOARD_INDEX)) {
            int idx = p.getInt(UaeOptionKeys.UAE_ROM_UAEBOARD_INDEX, 0);
            final String[] uaeBoardModes = new String[]{"disabled", "min", "full", "full+indirect"};

            if (idx <= 0) {
                // GUI: uaeboard = 0, boot_rom = disabled
                args.add("-s");
                args.add("uaeboard=disabled");
                if (!needsUaeBootRom) {
                    args.add("-s");
                    args.add("boot_rom_uae=disabled");
                }
            } else {
                int mode = idx - 1;
                if (mode < 0 || mode >= uaeBoardModes.length) mode = 0;
                args.add("-s");
                args.add("uaeboard=" + uaeBoardModes[mode]);
                args.add("-s");
                args.add("boot_rom_uae=automatic");
            }
        }

        // If we mounted any hardfile using the UAE controller (uae0/uae1...), we must ensure
        // the UAE Boot ROM is enabled, otherwise Kickstart won't be able to mount/boot it.
        // Also keep the resource name as uaehf.device (not renamed to scsi.device).
        if (needsUaeBootRom) {
            args.add("-s");
            args.add("boot_rom_uae=automatic");
            args.add("-s");
            args.add("scsidev_mode=original");
        }

        // RAM / Memory overrides
        // Skip when WHDLoad autoload is active — WHDBooter sets the right memory config
        boolean skipMemoryOverrides = (mWHDLoadFile != null && !mWHDLoadFile.isEmpty());
        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE)) {
            int v = p.getInt(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE, 2);
            args.add("-s");
            args.add("chipmem_size=" + v);
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE)) {
            int v = p.getInt(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE, 0);
            args.add("-s");
            args.add("bogomem_size=" + v);
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES)) {
            int bytes = p.getInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, 0);
            if (bytes <= 0) {
                args.add("-s");
                args.add("fastmem_size=0");
            } else if (bytes < 0x100000) {
                args.add("-s");
                args.add("fastmem_size_k=" + (bytes / 1024));
            } else {
                args.add("-s");
                args.add("fastmem_size=" + (bytes / 0x100000));
            }
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB)) {
            int v = p.getInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, 0);
            args.add("-s");
            args.add("z3mem_size=" + v);
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB)) {
            int v = p.getInt(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB, 0);
            args.add("-s");
            args.add("megachipmem_size=" + v);
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB)) {
            int v = p.getInt(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB, 0);
            args.add("-s");
            args.add("a3000mem_size=" + v);
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB)) {
            int v = p.getInt(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB, 0);
            args.add("-s");
            args.add("mbresmem_size=" + v);
        }

        if (!skipMemoryOverrides && p.contains(UaeOptionKeys.UAE_MEM_Z3MAPPING)) {
            String v = p.getString(UaeOptionKeys.UAE_MEM_Z3MAPPING, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("z3mapping=" + v.trim());
            }
        }

        // RTG (Picasso96 / UAEGFX)
        // cfgfile.cpp uses: gfxcard_size (MB) + gfxcard_type (string).
        if (p.contains(UaeOptionKeys.UAE_GFXCARD_SIZE_MB)) {
            int v = p.getInt(UaeOptionKeys.UAE_GFXCARD_SIZE_MB, 0);
            args.add("-s");
            args.add("gfxcard_size=" + v);
        }

        if (p.contains(UaeOptionKeys.UAE_GFXCARD_TYPE)) {
            String v = p.getString(UaeOptionKeys.UAE_GFXCARD_TYPE, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("gfxcard_type=" + v.trim());
            }
        }

        // Skip chipset/CPU/cycle settings when WHDLoad autoload is active
        // WHDBooter will configure these based on game requirements
        boolean skipBootstrapSettings = (mWHDLoadFile != null && !mWHDLoadFile.isEmpty());
        
        if (!skipBootstrapSettings && !isCdOnlyQuickstart) {
            if (p.contains(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE)) {
                String v = p.getString(UaeOptionKeys.UAE_CHIPSET_COMPATIBLE, null);
                if (v != null) {
                    v = v.trim();
                    if (!v.isEmpty() && !"-".equals(v)) {
                        args.add("-s");
                        args.add("chipset_compatible=" + v);
                    }
                }
            } else if (mMachinePreset != null && !mMachinePreset.isEmpty()) {
                args.add("-s");
                args.add("chipset_compatible=" + mMachinePreset);
            }

            if (p.contains(UaeOptionKeys.UAE_CHIPSET)) {
                String v = p.getString(UaeOptionKeys.UAE_CHIPSET, null);
                if (v != null && !v.trim().isEmpty()) {
                    args.add("-s");
                    args.add("chipset=" + v.trim());
                }
            }

            if (p.contains(UaeOptionKeys.UAE_NTSC)) {
                boolean v = p.getBoolean(UaeOptionKeys.UAE_NTSC, false);
                args.add("-s");
                args.add("ntsc=" + (v ? "true" : "false"));
            }

            if (p.contains(UaeOptionKeys.UAE_CYCLE_EXACT)) {
                String v = p.getString(UaeOptionKeys.UAE_CYCLE_EXACT, null);
                if (v != null && !v.trim().isEmpty()) {
                    args.add("-s");
                    args.add("cycle_exact=" + v.trim());
                }
            }
        }

        if (p.contains(UaeOptionKeys.UAE_COLLISION_LEVEL)) {
            String v = p.getString(UaeOptionKeys.UAE_COLLISION_LEVEL, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("collision_level=" + v.trim());
            }
        }

        if (!skipBootstrapSettings && !isCdOnlyQuickstart) {
            boolean cpuModelSet = false;
            if (p.contains(UaeOptionKeys.UAE_CPU_MODEL)) {
                String v = p.getString(UaeOptionKeys.UAE_CPU_MODEL, null);
                if (v != null && !v.trim().isEmpty()) {
                    cpuModelSet = true;
                    args.add("-s");
                    args.add("cpu_model=" + v.trim());
                }
            }

            if (!cpuModelSet && mCpuType != null && !mCpuType.isEmpty()) {
                args.add("-s");
                args.add("cpu_type=" + mCpuType);
            }

            if (p.contains(UaeOptionKeys.UAE_CPU_SPEED)) {
                String v = p.getString(UaeOptionKeys.UAE_CPU_SPEED, null);
                if (v != null && !v.trim().isEmpty()) {
                    args.add("-s");
                    args.add("cpu_speed=" + v.trim());
                }
            }

            if (p.contains(UaeOptionKeys.UAE_CPU_MULTIPLIER)) {
                String v = p.getString(UaeOptionKeys.UAE_CPU_MULTIPLIER, null);
                if (v != null && !v.trim().isEmpty()) {
                    args.add("-s");
                    args.add("cpu_multiplier=" + v.trim());
                }
            }

            if (p.contains(UaeOptionKeys.UAE_CPU_COMPATIBLE)) {
                boolean v = p.getBoolean(UaeOptionKeys.UAE_CPU_COMPATIBLE, false);
                args.add("-s");
                args.add("cpu_compatible=" + (v ? "true" : "false"));
            }
        }

        if (!skipBootstrapSettings && !isCdOnlyQuickstart && p.contains(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING, false);
            args.add("-s");
            args.add("cpu_24bit_addressing=" + (v ? "true" : "false"));
        }

        if (!skipBootstrapSettings && !isCdOnlyQuickstart && p.contains(UaeOptionKeys.UAE_CPU_DATA_CACHE)) {
            boolean v = p.getBoolean(UaeOptionKeys.UAE_CPU_DATA_CACHE, false);
            args.add("-s");
            args.add("cpu_data_cache=" + (v ? "true" : "false"));
        }

        if (p.contains(UaeOptionKeys.UAE_MMU_MODEL)) {
            String v = p.getString(UaeOptionKeys.UAE_MMU_MODEL, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("mmu_model=" + v.trim());
            }
        }

        if (p.getBoolean(UaeOptionKeys.UAE_PPC_ENABLED, false)) {
            String impl = p.getString(UaeOptionKeys.UAE_PPC_IMPLEMENTATION, "auto");
            if (impl != null && !impl.trim().isEmpty()) {
                args.add("-s");
                args.add("ppc_implementation=" + impl.trim());
            }

            String idle = p.getString(UaeOptionKeys.UAE_PPC_CPU_IDLE, "disabled");
            if (idle != null && !idle.trim().isEmpty()) {
                args.add("-s");
                args.add("ppc_cpu_idle=" + idle.trim());
            }
        }

        if (p.contains(UaeOptionKeys.UAE_FPU_MODEL)) {
            String v = p.getString(UaeOptionKeys.UAE_FPU_MODEL, null);
            if (v != null && !v.trim().isEmpty()) {
                args.add("-s");
                args.add("fpu_model=" + v.trim());
            }
        }

        if (p.getBoolean(UaeOptionKeys.UAE_FPU_STRICT, false)) {
            args.add("-s");
            args.add("fpu_strict=true");
        }

        if (p.contains(UaeOptionKeys.UAE_JIT_ENABLED) && !p.getBoolean(UaeOptionKeys.UAE_JIT_ENABLED, false)) {
            args.add("-s");
            args.add("cachesize=0");
        } else if (p.contains(UaeOptionKeys.UAE_JIT_ENABLED) || p.contains(UaeOptionKeys.UAE_CACHESIZE)) {
            int cacheSize = p.getInt(UaeOptionKeys.UAE_CACHESIZE, 0);
            // Some configs can persist jit_inhibit=true; ensure Quickstart/JIT selections can actually enable JIT.
            args.add("-s");
            args.add("jit_inhibit=false");
            args.add("-s");
            args.add("cachesize=" + cacheSize);

            if (p.contains(UaeOptionKeys.UAE_COMP_FPU)) {
                boolean v = p.getBoolean(UaeOptionKeys.UAE_COMP_FPU, false);
                args.add("-s");
                args.add("comp_fpu=" + (v ? "true" : "false"));
            }
            if (p.contains(UaeOptionKeys.UAE_COMP_CONSTJUMP)) {
                boolean v = p.getBoolean(UaeOptionKeys.UAE_COMP_CONSTJUMP, false);
                args.add("-s");
                args.add("comp_constjump=" + (v ? "true" : "false"));
            }
            if (p.contains(UaeOptionKeys.UAE_COMP_FLUSHMODE)) {
                String v = p.getString(UaeOptionKeys.UAE_COMP_FLUSHMODE, null);
                if (v != null && !v.trim().isEmpty()) {
                    args.add("-s");
                    args.add("comp_flushmode=" + v.trim());
                }
            }

            if (p.contains(UaeOptionKeys.UAE_COMP_TRUSTMODE)) {
                String v = p.getString(UaeOptionKeys.UAE_COMP_TRUSTMODE, null);
                if (v != null && !v.trim().isEmpty()) {
                    String trust = v.trim();
                    args.add("-s");
                    args.add("comp_trustbyte=" + trust);
                    args.add("-s");
                    args.add("comp_trustword=" + trust);
                    args.add("-s");
                    args.add("comp_trustlong=" + trust);
                    args.add("-s");
                    args.add("comp_trustnaddr=" + trust);
                }
            }
            if (p.contains(UaeOptionKeys.UAE_COMP_NF)) {
                boolean v = p.getBoolean(UaeOptionKeys.UAE_COMP_NF, false);
                args.add("-s");
                args.add("comp_nf=" + (v ? "true" : "false"));
            }
            if (p.contains(UaeOptionKeys.UAE_COMP_CATCHFAULT)) {
                boolean v = p.getBoolean(UaeOptionKeys.UAE_COMP_CATCHFAULT, false);
                args.add("-s");
                args.add("comp_catchfault=" + (v ? "true" : "false"));
            }
        }

        // RTG performance safety: ensure RTG runs non-cycle-exact and at max CPU speed.
        // Some flows (e.g., when launching outside Quickstart) may not carry these prefs, so enforce via runtime args.
        boolean rtgEnabled = false;
        try {
            int vram = p.getInt(UaeOptionKeys.UAE_GFXCARD_SIZE_MB, 0);
            String type = p.getString(UaeOptionKeys.UAE_GFXCARD_TYPE, null);
            if (vram > 0) rtgEnabled = true;
            if (type != null && !type.trim().isEmpty()) rtgEnabled = true;
        } catch (Throwable ignored) {
        }

        if (rtgEnabled) {
            boolean hasCpuSpeed = false;
            boolean hasCycleExact = false;
            for (String a : args) {
                if (a == null) continue;
                if (a.startsWith("cpu_speed=")) hasCpuSpeed = true;
                if (a.startsWith("cycle_exact=")) hasCycleExact = true;
            }
            if (!hasCpuSpeed) {
                args.add("-s");
                args.add("cpu_speed=max");
            }
            if (!hasCycleExact) {
                args.add("-s");
                args.add("cycle_exact=false");
            }
        }

        // If we imported a standard UAE .uae/.cfg file, apply every key/value pair as late -s overrides.
        // This makes loading a .uae behave like a full configuration overwrite.
        try {
            String json = p.getString(UaeOptionKeys.UAE_IMPORTED_CFG_OVERRIDES_JSON, null);
            if (json != null && !json.trim().isEmpty()) {
                JSONObject obj = new JSONObject(json.trim());
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k == null) continue;
                    String key = k.trim();
                    if (key.isEmpty()) continue;
                    String v = obj.optString(key, null);
                    if (v == null) continue;
                    String vv = v.trim();
                    if (vv.isEmpty()) continue;
                    if (isCdOnlyQuickstart) {
                        String lk = key.toLowerCase(java.util.Locale.ROOT);
                        if (lk.startsWith("filesystem") || lk.startsWith("hardfile") || lk.startsWith("uaehf") || lk.startsWith("floppy")) {
                            continue;
                        }
                    }
                    args.add("-s");
                    args.add(key + "=" + vv);
                }
            }
        } catch (Throwable ignored) {
        }

        // Re-assert CD-only safety after imported overrides.
        if (isCdOnlyQuickstart) {
            addSettingArg(args, "nr_floppies", "0");
            addSettingArg(args, "floppy0type", "-1");
            addSettingArg(args, "floppy1type", "-1");
            addSettingArg(args, "floppy2type", "-1");
            addSettingArg(args, "floppy3type", "-1");
            if (isCd32Quickstart) {
                addCd32SafetyOverrides(args);
            }
        }

        String bootMedium = p.getString("boot_medium", "floppy");
        String bootMediumNorm = (bootMedium == null) ? "" : bootMedium.trim().toLowerCase(java.util.Locale.ROOT);
        boolean hasExplicitFloppySelection =
            (mDf0DiskImagePath != null && !mDf0DiskImagePath.trim().isEmpty())
            || (mDf1DiskImagePath != null && !mDf1DiskImagePath.trim().isEmpty())
            || (mDf2DiskImagePath != null && !mDf2DiskImagePath.trim().isEmpty())
            || (mDf3DiskImagePath != null && !mDf3DiskImagePath.trim().isEmpty());
        boolean allowFloppyCli = !isCdOnlyQuickstart
            && (!("hd".equals(bootMediumNorm)
            || "hdf".equals(bootMediumNorm)
            || "cd".equals(bootMediumNorm)
            || "cdrom".equals(bootMediumNorm)
            || "cd32".equals(bootMediumNorm))
            || hasExplicitFloppySelection);

        if (allowFloppyCli && mDf0DiskImagePath != null && !mDf0DiskImagePath.isEmpty()) {
            args.add("-0");
            args.add(mDf0DiskImagePath);
        }

        if (allowFloppyCli && mDf1DiskImagePath != null && !mDf1DiskImagePath.isEmpty()) {
            args.add("-1");
            args.add(mDf1DiskImagePath);
        }

        if (allowFloppyCli && mDf2DiskImagePath != null && !mDf2DiskImagePath.isEmpty()) {
            args.add("-2");
            args.add(mDf2DiskImagePath);
        }

        if (allowFloppyCli && mDf3DiskImagePath != null && !mDf3DiskImagePath.isEmpty()) {
            args.add("-3");
            args.add(mDf3DiskImagePath);
        }

        // Diagnostics: verify JIT intent/prefs and the actual cachesize argument we emit.
        // This helps debug cases where UI selection doesn't result in JIT enabling at runtime.
        try {
            boolean jitEnabled = p.getBoolean(UaeOptionKeys.UAE_JIT_ENABLED, false);
            int cachePref = p.getInt(UaeOptionKeys.UAE_CACHESIZE, 0);
            String emittedCache = null;
            String emittedJitInhibit = null;
            String emittedCpuModel = null;
            String emittedCpuSpeed = null;
            String emittedCycleExact = null;
            for (String a : args) {
                if (a != null && a.startsWith("cachesize=")) {
                    emittedCache = a;
                }
                if (a != null && a.startsWith("jit_inhibit=")) {
                    emittedJitInhibit = a;
                }
                if (a != null && a.startsWith("cpu_model=")) {
                    emittedCpuModel = a;
                }
                if (a != null && a.startsWith("cpu_speed=")) {
                    emittedCpuSpeed = a;
                }
                if (a != null && a.startsWith("cycle_exact=")) {
                    emittedCycleExact = a;
                }
            }

            String imported = p.getString(UaeOptionKeys.UAE_IMPORTED_CFG_OVERRIDES_JSON, null);
            boolean importedActive = imported != null && !imported.trim().isEmpty();
            logI("JIT prefs: enabled=" + jitEnabled + " cachePref=" + cachePref + " emitted=" + emittedCache
                + " " + (emittedJitInhibit != null ? emittedJitInhibit : "jit_inhibit=(none)")
                + " " + (emittedCpuModel != null ? emittedCpuModel : "cpu_model=(none)")
                + " " + (emittedCpuSpeed != null ? emittedCpuSpeed : "cpu_speed=(none)")
                + " " + (emittedCycleExact != null ? emittedCycleExact : "cycle_exact=(none)")
                + " importedOverridesActive=" + importedActive);
        } catch (Throwable ignored) {
        }

        return args.toArray(new String[0]);
    }

    private File getAmiberryBaseDir() {
        File f = AppPaths.getBaseDir(this);
        ensureDir(f);
        return f;
    }

    private File getLogFile() {
        // Keep legacy name if it already exists, but default to the branded name.
        File base = getAmiberryBaseDir();
        File legacy = new File(base, "amiberry.log");
        if (legacy.exists()) return legacy;
        return new File(base, "uae4arm.log");
    }

    private File getRomsDir() {
        return new File(getAmiberryBaseDir(), "roms");
    }

    private File getDisksDir() {
        return new File(getAmiberryBaseDir(), "disks");
    }

    private void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private void configureDf0DiskSource() {
        // Do not override an explicitly provided DF0.
        if (mDf0DiskImagePath != null && !mDf0DiskImagePath.isEmpty()) {
            return;
        }

        // Prefer internal storage (imported via launcher).
        File internal = new File(getDisksDir(), DF0_DISK_NAME);
        if (internal.exists() && internal.canRead()) {
            mDf0DiskImagePath = internal.getAbsolutePath();
            logI("Using internal DF0 disk image: " + mDf0DiskImagePath);
            return;
        }

        // Otherwise, try external legacy locations.
        for (String rel : new String[]{EXTERNAL_DF0_REL_1, EXTERNAL_DF0_REL_2}) {
            File external = getExternalDf0DiskFile(rel);
            if (external.exists()) {
                if (external.canRead()) {
                    mDf0DiskImagePath = external.getAbsolutePath();
                    logI("Using external DF0 disk image: " + mDf0DiskImagePath);
                    return;
                }
                Log.w(TAG, "External DF0 exists but is not readable: " + external.getAbsolutePath());
            }
        }
    }

    private void provisionFloppySoundsIfPossible() {
        try {
            File soundsDir = new File(getAmiberryDataDir(), "floppy_sounds");
            ensureDir(soundsDir);

            AssetManager am = getAssets();
            String[] files = am.list("floppy_sounds");
            if (files == null || files.length == 0) {
                Log.w(TAG, "No floppy sounds found in APK (assets/floppy_sounds).");
                return;
            }
            for (String name : files) {
                if (name == null) continue;
                String n = name.trim();
                if (n.isEmpty()) continue;
                if (!n.toLowerCase().endsWith(".wav")) continue;
                copyAssetIfMissing("floppy_sounds/" + n, new File(soundsDir, n));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Floppy sounds provisioning failed: " + t);
        }
    }

    private void provisionWhdBootAssetsIfPossible() {
        try {
            File whdbootDir = new File(getAmiberryBaseDir(), "whdboot");
            ensureDir(whdbootDir);

            AssetManager am = getAssets();
            String[] files = am.list("whdboot");
            if (files == null || files.length == 0) {
                Log.w(TAG, "No WHDBoot assets found in APK (assets/whdboot).");
                return;
            }

            for (String name : files) {
                if (name == null) continue;
                String n = name.trim();
                if (n.isEmpty()) continue;
                if ("game-data".equals(n)) continue; // Handle subdir separately
                copyAssetIfMissing("whdboot/" + n, new File(whdbootDir, n));
            }

            File gameDataDir = new File(whdbootDir, "game-data");
            ensureDir(gameDataDir);
            String[] gameDataFiles = am.list("whdboot/game-data");
            if (gameDataFiles != null) {
                for (String name : gameDataFiles) {
                    if (name == null) continue;
                    copyAssetIfMissing("whdboot/game-data/" + name, new File(gameDataDir, name));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "WHDBoot provisioning failed: " + t);
        }
    }

    private void configureFloppySourcesFromPrefs() {
        boolean isCdOnlyQuickstart = false;
        try {
            if (mQsModel != null) {
                String m = mQsModel.trim().toUpperCase();
                isCdOnlyQuickstart = "CD32".equals(m) || "CDTV".equals(m) || "ALG".equals(m) || "ARCADIA".equals(m);
            }
        } catch (Throwable ignored) {
        }

        if (isCdOnlyQuickstart) {
            mDf0DiskImagePath = null;
            mDf1DiskImagePath = null;
            mDf2DiskImagePath = null;
            mDf3DiskImagePath = null;
            logI("CD-only quickstart model; skipping floppy path restore from prefs.");
            return;
        }

        final SharedPreferences p = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);

        if (mDf0DiskImagePath == null || mDf0DiskImagePath.trim().isEmpty()) {
            try {
                String df0 = p.getString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, null);
                if (df0 != null && !df0.trim().isEmpty()) {
                    String s = df0.trim();
                    if (s.startsWith("content://")) {
                        if (SafFileBridge.exists(s)) {
                            mDf0DiskImagePath = s;
                            logI("Using DF0 disk image from prefs (SAF): " + mDf0DiskImagePath);
                        }
                    } else {
                        File f = new File(s);
                        if (f.exists() && f.canRead() && f.isFile()) {
                            mDf0DiskImagePath = f.getAbsolutePath();
                            logI("Using DF0 disk image from prefs: " + mDf0DiskImagePath);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (mDf1DiskImagePath == null || mDf1DiskImagePath.trim().isEmpty()) {
            try {
                String df1 = p.getString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, null);
                if (df1 != null && !df1.trim().isEmpty()) {
                    String s = df1.trim();
                    if (s.startsWith("content://")) {
                        if (SafFileBridge.exists(s)) {
                            mDf1DiskImagePath = s;
                            logI("Using DF1 disk image from prefs (SAF): " + mDf1DiskImagePath);
                        }
                    } else {
                        File f = new File(s);
                        if (f.exists() && f.canRead() && f.isFile()) {
                            mDf1DiskImagePath = f.getAbsolutePath();
                            logI("Using DF1 disk image from prefs: " + mDf1DiskImagePath);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (mDf2DiskImagePath == null || mDf2DiskImagePath.trim().isEmpty()) {
            try {
                String df2 = p.getString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, null);
                if (df2 != null && !df2.trim().isEmpty()) {
                    String s = df2.trim();
                    File f = new File(s);
                    if (f.exists() && f.canRead() && f.isFile()) {
                        mDf2DiskImagePath = f.getAbsolutePath();
                        logI("Using DF2 disk image from prefs: " + mDf2DiskImagePath);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (mDf3DiskImagePath == null || mDf3DiskImagePath.trim().isEmpty()) {
            try {
                String df3 = p.getString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, null);
                if (df3 != null && !df3.trim().isEmpty()) {
                    String s = df3.trim();
                    File f = new File(s);
                    if (f.exists() && f.canRead() && f.isFile()) {
                        mDf3DiskImagePath = f.getAbsolutePath();
                        logI("Using DF3 disk image from prefs: " + mDf3DiskImagePath);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
