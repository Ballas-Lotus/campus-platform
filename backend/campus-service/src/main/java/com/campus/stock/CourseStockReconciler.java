package com.campus.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campus.course.dao.entity.CourseSelectionDO;
import com.campus.framework.lock.CampusLock;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 名额结构对账器（阶段 20 读侧自愈 → <b>阶段 21 受理制口径 + 超时裁决</b>，30 秒一轮）。
 *
 * <h3>对账口径（受理制的关键变化）</h3>
 * <p>真值 = {@code capacity − selected(DB) − Σ未决受理(pending)}（{@link CourseStockService#dbTruthWithPending}）。
 * <b>绝不能</b>按"capacity − selected(DB)"纠偏——等待落库的在途受理会被当成"多扣的"还回去 = 超卖。
 * 不变量：受理（结构 −1 与 pending +1 同时发生）与落库成功（DB +1 与 pending −1 同时发生）
 * 都不改变真值——因此本口径对在途噪音天然免疫，无需"pending 非空就跳过"这类特判。</p>
 *
 * <p>写回带<b>版本 CAS</b>：比对与写回之间若有受理/补偿发生（结构版本被 +1），本轮直接放弃——
 * 宁可漏纠一次（30 秒后再来），不可把并发的原子扣减覆盖回去。</p>
 *
 * <h3>超时裁决（受理制新增，兜住"消息丢失/进程崩溃"）</h3>
 * <pre>
 *   pending 超过时限（默认 5 分钟）仍未清账 → 按 (userId, classId) 查落库行：
 *     行内 op_id == 该受理  → 已生效（成功处理但清账前崩溃）→ 只清账
 *     否则                 → 未生效（消息丢失/处理进程死亡）→ 补偿 + 修正信号 + settled(false) 回执
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseStockReconciler {

    private static final String RECONCILE_LOCK = "campus:lock:stock:reconcile";
    private static final long LOCK_TTL_MS = 25_000L;

    private final StringRedisTemplate redis;
    private final CampusLock lock;
    private final CourseStockService stockService;
    private final CourseStockAcceptService acceptService;

    @Value("${campus.stock.reconcile-enabled:true}")
    private boolean enabled;

    @Value("${campus.stock.reconcile-max-keys:50}")
    private int maxKeys;

    /** 未决受理超时上限：超过即按 op_id 查库裁决（默认 5 分钟） */
    @Value("${campus.stock.pending-timeout-ms:300000}")
    private long pendingTimeoutMs;

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void reconcile() {
        if (!enabled) {
            return;
        }
        String token = lock.tryAcquire(RECONCILE_LOCK, 0, LOCK_TTL_MS);
        if (token == null) {
            return;
        }
        try {
            int checked = 0;
            for (String key : scanKeys(CourseStockKeys.PREFIX + "*")) {
                Long courseId = parseStockKey(key);
                if (courseId == null) {
                    continue;   // pend: / c2c 等非课程 key
                }
                checked++;
                reconcileOne(courseId);
            }
            if (checked > 0) {
                log.debug("[名额同步] 对账完成：{} 门课程", checked);
            }
            adjudicateExpiredPending();
        } catch (Exception e) {
            log.warn("[名额同步] 对账异常: {}", e.getMessage());
        } finally {
            lock.release(RECONCILE_LOCK, token);
        }
    }

    /** 结构 vs 受理制口径真值：偏差 → 版本 CAS 写回 + 修正信号（CAS 未命中=并发写者优先，弃权） */
    private void reconcileOne(Long courseId) {
        StockSnapshot cur = stockService.readL2(courseId);
        if (cur == null) {
            return;
        }
        Map<Long, StockSnapshot.ClassStock> truth = stockService.dbTruthWithPending(courseId);

        // ① 逐班比对：容量或余量不符 → 按真值写回（CAS）+ 版本 +1 + 推送修正
        for (Map.Entry<Long, StockSnapshot.ClassStock> e : truth.entrySet()) {
            Long classId = e.getKey();
            StockSnapshot.ClassStock want = e.getValue();
            StockSnapshot.ClassStock have = cur.classes().get(classId);
            if (have == null || have.remain() != want.remain() || have.capacity() != want.capacity()) {
                long expectedVer = have == null ? -1L : have.version();   // -1 = 要求字段仍缺失
                boolean applied = stockService.applyTruth(courseId, classId,
                        want.remain(), want.capacity(), expectedVer);
                if (applied) {
                    log.info("[名额同步] 纠偏 course={} class={}：{} → remain={} cap={}",
                            courseId, classId,
                            have == null ? "缺失" : ("remain=" + have.remain() + " cap=" + have.capacity()),
                            want.remain(), want.capacity());
                } else {
                    log.debug("[名额同步] 纠偏弃权（版本 CAS 未命中，并发受理/补偿优先） course={} class={}",
                            courseId, classId);
                }
            }
        }

        // ② 幽灵字段：结构里有、DB 里已不存在（教学班被删）→ 删除
        for (Long classId : cur.classes().keySet()) {
            if (!truth.containsKey(classId)) {
                redis.opsForHash().delete(CourseStockKeys.stock(courseId),
                        CourseStockKeys.capacityField(classId), CourseStockKeys.stockField(classId));
                log.info("[名额同步] 清理幽灵字段 course={} class={}", courseId, classId);
            }
        }
    }

    /**
     * 超时裁决：超出时限仍未清账的受理，按落库行 op_id 判定"已生效 / 未生效"。
     * 未生效 → 补偿（名额加回 + 版本 +1 + 修正信号）+ 条件清"已选集合" + settled(false) 回执。
     */
    private void adjudicateExpiredPending() {
        long now = System.currentTimeMillis();
        for (String key : scanKeys(CourseStockKeys.PEND_PREFIX + "*")) {
            Long courseId = parsePendKey(key);
            if (courseId == null) {
                continue;
            }
            for (CourseStockService.PendEntry e : stockService.pendingEntries(courseId)) {
                if (e.userId() == null || e.userId() <= 0 || e.classId() == null || e.classId() <= 0) {
                    continue;
                }
                if (now - e.ts() < pendingTimeoutMs) {
                    continue;
                }
                try {
                    if (e.opId().equals(acceptService.opIdOf(e.userId(), e.classId()))) {
                        // 已生效（落库成功但清账前进程死亡）：只清账
                        stockService.hdelPending(courseId, e.classId(), e.opId());
                        log.info("[受理·裁决] 超时受理已生效，仅清账: course={} class={} op={}",
                                courseId, e.classId(), e.opId());
                        continue;
                    }
                    int comp = acceptService.compensate(courseId, e.classId(), e.opId());
                    if (comp == CourseStockAcceptService.COMP_APPLIED) {
                        CourseSelectionDO row = acceptService.selectionOf(e.userId(), e.classId());
                        if (row == null || !"SELECTED".equals(row.getStatus())) {
                            stockService.selRemove(e.userId(), e.classId());
                        }
                        acceptService.settleNotify(e.userId(), e.classId(), false,
                                "系统未在时限内完成落库，本次选课未生效");
                        log.warn("[受理·裁决] 超时受理未生效，已补偿: course={} class={} op={} user={}",
                                courseId, e.classId(), e.opId(), e.userId());
                    }
                } catch (Exception ex) {
                    log.warn("[受理·裁决] 单条裁决失败（下轮重试）: op={} {}", e.opId(), ex.getMessage());
                }
            }
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> out = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern).count(200).build();
        try (Cursor<byte[]> cursor = redis.execute((org.springframework.data.redis.core.RedisCallback<Cursor<byte[]>>)
                connection -> connection.scan(options))) {
            if (cursor == null) {
                return out;
            }
            while (cursor.hasNext() && out.size() < maxKeys) {
                out.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("[名额同步] SCAN 失败: {}", e.getMessage());
        }
        return out;
    }

    /** campus:stock:{courseId} → courseId（后缀必须是纯数字；pend:/c2c 都含 ':' 被排除） */
    private static Long parseStockKey(String key) {
        if (key == null || !key.startsWith(CourseStockKeys.PREFIX)) {
            return null;
        }
        String suffix = key.substring(CourseStockKeys.PREFIX.length());
        if (suffix.indexOf(':') >= 0) {
            return null;
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return null;   // c2c / signal 等
        }
    }

    /** campus:stock:pend:{courseId} → courseId */
    private static Long parsePendKey(String key) {
        if (key == null || !key.startsWith(CourseStockKeys.PEND_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(CourseStockKeys.PEND_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
