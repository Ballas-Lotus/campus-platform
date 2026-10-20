package com.campus.sharding;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.framework.sharding.GeneUtil;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dao.mapper.ReservationMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 分库分表观测服务（阶段 14）：观测接口的数据源。
 *
 * <p>两个视角：</p>
 * <ol>
 *   <li><b>物理视角</b>——绕过 ShardingSphere，直连 campus_0 / campus_1 数物理表的行数
 *       （证明"数据真的被切开"）；</li>
 *   <li><b>逻辑视角</b>——经 ShardingSphere 走真实查询（按 user_id / 按单号），
 *       验证路由结果与基因法推导完全一致。</li>
 * </ol>
 */
@Slf4j
@Service
public class ShardingService {

    /** 分片表清单（逻辑表名）：观测页展示用 */
    public static final List<String> SHARDED_TABLES = List.of(
            "t_reservation", "t_resource_slot", "t_user_time_occupancy", "t_user_quota",
            "t_course_selection", "t_notification", "t_user");
    /** 广播表清单 */
    public static final List<String> BROADCAST_TABLES = List.of(
            "t_resource", "t_lecture", "t_venue", "t_library_area", "t_course", "t_course_class");
    /** 索引表（读扩散：phone / email / student_no → user_id） */
    public static final List<String> INDEX_TABLES = List.of("t_user_phone", "t_user_mail", "t_user_student_no");

    private final ReservationMapper reservationMapper;

    private final HikariDataSource physical0;
    private final HikariDataSource physical1;

    /** 物理行数缓存（30 秒），避免观测页反复全表 COUNT */
    private volatile Map<String, Object> rowCountsCache;
    private volatile long rowCountsCacheAt;

    public ShardingService(ReservationMapper reservationMapper,
                           @Value("${campus.sharding.physical.url0}") String url0,
                           @Value("${campus.sharding.physical.url1}") String url1,
                           @Value("${campus.sharding.physical.username}") String username,
                           @Value("${campus.sharding.physical.password}") String password) {
        this.reservationMapper = reservationMapper;
        this.physical0 = buildPhysical(url0, username, password);
        this.physical1 = buildPhysical(url1, username, password);
        log.info("[分库分表观测] 物理直连就绪: campus_0 / campus_1（仅观测接口使用，不参与业务）");
    }

    private HikariDataSource buildPhysical(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setPoolName("sharding-observe");
        return new HikariDataSource(config);
    }

    @PreDestroy
    void close() {
        physical0.close();
        physical1.close();
    }

    // ------------------------------------------------------------------
    // 拓扑 + 物理行数
    // ------------------------------------------------------------------

    public Map<String, Object> info() {
        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("databases", List.of("campus_0", "campus_1"));
        topology.put("shardsPerTable", GeneUtil.TABLES_PER_DB);
        topology.put("totalShards", GeneUtil.SHARD_COUNT);
        topology.put("shardingRule", "shard = MOD(分片键, 8)；库 = shard / 4；表 = shard % 4");
        topology.put("shardedTables", SHARDED_TABLES);
        topology.put("broadcastTables", BROADCAST_TABLES);
        topology.put("indexTables", INDEX_TABLES);
        topology.put("encryptColumns", List.of("t_user.phone (AES)", "t_user.email (AES)"));
        topology.put("gene", "reservation_no = RES + 雪花ID + 6 位基因(user_id % 1000000)——"
                + "尾 6 位可直接路由（1,000,000 整除 8，与 user_id 恒等分片）");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topology", topology);
        result.put("rowCounts", rowCounts());
        return result;
    }

