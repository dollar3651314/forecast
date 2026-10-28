#!/usr/bin/env bash
# 测试服务器上的切换和填密钥命令（S12 一键部署）。oneclick.sh 第 4 步装成 /usr/local/bin/fc（指向本文件，工具包更新时跟着更新）。用 root 运行：
#   sudo fc status              forecast 和另一个项目各自的状态
#   sudo fc to-<另一个项目>      停 forecast（stop.sh），原样启动另一个项目和它的定时器（也可以写 sudo fc to-other）
#   sudo fc to-forecast         停另一个项目（先停定时器、备份、只停容器），启动 forecast（start.sh）
#   sudo fc keys                可选密钥哪些已填、哪些没填（只显示名字）
#   sudo fc set-key 变量名       粘贴一个可选密钥的值（屏幕上不显示），写进 .env，自动 reload-env.sh 生效
#   sudo fc add-domain 域名      加对外预热域名（S18，例如 www.quickcome.online）：检查 DNS 指向本机 → 扩展证书 → 写进 .env → 重新加载 → 自己验证；可以重复运行
# 另一个项目的名字、目录不写在这里，由 switch-lib.sh 从 systemd 找到（scripts/brand-check.json：部署脚本里不写它的名字）。
# 输出同时写进 /opt/forecast/logs/fc-<时间>.log；任何时候都不显示密钥的值。
# 一定要带 sudo：fc 也是 bash 的内建命令（历史命令编辑），不带 sudo 直接敲 fc 会运行那个内建命令；sudo 运行的是 /usr/local/bin/fc。
set -uo pipefail
FC=/opt/forecast
export FC_HOME=$FC
# shellcheck source=switch-lib.sh
. "$FC/switch-lib.sh"

# 可以用 set-key 填的变量：可选密钥（env.example 里标【可选】的 key / token / 密码）……
SECRET_KEYS="FC_MAIL_PASSWORD FC_GEMINI_API_KEY FC_SRC_FOOTBALL_DATA_TOKEN FC_SRC_BALLDONTLIE_KEY FC_SRC_THESPORTSDB_KEY FC_TELEGRAM_BOT_TOKEN"
# ……和两个收件人（不是密钥，验收前要填，也用这个命令填，免得用 nano）
RECIPIENT_KEYS="FC_ALERT_EMAILS FC_RISK_ALERT_EMAILS"
# 填 FC_MAIL_PASSWORD 时，邮件的公开设置空着就一起填上（Resend，Amos 待办 T3；不是密钥）
MAIL_DEFAULTS="FC_MAIL_HOST=smtp.resend.com FC_MAIL_USERNAME=resend FC_MAIL_FROM=noreply@fc-email.quickcome.online"

LOG="$FC/logs/fc-$(date -u +%Y%m%d-%H%M%S).log"
say() { echo "$*" | tee -a "$LOG"; }
detail() { echo "    $*" | tee -a "$LOG"; }
die() {
  say "✗ $1"
  [ -z "${2:-}" ] || say "    下一步：$2"
  say "    把屏幕截图发我（先遮住密钥）。详细日志：$LOG"
  exit 1
}
envval() { sed -n "s/^$1=//p" "$FC/.env" 2> /dev/null | tail -1; }

