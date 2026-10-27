#!/usr/bin/env bash
# 一键部署和切换命令的演练（S12 一键部署，`开发记录/S12-测试环境部署准备.md` 第 10 节）。在开发机或 CI 上，用**真实的路径**
# （/opt/forecast、/usr/local/bin/fc、/etc/letsencrypt/live/forecast、/etc/systemd/system/forecast-*）按测试服务器的样子跑一遍：
#   - 一个假的"另一个项目" oldproj：/opt/oldproj 下的 lib.sh、backup.sh、deploy.sh、compose（4 个小容器占 80、443、127.0.0.1:8080），
#     systemd 里有 oldproj-deploy.timer、oldproj-backup.timer（启用着）——和测试服务器上那个项目的布局一样，只是名字不同；
#   - systemctl、certbot、apt-get、swapon、sysctl 用替身（放在临时目录，只对本脚本启动的命令生效）：这台机器上没有 systemd 或者不能改系统；
#     certbot 替身用本次生成的 CA 签证书（三个域名），要 80 端口空着（和 --standalone 一样），可以让它失败（模拟 DNS 没生效）；
#   - 三个假域名 *.oneclick.test 临时写进 /etc/hosts 指向 127.0.0.1；镜像用本机的 forecast-web / forecast-app（标成 staging）。
# 演练：①80 端口被别的程序占着 → 第 1 步停下、什么都没改 ②首次运行，证书申请失败（第 7 步）→ 修好再运行，前面做完的步骤跳过
#   ③再运行一次：全部跳过、容器没有重启 ④fc to-oldproj、fc to-forecast 来回切换（各自备份、定时器原样恢复）
#   ⑤fc keys、fc set-key（隐藏输入、写进 .env、reload-env 生效、值不出现在任何输出和日志里；变量名打错有提示）
#   ⑥另一个项目的备份失败 → fc to-forecast 停下，不停它的容器、定时器恢复。
# 用法：sudo bash e2e/oneclick-drill.sh
#   需要 root、Docker（含 compose 插件）、script（util-linux）、openssl；本机已有 ghcr.io/dollar3651314/forecast-{web,app}:${E2E_IMAGE_TAG:-e2e-local}
#   （例如刚跑完 e2e/run-deployed.sh）；80、443、8080 空着；/opt/forecast、/opt/oldproj 等不存在（不碰已有的东西）。
#   E2E_ONECLICK_CLEAN=1：跳过 ①② 的故意失败，直接首次运行（取一份干净的输出）。
# 结束时删除自己建的一切：两个 compose 项目的容器、/opt/forecast、/opt/oldproj、证书、systemd 文件、/usr/local/bin/fc、
# /etc/hosts 里加的行、server-setup.sh 写的系统文件（原来没有的才删）、本次打的镜像标签。不清理别人的镜像、容器、卷。
# 输出：e2e/logs/oneclick/（每一次运行的屏幕输出；不进仓库）。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
REG=ghcr.io/dollar3651314/forecast
TAG=${E2E_IMAGE_TAG:-e2e-local}
OUT=$ROOT/e2e/logs/oneclick
DOMAINS="fc-test.oneclick.test fc-test-admin.oneclick.test fc-test-www.oneclick.test"
SECRET_VALUE="ci_only_drill_gemini_value_$(openssl rand -hex 6)"
die() { echo "错误：$*" >&2; exit 2; }
pass() { echo "  ✔ $*"; }
bad() { echo "  ✘ 演练没有通过：$*" >&2; exit 1; }
say() { echo "== $*"; }

[ "$(id -u)" = 0 ] || die "要用 root 运行（sudo bash e2e/oneclick-drill.sh）"
for c in docker openssl script curl getent; do command -v "$c" > /dev/null || die "缺少命令 $c"; done
docker image inspect "$REG-web:$TAG" "$REG-app:$TAG" > /dev/null 2>&1 || die "本机没有 $REG-web:$TAG、$REG-app:$TAG（先跑 e2e/run-deployed.sh，或者设 E2E_IMAGE_TAG）"
REV=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$REG-app:$TAG")
[ -n "$REV" ] || die "镜像没有版本号标签"
# 不碰已有的东西
for p in /opt/forecast /opt/oldproj /usr/local/bin/fc /etc/letsencrypt/live/forecast /etc/letsencrypt/renewal/forecast.conf \
  /etc/systemd/system/forecast-deploy.timer /etc/systemd/system/oldproj-deploy.timer; do
  [ ! -e "$p" ] && [ ! -L "$p" ] || die "$p 已经存在，本演练不碰已有的东西"
