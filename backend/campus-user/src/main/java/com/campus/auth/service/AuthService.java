package com.campus.auth.service;

import com.campus.auth.dto.req.LoginReqDTO;
import com.campus.auth.dto.resp.LoginRespDTO;
import com.campus.auth.dto.resp.UserInfoRespDTO;

/**
 * 认证服务：登录 / 当前用户 / 登出。
 */
public interface AuthService {

    /**
     * 登录（学号/手机号/邮箱自动识别）。
     *
     * @return 令牌 + 基础用户信息
     */
    LoginRespDTO login(LoginReqDTO requestParam);

    /**
     * 当前登录用户信息（来自 UserContext）。
     */
    UserInfoRespDTO currentUser();

    /**
     * 登出：删除 Redis 登录态副本（服务端主动失效）。
     */
    void logout();
}
