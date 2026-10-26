#!/usr/bin/env bash
# 用备份恢复（S20，部署方案 v1.1 第 10 节；forecast S12 加对象存储目录）。用法：
#   sudo bash /opt/forecast/restore.sh /opt/forecast/backup/<文件名>.sql.gz
# 恢复数据库；同一前缀的 <文件名>.storage.tgz 存在时，同时把对象存储目录（证件、裁决截图）恢复到备份时的内容。
# 会先停后端、删掉现在的数据库再导入，**备份之后的数据会丢失**。恢复前会再备份一次现在的数据（名字 before-restore）。
# 文件只增不改：恢复文件时把备份里的文件放回去（缺的补上、被改坏的换回来），备份之后才新增的文件留着不删（数据库里已经没有记录引用它们，
# 不影响使用；要彻底回到备份时的样子，用 before-restore 那份对照着手工处理）。
set -euo pipefail
. "${FC_DIR:-/opt/forecast}/lib.sh"

f=${1:-}
case "$f" in
  *.sql.gz) ;;
  *.storage.tgz) f=${f%.storage.tgz}.sql.gz ;;
esac
[ -f "$f" ] || { echo "用法：restore.sh <备份文件 .sql.gz>；现有备份：" >&2; ls -1t "$FC_DIR/backup" >&2; exit 2; }
files=${f%.sql.gz}.storage.tgz
if [ -f "$files" ]; then
  echo "将用 $(basename "$f") 覆盖测试环境的数据库，并用 $(basename "$files") 恢复证件和截图；备份之后的数据会丢失。"
else
  echo "将用 $(basename "$f") 覆盖测试环境的数据库，备份之后的数据会丢失。"
  echo "注意：没有找到同一次备份的文件包 $(basename "$files")（S12 以前的备份只有数据库），证件和截图保持现状。"
fi
read -r -p "确认请输入 RESTORE：" answer
[ "$answer" = "RESTORE" ] || { echo "已取消"; exit 1; }

bash "$FC_DIR/backup.sh" before-restore
compose stop app
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "DROP DATABASE IF EXISTS forecast; CREATE DATABASE forecast CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'
gunzip -c "$f" | compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
if [ -f "$files" ]; then
  # 和 backup.sh 一样用后端镜像（uid 10001）解包，属主和后端一致
  compose run --rm --no-deps -T --entrypoint tar app -C /data -xzf - < "$files"
  log "已恢复文件：$(basename "$files")"
fi
compose start app
# 等的是当前镜像里记录的提交号（服务器上就是 state/image.env 的版本号）
if wait_healthy "$(revision "$REG-app:$(current_tag)")"; then log "已用 $(basename "$f") 恢复"; else log "恢复后后端没有通过健康检查，需要人工处理"; exit 1; fi
