package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Local chat history exporter:
 * Exports messages for any contact or chatroom to `Download/WechatXposed/`
 * as human-readable UTF-8 TXT using Scoped Storage / MediaStore.
 */
final class ChatExportRuntime {
    private ChatExportRuntime() {}

    static void showExportDialog(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 18);
        layout.setPadding(pad, pad, pad, pad);

        TextView intro = FeatureMenuUi.text(act, "聊天记录本地导出", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        layout.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "将指定联系人或群聊的本地全部聊天记录导出为纯文本 (TXT) 文件，保存在系统的 Download/WechatXposed 目录下。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        layout.addView(desc);

        TextView targetLabel = FeatureMenuUi.text(act, "导出目标 wxid 或群聊 ID (留空默认导出当前会话):", 13, FeatureMenuUi.Tone.INK);
        final EditText targetEdit = new EditText(act);
        targetEdit.setText(Kernel.CUR_TALKER != null ? Kernel.CUR_TALKER : "");
        targetEdit.setHint("例: filehelper 或 wxid_xxx 或 xxx@chatroom");
        layout.addView(targetLabel);
        layout.addView(targetEdit);

        FeatureMenuUi.builder(act)
                .setView(layout)
                .setPositiveButton("开始导出", (d, which) -> {
                    String talker = targetEdit.getText().toString().trim();
                    if (talker.isEmpty()) talker = Kernel.CUR_TALKER;
                    if (talker == null || talker.isEmpty()) {
                        Toast.makeText(act, "请输入有效的导出对象 wxid 或群 ID", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    exportTalkerHistory(act, talker);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void exportTalkerHistory(final Activity act, final String talker) {
        new Thread(() -> {
            try {
                Object db = Kernel.db0();
                if (db == null) {
                    showToast(act, "本地消息数据库未连接，请先登录账号");
                    return;
                }

                Cursor c = Kernel.rawQuery(
                        db,
                        "select createTime, isSend, type, content from message where talker = ? order by createTime asc",
                        new String[]{talker}
                );

                if (c == null) {
                    showToast(act, "未找到该对象的聊天记录");
                    return;
                }

                SimpleDateFormat timeFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                StringBuilder out = new StringBuilder();
                int count = 0;

                try {
                    out.append("=========================================\n");
                    out.append("WeChat 聊天记录导出 - ").append(talker).append('\n');
                    out.append("导出时间: ").append(timeFmt.format(new Date())).append('\n');
                    out.append("=========================================\n\n");

                    while (c.moveToNext()) {
                        long time = c.getLong(0);
                        int isSend = c.getInt(1);
                        int type = c.getInt(2);
                        String content = c.getString(3);

                        String sender = isSend == 1 ? "【我】" : "【对方】";
                        String timeStr = timeFmt.format(new Date(time));
                        out.append('[').append(timeStr).append("] ").append(sender)
                                .append(" (type ").append(type).append("):\n");
                        out.append(content != null ? content : "[空消息]").append("\n\n");
                        count++;
                    }
                } finally {
                    c.close();
                }

                String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String safeName = talker.replaceAll("[^a-zA-Z0-9_@.-]", "_");
                String fileName = "ChatExport_" + safeName + "_" + dateStr + ".txt";
                final String savedPath = MediaExportHelper.saveBytes(
                        act,
                        out.toString().getBytes("UTF-8"),
                        MediaExportHelper.Kind.DOWNLOAD,
                        fileName,
                        "text/plain"
                );

                final int finalCount = count;
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    FeatureMenuUi.builder(act)
                            .setTitle("导出成功")
                            .setMessage("已成功导出 " + finalCount + " 条记录！\n\n文件保存至:\n" + savedPath)
                            .setPositiveButton("复制文件路径", (d, which) -> {
                                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("ChatExportPath", savedPath));
                                    Toast.makeText(act, "文件路径已复制", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                });
            } catch (Throwable t) {
                KernelLog.debug("exportTalkerHistory err " + t);
                showToast(act, "导出失败: " + t.getMessage());
            }
        }, "wxentry-chat-export").start();
    }

    private static void showToast(final Activity act, final String msg) {
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
    }
}
