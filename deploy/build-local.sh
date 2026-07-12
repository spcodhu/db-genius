#!/bin/bash

###############################################################################
# DB-Genius 本地构建并部署脚本
# 功能：
#   1. 从指定 Git 仓库拉取指定分支代码；
#   2. 使用本地 Maven 打包多模块项目生成可运行 JAR；
#   3. 停止旧服务、备份旧版本、启动新服务，完成完整部署。
#
# 必填环境变量：
#   GIT_REPO          SSH 格式的 Git 仓库地址，例如 git@gitee.com:xxx/db-genius.git
#
# 敏感配置说明：
#   本脚本需要 deploy/.env 文件作为应用运行时的环境变量（含数据库密码、API Key 等敏感信息）。
#   .env 文件不应提交到 Git，已在 .gitignore 中排除。
#   推荐做法：将 deploy/.env.example 复制为 deploy/.env 并填入真实值，
#   然后将整个 deploy 目录手动上传到服务器，再执行本脚本。
#
# SSH 认证方式（三选一，按优先级匹配）：
#   1. 不设置任何 SSH 环境变量：
#      依赖系统默认 SSH 配置。适用于已按 Gitee/GitHub 官方流程配置好 SSH 密钥的场景：
#        ssh-keygen -t rsa
#        cat ~/.ssh/id_rsa.pub  # 将公钥添加到 Gitee/GitHub 账户的 SSH 公钥管理中
#      配置完成后，git 会自动使用 ~/.ssh/id_rsa 私钥连接仓库。
#   2. SSH_KEY_PATH：
#      指定本地已有的 SSH 私钥文件路径，例如 /home/user/.ssh/id_rsa_gitee
#   3. SSH_PRIVATE_KEY：
#      直接传入 SSH 私钥的完整内容（字符串），脚本会临时写入密钥文件使用。
#      适用于 CI/CD、Docker 或不便在服务器持久化私钥文件的场景。
#
# 可选环境变量：
#   GIT_BRANCH                    拉取的分支，默认 master
#   MVN_OPTS                      Maven 额外参数，例如 "-P prod -DskipTests"
#   BUILD_DIR                     代码拉取与构建目录，默认创建临时目录
#   SSH_STRICT_HOST_CHECKING      是否严格校验远程主机密钥，默认 yes
#   DEPLOY_HOME                   部署根目录，默认 /opt/db-genius
#   APP_PORT                      应用端口，默认 8080
#   JAVA_OPTS                     JVM 参数，默认 "-Xms512m -Xmx1024m -XX:+UseG1GC"
#   SPRING_PROFILES               Spring 环境，默认 prod
#   START_POSTGRES                是否通过 docker compose 启动 PostgreSQL，默认 true
###############################################################################

# 开启严格模式：遇错退出、未定义变量报错、管道错误传递
set -euo pipefail

# 设置颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 应用配置
APP_NAME="DB-Genius"
APP_NAME_LOWER="db-genius"
APP_VERSION="1.0.0"
JAR_NAME="db-genius-web-${APP_VERSION}.jar"

# 默认分支
GIT_BRANCH="${GIT_BRANCH:-master}"

# 默认构建目录为临时目录
BUILD_DIR="${BUILD_DIR:-$(mktemp -d)}"

# 部署目录配置
DEPLOY_HOME="${DEPLOY_HOME:-/opt/db-genius}"
APP_HOME="${DEPLOY_HOME}/app"
LOG_HOME="${DEPLOY_HOME}/logs"
CONFIG_HOME="${DEPLOY_HOME}/config"

# Java 配置
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC}"
SPRING_PROFILES="${SPRING_PROFILES:-prod}"
APP_PORT="${APP_PORT:-8080}"
START_POSTGRES="${START_POSTGRES:-true}"

# 临时 SSH 密钥文件路径（仅在使用 SSH_PRIVATE_KEY 时生成）
SSH_KEY_FILE=""

# 输出分隔线
print_line() {
    echo "=========================================="
}

