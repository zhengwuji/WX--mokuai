package io.github.shadowbaby.wechatxposed.hook.loader.dex;

import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import io.github.shadowbaby.wechatxposed.shared.hook.HookCallback;
import io.github.shadowbaby.wechatxposed.shared.hook.HookParam;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches every dex-holding ClassLoader constructor in the WeChat process and dumps whatever
 * byte array / ByteBuffer it is handed. Independent from {@link InMemoryDexLoadHook} so that
 * packs loaded through paths other than InMemoryDexClassLoader are still captured.
 */
public final class ClassLoaderWatch implements HookCallback {

    private static final String DUMP_DIR = "wxh_dump";

    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    private static volatile String dumpRoot;

    private final String tag;

    public ClassLoaderWatch(String tag) {
        this.tag = tag;
    }

    public static void setDumpRoot(String root) {
        dumpRoot = root;
    }

    @Override
    public void before(HookParam param) {
        // nothing
    }

    @Override
    public void after(HookParam param) {
        try {
            Object[] args = param.args();
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                byte[] bytes = toBytes(arg);
                if (bytes != null && isDex(bytes)) {
                    write(bytes, "arg" + i);
                    continue;
                }
                if (arg instanceof File file) {
                    dumpFile(file);
                } else if (arg instanceof String text && text.endsWith(".dex")) {
                    dumpFile(new File(text));
                }
            }
        } catch (Throwable error) {
            ModuleLogger.log(error);
        }
    }

    private static byte[] toBytes(Object arg) {
        if (arg instanceof byte[] bytes) {
            return bytes;
        }
        if (arg instanceof ByteBuffer buffer) {
            try {
                ByteBuffer copy = buffer.duplicate();
                copy.position(0);
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                return bytes;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isDex(byte[] bytes) {
        return bytes.length > 8
                && bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x' && bytes[3] == '\n';
    }

    private void dumpFile(File file) {
        try {
            if (file == null || !file.isFile() || file.length() == 0) {
                return;
            }
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < bytes.length) {
                    int read = in.read(bytes, off, bytes.length - off);
                    if (read < 0) {
                        break;
                    }
                    off += read;
                }
            }
            if (isDex(bytes)) {
                write(bytes, "file");
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void write(byte[] bytes, String source) {
        writeDex(tag, source, bytes);
    }

    /** 供其它 dump 入口复用。 */
    public static void writeDex(String prefix, String source, byte[] bytes) {
        File root = resolveRoot();
        if (root == null) {
            return;
        }
        int seq = SEQ.incrementAndGet();
        File out = new File(root, prefix + "_" + source + "_" + seq + "_" + bytes.length + ".dex");
        try {
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(bytes);
            }
            ModuleLogger.log("dex dump: " + out.getAbsolutePath() + " size=" + bytes.length);
        } catch (Throwable error) {
            ModuleLogger.log("dex dump failed: " + out.getAbsolutePath() + " " + error);
        }
    }

    private static File resolveRoot() {
        File root = null;
        String configured = dumpRoot;
        if (configured != null && !configured.isEmpty()) {
            File parent = new File(configured);
            if (parent.isDirectory()) {
                root = new File(parent, DUMP_DIR);
            }
        }
        if (root == null) {
            root = new File("/sdcard", DUMP_DIR);
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            root = new File(dataDir(), DUMP_DIR);
            if (!root.isDirectory() && !root.mkdirs()) {
                return null;
            }
        }
        return root;
    }

    private static File dataDir() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app != null) {
                return (File) app.getClass().getMethod("getDataDir").invoke(app);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return new File("/data/local/tmp");
    }

    @SuppressWarnings("unused")
    private static Object fieldOf(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
