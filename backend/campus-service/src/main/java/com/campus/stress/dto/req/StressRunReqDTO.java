package com.campus.stress.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 压测请求：scenario = OVERSELL / DUPLICATE / BITMAP / COURSE_OVERSELL。
 */
@Data
public class StressRunReqDTO {

    @NotBlank(message = "压测场景不能为空")
    private String scenario;

    /** 并发数（缺省按场景取默认值） */
    private Integer threads;
}
