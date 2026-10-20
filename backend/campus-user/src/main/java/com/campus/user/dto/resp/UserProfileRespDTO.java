package com.campus.user.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 个人中心资料 + 统计（阶段 10）。
 */
@Data
@Builder
public class UserProfileRespDTO {

    // ---------- 基本信息 ----------

    private Long userId;

    private String studentNo;

    private String realName;

    private String college;

    private String major;

    private String grade;

    private String role;

    private String phone;

    private String email;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDateTime createTime;

    // ---------- 预约统计 ----------

    private Long totalReservations;

    /** 进行中（PENDING + APPROVED） */
    private Long activeReservations;

    private Long completed;

    private Long cancelled;

    private Long noShow;

    // ---------- 学习 ----------

    /** 本学期已选课程数 */
    private Long courseCount;

    /** 已选学分合计 */
    private BigDecimal courseCredits;

    // ---------- 场馆周用量 ----------

    private Integer venueWeeklyUsed;

    private Integer venueWeeklyLimit;

    // ---------- 当前禁约状态（无惩罚为 null） ----------

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime penaltyUntil;

    private String penaltyReason;
}
