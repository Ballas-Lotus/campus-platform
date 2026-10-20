package com.campus.framework.lock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式锁观测指标（阶段 18 前端「🔒 并发控制」看板的锁部分）。
 *
 * <p>记录：获取成功 / 一次即得 / 自旋后得 / 等待超时 / 释放 / 当前持有者。
 * "一次即得 vs 自旋后得"是判断锁竞争烈度的关键口径——前者说明无争抢，
 * 后者说明有并发在同一锁上排队。</p>
 */
public class LockMetrics {

    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong acquiredFirstTry = new AtomicLong();
    private final AtomicLong acquiredAfterSpin = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    /** Redis 异常时的降级放行次数（宁可多做一次，不可让业务停摆） */
    private final AtomicLong degraded = new AtomicLong();
    private final ConcurrentHashMap<String, Holder> holders = new ConcurrentHashMap<>();

    /** 锁持有者快照（key → 谁、何时、持多久） */
    public record Holder(String key, String token, String thread, long acquiredAt, long ttlMs) {
    }

    public void onAcquired(String key, String token, long ttlMs, boolean firstTry) {
        acquired.incrementAndGet();
        if (firstTry) {
            acquiredFirstTry.incrementAndGet();
        } else {
            acquiredAfterSpin.incrementAndGet();
        }
        holders.put(key, new Holder(key, token, Thread.currentThread().getName(),
                System.currentTimeMillis(), ttlMs));
    }

    public void onTimeout(String key) {
        timeouts.incrementAndGet();
    }

    public void onDegraded(String key) {
        degraded.incrementAndGet();
    }

    public void onReleased(String key) {
        released.incrementAndGet();
        holders.remove(key);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acquired", acquired.get());
        m.put("acquiredFirstTry", acquiredFirstTry.get());
        m.put("acquiredAfterSpin", acquiredAfterSpin.get());
        m.put("timeouts", timeouts.get());
        m.put("released", released.get());
        m.put("degraded", degraded.get());
        m.put("holding", holders.size());
        List<Map<String, Object>> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Holder h : holders.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", h.key());
            row.put("thread", h.thread());
            row.put("heldMs", now - h.acquiredAt());
            row.put("ttlMs", h.ttlMs());
            list.add(row);
        }
        m.put("holders", list);
        return m;
    }
}
