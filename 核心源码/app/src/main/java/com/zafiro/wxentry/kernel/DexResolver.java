package com.zafiro.wxentry.kernel;

import de.robv.android.xposed.XposedHelpers;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dynamic signature-based WeChat class & method resolver with version-keyed disk caching.
 * Fast-paths verified 8.0.80 symbols and falls back to DexIdx / bytecode signature scans.
 */
final class DexResolver {
    private static final String CACHE_NAME = "dex.resolve.cache";
    private static final ConcurrentHashMap<String, String> RESOLVED = new ConcurrentHashMap<>();
    private static volatile String loadedKey = "";
    private static volatile boolean cacheLoaded;

    private DexResolver() {}

    private static synchronized void ensureCacheLoaded() {
        String key = Kernel.dexIdxKey(Kernel.apkPath());
        if (cacheLoaded && key.equals(loadedKey)) return;
        cacheLoaded = true;
        loadedKey = key;
        RESOLVED.clear();
        File f = new File(Kernel.WXDIR + CACHE_NAME);
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String first = r.readLine();
            if (!("K=" + key).equals(first)) return;
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    RESOLVED.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    private static synchronized void saveCache() {
        File tmp = new File(Kernel.WXDIR + CACHE_NAME + ".tmp");
        File dst = new File(Kernel.WXDIR + CACHE_NAME);
        try {
            if (tmp.getParentFile() != null) tmp.getParentFile().mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                w.write("K=" + loadedKey + "\n");
                for (Map.Entry<String, String> e : RESOLVED.entrySet()) {
                    w.write(e.getKey() + "=" + e.getValue() + "\n");
                }
            } finally {
                w.close();
            }
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        } catch (Throwable t) {
            KernelLog.debug("DexResolver cache save err " + t);
        }
    }

    static Class<?> load(ClassLoader cl, String name) {
        if (cl == null || name == null || name.isEmpty()) return null;
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?> resolveClass(ClassLoader cl, String key, String[] candidates, SignatureCheck check) {
        ensureCacheLoaded();
        String cached = RESOLVED.get(key);
        if (cached != null) {
            Class<?> c = load(cl, cached);
            if (c != null && (check == null || check.matches(c))) return c;
        }
        if (candidates != null) {
            for (String cand : candidates) {
                Class<?> c = load(cl, cand);
                if (c != null && (check == null || check.matches(c))) {
                    RESOLVED.put(key, c.getName());
                    saveCache();
                    return c;
                }
            }
        }
        return null;
    }

    interface SignatureCheck {
        boolean matches(Class<?> c);
    }

    /** Account core service locator: `zp0.j1` (8.0.80) or `u70.k1` (8.0.49). */
    static Class<?> serviceLocator(ClassLoader cl) {
        return resolveClass(cl, "svc_locator", new String[]{"zp0.j1", "u70.k1"}, c -> {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Class.class && "v".equals(m.getName())) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        });
    }

    /** Storage service interface: `gp3.c4` (8.0.80). */
    static Class<?> serviceIface(ClassLoader cl) {
        return resolveClass(cl, "svc_iface", new String[]{"gp3.c4"}, c -> c.isInterface());
    }

    /** Kernel netscene queue (`j1.q().b`) dispatch helper. */
    static boolean sendNetScene(ClassLoader cl, Object scene) {
        if (cl == null || scene == null) return false;
        try {
            Class<?> loc = serviceLocator(cl);
            if (loc == null) return false;
            Object q = null;
            try {
                q = XposedHelpers.callStaticMethod(loc, "q");
            } catch (Throwable ignored) {}
            if (q == null) {
                Object j = XposedHelpers.callStaticMethod(loc, "j");
                q = XposedHelpers.callMethod(j, "q");
            }
            Object queue = XposedHelpers.getObjectField(q, "b");
            XposedHelpers.callMethod(queue, "g", scene);
            return true;
        } catch (Throwable t) {
            KernelLog.info("sendNetScene err " + t);
            return false;
        }
    }

