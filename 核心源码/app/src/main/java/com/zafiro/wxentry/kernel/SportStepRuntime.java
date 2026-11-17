package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * WeChat Sport step count modifier:
 * 1) Intercepts hardware step counter sensor events (Sensor.TYPE_STEP_COUNTER = 19)
 * 2) Supports fixed target steps, random range, or multiplier amplification
 * 3) Persists configuration in `sport.config.json`
 */
final class SportStepRuntime {
    private static final String CONFIG_FILE = "sport.config.json";
    private static final Set<String> HOOKED_LISTENERS = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean started;
    private static volatile Config cachedConfig;
    private static volatile long cachedAt;

    static final class Config {
        boolean enabled = false;
        int mode = 0; // 0 = 固定步数, 1 = 随机步数, 2 = 倍率放大
        int targetSteps = 22888;
        int minSteps = 18000;
        int maxSteps = 28000;
        float multiplier = 3.0f;
    }

    private SportStepRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            // Hook SensorManager.registerListener to catch WeChat's sport step listener
            XposedBridge.hookAllMethods(SensorManager.class, "registerListener", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Kernel.MODULE_OFF || p.args == null || p.args.length < 2) return;
                    Object listenerObj = p.args[0];
                    Object sensorObj = p.args[1];
                    if (!(listenerObj instanceof SensorEventListener) || !(sensorObj instanceof Sensor)) return;
                    Sensor sensor = (Sensor) sensorObj;
                    if (sensor.getType() == Sensor.TYPE_STEP_COUNTER || sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
                        hookListenerClass(listenerObj.getClass());
                    }
                }
            });

            // Also try hooking known WeChat sport classes if present
            for (String clsName : new String[]{
                    "com.tencent.mm.plugin.sport.model.PushSportStepDetector",
                    "com.tencent.mm.plugin.sport.model.SportStepDetector",
                    "com.tencent.mm.plugin.sport.model.c0",
                    "com.tencent.mm.plugin.sport.model.a0"
            }) {
                try {
                    Class<?> c = DexResolver.load(cl, clsName);
                    if (c != null) {
                        hookListenerClass(c);
                    }
                } catch (Throwable ignored) {}
            }

            started = true;
            Kernel.T("SPORT ready");
        } catch (Throwable t) {
            Kernel.T("SPORT install-error " + t.getClass().getSimpleName());
        }
    }

    private static void hookListenerClass(Class<?> cls) {
        if (cls == null) return;
        String name = cls.getName();
        if (HOOKED_LISTENERS.contains(name)) return;
        HOOKED_LISTENERS.add(name);

        try {
            XposedBridge.hookAllMethods(cls, "onSensorChanged", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Kernel.MODULE_OFF || p.args == null || p.args.length == 0) return;
                    Object eventObj = p.args[0];
                    if (!(eventObj instanceof SensorEvent)) return;
                    SensorEvent event = (SensorEvent) eventObj;
                    if (event.sensor != null && (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER
                            || event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR)) {
                        applyStepModification(event);
                    }
                }
            });
            KernelLog.debug("Sport hooked listener: " + name);
        } catch (Throwable t) {
            KernelLog.debug("Sport hook listener err " + name + ": " + t);
        }
    }

    private static void applyStepModification(SensorEvent event) {
        Config c = load();
        if (!c.enabled || event.values == null || event.values.length == 0) return;
        try {
            float original = event.values[0];
            float modified = original;
            if (c.mode == 0) { // 固定步数
                modified = c.targetSteps;
            } else if (c.mode == 1) { // 随机范围
                int range = Math.max(1, c.maxSteps - c.minSteps);
                modified = c.minSteps + (int) (Math.random() * (range + 1));
            } else if (c.mode == 2) { // 倍率放大
                modified = original * Math.max(1.0f, c.multiplier);
            }
            event.values[0] = modified;
            KernelLog.debug("Sport step adjusted: " + original + " -> " + modified + " (mode " + c.mode + ")");
        } catch (Throwable t) {
            KernelLog.debug("Sport apply err " + t);
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
                    c.enabled = j.optBoolean("enabled", false);
                    c.mode = j.optInt("mode", 0);
                    c.targetSteps = j.optInt("targetSteps", 22888);
                    c.minSteps = j.optInt("minSteps", 18000);
                    c.maxSteps = j.optInt("maxSteps", 28000);
                    c.multiplier = (float) j.optDouble("multiplier", 3.0);
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("Sport load config err " + t);
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
            j.put("targetSteps", c.targetSteps);
            j.put("minSteps", c.minSteps);
            j.put("maxSteps", c.maxSteps);
            j.put("multiplier", (double) c.multiplier);
            boolean ok = ModuleState.write(Kernel.WXDIR + CONFIG_FILE, j.toString(2));
            if (ok) {
                cachedConfig = c;
                cachedAt = System.currentTimeMillis();
            }
            return ok;
        } catch (Throwable t) {
            KernelLog.debug("Sport save config err " + t);
            return false;
        }
    }

    static boolean enabled() {
        return !Kernel.MODULE_OFF && load().enabled;
    }

    static boolean setEnabled(boolean enabled) {
        Config c = load();
        c.enabled = enabled;
        return save(c);
    }

    static void showConfig(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final Config cfg = load();

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUi.dp(act, 18);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = FeatureMenuUi.text(act, "微信运动步数修改", 18, FeatureMenuUi.Tone.INK);
        intro.getPaint().setFakeBoldText(true);
        root.addView(intro);

        TextView desc = FeatureMenuUi.text(act, "拦截硬件步数传感器上报，支持固定点数、随机区间或倍率放大。\n微信运动排行榜会在下次步数同步时生效。", 12, FeatureMenuUi.Tone.MUTED);
        desc.setPadding(0, SettingsUi.dp(act, 6), 0, SettingsUi.dp(act, 12));
        root.addView(desc);

        final SettingsUi.ToggleRow enableToggle = FeatureMenuUi.toggle(act, "开启微信运动步数修改", "开启后按下方配置重写每日上报步数", cfg.enabled, on -> {
            cfg.enabled = on;
            return true;
        });
        root.addView(enableToggle);

        TextView modeTitle = FeatureMenuUi.text(act, "步数修改模式", 14, FeatureMenuUi.Tone.INK);
        modeTitle.getPaint().setFakeBoldText(true);
        modeTitle.setPadding(0, SettingsUi.dp(act, 14), 0, SettingsUi.dp(act, 6));
        root.addView(modeTitle);

        final RadioGroup rg = new RadioGroup(act);
        rg.setOrientation(LinearLayout.VERTICAL);

        RadioButton rbFixed = new RadioButton(act);
        rbFixed.setText("指定固定步数 (例: 25000)");
        rbFixed.setId(101);
        rg.addView(rbFixed);

        RadioButton rbRandom = new RadioButton(act);
        rbRandom.setText("指定区间随机 (例: 18000 ~ 28000)");
        rbRandom.setId(102);
        rg.addView(rbRandom);

        RadioButton rbMulti = new RadioButton(act);
        rbMulti.setText("按倍率放大实际步数 (例: 3.0倍)");
        rbMulti.setId(103);
        rg.addView(rbMulti);

        if (cfg.mode == 1) rbRandom.setChecked(true);
        else if (cfg.mode == 2) rbMulti.setChecked(true);
        else rbFixed.setChecked(true);

        root.addView(rg);

        // Inputs
        final LinearLayout fixedBox = new LinearLayout(act);
        fixedBox.setOrientation(LinearLayout.HORIZONTAL);
        TextView fixedLabel = FeatureMenuUi.text(act, "目标步数: ", 14, FeatureMenuUi.Tone.INK);
        final EditText fixedEdit = new EditText(act);
        fixedEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        fixedEdit.setText(String.valueOf(cfg.targetSteps));
        fixedEdit.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        fixedBox.addView(fixedLabel);
        fixedBox.addView(fixedEdit);
        root.addView(fixedBox);

        final LinearLayout randomBox = new LinearLayout(act);
        randomBox.setOrientation(LinearLayout.HORIZONTAL);
        TextView minLabel = FeatureMenuUi.text(act, "随机范围: ", 14, FeatureMenuUi.Tone.INK);
        final EditText minEdit = new EditText(act);
        minEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minEdit.setText(String.valueOf(cfg.minSteps));
        minEdit.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView dash = FeatureMenuUi.text(act, " ~ ", 14, FeatureMenuUi.Tone.INK);
        final EditText maxEdit = new EditText(act);
        maxEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        maxEdit.setText(String.valueOf(cfg.maxSteps));
        maxEdit.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        randomBox.addView(minLabel);
        randomBox.addView(minEdit);
        randomBox.addView(dash);
        randomBox.addView(maxEdit);
        root.addView(randomBox);

        final LinearLayout multiBox = new LinearLayout(act);
        multiBox.setOrientation(LinearLayout.HORIZONTAL);
        TextView multiLabel = FeatureMenuUi.text(act, "放大倍率: ", 14, FeatureMenuUi.Tone.INK);
        final EditText multiEdit = new EditText(act);
        multiEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        multiEdit.setText(String.valueOf(cfg.multiplier));
        multiEdit.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        multiBox.addView(multiLabel);
        multiBox.addView(multiEdit);
        root.addView(multiBox);

        // Update visibility based on radio selection
        rg.setOnCheckedChangeListener((group, checkedId) -> {
            fixedBox.setVisibility(checkedId == 101 ? View.VISIBLE : View.GONE);
            randomBox.setVisibility(checkedId == 102 ? View.VISIBLE : View.GONE);
            multiBox.setVisibility(checkedId == 103 ? View.VISIBLE : View.GONE);
        });
        fixedBox.setVisibility(cfg.mode == 0 ? View.VISIBLE : View.GONE);
        randomBox.setVisibility(cfg.mode == 1 ? View.VISIBLE : View.GONE);
        multiBox.setVisibility(cfg.mode == 2 ? View.VISIBLE : View.GONE);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        AlertDialog dialog = FeatureMenuUi.builder(act)
                .setView(scroll)
                .setPositiveButton("保存设置", (d, which) -> {
                    int checkedId = rg.getCheckedRadioButtonId();
                    if (checkedId == 102) cfg.mode = 1;
                    else if (checkedId == 103) cfg.mode = 2;
                    else cfg.mode = 0;

                    try { cfg.targetSteps = Math.max(0, Integer.parseInt(fixedEdit.getText().toString().trim())); } catch (Throwable ignored) {}
                    try { cfg.minSteps = Math.max(0, Integer.parseInt(minEdit.getText().toString().trim())); } catch (Throwable ignored) {}
                    try { cfg.maxSteps = Math.max(cfg.minSteps, Integer.parseInt(maxEdit.getText().toString().trim())); } catch (Throwable ignored) {}
                    try { cfg.multiplier = Math.max(1.0f, Float.parseFloat(multiEdit.getText().toString().trim())); } catch (Throwable ignored) {}

                    boolean ok = save(cfg);
                    Toast.makeText(act, ok ? "微信运动步数设置已保存！\n摇晃手机或重新打开运动排行榜生效" : "保存失败", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
    }
}
