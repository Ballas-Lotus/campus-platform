package com.campus.framework.cache;

import com.campus.framework.lock.CampusLock;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Redis 缓存实现（阶段 16）。
 *
 * <p>模式 = <b>Cache-Aside（写后失效）</b>：读走缓存、写清缓存，不做写穿透（写更新缓存）。
 * 理由：写路径是"先到先得"的争抢热点，让写去维护缓存只会把 DB 行锁的串行等待搬到 Redis 上，
 * 收益为零、复杂度却上升；而"写后失效 + 短 TTL"最坏只多一次回源。</p>
 *
 * <p>三个关键防线：</p>
 * <ul>
 *   <li><b>防击穿</b>（{@link #safeGet}）：热点 key 过期瞬间，抢到锁的线程单线程回源，
 *       其余线程短暂等待后重读缓存；<b>双检</b>（拿到锁后再读一次）保证不重复回源。</li>
 *   <li><b>防穿透</b>（空值回填）：loader 返回 null 时写 {@code __NULL__} 标记（短 TTL），
 *       挡住"反复查不存在的 id"；配合 {@link CampusBloomFilter} 在入口更早拦截。</li>
 *   <li><b>降级</b>：任何 Redis/序列化异常都不得让业务失败——记录指标后直接回源（Redis 是加速器，不是真相源）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCampusCache implements CampusCache {

    private final StringRedisTemplate redis;
    private final CampusLock lock;
    private final CacheMetrics metrics;

    /** 空值回填 TTL（防穿透；短 TTL 让它随数据补齐自动失效） */
    private static final long NULL_TTL_SECONDS = 30;
    /** 防击穿锁：等待上限 / 持有上限（回源是毫秒级 DB 查询，3 秒足够宽裕） */
    private static final long LOCK_WAIT_MS = 200;
    private static final long LOCK_TTL_MS = 3_000;
    /** 未抢到锁的线程的等待窗口（随后重读缓存，仍无则回源兜底） */
    private static final long LOCK_LOSER_SLEEP_MS = 40;

    private static final String LOCK_PREFIX = "campus:cache:lock:";

    @Override
    public <T> T get(String key, TypeReference<T> type, long ttlSeconds, Supplier<T> loader) {
        long t0 = System.nanoTime();
        String json = safeRead(key, type, t0);
        if (json != null) {
            return decode(json, type, key, t0);
        }
        metrics.record(key, "MISS", elapsedMs(t0));
        return load(key, ttlSeconds, loader);
    }

    @Override
    public <T> T safeGet(String key, TypeReference<T> type, long ttlSeconds, Supplier<T> loader) {
        long t0 = System.nanoTime();
        String json = safeRead(key, type, t0);
        if (json != null) {
            return decode(json, type, key, t0);
        }
        metrics.record(key, "MISS", elapsedMs(t0));

        String token = lock.tryAcquire(LOCK_PREFIX + key, LOCK_WAIT_MS, LOCK_TTL_MS);
        if (token != null) {
            metrics.record(key, "LOCK_ACQUIRED", elapsedMs(t0));
            try {
                String again = safeRead(key, type, t0);        // 双检：等锁期间可能已被其他线程填好
                if (again != null) {
                    return decode(again, type, key, t0);
                }
                return load(key, ttlSeconds, loader);
            } finally {
                lock.release(LOCK_PREFIX + key, token);
            }
        }

        // 未抢到锁：本线程不再回源（把击穿流量收敛到 1），短暂等待后重读；仍无则兜底回源
        metrics.record(key, "LOCK_MISSED", elapsedMs(t0));
        sleep(LOCK_LOSER_SLEEP_MS);
        String retry = safeRead(key, type, t0);
        if (retry != null) {
            return decode(retry, type, key, t0);
        }
        return load(key, ttlSeconds, loader);
    }

    @Override
    public <T> T getDirect(String key, TypeReference<T> type, long ttlSeconds, Supplier<T> loader) {
        long t0 = System.nanoTime();
        T value = loader.get();
        metrics.record(key, "DB", elapsedMs(t0));
        put(key, value, ttlSeconds);
        return value;
    }

    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
            metrics.record(key, "EVICT", 0);
        } catch (Exception e) {
            log.warn("[缓存] 失效失败（忽略，等 TTL 自然过期）: key={} {}", key, e.getMessage());
        }
    }

    @Override
    public long evictByPrefix(String prefix) {
        long removed = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(200).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
                removed++;
            }
        } catch (Exception e) {
            log.warn("[缓存] 前缀失效失败: prefix={} {}", prefix, e.getMessage());
        }
        if (removed > 0) {
            metrics.record(prefix + "*", "EVICT", 0);
            log.info("[缓存] 前缀失效 {} 个 key: {}", removed, prefix);
        }
        return removed;
    }

    @Override
    public boolean hasKey(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 读缓存：返回 null 表示未命中；空值标记返回 null（但算一次"空值命中"）。 */
    private <T> String safeRead(String key, TypeReference<T> type, long t0) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            if (CampusCacheKeys.NULL_MARKER.equals(json)) {
                metrics.record(key, "HIT", elapsedMs(t0));   // 空值命中：命中统计（挡住了一次穿透）
                return json;
            }
            return json;
        } catch (Exception e) {
            metrics.record(key, "ERROR", elapsedMs(t0));
            log.warn("[缓存] 读取异常（降级为回源）: key={} {}", key, e.getMessage());
            return null;
        }
    }

    private <T> T decode(String json, TypeReference<T> type, String key, long t0) {
        if (CampusCacheKeys.NULL_MARKER.equals(json)) {
            metrics.record(key, "HIT", elapsedMs(t0));       // 空值命中（防穿透生效）
            return null;
        }
        metrics.record(key, "HIT", elapsedMs(t0));
        try {
            return CacheJson.read(json, type);
        } catch (Exception e) {
            metrics.record(key, "ERROR", elapsedMs(t0));
            log.warn("[缓存] 反序列化失败（当未命中处理）: key={} {}", key, e.getMessage());
            return null;
        }
    }

    private <T> T load(String key, long ttlSeconds, Supplier<T> loader) {
        long t1 = System.nanoTime();
        T value = loader.get();
        metrics.record(key, "DB", elapsedMs(t1));
        put(key, value, ttlSeconds);
        return value;
    }

    private void put(String key, Object value, long ttlSeconds) {
        try {
            if (value == null) {
                redis.opsForValue().set(key, CampusCacheKeys.NULL_MARKER, Duration.ofSeconds(NULL_TTL_SECONDS));
                metrics.record(key, "NULL_PUT", 0);
                return;
            }
            redis.opsForValue().set(key, CacheJson.write(value), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[缓存] 写入失败（不影响本次查询）: key={} {}", key, e.getMessage());
        }
    }

    private long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 现存缓存 key（观测接口用，SCAN 采样） */
    public Set<String> sampleKeys(int limit) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(CampusCacheKeys.PREFIX + "*").count(limit).build())) {
            while (cursor.hasNext() && keys.size() < limit) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("[缓存] 采样 key 失败: {}", e.getMessage());
        }
        return keys;
    }
}