    /**
     * Resolves WeChat's 7-arg text send helper `void a(String, String, int, int, Map, String, String)`
     * (`di0.c.a` on 8.0.80). Falls back to DexIdx + method-proto scan across base APK & Tinker dex.
     */
    static Method msgSendMethod(ClassLoader cl) {
        ensureCacheLoaded();
        String cached = RESOLVED.get("msg_send");
        if (cached != null) {
            int dot = cached.lastIndexOf('.');
            if (dot > 0) {
                Class<?> c = load(cl, cached.substring(0, dot));
                Method m = find7ArgSend(c, cached.substring(dot + 1));
                if (m != null) return m;
            }
        }
        Class<?> fast = load(cl, "di0.c");
        Method m = find7ArgSend(fast, "a");
        if (m == null) m = find7ArgSend(fast, null);
        if (m == null) {
            List<String> cands = Kernel.dexIdx().msgSend != null
                    ? new ArrayList<>(Kernel.dexIdx().msgSend) : new ArrayList<>();
            if (cands.isEmpty()) cands = scan7ArgSendCandidates();
            for (String desc : cands) {
                String cn = desc.startsWith("L") && desc.endsWith(";")
                        ? desc.substring(1, desc.length() - 1).replace('/', '.') : desc;
                Class<?> c = load(cl, cn);
                m = find7ArgSend(c, null);
                if (m != null) break;
            }
        }
        if (m != null) {
            RESOLVED.put("msg_send", m.getDeclaringClass().getName() + "." + m.getName());
            saveCache();
        }
        return m;
    }

