package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GameEmojiRuntime {
    static ClassLoader cl;
    static Class<?> emoji, model;
    static Constructor<?> modelCtor;
    static boolean ready;
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final Map<String, Object> EMOJI_CACHE = new ConcurrentHashMap<String, Object>();

    static Class<?> cls(String name) throws Throwable {
        return Class.forName(name, false, cl);
    }

    static String str(Object object, String method) {
        try {
            return String.valueOf(XposedHelpers.callMethod(object, method));
        } catch (Throwable t) {
            return "";
        }
    }

    static final int[] ACTIVE_OUTCOME = new int[2];
    static final ThreadLocal<Integer> REPLAY_ASK = new ThreadLocal<>();
    static final Runnable CLEAR_ACTIVE_DICE = new Runnable() { public void run() { ACTIVE_OUTCOME[0] = 0; } };
    static final Runnable CLEAR_ACTIVE_RPS = new Runnable() { public void run() { ACTIVE_OUTCOME[1] = 0; } };

    static int num(Object object, String method) {
        try {
            Object v = XposedHelpers.callMethod(object, method);
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Throwable t) {
            return -1;
        }
    }

    static GameEmojiData.Kind kind(Object value) {
        if (value == null || !emoji.isInstance(value)) return null;
        try {
            int cat = num(value, "getGroup");
            if (cat == 50) return GameEmojiData.Kind.DICE;
            if (cat == 49) return GameEmojiData.Kind.RPS;
            int grp = -1;
            try { grp = Integer.parseInt(str(value, "j")); } catch (Throwable ignored) {}
            if (grp == 50) return GameEmojiData.Kind.DICE;
            if (grp == 49) return GameEmojiData.Kind.RPS;
            String name = str(value, "getName");
            if (name != null) {
                name = name.toLowerCase();
                if (name.contains("dice")) return GameEmojiData.Kind.DICE;
                if (name.contains("jsb")) return GameEmojiData.Kind.RPS;
            }
            String md5 = str(value, "getMd5");
            if (md5 != null) {
                if (md5.equals("08f223fa83f1ca34e143d1e580252c7c")) return GameEmojiData.Kind.DICE;
                if (md5.equals("9bd1281af3a31710a45b84d736363691")) return GameEmojiData.Kind.RPS;
            }
        } catch (Throwable t) {
            Kernel.T("kind check err " + t);
        }
        return null;
    }

    static synchronized int consumeOutcome(GameEmojiData.Kind k, String stage) {
        return consumeOutcome(k, stage, Kernel.currentChatTalker());
    }

    static synchronized int consumeOutcome(GameEmojiData.Kind k, String stage, String talker) {
        int idx = k == GameEmojiData.Kind.DICE ? 0 : 1;
        if (Looper.myLooper() == Looper.getMainLooper() && ACTIVE_OUTCOME[idx] > 0) {
            return ACTIVE_OUTCOME[idx];
        }
        GameEmojiData data = GameEmojiStore.current();
        if (!data.enabled) return 0;
        if (talker == null || talker.isEmpty()) talker = Kernel.currentChatTalker();
        if (data.isBlockedFor(talker)) return 0;

        GameEmojiData.Slot slot = data.effectiveSlot(k, talker);
        if (slot == null) return 0;
        int wanted = slot.next.isEmpty() ? slot.fallback : slot.next.get(0);
        if (wanted > 0) {
            if (!slot.next.isEmpty()) {
                slot.next.remove(0);
                GameEmojiStore.save(data);
                Kernel.T("GAMEEMOJI sequence advanced at " + stage + " talker=" + talker + ", remaining=" + slot.next.size());
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                ACTIVE_OUTCOME[idx] = wanted;
                Runnable clear = idx == 0 ? CLEAR_ACTIVE_DICE : CLEAR_ACTIVE_RPS;
                MAIN.removeCallbacks(clear);
                MAIN.post(clear);
            }
        }
        return wanted;
    }

    static int result(GameEmojiData.Kind kind, Object value) {
        if (value == null) return -1;
        String name = str(value, "getName");
        if (name == null || name.isEmpty() || "null".equals(name)) return -1;
        name = name.toLowerCase();
        if (kind == GameEmojiData.Kind.DICE) {
            Matcher m = Pattern.compile("dice_([1-6])").matcher(name);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        if (kind == GameEmojiData.Kind.RPS) {
            if (name.contains("jsb_s")) return 1; // 石头
            if (name.contains("jsb_j")) return 2; // 剪刀
            if (name.contains("jsb_b")) return 3; // 布
        }
        return -1;
    }

    static Object select(Object original, GameEmojiData.Kind kind, int target) throws Throwable {
        String cacheKey = kind.key + "_" + target;
        Object cached = EMOJI_CACHE.get(cacheKey);
        if (cached != null) return cached;

        int group = kind == GameEmojiData.Kind.DICE ? 50 : 49;
        try {
            int g = Integer.parseInt(str(original, "j"));
            if (g == 49 || g == 50) group = g;
        } catch (Throwable ignored) {}

        Object singleton = XposedHelpers.callStaticMethod(cls("com.tencent.mm.storage.n5"), "f");
        Object storage = XposedHelpers.callMethod(singleton, "c");
        Cursor cursor = (Cursor) XposedHelpers.callMethod(storage, "D3", group);
        try {
            int count = 0;
            while (cursor != null && cursor.moveToNext() && ++count <= 32) {
                Object candidate = emoji.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(candidate, "convertFrom", cursor);
                int r = result(kind, candidate);
                if (r > 0) {
                    EMOJI_CACHE.put(kind.key + "_" + r, candidate);
                    Kernel.T("GAMEEMOJI cached kind=" + kind.key + " result=" + r + " name=" + str(candidate, "getName"));
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        Object resultObj = EMOJI_CACHE.get(cacheKey);
        if (resultObj != null) return resultObj;
        throw new IllegalStateException("Native game outcome missing: " + kind + " target=" + target);
    }

    static void start(ClassLoader loader) throws Throwable {
        if (ready) return;
        cl = loader;
        emoji = cls("com.tencent.mm.storage.emotion.EmojiInfo");
        model = cls("vr.g");
        Class<?> info = cls("com.tencent.mm.api.IEmojiInfo");
        modelCtor = model.getDeclaredConstructor(info, int.class, String.class, int.class);


        Method click = cls("yr.p").getDeclaredMethod("b", View.class, android.content.Context.class, int.class, cls("vr.u0"));
        Method longPress = cls("ow5.e").getDeclaredMethod("a", View.class, Object.class);
        Method move = cls("ow5.e").getDeclaredMethod("b", View.class, Object.class);
        Method dispatch = cls("i82.h").getDeclaredMethod("Z", String.class, emoji, cls("com.tencent.mm.storage.f9"), cls("ga5.d"), int.class);

        // Long press on emoji panel: open settings dialog
        XposedBridge.hookMethod(longPress, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!GameEmojiStore.enabled()) return;
                try {
                    Object value = p.args[1];
                    if (!model.isInstance(value)) return;
                    Object orig = XposedHelpers.getObjectField(value, "b");
                    final GameEmojiData.Kind k = kind(orig);
                    if (k == null) return;
                    final View anchor = (View) p.args[0];
                    if (!anchor.isEnabled()) return;
                    p.setResult(Boolean.TRUE);
                    anchor.post(new Runnable() {
                        public void run() {
                            GameEmojiUi.show(anchor, k);
                        }
                    });
                    Kernel.T("GAMEEMOJI longpress configure kind=" + k.key);
                } catch (Throwable failure) {
                    error("longpress", failure);
                }
            }
        });

        // Cancel move drag on dice/morra
        XposedBridge.hookMethod(move, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!GameEmojiStore.enabled()) return;
                try {
                    if (model.isInstance(p.args[1]) && kind(XposedHelpers.getObjectField(p.args[1], "b")) != null) {
                        p.setResult(null);
                    }
                } catch (Throwable ignored) {}
            }
        });

        // Click on emoji panel: ask-before-send OR replace model if custom outcome desired
        XposedBridge.hookMethod(click, new XC_MethodHook() {
            protected void beforeHookedMethod(final MethodHookParam p) {
                if (!GameEmojiStore.enabled()) return;
                try {
                    if (!model.isInstance(p.args[3])) return;
                    final Object old = p.args[3];
                    final Object orig = XposedHelpers.getObjectField(old, "b");
                    final GameEmojiData.Kind k = kind(orig);
                    if (k == null) return;
                    String talker = Kernel.currentChatTalker();
                    GameEmojiData data = GameEmojiStore.current();
                    if (data.isBlockedFor(talker)) return;

                    Integer replayed = REPLAY_ASK.get();
                    if (data.askBeforeSend && replayed == null) {
                        Activity act = GameEmojiUi.activity(p.args[1] instanceof android.content.Context ? (android.content.Context) p.args[1] : null);
                        if (act == null && p.args[0] instanceof View) act = GameEmojiUi.activity(((View) p.args[0]).getContext());
                        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                            p.setResult(null);
                            final Object targetObj = p.thisObject;
                            final Method clickMethod = (Method) p.method;
                            final Object[] origArgs = p.args.clone();
                            GameEmojiUi.showAskPicker(act, k, chosen -> {
                                int idx = k == GameEmojiData.Kind.DICE ? 0 : 1;
                                try {
                                    REPLAY_ASK.set(Integer.valueOf(chosen));
                                    if (chosen > 0) {
                                        ACTIVE_OUTCOME[idx] = chosen;
                                        Runnable clear = idx == 0 ? CLEAR_ACTIVE_DICE : CLEAR_ACTIVE_RPS;
                                        MAIN.removeCallbacks(clear);
                                        MAIN.post(clear);
                                    }
                                    clickMethod.invoke(targetObj, origArgs);
                                } catch (Throwable err) {
                                    error("ask-replay", err);
                                } finally {
                                    REPLAY_ASK.remove();
                                }
                            });
                            return;
                        }
                    }

                    int wanted = (replayed != null && replayed.intValue() > 0)
                            ? replayed.intValue()
                            : consumeOutcome(k, "click", talker);
                    if (wanted > 0) {
                        Object selected = select(orig, k, wanted);
                        if (selected != null) {
                            Object replacement = modelCtor.newInstance(selected,
                                    XposedHelpers.getIntField(old, "c"),
                                    XposedHelpers.getObjectField(old, "d"),
                                    XposedHelpers.getIntField(old, "e"));
                            p.args[3] = replacement;
                            Kernel.T("GAMEEMOJI click replaced kind=" + k.key + " wanted=" + wanted);
                        }
                    }
                } catch (Throwable failure) {
                    error("click", failure);
                }
            }
        });

        // Native getRandomEmoji hooks (i82.h.d & ze5.c.d)
        XC_MethodHook randomHook = new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (!GameEmojiStore.enabled()) return;
                try {
                    Object input = p.args != null && p.args.length > 0 ? p.args[0] : null;
                    Object out = p.getResult();
                    GameEmojiData.Kind k = kind(input);
                    if (k == null) k = kind(out);
                    if (k == null) return;
                    int wanted = consumeOutcome(k, "getRandomEmoji");
                    if (wanted > 0) {
                        Object target = select(input != null ? input : out, k, wanted);
                        if (target != null) {
                            p.setResult(target);
                            Kernel.T("GAMEEMOJI getRandomEmoji replaced kind=" + k.key + " target=" + wanted + " name=" + str(target, "getName"));
                        }
                    }
                } catch (Throwable failure) {
                    error("random", failure);
                }
            }
        };
        try { XposedBridge.hookMethod(cls("i82.h").getDeclaredMethod("d", emoji), randomHook); } catch (Throwable t) { error("hook-h-d", t); }
        try { XposedBridge.hookMethod(cls("ze5.c").getDeclaredMethod("d", emoji), randomHook); } catch (Throwable t) { error("hook-c-d", t); }

        // Dispatch outgoing emoji: GUARANTEED interception across Z / Y / Vj / Nj
        XC_MethodHook dispatchEmojiArg1 = new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!GameEmojiStore.enabled()) return;
                try {
                    Object originalEmoji = p.args[1];
                    GameEmojiData.Kind k = kind(originalEmoji);
                    if (k == null) return;
                    String peer = (p.args.length > 0 && p.args[0] instanceof String) ? (String) p.args[0] : Kernel.currentChatTalker();
                    int wanted = consumeOutcome(k, p.method.getName(), peer);
                    if (wanted > 0) {
                        Object targetEmoji = select(originalEmoji, k, wanted);
                        if (targetEmoji != null) {
                            p.args[1] = targetEmoji;
                            Kernel.T("GAMEEMOJI " + p.method.getName() + " replaced kind=" + k.key + " target=" + wanted + " name=" + str(targetEmoji, "getName"));
                        }
                    }
                } catch (Throwable failure) {
                    error("dispatch", failure);
                }
            }
        };
        XposedBridge.hookMethod(dispatch, dispatchEmojiArg1);
        try { XposedBridge.hookAllMethods(cls("i82.h"), "Y", dispatchEmojiArg1); } catch (Throwable t) { error("hook-h-Y", t); }
        try { XposedBridge.hookAllMethods(cls("com.tencent.mm.feature.emoji.b0"), "Vj", dispatchEmojiArg1); } catch (Throwable t) { error("hook-b0-Vj", t); }
        try {
            XposedBridge.hookAllMethods(cls("k60.t"), "Nj", new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!GameEmojiStore.enabled()) return;
                    try {
                        Object kb = p.args != null && p.args.length > 0 ? p.args[0] : null;
                        if (kb == null) return;
                        for (java.lang.reflect.Field f : kb.getClass().getDeclaredFields()) {
                            if (!f.getType().isPrimitive() && f.getType().isAssignableFrom(emoji)) {
                                f.setAccessible(true);
                                Object val = f.get(kb);
                                if (emoji.isInstance(val)) {
                                    GameEmojiData.Kind k = kind(val);
                                    if (k != null) {
                                        int wanted = consumeOutcome(k, "Nj");
                                        if (wanted > 0) {
                                            Object targetEmoji = select(val, k, wanted);
                                            if (targetEmoji != null) {
                                                f.set(kb, targetEmoji);
                                                Kernel.T("GAMEEMOJI Nj replaced kind=" + k.key + " target=" + wanted + " name=" + str(targetEmoji, "getName"));
                                            }
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    } catch (Throwable failure) {
                        error("nj", failure);
                    }
                }
            });
        } catch (Throwable t) { error("hook-t-Nj", t); }

        ready = true;
        Kernel.T("GAMEEMOJI ready allEditions=true dualGuarantee=true");
    }

    static void configured(String owner, GameEmojiData.Kind kind) {
        // Refresh local cache if needed
    }

    static void tell(final Activity activity, final String message) {
        if (activity != null) {
            MAIN.post(new Runnable() {
                public void run() {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        GameEmojiUi.toast(activity, message);
                    }
                }
            });
        }
    }

    static void error(String stage, Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        Kernel.T("GAMEEMOJI " + stage + "-error " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
}
