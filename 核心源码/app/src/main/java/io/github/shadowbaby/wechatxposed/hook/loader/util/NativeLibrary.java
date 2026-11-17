package io.github.shadowbaby.wechatxposed.hook.loader.util;

/**
 * 原生库加载器。
 *
 * 原生库 libwexhook.so 提供 JNI 侧的结构描述、槽位分发表与资源解密，功能 dex 依赖它。
 *
 * 注意：不能直接用 {@code System.loadLibrary("wexhook")}。LSPosed 注入微信进程后，
 * {@code java.library.path} 指向的是微信自己的 lib 目录，看不到模块 APK 里的 so。
 * 正确做法是通过模块自己的 ClassLoader 调 {@code findLibrary} 拿到 APK 内
 * {@code lib/<abi>/libwexhook.so} 的解压绝对路径，再用 {@code System.load} 加载。
 *
 * 加载失败时记录错误并返回 false，不抛异常、不阻断启动，让功能层降级运行。
 */
public final class NativeLibrary {

    public static final String NAME = "wexhook";

    private static volatile boolean loadAttempted;

    private static volatile boolean loaded;

    private static volatile String lastError;

    private static volatile String loadedPath;

    private NativeLibrary() {
    }

    public static synchronized boolean load() {
        if (loadAttempted) {
            return loaded;
        }
        loadAttempted = true;

        // 第一优先：通过模块自己的 ClassLoader 解析 APK 内 so 的绝对路径。
        String resolved = resolveFromClassLoader();
        if (resolved != null && loadAbsolute(resolved)) {
            return true;
        }

        // 第二优先：宿主已在 java.library.path 里放好同名库（少见，但保留兜底）。
        try {
            System.loadLibrary(NAME);
            loaded = true;
            loadedPath = NAME + " (system path)";
            ModuleLogger.debug("native lib loaded: " + loadedPath);
            return true;
        } catch (Throwable error) {
            lastError = String.valueOf(error);
        }

        loaded = false;
        ModuleLogger.log("native lib load failed, degraded: " + lastError);
        return false;
    }

    private static String resolveFromClassLoader() {
        try {
            ClassLoader loader = NativeLibrary.class.getClassLoader();
            if (loader == null) {
                return null;
            }
            // ClassLoader.findLibrary 是 protected 的，只能走反射。
            java.lang.reflect.Method method = ClassLoader.class.getDeclaredMethod("findLibrary", String.class);
            method.setAccessible(true);
            Object result = method.invoke(loader, NAME);
            if (!(result instanceof String)) {
                return null;
            }
            String path = (String) result;
            if (path.isEmpty()) {
                return null;
            }
            return path;
        } catch (Throwable error) {
            lastError = "findLibrary: " + error;
            return null;
        }
    }

    private static boolean loadAbsolute(String path) {
        try {
            System.load(path);
            loaded = true;
            loadedPath = path;
            ModuleLogger.log("native lib loaded: " + path);
            return true;
        } catch (Throwable error) {
            lastError = "System.load(" + path + "): " + error;
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String loadedPath() {
        return loadedPath;
    }

    public static String lastError() {
        return lastError;
    }
}
