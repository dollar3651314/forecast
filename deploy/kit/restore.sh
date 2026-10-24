#!/usr/bin/env bash
# 用备份恢复数据库（S20，部署方案 v1.1 第 10 节）。用法：sudo /opt/forecast/restore.sh /opt/forecast/backup/<文件名>
# 会先停后端、删掉现在的数据库再导入，**备份之后的数据会丢失**。恢复前会再备份一次现在的数据。
set -euo pipefail
. /opt/forecast/lib.sh

f=${1:-}
[ -f "$f" ] || { echo "用法：restore.sh <备份文件>；现有备份：" >&2; ls -1t "$FC_DIR/backup" >&2; exit 2; }
echo "将用 $(basename "$f") 覆盖测试环境的数据库，备份之后的数据会丢失。"
read -r -p "确认请输入 RESTORE：" answer
[ "$answer" = "RESTORE" ] || { echo "已取消"; exit 1; }

bash "$FC_DIR/backup.sh" before-restore
compose stop app
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "DROP DATABASE IF EXISTS forecast; CREATE DATABASE forecast CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'
gunzip -c "$f" | compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
compose start app
if wait_healthy "$(current_tag)"; then log "已用 $(basename "$f") 恢复数据库"; else log "恢复后后端没有通过健康检查，需要人工处理"; exit 1; fi
