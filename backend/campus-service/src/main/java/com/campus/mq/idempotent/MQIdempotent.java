package com.campus.mq.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 消费幂等注解（照搬 12306 @MQIdempotent 的"消费中/已消费"两态）。
 *
 * <p>标注在消费者 {@code onMessage} 方法上；方法首参须实现 {@link com.campus.mq.MqMessage}。
 * 幂等键 = {@code campus:mq:idem:{prefix}:{messageKey}}：</p>
 * <ul>
 *   <li>首次投递 → 置 CONSUMING → 执行业务 → 置 CONSUMED；</li>
 *   <li>重复投递（已消费/消费中）→ 直接跳过，不重复处理。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MQIdempotent {

    /** 幂等键前缀（按消费者业务区分，如 notification / timeout） */
    String prefix();

    /** 幂等键 TTL（秒），默认 24 小时 */
    long ttlSeconds() default 86400;
}
