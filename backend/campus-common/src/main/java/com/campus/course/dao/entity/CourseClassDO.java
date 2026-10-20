package com.campus.course.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 教学班（t_course_class，300 个）。
 */
@Data
@TableName("t_course_class")
public class CourseClassDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    /** 01班 / 02班 */
    private String className;

    private String teacher;

    private String semester;

    private Integer capacity;

    /** 已选人数（容量计数扣减字段） */
    private Integer selected;

    /** 上课星期 1~7 */
    private Integer weekDay;

    private Integer startSection;

    private Integer endSection;

    private Integer startWeek;

    private Integer endWeek;

    private String location;

    @TableLogic
    private Integer deleted;
}
