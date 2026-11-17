package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XposedBridge;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Centralized module logging with automatic log rotation, debug gating, and in-app viewer. */
final class KernelLog {
    private static final long MAX_LOG_BYTES = 512L * 1024L;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile long lastRotateCheck;
    private static volatile Boolean cachedDebug;
    private static volatile long cachedDebugAt;

    private KernelLog() {}

    static File logFile() {
        return new File(Kernel.WXDIR + "trace.log");
    }

    static File debugFlagFile() {
        return new File(Kernel.WXDIR + "debug.log.on");
    }

    static boolean isDebugEnabled() {
        long now = System.currentTimeMillis();
        Boolean cached = cachedDebug;
        if (cached != null && now - cachedDebugAt < 3000L) {
            return cached.booleanValue();
        }
        boolean on = debugFlagFile().exists();
        cachedDebug = Boolean.valueOf(on);
        cachedDebugAt = now;
        return on;
    }

    static boolean setDebugEnabled(boolean on) {
        try {
            File f = debugFlagFile();
            if (on) {
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                if (!f.exists()) f.createNewFile();
            } else if (f.exists()) {
                f.delete();
            }
            cachedDebug = Boolean.valueOf(on);
            cachedDebugAt = System.currentTimeMillis();
            info("debug logging " + (on ? "enabled" : "disabled"));
            return isDebugEnabled();
        } catch (Throwable t) {
            return isDebugEnabled();
        }
    }

    static void debug(String msg) {
        if (isDebugEnabled()) info(msg);
    }

    static void info(String msg) {
        if (msg == null) return;
        try {
            File f = logFile();
            synchronized (LOCK) {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                long now = System.currentTimeMillis();
                if (now - lastRotateCheck > 15000L) {
                    lastRotateCheck = now;
                    rotateIfNeeded(f);
                }
                String stamp;
                synchronized (TIME_FMT) {
                    stamp = TIME_FMT.format(new Date(now));
                }
                FileWriter w = new FileWriter(f, true);
                w.write(stamp + "  " + msg + "\n");
                w.close();
            }
        } catch (Throwable ignored) {}
        try {
            XposedBridge.log("WXT: " + msg);
        } catch (Throwable ignored) {}
    }

    static void stack(String tag, Throwable e) {
        try {
            info(tag + " " + e);
            Throwable c = e;
            int depth = 0;
            while (c != null && depth < 4) {
                StringWriter sw = new StringWriter();
                c.printStackTrace(new PrintWriter(sw));
                String[] lines = sw.toString().split("\n");
                int n = Math.min(lines.length, 8);
                for (int i = 0; i < n; i++) {
                    info("  " + (i == 0 ? "C" + depth + ": " : "    ") + lines[i].trim());
                }
                c = c.getCause();
                depth++;
                if (c != null) info("  caused by:");
            }
        } catch (Throwable ignored) {}
    }

    private static void rotateIfNeeded(File f) {
        try {
            if (!f.exists() || f.length() <= MAX_LOG_BYTES) return;
            File bak = new File(f.getParentFile(), "trace.log.bak");
            if (bak.exists()) bak.delete();
            if (!f.renameTo(bak)) {
                byte[] tail = tailBytes(f, (int) (MAX_LOG_BYTES / 2));
                FileOutputStream out = new FileOutputStream(f, false);
                out.write(tail);
                out.close();
            }
        } catch (Throwable ignored) {}
    }

    static byte[] tailBytes(File f, int maxBytes) {
        if (!f.exists() || !f.isFile()) return new byte[0];
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0L, len - maxBytes);
            raf.seek(start);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) (len - start));
            byte[] buf = new byte[8192];
            int r;
            while ((r = raf.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    static String readTail(int maxBytes) {
        try {
            File f = logFile();
            if (!f.exists()) return "（暂无运行日志）";
            byte[] data;
            synchronized (LOCK) {
                data = tailBytes(f, maxBytes);
            }
            String text = new String(data, "UTF-8");
            if (f.length() > maxBytes) {
                int nl = text.indexOf('\n');
                if (nl >= 0 && nl + 1 < text.length()) text = text.substring(nl + 1);
            }
            return text.isEmpty() ? "（日志为空）" : text;
        } catch (Throwable t) {
            return "读取日志失败: " + t.getMessage();
        }
    }

    static boolean clear() {
        synchronized (LOCK) {
            boolean ok = true;
            try {
                File f = logFile();
                if (f.exists()) ok = f.delete();
                File bak = new File(Kernel.WXDIR + "trace.log.bak");
                if (bak.exists()) bak.delete();
            } catch (Throwable t) {
                ok = false;
            }
            return ok;
        }
    }

    static void showViewer(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        new Thread(() -> {
            final File f = logFile();
            final long sizeKb = f.exists() ? (f.length() + 1023) / 1024 : 0;
            final String content = readTail(48 * 1024);
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                final TextView tv = SettingsUi.text(act, content, 11);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                int pad = SettingsUi.dp(act, 14);
                tv.setPadding(pad, pad, pad, pad);
                final ScrollView scroll = new ScrollView(act);
                scroll.addView(tv);
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));

                new AlertDialog.Builder(act)
                        .setTitle("运行日志（trace.log · " + sizeKb + " KB）")
                        .setView(scroll)
                        .setPositiveButton("复制末尾", (d, w) -> {
                            try {
                                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("trace.log", content));
                                    Toast.makeText(act, "已复制日志到剪贴板", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable t) {
                                Toast.makeText(act, "复制失败", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton("清空日志", (d, w) -> {
                            clear();
                            Toast.makeText(act, "运行日志已清空", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        }, "wxentry-log-view").start();
    }
}
