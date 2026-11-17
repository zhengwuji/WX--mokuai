package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * Advanced Moments (SNS) utilities:
 * 1) Prevent long text folding in Moments post (`SnsUploadUI`)
 * 2) One-click save original high-res photos and videos in `SnsBrowseUI` & `SnsOnlineVideoActivity`
 */
final class MomentsAdvancedRuntime {
    private static final String CONFIG_FILE = "moments_adv.config.json";
    private static final Set<String> ATTACHED_ACTIVITIES = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Config {
        boolean noFoldEnabled = true;
        boolean mediaSaveButtonEnabled = true;
    }

    private MomentsAdvancedRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            // 1. Hook SnsEditText / SnsUploadUI for text anti-folding (zeroes paste-length tracking)
            Class<?> snsEditCls = DexResolver.load(cl, "com.tencent.mm.plugin.sns.ui.SnsEditText");
            if (snsEditCls != null) {
                try {
                    XposedBridge.hookAllMethods(snsEditCls, "getPasterLen", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (Kernel.MODULE_OFF) return;
                            if (load().noFoldEnabled) p.setResult(0);
                        }
                    });
                } catch (Throwable ignored) {}
                try {
                    XposedBridge.hookAllMethods(snsEditCls, "setPasterLen", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (Kernel.MODULE_OFF) return;
                            if (load().noFoldEnabled && p.args != null && p.args.length > 0 && p.args[0] instanceof Integer) {
                                p.args[0] = 0;
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // 2. Hook SnsBrowseUI & SnsOnlineVideoActivity to add Save button
            for (String uiName : new String[]{
                    "com.tencent.mm.plugin.sns.ui.SnsBrowseUI",
                    "com.tencent.mm.plugin.sns.ui.SnsOnlineVideoActivity",
                    "com.tencent.mm.plugin.sns.ui.VideoActivity"
            }) {
                try {
                    Class<?> uiCls = DexResolver.load(cl, uiName);
                    if (uiCls != null) {
                        XposedBridge.hookAllMethods(uiCls, "onResume", new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (Kernel.MODULE_OFF || !(p.thisObject instanceof Activity)) return;
                                Config c = load();
                                if (!c.mediaSaveButtonEnabled) return;
                                attachSaveButton((Activity) p.thisObject);
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }

            started = true;
            Kernel.T("MOMENTSADV ready");
        } catch (Throwable t) {
            Kernel.T("MOMENTSADV install-error " + t.getClass().getSimpleName());
        }
    }

    private static void attachSaveButton(final Activity act) {
        String key = act.getClass().getName() + "@" + System.identityHashCode(act);
        if (ATTACHED_ACTIVITIES.contains(key)) return;
        ATTACHED_ACTIVITIES.add(key);

        act.runOnUiThread(() -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            try {
                ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.bottomMargin = SettingsUi.dp(act, 80);
                lp.rightMargin = SettingsUi.dp(act, 16);

                Button btn = new Button(act);
                btn.setText("保存原图/视频");
                btn.setTextSize(12);
                btn.getPaint().setFakeBoldText(true);
                btn.setAlpha(0.85f);
                btn.setOnClickListener(v -> saveCurrentMedia(act));

                decor.addView(btn, lp);
            } catch (Throwable t) {
                KernelLog.debug("attachSaveButton err " + t);
            }
        });
    }

    private static void saveCurrentMedia(final Activity act) {
        new Thread(() -> {
            try {
                File snsDir = new File(act.getCacheDir().getParentFile(), "microMsg");
                File foundFile = MediaExportHelper.findLatestFile(snsDir, 50000L, "sns", "video", ".mp4", ".jpg");
                if (foundFile == null || !foundFile.exists()) {
                    foundFile = MediaExportHelper.findLatestFile(act.getExternalCacheDir(), 50000L, "sns", "video", ".mp4", ".jpg");
                }
                if (foundFile == null || !foundFile.exists()) {
                    act.runOnUiThread(() -> Toast.makeText(act, "未定位到当前媒体的本地缓存文件", Toast.LENGTH_SHORT).show());
                    return;
                }

                boolean isVideo = foundFile.getName().toLowerCase().endsWith(".mp4") || foundFile.length() > 2L * 1024L * 1024L;
                String ext = isVideo ? ".mp4" : ".jpg";
                String mime = isVideo ? "video/mp4" : "image/jpeg";
                MediaExportHelper.Kind kind = isVideo ? MediaExportHelper.Kind.VIDEO : MediaExportHelper.Kind.IMAGE;
                final String saved = MediaExportHelper.saveFile(act, foundFile, kind, "Sns_" + System.currentTimeMillis() + ext, mime);

                act.runOnUiThread(() -> Toast.makeText(act, "媒体已成功保存至相册:\n" + saved, Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                act.runOnUiThread(() -> Toast.makeText(act, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "wxentry-sns-save").start();
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
                    c.noFoldEnabled = j.optBoolean("noFoldEnabled", true);
                    c.mediaSaveButtonEnabled = j.optBoolean("mediaSaveButtonEnabled", true);
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("MomentsAdv load config err " + t);
        }
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static boolean save(Config c) {
        if (c == null) return false;
        try {
            JSONObject j = new JSONObject();
            j.put("noFoldEnabled", c.noFoldEnabled);
            j.put("mediaSaveButtonEnabled", c.mediaSaveButtonEnabled);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString(2));
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            KernelLog.debug("MomentsAdv save config err " + t);
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

        TextView intro = FeatureMenuUi.text(act, "朋友圈高级增强", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "解决长文本发表被微信折叠成一行的问题，并在全屏浏览朋友圈原图或视频时提供直接提取保存按钮。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        root.addView(FeatureMenuUi.toggle(act, "朋友圈长文防折叠", "发表长文本动态时自动绕过微信粘贴折叠判定，全文完全展开展示", cfg.noFoldEnabled, on -> {
            cfg.noFoldEnabled = on;
            save(cfg);
            return true;
        }));

        root.addView(FeatureMenuUi.toggle(act, "全屏浏览原图/视频一键直存", "查看朋友圈大图或播放视频时，右下角悬浮直接保存按钮，一键导出原画质到相册", cfg.mediaSaveButtonEnabled, on -> {
            cfg.mediaSaveButtonEnabled = on;
            save(cfg);
            return true;
        }));

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("完成", (d, which) -> {
                    save(cfg);
                    Toast.makeText(act, "朋友圈增强设置已保存", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
    }
}
