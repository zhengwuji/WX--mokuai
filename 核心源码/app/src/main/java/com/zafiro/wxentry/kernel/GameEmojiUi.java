package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

final class GameEmojiUi {
    static java.lang.ref.WeakReference<AlertDialog> shown = new java.lang.ref.WeakReference<>(null);

    interface AskCallback {
        void onPick(int outcome); // 0 = random native roll, >0 = chosen value
    }

    static Activity activity(Context context) {
        for (int i = 0; i < 8 && context != null; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    static void toast(Context context, String text) {
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        }
    }

    static void showAskPicker(final Activity act, final GameEmojiData.Kind kind, final AskCallback cb) {
        if (act == null || act.isFinishing() || act.isDestroyed() || cb == null) return;
        LinearLayout box = SettingsUi.column(act);
        box.addView(SettingsUi.text(act, "点击下方按钮立即发出对应点数，点外部或取消则不发送：", 13));

        LinearLayout row = new LinearLayout(act);
        row.setPadding(0, SettingsUi.dp(act, 10), 0, SettingsUi.dp(act, 6));
        final AlertDialog[] dlgRef = new AlertDialog[1];
        for (int i = 1; i <= kind.max; i++) {
            final int val = i;
            Button b = new Button(act);
            b.setText(GameEmojiData.label(kind, i));
            b.setTextSize(15);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(0, 0, 0, 0);
            b.setOnClickListener(v -> {
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                cb.onPick(val);
            });
            row.addView(b, new LinearLayout.LayoutParams(0, SettingsUi.dp(act, 48), 1));
        }
        box.addView(row);

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("选择本次" + kind.label)
                .setView(box)
                .setNeutralButton("按默认规则/随机", (d, w) -> cb.onPick(0))
                .setNegativeButton("取消", null)
                .create();
        dlgRef[0] = dialog;
        dialog.show();
    }

    static void show(final View anchor, final GameEmojiData.Kind kind) {
        show(activity(anchor != null ? anchor.getContext() : null), kind);
    }

    static void show(final Activity act, final GameEmojiData.Kind kind) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        AlertDialog previous = shown.get();
        if (previous != null && previous.isShowing()) return;
        try {
            if (!GameEmojiStore.enabled()) {
                GameEmojiStore.setEnabled(true);
            }
            final GameEmojiData data = GameEmojiStore.current();
            final String activeTalker = Kernel.currentChatTalker();
            final boolean hasTalker = activeTalker != null && !activeTalker.isEmpty();
            final String talkerLabel = hasTalker ? RecallArchiveRuntime.resolveDisplayName(null, activeTalker) : "";

            LinearLayout box = SettingsUi.column(act);
            box.addView(SettingsUi.text(act, "支持全局设置、按当前会话独立控制，以及发送前弹窗选点。", 13));

            final SettingsUi.ToggleRow askRow = SettingsUi.toggle(act, "发送前弹窗选择本次点数",
                    "开启后点击聊天表情面板中的" + kind.label + "将先弹窗选点", data.askBeforeSend, on -> true);
            box.addView(askRow);

            final Spinner scopeSpinner = new Spinner(act);
            final GameEmojiData.ChatMode[] selectedMode = new GameEmojiData.ChatMode[]{GameEmojiData.ChatMode.INHERIT};
            if (hasTalker) {
                SettingsUi.heading(box, "作用范围");
                String disp = talkerLabel.isEmpty() ? activeTalker : talkerLabel;
                String[] scopeItems = new String[]{
                        "全局默认配置",
                        "仅当前会话（" + disp + "）：独立自定义",
                        "仅当前会话（" + disp + "）：禁用控制",
                        "仅当前会话（" + disp + "）：继承全局"
                };
                scopeSpinner.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, scopeItems));
                GameEmojiData.ChatRule existing = data.chatRule(activeTalker);
                int initPos = 0;
                if (existing != null) {
                    selectedMode[0] = existing.mode;
                    if (existing.mode == GameEmojiData.ChatMode.CUSTOM) initPos = 1;
                    else if (existing.mode == GameEmojiData.ChatMode.DISABLE) initPos = 2;
                }
                scopeSpinner.setSelection(initPos);
                box.addView(scopeSpinner, new LinearLayout.LayoutParams(-1, -2));
            }

