# 转发到后端（S20）。客户端真实 IP 用 $remote_addr **覆盖** X-Forwarded-For（不追加），
# 客户端自己带的 X-Forwarded-For 不会传给后端；后端只信任内网代理传来的值（server.forward-headers-strategy: native）。
set $forecast_app http://app:8080;
proxy_pass $forecast_app;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host "";
# 追踪号（v2 E9）：每个请求 Nginx 生成一个 $request_id（32 位十六进制），覆盖客户端自己带的 X-Request-Id；
# 后端（common/trace/TraceIdFilter）沿用它写日志、放进响应头和错误响应体。Nginx 的访问日志里也有，可以把两边的日志对上
proxy_set_header X-Request-Id $request_id;
# 健康检查里的业务告警只给后台域名（v2 E8）：默认空 = 不发这个头，并且覆盖掉客户端自己带的同名头（不能伪造）；
# 只有后台域名的 location = /api/health 在 include 之后 set $fc_health_alerts 1。后端见 common/web/HealthController
set $fc_health_alerts "";
proxy_set_header X-Fc-Health-Alerts $fc_health_alerts;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
# 超时在各 location 里单独设（普通接口 120 秒，WebSocket 1 小时）
# S18：哪一个 server 块接的请求（site.conf.template 里的 map $server_name $fc_site）。只有对外预热域名是 prelaunch，其余为空 = 不发这个头，
# 并且覆盖掉客户端自己带的同名头（不能伪造）。后端见 common/web/PrelaunchSite
proxy_set_header X-Fc-Site $fc_site;
