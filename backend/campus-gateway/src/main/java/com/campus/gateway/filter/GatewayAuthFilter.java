package com.campus.gateway.filter;

import com.campus.auth.errorcode.AuthErrorCode;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.errorcode.IErrorCode;
import com.campus.framework.user.constant.UserConstant;
import com.campus.framework.user.core.UserInfoDTO;
import com.campus.framework.user.toolkit.JWTUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 网关鉴权过滤器（两级校验，照搬 12306 TokenValidateGatewayFilterFactory）：
 *
 * <ol>
 *   <li><b>JWT 签名与有效期</b>：解析失败 → 40100（未登录或登录已过期）</li>
 *   <li><b>Redis 登录态副本存在性</b>：副本不存在（登出/被踢/过期）→ 40101（登录态已失效）</li>
 * </ol>
 *
 * <p>通过后把用户标识放进请求头透传（X-Campus-UserId 等，12306 风格）；
 * Authorization 头原样转发，服务内保留原有解析/校验作纵深防御。</p>
 *
 * <p>失败响应与原单体语义一致：HTTP 200 + 业务码在 body（前端拦截器按 code 分流）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private final JWTUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 白名单：登录与健康检查（与单体期 WebConfig 白名单一致） */
    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/login",
            "/api/health"
    );

    public static final String HEADER_USER_ID = "X-Campus-UserId";
    public static final String HEADER_STUDENT_NO = "X-Campus-StudentNo";
    public static final String HEADER_REAL_NAME = "X-Campus-RealName";
    public static final String HEADER_ROLE = "X-Campus-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (WHITELIST.contains(path)) {
            return chain.filter(exchange);
        }

        String token = exchange.getRequest().getHeaders().getFirst(UserConstant.AUTHORIZATION_HEADER);
        UserInfoDTO user = jwtUtil.parseToken(token);
        if (user == null) {
            return reject(exchange, BaseErrorCode.UNAUTHORIZED);
        }
        String key = UserConstant.LOGIN_TOKEN_KEY + token;
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
            return reject(exchange, AuthErrorCode.SESSION_EXPIRED);
        }

        // 透传用户标识（下游服务可用；Authorization 原样转发）
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HEADER_USER_ID, String.valueOf(user.getUserId()))
                .header(HEADER_STUDENT_NO, nullSafe(user.getStudentNo()))
                .header(HEADER_REAL_NAME, nullSafe(user.getRealName()))
                .header(HEADER_ROLE, nullSafe(user.getRole()))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private Mono<Void> reject(ServerWebExchange exchange, IErrorCode code) {
        log.info("[网关拦截] {} → {} {}", exchange.getRequest().getURI().getPath(), code.code(), code.message());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);   // 与原单体语义一致：业务码在 body
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            byte[] fallback = "{\"code\":50000,\"message\":\"系统繁忙，请稍后重试\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(fallback)));
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public int getOrder() {
        return -100;   // 早于路由/转发执行
    }
}
