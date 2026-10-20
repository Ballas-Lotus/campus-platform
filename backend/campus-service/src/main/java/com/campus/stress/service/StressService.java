package com.campus.stress.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.entity.CourseSelectionDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dao.mapper.CourseSelectionMapper;
import com.campus.course.dto.req.CourseSelectReqDTO;
import com.campus.course.service.CourseService;
import com.campus.framework.concurrent.CampusThreadPools;
import com.campus.framework.reservation.BitmapUtil;
import com.campus.framework.user.core.UserContext;
import com.campus.framework.user.core.UserInfoDTO;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.entity.UserQuotaDO;
import com.campus.reservation.dao.entity.UserTimeOccupancyDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import com.campus.reservation.dao.mapper.UserQuotaMapper;
import com.campus.reservation.dao.mapper.UserTimeOccupancyMapper;
import com.campus.reservation.dto.req.ReservationCreateReqDTO;
import com.campus.reservation.service.ReservationService;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.entity.ResourceSlotDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import com.campus.resource.dao.mapper.ResourceSlotMapper;
import com.campus.stress.dao.mapper.StressMapper;
import com.campus.stress.dto.resp.StressResultRespDTO;
import com.campus.user.dao.entity.UserDO;
import com.campus.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * 并发压测服务（阶段 9 演示）。
 *
 * <p>四个场景（都是"同时发起 N 个真实事务请求"，打真实数据库行锁/唯一索引，测完自动清理）：</p>
 * <ol>
 *   <li><b>OVERSELL 防超卖</b>：100 个不同用户并发抢「压测专用场地」10 个名额 ——
 *       验证 {@code UPDATE ... WHERE booked < capacity} 原子扣减，成功数恰为 10；</li>
 *   <li><b>DUPLICATE 防重复</b>：同一用户并发提交同一预约 50 次 ——
 *       验证位图行锁 + uk_reserved 唯一索引，成功数恰为 1；</li>
 *   <li><b>BITMAP 位图冲突</b>：同一用户并发预约 50 个不同座位的**同一时段** ——
 *       验证位图冲突检测，成功数恰为 1；</li>
 *   <li><b>COURSE_OVERSELL 选课限额</b>：100 个用户并发抢「压测班」10 个名额 ——
 *       验证 {@code UPDATE ... WHERE selected < capacity}，成功数恰为 10。</li>
 * </ol>
 *
 * <p>每次压测后自动清理本轮写入（预约/选课/位图/配额），不会污染演示数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StressService {

    private final ReservationService reservationService;
    private final CourseService courseService;

    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper slotMapper;
    private final ReservationMapper reservationMapper;
    private final UserTimeOccupancyMapper occupancyMapper;
    private final UserQuotaMapper quotaMapper;
    private final UserMapper userMapper;
    private final StressMapper stressMapper;
    private final CourseClassMapper courseClassMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /** 阶段 14：Sentinel 限流演示——经 Controller 代理调用，触发与真实 HTTP 请求同一条限流链路 */
    private final com.campus.course.controller.CourseController courseController;
    private final com.campus.sentinel.SentinelFlowConfig sentinelFlowConfig;
    private final com.campus.user.dao.mapper.UserStudentNoMapper userStudentNoMapper;

    /** 阶段 17/18：预扣闸门 + 闸门方法（幂等注解挂在闸门上） */
    private final com.campus.prededuct.CourseSelectGate courseSelectGate;
    private final com.campus.prededuct.ReservationGate reservationGate;
    private final com.campus.prededuct.PreDeductService preDeductService;
    /** 阶段 19：教学班余量读模型（夹具重置时一并失效） */
    private final com.campus.cache.ClassStockCache classStockCache;
    /** 阶段 21：受理制结构与台账（齐射收敛等待 + 夹具重置） */
    private final com.campus.stock.CourseStockService courseStockService;
    /** 阶段 18：线程池注册表（具名有界池）与锁竞争演示 */
    private final CampusThreadPools threadPools;
    private final com.campus.resilience.LockDemoService lockDemoService;

    /** 压测专用场地（migrate-stress-fixtures.sql） */
    private static final Long STRESS_RESOURCE_ID = 90001L;
    /** 压测班（migrate-stress-fixtures.sql） */
    private static final Long STRESS_CLASS_ID = 9999L;
    /** 压测机器人（migrate-stress-fixtures.sql） */
    private static final String STRESS_BOT_STUDENT_NO = "stress01";
    /** 专用场地名额（= 片的 capacity） */
    private static final int SLOT_CAPACITY = 10;
    /** 体育馆每周限约次数（与 ReservationService 配置一致） */
    private static final int VENUE_WEEKLY_LIMIT = 4;
    /** 压测时段：明天 20:00~21:00（在"当天与明天"窗口内） */
    private static final LocalTime SLOT_START = LocalTime.of(20, 0);
    private static final LocalTime SLOT_END = LocalTime.of(21, 0);
    /** 单次 run 的等待上限 */
    private static final int AWAIT_SECONDS = 120;
    /** 压测线程池上限（需 ≥ 并发数，保证栅栏齐射；100 并发即 100 线程） */
    private static final int MAX_POOL = 128;
    /** 阶段 17：预扣场景的最大并发（1000 线程齐射，≈256MB 栈内存） */
    private static final int MAX_CONCURRENCY = 1000;
    /** 任务就位等待上限（线程创建很快，正常瞬时完成） */
    private static final int READY_SECONDS = 5;
    /** 大并发场景的就位等待上限（1000 线程创建需要更长时间） */
    private static final int READY_SECONDS_LARGE = 20;

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    public StressResultRespDTO run(String scenario, Integer threads) {
        if (scenario == null) {
            throw new ClientException(BaseErrorCode.PARAM_INVALID);
        }
        return switch (scenario.toUpperCase()) {
            case "OVERSELL" -> oversell(threads == null ? 100 : threads);
            case "DUPLICATE" -> duplicate(threads == null ? 50 : threads);
            case "BITMAP" -> bitmap(threads == null ? 50 : threads);
            case "COURSE_OVERSELL" -> courseOversell(threads == null ? 100 : threads);
            case "RATE_LIMIT" -> rateLimit(threads == null ? 120 : threads);
            case "PREDEDUCT" -> preDeductScenario(threads == null ? 1000 : threads);
            case "IDEMPOTENT" -> idempotentScenario(threads == null ? 100 : threads);
            case "LOCK" -> lockScenario(threads == null ? 100 : threads);
            default -> throw new ClientException("未知压测场景: " + scenario, BaseErrorCode.PARAM_INVALID);
        };
    }

    // ------------------------------------------------------------------
    // 场景 1：防超卖（100 人抢 10 个名额）
    // ------------------------------------------------------------------

    private StressResultRespDTO oversell(int n) {
        LocalDate date = LocalDate.now().plusDays(1);
        ensureStressSlot(STRESS_RESOURCE_ID, date, SLOT_START, SLOT_END, SLOT_CAPACITY, 1);
        purgeSlotReservations(date, SLOT_START);   // 清上一轮残留

        List<Long> racers = pickFreeVenueRacers(date, n);
        Map<Long, Integer> priorQuota = snapshotVenueQuota(racers);
        int capacity = SLOT_CAPACITY;

        Attempts at = fire(racers.size(), idx -> asUser(racers.get(idx), () ->
                reservationService.create(req(STRESS_RESOURCE_ID, date, SLOT_START, SLOT_END))));

        int booked = slotBooked(STRESS_RESOURCE_ID, date, SLOT_START);
        long activeCount = activeReservations(STRESS_RESOURCE_ID, date, SLOT_START);

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("成功数 = 名额数（不多不少）", String.valueOf(capacity), String.valueOf(at.successCount())),
                assertion("片 booked = 名额数（无超卖）", String.valueOf(capacity), String.valueOf(booked)),
                assertion("数据自洽：片 booked = 该片有效预约数", String.valueOf(activeCount), String.valueOf(booked)),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "专用场地容量", capacity,
                "参与抢单人数", racers.size(),
                "预约时段", date + " " + SLOT_START + "~" + SLOT_END,
                "片 booked（清理前）", booked);

        // 清理：删本轮预约 + 位图重算 + 片归零 + 配额恢复
        List<Long> affected = purgeSlotReservations(date, SLOT_START);
        restoreVenueQuota(affected.isEmpty() ? racers : affected, priorQuota);
        detail = new HashMap<>(detail);
        detail.put("片 booked（已清理）", slotBooked(STRESS_RESOURCE_ID, date, SLOT_START));

        return build("OVERSELL", "防超卖 · " + racers.size() + " 人并发抢 " + capacity + " 个名额", at, asserts, detail);
    }

    // ------------------------------------------------------------------
    // 场景 2：防重复（同一用户并发提交同一预约）
    // ------------------------------------------------------------------

    private StressResultRespDTO duplicate(int n) {
        LocalDate date = LocalDate.now().plusDays(1);
        Long botId = stressBotId();
        reservationService.resetForTestUser(botId);           // 机器人清干净
        ensureStressSlot(STRESS_RESOURCE_ID, date, SLOT_START, SLOT_END, SLOT_CAPACITY, 1);
        purgeSlotReservations(date, SLOT_START);

        Attempts at = fire(n, idx -> asUser(botId, () ->
                reservationService.create(req(STRESS_RESOURCE_ID, date, SLOT_START, SLOT_END))));

        int booked = slotBooked(STRESS_RESOURCE_ID, date, SLOT_START);
        long activeCount = activeReservations(STRESS_RESOURCE_ID, date, SLOT_START);

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("成功数 = 1（并发重复提交只生效一次）", "1", String.valueOf(at.successCount())),
                assertion("片 booked = 1", "1", String.valueOf(booked)),
                assertion("有效预约记录 = 1（唯一索引兜底）", "1", String.valueOf(activeCount)),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "同一请求并发提交", n + " 次",
                "预约时段", date + " " + SLOT_START + "~" + SLOT_END,
                "结果", "1 次成功，其余被位图冲突/唯一索引拒绝");

        reservationService.resetForTestUser(botId);           // 清理本轮写入
        zeroSlot(date, SLOT_START);

        return build("DUPLICATE", "防重复 · 同一预约并发提交 " + n + " 次", at, asserts, detail);
    }

    // ------------------------------------------------------------------
    // 场景 3：位图冲突（同一用户并发预约不同座位的同一时段）
    // ------------------------------------------------------------------

    private StressResultRespDTO bitmap(int n) {
        LocalDate date = LocalDate.now().plusDays(1);
        Long botId = stressBotId();
        reservationService.resetForTestUser(botId);

        // 取 n 个图书馆座位（id 最小的若干），确保明日 20:00 片存在（容量 1）
        List<Long> seatIds = resourceMapper.selectList(Wrappers.lambdaQuery(ResourceDO.class)
                        .eq(ResourceDO::getResourceType, "LIBRARY_SEAT")
                        .eq(ResourceDO::getStatus, 1)
                        .orderByAsc(ResourceDO::getId)
                        .last("LIMIT " + n))
                .stream().map(ResourceDO::getId).collect(Collectors.toList());
        for (Long seatId : seatIds) {
            ensureStressSlot(seatId, date, SLOT_START, SLOT_END, 1, 1);
        }

        Attempts at = fire(seatIds.size(), idx -> asUser(botId, () ->
                reservationService.create(req(seatIds.get(idx), date, SLOT_START, SLOT_END))));

        // 机器人当日位图（应恰好等于该时段的独立 bits）
        UserTimeOccupancyDO occ = occupancyMapper.selectOne(Wrappers.lambdaQuery(UserTimeOccupancyDO.class)
                .eq(UserTimeOccupancyDO::getUserId, botId)
                .eq(UserTimeOccupancyDO::getOccupyDate, date));
        String occHex = occ == null ? "（无）" : occ.getBitmap();
        String wantHex = BitmapUtil.toHex(BitmapUtil.toBitmap(SLOT_START, SLOT_END));

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("成功数 = 1（同一时段只能占一个座位）", "1", String.valueOf(at.successCount())),
                assertion("当日位图 = 该时段 bits（无丢更新）", wantHex, occHex),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "并发抢座", seatIds.size() + " 个不同座位的同一时段",
                "用户", "压测机器人（同一账号）",
                "预约时段", date + " " + SLOT_START + "~" + SLOT_END);

        reservationService.resetForTestUser(botId);           // 清理：释放座位 + 清位图

        return build("BITMAP", "位图冲突 · 同一用户并发抢 " + seatIds.size() + " 个座位（同一时段）", at, asserts, detail);
    }

    // ------------------------------------------------------------------
    // 场景 4：选课限额（100 人抢 10 个课额）
    // ------------------------------------------------------------------

    private StressResultRespDTO courseOversell(int n) {
        purgeStressClass();                                    // 清上一轮
        clearIdempotentKeys();

        List<Long> racers = stressMapper.pickCourseRacers(n);
        // 阶段 21：受理链路 = 立即返回受理结果，落库异步——断言前先等台账收敛（选课记录确实落库）
        long conv0 = System.currentTimeMillis();
        Attempts at = fire(racers.size(), idx -> asUser(racers.get(idx), () -> {
            CourseSelectReqDTO req = new CourseSelectReqDTO();
            req.setClassId(STRESS_CLASS_ID);
            // 阶段 17~21：走闸门（受理制 = ACCEPT 原子 + MQ 异步落库），与真实 HTTP 入口同一条链路
            courseSelectGate.select(req);
        }));
        long acceptMs = System.currentTimeMillis() - conv0;
        CourseClassDO probe = courseClassMapper.selectById(STRESS_CLASS_ID);
        Long convCourseId = probe == null ? null : probe.getCourseId();
        long convergedMs = awaitPendingDrained(convCourseId);

        CourseClassDO cls = courseClassMapper.selectById(STRESS_CLASS_ID);
        int selected = cls == null ? -1 : cls.getSelected();
        int capacity = cls == null ? -1 : cls.getCapacity();
        Long rows = courseSelectionMapper.selectCount(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getClassId, STRESS_CLASS_ID)
                .eq(CourseSelectionDO::getStatus, "SELECTED"));

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("受理成功数 = 名额数（不多不少）", String.valueOf(capacity), String.valueOf(at.successCount())),
                assertion("教学班 selected = 名额数（无超卖）", String.valueOf(capacity), String.valueOf(selected)),
                assertion("数据自洽：selected = 选课记录数", String.valueOf(rows), String.valueOf(selected)),
                assertion("受理台账已收敛（pending = 0）", "0",
                        String.valueOf(convCourseId == null ? -1 : courseStockService.pendingTotal(convCourseId))),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "教学班容量", capacity,
                "参与抢课人数", racers.size(),
                "教学班", "并发压测演练课 · 压测班（周日 9-10 节）",
                "齐射受理耗时", acceptMs + "ms",
                "落库收敛耗时", convergedMs + "ms",
                "口径", "阶段 21 受理制：成功 = 已受理（ACCEPT 原子），随后 MQ 异步落库");

        purgeStressClass();                                    // 清理本轮写入

        return build("COURSE_OVERSELL", "受理制 · " + racers.size() + " 人并发抢 " + capacity
                + " 个课额（受理 " + acceptMs + "ms + 落库收敛 " + convergedMs + "ms）", at, asserts, detail);
    }

    /** 等待该课程的受理台账归零（落库收敛）；返回耗时 ms；超时 15s 返回 -1 */
    private long awaitPendingDrained(Long courseId) {
        if (courseId == null) {
            return -1;
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 15_000) {
            if (courseStockService.pendingTotal(courseId) == 0) {
                return System.currentTimeMillis() - t0;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 场景 5：Sentinel 限流（选课接口抢课高峰削峰）
    // ------------------------------------------------------------------

    /**
     * 选课接口限流演示（阶段 14）：n 个用户并发抢压测班，
     * 请求经 {@code CourseController}（Spring 代理）触发 {@code @SentinelResource} 切面——
     * 与真实 HTTP 请求走同一条限流链路（QPS 阈值见 {@code campus.sentinel.course-select-qps}）。
     *
     * <p>预期：超过 QPS 阈值的请求被快速失败（40055 抢课高峰提示），
     * 放行部分继续走"防超卖"闸门（成功数 ≤ 名额数）——限流削峰与数据正确性叠加验证。</p>
     */
    private StressResultRespDTO rateLimit(int n) {
        purgeStressClass();
        clearIdempotentKeys();

        List<Long> racers = stressMapper.pickCourseRacers(n);
        Attempts at = fire(racers.size(), idx -> asUser(racers.get(idx), () -> {
            CourseSelectReqDTO req = new CourseSelectReqDTO();
            req.setClassId(STRESS_CLASS_ID);
            com.campus.common.result.Result<com.campus.course.dto.resp.MyCourseRespDTO> result =
                    courseController.select(req);
            if (result.getCode() != null && result.getCode() != 0) {
                throw new ClientException(result.getMessage(),
                        new DynamicErrorCode(result.getCode(), result.getMessage()));
            }
        }));

        CourseClassDO cls = courseClassMapper.selectById(STRESS_CLASS_ID);
        int capacity = cls == null ? -1 : cls.getCapacity();
        String flowCode = String.valueOf(sentinelFlowConfig.errorCode());
        long blocked = at.list().stream().filter(a -> !a.success() && flowCode.equals(a.code())).count();

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("Sentinel 限流生效（拦截数 > 0）", blocked > 0, ">0", String.valueOf(blocked)),
                assertion("防超卖仍成立（成功数 ≤ 名额数）", at.successCount() <= capacity,
                        "≤" + capacity, String.valueOf(at.successCount())),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "限流资源", sentinelFlowConfig.resourceName(),
                "QPS 阈值", sentinelFlowConfig.qpsLimit(),
                "并发请求", racers.size(),
                "结果", blocked + " 被限流快速失败，放行部分按「先到先得」抢 " + capacity + " 个名额");

        purgeStressClass();
        return build("RATE_LIMIT", "Sentinel 限流 · " + racers.size() + " 人并发抢课（QPS 阈值 "
                + sentinelFlowConfig.qpsLimit() + "）", at, asserts, detail);
    }

    /** 把业务 Result 的错误码包装为可被 fire() 统计的异常（错误码原样透传） */
    private record DynamicErrorCode(Integer code, String message) implements com.campus.common.errorcode.IErrorCode {
    }

    // ------------------------------------------------------------------
    // 场景 6：预扣闸门挡量（阶段 17）
    // ------------------------------------------------------------------

    /**
     * 预扣闸门 · n 人并发抢压测班的名额。
     *
     * <p>与「选课限额」场景的差别：那条走的是"数据库条件更新"这一层；
     * 这条走的是<b>Redis 预扣闸门</b>——预期绝大多数请求在 Redis 就被挡回（不占数据库连接），
     * 只有等于名额数的请求真正落到数据库。现场可见三组数字：拦截 / 放行 / DB 写入。</p>
     */
    private StressResultRespDTO preDeductScenario(int n) {
        int concurrency = Math.max(1, Math.min(n, MAX_CONCURRENCY));
        purgeStressClass();                                    // 清 DB + 丢弃旧计数器
        clearIdempotentKeys();                                 // 清上一轮幂等残留

        List<Long> racers = stressMapper.pickCourseRacers(concurrency);
        Map<String, Object> before = preDeductService.metrics();
        long intercepted0 = num(before, "intercepted");
        long passed0 = num(before, "passed");
        long rolled0 = num(before, "rolledBack");

        Attempts at = fireLarge(racers.size(), idx -> asUser(racers.get(idx), () -> {
            CourseSelectReqDTO req = new CourseSelectReqDTO();
            req.setClassId(STRESS_CLASS_ID);
            // 本场景演示"预扣闸门"本身（阶段 17）：受理制默认开启时闸门指标由老链路维护，
            // 因此显式走同步老链路——"受理制齐射"请看 COURSE_OVERSELL 场景
            courseSelectGate.selectLegacy(req);
        }));

        Map<String, Object> after = preDeductService.metrics();
        long intercepted = num(after, "intercepted") - intercepted0;
        long passedToDb = num(after, "passed") - passed0;
        long rolledBack = num(after, "rolledBack") - rolled0;

        CourseClassDO cls = courseClassMapper.selectById(STRESS_CLASS_ID);
        int capacity = cls == null ? -1 : cls.getCapacity();
        int selected = cls == null ? -1 : cls.getSelected();
        Long rows = courseSelectionMapper.selectCount(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getClassId, STRESS_CLASS_ID)
                .eq(CourseSelectionDO::getStatus, "SELECTED"));
        int total = racers.size();

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("成功数 = 名额数（不多不少）", String.valueOf(capacity), String.valueOf(at.successCount())),
                assertion("放行到数据库 = 名额数（Redis 精确挡量）", String.valueOf(capacity), String.valueOf(passedToDb)),
                assertion("Redis 拦截 = 请求数 - 名额数（未占数据库连接）",
                        String.valueOf(total - capacity), String.valueOf(intercepted)),
                assertion("教学班 selected = 名额数（无超卖）", String.valueOf(capacity), String.valueOf(selected)),
                assertion("数据自洽：selected = 选课记录数", String.valueOf(rows), String.valueOf(selected)),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "并发请求", total,
                "Redis 拦截（未查库/未占连接）", intercepted,
                "放行到数据库", passedToDb,
                "DB 拒绝后回补预扣", rolledBack,
                "对比", "改造前这 " + total + " 个请求会全部打到数据库（连接池 128 先被打爆）");

        purgeStressClass();
        return build("PREDEDUCT", "预扣闸门 · " + total + " 人并发抢 " + capacity + " 个课额（Redis 先挡 "
                + intercepted + "，数据库只写 " + capacity + " 行）", at, asserts, detail);
    }

    // ------------------------------------------------------------------
    // 场景 7：接口幂等挡重复提交（阶段 18）
    // ------------------------------------------------------------------

    /**
     * 幂等 · 同一用户并发提交同一笔预约 n 次。
     *
     * <p>预期：<b>1 次成功、其余被 Redis 幂等（两态 Lua）拦截（40060）</b>——
     * 与「防重复」场景互补：那条验证"幂等失效时数据库也不重复"（唯一索引兜底），
     * 这条验证"正常情况下重复请求在入口就被挡掉，根本不进业务"。</p>
     */
    private StressResultRespDTO idempotentScenario(int n) {
        LocalDate date = LocalDate.now().plusDays(1);
        Long botId = stressBotId();
        reservationService.resetForTestUser(botId);
        ensureStressSlot(STRESS_RESOURCE_ID, date, SLOT_START, SLOT_END, SLOT_CAPACITY, 1);
        purgeSlotReservations(date, SLOT_START);
        clearIdempotentKeys();                                 // 清上一轮幂等残留（否则首笔也被挡）

        Attempts at = fire(n, idx -> asUser(botId, () ->
                reservationGate.create(req(STRESS_RESOURCE_ID, date, SLOT_START, SLOT_END))));

        int booked = slotBooked(STRESS_RESOURCE_ID, date, SLOT_START);
        long active = activeReservations(STRESS_RESOURCE_ID, date, SLOT_START);
        long idemBlocked = at.list().stream()
                .filter(a -> !a.success() && "40060".equals(a.code())).count();

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("成功数 = 1（并发重复提交只生效一次）", "1", String.valueOf(at.successCount())),
                assertion("幂等拦截（40060）> 0", idemBlocked > 0, ">0", String.valueOf(idemBlocked)),
                assertion("片 booked = 1", "1", String.valueOf(booked)),
                assertion("有效预约记录 = 1", "1", String.valueOf(active)),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "同一用户并发提交", n + " 次",
                "幂等键", "用户 + 资源 + 日期 + 时段（窗口 5 秒）",
                "结果", idemBlocked + " 次被幂等拦截（40060），只有首笔进入业务");

        reservationService.resetForTestUser(botId);
        zeroSlot(date, SLOT_START);
        dropStressSlotCounters(date);

        return build("IDEMPOTENT", "接口幂等 · 同一预约并发提交 " + n + " 次", at, asserts, detail);
    }

    // ------------------------------------------------------------------
    // 场景 8：分布式锁竞争（阶段 18）
    // ------------------------------------------------------------------

    /**
     * 分布式锁 · n 个线程同时抢同一把锁（临界区内做共享计数）。
     *
     * <p>核心断言是 <b>互斥性违例 = 0</b>（任意时刻临界区内至多 1 个线程）——
     * 这比"看起来没出问题"更硬；同时演示"只有一个线程能进"的排队语义。</p>
     */
    private StressResultRespDTO lockScenario(int n) {
        Map<String, Object> demo = lockDemoService.run(n, 0);
        int entered = ((Number) demo.get("entered")).intValue();
        int timedOut = ((Number) demo.get("timedOut")).intValue();
        int violations = ((Number) demo.get("violations")).intValue();
        long wall = ((Number) demo.get("wallMs")).longValue();

        List<Long> times = demo.get("times") instanceof List<?> l
                ? l.stream().map(x -> ((Number) x).longValue()).collect(Collectors.toList())
                : List.of();
        List<Attempt> list = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < entered; i++) {
            list.add(new Attempt(true, "0", msAt(times, idx++)));
        }
        for (int i = 0; i < timedOut; i++) {
            list.add(new Attempt(false, "LOCK_NOT_ACQUIRED", msAt(times, idx++)));
        }
        Attempts at = new Attempts(list, wall);

        List<StressResultRespDTO.Assertion> asserts = List.of(
                assertion("互斥性违例 = 0（锁真的生效）", "0", String.valueOf(violations)),
                assertion("进入临界区 = 1（同一时刻只有一个持锁者）", "1", String.valueOf(entered)),
                noSystemError(at));

        Map<String, Object> detail = Map.of(
                "并发线程", n,
                "进入临界区", entered,
                "未抢到锁（立即放弃）", timedOut,
                "互斥性违例", violations,
                "锁实现", "SET NX PX + Lua 校验删除（campus:lock:demo:counter）");

        return build("LOCK", "分布式锁 · " + n + " 线程同抢一把锁", at, asserts, detail);
    }

    private long num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number num ? num.longValue() : 0L;
    }

    private long msAt(List<Long> times, int index) {
        return index < times.size() ? times.get(index) : 0L;
    }

    // ------------------------------------------------------------------
    // 并发执行器
    // ------------------------------------------------------------------

    private void asUser(Long userId, Runnable action) {
        UserContext.setUser(UserInfoDTO.builder().userId(userId).build());
        try {
            action.run();
        } finally {
            UserContext.removeUser();
        }
    }

    /**
     * 同时发起 n 个任务（栅栏对齐后齐射），收集每个请求的成败/错误码/耗时。
     *
     * <p>池大小 ≥ n，保证所有任务都能先"就位"（否则栅栏等不齐 → 空等到超时才开闸，
     * 白白拉长总耗时；曾因此触发前端 10s 请求超时导致"经常失败"——2026-10-08 修复）。</p>
     */
    private Attempts fire(int n, IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, MAX_POOL));
        try {
            return fireOn(n, task, pool, READY_SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 大并发齐射（阶段 17）：复用线程池注册表里的具名有界池（池大小 = 并发数，SynchronousQueue 不排队），
     * 并放宽"就位"等待上限（1000 线程创建需要时间）。池不在此关闭——由注册表统一管理，可观测。
     */
    private Attempts fireLarge(int n, IntConsumer task) {
        return fireOn(n, task, threadPools.stressPool(n), READY_SECONDS_LARGE);
    }

    private Attempts fireOn(int n, IntConsumer task, ExecutorService pool, int readySeconds) {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<Attempt> list = Collections.synchronizedList(new ArrayList<>(n));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.execute(() -> {
                ready.countDown();
                try {
                    go.await();
                    long t0 = System.nanoTime();
                    try {
                        task.accept(idx);
                        list.add(new Attempt(true, "0", (System.nanoTime() - t0) / 1_000_000));
                    } catch (ClientException ce) {
                        list.add(new Attempt(false, String.valueOf(ce.errorCode), (System.nanoTime() - t0) / 1_000_000));
                    } catch (Exception ex) {
                        list.add(new Attempt(false, "EX:" + ex.getClass().getSimpleName(), (System.nanoTime() - t0) / 1_000_000));
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long wall0;
        try {
            ready.await(readySeconds, TimeUnit.SECONDS);
            wall0 = System.currentTimeMillis();   // 总耗时 = 齐射开始 → 全部返回
            go.countDown();
            done.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            wall0 = System.currentTimeMillis();
        }
        return new Attempts(list, System.currentTimeMillis() - wall0);
    }

    private StressResultRespDTO build(String scenario, String title, Attempts at,
                                      List<StressResultRespDTO.Assertion> asserts, Map<String, Object> detail) {
        List<Long> times = at.list().stream().map(Attempt::ms).sorted().collect(Collectors.toList());
        Map<String, Integer> failures = new HashMap<>();
        for (Attempt a : at.list()) {
            if (!a.success()) {
                failures.merge(a.code(), 1, Integer::sum);
            }
        }
        boolean allPass = asserts.stream().allMatch(StressResultRespDTO.Assertion::isPass);
        log.info("[压测] {} → {}（{}/{} 成功，耗时 {}ms）", scenario, allPass ? "PASS" : "FAIL",
                at.successCount(), at.list().size(), at.wallMs());
        return StressResultRespDTO.builder()
                .scenario(scenario)
                .title(title)
                .conclusion(allPass ? "PASS" : "FAIL")
                .requests(at.list().size())
                .success(at.successCount())
                .durationMs(at.wallMs())
                .failures(failures)
                .p50Ms(percentile(times, 0.50))
                .p95Ms(percentile(times, 0.95))
                .maxMs(times.isEmpty() ? 0 : times.get(times.size() - 1))
                .assertions(asserts)
                .detail(detail)
                .build();
    }

    private double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private StressResultRespDTO.Assertion assertion(String name, String expected, String actual) {
        return StressResultRespDTO.Assertion.builder()
                .name(name)
                .expected(expected)
                .actual(actual)
                .pass(expected.equals(actual))
                .build();
    }

    /** 布尔断言（区间/不等式的"现场判定"，如"拦截数 > 0"） */
    private StressResultRespDTO.Assertion assertion(String name, boolean pass, String expected, String actual) {
        return StressResultRespDTO.Assertion.builder()
                .name(name)
                .expected(expected)
                .actual(actual)
                .pass(pass)
                .build();
    }

    /** 质量断言：失败必须全是业务拒绝（4xxxx），不允许系统异常（EX:... / 5xxxx）。 */
    private StressResultRespDTO.Assertion noSystemError(Attempts at) {
        long sys = at.list().stream()
                .filter(a -> !a.success())
                .filter(a -> a.code().startsWith("EX:") || a.code().startsWith("5"))
                .count();
        return assertion("无系统异常（失败均为业务拒绝）", "0", String.valueOf(sys));
    }

    // ------------------------------------------------------------------
    // 夹具 & 数据核对
    // ------------------------------------------------------------------

    private Long stressBotId() {
        // 阶段 14：t_user 按 user_id 分片 → 学号查询走索引表（读扩散），再按分片键回查
        com.campus.user.dao.entity.UserStudentNoDO index = userStudentNoMapper.selectById(STRESS_BOT_STUDENT_NO);
        if (index == null) {
            throw new ClientException("压测夹具缺失：请先执行 migrate-stress-fixtures.sql", BaseErrorCode.NOT_FOUND);
        }
        return index.getUserId();
    }

    private ResourceSlotDO ensureStressSlot(Long resourceId, LocalDate date, LocalTime start,
                                            LocalTime end, int capacity, int status) {
        ResourceSlotDO slot = slotMapper.selectOne(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getResourceId, resourceId)
                .eq(ResourceSlotDO::getSlotDate, date)
                .eq(ResourceSlotDO::getStartTime, start));
        if (slot != null) {
            return slot;
        }
        ResourceSlotDO neu = new ResourceSlotDO();
        neu.setResourceId(resourceId);
        neu.setSlotDate(date);
        neu.setStartTime(start);
        neu.setEndTime(end);
        neu.setCapacity(capacity);
        neu.setBooked(0);
        neu.setVersion(0);
        neu.setStatus(status);
        try {
            slotMapper.insert(neu);
        } catch (DuplicateKeyException e) {
            return slotMapper.selectOne(Wrappers.lambdaQuery(ResourceSlotDO.class)
                    .eq(ResourceSlotDO::getResourceId, resourceId)
                    .eq(ResourceSlotDO::getSlotDate, date)
                    .eq(ResourceSlotDO::getStartTime, start));
        }
        return neu;
    }

    /**
     * 抢单候选：真实学生中"该时段位图无占用 + 无惩罚 + 周配额未满"的用户。
     *
     * <p>阶段 14：候选 + 配额改为两次单表查询（原先的 LEFT JOIN 在分库分表下
     * 无法保证同分片联结，见 StressMapper 注释）。</p>
     */
    private List<Long> pickFreeVenueRacers(LocalDate date, int n) {
        LocalDate weekStart = weekStart();
        List<Long> pool = stressMapper.pickVenueCandidates(Math.max(n * 4, 200));
        if (pool.isEmpty()) {
            return pool;
        }
        // 第二步：批量取候选用户的本周场馆配额，服务层过滤"已用满"的用户
        Map<Long, Integer> usedMap = new java.util.HashMap<>();
        for (Map<String, Object> row : stressMapper.selectVenueQuotaIn(weekStart, pool)) {
            usedMap.put(((Number) row.get("userId")).longValue(), ((Number) row.get("usedTimes")).intValue());
        }
        pool.removeIf(uid -> usedMap.getOrDefault(uid, 0) >= VENUE_WEEKLY_LIMIT);
        Map<Long, String> occupancy = occupancyMapper.selectList(Wrappers.lambdaQuery(UserTimeOccupancyDO.class)
                        .eq(UserTimeOccupancyDO::getOccupyDate, date)
                        .in(UserTimeOccupancyDO::getUserId, pool))
                .stream().collect(Collectors.toMap(UserTimeOccupancyDO::getUserId, UserTimeOccupancyDO::getBitmap));
        BigInteger want = BitmapUtil.toBitmap(SLOT_START, SLOT_END);
        List<Long> out = new ArrayList<>();
        for (Long uid : pool) {
            String hex = occupancy.get(uid);
            if (hex != null && BitmapUtil.intersects(BitmapUtil.fromHex(hex), want)) {
                continue;
            }
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey("campus:penalty:" + uid))) {
                continue;
            }
            out.add(uid);
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    /** 删除专用片上的全部预约（含上一轮残留）并重算受影响用户位图；片 booked 归零。返回受影响用户。 */
    private List<Long> purgeSlotReservations(LocalDate date, LocalTime start) {
        List<ReservationDO> rows = reservationMapper.selectList(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getResourceId, STRESS_RESOURCE_ID)
                .eq(ReservationDO::getSlotDate, date)
                .eq(ReservationDO::getStartTime, start));
        List<Long> userIds = rows.stream().map(ReservationDO::getUserId).distinct().collect(Collectors.toList());
        if (!rows.isEmpty()) {
            reservationMapper.delete(Wrappers.lambdaQuery(ReservationDO.class)
                    .eq(ReservationDO::getResourceId, STRESS_RESOURCE_ID)
                    .eq(ReservationDO::getSlotDate, date)
                    .eq(ReservationDO::getStartTime, start));
            userIds.forEach(uid -> recomputeOccupancy(uid, date));
        }
        zeroSlot(date, start);
        dropStressSlotCounters(date);   // 阶段 17：库已归零 → 计数器一并丢弃（下次按真值重建）
        return userIds;
    }

    private void zeroSlot(LocalDate date, LocalTime start) {
        slotMapper.update(null, Wrappers.lambdaUpdate(ResourceSlotDO.class)
                .setSql("booked = 0, version = version + 1")
                .eq(ResourceSlotDO::getResourceId, STRESS_RESOURCE_ID)
                .eq(ResourceSlotDO::getSlotDate, date)
                .eq(ResourceSlotDO::getStartTime, start));
    }

    /** 位图重算：= 该用户当日全部有效预约位图的 OR（无则删行）——清理压测写入用。 */
    private void recomputeOccupancy(Long userId, LocalDate date) {
        List<ReservationDO> active = reservationMapper.selectList(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getUserId, userId)
                .eq(ReservationDO::getSlotDate, date)
                .in(ReservationDO::getStatus, "PENDING", "APPROVED"));
        BigInteger bits = BigInteger.ZERO;
        for (ReservationDO r : active) {
            bits = BitmapUtil.merge(bits, BitmapUtil.fromHex(r.getTimeBitmap()));
        }
        if (bits.signum() == 0) {
            occupancyMapper.delete(Wrappers.lambdaQuery(UserTimeOccupancyDO.class)
                    .eq(UserTimeOccupancyDO::getUserId, userId)
                    .eq(UserTimeOccupancyDO::getOccupyDate, date));
        } else {
            occupancyMapper.updateBitmap(userId, date, BitmapUtil.toHex(bits));
        }
    }

    private int slotBooked(Long resourceId, LocalDate date, LocalTime start) {
        ResourceSlotDO slot = slotMapper.selectOne(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getResourceId, resourceId)
                .eq(ResourceSlotDO::getSlotDate, date)
                .eq(ResourceSlotDO::getStartTime, start));
        return slot == null ? -1 : slot.getBooked();
    }

    private long activeReservations(Long resourceId, LocalDate date, LocalTime start) {
        return reservationMapper.selectCount(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getResourceId, resourceId)
                .eq(ReservationDO::getSlotDate, date)
                .eq(ReservationDO::getStartTime, start)
                .in(ReservationDO::getStatus, "PENDING", "APPROVED"));
    }

    private Map<Long, Integer> snapshotVenueQuota(List<Long> userIds) {
        Map<Long, Integer> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        LocalDate weekStart = weekStart();
        for (UserQuotaDO q : quotaMapper.selectList(Wrappers.lambdaQuery(UserQuotaDO.class)
                .eq(UserQuotaDO::getQuotaType, "VENUE_WEEKLY")
                .eq(UserQuotaDO::getPeriodStart, weekStart)
                .in(UserQuotaDO::getUserId, userIds))) {
            map.put(q.getUserId(), q.getUsedTimes());
        }
        return map;
    }

    /** 配额恢复：压测前无行的删除（压测中新建的），有行的还原为快照值。 */
    private void restoreVenueQuota(List<Long> userIds, Map<Long, Integer> prior) {
        List<Long> ids = userIds.stream().distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        LocalDate weekStart = weekStart();
        List<UserQuotaDO> rows = quotaMapper.selectList(Wrappers.lambdaQuery(UserQuotaDO.class)
                .eq(UserQuotaDO::getQuotaType, "VENUE_WEEKLY")
                .eq(UserQuotaDO::getPeriodStart, weekStart)
                .in(UserQuotaDO::getUserId, ids));
        for (UserQuotaDO q : rows) {
            Integer before = prior.get(q.getUserId());
            if (before == null) {
                quotaMapper.deleteById(q.getId());
            } else if (!before.equals(q.getUsedTimes())) {
                q.setUsedTimes(before);
                quotaMapper.updateById(q);
            }
        }
    }

    /**
     * 清空接口幂等键（阶段 18）。
     *
     * <p>为什么压测前必须清理：幂等窗口内（默认 5 秒）上一轮的 CONSUMED 标记会让本轮的
     * "首笔"也被当成重复提交挡掉——场景数字会整体偏移（拦截图不再等于"请求数 - 名额数"）。
     * 压测要测的是闸门本身，因此每轮开始前把 {@code campus:idem:*} 清干净。</p>
     */
    private void clearIdempotentKeys() {
        try {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            try (org.springframework.data.redis.core.Cursor<String> cursor = stringRedisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match("campus:idem:*").count(500).build())) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("[压测] 已清理 {} 个幂等键（上一轮残留）", keys.size());
            }
        } catch (Exception e) {
            log.warn("[压测] 清理幂等键失败（忽略）: {}", e.getMessage());
        }
    }

    /** 清空压测班：删选课记录 + selected 归零 + 丢弃预扣计数器（阶段 17：否则计数与库不一致）。 */
    private void purgeStressClass() {
        courseSelectionMapper.delete(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getClassId, STRESS_CLASS_ID));
        courseClassMapper.update(null, Wrappers.lambdaUpdate(CourseClassDO.class)
                .setSql("selected = 0")
                .eq(CourseClassDO::getId, STRESS_CLASS_ID));
        preDeductService.dropCounter(com.campus.prededuct.PreDeductService.classKey(STRESS_CLASS_ID));
        // 阶段 19：余量读模型同步失效（否则列表页显示旧余量，最长到下一次对账/重建）
        CourseClassDO stressCls = courseClassMapper.selectById(STRESS_CLASS_ID);
        if (stressCls != null) {
            classStockCache.evict(stressCls.getCourseId());
            purgeAcceptState(stressCls.getCourseId());
        }
    }

    /**
     * 阶段 21：清理受理制状态——台账 + "压测班"已选条目 + 名额结构（重建为新鲜真值）。
     *
     * <p>顺序有讲究：先删台账，后清行——这样"清理瞬间还在途的受理消息"到达消费端时
     * 看到台账已空 → 按"已解决"跳过，不会在清理后再把名额落库（否则下一轮夹具断言被污染）。
     * 已选集合只剔除"压测班"这一条（<b>不整组删除</b>）：集合保留 → 下一轮齐射不用为每个用户
     * 冷启动初始化（lock+DB），测量的是受理热态；条目失败仅告警，受理时会惰性重建。</p>
     */
    private void purgeAcceptState(Long courseId) {
        courseStockService.pendClear(courseId);
        try (org.springframework.data.redis.core.Cursor<String> cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(com.campus.stock.CourseStockKeys.SEL_PREFIX + "*").count(500).build())) {
            int n = 0;
            String stressClass = String.valueOf(STRESS_CLASS_ID);
            while (cursor.hasNext() && n++ < 1000) {
                stringRedisTemplate.opsForSet().remove(cursor.next(), stressClass);
            }
        } catch (Exception e) {
            log.warn("[压测] 清理已选集合条目失败（忽略，受理时会惰性重建）: {}", e.getMessage());
        }
        courseStockService.evict(courseId);
        courseStockService.rebuild(courseId);   // 按受理制口径真值重建（selected=0 → remain=capacity）
    }

    /** 丢弃压测场地当天各时间片的预扣计数器（阶段 17：库已归零，计数器必须同步重建）。 */
    private void dropStressSlotCounters(LocalDate date) {
        List<ResourceSlotDO> slots = slotMapper.selectList(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getResourceId, STRESS_RESOURCE_ID)
                .eq(ResourceSlotDO::getSlotDate, date));
        for (ResourceSlotDO slot : slots) {
            preDeductService.dropCounter(com.campus.prededuct.PreDeductService.slotKey(slot.getId()));
        }
    }

    private ReservationCreateReqDTO req(Long resourceId, LocalDate date, LocalTime start, LocalTime end) {
        ReservationCreateReqDTO req = new ReservationCreateReqDTO();
        req.setResourceId(resourceId);
        req.setSlotDate(date);
        req.setStartTime(start);
        req.setEndTime(end);
        return req;
    }

    private LocalDate weekStart() {
        LocalDate today = LocalDate.now();
        return today.minusDays(today.getDayOfWeek().getValue() - 1L);
    }

    // ------------------------------------------------------------------
    // 内部记录
    // ------------------------------------------------------------------

    private record Attempt(boolean success, String code, long ms) {
    }

    private record Attempts(List<Attempt> list, long wallMs) {
        int successCount() {
            return (int) list.stream().filter(Attempt::success).count();
        }
    }
}
