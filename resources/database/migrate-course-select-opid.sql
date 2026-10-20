-- ============================================================================
-- 阶段 21 · 受理制（async-select）：t_course_selection 增加 op_id（受理单号）
--
-- 用途（正确性关键，不是装饰）：
--   MQ 异步落库 + 补偿模型下，"重放的消息"必须与"业务拒绝"区分开——
--   落库时把受理单号写入本列，消费端按 op_id 判定"该受理是否已生效"：
--     行内 op_id == 消息 op_id  → 已生效 → 只清 pending，绝不补偿（否则超卖）
--     否则                     → 未生效 → 走补偿（回补名额 + 修正信号）
--
-- 可重复执行说明：重复执行会报 "Duplicate column name"（MySQL 无 IF NOT EXISTS for ADD COLUMN），
--   报错即代表列已存在，可忽略。
--
-- 执行方式（8 个分片 + 单体基线）：
--   docker exec -i campus-mysql mysql -uroot -p*** --default-character-set=utf8mb4 < migrate-course-select-opid.sql
-- ============================================================================

-- ---------- 8 个物理分片（campus_0 / campus_1 × _0..3）----------
ALTER TABLE campus_0.t_course_selection_0 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_0.t_course_selection_1 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_0.t_course_selection_2 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_0.t_course_selection_3 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_1.t_course_selection_0 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_1.t_course_selection_1 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_1.t_course_selection_2 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
ALTER TABLE campus_1.t_course_selection_3 ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;

-- ---------- 单体基线（阶段 15 对比/回滚用；保持结构同构）----------
ALTER TABLE campus_platform.t_course_selection ADD COLUMN op_id VARCHAR(64) NULL COMMENT '受理单号（阶段21 受理制：重放判定"已生效"用；同步老链路为 NULL）' AFTER status;
