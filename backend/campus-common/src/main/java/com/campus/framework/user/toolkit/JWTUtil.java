package com.campus.framework.user.toolkit;

import com.campus.framework.user.constant.UserConstant;
import com.campus.framework.user.core.UserInfoDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具（改造自 12306 frameworks/bizs/user · JWTUtil）。
 *
 * <p>相对 12306 的改造点：</p>
 * <ol>
 *   <li>密钥从硬编码常量改为配置项 {@code campus.jwt.secret}（生产可换配置中心/环境变量）</li>
 *   <li>静态类改为 Spring Bean（可注入 ObjectMapper，便于测试）</li>
 *   <li>jjwt 0.9.x → 0.12.x 新 API（HS512；不再需要 javax.xml.bind 那一串补丁依赖）</li>
 *   <li>载荷字段改为校园语义：userId / studentNo / realName / role</li>
 * </ol>
 */
@Slf4j
@Component
public class JWTUtil {

    private final SecretKey secretKey;
    private final String issuer;
    private final long expireSeconds;
    private final ObjectMapper objectMapper;

    public JWTUtil(@Value("${campus.jwt.secret}") String secret,
                   @Value("${campus.jwt.issuer:campus-platform}") String issuer,
                   @Value("${campus.jwt.expire-seconds:1800}") long expireSeconds,
                   ObjectMapper objectMapper) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.expireSeconds = expireSeconds;
        this.objectMapper = objectMapper;
    }

    /**
     * 签发访问 Token（返回值含 "Bearer " 前缀，与 12306 行为一致）
     */
    public String generateAccessToken(UserInfoDTO userInfo) {
        try {
            // token 字段不写入 JWT 本体（避免载荷自引用）
            UserInfoDTO payload = UserInfoDTO.builder()
                    .userId(userInfo.getUserId())
                    .studentNo(userInfo.getStudentNo())
                    .realName(userInfo.getRealName())
                    .role(userInfo.getRole())
                    .build();
            String jwt = Jwts.builder()
                    .issuer(issuer)
                    .subject(objectMapper.writeValueAsString(payload))
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + expireSeconds * 1000))
                    .signWith(secretKey, Jwts.SIG.HS512)
                    .compact();
            return UserConstant.TOKEN_PREFIX + jwt;
        } catch (Exception ex) {
            // 序列化失败属于编码错误，直接抛出
            throw new IllegalStateException("JWT 签发失败", ex);
        }
    }

    /**
     * 解析 Token（可带或不带 Bearer 前缀）。无效或过期返回 null。
     */
    public UserInfoDTO parseToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String actualToken = token.startsWith(UserConstant.TOKEN_PREFIX)
                ? token.substring(UserConstant.TOKEN_PREFIX.length())
                : token;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(actualToken)
                    .getPayload();
            return objectMapper.readValue(claims.getSubject(), UserInfoDTO.class);
        } catch (ExpiredJwtException ex) {
            log.debug("JWT 已过期: {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("JWT 解析失败: {}", ex.getMessage());
        }
        return null;
    }
}
