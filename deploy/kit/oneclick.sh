#!/usr/bin/env bash
# 测试环境第一次部署：一条命令做完（S12 一键部署，`部署方案/v1-测试环境第一次部署清单.md`）。Amos 在阿里云控制台"远程连接"终端里粘贴：
#   sudo docker pull ghcr.io/dollar3651314/forecast-web:staging && sudo bash -c "$(sudo docker run --rm --entrypoint cat ghcr.io/dollar3651314/forecast-web:staging /forecast-kit/oneclick.sh)"
# （脚本在前端镜像的部署工具包里，仓库是私有的，curl 拿不到；用 bash -c "$(...)" 而不是"| bash"，脚本运行时键盘输入不会被吃掉。）
#
# 做的事（每一步屏幕上一行"✓ / ✗ 第 N 步"，详细输出写进 /opt/forecast/logs/oneclick-<时间>.log）：
#   1 启动前检查：内存、磁盘、端口、DNS、另一个项目（只看，不改）
#   2 记录切换前的状态（free -m、磁盘、容器、定时器）
#   3 停掉另一个项目：先停它的定时器 → 等进行中的自动部署 → 它自己的 backup.sh 备份 → 只停容器（switch-lib.sh）
#   4 取出部署工具包到 /opt/forecast，一次性准备（server-setup.sh），装切换命令 /usr/local/bin/fc
#   5 生成 .env 里的密码和密钥对（init-env.sh；已有的不动）
#   6 启动前检查（preflight.sh）
#   7 申请证书并启动（first-start.sh：证书已有不重新申请；版本已在运行就跳过）
#   8 在服务器本机验证（容器、健康检查、HTTPS 证书、HTTP 跳转、定时器）
#   9 打印摘要（没有密钥，可以直接截图）
# 可以重复运行：中途失败，修好后再粘贴同一条命令，做完的步骤会跳过（.env 已有不覆盖、证书已有不重新申请、另一个项目已停不再停）。
# 不会在中途问 y/n。只有继续下去可能丢数据时才停下来说明（例如 .env 没了但数据库目录里有数据）。
# 可选环境变量（一般不用设）：FC_CERT_EMAIL 证书到期提醒邮箱（默认不留）；FC_DOMAINS 三个域名（客户端 后台 官网，空格分开）；
#   FC_PUBLIC_IP 这台服务器的公网 IP（默认自动查，查不到只提醒）；FC_OFFLINE_TEST=1 只用于开发机演练（镜像已在本机，不拉）。
# 输出里不显示任何密钥的值。

main() {
  set -uo pipefail
  umask 022
  REG=ghcr.io/dollar3651314/forecast
  WEB_IMAGE=$REG-web:staging
  FC=/opt/forecast
  read -r APP_DOMAIN ADMIN_DOMAIN WWW_DOMAIN <<< "${FC_DOMAINS:-fc-test.quickcome.online fc-test-admin.quickcome.online fc-test-www.quickcome.online}"
  OPTIONAL_KEYS="FC_MAIL_PASSWORD FC_GEMINI_API_KEY FC_SRC_FOOTBALL_DATA_TOKEN FC_SRC_BALLDONTLIE_KEY FC_SRC_THESPORTSDB_KEY FC_TELEGRAM_BOT_TOKEN"
  REQUIRED_KEYS="APP_DOMAIN ADMIN_DOMAIN WWW_DOMAIN MYSQL_ROOT_PASSWORD FC_DB_PASSWORD FC_REDIS_PASSWORD FC_PUSH_VAPID_PUBLIC_KEY FC_PUSH_VAPID_PRIVATE_KEY FC_PUSH_VAPID_SUBJECT"

  if [ "$(id -u)" != 0 ]; then echo "✗ 请用 sudo 运行（命令前面要有 sudo）"; exit 1; fi
  command -v docker > /dev/null || { echo "✗ 这台服务器上没有 docker"; exit 1; }
  mkdir -p "$FC/logs" "$FC/state"
  chmod 700 "$FC/state"
  LOG="$FC/logs/oneclick-$(date -u +%Y%m%d-%H%M%S).log"
  : > "$LOG"
  chmod 600 "$LOG"
  KIT=$(mktemp -d)
  trap 'rm -rf "$KIT"' EXIT

  say "forecast 测试环境一键部署（$(date '+%Y-%m-%d %H:%M:%S %Z')）"
  say "详细输出写在 $LOG"
  say ""

  # ---- 准备：从镜像里取出部署工具包（先放临时目录，第 4 步再放到 $FC）----
  if [ -z "${FC_OFFLINE_TEST:-}" ]; then
    if ! docker pull -q "$WEB_IMAGE" >> "$LOG" 2>&1; then
      if grep -qiE 'denied|unauthorized' "$LOG"; then
        fail 0 "拉取镜像" "没有权限拉取 $WEB_IMAGE（GitHub 令牌可能过期）" \
          "在这个终端里运行 sudo docker login ghcr.io，用户名填 GitHub 用户名，密码粘贴 GitHub 令牌（只粘贴在服务器上，不要发给我），再粘贴同一条命令"
      fi
      fail 0 "拉取镜像" "拉取 $WEB_IMAGE 失败（网络？）" "过一两分钟再粘贴同一条命令"
    fi
  fi
  if ! docker run --rm --entrypoint sh -v "$KIT:/out" "$WEB_IMAGE" -c 'cp -r /forecast-kit/. /out/' >> "$LOG" 2>&1 || [ ! -f "$KIT/switch-lib.sh" ]; then
    fail 0 "取出部署工具包" "镜像 $WEB_IMAGE 里没有完整的部署工具包" "截图发我"
  fi
  KIT_VERSION=$(cat "$KIT/VERSION" 2> /dev/null || echo 未知)
  export FC_HOME=$FC
  # shellcheck source=switch-lib.sh
  . "$KIT/switch-lib.sh"

  step1_check
  step2_record
  step3_stop_other
  step4_kit
  step5_env
  step6_preflight
  step7_start
  step8_verify
  step9_summary
}

