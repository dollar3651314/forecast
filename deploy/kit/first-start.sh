#!/usr/bin/env bash
# 第一次启动（复制源 S20；forecast 的步骤见 `部署方案/v1-测试环境与切换.md` 第 5.2 节）。用 root 运行：sudo bash /opt/forecast/first-start.sh
# 前提：同一台服务器上的另一个项目已经停掉（部署方案第 4 节）；server-setup.sh 已运行；.env 已填好；三个域名（客户端、后台、官网 WWW_DOMAIN）已经解析到这台服务器；防火墙已开 80、443。
# 已经启动过的服务器加官网域名时不用重跑本脚本，按部署方案第 14 节扩展证书。
# 做的事：检查 .env → 申请 HTTPS 证书 → 启动 staging 最新版本 → 证书改为自动续期 → 打开自动更新和每天备份。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
cd /opt/forecast

echo "== 0/5 启动前检查（另一个项目已停、磁盘、内存，部署方案 \`部署方案/v1-测试环境与切换.md\` 第 4、5 节）"
bash ./preflight.sh || { echo "启动前检查没有通过" >&2; exit 1; }

echo "== 1/5 检查 .env（只检查有没有填，不显示值）"
missing=0
for k in APP_DOMAIN ADMIN_DOMAIN WWW_DOMAIN CERT_EMAIL MYSQL_ROOT_PASSWORD FC_DB_PASSWORD FC_REDIS_PASSWORD FC_ADMIN_INIT_USERNAME FC_ADMIN_INIT_PASSWORD; do
  v=$(sed -n "s/^$k=//p" .env | tail -1)
  if [ -z "$v" ]; then echo "  缺少：$k"; missing=1; fi
  case "$v" in *[\ \$\"\'\`\\]*) echo "  $k 里有空格、引号、\$ 或反斜杠，请换成只有字母和数字的值"; missing=1 ;; esac
done
[ "$missing" = 0 ] || exit 1
chmod 600 .env
APP_DOMAIN=$(sed -n 's/^APP_DOMAIN=//p' .env | tail -1)
ADMIN_DOMAIN=$(sed -n 's/^ADMIN_DOMAIN=//p' .env | tail -1)
WWW_DOMAIN=$(sed -n 's/^WWW_DOMAIN=//p' .env | tail -1)
CERT_EMAIL=$(sed -n 's/^CERT_EMAIL=//p' .env | tail -1)
echo "  .env 齐全；域名：$APP_DOMAIN、$ADMIN_DOMAIN、$WWW_DOMAIN"

echo "== 2/5 申请 HTTPS 证书（Let's Encrypt）"
if [ ! -f /etc/letsencrypt/live/forecast/fullchain.pem ]; then
  # 一张证书包含三个域名
  certbot certonly --standalone -n --agree-tos -m "$CERT_EMAIL" --cert-name forecast -d "$APP_DOMAIN" -d "$ADMIN_DOMAIN" -d "$WWW_DOMAIN"
else
  echo "  已有证书，跳过（证书里缺官网域名时按部署方案第 14 节扩展）"
fi

echo "== 3/5 启动 staging 最新版本（第一次要下载镜像，约 2–5 分钟）"
bash ./deploy.sh || { echo "启动失败，看 /opt/forecast/logs/deploy.log" >&2; exit 1; }

echo "== 4/5 证书改为通过 Nginx 自动续期（续期后自动重新加载 Nginx）"
certbot reconfigure -n --cert-name forecast --webroot -w /var/www/certbot \
  --deploy-hook "docker exec forecast-web-1 nginx -s reload"

echo "== 5/5 打开自动更新（每分钟）和每天备份"
systemctl enable --now forecast-deploy.timer forecast-backup.timer
systemctl list-timers 'forecast-*' --no-pager

echo
echo "完成。当前版本：$(sed -n 's/^IMAGE_TAG=//p' state/image.env)"
echo "请打开 https://$ADMIN_DOMAIN 用超级管理员登录并修改初始密码；之后可以把 .env 里的 FC_ADMIN_INIT_USERNAME、FC_ADMIN_INIT_PASSWORD 删掉。"
