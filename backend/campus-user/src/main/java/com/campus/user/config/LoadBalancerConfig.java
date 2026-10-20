package com.campus.user.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 负载均衡客户端配置（阶段 12）。
 *
 * <p>Spring Cloud LoadBalancer 只对**带 @LoadBalanced 注解的 Bean** 做后处理（挂载
 * LoadBalancerInterceptor），因此需要显式声明该 Builder：
 * baseUrl 使用服务名（如 {@code http://campus-service}）时会经 Nacos 服务发现解析实例。</p>
 */
@Configuration
public class LoadBalancerConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
