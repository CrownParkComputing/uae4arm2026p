package com.uae4arm2026;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import org.libsdl.app.SDLActivity;

/**
 * SAF bridge for native (C/C++) code.
 *
 * Native code cannot directly use ContentResolver, so we expose minimal helpers that
 * return detached file descriptors and basic existence checks for content:// URIs.
 */
public final class SafFileBridge {
    private SafFileBridge() {}

    private static final class TreePath {
        final String treeUri;
        final String relPath;

        TreePath(String treeUri, String relPath) {
            this.treeUri = treeUri;
            this.relPath = relPath;
        }
    }

    private static TreePath splitTreeJoinedPath(String uriString) {
        if (uriString == null) return null;
        String s = uriString.trim();
        if (!s.startsWith("content://")) return null;
        int idx = s.indexOf("::");
        if (idx <= 0) return null;
        String tree = s.substring(0, idx);
        String rel = s.substring(idx + 2);
        if (rel.isEmpty()) rel = "/";
        return new TreePath(tree, rel);
    }

    /**
     * Decode a PNG from an on-device filesystem path into ABGR8888 bytes.
     *
     * Used by the Android "minimal" native build where PNG decoding libraries
     * may be excluded.
     *
        * @param filePath Absolute path (e.g. /data/data/.../files/uae4arm/data/vkbd/...)
     * @param outWH    int[2] output width/height
     * @return byte[] pixels in ABGR8888 order (A,B,G,R) or null on failure.
     */
    public static byte[] decodePngFileToAbgr(String filePath, int[] outWH) {
        try {
            if (outWH == null || outWH.length < 2) return null;
            outWH[0] = 0;
            outWH[1] = 0;

            if (filePath == null || filePath.trim().isEmpty()) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(filePath.trim(), opts);
            if (bmp == null) return null;

            final int w = bmp.getWidth();
            final int h = bmp.getHeight();
            if (w <= 0 || h <= 0) {
                bmp.recycle();
                return null;
            }

            int[] argb = new int[w * h];
            bmp.getPixels(argb, 0, w, 0, 0, w, h);
            bmp.recycle();

            byte[] abgr = new byte[w * h * 4];
            int di = 0;
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                int a = (p >>> 24) & 0xFF;
                int r = (p >>> 16) & 0xFF;
                int g = (p >>> 8) & 0xFF;
                int b = p & 0xFF;
                abgr[di++] = (byte) a;
                abgr[di++] = (byte) b;
                abgr[di++] = (byte) g;
                abgr[di++] = (byte) r;
            }

            outWH[0] = w;
            outWH[1] = h;
            return abgr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static DocumentFile resolveTreePath(Context ctx, String treeUriString, String relPath) {
        if (ctx == null) return null;
        if (treeUriString == null || treeUriString.trim().isEmpty()) return null;

        Uri treeUri = Uri.parse(treeUriString.trim());
        DocumentFile cur = DocumentFile.fromTreeUri(ctx, treeUri);
        if (cur == null) return null;

        if (relPath == null) return cur;
        String p = relPath.trim();
        if (p.isEmpty() || "/".equals(p)) return cur;

        // Normalize leading slash.
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return cur;

        String[] parts = p.split("/");
        for (String part : parts) {
            if (part == null) continue;
            String seg = part.trim();
            if (seg.isEmpty()) continue;
            DocumentFile next = cur.findFile(seg);
            if (next == null) return null;
            cur = next;
        }
        return cur;
    }

    public static int openDetachedFd(String uriString, String mode) {
        try {
            if (uriString == null || uriString.trim().isEmpty()) return -1;
            if (mode == null || mode.trim().isEmpty()) mode = "r";

            // Support our joined tree format: "content://...::/path/to/file".
            TreePath tp = splitTreeJoinedPath(uriString);
            if (tp != null) {
                return openDetachedFdTreePath(tp.treeUri, tp.relPath, mode);
            }

            Context ctx = SDLActivity.getContext();
            if (ctx == null) return -1;

            Uri uri = Uri.parse(uriString);
            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, mode);
            if (pfd == null) return -1;

            // detachFd() transfers ownership of the underlying fd to the caller.
            // The ParcelFileDescriptor is no longer responsible for closing it.
            int fd = pfd.detachFd();
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
            return fd;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean exists(String uriString) {
        try {
            if (uriString == null || uriString.trim().isEmpty()) return false;
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return false;

            TreePath tp = splitTreeJoinedPath(uriString);
            if (tp != null) {
                DocumentFile df = resolveTreePath(ctx, tp.treeUri, tp.relPath);
                return df != null && df.exists();
            }

            Uri uri = Uri.parse(uriString);

            DocumentFile df = DocumentFile.fromSingleUri(ctx, uri);
            if (df == null) {
                df = DocumentFile.fromTreeUri(ctx, uri);
            }
            return df != null && df.exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isDirectory(String uriString) {
        try {
            if (uriString == null || uriString.trim().isEmpty()) return false;
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return false;

            TreePath tp = splitTreeJoinedPath(uriString);
            if (tp != null) {
                DocumentFile df = resolveTreePath(ctx, tp.treeUri, tp.relPath);
                return df != null && df.exists() && df.isDirectory();
            }

            Uri uri = Uri.parse(uriString);

            DocumentFile df = DocumentFile.fromSingleUri(ctx, uri);
            if (df == null) {
                df = DocumentFile.fromTreeUri(ctx, uri);
            }
            return df != null && df.exists() && df.isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean existsTreePath(String treeUriString, String relPath) {
        try {
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return false;
            DocumentFile df = resolveTreePath(ctx, treeUriString, relPath);
            return df != null && df.exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isDirectoryTreePath(String treeUriString, String relPath) {
        try {
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return false;
            DocumentFile df = resolveTreePath(ctx, treeUriString, relPath);
            return df != null && df.exists() && df.isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String[] listChildrenTreePath(String treeUriString, String relPath) {
        try {
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return new String[0];
            DocumentFile df = resolveTreePath(ctx, treeUriString, relPath);
            if (df == null || !df.exists() || !df.isDirectory()) return new String[0];

            DocumentFile[] kids = df.listFiles();
            if (kids == null || kids.length == 0) return new String[0];

            String[] names = new String[kids.length];
            for (int i = 0; i < kids.length; i++) {
                String n = kids[i] != null ? kids[i].getName() : null;
                names[i] = (n != null) ? n : "";
            }
            return names;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    public static int openDetachedFdTreePath(String treeUriString, String relPath, String mode) {
        try {
            if (mode == null || mode.trim().isEmpty()) mode = "r";
            Context ctx = SDLActivity.getContext();
            if (ctx == null) return -1;

            DocumentFile df = resolveTreePath(ctx, treeUriString, relPath);
            if (df == null || !df.exists() || df.isDirectory()) return -1;

            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(df.getUri(), mode);
            if (pfd == null) return -1;

            int fd = pfd.detachFd();
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
            return fd;
        } catch (Throwable t) {
            return -1;
        }
    }
}
