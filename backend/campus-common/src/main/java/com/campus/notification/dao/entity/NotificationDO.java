package com.campus.notification.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内信（t_notification，阶段 13 MQ 化）。
 *
 * <p>由 campus-service 的 MQ 消费者异步写入（campus_notification_topic），
 * user-service 读取（顶栏铃铛 + 个人中心）。</p>
 */
@Data
@TableName("t_notification")
public class NotificationDO {

    /** 阶段 14：分片表主键改用雪花（AUTO 在 8 个物理分片间会撞号） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 分片键不参与 UPDATE（阶段 14：ShardingSphere 禁止修改分片列；updateById 需排除） */
    @TableField(value = "user_id", updateStrategy = FieldStrategy.NEVER)
    private Long userId;

    private String title;

    private String content;

    /** RESERVATION / COURSE / MQ_DEMO… */
    private String bizType;

    /** 业务标识（预约号 / MQ 消息键） */
    private String bizId;

    /** 0 未读 1 已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
