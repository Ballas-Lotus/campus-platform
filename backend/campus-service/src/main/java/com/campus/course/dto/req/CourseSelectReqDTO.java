package com.campus.course.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 选课 / 退课请求（都以教学班为操作单位）。
 */
@Data
public class CourseSelectReqDTO {

    @NotNull(message = "教学班不能为空")
    private Long classId;
}
