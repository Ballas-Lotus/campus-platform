package com.campus.mq.idempotent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * MQ 消费幂等底层支持（阶段 19 从 {@link MQIdempotentAspect} 抽取）。
 *
 * <p>抽出来的原因：<b>批量消费</b>的消费者方法签名是 {@code List<MessageExt>}，注解切面
 * 无法再按"单条消息首参"工作——批量消费者改为循环内逐条调用本组件完成两态判定，
 * 与切面共用同一份 Lua 与 key 规则（{@code campus:mq:idem:{prefix}:{messageKey}}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MQIdempotentSupport {

    private final StringRedisTemplate redis;

    private static final String KEY_PREFIX = "campus:mq:idem:";

    /** 两态幂等 Lua（原子判定 + 置"消费中"）：1 = 首次 / 0 = 已消费 / 2 = 消费中 */
    private static final DefaultRedisScript<Long> TWO_STATE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('GET', KEYS[1])
            if state == 'CONSUMED' then return 0 end
            if state == 'CONSUMING' then return 2 end
            redis.call('SET', KEYS[1], 'CONSUMING', 'EX', ARGV[1])
            return 1
            """, Long.class);

    /**
     * 抢"消费中"标记。
     *
     * @return true = 首次投递（应处理）；false = 重复投递（跳过）。
     * @throws org.springframework.dao.DataAccessException Redis 异常（由调用方决定：按未消费处理或整批重试）
     */
    public boolean tryAcquire(String prefix, String messageKey, long ttlSeconds) {
        Long r = redis.execute(TWO_STATE_SCRIPT, List.of(KEY_PREFIX + prefix + ":" + messageKey),
                String.valueOf(ttlSeconds));
        return r != null && r == 1L;
    }

    /** 消费成功 → 置"已消费" */
    public void markConsumed(String prefix, String messageKey, long ttlSeconds) {
        try {
            redis.opsForValue().set(KEY_PREFIX + prefix + ":" + messageKey, "CONSUMED",
                    Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[MQ 幂等] 置已消费失败（窗口内可能重复处理，由业务幂等/唯一索引兜底）: key={} {}",
                    messageKey, e.getMessage());
        }
    }

    /** 消费失败（将触发 MQ 重试）→ 释放标记，让重试能再次进入 */
    public void releaseQuietly(String prefix, String messageKey) {
        try {
            redis.delete(KEY_PREFIX + prefix + ":" + messageKey);
        } catch (Exception e) {
            log.warn("[MQ 幂等] 释放标记失败（到期自动释放）: key={} {}", messageKey, e.getMessage());
        }
    }
}
