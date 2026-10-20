package com.campus.gateway;

import com.campus.framework.user.toolkit.JWTUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 网关 · 启动类（阶段 12 拆分，端口 9080）。
 *
 * <p>路由 + JWT 鉴权（照搬 12306 gateway-service 的 TokenValidateGatewayFilterFactory 思路）：
 * 白名单外的请求在网关完成「JWT 签名/有效期 + Redis 登录态副本存在性」两级校验，
 * 通过后转发到 user-service / campus-service（lb:// 服务发现负载均衡）。</p>
 *
 * <p>扫描范围仅本包：不扫描 com.campus.framework（其中的 UserTransmitFilter 依赖 servlet API，
 * 与响应式网关不兼容）——只 @Import 纯工具 JWTUtil。</p>
 */
@SpringBootApplication(scanBasePackages = "com.campus.gateway")
@EnableDiscoveryClient
@Import(JWTUtil.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
