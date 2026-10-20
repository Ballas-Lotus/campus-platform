package com.campus.prededuct;

import com.campus.common.result.Result;
import com.campus.framework.lock.CampusLock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预扣闸门观测接口（阶段 17）。入口：前端「🔥 预扣闸门」页（/prededuct）。
 */
@RestController
@RequestMapping("/api/prededuct")
@RequiredArgsConstructor
public class PreDeductController {

    private final PreDeductService preDeductService;
    private final CampusLock campusLock;

    /** 闸门全景：指标 + 活跃计数器现场（Redis 预计数 vs DB 真值 vs 偏差） */
    @GetMapping("/state")
    public Result<Map<String, Object>> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metrics", preDeductService.metrics());
        m.put("counters", preDeductService.state());
        m.put("lock", campusLock.stats());
        m.put("howto", "Redis 是「预计数」、MySQL 是「真相」：拦截发生在 Redis（不占数据库连接），"
                + "最终裁决仍是 UPDATE ... WHERE booked < capacity；偏差由 30 秒对账纠偏");
        return Result.success(m);
    }

    /** 手动触发一轮对账（演示/排障；定时任务每 30 秒自动跑一次） */
    @PostMapping("/reconcile")
    public Result<Map<String, Object>> reconcile() {
        return Result.success(preDeductService.reconcileAll());
    }

    /** 丢弃某个教学班的计数器（下次请求按 DB 真值重建）——压测前清理用 */
    @PostMapping("/drop")
    public Result<Void> drop(@RequestParam Long classId) {
        preDeductService.dropCounter(PreDeductService.classKey(classId));
        return Result.success();
    }
}
