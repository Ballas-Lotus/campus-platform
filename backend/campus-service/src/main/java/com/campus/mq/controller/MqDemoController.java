package com.campus.mq.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.exception.ClientException;
import com.campus.common.result.Result;
import com.campus.framework.user.core.UserContext;
import com.campus.mq.MqMessagePublisher;
import com.campus.mq.MqTopics;
import com.campus.mq.message.NotificationMessage;
import com.campus.mq.message.ReservationTimeoutMessage;
import com.campus.notification.dao.entity.NotificationDO;
import com.campus.notification.dao.mapper.NotificationMapper;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import com.campus.reservation.dto.req.ReservationCreateReqDTO;
import com.campus.reservation.service.PenaltyService;
import com.campus.reservation.service.ReservationService;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.entity.ResourceSlotDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import com.campus.resource.dao.mapper.ResourceSlotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MQ 演示接口（阶段 13；建议使用测试账号体验——超时演示会施加 24h 禁约，测试账号登录即重置）。
 *
 * <ul>
 *   <li>{@code POST /api/mq/demo/notification}：发一条站内信消息（异步落库 → 顶栏铃铛可见）</li>
 *   <li>{@code POST /api/mq/demo/idempotent}：同一条消息投递两次（幂等消费只落库一次）</li>
 *   <li>{@code POST /api/mq/demo/timeout}：真实预约 + 8 秒延时消息（到点未签到 → 判爽约并回滚库存）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mq")
@RequiredArgsConstructor
public class MqDemoController {

    private final MqMessagePublisher publisher;
    private final ReservationService reservationService;
    private final ReservationMapper reservationMapper;
    private final ResourceSlotMapper slotMapper;
    private final ResourceMapper resourceMapper;
    private final NotificationMapper notificationMapper;
    private final PenaltyService penaltyService;
    /** 阶段 19：消费者批量消费指标 */
    private final com.campus.mq.consumer.BatchConsumeMetrics batchConsumeMetrics;

