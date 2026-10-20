package com.campus.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约实体（t_reservation，四业务共用）。
 *
 * <p>注意：不包含生成列 active_key（由数据库维护），插入/更新均不涉及。</p>
 */
@Data
@TableName("t_reservation")
public class ReservationDO {

    /** 阶段 14：分片表主键改用雪花（由 CampusIdGenerator 生成；AUTO 在 8 个物理分片间会撞号） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务单号（唯一）：RES + 雪花ID + 6 位基因（user_id % 1000000，见 GeneUtil）；
     * 分片键之一（复合 user_id + reservation_no）——创建后不可变，不参与 UPDATE（ShardingSphere 禁止改分片列）
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String reservationNo;

    /** 分片键不参与 UPDATE（阶段 14：ShardingSphere 禁止修改分片列；updateById 需排除） */
    @TableField(value = "user_id", updateStrategy = FieldStrategy.NEVER)
    private Long userId;

    private Long resourceId;

    /** 冗余资源类型，便于分类查询 */
    private String resourceType;

    /** 起始时间片 ID（未来预约必填） */
    private Long slotId;

    private LocalDate slotDate;

    private LocalTime startTime;

    private LocalTime endTime;

    /** 占用位图（24 hex） */
    private String timeBitmap;

    /** PENDING / APPROVED / CANCELLED / COMPLETED / NO_SHOW */
    private String status;

    /** 签到时间（未签到为 null；开始后未签到将判为爽约） */
    private LocalDateTime checkinTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
