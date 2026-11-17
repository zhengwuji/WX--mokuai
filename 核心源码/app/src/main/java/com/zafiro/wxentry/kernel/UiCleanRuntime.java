package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * WeChat UI cleaner runtime:
 * 1) Hide unwanted items on the Discovery tab (Channels, Live, Top Stories, Search, Mini Programs, etc.)
 * 2) Suppress red dot badges on the Discovery tab and main navigation bar
 */
final class UiCleanRuntime {
    private static final String CONFIG_FILE = "uiclean.config.json";
    private static final String FIND_MORE_UI = "com.tencent.mm.ui.FindMoreFriendsUI";
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final String[] DISCOVERY_KEYS = {
            "find_friends_by_finder",
            "find_friends_by_finder_live",
            "find_friends_by_look",
            "find_friends_by_search",
            "find_friends_by_listen",
            "find_friends_by_near",
            "find_friends_by_shake",
            "find_friends_by_game",
            "find_friends_by_appbrand",
            "find_friends_by_jd"
    };

    static final String[] DISCOVERY_LABELS = {
            "视频号",
            "直播",
            "看一看",
            "搜一搜",
            "听一听",
            "附近",
            "摇一摇",
            "游戏",
            "小程序",
            "购物"
    };

    static final class Config {
        boolean badgeBlockEnabled = false;
        Set<String> hiddenKeys = new HashSet<>();
    }

    private UiCleanRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            Class<?> findUiCls = DexResolver.load(cl, FIND_MORE_UI);
            if (findUiCls != null) {
                // Hook onResume to remove hidden preferences or hide views
                XposedBridge.hookAllMethods(findUiCls, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (Kernel.MODULE_OFF || !(p.thisObject instanceof Activity)) return;
                        cleanDiscoveryItems((Activity) p.thisObject);
                    }
                });
            }

            // Hook Preference.getView to suppress hidden items & red dots
            for (String prefClsName : new String[]{
                    "com.tencent.mm.ui.base.preference.Preference",
                    "com.tencent.mm.ui.base.preference.IconPreference",
                    "android.preference.Preference"
            }) {
                try {
                    Class<?> pCls = DexResolver.load(cl, prefClsName);
                    if (pCls != null) {
                        hookPreferenceView(pCls);
                    }
                } catch (Throwable ignored) {}
            }

            started = true;
            Kernel.T("UICLEAN ready");
        } catch (Throwable t) {
            Kernel.T("UICLEAN install-error " + t.getClass().getSimpleName());
        }
    }

    private static void hookPreferenceView(Class<?> pCls) {
        try {
            XposedBridge.hookAllMethods(pCls, "getView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (Kernel.MODULE_OFF) return;
                    Object viewObj = p.getResult();
                    if (!(viewObj instanceof View)) return;
                    View v = (View) viewObj;
                    Object pref = p.thisObject;
                    Config c = load();

                    // Check if preference key is hidden
                    try {
                        Method getKey = pref.getClass().getMethod("getKey");
                        if (getKey != null) {
                            String key = (String) getKey.invoke(pref);
                            if (key != null && c.hiddenKeys.contains(key)) {
                                v.setVisibility(View.GONE);
                                ViewGroup.LayoutParams lp = v.getLayoutParams();
                                if (lp != null) {
                                    lp.height = 0;
                                    v.setLayoutParams(lp);
                                }
                                return;
                            }
                        }
                    } catch (Throwable ignored) {}

                    // Suppress badge red dot if enabled
                    if (c.badgeBlockEnabled) {
                        suppressBadges(v);
                    }
                }
            });
        } catch (Throwable t) {
            KernelLog.debug("Hook pref getView err: " + t);
        }
    }

    private static void cleanDiscoveryItems(Activity act) {
        Config c = load();
        if (c.hiddenKeys.isEmpty() && !c.badgeBlockEnabled) return;
        try {
            // Find preference screen
            Method getScreen = null;
            for (Method m : act.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().toLowerCase().contains("screen")) {
                    getScreen = m;
                    break;
                }
            }
            if (getScreen != null) {
                Object screen = getScreen.invoke(act);
                if (screen != null) {
                    for (String k : c.hiddenKeys) {
                        try {
                            Method find = screen.getClass().getMethod("findPreference", String.class);
                            Object item = find.invoke(screen, k);
                            if (item != null) {
                                Method remove = screen.getClass().getMethod("removePreference", item.getClass());
                                remove.invoke(screen, item);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("cleanDiscoveryItems err " + t);
        }
    }

    private static void suppressBadges(View v) {
        if (v == null) return;
        try {
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    suppressBadges(child);
                }
            } else {
                CharSequence desc = v.getContentDescription();
                String name = v.getClass().getSimpleName();
                if (name.contains("Badge") || name.contains("Dot")
                        || (desc != null && (desc.toString().contains("红点") || desc.toString().contains("未读")))) {
                    v.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {}
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
                    c.badgeBlockEnabled = j.optBoolean("badgeBlockEnabled", false);
                    JSONArray arr = j.optJSONArray("hiddenKeys");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            c.hiddenKeys.add(arr.getString(i));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("UiClean load config err " + t);
        }
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static boolean save(Config c) {
        if (c == null) return false;
        try {
            JSONObject j = new JSONObject();
            j.put("badgeBlockEnabled", c.badgeBlockEnabled);
            JSONArray arr = new JSONArray();
            for (String k : c.hiddenKeys) arr.put(k);
            j.put("hiddenKeys", arr);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString(2));
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            KernelLog.debug("UiClean save config err " + t);
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

        TextView intro = FeatureMenuUi.text(act, "界面净化与发现页精简", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "自定义隐藏微信发现页中不需要的入口，并屏蔽角标小红点，还原纯净体验。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        root.addView(FeatureMenuUi.toggle(act, "屏蔽未读小红点 / 角标", "全局隐藏发现页及各栏目的提示红点，避免强迫症打扰", cfg.badgeBlockEnabled, on -> {
            cfg.badgeBlockEnabled = on;
            save(cfg);
            return true;
        }));

        TextView itemsTitle = FeatureMenuUi.text(act, "发现页入口精简 (勾选即隐藏)", 14, FeatureMenuUi.Tone.INK);
        itemsTitle.getPaint().setFakeBoldText(true);
        itemsTitle.setPadding(0, SettingsUi.dp(act, 14), 0, SettingsUi.dp(act, 6));
        root.addView(itemsTitle);

        final boolean[] checkedItems = new boolean[DISCOVERY_KEYS.length];
        for (int i = 0; i < DISCOVERY_KEYS.length; i++) {
            checkedItems[i] = cfg.hiddenKeys.contains(DISCOVERY_KEYS[i]);
            final int index = i;
            final String key = DISCOVERY_KEYS[i];
            SettingsUi.ToggleRow row = FeatureMenuUi.toggle(act, "隐藏 " + DISCOVERY_LABELS[i], "从发现页移除 " + DISCOVERY_LABELS[i] + " 栏目", checkedItems[i], on -> {
                if (on) cfg.hiddenKeys.add(key);
                else cfg.hiddenKeys.remove(key);
                save(cfg);
                return true;
            });
            root.addView(row);
        }

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("完成", (d, which) -> {
                    save(cfg);
                    Toast.makeText(act, "设置已保存，重新切换到底部发现页即可生效", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
    }
}
