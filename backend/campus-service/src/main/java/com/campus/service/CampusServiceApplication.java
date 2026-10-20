package com.campus.service;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 核心业务服务 · 启动类（阶段 12 拆分，端口 9082）。
 *
 * <p>职责：资源/时间片/预约引擎/图书馆/场馆/讲座/选课/并发压测（预约引擎强一致内聚，见 docs/04 §2）。</p>
 * <p>@EnableScheduling：ReservationScheduler（每 60 秒判爽约/结算/消化 PENDING，MQ 延时消息的兜底）。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.campus.resource",
        "com.campus.reservation",
        "com.campus.library",
        "com.campus.venue",
        "com.campus.lecture",
        "com.campus.course",
        "com.campus.stress",
        "com.campus.mq",
        "com.campus.controller",
        "com.campus.internal",
        "com.campus.sharding",
        "com.campus.sentinel",
        // 阶段 16~18：缓存观测 / 预扣闸门 / 并发控制（锁·幂等·线程池）
        "com.campus.cache",
        "com.campus.prededuct",
        "com.campus.resilience",
        // 阶段 20：名额实时同步（读侧三层缓存 + SSE 信号 + 对账）
        "com.campus.stock",
        "com.campus.common",
        "com.campus.framework",
        "com.campus.config"
})
@EnableScheduling
@MapperScan("com.campus.**.dao.mapper")
public class CampusServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusServiceApplication.class, args);
    }
}
