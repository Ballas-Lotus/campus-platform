"""齐射前后的一键准备/恢复（薄封装，避免手敲 token）：
  用法：
    python tools/amprep.py open            # 放开选课限流（qps=100000）+ 查看阈值
    python tools/amprep.py restore         # 恢复默认限流（qps=20）
    python tools/amprep.py purge           # COURSE_OVERSELL 场景（跑前/跑后清理夹具）
    python tools/amprep.py status          # 限流阈值 + 夹具真值（压测课程 9999）
"""
import http.client
import json
import sys

sys.path.insert(0, "tools")
import loadtest as lt  # noqa: E402

HOST, PORT = lt.HOST, lt.PORT


def call(method, path, token, body=None):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=180)
    try:
        headers = {"Authorization": token, "Content-Type": "application/json"}
        conn.request(method, path, body=json.dumps(body) if body else None, headers=headers)
        return json.loads(conn.getresponse().read().decode())
    finally:
        conn.close()


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    token = lt.login("admin")
    if not token:
        sys.exit("admin 登录失败")
    if cmd == "open":
        print(json.dumps(call("POST", "/api/stress/sentinel?qps=100000", token), ensure_ascii=False))
    elif cmd == "restore":
        print(json.dumps(call("POST", "/api/stress/sentinel?qps=20", token), ensure_ascii=False))
    elif cmd == "purge":
        print(json.dumps(call("POST", "/api/stress/run", token,
                              {"scenario": "COURSE_OVERSELL", "threads": 20}), ensure_ascii=False)[:300])
    # 通用：阈值 + 夹具真值
    print("sentinel:", json.dumps(call("GET", "/api/stress/sentinel", token), ensure_ascii=False))
    classes = call("GET", "/api/course/9999/classes", token)
    for c in (classes.get("data") or []):
        print(f"  夹具 教学班 {c['id']} {c['className']}: capacity={c['capacity']} selected={c['selected']} available={c['available']}")


if __name__ == "__main__":
    main()
