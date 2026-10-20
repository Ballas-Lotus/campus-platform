package com.campus.mq;

import com.campus.mq.message.NotificationMessage;
import com.campus.mq.message.ReservationTimeoutMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * MQ 消息发布器（阶段 13）。
 *
 * <p>策略：<b>通知不阻断主流程</b>（发送失败仅告警——预约/选课主事务已完成，
 * 消息丢失等价于"少发一条站内信"，可接受；生产应换事务消息，见 docs/04 §3.2）。</p>
 *
 * <p>延时消息：优先 RocketMQ 5.x 自定义延时（定时消息）；异常时降级为
 * delayLevel 分级延时 + 消费侧"未到点重投递"链兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqMessagePublisher {

    private final RocketMQTemplate rocketMQTemplate;

    /** delayLevel 分级表（RocketMQ 4.x 共 18 级），用于降级发送 */
    private static final long[] DELAY_LEVEL_MILLIS = {
            1_000L, 5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 180_000L, 240_000L, 300_000L,
            360_000L, 420_000L, 480_000L, 540_000L, 600_000L, 1_200_000L, 1_800_000L, 3_600_000L, 7_200_000L
    };

    /**
     * 阶段 21（受理制）：发送选课落库消息——<b>与通知类不同，失败必须向上抛</b>：
     * 受理方据此补偿回滚并返回 40056（"未被受理"）；发送成功后的 PUBLISH 才代表"受理成立"。
     *
     * @param timeoutMs 发送超时（受理链路取 300ms：宁可快速失败回滚，不让用户干等）
     */
    public void sendCourseSelect(com.campus.mq.message.CourseSelectMessage message, long timeoutMs) {
        rocketMQTemplate.syncSend(MqTopics.COURSE_SELECT,
                MessageBuilder.withPayload(message)
                        .setHeader(RocketMQHeaders.KEYS, message.messageKey())
                        .build(),
                timeoutMs);
    }

    public void sendNotification(NotificationMessage message) {
        try {
            rocketMQTemplate.syncSend(MqTopics.NOTIFICATION,
                    MessageBuilder.withPayload(message)
                            .setHeader(RocketMQHeaders.KEYS, message.messageKey())
                            .build());
            log.info("[MQ 发送] 通知 {} → user={}", message.messageKey(), message.userId());
        } catch (Exception e) {
            log.warn("通知消息发送失败（不阻断主流程）: key={}，原因: {}", message.messageKey(), e.getMessage());
        }
    }

    /**
     * 阶段 19：<b>批量投递</b>站内信（一次网络调用落一批消息）。
     *
     * <p>用途：演示消费者"一次拉取、一次 ACK 处理多条"的批量消费——
     * 逐条 syncSend 时消息被消费者实时追平（批大小恒为 1），批量投递才能形成队列积压供批量拉取。</p>
     */
    public void sendNotificationBatch(java.util.List<NotificationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            java.util.List<org.springframework.messaging.Message<NotificationMessage>> batch = messages.stream()
                    .map(m -> MessageBuilder.withPayload(m)
                            .setHeader(RocketMQHeaders.KEYS, m.messageKey())
                            .build())
                    .collect(java.util.stream.Collectors.toList());
            rocketMQTemplate.syncSend(MqTopics.NOTIFICATION, batch);
            log.info("[MQ 发送] 批量通知 {} 条（一次调用）→ {}", batch.size(), MqTopics.NOTIFICATION);
        } catch (Exception e) {
            log.warn("批量通知发送失败（不阻断主流程）: {} 条，原因: {}", messages.size(), e.getMessage());
        }
    }

    public void sendReservationTimeout(ReservationTimeoutMessage message, long delayMillis) {
        try {
            rocketMQTemplate.syncSendDelayTimeMills(MqTopics.RESERVATION_TIMEOUT, message, delayMillis);
            log.info("[MQ 发送] 延时消息 {} 延时 {}ms", message.messageKey(), delayMillis);
        } catch (Exception e) {
            int level = nearestDelayLevel(delayMillis);
            log.warn("自定义延时发送失败（{}），降级 delayLevel={} 重发: {}",
                    e.getMessage(), level, message.messageKey());
            try {
                rocketMQTemplate.syncSend(MqTopics.RESERVATION_TIMEOUT,
                        MessageBuilder.withPayload(message)
                                .setHeader(RocketMQHeaders.KEYS, message.messageKey())
                                .build(),
                        3000, level);
            } catch (Exception e2) {
                log.warn("延时消息降级发送仍失败（不阻断主流程）: key={}，原因: {}",
                        message.messageKey(), e2.getMessage());
            }
        }
    }

    /** 选择 ≤ 目标时长的最大 delayLevel（1~18），超出 2h 用 18 级（消费侧会重投递剩余时长） */
    private int nearestDelayLevel(long delayMillis) {
        for (int i = DELAY_LEVEL_MILLIS.length - 1; i >= 0; i--) {
            if (DELAY_LEVEL_MILLIS[i] <= delayMillis) {
                return i + 1;
            }
        }
        return 1;
    }
}
