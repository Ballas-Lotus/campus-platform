package com.campus.library.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.library.dao.entity.LibraryAreaDO;
import com.campus.library.dto.resp.LibraryAreaRespDTO;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface LibraryAreaMapper extends BaseMapper<LibraryAreaDO> {

    /**
     * 楼层列表 + 今日空闲率统计（座位按 location 前缀归属楼层，如 "逸夫楼1层A区" → "逸夫楼1层"）。
     */
    @Select("""
            SELECT a.id, a.name, a.campus, a.floor, a.open_time, a.close_time, a.seat_count,
                   COUNT(s.id) AS total_slots,
                   IFNULL(SUM(CASE WHEN s.booked < s.capacity THEN 1 ELSE 0 END), 0) AS free_slots
            FROM t_library_area a
            LEFT JOIN t_resource r ON r.resource_type = 'LIBRARY_SEAT' AND r.status = 1
                 AND r.location LIKE CONCAT(a.name, '%')
            LEFT JOIN t_resource_slot s ON s.resource_id = r.id AND s.slot_date = CURDATE() AND s.status = 1
            WHERE a.deleted = 0
            GROUP BY a.id, a.name, a.campus, a.floor, a.open_time, a.close_time, a.seat_count
            ORDER BY a.id
            """)
    List<LibraryAreaRespDTO> listWithTodayStats();
}
