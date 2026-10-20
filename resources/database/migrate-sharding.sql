-- ============================================================================
-- 阶段 14 · 分库分表迁移脚本（一次性；可重复执行——每次从 campus_platform 重建）
--
-- 源库  campus_platform（单体库，保留不动作「单体基线」，阶段 15 对比用）
-- 目标  campus_0 / campus_1（同实例 3308；2 库 x 4 表 = 8 分片）
--
-- 【分片规则】（与 Java 分片算法类严格一致，见 campus-common/.../sharding/）
--   用户维度表（t_user_time_occupancy / t_user_quota / t_course_selection / t_notification）：
--       shard = MOD(user_id, 8)；库 = shard / 4；表 = shard % 4
--   t_reservation：同 user_id 规则；另支持按单号路由（基因法，见下）
--   t_user：按主键 id（与 user_id 同值域，同一用户的数据全部同库同表）
--   t_resource_slot：按 resource_id 同规则
--   索引表 t_user_phone / t_user_mail / t_user_student_no：shard = CRC32(列) % 8
--       （MySQL CRC32() 与 Java java.util.zip.CRC32 结果一致——已实测）
--   广播表（全量复制到两个库）：t_resource, t_lecture, t_venue, t_library_area, t_course, t_course_class
--
-- 【基因法】reservation_no = 'RES' + LPAD(id, 19, '0') + LPAD(user_id % 1000000, 6, '0')
--   尾 6 位即"基因"；Java 分片算法解析尾 6 位取模 -> 1,000,000 = 8 x 125,000，
--   故 gene % 8 == user_id % 8 —— 按单号查与按 user_id 查命中同一分片。
--   迁移时统一重写历史单号（原 'RES+日期+12位序列' 不含基因，无法路由）。
--
-- 【列加密】t_user.phone / email 采用 ShardingSphere ENCRYPT（AES/ECB/PKCS5，
--   密钥 = SHA1(aes-key) 前 16 字节，Base64 输出）。MySQL 等价表达式：
--       TO_BASE64(AES_ENCRYPT(x, SUBSTRING(UNHEX(SHA1('campus-aes-2026-x')), 1, 16)))
--   已实测两端密文逐字节一致。加密后密文更长 -> 物理列扩宽：
--       phone VARCHAR(20) -> VARCHAR(64)；email VARCHAR(128) -> VARCHAR(256)
-- ============================================================================

SET @AES_KEY := SUBSTRING(UNHEX(SHA1('campus-aes-2026-x')), 1, 16);

-- ---------- 0) 重建目标库 ----------
DROP DATABASE IF EXISTS campus_0;
DROP DATABASE IF EXISTS campus_1;
CREATE DATABASE campus_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE campus_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------- 1) 建表（结构复制自单体库；分片表每库 4 张）----------

