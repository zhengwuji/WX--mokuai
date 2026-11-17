package io.github.shadowbaby.wechatxposed.hook.license;

import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 内核缓存校验。对应参考实现的 {@code loader/KernelDelivery.java}：
 * <pre>
 *   kernel.channel  = Properties{format=2, scope, edition, version, sha256, signature}
 *   kernel.enc      = 加密内核字节
 *   kernel.sha/ver  = 便于外部比对的明文副本
 * </pre>
 *
 * 与参考实现的差别只有一处：作者用 RSA 私钥签 manifest，我们拿不到私钥，所以沿用
 * {@link SelfLicense} 的对称密钥校验同一段规范化负载。判定顺序与参考实现一致：
 * 缓存签名 → 版本/edition 归属 → 字节哈希 → 任何一步不过就返回 null，
 * 由调用方回落到模块自带内核，绝不阻塞、绝不重试。
 */
public final class KernelCache {

    public static final String CHANNEL = "kernel.channel";
    public static final String BLOB = "kernel.enc";
    public static final String SHA_FILE = "kernel.sha";
    public static final String VER_FILE = "kernel.ver";

    private static final int MIN_BLOB = 1000;
    private static final int MAX_BLOB = 16 * 1024 * 1024;

    public static final class Manifest {

        public final String edition;
        public final String version;
        public final String sha256;
        public final String signature;
        public final String scope;

        Manifest(String edition, String version, String sha256, String signature, String scope) {
            this.edition = edition;
            this.version = version;
            this.sha256 = sha256;
            this.signature = signature;
            this.scope = scope;
        }

        String signedData() {
            return EditionPolicy.manifestData(edition, version, sha256);
        }
    }

    private KernelCache() {
    }

    /** 读取并校验清单；不通过返回 null。 */
    public static Manifest readManifest(File dir, String owner) {
        try {
            File channel = new File(dir, CHANNEL);
            String text = SelfLicense.readText(channel);
            if (text == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(new java.io.ByteArrayInputStream(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!"2".equals(props.getProperty("format"))) {
                ModuleLogger.debug("kernel cache: unsupported format");
                return null;
            }
            String scope = props.getProperty("scope");
            if (owner != null && scope != null && !scope.equals(SelfLicense.sha256(owner.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)))) {
                ModuleLogger.debug("kernel cache: belongs to another activation");
                return null;
            }
            Manifest manifest = new Manifest(
                    props.getProperty("edition"),
                    props.getProperty("version"),
                    props.getProperty("sha256"),
                    props.getProperty("signature"),
                    scope);
            if (!EditionPolicy.valid(manifest.edition)) {
                return null;
            }
            String expect = SelfLicense.issueSignature(manifest.signedData());
            if (expect == null || !expect.equals(manifest.signature)) {
                ModuleLogger.debug("kernel cache: bad manifest signature");
                return null;
            }
            return manifest;
        } catch (Throwable error) {
            ModuleLogger.debug("kernel cache: manifest failed: " + error);
            return null;
        }
    }

    /** 只有清单通过且字节哈希一致时才返回内核字节，否则 null（调用方回落自带内核）。 */
    public static byte[] readVerified(File dir, String owner) {
        try {
            Manifest manifest = readManifest(dir, owner);
            if (manifest == null) {
                return null;
            }
            File blob = new File(dir, BLOB);
            if (!blob.isFile() || blob.length() < MIN_BLOB || blob.length() > MAX_BLOB) {
                ModuleLogger.debug("kernel cache: blob size out of range");
                return null;
            }
            byte[] bytes = readAll(blob, MAX_BLOB);
            if (bytes == null) {
                return null;
            }
            String actual = SelfLicense.sha256(bytes);
            if (actual == null || !actual.equals(manifest.sha256)) {
                ModuleLogger.debug("kernel cache: hash mismatch");
                return null;
            }
            return bytes;
        } catch (Throwable error) {
            ModuleLogger.debug("kernel cache: verify failed: " + error);
            return null;
        }
    }

    /**
     * 一次性的状态描述，只写日志、不做控制流，便于排查内核到底走的是缓存还是自带。
     */
    public static String describe(File dir, String owner) {
        try {
            Manifest manifest = readManifest(dir, owner);
            if (manifest == null) {
                return "kernel cache: none -> embedded";
            }
            byte[] verified = readVerified(dir, owner);
            return "kernel cache: " + manifest.edition + " v" + manifest.version
                    + " verified=" + (verified != null)
                    + " bytes=" + (verified == null ? 0 : verified.length);
        } catch (Throwable error) {
            return "kernel cache: describe failed: " + error;
        }
    }

    private static byte[] readAll(File file, int max) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > max) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException error) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }
}
