#!/bin/bash

###############################################################################
# DB-Genius 裸机部署脚本
#
# 前置条件：
#   - 服务器已安装 JDK 21+、Maven 3.8+、Git
#   - 外部 PostgreSQL 已部署并可用
#   - 本脚本同目录下已放置 .env 文件（从 .env.example 复制并填写真实值）
#
# 必填环境变量：
#   GIT_REPO          SSH 格式的 Git 仓库地址，例如 git@gitee.com:xxx/db-genius.git
#
# 可选环境变量：
#   DEPLOY_HOME       部署根目录，默认 /opt/db-genius
#   JAVA_OPTS         JVM 参数，默认 "-Xms512m -Xmx1024m -XX:+UseG1GC"
#   SPRING_PROFILES   Spring 环境，默认 prod
#   MVN_OPTS          Maven 额外参数
#
# 用法：
#   sudo GIT_REPO=git@gitee.com:xxx/db-genius.git ./deploy/build-local.sh
###############################################################################

set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_NAME="db-genius"
APP_VERSION="1.0.0"
JAR_NAME="db-genius-web-${APP_VERSION}.jar"

DEPLOY_HOME="${DEPLOY_HOME:-/opt/db-genius}"
APP_HOME="${DEPLOY_HOME}/app"
LOG_HOME="${DEPLOY_HOME}/logs"
CONFIG_HOME="${DEPLOY_HOME}/config"

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC}"
SPRING_PROFILES="${SPRING_PROFILES:-prod}"
APP_PORT="${APP_PORT:-8109}"

SSH_KEY_FILE=""

cleanup() {
    if [ -n "${SSH_KEY_FILE}" ] && [ -f "${SSH_KEY_FILE}" ]; then
        rm -f "${SSH_KEY_FILE}"
    fi
}

trap cleanup EXIT

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_ok() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_err() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

prepare_ssh() {
    log_info "准备 SSH 认证..."

    if [ -n "${SSH_PRIVATE_KEY:-}" ]; then
        SSH_KEY_FILE=$(mktemp)
        echo "${SSH_PRIVATE_KEY}" > "${SSH_KEY_FILE}"
        chmod 600 "${SSH_KEY_FILE}"
        export GIT_SSH_COMMAND="ssh -i ${SSH_KEY_FILE} -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes"
        log_ok "使用 SSH_PRIVATE_KEY 环境变量"
        return
    fi

    if [ -n "${SSH_KEY_PATH:-}" ]; then
        if [ ! -f "${SSH_KEY_PATH}" ]; then
            log_err "SSH 私钥文件不存在: ${SSH_KEY_PATH}"
            exit 1
        fi
        chmod 600 "${SSH_KEY_PATH}"
        export GIT_SSH_COMMAND="ssh -i ${SSH_KEY_PATH} -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes"
        log_ok "使用 SSH 私钥文件: ${SSH_KEY_PATH}"
        return
    fi

    log_ok "使用系统默认 SSH 配置"
}

check_env_file() {
    log_info "检查 .env 文件..."

    local env_file="${SCRIPT_DIR}/.env"
    if [ ! -f "${env_file}" ]; then
        log_err "未找到 ${env_file}"
        echo "请将 deploy/.env.example 复制为 deploy/.env 并填入真实值后上传到服务器。"
        exit 1
    fi

    # shellcheck source=/dev/null
    source "${env_file}"
    local required=("SPRING_DATASOURCE_URL" "SPRING_DATASOURCE_USERNAME" "SPRING_DATASOURCE_PASSWORD" "DEEPSEEK_API_KEY" "DB_GENIUS_ENCRYPT_KEY")
    for var in "${required[@]}"; do
        if [ -z "${!var:-}" ]; then
            log_err ".env 中 ${var} 不能为空"
            exit 1
        fi
    done

    mkdir -p "${CONFIG_HOME}"
    cp "${env_file}" "${CONFIG_HOME}/.env"
    log_ok ".env 文件检查通过"
}

