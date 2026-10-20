# 种子数据生成器（阶段 2）

纯 Python 标准库（**零第三方依赖**），固定 `seed=42`，产出 13 张表的 CSV 并经 `LOAD DATA LOCAL INFILE` 批量导入 MySQL。

## 一键运行

```bash
cd data-generator

python generate.py      # ① 生成 CSV → output/（约 30 秒）
python verify.py        # ② 独立校验 CSV（16 项不变量，约 7 秒）
python import_data.py   # ③ 导入 MySQL + 库内 SQL 校验（约 3 分钟）
```

> 前置条件：`campus-mysql` 容器已启动（3308）；compose 已开启 `--local-infile=1`。

## 文件说明

| 文件 | 作用 |
|---|---|
| `config.py` | **全部口径与词库**：时间窗口、学院/专业/姓名库、讲座 20 场、课程 200 门、权重曲线、演示剧本 |
| `generate.py` | 生成器主体：用户 → 资源 → 课程/选课 → 预约引擎（位图 + 库存账本）→ 落盘 |
| `verify.py` | 独立校验：从 CSV 全量重算（不复用生成器状态），16 项不变量 |
| `import_data.py` | docker cp → TRUNCATE → LOAD DATA（逐表计时）→ ANALYZE → 库内 SQL 校验 |
| `output/` | 生成的 13 个 CSV + `gen_stats.json` |

## 数据规模（seed=42 固定，可复现）

| 表 | 行数 | 说明 |
|---|---:|---|
| t_user | 10,003 | 10,000 学生 + 3 演示账号（林晚秋/陈屿/王教务，密码均 123456） |
| t_user_quota | 29,920 | 每用户 图书馆每日 + 场馆每周 + 有爽约者再加一行 |
| t_resource | 9,320 | 座位 3,000 + 场馆 20 + 讲座席位 6,000 + 课程名额 300 |
| t_resource_slot | **597,920** | 未来 14 天滚动窗口（588,000 + 3,920）+ 讲座整场片 6,000 |
| t_reservation | **956,828** | 历史 90 天 ~92 万 + 未来 14 天 ~3.2 万 + 讲座 4.5 千 |
| t_user_time_occupancy | 554,795 | 有"未取消预约"的 (用户, 日期) 一条，96 格位图 |
| t_lecture / t_library_area / t_venue | 20 / 15 / 20 | — |
| t_course / t_course_class | 200 / 300 | 8 学院 × 25 门；每门 1~2 个教学班 |
| t_course_selection | 7,017 | 1,000 名学生 × 6~8 门（时间不冲突） |

## 真实感设计（对应 docs/种子数据预览.md 第 5 节）

- **双高峰曲线**：8–11 点 / 14–16 点 / 18–20 点三峰 + 12–13 点午低谷；周末曲线不同
- **月份规律**：暑假 ×0.5、开学初期 ×0.65、国庆假期 ×0.45、常态 ×1.0
- **人格差异**：早起型 25% / 夜猫型 25% / 午间型 15% / 均衡型 35%（贯穿全部历史）
- **活跃度差异**：用户采样权重 0.5~3.0（高频用户预约更多）
- **热点资源**：A馆3层A区（靠窗）×5、B馆2层自习区 ×3、羽毛球场-1号/游泳馆 ×4、头部讲座 ×5
- **爽约惯犯**：5% 的学生贡献 40% 的爽约（约 500 人爽约 ≥20 次）
- **选课规律**：每生 6~8 门且**时间不冲突**；20 个热门班"秒满"（演示选课页"满员直接拒绝"，无候补——2026-10-08 规则）
- **取消-再约**：约 1% 的历史预约附带更早的"CANCELLED"同片记录（演示 `active_key` 生成列）

## 校验不变量（verify.py 全量 + import_data.py 库内）

1. 同用户同日"未取消"预约两两不重叠（位图 AND + 区间扫描双检）
2. `t_user_time_occupancy.bitmap` = 该(用户,日期)未取消预约位图的 OR（逐行全量）
3. `t_resource_slot.booked` = 覆盖该片的有效预约数（逐片全量）；`0 ≤ booked ≤ capacity`
4. 未来有效预约的 `slot_id` 指向正确的片；历史预约 `slot_id` 为 NULL
5. 教学班 `selected` 计数一致；学生课表无冲突；无重复选课
6. 学号/手机/邮箱、`reservation_no`、片三元组唯一

## 常见问题

- **输出乱码**：Git Bash 控制台为 GBK，用 `PYTHONIOENCODING=utf-8 python generate.py`（或重定向到文件查看）。
- **`LOAD DATA` 报 `local_infile` 相关错误**：确认 `docker-compose.yml` 的 mysql `command` 含 `--local-infile=1` 且容器已重建。
- **重跑**：`import_data.py` 会先 TRUNCATE 再导入，可安全重复执行。
