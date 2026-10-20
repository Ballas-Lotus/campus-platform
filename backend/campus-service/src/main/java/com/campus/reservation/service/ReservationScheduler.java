package com.campus.reservation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.framework.lock.CampusLock;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约定时结算器（每 60 秒，2026-10-08 业务的"违约"裁判）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li><b>爽约</b>：APPROVED 且无签到、已过"开始 + checkin-after-minutes"<br>
 *       → NO_SHOW：释放座位（别人可以约了）+ 用户禁约 24 小时（"第二天无法预约"）</li>
 *   <li><b>完成</b>：APPROVED 且已签到、时段已结束 → COMPLETED</li>
 * </ul>
 *
 * <p>查询命中 {@code idx_status_slotdate}（只扫近两天起的 APPROVED 单，量级小）。</p>
 *
 * <p><b>阶段 18 · 分布式锁</b>：本任务是 {@code @Scheduled}——多实例部署时每个实例都会触发，
 * 同一批数据被扫多遍（虽然结算方法自身幂等，但白白浪费数据库资源，还会让日志翻倍）。
 * 现在整轮结算包在分布式锁里：<b>同一时刻只有一个实例真正执行</b>，其余实例跳过本轮。</p>
 *
 * <p>锁不承担正确性（结算方法本身就是幂等的：非 APPROVED 直接返回）；
 * 它只是"少做无用功"的优化——这个定位很重要，见 docs/09 §3 的锁语义讨论。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;
    private final CampusLock campusLock;

    /** 结算任务锁：TTL 略小于调度周期，保证"上一轮没跑完时下一轮不会重入" */
    private static final String SETTLE_LOCK = "campus:lock:scheduler:settle";
    private static final long SETTLE_LOCK_TTL_MS = 55_000;

    @Value("${campus.reservation.checkin-after-minutes:15}")
    private long checkinAfterMinutes;

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void settle() {
        Boolean executed = campusLock.executeWithLock(SETTLE_LOCK, 0, SETTLE_LOCK_TTL_MS, () -> {
            doSettle();
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(executed)) {
            log.debug("[定时结算] 本轮由其它实例执行（分布式锁未获取），本实例跳过");
        }
    }

    private void doSettle() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate since = LocalDate.now().minusDays(1);
        List<ReservationDO> pendings = reservationMapper.selectList(
                Wrappers.lambdaQuery(ReservationDO.class)
                        .eq(ReservationDO::getStatus, "APPROVED")
                        .ge(ReservationDO::getSlotDate, since));
        int noshow = 0;
        int completed = 0;
        for (ReservationDO r : pendings) {
            LocalDateTime start = LocalDateTime.of(r.getSlotDate(), r.getStartTime());
            LocalDateTime end = LocalDateTime.of(r.getSlotDate(), r.getEndTime());
            if (r.getCheckinTime() != null) {
                if (end.isBefore(now)) {
                    reservationService.markCompleted(r.getId());
                    completed++;
                }
            } else if (now.isAfter(start.plusMinutes(checkinAfterMinutes))) {
                reservationService.markNoShow(r.getId());
                noshow++;
            }
        }
        if (noshow + completed > 0) {
            log.info("定时结算: 爽约 {} 单，完成 {} 单", noshow, completed);
        }
        autoProcessPending(now);
    }

    /**
     * 机器自动处理待审批单（2026-10-08 规则：取消人工审批）。
     *
     * <p>历史遗留的 PENDING（如种子数据）：时段未开始 → 自动通过（APPROVED）；
     * 时段已开始 → 自动驳回（CANCELLED，释放资源）。新预约在创建时即按规则自动生效，不再产生 PENDING。</p>
     */
    private void autoProcessPending(LocalDateTime now) {
        List<ReservationDO> pendings = reservationMapper.selectList(
                Wrappers.lambdaQuery(ReservationDO.class)
                        .eq(ReservationDO::getStatus, "PENDING")
                        .ge(ReservationDO::getSlotDate, LocalDate.now().minusDays(1)));
        if (pendings.isEmpty()) {
            return;
        }
        int approved = 0;
        int rejected = 0;
        for (ReservationDO r : pendings) {
            LocalDateTime start = LocalDateTime.of(r.getSlotDate(), r.getStartTime());
            if (start.isAfter(now)) {
                reservationService.autoApprove(r.getId());
                approved++;
            } else {
                reservationService.autoReject(r.getId());
                rejected++;
            }
        }
        log.info("自动处理待审批: 通过 {} 单，驳回（时段已开始） {} 单", approved, rejected);
    }
}
