#!/bin/bash

###############################################################################
# DB-Genius Docker 一键部署脚本
#
# 职责划分：
#   - 本脚本：从远程拉取代码（git pull）
#   - Docker：负责打包（Maven）+ 运行（postgres / rabbitmq / app 全套）
#
# 前置条件：
#   - 服务器已安装 Docker（含 compose 插件），无需 JDK / Maven
#   - 本脚本同目录下已放置 .env（从 deploy/.env.example 复制并填写真实值）
#
# 必填环境变量：
#   GIT_REPO          SSH 格式的 Git 仓库地址，例如 git@gitee.com:xxx/db-genius.git
#
# 可选环境变量：
#   DEPLOY_HOME       部署根目录，默认 /opt/db-genius
#   GIT_BRANCH        分支，默认 master
#
# 用法：
#   GIT_REPO=git@gitee.com:xxx/db-genius.git ./deploy/build-docker.sh
###############################################################################

set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEPLOY_HOME="${DEPLOY_HOME:-/opt/db-genius}"
SRC_HOME="${DEPLOY_HOME}/src"
GIT_BRANCH="${GIT_BRANCH:-master}"

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_err()  { echo -e "${RED}[ERROR]${NC} $1" >&2; }

check_commands() {
    log_info "检查必要命令..."
    for cmd in git docker; do
        if ! command -v "${cmd}" &> /dev/null; then
            log_err "未找到命令: ${cmd}"
            exit 1
        fi
    done
    if ! docker compose version &> /dev/null; then
        log_err "未找到 docker compose 插件，请安装 docker-compose-plugin"
        exit 1
    fi
    log_ok "命令检查通过"
}

check_env_file() {
    log_info "检查 .env 文件..."
    local env_file="${SCRIPT_DIR}/.env"
    if [ ! -f "${env_file}" ]; then
        log_err "未找到 ${env_file}"
        echo "请将 deploy/.env.example 复制为 deploy/.env 并填入真实值。"
        exit 1
    fi
    # shellcheck source=/dev/null
    source "${env_file}"
    local required=("DB_GENIUS_DEFAULT_MODEL_API_KEY" "DB_GENIUS_ENCRYPT_KEY")
    for var in "${required[@]}"; do
        if [ -z "${!var:-}" ]; then
            log_err ".env 中 ${var} 不能为空"
            exit 1
        fi
    done
    log_ok ".env 文件检查通过"
}

sync_source() {
    log_info "拉取代码（分支: ${GIT_BRANCH}）..."
    if [ -d "${SRC_HOME}/.git" ]; then
        git -C "${SRC_HOME}" fetch --depth 1 origin "${GIT_BRANCH}"
        git -C "${SRC_HOME}" reset --hard "origin/${GIT_BRANCH}"
    else
        mkdir -p "$(dirname "${SRC_HOME}")"
        git clone --depth 1 --branch "${GIT_BRANCH}" "${GIT_REPO}" "${SRC_HOME}"
    fi
    log_ok "代码已同步到: ${SRC_HOME}"
}

deploy() {
    log_info "构建镜像并启动全套容器..."
    # 把 .env 放到 compose 目录，供 ${VAR} 插值 + app 服务读取
    cp "${SCRIPT_DIR}/.env" "${SRC_HOME}/.env"
    cd "${SRC_HOME}"
    docker compose up -d --build
    log_ok "容器已启动"
    echo ""
    docker compose ps
    echo ""
    echo "常用命令（在 ${SRC_HOME} 目录下执行）:"
    echo "  docker compose logs -f app     # 查看后端日志"
    echo "  docker compose ps              # 查看状态"
    echo "  docker compose restart app     # 重启后端"
    echo "  docker compose down            # 停止（加 -v 连数据卷一起删）"
}

# 主流程
if [ -z "${GIT_REPO:-}" ]; then
    log_err "环境变量 GIT_REPO 未设置"
    exit 1
fi

echo ""
echo -e "${GREEN}DB-Genius Docker 部署${NC}"
echo "  Git: ${GIT_REPO} (${GIT_BRANCH})"
echo "  部署目录: ${DEPLOY_HOME}"
echo ""

check_commands
check_env_file
sync_source
deploy

echo ""
log_ok "部署完成"