usage() {
  local o
  other_discover > /dev/null 2>&1
  o=${OTHER_NAME:-other}
  cat << EOF
用法（都要 sudo）：
  sudo fc status              看 forecast 和 $o 各自在不在运行
  sudo fc to-$o$(printf '%*s' $((14 - ${#o})) '')停 forecast，原样启动 $o（含它的定时器）
  sudo fc to-forecast         停 $o（先停定时器、备份、只停容器），启动 forecast
  sudo fc keys                可选密钥哪些已填、哪些没填（只显示名字）
  sudo fc set-key 变量名       填一个可选密钥（粘贴时屏幕上不显示），自动生效
  sudo fc add-domain 域名      加对外预热域名（例如 www.quickcome.online：根路径是等候页、不显示测试环境标识），可以重复运行
可以填的变量：$SECRET_KEYS $RECIPIENT_KEYS
EOF
}

# ---------- status ----------
cmd_status() {
  local body ver held o
  other_discover > /dev/null 2>&1
  o=${OTHER_NAME:-}
  say "== forecast（$FC）"
  if [ -f "$FC/state/image.env" ]; then
    detail "版本：$(sed -n 's/^IMAGE_TAG=//p' "$FC/state/image.env")"
  else
    detail "还没有部署过"
  fi
  status_of "$FC_LABEL" | sed 's/^/  /' | tee -a "$LOG"
  detail "定时器：forecast-deploy.timer $(systemctl is-enabled forecast-deploy.timer 2> /dev/null || echo 无)，forecast-backup.timer $(systemctl is-enabled forecast-backup.timer 2> /dev/null || echo 无)"
  if [ -n "$o" ]; then
    say "== $o（$OTHER_DIR）"
    detail "版本：$(sed -n 's/^IMAGE_TAG=//p' "$OTHER_DIR/state/image.env" 2> /dev/null || echo 未知)"
    status_of "$o" | sed 's/^/  /' | tee -a "$LOG"
    detail "定时器：$(for t in $(other_timers); do printf '%s %s  ' "$t" "$(systemctl is-enabled "$t" 2> /dev/null)"; done)"
  else
    say "== 没有发现另一个项目"
  fi
  held=$(docker ps --filter publish=443 --format '{{.Names}}' | head -1)
  body=$(lcurl -sf --max-time 5 http://127.0.0.1:8080/api/health || true)
  ver=$(printf '%s' "$body" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
  say "== 现在"
  case "$held" in
    "$FC_LABEL"-*) detail "在运行的是 forecast（$held 占着 443）" ;;
    "") detail "两个项目都没有在运行（443 端口空着）" ;;
    *) detail "在运行的是 ${o:-另一个项目}（$held 占着 443）" ;;
  esac
  case "$body" in
    *'"status":"UP"'*) detail "健康检查：UP（版本 $ver）" ;;
    "") detail "健康检查：没有响应" ;;
    *) detail "健康检查：不是 UP" ;;
  esac
  free -m | sed 's/^/    /' | tee -a "$LOG"
}

# ---------- to-<另一个项目> ----------
cmd_to_other() {
  other_discover > /dev/null 2>&1 || die "没有找到另一个项目（或者找到不止一个）${OTHER_DISCOVER_PROBLEM:+：$OTHER_DISCOVER_PROBLEM}"
  say "切换到 $OTHER_NAME（$(date '+%Y-%m-%d %H:%M:%S %Z')，日志 $LOG）"
  if [ -n "$(running_of "$OTHER_NAME")" ] && [ -z "$(running_of "$FC_LABEL")" ]; then
    say "✓ 已经是 $OTHER_NAME 在运行，什么都不用做"
    status_of "$OTHER_NAME" | tee -a "$LOG"
    return 0
  fi
  if forecast_running || forecast_timers_on; then
    say "  … 第 1 步：停 forecast（停定时器 → 等自动更新结束 → 备份 → 只停容器，约 1 分钟）"
    bash "$FC/stop.sh" >> "$LOG" 2>&1 < /dev/null || die "第 1 步：停 forecast 失败（stop.sh）" "看日志最后几行：sudo tail -20 $LOG"
    say "✓ 第 1 步：停 forecast（定时器已停用、已备份、容器已停止，数据都保留）"
  else
    say "✓ 第 1 步：停 forecast——已经停了，跳过"
  fi
  local busy
  busy=$(port_holders | grep -v " $OTHER_NAME-" || true)
  [ -z "$busy" ] || die "第 2 步：80、443、8080 端口还被占着：$(echo "$busy" | tr '\n' ' ')" "截图发我"
  say "  … 第 2 步：启动 $OTHER_NAME（用它上次运行的版本，约 1～2 分钟）"
  other_start >> "$LOG" 2>&1 || die "第 2 步：启动 $OTHER_NAME：$START_PROBLEM" "截图发我；要回到 forecast 就运行 sudo fc to-forecast"
  say "✓ 第 2 步：启动 $OTHER_NAME（健康检查通过，定时器已按原样打开）"
  status_of "$OTHER_NAME" | tee -a "$LOG"
  say "完成。现在是 $OTHER_NAME 在运行；forecast 的三个域名这时打不开，是正常的。"
}

