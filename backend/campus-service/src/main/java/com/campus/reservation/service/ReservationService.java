package com.campus.reservation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.common.exception.ClientException;
import com.campus.common.result.PageRespDTO;
import com.campus.framework.cache.CacheEvictions;
import com.campus.framework.cache.CampusCache;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.framework.reservation.BitmapUtil;
import com.campus.framework.user.core.UserContext;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.entity.UserQuotaDO;
import com.campus.reservation.dao.entity.UserTimeOccupancyDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import com.campus.reservation.dao.mapper.UserQuotaMapper;
import com.campus.reservation.dao.mapper.UserTimeOccupancyMapper;
import com.campus.reservation.dto.req.ReservationCreateReqDTO;
import com.campus.reservation.dto.resp.MyReservationRespDTO;
import com.campus.prededuct.PreDeductService;
import com.campus.reservation.errorcode.ReservationErrorCode;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.entity.ResourceSlotDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import com.campus.resource.dao.mapper.ResourceSlotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 预约核心服务（图书馆座位 / 体育场馆；讲座与课程走各自业务，见阶段 7/8）。
 *
 * <p>业务规则（2026-10-08 调整）：</p>
 * <ul>
 *   <li>图书馆与场馆均<b>不限预约次数</b>（原"每日/每周配额"已退役）</li>
 *   <li><b>签到违约</b>：开始后 15 分钟仍未签到 → 判爽约，禁约 24 小时（详见 ReservationScheduler）</li>
 *   <li><b>取消</b>：提前 30 分钟以上取消无惩罚；不足 30 分钟取消 → 禁约 10 分钟</li>
 * </ul>
 *
 * <p>创建预约的事务内五步（详解见 docs/03-预约核心伪代码.md）：</p>
 * <pre>
 *   ① 位图冲突检测（用户维度）  SELECT ... FOR UPDATE t_user_time_occupancy
 *   ② 逐片原子扣减（资源维度）  UPDATE t_resource_slot SET booked=booked+1
 *                               WHERE id=? AND booked &lt; capacity   —— 防超卖
 *   ③ 写预约主表（唯一索引兜底：uk_reservation_no 幂等、uk_reserved 防重）
 *   ④ 更新位图（OR 合并）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper slotMapper;
    private final ReservationMapper reservationMapper;
    private final UserTimeOccupancyMapper occupancyMapper;
    private final UserQuotaMapper quotaMapper;
    private final PenaltyService penaltyService;
    private final StringRedisTemplate stringRedisTemplate;
    private final com.campus.user.dao.mapper.UserMapper userMapper;
    /** 阶段 13：MQ 生产者（站内信 + 超时延时消息；发送失败不阻断主流程） */
    private final com.campus.mq.MqMessagePublisher mqMessagePublisher;
    /** 阶段 14：雪花发号器（基因法单号中的雪花序号段） */
    private final com.campus.framework.id.CampusIdGenerator idGenerator;
    /** 阶段 16：缓存门面（时段可用性在写后失效） */
    private final CampusCache cache;
    /** 阶段 17：预扣闸门计数器（取消/爽约时把预扣的名额还回去） */
    private final PreDeductService preDeduct;

    private static final int OPEN_HOUR = 8;
    private static final int CLOSE_HOUR = 22;
    private static final int MAX_HOURS = 4;

    @Value("${campus.reservation.venue-weekly-limit:4}")
    private int venueWeeklyLimit;

    /** 体育馆单次预约时长上限（小时；图书馆用 MAX_HOURS=4） */
    @Value("${campus.reservation.venue-max-hours:3}")
    private int venueMaxHours;

    @Value("${campus.reservation.checkin-before-minutes:30}")
    private long checkinBeforeMinutes;
    @Value("${campus.reservation.checkin-after-minutes:15}")
    private long checkinAfterMinutes;
    @Value("${campus.reservation.late-cancel-minutes:30}")
    private long lateCancelMinutes;
    @Value("${campus.reservation.late-cancel-penalty-minutes:10}")
    private long lateCancelPenaltyMinutes;
    @Value("${campus.reservation.noshow-penalty-hours:24}")
    private long noshowPenaltyHours;

    // ------------------------------------------------------------------
    // 创建预约
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public MyReservationRespDTO create(ReservationCreateReqDTO req) {
        Long userId = UserContext.getUserId();
        LocalDate today = LocalDate.now();
        LocalTime start = req.getStartTime();
        LocalTime end = req.getEndTime();
        LocalDate date = req.getSlotDate();

        // ① 资源校验
        ResourceDO resource = resourceMapper.selectById(req.getResourceId());
        if (resource == null || resource.getStatus() == null || resource.getStatus() != 1) {
            throw new ClientException(ReservationErrorCode.RESOURCE_UNAVAILABLE);
        }
        boolean isLibrary = "LIBRARY_SEAT".equals(resource.getResourceType());
        boolean isVenue = "VENUE".equals(resource.getResourceType());
        boolean isLecture = "LECTURE".equals(resource.getResourceType());
        if (!isLibrary && !isVenue && !isLecture) {
            // 课程名额有专属入口（阶段 8）
            throw new ClientException(ReservationErrorCode.RESOURCE_UNAVAILABLE);
        }

        // ② 时段校验：整点、时长（图书馆 1~4h / 体育馆 1~3h）、08:00~22:00、且尚未开始
        if (start.getMinute() != 0 || end.getMinute() != 0 || start.getSecond() != 0 || end.getSecond() != 0) {
            throw new ClientException(ReservationErrorCode.INVALID_TIME_RANGE);
        }
        long hours = Duration.between(start, end).toHours();
        int maxHours = isVenue ? venueMaxHours : MAX_HOURS;
        if (hours < 1 || hours > maxHours
                || start.getHour() < OPEN_HOUR || end.getHour() > CLOSE_HOUR
                || !start.isBefore(end)) {
            if (hours > maxHours) {
                throw new ClientException("单次预约时长超限: " + hours + "h > " + maxHours + "h",
                        "单次预约最长 " + maxHours + " 小时",
                        ReservationErrorCode.INVALID_TIME_RANGE);
            }
            throw new ClientException(ReservationErrorCode.INVALID_TIME_RANGE);
        }
        // 2026-10-08 规则：图书馆/体育馆仅支持预约当天与明天（时间片表仍预生成 14 天库存）；
        // 讲座为"开始前 7 天开放报名"（由 LectureController.signup 额外把关）
        int maxDayOffset = isLecture ? 7 : 1;
        if (date.isBefore(today) || date.isAfter(today.plusDays(maxDayOffset))) {
            if (isLecture) {
                throw new ClientException("讲座报名日期超窗: " + date,
                        "讲座仅支持开始前 7 天内报名",
                        ReservationErrorCode.DATE_OUT_OF_WINDOW);
            }
            throw new ClientException(ReservationErrorCode.DATE_OUT_OF_WINDOW);
        }
        LocalDateTime startDateTime = LocalDateTime.of(date, start);
        if (!startDateTime.isAfter(LocalDateTime.now())) {
            // 不允许预约已经开始（或过去）的时段——给出精确提示，避免"时段不合法"的误导
            throw new ClientException("预约已开始时段的请求: " + startDateTime,
                    "该时段已开始，请选择尚未开始的时段", ReservationErrorCode.INVALID_TIME_RANGE);
        }

        // ③ 惩罚检查（爽约 24h / 临近取消 5min）
        PenaltyService.Penalty penalty = penaltyService.get(userId);
        if (penalty != null) {
            String tip = penaltyService.describe(penalty);
            throw new ClientException("受限用户尝试预约: " + tip, tip,
                    ReservationErrorCode.PENALTY_RESTRICTED);
        }

        // ③.1 体育馆每周次数限制（用户维度，所有场馆合计；2026-10-08 规则：每周 4 次）
        //     图书馆不限次；取消会回退次数，爽约不回退
        UserQuotaDO venueQuota = null;
        if (isVenue) {
            LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            venueQuota = quotaMapper.selectOne(Wrappers.lambdaQuery(UserQuotaDO.class)
                    .eq(UserQuotaDO::getUserId, userId)
                    .eq(UserQuotaDO::getQuotaType, "VENUE_WEEKLY")
                    .eq(UserQuotaDO::getPeriodStart, weekStart));
            if (venueQuota != null && venueQuota.getUsedTimes() >= venueWeeklyLimit) {
                throw new ClientException("体育馆周次数超限: user=" + userId,
                        "本周体育馆预约次数已达上限（" + venueWeeklyLimit + " 次/周）",
                        ReservationErrorCode.QUOTA_EXCEEDED);
            }
        }

        // ③.2 锁用户行——把"同一用户"的预约串行化（2026-10-08 阶段 9 压测修复）
        //      背景：位图行 FOR UPDATE 对"当日尚无位图行"的用户锁不住（不存在的行只有间隙锁），
        //      同一用户并发首约时多个事务以"插入意向锁 vs 间隙锁"互相死锁 ——
        //      请求以 DeadlockLoser（系统繁忙）失败而非友好的"时间冲突"拒绝。
        //      先锁 t_user 行（与选课 CourseService 同款），后续读写全部串行，两类问题一并消除。
        userMapper.lockById(userId);

        // ④ 位图冲突检测（锁行防并发丢更新）
        BigInteger newBits = BitmapUtil.toBitmap(start, end);
        UserTimeOccupancyDO occupancy = occupancyMapper.selectForUpdate(userId, date);
        if (occupancy != null && BitmapUtil.intersects(BitmapUtil.fromHex(occupancy.getBitmap()), newBits)) {
            throw new ClientException(ReservationErrorCode.TIME_CONFLICT);
        }

        // ⑤ 逐片原子扣减（区间覆盖；任一失败整体回滚）
        List<ResourceSlotDO> slots = slotMapper.selectList(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getResourceId, resource.getId())
                .eq(ResourceSlotDO::getSlotDate, date)
                .orderByAsc(ResourceSlotDO::getStartTime));
        List<ResourceSlotDO> target = slots.stream()
                .filter(sl -> !sl.getStartTime().isBefore(start) && sl.getStartTime().isBefore(end))
                .collect(Collectors.toList());
        if (isLecture) {
            // 讲座为"整场报名"（2026-10-08 报名制，无选座）：整场一格——
            // 应恰好命中 1 片，且报名时段与场次片完全一致（不可拆分成半场报名）
            if (target.size() != 1
                    || !start.equals(target.get(0).getStartTime())
                    || !end.equals(target.get(0).getEndTime())) {
                throw new ClientException("讲座非整场报名请求: " + start + "~" + end,
                        "讲座需整场报名", ReservationErrorCode.INVALID_TIME_RANGE);
            }
        } else if (target.size() != hours) {
            throw new ClientException(ReservationErrorCode.SOLD_OUT);
        }
        for (ResourceSlotDO slot : target) {
            int updated = slotMapper.update(null, Wrappers.lambdaUpdate(ResourceSlotDO.class)
                    .setSql("booked = booked + 1, version = version + 1")
                    .eq(ResourceSlotDO::getId, slot.getId())
                    .eq(ResourceSlotDO::getResourceId, slot.getResourceId())   // 阶段 14：补分片键 → 单分片命中
                    .lt(ResourceSlotDO::getBooked, slot.getCapacity()));   // 数据库原子防超卖
            if (updated == 0) {
                throw new ClientException(ReservationErrorCode.SOLD_OUT);
            }
        }

        // ⑥ 写预约（唯一索引兜底：业务单号幂等 + 同片同用户有效预约唯一）
        //    2026-10-08 规则：规则校验全过即自动生效——不再有人工审批环节（游泳馆同其他场馆）
        ReservationDO reservation = new ReservationDO();
        reservation.setReservationNo(nextReservationNo(userId));
        reservation.setUserId(userId);
        reservation.setResourceId(resource.getId());
        reservation.setResourceType(resource.getResourceType());
        reservation.setSlotId(target.get(0).getId());
        reservation.setSlotDate(date);
        reservation.setStartTime(start);
        reservation.setEndTime(end);
        reservation.setTimeBitmap(BitmapUtil.toHex(newBits));
        reservation.setStatus("APPROVED");
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            throw new ClientException(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        // ⑦ 更新位图（OR 合并；行不存在则插入）
        if (occupancy == null) {
            UserTimeOccupancyDO neu = new UserTimeOccupancyDO();
            neu.setUserId(userId);
            neu.setOccupyDate(date);
            neu.setBitmap(BitmapUtil.toHex(newBits));
            neu.setVersion(1);
            try {
                occupancyMapper.insert(neu);
            } catch (DuplicateKeyException e) {
                occupancyMapper.updateBitmap(userId, date, BitmapUtil.toHex(newBits));
            }
        } else {
            BigInteger merged = BitmapUtil.merge(BitmapUtil.fromHex(occupancy.getBitmap()), newBits);
            occupancyMapper.updateBitmap(userId, date, BitmapUtil.toHex(merged));
        }

        // ⑧ 体育馆周次数 +1（取消会回退；爽约不回退）
        if (isVenue) {
            increaseVenueQuota(venueQuota, userId, today);
        }

        // ⑨ 异步侧（阶段 13 · MQ）：站内信通知 + 超时延时消息（到点未签到判爽约，兜底见 Scheduler）
        mqMessagePublisher.sendNotification(com.campus.mq.message.NotificationMessage.of(
                userId, "预约成功",
                String.format("你已成功预约 %s（%s %s~%s），单号 %s。签到窗口：开始前 %d 分钟至开始后 %d 分钟。",
                        resource.getName(), date, start, end, reservation.getReservationNo(),
                        checkinBeforeMinutes, checkinAfterMinutes),
                "RESERVATION", reservation.getReservationNo()));
        long timeoutDelay = Duration.between(LocalDateTime.now(),
                startDateTime.plusMinutes(checkinAfterMinutes)).toMillis();
        // 阶段 14：消息携带 userId —— 消费者可带分片键查询，单分片命中
        mqMessagePublisher.sendReservationTimeout(
                com.campus.mq.message.ReservationTimeoutMessage.of(reservation.getId(), userId, false, 0),
                Math.max(timeoutDelay, 1000));

        // ⑩ 阶段 16：写后失效——该资源当天的时段可用性缓存立刻作废（提交后执行，见 CacheEvictions）
        evictAvailability(resource.getId(), date);

        log.info("预约成功: user={} {} {} {}~{} {}", userId, resource.getName(), date, start, end,
                reservation.getReservationNo());
        return toResp(reservation, resource);
    }

    /** 体育馆周次数 +1（无行则新建；并发新建冲突时重读再 +1） */
    private void increaseVenueQuota(UserQuotaDO quota, Long userId, LocalDate today) {
        if (quota != null) {
            quota.setUsedTimes(quota.getUsedTimes() + 1);
            quotaMapper.updateById(quota);
            return;
        }
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        UserQuotaDO neu = new UserQuotaDO();
        neu.setUserId(userId);
        neu.setQuotaType("VENUE_WEEKLY");
        neu.setUsedTimes(1);
        neu.setPeriodStart(weekStart);
        neu.setPeriodEnd(weekStart.plusDays(6));
        try {
            quotaMapper.insert(neu);
        } catch (DuplicateKeyException e) {
            UserQuotaDO existing = quotaMapper.selectOne(Wrappers.lambdaQuery(UserQuotaDO.class)
                    .eq(UserQuotaDO::getUserId, userId)
                    .eq(UserQuotaDO::getQuotaType, "VENUE_WEEKLY")
                    .eq(UserQuotaDO::getPeriodStart, weekStart));
            if (existing != null) {
                existing.setUsedTimes(existing.getUsedTimes() + 1);
                quotaMapper.updateById(existing);
            }
        }
    }

    // ------------------------------------------------------------------
    // 签到
    // ------------------------------------------------------------------

    /**
     * 签到：窗口 [开始前 checkin-before-minutes, 开始后 checkin-after-minutes]。
     * 超窗未签到 → 由定时任务判爽约并施加 24 小时禁约。
     */
    @Transactional(rollbackFor = Exception.class)
    public void checkin(Long reservationId) {
        Long userId = UserContext.getUserId();
        // 阶段 14：带 user_id 查询 → 单分片命中（"我签到我自己的预约"，user_id 恒可得）
        ReservationDO reservation = reservationMapper.selectOne(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getId, reservationId)
                .eq(ReservationDO::getUserId, userId));
        if (reservation == null || !reservation.getUserId().equals(userId)) {
            throw new ClientException(ReservationErrorCode.NOT_YOUR_RESERVATION);
        }
        if (reservation.getCheckinTime() != null) {
            throw new ClientException(ReservationErrorCode.ALREADY_CHECKED_IN);
        }
        if (!"APPROVED".equals(reservation.getStatus())) {
            throw new ClientException(ReservationErrorCode.CHECKIN_NOT_ALLOWED);
        }
        LocalDateTime startDateTime = LocalDateTime.of(reservation.getSlotDate(), reservation.getStartTime());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startDateTime.minusMinutes(checkinBeforeMinutes))
                || now.isAfter(startDateTime.plusMinutes(checkinAfterMinutes))) {
            throw new ClientException(ReservationErrorCode.CHECKIN_NOT_ALLOWED);
        }
        reservation.setCheckinTime(now);
        reservationMapper.updateById(reservation);
        log.info("签到成功: user={} id={} 时段 {} {}", userId, reservationId,
                reservation.getSlotDate(), reservation.getStartTime());
    }

    // ------------------------------------------------------------------
    // 取消预约
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long reservationId) {
        Long userId = UserContext.getUserId();
        // 阶段 14：带 user_id 查询 → 单分片命中
        ReservationDO reservation = reservationMapper.selectOne(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getId, reservationId)
                .eq(ReservationDO::getUserId, userId));
        if (reservation == null) {
            throw new ClientException(ReservationErrorCode.CANNOT_CANCEL);
        }
        if (!reservation.getUserId().equals(userId)) {
            throw new ClientException(ReservationErrorCode.NOT_YOUR_RESERVATION);
        }
        if (!"PENDING".equals(reservation.getStatus()) && !"APPROVED".equals(reservation.getStatus())) {
            throw new ClientException(ReservationErrorCode.CANNOT_CANCEL);
        }
        LocalDateTime startDateTime = LocalDateTime.of(reservation.getSlotDate(), reservation.getStartTime());
        if (!startDateTime.isAfter(LocalDateTime.now())) {
            throw new ClientException(ReservationErrorCode.CANNOT_CANCEL);
        }

        // ①② 释放时间片 + 清位图
        releaseResources(reservation);

        // ③ 状态流转（生命周期由 status 表达，不物理删除）
        reservation.setStatus("CANCELLED");
        reservation.setRemark("用户取消");
        reservationMapper.updateById(reservation);

        // ④ 临近取消惩罚（2026-10-08 规则）：
        //    仅体育馆与讲座触发——距开始不足 30 分钟取消 → 禁约 5 分钟；
        //    图书馆取消无任何惩罚。
        if ("VENUE".equals(reservation.getResourceType())
                || "LECTURE".equals(reservation.getResourceType())) {
            long minutesToStart = Duration.between(LocalDateTime.now(), startDateTime).toMinutes();
            if (minutesToStart < lateCancelMinutes) {
                penaltyService.apply(userId, Duration.ofMinutes(lateCancelPenaltyMinutes), "临近开始取消预约");
                log.info("临近取消惩罚（体育馆）: user={} 距开始 {} 分钟（< {} 分钟），禁约 {} 分钟",
                        userId, minutesToStart, lateCancelMinutes, lateCancelPenaltyMinutes);
            }
        }

        // ⑤ 体育馆周次数回退（取消回退；爽约不回退）
        if ("VENUE".equals(reservation.getResourceType())) {
            LocalDate weekStart = LocalDate.now().minusDays(LocalDate.now().getDayOfWeek().getValue() - 1L);
            UserQuotaDO quota = quotaMapper.selectOne(Wrappers.lambdaQuery(UserQuotaDO.class)
                    .eq(UserQuotaDO::getUserId, userId)
                    .eq(UserQuotaDO::getQuotaType, "VENUE_WEEKLY")
                    .eq(UserQuotaDO::getPeriodStart, weekStart));
            if (quota != null && quota.getUsedTimes() > 0) {
                quota.setUsedTimes(quota.getUsedTimes() - 1);
                quotaMapper.updateById(quota);
            }
        }

        log.info("取消预约: user={} id={} {} {}", userId, reservationId,
                reservation.getSlotDate(), reservation.getStartTime());
    }

    // ------------------------------------------------------------------
    // 测试账号重置（2026-10-08：登录页常驻测试用户，每次登录清空数据）
    // ------------------------------------------------------------------

    /**
     * 重置测试用户的预约数据：释放占用中的时间片与位图 → 清空预约记录、位图、惩罚、周次数。
     *
     * <p>关键点：不能只删记录——**未终结预约占用着时间片**（booked 与位图），
     * 必须先逐条 releaseResources 归还库存，否则会留下"幽灵占用"。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetForTestUser(Long userId) {
        // ① 归还所有未终结预约占用的资源（片 booked-1 + 位图清位）
        List<ReservationDO> active = reservationMapper.selectList(
                Wrappers.lambdaQuery(ReservationDO.class)
                        .eq(ReservationDO::getUserId, userId)
                        .in(ReservationDO::getStatus, "PENDING", "APPROVED"));
        for (ReservationDO reservation : active) {
            releaseResources(reservation);
        }
        // ② 清空预约记录与位图（物理删除：预约/位图表不使用逻辑删除）
        int removed = reservationMapper.delete(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getUserId, userId));
        occupancyMapper.delete(Wrappers.lambdaQuery(UserTimeOccupancyDO.class)
                .eq(UserTimeOccupancyDO::getUserId, userId));
        // ③ 清惩罚（Redis）
        penaltyService.clear(userId);
        // ④ 周次数归零
        quotaMapper.update(null, Wrappers.lambdaUpdate(UserQuotaDO.class)
                .setSql("used_times = 0")
                .eq(UserQuotaDO::getUserId, userId));
        log.info("测试账号数据已重置: userId={}（释放 {} 条占用，删除 {} 条记录）", userId, active.size(), removed);
    }

    // ------------------------------------------------------------------
    // 我的预约
    // ------------------------------------------------------------------

    public PageRespDTO<MyReservationRespDTO> myReservations(String status, long pageNum, long pageSize) {
        Long userId = UserContext.getUserId();
        IPage<MyReservationRespDTO> page = reservationMapper.selectMyPage(
                Page.of(pageNum, pageSize), userId, status);
        return new PageRespDTO<>(page.getTotal(), page.getRecords());
    }

    // ------------------------------------------------------------------
    // 定时结算（由 ReservationScheduler 调用；也供取消复用资源释放）
    // ------------------------------------------------------------------

    /**
     * 判爽约：释放资源 + 清位图 + 状态 NO_SHOW + 禁约 24 小时（"第二天无法预约"）。
     * 幂等：非 APPROVED 状态直接返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markNoShow(Long reservationId) {
        markNoShow(reservationId, null);
    }

    /**
     * 判爽约（阶段 14：带 userId 时按分片键路由，单分片命中）。
     *
     * @param userId 预约归属用户（MQ 消息携带）；为 null 时退化为按主键全路由查询（Scheduler 兜底路径）
     */
    @Transactional(rollbackFor = Exception.class)
    public void markNoShow(Long reservationId, Long userId) {
        ReservationDO reservation = userId == null
                ? reservationMapper.selectById(reservationId)
                : reservationMapper.selectOne(Wrappers.lambdaQuery(ReservationDO.class)
                        .eq(ReservationDO::getId, reservationId)
                        .eq(ReservationDO::getUserId, userId));
        if (reservation == null || !"APPROVED".equals(reservation.getStatus())) {
            return;
        }
        reservation.setStatus("NO_SHOW");
        reservation.setRemark("未签到");
        reservationMapper.updateById(reservation);
        releaseResources(reservation);
        penaltyService.apply(reservation.getUserId(),
                Duration.ofHours(noshowPenaltyHours), "预约未签到");
        log.warn("判爽约: user={} id={} {} {}~{}（禁约 {} 小时）", reservation.getUserId(),
                reservationId, reservation.getSlotDate(), reservation.getStartTime(),
                reservation.getEndTime(), noshowPenaltyHours);
    }

    /**
     * 机器自动通过：PENDING → APPROVED（时段未开始的历史遗留申请）。幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoApprove(Long reservationId) {
        ReservationDO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || !"PENDING".equals(reservation.getStatus())) {
            return;
        }
        reservation.setStatus("APPROVED");
        reservation.setRemark("系统自动通过");
        reservationMapper.updateById(reservation);
        log.info("自动通过: id={} user={} {} {}", reservationId, reservation.getUserId(),
                reservation.getSlotDate(), reservation.getStartTime());
    }

    /**
     * 机器自动驳回：PENDING → CANCELLED 并释放资源（时段已开始的历史遗留申请）。幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoReject(Long reservationId) {
        ReservationDO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || !"PENDING".equals(reservation.getStatus())) {
            return;
        }
        reservation.setStatus("CANCELLED");
        reservation.setRemark("系统驳回：时段已开始");
        reservationMapper.updateById(reservation);
        releaseResources(reservation);
        log.info("自动驳回（时段已开始）: id={} user={}", reservationId, reservation.getUserId());
    }

    /**
     * 结算完成：已签到且时段结束 → COMPLETED。幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(Long reservationId) {
        ReservationDO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || !"APPROVED".equals(reservation.getStatus())) {
            return;
        }
        reservation.setStatus("COMPLETED");
        reservationMapper.updateById(reservation);
        log.info("结算完成: user={} id={}", reservation.getUserId(), reservationId);
    }

    /**
     * 释放预约资源：时间片 booked-1 + 位图清位（取消与爽约共用）。
     *
     * <p>阶段 17：同时把<b>预扣计数器</b>还回去（不退会造成"少卖"：Redis 扣了、库里其实空着）；
     * 阶段 16：写后失效该资源当天的可用性缓存。</p>
     */
    private void releaseResources(ReservationDO reservation) {
        List<ResourceSlotDO> slots = slotMapper.selectList(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getResourceId, reservation.getResourceId())
                .eq(ResourceSlotDO::getSlotDate, reservation.getSlotDate())
                .orderByAsc(ResourceSlotDO::getStartTime));
        List<ResourceSlotDO> target = slots.stream()
                .filter(sl -> !sl.getStartTime().isBefore(reservation.getStartTime())
                        && sl.getStartTime().isBefore(reservation.getEndTime()))
                .collect(Collectors.toList());
        List<String> predeductKeys = new ArrayList<>(target.size());
        for (ResourceSlotDO slot : target) {
            slotMapper.update(null, Wrappers.lambdaUpdate(ResourceSlotDO.class)
                    .setSql("booked = booked - 1, version = version + 1")
                    .eq(ResourceSlotDO::getId, slot.getId())
                    .eq(ResourceSlotDO::getResourceId, slot.getResourceId())   // 阶段 14：补分片键 → 单分片命中
                    .gt(ResourceSlotDO::getBooked, 0));
            predeductKeys.add(PreDeductService.slotKey(slot.getId()));
        }
        if (!predeductKeys.isEmpty()) {
            preDeduct.release(predeductKeys, 1);   // 阶段 17：归还预扣
        }
        UserTimeOccupancyDO occupancy = occupancyMapper.selectForUpdate(
                reservation.getUserId(), reservation.getSlotDate());
        if (occupancy != null) {
            BigInteger bits = BitmapUtil.fromHex(reservation.getTimeBitmap());
            BigInteger cleared = BitmapUtil.clear(BitmapUtil.fromHex(occupancy.getBitmap()), bits);
            occupancyMapper.updateBitmap(reservation.getUserId(), reservation.getSlotDate(),
                    BitmapUtil.toHex(cleared));
        }
        evictAvailability(reservation.getResourceId(), reservation.getSlotDate());
    }

    /** 阶段 16：时段可用性 + 概览的写后失效（事务提交后执行，避免"先删缓存后提交"的竞态） */
    private void evictAvailability(Long resourceId, LocalDate date) {
        CacheEvictions.afterCommit(() -> {
            cache.evict(CampusCacheKeys.slots(resourceId, date));
            cache.evict(CampusCacheKeys.overviewKey());
        });
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 单号（阶段 14 基因法，见 docs/04 §4.2）：{@code RES + 雪花ID + 6 位基因}，
     * 基因 = {@code user_id % 1_000_000} 左补零。
     *
     * <p>分片算法解析尾 6 位即可路由——"按单号查"与"按 user_id 查"命中同一分片
     * （1,000,000 整除 8，数学上恒等，见 {@link com.campus.framework.sharding.GeneUtil}）。</p>
     *
     * <p>单体期方案（RES + yyyyMMdd + Redis 全局序列 + 库内 MAX(id) 水位）已退役，
     * 见 docs/04 §4.5 对照素材；迁移脚本已将历史单号统一重写为本格式。</p>
     */
    private String nextReservationNo(Long userId) {
        return com.campus.framework.sharding.GeneUtil.reservationNo(idGenerator.nextLongId(), userId);
    }

    private MyReservationRespDTO toResp(ReservationDO r, ResourceDO resource) {
        MyReservationRespDTO dto = new MyReservationRespDTO();
        dto.setId(r.getId());
        dto.setReservationNo(r.getReservationNo());
        dto.setResourceId(r.getResourceId());
        dto.setResourceName(resource.getName());
        dto.setResourceLocation(resource.getLocation());
        dto.setCampus(resource.getCampus());
        dto.setResourceType(r.getResourceType());
        dto.setSlotDate(r.getSlotDate());
        dto.setStartTime(r.getStartTime());
        dto.setEndTime(r.getEndTime());
        dto.setStatus(r.getStatus());
        dto.setRemark(r.getRemark());
        dto.setCreateTime(LocalDateTime.now());
        return dto;
    }
}
