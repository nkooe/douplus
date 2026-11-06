package com.fj.dyhd;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 让宿主进程（抖音）读到模块的配置。
 * SELinux 会拦 app_data_file 的跨 uid 读取，chmod 无效，只能走 provider。
 */
public class PrefProvider extends ContentProvider {

    public static final String AUTHORITY = "com.fj.dyhd.pref";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/pref");
    public static final String PREF = "dyhd";

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"key", "type", "value"});
        try {
            Context ctx = getContext();
            if (ctx == null) return c;
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                String type;
                if (v instanceof Boolean) type = "boolean";
                else if (v instanceof Long) type = "long";
                else if (v instanceof Integer) type = "int";
                else if (v instanceof Float) type = "float";
                else type = "string";
                c.addRow(new Object[]{e.getKey(), type, String.valueOf(v)});
            }
        } catch (Throwable ignored) {}
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.dyhd.pref"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}