    /** 各分片物理行数（物理直连 COUNT；30 秒缓存） */
    public Map<String, Object> rowCounts() {
        Map<String, Object> cache = rowCountsCache;
        if (cache != null && System.currentTimeMillis() - rowCountsCacheAt < TimeUnit.SECONDS.toMillis(30)) {
            return cache;
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : SHARDED_TABLES) {
            counts.put(table, shardCounts(table));
        }
        Map<String, Object> broadcast = new LinkedHashMap<>();
        for (String table : BROADCAST_TABLES) {
            broadcast.put(table, countSingle(physical0, table));
        }
        Map<String, Object> index = new LinkedHashMap<>();
        for (String table : INDEX_TABLES) {
            index.put(table, countSingle(physical0, table) + countSingle(physical1, table));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sharded", counts);
        result.put("broadcastPerDb", broadcast);
        result.put("indexTotal", index);
        rowCountsCache = result;
        rowCountsCacheAt = System.currentTimeMillis();
        return result;
    }

    /** 逻辑表 → 8 个物理分片的行数 [{target: 'campus_0.t_reservation_0', rows: n}, ...] */
    private List<Map<String, Object>> shardCounts(String table) {
        List<Map<String, Object>> list = new ArrayList<>(GeneUtil.SHARD_COUNT);
        for (int shard = 0; shard < GeneUtil.SHARD_COUNT; shard++) {
            int db = GeneUtil.dbSuffix(shard);
            int suffix = GeneUtil.tableSuffix(shard);
            String target = "campus_" + db + "." + table + "_" + suffix;
            DataSource ds = db == 0 ? physical0 : physical1;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("target", target);
            row.put("rows", countSingle(ds, table + "_" + suffix));
            list.add(row);
        }
        return list;
    }

    private long countSingle(DataSource ds, String physicalTable) {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + physicalTable)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (Exception e) {
            log.warn("[分库分表观测] 物理表计数失败: {}（{}）", physicalTable, e.getMessage());
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 路由验证（基因法）
    // ------------------------------------------------------------------

    /**
     * 路由验证：同一 user_id 与同一条单号，推导的路由必须一致；
     * 并顺势经 ShardingSphere 执行真实查询，返回查询结果（查得到 = 路由正确）。
     */
    public Map<String, Object> route(Long userId, String reservationNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shardingRule", "shard = MOD(分片键, 8)；库 = shard / 4；表 = shard % 4");

        if (userId != null) {
            int shard = GeneUtil.shardFromUserId(userId);
            result.put("userId", userId);
            result.put("userShard", describeShard(shard));
            result.put("verifyByUserId", verifyByUserId(userId));
        }
        if (reservationNo == null && userId != null) {
            // 未给单号：取该用户最近一条预约的单号做演示
            List<ReservationDO> one = reservationMapper.selectList(Wrappers.lambdaQuery(ReservationDO.class)
                    .eq(ReservationDO::getUserId, userId)
                    .orderByDesc(ReservationDO::getId)
                    .last("LIMIT 1"));
            if (!one.isEmpty()) {
                reservationNo = one.get(0).getReservationNo();
                result.put("reservationNoFromDb", "（未传单号，自动取该用户最近一条预约）");
            }
        }
        if (reservationNo != null) {
            int shard = GeneUtil.shardFromReservationNo(reservationNo);
            result.put("reservationNo", reservationNo);
            result.put("gene", reservationNo.length() >= 6
                    ? reservationNo.substring(reservationNo.length() - 6) : null);
            if (shard >= 0) {
                result.put("noShard", describeShard(shard));
                if (userId != null) {
                    result.put("sameShard", shard == GeneUtil.shardFromUserId(userId));
                }
            } else {
                result.put("noShard", "无法解析（单号格式不含基因）→ 全路由兜底");
            }
            result.put("verifyByNo", verifyByNo(reservationNo));
        }
        return result;
    }

    private Map<String, String> describeShard(int shard) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("shard", String.valueOf(shard));
        m.put("database", "campus_" + GeneUtil.dbSuffix(shard));
        m.put("tableSuffix", "_" + GeneUtil.tableSuffix(shard));
        m.put("t_reservation", "campus_" + GeneUtil.dbSuffix(shard) + ".t_reservation_" + GeneUtil.tableSuffix(shard));
        return m;
    }

    /** 经 ShardingSphere 的真实查询（带 user_id → 单分片） */
    private Map<String, Object> verifyByUserId(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Long reservations = reservationMapper.selectCount(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getUserId, userId));
        m.put("sql", "SELECT COUNT(*) FROM t_reservation WHERE user_id = " + userId);
        m.put("routed", "单分片（user_id 为分片键）");
        m.put("count", reservations);
        return m;
    }

    /** 经 ShardingSphere 的真实查询（带单号 → 基因路由） */
    private Map<String, Object> verifyByNo(String reservationNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        ReservationDO row = reservationMapper.selectOne(Wrappers.lambdaQuery(ReservationDO.class)
                .eq(ReservationDO::getReservationNo, reservationNo)
                .last("LIMIT 1"));
        m.put("sql", "SELECT * FROM t_reservation WHERE reservation_no = '" + reservationNo + "'");
        m.put("routed", "基因路由（尾 6 位 → 与 user_id 同分片）");
        if (row == null) {
            m.put("found", false);
        } else {
            m.put("found", true);
            m.put("id", row.getId());
            m.put("userId", row.getUserId());
            m.put("status", row.getStatus());
            m.put("slotDate", String.valueOf(row.getSlotDate()));
        }
        return m;
    }
}
