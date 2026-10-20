package com.campus.framework.sharding;

/**
 * 基因法工具（阶段 14；照搬 12306 订单号"基因"设计，见 docs/04 §4.2）。
 *
 * <p>单号格式：{@code RES + 雪花ID + 6 位基因}，基因 = {@code user_id % 1_000_000}（左补零）。</p>
 *
 * <p>数学上：1,000,000 = 8 × 125,000，故 {@code 基因 % 8 == user_id % 8}，
 * 分片算法解析单号尾 6 位即可路由到与 user_id 完全相同的分片——
 * "按单号查"与"按 user_id 查"命中同一分片，避免广播查询。</p>
 *
 * <p>注意：分片总数/每库表数须与 shardingsphere-config.yaml 中
 * {@code sharding-count / table-sharding-count} 保持一致。</p>
 */
public final class GeneUtil {

    /** 基因取模基数（6 位） */
    public static final long GENE_MOD = 1_000_000L;
    /** 分片总数 = 2 库 × 4 表 */
    public static final int SHARD_COUNT = 8;
    /** 每库表数 */
    public static final int TABLES_PER_DB = 4;
    /** 单号前缀（预约） */
    public static final String RESERVATION_PREFIX = "RES";

    private GeneUtil() {
    }

    /** 取基因值（user_id % 1,000,000） */
    public static long gene(long userId) {
        return Math.floorMod(userId, GENE_MOD);
    }

    /** 6 位左补零基因串（拼在单号尾部） */
    public static String geneSuffix(long userId) {
        return String.format("%06d", gene(userId));
    }

    /** 生成带基因的预约单号：RES + 雪花ID + 基因 */
    public static String reservationNo(long snowflakeId, long userId) {
        return RESERVATION_PREFIX + snowflakeId + geneSuffix(userId);
    }

    /** user_id → 分片号（0~7） */
    public static int shardFromUserId(long userId) {
        return (int) Math.floorMod(userId, SHARD_COUNT);
    }

    /** 单号 → 分片号；解析失败返回 -1（调用方按全路由兜底） */
    public static int shardFromReservationNo(String reservationNo) {
        if (reservationNo == null || reservationNo.length() < 6) {
            return -1;
        }
        String tail = reservationNo.substring(reservationNo.length() - 6);
        try {
            return (int) (Long.parseLong(tail) % SHARD_COUNT);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 分片号 → 库后缀（campus_0 / campus_1） */
    public static int dbSuffix(int shard) {
        return shard / TABLES_PER_DB;
    }

    /** 分片号 → 表后缀（_0 ~ _3） */
    public static int tableSuffix(int shard) {
        return shard % TABLES_PER_DB;
    }

    /** 人类可读的分片描述（观测接口展示用） */
    public static String describe(int shard) {
        return "campus_" + dbSuffix(shard) + " · 表后缀 _" + tableSuffix(shard);
    }
}