# 清理临时资源
cleanup() {
    echo ""
    echo -e "${YELLOW}[清理] 清理临时文件...${NC}"

    # 删除临时 SSH 密钥文件
    if [ -n "${SSH_KEY_FILE}" ] && [ -f "${SSH_KEY_FILE}" ]; then
        rm -f "${SSH_KEY_FILE}"
        echo "已删除临时 SSH 密钥文件"
    fi

    # 删除临时构建目录
    if [ -n "${BUILD_DIR}" ] && [ -d "${BUILD_DIR}" ]; then
        rm -rf "${BUILD_DIR}"
        echo "已删除临时构建目录: ${BUILD_DIR}"
    fi
}

# 脚本退出时自动清理
trap cleanup EXIT

# 检查环境变量是否已设置
check_env() {
    local var_name="$1"
    if [ -z "${!var_name:-}" ]; then
        echo -e "${RED}[错误] 环境变量 ${var_name} 未设置${NC}" >&2
        exit 1
    fi
}

# 检查系统命令是否可用
check_command() {
    local cmd="$1"
    if ! command -v "${cmd}" &> /dev/null; then
        echo -e "${RED}[错误] 未找到命令: ${cmd}，请先安装${NC}" >&2
        exit 1
    fi
}

# 准备 SSH 认证
# 优先级：SSH_PRIVATE_KEY > SSH_KEY_PATH > 系统默认 SSH 配置
prepare_ssh() {
    echo -e "${BLUE}[步骤 3/9] 准备 SSH 认证...${NC}"

    local strict_checking="${SSH_STRICT_HOST_CHECKING:-yes}"

    # 方式一：通过环境变量传入私钥内容
    if [ -n "${SSH_PRIVATE_KEY:-}" ]; then
        SSH_KEY_FILE=$(mktemp)
        echo "${SSH_PRIVATE_KEY}" > "${SSH_KEY_FILE}"
        chmod 600 "${SSH_KEY_FILE}"
        echo -e "${GREEN}已使用环境变量 SSH_PRIVATE_KEY 生成临时密钥文件${NC}"
        export GIT_SSH_COMMAND="ssh -i ${SSH_KEY_FILE} -o IdentitiesOnly=yes -o StrictHostKeyChecking=${strict_checking}"
        return
    fi

    # 方式二：指定本地私钥文件路径
    if [ -n "${SSH_KEY_PATH:-}" ]; then
        if [ ! -f "${SSH_KEY_PATH}" ]; then
            echo -e "${RED}[错误] 指定的 SSH 私钥文件不存在: ${SSH_KEY_PATH}${NC}" >&2
            exit 1
        fi

        if [ "$(stat -c %a "${SSH_KEY_PATH}" 2>/dev/null || stat -f %Lp "${SSH_KEY_PATH}")" != "600" ]; then
            echo -e "${YELLOW}[警告] SSH 私钥文件权限不是 600，脚本将自动修正${NC}"
            chmod 600 "${SSH_KEY_PATH}"
        fi

        echo -e "${GREEN}已使用本地 SSH 私钥文件: ${SSH_KEY_PATH}${NC}"
        export GIT_SSH_COMMAND="ssh -i ${SSH_KEY_PATH} -o IdentitiesOnly=yes -o StrictHostKeyChecking=${strict_checking}"
        return
    fi

    # 方式三：依赖系统默认 SSH 配置
    echo -e "${GREEN}未配置 SSH_PRIVATE_KEY 和 SSH_KEY_PATH，将使用系统默认 SSH 配置${NC}"
    echo "请确保已按 Gitee/GitHub 官方流程配置 SSH 密钥，例如："
    echo "  ssh-keygen -t rsa"
    echo "  cat ~/.ssh/id_rsa.pub  # 添加到 Gitee/GitHub 账户的 SSH 公钥管理中"

    # 如果用户显式关闭了主机校验，则仍然需要设置 GIT_SSH_COMMAND
    if [ "${strict_checking}" == "no" ]; then
        export GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
    fi
}

# 检查 Java 环境
check_java() {
    echo -e "${BLUE}[步骤 5/9] 检查 Java 环境...${NC}"

    if ! command -v java &> /dev/null; then
        echo -e "${RED}[错误] 未找到 Java，请先安装 JDK 22 或更高版本${NC}" >&2
        exit 1
    fi

    local java_version
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{print $1}')
    if [ "${java_version}" -lt 22 ]; then
        echo -e "${RED}[错误] Java 版本过低，需要 JDK 22 或更高版本${NC}" >&2
        exit 1
    fi

    echo -e "${GREEN}Java 版本检查通过${NC}"
    java -version
    echo ""
}

