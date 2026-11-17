package com.zafiro.wxentry.kernel;

import java.io.File;
import java.util.List;

final class GameEmojiStore {
    static String owner() {
        String account = Kernel.receiptAccount();
        if (!ReceiptConfig.validId(account)) account = "wxid_default";
        return ModuleState.hash(account);
    }

    static String path(String owner) {
        if (owner == null || !owner.matches("[a-f0-9]{64}")) owner = ModuleState.hash("wxid_default");
        return Kernel.WXDIR + "gameemoji.settings." + owner;
    }

    static GameEmojiData read(String owner) {
        String p = path(owner);
        File f = new File(p);
        if (f.length() > 65536) throw new IllegalStateException("结果设置过大");
        String raw = ModuleState.read(p);
        if (f.exists() && raw == null) throw new IllegalStateException("结果设置读取失败");
        return GameEmojiData.decode(raw, owner);
    }

    static boolean save(GameEmojiData data) {
        return ModuleState.write(path(data.owner), data.encode());
    }

    static synchronized GameEmojiData current() {
        return read(owner());
    }

    static synchronized boolean enabled() {
        try { return !Kernel.MODULE_OFF && current().enabled; }
        catch (Throwable unavailable) { return false; }
    }

    static synchronized boolean setEnabled(boolean on) {
        try {
            if (Kernel.MODULE_OFF) return false;
            GameEmojiData d = current();
            boolean before = d.enabled;
            d.enabled = on;
            return save(d) ? on : before;
        } catch (Throwable failure) {
            return enabled();
        }
    }

    static synchronized boolean askBeforeSend() {
        try { return !Kernel.MODULE_OFF && current().askBeforeSend; }
        catch (Throwable t) { return false; }
    }

    static synchronized boolean setAskBeforeSend(boolean on) {
        try {
            if (Kernel.MODULE_OFF) return false;
            GameEmojiData d = current();
            boolean before = d.askBeforeSend;
            d.askBeforeSend = on;
            if (on && !d.enabled) d.enabled = true;
            return save(d) ? on : before;
        } catch (Throwable failure) {
            return askBeforeSend();
        }
    }

    static synchronized boolean configure(String owner, GameEmojiData.Kind kind, int fallback, List<Integer> next) {
        return configureChat(owner, null, null, kind, fallback, next);
    }

    static synchronized boolean configureChat(String owner, String talker, GameEmojiData.ChatMode mode,
                                              GameEmojiData.Kind kind, int fallback, List<Integer> next) {
        if (Kernel.MODULE_OFF) return false;
        String cur = owner();
        GameEmojiData d = read(cur);
        if (talker == null || talker.trim().isEmpty()) {
            d.configure(kind, fallback, next);
        } else {
            String key = talker.trim();
            if (mode == null || mode == GameEmojiData.ChatMode.INHERIT) {
                d.chatRules.remove(key);
            } else {
                GameEmojiData.ChatRule cr = d.ensureChatRule(key);
                cr.mode = mode;
                if (mode == GameEmojiData.ChatMode.CUSTOM) {
                    d.configure(key, kind, fallback, next);
                }
            }
        }
        return save(d);
    }
}
