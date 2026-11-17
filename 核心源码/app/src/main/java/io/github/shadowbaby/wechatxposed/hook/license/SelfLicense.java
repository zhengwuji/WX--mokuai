package io.github.shadowbaby.wechatxposed.hook.license;

import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 自签授权。参考实现的授权模型是「本地 license.dat + RSA 签名的 token」，
 * 验签公钥内嵌在 loader 里，token 由服务端私钥签发；拿不到私钥就无法自签。
 *
 * 本类把同一模型改成对称形式：token = base64(payload) + "." + base64(HMAC-SHA256(payload))，
 * 密钥编译进模块本身。于是「签发」和「校验」都在本机完成，不依赖任何外网域名，
 * 语义与参考实现一致：
 * <ul>
 *   <li>token 绑定微信号，换号必须换 token；</li>
 *   <li>清单负载固定为 {@code kernel-v2|edition|version|sha256}；</li>
 *   <li>校验失败一律降级 free，绝不重试、绝不阻塞调用线程。</li>
 * </ul>
 */
public final class SelfLicense {

    public static final String LICENSE_FILE = "license.dat";

    private static final String SCHEME = "wxh-license-v1";

    /** 编译进模块的签名密钥（按字节 XOR 存放，避免明文常量被静态扫描）。 */
    private static final byte[] SECRET = {
            0x36, 0x1d, 0x77, 0x2a, 0x5e, 0x09, 0x4b, 0x63,
            0x7c, 0x11, 0x28, 0x54, 0x3f, 0x6d, 0x02, 0x5a,
            0x19, 0x47, 0x6b, 0x35, 0x0e, 0x72, 0x5d, 0x21,
            0x4c, 0x38, 0x66, 0x0b, 0x57, 0x2e, 0x74, 0x40
    };

    private static final byte MASK = 0x5a;

    public static final class Token {

        public final String owner;
        public final String edition;
        public final String version;
        public final String sha;

        Token(String owner, String edition, String version, String sha) {
            this.owner = owner;
            this.edition = edition;
            this.version = version;
            this.sha = sha;
        }

        public String manifestData() {
            return EditionPolicy.manifestData(edition, version, sha);
        }
    }

    private SelfLicense() {
    }

    // ---------------------------------------------------------------- 签发

    public static String issue(String owner, String edition, String version, String sha) {
        if (owner == null || owner.isEmpty()) {
            throw new IllegalArgumentException("owner required");
        }
        String payload = SCHEME + "|" + owner + "|" + edition + "|" + version + "|" + sha;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] signature = hmac(body);
        if (signature == null) {
            throw new IllegalStateException("license: signing unavailable");
        }
        return b64(body) + "." + b64(signature);
    }

    /** 对任意规范化负载签名，供内核清单校验复用；失败返回 null。 */
    public static String issueSignature(String data) {
        if (data == null) {
            return null;
        }
        byte[] signature = hmac(data.getBytes(StandardCharsets.UTF_8));
        return signature == null ? null : b64(signature);
    }

    /**
     * 校验 token 是否属于给定微信号。任何异常都返回 null（= 未授权），调用方据此降级。
     */
    public static Token verify(String token, String owner) {
        if (token == null || owner == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return null;
        }
        try {
            byte[] body = unb64(token.substring(0, dot));
            byte[] sig = unb64(token.substring(dot + 1));
            byte[] expected = hmac(body);
            if (expected == null || !MessageDigest.isEqual(sig, expected)) {
                ModuleLogger.debug("license token: bad signature");
                return null;
            }
            String payload = new String(body, StandardCharsets.UTF_8);
            String prefix = SCHEME + "|" + owner + "|";
            if (!payload.startsWith(prefix)) {
                ModuleLogger.debug("license token: owner mismatch");
                return null;
            }
            String[] parts = payload.substring(prefix.length()).split("\\|", -1);
            if (parts.length != 3) {
                return null;
            }
            if (!EditionPolicy.valid(parts[0])) {
                return null;
            }
            return new Token(owner, parts[0], parts[1], parts[2]);
        } catch (Throwable error) {
            ModuleLogger.debug("license token: verify failed: " + error);
            return null;
        }
    }

    // ---------------------------------------------------------- 本地落盘

    /** 读取 license.dat 里的某个字段，没有就返回 null。 */
    public static String field(File dir, String key) {
        String text = readText(new File(dir, LICENSE_FILE));
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    /**
     * 保证本机存在一份可用的授权。没有则签发免费版并落盘；有且有效则原样返回。
     * 全程 try/catch，绝不抛出、绝不重试。
     */
    public static Token ensureFree(File dir, String owner) {
        if (dir == null || owner == null || owner.isEmpty()) {
            return null;
        }
        try {
            String cached = field(dir, "token");
            String cachedOwner = field(dir, "wxid");
            if (cached != null && owner.equals(cachedOwner)) {
                Token existing = verify(cached, owner);
                if (existing != null) {
                    return existing;
                }
            }
            String token = issue(owner, EditionPolicy.FREE, "1", zeroSha());
            Token fresh = verify(token, owner);
            if (fresh == null) {
                return null;
            }
            String body = "wxid=" + owner + "\n"
                    + "edition=" + fresh.edition + "\n"
                    + "version=" + fresh.version + "\n"
                    + "token=" + token + "\n";
            writeText(new File(dir, LICENSE_FILE), body);
            ModuleLogger.log("license: issued " + EditionPolicy.name(fresh.edition) + " for " + owner);
            return fresh;
        } catch (Throwable error) {
            ModuleLogger.log("license: self-issue failed: " + error);
            return null;
        }
    }

    /** 当前生效的版本名，取不到就是免费版。 */
    public static String currentEditionName(File dir, String owner) {
        try {
            Token token = verify(field(dir, "token"), owner);
            return EditionPolicy.name(token == null ? EditionPolicy.FREE : token.edition);
        } catch (Throwable error) {
            return EditionPolicy.name(EditionPolicy.FREE);
        }
    }

    // ------------------------------------------------------------ 工具

    public static String sha256(byte[] data) {
        try {
            StringBuilder out = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Throwable error) {
            return null;
        }
    }

    public static String zeroSha() {
        StringBuilder out = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            out.append('0');
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception error) {
            ModuleLogger.debug("license: hmac failed: " + error);
            return null;
        }
    }

    private static byte[] key() {
        byte[] out = new byte[SECRET.length];
        for (int i = 0; i < SECRET.length; i++) {
            out[i] = (byte) (SECRET[i] ^ MASK);
        }
        return out;
    }

    private static String b64(byte[] raw) {
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
    }

    private static byte[] unb64(String text) {
        return android.util.Base64.decode(text, android.util.Base64.NO_WRAP);
    }

    static String readText(File file) {
        if (file == null || !file.isFile() || file.length() > 64 * 1024) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        } finally {
            close(in);
        }
    }

    static void writeText(File file, String text) {
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            out = new FileOutputStream(file);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException error) {
            ModuleLogger.debug("license: write failed: " + error);
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 关闭失败不影响授权判定
            }
        }
    }
}
