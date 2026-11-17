package io.github.shadowbaby.wechatxposed.hook;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.shadowbaby.wechatxposed.hook.framework.LegacyFramework;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;

public class XposedInit implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private final LegacyFramework framework = new LegacyFramework();

    @Override
    public void initZygote(StartupParam startupParam) {
        ModuleLogger.attach(framework);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param.appInfo == null) {
            return;
        }

        if ("com.tencent.mm".equals(param.packageName) && ("com.tencent.mm".equals(param.processName) || param.processName == null)) {
            final ClassLoader appClassLoader = param.classLoader;

            try {
                XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object o = p.thisObject;
                        if (o instanceof Application) {
                            String pn = ((Application) o).getPackageName();
                            if ("com.tencent.mm".equals(pn)) {
                                ClassLoader activeCl = ((Application) o).getClassLoader();
                                com.zafiro.wxentry.kernel.Kernel.boot(activeCl != null ? activeCl : appClassLoader, (Application) o);
                            }
                        }
                    }
                });
                ModuleLogger.log("XposedInit: Application hook installed");
            } catch (Throwable t) {
                ModuleLogger.log("XposedInit: Application hook failed: " + t);
            }

            try {
                Class<?> launcher = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", appClassLoader);
                XposedHelpers.findAndHookMethod(launcher, "onCreate", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Activity act = (Activity) p.thisObject;
                        ClassLoader activeCl = act.getClassLoader();
                        com.zafiro.wxentry.kernel.Kernel.boot(activeCl != null ? activeCl : appClassLoader, act);
                    }
                });
                ModuleLogger.log("XposedInit: LauncherUI hook installed");
            } catch (Throwable t) {
                ModuleLogger.log("XposedInit: LauncherUI hook failed: " + t);
            }

            try {
                XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object o = p.thisObject;
                        if (o instanceof Activity) {
                            String cn = o.getClass().getName();
                            if (cn != null && cn.startsWith("com.tencent.mm.")) {
                                ClassLoader activeCl = ((Activity) o).getClassLoader();
                                com.zafiro.wxentry.kernel.Kernel.boot(activeCl != null ? activeCl : appClassLoader, (Activity) o);
                            }
                        }
                    }
                });
                ModuleLogger.log("XposedInit: Activity fallback hook installed");
            } catch (Throwable t) {
                ModuleLogger.log("XposedInit: Activity fallback hook failed: " + t);
            }
        }
    }
}
