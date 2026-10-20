package com.campus.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户时间占用位图（t_user_time_occupancy）。
 *
 * <p>组合主键 (user_id, occupy_date)，无自增 id；跨资源冲突检测的唯一入口。
 * 由分片键友好设计：user_id 为首列 → 阶段 14 分片后永不跨片。</p>
 */
@Data
@TableName("t_user_time_occupancy")
public class UserTimeOccupancyDO {

    private Long userId;

    private LocalDate occupyDate;

    /** 当日占用位图（24 hex，bit i = 第 i 个 15 分钟格） */
    private String bitmap;

    /** 乐观锁版本号 */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
