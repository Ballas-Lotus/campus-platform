package com.campus.framework.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 布隆过滤器（阶段 16）——Redis 位图实现，防缓存穿透的第一道闸。
 *
 * <p>为什么需要它：查一个<b>不存在</b>的资源 id（刷接口/爬虫/脏 ID）永远不会命中缓存，
 * 每次都穿透到数据库。空值回填（{@link RedisCampusCache}）挡的是"查过之后"的重复穿透，
 * 布隆过滤器挡的是"从一开始就不该查"的请求——两者互补。</p>
 *
 * <p>实现要点（经典布隆过滤器，无第三方依赖）：</p>
 * <ul>
 *   <li>位数组长度 {@code m = -n·ln p / (ln 2)²}，哈希个数 {@code k = (m/n)·ln 2}
 *       —— n 为预估元素数，p 为期望误判率；</li>
 *   <li>哈希函数用<b>双哈希法</b>模拟 k 个：{@code offset_i = (h1 + i·h2) mod m}，
 *       h1/h2 取自 MD5（前 8 字节 / 后 8 字节）；</li>
 *   <li>位操作走 Lua：一次网络往返完成 k 次 {@code SETBIT}/{@code GETBIT}，天然原子；</li>
 *   <li><b>只可能误判"存在"，绝不会误判"不存在"</b>——所以判断"不在集合中"可以直接拒绝，
 *       判断"在集合中"仍需回源确认（本项目的做法：布隆说没有 → 直接 404；说有可能 → 正常走缓存/DB）。</li>
 * </ul>
 *
 * <p>局限（诚实边界）：位图与元数据都在 Redis 里，Redis 清空即失效——由
 * {@link #reset} + 启动预热重建（见 campus-service 的 CacheWarmup），不追求持久化。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampusBloomFilter {

    private final StringRedisTemplate redis;

    private static final String META_PREFIX = "campus:bloom:meta:";
    private static final String BITS_PREFIX = "campus:bloom:bits:";
    /** 元数据 TTL（与资源目录同生命周期；重建由预热任务负责） */
    private static final Duration META_TTL = Duration.ofHours(12);
    /** 批量写入的分块大小（每块 BATCH×k 个位偏移打包进一次 Lua） */
    private static final int BATCH = 500;

    private static final AtomicLong rejected = new AtomicLong();   // 累计拦下的"确定不存在"查询

    /** 位操作 Lua：批量置位（原子、单次往返） */
    private static final DefaultRedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            for i = 1, #ARGV do
              redis.call('SETBIT', key, ARGV[i], 1)
            end
            return #ARGV
            """, Long.class);

    /** 位操作 Lua：全部位为 1 才认为"可能存在"（返回 1），任一位为 0 → 确定不存在（返回 0） */
    private static final DefaultRedisScript<Long> CONTAINS_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            for i = 1, #ARGV do
              if redis.call('GETBIT', key, ARGV[i]) == 0 then
                return 0
              end
            end
            return 1
            """, Long.class);

    /**
     * 初始化（幂等）：已存在则不动。注意——<b>改预期容量必须先 {@link #reset}</b>，
     * 否则 m/k 与位图内容不匹配，误判率失控。
     */
    public void init(String name, long expectedInsertions, double fpp) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(metaKey(name)))) {
                return;
            }
            long m = optimalM(expectedInsertions, fpp);
            int k = optimalK(expectedInsertions, m);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("m", String.valueOf(m));
            meta.put("k", String.valueOf(k));
            meta.put("expected", String.valueOf(expectedInsertions));
            meta.put("fpp", String.valueOf(fpp));
            meta.put("inserted", "0");
            meta.put("createdAt", String.valueOf(System.currentTimeMillis()));
            redis.opsForHash().putAll(metaKey(name), meta);
            redis.expire(metaKey(name), META_TTL);
            redis.expire(bitsKey(name), META_TTL);
            log.info("[布隆] 初始化: name={} 预计元素={} 位数组 m={} 哈希个数 k={} 期望误判率={}",
                    name, expectedInsertions, m, k, fpp);
        } catch (Exception e) {
            log.warn("[布隆] 初始化失败（降级：不启用布隆拦截）: name={} {}", name, e.getMessage());
        }
    }

    /** 加入元素 */
    public void add(String name, String value) {
        try {
            long m = metaLong(name, "m");
            int k = (int) metaLong(name, "k");
            if (m <= 0 || k <= 0) {
                return;
            }
            long[] offsets = offsets(value, m, k);
            String[] args = new String[offsets.length];
            for (int i = 0; i < offsets.length; i++) {
                args[i] = String.valueOf(offsets[i]);
            }
            redis.execute(ADD_SCRIPT, List.of(bitsKey(name)), (Object[]) args);
            redis.opsForHash().increment(metaKey(name), "inserted", 1);
        } catch (Exception e) {
            log.warn("[布隆] 写入失败（忽略）: name={} value={} {}", name, value, e.getMessage());
        }
    }

    /**
     * 批量加入（预热用）。
     *
     * <p>逐条 {@link #add} 是"每元素两次网络往返"（Lua + HINCRBY），3000+ 元素会拖慢启动十余秒；
     * 批量版把 k×{@value #BATCH} 个位偏移打包进一次 Lua，并只在收尾累加一次 inserted 计数。</p>
     */
    public void addAll(String name, java.util.Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        try {
            long m = metaLong(name, "m");
            int k = (int) metaLong(name, "k");
            if (m <= 0 || k <= 0) {
                return;
            }
            java.util.List<String> args = new java.util.ArrayList<>(BATCH * k);
            int count = 0;
            for (String v : values) {
                for (long offset : offsets(v, m, k)) {
                    args.add(String.valueOf(offset));
                }
                if (++count % BATCH == 0) {
                    flushBits(name, args);
                }
            }
            flushBits(name, args);
            redis.opsForHash().increment(metaKey(name), "inserted", values.size());
            redis.expire(metaKey(name), META_TTL);
            redis.expire(bitsKey(name), META_TTL);
        } catch (Exception e) {
            log.warn("[布隆] 批量写入失败: name={} {}", name, e.getMessage());
        }
    }

    private void flushBits(String name, java.util.List<String> args) {
        if (args.isEmpty()) {
            return;
        }
        redis.execute(ADD_SCRIPT, List.of(bitsKey(name)), args.toArray());
        args.clear();
    }

    /**
     * 判断"可能存在"。
     *
     * @return true = 可能存在（需回源确认）；false = <b>一定不存在</b>（可直接拒绝，不查库）
     */
    public boolean mightContain(String name, String value) {
        try {
            long m = metaLong(name, "m");
            int k = (int) metaLong(name, "k");
            if (m <= 0 || k <= 0) {
                return true;   // 未初始化：不做拦截（安全侧默认放行）
            }
            long[] offsets = offsets(value, m, k);
            String[] args = new String[offsets.length];
            for (int i = 0; i < offsets.length; i++) {
                args[i] = String.valueOf(offsets[i]);
            }
            Long r = redis.execute(CONTAINS_SCRIPT, List.of(bitsKey(name)), (Object[]) args);
            boolean maybe = r != null && r == 1L;
            if (!maybe) {
                rejected.incrementAndGet();
            }
            return maybe;
        } catch (Exception e) {
            log.warn("[布隆] 查询失败（降级放行）: name={} {}", name, e.getMessage());
            return true;
        }
    }

    /** 重置（清位图 + 元数据），用于重算容量或重新预热 */
    public void reset(String name) {
        redis.delete(List.of(metaKey(name), bitsKey(name)));
        log.info("[布隆] 已重置: {}", name);
    }

    /** 观测：参数 + 位图填充率 + 按填充率反推的当前误判率估计 */
    public Map<String, Object> stats(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String metaKey = metaKey(name);
            if (!Boolean.TRUE.equals(redis.hasKey(metaKey))) {
                m.put("initialized", false);
                return m;
            }
            Map<Object, Object> meta = redis.opsForHash().entries(metaKey);
            long bits = Long.parseLong(String.valueOf(meta.get("m")));
            int k = Integer.parseInt(String.valueOf(meta.get("k")));
            Long setBits = redis.execute(new DefaultRedisScript<>("return redis.call('BITCOUNT', KEYS[1])", Long.class),
                    List.of(bitsKey(name)));
            double fill = bits == 0 ? 0 : (double) (setBits == null ? 0 : setBits) / bits;
            m.put("initialized", true);
            m.put("expected", Long.parseLong(String.valueOf(meta.get("expected"))));
            m.put("fpp", Double.parseDouble(String.valueOf(meta.get("fpp"))));
            m.put("m", bits);
            m.put("k", k);
            m.put("inserted", Long.parseLong(String.valueOf(meta.get("inserted"))));
            m.put("setBits", setBits);
            m.put("fillRatio", Math.round(fill * 10000) / 100.0);
            m.put("currentFpp", Math.round(Math.pow(fill, k) * 1000000) / 10000.0);   // 百分比展示
            m.put("rejected", rejected.get());
            m.put("bitsKey", bitsKey(name));
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 参数计算（教科书公式，面试可直接推导）
    // ------------------------------------------------------------------

    /** m = -n·ln(p) / (ln2)² */
    static long optimalM(long n, double p) {
        return Math.max(64, (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2))));
    }

    /** k = (m/n)·ln2 */
    static int optimalK(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    private long[] offsets(String value, long m, int k) {
        byte[] digest = md5(value.getBytes(StandardCharsets.UTF_8));
        long h1 = toLong(digest, 0);
        long h2 = toLong(digest, 8);
        long[] out = new long[k];
        for (int i = 0; i < k; i++) {
            out[i] = Math.floorMod(h1 + (long) i * h2, m);
        }
        return out;
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long toLong(byte[] bytes, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (bytes[offset + i] & 0xFF);
        }
        return v;
    }

    private long metaLong(String name, String field) {
        Object v = redis.opsForHash().get(metaKey(name), field);
        return v == null ? -1 : Long.parseLong(String.valueOf(v));
    }

    private String metaKey(String name) {
        return META_PREFIX + name;
    }

    private String bitsKey(String name) {
        return BITS_PREFIX + name;
    }
}
