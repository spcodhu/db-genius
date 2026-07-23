# DB-Genius 部署指南（RabbitMQ 安装 + Docker 一键部署）

本文覆盖两部分：
1. 如何在 Ubuntu 上安装 RabbitMQ（单独部署时）；
2. 如何用 Docker 一键部署整套 DB-Genius（postgres + rabbitmq + 后端 app）。

---

## 一、在 Ubuntu 上安装 RabbitMQ

项目使用的镜像/版本为 `rabbitmq:3.13-management-alpine`（自带 Web 管理台）。

- 端口：`5672`（AMQP 连接）、`15672`（Web 管理台）
- 应用默认账号：`guest / guest`（见 `application.yml`）
- 队列/交换机由应用启动时自动创建（`RabbitMqConfig.java`），无需手工建。

### 方案 A（推荐）：Docker 单独跑

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker

docker run -d --name rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  -v rabbitmqdata:/var/lib/rabbitmq \
  --restart unless-stopped \
  rabbitmq:3.13-management-alpine
```

> 若用本项目的全 Docker 部署（见第二部分），RabbitMQ 已包含在 compose 里，**无需单独执行本步骤**。

### 方案 B：apt 原生安装

```bash
sudo apt update
sudo apt install -y rabbitmq-server
sudo systemctl enable --now rabbitmq-server
sudo rabbitmq-plugins enable rabbitmq_management   # 开启 15672 管理台
```

### 部署后必做

1. **别用 guest 远程连接**：RabbitMQ 的 `guest/guest` 默认只允许 `localhost`。跨机器连接需新建用户
   （Docker 环境在命令前加 `docker exec rabbitmq`）：
   ```bash
   sudo rabbitmqctl add_user dbgenius '你的强密码'
   sudo rabbitmqctl set_user_tags dbgenius administrator
   sudo rabbitmqctl set_permissions -p / dbgenius ".*" ".*" ".*"
   ```
   然后在应用侧设置 `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD`。

2. **防火墙**：管理台 `15672` 建议只对内网开放；`5672` 按需放行。
   ```bash
   sudo ufw allow 5672/tcp
   ```

3. 验证：浏览器打开 `http://服务器IP:15672` 登录即可。

---

## 二、Docker 一键部署整套 DB-Genius

### 组成文件

| 文件 | 作用 |
| --- | --- |
| `Dockerfile` | 多阶段构建：build 阶段用 Maven 打包，运行阶段只留 JRE + jar |
| `.dockerignore` | 排除 target/、.git/、.env 等，避免进镜像 |
| `docker-compose.yml` | 编排 `app` + `rabbitmq` 两个服务（PostgreSQL 为远程外部库，不纳入）|
| `deploy/build-docker.sh` | 瘦部署脚本：负责 git 拉代码，其余交给 Docker |

### 职责划分

- **拉代码**：由 `deploy/build-docker.sh` 完成（`git clone / pull`）。
  不放进 Dockerfile —— 源码获取是「构建之前」的事，塞进镜像会引入凭据泄漏、层缓存拿到旧代码等问题。
- **打包**：由 Dockerfile 的 build 阶段（`mvn clean package`）完成，服务器无需装 JDK / Maven。
- **运行**：由 compose 拉起 rabbitmq / app（PostgreSQL 用远程库）。

### 关键点：数据库远程、RabbitMQ 走服务名

- **PostgreSQL 为远程独立部署**，不在 compose 里。app 通过 `.env` 的
  `SPRING_DATASOURCE_URL` 指定远程完整 JDBC 地址（例如
  `jdbc:postgresql://db.example.com:5432/db_genius?currentSchema=app`）。
  - 若远程库其实和 Docker 宿主机是同一台，容器内**不能写 `localhost`**
    （那指向容器自身），应写宿主机内网 IP，或用 `host.docker.internal`。
  - 确认远程 PostgreSQL 的 `pg_hba.conf` / 防火墙允许 Docker 宿主机的 IP 连接。
- **RabbitMQ 与 app 同在 compose 网络**，靠**服务名** `rabbitmq` 互通，**不是 `localhost`**
  （已在 `docker-compose.yml` 的 app 服务环境变量里配好）。

### 部署步骤

1. 安装 Docker：
   ```bash
   sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
   sudo systemctl enable --now docker
   ```

2. 准备 `.env`（含敏感信息，勿提交 Git）：
   ```bash
   cp deploy/.env.example deploy/.env
   # 编辑 deploy/.env，必填：
   #   SPRING_DATASOURCE_URL/USERNAME/PASSWORD（指向远程 PostgreSQL）
   #   DEEPSEEK_API_KEY、DB_GENIUS_ENCRYPT_KEY（32 位）
   ```

3. 一键部署（脚本会拉代码 → 构建镜像 → 启动全套）：
   ```bash
   GIT_REPO=git@gitee.com:xxx/db-genius.git ./deploy/build-docker.sh
   ```

   可选环境变量：`DEPLOY_HOME`（默认 `/opt/db-genius`）、`GIT_BRANCH`（默认 `master`）。

### 手动方式（已在本地有代码时）

不想用脚本，也可在项目根目录直接操作：

```bash
cp deploy/.env.example .env   # compose 会自动读取同目录 .env
docker compose up -d --build  # 构建 app 镜像并启动 rabbitmq + app
```

### 常用运维命令

在 compose 文件所在目录执行（脚本部署时为 `/opt/db-genius/src`）：

```bash
docker compose logs -f app     # 查看后端日志
docker compose ps              # 查看服务状态
docker compose restart app     # 仅重启后端
docker compose up -d --build   # 代码更新后重新构建并滚动更新
docker compose down            # 停止全部（加 -v 连数据卷一并删除）
```

### 访问入口

- 后端 API：`http://服务器IP:8109/api`
- RabbitMQ 管理台：`http://服务器IP:15672`（guest/guest）
- PostgreSQL：远程外部库，地址见 `.env` 的 `SPRING_DATASOURCE_URL`

---

## 三、两个实例连同一个 RabbitMQ 会冲突吗？

**连接本身不冲突** —— RabbitMQ 支持大量并发连接，声明交换机/队列也是幂等的。

但业务上有一个坑：本项目消费者监听**固定队列名** `dbgenius.dbconfig.verify`
（`DbConfigVerifyConsumer.java`）。当远程与本地两个实例都监听它时，构成
「竞争消费者（competing consumers）」模式：

- 同一条消息**只投递给其中一个**消费者（默认轮询），不会重复处理；
- 但**由哪台机器处理是不确定的**。远程触发的「验证数据库 + 生成文档」任务，
  可能被本地机器抢到执行；若本地连不通目标库或环境不同，任务会失败并最终进 DLQ。

规避建议：

- **本地调试**：连一个独立的本机 RabbitMQ，两套环境互不干扰（最干净）。
- **需要隔离**：按环境区分队列名（改 `DbConfigMqConstants.QUEUE_VERIFY`）。
- **刻意做负载均衡**：共享同一队列没问题，但要确保两台机器都能连通目标数据库。
