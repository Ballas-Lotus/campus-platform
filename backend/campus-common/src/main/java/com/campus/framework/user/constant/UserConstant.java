package com.campus.framework.user.constant;

/**
 * 用户与登录态常量（改造自 12306 frameworks/bizs/base · UserConstant）。
 *
 * <p>12306 是微服务：网关解析 JWT 后把 userId/username 等放进 Header 透传给下游；
 * 校园平台当前是单体（阶段 0~11），Filter 直接解析 Authorization 头，无需透传 Header。</p>
 */
public final class UserConstant {

    private UserConstant() {
    }

    /** 认证头名称（提示词第 7 节：Authorization: Bearer &lt;token&gt;） */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Token 前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** Redis 登录态副本 Key 前缀：campus:login:token:{token}（照搬 12306「JWT + Redis 30 分钟副本」设计） */
    public static final String LOGIN_TOKEN_KEY = "campus:login:token:";

    /* ---------- 角色 ---------- */

    public static final String ROLE_STUDENT = "STUDENT";
    public static final String ROLE_TEACHER = "TEACHER";
    public static final String ROLE_ADMIN = "ADMIN";

    /* ---------- 用户状态 ---------- */

    /** 正常 */
    public static final int STATUS_NORMAL = 1;
    /** 禁用 */
    public static final int STATUS_DISABLED = 0;
}
