package com.campus.user.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.exception.ClientException;
import com.campus.course.dao.mapper.CourseSelectionMapper;
import com.campus.course.dto.resp.MyCourseRespDTO;
import com.campus.framework.reservation.BitmapUtil;
import com.campus.framework.user.core.UserContext;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.entity.UserQuotaDO;
import com.campus.reservation.dao.entity.UserTimeOccupancyDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import com.campus.reservation.dao.mapper.UserQuotaMapper;
import com.campus.reservation.dao.mapper.UserTimeOccupancyMapper;
import com.campus.reservation.service.PenaltyService;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import com.campus.user.dao.entity.UserDO;
import com.campus.user.dao.mapper.UserMapper;
import com.campus.user.dto.req.PasswordChangeReqDTO;
import com.campus.user.dto.resp.ScheduleRespDTO;
import com.campus.user.dto.resp.UserProfileRespDTO;
import com.campus.user.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个人中心服务（阶段 10）。
 *
 * <p>三块能力：</p>
 * <ol>
 *   <li><b>资料 + 统计</b>：基本信息 + 预约按状态计数 + 学分汇总 + 场馆周用量 + 当前禁约状态；</li>
 *   <li><b>当日日程（位图反解）</b>：把 96 格位图解码成连续占用段，逐段匹配预约明细，
 *       叠加"当日课程"（按周几 + 节次时间表），合并按时间排序——预约与课表在同一时间轴上；</li>
 *   <li><b>修改密码</b>：原密码校验（BCrypt）→ 更新。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserMapper userMapper;
    private final ReservationMapper reservationMapper;
    private final UserTimeOccupancyMapper occupancyMapper;
    private final UserQuotaMapper quotaMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final ResourceMapper resourceMapper;
    private final PenaltyService penaltyService;

    @Value("${campus.reservation.venue-weekly-limit:4}")
    private int venueWeeklyLimit;

    /** 节次 → [开始, 结束] 时钟时间（与种子数据预览的节次换算一致） */
    private static final Map<Integer, LocalTime[]> SECTION_TIME = Map.of(
            1, new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(9, 40)},
            3, new LocalTime[]{LocalTime.of(10, 0), LocalTime.of(11, 40)},
            5, new LocalTime[]{LocalTime.of(14, 0), LocalTime.of(15, 40)},
            7, new LocalTime[]{LocalTime.of(16, 0), LocalTime.of(17, 40)},
            9, new LocalTime[]{LocalTime.of(19, 0), LocalTime.of(20, 40)});

    // ------------------------------------------------------------------
    // 资料 + 统计
    // ------------------------------------------------------------------

    public UserProfileRespDTO profile() {
        Long userId = UserContext.getUserId();
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new ClientException(UserErrorCode.USER_NOT_FOUND);
        }

        // ① 预约按状态计数
        Map<String, Long> byStatus = new HashMap<>();
        for (Map<String, Object> row : reservationMapper.countByStatus(userId)) {
            byStatus.put((String) row.get("status"), ((Number) row.get("n")).longValue());
        }
        long active = byStatus.getOrDefault("PENDING", 0L) + byStatus.getOrDefault("APPROVED", 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        // ② 选课汇总
        Map<String, Object> courseSum = courseSelectionMapper.mySummary(userId);
        long courseCount = courseSum == null || courseSum.get("cnt") == null
                ? 0 : ((Number) courseSum.get("cnt")).longValue();
        BigDecimal credits = courseSum == null || courseSum.get("credits") == null
                ? BigDecimal.ZERO : (BigDecimal) courseSum.get("credits");

        // ③ 场馆本周已用次数
        LocalDate weekStart = LocalDate.now().minusDays(LocalDate.now().getDayOfWeek().getValue() - 1L);
        UserQuotaDO quota = quotaMapper.selectOne(Wrappers.lambdaQuery(UserQuotaDO.class)
                .eq(UserQuotaDO::getUserId, userId)
                .eq(UserQuotaDO::getQuotaType, "VENUE_WEEKLY")
                .eq(UserQuotaDO::getPeriodStart, weekStart));
        int venueUsed = quota == null ? 0 : quota.getUsedTimes();

        // ④ 当前禁约状态（Redis）
        PenaltyService.Penalty penalty = penaltyService.get(userId);

        return UserProfileRespDTO.builder()
                .userId(user.getId())
                .studentNo(user.getStudentNo())
                .realName(user.getRealName())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .role(user.getRole())
                .phone(user.getPhone())
                .email(user.getEmail())
                .createTime(user.getCreateTime())
                .totalReservations(total)
                .activeReservations(active)
                .completed(byStatus.getOrDefault("COMPLETED", 0L))
                .cancelled(byStatus.getOrDefault("CANCELLED", 0L))
                .noShow(byStatus.getOrDefault("NO_SHOW", 0L))
                .courseCount(courseCount)
                .courseCredits(credits)
                .venueWeeklyUsed(venueUsed)
                .venueWeeklyLimit(venueWeeklyLimit)
                .penaltyUntil(penalty == null ? null : penalty.until())
                .penaltyReason(penalty == null ? null : penalty.reason())
                .build();
    }

    // ------------------------------------------------------------------
    // 当日日程（位图反解 + 当日课程）
    // ------------------------------------------------------------------

    public ScheduleRespDTO schedule(LocalDate date) {
        Long userId = UserContext.getUserId();
        LocalDate day = date == null ? LocalDate.now() : date;
        List<ScheduleRespDTO.ScheduleItemDTO> items = new ArrayList<>();

        // ① 当日有效预约（取消/爽约已清位图，不会进入反解；匹配明细用）
        List<ReservationDO> dayReservations = reservationMapper.selectList(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getUserId, userId)
                .eq(ReservationDO::getSlotDate, day)
                .in(ReservationDO::getStatus, "PENDING", "APPROVED", "COMPLETED"));
        Map<Long, ResourceDO> resources = new HashMap<>();
        List<Long> resourceIds = dayReservations.stream().map(ReservationDO::getResourceId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (!resourceIds.isEmpty()) {
            for (ResourceDO res : resourceMapper.selectByIds(resourceIds)) {
                resources.put(res.getId(), res);
            }
        }

        // ② 位图反解：连续占用段 → 逐段匹配预约明细
        UserTimeOccupancyDO occupancy = occupancyMapper.selectOne(Wrappers.lambdaQuery(UserTimeOccupancyDO.class)
                .eq(UserTimeOccupancyDO::getUserId, userId)
                .eq(UserTimeOccupancyDO::getOccupyDate, day));
        String bitmapHex = occupancy == null ? BitmapUtil.toHex(BigInteger.ZERO) : occupancy.getBitmap();
        if (occupancy != null) {
            for (int[] segment : BitmapUtil.decodeSegments(BitmapUtil.fromHex(occupancy.getBitmap()))) {
                LocalTime segStart = BitmapUtil.gridToTime(segment[0]);
                LocalTime segEnd = BitmapUtil.gridToTime(segment[1]);
                List<ReservationDO> hits = dayReservations.stream()
                        .filter(r -> r.getStartTime().isBefore(segEnd) && r.getEndTime().isAfter(segStart))
                        .collect(Collectors.toList());
                if (hits.isEmpty()) {
                    items.add(ScheduleRespDTO.ScheduleItemDTO.builder()
                            .type("RESERVATION").title("占用时段（明细缺失）")
                            .startTime(segStart).endTime(segEnd).build());
                } else {
                    for (ReservationDO r : hits) {
                        items.add(fromReservation(r, resources.get(r.getResourceId())));
                    }
                }
            }
        }

        // ③ 当日课程（周几匹配；节次 → 时钟时间）
        for (MyCourseRespDTO c : courseSelectionMapper.selectMyCourses(userId)) {
            if (c.getWeekDay() == null || c.getWeekDay() != day.getDayOfWeek().getValue()) {
                continue;
            }
            LocalTime[] time = SECTION_TIME.getOrDefault(c.getStartSection(), fallbackSectionTime(c.getStartSection()));
            items.add(ScheduleRespDTO.ScheduleItemDTO.builder()
                    .type("COURSE")
                    .title(c.getCourseName() + "（" + c.getClassName() + "）")
                    .location(c.getLocation())
                    .startTime(time[0])
                    .endTime(time[1])
                    .resourceType("COURSE")
                    .build());
        }

        items.sort(Comparator.comparing(ScheduleRespDTO.ScheduleItemDTO::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return ScheduleRespDTO.builder()
                .date(day)
                .bitmap(bitmapHex)
                .items(items)
                .build();
    }

    /** 非标准节次对的兜底时间（每节约 50 分钟含课间，近似展示用） */
    private LocalTime[] fallbackSectionTime(Integer startSection) {
        int s = startSection == null ? 1 : Math.max(1, startSection);
        LocalTime start = LocalTime.of(8, 0).plusMinutes((s - 1) * 50L);
        return new LocalTime[]{start, start.plusMinutes(95)};
    }

    private ScheduleRespDTO.ScheduleItemDTO fromReservation(ReservationDO r, ResourceDO resource) {
        String title = resource != null ? resource.getName() : switch (r.getResourceType() == null ? "" : r.getResourceType()) {
            case "LIBRARY_SEAT" -> "图书馆座位";
            case "VENUE" -> "体育场馆";
            case "LECTURE" -> "讲座";
            default -> "预约";
        };
        return ScheduleRespDTO.ScheduleItemDTO.builder()
                .type("RESERVATION")
                .title(title)
                .location(resource == null ? null : resource.getLocation())
                .startTime(r.getStartTime())
                .endTime(r.getEndTime())
                .status(r.getStatus())
                .resourceType(r.getResourceType())
                .reservationId(r.getId())
                .reservationNo(r.getReservationNo())
                .build();
    }

    // ------------------------------------------------------------------
    // 修改密码
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void changePassword(PasswordChangeReqDTO req) {
        Long userId = UserContext.getUserId();
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new ClientException(UserErrorCode.USER_NOT_FOUND);
        }
        if (!BCrypt.checkpw(req.getOldPassword(), user.getPassword())) {
            throw new ClientException(UserErrorCode.OLD_PASSWORD_WRONG);
        }
        if (req.getOldPassword().equals(req.getNewPassword())) {
            throw new ClientException(UserErrorCode.SAME_PASSWORD);
        }
        user.setPassword(BCrypt.hashpw(req.getNewPassword(), BCrypt.gensalt()));
        userMapper.updateById(user);
        log.info("修改密码成功: userId={} studentNo={}", userId, user.getStudentNo());
    }
}
