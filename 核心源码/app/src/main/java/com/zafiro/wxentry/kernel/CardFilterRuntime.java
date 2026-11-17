package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Card / AppMsg filter runtime:
 * Filters incoming type=49 messages (Mini programs, Pinduoduo bargains, coupon links, etc.)
 * via MessageInsertBus before SQLite insertion.
 */
final class CardFilterRuntime {
    private static final String CONFIG_FILE = "cardfilter.config.json";
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>");
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Config {
        boolean enabled = false;
        int mode = 0; // 0 = 静默丢弃 (Drop), 1 = 折叠为轻提示
        List<String> keywords = new ArrayList<>(Arrays.asList(
                "砍一刀", "拼多多", "互助", "京喜", "加速包", "帮我点一下", "助力", "天天领现金"
        ));
    }

    private CardFilterRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        MessageInsertBus.addBeforeInsertFilter((db, table, cv) -> {
            if (!"message".equals(table) || Kernel.MODULE_OFF || cv == null) return false;
            Config c = load();
            if (!c.enabled) return false;

            Integer typeObj = cv.getAsInteger("type");
            if (typeObj == null || typeObj.intValue() != 49) return false;

            String content = cv.getAsString("content");
            if (content == null || content.isEmpty()) return false;

            // Check if matches any keyword
            boolean match = false;
            String matchedKw = "";
            for (String kw : c.keywords) {
                if (kw != null && !kw.isEmpty() && content.contains(kw)) {
                    match = true;
                    matchedKw = kw;
                    break;
                }
            }

            if (!match) return false;

            String title = "小程序/卡片";
            Matcher m = TITLE_PATTERN.matcher(content);
            if (m.find()) title = m.group(1);

            KernelLog.debug("CardFilter matched: kw=[" + matchedKw + "] title=[" + title + "]");

            if (c.mode == 0) {
                // Drop completely
                return true;
            } else {
                // Fold into simple text
                cv.put("type", 1); // standard text message
                cv.put("content", "[已折叠卡片消息: " + title + "]");
                return false;
            }
        });

        started = true;
        Kernel.T("CARDFILTER ready");
    }

    static Config load() {
        long now = System.currentTimeMillis();
        Config c = cachedConfig;
        if (c != null && now - cachedAt < 3000L) return c;
        c = new Config();
        try {
            File f = new File(Kernel.WXDIR + CONFIG_FILE);
            if (f.exists()) {
                String raw = ModuleState.read(f.getAbsolutePath());
                if (raw != null && !raw.isEmpty()) {
                    JSONObject j = new JSONObject(raw);
                    c.enabled = j.optBoolean("enabled", false);
                    c.mode = j.optInt("mode", 0);
                    JSONArray arr = j.optJSONArray("keywords");
                    if (arr != null) {
                        c.keywords.clear();
                        for (int i = 0; i < arr.length(); i++) {
                            c.keywords.add(arr.getString(i));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("CardFilter load config err " + t);
        }
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static boolean save(Config c) {
        if (c == null) return false;
        try {
            JSONObject j = new JSONObject();
            j.put("enabled", c.enabled);
            j.put("mode", c.mode);
            JSONArray arr = new JSONArray();
            for (String kw : c.keywords) arr.put(kw);
            j.put("keywords", arr);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString(2));
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            KernelLog.debug("CardFilter save config err " + t);
            return false;
        }
    }

    static void showConfig(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final Config cfg = load();

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 18);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = FeatureMenuUi.text(act, "卡片消息过滤与折叠", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "收到匹配关键词的小程序或分享卡片时，静默拦截丢弃或折叠为轻量单行文本。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        root.addView(FeatureMenuUi.toggle(act, "开启卡片消息过滤", "开启后按设定规则对 AppMsg 卡片消息进行过滤", cfg.enabled, on -> {
            cfg.enabled = on;
            save(cfg);
            return true;
        }));

        TextView modeTitle = FeatureMenuUi.text(act, "过滤处理动作", 14, FeatureMenuUi.Tone.INK);
        modeTitle.getPaint().setFakeBoldText(true);
        modeTitle.setPadding(0, SettingsUi.dp(act, 14), 0, SettingsUi.dp(act, 6));
        root.addView(modeTitle);

        final RadioGroup rg = new RadioGroup(act);
        rg.setOrientation(LinearLayout.VERTICAL);

        RadioButton rbDrop = new RadioButton(act);
        rbDrop.setText("完全静默拦截丢弃 (不显示在聊天列表中)");
        rbDrop.setId(201);
        rg.addView(rbDrop);

        RadioButton rbFold = new RadioButton(act);
        rbFold.setText("折叠为精简单行提示 (`[已折叠卡片: 标题]`)");
        rbFold.setId(202);
        rg.addView(rbFold);

        if (cfg.mode == 1) rbFold.setChecked(true);
        else rbDrop.setChecked(true);
        root.addView(rg);

        TextView kwTitle = FeatureMenuUi.text(act, "过滤关键词 (逗号或换行分隔)", 14, FeatureMenuUi.Tone.INK);
        kwTitle.getPaint().setFakeBoldText(true);
        kwTitle.setPadding(0, SettingsUi.dp(act, 14), 0, SettingsUi.dp(act, 6));
        root.addView(kwTitle);

        final EditText kwEdit = new EditText(act);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cfg.keywords.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cfg.keywords.get(i));
        }
        kwEdit.setText(sb.toString());
        kwEdit.setHint("砍一刀, 拼多多, 互助, 助力...");
        root.addView(kwEdit);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("保存设置", (d, which) -> {
                    cfg.mode = rg.getCheckedRadioButtonId() == 202 ? 1 : 0;
                    String raw = kwEdit.getText().toString();
                    cfg.keywords.clear();
                    for (String part : raw.split("[,，\\n]+")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) cfg.keywords.add(trimmed);
                    }
                    save(cfg);
                    Toast.makeText(act, "卡片过滤设置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
    }
}
