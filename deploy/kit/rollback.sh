#!/usr/bin/env bash
# 手动回滚到指定版本（S20，部署方案 v1.1 第 10 节）。用法：sudo /opt/forecast/rollback.sh <提交号>
# 不带参数时回到上一个版本（state/previous）。回滚后自动更新会跳过 staging 当前的版本，下一次合并时恢复。
# 推荐的回滚方式仍然是在仓库里 revert 后合并到 staging（不用登录服务器）。数据库不会跟着回退，要回退数据用 restore.sh。
set -euo pipefail
. /opt/forecast/lib.sh

exec 9> "$FC_DIR/state/deploy.lock"
flock 9

target=${1:-$(cat "$FC_DIR/state/previous" 2>/dev/null || true)}
[ -n "$target" ] || { echo "用法：rollback.sh <提交号>（没有记录上一个版本）" >&2; exit 2; }
cur=$(current_tag)
pull "$REG-app:$target"
pull "$REG-web:$target"
log "手动回滚：$cur → $target"
pull "$REG-app:staging" && revision "$REG-app:staging" > "$FC_DIR/state/skip"
switch_to "$target"
if wait_healthy "$target"; then
  echo "$cur" > "$FC_DIR/state/previous"
  log "回滚成功：$target"
else
  log "回滚后 $target 没有通过健康检查，需要人工处理"
  exit 1
fi
