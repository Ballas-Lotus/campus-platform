package com.campus.stock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.entity.CourseSelectionDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dao.mapper.CourseSelectionMapper;
import com.campus.framework.lock.CampusLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * <b>阶段 21 · 受理制的核心：ACCEPT / COMPENSATE 两个原子脚本 + 受理台账 + 已选集合 + 结果回执。</b>
 *
 * <h3>与阶段 20（只改读）的本质区别</h3>
 * <p>阶段 20 里"能不能选上"由同步事务裁决，信号只是落库后的通知；本类把裁决点前移到 Redis：
 * ACCEPT 一次原子完成"幂等两态 → 已选检查 → 边界检查 → 扣减 + 版本 +1 + 写台账"，
 * 随后 HTTP 线程只做三件事：<b>发落库消息（MQ，300ms 超时）→ PUBLISH 信号 → 置已消费</b>，
 * 立即返回"已受理"。数据库经 MQ 异步落库；落库被拒 → {@link #compensate} 把名额加回
 * 并推"修正信号"；落库成功/被拒都通过 SSE {@code settled} 事件回执给本人。</p>
 *
 * <h3>为什么"真相在 Redis"仍不会超卖（三道防线）</h3>
 * <ol>
 *   <li><b>受理台账（pending）</b>：每一笔未决受理都在 {@code campus:stock:pend:{courseId}} 里；
 *       对账/重建真值口径 = capacity − selected(DB) − Σ未决受理；补偿脚本以台账存在为原子闸门；</li>
 *   <li><b>落库行 op_id</b>：消费端把消息 opId 写进 t_course_selection.op_id——
 *       重放的消息靠它区分"已生效"（清账即止）与"未生效"（才允许补偿）；</li>
 *   <li><b>消费端"台账不存在即跳过"</b>：超时裁决先补偿过的受理，其迟到消息看到台账已清 → 不再落库。</li>
 * </ol>
 *
 * <h3>顺序刻意的两处（评审修正）</h3>
 * <ul>
 *   <li><b>先发 MQ 再 PUBLISH</b>：MQ 失败要补偿回滚——若先推了信号再回滚，客户端会看到
 *       "名额掉了一下又弹回来"的假信号；先 MQ 保证"推送出去的每一帧都有落库消息在路上"；</li>
 *   <li><b>满员拒绝不改任何状态</b>：不改计数、不加版本、不推信号——拒绝响应本身携带
 *       最新余量与版本，请求者无需信号；给满员拒绝加版本只会让抢课风暴里版本号空转。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseStockAcceptService {

    /** ACCEPT 返回码 */
    public static final int ACCEPTED = 1;
    /** 余量不足（未改任何状态，返回体带当前 remain/ver 供客户端同步） */
    public static final int FULL = -1;
    /** 名额结构未初始化（Java：按真值 rebuild 后重试一次） */
    public static final int NOT_INIT = -2;
    /** 幂等：窗口内已成功 */
    public static final int DUP_CONSUMED = -3;
    /** 幂等：并发处理中 */
    public static final int DUP_CONSUMING = -4;
    /** 快速已选检查：该生已选过此班 */
    public static final int ALREADY_SELECTED = -5;
    /** 已选集合未初始化（Java：按 DB 惰性初始化后重试一次） */
    public static final int SEL_NOT_INIT = -6;

    /** compensate 结果 */
    public static final int COMP_APPLIED = 1;   // 已补偿（名额加回 + 修正信号）
    public static final int COMP_NOOP = 0;      // 台账已不存在 = 已被解决过（幂等空转）
    public static final int COMP_ERROR = -1;    // Redis 异常：台账未动，等重试/裁决

    private static final long C2C_TTL_SECONDS = 86400L;
    private static final long SEL_INIT_LOCK_WAIT_MS = 300L;
    private static final long SEL_INIT_LOCK_TTL_MS = 3000L;
    /** 空集合哨兵：用户"没有任何已选"时 SADD 一个 -1，让 EXISTS 判定"已初始化"成立（班级 id 恒为正） */
    private static final String SEL_EMPTY_SENTINEL = "-1";

    private final StringRedisTemplate redis;
    private final CampusLock lock;
    private final CourseClassMapper courseClassMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final StockSignalBus signalBus;
    private final StockSseRegistry sseRegistry;
    private final com.campus.mq.MqMessagePublisher mqMessagePublisher;
    private final StockMetrics metrics;

    @Value("${campus.stock.key-ttl-seconds:21600}")
    private long keyTtlSeconds;

    @Value("${campus.stock.pend-ttl-seconds:86400}")
    private long pendTtlSeconds;

    @Value("${campus.stock.sel-ttl-seconds:86400}")
    private long selTtlSeconds;

    /** 受理结果（code + 拒绝时携带的最新真相） */
    public record AcceptResult(int code, int remain, long version) {
    }

    // ------------------------------------------------------------------
    // ACCEPT：一次原子受理
    // ------------------------------------------------------------------

    /**
     * <pre>
     * KEYS[1]=stock(courseId) KEYS[2]=pend(courseId) KEYS[3]=sel(userId) KEYS[4]=c2c KEYS[5]=idemKey
     * ARGV: 1=classId 2=userId 3=opId 4=idemTtl 5=pendTtl 6=stockTtl 7=c2cTtl 8=selTtl 9=nowMs 10=courseId
     *
     * 返回 {1, newRemain, newVer, cver}   受理成功（台账已写 / 幂等置 CONSUMING / 集合已 SADD）
     *      {-1, remain, ver, 0}          满员（<b>未改任何状态</b>）
     *      {-2,...} 结构未初始化  {-3/-4,...} 幂等拦截  {-5,...} 已选过  {-6,...} 集合未初始化
     * </pre>
     */
    private static final DefaultRedisScript<List> ACCEPT_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('GET', KEYS[5])
            if state == 'CONSUMED' then return {-3, 0, 0, 0} end
            if state == 'CONSUMING' then return {-4, 0, 0, 0} end
            if redis.call('EXISTS', KEYS[3]) == 0 then return {-6, 0, 0, 0} end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return {-5, 0, 0, 0} end
            local field = 'c:' .. ARGV[1]
            local cur = redis.call('HGET', KEYS[1], field)
            if cur == false then return {-2, 0, 0, 0} end
            local sep = string.find(cur, ':')
            local remain = tonumber(string.sub(cur, 1, sep - 1))
            local ver = tonumber(string.sub(cur, sep + 1))
            if remain < 1 then return {-1, remain, ver, 0} end
            local nv = ver + 1
            local nr = remain - 1
            redis.call('HSET', KEYS[1], field, nr .. ':' .. nv)
            local cver = redis.call('HINCRBY', KEYS[1], '@cver', 1)
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            redis.call('HSET', KEYS[2], ARGV[1] .. ':' .. ARGV[3], '-1|' .. ARGV[9] .. '|' .. ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            redis.call('SET', KEYS[5], 'CONSUMING', 'EX', ARGV[4])
            redis.call('SADD', KEYS[3], ARGV[1])
            redis.call('EXPIRE', KEYS[3], ARGV[8])
            redis.call('HSET', KEYS[4], ARGV[1], ARGV[10])
            redis.call('EXPIRE', KEYS[4], ARGV[7])
            return {1, nr, nv, cver}
            """, List.class);

    /**
     * 受理：写路径的"裁决时刻"。异常直接抛出（由闸门统一降级到同步老链路）。
     */
    @SuppressWarnings("unchecked")
    public AcceptResult accept(Long userId, Long classId, Long courseId, String opId,
                               String idemKey, long idemTtlSeconds) {
        List<Long> r = (List<Long>) redis.execute(ACCEPT_SCRIPT,
                List.of(CourseStockKeys.stock(courseId), CourseStockKeys.pend(courseId),
                        CourseStockKeys.sel(userId), CourseStockKeys.CLASS_COURSE, idemKey),
                String.valueOf(classId), String.valueOf(userId), opId,
                String.valueOf(idemTtlSeconds), String.valueOf(pendTtlSeconds), String.valueOf(keyTtlSeconds),
                String.valueOf(C2C_TTL_SECONDS), String.valueOf(selTtlSeconds),
                String.valueOf(System.currentTimeMillis()), String.valueOf(courseId));
        if (r == null || r.isEmpty()) {
            return new AcceptResult(NOT_INIT, 0, 0L);
        }
        int code = r.get(0).intValue();
        int remain = r.size() > 1 ? r.get(1).intValue() : 0;
        long ver = r.size() > 2 ? r.get(2) : 0L;
        return new AcceptResult(code, remain, ver);
    }

    // ------------------------------------------------------------------
    // COMPENSATE：回补（落库被拒 / 超时裁决）——台账存在是原子闸门
    // ------------------------------------------------------------------

    /**
     * <pre>
     * KEYS[1]=stock(courseId) KEYS[2]=pend(courseId)
     * ARGV: 1=classId 2=opId 3=amount(1) 4=stockTtl
     *
     * 返回 {1, newRemain, newVer}  已补偿（台账已 HDEL、名额已加回并钳制 ≤容量、版本 +1）
     *      {0, 0, 0}               台账不存在 = 已被解决过 → 空转（幂等，防重复补偿）
     * </pre>
     */
    private static final DefaultRedisScript<List> COMPENSATE_SCRIPT = new DefaultRedisScript<>("""
            local pf = ARGV[1] .. ':' .. ARGV[2]
            if redis.call('HEXISTS', KEYS[2], pf) == 0 then return {0, 0, 0} end
            redis.call('HDEL', KEYS[2], pf)
            local field = 'c:' .. ARGV[1]
            local cur = redis.call('HGET', KEYS[1], field)
            if cur == false then return {1, -1, -1} end
            local sep = string.find(cur, ':')
            local remain = tonumber(string.sub(cur, 1, sep - 1))
            local ver = tonumber(string.sub(cur, sep + 1))
            local nr = remain + tonumber(ARGV[3])
            local cap = redis.call('HGET', KEYS[1], 'm:' .. ARGV[1])
            if cap ~= false and nr > tonumber(cap) then nr = tonumber(cap) end
            local nv = ver + 1
            redis.call('HSET', KEYS[1], field, nr .. ':' .. nv)
            redis.call('HINCRBY', KEYS[1], '@cver', 1)
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return {1, nr, nv}
            """, List.class);

    /**
     * 补偿一笔未决受理：名额加回 + 版本 +1 + 推送修正信号（客户端自动收敛）。
     *
     * @return {@link #COMP_APPLIED} / {@link #COMP_NOOP}（已解决）/ {@link #COMP_ERROR}（Redis 异常，等重试）
     */
    @SuppressWarnings("unchecked")
    public int compensate(Long courseId, Long classId, String opId) {
        try {
            List<Long> r = (List<Long>) redis.execute(COMPENSATE_SCRIPT,
                    List.of(CourseStockKeys.stock(courseId), CourseStockKeys.pend(courseId)),
                    String.valueOf(classId), opId, "1", String.valueOf(keyTtlSeconds));
            if (r == null || r.isEmpty()) {
                return COMP_NOOP;
            }
            if (r.get(0) != 1L) {
                return COMP_NOOP;
            }
            metrics.compensated();
            int remain = r.size() > 1 ? r.get(1).intValue() : -1;
            long ver = r.size() > 2 ? r.get(2) : 0L;
            if (remain >= 0) {
                signalBus.publish(CourseStockKeys.signalJson(classId, ver, remain));
            }
            log.info("[受理] 补偿 {}: class={} op={} → remain={} ver={}", courseId, classId, opId, remain, ver);
            return COMP_APPLIED;
        } catch (Exception e) {
            log.warn("[受理] 补偿失败（台账保留，等 MQ 重试/超时裁决）: course={} class={} op={} {}",
                    courseId, classId, opId, e.getMessage());
            return COMP_ERROR;
        }
    }

    // ------------------------------------------------------------------
    // 辅助：课程定位 / 已选集合初始化 / 落库行判定 / 结果回执
    // ------------------------------------------------------------------

    /** classId → courseId：优先 c2c 反查（受理热路径 1 次 HGET），miss 回查 DB 并回填。null = 教学班不存在 */
    public Long resolveCourseOf(Long classId) {
        try {
            Object v = redis.opsForHash().get(CourseStockKeys.CLASS_COURSE, String.valueOf(classId));
            if (v != null) {
                return Long.parseLong(String.valueOf(v));
            }
        } catch (Exception ignore) {
            // 反查失败：回查 DB
        }
        CourseClassDO cls = courseClassMapper.selectById(classId);
        if (cls == null) {
            return null;
        }
        try {
            redis.opsForHash().put(CourseStockKeys.CLASS_COURSE, String.valueOf(classId),
                    String.valueOf(cls.getCourseId()));
            redis.expire(CourseStockKeys.CLASS_COURSE, Duration.ofSeconds(C2C_TTL_SECONDS));
        } catch (Exception ignore) {
            // 回填失败不影响本次受理
        }
        return cls.getCourseId();
    }

    /**
     * 惰性初始化已选集合（锁 + 双检 + 按 DB 真值 + 空集合哨兵）。
     * 失败返回 false（闸门据此降级到同步老链路——正确性永远在 DB，集合只是加速器）。
     */
    public boolean initSelSet(Long userId) {
        String key = CourseStockKeys.sel(userId);
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                return true;
            }
            String token = lock.tryAcquire(CourseStockKeys.selLock(userId), SEL_INIT_LOCK_WAIT_MS, SEL_INIT_LOCK_TTL_MS);
            if (token == null) {
                return Boolean.TRUE.equals(redis.hasKey(key));
            }
            try {
                if (Boolean.TRUE.equals(redis.hasKey(key))) {
                    return true;   // 双检
                }
                List<Long> ids = courseSelectionMapper.selectSelectedClassIds(userId);
                if (ids != null && !ids.isEmpty()) {
                    redis.opsForSet().add(key, ids.stream().map(String::valueOf).toArray(String[]::new));
                }
                redis.opsForSet().add(key, SEL_EMPTY_SENTINEL);   // "已初始化"标记（空集合也成立）
                redis.expire(key, Duration.ofSeconds(selTtlSeconds));
                return true;
            } finally {
                lock.release(CourseStockKeys.selLock(userId), token);
            }
        } catch (Exception e) {
            log.warn("[受理] 已选集合初始化失败（本次降级同步链路）: user={} {}", userId, e.getMessage());
            return false;
        }
    }

    /** 该生在此班的落库行（消费端判"已生效/是否仍已选"用） */
    public CourseSelectionDO selectionOf(Long userId, Long classId) {
        return courseSelectionMapper.selectOne(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getUserId, userId)
                .eq(CourseSelectionDO::getClassId, classId));
    }

    /** 落库行的 op_id（未落库/无行 → null） */
    public String opIdOf(Long userId, Long classId) {
        CourseSelectionDO row = selectionOf(userId, classId);
        return row == null ? null : row.getOpId();
    }

    /**
     * 受理结果回执：SSE {@code settled} 定向推送（在线即达）+ 失败时的站内信。
     * 在线不在线都不影响正确性——客户端有"超时兜底刷新"，站内信是持久凭据。
     */
    public void settleNotify(Long userId, Long classId, boolean ok, String reason) {
        try {
            String json = "{\"classId\":" + classId + ",\"ok\":" + ok
                    + (reason == null || reason.isEmpty() ? "" : ",\"reason\":\"" + escape(reason) + "\"}");
            sseRegistry.pushToUser(userId, StockSseRegistry.EVT_SETTLED, json);
            metrics.settledPushed();
        } catch (Exception e) {
            log.warn("[受理] settled 推送失败: user={} class={} {}", userId, classId, e.getMessage());
        }
        if (!ok) {
            try {
                mqMessagePublisher.sendNotification(com.campus.mq.message.NotificationMessage.of(
                        userId, "选课未成功",
                        (reason == null || reason.isEmpty() ? "本次选课未生效" : reason)
                                + "（名额已退回，可重新选课）",
                        "COURSE", "reject-" + classId + "-" + System.nanoTime()));
            } catch (Exception e) {
                log.warn("[受理] 失败站内信发送失败（不阻断）: user={} {}", userId, e.getMessage());
            }
        }
        if (ok) {
            metrics.settledOk();
        } else {
            metrics.settledReject();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
