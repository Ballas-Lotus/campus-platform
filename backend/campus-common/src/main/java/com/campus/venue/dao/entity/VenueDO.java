package com.campus.venue.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 体育场馆（t_venue，20 个）。
 */
@Data
@TableName("t_venue")
public class VenueDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应 t_resource.id（预约时的资源） */
    private Long resourceId;

    private String name;

    /** BADMINTON / BASKETBALL / TENNIS / GYM / SWIMMING */
    private String venueType;

    private String campus;

    private LocalTime openTime;

    private LocalTime closeTime;

    private BigDecimal pricePerHour;

    /** 是否需要审批 */
    private Integer needApproval;

    /** 每人每周上限（0=不限） */
    private Integer weekLimit;

    @TableLogic
    private Integer deleted;
}
