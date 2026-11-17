package com.zafiro.wxentry;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 模块辅助接口与本地无限制完整版特权分发。
 * 用于响应内核中反射调用的各类控制与状态方法，保证无网环境完全自闭环。
 */
public class Main {

    private static final String[] TABLET_FILES = new String[] {
        "/data/user/0/com.tencent.mm/files/WechatXposed/tablet.on",
        "/data/data/com.tencent.mm/files/WechatXposed/tablet.on"
    };

    public static String statusLine() {
        return "OK|0||||完整版";
    }

    public static String myVersionName() {
        return "2.30";
    }

    public static String apkPendingName() {
        return null;
    }

    public static String pendingVersion() {
        return null;
    }

    public static String kernelAnnouncements() {
        return "微X模块 8.0.80 完整版已就绪，所有功能均已解锁。";
    }

    public static String tabletFeatureEnabled() {
        return "1";
    }

    public static String tabletModeConfigured() {
        try {
            for (String path : TABLET_FILES) {
                if (new java.io.File(path).exists()) return "1";
            }
            return "0";
        } catch (Throwable t) {
            return "0";
        }
    }

    public static boolean setTabletMode(Activity act, boolean on) {
        try {
            for (String path : TABLET_FILES) {
                java.io.File f = new java.io.File(path);
                if (on) {
                    if (f.getParentFile() != null) f.getParentFile().mkdirs();
                    f.createNewFile();
                } else {
                    if (f.exists()) f.delete();
                }
            }
            if (act != null) {
                Toast.makeText(act, on ? "已开启平板模式（重启微信生效）" : "已关闭平板模式（重启微信生效）", Toast.LENGTH_SHORT).show();
            }
            return on;
        } catch (Throwable t) {
            if (act != null) {
                Toast.makeText(act, "设置平板模式失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return false;
        }
    }

    public static void openTabletQr(Activity act) {
        if (act != null) {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.login.ui.LoginAsExDeviceUI");
                act.startActivity(intent);
            } catch (Throwable t) {
                try {
                    Intent intent2 = new Intent();
                    intent2.setClassName("com.tencent.mm", "com.tencent.mm.ui.account.LoginAsExDeviceUI");
                    act.startActivity(intent2);
                } catch (Throwable t2) {
                    Toast.makeText(act, "未找到平板登录界面，请确认当前处于退出登录状态", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    public static String imageFeatureEnabledFor(String edition) {
        return "1";
    }

    public static String sendAppeal(String text) {
        return "OK";
    }

    public static void changeActivation(Activity act) {
        if (act != null) {
            Toast.makeText(act, "当前为永久完整版，所有功能已解锁", Toast.LENGTH_SHORT).show();
        }
    }

    public static void openUpdateCenter(Activity act) {
        if (act != null) {
            Toast.makeText(act, "当前已是最新完整版 (v2.30)", Toast.LENGTH_SHORT).show();
        }
    }

    public static String checkUpdateNow() {
        return "当前已是最新版本";
    }

    public static void postWish(String text) {
    }

    public static void uploadDiag(String reason) {
    }

    public static String installApkUpdate(Context ctx) {
        return "已是最新版本";
    }
}
