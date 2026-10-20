package com.campus.resilience;

import com.campus.common.result.Result;
import com.campus.framework.concurrent.CampusThreadPools;
import com.campus.framework.lock.CampusLock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 并发控制观测接口（阶段 18）。入口：前端「🔒 并发控制」页（/concurrency）。
 *
 * <p>三块看板合一的现场视图：<b>分布式锁</b>（谁持锁、竞争烈度）+ <b>接口幂等</b>（挡了多少重复）
 * + <b>线程池</b>（水位、拒绝数）。</p>
 */
@RestController
@RequestMapping("/api/concurrency")
@RequiredArgsConstructor
public class ConcurrencyController {

    private final CampusLock campusLock;
    private final IdempotentMetrics idempotentMetrics;
    private final CampusThreadPools threadPools;
    private final LockDemoService lockDemoService;
    /** 阶段 19：合并 Lua（幂等 + 预扣一次原子）对照实验 */
    private final FastPathDemoService fastPathDemoService;

    /** 全景：锁 + 幂等 + 线程池 */
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lock", campusLock.stats());
        m.put("idempotent", idempotentMetrics.snapshot());
        m.put("pools", threadPools.stats());
        m.put("totalRejected", threadPools.totalRejected());
        m.put("notes", List.of(
                "分布式锁：SET NX PX + Lua 释放（自研实现，等价 Redisson RLock 的核心语义；选型理由见 docs/09 §3）",
                "接口幂等：Redis 两态 Lua（CONSUMING / CONSUMED）挡重复提交；唯一性仍由数据库唯一索引兜底",
                "线程池：具名 + 有界队列 + 拒绝计数——把\"任务被静默丢弃\"变成可观测事件",
                "阶段 19：选课快路径把\"幂等 + 预扣\"合并为一个 Lua（成功路径 4 次往返 → 2 次；对照见 /lua-merge-demo）"));
        return Result.success(m);
    }

    /**
     * 锁竞争现场演示：N 个线程同时抢同一把锁。
     *
     * <p>返回里最硬的两个数字：<b>entered</b>（进入临界区次数）与
     * <b>violations</b>（互斥性违例，任意时刻进入数 &gt; 1）——后者恒为 0 才说明锁真的生效。</p>
     */
    @PostMapping("/lock-demo")
    public Result<Map<String, Object>> lockDemo(@RequestParam(defaultValue = "50") int threads,
                                                @RequestParam(defaultValue = "0") long waitMs) {
        return Result.success(lockDemoService.run(threads, waitMs));
    }

    /**
     * 阶段 19：「合并 Lua」对照——同一计数器上旧路径（4 次往返）与快路径（2 次往返）各跑 N 轮，
     * 返回 µs 级均值/P50/P95 与往返次数对照。
     */
    @PostMapping("/lua-merge-demo")
    public Result<Map<String, Object>> luaMergeDemo(@RequestParam(defaultValue = "300") int rounds) {
        return Result.success(fastPathDemoService.run(rounds));
    }
}
