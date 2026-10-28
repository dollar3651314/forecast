#!/usr/bin/env bash
# 部署到 Cloudflare（forecast S19，K3）：CI 的 deploy-cf job 在全部检查通过后运行；可以重复运行。
#   bash cf/scripts/deploy.sh staging      推到 staging 分支时：测试版 forecast-public-staging（workers.dev，Access 保护）
#   bash cf/scripts/deploy.sh production   推到 main 分支时：正式版 forecast-public（Amos 在控制台绑 www.quickcome.online 以后才对外）
# 步骤（每步一行 ✓ / ✗）：D1 没有就建（ensure-d1.mjs）→ 执行没执行过的 D1 迁移 → 部署（带提交号）→ 正式版已经绑了域名时，等健康检查换成这个版本。
# 前提：已经 node cf/scripts/build.mjs（cf/dist）。需要 CLOUDFLARE_API_TOKEN、CLOUDFLARE_ACCOUNT_ID；没配时只提示并成功退出（没配置不是故障，C103）。
set -euo pipefail
cd "$(dirname "$0")/.."
TARGET="${1:-}"
case "$TARGET" in
  staging) E="staging"; DB="forecast-public-staging" ;;
  production) E=""; DB="forecast-public" ;;
  *) echo "用法：bash cf/scripts/deploy.sh staging|production" >&2; exit 2 ;;
esac
if [ -z "${CLOUDFLARE_API_TOKEN:-}" ] || [ -z "${CLOUDFLARE_ACCOUNT_ID:-}" ]; then
  echo "· 还没配置 Cloudflare（GitHub Secrets 里没有 CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID），跳过部署。配置方法：部署方案/v1-公开部分Cloudflare操作清单.md 第 2 步"
  exit 0
fi
export WRANGLER_SEND_METRICS=false
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
trap 'rm -f wrangler.deploy.toml' EXIT
[ -f dist/index.html ] || { echo "✗ 没有 cf/dist：先运行 node cf/scripts/build.mjs" >&2; exit 1; }

node scripts/ensure-d1.mjs --env "$TARGET"
npx wrangler d1 migrations apply "$DB" --remote -c wrangler.deploy.toml --env="$E" || { echo "✗ D1 迁移失败：看上面 wrangler 的错误（令牌要有 D1 Edit 权限）" >&2; exit 1; }
echo "✓ D1 迁移（只执行没执行过的）"
npx wrangler deploy -c wrangler.deploy.toml --env="$E" --var "VERSION:$SHA" || { echo "✗ 部署失败：看上面 wrangler 的错误（令牌要有 Workers Scripts Edit 权限）" >&2; exit 1; }
echo "✓ 部署 $TARGET（版本 $SHA）"

if [ "$TARGET" = production ]; then
  URL="https://www.quickcome.online/api/health"
  body=$(curl -s --max-time 10 "$URL" || true)
  if echo "$body" | grep -q '"environment":"production"'; then
    for _ in $(seq 1 12); do
      echo "$body" | grep -q "\"version\":\"$SHA\"" && { echo "✓ $URL 已是这个版本"; exit 0; }
      sleep 5
      body=$(curl -s --max-time 10 "$URL" || true)
    done
    echo "✗ 一分钟内 $URL 没有换成 $SHA（现在：$(echo "$body" | grep -o '"version":"[^"]*"')）" >&2
    exit 1
  fi
  echo "· www.quickcome.online 还没绑到这个 Worker（操作清单第 5 步），跳过对外检查"
else
  echo "· 测试版在 workers.dev 上、有 Access 保护，CI 不能直接访问；Amos 登录后打开 /api/health 看版本（操作清单第 4 步）"
fi
