package com.campus.framework.user.core;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.Optional;

/**
 * 当前登录用户上下文（改造自 12306 frameworks/bizs/user · UserContext）。
 *
 * <p>基于 TransmittableThreadLocal：单体阶段与 ThreadLocal 等价，但为阶段 12 微服务化后
 * 的线程池透传（如异步任务中继续获取登录用户）预留相同语义，保证移植后行为一致。</p>
 */
public final class UserContext {

    private UserContext() {
    }

    private static final ThreadLocal<UserInfoDTO> USER_THREAD_LOCAL = new TransmittableThreadLocal<>();

    /**
     * 设置当前用户
     */
    public static void setUser(UserInfoDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    /**
     * 获取当前用户（可能为 null，未登录）
     */
    public static UserInfoDTO getUser() {
        return USER_THREAD_LOCAL.get();
    }

    /**
     * 用户 ID（未登录返回 null）
     */
    public static Long getUserId() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getUserId).orElse(null);
    }

    /**
     * 学号
     */
    public static String getStudentNo() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getStudentNo).orElse(null);
    }

    /**
     * 真实姓名
     */
    public static String getRealName() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getRealName).orElse(null);
    }

    /**
     * 角色
     */
    public static String getRole() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getRole).orElse(null);
    }

    /**
     * 当前请求携带的 Token（含 Bearer 前缀）
     */
    public static String getToken() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getToken).orElse(null);
    }

    /**
     * 是否为管理员
     */
    public static boolean isAdmin() {
        return "ADMIN".equals(getRole());
    }

    /**
     * 清理上下文（请求结束必须调用，防止线程复用串号）
     */
    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }
}
