package com.campus.common.exception;

import com.campus.common.errorcode.IErrorCode;

/**
 * 异常基类。
 *
 * <p>约定：{@code message} 为写给日志的内部信息；{@code errorMessage} 为返回给客户端的提示
 * （默认取错误码的静态文案；构造时可通过 {@code clientMessage} 覆盖为动态文案，
 * 如"你已被限制预约，解禁时间 2026-10-08 12:30"）。</p>
 */
public abstract class AbstractException extends RuntimeException {

    /** 返回给客户端的错误提示 */
    public final String errorMessage;

    /** 错误码 */
    public final Integer errorCode;

    public AbstractException(String message, Throwable cause, IErrorCode errorCode) {
        this(message, cause, errorCode, null);
    }

    public AbstractException(String message, IErrorCode errorCode) {
        this(message, (Throwable) null, errorCode);
    }

    /**
     * 完整构造器：clientMessage 非空时覆盖客户端提示。
     */
    public AbstractException(String message, Throwable cause, IErrorCode errorCode, String clientMessage) {
        // message 为内部调试信息：未显式给出时回退为客户端提示，保证 getMessage() 永不为 null
        super(message != null ? message : errorCode.message(), cause);
        this.errorMessage = (clientMessage != null && !clientMessage.isEmpty())
                ? clientMessage : errorCode.message();
        this.errorCode = errorCode.code();
    }

    /**
     * 便捷构造器：携带动态客户端提示。
     */
    public AbstractException(String message, String clientMessage, IErrorCode errorCode) {
        this(message, (Throwable) null, errorCode, clientMessage);
    }
}
