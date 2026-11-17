package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-risk silent zombie/one-way friend scanner:
 * Scans local `message` records for WeChat friend-verification / rejection system notices
 * alongside `rcontact` personal friend counts, without sending network packets or creating groups.
 */
final class GhostFriendRuntime {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean started;

    private GhostFriendRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        started = true;
        Kernel.T("GHOSTFRIEND ready");
    }

    static void showDetectionDialog(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 18);
        layout.setPadding(pad, pad, pad, pad);

        TextView intro = FeatureMenuUi.text(act, "单向好友 (僵尸粉) 本地无打扰排查", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        layout.addView(intro);

        TextView desc = FeatureMenuUi.text(act,
                "【零风险无打扰说明】\n"
                        + "自动检索本地通讯录 (`rcontact`) 与聊天系统提示记录 (`message`)，精准汇总曾触发「开启了朋友验证 / 被对方拒收」的单向好友。\n\n"
                        + "• 全程纯本地检索，不建群、不发消息、不发测试包\n"
                        + "• 零封号风险，对方完全无感知\n"
                        + "• 支持一键复制异常好友及通讯录名单",
                13, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 8), 0, SettingsUi.dp(act, 16));
        layout.addView(desc);

        FeatureMenuUi.builder(act)
                .setView(layout)
                .setPositiveButton("立即排查", (d, which) -> runDetection(act))
                .setNegativeButton("取消", null)
                .show();
    }

    private static final class ScanResult {
        final List<String> abnormal = new ArrayList<>();
        final List<String> allFriends = new ArrayList<>();
    }

    private static void runDetection(final Activity act) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage("正在排查通讯录与本地异常会话...");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            final ScanResult res = performLocalScan();
            MAIN.post(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (pd.isShowing()) pd.dismiss();
                if (res.allFriends.isEmpty() && res.abnormal.isEmpty()) {
                    Toast.makeText(act, "通讯录读取为空或数据库尚未就绪，请稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                showResultSummary(act, res);
            });
        }, "wxentry-ghost-scan").start();
    }

    private static ScanResult performLocalScan() {
        ScanResult res = new ScanResult();
        try {
            Object db = Kernel.db0();
            if (db == null) return res;

            Map<String, String> displayNames = new LinkedHashMap<>();
            Cursor c = Kernel.rawQuery(
                    db,
                    "select username, nickname, conRemark from rcontact where type & 1 != 0 and verifyFlag = 0 and username not like '%@%' and username not in ('filehelper','fmessage') limit 500",
                    null
            );
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String u = c.getString(0);
                        String nick = c.getString(1);
                        String remark = c.getString(2);
                        if (u == null || u.isEmpty()) continue;
                        String label = (remark != null && !remark.isEmpty()) ? remark
                                : (nick != null && !nick.isEmpty() ? nick : u);
                        displayNames.put(u, label);
                        res.allFriends.add(label + " [" + u + "]");
                    }
                } finally {
                    c.close();
                }
            }

            // Scan message table for known deleted/blocked system tips
            Cursor mc = Kernel.rawQuery(
                    db,
                    "select distinct talker, content from message where (content like '%开启了朋友验证%' or content like '%你还不是他%' or content like '%你还不是她%' or content like '%被对方拒收%' or content like '%friend verification%') and talker not like '%@chatroom%' order by createTime desc limit 100",
                    null
            );
            if (mc != null) {
                try {
                    Map<String, String> seen = new LinkedHashMap<>();
                    while (mc.moveToNext()) {
                        String talker = mc.getString(0);
                        String content = mc.getString(1);
                        if (talker == null || talker.isEmpty() || seen.containsKey(talker)) continue;
                        String reason = (content != null && content.contains("拒收")) ? "疑似拉黑" : "疑似已删好友";
                        String name = displayNames.containsKey(talker) ? displayNames.get(talker) : talker;
                        seen.put(talker, reason);
                        res.abnormal.add("• " + name + " (" + talker + ") — " + reason);
                    }
                } finally {
                    mc.close();
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("performLocalScan err " + t);
        }
        return res;
    }

    private static void showResultSummary(final Activity act, final ScanResult res) {
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 16);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = FeatureMenuUi.text(act,
                "排查完成：好友 " + res.allFriends.size() + " 人，异常会话 " + res.abnormal.size() + " 人",
                16, FeatureMenuUi.Tone.INK);
        title.getPaint().setFakeBoldText(true);
        layout.addView(title);

        StringBuilder body = new StringBuilder();
        if (res.abnormal.isEmpty()) {
            body.append("本地历史消息中未发现「开启朋友验证」或「消息被拒收」记录。\n");
        } else {
            body.append("发现以下曾触发删除/拉黑提示的联系人：\n\n");
            for (String line : res.abnormal) {
                body.append(line).append('\n');
            }
        }

        TextView tip = FeatureMenuUi.text(act, body.toString().trim(), 13, FeatureMenuUi.Tone.MUTED);
        tip.setPadding(0, SettingsUi.dp(act, 8), 0, SettingsUi.dp(act, 12));
        layout.addView(tip);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(layout);

        final String copyPayload;
        if (!res.abnormal.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String line : res.abnormal) sb.append(line).append('\n');
            copyPayload = sb.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            for (String f : res.allFriends) sb.append(f).append('\n');
            copyPayload = sb.toString();
        }

        FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton(res.abnormal.isEmpty() ? "复制好友名单" : "复制异常名单", (d, which) -> {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("GhostScan", copyPayload));
                        Toast.makeText(act, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
