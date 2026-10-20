# -*- coding: utf-8 -*-
"""
种子数据批量导入器（阶段 2）
============================
1. docker cp output/*.csv → campus-mysql:/tmp/seed/
2. TRUNCATE 12 张表（可重复执行）
3. 逐表 LOAD DATA LOCAL INFILE（记录每表耗时与行数）
4. ANALYZE TABLE 更新统计信息
5. 库内 SQL 校验（对"已入库"的数据做第二层验证）：
   行数 / 时间冲突 / 库存总账 / 超卖 / 选课计数 / 抽样库存明细

用法：python import_data.py      （退出码 0 = 全部通过）
"""

import os
import subprocess
import sys
import time

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")
CONTAINER = "campus-mysql"
DB = "campus_platform"
REMOTE_DIR = "/tmp/seed"

MYSQL = ["docker", "exec", "-i", CONTAINER, "mysql", "-uroot", "-pcampus123456",
         "--default-character-set=utf8mb4", "--local-infile=1", DB]

# (表名, LOAD DATA 列清单——顺序与 CSV 完全一致；生成列 active_key 不参与)
TABLES = [
    ("t_user", "id, student_no, password, real_name, phone, email, college, major, grade, role, status, create_time, update_time, deleted"),
    ("t_user_quota", "id, user_id, quota_type, used_times, period_start, period_end, create_time, update_time, deleted"),
    ("t_resource", "id, resource_type, name, parent_id, location, campus, capacity, exclusive, need_approval, status, create_time, update_time, deleted"),
    ("t_resource_slot", "id, resource_id, slot_date, start_time, end_time, capacity, booked, version, status, create_time, update_time"),
    ("t_reservation", "id, reservation_no, user_id, resource_id, resource_type, slot_id, slot_date, start_time, end_time, time_bitmap, status, remark, create_time, update_time"),
    ("t_user_time_occupancy", "user_id, occupy_date, bitmap, version, create_time, update_time"),
    ("t_lecture", "id, title, speaker, location, start_time, end_time, capacity, status, description, create_time, update_time, deleted"),
    ("t_library_area", "id, name, campus, floor, open_time, close_time, seat_count, create_time, update_time, deleted"),
    ("t_venue", "id, resource_id, name, venue_type, campus, open_time, close_time, price_per_hour, need_approval, week_limit, create_time, update_time, deleted"),
    ("t_course", "id, course_code, name, teacher, credits, college, course_type, create_time, update_time, deleted"),
    ("t_course_class", "id, course_id, class_name, teacher, semester, capacity, selected, week_day, start_section, end_section, start_week, end_week, location, create_time, update_time, deleted"),
    ("t_course_selection", "id, user_id, class_id, semester, status, create_time, update_time"),
]

failures = []


def sh(args, timeout=1800, check=True):
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=timeout)
    if check and r.returncode != 0:
        print("命令失败:", " ".join(args[:6]), "\nSTDERR:", (r.stderr or "")[:2000])
        sys.exit(2)
    return r


def sql(stmt, timeout=1800):
    r = sh(MYSQL + ["-N", "-e", stmt], timeout=timeout)
    return (r.stdout or "").strip()


def sql_rows(stmt, timeout=1800):
    r = sh(MYSQL + ["-e", stmt], timeout=timeout)
    return (r.stdout or "").strip()


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  —— {detail}" if detail else ""), flush=True)
    if not cond:
        failures.append(name)