    private static Method find7ArgSend(Class<?> c, String requiredName) {
        if (c == null) return null;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (requiredName != null && !requiredName.equals(m.getName())) continue;
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 7 && ps[0] == String.class && ps[1] == String.class
                        && ps[2] == int.class && ps[3] == int.class
                        && Map.class.isAssignableFrom(ps[4])
                        && ps[5] == String.class && ps[6] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static boolean sendTextMessage(ClassLoader cl, String talker, String content) {
        return sendTextMessage(cl, talker, content, null);
    }

    static boolean sendTextMessage(ClassLoader cl, String talker, String content, String msgSource) {
        if (cl == null || talker == null || talker.isEmpty() || content == null || content.isEmpty()) {
            return false;
        }
        try {
            Method m = msgSendMethod(cl);
            if (m == null) return false;
            m.invoke(null, talker, content, Integer.valueOf(1), Integer.valueOf(0), null, msgSource, null);
            return true;
        } catch (Throwable t) {
            KernelLog.stack("DexResolver.sendTextMessage", t);
            return false;
        }
    }

    /** Resolves VerifyUser NetScene (`com.tencent.mm.pluginsdk.model.r3` on 8.0.80). */
    static Class<?> verifyUserClass(ClassLoader cl) {
        Class<?> cls = resolveClass(cl, "verify_user",
                new String[]{"com.tencent.mm.pluginsdk.model.r3"},
                c -> hasVerifyCtor(c));
        if (cls != null) return cls;
        List<String> scanned = scanClassByString("com/tencent/mm/pluginsdk/model/", "/cgi-bin/micromsg-bin/verifyuser");
        for (String cn : scanned) {
            Class<?> c = load(cl, cn);
            if (c != null && hasVerifyCtor(c)) {
                RESOLVED.put("verify_user", c.getName());
                saveCache();
                return c;
            }
        }
        return null;
    }

    private static boolean hasVerifyCtor(Class<?> c) {
        if (c == null) return false;
        try {
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                Class<?>[] ps = ctor.getParameterTypes();
                if (ps.length == 8 && ps[0] == int.class && ps[1] == String.class
                        && ps[2] == String.class && ps[3] == int.class && ps[4] == String.class
                        && ps[5] == int.class && List.class.isAssignableFrom(ps[6])) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static List<String> scan7ArgSendCandidates() {
        final List<String> out = new ArrayList<>();
        scanAllDexBytes(bytes -> Kernel.dexScan7ArgSend(bytes, out));
        return out;
    }

    private static List<String> scanClassByString(final String pkgPrefix, final String needle) {
        final List<String> out = new ArrayList<>();
        scanAllDexBytes(bytes -> {
            // Cheap byte pre-filter before inspecting dex headers
            if (!containsAscii(bytes, needle)) return;
            Kernel.dexScanClassesWithPrefix(bytes, pkgPrefix, out);
        });
        return out;
    }

    private interface ByteVisitor {
        void visit(byte[] dexBytes);
    }

    private static void scanAllDexBytes(ByteVisitor visitor) {
        String apk = Kernel.apkPath();
        if (apk != null) visitZipDex(new File(apk), visitor);
        for (File tf : Kernel.tinkerDexZips()) visitZipDex(tf, visitor);
    }

    private static void visitZipDex(File file, ByteVisitor visitor) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(file);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String nm = e.getName();
                if (!nm.startsWith("classes") || !nm.endsWith(".dex")) continue;
                try {
                    visitor.visit(Kernel.readAll(zf.getInputStream(e)));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean containsAscii(byte[] hay, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (n.length == 0 || hay.length < n.length) return false;
        outer:
        for (int i = 0; i <= hay.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static Map<String, String> collectDiagnostics(ClassLoader cl) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("核心类加载器", cl == null ? "null" : cl.getClass().getSimpleName());
        out.put("DexIdx 来源", Kernel.DEX_IDX_SRC + " (DB实现="
                + (Kernel.DEX_IDX == null ? 0 : Kernel.DEX_IDX.dbImpls.size()) + ")");
        out.put("服务定位器", serviceLocator(cl) != null ? RESOLVED.getOrDefault("svc_locator", "zp0.j1") : "未找到");
        out.put("发消息接口", msgSendMethod(cl) != null ? RESOLVED.getOrDefault("msg_send", "di0.c.a") : "未找到");
        out.put("VerifyUser", verifyUserClass(cl) != null ? RESOLVED.getOrDefault("verify_user", "r3") : "未找到");
        out.put("DB Insert Bus", MessageInsertBus.hookCount() + " 个钩子");
        out.put("DB Update Hooks", Kernel.HOOK_WRITE_N + " 个钩子");
        out.put("AddMsg Hooks", Kernel.HOOK_ADDMSG_N + " 个钩子");
        out.put("防撤回 UI", RecallMarkLayout.ready ? "已就绪" : "未就绪");
        out.put("媒体防撤回", MediaRecallRuntime.cache != null ? "已就绪" : "未就绪");
        out.put("自动原图", OriginalImageRuntime.io != null ? "已就绪" : "未就绪");
        out.put("自动转账", TransferRuntime.ready() ? "已就绪" : "未就绪");
        out.put("骰子/猜拳控制", GameEmojiRuntime.ready ? "已就绪" : "未就绪");
        out.put("消息复读", MessageRepeatRuntime.available() ? "已就绪" : "未就绪");
        out.put("语音自动转文字", VoiceTextRuntime.available() ? "已就绪" : "未就绪");
        out.put("自动回复/好友", AutoReplyRuntime.ready() ? "已就绪" : "未就绪");
        out.put("群成员/拍一拍管理", GroupGuardRuntime.ready() ? "已就绪" : "未就绪");
        out.put("微信运动步数修改", SportStepRuntime.ready() ? "已就绪" : "未就绪");
        out.put("隐私增强(防输入/防窥/外链)", PrivacyEnhanceRuntime.ready() ? "已就绪" : "未就绪");
        out.put("界面净化(发现页/红点)", UiCleanRuntime.ready() ? "已就绪" : "未就绪");
        out.put("语音倍速与表情原图", VoiceSpeedRuntime.ready() ? "已就绪" : "未就绪");
        out.put("卡片消息过滤", CardFilterRuntime.ready() ? "已就绪" : "未就绪");
        out.put("突破转发限制", ForwardLimitRuntime.ready() ? "已就绪" : "未就绪");
        out.put("定时消息队列", ScheduledMessageRuntime.ready() ? "已就绪" : "未就绪");
        out.put("朋友圈高级增强", MomentsAdvancedRuntime.ready() ? "已就绪" : "未就绪");
        out.put("视频号视频提取", FinderDownloadRuntime.ready() ? "已就绪" : "未就绪");
        out.put("真名查询命中", Kernel.RN_QUERIES.get() + " 次 / 缓存 " + Kernel.RN_MAP.size() + " 人");
        return out;
    }
}
