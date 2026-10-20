package com.campus.resource.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * 时间片（GET /api/resource/{id}/slots?date=）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotRespDTO {

    private Long id;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    private Integer capacity;

    private Integer booked;

    /** 剩余可约数（capacity - booked） */
    private Integer available;

    /** 1 可用 0 停用 */
    private Integer status;
}
