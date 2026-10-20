package com.campus.config;

import com.campus.framework.web.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录校验拦截器与白名单。
 *
 * <p>阶段 12 起登录鉴权第一道闸在网关（campus-gateway），服务内拦截器保留为纵深防御
 * （直接访问服务端口时行为与单体期一致）。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                // 白名单：健康检查、登录（注册等后续对外开放接口也在此追加）
                .excludePathPatterns(
                        "/api/health",
                        "/api/auth/login",
                        // 服务间内部接口（网关不路由 /api/internal/**，仅内网直连可达）
                        "/api/internal/**"
                );
    }
}
