package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.database.Cursor;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * Group management utilities:
 * 1) Group member join/leave snapshot tracking with local chat tip insertion
 * 2) Nudge/Pat (`拍一拍`) message suppression
 * 3) Quick `@所有人` announcement sender
 */
final class GroupGuardRuntime {
    private static final String CONFIG_FILE = "groupguard.config.json";
    private static volatile ClassLoader appCl;
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Config {
        boolean memberTrackEnabled;
        boolean patBlockEnabled;
    }

    private GroupGuardRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        appCl = cl;
        if (started) return;
        MessageInsertBus.addBeforeInsertFilter((db, table, cv) -> {
            if (!"message".equals(table) || Kernel.MODULE_OFF || cv == null) return false;
            Config c = load();
            if (!c.patBlockEnabled) return false;
            return isPatMessage(cv);
        });
        MessageInsertBus.addUpdateListener((db, table, cv, where, whereArgs) -> {
            if (Kernel.MODULE_OFF || !"chatroom".equals(table) || cv == null) return;
            Config c = load();
            if (!c.memberTrackEnabled) return;
            onChatroomUpdated(db, cv, where, whereArgs);
        });
        started = true;
        Kernel.T("GROUPGUARD ready");
    }

    private static boolean isPatMessage(ContentValues cv) {
        Integer typeObj = cv.getAsInteger("type");
        if (typeObj == null) return false;
        int type = typeObj.intValue();
        int low = type & 0xffff;
        if (type == 922746929) return true;
        String content = cv.getAsString("content");
        if (content == null || content.isEmpty()) return false;
        if (type == 570425393 && (content.contains("<pat") || content.contains("patMsg"))) return true;
        return low == 10000 && (content.contains("拍了拍") || content.contains("patted"));
    }

    private static void onChatroomUpdated(Object db, ContentValues cv, String where, String[] whereArgs) {
        String memberList = cv.getAsString("memberlist");
        if (memberList == null || memberList.trim().isEmpty()) return;
        String room = cv.getAsString("chatroomname");
        if ((room == null || !room.endsWith("@chatroom")) && whereArgs != null && whereArgs.length > 0) {
            if (whereArgs[0] != null && whereArgs[0].endsWith("@chatroom")) room = whereArgs[0];
        }
        if (room == null || !room.endsWith("@chatroom")) return;

        File snapDir = new File(Kernel.WXDIR + "chatroom-snap");
        File snapFile = new File(snapDir, ModuleState.hash(room));
        Set<String> next = parseMembers(memberList);
        if (next.isEmpty()) return;

        String prevRaw = snapFile.exists() ? ModuleState.read(snapFile.getAbsolutePath()) : null;
        if (prevRaw == null) {
            ModuleState.write(snapFile.getAbsolutePath(), joinMembers(next));
            return;
        }
        Set<String> prev = parseMembers(prevRaw);
        if (prev.equals(next)) return;

        List<String> left = new ArrayList<>();
        for (String m : prev) if (!next.contains(m)) left.add(m);
        List<String> joined = new ArrayList<>();
        for (String m : next) if (!prev.contains(m)) joined.add(m);

        ModuleState.write(snapFile.getAbsolutePath(), joinMembers(next));
        if (left.isEmpty() && joined.isEmpty()) return;

        StringBuilder tip = new StringBuilder();
        if (!left.isEmpty()) {
            tip.append(formatNames(db, left)).append(" 退出了群聊");
        }
        if (!joined.isEmpty()) {
            if (tip.length() > 0) tip.append("；");
            tip.append(formatNames(db, joined)).append(" 加入了群聊");
        }
        insertGroupTip(db, room, tip.toString());
        Kernel.T("GROUPGUARD room=" + room + " " + tip);
    }

    private static Set<String> parseMembers(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String part : raw.split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String joinMembers(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(';');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String formatNames(Object db, List<String> wxids) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(wxids.size(), 5);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append("、");
            String id = wxids.get(i);
            String name = RecallArchiveRuntime.resolveDisplayName(db, id);
            sb.append(name.isEmpty() ? id : name);
        }
        if (wxids.size() > shown) sb.append(" 等").append(wxids.size()).append("人");
        return sb.toString();
    }

    private static void insertGroupTip(Object db, String room, String text) {
        if (db == null || room == null || text == null || text.isEmpty()) return;
        try {
            long msgId = System.currentTimeMillis();
            Cursor mc = Kernel.rawQuery(db, "select max(msgId) from message", null);
            if (mc != null) {
                try {
                    if (mc.moveToFirst()) msgId = Math.max(mc.getLong(0) + 1L, 100000L);
                } finally {
                    mc.close();
                }
            }
            ContentValues cv = new ContentValues();
            cv.put("msgId", Long.valueOf(msgId));
            cv.put("type", Integer.valueOf(10000));
            cv.put("isSend", Integer.valueOf(0));
            cv.put("status", Integer.valueOf(3));
            cv.put("createTime", Long.valueOf(System.currentTimeMillis()));
            cv.put("talker", room);
            cv.put("content", "[群变动] " + text);
            de.robv.android.xposed.XposedHelpers.callMethod(db, "insert", "message", "msgId", cv);
        } catch (Throwable t) {
            KernelLog.debug("GroupGuard tip insert err " + t);
        }
    }

    static synchronized Config load() {
        long now = System.currentTimeMillis();
        if (cachedConfig != null && now - cachedAt < 2000L) return cachedConfig;
        Config c = new Config();
        try {
            String raw = ModuleState.read(Kernel.WXDIR + CONFIG_FILE);
            if (raw != null && !raw.trim().isEmpty()) {
                JSONObject j = new JSONObject(raw);
                c.memberTrackEnabled = j.optBoolean("memberTrackEnabled", false);
                c.patBlockEnabled = j.optBoolean("patBlockEnabled", false);
            }
        } catch (Throwable ignored) {}
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static synchronized boolean save(Config c) {
        try {
            JSONObject j = new JSONObject();
            j.put("memberTrackEnabled", c.memberTrackEnabled);
            j.put("patBlockEnabled", c.patBlockEnabled);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString());
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    static void showConfig(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final Config cfg = load();
        LinearLayout box = SettingsUi.column(act);
        final SettingsUi.ToggleRow memberRow = SettingsUi.toggle(act, "群成员变动提醒（退群 / 入群）",
                "本地快照对比 memberlist，发现退群/入群时在群内插入本地系统提示", cfg.memberTrackEnabled, on -> true);
        box.addView(memberRow);
        final SettingsUi.ToggleRow patRow = SettingsUi.toggle(act, "屏蔽“拍一拍”系统提示",
                "拦截收到的拍一拍提示消息，减少群聊刷屏", cfg.patBlockEnabled, on -> true);
        box.addView(patRow);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(box);

        new AlertDialog.Builder(act)
                .setTitle("群管理增强")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    cfg.memberTrackEnabled = memberRow.control.isChecked();
                    cfg.patBlockEnabled = patRow.control.isChecked();
                    boolean ok = save(cfg);
                    Toast.makeText(act, ok ? "群管理设置已保存" : "设置保存失败", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    static void sendAtAll(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        String cur = Kernel.CUR_ROOM;
        if (cur != null && cur.endsWith("@chatroom")) {
            promptAtAllMessage(act, cur, RecallArchiveRuntime.resolveDisplayName(null, cur));
            return;
        }
        Toast.makeText(act, "正在读取群聊列表…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<String> ids = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            try {
                Object db = Kernel.db0();
                Cursor c = Kernel.rawQuery(db,
                        "select username, conRemark, nickname from rcontact where username like '%@chatroom' order by username",
                        null);
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String id = c.getString(0);
                            String name = c.getString(1);
                            if (name == null || name.trim().isEmpty()) name = c.getString(2);
                            if (name == null || name.trim().isEmpty()) name = Kernel.groupNameFromDb(db, id);
                            if (name == null || name.trim().isEmpty()) name = id;
                            ids.add(id);
                            names.add(name.trim());
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Throwable t) {
                KernelLog.debug("AT_ALL list err " + t);
            }
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (ids.isEmpty()) {
                    Toast.makeText(act, "未找到群聊，请先进入一个群后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(act)
                        .setTitle("选择要 @所有人 的群聊")
                        .setItems(names.toArray(new String[0]), (d, w) -> promptAtAllMessage(act, ids.get(w), names.get(w)))
                        .setNegativeButton("取消", null)
                        .show();
            });
        }, "wxentry-atall").start();
    }

    private static void promptAtAllMessage(final Activity act, final String roomId, final String roomName) {
        final EditText et = new EditText(act);
        et.setHint("输入公告内容（将自动附加 @所有人 提醒）");
        et.setMinLines(3);
        new AlertDialog.Builder(act)
                .setTitle("发送 @所有人 · " + (roomName == null || roomName.isEmpty() ? roomId : roomName))
                .setView(et)
                .setPositiveButton("发送", (d, w) -> {
                    String msg = et.getText().toString().trim();
                    String payload = msg.isEmpty() ? "@所有人" : "@所有人 " + msg;
                    String src = "<msgsource><atuserlist>notify@all</atuserlist></msgsource>";
                    boolean ok = DexResolver.sendTextMessage(appCl, roomId, payload, src);
                    Toast.makeText(act, ok ? "已发送 @所有人 消息" : "发送失败，请检查发消息接口", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