# ---------- 输出 ----------
say() { echo "$*" | tee -a "$LOG"; }
detail() { echo "    $*" | tee -a "$LOG"; }
ok() { say "✓ 第 $1 步：$2"; }
warn() { say "  ! $*"; }
# fail 步骤 做什么 原因 下一步
fail() {
  say "✗ 第 $1 步：$2"
  say "    原因：$3"
  say "    下一步：$4"
  say "    修好后再粘贴同一条命令，做完的步骤会自动跳过。"
  say "    把屏幕截图发我（先遮住密钥：密码、API key、token）。详细日志：$LOG"
  exit 1
}
# 运行一条命令，输出只写进日志（不占屏幕）；键盘输入不给它
quiet() { "$@" >> "$LOG" 2>&1 < /dev/null; }
# 日志里最近的几行，失败时给出来（这些脚本都不打印密钥的值）
tail_log() {
  local n=${1:-15}
  say "    —— 日志最后 $n 行 ——"
  tail -n "$n" "$LOG" | sed 's/^/    | /'
}
envval() { sed -n "s/^$1=//p" "$FC/.env" 2> /dev/null | tail -1; }

# ---------- 第 1 步 ----------
step1_check() {
  local total avail problems=() notes=() holders line p who rc others ip resolved d pub
  read -r total _ < <(free -m | awk '/^Mem:/ {print $2, $3}')
  if [ "$total" -lt 1592 ]; then problems+=("总内存 ${total} MB，小于 4 个容器上限 992 + 系统 450 + 余量 150 = 1592 MB"); fi
  avail=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
  if [ "${avail:-0}" -lt 5 ]; then
    problems+=("根分区只剩 ${avail} GB（至少要 5 GB）")
  elif [ "$avail" -lt 10 ]; then notes+=("根分区剩 ${avail} GB，低于建议的 10 GB，能继续"); fi

  other_discover
  rc=$?
  if [ "$rc" = 2 ]; then
    fail 1 "启动前检查" "$OTHER_DISCOVER_PROBLEM" "不要自己动它，截图发我，我来看"
  fi
  if [ "$rc" = 0 ]; then notes+=("另一个项目：$OTHER_NAME（目录 $OTHER_DIR），第 3 步会先停它"); else notes+=("没有发现另一个项目"); fi

  # crontab 里另一个项目的部署命令（不替你改 crontab）
  line=$(crontab -l 2> /dev/null | grep -v '^#' | grep -E 'deploy\.sh|docker' | grep -v "$FC/" || true)
  [ -z "$line" ] && line=$(cat /etc/cron.d/* 2> /dev/null | grep -v '^#' | grep -E 'deploy\.sh' | grep -v "$FC/" || true)
  [ -z "$line" ] || problems+=("crontab 里有别的项目的部署命令（会把它重新拉起来）：$line")

  # 端口：只允许 forecast 自己和另一个项目（第 3 步会停）的容器占着
  holders=$(port_holders)
  while read -r p who; do
    [ -n "$p" ] || continue
    case "$who" in
      "$FC_LABEL"-*) ;;
      进程:*) problems+=("端口 $p 被不是容器的程序占着：${who#进程:}（例如系统自带的 Nginx、没退出的 certbot）") ;;
      *)
        if [ -n "$OTHER_NAME" ] && running_of "$OTHER_NAME" | grep -qx "$who"; then :; else problems+=("端口 $p 被容器 $who 占着，它不属于 forecast，也不属于另一个项目"); fi
        ;;
    esac
  done <<< "$holders"
  # 既不是 forecast 也不是另一个项目的运行中容器（preflight 会拦下，提前说）
  others=$(docker ps --format '{{.Names}}' | grep -v "^$FC_LABEL-" || true)
  if [ -n "$others" ] && [ -n "$OTHER_NAME" ]; then others=$(echo "$others" | grep -vxF -f <(running_of "$OTHER_NAME") || true); fi
  [ -z "$others" ] || problems+=("这些容器不属于 forecast，也不属于另一个项目，不知道该不该停：$(echo "$others" | tr '\n' ' ')")

  # .env 没了、数据库目录里却有数据：生成新密码会连不上原来的数据库 → 停下来
  if [ -d "$FC/data/mysql" ] && [ -n "$(ls -A "$FC/data/mysql" 2> /dev/null)" ] && [ -z "$(envval MYSQL_ROOT_PASSWORD)" ]; then
    fail 1 "启动前检查" "$FC/data/mysql 里已经有数据库，但 $FC/.env 里没有数据库密码（.env 被删了或被清空了？）。继续会生成新密码，原来的数据库就打不开了" \
      "什么都不要删，截图发我"
  fi

  # DNS：三个域名都要解析到这台服务器（证书要用）
  pub=${FC_PUBLIC_IP:-}
  if [ -z "$pub" ]; then
    pub=$(curl -s -4 --max-time 3 http://100.100.100.200/latest/meta-data/eipv4 2> /dev/null | grep -E '^[0-9.]+$' || true)
    for u in https://api.ipify.org https://ifconfig.me/ip https://checkip.amazonaws.com; do
      [ -n "$pub" ] && break
      pub=$(curl -s -4 --max-time 5 "$u" 2> /dev/null | tr -d '[:space:]' | grep -E '^[0-9]+(\.[0-9]+){3}$' || true)
    done
  fi
  for d in "$APP_DOMAIN" "$ADMIN_DOMAIN" "$WWW_DOMAIN"; do
    resolved=$(getent ahostsv4 "$d" 2> /dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' | sed 's/ $//')
    if [ -z "$resolved" ]; then
      problems+=("域名 $d 查不到 IP（Cloudflare 里的 DNS 记录还没加或还没生效）")
    elif [ -n "$pub" ] && [ "$resolved" != "$pub" ]; then
      problems+=("域名 $d 指向 $resolved，不是这台服务器（$pub）；Cloudflare 里这条记录要指向 $pub，并且是灰色云（仅 DNS）")
    else
      ip=$resolved
    fi
  done
  if [ -z "$pub" ]; then notes+=("查不到这台服务器的公网 IP，没法核对 DNS 指向（域名解析到 ${ip:-?}）；如果指错了，第 7 步申请证书会失败"); fi

  if [ "${#problems[@]}" -gt 0 ]; then
    say "✗ 第 1 步：启动前检查（内存、磁盘、端口、DNS）"
    for p in "${problems[@]}"; do say "    ✗ $p"; done
    say "    这一步只检查，什么都没有改；另一个项目还在原样运行。"
    say "    下一步：按上面打 ✗ 的处理好，再粘贴同一条命令。不知道怎么处理就截图发我（先遮住密钥）。详细日志：$LOG"
    exit 1
  fi
  ok 1 "启动前检查（内存 ${total} MB、磁盘剩 ${avail} GB、端口、DNS 三个域名 → ${ip:-?}）"
  for p in "${notes[@]}"; do detail "$p"; done
}

# ---------- 第 2 步 ----------
step2_record() {
  local f used
  f="$FC/logs/before-switch-$(date -u +%Y%m%d-%H%M%S).txt"
  {
    echo "== free -m"; free -m
    echo "== df -h /"; df -h /
    echo "== docker ps -a"; docker ps -a --format '{{.Names}}  {{.Status}}'
    echo "== 定时器"; systemctl list-unit-files --type=timer --no-legend '*-deploy.timer' '*-backup.timer' 2> /dev/null
  } > "$f" 2>&1
  cat "$f" >> "$LOG"
  used=$(free -m | awk '/^Mem:/ {print $3}')
  ok 2 "记录切换前的状态（已用内存 ${used} MB、运行中的容器 $(docker ps -q | wc -l) 个；写在 $f）"
}

# ---------- 第 3 步 ----------
step3_stop_other() {
  local used
  if [ -z "$OTHER_NAME" ]; then ok 3 "停掉另一个项目——没有另一个项目，跳过"; return; fi
  if other_is_stopped; then
    ok 3 "停掉 $OTHER_NAME——已经停了（容器没在运行、定时器已停用），跳过"
    return
  fi
  say "  … 第 3 步：停掉 $OTHER_NAME（先停定时器 → 备份 → 停容器，约 1 分钟）"
  if ! other_stop >> "$LOG" 2>&1; then
    tail_log 12
    fail 3 "停掉 $OTHER_NAME" "$STOP_PROBLEM" "截图发我，先不要再运行"
  fi
  used=$(free -m | awk '/^Mem:/ {print $3}')
  ok 3 "停掉 $OTHER_NAME（定时器已停用、已备份、容器已停止，数据都保留；这时已用内存 ${used} MB）"
  [ "$used" -le 450 ] || detail "没有容器运行时已用 ${used} MB，超过内存总账里系统的 450 MB；能继续，摘要截图发我，我来调"
}

# ---------- 第 4 步 ----------
step4_kit() {
  local f rel changed=0
  for f in "$KIT"/* "$KIT"/systemd/*; do
    [ -f "$f" ] || continue
    rel=${f#"$KIT"/}
    if ! cmp -s "$f" "$FC/$rel"; then
      mkdir -p "$(dirname "$FC/$rel")"
      cp "$f" "$FC/$rel.new" && mv "$FC/$rel.new" "$FC/$rel"
      changed=$((changed + 1))
    fi
  done
  chmod 755 "$FC"/*.sh
  if [ ! -f "$FC/state/server-setup.done" ]; then
    say "  … 第 4 步：一次性准备（certbot、目录、定时器文件；约 1 分钟）"
    if ! quiet bash "$FC/server-setup.sh"; then
      tail_log 15
      fail 4 "取出部署工具包、一次性准备" "server-setup.sh 失败" "截图发我"
    fi
    date -u +%Y-%m-%dT%H:%M:%SZ > "$FC/state/server-setup.done"
  fi
  ln -sfn "$FC/fc.sh" /usr/local/bin/fc
  if [ "$changed" = 0 ]; then
    ok 4 "部署工具包已是最新（版本 ${KIT_VERSION:0:12}），跳过；切换命令 fc 已装好"
  else
    ok 4 "取出部署工具包到 $FC（版本 ${KIT_VERSION:0:12}，更新 $changed 个文件；.env、数据、备份不动），装好切换命令 fc"
  fi
}

# ---------- 第 5 步 ----------
step5_env() {
  local k missing=""
  for k in $REQUIRED_KEYS; do [ -n "$(envval "$k")" ] || missing="$missing $k"; done
  if [ -z "$missing" ]; then
    ok 5 "生成 .env——已经有了，不覆盖（不显示值）"
    return
  fi
  if ! quiet bash "$FC/init-env.sh" "$APP_DOMAIN" "$ADMIN_DOMAIN" "${FC_CERT_EMAIL:-}" "$WWW_DOMAIN"; then
    tail_log 10
    fail 5 "生成 .env" "init-env.sh 失败" "截图发我"
  fi
  missing=""
  for k in $REQUIRED_KEYS; do [ -n "$(envval "$k")" ] || missing="$missing $k"; done
  [ -z "$missing" ] || fail 5 "生成 .env" "生成以后还缺：$missing" "截图发我"
  ok 5 "生成 .env 里的数据库和 Redis 密码、推送密钥对、超管初始密码（写在 $FC/.env，只有 root 能读，这里不显示值）"
}

# ---------- 第 6 步 ----------
step6_preflight() {
  local out rc
  out=$(bash "$FC/preflight.sh" 2>&1 < /dev/null)
  rc=$?
  echo "$out" >> "$LOG"
  if [ "$rc" != 0 ]; then
    say "✗ 第 6 步：启动前检查（preflight.sh）"
    echo "$out" | grep -E '✗' | sed 's/^ */    /' | tee -a "$LOG"
    say "    下一步：按上面打 ✗ 的处理好，再粘贴同一条命令。不知道怎么处理就截图发我（先遮住密钥）。详细日志：$LOG"
    exit 1
  fi
  ok 6 "启动前检查 preflight.sh 通过"
  echo "$out" | grep -E '^ *! ' | sed 's/^ *! /    ! /' | tee -a "$LOG" || true
}

# ---------- 第 7 步 ----------
step7_start() {
  local cur new
  cur=$(sed -n 's/^IMAGE_TAG=//p' "$FC/state/image.env" 2> /dev/null | tail -1)
  if [ -n "$cur" ] && forecast_running && [ -f /etc/letsencrypt/live/forecast/fullchain.pem ] && forecast_timers_on && wait_up_quick "$cur"; then
    ok 7 "申请证书并启动——forecast 已经在运行（版本 ${cur:0:12}），证书已有，跳过"
    return
  fi
  # 上次第一次启动失败过（还没有运行成功的版本）：这次重新试，不跳过那个版本
  [ -n "$cur" ] || rm -f "$FC/state/skip"
  say "  … 第 7 步：申请证书并启动（第一次要下载镜像，约 3～6 分钟，请等它自己结束）"
  if ! quiet bash "$FC/first-start.sh"; then
    tail_log 20
    if [ ! -f /etc/letsencrypt/live/forecast/fullchain.pem ]; then
      fail 7 "申请证书并启动" "HTTPS 证书没有申请下来：多半是三个域名还没解析到这台服务器、云防火墙没开 80 端口，或者 80 端口被占着" \
        "在 Cloudflare 确认三条记录指向这台服务器、灰色云；在轻量服务器控制台的防火墙确认 80、443 开着"
    fi
    fail 7 "申请证书并启动" "证书已有，但启动或健康检查没有通过" "截图发我（后端日志：sudo bash -c '. /opt/forecast/lib.sh && compose logs --tail 80 app'）"
  fi
  new=$(sed -n 's/^IMAGE_TAG=//p' "$FC/state/image.env" | tail -1)
  ok 7 "申请证书并启动（证书包含三个域名；版本 ${new:0:12} 已启动，健康检查通过；自动更新和每天备份已打开）"
}

# 健康检查马上就是 UP、版本对得上（不等）
wait_up_quick() {
  local body
  body=$(lcurl -sf --max-time 5 http://127.0.0.1:8080/api/health || true)
  case "$body" in *'"status":"UP"'*'"version":"'"$1"'"'* | *'"version":"'"$1"'"'*'"status":"UP"'*) return 0 ;; esac
  return 1
}

# ---------- 第 8 步 ----------
step8_verify() {
  local problems=() cur body c st d code loc san days end t
  cur=$(sed -n 's/^IMAGE_TAG=//p' "$FC/state/image.env" | tail -1)
  for c in web app mysql redis; do
    st=$(docker inspect -f '{{.State.Status}}{{if .State.Health}} {{.State.Health.Status}}{{end}}' "$FC_LABEL-$c-1" 2> /dev/null || echo 不存在)
    case "$c:$st" in
      web:running | app:running | mysql:"running healthy" | redis:"running healthy") ;;
      *) problems+=("容器 $FC_LABEL-$c-1：$st") ;;
    esac
  done
  body=$(lcurl -sf --max-time 5 http://127.0.0.1:8080/api/health || true)
  case "$body" in *'"status":"UP"'*) ;; *) problems+=("健康检查不是 UP") ;; esac
  case "$body" in *'"env":"test"'*) ;; *) problems+=("健康检查里没有 \"env\":\"test\"（测试环境标识）") ;; esac
  case "$body" in *"\"version\":\"$cur\""*) ;; *) problems+=("健康检查的版本号不是 $cur") ;; esac
  # HTTPS：按域名访问本机，证书要能通过校验（签发方可信、包含这个域名）
  for d in "$APP_DOMAIN" "$ADMIN_DOMAIN" "$WWW_DOMAIN"; do
    code=$(lcurl -sL --max-redirs 3 -o /dev/null -w '%{http_code}' --max-time 10 --resolve "$d:443:127.0.0.1" "https://$d/" 2>> "$LOG" || true)
    [ "$code" = 200 ] || problems+=("https://$d/ 返回 $code（000 = 连不上或证书校验没通过）")
    loc=$(lcurl -s -o /dev/null -w '%{http_code} %{redirect_url}' --max-time 10 --resolve "$d:80:127.0.0.1" "http://$d/" 2>> "$LOG" || true)
    case "$loc" in 301\ https://"$d"/* | 308\ https://"$d"/*) ;; *) problems+=("http://$d/ 没有跳转到 https（$loc）") ;; esac
  done
  san=$(openssl x509 -noout -ext subjectAltName -in /etc/letsencrypt/live/forecast/fullchain.pem 2> /dev/null | tr ',' '\n' | sed -n 's/.*DNS:\(.*\)/\1/p' | tr '\n' ' ')
  for d in "$APP_DOMAIN" "$ADMIN_DOMAIN" "$WWW_DOMAIN"; do
    case " $san" in *" $d "*) ;; *) problems+=("证书里没有 $d（按《测试环境与切换》第 8 节扩展证书）") ;; esac
  done
  end=$(openssl x509 -noout -enddate -in /etc/letsencrypt/live/forecast/fullchain.pem 2> /dev/null | cut -d= -f2)
  days=$((($(date -d "${end:-now}" +%s) - $(date +%s)) / 86400))
  [ "$days" -gt 7 ] || problems+=("证书只剩 $days 天")
  for t in forecast-deploy.timer forecast-backup.timer; do
    [ "$(systemctl is-enabled "$t" 2> /dev/null)" = enabled ] || problems+=("定时器 $t 没有启用")
  done
  if [ "${#problems[@]}" -gt 0 ]; then
    say "✗ 第 8 步：在服务器本机验证"
    for p in "${problems[@]}"; do say "    ✗ $p"; done
    say "    下一步：截图发我（先遮住密钥）。详细日志：$LOG"
    exit 1
  fi
  ok 8 "在服务器本机验证（4 个容器正常、健康检查 UP、测试环境标识、三个域名 HTTPS 和跳转、证书还有 $days 天、两个定时器已启用）"
}

