#!/usr/bin/env bash
# 本机端到端（forecast S19）：用真实的配置 cf/wrangler.toml（正式版）和构建好的 cf/dist，在 wrangler dev（本地 workerd + 本地 D1）上走一遍：
#   页面（首页是等候页、官网 /about/ 和法律页、交易接口 404）→ 留邮箱 → 确认 → 退订；陷阱字段；按 IP 限次；定时任务取行情 → 看板。
# 没有 Resend key：邮件不发（日志里 mail.not_configured），确认 / 退订的随机串从本地 D1 里读。
# 用法：bash cf/scripts/e2e-local.sh（先 node cf/scripts/build.mjs）。每步一行 ✓ / ✗，失败时停下并打印 wrangler 的日志。
# 定时任务那一步要访问 OKX / 币安（真实网络）；两个都取不到时只记"看板：暂时取不到"，不算失败（CI 的出口不一定能访问）。
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${E2E_PORT:-8799}"
BASE="http://127.0.0.1:$PORT"
STATE="$(mktemp -d)"
LOG="$STATE/wrangler.log"
export WRANGLER_SEND_METRICS=false
PID=""
# 停掉一个进程和它的全部子孙进程（npx → wrangler → workerd）
killtree() {
  local kids
  kids=$(ps -eo pid=,ppid= | awk -v p="$1" '$2 == p { print $1 }')
  for k in $kids; do killtree "$k"; done
  kill "$1" 2>/dev/null || true
}
cleanup() {
  if [ -n "$PID" ]; then killtree "$PID"; wait "$PID" 2>/dev/null || true; fi
  rm -f wrangler.e2e.toml
  rm -rf "$STATE"
}
trap cleanup EXIT
ok() { echo "✓ $*"; }
fail() { echo "✗ $*" >&2; echo "---- wrangler 日志（最后 60 行）----" >&2; tail -60 "$LOG" >&2 || true; exit 1; }
[ -f dist/index.html ] || fail "没有 cf/dist：先运行 node cf/scripts/build.mjs"
curl -s "http://127.0.0.1:$PORT" > /dev/null 2>&1 && fail "端口 $PORT 已经有程序在用（换一个：E2E_PORT=…）"

# 本机用的配置：和正式版一样，只多一条——/__scheduled 先进 Worker（wrangler dev 的 --test-scheduled 用它触发定时任务；
# 正式配置里只有 /api/* 先进 Worker，/__scheduled 会被当成页面）。cleanup 时删掉。
sed 's#^run_worker_first = \["/api/\*"\]#run_worker_first = ["/api/*", "/__scheduled"]#' wrangler.toml > wrangler.e2e.toml
grep -q '"/__scheduled"' wrangler.e2e.toml || fail "生成本机配置失败"
npx wrangler d1 migrations apply forecast-public -c wrangler.e2e.toml --local --env="" --persist-to "$STATE/d1" > "$STATE/migrate.log" 2>&1 || { cat "$STATE/migrate.log"; fail "本地 D1 迁移失败"; }
ok "本地 D1 迁移"

npx wrangler dev -c wrangler.e2e.toml --env="" --local --ip 127.0.0.1 --port "$PORT" --persist-to "$STATE/d1" --test-scheduled --show-interactive-dev-session=false \
  --var "PUBLIC_BASE_URL:$BASE" --var "VERSION:e2e-local" > "$LOG" 2>&1 &
PID=$!
for _ in $(seq 1 60); do
  curl -sf "$BASE/api/health" > /dev/null 2>&1 && break
  kill -0 "$PID" 2>/dev/null || fail "wrangler dev 没有启动"
  sleep 1
done
curl -sf "$BASE/api/health" > "$STATE/h.json" || fail "健康检查打不开"
grep -q '"status":"UP"' "$STATE/h.json" && grep -q '"version":"e2e-local"' "$STATE/h.json" || fail "健康检查：$(cat "$STATE/h.json")"
grep -q '"env"' "$STATE/h.json" && fail "正式版的健康检查不应该有 env"
ok "wrangler dev 启动，健康检查 UP、版本 e2e-local、mail 未开通：$(grep -o '"mail":"[a-z_]*"' "$STATE/h.json")"

