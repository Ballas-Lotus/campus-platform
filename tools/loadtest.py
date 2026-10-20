#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校园综合服务平台 · 并发压测工具（零第三方依赖，Python 3.8+）

两个子命令，对应两种完全不同的测试目标：

  1) read    —— 容量测试：打只读接口，测「QPS 天花板」与延迟分位
               示例：python tools/loadtest.py read --concurrency 50,100,200 --duration 10

  2) verify  —— 端到端正确性测试：N 个真实用户「经网关」并发抢同一门课，
                断言"恰好等于名额数的人成功、0 系统异常"（HTTP 级防超卖验证，
                与前端「⚡ 并发压测」的服务内直调互为补充）；
                并自带**服务端观测台**：齐射前后自动对比 闸门/缓存读模型/限流 的关键增量。
               示例：python tools/loadtest.py verify --users 150
              万人齐射（库里正好 1 万个常规学生，2023~2026 级各 2500 名）：
                ① 先放开限流：POST /api/stress/sentinel?qps=100000
                ② python tools/loadtest.py verify --users 10000
                ③ 看输出里的「服务端观测」段 + 前端「🔥 预扣闸门 / 🚀 缓存」页

口径提醒（读结果前先看这里）：
  * 压测端与被测端同机 → 双方抢 CPU，数字是"下限"；真实容量请换一台机器打。
  * 走网关 = 含鉴权/路由的全程；服务内直调（前端压测页）不含网络，数字偏乐观。
  * 抢课接口有 Sentinel 限流（默认 QPS 20）与 5 秒幂等窗口：容量测试要先调阈值
    （POST /api/stress/sentinel，热更新、无需重启）、或把结果标注为"限流后"；
    verify 子命令会如实报告 40055/40060 的占比。
  * "同时"的物理上限：一万个请求经工作池（默认 ≤800 并发连接）发起，输出里的
    「发起窗口」即全部请求的到达时间跨度——同机单进程做不到严格的纳秒齐射，
    但服务端要观察的东西（闸门挡量/恰好成交/零超卖）与到达窗口无关。
