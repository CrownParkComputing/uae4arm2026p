package com.uae4arm2026;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class BootstrapRomCatalog {

    private BootstrapRomCatalog() {
    }

    static List<File> listRomCandidates(File romsDir) {
        ArrayList<File> out = new ArrayList<>();
        if (romsDir == null || !romsDir.exists() || !romsDir.isDirectory()) return out;
        File[] files = romsDir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (BootstrapMediaUtils.isLikelyRomFile(f)) out.add(f);
        }
        return out;
    }

    static List<RomSource> listRomSourcesFromKickstartsDir(Context context, String kickstartsPath) {
        ArrayList<RomSource> out = new ArrayList<>();
        if (context == null) return out;
        if (kickstartsPath == null || kickstartsPath.trim().isEmpty()) return out;

        String value = kickstartsPath.trim();

        try {
            if (ConfigStorage.isSafJoinedPath(value)) {
                ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(value);
                if (sp == null || sp.treeUri == null) return out;

                Uri treeUri = Uri.parse(sp.treeUri);
                DocumentFile cur = DocumentFile.fromTreeUri(context, treeUri);
                if (cur == null || !cur.exists() || !cur.isDirectory()) return out;

                String rel = sp.relPath;
                if (rel != null) {
                    String rp = rel.trim();
                    if (rp.startsWith("/")) rp = rp.substring(1);
                    if (!rp.isEmpty() && !"/".equals(rp)) {
                        String[] parts = rp.split("/");
                        for (String part : parts) {
                            if (part == null) continue;
                            String seg = part.trim();
                            if (seg.isEmpty()) continue;
                            DocumentFile next = cur.findFile(seg);
                            if (next == null) return out;
                            cur = next;
                        }
                    }
                }

                if (!cur.exists() || !cur.isDirectory()) return out;
                DocumentFile[] kids = cur.listFiles();
                if (kids == null) return out;
                for (DocumentFile df : kids) {
                    if (df == null || !df.exists() || !df.isFile()) continue;
                    String name = df.getName();
                    if (!BootstrapMediaUtils.isLikelyRomName(name)) continue;
                    out.add(new RomSource(name, null, df.getUri(), false));
                }
                return out;
            }

            if (isContentUriString(value)) {
                try {
                    Uri treeUri = Uri.parse(value);
                    DocumentFile dirDf = DocumentFile.fromTreeUri(context, treeUri);
                    if (dirDf != null && dirDf.exists() && dirDf.isDirectory()) {
                        DocumentFile[] kids = dirDf.listFiles();
                        if (kids != null) {
                            for (DocumentFile df : kids) {
                                if (df == null || !df.exists() || !df.isFile()) continue;
                                String name = df.getName();
                                if (!BootstrapMediaUtils.isLikelyRomName(name)) continue;
                                out.add(new RomSource(name, null, df.getUri(), false));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return out;
            }

            File dir = new File(value);
            if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) return out;
            File[] files = dir.listFiles();
            if (files == null) return out;
            for (File f : files) {
                if (!BootstrapMediaUtils.isLikelyRomFile(f)) continue;
                out.add(new RomSource(f.getName(), f, null, false));
            }
            return out;
        } catch (Throwable ignored) {
        }

        return out;
    }

    static List<RomSource> listAllRomSources(Context context, String kickstartsPath, File internalRomsDir) {
        ArrayList<RomSource> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        for (RomSource rs : listRomSourcesFromKickstartsDir(context, kickstartsPath)) {
            if (rs == null) continue;
            String key = rs.uri != null ? rs.uri.toString() : (rs.file != null ? rs.file.getAbsolutePath() : null);
            if (key != null && seen.add(key)) out.add(rs);
        }

        for (File f : listRomCandidates(internalRomsDir)) {
            if (f == null) continue;
            String key = f.getAbsolutePath();
            if (key == null || !seen.add(key)) continue;
            out.add(new RomSource(f.getName(), f, null, true));
        }

        return out;
    }

    private static boolean isContentUriString(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("content://");
    }
}
