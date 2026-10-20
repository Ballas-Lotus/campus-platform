package com.campus.framework.cache;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存运行指标（阶段 16）：命中率 / 回源次数 / 击穿加锁 / 空值回填（防穿透）/ 失效次数 / 最近访问轨迹。
 *
 * <p>指标只增不减（进程内累计），供前端「🚀 缓存」看板与压测断言使用；
 * 最近访问轨迹保留最后 {@value #RECENT_LIMIT} 条，用于"现场看到 key 从哪来"。</p>
 *
 * <p><b>阶段 19</b> 增补"教学班读模型"指标：pipeline 合并读（PIPE）、写后增量更新（STOCK_INCR）、
 * 增量跳过（STOCK_SKIP，key 已过期则下次读按真值重建）、读模型重建（STOCK_REBUILD）、
 * 对账检查与纠偏（STOCK_CHECK / STOCK_FIX）。</p>
 */
@Component
public class CacheMetrics {

    private static final int RECENT_LIMIT = 24;

    private final AtomicLong hits = new AtomicLong();          // 命中缓存
    private final AtomicLong misses = new AtomicLong();        // 未命中
    private final AtomicLong dbLoads = new AtomicLong();       // 回源（真正查库）
    private final AtomicLong nullPuts = new AtomicLong();      // 空值回填（防穿透写入）
    private final AtomicLong lockAcquires = new AtomicLong();  // 防击穿：抢到锁并回源
    private final AtomicLong lockMisses = new AtomicLong();    // 防击穿：未抢到锁（并发回源被抑制）
    private final AtomicLong evictions = new AtomicLong();     // 主动失效（写后失效）
    private final AtomicLong errors = new AtomicLong();        // 反序列化/Redis 异常（降级直查）
    private final AtomicLong pipelineReads = new AtomicLong(); // 阶段 19：pipeline 合并读（info + stock 一次往返）
    private final AtomicLong stockIncrs = new AtomicLong();    // 阶段 19：写后增量更新（HINCRBY if-exists）
    private final AtomicLong stockSkips = new AtomicLong();    // 阶段 19：增量跳过（key 不存在 → 下次读重建）
    private final AtomicLong stockRebuilds = new AtomicLong(); // 阶段 19：余量 Hash 按 DB 真值重建
    private final AtomicLong stockChecks = new AtomicLong();   // 阶段 19：对账检查的 Hash 数（无轨迹，避免刷屏）
    private final AtomicLong stockFixes = new AtomicLong();    // 阶段 19：对账纠偏的 Hash 数
    private final ConcurrentLinkedDeque<Access> recent = new ConcurrentLinkedDeque<>();

    /** 一次缓存访问轨迹：key / 结果（HIT、MISS、NULL_HIT、DB、LOCKED、EVICT）/ 耗时 */
    public record Access(long at, String key, String result, long ms) {
    }

    public void record(String key, String result, long ms) {
        switch (result) {
            case "HIT" -> hits.incrementAndGet();
            case "MISS" -> misses.incrementAndGet();
            case "DB" -> dbLoads.incrementAndGet();
            case "NULL_PUT" -> nullPuts.incrementAndGet();
            case "LOCK_ACQUIRED" -> lockAcquires.incrementAndGet();
            case "LOCK_MISSED" -> lockMisses.incrementAndGet();
            case "EVICT" -> evictions.incrementAndGet();
            case "ERROR" -> errors.incrementAndGet();
            case "PIPE" -> pipelineReads.incrementAndGet();
            case "STOCK_INCR" -> stockIncrs.incrementAndGet();
            case "STOCK_SKIP" -> stockSkips.incrementAndGet();
            case "STOCK_REBUILD" -> stockRebuilds.incrementAndGet();
            case "STOCK_FIX" -> stockFixes.incrementAndGet();
            default -> { /* 其它结果只进轨迹 */ }
        }
        recent.addFirst(new Access(System.currentTimeMillis(), key, result, ms));
        while (recent.size() > RECENT_LIMIT) {
            recent.pollLast();
        }
    }

    /** 对账检查量计数（不进访问轨迹——每 30 秒一轮会刷屏；纠偏才进轨迹） */
    public void countStockChecked(int n) {
        stockChecks.addAndGet(n);
    }

    /** "回源"总次数 = 普通回源 + 读模型重建（对照实验的口径：读列表打了几次数据库） */
    public long reloadCount() {
        return dbLoads.get() + stockRebuilds.get();
    }

    /** 命中率（含空值命中；无访问时为 0） */
    public double hitRate() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return total == 0 ? 0d : Math.round(h * 10000.0 / total) / 100.0;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hits", hits.get());
        m.put("misses", misses.get());
        m.put("hitRate", hitRate());
        m.put("dbLoads", dbLoads.get());
        m.put("nullPuts", nullPuts.get());
        m.put("lockAcquires", lockAcquires.get());
        m.put("lockMisses", lockMisses.get());
        m.put("evictions", evictions.get());
        m.put("errors", errors.get());
        m.put("pipelineReads", pipelineReads.get());
        m.put("stockIncrs", stockIncrs.get());
        m.put("stockSkips", stockSkips.get());
        m.put("stockRebuilds", stockRebuilds.get());
        m.put("stockChecks", stockChecks.get());
        m.put("stockFixes", stockFixes.get());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Access a : recent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", a.key());
            row.put("result", a.result());
            row.put("ms", a.ms());
            list.add(row);
        }
        m.put("recent", list);
        return m;
    }
}
