#!/usr/bin/env bash
# 从外部确认测试环境部署成功（S20，部署方案 v1.1 第 8 节）。在任何能上网的机器上运行，不需要登录服务器：
#   bash deploy/verify.sh test-app.<域名> test-admin.<域名> [期望的提交号] [官网域名 test-www.<域名>]
# 检查：版本、HTTPS 和证书、跳转、不该开放的路径和端口、页面能打开、测试环境标识；给了官网域名时再检查官网（AC2-10，部署方案第 14 节）。
# 只检查官网、不核对提交号时，第三个参数写 ""（例如 verify.sh test-app.x test-admin.x "" test-www.x）。
# 追踪号（X-Request-Id）；业务告警（v2 E8，只在后台域名的健康检查里有）只提示、不算部署失败。
# 真实 IP（伪造 X-Forwarded-For 不起作用）、WebSocket、冒烟流程要在浏览器里手动确认，见部署方案第 8 节。
set -uo pipefail
app=${1:?用法：verify.sh <客户端域名> <后台域名> [提交号]}
admin=${2:?用法：verify.sh <客户端域名> <后台域名> [提交号]}
want=${3:-}
www=${4:-}
fail=0
ok() { echo "  ✓ $1"; }
bad() { echo "  ✗ $1"; fail=1; }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }

echo "== 版本和健康检查"
h=$(curl -sf --max-time 10 "https://$app/api/health" || true)
case "$h" in *'"status":"UP"'*) ok "健康检查 UP" ;; *) bad "健康检查不是 UP：${h:-无法访问}" ;; esac
case "$h" in *'"env":"test"'*) ok "测试环境标识 env=test" ;; *) bad "健康检查里没有 env=test" ;; esac
# 业务告警只在后台域名的健康检查里有（客户端域名、官网不返回，v2 E8）；不影响部署是否成功（换版本解决不了），只提示去看告警邮件和日志
case "$h" in *'"alert'*) bad "客户端域名的健康检查不应该有 alert / alerts" ;; *) ok "客户端域名的健康检查不带业务告警" ;; esac
ah=$(curl -sf --max-time 10 "https://$admin/api/health" || true)
case "$ah" in
  *'"alert":"ALERT"'*) echo "  ! 有业务告警（不算部署失败）：$(printf '%s' "$ah" | sed -n 's/.*"alerts":{\([^}]*\)}.*/\1/p')" ;;
  *'"alert":"OK"'*) ok "没有业务告警（后台域名）" ;;
  *) bad "后台域名的健康检查里没有 alert 字段（后端或 Nginx 版本太旧？）" ;;
esac
# 追踪号：Nginx 生成，后端放进响应头
rid=$(curl -s -o /dev/null -D - --max-time 10 "https://$app/api/health" | tr -d '\r' | sed -n 's/^[Xx]-[Rr]equest-[Ii]d: *//p')
if printf '%s' "$rid" | grep -Eq '^[0-9a-f]{32}$'; then ok "响应头带追踪号 X-Request-Id"; else bad "响应头没有 X-Request-Id 或格式不对：${rid:-无}"; fi
if [ -n "$want" ]; then
  case "$h" in *"\"version\":\"$want\""*) ok "版本是 $want" ;; *) bad "版本不是 $want" ;; esac
fi

echo "== HTTPS"
[ "$(code "http://$app/")" = 301 ] && ok "http 跳转到 https" || bad "http 没有跳转"
for d in "$app" "$admin"; do
  if curl -s -o /dev/null --max-time 10 "https://$d/"; then ok "$d 证书有效"; else bad "$d 证书无效或无法访问"; fi
done

echo "== 页面"
[ "$(code "https://$app/")" = 200 ] && ok "客户端首页 200" || bad "客户端首页打不开"
[ "$(code "https://$admin/admin/")" = 200 ] && ok "后台首页 200" || bad "后台首页打不开"

echo "== 不该对外的路径"
# 带分号的写法（Spring 会忽略分号后面的内容）也要拦住（安全审查 SEC-02）
for u in "https://$app/api/admin/me" "https://$app/api/admin;x/me" "https://$app/api/e2e/mail/code" "https://$app/actuator/health" "https://$app/actuator/prometheus" "https://$admin/actuator/health" "https://$admin/actuator/prometheus" "https://$admin/api/ledger/entries"; do
  c=$(code "$u"); { [ "$c" = 404 ] || [ "$c" = 401 ]; } && ok "$c $u" || bad "$c $u（应为 404）"
done

if [ -n "$www" ]; then
  echo "== 官网 $www"
  [ "$(code "http://$www/")" = 301 ] && ok "http 跳转到 https" || bad "官网 http 没有跳转"
  if curl -s -o /dev/null --max-time 10 "https://$www/"; then ok "$www 证书有效"; else bad "$www 证书无效或无法访问（证书里有没有这个域名？见部署方案第 14 节）"; fi
  page=$(curl -s --max-time 10 "https://$www/" || true)
  case "$page" in *'name="fc-client-url"'*) ok "官网首页能打开" ;; *) bad "官网首页打不开或不是官网" ;; esac
  case "$page" in *"content=\"https://$app\""*) ok "注册、登录跳到 https://$app" ;; *) bad "官网页面里的客户端地址不是 https://$app" ;; esac
  for p in /terms /privacy /risk; do
    [ "$(code "https://$www$p")" = 200 ] && ok "官网 $p 200" || bad "官网 $p 打不开"
  done
  wh=$(curl -sf --max-time 10 "https://$www/api/health" || true)
  case "$wh" in *'"env":"test"'*) ok "官网的健康检查有测试环境标识" ;; *) bad "官网 /api/health 没有 env=test" ;; esac
  case "$wh" in *'"alert'*) bad "官网的健康检查不应该有 alert / alerts" ;; *) ok "官网的健康检查不带业务告警" ;; esac
  for u in "https://$www/api/me/session" "https://$www/api/admin/me" "https://$www/api/health;x" "https://$www/actuator/health"; do
    c=$(code "$u"); [ "$c" = 404 ] && ok "$c $u" || bad "$c $u（应为 404）"
  done
fi

echo "== 端口（只应开放 80、443；22 只对 Amos 的 IP 开放，这里看到不通是正常的）"
ip=$(getent hosts "$app" | awk '{print $1; exit}')
for p in 3306 6379 8080 8081 22; do
  if timeout 5 bash -c "exec 3<>/dev/tcp/$ip/$p" 2> /dev/null; then
    [ "$p" = 22 ] && echo "  ! 22 从本机可以连上（如果本机不是 Amos 的 IP，说明 SSH 对外开放了）" || bad "端口 $p 对外开放"
  else
    ok "端口 $p 不通"
  fi
done

echo
[ "$fail" = 0 ] && echo "全部通过" || echo "有检查没通过"
exit "$fail"