sql() { npx wrangler d1 execute forecast-public -c wrangler.e2e.toml --local --env="" --persist-to "$STATE/d1" --json --command "$1" 2>/dev/null; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
post() { curl -s -w '\n%{http_code}' -H 'Content-Type: application/json' -H "CF-Connecting-IP: $1" -X POST "$BASE$2" --data "$3"; }

# 页面
curl -sf "$BASE/" | grep -q 'name="fc-site-mode" content="prelaunch"' || fail "首页不是公开版"
curl -sf "$BASE/?src=threads&cmp=tw1" | grep -q '即將上線' || fail "首页标题不是繁中"
curl -sf "$BASE/data/confirm?t=x" | grep -q 'fc-site-mode' || fail "确认页没有回到等候页的单页应用"
curl -sf "$BASE/about/" | grep -q '/about/assets/' || fail "官网 /about/ 打不开"
curl -sf "$BASE/about/terms" | grep -q '/about/assets/' || fail "官网法律页 /about/terms 打不开"
curl -sf "$BASE/robots.txt" | grep -q 'Disallow: /api/' || fail "robots.txt 不对"
[ "$(curl -sI "$BASE/" | grep -ci '^x-frame-options: DENY')" = 1 ] || fail "页面没有安全头"
ok "页面：首页是等候页（繁中）、确认页、官网和法律页、robots、安全头"
for p in /api/auth/login /api/auth/register /api/me/session /api/markets /api/bets /api/wallet/deposit-address /api/admin/ /api/ws/user; do
  [ "$(status "$BASE$p")" = 404 ] || fail "交易 / 登录接口 $p 不是 404"
done
ok "交易、登录、后台接口一律 404（没配 Access 时 /api/admin/ 也是 404）"

# 留邮箱 → 确认 → 退订
EMAIL="e2e-$(date +%s)@Example.com"
t0=$(date +%s%3N)
r=$(post 198.51.100.1 /api/public/waitlist "{\"email\":\"$EMAIL\",\"lang\":\"zh-TW\",\"website\":\"\",\"attribution\":{\"src\":\"threads\",\"cmp\":\"tw1\"}}")
t1=$(date +%s%3N)
[ "$(echo "$r" | tail -1)" = 200 ] && echo "$r" | head -1 | grep -q '"status":"check_email"' || fail "留邮箱：$r"
LOWER=$(echo "$EMAIL" | tr 'A-Z' 'a-z')
row=$(sql "SELECT status, lang, src, cmp, confirm_token, unsub_token FROM waitlist WHERE email = '$LOWER'")
echo "$row" | grep -q '"status": "PENDING"' && echo "$row" | grep -q '"src": "threads"' || fail "订阅没记下：$row"
CT=$(echo "$row" | grep -o '"confirm_token": "[0-9a-f]*"' | grep -o '[0-9a-f]\{32\}')
UT=$(echo "$row" | grep -o '"unsub_token": "[0-9a-f]*"' | grep -o '[0-9a-f]\{32\}')
grep -q 'mail.not_configured' "$LOG" || fail "没填 Resend key 时应该记 mail.not_configured"
ok "留邮箱：check_email，待确认，来源 threads / tw1（$((t1 - t0)) ms），没填 key 不发信"
r=$(post 198.51.100.2 /api/public/waitlist "{\"email\":\"$EMAIL\",\"lang\":\"zh-TW\",\"website\":\"\"}")
echo "$r" | head -1 | grep -q '"status":"check_email"' || fail "已在名单的邮箱回答不一样：$r"
ok "已在名单的邮箱：回答一样"
r=$(post 198.51.100.3 /api/public/waitlist/confirm "{\"token\":\"$CT\"}")
echo "$r" | head -1 | grep -q '"status":"confirmed","lang":"zh-TW"' || fail "确认：$r"
r=$(post 198.51.100.3 /api/public/waitlist/confirm "{\"token\":\"$(printf 'f%.0s' $(seq 1 32))\"}")
[ "$(echo "$r" | tail -1)" = 404 ] || fail "无效的确认链接应该 404：$r"
ok "确认：confirmed；无效链接 404"
r=$(post 198.51.100.4 /api/public/waitlist/unsubscribe "{\"token\":\"$UT\"}")
echo "$r" | head -1 | grep -q '"status":"unsubscribed"' || fail "退订：$r"
sql "SELECT status FROM waitlist WHERE email = '$LOWER'" | grep -q UNSUBSCRIBED || fail "退订没写进 D1"
ok "退订：unsubscribed"

# 陷阱字段
BOT="bot-$(date +%s)@example.com"
r=$(post 198.51.100.5 /api/public/waitlist "{\"email\":\"$BOT\",\"lang\":\"en\",\"website\":\"http://spam\"}")
echo "$r" | head -1 | grep -q '"status":"check_email"' || fail "陷阱字段：$r"
sql "SELECT COUNT(*) AS n FROM waitlist WHERE email = '$BOT'" | grep -q '"n": 0' || fail "陷阱字段有值的不应该记下"
ok "陷阱字段：照样回答，不记"

# 按 IP 限次（同一个 IP 第 11 次 429）
for i in $(seq 1 10); do
  r=$(post 198.51.100.9 /api/public/waitlist "{\"email\":\"rl-$i-$(date +%s)@example.com\",\"lang\":\"en\",\"website\":\"\"}")
  [ "$(echo "$r" | tail -1)" = 200 ] || fail "限次前第 $i 次：$r"
done
r=$(post 198.51.100.9 /api/public/waitlist "{\"email\":\"rl-11@example.com\",\"lang\":\"en\",\"website\":\"\"}")
[ "$(echo "$r" | tail -1)" = 429 ] && echo "$r" | head -1 | grep -q 'RATE_LIMITED' || fail "第 11 次应该 429：$r"
[ "$(post 198.51.100.10 /api/public/waitlist "{\"email\":\"rl-other@example.com\",\"lang\":\"en\",\"website\":\"\"}" | tail -1)" = 200 ] || fail "别的 IP 不应该受影响"
ok "按 IP 限次：同一 IP 第 11 次 429，别的 IP 照常"

# 定时任务 → 看板
curl -sf "$BASE/__scheduled?cron=*/5+*+*+*+*" > /dev/null || fail "触发定时任务失败"
for _ in $(seq 1 20); do
  b=$(curl -sf "$BASE/api/public/board")
  echo "$b" | grep -q '"available":true' && break
  sleep 1
done
t0=$(date +%s%3N); b=$(curl -sf "$BASE/api/public/board"); t1=$(date +%s%3N)
if echo "$b" | grep -q '"available":true'; then
  ok "定时任务取到行情：$(echo "$b" | grep -o '"price":"[0-9.]*"'), $(echo "$b" | grep -o '"source":"[A-Za-z]*"'), $(echo "$b" | grep -o '"upPct":"[0-9.]*"')（看板接口 $((t1 - t0)) ms）"
else
  echo "· 看板：两个源都取不到（本机网络），暂时取不到：$(curl -sf "$BASE/api/health" | grep -o '"sources":\[[^]]*\]')"
fi
echo "本机端到端全部通过"
