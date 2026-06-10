#!/usr/bin/env bash
# 币安分析系统 启动/停止/状态 脚本 (Ubuntu, java -jar)
# 用法: ./start.sh [start|stop|restart|status|log]
#   start   后台启动 (默认, 不带参数即 start)
#   stop    停止
#   restart 重启
#   status  查看运行状态
#   log     实时跟踪日志 (Ctrl+C 退出查看, 不影响进程)
set -euo pipefail

# ===== 配置 (按需修改) =====
MYSQL_PASSWORD='改成你的数据库密码'   # 数据库密码; 含特殊字符务必保留单引号
JAR='binance-analyzer-1.0.0.jar'      # 打包产物 (mvn package -DskipTests 后在 target/ 下)
LOG='app.log'                         # 日志文件
# JVM 参数: 优先 IPv4 (EC2 等环境 IPv6 出网不通会导致币安请求卡死超时)
JAVA_OPTS='-Djava.net.preferIPv4Stack=true'

# 切到脚本所在目录, 保证相对路径稳定
cd "$(dirname "$0")"

pid_of() {
  pgrep -f "java -jar .*${JAR}" || true
}

start() {
  local pid; pid=$(pid_of)
  if [ -n "$pid" ]; then
    echo "已在运行 (PID $pid), 不重复启动。"
    exit 0
  fi
  if [ ! -f "$JAR" ]; then
    echo "找不到 $JAR — 先执行 mvn package -DskipTests, 并把 target/$JAR 放到当前目录。"
    exit 1
  fi
  if [ "$MYSQL_PASSWORD" = '改成你的数据库密码' ]; then
    echo "请先编辑本脚本顶部的 MYSQL_PASSWORD。"
    exit 1
  fi
  MYSQL_PASSWORD="$MYSQL_PASSWORD" nohup java $JAVA_OPTS -jar "$JAR" > "$LOG" 2>&1 &
  sleep 1
  echo "已启动 (PID $(pid_of)). 日志: $LOG  (用 ./start.sh log 跟踪)"
}

stop() {
  local pid; pid=$(pid_of)
  if [ -z "$pid" ]; then
    echo "未在运行。"
    return
  fi
  kill "$pid"
  echo "已发送停止信号 (PID $pid)。"
}

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 2; start ;;
  status)
    pid=$(pid_of)
    [ -n "$pid" ] && echo "运行中 (PID $pid)" || echo "未运行"
    ;;
  log)     tail -f "$LOG" ;;
  *)       echo "用法: $0 [start|stop|restart|status|log]"; exit 1 ;;
esac
