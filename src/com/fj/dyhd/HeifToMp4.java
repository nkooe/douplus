package com.fj.dyhd;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * HEIF 序列 -> H.264 MP4。
 *
 * 为什么要转码而不是重封装：
 *   HEIF 序列是 HEVC(H.265)。微信对 H.265 支持极差，发出去会灰度+绿条。
 *   必须解码成 YUV 再编成 H.264，虽然有一次重编码损失，但换来全平台兼容。
 */
final class HeifToMp4 {

    private static final String TAG = "DYHD";
    private static final Charset ASCII = Charset.forName("US-ASCII");

    static boolean isHeifSequence(String path) {
        RandomAccessFile raf = null;
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < 32) return false;
            raf = new RandomAccessFile(f, "r");
            byte[] ftyp = new byte[32];
            if (raf.read(ftyp) != 32) return false;
            if (!(ftyp[4]=='f' && ftyp[5]=='t' && ftyp[6]=='y' && ftyp[7]=='p')) return false;
            String brand = new String(ftyp, 8, 4, ASCII);
            if (!"msf1".equals(brand)) return false;
            return hasBox(f, "moov");
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    static File convert(String srcPath) {
        File tmp = null;
        File dst = null;
        try {
            tmp = rewriteFtyp(srcPath);
            if (tmp == null) {
                Log.println(Log.WARN, TAG, "heif2mp4: ftyp rewrite failed");
                return null;
            }
            File src = new File(srcPath);
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            dst = new File(src.getParentFile(), name + ".mp4");

            File res = transcodeToH264(tmp, dst);
            if (res == null) {
                Log.println(Log.WARN, TAG, "heif2mp4: transcode failed");
                if (dst.exists()) dst.delete();
                return null;
            }
            Log.println(Log.INFO, TAG, "heif2mp4(h264): " + dst.getName() + " " + dst.length() + "B");
            return dst;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "heif2mp4 fail: " + t);
            if (dst != null && dst.exists()) dst.delete();
            return null;
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete();
        }
    }

    /* ---------- ftyp：只换 brand，长度不变 ---------- */

    private static File rewriteFtyp(String srcPath) {
        RandomAccessFile raf = null;
        FileOutputStream out = null;
        try {
            File src = new File(srcPath);
            raf = new RandomAccessFile(src, "r");
            byte[] head = new byte[32];
            raf.seek(0);
            if (raf.read(head) != 32) return null;
            long ftypSize = be32(head, 0);
            if (ftypSize < 16 || ftypSize > 256) return null;

            byte[] ftypFull = new byte[(int) ftypSize];
            raf.seek(0);
            if (raf.read(ftypFull) != ftypFull.length) return null;

            byte[] newFtyp = rebuildFtyp(ftypFull);
            if (newFtyp.length != ftypSize) return null;

            File tmp = new File(src.getParentFile(), src.getName() + ".ftyp.tmp");
            out = new FileOutputStream(tmp);
            out.write(newFtyp);
            raf.seek(ftypSize);
            byte[] buf = new byte[65536];
            int n; long copied = 0;
            while ((n = raf.read(buf)) > 0) { out.write(buf, 0, n); copied += n; }
            out.flush(); out.close(); out = null;
            if (copied <= 0) { tmp.delete(); return null; }
            return tmp;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] rebuildFtyp(byte[] old) {
        int size = (int) be32(old, 0);
        long minor = be32(old, 12);
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<String>();
        ordered.add("mp42");
        int p = 16;
        while (p + 4 <= size) {
            String b = new String(old, p, 4, ASCII);
            if (!b.trim().isEmpty() && !"msf1".equals(b.trim())) ordered.add(b);
            p += 4;
        }
        if (!ordered.contains("isom")) ordered.add("isom");
        int slots = Math.max(1, (size - 16) / 4);
        byte[] out = new byte[size];
        putBE32(out, 0, size);
        out[4]='f'; out[5]='t'; out[6]='y'; out[7]='p';
        out[8]='m'; out[9]='p'; out[10]='4'; out[11]='2';
        putBE32(out, 12, (int) minor);
        int k = 16, w = 0;
        for (String b : ordered) {
            if (w >= slots) break;
            byte[] bs = b.getBytes(ASCII);
            for (int m = 0; m < 4 && m < bs.length; m++) out[k+m] = bs[m];
            k += 4; w++;
        }
        while (w < slots) { out[k]='i'; out[k+1]='s'; out[k+2]='o'; out[k+3]='m'; k+=4; w++; }
        return out;
    }

    /* ---------- HEVC -> H.264 转码 ---------- */

    private static File transcodeToH264(File in, File out) {
        MediaExtractor ex = null;
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(in.getAbsolutePath());

            int vIdx = -1;
            MediaFormat inFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { vIdx = i; inFmt = f; break; }
            }
            if (vIdx < 0 || inFmt == null) return null;
            ex.selectTrack(vIdx);

            String inMime = inFmt.getString(MediaFormat.KEY_MIME);
            int w = inFmt.getInteger(MediaFormat.KEY_WIDTH);
            int h = inFmt.getInteger(MediaFormat.KEY_HEIGHT);
            int w2 = (w / 2) * 2, h2 = (h / 2) * 2;
            Log.println(Log.INFO, TAG, "transcode: in=" + inMime + " " + w + "x" + h);

            dec = MediaCodec.createDecoderByType(inMime);
            dec.configure(inFmt, null, null, 0);
            dec.start();

            MediaFormat outFmt = MediaFormat.createVideoFormat("video/avc", w2, h2);
            outFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            outFmt.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(800 * 1024, w2 * h2 * 8));
            outFmt.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            outFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            int muxTrack = -1;
            boolean muxStarted = false;
            boolean inDone = false, outDone = false;
            int written = 0;
            int guard = 0;

            while (!outDone && guard++ < 5000) {
                // 喂解码器
                if (!inDone) {
                    int iIdx = dec.dequeueInputBuffer(10000);
                    if (iIdx >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(iIdx);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(iIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(iIdx, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                // 收解码结果
                int dIdx = dec.dequeueOutputBuffer(decInfo, 10000);
                if (dIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.println(Log.INFO, TAG, "decoder out fmt: " + dec.getOutputFormat());
                } else if (dIdx >= 0) {
                    if (decInfo.size > 0) {
                        android.media.MediaCodecInfo.CodecCapabilities.class.getName();
                        // 把解码出的帧原样交给编码器（YUV420Flexible，尺寸一致时可直通）
                        int eIdx = enc.dequeueInputBuffer(20000);
                        if (eIdx >= 0) {
                            ByteBuffer eb = enc.getInputBuffer(eIdx);
                            ByteBuffer db = dec.getOutputBuffer(dIdx);
                            if (db != null && eb != null) {
                                db.position(decInfo.offset);
                                db.limit(decInfo.offset + decInfo.size);
                                eb.clear();
                                eb.put(db);
                                enc.queueInputBuffer(eIdx, 0, decInfo.size, decInfo.presentationTimeUs, 0);
                            }
                        }
                    }
                    dec.releaseOutputBuffer(dIdx, false);
                }

                // 收编码结果
                while (true) {
                    int eIdx = enc.dequeueOutputBuffer(encInfo, 0);
                    if (eIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxStarted) {
                            muxTrack = mux.addTrack(enc.getOutputFormat());
                            mux.start();
                            muxStarted = true;
                        }
                        continue;
                    }
                    if (eIdx < 0) continue;
                    ByteBuffer eb = enc.getOutputBuffer(eIdx);
                    if (eb != null && encInfo.size > 0
                            && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        eb.position(encInfo.offset);
                        eb.limit(encInfo.offset + encInfo.size);
                        if (muxStarted && muxTrack >= 0) {
                            mux.writeSampleData(muxTrack, eb, encInfo);
                            written++;
                        }
                    }
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    enc.releaseOutputBuffer(eIdx, false);
                }

                // 解码器 EOS 后给编码器送 EOS
                if (inDone && dIdx == MediaCodec.INFO_TRY_AGAIN_LATER && !outDone) {
                    int eIdx = enc.dequeueInputBuffer(10000);
                    if (eIdx >= 0) {
                        enc.queueInputBuffer(eIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }
            }

            if (muxStarted) { mux.stop(); }
            Log.println(Log.INFO, TAG, "transcode: written=" + written);
            if (written <= 0) { out.delete(); return null; }
            return out;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "transcode fail: " + t);
            return null;
        } finally {
            try { if (mux != null) mux.release(); } catch (Throwable ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ignored) {}
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable ignored) {}
            try { if (ex != null) ex.release(); } catch (Throwable ignored) {}
        }
    }

    /* ---------- 工具 ---------- */

    private static boolean hasBox(File f, String want) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length(), pos = 0;
            byte[] hdr = new byte[8];
            int guard = 0;
            while (pos + 8 <= len && guard++ < 64) {
                raf.seek(pos);
                if (raf.read(hdr) != 8) break;
                long sz = be32(hdr, 0);
                String type = new String(hdr, 4, 4, ASCII);
                if (type.equals(want)) return true;
                if (sz == 1) {
                    byte[] big = new byte[8];
                    raf.seek(pos + 8);
                    if (raf.read(big) != 8) break;
                    sz = 0;
                    for (int i = 0; i < 8; i++) sz = (sz << 8) | (big[i] & 0xFF);
                    if (sz < 16) break;
                } else if (sz <= 0 || sz < 8) break;
                pos += sz;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static long be32(byte[] b, int i) {
        return ((long)(b[i] & 0xFF) << 24) | ((long)(b[i+1] & 0xFF) << 16)
                | ((long)(b[i+2] & 0xFF) << 8) | (long)(b[i+3] & 0xFF);
    }

    private static void putBE32(byte[] b, int i, int v) {
        b[i] = (byte)((v>>24)&0xFF); b[i+1] = (byte)((v>>16)&0xFF);
        b[i+2] = (byte)((v>>8)&0xFF); b[i+3] = (byte)(v&0xFF);
    }

    private HeifToMp4() {}
}