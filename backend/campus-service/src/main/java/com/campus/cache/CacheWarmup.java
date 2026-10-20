package com.campus.cache;

import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dto.resp.CourseClassRespDTO;
import com.campus.course.service.CourseService;
import com.campus.framework.cache.CampusBloomFilter;
import com.campus.framework.cache.CampusCache;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存预热（阶段 16 + <b>19</b>）。
 *
 * <ul>
 *   <li>阶段 16：把资源 id 全集灌进布隆过滤器（防穿透的入口拦截）；</li>
 *   <li>阶段 19：<b>抢课窗口预热</b>（吸收 12306 的桶预热/懒加载思想）——把"热门课程"的
 *       静态信息与余量 Hash 提前灌进 Redis：开抢时第一波读全部命中、零回源，
 *       且余量 Hash 在窗口期由写路径增量维护、对账兜底。</li>
 * </ul>
 *
 * <p><b>每次启动重建</b>（布隆 reset + 重新 add）：布隆是不可删除集合，与"资源会被增删"天然冲突，
 * 重建是唯一干净的对齐方式；热门课程预热则是幂等写入（重复执行只刷新 TTL 与真值）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmup implements ApplicationRunner {

    /** 布隆过滤器名称（resource id 全集） */
    public static final String BLOOM_RESOURCE = "resource";
    /** 预估元素数（当前约 3.4 千；留 5 倍余量）与期望误判率（1%） */
    private static final long EXPECTED_INSERTIONS = 20_000;
    private static final double FPP = 0.01;

    private final ResourceMapper resourceMapper;
    private final CampusBloomFilter bloomFilter;
    private final CourseService courseService;
    private final ClassStockCache classStockCache;
    /** 阶段 20：名额实时同步结构（读侧）——预热时一并重建，避免开抢第一波走慢路径 */
    private final com.campus.stock.CourseStockService courseStockService;
    private final CampusCache cache;
    private final CourseClassMapper courseClassMapper;

    /** 热门课程预热数量（按教学班总容量排序取前 N） */
    @Value("${campus.cache.warmup.hot-limit:20}")
    private int hotLimit;
    /** 可选：显式指定要预热的课程 id（逗号分隔）；为空则按总容量取前 N */
    @Value("${campus.cache.warmup.hot-course-ids:}")
    private String hotCourseIdsConfig;

    @Override
    public void run(ApplicationArguments args) {
        warmBloom();
        try {
            Map<String, Object> r = warmHotCourses();
            log.info("[缓存预热] 抢课窗口预热完成：课程 {} 门、余量字段 {} 个，耗时 {} ms（{}）",
                    r.get("courses"), r.get("totalClasses"), r.get("elapsedMs"), r.get("mode"));
        } catch (Exception e) {
            log.warn("[缓存预热] 热门课程预热失败（降级：按需懒加载）: {}", e.getMessage());
        }
    }

    private void warmBloom() {
        try {
            long t0 = System.currentTimeMillis();
            bloomFilter.reset(BLOOM_RESOURCE);
            bloomFilter.init(BLOOM_RESOURCE, EXPECTED_INSERTIONS, FPP);
            List<ResourceDO> all = resourceMapper.selectList(null);
            // 批量灌入（分块 Lua，避免 3000+ 次网络往返把启动拖慢十几秒）
            bloomFilter.addAll(BLOOM_RESOURCE, all.stream().map(r -> String.valueOf(r.getId())).toList());
            log.info("[缓存预热] 布隆过滤器重建完成：资源 id {} 个，耗时 {} ms（不存在的 id 将在入口被拦，不查库）",
                    all.size(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[缓存预热] 布隆过滤器预热失败（降级：不启用布隆拦截）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 抢课窗口预热（阶段 19）
    // ------------------------------------------------------------------

    /** 预热一批热门课程（前端「🧩 教学班读模型」卡片 / 启动时调用） */
    public Map<String, Object> warmHotCourses() {
        List<Long> ids = resolveHotCourseIds();
        long t0 = System.currentTimeMillis();
        int warmed = 0;
        int totalClasses = 0;
        List<Long> failed = new ArrayList<>();
        for (Long courseId : ids) {
            try {
                Map<String, Object> one = warmCourse(courseId);
                warmed++;
                totalClasses += ((Number) one.get("classes")).intValue();
            } catch (Exception e) {
                failed.add(courseId);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", StringUtils.hasText(hotCourseIdsConfig) ? "显式指定（campus.cache.warmup.hot-course-ids）"
                : "按教学班总容量取前 " + hotLimit + " 门");
        m.put("courses", warmed);
        m.put("totalClasses", totalClasses);
        m.put("failed", failed);
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        m.put("note", "预热 = 静态信息（TTL " + CampusCacheKeys.TTL_CLASS_INFO + "s）+ 余量 Hash（TTL "
                + CampusCacheKeys.TTL_CLASS_STOCK + "s，写路径增量维护）");
        return m;
    }

    /** 预热单门课程：静态信息 + 余量 Hash（幂等，可随时手动触发） */
    public Map<String, Object> warmCourse(Long courseId) {
        long t0 = System.currentTimeMillis();
        cache.getDirect(CampusCacheKeys.courseClasses(courseId), new TypeReference<List<CourseClassRespDTO>>() {
        }, CampusCacheKeys.TTL_CLASS_INFO, () -> courseService.loadClassesInfo(courseId));
        Map<Long, Integer> stock = classStockCache.rebuild(courseId);
        courseStockService.rebuild(courseId);   // 阶段 20：名额同步结构（版本+余量）一并预热
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseId", courseId);
        m.put("classes", stock.size());
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        m.put("infoKey", CampusCacheKeys.courseClasses(courseId));
        m.put("stockKey", CampusCacheKeys.classStock(courseId));
        return m;
    }

    private List<Long> resolveHotCourseIds() {
        if (StringUtils.hasText(hotCourseIdsConfig)) {
            List<Long> ids = new ArrayList<>();
            for (String part : hotCourseIdsConfig.split(",")) {
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    /* 忽略非法配置项 */
                }
            }
            return ids;
        }
        List<Long> ids = courseClassMapper.topCourseIdsByCapacity(hotLimit);
        return ids == null ? List.of() : ids;
    }
}
