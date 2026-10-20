package com.campus.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 接口幂等底层支持（阶段 19 从 {@link CampusIdempotentAspect} 抽取）。
 *
 * <p>抽出来的原因：选课快路径要把"幂等两态 + 预扣"合并进<b>一个</b> Lua 脚本（阶段 19），
 * 而合并脚本需要与切面<b>完全一致</b>的 key 规则与状态语义——与其复制两份，不如共用这一份。</p>
 *
 * <p>两态 Lua（一次原子判定）：返回 1 = 首次（置 CONSUMING，放行）/ 0 = 已消费（拦截）/
 * 2 = 消费中（并发处理中，拦截）。键 = {@code campus:idem:{prefix}:{userId}:{业务键}}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentSupport {

    public static final String KEY_PREFIX = "campus:idem:";
    public static final String CONSUMED = "CONSUMED";
    public static final String CONSUMING = "CONSUMING";

    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<Long> TWO_STATE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('GET', KEYS[1])
            if state == 'CONSUMED' then return 0 end
            if state == 'CONSUMING' then return 2 end
            redis.call('SET', KEYS[1], 'CONSUMING', 'EX', ARGV[1])
            return 1
            """, Long.class);

    /** 幂等键规约：{@code campus:idem:{prefix}:{userId}:{业务键}}（与压测清理脚本同一命名空间） */
    public static String key(String prefix, Long userId, String businessKey) {
        return KEY_PREFIX + prefix + ":" + (userId == null ? "anon" : userId) + ":" + businessKey;
    }

    /**
     * 抢"处理中"标记。
     *
     * @return 1 = 首次（放行）；0 = 窗口内已成功；2 = 并发处理中。
     * @throws org.springframework.dao.DataAccessException Redis 异常（由调用方决定降级策略）
     */
    public long tryAcquire(String key, long ttlSeconds) {
        Long r = redis.execute(TWO_STATE_SCRIPT, List.of(key), String.valueOf(ttlSeconds));
        return r == null ? -1 : r;
    }

    /** 业务成功后置"已消费"（窗口内所有重试都会拿到 40060） */
    public void markConsumed(String key, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, CONSUMED, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[幂等] 置已消费失败（窗口内可能允许再次提交，由唯一索引兜底）: key={} {}", key, e.getMessage());
        }
    }

    /** 业务失败/被拒后释放标记（允许用户重试）；忽略异常 */
    public void releaseQuietly(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[幂等] 释放标记失败（到期自动释放）: key={} {}", key, e.getMessage());
        }
    }
}
