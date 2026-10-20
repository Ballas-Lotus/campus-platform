package com.campus.stock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.framework.lock.CampusLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 20 · 名额同步核心（读侧）：
 *
 * <pre>
 *   写路径（不变）落库 → afterCommit 追加两个动作：
 *       ① {@link #push}  —— 版本+1 与最新余量同一脚本原子写入，PUBLISH 信号
 *       ② （信号经 Redis Pub/Sub 广播到各实例 → 各实例推自己的 SSE 连接）
 *
 *   读路径三层：
 *       L1 {@link StockL1Cache}（进程内，键含版本，命中 0 网络调用）
 *       L2 一次 HGETALL campus:stock:{courseId}（版本与余量同脚本写入 → 原子快照）
 *       L3 {@link #rebuild}（分布式锁 + 双检 + DB 真值回填；抢不到锁则兜底直返，不堆积）
 * </pre>
 *
 * <p><b>正确性边界（阶段 21 受理制起）</b>：本结构是<b>受理真相</b>——ACCEPT 在此原子裁决，
 * 数据库经 MQ 异步落库、失败补偿。对账/重建的真值口径 = capacity − selected(DB) − Σ未决受理
 * （见 {@link #dbTruthWithPending}）；直接按 DB 真值写回会把在途名额"还回去"导致超卖。
 * 数据库仍是最终校验者（落库事务不做任何让步：唯一索引/条件更新/行锁原样保留）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseStockService {

    private final StringRedisTemplate redis;
    private final CampusLock lock;
    private final CourseClassMapper courseClassMapper;
    private final StockL1Cache l1Cache;
    private final StockSignalBus signalBus;
    private final StockMetrics metrics;

    @Value("${campus.stock.key-ttl-seconds:21600}")
    private long keyTtlSeconds;

    private static final long C2C_TTL_SECONDS = 86400L;
    private static final long SEL_TTL_SECONDS = 86400L;
    private static final long LOCK_WAIT_MS = 200L;
    private static final long LOCK_TTL_MS = 3000L;

    // ------------------------------------------------------------------
    // 写后推送（仅在 afterCommit 调用；任何异常都不影响写路径）
    // ------------------------------------------------------------------

    /**
     * 推送脚本：读当前余量与版本 → 加 delta → <b>余量与版本同一次 HSET 原子写入</b>
     * → 课程版本 +1 → 刷新 TTL → 顺带维护 c2c 反查表。
     *
     * <p>返回 {1, remain, ver, cver}；返回 {-1,...} 表示结构尚未建立（字段缺失），
     * 由 Java 先按 DB 真值重建再重试一次。</p>
     */
    private static final DefaultRedisScript<List> PUSH_SCRIPT = new DefaultRedisScript<>("""
            local field = 'c:' .. ARGV[1]
            local cur = redis.call('HGET', KEYS[1], field)
            if cur == false then return {-1, 0, 0, 0} end
            local sep = string.find(cur, ':')
            local remain = tonumber(string.sub(cur, 1, sep - 1))
            local ver = tonumber(string.sub(cur, sep + 1)) + 1
            local newRemain = remain + tonumber(ARGV[2])
            if newRemain < 0 then newRemain = 0 end
            local cap = redis.call('HGET', KEYS[1], 'm:' .. ARGV[1])
            if cap ~= false and newRemain > tonumber(cap) then newRemain = tonumber(cap) end
            redis.call('HSET', KEYS[1], field, newRemain .. ':' .. ver)
            local cver = redis.call('HINCRBY', KEYS[1], '@cver', 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            return {1, newRemain, ver, cver}
            """, List.class);

    /**
     * 对账纠偏脚本（<b>带版本 CAS</b>）：仅当结构里的版本仍等于对账读取时的版本才写回
     * ——受理/补偿在比对与写回之间发生时，CAS 失败直接放弃本轮（避免把并发扣减覆盖回去）。
     * 返回 {1, newVer, cver} = 已写回；{0, curVer, 0} = CAS 失败（结构已被并发修改）。
     */
    private static final DefaultRedisScript<List> FIX_SCRIPT = new DefaultRedisScript<>("""
            local field = 'c:' .. ARGV[1]
            local cur = redis.call('HGET', KEYS[1], field)
            local ver = 0
            if cur ~= false then
              local sep = string.find(cur, ':')
              ver = tonumber(string.sub(cur, sep + 1))
            end
            local expected = tonumber(ARGV[5])
            if expected >= 0 then
              if cur == false or ver ~= expected then return {0, ver, 0} end
            else
              if cur ~= false then return {0, ver, 0} end
            end
            local newVer = ver + 1
            redis.call('HSET', KEYS[1], 'm:' .. ARGV[1], ARGV[2])
            redis.call('HSET', KEYS[1], field, ARGV[3] .. ':' .. newVer)
            local cver = redis.call('HINCRBY', KEYS[1], '@cver', 1)
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return {1, newVer, cver}
            """, List.class);

    /**
     * 写后推送：落库（afterCommit）后调用，把最新名额与版本写入结构并广播信号。
     * 全部异常只记日志——推送失败不影响选课结果，客户端由轮询/重连兜底。
     */
    public void push(Long courseId, Long classId, int delta) {
        try {
            List<Long> r = executePush(courseId, classId, delta);
            if (r.size() < 4 || r.get(0) != 1L) {
                // 结构未建（首次/过期）：按 DB 真值重建后重试一次
                rebuild(courseId);
                r = executePush(courseId, classId, delta);
                if (r.size() < 4 || r.get(0) != 1L) {
                    metrics.pushSkipped();
                    return;
                }
            }
            int remain = r.get(1).intValue();
            long ver = r.get(2);
            metrics.push();
            signalBus.publish(CourseStockKeys.signalJson(classId, ver, remain));
        } catch (Exception e) {
            metrics.pushSkipped();
            log.warn("[名额同步] 写后推送失败（不影响选课结果，客户端轮询兜底）: course={} class={} {}",
                    courseId, classId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> executePush(Long courseId, Long classId, int delta) {
        List<Long> r = (List<Long>) redis.execute(PUSH_SCRIPT,
                List.of(CourseStockKeys.stock(courseId), CourseStockKeys.CLASS_COURSE),
                String.valueOf(classId), String.valueOf(delta), String.valueOf(keyTtlSeconds),
                String.valueOf(courseId), String.valueOf(C2C_TTL_SECONDS));
        return r == null ? List.of(-1L, 0L, 0L, 0L) : r;
    }

    /**
     * 对账纠偏：按绝对真值写回（版本 CAS：结构自比对以来被受理/补偿动过则放弃本轮）+ 推送修正信号。
     *
     * @param expectedVer 对账读取时看到的版本；{@code <0} 表示"当时字段缺失"（CAS 要求仍缺失）
     * @return true = 已写回（含修正信号）；false = CAS 未命中或异常（不打扰并发写者）
     */
    @SuppressWarnings("unchecked")
    public boolean applyTruth(Long courseId, Long classId, int remain, int capacity, long expectedVer) {
        try {
            List<Long> r = (List<Long>) redis.execute(FIX_SCRIPT, List.of(CourseStockKeys.stock(courseId)),
                    String.valueOf(classId), String.valueOf(capacity), String.valueOf(remain),
                    String.valueOf(keyTtlSeconds), String.valueOf(expectedVer));
            if (r != null && !r.isEmpty() && r.get(0) == 1L) {
                metrics.reconciled(1);
                l1Cache.invalidate(courseId);
                signalBus.publish(CourseStockKeys.signalJson(classId, r.get(1), remain));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[名额同步] 对账纠偏失败: course={} class={} {}", courseId, classId, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 读：L1 → L2 → L3
    // ------------------------------------------------------------------

    /**
     * 三层查询。{@code clientVersion} 仅用于 L1 直返（命中即 0 网络调用），
     * 绝不用它推断客户端新鲜度——响应永远返回服务端当前真实 cver。
     */
    public StockSnapshot query(Long courseId, Long clientVersion) {
        if (clientVersion != null) {
            StockSnapshot hit = l1Cache.get(courseId, clientVersion);
            if (hit != null) {
                metrics.l1Hit();
                return hit;
            }
            metrics.l1Miss();
        }
        StockSnapshot snap = readL2(courseId);
        if (snap != null) {
            l1Cache.put(courseId, snap);
            return snap;
        }
        return rebuild(courseId);
    }

    /** L2：一次 HGETALL = 课程全部班级的 (remain, ver, capacity) 原子快照；key 不存在返回 null */
    public StockSnapshot readL2(Long courseId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(CourseStockKeys.stock(courseId));
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            long cver = 0L;
            Object cv = raw.get(CourseStockKeys.CVER_FIELD);
            if (cv != null) {
                cver = parseLong(String.valueOf(cv), 0L);
            }
            Map<Long, Integer> caps = new HashMap<>();
            Map<Long, StockSnapshot.ClassStock> classes = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                String field = String.valueOf(e.getKey());
                String value = String.valueOf(e.getValue());
                if (field.startsWith(CourseStockKeys.CAPACITY_PREFIX)) {
                    caps.put(parseLong(field.substring(CourseStockKeys.CAPACITY_PREFIX.length()), -1L),
                            (int) parseLong(value, 0L));
                } else if (field.startsWith(CourseStockKeys.STOCK_PREFIX)) {
                    Long id = parseLong(field.substring(CourseStockKeys.STOCK_PREFIX.length()), -1L);
                    if (id <= 0) {
                        continue;
                    }
                    int sep = value.indexOf(':');
                    if (sep <= 0) {
                        continue;   // 复合值损坏：按字段缺失处理（下次重建修正），不抛异常
                    }
                    int remain = (int) parseLong(value.substring(0, sep), 0L);
                    long ver = parseLong(value.substring(sep + 1), 0L);
                    classes.put(id, new StockSnapshot.ClassStock(remain, ver, caps.getOrDefault(id, 0)));
                }
            }
            if (classes.isEmpty()) {
                return null;
            }
            return new StockSnapshot(cver, classes, false);
        } catch (Exception e) {
            log.warn("[名额同步] L2 读取失败，转 L3: course={} {}", courseId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // L3：重建（锁 + 双检，按 DB 真值；抢不到锁兜底直返）
    // ------------------------------------------------------------------

    /**
     * 重建：分布式锁 + 双检。写回值 = <b>受理制口径真值</b>（capacity − selected(DB) − Σ未决受理）——
     * 少了 pending 这一项，重建就会把在途名额"还回去"→ 放行过量 → 超卖。
     * 未抢到锁/异常 → 直接返回真值快照（degraded，不写缓存、不阻塞请求）。
     */
    public StockSnapshot rebuild(Long courseId) {
        String token = lock.tryAcquire(CourseStockKeys.lock(courseId), LOCK_WAIT_MS, LOCK_TTL_MS);
        if (token == null) {
            metrics.degraded();
            return dbSnapshot(courseId, true);
        }
        try {
            StockSnapshot cur = readL2(courseId);
            Map<Long, StockSnapshot.ClassStock> truth = dbTruthWithPending(courseId);
            if (truth.isEmpty()) {
                redis.delete(CourseStockKeys.stock(courseId));
                return new StockSnapshot(cur == null ? 1L : cur.cver(), Map.of(), false);
            }
            if (cur != null && cur.classes().keySet().equals(truth.keySet())) {
                // 双检：他人已按同一份真值建好（保留既有版本号，避免客户端版本回退）
                return cur;
            }
            long cver = cur == null ? 1L : Math.max(1L, cur.cver());
            Map<String, String> hash = new LinkedHashMap<>();
            Map<Long, StockSnapshot.ClassStock> merged = new LinkedHashMap<>();
            for (Map.Entry<Long, StockSnapshot.ClassStock> e : truth.entrySet()) {
                Long id = e.getKey();
                long ver = 0L;
                if (cur != null && cur.classes().containsKey(id)) {
                    ver = cur.classes().get(id).version();   // 保留既有版本号
                }
                hash.put(CourseStockKeys.capacityField(id), String.valueOf(e.getValue().capacity()));
                hash.put(CourseStockKeys.stockField(id), CourseStockKeys.stockValue(e.getValue().remain(), ver));
                merged.put(id, new StockSnapshot.ClassStock(e.getValue().remain(), ver, e.getValue().capacity()));
            }
            hash.put(CourseStockKeys.CVER_FIELD, String.valueOf(cver));
            redis.opsForHash().putAll(CourseStockKeys.stock(courseId), hash);
            redis.expire(CourseStockKeys.stock(courseId), Duration.ofSeconds(keyTtlSeconds));
            metrics.rebuilt();
            log.info("[名额同步] 结构重建 course={}：{} 个教学班（cver={}）", courseId, merged.size(), cver);
            return new StockSnapshot(cver, merged, false);
        } catch (Exception e) {
            log.warn("[名额同步] 重建异常，本次直返 DB 真值: course={} {}", courseId, e.getMessage());
            metrics.degraded();
            return dbSnapshot(courseId, true);
        } finally {
            lock.release(CourseStockKeys.lock(courseId), token);
        }
    }

    /** 供测试夹具/管理操作：失效该课程的名额结构（下一位读者重建） */
    public void evict(Long courseId) {
        try {
            redis.delete(CourseStockKeys.stock(courseId));
            l1Cache.invalidate(courseId);
        } catch (Exception e) {
            log.warn("[名额同步] 失效失败: course={} {}", courseId, e.getMessage());
        }
    }

    /** 订阅用：把课程集合展开为班级集合（一次批量查询） */
    public Map<Long, List<Long>> classIdsOf(Collection<Long> courseIds) {
        Map<Long, List<Long>> out = new HashMap<>();
        if (courseIds == null || courseIds.isEmpty()) {
            return out;
        }
        List<CourseClassDO> classes = courseClassMapper.selectList(Wrappers.lambdaQuery(CourseClassDO.class)
                .in(CourseClassDO::getCourseId, courseIds));
        for (CourseClassDO c : classes) {
            out.computeIfAbsent(c.getCourseId(), k -> new ArrayList<>()).add(c.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** DB 真值（不含在途受理）：classId -> (remain=capacity-selected, version 占位 0, capacity) */
    public Map<Long, StockSnapshot.ClassStock> dbTruth(Long courseId) {
        List<CourseClassDO> classes = courseClassMapper.selectList(Wrappers.lambdaQuery(CourseClassDO.class)
                .eq(CourseClassDO::getCourseId, courseId)
                .orderByAsc(CourseClassDO::getId));
        Map<Long, StockSnapshot.ClassStock> m = new LinkedHashMap<>();
        for (CourseClassDO c : classes) {
            int capacity = c.getCapacity() == null ? 0 : c.getCapacity();
            int selected = c.getSelected() == null ? 0 : c.getSelected();
            m.put(c.getId(), new StockSnapshot.ClassStock(Math.max(0, capacity - selected), 0L, capacity));
        }
        return m;
    }

    /**
     * 受理制口径真值 = capacity − selected(DB) − Σ未决受理（pending）。
     *
     * <p>为什么必须带上 pending：受理先行、落库滞后。等待落库的每一笔受理都已从结构扣减，
     * 但 DB 的 selected 还没 +1——只按 DB 算真值会把它们当成"多扣的"纠回去（超卖窗口）。
     * 而不变量恒成立：<b>消费成功（DB selected+1 与 pending−1 同时发生）不改真值；
     * 受理（−1 与 pending+1 同时发生）也不改真值</b>——所以按本口径对账天然免疫在途噪音。</p>
     */
    public Map<Long, StockSnapshot.ClassStock> dbTruthWithPending(Long courseId) {
        Map<Long, StockSnapshot.ClassStock> m = dbTruth(courseId);
        Map<Long, Integer> pending = pendingCounts(courseId);
        if (pending.isEmpty()) {
            return m;
        }
        Map<Long, StockSnapshot.ClassStock> out = new LinkedHashMap<>();
        for (Map.Entry<Long, StockSnapshot.ClassStock> e : m.entrySet()) {
            StockSnapshot.ClassStock cs = e.getValue();
            int inFlight = Math.max(0, pending.getOrDefault(e.getKey(), 0));
            int remain = Math.max(0, cs.remain() - inFlight);
            out.put(e.getKey(), inFlight == 0 ? cs : new StockSnapshot.ClassStock(remain, cs.version(), cs.capacity()));
        }
        return out;
    }

    private StockSnapshot dbSnapshot(Long courseId, boolean degraded) {
        Map<Long, StockSnapshot.ClassStock> truth = dbTruthWithPending(courseId);
        return new StockSnapshot(0L, truth, degraded);
    }

    // ------------------------------------------------------------------
    // 阶段 21（受理制）：受理台账（pending）与用户已选集合（sel）的底层支持
    // ------------------------------------------------------------------

    /** 台账一条记录：delta 恒为 -1（选课）；ts=受理时刻；userId=落库/裁决所需 */
    public record PendEntry(Long classId, String opId, int delta, long ts, Long userId) {
    }

    /** 是否仍有未决受理（消费端进入处理前的"是否已被解决"判定） */
    public boolean pendingExists(Long courseId, Long classId, String opId) {
        try {
            return Boolean.TRUE.equals(redis.opsForHash()
                    .hasKey(CourseStockKeys.pend(courseId), CourseStockKeys.pendField(classId, opId)));
        } catch (Exception e) {
            // Redis 异常：保守视作"存在"（继续走正常落库；重放保护还有 op_id 一层）
            log.warn("[受理] 台账查询异常（视作存在）: course={} class={} {}", courseId, classId, e.getMessage());
            return true;
        }
    }

    /** 清账（落库成功 / 已生效重放）：幂等 */
    public void hdelPending(Long courseId, Long classId, String opId) {
        try {
            redis.opsForHash().delete(CourseStockKeys.pend(courseId), CourseStockKeys.pendField(classId, opId));
        } catch (Exception e) {
            log.warn("[受理] 台账清理失败（下轮对账/裁决兜底）: course={} {}", courseId, e.getMessage());
        }
    }

    /**
     * 清空整个课程台账（压测夹具语义，正常业务绝不用——正常路径是逐笔 hdelPending）。
     * 清空后"迟到消息"看到台账已空会按"已解决"跳过，不会再把名额落库。
     */
    public void pendClear(Long courseId) {
        try {
            redis.delete(CourseStockKeys.pend(courseId));
        } catch (Exception e) {
            log.warn("[受理] 台账清理失败: course={} {}", courseId, e.getMessage());
        }
    }

    /** 台账明细（超时裁决 / 观测用） */
    public List<PendEntry> pendingEntries(Long courseId) {
        List<PendEntry> out = new ArrayList<>();
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(CourseStockKeys.pend(courseId));
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String field = String.valueOf(e.getKey());
                int sep = field.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String[] p = String.valueOf(e.getValue()).split("\\|", 3);
                if (p.length < 3) {
                    continue;
                }
                out.add(new PendEntry(parseLong(field.substring(0, sep), -1L), field.substring(sep + 1),
                        (int) parseLong(p[0], -1L), parseLong(p[1], 0L), parseLong(p[2], -1L)));
            }
        } catch (Exception e) {
            log.warn("[受理] 台账读取失败: course={} {}", courseId, e.getMessage());
        }
        return out;
    }

    /** 每班未决受理数（对账口径用）：classId -> count（只统计 delta<0 的受理） */
    public Map<Long, Integer> pendingCounts(Long courseId) {
        Map<Long, Integer> counts = new HashMap<>();
        for (PendEntry e : pendingEntries(courseId)) {
            if (e.classId() > 0 && e.delta() != 0) {
                counts.merge(e.classId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 台账里全部未决受理数（压测收敛等待 / 观测用） */
    public int pendingTotal(Long courseId) {
        return pendingEntries(courseId).size();
    }

    /* ---- 用户已选集合（快速已选检查；全是加速器语义，失败只记日志）---- */

    public void selAdd(Long userId, Long classId) {
        try {
            redis.opsForSet().add(CourseStockKeys.sel(userId), String.valueOf(classId));
            redis.expire(CourseStockKeys.sel(userId), Duration.ofSeconds(SEL_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("[受理] 已选集合写入失败（下一轮惰性初始化兜底）: user={} {}", userId, e.getMessage());
        }
    }

    public void selRemove(Long userId, Long classId) {
        try {
            redis.opsForSet().remove(CourseStockKeys.sel(userId), String.valueOf(classId));
        } catch (Exception e) {
            log.warn("[受理] 已选集合移除失败: user={} class={} {}", userId, classId, e.getMessage());
        }
    }

    public void selClear(Long userId) {
        try {
            redis.delete(CourseStockKeys.sel(userId));
        } catch (Exception e) {
            log.warn("[受理] 已选集合清理失败: user={} {}", userId, e.getMessage());
        }
    }

    private static long parseLong(String s, long dft) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return dft;
        }
    }
}
