#!/usr/bin/env bash
# 部署形态的端到端测试（复制源的端到端测试方案 第 9 节）：同一个主流程用例，浏览器经过测试环境真正用的镜像和 Nginx：
#   浏览器 → forecast-web（Nginx，HTTPS，两个假域名）→ forecast-app（profile e2e）→ MySQL、Redis 容器
# 用 deploy/kit/docker-compose.yml + e2e/compose.deployed.yml 启动（compose 项目名 forecast-e2e），每次都是新库。
#
# 用法（仓库根目录或任意目录）：bash e2e/run-deployed.sh [传给 playwright test 的参数]
# 需要：Docker（含 compose 插件）、JDK 25 + Maven、Node 22、openssl；本机的 80、443、8080 端口空闲。
# 可选环境变量：
#   E2E_SKIP_BUILD=1   不重新构建 jar 和两个前端（用已有的 backend/target、web/*/dist），镜像照样重新打包
#   E2E_MVN_ARGS       传给 Maven 的额外参数（例如本机离线时 -o）
#   E2E_KEEP=1         测试结束后不停容器、不删临时目录（排查用；之后 docker compose -p forecast-e2e down -v 并手动删目录）
#   E2E_BUILDX_CACHE=gha  用 docker buildx 和 GitHub Actions 缓存构建镜像（CI 的 e2e-deployed 设；本机不要设）
#   E2E_APP_DOMAIN、E2E_ADMIN_DOMAIN、E2E_WWW_DOMAIN  三个假域名（默认 test-app.e2e.test、test-admin.e2e.test、test-www.e2e.test（官网）；
#                      .test 是保留的顶级域名，不会解析到外网）
# 密码（数据库、Redis、第一个超管）每次随机生成，只写进临时目录的 .env，结束时连同临时目录一起删除，不进仓库、不打印。
# 日志：e2e/logs/deployed/（各容器日志、内存、耗时；不进仓库）。Playwright 报告同默认形态：e2e/playwright-report/。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
PROJECT=forecast-e2e
REG=ghcr.io/dollar3651314/forecast
TAG=e2e-local
APP_DOMAIN=${E2E_APP_DOMAIN:-test-app.e2e.test}
ADMIN_DOMAIN=${E2E_ADMIN_DOMAIN:-test-admin.e2e.test}
WWW_DOMAIN=${E2E_WWW_DOMAIN:-test-www.e2e.test}
LOGS=$ROOT/e2e/logs/deployed
START=$(date +%s)
mkdir -p "$LOGS"
: > "$LOGS/timing.txt"

say() { echo "== [$(($(date +%s) - START))s] $*" | tee -a "$LOGS/timing.txt"; }
die() { echo "错误：$*" >&2; exit 2; }
# 访问本机时不走代理（有的机器给 curl 配了代理）
lcurl() { curl --noproxy '*' "$@"; }

# ---------- 0. 检查 ----------
say "检查环境"
for c in docker openssl curl node npm; do command -v "$c" > /dev/null || die "缺少命令 $c"; done
docker compose version > /dev/null || die "缺少 docker compose 插件"
if docker compose ls -a --format json 2> /dev/null | grep -q "\"Name\":\"$PROJECT\""; then
  die "compose 项目 $PROJECT 已经存在（上次用 E2E_KEEP=1 留下的？）：先 docker compose -p $PROJECT down -v"
fi
busy=""
for p in 80 443 8080; do
  if (exec 3<> "/dev/tcp/127.0.0.1/$p") 2> /dev/null; then busy="$busy $p"; fi
done
[ -z "$busy" ] || die "本机端口${busy} 已被占用（部署形态要用 80、443 和 127.0.0.1:8080）。请先停掉占用的程序；本脚本不会去停别人的进程"

