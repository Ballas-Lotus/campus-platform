package com.campus.cache;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dto.resp.CourseClassRespDTO;
import com.campus.framework.cache.CacheJson;
import com.campus.framework.cache.CacheMetrics;
import com.campus.framework.cache.CampusCache;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.framework.lock.CampusLock;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * <b>教学班"读模型"（阶段 19）——把"会变的余量"从静态缓存里拆出来。</b>
 *
 * <h3>为什么拆（吸收 12306 的读模型思路）</h3>
 * <p>阶段 16 的整列表缓存（含 selected/available）有两个问题：① 余量变化最快，却挤在
 * "名称/教师/时间"这类几乎不变的数据里，只能共享一个短 TTL；② 每次写都整键失效 →
 * 下一次读全程回源，写密集时缓存持续被打穿。拆法（与 12306 "余票读模型"同款思路）：</p>
 * <ul>
 *   <li><b>静态信息</b>（{@link CampusCacheKeys#courseClasses}，TTL 10 分钟）——选课/退课<b>不失效它</b>；</li>
 *   <li><b>动态余量</b>（{@link CampusCacheKeys#classStock}，Hash：field=classId → 剩余名额，
 *       TTL 120 秒）——写路径<b>提交后 HINCRBY 增量维护</b>（不是删！），读路径一次 pipeline 合并两者。</li>
 * </ul>
 *
 * <h3>三条防线（与预扣闸门同款结构）</h3>
 * <ol>
 *   <li><b>增量维护</b>（{@link #applyDelta}）：Lua "key 存在才 HINCRBY"——key 已过期则跳过，
 *       由下一次读按 DB 真值重建（绝不出现"只含一个 delta 字段"的残缺 Hash）；</li>
 *   <li><b>miss 重建</b>（{@link #rebuild}）：分布式锁 + 双检，单线程按 DB 真值回填；
 *       班级集合变化（新增教学班）也会触发重建；</li>
 *   <li><b>对账自愈</b>（{@link #reconcile}）：每 30 秒抽样比对 Hash 与 DB 真值，纠偏/清理幽灵字段。</li>
 * </ol>
 *
 * <h3>诚实的边界（写进 docs/13）</h3>
 * <p>增量与对账并发时存在极小窗口的竞态（提交后 HINCRBY 与对账 HSET 交错），最坏引入 ±1 的
 * <b>显示</b>偏差——下一轮对账或 TTL 到期重建即收敛；<b>闸门判定与数据库真相不受影响</b>
 * （余量 Hash 只是展示读模型，裁决仍在预扣 Lua 与 MySQL 条件更新）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassStockCache {

    private final StringRedisTemplate redis;
    private final CampusLock lock;
    private final CacheMetrics metrics;
    private final CampusCache cache;
    private final CourseClassMapper courseClassMapper;

    /** 增量维护开关（对照实验/排障可关：关闭后退化为"写后失效整键"——阶段 16 行为） */
    @Value("${campus.cache.class-stock.incremental-enabled:true}")
    private boolean incrementalEnabled;

    private static final String LOCK_PREFIX = "campus:cache:lock:";
    private static final String RECONCILE_LOCK = "campus:lock:cache:stock:reconcile";
    private static final long LOCK_WAIT_MS = 200;
    private static final long LOCK_TTL_MS = 3_000;
    private static final long LOCK_LOSER_SLEEP_MS = 40;

    /** Lua：key 存在才增量（避免"过期窗口里写出一个只剩 delta 的残缺 Hash"） */
    private static final DefaultRedisScript<Long> INCR_IF_EXISTS_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('HINCRBY', KEYS[1], ARGV[1], tonumber(ARGV[2]))
              return 1
            end
            return 0
            """, Long.class);

    /** 一次合并读的结果：静态信息 + 动态余量（{@code pipelined}=true 表示走了一次 pipeline 往返） */
    public record ReadModel(List<CourseClassRespDTO> info, Map<Long, Integer> stock, boolean pipelined) {
    }

    // ------------------------------------------------------------------
    // 读：pipeline 合并（静态信息 + 余量 Hash）
    // ------------------------------------------------------------------

    /**
     * 读教学班读模型：一次 pipeline 取回静态信息与余量 Hash。
     *
     * <p>命中路径 1 次往返；静态信息 miss 走 {@link CampusCache#safeGet}（防击穿语义不变），
     * 余量缺失/不完整（新班级）走 {@link #rebuild}。</p>
     */
    public ReadModel read(Long courseId, TypeReference<List<CourseClassRespDTO>> type,
                          Supplier<List<CourseClassRespDTO>> infoLoader) {
        String infoKey = CampusCacheKeys.courseClasses(courseId);
        String stockKey = CampusCacheKeys.classStock(courseId);

        long t0 = System.nanoTime();
        String infoJson = null;
        Map<Long, Integer> stock = null;
        boolean pipelined = false;
        try {
            List<Object> raw = redis.executePipelined((RedisCallback<Object>) connection -> {
                connection.stringCommands().get(infoKey.getBytes(StandardCharsets.UTF_8));
                connection.hashCommands().hGetAll(stockKey.getBytes(StandardCharsets.UTF_8));
                return null;
            });
            if (raw != null && raw.size() >= 2) {
                infoJson = asString(raw.get(0));
                stock = asStockMap(raw.get(1));
                pipelined = true;
                metrics.record(infoKey, "PIPE", elapsedMs(t0));
            }
        } catch (Exception e) {
            log.warn("[读模型] pipeline 合并读失败（回退逐条读）: course={} {}", courseId, e.getMessage());
            metrics.record(infoKey, "ERROR", elapsedMs(t0));
        }

        List<CourseClassRespDTO> info;
        if (CampusCacheKeys.NULL_MARKER.equals(infoJson)) {
            metrics.record(infoKey, "HIT", elapsedMs(t0));       // 空值命中（防穿透）
            info = List.of();
        } else if (infoJson != null) {
            List<CourseClassRespDTO> decoded = null;
            try {
                decoded = CacheJson.read(infoJson, type);
                metrics.record(infoKey, "HIT", elapsedMs(t0));
            } catch (Exception e) {
                metrics.record(infoKey, "ERROR", elapsedMs(t0));
                log.warn("[读模型] 静态信息反序列化失败（回退回源）: key={} {}", infoKey, e.getMessage());
            }
            info = decoded;
            if (info == null) {
                info = safeLoadInfo(infoKey, type, infoLoader);
            }
        } else {
            info = safeLoadInfo(infoKey, type, infoLoader);
        }

        if (info.isEmpty()) {
            return new ReadModel(info, Map.of(), pipelined);
        }
        List<Long> ids = info.stream().map(CourseClassRespDTO::getId).toList();
        if (stock == null || !stock.keySet().containsAll(ids)) {
            stock = rebuild(courseId);
            if (!stock.keySet().containsAll(ids)) {
                stock = loadFromDb(courseId);       // 最终兜底：本次直接用 DB 真值（不写缓存）
            }
        }
        return new ReadModel(info, stock, pipelined);
    }

    private List<CourseClassRespDTO> safeLoadInfo(String infoKey, TypeReference<List<CourseClassRespDTO>> type,
                                                  Supplier<List<CourseClassRespDTO>> infoLoader) {
        List<CourseClassRespDTO> loaded = cache.safeGet(infoKey, type, CampusCacheKeys.TTL_CLASS_INFO, infoLoader);
        return loaded == null ? List.of() : loaded;
    }

    // ------------------------------------------------------------------
    // 写：提交后增量维护
    // ------------------------------------------------------------------

    /**
     * 写路径增量更新（选课 delta=-1 / 退课 delta=+1，在 {@code afterCommit} 里调用）。
     *
     * <p>三条降级：开关关闭 → 失效重建；key 不存在（已过期）→ 跳过（下次读重建）；
     * Redis 异常 → 失效重建。任何一条都不会让写路径失败。</p>
     */
    public void applyDelta(Long courseId, Long classId, int delta) {
        String stockKey = CampusCacheKeys.classStock(courseId);
        if (!incrementalEnabled) {
            evict(courseId);
            return;
        }
        try {
            Long r = redis.execute(INCR_IF_EXISTS_SCRIPT, List.of(stockKey),
                    String.valueOf(classId), String.valueOf(delta));
            if (r != null && r == 1L) {
                metrics.record(stockKey, "STOCK_INCR", 0);
            } else {
                metrics.record(stockKey, "STOCK_SKIP", 0);   // key 已过期：不重建（读时按真值重建更准）
            }
        } catch (Exception e) {
            metrics.record(stockKey, "ERROR", 0);
            log.warn("[读模型] 增量更新失败，回退为失效重建: key={} {}", stockKey, e.getMessage());
            evict(courseId);
        }
    }

    /** 失效余量 Hash（测试夹具重置/管理操作；下一位读者按 DB 真值重建） */
    public void evict(Long courseId) {
        String stockKey = CampusCacheKeys.classStock(courseId);
        try {
            redis.delete(stockKey);
            metrics.record(stockKey, "EVICT", 0);
        } catch (Exception e) {
            log.warn("[读模型] 失效失败（等 TTL 自然过期）: key={} {}", stockKey, e.getMessage());
        }
    }

    /** 按 DB 真值重建余量 Hash（分布式锁 + 双检；预热/读 miss/班级集合变化时调用） */
    public Map<Long, Integer> rebuild(Long courseId) {
        String stockKey = CampusCacheKeys.classStock(courseId);
        String token = lock.tryAcquire(LOCK_PREFIX + stockKey, LOCK_WAIT_MS, LOCK_TTL_MS);
        if (token == null) {
            // 未抢到：等 40ms 后重读（可能已被重建）；仍空则直接用 DB 真值（本线程不回写）
            sleep(LOCK_LOSER_SLEEP_MS);
            Map<Long, Integer> cur = readStockDirect(stockKey);
            return cur.isEmpty() ? loadFromDb(courseId) : cur;
        }
        try {
            Map<Long, Integer> cur = readStockDirect(stockKey);
            Map<Long, Integer> truth = loadFromDb(courseId);   // 重建本来就要查库：一次查询同时用于"双检"与回填
            if (!cur.isEmpty() && cur.keySet().equals(truth.keySet())) {
                return cur;                                     // 双检：他人已按同一份真值建好
            }
            if (truth.isEmpty()) {
                redis.delete(stockKey);                         // 课程没有教学班：不留 key
                return truth;
            }
            Map<String, String> hash = new LinkedHashMap<>();
            truth.forEach((k, v) -> hash.put(String.valueOf(k), String.valueOf(v)));
            redis.opsForHash().putAll(stockKey, hash);
            redis.expire(stockKey, Duration.ofSeconds(CampusCacheKeys.TTL_CLASS_STOCK));
            metrics.record(stockKey, "STOCK_REBUILD", 0);
            log.info("[读模型] 余量 Hash 重建 {}：{} 个教学班（TTL {}s）",
                    stockKey, truth.size(), CampusCacheKeys.TTL_CLASS_STOCK);
            return truth;
        } catch (Exception e) {
            metrics.record(stockKey, "ERROR", 0);
            log.warn("[读模型] 重建失败（本次直接用 DB 真值）: course={} {}", courseId, e.getMessage());
            return loadFromDb(courseId);
        } finally {
            lock.release(LOCK_PREFIX + stockKey, token);
        }
    }

    // ------------------------------------------------------------------
    // 对账自愈：Hash 与 DB 真值逐字段比对
    // ------------------------------------------------------------------

    /**
     * 对账一轮：SCAN 现存余量 Hash（最多 maxKeys 个），逐字段与 DB 真值比对——
     * 缺失/漂移字段 HSET 拉回真值，DB 中已不存在的字段（幽灵班级）HDEL，整课无班级则删 key。
     */
    public Map<String, Object> reconcile(int maxKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        String token = lock.tryAcquire(RECONCILE_LOCK, 0, 25_000);
        if (token == null) {
            result.put("skipped", "另一实例正在对账（分布式锁）");
            return result;
        }
        int checked = 0;
        int fixed = 0;
        int scanned = 0;
        try {
            for (String key : sampleStockKeys(maxKeys)) {
                scanned++;
                Long courseId = parseCourseId(key);
                if (courseId == null) {
                    continue;
                }
                Map<Long, Integer> truth = loadFromDb(courseId);
                if (truth.isEmpty()) {
                    redis.delete(key);                          // 课程已无教学班：清掉幽灵 Hash
                    fixed++;
                    metrics.record(key, "STOCK_FIX", 0);
                    continue;
                }
                Map<Long, Integer> hash = readStockDirect(key);
                checked++;
                boolean changed = false;
                for (Map.Entry<Long, Integer> e : truth.entrySet()) {
                    Integer cur = hash.get(e.getKey());
                    if (cur == null || !cur.equals(e.getValue())) {
                        redis.opsForHash().put(key, String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        changed = true;
                        log.warn("[读模型·对账] 纠偏 {} field={}：{} → DB 真值 {}",
                                key, e.getKey(), cur == null ? "(缺失)" : cur, e.getValue());
                    }
                }
                for (Long ghostId : hash.keySet()) {
                    if (!truth.containsKey(ghostId)) {
                        redis.opsForHash().delete(key, String.valueOf(ghostId));
                        changed = true;
                        log.warn("[读模型·对账] 清理幽灵字段 {} field={}", key, ghostId);
                    }
                }
                if (changed) {
                    fixed++;
                    metrics.record(key, "STOCK_FIX", 0);
                }
            }
            metrics.countStockChecked(checked);
        } catch (Exception e) {
            log.warn("[读模型·对账] 异常: {}", e.getMessage());
        } finally {
            lock.release(RECONCILE_LOCK, token);
        }
        result.put("scanned", scanned);
        result.put("checked", checked);
        result.put("fixed", fixed);
        return result;
    }

    // ------------------------------------------------------------------
    // 观测 / 演示
    // ------------------------------------------------------------------

    /** 现场状态：每个教学班的 Hash 余量 vs DB 真值 vs 偏差（前端「🧩 教学班读模型」卡片） */
    public Map<String, Object> state(Long courseId) {
        String stockKey = CampusCacheKeys.classStock(courseId);
        Map<Long, Integer> hash = readStockDirect(stockKey);
        Map<Long, Integer> truth = loadFromDb(courseId);
        Long ttl = null;
        try {
            ttl = redis.getExpire(stockKey, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            /* 观测失败不抛 */
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Long> ids = new java.util.TreeSet<>();
        ids.addAll(truth.keySet());
        ids.addAll(hash.keySet());
        for (Long id : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("classId", id);
            Integer h = hash.get(id);
            Integer t = truth.get(id);
            row.put("hashAvailable", h);
            row.put("dbAvailable", t);
            row.put("drift", (h == null || t == null) ? null : h - t);
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseId", courseId);
        m.put("key", stockKey);
        m.put("hashExists", !hash.isEmpty());
        m.put("ttlSeconds", ttl == null || ttl < 0 ? null : ttl);
        m.put("incrementalEnabled", incrementalEnabled);
        m.put("classes", rows);
        m.put("note", "余量 Hash 是展示读模型：写路径 HINCRBY 增量维护，30 秒对账纠偏；裁决仍在预扣闸门与 MySQL 条件更新");
        return m;
    }

    /** 演示：把某个教学班的缓存余量"毒化"成错误值，现场看偏差与对账自愈 */
    public Map<String, Object> poison(Long courseId, Long classId, Integer value) {
        String stockKey = CampusCacheKeys.classStock(courseId);
        if (!Boolean.TRUE.equals(redis.hasKey(stockKey))) {
            // 余量 Hash 可能刚过期（TTL 120s）：先按 DB 真值重建，保证"随时可毒化"的演示体验
            Map<Long, Integer> rebuilt = rebuild(courseId);
            if (rebuilt.isEmpty()) {
                throw new ClientException("该课程没有教学班：无需毒化", BaseErrorCode.PARAM_INVALID);
            }
        }
        // 不传 value 时默认毒化成"真值 + 5"——保证一定是一个肉眼可见的错误值（余量为 0 的班也能演示）
        int target = value != null ? value
                : loadFromDb(courseId).getOrDefault(classId, 0) + 5;
        Object before = redis.opsForHash().get(stockKey, String.valueOf(classId));
        redis.opsForHash().put(stockKey, String.valueOf(classId), String.valueOf(target));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseId", courseId);
        m.put("classId", classId);
        m.put("before", before == null ? null : Integer.parseInt(String.valueOf(before)));
        m.put("after", target);
        m.put("note", "缓存余量已被改错 → 刷新状态看偏差 → 点『立即对账』恢复真值（≤30 秒自动对账也会纠）");
        return m;
    }

    public Map<String, Object> metricsSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incrementalEnabled", incrementalEnabled);
        m.put("ttlSeconds", CampusCacheKeys.TTL_CLASS_STOCK);
        return m;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<Long, Integer> loadFromDb(Long courseId) {
        List<CourseClassDO> classes = courseClassMapper.selectList(Wrappers.lambdaQuery(CourseClassDO.class)
                .eq(CourseClassDO::getCourseId, courseId)
                .orderByAsc(CourseClassDO::getId));
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (CourseClassDO c : classes) {
            m.put(c.getId(), Math.max(0, c.getCapacity() - c.getSelected()));
        }
        return m;
    }

    private Map<Long, Integer> readStockDirect(String stockKey) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(stockKey);
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                try {
                    m.put(Long.parseLong(String.valueOf(e.getKey())), Integer.parseInt(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignored) {
                    /* 非法字段：跳过（对账会清理） */
                }
            }
        } catch (Exception e) {
            log.warn("[读模型] 读取余量 Hash 失败（按未命中处理）: key={} {}", stockKey, e.getMessage());
        }
        return m;
    }

    /** 现有余量 Hash 采样（对账/观测用） */
    public List<String> sampleStockKeys(int limit) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(CampusCacheKeys.classStockPrefix() + "*").count(Math.max(limit, 50)).build())) {
            while (cursor.hasNext() && keys.size() < limit) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("[读模型] 采样 key 失败: {}", e.getMessage());
        }
        return keys;
    }

    private Long parseCourseId(String stockKey) {
        try {
            return Long.parseLong(stockKey.substring(CampusCacheKeys.classStockPrefix().length()));
        } catch (Exception e) {
            return null;
        }
    }

    /** pipeline 结果归一化：兼容 String / byte[] / Map（不同序列化路径） */
    @SuppressWarnings("unchecked")
    private String asString(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s;
        }
        if (o instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return String.valueOf(o);
    }

    private Map<Long, Integer> asStockMap(Object o) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        if (!(o instanceof Map<?, ?> map)) {
            return m;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            try {
                m.put(Long.parseLong(asString(e.getKey())), Integer.parseInt(asString(e.getValue())));
            } catch (NumberFormatException ignored) {
                /* 非法字段：跳过 */
            }
        }
        return m;
    }

    private long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供测试/演示用：当前余量字段集合是否覆盖给定班级（不额外查库） */
    public boolean covers(Long courseId, Collection<Long> ids) {
        return readStockDirect(CampusCacheKeys.classStock(courseId)).keySet().containsAll(ids);
    }
}
