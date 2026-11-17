package io.github.shadowbaby.wechatxposed.hook.license;

import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import java.io.File;

/**
 * 授权装配器。
 *
 * 判定顺序照参考实现（WXEntry 的 loader）来：
 * <ol>
 *   <li>本机自签授权（{@link SelfLicense}）—— 相当于参考实现里 license.dat 的角色，
 *       拿不到就当场签发免费版，永不阻塞；</li>
 *   <li>原生授权行（{@link LicenseDatabase}）—— 由 libwexhook.so 现算并写入 FKZ_WX_DATA，
 *       这一路是作者内核自己认的账；</li>
 *   <li>内核缓存状态（{@link KernelCache}）—— 只记录，不参与控制流。</li>
 * </ol>
 *
 * 三条都是「尽力而为 + 一次性」：同一进程里同一账号只尝试一次，任何异常都只记一条日志。
 * 这是把之前那次「BadPaddingException 无限重试拖死微信主线程」从结构上堵死。
 */
public final class LicenseInstaller {

    private static final java.util.Set<String> ATTEMPTED = new java.util.HashSet<String>();

    private LicenseInstaller() {
    }

    public static synchronized void ensure(File database, String account) {
        if (database == null || account == null || account.isEmpty()) {
            ModuleLogger.log("license: skipped (account unavailable)");
            return;
        }
        if (!ATTEMPTED.add(account)) {
            ModuleLogger.debug("license: already attempted for " + account);
            return;
        }

        File dir = database.getParentFile() != null ? database.getParentFile() : database;

        // 1. 自签授权：保证一定有可用授权，失败也不影响后续
        try {
            SelfLicense.Token token = SelfLicense.ensureFree(dir, account);
            ModuleLogger.log("license: local edition="
                    + (token == null ? EditionPolicy.name(EditionPolicy.FREE)
                            : EditionPolicy.name(token.edition))
                    + " token=" + (token != null));
        } catch (Throwable error) {
            ModuleLogger.log("license: local layer failed: " + error);
        }

        // 2. 原生授权行：作者内核认的那份，失败就跳过
        try {
            LicenseDatabase.Result result = LicenseDatabase.ensure(database, account);
            ModuleLogger.log("license db: " + account + " ready"
                    + " created=" + result.created
                    + " inserted=" + result.inserted
                    + " refreshed=" + result.refreshed
                    + " kept=" + result.kept);
        } catch (Throwable error) {
            ModuleLogger.log("license db: " + account + " deferred: " + error);
        }

        // 3. 内核缓存状态：纯诊断
        try {
            if (dir.isDirectory()) {
                ModuleLogger.log(KernelCache.describe(new File(dir, "WechatXposed"), account));
            }
        } catch (Throwable error) {
            ModuleLogger.debug("kernel cache: describe skipped: " + error);
        }
    }
}
