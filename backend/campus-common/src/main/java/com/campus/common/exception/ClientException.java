package com.campus.common.exception;

import com.campus.common.errorcode.IErrorCode;

/**
 * 客户端异常：调用方（前端/用户）的问题，如参数非法、业务前置条件不满足。
 *
 * <p>典型场景："该时段与你已选的《数据结构》冲突"。</p>
 */
public class ClientException extends AbstractException {

    public ClientException(String message, IErrorCode errorCode) {
        super(message, errorCode);
    }

    public ClientException(String message, Throwable cause, IErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    /** 携带动态客户端提示（如含解禁时间的惩罚提示） */
    public ClientException(String message, String clientMessage, IErrorCode errorCode) {
        super(message, clientMessage, errorCode);
    }

    public ClientException(IErrorCode errorCode) {
        this(null, errorCode);
    }
}
