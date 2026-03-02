package com.uae4arm2026;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

final class ConfigStorage {
    private ConfigStorage() {}

    private static volatile String sLastError;

    static String getLastError() {
        String e = sLastError;
        return e == null ? "" : e;
    }

    private static void setLastError(String msg) {
        sLastError = msg;
    }

    static final String EXT = ".cfg";
    static final String EXT_UAE = ".uae";
    static final String LAST_RAN_FILENAME = "lastran" + EXT;

    // Some SAF providers (notably some file managers) will append ".txt" when creating
    // "text/plain" documents, even if the display name already contains ".cfg".
    // Accept these so saved configs still appear in the list.
    private static final String EXT_TXT = ".txt";

    private static final String PREFIX_UAE = "uae_";
    private static final String PREFIX_QS = "qs_";

    // Non-uae_* keys from BootstrapActivity that should be part of a saved config.
    // (These are UI inputs that affect how the launcher constructs the runtime prefs.)
    private static final String[] BOOTSTRAP_CONFIG_KEYS = new String[] {
        "kick_src",
        "ext_src",
        "cd0_src",
        "df0_src",
        "df1_src",
        "df2_src",
        "df3_src",
        "dh0_src",
        "dh1_src",
        "dh2_src",
        "dh3_src",
        "dh4_src",
        "show_df1",
        "show_df2",
        "show_df3",
        "show_dh0",
        "show_dh1",
        "show_dh2",
        "show_dh3",
        "show_dh4",
        "show_cd0",
        "boot_medium",
        "dh0_mode",
        "dh1_mode",
    };

    private static boolean isBootstrapConfigKey(String k) {
        if (k == null) return false;
        for (String v : BOOTSTRAP_CONFIG_KEYS) {
            if (k.equals(v)) return true;
        }
        return false;
    }

    private static boolean shouldPersistKey(String k) {
        return k != null && (k.startsWith(PREFIX_UAE) || k.startsWith(PREFIX_QS) || isBootstrapConfigKey(k));
    }

    private static String readDecodedString(Map<String, String> loaded, String key) {
        if (loaded == null || key == null) return null;
        String enc = loaded.get(key);
        if (enc == null) return null;
        String s = enc.trim();
        if (s.startsWith("s:")) return unescape(s.substring(2)).trim();
        return s.trim();
    }

    private static String normalizeToken(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String stripKnownExtension(String name) {
        if (name == null) return "";
        String n = name.trim();
        int dot = n.lastIndexOf('.');
        if (dot > 0) return n.substring(0, dot);
        return n;
    }

    private static String candidateFromConfigFilename(String filename) {
        if (filename == null) return null;
        String base = filename.trim();
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith(EXT)) base = base.substring(0, base.length() - EXT.length());
        if (lower.endsWith(EXT_UAE)) base = base.substring(0, base.length() - EXT_UAE.length());

        String b = base.toLowerCase(Locale.ROOT);
        if (b.startsWith("floppies_")) {
            String rest = base.substring("floppies_".length());
            String[] modelSuffixes = new String[] {"_a500", "_a1200", "_a4000", "_cd32", "_cdtv", "_alg", "_arcadia"};
            String restLower = rest.toLowerCase(Locale.ROOT);
            for (String suf : modelSuffixes) {
                if (restLower.endsWith(suf)) {
                    return rest.substring(0, rest.length() - suf.length()).trim();
                }
            }
            return rest.trim();
        }
        return null;
    }

