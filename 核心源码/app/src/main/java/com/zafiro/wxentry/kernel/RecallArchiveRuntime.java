package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent anti-recall archive & real-time notification system.
 * Records text and media recalled by peers and supports exporting recovered media to gallery.
 */
final class RecallArchiveRuntime {
    private static final int MAX_ENTRIES = 300;
    private static final String CHANNEL_ID = "wxentry_recall_alert";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object FILE_LOCK = new Object();
    private static final ConcurrentHashMap<String, Long> DEDUP = new ConcurrentHashMap<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private RecallArchiveRuntime() {}

    static File archiveFile() {
        return new File(Kernel.WXDIR + "recall.archive.json");
    }

    static File notifyFlagFile() {
        return new File(Kernel.WXDIR + "recall.notify.on");
    }

    static boolean notifyEnabled() {
        return !Kernel.MODULE_OFF && notifyFlagFile().exists();
    }

    static boolean setNotifyEnabled(boolean on) {
        try {
            File f = notifyFlagFile();
            if (on) {
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                if (!f.exists()) f.createNewFile();
            } else if (f.exists()) {
                f.delete();
            }
            return notifyFlagFile().exists();
        } catch (Throwable t) {
            return notifyFlagFile().exists();
        }
    }

    static void recordFromDb(final Object db, final long msgId) {
        if (db == null || msgId <= 0 || Kernel.MODULE_OFF) return;
        final String key = "mid|" + msgId;
        long now = System.currentTimeMillis();
        Long prev = DEDUP.putIfAbsent(key, Long.valueOf(now));
        if (prev != null && now - prev.longValue() < 30_000L) return;
        if (DEDUP.size() > 512) DEDUP.clear();

        Cursor c = null;
        try {
            c = Kernel.rawQuery(db,
                    "select type, isSend, talker, content, imgPath, createTime from message where msgId=?",
                    new String[]{String.valueOf(msgId)});
            if (c == null || !c.moveToFirst()) return;
            int type = c.getInt(0);
            int isSend = c.getInt(1);
            if (isSend == 1) return; // Only record peer recalls
            String talker = c.getString(2);
            String content = c.getString(3);
            String imgPath = c.getString(4);
            long created = c.getLong(5);
            recordAsync(db, talker, type, content, imgPath, created);
        } catch (Throwable t) {
            KernelLog.debug("RecallArchive recordFromDb err " + t);
        } finally {
            if (c != null) c.close();
        }
    }

    static void recordAsync(final Object db, final String talker, final int rawType,
                            final String rawContent, final String imgPath, final long createdMs) {
        IO.execute(() -> {
            try {
                int low = rawType & 0xffff;
                String talkerName = resolveDisplayName(db, talker);
                String senderName = "";
                String body = rawContent == null ? "" : rawContent;
                if (talker != null && talker.endsWith("@chatroom")) {
                    int colon = body.indexOf(":\n");
                    if (colon > 0 && colon < 96) {
                        String wxid = body.substring(0, colon).trim();
                        senderName = resolveDisplayName(db, wxid);
                        if (senderName.isEmpty()) senderName = wxid;
                        body = body.substring(colon + 2);
                    }
                }
                String typeLabel;
                String summary;
                switch (low) {
                    case 1:
                        typeLabel = "文本";
                        summary = body;
                        break;
                    case 3:
                        typeLabel = "图片";
                        summary = "[图片]";
                        break;
                    case 34:
                        typeLabel = "语音";
                        summary = "[语音]";
                        break;
                    case 43:
                    case 62:
                        typeLabel = "视频";
                        summary = "[视频]";
                        break;
                    case 47:
                        typeLabel = "表情";
                        summary = "[动画表情]";
                        break;
                    case 49:
                        typeLabel = "引用/卡片";
                        String title = TransferRuntime.xmlTag(body, "title");
                        summary = title.isEmpty() ? "[应用/引用消息]" : title;
                        break;
                    default:
                        typeLabel = "消息(" + low + ")";
                        summary = body.length() > 160 ? body.substring(0, 160) + "..." : body;
                        break;
                }

                String savedMedia = copyMediaCandidate(imgPath, low);
                JSONObject item = new JSONObject();
                item.put("ts", System.currentTimeMillis());
                item.put("msgTs", createdMs);
                item.put("talker", talker == null ? "" : talker);
                item.put("talkerName", talkerName.isEmpty() ? (talker == null ? "未知会话" : talker) : talkerName);
                item.put("sender", senderName);
                item.put("type", typeLabel);
                item.put("summary", summary);
                item.put("media", savedMedia == null ? "" : savedMedia);

                appendEntry(item);
                if (notifyEnabled()) {
                    postRecallAlert(item);
                }
            } catch (Throwable t) {
                KernelLog.debug("RecallArchive async err " + t);
            }
        });
    }

