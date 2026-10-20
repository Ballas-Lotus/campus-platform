package com.campus.course.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 教学班列表项（供"查看教学班 / 选课"抽屉）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseClassRespDTO {

    private Long id;

    private Long courseId;

    /** 01班 / 02班 */
    private String className;

    private String teacher;

    private String semester;

    private Integer capacity;

    private Integer selected;

    /** 剩余名额 = capacity - selected */
    private Integer available;

    /** 上课星期 1~7 */
    private Integer weekDay;

    private Integer startSection;

    private Integer endSection;

    private Integer startWeek;

    private Integer endWeek;

    private String location;

    /** 我的选课状态：SELECTED（已选）/ DROPPED（已退）/ null（未选） */
    private String myStatus;
}
