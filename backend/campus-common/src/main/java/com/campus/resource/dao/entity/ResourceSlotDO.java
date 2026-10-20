package com.campus.resource.dao.entity;

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
 * 资源时间片实体（t_resource_slot）。
 *
 * <p>大表（59.8 万行，防坑 5）：查询必须命中 {@code uk_resource_slot(resource_id, slot_date, start_time)}
 * 或 {@code idx_date_status}，禁止无索引扫描。</p>
 */
@Data
@TableName("t_resource_slot")
public class ResourceSlotDO {

    /** 阶段 14：分片表主键改用雪花（AUTO 在 8 个物理分片间会撞号） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 分片键不参与 UPDATE（阶段 14：ShardingSphere 禁止修改分片列；updateById 需排除） */
    @TableField(value = "resource_id", updateStrategy = FieldStrategy.NEVER)
    private Long resourceId;

    private LocalDate slotDate;

    private LocalTime startTime;

    private LocalTime endTime;

    /** 本片容量 */
    private Integer capacity;

    /** 已订数 */
    private Integer booked;

    /** 乐观锁版本号 */
    private Integer version;

    /** 状态：1 可用 0 停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
