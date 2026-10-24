#!/usr/bin/env bash
# 服务器一次性准备（S20，部署方案 v1.1 第 6 节第 3 步）。用 root 运行：sudo bash /opt/forecast/server-setup.sh
# 前提：已经装好 Docker、登录了 GitHub 镜像仓库、把部署工具包放到了 /opt/forecast（部署方案第 6 节前几步）。
# 做的事：装 certbot；建 2G 交换分区；Docker 日志轮转；打开系统自动安全更新；建目录；装 systemd 定时器（先不启动）。
# 可以重复运行。不改服务器系统里的防火墙（防火墙在轻量应用服务器控制台设置，避免把人锁在外面）。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
. /etc/os-release
[ "${VERSION_ID:-}" = "24.04" ] || echo "提醒：本脚本按 Ubuntu 24.04 编写，这台是 ${PRETTY_NAME:-未知}"
command -v docker > /dev/null || { echo "还没有安装 Docker，先做部署方案第 6 节的上一步" >&2; exit 1; }

echo "== 1/6 安装 certbot、自动安全更新"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q certbot unattended-upgrades curl
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF

echo "== 2/6 交换分区 2G（内存只有 2G，兜底用）"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile > /dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=10' > /etc/sysctl.d/90-forecast.conf
sysctl -q --system

echo "== 3/6 Docker 日志轮转（每个容器最多 50MB × 3）"
if [ ! -f /etc/docker/daemon.json ]; then
  cat > /etc/docker/daemon.json <<'CONF'
{ "log-driver": "json-file", "log-opts": { "max-size": "50m", "max-file": "3" } }
CONF
  systemctl restart docker
fi

echo "== 4/6 目录"
cd /opt/forecast
mkdir -p data/mysql data/redis data/storage backup logs state /var/www/certbot
# 后端容器用 uid 10001 运行，证件照片目录要能写
chown -R 10001 data/storage
chmod 700 backup state
[ -f .env ] || { cp env.example .env; echo "已从 env.example 建好 .env（空值），接下来由 Amos 填写"; }
chmod 600 .env
chmod 755 ./*.sh

echo "== 5/6 systemd 定时器（自动更新每分钟一次、每天 UTC 19:30 = 北京时间 03:30 备份）"
cp systemd/forecast-*.service systemd/forecast-*.timer /etc/systemd/system/
systemctl daemon-reload

echo "== 6/6 检查"
docker --version
docker compose version
certbot --version
free -m | head -2
df -h / | tail -1
echo "准备完成。下一步：填好 /opt/forecast/.env，再运行 sudo bash /opt/forecast/first-start.sh"
