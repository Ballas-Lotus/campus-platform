package com.campus.course.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 课程（t_course，200 门）。
 */
@Data
@TableName("t_course")
public class CourseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 课程代码（如 CS101） */
    private String courseCode;

    private String name;

    private String teacher;

    private BigDecimal credits;

    private String college;

    /** 必修 / 选修 / 通识 */
    private String courseType;

    @TableLogic
    private Integer deleted;
}
