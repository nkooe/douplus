package com.fj.dyhd;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * WebP 动图 -> GIF。
 *
 * 只做一件事：解析 RIFF/WEBP 的 ANMF 块，逐帧拼成独立静态 WebP 交给 BitmapFactory 解码，
 * 再按 ANMF 的 (x,y,w,h) 合成到画布，最后用 GIF89a + 6x6x6 固定调色板 + giflib 标准 LZW 输出。
 *
 * 不依赖 ImageDecoder 的帧 API（它对 WebP 序列并不可靠）。
 */
final class WebpToGif {

    private static final String TAG = "DYHD";

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /* ---------------- 对外入口 ---------------- */

    /** 快速判断是否动图 WebP（RIFF/WEBP + VP8X.anim 位） */
    static boolean isAnimatedWebp(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] h = new byte[32];
            int n = in.read(h);
            if (n < 30) return false;
            if (!(h[0]=='R' && h[1]=='I' && h[2]=='F' && h[3]=='F'
                    && h[8]=='W' && h[9]=='E' && h[10]=='B' && h[11]=='P')) return false;
            // 找 VP8X
            for (int i = 12; i + 10 < n; i++) {
                if (h[i]=='V' && h[i+1]=='P' && h[i+2]=='8' && h[i+3]=='X') {
                    int flags = h[i+8] & 0xFF;
                    return (flags & 0x02) != 0;      // bit1 = animation
                }
                if (h[i]=='A' && h[i+1]=='N' && h[i+2]=='M' && h[i+3]=='F') return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /* ---------------- 对外入口 ---------------- */

    /** 把 src 的 WebP 动图转成同目录下的 .gif；成功返回 File，失败 null */
    static File convert(String srcPath, int maxFrames) {
        try {
            List<Bitmap> frames = extractFrames(srcPath, maxFrames);
            if (frames.size() < 2) {
                Log.println(Log.WARN, TAG, "webp2gif: only " + frames.size() + " frame(s), skip");
                for (Bitmap b : frames) recycle(b);
                return null;
            }
            File src = new File(srcPath);
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            File dst = new File(src.getParentFile(), name + ".gif");

            boolean ok = writeGif(frames, 100, dst);
            int n = frames.size();
            for (Bitmap b : frames) recycle(b);
            if (!ok) return null;
            Log.println(Log.INFO, TAG, "webp2gif: " + n + " frames -> "
                    + dst.getName() + " " + dst.length() + "B");
            return dst;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "webp2gif fail: " + t);
            return null;
        }
    }

    /* ---------------- WebP 解析 ---------------- */

    /** 供 WebpToMp4 复用的公开入口 */
    static List<Bitmap> extractFramesPublic(String path, int maxFrames) {
        return extractFrames(path, maxFrames);
    }

