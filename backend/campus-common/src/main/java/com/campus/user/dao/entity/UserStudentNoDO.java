package com.campus.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 学号 → 用户 索引表（阶段 14"读扩散"解法；按 CRC32(student_no) 分片）。
 *
 * <p>比 12306 蓝图（phone/mail 两张）多一张：本平台三种登录方式中"学号"是主路径，
 * 同为高频查询，同法处理（见 docs/04 §4.4 落地说明）。</p>
 */
@Data
@TableName("t_user_student_no")
public class UserStudentNoDO {

    /** 学号（明文；主键即分片键） */
    @TableId(type = IdType.INPUT)
    private String studentNo;

    private Long userId;
}
