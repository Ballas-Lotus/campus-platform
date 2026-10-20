package com.campus.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 手机号 → 用户 索引表（阶段 14 分库分表"读扩散"解法，见 docs/04 §4.4）。
 *
 * <p>t_user 按 user_id 分片后，"用手机号登录"无法定位分片；先按手机号查询本表
 * （本表按 CRC32(phone) 分片 → 单分片命中）拿到 user_id，再按 user_id 查用户分片。
 * 一次额外查询换取"永不广播"。邮箱/学号同构（{@link UserMailDO} / {@link UserStudentNoDO}）。</p>
 */
@Data
@TableName("t_user_phone")
public class UserPhoneDO {

    /** 手机号（明文；主键即分片键） */
    @TableId(type = IdType.INPUT)
    private String phone;

    private Long userId;
}
