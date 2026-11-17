package io.github.shadowbaby.wechatxposed.hook.license;

/**
 * 版本等级策略。对应参考实现 loader/EditionPolicy.java 的同一套语义：
 * free &lt; pro &lt; plus &lt; vip，逐级累积；图片类功能仅 plus 及以上。
 */
public final class EditionPolicy {

    public static final String FREE = "free";
    public static final String PRO = "pro";
    public static final String PLUS = "plus";
    public static final String VIP = "vip";

    private EditionPolicy() {
    }

    public static int rank(String edition) {
        if (FREE.equals(edition)) {
            return 0;
        }
        if (PRO.equals(edition)) {
            return 1;
        }
        if (PLUS.equals(edition)) {
            return 2;
        }
        if (VIP.equals(edition)) {
            return 3;
        }
        return -1;
    }

    public static boolean valid(String edition) {
        return rank(edition) >= 0;
    }

    public static boolean includesImages(String edition) {
        return rank(edition) >= 2;
    }

    public static String name(String edition) {
        if (PRO.equals(edition)) {
            return "专业版";
        }
        if (PLUS.equals(edition)) {
            return "高级版";
        }
        if (VIP.equals(edition)) {
            return "VIP 版";
        }
        return "免费版";
    }

    /**
     * 内核清单的规范化签名负载，与参考实现逐字一致：
     * {@code kernel-v2|edition|version|sha256}。
     */
    public static String manifestData(String edition, String version, String sha) {
        if (!valid(edition) || version == null || !version.matches("[1-9][0-9]{0,8}")
                || sha == null || !sha.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid kernel manifest");
        }
        return "kernel-v2|" + edition + "|" + version + "|" + sha;
    }

    /** 授权授予负载，对应参考实现的 {@code kernel-grant-v1|owner|edition|version|sha}。 */
    public static String grantData(String owner, String edition, String version, String sha) {
        if (owner == null || owner.isEmpty()) {
            throw new IllegalArgumentException("Invalid grant owner");
        }
        return "kernel-grant-v1|" + owner + "|" + edition + "|" + version + "|" + sha;
    }
}
