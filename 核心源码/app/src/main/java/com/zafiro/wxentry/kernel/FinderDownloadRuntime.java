package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * WeChat Channels (Finder / 视频号) video extractor & downloader:
 * Extracts cached MP4 video without watermark and exports via MediaStore to `Movies/WechatXposed/`.
 */
final class FinderDownloadRuntime {
    private static final Set<String> ATTACHED_ACTIVITIES = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean started;

    private FinderDownloadRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            for (String uiName : new String[]{
                    "com.tencent.mm.plugin.finder.ui.FinderShareFeedRelUI",
                    "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
                    "com.tencent.mm.plugin.finder.feed.ui.FinderTimelineUI",
                    "com.tencent.mm.plugin.finder.feed.ui.FinderLiveVisitorWithoutAffinityUI"
            }) {
                try {
                    Class<?> uiCls = DexResolver.load(cl, uiName);
                    if (uiCls != null) {
                        XposedBridge.hookAllMethods(uiCls, "onResume", new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (Kernel.MODULE_OFF || !enabled()) return;
                                if (!(p.thisObject instanceof Activity)) return;
                                attachDownloadButton((Activity) p.thisObject);
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }

            started = true;
            Kernel.T("FINDER ready");
        } catch (Throwable t) {
            Kernel.T("FINDER install-error " + t.getClass().getSimpleName());
        }
    }

    static boolean enabled() {
        return !Kernel.MODULE_OFF && !new File(Kernel.WXDIR + "finder.off").exists();
    }

    static boolean setEnabled(boolean on) {
        File f = new File(Kernel.WXDIR + "finder.off");
        if (!on) {
            return ModuleState.write(f.getAbsolutePath(), "1");
        } else {
            return !f.exists() || f.delete();
        }
    }

    private static void attachDownloadButton(final Activity act) {
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
                lp.bottomMargin = SettingsUi.dp(act, 100);
                lp.rightMargin = SettingsUi.dp(act, 16);

                Button btn = new Button(act);
                btn.setText("提取无水印视频");
                btn.setTextSize(12);
                btn.getPaint().setFakeBoldText(true);
                btn.setAlpha(0.85f);
                btn.setOnClickListener(v -> extractAndSaveFinderVideo(act));

                decor.addView(btn, lp);
            } catch (Throwable t) {
                KernelLog.debug("attachFinderButton err " + t);
            }
        });
    }

    private static void extractAndSaveFinderVideo(final Activity act) {
        new Thread(() -> {
            try {
                File video = MediaExportHelper.findLatestFile(act.getFilesDir(), 200000L, ".mp4", "finder", "video");
                if (video == null) {
                    video = MediaExportHelper.findLatestFile(act.getCacheDir(), 200000L, ".mp4", "finder", "video");
                }
                if (video == null) {
                    video = MediaExportHelper.findLatestFile(act.getExternalCacheDir(), 200000L, ".mp4", "finder", "video");
                }

                if (video == null || !video.exists()) {
                    showToast(act, "未定位到正在播放的视频号本地视频流，请等待视频缓冲完毕后重试");
                    return;
                }

                String saved = MediaExportHelper.saveFile(
                        act,
                        video,
                        MediaExportHelper.Kind.VIDEO,
                        "Finder_" + System.currentTimeMillis() + ".mp4",
                        "video/mp4"
                );
                showToast(act, "视频号无水印原视频已保存至相册:\n" + saved);
            } catch (Throwable t) {
                KernelLog.debug("extractFinderVideo err " + t);
                showToast(act, "保存视频失败: " + t.getMessage());
            }
        }, "wxentry-finder-save").start();
    }

    private static void showToast(final Activity act, final String msg) {
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
    }
}
