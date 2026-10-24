# 前端 + Nginx 镜像（S20，部署方案 v1.1 第 4、7 节；v2 加官网）。先构建三个前端（真实接口模式，不设 VITE_API_MODE），再在仓库根目录构建：
#   npm ci --prefix web/client && npm run build --prefix web/client
#   npm ci --prefix web/admin && npm run build --prefix web/admin
#   npm ci --prefix web/site && npm run build --prefix web/site
#   docker build -f deploy/web.Dockerfile --build-arg FC_VERSION=<提交号> -t forecast-web:<提交号> .
# 里面有：客户端、后台、官网的构建产物（官网里的客户端地址由 Nginx 在运行时填 https://${APP_DOMAIN}，不在构建时写死）、Nginx 配置、服务器端部署工具包（/forecast-kit，deploy.sh 每次更新时从这里同步到服务器）。
FROM nginx:1.27-bookworm
ARG FC_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/dollar3651314/forecast" \
      org.opencontainers.image.revision="${FC_VERSION}"
# Nginx 外层限流的阈值（P-05，site.conf.template 在容器启动时替换）：比后端宽松，只防明显的洪水。
# 测试环境和生产用这里的默认值；只有压测（e2e/load/compose.load.yml）调大
ENV FC_NGINX_API_RATE=20r/s \
    FC_NGINX_API_BURST=400 \
    FC_NGINX_API_CONN=200 \
    FC_NGINX_WS_RATE=5r/s \
    FC_NGINX_WS_BURST=100 \
    FC_NGINX_WS_CONN=500
RUN rm -f /etc/nginx/conf.d/default.conf
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/site.conf.template /etc/nginx/templates/site.conf.template
COPY deploy/nginx/proxy.inc /etc/nginx/snippets/proxy.inc
COPY deploy/nginx/security.inc /etc/nginx/snippets/security.inc
COPY web/client/dist /usr/share/nginx/client
COPY web/admin/dist /usr/share/nginx/admin
COPY web/site/dist /usr/share/nginx/site
COPY deploy/kit /forecast-kit
RUN echo "${FC_VERSION}" > /forecast-kit/VERSION