    /** 图书馆座位资源 id 区间（种子数据：10001~13000） */
    private static final long SEAT_MIN = 10001L;
    private static final long SEAT_MAX = 13000L;
    /** 开放时段（与 ReservationService 一致：08:00~22:00）——演示时刻要在时段表内才有片可用 */
    private static final int OPEN_HOUR = 8;
    private static final int CLOSE_HOUR = 22;
    /** 超时演示的延时（毫秒） */
    private static final long DEMO_TIMEOUT_DELAY_MS = 8_000L;

    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> data = new HashMap<>();
        data.put("namesrv", "127.0.0.1:9876");
        data.put("producerGroup", "campus-producer");
        data.put("topics", List.of(
                Map.of("topic", MqTopics.NOTIFICATION, "group", MqTopics.NOTIFICATION_GROUP,
                        "purpose", "预约/选课成功 → 站内信落库"),
                Map.of("topic", MqTopics.RESERVATION_TIMEOUT, "group", MqTopics.RESERVATION_TIMEOUT_GROUP,
                        "purpose", "延时消息：到点未签到判爽约（Scheduler 兜底并存）")));
        data.put("idempotent", Map.of(
                "keyPrefix", "campus:mq:idem:",
                "states", "CONSUMING / CONSUMED（两态 Lua，照搬 12306）",
                "ttlSeconds", 86400));
        return Result.success(data);
    }

    /** 场景 A：发一条站内信（异步），返回消息键；铃铛未读数应 +1 */
    @PostMapping("/demo/notification")
    public Result<Map<String, Object>> demoNotification() {
        Long userId = UserContext.getUserId();
        String key = "demo-notify-" + userId + "-" + System.currentTimeMillis();
        publisher.sendNotification(new NotificationMessage(key, userId,
                "这是一条异步站内信",
                "本消息由 RocketMQ（" + MqTopics.NOTIFICATION + "）异步投递后落库——"
                        + "预约成功通知走的是同一条通道。",
                "MQ_DEMO", key));
        return Result.success(Map.of("messageKey", key, "topic", MqTopics.NOTIFICATION));
    }

    /** 场景 B：同一条消息投递两次（幂等消费只落库一次） */
    @PostMapping("/demo/idempotent")
    public Result<Map<String, Object>> demoIdempotent() {
        Long userId = UserContext.getUserId();
        String key = "demo-idem-" + userId + "-" + System.currentTimeMillis();
        NotificationMessage message = new NotificationMessage(key, userId,
                "幂等演示消息",
                "同一条消息被投递了两次，你只会看到我一次。",
                "MQ_DEMO", key);
        publisher.sendNotification(message);
        publisher.sendNotification(message);   // 同 messageKey 再投一次
        return Result.success(Map.of("messageKey", key, "sent", 2));
    }

    /** 场景 B2（阶段 19）：突发投递 N 条站内信——看消费者"批量消费"的批大小 */
    @PostMapping("/demo/notification-batch")
    public Result<Map<String, Object>> demoNotificationBatch(
            @RequestParam(defaultValue = "50") int count) {
        int n = Math.max(1, Math.min(count, 500));
        Long userId = UserContext.getUserId();
        String batchId = "batch-" + System.currentTimeMillis();
        java.util.List<NotificationMessage> messages = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String key = "demo-batch-" + userId + "-" + batchId + "-" + i;
            messages.add(new NotificationMessage(key, userId,
                    "批量消费演示 #" + (i + 1),
                    "第 " + (i + 1) + " 条突发站内信——消费者按「一次拉取、一次 ACK」批量处理。",
                    "MQ_DEMO_BATCH", batchId + "-" + i));
        }
        // 批量投递（一次调用）——逐条 syncSend 会被消费者实时追平，批大小恒为 1，演示不出"批"
        publisher.sendNotificationBatch(messages);
        return Result.success(Map.of("sent", n, "topic", MqTopics.NOTIFICATION,
                "note", "批量投递后 1~2 秒刷新「消费者批量消费」面板，看批次数与平均批大小"));
    }

    /** 阶段 19：消费者批量消费指标（批次/消息数/平均批大小/幂等跳过） */
    @GetMapping("/consumer-stats")
    public Result<Map<String, Object>> consumerStats() {
        return Result.success(batchConsumeMetrics.snapshot());
    }

    /** 场景 B 轮询：该消息键落库了几条（预期 1） */
    @GetMapping("/demo/idempotent/{key}")
    public Result<Map<String, Object>> idempotentResult(@PathVariable String key) {
        Long count = notificationMapper.selectCount(Wrappers.lambdaQuery(NotificationDO.class)
                .eq(NotificationDO::getBizType, "MQ_DEMO")
                .eq(NotificationDO::getBizId, key));
        return Result.success(Map.of("messageKey", key, "count", count));
    }

    /** 场景 C：真实预约 + 8 秒延时消息（到点判爽约 → 回滚库存 + 位图 + 禁约 24h） */
    @PostMapping("/demo/timeout")
    public Result<Map<String, Object>> demoTimeout() {
        Long userId = UserContext.getUserId();
        LocalDateTime start = nextWholeHourAtLeast5MinLater();
        ClientException lastError = null;
        for (int i = 0; i < 3; i++) {
            LocalDateTime candidate = start.plusHours(i);
            if (candidate.getHour() >= CLOSE_HOUR) {
                candidate = candidate.toLocalDate().plusDays(1).atTime(OPEN_HOUR, 0);
            }
            ResourceSlotDO slot = findFreeSeatSlot(candidate.toLocalDate(), candidate.toLocalTime());
            if (slot == null) {
                continue;
            }
            try {
                ReservationCreateReqDTO req = new ReservationCreateReqDTO();
                req.setResourceId(slot.getResourceId());
                req.setSlotDate(slot.getSlotDate());
                req.setStartTime(slot.getStartTime());
                req.setEndTime(slot.getEndTime());
                var reservation = reservationService.create(req);
                ResourceDO resource = resourceMapper.selectById(slot.getResourceId());
                // 演示用强制延时消息（hop=99 与真实超时消息的幂等键区分）
                publisher.sendReservationTimeout(
                        ReservationTimeoutMessage.of(reservation.getId(), userId, true, 99), DEMO_TIMEOUT_DELAY_MS);
                Map<String, Object> data = new HashMap<>();
                data.put("reservationId", reservation.getId());
                data.put("reservationNo", reservation.getReservationNo());
                data.put("resourceName", resource.getName());
                data.put("resourceLocation", resource.getLocation());
                data.put("slotDate", slot.getSlotDate().toString());
                data.put("startTime", slot.getStartTime().toString());
                data.put("endTime", slot.getEndTime().toString());
                data.put("delayMs", DEMO_TIMEOUT_DELAY_MS);
                data.put("before", Map.of("status", "APPROVED", "booked", 1));
                log.info("[MQ 演示] 超时场景就绪: reservation={} 延时 {}ms 后判爽约", reservation.getId(), DEMO_TIMEOUT_DELAY_MS);
                return Result.success(data);
            } catch (ClientException e) {
                lastError = e;   // 该时段冲突/受限 → 尝试下一个整点
            }
        }
        throw lastError != null ? lastError
                : new ClientException("演示时段不可用", "找不到可用的演示时段，请稍后再试",
                        com.campus.common.errorcode.BaseErrorCode.CLIENT_ERROR);
    }

    /** 场景 C 轮询：预约当前状态 + 片库存 + 禁约（预期 8 秒余后 → NO_SHOW / booked=0） */
    @GetMapping("/demo/timeout/{reservationId}")
    public Result<Map<String, Object>> timeoutResult(@PathVariable Long reservationId) {
        ReservationDO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            throw new ClientException("演示预约不存在", com.campus.common.errorcode.BaseErrorCode.NOT_FOUND);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("status", reservation.getStatus());
        Integer booked = null;
        if (reservation.getSlotId() != null) {
            ResourceSlotDO slot = slotMapper.selectById(reservation.getSlotId());
            booked = slot == null ? null : slot.getBooked();
        }
        data.put("booked", booked);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        data.put("checkinTime", reservation.getCheckinTime() == null ? null
                : reservation.getCheckinTime().format(fmt));
        PenaltyService.Penalty penalty = penaltyService.get(reservation.getUserId());
        data.put("penaltyUntil", penalty == null ? null : penalty.until().format(fmt));
        return Result.success(data);
    }

    // ------------------------------------------------------------------

    /** 下一个整点（且距现在至少 5 分钟），如 16:10 → 17:00；23:00 之后顺延次日 08:00 */
    /**
     * 演示时段：下一个整点（至少 5 分钟后），并夹到开放时段 08:00~22:00。
     *
     * <p>2026-10-10 修复：原实现只处理"≥22 点顺延到次日 8 点"，漏了 <b>凌晨 0~8 点</b>——
     * 那段时间算出的候选时刻（如 02:00）在时段表里根本不存在，演示会以
     * "找不到可用的演示时段"失败。现在两侧都做夹取。</p>
     */
    private LocalDateTime nextWholeHourAtLeast5MinLater() {
        LocalDateTime candidate = LocalDateTime.now().plusMinutes(5)
                .truncatedTo(ChronoUnit.HOURS).plusHours(1);
        if (candidate.getHour() < OPEN_HOUR) {
            return candidate.toLocalDate().atTime(OPEN_HOUR, 0);           // 凌晨 → 当天 08:00
        }
        if (candidate.getHour() >= CLOSE_HOUR) {
            return candidate.toLocalDate().plusDays(1).atTime(OPEN_HOUR, 0); // 深夜 → 次日 08:00
        }
        return candidate;
    }

    /** 找一个该时刻空闲的图书馆座位片 */
    private ResourceSlotDO findFreeSeatSlot(LocalDate date, LocalTime start) {
        return slotMapper.selectOne(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getSlotDate, date)
                .eq(ResourceSlotDO::getStartTime, start)
                .eq(ResourceSlotDO::getStatus, 1)
                .between(ResourceSlotDO::getResourceId, SEAT_MIN, SEAT_MAX)
                .apply("booked < capacity")
                .last("LIMIT 1"));
    }
}
