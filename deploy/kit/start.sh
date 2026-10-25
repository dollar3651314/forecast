#!/usr/bin/env bash
# 切换到本项目：启动已经部署过的测试环境（部署方案 `部署方案/v1-测试环境与切换.md` 第 5.3 节）。用 root 运行：
#   sudo bash /opt/forecast/start.sh
# 前提：另一个项目已经按部署方案第 4 节停掉（容器停了、它的 deploy / backup 定时器停用了）；本机第一次部署用 first-start.sh，不用本脚本。
# 做的事：启动前检查（preflight.sh）→ 用上次运行的版本启动全部容器 → 3 分钟内健康检查通过并且版本号对得上 → 打开自动更新和每天备份。
# 数据目录 /opt/forecast/data/ 原样使用，不会清空。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
. /opt/forecast/lib.sh

cur=$(current_tag)
[ -n "$cur" ] || { echo "没有记录过运行的版本（state/image.env）：这台机器还没部署过，先运行 first-start.sh" >&2; exit 1; }

echo "== 1/3 启动前检查"
bash "$FC_DIR/preflight.sh" || { echo "启动前检查没有通过，没有启动任何容器" >&2; exit 1; }

echo "== 2/3 启动版本 $cur（第一次启动 MySQL 约 30 秒）"
exec 9> "$FC_DIR/state/deploy.lock"
flock 9
compose up -d --remove-orphans
if wait_healthy "$cur"; then
  log "切换到本项目：$cur 已启动，健康检查通过"
else
  log "切换到本项目：$cur 在 3 分钟内没有通过健康检查；容器保持运行，定时器没有打开，请看 docker compose logs"
  exit 1
fi
flock -u 9

echo "== 3/3 打开自动更新（每分钟）和每天备份"
systemctl enable --now forecast-deploy.timer forecast-backup.timer
systemctl list-timers 'forecast-*' --no-pager

echo
echo "完成。当前版本：$cur（如果 staging 上有更新的版本，自动更新会在 1 分钟内换上）"
