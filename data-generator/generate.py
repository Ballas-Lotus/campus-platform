# -*- coding: utf-8 -*-
"""
校园平台种子数据生成器（阶段 2）
================================
纯标准库（random 固定 seed=42），生成 13 张表的 CSV 到 output/，供 import_data.py
用 LOAD DATA LOCAL INFILE 批量导入。

设计要点（对应 docs/种子数据预览.md 第 5/6 节）：
  1. 时间分布有规律：人格时段偏好 × 工作日/周末曲线 × 暑假/开学/高峰月份因子
  2. 用户行为有偏好：每人固定人格 + 活跃度权重；爽约与"惯犯"关联
  3. 无时间冲突：96 格位图（15 分钟粒度）做跨资源冲突检测，同用户同日两两不重叠
  4. 库存自洽：t_resource_slot.booked 由预约逐片记账回填（未来 14 天滚动窗口）
  5. 防重复预订：生成时保证同片同用户最多一条"有效"预约；1% 历史单附带
     "先取消后重约"记录（演示 active_key 生成列的价值）

输出：output/*.csv（13 张表）+ output/gen_stats.json
运行：python generate.py     （纯标准库，无需第三方依赖；仅需 Python 3.10+）
"""

import json
import os
import random
import time
from bisect import bisect_right
from datetime import date, datetime, timedelta

import config as C

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output")
NULL = "\\N"

# 数据规模（与 config 口径联动，便于集中调整）
N_USERS = len(C.DEMO_USERS) + C.N_STUDENTS + 1      # 10,004（含常驻测试账号 id=10004）
N_DAYS = C.HISTORY_DAYS + C.LECTURE_FUTURE_DAYS     # 120（历史 90 + 讲座最远排期 30；位图数组覆盖范围）
N_SEATS_PER_DAY = C.SLOTS_PER_DAY                   # 14
SEAT_SLOT_TOTAL = 3000 * C.FUTURE_DAYS * C.SLOTS_PER_DAY      # 588,000
VENUE_SLOT_BASE = SEAT_SLOT_TOTAL + 1                          # 588,001
VENUE_SLOT_TOTAL = 20 * C.FUTURE_DAYS * C.SLOTS_PER_DAY       # 3,920
LECTURE_SLOT_BASE = VENUE_SLOT_BASE + VENUE_SLOT_TOTAL         # 591,921

# 未来日期相对索引 → 座位/场馆片 index 换算：idx = 资源序 * 196 + 天 * 14 + 小时
SLOTS_PER_RES = C.FUTURE_DAYS * C.SLOTS_PER_DAY

# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def d2s(d):
    return d.strftime("%Y-%m-%d")


def t2s(h):
    """整点小时 → MySQL TIME 字符串"""
    return f"{h:02d}:00:00"