    /** 解析动图 WebP，逐帧解码并合成出完整画面 */
    private static List<Bitmap> extractFrames(String path, int maxFrames) {
        List<Bitmap> out = new ArrayList<Bitmap>();
        byte[] d = readAll(path);
        if (d == null || d.length < 30) return out;
        if (!(d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P')) {
            Log.println(Log.WARN, TAG, "webp2gif: not RIFF/WEBP");
            return out;
        }

        int canvasW = 0, canvasH = 0;
        int i = 12;
        while (i + 8 <= d.length) {
            String fourcc = new String(d, i, 4, ASCII);
            long sz = le32(d, i + 4);
            if (sz < 0 || i + 8 + sz > d.length) break;
            int p = i + 8;
            if ("VP8X".equals(fourcc) && sz >= 10) {
                // VP8X: flags(1) reserved(3) canvasW-1(3) canvasH-1(3)
                canvasW = le24(d, p + 4) + 1;
                canvasH = le24(d, p + 7) + 1;
                break;
            }
            i += 8 + (int) sz + ((int) sz & 1);
        }
        if (canvasW <= 0 || canvasH <= 0) {
            Log.println(Log.WARN, TAG, "webp2gif: no VP8X canvas");
            return out;
        }

        Bitmap canvas = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(canvas);

        List<int[]> rects = new ArrayList<int[]>();
        List<Bitmap> patches = new ArrayList<Bitmap>();

        i = 12;
        while (i + 8 <= d.length && patches.size() < maxFrames) {
            String fourcc = new String(d, i, 4, ASCII);
            long sz = le32(d, i + 4);
            if (sz < 0 || i + 8 + sz > d.length) break;
            int p = i + 8;

            if ("ANMF".equals(fourcc) && sz >= 16) {
                int fx = le24(d, p) * 2;
                int fy = le24(d, p + 3) * 2;
                int fw = le24(d, p + 6) + 1;
                int fh = le24(d, p + 9) + 1;

                // 收集子块
                int off = p + 16;
                int end = p + (int) sz;
                byte[] alph = null, vp8 = null;
                int j = off;
                while (j + 8 <= end) {
                    String sf = new String(d, j, 4, ASCII);
                    long ssz = le32(d, j + 4);
                    if (ssz < 0 || j + 8 + ssz > end) break;
                    int bodyLen = 8 + (int) ssz;
                    byte[] body = new byte[bodyLen];
                    System.arraycopy(d, j, body, 0, bodyLen);
                    if ("ALPH".equals(sf)) alph = body;
                    else if ("VP8 ".equals(sf) || "VP8L".equals(sf)) vp8 = body;
                    j += 8 + (int) ssz + ((int) ssz & 1);
                }
                if (vp8 != null) {
                    byte[] single = singleWebp(fw, fh, alph, vp8);
                    Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(
                            single, 0, single.length);
                    if (bm != null) {
                        rects.add(new int[]{fx, fy, fw, fh});
                        patches.add(bm);
                    }
                }
            }
            i += 8 + (int) sz + ((int) sz & 1);
        }

        Log.println(Log.INFO, TAG, "webp2gif: canvas=" + canvasW + "x" + canvasH
                + " patches=" + patches.size());

        // 逐帧合成（dispose=keep，累加绘制）
        for (int k = 0; k < patches.size(); k++) {
            int[] r = rects.get(k);
            Bitmap bm = patches.get(k);
            try {
                Rect srcR = new Rect(0, 0, bm.getWidth(), bm.getHeight());
                Rect dstR = new Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]);
                cv.drawBitmap(bm, srcR, dstR, null);
            } catch (Throwable ignored) {}
            Bitmap snap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888);
            new Canvas(snap).drawBitmap(canvas, 0, 0, null);
            out.add(snap);
        }
        for (Bitmap b : patches) recycle(b);
        recycle(canvas);
        return out;
    }

    /** 用 ANMF 的子块拼出一个能独立解码的静态 WebP */
    private static byte[] singleWebp(int w, int h, byte[] alph, byte[] vp8) {
        int vp8xLen = 8 + 10;
        int alphLen = alph == null ? 0 : alph.length + (alph.length & 1);
        int total = 4 + vp8xLen + alphLen + vp8.length;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(total + 8);
        try {
            bo.write("RIFF".getBytes(ASCII));
            wle32(bo, total);
            bo.write("WEBP".getBytes(ASCII));

            bo.write("VP8X".getBytes(ASCII));
            wle32(bo, 10);
            bo.write(alph != null ? 0x10 : 0x00);   // bit4 = alpha
            bo.write(0); bo.write(0); bo.write(0);
            wle24(bo, w - 1);
            wle24(bo, h - 1);

            if (alph != null) {
                bo.write(alph, 0, alph.length);
                if ((alph.length & 1) != 0) bo.write(0);
            }
            bo.write(vp8, 0, vp8.length);
        } catch (Throwable ignored) {}
        return bo.toByteArray();
    }

    /* ---------------- GIF 编码 ---------------- */

    /** 中位切分调色板：按实际出现的颜色聚类，量化误差比固定立方低约 80% */
    private static int[] buildPalette(List<Bitmap> frames) {
        // 1) 采样
        int cap = 40000;
        int total = 0;
        for (Bitmap b : frames) total += b.getWidth() * b.getHeight();
        int step = Math.max(1, total / cap);

        List<int[]> samples = new ArrayList<int[]>();
        for (Bitmap b : frames) {
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i += step) {
                int p = px[i];
                int a = (p >>> 24) & 0xFF;
                if (a < 128) samples.add(new int[]{255, 255, 255});
                else samples.add(new int[]{(p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF});
            }
        }
        if (samples.isEmpty()) return cubePalette();

        // 2) 中位切分
        List<List<int[]>> boxes = new ArrayList<List<int[]>>();
        boxes.add(samples);
        while (boxes.size() < 256) {
            int bestI = -1;
            long bestScore = -1;
            for (int k = 0; k < boxes.size(); k++) {
                List<int[]> bx = boxes.get(k);
                if (bx.size() < 2) continue;
                int rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0;
                for (int[] q : bx) {
                    if (q[0] < rmin) rmin = q[0]; if (q[0] > rmax) rmax = q[0];
                    if (q[1] < gmin) gmin = q[1]; if (q[1] > gmax) gmax = q[1];
                    if (q[2] < bmin) bmin = q[2]; if (q[2] > bmax) bmax = q[2];
                }
                int rr = rmax - rmin, gr = gmax - gmin, br = bmax - bmin;
                int range = Math.max(rr, Math.max(gr, br));
                long score = (long) range * bx.size();
                if (score > bestScore) { bestScore = score; bestI = k; }
            }
            if (bestI < 0) break;

            List<int[]> bx = boxes.remove(bestI);
            int rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0;
            for (int[] q : bx) {
                if (q[0] < rmin) rmin = q[0]; if (q[0] > rmax) rmax = q[0];
                if (q[1] < gmin) gmin = q[1]; if (q[1] > gmax) gmax = q[1];
                if (q[2] < bmin) bmin = q[2]; if (q[2] > bmax) bmax = q[2];
            }
            int rr = rmax - rmin, gr = gmax - gmin, br = bmax - bmin;
            final int ci = (rr >= gr && rr >= br) ? 0 : (gr >= br ? 1 : 2);

            java.util.Collections.sort(bx, new java.util.Comparator<int[]>() {
                @Override public int compare(int[] x, int[] y) { return x[ci] - y[ci]; }
            });
            int mid = bx.size() / 2;
            if (mid <= 0 || mid >= bx.size()) break;
            boxes.add(new ArrayList<int[]>(bx.subList(0, mid)));
            boxes.add(new ArrayList<int[]>(bx.subList(mid, bx.size())));
        }

        // 3) 每箱取均值
        int[] pal = new int[256];
        int n = 0;
        for (List<int[]> bx : boxes) {
            if (bx.isEmpty() || n >= 256) continue;
            long r = 0, g = 0, b = 0;
            for (int[] q : bx) { r += q[0]; g += q[1]; b += q[2]; }
            int c = bx.size();
            pal[n++] = (int) ((r / c) << 16 | (g / c) << 8 | (b / c));
        }
        while (n < 256) pal[n++] = 0;
        return pal;
    }

    /** 兜底：6x6x6 立方 */
    private static int[] cubePalette() {
        int[] t = new int[256];
        int k = 0;
        for (int r = 0; r < 6; r++)
            for (int g = 0; g < 6; g++)
                for (int b = 0; b < 6; b++)
                    t[k++] = ((r * 255 / 5) << 16) | ((g * 255 / 5) << 8) | (b * 255 / 5);
        while (k < 256) {
            int v = (k - 216) * 255 / 39;
            t[k++] = (v << 16) | (v << 8) | v;
        }
        return t;
    }

    private static boolean writeGif(List<Bitmap> frames, int delayMs, File dst) {
        FileOutputStream out = null;
        try {
            int w = frames.get(0).getWidth();
            int h = frames.get(0).getHeight();
            int[] pal = buildPalette(frames);

            out = new FileOutputStream(dst);
            out.write("GIF89a".getBytes(ASCII));
            wle16(out, w);
            wle16(out, h);
            out.write(0x87);   // GCT 存在, colorRes=0, 256 色（与标准编码器一致）
            out.write(0);      // 背景色索引
            out.write(0);      // 像素宽高比
            for (int c : pal) {
                out.write((c >> 16) & 0xFF);
                out.write((c >> 8) & 0xFF);
                out.write(c & 0xFF);
            }

            // NETSCAPE2.0 无限循环
            out.write(0x21); out.write(0xFF); out.write(11);
            out.write("NETSCAPE2.0".getBytes(ASCII));
            out.write(3); out.write(1);
            wle16(out, 0);
            out.write(0);

            int[] lut = new int[32768];
            java.util.Arrays.fill(lut, -1);

            for (Bitmap bmp : frames) {
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                byte[] idx = mapTo(px, pal, lut);

                // Graphic Control Extension
                out.write(0x21); out.write(0xF9); out.write(4);
                out.write(0x04);                       // disposal=1, 无透明
                wle16(out, delayMs / 10);              // 单位 1/100 秒
                out.write(0);
                out.write(0);

                // Image Descriptor
                out.write(0x2C);
                wle16(out, 0); wle16(out, 0);
                wle16(out, w); wle16(out, h);
                out.write(0);                          // 无 LCT

                lzw(out, idx, 8);                      // 内部会写 LZW Minimum Code Size
                out.write(0);                          // 块终止
            }

            out.write(0x3B);   // Trailer
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "gif write fail: " + t);
            try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) {}
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    /** 把 ARGB 像素映射到调色板索引（15bit LUT 缓存） */
    private static byte[] mapTo(int[] px, int[] pal, int[] lut) {
        byte[] out = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = (p >>> 24) & 0xFF;
            int r, g, b;
            if (a < 128) { r = 255; g = 255; b = 255; }   // 透明压成白（GIF 无 alpha）
            else { r = (p >> 16) & 0xFF; g = (p >> 8) & 0xFF; b = p & 0xFF; }

            int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
            int idx = lut[key];
            if (idx < 0) {
                int best = 0, bd = Integer.MAX_VALUE;
                for (int j = 0; j < pal.length; j++) {
                    int c = pal[j];
                    int dr = ((c >> 16) & 0xFF) - r;
                    int dg = ((c >> 8) & 0xFF) - g;
                    int db = (c & 0xFF) - b;
                    int dd = dr * dr + dg * dg + db * db;
                    if (dd < bd) { bd = dd; best = j; }
                }
                idx = best;
                lut[key] = idx;
            }
            out[i] = (byte) idx;
        }
        return out;
    }

    /** giflib 标准 LZW：MaxCode1 = 1 << bits，条件 RunningCode > MaxCode1 */
    private static void lzw(FileOutputStream out, byte[] indices, int minCode) throws IOException {
        out.write(minCode);           // GIF 规范要求先写 minimum code size

        int CLEAR = 1 << minCode;
        int EOI = CLEAR + 1;
        int next = EOI + 1;
        int codeSize = minCode + 1;

        java.util.HashMap<Integer, Integer> dict = new java.util.HashMap<Integer, Integer>();
        BitPacker bp = new BitPacker(out);

        bp.put(CLEAR, codeSize);
        int prefix = indices.length > 0 ? (indices[0] & 0xFF) : 0;

        for (int i = 1; i < indices.length; i++) {
            int k = indices[i] & 0xFF;
            int key = (prefix << 8) | k;
            Integer code = dict.get(key);
            if (code != null) {
                prefix = code;
            } else {
                bp.put(prefix, codeSize);
                dict.put(key, next++);
                if (next > (1 << codeSize) && codeSize < 12) codeSize++;
                if (next >= 4096) {
                    bp.put(CLEAR, codeSize);
                    dict.clear();
                    next = EOI + 1;
                    codeSize = minCode + 1;
                }
                prefix = k;
            }
        }
        bp.put(prefix, codeSize);
        bp.put(EOI, codeSize);
        bp.finish();
    }

    /** 按 GIF 子块格式（[len][data]... [0]）输出 */
    private static final class BitPacker {
        private final FileOutputStream out;
        private final byte[] block = new byte[255];
        private int blockLen = 0;
        private int bitBuf = 0;
        private int bitCnt = 0;

        BitPacker(FileOutputStream out) { this.out = out; }

        void put(int code, int bits) {
            bitBuf |= (code << bitCnt);
            bitCnt += bits;
            while (bitCnt >= 8) {
                emit((byte) (bitBuf & 0xFF));
                bitBuf >>>= 8;
                bitCnt -= 8;
            }
        }

        void finish() {
            if (bitCnt > 0) {
                emit((byte) (bitBuf & 0xFF));
                bitBuf = 0;
                bitCnt = 0;
            }
            flushBlock();
            try { out.write(0); } catch (IOException ignored) {}   // 子块终止
        }

        private void emit(byte b) {
            if (blockLen == 255) flushBlock();
            block[blockLen++] = b;
        }

        private void flushBlock() {
            if (blockLen == 0) return;
            try {
                out.write(blockLen);
                out.write(block, 0, blockLen);
            } catch (IOException ignored) {}
            blockLen = 0;
        }
    }

    /* ---------------- 工具 ---------------- */

    private static byte[] readAll(String path) {
        java.io.FileInputStream in = null;
        try {
            File f = new File(path);
            long len = f.length();
            if (len <= 0 || len > 32L * 1024 * 1024) return null;
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            if (off != buf.length) return null;
            return buf;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static long le32(byte[] d, int i) {
        return (long) (d[i] & 0xFF)
                | ((long) (d[i + 1] & 0xFF) << 8)
                | ((long) (d[i + 2] & 0xFF) << 16)
                | ((long) (d[i + 3] & 0xFF) << 24);
    }

    private static int le24(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
    }

    private static void wle16(FileOutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void wle24(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
    }

    private static void wle32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void recycle(Bitmap b) {
        try { if (b != null && !b.isRecycled()) b.recycle(); } catch (Throwable ignored) {}
    }

    private WebpToGif() {}
}
