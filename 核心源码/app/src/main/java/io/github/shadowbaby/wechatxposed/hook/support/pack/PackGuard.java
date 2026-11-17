package io.github.shadowbaby.wechatxposed.hook.support.pack;

import io.github.shadowbaby.wechatxposed.hook.BuildConfig;
import io.github.shadowbaby.wechatxposed.shared.hook.HookCallback;
import io.github.shadowbaby.wechatxposed.shared.hook.HookFramework;
import io.github.shadowbaby.wechatxposed.shared.hook.HookParam;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能包删除拦截器。
 *
 * 原实现拦截对 {@code files/<WX_PACK>/} 下功能包文件的 {@link File#delete()} 调用，
 * 防止微信自身的清理逻辑把功能包删掉。这个拦截本身对功能可用性是正向的，保留。
 *
 * 断开版新增两个运行期开关，便于替换功能包与排障：
 * <ul>
 *   <li>{@link #setEnabled(boolean)} —— 整体开关</li>
 *   <li>{@link #setAllowDelete(boolean)} —— 允许删除（调试用）</li>
 * </ul>
 */
public final class PackGuard {

    private static final String ZIP_SUFFIX = ".zip";

    private static final String LOG_BLOCKED = "pack delete blocked: ";

    private static boolean installed;

    private static volatile boolean enabled = true;

    private static volatile boolean allowDelete = false;

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PackGuard() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ModuleLogger.debug("pack guard enabled=" + value);
    }

    public static void setAllowDelete(boolean value) {
        allowDelete = value;
        ModuleLogger.debug("pack guard allowDelete=" + value);
    }

    public static synchronized void install(HookFramework framework) {
        if (installed) {
            return;
        }

        installed = true;
        try {
            Class<?> fileClass = framework.findClassIfExists("java.io.File", null);
            if (fileClass == null) {
                installed = false;
                return;
            }
            framework.hookMethod(fileClass, "delete", new Class<?>[]{}, new Callback());
            ModuleLogger.debug("pack guard installed: File.delete");
        } catch (Throwable error) {
            framework.log(error);
        }
    }

    private static final class Callback implements HookCallback {

        @Override
        public void before(HookParam param) {
            if (!enabled || allowDelete) {
                return;
            }
            Object target = param.thisObject();
            if (!(target instanceof File file)) {
                return;
            }
            String path = file.getAbsolutePath().replace('\\', '/');
            if (path.endsWith(ZIP_SUFFIX)) {
                return;
            }
            String dir = BuildConfig.WX_PACK;
            if (dir.isEmpty() || !path.contains("/files/" + dir + "/")) {
                return;
            }
            if (LOGGED.add(path)) {
                ModuleLogger.log(LOG_BLOCKED + path);
            }
            param.setResult(Boolean.FALSE);
        }
    }
}
