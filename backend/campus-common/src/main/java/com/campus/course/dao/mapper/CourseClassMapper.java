package com.campus.course.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.campus.course.dao.entity.CourseClassDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface CourseClassMapper extends BaseMapper<CourseClassDO> {

    /**
     * 批量聚合指定课程的教学班概要（供课程列表第二段查询，避免 N+1）。
     */
    @Select("""
            <script>
            SELECT course_id AS courseId,
                   COUNT(*) AS classCount,
                   SUM(capacity) AS totalCapacity,
                   SUM(selected) AS totalSelected
            FROM t_course_class
            WHERE deleted = 0
              AND course_id IN
              <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY course_id
            </script>
            """)
    List<Map<String, Object>> aggregateByCourseIds(@Param("courseIds") List<Long> courseIds);

    /**
     * 占一个名额——<b>防超卖唯一防线</b>（条件更新：成功 1 行 = 抢到，0 行 = 已满）。
     */
    @Update("UPDATE t_course_class SET selected = selected + 1 " +
            "WHERE id = #{classId} AND deleted = 0 AND selected < capacity")
    int occupySeat(@Param("classId") Long classId);

    /**
     * 退课释放名额（selected 不为负，0 行 = 数据已一致，无需释放）。
     */
    @Update("UPDATE t_course_class SET selected = selected - 1 " +
            "WHERE id = #{classId} AND deleted = 0 AND selected > 0")
    int releaseSeat(@Param("classId") Long classId);

    /**
     * 教学班总览（供工作台统计）。
     */
    @Select("SELECT COUNT(*) AS classCount, IFNULL(SUM(capacity), 0) AS totalCapacity, " +
            "IFNULL(SUM(selected), 0) AS totalSelected FROM t_course_class WHERE deleted = 0")
    Map<String, Object> summary();

    /**
     * 阶段 19：按总容量取"热门课程"（抢课窗口预热用——总容量大 ≈ 选课人数多）。
     */
    @Select("SELECT course_id FROM t_course_class WHERE deleted = 0 " +
            "GROUP BY course_id ORDER BY SUM(capacity) DESC LIMIT #{limit}")
    List<Long> topCourseIdsByCapacity(@Param("limit") int limit);
}
