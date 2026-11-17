package io.github.shadowbaby.wechatxposed.hook.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import io.github.shadowbaby.wechatxposed.hook.BuildConfig;
import io.github.shadowbaby.wechatxposed.hook.R;
import io.github.shadowbaby.wechatxposed.hook.framework.HookBootstrap;
import io.github.shadowbaby.wechatxposed.hook.loader.SecretConstants;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String ICON_ALIAS = BuildConfig.APPLICATION_ID + ".ui.MainActivityAlias";
    private static final String OPEN_MENU_ACTION = "com.zafiro.wxentry.OPEN_MENU";
    private static final int REQ_EXPORT_BACKUP = 1001;
    private static final int REQ_IMPORT_BACKUP = 1002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MenuItem iconItem;
    private TextView statusSummary;
    private String lastTraceTail = "";
    private String pendingBackupJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setActionBar(toolbar);
        toolbar.setTitle(getString(R.string.app_name));

        statusSummary = findViewById(R.id.status_summary);

        Button appInfo = findViewById(R.id.goto_wechat_appinfo);
        appInfo.setOnClickListener(view -> openAppInfo());
        Button settings = findViewById(R.id.goto_settings);
        settings.setOnClickListener(view -> openWeChatSettings());
        Button refreshBtn = findViewById(R.id.btn_refresh_status);
        refreshBtn.setOnClickListener(view -> pullBridgeState(null));
        Button exportBtn = findViewById(R.id.btn_export_backup);
        exportBtn.setOnClickListener(view -> exportBackupViaSaf());
        Button importBtn = findViewById(R.id.btn_import_backup);
        importBtn.setOnClickListener(view -> openImportFilePicker());
        Button logBtn = findViewById(R.id.btn_view_logs);
        logBtn.setOnClickListener(view -> pullAndShowLogs());

        renderStaticHeader("未连接（请先启动微信后再点“刷新”）");
        pullBridgeState(null);
    }

    private String weChatVersionSummary() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(SecretConstants.WECHAT_PACKAGE, 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return "微信 " + pi.versionName + " (" + vc + ")";
        } catch (Throwable t) {
            return "未检测到微信客户端";
        }
    }

    private void renderStaticHeader(String liveDiag) {
        String base = "模块版本：" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "宿主状态：" + weChatVersionSummary() + "\n"
                + "————————————\n"
                + (liveDiag == null || liveDiag.isEmpty() ? "暂无实时诊断信息" : liveDiag);
        if (statusSummary != null) statusSummary.setText(base);
    }

    private void pullBridgeState(final Runnable onPulled) {
        final boolean[] replied = new boolean[]{false};
        ResultReceiver receiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                replied[0] = true;
                if (data == null) return;
                String diag = data.getString("diag", "");
                String kernel = data.getString("kernel", "");
                boolean dbg = data.getBoolean("debugLog", false);
                lastTraceTail = data.getString("trace", "");
                pendingBackupJson = data.getString("backup", "");
                renderStaticHeader("内核 v" + kernel + " · 调试日志：" + (dbg ? "开" : "关") + "\n" + diag);
                if (onPulled != null) onPulled.run();
            }
        };
        Intent it = new Intent(OPEN_MENU_ACTION);
        it.setPackage(SecretConstants.WECHAT_PACKAGE);
        it.putExtra("bridge_cmd", "pull_state");
        it.putExtra("bridge_reply", receiver);
        sendBroadcast(it);

        mainHandler.postDelayed(() -> {
            if (!replied[0] && onPulled != null) {
                Toast.makeText(this, "微信进程未响应，请先打开微信后再试", Toast.LENGTH_SHORT).show();
            }
        }, 1200L);
    }

    private void exportBackupViaSaf() {
        pullBridgeState(() -> {
            if (pendingBackupJson == null || pendingBackupJson.isEmpty()) {
                Toast.makeText(this, "未能从微信获取配置（或当前无可备份配置）", Toast.LENGTH_SHORT).show();
                return;
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("application/json");
            it.putExtra(Intent.EXTRA_TITLE, "WechatXposed_Backup_" + ts + ".json");
            startActivityForResult(it, REQ_EXPORT_BACKUP);
        });
    }

    private void openImportFilePicker() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_IMPORT_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_BACKUP) {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    out.write(pendingBackupJson.getBytes("UTF-8"));
                    out.close();
                    Toast.makeText(this, "配置备份已导出", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) return;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                in.close();
                String json = new String(bo.toByteArray(), "UTF-8");
                applyBackupToWeChat(json);
            } catch (Throwable t) {
                Toast.makeText(this, "读取备份失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void applyBackupToWeChat(String json) {
        final boolean[] replied = new boolean[]{false};
        ResultReceiver receiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                replied[0] = true;
                int restored = data != null ? data.getInt("restored", -1) : -1;
                Toast.makeText(MainActivity.this,
                        restored >= 0 ? "已同步恢复 " + restored + " 项配置（建议重启微信）" : "备份格式无效，恢复失败",
                        Toast.LENGTH_LONG).show();
                pullBridgeState(null);
            }
        };
        Intent it = new Intent(OPEN_MENU_ACTION);
        it.setPackage(SecretConstants.WECHAT_PACKAGE);
        it.putExtra("bridge_cmd", "apply_backup");
        it.putExtra("backup_json", json);
        it.putExtra("bridge_reply", receiver);
        sendBroadcast(it);
        mainHandler.postDelayed(() -> {
            if (!replied[0]) Toast.makeText(this, "微信未响应，请确认微信处于运行状态", Toast.LENGTH_SHORT).show();
        }, 1200L);
    }

    private void pullAndShowLogs() {
        pullBridgeState(() -> {
            final String content = (lastTraceTail == null || lastTraceTail.isEmpty()) ? "（暂无运行日志）" : lastTraceTail;
            TextView tv = new TextView(this);
            tv.setText(content);
            tv.setTextSize(11);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            int pad = Math.round(14 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(tv);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));

            new AlertDialog.Builder(this)
                    .setTitle("运行日志（trace.log 末尾）")
                    .setView(scroll)
                    .setPositiveButton("复制", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("trace.log", content));
                            Toast.makeText(this, "已复制日志", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("清空日志", (d, w) -> clearWeChatLogs())
                    .setNegativeButton("关闭", null)
                    .show();
        });
    }

    private void clearWeChatLogs() {
        ResultReceiver receiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                lastTraceTail = "";
                Toast.makeText(MainActivity.this, "已清空微信 trace.log", Toast.LENGTH_SHORT).show();
            }
        };
        Intent it = new Intent(OPEN_MENU_ACTION);
        it.setPackage(SecretConstants.WECHAT_PACKAGE);
        it.putExtra("bridge_cmd", "clear_log");
        it.putExtra("bridge_reply", receiver);
        sendBroadcast(it);
    }

    private void openAppInfo() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", SecretConstants.WECHAT_PACKAGE, null));
            startActivity(intent);
        } catch (Throwable ignored) {}
    }

    private void openWeChatSettings() {
        Intent intent = getPackageManager()
                .getLaunchIntentForPackage(SecretConstants.WECHAT_PACKAGE);
        if (intent == null) {
            intent = new Intent().setClassName(SecretConstants.WECHAT_PACKAGE,
                    "com.tencent.mm.ui.LauncherUI");
        }
        intent.putExtra("gotosettings", HookBootstrap.shellPackage());
        start(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        iconItem = menu.findItem(R.id.action_icon);
        updateIconTitle();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_icon) {
            boolean enabled = iconEnabled();
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(BuildConfig.APPLICATION_ID, ICON_ALIAS),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            updateIconTitle();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean iconEnabled() {
        return getPackageManager().getComponentEnabledSetting(
                new ComponentName(BuildConfig.APPLICATION_ID, ICON_ALIAS))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void updateIconTitle() {
        if (iconItem != null) {
            iconItem.setTitle(getString(iconEnabled() ? R.string.hide_icon : R.string.show_icon));
        }
    }

    private boolean start(Intent intent) {
        if (intent == null) return false;
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }
}
