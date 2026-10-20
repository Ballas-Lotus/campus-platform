package com.campus.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.reservation.dao.entity.UserTimeOccupancyDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface UserTimeOccupancyMapper extends BaseMapper<UserTimeOccupancyDO> {

    /**
     * 悲观锁读取当日位图（事务内"读-改-写"防并发丢更新）。
     */
    @Select("SELECT * FROM t_user_time_occupancy WHERE user_id = #{userId} AND occupy_date = #{date} FOR UPDATE")
    UserTimeOccupancyDO selectForUpdate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 覆盖写入位图（应用层已算好 OR / AND NOT 结果）。
     */
    @Update("UPDATE t_user_time_occupancy SET bitmap = #{bitmap}, version = version + 1, update_time = NOW() "
            + "WHERE user_id = #{userId} AND occupy_date = #{date}")
    int updateBitmap(@Param("userId") Long userId, @Param("date") LocalDate date, @Param("bitmap") String bitmap);
}
