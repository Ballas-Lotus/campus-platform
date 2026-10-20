package com.campus.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体（t_user，见 docs/01 3.1）。
 */
@Data
@TableName("t_user")
public class UserDO {

    /** 阶段 14：分片表主键改用雪花（t_user 按 id 分片；AUTO 在 8 个物理分片间会撞号） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 学号（登录名，唯一） */
    private String studentNo;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 手机号（登录方式之一，唯一） */
    private String phone;

    /** 邮箱（登录方式之一，唯一） */
    private String email;

    /** 学院 */
    private String college;

    /** 专业 */
    private String major;

    /** 年级 */
    private String grade;

    /** 角色：STUDENT/TEACHER/ADMIN */
    private String role;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删 1 已删（MP @TableLogic 自动过滤） */
    @TableLogic
    private Integer deleted;
}
