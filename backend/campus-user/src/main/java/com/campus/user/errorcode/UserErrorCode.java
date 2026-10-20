package com.campus.user.errorcode;

import com.campus.common.errorcode.IErrorCode;

/**
 * 用户模块错误码（40060~40099 段）。
 */
public enum UserErrorCode implements IErrorCode {

    /** 原密码不正确 */
    OLD_PASSWORD_WRONG(40060, "原密码不正确"),

    /** 新密码与原密码相同 */
    SAME_PASSWORD(40061, "新密码不能与原密码相同"),

    /** 用户不存在 */
    USER_NOT_FOUND(40062, "用户不存在");

    private final Integer code;
    private final String message;

    UserErrorCode(Integer code, String message) {
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