check_commands() {
    log_info "检查必要命令..."
    for cmd in git mvn java; do
        if ! command -v "${cmd}" &> /dev/null; then
            log_err "未找到命令: ${cmd}"
            exit 1
        fi
    done
    log_ok "命令检查通过"
}

check_java() {
    local version
    version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{print $1}')
    if [ "${version}" -lt 21 ]; then
        log_err "需要 JDK 21 或更高版本"
        exit 1
    fi
    log_ok "Java 版本: $(java -version 2>&1 | head -n 1)"
}

build_jar() {
    log_info "拉取代码并构建..."

    local build_dir
    build_dir=$(mktemp -d)

    git clone --depth 1 "${GIT_REPO}" "${build_dir}"
    cd "${build_dir}"

    local settings_arg=""
    if [ -f "${SCRIPT_DIR}/settings.xml" ]; then
        settings_arg="-s ${SCRIPT_DIR}/settings.xml"
    fi

    mvn ${settings_arg} clean package ${MVN_OPTS:-} -DskipTests

    local jar_path
    jar_path=$(find "${build_dir}" -name "db-genius-web-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" -type f | head -n 1)

    if [ -z "${jar_path}" ] || [ ! -f "${jar_path}" ]; then
        log_err "未找到构建生成的 JAR 文件"
        rm -rf "${build_dir}"
        exit 1
    fi

    echo "${jar_path}"
}

stop_service() {
    if systemctl is-active --quiet "${APP_NAME}" 2>/dev/null; then
        log_info "停止旧服务..."
        systemctl stop "${APP_NAME}"
        sleep 2
    fi
}

deploy_jar() {
    local jar_path="$1"

    log_info "部署 JAR..."
    mkdir -p "${APP_HOME}" "${LOG_HOME}" "${CONFIG_HOME}"

    if [ -f "${APP_HOME}/${JAR_NAME}" ]; then
        local backup="${APP_NAME}-$(date +%Y%m%d_%H%M%S).jar"
        mv "${APP_HOME}/${JAR_NAME}" "${APP_HOME}/${backup}"
        log_warn "旧版本已备份: ${APP_HOME}/${backup}"
    fi

    cp "${jar_path}" "${APP_HOME}/${JAR_NAME}"
    chmod +x "${APP_HOME}/${JAR_NAME}"
    log_ok "JAR 已部署到: ${APP_HOME}/${JAR_NAME}"
}

start_service() {
    log_info "创建 systemd 服务..."

    cat > "/etc/systemd/system/${APP_NAME}.service" << EOF
[Unit]
Description=DB-Genius
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

    systemctl daemon-reload
    systemctl start "${APP_NAME}"
    sleep 5

    if systemctl is-active --quiet "${APP_NAME}"; then
        log_ok "服务启动成功"
        echo ""
        echo "应用端口: ${APP_PORT}"
        echo "部署目录: ${DEPLOY_HOME}"
        echo "日志文件: ${LOG_HOME}/application.log"
        echo ""
        echo "常用命令:"
        echo "  systemctl status ${APP_NAME}"
        echo "  systemctl restart ${APP_NAME}"
        echo "  tail -f ${LOG_HOME}/application.log"
    else
        log_err "服务启动失败，请查看日志: ${LOG_HOME}/error.log"
        exit 1
    fi
}

# 主流程
if [ "$EUID" -ne 0 ]; then
    log_err "请使用 sudo 或 root 用户运行"
    exit 1
fi

if [ -z "${GIT_REPO:-}" ]; then
    log_err "环境变量 GIT_REPO 未设置"
    exit 1
fi

echo ""
echo -e "${GREEN}DB-Genius 裸机部署${NC}"
echo "  Git: ${GIT_REPO}"
echo "  部署目录: ${DEPLOY_HOME}"
echo ""

check_commands
check_java
prepare_ssh
check_env_file

jar_path=$(build_jar)
stop_service
deploy_jar "${jar_path}"
start_service

echo ""
log_ok "部署完成"
