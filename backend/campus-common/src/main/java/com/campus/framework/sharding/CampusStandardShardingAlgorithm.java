package com.campus.framework.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Properties;
import java.util.zip.CRC32;

/**
 * 单键分片算法（阶段 14；对照 12306 {@code CustomDbHashModShardingAlgorithm} 的校园改造版）。
 *
 * <p>配置（CLASS_BASED）：{@code level=db|table}、{@code sharding-count=8}、
 * {@code table-sharding-count=4}——同一个类通过 {@code level} 参数扮演库算法或表算法：</p>
 * <pre>
 *   分片号 s = h(value) % sharding-count
 *   库后缀 = s / table-sharding-count      （s=0..7 → campus_0 | campus_1）
 *   表后缀 = s % table-sharding-count      （s=0..7 → _0.._3）
 * </pre>
 *
 * <p>h(value)：数值（user_id / id / resource_id）→ 自身；字符串（索引表 phone/email/student_no）
 * → CRC32。**CRC32 与 MySQL 的 {@code CRC32()} 函数逐位一致**（zlib 多项式，已实测），
 * 故迁移脚本可以用 {@code WHERE CRC32(phone) % 8 = s} 精确切分索引表。</p>
 *
 * <p>范围查询（如 id BETWEEN）返回全分片——由 ShardingSphere 合并，语义与单库一致。</p>
 */
public final class CampusStandardShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private static final String SHARDING_COUNT_KEY = "sharding-count";
    private static final String TABLE_SHARDING_COUNT_KEY = "table-sharding-count";
    /** 本类角色：db（库层）或 table（表层）。注意不能用 "strategy"——那是 CLASS_BASED 包装器的保留参数
     *  （声明标准/复合接口类型：standard/complex） */
    private static final String LEVEL_KEY = "level";

    private int shardingCount;
    private int tableShardingCount;
    private boolean dbLevel;

    @Override
    public void init(Properties props) {
        this.shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY));
        this.tableShardingCount = Integer.parseInt(props.getProperty(TABLE_SHARDING_COUNT_KEY, String.valueOf(shardingCount)));
        this.dbLevel = "db".equalsIgnoreCase(props.getProperty(LEVEL_KEY, "table"));
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Comparable<?>> shardingValue) {
        int shard = shardIndex(shardingValue.getValue());
        if (shard < 0) {
            return null;
        }
        int suffix = dbLevel ? shard / tableShardingCount : shard % tableShardingCount;
        return findMatchedTargetName(availableTargetNames, String.valueOf(suffix));
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames, RangeShardingValue<Comparable<?>> shardingValue) {
        // 范围查询（日期区间/ID 区间等）：全路由，交由 ShardingSphere 归并
        return availableTargetNames;
    }

    @Override
    public String getType() {
        return "CLASS_BASED";
    }

    /** 值 → 分片号；无法处理返回 -1（调用方按全路由兜底） */
    private int shardIndex(Comparable<?> value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number number) {
            return (int) Math.floorMod(number.longValue(), shardingCount);
        }
        CRC32 crc = new CRC32();
        crc.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % shardingCount);
    }

    /** 在可用目标（ds_0/ds_1 或 t_x_0.._3）中找后缀匹配项 */
    static String findMatchedTargetName(Collection<String> availableTargetNames, String suffix) {
        for (String name : availableTargetNames) {
            if (name.endsWith("_" + suffix)) {
                return name;
            }
        }
        return null;
    }
}