-- 库 campus_0
CREATE TABLE campus_0.t_resource LIKE campus_platform.t_resource;
CREATE TABLE campus_0.t_lecture LIKE campus_platform.t_lecture;
CREATE TABLE campus_0.t_venue LIKE campus_platform.t_venue;
CREATE TABLE campus_0.t_library_area LIKE campus_platform.t_library_area;
CREATE TABLE campus_0.t_course LIKE campus_platform.t_course;
CREATE TABLE campus_0.t_course_class LIKE campus_platform.t_course_class;
CREATE TABLE campus_0.t_reservation_0 LIKE campus_platform.t_reservation;
CREATE TABLE campus_0.t_reservation_1 LIKE campus_platform.t_reservation;
CREATE TABLE campus_0.t_reservation_2 LIKE campus_platform.t_reservation;
CREATE TABLE campus_0.t_reservation_3 LIKE campus_platform.t_reservation;
CREATE TABLE campus_0.t_resource_slot_0 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_0.t_resource_slot_1 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_0.t_resource_slot_2 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_0.t_resource_slot_3 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_0.t_user_time_occupancy_0 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_0.t_user_time_occupancy_1 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_0.t_user_time_occupancy_2 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_0.t_user_time_occupancy_3 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_0.t_user_quota_0 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_0.t_user_quota_1 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_0.t_user_quota_2 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_0.t_user_quota_3 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_0.t_course_selection_0 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_0.t_course_selection_1 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_0.t_course_selection_2 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_0.t_course_selection_3 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_0.t_notification_0 LIKE campus_platform.t_notification;
CREATE TABLE campus_0.t_notification_1 LIKE campus_platform.t_notification;
CREATE TABLE campus_0.t_notification_2 LIKE campus_platform.t_notification;
CREATE TABLE campus_0.t_notification_3 LIKE campus_platform.t_notification;
CREATE TABLE campus_0.t_user_0 LIKE campus_platform.t_user;
CREATE TABLE campus_0.t_user_1 LIKE campus_platform.t_user;
CREATE TABLE campus_0.t_user_2 LIKE campus_platform.t_user;
CREATE TABLE campus_0.t_user_3 LIKE campus_platform.t_user;
CREATE TABLE campus_0.t_user_phone
(
    phone   VARCHAR(20)     NOT NULL COMMENT '手机号（明文索引；ShardingSphere 按 CRC32(phone)%8 分片）',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='手机号->用户 索引表（多方式登录"读扩散"解法）';
CREATE TABLE campus_0.t_user_mail
(
    email   VARCHAR(128)    NOT NULL COMMENT '邮箱（明文索引）',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='邮箱->用户 索引表';
CREATE TABLE campus_0.t_user_student_no
(
    student_no VARCHAR(32)     NOT NULL COMMENT '学号（明文索引）',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (student_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='学号->用户 索引表';
ALTER TABLE campus_0.t_user_0 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';
ALTER TABLE campus_0.t_user_1 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';
ALTER TABLE campus_0.t_user_2 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';
ALTER TABLE campus_0.t_user_3 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';

-- 库 campus_1
CREATE TABLE campus_1.t_resource LIKE campus_platform.t_resource;
CREATE TABLE campus_1.t_lecture LIKE campus_platform.t_lecture;
CREATE TABLE campus_1.t_venue LIKE campus_platform.t_venue;
CREATE TABLE campus_1.t_library_area LIKE campus_platform.t_library_area;
CREATE TABLE campus_1.t_course LIKE campus_platform.t_course;
CREATE TABLE campus_1.t_course_class LIKE campus_platform.t_course_class;
CREATE TABLE campus_1.t_reservation_0 LIKE campus_platform.t_reservation;
CREATE TABLE campus_1.t_reservation_1 LIKE campus_platform.t_reservation;
CREATE TABLE campus_1.t_reservation_2 LIKE campus_platform.t_reservation;
CREATE TABLE campus_1.t_reservation_3 LIKE campus_platform.t_reservation;
CREATE TABLE campus_1.t_resource_slot_0 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_1.t_resource_slot_1 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_1.t_resource_slot_2 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_1.t_resource_slot_3 LIKE campus_platform.t_resource_slot;
CREATE TABLE campus_1.t_user_time_occupancy_0 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_1.t_user_time_occupancy_1 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_1.t_user_time_occupancy_2 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_1.t_user_time_occupancy_3 LIKE campus_platform.t_user_time_occupancy;
CREATE TABLE campus_1.t_user_quota_0 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_1.t_user_quota_1 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_1.t_user_quota_2 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_1.t_user_quota_3 LIKE campus_platform.t_user_quota;
CREATE TABLE campus_1.t_course_selection_0 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_1.t_course_selection_1 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_1.t_course_selection_2 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_1.t_course_selection_3 LIKE campus_platform.t_course_selection;
CREATE TABLE campus_1.t_notification_0 LIKE campus_platform.t_notification;
CREATE TABLE campus_1.t_notification_1 LIKE campus_platform.t_notification;
CREATE TABLE campus_1.t_notification_2 LIKE campus_platform.t_notification;
CREATE TABLE campus_1.t_notification_3 LIKE campus_platform.t_notification;
CREATE TABLE campus_1.t_user_0 LIKE campus_platform.t_user;
CREATE TABLE campus_1.t_user_1 LIKE campus_platform.t_user;
CREATE TABLE campus_1.t_user_2 LIKE campus_platform.t_user;
CREATE TABLE campus_1.t_user_3 LIKE campus_platform.t_user;
CREATE TABLE campus_1.t_user_phone
(
    phone   VARCHAR(20)     NOT NULL COMMENT '手机号（明文索引；ShardingSphere 按 CRC32(phone)%8 分片）',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='手机号->用户 索引表（多方式登录"读扩散"解法）';
CREATE TABLE campus_1.t_user_mail
(
    email   VARCHAR(128)    NOT NULL COMMENT '邮箱（明文索引）',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='邮箱->用户 索引表';
CREATE TABLE campus_1.t_user_student_no
(
    student_no VARCHAR(32)     NOT NULL COMMENT '学号（明文索引）',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (student_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT ='学号->用户 索引表';
ALTER TABLE campus_1.t_user_0 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';
ALTER TABLE campus_1.t_user_1 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';
ALTER TABLE campus_1.t_user_2 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';
ALTER TABLE campus_1.t_user_3 MODIFY phone VARCHAR(64) NOT NULL COMMENT '手机号（AES 密文；ShardingSphere ENCRYPT）', MODIFY email VARCHAR(256) NOT NULL COMMENT '邮箱（AES 密文；ShardingSphere ENCRYPT）';

-- ---------- 2) 广播表（两库全量副本）----------
INSERT INTO campus_0.t_resource SELECT * FROM campus_platform.t_resource;
INSERT INTO campus_0.t_lecture SELECT * FROM campus_platform.t_lecture;
INSERT INTO campus_0.t_venue SELECT * FROM campus_platform.t_venue;
INSERT INTO campus_0.t_library_area SELECT * FROM campus_platform.t_library_area;
INSERT INTO campus_0.t_course SELECT * FROM campus_platform.t_course;
INSERT INTO campus_0.t_course_class SELECT * FROM campus_platform.t_course_class;
INSERT INTO campus_1.t_resource SELECT * FROM campus_platform.t_resource;
INSERT INTO campus_1.t_lecture SELECT * FROM campus_platform.t_lecture;
INSERT INTO campus_1.t_venue SELECT * FROM campus_platform.t_venue;
INSERT INTO campus_1.t_library_area SELECT * FROM campus_platform.t_library_area;
INSERT INTO campus_1.t_course SELECT * FROM campus_platform.t_course;
INSERT INTO campus_1.t_course_class SELECT * FROM campus_platform.t_course_class;

-- ---------- 3) 分片表数据（迁移 + 单号重写 + 列加密）----------

-- === 分片 0 -> campus_0 的第 0 表 ===
INSERT INTO campus_0.t_reservation_0 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 0;
INSERT INTO campus_0.t_resource_slot_0 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 0;
INSERT INTO campus_0.t_user_time_occupancy_0 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 0;
INSERT INTO campus_0.t_user_quota_0 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 0;
INSERT INTO campus_0.t_course_selection_0 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 0;
INSERT INTO campus_0.t_notification_0 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 0;
INSERT INTO campus_0.t_user_0 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 0;
INSERT INTO campus_0.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 0;
INSERT INTO campus_0.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 0;
INSERT INTO campus_0.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 0;

-- === 分片 1 -> campus_0 的第 1 表 ===
INSERT INTO campus_0.t_reservation_1 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 1;
INSERT INTO campus_0.t_resource_slot_1 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 1;
INSERT INTO campus_0.t_user_time_occupancy_1 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 1;
INSERT INTO campus_0.t_user_quota_1 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 1;
INSERT INTO campus_0.t_course_selection_1 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 1;
INSERT INTO campus_0.t_notification_1 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 1;
INSERT INTO campus_0.t_user_1 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 1;
INSERT INTO campus_0.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 1;
INSERT INTO campus_0.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 1;
INSERT INTO campus_0.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 1;

-- === 分片 2 -> campus_0 的第 2 表 ===
INSERT INTO campus_0.t_reservation_2 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 2;
INSERT INTO campus_0.t_resource_slot_2 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 2;
INSERT INTO campus_0.t_user_time_occupancy_2 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 2;
INSERT INTO campus_0.t_user_quota_2 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 2;
INSERT INTO campus_0.t_course_selection_2 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 2;
INSERT INTO campus_0.t_notification_2 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 2;
INSERT INTO campus_0.t_user_2 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 2;
INSERT INTO campus_0.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 2;
INSERT INTO campus_0.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 2;
INSERT INTO campus_0.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 2;

-- === 分片 3 -> campus_0 的第 3 表 ===
INSERT INTO campus_0.t_reservation_3 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 3;
INSERT INTO campus_0.t_resource_slot_3 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 3;
INSERT INTO campus_0.t_user_time_occupancy_3 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 3;
INSERT INTO campus_0.t_user_quota_3 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 3;
INSERT INTO campus_0.t_course_selection_3 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 3;
INSERT INTO campus_0.t_notification_3 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 3;
INSERT INTO campus_0.t_user_3 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 3;
INSERT INTO campus_0.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 3;
INSERT INTO campus_0.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 3;
INSERT INTO campus_0.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 3;

-- === 分片 4 -> campus_1 的第 0 表 ===
INSERT INTO campus_1.t_reservation_0 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 4;
INSERT INTO campus_1.t_resource_slot_0 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 4;
INSERT INTO campus_1.t_user_time_occupancy_0 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 4;
INSERT INTO campus_1.t_user_quota_0 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 4;
INSERT INTO campus_1.t_course_selection_0 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 4;
INSERT INTO campus_1.t_notification_0 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 4;
INSERT INTO campus_1.t_user_0 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 4;
INSERT INTO campus_1.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 4;
INSERT INTO campus_1.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 4;
INSERT INTO campus_1.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 4;

-- === 分片 5 -> campus_1 的第 1 表 ===
INSERT INTO campus_1.t_reservation_1 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 5;
INSERT INTO campus_1.t_resource_slot_1 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 5;
INSERT INTO campus_1.t_user_time_occupancy_1 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 5;
INSERT INTO campus_1.t_user_quota_1 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 5;
INSERT INTO campus_1.t_course_selection_1 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 5;
INSERT INTO campus_1.t_notification_1 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 5;
INSERT INTO campus_1.t_user_1 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 5;
INSERT INTO campus_1.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 5;
INSERT INTO campus_1.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 5;
INSERT INTO campus_1.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 5;

-- === 分片 6 -> campus_1 的第 2 表 ===
INSERT INTO campus_1.t_reservation_2 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 6;
INSERT INTO campus_1.t_resource_slot_2 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 6;
INSERT INTO campus_1.t_user_time_occupancy_2 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 6;
INSERT INTO campus_1.t_user_quota_2 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 6;
INSERT INTO campus_1.t_course_selection_2 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 6;
INSERT INTO campus_1.t_notification_2 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 6;
INSERT INTO campus_1.t_user_2 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 6;
INSERT INTO campus_1.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 6;
INSERT INTO campus_1.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 6;
INSERT INTO campus_1.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 6;

-- === 分片 7 -> campus_1 的第 3 表 ===
INSERT INTO campus_1.t_reservation_3 (id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, checkin_time, remark, create_time, update_time)
SELECT id, CONCAT('RES', LPAD(id, 19, '0'), LPAD(MOD(user_id, 1000000), 6, '0')),
       user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time,
       time_bitmap, status, checkin_time, remark, create_time, update_time
FROM campus_platform.t_reservation WHERE MOD(user_id, 8) = 7;
INSERT INTO campus_1.t_resource_slot_3 (id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time)
SELECT id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time FROM campus_platform.t_resource_slot WHERE MOD(resource_id, 8) = 7;
INSERT INTO campus_1.t_user_time_occupancy_3 (user_id, occupy_date, bitmap, version, create_time, update_time)
SELECT user_id, occupy_date, bitmap, version, create_time, update_time FROM campus_platform.t_user_time_occupancy WHERE MOD(user_id, 8) = 7;
INSERT INTO campus_1.t_user_quota_3 (id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted)
SELECT id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted FROM campus_platform.t_user_quota WHERE MOD(user_id, 8) = 7;
INSERT INTO campus_1.t_course_selection_3 (id, user_id, class_id, semester, status, create_time, update_time)
SELECT id, user_id, class_id, semester, status, create_time, update_time FROM campus_platform.t_course_selection WHERE MOD(user_id, 8) = 7;
INSERT INTO campus_1.t_notification_3 (id, user_id, title, content, biz_type, biz_id, is_read, create_time)
SELECT id, user_id, title, content, biz_type, biz_id, is_read, create_time FROM campus_platform.t_notification WHERE MOD(user_id, 8) = 7;
INSERT INTO campus_1.t_user_3 (id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted)
SELECT id, student_no, password, real_name,
       TO_BASE64(AES_ENCRYPT(phone, @AES_KEY)), TO_BASE64(AES_ENCRYPT(email, @AES_KEY)),
       college, major, grade, role, status, create_time, update_time, deleted
FROM campus_platform.t_user WHERE MOD(id, 8) = 7;
INSERT INTO campus_1.t_user_phone
SELECT phone, id FROM campus_platform.t_user WHERE CRC32(phone) % 8 = 7;
INSERT INTO campus_1.t_user_mail
SELECT email, id FROM campus_platform.t_user WHERE CRC32(email) % 8 = 7;
INSERT INTO campus_1.t_user_student_no
SELECT student_no, id FROM campus_platform.t_user WHERE CRC32(student_no) % 8 = 7;

-- ---------- 4) 迁移校验（源计数 = 各分片之和）----------
SELECT 't_reservation' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_reservation) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_reservation_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_reservation_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_reservation_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_reservation_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_reservation_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_reservation_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_reservation_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_reservation_3) x) AS dst;
SELECT 't_resource_slot' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_resource_slot) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_resource_slot_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_resource_slot_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_resource_slot_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_resource_slot_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_resource_slot_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_resource_slot_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_resource_slot_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_resource_slot_3) x) AS dst;
SELECT 't_user_time_occupancy' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_user_time_occupancy) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_user_time_occupancy_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_time_occupancy_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_time_occupancy_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_time_occupancy_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_time_occupancy_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_time_occupancy_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_time_occupancy_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_time_occupancy_3) x) AS dst;
SELECT 't_user_quota' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_user_quota) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_user_quota_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_quota_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_quota_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_quota_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_quota_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_quota_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_quota_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_quota_3) x) AS dst;
SELECT 't_course_selection' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_course_selection) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_course_selection_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_course_selection_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_course_selection_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_course_selection_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_course_selection_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_course_selection_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_course_selection_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_course_selection_3) x) AS dst;
SELECT 't_notification' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_notification) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_notification_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_notification_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_notification_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_notification_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_notification_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_notification_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_notification_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_notification_3) x) AS dst;
SELECT 't_user' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_user) AS src,
       (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM campus_0.t_user_0 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_1 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_2 UNION ALL SELECT COUNT(*) c FROM campus_0.t_user_3 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_0 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_1 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_2 UNION ALL SELECT COUNT(*) c FROM campus_1.t_user_3) x) AS dst;
