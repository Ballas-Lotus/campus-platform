# -*- coding: utf-8 -*-
"""
种子数据独立校验器（阶段 2 验收证据）
=====================================
从 output/*.csv 全量重读、独立重算，校验生成器声称的每一条不变量：

  1. 行数：12 张表实际行数
  2. 时间冲突 = 0：同用户同日"未取消"预约两两不重叠（区间 sweep + 位图 AND 双检）
  3. 位图正确性：每条预约的 time_bitmap 与其 start/end 推导值一致
  4. 位图表一致：t_user_time_occupancy 每行 = 该(用户,日期)未取消预约位图的 OR
  5. 库存自洽 = 0：t_resource_slot.booked = 覆盖该片的有效预约数（并验 booked ≤ capacity、
     version ≥ booked、片唯一）
  6. slot_id 关联：未来未取消预约的 slot_id 指向正确的片
  7. 选课：教学班 selected 计数一致、学生课表无冲突、无重复选课（无候补——2026-10-08 规则）
  8. 唯一性：学号/手机/邮箱 / reservation_no / 片三元组

用法：python verify.py   （退出码 0 = 全部通过）
"""

import os
import sys
import time

import config as C

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")
NULL = "\\N"
NONCANCEL = frozenset(("PENDING", "APPROVED", "COMPLETED", "NO_SHOW"))
TODAY_STR = C.TODAY.strftime("%Y-%m-%d")

failures = []
checks = []


def check(name, bad_count, detail=""):
    ok = bad_count == 0
    checks.append((name, ok, detail or f"异常 {bad_count} 条"))
    if not ok:
        failures.append(name)
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}  —— {detail or f'异常 {bad_count} 条'}", flush=True)
    return ok


def rows(name):
    """流式读取 CSV 行（split 逗号；数据保证不含逗号/引号/换行）"""
    path = os.path.join(OUT_DIR, name + ".csv")
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            yield line.rstrip("\n").split(",")


def ymd_int(ds):
    return int(ds.replace("-", ""))


def make_bitmap(start_h, end_h):
    return ((1 << (end_h * 4)) - 1) ^ ((1 << (start_h * 4)) - 1)


def verify_reservations():
    """单遍读 t_reservation：冲突、位图、未来记录收集、唯一性"""
    print("· 校验 t_reservation（时间冲突 / 位图 / 唯一性 / 收集未来记录）...", flush=True)
    acc = {}              # key(uid,ymd) -> [bits_or, max_end_hour]（仅未取消）
    bitmap_bad = 0
    conflict_range = 0
    conflict_bits = 0
    nos = set()
    dup_no = 0
    future_rows = []      # (uid, rid, ymd, d_str, h0, h1, slot_id, status, rtype)
    n = 0
    for f in rows("t_reservation"):
        n += 1
        rno, uid, rid, rtype, slot_id, dstr, t1, t2, bmp, status = \
            f[1], int(f[2]), int(f[3]), f[4], f[5], f[6], f[7], f[8], f[9], f[10]
        if rno in nos:
            dup_no += 1
        else:
            nos.add(rno)
        h0, h1 = int(t1[:2]), int(t2[:2])
        bits = int(bmp, 16)
        if bits != make_bitmap(h0, h1):
            bitmap_bad += 1
        if status in NONCANCEL:
            key = uid * 100000000 + ymd_int(dstr)
            v = acc.get(key)
            if v is None:
                acc[key] = [bits, [(h0, h1)]]
            else:
                if v[0] & bits:
                    conflict_bits += 1
                # 精确区间相交检测（半开区间 [start, end)：h0 < b 且 a < h1）
                for (a, b) in v[1]:
                    if h0 < b and a < h1:
                        conflict_range += 1
                        break
                v[1].append((h0, h1))
                v[0] |= bits
        if dstr >= TODAY_STR:
            future_rows.append((uid, rid, ymd_int(dstr), dstr, h0, h1,
                                None if slot_id == NULL else int(slot_id), status, rtype))
    check("reservation_no 唯一", dup_no)
    check("time_bitmap 编码正确", bitmap_bad)
    check("时间冲突（位图 AND）= 0", conflict_bits)
    check("时间冲突（区间扫描）= 0", conflict_range)
    return n, acc, future_rows


def verify_occupancy(acc):
    """t_user_time_occupancy 每行与重算位图一致（行集合完全一致）"""
    print("· 校验 t_user_time_occupancy（位图 = 未取消预约 OR）...", flush=True)
    seen = set()
    value_bad = 0
    n = 0
    for f in rows("t_user_time_occupancy"):
        n += 1
        uid, dstr, bmp = int(f[0]), f[1], f[2]
        key = uid * 100000000 + ymd_int(dstr)
        seen.add(key)
        v = acc.get(key)
        if v is None or v[0] != int(bmp, 16):
            value_bad += 1
    missing = len(set(acc.keys()) - seen)      # 有预约却没有位图行
    check("位图表值与重算一致", value_bad)
    check("位图表行集完整（无遗漏）", missing)
    return n