def main():
    t0 = time.time()
    print("========== 种子数据导入 ==========", flush=True)

    # 1. 复制 CSV 进容器
    print("· docker cp CSV → 容器 ...", flush=True)
    sh(["docker", "cp", OUT_DIR + "/.", f"{CONTAINER}:{REMOTE_DIR}"], timeout=600)

    # 2. TRUNCATE（可重复执行）
    print("· TRUNCATE 13 张表 ...", flush=True)
    sql("SET FOREIGN_KEY_CHECKS=0; " + " ".join(f"TRUNCATE TABLE {t};" for t, _ in TABLES))

    # 3. LOAD DATA 逐表导入并计时
    print("· LOAD DATA LOCAL INFILE 导入：", flush=True)
    import time as _t
    total_rows = 0
    for (table, cols) in TABLES:
        stmt = (f"LOAD DATA LOCAL INFILE '{REMOTE_DIR}/{table}.csv' INTO TABLE {table} "
                f"CHARACTER SET utf8mb4 FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n' ({cols})")
        ts = _t.time()
        sql(stmt)
        dt = _t.time() - ts
        cnt = int(sql(f"SELECT COUNT(*) FROM {table}") or 0)
        total_rows += cnt
        print(f"    {table:26s} {cnt:>10,d} 行   {dt:6.1f}s", flush=True)

    # 4. ANALYZE
    print("· ANALYZE TABLE（更新统计信息）...", flush=True)
    sql(" ".join(f"ANALYZE TABLE {t};" for t, _ in TABLES))

    # 5. 库内校验
    print("\n----- 库内 SQL 校验 -----", flush=True)

    # 5.1 时间冲突（窗口函数：同用户同日按 start 排序，与前序最大 end 比较）
    conflict = sql("""
        SELECT COUNT(*) FROM (
          SELECT start_time,
                 MAX(end_time) OVER (PARTITION BY user_id, slot_date
                                     ORDER BY start_time ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) pm
          FROM t_reservation
          WHERE status IN ('PENDING','APPROVED','COMPLETED','NO_SHOW')
        ) t WHERE pm IS NOT NULL AND start_time < pm""", timeout=1800)
    check("时间冲突 = 0（窗口函数全量扫描）", conflict == "0", f"冲突 {conflict}")

    # 5.2 库存总账：Σbooked（片侧） == Σ预约覆盖片数（有效预约侧）
    #     口径：图书馆/场馆每小时一片；讲座"整场一格"（2026-10-08 报名制，不论时长恒占 1 片）
    #     注意：两条 SQL 分开查询会因"后端运行中的定时结算"产生时刻竞态，必须单语句原子对比
    pair = sql("""
        SELECT IFNULL(SUM(booked), 0),
               (SELECT IFNULL(SUM(CASE WHEN resource_type = 'LECTURE' THEN 1
                                       ELSE TIMESTAMPDIFF(HOUR, start_time, end_time) END), 0)
                FROM t_reservation
                WHERE status IN ('PENDING','APPROVED') AND slot_id IS NOT NULL)
        FROM t_resource_slot""")
    parts = pair.split("\t") if pair else ["", ""]
    check("库存总账 Σbooked = Σ预约占用片数（原子快照）",
          len(parts) == 2 and parts[0] == parts[1],
          f"booked 合计 {parts[0] if parts else '?'} vs 预约占用 {parts[1] if len(parts) > 1 else '?'}")

    # 5.3 超卖 / 负库存
    over = sql("SELECT COUNT(*) FROM t_resource_slot WHERE booked > capacity OR booked < 0")
    check("无超卖（booked ≤ capacity）", over == "0", f"异常 {over}")

    # 5.4 未来有效预约 slot_id 非空
    null_slot = sql("""SELECT COUNT(*) FROM t_reservation
        WHERE status IN ('PENDING','APPROVED') AND slot_date >= CURDATE() AND slot_id IS NULL""")
    check("未来有效预约 slot_id 非空", null_slot == "0", f"缺失 {null_slot}")

    # 5.5 抽样库存明细（id 1~20000 内 20 个有占用的片逐片核对）
    sample_bad = sql("""
        SELECT COUNT(*) FROM (SELECT * FROM t_resource_slot
                              WHERE id <= 20000 AND booked > 0 LIMIT 20) s
        WHERE s.booked <> (SELECT COUNT(*) FROM t_reservation r
                           WHERE r.resource_id = s.resource_id AND r.slot_date = s.slot_date
                             AND r.status IN ('PENDING','APPROVED')
                             AND r.start_time <= s.start_time AND r.end_time > s.start_time)""")
    check("抽样 20 片库存明细一致", sample_bad == "0", f"不一致 {sample_bad}")

    # 5.6 选课计数一致
    sel_bad = sql("""
        SELECT COUNT(*) FROM t_course_class c
        LEFT JOIN (SELECT class_id, COUNT(*) n FROM t_course_selection
                   WHERE status='SELECTED' GROUP BY class_id) s ON s.class_id = c.id
        WHERE c.selected <> IFNULL(s.n, 0)""")
    check("教学班 selected 计数一致", sel_bad == "0", f"异常 {sel_bad}")

    # 5.7 选课时间冲突
    sel_conf = sql("""
        SELECT COUNT(*) FROM (
          SELECT s1.id FROM t_course_selection s1
          JOIN t_course_class c1 ON c1.id = s1.class_id
          JOIN t_course_selection s2 ON s2.user_id = s1.user_id AND s2.id > s1.id
          JOIN t_course_class c2 ON c2.id = s2.class_id
          WHERE c1.week_day = c2.week_day
            AND c1.start_section <= c2.end_section AND c2.start_section <= c1.end_section) t""")
    check("学生选课时间冲突 = 0", sel_conf == "0", f"冲突 {sel_conf}")

    # 5.8 演示账号数据
    demo = sql_rows("""SELECT u.real_name, r.slot_date, r.start_time, r.end_time, r.status, r.resource_type
        FROM t_reservation r JOIN t_user u ON u.id = r.user_id
        WHERE r.user_id IN (1,2) AND r.slot_date >= CURDATE() ORDER BY r.slot_date, r.start_time""")
    print("\n  演示账号未来预约（林晚秋/陈屿）：")
    print("  " + demo.replace("\n", "\n  ") if demo else "  （无）", flush=True)

    # 6. 汇总
    print(f"\n导入完成：13 表合计 {total_rows:,} 行，耗时 {time.time()-t0:.0f}s", flush=True)
    if failures:
        print("失败项：", "；".join(failures), flush=True)
        sys.exit(1)
    print("*** 导入与库内校验全部通过 ***", flush=True)


if __name__ == "__main__":
    main()
