package com.fj.dyhd;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * WebP 动图 -> H.264 MP4。
 *
 * 微信聊天里 GIF 常被当静态图压掉，MP4 才能稳定播放。
 * 用 MediaCodec 硬编 H.264 + MediaMuxer 封装，帧率固定 10fps。
 */
final class WebpToMp4 {

    private static final String TAG = "DYHD";
    private static final String MIME = "video/avc";
    private static final int FPS = 10;

    static File convert(String srcPath, int maxFrames) {
        java.util.List<Bitmap> frames = null;
        MediaCodec enc = null;
        MediaMuxer mux = null;
        File dst = null;
        try {
            frames = WebpToGif.extractFramesPublic(srcPath, maxFrames);
            if (frames == null || frames.size() < 2) {
                Log.println(Log.WARN, TAG, "webp2mp4: frames=" + (frames == null ? 0 : frames.size()));
                return null;
            }

            int w = frames.get(0).getWidth();
            int h = frames.get(0).getHeight();
            // H.264 要求偶数尺寸
            w = (w / 2) * 2;
            h = (h / 2) * 2;
            if (w < 16 || h < 16) return null;

            File src = new File(srcPath);
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            dst = new File(src.getParentFile(), name + ".mp4");

            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(512 * 1024, w * h * 6));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            enc = MediaCodec.createEncoderByType(MIME);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            mux = new MediaMuxer(dst.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int trackIndex = -1;
            boolean muxStarted = false;
            long ptsUs = 0;
            long frameDurUs = 1000000L / FPS;
            int framesWritten = 0;

            for (int fi = 0; fi < frames.size(); fi++) {
                Bitmap bmp = frames.get(fi);
                // 尺寸对齐：必要时缩放/裁剪到偶数尺寸
                Bitmap src2 = bmp;
                if (bmp.getWidth() != w || bmp.getHeight() != h) {
                    src2 = Bitmap.createScaledBitmap(bmp, w, h, true);
                }

                int inIdx = enc.dequeueInputBuffer(20000);
                if (inIdx < 0) continue;
                ByteBuffer inBuf = enc.getInputBuffer(inIdx);
                if (inBuf == null) continue;
                inBuf.clear();
                fillYuv420(inBuf, src2, w, h);
                enc.queueInputBuffer(inIdx, 0, yuvSize(w, h), ptsUs, 0);
                ptsUs += frameDurUs;
                if (src2 != bmp) src2.recycle();

                // 收编码输出
                while (true) {
                    int outIdx = enc.dequeueOutputBuffer(info, 0);
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxStarted) {
                            trackIndex = mux.addTrack(enc.getOutputFormat());
                            mux.start();
                            muxStarted = true;
                        }
                        continue;
                    }
                    if (outIdx < 0) continue;
                    ByteBuffer outBuf = enc.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        if (muxStarted && trackIndex >= 0) {
                            mux.writeSampleData(trackIndex, outBuf, info);
                            framesWritten++;
                        }
                    }
                    enc.releaseOutputBuffer(outIdx, false);
                }
            }

            // 冲最后几帧
            int inIdx = enc.dequeueInputBuffer(50000);
            if (inIdx >= 0) {
                enc.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            boolean eos = false;
            for (int guard = 0; guard < 200 && !eos; guard++) {
                int outIdx = enc.dequeueOutputBuffer(info, 20000);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        trackIndex = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                    }
                    continue;
                }
                if (outIdx < 0) continue;
                ByteBuffer outBuf = enc.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    if (muxStarted && trackIndex >= 0) {
                        mux.writeSampleData(trackIndex, outBuf, info);
                        framesWritten++;
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                enc.releaseOutputBuffer(outIdx, false);
            }

            if (muxStarted) { mux.stop(); muxStarted = false; }
            Log.println(Log.INFO, TAG, "webp2mp4: " + w + "x" + h + " samples=" + framesWritten
                    + " -> " + dst.getName() + " " + dst.length() + "B");

            if (framesWritten <= 0 || dst.length() <= 0) {
                dst.delete();
                return null;
            }
            return dst;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "webp2mp4 fail: " + t);
            try { if (dst != null && dst.exists()) dst.delete(); } catch (Throwable ignored) {}
            return null;
        } finally {
            try { if (mux != null) mux.release(); } catch (Throwable ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ignored) {}
            if (frames != null) {
                for (Bitmap b : frames) {
                    try { if (b != null && !b.isRecycled()) b.recycle(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /* ---------- 像素转换 ---------- */

    private static int yuvSize(int w, int h) {
        return w * h * 3 / 2;
    }

    /** ARGB -> YUV420 planar (I420) */
    private static void fillYuv420(ByteBuffer buf, Bitmap bmp, int w, int h) {
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        int ySize = w * h;
        int uvSize = ySize / 4;
        byte[] y = new byte[ySize];
        byte[] u = new byte[uvSize];
        byte[] v = new byte[uvSize];

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = px[j * w + i];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int yv = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                if (yv < 0) yv = 0; else if (yv > 255) yv = 255;
                y[j * w + i] = (byte) yv;
            }
        }
        int uIdx = 0;
        for (int j = 0; j < h; j += 2) {
            for (int i = 0; i < w; i += 2) {
                int sumR = 0, sumG = 0, sumB = 0;
                for (int dj = 0; dj < 2 && j + dj < h; dj++) {
                    for (int di = 0; di < 2 && i + di < w; di++) {
                        int c = px[(j + dj) * w + (i + di)];
                        sumR += (c >> 16) & 0xFF;
                        sumG += (c >> 8) & 0xFF;
                        sumB += c & 0xFF;
                    }
                }
                int r = sumR / 4, g = sumG / 4, b = sumB / 4;
                int uv = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                if (uv < 0) uv = 0; else if (uv > 255) uv = 255;
                if (vv < 0) vv = 0; else if (vv > 255) vv = 255;
                u[uIdx] = (byte) uv;
                v[uIdx] = (byte) vv;
                uIdx++;
            }
        }

        buf.clear();
        buf.put(y);
        buf.put(u);
        buf.put(v);
        buf.position(0);
    }

    private WebpToMp4() {}
}
