package com.campus.course.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 选课记录（t_course_selection）。
 *
 * <p>"选-退-选"为同一行状态变迁（SELECTED ⇄ DROPPED），不新增行；
 * 防重由唯一索引 uk_user_class_semester 兜底（无 deleted，生命周期由 status 表达）。</p>
 */
@Data
@TableName("t_course_selection")
public class CourseSelectionDO {

    /** 阶段 14：分片表主键改用雪花（AUTO 在 8 个物理分片间会撞号） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 分片键不参与 UPDATE（阶段 14：ShardingSphere 禁止修改分片列；updateById 需排除） */
    @TableField(value = "user_id", updateStrategy = FieldStrategy.NEVER)
    private Long userId;

    private Long classId;

    private String semester;

    /** SELECTED / DROPPED */
    private String status;

    /**
     * 阶段 21（受理制）：受理单号。落库时写 MQ 消息携带的 opId，消费端按它判定
     * "重放的消息是否已生效"——已生效只清 pending，绝不补偿（防超卖）；同步老链路为 null。
     */
    @TableField("op_id")
    private String opId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
