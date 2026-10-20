package com.campus.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sentinel 限流配置（阶段 14）：为选课接口注册 QPS 流控规则。
 *
 * <p>规则以代码方式注册（{@link FlowRuleManager}）——本项目 Nacos 不带持久化规则仓库，
 * 代码注册最简单可靠；生产可换成 Nacos 数据源动态规则（同一 API，见 docs/04 §6.7）。</p>
 *
 * <p>要点：Sentinel 是"入口保护"——超阈值请求在切面层快速失败（不产生任何 DB 动作），
 * 与"防超卖"（{@code UPDATE ... WHERE selected < capacity}）是两层互补的防线：
 * 前者削峰保系统，后者保数据正确。</p>
 */
@Slf4j
@Component
public class SentinelFlowConfig {

    /** 限流资源名（与 CourseController#select 的 @SentinelResource 一致） */
    public static final String COURSE_SELECT_RESOURCE = "courseSelect";

    /** 选课接口 QPS 阈值（默认 20——正常用户点击远低于此，压测/抢课高峰可触发） */
    @Value("${campus.sentinel.course-select-qps:20}")
    private volatile double courseSelectQps;

    /** 被限流拦截的累计次数（观测接口/压测断言用） */
    private final AtomicLong blockedCount = new AtomicLong();

    @PostConstruct
    void registerRules() {
        loadRule(courseSelectQps);
        log.info("[Sentinel] 已注册选课接口限流规则: resource={} 阈值={} QPS（快速失败）",
                COURSE_SELECT_RESOURCE, courseSelectQps);
    }

    /**
     * 运行时调整阈值（观察"万人齐射"前先放开限流，只看闸门真实挡量；演示后恢复 20）。
     * {@link FlowRuleManager#loadRules} 支持热加载——无需重启。
     */
    public synchronized void updateQps(double qps) {
        this.courseSelectQps = qps;
        loadRule(qps);
        log.info("[Sentinel] 阈值已热更新为 {} QPS（快速失败）", qps);
    }

    private void loadRule(double qps) {
        FlowRule rule = new FlowRule(COURSE_SELECT_RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);   // 快速失败
        FlowRuleManager.loadRules(List.of(rule));
    }

    /** 拦截计数（限流兜底方法调用） */
    public void recordBlocked() {
        blockedCount.incrementAndGet();
    }

    public long blockedCount() {
        return blockedCount.get();
    }

    public double qpsLimit() {
        return courseSelectQps;
    }

    public String resourceName() {
        return COURSE_SELECT_RESOURCE;
    }

    /** 错误码常量（与 CourseErrorCode.COURSE_FLOW_LIMIT 对齐；观测接口展示用） */
    public int errorCode() {
        return 40055;
    }
}
