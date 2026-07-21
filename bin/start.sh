#!/usr/bin/env bash
# =============================================================================
# netty-server 启动脚本（Linux/macOS/Git Bash）
#
# 用途：
#   1. 强制把工作目录切回项目根，保证 application.yml 中
#      netty.server.protocol.jar-dir: ./protocols 始终指向根目录的 protocols/
#   2. 启动 netty-launcher 编译出的 fat jar
#
# 用法：
#   bash bin/start.sh             # 前台启动
#   nohup bash bin/start.sh &     # 后台启动（日志输出到 nohup.out）
# =============================================================================
set -e

# 切回项目根（脚本所在目录的上一级）
cd "$(dirname "$0")/.."

PROJECT_ROOT="$(pwd)"
echo "[start] 项目根目录: $PROJECT_ROOT"
echo "[start] 工作目录已锁定，protocols/ 路径解析为: $PROJECT_ROOT/protocols"

JAR="netty-launcher/target/netty-launcher.jar"

if [ ! -f "$JAR" ]; then
    echo "[start] 未找到 $JAR"
    echo "[start] 请先执行: mvn clean install -DskipTests"
    exit 1
fi

echo "[start] 启动 $JAR ..."
exec java -jar "$JAR"
