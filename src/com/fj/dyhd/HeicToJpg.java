package com.fj.dyhd;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * HEIC/HEIF 静图 -> JPG。
 *
 * 为什么要转：Android 对 .heic 的 MIME 归类不稳，MediaScanner 经常判成 video，
 * 点开就跳视频播放器。转成 .jpg 后 MIME 固定是 image/jpeg，相册/图片查看器一定认。
 */
final class HeicToJpg {

    private static final String TAG = "DYHD";

    /** 把 HEIC/HEIF 静图转成同目录同名 .jpg；成功返回 File，失败 null */
    static File convert(String srcPath) {
        FileOutputStream out = null;
        Bitmap bmp = null;
        try {
            File src = new File(srcPath);
            // 只解第一帧（静图）
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = false;
            Bitmap raw = BitmapFactory.decodeFile(srcPath, opt);
            if (raw == null) {
                Log.println(Log.WARN, TAG, "img2jpg: decode failed");
                return null;
            }
            // JPEG 没有 alpha：带透明的先铺白底，否则透明区会变黑
            if (raw.hasAlpha()) {
                bmp = Bitmap.createBitmap(raw.getWidth(), raw.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                cv.drawColor(0xFFFFFFFF);
                cv.drawBitmap(raw, 0, 0, null);
                raw.recycle();
            } else {
                bmp = raw;
            }

            String name = src.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            File dst = new File(src.getParentFile(), name + ".jpg");

            out = new FileOutputStream(dst);
            boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();
            out = null;
            if (!ok || dst.length() <= 0) {
                dst.delete();
                return null;
            }
            Log.println(Log.INFO, TAG, "img2jpg: " + dst.getName() + " " + dst.length()
                    + "B " + bmp.getWidth() + "x" + bmp.getHeight());
            return dst;
        } catch (Throwable t) {
            Log.println(Log.WARN, TAG, "img2jpg fail: " + t);
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (bmp != null) bmp.recycle(); } catch (Throwable ignored) {}
        }
    }

    private HeicToJpg() {}
}