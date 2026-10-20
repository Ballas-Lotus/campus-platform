package com.campus.auth.controller;

import com.campus.auth.dto.req.LoginReqDTO;
import com.campus.auth.dto.resp.LoginRespDTO;
import com.campus.auth.dto.resp.UserInfoRespDTO;
import com.campus.auth.service.AuthService;
import com.campus.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（接口文档见 docs/02-接口文档.md）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录：POST /api/auth/login（白名单，无需 token）
     * 请求体：{ "account": "学号|手机号|邮箱", "password": "..." }
     */
    @PostMapping("/login")
    public Result<LoginRespDTO> login(@RequestBody @Valid LoginReqDTO requestParam) {
        return Result.success(authService.login(requestParam));
    }

    /**
     * 当前登录用户信息：GET /api/auth/me（需 token）
     */
    @GetMapping("/me")
    public Result<UserInfoRespDTO> me() {
        return Result.success(authService.currentUser());
    }

    /**
     * 登出：POST /api/auth/logout（需 token；删除 Redis 登录态副本 → 服务端主动失效）
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