    private static String findFileInJoinedOrFsDir(Context ctx, String joinedOrFsDir, String sourceName) {
        if (ctx == null || joinedOrFsDir == null || sourceName == null) return null;
        String dir = joinedOrFsDir.trim();
        String wanted = sourceName.trim();
        if (dir.isEmpty() || wanted.isEmpty()) return null;

        try {
            if (isSafJoinedPath(dir)) {
                SafPath sp = splitSafJoinedPath(dir);
                if (sp == null || sp.treeUri == null) return null;
                DocumentFile root = resolveTreePath(ctx, sp.treeUri, sp.relPath, false);
                if (root == null || !root.exists() || !root.isDirectory()) return null;
                DocumentFile[] kids = root.listFiles();
                if (kids == null) return null;
                String wantedNorm = normalizeToken(stripKnownExtension(wanted));
                for (DocumentFile kid : kids) {
                    if (kid == null || !kid.exists() || kid.isDirectory()) continue;
                    String n = kid.getName();
                    if (n != null && n.equalsIgnoreCase(wanted)) {
                        Uri u = kid.getUri();
                        return u == null ? null : u.toString();
                    }
                }
                for (DocumentFile kid : kids) {
                    if (kid == null || !kid.exists() || kid.isDirectory()) continue;
                    String n = kid.getName();
                    if (n == null) continue;
                    String fileNorm = normalizeToken(stripKnownExtension(n));
                    if (wantedNorm.isEmpty() || fileNorm.isEmpty()) continue;
                    if (fileNorm.equals(wantedNorm) || fileNorm.contains(wantedNorm) || wantedNorm.contains(fileNorm)) {
                        Uri u = kid.getUri();
                        return u == null ? null : u.toString();
                    }
                }
                return null;
            }

            File base = new File(dir);
            if (!base.exists() || !base.isDirectory()) return null;
            File[] kids = base.listFiles();
            if (kids == null) return null;
            String wantedNorm = normalizeToken(stripKnownExtension(wanted));
            for (File f : kids) {
                if (f == null || !f.exists() || !f.isFile()) continue;
                String n = f.getName();
                if (n != null && n.equalsIgnoreCase(wanted)) {
                    return f.getAbsolutePath();
                }
            }
            for (File f : kids) {
                if (f == null || !f.exists() || !f.isFile()) continue;
                String n = f.getName();
                if (n == null) continue;
                String fileNorm = normalizeToken(stripKnownExtension(n));
                if (wantedNorm.isEmpty() || fileNorm.isEmpty()) continue;
                if (fileNorm.equals(wantedNorm) || fileNorm.contains(wantedNorm) || wantedNorm.contains(fileNorm)) {
                    return f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isMissingLocalFilePath(String path) {
        if (path == null) return true;
        String p = path.trim();
        if (p.isEmpty()) return true;
        if (p.startsWith("content://")) return false;
        try {
            File f = new File(p);
            return !(f.exists() && f.isFile());
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void rehydrateMediaPathsFromSourceNames(Context ctx, SharedPreferences prefs, SharedPreferences.Editor e, Map<String, String> loaded, String loadedFilename) {
        if (ctx == null || prefs == null || e == null || loaded == null || loaded.isEmpty()) return;

        try {
            final String floppyDir = prefs.getString(UaeOptionKeys.UAE_PATH_FLOPPIES_DIR, null);
            final String cdromDir = prefs.getString(UaeOptionKeys.UAE_PATH_CDROMS_DIR, null);
            final String harddrivesDir = prefs.getString(UaeOptionKeys.UAE_PATH_HARDDRIVES_DIR, null);
            final String kickstartsDir = prefs.getString(UaeOptionKeys.UAE_PATH_KICKSTARTS_DIR, null);

            final String[] srcKeys = new String[] {"df0_src", "df1_src", "df2_src", "df3_src", "cd0_src", "dh0_src", "dh1_src", "dh2_src", "dh3_src", "dh4_src", "dh5_src", "dh6_src"};
            final String[] pathKeys = new String[] {
                UaeOptionKeys.UAE_DRIVE_DF0_PATH,
                UaeOptionKeys.UAE_DRIVE_DF1_PATH,
                UaeOptionKeys.UAE_DRIVE_DF2_PATH,
                UaeOptionKeys.UAE_DRIVE_DF3_PATH,
                UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF0_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF1_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF2_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF3_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF4_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF5_PATH,
                UaeOptionKeys.UAE_DRIVE_HDF6_PATH
            };
            final String[] enabledKeys = new String[] {
                null,
                null,
                null,
                null,
                null,
                UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED,
                UaeOptionKeys.UAE_DRIVE_HDF1_ENABLED,
                UaeOptionKeys.UAE_DRIVE_HDF2_ENABLED,
                UaeOptionKeys.UAE_DRIVE_HDF3_ENABLED,
                UaeOptionKeys.UAE_DRIVE_HDF4_ENABLED,
                UaeOptionKeys.UAE_DRIVE_HDF5_ENABLED,
                UaeOptionKeys.UAE_DRIVE_HDF6_ENABLED
            };

            for (int i = 0; i < srcKeys.length; i++) {
                String sourceName = readDecodedString(loaded, srcKeys[i]);
                if (sourceName == null || sourceName.isEmpty()) continue;

                String existingPath = readDecodedString(loaded, pathKeys[i]);
                if (existingPath != null && !existingPath.isEmpty() && !isMissingLocalFilePath(existingPath)) continue;

                String candidateDir;
                if (i <= 3) {
                    candidateDir = floppyDir;
                } else if (i == 4) {
                    candidateDir = cdromDir;
                } else {
                    candidateDir = harddrivesDir;
                }
                if (candidateDir == null || candidateDir.trim().isEmpty()) continue;

                String resolved = findFileInJoinedOrFsDir(ctx, candidateDir, sourceName);
                if (resolved == null || resolved.trim().isEmpty()) continue;

                applyDecoded(e, pathKeys[i], "s:" + escape(resolved));
                if (enabledKeys[i] != null) {
                    applyDecoded(e, enabledKeys[i], "b:true");
                }
            }

            final String[] romSrcKeys = new String[] {"kick_src", "ext_src"};
            final String[] romPathKeys = new String[] {
                UaeOptionKeys.UAE_ROM_KICKSTART_FILE,
                UaeOptionKeys.UAE_ROM_EXT_FILE
            };
            final String[] romLabelKeys = new String[] {
                UaeOptionKeys.UAE_ROM_KICKSTART_LABEL,
                UaeOptionKeys.UAE_ROM_EXT_LABEL
            };

            for (int i = 0; i < romSrcKeys.length; i++) {
                String sourceName = readDecodedString(loaded, romSrcKeys[i]);
                if (sourceName == null || sourceName.isEmpty()) continue;

                String existingPath = readDecodedString(loaded, romPathKeys[i]);
                if (existingPath != null && !existingPath.isEmpty() && !isMissingLocalFilePath(existingPath)) continue;

                String resolved = null;
                if (kickstartsDir != null && !kickstartsDir.trim().isEmpty()) {
                    resolved = findFileInJoinedOrFsDir(ctx, kickstartsDir, sourceName);
                }
                if (resolved != null && !resolved.trim().isEmpty()) {
                    applyDecoded(e, romPathKeys[i], "s:" + escape(resolved));
                    applyDecoded(e, romLabelKeys[i], "s:" + escape(sourceName));
                }
            }

            // Extra fallback for older/malformed floppy configs: derive DF0 media from config filename
            // like floppies_bad_dudes[_model].cfg when df0 keys are absent.
            String existingDf0 = readDecodedString(loaded, UaeOptionKeys.UAE_DRIVE_DF0_PATH);
            String existingDf0Src = readDecodedString(loaded, "df0_src");
            if ((existingDf0 == null || existingDf0.isEmpty()) && (existingDf0Src == null || existingDf0Src.isEmpty())) {
                String guessed = candidateFromConfigFilename(loadedFilename);
                if (guessed != null && !guessed.isEmpty() && floppyDir != null && !floppyDir.trim().isEmpty()) {
                    String resolved = findFileInJoinedOrFsDir(ctx, floppyDir, guessed);
                    if (resolved != null && !resolved.trim().isEmpty()) {
                        applyDecoded(e, UaeOptionKeys.UAE_DRIVE_DF0_PATH, "s:" + escape(resolved));
                        applyDecoded(e, "df0_src", "s:" + escape(guessed));
                        applyDecoded(e, "boot_medium", "s:" + escape("floppy"));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static String getConfigDirString(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(UaeOptionKeys.PREFS_NAME, Context.MODE_PRIVATE);
            String configured = p.getString(UaeOptionKeys.UAE_PATH_CONF_DIR, null);
            if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        } catch (Throwable ignored) {
        }
        return new File(AppPaths.getBaseDir(ctx), "conf").getAbsolutePath();
    }

    static boolean isSafJoinedPath(String p) {
        if (p == null) return false;
        String s = p.trim();
        return s.startsWith("content://") && s.contains("::");
    }

    static final class SafPath {
        final String treeUri;
        final String relPath; // may start with '/'

        SafPath(String treeUri, String relPath) {
            this.treeUri = treeUri;
            this.relPath = relPath;
        }
    }

    static SafPath splitSafJoinedPath(String joined) {
        if (joined == null) return null;
        String s = joined.trim();
        int idx = s.indexOf("::");
        if (idx <= 0) return null;
        String tree = s.substring(0, idx);
        String rel = s.substring(idx + 2);
        if (rel.isEmpty()) rel = "/";
        return new SafPath(tree, rel);
    }

    private static boolean hasPersistedPermission(Context ctx, String uriString, boolean requireWrite) {
        if (ctx == null || uriString == null) return false;
        String s = uriString.trim();
        if (!s.startsWith("content://")) return false;
        try {
            Uri u = Uri.parse(s);
            List<android.content.UriPermission> perms = ctx.getContentResolver().getPersistedUriPermissions();
            if (perms == null) return false;
            for (android.content.UriPermission p : perms) {
                if (p == null) continue;
                Uri pu = p.getUri();
                if (pu == null || !pu.equals(u)) continue;
                if (requireWrite) {
                    if (p.isWritePermission()) return true;
                } else {
                    if (p.isReadPermission() || p.isWritePermission()) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasPersistedReadPermissionForSafJoinedDir(Context ctx, String joinedDir) {
        if (joinedDir == null || !isSafJoinedPath(joinedDir)) return false;
        SafPath sp = splitSafJoinedPath(joinedDir);
        return sp != null && hasPersistedPermission(ctx, sp.treeUri, false);
    }

    private static boolean hasPersistedWritePermissionForSafJoinedDir(Context ctx, String joinedDir) {
        if (joinedDir == null || !isSafJoinedPath(joinedDir)) return false;
        SafPath sp = splitSafJoinedPath(joinedDir);
        return sp != null && hasPersistedPermission(ctx, sp.treeUri, true);
    }

    private static DocumentFile resolveTreePath(Context ctx, String treeUriString, String relPath, boolean createMissingDirs) {
        if (ctx == null) return null;
        if (treeUriString == null || treeUriString.trim().isEmpty()) return null;

        Uri treeUri = Uri.parse(treeUriString.trim());
        DocumentFile cur = DocumentFile.fromTreeUri(ctx, treeUri);
        if (cur == null) return null;

        if (relPath == null) return cur;
        String p = relPath.trim();
        if (p.isEmpty() || "/".equals(p)) return cur;

        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return cur;

        String[] parts = p.split("/");
        for (String part : parts) {
            if (part == null) continue;
            String seg = part.trim();
            if (seg.isEmpty()) continue;
            DocumentFile next = cur.findFile(seg);
            if (next == null && createMissingDirs) {
                try {
                    next = cur.createDirectory(seg);
                } catch (Throwable ignored) {
                    next = null;
                }
            }
            if (next == null) return null;
            cur = next;
        }
        return cur;
    }

    private static DocumentFile resolveConfigDirSaf(Context ctx, String joinedPath, boolean createIfMissing) {
        SafPath sp = splitSafJoinedPath(joinedPath);
        if (sp == null) return null;
        DocumentFile df = resolveTreePath(ctx, sp.treeUri, sp.relPath, createIfMissing);
        if (df == null || !df.exists() || !df.isDirectory()) return null;
        return df;
    }

    private static boolean sameFilenameIgnoringProviderSuffix(String candidate, String wanted) {
        if (candidate == null || wanted == null) return false;
        String c = candidate.trim().toLowerCase(Locale.ROOT);
        String w = wanted.trim().toLowerCase(Locale.ROOT);
        if (c.equals(w)) return true;
        return c.equals(w + EXT_TXT);
    }

    private static DocumentFile findConfigFileDoc(DocumentFile dir, String filename) {
        if (dir == null || filename == null) return null;
        try {
            DocumentFile exact = dir.findFile(filename);
            if (exact != null && exact.exists() && !exact.isDirectory()) return exact;
        } catch (Throwable ignored) {
        }
        try {
            DocumentFile[] kids = dir.listFiles();
            if (kids == null) return null;
            for (DocumentFile kid : kids) {
                if (kid == null || !kid.exists() || kid.isDirectory()) continue;
                String n = kid.getName();
                if (sameFilenameIgnoringProviderSuffix(n, filename)) return kid;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static final class ConfigEntry {
        final String name; // filename
        final boolean isLastRan;

        ConfigEntry(String name, boolean isLastRan) {
            this.name = name;
            this.isLastRan = isLastRan;
        }

        String displayName() {
            if (isLastRan) return "Last Ran (auto)";
            if (name == null) return "";
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(EXT + EXT_TXT)) return name.substring(0, name.length() - (EXT.length() + EXT_TXT.length()));
            if (n.endsWith(EXT_UAE + EXT_TXT)) return name.substring(0, name.length() - (EXT_UAE.length() + EXT_TXT.length()));
            if (n.endsWith(EXT)) return name.substring(0, name.length() - EXT.length());
            if (n.endsWith(EXT_UAE)) return name.substring(0, name.length() - EXT_UAE.length());
            return name;
        }
    }

    private static boolean hasSupportedConfigExtension(String filename) {
        if (filename == null) return false;
        String n = filename.toLowerCase(Locale.ROOT);
        return n.endsWith(EXT)
            || n.endsWith(EXT_UAE)
            || n.endsWith(EXT + EXT_TXT)
            || n.endsWith(EXT_UAE + EXT_TXT);
    }

    static List<ConfigEntry> listConfigs(Context ctx) {
        setLastError(null);
        String conf = getConfigDirString(ctx);
        List<ConfigEntry> out = new ArrayList<>();

        if (isSafJoinedPath(conf)) {
            if (!hasPersistedReadPermissionForSafJoinedDir(ctx, conf)) {
                setLastError("Missing SAF read permission for config folder");
                return out;
            }
            DocumentFile dir = resolveConfigDirSaf(ctx, conf, false);
            if (dir == null) {
                setLastError("SAF config folder cannot be resolved: " + conf);
                return out;
            }
            DocumentFile[] kids = dir.listFiles();
            if (kids == null) {
                setLastError("Unable to list files in SAF config folder");
                return out;
            }
            for (DocumentFile k : kids) {
                if (k == null || !k.exists() || k.isDirectory()) continue;
                String n = k.getName();
                if (n == null) continue;
                if (!hasSupportedConfigExtension(n)) continue;
                out.add(new ConfigEntry(n, LAST_RAN_FILENAME.equalsIgnoreCase(n)));
            }
        } else {
            File dir = new File(conf);
            File[] kids = dir.listFiles();
            if (kids == null) {
                if (!dir.exists()) setLastError("Config folder does not exist: " + conf);
                else if (!dir.isDirectory()) setLastError("Config path is not a directory: " + conf);
                return out;
            }
            for (File f : kids) {
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                if (n == null) continue;
                if (!hasSupportedConfigExtension(n)) continue;
                out.add(new ConfigEntry(n, LAST_RAN_FILENAME.equalsIgnoreCase(n)));
            }
        }

        Collections.sort(out, (a, b) -> {
            if (a.isLastRan != b.isLastRan) return a.isLastRan ? -1 : 1;
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        return out;
    }

    private static String sanitizeBaseName(String name) {
        if (name == null) return "";
        String s = name.trim();
        String n = s.toLowerCase(Locale.ROOT);
        if (n.endsWith(EXT)) s = s.substring(0, s.length() - EXT.length());
        else if (n.endsWith(EXT_UAE)) s = s.substring(0, s.length() - EXT_UAE.length());
        s = s.trim();
        // Replace disallowed characters for SAF/providers.
        s = s.replaceAll("[^A-Za-z0-9 _.-]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) s = "config";
        return s;
    }

    static String ensureFilename(String baseOrFilename) {
        String base = sanitizeBaseName(baseOrFilename);
        return base + EXT;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!esc) {
                if (c == '\\') {
                    esc = true;
                } else {
                    out.append(c);
                }
            } else {
                esc = false;
                if (c == 'n') out.append('\n');
                else if (c == 'r') out.append('\r');
                else out.append(c);
            }
        }
        if (esc) out.append('\\');
        return out.toString();
    }

    private static String encodeTyped(Object v) {
        if (v instanceof Boolean) return "b:" + (((Boolean) v) ? "true" : "false");
        if (v instanceof Integer) return "i:" + v;
        if (v instanceof Long) return "l:" + v;
        if (v instanceof Float) return "f:" + v;
        if (v instanceof String) return "s:" + escape((String) v);
        return null;
    }

    private static void applyDecoded(SharedPreferences.Editor e, String key, String encoded) {
        if (encoded == null) return;
        String s = encoded;
        if (s.length() >= 2 && s.charAt(1) == ':') {
            char t = s.charAt(0);
            String payload = s.substring(2);
            try {
                switch (t) {
                    case 'b':
                        e.putBoolean(key, "true".equalsIgnoreCase(payload));
                        return;
                    case 'i':
                        e.putInt(key, Integer.parseInt(payload.trim()));
                        return;
                    case 'l':
                        // SharedPreferences supports long via putLong.
                        e.putLong(key, Long.parseLong(payload.trim()));
                        return;
                    case 'f':
                        e.putFloat(key, Float.parseFloat(payload.trim()));
                        return;
                    case 's':
                        e.putString(key, unescape(payload));
                        return;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
                // fall back to string
            }
        }
        e.putString(key, unescape(s));
    }

    private static Map<String, String> exportPersistedPrefs(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(UaeOptionKeys.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = p.getAll();
        Map<String, String> out = new HashMap<>();
        if (all == null) return out;
        for (Map.Entry<String, ?> ent : all.entrySet()) {
            if (ent == null) continue;
            String k = ent.getKey();
            if (!shouldPersistKey(k)) continue;
            Object v = ent.getValue();
            String enc = encodeTyped(v);
            if (enc != null) out.put(k, enc);
        }
        return out;
    }

    static boolean saveConfig(Context ctx, String filename) {
        setLastError(null);
        String conf = getConfigDirString(ctx);
        String fn = ensureFilename(filename);
        Map<String, String> data = exportPersistedPrefs(ctx);
        return writeConfigFile(ctx, conf, fn, data);
    }

    static boolean saveLastRan(Context ctx) {
        setLastError(null);
        String conf = getConfigDirString(ctx);
        Map<String, String> data = exportPersistedPrefs(ctx);
        return writeConfigFile(ctx, conf, LAST_RAN_FILENAME, data);
    }

    private static boolean writeConfigFile(Context ctx, String confDir, String filename, Map<String, String> data) {
        if (ctx == null) {
            setLastError("Context is null");
            return false;
        }
        if (confDir == null || confDir.trim().isEmpty()) {
            setLastError("Config folder is empty (uae_path_conf_dir)");
            return false;
        }
        if (filename == null || filename.trim().isEmpty()) {
            setLastError("Filename is empty");
            return false;
        }

        OutputStream os = null;
        try {
            if (isSafJoinedPath(confDir)) {
                if (!hasPersistedReadPermissionForSafJoinedDir(ctx, confDir)) {
                    setLastError("Missing SAF read permission for config folder");
                    return false;
                }
                if (!hasPersistedWritePermissionForSafJoinedDir(ctx, confDir)) {
                    setLastError("Missing SAF write permission for config folder");
                    return false;
                }
                DocumentFile dir = resolveConfigDirSaf(ctx, confDir, true);
                if (dir == null) {
                    setLastError("SAF config folder cannot be resolved: " + confDir);
                    return false;
                }

                DocumentFile existing = findConfigFileDoc(dir, filename);
                if (existing != null && existing.exists()) {
                    // Overwrite by deleting and recreating to avoid provider quirks.
                    try { existing.delete(); } catch (Throwable ignored) {}
                }
                // Use a generic MIME type to reduce the chance of the provider force-appending ".txt".
                DocumentFile created = dir.createFile("application/octet-stream", filename);
                if (created == null) {
                    setLastError("SAF provider failed to create file: " + filename);
                    return false;
                }
                os = ctx.getContentResolver().openOutputStream(created.getUri(), "wt");
            } else {
                File dir = new File(confDir);
                if (!dir.exists()) {
                    // Best-effort create for filesystem paths.
                    // SAF paths must be created in file manager.
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                if (!dir.exists() || !dir.isDirectory()) {
                    setLastError("Config folder does not exist or is not a directory: " + confDir);
                    return false;
                }
                File out = new File(dir, filename);
                os = new FileOutputStream(out, false);
            }

            if (os == null) {
                setLastError("Failed to open output stream");
                return false;
            }
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
            w.write("# Android launcher config\n");
            w.write("# Keys stored: '" + PREFIX_UAE + "*', '" + PREFIX_QS + "*', plus a few bootstrap UI keys\n");
            for (Map.Entry<String, String> ent : data.entrySet()) {
                String k = ent.getKey();
                String v = ent.getValue();
                if (k == null || v == null) continue;
                w.write(k);
                w.write('=');
                w.write(v);
                w.write('\n');
            }
            w.flush();
            return true;
        } catch (Throwable t) {
            setLastError(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
        }
    }

    static boolean loadConfig(Context ctx, ConfigEntry entry) {
        if (entry == null) return false;
        return loadConfigByFilename(ctx, entry.name);
    }

    static boolean loadLastRan(Context ctx) {
        return loadConfigByFilename(ctx, LAST_RAN_FILENAME);
    }

    static String readConfigText(Context ctx, ConfigEntry entry) {
        if (entry == null) {
            setLastError("No config selected");
            return null;
        }
        return readConfigTextByFilename(ctx, entry.name);
    }

    private static String readConfigTextByFilename(Context ctx, String filename) {
        setLastError(null);
        if (ctx == null) {
            setLastError("Context is null");
            return null;
        }
        if (filename == null || filename.trim().isEmpty()) {
            setLastError("Filename is empty");
            return null;
        }

        String conf = getConfigDirString(ctx);
        InputStream is = null;
        try {
            if (isSafJoinedPath(conf)) {
                if (!hasPersistedReadPermissionForSafJoinedDir(ctx, conf)) {
                    setLastError("Missing SAF read permission for config folder");
                    return null;
                }
                DocumentFile dir = resolveConfigDirSaf(ctx, conf, false);
                if (dir == null) {
                    setLastError("SAF config folder cannot be resolved: " + conf);
                    return null;
                }
                DocumentFile f = findConfigFileDoc(dir, filename);
                if (f == null || !f.exists() || f.isDirectory()) {
                    setLastError("Config file not found in SAF folder: " + filename);
                    return null;
                }
                is = ctx.getContentResolver().openInputStream(f.getUri());
            } else {
                File f = new File(new File(conf), filename);
                if (!f.exists() || !f.isFile()) {
                    setLastError("Config file not found: " + f.getAbsolutePath());
                    return null;
                }
                is = new FileInputStream(f);
            }

            if (is == null) {
                setLastError("Failed to open config stream for: " + filename);
                return null;
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            setLastError(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean loadConfigByFilename(Context ctx, String filename) {
        if (ctx == null) return false;
        if (filename == null || filename.trim().isEmpty()) return false;

        String conf = getConfigDirString(ctx);
        InputStream is = null;
        try {
            if (isSafJoinedPath(conf)) {
                if (!hasPersistedReadPermissionForSafJoinedDir(ctx, conf)) {
                    setLastError("Missing SAF read permission for config folder");
                    return false;
                }
                DocumentFile dir = resolveConfigDirSaf(ctx, conf, false);
                if (dir == null) {
                    setLastError("SAF config folder cannot be resolved: " + conf);
                    return false;
                }
                DocumentFile f = findConfigFileDoc(dir, filename);
                if (f == null || !f.exists() || f.isDirectory()) {
                    setLastError("Config file not found in SAF folder: " + filename);
                    return false;
                }
                is = ctx.getContentResolver().openInputStream(f.getUri());
            } else {
                File f = new File(new File(conf), filename);
                if (!f.exists() || !f.isFile()) {
                    setLastError("Config file not found: " + f.getAbsolutePath());
                    return false;
                }
                is = new FileInputStream(f);
            }
            if (is == null) {
                setLastError("Failed to open config stream for: " + filename);
                return false;
            }

            Map<String, String> loaded = new HashMap<>();
            Map<String, String> legacy = new HashMap<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                String k = s.substring(0, eq).trim();
                String v = s.substring(eq + 1);
                if (shouldPersistKey(k)) {
                    loaded.put(k, v);
                } else {
                    // Allow importing of standard UAE cfg keys (e.g., floppy0=...)
                    legacy.put(k, v);
                }
            }

            SharedPreferences p = ctx.getSharedPreferences(UaeOptionKeys.PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit();

            // Clear existing persisted keys so missing keys revert to defaults.
            Map<String, ?> all = p.getAll();
            if (all != null) {
                for (String k : all.keySet()) {
                    if (shouldPersistKey(k)) e.remove(k);
                }
            }

            for (Map.Entry<String, String> ent : loaded.entrySet()) {
                applyDecoded(e, ent.getKey(), ent.getValue());
            }

            // Ensure launcher-only UI invariants are consistent with the loaded drive prefs.
            // This prevents cases where a config has DH0 mounted but boot_medium remained "floppy",
            // which would cause BootstrapActivity.syncBootMediumToUaePrefs() to immediately disable it.
            try {
                java.util.function.Function<String, Boolean> decodeBool = (enc) -> {
                    if (enc == null) return null;
                    String s = enc.trim();
                    if (s.startsWith("b:")) {
                        String v = s.substring(2).trim();
                        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
                        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
                    }
                    return null;
                };

                java.util.function.Function<String, String> decodeString = (enc) -> {
                    if (enc == null) return null;
                    String s = enc;
                    if (s.startsWith("s:")) {
                        return unescape(s.substring(2));
                    }
                    return null;
                };

                Boolean hdfEnabled = decodeBool.apply(loaded.get(UaeOptionKeys.UAE_DRIVE_HDF0_ENABLED));
                Boolean dirEnabled = decodeBool.apply(loaded.get(UaeOptionKeys.UAE_DRIVE_DIR0_ENABLED));
                String hdfPath = decodeString.apply(loaded.get(UaeOptionKeys.UAE_DRIVE_HDF0_PATH));
                String dirPath = decodeString.apply(loaded.get(UaeOptionKeys.UAE_DRIVE_DIR0_PATH));
                String df0Path = decodeString.apply(loaded.get(UaeOptionKeys.UAE_DRIVE_DF0_PATH));

                boolean hasHd = (hdfEnabled != null && hdfEnabled)
                    || (dirEnabled != null && dirEnabled)
                    || (hdfPath != null && !hdfPath.trim().isEmpty())
                    || (dirPath != null && !dirPath.trim().isEmpty());
                boolean hasFloppy = df0Path != null && !df0Path.trim().isEmpty();

                // If the config clearly implies HD boot, force boot_medium=hd. If it implies floppy-only, force floppy.
                // If both are present, keep whatever the config already said.
                String bootEnc = loaded.get("boot_medium");
                String boot = decodeString.apply(bootEnc);
                if (hasHd && (!hasFloppy)) {
                    if (boot == null || !"hd".equalsIgnoreCase(boot.trim())) {
                        applyDecoded(e, "boot_medium", "s:" + escape("hd"));
                    }
                } else if (hasFloppy && (!hasHd)) {
                    if (boot == null || !"floppy".equalsIgnoreCase(boot.trim())) {
                        applyDecoded(e, "boot_medium", "s:" + escape("floppy"));
                    }
                }

                // If exactly one DH0 mode is enabled, force dh0_mode to match.
                if ((dirEnabled != null && dirEnabled) && !(hdfEnabled != null && hdfEnabled)) {
                    applyDecoded(e, "dh0_mode", "s:" + escape("dir"));
                } else if ((hdfEnabled != null && hdfEnabled) && !(dirEnabled != null && dirEnabled)) {
                    applyDecoded(e, "dh0_mode", "s:" + escape("hdf"));
                }
            } catch (Throwable ignored) {
            }

            // Best-effort: map common standard cfg keys into our Android launcher prefs.
            applyLegacyCfgMappings(e, legacy);

            // Rebuild media path prefs from launcher source-name keys when configs were saved
            // with df*/cd*/dh* labels but without corresponding uae_drive_* absolute paths.
            rehydrateMediaPathsFromSourceNames(ctx, p, e, loaded, filename);

            // Use commit() so the caller can immediately reflect changes (e.g., BootstrapActivity onResume)
            // and to avoid transient UI states on fast activity transitions.
            return e.commit();
        } catch (Throwable t) {
            setLastError(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
        }
    }

    private static void applyLegacyCfgMappings(SharedPreferences.Editor e, Map<String, String> legacy) {
        if (e == null || legacy == null || legacy.isEmpty()) return;

        // Helpers
        java.util.function.Function<String, String> norm = (raw) -> {
            if (raw == null) return null;
            String s = raw.trim();
            // Strip common quoting
            if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
                s = s.substring(1, s.length() - 1);
            }
            return s.trim();
        };

        // Persist the full legacy map as JSON so we can apply *all* imported keys at launch.
        // This is what makes importing a standard .uae file behave like a full config overwrite.
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> ent : legacy.entrySet()) {
                if (ent == null) continue;
                String k = ent.getKey();
                if (k == null) continue;
                String key = k.trim();
                if (key.isEmpty()) continue;
                String v = norm.apply(ent.getValue());
                if (v == null) continue;
                // Keep empty values out to avoid overriding defaults with blanks.
                if (v.isEmpty()) continue;
                obj.put(key, v);
            }
            String json = obj.toString();
            if (json != null && !json.trim().isEmpty()) {
                applyDecoded(e, UaeOptionKeys.UAE_IMPORTED_CFG_OVERRIDES_JSON, "s:" + escape(json));
            }
        } catch (Throwable ignored) {
        }

        java.util.function.Function<String, Boolean> parseBool = (raw) -> {
            String s = norm.apply(raw);
            if (s == null) return null;
            if ("true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
            return null;
        };

        java.util.function.Function<String, Integer> parseInt = (raw) -> {
            String s = norm.apply(raw);
            if (s == null || s.isEmpty()) return null;
            try { return Integer.parseInt(s); } catch (Throwable ignored) { return null; }
        };

        java.util.function.BiConsumer<String, String> mapString = (fromKey, toKey) -> {
            if (!legacy.containsKey(fromKey)) return;
            String v = norm.apply(legacy.get(fromKey));
            if (v == null || v.isEmpty()) return;
            applyDecoded(e, toKey, "s:" + escape(v));
        };

        java.util.function.BiConsumer<String, String> mapInt = (fromKey, toKey) -> {
            if (!legacy.containsKey(fromKey)) return;
            Integer v = parseInt.apply(legacy.get(fromKey));
            if (v == null) return;
            applyDecoded(e, toKey, "i:" + v);
        };

        java.util.function.BiConsumer<String, String> mapBool = (fromKey, toKey) -> {
            if (!legacy.containsKey(fromKey)) return;
            Boolean v = parseBool.apply(legacy.get(fromKey));
            if (v == null) return;
            applyDecoded(e, toKey, "b:" + (v ? "true" : "false"));
        };

        // Media
        mapString.accept("floppy0", UaeOptionKeys.UAE_DRIVE_DF0_PATH);
        mapString.accept("floppy1", UaeOptionKeys.UAE_DRIVE_DF1_PATH);
        mapString.accept("cdimage0", UaeOptionKeys.UAE_DRIVE_CD_IMAGE0_PATH);

        // If we're importing a standard UAE config file, it won't have our launcher-only
        // boot_medium/dh0_mode keys. Infer them so Bootstrap/Quickload invariants are correct.
        try {
            boolean hasFloppy0 = false;
            if (legacy.containsKey("floppy0")) {
                String v = norm.apply(legacy.get("floppy0"));
                hasFloppy0 = v != null && !v.isEmpty();
            }

            boolean hasFilesystem = false;
            boolean hasHardfile = false;
            for (String k : legacy.keySet()) {
                if (k == null) continue;
                String kk = k.trim().toLowerCase(Locale.ROOT);
                if (kk.startsWith("filesystem")) hasFilesystem = true;
                if (kk.startsWith("hardfile")) hasHardfile = true;
            }

            // boot_medium: only set when config clearly implies one mode.
            if (hasFloppy0 && !(hasFilesystem || hasHardfile)) {
                applyDecoded(e, "boot_medium", "s:" + escape("floppy"));
            } else if (!hasFloppy0 && (hasFilesystem || hasHardfile)) {
                applyDecoded(e, "boot_medium", "s:" + escape("hd"));
            }

            // dh0_mode: if the imported config declares a filesystem/hardfile, prefer that.
            if (hasFilesystem && !hasHardfile) {
                applyDecoded(e, "dh0_mode", "s:" + escape("dir"));
            } else if (hasHardfile && !hasFilesystem) {
                applyDecoded(e, "dh0_mode", "s:" + escape("hdf"));
            }
        } catch (Throwable ignored) {
        }

        // ROMs
        mapString.accept("kickstart_rom_file", UaeOptionKeys.UAE_ROM_KICKSTART_FILE);
        mapString.accept("kickstart_ext_rom_file", UaeOptionKeys.UAE_ROM_EXT_FILE);
        mapString.accept("cart_file", UaeOptionKeys.UAE_ROM_CART_FILE);

        // RTG (Picasso96 / UAEGFX)
        mapInt.accept("gfxcard_size", UaeOptionKeys.UAE_GFXCARD_SIZE_MB);
        mapString.accept("gfxcard_type", UaeOptionKeys.UAE_GFXCARD_TYPE);

        // Memory
        mapInt.accept("chipmem_size", UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE);
        mapInt.accept("bogomem_size", UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE);
        mapInt.accept("z3mem_size", UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB);
        mapInt.accept("megachipmem_size", UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB);
        mapInt.accept("a3000mem_size", UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB);
        mapInt.accept("mbresmem_size", UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB);

        // fastmem_size is in MB in UAE configs; we store bytes.
        if (legacy.containsKey("fastmem_size")) {
            Integer mb = parseInt.apply(legacy.get("fastmem_size"));
            if (mb != null && mb > 0) {
                int bytes = mb * 0x100000;
                applyDecoded(e, UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, "i:" + bytes);
            } else if (mb != null && mb == 0) {
                applyDecoded(e, UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, "i:0");
            }
        }
        if (legacy.containsKey("fastmem_size_k")) {
            Integer kb = parseInt.apply(legacy.get("fastmem_size_k"));
            if (kb != null && kb > 0) {
                int bytes = kb * 1024;
                applyDecoded(e, UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, "i:" + bytes);
            }
        }

        // CPU / Chipset
        mapString.accept("chipset", UaeOptionKeys.UAE_CHIPSET);
        mapString.accept("chipset_compatible", UaeOptionKeys.UAE_CHIPSET_COMPATIBLE);
        mapBool.accept("ntsc", UaeOptionKeys.UAE_NTSC);
        mapString.accept("cycle_exact", UaeOptionKeys.UAE_CYCLE_EXACT);
        mapString.accept("collision_level", UaeOptionKeys.UAE_COLLISION_LEVEL);

        mapString.accept("cpu_model", UaeOptionKeys.UAE_CPU_MODEL);
        mapBool.accept("cpu_compatible", UaeOptionKeys.UAE_CPU_COMPATIBLE);
        mapString.accept("cpu_speed", UaeOptionKeys.UAE_CPU_SPEED);
        mapBool.accept("cpu_24bit_addressing", UaeOptionKeys.UAE_CPU_24BIT_ADDRESSING);
        mapBool.accept("cpu_data_cache", UaeOptionKeys.UAE_CPU_DATA_CACHE);

        // Sound
        mapString.accept("sound_output", UaeOptionKeys.UAE_SOUND_OUTPUT);
        mapBool.accept("sound_auto", UaeOptionKeys.UAE_SOUND_AUTO);
        mapString.accept("sound_channels", UaeOptionKeys.UAE_SOUND_CHANNELS);
        mapInt.accept("sound_frequency", UaeOptionKeys.UAE_SOUND_FREQUENCY);
        mapString.accept("sound_interpol", UaeOptionKeys.UAE_SOUND_INTERPOL);
        mapString.accept("sound_filter", UaeOptionKeys.UAE_SOUND_FILTER);
        mapString.accept("sound_filter_type", UaeOptionKeys.UAE_SOUND_FILTER_TYPE);
        mapInt.accept("sound_stereo_separation", UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION);
        mapInt.accept("sound_stereo_mixing_delay", UaeOptionKeys.UAE_SOUND_STEREO_DELAY);
        mapBool.accept("sound_stereo_swap_paula", UaeOptionKeys.UAE_SOUND_SWAP_PAULA);
        mapBool.accept("sound_stereo_swap_ahi", UaeOptionKeys.UAE_SOUND_SWAP_AHI);
        mapInt.accept("sound_volume_paula", UaeOptionKeys.UAE_SOUND_VOLUME_PAULA);
        mapInt.accept("sound_volume_cd", UaeOptionKeys.UAE_SOUND_VOLUME_CD);
        mapInt.accept("sound_volume_ahi", UaeOptionKeys.UAE_SOUND_VOLUME_AHI);
        mapInt.accept("sound_volume_midi", UaeOptionKeys.UAE_SOUND_VOLUME_MIDI);
        mapInt.accept("sound_max_buff", UaeOptionKeys.UAE_SOUND_MAX_BUFF);
        mapBool.accept("sound_pullmode", UaeOptionKeys.UAE_SOUND_PULLMODE);
    }

    static boolean renameConfig(Context ctx, ConfigEntry entry, String newBaseName) {
        if (ctx == null || entry == null) return false;
        if (entry.isLastRan) return false;

        String conf = getConfigDirString(ctx);
        String newName = ensureFilename(newBaseName);

        try {
            if (isSafJoinedPath(conf)) {
                DocumentFile dir = resolveConfigDirSaf(ctx, conf, false);
                if (dir == null) return false;
                DocumentFile f = dir.findFile(entry.name);
                if (f == null || !f.exists()) return false;
                // Prevent clobber.
                DocumentFile exists = findConfigFileDoc(dir, newName);
                if (exists != null && exists.exists()) return false;
                return f.renameTo(newName);
            } else {
                File dir = new File(conf);
                File from = new File(dir, entry.name);
                File to = new File(dir, newName);
                if (!from.exists() || to.exists()) return false;
                return from.renameTo(to);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean deleteConfig(Context ctx, ConfigEntry entry) {
        if (ctx == null || entry == null) return false;
        if (entry.isLastRan) return false;

        String conf = getConfigDirString(ctx);
        try {
            if (isSafJoinedPath(conf)) {
                DocumentFile dir = resolveConfigDirSaf(ctx, conf, false);
                if (dir == null) return false;
                DocumentFile f = findConfigFileDoc(dir, entry.name);
                return f != null && f.exists() && f.delete();
            } else {
                File f = new File(new File(conf), entry.name);
                return f.exists() && f.delete();
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
