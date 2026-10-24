#!/usr/bin/env bash
# 自动更新（S20，部署方案 v1.1 第 10 节）。systemd 定时器每分钟运行一次（forecast-deploy.timer）。
# 看 GitHub 镜像仓库里 staging 标签指向的版本；和正在运行的不同，就：同步部署工具包 → 备份数据库 → 换新版本 →
# 3 分钟内健康检查通过（并且版本号对得上）算成功；不通过自动换回上一个版本，并记下这个版本不再重试。
set -euo pipefail
. /opt/forecast/lib.sh

exec 9> "$FC_DIR/state/deploy.lock"
flock -n 9 || exit 0

pull "$REG-app:staging"
new=$(revision "$REG-app:staging")
cur=$(current_tag)
[ -n "$new" ] || { log "staging 镜像没有版本号，跳过"; exit 1; }
[ "$new" = "$cur" ] && exit 0
# 失败过的版本、手动回滚时跳过的版本不再重试，等下一次合并
[ "$new" = "$(cat "$FC_DIR/state/skip" 2>/dev/null || true)" ] && exit 0

log "发现新版本 $new（当前 ${cur:-无}）"
pull "$REG-app:$new"
pull "$REG-web:$new"
sync_kit "$new"
if [ -n "$cur" ]; then
  bash "$FC_DIR/backup.sh" "before-${new:0:12}"
fi
switch_to "$new"
if wait_healthy "$new"; then
  [ -n "$cur" ] && echo "$cur" > "$FC_DIR/state/previous"
  log "更新成功：$new"
  prune_images
  exit 0
fi

log "更新失败：$new 在 3 分钟内没有通过健康检查。后端最后 50 行日志："
compose logs --no-color --tail 50 app >> "$FC_DIR/logs/deploy.log" 2>&1 || true
echo "$new" > "$FC_DIR/state/skip"
if [ -n "$cur" ]; then
  switch_to "$cur"
  if wait_healthy "$cur"; then log "已自动换回 $cur"; else log "换回 $cur 后仍然没有通过健康检查，需要人工处理"; fi
fi
exit 1
