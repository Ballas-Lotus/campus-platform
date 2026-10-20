package com.campus.cache;

import com.campus.common.result.Result;
import com.campus.course.dto.resp.CourseClassRespDTO;
import com.campus.course.service.CourseService;
import com.campus.framework.cache.CacheMetrics;
import com.campus.framework.cache.CampusBloomFilter;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.framework.cache.RedisCampusCache;
import com.campus.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存观测接口（阶段 16 + <b>19</b>）。入口：前端「🚀 缓存」页（/cache）。
 *
 * <p>阶段 19 新增：教学班读模型（静态信息 + 余量 Hash）的现场状态、预热、对账与毒化自愈演示，
 * 以及「写后失效 vs 写后增量」对照实验。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheController {

    private final RedisCampusCache cache;
    private final CacheMetrics cacheMetrics;
    private final CampusBloomFilter bloomFilter;
    private final ResourceService resourceService;
    /** 阶段 19：教学班读模型（余量 Hash） */
    private final ClassStockCache classStockCache;
    private final CourseService courseService;
    private final CacheWarmup cacheWarmup;

    /** 缓存全景：命中率/回源/击穿/穿透 + 布隆过滤器状态 + 规则表 + 现存 key 采样 + 读模型指标 */
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metrics", cacheMetrics.snapshot());
        m.put("bloom", bloomFilter.stats(CacheWarmup.BLOOM_RESOURCE));
        m.put("rules", CampusCacheKeys.rules());
        m.put("keys", cache.sampleKeys(40));
        m.put("stock", classStockCache.metricsSnapshot());
        return Result.success(m);
    }

    /**
     * 「有缓存 vs 无缓存」对照：同一读路径各跑 N 轮，返回均值与 P95。
     *
     * <p>口径说明：先失效该 key 再测，保证"无缓存"与"有缓存"面对同一起点；
     * 有缓存的第 1 轮是回源（写入缓存），第 2 轮起才是纯命中——这正是真实流量形态。</p>
     */
    @PostMapping("/demo")
    public Result<Map<String, Object>> demo(@RequestParam(defaultValue = "slots") String type,
                                            @RequestParam(required = false) Long resourceId,
                                            @RequestParam(required = false) String date,
                                            @RequestParam(defaultValue = "20") int rounds) {
        if ("class_stock".equalsIgnoreCase(type)) {
            if (resourceId == null) {
                throw new com.campus.common.exception.ClientException(
                        "教学班读模型对照需要 courseId（用 resourceId 参数传入）",
                        com.campus.common.errorcode.BaseErrorCode.PARAM_INVALID);
            }
            return Result.success(classStockDemo(resourceId, rounds));
        }
        boolean overviewMode = "overview".equalsIgnoreCase(type);
        if (!overviewMode && (resourceId == null || date == null)) {
            throw new com.campus.common.exception.ClientException(
                    "slots 对照需要 resourceId 与 date 参数", com.campus.common.errorcode.BaseErrorCode.PARAM_INVALID);
        }
        String note;
        java.util.function.Supplier<Object> direct;
        java.util.function.Supplier<Object> cachedCall;
        if (overviewMode) {
            cache.evict(CampusCacheKeys.overviewKey());
            direct = resourceService::overviewDirect;
            cachedCall = resourceService::overview;
            note = "「无缓存」= 每次执行 6 次 count 聚合（跨广播表/分片表）；「有缓存」= 首轮聚合后命中 Redis";
        } else {
            LocalDate d = LocalDate.parse(date);
            cache.evict(CampusCacheKeys.slots(resourceId, d));
            direct = () -> resourceService.slotsDirect(resourceId, d);
            cachedCall = () -> resourceService.slots(resourceId, d);
            note = "「无缓存」= 每次查库（分片 SQL + 结果集归并）；「有缓存」= 首轮回源，其余命中 Redis";
        }

        List<Long> noCache = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            direct.get();
            noCache.add((System.nanoTime() - t0) / 1_000_000);
        }
        List<Long> cached = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            cachedCall.get();
            cached.add((System.nanoTime() - t0) / 1_000_000);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", overviewMode ? "overview" : "slots");
        m.put("resourceId", resourceId);
        m.put("date", date);
        m.put("rounds", rounds);
        m.put("noCache", stat(noCache));
        m.put("cache", stat(cached));
        m.put("note", note);
        return Result.success(m);
    }

    /**
     * 布隆过滤器探针：传一个资源 id，看是否在"全集"中。
     *
     * <p>传一个不存在的 id（如 99999999）即可现场看到穿透被拦截——响应里带耗时，
     * 可以看到拦截发生在"不查库"的前提下。</p>
     */
    @GetMapping("/bloom/probe")
    public Result<Map<String, Object>> bloomProbe(@RequestParam Long resourceId) {
        long t0 = System.nanoTime();
        boolean maybe = bloomFilter.mightContain(CacheWarmup.BLOOM_RESOURCE, String.valueOf(resourceId));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resourceId", resourceId);
        m.put("maybeExist", maybe);
        m.put("conclusion", maybe ? "可能存在（放行到缓存/DB 确认）" : "一定不存在（直接拒绝，未查库未查缓存）");
        m.put("ms", ms);
        return Result.success(m);
    }

    /** 阶段 19：抢课窗口预热——不传 courseId 则预热热门课程（按教学班总容量前 N） */
    @PostMapping("/warmup")
    public Result<Map<String, Object>> warmup(@RequestParam(required = false) Long courseId) {
        return Result.success(courseId == null ? cacheWarmup.warmHotCourses() : cacheWarmup.warmCourse(courseId));
    }

    /** 阶段 19：教学班读模型现场（余量 Hash vs DB 真值，逐班偏差） */
    @GetMapping("/stock/state")
    public Result<Map<String, Object>> stockState(@RequestParam Long courseId) {
        return Result.success(classStockCache.state(courseId));
    }

    /** 阶段 19：手动对账一轮（把余量 Hash 拉回 DB 真值；自动对账每 30 秒也在跑） */
    @PostMapping("/stock/reconcile")
    public Result<Map<String, Object>> stockReconcile() {
        return Result.success(classStockCache.reconcile(50));
    }

    /** 阶段 19（演示）：把某教学班的缓存余量"毒化"成错误值——刷新状态看偏差 → 对账自愈 */
    @PostMapping("/stock/poison")
    public Result<Map<String, Object>> stockPoison(@RequestParam Long courseId,
                                                   @RequestParam Long classId,
                                                   @RequestParam(required = false) Integer value) {
        return Result.success(classStockCache.poison(courseId, classId, value));
    }

    /** 手动失效（演示/排障用）：scope = slots | resources | courses | classes | lectures | overview | all */
    @PostMapping("/evict")
    public Result<Map<String, Object>> evict(@RequestParam String scope,
                                             @RequestParam(required = false) Long resourceId,
                                             @RequestParam(required = false) String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (scope) {
            case "slots" -> {
                if (resourceId == null) {
                    m.put("removed", cache.evictByPrefix(CampusCacheKeys.slotsPrefix(null) + ""));
                    break;
                }
                if (date == null) {
                    m.put("removed", cache.evictByPrefix(CampusCacheKeys.slotsPrefix(resourceId)));
                } else {
                    cache.evict(CampusCacheKeys.slots(resourceId, LocalDate.parse(date)));
                    m.put("removed", 1);
                }
            }
            case "resources" -> m.put("removed", cache.evictByPrefix(CampusCacheKeys.resourceListPrefix()));
            case "courses" -> m.put("removed", cache.evictByPrefix(CampusCacheKeys.courseListPrefix()));
            case "classes" -> {
                long removed = cache.evictByPrefix(CampusCacheKeys.courseClassesPrefix());
                removed += cache.evictByPrefix(CampusCacheKeys.classStockPrefix());   // 阶段 19：静态信息 + 余量 Hash
                m.put("removed", removed);
            }
            case "lectures" -> m.put("removed", cache.evictByPrefix(CampusCacheKeys.lecturesPrefix()));
            case "overview" -> {
                cache.evict(CampusCacheKeys.overviewKey());
                m.put("removed", 1);
            }
            case "all" -> m.put("removed", cache.evictByPrefix(CampusCacheKeys.PREFIX));
            default -> throw new com.campus.common.exception.ClientException(
                    "未知失效范围: " + scope, com.campus.common.errorcode.BaseErrorCode.PARAM_INVALID);
        }
        m.put("scope", scope);
        return Result.success(m);
    }

    /**
     * 阶段 19 对照实验：教学班读模型「写后整键失效（旧）vs 写后增量（新）」各跑 N 轮。
     *
     * <p>A 组：每次"写"后把静态信息 + 余量 Hash 都失效 → 下一次读全程回源；
     * B 组：写后对余量 Hash HINCRBY（真实写路径的做法，-1 后读、+1 还原）→ 读全部命中，
     * 且显示值写后<b>立即可见</b>。两组共用同一读路径（含每次"我的已选"实时轻查询——
     * 用户态分离，不计入缓存回源口径）。</p>
     */
    private Map<String, Object> classStockDemo(Long courseId, int roundsParam) {
        int rounds = Math.max(5, Math.min(roundsParam, 100));
        List<CourseClassRespDTO> classes = courseService.classes(courseId);
        if (classes.isEmpty()) {
            throw new com.campus.common.exception.ClientException(
                    "该课程没有教学班，换一个 courseId", com.campus.common.errorcode.BaseErrorCode.PARAM_INVALID);
        }
        // 增量演示选一个"有余量"的教学班——余量为 0 的班做 -1 会被展示层钳到 0，看不出"写后立即可见"
        Long classId = classes.get(0).getId();
        for (CourseClassRespDTO c : classes) {
            if (c.getAvailable() != null && c.getAvailable() > 0) {
                classId = c.getId();
                break;
            }
        }

        // A 组：写后整键失效（阶段 16 行为）→ 每次读都回源
        List<Long> evictMs = new ArrayList<>(rounds);
        long reload0 = cacheMetrics.reloadCount();
        for (int i = 0; i < rounds; i++) {
            classStockCache.evict(courseId);
            cache.evict(CampusCacheKeys.courseClasses(courseId));
            long t0 = System.nanoTime();
            courseService.classes(courseId);
            evictMs.add((System.nanoTime() - t0) / 1_000_000);
        }
        long evictReloads = cacheMetrics.reloadCount() - reload0;

        // B 组：先预热（保证 info+stock 就位）；写后增量（-1 → 读 → +1 还原，净变化 0）
        courseService.classes(courseId);
        Integer hashBefore = hashField(courseId, classId);
        List<Long> incrMs = new ArrayList<>(rounds);
        long reload1 = cacheMetrics.reloadCount();
        Integer displayedAfterWrite = null;
        for (int i = 0; i < rounds; i++) {
            classStockCache.applyDelta(courseId, classId, -1);
            long t0 = System.nanoTime();
            List<CourseClassRespDTO> read = courseService.classes(courseId);
            incrMs.add((System.nanoTime() - t0) / 1_000_000);
            if (i == 0) {
                for (CourseClassRespDTO dto : read) {
                    if (dto.getId().equals(classId)) {
                        displayedAfterWrite = dto.getAvailable();
                    }
                }
            }
            classStockCache.applyDelta(courseId, classId, 1);
        }
        long incrReloads = cacheMetrics.reloadCount() - reload1;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "class_stock");
        m.put("courseId", courseId);
        m.put("classId", classId);
        m.put("rounds", rounds);
        Map<String, Object> a = stat(evictMs);
        a.put("name", "A · 写后整键失效（阶段 16）");
        a.put("reloads", evictReloads);
        m.put("evictStrategy", a);
        Map<String, Object> b = stat(incrMs);
        b.put("name", "B · 写后增量维护（阶段 19）");
        b.put("reloads", incrReloads);
        m.put("incrStrategy", b);
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("classId", classId);
        sample.put("hashBefore", hashBefore);
        sample.put("displayedAfterWrite", displayedAfterWrite);
        sample.put("note", "B 组第一轮：写后增量 -1 → 读到的 available 立即可见（A 组每次读都要回源才能看到）");
        m.put("sample", sample);
        m.put("note", "A=写后失效→每次读全程回源；B=写后 HINCRBY→读全部命中（pipeline 一次往返）。"
                + "两组都含每次读的『我的已选』实时轻查询（用户态分离，不计入缓存回源口径）");
        return m;
    }

    @SuppressWarnings("unchecked")
    private Integer hashField(Long courseId, Long classId) {
        Object raw = classStockCache.state(courseId).get("classes");
        if (!(raw instanceof List<?> rows)) {
            return null;
        }
        for (Object o : rows) {
            Map<String, Object> row = (Map<String, Object>) o;
            if (classId.equals(((Number) row.get("classId")).longValue())) {
                Object v = row.get("hashAvailable");
                return v == null ? null : ((Number) v).intValue();
            }
        }
        return null;
    }

    private Map<String, Object> stat(List<Long> times) {
        List<Long> sorted = new ArrayList<>(times);
        sorted.sort(Long::compareTo);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgMs", Math.round(avg * 100) / 100.0);
        m.put("p95Ms", percentile(sorted, 0.95));
        m.put("minMs", sorted.isEmpty() ? 0 : sorted.get(0));
        m.put("maxMs", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
        return m;
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