# ---------- 1. 构建 jar 和三个前端（客户端、后台、官网；真实接口模式） ----------
if [ -z "${E2E_SKIP_BUILD:-}" ]; then
  say "构建后端 jar"
  # shellcheck disable=SC2086
  mvn -B -ntp -q ${E2E_MVN_ARGS:-} -f backend package -DskipTests
  for app in client admin site; do
    say "构建 web/$app（真实接口模式）"
    [ -d "web/$app/node_modules" ] || npm ci --prefix "web/$app" --no-audit --no-fund
    (unset VITE_API_MODE; npm run build --prefix "web/$app" > "$LOGS/build-$app.log" 2>&1) || { tail -40 "$LOGS/build-$app.log" >&2; exit 1; }
  done
fi
ls backend/target/forecast-*.jar > /dev/null 2>&1 || die "没有 backend/target/forecast-*.jar"
for app in client admin site; do [ -f "web/$app/dist/index.html" ] || die "没有 web/$app/dist（去掉 E2E_SKIP_BUILD 重跑）"; done
[ -d e2e/node_modules ] || npm ci --prefix e2e --no-audit --no-fund

# ---------- 2. 构建两个镜像（和 CI 的 publish-staging 同样的 Dockerfile） ----------
SHA=$(git rev-parse HEAD 2> /dev/null || echo dev)
say "构建镜像 $REG-app:$TAG、$REG-web:$TAG（FC_VERSION=$SHA）"
# E2E_BUILDX_CACHE=gha（只在 CI 里设）：用 buildx 和 GitHub Actions 缓存（scope 和 ci.yml 的 publish-staging 相同），本机不设，照旧 docker build
build_image() { # $1 Dockerfile  $2 镜像标签  $3 缓存 scope
  if [ "${E2E_BUILDX_CACHE:-}" = gha ]; then
    docker buildx build -q --load --cache-from "type=gha,scope=$3" --cache-to "type=gha,mode=max,scope=$3" \
      -f "$1" --build-arg FC_VERSION="$SHA" -t "$2" . > /dev/null
  else
    docker build -q -f "$1" --build-arg FC_VERSION="$SHA" -t "$2" . > /dev/null
  fi
}
build_image deploy/app.Dockerfile "$REG-app:$TAG" forecast-app
build_image deploy/web.Dockerfile "$REG-web:$TAG" forecast-web

