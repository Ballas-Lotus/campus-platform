package com.campus.resilience;

import com.campus.prededuct.PreDeductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「合并 Lua」对照实验（阶段 19）：同一计数器上，旧路径 vs 快路径各跑 N 轮。
 *
 * <pre>
 *  旧路径（阶段 18）：ensureInitialized(hasKey) → 幂等两态 Lua → 预扣 Lua → 置已消费   = 4 次往返
 *  快路径（阶段 19）：合并脚本（幂等 + 预扣一次原子）→ 置已消费                        = 2 次往返
 * </pre>
 *
 * <p>口径说明：两条路径都跑真实的 Lua/命令（同一套 state 语义），计时只包住"核心链路"；
 * 每轮后的计数回填/幂等清理不计时（两条路径同等处理）。localhost 下绝对耗时是 µs 级——
 * 该实验看的是<b>往返次数差的相对收益</b>，跨网络部署时这个差会乘以 RTT 放大。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FastPathDemoService {

    private final PreDeductService preDeduct;
    private final IdempotentSupport idempotentSupport;
    private final StringRedisTemplate redis;

    private static final String COUNTER_KEY = "campus:prededuct:demo:fastpath";
    private static final long IDEM_TTL_SECONDS = 5;

    public Map<String, Object> run(int roundsParam) {
        int rounds = Math.max(10, Math.min(roundsParam, 2000));
        // 演示计数器：容量足够两条路径各扣 rounds 次
        redis.opsForValue().set(COUNTER_KEY, String.valueOf(rounds * 10L + 100), Duration.ofSeconds(180));
        String tag = "demo-" + System.currentTimeMillis();

        List<Long> oldUs = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) {
            String idemKey = IdempotentSupport.key("demo-fastpath", 0L, tag + "-o" + i);
            long t0 = System.nanoTime();
            // 旧路径核心（与阶段 18 的切面 + 闸门慢路径一致）
            preDeduct.ensureInitialized(COUNTER_KEY, meta(), () -> 999_999);
            idempotentSupport.tryAcquire(idemKey, IDEM_TTL_SECONDS);
            preDeduct.tryDeduct(List.of(COUNTER_KEY), 1);
            idempotentSupport.markConsumed(idemKey, IDEM_TTL_SECONDS);
            oldUs.add((System.nanoTime() - t0) / 1_000);
            cleanup(idemKey);
        }

        List<Long> newUs = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) {
            String idemKey = IdempotentSupport.key("demo-fastpath", 0L, tag + "-n" + i);
            long t0 = System.nanoTime();
            // 快路径核心（与 CourseSelectGate 快路径一致）
            preDeduct.tryFastPath(COUNTER_KEY, idemKey, IDEM_TTL_SECONDS, 1);
            idempotentSupport.markConsumed(idemKey, IDEM_TTL_SECONDS);
            newUs.add((System.nanoTime() - t0) / 1_000);
            cleanup(idemKey);
        }

        // 收尾：演示计数器直接删除（不污染预扣指标与注册表）
        redis.delete(COUNTER_KEY);
        preDeduct.dropCounter(COUNTER_KEY);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rounds", rounds);
        m.put("oldPath", stat("阶段 18 路径（hasKey + 幂等 + 预扣 + 置已消费）", 4, oldUs));
        m.put("newPath", stat("阶段 19 快路径（合并脚本 + 置已消费）", 2, newUs));
        m.put("note", "同一计数器、同一批操作语义；本地 loopback 绝对耗时是 µs 级——看往返次数差（4 → 2）"
                + "在真实网络 RTT（0.2~1ms）下的放大效应：每笔写请求省 2 次 RTT");
        return m;
    }

    /** 每轮收尾：删幂等键 + 直接 INCRBY 回填演示计数器（不走业务方法，避免污染预扣指标） */
    private void cleanup(String idemKey) {
        try {
            redis.delete(idemKey);
            redis.opsForValue().increment(COUNTER_KEY, 1);
        } catch (Exception e) {
            log.warn("[快路径演示] 收尾失败（忽略）: {}", e.getMessage());
        }
    }

    private PreDeductService.KeyMeta meta() {
        return new PreDeductService.KeyMeta(COUNTER_KEY, PreDeductService.TYPE_CLASS, 0L,
                null, null, "合并脚本对照演示", System.currentTimeMillis());
    }

    private Map<String, Object> stat(String name, int redisRounds, List<Long> us) {
        List<Long> sorted = new ArrayList<>(us);
        sorted.sort(Long::compareTo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("redisRounds", redisRounds);
        m.put("avgUs", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0) * 10) / 10.0);
        m.put("p50Us", percentile(sorted, 0.50));
        m.put("p95Us", percentile(sorted, 0.95));
        return m;
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
