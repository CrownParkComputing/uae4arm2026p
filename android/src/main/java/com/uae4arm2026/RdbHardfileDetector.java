package com.uae4arm2026;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Pure-static utility for detecting and analysing Amiga RDB (Rigid Disk Block)
 * hard-file images, CHS geometry, and AmigaDOS bootblocks.
 * Extracted from AmiberryActivity to reduce module size.
 */
final class RdbHardfileDetector {

    private static final String TAG = "RdbHardfileDetector";

    private RdbHardfileDetector() { }

    // ── Inner value types ────────────────────────────────────────────────

    static final class RdbGeometry {
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

    static final class ChsGeometry {
        final int sectors;
        final int heads;

        ChsGeometry(int sectors, int heads) {
            this.sectors = sectors;
            this.heads = heads;
        }
    }

    // ── Low-level byte helpers ───────────────────────────────────────────

    static int readBeU16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static long readBeU32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24)
            | ((long) (b[off + 1] & 0xff) << 16)
            | ((long) (b[off + 2] & 0xff) << 8)
            | ((long) (b[off + 3] & 0xff));
    }

    // ── RDB signature detection ──────────────────────────────────────────

    static boolean looksLikeRdbHardfile(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        String p = path.trim();

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
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
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

    static boolean isRdbSignatureBlock(byte[] blk) {
        if (blk == null || blk.length < 4) return false;
        // ADIDE encoded "CPRM"
        if ((blk[0] & 0xFF) == 0x39 && (blk[1] & 0xFF) == 0x10 && (blk[2] & 0xFF) == 0xD3 && (blk[3] & 0xFF) == 0x12) {
            return true;
        }
        // A2090 BABE marker
        if ((blk[0] & 0xFF) == 0xBA && (blk[1] & 0xFF) == 0xBE) {
            return true;
        }
        // Classic RDB signatures
        return (blk[0] == 'R' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K')
            || (blk[0] == 'D' && blk[1] == 'R' && blk[2] == 'K' && blk[3] == 'S')
            || (blk[0] == 'C' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K');
    }

    // ── RDB block validation ─────────────────────────────────────────────

    static boolean isLikelyValidRdbBlock(byte[] blk) {
        if (blk == null || blk.length < 512) return false;

        boolean sig = (blk[0] == 'R' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K')
            || (blk[0] == 'C' && blk[1] == 'D' && blk[2] == 'S' && blk[3] == 'K');
        if (!sig) return false;

        long sizeLong = readBeU32(blk, 0x04);
        if (sizeLong < 8 || sizeLong > 128) return false;
        int sizeBytes = (int) (sizeLong * 4);
        if (sizeBytes > 512) return false;

        int sum = 0;
        for (int i = 0; i < sizeLong; i++) {
            int lw = (int) readBeU32(blk, i * 4);
            sum += lw;
        }
        if (sum != 0) return false;

        int blocksize = (int) readBeU32(blk, 0x10);
        if (blocksize <= 0) blocksize = 512;
        if (!(blocksize == 512 || blocksize == 1024 || blocksize == 2048 || blocksize == 4096)) return false;

        int sectors = (int) readBeU32(blk, 0x44);
        int heads = (int) readBeU32(blk, 0x48);
        if (sectors <= 0 || heads <= 0 || sectors > 255 || heads > 255) {
            sectors = readBeU16(blk, 0x44 + 2);
            heads = readBeU16(blk, 0x48 + 2);
        }
        if (sectors <= 0 || heads <= 0 || sectors > 255 || heads > 255) return false;

        return true;
    }

    // ── RDB geometry parsing ─────────────────────────────────────────────

    static RdbGeometry tryReadRdbGeometry(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String p = path.trim();

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

    // ── CHS geometry selection ───────────────────────────────────────────

    static ChsGeometry chooseChsGeometryForHardfile(String path, int blocksize) {
        return chooseChsGeometryForHardfile(path, blocksize, 0);
    }

    static ChsGeometry chooseChsGeometryForHardfile(String path, int blocksize, int reservedBlocks) {
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

        int[][] candidates = new int[][]{
            {32, 16}, {32, 8}, {32, 4}, {32, 2}, {32, 1},
            {63, 16}, {63, 8}, {63, 4}, {63, 2}, {63, 1},
            {127, 16}, {127, 8}, {127, 4}, {127, 2}, {127, 1},
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

        return new ChsGeometry(32, 16);
    }

    // ── AmigaDOS bootblock helpers ───────────────────────────────────────

    static boolean isAmigaDosBootblockDostype(long dt) {
        return (dt & 0xFFFFFF00L) == 0x444F5300L; // 'D''O''S'\0
    }

    static int findAmigaDosBootblockOffsetBlocks(String path, int maxBlocks, int blocksize) {
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

    static long readBootBlockDostype(String path) {
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

    static int probeReservedBlocksForDosBootblock(String path, int blocksize) {
        if (path == null || path.trim().isEmpty()) return 0;
        String p = path.trim();
        if (p.startsWith("content://")) return 0;

        try (RandomAccessFile raf = new RandomAccessFile(p, "r")) {
            if (hasDosMagicAt(raf, 0)) return 0;
            long off2 = (long) blocksize * 2L;
            if (hasDosMagicAt(raf, off2)) return 2;
        } catch (Throwable t) {
            Log.i(TAG, "Reserved-block probe failed for " + p + ": " + t);
        }
        return 0;
    }

    static boolean hasDosMagicAt(RandomAccessFile raf, long offset) throws java.io.IOException {
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
}
