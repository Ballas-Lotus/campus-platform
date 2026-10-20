package com.campus.common.errorcode;

/**
 * 基础错误码（通用）。
 *
 * <p>业务模块的错误码在本模块对应的枚举中定义，禁止散落魔法数字。</p>
 */
public enum BaseErrorCode implements IErrorCode {

    /** 成功 */
    SUCCESS(0, "success"),

    /* ---------- 客户端错误 4xxxx ---------- */

    /** 通用客户端错误 */
    CLIENT_ERROR(40000, "请求错误"),
    /** 参数校验失败 */
    PARAM_INVALID(40001, "参数校验失败"),
    /** 未登录或凭证失效 */
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    /** 无权限 */
    FORBIDDEN(40300, "无权限执行该操作"),
    /** 资源不存在 */
    NOT_FOUND(40400, "请求的资源不存在"),
    /** 数据冲突（重复提交、版本冲突等） */
    CONFLICT(40900, "数据处理冲突，请刷新后重试"),
    /** 接口幂等拦截（阶段 18：窗口内重复提交同一请求） */
    IDEMPOTENT_REJECTED(40060, "请勿重复提交（请求已受理，请稍后查看结果）"),

    /* ---------- 服务端错误 5xxxx ---------- */

    /** 通用服务端错误 */
    SERVICE_ERROR(50000, "系统繁忙，请稍后重试");

    private final Integer code;
    private final String message;

    BaseErrorCode(Integer code, String message) {
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
