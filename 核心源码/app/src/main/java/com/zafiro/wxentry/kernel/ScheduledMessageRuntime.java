package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.HandlerThread;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scheduled and recurring message dispatcher:
 * 1) Queues one-shot or daily recurring messages for any contact or chatroom
 * 2) Sends via DexResolver.sendTextMessage when target wall-clock time is reached
 * 3) Stores task queue in `scheduled.config.json`
 */
final class ScheduledMessageRuntime {
    private static final String CONFIG_FILE = "scheduled.config.json";
    private static volatile ClassLoader appCl;
    private static volatile boolean started;
    private static HandlerThread workerThread;
    private static Handler workerHandler;

    static final class TaskItem {
        String id;
        String talker;
        String content;
        int mode; // 0 = 单次, 1 = 每日固定时间
        String dailyTime; // "08:30"
        long runAtTimestamp;
        boolean executed;
    }

    private ScheduledMessageRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        appCl = cl;
        if (started) return;
        workerThread = new HandlerThread("wxentry-scheduler");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        scheduleNextCheck();
        started = true;
        Kernel.T("SCHEDULED ready");
    }

    private static void scheduleNextCheck() {
        if (workerHandler == null) return;
        workerHandler.postDelayed(() -> {
            checkAndDispatch();
            scheduleNextCheck();
        }, 30000L); // Check every 30 seconds
    }

    private static synchronized void checkAndDispatch() {
        if (Kernel.MODULE_OFF || appCl == null) return;
        List<TaskItem> list = loadTasks();
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        int curHour = cal.get(Calendar.HOUR_OF_DAY);
        int curMin = cal.get(Calendar.MINUTE);
        String curTimeStr = String.format(Locale.getDefault(), "%02d:%02d", curHour, curMin);

        boolean changed = false;
        for (TaskItem task : list) {
            if (task.executed && task.mode == 0) continue;

            boolean shouldRun = false;
            if (task.mode == 0) {
                if (now >= task.runAtTimestamp) {
                    shouldRun = true;
                    task.executed = true;
                    changed = true;
                }
            } else if (task.mode == 1) {
                // Daily recurring check
                if (curTimeStr.equals(task.dailyTime)) {
                    // Check if already ran in the last 60 seconds
                    if (now - task.runAtTimestamp > 70000L) {
                        shouldRun = true;
                        task.runAtTimestamp = now;
                        changed = true;
                    }
                }
            }

            if (shouldRun) {
                try {
                    boolean ok = DexResolver.sendTextMessage(appCl, task.talker, task.content);
                    KernelLog.debug("Scheduled msg sent to " + task.talker + " ok=" + ok + ": " + task.content);
                } catch (Throwable t) {
                    KernelLog.debug("Scheduled msg send err: " + t);
                }
            }
        }

        if (changed) {
            saveTasks(list);
        }
    }

    static synchronized List<TaskItem> loadTasks() {
        List<TaskItem> result = new ArrayList<>();
        try {
            File f = new File(Kernel.WXDIR + CONFIG_FILE);
            if (!f.exists()) return result;
            String raw = ModuleState.read(f.getAbsolutePath());
            if (raw == null || raw.isEmpty()) return result;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                TaskItem item = new TaskItem();
                item.id = j.optString("id", String.valueOf(System.currentTimeMillis()));
                item.talker = j.optString("talker", "");
                item.content = j.optString("content", "");
                item.mode = j.optInt("mode", 0);
                item.dailyTime = j.optString("dailyTime", "08:00");
                item.runAtTimestamp = j.optLong("runAtTimestamp", 0L);
                item.executed = j.optBoolean("executed", false);
                if (!item.talker.isEmpty()) result.add(item);
            }
        } catch (Throwable t) {
            KernelLog.debug("loadTasks err " + t);
        }
        return result;
    }

    static synchronized boolean saveTasks(List<TaskItem> list) {
        try {
            JSONArray arr = new JSONArray();
            for (TaskItem item : list) {
                JSONObject j = new JSONObject();
                j.put("id", item.id);
                j.put("talker", item.talker);
                j.put("content", item.content);
                j.put("mode", item.mode);
                j.put("dailyTime", item.dailyTime);
                j.put("runAtTimestamp", item.runAtTimestamp);
                j.put("executed", item.executed);
                arr.put(j);
            }
            return ModuleState.write(Kernel.WXDIR + CONFIG_FILE, arr.toString(2));
        } catch (Throwable t) {
            KernelLog.debug("saveTasks err " + t);
            return false;
        }
    }

    static void showConfig(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final List<TaskItem> tasks = loadTasks();

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 18);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = FeatureMenuUi.text(act, "定时消息与周期打卡", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "设定单次定时或每日定时发送消息。到达设定时间后由模块内核自动发送。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        // Task list
        if (tasks.isEmpty()) {
            TextView empty = FeatureMenuUi.text(act, "暂无定时任务，点击下方按钮添加", 14, FeatureMenuUi.Tone.MUTED);
            empty.setPadding(0, pad, 0, pad);
            root.addView(empty);
        } else {
            for (final TaskItem item : tasks) {
                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                MenuPalette pal = MenuPalette.current(act);
                android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                cardBg.setColor(pal.card);
                cardBg.setCornerRadius(SettingsUi.dp(act, 10));
                cardBg.setStroke(SettingsUi.dp(act, 1), pal.line);
                card.setBackground(cardBg);
                card.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, SettingsUi.dp(act, 8));
                card.setLayoutParams(lp);

                String modeLabel = item.mode == 1 ? "每日固定 " + item.dailyTime : "单次 (" + (item.executed ? "已完成" : "等待执行") + ")";
                TextView title = FeatureMenuUi.text(act, "对象: " + item.talker + " [" + modeLabel + "]", 14, FeatureMenuUi.Tone.INK);
                title.getPaint().setFakeBoldText(true);
                card.addView(title);

                TextView body = FeatureMenuUi.text(act, "内容: " + item.content, 13, FeatureMenuUi.Tone.MUTED);
                body.setPadding(0, SettingsUi.dp(act, 4), 0, SettingsUi.dp(act, 8));
                card.addView(body);

                Button delBtn = new Button(act);
                delBtn.setText("删除任务");
                delBtn.setTextSize(12);
                delBtn.setOnClickListener(v -> {
                    tasks.remove(item);
                    saveTasks(tasks);
                    Toast.makeText(act, "已删除任务", Toast.LENGTH_SHORT).show();
                    showConfig(act);
                });
                card.addView(delBtn);
                root.addView(card);
            }
        }

        Button addBtn = new Button(act);
        addBtn.setText("+ 新增定时任务");
        addBtn.setOnClickListener(v -> showAddTaskDialog(act));
        root.addView(addBtn);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();
    }

    private static void showAddTaskDialog(final Activity act) {
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 16);
        layout.setPadding(pad, pad, pad, pad);

        TextView tLabel = FeatureMenuUi.text(act, "接收对象 wxid 或群聊 ID (留空默认发给当前会话):", 13, FeatureMenuUi.Tone.INK);
        final EditText tEdit = new EditText(act);
        tEdit.setHint("例: filehelper 或 wxid_xxx 或 xxx@chatroom");
        layout.addView(tLabel);
        layout.addView(tEdit);

        TextView cLabel = FeatureMenuUi.text(act, "消息内容:", 13, FeatureMenuUi.Tone.INK);
        final EditText cEdit = new EditText(act);
        cEdit.setHint("输入要定时发送的文本...");
        layout.addView(cLabel);
        layout.addView(cEdit);

        final RadioGroup rg = new RadioGroup(act);
        rg.setOrientation(LinearLayout.VERTICAL);

        RadioButton rbDaily = new RadioButton(act);
        rbDaily.setText("每日定时 (例: 08:30 自动早安/打卡)");
        rbDaily.setId(301);
        rbDaily.setChecked(true);
        rg.addView(rbDaily);

        RadioButton rbDelay = new RadioButton(act);
        rbDelay.setText("延迟 N 分钟后单次发送");
        rbDelay.setId(302);
        rg.addView(rbDelay);
        layout.addView(rg);

        final EditText timeEdit = new EditText(act);
        timeEdit.setText("08:30");
        timeEdit.setHint("格式: HH:mm (24小时制)");
        layout.addView(timeEdit);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == 301) {
                timeEdit.setText("08:30");
                timeEdit.setHint("格式: HH:mm (24小时制)");
            } else {
                timeEdit.setText("10");
                timeEdit.setHint("延迟分钟数 (例: 10)");
            }
        });

        FeatureMenuUi.builder(act)
                .setTitle("新增定时消息")
                .setView(layout)
                .setPositiveButton("确认添加", (d, which) -> {
                    String talker = tEdit.getText().toString().trim();
                    if (talker.isEmpty()) talker = Kernel.CUR_TALKER != null ? Kernel.CUR_TALKER : "filehelper";
                    String text = cEdit.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(act, "消息内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    TaskItem item = new TaskItem();
                    item.id = String.valueOf(System.currentTimeMillis());
                    item.talker = talker;
                    item.content = text;

                    if (rg.getCheckedRadioButtonId() == 301) {
                        item.mode = 1;
                        item.dailyTime = timeEdit.getText().toString().trim();
                    } else {
                        item.mode = 0;
                        int mins = 5;
                        try { mins = Integer.parseInt(timeEdit.getText().toString().trim()); } catch (Throwable ignored) {}
                        item.runAtTimestamp = System.currentTimeMillis() + mins * 60000L;
                    }

                    List<TaskItem> list = loadTasks();
                    list.add(item);
                    saveTasks(list);
                    Toast.makeText(act, "定时任务已添加", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
