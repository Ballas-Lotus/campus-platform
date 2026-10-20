package com.campus.lecture.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lecture.dao.entity.LectureDO;
import com.campus.lecture.dto.resp.LectureRespDTO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface LectureMapper extends BaseMapper<LectureDO> {

    /**
     * 讲座列表 + 已约席位（子查询走 t_resource.idx_parent 定位席位片，再 SUM(booked)）。
     * <p>语义：已约数来自库存账本（t_resource_slot.booked），与"抢座"页面展示同源。</p>
     */
    @Select("""
            SELECT l.*,
                   (SELECT COALESCE(SUM(s.booked), 0)
                    FROM t_resource_slot s
                    JOIN t_resource r ON r.id = s.resource_id
                    WHERE r.parent_id = l.id AND r.resource_type = 'LECTURE') AS booked_count,
                   (l.start_time <= DATE_ADD(NOW(), INTERVAL 7 DAY)) AS bookable,
                   DATE_SUB(l.start_time, INTERVAL 7 DAY) AS bookable_from
            FROM t_lecture l
            WHERE l.deleted = 0
            ORDER BY l.start_time ASC
            """)
    List<LectureRespDTO> listWithBooked();
}
