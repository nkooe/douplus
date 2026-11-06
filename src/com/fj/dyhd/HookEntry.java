package com.fj.dyhd;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 抖plus - Modern API 版 (LSPosed API 101+)
 *
 * 与旧版 Xposed API 的差别：
 *   入口    META-INF/xposed/java_init.list 指向本类
 *   钩子    hook(Executable).intercept(chain -> ...)  取代 findAndHookMethod
 *   返回值  chain.proceedWith(x)                      取代 param.setResult(x)
 *   入参    chain.getArg(i) / chain.getThisObject()   取代 param.args / param.thisObject
 *
 * 这样宿主只需强制停止重开即可生效，不需要重启设备刷 dex2oat 缓存。
 */
public class HookEntry extends XposedModule {

    private static final String PKG  = "com.ss.android.ugc.aweme";
    private static final String PREF = "dyhd";
    private static final String TAG  = "DYHD";

    private static final String C_VIDEO   = "com.ss.android.ugc.aweme.feed.model.Video";
    private static final String C_URL     = "com.ss.android.ugc.aweme.base.model.UrlModel";
    private static final String C_LPP     = "com.ss.android.ugc.aweme.feed.long_press_panel.base.LongPressPanelDelegate";


    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (param == null) return;
        String pkg = param.getPackageName();
        if (!PKG.equals(pkg)) return;
        ClassLoader cl = param.getDefaultClassLoader();
        log(4, TAG, "onPackageLoaded pkg=" + pkg + " process=" + param.getApplicationInfo().processName);

