package com.campus.lecture.errorcode;

import com.campus.common.errorcode.IErrorCode;

/**
 * 讲座模块错误码（40040~40049 段）。
 */
public enum LectureErrorCode implements IErrorCode {

    /** 未到开放时间（开始前 7 天开放预约；2026-10-08 规则） */
    NOT_BOOKABLE(40040, "该讲座尚未开放预约（开始前 7 天开放）");

    private final Integer code;
    private final String message;

    LectureErrorCode(Integer code, String message) {
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
