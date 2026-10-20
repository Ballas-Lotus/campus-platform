package com.campus.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 邮箱 → 用户 索引表（阶段 14"读扩散"解法；按 CRC32(email) 分片） */
@Data
@TableName("t_user_mail")
public class UserMailDO {

    /** 邮箱（明文；主键即分片键） */
    @TableId(type = IdType.INPUT)
    private String email;

    private Long userId;
}
