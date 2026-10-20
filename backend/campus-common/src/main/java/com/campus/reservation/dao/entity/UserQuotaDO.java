package com.campus.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 用户配额（t_user_quota）：图书馆每日 / 场馆每周 / 爽约累计。
 */
@Data
@TableName("t_user_quota")
public class UserQuotaDO {

    /** 阶段 14：分片表主键改用雪花（AUTO 在 8 个物理分片间会撞号） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 分片键不参与 UPDATE（阶段 14：ShardingSphere 禁止修改分片列；updateById 需排除） */
    @TableField(value = "user_id", updateStrategy = FieldStrategy.NEVER)
    private Long userId;

    /** LIBRARY_DAILY / VENUE_WEEKLY / NO_SHOW */
    private String quotaType;

    private Integer usedTimes;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    @TableLogic
    private Integer deleted;
}