def verify_slots(future_rows):
    """t_resource_slot：booked = 覆盖片数；booked ≤ capacity；version ≥ booked；片唯一；slot_id 关联"""
    print("· 校验 t_resource_slot（库存自洽 / 片唯一 / slot_id 关联）...", flush=True)
    counts = {}
    for (uid, rid, ymd, dstr, h0, h1, slot_id, status, rtype) in future_rows:
        if status in ("PENDING", "APPROVED"):
            kbase = rid * 100000000000 + ymd * 100
            # 讲座整场一格（只占起始片）；图书馆/场馆按小时逐片
            span = 1 if rtype == "LECTURE" else h1 - h0
            for h in range(h0, h0 + span):
                k = kbase + h
                counts[k] = counts.get(k, 0) + 1
    seen = set()
    booked_bad = 0
    over_cap = 0
    ver_bad = 0
    dup = 0
    n = 0
    slot_key_by_id = {}
    for f in rows("t_resource_slot"):
        n += 1
        sid, rid = int(f[0]), int(f[1])
        dstr, t1 = f[2], f[3]
        cap, booked, version = int(f[5]), int(f[6]), int(f[7])
        h = int(t1[:2])
        k = rid * 100000000000 + ymd_int(dstr) * 100 + h
        if k in seen:
            dup += 1
        else:
            seen.add(k)
        if counts.get(k, 0) != booked:
            booked_bad += 1
        if booked > cap or booked < 0:
            over_cap += 1
        if version < booked:
            ver_bad += 1
        slot_key_by_id[sid] = k
    check("片三元组唯一 (resource_id,date,start)", dup)
    check("库存自洽 booked = 有效预约覆盖数", booked_bad)
    check("无超卖 (0 ≤ booked ≤ capacity)", over_cap)
    check("version ≥ booked", ver_bad)

    link_bad = 0
    for (uid, rid, ymd, dstr, h0, h1, slot_id, status, rtype) in future_rows:
        if status in ("PENDING", "APPROVED"):
            expect = rid * 100000000000 + ymd * 100 + h0
            if slot_id is None or slot_key_by_id.get(slot_id) != expect:
                link_bad += 1
    check("未来有效预约 slot_id 指向正确", link_bad)
    return n


def verify_courses():
    """选课：selected 计数、学生课表冲突、重复选课（无候补——2026-10-08 规则）"""
    print("· 校验课程选课 ...", flush=True)
    cls = {}
    for f in rows("t_course_class"):
        cls[int(f[0])] = (int(f[6]), int(f[7]), int(f[8]), int(f[9]))  # selected, day, s1, s2
    sel_count = {}
    stu = {}
    dup_sel = 0
    taken = set()
    n_sel = 0
    for f in rows("t_course_selection"):
        n_sel += 1
        uid, cid = int(f[1]), int(f[2])
        if (uid, cid) in taken:
            dup_sel += 1
        taken.add((uid, cid))
        sel_count[cid] = sel_count.get(cid, 0) + 1
        stu.setdefault(uid, []).append(cid)
    cnt_bad = 0
    for cid, cnt in sel_count.items():
        if cls[cid][0] != cnt:
            cnt_bad += 1
    check("教学班 selected 计数一致", cnt_bad)
    check("无重复选课 (user,class)", dup_sel)

    sched_bad = 0
    for uid, cids in stu.items():
        intervals = []
        for cid in cids:
            _, day, s1, s2 = cls[cid]
            intervals.append((day, s1, s2))
        intervals.sort(key=lambda x: (x[0], x[1]))
        last = None
        for (day, s1, s2) in intervals:
            if last and last[0] == day and s1 <= last[2]:
                sched_bad += 1
                break
            last = (day, s1, s2)
    check("学生课表无时间冲突", sched_bad)
    return n_sel


def verify_uniques():
    print("· 校验用户与资源的唯一性 ...", flush=True)
    sno, phone, email = set(), set(), set()
    dup = 0
    n_u = 0
    for f in rows("t_user"):
        n_u += 1
        if f[1] in sno or f[4] in phone or f[5] in email:
            dup += 1
        sno.add(f[1])
        phone.add(f[4])
        email.add(f[5])
    check("学号/手机/邮箱唯一", dup)
    return n_u


def main():
    t0 = time.time()
    print("========== 种子数据独立校验 ==========", flush=True)
    n_res, acc, future = verify_reservations()
    n_occ = verify_occupancy(acc)
    n_slot = verify_slots(future)
    n_sel = verify_courses()
    n_user = verify_uniques()

    print("\n----- 行数统计 -----", flush=True)
    files = ["t_user", "t_user_quota", "t_resource", "t_resource_slot", "t_reservation",
             "t_user_time_occupancy", "t_lecture", "t_library_area", "t_venue",
             "t_course", "t_course_class", "t_course_selection"]
    for name in files:
        path = os.path.join(OUT_DIR, name + ".csv")
        with open(path, "r", encoding="utf-8") as f:
            cnt = sum(1 for _ in f)
        print(f"  {name:26s} {cnt:>10,d}", flush=True)

    total_bad = len(failures)
    print(f"\n校验完成：{len(checks) - total_bad}/{len(checks)} 项通过，耗时 {time.time()-t0:.1f}s", flush=True)
    if failures:
        print("失败项：", "；".join(failures), flush=True)
        sys.exit(1)
    print("*** 全部通过 ***", flush=True)


if __name__ == "__main__":
    main()
