package com.uae4arm2026;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;

final class BootstrapCueCompanionImporter {

    interface Callbacks {
        void ensureDir(File dir);

        String lowerExt(String name);

        boolean isValidCdExtension(String name);

        String safeFilename(String name, String fallback);

        boolean copyDocumentFileTo(DocumentFile src, File dest);

        SharedPreferences getPrefs();

        String resolveConfiguredPathForKeyWithParentFallback(SharedPreferences prefs, String key);

        boolean isContentUriString(String value);

        String joinSafTreeBase(String treeBase, String relPath);

        String extractCueSubdirUnderCdroms(Uri cueUri);

        DocumentFile findFileByNameIgnoreCase(DocumentFile root, String targetName, int maxDepth);
    }

    private BootstrapCueCompanionImporter() {
    }

    static boolean importCueTrackFromFilesystemSibling(Callbacks callbacks, Uri cueUri, String relTrack, File dest) {
        if (cueUri == null || relTrack == null || dest == null) return false;
        try {
            File cueFs = fileFromPrimaryDocUri(cueUri);
            if (cueFs == null || !cueFs.exists() || !cueFs.isFile()) return false;
            File cueDir = cueFs.getParentFile();
            if (cueDir == null || !cueDir.exists() || !cueDir.isDirectory()) return false;

            String rel = relTrack.trim().replace('\\', '/');
            while (rel.startsWith("./")) rel = rel.substring(2);
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) return false;

            File src = new File(cueDir, rel);
            if (!src.exists() || !src.isFile()) {
                int slash = rel.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < rel.length()) {
                    src = new File(cueDir, rel.substring(slash + 1));
                }
            }
            if (!src.exists() || !src.isFile()) return false;

