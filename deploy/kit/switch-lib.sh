# 一键部署（oneclick.sh）和切换命令（fc.sh）共用的函数（S12 一键部署）。不单独运行。
# shellcheck shell=bash
# shellcheck disable=SC2034  # OTHER_DISCOVER_PROBLEM、STOP_PROBLEM、START_PROBLEM 由调用方读取
# 测试服务器上 forecast 和另一个项目轮流运行（AQ-23，`部署方案/v1-测试环境与切换.md`）。本文件负责"另一个项目"：找到它、停它、原样启动它。
# - 不写另一个项目的名字和目录（scripts/brand-check.json：部署脚本里不写它的名字），按 systemd 找：
#   名字叫 <名字>-deploy.timer、<名字> 不是 forecast 的定时器；目录取 <名字>-deploy.service 的 ExecStart 所在目录（没有时 /opt/<名字>），
#   目录里要有 lib.sh（它的 compose 函数）和 backup.sh。找到后记在 /opt/forecast/state/other-project.env，以后直接用。
# - 停：先停它的定时器（每分钟自动部署会把容器拉起来）→ 等进行中的自动部署结束 → 用它自己的 backup.sh 备份 → 只停容器（compose stop，不删数据）。
#   停之前启用着哪些定时器记下来，启动时原样打开。
# - 启动：它自己的 lib.sh 的 compose up -d（用它上次运行的版本，不用 deploy.sh：版本没变时 deploy.sh 直接退出）→ 健康检查 → 打开记下的定时器。
# 调用前要设好 FC_HOME（forecast 目录，默认 /opt/forecast）。子命令的输出由调用方写进日志。

FC_HOME=${FC_HOME:-/opt/forecast}
FC_LABEL=${FC_PROJECT:-forecast}
OTHER_STATE="$FC_HOME/state/other-project.env"
OTHER_NAME=""
OTHER_DIR=""
OTHER_TIMERS_ENABLED=""

# 本机访问不走代理（有的机器给 curl 配了代理）
lcurl() { curl --noproxy '*' "$@"; }

# 找另一个项目。返回 0 找到了（OTHER_NAME、OTHER_DIR 已设）；1 没有别的项目；2 找到不止一个或者不完整（不猜，交给人看）
other_discover() {
  OTHER_NAME="" OTHER_DIR="" OTHER_TIMERS_ENABLED="" OTHER_DISCOVER_PROBLEM=""
  if [ -f "$OTHER_STATE" ]; then
    # shellcheck disable=SC1090
    . "$OTHER_STATE"
    if [ -n "$OTHER_NAME" ] && [ -f "$OTHER_DIR/lib.sh" ]; then return 0; fi
    OTHER_NAME="" OTHER_DIR="" OTHER_TIMERS_ENABLED=""
  fi
  local names n exe dir found=""
  names=$(systemctl list-unit-files --type=timer --no-legend '*-deploy.timer' 2> /dev/null | awk '{print $1}' \
    | sed -n 's/-deploy\.timer$//p' | grep -vx "$FC_LABEL" | grep -E '^[a-z0-9][a-z0-9_-]*$' | sort -u || true)
  [ -n "$names" ] || return 1
  for n in $names; do
    exe=$(systemctl show -p ExecStart --value "$n-deploy.service" 2> /dev/null | sed -n 's/.*path=\([^ ;]*\).*/\1/p' | head -1)
    if [ -n "$exe" ]; then dir=$(dirname "$exe"); else dir="/opt/$n"; fi
    if [ -f "$dir/lib.sh" ] && [ -f "$dir/backup.sh" ]; then
      [ -z "$found" ] || { OTHER_DISCOVER_PROBLEM="发现不止一个别的项目（$found、$n），不知道该停哪个"; return 2; }
      found=$n
      OTHER_NAME=$n
      OTHER_DIR=$dir
    else
      OTHER_DISCOVER_PROBLEM="有定时器 $n-deploy.timer，但在 $dir 下没有找到它的 lib.sh、backup.sh"
    fi
  done
  [ -n "$found" ] || return 2
  other_save
  return 0
}

