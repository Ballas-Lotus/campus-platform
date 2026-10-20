package com.campus.stress.dao.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 压测候选用户查询（仅阶段 9 演示用）。
 *
 * <p>阶段 14（分库分表）改造：原 {@code t_user LEFT JOIN t_user_quota} 跨表查询
 * 拆成两次单表查询（用户候选 + 配额批量），配额过滤在服务层完成——
 * 跨分片表的 JOIN 在分片中间件下要么需要绑定表声明、要么退化为笛卡尔路由，
 * 拆查询是分片改造的常规手法（见 docs/04 §6.7）。</p>
 */
public interface StressMapper {

    /**
     * 抢场馆名额的用户候选：真实学生（4~10003，排除演示/测试/机器人账号）。
     * （分片环境下 id 区间为范围查询 → 全分片命中后归并排序取前 N，语义与单库一致。）
     */
    @Select("""
            SELECT id FROM t_user
            WHERE deleted = 0 AND status = 1 AND id BETWEEN 4 AND 10003
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> pickVenueCandidates(@Param("limit") int limit);

    /**
     * 批量取本周场馆配额（第二步：只对候选用户查配额）。
     */
    @Select("""
            <script>
            SELECT user_id AS userId, used_times AS usedTimes
            FROM t_user_quota
            WHERE quota_type = 'VENUE_WEEKLY' AND period_start = #{weekStart}
              AND user_id IN
              <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectVenueQuotaIn(@Param("weekStart") LocalDate weekStart,
                                                 @Param("userIds") List<Long> userIds);

    /**
     * 抢课程名额的候选用户（压测班为周日时段，与全部种子课表天然不冲突）。
     */
    @Select("""
            SELECT id FROM t_user
            WHERE deleted = 0 AND status = 1 AND id BETWEEN 4 AND 10003
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<Long> pickCourseRacers(@Param("limit") int limit);
}
