package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chat automation:
 * 1) Keyword / @mention strong alerts (vibration + high-priority notification)
 * 2) Rule-based auto-reply with per-talker cooldown & freshness guard
 * 3) Zero-UI auto-accept friend verification (`fmessage_msginfo`) with optional welcome message
 */
final class AutoReplyRuntime {
    private static final String CONFIG_FILE = "autoreply.config.json";
    private static final String ALERT_CHANNEL = "wxentry_kw_alert";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, Long> LAST_REPLY_MS = new ConcurrentHashMap<>();
    private static final Set<String> SEEN_MSG = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> SEEN_FRIEND = Collections.synchronizedSet(new HashSet<>());
    private static volatile ClassLoader appCl;
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Rule {
        String scope = "ALL"; // ALL, PERSON, GROUP
        String match = "CONTAINS"; // CONTAINS, EXACT
        String pattern = "";
        String reply = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("scope", scope);
                o.put("match", match);
                o.put("pattern", pattern);
                o.put("reply", reply);
            } catch (Throwable ignored) {}
            return o;
        }

        static Rule fromJson(JSONObject o) {
            Rule r = new Rule();
            if (o != null) {
                r.scope = o.optString("scope", "ALL");
                r.match = o.optString("match", "CONTAINS");
                r.pattern = o.optString("pattern", "");
                r.reply = o.optString("reply", "");
            }
            return r;
        }
    }

    static final class Config {
        boolean alertEnabled;
        boolean alertVibrate = true;
        boolean alertNotify = true;
        String alertKeywords = "";
        boolean replyEnabled;
        int cooldownSec = 30;
        final List<Rule> rules = new ArrayList<>();
        boolean friendEnabled;
        String friendKeyword = "";
        String friendWelcome = "";
    }

    private AutoReplyRuntime() {}

    static boolean ready() {
        return started;
    }

    static boolean friendEnabled() {
        return !Kernel.MODULE_OFF && load().friendEnabled;
    }

    static boolean setFriendEnabled(boolean on) {
        Config c = load();
        c.friendEnabled = on;
        return save(c) ? on : load().friendEnabled;
    }

    static synchronized void start(ClassLoader cl) {
        appCl = cl;
        if (started) return;
        MessageInsertBus.addInsertListener((db, table, cv, rowId) -> {
            if (Kernel.MODULE_OFF || cv == null) return;
            if ("message".equals(table)) {
                onMessageInserted(db, cv);
            } else if ("fmessage_msginfo".equals(table)) {
                onFriendMessageInserted(cv);
            }
        });
        started = true;
        Kernel.T("AUTOREPLY ready");
    }

    static synchronized Config load() {
        long now = System.currentTimeMillis();
        if (cachedConfig != null && now - cachedAt < 2000L) return cachedConfig;
        Config c = new Config();
        try {
            String raw = ModuleState.read(Kernel.WXDIR + CONFIG_FILE);
            if (raw != null && !raw.trim().isEmpty()) {
                JSONObject j = new JSONObject(raw);
                c.alertEnabled = j.optBoolean("alertEnabled", false);
                c.alertVibrate = j.optBoolean("alertVibrate", true);
                c.alertNotify = j.optBoolean("alertNotify", true);
                c.alertKeywords = j.optString("alertKeywords", "");
                c.replyEnabled = j.optBoolean("replyEnabled", false);
                c.cooldownSec = Math.max(3, j.optInt("cooldownSec", 30));
                c.friendEnabled = j.optBoolean("friendEnabled", false);
                c.friendKeyword = j.optString("friendKeyword", "");
                c.friendWelcome = j.optString("friendWelcome", "");
                JSONArray arr = j.optJSONArray("rules");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        Rule r = Rule.fromJson(arr.optJSONObject(i));
                        if (!r.pattern.isEmpty() && !r.reply.isEmpty()) c.rules.add(r);
                    }
                }
            }
        } catch (Throwable ignored) {}
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static synchronized boolean save(Config c) {
        try {
            JSONObject j = new JSONObject();
            j.put("alertEnabled", c.alertEnabled);
            j.put("alertVibrate", c.alertVibrate);
            j.put("alertNotify", c.alertNotify);
            j.put("alertKeywords", c.alertKeywords == null ? "" : c.alertKeywords);
            j.put("replyEnabled", c.replyEnabled);
            j.put("cooldownSec", Math.max(3, Math.min(86400, c.cooldownSec)));
            j.put("friendEnabled", c.friendEnabled);
            j.put("friendKeyword", c.friendKeyword == null ? "" : c.friendKeyword);
            j.put("friendWelcome", c.friendWelcome == null ? "" : c.friendWelcome);
            JSONArray arr = new JSONArray();
            for (Rule r : c.rules) arr.put(r.toJson());
            j.put("rules", arr);
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

    private static void onMessageInserted(Object db, ContentValues cv) {
        Config cfg = load();
        if (!cfg.alertEnabled && !cfg.replyEnabled) return;
        Integer typeObj = cv.getAsInteger("type");
        Long isSend = cv.getAsLong("isSend");
        if (typeObj == null || isSend == null || isSend.longValue() != 0L) return;
        if ((typeObj.intValue() & 0xffff) != 1) return; // Text messages

        Long created = cv.getAsLong("createTime");
        if (created == null) return;
        long sentMs = created.longValue() < 100000000000L ? created.longValue() * 1000L : created.longValue();
        long age = System.currentTimeMillis() - sentMs;
        if (age > 120_000L || age < -30_000L) return; // Only react to live incoming messages

        final String talker = cv.getAsString("talker");
        if (shouldIgnoreTalker(talker)) return;

        Long svrId = cv.getAsLong("msgSvrId");
        String dedup = talker + "|" + (svrId != null ? svrId : cv.getAsLong("msgId"));
        if (!SEEN_MSG.add(dedup)) return;
        if (SEEN_MSG.size() > 512) SEEN_MSG.clear();

        String rawContent = cv.getAsString("content");
        if (rawContent == null || rawContent.isEmpty()) return;
        final String body = stripGroupPrefix(talker, rawContent);
        if (body.isEmpty()) return;

        if (cfg.alertEnabled && matchesAlert(cfg.alertKeywords, body)) {
            triggerAlert(db, talker, body, cfg);
        }

        if (cfg.replyEnabled && !cfg.rules.isEmpty()) {
            boolean isGroup = talker.endsWith("@chatroom");
            String matchedReply = matchRule(cfg.rules, isGroup, body);
            if (matchedReply != null) {
                long now = System.currentTimeMillis();
                Long last = LAST_REPLY_MS.get(talker);
                if (last == null || now - last.longValue() >= cfg.cooldownSec * 1000L) {
                    LAST_REPLY_MS.put(talker, Long.valueOf(now));
                    final String replyText = matchedReply;
                    MAIN.postDelayed(() -> {
                        boolean ok = DexResolver.sendTextMessage(appCl, talker, replyText, null);
                        Kernel.T("AUTOREPLY sent to=" + talker + " ok=" + ok);
                    }, 450L);
                }
            }
        }
    }

    private static boolean matchesAlert(String keywords, String body) {
        if (keywords == null || keywords.trim().isEmpty()) return false;
        String lower = body.toLowerCase();
        for (String kw : keywords.split("[,，\\r\\n]+")) {
            String k = kw.trim();
            if (!k.isEmpty() && lower.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    private static void triggerAlert(Object db, String talker, String body, Config cfg) {
        String name = RecallArchiveRuntime.resolveDisplayName(db, talker);
        String who = name.isEmpty() ? talker : name;
        String preview = body.length() > 60 ? body.substring(0, 60) + "..." : body;
        if (cfg.alertVibrate) {
            try {
                Context ctx = Kernel.APP_CTX;
                if (ctx != null) {
                    Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26) {
                            v.vibrate(VibrationEffect.createOneShot(280L, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            v.vibrate(280L);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (cfg.alertNotify) {
            Kernel.toastMain("关键词提醒 [" + who + "]：" + preview);
            Context ctx = Kernel.APP_CTX;
            if (ctx != null) {
                try {
                    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        if (Build.VERSION.SDK_INT >= 26) {
                            NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL, "关键词强提醒", NotificationManager.IMPORTANCE_HIGH);
                            ch.enableVibration(true);
                            nm.createNotificationChannel(ch);
                        }
                        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                                ? new Notification.Builder(ctx, ALERT_CHANNEL)
                                : new Notification.Builder(ctx);
                        int icon = ctx.getApplicationInfo().icon != 0 ? ctx.getApplicationInfo().icon : android.R.drawable.ic_dialog_info;
                        b.setSmallIcon(icon).setContentTitle("关键词提醒 · " + who).setContentText(preview).setAutoCancel(true);
                        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String matchRule(List<Rule> rules, boolean isGroup, String body) {
        String trimmed = body.trim();
        for (Rule r : rules) {
            if ("PERSON".equals(r.scope) && isGroup) continue;
            if ("GROUP".equals(r.scope) && !isGroup) continue;
            if ("EXACT".equals(r.match)) {
                if (trimmed.equalsIgnoreCase(r.pattern.trim())) return r.reply;
            } else {
                if (trimmed.toLowerCase().contains(r.pattern.trim().toLowerCase())) return r.reply;
            }
        }
        return null;
    }

    private static void onFriendMessageInserted(ContentValues cv) {
        final Config cfg = load();
        if (!cfg.friendEnabled) return;
        Integer isSend = cv.getAsInteger("isSend");
        if (isSend == null || isSend.intValue() != 0) return;

        Long created = cv.getAsLong("createTime");
        if (created != null && created.longValue() > 0) {
            long sentMs = created.longValue() < 100000000000L ? created.longValue() * 1000L : created.longValue();
            long age = System.currentTimeMillis() - sentMs;
            if (age > 180_000L || age < -30_000L) return;
        }

        final String msgContent = cv.getAsString("msgContent");
        String talker = cv.getAsString("talker");
        if (msgContent == null) return;

        String from = xmlAttr(msgContent, "fromusername");
        if (from.isEmpty()) from = talker == null ? "" : talker;
        String ticket = xmlAttr(msgContent, "ticket");
        String sceneStr = xmlAttr(msgContent, "scene");
        String verifyText = xmlAttr(msgContent, "content");
        int scene = parseInt(sceneStr, 30);
        if (from.isEmpty() || ticket.isEmpty()) return;

        if (!cfg.friendKeyword.trim().isEmpty()) {
            boolean hit = false;
            String low = verifyText.toLowerCase();
            for (String kw : cfg.friendKeyword.split("[,，\\r\\n]+")) {
                String k = kw.trim();
                if (!k.isEmpty() && low.contains(k.toLowerCase())) { hit = true; break; }
            }
            if (!hit) return;
        }

        String dedup = from + "|" + ticket;
        if (!SEEN_FRIEND.add(dedup)) return;
        if (SEEN_FRIEND.size() > 256) SEEN_FRIEND.clear();

        final String targetUser = from;
        final String verifyTicket = ticket;
        final int verifyScene = scene;
        MAIN.postDelayed(() -> acceptFriend(targetUser, verifyTicket, verifyScene, cfg.friendWelcome), 900L);
    }

    private static void acceptFriend(final String user, final String ticket, final int scene, final String welcome) {
        try {
            Class<?> r3 = DexResolver.verifyUserClass(appCl);
            if (r3 == null) {
                Kernel.T("AUTOFRIEND verifyUserClass missing");
                return;
            }
            Constructor<?> targetCtor = null;
            for (Constructor<?> c : r3.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 8 && ps[0] == int.class && ps[1] == String.class
                        && ps[2] == String.class && ps[3] == int.class && ps[4] == String.class
                        && ps[5] == int.class && List.class.isAssignableFrom(ps[6])) {
                    targetCtor = c;
                    break;
                }
            }
            if (targetCtor == null) return;
            targetCtor.setAccessible(true);
            Object netScene = targetCtor.newInstance(
                    Integer.valueOf(3), user, ticket, Integer.valueOf(scene), "", Integer.valueOf(0), null, null);
            boolean sent = DexResolver.sendNetScene(appCl, netScene);
            Kernel.T("AUTOFRIEND verify dispatched user=" + user + " scene=" + scene + " ok=" + sent);
            if (sent && welcome != null && !welcome.trim().isEmpty()) {
                final String msg = welcome.trim();
                MAIN.postDelayed(() -> DexResolver.sendTextMessage(appCl, user, msg, null), 1600L);
            }
        } catch (Throwable t) {
            KernelLog.stack("AutoReplyRuntime.acceptFriend", t);
        }
    }

    private static boolean shouldIgnoreTalker(String talker) {
        if (talker == null || talker.isEmpty()) return true;
        if (talker.startsWith("gh_") || talker.endsWith("@app")) return true;
        return "weixin".equals(talker) || "fmessage".equals(talker)
                || "newsapp".equals(talker) || "filehelper".equals(talker);
    }

    private static String stripGroupPrefix(String talker, String content) {
        if (talker != null && talker.endsWith("@chatroom")) {
            int colon = content.indexOf(":\n");
            if (colon > 0 && colon < 128) return content.substring(colon + 2);
        }
        return content;
    }

    private static String xmlAttr(String xml, String attr) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(attr) + "\\s*=\\s*\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static int parseInt(String s, int def) {
        try {
            return (s == null || s.isEmpty()) ? def : Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    static void showConfig(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final Config cfg = load();
        LinearLayout box = SettingsUi.column(act);

        SettingsUi.heading(box, "关键词强提醒");
        final SettingsUi.ToggleRow alertRow = SettingsUi.toggle(act, "开启关键词提醒", "收到匹配消息时震动/通知，群免打扰也有效",
                cfg.alertEnabled, on -> true);
        box.addView(alertRow);
        final EditText kwInput = new EditText(act);
        kwInput.setHint("提醒关键词，多个用逗号或换行分隔（如：开会,@我,红包）");
        kwInput.setText(cfg.alertKeywords);
        box.addView(kwInput);

        SettingsUi.heading(box, "规则自动回复");
        final SettingsUi.ToggleRow replyRow = SettingsUi.toggle(act, "开启自动回复", "当前规则数：" + cfg.rules.size() + "（点下方按钮编辑规则）",
                cfg.replyEnabled, on -> true);
        box.addView(replyRow);
        final EditText cdInput = new EditText(act);
        cdInput.setHint("同一会话冷却时间（秒，默认 30）");
        cdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        cdInput.setText(String.valueOf(cfg.cooldownSec));
        box.addView(cdInput);

        android.widget.Button editRules = new android.widget.Button(act);
        editRules.setText("管理自动回复规则（" + cfg.rules.size() + " 条）");
        editRules.setOnClickListener(v -> showRulesManager(act, cfg, () ->
                replyRow.subtitle.setText("当前规则数：" + cfg.rules.size() + "（点下方按钮编辑规则）")));
        box.addView(editRules);

        SettingsUi.heading(box, "自动通过好友请求");
        final SettingsUi.ToggleRow friendRow = SettingsUi.toggle(act, "自动同意新好友", "收到好友验证时静默通过",
                cfg.friendEnabled, on -> true);
        box.addView(friendRow);
        final EditText fKwInput = new EditText(act);
        fKwInput.setHint("验证口令关键词过滤（留空=全部自动通过）");
        fKwInput.setText(cfg.friendKeyword);
        box.addView(fKwInput);
        final EditText fWelcomeInput = new EditText(act);
        fWelcomeInput.setHint("通过后自动发送欢迎语（留空=不发送）");
        fWelcomeInput.setText(cfg.friendWelcome);
        box.addView(fWelcomeInput);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(box);

        new AlertDialog.Builder(act)
                .setTitle("关键词提醒 / 自动回复 / 好友")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    cfg.alertEnabled = alertRow.control.isChecked();
                    cfg.alertKeywords = kwInput.getText().toString();
                    cfg.replyEnabled = replyRow.control.isChecked();
                    cfg.cooldownSec = Math.max(3, parseInt(cdInput.getText().toString().trim(), 30));
                    cfg.friendEnabled = friendRow.control.isChecked();
                    cfg.friendKeyword = fKwInput.getText().toString();
                    cfg.friendWelcome = fWelcomeInput.getText().toString();
                    boolean ok = save(cfg);
                    Toast.makeText(act, ok ? "自动化设置已保存" : "设置保存失败", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showRulesManager(final Activity act, final Config cfg, final Runnable onUpdated) {
        String[] labels = new String[cfg.rules.size() + 1];
        labels[0] = "+ 新增自动回复规则";
        for (int i = 0; i < cfg.rules.size(); i++) {
            Rule r = cfg.rules.get(i);
            labels[i + 1] = "[" + scopeLabel(r.scope) + " · " + ("EXACT".equals(r.match) ? "精确" : "包含") + "] "
                    + r.pattern + " -> " + r.reply;
        }
        new AlertDialog.Builder(act)
                .setTitle("自动回复规则列表")
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        showRuleEditor(act, cfg, -1, onUpdated);
                    } else {
                        showRuleEditor(act, cfg, which - 1, onUpdated);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String scopeLabel(String scope) {
        if ("PERSON".equals(scope)) return "私聊";
        if ("GROUP".equals(scope)) return "群聊";
        return "全部";
    }

    private static void showRuleEditor(final Activity act, final Config cfg, final int idx, final Runnable onUpdated) {
        final Rule editing = idx >= 0 ? cfg.rules.get(idx) : new Rule();
        LinearLayout box = SettingsUi.column(act);

        final Spinner scopeSpin = new Spinner(act);
        scopeSpin.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部会话", "仅私聊", "仅群聊"}));
        scopeSpin.setSelection("PERSON".equals(editing.scope) ? 1 : ("GROUP".equals(editing.scope) ? 2 : 0));
        box.addView(scopeSpin);

        final Spinner matchSpin = new Spinner(act);
        matchSpin.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"包含关键词", "精确匹配全文"}));
        matchSpin.setSelection("EXACT".equals(editing.match) ? 1 : 0);
        box.addView(matchSpin);

        final EditText patInput = new EditText(act);
        patInput.setHint("触发关键词（如：在吗）");
        patInput.setText(editing.pattern);
        box.addView(patInput);

        final EditText repInput = new EditText(act);
        repInput.setHint("自动回复内容");
        repInput.setText(editing.reply);
        box.addView(repInput);

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(idx >= 0 ? "编辑规则" : "新增自动回复规则")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String p = patInput.getText().toString().trim();
                    String r = repInput.getText().toString().trim();
                    if (p.isEmpty() || r.isEmpty()) {
                        Toast.makeText(act, "关键词和回复内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Rule nr = new Rule();
                    int sp = scopeSpin.getSelectedItemPosition();
                    nr.scope = sp == 1 ? "PERSON" : (sp == 2 ? "GROUP" : "ALL");
                    nr.match = matchSpin.getSelectedItemPosition() == 1 ? "EXACT" : "CONTAINS";
                    nr.pattern = p;
                    nr.reply = r;
                    if (idx >= 0) cfg.rules.set(idx, nr);
                    else cfg.rules.add(nr);
                    save(cfg);
                    if (onUpdated != null) onUpdated.run();
                })
                .setNegativeButton("取消", null);
        if (idx >= 0) {
            b.setNeutralButton("删除此规则", (d, w) -> {
                cfg.rules.remove(idx);
                save(cfg);
                if (onUpdated != null) onUpdated.run();
            });
        }
        b.show();
    }
}
