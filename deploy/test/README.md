# DB-Genius 全量测试环境部署说明

本目录配套根目录的 `docker-compose.test.yml`，用于在测试服务器上**一次性启动**：
后端 app + 系统库 PostgreSQL + RabbitMQ + 全部 10 种待测目标数据库。

与根目录 `docker-compose.yml`（生产/本地开发用，依赖外部 PG）**完全独立**，互不影响。

## 前置条件

- Docker（含 compose 插件），无需 JDK / Maven（镜像内多阶段构建）。
  macOS 上可用 Docker Desktop / OrbStack / colima 任一运行时（macOS 无原生 Docker，均需 Linux VM）
- 内存建议 ≥ 16GB（Oracle / SQL Server / Doris / StarRocks / OceanBase 较重；
  低配机器可在 `docker-compose.test.yml` 中注释掉暂不需要的数据库服务）
- 使用 Docker Desktop（Windows/macOS）时，记得把 VM 内存上限调大：
  Settings → Resources → Memory 建议 12~16GB（默认值远小于物理内存）；
  colima 用 `colima start --memory 16` 创建
- Oracle / Doris / StarRocks 所需的共享内存（`shm_size`）已在 compose 中配好，
  与宿主机内存大小、运行时种类无关，无需额外处理

## 部署步骤

```bash
# 1. 准备环境变量（填入 DEEPSEEK_API_KEY / 加密密钥 / OSS 等）
cp deploy/test/.env.example deploy/test/.env

# 2. 构建并启动全套容器（在项目根目录执行）
docker compose -f docker-compose.test.yml --env-file deploy/test/.env up -d --build

# 3. 查看状态（重型数据库首次启动较慢，见下方启动耗时）
docker compose -f docker-compose.test.yml ps

# 4. 查看后端日志
docker compose -f docker-compose.test.yml logs -f app
```

访问：`http://<测试服务器IP>:8109/api`，默认账号 `admin` / `admin123`。

## 启动耗时参考

| 服务 | 首次启动耗时 |
|------|-------------|
| app / postgres-system / rabbitmq / mysql / postgres / mongo / mariadb | < 1 分钟 |
| TiDB / SQL Server | 1~2 分钟 |
| Doris / StarRocks | 1~2 分钟（FE+BE 全部就绪后才可连接） |
| Oracle XE | 2~3 分钟（含建库与 demo 用户初始化） |
| OceanBase | 3~5 分钟（首次需自建集群） |

## 在 app 中创建数据库配置（容器网络内连接信息）

| 类型 | dbType | host | port | 账号 / 密码 | dbName |
|------|--------|------|------|-------------|--------|
| MySQL | `mysql` | `mysql-test` | 3306 | root / `DbGenius!234` | `demo` |
| PostgreSQL | `postgresql` | `postgres-test` | 5432 | demo / `DbGenius!234` | `demo` |
| MongoDB | `mongodb` | `mongo-test` | 27017 | root / `DbGenius!234` | `admin` |
| MariaDB | `mariadb` | `mariadb-test` | 3306 | demo / `DbGenius!234` | `demo` |
| TiDB | `tidb` | `tidb-test` | 4000 | root / （空密码） | `test` |
| Doris | `doris` | `doris-test` | 9030 | root / （空密码） | `demo`（需先建库，见下） |
| StarRocks | `starrocks` | `starrocks-test` | 9030 | root / （空密码） | `demo`（需先建库，见下） |
| OceanBase | `oceanbase` | `oceanbase-test` | 2881 | root@test / `DbGenius!234` | `test` 租户下自建库 |
| Oracle | `oracle` | `oracle-test` | 1521 | demo / `DbGenius!234` | `XEPDB1`（service name） |
| SQL Server | `sqlserver` | `sqlserver-test` | 1433 | sa / `DbGenius!234` | `master` |

> 宿主机端口映射与容器内端口不同（如 StarRocks 宿主机 19030），从 app 容器内
> 连接时**一律用上表的容器名 + 容器内端口**；宿主机端口仅供外部客户端调试。

## Doris / StarRocks 建库

两者镜像默认没有 `demo` 库，首次启动后建一次即可：

```bash
docker exec -it db-genius-test-doris-test-1 mysql -h127.0.0.1 -P9030 -uroot -e "CREATE DATABASE demo;"
docker exec -it db-genius-test-starrocks-test-1 mysql -h127.0.0.1 -P9030 -uroot -e "CREATE DATABASE demo;"
```

（容器名以 `docker compose -f docker-compose.test.yml ps` 实际输出为准；
all-in-one 镜像内一般自带 mysql 客户端，没有的话用宿主机客户端连映射端口亦可。）

## 试用版联调

`deploy/test/.env` 中把 `DB_GENIUS_TRIAL_ENABLED=true` 后重建 app，
预置内置库会自动指向 compose 内 `mysql-test` 的 `demo` 库。

## 常用命令

```bash
docker compose -f docker-compose.test.yml ps              # 状态
docker compose -f docker-compose.test.yml logs -f app     # 后端日志
docker compose -f docker-compose.test.yml restart app     # 重启后端
docker compose -f docker-compose.test.yml down            # 停止（加 -v 连数据卷一起删）
```

## 安全提醒

所有数据库密码（`DbGenius!234` 等）仅供测试环境，严禁复用到生产；
`.env` 含敏感信息，不要提交 Git。
