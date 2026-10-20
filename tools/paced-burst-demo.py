#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 21.3 · 真实节奏演示：N 名学生、T 秒内共 M 人次选课，每秒人数随机起伏。

与一次性齐射（multi-burst-demo）的差别：
  * 流量按秒分布，曲线先升后降并带随机抖动（每秒人数不一）；
  * 每次尝试随机落在某个学生 + 某门课（学生可先后抢多门课，人均 M/N 次）；
  * 分配时避开学生已选课程与时间冲突，允许“抢不到”（40052）与“已满课程”（如 CS110）；
  * 结束后默认自动退课复原。

用法（项目根目录，服务已启动并放开限流）：
  python tools/paced-burst-demo.py                         # 2000 人 / 20s / 10000 人次 / 课程 1..10
  python tools/paced-burst-demo.py --total 3000 --users 600 --duration 10
  python tools/paced-burst-demo.py --dry-run               # 只打印每秒计划与分配结果
  python tools/paced-burst-demo.py --keep                  # 保留选课，不自动清理
"""
import argparse
import http.client
import json
import math
import queue
import random
import sys
import threading
import time
from collections import Counter, defaultdict
from concurrent import futures
from datetime import datetime

HOST = "127.0.0.1"
PORT = 9080
PASSWORD = "123456"

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors="replace")
    except Exception:
        pass

_tls = threading.local()


def ts():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def _request(method, path, token=None, body=None):
    """线程内复用长连接（避免万人级演示把本机临时端口耗尽）"""
    for attempt in range(2):
        conn = getattr(_tls, "conn", None)
        if conn is None:
            conn = http.client.HTTPConnection(HOST, PORT, timeout=90)
            _tls.conn = conn
        try:
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = token
            conn.request(method, path,
                         body=json.dumps(body) if body is not None else None,
                         headers=headers)
            resp = conn.getresponse()
            raw = resp.read()
            if resp.will_close:
                conn.close()
                _tls.conn = None
            return json.loads(raw.decode())
        except Exception as e:
            try:
                conn.close()
            except Exception:
                pass
            _tls.conn = None
            if attempt == 1:
                return {"code": -1, "message": str(e)}
            time.sleep(0.02)
    return {"code": -1, "message": "request failed"}


def login(account):
    pl = _request("POST", "/api/auth/login", body={"account": account, "password": PASSWORD})
    return pl["data"]["accessToken"] if pl.get("code") == 0 else None


def api(method, path, token, body=None):
    return _request(method, path, token=token, body=body)


def gen_accounts(n):
    out = []
    for year in (2023, 2024, 2025, 2026):
        for i in range(1, 2501):
            out.append(f"{year}{i:04d}")
            if len(out) >= n:
                return out
    return out


def conflicts(my_courses, slot):
    for c in my_courses:
        if c.get("classId") == slot["classId"]:
            return True
        if c.get("weekDay") == slot.get("weekDay") \
                and (c.get("startSection") or 0) <= (slot.get("endSection") or 0) \
                and (slot.get("startSection") or 0) <= (c.get("endSection") or 0):
            return True
    return False


def pct(sorted_list, p):
    if not sorted_list:
        return 0
    idx = max(0, min(len(sorted_list) - 1, int(round(p * len(sorted_list))) - 1))
    return sorted_list[idx]


def build_second_plan(total, duration, rng):
    """先升后降的钟形曲线 + 随机抖动，归一化到 total（每秒人数不同）。"""
    raw = []
    for i in range(duration):
        base = max(0.15, math.sin(math.pi * (i + 0.5) / duration))
        raw.append(base * rng.uniform(0.55, 1.45))
    s = sum(raw)
    plan = [int(round(x / s * total)) for x in raw]
    diff = total - sum(plan)
    # 用小数余量最大的秒补齐差额
    order = sorted(range(duration), key=lambda i: (raw[i] / s * total) - plan[i], reverse=True)
    i = 0
    while diff != 0:
        idx = order[i % duration]
        step = 1 if diff > 0 else -1
        if plan[idx] + step >= 0:
            plan[idx] += step
            diff -= step
        i += 1
    return plan


def main():
    ap = argparse.ArgumentParser(description="节奏型真实演示：N 人 T 秒 M 人次，每秒人数随机起伏")
    ap.add_argument("--users", type=int, default=2000, help="参与学生数（默认 2000）")
    ap.add_argument("--total", type=int, default=10000, help="选课总人次（默认 10000）")
    ap.add_argument("--duration", type=int, default=20, help="持续时间秒（默认 20）")
    ap.add_argument("--course-ids", default="1,2,3,4,5,6,7,8,9,10",
                    help="参与课程 id，逗号分隔（默认 1..10，即 CS101~CS110）")
    ap.add_argument("--pool", type=int, default=0, help="登录候选池（默认 users×1.1）")
    ap.add_argument("--login-workers", type=int, default=200)
    ap.add_argument("--workers", type=int, default=400, help="发送工作线程（默认 400）")
    ap.add_argument("--seed", type=int, default=20261019)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    rng = random.Random(args.seed)
    pool = args.pool or int(args.users * 1.1)

    observer = login("test")
    if not observer:
        sys.exit("测试账号登录失败，请确认服务已启动")

    # 1) 目标课程/班级槽位（含已满班级，展示真实“已满”）
    slots = []
    for cid in [int(x) for x in args.course_ids.split(",")]:
        classes = api("GET", f"/api/course/{cid}/classes", observer).get("data") or []
        for c in classes:
            slots.append({
                "courseId": cid, "courseName": f"课程{cid}", "classId": c["id"],
                "className": c.get("className"), "weekDay": c.get("weekDay"),
                "startSection": c.get("startSection"), "endSection": c.get("endSection"),
                "beforeSelected": c.get("selected") or 0,
                "beforeAvail": c.get("available") or 0,
            })
    if not slots:
        sys.exit("没有可用教学班")
    print(f"[{ts()}] 目标 {len(slots)} 个教学班，演示前余量合计 = {sum(s['beforeAvail'] for s in slots)}")

    # 2) 人次分配：按“余量 + 基础热度”分到各班（已满班级也有少量尝试）
    weights = [max(s["beforeAvail"], 0) + 20 for s in slots]
    wsum = sum(weights)
    shares = [int(round(args.total * w / wsum)) for w in weights]
    diff = args.total - sum(shares)
    order = sorted(range(len(shares)), key=lambda i: (args.total * weights[i] / wsum) - shares[i], reverse=True)
    i = 0
    while diff != 0:
        idx = order[i % len(shares)]
        step = 1 if diff > 0 else -1
        if shares[idx] + step >= 0:
            shares[idx] += step
            diff -= step
        i += 1
    need = {s["classId"]: n for s, n in zip(slots, shares)}
    slot_by_class = {s["classId"]: s for s in slots}
    print(f"[{ts()}] 人次分配（共 {args.total}）：")
    for s, n in zip(slots, shares):
        if n > 0:
            print(f"[{ts()}]   课程{s['courseId']} {s['className']}（余量 {s['beforeAvail']}）← {n} 人次")

    # 3) 登录学生并体检课表
    accounts = gen_accounts(pool)
    print(f"[{ts()}] 并发登录并体检 {len(accounts)} 个候选学生（并发 {args.login_workers}）…")
    t0 = time.time()
    students = []

    def probe(acc):
        tok = login(acc)
        if not tok:
            return None
        my = api("GET", "/api/course/my", tok).get("data") or []
        return (acc, tok, my)

    with futures.ThreadPoolExecutor(max_workers=args.login_workers) as ex:
        for r in futures.as_completed([ex.submit(probe, a) for a in accounts]):
            r = r.result()
            if r:
                students.append(r)
    print(f"[{ts()}] 可用学生 {len(students)} 人（耗时 {time.time() - t0:.1f}s）")
    if len(students) < args.users:
        sys.exit(f"学生不足：需要 {args.users}，可用 {len(students)}")

    # 4) 分配“谁在什么时候抢哪个班”：多轮公平分配，优先无冲突
    #    每个学生每轮最多领 1 次；优先选“需求最大且无冲突/未选该课程”的班
    assignments = defaultdict(list)   # classId -> [(acc, tok), ...]
    used_pair = set()
    assigned_by_student = defaultdict(list)   # acc -> [slot, ...] 本人本次已分配的班（用于避开与"自己"撞课）
    rounds = 0
    remaining_total = sum(need.values())
    students = students[:args.users]
    while remaining_total > 0 and rounds < args.users * 2:
        progress = 0
        for acc, tok, my in students:
            if remaining_total <= 0:
                break
            mine = assigned_by_student[acc]
            my_course_ids = {c.get("courseId") for c in my} | {s["courseId"] for s in mine}
            blocked = my + mine          # 已有课程 + 本次已分配的课程，统一做时间冲突检查
            candidates = []
            for s in slots:
                key = (acc, s["classId"])
                if need[s["classId"]] <= 0 or key in used_pair:
                    continue
                if s["courseId"] in my_course_ids:
                    continue
                if conflicts(blocked, s):
                    continue
                candidates.append(s)
            if not candidates:
                continue
            best = max(candidates, key=lambda s: need[s["classId"]])
            key = (acc, best["classId"])
            used_pair.add(key)
            assignments[best["classId"]].append((acc, tok))
            assigned_by_student[acc].append(best)
            need[best["classId"]] -= 1
            remaining_total -= 1
            progress += 1
        rounds += 1
        if progress == 0:
            break
    # 兜底：仍有人次未分配（如时间冲突过多）→ 忽略冲突再补，真实用户也可能“撞了再试”
    if remaining_total > 0:
        print(f"[{ts()}] ⚠ 无冲突分配后仍剩 {remaining_total} 人次，按“忽略冲突”补足（这些请求落库可能被拒）")
        for acc, tok, my in students:
            if remaining_total <= 0:
                break
            for s in slots:
                key = (acc, s["classId"])
                if need[s["classId"]] <= 0 or key in used_pair:
                    continue
                used_pair.add(key)
                assignments[s["classId"]].append((acc, tok))
                need[s["classId"]] -= 1
                remaining_total -= 1
                break
    if remaining_total > 0:
        sys.exit(f"仍有 {remaining_total} 人次无法分配（请增加学生池）")
    print(f"[{ts()}] 分配完成：{sum(len(v) for v in assignments.values())} 人次，"
          f"涉及 {len({a for v in assignments.values() for a, _ in v})} 名学生")

    # 5) 生成“第几秒 + 秒内随机偏移”的时间表
    plan = build_second_plan(args.total, args.duration, rng)
    print(f"[{ts()}] 每秒计划（人次）：{plan}（合计 {sum(plan)}）")
    flat = []
    for classId, items in assignments.items():
        for item in items:
            flat.append((classId, item))
    rng.shuffle(flat)
    events = []
    k = 0
    for sec, n in enumerate(plan):
        for _ in range(n):
            if k >= len(flat):
                break
            events.append((sec + rng.random(), flat[k]))
            k += 1
    events.sort(key=lambda x: x[0])
    # 保证所有人次都被排进时间表（计划总和 = total）
    if k < len(flat):
        last = args.duration
        for item in flat[k:]:
            events.append((last + rng.random() * 0.1, item))
        events.sort(key=lambda x: x[0])
    if args.dry_run:
        print(f"[{ts()}] dry-run：不发送请求")
        return

    # 6) 按时间表发送（线程池 + 长连接）
    codes = Counter()
    per_sec = defaultdict(Counter)
    lat_all = []
    successes = []
    lock = threading.Lock()

    def fire(sec, classId, acc, tok):
        t = time.perf_counter()
        pl = api("POST", "/api/course/select", tok, {"classId": classId})
        ms = (time.perf_counter() - t) * 1000
        code = pl.get("code")
        with lock:
            codes[code] += 1
            per_sec[sec][code] += 1
            lat_all.append(ms)
            if code == 0:
                successes.append((acc, tok, classId))

    print(f"[{ts()}] ===== 开始：{args.users} 名学生 / {args.duration}s / {args.total} 人次 =====")
    exec_pool = futures.ThreadPoolExecutor(max_workers=args.workers)
    start = time.perf_counter()
    dispatched = 0
    for ev in events:
        target = start + ev[0]
        delay = target - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        classId, (acc, tok) = ev[1]
        exec_pool.submit(fire, int(ev[0]), classId, acc, tok)
        dispatched += 1
    exec_pool.shutdown(wait=True)
    wall = time.perf_counter() - start
    lat_all.sort()
    print(f"[{ts()}] 发送完成：{dispatched} 人次 / {wall:.1f}s｜"
          f"P50 {pct(lat_all, .5):.0f}ms / P95 {pct(lat_all, .95):.0f}ms / P99 {pct(lat_all, .99):.0f}ms")
    print(f"[{ts()}] 返回码分布：{dict(codes)}")
    print(f"[{ts()}] 每秒完成情况（秒: 总/成功/满员/其他）：")
    for sec in range(args.duration + 1):
        if sec not in per_sec:
            continue
        c = per_sec[sec]
        ok = c.get(0, 0)
        full = c.get(40052, 0)
        other = sum(v for kk, v in c.items() if kk not in (0, 40052))
        print(f"[{ts()}]   {sec:>2}s: {sum(c.values()):>4} / {ok:>4} / {full:>4} / {other:>3}")

    # 7) 落库收敛（按班核对受理成功数）
    ok_by_class = Counter(cid for _, _, cid in successes)
    print(f"[{ts()}] 等待落库收敛（最多 90s）…")
    conv0 = time.time()
    final = {}
    prev_sig, last_change = None, time.time()
    while time.time() - conv0 < 90:
        final = {}
        done_all = True
        for s in slots:
            lst = api("GET", f"/api/course/{s['courseId']}/classes", observer).get("data") or []
            c = next((x for x in lst if x.get("id") == s["classId"]), None)
            sel = (c or {}).get("selected", s["beforeSelected"])
            final[s["classId"]] = sel
            if sel < s["beforeSelected"] + ok_by_class.get(s["classId"], 0):
                done_all = False
        sig = tuple(sorted(final.items()))
        if done_all:
            break
        if sig != prev_sig:
            prev_sig, last_change = sig, time.time()
        elif time.time() - last_change > 3:
            break
        time.sleep(0.5)
    print(f"[{ts()}] 落库收敛耗时 ≈ {(time.time() - conv0) * 1000:.0f}ms")
    for s in slots:
        got = final.get(s["classId"])
        ok = ok_by_class.get(s["classId"], 0)
        if ok or got != s["beforeSelected"]:
            print(f"[{ts()}]   课程{s['courseId']} {s['className']}：selected {s['beforeSelected']} → {got}（受理 {ok}）")

    if args.keep:
        print(f"[{ts()}] --keep：保留选课，未清理")
        return

    # 8) 清理
    print(f"[{ts()}] 清理：{len(successes)} 名学生的选课并发退课…")
    ok_drop = [0]
    dw = threading.Lock()

    def drop(item):
        acc, tok, classId = item
        pl = api("POST", "/api/course/drop", tok, {"classId": classId})
        if pl.get("code") == 0:
            with dw:
                ok_drop[0] += 1

    with futures.ThreadPoolExecutor(max_workers=64) as ex:
        list(ex.map(drop, successes))
    conv0 = time.time()
    while time.time() - conv0 < 90:
        ok = True
        for s in slots:
            lst = api("GET", f"/api/course/{s['courseId']}/classes", observer).get("data") or []
            c = next((x for x in lst if x.get("id") == s["classId"]), None)
            if (c or {}).get("selected", 0) > s["beforeSelected"]:
                ok = False
        if ok:
            break
        time.sleep(0.5)
    print(f"[{ts()}] 清理完成：退课 {ok_drop[0]}/{len(successes)}"
          f"（未成功的为落库校验已拒绝并自动退座，无需再退），各班已恢复演示前状态")


if __name__ == "__main__":
    main()
