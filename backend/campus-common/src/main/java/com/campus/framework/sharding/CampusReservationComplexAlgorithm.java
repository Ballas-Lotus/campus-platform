package com.campus.framework.sharding;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 预约表复合分片算法（阶段 14；对照 12306 {@code OrderCommon*ComplexAlgorithm} 的校园改造版）。
 *
 * <p>分片键二选一（SQL 里出现哪个用哪个）：</p>
 * <ol>
 *   <li>{@code user_id}："我的预约"等用户维度查询 → 直接取模；</li>
 *   <li>{@code reservation_no}：按单号查 → 解析尾 6 位基因（见 {@link GeneUtil}）取模
 *       —— 与 user_id 路由结果恒等（1,000,000 整除 8）。</li>
 * </ol>
 *
 * <p>两个键都缺失或单号不合法（历史格式）→ 返回全分片兜底（ShardingSphere 归并，语义不变仅慢）。</p>
 *
 * <p>与 12306 的差异：12306 单号尾 6 位用 {@code String.hashCode} 参与路由，
 * 与其 user_id 侧（数值取模）**并不严格一致**；本实现两侧统一为数值取模（基因左补零 6 位），
 * 数学上可证明恒等。</p>
 */
public final class CampusReservationComplexAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    private static final String SHARDING_COUNT_KEY = "sharding-count";
    private static final String TABLE_SHARDING_COUNT_KEY = "table-sharding-count";
    private static final String LEVEL_KEY = "level";

    private static final String USER_ID_COLUMN = "user_id";
    private static final String RESERVATION_NO_COLUMN = "reservation_no";

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
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        int shard = resolveShard(shardingValue.getColumnNameAndShardingValuesMap());
        if (shard < 0) {
            return availableTargetNames;   // 无可用分片键 → 全路由兜底
        }
        int suffix = dbLevel ? shard / tableShardingCount : shard % tableShardingCount;
        String target = CampusStandardShardingAlgorithm.findMatchedTargetName(availableTargetNames, String.valueOf(suffix));
        return target == null ? availableTargetNames : List.of(target);
    }

    /** 从 SQL 的等值条件中解析分片号：优先 user_id，其次 reservation_no */
    private int resolveShard(Map<String, Collection<Comparable<?>>> valuesMap) {
        if (valuesMap == null || valuesMap.isEmpty()) {
            return -1;
        }
        for (Map.Entry<String, Collection<Comparable<?>>> entry : valuesMap.entrySet()) {
            String column = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
            Collection<Comparable<?>> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            Comparable<?> first = values.iterator().next();
            if (USER_ID_COLUMN.equals(column) && first instanceof Number number) {
                return (int) Math.floorMod(number.longValue(), shardingCount);
            }
            if (RESERVATION_NO_COLUMN.equals(column)) {
                int shard = GeneUtil.shardFromReservationNo(String.valueOf(first));
                if (shard >= 0) {
                    return shard;
                }
            }
        }
        return -1;
    }

    @Override
    public String getType() {
        return "CLASS_BASED";
    }
}
