package com.zafiro.wxentry.kernel;

import android.content.ContentValues;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Single shared database insert hook + update event bus across all runtimes. */
final class MessageInsertBus {
    interface BeforeInsertFilter {
        /** Return true to suppress/cancel insertion. */
        boolean shouldBlock(Object db, String table, ContentValues cv);
    }

    interface InsertListener {
        void onInsert(Object db, String table, ContentValues cv, long rowId);
    }

    interface UpdateListener {
        void onUpdate(Object db, String table, ContentValues cv, String where, String[] whereArgs);
    }

    private static final CopyOnWriteArrayList<BeforeInsertFilter> BEFORE_FILTERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<InsertListener> INSERT_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<UpdateListener> UPDATE_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean hooked;
    private static volatile int hookCount;

    private MessageInsertBus() {}

    static void addBeforeInsertFilter(BeforeInsertFilter filter) {
        if (filter != null && !BEFORE_FILTERS.contains(filter)) BEFORE_FILTERS.add(filter);
    }

    static void addInsertListener(InsertListener listener) {
        if (listener != null && !INSERT_LISTENERS.contains(listener)) INSERT_LISTENERS.add(listener);
    }

    static void addUpdateListener(UpdateListener listener) {
        if (listener != null && !UPDATE_LISTENERS.contains(listener)) UPDATE_LISTENERS.add(listener);
    }

    static int hookCount() {
        return hookCount;
    }

    static synchronized void install(ClassLoader loader) {
        if (hooked || loader == null) return;
        List<Class<?>> impls = Kernel.discoverDbImpls(loader);
        if (impls.isEmpty()) {
            for (String cn : new String[]{"ai5.b0", "ai5.d0", "t93.l", "o94.y", "wc2.j"}) {
                try { impls.add(XposedHelpers.findClass(cn, loader)); } catch (Throwable ignored) {}
            }
        }
        int n = 0;
        for (Class<?> k : impls) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getReturnType() != long.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3 || ps[0] != String.class || ps[1] != String.class
                        || !"android.content.ContentValues".equals(ps[2].getName())) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (Kernel.MODULE_OFF) return;
                            if (!(p.args[2] instanceof ContentValues)) return;
                            String table = String.valueOf(p.args[0]);
                            ContentValues cv = (ContentValues) p.args[2];
                            for (BeforeInsertFilter f : BEFORE_FILTERS) {
                                try {
                                    if (f.shouldBlock(p.thisObject, table, cv)) {
                                        p.setResult(Long.valueOf(0L));
                                        return;
                                    }
                                } catch (Throwable t) {
                                    KernelLog.debug("insert-filter err " + t);
                                }
                            }
                        }

                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable() || Kernel.MODULE_OFF) return;
                            Object res = p.getResult();
                            if (!(res instanceof Number)) return;
                            long rowId = ((Number) res).longValue();
                            if (rowId < 0) return;
                            if (!(p.args[2] instanceof ContentValues)) return;
                            String table = String.valueOf(p.args[0]);
                            ContentValues cv = (ContentValues) p.args[2];
                            for (InsertListener l : INSERT_LISTENERS) {
                                try {
                                    l.onInsert(p.thisObject, table, cv, rowId);
                                } catch (Throwable t) {
                                    KernelLog.debug("insert-listener err " + t);
                                }
                            }
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    Kernel.T("MessageInsertBus hook fail " + k.getName() + "." + m.getName() + " " + t);
                }
            }
        }
        hookCount = n;
        hooked = true;
        Kernel.T("MessageInsertBus ready insertHooks=" + n);
    }

    static void dispatchUpdate(Object db, String table, ContentValues cv, String where, String[] whereArgs) {
        if (Kernel.MODULE_OFF || UPDATE_LISTENERS.isEmpty()) return;
        for (UpdateListener l : UPDATE_LISTENERS) {
            try {
                l.onUpdate(db, table, cv, where, whereArgs);
            } catch (Throwable t) {
                KernelLog.debug("update-listener err " + t);
            }
        }
    }
}
