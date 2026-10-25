#!/usr/bin/env bash
# 手动发布测试环境镜像：备用办法，GitHub Actions 不可用时代替 CI 的 publish-staging（部署方案第 20 节）。
# 2026-10-21 额度用完时写的；同一天仓库改为公开、Actions 恢复，平时不用。
# 在 Amos 自己的电脑上运行（macOS 或 Linux，要 git、curl、Docker Desktop / Docker Engine + buildx），和 CI 做的事一样：
#   取 origin/staging 最新提交 → 在容器里构建后端 jar 和三个前端 → 构建 linux/amd64 镜像（标签 = 提交号）并推送
#   → 最后移动 staging 标签（先 web 后 app）→ 测试服务器每分钟检查一次，自己换上新版本 → 等健康检查返回这个版本号
# 只发布已经合并到 staging 的提交；合并前的检查仍以 CI 为准，用本地完整检查代替 CI 要 Amos 同意（部署方案第 20 节）。
# 登录镜像仓库的令牌只由 Amos 自己保管：运行前先 `docker login ghcr.io -u dollar3651314`，密码填 GitHub 令牌（write:packages），
# 这个脚本不读、不存、不打印令牌。
#
# 用法（在仓库目录里）：
#   bash deploy/manual-publish.sh            构建、推送、等测试环境换上新版本
#   bash deploy/manual-publish.sh --no-push  只在本机构建镜像（检查能不能构建），不推送
set -euo pipefail

REG=ghcr.io/dollar3651314/forecast
APP_URL=${FC_STAGING_APP_URL:?缺少 FC_STAGING_APP_URL（测试环境客户端地址，例如 https://test-app.<域名>）}
MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
NODE_IMAGE=node:22
PUSH=1
[ "${1:-}" = "--no-push" ] && PUSH=0

say() { printf '\n== %s\n' "$*"; }
die() { printf '\n!! %s\n' "$*" >&2; exit 1; }

for c in git curl docker tar; do command -v "$c" >/dev/null || die "没有找到 $c，请先安装"; done
docker info >/dev/null 2>&1 || die "Docker 没有运行：请先打开 Docker Desktop"
docker buildx version >/dev/null 2>&1 || die "Docker 没有 buildx：请升级 Docker Desktop"

REPO_DIR=$(cd "$(dirname "$0")/.." && pwd)
say "取 staging 最新提交"
git -C "$REPO_DIR" fetch -q origin staging
SHA=$(git -C "$REPO_DIR" rev-parse origin/staging)
echo "要发布的版本：$SHA"
git -C "$REPO_DIR" log -1 --format='  %s' "$SHA"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/forecast-publish.XXXXXX")
cleanup() {
  # 容器里构建出来的文件在 Linux 上属于 root，用容器删
  docker run --rm -v "$WORK":/w "$NODE_IMAGE" rm -rf /w/src >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
mkdir -p "$WORK/src"
# 用 git archive 取一份干净的源码：不带本机没提交的改动
git -C "$REPO_DIR" archive "$SHA" | tar -x -C "$WORK/src"

say "构建后端 jar（容器里的 JDK 25 + Maven；依赖缓存在 Docker 卷 forecast-m2）"
docker run --rm -v "$WORK/src":/src -v forecast-m2:/root/.m2 -w /src "$MAVEN_IMAGE" \
  mvn -B -ntp -q -f backend package -DskipTests
ls "$WORK"/src/backend/target/forecast-*.jar >/dev/null 2>&1 || die "没有生成后端 jar"

say "构建客户端、后台、官网（容器里的 Node 22；真实接口模式，不设 VITE_API_MODE）"
docker run --rm -v "$WORK/src":/src -w /src "$NODE_IMAGE" sh -ec '
  for p in client admin site; do
    echo "-- web/$p"
    npm ci --prefix "web/$p" --no-audit --no-fund --loglevel=error
    npm run build --prefix "web/$p" >/dev/null
  done'
for p in client admin site; do [ -f "$WORK/src/web/$p/dist/index.html" ] || die "web/$p 没有构建出来"; done

# 服务器是 x86_64：镜像一律构建成 linux/amd64（Apple 芯片的 Mac 由 Docker Desktop 模拟，只影响构建速度）
if [ "$PUSH" = 0 ]; then
  say "只在本机构建镜像（--no-push）"
  docker buildx build --platform linux/amd64 --provenance=false --load -f "$WORK/src/deploy/app.Dockerfile" --build-arg FC_VERSION="$SHA" -t "$REG-app:$SHA" "$WORK/src"
  docker buildx build --platform linux/amd64 --provenance=false --load -f "$WORK/src/deploy/web.Dockerfile" --build-arg FC_VERSION="$SHA" -t "$REG-web:$SHA" "$WORK/src"
  docker image ls --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep "$SHA"
  say "构建成功，没有推送"
  exit 0
fi

say "构建并推送镜像（标签 = 提交号）"
docker buildx build --platform linux/amd64 --provenance=false --push -f "$WORK/src/deploy/web.Dockerfile" --build-arg FC_VERSION="$SHA" -t "$REG-web:$SHA" "$WORK/src" \
  || die "推送失败：是否已经 docker login ghcr.io？令牌要有 write:packages 权限"
docker buildx build --platform linux/amd64 --provenance=false --push -f "$WORK/src/deploy/app.Dockerfile" --build-arg FC_VERSION="$SHA" -t "$REG-app:$SHA" "$WORK/src"

# 和 CI 一样：两个版本号标签都推好以后，最后才移动 app 的 staging 标签（服务器看的是它）
say "移动 staging 标签（先 web 后 app）"
docker buildx imagetools create -t "$REG-web:staging" "$REG-web:$SHA"
docker buildx imagetools create -t "$REG-app:staging" "$REG-app:$SHA"

say "等测试环境换成这个版本（服务器每分钟检查一次，最多等 15 分钟）"
v=""
for _ in $(seq 1 90); do
  v=$(curl -sf --max-time 5 "$APP_URL/api/health" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' || true)
  if [ "$v" = "$SHA" ]; then say "测试环境已经是 $SHA，发布完成"; exit 0; fi
  sleep 10
done
die "15 分钟内测试环境没有换成 $SHA（现在是：${v:-无法访问}）。镜像已经推送；请把这段输出截图发给智能体，并在服务器上看 /opt/forecast/logs/deploy.log"
