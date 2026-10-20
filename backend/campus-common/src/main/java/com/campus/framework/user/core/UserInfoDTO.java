package com.campus.framework.user.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息载体（改造自 12306 frameworks/bizs/user · UserInfoDTO）。
 *
 * <p>三个用途共用一份结构：JWT subject 载荷、UserContext 上下文、Redis 登录态副本。
 * 校园版将 12306 的 String userId 改为 Long（单体不受 Header 透传的字符串约束）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoDTO {

    /** 用户 ID */
    private Long userId;

    /** 学号（登录名） */
    private String studentNo;

    /** 真实姓名 */
    private String realName;

    /** 角色：STUDENT / TEACHER / ADMIN */
    private String role;

    /** 当前请求携带的原始 Token（含 Bearer 前缀；登录签发时为空） */
    private String token;
}
