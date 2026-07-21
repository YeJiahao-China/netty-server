@echo off
REM =============================================================================
REM  netty-server 启动脚本（Windows）
REM
REM  用途：
REM    1. 强制把工作目录切回项目根，保证 application.yml 中
REM       netty.server.protocol.jar-dir: ./protocols 始终指向根目录的 protocols/
REM    2. 启动 netty-launcher 编译出的 fat jar
REM =============================================================================

REM 切回项目根（脚本所在目录的上一级）
cd /d "%~dp0\.."

set "PROJECT_ROOT=%CD%"
echo [start] 项目根目录: %PROJECT_ROOT%
echo [start] 工作目录已锁定，protocols/ 路径解析为: %PROJECT_ROOT%\protocols

set "JAR=netty-launcher\target\netty-launcher.jar"

if not exist "%JAR%" (
    echo [start] 未找到 %JAR%
    echo [start] 请先执行: mvn clean install -DskipTests
    exit /b 1
)

echo [start] 启动 %JAR% ...
java -jar "%JAR%"