# ---------- 3. 临时目录：.env、state/image.env、自签名证书、数据目录 ----------
TMP=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/forecast-e2e.XXXXXX")
chmod 700 "$TMP"
compose() {
  docker compose --project-name "$PROJECT" --project-directory "$TMP" \
    -f "$ROOT/deploy/kit/docker-compose.yml" -f "$ROOT/e2e/compose.deployed.yml" \
    --env-file "$TMP/.env" --env-file "$TMP/state/image.env" "$@"
}
# shellcheck disable=SC2317  # 由 trap 调用
cleanup() {
  local rc=$?
  set +e
  if [ -f "$TMP/.env" ]; then
    compose ps -a > "$LOGS/ps.txt" 2>&1
    for s in web app mysql redis; do compose logs --no-color "$s" > "$LOGS/$s.log" 2>&1; done
  fi
  if [ -n "${E2E_KEEP:-}" ]; then
    echo "E2E_KEEP=1：容器和临时目录 $TMP 保留；用完执行 docker compose -p $PROJECT down -v，再删除该目录（里面有密码）"
  else
    [ -f "$TMP/.env" ] && compose down -v --remove-orphans > /dev/null 2>&1
    # 数据库文件属于容器里的 mysql 用户，用容器删（本机不是 root 时也能删干净）
    docker run --rm -v "$TMP:/t" --entrypoint sh redis:7 -c 'rm -rf /t/* /t/.[!.]*' > /dev/null 2>&1
    rm -rf "$TMP"
  fi
  say "结束（退出码 $rc），日志在 $LOGS"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

rnd() { echo "e2e$(openssl rand -hex 16)"; }
ADMIN_USER=e2e_admin
ADMIN_INIT=$(rnd)
ADMIN_NEW=$(rnd)
mkdir -p "$TMP/state" "$TMP/data/mysql" "$TMP/data/redis" "$TMP/data/storage" "$TMP/certs/live/forecast" "$TMP/certs/www"
(
  umask 077
  cat > "$TMP/.env" << EOF
APP_DOMAIN=$APP_DOMAIN
ADMIN_DOMAIN=$ADMIN_DOMAIN
WWW_DOMAIN=$WWW_DOMAIN
MYSQL_ROOT_PASSWORD=$(rnd)
FC_DB_PASSWORD=$(rnd)
FC_REDIS_PASSWORD=$(rnd)
FC_ADMIN_INIT_USERNAME=$ADMIN_USER
FC_ADMIN_INIT_PASSWORD=$ADMIN_INIT
E2E_CERT_DIR=$TMP/certs
EOF
)
printf 'IMAGE_TAG=%s\n' "$TAG" > "$TMP/state/image.env"
# 和 server-setup.sh 一样：后端容器用 uid 10001 运行，证件照片目录要能写
chown 10001 "$TMP/data/storage" 2> /dev/null || sudo -n chown 10001 "$TMP/data/storage" 2> /dev/null || chmod 777 "$TMP/data/storage"
# 一张证书同时包含三个域名，路径和 Let's Encrypt 的一样（live/forecast/），只给这次测试用
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=$APP_DOMAIN" \
  -addext "subjectAltName=DNS:$APP_DOMAIN,DNS:$ADMIN_DOMAIN,DNS:$WWW_DOMAIN" \
  -keyout "$TMP/certs/live/forecast/privkey.pem" -out "$TMP/certs/live/forecast/fullchain.pem" 2> /dev/null

# ---------- 4. 启动（新库：每次都是新的数据目录） ----------
say "启动容器（compose 项目 $PROJECT）"
# 刚构建的两个镜像在本机，不会去拉；mysql、redis 本机没有时（例如 CI）才拉
E2E_CERT_DIR="$TMP/certs" compose up -d --pull missing --quiet-pull > "$LOGS/up.log" 2>&1 || { cat "$LOGS/up.log" >&2; exit 1; }
say "等后端健康检查（版本 $SHA）"
ok=""
for _ in $(seq 1 120); do
  v=$(lcurl -sf --max-time 3 http://127.0.0.1:8080/api/health | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' || true)
  if [ "$v" = "$SHA" ]; then ok=1; break; fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$PROJECT-app-1" 2> /dev/null)" != true ] && [ "$(docker inspect -f '{{.RestartCount}}' "$PROJECT-app-1" 2> /dev/null || echo 0)" -ge 2 ]; then break; fi
  sleep 2
done
[ -n "$ok" ] || { echo "后端 4 分钟内没有通过健康检查，日志最后 80 行：" >&2; compose logs --no-color --tail 80 app >&2; exit 1; }
say "等 Nginx（https://$APP_DOMAIN）"
for _ in $(seq 1 30); do
  lcurl -skf --max-time 3 --resolve "$APP_DOMAIN:443:127.0.0.1" "https://$APP_DOMAIN/api/health" > /dev/null && break
  sleep 1
done
lcurl -skf --max-time 3 --resolve "$APP_DOMAIN:443:127.0.0.1" "https://$APP_DOMAIN/api/health" > /dev/null || { compose logs --no-color --tail 40 web >&2; die "Nginx 没有起来"; }

# ---------- 5. Playwright ----------
say "运行 Playwright（部署形态）"
set +e
(
  cd e2e
  E2E_MODE=deployed \
    E2E_CLIENT_URL="https://$APP_DOMAIN" \
    E2E_ADMIN_URL="https://$ADMIN_DOMAIN" \
    E2E_SITE_URL="https://$WWW_DOMAIN" \
    E2E_STUB_URL=http://127.0.0.1:8080 \
    FC_ADMIN_INIT_USERNAME="$ADMIN_USER" \
    FC_ADMIN_INIT_PASSWORD="$ADMIN_INIT" \
    E2E_ADMIN_NEW_PASSWORD="$ADMIN_NEW" \
    npx playwright test "$@"
)
rc=$?
set -e
say "Playwright 结束（退出码 $rc）"
# 跑完主流程后各容器的内存（和部署方案第 9 节的上限对照）
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' \
  "$PROJECT-web-1" "$PROJECT-app-1" "$PROJECT-mysql-1" "$PROJECT-redis-1" | tee "$LOGS/memory.txt"
exit "$rc"
