package com.campus.mq.consumer;

import com.campus.common.exception.ClientException;
import com.campus.course.errorcode.CourseErrorCode;
import com.campus.course.service.CourseService;
import com.campus.mq.MqTopics;
import com.campus.mq.idempotent.MQIdempotentSupport;
import com.campus.mq.message.CourseSelectMessage;
import com.campus.stock.CourseStockAcceptService;
import com.campus.stock.CourseStockService;
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
 * <b>阶段 21 · 选课落库消费者（受理制的"迟到的记录者"）</b>。
 *
 * <p>每条消息（一次受理）的完整判定树——<b>顺序即正确性</b>，四条分支缺一不可：</p>
 * <pre>
 *  ① 台账不存在？（受理已被解决：落库成功清账 / 已补偿 / 超时裁决过）
 *        → 只置"已消费"，<b>绝不落库</b>
 *        （否则：超时补偿后迟到的消息会再把名额落库一次 → 结构少扣 1 → 超卖）
 *  ② 落库行 op_id == 本受理？→ 已生效（重放/成功后清账前崩溃）→ 只清账，绝不补偿
 *  ③ 正常调用 CourseService.select(userId, classId, opId) 落库（四道防线原样保留）：
 *        成功  → 清账 + settled(ok) 回执（"选课成功"站内信由落库事务 afterCommit 发出）
 *  ④ 业务拒绝（重复/真满/时间冲突/班级不存在）：
 *        - 复查 op_id 命中 → 竞态窗口内其实已生效 → 清账收尾
 *        - 否则 → 补偿（名额加回 + 版本 +1 + 修正信号）→
 *                 非"已选过"再清"已选集合" → settled(false) 回执 + 失败站内信
 *  系统异常 → 整批 RECONSUME_LATER（不清账、不补偿——受理依然有效，等重试）
 * </pre>
 *
 * <p>消费幂等两层：MQ 两态幂等键（{@code campus:mq:idem:course-select:{opId}}，24h）
 * 是快路径；台账 + op_id 是正确性底线（幂等键过期后重放仍安全）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseSelectConsumer {

    private final CourseService courseService;
    private final CourseStockService stockService;
    private final CourseStockAcceptService acceptService;
    private final MQIdempotentSupport idempotentSupport;
    private final BatchConsumeMetrics batchMetrics;
    /** 仅用于启动时的生产者路由预热（受理链路的发送超时只有 300ms，见 warmupProducerRoute） */
    private final org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;

    /** MQ 幂等键 TTL：24 小时（重投递窗口远小于此；过期后由台账 + op_id 兜底） */
    private static final long IDEM_TTL_SECONDS = 86_400;
    public static final int BATCH_MAX_SIZE = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer(MqTopics.COURSE_SELECT_GROUP);
            consumer.setNamesrvAddr(nameServer);
            consumer.setConsumeMessageBatchMaxSize(BATCH_MAX_SIZE);
            consumer.subscribe(MqTopics.COURSE_SELECT, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                try {
                    handleBatch(messages);
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Exception e) {
                    // 系统异常（DB 不可用等）：不清账、不补偿，整批重试（已处理条目由幂等/台账跳过）
                    log.warn("[选课落库] 本批处理失败，触发重试: {} {}", e.getClass().getSimpleName(), e.getMessage());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            });
            consumer.start();
            log.info("[选课落库] 消费者已启动：topic={} group={} batchMaxSize={} namesrv={}",
                    MqTopics.COURSE_SELECT, MqTopics.COURSE_SELECT_GROUP, BATCH_MAX_SIZE, nameServer);
            warmupProducerRoute();
        } catch (MQClientException e) {
            log.error("[选课落库] 消费者启动失败（受理将无法落库！请检查 RocketMQ）: {}", e.getMessage());
        }
    }

    /**
     * 生产者路由预热：受理链路的发送超时只有 300ms（宁可快速失败回滚，不让用户干等），
     * 而"首次向新 topic 发送"需要从 NameServer 拉路由 + 建连接（还兼有 broker 自动建 topic 的
     * 首投延迟），冷启动后第一笔受理极易超时被补偿。用一条 3 秒超时的探针把路由焐热——
     * 探针消息自身会被本消费者当作"解析失败"安全跳过（见 {@link #handleBatch}）。
     */
    private void warmupProducerRoute() {
        try {
            rocketMQTemplate.syncSend(MqTopics.COURSE_SELECT, "warmup-probe", 3000);
            log.info("[选课落库] 生产者路由预热完成（首笔受理不再吃冷启动延迟）");
        } catch (Exception e) {
            log.warn("[选课落库] 生产者预热失败（首笔受理可能超时被补偿，随后自愈）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("[选课落库] 消费者已关闭");
        }
    }

    private void handleBatch(List<MessageExt> messages) {
        long t0 = System.nanoTime();
        batchMetrics.onBatch(messages.size());
        int landed = 0;
        int settled = 0;
        int compensated = 0;
        int skipped = 0;
        int unparsable = 0;
        for (MessageExt raw : messages) {
            CourseSelectMessage m;
            try {
                m = MAPPER.readValue(raw.getBody(), CourseSelectMessage.class);
            } catch (Exception e) {
                unparsable++;
                log.warn("[选课落库] 消息体解析失败，跳过: msgId={} {}", raw.getMsgId(), e.getMessage());
                continue;
            }
            boolean first;
            try {
                first = idempotentSupport.tryAcquire("course-select", m.opId(), IDEM_TTL_SECONDS);
            } catch (Exception e) {
                first = true;   // Redis 异常：按未消费处理（正确性由台账 + op_id 保证）
                log.warn("[选课落库] 幂等判定异常，按未消费处理: op={} {}", m.opId(), e.getMessage());
            }
            if (!first) {
                skipped++;
                continue;
            }
            try {
                int r = process(m);
                if (r == 1) {
                    landed++;
                } else if (r == 2) {
                    settled++;
                } else if (r == 3) {
                    compensated++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                idempotentSupport.releaseQuietly("course-select", m.opId());   // 让重试能再进入
                throw e;   // 整批重试（已处理的条目会被台账/幂等跳过）
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        batchMetrics.onProcessed(landed + settled, skipped, ms);
        log.info("[选课落库] 本批 {} 条：落库 {} / 已生效收尾 {} / 补偿 {} / 跳过 {} / 解析失败 {}（{} ms，一次 ACK）",
                messages.size(), landed, settled, compensated, skipped, unparsable, ms);
    }

    /**
     * @return 1=已落库 2=已生效收尾（重放） 3=已补偿拒绝 0=跳过
     */
    private int process(CourseSelectMessage m) {
        Long userId = m.userId();
        Long classId = m.classId();
        Long courseId = m.courseId();

        // ① 台账不存在 = 已被解决（成功清账 / 已补偿 / 超时裁决）→ 绝不落库
        if (!stockService.pendingExists(courseId, classId, m.opId())) {
            idempotentSupport.markConsumed("course-select", m.opId(), IDEM_TTL_SECONDS);
            log.info("[选课落库] 受理已被解决，跳过迟到消息: op={} class={}", m.opId(), classId);
            return 0;
        }
        // ② 重放：落库行 op_id 命中 → 已生效 → 只清账
        if (m.opId().equals(acceptService.opIdOf(userId, classId))) {
            stockService.hdelPending(courseId, classId, m.opId());
            idempotentSupport.markConsumed("course-select", m.opId(), IDEM_TTL_SECONDS);
            log.info("[选课落库] 重放且已生效，仅清账: op={} class={}", m.opId(), classId);
            return 2;
        }
        // ③ 落库（事务内四道防线原样：锁用户行 / 唯一索引 / 冲突检测 / 条件更新占名额）
        try {
            courseService.select(userId, classId, m.opId());
            stockService.hdelPending(courseId, classId, m.opId());
            acceptService.settleNotify(userId, classId, true, null);
            idempotentSupport.markConsumed("course-select", m.opId(), IDEM_TTL_SECONDS);
            return 1;
        } catch (ClientException ce) {
            // ④ 业务拒绝
            if (m.opId().equals(acceptService.opIdOf(userId, classId))) {
                // 竞态窗口：并发实例刚落了同 op（本实例读到的拒绝是旧视图）→ 按已生效收尾
                stockService.hdelPending(courseId, classId, m.opId());
                idempotentSupport.markConsumed("course-select", m.opId(), IDEM_TTL_SECONDS);
                return 2;
            }
            int comp = acceptService.compensate(courseId, classId, m.opId());
            if (comp == CourseStockAcceptService.COMP_ERROR) {
                throw new RuntimeException("补偿失败（Redis 异常），整批重试: op=" + m.opId());
            }
            if (comp == CourseStockAcceptService.COMP_APPLIED) {
                // "已选过"不清集合（用户确实已选，集合里应当有它）；其余拒绝（真满/冲突/班级不存在）清掉
                if (!CourseErrorCode.ALREADY_SELECTED.code().equals(ce.errorCode)) {
                    stockService.selRemove(userId, classId);
                }
                acceptService.settleNotify(userId, classId, false, ce.errorMessage);
                idempotentSupport.markConsumed("course-select", m.opId(), IDEM_TTL_SECONDS);
                log.info("[选课落库] 业务拒绝已补偿: op={} class={} code={} {}",
                        m.opId(), classId, ce.errorCode, ce.errorMessage);
                return 3;
            }
            // COMP_NOOP：台账已被（超时裁决）解决过，裁决方已发过通知 → 不重复
            idempotentSupport.markConsumed("course-select", m.opId(), IDEM_TTL_SECONDS);
            return 0;
        }
    }
}
