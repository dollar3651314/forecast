#!/usr/bin/env bash
# 原型构建：把客户端（/）、后台（/admin/）和官网（/site/，v2）合并成一个静态站点，输出到 dist/。
# 官网放在 /site/：客户端和后台的原型地址不变；官网的"注册""登录"跳同一个站点的 /register、/login（客户端原型）。
# Vercel 用它构建原型预览（vercel.json 的 buildCommand）；本地也可以直接运行。
set -euo pipefail
cd "$(dirname "$0")/.."
# 原型站点用浏览器内的模拟数据：显式设成 mock（客户端和后台默认都是 real，会去调用后端接口）。见 web/*/src/api/mode.ts
export VITE_API_MODE=mock
for app in client admin site; do
  echo "== 构建 web/$app"
  npm ci --prefix "web/$app" --no-audit --no-fund
  if [ "$app" = site ]; then
    SITE_BASE=/site/ npm run build --prefix "web/$app"
  else
    npm run build --prefix "web/$app"
  fi
done
rm -rf dist
mkdir -p dist/admin dist/site
cp -R web/client/dist/. dist/
cp -R web/admin/dist/. dist/admin/
cp -R web/site/dist/. dist/site/
echo "== 原型站点已生成：dist/（客户端 /，后台 /admin/，官网 /site/）"
