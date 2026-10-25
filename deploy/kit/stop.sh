#!/usr/bin/env bash
# 切换走：停掉本项目，把服务器让给另一个项目（部署方案 `部署方案/v1-测试环境与切换.md` 第 6 节）。用 root 运行：
#   sudo bash /opt/forecast/stop.sh
# 做的事：停用自动更新和每天备份的定时器（否则自动更新可能把容器重新拉起来）→ 等正在进行的更新结束 → 备份数据库 → 停止全部容器。
# 只停止（docker compose stop），不删除容器、不删除数据目录 /opt/forecast/data/ 和备份 /opt/forecast/backup/；
# 以后运行 start.sh 原样恢复。不要用 docker compose down -v、docker volume prune、docker system prune --volumes。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
. /opt/forecast/lib.sh

echo "== 1/4 停用定时器"
systemctl disable --now forecast-deploy.timer forecast-backup.timer
echo "  forecast-deploy.timer：$(systemctl is-enabled forecast-deploy.timer 2> /dev/null || true) / $(systemctl is-active forecast-deploy.timer 2> /dev/null || true)"
echo "  forecast-backup.timer：$(systemctl is-enabled forecast-backup.timer 2> /dev/null || true) / $(systemctl is-active forecast-backup.timer 2> /dev/null || true)"

echo "== 2/4 等正在进行的自动更新结束（最多 15 分钟）"
exec 9> "$FC_DIR/state/deploy.lock"
flock -w 900 9 || { echo "等了 15 分钟自动更新还没结束，先不停止；看 /opt/forecast/logs/deploy.log" >&2; exit 1; }

echo "== 3/4 备份数据库"
if compose ps --status running --services 2> /dev/null | grep -qx mysql; then
  bash "$FC_DIR/backup.sh" before-stop
else
  echo "  MySQL 没有在运行，跳过备份"
fi

echo "== 4/4 停止容器（只停止，不删除数据）"
compose stop
log "切换走：本项目已停止（版本 $(current_tag)），定时器已停用"
docker ps -a --filter 'name=^forecast-' --format '  {{.Names}}  {{.Status}}'
echo
echo "完成。80、443 端口已经空出来；数据目录 /opt/forecast/data/ 和备份 /opt/forecast/backup/ 都保留着。"