# ---------- to-forecast ----------
cmd_to_forecast() {
  [ -s "$FC/state/image.env" ] || die "forecast 还没有部署过" "先粘贴一键部署的那条命令（部署方案/v1-测试环境第一次部署清单.md）"
  other_discover > /dev/null 2>&1
  say "切换到 forecast（$(date '+%Y-%m-%d %H:%M:%S %Z')，日志 $LOG）"
  if [ -z "$OTHER_NAME" ]; then
    say "✓ 第 1 步：停另一个项目——没有另一个项目，跳过"
  elif other_is_stopped; then
    say "✓ 第 1 步：停 $OTHER_NAME——已经停了，跳过"
  else
    say "  … 第 1 步：停 $OTHER_NAME（先停定时器 → 备份 → 只停容器，约 1 分钟）"
    other_stop >> "$LOG" 2>&1 || die "第 1 步：停 $OTHER_NAME：$STOP_PROBLEM" "截图发我"
    say "✓ 第 1 步：停 $OTHER_NAME（定时器已停用、已备份、容器已停止，数据都保留）"
  fi
  if forecast_running && forecast_timers_on; then
    say "✓ 第 2 步：启动 forecast——已经在运行，跳过"
  else
    say "  … 第 2 步：启动 forecast（启动前检查 → 用上次的版本启动 → 健康检查，约 1～2 分钟）"
    if ! bash "$FC/start.sh" >> "$LOG" 2>&1 < /dev/null; then
      grep -E '✗' "$LOG" | tail -5 | sed 's/^ */    /'
      die "第 2 步：启动 forecast 失败（start.sh）" "看上面打 ✗ 的项；或者 sudo tail -30 $LOG"
    fi
    say "✓ 第 2 步：启动 forecast（健康检查通过，自动更新和每天备份已打开）"
  fi
  status_of "$FC_LABEL" | tee -a "$LOG"
  say "完成。现在是 forecast 在运行：https://$(envval APP_DOMAIN)"
}

# ---------- keys ----------
cmd_keys() {
  local k n=0
  [ -f "$FC/.env" ] || die "还没有 $FC/.env" "先粘贴一键部署的那条命令"
  say "可选密钥（只显示名字和有没有填）："
  for k in $SECRET_KEYS; do
    if [ -n "$(envval "$k")" ]; then say "  已填  $k"; else say "  未填  $k"; n=$((n + 1)); fi
  done
  say "收件人："
  for k in $RECIPIENT_KEYS; do
    if [ -n "$(envval "$k")" ]; then say "  已填  $k"; else say "  未填  $k"; fi
  done
  say "必填（一键部署自动生成）：$(missing_required)"
  [ "$n" = 0 ] || say "填的方法：sudo fc set-key 变量名（例如 sudo fc set-key FC_MAIL_PASSWORD），粘贴值、回车；屏幕上不显示。"
}

missing_required() {
  local k miss=""
  for k in APP_DOMAIN ADMIN_DOMAIN WWW_DOMAIN MYSQL_ROOT_PASSWORD FC_DB_PASSWORD FC_REDIS_PASSWORD FC_PUSH_VAPID_PUBLIC_KEY FC_PUSH_VAPID_PRIVATE_KEY FC_PUSH_VAPID_SUBJECT; do
    [ -n "$(envval "$k")" ] || miss="$miss $k"
  done
  if [ -z "$miss" ]; then echo "齐全"; else echo "缺$miss"; fi
}

