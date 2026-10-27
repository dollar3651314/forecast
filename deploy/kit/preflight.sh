#!/usr/bin/env bash
# 启动前检查（部署方案 `部署方案/v1-测试环境与切换.md` 第 4、5 节）。只读，不改任何东西。用 root 运行：
#   sudo bash /opt/forecast/preflight.sh
# 测试环境和另一个项目轮流用同一台服务器（AQ-23），同一时间只运行一个项目。启动本项目前确认：
#   1. 没有别的项目的容器占着 80、443、127.0.0.1:8080；
#   2. 别的项目的自动更新、备份定时器已经停用（否则它会每分钟把自己的容器拉起来，抢端口和内存）；
#   3. 磁盘剩余空间够（镜像、数据库、备份）；
#   4. 内存：别的项目停掉以后，系统和 Docker 自己用的内存在内存总账的假设以内。
# first-start.sh、start.sh 启动前都会先运行本脚本；不通过就不启动。全部通过时最后一行是"检查通过"。
set -uo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }

# 内存总账（部署方案第 3 节）：四个容器上限合计 992 MB；系统和 Docker 按 450 MB 算；余量至少 150 MB
CONTAINERS_MB=992
SYSTEM_MB=450
MARGIN_MB=150
# 磁盘：第一次拉镜像约 1.5 GB，数据库和 7 天备份开始时很小；低于 5 GB 不启动，低于 10 GB 提醒
DISK_MIN_GB=5
DISK_WARN_GB=10

fail=0
ok() { echo "  ✓ $1"; }
warn() { echo "  ! $1"; }
bad() { echo "  ✗ $1"; fail=1; }

echo "== 1/4 别的项目的容器"
others=$(docker ps --format '{{.Names}}' | grep -v '^forecast-' || true)
if [ -z "$others" ]; then
  ok "没有别的项目的容器在运行"
else
  bad "这些容器还在运行，先按部署方案第 4 节停掉它们所在的项目：$(echo "$others" | tr '\n' ' ')"
fi
for p in 80 443; do
  holder=$(docker ps --filter "publish=$p" --format '{{.Names}}' | grep -v '^forecast-' || true)
  [ -z "$holder" ] || bad "端口 $p 被容器 $holder 占着"
done
# 不是容器占着端口的情况（例如 certbot --standalone 没退出，或者装了系统自带的 Nginx）
if command -v ss > /dev/null; then
  for p in 80 443; do
    if ss -ltnH "sport = :$p" | grep -q .; then
      if ! docker ps --filter "publish=$p" --format '{{.Names}}' | grep -q '^forecast-'; then
        bad "端口 $p 被非容器程序占着：$(ss -ltnpH "sport = :$p" | head -1)"
      fi
    fi
  done
fi

echo "== 2/4 别的项目的自动更新、备份定时器"
# "别的项目"只认有 <名字>-deploy.timer 的（和 switch-lib.sh 的 other_discover 一样），只查它的 <名字>-deploy.timer、<名字>-backup.timer。
# 不能按 *-backup.timer 宽匹配：Debian / Ubuntu 自带的 dpkg-db-backup.timer 也叫这个名字，它不会拉起任何容器（线上第一次运行时误报过）。
projects=$(systemctl list-unit-files --type=timer --no-legend '*-deploy.timer' 2> /dev/null | awk '{print $1}' \
  | sed -n 's/-deploy\.timer$//p' | grep -vx forecast | sort -u || true)
live=""
for n in $projects; do
  for t in "$n-deploy.timer" "$n-backup.timer"; do
    if [ "$(systemctl is-enabled "$t" 2> /dev/null)" = enabled ] || systemctl is-active -q "$t" 2> /dev/null; then live="$live $t"; fi
  done
done
live=$(echo "$live" | tr ' ' '\n' | sed '/^$/d' | sort -u | tr '\n' ' ' | sed 's/ $//')
if [ -z "$live" ]; then
  ok "没有启用中的别的项目的 deploy / backup 定时器"
else
  bad "这些定时器还启用着（会把别的项目自动拉起来），先 systemctl disable --now：$live"
fi
cron=$(crontab -l 2> /dev/null | grep -v '^#' | grep -E 'deploy\.sh|docker' | grep -v '/opt/forecast/' || true)
[ -z "$cron" ] || bad "root 的 crontab 里有别的项目的部署命令，先注释掉：$cron"

echo "== 3/4 磁盘"
avail=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
if [ "${avail:-0}" -lt "$DISK_MIN_GB" ]; then
  bad "根分区只剩 ${avail} GB（至少要 ${DISK_MIN_GB} GB）。可以先 docker image prune -f 清掉没有标签的旧镜像，不要删数据目录"
elif [ "$avail" -lt "$DISK_WARN_GB" ]; then
  warn "根分区剩 ${avail} GB，低于建议的 ${DISK_WARN_GB} GB；能启动，但请留意"
else
  ok "根分区剩 ${avail} GB"
fi

echo "== 4/4 内存（free -m）"
read -r total used < <(free -m | awk '/^Mem:/ {print $2, $3}')
running_fc=$(docker ps --format '{{.Names}}' | grep -c '^forecast-' || true)
echo "  总量 ${total} MB，已用 ${used} MB（本项目运行中的容器：${running_fc} 个）"
need=$((CONTAINERS_MB + SYSTEM_MB + MARGIN_MB))
if [ "$total" -lt "$need" ]; then
  bad "总内存 ${total} MB 小于 容器上限 ${CONTAINERS_MB} + 系统 ${SYSTEM_MB} + 余量 ${MARGIN_MB} = ${need} MB，按部署方案第 3 节重算"
else
  ok "总内存够：容器上限 ${CONTAINERS_MB} + 系统 ${SYSTEM_MB} + 余量 ${MARGIN_MB} = ${need} MB ≤ ${total} MB"
fi
if [ "$running_fc" = 0 ] && [ -z "$others" ]; then
  # 没有任何容器在运行：已用的就是系统和 Docker 自己
  if [ "$used" -gt "$SYSTEM_MB" ]; then
    warn "没有容器运行时已用 ${used} MB，超过内存总账里系统的 ${SYSTEM_MB} MB；能启动，但请截图 free -m 发给运维重算"
  else
    ok "没有容器运行时已用 ${used} MB，在系统 ${SYSTEM_MB} MB 的假设以内"
  fi
fi

echo
if [ "$fail" = 0 ]; then echo "检查通过"; else echo "检查没有通过：按上面打 ✗ 的项处理后再运行"; fi
exit "$fail"
