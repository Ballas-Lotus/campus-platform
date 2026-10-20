package com.campus.user.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 当日日程（阶段 10）：位图反解出的预约占用段 + 当日课程，合并按时间排序。
 */
@Data
@Builder
public class ScheduleRespDTO {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 当日占用位图（24 hex；全空为 24 个 0） */
    private String bitmap;

    private List<ScheduleItemDTO> items;

    @Data
    @Builder
    public static class ScheduleItemDTO {

        /** RESERVATION / COURSE */
        private String type;

        private String title;

        private String location;

        @JsonFormat(pattern = "HH:mm")
        private LocalTime startTime;

        @JsonFormat(pattern = "HH:mm")
        private LocalTime endTime;

        /** 预约状态（PENDING/APPROVED/COMPLETED/...；课程为 null） */
        private String status;

        /** LIBRARY_SEAT / VENUE / LECTURE / COURSE */
        private String resourceType;

        private Long reservationId;

        private String reservationNo;
    }
}
