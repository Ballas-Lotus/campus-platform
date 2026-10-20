-- ============================================================================
-- 迁移：站内信表（阶段 13 · MQ 化：预约/选课成功 → 异步通知）
-- ============================================================================
-- 由 campus-service 的 MQ 消费者写入（campus_notification_topic），
-- 由 user-service 读取（个人中心/顶栏铃铛）。单库期两服务共享，分库时随 user 域走。
--
-- 执行方式：
--   docker exec -i campus-mysql mysql -uroot -pcampus123456 \
--     --default-character-set=utf8mb4 campus_platform < migrate-notification.sql
-- ============================================================================

USE campus_platform;

CREATE TABLE IF NOT EXISTS `t_notification`
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
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='站内信表（MQ 异步通知落库）';