SELECT 't_user_phone' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_user) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_user_phone) + (SELECT COUNT(*) FROM campus_1.t_user_phone)) AS dst;
SELECT 't_user_mail' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_user) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_user_mail) + (SELECT COUNT(*) FROM campus_1.t_user_mail)) AS dst;
SELECT 't_user_student_no' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_user) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_user_student_no) + (SELECT COUNT(*) FROM campus_1.t_user_student_no)) AS dst;
SELECT 'broadcast.t_resource' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_resource) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_resource) + (SELECT COUNT(*) FROM campus_1.t_resource)) AS dst_2x;
SELECT 'broadcast.t_lecture' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_lecture) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_lecture) + (SELECT COUNT(*) FROM campus_1.t_lecture)) AS dst_2x;
SELECT 'broadcast.t_venue' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_venue) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_venue) + (SELECT COUNT(*) FROM campus_1.t_venue)) AS dst_2x;
SELECT 'broadcast.t_library_area' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_library_area) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_library_area) + (SELECT COUNT(*) FROM campus_1.t_library_area)) AS dst_2x;
SELECT 'broadcast.t_course' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_course) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_course) + (SELECT COUNT(*) FROM campus_1.t_course)) AS dst_2x;
SELECT 'broadcast.t_course_class' AS tbl, (SELECT COUNT(*) FROM campus_platform.t_course_class) AS src,
       ((SELECT COUNT(*) FROM campus_0.t_course_class) + (SELECT COUNT(*) FROM campus_1.t_course_class)) AS dst_2x;