            callbacks.ensureDir(dest.getParentFile());
            return copyFileDirect(src, dest);
        } catch (Throwable ignored) {
        }
        return false;
    }

    static int importAllCueCompanionFilesFromFilesystemFolder(Callbacks callbacks, Uri cueUri, File cd0Dir) {
        if (cueUri == null || cd0Dir == null) return 0;
        try {
            File cueFs = fileFromPrimaryDocUri(cueUri);
            if (cueFs == null || !cueFs.exists() || !cueFs.isFile()) return 0;
            File cueDir = cueFs.getParentFile();
            if (cueDir == null || !cueDir.exists() || !cueDir.isDirectory()) return 0;

            File[] files = cueDir.listFiles();
            if (files == null || files.length == 0) return 0;

            int copied = 0;
            for (File f : files) {
                if (f == null || !f.exists() || !f.isFile() || !f.canRead()) continue;
                String name = f.getName();
                if (name == null || name.trim().isEmpty()) continue;
                String ext = callbacks.lowerExt(name);
                if (!callbacks.isValidCdExtension(name) && !"bin".equals(ext)) continue;

                File dest = new File(cd0Dir, callbacks.safeFilename(name, "track.bin"));
                callbacks.ensureDir(dest.getParentFile());
                if (copyFileDirect(f, dest)) copied++;
            }
            return copied;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String extractPrimaryRelativePathFromDocUri(Uri uri) {
        if (uri == null) return null;
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId == null || docId.trim().isEmpty()) return null;
            String id = docId.trim();
            if (!id.startsWith("primary:")) return null;
            String rel = id.substring("primary:".length()).trim();
            rel = rel.replace('\\', '/');
            while (rel.startsWith("/")) rel = rel.substring(1);
            return rel.isEmpty() ? null : rel;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File fileFromPrimaryDocUri(Uri uri) {
        String rel = extractPrimaryRelativePathFromDocUri(uri);
        if (rel == null || rel.trim().isEmpty()) return null;
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return null;
        return new File(root, rel);
    }

    private static boolean copyFileDirect(File src, File dest) {
        if (src == null || dest == null) return false;
        if (!src.exists() || !src.isFile() || !src.canRead()) return false;
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            }
            return dest.exists() && dest.isFile() && dest.length() > 0;
        } catch (Throwable ignored) {
        }
        return false;
    }

    static DocumentFile resolveDocumentFileFromSafJoinedPath(BootstrapActivity activity, String joinedDir, String relativePath) {
        if (joinedDir == null || relativePath == null) return null;
        if (!ConfigStorage.isSafJoinedPath(joinedDir)) return null;
        ConfigStorage.SafPath sp = ConfigStorage.splitSafJoinedPath(joinedDir);
        if (sp == null || sp.treeUri == null || sp.treeUri.trim().isEmpty()) return null;

        try {
            DocumentFile cur = DocumentFile.fromTreeUri(activity, Uri.parse(sp.treeUri));
            if (cur == null || !cur.exists() || !cur.isDirectory()) return null;

            String baseRel = sp.relPath == null ? "" : sp.relPath.trim();
            if (baseRel.startsWith("/")) baseRel = baseRel.substring(1);
            if (baseRel.endsWith("/")) baseRel = baseRel.substring(0, baseRel.length() - 1);
            if (!baseRel.isEmpty()) {
                String[] parts = baseRel.split("/");
                for (String part : parts) {
                    if (part == null) continue;
                    String seg = part.trim();
                    if (seg.isEmpty()) continue;
                    DocumentFile next = cur.findFile(seg);
                    if (next == null || !next.exists()) return null;
                    cur = next;
                }
            }

            String rel = relativePath.trim().replace('\\', '/');
            while (rel.startsWith("./")) rel = rel.substring(2);
            while (rel.startsWith("/")) rel = rel.substring(1);
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
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean importCueTrackFromConfiguredCdroms(BootstrapActivity activity,
                                                       Callbacks callbacks,
                                                       Uri cueUri,
                                                       String relTrack,
                                                       File dest) {
        if (cueUri == null || relTrack == null || dest == null) return false;
        try {
            SharedPreferences p = callbacks.getPrefs();
            String cdromsPath = callbacks.resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_CDROMS_DIR);
            if (cdromsPath == null || cdromsPath.trim().isEmpty()) {
                String parentTree = p.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
                if (parentTree != null && !parentTree.trim().isEmpty() && callbacks.isContentUriString(parentTree)) {
                    cdromsPath = callbacks.joinSafTreeBase(parentTree.trim(), "cdroms");
                }
            }
            if (cdromsPath == null || cdromsPath.trim().isEmpty()) return false;
            if (!ConfigStorage.isSafJoinedPath(cdromsPath)) return false;

            String cueSubdir = callbacks.extractCueSubdirUnderCdroms(cueUri);
            String rel = relTrack.trim().replace('\\', '/');
            while (rel.startsWith("./")) rel = rel.substring(2);
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) return false;

            String lookup = cueSubdir.isEmpty() ? rel : (cueSubdir + "/" + rel);
            DocumentFile trackDf = resolveDocumentFileFromSafJoinedPath(activity, cdromsPath, lookup);

            if ((trackDf == null || !trackDf.isFile()) && rel.contains("/")) {
                String base = rel.substring(rel.lastIndexOf('/') + 1);
                String baseLookup = cueSubdir.isEmpty() ? base : (cueSubdir + "/" + base);
                trackDf = resolveDocumentFileFromSafJoinedPath(activity, cdromsPath, baseLookup);
            }

            if (trackDf == null || !trackDf.isFile()) {
                String base = rel;
                int slash = base.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < base.length()) {
                    base = base.substring(slash + 1);
                }

                DocumentFile cueDir = null;
                if (!cueSubdir.isEmpty()) {
                    cueDir = resolveDocumentFileFromSafJoinedPath(activity, cdromsPath, cueSubdir);
                }
                if (cueDir != null && cueDir.exists() && cueDir.isDirectory()) {
                    trackDf = callbacks.findFileByNameIgnoreCase(cueDir, base, 6);
                }

                if ((trackDf == null || !trackDf.isFile())) {
                    DocumentFile cdRoot = resolveDocumentFileFromSafJoinedPath(activity, cdromsPath, "");
                    if (cdRoot != null && cdRoot.exists() && cdRoot.isDirectory()) {
                        trackDf = callbacks.findFileByNameIgnoreCase(cdRoot, base, 8);
                    }
                }
            }

            if (trackDf == null || !trackDf.isFile()) return false;
            callbacks.ensureDir(dest.getParentFile());
            return callbacks.copyDocumentFileTo(trackDf, dest);
        } catch (Throwable ignored) {
        }
        return false;
    }

    static int importAllCueCompanionFilesFromConfiguredCdroms(BootstrapActivity activity,
                                                               Callbacks callbacks,
                                                               Uri cueUri,
                                                               File cd0Dir) {
        if (cueUri == null || cd0Dir == null) return 0;
        try {
            SharedPreferences p = callbacks.getPrefs();
            String cdromsPath = callbacks.resolveConfiguredPathForKeyWithParentFallback(p, UaeOptionKeys.UAE_PATH_CDROMS_DIR);
            if (cdromsPath == null || cdromsPath.trim().isEmpty()) {
                String parentTree = p.getString(UaeOptionKeys.UAE_PATH_PARENT_TREE_URI, null);
                if (parentTree != null && !parentTree.trim().isEmpty() && callbacks.isContentUriString(parentTree)) {
                    cdromsPath = callbacks.joinSafTreeBase(parentTree.trim(), "cdroms");
                }
            }
            if (cdromsPath == null || cdromsPath.trim().isEmpty()) return 0;
            if (!ConfigStorage.isSafJoinedPath(cdromsPath)) return 0;

            String cueSubdir = callbacks.extractCueSubdirUnderCdroms(cueUri);
            DocumentFile cueDir = resolveDocumentFileFromSafJoinedPath(activity, cdromsPath, cueSubdir);
            if (cueDir == null || !cueDir.exists() || !cueDir.isDirectory()) return 0;

            DocumentFile[] kids = cueDir.listFiles();
            if (kids == null || kids.length == 0) return 0;

            int copied = 0;
            for (DocumentFile df : kids) {
                if (df == null || !df.exists() || !df.isFile()) continue;
                String name = df.getName();
                if (name == null || name.trim().isEmpty()) continue;
                String ext = callbacks.lowerExt(name);
                if (!callbacks.isValidCdExtension(name) && !"bin".equals(ext)) continue;

                File dest = new File(cd0Dir, callbacks.safeFilename(name, "track.bin"));
                callbacks.ensureDir(dest.getParentFile());
                if (callbacks.copyDocumentFileTo(df, dest)) {
                    copied++;
                }
            }
            return copied;
        } catch (Throwable ignored) {
        }
        return 0;
    }
}