done
for img in "$REG-web:staging" "$REG-app:staging" "$REG-web:$REV" "$REG-app:$REV"; do
  [ "$img" = "$REG-web:$TAG" ] || [ "$img" = "$REG-app:$TAG" ] || ! docker image inspect "$img" > /dev/null 2>&1 || die "本机已经有 $img，本演练会覆盖它的标签，先不跑"
done
for proj in forecast oldproj; do
  [ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$proj")" ] || die "已经有 compose 项目 $proj 的容器"
done
for p in 80 443 8080; do
  if (exec 3<> "/dev/tcp/127.0.0.1/$p") 2> /dev/null; then die "本机端口 $p 已被占用"; fi
done

WORK=$(mktemp -d)
chmod 700 "$WORK"
mkdir -p "$OUT" "$WORK/bin" "$WORK/sd"
CREATED=()   # 本次新建的系统文件、目录（结束时删除）
note_new() { [ -e "$1" ] || CREATED+=("$1"); }
for f in /etc/apt/apt.conf.d/20auto-upgrades /etc/sysctl.d/90-forecast.conf /etc/docker/daemon.json /var/www/certbot /etc/letsencrypt; do note_new "$f"; done
TAGGED=()
BLOCKER=""

# shellcheck disable=SC2317  # 由 trap 调用
cleanup() {
  local rc=$? f
  set +e
  [ -z "$BLOCKER" ] || kill "$BLOCKER" 2> /dev/null
  # 只删这两个 compose 项目自己的容器和网络（forecast 的数据是目录映射，没有命名卷）
  for proj in forecast oldproj; do
    docker ps -aq --filter "label=com.docker.compose.project=$proj" | xargs -r docker rm -f > /dev/null 2>&1
    docker network ls -q --filter "label=com.docker.compose.project=$proj" | xargs -r docker network rm > /dev/null 2>&1
  done
  rm -rf /opt/forecast /opt/oldproj /etc/letsencrypt/live/forecast /etc/letsencrypt/archive/forecast /etc/letsencrypt/renewal/forecast.conf
  rm -f /usr/local/bin/fc /etc/systemd/system/forecast-* /etc/systemd/system/oldproj-*
  sed -i '/# oneclick-drill$/d' /etc/hosts
  for f in "${CREATED[@]}"; do rm -rf "$f"; done
  for f in "${TAGGED[@]}"; do docker image rm "$f" > /dev/null 2>&1; done
  rm -rf "$WORK"
  echo "== 结束（退出码 $rc），每次运行的输出在 $OUT"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ---------- 替身 ----------
say "准备替身（systemctl、certbot、apt-get、swapon、sysctl）"
cat > "$WORK/bin/systemctl" << 'SH'
#!/usr/bin/env bash
# 演练用的 systemctl 替身：单元文件在 /etc/systemd/system，启用 / 运行状态记在 $FAKE_SD
U=/etc/systemd/system
S=${FAKE_SD:?}
mkdir -p "$S/enabled" "$S/active"
cmd=$1; shift
now=0 quiet=0 type="" state="" prop="" units=()
while [ $# -gt 0 ]; do
  case $1 in
    --now) now=1 ;; -q | --quiet) quiet=1 ;; --no-pager | --no-legend | --value | --all) ;;
    --type=*) type=${1#--type=} ;; --state=*) state=${1#--state=} ;; -p) prop=$2; shift ;;
    *) units+=("$1") ;;
  esac
  shift