            SettingsUi.heading(box, "默认结果");
            final Spinner fallback = new Spinner(act);
            String[] labels = new String[kind.max + 1];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = GameEmojiData.label(kind, i);
            }
            fallback.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, labels));
            box.addView(fallback, new LinearLayout.LayoutParams(-1, -2));

            SettingsUi.heading(box, "后续结果（按顺序发送）");
            final TextView count = SettingsUi.text(act, "", 13);
            box.addView(count);

            final EditText sequence = new EditText(act);
            sequence.setTextSize(16);
            sequence.setHint(kind == GameEmojiData.Kind.DICE ? "例如：6, 1, 3, 6" : "例如：石头, 剪刀, 布");
            sequence.setMinLines(2);
            sequence.setMaxLines(5);
            sequence.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            box.addView(sequence, new LinearLayout.LayoutParams(-1, -2));

            final Runnable loadSlotUi = () -> {
                int pos = hasTalker ? scopeSpinner.getSelectedItemPosition() : 0;
                GameEmojiData.Slot slot;
                boolean editable = true;
                if (pos == 1 && hasTalker) {
                    slot = data.ensureChatRule(activeTalker).slot(kind);
                } else if (pos == 2 || pos == 3) {
                    slot = data.slot(kind);
                    editable = false;
                } else {
                    slot = data.slot(kind);
                }
                fallback.setEnabled(editable);
                sequence.setEnabled(editable);
                fallback.setSelection(slot.fallback);
                sequence.setText(GameEmojiData.display(kind, slot.next));
                count.setText(editable ? ("共 " + slot.next.size() + " 次，最多 256 次")
                        : (pos == 2 ? "当前会话已设为禁用控制（恢复原生随机）" : "当前会话将继承上方全局配置"));
            };

            sequence.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (!sequence.isEnabled()) return;
                    try {
                        count.setText("共 " + GameEmojiData.parse(kind, s.toString()).size() + " 次，最多 256 次");
                    } catch (Throwable invalid) {
                        count.setText("请检查结果格式");
                    }
                }
                public void afterTextChanged(Editable e) {}
            });

            if (hasTalker) {
                scopeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    public void onItemSelected(AdapterView<?> p, View v, int position, long id) { loadSlotUi.run(); }
                    public void onNothingSelected(AdapterView<?> p) {}
                });
            }
            loadSlotUi.run();

            LinearLayout quick = new LinearLayout(act);
            for (int i = 1; i <= kind.max; i++) {
                final int value = i;
                Button b = new Button(act);
                b.setText(GameEmojiData.label(kind, i));
                b.setTextSize(14);
                b.setMinWidth(0);
                b.setMinimumWidth(0);
                b.setPadding(0, 0, 0, 0);
                quick.addView(b, new LinearLayout.LayoutParams(0, SettingsUi.dp(act, 44), 1));
                b.setOnClickListener(v -> {
                    if (!sequence.isEnabled()) return;
                    String old = sequence.getText().toString().trim();
                    sequence.setText(old + (old.isEmpty() ? "" : ", ") + GameEmojiData.label(kind, value));
                    sequence.setSelection(sequence.length());
                });
            }
            box.addView(quick);
            box.addView(SettingsUi.text(act, "点击上方快捷按钮追加结果，也可手动输入并用逗号隔开。留空则采用上方默认结果。", 12));

            ScrollView scroll = new ScrollView(act);
            scroll.addView(box);

            final AlertDialog dialog = new AlertDialog.Builder(act)
                    .setTitle(kind.label + "结果设置")
                    .setView(scroll)
                    .setNegativeButton("取消", null)
                    .setNeutralButton("清空序列", null)
                    .setPositiveButton("保存", null)
                    .create();
            shown = new java.lang.ref.WeakReference<>(dialog);
            dialog.show();

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (sequence.isEnabled()) sequence.setText("");
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String curOwner = GameEmojiStore.owner();
                    GameEmojiStore.setAskBeforeSend(askRow.control.isChecked());
                    int scopePos = hasTalker ? scopeSpinner.getSelectedItemPosition() : 0;
                    if (scopePos == 0) {
                        List<Integer> values = GameEmojiData.parse(kind, sequence.getText().toString());
                        if (!GameEmojiStore.configure(curOwner, kind, fallback.getSelectedItemPosition(), values)) {
                            throw new IllegalStateException("设置保存失败");
                        }
                        dialog.dismiss();
                        toast(act, "已保存全局" + kind.label + "：" + GameEmojiData.label(kind, fallback.getSelectedItemPosition())
                                + (values.isEmpty() ? "" : "，后续 " + values.size() + " 次"));
                    } else if (scopePos == 1) {
                        List<Integer> values = GameEmojiData.parse(kind, sequence.getText().toString());
                        if (!GameEmojiStore.configureChat(curOwner, activeTalker, GameEmojiData.ChatMode.CUSTOM,
                                kind, fallback.getSelectedItemPosition(), values)) {
                            throw new IllegalStateException("设置保存失败");
                        }
                        dialog.dismiss();
                        toast(act, "已保存当前会话" + kind.label + "独立规则");
                    } else if (scopePos == 2) {
                        if (!GameEmojiStore.configureChat(curOwner, activeTalker, GameEmojiData.ChatMode.DISABLE, kind, 0, java.util.Collections.emptyList())) {
                            throw new IllegalStateException("设置保存失败");
                        }
                        dialog.dismiss();
                        toast(act, "已在当前会话禁用" + kind.label + "控制");
                    } else {
                        if (!GameEmojiStore.configureChat(curOwner, activeTalker, GameEmojiData.ChatMode.INHERIT, kind, 0, java.util.Collections.emptyList())) {
                            throw new IllegalStateException("设置保存失败");
                        }
                        dialog.dismiss();
                        toast(act, "当前会话已恢复继承全局设置");
                    }
                    GameEmojiRuntime.configured(curOwner, kind);
                } catch (Throwable failure) {
                    toast(act, failure.getMessage() == null ? "设置保存失败" : failure.getMessage());
                }
            });
        } catch (Throwable failure) {
            toast(act, "结果设置暂不可用: " + failure.getMessage());
            Kernel.T("GAMEEMOJI settings-error " + failure.getClass().getSimpleName());
        }
    }
}
