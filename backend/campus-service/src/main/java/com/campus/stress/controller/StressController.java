package com.campus.stress.controller;

import com.campus.common.result.Result;
import com.campus.sentinel.SentinelFlowConfig;
import com.campus.stress.dto.req.StressRunReqDTO;
import com.campus.stress.dto.resp.StressResultRespDTO;
import com.campus.stress.service.StressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 并发压测接口（阶段 9 演示；压测夹具与清理逻辑见 StressService）。
 *
 * <p>阶段 19 增补：选课限流阈值的查询与<b>运行时热调</b>——观察"万人同抢一课"前
 * 先把阈值放开（否则 40055 会先把人挡光，看不到预扣闸门的真实挡量），演示后恢复。</p>
 */
@RestController
@RequestMapping("/api/stress")
@RequiredArgsConstructor
public class StressController {

    private final StressService stressService;
    private final SentinelFlowConfig sentinelFlowConfig;

    /**
     * 执行压测：POST /api/stress/run　{ "scenario": "OVERSELL", "threads": 100 }
     * scenario ∈ OVERSELL / DUPLICATE / BITMAP / COURSE_OVERSELL；压测后自动清理，不污染业务数据。
     */
    @PostMapping("/run")
    public Result<StressResultRespDTO> run(@RequestBody @Valid StressRunReqDTO requestParam) {
        return Result.success(stressService.run(requestParam.getScenario(), requestParam.getThreads()));
    }

    /** 选课限流状态：当前 QPS 阈值 + 累计拦截数（万人齐射前后对比用） */
    @GetMapping("/sentinel")
    public Result<Map<String, Object>> sentinel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qps", sentinelFlowConfig.qpsLimit());
        m.put("blocked", sentinelFlowConfig.blockedCount());
        return Result.success(m);
    }

    /** 运行时热调选课限流阈值：观察闸门真实挡量先放开（如 qps=100000），演示后恢复 20 */
    @PostMapping("/sentinel")
    public Result<Map<String, Object>> updateSentinel(@RequestParam double qps) {
        double previous = sentinelFlowConfig.qpsLimit();
        sentinelFlowConfig.updateQps(qps);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previousQps", previous);
        m.put("qps", sentinelFlowConfig.qpsLimit());
        m.put("blocked", sentinelFlowConfig.blockedCount());
        return Result.success(m);
    }
}
