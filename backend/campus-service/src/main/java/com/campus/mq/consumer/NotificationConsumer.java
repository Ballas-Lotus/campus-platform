package com.campus.mq.consumer;

import com.campus.mq.MqTopics;
import com.campus.mq.idempotent.MQIdempotentSupport;
import com.campus.mq.message.NotificationMessage;
import com.campus.notification.dao.entity.NotificationDO;
import com.campus.notification.dao.mapper.NotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 站内信消费者（campus_notification_topic）：消息 → t_notification 落库。
 * <b>阶段 19：批量消费版</b>。
 *
 * <h3>为什么改用原生 DefaultMQPushConsumer（诚实说明，写进 docs/13）</h3>
 * <p>本项目的 {@code rocketmq-spring-boot 2.2.3} <b>不支持消费端批量</b>：
 * 注解没有 {@code consumeMessageBatchMaxSize} 属性，容器也只声明了
 * {@code handleMessage(MessageExt)}（逐条处理）。想用"一次拉取、一次 ACK 处理多条"，
 * 只能绕过 spring 封装，用底层 client 注册 {@link MessageListenerConcurrently} 并显式设置
 * {@code setConsumeMessageBatchMaxSize}——这正是本类的做法（协议与幂等语义与逐条版完全一致）。</p>
 *
 * <p>批量语义：一次最多拉 20 条；整批处理成功后<b>统一 ACK</b>；任一条失败 → 整批 RECONSUME_LATER，
 * 已成功落库的条目由 {@link MQIdempotentSupport} 两态幂等跳过（key 与逐条版相同：
 * {@code campus:mq:idem:notification:{messageKey}}）。低流量时批大小自然退化为 1（与逐条等价）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationMapper notificationMapper;
    private final MQIdempotentSupport idempotentSupport;
    private final BatchConsumeMetrics batchMetrics;

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;

    /** 幂等键 TTL（与逐条版一致：24 小时） */
    private static final long IDEM_TTL_SECONDS = 86_400;
    /** 一次消费批量上限（拉取与 ACK 的批粒度） */
    private static final int BATCH_MAX_SIZE = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer(MqTopics.NOTIFICATION_GROUP);
            consumer.setNamesrvAddr(nameServer);
            consumer.setConsumeMessageBatchMaxSize(BATCH_MAX_SIZE);
            consumer.subscribe(MqTopics.NOTIFICATION, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                try {
                    handleBatch(messages);
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Exception e) {
                    log.warn("[MQ 批量消费] 本批处理失败，触发重试（已成功条目将被幂等跳过）: {} {}",
                            e.getClass().getSimpleName(), e.getMessage());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            });
            consumer.start();
            log.info("[MQ 批量消费] 站内信消费者已启动：topic={} group={} batchMaxSize={} namesrv={}",
                    MqTopics.NOTIFICATION, MqTopics.NOTIFICATION_GROUP, BATCH_MAX_SIZE, nameServer);
        } catch (MQClientException e) {
            // MQ 不可用不阻断服务启动（站内信降级为不消费；恢复需重启本服务）
            log.warn("[MQ 批量消费] 消费者启动失败（站内信将不落库，恢复需重启）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("[MQ 批量消费] 站内信消费者已关闭");
        }
    }

    private void handleBatch(List<MessageExt> messages) {
        long t0 = System.nanoTime();
        batchMetrics.onBatch(messages.size());
        int inserted = 0;
        int skipped = 0;
        int unparsable = 0;
        for (MessageExt raw : messages) {
            NotificationMessage message;
            try {
                message = MAPPER.readValue(raw.getBody(), NotificationMessage.class);
            } catch (Exception e) {
                unparsable++;
                log.warn("[MQ 批量消费] 消息体解析失败，跳过: msgId={} {}", raw.getMsgId(), e.getMessage());
                continue;
            }
            boolean first;
            try {
                first = idempotentSupport.tryAcquire("notification", message.messageKey(), IDEM_TTL_SECONDS);
            } catch (Exception e) {
                // Redis 异常：按"未消费"处理（宁可重复落库一次也不丢消息；唯一性由业务兜底）
                first = true;
                log.warn("[MQ 幂等] Redis 异常，按未消费处理: key={} {}", message.messageKey(), e.getMessage());
            }
            if (!first) {
                skipped++;
                continue;
            }
            NotificationDO notification = new NotificationDO();
            notification.setUserId(message.userId());
            notification.setTitle(message.title());
            notification.setContent(message.content());
            notification.setBizType(message.bizType());
            notification.setBizId(message.bizId());
            notification.setIsRead(0);
            try {
                notificationMapper.insert(notification);
                idempotentSupport.markConsumed("notification", message.messageKey(), IDEM_TTL_SECONDS);
                inserted++;
            } catch (Exception e) {
                idempotentSupport.releaseQuietly("notification", message.messageKey());
                batchMetrics.onFailed();
                throw e;   // 整批重试
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        batchMetrics.onProcessed(inserted, skipped, ms);
        log.info("[MQ 批量消费] 本批 {} 条：落库 {} / 幂等跳过 {} / 解析失败 {}（{} ms，一次 ACK）",
                messages.size(), inserted, skipped, unparsable, ms);
    }
}
