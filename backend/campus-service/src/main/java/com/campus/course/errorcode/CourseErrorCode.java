package com.campus.course.errorcode;

import com.campus.common.errorcode.IErrorCode;

/**
 * 选课模块错误码（40050~40099 段）。
 *
 * <p>2026-10-08 规则：选课不设候补队列——满员直接拒绝（不提供"进候补"选项）。</p>
 */
public enum CourseErrorCode implements IErrorCode {

    /** 教学班不存在 */
    CLASS_NOT_FOUND(40050, "教学班不存在或已停开"),

    /** 重复选课 */
    ALREADY_SELECTED(40051, "你已选过该教学班，请勿重复选课"),

    /** 名额已满（并发下先到先得） */
    COURSE_FULL(40052, "手慢了，该教学班名额已满"),

    /** 与已选课程时间冲突 */
    TIME_CONFLICT(40053, "该教学班与你的已选课程时间冲突"),

    /** 未选该教学班（或已退课） */
    NOT_SELECTED(40054, "你未选该教学班（或已退课）"),
    /** 抢课高峰限流（阶段 14 · Sentinel：超过 QPS 阈值快速失败） */
    COURSE_FLOW_LIMIT(40055, "当前选课人数过多，请稍后再试（抢课高峰限流）"),

    /**
     * 阶段 21（受理制）：受理后"落库消息发送失败"→ 补偿回滚 → 对用户而言"未被受理"。
     * 语义 = 什么都没发生（名额已退回、未产生任何订单），可安全重试。
     */
    ACCEPT_FAILED(40056, "选课请求未能受理（系统繁忙），请稍后重试");

    private final Integer code;
    private final String message;

    CourseErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public Integer code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
