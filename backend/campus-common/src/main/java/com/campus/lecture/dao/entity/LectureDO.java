package com.campus.lecture.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 讲座（t_lecture，20 场）。
 */
@Data
@TableName("t_lecture")
public class LectureDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String speaker;

    private String location;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer capacity;

    /** 1 已发布 0 已下架 */
    private Integer status;

    private String description;

    @TableLogic
    private Integer deleted;
}
