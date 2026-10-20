package com.campus.course.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.course.dao.entity.CourseSelectionDO;
import com.campus.course.dto.resp.MyCourseRespDTO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface CourseSelectionMapper extends BaseMapper<CourseSelectionDO> {

    /**
     * 我在某学期已选教学班（仅时段信息，供时间冲突检测）。
     */
    @Select("""
            SELECT c.id            AS classId,
                   c.week_day      AS weekDay,
                   c.start_section AS startSection,
                   c.end_section   AS endSection,
                   co.name         AS courseName,
                   c.class_name    AS className
            FROM t_course_selection s
            JOIN t_course_class c ON c.id = s.class_id AND c.deleted = 0
            JOIN t_course co ON co.id = c.course_id AND co.deleted = 0
            WHERE s.user_id = #{userId} AND s.semester = #{semester} AND s.status = 'SELECTED'
            """)
    List<Map<String, Object>> selectMyClassSlots(@Param("userId") Long userId,
                                                 @Param("semester") String semester);

    /**
     * 阶段 21（受理制）：我当前已选的全部教学班 id —— 用于惰性初始化
     * {@code campus:sel:{userId}} 集合（受理闸门的"快速已选检查"，防"受理后才被 DB 判重复"）。
     * 按 user_id 分片 → 单分片点查。
     */
    @Select("SELECT class_id FROM t_course_selection WHERE user_id = #{userId} AND status = 'SELECTED'")
    List<Long> selectSelectedClassIds(@Param("userId") Long userId);

    /**
     * 我在指定教学班上的选课状态（供教学班列表标记"已选/已退"）。
     */
    @Select("""
            <script>
            SELECT class_id AS classId, status
            FROM t_course_selection
            WHERE user_id = #{userId} AND class_id IN
            <foreach collection="classIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectMyStatusInClasses(@Param("userId") Long userId,
                                                      @Param("classIds") List<Long> classIds);

    /**
     * 我的选课汇总：课程数 + 学分合计（个人中心统计）。
     */
    @Select("""
            SELECT COUNT(*) AS cnt, IFNULL(SUM(co.credits), 0) AS credits
            FROM t_course_selection s
            JOIN t_course_class c ON c.id = s.class_id AND c.deleted = 0
            JOIN t_course co ON co.id = c.course_id AND co.deleted = 0
            WHERE s.user_id = #{userId} AND s.status = 'SELECTED'
            """)
    Map<String, Object> mySummary(@Param("userId") Long userId);

    /**
     * 我在这批课程下已选的教学班名（供课程列表打"已选"标记，避免重复选课）。
     */
    @Select("""
            <script>
            SELECT c.course_id AS courseId,
                   GROUP_CONCAT(c.class_name ORDER BY c.id SEPARATOR '、') AS classNames
            FROM t_course_selection s
            JOIN t_course_class c ON c.id = s.class_id AND c.deleted = 0
            WHERE s.user_id = #{userId} AND s.status = 'SELECTED' AND c.course_id IN
            <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY c.course_id
            </script>
            """)
    List<Map<String, Object>> selectMyCoursesIn(@Param("userId") Long userId,
                                                @Param("courseIds") List<Long> courseIds);

    /**
     * 我的课表（联课程/教学班信息，按周几+节次排序）。
     */
    @Select("""
            SELECT s.id, s.class_id, s.semester, s.create_time,
                   c.course_id, c.class_name, c.teacher, c.week_day, c.start_section, c.end_section,
                   c.start_week, c.end_week, c.location,
                   co.course_code, co.name AS course_name, co.credits, co.course_type, co.college
            FROM t_course_selection s
            JOIN t_course_class c ON c.id = s.class_id AND c.deleted = 0
            JOIN t_course co ON co.id = c.course_id AND co.deleted = 0
            WHERE s.user_id = #{userId} AND s.status = 'SELECTED'
            ORDER BY c.week_day, c.start_section
            """)
    List<MyCourseRespDTO> selectMyCourses(@Param("userId") Long userId);
}