"""
import argparse
import http.client
import json
import queue
import statistics
import sys
import threading
import time
from concurrent import futures
from datetime import date, timedelta

HOST = "127.0.0.1"
PORT = 9080
PASSWORD = "123456"

# Windows 控制台默认 GBK：✓/✗ 等字符无法编码会让"跑完全程"的脚本在最后一行崩溃。
# 只放宽错误处理（替换为 ?），不改编码——中文在 GBK 控制台仍可读。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors="replace")
    except Exception:
        pass


# ----------------------------------------------------------------------
# 基础：单连接 keep-alive 的请求发送
# ----------------------------------------------------------------------
def login(account, password=PASSWORD):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
    try:
        conn.request("POST", "/api/auth/login",
                     body=json.dumps({"account": account, "password": password}),
                     headers={"Content-Type": "application/json"})
        payload = json.loads(conn.getresponse().read().decode())
        if payload.get("code") == 0:
            return payload["data"]["accessToken"]
        return None
    except Exception:
        return None
    finally:
        conn.close()


class Runner(threading.Thread):
    """一个压测线程：独占一条 keep-alive 连接，循环打同一个请求直到 deadline。"""

    def __init__(self, spec, barrier, deadline, latencies, codes, lock):
        super().__init__(daemon=True)
        self.spec = spec
        self.barrier = barrier
        self.deadline = deadline
        self.latencies = latencies
        self.codes = codes
        self.lock = lock

    def run(self):
        conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
        self.barrier.wait()
        while time.time() < self.deadline:
            t0 = time.perf_counter()
            try:
                conn.request(self.spec["method"], self.spec["path"],
                             body=self.spec.get("body"),
                             headers=self.spec["headers"])
                resp = conn.getresponse()
                raw = resp.read()
                ms = (time.perf_counter() - t0) * 1000
                try:
                    code = json.loads(raw.decode()).get("code")
                except Exception:
                    code = "HTTP%d" % resp.status
                with self.lock:
                    self.codes[code] = self.codes.get(code, 0) + 1
                    if code == 0:
                        self.latencies.append(ms)
            except Exception:
                # 连接被打断：重连一次再继续（计入错误）
                with self.lock:
                    self.codes["CONN_ERR"] = self.codes.get("CONN_ERR", 0) + 1
                try:
                    conn.close()
                    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
                except Exception:
                    pass
        conn.close()


def run_load(spec, concurrency, duration, warmup=2):
    print(f"  并发 {concurrency:<5} 预热 {warmup}s / 测量 {duration}s …", end="", flush=True)
    # 预热
    deadline = time.time() + warmup
    _fire(spec, concurrency, deadline)
    # 测量
    latencies, codes, lock = [], {}, threading.Lock()
    t0 = time.time()
    deadline = t0 + duration
    _fire(spec, concurrency, deadline, latencies, codes, lock)
    wall = time.time() - t0

    total = sum(codes.values())
    ok = codes.get(0, 0)
    lat = sorted(latencies)
    qps = total / wall if wall > 0 else 0
    line = (f"\r  并发 {concurrency:<5} 总请求 {total:<7} QPS {qps:>8.1f}  "
            f"成功 {ok:<7} 符合预期成功率 {ok * 100.0 / max(total, 1):>5.1f}%  ")
    if lat:
        line += (f"P50 {pct(lat, .5):>6.1f}ms  P95 {pct(lat, .95):>6.1f}ms  "
                 f"P99 {pct(lat, .99):>6.1f}ms  最大 {lat[-1]:>6.1f}ms")
    print(line)
    others = {k: v for k, v in codes.items() if k != 0}
    if others:
        print(f"           非成功响应分布：{others}")
    return {"concurrency": concurrency, "total": total, "qps": qps, "ok": ok,
            "p50": pct(lat, .5), "p95": pct(lat, .95), "p99": pct(lat, .99), "codes": codes}


def _fire(spec, concurrency, deadline, latencies=None, codes=None, lock=None):
    latencies = latencies if latencies is not None else []
    codes = codes if codes is not None else {}
    lock = lock or threading.Lock()
    barrier = threading.Barrier(concurrency)
    threads = [Runner(spec, barrier, deadline, latencies, codes, lock) for _ in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def pct(sorted_list, p):
    if not sorted_list:
        return 0.0
    idx = min(len(sorted_list) - 1, int(len(sorted_list) * p))
    return sorted_list[idx]


# ----------------------------------------------------------------------
# 子命令 1：容量测试（只读）
# ----------------------------------------------------------------------
DEFAULT_TARGETS = {
    "时段查询(热点key·命中缓存)": lambda: {
        "method": "GET", "path": "/api/resource/10001/slots?date=" + tomorrow()},
    "工作台概览(命中缓存)": lambda: {"method": "GET", "path": "/api/resource/stats/overview"},
    "资源列表(命中缓存)": lambda: {
        "method": "GET", "path": "/api/resource/list?resourceType=VENUE&pageNum=1&pageSize=10"},
}


def cmd_read(args):
    token = login(args.account)
    if not token:
        sys.exit(f"登录失败：{args.account}（请确认账号/密码）")
    targets = DEFAULT_TARGETS
    if args.path:
        targets = {args.path: lambda: {"method": args.method, "path": args.path,
                                       "body": args.body}}
    print(f"容量测试（走网关 {HOST}:{PORT}，压测端与被测端同机）\n")
    summary = []
    for name, builder in targets.items():
        print(f"■ {name}")
        base = builder()
        spec = {"method": base["method"], "path": base["path"],
                "body": base.get("body"),
                "headers": {"Authorization": token, "Content-Type": "application/json"}}
        for c in args.concurrency:
            summary.append((name, run_load(spec, c, args.duration, args.warmup)))
        print()
    return summary


# ----------------------------------------------------------------------
# 子命令 2：端到端正确性（HTTP 级并发抢课 + 服务端观测台）
# ----------------------------------------------------------------------
def gen_accounts(n):
    """按种子数据规律生成学号（2023~2026 级各 2500 名，共 1 万人；当前库正好 1 万学生）。"""
    out = []
    for year in (2023, 2024, 2025, 2026):
        for i in range(1, 2501):
            out.append(f"{year}{i:04d}")
            if len(out) >= n:
                return out
    return out


def api_get(path, token):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    try:
        conn.request("GET", path, headers={"Authorization": token})
        return json.loads(conn.getresponse().read().decode()).get("data")
    except Exception:
        return None
    finally:
        conn.close()


def server_snapshot(token):
    """服务端观测量：受理制指标（阶段 21）+ 预扣闸门 + 缓存读模型 + 限流（跑前跑后各取一次，看增量）。"""
    st = api_get("/api/course/stock/metrics", token) or {}
    pd = (api_get("/api/prededuct/state", token) or {}).get("metrics") or {}
    mm = (api_get("/api/cache/metrics", token) or {}).get("metrics") or {}
    sn = api_get("/api/stress/sentinel", token) or {}
    return {
        "受理·成功受理": st.get("accepted"),
        "受理·满员拒绝": st.get("acceptFull"),
        "受理·幂等拦截": st.get("acceptDup"),
        "受理·已选拒绝": st.get("acceptAlready"),
        "受理·降级同步": st.get("acceptDegraded"),
        "受理·MQ发送失败": st.get("mqFail"),
        "受理·补偿": st.get("compensated"),
        "受理·落库回执": st.get("settledOk"),
        "受理·拒绝回执": st.get("settledReject"),
        "闸门·放行到数据库": pd.get("passed"),
        "闸门·Redis拦截": pd.get("intercepted"),
        "缓存·余量增量": mm.get("stockIncrs"),
        "缓存·读模型重建": mm.get("stockRebuilds"),
        "限流·累计拦截": sn.get("blocked"),
        "限流·当前阈值": sn.get("qps"),
    }


def print_delta(before, after):
    print("\n  ── 服务端观测（齐射前后增量）" + "─" * 28)
    for k in before:
        b, a = before.get(k), after.get(k)
        if isinstance(b, (int, float)) and not isinstance(b, bool) \
                and isinstance(a, (int, float)) and not isinstance(a, bool):
            d = a - b
            shown = f"{d:+g}" if d else "±0"
            print(f"    {k:<14} {b:>10} → {a:>10}   Δ {shown}")
        else:
            print(f"    {k:<14} {b!s:>10} → {a!s:>10}")


# ----------------------------------------------------------------------
# HTML 战报（单文件、内联样式、零依赖——跑完自动生成，可双击打开/截屏/存档）
# ----------------------------------------------------------------------
REPORT_CODE_LABELS = {
    0: "受理成功（ACCEPT 原子通过，异步落库）",
    40024: "资源时段冲突", 40051: "已选过该班（快速已选检查）", 40052: "名额已满（Redis 真相挡回）",
    40053: "时间冲突（落库被拒已补偿）", 40054: "未选课", 40055: "限流快速失败",
    40056: "受理失败（MQ 异常，已回滚）", 40060: "重复提交（幂等拦截）",
}
_LAT_BUCKETS = [(0, 50, "0~50ms"), (50, 200, "50~200ms"), (200, 500, "200~500ms"),
                (500, 1000, "0.5~1s"), (1000, 2000, "1~2s"), (2000, 5000, "2~5s"),
                (5000, 10 ** 9, ">5s")]


def _code_label(k):
    if isinstance(k, int):
        return REPORT_CODE_LABELS.get(k, f"业务错误 {k}")
    s = str(k)
    if s.startswith("CONN_ERR"):
        sub = s.split(":", 1)[-1].replace("Error", "") or "未知"
        return f"连接错误（{sub}）"
    return s


def _delta(before, after, key):
    b, a = before.get(key), after.get(key)
    if isinstance(b, (int, float)) and isinstance(a, (int, float)) and not isinstance(b, bool):
        return a - b
    return 0


def _bar_rows(items, total, color):
    rows = []
    for label, count in items:
        pct = (count * 100.0 / total) if total else 0
        rows.append(
            f'<div class="bar-row"><span class="bar-label">{label}</span>'
            f'<span class="bar-track"><span class="bar-fill" style="width:{pct:.1f}%;background:{color}"></span></span>'
            f'<span class="bar-num">{count}（{pct:.1f}%）</span></div>')
    return "\n".join(rows)


def _checks(meta):
    """阶段 21 受理制口径：受理（Redis 原子）与落库（MQ 异步）两段都要闭合。"""
    codes, cap = meta["codes"], meta["capacity"]
    before, after = meta["snap_before"], meta["snap_after"]
    ok = codes.get(0, 0)
    conn_err = sum(v for k, v in codes.items() if str(k).startswith("CONN_ERR"))
    total = sum(codes.values())
    d_acc = _delta(before, after, "受理·成功受理")
    d_full = _delta(before, after, "受理·满员拒绝")
    d_dup = _delta(before, after, "受理·幂等拦截")
    d_already = _delta(before, after, "受理·已选拒绝")
    d_deg = _delta(before, after, "受理·降级同步")
    d_mq = _delta(before, after, "受理·MQ发送失败")
    d_comp = _delta(before, after, "受理·补偿")
    d_settle_ok = _delta(before, after, "受理·落库回执")
    d_settle_no = _delta(before, after, "受理·拒绝回执")
    sel_after = meta.get("selected_after")
    checks = [
        ("受理成功数 = 名额数（恰好受理）", ok == cap, f"受理成功 {ok} / 容量 {cap}"),
        ("服务端受理计数 = 客户端成功数（双向闭合）", d_acc == ok,
         f"Δ服务端受理 {d_acc} / 客户端受理成功 {ok}"),
        ("拒绝侧算术闭合：满员/幂等/已选/限流 逐一对应",
         d_full == codes.get(40052, 0) and d_dup == codes.get(40060, 0)
         and d_already == codes.get(40051, 0),
         f"服务端 满员{d_full}/幂等{d_dup}/已选{d_already} ｜ 客户端 "
         f"{codes.get(40052, 0)}/{codes.get(40060, 0)}/{codes.get(40051, 0)}"),
        ("零 MQ 失败 / 零降级（受理链路全程在线）", d_mq == 0 and d_deg == 0,
         f"MQ 失败 {d_mq}，降级 {d_deg}"),
        ("零补偿（没有受理被数据库拒绝）", d_comp == 0, f"补偿 {d_comp} 次"),
        ("落库全部收敛（回执数 = 受理数）", d_settle_ok + d_settle_no == d_acc,
         f"回执 {d_settle_ok + d_settle_no}（成功 {d_settle_ok} / 拒绝 {d_settle_no}）/ 受理 {d_acc}"),
        ("落库零超卖（收敛后 selected = 名额数）", sel_after == cap,
         f"selected = {sel_after} / 容量 {cap}"),
        ("无系统异常 / 无连接错误", conn_err == 0, f"连接错误 {conn_err}"),
    ]
    return checks


def write_report(meta, path):
    """把一次 verify 的客户端 + 服务端数据渲染成单文件 HTML 战报。"""
    import os
    os.makedirs(os.path.dirname(path), exist_ok=True)

    codes, lat = meta["codes"], meta["lat"]
    total = sum(codes.values())
    ok = codes.get(0, 0)
    conn_err = sum(v for k, v in codes.items() if str(k).startswith("CONN_ERR"))
    checks = _checks(meta)
    verdict_pass = all(good for _, good, _ in checks)

    code_items = sorted((( _code_label(k), v) for k, v in codes.items()),
                        key=lambda x: -x[1])
    hist_items = [(name, sum(1 for x in lat if lo <= x < hi)) for lo, hi, name in _LAT_BUCKETS]

    delta_keys = [k for k in meta["snap_before"] if k != "限流·当前阈值"]
    delta_rows = []
    for k in delta_keys:
        b, a = meta["snap_before"].get(k), meta["snap_after"].get(k)
        d = _delta(meta["snap_before"], meta["snap_after"], k)
        delta_rows.append(f"<tr><td>{k}</td><td class='num'>{b}</td><td class='num'>{a}</td>"
                          f"<td class='num {'hl' if d else ''}'>{'+' if d > 0 else ''}{d}</td></tr>")

    css = """
    body{font-family:'PingFang SC','Microsoft YaHei',system-ui,sans-serif;background:#f6f8fb;color:#0f172a;margin:0;padding:28px;line-height:1.7}
    .wrap{max-width:960px;margin:0 auto}
    h1{font-size:24px;margin:0 0 4px}
    .meta{color:#64748b;font-size:13px;margin-bottom:16px}
    .verdict{padding:14px 20px;border-radius:12px;font-size:17px;font-weight:700;margin:14px 0}
    .verdict.pass{background:#ecfdf5;color:#047857;border:1px solid #a7f3d0}
    .verdict.fail{background:#fef2f2;color:#b91c1c;border:1px solid #fca5a5}
    .card{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:18px 22px;margin:14px 0}
    .card h2{font-size:16px;margin:0 0 10px}
    .kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:10px 0}
    .kpi{background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;padding:10px;text-align:center}
    .kpi b{display:block;font-size:20px;color:#2563eb}
    .kpi span{font-size:12px;color:#64748b}
    .bar-row{display:grid;grid-template-columns:190px 1fr 150px;gap:10px;align-items:center;margin:6px 0;font-size:13px}
    .bar-track{background:#f1f5f9;border-radius:6px;height:16px;overflow:hidden;display:block}
    .bar-fill{display:block;height:100%;border-radius:6px}
    .bar-num{color:#475569;font-size:12.5px}
    .check{display:grid;grid-template-columns:26px 1fr auto;gap:8px;padding:7px 0;border-bottom:1px dashed #eef2f7;font-size:13.5px}
    .check:last-child{border-bottom:none}
    .mk-ok{color:#059669;font-weight:800}.mk-no{color:#dc2626;font-weight:800}
    .detail{color:#64748b;font-size:12.5px}
    table{width:100%;border-collapse:collapse;font-size:13px}
    th,td{border:1px solid #e2e8f0;padding:7px 10px;text-align:left}
    th{background:#f1f5f9}
    td.num{text-align:right;font-family:Consolas,monospace}
    td.hl{color:#b45309;font-weight:700}
    .foot{color:#94a3b8;font-size:12px;margin-top:18px;line-height:1.9}
    """

    checks_html = "\n".join(
        f'<div class="check"><span class="{"mk-ok" if good else "mk-no"}">{"✓" if good else "✗"}</span>'
        f'<span>{name}</span><span class="detail">{detail}</span></div>'
        for name, good, detail in checks)

    html = f"""<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<title>并发压测战报 · {meta['users']} 用户齐射</title><style>{css}</style></head><body><div class="wrap">
<h1>📊 并发压测战报 · 端到端抢课（经网关）</h1>
<div class="meta">生成时间 {meta['generated_at']} ｜ 教学班 {meta['class_name']}（id={meta['class_id']}）｜
批量登录 {meta['tokens']} 个真实学生账号耗时 {meta['login_s']:.0f}s ｜
并发连接 {meta['workers']} ｜ 总耗时 {meta['wall_ms']:.0f}ms ｜ 全部请求的发起窗口 {meta['window_ms']:.0f}ms</div>

<div class="verdict {'pass' if verdict_pass else 'fail'}">
{'✅ PASS —— 恰好 ' + str(ok) + ' 人受理成交、' + str(total - ok - conn_err) + ' 人在 Redis 受理层被挡回、落库零超卖、零系统异常（阶段 21 受理制口径）'
 if verdict_pass else
 '❌ FAIL —— 与预期不符（受理成功 ' + str(ok) + ' / 容量 ' + str(meta['capacity']) + '，连接错误 ' + str(conn_err) + '），请检查夹具与限流阈值'}
</div>

<div class="card"><h2>🧾 判读清单（受理与落库两段算术必须闭合）</h2>{checks_html}</div>

<div class="kpis">
<div class="kpi"><b>{ok}</b><span>受理成功（=名额数）</span></div>
<div class="kpi"><b>{total - ok - conn_err}</b><span>Redis 受理层挡回</span></div>
<div class="kpi"><b>{pct(sorted(lat), .95):.0f} ms</b><span>P95 延迟</span></div>
<div class="kpi"><b>{meta['wall_ms']:.0f} ms</b><span>总耗时（受理段）</span></div>
</div>

<div class="card"><h2>🖥️ 客户端视角 · 响应分布</h2>
{_bar_rows(code_items, total, "#2563eb")}</div>

<div class="card"><h2>⏱️ 客户端视角 · 延迟直方图（P50 {pct(sorted(lat), .5):.0f}ms / P95 {pct(sorted(lat), .95):.0f}ms / P99 {pct(sorted(lat), .99):.0f}ms）</h2>
{_bar_rows(hist_items, max(1, len(lat)), "#7c3aed")}</div>

<div class="card"><h2>🛠️ 服务端观测（齐射前后增量）</h2>
<table><tr><th>观测项</th><th>齐射前</th><th>齐射后</th><th>Δ</th></tr>
{''.join(delta_rows)}</table>
<div class="foot">口径：压测端与被测端同机（数字是下限）；"发起窗口"= 全部请求的到达时间跨度；限流阈值 {meta['snap_before'].get('限流·当前阈值')} QPS。</div>
</div>

<div class="foot">由 tools/loadtest.py 自动生成（零依赖单文件，可直接归档/截图）。完整方法论见 docs/10-并发能力测试指南.md §7。</div>
</div></body></html>"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    return path


def cmd_verify(args):
    users = args.users
    print(f"端到端正确性测试：{users} 个真实用户经网关并发抢教学班 {args.class_id}\n")

    # ① 批量拿 token（线程池并发登录；1 万账号约 1~2 分钟）
    accounts = gen_accounts(users)
    print(f"  ① 批量登录 {len(accounts)} 个学生账号（并发 {args.login_workers}）…")
    tokens, lock = [], threading.Lock()
    t_login = time.time()
    with futures.ThreadPoolExecutor(max_workers=args.login_workers) as ex:
        futs = [ex.submit(login, acc) for acc in accounts]
        for i, f in enumerate(futures.as_completed(futs), 1):
            try:
                t = f.result()
            except Exception:
                t = None
            if t:
                with lock:
                    tokens.append(t)
            if i % 1000 == 0:
                print(f"     已尝试 {i}/{len(accounts)}，成功 {len(tokens)}（{time.time() - t_login:.0f}s）")
    print(f"  拿到 {len(tokens)} 个用户 token（候选 {len(accounts)} 个，耗时 {time.time() - t_login:.0f}s）")
    if len(tokens) < min(10, users):
        sys.exit("  可用 token 太少，无法压测")
    probe = {"Authorization": tokens[0], "Content-Type": "application/json"}

    # ② 服务端基线快照 + 读夹具真值（名额数）
    snap_before = server_snapshot(tokens[0])
    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
    conn.request("GET", f"/api/course/{args.course_id}/classes", headers=probe)
    payload = json.loads(conn.getresponse().read().decode())
    conn.close()
    classes = payload.get("data") or []
    cls = next((c for c in classes if c["id"] == args.class_id), None)
    if cls is None:
        sys.exit(f"  找不到教学班 {args.class_id}（接口返回 code={payload.get('code')} "
                 f"{payload.get('message')}）——请确认 --course-id 与夹具（migrate-stress-fixtures.sql）一致")
    capacity = cls["capacity"]
    print(f"  ② 教学班 {cls['className']}：容量 {capacity}，当前已选 {cls['selected']} "
          f"(夹具应为 0；若非 0 请先跑一次前端压测页的清理)")
    qps = snap_before.get("限流·当前阈值")
    if isinstance(qps, (int, float)) and qps < users:
        print(f"  ⚠ 当前 Sentinel 阈值 = {qps} QPS < 用户数——大部分请求会先被 40055 挡掉。"
              f"观察闸门真实挡量请先放开：POST /api/stress/sentinel?qps=100000")

    # ③ 齐射：工作池 + 每工人一条 keep-alive 连接（1 万请求不必开 1 万线程/连接）
    fire_workers = min(args.workers, len(tokens))
    q = queue.Queue()
    for tk in tokens:
        q.put(tk)
    start_gate = threading.Event()
    codes, res_lock = {}, threading.Lock()
    lat_all, lat_ok, send_ts = [], [], []

    def worker():
        conn2 = http.client.HTTPConnection(HOST, PORT, timeout=30)
        try:
            conn2.connect()      # 先建立连接：把"连接风暴"与"请求齐射"分开观察（万人场景的关键）
        except Exception:
            pass                 # 建连失败留到请求时重试（会计入 CONN_ERR 子类型）
        start_gate.wait()
        while True:
            try:
                tk = q.get_nowait()
            except queue.Empty:
                break
            ts = time.perf_counter()
            try:
                c0 = time.perf_counter()
                conn2.request("POST", "/api/course/select",
                              body=json.dumps({"classId": args.class_id}),
                              headers={"Authorization": tk, "Content-Type": "application/json"})
                pl = json.loads(conn2.getresponse().read().decode())
                code = pl.get("code")
                ms = (time.perf_counter() - c0) * 1000
            except Exception as e:
                code = "CONN_ERR:" + type(e).__name__   # 子类型 = 观察线索（refused/reset/非 JSON 响应…）
                ms = (time.perf_counter() - ts) * 1000
                try:
                    conn2.close()
                    conn2 = http.client.HTTPConnection(HOST, PORT, timeout=30)
                    conn2.connect()
                except Exception:
                    pass
            with res_lock:
                codes[code] = codes.get(code, 0) + 1
                lat_all.append(ms)
                send_ts.append(ts)
                if code == 0:
                    lat_ok.append(ms)
        conn2.close()

    print(f"  ③ 齐射：{len(tokens)} 请求 / {fire_workers} 并发连接（全部就绪后同时开火）…")
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(fire_workers)]
    t0 = time.time()
    for t in threads:
        t.start()
    time.sleep(0.3)         # 让所有工人挂到 start_gate 上
    start_gate.set()
    for t in threads:
        t.join()
    wall = (time.time() - t0) * 1000
    window = (max(send_ts) - min(send_ts)) * 1000 if send_ts else 0

    ok = codes.get(0, 0)
    syserr = sum(v for k, v in codes.items()
                 if (isinstance(k, str) and k.startswith("CONN_ERR"))
                 or (isinstance(k, int) and 50000 <= k < 60000))
    lat_all.sort()
    print(f"\n  齐射完成：总耗时 {wall:.0f}ms（全部请求的发起窗口 {window:.0f}ms）")
    print(f"  成功 {ok}（应为名额数 {capacity}）｜ 失败分布 {codes}")
    print(f"  系统异常 {syserr}（应为 0）"
          f"｜ 延迟 P50 {pct(lat_all, .5):.0f}ms / P95 {pct(lat_all, .95):.0f}ms / P99 {pct(lat_all, .99):.0f}ms")

    # ④ 事后核对：受理是即时的、落库是异步的——先等"落库收敛"（selected = 名额数）再核对
    a = None
    conv_t0 = time.time()
    while time.time() - conv_t0 < 20:
        conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
        conn.request("GET", f"/api/course/{args.course_id}/classes", headers=probe)
        after_classes = json.loads(conn.getresponse().read().decode()).get("data") or []
        conn.close()
        a = next((c for c in after_classes if c["id"] == args.class_id), None)
        if a and a.get("selected") == capacity:
            break
        time.sleep(0.3)
    conv_ms = (time.time() - conv_t0) * 1000
    sel_after = a.get("selected") if a else None
    if a:
        print(f"  事后：教学班 selected = {sel_after}（应 = {capacity}），"
              f"落库收敛耗时 {conv_ms:.0f}ms")
    snap_after = server_snapshot(tokens[0])
    print_delta(snap_before, snap_after)

    meta = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "users": users, "tokens": len(tokens), "workers": fire_workers,
        "class_id": args.class_id, "class_name": cls["className"], "capacity": capacity,
        "wall_ms": wall, "window_ms": window, "login_s": time.time() - t_login,
        "codes": codes, "lat": lat_all,
        "snap_before": snap_before, "snap_after": snap_after,
        "selected_after": sel_after, "converge_ms": conv_ms,
    }
    checks = _checks(meta)
    for name, good, detail in checks:
        print(("  ✓ " if good else "  ✗ ") + f"{name}（{detail}）")
    verdict = "PASS" if all(g for _, g, _ in checks) else "FAIL"
    print(f"\n  结论：{verdict}"
          + ("  ✓ 端到端（含网关/鉴权/限流/受理/落库）防超卖成立" if verdict == "PASS"
             else "  ✗ 与预期不符，请检查夹具是否干净、Sentinel 阈值是否过低"))
    print("\n  提示：验证完请清理夹具（删选课记录 + selected 归零），"
          "或直接跑一次前端「⚡ 并发压测」里任一选课场景（自带清理）。")

    # ⑤ HTML 战报（单文件，可双击打开/截屏/归档）
    report_path = None
    if not args.no_report:
        import os
        default_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reports")
        report_path = args.report or os.path.join(
            default_dir, "verify-report-" + time.strftime("%Y%m%d-%H%M%S") + ".html")
        try:
            write_report(meta, report_path)
            print(f"\n  📄 HTML 战报已生成：{report_path}")
            print("     （单文件零依赖，双击浏览器打开——判读清单 / 响应与延迟分布 / 服务端增量一张纸看完）")
        except Exception as e:
            print(f"\n  （战报生成失败，不影响压测结论：{e}）")
            report_path = None
    return {"ok": ok, "capacity": capacity, "codes": codes, "wall_ms": wall,
            "window_ms": window, "report": report_path}


def tomorrow():
    return (date.today() + timedelta(days=1)).isoformat()


def main():
    ap = argparse.ArgumentParser(description="校园平台并发压测工具（容量 + 端到端正确性）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("read", help="容量测试：只读接口 QPS/延迟分位")
    r.add_argument("--account", default="test", help="用于取 token 的账号（默认 test）")
    r.add_argument("--concurrency", default="50,100,200",
                   help="并发梯度，逗号分隔（默认 50,100,200）")
    r.add_argument("--duration", type=int, default=10, help="每档测量秒数（默认 10）")
    r.add_argument("--warmup", type=int, default=2, help="每档预热秒数（默认 2）")
    r.add_argument("--path", help="自定义只读接口路径（不给则测 3 个内置目标）")
    r.add_argument("--method", default="GET")
    r.add_argument("--body", default=None)
    r.set_defaults(func=cmd_read)

    v = sub.add_parser("verify", help="端到端正确性：N 个真实用户并发抢同一门课（含服务端观测台）")
    v.add_argument("--users", type=int, default=150, help="并发用户数（默认 150；库里共 1 万学生账号）")
    v.add_argument("--course-id", type=int, default=9999, help="课程 id（压测班课程，与教学班同 id）")
    v.add_argument("--class-id", type=int, default=9999, help="教学班 id（压测班，容量 10）")
    v.add_argument("--workers", type=int, default=800, help="齐射并发连接数（默认 800，自动不超过用户数）")
    v.add_argument("--login-workers", type=int, default=128, help="批量登录并发（默认 128）")
    v.add_argument("--report", default=None,
                   help="HTML 战报输出路径（默认 tools/reports/verify-report-<时间戳>.html）")
    v.add_argument("--no-report", action="store_true", help="不生成 HTML 战报")
    v.set_defaults(func=cmd_verify)

    args = ap.parse_args()
    if args.cmd == "read":
        args.concurrency = [int(x) for x in str(args.concurrency).split(",")]
    args.func(args)


if __name__ == "__main__":
    main()
