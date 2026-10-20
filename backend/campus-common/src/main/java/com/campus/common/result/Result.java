package com.campus.common.result;

import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.errorcode.IErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 全局统一返回结构：{@code { code, message, data }}。
 *
 * <p>code = 0 表示成功；非 0 为错误码（见 {@link IErrorCode}）。</p>
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 错误码，0 表示成功 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /* ---------- 成功 ---------- */

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(BaseErrorCode.SUCCESS.code(), BaseErrorCode.SUCCESS.message(), data);
    }

    /* ---------- 失败 ---------- */

    public static <T> Result<T> fail(IErrorCode errorCode) {
        return new Result<>(errorCode.code(), errorCode.message(), null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