def dt2s(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def dt2s_epoch(ep):
    return datetime.fromtimestamp(ep).strftime("%Y-%m-%d %H:%M:%S")


def make_bitmap(start_h, end_h):
    """整点时段 → 96 bit 位图（bit i = 第 i 个 15 分钟格；低 bit 在右）"""
    return ((1 << (end_h * 4)) - 1) ^ ((1 << (start_h * 4)) - 1)


def bmp_hex(bits):
    return f"{bits:024x}"


def cum_list(weights):
    """权重 → 累积和数组（配合 pick_cum 做 O(log n) 加权采样）"""
    out = []
    acc = 0
    for w in weights:
        acc += w
        out.append(acc)
    return out


def pick_cum(rng, cum):
    return bisect_right(cum, rng.random() * cum[-1])


def sv(v):
    """字段值 → CSV 字符串（None → NULL 标记）"""
    return NULL if v is None else str(v)


class Writer:
    """单表 CSV 写入器（1MB 缓冲）"""

    def __init__(self, name):
        self.path = os.path.join(OUT_DIR, name + ".csv")
        self.f = open(self.path, "w", encoding="utf-8", newline="\n", buffering=1024 * 1024)
        self.w = self.f.write
        self.n = 0

    def row(self, *fields):
        self.w(",".join(sv(x) for x in fields))
        self.w("\n")
        self.n += 1

    def close(self):
        self.f.close()


class Ledger:
    """时间片库存账本（booked / 操作次数 / 最后操作时间）"""

    __slots__ = ("booked", "ops", "last", "caps", "caps_is_list")

    def __init__(self, n, caps):
        self.booked = [0] * n
        self.ops = [0] * n
        self.last = [0] * n
        self.caps = caps
        self.caps_is_list = isinstance(caps, list)

    def cap(self, i):
        return self.caps[i] if self.caps_is_list else self.caps

    def try_book(self, i, now_ep):
        if self.booked[i] >= self.cap(i):
            return False
        self.booked[i] += 1
        self.ops[i] += 1
        self.last[i] = now_ep
        return True

    def release(self, i, now_ep):
        self.booked[i] -= 1
        self.ops[i] += 1
        self.last[i] = now_ep


# ---------------------------------------------------------------------------
# 时段偏好权重：14 格（8:00-21:00 起始）× 人格 × 工作日/周末
# ---------------------------------------------------------------------------

HOUR_W = {
    "EARLY":    {0: [9, 10, 9, 7, 3, 2, 5, 6, 5, 4, 4, 5, 4, 3], 1: [5, 6, 6, 5, 4, 4, 5, 6, 5, 4, 5, 6, 5, 4]},
    "NIGHT":    {0: [2, 3, 4, 4, 3, 3, 5, 6, 6, 5, 9, 10, 9, 7], 1: [3, 4, 4, 4, 4, 4, 5, 6, 6, 6, 8, 9, 8, 6]},
    "NOON":     {0: [3, 3, 4, 4, 7, 8, 7, 6, 5, 4, 4, 5, 4, 3], 1: [3, 4, 5, 5, 7, 8, 7, 6, 5, 5, 5, 6, 5, 4]},
    "BALANCED": {0: [6, 7, 7, 6, 4, 4, 6, 7, 7, 5, 6, 7, 6, 5], 1: [5, 6, 6, 5, 5, 5, 6, 6, 6, 5, 6, 6, 6, 5]},
}
# 预计算 cum 缓存：key = (persona, weekend_flag, dur)（dur 影响可选的起始格数 = 15-dur）
HOUR_CUM = {}
for _p, _wy in HOUR_W.items():
    for _wk, _ws in _wy.items():
        for _dur in (1, 2, 3, 4):
            HOUR_CUM[(_p, _wk, _dur)] = cum_list(_ws[:15 - _dur])


def hist_factor_lib(d):
    """历史日需求因子（图书馆）：国庆假期/暑假/开学/常态 × 周末"""
    if d.month == 10 and d.day <= 7:
        f = 0.45                                  # 国庆假期（学生离校）
    elif d.month in (7, 8):
        f = 0.50                                  # 暑假
    elif d.month == 9 and d.day < 8:
        f = 0.65                                  # 开学初期
    elif d.month == 9:
        f = 0.95
    else:
        f = 1.0
    if d.weekday() >= 5:
        f *= 0.9
    return f


def hist_factor_venue(d):
    if d.month == 10 and d.day <= 7:
        f = 0.6
    elif d.month in (7, 8):
        f = 0.55
    elif d.month == 9 and d.day < 8:
        f = 0.65
    elif d.month == 9:
        f = 1.0
    else:
        f = 1.05
    if d.weekday() >= 5:
        f *= 1.3
    return f


# ---------------------------------------------------------------------------
# 主体
# ---------------------------------------------------------------------------

def main():
    t_start = time.time()
    os.makedirs(OUT_DIR, exist_ok=True)
    rng = random.Random(C.SEED)
    now = datetime.now()
    now_ep = int(now.timestamp())

    stats = {}

    # ======================================================================
    # 1. 用户（演示账号 1/2/3 + 批量学生）
    # ======================================================================
    print("[1/12] 生成用户 ...", flush=True)
    users = []          # dict: idx, id, persona, activity_cum, noshow_prob, ...
    used_names = set()
    used_phones = set()

    def gen_name():
        for _ in range(6):
            surname = rng.choice(C.SURNAMES)
            if rng.random() < 0.75:
                name = surname + rng.choice(C.GIVEN_A) + rng.choice(C.GIVEN_B)
            else:
                name = surname + rng.choice(C.GIVEN_A)
            if name not in used_names:
                used_names.add(name)
                return name
        return name  # 极少数允许重名

    w_user = Writer("t_user")
    for (uid, sno, name, phone, email, college, major, grade, role) in C.DEMO_USERS:
        used_phones.add(phone)
        used_names.add(name)
        if role == "ADMIN":
            ct = datetime(2024, 1, 1, 9, 0, 0)
        else:
            ct = datetime(2026, 9, 1, 8, 0, 0) + timedelta(seconds=rng.randint(0, 86400 * 3))
        users.append(dict(idx=len(users), id=uid, persona="EARLY" if uid == 1 else "NIGHT",
                          activity=None, noshow_prob=0.0))
        w_user.row(uid, sno, C.PASSWORD_HASH, name, phone, email, college, major, grade, role, 1,
                   dt2s(ct), dt2s(ct), 0)

    colleges = list(C.COLLEGES.items())
    for i in range(C.N_STUDENTS):
        uid = len(C.DEMO_USERS) + 1 + i
        grade = C.GRADES[i % 4]                      # 均匀分布大一~大四
        college, majors = colleges[i // 1250 % 8]    # 每学院 1250 人
        major = rng.choice(majors)
        sno = grade + f"{i // 4 + 1:04d}"            # {入学年}{4位序号}，每年级 2500 人
        name = gen_name()
        while True:
            phone = rng.choice(C.PHONE_PREFIX) + "".join(rng.choice("0123456789") for _ in range(8))
            if phone not in used_phones:
                used_phones.add(phone)
                break
        email = f"{sno}@stu.campus.edu.cn"
        persona = rng.choices(C.PERSONAS, weights=C.PERSONA_WEIGHTS)[0]
        activity = rng.choice(C.USER_ACTIVITY)
        noshow_prob = C.NO_SHOW_PROB_FRAUD if rng.random() < C.NO_SHOW_FRAUD_RATE else C.NO_SHOW_PROB_NORMAL
        status = 0 if rng.random() < 0.01 else 1      # 约 1% 禁用样本
        ct = datetime(int(grade), 9, 1, 8, 0, 0) + timedelta(seconds=rng.randint(0, 86400 * 30))
        users.append(dict(idx=len(users), id=uid, persona=persona,
                          activity=activity, noshow_prob=noshow_prob))
        w_user.row(uid, sno, C.PASSWORD_HASH, name, phone, email, college, major, grade,
                   "STUDENT", status, dt2s(ct), dt2s(ct), 0)

    # 常驻测试账号（id=10004；不参与预约采样——其预约数据由后端在每次登录时重置）
    tu = C.TEST_USER
    test_ct = datetime(2026, 9, 1, 8, 0, 0)
    users.append(dict(idx=len(users), id=tu[0], persona="BALANCED", activity=None, noshow_prob=0.0))
    w_user.row(tu[0], tu[1], C.PASSWORD_HASH, tu[2], tu[3], tu[4], tu[5], tu[6], tu[7], tu[8],
               1, dt2s(test_ct), dt2s(test_ct), 0)
    w_user.close()
    stats["t_user"] = w_user.n

    # 采样权重：管理员（id=3）与测试账号（id=10004）权重 0 → 永不产生种子预约
    user_cum = cum_list([0.0 if u["id"] in (3, C.TEST_USER[0]) else (u["activity"] or 1.0)
                         for u in users])

    # ======================================================================
    # 2. 图书馆：馆区（15）+ 座位资源（3000）
    # ======================================================================
    print("[2/12] 生成图书馆馆区与座位资源 ...", flush=True)
    w_area = Writer("t_library_area")
    area_ct = datetime(2024, 9, 1, 9, 0, 0)
    aid = 0
    for (bname, per_floor) in C.LIBRARY_BUILDINGS:
        for floor in C.LIBRARY_FLOORS:
            aid += 1
            w_area.row(aid, f"{bname}{floor}层", "主校区", floor,
                       C.LIBRARY_OPEN, C.LIBRARY_CLOSE, per_floor,
                       dt2s(area_ct), dt2s(area_ct), 0)
    w_area.close()
    stats["t_library_area"] = w_area.n

    seats = []           # dict: rid, name, location, campus, res_ct
    rid = 10001
    for (bname, per_floor) in C.LIBRARY_BUILDINGS:
        half = per_floor // 2
        for floor in C.LIBRARY_FLOORS:
            for zone in ("A", "B"):
                for seq in range(1, half + 1):
                    name = f"{bname}-{floor}F-{zone}区-{seq:03d}"
                    seats.append(dict(rid=rid, name=name,
                                      location=f"{bname}{floor}层{zone}区",
                                      campus="主校区",
                                      ct=dt2s(area_ct)))
                    rid += 1
    seat_cum = cum_list([
        (5 if st["name"].startswith(C.SEAT_HOTSPOTS[0][0])
         else 3 if st["name"].startswith(C.SEAT_HOTSPOTS[1][0])
         else C.SEAT_BASE_WEIGHT) for st in seats])
    assert len(seats) == 3000, len(seats)

    # ======================================================================
    # 3. 场馆（20）+ 场馆资源
    # ======================================================================
    print("[3/12] 生成场馆与资源 ...", flush=True)
    w_venue = Writer("t_venue")
    venues = []          # dict: rid, vid, name, capacity, need_approval, hotspot
    vrid = 20001
    venue_ct = dt2s(datetime(2024, 9, 1, 9, 0, 0))
    for (vid, name, vtype, campus, price, need_appr, week_limit, cap, hot) in C.VENUES:
        w_venue.row(vid, vrid, name, vtype, campus, C.VENUE_OPEN, C.VENUE_CLOSE,
                    f"{price:.2f}", need_appr, week_limit, venue_ct, venue_ct, 0)
        venues.append(dict(rid=vrid, vid=vid, name=name, capacity=cap, campus=campus,
                           location=C.VENUE_LOCATIONS.get(vtype), need_approval=need_appr,
                           hotspot=hot, ct=venue_ct))
        vrid += 1
    w_venue.close()
    stats["t_venue"] = w_venue.n

    venue_cum = cum_list([(4 if v["hotspot"] else 1) for v in venues])
    # 每片容量数组（3920 = 20 馆 × 14 天 × 14 格；同一馆的所有片容量相同）
    venue_caps = [v["capacity"] for v in venues for _ in range(SLOTS_PER_RES)]

    # ======================================================================
    # 4. 讲座（20）+ 名额资源（每场 1 条，报名制）
    # ======================================================================
    print("[4/12] 生成讲座与名额资源 ...", flush=True)
    w_lecture = Writer("t_lecture")
    # 2026-10-08 报名制：每场讲座 1 条 LECTURE 名额资源（capacity=场容量，无座位图）
    # 资源 id 保持 30001、+容量 递增（与线上迁移结果一致）
    lectures = []
    lec_rid = 30001
    for k, (lid, title, speaker, cap, afternoon, hot) in enumerate(C.LECTURES):
        d = C.TODAY + timedelta(days=1 + int(k * 1.5))          # 未来 30 天内分布
        h0, h1 = C.LECTURE_AFTERNOON if afternoon else C.LECTURE_EVENING
        start_dt = datetime(d.year, d.month, d.day, h0, 0, 0)
        end_dt = start_dt + timedelta(hours=h1 - h0)
        location = rng.choice(C.LECTURE_LOCATIONS)
        publish = start_dt - timedelta(days=rng.randint(10, 20))
        desc = f"面向全校师生的公开讲座，主讲人：{speaker}。请提前预约入场。"
        w_lecture.row(lid, title, speaker, location, dt2s(start_dt), dt2s(end_dt), cap, 1,
                      desc, dt2s(publish), dt2s(publish), 0)
        lectures.append(dict(lid=lid, title=title, capacity=cap, start_dt=start_dt,
                             end_dt=end_dt, res_rid=lec_rid, location=location, hot=hot,
                             publish=dt2s(publish),
                             fill=min(0.96, 0.55 + 0.08 * hot)))
        lec_rid += cap
    w_lecture.close()
    stats["t_lecture"] = w_lecture.n
    lecture_res_total = len(lectures)                            # 20 条名额资源
    assert lec_rid == 30001 + sum(c[3] for c in C.LECTURES), lec_rid

    # ======================================================================
    # 5. 课程（200）+ 教学班（300）+ 名额资源
    # ======================================================================
    print("[5/12] 生成课程、教学班与名额资源 ...", flush=True)
    courses = []
    classes = []         # dict: cid, course_id, name, capacity, day, s1, s2, selected=0
    cid = 7001
    course_id = 0
    used_rooms = set()   # (day, s1, location) 防撞
    for college, names in C.COURSE_NAMES.items():
        for ni, cname in enumerate(names):
            course_id += 1
            ctype = "必修" if ni < 10 else ("选修" if ni < 18 else "通识")
            code = C.COURSE_CODE_PREFIX[college] + str(101 + ni)
            teacher = gen_name()
            courses.append(dict(id=course_id, code=code, name=cname, teacher=teacher,
                                credits=rng.choice(C.COURSE_CREDITS[ctype]),
                                college=college, ctype=ctype,
                                ct=dt2s(datetime(2026, 8, 10, 10, 0, 0) + timedelta(minutes=rng.randint(0, 3600)))))
            for k in range(2 if course_id % 2 == 1 else 1):      # 100×2 + 100×1 = 300 班
                day = rng.randint(1, 5)
                s1, s2 = rng.choice(C.SECTION_PAIRS)
                for _ in range(20):
                    loc = rng.choice(C.CLASSROOMS)
                    if (day, s1, loc) not in used_rooms:
                        used_rooms.add((day, s1, loc))
                        break
                classes.append(dict(cid=cid, course_id=course_id, name=f"{k+1:02d}班",
                                    teacher=teacher if k == 0 else gen_name(),
                                    capacity=rng.randint(*C.CLASS_CAPACITY[ctype]),
                                    selected=0, day=day, s1=s1, s2=s2, loc=loc,
                                    ctype=ctype))
                cid += 1
    assert len(courses) == 200 and len(classes) == 300, (len(courses), len(classes))

    # ======================================================================
    # 6. 选课（7000；含 20 个热门满班——演示"名额已满直接拒绝"，无候补队列）
    # ======================================================================
    print("[6/12] 生成选课 ...", flush=True)
    sel_u = rng.sample([u["idx"] for u in users if u["id"] > 3], C.N_SELECTION_STUDENTS - 2)
    sel_students = [0, 1] + sel_u                     # idx 0/1 = 演示账号林晚秋/陈屿
    stu_sched = {u: [] for u in sel_students}         # 学生课表：[(day, s1, s2)]
    stu_taken = {u: set() for u in sel_students}      # 已选 class_id
    quota = {u: rng.randint(6, 8) for u in sel_students}
    cls_by_name = {}
    for c in classes:
        cls_by_name[(courses[c["course_id"] - 1]["name"], c["name"])] = c

    sel_rows = []          # (user_idx, cid, ct_datetime)
    open_days = (C.SELECTION_OPEN_END - C.SELECTION_OPEN_START).days + 1

    def sel_ct():
        return datetime.combine(C.SELECTION_OPEN_START, datetime.min.time()) + timedelta(
            days=rng.randint(0, open_days - 1), seconds=rng.randint(0, 86399))

    def try_take(u, cls):
        """尝试为学生选上教学班（额度/冲突/重复检查）"""
        if quota[u] <= 0 or cls["cid"] in stu_taken[u] or cls["selected"] >= cls["capacity"]:
            return False
        for (d0, a0, b0) in stu_sched[u]:
            if d0 == cls["day"] and not (cls["s2"] < a0 or cls["s1"] > b0):
                return False
        stu_taken[u].add(cls["cid"])
        stu_sched[u].append((cls["day"], cls["s1"], cls["s2"]))
        cls["selected"] += 1
        quota[u] -= 1
        sel_rows.append((u, cls["cid"], sel_ct()))
        return True

    # 6.1 热门班"秒满"（演示"名额已满直接拒绝"；2026-10-08 规则：选课无候补队列）
    for cname in C.HOT_COURSE_NAMES:
        cls = cls_by_name[(cname, "01班")]
        guard = 0
        while cls["selected"] < cls["capacity"] and guard < cls["capacity"] * 30:
            guard += 1
            try_take(rng.choice(sel_students), cls)
    # 6.2 剩余额度撒点（避开已满/冲突/重复）
    active = [u for u in sel_students if quota[u] > 0]
    guard = 0
    while active and guard < 200000:
        guard += 1
        i = rng.randrange(len(active))
        u = active[i]
        cls = classes[rng.randrange(len(classes))]
        try_take(u, cls)
        if quota[u] <= 0:
            active[i] = active[-1]
            active.pop()

    # 6.3 落盘选课
    w_sel = Writer("t_course_selection")
    for sid, (u, c, ct) in enumerate(sel_rows, 1):
        uid = u + 1
        w_sel.row(sid, uid, c, C.SEMESTER, "SELECTED", dt2s(ct), dt2s(ct))
    w_sel.close()
    stats["t_course_selection"] = w_sel.n

    # ======================================================================
    # 7. 统一资源表落盘（座位 + 场馆 + 讲座名额 + 课程名额）
    # ======================================================================
    print("[7/12] 落盘统一资源表（9,320 条）...", flush=True)
    w_res = Writer("t_resource")
    res_ct_seat = seats[0]["ct"]
    for st in seats:
        w_res.row(st["rid"], "LIBRARY_SEAT", st["name"], None, st["location"], st["campus"],
                  1, 1, 0, 1, res_ct_seat, res_ct_seat, 0)
    for v in venues:
        w_res.row(v["rid"], "VENUE", v["name"], None, v["location"], v["campus"],
                  v["capacity"], 0, v["need_approval"], 1, v["ct"], v["ct"], 0)
    for lec in lectures:
        # 讲座名额资源：每场一条（容量计数，与场馆同语义——报名制，无座位图）
        w_res.row(lec["res_rid"], "LECTURE", f"讲座名额·{lec['title']}",
                  lec["lid"], lec["location"], "主校区", lec["capacity"], 0, 0, 1,
                  lec["publish"], lec["publish"], 0)
    course_res_ct = dt2s(datetime(2026, 8, 15, 10, 0, 0))
    course_rid = 40001
    for c in classes:
        cname = courses[c["course_id"] - 1]["name"]
        w_res.row(course_rid, "COURSE_QUOTA", f"{cname}-{c['name']}-名额", c["cid"],
                  None, None, c["capacity"], 0, 0, 1, course_res_ct, course_res_ct, 0)
        c["res_rid"] = course_rid
        course_rid += 1
    w_res.close()
    stats["t_resource"] = w_res.n

    # ======================================================================
    # 8. 预约引擎：位图 + 库存账本
    # ======================================================================
    print("[8/12] 生成预约（历史 90 天 + 未来 14 天 + 讲座）...", flush=True)
    occ = [0] * (N_USERS * N_DAYS)       # 未取消预约的位图 OR
    occv = [0] * (N_USERS * N_DAYS)      # version：当日操作次数（预约 + 取消）
    occ_ct = [0] * (N_USERS * N_DAYS)    # 首条预约 create_time (epoch)
    occ_ut = [0] * (N_USERS * N_DAYS)    # 最后操作 update_time (epoch)

    seat_ledger = Ledger(SEAT_SLOT_TOTAL, 1)
    venue_ledger = Ledger(VENUE_SLOT_TOTAL, venue_caps)
    # 讲座：每场一片，容量=场容量（报名制，容量计数）
    lecture_ledger = Ledger(lecture_res_total, [lec["capacity"] for lec in lectures])

    quota_lib_today = [0] * N_USERS
    quota_venue_week = [0] * N_USERS
    quota_noshow = [0] * N_USERS

    w_resv = Writer("t_reservation")
    resv_seq = 0
    extra_pairs = 0      # "先取消后重约"额外记录数
    miss = 0             # 重采样仍失败的预约数

    def write_resv(uid_idx, res_rid, rtype, d, h0, h1, bits, status, ct, ut, remark, slot_id, extra=False):
        nonlocal resv_seq, extra_pairs
        resv_seq += 1
        rno = f"RES{d:%Y%m%d}{resv_seq:012d}"
        w_resv.w(f"{resv_seq},{rno},{uid_idx + 1},{res_rid},{rtype},{sv(slot_id)},"
                 f"{d2s(d)},{t2s(h0)},{t2s(h1)},{bmp_hex(bits)},{status},{sv(remark)},"
                 f"{dt2s(ct)},{dt2s(ut)}\n")
        w_resv.n += 1
        if extra:
            extra_pairs += 1
        return resv_seq

    def stamp(day_idx, ct_dt, ut_dt):
        """更新用户占用数组的时间戳"""
        base = day_idx
        if occ_ct[base] == 0:
            occ_ct[base] = int(ct_dt.timestamp())
        occ_ut[base] = int(ut_dt.timestamp())

    def gen_reservation(kind, d, is_history):
        """生成一条预约（含重采样）。kind: 'lib' | 'venue'。返回 True/False（成功/放弃）"""
        nonlocal miss
        didx = (d - C.HISTORY_START).days
        weekend = d.weekday() >= 5
        f_dur = C.LIB_DURATION if kind == "lib" else C.VENUE_DURATION
        for _ in range(C.RESERVE_RETRY):
            u_idx = pick_cum(rng, user_cum)
            persona = users[u_idx]["persona"]
            dur = rng.choice(f_dur)
            h0 = pick_cum(rng, HOUR_CUM[(persona, 1 if weekend else 0, dur)]) + C.OPEN_HOUR
            h1 = h0 + dur
            bits = make_bitmap(h0, h1)
            ob = u_idx * N_DAYS + didx
            if occ[ob] & bits:
                continue
            # 状态：15% 取消（历史）/ 2%（未来）；取消单不占位图与库存
            cancelled = rng.random() < (C.CANCEL_PROB if is_history else C.FUTURE_CANCEL_PROB)
            if is_history:
                if cancelled:
                    if kind == "lib":
                        res = seats[pick_cum(rng, seat_cum)]
                    else:
                        res = venues[pick_cum(rng, venue_cum)]
                    ct = datetime.combine(d, datetime.min.time()) + timedelta(hours=h0) \
                         - timedelta(seconds=rng.randint(3600, 86400 * 5))
                    ut = ct + (datetime.combine(d, datetime.min.time()) + timedelta(hours=h0) - ct) * rng.uniform(0.1, 0.9)
                    write_resv(u_idx, res["rid"], "LIBRARY_SEAT" if kind == "lib" else "VENUE",
                               d, h0, h1, bits, "CANCELLED", ct, ut,
                               rng.choice(C.CANCEL_REASONS), None)
                    occv[ob] += 1
                    return True
                # 非取消：位图检查已过 → 占位图
                occ[ob] |= bits
                occv[ob] += 1
                if rng.random() < users[u_idx]["noshow_prob"]:
                    status = "NO_SHOW"
                    quota_noshow[u_idx] += 1
                else:
                    status = "COMPLETED"
                if kind == "lib":
                    res = seats[pick_cum(rng, seat_cum)]
                else:
                    res = venues[pick_cum(rng, venue_cum)]
                start_dt = datetime.combine(d, datetime.min.time()) + timedelta(hours=h0)
                ct = start_dt - timedelta(seconds=rng.randint(2600, 86400 * 6))
                ut = start_dt + timedelta(hours=(h1 - h0), seconds=rng.randint(0, 7200))
                write_resv(u_idx, res["rid"], "LIBRARY_SEAT" if kind == "lib" else "VENUE",
                           d, h0, h1, bits, status, ct, ut, None, None)
                stamp(ob, ct, ut)
                # 1% 概率：为该片段补一条更早的"已取消"记录 → 演示"取消-再约"
                if rng.random() < 0.01:
                    ut2 = ct - timedelta(minutes=rng.randint(30, 600))
                    ct2 = ct - timedelta(hours=rng.randint(12, 72))
                    write_resv(u_idx, res["rid"], "LIBRARY_SEAT" if kind == "lib" else "VENUE",
                               d, h0, h1, bits, "CANCELLED", ct2, ut2,
                               rng.choice(C.CANCEL_REASONS), None, extra=True)
                    occv[ob] += 1
                    if occ_ct[ob] > int(ct2.timestamp()):
                        occ_ct[ob] = int(ct2.timestamp())
                return True
            else:
                # 未来：需要库存片
                fi = (d - C.TODAY).days
                if kind == "lib":
                    res = pick_cum(rng, seat_cum)
                    res_d = seats[res]
                    ledger, res_idx = seat_ledger, res
                else:
                    res = pick_cum(rng, venue_cum)
                    res_d = venues[res]
                    ledger, res_idx = venue_ledger, res
                si_list = [res_idx * SLOTS_PER_RES + fi * C.SLOTS_PER_DAY + (h - C.OPEN_HOUR)
                           for h in range(h0, h1)]
                first_slot_id = si_list[0] + 1 if kind == "lib" else VENUE_SLOT_BASE + si_list[0]
                booked = []
                ok = True
                for si in si_list:
                    if ledger.try_book(si, now_ep):
                        booked.append(si)
                    else:
                        ok = False
                        break
                if not ok:
                    for si in booked:
                        ledger.release(si, now_ep)
                    continue
                start_dt = datetime.combine(d, datetime.min.time()) + timedelta(hours=h0)
                ct = start_dt - timedelta(seconds=rng.randint(2600, 86400 * 6))
                if ct > now:
                    ct = now - timedelta(seconds=rng.randint(600, 86400 * 2))
                if cancelled:
                    for si in si_list:
                        ledger.release(si, now_ep)
                    write_resv(u_idx, res_d["rid"], "LIBRARY_SEAT" if kind == "lib" else "VENUE",
                               d, h0, h1, bits, "CANCELLED", ct, ct,
                               rng.choice(C.CANCEL_REASONS), first_slot_id)
                    occv[ob] += 1
                    return True
                occ[ob] |= bits
                occv[ob] += 1
                if res_d.get("need_approval") and rng.random() < C.PENDING_PROB:
                    status = "PENDING"
                else:
                    status = "APPROVED"
                write_resv(u_idx, res_d["rid"], "LIBRARY_SEAT" if kind == "lib" else "VENUE",
                           d, h0, h1, bits, status, ct, ct, None, first_slot_id)
                stamp(ob, ct, ct)
                if kind == "lib" and d == C.TODAY:
                    quota_lib_today[u_idx] += 1
                if kind == "venue" and C.QUOTA_VENUE_START <= d <= C.QUOTA_VENUE_END:
                    quota_venue_week[u_idx] += 1
                return True
        miss += 1
        return False

    # ------------------------------------------------------------------
    # 8.1 演示剧本预约（先占位，保证演示账号的数据干净）
    # ------------------------------------------------------------------
    seat_by_name = {st["name"]: st for st in seats}
    venue_by_name = {v["name"]: v for v in venues}
    demo_ct_base = now - timedelta(days=1)
    for (uid, doff, h0, dur, rname, status) in C.DEMO_RESERVATIONS:
        d = C.TODAY + timedelta(days=doff)
        h1 = h0 + dur
        bits = make_bitmap(h0, h1)
        u_idx = uid - 1
        didx = (d - C.HISTORY_START).days
        ob = u_idx * N_DAYS + didx
        if rname.startswith("逸夫楼"):
            res = seat_by_name[rname]
            rtype = "LIBRARY_SEAT"
            need_appr, cap_ok = False, True
        else:
            res = venue_by_name[rname]
            rtype = "VENUE"
            need_appr, cap_ok = bool(res["need_approval"]), True
        start_dt = datetime.combine(d, datetime.min.time()) + timedelta(hours=h0)
        ct = min(start_dt - timedelta(days=2, hours=rng.randint(0, 5)), demo_ct_base)
        slot_id = None
        if d >= C.TODAY:      # 未来：占库存片（演示剧本先于批量生成，必占成功）
            fi = (d - C.TODAY).days
            if rtype == "LIBRARY_SEAT":
                r_i, ledger = res["rid"] - 10001, seat_ledger
            else:
                r_i, ledger = res["rid"] - 20001, venue_ledger
            si_list = [r_i * SLOTS_PER_RES + fi * C.SLOTS_PER_DAY + (h - C.OPEN_HOUR)
                       for h in range(h0, h1)]
            for si in si_list:
                assert ledger.try_book(si, now_ep), f"演示预约片冲突: {rname} {d} {h0}"
            slot_id = si_list[0] + 1 if rtype == "LIBRARY_SEAT" else VENUE_SLOT_BASE + si_list[0]
        if status != "CANCELLED":
            occ[ob] |= bits
            occv[ob] += 1
            stamp(ob, ct, ct)
            if rtype == "LIBRARY_SEAT" and d == C.TODAY:
                quota_lib_today[u_idx] += 1
            if rtype == "VENUE" and C.QUOTA_VENUE_START <= d <= C.QUOTA_VENUE_END:
                quota_venue_week[u_idx] += 1
        else:
            occv[ob] += 1
        ut = ct
        write_resv(u_idx, res["rid"], rtype, d, h0, h1, bits, status, ct, ut, None, slot_id)

    # ------------------------------------------------------------------
    # 8.2 历史 90 天
    # ------------------------------------------------------------------
    for di in range(C.HISTORY_DAYS):
        d = C.HISTORY_START + timedelta(days=di)
        n_lib = int(C.HIST_LIB_PER_DAY * hist_factor_lib(d))
        n_ven = int(C.HIST_VENUE_PER_DAY * hist_factor_venue(d))
        for _ in range(n_lib):
            gen_reservation("lib", d, True)
        for _ in range(n_ven):
            gen_reservation("venue", d, True)
        if di % 15 == 14:
            print(f"    历史进度 {di+1}/90 天，累计预约 {w_resv.n} 条（{time.time()-t_start:.0f}s）", flush=True)

    # ------------------------------------------------------------------
    # 8.3 未来 14 天
    # ------------------------------------------------------------------
    for fi in range(C.FUTURE_DAYS):
        d = C.TODAY + timedelta(days=fi)
        holiday = 0.55 if (d.month == 10 and d.day <= 7) else 1.0   # 国庆假期（最后一天 = 今天）
        decay = C.FUTURE_DECAY[fi]
        n_lib = int(C.FUTURE_LIB_BASE * decay * holiday)
        n_ven = int(C.FUTURE_VENUE_BASE * decay * holiday * (1.25 if d.weekday() >= 5 else 1))
        for _ in range(n_lib):
            gen_reservation("lib", d, False)
        for _ in range(n_ven):
            gen_reservation("venue", d, False)

    # ------------------------------------------------------------------
    # 8.4 讲座报名（人数限额；时段冲突自动跳过）
    # ------------------------------------------------------------------
    for k, lec in enumerate(lectures):
        start_dt = lec["start_dt"]
        h0, h1 = start_dt.hour, lec["end_dt"].hour
        bits = make_bitmap(h0, h1)
        didx = (start_dt.date() - C.HISTORY_START).days
        n_target = int(lec["capacity"] * lec["fill"])
        got_users = set()
        booked = 0
        tries = 0
        limit = n_target * 6
        while booked < n_target and tries < limit:
            tries += 1
            u_idx = pick_cum(rng, user_cum)
            if u_idx in got_users:
                continue
            ob = u_idx * N_DAYS + didx
            if occ[ob] & bits:
                continue
            # 整场一片、容量计数：报名即占一个名额（与场馆同语义）
            if not lecture_ledger.try_book(k, now_ep):
                break
            got_users.add(u_idx)
            occ[ob] |= bits
            occv[ob] += 1
            ct = start_dt - timedelta(seconds=rng.randint(43200, 86400 * 12))
            if ct > now:
                ct = now - timedelta(seconds=rng.randint(600, 86400 * 3))
            write_resv(u_idx, lec["res_rid"], "LECTURE",
                       start_dt.date(), h0, h1, bits, "APPROVED", ct, ct, None,
                       LECTURE_SLOT_BASE + k)
            stamp(ob, ct, ct)
            booked += 1
    w_resv.close()
    stats["t_reservation"] = w_resv.n
    print(f"    预约生成完成：{w_resv.n} 条（额外取消-再约记录 {extra_pairs}，重采样失败 {miss}）", flush=True)

    # ======================================================================
    # 9. 时间片表（未来 14 天滚动窗口：座位 588,000 + 场馆 3,920 + 讲座 20）
    # ======================================================================
    print("[9/12] 落盘时间片库存表 ...", flush=True)
    w_slot = Writer("t_resource_slot")
    day_ds = [d2s(C.TODAY + timedelta(days=fi)) for fi in range(C.FUTURE_DAYS)]
    day_ct = []
    for fi in range(C.FUTURE_DAYS):
        d = C.TODAY + timedelta(days=fi)
        gen_dt = datetime.combine(d - timedelta(days=C.FUTURE_DAYS), datetime.min.time()) + timedelta(hours=8)
        day_ct.append(dt2s(gen_dt + timedelta(seconds=rng.randint(0, 10800))))

    sid = 0
    ep_to_s = dt2s_epoch
    # 座位（每座 14 天 × 14 格；booked/version 由预约账本回填）
    for si in range(3000):
        base = si * SLOTS_PER_RES
        for fi in range(C.FUTURE_DAYS):
            ds, ct = day_ds[fi], day_ct[fi]
            row_base = base + fi * C.SLOTS_PER_DAY
            for hi in range(C.SLOTS_PER_DAY):
                i = row_base + hi
                sid += 1
                h0 = C.OPEN_HOUR + hi
                last = seat_ledger.last[i]
                w_slot.row(sid, 10001 + si, ds, t2s(h0), t2s(h0 + 1), 1,
                           seat_ledger.booked[i], seat_ledger.ops[i], 1,
                           ct, ep_to_s(last) if last else ct)
    # 场馆
    for vi in range(20):
        base = vi * SLOTS_PER_RES
        v_cap = venues[vi]["capacity"]
        for fi in range(C.FUTURE_DAYS):
            ds, ct = day_ds[fi], day_ct[fi]
            row_base = base + fi * C.SLOTS_PER_DAY
            for hi in range(C.SLOTS_PER_DAY):
                i = row_base + hi
                sid += 1
                h0 = C.OPEN_HOUR + hi
                last = venue_ledger.last[i]
                w_slot.row(sid, 20001 + vi, ds, t2s(h0), t2s(h0 + 1),
                           v_cap, venue_ledger.booked[i], venue_ledger.ops[i], 1,
                           ct, ep_to_s(last) if last else ct)
    # 讲座名额片（每场一片：整场一格，容量=场容量）
    for k, lec in enumerate(lectures):
        h0, h1 = lec["start_dt"].hour, lec["end_dt"].hour
        ds = d2s(lec["start_dt"].date())
        ct = lec["publish"]
        sid += 1
        b = lecture_ledger.booked[k]
        ut = dt2s_epoch(lecture_ledger.last[k]) if lecture_ledger.last[k] else ct
        w_slot.row(sid, lec["res_rid"], ds, t2s(h0), t2s(h1), lec["capacity"],
                   b, lecture_ledger.ops[k], 1, ct, ut)
    w_slot.close()
    stats["t_resource_slot"] = w_slot.n
    assert sid == SEAT_SLOT_TOTAL + VENUE_SLOT_TOTAL + lecture_res_total, sid

    # ======================================================================
    # 10. 用户时间位图表
    # ======================================================================
    print("[10/12] 落盘用户时间位图表 ...", flush=True)
    w_occ = Writer("t_user_time_occupancy")
    for u_idx in range(N_USERS):
        base = u_idx * N_DAYS
        for di in range(N_DAYS):
            b = occ[base + di]
            if b:
                d = C.HISTORY_START + timedelta(days=di)
                w_occ.row(u_idx + 1, d2s(d), bmp_hex(b), occv[base + di],
                          dt2s_epoch(occ_ct[base + di]), dt2s_epoch(occ_ut[base + di]))
    w_occ.close()
    stats["t_user_time_occupancy"] = w_occ.n

    # ======================================================================
    # 11. 配额表
    # ======================================================================
    print("[11/12] 落盘配额表 ...", flush=True)
    w_quota = Writer("t_user_quota")
    qct = dt2s(datetime.combine(C.QUOTA_NOSHOW_START, datetime.min.time()) + timedelta(hours=9))
    qid = 0
    for u_idx in range(N_USERS):
        uid = u_idx + 1
        qid += 1
        w_quota.row(qid, uid, "LIBRARY_DAILY", quota_lib_today[u_idx],
                    d2s(C.QUOTA_LIBRARY_START), d2s(C.QUOTA_LIBRARY_END), qct, qct, 0)
        qid += 1
        w_quota.row(qid, uid, "VENUE_WEEKLY", quota_venue_week[u_idx],
                    d2s(C.QUOTA_VENUE_START), d2s(C.QUOTA_VENUE_END), qct, qct, 0)
        if quota_noshow[u_idx] > 0:
            qid += 1
            w_quota.row(qid, uid, "NO_SHOW", quota_noshow[u_idx],
                        d2s(C.QUOTA_NOSHOW_START), d2s(C.QUOTA_NOSHOW_END), qct, qct, 0)
    w_quota.close()
    stats["t_user_quota"] = w_quota.n

    # ======================================================================
    # 12. 课程班级表（选课回填 selected 后落盘）
    # ======================================================================
    print("[12/12] 落盘课程与教学班 ...", flush=True)
    w_course = Writer("t_course")
    for c in courses:
        w_course.row(c["id"], c["code"], c["name"], c["teacher"], f"{c['credits']:.1f}",
                     c["college"], c["ctype"], c["ct"], c["ct"], 0)
    w_course.close()
    stats["t_course"] = w_course.n

    w_class = Writer("t_course_class")
    class_ct = dt2s(datetime(2026, 8, 15, 10, 0, 0))
    for c in classes:
        w_class.row(c["cid"], c["course_id"], c["name"], c["teacher"], C.SEMESTER,
                    c["capacity"], c["selected"], c["day"], c["s1"], c["s2"],
                    1, 16, c["loc"], class_ct, class_ct, 0)
    w_class.close()
    stats["t_course_class"] = w_class.n

    # ======================================================================
    # 统计
    # ======================================================================
    stats["_elapsed_sec"] = round(time.time() - t_start, 1)
    stats["_reservation_extra_pairs"] = extra_pairs
    stats["_reservation_miss"] = miss
    with open(os.path.join(OUT_DIR, "gen_stats.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)

    print("\n===== 生成完成 =====", flush=True)
    total = 0
    for k, v in stats.items():
        if k.startswith("_"):
            continue
        total += v
        print(f"  {k:26s} {v:>10,d}")
    print(f"  {'合计':24s} {total:>10,d}")
    print(f"  耗时 {stats['_elapsed_sec']}s | 取消-再约附加 {extra_pairs} | 重采样失败 {miss}")


if __name__ == "__main__":
    main()
