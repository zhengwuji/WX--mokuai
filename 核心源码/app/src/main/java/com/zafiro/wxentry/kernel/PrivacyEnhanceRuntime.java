package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Privacy & Security enhancement runtime:
 * 1) Block "typing..." status indicator packets (NetSceneSendTyping / type 261)
 * 2) Wallet balance & coin anti-peep masking (replaces real money values with `****`)
 * 3) Skip WeChat external link warning & block page (`weixin110.qq.com`)
 */
final class PrivacyEnhanceRuntime {
    private static final String CONFIG_FILE = "privacy.config.json";
    private static final Pattern MONEY_PATTERN = Pattern.compile("^[¥￥]?\\s*\\d{1,3}(,\\d{3})*(\\.\\d{2})?\\s*元?$");
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Config {
        boolean typingBlockEnabled = true;
        boolean walletMaskEnabled = false;
        boolean skipUrlBlockEnabled = true;
    }

    private PrivacyEnhanceRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            // 1. Hook NetScene queue to intercept NetSceneSendTyping (type 261)
            hookTypingScene(cl);

            // 2. Hook TextView.setText to mask wallet money numbers
            hookWalletMasking();

            // 3. Hook WebView.loadUrl to bypass weixin110 interceptor
            hookWebViewRedirect(cl);

            started = true;
            Kernel.T("PRIVACY ready");
        } catch (Throwable t) {
            Kernel.T("PRIVACY install-error " + t.getClass().getSimpleName());
        }
    }

    private static void hookTypingScene(ClassLoader cl) {
        try {
            Class<?> loc = DexResolver.serviceLocator(cl);
            if (loc != null) {
                for (Method m : loc.getDeclaredMethods()) {
                    if ("q".equals(m.getName()) && m.getParameterCount() == 0) {
                        Class<?> qContainerCls = m.getReturnType();
                        if (qContainerCls == null) continue;
                        try {
                            java.lang.reflect.Field bField = qContainerCls.getDeclaredField("b");
                            Class<?> queueCls = bField.getType();
                            if (queueCls != null && queueCls != Object.class) {
                                XposedBridge.hookAllMethods(queueCls, "g", new XC_MethodHook() {
                                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                                        if (Kernel.MODULE_OFF || p.args == null || p.args.length == 0) return;
                                        Object scene = p.args[0];
                                        if (scene == null) return;
                                        Config c = load();
                                        if (!c.typingBlockEnabled) return;
                                        if (isTypingScene(scene)) {
                                            p.setResult(true);
                                            KernelLog.debug("Blocked outgoing NetSceneSendTyping: " + scene.getClass().getName());
                                        }
                                    }
                                });
                                KernelLog.debug("Hooked NetSceneQueue.g on " + queueCls.getName());
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("Typing hook err " + t);
        }
    }

    private static boolean isTypingScene(Object scene) {
        if (scene == null) return false;
        String name = scene.getClass().getSimpleName();
        if (name.contains("Typing") || name.contains("SendTyping")) return true;
        try {
            Method getType = scene.getClass().getMethod("getType");
            if (getType != null) {
                int type = ((Integer) getType.invoke(scene)).intValue();
                if (type == 261) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void hookWalletMasking() {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Kernel.MODULE_OFF || p.args == null || p.args.length == 0) return;
                    CharSequence cs = (CharSequence) p.args[0];
                    if (cs == null || cs.length() == 0) return;
                    Config c = load();
                    if (!c.walletMaskEnabled) return;

                    TextView tv = (TextView) p.thisObject;
                    Context ctx = tv.getContext();
                    if (!(ctx instanceof Activity)) return;
                    String actName = ctx.getClass().getName();
                    if (isWalletActivity(actName)) {
                        String s = cs.toString().trim();
                        if (MONEY_PATTERN.matcher(s).matches()) {
                            p.args[0] = s.startsWith("¥") || s.startsWith("￥") ? "¥ ****" : "****";
                        }
                    }
                }
            });
        } catch (Throwable t) {
            KernelLog.debug("Wallet mask hook err " + t);
        }
    }

    private static boolean isWalletActivity(String actName) {
        if (actName == null) return false;
        return actName.contains("MallIndex") || actName.contains("Wallet")
                || actName.contains("OfflineCoin") || actName.contains("Bankcard")
                || actName.contains("Balance");
    }

    private static void hookWebViewRedirect(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Kernel.MODULE_OFF || p.args == null || p.args.length == 0) return;
                    Object arg = p.args[0];
                    if (!(arg instanceof String)) return;
                    String url = (String) arg;
                    Config c = load();
                    if (!c.skipUrlBlockEnabled) return;

                    if (url.contains("weixin110.qq.com") || url.contains("w_safe_page")) {
                        String target = extractTargetUrl(url);
                        if (target != null && !target.isEmpty()) {
                            KernelLog.debug("Bypassing WeChat link block: redirecting to " + target);
                            p.args[0] = target;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            KernelLog.debug("WebView redirect hook err " + t);
        }
    }

    private static String extractTargetUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String target = uri.getQueryParameter("target_url");
            if (target == null) target = uri.getQueryParameter("url");
            if (target == null) target = uri.getQueryParameter("requrl");
            if (target != null && !target.isEmpty()) {
                return URLDecoder.decode(target, "UTF-8");
            }
        } catch (Throwable ignored) {}
        return null;
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
                    c.typingBlockEnabled = j.optBoolean("typingBlockEnabled", true);
                    c.walletMaskEnabled = j.optBoolean("walletMaskEnabled", false);
                    c.skipUrlBlockEnabled = j.optBoolean("skipUrlBlockEnabled", true);
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("Privacy load config err " + t);
        }
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static boolean save(Config c) {
        if (c == null) return false;
        try {
            JSONObject j = new JSONObject();
            j.put("typingBlockEnabled", c.typingBlockEnabled);
            j.put("walletMaskEnabled", c.walletMaskEnabled);
            j.put("skipUrlBlockEnabled", c.skipUrlBlockEnabled);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString(2));
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            KernelLog.debug("Privacy save config err " + t);
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

        TextView intro = FeatureMenuUi.text(act, "微信隐私增强与防护", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "提供防窥、状态屏蔽与安全拦截跳过功能，保护聊天及资产隐私。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        root.addView(FeatureMenuUi.toggle(act, "屏蔽“对方正在输入...”", "在会话输入文字时，静默拦截向上汇报的状态包，对方无法得知输入状态", cfg.typingBlockEnabled, on -> {
            cfg.typingBlockEnabled = on;
            save(cfg);
            return true;
        }));

        root.addView(FeatureMenuUi.toggle(act, "钱包资产防窥遮罩", "打开微信服务、钱包、零钱页面时，自动将金额数字遮挡为 `****`，防止旁人偷窥", cfg.walletMaskEnabled, on -> {
            cfg.walletMaskEnabled = on;
            save(cfg);
            return true;
        }));

        root.addView(FeatureMenuUi.toggle(act, "跳过外部网页风险拦截页", "点击外链时自动跳过 weixin110 拦截确认页，直接访问目标原始网址", cfg.skipUrlBlockEnabled, on -> {
            cfg.skipUrlBlockEnabled = on;
            save(cfg);
            return true;
        }));

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("完成", (d, which) -> {
                    save(cfg);
                    Toast.makeText(act, "隐私增强设置已更新", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
    }
}
