package io.github.shadowbaby.wechatxposed.hook;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.shadowbaby.wechatxposed.hook.framework.ModernFramework;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;

public class ModernEntry extends XposedModule {

    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private final ModernFramework framework = new ModernFramework(this);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        ModuleLogger.attach(framework);
        XposedBridge.init(framework);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        try {
            handlePackage(param);
        } catch (Throwable error) {
            framework.log(error);
        }
    }

    private void handlePackage(PackageReadyParam param) {
        if (!WECHAT_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        final ClassLoader appClassLoader = param.getClassLoader();

        // 1. Hook Application.onCreate 保证尽早初始化内核与消息钩子
        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object o = p.thisObject;
                    if (o instanceof Application) {
                        String pn = ((Application) o).getPackageName();
                        if (WECHAT_PACKAGE.equals(pn)) {
                            ClassLoader activeCl = ((Application) o).getClassLoader();
                            com.zafiro.wxentry.kernel.Kernel.boot(activeCl != null ? activeCl : appClassLoader, (Application) o);
                        }
                    }
                }
            });
            ModuleLogger.log("ModernEntry: Application hook installed");
        } catch (Throwable t) {
            ModuleLogger.log("ModernEntry: Application hook failed: " + t);
        }

        // 2. Hook LauncherUI.onCreate 初始化 UI 与绑定「+」号菜单
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
            ModuleLogger.log("ModernEntry: LauncherUI hook installed");
        } catch (Throwable t) {
            ModuleLogger.log("ModernEntry: LauncherUI hook failed: " + t);
        }

        // 3. Activity fallback hook
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
            ModuleLogger.log("ModernEntry: Activity fallback hook installed");
        } catch (Throwable t) {
            ModuleLogger.log("ModernEntry: Activity fallback hook failed: " + t);
        }
    }
}
