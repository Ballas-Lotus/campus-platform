package com.campus.auth.dto.resp;

import lombok.Builder;
import lombok.Data;

/**
 * 当前登录用户信息（GET /api/auth/me）。
 */
@Data
@Builder
public class UserInfoRespDTO {

    private Long userId;

    private String studentNo;

    private String realName;

    private String college;

    private String major;

    private String grade;

    /** 角色：STUDENT/TEACHER/ADMIN */
    private String role;
}
