package com.campus.auth.errorcode;

import com.campus.common.errorcode.IErrorCode;

/**
 * 认证模块错误码（40010~40199 段）。
 *
 * <p>提示词第 7 节：错误码集中定义在枚举类，禁止散落魔法数字。</p>
 */
public enum AuthErrorCode implements IErrorCode {

    /**
     * 登录失败（账号不存在与密码错误共用——不向攻击者泄露账号是否存在）
     */
    LOGIN_FAILED(40010, "账号或密码错误"),

    /**
     * 账号被禁用
     */
    ACCOUNT_DISABLED(40011, "账号已被禁用，请联系教务处"),

    /**
     * 登录态已失效（Redis 副本不存在：过期 / 已登出 / 被踢下线）
     */
    SESSION_EXPIRED(40101, "登录已过期，请重新登录");

    private final Integer code;
    private final String message;

    AuthErrorCode(Integer code, String message) {
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
