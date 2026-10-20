package com.campus.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.reservation.dao.entity.ReservationDO;
import com.campus.reservation.dto.resp.MyReservationRespDTO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface ReservationMapper extends BaseMapper<ReservationDO> {

    /**
     * 我的预约按状态计数（个人中心统计；走 idx_user_status）。
     */
    @Select("SELECT status, COUNT(*) AS n FROM t_reservation WHERE user_id = #{userId} GROUP BY status")
    List<Map<String, Object>> countByStatus(@Param("userId") Long userId);

    /**
     * 当前最大主键（单号序列水位初始化用；种子数据中 id 与全局序列一一对应）。
     */
    @Select("SELECT COALESCE(MAX(id), 0) FROM t_reservation")
    Long selectMaxId();

    /**
     * 我的预约（分页，联资源名；走 idx_user_status）。
     */
    @Select("""
            <script>
            SELECT r.id, r.reservation_no AS reservationNo, r.resource_id AS resourceId,
                   res.name AS resourceName, res.location AS resourceLocation, res.campus AS campus,
                   r.resource_type AS resourceType, r.slot_date AS slotDate,
                   r.start_time AS startTime, r.end_time AS endTime,
                   r.status, r.checkin_time AS checkinTime, r.remark, r.create_time AS createTime
            FROM t_reservation r
            LEFT JOIN t_resource res ON res.id = r.resource_id
            WHERE r.user_id = #{userId}
            <if test="status != null and status != ''">AND r.status = #{status}</if>
            ORDER BY r.slot_date DESC, r.start_time DESC, r.id DESC
            </script>
            """)
    IPage<MyReservationRespDTO> selectMyPage(IPage<MyReservationRespDTO> page,
                                             @Param("userId") Long userId,
                                             @Param("status") String status);
}
