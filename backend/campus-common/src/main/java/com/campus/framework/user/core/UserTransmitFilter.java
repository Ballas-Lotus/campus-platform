package com.campus.framework.user.core;

import com.campus.framework.user.constant.UserConstant;
import com.campus.framework.user.toolkit.JWTUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 用户信息注入过滤器（改造自 12306 frameworks/bizs/user · UserTransmitFilter）。
 *
 * <p>12306（微服务）：网关解析 JWT 后把 userId/username/realName 放入 Header 透传，
 * 本 Filter 从 Header 读并注入上下文。</p>
 *
 * <p>校园平台（单体）：没有网关，本 Filter <b>直接解析 Authorization 头</b>的 JWT，
 * 验证签名与有效期后注入 UserContext。</p>
 *
 * <p>职责边界：只负责"解析并注入"，<b>不拒绝</b>未登录请求——是否必须登录由
 * {@code LoginInterceptor} 按路径决定（白名单接口无需登录）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class UserTransmitFilter implements Filter {

    private final JWTUtil jwtUtil;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        try {
            String token = request.getHeader(UserConstant.AUTHORIZATION_HEADER);
            if (token != null && token.startsWith(UserConstant.TOKEN_PREFIX)) {
                UserInfoDTO userInfo = jwtUtil.parseToken(token);
                if (userInfo != null) {
                    userInfo.setToken(token);
                    UserContext.setUser(userInfo);
                }
            }
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            // 线程复用安全：无论成败必须清理
            UserContext.removeUser();
        }
    }
}
