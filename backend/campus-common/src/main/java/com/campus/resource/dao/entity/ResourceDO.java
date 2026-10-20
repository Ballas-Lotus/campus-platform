package com.campus.resource.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一资源表实体（t_resource，见 docs/01 3.3）。
 */
@Data
@TableName("t_resource")
public class ResourceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 资源类型：LIBRARY_SEAT / VENUE / LECTURE_SEAT / COURSE_QUOTA */
    private String resourceType;

    /** 资源名称 */
    private String name;

    /** 父资源：讲座席位 -> 讲座 id；课程名额 -> 教学班 id；其余 NULL */
    private Long parentId;

    /** 位置描述 */
    private String location;

    /** 校区 */
    private String campus;

    /** 容量：独占=1，容量计数=N */
    private Integer capacity;

    /** 库存语义：1 区间独占 0 容量计数 */
    private Integer exclusive;

    /** 是否需要审批 */
    private Integer needApproval;

    /** 状态：1 可预约 0 停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
