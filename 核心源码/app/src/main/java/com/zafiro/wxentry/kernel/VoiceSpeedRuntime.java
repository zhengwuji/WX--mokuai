package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileInputStream;
import org.json.JSONObject;

/**
 * Voice playback speed controller and sticker / emoji gallery saver:
 * 1) Adjusts AudioTrack / MediaPlayer playback speed (1.25x ~ 2.0x) for voice messages
 * 2) Long-press any custom emoji / sticker in panel to save as GIF / PNG to album
 */
final class VoiceSpeedRuntime {
    private static final String CONFIG_FILE = "voicespeed.config.json";
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Config {
        boolean speedEnabled = false;
        float speed = 1.5f; // 1.25f, 1.5f, 1.75f, 2.0f
        boolean emojiSaveEnabled = true;
    }

    private VoiceSpeedRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            // 1. Hook AudioTrack.play() and MediaPlayer.start() for voice playback speed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                XposedBridge.hookAllMethods(AudioTrack.class, "play", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Kernel.MODULE_OFF) return;
                        Config c = load();
                        if (!c.speedEnabled) return;
                        try {
                            AudioTrack track = (AudioTrack) p.thisObject;
                            PlaybackParams params = track.getPlaybackParams();
                            if (params == null) params = new PlaybackParams();
                            params.setSpeed(c.speed);
                            track.setPlaybackParams(params);
                            KernelLog.debug("AudioTrack speed set: " + c.speed + "x");
                        } catch (Throwable ignored) {}
                    }
                });

                XposedBridge.hookAllMethods(MediaPlayer.class, "start", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Kernel.MODULE_OFF) return;
                        Config c = load();
                        if (!c.speedEnabled) return;
                        try {
                            MediaPlayer mp = (MediaPlayer) p.thisObject;
                            PlaybackParams params = mp.getPlaybackParams();
                            if (params == null) params = new PlaybackParams();
                            params.setSpeed(c.speed);
                            mp.setPlaybackParams(params);
                            KernelLog.debug("MediaPlayer speed set: " + c.speed + "x");
                        } catch (Throwable ignored) {}
                    }
                });
            }

            // 2. Hook emoji long-press in panel (`ow5.e.a`) for custom sticker gallery saving
            try {
                Class<?> ow5e = DexResolver.load(cl, "ow5.e");
                if (ow5e != null) {
                    XposedBridge.hookAllMethods(ow5e, "a", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (Kernel.MODULE_OFF || p.args == null || p.args.length < 2) return;
                            Config c = load();
                            if (!c.emojiSaveEnabled) return;
                            Object modelVal = p.args[1];
                            View v = p.args[0] instanceof View ? (View) p.args[0] : null;
                            if (v == null || !v.isEnabled() || modelVal == null) return;
                            if (handleEmojiSavePrompt(v, modelVal)) {
                                p.setResult(Boolean.TRUE);
                            }
                        }
                    });
                }
            } catch (Throwable ignored) {}

            started = true;
            Kernel.T("VOICESPEED ready");
        } catch (Throwable t) {
            Kernel.T("VOICESPEED install-error " + t.getClass().getSimpleName());
        }
    }

    private static Activity findActivity(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static boolean handleEmojiSavePrompt(final View anchor, final Object modelVal) {
        try {
            Object emojiInfo = null;
            try { emojiInfo = XposedHelpers.getObjectField(modelVal, "b"); } catch (Throwable ignored) {}
            if (emojiInfo == null) {
                try { emojiInfo = XposedHelpers.getObjectField(modelVal, "a"); } catch (Throwable ignored) {}
            }
            if (emojiInfo == null) emojiInfo = modelVal;

            // Leave dice / RPS to GameEmojiRuntime when game control is enabled
            if (GameEmojiRuntime.ready && GameEmojiRuntime.kind(emojiInfo) != null && GameEmojiStore.enabled()) {
                return false;
            }

            String md5 = null;
            try { md5 = (String) XposedHelpers.callMethod(emojiInfo, "getMd5"); } catch (Throwable ignored) {}
            if (md5 == null || md5.isEmpty() || md5.equals("null")
                    || md5.equals("08f223fa83f1ca34e143d1e580252c7c")
                    || md5.equals("9bd1281af3a31710a45b84d736363691")) {
                return false;
            }

            final Activity act = findActivity(anchor.getContext());
            if (act == null || act.isFinishing() || act.isDestroyed()) return false;
            final Object finalEmoji = emojiInfo;
            final String finalMd5 = md5;

            anchor.post(() -> FeatureMenuUi.builder(act)
                    .setTitle("自定义表情操作")
                    .setMessage("MD5: " + finalMd5.substring(0, Math.min(12, finalMd5.length())) + "...\n是否将该表情原图/动图保存到系统相册？")
                    .setPositiveButton("保存到相册", (d, which) -> saveEmojiToGallery(act, finalEmoji, finalMd5))
                    .setNegativeButton("取消", null)
                    .show());
            return true;
        } catch (Throwable t) {
            KernelLog.debug("handleEmojiSavePrompt err " + t);
            return false;
        }
    }

    private static void saveEmojiToGallery(Activity act, Object emojiInfo, String md5) {
        try {
            String path = null;
            for (String method : new String[]{"getContentPath", "getCoverPath", "j", "k"}) {
                try {
                    Object res = XposedHelpers.callMethod(emojiInfo, method);
                    if (res instanceof String && new File((String) res).exists()) {
                        path = (String) res;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (path == null) {
                File emojiDir = new File(act.getFilesDir(), "emoji");
                if (emojiDir.exists()) {
                    File target = MediaExportHelper.findLatestFile(emojiDir, 100L, md5.toLowerCase());
                    if (target != null && target.exists()) path = target.getAbsolutePath();
                }
            }

            if (path == null || !new File(path).exists()) {
                Toast.makeText(act, "未找到该表情的本地缓存文件", Toast.LENGTH_SHORT).show();
                return;
            }

            File src = new File(path);
            boolean gif = path.endsWith(".gif") || isGifHeader(src);
            String ext = gif ? ".gif" : ".png";
            String mime = gif ? "image/gif" : "image/png";
            String saved = MediaExportHelper.saveFile(act, src, MediaExportHelper.Kind.IMAGE, "Emoji_" + md5 + ext, mime);
            Toast.makeText(act, "表情已成功保存至相册:\n" + saved, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(act, "保存表情失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isGifHeader(File f) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] header = new byte[3];
            int read = fis.read(header);
            return read == 3 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F';
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
        }
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
                    c.speedEnabled = j.optBoolean("speedEnabled", false);
                    c.speed = (float) j.optDouble("speed", 1.5);
                    c.emojiSaveEnabled = j.optBoolean("emojiSaveEnabled", true);
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("VoiceSpeed load config err " + t);
        }
        cachedConfig = c;
        cachedAt = now;
        return c;
    }

    static boolean save(Config c) {
        if (c == null) return false;
        try {
            JSONObject j = new JSONObject();
            j.put("speedEnabled", c.speedEnabled);
            j.put("speed", (double) c.speed);
            j.put("emojiSaveEnabled", c.emojiSaveEnabled);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString(2));
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            KernelLog.debug("VoiceSpeed save config err " + t);
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

        TextView intro = FeatureMenuUi.text(act, "语音倍速与表情原图", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "播放语音消息时自动应用加速，长按表情面板中的自定义表情一键保存到系统相册。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        root.addView(FeatureMenuUi.toggle(act, "开启语音倍速播放", "播放聊天语音消息时自动加速，告别又长又慢的语音", cfg.speedEnabled, on -> {
            cfg.speedEnabled = on;
            save(cfg);
            return true;
        }));

        TextView speedTitle = FeatureMenuUi.text(act, "倍速档位选择", 14, FeatureMenuUi.Tone.INK);
        speedTitle.getPaint().setFakeBoldText(true);
        speedTitle.setPadding(0, SettingsUi.dp(act, 14), 0, SettingsUi.dp(act, 6));
        root.addView(speedTitle);

        final RadioGroup rg = new RadioGroup(act);
        rg.setOrientation(LinearLayout.HORIZONTAL);

        final float[] speeds = { 1.25f, 1.5f, 1.75f, 2.0f };
        for (float s : speeds) {
            RadioButton rb = new RadioButton(act);
            rb.setText(s + "x");
            rb.setId((int) (s * 100));
            if (Math.abs(cfg.speed - s) < 0.05f) rb.setChecked(true);
            rg.addView(rb);
        }
        root.addView(rg);

        root.addView(FeatureMenuUi.toggle(act, "长按表情保存到相册", "长按面板中的自定义表情可弹出保存确认，一键转存为 GIF/PNG", cfg.emojiSaveEnabled, on -> {
            cfg.emojiSaveEnabled = on;
            save(cfg);
            return true;
        }));

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("完成", (d, which) -> {
                    int checkedId = rg.getCheckedRadioButtonId();
                    if (checkedId > 0) cfg.speed = checkedId / 100.0f;
                    save(cfg);
                    Toast.makeText(act, "语音倍速与表情设置已更新", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
    }
}
