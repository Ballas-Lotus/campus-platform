package com.campus.stock;

import com.campus.common.result.Result;
import com.campus.framework.user.core.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段 20 · 名额实时同步接口（只读侧；写路径未改）。
 *
 * <pre>
 *   GET /api/course/{courseId}/stock?version=V   三层读（L1 进程内 → L2 Redis → L3 DB 兜底）
 *       version 仅用于 L1 直返（命中则零网络调用）；响应永远返回服务端当前真实版本
 *   GET /api/course/stock/stream?courses=1,2,3   订阅名额信号（SSE；断线由前端重连 + 轮询兜底）
 *   GET /api/course/stock/metrics                观测计数（发布/扇出/连接/L1 命中…）
 * </pre>
 */
@RestController
@RequestMapping("/api/course")
@RequiredArgsConstructor
public class CourseStockController {

    private final CourseStockService stockService;
    private final StockSseRegistry sseRegistry;
    private final StockMetrics metrics;

    /** 名额快照（教学班粒度：每个班带自己的版本与剩余） */
    public record StockRespDTO(Long courseId, long cver, long serverTs, boolean degraded,
                               List<ClassStockDTO> classes) {
    }

    public record ClassStockDTO(Long id, long v, int remain, int capacity) {
    }

    @GetMapping("/{courseId}/stock")
    public Result<StockRespDTO> stock(@PathVariable("courseId") Long courseId,
                                      @RequestParam(value = "version", required = false) Long version) {
        StockSnapshot snap = stockService.query(courseId, version);
        List<ClassStockDTO> list = snap.classes().entrySet().stream()
                .map(e -> new ClassStockDTO(e.getKey(), e.getValue().version(),
                        e.getValue().remain(), e.getValue().capacity()))
                .toList();
        return Result.success(new StockRespDTO(courseId, snap.cver(), System.currentTimeMillis(),
                snap.degraded(), list));
    }

    /**
     * 订阅信号：{@code courses} 为逗号分隔的课程 id（订阅上限见配置，超限返回业务错误，前端降级轮询）。
     */
    @GetMapping("/stock/stream")
    public SseEmitter stream(@RequestParam(value = "courses", required = false) String courses) {
        Long userId = UserContext.getUserId();
        Set<Long> courseIds = parseIds(courses);
        Map<Long, List<Long>> classMap = stockService.classIdsOf(courseIds);
        Set<Long> classIds = new LinkedHashSet<>();
        classMap.values().forEach(classIds::addAll);
        return sseRegistry.register(userId, courseIds, classIds);
    }

    /** 观测：信号/扇出/连接/L1 命中/重建/降级 */
    @GetMapping("/stock/metrics")
    public Result<Map<String, Object>> metrics() {
        Map<String, Object> m = new LinkedHashMap<>(metrics.snapshot());
        m.putAll(sseRegistry.stats());
        return Result.success(m);
    }

    private static Set<Long> parseIds(String csv) {
        Set<Long> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignore) {
                // 忽略非法片段
            }
        }
        return out;
    }
}