        String a = hookDownloadAddr(cl);
        String b = hookLongPress(cl);
        String c = hookCommentMedia(cl);
        hookPreviewButtons(cl);
        hookDialogEmoji(cl);
        hookActivityTracker(cl);
        hookAutoPlay(cl);
        hookNativeMenuItem(cl);
        hookAwemeDesc(cl);
        log(4, TAG, "init addr=" + a + " panel=" + b + " comment=" + c);
    }

    /* ============ 1) 长按面板注入按钮 ============ */

    private String hookLongPress(ClassLoader cl) {
        Class<?> lpp;
        try { lpp = Class.forName(C_LPP, false, cl); }
        catch (Throwable t) { return "no-lpp"; }

        int n = 0;
        for (final Method m : lpp.getDeclaredMethods()) {
            if (m.getName().startsWith("<")) continue;
            final String mn = m.getName();
            try {
                hook(m).setId("dyhd-panel-" + mn).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        Object ret = chain.proceed();
                        try { onPanelShown(chain); }
                        catch (Throwable t) { log(5, TAG, "panel inject err " + t); }
                        return ret;
                    }
                });
                n++;
            } catch (Throwable ignored) {}
        }
        return n == 0 ? "no-method" : ("ok/" + n);
    }

    private void onPanelShown(XposedInterface.Chain chain) {
        if (!isEnabled()) return;
        View root = null;
        for (Object a : chain.getArgs()) {
            if (a instanceof View) { root = (View) a; break; }
        }
        Object thiz = chain.getThisObject();
        if (root == null && thiz instanceof View) root = (View) thiz;

        Object params = findParams(thiz);
        Object aweme  = getFieldByName(params, "a");
        Object video  = getVideo(aweme);

        // 诊断：搜索页不弹按钮时，先看卡在哪一步
        log(4, TAG, "panel probe thiz=" + (thiz == null ? "null" : thiz.getClass().getSimpleName())
                + " root=" + (root != null)
                + " params=" + (params == null ? "null" : params.getClass().getSimpleName())
                + " aweme=" + (aweme != null)
                + " video=" + (video != null));

        if (root == null) return;
        log(4, TAG, "panel hit root=" + root.getClass().getSimpleName()
                + " video=" + (video != null)
                + " menuOk=" + sMenuInjectOk + " menuFail=" + sMenuInjectFails);
        if (video == null) return;

        // 原生菜单可用时不再显示浮窗；菜单连续没插进去则退回浮窗，保证功能不丢
        final View froot = root;
        final Object fvideo = video;
        final Object faweme = aweme;
        final int before = sMenuInjectOk;      // 本次长按前的快照
        postDelayedOn(froot, new Runnable() {
            @Override public void run() {
                if (sMenuInjectOk > before) {
                    log(4, TAG, "fallback: this panel got native item, skip floating");
                } else {
                    log(4, TAG, "fallback: no native item this time, show floating button");
                    injectButton(froot, fvideo, faweme);
                }
            }
        }, 300);
    }

    private static Object findParams(Object delegate) {
        if (delegate == null) return null;
        for (Field f : delegate.getClass().getDeclaredFields()) {
            if (f.getType().getName().endsWith("LongPressPanelParams")) {
                try { f.setAccessible(true); return f.get(delegate); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 按名字取字段值：沿继承链查找（父类字段也能拿到） */
    private static Object getFieldByName(Object o, String name) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object getVideo(Object aweme) {
        if (aweme == null) return null;
        try {
            Method m = aweme.getClass().getMethod("getVideo");
            return m.invoke(aweme);
        } catch (Throwable ignored) {}
        Class<?> c = aweme.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().endsWith("feed.model.Video")) {
                    try { f.setAccessible(true); return f.get(aweme); } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void injectButton(final View root, final Object video, final Object aweme) {
        if (!(root instanceof ViewGroup)) return;
        final ViewGroup vg = (ViewGroup) root;
        final String mark = "fj_dyhd_btn";

        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c != null && mark.equals(c.getTag())) return;
        }

        final Context ctx = vg.getContext();
        TextView btn = new TextView(ctx);
        btn.setTag(mark);
        btn.setText("高画质下载");
        btn.setTextSize(13f);
        btn.setTextColor(0xFFFFFFFF);
        btn.setPadding(26, 13, 26, 13);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFE2C55);
        bg.setCornerRadius(26f);
        btn.setBackground(bg);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { showQualityDialog(ctx, video, aweme); }
                catch (Throwable t) { log(5, TAG, "dialog err " + t); }
            }
        });

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setTag(mark + "_wrap");
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.END);
        wrap.setPadding(0, 8, 24, 8);
        wrap.setClickable(true);
        wrap.setFocusable(true);
        wrap.addView(btn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        vg.addView(wrap, lp);
        wrap.bringToFront();
        log(4, TAG, "button injected");
    }

    /* ============ 2) 画质选择弹窗 ============ */

    private void showQualityDialog(final Context ctx, final Object video, final Object aweme) {
        final List<Object> addrs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        Object origin = findPlayAddrField(video);
        if (hasUrl(origin)) {
            labels.add("超高原画质-无水印");
            addrs.add(origin);
        }

        List<?> rates = findBitRateList(video);
        if (rates != null) {
            java.util.LinkedHashMap<String, Object[]> grp = new java.util.LinkedHashMap<>();
            for (Object it : rates) {
                if (it == null) continue;
                String gear = strField(it, "gearName");
                String res = (gear == null || gear.isEmpty()) ? "" : gear.split("_")[0];
                if (res.isEmpty()) continue;
                Object addr = field(it, "playAddr");
                if (!hasUrl(addr)) addr = field(it, "playAddrBytevc1");
                if (!hasUrl(addr)) addr = field(it, "playAddr265");
                if (!hasUrl(addr)) continue;
                int br = intField(it, "bitRate");
                Object[] cur = grp.get(res);
                if (cur == null || br > ((Number) cur[1]).intValue()) {
                    grp.put(res, new Object[]{addr, Integer.valueOf(br)});
                }
            }
            List<String> keys = new ArrayList<>(grp.keySet());
            java.util.Collections.sort(keys, new java.util.Comparator<String>() {
                @Override public int compare(String x, String y) { return num(y) - num(x); }
            });
            for (String k : keys) {
                labels.add(k + "P画质-无水印");
                addrs.add(grp.get(k)[0]);
            }
        }

        if (addrs.isEmpty()) { toast(ctx, "没有可下载的地址"); return; }

        final String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(ctx)
                .setTitle("选择下载画质（无水印）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        try { download(ctx, addrs.get(which), items[which], aweme); }
                        catch (Throwable t) { log(5, TAG, "dl err " + t); }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ============ 3) 下载 ============ */

    private void download(final Context ctx, Object urlModel, String label, Object aweme) {
        final String url = firstUrl(urlModel);
        log(4, TAG, "download label=" + label + " url=" + url);
        if (url == null) { toast(ctx, "地址为空"); return; }

        String gear = label.split(" ")[0];
        String title = safeName(strField(aweme, "desc"));
        String base = title.isEmpty() ? ("douyin_" + gear + "_" + System.currentTimeMillis()) : title;
        final String name = base + ".mp4";

        final File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "douyin");
        if (!dir.exists()) dir.mkdirs();
        final String path = uniqueFile(dir, name).getAbsolutePath();
        log(4, TAG, "target=" + path);
        toast(ctx, "开始下载 " + gear);

        new Thread(new Runnable() {
            @Override public void run() {
                java.io.InputStream in = null;
                java.io.FileOutputStream out = null;
                try {
                    java.net.URLConnection c = new java.net.URL(url).openConnection();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.connect();
                    in = c.getInputStream();
                    out = new java.io.FileOutputStream(path);
                    byte[] buf = new byte[65536];
                    int n; long done = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; }
                    out.flush(); out.close(); out = null;
                    in.close(); in = null;
                    log(4, TAG, "download done bytes=" + done);
                    android.media.MediaScannerConnection.scanFile(
                            ctx, new String[]{path}, new String[]{"video/mp4"}, null);
                    final long fdone = done;
                    post(ctx, new Runnable() {
                        @Override public void run() { showDone(ctx, name, path, fdone); }
                    });
                } catch (final Throwable t) {
                    log(5, TAG, "download err " + t);
                    try { if (out != null) out.close(); } catch (Throwable ig) {}
                    try { if (in != null) in.close(); } catch (Throwable ig) {}
                    final String msg = String.valueOf(t);
                    post(ctx, new Runnable() {
                        @Override public void run() { toast(ctx, "下载失败: " + msg); }
                    });
                }
            }
        }).start();
    }

    /** 在指定 View 上延迟执行（保证在主线程） */
    private static void postDelayedOn(View v, Runnable r, long ms) {
        try { v.postDelayed(r, ms); }
        catch (Throwable t) { post(v.getContext(), r); }
    }

    private static void post(Context ctx, Runnable r) {
        try { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
        catch (Throwable t) { r.run(); }
    }

    private void toast(Context ctx, String msg) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }


    /** 按文件头纠正扩展名（抖音表情多为 HEIC / WebP） */
    /** 同名文件已存在时追加 (1)(2)...，避免覆盖 */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + "(" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    /** 扫 ISO BMFF 顶层 box，判断是否存在某个 box（如 moov = 视频/序列） */
    private static boolean hasTopLevelBox(java.io.File f, String want) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long pos = 0;
            byte[] hdr = new byte[8];
            int guard = 0;
            while (pos + 8 <= len && guard++ < 64) {
                raf.seek(pos);
                if (raf.read(hdr) != 8) break;
                long size = ((long)(hdr[0] & 0xFF) << 24) | ((long)(hdr[1] & 0xFF) << 16)
                        | ((long)(hdr[2] & 0xFF) << 8) | (long)(hdr[3] & 0xFF);
                String type = new String(hdr, 4, 4, java.nio.charset.Charset.forName("US-ASCII"));
                if (type.equals(want)) return true;
                if (size == 1) {                      // 64 位长度
                    byte[] big = new byte[8];
                    raf.seek(pos + 8);
                    if (raf.read(big) != 8) break;
                    size = 0;
                    for (int i = 0; i < 8; i++) size = (size << 8) | (big[i] & 0xFF);
                    if (size < 16) break;
                } else if (size == 0) {
                    break;                            // 到文件末尾
                } else if (size < 8) {
                    break;
                }
                pos += size;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 按扩展名给出明确的 MIME，避免 MediaScanner 把图片猜成视频 */
    private static String mimeOf(String path) {
        if (path == null) return null;
        String p = path.toLowerCase(java.util.Locale.US);
        if (p.endsWith(".heif")) return "image/heif";
        if (p.endsWith(".heic")) return "image/heic";
        if (p.endsWith(".avif")) return "image/avif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif"))  return "image/gif";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".mp4"))  return "video/mp4";
        if (p.endsWith(".mov"))  return "video/quicktime";
        return null;
    }

    private static String fixExtByMagic(String path) {
        java.io.File f = new java.io.File(path);
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[16];
            int n = in.read(h);
            if (n < 12) return path;
            String ext = null;
            if (h[0] == (byte) 0xFF && h[1] == (byte) 0xD8) ext = ".jpg";
            else if (h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') ext = ".png";
            else if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') ext = ".gif";
            else if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') ext = ".webp";
            else if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
                // ISO BMFF 家族。判定靠顶层 box：有 moov = 序列(动图)，有 meta = 单帧(静图)
                String brand = new String(h, 8, 4, java.nio.charset.Charset.forName("US-ASCII"));
                if (brand.startsWith("avif")) {
                    ext = ".avif";
                } else if (brand.startsWith("msf1")) {
                    ext = ".heif";                                     // 明确是 HEIF 序列
                } else if (brand.startsWith("heic") || brand.startsWith("heix")
                        || brand.startsWith("mif1") || brand.startsWith("miaf")) {
                    ext = hasTopLevelBox(f, "moov") ? ".heif" : ".heic";   // 靠 moov/meta 区分
                } else {
                    ext = ".mp4";
                }
            }
            if (ext == null) return path;
            String base = path.endsWith(".img") ? path.substring(0, path.length() - 4) : path;
            java.io.File dst = new java.io.File(base + ext);
            if (dst.exists()) dst.delete();
            if (f.renameTo(dst)) return dst.getAbsolutePath();
            return path;
        } catch (Throwable t) { return path; }
        finally { try { if (in != null) in.close(); } catch (Throwable ignored) {} }
    }

    private void showDone(final Context ctx, String name, final String path, long bytes) {
        try {
            String size = bytes > 1048576
                    ? String.format("%.1f MB", bytes / 1048576.0)
                    : String.format("%d KB", bytes / 1024);
            new AlertDialog.Builder(ctx)
                    .setTitle("已保存到相册")
                    .setMessage(name + "\n\n" + size + "\nDownload/douyin")
                    .setPositiveButton("去相册看看", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                                i.setDataAndType(android.net.Uri.parse(path), "video/mp4");
                                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(i);
                            } catch (Throwable t) { toast(ctx, "已保存到相册"); }
                        }
                    })
                    .setNegativeButton("好", null)
                    .show();
        } catch (Throwable t) { toast(ctx, "已保存到相册"); }
    }

    /* ============ 4) getDownloadAddr 兜底 ============ */

    private String hookDownloadAddr(ClassLoader cl) {
        Class<?> video, urlModel;
        try {
            video    = Class.forName(C_VIDEO, false, cl);
            urlModel = Class.forName(C_URL, false, cl);
        } catch (Throwable t) { return "cls-missing"; }

        String getter = null;
        try { video.getDeclaredMethod("getDownloadAddr"); getter = "getDownloadAddr"; } catch (Throwable ignored) {}
        if (getter == null) {
            for (Method m : video.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != urlModel) continue;
                String nm = m.getName();
                if (nm.startsWith("_") || nm.equals("toString")) continue;
                if (getter == null || nm.length() < getter.length()) getter = nm;
            }
        }
        if (getter == null) return "no-getter";

        try {
            final Method target = video.getDeclaredMethod(getter);
            hook(target).setId("dyhd-dladdr").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    Object ret = chain.proceed();
                    if (!isEnabled()) return ret;
                    try {
                        Object self = chain.getThisObject();
                        if (self != null) sLastVideo = self;
                        Object best = bestAddr(self);
                        if (best != null) return best;
                    } catch (Throwable ignored) {}
                    return ret;
                }
            });
        } catch (Throwable t) { return "hook-fail"; }
        return getter;
    }

    /* ============ 5) 评论区图片 / 表情 ============ */

    private String hookCommentMedia(ClassLoader cl) {
        int ok = 0;
        // 图片评论：LJIJJLI(Comment, CommentImageStruct)
        try {
            Class<?> icc = Class.forName(
                    "com.ss.android.ugc.aweme.comment.adapter.vh.components.ImageCommentComponent", false, cl);
            Class<?> cis = Class.forName(
                    "com.ss.android.ugc.aweme.comment.model.CommentImageStruct", false, cl);
            for (final Method m : icc.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2) continue;
                if (ps[1] != cis) continue;
                hook(m).setId("dyhd-imgcomment").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        Object ret = chain.proceed();
                        try {
                            Object struct = chain.getArg(1);
                            String url = pickImageUrl(struct);
                            log(4, TAG, "imgComment click url=" + url);
                            if (url != null) attachMediaDownloader(chain.getArg(0), url, "image");
                        } catch (Throwable t) { log(5, TAG, "imgComment err " + t); }
                        return ret;
                    }
                });
                ok++;
            }
        } catch (Throwable t) { log(5, TAG, "imgComment hook fail " + t); }

        // 表情评论：EmojiComponent.LJIJI(View)
        try {
            Class<?> ec = Class.forName(
                    "com.ss.android.ugc.aweme.comment.adapter.vh.components.EmojiComponent", false, cl);
            for (final Method m : ec.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != View.class) continue;
                hook(m).setId("dyhd-emoji").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        try {
                            Object thiz = chain.getThisObject();
                            // Emoji 实例由 dialog 捕获，这里不再记录（避免相似列表预加载覆盖）
                        } catch (Throwable t) { log(5, TAG, "emoji rec err " + t); }
                        return chain.proceed();
                    }
                });
                ok++;
            }
        } catch (Throwable t) { log(5, TAG, "emoji hook fail " + t); }

        return ok == 0 ? "none" : ("ok/" + ok);
    }

    /** CommentImageStruct 的多个 UrlModel 字段里挑一个可用地址 */
    private static String pickImageUrl(Object struct) {
        if (struct == null) return null;
        String[] order = {"a", "b", "i", "d", "e", "f"};
        for (String n : order) {
            String u = firstUrl(field(struct, n));
            if (u != null) return u;
        }
        return firstUrl(field(struct, "localUrl"));
    }

    /** 在预览页加一个下载按钮（挂在 DecorView 右上角） */
    private void attachMediaDownloader(Object ctxObj, final String url, final String kind) {
        if (!(ctxObj instanceof Context)) return;
        final Context ctx = (Context) ctxObj;
        post(ctx, new Runnable() {
            @Override public void run() {
                try {
                    if (!(ctx instanceof android.app.Activity)) return;
                    final android.app.Activity act = (android.app.Activity) ctx;
                    final android.view.ViewGroup decor = (android.view.ViewGroup) act.getWindow().getDecorView();
                    final String mark = "fj_dyhd_media_" + kind;
                    if (decor.findViewWithTag(mark) != null) return;

                    android.widget.TextView btn = new android.widget.TextView(act);
                    btn.setTag(mark);
                    btn.setText("下载");
                    btn.setTextSize(13f);
                    btn.setTextColor(0xFFFFFFFF);
                    btn.setPadding(28, 14, 28, 14);
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0xFFFE2C55);
                    bg.setCornerRadius(28f);
                    btn.setBackground(bg);
                    btn.setClickable(true);

                    android.widget.FrameLayout.LayoutParams lp =
                            new android.widget.FrameLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                    lp.topMargin = (int) (56 * act.getResources().getDisplayMetrics().density);
                    lp.rightMargin = (int) (16 * act.getResources().getDisplayMetrics().density);

                    btn.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try {
                    // 用 .img 占位，扩展名由 fixExtByMagic 按魔数决定
                    saveMedia(act, url,
                            (kind.equals("emoji") ? "抖音表情" : "抖音图片")
                            + "_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                                    .format(new java.util.Date()) + ".img");
                            } catch (Throwable t) { log(5, TAG, "media dl err " + t); }
                        }
                    });
                    decor.addView(btn, lp);
                    btn.bringToFront();
                    log(4, TAG, "media button attached kind=" + kind + " act=" + act.getClass().getSimpleName());
                } catch (Throwable t) { log(5, TAG, "attach err " + t); }
            }
        });
    }

    private void saveMedia(final Context ctx, final String url, final String name) {
        final File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "douyin");
        if (!dir.exists()) dir.mkdirs();
        final String path = uniqueFile(dir, safeName(name)).getAbsolutePath();
        new Thread(new Runnable() {
            @Override public void run() {
                java.io.InputStream in = null;
                java.io.FileOutputStream out = null;
                try {
                    java.net.URLConnection c = new java.net.URL(url).openConnection();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    in = c.getInputStream();
                    out = new java.io.FileOutputStream(path);
                    byte[] buf = new byte[65536];
                    int n; long done = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; }
                    out.flush(); out.close(); out = null;
                    in.close(); in = null;
                    String fp = fixExtByMagic(path);
                    // 保存原格式：只按魔数修正扩展名，不转码
                    log(4, TAG, "saved original: " + new File(fp).getName()
                            + " " + new File(fp).length() + "B");

                    // 动图统一输出 MP4（微信聊天只认视频/GIF，MP4 最稳）
                    String savePath = fp;
                    String low = fp.toLowerCase(java.util.Locale.US);
                    try {
                        if (low.endsWith(".heic") || low.endsWith(".heif")) {
                            // 静图 -> JPG（MIME 稳定为 image/jpeg，不会被当视频打开）
                            if (HeifToMp4.isHeifSequence(fp)) {
                                File m = HeifToMp4.convert(fp);
                                if (m != null && m.exists() && m.length() > 0) {
                                    log(4, TAG, "heif->mp4 ok: " + m.getName() + " " + m.length() + "B");
                                    new File(fp).delete();
                                    savePath = m.getAbsolutePath();
                                } else {
                                    log(4, TAG, "heif->mp4 failed, keep heif");
                                }
                            } else {
                                File j = HeicToJpg.convert(fp);
                                if (j != null && j.exists() && j.length() > 0) {
                                    log(4, TAG, "heic->jpg ok: " + j.getName() + " " + j.length() + "B");
                                    new File(fp).delete();
                                    savePath = j.getAbsolutePath();
                                } else {
                                    log(4, TAG, "heic->jpg failed, keep heic");
                                }
                            }
                        } else if (low.endsWith(".webp")) {
                            if (WebpToGif.isAnimatedWebp(fp)) {
                                // 动图 -> MP4
                                File m = WebpToMp4.convert(fp, 200);
                                if (m != null && m.exists() && m.length() > 0) {
                                    log(4, TAG, "webp->mp4 ok: " + m.getName() + " " + m.length() + "B");
                                    new File(fp).delete();
                                    savePath = m.getAbsolutePath();
                                } else {
                                    File g = WebpToGif.convert(fp, 200);
                                    if (g != null && g.exists() && g.length() > 0) {
                                        log(4, TAG, "webp->gif fallback: " + g.getName() + " " + g.length() + "B");
                                        new File(fp).delete();
                                        savePath = g.getAbsolutePath();
                                    } else {
                                        log(4, TAG, "webp anim convert failed, keep webp");
                                    }
                                }
                            } else {
                                // 静图 -> JPG（透明铺白底）
                                File j = HeicToJpg.convert(fp);
                                if (j != null && j.exists() && j.length() > 0) {
                                    log(4, TAG, "webp(static)->jpg ok: " + j.getName() + " " + j.length() + "B");
                                    new File(fp).delete();
                                    savePath = j.getAbsolutePath();
                                } else {
                                    log(4, TAG, "webp(static)->jpg failed, keep webp");
                                }
                            }
                        } else if (low.endsWith(".avif")) {
                            File j = HeicToJpg.convert(fp);
                            if (j != null && j.exists() && j.length() > 0) {
                                log(4, TAG, "avif->jpg ok: " + j.getName());
                                new File(fp).delete();
                                savePath = j.getAbsolutePath();
                            }
                        }
                    } catch (Throwable t) {
                        log(5, TAG, "anim convert err " + t);
                    }

                    final String finalPath = savePath;
                    android.media.MediaScannerConnection.scanFile(ctx, new String[]{finalPath},
                            new String[]{mimeOf(finalPath)}, null);
                    final long fd = done;
                    post(ctx, new Runnable() {
                        @Override public void run() { showDone(ctx, new File(finalPath).getName(), finalPath, fd); }
                    });
                } catch (final Throwable t) {
                    log(5, TAG, "saveMedia err " + t);
                    try { if (out != null) out.close(); } catch (Throwable ig) {}
                    try { if (in != null) in.close(); } catch (Throwable ig) {}
                    post(ctx, new Runnable() {
                        @Override public void run() { toast(ctx, "下载失败"); }
                    });
                }
            }
        }).start();
    }



    private static Object scanFor(Object obj, Class<?> target, int depth) {
        if (obj == null || depth > 2) return null;
        Class<?> c = obj.getClass();
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == target) {
                try { f.setAccessible(true); Object v = f.get(obj); if (v != null) return v; }
                catch (Throwable ignored) {}
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (f.getType().isPrimitive() || f.getType().isArray()) continue;
            String tn = f.getType().getName();
            if (tn.startsWith("android") || tn.startsWith("java") || tn.startsWith("kotlin")) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null && depth < 2) {
                    Object r = scanFor(v, target, depth + 1);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 给「添加表情」类按钮挂长按下载 */
    private void hookPreviewButtons(ClassLoader cl) {
        try {
            final Class<?> tv = android.widget.TextView.class;
            final Method mAttach = android.view.View.class.getDeclaredMethod("onAttachedToWindow");
            hook(mAttach).setId("dyhd-attach").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    Object ret = chain.proceed();
                    try {
                        Object thiz = chain.getThisObject();
                        if (!(thiz instanceof android.widget.TextView)) return ret;
                        android.widget.TextView t = (android.widget.TextView) thiz;
                        CharSequence cs = t.getText();
                        if (cs == null) return ret;
                        String txt = cs.toString().trim();
                        if (!txt.contains("添加表情") && !txt.contains("添加")) return ret;
                        final android.content.Context ctx = t.getContext();
                        t.setLongClickable(true);
                        t.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override public boolean onLongClick(View v) {
                                if (!isEmojiEnabled()) {
                                    toast(ctx, "表情下载已关闭");
                                    return true;
                                }
                                String url = null;
                                Object de = sDialogEmoji;
                                if (de != null) url = emojiBestUrl(de);
                                log(4, TAG, "emoji longpress url=" + url
                                        + " fromDialog=" + (de != null));
                                if (url == null) {
                                    toast(ctx, "没有可用地址");
                                    return true;
                                }
                                saveMedia(ctx, url, "抖音表情_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".img");
                                return true;
                            }
                        });
                        log(4, TAG, "longpress hooked on: " + txt);
                    } catch (Throwable t) { log(5, TAG, "attach hook err " + t); }
                    return ret;
                }
            });
        } catch (Throwable t) { log(5, TAG, "hookPreviewButtons fail " + t); }
    }

    /* ============ 5b) 预览对话框里的 Emoji ============ */

    private static volatile Object sDialogEmoji;

    private void hookDialogEmoji(ClassLoader cl) {
        final Class<?> emojiCls;
        try { emojiCls = Class.forName("com.ss.android.ugc.aweme.emoji.model.Emoji", false, cl); }
        catch (Throwable t) { log(5, TAG, "dialog: no Emoji class"); return; }

        try {
            final Method show = android.app.Dialog.class.getDeclaredMethod("show");
            hook(show).setId("dyhd-dialog-show").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    Object ret = chain.proceed();
                    try {
                        Object dlg = chain.getThisObject();
                        if (dlg == null) return ret;
                        Object e = findDeep(dlg, emojiCls, 0, 3);
                        if (e != null) {
                            sDialogEmoji = e;
                            log(4, TAG, "dialog emoji captured url=" + emojiBestUrl(e));
                        }
                    } catch (Throwable t) { log(5, TAG, "dialog err " + t); }
                    return ret;
                }
            });
            log(4, TAG, "dialog hook installed");
        } catch (Throwable t) { log(5, TAG, "dialog hook fail " + t); }
    }

    /** 广度优先深度搜索目标类型实例（跳过 View/图形/上下文类，限制节点数） */
    private static Object findDeep(Object root, Class<?> target, int depth, int maxDepth) {
        if (root == null || depth > maxDepth) return null;
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        q.add(root);
        int inspected = 0;
        while (!q.isEmpty() && inspected < 400) {
            Object o = q.poll();
            if (o == null) continue;
            if (seen.put(o, Boolean.TRUE) != null) continue;
            inspected++;
            Class<?> c = o.getClass();
            if (c == target) return o;
            String cn = c.getName();
            if (cn.startsWith("android.") || cn.startsWith("java.") || cn.startsWith("kotlin.")) continue;

            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                String fn = ft.getName();
                if (fn.startsWith("android.view") || fn.startsWith("android.graphics")
                        || fn.startsWith("android.content") || fn.startsWith("android.widget")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v.getClass() == target) return v;
                    if (depth + 1 <= maxDepth) q.add(v);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 取 Emoji 的最佳地址：动图优先 animateUrl，静态用 staticUrl */
    private static String emojiBestUrl(Object emoji) {
        String atype = strField(emoji, "animateType");
        String anim  = firstUrl(field(emoji, "animateUrl"));
        String stat  = firstUrl(field(emoji, "staticUrl"));
        if ("lottie".equalsIgnoreCase(atype)) return stat != null ? stat : anim;
        return anim != null ? anim : stat;
    }

    /* ============ 6) 自动播放下一个 ============ */

    private static volatile android.app.Activity sCurActivity;
    private static volatile Object sLastVideo;      // 最近一次被取下载地址的 Video
    private static volatile Object sLastAweme;      // 配套的 Aweme（用于标题）
    private static volatile long sLastSwipeTs = 0;   // 上次切走的时刻（节流）
    private static volatile View sFab;              // 悬浮球
    private static volatile ViewGroup sFabHost;     // 悬浮球宿主

    /** 追踪当前前台 Activity，供自动播放/滑动手势使用 */
    private void hookActivityTracker(ClassLoader cl) {
        try {
            final Method onResume = android.app.Activity.class.getDeclaredMethod("onResume");
            hook(onResume).setId("dyhd-act-resume").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    Object ret = chain.proceed();
                    try {
                        Object a = chain.getThisObject();
                        if (a instanceof android.app.Activity) {
                            sCurActivity = (android.app.Activity) a;
                            showFab((android.app.Activity) a);
                        }
                    } catch (Throwable ignored) {}
                    return ret;
                }
            });
            log(4, TAG, "activity tracker installed");
        } catch (Throwable t) { log(5, TAG, "activity tracker fail " + t); }
    }

    /** 播放完成回调：开关打开时上滑到下一个视频 */
    private void hookAutoPlay(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.ss.android.ugc.aweme.feed.play.FeedPlayTimeDispatcher$mPlayCompletedCallback$1", false, cl);
            int n = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!"onChanged".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1) continue;
                hook(m).setId("dyhd-autoplay").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        Object ret = chain.proceed();
                        try {
                            Object arg = chain.getArg(0);
                            if (!isAutoPlayEnabled()) return ret;
                            // 只有真正的播放结束才滑（参数为 true 时更可能是结束态）
                            boolean finished = arg instanceof Boolean ? ((Boolean) arg).booleanValue() : true;
                            if (!finished) return ret;
                            long now = android.os.SystemClock.elapsedRealtime();
                            long gap = now - sLastSwipeTs;
                            if (gap < 2500) {           // 2.5 秒内不重复切，避免连滑
                                log(4, TAG, "playCompleted skipped (throttle " + gap + "ms)");
                                return ret;
                            }
                            sLastSwipeTs = now;
                            log(4, TAG, "playCompleted -> swipe to next (gap " + gap + "ms)");
                            swipeToNext();
                        } catch (Throwable t) { log(5, TAG, "autoplay err " + t); }
                        return ret;
                    }
                });
                n++;
            }
            log(4, TAG, "autoplay hooks=" + n);
        } catch (Throwable t) { log(5, TAG, "autoplay hook fail " + t); }
    }

    /** 在主线程模拟一次上滑 */
    private void swipeToNext() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.app.Activity a = sCurActivity;
                    if (a == null) { log(5, TAG, "autoplay: no activity"); return; }
                    android.view.View root = a.getWindow().getDecorView();
                    int w = root.getWidth();
                    int h = root.getHeight();
                    if (w <= 0 || h <= 0) { log(5, TAG, "autoplay: zero size"); return; }

                    int x = w / 2;
                    int yStart = (int) (h * 0.72f);
                    int yEnd   = (int) (h * 0.28f);
                    long t0 = android.os.SystemClock.uptimeMillis();

                    android.view.MotionEvent d = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, x, yStart, 0);
                    android.view.MotionEvent mv1 = android.view.MotionEvent.obtain(t0, t0 + 60, android.view.MotionEvent.ACTION_MOVE, x, yStart - (yStart - yEnd) / 3, 0);
                    android.view.MotionEvent mv2 = android.view.MotionEvent.obtain(t0, t0 + 120, android.view.MotionEvent.ACTION_MOVE, x, yStart - (yStart - yEnd) * 2 / 3, 0);
                    android.view.MotionEvent mv3 = android.view.MotionEvent.obtain(t0, t0 + 180, android.view.MotionEvent.ACTION_MOVE, x, yEnd, 0);
                    android.view.MotionEvent u = android.view.MotionEvent.obtain(t0, t0 + 200, android.view.MotionEvent.ACTION_UP, x, yEnd, 0);

                    root.dispatchTouchEvent(d);
                    root.dispatchTouchEvent(mv1);
                    root.dispatchTouchEvent(mv2);
                    root.dispatchTouchEvent(mv3);
                    root.dispatchTouchEvent(u);
                    d.recycle(); mv1.recycle(); mv2.recycle(); mv3.recycle(); u.recycle();
                    log(4, TAG, "autoplay: swiped up on " + a.getClass().getSimpleName());
                } catch (Throwable t) { log(5, TAG, "swipe err " + t); }
            }
        }, 80);
    }

    /* ============ 6a) 记录当前 Aweme（用于标题） ============ */

    private void hookAwemeDesc(ClassLoader cl) {
        try {
            Class<?> aw = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, cl);
            int n = 0;
            for (Method m : aw.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                String nm = m.getName();
                // getDesc / getAwemeId 之类，都会被频繁调用
                if (rt != String.class) continue;
                if (!(nm.equals("getDesc") || nm.equals("getAid") || nm.equals("getAwemeId"))) continue;
                final Method mm = m;
                hook(m).setId("dyhd-aweme-" + nm).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        Object ret = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            if (self != null) sLastAweme = self;
                        } catch (Throwable ignored) {}
                        return ret;
                    }
                });
                n++;
            }
            log(4, TAG, "aweme hooks=" + n);
        } catch (Throwable t) { log(5, TAG, "aweme hook fail " + t); }
    }

    /* ============ 6b) 首页悬浮球 ============ */

    private static final String MARK_FAB = "fj_dyhd_fab";

    /** 所有界面都显示悬浮球（只排除启动页），这样任何页面都能下载 */
    private void showFab(final android.app.Activity act) {
        try {
            if (!isEnabled()) { hideFab(); return; }
            if (act == null) return;
            String cn = act.getClass().getSimpleName();
            // 启动页不放，其余全放
            if (cn.contains("Splash")) { hideFab(); return; }

            final android.view.Window win = act.getWindow();
            if (win == null) return;
            final View decor = win.getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            final ViewGroup root = (ViewGroup) decor;

            if (sFab != null && sFabHost == root && sFab.getParent() != null) return;
            hideFab();

            final Context ctx = act;
            float d = ctx.getResources().getDisplayMetrics().density;
            int size = (int) (40 * d);

            // 圆形按钮
            FrameLayout fab = new FrameLayout(ctx);
            fab.setTag(MARK_FAB);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0xE6000000);                       // 近乎不透明的深色底
            bg.setStroke((int) (1.2f * d), 0x59FFFFFF);    // 白色描边提亮
            fab.setBackground(bg);
            fab.setElevation(10 * d);
            fab.setAlpha(1f);

            android.widget.ImageView icon = new android.widget.ImageView(ctx);
            int iconPx = (int) (22 * d);
            try {
                // 必须用模块自己的 Resources，host 的 Resources 找不到本模块资源
                Context mctx = ctx.createPackageContext("com.fj.dyhd",
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                android.content.res.Resources res = mctx.getResources();
                android.graphics.drawable.Drawable d1 = res.getDrawable(
                        res.getIdentifier("ic_fab_douyin", "drawable", "com.fj.dyhd"),
                        mctx.getTheme());
                if (d1 != null) icon.setImageDrawable(d1);
                else icon.setImageResource(android.R.drawable.stat_sys_download);
                log(4, TAG, "fab icon loaded from module res");
            } catch (Throwable t) {
                log(5, TAG, "fab icon load err " + t);
                icon.setImageResource(android.R.drawable.stat_sys_download);
            }
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iclp = new FrameLayout.LayoutParams(iconPx, iconPx);
            iclp.gravity = Gravity.CENTER;
            fab.addView(icon, iclp);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.topMargin = (int) (150 * d);
            lp.rightMargin = (int) (14 * d);
            root.addView(fab, lp);

            // 拖动 + 点击
            fab.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private int startL, startT;
                private boolean moved;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY();
                            FrameLayout.LayoutParams dp = (FrameLayout.LayoutParams) v.getLayoutParams();
                            startL = dp.leftMargin; startT = dp.topMargin;
                            moved = false;
                            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                            if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                            FrameLayout.LayoutParams mp = (FrameLayout.LayoutParams) v.getLayoutParams();
                            mp.gravity = Gravity.TOP | Gravity.START;
                            mp.leftMargin = (int) (startL + dx);
                            mp.topMargin = (int) (startT + dy);
                            v.setLayoutParams(mp);
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL: {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                            if (moved) {
                                // 吸附到左右边缘
                                FrameLayout.LayoutParams up = (FrameLayout.LayoutParams) v.getLayoutParams();
                                int w = root.getWidth();
                                boolean right = up.leftMargin + v.getWidth() / 2 > w / 2;
                                up.leftMargin = right ? (w - v.getWidth() - (int) (10 * d)) : (int) (10 * d);
                                v.setLayoutParams(up);
                            } else {
                                onFabClick(ctx);
                            }
                            return true;
                        }
                    }
                    return false;
                }
            });

            sFab = fab;
            sFabHost = root;
            log(4, TAG, "fab shown on " + cn);
        } catch (Throwable t) { log(5, TAG, "fab err " + t); }
    }

    private void hideFab() {
        try {
            View f = sFab;
            sFab = null; sFabHost = null;
            if (f != null && f.getParent() instanceof ViewGroup) {
                ((ViewGroup) f.getParent()).removeView(f);
            }
        } catch (Throwable ignored) {}
    }

    private void onFabClick(Context ctx) {
        Object video = sLastVideo;
        Object aweme = sLastAweme;
        if (video == null) {
            toast(ctx, "还没取到视频信息，先播一下");
            return;
        }
        if (aweme == null) {
            // 尝试从 video 找 aweme 反向引用
            aweme = findHostAweme(video);
            sLastAweme = aweme;
        }
        try { showQualityDialog(ctx, video, aweme); }
        catch (Throwable t) { log(5, TAG, "fab click err " + t); }
    }

    /** Video 里可能持有 Aweme 引用，找不到就返回 null（标题降级为时间戳） */
    private static Object findHostAweme(Object video) {
        try {
            Class<?> c = video.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().endsWith("feed.model.Aweme")) {
                        f.setAccessible(true);
                        Object v = f.get(video);
                        if (v != null) return v;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ============ 7) 原生菜单项注入 ============ */

    private static volatile int sMenuInjectOk = 0;
    private static volatile int sMenuInjectFails = 0;

    private static final String C_ADDCACHE =
            "com.ss.android.ugc.aweme.feed.long_press_panel.modules.business.homepage.LPPAddCacheVideoModule";

    private void hookNativeMenuItem(ClassLoader cl) {
        try {
            final Class<?> cls = Class.forName(C_ADDCACHE, false, cl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2
                        && ps[0] == android.content.Context.class
                        && ps[1] == android.view.ViewGroup.class
                        && android.view.View.class.isAssignableFrom(m.getReturnType())) {
                    target = m;
                    break;
                }
            }
            if (target == null) { log(5, TAG, "menu: no view method"); return; }

            final Method mk = target;
            hook(mk).setId("dyhd-native-menu").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    final Object ret = chain.proceed();
                    try {
                        if (!(ret instanceof View)) return ret;
                        final Object module = chain.getThisObject();
                        final View anchor = (View) ret;
                        // 等它被加到父容器后再插
                        anchor.post(new Runnable() {
                            @Override public void run() {
                                try { insertMenuItem(anchor, module); }
                                catch (Throwable t) { log(5, TAG, "menu insert err " + t); }
                            }
                        });
                    } catch (Throwable t) { log(5, TAG, "menu hook err " + t); }
                    return ret;
                }
            });
            log(4, TAG, "native menu hook installed on " + mk.getName());
        } catch (Throwable t) { log(5, TAG, "native menu hook fail " + t); }
    }

    /** 在 anchor 所在的父容器里，紧挨着 anchor 插入一个「高画质下载」项 */
    private void insertMenuItem(View anchor, Object module) {
        if (!(anchor.getParent() instanceof ViewGroup)) {
            log(5, TAG, "menu: anchor has no ViewGroup parent");
            return;
        }
        final ViewGroup parent = (ViewGroup) anchor.getParent();
        final String mark = "fj_dyhd_menu_item";
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != null && mark.equals(c.getTag())) return;
        }

        // 从模块里拿 Aweme
        Object params = getFieldByName(module, "e");
        final Object aweme = params == null ? null : getFieldByName(params, "a");
        final Object video = getVideo(aweme);
        if (video == null) { log(5, TAG, "menu: no video"); return; }

        final Context ctx = anchor.getContext();

        // 复制 anchor 的结构：若是容器则逐个复制子 View 的样式，否则直接造 TextView
        LinearLayout item = new LinearLayout(ctx);
        item.setTag(mark);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        if (anchor instanceof View) {
            item.setPadding(anchor.getPaddingLeft(), anchor.getPaddingTop(),
                    anchor.getPaddingRight(), anchor.getPaddingBottom());
        }
        item.setClickable(true);
        item.setFocusable(true);

        // 图标：用一个箭头样式的 TextView 占位，尺寸跟随 anchor
        TextView icon = new TextView(ctx);
        icon.setText("\u2193");
        icon.setTextSize(17f);
        icon.setTextColor(0xFFF2F4F8);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
        item.addView(icon, ilp);

        TextView label = new TextView(ctx);
        label.setText("高画质下载");
        label.setTextSize(15f);
        label.setTextColor(0xFFF2F4F8);
        item.addView(label);

        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { showQualityDialog(ctx, video, aweme); }
                catch (Throwable t) { log(5, TAG, "menu click err " + t); }
            }
        });

        // RecyclerView 不允许直接 addView，直接放弃（交给浮窗兜底）
        if (parent.getClass().getName().contains("RecyclerView")) {
            log(4, TAG, "menu: parent is RecyclerView, skip");
            sMenuInjectFails++;
            return;
        }

        int idx = parent.indexOfChild(anchor);
        if (idx < 0) idx = parent.getChildCount();

        // 必须拷贝 LayoutParams，不能复用 anchor 的实例
        ViewGroup.LayoutParams src = anchor.getLayoutParams();
        ViewGroup.LayoutParams dst;
        if (src instanceof LinearLayout.LayoutParams) {
            dst = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src);
        } else if (src != null) {
            dst = new ViewGroup.LayoutParams(src.width, src.height);
        } else {
            dst = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        try {
            parent.addView(item, idx, dst);
            sMenuInjectOk++;
            log(4, TAG, "native menu item inserted at " + idx + " of "
                    + parent.getClass().getSimpleName() + " (children=" + parent.getChildCount() + ")");
        } catch (Throwable t) {
            sMenuInjectFails++;
            log(5, TAG, "menu addView fail " + t);
        }
    }

    /* ============ 工具 ============ */

    private static List<?> findBitRateList(Object video) {
        if (video == null) return null;
        List<?> best = null;
        int bestSize = 0;
        for (Field f : video.getClass().getDeclaredFields()) {
            if (f.getType() != List.class) continue;
            Object v;
            try { f.setAccessible(true); v = f.get(video); } catch (Throwable t) { continue; }
            if (!(v instanceof List)) continue;
            List<?> l = (List<?>) v;
            if (l.isEmpty()) continue;
            Object first = l.get(0);
            if (first == null || !first.getClass().getName().endsWith(".BitRate")) continue;
            if (l.size() > bestSize) { bestSize = l.size(); best = l; }
        }
        return best;
    }

    private static Object bestAddr(Object video) {
        List<?> l = findBitRateList(video);
        if (l == null) return null;
        Object best = null; int bestBr = Integer.MIN_VALUE;
        for (Object it : l) {
            if (it == null) continue;
            Object addr = field(it, "playAddr");
            if (!hasUrl(addr)) continue;
            int br = intField(it, "bitRate");
            if (br > bestBr) { bestBr = br; best = addr; }
        }
        return best;
    }

    private static Object findPlayAddrField(Object video) {
        if (video == null) return null;
        Object fb = null;
        for (Field f : video.getClass().getDeclaredFields()) {
            String tn = f.getType().getName();
            if (!tn.endsWith("VideoUrlModel")) continue;
            Object v;
            try { f.setAccessible(true); v = f.get(video); } catch (Throwable t) { continue; }
            if (!hasUrl(v)) continue;
            if (f.getName().toLowerCase().contains("playaddr")) return v;
            if (fb == null) fb = v;
        }
        return fb;
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("#[^#\\s]+", " ");
        s = s.replaceAll("@[^@\\s]+", " ");
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        if (s.length() > 40) s = s.substring(0, 40).trim();
        return s;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Throwable t) { return 0; }
    }

    private static Object field(Object o, String n) {
        if (o == null) return null;
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
        catch (Throwable t) { return null; }
    }

    private static int intField(Object o, String n) {
        Object v = field(o, n);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static String strField(Object o, String n) {
        Object v = field(o, n);
        return v instanceof String ? (String) v : null;
    }

    private static boolean hasUrl(Object urlModel) {
        return firstUrl(urlModel) != null;
    }

    private static String firstUrl(Object urlModel) {
        if (urlModel == null) return null;
        try {
            Object l = urlModel.getClass().getMethod("getUrlList").invoke(urlModel);
            if (l instanceof List) {
                List<?> ll = (List<?>) l;
                if (!ll.isEmpty() && ll.get(0) instanceof String) return (String) ll.get(0);
            }
        } catch (Throwable ignored) {}
        Object l = field(urlModel, "urlList");
        if (l instanceof List) {
            List<?> ll = (List<?>) l;
            if (!ll.isEmpty() && ll.get(0) instanceof String) return (String) ll.get(0);
        }
        return null;
    }

    private boolean prefOn(String key, boolean def) {
        try {
            android.content.SharedPreferences p = getRemotePreferences(PREF);
            if (p != null) return p.getBoolean(key, def);
        } catch (Throwable ignored) {}
        return def;
    }

    private boolean isEmojiEnabled() { return prefOn("emoji", true); }
    private boolean isAutoPlayEnabled() { return prefOn("autoPlay", true); }

    private boolean isEnabled() {
        try {
            android.content.SharedPreferences p = getRemotePreferences(PREF);
            if (p != null) return p.getBoolean("enabled", true);
        } catch (Throwable ignored) {}
        return true;
    }
}