done
match() { local n=$1 p; [ ${#units[@]} = 0 ] && return 0; for p in "${units[@]}"; do [[ $n == $p ]] && return 0; done; return 1; }
rc=0
case $cmd in
  enable | disable)
    for u in "${units[@]}"; do
      [ -f "$U/$u" ] || { echo "Failed to $cmd unit: Unit file $u does not exist." >&2; rc=1; continue; }
      if [ "$cmd" = enable ]; then touch "$S/enabled/$u"; [ $now = 1 ] && touch "$S/active/$u"
      else rm -f "$S/enabled/$u"; [ $now = 1 ] && rm -f "$S/active/$u"; fi
    done ;;
  start) for u in "${units[@]}"; do touch "$S/active/$u"; done ;;
  stop) for u in "${units[@]}"; do rm -f "$S/active/$u"; done ;;
  is-enabled)
    for u in "${units[@]}"; do
      if [ ! -f "$U/$u" ]; then st=not-found; elif [ -f "$S/enabled/$u" ]; then st=enabled; else st=disabled; fi
      [ $quiet = 1 ] || echo "$st"; [ "$st" = enabled ] || rc=1
    done ;;
  is-active)
    for u in "${units[@]}"; do
      if [ -f "$S/active/$u" ]; then st=active; else st=inactive; fi
      [ $quiet = 1 ] || echo "$st"; [ "$st" = active ] || rc=3
    done ;;
  list-unit-files)
    for f in "$U"/*; do
      [ -f "$f" ] || continue; n=$(basename "$f")
      [ -z "$type" ] || [[ $n == *."$type" ]] || continue
      match "$n" || continue
      if [ -f "$S/enabled/$n" ]; then st=enabled; else st=disabled; fi
      [ -z "$state" ] || [ "$state" = "$st" ] || continue
      echo "$n $st enabled"
    done ;;
  list-units)
    for f in "$S"/active/*; do
      [ -f "$f" ] || continue; n=$(basename "$f")
      [ -z "$type" ] || [[ $n == *."$type" ]] || continue
      match "$n" || continue
      echo "$n loaded active waiting 演练"
    done ;;
  list-timers)
    echo "NEXT LEFT LAST PASSED UNIT ACTIVATES"
    for f in "$S"/active/*.timer; do [ -f "$f" ] || continue; n=$(basename "$f"); match "$n" && echo "- - - - $n ${n%.timer}.service"; done ;;
  show)
    for u in "${units[@]}"; do
      x=$(sed -n "s/^$prop=//p" "$U/$u" 2> /dev/null | head -1)
      [ -z "$x" ] || echo "{ path=${x%% *} ; argv[]=$x ; }"
    done ;;
  daemon-reload | restart | reload | reset-failed) ;;
  *) echo "演练替身不支持 systemctl $cmd" >&2; rc=1 ;;
esac
exit $rc
SH
cat > "$WORK/bin/certbot" << 'SH'
#!/usr/bin/env bash
# 演练用的 certbot 替身：用本次演练的 CA 签一张包含 -d 各域名的证书，路径和 Let's Encrypt 一样
LE=/etc/letsencrypt
cmd=$1; shift
name="" doms=()
while [ $# -gt 0 ]; do
  case $1 in --cert-name) name=$2; shift ;; -d) doms+=("$2"); shift ;; esac
  shift
done
case $cmd in
  certonly)
    if [ -f "$FAKE_SD/../certbot-fail" ]; then echo "Certbot failed to authenticate some domains (演练：模拟 DNS 还没生效)"; exit 1; fi
    if (exec 3<> /dev/tcp/127.0.0.1/80) 2> /dev/null; then echo "Could not bind TCP port 80 because it is already in use"; exit 1; fi
    d=$LE/live/$name; mkdir -p "$d" "$LE/renewal"
    san=$(printf 'DNS:%s,' "${doms[@]}"); san=${san%,}
    openssl req -newkey rsa:2048 -nodes -subj "/CN=${doms[0]}" -keyout "$d/privkey.pem" -out "$FAKE_SD/../leaf.csr" 2> /dev/null
    printf 'subjectAltName=%s\n' "$san" > "$FAKE_SD/../leaf.ext"
    openssl x509 -req -in "$FAKE_SD/../leaf.csr" -CA "$FAKE_SD/../ca.pem" -CAkey "$FAKE_SD/../ca.key" -CAcreateserial -days 90 \
      -extfile "$FAKE_SD/../leaf.ext" -out "$d/cert.pem" 2> /dev/null
    cat "$d/cert.pem" "$FAKE_SD/../ca.pem" > "$d/fullchain.pem"
    printf 'authenticator = standalone\n' > "$LE/renewal/$name.conf"
    echo "Successfully received certificate.（演练替身）" ;;
  reconfigure) sed -i 's/^authenticator = .*/authenticator = webroot/' "$LE/renewal/$name.conf"; echo "已改成 webroot（演练替身）" ;;
  certificates) for c in "$LE"/live/*/cert.pem; do echo "Certificate Name: $(basename "$(dirname "$c")")"; done ;;
  --version) echo "certbot 0（演练替身）" ;;
esac
SH
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/apt-get"
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/sysctl"
printf '#!/bin/sh\necho "/swapfile file 2G 0B -2"\n' > "$WORK/bin/swapon"
chmod 755 "$WORK"/bin/*
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=oneclick drill CA" -keyout "$WORK/ca.key" -out "$WORK/ca.pem" 2> /dev/null
export PATH="$WORK/bin:$PATH" FAKE_SD="$WORK/sd" FC_OFFLINE_TEST=1 FC_DOMAINS="$DOMAINS" FC_PUBLIC_IP=127.0.0.1 CURL_CA_BUNDLE="$WORK/ca.pem"
for d in $DOMAINS; do echo "127.0.0.1 $d # oneclick-drill" >> /etc/hosts; done

# 镜像：本机构建的当作 staging（和服务器上 deploy.sh 用的标签一样：staging 和提交号）
for img in web app; do
  for t in staging "$REV"; do
    [ "$t" = "$TAG" ] && continue
    docker tag "$REG-$img:$TAG" "$REG-$img:$t"
    TAGGED+=("$REG-$img:$t")
  done
done

# ---------- 假的另一个项目 oldproj ----------
say "准备假的另一个项目 oldproj（4 个容器占 80、443、8080，两个定时器启用着）"
mkdir -p /opt/oldproj/state /opt/oldproj/backup
cat > /opt/oldproj/docker-compose.yml << 'YML'
services:
  web:
    image: nginx:1.27-bookworm
    ports: ["80:80", "443:443"]
  app:
    image: nginx:1.27-bookworm
    ports: ["127.0.0.1:8080:8080"]
    volumes: ["./app.conf:/etc/nginx/conf.d/default.conf:ro"]
  mysql:
    image: redis:7
  redis:
    image: redis:7
YML
cat > /opt/oldproj/app.conf << 'CONF'
server { listen 8080; location /api/health { default_type application/json; return 200 '{"status":"UP","version":"oldproj-v1"}'; } }
CONF
cat > /opt/oldproj/lib.sh << 'SH'
cd /opt/oldproj || exit 1
compose() { docker compose --project-name oldproj --project-directory /opt/oldproj -f /opt/oldproj/docker-compose.yml "$@"; }
SH
cat > /opt/oldproj/backup.sh << 'SH'
#!/usr/bin/env bash
set -euo pipefail
. /opt/oldproj/lib.sh
[ ! -f /opt/oldproj/FAIL_BACKUP ] || { echo "演练：备份失败"; exit 1; }
f=/opt/oldproj/backup/oldproj-$(date +%Y%m%dT%H%M%S)-${1:-daily}.txt
# compose exec 会读标准输入：调用方要给 /dev/null，否则会吃掉后面的输入
compose exec -T mysql redis-cli ping > "$f"
echo "备份完成：$(basename "$f")"
SH
printf '#!/bin/sh\nexit 0\n' > /opt/oldproj/deploy.sh
chmod 755 /opt/oldproj/*.sh
echo "IMAGE_TAG=oldproj-v1" > /opt/oldproj/state/image.env
for u in deploy backup; do
  printf '[Timer]\nOnUnitActiveSec=1min\n[Install]\nWantedBy=timers.target\n' > "/etc/systemd/system/oldproj-$u.timer"
  printf '[Service]\nType=oneshot\nExecStart=/opt/oldproj/%s.sh\n' "$u" > "/etc/systemd/system/oldproj-$u.service"
done
systemctl enable --now oldproj-deploy.timer oldproj-backup.timer
(. /opt/oldproj/lib.sh && compose up -d --quiet-pull) > /dev/null 2>&1 || die "oldproj 启动失败"
for _ in $(seq 1 30); do curl -sf --noproxy '*' http://127.0.0.1:8080/api/health > /dev/null && break; sleep 1; done

ONECLICK_CMD=(bash -c "$(docker run --rm --entrypoint cat "$REG-web:staging" /forecast-kit/oneclick.sh)")
run_oneclick() { # $1 输出文件名
  set +e
  "${ONECLICK_CMD[@]}" < /dev/null 2>&1 | tee "$OUT/$1.txt"
  local rc=${PIPESTATUS[0]}
  set -e
  return "$rc"
}
# 用完整路径：fc 也是 bash 的内建命令（在服务器上总是 sudo fc，sudo 运行的是 /usr/local/bin/fc）
fcx() { /usr/local/bin/fc "$@" < /dev/null 2>&1 | tee -a "$OUT/fc.txt"; return "${PIPESTATUS[0]}"; }
running() { docker ps --filter "label=com.docker.compose.project=$1" -q | wc -l; }
enabled() { systemctl is-enabled -q "$1"; }
no_secret_anywhere() {
  ! grep -rqF "$SECRET_VALUE" "$OUT" /opt/forecast/logs 2> /dev/null || bad "密钥的值出现在了输出或日志里"
}
: > "$OUT/fc.txt"

if [ -z "${E2E_ONECLICK_CLEAN:-}" ]; then
  # ---------- ① 80 端口被别的程序占着 ----------
  say "① 80 端口被一个不是容器的程序占着（先停掉 oldproj 的 web，换成一个 python 程序占 80）"
  (. /opt/oldproj/lib.sh && compose stop web) > /dev/null 2>&1
  python3 -m http.server 80 --bind 127.0.0.1 > /dev/null 2>&1 &
  BLOCKER=$!
  for _ in $(seq 1 20); do (exec 3<> /dev/tcp/127.0.0.1/80) 2> /dev/null && break; sleep 0.5; done
  if run_oneclick 1-port-busy; then bad "端口被占时应该失败"; fi
  grep -q '✗ 第 1 步' "$OUT/1-port-busy.txt" || bad "应该停在第 1 步"
  grep -q '端口 80 被不是容器的程序占着' "$OUT/1-port-busy.txt" || bad "没有说出端口 80 被占"
  enabled oldproj-deploy.timer || bad "第 1 步失败时不应该动 oldproj 的定时器"
  [ ! -f /opt/forecast/.env ] || bad "第 1 步失败时不应该生成 .env"
  pass "停在第 1 步，说明了原因；oldproj 的定时器、容器都没动"
  kill "$BLOCKER"; wait "$BLOCKER" 2> /dev/null || true; BLOCKER=""
  (. /opt/oldproj/lib.sh && compose start web) > /dev/null 2>&1

  # ---------- ② 首次运行：证书申请失败（第 7 步）→ 修好再运行 ----------
  say "② 首次运行，证书申请失败（模拟 DNS 还没生效）"
  touch "$WORK/certbot-fail"
  if run_oneclick 2-cert-fail; then bad "证书申请失败时应该失败"; fi
  grep -q '✗ 第 7 步' "$OUT/2-cert-fail.txt" || bad "应该停在第 7 步"
  grep -q '证书没有申请下来' "$OUT/2-cert-fail.txt" || bad "没有说出证书的原因"
  [ "$(running oldproj)" = 0 ] || bad "oldproj 应该已经停了"
  ! enabled oldproj-deploy.timer || bad "oldproj 的定时器应该已经停用"
  ls /opt/oldproj/backup/*-before-switch.txt > /dev/null 2>&1 || bad "停 oldproj 前没有用它的 backup.sh 备份"
  pass "停在第 7 步：oldproj 已按顺序停（定时器 → 备份 → 容器），.env 已生成"
  rm -f "$WORK/certbot-fail"
  say "② 修好后再运行同一条命令"
fi
run_oneclick 3-first-ok || bad "首次运行（或修好后再运行）没有成功"
grep -q '✓ 第 9 步' "$OUT/3-first-ok.txt" || bad "没有走到第 9 步"
if [ -z "${E2E_ONECLICK_CLEAN:-}" ]; then
  grep -q '✓ 第 3 步：停掉 oldproj——已经停了' "$OUT/3-first-ok.txt" || bad "再运行时第 3 步应该跳过"
  grep -q '✓ 第 5 步：生成 .env——已经有了' "$OUT/3-first-ok.txt" || bad "再运行时 .env 应该不覆盖"
fi
[ "$(running forecast)" = 4 ] || bad "forecast 应该有 4 个容器在运行"
curl -sf --noproxy '*' http://127.0.0.1:8080/api/health | grep -q "\"version\":\"$REV\"" || bad "健康检查的版本不对"
enabled forecast-deploy.timer && enabled forecast-backup.timer || bad "forecast 的定时器应该启用"
[ "$(readlink /usr/local/bin/fc)" = /opt/forecast/fc.sh ] || bad "没有装好 /usr/local/bin/fc"
[ "$(stat -c %a /opt/forecast/.env)" = 600 ] || bad ".env 的权限不是 600"
ls /opt/forecast/logs/oneclick-*.log > /dev/null || bad "没有写日志文件"
grep -q "还没填的可选密钥： FC_MAIL_PASSWORD FC_GEMINI_API_KEY" "$OUT/3-first-ok.txt" || bad "摘要里没有列出没填的可选密钥"
for k in MYSQL_ROOT_PASSWORD FC_DB_PASSWORD FC_REDIS_PASSWORD FC_ADMIN_INIT_PASSWORD FC_PUSH_VAPID_PRIVATE_KEY; do
  v=$(sed -n "s/^$k=//p" /opt/forecast/.env)
  [ -n "$v" ] || bad "$k 没有生成"
  ! grep -rqF "$v" "$OUT" /opt/forecast/logs || bad "$k 的值出现在了输出或日志里"
done
pass "首次运行成功：4 个容器、版本 ${REV:0:12}、两个定时器、fc 命令、日志；生成的密码没有出现在任何输出里"

# ---------- ③ 再运行一次：全部跳过 ----------
say "③ 再运行一次"
started=$(docker inspect -f '{{.State.StartedAt}}' forecast-app-1 forecast-mysql-1)
run_oneclick 4-second-run || bad "第二次运行应该成功"
for s in '第 3 步：停掉 oldproj——已经停了' '第 4 步：部署工具包已是最新' '第 5 步：生成 .env——已经有了' '第 7 步：申请证书并启动——forecast 已经在运行'; do
  grep -q "✓ $s" "$OUT/4-second-run.txt" || bad "第二次运行应该跳过：$s"
done
[ "$started" = "$(docker inspect -f '{{.State.StartedAt}}' forecast-app-1 forecast-mysql-1)" ] || bad "第二次运行不应该重启容器"
pass "全部跳过，容器没有重启"

# ---------- ④ 来回切换 ----------
say "④ fc to-oldproj、fc to-forecast"
fcx status > /dev/null
fcx to-oldproj || bad "fc to-oldproj 失败"
[ "$(running forecast)" = 0 ] || bad "forecast 应该停了"
[ "$(running oldproj)" = 4 ] || bad "oldproj 应该有 4 个容器在运行"
enabled oldproj-deploy.timer && enabled oldproj-backup.timer || bad "oldproj 的定时器应该原样打开"
! enabled forecast-deploy.timer || bad "forecast 的定时器应该停用"
curl -sf --noproxy '*' http://127.0.0.1:8080/api/health | grep -q '"version":"oldproj-v1"' || bad "oldproj 的健康检查不对"
ls /opt/forecast/backup/forecast-*-before-stop.sql.gz > /dev/null 2>&1 || bad "停 forecast 时没有备份"
pass "切到 oldproj：forecast 已备份并停止，oldproj 和它的两个定时器原样启动"
fcx to-forecast || bad "fc to-forecast 失败"
[ "$(running oldproj)" = 0 ] || bad "oldproj 应该停了"
[ "$(running forecast)" = 4 ] || bad "forecast 应该在运行"
! enabled oldproj-deploy.timer || bad "oldproj 的定时器应该停用"
enabled forecast-deploy.timer || bad "forecast 的定时器应该打开"
[ "$(ls /opt/oldproj/backup/*-before-switch.txt | wc -l)" -ge 1 ] || bad "停 oldproj 时没有备份"
curl -sf --noproxy '*' http://127.0.0.1:8080/api/health | grep -q "\"version\":\"$REV\"" || bad "forecast 的健康检查不对"
fcx status > /dev/null
pass "切回 forecast：oldproj 先停定时器、备份、停容器，forecast 用原来的版本和数据启动"

# ---------- ⑤ fc keys、fc set-key ----------
say "⑤ fc keys、fc set-key"
fcx keys > /dev/null
grep -q '未填  FC_GEMINI_API_KEY' "$OUT/fc.txt" || bad "fc keys 应该显示 FC_GEMINI_API_KEY 未填"
if fcx set-key fc_gemini_api_key; then bad "小写变量名应该被拒绝"; fi
grep -q '变量名要全部大写：FC_GEMINI_API_KEY' "$OUT/fc.txt" || bad "小写变量名没有提示改成大写"
if fcx set-key FC_DB_PASSWORD; then bad "必填的自动生成变量不应该能用 set-key 改"; fi
grep -q '不认识变量名：FC_DB_PASSWORD' "$OUT/fc.txt" || bad "不在名单里的变量没有提示"
# 用伪终端模拟在控制台里粘贴（set-key 从 /dev/tty 读，输入不回显）
# 等提示出来、关掉回显以后再"粘贴"（人是看到提示才粘贴的；先送进去会被终端回显）
paste_in() { sleep 3; printf '%s\n' "$1"; }
paste_in 'has space' | script -qec "/usr/local/bin/fc set-key FC_GEMINI_API_KEY" /dev/null >> "$OUT/fc.txt" 2>&1 && bad "带空格的值应该被拒绝"
[ -z "$(sed -n 's/^FC_GEMINI_API_KEY=//p' /opt/forecast/.env)" ] || bad "被拒绝的值不应该写进 .env"
before=$(docker inspect -f '{{.Id}}' forecast-app-1)
paste_in "$SECRET_VALUE" | script -qec "/usr/local/bin/fc set-key FC_GEMINI_API_KEY" /dev/null >> "$OUT/fc.txt" 2>&1 || { tail -20 "$OUT/fc.txt"; bad "fc set-key 失败"; }
grep -q '已设置 FC_GEMINI_API_KEY（不显示值）' "$OUT/fc.txt" || bad "没有显示'已设置（不显示值）'"
grep -q '已生效，健康检查 UP' "$OUT/fc.txt" || bad "没有显示健康检查结果"
[ "$(sed -n 's/^FC_GEMINI_API_KEY=//p' /opt/forecast/.env)" = "$SECRET_VALUE" ] || bad ".env 里的值不对"
[ "$(docker exec forecast-app-1 printenv FC_GEMINI_API_KEY)" = "$SECRET_VALUE" ] || bad "容器没有拿到新值（reload-env 没生效）"
[ "$before" != "$(docker inspect -f '{{.Id}}' forecast-app-1)" ] || bad "后端容器应该按新配置重建"
[ "$(stat -c %a /opt/forecast/.env)" = 600 ] || bad ".env 的权限不是 600"
fcx keys > /dev/null
grep -q '已填  FC_GEMINI_API_KEY' "$OUT/fc.txt" || bad "fc keys 应该显示已填"
no_secret_anywhere
pass "set-key：隐藏输入、写进 .env（权限 600）、容器按新配置重建、健康检查 UP；值没有出现在任何输出和日志里；打错变量名有提示"

# ---------- ⑥ 另一个项目的备份失败 ----------
say "⑥ 另一个项目的备份失败时，fc to-forecast 停下来"
fcx to-oldproj > /dev/null || bad "fc to-oldproj 失败"
touch /opt/oldproj/FAIL_BACKUP
if fcx to-forecast; then bad "备份失败时应该停下"; fi
grep -q '没有停它的容器，已经把定时器恢复' "$OUT/fc.txt" || bad "没有说明备份失败后的处理"
[ "$(running oldproj)" = 4 ] || bad "备份失败时不应该停 oldproj 的容器"
enabled oldproj-deploy.timer && enabled oldproj-backup.timer || bad "备份失败后 oldproj 的定时器应该恢复"
rm -f /opt/oldproj/FAIL_BACKUP
fcx to-forecast > /dev/null || bad "修好后 fc to-forecast 应该成功"
[ "$(running forecast)" = 4 ] || bad "forecast 应该在运行"
pass "备份失败：没有停 oldproj 的容器、定时器恢复；修好后再切换成功"
no_secret_anywhere
say "演练全部通过"
