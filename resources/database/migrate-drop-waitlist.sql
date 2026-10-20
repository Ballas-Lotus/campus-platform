-- ============================================================================
-- 迁移：退役课程候补队列（2026-10-08 规则调整 · 阶段 8）
-- ============================================================================
-- 规则：选课不设候补队列——人数限额制，满员直接拒绝（先到先得）。
-- 影响：
--   1. 删除 t_course_waitlist 表（322 行候补数据一并清除）
--   2. schema.sql 中已同步移除该表 DDL（全新环境不会建此表）
--   3. t_course_selection / t_course_class 数据不受影响（7017 条选课保留）
--
-- 执行方式：
--   docker exec -i campus-mysql mysql -uroot -pcampus123456 \
--     --default-character-set=utf8mb4 campus_platform < migrate-drop-waitlist.sql
-- ============================================================================

USE campus_platform;

DROP TABLE IF EXISTS `t_course_waitlist`;
