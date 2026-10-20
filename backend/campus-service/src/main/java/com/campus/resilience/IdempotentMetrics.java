package com.campus.resilience;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接口幂等指标（阶段 18）：按业务前缀统计"放行 / 拦截重复"，供前端「🔒 并发控制」看板展示。
 */
@Component
public class IdempotentMetrics {

    /** 通过（首次请求） */
    private final AtomicLong passed = new AtomicLong();
    /** 拦截（窗口内重复请求） */
    private final AtomicLong blocked = new AtomicLong();
    /** 业务失败后释放标记（放行重试） */
    private final AtomicLong releasedForRetry = new AtomicLong();
    /** 降级放行（Redis 异常） */
    private final AtomicLong degraded = new AtomicLong();

    private final Map<String, AtomicLong> blockedByPrefix = new ConcurrentHashMap<>();

    public void onPassed() {
        passed.incrementAndGet();
    }

    public void onBlocked(String prefix) {
        blocked.incrementAndGet();
        blockedByPrefix.computeIfAbsent(prefix, k -> new AtomicLong()).incrementAndGet();
    }

    public void onReleasedForRetry() {
        releasedForRetry.incrementAndGet();
    }

    public void onDegraded() {
        degraded.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passed", passed.get());
        m.put("blocked", blocked.get());
        m.put("releasedForRetry", releasedForRetry.get());
        m.put("degraded", degraded.get());
        long total = passed.get() + blocked.get();
        m.put("blockRate", total == 0 ? 0d : Math.round(blocked.get() * 10000.0 / total) / 100.0);
        Map<String, Long> byPrefix = new LinkedHashMap<>();
        blockedByPrefix.forEach((k, v) -> byPrefix.put(k, v.get()));
        m.put("blockedByPrefix", byPrefix);
        return m;
    }
}
