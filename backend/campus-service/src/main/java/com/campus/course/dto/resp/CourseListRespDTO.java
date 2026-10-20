package com.campus.course.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 课程列表项：课程信息 + 教学班聚合（班级数 / 总容量 / 已选总数）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseListRespDTO {

    private Long id;

    private String courseCode;

    private String name;

    private String teacher;

    private BigDecimal credits;

    private String college;

    private String courseType;

    /** 教学班数量 */
    private Long classCount;

    /** 教学班总容量 */
    private Long totalCapacity;

    /** 已选总人数 */
    private Long totalSelected;

    /** 当前用户是否已选该课程（任一教学班） */
    private Boolean mySelected;

    /** 当前用户已选的教学班名（如 "02班"，多个用"、"连接；未选为 null） */
    private String myClassName;
}
