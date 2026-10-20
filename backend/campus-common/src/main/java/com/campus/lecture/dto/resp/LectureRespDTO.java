package com.campus.lecture.dto.resp;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 讲座列表项：讲座信息 + 已约席位数（取自席位时间片 booked 之和）。
 */
@Data
public class LectureRespDTO {

    @TableId
    private Long id;

    private String title;

    private String speaker;

    private String location;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer capacity;

    private Integer status;

    private String description;

    /** 已约席位数（SUM(t_resource_slot.booked)） */
    private Long bookedCount;

    /** 是否可预约：仅未来 7 天内的讲座可约（2026-10-08 规则；7 天后可查看不可约） */
    private Boolean bookable;

    /** 开放预约时间 = 开始时间 - 7 天（前端展示"将于 xx 开放预约"） */
    private LocalDateTime bookableFrom;
}
