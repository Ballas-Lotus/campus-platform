package com.campus.library.dto.resp;

import lombok.Data;

import java.time.LocalTime;

/**
 * 楼层（馆区）列表项：基础信息 + 今日空闲率。
 *
 * <p>空闲率口径：该楼层全部座位「今日可约时间片」中未占用（booked &lt; capacity）的占比。</p>
 */
@Data
public class LibraryAreaRespDTO {

    private Long id;

    private String name;

    private String campus;

    private Integer floor;

    private LocalTime openTime;

    private LocalTime closeTime;

    private Integer seatCount;

    /** 今日可约时间片总数（座位 × 开放时长） */
    private Long totalSlots;

    /** 其中空闲片数 */
    private Long freeSlots;

    /** 今日空闲率（%，保留 1 位小数；无时间片时为 null） */
    private Double freeRate;
}
