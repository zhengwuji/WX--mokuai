package io.github.shadowbaby.wechatxposed.hook.support.diag;

import io.github.shadowbaby.wechatxposed.hook.loader.util.ModuleLogger;
import io.github.shadowbaby.wechatxposed.shared.hook.HookCallback;
import io.github.shadowbaby.wechatxposed.shared.hook.HookParam;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook 命中率统计。
 *
 * 目的：功能层有 500+ 个 Hook 点，但装到某个微信版本后实际命中多少无从得知。
 * 之前只能靠日志里「功能加载成功」这类汇总行判断，具体哪个 Hook 死了看不见。
 *
 * 用法：在 {@code WxComponentHooks} 等安装 Hook 的入口外面包一层
 * {@link #wrap(String, HookCallback)}，标记该 Hook 点已安装；
 * 命中时由回调自身调用 {@link #hit(String)} 计数。
 *
 * 输出：{@link #report()} 返回「已装/已命中/未命中」三类计数与明细，
 * 由 {@code MainActivity} 调起或写进微信设置页。
 *
 * 统计本身不做任何拦截，不影响功能行为。
 */
public final class HookHitStats {

    /** 已安装的 Hook 点（标记名 -> 安装时间戳） */
    private static final Map<String, Long> INSTALLED = new ConcurrentHashMap<>();

    /** 已命中的 Hook 点（标记名 -> 命中次数） */
    private static final Map<String, AtomicLong> HITS = new ConcurrentHashMap<>();

    /** 安装失败的 Hook 点（标记名 -> 失败原因） */
    private static final Map<String, String> FAILED = new ConcurrentHashMap<>();

    private static final Set<String> LOGGED_MISS = ConcurrentHashMap.newKeySet();

    private HookHitStats() {
    }

    /** 标记一个 Hook 点已安装。 */
    public static void installed(String tag) {
        INSTALLED.put(tag, System.currentTimeMillis());
    }

    /** 标记一个 Hook 点安装失败。 */
    public static void failed(String tag, Throwable error) {
        FAILED.put(tag, String.valueOf(error));
    }

    /** 记录一次命中。 */
    public static void hit(String tag) {
        HITS.computeIfAbsent(tag, key -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 包装一个 HookCallback：安装时登记，命中时自动计数。
     * 回调本身的行为完全透传，不做任何修改。
     */
    public static HookCallback wrap(String tag, HookCallback delegate) {
        installed(tag);
        return new CountingCallback(tag, delegate);
    }

    /** 汇总：已装数量、已命中数量、未命中数量。 */
    public static String summary() {
        int total = INSTALLED.size();
        int hit = 0;
        for (String tag : INSTALLED.keySet()) {
            if (HITS.containsKey(tag)) {
                hit++;
            }
        }
        return "hook stats: installed " + total
                + ", hit " + hit
                + ", miss " + (total - hit)
                + ", failed " + FAILED.size();
    }

    /** 明细：逐条列出未命中的 Hook 点，用于定位死 Hook。 */
    public static String detail() {
        StringBuilder builder = new StringBuilder(summary());
        builder.append('\n');
        for (Map.Entry<String, Long> entry : INSTALLED.entrySet()) {
            String tag = entry.getKey();
            AtomicLong counter = HITS.get(tag);
            if (counter == null) {
                builder.append("[MISS] ").append(tag).append('\n');
            } else {
                builder.append("[HIT  ").append(counter.get()).append("] ")
                        .append(tag).append('\n');
            }
        }
        for (Map.Entry<String, String> entry : FAILED.entrySet()) {
            builder.append("[FAIL] ").append(entry.getKey())
                    .append(" : ").append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    /** 打一条汇总日志，供启动流程末尾调用。 */
    public static void logSummary() {
        ModuleLogger.log(summary());
        for (Map.Entry<String, Long> entry : INSTALLED.entrySet()) {
            if (!HITS.containsKey(entry.getKey()) && LOGGED_MISS.add(entry.getKey())) {
                ModuleLogger.debug("hook miss: " + entry.getKey());
            }
        }
    }

    private static final class CountingCallback implements HookCallback {

        private final String tag;

        private final HookCallback delegate;

        private CountingCallback(String tag, HookCallback delegate) {
            this.tag = tag;
            this.delegate = delegate;
        }

        @Override
        public void before(HookParam param) throws Throwable {
            hit(tag);
            if (delegate != null) {
                delegate.before(param);
            }
        }

        @Override
        public void after(HookParam param) throws Throwable {
            if (delegate != null) {
                delegate.after(param);
            }
        }
    }
}
