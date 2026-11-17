package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.EditText;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * Exports and restores all module settings and rules inside WXDIR as a portable JSON bundle.
 * Also answers live bridge queries from MainActivity via ResultReceiver Binder IPC.
 */
final class BackupManager {
    private static final String EXPORT_PREFIX = "WechatXposed_Backup_";

    private BackupManager() {}

    private static boolean shouldIncludeFile(File f) {
        if (f == null || !f.isFile()) return false;
        if (f.length() <= 0 || f.length() > 512L * 1024L) return false;
        String name = f.getName();
        if (name.startsWith("trace.log") || name.startsWith("dex.")
                || name.equals("kernel.ver") || name.equals("boot.log")
                || name.equals("realname.cache") || name.endsWith(".tmp")) {
            return false;
        }
        return true;
    }

    static String exportBackupJson() {
        try {
            File dir = new File(Kernel.WXDIR);
            JSONObject root = new JSONObject();
            root.put("format", 1);
            root.put("version", "2.30");
            root.put("build", Kernel.BUILD);
            root.put("exportedAt", System.currentTimeMillis());
            JSONObject files = new JSONObject();
            File[] children = dir.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (!shouldIncludeFile(f)) continue;
                    byte[] bytes = readBytes(f);
                    if (bytes != null) {
                        files.put(f.getName(), Base64.encodeToString(bytes, Base64.NO_WRAP));
                    }
                }
            }
            root.put("files", files);
            return root.toString();
        } catch (Throwable t) {
            KernelLog.stack("BackupManager.export", t);
            return null;
        }
    }

    static int importBackupJson(String json) {
        if (json == null || json.trim().isEmpty()) return -1;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("format", 0) != 1) return -1;
            JSONObject files = root.optJSONObject("files");
            if (files == null) return -1;
            File dir = new File(Kernel.WXDIR);
            if (!dir.exists()) dir.mkdirs();

            int restored = 0;
            Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                    continue;
                }
                String b64 = files.optString(name, "");
                if (b64.isEmpty()) continue;
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                File dst = new File(dir, name);
                File tmp = new File(dir, name + ".restore.tmp");
                FileOutputStream out = new FileOutputStream(tmp, false);
                out.write(data);
                out.close();
                if (dst.exists()) dst.delete();
                if (tmp.renameTo(dst)) restored++;
            }
            Kernel.FLAGS_AT = 0;
            Kernel.T("BACKUP restored files=" + restored);
            return restored;
        } catch (Throwable t) {
            KernelLog.stack("BackupManager.import", t);
            return -1;
        }
    }

    private static byte[] readBytes(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    static void handleBridgeCommand(final Activity act, android.content.Intent intent) {
        if (intent == null || !intent.hasExtra("bridge_cmd")) return;
        final String cmd = intent.getStringExtra("bridge_cmd");
        final ResultReceiver rr = intent.getParcelableExtra("bridge_reply");
        new Thread(() -> {
            Bundle res = new Bundle();
            try {
                if ("pull_state".equals(cmd)) {
                    Map<String, String> diag = DexResolver.collectDiagnostics(Kernel.APP_CL);
                    StringBuilder diagText = new StringBuilder();
                    for (Map.Entry<String, String> e : diag.entrySet()) {
                        diagText.append(e.getKey()).append("：").append(e.getValue()).append('\n');
                    }
                    res.putString("diag", diagText.toString().trim());
                    res.putString("trace", KernelLog.readTail(24 * 1024));
                    String backup = exportBackupJson();
                    if (backup != null && backup.length() < 256 * 1024) {
                        res.putString("backup", backup);
                    }
                    res.putString("kernel", Kernel.BUILD);
                    res.putBoolean("debugLog", KernelLog.isDebugEnabled());
                    if (rr != null) rr.send(1, res);
                } else if ("apply_backup".equals(cmd)) {
                    int n = importBackupJson(intent.getStringExtra("backup_json"));
                    res.putInt("restored", n);
                    if (rr != null) rr.send(2, res);
                } else if ("clear_log".equals(cmd)) {
                    res.putBoolean("cleared", KernelLog.clear());
                    if (rr != null) rr.send(3, res);
                }
            } catch (Throwable t) {
                res.putString("error", String.valueOf(t));
                if (rr != null) rr.send(-1, res);
            }
        }, "wxentry-bridge").start();
    }

    static void showDiagnosticsDialog(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        new Thread(() -> {
            Map<String, String> map = DexResolver.collectDiagnostics(Kernel.APP_CL);
            final StringBuilder sb = new StringBuilder();
            sb.append("内核版本：v").append(Kernel.BUILD).append("（完整版）\n");
            sb.append("调试日志：").append(KernelLog.isDebugEnabled() ? "开启" : "关闭").append("\n\n");
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append("• ").append(e.getKey()).append("：").append(e.getValue()).append('\n');
            }
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                new AlertDialog.Builder(act)
                        .setTitle("本地模块自检诊断")
                        .setMessage(sb.toString().trim())
                        .setPositiveButton("查看运行日志", (d, w) -> KernelLog.showViewer(act))
                        .setNeutralButton("复制报告", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("wxentry-diag", sb.toString()));
                                Toast.makeText(act, "已复制自检报告", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        }, "wxentry-diag").start();
    }

    static void showBackupDialog(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        String[] actions = {
                "导出配置备份到手机 Download 目录",
                "复制配置备份 JSON 到剪贴板",
                "从剪贴板 / 粘贴 JSON 恢复配置"
        };
        new AlertDialog.Builder(act)
                .setTitle("配置备份与恢复")
                .setItems(actions, (d, which) -> {
                    if (which == 0) exportToDownloads(act);
                    else if (which == 1) copyBackupToClipboard(act);
                    else promptImportJson(act);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void exportToDownloads(final Activity act) {
        new Thread(() -> {
            String json = exportBackupJson();
            String savedName = null;
            if (json != null) {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = EXPORT_PREFIX + ts + ".json";
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentResolver cr = act.getContentResolver();
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WechatXposed");
                        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (uri != null) {
                            OutputStream out = cr.openOutputStream(uri);
                            if (out != null) {
                                out.write(json.getBytes("UTF-8"));
                                out.close();
                                savedName = "Download/WechatXposed/" + fileName;
                            }
                        }
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WechatXposed");
                        if (!dir.exists()) dir.mkdirs();
                        File f = new File(dir, fileName);
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(json.getBytes("UTF-8"));
                        out.close();
                        savedName = f.getAbsolutePath();
                    }
                } catch (Throwable t) {
                    KernelLog.stack("BackupManager.downloads", t);
                }
            }
            final String outPath = savedName;
            act.runOnUiThread(() -> Toast.makeText(act,
                    outPath != null ? "已备份到：" + outPath : "导出失败（可在模块桌面 App 使用系统文件选择器导出）",
                    Toast.LENGTH_LONG).show());
        }, "wxentry-export").start();
    }

    private static void copyBackupToClipboard(final Activity act) {
        new Thread(() -> {
            final String json = exportBackupJson();
            act.runOnUiThread(() -> {
                if (json == null) {
                    Toast.makeText(act, "生成备份失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("wxentry_backup", json));
                    Toast.makeText(act, "已复制备份 JSON（" + (json.length() / 1024 + 1) + " KB）", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static void promptImportJson(final Activity act) {
        final EditText et = new EditText(act);
        et.setHint("在此粘贴之前导出的备份 JSON");
        et.setMinLines(4);
        et.setMaxLines(8);
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
                    String clip = cd.getItemAt(0).getText().toString().trim();
                    if (clip.startsWith("{") && clip.contains("\"files\"")) {
                        et.setText(clip);
                    }
                }
            }
        } catch (Throwable ignored) {}

        new AlertDialog.Builder(act)
                .setTitle("从 JSON 恢复配置")
                .setView(et)
                .setPositiveButton("恢复", (d, w) -> {
                    int n = importBackupJson(et.getText().toString().trim());
                    Toast.makeText(act, n >= 0 ? "已恢复 " + n + " 项配置，建议重启微信生效" : "备份格式无效，恢复失败",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
