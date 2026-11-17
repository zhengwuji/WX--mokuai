package io.github.shadowbaby.wechatxposed.hook.framework;

import io.github.shadowbaby.wechatxposed.hook.deploy.ModuleApk;
import io.github.shadowbaby.wechatxposed.hook.loader.dex.ClassLoaderWatch;
import io.github.shadowbaby.wechatxposed.hook.loader.dex.InMemoryDexLoadHook;
import io.github.shadowbaby.wechatxposed.hook.support.guard.NoticeSuppressor;
import io.github.shadowbaby.wechatxposed.hook.support.pack.PackGuard;
import io.github.shadowbaby.wechatxposed.hook.loader.SecretConstants;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import io.github.shadowbaby.wechatxposed.hook.loader.util.Secret;
import io.github.shadowbaby.wechatxposed.hook.support.identity.Wx244Compat;
import io.github.shadowbaby.wechatxposed.hook.support.shell.ShellBundle;
import io.github.shadowbaby.wechatxposed.shared.hook.HookFramework;

public final class Wx244Hooks implements PlatformHooks {

    @Override
    public void installZygote(HookFramework framework, String modulePath) {
        ModuleApk.remember(modulePath);
        Wx244Compat.install(framework);
        String className = Secret.of(SecretConstants.IN_MEMORY_DEX_LOADER);
        Class<?> inMemoryDexClassLoader = framework.findClass(className, null);
        framework.hookConstructors(inMemoryDexClassLoader,
                new InMemoryDexLoadHook(framework, InMemoryDexLoadHook.DEX_LOADER));

        // 补挂其余 dex 承载型 ClassLoader，并挂全量 dump 观察器。
        // 功能包不一定只经 InMemoryDexClassLoader 装载，其余入口同样要能抓到。
        ClassLoaderWatch watch = new ClassLoaderWatch("cl");
        for (String name : DEX_CARRIER_CLASSES) {
            try {
                Class<?> carrier = framework.findClassIfExists(name, null);
                if (carrier == null) {
                    ModuleLogger.log("clwatch skip: " + name + " (absent)");
                    continue;
                }
                framework.hookConstructors(carrier, watch);
                ModuleLogger.log("clwatch hooked: " + name);
            } catch (Throwable error) {
                ModuleLogger.log("clwatch skip: " + name + " " + error);
            }
        }
        ClassLoaderWatch.setDumpRoot(modulePath);
    }

    private static final String[] DEX_CARRIER_CLASSES = {
            "dalvik.system.DexClassLoader",
            "dalvik.system.PathClassLoader",
            "dalvik.system.BaseDexClassLoader",
            "dalvik.system.DexFile",
    };

    @Override
    public void installEarly(HookFramework framework, ClassLoader classLoader) {
    }

    @Override
    public void installPackGuards(HookFramework framework) {
        PackGuard.install(framework);
        NoticeSuppressor.install(framework);
    }

    @Override
    public String prepareShell(HookFramework framework,
            android.content.pm.ApplicationInfo appInfo, String modulePath) {
        String shell = ShellBundle.ensure(appInfo);
        return shell != null ? shell : modulePath;
    }

    @Override
    public ClassLoader prepareShellLoader(HookFramework framework,
            android.content.pm.ApplicationInfo appInfo, String modulePath,
            ClassLoader hostLoader) {
        String shell = ShellBundle.ensure(appInfo);
        if (shell == null) {
            return null;
        }
        Wx244Compat.attach(shell);
        ClassLoader loader = ShellBundle.loader(shell, new java.io.File(appInfo.dataDir),
                modulePath, framework, hostLoader);
        seedIdentity(loader);
        return loader;
    }

    private static void seedIdentity(ClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            Class<?> type = Class.forName("x.ʼˏ.ʾﹳ", true, loader);
            setStatic(type, "ⁱˑ", "com.fkzhang.wechatxposed");
            setStatic(type, "ـﾞ", "2.44");
            setStatic(type, "ᐧᵎ", Boolean.TRUE);
        } catch (Throwable error) {
            ModuleLogger.log("wx244 seed: " + error);
        }
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Override
    public String shellEntryClass() {
        return "com.fkzhang.wechatxposed.XposedInit";
    }

    @Override
    public String shellPackage() {
        return "com.fkzhang.wechatxposed";
    }

    @Override
    public String settingsHint() {
        return "请在微信「我 → 设置」中打开微X模块";
    }
}