# 把 .env 里 KEY 的值换成 VAL（值从环境变量传给 awk，不出现在命令行上）；没有这一行就加在最后
env_write() {
  local key=$1 tmp
  tmp=$(mktemp "$FC/.env.XXXXXX")
  chmod 600 "$tmp"
  KEY="$key" awk 'BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VAL"] }
    index($0, k "=") == 1 { if (!done) print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }' "$FC/.env" > "$tmp" && mv "$tmp" "$FC/.env"
  chmod 600 "$FC/.env"
}

# 健康检查 alerts 里和这个变量对应的项（只有这几个在 alerts 里）
alert_of() {
  case "$1" in
    FC_SRC_FOOTBALL_DATA_TOKEN) echo source.football_data ;;
    FC_SRC_BALLDONTLIE_KEY) echo source.balldontlie ;;
    FC_SRC_THESPORTSDB_KEY) echo source.thesportsdb ;;
    FC_TELEGRAM_BOT_TOKEN) echo telegram.bot ;;
  esac
}

# ---------- set-key ----------
cmd_set_key() {
  local key=${1:-} val k up d alert body allowed=" $SECRET_KEYS $RECIPIENT_KEYS " pair
  [ -n "$key" ] || die "要写变量名，例如：sudo fc set-key FC_MAIL_PASSWORD" "可以填的：$SECRET_KEYS $RECIPIENT_KEYS"
  if [ "${allowed#* "$key" }" = "$allowed" ] || ! grep -q "^$key=" "$FC/env.example"; then
    up=$(echo "$key" | tr '[:lower:]-' '[:upper:]_')
    case "$allowed" in
      *" $up "*) die "变量名要全部大写：$up" "运行 sudo fc set-key $up" ;;
      *" FC_$up "*) die "变量名前面要有 FC_：FC_$up" "运行 sudo fc set-key FC_$up" ;;
    esac
    say "✗ 不认识变量名：$key"
    say "    可以填的（可选密钥）：$SECRET_KEYS"
    say "    收件人：$RECIPIENT_KEYS"
    say "    其他变量（区块链、合约地址等）按《第一次部署清单》用 sudo nano $FC/.env 填。"
    exit 1
  fi
  [ -f "$FC/.env" ] || die "还没有 $FC/.env" "先粘贴一键部署的那条命令"
  [ -r /dev/tty ] || die "这里不能输入（没有终端）" "在阿里云控制台的'远程连接'终端里运行"
  echo "请粘贴 $key 的值，然后按回车（屏幕上不会显示，这是正常的；不要用控制台的 AI 助手）："
  IFS= read -r -s val < /dev/tty
  echo
  val=$(printf '%s' "$val" | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  if [ -z "$val" ]; then say "没有输入，什么都没有改。"; exit 0; fi
  case "$val" in
    *[[:space:]\"\'\`\$\\]*) die "值里有空格、引号、\$、反引号或反斜杠，.env 里不能这样写；什么都没有改" "确认复制的是完整的 key（不要带引号），再运行一次" ;;
  esac
  if [ "$(envval "$key")" = "$val" ]; then
    unset val
    say "和原来的值一样，没有改。"
    exit 0
  fi
  VAL="$val" env_write "$key"
  unset val
  say "✓ 已设置 $key（不显示值）"
  if [ "$key" = FC_MAIL_PASSWORD ]; then
    for pair in $MAIL_DEFAULTS; do
      k=${pair%%=*} d=${pair#*=}
      if [ -z "$(envval "$k")" ]; then VAL="$d" env_write "$k"; say "  同时填好了邮件的公开设置 $k=$d"; fi
    done
  fi
  if ! forecast_running; then
    say "forecast 现在没有在运行（服务器给了另一个项目），下次 sudo fc to-forecast 启动时生效。"
    exit 0
  fi
  say "  … 让它生效（reload-env.sh：只重建配置变了的容器，数据不动，约 1～2 分钟）"
  if ! bash "$FC/reload-env.sh" >> "$LOG" 2>&1 < /dev/null; then
    die "改了以后后端 3 分钟内没有通过健康检查" "截图发我；也可以再运行一次 sudo fc set-key $key 换回原来的值"
  fi
  body=$(lcurl -sf --max-time 5 http://127.0.0.1:8080/api/health || true)
  say "✓ 已生效，健康检查 UP"
  alert=$(alert_of "$key")
  if [ -n "$alert" ]; then
    case "$body" in
      *"\"$alert\":\"UP\""*) say "  $alert：UP ✓" ;;
      *"\"$alert\":\"DOWN\""*) say "  $alert：还是 DOWN（刚启动时可能要等几分钟第一次调用；过 10 分钟再 sudo fc status 看，还是 DOWN 就截图发我）" ;;
    esac
  fi
  [ "$key" != FC_MAIL_PASSWORD ] || say "  邮件：在客户端注册一个账号，能收到验证码就是好了。"
  [ "$key" != FC_GEMINI_API_KEY ] || say "  Gemini：后台'内容中心'不再显示'Gemini 未配置'就是好了。"
}

# ---------- add-domain（S18，P1）----------
# 本机的公网 IP（和 oneclick.sh 第 1 步同一个查法；FC_PUBLIC_IP 只用于开发机演练）
public_ip() {
  local ip=${FC_PUBLIC_IP:-} u
  if [ -z "$ip" ]; then
    ip=$(curl -s -4 --max-time 3 http://100.100.100.200/latest/meta-data/eipv4 2> /dev/null | grep -E '^[0-9.]+$' || true)
    for u in https://api.ipify.org https://ifconfig.me/ip https://checkip.amazonaws.com; do
      [ -n "$ip" ] && break
      ip=$(curl -s -4 --max-time 5 "$u" 2> /dev/null | tr -d '[:space:]' | grep -E '^[0-9]+(\.[0-9]+){3}$' || true)
    done
  fi
  echo "$ip"
}

cert_sans() {
  openssl x509 -noout -ext subjectAltName -in /etc/letsencrypt/live/forecast/fullchain.pem 2> /dev/null | tr ',' '\n' | sed -n 's/.*DNS:\(.*\)/\1/p' | tr -d ' ' | tr '\n' ' '
}

cmd_add_domain() {
  local d=${1:-} cur pub resolved sans web args=() s code page body loc problems=()
  d=$(printf '%s' "$d" | tr '[:upper:]' '[:lower:]')
  [ -n "$d" ] || die "要写域名，例如：sudo fc add-domain www.quickcome.online"
  printf '%s' "$d" | grep -Eq '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$' \
    || die "域名写法不对：$d（只写域名，不要 https:// 和斜杠）" "例如 sudo fc add-domain www.quickcome.online"
  [ -f "$FC/.env" ] || die "还没有 $FC/.env" "先粘贴一键部署的那条命令"
  for k in APP_DOMAIN ADMIN_DOMAIN WWW_DOMAIN; do
    [ "$(envval "$k")" != "$d" ] || die "$d 已经是 $k（测试环境自己的域名），不能当预热域名"
  done
  cur=$(envval PRELAUNCH_DOMAIN)
  if [ -n "$cur" ] && [ "$cur" != "$d" ]; then
    die "已经配了预热域名 $cur，只能有一个" "要换成 $d：先把 $FC/.env 里 PRELAUNCH_DOMAIN= 这一行改成空，再运行 sudo fc add-domain $d（截图发我确认再做）"
  fi
  say "加对外预热域名 $d（$(date '+%Y-%m-%d %H:%M:%S %Z')，日志 $LOG）"
  forecast_running || die "forecast 现在没有在运行（服务器给了另一个项目）" "先 sudo fc to-forecast，再运行同一条命令"
  web="$FC_LABEL-web-1"
  docker exec "$web" grep -q PRELAUNCH_DOMAIN /etc/nginx/templates/site.conf.template 2>> "$LOG" \
    || die "现在运行的版本还不支持预热域名（版本 $(sed -n 's/^IMAGE_TAG=//p' "$FC/state/image.env" | cut -c1-12)）" "等自动更新换上包含 S18 的版本（sudo fc status 看版本），再运行同一条命令"

  # 第 1 步：DNS 指向本机
  pub=$(public_ip)
  resolved=$(getent ahostsv4 "$d" 2> /dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' | sed 's/ $//')
  [ -n "$resolved" ] || die "第 1 步：$d 查不到 IP（Cloudflare 里的记录还没加或还没生效）" "在 Cloudflare 加一条 A 记录：名称 ${d%%.*}，指向 ${pub:-这台服务器的公网 IP}，代理状态选'仅 DNS'（灰色云）；等 1～5 分钟再运行同一条命令"
  if [ -n "$pub" ] && [ "$resolved" != "$pub" ]; then
    die "第 1 步：$d 指向 $resolved，不是这台服务器（$pub）" "Cloudflare 里这条记录要指向 $pub，并且是灰色云（仅 DNS；橙色云会让证书申请失败）"
  fi
  say "✓ 第 1 步：DNS——$d → $resolved${pub:+（就是这台服务器）}"

  # 第 2 步：把域名加进同一张证书（webroot：Nginx 照常运行，80 端口的 /.well-known/acme-challenge/ 由它回答；续期方式不变）
  sans=$(cert_sans)
  case " $sans " in
    *" $d "*) say "✓ 第 2 步：证书——已经包含 $d，跳过" ;;
    *)
      [ -n "$sans" ] || die "第 2 步：没有找到证书 /etc/letsencrypt/live/forecast" "先粘贴一键部署的那条命令"
      for s in $sans; do args+=(-d "$s"); done
      if [ -n "$(envval CERT_EMAIL)" ]; then args+=(-m "$(envval CERT_EMAIL)"); else args+=(--register-unsafely-without-email); fi
      say "  … 第 2 步：扩展证书（把 $d 加进去，约 30 秒）"
      if ! certbot certonly -n --agree-tos --cert-name forecast --expand --webroot -w /var/www/certbot "${args[@]}" -d "$d" \
        --deploy-hook "docker exec $web nginx -s reload" >> "$LOG" 2>&1; then
        tail -5 "$LOG" | sed 's/^/    /'
        die "第 2 步：证书没有扩展成功（多半是 DNS 刚改还没生效、是橙色云，或者云防火墙没开 80）" "确认 Cloudflare 里是灰色云、等几分钟再运行同一条命令"
      fi
      case " $(cert_sans) " in *" $d "*) ;; *) die "第 2 步：certbot 说成功了，但证书里还是没有 $d" "截图发我" ;; esac
      say "✓ 第 2 步：证书——已扩展，现在包含：$(cert_sans)（自动续期照旧）"
      ;;
  esac

  # 第 3 步：写进 .env，让 Nginx 和后端用上（只重建配置变了的容器，数据不动）
  if [ "$cur" = "$d" ] && [ "$(docker exec "$web" printenv PRELAUNCH_DOMAIN 2> /dev/null)" = "$d" ]; then
    docker exec "$web" nginx -s reload >> "$LOG" 2>&1 || true
    say "✓ 第 3 步：配置——.env 里已经是 $d、正在使用，跳过（重新加载了一次 Nginx，用上最新的证书）"
  else
    [ "$cur" = "$d" ] || VAL="$d" env_write PRELAUNCH_DOMAIN
    say "  … 第 3 步：写进 .env（PRELAUNCH_DOMAIN=$d）并重新加载（reload-env.sh，约 1～2 分钟）"
    bash "$FC/reload-env.sh" >> "$LOG" 2>&1 < /dev/null || die "第 3 步：重新加载后 3 分钟内健康检查没有通过" "截图发我（日志 $LOG）"
    say "✓ 第 3 步：配置——已写进 .env，Nginx 和后端已用上"
  fi

  # 第 4 步：在本机按这个域名访问一遍
  for _ in $(seq 1 15); do
    code=$(lcurl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve "$d:443:127.0.0.1" "https://$d/" 2>> "$LOG" || true)
    [ "$code" = 200 ] && break
    sleep 2
  done
  [ "$code" = 200 ] || problems+=("https://$d/ 返回 $code（000 = 连不上或证书不包含这个域名）")
  page=$(lcurl -s --max-time 10 --resolve "$d:443:127.0.0.1" "https://$d/" 2>> "$LOG" || true)
  case "$page" in *'name="fc-site-mode" content="prelaunch"'*) ;; *) problems+=("首页不是等候页（页面里没有 fc-site-mode=prelaunch）") ;; esac
  body=$(lcurl -s --max-time 10 --resolve "$d:443:127.0.0.1" "https://$d/api/health" 2>> "$LOG" || true)
  case "$body" in *'"status":"UP"'*) ;; *) problems+=("https://$d/api/health 不是 UP") ;; esac
  case "$body" in *'"env"'*) problems+=("这个域名的健康检查还带测试环境标识 env（页面会显示标识条）") ;; esac
  loc=$(lcurl -s -o /dev/null -w '%{http_code} %{redirect_url}' --max-time 10 --resolve "$d:443:127.0.0.1" "https://$d/login" 2>> "$LOG" || true)
  case "$loc" in 302\ https://"$d"/) ;; *) problems+=("https://$d/login 应该跳回首页（$loc）") ;; esac
  code=$(lcurl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve "$d:443:127.0.0.1" "https://$d/api/me/session" 2>> "$LOG" || true)
  [ "$code" = 404 ] || problems+=("https://$d/api/me/session 应该是 404（$code）")
  loc=$(lcurl -s -o /dev/null -w '%{http_code} %{redirect_url}' --max-time 10 --resolve "$d:80:127.0.0.1" "http://$d/" 2>> "$LOG" || true)
  case "$loc" in 301\ https://"$d"/*) ;; *) problems+=("http://$d/ 没有跳转到 https（$loc）") ;; esac
  if [ "${#problems[@]}" -gt 0 ]; then
    say "✗ 第 4 步：在本机验证"
    for s in "${problems[@]}"; do say "    ✗ $s"; done
    say "    下一步：截图发我（日志 $LOG）"
    exit 1
  fi
  say "✓ 第 4 步：验证——https://$d/ 是等候页、没有测试环境标识；/login 跳回首页、其他接口 404；http 跳 https"
  say "完成。对外地址：https://$d/ （等候页兼公开概率看板；测试环境的三个域名照旧）"
}

[ "$(id -u)" = 0 ] || { echo "✗ 请用 sudo 运行，例如：sudo fc status"; exit 1; }
mkdir -p "$FC/logs"
: > "$LOG"
chmod 600 "$LOG"
cmd=${1:-help}
case "$cmd" in
  status) cmd_status ;;
  to-forecast) cmd_to_forecast ;;
  to-other) cmd_to_other ;;
  to-*)
    other_discover > /dev/null 2>&1
    if [ -n "$OTHER_NAME" ] && [ "$cmd" = "to-$OTHER_NAME" ]; then cmd_to_other; else
      echo "✗ 不认识 $cmd。另一个项目是：${OTHER_NAME:-（没有找到）}"
      usage
      exit 1
    fi
    ;;
  keys) cmd_keys ;;
  set-key) cmd_set_key "${2:-}" ;;
  add-domain) cmd_add_domain "${2:-}" ;;
  help | -h | --help) usage ;;
  *)
    echo "✗ 不认识 $cmd"
    usage
    exit 1
    ;;
esac
