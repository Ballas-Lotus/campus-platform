package com.campus.prededuct;

import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dto.req.CourseSelectReqDTO;
import com.campus.course.dto.resp.MyCourseRespDTO;
import com.campus.course.errorcode.CourseErrorCode;
import com.campus.course.service.CourseService;
import com.campus.framework.user.core.UserContext;
import com.campus.mq.MqMessagePublisher;
import com.campus.mq.message.CourseSelectMessage;
import com.campus.resilience.IdempotentMetrics;
import com.campus.resilience.IdempotentSupport;
import com.campus.stock.CourseStockAcceptService;
import com.campus.stock.CourseStockAcceptService.AcceptResult;
import com.campus.stock.CourseStockKeys;
import com.campus.stock.CourseStockService;
import com.campus.stock.StockMetrics;
import com.campus.stock.StockSignalBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 选课闸门（阶段 17 预扣 → 18 幂等 → 19 合并 Lua → 20 读侧信号 → <b>21 受理制</b>）。
 *
 * <h3>阶段 21：两条链路，一键切换（{@code campus.stock.async-select-enabled}）</h3>
 *
 * <pre>
 * 【受理链路（默认）】真相在 Redis，响应即受理，落库走 MQ：
 *    用户请求
 *      → ACCEPT Lua（一次原子）：幂等两态 → 已选检查 → 边界检查 →
 *        扣减 + 版本+1 + 写受理台账 + 置 CONSUMING + 集合 SADD（1 次往返）
 *      → 发落库消息（MQ syncSend 300ms 超时；失败 → 补偿回滚 → 40056"未被受理"）
 *      → PUBLISH 名额信号（先 MQ 后 PUBLISH：推出去的每一帧都有落库消息在路上）
 *      → 返回 {accepted:true, remain, version}　—— 数据库经 MQ 异步落库，
 *        成功/被拒都由消费端回推 SSE settled 收据（被拒时补偿回补名额 + 修正信号）
 *
 * 【同步链路（回退开关 false / Redis 异常降级）】阶段 19 语义原样保留：
 *    合并 Lua（幂等+预扣）→ CourseService.select 同步事务 → 落库后推送信号
 * </pre>
 *
 * <p><b>降级策略</b>：受理链路上任何 Redis 异常（含结构/集合未初始化且重建失败）都回落到
 * {@link #selectLegacy}；再退一层还有既有的"Redis 异常 → 幂等放行直通数据库"。三层降级后
 * 正确性始终由数据库（唯一索引 + 条件更新 + 行锁）兜底——Redis 从"唯一入口"降级为"加速器"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseSelectGate {

    private final PreDeductService preDeduct;
    private final CourseService courseService;
    private final CourseClassMapper courseClassMapper;
    /** 阶段 19：合并脚本与幂等切面共用同一份状态语义 */
    private final IdempotentSupport idempotentSupport;
    private final IdempotentMetrics idempotentMetrics;
    /** 阶段 21：受理（ACCEPT/COMPENSATE + 台账 + 集合 + 回执） */
    private final CourseStockAcceptService acceptService;
    /** 阶段 21：结构重建（ACCEPT 发现结构缺失时按真值重建后重试） */
    private final CourseStockService stockService;
    private final MqMessagePublisher mqMessagePublisher;
    private final StockSignalBus signalBus;
    private final StockMetrics stockMetrics;

    private static final String IDEM_PREFIX = "course-select";
    /** 幂等窗口 5 秒：连点/网络重试只生效一次 */
    private static final long IDEM_TTL_SECONDS = 5;

    /** 阶段 21：受理制开关（false = 一键回退到阶段 19/20 同步到库链路） */
    @Value("${campus.stock.async-select-enabled:true}")
    private boolean asyncSelectEnabled;

    /** 阶段 21：落库消息发送超时——宁可快速失败回滚（40056），不让用户干等 */
    @Value("${campus.stock.mq-send-timeout-ms:300}")
    private long mqSendTimeoutMs;

    // ------------------------------------------------------------------
    // 入口：受理链路（默认）或同步老链路（回退开关）
    // ------------------------------------------------------------------

    public MyCourseRespDTO select(CourseSelectReqDTO req) {
        if (!asyncSelectEnabled) {
            return selectLegacy(req);
        }
        Long userId = UserContext.getUserId();
        Long classId = req.getClassId();
        // 受理只用 classId→courseId 反查定位结构（c2c 热命中 1 次 HGET；miss 回查 DB 一次）
        Long courseId = acceptService.resolveCourseOf(classId);
        if (courseId == null) {
            return selectLegacy(req);   // 教学班不存在等：交回老链路给出准确错误
        }
        String opId = UUID.randomUUID().toString().replace("-", "");
        String idemKey = IdempotentSupport.key(IDEM_PREFIX, userId, String.valueOf(classId));

        AcceptResult r;
        try {
            r = acceptService.accept(userId, classId, courseId, opId, idemKey, IDEM_TTL_SECONDS);
            // 冷启动恢复：结构缺失（-2）与已选集合未初始化（-6）会**连续出现**（脚本先查集合后查结构，
            // 两者都冷时先得 -6；初始化集合后的重试才暴露 -2）——所以用小循环恢复，各项至多处理一次。
            // （曾经写成两个顺序 if：集合初始化后的第二次 accept 拿到 -2 却再没人重建结构 → 直接降级老链路）
            boolean selInitTried = false;
            boolean rebuildTried = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (r.code() == CourseStockAcceptService.SEL_NOT_INIT && !selInitTried) {
                    stockMetrics.acceptInit();
                    selInitTried = true;
                    if (!acceptService.initSelSet(userId)) {
                        break;   // 初始化失败（DB/Redis 异常）→ 降级老链路
                    }
                } else if (r.code() == CourseStockAcceptService.NOT_INIT && !rebuildTried) {
                    stockMetrics.acceptInit();
                    rebuildTried = true;
                    stockService.rebuild(courseId);   // 按受理制口径真值重建（锁+双检；抢不到锁则降级返回）
                } else {
                    break;   // 其他返回码（成功/满员/重复/已选）或恢复次数用尽
                }
                r = acceptService.accept(userId, classId, courseId, opId, idemKey, IDEM_TTL_SECONDS);
            }
        } catch (Exception e) {
            // Redis 异常 → 回落同步老链路（其内部还有一层"Redis 异常直通数据库"的降级）
            stockMetrics.acceptDegraded();
            log.warn("[受理] Redis 异常，回落同步老链路: classId={} {}", classId, e.getMessage());
            return selectLegacy(req);
        }

        return switch (r.code()) {
            case CourseStockAcceptService.ACCEPTED -> afterAccept(userId, classId, courseId, opId, idemKey, r);
            case CourseStockAcceptService.FULL -> {
                stockMetrics.acceptFull();
                log.info("[受理] 满员拦截 classId={}（未改任何状态；余量 {}）", classId, r.remain());
                throw new ClientException("受理满员(Redis 真相): classId=" + classId,
                        "手慢了，该教学班名额已满", CourseErrorCode.COURSE_FULL);
            }
            case CourseStockAcceptService.DUP_CONSUMED, CourseStockAcceptService.DUP_CONSUMING -> {
                stockMetrics.acceptDup();
                idempotentMetrics.onBlocked(IDEM_PREFIX);
                log.info("[受理] 幂等拦截重复提交（合并受理脚本）: key={}", idemKey);
                throw new ClientException("幂等拦截重复提交: key=" + idemKey,
                        "选课请求正在处理，请勿重复提交", BaseErrorCode.IDEMPOTENT_REJECTED);
            }
            case CourseStockAcceptService.ALREADY_SELECTED -> {
                stockMetrics.acceptAlready();
                log.info("[受理] 快速已选检查拦截: user={} class={}", userId, classId);
                throw new ClientException("快速已选检查拦截: user=" + userId + " class=" + classId,
                        "你已选过该教学班，请勿重复选课", CourseErrorCode.ALREADY_SELECTED);
            }
            default -> {
                // 重试后结构/集合仍未就绪（持续竞争等）→ 降级同步老链路，宁可慢不可误拒
                stockMetrics.acceptDegraded();
                log.warn("[受理] 结构/集合未就绪，回落同步老链路: classId={} code={}", classId, r.code());
                yield selectLegacy(req);
            }
        };
    }

    /**
     * 受理成功后的三段（顺序即语义，评审修正过）：
     * <b>① 发落库消息（失败必须补偿回滚）→ ② PUBLISH 信号 → ③ 置已消费 → 返回受理回执</b>。
     */
    private MyCourseRespDTO afterAccept(Long userId, Long classId, Long courseId,
                                        String opId, String idemKey, AcceptResult r) {
        // ① 先 MQ：失败 → 补偿（名额加回 + 版本+1 + 清台账 + 修正信号）→ 40056
        //    "未被受理"的语义 = 什么都没发生，用户可安全重试
        try {
            mqMessagePublisher.sendCourseSelect(
                    new CourseSelectMessage(opId, userId, classId, courseId, System.currentTimeMillis()),
                    mqSendTimeoutMs);
        } catch (Exception e) {
            stockMetrics.mqFail();
            int comp = acceptService.compensate(courseId, classId, opId);
            // 补偿后同步清"已选集合"：本次受理在集合里 SADD 过该班（它通过了 SISMEMBER 检查），
            // 而实际上什么都没发生——不清掉，用户下次选同一班会被"快速已选检查"误拦
            if (comp == CourseStockAcceptService.COMP_APPLIED) {
                stockService.selRemove(userId, classId);
            }
            log.warn("[受理] 落库消息发送失败，已补偿回滚: op={} class={} comp={} {}",
                    opId, classId, comp, e.getMessage());
            throw new ClientException("受理后落库消息发送失败: opId=" + opId,
                    "选课请求未能受理（系统繁忙），请稍后重试", CourseErrorCode.ACCEPT_FAILED);
        }
        // ② 再 PUBLISH：此刻落库消息已在 broker 上，信号不可能"推了却落不了库"
        signalBus.publish(CourseStockKeys.signalJson(classId, r.version(), r.remain()));
        // ③ 受理成立 → 置已消费（5 秒幂等窗口内重复提交得到 40060）
        idempotentSupport.markConsumed(idemKey, IDEM_TTL_SECONDS);
        stockMetrics.accepted();
        log.info("[受理] user={} class={} op={} → remain={} ver={}（已发落库消息，等 settled 回执）",
                userId, classId, opId, r.remain(), r.version());

        MyCourseRespDTO dto = new MyCourseRespDTO();
        dto.setClassId(classId);
        dto.setCourseId(courseId);
        dto.setAccepted(true);
        dto.setRemain(r.remain());
        dto.setVersion(r.version());
        dto.setOpId(opId);
        return dto;
    }

    // ------------------------------------------------------------------
    // 同步老链路（阶段 19 语义原样保留；回退开关 / 降级 / 压测·预扣场景演示共用）
    // ------------------------------------------------------------------

    /**
     * 同步链路（预扣闸门 + 幂等，快/慢/降级三层）。
     *
     * <p>三层的职责边界：快路径服务 99% 的正常流量；慢路径只服务"计数器未初始化的第一波请求"；
     * 降级路径只在 Redis 故障时启用——<b>每一层都保证"结果语义一致"：40052 满员 / 40060 重复 /
     * 成功时恰好扣 1 个名额</b>。</p>
     */
    public MyCourseRespDTO selectLegacy(CourseSelectReqDTO req) {
        Long userId = UserContext.getUserId();
        String counterKey = PreDeductService.classKey(req.getClassId());
        String idemKey = IdempotentSupport.key(IDEM_PREFIX, userId, String.valueOf(req.getClassId()));

        int fast;
        try {
            fast = preDeduct.tryFastPath(counterKey, idemKey, IDEM_TTL_SECONDS, 1);
        } catch (Exception e) {
            // Redis 异常 → 完全降级：幂等放行 + 不预扣，直通数据库（与阶段 18 切面降级行为一致）
            idempotentMetrics.onDegraded();
            log.warn("[选课闸门] Redis 异常，降级直通数据库: classId={} {}", req.getClassId(), e.getMessage());
            return courseService.select(req);
        }

        switch (fast) {
            case PreDeductService.FAST_PASS -> {
                idempotentMetrics.onPassed();
                return runBusiness(req, idemKey, counterKey);
            }
            case PreDeductService.FAST_FULL -> {
                log.info("[预扣闸门] 拦截选课 classId={}（合并脚本判定余量不足，未查库未占幂等标记）", req.getClassId());
                throw new ClientException("预扣闸门拦截选课请求: classId=" + req.getClassId(),
                        "手慢了，该教学班名额已满", CourseErrorCode.COURSE_FULL);
            }
            case PreDeductService.FAST_DUP_CONSUMED, PreDeductService.FAST_DUP_CONSUMING -> {
                idempotentMetrics.onBlocked(IDEM_PREFIX);
                log.info("[幂等] 拦截重复提交（合并脚本）: key={}", idemKey);
                throw new ClientException("幂等拦截重复提交: key=" + idemKey,
                        "选课请求正在处理，请勿重复提交", BaseErrorCode.IDEMPOTENT_REJECTED);
            }
            default -> {
                // FAST_NOT_INIT：计数器未初始化（冷启动/过期后的第一波）→ 走慢路径初始化后再判定
                return slowPath(req, idemKey, counterKey);
            }
        }
    }

    /**
     * 慢路径：计数器未初始化时的完整链路（保留阶段 18 语义）。
     * 只有"冷启动第一波"会走到这里，之后全部由快路径服务。
     */
    private MyCourseRespDTO slowPath(CourseSelectReqDTO req, String idemKey, String counterKey) {
        long state;
        try {
            state = idempotentSupport.tryAcquire(idemKey, IDEM_TTL_SECONDS);
        } catch (Exception e) {
            idempotentMetrics.onDegraded();
            log.warn("[选课闸门] Redis 异常（幂等），降级直通: classId={} {}", req.getClassId(), e.getMessage());
            return courseService.select(req);
        }
        if (state != 1L) {
            idempotentMetrics.onBlocked(IDEM_PREFIX);
            log.info("[幂等] 拦截重复提交（慢路径）: key={}（{}）", idemKey, state == 0L ? "窗口内已成功" : "并发处理中");
            throw new ClientException("幂等拦截重复提交: key=" + idemKey,
                    "选课请求正在处理，请勿重复提交", BaseErrorCode.IDEMPOTENT_REJECTED);
        }
        idempotentMetrics.onPassed();

        List<String> keys = List.of(counterKey);
        List<PreDeductService.KeyMeta> metas = List.of(new PreDeductService.KeyMeta(
                counterKey, PreDeductService.TYPE_CLASS, req.getClassId(), null, null, "", System.currentTimeMillis()));
        Supplier<Integer> truthLoader = () -> {
            CourseClassDO cls = courseClassMapper.selectById(req.getClassId());
            return cls == null ? null : Math.max(0, cls.getCapacity() - cls.getSelected());
        };
        int r = preDeduct.ensureThenDeduct(keys, metas, List.of(truthLoader), 1);
        if (r == PreDeductService.INSUFFICIENT) {
            // 释放幂等标记：失败不算"已提交"，允许用户换班或稍后重试
            idempotentSupport.releaseQuietly(idemKey);
            idempotentMetrics.onReleasedForRetry();
            log.info("[预扣闸门] 拦截选课 classId={}（慢路径：Redis 判定余量不足，未查库）", req.getClassId());
            throw new ClientException("预扣闸门拦截选课请求: classId=" + req.getClassId(),
                    "手慢了，该教学班名额已满", CourseErrorCode.COURSE_FULL);
        }
        return runBusiness(req, idemKey, counterKey);
    }

    /** 业务执行与收尾：成功置 CONSUMED；失败回补预扣并释放幂等标记（允许重试） */
    private MyCourseRespDTO runBusiness(CourseSelectReqDTO req, String idemKey, String counterKey) {
        try {
            MyCourseRespDTO dto = courseService.select(req);
            idempotentSupport.markConsumed(idemKey, IDEM_TTL_SECONDS);
            return dto;
        } catch (RuntimeException e) {
            // 数据库拒绝（冲突/真满/重复）→ 把预扣的名额加回去，避免"少卖"；并释放幂等标记
            preDeduct.releaseQuietly(List.of(counterKey), 1);
            idempotentSupport.releaseQuietly(idemKey);
            idempotentMetrics.onReleasedForRetry();
            throw e;
        }
    }

    /** 退课：释放名额后同步释放预扣（否则 Redis 会比真值少 1） */
    public void drop(Long classId) {
        courseService.drop(classId);
        preDeduct.release(List.of(PreDeductService.classKey(classId)), 1);
    }
}
