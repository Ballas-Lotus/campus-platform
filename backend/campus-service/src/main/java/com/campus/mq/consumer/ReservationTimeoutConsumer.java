package com.campus.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.mq.MqMessagePublisher;
import com.campus.mq.MqTopics;
import com.campus.mq.idempotent.MQIdempotent;
import com.campus.mq.message.ReservationTimeoutMessage;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import com.campus.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 预约超时消费者（campus_reservation_timeout_topic，延时消息）：
 * 到期时若预约仍是 APPROVED 且未签到 → 判爽约（释放库存 + 位图 + 禁约 24h）。
 *
 * <p>幂等三重保障：</p>
 * <ol>
 *   <li>@MQIdempotent：同一条消息（同 hop）重复投递只处理一次；</li>
 *   <li>状态校验：已签到/已终结的预约直接跳过（与 ReservationScheduler 兜底扫到同一条时也不会双罚）；</li>
 *   <li>"未到点"（时钟偏差/降级延时）→ 按剩余时长重投递（hop+1，新幂等键）。</li>
 * </ol>
 *
 * <p>ReservationScheduler（每 60 秒扫描）保留为兜底——消息丢失时仍能判爽约（见 docs/04 §3.1）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.RESERVATION_TIMEOUT, consumerGroup = MqTopics.RESERVATION_TIMEOUT_GROUP)
public class ReservationTimeoutConsumer implements RocketMQListener<ReservationTimeoutMessage> {

    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;
    private final MqMessagePublisher publisher;

    @Value("${campus.reservation.checkin-after-minutes:15}")
    private long checkinAfterMinutes;

    @Override
    @MQIdempotent(prefix = "timeout")
    public void onMessage(ReservationTimeoutMessage message) {
        // 阶段 14：消息携带 userId → 带分片键查询单分片命中；旧消息（无 userId）退化为按主键全路由
        ReservationDO reservation = message.userId() == null
                ? reservationMapper.selectById(message.reservationId())
                : reservationMapper.selectOne(Wrappers.lambdaQuery(ReservationDO.class)
                        .eq(ReservationDO::getId, message.reservationId())
                        .eq(ReservationDO::getUserId, message.userId()));
        if (reservation == null || !"APPROVED".equals(reservation.getStatus())) {
            log.info("[MQ 消费] 超时消息跳过（预约不存在或已终结）: key={} status={}",
                    message.messageKey(), reservation == null ? "null" : reservation.getStatus());
            return;
        }
        if (reservation.getCheckinTime() != null) {
            log.info("[MQ 消费] 超时消息跳过（已签到）: key={}", message.messageKey());
            return;
        }
        LocalDateTime due = LocalDateTime.of(reservation.getSlotDate(), reservation.getStartTime())
                .plusMinutes(checkinAfterMinutes);
        LocalDateTime now = LocalDateTime.now();
        if (!message.force() && now.isBefore(due)) {
            long remaining = Duration.between(now, due).toMillis() + 1000;
            log.info("[MQ 消费] 未到点，重投递剩余 {}ms: key={}", remaining, message.messageKey());
            publisher.sendReservationTimeout(
                    ReservationTimeoutMessage.of(message.reservationId(), reservation.getUserId(),
                            false, message.hop() + 1), remaining);
            return;
        }
        reservationService.markNoShow(reservation.getId(), reservation.getUserId());
        log.warn("[MQ 消费] 延时到点判爽约: key={} reservationId={}（释放库存 + 禁约 24h）",
                message.messageKey(), reservation.getId());
    }
}
