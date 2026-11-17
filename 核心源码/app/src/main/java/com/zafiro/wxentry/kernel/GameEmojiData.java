package com.zafiro.wxentry.kernel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, account-owned outcome queues + optional ask-before-send mode + per-chat overrides.
 * Backward compatible with legacy format=1 settings files.
 */
final class GameEmojiData {
    static final int MAX = 256;

    enum Kind {
        DICE("dice", "骰子", 6), RPS("rps", "石头剪刀布", 3);
        final String key, label;
        final int max;
        Kind(String k, String l, int m) { key = k; label = l; max = m; }
    }

    enum ChatMode {
        INHERIT, DISABLE, CUSTOM
    }

    static final class Slot {
        int fallback, chosen;
        boolean queued;
        String ticket = "";
        final List<Integer> next = new ArrayList<>();

        Slot copy() {
            Slot s = new Slot();
            s.fallback = fallback;
            s.chosen = chosen;
            s.queued = queued;
            s.ticket = ticket;
            s.next.addAll(next);
            return s;
        }

        void clearClaim() {
            ticket = "";
            chosen = 0;
            queued = false;
        }
    }

    static final class ChatRule {
        ChatMode mode = ChatMode.INHERIT;
        Slot dice = new Slot();
        Slot rps = new Slot();

        Slot slot(Kind k) {
            return k == Kind.DICE ? dice : rps;
        }

        ChatRule copy() {
            ChatRule c = new ChatRule();
            c.mode = mode;
            c.dice = dice.copy();
            c.rps = rps.copy();
            return c;
        }
    }

    final String owner;
    boolean enabled;
    boolean askBeforeSend;
    Slot dice = new Slot(), rps = new Slot();
    final Map<String, ChatRule> chatRules = new LinkedHashMap<>();

    GameEmojiData(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid owner");
        owner = hash;
    }

    Slot slot(Kind k) {
        if (k == null) throw new IllegalArgumentException("Missing kind");
        return k == Kind.DICE ? dice : rps;
    }

    ChatRule chatRule(String talker) {
        if (talker == null || talker.trim().isEmpty()) return null;
        return chatRules.get(talker.trim());
    }

    ChatRule ensureChatRule(String talker) {
        String key = talker.trim();
        ChatRule r = chatRules.get(key);
        if (r == null) {
            r = new ChatRule();
            chatRules.put(key, r);
        }
        return r;
    }

    Slot effectiveSlot(Kind k, String talker) {
        ChatRule r = chatRule(talker);
        if (r != null) {
            if (r.mode == ChatMode.DISABLE) return null;
            if (r.mode == ChatMode.CUSTOM) return r.slot(k);
        }
        return slot(k);
    }

    boolean isBlockedFor(String talker) {
        ChatRule r = chatRule(talker);
        return r != null && r.mode == ChatMode.DISABLE;
    }

    GameEmojiData copy() {
        GameEmojiData d = new GameEmojiData(owner);
        d.enabled = enabled;
        d.askBeforeSend = askBeforeSend;
        d.dice = dice.copy();
        d.rps = rps.copy();
        for (Map.Entry<String, ChatRule> e : chatRules.entrySet()) {
            d.chatRules.put(e.getKey(), e.getValue().copy());
        }
        return d;
    }

    int peek(Kind k) {
        return peek(k, null);
    }

    int peek(Kind k, String talker) {
        Slot s = effectiveSlot(k, talker);
        if (s == null) return 0;
        return s.next.isEmpty() ? s.fallback : s.next.get(0);
    }

    void configure(Kind k, int fallback, List<Integer> next) {
        configure(null, k, fallback, next);
    }

    void configure(String talker, Kind k, int fallback, List<Integer> next) {
        if (fallback < 0 || fallback > k.max || next == null || next.size() > MAX) {
            throw new IllegalArgumentException("Invalid outcomes");
        }
        for (Integer v : next) {
            if (v == null || v < 0 || v > k.max) throw new IllegalArgumentException("Invalid outcome");
        }
        Slot s = (talker == null || talker.trim().isEmpty()) ? slot(k) : ensureChatRule(talker).slot(k);
        s.fallback = fallback;
        s.next.clear();
        s.next.addAll(next);
        s.clearClaim();
    }

    static String label(Kind k, int v) {
        if (v == 0) return "随机";
        if (v < 1 || v > k.max) throw new IllegalArgumentException("Invalid outcome");
        return k == Kind.DICE ? Integer.toString(v) : v == 1 ? "石头" : v == 2 ? "剪刀" : "布";
    }

