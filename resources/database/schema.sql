-- ============================================================================
-- 校园综合服务平台 · 建表脚本（阶段 1）
-- ============================================================================
-- 字符集/排序规则：utf8mb4 / utf8mb4_0900_ai_ci
-- 通用约定（详见 docs/01-数据库设计.md）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（演示用；生产应换分布式 ID，见 docs/04）
--   2. 不使用物理外键，约束由应用层保证（为分库分表预留）
--   3. "时刻"类字段用 DATETIME、"时段"类字段用 TIME（与日期字段组合）、周期用 DATE
--   4. 逻辑删除策略（提示词防坑 2 的明确选择）：
--        生命周期完全由 status 表达的表（t_reservation / t_resource_slot /
--        t_user_time_occupancy / t_course_selection）不使用 deleted；
--        其余管理类表使用 deleted 逻辑删除，其唯一索引不含 deleted 列（业务上不放号，见 docs/01）
--   5. t_reservation 防重复预约：使用生成列 active_key（仅 PENDING/APPROVED 为 'Y'），
--        解决"取消-再约-再取消"场景下唯一索引的误伤
-- 执行方式：
--   docker exec -i campus-mysql mysql -uroot -pcampus123456 \
--     --default-character-set=utf8mb4 campus_platform < schema.sql
-- ============================================================================

USE campus_platform;

SET NAMES utf8mb4;

-- ============================================================================
-- 一、用户与权限
-- ============================================================================

DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_no`  VARCHAR(32)     NOT NULL COMMENT '学号（登录名，唯一）',
    `password`    VARCHAR(100)    NOT NULL COMMENT 'BCrypt 加密后的密码',
    `real_name`   VARCHAR(64)     NOT NULL COMMENT '真实姓名',
    `phone`       VARCHAR(20)     NOT NULL COMMENT '手机号（登录方式之一，唯一）',
    `email`       VARCHAR(128)    NOT NULL COMMENT '邮箱（登录方式之一，唯一）',
    `college`     VARCHAR(64)              DEFAULT NULL COMMENT '学院',
    `major`       VARCHAR(64)              DEFAULT NULL COMMENT '专业',
    `grade`       VARCHAR(16)              DEFAULT NULL COMMENT '年级（如 2021）',
    `role`        VARCHAR(16)     NOT NULL DEFAULT 'STUDENT' COMMENT '角色：STUDENT/TEACHER/ADMIN',
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 禁用',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_student_no` (`student_no`) COMMENT '学号登录 + 唯一约束（服务"学号登录"）',
    UNIQUE KEY `uk_phone` (`phone`) COMMENT '手机号登录 + 唯一约束（服务"手机号登录"）',
    UNIQUE KEY `uk_email` (`email`) COMMENT '邮箱登录 + 唯一约束（服务"邮箱登录"）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表（学生/教师/管理员）';

DROP TABLE IF EXISTS `t_user_quota`;
CREATE TABLE `t_user_quota`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    `quota_type`   VARCHAR(32)     NOT NULL COMMENT '配额类型：LIBRARY_DAILY 图书馆每日/ VENUE_WEEKLY 场馆每周/ NO_SHOW 爽约累计',
    `used_times`   INT             NOT NULL DEFAULT 0 COMMENT '周期内已使用次数',
    `period_start` DATE            NOT NULL COMMENT '周期开始（含）',
    `period_end`   DATE            NOT NULL COMMENT '周期结束（含）',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_type_period` (`user_id`, `quota_type`, `period_start`) COMMENT '同一用户同一类型的周期配额只有一行（服务"扣减/查询配额"）',
    KEY `idx_user_type` (`user_id`, `quota_type`) COMMENT '服务"查我的当前配额"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户配额表（预约上限/爽约）';

-- ============================================================================
-- 二、统一资源模型（四个业务共用）
-- ============================================================================

DROP TABLE IF EXISTS `t_resource`;
CREATE TABLE `t_resource`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `resource_type` VARCHAR(32)     NOT NULL COMMENT '资源类型：LIBRARY_SEAT/VENUE/LECTURE_SEAT/COURSE_QUOTA',
    `name`          VARCHAR(128)    NOT NULL COMMENT '资源名称（如"图书馆A馆-3F-A区-001"）',
    `parent_id`     BIGINT UNSIGNED          DEFAULT NULL COMMENT '父资源：讲座席位->讲座 id；课程名额->教学班 id；其余为 NULL',
    `location`      VARCHAR(128)             DEFAULT NULL COMMENT '位置描述（如"A馆3层A区"）',
    `campus`        VARCHAR(32)              DEFAULT NULL COMMENT '校区',
    `capacity`      INT             NOT NULL DEFAULT 1 COMMENT '容量：独占资源=1，容量计数资源=N',
    `exclusive`     TINYINT         NOT NULL DEFAULT 0 COMMENT '库存语义：1 区间独占 0 容量计数',
    `need_approval` TINYINT         NOT NULL DEFAULT 0 COMMENT '是否需要审批：1 是 0 否',
    `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 可预约 0 停用',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_type_campus` (`resource_type`, `campus`, `status`) COMMENT '服务"按类型/校区筛选资源列表"',
    KEY `idx_parent` (`parent_id`) COMMENT '服务"查某讲座/教学班下的席位/名额资源"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='统一资源表（四业务共用）';

