-- ============================================================================
-- 迁移：讲座"选座制" → "报名制"（2026-10-08 业务调整）
-- ============================================================================
-- 背景：讲座不再提供座位图（到场自选座位），改为"人数限额报名"。
-- 数据模型：每场讲座从 N 条 LECTURE_SEAT 席位资源（capacity=1）
--           → 1 条 LECTURE 名额资源（capacity=场容量，容量计数，同场馆语义）。
--
-- 迁移策略（保留全部历史报名记录，只改指向）：
--   ① 每场讲座取"最小席位 id"作为新的名额资源（base）
--   ② 该行改写为 LECTURE 类型（名称/容量/独占性）
--   ③ 其时间片改写：capacity=场容量，booked=该场有效报名数
--   ④ 该场全部预约记录改指：resource_id→base、slot_id→base 的片、resource_type→LECTURE
--   ⑤ 删除其余席位资源与时间片
--
-- 执行：docker exec -i campus-mysql mysql -uroot -p*** --default-character-set=utf8mb4 campus_platform < migrate-lecture-reporting.sql
-- ============================================================================

USE campus_platform;

START TRANSACTION;

-- 迁移前基线（用于对照）：席位资源 6000、席位片 6000、讲座预约 ~4540、资源总数 9320、片总数 597920
SELECT '=== 迁移前 ===' AS step;
SELECT (SELECT COUNT(*) FROM t_resource WHERE resource_type='LECTURE_SEAT') AS 席位资源,
       (SELECT COUNT(*) FROM t_resource WHERE resource_type='LECTURE') AS 讲座资源,
       (SELECT COUNT(*) FROM t_reservation WHERE resource_type='LECTURE_SEAT') AS 席位预约;

-- ① + ② 每场最小席位 id → 改写为讲座名额资源
UPDATE t_resource r
JOIN (SELECT parent_id, MIN(id) AS base_id
      FROM t_resource WHERE resource_type = 'LECTURE_SEAT' GROUP BY parent_id) b
  ON r.id = b.base_id
JOIN t_lecture l ON l.id = r.parent_id
SET r.resource_type = 'LECTURE',
    r.name = CONCAT('讲座名额·', l.title),
    r.capacity = l.capacity,
    r.exclusive = 0,
    r.need_approval = 0;

-- ③ 名额片：capacity=场容量，booked=该场有效报名数（此刻预约记录还是旧指向，能数全）
UPDATE t_resource_slot s
JOIN t_resource r ON r.id = s.resource_id AND r.resource_type = 'LECTURE'
JOIN t_lecture l ON l.id = r.parent_id
SET s.capacity = l.capacity,
    s.booked = (SELECT COUNT(*) FROM t_reservation v
                JOIN t_resource r2 ON r2.id = v.resource_id
                WHERE r2.parent_id = l.id AND v.resource_type = 'LECTURE_SEAT'
                  AND v.status = 'APPROVED'),
    s.version = s.booked,
    s.status = 1;

-- ④ 历史报名记录改指到新名额资源（slot_id → 该场名额片）
UPDATE t_reservation v
JOIN t_resource r2 ON r2.id = v.resource_id
JOIN (SELECT parent_id, MIN(id) AS base_id
      FROM t_resource WHERE parent_id IS NOT NULL AND resource_type = 'LECTURE'
      GROUP BY parent_id) b
  ON b.parent_id = r2.parent_id
SET v.resource_id = b.base_id,
    v.resource_type = 'LECTURE',
    v.slot_id = (SELECT s.id FROM t_resource_slot s WHERE s.resource_id = b.base_id LIMIT 1)
WHERE v.resource_type = 'LECTURE_SEAT';

-- ⑤ 删除其余席位资源与时间片
DELETE s FROM t_resource_slot s
JOIN t_resource r ON r.id = s.resource_id
WHERE r.resource_type = 'LECTURE_SEAT';

DELETE FROM t_resource WHERE resource_type = 'LECTURE_SEAT';

-- 迁移后校验（预期：席位资源 0、讲座资源 20、席位预约 0、LECTURE 预约 ≈4540、资源 3340、片 591940）
SELECT '=== 迁移后 ===' AS step;
SELECT (SELECT COUNT(*) FROM t_resource WHERE resource_type='LECTURE_SEAT') AS 席位资源,
       (SELECT COUNT(*) FROM t_resource WHERE resource_type='LECTURE') AS 讲座资源,
       (SELECT COUNT(*) FROM t_reservation WHERE resource_type='LECTURE_SEAT') AS 席位预约,
       (SELECT COUNT(*) FROM t_reservation WHERE resource_type='LECTURE') AS 讲座预约,
       (SELECT COUNT(*) FROM t_resource) AS 资源总数,
       (SELECT COUNT(*) FROM t_resource_slot) AS 片总数;

-- 逐场自洽抽查：每场 1 条名额资源 + 1 片，片 booked = 有效报名数
SELECT '=== 逐场自洽（前5场）===' AS step;
SELECT l.id AS 讲座, r.id AS 资源, r.capacity AS 容量, s.booked AS 已报名,
       (SELECT COUNT(*) FROM t_reservation v WHERE v.resource_id = r.id AND v.status='APPROVED') AS 报名数
FROM t_lecture l
JOIN t_resource r ON r.parent_id = l.id AND r.resource_type = 'LECTURE'
JOIN t_resource_slot s ON s.resource_id = r.id
ORDER BY l.id LIMIT 5;

COMMIT;

SELECT '迁移完成' AS result;