    static List<Integer> parse(Kind k, String text) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("最多设置 256 次结果");
        List<Integer> values = new ArrayList<>();
        for (String word : text.trim().split("[，,、;；\\s]+")) {
            if (word.isEmpty()) continue;
            int v;
            if (word.equals("随机")) v = 0;
            else if (k == Kind.RPS && word.equals("石头")) v = 1;
            else if (k == Kind.RPS && word.equals("剪刀")) v = 2;
            else if (k == Kind.RPS && word.equals("布")) v = 3;
            else {
                try { v = Integer.parseInt(word); }
                catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException(k == Kind.DICE ? "请填写 1～6，或随机" : "请填写石头、剪刀、布，或随机");
                }
            }
            if (v < 0 || v > k.max) throw new IllegalArgumentException("结果超出范围");
            values.add(v);
            if (values.size() > MAX) throw new IllegalArgumentException("最多设置 256 次结果");
        }
        return values;
    }

    static String display(Kind k, List<Integer> values) {
        StringBuilder s = new StringBuilder();
        for (int v : values) {
            if (s.length() > 0) s.append(", ");
            s.append(label(k, v));
        }
        return s.toString();
    }

    private static void encodeSlot(StringBuilder s, String prefix, Slot v) {
        s.append(prefix).append(".default=").append(v.fallback).append('\n')
                .append(prefix).append(".queue=");
        for (int i = 0; i < v.next.size(); i++) {
            if (i > 0) s.append(',');
            s.append(v.next.get(i));
        }
        s.append('\n')
                .append(prefix).append(".ticket=").append(v.ticket).append('\n')
                .append(prefix).append(".chosen=").append(v.chosen).append('\n')
                .append(prefix).append(".queued=").append(v.queued ? 1 : 0).append('\n');
    }

    String encode() {
        StringBuilder s = new StringBuilder("format=2\nowner=" + owner + "\nenabled=" + (enabled ? 1 : 0)
                + "\naskBeforeSend=" + (askBeforeSend ? 1 : 0) + "\n");
        for (Kind k : Kind.values()) {
            encodeSlot(s, k.key, slot(k));
        }
        int count = 0;
        for (Map.Entry<String, ChatRule> e : chatRules.entrySet()) {
            ChatRule r = e.getValue();
            if (r == null || r.mode == ChatMode.INHERIT) continue;
            if (count >= 64) break;
            String pfx = "chat." + count;
            s.append(pfx).append(".id=").append(e.getKey()).append('\n');
            s.append(pfx).append(".mode=").append(r.mode.name()).append('\n');
            for (Kind k : Kind.values()) {
                encodeSlot(s, pfx + "." + k.key, r.slot(k));
            }
            count++;
        }
        s.append("chat.count=").append(count).append('\n');
        return s.toString();
    }

    private static void decodeSlot(Map<String, String> fields, String prefix, Kind k, Slot s) {
        String def = fields.get(prefix + ".default");
        s.fallback = def != null ? Integer.parseInt(def) : 0;
        String q = fields.get(prefix + ".queue");
        if (q != null && !q.isEmpty()) s.next.addAll(parse(k, q));
        String t = fields.get(prefix + ".ticket");
        s.ticket = t == null ? "" : t;
        String ch = fields.get(prefix + ".chosen");
        s.chosen = ch != null ? Integer.parseInt(ch) : 0;
        String qu = fields.get(prefix + ".queued");
        s.queued = qu != null && bit(qu);
        if (s.fallback < 0 || s.fallback > k.max || s.chosen < 0 || s.chosen > k.max) {
            throw new IllegalArgumentException("Invalid slot range");
        }
    }

    static GameEmojiData decode(String raw, String owner) {
        GameEmojiData d = new GameEmojiData(owner);
        if (raw == null) return d;
        if (raw.length() > 65536) throw new IllegalArgumentException("Oversize outcomes");
        Map<String, String> fields = new HashMap<>();
        for (String line : raw.split("\n")) {
            int p = line.indexOf('=');
            if (p < 1 || fields.put(line.substring(0, p), line.substring(p + 1)) != null) {
                throw new IllegalArgumentException("Malformed outcomes");
            }
        }
        String fmt = fields.get("format");
        if ((!"1".equals(fmt) && !"2".equals(fmt)) || !owner.equals(fields.get("owner"))) {
            throw new IllegalArgumentException("Wrong owner or version");
        }
        d.enabled = bit(fields.get("enabled"));
        String ask = fields.get("askBeforeSend");
        d.askBeforeSend = "1".equals(ask);
        for (Kind k : Kind.values()) {
            decodeSlot(fields, k.key, k, d.slot(k));
        }
        if ("2".equals(fmt)) {
            int count = 0;
            try { count = Integer.parseInt(fields.getOrDefault("chat.count", "0")); } catch (Throwable ignored) {}
            for (int i = 0; i < count && i < 64; i++) {
                String pfx = "chat." + i;
                String id = fields.get(pfx + ".id");
                String modeStr = fields.get(pfx + ".mode");
                if (id == null || id.isEmpty() || modeStr == null) continue;
                try {
                    ChatRule cr = new ChatRule();
                    cr.mode = ChatMode.valueOf(modeStr);
                    for (Kind k : Kind.values()) {
                        decodeSlot(fields, pfx + "." + k.key, k, cr.slot(k));
                    }
                    d.chatRules.put(id, cr);
                } catch (Throwable ignored) {}
            }
        }
        return d;
    }

    static boolean bit(String v) {
        if (!"0".equals(v) && !"1".equals(v)) throw new IllegalArgumentException("Invalid switch");
        return "1".equals(v);
    }
}
