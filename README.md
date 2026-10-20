# 校园综合服务平台

面向开学抢课与校园预约场景的综合平台，覆盖 **选课、图书馆座位、体育场馆、讲座报名** 四类业务。四个功能共用一套「资源 + 时间片 + 预约」模型，支持区间独占与容量计数两种库存语义，以及跨资源时间冲突检测。

## 核心能力

- **选课**：人数限额先到先得，自动检测同周几 + 节次冲突，退课立即释放名额，高峰期限流。
- **图书馆座位**：楼层 / 座位 / 时段预约，签到与爽约惩罚。
- **体育场馆**：容量计数预约，每人每周限次，签到与取消规则。
- **讲座报名**：限额报名，开场前 7 天开放。
- **统一工作台**：四类资源浏览、时间片格子图、个人中心与当日日程。

## 高并发设计

- **防超卖**：Redis + Lua 原子预扣减；数据库条件更新兜底；唯一索引最终兜底。
- **防击穿 / 穿透**：进程内缓存 + Redis 快照 + 分布式锁回源；空值回填与布隆过滤。
- **名额实时同步**：落库后信号推送 + Redis Pub/Sub 合并 + SSE 长连接；前端按版本去重、防抖校准。
- **异步落库**：Redis 受理即裁决，RocketMQ 异步落库，补偿、修正信号与超时裁决兜底。
- **分库分表**：ShardingSphere-JDBC 2 库 × 4 片，基因法单号，AES 列加密。
- **服务治理**：Spring Cloud Gateway 统一鉴权，Nacos 注册发现，Sentinel 限流。
- **并发正确性**：条件更新、用户行锁、唯一索引、96 格位图冲突检测四道防线。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 3.2、MyBatis-Plus 3.5 |
| 微服务 | Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.1.0（Gateway / Nacos / LoadBalancer / Sentinel） |
| 数据 | MySQL 8、Redis 7、ShardingSphere-JDBC 5.3.2 |
| 消息 | RocketMQ |
| 前端 | Vue 3、Vite、Element Plus、Pinia、Vue Router、Axios |
| 构建 | Maven Wrapper、pnpm |
| 数据生成 | Python 3、pandas、numpy、Faker |

## 环境要求

- JDK 17
- Node.js 18+ 与 pnpm
- Docker Compose（一条命令启动 MySQL、Redis、Nacos、RocketMQ）

## 启动

```bash
# 1. 准备配置
cp .env.example .env
# 修改 .env 里的 MYSQL_ROOT_PASSWORD / DB_PASSWORD / REDIS_PASSWORD
# 注意：DB_PASSWORD 必须与 MYSQL_ROOT_PASSWORD 一致（后端使用 root 连接）

# 2. 启动全部依赖：MySQL 3308 / Redis 6380 / Nacos 8848 / RocketMQ 9876
docker compose up -d

# 3. 首次启动执行分库分表迁移
set -a; source .env; set +a
docker exec -i campus-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 < resources/database/migrate-sharding.sql

# 4. 后端（沿用当前 shell 已注入的环境变量）
cd backend
JAVA_HOME=/path/to/jdk-17 ./mvnw clean package -DskipTests
java -jar campus-user/target/campus-user-0.0.1-SNAPSHOT.jar &
java -jar campus-service/target/campus-service-0.0.1-SNAPSHOT.jar &
java -jar campus-gateway/target/campus-gateway-0.0.1-SNAPSHOT.jar &

# 5. 前端
cd ../frontend
pnpm install
pnpm dev
```

浏览器打开 <http://localhost:5173>。如果后端换了一个新终端启动，先重新执行 `set -a; source .env; set +a`。

## 端口与账号

| 组件 | 地址 | 账号 / 密码 |
|---|---|---|
| MySQL | 127.0.0.1:3308 | root / 以 `.env` 的 `MYSQL_ROOT_PASSWORD` 为准 |
| Redis | 127.0.0.1:6380 | 以 `.env` 的 `REDIS_PASSWORD` 为准 |
| Nacos | http://127.0.0.1:8848/nacos | 本地关闭鉴权 |
| RocketMQ NameServer | 127.0.0.1:9876 | — |
| 网关 | http://127.0.0.1:9080 | — |
| 用户 / 业务服务 | 9081 / 9082 | — |
| 前端 | http://localhost:5173 | — |

演示账号（密码均为 `123456`）：`2021001`、`2021002`、`admin`。测试账号 `test` 每次登录会自动清空预约与选课数据，便于反复演示。

## 目录结构

```text
campus-platform/
├── backend/                 # Spring Boot 多模块
│   ├── campus-common/       # 共享内核（Result / 异常 / JWT / UserContext）
│   ├── campus-user/         # 用户服务 :9081
│   ├── campus-service/      # 业务服务 :9082
│   └── campus-gateway/      # 网关 :9080（路由 + JWT 鉴权）
├── frontend/                # Vue 3 + Vite
├── resources/database/      # 建表与迁移脚本
├── data-generator/          # 种子数据生成
├── tools/                   # 并发压测脚本
├── rocketmq/broker.conf     # RocketMQ Broker 配置
└── docker-compose.yml       # MySQL / Redis / Nacos / RocketMQ
```

## 压测与种子数据

- 压测脚本：`tools/loadtest.py`、`tools/burst-demo.py` 等。
- 数据生成：

```bash
cd data-generator
python generate.py
python verify.py
python import_data.py
```

## 常见问题

- `pnpm install` 报 `ERR_PNPM_IGNORED_BUILDS`：执行 `pnpm approve-builds --all` 后重试。
- 分片后连接池需按单分片峰值并发规划，MySQL `max_connections=500`（compose 已固化）。
- `127.0.0.1:5173` 不通时改用 `http://localhost:5173` 访问。
- Nacos / RocketMQ 首次启动需要几十秒；后端注册失败会自动重试。
- 修改 `.env` 的 `DB_PASSWORD` 后，必须同步修改两份 `shardingsphere-config.yaml`（ShardingSphere 5.3.2 不支持环境变量占位符）。
