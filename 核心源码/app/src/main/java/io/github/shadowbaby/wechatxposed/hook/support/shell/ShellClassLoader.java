package io.github.shadowbaby.wechatxposed.hook.support.shell;

import dalvik.system.DexClassLoader;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;

/**
 * 加载 shell.apk 的类加载器。
 *
 * <p>shell.apk 里的 {@code com.fkzhang.wechatxposed.XposedInit} 是老式 Xposed 入口，
 * {@code implements de.robv.android.xposed.IXposedHookLoadPackage}。这个接口由 LSPosed
 * 在运行时注入到<b>宿主应用（微信）的 ClassLoader</b>，不在模块自己的
 * {@code LspModuleClassLoader} 里。
 *
 * <p>所以父加载器必须是宿主的 ClassLoader，shell.apk 才能解析到真正的
 * {@code XposedBridge} / {@code XposedHelpers}，hook 调用才能落进当前框架。
 * 用模块自己的 ClassLoader 当父会一路 {@code ClassNotFoundException}，入口类加载失败，
 * 功能层整条链路断开。
 *
 * <p>宿主 ClassLoader 不可用时退回模块隔离加载的桩类，保证入口类至少能被实例化。
 */
final class ShellClassLoader extends DexClassLoader {

    ShellClassLoader(String dexPath, String optimizedDirectory, String moduleApk,
            io.github.shadowbaby.wechatxposed.shared.hook.HookFramework framework,
            ClassLoader hostLoader) {
        super(withModule(dexPath, moduleApk), optimizedDirectory, null,
                ShellClassLoader.class.getClassLoader());
        ModuleLogger.log("shell loader dexPath=" + withModule(dexPath, moduleApk)
                + " parent=module");
        seedFramework(framework);
    }

    /**
     * 把框架实例注入本加载器里的那份 {@code XposedBridge}。
     *
     * <p>dexPath 同时包含 shell.apk 和模块 APK，因此 {@code de.robv.android.xposed.*}
     * 是从本加载器自己的 dex 里解出来的，与父加载器（LspModuleClassLoader）持有的那份
     * 是两个不同的 Class，静态字段 {@code framework} 互不相通。父加载器那份已由
     * {@code ModernEntry.onModuleLoaded} 注入过，这里必须再注入一次，否则 shell 入口
     * 调用 {@code XposedBridge.hookAllMethods} 会抛
     * {@code IllegalStateException("XposedBridge 未初始化")}，被它自己的
     * catch(Throwable) 吞掉，表现为「入口调用了但什么都不发生」。
     *
     * <p>参数类型是 {@code shared.hook.HookFramework}，该接口由父加载器提供，
     * 两边是同一个 Class，可以直接 {@code Class.forName} 后反射赋值。
     */
    private void seedFramework(
            io.github.shadowbaby.wechatxposed.shared.hook.HookFramework framework) {
        if (framework == null) {
            ModuleLogger.log("shell framework seed skipped: null");
            return;
        }
        try {
            Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge",
                    true, this);
            java.lang.reflect.Method init = bridge.getDeclaredMethod(
                    "init",
                    io.github.shadowbaby.wechatxposed.shared.hook.HookFramework.class);
            init.setAccessible(true);
            init.invoke(null, framework);
            ModuleLogger.log("shell framework seeded");
        } catch (Throwable error) {
            ModuleLogger.log("shell framework seed failed: " + error);
        }
    }

    /**
     * 把模块 APK 追加进 dexPath。
     *
     * <p>{@code ShellClassLoader} 的父加载器固定为模块自己的
     * {@code LspModuleClassLoader}，因此 {@code shared.hook.HookFramework} 这类共享类
     * 仍然只有一份 Class（父先提供），跨 ClassLoader 的强类型问题不存在。
     *
     * <p>而 {@code de.robv.android.xposed.*} 这些桩类虽然物理上就在模块 APK 里，
     * {@code LspModuleClassLoader.loadClass} 却对该前缀做了过滤，父加载器拿不到。
     * 把模块 APK 一并放进本加载器的 dexPath 之后，{@code DexClassLoader} 会在自己的
     * dex 列表里直接找到这些类，绕开父加载器的过滤。
     */
    private static String withModule(String dexPath, String moduleApk) {
        if (moduleApk == null || moduleApk.isEmpty() || moduleApk.equals(dexPath)) {
            return dexPath;
        }
        if (dexPath != null && dexPath.contains(moduleApk)) {
            return dexPath;
        }
        return dexPath == null || dexPath.isEmpty()
                ? moduleApk
                : dexPath + java.io.File.pathSeparator + moduleApk;
    }

}
