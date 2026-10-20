package com.campus.mq.idempotent;

import com.campus.mq.MqMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 消费幂等切面（"消费中/已消费"两态 Lua，照搬 12306）。
 *
 * <p>Lua 原子判定：0 = 已消费（跳过）/ 1 = 首次（放行并置消费中）/ 2 = 消费中（并发重复，跳过）。
 * 业务异常时删除标记放行 MQ 重试。</p>
 *
 * <p><b>阶段 19</b>：Lua 与 key 规则抽取到 {@link MQIdempotentSupport}——
 * 批量消费者（{@code List<MessageExt>} 签名）无法使用本切面，改为在循环内逐条调用同一组件，
 * 两种消费形态共享同一份状态语义。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MQIdempotentAspect {

    private final MQIdempotentSupport idempotentSupport;

    @Around("@annotation(mqIdempotent)")
    public Object around(ProceedingJoinPoint joinPoint, MQIdempotent mqIdempotent) throws Throwable {
        Object[] args = joinPoint.getArgs();
        if (args.length == 0 || !(args[0] instanceof MqMessage message)) {
            throw new IllegalStateException("@MQIdempotent 方法首参须实现 MqMessage: "
                    + joinPoint.getSignature());
        }
        boolean first = idempotentSupport.tryAcquire(mqIdempotent.prefix(), message.messageKey(),
                mqIdempotent.ttlSeconds());

        if (first) {
            try {
                Object result = joinPoint.proceed();
                idempotentSupport.markConsumed(mqIdempotent.prefix(), message.messageKey(),
                        mqIdempotent.ttlSeconds());
                return result;
            } catch (Throwable t) {
                // 业务失败：移除标记放行 MQ 重试（消费幂等的正确性不依赖消费成功与否）
                idempotentSupport.releaseQuietly(mqIdempotent.prefix(), message.messageKey());
                throw t;
            }
        }
        log.info("[MQ 幂等] 重复投递已跳过: key={}", message.messageKey());
        return null;
    }
}
