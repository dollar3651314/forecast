# 部署脚本的公共函数（S20）。由 deploy.sh、rollback.sh、backup.sh、restore.sh、first-start.sh 引用，不单独运行。
# shellcheck shell=bash
FC_DIR=/opt/forecast
REG=ghcr.io/dollar3651314/forecast
cd "$FC_DIR" || exit 1
mkdir -p "$FC_DIR/state" "$FC_DIR/logs" "$FC_DIR/backup"

# 拉取镜像。FC_OFFLINE_TEST=1 只用于在开发机上演练（镜像已在本机），服务器上不要设
pull() {
  [ -n "${FC_OFFLINE_TEST:-}" ] && return 0
  docker pull -q "$1" > /dev/null
}

# 带上两个变量文件运行 docker compose：.env（密钥和域名，Amos 填）、state/image.env（当前版本）
compose() {
  docker compose --project-name forecast --project-directory "$FC_DIR" -f "$FC_DIR/docker-compose.yml" \
    --env-file "$FC_DIR/.env" --env-file "$FC_DIR/state/image.env" "$@"
}

log() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$FC_DIR/logs/deploy.log"
}

# 当前运行的版本（提交号），没有时为空
current_tag() {
  sed -n 's/^IMAGE_TAG=//p' "$FC_DIR/state/image.env" 2>/dev/null || true
}

# 镜像里记录的提交号
revision() {
  docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1"
}

# 等后端健康检查通过，并且返回的版本号等于要的提交号（最多 3 分钟）。
# 只看"应用活着"：HTTP 200、"status":"UP"（应用、数据库、Redis、模块配置）和 version。
# 业务告警（"alert":"ALERT"、alerts 里的扫块停滞、节点不通、邮件失败，v2 E8）不算失败：
# 这些是外部服务的问题，换回旧版本也解决不了，不能因此回滚；它们另外发告警邮件。告警写进部署日志，方便对照
wait_healthy() {
  local want=$1 body got _
  for _ in $(seq 1 90); do
    body=$(curl -sf --max-time 3 http://127.0.0.1:8080/api/health || true)
    got=$(printf '%s' "$body" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
    if [ "$got" = "$want" ] && health_up "$body"; then
      case "$body" in *'"alert":"ALERT"'*) log "注意：新版本已通过健康检查，但有业务告警（不影响更新）：$(alerts_of "$body")" ;; esac
      return 0
    fi
    sleep 2
  done
  return 1
}

# 健康检查的整体状态是 UP（JSON 里第一个 "status"；alerts 里的项不叫 status，不会混淆）
health_up() {
  case "$1" in *'"status":"UP"'*) return 0 ;; *) return 1 ;; esac
}

# 取出 alerts 里 DOWN 的项，例如 market node.TRC20（没有时为空）
alerts_of() {
  printf '%s' "$1" | sed -n 's/.*"alerts":{\([^}]*\)}.*/\1/p' | tr ',' '\n' | sed -n 's/^"\([^"]*\)":"DOWN"$/\1/p' | tr '\n' ' '
}

# 换到某个版本（两个镜像用同一个提交号）
switch_to() {
  printf 'IMAGE_TAG=%s\n' "$1" > "$FC_DIR/state/image.env.new"
  mv "$FC_DIR/state/image.env.new" "$FC_DIR/state/image.env"
  compose up -d --remove-orphans
}

# 本机只保留最近 5 个版本的镜像（GitHub 镜像仓库保留 10 个），正在用的和上一个版本不删
prune_images() {
  local keep cur prev
  cur=$(current_tag)
  prev=$(cat "$FC_DIR/state/previous" 2>/dev/null || true)
  for repo in "$REG-app" "$REG-web"; do
    keep=0
    docker image ls "$repo" --format '{{.Tag}}' | while read -r tag; do
      [ "$tag" = "staging" ] && continue
      keep=$((keep + 1))
      if [ "$keep" -gt 5 ] && [ "$tag" != "$cur" ] && [ "$tag" != "$prev" ]; then
        docker image rm "$repo:$tag" > /dev/null 2>&1 || true
      fi
    done
  done
  docker image prune -f > /dev/null 2>&1 || true
}

# 从前端镜像里取出部署工具包（本目录的脚本、compose、systemd 文件），更新到服务器上。
# 用"写临时文件再改名"的方式替换，正在运行的脚本不受影响；.env、data/、state/、backup/ 不动。
sync_kit() {
  local tag=$1 tmp f
  tmp=$(mktemp -d)
  docker run --rm --entrypoint sh -v "$tmp:/out" "$REG-web:$tag" -c 'cp -r /forecast-kit/. /out/'
  for f in "$tmp"/* "$tmp"/systemd/*; do
    [ -f "$f" ] || continue
    local rel=${f#"$tmp"/}
    mkdir -p "$(dirname "$FC_DIR/$rel")"
    cp "$f" "$FC_DIR/$rel.new" && mv "$FC_DIR/$rel.new" "$FC_DIR/$rel"
  done
  chmod 755 "$FC_DIR"/*.sh
  rm -rf "$tmp"
}
