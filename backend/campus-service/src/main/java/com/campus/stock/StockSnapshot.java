package com.campus.stock;

import java.util.Map;

/**
 * 阶段 20：某课程的名额快照（一次读取的一致性视图）。
 *
 * <p>{@code cver} 为课程级版本；每个教学班带自己的 {@code remain/version/capacity}。
 * 由于余量与版本在同一 Lua 脚本内写入同一个 Hash，一次 HGETALL 得到的就是原子快照——
 * 这是 L1 进程内缓存敢于"按版本免失效"的前提。</p>
 *
 * @param cver     课程级版本（服务端当前真实值，永远返回给客户端做新旧比较）
 * @param classes  classId -> 班级名额
 * @param degraded L3 兜底标记：抢锁失败/重建异常时直接返回 DB 真值（未写缓存），前端可提示"降级"
 */
public record StockSnapshot(long cver, Map<Long, ClassStock> classes, boolean degraded) {

    /** 单个教学班的名额视图 */
    public record ClassStock(int remain, long version, int capacity) {
    }

    public static StockSnapshot empty() {
        return new StockSnapshot(0L, Map.of(), false);
    }
}
