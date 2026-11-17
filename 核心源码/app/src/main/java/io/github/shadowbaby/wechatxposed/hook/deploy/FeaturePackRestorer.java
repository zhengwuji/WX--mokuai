package io.github.shadowbaby.wechatxposed.hook.deploy;

import io.github.shadowbaby.wechatxposed.hook.BuildConfig;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public abstract class FeaturePackRestorer {

    private static final String SKIP_MARKER = "wxh_skip_restore";

    private static final byte[] MERGED_MAGIC = {'W', 'X', 'H', '1'};

    private static final String VERSION_MARKER = "wxh_pack_version";

    /**
     * 本地覆盖目录名，位于 {@code files/override/<WX_PACK>/}。
     *
     * 里面的同名文件会覆盖 assets 里的功能包，优先级最高。该目录不属于 assets
     * 内容，因此不参与 pruneUnknown 清理、不影响版本标记判定，也不需要重装 APK：
     * 把改好的功能包丢进去，重启微信即生效。
     */
    private static final String OVERRIDE_DIR = "override";

    private static final String MTIME_MARKER = "wxh_pack_mtime";

    private static final String[] SIDECAR_SUFFIXES = {"_c", "_s"};

    public static synchronized void ensureOnce(File filesDir) {
        ensureOnce(filesDir, false);
    }

    public static synchronized void ensureOnce(File filesDir, boolean allowExtensions) {
        if (filesDir == null || new File(filesDir, SKIP_MARKER).isFile()) {
            return;
        }
        restoreFlatAssets(filesDir);
    }

    private static void restoreFlatAssets(File filesDir) {
        File directory = new File(filesDir, BuildConfig.WX_PACK);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            ModuleLogger.log("feature packs: cannot create dir " + directory);
            return;
        }
        boolean force = !matchesVersion(directory) || !matchesMtime(directory);
        int restored = 0;
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        for (String name : ModuleApk.listAssets()) {
            if (skipAsset(name)) {
                continue;
            }
            if (hasOverride(filesDir, name)) {
                allowed.add(name);
                continue;
            }
            byte[] data = ModuleApk.readAsset(name);
            if (data == null) {
                continue;
            }
            allowed.add(name);
            if (isMerged(data)) {
                allowed.add(name + "_c");
                allowed.add(name + "_s");
                restored += restoreMerged(directory, name, data, force);
            } else if (writeIfMissing(directory, name, data, 0, data.length, force)) {
                restored++;
            }
            for (String suffix : SIDECAR_SUFFIXES) {
                byte[] sidecar = ModuleApk.readAsset(name + suffix);
                if (sidecar != null) {
                    allowed.add(name + suffix);
                }
                if (sidecar != null
                        && writeIfMissing(directory, name + suffix, sidecar,
                                0, sidecar.length, force)) {
                    restored++;
                }
            }
        }
        pruneUnknown(directory, allowed);
        writeVersion(directory);
        writeMtime(directory);
        applyOverrides(filesDir, directory);
        if (restored > 0) {
            ModuleLogger.log("feature packs: restored " + restored + " file(s) from assets");
        }
    }

    /**
     * 本地覆盖目录：{@code files/override/<WX_PACK>/}。
     *
     * 该目录里的文件优先级最高，按文件名逐字节复制到功能包目录，
     * 覆盖同名文件。适合在不重装 APK 的前提下替换功能包。
     */
    private static void applyOverrides(File filesDir, File target) {
        File source = new File(new File(filesDir, OVERRIDE_DIR), BuildConfig.WX_PACK);
        if (!source.isDirectory()) {
            return;
        }
        File[] files = source.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        int applied = 0;
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) {
                continue;
            }
            String name = file.getName();
            if (VERSION_MARKER.equals(name) || MTIME_MARKER.equals(name)) {
                continue;
            }
            byte[] data = readAll(file);
            if (data == null) {
                continue;
            }
            File destination = new File(target, name);
            if (destination.isFile() && destination.length() == data.length
                    && sameContent(destination, data)) {
                continue;
            }
            if (writeIfMissing(target, name, data, 0, data.length, true)) {
                applied++;
            }
        }
        if (applied > 0) {
            ModuleLogger.log("feature packs: applied " + applied + " override(s)");
        }
    }

    private static boolean hasOverride(File filesDir, String name) {
        File file = new File(new File(new File(filesDir, OVERRIDE_DIR), BuildConfig.WX_PACK), name);
        return file.isFile() && file.length() > 0;
    }

    private static byte[] readAll(File file) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = 0;
            while (read < buffer.length) {
                int count = input.read(buffer, read, buffer.length - read);
                if (count <= 0) {
                    break;
                }
                read += count;
            }
            return read == buffer.length ? buffer : null;
        } catch (Throwable error) {
            ModuleLogger.log("feature packs: read override failed " + file.getName() + " " + error);
            return null;
        }
    }

    private static boolean sameContent(File file, byte[] data) {
        byte[] existing = readAll(file);
        return existing != null && java.util.Arrays.equals(existing, data);
    }

    private static void pruneUnknown(File directory, java.util.Set<String> allowed) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (VERSION_MARKER.equals(name) || allowed.contains(name)) {
                continue;
            }
            if (file.delete()) {
                ModuleLogger.debug("feature packs: pruned " + name);
            }
        }
    }

    private static void pruneStaleSidecars(File directory) {
        java.util.Set<String> assets = new java.util.LinkedHashSet<>();
        for (String name : ModuleApk.listAssets()) {
            assets.add(name);
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith("_c") && !name.endsWith("_s")) {
                continue;
            }
            if (!assets.contains(name) && file.delete()) {
                ModuleLogger.debug("feature packs: pruned stale " + name);
            }
        }
    }

    private static boolean skipAsset(String name) {
        if (name.endsWith("_c") || name.endsWith("_s")) {
            return true;
        }
        if ("shell.apk".equals(name) || "xposed_init".equals(name)) {
            return true;
        }
        if (name.startsWith("x3-")) {
            return true;
        }
        if (name.startsWith("8af2692bdcb8661da163b4204b5f5d5e")) {
            return true;
        }
        return false;
    }

    private static boolean matchesVersion(File directory) {
        File marker = new File(directory, VERSION_MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try (InputStream input = new java.io.FileInputStream(marker)) {
            byte[] buffer = new byte[32];
            int read = input.read(buffer);
            return read > 0 && String.valueOf(BuildConfig.VERSION_CODE)
                    .equals(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (Throwable error) {
            return false;
        }
    }

    private static void writeVersion(File directory) {
        File marker = new File(directory, VERSION_MARKER);
        byte[] data = String.valueOf(BuildConfig.VERSION_CODE)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(data);
        } catch (Throwable error) {
            ModuleLogger.log(error);
        }
    }

    /**
     * 内容指纹标记：把 assets 里所有功能条目的名字、长度与 CRC 拼成签名，
     * 与 {@link #VERSION_MARKER} 一起决定是否需要强制覆盖。
     *
     * 这样改了 assets 内容但忘了提 versionCode 时也能自动刷新，避免静默不生效。
     */
    private static String buildMtimeSignature() {
        java.lang.StringBuilder builder = new java.lang.StringBuilder();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String name : ModuleApk.listAssets()) {
            if (skipAsset(name)) {
                continue;
            }
            names.add(name);
        }
        java.util.Collections.sort(names);
        for (String name : names) {
            long crc = ModuleApk.assetSignature(name);
            builder.append(name).append(':').append(crc).append(';');
        }
        return java.util.UUID.nameUUIDFromBytes(
                builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static boolean matchesMtime(File directory) {
        File marker = new File(directory, MTIME_MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try (InputStream input = new java.io.FileInputStream(marker)) {
            byte[] buffer = new byte[64];
            int read = input.read(buffer);
            String stored = read > 0
                    ? new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim()
                    : "";
            return !stored.isEmpty() && stored.equals(buildMtimeSignature());
        } catch (Throwable error) {
            return false;
        }
    }

    private static void writeMtime(File directory) {
        File marker = new File(directory, MTIME_MARKER);
        byte[] data = buildMtimeSignature().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(data);
        } catch (Throwable error) {
            ModuleLogger.log(error);
        }
    }

private static boolean isMerged(byte[] data) {
        if (data.length < 16) {
            return false;
        }
        for (int index = 0; index < MERGED_MAGIC.length; index++) {
            if (data[index] != MERGED_MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    private static int restoreMerged(File directory, String name, byte[] data, boolean force) {
        int bodySize = readInt(data, 4);
        int sidecarSize = readInt(data, 8);
        int signatureSize = readInt(data, 12);
        if (bodySize < 0 || sidecarSize < 0 || signatureSize < 0
                || 16L + bodySize + sidecarSize + signatureSize != data.length) {
            ModuleLogger.log("feature packs: invalid merged entry " + name);
            return 0;
        }
        int restored = 0;
        int offset = 16;
        if (writeIfMissing(directory, name, data, offset, bodySize, force)) {
            restored++;
        }
        offset += bodySize;
        if (sidecarSize > 0) {
            if (writeIfMissing(directory, name + "_c", data, offset, sidecarSize, force)) {
                restored++;
            }
            offset += sidecarSize;
        }
        if (signatureSize > 0) {
            if (writeIfMissing(directory, name + "_s", data, offset, signatureSize, force)) {
                restored++;
            }
        }
        return restored;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24
                | (data[offset + 1] & 0xFF) << 16
                | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }

    private static boolean writeIfMissing(File directory, String name, byte[] data) {
        return writeIfMissing(directory, name, data, 0, data.length, false);
    }

    private static boolean writeIfMissing(File directory, String name, byte[] data,
            int offset, int length) {
        return writeIfMissing(directory, name, data, offset, length, false);
    }

    private static boolean writeIfMissing(File directory, String name, byte[] data,
            int offset, int length, boolean force) {
        File target = new File(directory, name);
        if (!force && target.isFile() && target.length() == length) {
            return false;
        }
        File temp = new File(directory, name + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(data, offset, length);
            output.flush();
            output.getFD().sync();
        } catch (Throwable error) {
            ModuleLogger.log(error);
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            if (target.delete() && temp.renameTo(target)) {
                target.setReadable(true, false);
                return true;
            }
            temp.delete();
            ModuleLogger.log("feature packs: cannot replace " + name);
            return false;
        }
        target.setReadable(true, false);
        return true;
    }
}
