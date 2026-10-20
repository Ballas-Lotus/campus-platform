#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 21.1 · 高并发“余量充足”演示：N 个真实学生经网关同时选同一个教学班（默认 1000 人）。

与 loadtest.py verify 的差别：
  * 面向“容量充足、全员成功”的演示（默认目标：课程 9999 的 9998 千人演示班，容量 1500）；
  * 先并发体检候选学生的「我的课表」，自动过滤时间冲突/已选该班者，保证齐射成功率；
  * 结束后默认自动退掉本次产生的选课（恢复余量），并用数据库核对收敛。

用法（项目根目录）：
  python tools/burst-demo.py                       # 1000 人，打 9998（容量 1500）
  python tools/burst-demo.py --users 300 --dry-run # 只体检候选与容量，不开火
  python tools/burst-demo.py --users 1000 --keep   # 开火后保留选课（不自动退课）

前置：压测班课程数据已就绪（见 resources/database/migrate-stress-fixtures.sql 第 4 节）；
      若服务刚重启，先放开限流：POST /api/stress/sentinel?qps=100000
"""
import argparse
import http.client
import json
import sys
import threading
import time
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


def login(account):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    try:
        conn.request("POST", "/api/auth/login",
                     body=json.dumps({"account": account, "password": PASSWORD}),
                     headers={"Content-Type": "application/json"})
        pl = json.loads(conn.getresponse().read().decode())
        return pl["data"]["accessToken"] if pl.get("code") == 0 else None
    except Exception:
        return None
    finally:
        conn.close()


def api(method, path, token, body=None, timeout=30):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    try:
        conn.request(method, path,
                     body=json.dumps(body) if body is not None else None,
                     headers={"Authorization": token, "Content-Type": "application/json"})
        return json.loads(conn.getresponse().read().decode())
    except Exception as e:
        return {"code": -1, "message": str(e)}
    finally:
        conn.close()


def gen_accounts(n):
    out = []
    for year in (2023, 2024, 2025, 2026):
        for i in range(1, 2501):
            out.append(f"{year}{i:04d}")
            if len(out) >= n:
                return out
    return out


def has_conflict(my_courses, cls):
    for c in my_courses:
        if c.get("classId") == cls["id"]:
            return True
        if c.get("weekDay") == cls.get("weekDay") \
                and (c.get("startSection") or 0) <= (cls.get("endSection") or 0) \
                and (cls.get("startSection") or 0) <= (c.get("endSection") or 0):
            return True
    return False


def pct(sorted_list, p):
    if not sorted_list:
        return 0
    idx = max(0, min(len(sorted_list) - 1, int(round(p * len(sorted_list))) - 1))
    return sorted_list[idx]


def main():
    ap = argparse.ArgumentParser(description="N 个真实学生并发选同一个教学班（容量充足场景）")
    ap.add_argument("--course", type=int, default=9999)
    ap.add_argument("--class-id", type=int, default=9998)
    ap.add_argument("--users", type=int, default=1000, help="参与齐射的学生数（默认 1000）")
    ap.add_argument("--pool", type=int, default=0, help="登录候选池大小（默认 users×1.3）")
    ap.add_argument("--login-workers", type=int, default=128)
    ap.add_argument("--dry-run", action="store_true", help="只体检候选与容量，不开火")
    ap.add_argument("--keep", action="store_true", help="保留本次选课（不自动退课清理）")
    args = ap.parse_args()
    pool = args.pool or int(args.users * 1.3)

    observer = login("test")
    if not observer:
        sys.exit("测试账号登录失败，请确认服务已启动")
    classes = api("GET", f"/api/course/{args.course}/classes", observer).get("data") or []
    cls = next((c for c in classes if c.get("id") == args.class_id), None)
    if not cls:
        sys.exit(f"课程 {args.course} 下找不到教学班 {args.class_id}（先执行夹具并清缓存）")
    before_sel = cls.get("selected") or 0
    capacity = cls.get("capacity") or 0
    before_remain = cls.get("available")
    print(f"[{ts()}] 目标：《{cls.get('className')}》教学班 {args.class_id}｜容量 {capacity}｜"
          f"当前已选 {before_sel}｜余量 {before_remain}")
    if capacity - before_sel < args.users:
        sys.exit(f"余量不足：需要 {args.users}，当前只有 {capacity - before_sel}")

    accounts = gen_accounts(pool)
    print(f"[{ts()}] 并发登录 {len(accounts)} 个候选学生（并发 {args.login_workers}）…")
    t0 = time.time()
    eligible = []

    def probe(acc):
        tok = login(acc)
        if not tok:
            return None
        my = api("GET", "/api/course/my", tok).get("data") or []
        if has_conflict(my, cls):
            return None
        return acc, tok

    with futures.ThreadPoolExecutor(max_workers=args.login_workers) as ex:
        futs = [ex.submit(probe, a) for a in accounts]
        for f in futures.as_completed(futs):
            r = f.result()
            if r:
                eligible.append(r)
            if len(eligible) >= args.users:
                break
    print(f"[{ts()}] 可用无冲突学生 {len(eligible)} 人（耗时 {time.time() - t0:.1f}s）")
    if args.dry_run:
        return
    if len(eligible) < args.users:
        sys.exit(f"无冲突学生不足：需要 {args.users}，只有 {len(eligible)}")

    picked = eligible[:args.users]
    codes = {}
    lat_all = []
    successes = []          # (account, token) 受理成功的，供清理阶段退课
    lock = threading.Lock()
    gate = threading.Event()
    done = threading.Event()
    pending = [len(picked)]

    def fire(item):
        acc, tok = item
        gate.wait()
        t = time.perf_counter()
        pl = api("POST", "/api/course/select", tok, {"classId": args.class_id}, timeout=90)
        ms = (time.perf_counter() - t) * 1000
        code = pl.get("code")
        with lock:
            codes[code] = codes.get(code, 0) + 1
            lat_all.append(ms)
            if code == 0:
                successes.append((acc, tok))
            pending[0] -= 1
            if pending[0] == 0:
                done.set()

    print(f"[{ts()}] ===== 齐射：{len(picked)} 名学生同时开火 =====")
    threads = [threading.Thread(target=fire, args=(it,), daemon=True) for it in picked]
    for th in threads:
        th.start()
    t_fire = time.time()
    time.sleep(0.3)
    gate.set()
    done.wait(120)
    wall = (time.time() - t_fire) * 1000
    lat_all.sort()
    print(f"[{ts()}] 齐射完成：{len(picked)} 请求 / {wall:.0f}ms｜"
          f"P50 {pct(lat_all, .5):.0f}ms / P95 {pct(lat_all, .95):.0f}ms / P99 {pct(lat_all, .99):.0f}ms")
    print(f"[{ts()}] 返回码分布：{codes}")

    # 等待落库收敛（selected 增量 = 成功数）
    ok = codes.get(0, 0)
    print(f"[{ts()}] 等待落库收敛（最多 60s）…")
    target = before_sel + ok
    conv0 = time.time()
    selected = before_sel
    while time.time() - conv0 < 60:
        lst = api("GET", f"/api/course/{args.course}/classes", observer).get("data") or []
        c = next((x for x in lst if x.get("id") == args.class_id), None)
        selected = (c or {}).get("selected", before_sel)
        if selected >= target:
            break
        time.sleep(0.5)
    conv_ms = (time.time() - conv0) * 1000
    stock = api("GET", f"/api/course/{args.course}/stock", observer).get("data") or {}
    remain = next((x.get("remain") for x in stock.get("classes") or []
                   if x.get("id") == args.class_id), None)
    print(f"[{ts()}] 落库收敛：selected {before_sel} → {selected}（目标 {target}，耗时 {conv_ms:.0f}ms）｜"
          f"结构余量 {remain}")

    if args.keep:
        print(f"[{ts()}] --keep：保留本次选课，未清理")
        return

    # 清理：并发退课，恢复余量（只退受理成功的）
    print(f"[{ts()}] 清理：{len(successes)} 名学生并发退课…")
    ok_drop = [0]
    dw = threading.Lock()

    def drop(item):
        acc, tok = item
        pl = api("POST", "/api/course/drop", tok, {"classId": args.class_id}, timeout=60)
        if pl.get("code") == 0:
            with dw:
                ok_drop[0] += 1

    with futures.ThreadPoolExecutor(max_workers=64) as ex:
        list(ex.map(drop, successes))
    conv0 = time.time()
    while time.time() - conv0 < 60:
        lst = api("GET", f"/api/course/{args.course}/classes", observer).get("data") or []
        c = next((x for x in lst if x.get("id") == args.class_id), None)
        if (c or {}).get("selected", 0) <= before_sel:
            break
        time.sleep(0.5)
    stock = api("GET", f"/api/course/{args.course}/stock", observer).get("data") or {}
    remain = next((x.get("remain") for x in stock.get("classes") or []
                   if x.get("id") == args.class_id), None)
    print(f"[{ts()}] 清理完成：退课 {ok_drop[0]}/{ok}｜最终余量 {remain}（演示前 {before_remain}）")


if __name__ == "__main__":
    main()
