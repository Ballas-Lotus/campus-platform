package com.campus.mq;

/**
 * MQ 消息统一契约：每条消息携带幂等键（messageKey）。
 *
 * <p>消费侧 {@code @MQIdempotent} 以 {@code prefix + messageKey} 作为 Redis 幂等键，
 * "消费中/已消费"两态防重复处理（照搬 12306）。</p>
 */
public interface MqMessage {

    /** 幂等键（业务唯一；同一条消息重复投递时保持一致，重投递场景应变化，见延时消息 hop） */
    String messageKey();
}
