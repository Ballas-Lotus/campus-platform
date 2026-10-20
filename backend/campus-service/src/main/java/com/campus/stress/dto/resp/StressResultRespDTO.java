package com.campus.stress.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 压测结果（一次 run 的完整报告：并发统计 + 数据核对断言 + 时延分位）。
 */
@Data
@Builder
public class StressResultRespDTO {

    private String scenario;

    private String title;

    /** PASS / FAIL（全部断言通过为 PASS） */
    private String conclusion;

    /** 实际并发请求数 */
    private int requests;

    /** 成功数（拿到名额/预约成功的请求） */
    private int success;

    /** 总耗时（ms，从同时发起到全部返回） */
    private long durationMs;

    /** 失败分布：错误码 → 次数 */
    private Map<String, Integer> failures;

    private Double p50Ms;

    private Double p95Ms;

    private Long maxMs;

    /** 数据核对断言（防超卖 / 唯一性 / 计数一致等） */
    private List<Assertion> assertions;

    /** 场景特有明细（容量、前后数值等） */
    private Map<String, Object> detail;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Assertion {
        private String name;
        private String expected;
        private String actual;
        private boolean pass;
    }
}
