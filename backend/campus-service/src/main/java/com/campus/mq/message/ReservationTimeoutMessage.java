package com.campus.mq.message;

import com.campus.mq.MqMessage;

/**
 * 预约超时消息（延时投递：到期时若仍未签到 → 判爽约并释放资源）。
 *
 * <p>hop：延时兜底重投递的跳数——延时不足（broker 最大延时限制/时钟偏差）时，
 * 消费者按剩余时间重投递并 hop+1，messageKey 随之变化（幂等键不冲突，每跳只处理一次）。
 * force=true 供演示接口使用（跳过"未到点"校验，立即评估）。</p>
 *
 * <p>userId（阶段 14 新增）：分库分表后按 user_id 路由 → 消费者单分片命中预约行。</p>
 */
public record ReservationTimeoutMessage(
        String messageKey,
        Long reservationId,
        Long userId,
        boolean force,
        int hop) implements MqMessage {
    public static ReservationTimeoutMessage of(Long reservationId, Long userId, boolean force, int hop) {
        return new ReservationTimeoutMessage("timeout:" + reservationId + ":" + hop, reservationId, userId, force, hop);
    }
}