# 把另一个项目的名字、目录、停之前启用着的定时器记下来（只有 root 能读）
other_save() {
  mkdir -p "$FC_HOME/state"
  (
    umask 077
    printf 'OTHER_NAME=%q\nOTHER_DIR=%q\nOTHER_TIMERS_ENABLED=%q\n' "$OTHER_NAME" "$OTHER_DIR" "$OTHER_TIMERS_ENABLED" > "$OTHER_STATE.new"
  )
  mv "$OTHER_STATE.new" "$OTHER_STATE"
}

# 另一个项目的全部定时器（不管启用没有）
other_timers() {
  systemctl list-unit-files --type=timer --no-legend "$OTHER_NAME-*.timer" 2> /dev/null | awk '{print $1}'
}

# 现在启用着或者在运行的定时器
other_live_timers() {
  local t
  for t in $(other_timers); do
    if [ "$(systemctl is-enabled "$t" 2> /dev/null)" = enabled ] || systemctl is-active -q "$t" 2> /dev/null; then echo "$t"; fi
  done
}

# 某个 compose 项目正在运行的容器名
running_of() { docker ps --filter "label=com.docker.compose.project=$1" --format '{{.Names}}'; }

# 某个 compose 项目的全部容器和状态（每行"  名字  状态"）
status_of() { docker ps -a --filter "label=com.docker.compose.project=$1" --format '{{.Names}}  {{.Status}}' | sort | sed 's/^/  /'; }

# 另一个项目是不是已经完全停了（容器都没在运行、定时器都停了）
other_is_stopped() {
  [ -z "$(running_of "$OTHER_NAME")" ] && [ -z "$(other_live_timers)" ]
}

# 停另一个项目。返回 0 成功；非 0 时 STOP_PROBLEM 说明原因（已经尽量恢复到停之前的样子）
other_stop() {
  STOP_PROBLEM=""
  local live t state running i
  live=$(other_live_timers | tr '\n' ' ' | sed 's/ $//')
  if [ -n "$live" ]; then
    OTHER_TIMERS_ENABLED=$live
    other_save
    echo "  停用定时器：$live"
    # shellcheck disable=SC2086
    systemctl disable --now $live
  fi
  for t in $(other_timers); do
    echo "  $t：$(systemctl is-enabled "$t" 2> /dev/null || true) / $(systemctl is-active "$t" 2> /dev/null || true)"
  done
  # 等正在进行的自动部署结束（它的 deploy.service 是 oneshot，运行时是 activating）
  for i in $(seq 1 180); do
    state=$(systemctl is-active "$OTHER_NAME-deploy.service" 2> /dev/null || true)
    case "$state" in activating | active | reloading | deactivating) ;; *) break ;; esac
    [ "$i" = 1 ] && echo "  它的自动部署正在进行（$state），等它结束（最多 15 分钟）"
    sleep 5
  done
  case "$(systemctl is-active "$OTHER_NAME-deploy.service" 2> /dev/null || true)" in
    activating | active | reloading | deactivating)
      STOP_PROBLEM="它的自动部署 15 分钟还没结束"
      return 1
      ;;
  esac

  running=$(running_of "$OTHER_NAME")
  if [ -z "$running" ]; then
    echo "  它的容器已经停了"
    return 0
  fi
  # 用它自己的备份脚本备份一次（容器在运行时才能导出数据库）
  if [ -n "$(docker ps -q --filter "label=com.docker.compose.project=$OTHER_NAME" --filter label=com.docker.compose.service=mysql)" ]; then
    echo "  备份：bash $OTHER_DIR/backup.sh before-switch"
    if ! bash "$OTHER_DIR/backup.sh" before-switch < /dev/null; then
      STOP_PROBLEM="它自己的备份脚本 $OTHER_DIR/backup.sh 失败了；为了不丢数据，没有停它的容器，已经把定时器恢复成原来的样子"
      # shellcheck disable=SC2086
      [ -z "$live" ] || systemctl enable --now $live
      return 1
    fi
  else
    echo "  它的数据库容器没有在运行，跳过备份"
  fi
  echo "  只停止容器（不删容器、不删数据）"
  bash -c ". '$OTHER_DIR/lib.sh' && compose stop" < /dev/null
  running=$(running_of "$OTHER_NAME")
  if [ -n "$running" ]; then
    STOP_PROBLEM="停止后还有容器在运行：$(echo "$running" | tr '\n' ' ')"
    return 1
  fi
  status_of "$OTHER_NAME"
  return 0
}

