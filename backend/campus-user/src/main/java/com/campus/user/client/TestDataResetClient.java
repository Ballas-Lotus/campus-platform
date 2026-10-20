package com.campus.user.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 测试数据重置客户端（阶段 12：user-service → campus-service 的服务间调用示例）。
 *
 * <p>@LoadBalanced RestClient：baseUrl 用服务名 {@code http://campus-service}，
 * 由 Spring Cloud LoadBalancer 从 Nacos 解析实例并负载均衡——这是拆分后
 * "测试账号登录自愈"（重置预约 + 选课数据）的跨服务通道。</p>
 *
 * <p>失败策略：仅告警不阻断登录——重置属于体验优化，不应让登录因下游抖动失败。</p>
 */
@Slf4j
@Component
public class TestDataResetClient {

    private final RestClient restClient;

    public TestDataResetClient(@LoadBalanced RestClient.Builder builder) {
        this.restClient = builder.baseUrl("http://campus-service").build();
    }

    public void resetTestData(Long userId) {
        try {
            restClient.post()
                    .uri("/api/internal/test-reset/{userId}", userId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("测试账号数据重置完成（经 campus-service）: userId={}", userId);
        } catch (Exception e) {
            log.warn("测试账号数据重置调用失败（不阻断登录）: userId={}，原因: {}", userId, e.getMessage());
        }
    }
}
