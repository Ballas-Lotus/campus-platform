package com.campus.common.errorcode;

/**
 * 错误码接口：所有错误码枚举实现此接口。
 *
 * <p>约定：code 为数字，0 表示成功；4xxxx 客户端错误；5xxxx 服务端错误。</p>
 */
public interface IErrorCode {

    /**
     * 错误码
     */
    Integer code();

    /**
     * 面向用户的错误提示
     */
    String message();
}