DROP TABLE IF EXISTS `t_resource_slot`;
CREATE TABLE `t_resource_slot`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `resource_id` BIGINT UNSIGNED NOT NULL COMMENT '资源 ID',
    `slot_date`   DATE            NOT NULL COMMENT '日期',
    `start_time`  TIME            NOT NULL COMMENT '开始时间（时段）',
    `end_time`    TIME            NOT NULL COMMENT '结束时间（时段；不假设固定时长：图书馆 1 小时、讲座整场、场馆 1 小时）',
    `capacity`    INT             NOT NULL COMMENT '本片容量',
    `booked`      INT             NOT NULL DEFAULT 0 COMMENT '已订数（容量计数语义用；独占语义恒 0/1）',
    `version`     INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 可用 0 停用',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_resource_slot` (`resource_id`, `slot_date`, `start_time`) COMMENT '服务"查某资源某天某时段还有没有位置"+防重复生成',
    KEY `idx_date_status` (`slot_date`, `status`) COMMENT '服务"按天统计/批处理"，如运营数据生成'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='资源时间片+库存表（高频大表，生命周期由 status 表达，不用 deleted）';

-- ============================================================================
-- 三、预约（核心）
-- ============================================================================

DROP TABLE IF EXISTS `t_reservation`;
CREATE TABLE `t_reservation`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `reservation_no` VARCHAR(64)     NOT NULL COMMENT '业务单号（唯一；演进后为"雪花+user_id 基因"，见 docs/04）',
    `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    `resource_id`    BIGINT UNSIGNED NOT NULL COMMENT '资源 ID',
    `resource_type`  VARCHAR(32)     NOT NULL COMMENT '资源类型（冗余，便于分类查询）',
    `slot_id`        BIGINT UNSIGNED          DEFAULT NULL COMMENT '起始时间片 ID（跨片时以 start_time/end_time 推导范围并逐片扣减）；历史片随 14 天滚动窗口归档后为 NULL（见 docs/01 3.5）',
    `slot_date`      DATE            NOT NULL COMMENT '预约日期',
    `start_time`     TIME            NOT NULL COMMENT '开始时间',
    `end_time`       TIME            NOT NULL COMMENT '结束时间',
    `time_bitmap`    VARCHAR(64)     NOT NULL COMMENT '占用位图：96 格（15 分钟/格），24 个 hex 字符',
    `status`         VARCHAR(16)     NOT NULL COMMENT '状态：PENDING/APPROVED/CANCELLED/COMPLETED/NO_SHOW',
    `checkin_time`   DATETIME                 DEFAULT NULL COMMENT '签到时间（未签到为 NULL；开始后未签到将判为爽约）',
    `remark`         VARCHAR(255)             DEFAULT NULL COMMENT '备注（如取消原因）',
    `active_key`     VARCHAR(1) GENERATED ALWAYS AS (IF(`status` IN ('PENDING', 'APPROVED'), 'Y', NULL)) VIRTUAL COMMENT '有效占用标记：仅有效状态为 Y，终结状态为 NULL（唯一索引载体，NULL 不参与唯一性）',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_reservation_no` (`reservation_no`) COMMENT '业务单号唯一（服务"按单号查预约"+幂等）',
    UNIQUE KEY `uk_reserved` (`slot_id`, `user_id`, `active_key`) COMMENT '防重复预订：同一用户对同一时间片最多一条有效预约（取消后可再约，见 active_key）',
    KEY `idx_user_status` (`user_id`, `status`) COMMENT '服务"我的预约列表"',
    KEY `idx_resource_date` (`resource_id`, `slot_date`) COMMENT '服务"某资源某天的预约情况"（管理/统计）',
    KEY `idx_status_slotdate` (`status`, `slot_date`) COMMENT '服务"定时结算任务：扫描待结算预约"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='预约表（四业务共用；生命周期由 status 表达，不用 deleted）';

