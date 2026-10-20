package com.campus.common.exception;

import com.campus.common.errorcode.IErrorCode;

/**
 * 服务端异常：系统自身的故障，如依赖不可用、数据不一致。
 */
public class ServiceException extends AbstractException {

    public ServiceException(String message, IErrorCode errorCode) {
        super(message, errorCode);
    }

    public ServiceException(String message, Throwable cause, IErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }
}
