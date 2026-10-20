package com.campus.framework.web;

import com.campus.auth.errorcode.AuthErrorCode;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.framework.user.constant.UserConstant;
import com.campus.framework.user.core.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 登录校验拦截器（校园平台新增；12306 中该职责在网关的全局认证过滤器）。
 *
 * <p>两级校验：</p>
 * <ol>
 *   <li><b>JWT</b>（UserTransmitFilter 已解析）：签名/有效期不过 → UserContext 为空 → 40100</li>
 *   <li><b>Redis 登录态副本</b>：副本不存在 → 登录态已失效（过期/登出/被踢）→ 40101。
 *       这是 12306「JWT + Redis 副本」设计的关键：JWT 无法主动吊销，副本可以。</li>
 * </ol>
 *
 * <p>滑动续期（改进点）：剩余 TTL 低于阈值时续满 session-ttl-seconds，
 * 活跃用户不会在 30 分钟整被踢下线（JWT 本体 1 天有效，续期不会与 JWT 过期冲突）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${campus.jwt.session-ttl-seconds:1800}")
    private long sessionTtlSeconds;

    @Value("${campus.jwt.renew-threshold-seconds:900}")
    private long renewThresholdSeconds;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = UserContext.getUserId();
        String token = UserContext.getToken();
        if (userId == null || token == null) {
            throw new ClientException(BaseErrorCode.UNAUTHORIZED);
        }
        String key = UserConstant.LOGIN_TOKEN_KEY + token;
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            // 副本不存在：登录态已被服务端失效（过期/登出/管理员踢人）
            throw new ClientException(AuthErrorCode.SESSION_EXPIRED);
        }
        if (ttl < renewThresholdSeconds) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(sessionTtlSeconds));
            log.debug("登录态续期: userId={}，续至 {}s", userId, sessionTtlSeconds);
        }
        return true;
    }
}