# 处理环境变量文件
prepare_env_file() {
    echo -e "${BLUE}[步骤 6/9] 准备环境变量文件...${NC}"

    mkdir -p "${CONFIG_HOME}"

    local env_file=""
    if [ -f "${SCRIPT_DIR}/.env" ]; then
        env_file="${SCRIPT_DIR}/.env"
    elif [ -f "./.env" ]; then
        env_file="./.env"
    else
        echo -e "${RED}[错误] 未找到 .env 文件${NC}" >&2
        echo "请将 deploy/.env.example 复制为 deploy/.env 并填入真实值，" >&2
        echo "然后将整个 deploy 目录上传到服务器（.env 不要提交到 Git）。" >&2
        exit 1
    fi

    echo -e "${GREEN}找到环境变量文件: ${env_file}${NC}"
    cp "${env_file}" "${CONFIG_HOME}/.env"

    # 验证必要变量
    # shellcheck source=/dev/null
    source "${env_file}"
    local required_vars=("SPRING_DATASOURCE_URL" "SPRING_DATASOURCE_USERNAME" "SPRING_DATASOURCE_PASSWORD" "DEEPSEEK_API_KEY" "DB_GENIUS_ENCRYPT_KEY")
    local missing=()
    for var in "${required_vars[@]}"; do
        if [ -z "${!var:-}" ]; then
            missing+=("${var}")
        fi
    done

    if [ ${#missing[@]} -gt 0 ]; then
        echo -e "${RED}[错误] .env 文件中以下必填项不能为空:${NC}" >&2
        for var in "${missing[@]}"; do
            echo "  - ${var}" >&2
        done
        exit 1
    fi

    echo -e "${GREEN}环境变量文件准备完成${NC}"
    echo ""
}

# 启动 PostgreSQL（可选）
start_postgres() {
    if [ "${START_POSTGRES}" != "true" ]; then
        echo -e "${YELLOW}[跳过] 不启动 PostgreSQL，请确保外部数据库已可用${NC}"
        return
    fi

    echo -e "${BLUE}[步骤 7/9] 启动 PostgreSQL 服务...${NC}"

    if [ ! -f "${PROJECT_DIR}/docker-compose.yml" ]; then
        echo -e "${RED}[错误] 未找到 docker-compose.yml: ${PROJECT_DIR}/docker-compose.yml${NC}" >&2
        echo "请将 docker-compose.yml 放在项目根目录，或设置 START_POSTGRES=false 使用外部数据库" >&2
        exit 1
    fi

    cd "${PROJECT_DIR}"
    docker compose up -d

    echo -e "${BLUE}等待 PostgreSQL 健康检查...${NC}"
    local retries=30
    while [ ${retries} -gt 0 ]; do
        if docker compose ps postgres | grep -q "healthy"; then
            echo -e "${GREEN}PostgreSQL 已就绪${NC}"
            echo ""
            return
        fi
        sleep 1
        retries=$((retries - 1))
    done

    echo -e "${YELLOW}[警告] PostgreSQL 健康检查超时，继续部署，但服务可能启动失败${NC}" >&2
    echo ""
}

# 停止旧服务
stop_old_service() {
    echo -e "${BLUE}检查并停止旧的服务...${NC}"

    if systemctl is-active --quiet "${APP_NAME_LOWER}" 2>/dev/null; then
        echo "停止服务: ${APP_NAME_LOWER}"
        systemctl stop "${APP_NAME_LOWER}"
        sleep 2
    fi

    # 如果服务不存在，尝试使用 PID 文件停止
    if [ -f "${DEPLOY_HOME}/${APP_NAME_LOWER}.pid" ]; then
        local old_pid
        old_pid=$(cat "${DEPLOY_HOME}/${APP_NAME_LOWER}.pid")
        if ps -p "${old_pid}" > /dev/null 2>&1; then
            echo "停止进程: ${old_pid}"
            kill -15 "${old_pid}"
            sleep 3
            # 如果还在运行，强制停止
            if ps -p "${old_pid}" > /dev/null 2>&1; then
                kill -9 "${old_pid}"
            fi
        fi
        rm -f "${DEPLOY_HOME}/${APP_NAME_LOWER}.pid"
    fi
}

# 部署新版本
deploy_jar() {
    local jar_path="$1"

    echo -e "${BLUE}部署新版本...${NC}"

    mkdir -p "${APP_HOME}"
    mkdir -p "${LOG_HOME}"
    mkdir -p "${CONFIG_HOME}"

    # 备份旧版本
    if [ -f "${APP_HOME}/${JAR_NAME}" ]; then
        echo -e "${YELLOW}备份旧版本...${NC}"
        local backup_name="${APP_NAME_LOWER}-$(date +%Y%m%d_%H%M%S).jar"
        mv "${APP_HOME}/${JAR_NAME}" "${APP_HOME}/${backup_name}"
        echo "旧版本已备份为: ${backup_name}"
    fi

    # 复制新版本
    cp "${jar_path}" "${APP_HOME}/${JAR_NAME}"
    chmod +x "${APP_HOME}/${JAR_NAME}"
    echo -e "${GREEN}新版本已部署到: ${APP_HOME}/${JAR_NAME}${NC}"
}

# 创建并启动 systemd 服务
start_service() {
    echo -e "${BLUE}创建 systemd 服务...${NC}"

    cat > "/etc/systemd/system/${APP_NAME_LOWER}.service" << EOF
[Unit]
Description=DB-Genius AI Database Master
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${APP_HOME}
EnvironmentFile=${CONFIG_HOME}/.env
ExecStart=/usr/bin/java ${JAVA_OPTS} -Dspring.profiles.active=${SPRING_PROFILES} -jar ${APP_HOME}/${JAR_NAME}
ExecStop=/bin/kill -15 \$MAINPID
Restart=on-failure
RestartSec=10
StandardOutput=append:${LOG_HOME}/application.log
StandardError=append:${LOG_HOME}/error.log

[Install]
WantedBy=multi-user.target
EOF

    echo -e "${BLUE}重新加载 systemd 配置...${NC}"
    systemctl daemon-reload

    echo -e "${BLUE}启动服务...${NC}"
    systemctl start "${APP_NAME_LOWER}"

    echo -e "${BLUE}等待服务启动...${NC}"
    sleep 8

    # 检查服务状态
    if systemctl is-active --quiet "${APP_NAME_LOWER}"; then
        echo ""
        print_line
        echo -e "${GREEN}部署成功！${NC}"
        print_line
        echo ""
        echo -e "服务名称: ${GREEN}${APP_NAME_LOWER}${NC}"
        echo -e "服务状态: ${GREEN}运行中${NC}"
        echo -e "应用端口: ${GREEN}${APP_PORT}${NC}"
        echo -e "部署目录: ${GREEN}${DEPLOY_HOME}${NC}"
        echo -e "日志目录: ${GREEN}${LOG_HOME}${NC}"
        echo ""
        echo -e "${YELLOW}常用命令：${NC}"
        echo "查看状态: systemctl status ${APP_NAME_LOWER}"
        echo "停止服务: systemctl stop ${APP_NAME_LOWER}"
        echo "启动服务: systemctl start ${APP_NAME_LOWER}"
        echo "重启服务: systemctl restart ${APP_NAME_LOWER}"
        echo "查看日志: tail -f ${LOG_HOME}/application.log"
        echo "开机自启: systemctl enable ${APP_NAME_LOWER}"
        echo "编辑配置: vi ${CONFIG_HOME}/.env (修改后需 systemctl restart ${APP_NAME_LOWER})"
        echo ""
    else
        echo -e "${RED}=========================================${NC}" >&2
        echo -e "${RED}部署失败！${NC}" >&2
        echo -e "${RED}=========================================${NC}" >&2
        echo ""
        echo "请检查日志文件: ${LOG_HOME}/error.log"
        echo "或运行: systemctl status ${APP_NAME_LOWER}"
        exit 1
    fi
}

# 主流程开始
echo ""
print_line
echo -e "${GREEN}  DB-Genius - Build & Deploy Local${NC}"
print_line
echo ""

# 检查是否为 root 用户
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}[错误] 请使用 sudo 或 root 用户运行此脚本${NC}" >&2
    exit 1
fi

# 1. 检查必填环境变量
echo -e "${BLUE}[步骤 1/9] 检查环境变量...${NC}"
check_env "GIT_REPO"
echo -e "${GREEN}环境变量检查通过${NC}"
echo "  Git 仓库: ${GIT_REPO}"
echo "  构建分支: ${GIT_BRANCH}"
echo "  部署目录: ${DEPLOY_HOME}"
if [ -n "${SSH_PRIVATE_KEY:-}" ]; then
    echo "  SSH 认证方式: 环境变量 SSH_PRIVATE_KEY"
elif [ -n "${SSH_KEY_PATH:-}" ]; then
    echo "  SSH 认证方式: 本地私钥文件 SSH_KEY_PATH=${SSH_KEY_PATH}"
else
    echo "  SSH 认证方式: 系统默认 SSH 配置"
fi
echo "  启动 PostgreSQL: ${START_POSTGRES}"
echo ""

# 2. 检查必要命令
echo -e "${BLUE}[步骤 2/9] 检查必要命令...${NC}"
check_command "git"
check_command "mvn"
check_command "java"
if [ "${START_POSTGRES}" == "true" ]; then
    check_command "docker"
fi
echo -e "${GREEN}命令检查通过${NC}"
echo ""

# 3. 准备 SSH 认证
prepare_ssh
echo ""

# 4. 拉取代码
echo -e "${BLUE}[步骤 4/9] 拉取代码...${NC}"

# 如果构建目录已存在，先清空
if [ -d "${BUILD_DIR}" ]; then
    rm -rf "${BUILD_DIR}"
fi

echo "开始克隆仓库分支: ${GIT_BRANCH}"
git clone -b "${GIT_BRANCH}" --depth 1 "${GIT_REPO}" "${BUILD_DIR}"

echo -e "${GREEN}代码拉取成功，目录: ${BUILD_DIR}${NC}"
echo ""

# 5. Maven 打包
echo -e "${BLUE}[步骤 5/9] 开始 Maven 打包...${NC}"
cd "${BUILD_DIR}"

# 执行 Maven 打包命令
# 如果脚本同级目录存在 settings.xml，则使用该配置文件（可配置阿里云等国内镜像）
if [ -f "${SCRIPT_DIR}/settings.xml" ]; then
    echo -e "${GREEN}使用本地 Maven 配置文件: ${SCRIPT_DIR}/settings.xml${NC}"
    MVN_SETTINGS_ARG="-s ${SCRIPT_DIR}/settings.xml"
else
    MVN_SETTINGS_ARG=""
fi

mvn ${MVN_SETTINGS_ARG} clean package ${MVN_OPTS:-} -DskipTests

# 查找生成的可运行 JAR 文件（排除 source/javadoc 包，优先 db-genius-web 模块）
JAR_PATH=$(find "${BUILD_DIR}" -maxdepth 4 -name "db-genius-web-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" -type f | head -n 1)

if [ -z "${JAR_PATH}" ] || [ ! -f "${JAR_PATH}" ]; then
    echo -e "${RED}[错误] 未找到 db-genius-web 模块构建生成的 JAR 文件${NC}" >&2
    exit 1
fi

echo -e "${GREEN}Maven 打包成功${NC}"
echo "  JAR 文件: ${JAR_PATH}"
echo ""

# 6. 检查 Java 环境并准备环境变量文件
check_java
prepare_env_file

# 7. 启动 PostgreSQL
start_postgres

# 8. 部署 JAR
echo -e "${BLUE}[步骤 8/9] 部署新版本...${NC}"
stop_old_service
deploy_jar "${JAR_PATH}"
echo ""

# 9. 启动服务
echo -e "${BLUE}[步骤 9/9] 启动服务...${NC}"
start_service

echo ""
print_line
echo -e "${GREEN}构建并部署流程执行完毕${NC}"
print_line
echo ""
