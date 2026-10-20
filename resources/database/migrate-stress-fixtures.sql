-- ============================================================================
-- 迁移：并发压测专用夹具（阶段 9 · 2026-10-08）
-- ============================================================================
-- 目的：为"防超卖 / 防重复 / 位图冲突 / 选课限额"四个压测场景提供**不污染业务数据**
--       的专用夹具（每轮压测结束由后端自动清理本轮写入）：
--   1. t_resource 90001  —— 「并发压测专用场地」（VENUE 语义，容量 10，仅 API 可见，
--                            不在 t_venue 中故不会出现在场馆页列表）
--   2. t_user   stress01 —— 「压测机器人」账号（密码哈希复制自测试账号 test，即 123456）
--   3. t_course 9999 + t_course_class 9999 —— 「并发压测演练课 · 压测班」
--                            （容量 10；周日 9-10 节，与全部种子课表天然不冲突）
--
-- 执行方式（可重复执行，全部幂等）：
--   docker exec -i campus-mysql mysql -uroot -pcampus123456 \
--     --default-character-set=utf8mb4 campus_platform < migrate-stress-fixtures.sql
-- ============================================================================

USE campus_platform;

-- 1. 压测专用场地（资源）
INSERT INTO t_resource (id, resource_type, name, location, campus, capacity, exclusive, need_approval, status)
SELECT 90001, 'VENUE', '并发压测专用场地', '体育中心-压测区', '主校区', 10, 0, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM t_resource WHERE id = 90001) x);

-- 2. 压测机器人账号（密码哈希复制自 test 账号）
INSERT INTO t_user (student_no, password, real_name, phone, email, college, major, grade, role, status)
SELECT 'stress01', password, '压测机器人', '19900000001', 'stress01@campus.edu.cn',
       '计算机学院', '软件工程', '2021', 'STUDENT', 1
FROM t_user
WHERE student_no = 'test'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM t_user WHERE student_no = 'stress01') y);

-- 3. 压测演练课 + 压测班（容量 10）
INSERT INTO t_course (id, course_code, name, teacher, credits, college, course_type)
SELECT 9999, 'STRESS01', '并发压测演练课', '系统', 1.0, '计算机学院', '通识'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM t_course WHERE id = 9999) z);

INSERT INTO t_course_class (id, course_id, class_name, teacher, semester, capacity, selected,
                            week_day, start_section, end_section, start_week, end_week, location)
SELECT 9999, 9999, '压测班', '系统', '2026-2027-1', 10, 0, 7, 9, 10, 1, 16, '在线'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM t_course_class WHERE id = 9999) w);

-- 4. 千人并发演示班（阶段 21.1 演示用：容量 1500；周日 3-4 节——与种子课表零冲突）
--    注意：t_course_class 是广播表；若库已按阶段 14 分片（campus_0/campus_1），
--    需分别向两个库插入（本脚本按 campus_platform 单体库编写，migrate-sharding 会复制过去）
INSERT INTO t_course_class (id, course_id, class_name, teacher, semester, capacity, selected,
                            week_day, start_section, end_section, start_week, end_week, location)
SELECT 9998, 9999, '千人演示班', '系统', '2026-2027-1', 1500, 0, 7, 3, 4, 1, 16, '在线'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM t_course_class WHERE id = 9998) x);
