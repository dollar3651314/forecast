#!/usr/bin/env bash
# 改了 .env 以后让它生效（forecast S12）。用 root 运行：
#   sudo bash /opt/forecast/reload-env.sh
# 为什么要有：deploy.sh 只在 staging 有新版本时才动，版本没变时直接退出，改了 .env（邮件、Gemini、体育数据源、Telegram、合约地址……）不会生效。
# 做的事：确认本项目正在运行 → 等正在进行的自动更新结束 → 用当前版本 docker compose up -d（只重建配置变了的容器，数据目录不动）
#   → 3 分钟内健康检查通过 → 列出健康检查 alerts 里还是 DOWN 的项（没填的可选功能会显示在这里，属于正常）。
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "请用 root 运行（sudo）" >&2; exit 1; }
. "${FC_DIR:-/opt/forecast}/lib.sh"

cur=$(current_tag)
[ -n "$cur" ] || { echo "还没有部署过（state/image.env 里没有版本），先运行 first-start.sh" >&2; exit 1; }
busy=$(docker ps --filter publish=443 --format '{{.Names}}' | grep -v "^${FC_PROJECT}-" || true)
[ -z "$busy" ] || { echo "另一个项目正在运行（$busy 占着 443 端口），先切换到本项目（start.sh）再改配置" >&2; exit 1; }
chmod 600 "$FC_DIR/.env"

exec 9> "$FC_DIR/state/deploy.lock"
flock -w 900 9 || { echo "自动更新正在进行，等了 15 分钟还没结束；看 /opt/forecast/logs/deploy.log，稍后再运行" >&2; exit 1; }
echo "== 用版本 $cur 按新的 .env 重新启动（只重建配置变了的容器）"
compose up -d
if wait_healthy "$(revision "$REG-app:$cur")"; then
  log "已按新的 .env 重新启动（版本 $cur）"
  down=$(alerts_of "$(curl -sf --max-time 5 http://127.0.0.1:8080/api/health || true)")
  echo
  echo "完成。健康检查通过（版本 $cur）。"
  if [ -n "$down" ]; then
    echo "健康检查 alerts 里是 DOWN 的项（没填的可选功能会在这里，属于正常；刚填的那一项应该已经不在了）：$down"
  else
    echo "健康检查 alerts 里没有 DOWN 的项"
  fi
else
  log "按新的 .env 重新启动后 3 分钟内没有通过健康检查（版本 $cur）"
  echo "后端 3 分钟内没有通过健康检查。看日志：sudo bash -c '. /opt/forecast/lib.sh && compose logs --tail 80 app'；或者把 .env 改回去再运行一次" >&2
  exit 1
fi
