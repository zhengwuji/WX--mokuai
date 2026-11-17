package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.lang.reflect.Method;

/**
 * Forward limit unlocker runtime:
 * Removes WeChat's hardcoded 9-recipient selection restriction in `SelectConversationUI`,
 * allowing forwarding to 999+ contacts and chatrooms simultaneously.
 */
final class ForwardLimitRuntime {
    private static final String FLAG_FILE = "forward_limit_unlock.on";
    private static final String SELECT_UI = "com.tencent.mm.ui.transmit.SelectConversationUI";
    private static volatile boolean started;

    private ForwardLimitRuntime() {}

    static boolean ready() {
        return started;
    }

    static synchronized void start(ClassLoader cl) {
        if (started) return;
        try {
            Class<?> selectCls = DexResolver.load(cl, SELECT_UI);
            if (selectCls != null) {
                // Hook onCreate to override selection limit extras
                XposedBridge.hookAllMethods(selectCls, "onCreate", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Kernel.MODULE_OFF || !enabled()) return;
                        if (!(p.thisObject instanceof Activity)) return;
                        Activity act = (Activity) p.thisObject;
                        Intent intent = act.getIntent();
                        if (intent != null) {
                            intent.putExtra("Select_Conv_Max_Select_Count", 999);
                            intent.putExtra("max_limit_num", 999);
                            intent.putExtra("max_select_count", 999);
                            intent.putExtra("select_is_ret", true);
                        }
                    }
                });

                // Hook internal limit validation methods that return 9
                for (Method m : selectCls.getDeclaredMethods()) {
                    if (m.getReturnType() == int.class && m.getParameterCount() == 0) {
                        try {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam p) {
                                    if (Kernel.MODULE_OFF || !enabled()) return;
                                    Object res = p.getResult();
                                    if (res instanceof Integer && ((Integer) res).intValue() == 9) {
                                        p.setResult(999);
                                    }
                                }
                            });
                        } catch (Throwable ignored) {}
                    }
                }
            }

            started = true;
            Kernel.T("FORWARDLIMIT ready");
        } catch (Throwable t) {
            Kernel.T("FORWARDLIMIT install-error " + t.getClass().getSimpleName());
        }
    }

    static boolean enabled() {
        return !Kernel.MODULE_OFF && new File(Kernel.WXDIR + FLAG_FILE).exists();
    }

    static boolean setEnabled(boolean on) {
        File f = new File(Kernel.WXDIR + FLAG_FILE);
        if (on) {
            return ModuleState.write(f.getAbsolutePath(), "1");
        } else {
            return !f.exists() || f.delete();
        }
    }
}
