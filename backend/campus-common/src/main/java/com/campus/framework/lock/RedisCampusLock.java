package com.campus.framework.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 基于 Redis 的分布式锁（{@code SET NX PX} + Lua 释放），见 {@link CampusLock} 的选型说明。
 *
 * <p>自旋等待采用<b>退避 + 抖动</b>（10~30ms 随机），避免大量等待者同频重试造成惊群。</p>
 *
 * <p><b>Redis 故障时的降级</b>：加锁期间 Redis 不可用 → 返回 {@link #DEGRADED_PREFIX} 前缀的
 * 降级凭证（视为已持有，临界区照常执行；释放是空操作）。理由：本项目的锁保护的都是
 * "重复执行无害"的动作（回源重建、定时扫描），<b>宁可多做一次，不可让业务停摆</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCampusLock implements CampusLock {

    private final StringRedisTemplate redis;
    private final LockMetrics metrics = new LockMetrics();

    /** 降级凭证前缀：Redis 异常时返回，release 对其为空操作 */
    static final String DEGRADED_PREFIX = "DEGRADED:";

    /** 释放 Lua：值为自己才删（防误删他人锁），一步原子 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final long MIN_SLEEP_MS = 10;
    private static final long MAX_SLEEP_MS = 30;

    @Override
    public String tryAcquire(String key, long waitMs, long ttlMs) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(ttlMs));
                if (Boolean.TRUE.equals(ok)) {
                    metrics.onAcquired(key, token, ttlMs, attempt == 1);
                    return token;
                }
            } catch (Exception e) {
                log.error("[分布式锁] Redis 异常，降级放行（临界区照常执行）: key={} {}", key, e.getMessage());
                metrics.onDegraded(key);
                return DEGRADED_PREFIX + token;
            }
            if (System.currentTimeMillis() >= deadline) {
                metrics.onTimeout(key);
                return null;
            }
            sleep(ThreadLocalRandom.current().nextLong(MIN_SLEEP_MS, MAX_SLEEP_MS + 1));
        }
    }

    @Override
    public boolean release(String key, String token) {
        if (token == null) {
            return false;
        }
        if (token.startsWith(DEGRADED_PREFIX)) {
            metrics.onReleased(key);
            return true;   // 降级凭证：无实际锁可释放
        }
        try {
            Long r = redis.execute(RELEASE_SCRIPT, List.of(key), token);
            metrics.onReleased(key);
            return r != null && r > 0;
        } catch (Exception e) {
            log.warn("[分布式锁] 释放异常（等 TTL 自动过期）: key={} {}", key, e.getMessage());
            metrics.onReleased(key);
            return false;
        }
    }

    @Override
    public <T> T executeWithLock(String key, long waitMs, long ttlMs, Supplier<T> action) {
        String token = tryAcquire(key, waitMs, ttlMs);
        if (token == null) {
            return null;
        }
        try {
            return action.get();
        } finally {
            release(key, token);
        }
    }

    @Override
    public Map<String, Object> stats() {
        return metrics.snapshot();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
