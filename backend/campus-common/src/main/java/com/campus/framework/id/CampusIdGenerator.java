package com.campus.framework.id;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 分布式 ID 发生器（阶段 14）：MyBatis-Plus {@link IdentifierGenerator} 的雪花实现。
 *
 * <p>注册为 Spring Bean 后，MyBatis-Plus 自动以它替代默认的 ASSIGN_ID 生成器——
 * 所有 {@code IdType.ASSIGN_ID} 实体（分片表：预约/选课/站内信/配额/时间片）落库前在此取号。</p>
 *
 * <p>机器号分配：Redis {@code INCR campus:snowflake:worker}，多实例自动错开（1024 取模）；
 * Redis 不可用时回退随机机器号（单机演示下冲突概率极低，且日志有记录）。</p>
 */
@Slf4j
@Component
public class CampusIdGenerator implements IdentifierGenerator {

    private static final String WORKER_ID_KEY = "campus:snowflake:worker";

    private final StringRedisTemplate stringRedisTemplate;
    private CampusSnowflake snowflake;

    public CampusIdGenerator(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    void init() {
        long workerId;
        try {
            Long value = stringRedisTemplate.opsForValue().increment(WORKER_ID_KEY);
            workerId = (value == null ? 0L : value) % 1024L;
            log.info("[分布式ID] 雪花机器号由 Redis 分配: workerId={}", workerId);
        } catch (Exception e) {
            workerId = ThreadLocalRandom.current().nextInt(1024);
            log.warn("[分布式ID] Redis 不可用，回退随机机器号: workerId={}（{}）", workerId, e.getMessage());
        }
        this.snowflake = new CampusSnowflake(workerId);
    }

    @Override
    public Number nextId(Object entity) {
        return snowflake.nextId();
    }

    /** 供业务直接取号（单号中的雪花序号；与实体主键共用同一发生器） */
    public long nextLongId() {
        return snowflake.nextId();
    }
}