    private static String copyMediaCandidate(String imgPath, int lowType) {
        if (imgPath == null || imgPath.isEmpty()) return null;
        File src = resolveHostMediaFile(imgPath, lowType);
        if (src == null || !src.isFile() || src.length() <= 0 || src.length() > 30L * 1024 * 1024) {
            return null;
        }
        File dir = new File(Kernel.WXDIR + "recall-archive");
        if (!dir.exists()) dir.mkdirs();
        String ext = lowType == 43 || lowType == 62 ? ".mp4" : (lowType == 3 ? ".jpg" : "");
        String name = System.currentTimeMillis() + "_" + src.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!ext.isEmpty() && !name.contains(".")) name += ext;
        File dst = new File(dir, name);
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private static File resolveHostMediaFile(String imgPath, int lowType) {
        File direct = new File(imgPath);
        if (direct.isFile()) return direct;
        // Look inside recall cache folder first if MediaRecallRuntime already saved it
        File recallDir = new File(Kernel.WXDIR + "recall");
        if (recallDir.isDirectory()) {
            String base = new File(imgPath).getName();
            File[] fs = recallDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().contains(base)) return f;
                }
            }
        }
        return null;
    }

    private static void appendEntry(JSONObject entry) {
        synchronized (FILE_LOCK) {
            JSONArray old = readArray();
            JSONArray next = new JSONArray();
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < MAX_ENTRIES; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null) next.put(o);
            }
            ModuleState.write(archiveFile().getAbsolutePath(), next.toString());
        }
    }

    private static JSONArray readArray() {
        try {
            String raw = ModuleState.read(archiveFile().getAbsolutePath());
            if (raw == null || raw.trim().isEmpty()) return new JSONArray();
            return new JSONArray(raw);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static void postRecallAlert(JSONObject item) {
        final String who = item.optString("sender").isEmpty()
                ? item.optString("talkerName")
                : item.optString("talkerName") + " · " + item.optString("sender");
        final String msg = "[" + item.optString("type") + "] " + item.optString("summary");
        Kernel.toastMain("防撤回：" + who + " 撤回了" + item.optString("type"));

        Context ctx = Kernel.APP_CTX;
        if (ctx == null) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "防撤回提醒", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            int icon = ctx.getApplicationInfo().icon != 0 ? ctx.getApplicationInfo().icon : android.R.drawable.ic_dialog_info;
            b.setSmallIcon(icon)
                    .setContentTitle("已拦截撤回 · " + who)
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Throwable ignored) {}
    }

    static String resolveDisplayName(Object db, String username) {
        if (username == null || username.isEmpty()) return "";
        Object qdb = db != null ? db : safeDb();
        if (qdb == null) return "";
        Cursor c = null;
        try {
            c = Kernel.rawQuery(qdb, "select conRemark, nickname from rcontact where username=?", new String[]{username});
            if (c != null && c.moveToFirst()) {
                String remark = c.getString(0);
                String nick = c.getString(1);
                if (remark != null && !remark.trim().isEmpty()) return remark.trim();
                if (nick != null && !nick.trim().isEmpty()) return nick.trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "";
    }

    private static Object safeDb() {
        try { return Kernel.db0(); } catch (Throwable t) { return null; }
    }

    static void showArchive(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        IO.execute(() -> {
            final JSONArray list;
            synchronized (FILE_LOCK) {
                list = readArray();
            }
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (list.length() == 0) {
                    Toast.makeText(act, "暂无防撤回历史记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] titles = new String[list.length()];
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    String when;
                    synchronized (FMT) { when = FMT.format(new Date(o.optLong("ts"))); }
                    String who = o.optString("sender").isEmpty()
                            ? o.optString("talkerName")
                            : o.optString("talkerName") + " / " + o.optString("sender");
                    String summary = o.optString("summary");
                    if (summary.length() > 36) summary = summary.substring(0, 36) + "…";
                    titles[i] = "[" + o.optString("type") + "] " + who + "\n" + when + " · " + summary;
                }
                new AlertDialog.Builder(act)
                        .setTitle("防撤回记录（" + list.length() + " 条）")
                        .setItems(titles, (d, which) -> showDetail(act, list.optJSONObject(which)))
                        .setNeutralButton("清空全部", (d, w) -> {
                            synchronized (FILE_LOCK) {
                                archiveFile().delete();
                                File dir = new File(Kernel.WXDIR + "recall-archive");
                                if (dir.isDirectory()) {
                                    File[] fs = dir.listFiles();
                                    if (fs != null) for (File f : fs) f.delete();
                                }
                            }
                            Toast.makeText(act, "已清空防撤回记录", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        });
    }

    private static void showDetail(final Activity act, final JSONObject item) {
        if (item == null) return;
        LinearLayout box = SettingsUi.column(act);
        String who = item.optString("sender").isEmpty()
                ? item.optString("talkerName")
                : item.optString("talkerName") + " · " + item.optString("sender");
        String when;
        synchronized (FMT) { when = FMT.format(new Date(item.optLong("ts"))); }
        box.addView(SettingsUi.text(act, who + "\n" + when + " · 类型：" + item.optString("type"), 12));
        TextView body = SettingsUi.text(act, item.optString("summary"), 15);
        body.setTextIsSelectable(true);
        body.setPadding(0, SettingsUi.dp(act, 10), 0, SettingsUi.dp(act, 8));
        box.addView(body);

        final String mediaPath = item.optString("media");
        final File mediaFile = mediaPath.isEmpty() ? null : new File(mediaPath);
        if (mediaFile != null && mediaFile.isFile() && item.optString("type").contains("图")) {
            try {
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(mediaFile.getAbsolutePath());
                if (bm != null) {
                    ImageView iv = new ImageView(act);
                    iv.setImageBitmap(bm);
                    iv.setAdjustViewBounds(true);
                    box.addView(iv);
                }
            } catch (Throwable ignored) {}
        }
        ScrollView scroll = new ScrollView(act);
        scroll.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle("撤回消息详情")
                .setView(scroll)
                .setPositiveButton("复制内容", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("recalled", item.optString("summary")));
                        Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null);
        if (mediaFile != null && mediaFile.isFile()) {
            b.setNeutralButton("保存到相册", (d, w) -> exportMediaToGallery(act, mediaFile));
        }
        b.show();
    }

    private static void exportMediaToGallery(final Activity act, final File src) {
        IO.execute(() -> {
            boolean ok = false;
            try {
                boolean isVideo = src.getName().endsWith(".mp4");
                ContentResolver cr = act.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "WXRecall_" + src.getName());
                cv.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                if (Build.VERSION.SDK_INT >= 29) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/WechatXposed");
                }
                Uri uri = cr.insert(isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    FileInputStream in = new FileInputStream(src);
                    OutputStream out = cr.openOutputStream(uri);
                    if (out != null) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        out.close();
                        ok = true;
                    }
                    in.close();
                }
            } catch (Throwable t) {
                KernelLog.stack("RecallArchive.export", t);
            }
            final boolean res = ok;
            act.runOnUiThread(() -> Toast.makeText(act,
                    res ? "已保存到相册/WechatXposed" : "保存失败，请检查媒体权限", Toast.LENGTH_SHORT).show());
        });
    }
}
