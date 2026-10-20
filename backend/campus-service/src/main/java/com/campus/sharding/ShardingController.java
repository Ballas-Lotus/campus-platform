package com.campus.sharding;

import com.campus.common.result.Result;
import com.campus.sentinel.SentinelFlowConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分库分表观测接口（阶段 14）。入口：前端「🧩 分库分表」页（/sharding）。
 */
@RestController
@RequestMapping("/api/sharding")
@RequiredArgsConstructor
public class ShardingController {

    private final ShardingService shardingService;
    private final SentinelFlowConfig sentinelFlowConfig;

    /**
     * 分片拓扑 + 各分片物理行数。
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        return Result.success(shardingService.info());
    }

    /**
     * 路由验证：GET /api/sharding/route?userId=1 或 ?reservationNo=RES...
     *
     * <p>返回基因法推导结果 + 经 ShardingSphere 的真实查询结果（查得到即路由正确）。</p>
     */
    @GetMapping("/route")
    public Result<Map<String, Object>> route(@RequestParam(required = false) Long userId,
                                             @RequestParam(required = false) String reservationNo) {
        return Result.success(shardingService.route(userId, reservationNo));
    }

    /**
     * Sentinel 限流状态（选课接口）。
     */
    @GetMapping("/sentinel")
    public Result<Map<String, Object>> sentinel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", sentinelFlowConfig.resourceName());
        m.put("qpsLimit", sentinelFlowConfig.qpsLimit());
        m.put("blockedCount", sentinelFlowConfig.blockedCount());
        m.put("errorCode", sentinelFlowConfig.errorCode());
        m.put("hint", "压测页「Sentinel 限流」场景可现场触发拦截");
        return Result.success(m);
    }
}
