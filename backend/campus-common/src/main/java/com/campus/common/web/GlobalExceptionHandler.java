package com.campus.common.web;

import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.AbstractException;
import com.campus.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把各类异常统一转换为 {@link Result} 结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（ClientException / ServiceException 等）。
     *
     * <p>业务异常是预期内的分支（未登录、密码错误、时段冲突……），只打一行摘要；
     * 完整堆栈留给兜底的系统异常，避免 40100 这类高频分支刷屏。</p>
     */
    @ExceptionHandler(AbstractException.class)
    public Result<Void> handleAbstractException(AbstractException ex) {
        log.warn("[业务异常] code={} message={}", ex.errorCode, ex.getMessage());
        return Result.fail(ex.errorCode, ex.errorMessage);
    }

    /**
     * 请求体参数校验失败（@RequestBody + @Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String detail = fieldError == null
                ? BaseErrorCode.PARAM_INVALID.message()
                : fieldError.getDefaultMessage();
        log.warn("[参数校验失败] {}", detail);
        return Result.fail(BaseErrorCode.PARAM_INVALID.code(), detail);
    }

    /**
     * 表单/查询参数绑定校验失败
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String detail = fieldError == null
                ? BaseErrorCode.PARAM_INVALID.message()
                : fieldError.getDefaultMessage();
        log.warn("[参数绑定失败] {}", detail);
        return Result.fail(BaseErrorCode.PARAM_INVALID.code(), detail);
    }

    /**
     * 请求体不可读（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("[请求体解析失败] {}", ex.getMessage());
        return Result.fail(BaseErrorCode.PARAM_INVALID.code(), "请求体格式错误");
    }

    /**
     * 静态资源 / 路径不存在 -> 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("[资源不存在] {}", ex.getResourcePath());
        return Result.fail(BaseErrorCode.NOT_FOUND);
    }

    /**
     * 兜底：未预料的异常 -> 50000（完整堆栈只进日志）
     */
    @ExceptionHandler(Throwable.class)
    public Result<Void> handleThrowable(Throwable ex) {
        log.error("[系统异常] {}", ex.getMessage(), ex);
        return Result.fail(BaseErrorCode.SERVICE_ERROR);
    }
}
