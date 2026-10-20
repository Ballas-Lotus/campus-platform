#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 21.2 · 多课程分散齐射演示：1000 名学生同时抢不同的课（可就地观察课程列表“名额情况”实时变化）。

与 burst-demo.py 的差别：
  * 按“课程代码:人数”计划把学生分散到多门课（自动分配到各教学班，并校验余量）；
  * 每个学生先体检「我的课表」，自动过滤与其目标班时间冲突/已选该班者；
  * 所有学生**同时开火**（不是逐门课串行），结束后默认自动退课复原，并逐班核对收敛。

用法（项目根目录）：
  python tools/multi-burst-demo.py --plan "CS107:300,CS109:123,CS105:200,CS103:280,CS106:97"
  python tools/multi-burst-demo.py --plan "CS107:30,CS109:20" --pool 200 --dry-run
  python tools/multi-burst-demo.py --plan "..." --keep        # 保留选课，不自动退课

前置：服务已启动；若刚重启，放开限流：POST /api/stress/sentinel?qps=100000
"""
import argparse
import http.client
import json
import queue
import sys
import threading
import time
import urllib.parse
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


def ts():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


# 每个工作线程复用自己的 HTTP 长连接——万人级演示若"每请求新建连接"，
# Windows 的临时端口（约 1.6 万）会被 TIME_WAIT 占满，导致后续请求在客户端就失败。
_tls = threading.local()


def _request(method, path, token=None, body=None):
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


def api(method, path, token, body=None, timeout=30):
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


def resolve_course(token, code):
    q = urllib.parse.quote(code)
    lst = api("GET", f"/api/course/list?keyword={q}&pageNum=1&pageSize=5", token).get("data", {}).get("list") or []
    for c in lst:
        if str(c.get("courseCode")) == code:
            return c
    return None


def main():
    ap = argparse.ArgumentParser(description="多课程分散齐射（1000 人各抢各的课，同时开火）")
    ap.add_argument("--plan", required=True, help='课程代码:人数，逗号分隔，如 "CS107:300,CS109:123"')
    ap.add_argument("--pool", type=int, default=0, help="登录候选池（默认 计划人数 ×1.4，上限 10000）")
    ap.add_argument("--login-workers", type=int, default=128)
    ap.add_argument("--workers", type=int, default=600, help="齐射工作线程数（默认 600）")
    ap.add_argument("--contend", action="store_true",
                    help="争抢模式：分配人数可远超余量（按余量占比分到各班），制造“部分人抢到”的效果")
    ap.add_argument("--dry-run", action="store_true", help="只体检候选与分配，不开火")
    ap.add_argument("--keep", action="store_true", help="保留本次选课（不自动退课清理）")
    args = ap.parse_args()

    plan = []
    for part in args.plan.split(","):
        code, n = part.split(":")
        plan.append((code.strip(), int(n)))
    total_want = sum(n for _, n in plan)
    pool = args.pool or int(total_want * 1.4)

    observer = login("test")
    if not observer:
        sys.exit("测试账号登录失败，请确认服务已启动")

    # 1) 解析课程 → 教学班槽位（按余量分配人数）
    slots = []
    print(f"[{ts()}] 计划：{total_want} 人分散抢 {len(plan)} 门课")
    for code, want in plan:
        course = resolve_course(observer, code)
        if not course:
            sys.exit(f"找不到课程 {code}")
        classes = api("GET", f"/api/course/{course['id']}/classes", observer).get("data") or []
        avail = [c for c in classes if (c.get("available") or 0) > 0] or classes
        total_avail = sum(c.get("available") or 0 for c in avail)
        allocs = []
        if args.contend:
            # 争抢模式：按当前余量占比把需求分到各班（需求可远超余量；优先分给有空位的班）
            if total_avail > 0:
                acc = 0
                for i, c in enumerate(avail):
                    if i == len(avail) - 1:
                        n = want - acc
                    else:
                        n = int(round(want * (c.get("available") or 0) / total_avail))
                    allocs.append(n)
                    acc += n
            else:
                allocs = [0] * len(avail)
                allocs[0] = want
        else:
            take = min(want, total_avail)
            if take < want:
                print(f"[{ts()}] ⚠ {code} 余量不足：计划 {want}，可容纳 {take}")
            need = take
            for c in avail:
                n = min(need, c.get("available") or 0)
                allocs.append(n)
                need -= n
                if need <= 0:
                    break
            allocs += [0] * (len(avail) - len(allocs))
        for c, n in zip(avail, allocs):
            if n <= 0:
                continue
            slots.append({
                "code": code, "courseId": course["id"], "courseName": course.get("name"),
                "classId": c["id"], "className": c.get("className"),
                "weekDay": c.get("weekDay"), "startSection": c.get("startSection"),
                "endSection": c.get("endSection"),
                "beforeSelected": c.get("selected") or 0,
                "beforeAvail": c.get("available") or 0,
                "need": n, "assigned": [],
            })
    if args.contend:
        print(f"[{ts()}] 争抢模式：分配人数可超过余量（按各班余量占比），预计只有余量数的人能抢到")
    planned = sum(s["need"] for s in slots)
    for s in slots:
        print(f"[{ts()}]   {s['code']} {s['className']}（教学班 {s['classId']}，"
              f"周{s['weekDay']} 第{s['startSection']}-{s['endSection']}节）分配 {s['need']} 人，"
              f"当前余量 {s['beforeAvail']}")
    if planned < total_want:
        print(f"[{ts()}] ⚠ 实际可分配 {planned} 人（计划 {total_want}）")

    # 2) 并发体检候选学生
    accounts = gen_accounts(pool)
    print(f"[{ts()}] 并发登录并体检 {len(accounts)} 个候选学生（并发 {args.login_workers}）…")
    t0 = time.time()
    candidates = []

    def probe(acc):
        tok = login(acc)
        if not tok:
            return None
        my = api("GET", "/api/course/my", tok).get("data") or []
        return acc, tok, my

    with futures.ThreadPoolExecutor(max_workers=args.login_workers) as ex:
        for r in futures.as_completed([ex.submit(probe, a) for a in accounts]):
            r = r.result()
            if r:
                candidates.append(r)
    print(f"[{ts()}] 候选学生 {len(candidates)} 人（耗时 {time.time() - t0:.1f}s），开始分配…")

    # 3) 分配：第一轮只派“无冲突”的学生；争抢模式下第二轮用剩余学生补齐需求
    used = set()
    clean = 0
    for acc, tok, my in candidates:
        if all(s["need"] == 0 for s in slots):
            break
        best = None
        for s in slots:
            if s["need"] > 0 and not conflicts(my, s):
                if best is None or s["need"] > best["need"]:
                    best = s
        if best:
            best["need"] -= 1
            best["assigned"].append((acc, tok))
            used.add(acc)
            clean += 1
    fallback = 0
    if args.contend:
        for acc, tok, my in candidates:
            if all(s["need"] == 0 for s in slots):
                break
            if acc in used:
                continue
            best = max((s for s in slots if s["need"] > 0), key=lambda s: s["need"], default=None)
            if best:
                best["need"] -= 1
                best["assigned"].append((acc, tok))
                used.add(acc)
                fallback += 1
    unfilled = sum(s["need"] for s in slots)
    if unfilled:
        if args.contend:
            print(f"[{ts()}] ⚠ 仍有 {unfilled} 个分配名额未凑满学生（将少发对应请求）")
        else:
            sys.exit(f"可用学生不足：还有 {unfilled} 个名额无人可去，请增大 --pool 或调整计划（未发生任何写操作）")
    print(f"[{ts()}] 分配完成：无冲突 {clean} 人" + (f" + 兜底 {fallback} 人（有冲突，可能落库被拒）" if fallback else ""))
    for s in slots:
        print(f"[{ts()}]   {s['code']} {s['className']} ← {len(s['assigned'])} 人"
              f"（当前余量 {s['beforeAvail']}）")
    if args.dry_run:
        return

    # 4) 同时开火（工作线程从队列取任务，1 万请求不需要 1 万 Python 线程）
    lock = threading.Lock()
    gate = threading.Event()
    tasks = queue.Queue()
    total_tasks = 0
    for s in slots:
        for acc, tok in s["assigned"]:
            tasks.put((s, acc, tok))
            total_tasks += 1
    lat_all = []
    results = {}          # classId -> {code: count}
    successes = []        # (acc, tok, classId)

    def fire_one(slot, acc, tok):
        t = time.perf_counter()
        pl = api("POST", "/api/course/select", tok, {"classId": slot["classId"]}, timeout=90)
        ms = (time.perf_counter() - t) * 1000
        code = pl.get("code")
        with lock:
            d = results.setdefault(slot["classId"], {})
            d[code] = d.get(code, 0) + 1
            lat_all.append(ms)
            if code == 0:
                successes.append((acc, tok, slot["classId"]))

    def worker():
        gate.wait()
        while True:
            try:
                s, acc, tok = tasks.get_nowait()
            except queue.Empty:
                return
            fire_one(s, acc, tok)

    workers = min(args.workers, max(total_tasks, 1))
    print(f"\n[{ts()}] ===== 齐射：{total_tasks} 人同时开火（分散在 {len(slots)} 个教学班，"
          f"{workers} 个工作线程）=====")
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(workers)]
    for th in threads:
        th.start()
    t_fire = time.time()
    time.sleep(0.3)
    gate.set()
    for th in threads:
        th.join(300)
    wall = (time.time() - t_fire) * 1000
    lat_all.sort()
    print(f"[{ts()}] 齐射完成：{total_tasks} 请求 / {wall:.0f}ms｜"
          f"P50 {pct(lat_all, .5):.0f}ms / P95 {pct(lat_all, .95):.0f}ms / P99 {pct(lat_all, .99):.0f}ms")
    total_ok = sum(d.get(0, 0) for d in results.values())
    flat = {}
    for d in results.values():
        for code, n in d.items():
            flat[code] = flat.get(code, 0) + n
    print(f"[{ts()}] 受理成功合计：{total_ok}/{total_tasks}（按班：{ {k: v.get(0, 0) for k, v in results.items()} }）")
    print(f"[{ts()}] 返回码分布：{flat}")

    # 5) 等待落库收敛并逐班核对
    print(f"[{ts()}] 等待落库收敛（最多 90s）…")
    conv0 = time.time()
    final = {}
    prev_sig = None
    last_change = time.time()
    while time.time() - conv0 < 90:
        final = {}
        done_all = True
        for s in slots:
            lst = api("GET", f"/api/course/{s['courseId']}/classes", observer).get("data") or []
            c = next((x for x in lst if x.get("id") == s["classId"]), None)
            sel = (c or {}).get("selected", s["beforeSelected"])
            final[s["classId"]] = sel
            ok_cnt = results.get(s["classId"], {}).get(0, 0)
            if sel < s["beforeSelected"] + ok_cnt:
                done_all = False
        sig = tuple(sorted(final.items()))
        if done_all:
            break
        if sig != prev_sig:
            prev_sig = sig
            last_change = time.time()
        elif time.time() - last_change > 3.0:
            break   # 连续 3 秒无变化 → 视为收敛（可能有补偿/迟滞）
        time.sleep(0.5)
    print(f"[{ts()}] 落库收敛耗时 ≈ {(time.time() - conv0) * 1000:.0f}ms")
    for s in slots:
        got = final.get(s["classId"])
        ok_cnt = results.get(s["classId"], {}).get(0, 0)
        print(f"[{ts()}]   {s['code']} {s['className']}：selected {s['beforeSelected']} → {got}"
              f"（受理成功 {ok_cnt}，目标 {s['beforeSelected'] + ok_cnt}）")

    if args.keep:
        print(f"[{ts()}] --keep：保留选课，未清理")
        return

    # 6) 清理：并发退课恢复
    print(f"[{ts()}] 清理：{len(successes)} 名学生并发退课…")
    ok_drop = [0]
    dw = threading.Lock()

    def drop(item):
        acc, tok, classId = item
        pl = api("POST", "/api/course/drop", tok, {"classId": classId}, timeout=60)
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
    print(f"[{ts()}] 清理完成：退课 {ok_drop[0]}/{len(successes)}，各教学班已恢复到演示前状态")


if __name__ == "__main__":
    main()
