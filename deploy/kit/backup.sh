#!/usr/bin/env bash
# 备份（S20；forecast S12 补上对象存储目录）。每天由 forecast-backup.timer 运行；deploy.sh 每次更新前、stop.sh 切走前、restore.sh 恢复前也运行。
# 用法：backup.sh [名字]。同一次备份是同一个前缀的两个文件，放在 /opt/forecast/backup/，权限 600，保留 7 天：
#   forecast-<时间>-<名字>.sql.gz       数据库（mysqldump，一个一致的快照）
#   forecast-<时间>-<名字>.storage.tgz  对象存储目录 data/storage/：KYC 证件（kyc/）、裁决台的存档截图（resolve/）
#     （架构方案第 11.6 节"文件随数据库备份一起打包"；分享卡片、内容程序的图片是按数据现画的，不落盘，不用备份）
# 先导出数据库、再打包文件：文件只增不改（证件、截图都不覆盖、不删除），晚打包的文件只会比数据库多、不会少，数据库里引用的文件都在。
# 恢复用 restore.sh（数据库和同一前缀的文件一起恢复）。
set -euo pipefail
. "${FC_DIR:-/opt/forecast}/lib.sh"

name=${1:-daily}
case "$name" in *[!A-Za-z0-9._-]*) echo "备份名字只能有字母、数字和 . _ -" >&2; exit 2 ;; esac
base="$FC_DIR/backup/forecast-$(date -u +%Y%m%dT%H%M%SZ)-$name"
umask 077

# 1) 数据库。密码用容器里已有的环境变量，不出现在命令行
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --set-gtid-purged=OFF --databases forecast' \
  | gzip > "$base.sql.gz.tmp"
mv "$base.sql.gz.tmp" "$base.sql.gz"
chmod 600 "$base.sql.gz"

# 2) 对象存储目录。用后端镜像临时起一个容器来打包（和后端同一个用户 uid 10001、同一个挂载 ./data/storage:/data/storage），
#    后端在不在运行都能打包，恢复时文件的属主也对；不启动 Java，用完就删（--rm）
storage_tar() {
  compose run --rm --no-deps -T --entrypoint tar app -C /data -czf - storage
}
storage_tar > "$base.storage.tgz.tmp"
mv "$base.storage.tgz.tmp" "$base.storage.tgz"
chmod 600 "$base.storage.tgz"
files=$(gzip -cd "$base.storage.tgz" | tar -tf - | grep -vc '/$' || true)

find "$FC_DIR/backup" \( -name 'forecast-*.sql.gz' -o -name 'forecast-*.storage.tgz' \) -mtime +7 -delete
log "备份完成：$(basename "$base").sql.gz（$(du -h "$base.sql.gz" | cut -f1)）、.storage.tgz（${files} 个文件，$(du -h "$base.storage.tgz" | cut -f1)）"