# ---------- 第 9 步 ----------
step9_summary() {
  local cur staging k empty="" mark other_hint
  cur=$(sed -n 's/^IMAGE_TAG=//p' "$FC/state/image.env" | tail -1)
  staging=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$REG-app:staging" 2> /dev/null || true)
  if [ "$cur" = "$staging" ]; then mark="和 staging 镜像一致 ✓"; else mark="staging 镜像是 ${staging:-?}，自动更新会在 1 分钟内换上"; fi
  for k in $OPTIONAL_KEYS; do [ -n "$(envval "$k")" ] || empty="$empty $k"; done
  other_hint=${OTHER_NAME:-另一个项目}
  ok 9 "完成。下面的摘要没有密钥，可以直接截图发我"
  {
    echo
    echo "==================== forecast 测试环境部署结果 ===================="
    echo "当前版本：$cur（$mark）"
    echo "网址："
    echo "  客户端  https://$APP_DOMAIN"
    echo "  后台    https://$ADMIN_DOMAIN"
    echo "  官网    https://$WWW_DOMAIN"
    echo "容器："
    status_of "$FC_LABEL"
    echo "内存（free -m）："
    free -m | sed 's/^/  /'
    echo "容器内存（docker stats --no-stream）："
    # shellcheck disable=SC2046  # 容器名逐个传给 docker stats
    docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' $(running_of "$FC_LABEL" | sort) | sed 's/^/  /'
    echo "还没填的可选密钥：${empty:- 没有（都填了）}"
    echo "==================================================================="
    echo "之后："
    echo "  填密钥（屏幕上不显示值）：sudo fc set-key 变量名      看哪些填了：sudo fc keys"
    echo "  切回 $other_hint：sudo fc to-$other_hint      再切回 forecast：sudo fc to-forecast      看状态：sudo fc status"
    echo "  后台初始密码只在服务器上看：sudo grep ^FC_ADMIN_INIT_PASSWORD= /opt/forecast/.env （这一行的输出不要截图）"
    echo "日志：$LOG"
  } 2>&1 | tee -a "$LOG"
}

main "$@"