DROP TABLE IF EXISTS `t_user_time_occupancy`;
CREATE TABLE `t_user_time_occupancy`
(
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    `occupy_date` DATE            NOT NULL COMMENT '日期',
    `bitmap`      VARCHAR(64)     NOT NULL DEFAULT '000000000000000000000000' COMMENT '当日占用位图：96 格（15 分钟/格），24 个 hex 字符',
    `version`     INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（并发更新位图时使用）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`, `occupy_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户时间占用位图表（跨资源冲突检测核心；主键即分片键，永单分片命中）';

-- ============================================================================
-- 四、业务扩展表
-- ============================================================================

DROP TABLE IF EXISTS `t_lecture`;
CREATE TABLE `t_lecture`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(128)    NOT NULL COMMENT '讲座标题',
    `speaker`     VARCHAR(64)     NOT NULL COMMENT '主讲人',
    `location`    VARCHAR(128)    NOT NULL COMMENT '地点',
    `start_time`  DATETIME        NOT NULL COMMENT '开始时刻',
    `end_time`    DATETIME        NOT NULL COMMENT '结束时刻',
    `capacity`    INT             NOT NULL COMMENT '容量（100~500）',
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 已发布 0 已下架',
    `description` VARCHAR(512)             DEFAULT NULL COMMENT '简介',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_start` (`status`, `start_time`) COMMENT '服务"讲座列表：按发布状态筛选、按时间排序"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='讲座表';

DROP TABLE IF EXISTS `t_library_area`;
CREATE TABLE `t_library_area`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(64)     NOT NULL COMMENT '馆区名称（如"图书馆A馆3层"）',
    `campus`      VARCHAR(32)     NOT NULL COMMENT '校区',
    `floor`       INT             NOT NULL COMMENT '楼层',
    `open_time`   TIME            NOT NULL COMMENT '开馆时间',
    `close_time`  TIME            NOT NULL COMMENT '闭馆时间',
    `seat_count`  INT             NOT NULL DEFAULT 0 COMMENT '座位数',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='图书馆馆区表';

DROP TABLE IF EXISTS `t_venue`;
CREATE TABLE `t_venue`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `resource_id`    BIGINT UNSIGNED          DEFAULT NULL COMMENT '对应 t_resource.id（VENUE 类型，预约时使用）',
    `name`           VARCHAR(64)     NOT NULL COMMENT '场馆名称',
    `venue_type`     VARCHAR(32)     NOT NULL COMMENT '类型：BADMINTON/BASKETBALL/TENNIS/GYM/SWIMMING',
    `campus`         VARCHAR(32)     NOT NULL COMMENT '校区',
    `open_time`      TIME            NOT NULL COMMENT '开放时间',
    `close_time`     TIME            NOT NULL COMMENT '关闭时间',
    `price_per_hour` DECIMAL(10, 2)  NOT NULL DEFAULT 0.00 COMMENT '每小时价格（元）',
    `need_approval`  TINYINT         NOT NULL DEFAULT 0 COMMENT '是否需要审批：1 是 0 否',
    `week_limit`     INT             NOT NULL DEFAULT 0 COMMENT '每人每周预约上限（0=不限）',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_type_campus` (`venue_type`, `campus`) COMMENT '服务"按类型/校区筛选场馆列表"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='体育场馆表';

DROP TABLE IF EXISTS `t_course`;
CREATE TABLE `t_course`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `course_code` VARCHAR(32)     NOT NULL COMMENT '课程代码（唯一，如 CS101）',
    `name`        VARCHAR(128)    NOT NULL COMMENT '课程名称',
    `teacher`     VARCHAR(64)     NOT NULL COMMENT '开课教师',
    `credits`     DECIMAL(3, 1)   NOT NULL DEFAULT 0.0 COMMENT '学分',
    `college`     VARCHAR(64)     NOT NULL COMMENT '开课学院',
    `course_type` VARCHAR(16)     NOT NULL COMMENT '类型：必修/选修/通识',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_code` (`course_code`) COMMENT '课程代码唯一',
    KEY `idx_college_type` (`college`, `course_type`) COMMENT '服务"按学院/类型筛选课程列表"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='课程表';

DROP TABLE IF EXISTS `t_course_class`;
CREATE TABLE `t_course_class`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `course_id`     BIGINT UNSIGNED NOT NULL COMMENT '课程 ID',
    `class_name`    VARCHAR(32)     NOT NULL COMMENT '教学班名称（如 01班）',
    `teacher`       VARCHAR(64)     NOT NULL COMMENT '授课教师',
    `semester`      VARCHAR(16)     NOT NULL COMMENT '学期（如 2026-2027-1）',
    `capacity`      INT             NOT NULL COMMENT '容量',
    `selected`      INT             NOT NULL DEFAULT 0 COMMENT '已选人数（容量计数扣减字段）',
    `week_day`      TINYINT         NOT NULL COMMENT '上课星期：1~7',
    `start_section` TINYINT         NOT NULL COMMENT '开始节次：1~10',
    `end_section`   TINYINT         NOT NULL COMMENT '结束节次',
    `start_week`    TINYINT         NOT NULL DEFAULT 1 COMMENT '开始周',
    `end_week`      TINYINT         NOT NULL DEFAULT 16 COMMENT '结束周',
    `location`      VARCHAR(128)             DEFAULT NULL COMMENT '上课地点',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_course` (`course_id`) COMMENT '服务"课程 -> 教学班列表"',
    KEY `idx_schedule` (`semester`, `week_day`, `start_section`) COMMENT '服务"排课冲突检测 / 某天某节次有哪些课"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='教学班表';

-- 注：t_course_waitlist（候补队列）已于 2026-10-08 退役（阶段 8 规则：选课不设候补，
--     人数限额制满员直接拒绝）；对应迁移见 resources/database/migrate-drop-waitlist.sql

DROP TABLE IF EXISTS `t_notification`;
CREATE TABLE `t_notification`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '接收用户 ID',
    `title`       VARCHAR(128)    NOT NULL COMMENT '标题',
    `content`     VARCHAR(512)    NOT NULL COMMENT '内容',
    `biz_type`    VARCHAR(32)     NOT NULL COMMENT '业务类型：RESERVATION/COURSE/MQ_DEMO…',
    `biz_id`      VARCHAR(64)     DEFAULT NULL COMMENT '业务标识（如预约号/消息键；幂等演示用）',
    `is_read`     TINYINT         NOT NULL DEFAULT 0 COMMENT '是否已读：0 未读 1 已读',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `is_read`, `id`) COMMENT '服务"我的通知列表 + 未读数"',
    KEY `idx_biz` (`biz_type`, `biz_id`) COMMENT '服务幂等演示计数'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='站内信表（MQ 异步通知落库；阶段 13）';

DROP TABLE IF EXISTS `t_course_selection`;
CREATE TABLE `t_course_selection`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    `class_id`    BIGINT UNSIGNED NOT NULL COMMENT '教学班 ID',
    `semester`    VARCHAR(16)     NOT NULL COMMENT '学期',
    `status`      VARCHAR(16)     NOT NULL DEFAULT 'SELECTED' COMMENT '状态：SELECTED/DROPPED（"选-退-选"为同一行状态变迁，不新增行）',
    `op_id`       VARCHAR(64)     NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_class_semester` (`user_id`, `class_id`, `semester`) COMMENT '防重复选课：同一学生同一学期同一教学班只有一行',
    KEY `idx_class_semester` (`class_id`, `semester`) COMMENT '服务"教学班选课名单"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='选课记录表（生命周期由 status 表达，不用 deleted）';
