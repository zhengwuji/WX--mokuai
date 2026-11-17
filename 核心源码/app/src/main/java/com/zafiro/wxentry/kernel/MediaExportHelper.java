package com.zafiro.wxentry.kernel;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared Scoped Storage / MediaStore export helper for Android 10+ and legacy external storage.
 * Avoids EACCES errors on Android 11-14 when writing to Pictures/Movies/Download/WechatXposed.
 */
final class MediaExportHelper {
    enum Kind {
        IMAGE(Environment.DIRECTORY_PICTURES),
        VIDEO(Environment.DIRECTORY_MOVIES),
        DOWNLOAD(Environment.DIRECTORY_DOWNLOADS);

        final String baseDir;
        Kind(String dir) { baseDir = dir; }
    }

    private MediaExportHelper() {}

    static String saveFile(Context ctx, File src, Kind kind, String fileName, String mimeType) throws Exception {
        if (ctx == null || src == null || !src.exists()) {
            throw new IllegalArgumentException("Source file missing");
        }
        FileInputStream in = new FileInputStream(src);
        try {
            return saveStream(ctx, in, kind, fileName, mimeType);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    static String saveBytes(Context ctx, byte[] data, Kind kind, String fileName, String mimeType) throws Exception {
        if (ctx == null || data == null) {
            throw new IllegalArgumentException("Data missing");
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection = collectionUri(kind);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, kind.baseDir + "/WechatXposed");
            Uri uri = cr.insert(collection, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) throw new IllegalStateException("MediaStore output stream null");
            try {
                out.write(data);
                out.flush();
            } finally {
                out.close();
            }
            return kind.baseDir + "/WechatXposed/" + fileName;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(kind.baseDir), "WechatXposed");
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, fileName);
            FileOutputStream out = new FileOutputStream(dst);
            try {
                out.write(data);
                out.flush();
            } finally {
                out.close();
            }
            notifyScanner(ctx, dst);
            return dst.getAbsolutePath();
        }
    }

    static String saveStream(Context ctx, InputStream in, Kind kind, String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection = collectionUri(kind);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, kind.baseDir + "/WechatXposed");
            Uri uri = cr.insert(collection, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) throw new IllegalStateException("MediaStore output stream null");
            try {
                pump(in, out);
            } finally {
                out.close();
            }
            return kind.baseDir + "/WechatXposed/" + fileName;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(kind.baseDir), "WechatXposed");
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, fileName);
            FileOutputStream out = new FileOutputStream(dst);
            try {
                pump(in, out);
            } finally {
                out.close();
            }
            notifyScanner(ctx, dst);
            return dst.getAbsolutePath();
        }
    }

    static File findLatestFile(File dir, long minSizeBytes, String... nameHints) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        File latest = null;
        long latestTime = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                File sub = findLatestFile(f, minSizeBytes, nameHints);
                if (sub != null && sub.lastModified() > latestTime) {
                    latest = sub;
                    latestTime = sub.lastModified();
                }
            } else if (f.isFile() && f.length() >= minSizeBytes) {
                String n = f.getName().toLowerCase();
                boolean match = nameHints == null || nameHints.length == 0;
                if (!match) {
                    for (String hint : nameHints) {
                        if (n.contains(hint)) {
                            match = true;
                            break;
                        }
                    }
                }
                if (match && f.lastModified() > latestTime) {
                    latest = f;
                    latestTime = f.lastModified();
                }
            }
        }
        return latest;
    }

    private static Uri collectionUri(Kind kind) {
        if (kind == Kind.IMAGE) return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        if (kind == Kind.VIDEO) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if (Build.VERSION.SDK_INT >= 29) return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        return MediaStore.Files.getContentUri("external");
    }

    private static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static void notifyScanner(Context ctx, File dst) {
        try {
            ctx.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)));
        } catch (Throwable ignored) {}
    }
}
