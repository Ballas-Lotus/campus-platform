package com.campus.course.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 我的课表项（联课程与教学班）。
 */
@Data
public class MyCourseRespDTO {

    /** 选课记录 id */
    private Long id;

    private Long classId;

    private Long courseId;

    private String courseCode;

    private String courseName;

    private BigDecimal credits;

    private String courseType;

    private String college;

    private String className;

    private String teacher;

    private String location;

    private Integer weekDay;

    private Integer startSection;

    private Integer endSection;

    private Integer startWeek;

    private Integer endWeek;

    private String semester;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    // ---------------- 阶段 21（受理制）--------
    // 受理模式下 select 返回的是"已受理"回执（DB 尚未落库，经 MQ 异步落库）：
    // 结果经 SSE「settled」事件回执（ok=true 已落库 / ok=false 已拒绝且名额已退回）。

    /** true = 已受理（异步落库中）；null/false = 老链路同步落库结果 */
    private Boolean accepted;

    /** 受理后的最新余量（客户端立即乐观渲染，不必等信号） */
    private Integer remain;

    /** 受理后的教学班版本（与信号同源，客户端据此去重） */
    private Long version;

    /** 受理单号（排障关联：日志 / 台账 / 落库行 op_id 三处可对） */
    private String opId;
}
