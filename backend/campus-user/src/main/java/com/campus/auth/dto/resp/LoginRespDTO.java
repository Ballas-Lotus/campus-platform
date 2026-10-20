package com.campus.auth.dto.resp;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应：Access Token + 基础用户信息（前端存 token 于 localStorage，后续请求携带）。
 */
@Data
@Builder
public class LoginRespDTO {

    /** 访问令牌（含 "Bearer " 前缀，前端原样放入 Authorization 头） */
    private String accessToken;

    private Long userId;

    private String studentNo;

    private String realName;

    /** 角色：STUDENT/TEACHER/ADMIN */
    private String role;
}
