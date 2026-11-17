package io.github.shadowbaby.wechatxposed.hook.loader.dex;

import io.github.shadowbaby.wechatxposed.hook.support.guard.WxComponentRegistry;
import io.github.shadowbaby.wechatxposed.hook.support.guard.WxComponentHooks;
import io.github.shadowbaby.wechatxposed.hook.loader.SecretConstants;
import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import io.github.shadowbaby.wechatxposed.hook.loader.util.Secret;
import io.github.shadowbaby.wechatxposed.shared.hook.HookCallback;
import io.github.shadowbaby.wechatxposed.shared.hook.HookFramework;
import io.github.shadowbaby.wechatxposed.shared.hook.HookParam;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;

public final class InMemoryDexLoadHook implements HookCallback {

    public static final int DEX_LOADER = 0;

    public static final int BASE_HANDSHAKE = 1;

    public static final int THEMES_HANDSHAKE = 2;

    public static final int DONATION_HANDSHAKE = 3;

    private static final String[] COMPONENT_ENTRY_CLASSES = {
            Secret.of(SecretConstants.COMPONENT_BASE),
            Secret.of(SecretConstants.COMPONENT_CORE),
            Secret.of(SecretConstants.COMPONENT_THEMES),
            Secret.of(SecretConstants.COMPONENT_DONATION)};

    private static final String ON_UPDATE_CHECK = Secret.of(SecretConstants.METHOD_ON_UPDATE_CHECK);
    private static final String DISABLE = Secret.of(SecretConstants.METHOD_DISABLE);
    private static final String PROCESS_METADATA = Secret.of(SecretConstants.METHOD_PROCESS_METADATA);
    private static final String UPDATE_LICENSE = Secret.of(SecretConstants.METHOD_UPDATE_LICENSE);
    private static final String DELAYED_CHECK = Secret.of(SecretConstants.METHOD_DELAYED_CHECK);

    private final HookFramework framework;
    private final int mode;

    public InMemoryDexLoadHook(HookFramework framework, int mode) {
        this.framework = framework;
        this.mode = mode;
    }

    @Override
    public void after(HookParam param) {
        if (mode != DEX_LOADER || !(param.thisObject() instanceof ClassLoader hostClassLoader)) {
            return;
        }
        try {
            dump(param);
        } catch (Throwable error) {
            ModuleLogger.log(error);
        }
        try {
            synchronized (WxComponentHooks.class) {
                ArrayList<WxComponentHooks> pending = WxComponentRegistry.WX_COMPONENTS;
                if (pending.isEmpty()) {
                    return;
                }
                Iterator<WxComponentHooks> iterator = pending.iterator();
                while (iterator.hasNext()) {
                    WxComponentHooks component = iterator.next();
                    if (framework.findClassIfExists(
                            COMPONENT_ENTRY_CLASSES[component.componentId],
                            hostClassLoader) == null) {
                        continue;
                    }
                    component.inject(framework, hostClassLoader);
                    iterator.remove();
                }
            }
        } catch (Throwable error) {
            ModuleLogger.log(error);
        }
    }

    @Override
    public void before(HookParam param) {
        if (mode == DEX_LOADER) {
            return;
        }
        String methodName = String.valueOf(param.arg(0));
        boolean block = ON_UPDATE_CHECK.equals(methodName);
        if (mode == BASE_HANDSHAKE) {
            block |= DISABLE.equals(methodName) || PROCESS_METADATA.equals(methodName);
        } else if (mode == DONATION_HANDSHAKE) {
            block |= DISABLE.equals(methodName) || UPDATE_LICENSE.equals(methodName)
                    || DELAYED_CHECK.equals(methodName);
        }
        if (block) {
            param.setResult(null);
        }
    }

    /** 把构造器收到的 dex 字节落到磁盘，供离线分析。 */
    private static void dump(HookParam param) {
        try {
            Object[] args = param.args();
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                byte[] bytes = toBytes(args[i]);
                if (bytes == null || !isDex(bytes)) {
                    continue;
                }
                ClassLoaderWatch.writeDex("imdex_dump", "arg" + i, bytes);
            }
        } catch (Throwable error) {
            ModuleLogger.log("dex dump failed: " + error);
        }
    }

    private static byte[] toBytes(Object arg) {
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
        if (arg instanceof byte[] bytes) {
            return bytes;
        }
        return null;
    }

    private static boolean isDex(byte[] bytes) {
        return bytes.length > 8
                && bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x' && bytes[3] == '\n';
    }
}
