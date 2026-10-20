package com.campus.framework.id;

/**
 * 雪花算法发生器（阶段 14；对照 12306 distributedid 模块的校园精简版）。
 *
 * <p>64 位布局：1 位符号（恒 0）+ 41 位毫秒时间戳（相对自定义纪元）+ 10 位机器号 + 12 位序列，
 * 单机每毫秒 4096 个 ID，够用且单调递增（对 B+Tree 友好）。</p>
 *
 * <p>机器号由 Redis 分配（见 {@link CampusIdGenerator}），多实例自动错开——
 * 与 12306 的"Redis 生成 workId"思路一致，但用 INCR 而非 SETNX 组锁。</p>
 */
public class CampusSnowflake {

    /** 自定义纪元（2023-11-14，减少时间戳位数） */
    private static final long EPOCH = 1_700_000_000_000L;
    private static final long MAX_WORKER_ID = 1023L;
    private static final long SEQUENCE_MASK = 0xFFFL;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public CampusSnowflake(long workerId) {
        this.workerId = workerId & MAX_WORKER_ID;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // 时钟回拨：等待追上（小回拨可容忍；本机演示环境不会出现大回拨）
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << 22) | (workerId << 12) | sequence;
    }

    public long workerId() {
        return workerId;
    }

    private long waitUntil(long target) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < target) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
