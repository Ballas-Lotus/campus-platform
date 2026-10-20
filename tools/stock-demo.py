#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 20/21 · 读体验演示：模拟“其他同学”选课/退课，驱动测试用户页面的实时变化。

原理：
  演示学生经网关调用真实的 /api/course/select 与 /api/course/drop →
  服务端在 Redis 受理并 PUBLISH 名额信号 → SSE 推到你打开的页面 →
  教学班抽屉里的“已选/余量”数字立刻变化（差值高亮），无需刷新。

用法（在校园平台项目根目录）：
  python tools/stock-demo.py --course 147 --class-id 7221                 # 默认 6 选 / 6 退，间隔 2s
  python tools/stock-demo.py --course 147 --class-id 7221 --selects 10 --drops 10 --interval 1.5
  python tools/stock-demo.py --course 147 --class-id 7221 --dry-run       # 只体检候选学生，不选课

说明：
  * 只使用种子学生账号（2023xxxx），绝不会动 test 账号；
  * 逐人先查“我的课表”自算时间冲突，自动跳过冲突/已选该班的学生；
  * 结束时自动退掉本演示产生的所有选课，恢复原状（中途 Ctrl+C 也会尽力清理）。
"""
import argparse
import http.client
import json
import sys
import time
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
    body = json.dumps({"account": account, "password": PASSWORD})
    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
    try:
        conn.request("POST", "/api/auth/login", body=body,
                     headers={"Content-Type": "application/json"})
        pl = json.loads(conn.getresponse().read().decode())
        if pl.get("code") == 0:
            return pl["data"]["accessToken"]
        return None
    except Exception:
        return None
    finally:
        conn.close()


def api(method, path, token, body=None):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    try:
        conn.request(method, path,
                     body=json.dumps(body) if body is not None else None,
                     headers={"Authorization": token, "Content-Type": "application/json"})
        return json.loads(conn.getresponse().read().decode())
    except Exception as e:
        return {"code": -1, "message": str(e)}
    finally:
        conn.close()


def stock(token, course_id):
    pl = api("GET", f"/api/course/{course_id}/stock", token)
    return (pl.get("data") or {}) if pl.get("code") == 0 else {}


def remain_of(token, course_id, class_id):
    for c in stock(token, course_id).get("classes") or []:
        if c.get("id") == class_id:
            return c.get("remain")
    return None


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
            return True   # 已经选过该班
        if c.get("weekDay") == cls.get("weekDay") \
                and (c.get("startSection") or 0) <= (cls.get("endSection") or 0) \
                and (cls.get("startSection") or 0) <= (c.get("endSection") or 0):
            return True   # 时间冲突
    return False


def main():
    ap = argparse.ArgumentParser(description="模拟其他同学选课/退课，演示测试用户页面的实时变化")
    ap.add_argument("--course", type=int, default=147, help="课程 id（默认 147）")
    ap.add_argument("--class-id", type=int, default=7221, help="教学班 id（默认 7221）")
    ap.add_argument("--selects", type=int, default=6, help="演示选课次数（默认 6）")
    ap.add_argument("--drops", type=int, default=6, help="演示退课次数（默认 6）")
    ap.add_argument("--interval", type=float, default=2.0, help="每步间隔秒数（默认 2）")
    ap.add_argument("--max-candidates", type=int, default=40, help="最多体检多少候选学生（默认 40）")
    ap.add_argument("--dry-run", action="store_true", help="只体检候选学生，不做任何写操作")
    args = ap.parse_args()

    observer = login("test")
    if not observer:
        sys.exit("测试账号登录失败，请确认网关与账号服务已启动")
    classes = api("GET", f"/api/course/{args.course}/classes", observer).get("data") or []
    cls = next((c for c in classes if c.get("id") == args.class_id), None)
    if not cls:
        sys.exit(f"课程 {args.course} 下找不到教学班 {args.class_id}")
    print(f"[{ts()}] 目标：《{cls.get('className')}》课程 {args.course} · 教学班 {args.class_id}"
          f"（{cls.get('weekDay')} 第{cls.get('startSection')}-{cls.get('endSection')}节，"
          f"名额 {cls.get('selected')}/{cls.get('capacity')}）")
    print(f"[{ts()}] 演示前余量 = {remain_of(observer, args.course, args.class_id)}")

    accounts = gen_accounts(args.max_candidates)
    chosen = []          # [(account, token)]
    print(f"[{ts()}] 体检候选学生（最多 {args.max_candidates} 人）…")
    seen_ok = 0
    for acc in accounts:
        tok = login(acc)
        if not tok:
            continue
        my = api("GET", "/api/course/my", tok).get("data") or []
        if has_conflict(my, cls):
            continue
        chosen.append((acc, tok))
        seen_ok += 1
        print(f"[{ts()}]   ✓ {acc} 可选（无冲突）")
        if seen_ok >= max(args.selects, 1):
            break
    print(f"[{ts()}] 可用候选 {len(chosen)} 人")
    if args.dry_run:
        return
    if len(chosen) < args.selects:
        sys.exit(f"可用候选不足：需要 {args.selects}，只有 {len(chosen)}")

    picked = chosen[:args.selects]
    selected = []        # 已完成选择的 [(account, token)]
    try:
        print(f"\n[{ts()}] ===== 阶段 1：其他同学陆续选课（观察页面余量 -1）=====")
        for i, (acc, tok) in enumerate(picked):
            pl = api("POST", "/api/course/select", tok, {"classId": args.class_id})
            code = pl.get("code")
            d = pl.get("data") or {}
            if code == 0 and d.get("accepted"):
                selected.append((acc, tok))
                print(f"[{ts()}] 学生 {acc} 选课 → 受理成功（服务端余量 {d.get('remain')}，v={d.get('version')}）")
            else:
                print(f"[{ts()}] 学生 {acc} 选课 → code={code} {pl.get('message')}（跳过）")
            if i < len(picked) - 1:
                time.sleep(args.interval)
        time.sleep(1.0)   # 等最后一位落库

        print(f"\n[{ts()}] ===== 阶段 2：这些同学陆续退课（观察页面余量 +1）=====")
        drops = min(args.drops, len(selected))
        for i, (acc, tok) in enumerate(selected[:drops]):
            pl = api("POST", "/api/course/drop", tok, {"classId": args.class_id})
            if pl.get("code") == 0:
                selected.remove((acc, tok))
                print(f"[{ts()}] 学生 {acc} 退课 → 成功（服务端余量 {remain_of(observer, args.course, args.class_id)}）")
            else:
                print(f"[{ts()}] 学生 {acc} 退课 → code={pl.get('code')} {pl.get('message')}")
            if i < drops - 1:
                time.sleep(args.interval)
    finally:
        if selected:
            print(f"\n[{ts()}] 清理：退掉剩余 {len(selected)} 位演示学生的选课…")
            for acc, tok in selected:
                pl = api("POST", "/api/course/drop", tok, {"classId": args.class_id})
                print(f"[{ts()}]   清理 {acc} → {'OK' if pl.get('code') == 0 else pl.get('message')}")
        final = remain_of(observer, args.course, args.class_id)
        print(f"\n[{ts()}] 演示结束：最终余量 = {final}（应恢复到演示前）")


if __name__ == "__main__":
    main()
