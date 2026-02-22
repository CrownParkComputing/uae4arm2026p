package com.uae4arm2026;

import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BootstrapMediaUtils {

    private BootstrapMediaUtils() {
    }

    static Uri buildExternalStorageDocumentUriFromPath(String absPath) {
        if (absPath == null) return null;
        String p = absPath.trim();
        if (p.isEmpty()) return null;
        p = p.replace('\\', '/');

        // Common variants.
        if (p.startsWith("/sdcard/")) {
            p = "/storage/emulated/0/" + p.substring("/sdcard/".length());
        }

        // Only supports paths under /storage/<volume>/...
        if (!p.startsWith("/storage/")) return null;

        String rest = p.substring("/storage/".length());
        int slash = rest.indexOf('/');
        String volume = (slash >= 0) ? rest.substring(0, slash) : rest;
        String rel = (slash >= 0) ? rest.substring(slash + 1) : "";
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);

        String docId;
        if ("emulated".equalsIgnoreCase(volume)) {
            // /storage/emulated/0/...
            if (rel.startsWith("0/")) rel = rel.substring(2);
            docId = "primary:" + rel;
        } else if ("self".equalsIgnoreCase(volume)) {
            // Uncommon, but treat as primary.
            docId = "primary:" + rel;
        } else {
            // Removable storage: /storage/XXXX-XXXX/...
            docId = volume + ":" + rel;
        }

        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
    }

    static String safeFilename(String name, String fallback) {
        if (name == null) name = "";
        name = name.trim();
        if (name.isEmpty()) name = fallback;
        return name
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_');
    }

    static String sanitizeFilename(String name) {
        if (name == null) return "rom.bin";
        String s = name.trim();
        if (s.isEmpty()) return "rom.bin";
        // Windows-safe + Android-safe filename sanitization.
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static String lowerExt(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) return lower.substring(dot + 1);
        return "";
    }

    static boolean isContentUriString(String s) {
        return s != null && s.trim().startsWith("content://");
    }

    static int cdMainPriority(String ext) {
        if (ext == null) return 100;
        switch (ext) {
            case "chd":
                return 0;
            case "iso":
                return 1;
            case "cue":
                return 2;
            case "ccd":
                return 3;
            default:
                return 50;
        }
    }

    static boolean cueHasMissingTracks(File cueFile) {
        return BootstrapCueUtils.cueHasMissingTracks(cueFile);
    }

    static List<String> parseCueTrackFilenames(File cueFile) {
        return BootstrapCueUtils.parseCueTrackFilenames(cueFile);
    }

    static void fixCueTrackFilenameCase(File cueFile) {
        BootstrapCueUtils.fixCueTrackFilenameCase(cueFile);
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static DocumentFile findFileByNameIgnoreCase(DocumentFile dir, String name, int depth) {
        if (dir == null || !dir.isDirectory() || depth < 0 || name == null) return null;
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return null;
        for (DocumentFile kid : kids) {
            if (kid == null) continue;
            String n = kid.getName();
            if (n != null && n.equalsIgnoreCase(name) && kid.isFile()) {
                return kid;
            }
        }
        for (DocumentFile kid : kids) {
            if (kid == null) continue;
            if (kid.isDirectory()) {
                DocumentFile found = findFileByNameIgnoreCase(kid, name, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    static boolean isLikelyRomFile(File f) {
        if (f == null || !f.exists() || !f.isFile() || f.length() <= 0) return false;
        String n = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".rom") || n.endsWith(".bin") || n.endsWith(".zip");
    }

    static boolean isLikelyRomName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".rom") || n.endsWith(".bin") || n.endsWith(".zip");
    }

    /**
     * Internal helper class used to keep the big CUE parsing logic out of BootstrapActivity
     * without changing behavior.
     */
    private static final class BootstrapCueUtils {
        private BootstrapCueUtils() {
        }

        static boolean cueHasMissingTracks(File cueFile) {
            if (cueFile == null || !cueFile.exists()) return true;
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(cueFile));
                try {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        // Typical: FILE "Track 01.bin" BINARY
                        if (line.toUpperCase(Locale.ROOT).startsWith("FILE")) {
                            int q1 = line.indexOf('"');
                            int q2 = q1 >= 0 ? line.indexOf('"', q1 + 1) : -1;
                            if (q1 >= 0 && q2 > q1) {
                                String ref = line.substring(q1 + 1, q2);
                                if (ref != null && !ref.trim().isEmpty()) {
                                    File track = new File(cueFile.getParentFile(), ref);
                                    if (!track.exists() || track.length() <= 0) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    br.close();
                }
            } catch (Throwable t) {
                return true;
            }
            return false;
        }

        static List<String> parseCueTrackFilenames(File cueFile) {
            List<String> names = new ArrayList<>();
            if (cueFile == null || !cueFile.exists()) return names;
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(cueFile));
                try {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        if (line.toUpperCase(Locale.ROOT).startsWith("FILE")) {
                            int q1 = line.indexOf('"');
                            int q2 = q1 >= 0 ? line.indexOf('"', q1 + 1) : -1;
                            if (q1 >= 0 && q2 > q1) {
                                String ref = line.substring(q1 + 1, q2);
                                if (ref != null) {
                                    ref = ref.trim();
                                    if (!ref.isEmpty()) {
                                        names.add(ref);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    br.close();
                }
            } catch (IOException ignored) {
            }
            return names;
        }

        static void fixCueTrackFilenameCase(File cueFile) {
            if (cueFile == null || !cueFile.exists()) return;
            File dir = cueFile.getParentFile();
            if (dir == null || !dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) files = new File[0];

            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(cueFile));
                try {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        if (line.toUpperCase(Locale.ROOT).startsWith("FILE")) {
                            int q1 = line.indexOf('"');
                            int q2 = q1 >= 0 ? line.indexOf('"', q1 + 1) : -1;
                            if (q1 >= 0 && q2 > q1) {
                                String ref = line.substring(q1 + 1, q2);
                                if (ref == null) continue;
                                ref = ref.trim();
                                if (ref.isEmpty()) continue;
                                // If the CUE references a subpath, leave it alone.
                                if (ref.contains("/") || ref.contains("\\")) continue;

                                File exact = new File(dir, ref);
                                if (exact.exists() && exact.length() > 0) continue;

                                for (File f : files) {
                                    if (f == null || !f.isFile()) continue;
                                    String n = f.getName();
                                    if (n != null && n.equalsIgnoreCase(ref)) {
                                        // Rename track file to match the CUE reference exactly (Android fs is case-sensitive).
                                        //noinspection ResultOfMethodCallIgnored
                                        f.renameTo(exact);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    br.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
