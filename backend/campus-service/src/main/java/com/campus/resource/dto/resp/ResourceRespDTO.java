package com.campus.resource.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资源列表项（GET /api/resource/list）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceRespDTO {

    private Long id;

    /** LIBRARY_SEAT / VENUE / LECTURE_SEAT / COURSE_QUOTA */
    private String resourceType;

    private String name;

    private Long parentId;

    private String location;

    private String campus;

    private Integer capacity;

    /** 1 区间独占 0 容量计数 */
    private Integer exclusive;

    private Integer needApproval;

    private Integer status;
}
