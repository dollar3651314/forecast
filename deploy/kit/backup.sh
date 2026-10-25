#!/usr/bin/env bash
# 备份数据库（S20，部署方案 v1.1 D5）。每天由 forecast-backup.timer 运行；deploy.sh 每次更新前也运行。
# 备份放在 /opt/forecast/backup/，保留 7 天。用法：backup.sh [名字]
set -euo pipefail
. /opt/forecast/lib.sh

name=${1:-daily}
f="$FC_DIR/backup/forecast-$(date -u +%Y%m%dT%H%M%SZ)-$name.sql.gz"
# 密码用容器里已有的环境变量，不出现在命令行
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --set-gtid-purged=OFF --databases forecast' \
  | gzip > "$f.tmp"
mv "$f.tmp" "$f"
chmod 600 "$f"
find "$FC_DIR/backup" -name 'forecast-*.sql.gz' -mtime +7 -delete
log "备份完成：$(basename "$f")（$(du -h "$f" | cut -f1)）"
