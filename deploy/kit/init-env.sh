#!/usr/bin/env bash
# 在服务器上生成 .env 里不需要 Amos 提供的值（S20，部署方案 v1.3 第 6 节）。用 root 运行：
#   sudo bash /opt/forecast/init-env.sh <客户端域名> <后台域名> <证书提醒邮箱> [官网域名]
# 生成：MYSQL_ROOT_PASSWORD、FC_DB_PASSWORD、FC_REDIS_PASSWORD、浏览器推送密钥对（VAPID）、第一个超级管理员（用户名 admin、随机初始密码）。
# - 只填**空着的**变量，已经有值的不动，可以重复运行。
# - 生成的值直接写进 /opt/forecast/.env（权限 600），**屏幕上不显示**；超管初始密码要看时由 Amos 自己运行：
#     sudo grep ^FC_ADMIN_INIT_PASSWORD= /opt/forecast/.env
# - 邮件（FC_MAIL_*）、区块链（FC_WALLET_*）由 Amos 自己填。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
# FC_DIR 只用于在开发机上演练（S12），服务器上不设
cd "${FC_DIR:-/opt/forecast}"
[ -f .env ] || cp env.example .env
chmod 600 .env

# 把 KEY 设成 VALUE（只在现在为空时）；值只含字母、数字、-、_、.、@、:，不会破坏 .env
set_if_empty() {
  local key=$1 val=$2 cur
  cur=$(sed -n "s/^$key=//p" .env | tail -1)
  [ -n "$cur" ] && { echo "  $key：已有值，不改"; return; }
  if grep -q "^$key=" .env; then
    sed -i "s|^$key=.*|$key=$val|" .env
  else
    echo "$key=$val" >> .env
  fi
  echo "  $key：已生成"
}
rand() { openssl rand -hex "$1" | tr -d '\n'; }
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }

[ $# -ge 3 ] && {
  set_if_empty APP_DOMAIN "$1"
  set_if_empty ADMIN_DOMAIN "$2"
  set_if_empty CERT_EMAIL "$3"
}
# 官网域名（第 4 个参数；forecast 第一次部署时就要给，first-start.sh 申请证书要三个域名）
[ $# -ge 4 ] && set_if_empty WWW_DOMAIN "$4"

set_if_empty MYSQL_ROOT_PASSWORD "$(rand 24)"
set_if_empty FC_DB_PASSWORD "$(rand 24)"
set_if_empty FC_REDIS_PASSWORD "$(rand 24)"
set_if_empty FC_ADMIN_INIT_USERNAME admin
# 后台密码规则：至少 10 位、有大小写字母和数字
set_if_empty FC_ADMIN_INIT_PASSWORD "Fx$(rand 10)A9"

# 浏览器推送密钥对（P-256）：私钥 32 字节、公钥 65 字节（未压缩），都用 base64url，和后端 WebPush 的格式一致。测试环境单独一对
if [ -z "$(sed -n 's/^FC_PUSH_VAPID_PRIVATE_KEY=//p' .env | tail -1)" ]; then
  tmp=$(mktemp)
  openssl ecparam -name prime256v1 -genkey -noout -out "$tmp" 2> /dev/null
  priv=$(openssl ec -in "$tmp" -outform DER 2> /dev/null | tail -c +8 | head -c 32 | b64url)
  pub=$(openssl ec -in "$tmp" -pubout -outform DER 2> /dev/null | tail -c 65 | b64url)
  rm -f "$tmp"
  sed -i '/^FC_PUSH_VAPID_PUBLIC_KEY=/d; /^FC_PUSH_VAPID_PRIVATE_KEY=/d' .env
  printf 'FC_PUSH_VAPID_PUBLIC_KEY=%s\nFC_PUSH_VAPID_PRIVATE_KEY=%s\n' "$pub" "$priv" >> .env
  unset priv pub
  echo "  FC_PUSH_VAPID_PUBLIC_KEY、FC_PUSH_VAPID_PRIVATE_KEY：已生成"
else
  echo "  FC_PUSH_VAPID_*：已有值，不改"
fi
cert=$(sed -n 's/^CERT_EMAIL=//p' .env | tail -1)
[ -n "$cert" ] && set_if_empty FC_PUSH_VAPID_SUBJECT "mailto:$cert"

echo
echo "完成。值都在 /opt/forecast/.env（只有 root 能读），这里不显示。"
echo "还空着的（邮件、区块链等，之后由 Amos 填）："
grep -E '^[A-Z0-9_]+=$' .env | cut -d= -f1 | tr '\n' ' '; echo
