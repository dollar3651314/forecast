#!/usr/bin/env bash
# 以端到端测试模式（profile e2e）在后台启动后端，等健康检查通过（S19）。
# 用法：bash e2e/start-backend.sh   （先 mvn -f backend package -DskipTests）
# 需要的环境变量：FC_DB_URL、FC_DB_USERNAME、FC_DB_PASSWORD（专用的空库，例如 forecast_e2e）、FC_REDIS_HOST；
# FC_ADMIN_INIT_USERNAME、FC_ADMIN_INIT_PASSWORD（第一个超管，一次性测试值）。
# 日志写到 e2e/logs/backend.log（失败时 CI 作为附件上传）。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p e2e/logs
jar=$(ls backend/target/forecast-*.jar | grep -v plain | head -1)
export SPRING_PROFILES_ACTIVE=e2e
export FC_E2E_ENABLED=true
# 本机和 CI 都是 http，登录 Cookie 不能只走 HTTPS
export FC_COOKIE_SECURE=false
export FC_STORAGE_DIR="${FC_STORAGE_DIR:-$PWD/e2e/logs/storage}"
# 邮件里的链接（#23 开赛提醒，forecast S11）指向端到端测试站点
export FC_CLIENT_URL="${FC_CLIENT_URL:-http://127.0.0.1:${E2E_PORT:-4173}}"
nohup java -jar "$jar" > e2e/logs/backend.log 2>&1 &
echo $! > e2e/logs/backend.pid
for i in $(seq 1 90); do
  if curl -sf http://127.0.0.1:${FC_HTTP_PORT:-8080}/api/health > /dev/null; then
    echo "后端已启动（e2e profile），pid $(cat e2e/logs/backend.pid)"
    exit 0
  fi
  if ! kill -0 "$(cat e2e/logs/backend.pid)" 2> /dev/null; then
    echo "后端启动失败，日志最后 80 行：" >&2
    tail -80 e2e/logs/backend.log >&2
    exit 1
  fi
  sleep 2
done
echo "等了 180 秒后端还没有通过健康检查，日志最后 80 行：" >&2
tail -80 e2e/logs/backend.log >&2
exit 1