# 等 127.0.0.1:8080 的健康检查是 UP（want 不空时版本号也要对得上），最多 3 分钟
wait_up() {
  local want=$1 body got _
  for _ in $(seq 1 90); do
    body=$(lcurl -sf --max-time 3 http://127.0.0.1:8080/api/health || true)
    got=$(printf '%s' "$body" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
    case "$body" in *'"status":"UP"'*)
      if [ -z "$want" ] || [ "$got" = "$want" ]; then return 0; fi
      ;;
    esac
    sleep 2
  done
  return 1
}

# 原样启动另一个项目：它上次运行的版本 + 停之前启用着的定时器。返回非 0 时 START_PROBLEM 说明原因
other_start() {
  START_PROBLEM=""
  local want timers
  want=$(sed -n 's/^IMAGE_TAG=//p' "$OTHER_DIR/state/image.env" 2> /dev/null | tail -1)
  echo "  启动容器（用它上次运行的版本${want:+ $want}）：$OTHER_DIR/lib.sh 的 compose up -d"
  if ! bash -c ". '$OTHER_DIR/lib.sh' && compose up -d" < /dev/null; then
    START_PROBLEM="docker compose up 失败"
    return 1
  fi
  echo "  等健康检查（最多 3 分钟）"
  if ! wait_up "$want"; then
    START_PROBLEM="3 分钟内健康检查没有通过${want:+（要的版本 $want）}；容器保持运行，定时器没有打开"
    return 1
  fi
  echo "  健康检查通过"
  timers=$OTHER_TIMERS_ENABLED
  [ -n "$timers" ] || timers=$(other_timers | tr '\n' ' ')
  if [ -n "$timers" ]; then
    echo "  打开它的定时器：$timers"
    # shellcheck disable=SC2086
    systemctl enable --now $timers
  fi
  status_of "$OTHER_NAME"
  return 0
}

# 端口 80、443、8080 被谁占着：每行"端口 容器名"或"端口 进程:描述"
port_holders() {
  local p c
  for p in 80 443 8080; do
    c=$(docker ps --filter "publish=$p" --format '{{.Names}}' | head -1)
    if [ -n "$c" ]; then
      echo "$p $c"
    elif command -v ss > /dev/null; then
      if ss -ltnH "sport = :$p" | grep -q .; then echo "$p 进程:$(ss -ltnpH "sport = :$p" | head -1 | tr -s ' ' | cut -c1-120)"; fi
    elif (exec 3<> "/dev/tcp/127.0.0.1/$p") 2> /dev/null; then
      # 没有 ss 的机器（Ubuntu 服务器上都有）：能连上就说明有程序在听
      echo "$p 进程:（不知道是哪个程序，这台机器上没有 ss 命令）"
    fi
  done
}

# forecast 是不是在运行（4 个容器都在运行）
forecast_running() { [ "$(running_of "$FC_LABEL" | wc -l)" -ge 4 ]; }

# forecast 的定时器是不是启用着
forecast_timers_on() {
  [ "$(systemctl is-enabled forecast-deploy.timer 2> /dev/null)" = enabled ] || [ "$(systemctl is-enabled forecast-backup.timer 2> /dev/null)" = enabled ]
}
